import { expect, test, type APIRequestContext } from "@playwright/test";
import { resetToQuiet } from "./support/warehouse";

/**
 * Cancelling an order mid-carry must give the pallet back.
 *
 * The vehicle used to keep it. `completeCancellation` cleared `carried_load_id` on the AGV
 * row, but the simulator never released its own `carriedLoadId`, and handling telemetry is
 * latest-value-wins -- so `updateHandling` wrote the load straight back onto the vehicle a
 * few hundred milliseconds later. The end state was an AGV parked at the charger holding a
 * pallet with no task, and a load stranded in `IN_TRANSIT`: not `INBOUND`, so it could not
 * be put away again, and not `STORED`, so it could never ship. The pallet was unrecoverable
 * without a scenario reset.
 *
 * The last assertion is the one that matters. Checking the status fields only proves they
 * look right; putting the same pallet away again proves the inventory is actually intact.
 */

interface LoadState { id: string; status: string }
interface AgvState { id: string; carriedLoadId?: string | null; taskId?: string | null }
interface Snapshot { loads: LoadState[]; agvs: AgvState[] }

const snapshot = async (request: APIRequestContext): Promise<Snapshot> =>
  (await request.get("/api/v1/warehouses/linz/snapshot")).json() as Promise<Snapshot>;

const loadStatus = async (request: APIRequestContext, loadId: string) =>
  (await snapshot(request)).loads.find((load) => load.id === loadId)?.status;

const carriedBy = async (request: APIRequestContext) =>
  (await snapshot(request)).agvs.find((agv) => agv.id === "FL-01")?.carriedLoadId ?? null;

async function putaway(request: APIRequestContext, loadId: string) {
  const response = await request.post("/api/v1/warehouses/linz/putaway-requests", {
    data: { inboundLoadIds: [loadId], operatorPrompt: "Store this pallet anywhere eligible." }
  });
  // Carry the body into the failure. A bare ok() check reports only "expected true", which
  // says nothing about whether the load was unknown, staging was full, or planning refused.
  const body = await response.text();
  expect(response.ok(), `putaway for ${loadId} failed: HTTP ${response.status()} ${body}`).toBeTruthy();
  return (JSON.parse(body) as { requestId: string }).requestId;
}

test("a pallet is returned when its order is cancelled mid-carry", async ({ request }) => {
  test.setTimeout(300_000);
  test.skip(!process.env.E2E_BASE_URL, "This scenario requires the live Docker application.");

  await resetToQuiet(request, 3);

  const received = await request.post("/api/v1/warehouses/linz/inbound-loads", {
    data: { sku: "E2E-CANCEL-RELEASE", quantity: 1 }
  });
  expect(received.ok()).toBeTruthy();
  const loadId = ((await received.json()) as { loads: LoadState[] }).loads[0]?.id as string;
  expect(loadId).toBeTruthy();

  const orderId = await putaway(request, loadId);

  // Cancel only once the fork is physically holding it. Cancelling a queued task takes a
  // different path and would not exercise the bug at all.
  await expect.poll(() => carriedBy(request),
    { timeout: 120_000, intervals: [500], message: "the fork never picked the pallet up" })
    .toBe(loadId);

  await request.post(`/api/v1/warehouses/linz/transport-orders/${orderId}/cancel`, { data: {} });

  // The vehicle must let go and stay let go. A single sample would pass even with the bug
  // present, because the backend clears the row before the simulator overwrites it.
  await expect.poll(() => carriedBy(request),
    { timeout: 30_000, intervals: [500], message: "the vehicle never released the cancelled pallet" })
    .toBeNull();
  await new Promise((resolve) => setTimeout(resolve, 5_000));
  expect(await carriedBy(request), "the vehicle re-acquired the pallet after releasing it").toBeNull();

  await expect.poll(() => loadStatus(request, loadId),
    { timeout: 30_000, intervals: [500], message: "a cancelled pallet must go back to its source" })
    .toBe("INBOUND");

  // The real test: it is only genuinely back if it can be put away again.
  await putaway(request, loadId);
  await expect.poll(() => loadStatus(request, loadId),
    { timeout: 150_000, intervals: [1_000], message: "the pallet was not recoverable after cancellation" })
    .toBe("STORED");
});
