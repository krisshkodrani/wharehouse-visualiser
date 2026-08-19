import { cpSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const runId = new Date().toISOString().replace(/[:.]/g, "-").replace(/T/g, "_").replace(/Z$/i, "");
const recordingRoot = resolve(appRoot, "artifacts", "e2e-runs", runId);
const resultsPath = resolve(recordingRoot, "playwright-results.json");
const summaryPath = resolve(recordingRoot, "run-summary.json");
const startTime = new Date().toISOString();

const command = ["playwright", "test", "test/e2e/package-lifecycle.spec.ts"];

mkdirSync(recordingRoot, { recursive: true });

const nodeEnv = {
  ...process.env,
  E2E_BASE_URL: process.env.E2E_BASE_URL || "http://localhost:8080",
  E2E_JSON_REPORT_PATH: resultsPath
};

// `npx` is a .cmd shim on Windows, which spawnSync cannot resolve without a shell.
// Inherit stdio so a multi-minute run reports progress instead of going silent; the
// Playwright JSON report below is what the summary actually reads.
const result = spawnSync("npx", command, {
  cwd: appRoot,
  env: nodeEnv,
  encoding: "utf8",
  shell: process.platform === "win32",
  stdio: ["ignore", "inherit", "inherit"]
});

let reportSummary = null;
if (existsSync(resultsPath)) {
  try {
    const report = JSON.parse(readFileSync(resultsPath, "utf8"));
    // Playwright's stats carry expected/unexpected/flaky/skipped -- there is no `tests`
    // key, so the total has to be summed rather than read.
    const stats = report.stats ?? {};
    const passed = stats.expected ?? 0;
    const failed = stats.unexpected ?? 0;
    const flaky = stats.flaky ?? 0;
    const skipped = stats.skipped ?? 0;
    reportSummary = {
      suites: report.suites?.length ?? 0,
      tests: passed + failed + flaky + skipped,
      passed,
      failed,
      flaky,
      skipped,
      durationMs: stats.duration ?? 0
    };
  } catch {
    reportSummary = null;
  }
}

const copyRunArtifacts = (source, destination) => {
  const absoluteSource = resolve(appRoot, source);
  const absoluteDestination = resolve(recordingRoot, destination);
  if (existsSync(absoluteSource)) {
    cpSync(absoluteSource, absoluteDestination, { recursive: true });
  }
};

copyRunArtifacts("playwright-report", "playwright-report");
copyRunArtifacts("test-results", "test-results");

try {
  const composeLogs = spawnSync("docker", ["compose", "logs", "--no-color"], {
    cwd: appRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"]
  });
  if (composeLogs.stdout) {
    writeFileSync(resolve(recordingRoot, "docker-compose.log"), composeLogs.stdout);
  }
} catch {
  // Optional diagnostics capture; keep best effort.
}

let gitSha = "unknown";
try {
  const git = spawnSync("git", ["rev-parse", "HEAD"], {
    cwd: appRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"]
  });
  if (git.status === 0 && git.stdout?.trim()) {
    gitSha = git.stdout.trim();
  }
} catch {
  // Non-critical metadata for analysis; continue without repo revision.
}

const summary = {
  runId,
  suite: "package-lifecycle",
  startTime,
  endTime: new Date().toISOString(),
  command: `npx ${command.join(" ")}`,
  baseUrl: nodeEnv.E2E_BASE_URL,
  exitCode: result.status ?? 1,
  signal: result.signal,
  gitSha,
  report: reportSummary,
  spawnError: result.error ? String(result.error.message) : null
};

writeFileSync(summaryPath, JSON.stringify(summary, null, 2));

if (summaryPath) {
  process.stdout.write(`e2e package-run recorded in ${recordingRoot}\n`);
  process.stdout.write(`summary: ${summaryPath}\n`);
}

if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

