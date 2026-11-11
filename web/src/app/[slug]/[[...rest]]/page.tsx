import Link from "next/link";
import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { ApiError, publicApi, type PublicDemo } from "@/lib/api";
import { pick } from "@/lib/format";
import { WakePanel } from "./wake-panel";

export const dynamic = "force-dynamic";

type Props = { params: Promise<{ slug: string }>; searchParams: Promise<{ s?: string }> };

const SLUG = /^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$/;

async function load(slug: string): Promise<PublicDemo | null> {
  if (!SLUG.test(slug)) return null;
  try {
    return await publicApi<PublicDemo>(`/public/demos/${slug}`);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const demo = await load((await params).slug).catch(() => null);
  return { title: demo ? pick(demo.title) : "Demo", robots: { index: false } };
}

/**
 * Schlafseite: Traefik schickt /<slug>/… nur dann hierher, wenn die Demo selbst gerade keinen Router hat –
 * sie schläft, wird ausgerollt oder ist nicht verfügbar. Läuft sie, sieht man diese Seite nie.
 */
export default async function SleepingDemo({ params, searchParams }: Props) {
  const { slug } = await params;
  const { s } = await searchParams;
  const demo = await load(slug);
  if (!demo) notFound();

  return (
    <div className="grid min-h-dvh place-items-center p-4">
      <div className="rise w-full max-w-xl rounded-[var(--radius-page)] border border-line bg-well p-8 shadow-[var(--shadow-card)] sm:p-10">
        <p className="label mb-4">Coach Raffael / Showcase</p>
        <h1 className="display text-[36px] sm:text-[44px]">{pick(demo.title)}</h1>
        {pick(demo.teaser) && <p className="mt-4 text-[16px] leading-relaxed text-muted">{pick(demo.teaser)}</p>}
        <WakePanel slug={slug} initialState={demo.state} wakePolicy={demo.wakePolicy} token={s} />
        <p className="mt-8 text-[13px] text-muted">
          Demos sleep when nobody uses them, so the server stays fast for everyone.{" "}
          <Link className="underline hover:text-ink" href="/">
            See all showcases
          </Link>
        </p>
      </div>
    </div>
  );
}
