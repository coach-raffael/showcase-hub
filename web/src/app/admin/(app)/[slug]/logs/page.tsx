import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { PageHead } from "@/components/ui";

export const dynamic = "force-dynamic";

export default async function Logs({
  params,
  searchParams,
}: {
  params: Promise<{ slug: string }>;
  searchParams: Promise<{ tail?: string }>;
}) {
  const { slug } = await params;
  const tail = Math.min(500, Math.max(10, Number((await searchParams).tail ?? 200) || 200));
  let text: string;
  try {
    text = await api<string>(`/demos/${encodeURIComponent(slug)}/logs?tail=${tail}`, { timeoutMs: 30_000 });
  } catch (e) {
    text = e instanceof ApiError ? e.message : "Logs nicht abrufbar";
  }
  return (
    <>
      <PageHead index="03" eyebrow={`${slug} · letzte ${tail} Zeilen`} title="Logs">
        <Link href={`/admin/${slug}`} className="pill pill-soft">
          Zurück
        </Link>
        <Link href={`/admin/${slug}/logs?tail=500`} className="pill pill-soft">
          500 Zeilen
        </Link>
      </PageHead>
      <pre className="code max-h-[75vh] overflow-auto rounded-[var(--radius-card)] border border-line bg-field p-5">{text || "(leer)"}</pre>
    </>
  );
}
