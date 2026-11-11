import type { Metadata } from "next";
import "@fontsource-variable/instrument-sans";
import "@fontsource-variable/geist-mono";
import "./globals.css";

const site = process.env.PUBLIC_URL ?? "https://demos.coach-raffael.com";

export const metadata: Metadata = {
  metadataBase: new URL(site),
  title: { default: "Showcases · Coach Raffael", template: "%s · Coach Raffael" },
  description: "Live demos and showcase projects by Raffael Hühnerschulte – software architecture you can click through.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
