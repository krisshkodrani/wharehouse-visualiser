import { buildOrderInspection, filterInspectionActivity } from "../../model/orderInspection";
import type { ApiTransportOrder, ApiVdaDispatch, ApiTransportTask } from "../../model/types";

const task = (overrides: Partial<ApiTransportTask> = {}): ApiTransportTask => ({
  id: "task-1", transportOrderId: "order-1", sequence: 1, loadId: "LOAD-01", source: "S-A1", destination: "OUTBOUND-01",
  status: "EXECUTING", route: ["S-A1", "W-A", "OUTBOUND"], assignedAgvId: "FL-01", startedAt: "2026-08-15T10:01:00Z", ...overrides
});

const payload = (update: number, released: number): string => JSON.stringify({
  headerId: update + 1, timestamp: "2026-08-15T10:01:00Z", version: "3.0.0", manufacturer: "demo", serialNumber: "FL-01",
  orderId: "task-1", orderUpdateId: update,
  nodes: [
    { nodeId: "A", sequenceId: 0, released: true, nodePosition: { x: 0, y: 0, mapId: "linz" }, actions: [] },
    { nodeId: "B", sequenceId: 2, released: released > 1, nodePosition: { x: 1, y: 0, mapId: "linz" }, actions: [{ actionType: "drop", actionParameters: [{ key: "loadId", value: "LOAD-01" }] }] }
  ],
  edges: [{ edgeId: "A-B", sequenceId: 1, released: released > 1, actions: [], maximumSpeed: 2.5 }]
});

const dispatch = (id: string, update: number, released: number, overrides: Partial<ApiVdaDispatch> = {}): ApiVdaDispatch => ({
  id, taskId: "task-1", manufacturer: "demo", serialNumber: "FL-01", orderId: "task-1", orderUpdateId: update,
  status: "ACCEPTED", valid: true, createdAt: `2026-08-15T10:0${update}:00Z`, publishedAt: `2026-08-15T10:0${update}:00Z`,
  payload: payload(update, released), ...overrides
});

const order = (overrides: Partial<ApiTransportOrder> = {}): ApiTransportOrder => ({
  id: "order-1", type: "OUTBOUND", priority: "URGENT", status: "IN_PROGRESS", objective: "Ship priority load",
  createdAt: "2026-08-15T10:00:00Z", tasks: [task()], vdaDispatches: [dispatch("update-1", 1, 2), dispatch("update-0", 0, 1)], ...overrides
});

QUnit.module("order inspection");

QUnit.test("groups updates by task and selects the latest update", (assert) => {
  const inspection = buildOrderInspection(order());
  assert.strictEqual(inspection.selectedTaskId, "task-1");
  assert.strictEqual(inspection.selectedDispatchId, "update-1");
  assert.strictEqual(inspection.tasks[0].updateCount, 2);
  assert.strictEqual(inspection.selectedDispatch?.sequence.length, 3);
  assert.strictEqual(inspection.selectedDispatch?.releasedNodeCount, 2);
});

QUnit.test("preserves an older manual selection when follow latest is disabled", (assert) => {
  const inspection = buildOrderInspection(order(), "task-1", "update-0", false);
  assert.strictEqual(inspection.selectedDispatchId, "update-0");
  assert.notOk(inspection.followLatest);
});

QUnit.test("describes meaningful release changes between updates", (assert) => {
  const inspection = buildOrderInspection(order());
  assert.deepEqual(inspection.selectedDispatch?.diffItems, ["Released nodes: B", "Released edges: A-B"]);
});

QUnit.test("builds filterable task and VDA activity", (assert) => {
  const inspection = buildOrderInspection(order());
  assert.ok(inspection.activity.some((entry) => entry.category === "TASK"));
  assert.strictEqual(filterInspectionActivity(inspection.activity, "VDA").length, 2);
  assert.ok(inspection.activity.every((entry, index, values) => index === 0 || values[index - 1].timestamp >= entry.timestamp));
});

QUnit.test("keeps malformed payloads inspectable", (assert) => {
  const malformed = dispatch("bad", 2, 2, { valid: false, payload: "{bad json", validationError: "Schema validation failed" });
  const inspection = buildOrderInspection(order({ vdaDispatches: [malformed] }));
  assert.ok(inspection.selectedDispatch?.parseError);
  assert.strictEqual(inspection.selectedDispatch?.rawJson, "{bad json");
  assert.strictEqual(inspection.invalidUpdateCount, 1);
});
