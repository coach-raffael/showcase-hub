import { defineConfig, devices } from "@playwright/test";

// BASE_URL setzt deploy-demo.yml auf die laufende Demo, mit / am Ende: https://demos.coach-raffael.com/my-showcase/
// (sonst löst Playwright "./" gegen die Domain-Wurzel auf)
export default defineConfig({
  testDir: ".",
  timeout: 60_000,
  retries: 1,
  use: { baseURL: process.env.BASE_URL ?? "http://localhost:3000", trace: "retain-on-failure" },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile", use: { ...devices["Pixel 7"] } },
  ],
});
