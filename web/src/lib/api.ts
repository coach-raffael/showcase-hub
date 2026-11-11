// Typisierter Zugriff auf die Spring-Boot-API (Context-Path /api), nur serverseitig.
// `api` hängt das Keycloak-Access-Token an (Adminbereich), `publicApi` nicht (Galerie, Schlafseite).
import "server-only";
import { getAccessToken } from "./auth-token";

const API_URL = (process.env.API_URL ?? "http://localhost:8080").replace(/\/$/, "");

export type I18n = Record<string, string>;

export type DemoState = "new" | "deploying" | "running" | "stopped" | "failed" | "expired";
export type Visibility = "public" | "unlisted" | "private";
export type Lifecycle = "permanent" | "on-demand" | "ttl";

export type Demo = {
  id: number;
  slug: string;
  kind: "managed" | "external";
  sourceRepo?: string;
  sourcePath?: string;
  externalUrl?: string;
  title: I18n;
  teaser: I18n;
  description: I18n;
  category: "showcase" | "demo" | "tool";
  tags: string[];
  visibility: Visibility;
  sortOrder: number;
  featured: boolean;
  screenshotUrl?: string;
  lifecycle: Lifecycle;
  ttlDays?: number;
  expiresAt?: string;
  routing: "path" | "subdomain";
  idleMinutes: number;
  wakePolicy: "anyone" | "share_link" | "admin";
  autoDeploy: boolean;
  state: DemoState;
  stateAt: string;
  lastWokenAt?: string;
  currentVersionId?: number;
  url: string;
  createdAt: string;
  updatedAt: string;
};

export type GateCheck = { name: string; ok: boolean; detail: string };
export type Gate = { passed: boolean; missing: string[]; failed: string[]; checks: GateCheck[] };

export type DemoVersion = {
  id: number;
  demoId: number;
  gitSha: string;
  images: Record<string, string>;
  gate: Gate;
  gatePassed: boolean;
  gateExperience?: Gate;
  experiencePassed?: boolean;
  runUrl?: string;
  createdAt: string;
};

export type DemoEvent = {
  id: number;
  action: string;
  actor: string;
  channel: string;
  ok: boolean;
  detail?: Record<string, unknown>;
  at: string;
};

export type SecretInfo = { name: string; set: boolean; required: boolean; updatedAt?: string };

export type ShareLink = {
  id: number;
  label?: string;
  leadRef?: string;
  expiresAt?: string;
  createdBy: string;
  createdAt: string;
  lastUsedAt?: string;
  uses: number;
};

export type Overview = { demo: Demo; currentVersion: DemoVersion | null };

export type DemoDetail = {
  demo: Demo;
  currentVersion: DemoVersion | null;
  events: DemoEvent[];
  shareLinks: ShareLink[];
  versions?: DemoVersion[];
  secrets?: SecretInfo[];
};

export type ContainerStatus = { service: string; state: string; health: string; memory: string; cpu: string };
export type DemoStatus = { slug: string; state: string; containers: ContainerStatus[] };

export type CatalogEntry = {
  slug: string;
  kind: "managed" | "external";
  category: "showcase" | "demo" | "tool";
  title: I18n;
  teaser: I18n;
  description: I18n;
  tags: string[];
  url: string;
  screenshotUrl?: string;
  featured: boolean;
  sortOrder: number;
  state: "running" | "sleeping" | "unavailable";
  verified: boolean;
  updatedAt: string;
};
export type CatalogFeed = { version: number; generatedAt: string; entries: CatalogEntry[] };

export type PublicDemo = {
  slug: string;
  title: I18n;
  teaser: I18n;
  state: "running" | "sleeping" | "deploying" | "unavailable";
  wakePolicy: "anyone" | "share_link" | "admin";
  url: string;
};

export type McpPolicy = {
  tool: string;
  mode: "ALLOW" | "APPROVAL" | "DENY";
  locked: boolean;
  description: string;
  updatedAt: string;
  updatedBy: string;
};

export type PendingAction = {
  id: number;
  tool: string;
  operation: string;
  arguments: Record<string, unknown>;
  summary?: string;
  status: "pending" | "approved" | "rejected" | "executed" | "failed";
  requestedAt: string;
  requestedBy: string;
  decidedAt?: string;
  decidedBy?: string;
  result?: Record<string, unknown>;
};

export type RegisterResult = { demo: Demo; created: boolean; notes: string[] };

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

async function call<T>(path: string, init: RequestInit & { timeoutMs?: number }, token?: string): Promise<T> {
  const { timeoutMs = 15_000, ...rest } = init;
  const res = await fetch(`${API_URL}/api${path}`, {
    ...rest,
    headers: {
      Accept: "application/json",
      ...(typeof rest.body === "string" ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...rest.headers,
    },
    cache: "no-store",
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) {
    let message = `Fehler ${res.status}`;
    try {
      const body = (await res.json()) as { message?: string; error?: string };
      message = body.message || body.error || message;
    } catch {
      /* kein JSON */
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  const type = res.headers.get("content-type") ?? "";
  return (type.includes("json") ? await res.json() : await res.text()) as T;
}

/** Adminbereich: mit Access-Token der Sitzung. */
export async function api<T>(path: string, init: RequestInit & { timeoutMs?: number } = {}): Promise<T> {
  return call<T>(path, init, await getAccessToken());
}

/** Öffentliche Endpunkte (Katalog, Schlafseite) – ohne Token. */
export async function publicApi<T>(path: string, init: RequestInit & { timeoutMs?: number } = {}): Promise<T> {
  return call<T>(path, init);
}
