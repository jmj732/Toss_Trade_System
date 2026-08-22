import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import {
  ActionQueue,
  DataFreshnessIndicator,
  GlobalStockSearch,
  KillSwitchBanner,
  KillSwitchStatus,
  PortfolioPositionTable,
  PortfolioRiskPanel,
  PortfolioSummary
} from "../app/decision-center.js";
import { buildActions } from "../lib/action-model.js";

const NOW = Date.parse("2026-08-18T00:00:00Z");

function dashboard(overrides = {}) {
  return {
    portfolio: { data: { positions: [], account: {} } },
    analysis: {},
    pendingEvents: { data: [] },
    pendingOrderProposals: { data: [] },
    ...overrides
  };
}

function render(element) {
  return renderToStaticMarkup(element);
}

test("ActionQueue renders priority label + type label, order buttons, and status label", () => {
  const actions = buildActions({
    dashboard: dashboard({
      pendingOrderProposals: { data: [{
        id: "o1", status: "PROPOSED", side: "BUY", symbol: "NVDA", type: "MARKET",
        quantity: 1, createdAt: "2026-08-17T00:00:00Z", expiresAt: "2099-01-01T00:00:00Z"
      }] }
    }),
    now: NOW
  });
  const html = render(createElement(ActionQueue, {
    items: actions, state: "ACTIVE", onOrderAction() {}, busyOrderId: null
  }));
  for (const text of ["결정 센터", "높음", "주문", "매수 NVDA", "승인 검토", "주문 취소", "종목 보기", "승인 대기"]) {
    assert.match(html, new RegExp(text));
  }
  // 승인 대기 + 만료 전이면 승인/취소 모두 활성.
  assert.doesNotMatch(html, /disabled/);
});

test("ActionQueue blocks approval on an expired proposal but keeps cancel", () => {
  const actions = buildActions({
    dashboard: dashboard({
      pendingOrderProposals: { data: [{
        id: "o1", status: "PROPOSED", side: "BUY", symbol: "NVDA", type: "MARKET",
        quantity: 1, createdAt: "2000-01-01T00:00:00Z", expiresAt: "2000-01-02T00:00:00Z"
      }] }
    }),
    now: NOW
  });
  const html = render(createElement(ActionQueue, {
    items: actions, state: "CRITICAL", onOrderAction() {}, busyOrderId: null
  }));
  assert.match(html, /즉시 확인/);
  assert.match(html, /만료됨/);
  assert.match(html, /만료된 제안은 승인할 수 없습니다/);
  // 승인만 비활성, 취소는 남는다.
  assert.equal((html.match(/disabled/g) ?? []).length, 1);
});

test("ActionQueue disables both actions on a non-PROPOSED order with a reason", () => {
  const actions = buildActions({
    dashboard: dashboard({
      pendingOrderProposals: { data: [{
        id: "o1", status: "MANUAL_REVIEW_REQUIRED", side: "SELL", symbol: "AAPL", type: "LIMIT",
        quantity: 1, createdAt: "2026-08-17T00:00:00Z", expiresAt: null
      }] }
    }),
    now: NOW
  });
  const html = render(createElement(ActionQueue, {
    items: actions, state: "CRITICAL", onOrderAction() {}, busyOrderId: null
  }));
  assert.match(html, /즉시/);
  assert.match(html, /수동 검토 필요/);
  assert.match(html, /승인 대기 상태에서만 처리할 수 있습니다/);
  assert.equal((html.match(/disabled/g) ?? []).length, 2);
});

test("ActionQueue renders event actions with an impact link", () => {
  const actions = buildActions({
    dashboard: dashboard({ portfolio: { data: { positions: [{ symbol: "AAPL" }], account: {} } } }),
    events: [{ id: "e1", type: "EARNINGS", summary: "실적 발표", affectedSymbols: ["AAPL"], occurredAt: "2026-08-17T00:00:00Z" }],
    now: NOW
  });
  const html = render(createElement(ActionQueue, { items: actions, state: "ACTIVE", onOrderAction() {} }));
  assert.match(html, /이벤트/);
  assert.match(html, /실적 발표/);
  assert.match(html, /영향 보기/);
  assert.match(html, /\/events\?event=e1/);
});

test("ActionQueue only offers re-sync for data quality when onRefresh is provided", () => {
  const actions = buildActions({
    dashboard: dashboard({ portfolio: { stale: true, data: { positions: [], account: {}, staleReason: "SYNC_LAG" } } }),
    now: NOW
  });
  const withRefresh = render(createElement(ActionQueue, { items: actions, state: "CALM", onRefresh() {} }));
  assert.match(withRefresh, /데이터 품질/);
  assert.match(withRefresh, /다시 동기화/);

  const withoutRefresh = render(createElement(ActionQueue, { items: actions, state: "CALM" }));
  assert.doesNotMatch(withoutRefresh, /다시 동기화/);
});

test("ActionQueue shows a single line when there are no decisions", () => {
  const html = render(createElement(ActionQueue, { items: [], state: "CALM", lastChecked: "2026-08-18T00:31:00Z" }));
  assert.match(html, /확인할 결정이 없습니다/);
  assert.doesNotMatch(html, /action-list/);
});

test("unknown freshness stays explicit", () => {
  const html = render(createElement(DataFreshnessIndicator, { section: { unknown: true } }));
  assert.match(html, /데이터 확인 필요/);
  assert.doesNotMatch(html, /LIVE/);
});

test("global stock search is an accessible symbol route entry", () => {
  const html = render(createElement(GlobalStockSearch, { onSearch() {} }));
  assert.match(html, /종목 검색/);
  assert.match(html, /placeholder="심볼 검색/);
});

test("PortfolioRiskPanel stays a compact one-liner when the server omits the section", () => {
  const html = render(createElement(PortfolioRiskPanel, { dashboard: dashboard() }));
  assert.match(html, /포트폴리오 위험/);
  assert.match(html, /서버 위험 평가 없음/);
});

test("PortfolioRiskPanel surfaces the reason when the section is unavailable", () => {
  const html = render(createElement(PortfolioRiskPanel, {
    dashboard: dashboard({ riskEvaluation: { unavailable: true, unavailableReason: "RISK_EVALUATION_UNAVAILABLE", data: null } })
  }));
  assert.match(html, /서버 위험 평가 없음/);
  assert.match(html, /RISK_EVALUATION_UNAVAILABLE/);
});

test("PortfolioRiskPanel renders server risk items with breached first, without computing usage", () => {
  const html = render(createElement(PortfolioRiskPanel, {
    dashboard: dashboard({
      riskEvaluation: { data: { items: [
        { key: "cash", subject: "현금 비중", scope: "CASH", current: 0.08, limit: 0.1, usageRatio: 0.8, breached: false },
        { key: "conc", subject: "MRVL 집중도", scope: "POSITION", current: 0.21, limit: 0.2, usageRatio: 1.05, breached: true }
      ] } }
    })
  }));
  assert.match(html, /한도 초과/);
  assert.match(html, /MRVL 집중도/);
  // breached 항목이 먼저.
  assert.ok(html.indexOf("MRVL 집중도") < html.indexOf("현금 비중"));
});

test("PortfolioRiskPanel maps scope enums to labels and footnotes policy/version time", () => {
  const html = render(createElement(PortfolioRiskPanel, {
    dashboard: dashboard({
      riskEvaluation: { data: {
        policyVersion: 0,
        evaluatedAt: "2026-08-18T08:07:22Z",
        items: [
          { key: "POSITION_CONCENTRATION:MRVL", scope: "POSITION", subject: "MRVL", current: 0.8, limit: 0.25, usageRatio: 3.2, breached: true },
          { key: "CURRENCY_CONCENTRATION:USD", scope: "CURRENCY", subject: "USD", current: 1, limit: 0.25, usageRatio: 4, breached: true }
        ]
      } }
    })
  }));
  assert.match(html, /종목 집중도/);
  assert.match(html, /통화 집중도/);
  // policyVersion 0 은 실제 값이므로 그대로 노출한다.
  assert.match(html, /정책 v0/);
  assert.match(html, /기준/);
});

test("PortfolioRiskPanel surfaces unknownFields instead of dropping them silently", () => {
  const html = render(createElement(PortfolioRiskPanel, {
    dashboard: dashboard({
      riskEvaluation: {
        unknown: true,
        unknownFields: ["positions[NVDA].weight"],
        data: { policyVersion: 1, items: [
          { key: "cash", scope: "CASH", subject: "현금 비중", current: 0.08, limit: 0.1, usageRatio: 0.8, breached: false }
        ] }
      }
    })
  }));
  assert.match(html, /일부 항목 확인 불가/);
  assert.match(html, /positions\[NVDA\]\.weight/);
});

test("KillSwitchBanner renders only when engaged is true", () => {
  const engaged = render(createElement(KillSwitchBanner, {
    killSwitch: { engaged: true, reason: "수동 정지", changedAt: "2026-08-18T08:00:00Z" }
  }));
  assert.match(engaged, /거래 중지됨 · 내 계정 기준/);
  assert.match(engaged, /수동 정지/);
  assert.match(engaged, /변경/);

  // null(미확정) · undefined(미설정) · false(해제)는 배너를 띄우지 않는다.
  assert.equal(render(createElement(KillSwitchBanner, { killSwitch: null })), "");
  assert.equal(render(createElement(KillSwitchBanner, { killSwitch: { engaged: null } })), "");
  assert.equal(render(createElement(KillSwitchBanner, { killSwitch: { engaged: false } })), "");
});

test("KillSwitchStatus distinguishes unknown, unset, engaged, and released", () => {
  assert.match(render(createElement(KillSwitchStatus, { killSwitch: null })), /상태 확인 필요/);
  // engaged === null 은 미설정이지 "정상"이 아니다.
  const unset = render(createElement(KillSwitchStatus, { killSwitch: { engaged: null } }));
  assert.match(unset, /상태 미설정/);
  assert.doesNotMatch(unset, /정상/);
  assert.match(render(createElement(KillSwitchStatus, { killSwitch: { engaged: true, reason: "수동 정지" } })), /거래 중지됨/);
  assert.match(render(createElement(KillSwitchStatus, { killSwitch: { engaged: false } })), /정상 \(거래 가능\)/);
});

test("PortfolioPositionTable drops the unimplemented BC-2 columns", () => {
  const html = render(createElement(PortfolioPositionTable, {
    section: { data: { positions: [{ symbol: "NVDA", currency: "USD", quantity: 1, marketValueAmount: 120, profitLossAmount: 20 }] } },
    analysis: { data: { result: { positions: [{ symbol: "NVDA", weight: 1 }] } } }
  }));
  for (const label of ["종목", "현재가", "수량", "평가금액", "비중", "손익", "행동"]) {
    assert.match(html, new RegExp(label));
  }
  for (const removed of ["Risk", "Next Catalyst", "판단"]) {
    assert.doesNotMatch(html, new RegExp(removed));
  }
  assert.match(html, /주문 작성/);
});

test("PortfolioSummary renders per-currency buying power as orderable cash", () => {
  const html = render(createElement(PortfolioSummary, {
    dashboard: dashboard({
      portfolio: {
        data: {
          account: { marketValueAmounts: { KRW: 1000 } },
          buyingPower: {
            KRW: { cashBuyingPower: 900 },
            USD: { cashBuyingPower: 12.5 }
          }
        }
      }
    })
  }));
  assert.match(html, /주문 가능 현금/);
  assert.match(html, /KRW 900/);
  assert.match(html, /USD 12\.50/);
  assert.doesNotMatch(html, /현금 잔고 상태|주문 가능 금액/);
});

test("PortfolioPositionTable turns on Risk/판단 columns from the positionDecisions contract and keeps null causes distinct", () => {
  const html = render(createElement(PortfolioPositionTable, {
    section: { data: { positions: [
      { symbol: "NVDA", currency: "USD", quantity: 1, marketValueAmount: 120, profitLossAmount: 20 },
      { symbol: "MRVL", currency: "USD", quantity: 3, marketValueAmount: 240, profitLossAmount: -5 },
      { symbol: "AMD", currency: "USD", quantity: 2, marketValueAmount: 200, profitLossAmount: 0 }
    ] } },
    analysis: { data: { result: { positions: [{ symbol: "NVDA", weight: 1 }] } } },
    positionDecisions: { data: [
      { symbol: "NVDA", riskLevel: "LOW", decision: "BUY", confidence: 0.72,
        decisionRuleVersion: "decision-rule-v1", decisionAsOf: "2026-07-28T00:03:00Z", decisionRunId: "run-1" },
      // 분석했으나 지표 부족으로 판단 없음(decisionRunId 있음, decision null).
      { symbol: "MRVL", riskLevel: null, decision: null, confidence: null,
        decisionRuleVersion: null, decisionAsOf: null, decisionRunId: "run-2" },
      // 분석한 적 없음(decisionRunId null).
      { symbol: "AMD", riskLevel: null, decision: null, confidence: null,
        decisionRuleVersion: null, decisionAsOf: null, decisionRunId: null }
    ] }
  }));

  // 계약이 있으면 Risk·판단 열이 켜진다. Next Catalyst 는 데이터 소스가 없어 만들지 않는다.
  assert.match(html, /Risk/);
  assert.match(html, /판단/);
  assert.doesNotMatch(html, /Next Catalyst/);

  // 판단은 확정 표현이 아니라 규칙 산출물로 노출한다(규칙 버전·신뢰도·기준 시각 동반).
  assert.match(html, /규칙 판단: 매수/);
  assert.match(html, /신뢰도 72\.0%/);
  assert.match(html, /decision-rule-v1/);

  // riskLevel: null 은 "안전"이 아니라 판정 근거 없음 → "확인 불가".
  assert.match(html, /확인 불가/);
  // decisionRunId != null && decision == null → "판단 보류 · 지표 부족".
  assert.match(html, /판단 보류 · 지표 부족/);
  // decisionRunId == null → "분석 없음". 두 원인은 서로 다른 문구다.
  assert.match(html, /분석 없음/);
  assert.notEqual("확인 불가", "판단 보류 · 지표 부족");
  assert.notEqual("판단 보류 · 지표 부족", "분석 없음");
  // 세 문구가 실제로 모두(중복 없이) 렌더된다.
  assert.ok(html.indexOf("판단 보류 · 지표 부족") !== html.indexOf("분석 없음"));
});

test("PortfolioPositionTable hides Risk/판단 columns when the positionDecisions contract is absent", () => {
  const html = render(createElement(PortfolioPositionTable, {
    section: { data: { positions: [{ symbol: "NVDA", currency: "USD", quantity: 1, marketValueAmount: 120, profitLossAmount: 20 }] } },
    analysis: {}
  }));
  for (const removed of ["Risk", "Next Catalyst", "판단"]) {
    assert.doesNotMatch(html, new RegExp(removed));
  }
});

test("PortfolioPositionTable limits rows and links to the full portfolio", () => {
  const positions = Array.from({ length: 8 }, (_, index) => ({
    symbol: `S${index}`, currency: "USD", quantity: 1, marketValueAmount: 1, profitLossAmount: 0
  }));
  const html = render(createElement(PortfolioPositionTable, {
    section: { data: { positions } }, analysis: {}, limit: 5
  }));
  assert.match(html, /전체 보기/);
  assert.match(html, /href="\/portfolio"/);
  assert.equal((html.match(/scope="row"/g) ?? []).length, 5);
});
