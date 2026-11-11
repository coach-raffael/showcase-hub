import type { NextConfig } from "next";

// Basis-URL des Spring-Boot-Backends (ohne Context-Path /api).
const apiUrl = (process.env.API_URL ?? "http://localhost:8080").replace(/\/$/, "");

const nextConfig: NextConfig = {
  poweredByHeader: false,
  output: "standalone",
  turbopack: {
    rules: {
      "*.css": { loaders: ["@tailwindcss/turbopack"], as: "*.css" },
    },
  },
  async rewrites() {
    // Dev: Browser-Aufrufe an /api/* gehen ans Backend. In Produktion routet Traefik /api direkt.
    return [{ source: "/api/:path*", destination: `${apiUrl}/api/:path*` }];
  },
};

export default nextConfig;
