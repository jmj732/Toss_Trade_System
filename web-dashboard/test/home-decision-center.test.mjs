import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { HomeDecisionCenter } from "../app/home-decision-center.js";

const DASHBOARD = {
  portfolio: {
    completedAt: "2026-08-18T00:31:00Z",
    data: {
      account: {
        marketValueAmounts: { USD: 120 },
        profitLossAmounts: { USD: 20 },
        dailyProfitLossAmounts: { USD: 4 }
      },
      positions: [{ symbol: "NVDA", currency: "USD", quantity: 1, marketValueAmount: 120, profitLossAmount: 20 }],
      buyingPower: { USD: { cashBuyingPower: 1000 } }
    }
  },
  analysis: { data: { result: { positions: [{ symbol: "NVDA", weight: 1 }] } } },
  pendingEvents: { data: [] },
  pendingOrderProposals: { data: [] }
};

const URGENT_ACTION = {
  key: "order:o1", id: "o1", priority: "URGENT", type: "ORDER", symbol: "NVDA",
  title: "매수 NVDA", facts: [], statusCode: "MANUAL_REVIEW_REQUIRED", deadline: null
};
const HIGH_ACTION = {
  key: "order:o2", id: "o2", priority: "HIGH", type: "ORDER", symbol: "NVDA",
  title: "매수 NVDA", facts: [], statusCode: "PROPOSED", deadline: "2099-01-01T00:00:00Z"
};

function surfaceFor(state, actionCount) {
  const labels = {
    CRITICAL: "즉시 확인", RISK: "한도 초과", ACTIVE: "확인 필요", CALM: "확인할 결정 없음", BLOCKED: "연결 필요"
  };
  return { state, label: labels[state], actionCount, urgentCount: state === "CRITICAL" ? 1 : 0 };
}

function render(state, actions) {
  return renderToStaticMarkup(createElement(HomeDecisionCenter, {
    surface: surfaceFor(state, actions.length),
    actions,
    dashboard: DASHBOARD,
    portfolioHistory: { data: { points: [] } },
    historyBusy: false,
    marketContext: createElement("div", { className: "market-ctx" }, "시장"),
    busyOrderId: null,
    onOrderAction() {},
    onRefresh() {}
  }));
}

function regionOrder(html) {
  return [...html.matchAll(/data-home-region="([a-z]+)"/g)]
    .map(match => match[1])
    .filter(name => name !== "status");
}

function weights(html) {
  const result = {};
  for (const match of html.matchAll(/data-home-region="([a-z]+)"[^>]*data-weight="(\d)"/g)) {
    if (match[1] !== "status") result[match[1]] = Number(match[2]);
  }
  return result;
}

const EXPECTED = {
  CRITICAL: {
    weights: { actions: 4, risk: 3, summary: 1, positions: 1, trend: 0, market: 0 },
    order: ["actions", "risk", "summary", "positions", "trend", "market"],
    actions: [URGENT_ACTION]
  },
  RISK: {
    weights: { actions: 3, risk: 4, summary: 2, positions: 3, trend: 0, market: 0 },
    order: ["risk", "actions", "positions", "summary", "trend", "market"],
    actions: [HIGH_ACTION]
  },
  ACTIVE: {
    weights: { actions: 3, risk: 2, summary: 2, positions: 2, trend: 0, market: 0 },
    order: ["actions", "risk", "summary", "positions", "trend", "market"],
    actions: [HIGH_ACTION]
  },
  CALM: {
    weights: { actions: 1, risk: 1, summary: 3, positions: 3, trend: 1, market: 1 },
    order: ["summary", "positions", "actions", "risk", "trend", "market"],
    actions: []
  }
};

for (const [state, expected] of Object.entries(EXPECTED)) {
  test(`${state}: renders all six regions with the expected weights and DOM order`, () => {
    const html = render(state, expected.actions);
    assert.deepEqual(regionOrder(html), expected.order);
    assert.deepEqual(weights(html), expected.weights);
    assert.match(html, new RegExp(`data-home-state="${state}"`));
    // C-2: 현재 상태 라벨을 노출한다.
    assert.match(html, new RegExp(surfaceFor(state, expected.actions.length).label));
  });
}

test("CRITICAL promotes exactly one hero card", () => {
  const html = render("CRITICAL", [URGENT_ACTION]);
  assert.equal((html.match(/panel--hero/g) ?? []).length, 1);
});

test("CALM keeps the ActionQueue as a single compact line", () => {
  const html = render("CALM", []);
  const actionsRegion = html.slice(html.indexOf('data-home-region="actions"'));
  assert.match(html, /panel--compact/);
  assert.match(actionsRegion, /확인할 결정이 없습니다/);
  assert.doesNotMatch(html, /action-list/);
});

test("Home positions render the server position decision contract", () => {
  const html = renderToStaticMarkup(createElement(HomeDecisionCenter, {
    surface: surfaceFor("ACTIVE", 1),
    actions: [HIGH_ACTION],
    dashboard: {
      ...DASHBOARD,
      positionDecisions: { data: [{
        symbol: "NVDA",
        riskLevel: "LOW",
        decision: "BUY",
        confidence: 0.72,
        decisionRuleVersion: "decision-rule-v1",
        decisionAsOf: "2026-08-18T00:31:00Z",
        decisionRunId: "run-1",
        nextCatalystAt: "2099-01-02T00:00:00Z",
        nextCatalystType: "EARNINGS"
      }] }
    }
  }));

  assert.match(html, /Risk/);
  assert.match(html, /EARNINGS/);
  assert.match(html, /규칙 판단: 매수/);
  assert.match(html, /신뢰도 72\.0%/);
});

test("BLOCKED renders nothing (host draws onboarding)", () => {
  const html = renderToStaticMarkup(createElement(HomeDecisionCenter, {
    surface: surfaceFor("BLOCKED", 0), actions: [], dashboard: DASHBOARD
  }));
  assert.equal(html, "");
});

test("uses buying power as the only cash metric", () => {
  const html = render("CALM", []);
  assert.match(html, /주문 가능 현금/);
  assert.match(html, /USD 1,000\.00/);
  assert.doesNotMatch(html, /현금 잔고 상태|주문 가능 금액/);
});
