import { expect, test } from "@playwright/test";

test("browser unit suite passes", async ({ page }) => {
  await page.goto("/test/unit/unitTests.qunit.html", { waitUntil: "domcontentloaded" });
  const result = page.locator("#qunit-testresult-display");
  await expect(result).toContainText("completed", { timeout: 60_000 });
  await expect(result).toContainText("0 failed");
});
