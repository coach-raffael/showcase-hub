# showcase-hub

Manager für Showcases und Demos unter **[demos.coach-raffael.com](https://demos.coach-raffael.com)** –
registrieren, ausrollen, hoch- und runterfahren, Qualitätsgate, Katalog für coach-raffael.com, MCP für Claude.

```
Showcase-Repo (öffentlich)                      showcase-hub                               VPS
──────────────────────────                      ────────────                               ───
demo.yaml ─ register_demo ───────────────────▶  Registry (Postgres)
push ─ build-showcase (Gate: build, tests,      /api/ci/builds ── grün? ── deploy-demo ──▶ /opt/demos/<slug>/
       lint, secrets, deps, images, readme) ──▶ Versionen + Gate                          compose.yml · .env
                                                     ▲            ◀── Erlebnis-Gate ──      │
Galerie /  ·  Schlafseite /<slug>  ·  Admin          │ Unix-Socket + HMAC                    ▼
MCP /api/mcp  ·  Katalog /api/catalog ──▶ Homepage   └──────────────▶ demo-agent (systemd) ─ docker compose
```

| Ordner | Inhalt |
|---|---|
| `api/` | Spring Boot 3.5 / Java 21, JDBC + Flyway, PostgreSQL: Registry, Manifest-Prüfung, Compose-Erzeugung, Gate, Lebenszyklus, Katalog, MCP |
| `web/` | Next.js 16: öffentliche Galerie (en), Schlafseite mit Wecken, Adminbereich (de) |
| `agent/` | demo-agent (Python-Standardbibliothek): Start/Stopp/Status/Logs/Reset über einen Unix-Socket |
| `.github/workflows/` | `api`, `web`, `agent`, `deploy` (Hub), `deploy-demo` (Demos), `build-showcase` (wiederverwendbar für Showcase-Repos) |
| `tools/experience/` | Erlebnis-Gate: Konsole, Links, Mobil, Screenshot (Playwright) |
| `template/` | Vorlage für neue Showcase-Repos (`demo.yaml`, Workflow, e2e, README) |
| `infra/` | `compose.prod.yml`, Einrichtung (`infra/README.md`) |

Planung: `planung/showcase-hub.md` im Claude-Projekt.

## Lokal

```bash
# API (Postgres auf 5433, Datenbank demos)
cd api && mvn spring-boot:run
# Web
cd web && cp .env.example .env.local && npm install && npm run dev
# Tests
cd api && TEST_DB_URL=jdbc:postgresql://localhost:5433/demos_test mvn verify
python3 -m unittest discover -s agent -v
```
