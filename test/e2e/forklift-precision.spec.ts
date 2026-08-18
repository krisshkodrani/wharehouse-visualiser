import { writeFile } from "node:fs/promises";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

/**
 * Records one forklift pick-and-drop cycle for frame-level analysis.
 *
 * This spec asserts almost nothing. Its job is to produce three correlated artefacts
 * so that handling precision can be judged by eye against numbers:
 *
 *   1. a video of the cycle (Playwright, `video: on`),
 *   2. `fork-samples.json` -- fork height, extension, handling phase, carried load and
 *      vehicle pose, each stamped with the page's `performance.now()`,
 *   3. `animation-telemetry.json` -- the scene's own event log, already stamped with
 *      `elapsedMs` from the same clock by `webapp/diagnostics.js`.
 *
 * Sharing one clock is the whole point: `scripts/analyze-handling.mjs` uses those
 * timestamps to pull the video frame for the instant the fork reached a given height or
 * claimed a pallet, instead of scrubbing a three-minute recording by hand.
 *
 * Deliberately opt-in (needs E2E_VIDEO=on) so the ordinary suite and the docker-e2e CI
 * job are unaffected -- this run is slower and produces large artefacts.
 *
 * Runs at speed multiplier 1 on purpose. Faster simulation covers the same motion in
 * fewer rendered frames, which is exactly the detail this is meant to inspect.
 */

interface LoadState { id: string; status: string }
interface JobState { loadId: string; status: string }
interface AgvState {
  id: string;
  x: number; z: number; theta: number;
  status: string;
  handlingPhase?: string;
  forkHeight?: number;
  forkExtension?: number;
  carriedLoadId?: string | null;
  currentStationId?: string | null;
}
interface Snapshot { loads: LoadState[]; jobs: JobState[]; agvs: AgvState[] }

interface ForkSample {
  pageMs: number;
  loadStatus?: string;
  jobStatus?: string;
  agv: Pick<AgvState, "x" | "z" | "theta" | "status" | "handlingPhase" | "forkHeight" | "forkExtension" | "carriedLoadId" | "currentStationId">;
}

const SAMPLE_INTERVAL_MS = 200;
/**
 * Wheel notches of zoom before recording, via E2E_ZOOM_STEPS.
 *
 * Zero by default. Scroll zooms toward the centre of the building rather than toward the
 * vehicle, so eight notches magnified the racks and pushed the forklift off screen
 * entirely. Until the camera can be centred on the vehicle, a wide frame that always
 * contains it beats a close one that sometimes does not.
 */
const ZOOM_STEPS = Number(process.env.E2E_ZOOM_STEPS ?? 0);

test.use({ viewport: { width: 1600, height: 900 }, video: "on" });

async function snapshot(request: APIRequestContext): Promise<Snapshot> {
  const response = await request.get("/api/v1/warehouses/linz/snapshot");
  return response.json() as Promise<Snapshot>;
}

/** The page's own clock, shared with the scene telemetry and the video timeline. */
async function pageMs(page: Page): Promise<number> {
  return page.evaluate(() => Math.round(performance.now()));
}

test("records a forklift pick and drop for frame-level analysis", async ({ page, request }, testInfo) => {
  test.setTimeout(360_000);
  test.skip(!process.env.E2E_BASE_URL, "This scenario requires the live Docker application.");
  test.skip(process.env.E2E_VIDEO !== "on", "Analysis run. Set E2E_VIDEO=on to record it.");

  const browserMessages: string[] = [];
  page.on("console", (message) => browserMessages.push(`[console:${message.type()}] ${message.text()}`));
  page.on("pageerror", (error) => browserMessages.push(`[pageerror] ${error.stack || error.message}`));

  // An empty warehouse is what makes this measurable: one pallet, one vehicle, no competing
  // orders to interleave with the cycle. Reset gives exactly that.
  await request.post("/api/v1/warehouses/linz/operations/reset", { data: {} });
  await request.post("/api/v1/warehouses/linz/operations/speed", { data: { multiplier: 1 } });

  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator("canvas.warehouseCanvas")).toBeVisible();

  // Reset also clears the scenario selection, so the UI opens the "Choose today's warehouse
  // story" dialog over the middle of the warehouse -- the first run of this spec recorded a
  // modal instead of a forklift.
  //
  // Hide it rather than answering it. Choosing a story seeds inventory and transport orders
  // that fight this spec's pallet for the only forklift, and cancelling those afterwards
  // leaves the vehicle holding a cancelled load and never going idle. Suppressing the
  // overlay keeps the warehouse pristine and the camera unobstructed; the dialog is UI
  // chrome, and nothing under test lives in it.
  // A style rule rather than hiding the node once: the controller opens seedDialog after the
  // first snapshot arrives, so a one-shot evaluate runs too early and hides nothing.
  await page.addStyleTag({
    content: ".seedDialog, [id^='sap-ui-blocklayer'] { display: none !important; }"
  });
  await expect(page.locator(".seedDialog:visible")).toHaveCount(0);
  await page.waitForTimeout(1_500);

  // Zoom in. The default camera frames the whole building, which renders the forklift about
  // forty pixels tall -- far too small to judge whether a pallet sits on the forks or floats
  // above them, which is the entire point of the recording. The scene keeps the active
  // vehicle framed, so zooming does not lose it.
  const canvas = page.locator("canvas.warehouseCanvas");
  await canvas.hover();
  for (let step = 0; step < ZOOM_STEPS; step++) {
    await page.mouse.wheel(0, -240);
    await page.waitForTimeout(120);
  }
  await page.waitForTimeout(800);

  // Clock calibration. Playwright starts recording when the browser context is created,
  // which is before `performance.now()` starts at navigation, so an event at page time T
  // appears somewhere after T in the video. The offset is real -- the first usable run
  // pulled a pre-pick frame for CARGO_ATTACHED -- and is not derivable from the logs.
  //
  // So mark it: black out the viewport for a moment and record the page time. The frame
  // where luma collapses is that instant, which gives the analyzer an exact offset.
  const calibrationPageMs = await page.evaluate((holdMs) => {
    const shade = document.createElement("div");
    shade.setAttribute("data-calibration-flash", "");
    shade.style.cssText = "position:fixed;inset:0;background:#000;z-index:2147483647;pointer-events:none";
    document.body.appendChild(shade);
    const at = Math.round(performance.now());
    window.setTimeout(() => shade.remove(), holdMs);
    return at;
  }, 400);
  await page.waitForTimeout(1_200);

  const received = await request.post("/api/v1/warehouses/linz/inbound-loads", {
    data: { sku: "E2E-PRECISION-PALLET", quantity: 1 }
  });
  expect(received.ok()).toBeTruthy();
  const loadId = ((await received.json()) as { loads: LoadState[] }).loads[0]?.id;
  expect(loadId).toBeTruthy();

  const accepted = await request.post("/api/v1/warehouses/linz/putaway-requests", {
    data: { inboundLoadIds: [loadId], operatorPrompt: "Store this pallet in the nearest eligible storage slot." }
  });
  expect(accepted.ok()).toBeTruthy();

  // Fail fast if the warehouse is not ours alone. reset() above clears the scenario, so
  // anything that re-seeds one deletes this pallet and its order, and the sampling loop
  // would then spend four minutes watching a load that no longer exists. One forklift,
  // one backend, one database -- see workers: 1 in playwright.config.ts.
  await expect.poll(async () => {
    const current = await snapshot(request);
    if (!current.loads.some((entry) => entry.id === loadId)) return "PALLET_VANISHED";
    return current.jobs.some((job) => job.loadId === loadId) ? "PLANNED" : "PLANNING";
  }, {
    timeout: 45_000,
    intervals: [500],
    message: `no task was planned for ${loadId}. PALLET_VANISHED means another spec or a browser `
      + `re-seeded the scenario and wiped it -- run this alone against an idle stack.`
  }).toBe("PLANNED");

  // Sample until the pallet is stored. Every sample carries the page clock, so each one
  // can be resolved to a video frame later.
  const samples: ForkSample[] = [];
  const deadline = Date.now() + 240_000;
  let stored = false;
  while (Date.now() < deadline && !stored) {
    const [current, ms] = await Promise.all([snapshot(request), pageMs(page)]);
    const agv = current.agvs.find((entry) => entry.id === "FL-01");
    const load = current.loads.find((entry) => entry.id === loadId);
    if (agv) {
      samples.push({
        pageMs: ms,
        loadStatus: load?.status,
        jobStatus: current.jobs.find((job) => job.loadId === loadId)?.status,
        agv: {
          x: agv.x, z: agv.z, theta: agv.theta, status: agv.status,
          handlingPhase: agv.handlingPhase, forkHeight: agv.forkHeight,
          forkExtension: agv.forkExtension, carriedLoadId: agv.carriedLoadId,
          currentStationId: agv.currentStationId
        }
      });
    }
    stored = load?.status === "STORED";
    if (!stored) await page.waitForTimeout(SAMPLE_INTERVAL_MS);
  }

  // Let the drop animation finish inside the recording rather than cutting on the
  // frame the status flips.
  await page.waitForTimeout(3_000);

  const telemetry = await page.evaluate(() =>
    (window as Window & { __warehouseAnimationTelemetry?: unknown[] }).__warehouseAnimationTelemetry ?? []);

  // Written through outputPath and attached by path, not by body: a body attachment is
  // held in the reporter's own store, so it never lands next to video.webm and
  // scripts/analyze-handling.mjs cannot pair the two. Writing the file first puts both
  // artefacts in the same directory under stable names.
  const write = async (name: string, contents: string, contentType: string) => {
    const target = testInfo.outputPath(name);
    await writeFile(target, contents, "utf8");
    await testInfo.attach(name, { path: target, contentType });
  };

  await write("fork-samples.json",
    JSON.stringify({ loadId, sampleIntervalMs: SAMPLE_INTERVAL_MS, calibrationPageMs, samples }, null, 2),
    "application/json");
  await write("animation-telemetry.json", JSON.stringify(telemetry, null, 2), "application/json");
  await write("browser-log.txt", browserMessages.join("\n"), "text/plain");

  // The only hard failures: the page must not have thrown, and the pallet must have
  // completed the journey, or the recording shows nothing worth analysing.
  const fatal = browserMessages.filter((message) => message.includes("[pageerror]"));
  expect(fatal, fatal.join("\n")).toEqual([]);
  expect(stored, `pallet ${loadId} never reached STORED; nothing to analyse`).toBe(true);
  expect(samples.some((sample) => sample.agv.carriedLoadId === loadId),
    "the vehicle never reported carrying the pallet, so no pick was recorded").toBe(true);
});
