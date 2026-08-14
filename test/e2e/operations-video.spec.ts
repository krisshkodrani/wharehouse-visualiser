import { expect, test } from "@playwright/test";

interface LoadState {
  id: string;
  status: string;
}

interface JobState {
  loadId: string;
  status: string;
}

interface Snapshot {
  loads: LoadState[];
  jobs: JobState[];
}

interface AnimationTelemetry {
  time: string;
  event: string;
  payload: Record<string, unknown>;
}

test.use({ viewport: { width: 1600, height: 900 } });

test("records one complete putaway and outbound transfer", async ({ page, request }, testInfo) => {
  test.setTimeout(300_000);
  test.skip(!process.env.E2E_BASE_URL, "This scenario requires the live Docker application.");

  const browserMessages: string[] = [];
  page.on("console", (message) => browserMessages.push(`[console:${message.type()}] ${message.text()}`));
  page.on("pageerror", (error) => browserMessages.push(`[pageerror] ${error.stack || error.message}`));

  await request.post("/api/v1/warehouses/linz/operations/reset", { data: {} });
  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator("#startup-status")).toHaveCount(0);
  await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();
  await page.waitForTimeout(1500);

  const accepted = await request.post("/api/v1/warehouses/linz/putaway-requests", {
    data: {
      inboundLoadIds: ["PALLET-A-001"],
      operatorPrompt: "Store this pallet in the nearest eligible storage slot."
    }
  });
  expect(accepted.ok()).toBeTruthy();

  await expect.poll(async () => {
    const response = await request.get("/api/v1/warehouses/linz/snapshot");
    const snapshot = await response.json() as Snapshot;
    return snapshot.jobs.find((job) => job.loadId === "PALLET-A-001")?.status;
  }, { timeout: 60_000 }).toMatch(/EXECUTING|COMPLETED/);

  await expect.poll(async () => {
    const response = await request.get("/api/v1/warehouses/linz/snapshot");
    const snapshot = await response.json() as Snapshot;
    return snapshot.loads.find((load) => load.id === "PALLET-A-001")?.status;
  }, { timeout: 90_000, intervals: [500] }).toBe("STORED");
  await page.waitForTimeout(1800);

  const outbound = await request.post("/api/v1/warehouses/linz/outbound-requests", {
    data: { loadIds: ["PALLET-A-001"] }
  });
  expect(outbound.ok()).toBeTruthy();

  await expect.poll(async () => {
    const response = await request.get("/api/v1/warehouses/linz/snapshot");
    const snapshot = await response.json() as Snapshot;
    return snapshot.loads.find((load) => load.id === "PALLET-A-001")?.status;
  }, { timeout: 180_000, intervals: [250] }).toBe("ON_CONVEYOR");

  await expect.poll(async () => {
    const response = await request.get("/api/v1/warehouses/linz/snapshot");
    const snapshot = await response.json() as Snapshot;
    return snapshot.loads.find((load) => load.id === "PALLET-A-001")?.status;
  }, { timeout: 30_000, intervals: [500] }).toBe("SHIPPED");
  await page.waitForTimeout(1200);

  const fatalMessages = browserMessages.filter((message) => message.includes("[pageerror]"));
  expect(fatalMessages, fatalMessages.join("\n")).toEqual([]);
  const animationTelemetry = await page.evaluate(() =>
    (window as Window & { __warehouseAnimationTelemetry?: AnimationTelemetry[] }).__warehouseAnimationTelemetry ?? []
  );
  expect(animationTelemetry.some((entry) => entry.event === "CARGO_ATTACHED")).toBe(true);
  expect(animationTelemetry.some((entry) => entry.event === "CARGO_DETACHED")).toBe(true);
  expect(animationTelemetry.filter((entry) => entry.event === "LIVE_COLLISION_BLOCKED")).toEqual([]);
  await testInfo.attach("browser-log", { body: browserMessages.join("\n"), contentType: "text/plain" });
  await testInfo.attach("animation-telemetry", { body: JSON.stringify(animationTelemetry, null, 2), contentType: "application/json" });
});
