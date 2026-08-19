import { expect, test } from "@playwright/test";

/**
 * A pallet must stay visible while it changes hands.
 *
 * Cargo used to be destroyed and recreated at every handover, and the two streams that drive
 * one -- the snapshot deciding what belongs on a shelf, telemetry deciding what the fork holds
 * -- do not coordinate. Whichever lost the race left a gap with no box anywhere: a shelf pallet
 * shrinking to nothing and a new one popping onto the fork, a pallet arriving late after a drop
 * (measured at 5.6 s on the live stack), or a pallet that never appeared at all.
 *
 * The scene now parks a visual between owners instead of disposing it, which makes the bug
 * expressible as an invariant: every CARGO_ORPHANED is claimed. CARGO_EXPIRED is the failure --
 * it means the pallet was retired while still in limbo, which is exactly the moment the viewer
 * sees nothing.
 *
 * This watches the handovers the running scenario produces rather than driving a pallet through
 * a full putaway. An earlier version orchestrated its own load and proved too slow under the CPU
 * load of a full suite run: the journey outran its budget, and the reset it needed also killed
 * whatever order the previous spec still had in flight.
 */

interface TelemetryEntry { event: string; payload: Record<string, unknown> }

const CARGO_EVENTS = ["CARGO_ORPHANED", "CARGO_ADOPTED", "CARGO_ATTACHED", "CARGO_EXPIRED"];

test.use({ viewport: { width: 1400, height: 900 } });

test("a pallet is never lost between the shelf, the fork and the floor", async ({ page, request }) => {
  test.setTimeout(240_000);
  test.skip(!process.env.E2E_BASE_URL, "This scenario requires the live Docker application.");

  // Seed a scenario rather than resetting to the chooser: the fleet then has work of its own,
  // so handovers happen without this spec having to drive one, and no in-flight order is torn
  // out from under a spec that ran before this one.
  await request.post("/api/v1/warehouses/linz/scenario", { data: { presetId: "balanced-shift" } });
  await request.post("/api/v1/warehouses/linz/operations/speed", { data: { multiplier: 4 } });

  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();

  const cargoTelemetry = async (): Promise<TelemetryEntry[]> => page.evaluate((events) =>
    ((window as unknown as { __warehouseAnimationTelemetry?: TelemetryEntry[] }).__warehouseAnimationTelemetry ?? [])
      .filter((entry) => events.includes(entry.event)), CARGO_EVENTS) as Promise<TelemetryEntry[]>;

  // Wait for the fleet to actually hand a pallet over, so the assertions below are not vacuous.
  await expect.poll(async () => (await cargoTelemetry())
    .filter((entry) => entry.event === "CARGO_ORPHANED").length,
  { timeout: 180_000, intervals: [1000] }).toBeGreaterThan(0);

  // Give the claim that follows a handover time to land.
  await page.waitForTimeout(6000);

  const telemetry = await cargoTelemetry();

  // The invariant. An expiry means a pallet was retired while still in limbo -- the gap the
  // viewer used to see as a missing box.
  const expired = telemetry.filter((entry) => entry.event === "CARGO_EXPIRED");
  expect(expired.map((entry) => entry.payload?.loadId), "pallets left unclaimed mid-handover").toEqual([]);

  // Every handover that started must have finished, per pallet: an orphan is followed by the
  // fork taking it or by a slot adopting it.
  const byLoad = new Map<string, string[]>();
  for (const entry of telemetry) {
    const loadId = String(entry.payload?.loadId ?? "");
    if (!loadId) continue;
    byLoad.set(loadId, [...(byLoad.get(loadId) ?? []), entry.event]);
  }
  for (const [loadId, sequence] of byLoad) {
    const lastOrphan = sequence.lastIndexOf("CARGO_ORPHANED");
    if (lastOrphan === -1) continue;
    const claimed = sequence.slice(lastOrphan + 1).some((event) => event === "CARGO_ADOPTED" || event === "CARGO_ATTACHED");
    // A pallet still in flight when the spec ends is fine; it simply has not been claimed yet,
    // and the expiry assertion above already proves it was not thrown away.
    if (!claimed) expect(expired.some((entry) => entry.payload?.loadId === loadId),
      `pallet ${loadId} (${sequence.join(",")}) ended orphaned and expired`).toBe(false);
  }
});

/**
 * The mirror of the invariant above: a pallet must also never be in *two* places.
 *
 * syncCarriedCargo splices the carried item out of cargoItems, and that same list is the
 * "already built" test syncRackCargo uses -- so the snapshot, which goes on listing the
 * load in its old slot until the task status catches up, built a second pallet on the
 * shelf while the first rode away on the fork. Measured before the fix at 7.6 s of dual
 * ownership and a duplicate mesh that outlived the carry by twelve seconds. Read from the
 * ground truth in the scene graph rather than from telemetry: the defect is the existence
 * of two nodes, so counting nodes is what actually proves it gone.
 */
test("a pallet on the fork is never rebuilt in the slot it came from", async ({ page, request }) => {
  test.setTimeout(240_000);
  test.skip(!process.env.E2E_BASE_URL, "This scenario requires the live Docker application.");

  await request.post("/api/v1/warehouses/linz/scenario", { data: { presetId: "balanced-shift" } });
  await request.post("/api/v1/warehouses/linz/operations/speed", { data: { multiplier: 4 } });

  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();

  // Unlike the spec above, this one drives its own load. It has to: the duplicate only
  // exists between the fork taking a pallet off a shelf and that load's status catching
  // up, and an ambient scenario gives no control over whether that window is ever entered
  // while the probe is watching. A version that only watched what the fleet happened to do
  // passed with the guard deliberately removed -- i.e. it proved nothing.
  const storedLoadId = await (async () => {
    const snapshot = await (await request.get("/api/v1/warehouses/linz/snapshot")).json() as
      { loads: { id: string; status: string }[] };
    const stored = snapshot.loads.find((load) => load.status === "STORED");
    expect(stored, "scenario produced no STORED load to pull from a rack").toBeTruthy();
    return (stored as { id: string }).id;
  })();

  // Sample the scene from inside the page for the whole run. A duplicate is transient --
  // it lasts only until the load's status catches up -- so a poll that looked once could
  // step straight over it.
  await page.evaluate(() => {
    const scene = (window as unknown as { __cargoProbe?: unknown }).__cargoProbe;
    if (scene) return;
    const control = (window as unknown as {
      sap: { ui: { getCore(): { byId(id: string): { sceneController?: Record<string, never> } | undefined } } }
    }).sap.ui.getCore().byId("container-warehouseVisualizer---main--viewport");
    const controller = control?.sceneController as unknown as {
      scene: { transformNodes: { name: string }[] };
      cargoItems: { id: string }[];
      inboundCargoItems: { id: string }[];
      carriedCargo?: { id: string };
    };
    const worst: {
      duplicateNodes: string[]; doubleOwned: string[]; carriedSamples: number; nodeSamples: number; carriedIds: string[];
    } = { duplicateNodes: [], doubleOwned: [], carriedSamples: 0, nodeSamples: 0, carriedIds: [] };
    (window as unknown as { __cargoProbe: unknown }).__cargoProbe = worst;
    window.setInterval(() => {
      const counts = new Map<string, number>();
      for (const node of controller.scene.transformNodes)
        if (/^(cargo|inboundCargo)-/.test(node.name)) counts.set(node.name, (counts.get(node.name) ?? 0) + 1);
      worst.nodeSamples = Math.max(worst.nodeSamples, counts.size);
      for (const [name, n] of counts) if (n > 1 && !worst.duplicateNodes.includes(name)) worst.duplicateNodes.push(name);
      const carried = controller.carriedCargo?.id;
      if (carried) worst.carriedSamples += 1;
      if (carried && !worst.carriedIds.includes(carried)) worst.carriedIds.push(carried);
      if (carried && [...controller.cargoItems, ...controller.inboundCargoItems].some((item) => item.id === carried)
        && !worst.doubleOwned.includes(carried)) worst.doubleOwned.push(carried);
    }, 100);
  });

  // Pull this specific pallet off its shelf, which is the move that used to duplicate it.
  const outbound = await request.post("/api/v1/warehouses/linz/outbound-requests", { data: { loadIds: [storedLoadId] } });
  expect(outbound.ok(), `outbound request for ${storedLoadId} failed: HTTP ${outbound.status()}`).toBeTruthy();

  // Wait until the fork is genuinely holding it -- the start of the window under test.
  await expect.poll(async () => page.evaluate(() => (window as unknown as {
    __cargoProbe: { carriedIds: string[] } }).__cargoProbe.carriedIds),
  { timeout: 180_000, intervals: [500], message: "the fork never picked the pallet up" })
    .toContain(storedLoadId);

  // Let the carry play out. The duplicate previously persisted for ~20 s.
  await page.waitForTimeout(20_000);

  const probe = await page.evaluate(() => (window as unknown as {
    __cargoProbe: {
      duplicateNodes: string[]; doubleOwned: string[]; carriedSamples: number; nodeSamples: number; carriedIds: string[];
    }
  }).__cargoProbe);

  // Read the probe first, then put the warehouse back before asserting. Unlike the spec
  // above, this one drives an outbound of its own, and leaving that in flight starves the
  // specs that follow: they reset and immediately queue work while the fleet is still busy
  // with this one's order. Cleaning up before the assertions means a failure here cannot
  // cascade into unrelated failures elsewhere in the suite.
  await request.post("/api/v1/warehouses/linz/operations/reset", { data: {} });

  // Prove the probe was actually looking at something before trusting its two empties: it
  // must have seen cargo nodes, and it must have caught this pallet on the fork, which is
  // the only window in which the duplicate could ever have appeared.
  expect(probe.nodeSamples, "probe never saw any cargo nodes -- it is not reading the scene").toBeGreaterThan(0);
  expect(probe.carriedIds, "probe never caught the pallet under test on the fork -- assertions would be vacuous")
    .toContain(storedLoadId);

  expect(probe.duplicateNodes, "cargo nodes rebuilt while the fork held the pallet").toEqual([]);
  expect(probe.doubleOwned, "loads owned by a slot and the fork at the same time").toEqual([]);
});
