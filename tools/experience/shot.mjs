// Screenshot einer Seite für Galerie und Homepage (screenshot-externals.yml) – gleiches Format wie beim
// Erlebnis-Gate der verwalteten Demos: 1280×800, nur der sichtbare Bereich, PNG.
//
//   node shot.mjs <url> <shot.png>
//
// Wartet auf networkidle (höchstens 45 s, sonst reicht „load“) und kurz auf Einblend-Animationen.
// Exit 1, wenn die Seite nicht lädt oder mit einem Fehlerstatus antwortet – dann bleibt der alte Screenshot.

import { chromium } from "playwright";
import { statSync } from "node:fs";

const [url, shotFile] = process.argv.slice(2);
if (!url || !shotFile) {
  console.error("Aufruf: node shot.mjs <url> <shot.png>");
  process.exit(2);
}

const MAX_BYTES = 2 * 1024 * 1024; // Grenze des Hubs (ScreenshotStore)
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1, locale: "en-US" });
  let res = await page.goto(url, { waitUntil: "networkidle", timeout: 45000 }).catch(() => null);
  if (!res) res = await page.goto(url, { waitUntil: "load", timeout: 45000 });
  if (!res || res.status() >= 400) throw new Error(`HTTP ${res?.status() ?? "?"}`);
  await page.waitForTimeout(1500);
  await page.screenshot({ path: shotFile, fullPage: false });
  const size = statSync(shotFile).size;
  if (size > MAX_BYTES) throw new Error(`Screenshot zu groß (${size} Bytes)`);
  console.log(`✓ ${url} → ${shotFile} (${Math.round(size / 1024)} KB)`);
} catch (e) {
  console.error(`✗ ${url}: ${e.message.split("\n")[0]}`);
  process.exitCode = 1;
} finally {
  await browser.close();
}
