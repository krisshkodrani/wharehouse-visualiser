import Babylon from "../../vendor/BabylonRuntime";
import type { Color3 } from "@babylonjs/core/Maths/math.color";
import type { Mesh } from "@babylonjs/core/Meshes/mesh";
import type { Scene as SceneType } from "@babylonjs/core/scene";
import type { TransformNode as TransformNodeType } from "@babylonjs/core/Meshes/transformNode";
import type { StandardMaterial as StandardMaterialType } from "@babylonjs/core/Materials/standardMaterial";
import type { RackDefinition } from "../../model/types";
import MaterialFactory from "../factories/MaterialFactory";

const { MeshBuilder, TransformNode, Vector3 } = Babylon;

/** Owns the meshes and shared cargo materials for one logical rack. */
export default class RackVisual {
  public constructor(
    public readonly meshes: Mesh[],
    public readonly material: StandardMaterialType,
    public readonly definition: RackDefinition,
    public readonly width: number,
    public readonly cardboardMaterial: StandardMaterialType,
    public readonly palletMaterial: StandardMaterialType
  ) {}

  public static create(scene: SceneType, parent: TransformNodeType, materials: MaterialFactory,
      definition: RackDefinition, accentColor: string): RackVisual {
    const root = new TransformNode(`rack-${definition.id}`, scene);
    root.position = Vector3.FromArray(definition.position);
    root.rotation.y = definition.rotationY ?? 0;
    root.parent = parent;
    const material = materials.metal(`rackMaterial-${definition.id}`, accentColor, 64);
    const beamMaterial = materials.metal(`rackBeamMaterial-${definition.id}`, "#e87518", 48);
    const shelfMaterial = materials.metal(`shelfMaterial-${definition.id}`, "#7f898f", 80);
    const cardboardMaterial = materials.cardboard(`cardboard-${definition.id}`);
    const palletMaterial = materials.pallet(`palletWood-${definition.id}`);
    const meshes: Mesh[] = [];
    const width = definition.bays * 1.05;
    for (const x of [-width / 2, width / 2]) {
      const upright = MeshBuilder.CreateBox(`${definition.id}-upright`, { width: .12, height: 4.05, depth: .62 }, scene);
      upright.position.set(x, 2.025, 0); upright.material = material; upright.parent = root; meshes.push(upright);
    }
    for (let bay = 1; bay < definition.bays; bay += 1) {
      const divider = MeshBuilder.CreateBox(`${definition.id}-divider`, { width: .09, height: 4.05, depth: .58 }, scene);
      divider.position.set(-width / 2 + bay * 1.05, 2.025, 0); divider.material = material; divider.parent = root; meshes.push(divider);
    }
    for (const y of [.38, 1.48, 2.58, 3.68]) {
      const shelf = MeshBuilder.CreateBox(`${definition.id}-shelf`, { width: width + .08, height: .045, depth: .62 }, scene);
      shelf.position.set(0, y, 0); shelf.material = shelfMaterial; shelf.parent = root; meshes.push(shelf);
      for (const z of [-.34, .34]) {
        const beam = MeshBuilder.CreateBox(`${definition.id}-load-beam`, { width: width + .2, height: .18, depth: .09 }, scene);
        beam.position.set(0, y - .045, z); beam.material = beamMaterial; beam.parent = root; meshes.push(beam);
      }
    }
    for (const x of [-width / 2, width / 2]) for (let segment = 0; segment < 3; segment += 1) {
      for (const direction of [-1, 1]) {
        const brace = MeshBuilder.CreateBox(`${definition.id}-brace`, { width: .07, height: 1.18, depth: .055 }, scene);
        brace.position.set(x, .93 + segment * 1.1, direction * .04);
        brace.rotation.x = direction * .5; brace.material = shelfMaterial; brace.parent = root; meshes.push(brace);
      }
    }
    return new RackVisual(meshes, material, definition, width, cardboardMaterial, palletMaterial);
  }

  public setHighlighted(highlighted: boolean, color: Color3): void {
    for (const mesh of this.meshes) {
      mesh.renderOverlay = highlighted;
      mesh.overlayColor = color;
      mesh.overlayAlpha = .65;
    }
  }

  public dispose(): void {
    for (const mesh of this.meshes) mesh.dispose(false, false);
  }
}
