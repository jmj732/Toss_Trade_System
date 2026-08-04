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
    "포트폴리오", "분석", "이벤트", "주문 검토",
    "지연", "확인 필요", "불러오기 실패", "account.cashBalance",
    "KRW", "USD", "NVDA", "승인", "취소"
  ]) {
    assert.match(html, new RegExp(text));
  }

  for (const className of [
    "dashboard-surface", "portfolio-panel", "portfolio-hero", "analysis-panel", "event-panel", "decision-queue"
  ]) {
    assert.match(html, new RegExp(`class=\\"[^\\"]*${className}`));
  }
});
