import { defineConfig, devices } from "@playwright/test";

// UI harness config. The UI has been recovered and its screens approved, so the
// state matrix now compares against a committed visual baseline; the a11y sweep
// is likewise enforced as a gate.
const VIEWPORTS = [
  { name: "vp-360", width: 360, height: 800 },
  { name: "vp-768", width: 768, height: 1024 },
  { name: "vp-1280", width: 1280, height: 800 },
  { name: "vp-1440", width: 1440, height: 900 }
];

export default defineConfig({
  testDir: "e2e",
  // The audit is meant to surface failures, not gate on them, so keep going.
  fullyParallel: true,
  forbidOnly: false,
  retries: 0,
  workers: 2,
  reporter: [["list"], ["json", { outputFile: "e2e/__reports__/results.json" }]],
  use: {
    baseURL: "http://localhost:3000",
    screenshot: "on",
    trace: "on-first-retry",
    video: "off"
  },
  projects: VIEWPORTS.map(vp => ({
    name: vp.name,
    use: {
      ...devices["Desktop Chrome"],
      viewport: { width: vp.width, height: vp.height }
    }
  })),
  webServer: {
    command: "npm run dev",
    port: 3000,
    reuseExistingServer: true,
    timeout: 120000
  }
});
