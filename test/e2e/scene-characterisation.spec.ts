import { expect, test } from "@playwright/test";
import { resetToQuiet, seedFromQuiet, waitForQuiet } from "./support/warehouse";
import { installCargoProbe, readCargoProbe, installForkRecorder, readForkFrames, forkTravel, meshBounds }
  from "./support/scene";

/**
 * Characterisation tests for the Babylon scene, written to be refactored against.
 *
 * <p>The planned refactor breaks `WarehouseScene.ts` -- 2,087 lines, the largest file in the
 * repo -- into entities, factories, animators and interpolators across ten phases. The
 * Babylon layer currently has no unit coverage at all, and every defect found in it so far
 * was invisible to the rest of the suite: a pallet drawn twice for 19.7 s, staged pallets
 * losing their material to a shared-material disposal, a mast that stepped because only the
 * chassis was interpolated, cargo passing through a door header. None of those move a status
 * field, so nothing that reads the API can see them.
 *
 * <p>These assert observable scene behaviour rather than structure, so meshes are free to
 * move between files. They are the invariants a refactor must not silently break; each one
 * corresponds to a defect that actually shipped.
 */

test.describe("scene characterisation", () => {
  test.skip(!process.env.E2E_BASE_URL, "These scenarios require the live Docker application.");

  test("cargo is never drawn twice, orphaned between owners, or left without a material", async ({ page, request }) => {
    test.setTimeout(300_000);
    await seedFromQuiet(request, "balanced-shift", 4);
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();
    await installCargoProbe(page);

    // Let the seeded scenario supply the work rather than queueing behind it. An earlier
    // version requested an outbound for one specific pallet and then waited for that pallet;
    // the fleet worked through four others first and the wait expired. The invariants here
    // are about any cargo, so what matters is that enough handovers were observed -- not
    // which pallet they involved.
    // Cross-check the probe against the API rather than polling the probe alone. A bare
    // "the fleet never carried anything" cannot tell a fleet that never moved -- a drained
    // battery, a stalled dispatch, contention with a previous spec -- from a probe that is
    // not reading the scene. Tracking both makes the failure say which half went blind.
    const apiCarries = new Set<string>();
    await expect.poll(async () => {
      const snapshot = await (await request.get("/api/v1/warehouses/linz/snapshot")).json() as
        { agvs: { carriedLoadId?: string | null }[] };
      const carried = snapshot.agvs?.[0]?.carriedLoadId;
      if (carried) apiCarries.add(carried);
      return Math.min(apiCarries.size, (await readCargoProbe(page)).carriedIds.length);
    }, { timeout: 240_000, intervals: [500], message: "no handover was observed" })
      .toBeGreaterThanOrEqual(2);
    await page.waitForTimeout(20_000);

    const probe = await readCargoProbe(page);
    await resetToQuiet(request);

    // Non-vacuity first: empty findings mean nothing if the probe saw no cargo.
    expect(probe.peakCargoNodes, "probe never saw any cargo -- it is not reading the scene").toBeGreaterThan(0);
    expect(probe.carriedIds.length,
      `probe saw ${probe.carriedIds.length} carries while the API reported ${apiCarries.size} ` +
      `(${[...apiCarries].join(", ")}) -- if the API moved pallets the probe did not see, the probe is blind`)
      .toBeGreaterThanOrEqual(2);

    expect(probe.duplicateNodes, "one pallet drawn twice").toEqual([]);
    expect(probe.doubleOwned, "a load owned by a slot and the fork at once").toEqual([]);
    expect(probe.untexturedMeshes, "cargo left with no material renders default white").toEqual([]);
  });

  // Held back deliberately rather than shipped flaky. The invariant is right and the setup
  // now works -- it settles the seeded scenario, drives a level-2 pick and measures a genuine
  // 2.47 m travel -- but end to end it takes ~4 minutes across a 240 s settle and a 150 s
  // poll, and it passed three of four trio runs rather than four. A characterisation suite
  // that cries wolf is worse than none: the whole reason for building this was that a
  // refactor gated on an unreliable suite is not gated. Needs a cheaper, more deterministic
  // route to a top-shelf lift before it earns a place in the gate.
  test.fixme("the mast moves rather than jumps", async ({ page, request }) => {
    test.setTimeout(420_000);
    // balanced-shift for its stocked racks, and because its two orders settle quickly.
    await seedFromQuiet(request, "balanced-shift", 3);
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();
    await installForkRecorder(page);

    // Let the scenario's own orders finish before driving the lift under test. Waiting for
    // the fleet to happen to make a tall lift does not work: outbound-wave only ever reached
    // 0.96 m, which is the 0.84 m handoff drop plus the 0.12 m lift, because nothing it
    // shipped came off a top shelf. The pick has to be chosen, not hoped for.
    await waitForQuiet(request, 240_000);

    // A level-2 slot has a handling height of 2.35 m, so the mast makes a long, unambiguous
    // travel. Level 0 and 1 are 0.15 m and 1.25 m -- too short to tell interpolation from
    // stepping, which is why two earlier versions of this test failed on 0.27 m and 0.96 m.
    const topShelfLoad = await (async () => {
      const snapshot = await (await request.get("/api/v1/warehouses/linz/snapshot")).json() as {
        loads: { id: string; status: string; locationId?: string }[];
        locations: { id: string; levelIndex?: number | null }[];
      };
      const top = new Set(snapshot.locations.filter((l) => l.levelIndex === 2).map((l) => l.id));
      const load = snapshot.loads.find((l) => l.status === "STORED" && l.locationId && top.has(l.locationId));
      expect(load, "scenario produced no load on a top-level slot to lift from").toBeTruthy();
      return (load as { id: string }).id;
    })();
    const outbound = await request.post("/api/v1/warehouses/linz/outbound-requests",
      { data: { loadIds: [topShelfLoad] } });
    expect(outbound.ok(), `outbound for ${topShelfLoad} failed: HTTP ${outbound.status()}`).toBeTruthy();

    await expect.poll(async () => forkTravel(await readForkFrames(page)).peak,
      { timeout: 150_000, intervals: [1000], message: `the mast never lifted ${topShelfLoad} off the top shelf` })
      .toBeGreaterThan(1.5);

    const { peak, largestJump } = forkTravel(await readForkFrames(page));
    await resetToQuiet(request);

    // A teleporting mast covers the whole lift in one frame. This bounds the largest single
    // frame-to-frame movement well below the travel, so arriving rather than travelling
    // fails while any real motion passes. It does not claim to prove per-frame interpolation
    // -- see forkTravel for why that is not measurable at this frame rate.
    expect(largestJump,
      `the mast moved ${largestJump.toFixed(2)}m in a single frame on a ${peak.toFixed(2)}m lift -- ` +
      "that is arriving, not travelling")
      .toBeLessThan(0.5);
  });

  test("the building encloses its cargo", async ({ page, request }) => {
    test.setTimeout(180_000);
    await resetToQuiet(request);
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();
    await page.waitForTimeout(3_000);

    // The north elevation had an 8 m hole no belt passed through: from inside the shipping
    // hall you could see straight out of the building.
    const north = (await meshBounds(page, "^WALL-N")).sort((a, b) => a.x[0] - b.x[0]);
    expect(north.length, "north wall segments missing from the scene").toBeGreaterThan(1);
    for (let index = 1; index < north.length; index += 1)
      expect(north[index].x[0],
        `gap in the north wall between ${north[index - 1].name} and ${north[index].name}`)
        .toBeLessThanOrEqual(north[index - 1].x[1] + 0.01);

    // Cargo rides the conveyor at 1.48 m; the door header sat at 1.10 m, so every carton
    // passed bodily through the steel.
    const header = (await meshBounds(page, "shippingHeader"))[0];
    expect(header, "shipping door header missing from the scene").toBeTruthy();
    expect(header.y[0], "the door header is lower than the cargo that passes under it")
      .toBeGreaterThanOrEqual(1.48);
  });
});
