import type { Metadata } from "next";
import { signInWithKeycloak } from "@/lib/auth-actions";
import { SubmitButton } from "@/components/submit-button";

export const metadata: Metadata = { title: "Anmelden", robots: { index: false } };

export default async function SignIn({ searchParams }: { searchParams: Promise<{ forbidden?: string }> }) {
  const { forbidden } = await searchParams;
  return (
    <div className="grid min-h-dvh place-items-center p-4">
      <div className="rise w-full max-w-md rounded-[var(--radius-page)] border border-line bg-well p-8 shadow-[var(--shadow-card)]">
        <p className="label mb-3">Showcase-Hub</p>
        <h1 className="display text-[36px]">Anmelden</h1>
        {forbidden ? (
          <p lang="de" className="mt-4 rounded-[var(--radius-pop)] bg-warn-soft px-4 py-3 text-warn">
            Dieses Konto hat keinen Zugang zum Hub (Realm-Rolle demos-admin fehlt).
          </p>
        ) : (
          <p lang="de" className="mt-4 text-muted">Demos verwalten, starten und freischalten.</p>
        )}
        <form action={signInWithKeycloak} className="mt-6">
          <SubmitButton>Mit Keycloak anmelden</SubmitButton>
        </form>
      </div>
    </div>
  );
}
