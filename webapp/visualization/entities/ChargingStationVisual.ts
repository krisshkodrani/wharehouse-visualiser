import Babylon from "../../vendor/BabylonRuntime";
import type { Scene as SceneType } from "@babylonjs/core/scene";
import type { TransformNode as TransformNodeType } from "@babylonjs/core/Meshes/transformNode";
import type { StandardMaterial as StandardMaterialType } from "@babylonjs/core/Materials/standardMaterial";
import type { StationDefinition } from "../../model/types";
import MaterialFactory from "../factories/MaterialFactory";

const { Color3, DynamicTexture, MeshBuilder, StandardMaterial, TransformNode } = Babylon;

/** Owns parking/charging meshes and their active-state indicators. */
export default class ChargingStationVisual {
  private readonly root: TransformNodeType;
  private readonly indicators = new Map<string, StandardMaterialType>();

  public constructor(private readonly scene: SceneType, parent: TransformNodeType,
      private readonly materials: MaterialFactory) {
    this.root = new TransformNode("charging-stations", scene);
    this.root.parent = parent;
  }

  public build(stations: StationDefinition[]): void {
    if (stations.length === 0) return;
    const bay = this.materials.create("parkingBay", "#2d9c73");
    const safety = this.materials.create("parkingSafety", "#f2c94c");
    const charger = this.materials.metal("parkingCharger", "#323a42", 68);
    stations.forEach((station, index) => this.buildStation(station, index, bay, safety, charger));
  }

  public setActive(activeStationId?: string): void {
    for (const [stationId, material] of this.indicators) {
      const active = stationId === activeStationId;
      material.diffuseColor = Color3.FromHexString(active ? "#63e6be" : "#20473c");
      material.emissiveColor = Color3.FromHexString(active ? "#27c98b" : "#0d3025");
    }
  }

  public dispose(): void {
    this.indicators.clear();
    this.root.dispose(false, false);
  }

  private buildStation(station: StationDefinition, index: number, bay: StandardMaterialType,
      safety: StandardMaterialType, charger: StandardMaterialType): void {
    const number = index + 1;
    const halfWidth = station.width / 2;
    const halfDepth = station.depth / 2;
    for (const [x, z, width, depth] of [
      [0, -halfDepth, station.width, 0.1], [0, halfDepth, station.width, 0.1],
      [-halfWidth, 0, 0.1, station.depth], [halfWidth, 0, 0.1, station.depth]
    ] as number[][]) {
      const line = MeshBuilder.CreateBox(`parking-${number}-boundary`, { width, height: 0.025, depth }, this.scene);
      const point = this.point(station, x, z);
      line.position.set(point.x, 0.018, point.z);
      line.rotation.y = station.rotationY;
      line.material = bay;
      line.parent = this.root;
    }
    const postPoint = this.point(station, 0, halfDepth - 0.28);
    const post = MeshBuilder.CreateBox(`parking-${number}-charger`, { width: 0.62, height: 1.25, depth: 0.38 }, this.scene);
    post.position.set(postPoint.x, 0.625, postPoint.z);
    post.rotation.y = station.rotationY;
    post.material = charger;
    post.parent = this.root;

    const display = this.materials.create(`parkingDisplay-${station.id}`, "#20473c");
    display.emissiveColor = new Color3(.05, .18, .13);
    this.indicators.set(station.id, display);
    const screenPoint = this.point(station, 0, halfDepth - 0.48);
    const screen = MeshBuilder.CreateBox(`parking-${number}-display`, { width: 0.34, height: 0.22, depth: 0.025 }, this.scene);
    screen.position.set(screenPoint.x, 0.82, screenPoint.z);
    screen.rotation.y = station.rotationY;
    screen.material = display;
    screen.parent = this.root;

    const padPoint = this.point(station, 0, .15);
    const pad = MeshBuilder.CreateBox(`parking-${number}-charge-pad`, { width: 1.15, height: .025, depth: .72 }, this.scene);
    pad.position.set(padPoint.x, .02, padPoint.z);
    pad.rotation.y = station.rotationY;
    pad.material = charger;
    pad.parent = this.root;
    for (const x of [-.34, .34]) {
      const point = this.point(station, x, .15);
      const contact = MeshBuilder.CreateBox(`parking-${number}-contact`, { width: .17, height: .035, depth: .5 }, this.scene);
      contact.position.set(point.x, .045, point.z);
      contact.rotation.y = station.rotationY;
      contact.material = display;
      contact.parent = this.root;
    }
    for (const x of [-.72, .72]) {
      const point = this.point(station, x, -halfDepth + .42);
      const stop = MeshBuilder.CreateBox(`parking-${number}-wheel-stop`, { width: .46, height: .12, depth: .18 }, this.scene);
      stop.position.set(point.x, .06, point.z);
      stop.rotation.y = station.rotationY;
      stop.material = safety;
      stop.parent = this.root;
    }

    const texture = new DynamicTexture(`parking-${number}-label-texture`, { width: 256, height: 128 }, this.scene, true);
    texture.hasAlpha = true;
    texture.drawText(`P${number} CHARGE`, null, 84, "bold 42px Arial", "#63e6be", "transparent", true, true);
    const labelMaterial = new StandardMaterial(`parking-${number}-label-material`, this.scene);
    labelMaterial.diffuseTexture = texture;
    labelMaterial.emissiveColor = new Color3(.12, .3, .24);
    labelMaterial.useAlphaFromDiffuseTexture = true;
    const labelPoint = this.point(station, 0, 0);
    const label = MeshBuilder.CreatePlane(`parking-${number}-label`, { width: 1.4, height: .7 }, this.scene);
    label.position.set(labelPoint.x, .026, labelPoint.z);
    label.rotation.x = Math.PI / 2;
    label.rotation.y = station.rotationY + Math.PI;
    label.material = labelMaterial;
    label.parent = this.root;
  }

  private point(station: StationDefinition, localX: number, localZ: number): { x: number; z: number } {
    const cos = Math.cos(station.rotationY);
    const sin = Math.sin(station.rotationY);
    return {
      x: station.position[0] + localX * cos + localZ * sin,
      z: station.position[2] - localX * sin + localZ * cos
    };
  }
}
