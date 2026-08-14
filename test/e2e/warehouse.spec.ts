import { expect, test } from "@playwright/test";

test("renders live controls and keeps manual driving in sandbox", async ({ page }, testInfo) => {
  const browserMessages: string[] = [];
  page.on("console", (message) => browserMessages.push(`[console:${message.type()}] ${message.text()}`));
  page.on("pageerror", (error) => browserMessages.push(`[pageerror] ${error.stack || error.message}`));

  await page.route("**/api/v1/warehouses/linz/snapshot", async (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      id: "linz", name: "Linz Central Warehouse", width: 48, depth: 36,
      racks: [
        { id: "L-A1", name: "Rack L-A1", x: -7, z: -5.7, rotationY: 0, bays: 4 },
        { id: "L-B1", name: "Rack L-B1", x: -7, z: 1.8, rotationY: 0, bays: 4 }
      ],
      locations: [],
      loads: [{ id: "PALLET-A-001", item: "PALLET-A", status: "INBOUND", locationId: "INBOUND-01" }],
      agvs: [{ id: "FL-01", x: 8.2, z: -4.7, theta: 0, battery: 82, status: "IDLE" }],
      jobs: [],
      runtime: { operationState: "RUNNING", simulationEpoch: 1, changedAt: new Date().toISOString() },
      conveyorTransfers: []
    })
  }));

  try {
    await page.goto("/index.html?e2e=1", { waitUntil: "domcontentloaded", timeout: 120_000 });
    await expect(page.locator("#startup-status")).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Warehouse AI Control", exact: true })).toBeVisible();
    await expect(page.getByText("Linz Central Warehouse", { exact: true })).toBeVisible();
    await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();
    await expect(page.getByRole("button", { name: "Store selected pallets" })).toBeVisible();
    await expect(page.getByText("PALLET-A-001 / PALLET-A", { exact: true })).toBeVisible();
    console.log("STEP live dashboard verified");

    const canvasBox = await page.locator("canvas.warehouseCanvas").boundingBox();
    expect(canvasBox?.width).toBeGreaterThan(700);
    expect(canvasBox?.height).toBeGreaterThan(300);

    await page.getByRole("option", { name: "Sandbox" }).click();
    await expect(page.getByText("Sandbox motion is local only and never changes jobs or inventory.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Store selected pallets" })).toBeHidden();
    console.log("STEP sandbox isolation verified");

    const canvas = page.locator("canvas.warehouseCanvas");
    await canvas.focus();
    await page.keyboard.down("f");
    await page.waitForTimeout(350);
    await page.keyboard.up("f");
    await expect.poll(async () => page.evaluate(() => {
      const entries = (window as Window & { __warehouseDiagnostics?: { entries: Array<{ stage: string; message: string }> } }).__warehouseDiagnostics?.entries ?? [];
      return entries.some((entry) => entry.stage === "forklift" && entry.message.includes("manual state") && !entry.message.endsWith("forks 0.00"));
    })).toBe(true);
    console.log("STEP sandbox manual lift verified");

    await page.getByRole("button", { name: "Run sandbox route" }).click();
    await expect.poll(async () => page.evaluate(() => {
      const entries = (window as Window & { __warehouseDiagnostics?: { entries: Array<{ stage: string; message: string }> } }).__warehouseDiagnostics?.entries ?? [];
      return entries.some((entry) => entry.stage === "forklift" && entry.message.includes("arrived"));
    }), { timeout: 10_000 }).toBe(true);
    console.log("STEP sandbox route verified");

    await page.getByRole("option", { name: "Live" }).click();
    await expect(page.getByRole("button", { name: "Store selected pallets" })).toBeVisible();
    console.log("STEP live mode restored");

    const fatalMessages = browserMessages.filter((message) => message.includes("[pageerror]") || message.includes("failed to load JavaScript resource"));
    expect(fatalMessages, fatalMessages.join("\n")).toEqual([]);
  } finally {
    await testInfo.attach("browser-log", { body: browserMessages.join("\n"), contentType: "text/plain" });
  }
});
