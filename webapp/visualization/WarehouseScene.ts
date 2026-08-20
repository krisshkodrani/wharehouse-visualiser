import Babylon from "../vendor/BabylonRuntime";
import type { ArcRotateCamera as ArcRotateCameraType } from "@babylonjs/core/Cameras/arcRotateCamera";
import type { Engine as EngineType } from "@babylonjs/core/Engines/engine";
import type { StandardMaterial as StandardMaterialType } from "@babylonjs/core/Materials/standardMaterial";
import type { Vector3 as Vector3Type } from "@babylonjs/core/Maths/math.vector";
import type { Mesh } from "@babylonjs/core/Meshes/mesh";
import type { TransformNode as TransformNodeType } from "@babylonjs/core/Meshes/transformNode";
import type { Scene as SceneType } from "@babylonjs/core/scene";
import type { AisleDefinition, ApiAgv, CartonVisualDefinition, ConveyorVisualDefinition, LoadVisualDefinition, ObstacleDefinition, RackDefinition, RobotCellVisualDefinition, StationDefinition, WarehouseVisualConfig } from "../model/types";
import { maximumReach, requiredReach, solveArmPose } from "./armKinematics";
import type { ArmGeometry, ArmPose } from "./armKinematics";
import MaterialFactory from "./factories/MaterialFactory";
import CargoFactory from "./factories/CargoFactory";
import PoseInterpolator from "./telemetry/PoseInterpolator";
import ForkInterpolator from "./telemetry/ForkInterpolator";
import ChargingStationVisual from "./entities/ChargingStationVisual";
import ConveyorVisual from "./entities/ConveyorVisual";
import CargoHandoverAnimator from "./animation/CargoHandoverAnimator";
import ForkliftAnimator from "./animation/ForkliftAnimator";
import PalletVisual from "./entities/PalletVisual";
import RobotArmAnimator from "./animation/RobotArmAnimator";
import ConveyorAnimator from "./animation/ConveyorAnimator";
import ForkliftVisual from "./entities/ForkliftVisual";
import RackVisual from "./entities/RackVisual";
import RobotCellVisual from "./entities/RobotCellVisual";
import WarehouseStructureVisual from "./entities/WarehouseStructureVisual";

const {
  ActionManager,
  ExecuteCodeAction,
  ArcRotateCamera,
  Engine,
  HemisphericLight,
  DirectionalLight,
  Color3,
  DynamicTexture,
  StandardMaterial,
  Vector3,
  MeshBuilder,
  TransformNode,
  Scene
} = Babylon;

interface RackCollider {
  id: string;
  x: number;
  z: number;
  halfWidth: number;
  halfDepth: number;
  rotationY: number;
}

type CargoItem = PalletVisual;

interface PoseSample { receivedAt: number; x: number; z: number; theta: number; velocity: number; }

/** Fork height and reach, buffered and interpolated exactly like {@link PoseSample}.
 *
 * <p>These used to be assigned straight onto the transform the moment telemetry landed,
 * while the chassis went through the pose buffer -- so the vehicle glided and the mast it
 * carries snapped. The load is a child of the fork assembly, so the mismatch was visible
 * as the pallet leading or lagging the truck under it, and every coalesced sample became
 * a jump instead of degrading gracefully. Kept in its own buffer rather than folded into
 * PoseSample because handling telemetry never reaches {@link WarehouseScene.setAgvState}
 * at all, and because a lift happens while the vehicle is stationary -- the pose buffer's
 * "only if x/z/theta moved" test would drop every fork sample that matters. */
interface ForkSample { receivedAt: number; height: number; extension: number; }

// Double both dimensions to provide four times the original floor area.
// West-wall conveyor penetration (V23). The opening spans both lane envelopes
// (z 12.7..14.1 and 14.7..16.1) with ~0.3 m of reveal each side; WALL-W stops at
// SHIPPING_OPENING_MIN_Z and the WALL-W-OUT-N return starts at SHIPPING_OPENING_MAX_Z.
const SHIPPING_WALL_X = -23.45;
const SHIPPING_OPENING_MIN_Z = 12.4;
const SHIPPING_OPENING_MAX_Z = 16.4;
const WALL_HEIGHT = 1.1;
// The structural opening is 4 m wide but the two belt envelopes only account for
// 2.4 m of it, so 1.6 m was simply missing wall -- 0.4 m of reveal at each end and a
// 0.8 m hole straight through the building between the lanes. The lanes are data
// (CONV-OUT-01/02 sit at z 13.4 and 15.4, 1.4 m deep), so the infill is derived from
// them rather than hard-coded, and stays correct if a lane ever moves.
const SHIPPING_LANE_ENVELOPES: ReadonlyArray<readonly [number, number]> = [[12.7, 14.1], [14.7, 16.1]];
// Cargo rides the rollers at y 0.92 and stands 0.56 m tall, so it needs 1.48 m of
// clear height; the header used to sit at 1.10 and every carton passed bodily through
// it. The wall is only a 1.1 m parapet, so the frame is lifted into a portal that
// projects above the wall line -- which is what a real dock opening does -- rather
// than raising the whole building.
const SHIPPING_PORTAL_HEIGHT = 1.6;
// Exterior slab carrying the belt overhang and the relocated trailer bay.
// Aisle lettering. Inset from each end so the text sits inside the lane rather than
// under the cross-aisle it joins.
// How long a cargo visual waits, still on screen, between losing one owner and
// being claimed by the next. Handovers are driven by two independent streams --
// the snapshot decides what belongs on a shelf, telemetry decides what the fork
// holds -- and either can arrive first, so the box is parked rather than
// destroyed until one of them claims it.
/** Where a staged inbound pallet sits, in the receiving station's local frame.
 *
 * <p>The grid used to start at local x 2.25 and step back 0.88 a row, which put two of its
 * four rows underneath the vehicle: the AGV serves INBOUND-01 from local x +1.5, and its
 * envelope is 3.19 m long, reaching from x 0.48 back to x 3.67. Rows at 2.25 and 1.37 sat
 * squarely inside that, so the forklift drove through the pallets it had come to collect.
 *
 * <p>The grid now begins clear of the vehicle's front face and steps back at pallet pitch,
 * which keeps all four rows inside the 6 m station and holds the same 20-pallet capacity. */
const INBOUND_ROW_FRONT_X = -.15;
const INBOUND_ROW_PITCH = .7;
const INBOUND_COLUMN_FIRST_Z = -2.1;
const INBOUND_COLUMN_PITCH = .84;
const INBOUND_COLUMNS = 5;

const CARGO_HANDOVER_GRACE_MS = 30000;
// Statuses whose visual belongs to something other than a shelf, staging or the
// fork, so a pallet parked mid-handover in one of these is genuinely gone rather
// than in limbo.
const CARGO_STATUSES_ELSEWHERE = new Set(["SHIPPED", "ON_CONVEYOR"]);
const CARGO_PLACEMENT_FRAMES = 21;
const AISLE_LABEL_INSET = 3.2;
const AISLE_LABEL_WIDTH = 6.4;
const AISLE_LABEL_HEIGHT = 1.6;
// Dark on a light floor. The first attempt used the mint accent the conveyor labels
// use, which is legible on a dark deck and all but invisible on #dce8e5 concrete --
// the text rendered correctly and simply could not be read.
const AISLE_LABEL_COLOR = "#123f33";
const FLOOR_HALF_WIDTH = 24;
const FLOOR_HALF_DEPTH = 18;
/** Collision radius used to detect the live vehicle overlapping rack geometry. */
const FORKLIFT_RADIUS = 0.72;
const MAX_FORK_HEIGHT = 2.75;
/** Reach travel. Was an unnamed .7 inline in setAgvOperations. */
const MAX_FORK_EXTENSION = .7;
/** Both telemetry buffers render this far behind wall-clock, so the mast and the chassis
 * -- and therefore the pallet hanging off the mast -- are read at the same instant. */
const RENDER_DELAY_MS = 150;
/** Fork samples further apart than this are a new stream, not a slow move: snap. The
 * simulator only publishes handling while a moveFork is running, so the quiet between
 * two lifts is seconds long and interpolating across it would make the next lift creep. */
const FORK_STREAM_GAP_MS = 400;
/** updateForkFrame only ever reads the first two entries; the rest is slack. */
const FORK_BUFFER_LIMIT = 8;
/** WASD pans the camera. Fractions of the orbit radius travelled per second, so the pan
 * feels the same when zoomed into an aisle as when viewing the whole facility: roughly
 * 2.8 m/s fully zoomed in and 18 m/s fully out. Tuned down from 0.55, which crossed the
 * whole 48 m floor in under three seconds and was impossible to aim while presenting. */
const CAMERA_PAN_RATE = 0.28;
const CAMERA_PAN_KEYS = ["KeyW", "KeyA", "KeyS", "KeyD"];
/** Shared by the initial build and the incremental sync. These used to disagree
 * (8 vs 4), so cartons appeared on first build and vanished on the next sync. */
const CONVEYOR_CARGO_LIMIT = 8;

// Robot arm link geometry. Reach from the shoulder is UPPER + FOREARM = 2.90 m.
// The arm has to serve two points 5.1 m apart: the handoff pad at cell-local +0.5
// and the conveyor infeed CONVEYOR_INFEED_INSET in from each lane's upstream end.
// With the V21 layout those are 2.60 m and 2.82 m from the pedestal. The margin is
// thin, so changing any length, the pedestal offset, the pad offset, the inset, or
// the lane geometry in V21 needs armKinematics.qunit.ts re-checked: an unreachable
// target does not fail loudly, the IK clamps and the gripper quietly stops short.
const ARM_UPPER_LENGTH = 1.55;
const ARM_FOREARM_LENGTH = 1.35;
const ARM_GRIPPER_LENGTH = .3;
const ARM_SHOULDER_HEIGHT = .95;
/** Pedestal and handoff pad, in cell-local coordinates. */
const ARM_PEDESTAL_LOCAL_X = -2.0;
const ROBOT_HANDOFF_LOCAL_X = .5;
/** How far in from a lane's upstream end cargo is placed and picked up. Shared by
 * the arm's placing target and addConveyorCargo so the gripper releases exactly
 * where the carton appears. */
const CONVEYOR_INFEED_INSET = .7;

/** Reused every frame by the camera pan; allocating these per frame would churn the GC. */
const FORWARD_AXIS = new Vector3(0, 0, 1);
const RIGHT_AXIS = new Vector3(1, 0, 0);

const ARM_GEOMETRY: ArmGeometry = {
  upperLength: ARM_UPPER_LENGTH,
  forearmLength: ARM_FOREARM_LENGTH,
  gripperLength: ARM_GRIPPER_LENGTH,
  shoulderHeight: ARM_SHOULDER_HEIGHT,
  pedestalLocalX: ARM_PEDESTAL_LOCAL_X
};

export default class WarehouseScene {
  private readonly engine: EngineType;
  private readonly scene: SceneType;
  private readonly camera: ArcRotateCameraType;
  private readonly materials: MaterialFactory;
  private readonly cargoAnimator: CargoHandoverAnimator;
  private readonly conveyorAnimator: ConveyorAnimator;
  private warehouseRoot?: TransformNodeType;
  private forklift?: TransformNodeType;
  private forkliftLift?: TransformNodeType;
  private readonly poseInterpolator = new PoseInterpolator();
  private readonly forkInterpolator = new ForkInterpolator({
    maximumHeight: MAX_FORK_HEIGHT,
    maximumExtension: MAX_FORK_EXTENSION,
    streamGapMs: FORK_STREAM_GAP_MS,
    bufferLimit: FORK_BUFFER_LIMIT
  });
  /** Loads whose slot rebuild is currently suppressed because the fork holds them,
   * keyed `${source}:${loadId}`. Only used to keep the telemetry to one event per
   * handover: syncRackCargo runs on every snapshot, and the animation ring holds 500
   * entries that cargo-handover.spec.ts reads its CARGO_ORPHANED pairs out of. */
  private readonly suppressedRebuilds = new Set<string>();
  private forkliftAnimator?: ForkliftAnimator;
  private forkliftVisual?: ForkliftVisual;
  private readonly wheelMeshes: Mesh[] = [];
  private forkliftForkAssembly?: TransformNodeType;
  private chargingStations?: ChargingStationVisual;
  private readonly pressedKeys = new Set<string>();
  private readonly rackColliders: RackCollider[] = [];
  private readonly cargoItems: CargoItem[] = [];
  private readonly inboundCargoItems: CargoItem[] = [];
  /** Cargo mid-handover: detached from its previous owner, still on screen, waiting
   * to be claimed. Keyed by load id. */
  private readonly pendingCargo = new Map<string, { item: CargoItem; orphanedAt: number }>();
  private cargoCardboardMaterial?: StandardMaterialType;
  private cargoPalletMaterial?: StandardMaterialType;
  private readonly conveyorCargoItems: CargoItem[] = [];
  private robotCellRoot?: TransformNodeType;
  private robotCellStation?: StationDefinition;
  private armYaw?: TransformNodeType;
  private armShoulder?: TransformNodeType;
  private armElbow?: TransformNodeType;
  private armWrist?: TransformNodeType;
  private robotArmAnimator?: RobotArmAnimator;
  private handoffPallet?: TransformNodeType;
  private handoffCartonIds: string[] = [];
  private grippedCarton?: Mesh;
  private grippedCartonId?: string;
  private carriedCargo?: CargoItem;
  private inboundCardboardMaterial?: StandardMaterialType;
  private inboundPalletMaterial?: StandardMaterialType;
  private conveyorCardboardMaterial?: StandardMaterialType;
  private inboundStation?: StationDefinition;
  private defaultConveyorLane?: StationDefinition;
  private readonly conveyorStations = new Map<string, StationDefinition>();
  private forkliftStops?: [Vector3Type, Vector3Type];
  private selectedRackId?: string;
  private readonly rackParts = new Map<string, RackVisual>();
  private highlightMaterial?: StandardMaterialType;
  private lastPoseTelemetryAt = 0;
  private lastCameraTelemetryAt = 0;
  private liveMotionBlocked = false;
  private robotCellPhase = "IDLE";

  public constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly onRackSelected: (rackId: string, rackName: string) => void,
    private readonly onLoadHover: (loadId?: string) => void
  ) {
    this.engine = new Engine(canvas, true, { preserveDrawingBuffer: true, stencil: true });
    this.scene = new Scene(this.engine);
    this.materials = new MaterialFactory(this.scene);
    this.cargoAnimator = new CargoHandoverAnimator(this.scene);
    this.conveyorAnimator = new ConveyorAnimator(this.scene);
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
    // Babylon defaults groundColor to pure black, and scene.ambientColor is black too, so
    // with one up-facing hemispheric and one directional key every face pointing downwards
    // or away from the key received *zero* light -- not dim, zero. Crates sitting inside a
    // rack rendered as black silhouettes, and the same pallet appeared to "pop" to full
    // illumination the moment the fork carried it into the key. A dim concrete-toned bounce
    // is the fill an indoor slab actually provides.
    skyLight.groundColor = new Color3(0.26, 0.28, 0.31);
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
    const structure = WarehouseStructureVisual.create(
      this.scene, this.materials, config.id, config.floorColor);
    this.warehouseRoot = structure.root;
    this.highlightMaterial = this.createMaterial("rackHighlight", "#ffd34e");

    const floorMaterial = structure.floorMaterial;
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
    this.createParkingAreas(config.stations?.filter((station) => station.type === "PARKING_CHARGING") ?? []);
    this.createSign(config.signText, config.accentColor);
    this.createInboundStaging(inboundStation, config.inboundLoads ?? this.placeholderLoads(config.inboundCount ?? 0));
    this.createOutboundConveyors(config.stations?.filter((station) => station.type === "CONVEYOR") ?? [], config.conveyorLoads ?? [], config.conveyorTransfers ?? []);
    const robotStation = config.stations?.find((station) => station.type === "ROBOT_CELL");
    if (robotStation) this.createRobotCell(robotStation, config.robotCells ?? [], config.conveyorTransfers ?? [], config.cartons ?? []);
    this.createAisleMarkings(config.aisles ?? []);
    this.createAuxiliaryStations(config.stations ?? []);
    this.forkliftStops = [Vector3.FromArray(config.forkliftStops[0]), Vector3.FromArray(config.forkliftStops[1])];
    this.createForklift(previousForkliftPosition ?? this.forkliftStops[0], config.accentColor);
    if (previousForkliftRotation !== undefined && this.forklift) this.forklift.rotation.y = previousForkliftRotation;
    // Go through syncCarriedCargo rather than building a carried visual outright. The rack
    // and staging passes above have already built a pallet for this load from the same
    // config, so creating a second one here left two on screen -- and because
    // syncCarriedCargo early-returns once carriedCargo matches, that pair was never
    // reconciled. Reparenting the existing one instead is both one pallet and the right
    // shape; it still falls back to createLiveCarriedCargo when the load is in neither list.
    this.syncCarriedCargo(config.carriedLoadId);
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
    this.syncConveyorCargo(config.conveyorLoads ?? [], config.conveyorTransfers ?? []);
    this.syncRobotCell(config.robotCells ?? [], config.conveyorTransfers ?? [], config.cartons ?? []);
    // Last, so a pallet the syncs above have just claimed is no longer pending and
    // cannot be retired by the same pass that placed it.
    this.reconcilePendingCargo(config.loadDetails ?? []);
    this.updateChargingIndicators(config.chargingStationId);
  }

  public setAgvState(agv: ApiAgv): void {
    this.setAgvOperations(agv);
    const sample = { receivedAt: performance.now(), x: agv.x, z: agv.z, theta: -agv.theta - Math.PI / 2, velocity: agv.velocity ?? 0 };
    this.poseInterpolator.push(sample);
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
    this.syncCarriedCargo(agv.carriedLoadId);
    this.updateChargingIndicators(agv.charging ? agv.currentStationId : undefined);
    this.pushForkSample(agv.forkHeight, agv.forkExtension);
  }

  /** Fork telemetry rides its own stream -- AGV_HANDLING_UPDATED at ~20 Hz -- which never
   * reaches setAgvState and therefore never reaches the pose buffer. It cannot share that
   * buffer either: mergeAgvEvent replaces the pose on a handling event with the client's
   * own last-known pose, so a fork sample smuggled in as a PoseSample would re-anchor the
   * chassis segment at a stale position under a fresh timestamp and stall the vehicle.
   *
   * <p>Deliberately no value dedupe. The pose buffer drops repeats, but doing that here
   * would leave the newest sample stamped seconds in the past, so the first frame of a
   * lift would render at progress ~1 and jump -- precisely the staircase this removes. */
  private pushForkSample(height?: number, extension?: number): void {
    this.forkInterpolator.push(height, extension);
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
    if (!this.warehouseRoot) return;
    const visual = RackVisual.create(this.scene, this.warehouseRoot, this.materials, rack, accentColor);
    const parts = visual.meshes;
    const width = visual.width;
    this.rackColliders.push({
      id: rack.id,
      x: rack.position[0],
      z: rack.position[2],
      halfWidth: width / 2 + 0.12,
      halfDepth: 0.46,
      rotationY: rack.rotationY ?? 0
    });

    if (rack.loads) {
      for (const load of rack.loads) this.createCargo(rack, width, load.bay, load.level,
        visual.cardboardMaterial, visual.palletMaterial, parts, false, load.id);
    } else for (let bay = 0; bay < rack.bays; bay += 1) {
      for (let level = 0; level < 3; level += 1) {
        if (!rack.emptySlots?.some(([emptyBay, emptyLevel]) => emptyBay === bay && emptyLevel === level))
          this.createCargo(rack, width, bay, level, visual.cardboardMaterial, visual.palletMaterial, parts, false);
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
    this.rackParts.set(rack.id, visual);
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
    const item = CargoFactory.createRackCargo(
      this.scene, this.warehouseRoot ?? null, rack, rackWidth, bay, level,
      crateMaterial, palletMaterial, rackParts, loadId);
    this.cargoItems.push(item);
    if (animateEntry) this.animateCargoEntry(item);
    return item;
  }

  private readonly onKeyDown = (event: KeyboardEvent): void => {
    if (!this.isControlKey(event.code) || this.isEditingTarget(event.target)) return;
    event.preventDefault();
    this.pressedKeys.add(event.code);
  };

  private readonly onKeyUp = (event: KeyboardEvent): void => {
    if (!this.isControlKey(event.code)) return;
    event.preventDefault();
    this.pressedKeys.delete(event.code);
  };

  private readonly onWindowBlur = (): void => {
    this.pressedKeys.clear();
  };

  /**
   * Pans the orbit camera with WASD, in the directions the viewer sees rather than world
   * axes, so W always moves away from the viewer whatever the current orbit angle.
   *
   * Speed scales with zoom distance: at 10 m a 12 m/s pan would overshoot the whole aisle,
   * and at 65 m it would feel stuck. The target is clamped to the floor so panning cannot
   * strand the camera looking at empty space.
   */
  private readonly updateCameraPan = (): void => {
    const strafe = (this.pressedKeys.has("KeyD") ? 1 : 0) - (this.pressedKeys.has("KeyA") ? 1 : 0);
    const advance = (this.pressedKeys.has("KeyW") ? 1 : 0) - (this.pressedKeys.has("KeyS") ? 1 : 0);
    if (strafe === 0 && advance === 0) return;

    // getDirection resolves the camera's own axes in world space, which keeps this correct
    // in Babylon's left-handed system without re-deriving it from alpha and beta.
    const forward = this.camera.getDirection(FORWARD_AXIS);
    const right = this.camera.getDirection(RIGHT_AXIS);
    let x = forward.x * advance + right.x * strafe;
    let z = forward.z * advance + right.z * strafe;
    const length = Math.hypot(x, z);
    if (length < 1e-4) return;

    const seconds = Math.min(this.engine.getDeltaTime(), 50) / 1000;
    const metres = this.camera.radius * CAMERA_PAN_RATE * seconds;
    x = (x / length) * metres;
    z = (z / length) * metres;

    const target = this.camera.target;
    target.x = Math.max(-FLOOR_HALF_WIDTH, Math.min(FLOOR_HALF_WIDTH, target.x + x));
    target.z = Math.max(-FLOOR_HALF_DEPTH, Math.min(FLOOR_HALF_DEPTH, target.z + z));

    // Throttled so a held key cannot flood the 500-entry telemetry ring.
    const now = performance.now();
    if (now - this.lastCameraTelemetryAt >= 250) {
      this.lastCameraTelemetryAt = now;
      this.telemetry("CAMERA_PANNED", { x: Number(target.x.toFixed(2)), z: Number(target.z.toFixed(2)) });
    }
  };

  /** The chassis interpolation, run against the fork's own buffer and the caller's clock.
   * After this, forkliftLift.position.y has exactly one writer -- the render loop -- which
   * is what makes the smoothing safe. */
  private updateForkFrame(renderAt: number): void {
    const sample = this.forkInterpolator.sample(renderAt);
    if (!sample) return;
    const visual = this.ensureForkliftVisual();
    visual?.setForkHeight(sample.height);
    visual?.setForkExtension(sample.extension);
  }

  private ensureForkliftVisual(): ForkliftVisual | undefined {
    if (!this.forkliftVisual && this.forklift && this.forkliftLift && this.forkliftForkAssembly) {
      this.forkliftVisual = new ForkliftVisual(
        this.forklift, this.forkliftLift, this.forkliftForkAssembly, this.wheelMeshes);
    }
    return this.forkliftVisual;
  }

  private readonly updateFrame = (): void => {
    this.updateCameraPan();
    this.sweepPendingCargo();
    // One clock for both buffers: the carried pallet is a child of the fork assembly, so
    // reading the mast at a different instant from the chassis makes the load lead or lag
    // the vehicle underneath it.
    const renderAt = performance.now() - RENDER_DELAY_MS;
    // Above the pose guard on purpose. The vehicle is stationary for the whole of a lift --
    // moveFork runs at velocity 0 with x/z/theta unchanged -- so the pose buffer receives
    // nothing at exactly the moment the mast is moving fastest.
    this.updateForkFrame(renderAt);
    if (!this.forklift) return;
    const pose = this.poseInterpolator.sample(renderAt);
    if (!pose) return;
    const nextX = pose.x;
    const nextZ = pose.z;
    const nextHeading = pose.theta;
    const visual = this.ensureForkliftVisual();
    if (!visual) return;
    this.forkliftAnimator ??= new ForkliftAnimator(visual);
    this.forkliftAnimator.setPose(nextX, nextZ, nextHeading);
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
    return CAMERA_PAN_KEYS.includes(code);
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

  private selectRack(rackId: string, rackName: string): void {
    this.selectedRackId = rackId;
    for (const [id, rack] of this.rackParts) {
      rack.setHighlighted(id === rackId, this.highlightMaterial?.diffuseColor ?? Color3.Yellow());
    }
    this.onRackSelected(rackId, rackName);
  }

  private createForklift(position: Vector3Type, accentColor: string): void {
    const visual = ForkliftVisual.create(this.scene, this.warehouseRoot ?? null, this.materials, position, accentColor);
    this.forkliftVisual = visual;
    this.forklift = visual.root;
    this.forkliftLift = visual.lift;
    this.forkliftForkAssembly = visual.forkAssembly;
    this.wheelMeshes.push(...visual.wheels);
  }

  private createLiveCarriedCargo(loadId: string): void {
    if (!this.forkliftForkAssembly) return;
    const root = new TransformNode(`liveCarriedCargo-${loadId}`, this.scene);
    root.parent = this.forkliftForkAssembly;
    root.position.set(0, 0.43, -1.72);
    // Built once and reused. These were rebuilt on every pickup, and each one
    // rasterises a 512x512 canvas texture, so the hitch landed precisely on the
    // frame the box changed hands.
    this.cargoPalletMaterial ??= this.createWoodMaterial("liveCargoPalletWood");
    this.cargoCardboardMaterial ??= this.createCardboardMaterial("liveCargoCardboard");
    const palletMaterial = this.cargoPalletMaterial;
    const cardboardMaterial = this.cargoCardboardMaterial;
    const slats = [-0.24, 0, 0.24].map((z) => {
      const slat = MeshBuilder.CreateBox("liveCargoPalletSlat", { width: 0.82, height: 0.075, depth: 0.13 }, this.scene);
      slat.position.set(0, 0.03, z); slat.material = palletMaterial; slat.parent = root;
      return slat;
    });
    const blocks = [-0.31, 0, 0.31].map((x) => {
      const block = MeshBuilder.CreateBox("liveCargoPalletBlock", { width: 0.14, height: 0.12, depth: 0.48 }, this.scene);
      block.position.set(x, -0.055, 0); block.material = palletMaterial; block.parent = root;
      return block;
    });
    const box = MeshBuilder.CreateBox("liveCarriedBox", { width: 0.72, height: 0.56, depth: 0.56 }, this.scene);
    box.position.y = 0.34; box.material = cardboardMaterial; box.parent = root;
    const parts = [...slats, ...blocks, box];
    for (const mesh of parts) this.registerLoadHover(mesh, loadId);
    const item = new PalletVisual(loadId, root, true, parts);
    this.carriedCargo = item;
    this.telemetry("CARGO_ATTACHED", {
      loadId, x: Number((this.forklift?.position.x ?? 0).toFixed(2)), z: Number((this.forklift?.position.z ?? 0).toFixed(2))
    });
  }

  /** One event per handover, not one per snapshot: syncRackCargo runs on every refresh,
   * and the animation telemetry ring holds 500 entries that cargo-handover.spec.ts reads
   * its CARGO_ORPHANED/CARGO_ADOPTED pairs out of. Flooding it would evict the very
   * events that spec asserts on. */
  private noteCarriedRebuildSuppressed(loadId: string, source: "RACK" | "STAGING", rackId?: string): void {
    const key = `${source}:${loadId}`;
    if (this.suppressedRebuilds.has(key)) return;
    this.suppressedRebuilds.add(key);
    this.telemetry("CARGO_REBUILD_SUPPRESSED", { loadId, source, owner: "FORK", ...(rackId ? { rackId } : {}) });
  }

  private syncCarriedCargo(loadId?: string): void {
    if (loadId && this.carriedCargo?.id === loadId) return;
    // The fork's load is changing, so any suppression recorded against the old one is
    // spent; keeping it would silence the telemetry for the next genuine handover.
    this.suppressedRebuilds.clear();
    if (this.carriedCargo) {
      const previous = this.carriedCargo;
      this.carriedCargo = undefined;
      // Parked, not destroyed: the shelf will not list this load until the next
      // snapshot reports it stored, and disposing here is what made a dropped
      // pallet arrive late -- or never, when the status lagged the physical drop.
      this.releaseCargo(previous, "RELEASED_BY_FORK");
      this.telemetry("CARGO_DETACHED", { loadId: previous.id });
    }
    if (!loadId || !this.forkliftForkAssembly) return;
    const existing = this.claimPendingCargo(loadId)
      ?? [...this.inboundCargoItems, ...this.cargoItems].find((item) => item.id === loadId);
    if (!existing) {
      this.createLiveCarriedCargo(loadId);
      return;
    }
    const inboundIndex = this.inboundCargoItems.indexOf(existing);
    if (inboundIndex >= 0) this.inboundCargoItems.splice(inboundIndex, 1);
    const rackIndex = this.cargoItems.indexOf(existing);
    if (rackIndex >= 0) this.cargoItems.splice(rackIndex, 1);
    const forkliftVisual = this.ensureForkliftVisual();
    if (forkliftVisual) forkliftVisual.attachCargo(existing);
    else {
      existing.root.parent = this.forkliftForkAssembly;
      existing.carried = true;
    }
    existing.root.position.set(0, .43, -1.72);
    existing.root.rotation.set(0, 0, 0);
    existing.root.scaling.set(1, 1, 1);
    this.carriedCargo = existing;
    this.telemetry("CARGO_ATTACHED", { loadId });
  }

  // animateForkHeight lived here: a tween onto forkliftLift.position.y that nothing ever
  // called. Now that updateForkFrame owns that property it would be a silent second writer
  // whose winner depends on Babylon ordering _animate() against onBeforeRenderObservable,
  // so it is gone rather than left as a trap for whoever revived it.

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
    const steel = this.createMetalMaterial(`${station.id}-dockSteel`, "#59636c", 64);
    const rubber = this.createMaterial(`${station.id}-dockRubber`, "#20262b");
    const safety = this.createMaterial(`${station.id}-dockSafety`, "#e87518");
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
    if (!this.warehouseRoot) return;
    this.chargingStations = new ChargingStationVisual(this.scene, this.warehouseRoot, this.materials);
    this.chargingStations.build(stations);
    this.telemetry("PARKING_AREAS_CREATED", { count: stations.length, ids: stations.map((station) => station.id) });
  }

  private updateChargingIndicators(activeStationId?: string): void {
    this.chargingStations?.setActive(activeStationId);
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
    loads.slice(0, 20).forEach((load, index) => this.addInboundCargo(load, index, false));
  }

  /** Shared by the initial placement and the reposition pass, so a pallet cannot be built
   * in one place and then moved to another. */
  private inboundSlot(index: number): { localX: number; localZ: number } {
    return {
      localX: INBOUND_ROW_FRONT_X - Math.floor(index / INBOUND_COLUMNS) * INBOUND_ROW_PITCH,
      localZ: INBOUND_COLUMN_FIRST_Z + (index % INBOUND_COLUMNS) * INBOUND_COLUMN_PITCH
    };
  }

  private addInboundCargo(load: LoadVisualDefinition, index: number, animateEntry: boolean): void {
    if (!this.inboundCardboardMaterial || !this.inboundPalletMaterial || !this.inboundStation) return;
    const slot = this.inboundSlot(index);
    const point = this.stationPoint(this.inboundStation, slot.localX, slot.localZ);
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
    const item = new PalletVisual(load.id, root, false, [base, box, label]);
    for (const mesh of item.meshes) this.registerLoadHover(mesh, load.id);
    this.inboundCargoItems.push(item);
    if (animateEntry) this.animateCargoEntry(item);
  }

  private syncInboundCargo(loads: LoadVisualDefinition[]): void {
    const desired = loads.slice(0, 20);
    const desiredIds = new Set(desired.map((load) => load.id));
    const previousIds = this.inboundCargoItems.map((item) => item.id);
    for (const item of [...this.inboundCargoItems]) {
      if (desiredIds.has(item.id) || item.carried) continue;
      this.inboundCargoItems.splice(this.inboundCargoItems.indexOf(item), 1);
      // Staging to fork is the commonest move in the demo and was the ugliest: this
      // list is rebuilt from the snapshot, which regularly drops the load before
      // telemetry announces the fork has it.
      this.releaseCargo(item, "LEFT_STAGING");
    }
    desired.forEach((load, index) => {
      if (this.inboundCargoItems.some((item) => item.id === load.id)) return;
      // Same defect as syncRackCargo, from the staging side: the pallet is on the fork and
      // inboundCargoItems no longer proves it, so a second one appeared back in the lane.
      if (load.id === this.carriedCargo?.id) { this.noteCarriedRebuildSuppressed(load.id, "STAGING"); return; }
      const handedOver = this.pendingCargo.has(load.id);
      this.addInboundCargo(load, index, !handedOver);
      const created = this.inboundCargoItems.find((item) => item.id === load.id);
      if (handedOver && created) this.adoptPendingCargo(load.id, created);
    });
    desired.forEach((load, index) => {
      const item = this.inboundCargoItems.find((candidate) => candidate.id === load.id);
      if (!item || !this.inboundStation) return;
      const slot = this.inboundSlot(index);
      const point = this.stationPoint(this.inboundStation, slot.localX, slot.localZ);
      item.root.position.x = point.x; item.root.position.z = point.z;
    });
    const currentIds = this.inboundCargoItems.map((item) => item.id);
    if (JSON.stringify(previousIds) !== JSON.stringify(currentIds)) this.telemetry("INBOUND_LOADS_SYNCED", { previousIds, currentIds });
  }

  private createOutboundConveyors(stations: StationDefinition[], loads: LoadVisualDefinition[], transfers: ConveyorVisualDefinition[]): void {
    this.conveyorStations.clear();
    const lanes = [...stations].sort((a, b) => a.id.localeCompare(b.id));
    // Guard before creating materials: they are only reclaimed via the mesh graph,
    // so returning after building them leaked a material set and a 512x512 texture
    // on every scene rebuild (and the offline fallback config has no stations).
    if (lanes.length === 0) {
      return;
    }
    const visual = ConveyorVisual.create(this.scene, this.warehouseRoot ?? null, this.materials, lanes);
    this.conveyorCardboardMaterial = visual.cardboardMaterial;
    for (const lane of visual.lanes) {
      const { station, center, length, depth: laneDepth } = lane;
      this.conveyorStations.set(station.id, station);
      this.createFloorLabel(station, station.id, 0, 0, 2.4, 0.42, "#d9f2e7");
      // Conveyors are solid, but they are not warehouse_obstacle rows because the
      // AGV never routes through them and the route graph carries conveyor-flow
      // edges along these same lanes. Registering them client-side stops the
      // sandbox forklift driving straight through the decks.
      this.rackColliders.push({
        id: station.id, x: center.x, z: center.z,
        halfWidth: length / 2, halfDepth: laneDepth / 2, rotationY: station.rotationY
      });
    }
    this.defaultConveyorLane = lanes[0];
    loads.slice(0, CONVEYOR_CARGO_LIMIT).forEach((load, index) => {
      this.addConveyorCargo(load, index, false, this.conveyorIdFor(load, index, transfers, lanes));
    });
  }

  /** Resolves the lane a carton actually travels on. The WCS load-balances the two
   * lanes in WarehouseStore.completeRobotPick, so deriving the lane from the array
   * index instead of the transfer record made the picture contradict the data. */
  private conveyorIdFor(load: LoadVisualDefinition, index: number,
    transfers: ConveyorVisualDefinition[], lanes: StationDefinition[]): string | undefined {
    const transfer = transfers.find((candidate) => candidate.loadId === load.id || candidate.cartonId === load.id);
    return transfer?.conveyorId ?? lanes[index % lanes.length]?.id;
  }

  /** Zone outlines for the stations that carry floor area but no equipment of their
   * own. Without these, five station types (both docks, quality control,
   * maintenance, and any future zone) occupied space in the layout and in the
   * overlap checks while drawing nothing at all, which is how the dock and staging
   * areas were able to overlap unnoticed. */
  private createAuxiliaryStations(stations: StationDefinition[]): void {
    const zones: Array<[string, string, string]> = [
      // Quality control and maintenance are modelled as locations but nothing routes
      // to them and nothing reads them, so their outlines and floor text were two
      // unexplained rectangles in open floor. The rows stay in the database; they
      // just no longer claim space on the operator's picture.
      ["OUTBOUND_DOCK", "SHIPPING DOCK", "#8fd0ff"],
      ["RECEIVING_DOCK", "RECEIVING DOCK", "#8fd0ff"]
    ];
    for (const [type, label, color] of zones) {
      for (const station of stations.filter((candidate) => candidate.type === type)) {
        const outline = this.createMaterial(`zoneOutline-${station.id}`, color);
        outline.emissiveColor = Color3.FromHexString(color).scale(.18);
        const halfWidth = station.width / 2;
        const halfDepth = station.depth / 2;
        for (const [x, z, width, depth] of [
          [0, -halfDepth, station.width, .1], [0, halfDepth, station.width, .1],
          [-halfWidth, 0, .1, station.depth], [halfWidth, 0, .1, station.depth]
        ] as number[][]) {
          const line = MeshBuilder.CreateBox(`zoneBoundary-${station.id}`, { width, height: .02, depth }, this.scene);
          const point = this.stationPoint(station, x, z);
          line.position.set(point.x, .016, point.z);
          line.rotation.y = station.rotationY;
          line.material = outline;
          line.parent = this.warehouseRoot ?? null;
        }
        this.createFloorLabel(station, label, 0, 0, Math.min(station.width - .6, 4.4), .5, color);
        // Dock hardware belongs on the dock, not on the staging area behind it.
        if (type === "RECEIVING_DOCK") this.createDockEquipment(station, "RECEIVING");
        if (type === "OUTBOUND_DOCK") this.createShippingDoors(station);
      }
    }
  }

  /** Frames the conveyor penetration in the west wall (V23).
   *
   * <p>This used to draw two solid shutters keyed to the dock centre +/- 1.5 m, so
   * one landed on a lane and the other on blank wall, and both sealed an opening
   * that did not exist -- the belts simply stopped at the wall. The opening is a
   * property of the wall, not of the dock, so it is drawn from the same constants
   * the migration used rather than from a station that has since moved outside. */
  private createShippingDoors(station: StationDefinition): void {
    const steel = this.createMetalMaterial(`shippingDoorSteel-${station.id}`, "#59636c", 64);
    const safety = this.createMaterial(`shippingDoorSafety-${station.id}`, "#e87518");

    // Jambs close the reveal either side of the opening, so the wall reads as cut
    // rather than as merely absent between two segments. They run the full portal
    // height, not just the parapet, so the raised opening still reads as framed.
    for (const z of [SHIPPING_OPENING_MIN_Z, SHIPPING_OPENING_MAX_Z]) {
      const jamb = MeshBuilder.CreateBox(`shippingJamb-${station.id}`, { width: 0.34, height: SHIPPING_PORTAL_HEIGHT, depth: 0.22 }, this.scene);
      jamb.position.set(SHIPPING_WALL_X, SHIPPING_PORTAL_HEIGHT / 2, z);
      jamb.material = steel;
      jamb.parent = this.warehouseRoot ?? null;
    }

    // Infill the parts of the structural opening no belt passes through: the reveal at
    // each end and, most visibly, the gap between the two lanes. Without these you can
    // see straight through the building between the belts.
    const infillSpans: Array<[number, number]> = [];
    let edge = SHIPPING_OPENING_MIN_Z;
    for (const [laneMin, laneMax] of SHIPPING_LANE_ENVELOPES) {
      if (laneMin > edge) infillSpans.push([edge, laneMin]);
      edge = Math.max(edge, laneMax);
    }
    if (SHIPPING_OPENING_MAX_Z > edge) infillSpans.push([edge, SHIPPING_OPENING_MAX_Z]);
    const wallMaterial = this.createMaterial(`shippingInfill-${station.id}`, "#c7cbc8");
    for (const [spanMin, spanMax] of infillSpans) {
      const panel = MeshBuilder.CreateBox(`shippingInfill-${station.id}`,
        { width: 0.18, height: WALL_HEIGHT, depth: spanMax - spanMin }, this.scene);
      panel.position.set(SHIPPING_WALL_X, WALL_HEIGHT / 2, (spanMin + spanMax) / 2);
      panel.material = wallMaterial;
      panel.parent = this.warehouseRoot ?? null;
    }

    // Header spanning the opening, with the roller drum a shutter would wind onto.
    // Sits on top of the portal so its underside clears the cargo profile.
    const openingWidth = SHIPPING_OPENING_MAX_Z - SHIPPING_OPENING_MIN_Z;
    const openingCentreZ = (SHIPPING_OPENING_MIN_Z + SHIPPING_OPENING_MAX_Z) / 2;
    const header = MeshBuilder.CreateBox(`shippingHeader-${station.id}`, { width: 0.3, height: 0.34, depth: openingWidth }, this.scene);
    header.position.set(SHIPPING_WALL_X, SHIPPING_PORTAL_HEIGHT + 0.17, openingCentreZ);
    header.material = steel;
    header.parent = this.warehouseRoot ?? null;

    const drum = MeshBuilder.CreateCylinder(`shippingDoorDrum-${station.id}`, { diameter: 0.3, height: openingWidth - 0.5, tessellation: 16 }, this.scene);
    drum.rotation.x = Math.PI / 2;
    drum.position.set(SHIPPING_WALL_X + 0.3, SHIPPING_PORTAL_HEIGHT + 0.16, openingCentreZ);
    drum.material = steel;
    drum.parent = this.warehouseRoot ?? null;

    // Hazard kerbs along the reveal, at the belt edges rather than the dock centre.
    for (const z of [SHIPPING_OPENING_MIN_Z + 0.22, SHIPPING_OPENING_MAX_Z - 0.22]) {
      const kerb = MeshBuilder.CreateBox(`shippingKerb-${station.id}`, { width: 0.62, height: 0.1, depth: 0.16 }, this.scene);
      kerb.position.set(SHIPPING_WALL_X, 0.05, z);
      kerb.material = safety;
      kerb.parent = this.warehouseRoot ?? null;
    }
  }

  /** Builds the arm as a genuine pin-joint chain.
   *
   * The previous version positioned each link as if its pivot were at the link's
   * lower end, but Babylon rotates a box about its centroid and the rotations were
   * applied to the meshes rather than to the joints. Every link therefore slid off
   * its joint by half its length times the sine of its angle, leaving three
   * disconnected boxes with 0.36 m, 0.66 m and 0.86 m gaps between them and a
   * gripper hovering 3.1 m up over empty floor.
   *
   * The invariant that keeps it assembled: rotation lives ONLY on the joint nodes,
   * and each link mesh sits at +length/2 along its parent joint's local +Y, with
   * the next joint at +length. Move a rotation onto a mesh and it comes apart
   * again. */
  private createRobotCell(station: StationDefinition, cells: RobotCellVisualDefinition[],
    transfers: ConveyorVisualDefinition[] = [], cartons: CartonVisualDefinition[] = []): void {
    const visual = RobotCellVisual.create(
      this.scene, this.warehouseRoot ?? null, this.materials, station, {
        pedestalX: ARM_PEDESTAL_LOCAL_X,
        shoulderHeight: ARM_SHOULDER_HEIGHT,
        upperLength: ARM_UPPER_LENGTH,
        forearmLength: ARM_FOREARM_LENGTH,
        gripperLength: ARM_GRIPPER_LENGTH,
        handoffX: ROBOT_HANDOFF_LOCAL_X
      });
    this.robotCellRoot = visual.root;
    this.robotCellStation = station;
    const halfDepth = station.depth / 2;
    this.armYaw = visual.yaw; this.armShoulder = visual.shoulder; this.armElbow = visual.elbow; this.armWrist = visual.wrist;
    this.robotArmAnimator = new RobotArmAnimator(this.scene, {
      yaw: visual.yaw, shoulder: visual.shoulder, elbow: visual.elbow, wrist: visual.wrist
    });
    this.createFloorLabel(station, "ROBOT-01", 0, -halfDepth + .55, 2.5, .55, "#fff1bf");
    this.robotCellPhase = "";
    this.applyArmPose(this.stowPose(), 0);
    this.syncRobotCell(cells, transfers, cartons);
  }

  private solveArmPose(localX: number, localY: number, localZ: number): ArmPose {
    const target = { x: localX, y: localY, z: localZ };
    const reach = requiredReach(ARM_GEOMETRY, target);
    if (reach >= maximumReach(ARM_GEOMETRY))
      this.telemetry("ROBOT_TARGET_OUT_OF_REACH", {
        target, required: Number(reach.toFixed(2)), maximum: maximumReach(ARM_GEOMETRY)
      });
    return solveArmPose(ARM_GEOMETRY, target);
  }

  private stowPose(): ArmPose {
    return this.solveArmPose(ARM_PEDESTAL_LOCAL_X + .85, 2.1, 0);
  }

  private applyArmPose(pose: ArmPose, frames: number): void {
    this.robotArmAnimator?.apply(pose, frames);
  }

  /** Mirrors WarehouseStore.completeRobotPick: the WCS assigns each carton to the
   * lane with fewer transfers in flight, so the arm reaches for that same lane. */
  private placingLane(transfers: ConveyorVisualDefinition[]): StationDefinition | undefined {
    const lanes = [...this.conveyorStations.values()].sort((a, b) => a.id.localeCompare(b.id));
    if (lanes.length === 0) return undefined;
    const moving = (id: string): number => transfers.filter((t) => t.conveyorId === id && t.status === "MOVING").length;
    return lanes.reduce((best, lane) => moving(lane.id) < moving(best.id) ? lane : best, lanes[0]);
  }

  private syncRobotCell(cells: RobotCellVisualDefinition[], transfers: ConveyorVisualDefinition[] = [],
    cartons: CartonVisualDefinition[] = []): void {
    const phase = cells.find((cell) => cell.id === "ROBOT-01")?.phase ?? "IDLE";
    const activeCarton = cartons.find((carton) => carton.status === "PICKING" || carton.status === "PLACING");
    const handoffPalletId = activeCarton?.palletId
      ?? cartons.find((carton) => carton.status === "AT_HANDOFF")?.palletId;
    const handoffCartons = cartons
      .filter((carton) => carton.status === "AT_HANDOFF" && carton.palletId === handoffPalletId)
      .map((carton) => carton.id)
      .sort();
    this.setHandoffPallet(handoffCartons);
    this.setGrippedCarton(activeCarton?.id);
    if (!this.armYaw || !this.robotCellStation || phase === this.robotCellPhase) return;
    this.robotCellPhase = phase;
    const station = this.robotCellStation;
    const pad = { x: ROBOT_HANDOFF_LOCAL_X, z: 0 };

    if (phase === "IDLE") {
      this.applyArmPose(this.stowPose(), 42);
    } else if (phase === "AT_HANDOFF") {
      this.applyArmPose(this.solveArmPose(pad.x, 1.35, pad.z), 42);
    } else if (phase === "PICKING") {
      this.applyArmPose(this.solveArmPose(pad.x, .95, pad.z), 34);
    } else if (phase === "PLACING") {
      const lane = this.placingLane(transfers);
      // Aim at the lane's INFEED, not its centre: the lanes are 9.6 m long, so the
      // centre is 7 m from the pedestal and far outside the arm's envelope. Going
      // through stationPoint then back into cell-local keeps this correct for any
      // lane rotation rather than assuming the two frames are axis-aligned.
      const target = lane
        ? this.toStationLocal(station, ...this.conveyorInfeed(lane))
        : { x: ARM_PEDESTAL_LOCAL_X - 2, z: 0 };
      this.applyArmPose(this.solveArmPose(target.x, 1.1, target.z), 46);
    }
    this.telemetry("ROBOT_PHASE", { robotId: "ROBOT-01", phase });
  }

  /** World position where cargo enters a lane, i.e. where the arm must release it. */
  private conveyorInfeed(lane: StationDefinition): [number, number] {
    const point = this.stationPoint(lane, -lane.width / 2 + CONVEYOR_INFEED_INSET, 0);
    return [point.x, point.z];
  }

  /** Inverse of stationPoint: world position into station-local coordinates. */
  private toStationLocal(station: StationDefinition, worldX: number, worldZ: number): { x: number; z: number } {
    const cos = Math.cos(station.rotationY);
    const sin = Math.sin(station.rotationY);
    const dx = worldX - station.position[0];
    const dz = worldZ - station.position[2];
    return { x: dx * cos - dz * sin, z: dx * sin + dz * cos };
  }

  /** The pallet the AGV presented, sitting on the handoff pad. */
  private setHandoffPallet(cartonIds: string[]): void {
    if (cartonIds.length === this.handoffCartonIds.length
      && cartonIds.every((id, index) => id === this.handoffCartonIds[index])) return;
    if (this.handoffPallet) {
      this.handoffPallet.dispose(false, false);
      this.handoffPallet = undefined;
    }
    this.handoffCartonIds = [...cartonIds];
    if (cartonIds.length === 0) return;
    if (!this.robotCellRoot) return;
    const pallet = new TransformNode("robotHandoffPallet", this.scene);
    pallet.position.set(ROBOT_HANDOFF_LOCAL_X, .06, 0);
    pallet.parent = this.robotCellRoot;
    const deck = MeshBuilder.CreateBox("robotHandoffPalletDeck", { width: .92, height: .12, depth: .74 }, this.scene);
    deck.material = this.createWoodMaterial("robotHandoffPalletWood"); deck.parent = pallet;
    // One shared cardboard material for all four cartons; creating it per carton
    // would build four identical 512x512 procedural textures.
    const cartonMaterial = this.createCardboardMaterial("robotHandoffCartonBoard");
    const positions = [[-.24, -.2], [.24, -.2], [-.24, .2], [.24, .2]] as number[][];
    cartonIds.slice(0, positions.length).forEach((cartonId, index) => {
      const [x, z] = positions[index];
      const carton = MeshBuilder.CreateBox(`robotHandoffCarton-${cartonId}`, { width: .38, height: .34, depth: .3 }, this.scene);
      carton.position.set(x, .23, z);
      carton.material = cartonMaterial;
      carton.metadata = { cartonId, owner: "HANDOFF" };
      carton.parent = pallet;
    });
    this.handoffPallet = pallet;
  }

  /** The carton currently held by the gripper. Parented to the wrist so it follows
   * the solved pose rather than being animated separately. */
  private setGrippedCarton(cartonId?: string): void {
    if (cartonId === this.grippedCartonId) return;
    if (this.grippedCarton) {
      this.grippedCarton.dispose(false, false);
      this.grippedCarton = undefined;
    }
    this.grippedCartonId = cartonId;
    if (!cartonId) return;
    if (!this.armWrist) return;
    const carton = MeshBuilder.CreateBox(`robotGrippedCarton-${cartonId}`, { width: .38, height: .34, depth: .3 }, this.scene);
    carton.position.y = ARM_GRIPPER_LENGTH + .25;
    carton.material = this.createCardboardMaterial("robotGrippedCartonBoard");
    carton.metadata = { cartonId, owner: "GRIPPER" };
    carton.parent = this.armWrist;
    this.grippedCarton = carton;
  }

  private createFloorLabel(station: StationDefinition, text: string, localX: number, localZ: number, width: number, height: number, color: string): void {
    const point = this.stationPoint(station, localX, localZ);
    this.drawFloorText(text, point.x, point.z, station.rotationY, width, height, color);
  }

  /** World-space floor text. Split out of createFloorLabel because aisles are not
   * stations and have no local frame to project through, but should be lettered in
   * exactly the same hand as everything else painted on the floor. */
  private drawFloorText(text: string, worldX: number, worldZ: number, rotationY: number,
    width: number, height: number, color: string): void {
    const texture = new DynamicTexture(`floorLabel-${text}`, { width: 512, height: 128 }, this.scene, true);
    texture.hasAlpha = true;
    texture.drawText(text, null, 84, "bold 42px Arial", color, "transparent", true, true);
    const material = new StandardMaterial(`floorLabelMaterial-${text}`, this.scene);
    material.diffuseTexture = texture; material.emissiveColor = new Color3(.14, .14, .14); material.useAlphaFromDiffuseTexture = true;
    const label = MeshBuilder.CreatePlane(`floorLabel-${text}`, { width, height }, this.scene);
    // rotation.x lays the plane flat with its textured face downwards, so the camera
    // reads it from behind: the extra PI that used to be added here spun the label
    // to compensate, which left every floor label in the warehouse -- "RECEIVING"
    // included -- readable only in a mirror. The plane is symmetric about its own
    // centre, so dropping it is the whole fix.
    label.position.set(worldX, .027, worldZ); label.rotation.x = Math.PI / 2; label.rotation.y = rotationY;
    label.material = material; label.parent = this.warehouseRoot ?? null;
  }

  /** Letters each travel aisle at both ends.
   *
   * <p>The aisles have always existed in the data -- three rack rows, a lane of
   * route nodes each -- but carried no name anywhere the operator could see, so
   * "put it in aisle B" had nothing on screen to refer to. Both ends are marked
   * because the vehicle enters from either the west cross-aisle or the east one. */
  private createAisleMarkings(aisles: AisleDefinition[]): void {
    for (const aisle of aisles) {
      const [x, , z] = aisle.position;
      const rotationY = aisle.rotationY ?? 0;
      const inset = aisle.length / 2 - AISLE_LABEL_INSET;
      for (const offset of [-inset, inset]) {
        const worldX = x + Math.cos(rotationY) * offset;
        const worldZ = z - Math.sin(rotationY) * offset;
        this.drawFloorText(aisle.name.toUpperCase(), worldX, worldZ, rotationY,
          AISLE_LABEL_WIDTH, AISLE_LABEL_HEIGHT, AISLE_LABEL_COLOR);
      }
    }
    if (aisles.length > 0) this.telemetry("AISLES_MARKED", { aisles: aisles.map((aisle) => aisle.id) });
  }

  /** Cargo enters at the lane's local -x infeed and travels to its local +x
   * discharge. Every position goes through stationPoint, so the lane's rotationY is
   * what decides the world-space direction: the outbound lanes are rotated by pi in
   * V21 so they run west into the shipping dock. Nothing here hard-codes a compass
   * direction, and the two lanes can be given opposite rotations independently.
   * Node and animation names are keyed by load id, not array index, so a carton's
   * identity survives resyncs. */
  private addConveyorCargo(load: LoadVisualDefinition, index: number, animateEntry: boolean, conveyorId?: string): void {
    const station = (conveyorId ? this.conveyorStations.get(conveyorId) : undefined) ?? this.defaultConveyorLane;
    if (!this.conveyorCardboardMaterial || !station) return;
    const root = new TransformNode(`shippingCargo-${load.id}`, this.scene);
    const start = this.stationPoint(station, -station.width / 2 + CONVEYOR_INFEED_INSET + index * .15, 0);
    root.position.set(start.x, 1.2, start.z);
    root.rotation.y = station.rotationY;
    root.parent = this.warehouseRoot ?? null;
    const box = MeshBuilder.CreateBox(`shippingBox-${load.id}`, { width: 0.62, height: 0.56, depth: 0.62 }, this.scene);
    box.material = this.conveyorCardboardMaterial;
    box.metadata = { cartonId: load.id, owner: "CONVEYOR" };
    box.parent = root;
    const item = new PalletVisual(load.id, root, false, [box]);
    this.registerLoadHover(box, load.id);
    this.conveyorCargoItems.push(item);
    if (animateEntry) this.animateCargoEntry(item);
    const end = this.stationPoint(station, station.width / 2 - .7 - index * .25, 0);
    this.conveyorAnimator.travel(load.id, root, new Vector3(end.x, 1.2, end.z));
    this.telemetry("CONVEYOR_CARGO_ADDED", {
      loadId: load.id, conveyorId: station.id,
      startX: Number(start.x.toFixed(2)), endX: Number(end.x.toFixed(2))
    });
  }

  private syncConveyorCargo(loads: LoadVisualDefinition[], transfers: ConveyorVisualDefinition[]): void {
    const lanes = [...this.conveyorStations.values()].sort((a, b) => a.id.localeCompare(b.id));
    const desired = loads.slice(0, CONVEYOR_CARGO_LIMIT);
    const desiredIds = new Set(desired.map((load) => load.id));
    if (desired.length !== this.conveyorCargoItems.length) this.telemetry("CONVEYOR_COUNT_CHANGED", { from: this.conveyorCargoItems.length, to: desired.length });
    for (const item of [...this.conveyorCargoItems]) {
      if (desiredIds.has(item.id)) continue;
      this.conveyorCargoItems.splice(this.conveyorCargoItems.indexOf(item), 1);
      // Leave along the lane the carton is actually on, past its discharge end.
      // The previous fixed offset from lanes[0] dragged cartons diagonally onto
      // the other lane's centreline before they faded.
      const lane = this.laneForCargo(item.id, transfers) ?? this.defaultConveyorLane;
      const end = lane
        ? this.stationPoint(lane, lane.width / 2 + 1.6, 0)
        : { x: item.root.position.x, z: item.root.position.z };
      this.conveyorAnimator.exit(item.root, new Vector3(end.x, item.root.position.y, end.z),
        () => this.disposeCargoVisual(item));
    }
    desired.forEach((load, index) => {
      if (!this.conveyorCargoItems.some((item) => item.id === load.id))
        this.addConveyorCargo(load, index, true, this.conveyorIdFor(load, index, transfers, lanes));
    });
  }

  private laneForCargo(loadId: string, transfers: ConveyorVisualDefinition[]): StationDefinition | undefined {
    const transfer = transfers.find((candidate) => candidate.loadId === loadId || candidate.cartonId === loadId);
    return transfer?.conveyorId ? this.conveyorStations.get(transfer.conveyorId) : undefined;
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
      for (const [id, target] of desired) {
        if (target.rack.id !== rack.id || this.cargoItems.some((item) => item.id === id)) continue;
        // The fork is holding this pallet. syncCarriedCargo splices the carried visual out
        // of cargoItems, and that same list is the "already built" test above -- so the
        // snapshot, which goes on listing the load in its old slot until the task status
        // catches up, built a second pallet on the shelf while the first rode away on the
        // fork. Measured on the live stack at 7.6 s of dual ownership and a duplicate mesh
        // that outlived the carry by twelve seconds.
        if (id === this.carriedCargo?.id) { this.noteCarriedRebuildSuppressed(id, "RACK", rack.id); continue; }
        // A load arriving on a shelf it was just carried to already has a visual in
        // flight; glide that one into the slot instead of popping a second one in.
        const handedOver = this.pendingCargo.has(id);
        const created = this.createCargo(rack, parts.width, target.bay, target.level,
          parts.cardboardMaterial, parts.palletMaterial, parts.meshes, !handedOver, id);
        if (handedOver) this.adoptPendingCargo(id, created);
      }
    }
    for (const item of [...this.cargoItems]) {
      if (desired.has(item.id) || item.carried) continue;
      this.cargoItems.splice(this.cargoItems.indexOf(item), 1);
      // The fork may be about to claim this: telemetry saying so can arrive after
      // the snapshot that removed it from the shelf.
      this.releaseCargo(item, "LEFT_RACK");
    }
  }

  /** Parks a cargo visual between owners instead of destroying it.
   *
   * <p>Every handover used to be a dispose followed by a create, and the two update
   * streams that drive it do not coordinate: the snapshot removes a load from a
   * shelf when its status changes, telemetry adds it to the fork when the vehicle
   * reports it. Whichever lost the race left a gap with no box anywhere -- visible
   * as a shelf box shrinking away and a new one popping onto the fork, or as a
   * pallet arriving late (or never) after a drop. Parking it keeps one continuous
   * object across the handover no matter which stream arrives first.
   *
   * <p>setParent, not .parent: it preserves the world transform, so the box stays
   * exactly where the viewer last saw it rather than jumping to the origin. */
  /** Retire a cargo visual without taking the shared materials with it.
   *
   * <p>These roots used to be disposed with Babylon's disposeMaterialAndTextures flag set.
   * Cargo materials are deliberately shared -- one cardboard material serves every staged
   * pallet, because each one rasterises a 512x512 canvas texture -- and Material.dispose()
   * unbinds itself from every mesh using it. So retiring a single pallet stripped the
   * material off all the others and they rendered in Babylon's default white. Worse, it did
   * not recover: the next createInboundStaging built a fresh material under the same name,
   * but nothing reassigns it to meshes that already exist, so they stayed white until the
   * whole scene was rebuilt.
   *
   * <p>Only the per-load label material belongs to this item, so only that is disposed. */
  private disposeCargoVisual(item: CargoItem): void {
    const own = (item.meshes ?? [])
      .map((mesh) => mesh.material)
      .filter((material) => !!material && material.name.startsWith("inboundLabelMaterial-"));
    item.root.dispose(false, false);
    for (const material of own) material?.dispose();
  }

  private releaseCargo(item: CargoItem, reason: string): void {
    if (this.forkliftVisual) this.forkliftVisual.detachCargo(item, this.warehouseRoot ?? null);
    else item.detachTo(this.warehouseRoot ?? null);
    this.pendingCargo.set(item.id, { item, orphanedAt: performance.now() });
    this.telemetry("CARGO_ORPHANED", { loadId: item.id, reason });
  }

  /** Takes a parked visual, if one is waiting for this load. */
  private claimPendingCargo(loadId: string): CargoItem | undefined {
    const pending = this.pendingCargo.get(loadId);
    if (!pending) return undefined;
    this.pendingCargo.delete(loadId);
    return pending.item;
  }

  /** Hands a parked visual over to a newly built one standing in the same place.
   *
   * <p>Staging pallets and shelf pallets are modelled differently (a flat base with
   * a label versus slats, blocks and a crate), so a staging visual cannot simply be
   * adopted onto a shelf -- the wrong shape would stay there. The replacement is
   * created at the parked visual's exact pose and the parked one disposed in the
   * same frame, which is invisible, and then it glides to its slot. */
  private adoptPendingCargo(loadId: string, replacement: CargoItem): boolean {
    const parked = this.claimPendingCargo(loadId);
    if (!parked) return false;
    const target = replacement.root.position.clone();
    const targetRotationY = replacement.root.rotation.y;
    // Both are read in warehouseRoot space: releaseCargo reparents the parked visual
    // there, so its local position is directly comparable to the replacement's.
    // Mixing an absolute position with a local one puts the box in the wrong place
    // the moment the root is ever transformed.
    const from = parked.root.position.clone();
    this.disposeCargoVisual(parked);
    replacement.root.position.copyFrom(from);
    replacement.root.scaling.set(1, 1, 1);
    this.animateCargoPlacement(replacement, target, targetRotationY);
    this.telemetry("CARGO_ADOPTED", { loadId });
    return true;
  }

  /** Glides a box from wherever it was handed over into its slot, so the vehicle is
   * seen to place it rather than the box blinking into position. */
  private animateCargoPlacement(item: CargoItem, target: Vector3Type, targetRotationY: number): void {
    this.cargoAnimator.place(item.id, item.root, target, targetRotationY, CARGO_PLACEMENT_FRAMES);
  }

  /** Retires anything nobody claimed, as a long-stop only.
   *
   * <p>The real decision is made by reconcilePendingCargo against the load list. A
   * timer alone could not do this job: the interval between the fork releasing a
   * pallet and the backend reporting it stored is not bounded by anything the
   * browser knows, and measuring it produced values from a few seconds to never.
   * Any constant short enough to clear ghosts promptly was also short enough to
   * delete a pallet that was still on its way to a shelf -- which is the original
   * bug. So this only catches leaks. */
  private sweepPendingCargo(): void {
    if (this.pendingCargo.size === 0) return;
    const now = performance.now();
    for (const [loadId, pending] of [...this.pendingCargo]) {
      if (now - pending.orphanedAt < CARGO_HANDOVER_GRACE_MS) continue;
      this.pendingCargo.delete(loadId);
      this.telemetry("CARGO_EXPIRED", { loadId });
      this.animateCargoExit(pending.item, new Vector3(0.12, 0.12, 0.12));
    }
  }

  /** Decides the fate of parked cargo from the load list rather than from a clock.
   *
   * <p>A pallet is kept while the data still says it is somewhere the scene draws --
   * staging, a shelf, or in transit between them -- because one of the sync paths
   * will claim it as soon as its status catches up. It is retired only on evidence:
   * the load has left the warehouse, or its visual now belongs to the conveyor. */
  private reconcilePendingCargo(loads: LoadVisualDefinition[]): void {
    if (this.pendingCargo.size === 0) return;
    const byId = new Map(loads.map((load) => [load.id, load]));
    for (const [loadId, pending] of [...this.pendingCargo]) {
      const load = byId.get(loadId);
      const gone = loads.length > 0 && (!load || CARGO_STATUSES_ELSEWHERE.has(load.status ?? ""));
      if (!gone) continue;
      this.pendingCargo.delete(loadId);
      this.telemetry("CARGO_EXPIRED", { loadId, reason: load ? load.status : "NOT_IN_INVENTORY" });
      this.animateCargoExit(pending.item, new Vector3(0.12, 0.12, 0.12));
    }
  }

  private animateCargoEntry(item: CargoItem): void {
    this.cargoAnimator.enter(item.id, item.root);
  }

  private animateCargoExit(item: CargoItem, finalScale: Vector3Type): void {
    this.cargoAnimator.exit(item.id, item.root, finalScale, () => this.disposeCargoVisual(item));
  }

  private createConcreteFloorMaterial(baseColor: string): StandardMaterialType {
    return this.materials.floor("floor", baseColor);
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

  }

  private createMetalMaterial(name: string, hex: string, specularPower: number): StandardMaterialType {
    return this.materials.metal(name, hex, specularPower);
  }

  private createCardboardMaterial(name: string): StandardMaterialType {
    return this.materials.cardboard(name);
  }

  private createWoodMaterial(name: string): StandardMaterialType {
    return this.materials.pallet(name);
  }

  private createMaterial(name: string, hex: string): StandardMaterialType {
    return this.materials.create(name, hex);
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

  private log(message: string): void {
    (window as Window & { __warehouseDiagnostics?: { info: (stage: string, value: unknown) => void } })
      .__warehouseDiagnostics?.info("forklift", message);
  }

  private telemetry(event: string, payload: Record<string, unknown>): void {
    (window as Window & { __warehouseRecordAnimation?: (event: string, payload: Record<string, unknown>) => void })
      .__warehouseRecordAnimation?.(event, payload);
  }

  private disposeWarehouse(): void {
    this.materials.clear();
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
    // The warehouse root is disposed with the scene, so the parked visuals go with
    // it; clearing the map stops a stale entry being claimed after a rebuild.
    this.pendingCargo.clear();
    this.cargoCardboardMaterial = undefined;
    this.cargoPalletMaterial = undefined;
    this.conveyorCargoItems.length = 0;
    this.poseInterpolator.clear();
    // createForklift builds a fresh lift node at y=0; a surviving buffer would drive it to
    // the pre-rebuild height on the first frame, before any telemetry confirms the vehicle
    // still holds anything. applySnapshot re-seeds both in the same tick.
    this.forkInterpolator.clear();
    this.suppressedRebuilds.clear();
    this.wheelMeshes.length = 0;
    this.chargingStations?.dispose();
    this.chargingStations = undefined;
    this.conveyorStations.clear();
    this.defaultConveyorLane = undefined;
    this.inboundStation = undefined;
    this.robotCellRoot = undefined;
    this.robotCellStation = undefined;
    this.armYaw = undefined;
    this.armShoulder = undefined;
    this.armElbow = undefined;
    this.armWrist = undefined;
    this.robotArmAnimator = undefined;
    this.handoffPallet = undefined;
    this.handoffCartonIds = [];
    this.grippedCarton = undefined;
    this.grippedCartonId = undefined;
    this.robotCellPhase = "IDLE";
    this.forkliftAnimator = undefined;
    this.forkliftVisual = undefined;
    this.carriedCargo = undefined;
    this.inboundCardboardMaterial = undefined;
    this.inboundPalletMaterial = undefined;
    this.conveyorCardboardMaterial = undefined;
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
