"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { api, ApiError, type RegisterResult } from "./api";

/** Führt eine API-Änderung aus und leitet bei Fehlern mit der Meldung der API zurück (?error=…). */
async function run(back: string, fn: () => Promise<unknown>, okMessage?: string): Promise<never> {
  try {
    await fn();
  } catch (e) {
    const message = e instanceof ApiError ? e.message : "Unerwarteter Fehler";
    redirect(`${back}?error=${encodeURIComponent(message)}`);
  }
  revalidatePath(back);
  redirect(okMessage ? `${back}?ok=${encodeURIComponent(okMessage)}` : back);
}

const SLUG = /^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$/;
function slugOf(form: FormData): string {
  const slug = String(form.get("slug") ?? "");
  if (!SLUG.test(slug)) throw new Error("Slug ungültig");
  return slug;
}
function back(form: FormData, fallback: string): string {
  const b = String(form.get("back") ?? fallback);
  return b.startsWith("/admin") ? b : fallback;
}

export async function controlDemo(form: FormData) {
  const slug = slugOf(form);
  const action = String(form.get("action"));
  if (!["start", "stop", "restart", "reset"].includes(action)) throw new Error("Aktion ungültig");
  const labels: Record<string, string> = { start: "gestartet", stop: "gestoppt", restart: "neu gestartet", reset: "zurückgesetzt" };
  await run(back(form, `/admin/${slug}`), () => api(`/demos/${slug}/${action}`, { method: "POST", timeoutMs: 180_000 }),
    `${slug} ${labels[action]}`);
}

export async function deployDemo(form: FormData) {
  const slug = slugOf(form);
  const sha = String(form.get("sha") ?? "").trim();
  await run(`/admin/${slug}`, () => api(`/demos/${slug}/deploy`, { method: "POST", body: JSON.stringify({ sha: sha || null }) }),
    "Ausrollen über GitHub Actions gestartet");
}

export async function setVisibility(form: FormData) {
  const slug = slugOf(form);
  await run(`/admin/${slug}`, () =>
    api(`/demos/${slug}/visibility`, { method: "PUT", body: JSON.stringify({ visibility: form.get("visibility") }) }),
    "Sichtbarkeit geändert");
}

export async function setLifecycle(form: FormData) {
  const slug = slugOf(form);
  const num = (k: string) => (form.get(k) ? Number(form.get(k)) : null);
  await run(`/admin/${slug}`, () =>
    api(`/demos/${slug}/lifecycle`, {
      method: "PATCH",
      body: JSON.stringify({
        lifecycle: form.get("lifecycle"),
        ttlDays: num("ttlDays"),
        idleMinutes: num("idleMinutes"),
        wakePolicy: form.get("wakePolicy"),
        autoDeploy: form.get("autoDeploy") === "on",
      }),
    }),
    "Lebenszyklus gespeichert");
}

export async function extendTtl(form: FormData) {
  const slug = slugOf(form);
  await run(`/admin/${slug}`, () =>
    api(`/demos/${slug}/ttl`, { method: "POST", body: JSON.stringify({ days: Number(form.get("days")) }) }),
    "TTL verlängert");
}

export async function setRouting(form: FormData) {
  const slug = slugOf(form);
  await run(`/admin/${slug}`, () =>
    api(`/demos/${slug}/routing`, { method: "PUT", body: JSON.stringify({ routing: form.get("routing") }) }),
    "Routing geändert – wirksam ab dem nächsten Deploy");
}

export async function updateCatalog(form: FormData) {
  const slug = slugOf(form);
  await run(`/admin/${slug}`, () =>
    api(`/demos/${slug}/catalog`, {
      method: "PATCH",
      body: JSON.stringify({ sortOrder: Number(form.get("sortOrder")), featured: form.get("featured") === "on" }),
    }),
    "Katalog gespeichert");
}

export async function rereadManifest(form: FormData) {
  const slug = slugOf(form);
  await run(`/admin/${slug}`, () =>
    api(`/demos/register`, {
      method: "POST",
      body: JSON.stringify({ repo: form.get("repo"), path: form.get("path"), ref: form.get("ref") || "main" }),
    }),
    "demo.yaml neu eingelesen");
}

export async function archiveDemo(form: FormData) {
  const slug = slugOf(form);
  await run(`/admin/${slug}`, () => api(`/demos/${slug}/archive`, { method: "POST", timeoutMs: 60_000 }), "Archiviert");
}

export async function setSecret(form: FormData) {
  const slug = slugOf(form);
  const name = String(form.get("name") ?? "").trim();
  await run(`/admin/${slug}`, () =>
    api(`/demos/${slug}/secrets/${encodeURIComponent(name)}`, { method: "PUT", body: JSON.stringify({ value: form.get("value") }) }),
    `Secret ${name} gespeichert`);
}

export async function deleteSecret(form: FormData) {
  const slug = slugOf(form);
  const name = String(form.get("name") ?? "");
  await run(`/admin/${slug}`, () => api(`/demos/${slug}/secrets/${encodeURIComponent(name)}`, { method: "DELETE" }),
    `Secret ${name} gelöscht`);
}

export async function createShareLink(form: FormData) {
  const slug = slugOf(form);
  let url = "";
  try {
    const r = await api<{ url: string }>(`/demos/${slug}/share-links`, {
      method: "POST",
      body: JSON.stringify({
        label: form.get("label") || null,
        leadRef: form.get("leadRef") || null,
        validDays: form.get("validDays") ? Number(form.get("validDays")) : null,
      }),
    });
    url = r.url;
  } catch (e) {
    redirect(`/admin/${slug}?error=${encodeURIComponent(e instanceof ApiError ? e.message : "Fehler")}`);
  }
  revalidatePath(`/admin/${slug}`);
  // Das Token gibt es nur jetzt – einmal anzeigen
  redirect(`/admin/${slug}?link=${encodeURIComponent(url)}`);
}

export async function registerDemo(form: FormData) {
  let result: RegisterResult | undefined;
  try {
    result = await api<RegisterResult>(`/demos/register`, {
      method: "POST",
      body: JSON.stringify({
        repo: String(form.get("repo") ?? "").trim(),
        path: String(form.get("path") ?? "").trim() || null,
        ref: String(form.get("ref") ?? "").trim() || null,
      }),
      timeoutMs: 30_000,
    });
  } catch (e) {
    redirect(`/admin/neu?error=${encodeURIComponent(e instanceof ApiError ? e.message : "Fehler")}`);
  }
  revalidatePath("/admin");
  redirect(`/admin/${result!.demo.slug}?ok=${encodeURIComponent(result!.notes.join(" "))}`);
}

export async function upsertExternal(form: FormData) {
  const slug = String(form.get("slug") ?? "").trim();
  await run("/admin/neu", () =>
    api(`/demos/external/${encodeURIComponent(slug)}`, {
      method: "PUT",
      body: JSON.stringify({
        url: form.get("url"),
        title: { en: form.get("titleEn"), ...(form.get("titleDe") ? { de: form.get("titleDe") } : {}) },
        teaser: { en: form.get("teaserEn") ?? "", ...(form.get("teaserDe") ? { de: form.get("teaserDe") } : {}) },
        category: form.get("category"),
        tags: String(form.get("tags") ?? "").split(",").map((t) => t.trim()).filter(Boolean),
      }),
    }),
    `Eintrag ${slug} gespeichert`);
}

export async function decideApproval(form: FormData) {
  const id = Number(form.get("id"));
  const decision = form.get("decision") === "approve" ? "approve" : "reject";
  await run("/admin/freigaben", () => api(`/approvals/${id}/${decision}`, { method: "POST", timeoutMs: 180_000 }),
    decision === "approve" ? "Freigegeben" : "Abgelehnt");
}

export async function setPolicy(form: FormData) {
  const tool = String(form.get("tool"));
  await run("/admin/mcp", () =>
    api(`/mcp-policy/${encodeURIComponent(tool)}`, { method: "PUT", body: JSON.stringify({ mode: form.get("mode") }) }),
    `Richtlinie ${tool} geändert`);
}
