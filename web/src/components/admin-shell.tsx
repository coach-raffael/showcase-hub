import Link from "next/link";
import { signOutEverywhere } from "@/lib/auth-actions";
import { SubmitButton } from "@/components/submit-button";

const NAV = [
  { href: "/admin", label: "Demos" },
  { href: "/admin/neu", label: "Hinzufügen" },
  { href: "/admin/freigaben", label: "Freigaben" },
  { href: "/admin/mcp", label: "MCP" },
];

/** Ridgeline-Rahmen wie im CRM: graues Feld → Karte → weißes Well mit klebendem Kopf. */
export function AdminShell({
  children,
  firstName,
  openApprovals,
}: {
  children: React.ReactNode;
  firstName?: string;
  openApprovals: number;
}) {
  return (
    <div className="min-h-dvh p-2 sm:p-4">
      <div className="min-h-[calc(100dvh-1rem)] rounded-[var(--radius-page)] bg-card sm:min-h-[calc(100dvh-2rem)]">
        <div className="min-h-[inherit] rounded-[var(--radius-page)] bg-well">
          <header className="sticky top-0 z-20 rounded-t-[var(--radius-page)] border-b border-line bg-well/90 backdrop-blur-md">
            <div className="flex items-center gap-4 px-5 py-4 sm:px-8">
              <Link href="/admin" className="flex items-center gap-2.5">
                <span className="grid size-9 place-items-center rounded-[12px] bg-navy font-mono text-[13px] font-medium text-white">
                  CR
                </span>
                <span className="text-[17px] font-semibold tracking-[-0.02em]">Showcases</span>
              </Link>
              <nav className="ml-2 hidden gap-1 md:flex" aria-label="Hauptnavigation">
                <NavLinks openApprovals={openApprovals} />
              </nav>
              <div className="ml-auto flex shrink-0 items-center gap-3">
                <Link href="/" className="label hidden hover:text-ink sm:inline">
                  Galerie ↗
                </Link>
                {firstName && <span className="label hidden lg:inline">Hallo {firstName}</span>}
                <form action={signOutEverywhere}>
                  <SubmitButton className="pill pill-soft pill-sm">Abmelden</SubmitButton>
                </form>
              </div>
            </div>
            <nav className="flex gap-1 overflow-x-auto px-4 pb-3 md:hidden" aria-label="Hauptnavigation mobil">
              <NavLinks openApprovals={openApprovals} />
            </nav>
          </header>
          <main className="px-5 py-8 sm:px-8 sm:py-10">{children}</main>
        </div>
      </div>
    </div>
  );
}

function NavLinks({ openApprovals }: { openApprovals: number }) {
  return (
    <>
      {NAV.map((n) => (
        <Link key={n.href} href={n.href} className="pill pill-sm hover:bg-field">
          {n.label}
          {n.href === "/admin/freigaben" && openApprovals > 0 && (
            <span className="inline-grid min-w-4 place-items-center rounded-full bg-bad px-1 font-mono text-[10px] leading-4 text-white">
              {openApprovals}
            </span>
          )}
        </Link>
      ))}
    </>
  );
}
