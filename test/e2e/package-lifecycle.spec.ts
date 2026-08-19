import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

interface LoadState {
  id: string;
  status: string;
}

interface JobState {
  loadId: string;
  status: string;
}

interface CartonState {
  palletId: string;
  status: string;
}

interface Snapshot {
  loads: LoadState[];
  jobs: JobState[];
  cartons: CartonState[];
}

type BrowserMessages = string[];

const SKEW_MS = 240_000;

test.use({ viewport: { width: 1600, height: 900 } });

async function bootDashboard(page: Page) {
  const browserMessages: BrowserMessages = [];
  page.on("console", (message) => browserMessages.push(`[console:${message.type()}] ${message.text()}`));
  page.on("pageerror", (error) => browserMessages.push(`[pageerror] ${error.stack || error.message}`));

  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();
  await page.waitForTimeout(1_000);
  return browserMessages;
}

async function collectPackage(request: APIRequestContext) {
  const received = await request.post("/api/v1/warehouses/linz/inbound-loads", {
    data: { sku: "E2E-MOTION-PALLET", quantity: 1 }
  });
  expect(received.ok()).toBeTruthy();
  const receivedBody = await received.json() as { loads: LoadState[] };
  const loadId = receivedBody.loads[0]?.id;
  expect(loadId).toBeTruthy();
  return loadId as string;
}

async function getSnapshot(request: APIRequestContext): Promise<Snapshot> {
  const response = await request.get("/api/v1/warehouses/linz/snapshot");
  return response.json() as Promise<Snapshot>;
}

async function resetAndSpeedUp(request: APIRequestContext) {
  await request.post("/api/v1/warehouses/linz/operations/reset", { data: {} });
  await request.post("/api/v1/warehouses/linz/operations/speed", { data: { multiplier: 2 } });
}

test("package is collected", async ({ page, request }, testInfo) => {
  test.setTimeout(SKEW_MS);
  test.skip(!process.env.E2E_BASE_URL, "This scenario requires the live Docker application.");

  await resetAndSpeedUp(request);
  const browserMessages = await bootDashboard(page);
  const loadId = await collectPackage(request);

  const snapshot = await getSnapshot(request);
  const load = snapshot.loads.find((entry) => entry.id === loadId);
  expect(load?.status).toBe("INBOUND");
  expect(snapshot.cartons.filter((entry) => entry.palletId === loadId)).toHaveLength(4);

  await testInfo.attach("browser-log", { body: browserMessages.join("\n"), contentType: "text/plain" });
});

test("package is stored", async ({ page, request }, testInfo) => {
  test.setTimeout(SKEW_MS);
  test.skip(!process.env.E2E_BASE_URL, "This scenario requires the live Docker application.");

  await resetAndSpeedUp(request);
  const browserMessages = await bootDashboard(page);
  const loadId = await collectPackage(request);

  const accepted = await request.post("/api/v1/warehouses/linz/putaway-requests", {
    data: {
      inboundLoadIds: [loadId],
      operatorPrompt: "Store this pallet in the nearest eligible storage slot."
    }
  });
  expect(accepted.ok()).toBeTruthy();

  await expect.poll(async () => {
    const snapshot = await getSnapshot(request);
    return snapshot.jobs.find((job) => job.loadId === loadId)?.status;
  }, { timeout: 60_000 }).toMatch(/EXECUTING|COMPLETED/);

  await expect.poll(async () => {
    const snapshot = await getSnapshot(request);
    return snapshot.loads.find((load) => load.id === loadId)?.status;
  }, { timeout: 90_000, intervals: [500] }).toBe("STORED");

  await testInfo.attach("browser-log", { body: browserMessages.join("\n"), contentType: "text/plain" });
});

test("package is sent out", async ({ page, request }, testInfo) => {
  test.setTimeout(SKEW_MS);
  test.skip(!process.env.E2E_BASE_URL, "This scenario requires the live Docker application.");

  await resetAndSpeedUp(request);
  const browserMessages = await bootDashboard(page);
  const loadId = await collectPackage(request);

  await request.post("/api/v1/warehouses/linz/putaway-requests", {
    data: {
      inboundLoadIds: [loadId],
      operatorPrompt: "Store this pallet in the nearest eligible storage slot."
    }
  });

  await expect.poll(async () => {
    const snapshot = await getSnapshot(request);
    return snapshot.loads.find((load) => load.id === loadId)?.status;
  }, { timeout: 90_000, intervals: [500] }).toBe("STORED");

  const outbound = await request.post("/api/v1/warehouses/linz/outbound-requests", {
    data: { loadIds: [loadId] }
  });
  expect(outbound.ok()).toBeTruthy();

  await expect.poll(async () => {
    const snapshot = await getSnapshot(request);
    return snapshot.loads.find((load) => load.id === loadId)?.status;
  }, { timeout: 180_000, intervals: [250] }).toBe("ON_CONVEYOR");

  await expect.poll(async () => {
    const snapshot = await getSnapshot(request);
    return snapshot.loads.find((load) => load.id === loadId)?.status;
  }, { timeout: 30_000, intervals: [500] }).toBe("SHIPPED");

  await testInfo.attach("browser-log", { body: browserMessages.join("\n"), contentType: "text/plain" });
});
