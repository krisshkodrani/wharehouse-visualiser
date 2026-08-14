import { defineConfig, devices } from "@playwright/test";

const externalBaseUrl = process.env.E2E_BASE_URL;
const recordVideo = process.env.E2E_VIDEO === "on";

export default defineConfig({
  testDir: "./test/e2e",
  timeout: 120_000,
  expect: { timeout: 60_000 },
  reporter: [["list"], ["html", { open: "never" }]],
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
