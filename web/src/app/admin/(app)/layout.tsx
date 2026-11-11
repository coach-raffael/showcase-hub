import type { Metadata } from "next";
import { requireAdmin } from "@/lib/session";
import { api, type PendingAction } from "@/lib/api";
import { AdminShell } from "@/components/admin-shell";

export const metadata: Metadata = { title: { default: "Hub", template: "%s · Showcase-Hub" }, robots: { index: false } };

export default async function AdminLayout({ children }: { children: React.ReactNode }) {
  const session = await requireAdmin();
  const open = await api<PendingAction[]>("/approvals?status=pending").then((a) => a.length).catch(() => 0);
  return (
    <div lang="de">
      <AdminShell firstName={session.user.firstName} openApprovals={open}>
        {children}
      </AdminShell>
    </div>
  );
}
