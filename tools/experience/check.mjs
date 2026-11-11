// Erlebnis-Gate (Teil von deploy-demo.yml): prüft eine laufende Demo wie ein Besucher.
//
//   node check.mjs <einstiegs-url> <ausgabe.json> <screenshot.png>
//
// Prüfungen (je {name, ok, detail}):
//   console – keine Fehler in der Browser-Konsole und keine unbehandelten Ausnahmen
//   links   – alle internen Seiten (bis 25) und ihre Ressourcen antworten < 400
//   mobile  – bei 390 px Breite kein waagrechtes Scrollen
// Dazu ein Screenshot (1280×800) für Galerie und Homepage.

import { chromium } from "playwright";
import { writeFileSync } from "node:fs";

const [entry, outFile, shotFile] = process.argv.slice(2);
if (!entry || !outFile || !shotFile) {
  console.error("Aufruf: node check.mjs <url> <out.json> <shot.png>");
  process.exit(2);
}

const MAX_PAGES = 25;
const base = new URL(entry);
const prefix = base.pathname.replace(/\/$/, "");
const inScope = (u) => u.origin === base.origin && (prefix === "" || u.pathname === prefix || u.pathname.startsWith(prefix + "/"));

const consoleErrors = [];
const badResponses = [];
const browser = await chromium.launch();
const checks = [];

try {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, ignoreHTTPSErrors: false });
  const page = await ctx.newPage();
  page.on("console", (m) => {
    if (m.type() === "error") consoleErrors.push(`${page.url()}: ${m.text()}`.slice(0, 300));
  });
  page.on("pageerror", (e) => consoleErrors.push(`${page.url()}: ${e.message}`.slice(0, 300)));
  page.on("response", (r) => {
    const u = new URL(r.url());
    if (u.origin === base.origin && r.status() >= 400) badResponses.push(`${r.status()} ${u.pathname}`);
  });
  page.on("requestfailed", (r) => {
    const u = new URL(r.url());
    if (u.origin === base.origin) badResponses.push(`fehlgeschlagen ${u.pathname}: ${r.failure()?.errorText ?? ""}`);
  });

  // Seiten einsammeln (Breitensuche über interne Links)
  const queue = [base.href];
  const seen = new Set();
  while (queue.length && seen.size < MAX_PAGES) {
    const url = queue.shift();
    const key = url.split("#")[0];
    if (seen.has(key)) continue;
    seen.add(key);
    const res = await page.goto(key, { waitUntil: "networkidle", timeout: 45000 }).catch((e) => {
      badResponses.push(`nicht ladbar ${key}: ${e.message.split("\n")[0]}`);
      return null;
    });
    if (!res) continue;
    if (seen.size === 1) {
      await page.screenshot({ path: shotFile, fullPage: false });
    }
    const hrefs = await page.$$eval("a[href]", (as) => as.map((a) => a.href)).catch(() => []);
    for (const h of hrefs) {
      try {
        const u = new URL(h);
        if (inScope(u) && !seen.has(u.href.split("#")[0])) queue.push(u.href);
      } catch {
        /* keine gültige URL */
      }
    }
  }

  checks.push({
    name: "console",
    ok: consoleErrors.length === 0,
    detail: consoleErrors.length ? consoleErrors.slice(0, 10).join("\n") : `${seen.size} Seiten ohne Konsolenfehler`,
  });
  checks.push({
    name: "links",
    ok: badResponses.length === 0,
    detail: badResponses.length ? [...new Set(badResponses)].slice(0, 15).join("\n") : `${seen.size} Seiten, alle Ressourcen < 400`,
  });

  // Mobil
  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const mp = await mobile.newPage();
  await mp.goto(base.href, { waitUntil: "networkidle", timeout: 45000 });
  const width = await mp.evaluate(() => document.documentElement.scrollWidth);
  checks.push({
    name: "mobile",
    ok: width <= 391,
    detail: width <= 391 ? "kein waagrechtes Scrollen bei 390 px" : `Seite ist bei 390 px ${width} px breit`,
  });
} catch (e) {
  checks.push({ name: "console", ok: false, detail: `Prüfung abgebrochen: ${e.message}` });
} finally {
  await browser.close();
}

writeFileSync(outFile, JSON.stringify(checks, null, 2));
console.log(checks.map((c) => `${c.ok ? "✓" : "✗"} ${c.name}: ${c.detail.split("\n")[0]}`).join("\n"));
