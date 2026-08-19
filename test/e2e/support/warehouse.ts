import { expect, type APIRequestContext } from "@playwright/test";

/**
 * Shared setup for the specs that drive the live stack.
 *
 * <p>Every spec here shares one forklift, one backend and one database, and Playwright runs
 * them serially for exactly that reason. What was missing is a barrier: `operations/reset`
 * bumps the simulation epoch and publishes a RESET to the broker, then returns immediately.
 * The simulator acts on that asynchronously, so a spec that reset and queued work in the
 * next breath was racing the previous spec's vehicle. That is the shared-state flakiness
 * behind specs which fail in a full run and pass in isolation and in pairs.
 *
 * <p>The barrier is defensive at the start rather than polite at the end, because a spec
 * that fails never reaches its own cleanup -- and a failure is precisely when the next spec
 * most needs a clean warehouse.
 */

interface QuietSnapshot {
  runtime?: { simulationEpoch?: number; operationState?: string };
  agvs?: { id: string; status?: string; taskId?: string | null; carriedLoadId?: string | null }[];
  jobs?: { status?: string }[];
}

/** Statuses that mean the vehicle is still working, or still being told to stop. */
const BUSY_VEHICLE = ["MOVING", "DISPATCHED", "PARKING", "DOCKING", "PAUSED"];
/** Task states that are not yet finished, so their vehicle is still spoken for. */
const LIVE_TASK = ["QUEUED", "DISPATCHED", "ACCEPTED", "EXECUTING"];

const snapshot = async (request: APIRequestContext): Promise<QuietSnapshot> =>
  (await request.get("/api/v1/warehouses/linz/snapshot")).json() as Promise<QuietSnapshot>;

/** Why the warehouse is not quiet yet, or undefined once it is. */
async function unsettled(request: APIRequestContext): Promise<string | undefined> {
  const state = await snapshot(request);
  const vehicle = state.agvs?.[0];
  if (!vehicle) return "no vehicle in the snapshot";
  if (vehicle.status && BUSY_VEHICLE.includes(vehicle.status)) return `vehicle is ${vehicle.status}`;
  if (vehicle.taskId) return `vehicle still holds task ${vehicle.taskId}`;
  if (vehicle.carriedLoadId) return `vehicle still carries ${vehicle.carriedLoadId}`;
  const live = (state.jobs ?? []).filter((job) => job.status && LIVE_TASK.includes(job.status));
  if (live.length) return `${live.length} task(s) still in flight`;
  return undefined;
}

/**
 * Reset the warehouse and wait until it is genuinely idle before handing back.
 *
 * <p>Requires two consecutive quiet samples: a single one can catch the gap between the
 * backend clearing a task and the simulator reporting what it is doing next, which looks
 * idle without being idle.
 */
export async function resetToQuiet(request: APIRequestContext, speedMultiplier = 2): Promise<void> {
  await request.post("/api/v1/warehouses/linz/operations/reset", { data: {} });

  let reason: string | undefined = "not sampled yet";
  let consecutiveQuiet = 0;
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    reason = await unsettled(request);
    consecutiveQuiet = reason === undefined ? consecutiveQuiet + 1 : 0;
    if (consecutiveQuiet >= 2) break;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  expect(consecutiveQuiet, `warehouse never settled after reset: ${reason}`).toBeGreaterThanOrEqual(2);

  await request.post("/api/v1/warehouses/linz/operations/speed", { data: { multiplier: speedMultiplier } });
}

/**
 * Seed a demo scenario from a quiet warehouse.
 *
 * <p>Seeding on top of a running scenario leaves the previous one's orders in flight, which
 * is how one spec's work ends up competing for the vehicle another spec is waiting on.
 */
export async function seedFromQuiet(
  request: APIRequestContext, presetId: string, speedMultiplier = 2): Promise<void> {
  await resetToQuiet(request, speedMultiplier);
  const seeded = await request.post("/api/v1/warehouses/linz/scenario", { data: { presetId } });
  expect(seeded.ok(), `seeding ${presetId} failed: HTTP ${seeded.status()}`).toBeTruthy();
  await request.post("/api/v1/warehouses/linz/operations/speed", { data: { multiplier: speedMultiplier } });
}
