import { expect, test } from "@playwright/test";

/**
 * Story view is what a first-time viewer meets, so it is asserted directly: the map has to
 * dominate, the narration has to have a subject without anyone clicking, and the dense
 * control tower has to remain reachable in one click.
 */

const task = {
  id: "11111111-1111-1111-1111-111111111111", transportOrderId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  sequence: 1, loadId: "IN-001", source: "INBOUND-01", destination: "L-A1-B01-L01", status: "EXECUTING",
  route: ["INBOUND", "N-A", "S-A1"], assignedAgvId: "FL-01"
};

const order = {
  id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", type: "PUTAWAY", priority: "HIGH", status: "IN_PROGRESS",
  objective: "Clear inbound staging", createdAt: new Date().toISOString(), tasks: [task],
  vdaDispatches: [{
    id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", taskId: task.id, manufacturer: "demo", serialNumber: "FL-01",
    orderId: task.id, orderUpdateId: 4, status: "ACCEPTED", valid: true, createdAt: new Date().toISOString(),
    payload: JSON.stringify({ orderId: task.id, orderUpdateId: 4, nodes: [], edges: [] })
  }]
};

test.beforeEach(async ({ page }) => {
  // Mocking the snapshot alone does not isolate this spec: the app also opens a live
  // WebSocket to /ws, and real telemetry for the real FL-01 overwrites the mocked vehicle,
  // so assertions on the narration would pass or fail according to whatever the simulator
  // happens to be doing. Accepting the socket without connecting upstream keeps the page
  // driven entirely by the fixture below.
  await page.routeWebSocket("**/ws", () => { /* mock endpoint; never reaches the backend */ });

  await page.route("**/api/v1/warehouses/linz/snapshot", async (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      id: "linz", name: "Linz Central Warehouse", width: 48, depth: 36,
      racks: [{ id: "L-A1", name: "Rack L-A1", x: -7, z: -5.7, rotationY: 0, bays: 4 }],
      locations: [
        { id: "INBOUND-01", name: "Inbound", type: "INBOUND", capacity: 20, occupied: 1, reserved: 0, x: 17, z: -12, rotationY: 0 },
        { id: "OUTBOUND-01", name: "Outbound", type: "OUTBOUND", capacity: 20, occupied: 0, reserved: 0, x: 17, z: 12, rotationY: 0 }
      ],
      loads: [{ id: "IN-001", item: "ELECTRONICS", status: "INBOUND", locationId: "INBOUND-01", receivedAt: new Date().toISOString() }],
      agvs: [{
        id: "FL-01", x: 8.2, z: -4.7, theta: 0, velocity: 1.2, battery: 82, status: "MOVING", taskId: task.id,
        charging: false, handlingPhase: "IDLE", forkHeight: 0, forkExtension: 0, carriedLoadId: "IN-001"
      }],
      jobs: [], tasks: [task], transportOrders: [order],
      scenario: { id: "inbound-surge", name: "Inbound surge", configured: true },
      runtime: { operationState: "RUNNING", simulationEpoch: 11, timeScale: 2, scenarioId: "inbound-surge", scenarioConfigured: true, changedAt: new Date().toISOString() },
      conveyorTransfers: [], obstacles: []
    })
  }));
  await page.goto("/index.html?e2e=1", { waitUntil: "domcontentloaded", timeout: 120_000 });
  await expect(page.locator("#startup-status")).toHaveCount(0);
  await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();
});

test("opens in story view with the map dominant and no permanent rails", async ({ page }) => {
  await expect(page.locator(".narrativeBar")).toBeVisible();
  await expect(page.locator(".orderRail")).toHaveCount(0);
  await expect(page.locator(".orderDetail")).toHaveCount(0);
  await expect(page.locator(".mapKpis")).toHaveCount(0);

  // The map must own the viewport, not share it with two rails.
  const canvas = await page.locator("canvas.warehouseCanvas").boundingBox();
  const page_ = page.viewportSize();
  expect(canvas!.width).toBeGreaterThan((page_?.width ?? 1280) * 0.95);
});

test("narrates the live work without anyone selecting an order", async ({ page }) => {
  // Auto-focus: a narration with no subject would be worse than the dense screen.
  await expect(page.locator(".narrativeSentence")).toContainText("FL-01");
  await expect(page.locator(".narrativeSentence")).toContainText("IN-001");
  await expect(page.locator(".narrativeProof")).toContainText("VDA 5050 v3.0.0");
  await expect(page.locator(".narrativeProof")).toContainText("update 4");
  await expect(page.getByText("Following TO-AAAAAAAA")).toBeVisible();
});

test("shows every pipeline stage with exactly one current", async ({ page }) => {
  const stages = page.locator(".pipelineStrip > .sapMObjStatus");
  await expect(stages).toHaveCount(6);
  // An executing task sits on the vehicle, so AGV is the emphasised stage.
  await expect(page.locator(".pipelineStrip > .sapMObjStatusInverted")).toHaveCount(1);
});

test("explains the architecture on demand", async ({ page }) => {
  await page.getByRole("button", { name: "How this works" }).click();
  const dialog = page.getByRole("dialog", { name: "System design" });
  await expect(dialog).toBeVisible();
  await expect(dialog.locator(".systemNode")).toHaveCount(5);
  await expect(dialog).toContainText("OpenUI5 control tower");
  await expect(dialog).toContainText("Warehouse backend");
  await expect(dialog).toContainText("PostgreSQL");
  await expect(dialog).toContainText("MQTT broker");
  await expect(dialog).toContainText("FL-01 simulator");
  await expect(dialog).toContainText("ROBOT-01 + conveyors");
  await expect(dialog).toContainText("Durable command path");
  await expect(dialog).toContainText("Operational return path");
  await expect(dialog).toContainText("VDA 5050 v3.0.0");
  await expect(dialog).toContainText("not VDA-certified");
  const overflows = await dialog.locator(".howItWorksContent").evaluate((element) =>
    element.scrollWidth > element.clientWidth + 1);
  expect(overflows, "system diagram overflows the dialog horizontally").toBe(false);
  await dialog.getByRole("button", { name: "Close", exact: true }).click();
  await expect(dialog).toBeHidden();
});

test("engineer view is one click away and returns", async ({ page }) => {
  await page.getByRole("button", { name: "Engineer view" }).click();
  await expect(page.getByText("Transport orders", { exact: true })).toBeVisible();
  await expect(page.locator(".narrativeBar")).toHaveCount(0);
  await expect(page.locator(".activityStrip")).toBeVisible();

  await page.getByRole("button", { name: "Story view" }).click();
  await expect(page.locator(".narrativeBar")).toBeVisible();
  await expect(page.locator(".orderRail")).toHaveCount(0);
});

test("presenter controls stay reachable when the rails are hidden", async ({ page }) => {
  // Reset and simulation speed used to live in the order-rail footer, which story view hides.
  await page.getByRole("button", { name: "Presenter" }).click();
  const menu = page.getByRole("menu");
  await expect(menu.getByText("Reset scenario")).toBeVisible();
  await expect(menu.getByText("Simulation speed")).toBeVisible();
  await expect(menu.getByText("Receive inventory")).toBeVisible();
});

interface PanEntry { event: string; payload: { x: number; z: number } }

test("WASD pans the camera and leaves the vehicle alone", async ({ page }) => {
  const pans = async (): Promise<PanEntry[]> => page.evaluate(() =>
    ((window as unknown as { __warehouseAnimationTelemetry?: PanEntry[] }).__warehouseAnimationTelemetry ?? [])
      .filter((entry) => entry.event === "CAMERA_PANNED"));

  expect(await pans(), "no panning before any key is pressed").toEqual([]);

  await page.keyboard.down("KeyW");
  await page.waitForTimeout(900);
  await page.keyboard.up("KeyW");

  const moved = await pans();
  expect(moved.length, "holding W pans the camera").toBeGreaterThan(1);
  const first = moved[0].payload;
  const last = moved[moved.length - 1].payload;
  expect(Math.hypot(last.x - first.x, last.z - first.z)).toBeGreaterThan(0.5);
  // Panning may not strand the camera off the warehouse floor.
  expect(Math.abs(last.x)).toBeLessThanOrEqual(24);
  expect(Math.abs(last.z)).toBeLessThanOrEqual(18);

  // The vehicle is driven by telemetry, so camera input must not disturb the narration.
  await expect(page.locator(".narrativeSentence")).toContainText("FL-01");
});

test("typing in a form is not captured as camera input", async ({ page }) => {
  await page.getByRole("button", { name: "New transport order" }).click();
  const dialog = page.getByRole("dialog", { name: "New transport order" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("textbox").first().fill("Move pallet west");

  const pans = await page.evaluate(() =>
    ((window as unknown as { __warehouseAnimationTelemetry?: PanEntry[] }).__warehouseAnimationTelemetry ?? [])
      .filter((entry) => entry.event === "CAMERA_PANNED"));
  expect(pans, "the 'w' in the objective must not pan the camera").toEqual([]);
});
