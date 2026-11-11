# Einrichtung – Showcase-Hub auf dem Hostinger-VPS

Gleiches Muster wie crm-tool und language-platform: globaler Traefik des Hostinger Docker Managers, Images aus
GHCR, Deploy per GitHub Actions über SSH. Neu ist der **demo-agent** (systemd), der Demos startet und stoppt.

## 1. DNS (Hostinger)

| Typ | Name | Ziel |
|---|---|---|
| A | `demos` | VPS-IP |
| A | `*.demos` | VPS-IP – nur nötig, sobald eine Demo `routing: subdomain` nutzt |

Zertifikate holt Traefik je Host per HTTP-Challenge; ein Wildcard-Zertifikat ist nicht nötig.

## 2. Server (einmalig, als root)

```bash
git clone https://github.com/coach-raffael/showcase-hub /tmp/showcase-hub
AGENT_KEY="$(openssl rand -hex 32)"; echo "AGENT_KEY=$AGENT_KEY"   # Wert für das GitHub-Secret merken
sudo AGENT_KEY="$AGENT_KEY" /tmp/showcase-hub/agent/install.sh
```

Das Skript legt an:

- Gruppe/Benutzer `demo-agent` (in `docker`), Dienst `demo-agent` (systemd, gehärtet, Socket
  `/run/demo-agent/agent.sock`, Rechte 0660, Gruppe `demo-agent`)
- Deploy-Benutzer `demos-deploy` (in `docker` und `demo-agent`) – öffentlichen Schlüssel des Deploy-Keys nach
  `~demos-deploy/.ssh/authorized_keys`
- `/opt/demos` (schreibt `demos-deploy`, liest `demo-agent`), Schlüssel `/etc/demo-agent/key`

Am Ende gibt es die **GID** der Gruppe `demo-agent` aus → GitHub-Variable `AGENT_GID`.
Außerdem anlegen: `mkdir -p /srv/showcase-hub/{data,backups} /opt/showcase-hub && chown demos-deploy /opt/showcase-hub`.

Prüfen: `systemctl status demo-agent`, `ls -l /run/demo-agent/agent.sock`.

## 3. Keycloak (Repo identity, als Code)

- Client `demos-web` (confidential, Authorization Code + PKCE), Redirect `https://demos.coach-raffael.com/auth/callback/keycloak`,
  Post-Logout `https://demos.coach-raffael.com/*`, Audience-Mapper → `demos-api`
- Client `demos-mcp` für den Claude-Connector (wie `crm-mcp`), Audience `demos-api`
- Realm-Rolle `demos-admin` → Raffael

## 4. GitHub

**Repo showcase-hub → Secrets (Environment `production`)**

| Secret | Inhalt |
|---|---|
| `VPS_HOST`, `VPS_SSH_KEY`, `VPS_KNOWN_HOSTS` | wie bei crm-tool, Benutzer `demos-deploy` |
| `DB_PASSWORD` | `openssl rand -hex 24` |
| `AUTH_SECRET` | `openssl rand -hex 32` |
| `KEYCLOAK_CLIENT_SECRET` | Secret des Clients `demos-web` |
| `HUB_SECRET_KEY` | `openssl rand -base64 32` – verschlüsselt Demo-Secrets in der DB (nie wechseln) |
| `HUB_CI_TOKEN` | `echo "hubci_$(openssl rand -hex 24)"` – Builds/Deploys melden |
| `HUB_GITHUB_TOKEN` | Fine-grained PAT (Org coach-raffael): **Actions: write** auf `showcase-hub`, **Contents: write** auf `portfolio-page-raffael` |
| `AGENT_KEY` | Wert aus Schritt 2 |

**Variablen:** `AGENT_GID` (aus Schritt 2), `HOMEPAGE_REPO` = `coach-raffael/portfolio-page-raffael` (leer = aus),
`DEPLOY_ENABLED` = `true`, sobald der erste manuelle Deploy lief.

**Organisation → Secrets:** `SHOWCASE_HUB_TOKEN` = derselbe Wert wie `HUB_CI_TOKEN`. Bei GitHub Free stehen
Org-Secrets öffentlichen Repos zur Verfügung – Showcases sind öffentlich, passt.

**Organisation → Packages:** Images öffentlicher Showcase-Repos sind öffentlich; der VPS braucht für Demos
keinen Pull-Token. Das Hub-Image zieht `deploy.yml` mit dem `GITHUB_TOKEN`.

## 5. Erster Start

1. Actions → `deploy` → Run workflow (`latest`).
2. https://demos.coach-raffael.com → Galerie (Langtutor ist schon als Tool eingetragen).
3. https://demos.coach-raffael.com/admin → Anmelden (Rolle `demos-admin`).
4. Claude: benutzerdefinierter Connector `https://demos.coach-raffael.com/api/mcp` (OAuth über Keycloak).

## 6. Erste Demo

1. Repo aus `template/` anlegen (bzw. aus dem Template-Repo `demo-template`), `demo.yaml` anpassen, öffentlich.
2. Hub: „Hinzufügen“ → Repo nennen (oder Claude: `register_demo`).
3. Secrets aus `env_required` im Hub setzen.
4. Push auf `main` → `build-showcase` → grünes Gate → Hub rollt aus → `deploy-demo` → Erlebnis-Gate.
5. Alles grün → „Öffentlich“ (bzw. `set_visibility`, Freigabe) → Homepage baut neu.

## Homepage (coach-raffael.com)

Im Repo `portfolio-page-raffael` ein Workflow auf `repository_dispatch` (`showcase-catalog-changed`) und
`push`: beim Build `GET https://demos.coach-raffael.com/api/catalog` lesen und die Einträge unter
Showcases/Tools rendern (`category: tool` → Tools). Felder siehe `CatalogService.Entry`;
Screenshot: `screenshotUrl`. Fällt der Hub aus, bleibt die zuletzt gebaute Homepage stehen.

## Betrieb

- Demo-Container: Compose-Projekte `demo-<slug>` unter `/opt/demos/<slug>/` (`compose.yml`, `.env`, `agent.json`).
- Ressourcen: je Dienst `mem_limit` aus demo.yaml (höchstens 2 GB, 3 GB je Demo); on-demand-Demos schlafen.
- Backups: täglicher `pg_dump` nach `/srv/showcase-hub/backups` (Daten der Demos selbst sind Demo-Daten mit Reset).
- Logs des Agenten: `journalctl -u demo-agent`.
