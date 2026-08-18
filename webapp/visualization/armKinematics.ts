/** Two-link inverse and forward kinematics for the ROBOT-01 picking arm.
 *
 * Kept separate from the scene so it can be unit tested without a WebGL context.
 * The arm previously used hard-coded joint angles with the rotations applied to
 * the link meshes; because Babylon rotates a box about its centroid, every link
 * slid off its joint and the arm rendered as three disconnected pieces with a
 * gripper hovering 3.1 m above empty floor. Solving the pose instead means the
 * gripper provably lands on its target, and {@link forwardKinematics} lets tests
 * assert exactly that.
 *
 * Frame: station-local, x/z on the floor, y up. Angles follow Babylon's
 * convention, where a joint's rotation.z of theta maps its local +Y axis to
 * (-sin theta, cos theta).
 */

export interface ArmGeometry {
  upperLength: number;
  forearmLength: number;
  gripperLength: number;
  shoulderHeight: number;
  pedestalLocalX: number;
}

export interface ArmPose {
  yaw: number;
  shoulder: number;
  elbow: number;
  wrist: number;
}

export interface ArmTarget { x: number; y: number; z: number; }

const clampUnit = (value: number): number => Math.max(-1, Math.min(1, value));

/** Straight-line distance the gripper tip must cover from the shoulder. Compare
 * against {@link maximumReach} to detect a target the arm cannot actually make. */
export function requiredReach(geometry: ArmGeometry, target: ArmTarget): number {
  const radial = Math.hypot(target.x - geometry.pedestalLocalX, target.z);
  const rise = target.y + geometry.gripperLength - geometry.shoulderHeight;
  return Math.hypot(radial, rise);
}

export function maximumReach(geometry: ArmGeometry): number {
  return geometry.upperLength + geometry.forearmLength;
}

/** Solves for the joint angles that put the gripper tip on `target` with the
 * gripper pointing straight down, so it approaches pallets and belts from above.
 * Out-of-reach targets are clamped to the arm's envelope rather than producing
 * NaN, so the arm stops short instead of disappearing. */
export function solveArmPose(geometry: ArmGeometry, target: ArmTarget): ArmPose {
  const { upperLength: l1, forearmLength: l2, gripperLength, shoulderHeight, pedestalLocalX } = geometry;
  const deltaX = target.x - pedestalLocalX;
  const yaw = Math.atan2(-target.z, deltaX);
  const radial = Math.hypot(deltaX, target.z);
  const rise = target.y + gripperLength - shoulderHeight;
  const span = Math.min(
    Math.max(Math.hypot(radial, rise), Math.abs(l1 - l2) + 1e-3),
    l1 + l2 - 1e-3);
  // Interior angle at the elbow, then the elbow-up branch of the two-link solution.
  const interior = Math.acos(clampUnit((l1 * l1 + l2 * l2 - span * span) / (2 * l1 * l2)));
  const elevation = Math.atan2(rise, radial);
  const offset = Math.acos(clampUnit((span * span + l1 * l1 - l2 * l2) / (2 * span * l1)));
  const shoulder = elevation + offset - Math.PI / 2;
  const elbow = interior - Math.PI;
  return { yaw, shoulder, elbow, wrist: -Math.PI - (shoulder + elbow) };
}

/** Where the gripper tip and each joint actually end up for a pose. Mirrors the
 * transform hierarchy the scene builds: rotation on the joint nodes only, each
 * link offset by half its length along its parent joint's local +Y. */
export function forwardKinematics(geometry: ArmGeometry, pose: ArmPose): {
  tip: ArmTarget; shoulder: ArmTarget; elbow: ArmTarget; wrist: ArmTarget;
} {
  const { upperLength: l1, forearmLength: l2, gripperLength, shoulderHeight, pedestalLocalX } = geometry;
  const toWorld = (radial: number, height: number): ArmTarget => ({
    x: pedestalLocalX + radial * Math.cos(pose.yaw),
    y: height,
    z: -radial * Math.sin(pose.yaw)
  });
  let radial = 0;
  let height = shoulderHeight;
  const shoulder = toWorld(radial, height);
  radial += -Math.sin(pose.shoulder) * l1;
  height += Math.cos(pose.shoulder) * l1;
  const elbow = toWorld(radial, height);
  const absoluteElbow = pose.shoulder + pose.elbow;
  radial += -Math.sin(absoluteElbow) * l2;
  height += Math.cos(absoluteElbow) * l2;
  const wrist = toWorld(radial, height);
  const absoluteWrist = absoluteElbow + pose.wrist;
  radial += -Math.sin(absoluteWrist) * gripperLength;
  height += Math.cos(absoluteWrist) * gripperLength;
  return { tip: toWorld(radial, height), shoulder, elbow, wrist };
}
