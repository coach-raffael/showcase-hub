import Link from "next/link";
import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { api, ApiError, type DemoDetail, type DemoStatus, type DemoVersion, type Gate } from "@/lib/api";
import {
  archiveDemo,
  controlDemo,
  createShareLink,
  deleteSecret,
  deployDemo,
  extendTtl,
  requestScreenshot,
  rereadManifest,
  setLifecycle,
  setRouting,
  setSecret,
  setVisibility,
  updateCatalog,
} from "@/lib/actions";
import { LIFECYCLE_LABEL, pick, short, when } from "@/lib/format";
import { Banner, Card, CardHead, Chip, GateChip, PageHead, StateChip, VisibilityChip } from "@/components/ui";
import { SubmitButton } from "@/components/submit-button";

export const dynamic = "force-dynamic";

type Props = {
  params: Promise<{ slug: string }>;
  searchParams: Promise<{ error?: string; ok?: string; link?: string }>;
};

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  return { title: (await params).slug };
}

async function load(slug: string) {
  try {
    return await api<DemoDetail>(`/demos/${encodeURIComponent(slug)}`);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) notFound();
    throw e;
  }
}

async function status(slug: string): Promise<DemoStatus | string> {
  try {
    return await api<DemoStatus>(`/demos/${slug}/status`, { timeoutMs: 20_000 });
  } catch (e) {
    return e instanceof ApiError ? e.message : "Status nicht abrufbar";
  }
}

export default async function DemoPage({ params, searchParams }: Props) {
  const { slug } = await params;
  const { error, ok, link } = await searchParams;
  const detail = await load(slug);
  const d = detail.demo;
  const managed = d.kind === "managed";
  const live = managed && d.state !== "new" ? await status(slug) : null;
  const hidden = <input type="hidden" name="slug" value={slug} />;

  return (
    <>
      <PageHead index="02" eyebrow={`${slug} · ${managed ? d.sourceRepo : "externer Eintrag"}`} title={pick(d.title)}>
        <a href={d.url} target="_blank" rel="noopener" className="pill pill-soft">
          Öffnen ↗
        </a>
        {managed && (
          <a href={`https://github.com/${d.sourceRepo}`} target="_blank" rel="noopener" className="pill pill-soft">
            Repo ↗
          </a>
        )}
      </PageHead>
      <Banner error={error} ok={ok} />
      {link && (
        <div className="mb-6 rounded-[var(--radius-pop)] bg-ok-soft px-5 py-4 text-ok">
          <p className="label mb-2 text-ok">Share-Link – nur jetzt sichtbar, bitte kopieren</p>
          <code className="code select-all break-all">{link}</code>
        </div>
      )}

      <div className="mb-6 flex flex-wrap items-center gap-2">
        {managed ? <StateChip state={d.state} /> : <Chip>extern</Chip>}
        <VisibilityChip visibility={d.visibility} />
        {managed && <GateChip version={detail.currentVersion} />}
        {managed && <Chip>{LIFECYCLE_LABEL[d.lifecycle]}</Chip>}
        {d.expiresAt && <Chip tone="warn">läuft ab {when(d.expiresAt)}</Chip>}
      </div>

      <div className="grid gap-6 xl:grid-cols-2">
        {managed && (
          <Card>
            <CardHead title="Betrieb">
              <Link href={`/admin/${slug}/logs`} className="pill pill-soft pill-sm">
                Logs
              </Link>
            </CardHead>
            <div className="space-y-5 p-5 sm:p-6">
              <div className="flex flex-wrap gap-2">
                {(["start", "stop", "restart", "reset"] as const).map((a) => (
                  <form key={a} action={controlDemo}>
                    {hidden}
                    <input type="hidden" name="action" value={a} />
                    <SubmitButton className={`pill pill-sm ${a === "start" ? "pill-deep" : "pill-soft"}`}>
                      {{ start: "Starten", stop: "Stoppen", restart: "Neu starten", reset: "Zurücksetzen" }[a]}
                    </SubmitButton>
                  </form>
                ))}
              </div>
              {typeof live === "string" && <p className="text-[14px] text-muted">{live}</p>}
              {live && typeof live !== "string" && (
                <table className="w-full text-left text-[14px]">
                  <thead>
                    <tr className="label">
                      <th className="pb-2 font-medium">Dienst</th>
                      <th className="pb-2 font-medium">Zustand</th>
                      <th className="pb-2 font-medium">RAM</th>
                      <th className="pb-2 font-medium">CPU</th>
                    </tr>
                  </thead>
                  <tbody className="num">
                    {live.containers.map((c) => (
                      <tr key={c.service} className="border-t border-line">
                        <td className="py-2 font-sans">{c.service}</td>
                        <td className="py-2">
                          {c.state}
                          {c.health && ` · ${c.health}`}
                        </td>
                        <td className="py-2">{c.memory || "–"}</td>
                        <td className="py-2">{c.cpu || "–"}</td>
                      </tr>
                    ))}
                    {live.containers.length === 0 && (
                      <tr>
                        <td colSpan={4} className="py-2 font-sans text-muted">
                          Keine Container – Demo schläft.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              )}
              <form action={deployDemo} className="flex flex-wrap items-center gap-2 border-t border-line pt-5">
                {hidden}
                <input name="sha" placeholder="Commit (leer = neueste grüne Version)" className="field max-w-xs" />
                <SubmitButton className="pill pill-deep">Ausrollen</SubmitButton>
              </form>
            </div>
          </Card>
        )}

        <Card>
          <CardHead title="Sichtbarkeit" />
          <form action={setVisibility} className="space-y-4 p-5 sm:p-6">
            {hidden}
            <div className="flex flex-wrap gap-2">
              {(["public", "unlisted", "private"] as const).map((v) => (
                <label key={v} className="pill pill-soft pill-sm has-[:checked]:bg-navy has-[:checked]:text-white">
                  <input type="radio" name="visibility" value={v} defaultChecked={d.visibility === v} className="sr-only" />
                  {{ public: "Öffentlich", unlisted: "Nur per Link", private: "Privat" }[v]}
                </label>
              ))}
            </div>
            {managed && (
              <p className="text-[14px] text-muted">
                Öffentlich (Galerie + coach-raffael.com) nur, wenn die ausgerollte Version Build- und Erlebnis-Gate bestanden hat.
              </p>
            )}
            <SubmitButton className="pill pill-deep pill-sm">Speichern</SubmitButton>
          </form>
        </Card>

        {!managed && (
          <Card>
            <CardHead title="Screenshot" />
            <div className="space-y-4 p-5 sm:p-6">
              {d.screenshotUrl && d.visibility === "public" ? (
                // eslint-disable-next-line @next/next/no-img-element -- Bild kommt aus dem eigenen Katalog-Endpunkt
                <img src={d.screenshotUrl} alt={`Screenshot ${pick(d.title)}`} width={1280} height={800}
                  className="w-full rounded-[var(--radius-pop)] border border-line" />
              ) : (
                <p className="text-[14px] text-muted">
                  {d.screenshotUrl ? "Screenshot vorhanden – Vorschau, sobald der Eintrag öffentlich ist." : "Noch kein Screenshot."}
                </p>
              )}
              <p className="text-[14px] text-muted">
                Playwright fotografiert {d.url} (1280×800). Automatisch beim Anlegen, bei neuer Adresse und wöchentlich.
              </p>
              <form action={requestScreenshot}>
                {hidden}
                <SubmitButton className="pill pill-deep pill-sm">Screenshot aufnehmen</SubmitButton>
              </form>
            </div>
          </Card>
        )}

        {managed && detail.versions && (
          <Card className="xl:col-span-2">
            <CardHead title="Versionen & Qualitätsgate" />
            <ul className="divide-y divide-line">
              {detail.versions.map((v) => (
                <VersionRow key={v.id} v={v} current={v.id === d.currentVersionId} slug={slug} />
              ))}
              {detail.versions.length === 0 && (
                <li className="px-6 py-6 text-muted">
                  Noch kein Build gemeldet. Im Repo den Workflow build-showcase einbinden (Vorlage: template/).
                </li>
              )}
            </ul>
          </Card>
        )}

        {managed && (
          <Card>
            <CardHead title="Lebenszyklus" />
            <form action={setLifecycle} className="grid gap-4 p-5 sm:grid-cols-2 sm:p-6">
              {hidden}
              <label className="space-y-1.5">
                <span className="label">Art</span>
                <select name="lifecycle" defaultValue={d.lifecycle} className="field">
                  <option value="on-demand">bei Bedarf (schläft)</option>
                  <option value="permanent">permanent</option>
                  <option value="ttl">befristet (TTL)</option>
                </select>
              </label>
              <label className="space-y-1.5">
                <span className="label">Schlafen nach (Min.)</span>
                <input name="idleMinutes" type="number" min={5} max={1440} defaultValue={d.idleMinutes} className="field" />
              </label>
              <label className="space-y-1.5">
                <span className="label">TTL (Tage)</span>
                <input name="ttlDays" type="number" min={1} max={365} defaultValue={d.ttlDays ?? ""} className="field" />
              </label>
              <label className="space-y-1.5">
                <span className="label">Wecken durch</span>
                <select name="wakePolicy" defaultValue={d.wakePolicy} className="field">
                  <option value="anyone">alle Besucher</option>
                  <option value="share_link">nur Share-Link</option>
                  <option value="admin">nur mich</option>
                </select>
              </label>
              <label className="flex items-center gap-2 sm:col-span-2">
                <input type="checkbox" name="autoDeploy" defaultChecked={d.autoDeploy} className="size-4" />
                <span className="text-[15px]">Grüne Builds automatisch ausrollen</span>
              </label>
              <div className="sm:col-span-2">
                <SubmitButton className="pill pill-deep pill-sm">Speichern</SubmitButton>
              </div>
            </form>
            <div className="flex flex-wrap gap-6 border-t border-line p-5 sm:p-6">
              <form action={extendTtl} className="flex items-center gap-2">
                {hidden}
                <input name="days" type="number" min={1} max={365} defaultValue={14} className="field w-24" />
                <SubmitButton className="pill pill-soft pill-sm">TTL verlängern</SubmitButton>
              </form>
              <form action={setRouting} className="flex items-center gap-2">
                {hidden}
                <select name="routing" defaultValue={d.routing} className="field w-40">
                  <option value="path">Pfad</option>
                  <option value="subdomain">Subdomain</option>
                </select>
                <SubmitButton className="pill pill-soft pill-sm">Routing</SubmitButton>
              </form>
            </div>
          </Card>
        )}

        <Card>
          <CardHead title="Katalog" />
          <form action={updateCatalog} className="flex flex-wrap items-end gap-4 p-5 sm:p-6">
            {hidden}
            <label className="space-y-1.5">
              <span className="label">Reihenfolge</span>
              <input name="sortOrder" type="number" defaultValue={d.sortOrder} className="field w-28" />
            </label>
            <label className="flex h-11 items-center gap-2">
              <input type="checkbox" name="featured" defaultChecked={d.featured} className="size-4" />
              <span className="text-[15px]">Hervorgehoben</span>
            </label>
            <SubmitButton className="pill pill-deep pill-sm">Speichern</SubmitButton>
          </form>
          <div className="space-y-3 border-t border-line p-5 text-[14px] sm:p-6">
            <p className="text-muted">{pick(d.teaser) || "Kein Teaser."}</p>
            <div className="flex flex-wrap gap-1.5">
              {d.tags.map((t) => (
                <Chip key={t}>{t}</Chip>
              ))}
            </div>
            {managed && (
              <form action={rereadManifest} className="flex flex-wrap items-center gap-2 pt-2">
                {hidden}
                <input type="hidden" name="repo" value={d.sourceRepo} />
                <input type="hidden" name="path" value={d.sourcePath} />
                <p className="text-muted">Texte, Tags und Lebenszyklus stehen in {d.sourcePath}.</p>
                <SubmitButton className="pill pill-soft pill-sm">Neu einlesen</SubmitButton>
              </form>
            )}
          </div>
        </Card>

        {managed && detail.secrets && (
          <Card>
            <CardHead title="Secrets" />
            <ul className="divide-y divide-line">
              {detail.secrets.map((s) => (
                <li key={s.name} className="flex flex-wrap items-center gap-3 px-5 py-3 sm:px-6">
                  <code className="code flex-1">{s.name}</code>
                  {s.set ? <Chip tone="ok">gesetzt</Chip> : <Chip tone="bad">fehlt</Chip>}
                  {!s.required && <Chip>nicht mehr verlangt</Chip>}
                  {s.set && (
                    <form action={deleteSecret}>
                      {hidden}
                      <input type="hidden" name="name" value={s.name} />
                      <SubmitButton className="pill pill-soft pill-sm">Löschen</SubmitButton>
                    </form>
                  )}
                </li>
              ))}
            </ul>
            <form action={setSecret} className="flex flex-wrap gap-2 border-t border-line p-5 sm:p-6">
              {hidden}
              <input name="name" placeholder="NAME" required className="field w-48 font-mono" />
              <input name="value" type="password" placeholder="Wert" required autoComplete="off" className="field min-w-0 flex-1" />
              <SubmitButton className="pill pill-deep pill-sm">Setzen</SubmitButton>
            </form>
          </Card>
        )}

        <Card>
          <CardHead title="Share-Links" />
          <form action={createShareLink} className="grid gap-3 p-5 sm:grid-cols-[1fr_1fr_7rem_auto] sm:p-6">
            {hidden}
            <input name="label" placeholder="Für (Firma/Person)" className="field" />
            <input name="leadRef" placeholder="Lead im CRM" className="field" />
            <input name="validDays" type="number" min={1} max={365} placeholder="Tage" className="field" />
            <SubmitButton className="pill pill-deep pill-sm">Erzeugen</SubmitButton>
          </form>
          <ul className="divide-y divide-line border-t border-line">
            {detail.shareLinks.map((l) => (
              <li key={l.id} className="flex flex-wrap items-center justify-between gap-2 px-5 py-3 text-[14px] sm:px-6">
                <span>
                  {l.label ?? "ohne Bezeichnung"}
                  {l.leadRef && <span className="text-muted"> · {l.leadRef}</span>}
                </span>
                <span className="label">
                  {l.uses}× · zuletzt {when(l.lastUsedAt)}
                  {l.expiresAt && ` · bis ${when(l.expiresAt)}`}
                </span>
              </li>
            ))}
          </ul>
        </Card>

        <Card className="xl:col-span-2">
          <CardHead title="Ereignisse" />
          <ul className="divide-y divide-line">
            {detail.events.map((e) => (
              <li key={e.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-2.5 text-[14px] sm:px-6">
                <span className={`num w-4 ${e.ok ? "text-ok" : "text-bad"}`}>{e.ok ? "✓" : "✗"}</span>
                <span className="w-36 font-medium">{e.action}</span>
                <span className="text-muted">
                  {e.actor} · {e.channel}
                </span>
                {e.detail && <code className="code min-w-0 flex-1 truncate text-muted">{JSON.stringify(e.detail)}</code>}
                <span className="label ml-auto">{when(e.at)}</span>
              </li>
            ))}
            {detail.events.length === 0 && <li className="px-6 py-6 text-muted">Noch keine Ereignisse.</li>}
          </ul>
        </Card>
      </div>

      <form action={archiveDemo} className="mt-10 border-t border-line pt-6">
        {hidden}
        <p className="mb-3 max-w-xl text-[14px] text-muted">
          Archivieren stoppt die Demo, macht sie privat und nimmt sie aus dem Katalog. Versionen und Protokolle bleiben.
        </p>
        <SubmitButton className="pill pill-soft pill-sm">Archivieren</SubmitButton>
      </form>
    </>
  );
}

function VersionRow({ v, current, slug }: { v: DemoVersion; current: boolean; slug: string }) {
  return (
    <li className="px-5 py-4 sm:px-6">
      <details>
        <summary className="flex cursor-pointer list-none flex-wrap items-center gap-3">
          <code className="code text-[14px] font-medium">{short(v.gitSha)}</code>
          <GateChip version={v} />
          {current && <Chip tone="ok">live</Chip>}
          <span className="label ml-auto">{when(v.createdAt)}</span>
        </summary>
        <div className="mt-4 grid gap-4 lg:grid-cols-2">
          <GateList title="Build" gate={v.gate} />
          {v.gateExperience ? <GateList title="Erlebnis" gate={v.gateExperience} /> : <p className="text-[14px] text-muted">Erlebnis-Prüfung läuft nach dem Ausrollen.</p>}
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          {v.runUrl && (
            <a href={v.runUrl} target="_blank" rel="noopener" className="pill pill-soft pill-sm">
              Actions-Lauf ↗
            </a>
          )}
          {!current && (
            <form action={deployDemo}>
              <input type="hidden" name="slug" value={slug} />
              <input type="hidden" name="sha" value={v.gitSha} />
              <SubmitButton className="pill pill-soft pill-sm">Diese Version ausrollen</SubmitButton>
            </form>
          )}
        </div>
      </details>
    </li>
  );
}

function GateList({ title, gate }: { title: string; gate: Gate }) {
  return (
    <div>
      <p className="label mb-2">{title}</p>
      <ul className="space-y-1.5 text-[14px]">
        {gate.checks.map((c) => (
          <li key={c.name} className="flex gap-2">
            <span className={c.ok ? "text-ok" : "text-bad"}>{c.ok ? "✓" : "✗"}</span>
            <span className="font-medium">{c.name}</span>
            <span className="min-w-0 flex-1 truncate text-muted" title={c.detail}>
              {c.detail.split("\n")[0]}
            </span>
          </li>
        ))}
        {gate.missing.map((m) => (
          <li key={m} className="flex gap-2 text-bad">
            <span>?</span>
            <span className="font-medium">{m}</span>
            <span>nicht gemeldet</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
