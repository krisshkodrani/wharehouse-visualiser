import type { Mesh, Scene, TransformNode as TransformNodeType } from "@babylonjs/core";
import type { StandardMaterial } from "@babylonjs/core/Materials/standardMaterial";

import type { RackDefinition } from "../../model/types";
import Babylon from "../../vendor/BabylonRuntime";
import PalletVisual from "../entities/PalletVisual";

const { MeshBuilder, TransformNode } = Babylon;

/** Creates identity-bearing pallet cargo without scene orchestration policy. */
export default class CargoFactory {
  static createRackCargo(
    scene: Scene,
    parent: TransformNodeType | null,
    rack: RackDefinition,
    rackWidth: number,
    bay: number,
    level: number,
    crateMaterial: StandardMaterial,
    palletMaterial: StandardMaterial,
    rackParts: Mesh[],
    loadId?: string
  ): PalletVisual {
    const localX = -rackWidth / 2 + 0.52 + bay * 1.05;
    const rotationY = rack.rotationY ?? 0;
    const cos = Math.cos(rotationY);
    const sin = Math.sin(rotationY);
    const cargoRoot = new TransformNode(`cargo-${rack.id}-${bay}-${level}`, scene);
    cargoRoot.position.set(
      rack.position[0] + localX * cos,
      0.47 + level * 1.1,
      rack.position[2] - localX * sin
    );
    cargoRoot.rotation.y = rotationY;
    cargoRoot.parent = parent;

    const palletParts: Mesh[] = [];
    for (const z of [-0.24, 0, 0.24]) {
      const slat = MeshBuilder.CreateBox(
        `${rack.id}-pallet-slat`, { width: 0.82, height: 0.075, depth: 0.13 }, scene);
      slat.position.set(0, 0.03, z);
      slat.material = palletMaterial;
      slat.parent = cargoRoot;
      palletParts.push(slat);
    }
    for (const x of [-0.31, 0, 0.31]) {
      const block = MeshBuilder.CreateBox(
        `${rack.id}-pallet-block`, { width: 0.14, height: 0.12, depth: 0.48 }, scene);
      block.position.set(x, -0.055, 0);
      block.material = palletMaterial;
      block.parent = cargoRoot;
      palletParts.push(block);
    }
    const crate = MeshBuilder.CreateBox(
      `${rack.id}-cargo`, { width: 0.72, height: 0.56, depth: 0.56 }, scene);
    crate.position.y = 0.34;
    crate.material = crateMaterial;
    crate.parent = cargoRoot;
    const cargoId = loadId ?? `${rack.id}-${bay + 1}-${level + 1}`;
    for (const mesh of [...palletParts, crate]) mesh.metadata = { loadId: cargoId };
    rackParts.push(...palletParts, crate);
    return new PalletVisual(cargoId, cargoRoot, false, [...palletParts, crate]);
  }
}
