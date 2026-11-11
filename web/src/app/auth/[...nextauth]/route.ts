// Auth.js-Endpunkte unter /auth/* (signin, callback/keycloak, session, signout, csrf, …).
// Liegt bewusst nicht unter /api, weil /api/* in Produktion an das Spring-Backend geht.
import { handlers } from "@/auth";

export const { GET, POST } = handlers;
