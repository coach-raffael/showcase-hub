#!/usr/bin/env python3
"""demo-agent – startet und stoppt Showcase-Demos auf dem VPS im Auftrag des Showcase-Hubs.

Grundsätze
----------
* Einziger Weg vom Hub zu Docker. Der Hub selbst hat keinen Docker-Socket.
* Lauscht nur auf einem Unix-Socket (Standard /run/demo-agent/agent.sock), nie auf einem Netzwerk-Port.
  Der Socket gehört der Gruppe demo-agent; der API-Container des Hubs bekommt nur diese Gruppe.
* Jede Anfrage ist per HMAC-SHA256 signiert (Zeitstempel ± 60 s, Einmal-Nonce gegen Wiederholungen).
* Festes Befehlsset: start, stop, restart, reset, status, logs – nichts anderes.
* Nur Demos unter DEMOS_ROOT/<slug>/ mit compose.yml; Slug wird streng geprüft, kein Pfad von außen.
* Keine Shell: Befehle sind feste Argumentlisten.
* compose.yml wird vor jedem Start auf gefährliche Einstellungen geprüft (privileged, Host-Pfade, Docker-Socket,
  host-Netz …) – auch wenn der Hub sie nie erzeugt. Verteidigung in der Tiefe.

Nur Python-Standardbibliothek (Ubuntu 24.04: python3). Konfiguration über Umgebungsvariablen, siehe main().
"""

import hashlib
import hmac
import json
import os
import re
import socketserver
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlsplit

SLUG = re.compile(r"^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$")
NONCE = re.compile(r"^[A-Za-z0-9_-]{16,64}$")
SERVICE = re.compile(r"^[a-z][a-z0-9-]{0,30}$")
ACTIONS = ("start", "stop", "restart", "reset")
MAX_SKEW = 60
MAX_LOG_BYTES = 200_000

# Zeilen in compose.yml, die ein Demo-Stack nie braucht und die aus einem Container heraus den Host gefährden.
FORBIDDEN = [
    (re.compile(r"^\s*privileged\s*:\s*(true|yes)", re.I | re.M), "privileged"),
    (re.compile(r"docker\.sock"), "Docker-Socket"),
    (re.compile(r"^\s*network_mode\s*:", re.M), "network_mode"),
    (re.compile(r"^\s*(pid|ipc|uts|userns_mode)\s*:", re.M), "Host-Namespace"),
    (re.compile(r"^\s*cap_add\s*:", re.M), "cap_add"),
    (re.compile(r"^\s*devices\s*:", re.M), "devices"),
    (re.compile(r"^\s*-\s*[\"']?(/|\.|~)", re.M), "Host-Pfad als Volume"),
    (re.compile(r"^\s*source\s*:\s*[\"']?(/|\.|~)", re.M), "Host-Pfad als Volume"),
    (re.compile(r"^\s*build\s*:", re.M), "build"),
]


class Config:
    def __init__(self, socket_path, key, demos_root, docker="docker", socket_group=None, timeout=300):
        self.socket_path = socket_path
        self.key = key.encode() if isinstance(key, str) else key
        self.demos_root = os.path.realpath(demos_root)
        self.docker = docker
        self.socket_group = socket_group
        self.timeout = timeout


class AgentError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


def sign(key, ts, nonce, method, path):
    """Muss zu UnixSocketAgentClient.sign im Hub passen."""
    return hmac.new(key, f"{ts}\n{nonce}\n{method}\n{path}".encode(), hashlib.sha256).hexdigest()


def audit_compose(text):
    """Gibt die Liste der verbotenen Einstellungen zurück (leer = in Ordnung)."""
    found = []
    for pattern, label in FORBIDDEN:
        if pattern.search(text) and label not in found:
            found.append(label)
    return found


class Agent:
    def __init__(self, cfg):
        self.cfg = cfg
        self._locks = {}
        self._locks_guard = threading.Lock()
        self._seen = {}
        self._seen_guard = threading.Lock()

    # ---------- Authentifizierung ----------

    def authenticate(self, method, path, ts, nonce, signature, now=None):
        now = int(now if now is not None else time.time())
        if not ts or not signature or not ts.isdigit() or not NONCE.match(nonce or ""):
            raise AgentError(401, "Signatur fehlt")
        if abs(now - int(ts)) > MAX_SKEW:
            raise AgentError(401, "Zeitstempel außerhalb des Fensters")
        expected = sign(self.cfg.key, ts, nonce, method, path)
        if not hmac.compare_digest(expected, signature):
            raise AgentError(401, "Signatur ungültig")
        with self._seen_guard:
            for s, t in list(self._seen.items()):
                if now - t > 2 * MAX_SKEW:
                    del self._seen[s]
            if nonce in self._seen:
                raise AgentError(401, "Anfrage wiederholt")
            self._seen[nonce] = now

    # ---------- Demos ----------

    def demo_dir(self, slug):
        if not slug or not SLUG.match(slug):
            raise AgentError(400, "Slug ungültig")
        d = os.path.realpath(os.path.join(self.cfg.demos_root, slug))
        if os.path.dirname(d) != self.cfg.demos_root:
            raise AgentError(400, "Slug ungültig")
        if not os.path.isfile(os.path.join(d, "compose.yml")):
            raise AgentError(404, f"Demo {slug} ist auf dem Server nicht vorhanden – erst ausrollen")
        return d

    def slugs(self):
        try:
            names = sorted(os.listdir(self.cfg.demos_root))
        except FileNotFoundError:
            return []
        return [n for n in names if SLUG.match(n) and os.path.isfile(os.path.join(self.cfg.demos_root, n, "compose.yml"))]

    def project(self, slug, d):
        """Projektname aus agent.json – muss demo-<slug> sein, sonst könnte eine Datei fremde Stacks steuern."""
        expected = f"demo-{slug}"
        cfg = self.agent_json(d)
        if cfg.get("project", expected) != expected or cfg.get("slug", slug) != slug:
            raise AgentError(409, "agent.json passt nicht zur Demo")
        return expected

    @staticmethod
    def agent_json(d):
        p = os.path.join(d, "agent.json")
        if not os.path.isfile(p):
            return {}
        try:
            with open(p, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            raise AgentError(409, "agent.json nicht lesbar")
        return data if isinstance(data, dict) else {}

    def lock(self, slug):
        with self._locks_guard:
            return self._locks.setdefault(slug, threading.Lock())

    def compose(self, slug, d, *args, timeout=None):
        argv = [self.cfg.docker, "compose", "--project-directory", d, "-f", os.path.join(d, "compose.yml"),
                "-p", self.project(slug, d)]
        env_file = os.path.join(d, ".env")
        if os.path.isfile(env_file):
            argv += ["--env-file", env_file]
        argv += list(args)
        try:
            r = subprocess.run(argv, capture_output=True, text=True, timeout=timeout or self.cfg.timeout,
                               cwd=d)
        except subprocess.TimeoutExpired:
            raise AgentError(504, f"docker compose {args[0]} hat zu lange gedauert")
        return r

    def run(self, slug, action):
        if action not in ACTIONS:
            raise AgentError(404, "Unbekannte Aktion")
        d = self.demo_dir(slug)
        with self.lock(slug):
            if action in ("start", "restart"):
                with open(os.path.join(d, "compose.yml"), encoding="utf-8") as f:
                    problems = audit_compose(f.read())
                if problems:
                    raise AgentError(409, "compose.yml enthält Verbotenes: " + ", ".join(problems))
            if action == "start":
                r = self.compose(slug, d, "up", "-d", "--no-build", "--pull", "never")
            elif action == "stop":
                r = self.compose(slug, d, "stop", "--timeout", "20")
            elif action == "restart":
                r = self.compose(slug, d, "restart", "--timeout", "20")
            else:
                reset = self.agent_json(d).get("reset")
                if not isinstance(reset, dict):
                    raise AgentError(409, "Für diese Demo ist kein reset in demo.yaml hinterlegt")
                service, command = reset.get("service"), reset.get("command")
                if not isinstance(service, str) or not SERVICE.match(service) or not isinstance(command, list) \
                        or not command or not all(isinstance(c, str) and c for c in command):
                    raise AgentError(409, "reset in agent.json ungültig")
                r = self.compose(slug, d, "exec", "-T", service, *command)
            if r.returncode != 0:
                raise AgentError(500, f"{action} fehlgeschlagen: " + tail(r.stderr or r.stdout, 1500))
        return {"ok": True, "slug": slug, "action": action, "output": tail(r.stdout + r.stderr, 1500)}

    def status(self, slug):
        d = self.demo_dir(slug)
        r = self.compose(slug, d, "ps", "--all", "--format", "json", timeout=30)
        if r.returncode != 0:
            raise AgentError(500, "Status nicht lesbar: " + tail(r.stderr, 500))
        containers = parse_ps(r.stdout)
        stats = self.stats([c["id"] for c in containers if c.get("id") and c["state"] == "running"])
        for c in containers:
            s = stats.get(c.pop("id", ""), {})
            c["memory"] = s.get("MemUsage", "")
            c["cpu"] = s.get("CPUPerc", "")
        return {"slug": slug, "state": overall(containers), "containers": containers}

    def stats(self, ids):
        if not ids:
            return {}
        try:
            r = subprocess.run([self.cfg.docker, "stats", "--no-stream", "--format", "{{json .}}", *ids],
                               capture_output=True, text=True, timeout=30)
        except subprocess.TimeoutExpired:
            return {}
        out = {}
        for line in r.stdout.splitlines():
            try:
                s = json.loads(line)
            except ValueError:
                continue
            cid = s.get("ID") or s.get("Container") or ""
            for full in ids:
                if cid and full.startswith(cid):
                    out[full] = s
        return out

    def logs(self, slug, n):
        d = self.demo_dir(slug)
        n = max(1, min(int(n), 500))
        r = self.compose(slug, d, "logs", "--no-color", "--timestamps", "--tail", str(n), timeout=30)
        text = r.stdout + r.stderr
        if len(text) > MAX_LOG_BYTES:
            text = text[-MAX_LOG_BYTES:]
        return {"slug": slug, "logs": text}


def parse_ps(stdout):
    """docker compose ps --format json: je nach Version ein Array oder eine Zeile je Container."""
    stdout = stdout.strip()
    if not stdout:
        return []
    try:
        data = json.loads(stdout)
        items = data if isinstance(data, list) else [data]
    except ValueError:
        items = []
        for line in stdout.splitlines():
            line = line.strip()
            if line:
                try:
                    items.append(json.loads(line))
                except ValueError:
                    pass
    out = []
    for c in items:
        out.append({
            "id": c.get("ID", ""),
            "service": c.get("Service", ""),
            "state": (c.get("State") or "").lower(),
            "health": (c.get("Health") or "").lower(),
        })
    return out


def overall(containers):
    if not containers:
        return "stopped"
    states = [c["state"] for c in containers]
    if any(c["health"] == "unhealthy" for c in containers):
        return "unhealthy"
    if any(s == "restarting" for s in states):
        return "restarting"
    if all(s == "running" for s in states):
        return "running"
    if not any(s == "running" for s in states):
        return "stopped"
    return "partial"


def tail(text, n):
    text = (text or "").strip()
    return text if len(text) <= n else "…" + text[-n:]


# ---------- HTTP über Unix-Socket ----------

ROUTE = re.compile(r"^/v1/demos(?:/(?P<slug>[^/]+)(?:/(?P<action>[a-z]+))?)?$")


class Handler(BaseHTTPRequestHandler):
    agent = None  # vom Server gesetzt
    server_version = "demo-agent/1"
    protocol_version = "HTTP/1.0"

    def address_string(self):
        return "unix"

    def log_message(self, fmt, *args):
        sys.stderr.write("demo-agent: " + (fmt % args) + "\n")

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def dispatch(self, method):
        try:
            self.agent.authenticate(method, self.path, self.headers.get("X-Agent-Timestamp", ""),
                                    self.headers.get("X-Agent-Nonce", ""), self.headers.get("X-Agent-Signature", ""))
            url = urlsplit(self.path)
            m = ROUTE.match(url.path)
            if not m:
                raise AgentError(404, "Unbekannter Pfad")
            slug, action = m.group("slug"), m.group("action")
            if method == "GET" and slug is None:
                demos = []
                for s in self.agent.slugs():
                    try:
                        demos.append(self.agent.status(s))
                    except AgentError as e:
                        demos.append({"slug": s, "state": "unknown", "containers": [], "error": e.message})
                body = {"demos": demos}
            elif method == "GET" and action == "status":
                body = self.agent.status(slug)
            elif method == "GET" and action == "logs":
                n = parse_qs(url.query).get("tail", ["200"])[0]
                if not n.isdigit():
                    raise AgentError(400, "tail muss eine Zahl sein")
                body = self.agent.logs(slug, int(n))
            elif method == "POST" and action in ACTIONS:
                body = self.agent.run(slug, action)
            else:
                raise AgentError(404, "Unbekannte Aktion")
            self.reply(200, body)
        except AgentError as e:
            self.reply(e.status, {"error": e.message})
        except Exception as e:  # noqa: BLE001 – nie den Dienst abstürzen lassen
            self.log_message("Fehler: %r", e)
            self.reply(500, {"error": "interner Fehler"})

    def reply(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True


def serve(cfg):
    if os.path.exists(cfg.socket_path):
        os.unlink(cfg.socket_path)
    os.makedirs(os.path.dirname(cfg.socket_path), exist_ok=True)
    handler = type("BoundHandler", (Handler,), {"agent": Agent(cfg)})
    old = os.umask(0o117)
    try:
        server = Server(cfg.socket_path, handler)
    finally:
        os.umask(old)
    os.chmod(cfg.socket_path, 0o660)
    if cfg.socket_group is not None:
        os.chown(cfg.socket_path, -1, cfg.socket_group)
    sys.stderr.write(f"demo-agent: lausche auf {cfg.socket_path}, Demos unter {cfg.demos_root}\n")
    return server


def main():
    key_file = os.environ.get("DEMO_AGENT_KEY_FILE", "/etc/demo-agent/key")
    try:
        with open(key_file, encoding="utf-8") as f:
            key = f.read().strip()
    except OSError as e:
        sys.exit(f"demo-agent: Schlüssel {key_file} nicht lesbar: {e}")
    if len(key) < 32:
        sys.exit("demo-agent: Schlüssel zu kurz (mindestens 32 Zeichen)")
    gid = os.environ.get("DEMO_AGENT_SOCKET_GID")
    cfg = Config(
        socket_path=os.environ.get("DEMO_AGENT_SOCKET", "/run/demo-agent/agent.sock"),
        key=key,
        demos_root=os.environ.get("DEMOS_ROOT", "/opt/demos"),
        docker=os.environ.get("DOCKER_BIN", "/usr/bin/docker"),
        socket_group=int(gid) if gid else None,
    )
    server = serve(cfg)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
