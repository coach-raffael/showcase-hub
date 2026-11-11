// Keycloak-Konfiguration und kleine Helfer rund um OIDC-Tokens.
//
// Bewusst ohne Next.js-/Auth.js-Importe gehalten, damit die Datei sowohl aus
// `src/auth.ts` (Server, Proxy) als auch aus Server Actions genutzt werden kann.

/** Realm-Issuer; entspricht `AUTH_KEYCLOAK_ISSUER`. */
export const KEYCLOAK_ISSUER = (
  process.env.AUTH_KEYCLOAK_ISSUER ?? "https://auth.coach-raffael.com/realms/coach-raffael"
).replace(/\/$/, "");

/** OIDC-Client der Web-App (confidential, Authorization Code + PKCE). */
export const KEYCLOAK_CLIENT_ID = process.env.AUTH_KEYCLOAK_ID ?? "demos-web";

/** Auth.js läuft unter diesem Präfix – nicht `/api/auth`, weil `/api/*` zum Spring-Backend geht. */
export const AUTH_BASE_PATH = "/auth";

/**
 * Öffentliche URL der Auth.js-Endpunkte (`AUTH_URL`), z. B. `http://localhost:3000/auth`.
 * Fällt im Dev auf localhost zurück.
 */
export function authUrl(): string {
  return (process.env.AUTH_URL ?? `http://localhost:3000${AUTH_BASE_PATH}`).replace(/\/$/, "");
}

/** Wurzel der App (= `AUTH_URL` ohne den Auth.js-Präfix), z. B. `https://demos.coach-raffael.com`. */
export function appUrl(): string {
  const url = new URL(authUrl());
  if (url.pathname.endsWith(AUTH_BASE_PATH)) {
    url.pathname = url.pathname.slice(0, -AUTH_BASE_PATH.length) || "/";
  }
  url.search = "";
  url.hash = "";
  return url.toString().replace(/\/$/, "");
}

/** Auth.js verwendet bei HTTPS das Cookie-Präfix `__Secure-`; daran orientiert sich das Lesen des JWT. */
export function usesSecureCookies(): boolean {
  return authUrl().startsWith("https://");
}

/** Keycloak-Endpunkte des Realms. */
export const keycloakEndpoints = {
  token: () => `${KEYCLOAK_ISSUER}/protocol/openid-connect/token`,
  logout: () => `${KEYCLOAK_ISSUER}/protocol/openid-connect/logout`,
};

/** Relevanter Ausschnitt eines Keycloak-Access-Tokens. */
export type AccessTokenClaims = {
  sub?: string;
  exp?: number;
  given_name?: string;
  realm_access?: { roles?: string[] };
};

/**
 * Dekodiert die Payload eines JWT **ohne Signaturprüfung**.
 * Nur für UI-Zwecke (Rollen anzeigen, Vorname) – die Autorisierung prüft das
 * Backend selbst anhand der Signatur.
 */
export function decodeJwtPayload<T = AccessTokenClaims>(jwt: string | undefined): T | null {
  if (!jwt) return null;
  const parts = jwt.split(".");
  if (parts.length < 2) return null;
  try {
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const json = Buffer.from(base64, "base64").toString("utf8");
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}

/** Realm-Rollen aus einem Access-Token (leer, falls nicht dekodierbar). */
export function rolesFromAccessToken(accessToken: string | undefined): string[] {
  return decodeJwtPayload(accessToken)?.realm_access?.roles ?? [];
}

/** Antwort des Keycloak-Token-Endpunkts bei `grant_type=refresh_token`. */
export type RefreshedTokens = {
  access_token: string;
  refresh_token?: string;
  id_token?: string;
  /** Unix-Sekunden, ab denen das Access-Token ungültig ist. */
  expires_at: number;
};

/**
 * Erneuert das Access-Token über den Keycloak-Token-Endpunkt.
 * Wirft bei HTTP-Fehlern oder fehlendem Client-Secret.
 */
export async function refreshAccessToken(refreshToken: string): Promise<RefreshedTokens> {
  const clientSecret = process.env.AUTH_KEYCLOAK_SECRET;
  if (!clientSecret) throw new Error("AUTH_KEYCLOAK_SECRET ist nicht gesetzt");

  const res = await fetch(keycloakEndpoints.token(), {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      client_id: KEYCLOAK_CLIENT_ID,
      client_secret: clientSecret,
      refresh_token: refreshToken,
    }),
    cache: "no-store",
    signal: AbortSignal.timeout(8000),
  });

  if (!res.ok) {
    throw new Error(`Token-Refresh fehlgeschlagen (${res.status})`);
  }

  const data = (await res.json()) as {
    access_token: string;
    refresh_token?: string;
    id_token?: string;
    expires_in: number;
  };

  return {
    access_token: data.access_token,
    refresh_token: data.refresh_token,
    id_token: data.id_token,
    expires_at: Math.floor(Date.now() / 1000 + data.expires_in),
  };
}

/** Läuft das Access-Token innerhalb der nächsten `leewaySeconds` ab (oder ist es schon abgelaufen)? */
export function isExpiring(expiresAt: number | undefined, leewaySeconds = 60): boolean {
  if (!expiresAt) return true;
  return Date.now() / 1000 >= expiresAt - leewaySeconds;
}
