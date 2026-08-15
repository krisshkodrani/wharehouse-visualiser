import { mergeAgvEvent } from "../../model/agvState";
import type { ApiAgv } from "../../model/types";

const current: ApiAgv = {
  id: "FL-01", x: 6.4, z: 2.8, theta: 1.2, velocity: 1.8, battery: 72,
  status: "MOVING", charging: false, handlingPhase: "IDLE", forkHeight: 0, forkExtension: 0
};

QUnit.module("AGV event state");

QUnit.test("operational events cannot rewind the rendered pose", (assert) => {
  const delayed = { ...current, x: 5.7, z: 2.1, theta: .8, velocity: 1.1, battery: 69, forkHeight: 1.4 };
  const merged = mergeAgvEvent(current, delayed, false);

  assert.deepEqual(
    { x: merged.x, z: merged.z, theta: merged.theta, velocity: merged.velocity },
    { x: current.x, z: current.z, theta: current.theta, velocity: current.velocity },
    "the last visualization pose is retained"
  );
  assert.strictEqual(merged.battery, 69, "battery state still updates");
  assert.strictEqual(merged.forkHeight, 1.4, "handling state still updates");
});

QUnit.test("visualization events replace the complete pose", (assert) => {
  const live = { ...current, x: 6.8, z: 3.2, theta: 1.4, velocity: 2.0 };
  assert.strictEqual(mergeAgvEvent(current, live, true), live, "the live telemetry object is accepted unchanged");
});

QUnit.test("the first operational event can initialize an absent pose", (assert) => {
  assert.strictEqual(mergeAgvEvent(undefined, current, false), current, "startup state is not discarded");
});
