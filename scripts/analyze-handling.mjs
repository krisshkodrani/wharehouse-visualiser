#!/usr/bin/env node
/**
 * Turns a recorded forklift run into labelled still frames for visual inspection.
 *
 * `forklift-precision.spec.ts` records a video and two JSON logs whose timestamps come
 * from the same page clock (`performance.now()`). This script picks the interesting
 * moments out of those logs and asks ffmpeg for the matching video frames, so handling
 * precision can be judged frame by frame instead of by scrubbing the recording.
 *
 * Usage:
 *   node scripts/analyze-handling.mjs [--results test-results] [--out artifacts/handling]
 *                                     [--window 600] [--step 300] [--fps 2]
 *
 *   --window  ms either side of each moment to sample (default 600)
 *   --step    ms between frames inside that window (default 300)
 *   --fps     frames per second for the whole-run contact sheet (default 2)
 *
 * Clock alignment: Playwright starts recording when the browser context is created, before
 * `performance.now()` starts at navigation, so an event at page time T appears later in the
 * video. The spec blacks out the viewport once and records the page time; this script finds
 * that frame by luma collapse and uses it to convert page time to video time exactly. A
 * window of frames is still taken around each moment, because 25 fps leaves ~40 ms of slack
 * and animation easing means the interesting frame is sometimes just after the event.
 */

import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  return index === -1 ? fallback : args[index + 1];
};

const resultsDir = resolve(flag("results", "test-results"));
const outDir = resolve(flag("out", join("artifacts", "handling")));
const windowMs = Number(flag("window", 600));
const stepMs = Number(flag("step", 300));
const sheetFps = Number(flag("fps", 2));

/** Scene events worth a still. Everything else is noise for handling precision. */
const KEY_EVENTS = new Set([
  "CARGO_ATTACHED",
  "CARGO_DETACHED",
  "CARGO_ORPHANED",
  "CARGO_ADOPTED",
  "CARGO_EXPIRED",
  "LIVE_COLLISION_DETECTED",
  "ROBOT_TARGET_OUT_OF_REACH"
]);

function findFiles(dir, predicate, found = []) {
  if (!existsSync(dir)) return found;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) findFiles(full, predicate, found);
    else if (predicate(entry.name)) found.push(full);
  }
  return found;
}

function newest(paths) {
  return paths.map((path) => ({ path, mtime: statSync(path).mtimeMs }))
    .sort((a, b) => b.mtime - a.mtime)[0]?.path;
}

function ffmpeg(fnArgs) {
  const result = spawnSync("ffmpeg", fnArgs, { encoding: "utf8" });
  if (result.error) {
    console.error("ffmpeg not found on PATH. Install it, or add it to PATH, then re-run.");
    process.exit(1);
  }
  return result;
}

/** Single frame at an absolute video time, via a fast seek before the input. */
function grabFrame(video, seconds, target) {
  const result = ffmpeg(["-y", "-ss", seconds.toFixed(3), "-i", video, "-frames:v", "1", "-q:v", "2", target]);
  return result.status === 0 && existsSync(target);
}

/**
 * Video time of the calibration flash, or null.
 *
 * Reads per-frame average luma over the opening seconds and returns the darkest frame.
 * The spec covers the whole viewport in black, so that frame is unambiguous against a lit
 * 3D scene -- no image decoding needed here, ffmpeg reports the statistic.
 */
function findCalibrationFlash(video, searchSeconds = 40) {
  const result = ffmpeg([
    "-i", video, "-t", String(searchSeconds),
    "-vf", "signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=-",
    "-f", "null", "-"
  ]);
  const text = `${result.stdout || ""}\n${result.stderr || ""}`;
  const frames = [];
  let pending = null;
  for (const line of text.split(/\r?\n/)) {
    const time = line.match(/pts_time:([0-9.]+)/);
    if (time) { pending = Number(time[1]); continue; }
    const luma = line.match(/lavfi\.signalstats\.YAVG=([0-9.]+)/);
    if (luma && pending !== null) { frames.push({ seconds: pending, luma: Number(luma[1]) }); pending = null; }
  }
  if (frames.length < 10) return null;
  const darkest = frames.reduce((a, b) => (b.luma < a.luma ? b : a));
  const median = [...frames].sort((a, b) => a.luma - b.luma)[Math.floor(frames.length / 2)].luma;
  // Require a real collapse, not just a dim frame, so a missing flash is reported rather
  // than silently producing a bogus offset.
  if (darkest.luma > median - 20) return null;
  return darkest;
}

const video = newest(findFiles(resultsDir, (name) => name.endsWith(".webm")));
const samplesFile = newest(findFiles(resultsDir, (name) => name === "fork-samples.json"));
const telemetryFile = newest(findFiles(resultsDir, (name) => name === "animation-telemetry.json"));

if (!video) {
  console.error(`No .webm under ${resultsDir}.`);
  console.error("Record one first:  E2E_BASE_URL=http://localhost:8080 E2E_VIDEO=on npx playwright test test/e2e/forklift-precision.spec.ts");
  process.exit(1);
}

console.log(`video     : ${relative(process.cwd(), video)}`);
console.log(`samples   : ${samplesFile ? relative(process.cwd(), samplesFile) : "(none)"}`);
console.log(`telemetry : ${telemetryFile ? relative(process.cwd(), telemetryFile) : "(none)"}`);

rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });

// --- moments worth a still -------------------------------------------------------

const moments = [];

if (telemetryFile) {
  const telemetry = JSON.parse(readFileSync(telemetryFile, "utf8"));
  for (const entry of telemetry) {
    if (!KEY_EVENTS.has(entry.event)) continue;
    if (typeof entry.elapsedMs !== "number") continue;
    moments.push({
      pageMs: entry.elapsedMs,
      label: entry.event,
      detail: JSON.stringify(entry.payload ?? {})
    });
  }
}

let samples = [];
let calibrationPageMs = null;
if (samplesFile) {
  const parsed = JSON.parse(readFileSync(samplesFile, "utf8"));
  samples = parsed.samples ?? [];
  calibrationPageMs = typeof parsed.calibrationPageMs === "number" ? parsed.calibrationPageMs : null;

  // Transitions in the physical handling state are exactly the frames to look at.
  const changed = (a, b) => a?.agv.handlingPhase !== b?.agv.handlingPhase
    || a?.agv.carriedLoadId !== b?.agv.carriedLoadId
    || a?.loadStatus !== b?.loadStatus;
  for (let i = 1; i < samples.length; i++) {
    if (!changed(samples[i - 1], samples[i])) continue;
    const s = samples[i];
    moments.push({
      pageMs: s.pageMs,
      label: `PHASE_${s.agv.handlingPhase ?? "NONE"}`,
      detail: `load=${s.loadStatus} carried=${s.agv.carriedLoadId ?? "-"} forkH=${s.agv.forkHeight ?? "-"} forkX=${s.agv.forkExtension ?? "-"}`
    });
  }

  // Peak fork height: the pallet is at its most precarious, and any float or sink
  // between fork and pallet shows up most clearly here.
  const withHeight = samples.filter((s) => typeof s.agv.forkHeight === "number");
  if (withHeight.length) {
    const peak = withHeight.reduce((a, b) => (b.agv.forkHeight > a.agv.forkHeight ? b : a));
    moments.push({
      pageMs: peak.pageMs,
      label: "FORK_PEAK",
      detail: `forkH=${peak.agv.forkHeight} forkX=${peak.agv.forkExtension} carried=${peak.agv.carriedLoadId ?? "-"}`
    });
  }
}

moments.sort((a, b) => a.pageMs - b.pageMs);

// --- clock alignment ------------------------------------------------------------

let offsetMs = 0;
if (calibrationPageMs !== null) {
  const flash = findCalibrationFlash(video);
  if (flash) {
    offsetMs = Math.round(flash.seconds * 1000 - calibrationPageMs);
    console.log(`\ncalibration: flash at video ${flash.seconds.toFixed(3)}s (luma ${flash.luma.toFixed(1)})`
      + ` == page ${calibrationPageMs}ms  ->  offset ${offsetMs >= 0 ? "+" : ""}${offsetMs}ms`);
  } else {
    console.warn("\ncalibration: no flash found in the opening seconds; frames may be early by ~1-3s.");
  }
} else {
  console.warn("\ncalibration: this recording predates the flash marker; frames may be early by ~1-3s.");
}

const videoSeconds = (pageMs) => (pageMs + offsetMs) / 1000;

// --- frames ---------------------------------------------------------------------

const offsets = [];
for (let d = -windowMs; d <= windowMs; d += stepMs) offsets.push(d);

const index = [];
let written = 0;
for (const [n, moment] of moments.entries()) {
  const stem = `${String(n).padStart(2, "0")}_${String(moment.label).toLowerCase()}`;
  for (const offset of offsets) {
    const seconds = videoSeconds(moment.pageMs + offset);
    if (seconds < 0) continue;
    const sign = offset < 0 ? "m" : "p";
    const name = `${stem}_${sign}${String(Math.abs(offset)).padStart(4, "0")}ms.jpg`;
    if (grabFrame(video, seconds, join(outDir, name))) {
      written++;
      index.push({ file: name, event: moment.label, pageMs: moment.pageMs, offsetMs: offset, detail: moment.detail });
    }
  }
}

// Whole-run contact sheet, for orientation and for spotting anything the logs missed.
const sheetDir = join(outDir, "timeline");
mkdirSync(sheetDir, { recursive: true });
ffmpeg(["-y", "-i", video, "-vf", `fps=${sheetFps}`, "-q:v", "4", join(sheetDir, "t_%04d.jpg")]);
const sheetCount = readdirSync(sheetDir).length;

// --- fork travel, as text -------------------------------------------------------

let profile = "";
if (samples.length) {
  const rows = samples
    .filter((s, i) => i === 0 || s.agv.handlingPhase !== samples[i - 1].agv.handlingPhase
      || Math.abs((s.agv.forkHeight ?? 0) - (samples[i - 1].agv.forkHeight ?? 0)) > 0.01)
    .map((s) => [
      String(s.pageMs).padStart(7),
      (s.agv.handlingPhase ?? "-").padEnd(12),
      (s.agv.forkHeight ?? 0).toFixed(3).padStart(7),
      (s.agv.forkExtension ?? 0).toFixed(3).padStart(7),
      (s.agv.carriedLoadId ?? "-").padEnd(12),
      (s.loadStatus ?? "-").padEnd(12),
      `${s.agv.x.toFixed(2)},${s.agv.z.toFixed(2)}`
    ].join("  "));
  profile = ["   pageMs  phase         forkH    forkX  carried       load          x,z", ...rows].join("\n");
}

writeFileSync(join(outDir, "index.json"), JSON.stringify({ video, moments, frames: index }, null, 2));
if (profile) writeFileSync(join(outDir, "fork-profile.txt"), profile + "\n");

console.log(`\nmoments   : ${moments.length}`);
console.log(`frames    : ${written} in ${relative(process.cwd(), outDir)}`);
console.log(`timeline  : ${sheetCount} frames at ${sheetFps} fps in ${relative(process.cwd(), sheetDir)}`);
if (profile) console.log(`profile   : ${relative(process.cwd(), join(outDir, "fork-profile.txt"))}`);
console.log("\nMoments:");
for (const [n, m] of moments.entries()) {
  console.log(`  ${String(n).padStart(2, "0")}  ${String(m.pageMs).padStart(7)}ms  ${m.label.padEnd(24)} ${m.detail}`);
}
