import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { MarketOverviewView } from "../app/market-overview-view.js";

test("renders loading, unavailable, and error states without fake empty data", () => {
  const html = renderToStaticMarkup(createElement(MarketOverviewView, {
    exchangeRate: null,
    calendar: { status: "UNAVAILABLE", unavailableReason: "PROVIDER_UNSUPPORTED" },
    rankings: { status: "ERROR", unavailableReason: "RANKINGS_UNAVAILABLE" },
    onRankingCategory() {}
  }));

  assert.match(html, /환율 정보를 불러오는 중/);
  assert.match(html, /지원되지 않음 \(PROVIDER_UNSUPPORTED\)/);
  assert.match(html, /조회 실패 \(RANKINGS_UNAVAILABLE\)/);
  assert.doesNotMatch(html, /등록된 휴장일이 없습니다/);
});

test("uses the compact market surface classes instead of inline layout styles", () => {
  const html = renderToStaticMarkup(createElement(MarketOverviewView, {
    exchangeRate: { data: { rate: 1390.25, change: 1.2, changeRate: 0.001, asOf: "2026-08-05T00:00:00Z" } },
    calendar: { data: { marketStatus: "OPEN", holidays: [] } },
    rankings: { data: { items: [{ symbol: "NVDA", name: "NVIDIA", price: 120, currency: "USD", changePercent: 0.02 }] } },
    onRankingCategory() {}
  }));

  assert.match(html, /class="market-overview-grid"/);
  assert.match(html, /class="ranking-tabs"/);
  assert.doesNotMatch(html, /style="/);
});
