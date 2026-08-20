import Babylon from "../../vendor/BabylonRuntime";
import type { Scene as SceneType } from "@babylonjs/core/scene";
import type { TransformNode as TransformNodeType } from "@babylonjs/core/Meshes/transformNode";
import type { ArmPose } from "../armKinematics";

const { Animation, CubicEase, EasingFunction } = Babylon;

export interface RobotArmJoints {
  yaw: TransformNodeType;
  shoulder: TransformNodeType;
  elbow: TransformNodeType;
  wrist: TransformNodeType;
}

/** Applies solved arm poses; geometry and inverse kinematics remain separate. */
export default class RobotArmAnimator {
  public constructor(private readonly scene: SceneType, private readonly joints: RobotArmJoints) {}

  public apply(pose: ArmPose, frames: number): void {
    const targets: Array<[TransformNodeType, "rotation.y" | "rotation.z", number]> = [
      [this.joints.yaw, "rotation.y", pose.yaw],
      [this.joints.shoulder, "rotation.z", pose.shoulder],
      [this.joints.elbow, "rotation.z", pose.elbow],
      [this.joints.wrist, "rotation.z", pose.wrist]
    ];
    for (const [node, property, value] of targets) {
      this.scene.stopAnimation(node);
      if (frames <= 0) {
        if (property === "rotation.y") node.rotation.y = value;
        else node.rotation.z = value;
        continue;
      }
      const current = property === "rotation.y" ? node.rotation.y : node.rotation.z;
      const animation = new Animation(`armJoint-${node.name}`, property, 60,
          Animation.ANIMATIONTYPE_FLOAT, Animation.ANIMATIONLOOPMODE_CONSTANT);
      const delta = Math.atan2(Math.sin(value - current), Math.cos(value - current));
      animation.setKeys([{ frame: 0, value: current }, { frame: frames, value: current + delta }]);
      const easing = new CubicEase();
      easing.setEasingMode(EasingFunction.EASINGMODE_EASEINOUT);
      animation.setEasingFunction(easing);
      this.scene.beginDirectAnimation(node, [animation], 0, frames, false);
    }
  }
}
