// Typerweiterungen (Module Augmentation) für Auth.js.
// Session: was der Client sieht. JWT: was verschlüsselt im Cookie liegt.
import type { DefaultSession } from "next-auth";

declare module "next-auth" {
  interface Session {
    user: {
      /** Keycloak-`sub` */
      id: string;
      /** Vorname aus dem OIDC-Profil (`given_name`), für die Begrüßung */
      firstName?: string;
    } & DefaultSession["user"];
    /** Realm-Rollen aus dem Access-Token (nur für UI-Zwecke, nicht signaturgeprüft) */
    roles: string[];
    /** Gesetzt, wenn der Token-Refresh fehlgeschlagen ist – Nutzer muss sich neu anmelden */
    error?: "RefreshTokenError";
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    access_token?: string;
    refresh_token?: string;
    id_token?: string;
    /** Unix-Sekunden, ab denen das Access-Token ungültig ist */
    expires_at?: number;
    roles?: string[];
    given_name?: string;
    error?: "RefreshTokenError";
  }
}
