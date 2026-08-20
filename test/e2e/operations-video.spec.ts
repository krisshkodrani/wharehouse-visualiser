import { expect, test } from "@playwright/test";
import { resetToQuiet } from "./support/warehouse";

interface LoadState {
  id: string;
  status: string;
}

interface JobState {
  loadId: string;
  status: string;
}

interface Location {
  id: string;
  type: string;
  x: number;
  z: number;
  operatingWidth?: number;
  operatingDepth?: number;
}

interface Snapshot {
  loads: LoadState[];
  jobs: JobState[];
  agvs: Array<{ id: string }>;
  cartons: Array<{ id: string; palletId: string; status: string }>;
  locations: Location[];
  conveyorTransfers: Array<{ loadId?: string; cartonId?: string; conveyorId?: string; status: string }>;
  obstacles: Array<{ id: string }>;
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

  await resetToQuiet(request, 2);
  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator("#startup-status")).toHaveCount(0);
  await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();
  await page.waitForTimeout(1500);

  const received = await request.post("/api/v1/warehouses/linz/inbound-loads", {
    data: { sku: "E2E-MOTION-PALLET", quantity: 1 }
  });
  expect(received.ok()).toBeTruthy();
  const receivedBody = await received.json() as { loads: LoadState[] };
  const loadId = receivedBody.loads[0]?.id;
  expect(loadId).toBeTruthy();

  const receivedSnapshot = await (await request.get("/api/v1/warehouses/linz/snapshot")).json() as Snapshot;
  expect(receivedSnapshot.cartons.filter((carton) => carton.palletId === loadId)).toHaveLength(4);

  // The reference fleet is single-vehicle by design (V20). Companion vehicles were
  // claimable for tasks but could never dock, so they drained and never recharged.
  expect(receivedSnapshot.agvs.map((vehicle) => vehicle.id)).toEqual(["FL-01"]);

  // V21 moved receiving staging but accidentally left the V5 guard rails around
  // its old footprint. Both crossed the valid handling envelope and rendered
  // through the forklift at the receiving stop.
  expect(receivedSnapshot.obstacles.map((obstacle) => obstacle.id)).not.toContain("REC-GUARD-W");
  expect(receivedSnapshot.obstacles.map((obstacle) => obstacle.id)).not.toContain("REC-GUARD-N");

  // No two station footprints may intersect. Three generations of outbound layout
  // used to sit on top of each other: the dock overlapped staging by 2.5 m, the
  // robot cell by another 2.2 m, and CHARGE-01 completely contained PARK-02.
  const footprints = receivedSnapshot.locations.filter((location) => location.type !== "STORAGE");
  expect(footprints.length).toBeGreaterThan(4);
  const overlaps: string[] = [];
  for (let outer = 0; outer < footprints.length; outer += 1) {
    for (let inner = outer + 1; inner < footprints.length; inner += 1) {
      const a = footprints[outer];
      const b = footprints[inner];
      const [aw, ad] = [a.operatingWidth ?? 7, a.operatingDepth ?? 7];
      const [bw, bd] = [b.operatingWidth ?? 7, b.operatingDepth ?? 7];
      const overlapX = Math.min(a.x + aw / 2, b.x + bw / 2) - Math.max(a.x - aw / 2, b.x - bw / 2);
      const overlapZ = Math.min(a.z + ad / 2, b.z + bd / 2) - Math.max(a.z - ad / 2, b.z - bd / 2);
      if (overlapX > 1e-6 && overlapZ > 1e-6) overlaps.push(`${a.id} / ${b.id}`);
    }
  }
  expect(overlaps, `overlapping station footprints: ${overlaps.join(", ")}`).toEqual([]);

  // Outbound flow runs west into the shipping dock: robot cell east of the
  // conveyors, conveyors east of the dock. Both lanes rotated to face west.
  const robot = receivedSnapshot.locations.find((location) => location.type === "ROBOT_CELL");
  const dock = receivedSnapshot.locations.find((location) => location.type === "OUTBOUND_DOCK");
  const lanes = receivedSnapshot.locations.filter((location) => location.type === "CONVEYOR");
  expect(robot).toBeTruthy();
  expect(dock).toBeTruthy();
  expect(lanes).toHaveLength(2);
  for (const lane of lanes) {
    expect(lane.x + (lane.operatingWidth ?? 0) / 2).toBeLessThanOrEqual(robot!.x - (robot!.operatingWidth ?? 0) / 2);
    expect(dock!.x + (dock!.operatingWidth ?? 0) / 2).toBeLessThanOrEqual(lane.x - (lane.operatingWidth ?? 0) / 2);
  }

  const accepted = await request.post("/api/v1/warehouses/linz/putaway-requests", {
    data: {
      inboundLoadIds: [loadId],
      operatorPrompt: "Store this pallet in the nearest eligible storage slot."
    }
  });
  expect(accepted.ok()).toBeTruthy();

  await expect.poll(async () => {
    const response = await request.get("/api/v1/warehouses/linz/snapshot");
    const snapshot = await response.json() as Snapshot;
    return snapshot.jobs.find((job) => job.loadId === loadId)?.status;
  }, { timeout: 60_000 }).toMatch(/EXECUTING|COMPLETED/);

  await expect.poll(async () => {
    const response = await request.get("/api/v1/warehouses/linz/snapshot");
    const snapshot = await response.json() as Snapshot;
    return snapshot.loads.find((load) => load.id === loadId)?.status;
  }, { timeout: 90_000, intervals: [500] }).toBe("STORED");
  await page.waitForTimeout(1800);

  // Sample the scene throughout the robot cycle. A snapshot used to create the
  // destination carton while the source visual was still attached to the gripper,
  // leaving the same carton both in the air and on the floor/conveyor.
  await page.evaluate(() => {
    const control = (window as unknown as {
      sap: { ui: { getCore(): { byId(id: string): { sceneController?: Record<string, never> } | undefined } } }
    }).sap.ui.getCore().byId("container-warehouseVisualizer---main--viewport");
    const controller = control?.sceneController as unknown as {
      scene: { meshes: Array<{ metadata?: { cartonId?: string; owner?: string } }> };
    };
    const probe = { duplicates: [] as string[], owners: [] as string[], sawHandoff: false, sawGripper: false, sawConveyor: false };
    (window as unknown as { __robotCartonProbe: unknown }).__robotCartonProbe = probe;
    window.setInterval(() => {
      const byCarton = new Map<string, string[]>();
      for (const mesh of controller.scene.meshes) {
        const cartonId = mesh.metadata?.cartonId;
        const owner = mesh.metadata?.owner;
        if (!cartonId || !owner) continue;
        byCarton.set(cartonId, [...(byCarton.get(cartonId) ?? []), owner]);
        probe.sawHandoff ||= owner === "HANDOFF";
        probe.sawGripper ||= owner === "GRIPPER";
        probe.sawConveyor ||= owner === "CONVEYOR";
      }
      for (const [cartonId, owners] of byCarton) {
        const uniqueOwners = [...new Set(owners)];
        if (uniqueOwners.length > 1 && !probe.duplicates.includes(cartonId)) probe.duplicates.push(cartonId);
        const sample = `${cartonId}:${uniqueOwners.join("+")}`;
        if (!probe.owners.includes(sample)) probe.owners.push(sample);
      }
    }, 50);
  });

  const outbound = await request.post("/api/v1/warehouses/linz/outbound-requests", {
    data: { loadIds: [loadId] }
  });
  expect(outbound.ok()).toBeTruthy();

  await expect.poll(async () => {
    const response = await request.get("/api/v1/warehouses/linz/snapshot");
    const snapshot = await response.json() as Snapshot;
    return snapshot.loads.find((load) => load.id === loadId)?.status;
  }, { timeout: 180_000, intervals: [250] }).toBe("ON_CONVEYOR");

  await expect.poll(async () => {
    const response = await request.get("/api/v1/warehouses/linz/snapshot");
    const snapshot = await response.json() as Snapshot;
    return snapshot.loads.find((load) => load.id === loadId)?.status;
  }, { timeout: 30_000, intervals: [500] }).toBe("SHIPPED");
  await page.waitForTimeout(1200);

  const fatalMessages = browserMessages.filter((message) => message.includes("[pageerror]"));
  expect(fatalMessages, fatalMessages.join("\n")).toEqual([]);
  const animationTelemetry = await page.evaluate(() =>
    (window as Window & { __warehouseAnimationTelemetry?: AnimationTelemetry[] }).__warehouseAnimationTelemetry ?? []
  );
  expect(animationTelemetry.some((entry) => entry.event === "LOAD_STATUS" && entry.payload.to === "IN_TRANSIT")).toBe(true);
  expect(animationTelemetry.some((entry) => entry.event === "LOAD_STATUS" && entry.payload.to === "ON_CONVEYOR")).toBe(true);
  expect(animationTelemetry.some((entry) => entry.event === "LOAD_STATUS" && entry.payload.to === "SHIPPED")).toBe(true);
  // LIVE_COLLISION_DETECTED, not LIVE_COLLISION_BLOCKED: the scene has only ever emitted the
  // former, so the old name made this assertion unfalsifiable. The rendered vehicle must not
  // pass through rack geometry at any point in the cycle.
  expect(animationTelemetry.filter((entry) => entry.event === "LIVE_COLLISION_DETECTED")).toEqual([]);

  // The scene may only be rebuilt for structural changes. Carton, robot-phase and
  // conveyor-transfer status used to feed the rebuild signature, so a single
  // outbound pallet tore down and rebuilt the whole scene ~35 times, destroying
  // pose interpolation and every in-flight animation. One rebuild for the offline
  // fallback plus one for the first real snapshot is the budget.
  const rebuilds = animationTelemetry.filter((entry) => entry.event === "SCENE_CONFIGURED");
  expect(rebuilds.length,
    `scene rebuilt ${rebuilds.length} times; operational state is leaking into the structural signature`)
    .toBeLessThanOrEqual(2);

  // The arm must actually work the carton through both handling phases.
  const phases = animationTelemetry.filter((entry) => entry.event === "ROBOT_PHASE").map((entry) => entry.payload.phase);
  expect(phases).toContain("PICKING");
  expect(phases).toContain("PLACING");
  // Nothing may be asked of the arm that it cannot physically reach.
  expect(animationTelemetry.filter((entry) => entry.event === "ROBOT_TARGET_OUT_OF_REACH")).toEqual([]);

  const cartonProbe = await page.evaluate(() => (window as unknown as {
    __robotCartonProbe: { duplicates: string[]; owners: string[]; sawHandoff: boolean; sawGripper: boolean; sawConveyor: boolean };
  }).__robotCartonProbe);
  expect(cartonProbe.sawHandoff, cartonProbe.owners.join(", ")).toBe(true);
  expect(cartonProbe.sawGripper, cartonProbe.owners.join(", ")).toBe(true);
  expect(cartonProbe.sawConveyor, cartonProbe.owners.join(", ")).toBe(true);
  expect(cartonProbe.duplicates, `cartons rendered by multiple owners: ${cartonProbe.owners.join(", ")}`).toEqual([]);

  // Cartons must be drawn on the lane the WCS actually assigned, and must travel
  // westwards (decreasing x) towards the dock.
  const placements = animationTelemetry.filter((entry) => entry.event === "CONVEYOR_CARGO_ADDED");
  expect(placements.length).toBeGreaterThan(0);
  for (const placement of placements) {
    expect(["CONV-OUT-01", "CONV-OUT-02"]).toContain(placement.payload.conveyorId);
    expect(Number(placement.payload.endX)).toBeLessThan(Number(placement.payload.startX));
  }
  await testInfo.attach("browser-log", { body: browserMessages.join("\n"), contentType: "text/plain" });
  await testInfo.attach("animation-telemetry", { body: JSON.stringify(animationTelemetry, null, 2), contentType: "application/json" });
});
