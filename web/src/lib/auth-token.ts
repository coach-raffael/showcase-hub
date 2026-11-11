// Serverseitiger Zugriff auf die Keycloak-Tokens der aktuellen Sitzung.
//
// Warum nicht einfach `auth()`? Der `session`-Callback liefert dasselbe Objekt
// an Server Components *und* an den Browser (`GET /auth/session`). Ein dort
// eingebautes Access-Token würde also an den Client durchgereicht. Auth.js v5
// kennt kein „server-only“-Feld in der Session.
//
// Der saubere Weg in Auth.js v5 ist deshalb `getToken()` aus `next-auth/jwt`:
// es entschlüsselt das Session-Cookie (JWE) direkt – mit `secret` (AUTH_SECRET)
// und `salt` = Cookie-Name, genau so wie Auth.js es beim Schreiben macht. Das
// Ergebnis ist das vollständige JWT inkl. `access_token`, `refresh_token` und
// `id_token`, ohne dass davon etwas den Server verlässt.
//
// Refresh: Läuft das Access-Token in ≤ 60 s ab, wird es hier erneuert. In Server
// Components lässt sich das Cookie dabei nicht zurückschreiben; dauerhaft
// persistiert wird der Refresh durch `auth` in `src/proxy.ts`, das bei jedem
// Seitenaufruf vor dem Rendern läuft und das Cookie aktualisiert.

import { cache } from "react";
import { headers } from "next/headers";
import { getToken, type JWT } from "next-auth/jwt";
import { isExpiring, refreshAccessToken, usesSecureCookies } from "./keycloak";

/** Cookie-Name von Auth.js: mit `__Secure-`-Präfix, wenn `AUTH_URL` https ist. */
function sessionCookieName(): string {
  return `${usesSecureCookies() ? "__Secure-" : ""}authjs.session-token`;
}

/**
 * Entschlüsseltes Session-JWT der aktuellen Anfrage (pro Request gecacht).
 * `null`, wenn keine Sitzung besteht oder das Cookie nicht lesbar ist.
 */
const readSessionJwt = cache(async (): Promise<JWT | null> => {
  const secret = process.env.AUTH_SECRET;
  if (!secret) return null;

  const cookieName = sessionCookieName();
  try {
    const jwt = await getToken({
      req: { headers: await headers() },
      secret,
      cookieName,
      salt: cookieName,
      secureCookie: usesSecureCookies(),
    });
    if (!jwt?.access_token) return jwt;

    // Access-Token (fast) abgelaufen → für diesen Request frisch holen.
    if (isExpiring(jwt.expires_at) && jwt.refresh_token) {
      try {
        const refreshed = await refreshAccessToken(jwt.refresh_token);
        return {
          ...jwt,
          access_token: refreshed.access_token,
          refresh_token: refreshed.refresh_token ?? jwt.refresh_token,
          id_token: refreshed.id_token ?? jwt.id_token,
          expires_at: refreshed.expires_at,
        };
      } catch {
        // Refresh gescheitert: Token als unbrauchbar behandeln, Sitzung bleibt bestehen
        // (der `jwt`-Callback setzt parallel `error: "RefreshTokenError"`).
        return { ...jwt, access_token: undefined, error: "RefreshTokenError" };
      }
    }
    return jwt;
  } catch {
    return null;
  }
});

/** Gültiges Keycloak-Access-Token der Sitzung oder `undefined` (nicht angemeldet / Refresh fehlgeschlagen). */
export async function getAccessToken(): Promise<string | undefined> {
  const jwt = await readSessionJwt();
  if (!jwt?.access_token || isExpiring(jwt.expires_at, 0)) return undefined;
  return jwt.access_token;
}

/** ID-Token der Sitzung – wird als `id_token_hint` für den Keycloak-Logout gebraucht. */
export async function getIdToken(): Promise<string | undefined> {
  const jwt = await readSessionJwt();
  return jwt?.id_token;
}
