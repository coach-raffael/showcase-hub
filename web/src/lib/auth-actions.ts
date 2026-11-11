"use server";

import { redirect } from "next/navigation";
import { signIn, signOut } from "@/auth";
import { getIdToken } from "./auth-token";
import { KEYCLOAK_CLIENT_ID, appUrl, keycloakEndpoints } from "./keycloak";

/** Login über Keycloak (Authorization Code + PKCE), danach in den Adminbereich. */
export async function signInWithKeycloak() {
  await signIn("keycloak", { redirectTo: "/admin" });
}

/** Abmelden bei Auth.js und Keycloak (RP-Initiated Logout), zurück zur Galerie. */
export async function signOutEverywhere() {
  const idToken = await getIdToken();
  await signOut({ redirect: false });
  const params = new URLSearchParams({
    client_id: KEYCLOAK_CLIENT_ID,
    post_logout_redirect_uri: `${appUrl()}/`,
  });
  if (idToken) params.set("id_token_hint", idToken);
  redirect(`${keycloakEndpoints.logout()}?${params.toString()}`);
}
