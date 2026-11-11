import Link from "next/link";
import { api, type Overview } from "@/lib/api";
import { controlDemo } from "@/lib/actions";
import { LIFECYCLE_LABEL, pick, short, when } from "@/lib/format";
import { Banner, Card, Chip, GateChip, PageHead, StateChip, VisibilityChip } from "@/components/ui";
import { SubmitButton } from "@/components/submit-button";

export const dynamic = "force-dynamic";

export default async function Demos({ searchParams }: { searchParams: Promise<{ error?: string; ok?: string }> }) {
  const { error, ok } = await searchParams;
  const list = await api<Overview[]>("/demos");
  const running = list.filter((o) => o.demo.kind === "managed" && o.demo.state === "running").length;

  return (
    <>
      <PageHead index="01" eyebrow={`${list.length} Einträge · ${running} laufen`} title="Demos">
        <Link href="/admin/neu" className="pill pill-deep">
          Hinzufügen
        </Link>
      </PageHead>
      <Banner error={error} ok={ok} />
      <Card>
        <ul className="divide-y divide-line">
          {list.map(({ demo: d, currentVersion: v }) => (
            <li key={d.slug} className="flex flex-wrap items-center gap-x-6 gap-y-3 px-5 py-4 sm:px-6">
              <div className="min-w-0 flex-1 basis-64">
                <Link href={`/admin/${d.slug}`} className="text-[17px] font-semibold tracking-[-0.01em] hover:underline">
                  {pick(d.title)}
                </Link>
                <p className="label mt-1 truncate">
                  {d.slug} · {d.kind === "managed" ? d.sourceRepo : "extern"}
                  {v && ` · ${short(v.gitSha)}`}
                </p>
              </div>
              <div className="flex flex-wrap items-center gap-1.5">
                {d.kind === "managed" ? <StateChip state={d.state} /> : <Chip>extern</Chip>}
                <VisibilityChip visibility={d.visibility} />
                {d.kind === "managed" && <GateChip version={v} />}
                {d.kind === "managed" && <Chip>{LIFECYCLE_LABEL[d.lifecycle]}</Chip>}
              </div>
              <div className="flex items-center gap-2">
                {d.kind === "managed" && d.state === "running" && (
                  <form action={controlDemo}>
                    <input type="hidden" name="slug" value={d.slug} />
                    <input type="hidden" name="action" value="stop" />
                    <input type="hidden" name="back" value="/admin" />
                    <SubmitButton className="pill pill-soft pill-sm">Stoppen</SubmitButton>
                  </form>
                )}
                {d.kind === "managed" && ["stopped", "failed", "expired"].includes(d.state) && (
                  <form action={controlDemo}>
                    <input type="hidden" name="slug" value={d.slug} />
                    <input type="hidden" name="action" value="start" />
                    <input type="hidden" name="back" value="/admin" />
                    <SubmitButton className="pill pill-deep pill-sm">Starten</SubmitButton>
                  </form>
                )}
                <a href={d.url} target="_blank" rel="noopener" className="pill pill-soft pill-sm">
                  Öffnen ↗
                </a>
              </div>
              <p className="label w-full sm:w-auto">{when(d.stateAt)}</p>
            </li>
          ))}
          {list.length === 0 && <li className="px-6 py-10 text-muted">Noch nichts registriert.</li>}
        </ul>
      </Card>
    </>
  );
}
