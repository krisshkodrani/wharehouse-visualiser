import type { Scene, TransformNode as TransformNodeType } from "@babylonjs/core";
import type { StandardMaterial } from "@babylonjs/core/Materials/standardMaterial";

import Babylon from "../../vendor/BabylonRuntime";
import type MaterialFactory from "../factories/MaterialFactory";

const { MeshBuilder, TransformNode } = Babylon;

const FLOOR_HALF_WIDTH = 24;
const FLOOR_HALF_DEPTH = 18;
const APRON_WIDTH = 8;
const APRON_DEPTH = 14;
const APRON_CENTRE_Z = 13.5;

/** Owns the fixed building root and floor surfaces. */
export default class WarehouseStructureVisual {
  private constructor(
    readonly root: TransformNodeType,
    readonly floorMaterial: StandardMaterial
  ) {}

  static create(
    scene: Scene,
    materials: MaterialFactory,
    warehouseId: string,
    floorColor: string
  ): WarehouseStructureVisual {
    const root = new TransformNode(`warehouse-${warehouseId}`, scene);
    const floorMaterial = materials.floor("floor", floorColor);
    const floor = MeshBuilder.CreateGround(
      "floor",
      { width: FLOOR_HALF_WIDTH * 2, height: FLOOR_HALF_DEPTH * 2 },
      scene
    );
    floor.material = floorMaterial;
    floor.parent = root;
    floor.receiveShadows = true;

    const apron = MeshBuilder.CreateGround(
      "shippingApron", { width: APRON_WIDTH, height: APRON_DEPTH }, scene);
    apron.position.set(
      -FLOOR_HALF_WIDTH - APRON_WIDTH / 2 + 0.05,
      0.001,
      APRON_CENTRE_Z
    );
    apron.material = floorMaterial;
    apron.receiveShadows = true;
    apron.parent = root;
    return new WarehouseStructureVisual(root, floorMaterial);
  }
}
