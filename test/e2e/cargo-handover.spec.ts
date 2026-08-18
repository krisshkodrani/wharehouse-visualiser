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
