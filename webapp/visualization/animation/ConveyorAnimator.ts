import Babylon from "../../vendor/BabylonRuntime";
import type { Scene as SceneType } from "@babylonjs/core/scene";
import type { TransformNode as TransformNodeType } from "@babylonjs/core/Meshes/transformNode";
import type { Vector3 as Vector3Type } from "@babylonjs/core/Maths/math.vector";

const { Animation, Vector3 } = Babylon;

/** Visual-only conveyor cargo travel and discharge timelines. */
export default class ConveyorAnimator {
  public constructor(private readonly scene: SceneType) {}

  public travel(id: string, root: TransformNodeType, destination: Vector3Type): void {
    const animation = new Animation(`conveyorTravel-${id}`, "position", 30,
        Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
    animation.setKeys([{ frame: 0, value: root.position.clone() }, { frame: 180, value: destination }]);
    this.scene.beginDirectAnimation(root, [animation], 0, 180, false);
  }

  public exit(root: TransformNodeType, destination: Vector3Type, onComplete: () => void): void {
    const movement = new Animation("conveyorCargoExit", "position", 60,
        Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
    movement.setKeys([{ frame: 0, value: root.position.clone() }, { frame: 30, value: destination }]);
    const scale = new Animation("conveyorCargoExitScale", "scaling", 60,
        Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
    scale.setKeys([{ frame: 0, value: root.scaling.clone() }, { frame: 30, value: new Vector3(.4, .4, .4) }]);
    this.scene.beginDirectAnimation(root, [movement, scale], 0, 30, false, 1, onComplete);
  }
}
