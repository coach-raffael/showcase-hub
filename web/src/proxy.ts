// Proxy (ehemals Middleware): lässt Auth.js im Adminbereich bei jedem Seitenaufruf laufen, damit ein erneuertes
// Access-Token ins Session-Cookie zurückgeschrieben wird. Galerie und Schlafseiten brauchen keine Sitzung.
export { auth as proxy } from "@/auth";

export const config = {
  matcher: ["/admin/:path*"],
};
