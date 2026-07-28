import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { DashboardView } from "../app/dashboard-view.js";

test("renders all dashboard sections and explicit data quality", () => {
  const dashboard = {
    portfolio: {
      stale: true,
      unknown: true,
      unknownFields: ["account.cashBalance"],
      unavailable: false,
      data: {
        account: {
          displayAccountNumber: "****5678",
          marketValueAmounts: { USD: 120 },
          profitLossAmounts: { USD: 20 },
          cashBalanceStatus: "UNKNOWN"
        },
        positions: [{
          symbol: "NVDA",
          name: "NVIDIA",
          quantity: 1,
          currency: "USD",
          marketValueAmount: 120,
          profitLossAmount: 20
        }],
        buyingPower: { KRW: { cashBuyingPower: 1000 } }
      }
    },
    analysis: {
      stale: false,
      unknown: false,
      unknownFields: [],
      unavailable: false,
      data: {
        result: {
          currencyTotals: [{ currency: "USD", marketValue: 120, profitLoss: 20 }],
          positions: [{ symbol: "NVDA", currency: "USD", weight: 1 }]
        }
      }
    },
    pendingEvents: {
      stale: false,
      unknown: false,
      unknownFields: [],
      unavailable: true,
      unavailableReason: "EVENTS_UNAVAILABLE",
      data: null
    },
    pendingOrderProposals: {
      stale: false,
      unknown: false,
      unknownFields: [],
      unavailable: false,
      data: [{
        id: "order-1",
        side: "BUY",
        type: "MARKET",
        symbol: "NVDA",
        quantity: 1,
        limitPrice: null,
        currency: "USD",
        status: "PROPOSED"
      }]
    }
  };

  const html = renderToStaticMarkup(createElement(DashboardView, {
    dashboard,
    busyOrderId: null,
    onOrderAction() {}
  }));

  for (const text of [
    "Portfolio", "Analysis", "Events", "Order proposals",
    "STALE", "UNKNOWN", "UNAVAILABLE", "account.cashBalance",
    "KRW", "USD", "NVDA", "Approve", "Cancel"
  ]) {
    assert.match(html, new RegExp(text));
  }
});
