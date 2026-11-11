#!/usr/bin/env bash
# Richtet den demo-agent auf dem VPS ein (einmalig, als root; erneut ausführen = Aktualisieren).
#
#   sudo AGENT_KEY='<derselbe Wert wie das Secret AGENT_KEY im Hub>' ./agent/install.sh
#
# Legt an: Gruppe/Benutzer demo-agent, /opt/demos (Deploy-Benutzer demos-deploy schreibt, Agent liest),
# Schlüssel /etc/demo-agent/key, systemd-Dienst demo-agent. Gibt am Ende die GID für AGENT_GID aus.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Bitte als root ausführen (sudo)." >&2; exit 1; }
here="$(cd "$(dirname "$0")" && pwd)"
deploy_user="${DEPLOY_USER:-demos-deploy}"

getent group demo-agent >/dev/null || groupadd --system demo-agent
id demo-agent >/dev/null 2>&1 || useradd --system --gid demo-agent --no-create-home --shell /usr/sbin/nologin demo-agent
usermod -aG docker demo-agent

if ! id "$deploy_user" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$deploy_user"
  echo "Benutzer $deploy_user angelegt – öffentlichen Deploy-Schlüssel nach ~$deploy_user/.ssh/authorized_keys legen."
fi
usermod -aG docker,demo-agent "$deploy_user"

# Demos: Deploy-Benutzer schreibt, Gruppe demo-agent liest (setgid vererbt die Gruppe auf neue Ordner)
install -d -m 2750 -o "$deploy_user" -g demo-agent /opt/demos

install -d -m 0750 -o root -g demo-agent /etc/demo-agent
if [[ -n "${AGENT_KEY:-}" ]]; then
  [[ ${#AGENT_KEY} -ge 32 ]] || { echo "AGENT_KEY ist kürzer als 32 Zeichen." >&2; exit 1; }
  umask 027
  printf '%s\n' "$AGENT_KEY" > /etc/demo-agent/key
elif [[ ! -f /etc/demo-agent/key ]]; then
  echo "AGENT_KEY fehlt (beim ersten Einrichten Pflicht)." >&2
  exit 1
fi
chown root:demo-agent /etc/demo-agent/key
chmod 0640 /etc/demo-agent/key

gid="$(getent group demo-agent | cut -d: -f3)"
printf 'DEMO_AGENT_SOCKET_GID=%s\n' "$gid" > /etc/demo-agent/env

install -d -m 0755 /usr/local/lib/demo-agent
install -m 0644 "$here/demo_agent.py" /usr/local/lib/demo-agent/demo_agent.py
install -m 0644 "$here/demo-agent.service" /etc/systemd/system/demo-agent.service
systemctl daemon-reload
systemctl enable --now demo-agent
systemctl restart demo-agent
sleep 1
systemctl --no-pager --lines=5 status demo-agent || true

echo
echo "Fertig. Für den Hub (GitHub-Variable AGENT_GID): $gid"
