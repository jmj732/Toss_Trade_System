import assert from "node:assert/strict";
import test from "node:test";

import { portfolioStateSection, unavailablePortfolioStateSection } from "../lib/portfolio-state.js";

test("maps the shared portfolio state without recalculating server metrics", () => {
  const section = portfolioStateSection({
    asOf: "2026-08-05T00:00:00Z",
    currency: "USD",
    account: { totalValue: 1000, cash: 100, cashPct: 10 },
    positions: [{
      symbol: "AAPL", quantity: 1, avgPrice: 100, currentPrice: 110,
      marketValue: 110, weightPct: 11, unrealizedPnlPct: 10, currency: "USD"
    }],
    openOrders: [{ symbol: "MSFT", quantity: 1, status: "PENDING" }],
    risk: { largestPositionPct: 11, investedPct: 90, cashPct: 10 },
    stale: false, partial: false, missingSections: [], unknownFields: []
  });

  assert.equal(section.data.account.marketValueAmounts.USD, 1000);
  assert.equal(section.data.buyingPower.USD.cashBuyingPower, 100);
  assert.equal(section.data.positions[0].weight, 0.11);
  assert.equal(section.data.positions[0].unrealizedPnlPct, 10);
  assert.equal(section.data.openOrders.length, 1);
  assert.deepEqual(section.data.risk, { largestPositionPct: 11, investedPct: 90, cashPct: 10 });
});

test("preserves canonical account P/L maps, rates, and state timestamps", () => {
  const section = portfolioStateSection({
    asOf: "2026-08-05T00:00:00Z",
    sourceAsOf: "2026-08-04T23:59:58Z",
    syncedAt: "2026-08-05T00:00:03Z",
    currency: "USD",
    account: {
      totalValue: 1000,
      cash: 100,
      totalPurchaseAmounts: { USD: 800 },
      marketValueAmounts: { USD: 900 },
      marketValueAfterCostAmounts: { USD: 899 },
      profitLossAmounts: { USD: 100 },
      profitLossAfterCostAmounts: { USD: 99 },
      dailyProfitLossAmounts: { USD: 5 },
      profitLossRate: 0.125,
      profitLossRateAfterCost: 0.123,
      dailyProfitLossRate: 0.006,
      observedAt: "2026-08-04T23:59:58Z"
    },
    positions: [], openOrders: [], risk: null,
    stale: false, partial: false, missingSections: [], unknownFields: []
  });

  assert.equal(section.asOf, "2026-08-05T00:00:00Z");
  assert.equal(section.sourceAsOf, "2026-08-04T23:59:58Z");
  assert.equal(section.syncedAt, "2026-08-05T00:00:03Z");
  assert.equal(section.data.completedAt, "2026-08-05T00:00:03Z");
  assert.deepEqual(section.data.account.totalPurchaseAmounts, { USD: 800 });
  assert.deepEqual(section.data.account.marketValueAmounts, { USD: 900 });
  assert.deepEqual(section.data.account.profitLossAmounts, { USD: 100 });
  assert.deepEqual(section.data.account.dailyProfitLossAmounts, { USD: 5 });
  assert.equal(section.data.account.profitLossRate, 0.125);
  assert.equal(section.data.account.profitLossRateAfterCost, 0.123);
  assert.equal(section.data.account.dailyProfitLossRate, 0.006);
});

test("unavailable state stays explicit for the dashboard", () => {
  const section = unavailablePortfolioStateSection({}, "SYNC_FAILED");

  assert.equal(section.stale, true);
  assert.equal(section.unknown, true);
  assert.deepEqual(section.unknownFields, ["PORTFOLIO_STATE"]);
  assert.equal(section.data.partial, true);
  assert.deepEqual(section.data.missingSections, ["PORTFOLIO_STATE"]);
  assert.equal(section.data.staleReason, "SYNC_FAILED");
});

test("null collections stay unknown instead of becoming empty provider results", () => {
  const section = portfolioStateSection({
    asOf: null, sourceAsOf: null, syncedAt: null, currency: null,
    account: null, positions: null, openOrders: null, risk: null,
    stale: true, partial: true, missingSections: ["ACCOUNT"], unknownFields: ["PORTFOLIO_STATE"]
  });

  assert.equal(section.data.positions, null);
  assert.equal(section.data.openOrders, null);
  assert.equal(section.unknown, true);
});
