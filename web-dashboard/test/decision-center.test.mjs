import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import {
  ActionQueue,
  DataFreshnessIndicator,
  DecisionCenter,
  GlobalStockSearch,
  PortfolioPositionTable,
  buildActionItems
} from "../app/decision-center.js";

const dashboard = {
  portfolio: {
    stale: false,
    asOf: "2026-08-17T00:00:00Z",
    data: {
      account: { marketValueAmounts: { USD: 120 }, profitLossAmounts: { USD: 20 } },
      positions: [{ symbol: "NVDA", name: "NVIDIA", quantity: 1, marketValueAmount: 120 }]
    }
  },
  pendingOrderProposals: { data: [{ id: "order-1", status: "PROPOSED", side: "BUY", type: "MARKET", symbol: "NVDA", quantity: 1 }] },
  pendingEvents: { data: [{ id: "event-1", type: "MACRO", summary: "Rate decision", affectedSymbols: ["NVDA"] }] }
};

test("buildActionItems only adapts real proposal and event records", () => {
  const items = buildActionItems({ dashboard });
  assert.deepEqual(items.map(item => [item.kind, item.id]), [["order", "order-1"], ["event", "event-1"]]);
  assert.equal(items[0].symbol, "NVDA");
  assert.equal(items[1].cause, "MACRO");
});

test("ActionQueue exposes decision, action, impact, provenance, and links", () => {
  const html = renderToStaticMarkup(createElement(ActionQueue, {
    items: buildActionItems({ dashboard }), onOrder() {}
  }));
  for (const text of ["결정 센터", "매수 NVDA", "주문 검토", "Rate decision", "포트폴리오 영향", "원인", "/events"]) {
    assert.match(html, new RegExp(text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
});

test("unknown freshness stays explicit", () => {
  const html = renderToStaticMarkup(createElement(DataFreshnessIndicator, { section: { unknown: true } }));
  assert.match(html, /데이터 확인 필요/);
  assert.doesNotMatch(html, /LIVE/);
});

test("global stock search is an accessible symbol route entry", () => {
  const html = renderToStaticMarkup(createElement(GlobalStockSearch, { onSearch() {} }));
  assert.match(html, /종목 검색/);
  assert.match(html, /placeholder="심볼 검색/);
});

test("DecisionCenter labels unavailable portfolio risk instead of inventing a judgement", () => {
  const html = renderToStaticMarkup(createElement(DecisionCenter, { dashboard, onOrder() {} }));
  assert.match(html, /포트폴리오 위험/);
  assert.match(html, /서버 위험 데이터 없음/);
});

test("position management keeps risk and catalyst fields explicit", () => {
  const html = renderToStaticMarkup(createElement(PortfolioPositionTable, {
    section: { data: { positions: [{ symbol: "NVDA", currency: "USD", quantity: 1 }] } },
    analysis: { data: { result: { positions: [] } } }
  }));
  assert.match(html, /Risk/);
  assert.match(html, /Next Catalyst/);
  assert.match(html, /확인 필요/);
});
