import test from "node:test";
import assert from "node:assert/strict";
import {
  SURFACE_STATES,
  URGENT_EXPIRY_WINDOW_MS,
  resolveSurfaceState
} from "../lib/surface-state.js";

const NOW = Date.parse("2026-08-18T00:00:00Z");

// 정상 섹션의 최소 형태. 개별 테스트가 필요한 필드만 덮어쓴다.
function section(overrides = {}) {
  return { stale: false, unknown: false, unavailable: false, data: null, ...overrides };
}

function dashboard(overrides = {}) {
  return {
    portfolio: section(),
    analysis: section(),
    pendingEvents: section({ data: [] }),
    pendingOrderProposals: section({ data: [] }),
    ...overrides
  };
}

function urgentAction() {
  return { key: "order:1", priority: "URGENT", type: "ORDER" };
}

function highAction() {
  return { key: "order:2", priority: "HIGH", type: "ORDER" };
}

test("SURFACE_STATES와 상수를 노출한다", () => {
  assert.deepEqual(SURFACE_STATES, ["BLOCKED", "CRITICAL", "RISK", "ACTIVE", "CALM"]);
  assert.equal(URGENT_EXPIRY_WINDOW_MS, 900000);
});

test("connectionId나 dashboard가 없으면 BLOCKED", () => {
  const noConn = resolveSurfaceState({ dashboard: dashboard(), now: NOW });
  assert.equal(noConn.state, "BLOCKED");
  assert.equal(noConn.label, "연결 필요");

  const noDash = resolveSurfaceState({ connectionId: "c1", now: NOW });
  assert.equal(noDash.state, "BLOCKED");
});

test("connectionId가 빈 문자열이면 BLOCKED", () => {
  const result = resolveSurfaceState({ connectionId: "", dashboard: dashboard(), now: NOW });
  assert.equal(result.state, "BLOCKED");
});

test("portfolio unavailable은 dataBroken이라 BLOCKED", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard({ portfolio: section({ unavailable: true }) }),
    now: NOW
  });
  assert.equal(result.state, "BLOCKED");
  assert.equal(result.dataBroken, true);
});

test("analysis unavailable은 dataBroken이라 BLOCKED", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard({ analysis: section({ unavailable: true }) }),
    now: NOW
  });
  assert.equal(result.state, "BLOCKED");
  assert.equal(result.dataBroken, true);
});

test("ANALYSIS_RESULT_NOT_FOUND는 분석 미생성이라 BLOCKED가 아니다", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard({
      analysis: section({ unavailable: true, unavailableReason: "ANALYSIS_RESULT_NOT_FOUND" })
    }),
    now: NOW
  });
  assert.notEqual(result.state, "BLOCKED");
  assert.equal(result.dataBroken, false);
  assert.equal(result.state, "CALM");
});

test("urgent action이 하나라도 있으면 CRITICAL", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard(),
    actions: [urgentAction(), highAction()],
    now: NOW
  });
  assert.equal(result.state, "CRITICAL");
  assert.equal(result.label, "즉시 확인");
  assert.equal(result.urgentCount, 1);
  assert.equal(result.actionCount, 2);
});

test("urgent가 있으면 riskBreached·actionCount 무관하게 CRITICAL", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard({
      riskEvaluation: section({ data: { items: [{ breached: true }] } })
    }),
    actions: [urgentAction()],
    now: NOW
  });
  assert.equal(result.state, "CRITICAL");
  assert.equal(result.riskBreached, true);
});

test("killSwitch engaged 단독으로 CRITICAL", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard(),
    killSwitch: { engaged: true },
    now: NOW
  });
  assert.equal(result.state, "CRITICAL");
  assert.equal(result.killSwitchEngaged, true);
});

test("riskBreached면 RISK", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard({
      riskEvaluation: section({ data: { items: [{ breached: false }, { breached: true }] } })
    }),
    actions: [highAction()],
    now: NOW
  });
  assert.equal(result.state, "RISK");
  assert.equal(result.label, "한도 초과");
  assert.equal(result.riskBreached, true);
});

test("전부 breached=false면 RISK 미진입", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard({
      riskEvaluation: section({ data: { items: [{ breached: false }, { breached: false }] } })
    }),
    now: NOW
  });
  assert.equal(result.riskBreached, false);
  assert.equal(result.state, "CALM");
});

test("riskEvaluation 섹션이 unavailable이면 riskBreached=false이고 dataBroken 아님", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard({
      riskEvaluation: section({ unavailable: true, data: null })
    }),
    now: NOW
  });
  assert.equal(result.riskBreached, false);
  assert.equal(result.dataBroken, false);
  assert.equal(result.state, "CALM");
});

test("urgent 없고 riskBreached 아니고 action이 있으면 ACTIVE", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard(),
    actions: [highAction()],
    now: NOW
  });
  assert.equal(result.state, "ACTIVE");
  assert.equal(result.label, "확인 필요");
  assert.equal(result.actionCount, 1);
  assert.equal(result.urgentCount, 0);
});

test("아무 것도 없으면 CALM", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard(),
    actions: [],
    now: NOW
  });
  assert.equal(result.state, "CALM");
  assert.equal(result.label, "확인할 결정 없음");
});

test("riskEvaluation 필드가 없으면 riskBreached=false", () => {
  const result = resolveSurfaceState({
    connectionId: "c1",
    dashboard: dashboard(),
    now: NOW
  });
  assert.equal(result.riskBreached, false);
  assert.equal(result.state, "CALM");
});

test("actions 미주입 시 urgentCount·actionCount는 0", () => {
  const result = resolveSurfaceState({ connectionId: "c1", dashboard: dashboard(), now: NOW });
  assert.equal(result.urgentCount, 0);
  assert.equal(result.actionCount, 0);
});

test("빈 입력·undefined에도 throw하지 않는다", () => {
  assert.doesNotThrow(() => resolveSurfaceState({}));
  assert.doesNotThrow(() => resolveSurfaceState(undefined));
  const empty = resolveSurfaceState({});
  assert.equal(empty.state, "BLOCKED");
  assert.ok(SURFACE_STATES.includes(empty.state));
});
