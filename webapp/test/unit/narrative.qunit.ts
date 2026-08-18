import { buildNarrative, pipelineStage, activeTask, latestDispatch, PIPELINE_STAGES } from "../../model/narrative";
import type { ApiAgv, ApiTransportOrder, ApiTransportTask, ApiVdaDispatch } from "../../model/types";

const agv: ApiAgv = {
  id: "FL-01", x: 2, z: 4, theta: 0, velocity: 0, battery: 64,
  status: "IDLE", charging: false, handlingPhase: "IDLE", forkHeight: 0, forkExtension: 0
};

function task(overrides: Partial<ApiTransportTask> = {}): ApiTransportTask {
  return {
    id: "task-1", transportOrderId: "order-1", sequence: 1, loadId: "L-042",
    source: "INBOUND-01", destination: "OUTBOUND-01", status: "QUEUED", route: [], ...overrides
  };
}

function dispatch(overrides: Partial<ApiVdaDispatch> = {}): ApiVdaDispatch {
  return {
    id: "d1", taskId: "task-1", manufacturer: "demo", serialNumber: "FL-01",
    orderId: "task-1", orderUpdateId: 0, status: "PUBLISHED", valid: true,
    createdAt: "2026-08-17T10:00:00Z", payload: "{}", ...overrides
  };
}

function order(overrides: Partial<ApiTransportOrder> = {}): ApiTransportOrder {
  return {
    id: "abcdef12-3456-7890-abcd-ef1234567890", type: "OUTBOUND", priority: "NORMAL",
    status: "IN_PROGRESS", createdAt: "2026-08-17T10:00:00Z", tasks: [task()], vdaDispatches: [], ...overrides
  };
}

QUnit.module("Story narrative");

QUnit.test("every task status maps to exactly one pipeline stage", (assert) => {
  const cases: Array<[Partial<ApiTransportTask>, string]> = [
    [{ status: "QUEUED" }, "TASK"],
    [{ status: "READY" }, "TASK"],
    [{ status: "ASSIGNED", route: [] }, "TASK"],
    [{ status: "ASSIGNED", route: ["N1", "N2"] }, "ROUTE"],
    [{ status: "DISPATCHED" }, "VDA"],
    [{ status: "ACCEPTED" }, "AGV"],
    [{ status: "EXECUTING" }, "AGV"]
  ];
  for (const [overrides, expected] of cases) {
    const current = task(overrides);
    assert.strictEqual(pipelineStage(order({ tasks: [current] }), current), expected,
      `${overrides.status}${overrides.route?.length ? " with a route" : ""} is ${expected}`);
  }
});

QUnit.test("the strip marks earlier stages done and later stages pending", (assert) => {
  const current = task({ status: "DISPATCHED" });
  const narrative = buildNarrative(agv, order({ tasks: [current] }));

  assert.strictEqual(narrative.stage, "VDA", "dispatched work sits at the VDA stage");
  assert.deepEqual(narrative.steps.map((step) => step.state),
    ["DONE", "DONE", "DONE", "CURRENT", "PENDING", "PENDING"], "exactly one step is current");
  assert.strictEqual(narrative.steps.length, PIPELINE_STAGES.length, "the strip covers every stage");
});

QUnit.test("a completed order only reaches DONE once every task is complete", (assert) => {
  const partly = order({ tasks: [task({ status: "COMPLETED" }), task({ id: "task-2", status: "EXECUTING" })] });
  assert.strictEqual(buildNarrative(agv, partly).stage, "AGV", "remaining work keeps the order on the vehicle");

  const finished = order({ status: "COMPLETED", tasks: [task({ status: "COMPLETED" })] });
  assert.strictEqual(buildNarrative(agv, finished).stage, "DONE", "a completed order is done");
});

QUnit.test("carrying a load names the load and its destination", (assert) => {
  const moving = { ...agv, status: "MOVING", carriedLoadId: "L-042" };
  const narrative = buildNarrative(moving, order({ tasks: [task({ status: "EXECUTING" })] }));

  assert.ok(narrative.sentence.includes("L-042"), "the load is named");
  assert.ok(narrative.sentence.includes("OUTBOUND 1"), "the destination is readable");
  assert.notOk(narrative.exception, "ordinary progress is not an exception");
});

QUnit.test("driving without a load explains what it is going to collect", (assert) => {
  const narrative = buildNarrative({ ...agv, status: "MOVING" }, order({ tasks: [task({ status: "EXECUTING" })] }));
  assert.ok(narrative.sentence.includes("collect"), "the intent is stated");
  assert.ok(narrative.sentence.includes("INBOUND 1"), "the pickup point is named");
});

QUnit.test("handling phases read as actions, not enum names", (assert) => {
  const lowering = buildNarrative(
    { ...agv, status: "MOVING", handlingPhase: "LOWERING", carriedLoadId: "L-042" },
    order({ tasks: [task({ status: "EXECUTING" })] }));
  assert.ok(lowering.sentence.includes("setting down"), "LOWERING is phrased for a human");
  assert.notOk(lowering.sentence.includes("LOWERING"), "the raw phase is not shown");
  assert.ok(lowering.sentence.includes("OUTBOUND 1"), "setting down happens at the destination");

  const lifting = buildNarrative(
    { ...agv, status: "MOVING", handlingPhase: "LIFTING", carriedLoadId: "L-042" },
    order({ tasks: [task({ status: "EXECUTING" })] }));
  assert.ok(lifting.sentence.includes("INBOUND 1"), "lifting happens at the source");
});

QUnit.test("charging is reported with the battery level", (assert) => {
  const narrative = buildNarrative({ ...agv, charging: true, battery: 71.4, status: "CHARGING" }, null);
  assert.ok(narrative.sentence.includes("71%"), "the level is rounded and shown");
  assert.notOk(narrative.exception, "charging is normal operation");
});

QUnit.test("failures surface as exceptions with the reason", (assert) => {
  const failed = buildNarrative(agv, order({ status: "FAILED", error: "Route blocked at N04" }));
  assert.ok(failed.exception, "a failed order is an exception");
  assert.ok(failed.sentence.includes("Route blocked at N04"), "the reason is carried through");

  const faulted = buildNarrative({ ...agv, status: "FAULT" }, order());
  assert.ok(faulted.exception, "a faulted vehicle is an exception");
});

QUnit.test("with no order the narrative still tells the viewer what to do", (assert) => {
  const narrative = buildNarrative(agv, null);
  assert.strictEqual(narrative.stage, "ORDER", "the strip starts at the beginning");
  assert.ok(narrative.sentence.includes("Create a transport order"), "the next action is suggested");
  assert.ok(narrative.proofLine.includes("VDA 5050 v3.0.0"), "the protocol is still stated");
});

QUnit.test("with no telemetry the narrative says so rather than inventing state", (assert) => {
  assert.strictEqual(buildNarrative(null, null).sentence, "Waiting for vehicle telemetry.");
});

QUnit.test("the proof line follows the newest VDA update", (assert) => {
  const updated = order({ vdaDispatches: [dispatch({ orderUpdateId: 0 }), dispatch({ id: "d2", orderUpdateId: 3 })] });
  assert.strictEqual(latestDispatch(updated)?.orderUpdateId, 3, "the highest orderUpdateId wins");
  assert.ok(buildNarrative(agv, updated).proofLine.includes("update 3"), "the strip shows the newest update");
  assert.ok(buildNarrative(agv, updated).proofLine.includes("schema valid"), "validity is stated");
});

QUnit.test("a vehicle rejection is visible in the proof line", (assert) => {
  const rejected = order({ vdaDispatches: [dispatch({ rejectionError: "unsupported action" })] });
  assert.ok(buildNarrative(agv, rejected).proofLine.includes("rejected by vehicle"));
});

QUnit.test("the active task prefers live work over finished work", (assert) => {
  const mixed = order({
    tasks: [task({ id: "done", status: "COMPLETED" }), task({ id: "live", status: "EXECUTING" })]
  });
  assert.strictEqual(activeTask(mixed)?.id, "live", "the executing task is described");
});
