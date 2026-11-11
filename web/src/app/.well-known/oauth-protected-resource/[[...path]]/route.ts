// OAuth Protected Resource Metadata (RFC 9728) für den MCP-Endpunkt – wie im CRM.
// claude.ai bekommt beim ersten Aufruf von /api/mcp ein 401 mit
//   WWW-Authenticate: Bearer resource_metadata=".../.well-known/oauth-protected-resource/api/mcp"
// und findet hier den Keycloak-Realm als Autorisierungsserver (Client demos-mcp).
// Liegt im Web-Container, weil Traefik nur /api/* an die API routet.

const ISSUER = (process.env.AUTH_KEYCLOAK_ISSUER ?? "https://auth.coach-raffael.com/realms/coach-raffael").replace(/\/$/, "");

function origin(): string {
  const auth = process.env.AUTH_URL ?? "http://localhost:3000/auth";
  return new URL(auth).origin;
}

export function GET() {
  const base = origin();
  return Response.json(
    {
      resource: `${base}/api/mcp`,
      authorization_servers: [ISSUER],
      bearer_methods_supported: ["header"],
      scopes_supported: ["openid", "profile", "email"],
      resource_name: "Showcase-Hub (Coach Raffael)",
      resource_documentation: `${base}/admin/mcp`,
    },
    { headers: { "Cache-Control": "public, max-age=300", "Access-Control-Allow-Origin": "*" } },
  );
}
