import Babylon from "../vendor/BabylonRuntime";
import type { ArcRotateCamera as ArcRotateCameraType } from "@babylonjs/core/Cameras/arcRotateCamera";
import type { Engine as EngineType } from "@babylonjs/core/Engines/engine";
import type { StandardMaterial as StandardMaterialType } from "@babylonjs/core/Materials/standardMaterial";
import type { Vector3 as Vector3Type } from "@babylonjs/core/Maths/math.vector";
import type { Mesh } from "@babylonjs/core/Meshes/mesh";
import type { TransformNode as TransformNodeType } from "@babylonjs/core/Meshes/transformNode";
import type { Scene as SceneType } from "@babylonjs/core/scene";
import type { ApiAgv, ConveyorVisualDefinition, LoadVisualDefinition, ObstacleDefinition, RackDefinition, RobotCellVisualDefinition, StationDefinition, WarehouseVisualConfig } from "../model/types";

const {
  ActionManager,
  ExecuteCodeAction,
  Animation,
  CubicEase,
  EasingFunction,
  ArcRotateCamera,
  Engine,
  HemisphericLight,
  DirectionalLight,
  StandardMaterial,
  DynamicTexture,
  Color3,
  Vector3,
  MeshBuilder,
  TransformNode,
  Scene
} = Babylon;

interface RackParts {
  meshes: Mesh[];
  material: StandardMaterialType;
  definition: RackDefinition;
  width: number;
  cardboardMaterial: StandardMaterialType;
  palletMaterial: StandardMaterialType;
}

interface RackCollider {
  id: string;
  x: number;
  z: number;
  halfWidth: number;
  halfDepth: number;
  rotationY: number;
}

interface CargoItem {
  id: string;
  root: TransformNodeType;
  carried: boolean;
  meshes?: Mesh[];
}

interface PoseSample { receivedAt: number; x: number; z: number; theta: number; velocity: number; }

// Double both dimensions to provide four times the original floor area.
const FLOOR_HALF_WIDTH = 24;
const FLOOR_HALF_DEPTH = 18;
const FORKLIFT_RADIUS = 0.72;
const MAX_FORK_HEIGHT = 2.75;
const OUTBOUND_CONVEYOR_START = -2.2;
const OUTBOUND_CONVEYOR_END = 8.2;
const OUTBOUND_CONVEYOR_LENGTH = OUTBOUND_CONVEYOR_END - OUTBOUND_CONVEYOR_START;
const OUTBOUND_CONVEYOR_CENTER = (OUTBOUND_CONVEYOR_START + OUTBOUND_CONVEYOR_END) / 2;
const OUTBOUND_CONVEYOR_LANES = [-0.78, 0.78];

export default class WarehouseScene {
  private readonly engine: EngineType;
  private readonly scene: SceneType;
  private readonly camera: ArcRotateCameraType;
  private warehouseRoot?: TransformNodeType;
  private forklift?: TransformNodeType;
  private forkliftLift?: TransformNodeType;
  private readonly poseBuffer: PoseSample[] = [];
  private lastRenderedPosition?: Vector3Type;
  private readonly wheelMeshes: Mesh[] = [];
  private forkliftForkAssembly?: TransformNodeType;
  private readonly chargingIndicators = new Map<string, StandardMaterialType>();
  private sandboxMode = false;
  private readonly pressedKeys = new Set<string>();
  private manualControlActive = false;
  private collisionBlocked = false;
  private readonly rackColliders: RackCollider[] = [];
  private readonly cargoItems: CargoItem[] = [];
  private readonly inboundCargoItems: CargoItem[] = [];
  private readonly conveyorCargoItems: CargoItem[] = [];
  private readonly companionAgvs = new Map<string, TransformNodeType>();
  private robotCellRoot?: TransformNodeType;
  private robotArm?: TransformNodeType;
  private robotGripper?: TransformNodeType;
  private carriedCargo?: CargoItem;
  private inboundCardboardMaterial?: StandardMaterialType;
  private inboundPalletMaterial?: StandardMaterialType;
  private conveyorCardboardMaterial?: StandardMaterialType;
  private inboundStation?: StationDefinition;
  private outboundStation?: StationDefinition;
  private forkliftStops?: [Vector3Type, Vector3Type];
  private nextForkliftStop = 1;
  private selectedRackId?: string;
  private readonly rackParts = new Map<string, RackParts>();
  private highlightMaterial?: StandardMaterialType;
  private lastPoseTelemetryAt = 0;
  private liveMotionBlocked = false;
  private robotCellPhase = "IDLE";

  public constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly onRackSelected: (rackId: string, rackName: string) => void,
    private readonly onLoadHover: (loadId?: string) => void
  ) {
    this.engine = new Engine(canvas, true, { preserveDrawingBuffer: true, stencil: true });
    this.scene = new Scene(this.engine);
    this.scene.clearColor.set(0.035, 0.055, 0.08, 1);

    this.camera = new ArcRotateCamera("warehouseCamera", -Math.PI / 2.25, Math.PI / 3.1, 34, new Vector3(0, 1.8, 0), this.scene);
    this.camera.lowerRadiusLimit = 10;
    this.camera.upperRadiusLimit = 65;
    this.camera.lowerBetaLimit = 0.3;
    this.camera.upperBetaLimit = Math.PI / 2.05;
    this.camera.wheelPrecision = 35;
    this.camera.attachControl(canvas, true);

    const skyLight = new HemisphericLight("skyLight", new Vector3(0, 1, 0), this.scene);
    skyLight.intensity = 0.8;
    const keyLight = new DirectionalLight("keyLight", new Vector3(-0.6, -1, 0.4), this.scene);
    keyLight.position = new Vector3(7, 12, -7);
    keyLight.intensity = 1.1;

    window.addEventListener("keydown", this.onKeyDown, { capture: true });
    window.addEventListener("keyup", this.onKeyUp, { capture: true });
    window.addEventListener("blur", this.onWindowBlur);
    this.scene.onBeforeRenderObservable.add(this.updateFrame);
    this.engine.runRenderLoop(() => this.scene.render());
  }

  public setWarehouse(config: WarehouseVisualConfig): void {
    const previousForkliftPosition = this.forklift?.position.clone();
    const previousForkliftRotation = this.forklift?.rotation.y;
    this.disposeWarehouse();
    this.warehouseRoot = new TransformNode(`warehouse-${config.id}`, this.scene);
    this.highlightMaterial = this.createMaterial("rackHighlight", "#ffd34e");

    const floorMaterial = this.createConcreteFloorMaterial(config.floorColor);
    const floor = MeshBuilder.CreateGround("floor", { width: FLOOR_HALF_WIDTH * 2, height: FLOOR_HALF_DEPTH * 2 }, this.scene);
    floor.material = floorMaterial;
    floor.parent = this.warehouseRoot;
    floor.receiveShadows = true;
    const inboundStation = config.stations?.find((station) => station.canonicalId === "REC-STG-01" || station.type === "RECEIVING_STAGING" || station.type === "INBOUND") ?? {
      id: "INBOUND-01", type: "INBOUND", position: config.forkliftStops[0], rotationY: 0, width: 7, depth: 7
    } as StationDefinition;
    const outboundStation = config.stations?.find((station) => station.canonicalId === "OUT-STG-01" || station.type === "OUTBOUND_STAGING" || station.type === "OUTBOUND") ?? {
      id: "OUTBOUND-01", type: "OUTBOUND", position: config.forkliftStops[1], rotationY: Math.PI, width: 7, depth: 6
    } as StationDefinition;
    this.createFloorMarkings(inboundStation, outboundStation);

    for (const rack of config.racks) {
      this.createRack(rack, config.accentColor);
    }
    for (const obstacle of config.obstacles ?? []) this.createObstacle(obstacle);
    this.createParkingAreas(config.stations?.filter((station) => station.type === "PARKING_CHARGING" || station.type === "CHARGING_AREA") ?? []);
    this.createSign(config.signText, config.accentColor);
    this.createInboundStaging(inboundStation, config.inboundLoads ?? this.placeholderLoads(config.inboundCount ?? 0));
    this.createOutboundConveyor(outboundStation, config.conveyorLoads ?? [], config.conveyorTransfers ?? []);
    this.createRobotCell(outboundStation, config.robotCells ?? []);
    this.forkliftStops = [Vector3.FromArray(config.forkliftStops[0]), Vector3.FromArray(config.forkliftStops[1])];
    this.nextForkliftStop = 1;
    this.createForklift(previousForkliftPosition ?? this.forkliftStops[0], config.accentColor);
    this.createCompanionAgvs(config.fleetAgvs ?? []);
    if (previousForkliftRotation !== undefined && this.forklift) this.forklift.rotation.y = previousForkliftRotation;
    if (config.carriedLoadId) this.createLiveCarriedCargo(config.carriedLoadId);
    this.updateChargingIndicators(config.chargingStationId);
    this.telemetry("SCENE_CONFIGURED", {
      warehouseId: config.id, racks: config.racks.length, obstacles: config.obstacles?.length ?? 0,
      inbound: inboundStation.position, outbound: outboundStation.position
    });
  }

  public updateOperations(config: WarehouseVisualConfig): void {
    if (!this.warehouseRoot) {
      this.setWarehouse(config);
      return;
    }
    this.syncCarriedCargo(config.carriedLoadId);
    this.syncRackCargo(config.racks);
    this.syncInboundCargo(config.inboundLoads ?? this.placeholderLoads(config.inboundCount ?? 0));
    this.syncConveyorCargo(config.conveyorLoads ?? []);
    this.syncRobotCell(config.robotCells ?? []);
    this.syncCompanionAgvs(config.fleetAgvs ?? []);
    this.updateChargingIndicators(config.chargingStationId);
  }

  public moveForklift(): void {
    if (!this.sandboxMode || !this.forklift || !this.forkliftStops) {
      return;
    }
    this.scene.stopAnimation(this.forklift);
    if (this.forkliftLift) {
      this.scene.stopAnimation(this.forkliftLift);
    }
    const destinationIndex = this.nextForkliftStop;
    const start = this.forklift.position.clone();
    const end = this.forkliftStops[destinationIndex].clone();
    const animation = new Animation("forkliftMove", "position", 60, Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
    animation.setKeys([{ frame: 0, value: start }, { frame: 90, value: end }]);
    const easing = new CubicEase();
    easing.setEasingMode(EasingFunction.EASINGMODE_EASEINOUT);
    animation.setEasingFunction(easing);
    this.forklift.rotation.y = end.z >= start.z ? Math.PI : 0;
    if (this.forkliftLift) {
      const liftAnimation = new Animation("forkliftLift", "position.y", 60, Animation.ANIMATIONTYPE_FLOAT, Animation.ANIMATIONLOOPMODE_CONSTANT);
      liftAnimation.setKeys([
        { frame: 0, value: this.forkliftLift.position.y },
        { frame: 22, value: 1.6 },
        { frame: 68, value: 1.6 },
        { frame: 90, value: 0 }
      ]);
      liftAnimation.setEasingFunction(easing);
      const lift = this.forkliftLift;
      this.log(`forks lifting from ${lift.position.y.toFixed(2)}`);
      this.scene.beginDirectAnimation(lift, [liftAnimation], 0, 90, false, 1, () => {
        this.log(`forks lowered to ${lift.position.y.toFixed(2)}`);
      });
    }
    this.log(`forklift moving from ${this.formatPosition(start)} to ${this.formatPosition(end)}`);
    this.scene.beginDirectAnimation(this.forklift, [animation], 0, 90, false, 1, () => {
      this.log(`forklift arrived at ${this.formatPosition(end)}`);
    });
    this.nextForkliftStop = destinationIndex === 0 ? 1 : 0;
  }

  public setAgvState(agv: ApiAgv): void {
    if (this.sandboxMode) return;
    this.setAgvOperations(agv);
    const sample = { receivedAt: performance.now(), x: agv.x, z: agv.z, theta: -agv.theta - Math.PI / 2, velocity: agv.velocity ?? 0 };
    const previous = this.poseBuffer.at(-1);
    if (!previous || previous.x !== sample.x || previous.z !== sample.z || previous.theta !== sample.theta) this.poseBuffer.push(sample);
    while (this.poseBuffer.length > 40) this.poseBuffer.shift();
    const now = performance.now();
    if (now - this.lastPoseTelemetryAt >= 1000) {
      this.lastPoseTelemetryAt = now;
      this.telemetry("POSE_TARGET", {
        x: Number(agv.x.toFixed(2)), z: Number(agv.z.toFixed(2)), theta: Number(agv.theta.toFixed(2)), velocity: agv.velocity,
        renderedX: Number((this.forklift?.position.x ?? agv.x).toFixed(2)), renderedZ: Number((this.forklift?.position.z ?? agv.z).toFixed(2))
      });
    }
  }

  public setAgvOperations(agv: ApiAgv): void {
    if (this.sandboxMode) return;
    this.syncCarriedCargo(agv.carriedLoadId);
    this.updateChargingIndicators(agv.charging ? agv.currentStationId : undefined);
    if (this.forkliftLift) this.forkliftLift.position.y = Math.max(0, Math.min(MAX_FORK_HEIGHT, agv.forkHeight ?? 0));
    if (this.forkliftForkAssembly) this.forkliftForkAssembly.position.z = -Math.max(0, Math.min(.7, agv.forkExtension ?? 0));
  }

  public setSandboxMode(enabled: boolean): void {
    this.sandboxMode = enabled;
    this.pressedKeys.clear();
    this.manualControlActive = false;
  }

  public resize(): void {
    this.engine.resize();
  }

  public dispose(): void {
    window.removeEventListener("keydown", this.onKeyDown, { capture: true });
    window.removeEventListener("keyup", this.onKeyUp, { capture: true });
    window.removeEventListener("blur", this.onWindowBlur);
    this.pressedKeys.clear();
    this.disposeWarehouse();
    this.camera.detachControl();
    this.scene.dispose();
    this.engine.dispose();
  }

  private createRack(rack: RackDefinition, accentColor: string): void {
    const root = new TransformNode(`rack-${rack.id}`, this.scene);
    root.position = Vector3.FromArray(rack.position);
    root.rotation.y = rack.rotationY ?? 0;
    root.parent = this.warehouseRoot ?? null;
    const material = this.createMetalMaterial(`rackMaterial-${rack.id}`, accentColor, 64);
    const beamMaterial = this.createMetalMaterial(`rackBeamMaterial-${rack.id}`, "#e87518", 48);
    const shelfMaterial = this.createMetalMaterial(`shelfMaterial-${rack.id}`, "#7f898f", 80);
    const cardboardMaterial = this.createCardboardMaterial(`cardboard-${rack.id}`);
    const palletMaterial = this.createWoodMaterial(`palletWood-${rack.id}`);
    const parts: Mesh[] = [];
    const width = rack.bays * 1.05;
    this.rackColliders.push({
      id: rack.id,
      x: rack.position[0],
      z: rack.position[2],
      halfWidth: width / 2 + 0.12,
      halfDepth: 0.46,
      rotationY: rack.rotationY ?? 0
    });

    for (const x of [-width / 2, width / 2]) {
      const upright = MeshBuilder.CreateBox(`${rack.id}-upright`, { width: 0.12, height: 4.05, depth: 0.62 }, this.scene);
      upright.position.set(x, 2.025, 0);
      upright.material = material;
      upright.parent = root;
      parts.push(upright);
    }
    for (let bay = 1; bay < rack.bays; bay += 1) {
      const upright = MeshBuilder.CreateBox(`${rack.id}-divider`, { width: 0.09, height: 4.05, depth: 0.58 }, this.scene);
      upright.position.set(-width / 2 + bay * 1.05, 2.025, 0);
      upright.material = material;
      upright.parent = root;
      parts.push(upright);
    }
    for (const y of [0.38, 1.48, 2.58, 3.68]) {
      const shelf = MeshBuilder.CreateBox(`${rack.id}-shelf`, { width: width + 0.08, height: 0.045, depth: 0.62 }, this.scene);
      shelf.position.set(0, y, 0);
      shelf.material = shelfMaterial;
      shelf.parent = root;
      parts.push(shelf);
      for (const z of [-0.34, 0.34]) {
        const beam = MeshBuilder.CreateBox(`${rack.id}-load-beam`, { width: width + 0.2, height: 0.18, depth: 0.09 }, this.scene);
        beam.position.set(0, y - 0.045, z);
        beam.material = beamMaterial;
        beam.parent = root;
        parts.push(beam);
      }
    }
    for (const x of [-width / 2, width / 2]) {
      for (let segment = 0; segment < 3; segment += 1) {
        for (const direction of [-1, 1]) {
          const brace = MeshBuilder.CreateBox(`${rack.id}-brace`, { width: 0.07, height: 1.18, depth: 0.055 }, this.scene);
          brace.position.set(x, 0.93 + segment * 1.1, direction * 0.04);
          brace.rotation.x = direction * 0.5;
          brace.material = shelfMaterial;
          brace.parent = root;
          parts.push(brace);
        }
      }
    }
    if (rack.loads) {
      for (const load of rack.loads) this.createCargo(rack, width, load.bay, load.level, cardboardMaterial, palletMaterial, parts, false, load.id);
    } else for (let bay = 0; bay < rack.bays; bay += 1) {
      for (let level = 0; level < 3; level += 1) {
        if (!rack.emptySlots?.some(([emptyBay, emptyLevel]) => emptyBay === bay && emptyLevel === level))
          this.createCargo(rack, width, bay, level, cardboardMaterial, palletMaterial, parts, false);
      }
    }
    for (const part of parts) {
      part.metadata = { ...(part.metadata as object | undefined), rackId: rack.id };
      part.isPickable = true;
      part.actionManager = new ActionManager(this.scene);
      part.actionManager.registerAction(new ExecuteCodeAction(ActionManager.OnPickTrigger, () => this.selectRack(rack.id, rack.name)));
      const loadId = (part.metadata as { loadId?: string }).loadId;
      if (loadId) this.registerLoadHover(part, loadId);
    }
    this.rackParts.set(rack.id, {
      meshes: parts,
      material,
      definition: rack,
      width,
      cardboardMaterial,
      palletMaterial
    });
  }

  private createCargo(
    rack: RackDefinition,
    rackWidth: number,
    bay: number,
    level: number,
    crateMaterial: StandardMaterialType,
    palletMaterial: StandardMaterialType,
    rackParts: Mesh[],
    animateEntry: boolean,
    loadId?: string
  ): CargoItem {
    const localX = -rackWidth / 2 + 0.52 + bay * 1.05;
    const rotationY = rack.rotationY ?? 0;
    const cos = Math.cos(rotationY);
    const sin = Math.sin(rotationY);
    const cargoRoot = new TransformNode(`cargo-${rack.id}-${bay}-${level}`, this.scene);
    cargoRoot.position.set(
      rack.position[0] + localX * cos,
      0.47 + level * 1.1,
      rack.position[2] - localX * sin
    );
    cargoRoot.rotation.y = rotationY;
    cargoRoot.parent = this.warehouseRoot ?? null;

    const palletParts: Mesh[] = [];
    for (const z of [-0.24, 0, 0.24]) {
      const slat = MeshBuilder.CreateBox(`${rack.id}-pallet-slat`, { width: 0.82, height: 0.075, depth: 0.13 }, this.scene);
      slat.position.set(0, 0.03, z);
      slat.material = palletMaterial;
      slat.parent = cargoRoot;
      palletParts.push(slat);
    }
    for (const x of [-0.31, 0, 0.31]) {
      const block = MeshBuilder.CreateBox(`${rack.id}-pallet-block`, { width: 0.14, height: 0.12, depth: 0.48 }, this.scene);
      block.position.set(x, -0.055, 0);
      block.material = palletMaterial;
      block.parent = cargoRoot;
      palletParts.push(block);
    }
    const crate = MeshBuilder.CreateBox(`${rack.id}-cargo`, { width: 0.72, height: 0.56, depth: 0.56 }, this.scene);
    crate.position.y = 0.34;
    crate.material = crateMaterial;
    crate.parent = cargoRoot;
    const cargoId = loadId ?? `${rack.id}-${bay + 1}-${level + 1}`;
    for (const mesh of [...palletParts, crate]) mesh.metadata = { loadId: cargoId };
    rackParts.push(...palletParts, crate);
    const item = { id: cargoId, root: cargoRoot, carried: false, meshes: [...palletParts, crate] };
    this.cargoItems.push(item);
    if (animateEntry) this.animateCargoEntry(item);
    return item;
  }

  private readonly onKeyDown = (event: KeyboardEvent): void => {
    if (!this.sandboxMode || !this.isControlKey(event.code) || this.isEditingTarget(event.target)) {
      return;
    }
    event.preventDefault();
    const firstPress = !this.pressedKeys.has(event.code);
    if (firstPress) {
      this.log(`manual control engaged: ${event.code}`);
    }
    this.pressedKeys.add(event.code);
    if (firstPress && event.code === "KeyE") {
      this.toggleCargo();
    }
  };

  private readonly onKeyUp = (event: KeyboardEvent): void => {
    if (!this.isControlKey(event.code)) {
      return;
    }
    event.preventDefault();
    this.pressedKeys.delete(event.code);
    if (this.forklift && this.forkliftLift) {
      this.log(`manual state position ${this.formatPosition(this.forklift.position)}; heading ${this.forklift.rotation.y.toFixed(2)}; forks ${this.forkliftLift.position.y.toFixed(2)}`);
    }
  };

  private readonly onWindowBlur = (): void => {
    this.pressedKeys.clear();
  };

  private readonly updateManualControls = (): void => {
    if (!this.forklift || !this.forkliftLift || this.pressedKeys.size === 0) {
      this.manualControlActive = false;
      return;
    }
    if (!this.manualControlActive) {
      this.scene.stopAnimation(this.forklift);
      this.scene.stopAnimation(this.forkliftLift);
      this.manualControlActive = true;
    }

    const seconds = Math.min(this.engine.getDeltaTime(), 50) / 1000;
    const steering = (this.pressedKeys.has("KeyD") ? 1 : 0) - (this.pressedKeys.has("KeyA") ? 1 : 0);
    const throttle = (this.pressedKeys.has("KeyW") ? 1 : 0) - (this.pressedKeys.has("KeyS") ? 1 : 0);
    this.forklift.rotation.y += steering * 1.75 * seconds;
    if (throttle !== 0) {
      const distance = throttle * 2.4 * seconds;
      const nextX = Math.max(-FLOOR_HALF_WIDTH + FORKLIFT_RADIUS, Math.min(FLOOR_HALF_WIDTH - FORKLIFT_RADIUS,
        this.forklift.position.x - Math.sin(this.forklift.rotation.y) * distance));
      const nextZ = Math.max(-FLOOR_HALF_DEPTH + FORKLIFT_RADIUS, Math.min(FLOOR_HALF_DEPTH - FORKLIFT_RADIUS,
        this.forklift.position.z - Math.cos(this.forklift.rotation.y) * distance));
      if (!this.collidesWithRack(nextX, nextZ)) {
        this.forklift.position.x = nextX;
        this.forklift.position.z = nextZ;
        this.collisionBlocked = false;
      } else if (!this.collisionBlocked) {
        this.log("rack collision blocked forklift movement");
        this.collisionBlocked = true;
      }
    } else {
      this.collisionBlocked = false;
    }

    const liftDirection = (this.pressedKeys.has("KeyF") ? 1 : 0) - (this.pressedKeys.has("KeyC") ? 1 : 0);
    if (liftDirection !== 0) {
      this.forkliftLift.position.y = Math.max(0, Math.min(MAX_FORK_HEIGHT, this.forkliftLift.position.y + liftDirection * 0.95 * seconds));
    }
  };

  private readonly updateFrame = (): void => {
    if (this.sandboxMode) {
      this.updateManualControls();
      return;
    }
    if (!this.forklift || this.poseBuffer.length === 0) return;
    const renderAt = performance.now() - 150;
    while (this.poseBuffer.length > 2 && this.poseBuffer[1].receivedAt <= renderAt) this.poseBuffer.shift();
    const from = this.poseBuffer[0];
    const to = this.poseBuffer[1] ?? from;
    const span = Math.max(1, to.receivedAt - from.receivedAt);
    const progress = Math.max(0, Math.min(1, (renderAt - from.receivedAt) / span));
    const nextX = from.x + (to.x - from.x) * progress;
    const nextZ = from.z + (to.z - from.z) * progress;
    let headingDelta = Math.atan2(Math.sin(to.theta - from.theta), Math.cos(to.theta - from.theta));
    const nextHeading = from.theta + headingDelta * progress;
    const previous = this.lastRenderedPosition ?? this.forklift.position.clone();
    const distance = Math.hypot(nextX - previous.x, nextZ - previous.z);
    this.forklift.position.x = nextX;
    this.forklift.position.z = nextZ;
    this.forklift.rotation.y = nextHeading;
    for (const wheel of this.wheelMeshes) wheel.rotation.x += distance / .25;
    this.lastRenderedPosition = new Vector3(nextX, this.forklift.position.y, nextZ);
    const collision = this.collidesWithRack(nextX, nextZ);
    if (collision && !this.liveMotionBlocked) {
      this.liveMotionBlocked = true;
      this.telemetry("LIVE_COLLISION_DETECTED", { x: Number(nextX.toFixed(2)), z: Number(nextZ.toFixed(2)) });
    } else if (!collision && this.liveMotionBlocked) {
      this.liveMotionBlocked = false;
      this.telemetry("LIVE_COLLISION_CLEARED", { x: Number(nextX.toFixed(2)), z: Number(nextZ.toFixed(2)) });
    }
  };

  private isControlKey(code: string): boolean {
    return ["KeyW", "KeyA", "KeyS", "KeyD", "KeyF", "KeyC", "KeyE"].includes(code);
  }

  private isEditingTarget(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) {
      return false;
    }
    return target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName);
  }

  private collidesWithRack(x: number, z: number): boolean {
    return this.rackColliders.some((rack) => {
      const dx = x - rack.x;
      const dz = z - rack.z;
      const cos = Math.cos(rack.rotationY);
      const sin = Math.sin(rack.rotationY);
      const localX = dx * cos - dz * sin;
      const localZ = dx * sin + dz * cos;
      return Math.abs(localX) < rack.halfWidth + FORKLIFT_RADIUS &&
        Math.abs(localZ) < rack.halfDepth + FORKLIFT_RADIUS;
    });
  }

  private toggleCargo(): void {
    if (!this.forklift || !this.forkliftLift || !this.warehouseRoot) {
      return;
    }
    if (this.carriedCargo) {
      const cargo = this.carriedCargo;
      const yaw = this.forklift.rotation.y;
      cargo.root.parent = this.warehouseRoot;
      cargo.root.position.set(
        this.forklift.position.x - Math.sin(yaw) * 1.72,
        this.forkliftLift.position.y + 0.43,
        this.forklift.position.z - Math.cos(yaw) * 1.72
      );
      cargo.root.rotation.y = yaw;
      cargo.carried = false;
      this.carriedCargo = undefined;
      this.log(`cargo dropped: ${cargo.id}`);
      return;
    }

    const tip = this.getForkPickupPoint();
    let nearest: CargoItem | undefined;
    let nearestDistance = Number.POSITIVE_INFINITY;
    for (const cargo of this.cargoItems) {
      if (cargo.carried) {
        continue;
      }
      const horizontalDistance = Math.hypot(cargo.root.position.x - tip.x, cargo.root.position.z - tip.z);
      const verticalDistance = Math.abs(cargo.root.position.y - tip.y);
      const score = horizontalDistance + verticalDistance;
      if (horizontalDistance <= 1.05 && verticalDistance <= 0.42 && score < nearestDistance) {
        nearest = cargo;
        nearestDistance = score;
      }
    }
    if (!nearest) {
      this.log("cargo pickup missed: align the forks with a pallet and match its height");
      return;
    }
    nearest.root.parent = this.forkliftLift;
    nearest.root.position.set(0, 0.43, -1.72);
    nearest.root.rotation.y = 0;
    nearest.carried = true;
    this.carriedCargo = nearest;
    this.log(`cargo picked up: ${nearest.id}`);
  }

  private getForkPickupPoint(): Vector3Type {
    const yaw = this.forklift?.rotation.y ?? 0;
    return new Vector3(
      (this.forklift?.position.x ?? 0) - Math.sin(yaw) * 1.72,
      (this.forkliftLift?.position.y ?? 0) + 0.43,
      (this.forklift?.position.z ?? 0) - Math.cos(yaw) * 1.72
    );
  }

  private selectRack(rackId: string, rackName: string): void {
    this.selectedRackId = rackId;
    for (const [id, rack] of this.rackParts) {
      for (const mesh of rack.meshes) {
        mesh.renderOverlay = id === rackId;
        mesh.overlayColor = this.highlightMaterial?.diffuseColor ?? Color3.Yellow();
        mesh.overlayAlpha = 0.65;
      }
    }
    this.onRackSelected(rackId, rackName);
  }

  private createForklift(position: Vector3Type, accentColor: string): void {
    const root = new TransformNode("forklift", this.scene);
    root.position = position.clone();
    root.parent = this.warehouseRoot ?? null;
    this.forklift = root;
    const bodyMaterial = this.createMaterial("forkliftBody", "#f2b705");
    const darkMaterial = this.createMaterial("forkliftDark", "#263442");
    const accentMaterial = this.createMaterial("forkliftAccent", accentColor);
    const steelMaterial = this.createMaterial("forkliftSteel", "#657382");
    const lightMaterial = this.createMaterial("forkliftLights", "#fff4b0");
    lightMaterial.emissiveColor = Color3.FromHexString("#ffd966");
    const sensorMaterial = this.createMaterial("forkliftSensors", "#68e5ff");
    sensorMaterial.emissiveColor = Color3.FromHexString("#168aa3");
    const safetyMaterial = this.createMaterial("forkliftSafety", "#ff5b35");
    safetyMaterial.emissiveColor = Color3.FromHexString("#b8240d");

    const body = MeshBuilder.CreateBox("forkliftBody", { width: 1.05, height: 0.55, depth: 1.35 }, this.scene);
    body.position.set(0, 0.58, 0.12);
    body.material = bodyMaterial;
    body.parent = root;

    const counterweight = MeshBuilder.CreateBox("forkliftCounterweight", { width: 1.12, height: 0.68, depth: 0.42 }, this.scene);
    counterweight.position.set(0, 0.72, 0.72);
    counterweight.material = accentMaterial;
    counterweight.parent = root;

    const electronicsDeck = MeshBuilder.CreateBox("agvElectronicsDeck", { width: 0.9, height: 0.34, depth: 0.72 }, this.scene);
    electronicsDeck.position.set(0, 1.04, 0.3);
    electronicsDeck.material = bodyMaterial;
    electronicsDeck.parent = root;

    for (const z of [-0.43, 0.48]) {
      for (const x of [-0.53, 0.53]) {
        const wheel = MeshBuilder.CreateCylinder("forkliftWheel", { diameter: z < 0 ? 0.46 : 0.52, height: 0.2, tessellation: 20 }, this.scene);
        wheel.rotation.z = Math.PI / 2;
        wheel.position.set(x, z < 0 ? 0.34 : 0.37, z);
        wheel.material = darkMaterial;
        wheel.parent = root;
        this.wheelMeshes.push(wheel);
      }
    }

    const controllerHousing = MeshBuilder.CreateBox("agvControllerHousing", { width: 0.64, height: 0.28, depth: 0.42 }, this.scene);
    controllerHousing.position.set(0, 1.34, 0.32);
    controllerHousing.material = darkMaterial;
    controllerHousing.parent = root;

    const lidarStem = MeshBuilder.CreateCylinder("agvLidarStem", { diameter: 0.09, height: 0.25, tessellation: 16 }, this.scene);
    lidarStem.position.set(0, 1.6, 0.32);
    lidarStem.material = steelMaterial;
    lidarStem.parent = root;
    const lidar = MeshBuilder.CreateCylinder("agvLidar", { diameter: 0.36, height: 0.18, tessellation: 32 }, this.scene);
    lidar.position.set(0, 1.79, 0.32);
    lidar.material = sensorMaterial;
    lidar.parent = root;
    const lidarBand = MeshBuilder.CreateTorus("agvLidarBand", { diameter: 0.37, thickness: 0.035, tessellation: 32 }, this.scene);
    lidarBand.position.set(0, 1.79, 0.32);
    lidarBand.material = darkMaterial;
    lidarBand.parent = root;

    for (const x of [-0.38, 0.38]) {
      const sideSensor = MeshBuilder.CreateBox("agvSideSensor", { width: 0.13, height: 0.18, depth: 0.28 }, this.scene);
      sideSensor.position.set(x, 1.3, 0.13);
      sideSensor.material = sensorMaterial;
      sideSensor.parent = root;
    }

    const statusBeacon = MeshBuilder.CreateCylinder("agvStatusBeacon", { diameter: 0.2, height: 0.16, tessellation: 20 }, this.scene);
    statusBeacon.position.set(0, 1.58, 0.58);
    statusBeacon.material = safetyMaterial;
    statusBeacon.parent = root;

    const safetyBumper = MeshBuilder.CreateBox("agvSafetyBumper", { width: 1.16, height: 0.18, depth: 0.14 }, this.scene);
    safetyBumper.position.set(0, 0.4, 0.95);
    safetyBumper.material = safetyMaterial;
    safetyBumper.parent = root;

    for (const x of [-0.38, 0.38]) {
      const mast = MeshBuilder.CreateBox("forkliftMast", { width: 0.11, height: 4.4, depth: 0.14 }, this.scene);
      mast.position.set(x, 2.25, -0.78);
      mast.material = steelMaterial;
      mast.parent = root;
      const headlight = MeshBuilder.CreateBox("forkliftHeadlight", { width: 0.18, height: 0.16, depth: 0.08 }, this.scene);
      headlight.position.set(x, 1.58, -0.89);
      headlight.material = lightMaterial;
      headlight.parent = root;
    }
    for (const y of [0.34, 1.42, 2.5, 3.58, 4.4]) {
      const mastCrossbar = MeshBuilder.CreateBox("forkliftMastCrossbar", { width: 0.9, height: 0.1, depth: 0.13 }, this.scene);
      mastCrossbar.position.set(0, y, -0.78);
      mastCrossbar.material = steelMaterial;
      mastCrossbar.parent = root;
    }

    const lift = new TransformNode("forkliftLift", this.scene);
    lift.parent = root;
    this.forkliftLift = lift;
    const forkAssembly = new TransformNode("forkliftForkAssembly", this.scene);
    forkAssembly.parent = lift;
    this.forkliftForkAssembly = forkAssembly;
    const carriage = MeshBuilder.CreateBox("forkliftCarriage", { width: 0.78, height: 0.48, depth: 0.12 }, this.scene);
    carriage.position.set(0, 0.63, -0.9);
    carriage.material = darkMaterial;
    carriage.parent = forkAssembly;
    for (const x of [-0.25, 0.25]) {
      const backrest = MeshBuilder.CreateBox("forkliftBackrest", { width: 0.07, height: 0.75, depth: 0.07 }, this.scene);
      backrest.position.set(x, 0.82, -0.98);
      backrest.material = darkMaterial;
      backrest.parent = forkAssembly;
      const fork = MeshBuilder.CreateBox("forkliftFork", { width: 0.1, height: 0.09, depth: 1.35 }, this.scene);
      fork.position.set(x, 0.32, -1.5);
      fork.material = steelMaterial;
      fork.parent = forkAssembly;
    }
  }

  private createLiveCarriedCargo(loadId: string): void {
    if (!this.forkliftForkAssembly) return;
    const root = new TransformNode(`liveCarriedCargo-${loadId}`, this.scene);
    root.parent = this.forkliftForkAssembly;
    root.position.set(0, 0.43, -1.72);
    const palletMaterial = this.createWoodMaterial("liveCargoPalletWood");
    const cardboardMaterial = this.createCardboardMaterial("liveCargoCardboard");
    for (const z of [-0.24, 0, 0.24]) {
      const slat = MeshBuilder.CreateBox("liveCargoPalletSlat", { width: 0.82, height: 0.075, depth: 0.13 }, this.scene);
      slat.position.set(0, 0.03, z); slat.material = palletMaterial; slat.parent = root;
    }
    for (const x of [-0.31, 0, 0.31]) {
      const block = MeshBuilder.CreateBox("liveCargoPalletBlock", { width: 0.14, height: 0.12, depth: 0.48 }, this.scene);
      block.position.set(x, -0.055, 0); block.material = palletMaterial; block.parent = root;
    }
    const box = MeshBuilder.CreateBox("liveCarriedBox", { width: 0.72, height: 0.56, depth: 0.56 }, this.scene);
    box.position.y = 0.34; box.material = cardboardMaterial; box.parent = root;
    this.registerLoadHover(box, loadId);
    const item = { id: loadId, root, carried: true };
    this.carriedCargo = item;
    this.telemetry("CARGO_ATTACHED", {
      loadId, x: Number((this.forklift?.position.x ?? 0).toFixed(2)), z: Number((this.forklift?.position.z ?? 0).toFixed(2))
    });
  }

  private syncCarriedCargo(loadId?: string): void {
    if (loadId && this.carriedCargo?.id === loadId) return;
    if (this.carriedCargo) {
      const previousId = this.carriedCargo.id;
      this.carriedCargo.root.dispose(false, true);
      this.carriedCargo = undefined;
      this.telemetry("CARGO_DETACHED", { loadId: previousId });
    }
    if (!loadId || !this.forkliftForkAssembly) return;
    const existing = [...this.inboundCargoItems, ...this.cargoItems].find((item) => item.id === loadId);
    if (!existing) {
      this.createLiveCarriedCargo(loadId);
      return;
    }
    const inboundIndex = this.inboundCargoItems.indexOf(existing);
    if (inboundIndex >= 0) this.inboundCargoItems.splice(inboundIndex, 1);
    const rackIndex = this.cargoItems.indexOf(existing);
    if (rackIndex >= 0) this.cargoItems.splice(rackIndex, 1);
    existing.root.parent = this.forkliftForkAssembly;
    existing.root.position.set(0, .43, -1.72);
    existing.root.rotation.set(0, 0, 0);
    existing.root.scaling.set(1, 1, 1);
    existing.carried = true;
    this.carriedCargo = existing;
    this.telemetry("CARGO_ATTACHED", { loadId });
  }

  private animateForkHeight(height: number, frames: number): void {
    if (!this.forkliftLift) return;
    const animation = new Animation("liveForkHeight", "position.y", 60, Animation.ANIMATIONTYPE_FLOAT, Animation.ANIMATIONLOOPMODE_CONSTANT);
    animation.setKeys([{ frame: 0, value: this.forkliftLift.position.y }, { frame: frames, value: height }]);
    const easing = new CubicEase();
    easing.setEasingMode(EasingFunction.EASINGMODE_EASEINOUT);
    animation.setEasingFunction(easing);
    this.scene.beginDirectAnimation(this.forkliftLift, [animation], 0, frames, false);
  }

  private createObstacle(obstacle: ObstacleDefinition): void {
    const material = obstacle.type === "WALL"
      ? this.createMaterial(`wall-${obstacle.id}`, "#c7cbc8")
      : this.createMetalMaterial(`barrier-${obstacle.id}`, "#e5a614", 38);
    const body = MeshBuilder.CreateBox(obstacle.id, {
      width: obstacle.width, height: obstacle.height, depth: obstacle.depth
    }, this.scene);
    body.position.set(obstacle.position[0], obstacle.height / 2, obstacle.position[2]);
    body.rotation.y = obstacle.rotationY;
    body.material = material;
    body.parent = this.warehouseRoot ?? null;
    this.rackColliders.push({
      id: obstacle.id, x: obstacle.position[0], z: obstacle.position[2],
      halfWidth: obstacle.width / 2, halfDepth: obstacle.depth / 2, rotationY: obstacle.rotationY
    });
  }

  private createDockEquipment(station: StationDefinition, label: "RECEIVING" | "SHIPPING"): void {
    const steel = this.createMetalMaterial(`${label}-dockSteel`, "#59636c", 64);
    const rubber = this.createMaterial(`${label}-dockRubber`, "#20262b");
    const safety = this.createMaterial(`${label}-dockSafety`, "#e87518");
    const localDockZ = -station.depth / 2 - 1.45;
    for (const localX of [-1.8, 1.8]) {
      const doorPoint = this.stationPoint(station, localX, localDockZ);
      const door = MeshBuilder.CreateBox(`${label}-dockDoor`, { width: 2.8, height: 3.05, depth: 0.16 }, this.scene);
      door.position.set(doorPoint.x, 1.53, doorPoint.z); door.rotation.y = station.rotationY;
      door.material = steel; door.parent = this.warehouseRoot ?? null;
      for (const bumperX of [-1.12, 1.12]) {
        const bumperPoint = this.stationPoint(station, localX + bumperX, localDockZ + 0.16);
        const bumper = MeshBuilder.CreateBox(`${label}-dockBumper`, { width: 0.22, height: 0.7, depth: 0.25 }, this.scene);
        bumper.position.set(bumperPoint.x, 0.42, bumperPoint.z); bumper.rotation.y = station.rotationY;
        bumper.material = rubber; bumper.parent = this.warehouseRoot ?? null;
      }
      const levellerPoint = this.stationPoint(station, localX, localDockZ + 1.05);
      const leveller = MeshBuilder.CreateBox(`${label}-dockLeveller`, { width: 2.45, height: 0.08, depth: 1.65 }, this.scene);
      leveller.position.set(levellerPoint.x, 0.07, levellerPoint.z); leveller.rotation.y = station.rotationY;
      leveller.material = steel; leveller.parent = this.warehouseRoot ?? null;
    }

    // Scanner arch and four impact bollards mark the controlled hand-off point.
    for (const localX of [-1.15, 1.15]) {
      const point = this.stationPoint(station, localX, 1.75);
      const upright = MeshBuilder.CreateBox(`${label}-scannerUpright`, { width: 0.13, height: 2.25, depth: 0.13 }, this.scene);
      upright.position.set(point.x, 1.125, point.z); upright.rotation.y = station.rotationY;
      upright.material = safety; upright.parent = this.warehouseRoot ?? null;
    }
    const scannerTopPoint = this.stationPoint(station, 0, 1.75);
    const scannerTop = MeshBuilder.CreateBox(`${label}-scannerTop`, { width: 2.45, height: 0.13, depth: 0.13 }, this.scene);
    scannerTop.position.set(scannerTopPoint.x, 2.22, scannerTopPoint.z); scannerTop.rotation.y = station.rotationY;
    scannerTop.material = safety; scannerTop.parent = this.warehouseRoot ?? null;
    for (const localX of [-2.8, 2.8]) for (const localZ of [-2.2, 2.2]) {
      const point = this.stationPoint(station, localX, localZ);
      const bollard = MeshBuilder.CreateCylinder(`${label}-bollard`, { diameter: 0.22, height: 0.9, tessellation: 16 }, this.scene);
      bollard.position.set(point.x, 0.45, point.z); bollard.material = safety; bollard.parent = this.warehouseRoot ?? null;
    }

    const texture = new DynamicTexture(`${label}-floorLabelTexture`, { width: 512, height: 128 }, this.scene, true);
    texture.hasAlpha = true;
    texture.drawText(label, null, 88, "bold 54px Arial", "#f4f4ee", "transparent", true, true);
    const labelMaterial = new StandardMaterial(`${label}-floorLabelMaterial`, this.scene);
    labelMaterial.diffuseTexture = texture; labelMaterial.emissiveColor = new Color3(0.2, 0.2, 0.2); labelMaterial.useAlphaFromDiffuseTexture = true;
    const labelMesh = MeshBuilder.CreatePlane(`${label}-floorLabel`, { width: 4.8, height: 1.2 }, this.scene);
    const labelPoint = this.stationPoint(station, 0, 2.8);
    labelMesh.position.set(labelPoint.x, 0.028, labelPoint.z); labelMesh.rotation.x = Math.PI / 2; labelMesh.rotation.y = station.rotationY + Math.PI;
    labelMesh.material = labelMaterial; labelMesh.parent = this.warehouseRoot ?? null;
  }

  private stationPoint(station: StationDefinition, localX: number, localZ: number): { x: number; z: number } {
    const cos = Math.cos(station.rotationY);
    const sin = Math.sin(station.rotationY);
    return {
      x: station.position[0] + localX * cos + localZ * sin,
      z: station.position[2] - localX * sin + localZ * cos
    };
  }

  private createSign(text: string, accentColor: string): void {
    const texture = new DynamicTexture("warehouseSignTexture", { width: 1024, height: 256 }, this.scene, true);
    texture.hasAlpha = false;
    texture.drawText(text, null, 165, "bold 72px Arial", "white", accentColor, true, true);
    const material = new StandardMaterial("warehouseSignMaterial", this.scene);
    material.diffuseTexture = texture;
    material.emissiveColor = new Color3(0.18, 0.18, 0.18);
    const sign = MeshBuilder.CreatePlane("warehouseFloorWriting", { width: 8.4, height: 1.65 }, this.scene);
    sign.position.set(12.5, 0.025, 14.1);
    sign.rotation.x = Math.PI / 2;
    sign.rotation.y = Math.PI;
    sign.material = material;
    sign.parent = this.warehouseRoot ?? null;
  }

  private createParkingAreas(stations: StationDefinition[]): void {
    if (stations.length === 0) return;
    const bay = this.createMaterial("parkingBay", "#2d9c73");
    const safety = this.createMaterial("parkingSafety", "#f2c94c");
    const charger = this.createMetalMaterial("parkingCharger", "#323a42", 68);

    stations.forEach((station, index) => {
      const halfWidth = station.width / 2;
      const halfDepth = station.depth / 2;
      for (const [x, z, width, depth] of [
        [0, -halfDepth, station.width, 0.1],
        [0, halfDepth, station.width, 0.1],
        [-halfWidth, 0, 0.1, station.depth],
        [halfWidth, 0, 0.1, station.depth]
      ] as number[][]) {
        const line = MeshBuilder.CreateBox(`parking-${index + 1}-boundary`, { width, height: 0.025, depth }, this.scene);
        const point = this.stationPoint(station, x, z);
        line.position.set(point.x, 0.018, point.z);
        line.rotation.y = station.rotationY;
        line.material = bay;
        line.parent = this.warehouseRoot ?? null;
      }

      const postPoint = this.stationPoint(station, 0, halfDepth - 0.28);
      const post = MeshBuilder.CreateBox(`parking-${index + 1}-charger`, { width: 0.62, height: 1.25, depth: 0.38 }, this.scene);
      post.position.set(postPoint.x, 0.625, postPoint.z);
      post.rotation.y = station.rotationY;
      post.material = charger;
      post.parent = this.warehouseRoot ?? null;
      const screenPoint = this.stationPoint(station, 0, halfDepth - 0.48);
      const display = this.createMaterial(`parkingDisplay-${station.id}`, "#20473c");
      display.emissiveColor = new Color3(.05, .18, .13);
      this.chargingIndicators.set(station.id, display);
      const screen = MeshBuilder.CreateBox(`parking-${index + 1}-display`, { width: 0.34, height: 0.22, depth: 0.025 }, this.scene);
      screen.position.set(screenPoint.x, 0.82, screenPoint.z);
      screen.rotation.y = station.rotationY;
      screen.material = display;
      screen.parent = this.warehouseRoot ?? null;
      const chargePad = MeshBuilder.CreateBox(`parking-${index + 1}-charge-pad`, { width: 1.15, height: .025, depth: .72 }, this.scene);
      const padPoint = this.stationPoint(station, 0, .15);
      chargePad.position.set(padPoint.x, .02, padPoint.z); chargePad.rotation.y = station.rotationY;
      chargePad.material = charger; chargePad.parent = this.warehouseRoot ?? null;
      for (const x of [-.34, .34]) {
        const contact = MeshBuilder.CreateBox(`parking-${index + 1}-contact`, { width: .17, height: .035, depth: .5 }, this.scene);
        const contactPoint = this.stationPoint(station, x, .15);
        contact.position.set(contactPoint.x, .045, contactPoint.z); contact.rotation.y = station.rotationY;
        contact.material = display; contact.parent = this.warehouseRoot ?? null;
      }
      for (const x of [-0.72, 0.72]) {
        const stopPoint = this.stationPoint(station, x, -halfDepth + 0.42);
        const wheelStop = MeshBuilder.CreateBox(`parking-${index + 1}-wheel-stop`, { width: 0.46, height: 0.12, depth: 0.18 }, this.scene);
        wheelStop.position.set(stopPoint.x, 0.06, stopPoint.z);
        wheelStop.rotation.y = station.rotationY;
        wheelStop.material = safety;
        wheelStop.parent = this.warehouseRoot ?? null;
      }

      const texture = new DynamicTexture(`parking-${index + 1}-label-texture`, { width: 256, height: 128 }, this.scene, true);
      texture.hasAlpha = true;
      texture.drawText(`P${index + 1} CHARGE`, null, 84, "bold 42px Arial", "#63e6be", "transparent", true, true);
      const labelMaterial = new StandardMaterial(`parking-${index + 1}-label-material`, this.scene);
      labelMaterial.diffuseTexture = texture;
      labelMaterial.emissiveColor = new Color3(0.12, 0.3, 0.24);
      labelMaterial.useAlphaFromDiffuseTexture = true;
      const label = MeshBuilder.CreatePlane(`parking-${index + 1}-label`, { width: 1.4, height: 0.7 }, this.scene);
      const labelPoint = this.stationPoint(station, 0, 0);
      label.position.set(labelPoint.x, 0.026, labelPoint.z);
      label.rotation.x = Math.PI / 2;
      label.rotation.y = station.rotationY + Math.PI;
      label.material = labelMaterial;
      label.parent = this.warehouseRoot ?? null;
    });
    this.telemetry("PARKING_AREAS_CREATED", { count: stations.length, ids: stations.map((station) => station.id) });
  }

  private updateChargingIndicators(activeStationId?: string): void {
    for (const [stationId, material] of this.chargingIndicators) {
      const active = stationId === activeStationId;
      material.diffuseColor = Color3.FromHexString(active ? "#63e6be" : "#20473c");
      material.emissiveColor = Color3.FromHexString(active ? "#27c98b" : "#0d3025");
    }
  }

  private placeholderLoads(count: number): LoadVisualDefinition[] {
    return Array.from({ length: Math.min(count, 20) }, (_, index) => ({ id: `placeholder-${index}`, item: "PALLET" }));
  }

  private createInboundStaging(station: StationDefinition, loads: LoadVisualDefinition[]): void {
    this.inboundStation = station;
    const cardboard = this.createCardboardMaterial("inboundCardboard");
    const pallet = this.createWoodMaterial("inboundPalletWood");
    this.inboundCardboardMaterial = cardboard;
    this.inboundPalletMaterial = pallet;
    const yellow = this.createMaterial("inboundZone", "#e5b516");
    for (const [x, z, width, depth] of [[0, -station.depth / 2, station.width, 0.08], [0, station.depth / 2, station.width, 0.08], [-station.width / 2, 0, 0.08, station.depth], [station.width / 2, 0, 0.08, station.depth]] as number[][]) {
      const line = MeshBuilder.CreateBox("inboundBoundary", { width, height: 0.02, depth }, this.scene);
      const point = this.stationPoint(station, x, z);
      line.position.set(point.x, 0.015, point.z); line.rotation.y = station.rotationY; line.material = yellow; line.parent = this.warehouseRoot ?? null;
    }
    this.createDockEquipment(station, "RECEIVING");
    loads.slice(0, 20).forEach((load, index) => this.addInboundCargo(load, index, false));
  }

  private addInboundCargo(load: LoadVisualDefinition, index: number, animateEntry: boolean): void {
    if (!this.inboundCardboardMaterial || !this.inboundPalletMaterial || !this.inboundStation) return;
    const point = this.stationPoint(this.inboundStation, 2.25 - Math.floor(index / 5) * 0.88, -2.1 + (index % 5) * 0.84);
    const root = new TransformNode(`inboundCargo-${load.id}`, this.scene);
    root.position.set(point.x, 0, point.z);
    root.rotation.y = this.inboundStation.rotationY;
    root.parent = this.warehouseRoot ?? null;
    const base = MeshBuilder.CreateBox(`inboundPallet-${load.id}`, { width: 0.76, height: 0.1, depth: 0.62 }, this.scene);
    base.position.y = 0.08; base.material = this.inboundPalletMaterial; base.parent = root;
    const box = MeshBuilder.CreateBox(`inboundBox-${load.id}`, { width: 0.66, height: 0.58, depth: 0.54 }, this.scene);
    box.position.y = 0.42; box.material = this.inboundCardboardMaterial; box.parent = root;
    const label = MeshBuilder.CreateBox(`inboundLabel-${load.id}`, { width: 0.32, height: 0.012, depth: 0.22 }, this.scene);
    label.position.set(0, 0.715, 0); label.material = this.createMaterial(`inboundLabelMaterial-${load.id}`, "#f3f0dc"); label.parent = root;
    const item = { id: load.id, root, carried: false, meshes: [base, box, label] };
    for (const mesh of item.meshes) this.registerLoadHover(mesh, load.id);
    this.inboundCargoItems.push(item);
    if (animateEntry) this.animateCargoEntry(item);
  }

  private syncInboundCargo(loads: LoadVisualDefinition[]): void {
    const desired = loads.slice(0, 20);
    const desiredIds = new Set(desired.map((load) => load.id));
    const previousIds = this.inboundCargoItems.map((item) => item.id);
    for (const item of [...this.inboundCargoItems]) {
      if (desiredIds.has(item.id)) continue;
      this.inboundCargoItems.splice(this.inboundCargoItems.indexOf(item), 1);
      this.animateCargoExit(item, new Vector3(0.18, 0.18, 0.18));
    }
    desired.forEach((load, index) => {
      if (!this.inboundCargoItems.some((item) => item.id === load.id)) this.addInboundCargo(load, index, true);
    });
    desired.forEach((load, index) => {
      const item = this.inboundCargoItems.find((candidate) => candidate.id === load.id);
      if (!item || !this.inboundStation) return;
      const point = this.stationPoint(this.inboundStation, 2.25 - Math.floor(index / 5) * 0.88, -2.1 + (index % 5) * 0.84);
      item.root.position.x = point.x; item.root.position.z = point.z;
    });
    const currentIds = this.inboundCargoItems.map((item) => item.id);
    if (JSON.stringify(previousIds) !== JSON.stringify(currentIds)) this.telemetry("INBOUND_LOADS_SYNCED", { previousIds, currentIds });
  }

  private createOutboundConveyor(station: StationDefinition, loads: LoadVisualDefinition[], transfers: ConveyorVisualDefinition[]): void {
    this.outboundStation = station;
    const frame = this.createMetalMaterial("conveyorFrame", "#59636c", 72);
    const belt = this.createMaterial("conveyorBelt", "#20262b");
    const safety = this.createMaterial("conveyorSafety", "#e87518");
    const cardboard = this.createCardboardMaterial("outboundCardboard");
    this.conveyorCardboardMaterial = cardboard;
    OUTBOUND_CONVEYOR_LANES.forEach((laneZ, laneIndex) => {
      const deck = MeshBuilder.CreateBox(`outboundConveyor-${laneIndex + 1}`, { width: OUTBOUND_CONVEYOR_LENGTH, height: 0.18, depth: 1.05 }, this.scene);
      const center = this.stationPoint(station, OUTBOUND_CONVEYOR_CENTER, laneZ);
      deck.position.set(center.x, 0.72, center.z); deck.rotation.y = station.rotationY; deck.material = belt; deck.parent = this.warehouseRoot ?? null;
      for (const z of [-0.58, 0.58]) {
        const rail = MeshBuilder.CreateBox(`conveyorRail-${laneIndex + 1}`, { width: OUTBOUND_CONVEYOR_LENGTH, height: 0.3, depth: 0.1 }, this.scene);
        const point = this.stationPoint(station, OUTBOUND_CONVEYOR_CENTER, laneZ + z);
        rail.position.set(point.x, 0.88, point.z); rail.rotation.y = station.rotationY; rail.material = safety; rail.parent = this.warehouseRoot ?? null;
      }
      const rollerSpacing = 0.42;
      const rollerCount = Math.floor(OUTBOUND_CONVEYOR_LENGTH / rollerSpacing);
      for (let index = 0; index <= rollerCount; index += 1) {
        const roller = MeshBuilder.CreateCylinder(`conveyorRoller-${laneIndex + 1}-${index}`, { diameter: 0.16, height: 0.92, tessellation: 18 }, this.scene);
        const point = this.stationPoint(station, OUTBOUND_CONVEYOR_START + index * rollerSpacing, laneZ);
        roller.rotation.x = Math.PI / 2; roller.rotation.y = station.rotationY; roller.position.set(point.x, 0.84, point.z);
        roller.material = frame; roller.parent = this.warehouseRoot ?? null;
      }
      for (const x of [-1.8, 0.8, 3.4, 6, 8]) {
        const leg = MeshBuilder.CreateBox(`conveyorLeg-${laneIndex + 1}`, { width: 0.12, height: 0.7, depth: 0.12 }, this.scene);
        const point = this.stationPoint(station, x, laneZ);
        leg.position.set(point.x, 0.35, point.z); leg.material = frame; leg.parent = this.warehouseRoot ?? null;
      }
      this.createFloorLabel(station, laneIndex === 0 ? "CONV-OUT-01" : "CONV-OUT-02", -0.2, laneZ + 0.05, 2.4, 0.42, "#d9f2e7");
    });
    this.createOutboundDischargePortal(station, frame, safety);
    loads.slice(0, 8).forEach((load, index) => {
      const transfer = transfers.find((candidate) => candidate.loadId === load.id);
      this.addConveyorCargo(load, index, false, transfer?.conveyorId ?? `CONV-OUT-0${index % 2 + 1}`);
    });
  }

  private createOutboundDischargePortal(
    station: StationDefinition,
    frame: StandardMaterialType,
    safety: StandardMaterialType
  ): void {
    const darkConcrete = this.createMaterial("outboundApron", "#777e7c");
    const apron = MeshBuilder.CreateGround("outboundApron", { width: 5.2, height: 4.2 }, this.scene);
    const apronPoint = this.stationPoint(station, 8.8, 0);
    apron.position.set(apronPoint.x, 0.006, apronPoint.z);
    apron.rotation.y = station.rotationY;
    apron.material = darkConcrete;
    apron.parent = this.warehouseRoot ?? null;

    // The portal is centred on the west wall and visibly frames the indoor/outdoor hand-off.
    const portalX = 6.45;
    for (const z of [-0.92, 0.92]) {
      const point = this.stationPoint(station, portalX, z);
      const upright = MeshBuilder.CreateBox("outboundPortalUpright", { width: 0.2, height: 2.8, depth: 0.2 }, this.scene);
      upright.position.set(point.x, 1.4, point.z); upright.rotation.y = station.rotationY;
      upright.material = frame; upright.parent = this.warehouseRoot ?? null;

      const curtain = MeshBuilder.CreateBox("outboundSafetyCurtain", { width: 0.08, height: 1.8, depth: 0.08 }, this.scene);
      curtain.position.set(point.x, 1.35, point.z); curtain.rotation.y = station.rotationY;
      curtain.material = safety; curtain.parent = this.warehouseRoot ?? null;
    }
    const headerPoint = this.stationPoint(station, portalX, 0);
    const header = MeshBuilder.CreateBox("outboundPortalHeader", { width: 0.2, height: 0.22, depth: 2.05 }, this.scene);
    header.position.set(headerPoint.x, 2.7, headerPoint.z); header.rotation.y = station.rotationY;
    header.material = frame; header.parent = this.warehouseRoot ?? null;

    for (const z of [-1.18, 1.18]) {
      const point = this.stationPoint(station, 5.75, z);
      const bollard = MeshBuilder.CreateCylinder("outboundBollard", { diameter: 0.24, height: 0.95, tessellation: 16 }, this.scene);
      bollard.position.set(point.x, 0.475, point.z); bollard.material = safety; bollard.parent = this.warehouseRoot ?? null;
    }

    const texture = new DynamicTexture("outboundDirectionTexture", { width: 512, height: 128 }, this.scene, true);
    texture.hasAlpha = true;
    texture.drawText("OUTBOUND  →", null, 88, "bold 48px Arial", "#f4f4ee", "transparent", true, true);
    const directionMaterial = new StandardMaterial("outboundDirectionMaterial", this.scene);
    directionMaterial.diffuseTexture = texture;
    directionMaterial.emissiveColor = new Color3(0.2, 0.2, 0.2);
    directionMaterial.useAlphaFromDiffuseTexture = true;
    const direction = MeshBuilder.CreatePlane("outboundDirection", { width: 3.8, height: 0.9 }, this.scene);
    const directionPoint = this.stationPoint(station, 1.2, 1.45);
    direction.position.set(directionPoint.x, 0.026, directionPoint.z);
    direction.rotation.x = Math.PI / 2;
    direction.rotation.y = station.rotationY + Math.PI;
    direction.material = directionMaterial;
    direction.parent = this.warehouseRoot ?? null;
  }

  private createRobotCell(station: StationDefinition, cells: RobotCellVisualDefinition[]): void {
    const root = new TransformNode("robotCell-ROBOT-01", this.scene);
    const anchor = this.stationPoint(station, -5, 0);
    root.position.set(anchor.x, 0, anchor.z);
    root.rotation.y = station.rotationY;
    root.parent = this.warehouseRoot ?? null;
    this.robotCellRoot = root;
    const baseMaterial = this.createMetalMaterial("robotCellBase", "#4b5961", 84);
    const armMaterial = this.createMaterial("robotCellArm", "#f3a712");
    const safetyMaterial = this.createMaterial("robotCellSafety", "#ef7d19");
    const cageMaterial = this.createMaterial("robotCellFence", "#8a9a9a");

    const base = MeshBuilder.CreateCylinder("robotCellBase", { diameter: 1.2, height: .28, tessellation: 32 }, this.scene);
    base.position.y = .15; base.material = baseMaterial; base.parent = root;
    const shoulder = new TransformNode("robotCellShoulder", this.scene); shoulder.position.set(0, .65, 0); shoulder.parent = root;
    const upper = MeshBuilder.CreateBox("robotArmUpper", { width: .38, height: 1.55, depth: .32 }, this.scene);
    upper.position.set(0, .75, 0); upper.rotation.z = -.42; upper.material = armMaterial; upper.parent = shoulder;
    const elbow = new TransformNode("robotCellElbow", this.scene); elbow.position.set(.32, 1.35, 0); elbow.parent = shoulder;
    const forearm = MeshBuilder.CreateBox("robotArmForearm", { width: .3, height: 1.35, depth: .28 }, this.scene);
    forearm.position.set(.25, .65, 0); forearm.rotation.z = .66; forearm.material = armMaterial; forearm.parent = elbow;
    const wrist = new TransformNode("robotCellWrist", this.scene); wrist.position.set(.7, 1.12, 0); wrist.parent = elbow;
    const wristJoint = MeshBuilder.CreateCylinder("robotArmWrist", { diameter: .28, height: .45, tessellation: 20 }, this.scene);
    wristJoint.rotation.z = Math.PI / 2; wristJoint.material = baseMaterial; wristJoint.parent = wrist;
    const gripper = MeshBuilder.CreateBox("robotGripper", { width: .48, height: .12, depth: .3 }, this.scene);
    gripper.position.set(.35, 0, 0); gripper.material = safetyMaterial; gripper.parent = wrist;
    this.robotArm = shoulder; this.robotGripper = wrist;

    // Guarding surrounds the arm but leaves the east-side AGV presentation gate open.
    for (const [x, z, width, depth] of [[0, -2.9, 7.4, .12], [0, 2.9, 7.4, .12], [-3.7, 0, .12, 5.8]] as number[][]) {
      const fence = MeshBuilder.CreateBox("robotCellFence", { width, height: 1.4, depth }, this.scene);
      fence.position.set(x, .7, z); fence.material = cageMaterial; fence.parent = root;
      fence.visibility = .7;
    }
    for (const x of [-3.7, 3.7]) for (const z of [-2.9, 2.9]) {
      const post = MeshBuilder.CreateCylinder("robotCellPost", { diameter: .12, height: 1.6, tessellation: 12 }, this.scene);
      post.position.set(x, .8, z); post.material = safetyMaterial; post.parent = root;
    }
    const handoff = MeshBuilder.CreateBox("robotHandoffPad", { width: 2.2, height: .025, depth: 2.1 }, this.scene);
    handoff.position.set(2.0, .02, 0); handoff.material = safetyMaterial; handoff.parent = root;
    this.createFloorLabel(station, "ROBOT-01", -5, -3.45, 2.5, .55, "#fff1bf");
    this.robotCellPhase = cells.find((cell) => cell.id === "ROBOT-01")?.phase ?? "IDLE";
    this.syncRobotCell(cells);
  }

  private syncRobotCell(cells: RobotCellVisualDefinition[]): void {
    const phase = cells.find((cell) => cell.id === "ROBOT-01")?.phase ?? "IDLE";
    if (!this.robotArm || !this.robotGripper || phase === this.robotCellPhase) return;
    this.robotCellPhase = phase;
    const active = ["AT_HANDOFF", "PICKING", "PLACING"].includes(phase);
    this.robotArm.rotation.y = active ? .35 : 0;
    this.robotArm.rotation.z = phase === "PICKING" ? -.15 : phase === "PLACING" ? .35 : -.42;
    this.robotGripper.rotation.z = phase === "PLACING" ? -.28 : 0;
    this.telemetry("ROBOT_PHASE", { robotId: "ROBOT-01", phase });
  }

  private createCompanionAgvs(agvs: ApiAgv[]): void {
    const palette = ["#d97b21", "#6d8ee6", "#56b58b"];
    agvs.filter((agv) => agv.id !== "FL-01").forEach((agv, index) => {
      const root = new TransformNode(`companion-${agv.id}`, this.scene);
      root.position.set(agv.x, 0, agv.z); root.rotation.y = -agv.theta - Math.PI / 2; root.parent = this.warehouseRoot ?? null;
      const body = MeshBuilder.CreateBox(`companionBody-${agv.id}`, { width: 1.05, height: .5, depth: 1.3 }, this.scene);
      body.position.y = .52; body.material = this.createMaterial(`companionMaterial-${agv.id}`, palette[index % palette.length]); body.parent = root;
      const mast = MeshBuilder.CreateBox(`companionMast-${agv.id}`, { width: .62, height: 1.9, depth: .12 }, this.scene);
      mast.position.set(0, 1.35, -.58); mast.material = this.createMetalMaterial(`companionSteel-${agv.id}`, "#657382", 64); mast.parent = root;
      const beacon = MeshBuilder.CreateCylinder(`companionBeacon-${agv.id}`, { diameter: .16, height: .15, tessellation: 16 }, this.scene);
      beacon.position.set(0, 1.35, .4); beacon.material = this.createMaterial(`companionBeaconMaterial-${agv.id}`, "#63e6be"); beacon.parent = root;
      this.companionAgvs.set(agv.id, root);
    });
  }

  private syncCompanionAgvs(agvs: ApiAgv[]): void {
    for (const agv of agvs) {
      const root = this.companionAgvs.get(agv.id);
      if (!root || agv.id === "FL-01") continue;
      root.position.x = agv.x; root.position.z = agv.z; root.rotation.y = -agv.theta - Math.PI / 2;
    }
  }

  private createFloorLabel(station: StationDefinition, text: string, localX: number, localZ: number, width: number, height: number, color: string): void {
    const texture = new DynamicTexture(`floorLabel-${text}`, { width: 512, height: 128 }, this.scene, true);
    texture.hasAlpha = true;
    texture.drawText(text, null, 84, "bold 42px Arial", color, "transparent", true, true);
    const material = new StandardMaterial(`floorLabelMaterial-${text}`, this.scene);
    material.diffuseTexture = texture; material.emissiveColor = new Color3(.14, .14, .14); material.useAlphaFromDiffuseTexture = true;
    const label = MeshBuilder.CreatePlane(`floorLabel-${text}`, { width, height }, this.scene);
    const point = this.stationPoint(station, localX, localZ);
    label.position.set(point.x, .027, point.z); label.rotation.x = Math.PI / 2; label.rotation.y = station.rotationY + Math.PI;
    label.material = material; label.parent = this.warehouseRoot ?? null;
  }

  private addConveyorCargo(load: LoadVisualDefinition, index: number, animateEntry: boolean, conveyorId = "CONV-OUT-01"): void {
    if (!this.conveyorCardboardMaterial || !this.outboundStation) return;
    const root = new TransformNode(`shippingCargo-${index}`, this.scene);
    const laneIndex = conveyorId.endsWith("02") ? 1 : 0;
    const laneZ = OUTBOUND_CONVEYOR_LANES[laneIndex];
    const start = this.stationPoint(this.outboundStation, -1.75 - index * 0.15, laneZ);
    root.position.set(start.x, 1.2, start.z);
    root.rotation.y = this.outboundStation.rotationY;
    root.parent = this.warehouseRoot ?? null;
    const box = MeshBuilder.CreateBox(`shippingBox-${index}`, { width: 0.62, height: 0.56, depth: 0.62 }, this.scene);
    box.material = this.conveyorCardboardMaterial;
    box.parent = root;
    const item = { id: load.id, root, carried: false, meshes: [box] };
    this.registerLoadHover(box, load.id);
    this.conveyorCargoItems.push(item);
    if (animateEntry) this.animateCargoEntry(item);
    const end = this.stationPoint(this.outboundStation, 5.6 - index * 0.55, laneZ);
    const travel = new Animation(`conveyorTravel-${index}`, "position", 30, Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
    travel.setKeys([{ frame: 0, value: root.position.clone() }, { frame: 180, value: new Vector3(end.x, 1.2, end.z) }]);
    this.scene.beginDirectAnimation(root, [travel], 0, 180, false);
  }

  private syncConveyorCargo(loads: LoadVisualDefinition[]): void {
    const desired = loads.slice(0, 4);
    const desiredIds = new Set(desired.map((load) => load.id));
    if (desired.length !== this.conveyorCargoItems.length) this.telemetry("CONVEYOR_COUNT_CHANGED", { from: this.conveyorCargoItems.length, to: desired.length });
    for (const item of [...this.conveyorCargoItems]) {
      if (desiredIds.has(item.id)) continue;
      this.conveyorCargoItems.splice(this.conveyorCargoItems.indexOf(item), 1);
      const exit = new Animation("conveyorCargoExit", "position", 60, Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
      const end = this.outboundStation ? this.stationPoint(this.outboundStation, 9.6, 0) : { x: item.root.position.x, z: item.root.position.z };
      exit.setKeys([{ frame: 0, value: item.root.position.clone() }, { frame: 30, value: new Vector3(end.x, item.root.position.y, end.z) }]);
      const scale = new Animation("conveyorCargoExitScale", "scaling", 60, Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
      scale.setKeys([{ frame: 0, value: item.root.scaling.clone() }, { frame: 30, value: new Vector3(0.4, 0.4, 0.4) }]);
      this.scene.beginDirectAnimation(item.root, [exit, scale], 0, 30, false, 1, () => item.root.dispose(false, true));
    }
    desired.forEach((load, index) => {
      if (!this.conveyorCargoItems.some((item) => item.id === load.id)) this.addConveyorCargo(load, index, true, `CONV-OUT-0${index % 2 + 1}`);
    });
  }

  private syncRackCargo(racks: RackDefinition[]): void {
    const desired = new Map<string, { rack: RackDefinition; bay: number; level: number }>();
    for (const rack of racks) {
      if (rack.loads) for (const load of rack.loads) desired.set(load.id, { rack, bay: load.bay, level: load.level });
      else for (let bay = 0; bay < rack.bays; bay += 1) for (let level = 0; level < 3; level += 1)
        if (!rack.emptySlots?.some(([emptyBay, emptyLevel]) => emptyBay === bay && emptyLevel === level))
          desired.set(`${rack.id}-${bay + 1}-${level + 1}`, { rack, bay, level });
    }
    for (const rack of racks) {
      const parts = this.rackParts.get(rack.id);
      if (!parts) continue;
      for (const [id, target] of desired) if (target.rack.id === rack.id && !this.cargoItems.some((item) => item.id === id))
        this.createCargo(rack, parts.width, target.bay, target.level, parts.cardboardMaterial, parts.palletMaterial, parts.meshes, true, id);
    }
    for (const item of [...this.cargoItems]) {
      if (desired.has(item.id) || item.carried) continue;
      this.cargoItems.splice(this.cargoItems.indexOf(item), 1);
      this.animateCargoExit(item, new Vector3(0.12, 0.12, 0.12));
    }
  }

  private animateCargoEntry(item: CargoItem): void {
    item.root.scaling.set(0.16, 0.16, 0.16);
    const animation = new Animation(`cargoEntry-${item.id}`, "scaling", 60, Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
    animation.setKeys([{ frame: 0, value: item.root.scaling.clone() }, { frame: 28, value: new Vector3(1, 1, 1) }]);
    const easing = new CubicEase();
    easing.setEasingMode(EasingFunction.EASINGMODE_EASEOUT);
    animation.setEasingFunction(easing);
    this.scene.beginDirectAnimation(item.root, [animation], 0, 28, false);
  }

  private animateCargoExit(item: CargoItem, finalScale: Vector3Type): void {
    const animation = new Animation(`cargoExit-${item.id}`, "scaling", 60, Animation.ANIMATIONTYPE_VECTOR3, Animation.ANIMATIONLOOPMODE_CONSTANT);
    animation.setKeys([{ frame: 0, value: item.root.scaling.clone() }, { frame: 24, value: finalScale }]);
    const easing = new CubicEase();
    easing.setEasingMode(EasingFunction.EASINGMODE_EASEIN);
    animation.setEasingFunction(easing);
    this.scene.beginDirectAnimation(item.root, [animation], 0, 24, false, 1, () => item.root.dispose(false, true));
  }

  private createConcreteFloorMaterial(baseColor: string): StandardMaterialType {
    const texture = new DynamicTexture("concreteFloorTexture", { width: 1536, height: 1152 }, this.scene, true);
    const context = texture.getContext() as CanvasRenderingContext2D;
    context.fillStyle = baseColor;
    context.fillRect(0, 0, 1536, 1152);
    context.fillStyle = "rgba(72, 78, 77, 0.16)";
    context.fillRect(0, 0, 1536, 1152);

    // Deterministic aggregate and wear marks keep the floor natural without external image assets.
    for (let i = 0; i < 2200; i += 1) {
      const x = (i * 73) % 1536;
      const y = (i * 151 + (i % 17) * 29) % 1152;
      const shade = 90 + (i * 37) % 90;
      context.fillStyle = `rgba(${shade}, ${shade + 3}, ${shade + 1}, ${0.025 + (i % 4) * 0.01})`;
      context.fillRect(x, y, 1 + (i % 3), 1 + ((i + 1) % 3));
    }

    context.lineWidth = 3;
    context.strokeStyle = "rgba(48, 55, 54, 0.34)";
    for (let x = 0; x <= 1536; x += 128) {
      context.beginPath();
      context.moveTo(x, 0);
      context.lineTo(x, 1152);
      context.stroke();
    }
    for (let y = 0; y <= 1152; y += 128) {
      context.beginPath();
      context.moveTo(0, y);
      context.lineTo(1536, y);
      context.stroke();
    }
    context.lineWidth = 1;
    context.strokeStyle = "rgba(255, 255, 255, 0.2)";
    for (let x = 2; x <= 1536; x += 128) {
      context.beginPath();
      context.moveTo(x, 0);
      context.lineTo(x, 1152);
      context.stroke();
    }
    for (let y = 2; y <= 1152; y += 128) {
      context.beginPath();
      context.moveTo(0, y);
      context.lineTo(1536, y);
      context.stroke();
    }
    texture.update(false);
    texture.anisotropicFilteringLevel = 8;

    const material = this.createMaterial("floor", "#b7bcba");
    material.diffuseTexture = texture;
    material.specularColor = new Color3(0.08, 0.08, 0.075);
    material.specularPower = 22;
    material.roughness = 0.9;
    return material;
  }

  private createFloorMarkings(inbound: StationDefinition, outbound: StationDefinition): void {
    const yellow = this.createMaterial("safetyLineYellow", "#e5b516");
    yellow.emissiveColor = new Color3(0.07, 0.05, 0.005);
    const white = this.createMaterial("pedestrianMarking", "#e7e7df");

    const addMark = (name: string, x: number, z: number, width: number, depth: number, material: StandardMaterialType): void => {
      const mark = MeshBuilder.CreateBox(name, { width, height: 0.018, depth }, this.scene);
      mark.position.set(x, 0.012, z);
      mark.material = material;
      mark.parent = this.warehouseRoot ?? null;
    };

    // Three forklift service aisles and protected east/west cross-aisles.
    for (const z of [-6, 2, 10]) {
      addMark(`aisle-${z}-north`, -5, z - 0.95, 26, 0.08, yellow);
      addMark(`aisle-${z}-south`, -5, z + 0.95, 26, 0.08, yellow);
    }
    addMark("westCrossAisle", -18.95, 2, 0.08, 18, yellow);
    addMark("eastCrossAisle", 8.95, 2, 0.08, 18, yellow);

    // Pedestrian crossings at both operational zones.
    for (let i = 0; i < 7; i += 1) {
      const inPoint = this.stationPoint(inbound, -1.5 + i * 0.5, 1.75);
      addMark(`receiving-crosswalk-${i}`, inPoint.x, inPoint.z, 0.25, 1.2, white);
      const outPoint = this.stationPoint(outbound, -1.5 + i * 0.5, 1.75);
      addMark(`shipping-crosswalk-${i}`, outPoint.x, outPoint.z, 0.25, 1.2, white);
    }

    // Longitudinal exclusion lines reinforce the westbound conveyor flow while leaving the AGV loading end open.
    for (const localZ of [-1.12, 1.12]) {
      const point = this.stationPoint(outbound, 2.15, localZ);
      const mark = MeshBuilder.CreateBox("shipping-conveyor-clearance", { width: 8.7, height: 0.018, depth: 0.08 }, this.scene);
      mark.position.set(point.x, 0.014, point.z);
      mark.rotation.y = outbound.rotationY;
      mark.material = yellow;
      mark.parent = this.warehouseRoot ?? null;
    }
    for (let index = 0; index < 9; index += 1) {
      const point = this.stationPoint(outbound, -1.45 + index * 0.95, -1.12);
      const hatch = MeshBuilder.CreateBox(`shipping-clearance-hatch-${index}`, { width: 0.08, height: 0.019, depth: 0.46 }, this.scene);
      hatch.position.set(point.x, 0.015, point.z);
      hatch.rotation.y = outbound.rotationY + 0.62;
      hatch.material = yellow;
      hatch.parent = this.warehouseRoot ?? null;
    }
  }

  private createMetalMaterial(name: string, hex: string, specularPower: number): StandardMaterialType {
    const material = this.createMaterial(name, hex);
    material.specularColor = new Color3(0.32, 0.34, 0.35);
    material.specularPower = specularPower;
    material.roughness = 0.58;
    return material;
  }

  private createCardboardMaterial(name: string): StandardMaterialType {
    const texture = new DynamicTexture(`${name}-texture`, { width: 512, height: 512 }, this.scene, true);
    const context = texture.getContext() as CanvasRenderingContext2D;
    context.fillStyle = "#a97943";
    context.fillRect(0, 0, 512, 512);
    for (let i = 0; i < 420; i += 1) {
      const x = (i * 47) % 512;
      const y = (i * 83) % 512;
      context.fillStyle = i % 3 === 0 ? "rgba(65, 38, 17, .11)" : "rgba(255, 235, 192, .08)";
      context.fillRect(x, y, 1 + (i % 2), 1);
    }
    context.fillStyle = "rgba(218, 190, 139, .62)";
    context.fillRect(226, 0, 60, 512);
    context.fillStyle = "rgba(69, 43, 24, .52)";
    context.fillRect(30, 340, 150, 5);
    context.fillRect(30, 357, 105, 4);
    texture.update(false);
    texture.anisotropicFilteringLevel = 8;
    const material = this.createMaterial(name, "#a97943");
    material.diffuseTexture = texture;
    material.specularColor = new Color3(0.025, 0.02, 0.015);
    material.roughness = 0.94;
    return material;
  }

  private createWoodMaterial(name: string): StandardMaterialType {
    const texture = new DynamicTexture(`${name}-texture`, { width: 512, height: 256 }, this.scene, true);
    const context = texture.getContext() as CanvasRenderingContext2D;
    context.fillStyle = "#8a5a32";
    context.fillRect(0, 0, 512, 256);
    for (let y = 12; y < 256; y += 18) {
      context.strokeStyle = y % 36 === 12 ? "rgba(54, 29, 12, .28)" : "rgba(224, 168, 102, .18)";
      context.lineWidth = 2;
      context.beginPath();
      context.moveTo(0, y);
      context.bezierCurveTo(150, y - 5, 350, y + 6, 512, y - 2);
      context.stroke();
    }
    texture.update(false);
    texture.anisotropicFilteringLevel = 8;
    const material = this.createMaterial(name, "#8a5a32");
    material.diffuseTexture = texture;
    material.specularColor = new Color3(0.04, 0.025, 0.015);
    material.roughness = 0.88;
    return material;
  }

  private createMaterial(name: string, hex: string): StandardMaterialType {
    const material = new StandardMaterial(name, this.scene);
    material.diffuseColor = Color3.FromHexString(hex);
    material.specularColor = new Color3(0.12, 0.12, 0.12);
    return material;
  }

  private registerLoadHover(mesh: Mesh, loadId: string): void {
    mesh.isPickable = true;
    mesh.actionManager ??= new ActionManager(this.scene);
    mesh.actionManager.registerAction(new ExecuteCodeAction(ActionManager.OnPointerOverTrigger, () => {
      this.canvas.style.cursor = "help";
      this.onLoadHover(loadId);
    }));
    mesh.actionManager.registerAction(new ExecuteCodeAction(ActionManager.OnPointerOutTrigger, () => {
      this.canvas.style.cursor = "";
      this.onLoadHover();
    }));
  }

  private formatPosition(position: Vector3Type): string {
    return `[${position.x.toFixed(1)}, ${position.y.toFixed(1)}, ${position.z.toFixed(1)}]`;
  }

  private log(message: string): void {
    (window as Window & { __warehouseDiagnostics?: { info: (stage: string, value: unknown) => void } })
      .__warehouseDiagnostics?.info("forklift", message);
  }

  private telemetry(event: string, payload: Record<string, unknown>): void {
    (window as Window & { __warehouseRecordAnimation?: (event: string, payload: Record<string, unknown>) => void })
      .__warehouseRecordAnimation?.(event, payload);
  }

  private disposeWarehouse(): void {
    this.onLoadHover();
    this.canvas.style.cursor = "";
    if (this.forklift) {
      this.scene.stopAnimation(this.forklift);
    }
    if (this.forkliftLift) {
      this.scene.stopAnimation(this.forkliftLift);
    }
    this.rackParts.clear();
    this.rackColliders.length = 0;
    this.cargoItems.length = 0;
    this.inboundCargoItems.length = 0;
    this.conveyorCargoItems.length = 0;
    this.poseBuffer.length = 0;
    this.wheelMeshes.length = 0;
    this.chargingIndicators.clear();
    this.companionAgvs.clear();
    this.robotCellRoot = undefined;
    this.robotArm = undefined;
    this.robotGripper = undefined;
    this.robotCellPhase = "IDLE";
    this.lastRenderedPosition = undefined;
    this.carriedCargo = undefined;
    this.inboundCardboardMaterial = undefined;
    this.inboundPalletMaterial = undefined;
    this.conveyorCardboardMaterial = undefined;
    this.collisionBlocked = false;
    this.selectedRackId = undefined;
    this.forklift = undefined;
    this.forkliftLift = undefined;
    this.forkliftForkAssembly = undefined;
    this.forkliftStops = undefined;
    this.highlightMaterial = undefined;
    this.liveMotionBlocked = false;
    this.warehouseRoot?.dispose(false, true);
    this.warehouseRoot = undefined;
  }
}
