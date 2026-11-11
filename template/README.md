# demo-template

Vorlage für neue Showcase-Repos. Aus diesem Ordner wird das GitHub-Template-Repo
`coach-raffael/demo-template` (Settings → „Template repository“). Neue Showcases: „Use this template“,
dann `demo.yaml`, `README.md` und `e2e/` anpassen.

Was drin ist:

| Datei | Zweck |
|---|---|
| `demo.yaml` | Demo-Vertrag – der Hub liest ihn bei `register_demo` und bei jedem Build |
| `.github/workflows/showcase.yml` | ruft `build-showcase` im Hub auf: Gate, Images, Meldung an den Hub |
| `e2e/` | Playwright-Kernabläufe für das Erlebnis-Gate (`quality.flows`) |
| `README.showcase.md` | README-Gerüst, das das Gate besteht → nach `README.md` umbenennen |

Konventionen:

- Jeder eigene Dienst hat ein Dockerfile in `<dienst>/` und liest `BASE_PATH` (Build-Arg und Laufzeit):
  Next.js `basePath: process.env.BASE_PATH`, Spring `server.servlet.context-path: ${BASE_PATH:}` – bzw. für
  APIs unter `/api` den Pfad `${BASE_PATH}/api`.
- Cookies mit Präfix `DEMO_COOKIE_PREFIX` (alle Demos teilen sich einen Origin).
- Demo-Daten fiktiv, Seed + Reset-Befehl (`reset` in demo.yaml), kein echtes Secret im Repo.
- Ein sichtbares Demo-Banner („Showcase – data resets nightly“).
- Repo öffentlich. Secret `SHOWCASE_HUB_TOKEN` (= `HUB_CI_TOKEN` des Hubs) im neuen Repo setzen:
  `gh secret set SHOWCASE_HUB_TOKEN --repo coach-raffael/<repo>` – coach-raffael ist ein Benutzerkonto, Org-Secrets gibt es dort nicht.
