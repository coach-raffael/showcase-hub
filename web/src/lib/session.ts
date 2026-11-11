import "server-only";
import { redirect } from "next/navigation";
import { auth } from "@/auth";

/** Erforderliche Realm-Rolle (wie im Backend). */
export const ADMIN_ROLE = "demos-admin";

/** Für den Adminbereich: ohne Sitzung zur Anmeldung, ohne Rolle zur Hinweisseite. */
export async function requireAdmin() {
  const session = await auth();
  if (!session?.user || session.error) redirect("/admin/anmelden");
  if (!session.roles?.includes(ADMIN_ROLE)) redirect("/admin/anmelden?forbidden=1");
  return session;
}
