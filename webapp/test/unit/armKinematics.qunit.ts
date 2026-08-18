import { forwardKinematics, maximumReach, requiredReach, solveArmPose } from "../../visualization/armKinematics";
import type { ArmGeometry, ArmTarget } from "../../visualization/armKinematics";

// Must stay in step with the ARM_* constants in WarehouseScene.ts.
const GEOMETRY: ArmGeometry = {
  upperLength: 1.55,
  forearmLength: 1.35,
  gripperLength: .3,
  shoulderHeight: .95,
  pedestalLocalX: -2.0
};

// V21 geometry: ROBOT-01 at world (-4.1, 14.4), handoff pad at cell-local +0.5
// (world -3.6), conveyor lanes centred at world x=-12.8, 9.6 m long and rotated by
// pi so they flow west. Their infeed is therefore CONVEYOR_INFEED_INSET (0.7 m) in
// from the eastern end: -12.8 + 9.6/2 - 0.7 = -8.7.
const CELL_ORIGIN = { x: -4.1, z: 14.4 };
const local = (worldX: number, y: number, worldZ: number): ArmTarget =>
  ({ x: worldX - CELL_ORIGIN.x, y, z: worldZ - CELL_ORIGIN.z });

const TARGETS: Record<string, ArmTarget> = {
  "approach above the handoff pallet": local(-3.6, 1.35, 14.4),
  "grip on the handoff pallet": local(-3.6, .95, 14.4),
  "place on conveyor lane 1 infeed": local(-8.7, 1.1, 13.4),
  "place on conveyor lane 2 infeed": local(-8.7, 1.1, 15.4)
};

QUnit.module("armKinematics");

QUnit.test("solved poses put the gripper tip exactly on every cell target", (assert) => {
  for (const [name, target] of Object.entries(TARGETS)) {
    const { tip } = forwardKinematics(GEOMETRY, solveArmPose(GEOMETRY, target));
    const error = Math.hypot(tip.x - target.x, tip.y - target.y, tip.z - target.z);
    assert.ok(error < 1e-9, `${name}: tip error ${error.toExponential(2)} m`);
  }
});

QUnit.test("every cell target is inside the arm's reach envelope", (assert) => {
  // Regression guard: the placing target was originally aimed at each lane's
  // centre, 7 m from the pedestal, so the IK clamped and the gripper released into
  // thin air well short of the belt. The lanes are 9.6 m long — only their infeed
  // end is reachable.
  for (const [name, target] of Object.entries(TARGETS)) {
    const reach = requiredReach(GEOMETRY, target);
    assert.ok(reach < maximumReach(GEOMETRY),
      `${name}: needs ${reach.toFixed(2)} m of ${maximumReach(GEOMETRY).toFixed(2)} m`);
  }
});

QUnit.test("aiming at a lane centre instead of its infeed is out of reach", (assert) => {
  const laneCentre = local(-12.8, 1.1, 13.4);
  assert.ok(requiredReach(GEOMETRY, laneCentre) > maximumReach(GEOMETRY),
    `lane centre needs ${requiredReach(GEOMETRY, laneCentre).toFixed(2)} m, beyond the envelope`);
});

QUnit.test("links stay attached to their joints", (assert) => {
  // The original bug: link positions were authored for pin joints but the
  // rotations were applied to the meshes, so each link slid off its joint by half
  // its length times the sine of its angle. Exact link lengths between successive
  // joints are what prove the chain is assembled.
  for (const [name, target] of Object.entries(TARGETS)) {
    const joints = forwardKinematics(GEOMETRY, solveArmPose(GEOMETRY, target));
    const upper = Math.hypot(joints.elbow.x - joints.shoulder.x,
      joints.elbow.y - joints.shoulder.y, joints.elbow.z - joints.shoulder.z);
    const forearm = Math.hypot(joints.wrist.x - joints.elbow.x,
      joints.wrist.y - joints.elbow.y, joints.wrist.z - joints.elbow.z);
    assert.ok(Math.abs(upper - GEOMETRY.upperLength) < 1e-9, `${name}: upper arm ${upper.toFixed(6)} m`);
    assert.ok(Math.abs(forearm - GEOMETRY.forearmLength) < 1e-9, `${name}: forearm ${forearm.toFixed(6)} m`);
  }
});

QUnit.test("the shoulder sits on the pedestal, not floating above it", (assert) => {
  const { shoulder } = forwardKinematics(GEOMETRY, solveArmPose(GEOMETRY, TARGETS["grip on the handoff pallet"]));
  assert.strictEqual(Number(shoulder.x.toFixed(6)), GEOMETRY.pedestalLocalX, "shoulder is on the pedestal axis");
  assert.strictEqual(Number(shoulder.y.toFixed(6)), GEOMETRY.shoulderHeight, "shoulder is at the column top");
});

QUnit.test("the gripper stays vertical so it approaches from above", (assert) => {
  for (const [name, target] of Object.entries(TARGETS)) {
    const pose = solveArmPose(GEOMETRY, target);
    const joints = forwardKinematics(GEOMETRY, pose);
    assert.ok(joints.wrist.y > joints.tip.y,
      `${name}: wrist (${joints.wrist.y.toFixed(2)}) is above the tip (${joints.tip.y.toFixed(2)})`);
    assert.ok(Math.abs(joints.wrist.y - joints.tip.y - GEOMETRY.gripperLength) < 1e-9,
      `${name}: gripper hangs its full length straight down`);
  }
});

QUnit.test("an unreachable target clamps to the envelope instead of producing NaN", (assert) => {
  const faraway: ArmTarget = { x: 40, y: 1, z: 0 };
  const pose = solveArmPose(GEOMETRY, faraway);
  assert.ok(requiredReach(GEOMETRY, faraway) > maximumReach(GEOMETRY), "target is out of reach");
  for (const [joint, value] of Object.entries(pose))
    assert.ok(Number.isFinite(value), `${joint} is finite (${value})`);
  const { tip } = forwardKinematics(GEOMETRY, pose);
  assert.ok(Number.isFinite(tip.x) && Number.isFinite(tip.y), "tip stays finite");
});

QUnit.test("lane 1 and lane 2 need opposite yaw from the handoff pad", (assert) => {
  const pad = solveArmPose(GEOMETRY, TARGETS["grip on the handoff pallet"]);
  const lane1 = solveArmPose(GEOMETRY, TARGETS["place on conveyor lane 1 infeed"]);
  const lane2 = solveArmPose(GEOMETRY, TARGETS["place on conveyor lane 2 infeed"]);
  assert.ok(Math.abs(lane1.yaw - pad.yaw) > 2, "the arm swings away from the pad to reach lane 1");
  assert.ok(Math.abs(lane2.yaw - pad.yaw) > 2, "the arm swings away from the pad to reach lane 2");
  assert.ok(lane1.yaw * lane2.yaw < 0, "the two lanes sit on opposite sides of the cell axis");
});
