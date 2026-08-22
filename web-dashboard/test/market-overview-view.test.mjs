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

test("renders degraded provider values while showing missing-field scope and provenance", () => {
  const html = renderToStaticMarkup(createElement(MarketOverviewView, {
    exchangeRate: {
      status: "DEGRADED",
      unknownFields: ["validUntil"],
      data: { rate: 1390.25, basisPoint: 1.2, validFrom: "2026-08-05T00:00:00Z" },
      provenance: [{ provider: "TOSS", endpoint: "/api/v1/exchange-rate", asOf: "2026-08-05T00:00:00Z", observedAt: "2026-08-05T00:00:01Z" }]
    },
    calendar: {
      status: "DEGRADED",
      unknownFields: ["today"],
      data: { market: "US", payload: { holidays: [{ date: "2026-08-10", name: "휴장" }] } },
      provenance: [{ provider: "TOSS", endpoint: "/api/v1/market-calendar/US", observedAt: "2026-08-05T00:00:01Z" }]
    },
    rankings: { data: { items: [] } }
  }));

  assert.match(html, /₩1,390\.25/);
  assert.match(html, /휴장/);
  assert.match(html, /부분 데이터만 확인할 수 있습니다 · 누락 필드: validUntil/);
  assert.match(html, /부분 데이터만 확인할 수 있습니다 · 누락 필드: today/);
  assert.match(html, /출처 TOSS/);
  assert.match(html, /기준/);
  assert.match(html, /수집/);
});

test("elevates visual weight for degraded/unavailable/stale widgets and keeps nominal ones plain", () => {
  const html = renderToStaticMarkup(createElement(MarketOverviewView, {
    // stale=true even though status is AVAILABLE — unavailable() alone never surfaced this.
    exchangeRate: { status: "AVAILABLE", stale: true, data: { rate: 1390.25, basisPoint: 1.2 } },
    calendar: { status: "UNAVAILABLE", unavailable: true, unavailableReason: "PROVIDER_UNSUPPORTED" },
    // 정상 상태: 배지도 강조 테두리도 없어야 한다.
    rankings: { status: "AVAILABLE", data: { items: [{ symbol: "NVDA", price: 120, currency: "USD", changePercent: 0.01 }] } },
    onRankingCategory() {}
  }));

  // stale이 AVAILABLE과 함께 와도 warn 배지 + market-widget--warn 로 드러난다.
  assert.match(html, /market-widget--warn"[^>]*>[\s\S]*?class="quality"[^>]*>[\s\S]*?지연/);
  assert.match(html, /₩1,390\.25/);
  // unavailable은 danger 배지 + market-widget--danger.
  assert.match(html, /market-widget--danger/);
  // 정상 랭킹 위젯은 어떤 강조 modifier 도 붙지 않는다.
  assert.doesNotMatch(html, /market-widget rankings-widget market-widget--/);
});
