"use client";

import { useFormStatus } from "react-dom";

/**
 * Absende-Button mit Ladezustand für jedes Formular (Server Action oder next/form):
 * zeigt sofort einen Spinner (optional einen eigenen Text) und verhindert Doppelklicks, solange die Aktion läuft.
 */
export function SubmitButton({
  children,
  pending,
  className = "pill pill-deep",
  disabled = false,
  ...rest
}: Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, "type"> & {
  children: React.ReactNode;
  /** Text während der Aktion; ohne Angabe bleibt die Beschriftung und ein Spinner erscheint. */
  pending?: string;
}) {
  const status = useFormStatus();
  const busy = status.pending;
  return (
    <button
      type="submit"
      {...rest}
      className={`${className} ${busy ? "cursor-progress" : ""}`}
      disabled={disabled || busy}
      aria-busy={busy}
    >
      {busy && (
        <span
          aria-hidden
          className="mr-1.5 inline-block size-3 shrink-0 animate-spin rounded-full border-[1.5px] border-current border-r-transparent align-[-1px]"
        />
      )}
      {busy && pending ? pending : children}
    </button>
  );
}
