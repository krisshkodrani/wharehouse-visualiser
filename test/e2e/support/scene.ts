import type { Page } from "@playwright/test";

/**
 * A window into the running Babylon scene, for tests that need ground truth rather than
 * telemetry.
 *
 * <p>The Babylon layer has no unit coverage -- `unitTests.qunit.ts` covers only the pure
 * modules -- and every defect found in it so far was invisible to the existing suite: a
 * pallet rendered twice for 19.7 s, staged pallets silently losing their material to a
 * shared-material disposal, a mast that stepped instead of moving because only the chassis
 * was interpolated. None of those change any status field, so nothing that reads the API
 * can see them. They are all visible in the scene graph.
 *
 * <p>This is the technique that caught the duplicate: reach into `sceneController` through
 * `page.evaluate` and measure. It is deliberately read-only and returns plain data, so a
 * refactor is free to move meshes between files as long as the observable scene still holds.
 */

const VIEWPORT_ID = "container-warehouseVisualizer---main--viewport";

export interface CargoOwnership {
  /** Cargo root node names that exist more than once -- one pallet drawn twice. */
  duplicateNodes: string[];
  /** Load ids owned by a slot and the fork at the same time. */
  doubleOwned: string[];
  /** Cargo meshes rendering with no material, which Babylon draws in default white. */
  untexturedMeshes: string[];
  /** Load ids seen on the fork, so a caller can prove the window under test was entered. */
  carriedIds: string[];
  /** Highest number of cargo nodes seen at once, to prove the probe was reading anything. */
  peakCargoNodes: number;
}

/** Sample the scene continuously and keep the worst reading of each invariant.
 *
 * <p>Continuous rather than point-in-time on purpose: every one of these defects is
 * transient. The duplicate lasted only until the load's status caught up, so a probe that
 * looked once could step straight over it. */
export async function installCargoProbe(page: Page): Promise<void> {
  await page.evaluate((viewportId) => {
    const existing = (window as unknown as { __sceneProbe?: unknown }).__sceneProbe;
    if (existing) return;
    const control = (window as unknown as {
      sap: { ui: { getCore(): { byId(id: string): { sceneController?: unknown } | undefined } } };
    }).sap.ui.getCore().byId(viewportId);
    const scene = (control?.sceneController ?? {}) as {
      scene: { transformNodes: { name: string }[]; meshes: { name: string; material?: unknown }[] };
      cargoItems: { id: string }[];
      inboundCargoItems: { id: string }[];
      conveyorCargoItems: { id: string }[];
      carriedCargo?: { id: string };
    };
    const worst = { duplicateNodes: [], doubleOwned: [], untexturedMeshes: [], carriedIds: [], peakCargoNodes: 0 } as {
      duplicateNodes: string[]; doubleOwned: string[]; untexturedMeshes: string[];
      carriedIds: string[]; peakCargoNodes: number;
    };
    (window as unknown as { __sceneProbe: unknown }).__sceneProbe = worst;
    const note = (list: string[], value: string) => { if (!list.includes(value)) list.push(value); };
    window.setInterval(() => {
      const counts = new Map<string, number>();
      for (const node of scene.scene.transformNodes)
        if (/^(cargo|inboundCargo|shippingCargo)-/.test(node.name))
          counts.set(node.name, (counts.get(node.name) ?? 0) + 1);
      worst.peakCargoNodes = Math.max(worst.peakCargoNodes, counts.size);
      for (const [name, seen] of counts) if (seen > 1) note(worst.duplicateNodes, name);
      // A cargo mesh with no material is drawn in Babylon's default white. This is how a
      // shared material disposed by one item's teardown shows up on every other item.
      for (const mesh of scene.scene.meshes)
        if (/^(inboundBox|inboundPallet|shippingBox|liveCarried|.*-crate)/.test(mesh.name) && !mesh.material)
          note(worst.untexturedMeshes, mesh.name);
      const carried = scene.carriedCargo?.id;
      if (carried) {
        note(worst.carriedIds, carried);
        const alsoInASlot = [...scene.cargoItems, ...scene.inboundCargoItems].some((item) => item.id === carried);
        if (alsoInASlot) note(worst.doubleOwned, carried);
      }
    }, 100);
  }, VIEWPORT_ID);
}

export async function readCargoProbe(page: Page): Promise<CargoOwnership> {
  return page.evaluate(() => (window as unknown as { __sceneProbe: CargoOwnership }).__sceneProbe);
}

/** Records rendered fork height once per animation frame, in the background.
 *
 * <p>Per frame rather than on a timer because the question is whether the rendered mast
 * updates *between* telemetry arrivals: the backend coalesces handling telemetry and drains
 * it every 50 ms, so 20 Hz is a hard ceiling on how often a value assigned straight from
 * telemetry can change. Anything faster can only come from interpolation.
 *
 * <p>Background rather than blocking because a caller cannot know in advance when the lift
 * it wants will happen -- the fleet works through its own queue first, and a blocking
 * sampler simply expires while the vehicle is elsewhere. */
export async function installForkRecorder(page: Page): Promise<void> {
  await page.evaluate((viewportId) => {
    if ((window as unknown as { __forkFrames?: unknown }).__forkFrames) return;
    const control = (window as unknown as {
      sap: { ui: { getCore(): { byId(id: string): { sceneController?: unknown } | undefined } } };
    }).sap.ui.getCore().byId(viewportId);
    const sc = control?.sceneController as { forkliftLift?: { position: { y: number } } };
    const frames: { t: number; y: number }[] = [];
    (window as unknown as { __forkFrames: unknown }).__forkFrames = frames;
    const started = performance.now();
    const tick = () => {
      if (sc.forkliftLift) frames.push({ t: performance.now() - started, y: sc.forkliftLift.position.y });
      if (frames.length > 60000) frames.shift();
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }, VIEWPORT_ID);
}

export async function readForkFrames(page: Page): Promise<{ t: number; y: number }[]> {
  return page.evaluate(() => (window as unknown as { __forkFrames: { t: number; y: number }[] }).__forkFrames);
}

/** Peak height reached, and the largest jump between consecutive rendered frames.
 *
 * <p>Deliberately not a measure of interpolation. The obvious metric -- how many distinct
 * heights appear per second -- cannot separate interpolated from stepped motion here,
 * because the test browser renders this scene at roughly 22 fps while handling telemetry is
 * capped at 20 Hz by the backend's 50 ms drain. When frame rate and message rate are that
 * close, both cases update on almost every frame and no frame-based count discriminates.
 *
 * <p>The largest single-frame jump does capture what a viewer actually complained about: a
 * mast that arrives rather than travels. A teleport is one jump the size of the whole lift;
 * motion of any kind is many small ones. */
export function forkTravel(samples: { t: number; y: number }[]): { peak: number; largestJump: number } {
  let largestJump = 0;
  for (let index = 1; index < samples.length; index += 1) {
    const jump = Math.abs(samples[index].y - samples[index - 1].y);
    if (jump > largestJump) largestJump = jump;
  }
  return { peak: samples.reduce((high, entry) => Math.max(high, entry.y), 0), largestJump };
}

/** World-space extents of named meshes, for the geometry invariants. */
export async function meshBounds(page: Page, pattern: string): Promise<
  { name: string; x: [number, number]; y: [number, number]; z: [number, number] }[]> {
  return page.evaluate(([viewportId, source]) => {
    const control = (window as unknown as {
      sap: { ui: { getCore(): { byId(id: string): { sceneController?: unknown } | undefined } } };
    }).sap.ui.getCore().byId(viewportId as string);
    const sc = control?.sceneController as { scene: { meshes: Record<string, unknown>[] } };
    const round = (value: number) => Math.round(value * 100) / 100;
    const test = new RegExp(source as string);
    return sc.scene.meshes.filter((mesh) => test.test(String(mesh.name))).map((mesh) => {
      const m = mesh as unknown as {
        name: string; computeWorldMatrix(force: boolean): void;
        getBoundingInfo(): { boundingBox: { minimumWorld: { x: number; y: number; z: number };
          maximumWorld: { x: number; y: number; z: number } } };
      };
      m.computeWorldMatrix(true);
      const box = m.getBoundingInfo().boundingBox;
      return {
        name: m.name,
        x: [round(box.minimumWorld.x), round(box.maximumWorld.x)] as [number, number],
        y: [round(box.minimumWorld.y), round(box.maximumWorld.y)] as [number, number],
        z: [round(box.minimumWorld.z), round(box.maximumWorld.z)] as [number, number]
      };
    });
  }, [VIEWPORT_ID, pattern] as [string, string]);
}
