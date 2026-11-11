import NextAuth from "next-auth";
import type { JWT } from "next-auth/jwt";
import Keycloak from "next-auth/providers/keycloak";
import {
  AUTH_BASE_PATH,
  KEYCLOAK_CLIENT_ID,
  KEYCLOAK_ISSUER,
  isExpiring,
  refreshAccessToken,
  rolesFromAccessToken,
} from "@/lib/keycloak";

/**
 * Auth.js v5 mit Keycloak (Realm coach-raffael, Client demos-web) – gleiches Muster wie crm-tool:
 * Endpunkte unter /auth (weil /api zum Spring-Backend geht), Tokens verschlüsselt im
 * Session-Cookie, Refresh im jwt-Callback und persistiert über src/proxy.ts.
 */
export const { handlers, auth, signIn, signOut } = NextAuth({
  basePath: AUTH_BASE_PATH,
  trustHost: true,
  providers: [
    Keycloak({
      clientId: KEYCLOAK_CLIENT_ID,
      clientSecret: process.env.AUTH_KEYCLOAK_SECRET,
      issuer: KEYCLOAK_ISSUER,
      checks: ["pkce", "state"],
    }),
  ],
  session: { strategy: "jwt" },
  callbacks: {
    async jwt({ token, account, profile }): Promise<JWT> {
      if (account) {
        return {
          ...token,
          access_token: account.access_token,
          refresh_token: account.refresh_token,
          id_token: account.id_token,
          expires_at: account.expires_at,
          roles: rolesFromAccessToken(account.access_token),
          given_name: typeof profile?.given_name === "string" ? profile.given_name : undefined,
          error: undefined,
        };
      }
      if (!isExpiring(token.expires_at)) return token;
      if (!token.refresh_token) return { ...token, error: "RefreshTokenError" };
      try {
        const refreshed = await refreshAccessToken(token.refresh_token);
        return {
          ...token,
          access_token: refreshed.access_token,
          refresh_token: refreshed.refresh_token ?? token.refresh_token,
          id_token: refreshed.id_token ?? token.id_token,
          expires_at: refreshed.expires_at,
          roles: rolesFromAccessToken(refreshed.access_token),
          error: undefined,
        };
      } catch (err) {
        console.warn("[auth] Token-Refresh fehlgeschlagen:", err instanceof Error ? err.message : err);
        return { ...token, error: "RefreshTokenError" };
      }
    },
    async session({ session, token }) {
      session.user.id = token.sub ?? "";
      session.user.firstName = token.given_name;
      session.roles = token.roles ?? [];
      session.error = token.error;
      return session;
    },
  },
});
