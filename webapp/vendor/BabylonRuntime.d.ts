import type { ActionManager } from "@babylonjs/core/Actions/actionManager";
import type { ExecuteCodeAction } from "@babylonjs/core/Actions/directActions";
import type { Animation } from "@babylonjs/core/Animations/animation";
import type { CubicEase, EasingFunction } from "@babylonjs/core/Animations/easing";
import type { ArcRotateCamera } from "@babylonjs/core/Cameras/arcRotateCamera";
import type { Engine } from "@babylonjs/core/Engines/engine";
import type { HemisphericLight } from "@babylonjs/core/Lights/hemisphericLight";
import type { DirectionalLight } from "@babylonjs/core/Lights/directionalLight";
import type { StandardMaterial } from "@babylonjs/core/Materials/standardMaterial";
import type { DynamicTexture } from "@babylonjs/core/Materials/Textures/dynamicTexture";
import type { Color3 } from "@babylonjs/core/Maths/math.color";
import type { Vector3 } from "@babylonjs/core/Maths/math.vector";
import type { MeshBuilder } from "@babylonjs/core/Meshes/meshBuilder";
import type { TransformNode } from "@babylonjs/core/Meshes/transformNode";
import type { Scene } from "@babylonjs/core/scene";

declare const Babylon: {
  ActionManager: typeof ActionManager;
  ExecuteCodeAction: typeof ExecuteCodeAction;
  Animation: typeof Animation;
  CubicEase: typeof CubicEase;
  EasingFunction: typeof EasingFunction;
  ArcRotateCamera: typeof ArcRotateCamera;
  Engine: typeof Engine;
  HemisphericLight: typeof HemisphericLight;
  DirectionalLight: typeof DirectionalLight;
  StandardMaterial: typeof StandardMaterial;
  DynamicTexture: typeof DynamicTexture;
  Color3: typeof Color3;
  Vector3: typeof Vector3;
  MeshBuilder: typeof MeshBuilder;
  TransformNode: typeof TransformNode;
  Scene: typeof Scene;
};

export default Babylon;
