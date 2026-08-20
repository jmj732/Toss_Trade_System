import assert from "node:assert/strict";
import test from "node:test";

import { buildActions } from "../../lib/action-model.js";
import { URGENT_EXPIRY_WINDOW_MS, resolveSurfaceState } from "../../lib/surface-state.js";
import {
  DECISION_STATES,
  DECISION_STATE_SURFACES,
  FROZEN_NOW_ISO,
  ROUTES,
  STATES,
  routeStates,
  stateRoute
} from "./states.mjs";

async function responseFor(pathname, state, method = "GET") {
  let response;
  const route = {
    request: () => ({
      url: () => `http://localhost:3000${pathname}`,
      method: () => method
    }),
    fulfill: value => {
      response = value;
    }
  };

  await stateRoute(state, { delayMs: 0 })(route);
  return { ...response, body: JSON.parse(response.body) };
}

test("state matrix includes approved new global states", () => {
  assert.ok(STATES.includes("refreshing"));
  assert.ok(STATES.includes("degraded"));
  assert.ok(!STATES.includes("unsupported"));
});

test("live market routes are covered by the normal state matrix", () => {
  assert.ok(ROUTES.some(route => route.name === "home"));
  assert.equal(ROUTES.find(route => route.name === "stocks-AAPL")?.extraStates, undefined);
});

test("stock market endpoint fixture is a real provider response", async () => {
  const response = await responseFor("/api/v1/connections/audit-connection/exchange-rate", "unsupported");

  assert.equal(response.status, 200);
  assert.equal(response.body.status, "AVAILABLE");
  assert.equal(response.body.data.baseCurrency, "USD");
  assert.equal(response.body.provenance[0].provider, "TOSS");
});

test("candle fixture preserves the requested symbol and interval", async () => {
  const response = await responseFor(
    "/api/v1/broker-connections/audit-connection/candles?symbol=NVDA&interval=1m",
    "degraded"
  );

  assert.equal(response.body.data.symbol, "NVDA");
  assert.equal(response.body.data.interval, "1m");
});

// ---------------------------------------------------------------------------
// New backend surface: every endpoint the app actually calls must be registered,
// otherwise matchEndpoint throws inside the Playwright route handler and the
// request leaks as a failure instead of a pinned state.
// ---------------------------------------------------------------------------

test("kill switch, paper preview, and live order commands are all registered", async () => {
  const killSwitch = await responseFor("/api/v1/trading/kill-switch?scope=USER", "degraded");
  assert.equal(killSwitch.status, 200);
  // engaged === false is the only "trading allowed" answer; null would mean unset.
  assert.equal(killSwitch.body.engaged, false);

  const preview = await responseFor("/api/v1/paper-orders/preview", "degraded", "POST");
  assert.equal(preview.status, 200);
  assert.equal(preview.body.decision, "ALLOW");
  // The preview must not be swallowed by the generic /paper-orders/<id> rule.
  assert.notEqual(preview.body.status, "COMPLETED");

  for (const action of ["step-up", "approve", "dispatch", "cancel"]) {
    const response = await responseFor(`/api/v1/live-orders/order-9/${action}`, "degraded", "POST");
    assert.equal(response.status, 200, `live-orders/${action} must be registered`);
  }

  const approvalPreview = await responseFor(
    "/api/v1/paper-orders/order-1/approval-preview", "degraded", "POST");
  assert.equal(approvalPreview.body.displayedCurrency, "USD");
});

test("healthy dashboards carry the riskEvaluation and positionDecisions sections", async () => {
  const dashboard = await responseFor(
    "/api/v1/broker-connections/audit-connection/dashboard", "degraded");
  assert.ok(Array.isArray(dashboard.body.riskEvaluation.data.items));
  assert.ok(Array.isArray(dashboard.body.positionDecisions.data));
});

// ---------------------------------------------------------------------------
// Decision surface states
// ---------------------------------------------------------------------------

test("decision states are attached to the home route only", () => {
  const home = ROUTES.find(route => route.name === "home");
  assert.deepEqual(home.extraStates, DECISION_STATES);
  assert.deepEqual(routeStates(home), [...STATES, ...DECISION_STATES]);
  for (const route of ROUTES.filter(value => value.name !== "home")) {
    assert.equal(route.extraStates, undefined, `${route.name} must not carry decision states`);
  }
});

test("the /predictions route is covered by its redirect spec, not the pixel matrix", () => {
  assert.ok(!ROUTES.some(route => route.name === "predictions"));
});

// The expiry edges baked into the decision fixtures are only correct relative to
// the clock freezeClock installs and the window surface-state publishes. If either
// constant moves, this fails instead of silently reclassifying an order's priority.
test("decision fixture expiry edges stay pinned to the frozen clock and urgent window", () => {
  assert.equal(FROZEN_NOW_ISO, "2026-08-06T00:00:00Z");
  assert.equal(URGENT_EXPIRY_WINDOW_MS, 900000);
  assert.equal(
    Date.parse("2026-08-06T00:15:00Z") - Date.parse(FROZEN_NOW_ISO),
    URGENT_EXPIRY_WINDOW_MS
  );
  // The ACTIVE fixture's expiry must sit strictly outside the urgent window.
  assert.ok(
    Date.parse("2026-08-06T08:00:00Z") - Date.parse(FROZEN_NOW_ISO) > URGENT_EXPIRY_WINDOW_MS
  );
});

test("each decision state resolves to exactly its intended surface state", async () => {
  const now = Date.parse(FROZEN_NOW_ISO);
  for (const state of DECISION_STATES) {
    const response = await responseFor(
      "/api/v1/broker-connections/audit-connection/dashboard", state);
    const dashboard = response.body;
    const actions = buildActions({ dashboard, now });
    const surface = resolveSurfaceState({
      connectionId: "audit-connection",
      dashboard,
      actions,
      killSwitch: { engaged: false },
      now
    });
    assert.equal(surface.state, DECISION_STATE_SURFACES[state], `${state} surface state`);
  }
});

test("decision-calm produces zero actions, including every DATA_QUALITY rule", async () => {
  const response = await responseFor(
    "/api/v1/broker-connections/audit-connection/dashboard", "decision-calm");
  const dashboard = response.body;
  assert.deepEqual(buildActions({ dashboard, now: Date.parse(FROZEN_NOW_ISO) }), []);
  // Spelled out so a fixture edit that reintroduces one of them is obvious.
  assert.notEqual(dashboard.portfolio.stale, true);
  assert.notEqual(dashboard.portfolio.data.partial, true);
  assert.notEqual(dashboard.portfolio.data.account.cashBalanceStatus, "UNKNOWN");
  assert.notEqual(dashboard.analysis.data.result.status, "DEGRADED");
  assert.notEqual(dashboard.analysis.data.result.quality?.partial, true);
  assert.deepEqual(dashboard.pendingOrderProposals.data, []);
  assert.deepEqual(dashboard.pendingEvents.data, []);
});

test("decision-active stays HIGH and decision-critical is URGENT", async () => {
  const now = Date.parse(FROZEN_NOW_ISO);
  const active = await responseFor(
    "/api/v1/broker-connections/audit-connection/dashboard", "decision-active");
  const activeActions = buildActions({ dashboard: active.body, now });
  assert.equal(activeActions.length, 1);
  assert.equal(activeActions[0].priority, "HIGH");

  const critical = await responseFor(
    "/api/v1/broker-connections/audit-connection/dashboard", "decision-critical");
  const criticalActions = buildActions({ dashboard: critical.body, now });
  assert.equal(criticalActions.length, 1);
  assert.equal(criticalActions[0].priority, "URGENT");
});
