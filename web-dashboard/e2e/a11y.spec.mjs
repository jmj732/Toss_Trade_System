import { mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

import { REPORTS_DIR, writeRecord } from "./fixtures/record.mjs";
import { primeAuth, ROUTES, STATES, stateRoute } from "./fixtures/states.mjs";

// Accessibility gate. Every route is swept under every state for WCAG 2.2 AA
// violations. Records are still written for the aggregate report, but the sweep
// is now enforced: any violation fails its combination.

const WCAG_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];

for (const routeDef of ROUTES) {
  for (const state of STATES) {
    test(`a11y ${routeDef.name} :: ${state}`, async ({ page, context }, testInfo) => {
      const viewport = testInfo.project.name;
      const key = `${routeDef.name}__${state}__${viewport}`;

      await primeAuth(context, { withConnection: routeDef.name !== "login" });
      await page.route("**/api/v1/**", stateRoute(state, { delayMs: 500 }));

      const withToken = routeDef.name !== "login";
      const target = withToken
        ? `${routeDef.path}#access_token=test-token`
        : routeDef.path;

      await page.goto(target, { waitUntil: "domcontentloaded", timeout: 30000 }).catch(() => {});
      await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
      await page.waitForTimeout(400);

      let violations = [];
      let axeError = "";
      try {
        const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
        violations = results.violations.map(v => ({
          id: v.id,
          impact: v.impact,
          help: v.help,
          nodes: v.nodes.length,
          targets: v.nodes.slice(0, 3).map(n => n.target.join(" "))
        }));
      } catch (err) {
        axeError = err?.message ?? String(err);
      }

      writeRecord("axe", key, {
        route: routeDef.name,
        state,
        viewport,
        axeError,
        violationCount: violations.length,
        violations
      });

      // Enforcement: no WCAG 2.2 AA violation may survive on any combination.
      // The record above is written first so the aggregate report still captures
      // whatever failed here.
      expect(axeError, `axe failed to run for ${key}`).toBe("");
      const summary = violations.map(v => `${v.id} (${v.nodes})`).join(", ");
      expect(violations, `WCAG 2.2 AA violations on ${key}: ${summary}`).toEqual([]);
    });
  }
}

// After every combination is recorded, dump a single aggregate JSON. This runs in
// each worker/project; last writer wins with the full on-disk record set, so the
// file always reflects the complete records/ directory at teardown time.
test.afterAll(async () => {
  const { readdirSync, readFileSync, existsSync } = await import("node:fs");
  const recordsDir = resolve(REPORTS_DIR, "records");
  if (!existsSync(recordsDir)) {
    return;
  }
  const entries = readdirSync(recordsDir).filter(f => f.startsWith("axe__"));
  const all = entries.map(f => JSON.parse(readFileSync(resolve(recordsDir, f), "utf8")));
  mkdirSync(REPORTS_DIR, { recursive: true });
  writeFileSync(resolve(REPORTS_DIR, "axe.json"), JSON.stringify(all, null, 2));
});
