import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { DashboardView, orderStatusLabel, orderStatusBadgeState } from "../app/dashboard-view.js";

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
    "지연", "확인 필요", "불러오기 실패", "현금 잔고",
    "KRW", "USD", "NVDA", "승인", "취소"
  ]) {
    assert.match(html, new RegExp(text));
  }

  // D-26: 백엔드 내부 필드 경로가 그대로 노출되지 않는다.
  assert.ok(!html.includes("account.cashBalance"));

  for (const className of [
    "dashboard-surface", "portfolio-panel", "portfolio-hero", "analysis-panel", "event-panel", "decision-queue"
  ]) {
    assert.match(html, new RegExp(`class=\\"[^\\"]*${className}`));
  }
});

function section(data, quality = {}) {
  return {
    stale: false,
    unknown: false,
    unknownFields: [],
    unavailable: false,
    data,
    ...quality
  };
}

test("does not assert 최신 when a section is partial and labels missing sections in Korean", () => {
  const dashboard = {
    portfolio: section({
      partial: true,
      missingSections: ["BUYING_POWER_USD"],
      completedAt: "2026-08-05T00:00:00Z",
      account: { marketValueAmounts: { KRW: 1000 }, cashBalanceStatus: "KNOWN" },
      positions: [],
      buyingPower: {}
    }),
    analysis: section({ result: { status: "COMPLETED", currencyTotals: [], positions: [] } }),
    pendingEvents: section([]),
    pendingOrderProposals: section([])
  };

  const html = renderToStaticMarkup(createElement(DashboardView, {
    dashboard,
    busyOrderId: null,
    onOrderAction() {}
  }));

  assert.match(html, /일부 누락/);
  assert.match(html, /USD 주문 가능 금액/);
  // partial 인 포트폴리오 섹션 헤더에 "최신" 을 렌더하지 않는다.
  const portfolioHeader = html.slice(
    html.indexOf("portfolio-panel"),
    html.indexOf("analysis-panel"));
  assert.ok(!portfolioHeader.includes("최신"));
});

test("never leaks raw undefined/null/NaN/Invalid Date for missing optional fields", () => {
  const dashboard = {
    portfolio: section({
      // completedAt, account amounts, positions fields all absent
      account: { cashBalanceStatus: undefined },
      positions: [{ symbol: "NVDA" }],
      buyingPower: {}
    }, { stale: true }),
    analysis: section({ result: { positions: [{ symbol: "NVDA" }], currencyTotals: [{ currency: "USD" }] } }),
    pendingEvents: section([{ id: "e1" }]),
    pendingOrderProposals: section([{ id: "o1", side: undefined, symbol: undefined }])
  };

  const html = renderToStaticMarkup(createElement(DashboardView, {
    dashboard,
    busyOrderId: new Set(["o1"]),
    onOrderAction() {}
  }));

  for (const forbidden of ["undefined", "NaN", "Invalid Date", ">null<"]) {
    assert.ok(!html.includes(forbidden), `markup leaked "${forbidden}"`);
  }
});

test("the proposals section renders every status label and covers all 13 statuses (D-03)", () => {
  const ALL = {
    PROPOSED: "승인 대기", APPROVED: "승인됨", REVALIDATING: "재검증 중",
    SUBMISSION_PENDING: "전송 대기", RECONCILIATION_REQUIRED: "대사 필요",
    MANUAL_REVIEW_REQUIRED: "수동 검토 필요", ACTIVE: "체결 진행 중",
    PARTIALLY_COMPLETED: "부분 체결", COMPLETED: "체결 완료", CANCELED: "취소됨",
    REJECTED: "거부됨", EXPIRED: "만료됨", BLOCKED: "차단됨"
  };
  for (const [status, label] of Object.entries(ALL)) {
    assert.equal(orderStatusLabel(status), label);
  }
  // 미등록 상태는 원문을 노출한다. MANUAL_REVIEW_REQUIRED 는 고유 상태(warn)다.
  assert.equal(orderStatusLabel("QUEUED"), "알 수 없는 상태: QUEUED");
  assert.equal(orderStatusBadgeState("QUEUED"), "neutral");
  assert.equal(orderStatusBadgeState("MANUAL_REVIEW_REQUIRED"), "warn");
  assert.equal(orderStatusBadgeState("COMPLETED"), "ok");
});

test("the dashboard proposals section shows timing and blocks an expired approval (D-42)", () => {
  const dashboard = {
    portfolio: section({ account: {}, positions: [], buyingPower: {} }),
    analysis: section({ result: { currencyTotals: [], positions: [] } }),
    pendingEvents: section([]),
    pendingOrderProposals: section([{
      id: "o1", side: "BUY", type: "MARKET", symbol: "NVDA", quantity: 1,
      limitPrice: null, currency: "USD", status: "PROPOSED",
      createdAt: "2026-08-05T00:00:00Z", expiresAt: "2000-01-01T00:00:00Z"
    }])
  };

  const html = renderToStaticMarkup(createElement(DashboardView, {
    dashboard,
    busyOrderId: null,
    onOrderAction() {}
  }));

  assert.match(html, /기준 2026-08-05 09:00 KST/);
  assert.match(html, /만료됨/);
  assert.match(html, /badge-pill badge-pill--danger/);
  // 승인만 비활성화되고 취소는 남는다.
  assert.equal((html.match(/disabled/g) ?? []).length, 1);
});
