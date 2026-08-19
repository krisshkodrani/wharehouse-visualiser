import { defineConfig, devices } from "@playwright/test";

const externalBaseUrl = process.env.E2E_BASE_URL;
const recordVideo = process.env.E2E_VIDEO === "on";
const jsonReportPath = process.env.E2E_JSON_REPORT_PATH;
const reporters = [["list"], ["html", { open: "never" }]];

if (jsonReportPath) {
  reporters.push(["json", { outputFile: jsonReportPath }]);
}

export default defineConfig({
  testDir: "./test/e2e",
  // Every spec drives the same physical resources: one simulated forklift, one backend, one
  // database. operations-video additionally waits on a wall-clock flow (a load reaching
  // ON_CONVEYOR within 180s), so a browser-heavy spec rendering Babylon in a second worker
  // starves the backend and simulator containers and the wait expires. Serial execution is
  // the correct model for a single-vehicle fleet, and it is also faster than the parallel
  // run once retries are taken into account.
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 60_000 },
  reporter: reporters,
  use: {
    baseURL: externalBaseUrl || "http://localhost:8082",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: recordVideo ? "on" : "retain-on-failure"
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: externalBaseUrl ? undefined : {
    command: "npx ui5 serve --port 8082",
    url: "http://localhost:8082/index.html",
    reuseExistingServer: true,
    timeout: 120_000
  }
});
