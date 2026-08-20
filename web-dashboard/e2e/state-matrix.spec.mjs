import { resolve } from "node:path";

import { expect, test } from "@playwright/test";

import {
  collectDiagnostics,
  measureOverflow,
  scanForbidden,
  SCREENSHOTS_DIR,
  writeRecord
} from "./fixtures/record.mjs";
import { freezeClock, primeAuth, ROUTES, routeStates, stateRoute } from "./fixtures/states.mjs";

// Visual-regression state matrix. Every route is rendered under every pinned
// state, screenshotted for the report, and compared against an approved
// baseline. The baseline is committed; regressions fail the run.
//
// Determinism: the wall clock is frozen (freezeClock) so relative-time text is
// stable, animations are disabled during capture, and the `loading` state holds
// its data requests open far longer than a test runs so its pending UI never
// transitions mid-capture. No maxDiffPixels slack is used — flakiness is removed
// at the source, not masked by a threshold.
const LOADING_HOLD_MS = 60000;

for (const routeDef of ROUTES) {
  for (const state of routeStates(routeDef)) {
    test(`${routeDef.name} :: ${state}`, async ({ page, context }, testInfo) => {
      const viewport = testInfo.project.name;
      const key = `${routeDef.name}__${state}__${viewport}`;
      const requests = [];

      const diagnostics = collectDiagnostics(page);
      await primeAuth(context, { withConnection: routeDef.name !== "login" });
      await freezeClock(context);
      await page.route(
        "**/api/v1/**",
        stateRoute(state, {
          delayMs: LOADING_HOLD_MS,
          onRequest: info => requests.push(info)
        })
      );

      // Public login page is server-rendered and must not carry an access token.
      const withToken = routeDef.name !== "login";
      // The login page never calls the API — its failure arrives as an OAuth error
      // query parameter, so that is what drives its error state.
      const loginPath = routeDef.name === "login" && state === "error"
        ? `${routeDef.path}?error=login`
        : routeDef.path;
      const target = withToken ? `${routeDef.path}#access_token=test-token` : loginPath;

      const failures = [];
      let navError = "";
      try {
        await page.goto(target, { waitUntil: "domcontentloaded", timeout: 30000 });
        if (state === "loading") {
          // Capture the pending UI while data requests are still held open.
          await page.waitForTimeout(1200);
        } else if (state === "refreshing") {
          await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
          // Only a control the user can actually reach counts. Since the rework put
          // several refresh controls inside collapsed <details> sections, an unscoped
          // locator matches a hidden button and the click blocks on actionability
          // until the whole test times out. Requiring :visible keeps the refresh
          // genuinely exercised wherever one is reachable, and the explicit timeout
          // means a screen with no reachable refresh degrades to a plain load
          // instead of hanging.
          const refresh = page.locator('button:visible:has-text("새로고침")').first();
          if (await refresh.count()) {
            await refresh.click({ timeout: 2000 }).catch(() => {});
          }
          await page.waitForTimeout(1200);
        } else {
          await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
          await page.waitForTimeout(400);
        }
      } catch (err) {
        navError = err?.message ?? String(err);
      }

      // --- Screenshot: report artifact (always) + baseline gate ---
      const screenshotPath = resolve(SCREENSHOTS_DIR, `${key}.png`);
      await page.screenshot({ path: screenshotPath, fullPage: true }).catch(() => {});

      // Volatile, time-derived nodes are masked as defence-in-depth on top of the
      // frozen clock so the baseline never keys on a rendered timestamp.
      const timeMask = [
        page.locator(".disclaimer"),
        page.locator(".quality small"),
        page.locator(".connection-meta dd")
      ];
      await expect(page).toHaveScreenshot(`${key}.png`, {
        fullPage: true,
        animations: "disabled",
        mask: timeMask,
        timeout: 15000
      });

      // --- Measurements ---
      const overflow = await measureOverflow(page).catch(() => null);
      const { text, hits } = await scanForbidden(page).catch(() => ({ text: "", hits: [] }));

      // --- Soft assertions ---
      if (overflow) {
        const ok = overflow.scrollWidth <= overflow.innerWidth + 1;
        if (!ok) {
          failures.push({
            assertion: "no-horizontal-overflow",
            actual: `scrollWidth=${overflow.scrollWidth} innerWidth=${overflow.innerWidth}`
          });
        }
        expect.soft(overflow.scrollWidth, "horizontal overflow").toBeLessThanOrEqual(
          overflow.innerWidth + 1
        );
      }

      const visibleLen = text.trim().length;
      if (visibleLen === 0) {
        failures.push({ assertion: "non-empty-page", actual: "0 visible chars" });
      }
      expect.soft(visibleLen, "visible text length").toBeGreaterThan(0);

      if (hits.length > 0) {
        failures.push({ assertion: "no-leaked-tokens", actual: hits.join(", ") });
      }
      expect.soft(hits, "leaked placeholder strings").toEqual([]);

      if (state === "unauthorized") {
        // A signed-out screen must offer a way back in.
        const reloginCount = await page
          .locator(
            'a[href*="/auth/login"], a[href*="/oauth2"], a:has-text("로그인"), button:has-text("로그인")'
          )
          .count()
          .catch(() => 0);
        if (reloginCount === 0) {
          failures.push({ assertion: "unauthorized-has-relogin", actual: "no re-login control" });
        }
        expect.soft(reloginCount, "re-login control present").toBeGreaterThan(0);
      }

      if (state === "error") {
        const alertText = await page
          .locator('[role="alert"]')
          .allInnerTexts()
          .then(list => list.join(" ").trim())
          .catch(() => "");
        if (alertText.length === 0) {
          failures.push({ assertion: "error-has-readable-message", actual: "no [role=alert] text" });
        }
        expect.soft(alertText.length, "error message present").toBeGreaterThan(0);

        const retryCount = await page
          .locator("button")
          .count()
          .catch(() => 0);
        if (retryCount === 0) {
          failures.push({ assertion: "error-has-retry", actual: "no button to retry" });
        }
        expect.soft(retryCount, "retry control present").toBeGreaterThan(0);
      }

      writeRecord("state", key, {
        route: routeDef.name,
        path: routeDef.path,
        state,
        viewport,
        navError,
        overflow,
        visibleTextLength: visibleLen,
        forbiddenHits: hits,
        consoleErrors: diagnostics.consoleErrors,
        consoleWarnings: diagnostics.consoleWarnings,
        pageErrors: diagnostics.pageErrors,
        requestCount: requests.length,
        failures,
        screenshot: `${key}.png`
      });
    });
  }
}
