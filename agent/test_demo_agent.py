"""Tests für den demo-agent – laufen ohne Docker gegen ein Fake-docker, das seine Argumente protokolliert.

    python3 -m unittest discover -s agent -v
"""

import http.client
import json
import os
import shutil
import socket
import stat
import tempfile
import threading
import time
import unittest
import uuid

import demo_agent as da

KEY = "k" * 40

FAKE_DOCKER = r"""#!/bin/sh
echo "$@" >> "$FAKE_LOG"
case "$*" in
  *" ps "*) printf '%s\n' '{"ID":"abc123","Service":"web","State":"running","Health":"healthy"}' '{"ID":"def456","Service":"db","State":"running","Health":""}' ;;
  "stats "*) printf '%s\n' '{"ID":"abc123","MemUsage":"120MiB / 384MiB","CPUPerc":"0.50%"}' ;;
  *" logs "*) echo "web-1 | hello" ;;
  *" up "*) [ -n "$FAKE_FAIL" ] && { echo "boom" >&2; exit 1; }; echo started ;;
  *) echo ok ;;
esac
"""

GOOD_COMPOSE = """name: demo-shop
services:
  web:
    image: ghcr.io/coach-raffael/shop-web@sha256:aa
    security_opt:
      - no-new-privileges:true
    volumes:
      - web-data:/data
    networks:
      - internal
networks:
  internal: {}
volumes:
  web-data: {}
"""


class UnixHTTPConnection(http.client.HTTPConnection):
    def __init__(self, path):
        super().__init__("demo-agent")
        self.unix_path = path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect(self.unix_path)


class AgentTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.root = os.path.join(self.tmp, "demos")
        os.makedirs(os.path.join(self.root, "shop"))
        with open(os.path.join(self.root, "shop", "compose.yml"), "w") as f:
            f.write(GOOD_COMPOSE)
        with open(os.path.join(self.root, "shop", "agent.json"), "w") as f:
            json.dump({"slug": "shop", "project": "demo-shop",
                       "reset": {"service": "web", "command": ["node", "reset.js"]}}, f)
        self.docker = os.path.join(self.tmp, "docker")
        with open(self.docker, "w") as f:
            f.write(FAKE_DOCKER)
        os.chmod(self.docker, os.stat(self.docker).st_mode | stat.S_IEXEC)
        self.log = os.path.join(self.tmp, "calls.log")
        os.environ["FAKE_LOG"] = self.log
        os.environ.pop("FAKE_FAIL", None)
        self.sock = os.path.join(self.tmp, "run", "agent.sock")
        self.cfg = da.Config(self.sock, KEY, self.root, docker=self.docker)
        self.server = da.serve(self.cfg)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        shutil.rmtree(self.tmp)

    def call(self, method, path, key=KEY, ts=None, signature=None, nonce=None):
        ts = str(int(time.time())) if ts is None else ts
        nonce = nonce or uuid.uuid4().hex
        sig = signature or da.sign(key.encode(), ts, nonce, method, path)
        c = UnixHTTPConnection(self.sock)
        c.request(method, path, headers={"X-Agent-Timestamp": ts, "X-Agent-Nonce": nonce, "X-Agent-Signature": sig})
        r = c.getresponse()
        body = json.loads(r.read())
        c.close()
        return r.status, body

    def calls(self):
        if not os.path.exists(self.log):
            return []
        with open(self.log) as f:
            return [l.strip() for l in f]

    # ---------- Socket & Authentifizierung ----------

    def test_socket_is_not_world_accessible(self):
        mode = stat.S_IMODE(os.stat(self.sock).st_mode)
        self.assertEqual(mode & 0o007, 0, oct(mode))

    def test_rejects_missing_wrong_old_and_replayed_signatures(self):
        self.assertEqual(self.call("GET", "/v1/demos", signature="00")[0], 401)
        self.assertEqual(self.call("GET", "/v1/demos", key="x" * 40)[0], 401)
        self.assertEqual(self.call("GET", "/v1/demos", ts=str(int(time.time()) - 300))[0], 401)
        ts = str(int(time.time()))
        nonce = uuid.uuid4().hex
        sig = da.sign(KEY.encode(), ts, nonce, "GET", "/v1/demos")
        self.assertEqual(self.call("GET", "/v1/demos", ts=ts, signature=sig, nonce=nonce)[0], 200)
        self.assertEqual(self.call("GET", "/v1/demos", ts=ts, signature=sig, nonce=nonce)[0], 401)
        self.assertEqual(self.call("GET", "/v1/demos", nonce="short")[0], 401)

    def test_signature_binds_method_and_path(self):
        ts = str(int(time.time()))
        nonce = uuid.uuid4().hex
        sig = da.sign(KEY.encode(), ts, nonce, "GET", "/v1/demos/shop/status")
        self.assertEqual(self.call("POST", "/v1/demos/shop/stop", ts=ts, signature=sig, nonce=nonce)[0], 401)
        self.assertEqual(self.calls(), [])

    # ---------- Befehle ----------

    def test_start_runs_fixed_compose_command(self):
        status, body = self.call("POST", "/v1/demos/shop/start")
        self.assertEqual(status, 200, body)
        line = self.calls()[-1]
        self.assertIn("compose --project-directory", line)
        self.assertIn("-p demo-shop", line)
        self.assertTrue(line.endswith("up -d --no-build --pull never"), line)

    def test_stop_restart_reset(self):
        self.assertEqual(self.call("POST", "/v1/demos/shop/stop")[0], 200)
        self.assertTrue(self.calls()[-1].endswith("stop --timeout 20"))
        self.assertEqual(self.call("POST", "/v1/demos/shop/restart")[0], 200)
        self.assertEqual(self.call("POST", "/v1/demos/shop/reset")[0], 200)
        self.assertTrue(self.calls()[-1].endswith("exec -T web node reset.js"), self.calls()[-1])

    def test_unknown_action_and_get_on_action_rejected(self):
        self.assertEqual(self.call("POST", "/v1/demos/shop/rm")[0], 404)
        self.assertEqual(self.call("GET", "/v1/demos/shop/start")[0], 404)
        self.assertEqual(self.calls(), [])

    def test_failed_command_reports_error(self):
        os.environ["FAKE_FAIL"] = "1"
        status, body = self.call("POST", "/v1/demos/shop/start")
        self.assertEqual(status, 500)
        self.assertIn("boom", body["error"])

    def test_status_and_logs(self):
        status, body = self.call("GET", "/v1/demos/shop/status")
        self.assertEqual(status, 200, body)
        self.assertEqual(body["state"], "running")
        web = next(c for c in body["containers"] if c["service"] == "web")
        self.assertEqual(web["memory"], "120MiB / 384MiB")
        status, body = self.call("GET", "/v1/demos/shop/logs?tail=50")
        self.assertEqual(status, 200)
        self.assertIn("hello", body["logs"])
        self.assertIn("--tail 50", self.calls()[-1])
        self.assertEqual(self.call("GET", "/v1/demos/shop/logs?tail=abc")[0], 400)

    def test_list_all(self):
        status, body = self.call("GET", "/v1/demos")
        self.assertEqual(status, 200)
        self.assertEqual([d["slug"] for d in body["demos"]], ["shop"])

    # ---------- Grenzen ----------

    def test_rejects_traversal_and_unknown_demos(self):
        self.assertEqual(self.call("POST", "/v1/demos/..%2Fetc/start")[0], 400)
        self.assertEqual(self.call("POST", "/v1/demos/UPPER/start")[0], 400)
        self.assertEqual(self.call("POST", "/v1/demos/other/start")[0], 404)
        self.assertEqual(self.calls(), [])

    def test_refuses_dangerous_compose(self):
        for bad in ["    privileged: true\n", "      - /var/run/docker.sock:/var/run/docker.sock\n",
                    "    network_mode: host\n", "      - ./src:/app\n", "    cap_add:\n      - SYS_ADMIN\n",
                    "    pid: host\n"]:
            with open(os.path.join(self.root, "shop", "compose.yml"), "w") as f:
                f.write(GOOD_COMPOSE + "  evil:\n    image: x\n" + bad)
            status, body = self.call("POST", "/v1/demos/shop/start")
            self.assertEqual(status, 409, bad)
            self.assertIn("Verbotenes", body["error"])
        self.assertEqual(self.calls(), [])

    def test_agent_json_must_match_slug(self):
        with open(os.path.join(self.root, "shop", "agent.json"), "w") as f:
            json.dump({"slug": "shop", "project": "crm-tool"}, f)
        self.assertEqual(self.call("POST", "/v1/demos/shop/stop")[0], 409)
        self.assertEqual(self.calls(), [])

    def test_reset_requires_config(self):
        with open(os.path.join(self.root, "shop", "agent.json"), "w") as f:
            json.dump({"slug": "shop", "project": "demo-shop"}, f)
        self.assertEqual(self.call("POST", "/v1/demos/shop/reset")[0], 409)


class PureFunctionsTest(unittest.TestCase):

    def test_audit_compose_accepts_hub_output(self):
        self.assertEqual(da.audit_compose(GOOD_COMPOSE), [])

    def test_overall(self):
        self.assertEqual(da.overall([]), "stopped")
        self.assertEqual(da.overall([{"state": "running", "health": ""}, {"state": "exited", "health": ""}]), "partial")
        self.assertEqual(da.overall([{"state": "running", "health": "unhealthy"}]), "unhealthy")
        self.assertEqual(da.overall([{"state": "exited", "health": ""}]), "stopped")

    def test_parse_ps_both_formats(self):
        arr = '[{"ID":"1","Service":"web","State":"running"}]'
        lines = '{"ID":"1","Service":"web","State":"running"}\n{"ID":"2","Service":"db","State":"exited"}'
        self.assertEqual(len(da.parse_ps(arr)), 1)
        self.assertEqual([c["state"] for c in da.parse_ps(lines)], ["running", "exited"])

    def test_known_signature_vector(self):
        # Gemeinsamer Testvektor mit UnixSocketAgentClientTest (Java)
        self.assertEqual(da.sign(b"secret", "1700000000", "n0nce-0123456789ab", "POST", "/v1/demos/shop/start"), SIGNATURE_VECTOR)


# HMAC-SHA256("secret", "1700000000\nn0nce-0123456789ab\nPOST\n/v1/demos/shop/start"), unabhängig mit openssl berechnet
SIGNATURE_VECTOR = "50fa117d9d80da0432d46d69e066342086bf1d67977750f8d4d83b61da7c50a3"


if __name__ == "__main__":
    unittest.main()
