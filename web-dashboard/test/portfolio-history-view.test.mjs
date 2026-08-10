import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { PortfolioHistoryView } from "../app/portfolio-history-view.js";

const QUERY = { from: "", to: "", maxPoints: 90 };

test("renders separate KRW/USD trend lines, quality, and formatted points", () => {
  const html = renderToStaticMarkup(createElement(PortfolioHistoryView, {
    history: {
      stale: false,
      unknown: true,
      unknownFields: ["account.cashBalance"],
      unavailable: false,
      unavailableReason: null,
      data: {
        from: "2026-01-01T00:00:00Z",
        to: "2026-01-05T00:00:00Z",
        partial: false,
        totalMatched: 2,
        returnedPoints: 2,
        points: [
          {
            syncRunId: "run-1",
            completedAt: "2026-01-01T00:00:00Z",
            marketValueAmounts: { KRW: 130000, USD: 100 },
            profitLossAmounts: { KRW: 13000, USD: 10 },
            profitLossRate: 0.2,
            dailyProfitLossRate: 0.01
          },
          {
            syncRunId: "run-2",
            completedAt: "2026-01-05T00:00:00Z",
            marketValueAmounts: { KRW: 132000, USD: 110 },
            profitLossAmounts: { KRW: 14000, USD: 11 },
            profitLossRate: 0.21,
            dailyProfitLossRate: 0.015
          }
        ]
      }
    },
    query: QUERY,
    busy: false,
    onQuery() {}
  }));

  assert.match(html, /자산 · P\/L 추세/);
  // V-37: DashboardView 와 동일한 한국어 어휘. V-36: 공통 .badge-pill modifier.
  assert.match(html, /badge-pill badge-pill--warn[^>]*>확인 필요/);
  assert.doesNotMatch(html, /UNKNOWN/);
  assert.match(html, /현금 잔고/);
  assert.doesNotMatch(html, /account\.cashBalance/);
  assert.equal((html.match(/class="trend-krw"/g) ?? []).length, 2);
  assert.equal((html.match(/class="trend-usd"/g) ?? []).length, 2);
  assert.match(html, /KRW 130,000/);
  assert.match(html, /USD 110\.00/);
  assert.match(html, /2026-01-01 09:00 KST/);
  assert.doesNotMatch(html, /2026-01-01T00:00:00\.000Z/);
  assert.doesNotMatch(html, /\d+개 중 \d+개 표시/);
});

test("aligns a currency that only appears on later points to its actual position on the axis", () => {
  const points = [
    { syncRunId: "p0", completedAt: "t0", marketValueAmounts: { KRW: 100 }, profitLossAmounts: {} },
    { syncRunId: "p1", completedAt: "t1", marketValueAmounts: { KRW: 101 }, profitLossAmounts: {} },
    { syncRunId: "p2", completedAt: "t2", marketValueAmounts: { KRW: 102, USD: 50 }, profitLossAmounts: {} },
    { syncRunId: "p3", completedAt: "t3", marketValueAmounts: { KRW: 103, USD: 55 }, profitLossAmounts: {} }
  ];
  const html = renderToStaticMarkup(createElement(PortfolioHistoryView, {
    history: {
      stale: false, unknown: false, unknownFields: [], unavailable: false, unavailableReason: null,
      data: { from: "t0", to: "t3", partial: false, totalMatched: 4, returnedPoints: 4, points }
    },
    query: QUERY,
    busy: false,
    onQuery() {}
  }));

  const usdPath = html.match(/class="trend-usd" d="([^"]+)"/)[1];
  const krwPath = html.match(/class="trend-krw" d="([^"]+)"/)[1];
  // 4 points, lastIndex=3: USD only present at index 2 and 3, so its line must start at
  // x = 2/3 * 320 ≈ 213.33 — not at x=0, which would mean it was stretched across the
  // full width using only its own (shorter) series length instead of the shared axis.
  assert.match(usdPath, /^M213\.33,/);
  assert.match(krwPath, /^M0\.00,/);
});

test("shows an unavailable message and no trend when history has never synced", () => {
  const html = renderToStaticMarkup(createElement(PortfolioHistoryView, {
    history: {
      stale: false,
      unknown: false,
      unknownFields: [],
      unavailable: true,
      unavailableReason: "PORTFOLIO_HISTORY_NOT_FOUND",
      data: null
    },
    query: QUERY,
    busy: false,
    onQuery() {}
  }));

  assert.match(html, /아직 기록된 포트폴리오 이력이 없습니다/);
  assert.doesNotMatch(html, /PORTFOLIO_HISTORY_NOT_FOUND/);
  assert.doesNotMatch(html, /sparkline/);
});

test("shows a truncated-coverage notice when the series was downsampled", () => {
  const html = renderToStaticMarkup(createElement(PortfolioHistoryView, {
    history: {
      stale: false,
      unknown: false,
      unknownFields: [],
      unavailable: false,
      unavailableReason: null,
      data: {
        from: "2026-01-01T00:00:00Z",
        to: "2026-01-10T00:00:00Z",
        partial: true,
        totalMatched: 10,
        returnedPoints: 3,
        points: [
          {
            syncRunId: "run-1",
            completedAt: "2026-01-01T00:00:00Z",
            marketValueAmounts: { USD: 100 },
            profitLossAmounts: { USD: 10 },
            profitLossRate: 0.2,
            dailyProfitLossRate: 0.01
          }
        ]
      }
    },
    query: QUERY,
    busy: false,
    onQuery() {}
  }));

  assert.match(html, /10개 중 3개 표시/);
});

test("renders nothing before a connection has ever been opened", () => {
  const html = renderToStaticMarkup(createElement(PortfolioHistoryView, {
    history: null,
    query: QUERY,
    busy: false,
    onQuery() {}
  }));

  assert.match(html, /이력이 아직 없습니다/);
});

test("disables the apply button while a query is in flight", () => {
  const html = renderToStaticMarkup(createElement(PortfolioHistoryView, {
    history: null,
    query: QUERY,
    busy: true,
    onQuery() {}
  }));

  assert.match(html, /disabled=""/);
});
