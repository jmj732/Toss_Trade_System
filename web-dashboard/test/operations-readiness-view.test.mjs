import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";

import { OperationsReadinessView } from "../app/operations-readiness-view.js";

test("renders safe provider and safety readiness without secrets or values", () => {
  const html = renderToStaticMarkup(OperationsReadinessView({
    readiness: {
      status: "DEGRADED",
      canary: { status: "DISABLED" },
      killSwitch: { status: "NOT_REQUIRED" },
      dataFreshness: { status: "STALE", maxLagMs: 301000 },
      alerts: ["PROVIDER_FMP_STALE"],
      providers: [{
        provider: "FMP", status: "STALE", credentialConfigured: true,
        lagMs: 301000, missingData: []
      }]
    },
    onRefresh() {},
    onProbe() {}
  }));

  assert.match(html, /운영 준비 상태/);
  assert.match(html, /FMP/);
  assert.match(html, /5m 1s/);
  assert.doesNotMatch(html, /provider-secret|189\.40|raw-response/);
});

test("distinguishes readiness loading, refreshing, empty, and degraded states", () => {
  const loading = renderToStaticMarkup(OperationsReadinessView({
    readiness: null,
    busy: true,
    onRefresh() {},
    onProbe() {}
  }));
  assert.match(loading, /초기 상태 확인 중/);

  const empty = renderToStaticMarkup(OperationsReadinessView({
    readiness: {
      status: "READY",
      canary: { status: "DISABLED" },
      killSwitch: { status: "NOT_REQUIRED" },
      dataFreshness: { status: "FRESH", maxLagMs: 0 },
      alerts: [],
      providers: []
    },
    onRefresh() {},
    onProbe() {}
  }));
  assert.match(empty, /등록된 제공자가 없습니다/);

  const refreshing = renderToStaticMarkup(OperationsReadinessView({
    readiness: {
      status: "DEGRADED",
      canary: { status: "DISABLED" },
      killSwitch: { status: "NOT_REQUIRED" },
      dataFreshness: { status: "STALE", maxLagMs: 301000 },
      alerts: ["PROVIDER_FMP_STALE"],
      providers: [{ provider: "very-long-provider-name.example", status: "DEGRADED", credentialConfigured: false, lagMs: 301000, missingData: ["quote.price"] }]
    },
    busy: true,
    onRefresh() {},
    onProbe() {}
  }));
  assert.match(refreshing, /새로고침 중/);
  assert.match(refreshing, /일부 준비 상태가 저하됐습니다/);
  assert.match(refreshing, /very-long-provider-name\.example/);
});
