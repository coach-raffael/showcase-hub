import { api, type McpPolicy } from "@/lib/api";
import { setPolicy } from "@/lib/actions";
import { Banner, Card, Chip, PageHead } from "@/components/ui";
import { SubmitButton } from "@/components/submit-button";

export const dynamic = "force-dynamic";

export default async function Policy({ searchParams }: { searchParams: Promise<{ error?: string; ok?: string }> }) {
  const { error, ok } = await searchParams;
  const list = await api<McpPolicy[]>("/mcp-policy");
  const site = process.env.PUBLIC_URL ?? "https://demos.coach-raffael.com";
  return (
    <>
      <PageHead index="06" eyebrow={`Connector: ${site}/api/mcp`} title="MCP-Richtlinie" />
      <Banner error={error} ok={ok} />
      <Card>
        <ul className="divide-y divide-line">
          {list.map((p) => (
            <li key={p.tool} className="flex flex-wrap items-center gap-4 px-5 py-4 sm:px-6">
              <div className="min-w-0 flex-1 basis-72">
                <code className="code font-medium">{p.tool}</code>
                <p className="mt-1 text-[14px] text-muted">{p.description}</p>
              </div>
              {p.locked ? (
                <Chip tone="bad">{p.mode} · fest</Chip>
              ) : (
                <form action={setPolicy} className="flex items-center gap-2">
                  <input type="hidden" name="tool" value={p.tool} />
                  <select name="mode" defaultValue={p.mode} className="field w-36">
                    <option value="ALLOW">ALLOW</option>
                    <option value="APPROVAL">APPROVAL</option>
                    <option value="DENY">DENY</option>
                  </select>
                  <SubmitButton className="pill pill-soft pill-sm">Setzen</SubmitButton>
                </form>
              )}
            </li>
          ))}
        </ul>
      </Card>
    </>
  );
}
