import { api, type PendingAction } from "@/lib/api";
import { decideApproval } from "@/lib/actions";
import { when } from "@/lib/format";
import { Banner, Card, Chip, PageHead } from "@/components/ui";
import { SubmitButton } from "@/components/submit-button";

export const dynamic = "force-dynamic";

const TONE = { pending: "warn", executed: "ok", failed: "bad", rejected: "neutral", approved: "neutral" } as const;

export default async function Approvals({ searchParams }: { searchParams: Promise<{ error?: string; ok?: string }> }) {
  const { error, ok } = await searchParams;
  const list = await api<PendingAction[]>("/approvals");
  const open = list.filter((a) => a.status === "pending");
  return (
    <>
      <PageHead index="05" eyebrow={`${open.length} offen`} title="Freigaben" />
      <Banner error={error} ok={ok} />
      <Card>
        <ul className="divide-y divide-line">
          {list.map((a) => (
            <li key={a.id} className="flex flex-wrap items-center gap-4 px-5 py-4 sm:px-6">
              <div className="min-w-0 flex-1 basis-72">
                <p className="font-medium">{a.summary ?? a.operation}</p>
                <p className="label mt-1">
                  {a.operation} · {a.requestedBy} · {when(a.requestedAt)}
                </p>
                <code className="code mt-1 block truncate text-muted">{JSON.stringify(a.arguments)}</code>
                {a.result && a.status !== "pending" && (
                  <code className="code mt-1 block truncate text-muted">→ {JSON.stringify(a.result)}</code>
                )}
              </div>
              <Chip tone={TONE[a.status]}>{a.status}</Chip>
              {a.status === "pending" && (
                <div className="flex gap-2">
                  <form action={decideApproval}>
                    <input type="hidden" name="id" value={a.id} />
                    <input type="hidden" name="decision" value="approve" />
                    <SubmitButton className="pill pill-deep pill-sm">Freigeben</SubmitButton>
                  </form>
                  <form action={decideApproval}>
                    <input type="hidden" name="id" value={a.id} />
                    <input type="hidden" name="decision" value="reject" />
                    <SubmitButton className="pill pill-soft pill-sm">Ablehnen</SubmitButton>
                  </form>
                </div>
              )}
            </li>
          ))}
          {list.length === 0 && <li className="px-6 py-10 text-muted">Keine Freigaben.</li>}
        </ul>
      </Card>
    </>
  );
}
