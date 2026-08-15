import { expect, test } from "@playwright/test";

test("renders the seeded VDA 5050 control tower and unified order workflow", async ({ page }, testInfo) => {
  const browserMessages: string[] = [];
  page.on("console", (message) => browserMessages.push(`[console:${message.type()}] ${message.text()}`));
  page.on("pageerror", (error) => browserMessages.push(`[pageerror] ${error.stack || error.message}`));

  const task = {
    id: "11111111-1111-1111-1111-111111111111", transportOrderId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    sequence: 1, loadId: "IN-001", source: "INBOUND-01", destination: "L-A1-B01-L01", status: "EXECUTING",
    route: ["INBOUND", "N-A", "S-A1"], assignedAgvId: "FL-01"
  };
  const order = {
    id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", type: "PUTAWAY", priority: "HIGH", status: "IN_PROGRESS",
    objective: "Clear inbound staging", scenarioId: "inbound-surge", createdAt: new Date().toISOString(), tasks: [task],
    vdaDispatches: [{
      id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", taskId: task.id, manufacturer: "demo", serialNumber: "FL-01",
      orderId: task.id, orderUpdateId: 0, status: "ACCEPTED", valid: true, createdAt: new Date().toISOString(),
      payload: JSON.stringify({ orderId: task.id, orderUpdateId: 0, nodes: [{ nodeId: "INBOUND", sequenceId: 0, released: true }], edges: [] })
    }]
  };

  await page.route("**/api/v1/warehouses/linz/snapshot", async (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      id: "linz", name: "Linz Central Warehouse", width: 48, depth: 36,
      racks: [
        { id: "L-A1", name: "Rack L-A1", x: -7, z: -5.7, rotationY: 0, bays: 4 },
        { id: "L-B1", name: "Rack L-B1", x: -7, z: 1.8, rotationY: 0, bays: 4 }
      ],
      locations: [
        { id: "INBOUND-01", name: "Inbound", type: "INBOUND", capacity: 20, occupied: 1, reserved: 0, x: 17, z: -12, rotationY: 0 },
        { id: "OUTBOUND-01", name: "Outbound", type: "OUTBOUND", capacity: 20, occupied: 0, reserved: 0, x: 17, z: 12, rotationY: 0 }
      ],
      loads: [{ id: "IN-001", item: "ELECTRONICS", status: "INBOUND", locationId: "INBOUND-01", receivedAt: new Date().toISOString() }],
      agvs: [{ id: "FL-01", x: 8.2, z: -4.7, theta: 0, velocity: 1.2, battery: 82, status: "MOVING", taskId: task.id,
        charging: false, handlingPhase: "IDLE", forkHeight: 0, forkExtension: 0 }],
      jobs: [], tasks: [task], transportOrders: [order], scenario: { id: "inbound-surge", name: "Inbound surge", configured: true },
      runtime: { operationState: "RUNNING", simulationEpoch: 11, timeScale: 2, scenarioId: "inbound-surge", scenarioConfigured: true, changedAt: new Date().toISOString() },
      conveyorTransfers: [], obstacles: []
    })
  }));

  try {
    await page.goto("/index.html?e2e=1", { waitUntil: "domcontentloaded", timeout: 120_000 });
    await expect(page.locator("#startup-status")).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Warehouse Control Tower", exact: true })).toBeVisible();
    await expect(page.getByText("Transport orders", { exact: true })).toBeVisible();
    await expect(page.getByText("TO-AAAAAAAA", { exact: true }).first()).toBeVisible();
    await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();

    const canvasBox = await page.locator("canvas.warehouseCanvas").boundingBox();
    expect(canvasBox?.width).toBeGreaterThan(400);
    expect(canvasBox?.height).toBeGreaterThan(300);

    await page.getByText("TO-AAAAAAAA", { exact: true }).first().click();
    await page.getByRole("button", { name: "More" }).click();
    await page.locator(".sapMITBSelectItem").filter({ hasText: "VDA 5050" }).click();
    await expect(page.getByText("VDA 5050 v3.0.0", { exact: true })).toBeVisible();
    await expect(page.getByText("Order update 0", { exact: true })).toBeVisible();

    await page.getByRole("button", { name: "New transport order" }).click();
    const orderDialog = page.getByRole("dialog", { name: "New transport order" });
    await expect(orderDialog).toBeVisible();
    await expect(page.getByText("Priority affects fleet scheduling, not the VDA payload.", { exact: false })).toBeVisible();
    await orderDialog.getByRole("button", { name: "Cancel" }).click();

    const fatalMessages = browserMessages.filter((message) => message.includes("[pageerror]") ||
      (message.includes("failed to load JavaScript resource") && !message.includes("Component-preload.js")));
    expect(fatalMessages, fatalMessages.join("\n")).toEqual([]);
  } finally {
    await testInfo.attach("browser-log", { body: browserMessages.join("\n"), contentType: "text/plain" });
  }
});
