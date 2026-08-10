import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { PaperPerformanceView } from "../app/paper-performance-view.js";

const QUERY = { from: "", to: "", maxPoints: 90 };

test("renders per-currency metrics without summing or converting KRW/USD", () => {
  const html = renderToStaticMarkup(createElement(PaperPerformanceView, {
    performance: {
      unavailable: false,
      unavailableReason: null,
      data: {
        from: "2026-01-01T00:00:00Z",
        to: "2026-01-05T00:00:00Z",
        byCurrency: {
          USD: {
            realizedPnl: 195.4,
            unrealizedPnl: 0,
            totalFees: 2.2,
            totalTax: 2.4,
            turnover: 2200,
            closedTradeCount: 1,
            winningTradeCount: 1,
            winRate: 1,
            maxDrawdown: 0,
            partial: false,
            totalMatched: 1,
            returnedPoints: 1,
            equityCurve: [{ capturedAt: "2026-01-01T00:01:00Z", cumulativeRealizedPnl: 195.4 }]
          },
          KRW: {
            realizedPnl: -102500,
            unrealizedPnl: 0,
            totalFees: 700,
            totalTax: 1200,
            turnover: 1300000,
            closedTradeCount: 1,
            winningTradeCount: 0,
            winRate: 0,
            maxDrawdown: 102500,
            partial: false,
            totalMatched: 1,
            returnedPoints: 1,
            equityCurve: [{ capturedAt: "2026-01-01T00:01:00Z", cumulativeRealizedPnl: -102500 }]
          }
        }
      }
    },
    query: QUERY,
    busy: false,
    onQuery() {}
  }));

  assert.match(html, /모의 매매 성과/);
  assert.match(html, /실제 주문 체결이나/);
  assert.match(html, />USD</);
  assert.match(html, />KRW</);
  assert.match(html, /195\.4/);
  assert.match(html, /-102500/);
});

test("shows an unavailable message and no metrics when the connection has never traded", () => {
  const html = renderToStaticMarkup(createElement(PaperPerformanceView, {
    performance: {
      unavailable: true,
      unavailableReason: "PAPER_PERFORMANCE_NOT_FOUND",
      data: null
    },
    query: QUERY,
    busy: false,
    onQuery() {}
  }));

  assert.match(html, /PAPER_PERFORMANCE_NOT_FOUND/);
  assert.doesNotMatch(html, /metrics-grid/);
});

test("shows a truncated-coverage notice when the equity curve was downsampled", () => {
  const html = renderToStaticMarkup(createElement(PaperPerformanceView, {
    performance: {
      unavailable: false,
      unavailableReason: null,
      data: {
        from: "2026-01-01T00:00:00Z",
        to: "2026-01-10T00:00:00Z",
        byCurrency: {
          USD: {
            realizedPnl: 10,
            unrealizedPnl: 0,
            totalFees: 0,
            totalTax: 0,
            turnover: 100,
            closedTradeCount: 10,
            winningTradeCount: 5,
            winRate: 0.5,
            maxDrawdown: 5,
            partial: true,
            totalMatched: 10,
            returnedPoints: 3,
            equityCurve: [
              { capturedAt: "t0", cumulativeRealizedPnl: 1 },
              { capturedAt: "t1", cumulativeRealizedPnl: 2 },
              { capturedAt: "t2", cumulativeRealizedPnl: 3 }
            ]
          }
        }
      }
    },
    query: QUERY,
    busy: false,
    onQuery() {}
  }));

  assert.match(html, /청산 거래 10개 중 3개 표시/);
});

test("renders nothing before a connection has ever been opened", () => {
  const html = renderToStaticMarkup(createElement(PaperPerformanceView, {
    performance: null,
    query: QUERY,
    busy: false,
    onQuery() {}
  }));

  assert.match(html, /모의 거래가 아직 없습니다/);
});

test("disables the apply button while a query is in flight", () => {
  const html = renderToStaticMarkup(createElement(PaperPerformanceView, {
    performance: null,
    query: QUERY,
    busy: true,
    onQuery() {}
  }));

  assert.match(html, /disabled=""/);
});

test("distinguishes initial loading, refresh, stale, unauthorized, and unsupported envelopes", () => {
  const loading = renderToStaticMarkup(createElement(PaperPerformanceView, {
    performance: null,
    query: QUERY,
    busy: true,
    onQuery() {}
  }));
  assert.match(loading, /불러오는 중/);

  const refreshing = renderToStaticMarkup(createElement(PaperPerformanceView, {
    performance: {
      stale: true,
      asOf: "2026-05-01T00:00:00Z",
      unavailable: false,
      data: { byCurrency: {} }
    },
    query: QUERY,
    busy: true,
    onQuery() {}
  }));
  assert.match(refreshing, /새로고침 중/);
  assert.match(refreshing, /지연 데이터/);
  assert.match(refreshing, /기준 2026-05-01 09:00 KST/);

  const unauthorized = renderToStaticMarkup(createElement(PaperPerformanceView, {
    performance: { status: "UNAUTHORIZED", unavailableReason: "UNAUTHORIZED", data: null },
    query: QUERY,
    busy: false,
    onQuery() {}
  }));
  assert.match(unauthorized, /권한 확인 필요/);
  assert.doesNotMatch(unauthorized, /지원되지 않음/);

  const unsupported = renderToStaticMarkup(createElement(PaperPerformanceView, {
    performance: { status: "UNAVAILABLE", unavailable: true, unavailableReason: "PROVIDER_UNSUPPORTED", data: null },
    query: QUERY,
    busy: false,
    onQuery() {}
  }));
  assert.match(unsupported, /지원되지 않음/);
  assert.match(unsupported, /PROVIDER_UNSUPPORTED/);
});
