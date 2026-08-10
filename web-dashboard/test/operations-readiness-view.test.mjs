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

test("maps backend readiness statuses to visible semantic states", () => {
  const cases = [
    ["HEALTHY", "ok", "제공자 준비 상태를 확인했습니다"],
    ["BLOCKED", "danger", "운영 준비가 차단되었습니다"],
    ["SECRET_MISSING", "danger", "자격 증명이 필요합니다"],
    ["NOT_CHECKED", "warn", "제공자 점검 전입니다"],
    ["NOT_CONFIGURED", "warn", "제공자가 설정되지 않았습니다"]
  ];

  for (const [status, tone, copy] of cases) {
    const html = renderToStaticMarkup(OperationsReadinessView({
      readiness: { status, providers: [], alerts: [] },
      onRefresh() {},
      onProbe() {}
    }));

    assert.match(html, new RegExp(`badge-pill--${tone}`));
    assert.match(html, new RegExp(copy));
  }
});

test("shows blocked and credential reasons from readiness payload fields", () => {
  const html = renderToStaticMarkup(OperationsReadinessView({
    readiness: {
      status: "BLOCKED",
      canary: { status: "BLOCKED", blockers: ["BROKER_CREDENTIAL_MISSING"] },
      alerts: ["KILL_SWITCH_ENGAGED"],
      providers: [{
        provider: "FMP",
        status: "SECRET_MISSING",
        credentialConfigured: false,
        missingData: ["quote.price"]
      }]
    },
    onRefresh() {},
    onProbe() {}
  }));

  assert.match(html, /badge-pill--danger/);
  assert.match(html, /KILL_SWITCH_ENGAGED/);
  assert.match(html, /BROKER_CREDENTIAL_MISSING/);
  assert.match(html, /FMP: SECRET_MISSING/);
  assert.match(html, /quote\.price/);
});

test("keeps safety reasons visible when readiness has more than three reasons", () => {
  const html = renderToStaticMarkup(OperationsReadinessView({
    readiness: {
      status: "BLOCKED",
      canary: { status: "BLOCKED", blockers: ["BROKER_CREDENTIAL_MISSING"] },
      alerts: [
        "PROVIDER_FMP_STALE",
        "PROVIDER_IEX_STALE",
        "PROVIDER_POLYGON_STALE",
        "KILL_SWITCH_ENGAGED"
      ],
      providers: [{
        provider: "FMP",
        status: "SECRET_MISSING",
        credentialConfigured: false,
        missingData: []
      }]
    },
    onRefresh() {},
    onProbe() {}
  }));

  assert.match(html, /KILL_SWITCH_ENGAGED/);
  assert.match(html, /BROKER_CREDENTIAL_MISSING/);
  assert.match(html, /FMP: SECRET_MISSING/);
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
