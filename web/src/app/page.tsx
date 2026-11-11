import Link from "next/link";
import { publicApi, type CatalogEntry, type CatalogFeed } from "@/lib/api";
import { pick } from "@/lib/format";

export const dynamic = "force-dynamic";

async function loadCatalog(): Promise<CatalogEntry[] | null> {
  try {
    const feed = await publicApi<CatalogFeed>("/catalog");
    return [...feed.entries].sort((a, b) => Number(b.featured) - Number(a.featured) || a.sortOrder - b.sortOrder);
  } catch {
    return null;
  }
}

/** Öffentliche Galerie – englisch, wie alle eigenen Inhalte. */
export default async function Gallery() {
  const entries = await loadCatalog();
  const showcases = entries?.filter((e) => e.category !== "tool") ?? [];
  const tools = entries?.filter((e) => e.category === "tool") ?? [];

  return (
    <div className="min-h-dvh p-2 sm:p-4">
      <div className="min-h-[calc(100dvh-1rem)] rounded-[var(--radius-page)] bg-card sm:min-h-[calc(100dvh-2rem)]">
        <div className="min-h-[inherit] rounded-[var(--radius-page)] bg-well px-5 pb-16 pt-10 sm:px-10 sm:pt-16">
          <header className="rise mx-auto max-w-6xl">
            <p className="label mb-4">
              <a href="https://coach-raffael.com" className="hover:text-ink">
                Coach Raffael
              </a>{" "}
              / Showcases
            </p>
            <h1 className="display max-w-3xl text-[44px] sm:text-[72px]">Software you can click through.</h1>
            <p className="mt-6 max-w-2xl text-[18px] leading-relaxed text-muted">
              Live demos of platforms I designed and built – architecture, code quality and the details that make a
              product feel finished. Every showcase passes the same automated quality gate before it appears here.
            </p>
          </header>

          {entries === null && (
            <p className="mx-auto mt-12 max-w-6xl rounded-[var(--radius-pop)] bg-warn-soft px-5 py-4 text-warn">
              The catalog is unavailable right now. Please try again in a minute.
            </p>
          )}

          {showcases.length > 0 && <Section title="Showcases" entries={showcases} offset={1} />}
          {tools.length > 0 && <Section title="Tools" entries={tools} offset={1 + showcases.length} />}

          {entries !== null && entries.length === 0 && (
            <p className="mx-auto mt-12 max-w-6xl text-muted">The first showcases are on their way.</p>
          )}

          <footer className="mx-auto mt-20 flex max-w-6xl flex-wrap items-center justify-between gap-4 border-t border-line pt-6">
            <p className="label">Raffael Hühnerschulte · IT made simple</p>
            <div className="flex gap-4">
              <a className="label hover:text-ink" href="https://coach-raffael.com">
                coach-raffael.com
              </a>
              <Link className="label hover:text-ink" href="/admin">
                Admin
              </Link>
            </div>
          </footer>
        </div>
      </div>
    </div>
  );
}

function Section({ title, entries, offset }: { title: string; entries: CatalogEntry[]; offset: number }) {
  return (
    <section className="mx-auto mt-16 max-w-6xl">
      <h2 className="label mb-5">{title}</h2>
      <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {entries.map((e, i) => (
          <li key={e.slug} className="rise" style={{ "--i": offset + i } as React.CSSProperties}>
            <EntryCard entry={e} />
          </li>
        ))}
      </ul>
    </section>
  );
}

function EntryCard({ entry: e }: { entry: CatalogEntry }) {
  const external = e.kind === "external";
  return (
    <a
      href={e.url}
      {...(external ? { target: "_blank", rel: "noopener" } : {})}
      className="group flex h-full flex-col overflow-hidden rounded-[var(--radius-card)] border border-line bg-well shadow-[var(--shadow-card)] transition-transform duration-200 hover:-translate-y-0.5"
    >
      <div className="relative aspect-[16/10] overflow-hidden bg-field">
        {e.screenshotUrl ? (
          // eslint-disable-next-line @next/next/no-img-element -- Screenshot vom eigenen Hub, keine Optimierung nötig
          <img
            src={e.screenshotUrl}
            alt={`Screenshot of ${pick(e.title)}`}
            loading="lazy"
            className="size-full object-cover object-top transition-transform duration-500 group-hover:scale-[1.02]"
          />
        ) : (
          <div className="grid size-full place-items-center">
            <span className="display text-[40px] text-subtle">{pick(e.title).slice(0, 2)}</span>
          </div>
        )}
        <span className="absolute left-3 top-3 flex gap-1.5">
          {e.state === "running" && <Badge>Live</Badge>}
          {e.state === "sleeping" && <Badge>Starts on open</Badge>}
          {e.featured && <Badge>Featured</Badge>}
        </span>
      </div>
      <div className="flex flex-1 flex-col gap-3 p-5">
        <h3 className="text-[20px] font-semibold tracking-[-0.02em]">{pick(e.title)}</h3>
        <p className="text-[15px] leading-relaxed text-muted">{pick(e.teaser)}</p>
        <div className="mt-auto flex flex-wrap gap-1.5 pt-2">
          {e.tags.slice(0, 5).map((t) => (
            <span key={t} className="rounded-full bg-field px-2.5 py-1 font-mono text-[11px] text-muted">
              {t}
            </span>
          ))}
        </div>
        <div className="flex items-center justify-between pt-1">
          <span className="label">{e.verified ? "✓ Quality gate passed" : external ? "Own domain" : ""}</span>
          <span className="label text-ink group-hover:underline">Open {external ? "↗" : "→"}</span>
        </div>
      </div>
    </a>
  );
}

function Badge({ children }: { children: React.ReactNode }) {
  return (
    <span className="rounded-full bg-well/90 px-2.5 py-1 font-mono text-[10.5px] uppercase tracking-[1px] text-ink shadow-sm backdrop-blur">
      {children}
    </span>
  );
}
