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

// globals.css ships a `prefers-color-scheme: dark` token set, so every screen has
// two renderings and only one of them was ever gated. The scheme is a second
// project axis rather than a second spec: contrast, overflow and the visual
// baseline all have to hold in both.
//
// The light projects keep their original bare names on purpose. Playwright derives
// a snapshot's filename from the project name, so renaming them would orphan all
// 192 approved light baselines; dark runs land on new `-dark` filenames instead and
// leave the existing ones byte-identical.
const SCHEMES = [
  { suffix: "", colorScheme: "light" },
  { suffix: "-dark", colorScheme: "dark" }
];

const E2E_PORT = Number(process.env.TRADE_E2E_PORT ?? 3107);

export default defineConfig({
  testDir: "e2e",
  // The audit is meant to surface failures, not gate on them, so keep going.
  fullyParallel: true,
  forbidOnly: false,
  retries: 0,
  // 8 projects x 8 routes x 6 states x 2 specs = 768 combinations. Two workers
  // was tuned for half that matrix; this machine has 18 cores.
  workers: 6,
  reporter: [["list"], ["json", { outputFile: "e2e/__reports__/results.json" }]],
  use: {
    baseURL: `http://localhost:${E2E_PORT}`,
    screenshot: "on",
    trace: "on-first-retry",
    video: "off"
  },
  projects: VIEWPORTS.flatMap(vp => SCHEMES.map(scheme => ({
    name: `${vp.name}${scheme.suffix}`,
    use: {
      ...devices["Desktop Chrome"],
      viewport: { width: vp.width, height: vp.height },
      colorScheme: scheme.colorScheme
    }
  }))),
  webServer: {
    command: `npm run dev -- --port ${E2E_PORT}`,
    port: E2E_PORT,
    reuseExistingServer: false,
    timeout: 120000
  }
});
