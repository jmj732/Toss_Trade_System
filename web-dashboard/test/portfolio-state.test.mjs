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

test("unavailable state stays explicit for the dashboard", () => {
  const section = unavailablePortfolioStateSection({}, "SYNC_FAILED");

  assert.equal(section.stale, true);
  assert.equal(section.unknown, true);
  assert.deepEqual(section.unknownFields, ["PORTFOLIO_STATE"]);
  assert.equal(section.data.partial, true);
  assert.deepEqual(section.data.missingSections, ["PORTFOLIO_STATE"]);
  assert.equal(section.data.staleReason, "SYNC_FAILED");
});
