# CLAUDE.md – showcase-hub

Antworten an Raffael auf Deutsch. Code-Kommentare und Admin-Oberfläche Deutsch; Galerie, Schlafseite und
Katalogtexte Englisch (eigene Inhalte sind englisch).

## Unverhandelbar

- **Der Hub hat keinen Docker-Socket.** Starten/Stoppen nur über den demo-agent (Unix-Socket, HMAC, festes
  Befehlsset). Nie den Docker-Socket in `infra/compose.prod.yml` mounten, nie dem Agenten neue Befehle geben,
  die beliebige Images, Pfade oder Shell-Kommandos annehmen.
- **Öffentlich nur mit grünem Gate.** `DemoService.setVisibility` prüft Build- und Erlebnis-Gate der ausgerollten
  Version. Die Pflichtprüfungen stehen in `VersionService.BUILD_CHECKS` / `EXPERIENCE_CHECKS` – fehlende zählen
  wie rote. Diese Regeln nicht aufweichen, auch nicht „vorübergehend“.
- **Letzte grüne Version bleibt live.** Automatisch und für öffentliche Demos werden nur grüne Versionen
  ausgerollt; `deploy-demo.yml` stellt bei rotem Smoke-Test den vorherigen Stand wieder her.
- **demo.yaml ist der Demo-Vertrag.** Neue Felder in `ManifestParser` (prüfen, Fehler einzeln melden),
  `DemoManifest`, `ComposeRenderer` und `template/demo.yaml` gemeinsam ergänzen. Eigene Images nur aus
  `ghcr.io/coach-raffael/`, Basis-Images nur aus der Liste `BASE_IMAGES` mit festem Tag, keine Host-Pfade.
- **compose.yml-Ausgabe ↔ Agent:** Was `ComposeRenderer` erzeugt, muss `audit_compose` im Agenten bestehen
  (Test `ComposeRendererTest.outputPassesTheAgentsForbiddenPatterns`). Beide Seiten zusammen ändern.
- **Signatur Hub ↔ Agent:** `UnixSocketAgentClient.sign` und `demo_agent.sign` teilen einen Testvektor.
- **Eine Fachlogik für alle Kanäle.** REST, MCP und CI rufen dieselben Services; MCP-Schreibzugriffe nur über
  `McpGuard.write` (Richtlinie ALLOW/APPROVAL/DENY). Secrets nie per MCP lesen oder setzen.
- **Schema nur per neuer Flyway-Migration**, bestehende nie ändern. `demo_versions` und `audit_log` sind
  per Trigger geschützt.
- Katalog-Feed (`CatalogService.Entry`) ist ein Vertrag mit der Homepage: Felder nur ergänzen.

## Tests

- API: `cd api && TEST_DB_URL=jdbc:postgresql://localhost:5433/demos_test mvn verify` (oder `CI=true` mit
  Testcontainers). `HubFlowTest` deckt den ganzen Weg ab – jede Änderung an Gate, Deploy oder Lebenszyklus
  braucht dort einen Test.
- Agent: `python3 -m unittest discover -s agent -v` (ohne Docker, Fake-docker).
- Web: `cd web && npm run lint && npm run build`.

## Design (web/)

Style **Ridgeline** wie im CRM (Tokens in `globals.css`, plus Dark Mode). Mono für Labels/Zahlen/Buttons,
Instrument Sans für Sprache, Pills für Controls.
