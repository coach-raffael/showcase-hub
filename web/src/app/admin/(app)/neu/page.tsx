import { registerDemo, upsertExternal } from "@/lib/actions";
import { Banner, Card, CardHead, PageHead } from "@/components/ui";
import { SubmitButton } from "@/components/submit-button";

export default async function Add({ searchParams }: { searchParams: Promise<{ error?: string; ok?: string }> }) {
  const { error, ok } = await searchParams;
  return (
    <>
      <PageHead index="04" eyebrow="Repo nennen – der Hub liest demo.yaml" title="Hinzufügen" />
      <Banner error={error} ok={ok} />
      <div className="grid gap-6 xl:grid-cols-2">
        <Card>
          <CardHead title="Showcase aus einem Repo" />
          <form action={registerDemo} className="space-y-4 p-5 sm:p-6">
            <label className="block space-y-1.5">
              <span className="label">Repo</span>
              <input name="repo" required placeholder="coach-raffael/realestate-platform" className="field" />
            </label>
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="block space-y-1.5">
                <span className="label">Pfad (optional)</span>
                <input name="path" placeholder="demo.yaml" className="field" />
              </label>
              <label className="block space-y-1.5">
                <span className="label">Branch (optional)</span>
                <input name="ref" placeholder="main" className="field" />
              </label>
            </div>
            <p className="text-[14px] text-muted">
              Neue Demos starten privat. Der erste grüne Build aus build-showcase rollt automatisch aus; öffentlich wird
              sie erst, wenn Build- und Erlebnis-Gate grün sind.
            </p>
            <SubmitButton>Registrieren</SubmitButton>
          </form>
        </Card>

        <Card>
          <CardHead title="Externer Eintrag (nur Katalog)" />
          <form action={upsertExternal} className="grid gap-4 p-5 sm:grid-cols-2 sm:p-6">
            <label className="space-y-1.5">
              <span className="label">Slug</span>
              <input name="slug" required pattern="[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?" placeholder="langtutor" className="field" />
            </label>
            <label className="space-y-1.5">
              <span className="label">Adresse</span>
              <input name="url" type="url" required placeholder="https://langtutor.cloud" className="field" />
            </label>
            <label className="space-y-1.5">
              <span className="label">Titel (en)</span>
              <input name="titleEn" required className="field" />
            </label>
            <label className="space-y-1.5">
              <span className="label">Titel (de, optional)</span>
              <input name="titleDe" className="field" />
            </label>
            <label className="space-y-1.5 sm:col-span-2">
              <span className="label">Teaser (en)</span>
              <textarea name="teaserEn" className="field" />
            </label>
            <label className="space-y-1.5">
              <span className="label">Kategorie</span>
              <select name="category" defaultValue="tool" className="field">
                <option value="tool">Tool</option>
                <option value="showcase">Showcase</option>
                <option value="demo">Demo</option>
              </select>
            </label>
            <label className="space-y-1.5">
              <span className="label">Tags (Komma)</span>
              <input name="tags" placeholder="nextjs, spring-boot" className="field" />
            </label>
            <div className="sm:col-span-2">
              <SubmitButton>Speichern</SubmitButton>
            </div>
          </form>
        </Card>
      </div>
    </>
  );
}
