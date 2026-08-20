import type { Mesh, Scene, TransformNode as TransformNodeType, Vector3 as Vector3Type } from "@babylonjs/core";

import Babylon from "../../vendor/BabylonRuntime";
import type MaterialFactory from "../factories/MaterialFactory";
import PalletVisual from "./PalletVisual";

const { Color3, MeshBuilder, TransformNode } = Babylon;

export default class ForkliftVisual {
  public constructor(
    readonly root: TransformNodeType,
    readonly lift: TransformNodeType,
    readonly forkAssembly: TransformNodeType,
    readonly wheels: Mesh[],
  ) {}

  static create(
    scene: Scene,
    parent: TransformNodeType | null,
    materials: MaterialFactory,
    position: Vector3Type,
    accentColor: string,
  ): ForkliftVisual {
    const root = new TransformNode("forklift", scene);
    root.position = position.clone();
    root.parent = parent;
    const bodyMaterial = materials.create("forkliftBody", "#f2b705");
    const darkMaterial = materials.create("forkliftDark", "#263442");
    const accentMaterial = materials.create("forkliftAccent", accentColor);
    const steelMaterial = materials.create("forkliftSteel", "#657382");
    const lightMaterial = materials.create("forkliftLights", "#fff4b0");
    lightMaterial.emissiveColor = Color3.FromHexString("#ffd966");
    const sensorMaterial = materials.create("forkliftSensors", "#68e5ff");
    sensorMaterial.emissiveColor = Color3.FromHexString("#168aa3");
    const safetyMaterial = materials.create("forkliftSafety", "#ff5b35");
    safetyMaterial.emissiveColor = Color3.FromHexString("#b8240d");

    const box = (name: string, size: { width: number; height: number; depth: number }, at: [number, number, number], material: typeof bodyMaterial, parentNode: TransformNodeType = root) => {
      const mesh = MeshBuilder.CreateBox(name, size, scene);
      mesh.position.set(...at);
      mesh.material = material;
      mesh.parent = parentNode;
      return mesh;
    };

    box("forkliftBody", { width: 1.05, height: 0.55, depth: 1.35 }, [0, 0.58, 0.12], bodyMaterial);
    box("forkliftCounterweight", { width: 1.12, height: 0.68, depth: 0.42 }, [0, 0.72, 0.72], accentMaterial);
    box("agvElectronicsDeck", { width: 0.9, height: 0.34, depth: 0.72 }, [0, 1.04, 0.3], bodyMaterial);

    const wheels: Mesh[] = [];
    for (const z of [-0.43, 0.48]) {
      for (const x of [-0.53, 0.53]) {
        const wheel = MeshBuilder.CreateCylinder("forkliftWheel", { diameter: z < 0 ? 0.46 : 0.52, height: 0.2, tessellation: 20 }, scene);
        wheel.rotation.z = Math.PI / 2;
        wheel.position.set(x, z < 0 ? 0.34 : 0.37, z);
        wheel.material = darkMaterial;
        wheel.parent = root;
        wheels.push(wheel);
      }
    }

    box("agvControllerHousing", { width: 0.64, height: 0.28, depth: 0.42 }, [0, 1.34, 0.32], darkMaterial);
    const lidarStem = MeshBuilder.CreateCylinder("agvLidarStem", { diameter: 0.09, height: 0.25, tessellation: 16 }, scene);
    lidarStem.position.set(0, 1.6, 0.32);
    lidarStem.material = steelMaterial;
    lidarStem.parent = root;
    const lidar = MeshBuilder.CreateCylinder("agvLidar", { diameter: 0.36, height: 0.18, tessellation: 32 }, scene);
    lidar.position.set(0, 1.79, 0.32);
    lidar.material = sensorMaterial;
    lidar.parent = root;
    const lidarBand = MeshBuilder.CreateTorus("agvLidarBand", { diameter: 0.37, thickness: 0.035, tessellation: 32 }, scene);
    lidarBand.position.set(0, 1.79, 0.32);
    lidarBand.material = darkMaterial;
    lidarBand.parent = root;

    for (const x of [-0.38, 0.38]) {
      box("agvSideSensor", { width: 0.13, height: 0.18, depth: 0.28 }, [x, 1.3, 0.13], sensorMaterial);
    }
    const statusBeacon = MeshBuilder.CreateCylinder("agvStatusBeacon", { diameter: 0.2, height: 0.16, tessellation: 20 }, scene);
    statusBeacon.position.set(0, 1.58, 0.58);
    statusBeacon.material = safetyMaterial;
    statusBeacon.parent = root;
    box("agvSafetyBumper", { width: 1.16, height: 0.18, depth: 0.14 }, [0, 0.4, 0.95], safetyMaterial);

    for (const x of [-0.38, 0.38]) {
      box("forkliftMast", { width: 0.11, height: 4.4, depth: 0.14 }, [x, 2.25, -0.78], steelMaterial);
      box("forkliftHeadlight", { width: 0.18, height: 0.16, depth: 0.08 }, [x, 1.58, -0.89], lightMaterial);
    }
    for (const y of [0.34, 1.42, 2.5, 3.58, 4.4]) {
      box("forkliftMastCrossbar", { width: 0.9, height: 0.1, depth: 0.13 }, [0, y, -0.78], steelMaterial);
    }

    const lift = new TransformNode("forkliftLift", scene);
    lift.parent = root;
    const forkAssembly = new TransformNode("forkliftForkAssembly", scene);
    forkAssembly.parent = lift;
    box("forkliftCarriage", { width: 0.78, height: 0.48, depth: 0.12 }, [0, 0.63, -0.9], darkMaterial, forkAssembly);
    for (const x of [-0.25, 0.25]) {
      box("forkliftBackrest", { width: 0.07, height: 0.75, depth: 0.07 }, [x, 0.82, -0.98], darkMaterial, forkAssembly);
      box("forkliftFork", { width: 0.1, height: 0.09, depth: 1.35 }, [x, 0.32, -1.5], steelMaterial, forkAssembly);
    }
    return new ForkliftVisual(root, lift, forkAssembly, wheels);
  }

  setPose(x: number, z: number, rotationY: number): number {
    const distance = Math.hypot(x - this.root.position.x, z - this.root.position.z);
    this.root.position.x = x;
    this.root.position.z = z;
    this.root.rotation.y = rotationY;
    return distance;
  }

  rotateWheels(distance: number): void {
    for (const wheel of this.wheels) wheel.rotation.y += distance / .24;
  }

  setForkHeight(height: number): void {
    this.lift.position.y = height;
  }

  setForkExtension(extension: number): void {
    this.forkAssembly.position.z = -extension;
  }

  attachCargo(cargo: PalletVisual): void {
    cargo.attachTo(this.forkAssembly);
  }

  detachCargo(cargo: PalletVisual, parent: TransformNodeType | null): void {
    cargo.detachTo(parent);
  }

  dispose(): void {
    this.root.dispose(false, false);
  }
}
