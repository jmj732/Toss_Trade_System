import { resolve } from "node:path";

import { expect, test } from "@playwright/test";

import { collectDiagnostics, SCREENSHOTS_DIR, writeRecord } from "./fixtures/record.mjs";
import {
  CONNECTION_ID,
  freezeClock,
  fullDashboard,
  jsonResponse,
  primeAuth,
  RISK_POLICY,
  SESSION
} from "./fixtures/states.mjs";

// Core user journeys, driven at the 1280 viewport only. These are audit probes:
// a step that gets stuck is a real finding and MUST be reported as a failure,
// never smoothed over.

const SHOT_PREFIX = "journey";

function shot(page, name) {
  return page
    .screenshot({ path: resolve(SCREENSHOTS_DIR, `${SHOT_PREFIX}__${name}.png`), fullPage: true })
    .catch(() => {});
}

function isSession(pathname) {
  return /\/api\/v1\/session$/.test(pathname);
}
function isRiskPolicy(pathname) {
  return /\/risk-policy(\/history)?$/.test(pathname);
}

test.beforeEach(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== "vp-1280", "journeys run at 1280 only");
});

// ---------------------------------------------------------------------------
// 1. Login journey
// ---------------------------------------------------------------------------
test("journey: login", async ({ page, context }) => {
  const diag = collectDiagnostics(page);
  const steps = [];
  let blockedAt = "";

  await primeAuth(context, { withConnection: false });
  // Signed out: session 401 so the home screen shows the login prompt.
  await page.route("**/api/v1/**", route => jsonResponse(route, 401, { code: "UNAUTHORIZED" }));

  try {
    await page.goto("/", { waitUntil: "domcontentloaded", timeout: 30000 });
    await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
    await shot(page, "01-signed-out-home");

    const loginLink = page.locator('a:has-text("로그인")').first();
    const hasPrompt = (await loginLink.count()) > 0;
    steps.push({ step: "home-shows-login-prompt", ok: hasPrompt });
    expect.soft(hasPrompt, "home shows login prompt").toBe(true);
    if (!hasPrompt) {
      blockedAt = "home-login-prompt";
    }

    // Watch for the OIDC redirect the /auth/login route issues.
    const oauthRequest = page
      .waitForRequest(r => /\/oauth2\/authorization/.test(r.url()), { timeout: 8000 })
      .catch(() => null);

    if (hasPrompt) {
      await loginLink.click().catch(() => {});
    }
    const reached = await oauthRequest;
    const reachedOauth = Boolean(reached);
    steps.push({ step: "redirects-to-oauth", ok: reachedOauth, url: reached?.url() ?? "" });
    expect.soft(reachedOauth, "reaches OIDC authorization redirect").toBe(true);
    if (!reachedOauth && !blockedAt) {
      blockedAt = "oauth-redirect";
    }
    await shot(page, "02-after-login-click");
  } catch (err) {
    blockedAt = blockedAt || (err?.message ?? String(err));
  }

  writeRecord("journey", "login", {
    steps,
    blockedAt,
    consoleErrors: diag.consoleErrors,
    pageErrors: diag.pageErrors
  });
  expect.soft(blockedAt, "login journey completed").toBe("");
});

// ---------------------------------------------------------------------------
// 2. Analysis journey
// ---------------------------------------------------------------------------
test("journey: analysis", async ({ page, context }) => {
  const diag = collectDiagnostics(page);
  const steps = [];
  let blockedAt = "";
  let analysisCreated = false;

  await primeAuth(context, { withConnection: true });

  await page.route("**/api/v1/**", async route => {
    const req = route.request();
    const { pathname } = new URL(req.url());
    const method = req.method();

    if (isSession(pathname)) return jsonResponse(route, 200, SESSION);
    if (isRiskPolicy(pathname)) {
      return jsonResponse(route, 200, pathname.includes("history") ? [] : RISK_POLICY);
    }
    if (/\/dashboard$/.test(pathname)) return jsonResponse(route, 200, fullDashboard());
    if (/\/events$/.test(pathname)) return jsonResponse(route, 200, []);
    if (/\/stock-analyses\/[^/]+\/history/.test(pathname)) return jsonResponse(route, 200, []);
    if (/\/prediction-model-versions/.test(pathname)) {
      return jsonResponse(route, 200, [
        { id: "m1", modelVersion: "model-v1", contractVersion: "contract-v1", status: "ACTIVE" }
      ]);
    }
    if (/\/stock-analyses\/[^/]+$/.test(pathname)) {
      if (method === "POST") {
        analysisCreated = true;
        // Simulate the run taking a moment so the PROGRESS/loading state shows.
        await new Promise(r => setTimeout(r, 1500));
        return jsonResponse(route, 200, { runId: "run-1", symbol: "AAPL", result: { status: "COMPLETED" } });
      }
      return jsonResponse(route, 200, analysisCreated
        ? { runId: "run-1", symbol: "AAPL", result: { status: "COMPLETED" } }
        : { symbol: "AAPL", runId: null, result: null });
    }
    if (/\/stock-forecasts\/[^/]+/.test(pathname)) {
      return jsonResponse(route, 200, { runId: "run-1", result: { status: "COMPLETED", forecasts: [] } });
    }
    if (/\/stock-analysis-explanations\/[^/]+/.test(pathname)) {
      return jsonResponse(route, 200, { status: "COMPLETED", citations: [], explanation: { evidence: [] } });
    }
    return jsonResponse(route, 200, {});
  });

  try {
    await page.goto("/stocks/AAPL#access_token=test-token", {
      waitUntil: "domcontentloaded",
      timeout: 30000
    });
    await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
    await shot(page, "03-analysis-initial");

    const createBtn = page.locator('button:has-text("분석 생성")').first();
    const hasCreate = (await createBtn.count()) > 0;
    steps.push({ step: "analyze-button-present", ok: hasCreate });
    expect.soft(hasCreate, "analysis create button present").toBe(true);
    if (!hasCreate) {
      blockedAt = "analyze-button";
    } else {
      await createBtn.click().catch(() => {});
      await page.waitForTimeout(500);
      await shot(page, "04-analysis-loading");

      // Result surfaces after the simulated run. The surface maps the backend
      // COMPLETED/READY status to the Korean label "완료" (STATE_LABELS), so assert
      // on the value the user actually sees, not the raw backend code.
      const resultShown = await page
        .locator('.badge-pill.badge-pill--ok', { hasText: "완료" })
        .first()
        .waitFor({ state: "visible", timeout: 6000 })
        .then(() => true)
        .catch(() => false);
      steps.push({ step: "result-rendered", ok: resultShown });
      expect.soft(resultShown, "analysis result rendered").toBe(true);
      if (!resultShown) {
        blockedAt = "analysis-result";
      }
      await shot(page, "05-analysis-result");
    }
  } catch (err) {
    blockedAt = blockedAt || (err?.message ?? String(err));
  }

  writeRecord("journey", "analysis", {
    steps,
    blockedAt,
    analysisCreated,
    consoleErrors: diag.consoleErrors,
    pageErrors: diag.pageErrors
  });
  expect.soft(blockedAt, "analysis journey completed").toBe("");
});

// ---------------------------------------------------------------------------
// 3. Orders journey + double-submit guard
// ---------------------------------------------------------------------------
test("journey: orders double-submit guard", async ({ page, context }) => {
  const diag = collectDiagnostics(page);
  const steps = [];
  let blockedAt = "";
  let approvePostCount = 0;
  let approveBody = null;

  // The preview the panel renders. The approve POST must carry exactly these values
  // back (D-01), so the same object seeds both the mock and the expected assertion.
  const PREVIEW = {
    displayedQuantity: 1, displayedMaxLoss: 100, displayedCurrency: "USD", proposalVersion: null
  };

  await primeAuth(context, { withConnection: true });
  // fullDashboard()'s proposals carry createdAt/expiresAt anchored to FROZEN_NOW_ISO
  // (see state-matrix.spec.mjs) so their fresh/expiring/expired classification is
  // deterministic. Without freezing the clock here too, the real wall clock makes
  // every proposal look expired days after the anchor date, disabling "승인" and
  // silently no-opping the click below.
  await freezeClock(context);

  await page.route("**/api/v1/**", async route => {
    const req = route.request();
    const { pathname } = new URL(req.url());
    const method = req.method();

    if (isSession(pathname)) return jsonResponse(route, 200, SESSION);
    if (isRiskPolicy(pathname)) {
      return jsonResponse(route, 200, pathname.includes("history") ? [] : RISK_POLICY);
    }
    if (/\/dashboard$/.test(pathname)) return jsonResponse(route, 200, fullDashboard());
    if (/\/events$/.test(pathname)) return jsonResponse(route, 200, []);

    // Approval flow endpoints.
    if (/\/paper-orders\/[^/]+\/approval-preview$/.test(pathname)) {
      return jsonResponse(route, 200, PREVIEW);
    }
    if (/\/paper-orders\/[^/]+\/step-up$/.test(pathname)) {
      return jsonResponse(route, 200, { stepUpToken: "step-up-token" });
    }
    if (/\/paper-orders\/[^/]+\/approve$/.test(pathname)) {
      approvePostCount += 1;
      approveBody = req.postDataJSON();
      await new Promise(r => setTimeout(r, 1500)); // keep the button busy
      return jsonResponse(route, 200, { status: "COMPLETED" });
    }
    return jsonResponse(route, 200, {});
  });

  // Parse a rendered amount cell ("USD 100.00") back to a number for comparison.
  const numeric = text => Number(String(text).replace(/[^0-9.-]/g, ""));

  try {
    await page.goto("/orders#access_token=test-token", {
      waitUntil: "domcontentloaded",
      timeout: 30000
    });
    await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
    await shot(page, "06-orders-initial");

    // Step 1: the list "승인" only opens the confirmation panel; it must not approve yet.
    const approveBtn = page.locator('.orders-surface button:has-text("승인")').first();
    const hasApprove = (await approveBtn.count()) > 0;
    steps.push({ step: "approve-button-present", ok: hasApprove });
    expect.soft(hasApprove, "approve button present").toBe(true);

    if (!hasApprove) {
      blockedAt = "approve-button";
    } else {
      await approveBtn.click().catch(() => {});

      // The preview panel opens and renders the exact figures the user is asked to
      // confirm — max expected loss, quantity, and currency (D-01).
      const panel = page.locator(".order-approval-panel");
      const panelShown = await panel
        .waitFor({ state: "visible", timeout: 6000 })
        .then(() => true)
        .catch(() => false);
      await shot(page, "07-orders-approval-panel");

      const row = label => panel.locator(".approval-preview > div").filter({ hasText: label });
      const qtyText = panelShown
        ? (await row("수량").locator("dd").first().innerText().catch(() => "")).trim()
        : "";
      const currencyText = panelShown
        ? (await row("통화").locator("dd").first().innerText().catch(() => "")).trim()
        : "";
      const maxLossText = panelShown
        ? (await row("최대 예상 손실").locator("dd").first().innerText().catch(() => "")).trim()
        : "";
      const figuresShown = panelShown
        && qtyText === "1" && currencyText === "USD" && /100\.00/.test(maxLossText);
      steps.push({
        step: "approval-figures-rendered",
        ok: figuresShown,
        quantity: qtyText, currency: currencyText, maxLoss: maxLossText
      });
      expect.soft(figuresShown, "approval panel renders confirm figures").toBe(true);
      if (!figuresShown) {
        blockedAt = "approval-figures";
      }

      // Step 2 + guard: two rapid clicks on confirm must yield exactly one approve POST.
      const confirmBtn = page.locator('button:has-text("확인하고 승인")').first();
      await confirmBtn.click().catch(() => {});
      await confirmBtn.click({ timeout: 1000, force: true }).catch(() => {});
      await page.waitForTimeout(2500); // let the (delayed) approve settle
      await shot(page, "08-orders-after-approve");

      steps.push({ step: "single-approve-post", ok: approvePostCount === 1, actual: approvePostCount });
      expect.soft(approvePostCount, "approve POST count (dedupe guard)").toBe(1);
      if (approvePostCount !== 1 && !blockedAt) {
        blockedAt = `duplicate-submit(${approvePostCount})`;
      }

      // D-01 gate: the approve POST body must carry the exact figures the panel showed.
      const bodyMatches = Boolean(approveBody)
        && approveBody.displayedCurrency === currencyText
        && approveBody.displayedQuantity === numeric(qtyText)
        && approveBody.displayedMaxLoss === numeric(maxLossText);
      steps.push({
        step: "approve-body-matches-displayed",
        ok: bodyMatches,
        body: approveBody
      });
      expect.soft(bodyMatches, "approve body matches displayed figures").toBe(true);
      if (!bodyMatches && !blockedAt) {
        blockedAt = "display-body-mismatch";
      }
    }
  } catch (err) {
    blockedAt = blockedAt || (err?.message ?? String(err));
  }

  writeRecord("journey", "orders", {
    steps,
    blockedAt,
    approvePostCount,
    approveBody,
    consoleErrors: diag.consoleErrors,
    pageErrors: diag.pageErrors
  });
  expect.soft(blockedAt, "orders journey completed").toBe("");
});

// ---------------------------------------------------------------------------
// 4. Error recovery journey
// ---------------------------------------------------------------------------
test("journey: error recovery", async ({ page, context }) => {
  const diag = collectDiagnostics(page);
  const steps = [];
  let blockedAt = "";
  let dashboardAttempts = 0;

  await primeAuth(context, { withConnection: true });

  await page.route("**/api/v1/**", async route => {
    const req = route.request();
    const { pathname } = new URL(req.url());

    if (isSession(pathname)) return jsonResponse(route, 200, SESSION);
    if (isRiskPolicy(pathname)) {
      return jsonResponse(route, 200, pathname.includes("history") ? [] : RISK_POLICY);
    }
    if (/\/dashboard$/.test(pathname)) {
      dashboardAttempts += 1;
      // First load fails; retry succeeds.
      return dashboardAttempts <= 1
        ? jsonResponse(route, 500, { code: "INTERNAL_ERROR", message: "Temporary failure" })
        : jsonResponse(route, 200, fullDashboard());
    }
    if (/\/events$/.test(pathname)) return jsonResponse(route, 200, []);
    if (/\/portfolio-history/.test(pathname)) {
      return jsonResponse(route, 200, { stale: false, unavailable: false, data: { points: [] } });
    }
    return jsonResponse(route, 200, {});
  });

  try {
    await page.goto("/portfolio#access_token=test-token", {
      waitUntil: "domcontentloaded",
      timeout: 30000
    });
    await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
    await page.waitForTimeout(500);
    await shot(page, "08-error-shown");

    const errorText = await page
      .locator('[role="alert"]')
      .allInnerTexts()
      .then(list => list.join(" ").trim())
      .catch(() => "");
    const errorShown = errorText.length > 0;
    steps.push({ step: "error-visible", ok: errorShown, text: errorText });
    expect.soft(errorShown, "error message visible").toBe(true);
    if (!errorShown) {
      blockedAt = "error-not-shown";
    }

    // Retry via the "열기" (open) button on the connection form.
    const openBtn = page.locator('button:has-text("열기")').first();
    const hasRetry = (await openBtn.count()) > 0;
    steps.push({ step: "retry-control-present", ok: hasRetry });
    if (!hasRetry) {
      blockedAt = blockedAt || "no-retry-control";
    } else {
      // Ensure the connection id is present so the retry actually requests.
      await page.fill("#route-connection-id", CONNECTION_ID).catch(() => {});
      await openBtn.click().catch(() => {});
      await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
      await page.waitForTimeout(500);
      await shot(page, "09-error-recovered");

      const recovered = await page
        .locator('.error[role="alert"]')
        .count()
        .then(c => c === 0)
        .catch(() => false);
      steps.push({ step: "recovered-after-retry", ok: recovered, dashboardAttempts });
      expect.soft(recovered, "error cleared after retry").toBe(true);
      if (!recovered) {
        blockedAt = blockedAt || "retry-did-not-recover";
      }
    }
  } catch (err) {
    blockedAt = blockedAt || (err?.message ?? String(err));
  }

  writeRecord("journey", "error-recovery", {
    steps,
    blockedAt,
    dashboardAttempts,
    consoleErrors: diag.consoleErrors,
    pageErrors: diag.pageErrors
  });
  expect.soft(blockedAt, "error-recovery journey completed").toBe("");
});
