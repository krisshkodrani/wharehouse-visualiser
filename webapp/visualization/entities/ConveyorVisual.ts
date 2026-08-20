import type { Scene, TransformNode } from "@babylonjs/core";
import type { StandardMaterial } from "@babylonjs/core/Materials/standardMaterial";

import type { StationDefinition } from "../../model/types";
import Babylon from "../../vendor/BabylonRuntime";
import type MaterialFactory from "../factories/MaterialFactory";

const { MeshBuilder } = Babylon;

export interface ConveyorLaneVisual {
  station: StationDefinition;
  center: { x: number; z: number };
  length: number;
  depth: number;
}

/** Owns static conveyor geometry; transfer state remains in the scene orchestrator. */
export default class ConveyorVisual {
  private constructor(
    readonly lanes: ConveyorLaneVisual[],
    readonly cardboardMaterial: StandardMaterial
  ) {}

  static create(
    scene: Scene,
    parent: TransformNode | null,
    materials: MaterialFactory,
    stations: StationDefinition[]
  ): ConveyorVisual {
    const frame = materials.metal("conveyorFrame", "#59636c", 72);
    const belt = materials.create("conveyorBelt", "#20262b");
    const safety = materials.create("conveyorSafety", "#e87518");
    const cardboard = materials.cardboard("outboundCardboard");
    const lanes: ConveyorLaneVisual[] = [];

    stations.forEach((station, laneIndex) => {
      const length = Math.max(3, station.width - .35);
      const laneDepth = Math.max(.8, station.depth - .2);
      const center = stationPoint(station, 0, 0);
      const deck = MeshBuilder.CreateBox(
        `outboundConveyor-${laneIndex + 1}`,
        { width: length, height: 0.18, depth: laneDepth },
        scene
      );
      deck.position.set(center.x, 0.72, center.z);
      deck.rotation.y = station.rotationY;
      deck.material = belt;
      deck.parent = parent;

      for (const z of [-laneDepth / 2 - .06, laneDepth / 2 + .06]) {
        const rail = MeshBuilder.CreateBox(
          `conveyorRail-${laneIndex + 1}`,
          { width: length, height: 0.3, depth: 0.1 },
          scene
        );
        const point = stationPoint(station, 0, z);
        rail.position.set(point.x, 0.88, point.z);
        rail.rotation.y = station.rotationY;
        rail.material = safety;
        rail.parent = parent;
      }

      const rollerSpacing = 0.42;
      const rollerCount = Math.floor(length / rollerSpacing);
      for (let index = 0; index <= rollerCount; index += 1) {
        const roller = MeshBuilder.CreateCylinder(
          `conveyorRoller-${laneIndex + 1}-${index}`,
          { diameter: 0.16, height: 0.92, tessellation: 18 },
          scene
        );
        const point = stationPoint(station, -length / 2 + index * rollerSpacing, 0);
        roller.rotation.x = Math.PI / 2;
        roller.rotation.y = station.rotationY;
        roller.position.set(point.x, 0.84, point.z);
        roller.material = frame;
        roller.parent = parent;
      }
      for (const x of [-length / 2 + .3, -length / 4, length / 4, length / 2 - .3]) {
        const leg = MeshBuilder.CreateBox(
          `conveyorLeg-${laneIndex + 1}`,
          { width: 0.12, height: 0.7, depth: 0.12 },
          scene
        );
        const point = stationPoint(station, x, 0);
        leg.position.set(point.x, 0.35, point.z);
        leg.material = frame;
        leg.parent = parent;
      }
      createFlowArrows(scene, parent, station, length, laneDepth, safety);
      lanes.push({ station, center, length, depth: laneDepth });
    });
    return new ConveyorVisual(lanes, cardboard);
  }
}

function stationPoint(station: StationDefinition, localX: number, localZ: number): { x: number; z: number } {
  const cos = Math.cos(station.rotationY);
  const sin = Math.sin(station.rotationY);
  return {
    x: station.position[0] + localX * cos + localZ * sin,
    z: station.position[2] - localX * sin + localZ * cos
  };
}

function createFlowArrows(
  scene: Scene,
  parent: TransformNode | null,
  station: StationDefinition,
  length: number,
  laneDepth: number,
  material: StandardMaterial
): void {
  const count = Math.max(2, Math.floor(length / 2.4));
  for (let index = 0; index < count; index += 1) {
    const localX = -length / 2 + length * (index + .5) / count;
    for (const side of [-1, 1]) {
      const chevron = MeshBuilder.CreateBox(
        `conveyorFlowArrow-${station.id}-${index}-${side}`,
        { width: .5, height: .02, depth: .09 },
        scene
      );
      const point = stationPoint(station, localX + side * .16, side * laneDepth * .22);
      chevron.position.set(point.x, 1.01, point.z);
      chevron.rotation.y = station.rotationY + side * .6;
      chevron.material = material;
      chevron.parent = parent;
    }
  }
}
