import { expect, test } from "@playwright/test";

// Der Ablauf, den die Demo zeigen soll – von der Startseite bis zum Ergebnis. Ein Showcase ohne
// funktionierenden Kernablauf wird nicht öffentlich.
test("visitor completes the core flow", async ({ page }) => {
  await page.goto("./");
  await expect(page).toHaveTitle(/My Showcase/);
  await expect(page.getByRole("banner")).toContainText(/showcase/i); // Demo-Banner
  // TODO im neuen Repo: echten Ablauf beschreiben (suchen → auswählen → abschließen)
});
