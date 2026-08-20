import type { Scene, TransformNode as TransformNodeType } from "@babylonjs/core";

import type { StationDefinition } from "../../model/types";
import Babylon from "../../vendor/BabylonRuntime";
import type MaterialFactory from "../factories/MaterialFactory";

const { MeshBuilder, TransformNode } = Babylon;

export interface RobotArmDimensions {
  pedestalX: number;
  shoulderHeight: number;
  upperLength: number;
  forearmLength: number;
  gripperLength: number;
  handoffX: number;
}

/** Owns the guarded robot cell and its invariant pin-joint hierarchy. */
export default class RobotCellVisual {
  private constructor(
    readonly root: TransformNodeType,
    readonly yaw: TransformNodeType,
    readonly shoulder: TransformNodeType,
    readonly elbow: TransformNodeType,
    readonly wrist: TransformNodeType
  ) {}

  static create(
    scene: Scene,
    parent: TransformNodeType | null,
    materials: MaterialFactory,
    station: StationDefinition,
    dimensions: RobotArmDimensions
  ): RobotCellVisual {
    const root = new TransformNode("robotCell-ROBOT-01", scene);
    root.position.set(station.position[0], 0, station.position[2]);
    root.rotation.y = station.rotationY;
    root.parent = parent;
    const baseMaterial = materials.metal("robotCellBase", "#4b5961", 84);
    const armMaterial = materials.create("robotCellArm", "#f3a712");
    const jointMaterial = materials.metal("robotCellJoint", "#37424a", 96);
    const safetyMaterial = materials.create("robotCellSafety", "#ef7d19");

    const pedestal = new TransformNode("robotCellPedestal", scene);
    pedestal.position.set(dimensions.pedestalX, 0, 0);
    pedestal.parent = root;
    const base = MeshBuilder.CreateCylinder(
      "robotCellBase", { diameter: 1.2, height: .28, tessellation: 32 }, scene);
    base.position.y = .14;
    base.material = baseMaterial;
    base.parent = pedestal;
    const column = MeshBuilder.CreateCylinder(
      "robotCellColumn",
      { diameter: .56, height: dimensions.shoulderHeight - .28, tessellation: 24 },
      scene
    );
    column.position.y = .28 + (dimensions.shoulderHeight - .28) / 2;
    column.material = baseMaterial;
    column.parent = pedestal;

    const yaw = new TransformNode("robotCellYaw", scene);
    yaw.parent = pedestal;
    const shoulder = new TransformNode("robotCellShoulder", scene);
    shoulder.position.y = dimensions.shoulderHeight;
    shoulder.parent = yaw;
    const shoulderJoint = MeshBuilder.CreateSphere(
      "robotArmShoulderJoint", { diameter: .46, segments: 16 }, scene);
    shoulderJoint.material = jointMaterial;
    shoulderJoint.parent = shoulder;
    const upper = MeshBuilder.CreateBox(
      "robotArmUpper",
      { width: .34, height: dimensions.upperLength, depth: .3 },
      scene
    );
    upper.position.y = dimensions.upperLength / 2;
    upper.material = armMaterial;
    upper.parent = shoulder;

    const elbow = new TransformNode("robotCellElbow", scene);
    elbow.position.y = dimensions.upperLength;
    elbow.parent = shoulder;
    const elbowJoint = MeshBuilder.CreateSphere(
      "robotArmElbowJoint", { diameter: .38, segments: 16 }, scene);
    elbowJoint.material = jointMaterial;
    elbowJoint.parent = elbow;
    const forearm = MeshBuilder.CreateBox(
      "robotArmForearm",
      { width: .28, height: dimensions.forearmLength, depth: .26 },
      scene
    );
    forearm.position.y = dimensions.forearmLength / 2;
    forearm.material = armMaterial;
    forearm.parent = elbow;

    const wrist = new TransformNode("robotCellWrist", scene);
    wrist.position.y = dimensions.forearmLength;
    wrist.parent = elbow;
    const wristJoint = MeshBuilder.CreateCylinder(
      "robotArmWrist", { diameter: .28, height: .3, tessellation: 20 }, scene);
    wristJoint.rotation.z = Math.PI / 2;
    wristJoint.material = jointMaterial;
    wristJoint.parent = wrist;
    const gripper = MeshBuilder.CreateBox(
      "robotGripper", { width: .34, height: .18, depth: .28 }, scene);
    gripper.position.y = dimensions.gripperLength - .1;
    gripper.material = safetyMaterial;
    gripper.parent = wrist;
    for (const x of [-.15, .15]) {
      const finger = MeshBuilder.CreateBox(
        "robotGripperFinger", { width: .05, height: .2, depth: .22 }, scene);
      finger.position.set(x, dimensions.gripperLength + .06, 0);
      finger.material = safetyMaterial;
      finger.parent = wrist;
    }

    const halfWidth = station.width / 2;
    const halfDepth = station.depth / 2;
    for (const x of [-halfWidth, halfWidth]) {
      for (const z of [-halfDepth, halfDepth]) {
        const post = MeshBuilder.CreateCylinder(
          "robotCellPost", { diameter: .12, height: 1.6, tessellation: 12 }, scene);
        post.position.set(x, .8, z);
        post.material = safetyMaterial;
        post.parent = root;
      }
    }
    const handoff = MeshBuilder.CreateBox(
      "robotHandoffPad", { width: 2.2, height: .025, depth: 2.1 }, scene);
    handoff.position.set(dimensions.handoffX, .02, 0);
    handoff.material = safetyMaterial;
    handoff.parent = root;
    return new RobotCellVisual(root, yaw, shoulder, elbow, wrist);
  }
}
