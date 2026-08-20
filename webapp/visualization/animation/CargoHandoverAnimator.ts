import Babylon from "../../vendor/BabylonRuntime";
import type { Scene as SceneType } from "@babylonjs/core/scene";
import type { TransformNode as TransformNodeType } from "@babylonjs/core/Meshes/transformNode";
import type { Vector3 as Vector3Type } from "@babylonjs/core/Maths/math.vector";

const { Animation, CubicEase, EasingFunction, Vector3 } = Babylon;

/** Visual-only cargo entry, placement, and exit transitions. */
export default class CargoHandoverAnimator {
  public constructor(private readonly scene: SceneType) {}

  public place(id: string, root: TransformNodeType, target: Vector3Type, targetRotationY: number,
      placementFrames: number): void {
    const easing = new CubicEase();
    easing.setEasingMode(EasingFunction.EASINGMODE_EASEOUT);
    const move = new Animation(`cargoPlace-${id}`, "position", 60,
        Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
    move.setKeys([{ frame: 0, value: root.position.clone() }, { frame: placementFrames, value: target.clone() }]);
    move.setEasingFunction(easing);
    const settle = new Animation(`cargoSettle-${id}`, "scaling", 60,
        Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
    settle.setKeys([
      { frame: 0, value: new Vector3(1, 1, 1) },
      { frame: placementFrames, value: new Vector3(1.04, .94, 1.04) },
      { frame: placementFrames + 7, value: new Vector3(1, 1, 1) }
    ]);
    settle.setEasingFunction(easing);
    root.rotation.y = targetRotationY;
    this.scene.beginDirectAnimation(root, [move, settle], 0, placementFrames + 7, false);
  }

  public enter(id: string, root: TransformNodeType): void {
    root.scaling.set(.16, .16, .16);
    const animation = new Animation(`cargoEntry-${id}`, "scaling", 60,
        Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
    animation.setKeys([{ frame: 0, value: root.scaling.clone() }, { frame: 28, value: new Vector3(1, 1, 1) }]);
    const easing = new CubicEase();
    easing.setEasingMode(EasingFunction.EASINGMODE_EASEOUT);
    animation.setEasingFunction(easing);
    this.scene.beginDirectAnimation(root, [animation], 0, 28, false);
  }

  public exit(id: string, root: TransformNodeType, finalScale: Vector3Type, onComplete: () => void): void {
    const animation = new Animation(`cargoExit-${id}`, "scaling", 60,
        Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
    animation.setKeys([{ frame: 0, value: root.scaling.clone() }, { frame: 24, value: finalScale }]);
    const easing = new CubicEase();
    easing.setEasingMode(EasingFunction.EASINGMODE_EASEIN);
    animation.setEasingFunction(easing);
    this.scene.beginDirectAnimation(root, [animation], 0, 24, false, 1, onComplete);
  }
}
