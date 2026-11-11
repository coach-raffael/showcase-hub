import { STATE_LABEL, VISIBILITY_LABEL } from "@/lib/format";
import type { DemoVersion } from "@/lib/api";

/** Seitenkopf: Mono-Eyebrow mit Index, große Sans-Zeile, optional Aktionen rechts. */
export function PageHead({
  index,
  eyebrow,
  title,
  children,
}: {
  index: string;
  eyebrow: string;
  title: React.ReactNode;
  children?: React.ReactNode;
}) {
  return (
    <div className="mb-8 flex flex-wrap items-end justify-between gap-4">
      <div className="rise min-w-0">
        <p className="label mb-3">
          <span className="text-ink">[{index}]</span> {eyebrow}
        </p>
        <h1 className="display break-words text-[36px] sm:text-[52px]">{title}</h1>
      </div>
      {children && (
        <div className="rise flex flex-wrap gap-2" style={{ "--i": 1 } as React.CSSProperties}>
          {children}
        </div>
      )}
    </div>
  );
}

export function Card({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  return (
    <section className={`rounded-[var(--radius-card)] border border-line bg-well shadow-[var(--shadow-card)] ${className}`}>
      {children}
    </section>
  );
}

export function CardHead({ title, children }: { title: string; children?: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-4 sm:px-6">
      <h2 className="label">{title}</h2>
      {children}
    </div>
  );
}

/** Meldungen aus ?error=… / ?ok=… (Server Actions leiten damit zurück). */
export function Banner({ error, ok }: { error?: string; ok?: string }) {
  if (error) {
    return (
      <div role="alert" className="mb-6 rounded-[var(--radius-pop)] bg-bad-soft px-5 py-3.5 text-[15px] text-bad">
        {error}
      </div>
    );
  }
  if (ok) {
    return (
      <div role="status" className="mb-6 rounded-[var(--radius-pop)] bg-ok-soft px-5 py-3.5 text-[15px] text-ok">
        {ok}
      </div>
    );
  }
  return null;
}

const TONE: Record<string, string> = {
  ok: "bg-ok-soft text-ok",
  warn: "bg-warn-soft text-warn",
  bad: "bg-bad-soft text-bad",
  neutral: "bg-field text-muted",
};

export function Chip({ tone = "neutral", children }: { tone?: keyof typeof TONE; children: React.ReactNode }) {
  return (
    <span className={`inline-flex h-7 items-center rounded-[var(--radius-chip)] px-3 font-mono text-[11px] uppercase tracking-[1px] ${TONE[tone]}`}>
      {children}
    </span>
  );
}

export function StateChip({ state }: { state: string }) {
  const tone = state === "running" ? "ok" : state === "failed" ? "bad" : state === "deploying" ? "warn" : "neutral";
  return <Chip tone={tone}>{STATE_LABEL[state] ?? state}</Chip>;
}

export function VisibilityChip({ visibility }: { visibility: string }) {
  return <Chip tone={visibility === "public" ? "ok" : "neutral"}>{VISIBILITY_LABEL[visibility] ?? visibility}</Chip>;
}

/** Gate einer Version auf einen Blick: Build · Erlebnis. */
export function GateChip({ version }: { version: DemoVersion | null | undefined }) {
  if (!version) return <Chip>kein Build</Chip>;
  if (!version.gatePassed) return <Chip tone="bad">Gate rot</Chip>;
  if (version.experiencePassed === false) return <Chip tone="bad">Erlebnis rot</Chip>;
  if (version.experiencePassed == null) return <Chip tone="warn">Erlebnis offen</Chip>;
  return <Chip tone="ok">Gate grün</Chip>;
}
