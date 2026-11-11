"use client";

import { useCallback, useEffect, useRef, useState } from "react";

type State = "running" | "sleeping" | "deploying" | "unavailable" | "starting" | "error";

/**
 * Weckt eine schlafende Demo und wartet, bis sie antwortet; danach Neuladen derselben Adresse –
 * dann greift der Router der Demo und nicht mehr diese Seite.
 */
export function WakePanel({
  slug,
  initialState,
  wakePolicy,
  token,
}: {
  slug: string;
  initialState: State;
  wakePolicy: "anyone" | "share_link" | "admin";
  token?: string;
}) {
  const [state, setState] = useState<State>(initialState);
  const [message, setMessage] = useState<string>();
  const [elapsed, setElapsed] = useState(0);
  const started = useRef(false);

  const key = `wake:${slug}`;
  const reloads = useCallback(() => {
    try {
      return Number(sessionStorage.getItem(key) ?? 0);
    } catch {
      return 0;
    }
  }, [key]);
  const countReload = useCallback(() => {
    try {
      sessionStorage.setItem(key, String(reloads() + 1));
    } catch {
      /* ohne Speicher eben ohne Zähler */
    }
  }, [key, reloads]);

  const poll = useCallback(async () => {
    for (let i = 0; i < 60; i++) {
      await new Promise((r) => setTimeout(r, 3000));
      setElapsed((e) => e + 3);
      try {
        const res = await fetch(`/api/public/demos/${slug}`, { cache: "no-store" });
        const body = (await res.json()) as { state: State };
        if (body.state === "running") {
          // Kurz warten, bis die App selbst antwortet, dann neu laden. Höchstens fünfmal – meldet der Hub
          // „läuft“, die Demo antwortet aber nicht, soll die Seite nicht endlos neu laden.
          if (reloads() >= 5) break;
          await new Promise((r) => setTimeout(r, 4000));
          countReload();
          window.location.reload();
          return;
        }
      } catch {
        /* weiter warten */
      }
    }
    setState("error");
    setMessage("The demo takes longer than usual. Please reload in a minute.");
  }, [slug, reloads, countReload]);

  const wake = useCallback(async () => {
    if (started.current) return;
    started.current = true;
    setState("starting");
    setMessage(undefined);
    try {
      const res = await fetch(`/api/public/demos/${slug}/wake`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: token ?? null }),
      });
      if (!res.ok) {
        const body = (await res.json().catch(() => ({}))) as { message?: string };
        setState("error");
        setMessage(body.message ?? "The demo could not be started right now.");
        started.current = false;
        return;
      }
      await poll();
    } catch {
      setState("error");
      setMessage("Network error – please try again.");
      started.current = false;
    }
  }, [slug, token, poll]);

  // Mit persönlichem Link oder offener Demo direkt starten – Besucher sollen nicht zweimal klicken
  useEffect(() => {
    const canAutoWake = initialState === "sleeping" && (wakePolicy === "anyone" || (wakePolicy === "share_link" && token));
    const timer = setTimeout(() => {
      if (canAutoWake) void wake();
      else if (initialState === "running" || initialState === "deploying") void poll();
    }, 0);
    return () => clearTimeout(timer);
  }, [initialState, wakePolicy, token, wake, poll]);

  if (state === "unavailable") {
    return <p className="mt-8 rounded-[var(--radius-pop)] bg-field px-5 py-4 text-muted">This demo is not available at the moment.</p>;
  }
  if (wakePolicy === "admin" && state === "sleeping") {
    return (
      <p className="mt-8 rounded-[var(--radius-pop)] bg-field px-5 py-4 text-muted">
        This demo is started on request.{" "}
        <a className="underline" href="https://coach-raffael.com/#contact">
          Get in touch
        </a>{" "}
        and I will switch it on for you.
      </p>
    );
  }
  if (wakePolicy === "share_link" && !token && state === "sleeping") {
    return <p className="mt-8 rounded-[var(--radius-pop)] bg-field px-5 py-4 text-muted">This demo opens with a personal link.</p>;
  }

  return (
    <div className="mt-8" aria-live="polite">
      {(state === "starting" || state === "running" || state === "deploying") && (
        <div className="flex items-center gap-4 rounded-[var(--radius-pop)] bg-field px-5 py-4">
          <span
            aria-hidden
            className="inline-block size-5 shrink-0 animate-spin rounded-full border-2 border-ink border-r-transparent"
          />
          <div>
            <p className="font-medium">{state === "deploying" ? "A new version is being deployed…" : "Waking the demo up…"}</p>
            <p className="label mt-1">{elapsed > 0 ? `${elapsed}s · usually under a minute` : "usually under a minute"}</p>
          </div>
        </div>
      )}
      {state === "sleeping" && (
        <button type="button" className="pill pill-deep" onClick={() => void wake()}>
          Start demo
        </button>
      )}
      {state === "error" && (
        <div className="space-y-4">
          <p className="rounded-[var(--radius-pop)] bg-bad-soft px-5 py-4 text-bad">{message}</p>
          <button type="button" className="pill pill-soft" onClick={() => void wake()}>
            Try again
          </button>
        </div>
      )}
    </div>
  );
}
