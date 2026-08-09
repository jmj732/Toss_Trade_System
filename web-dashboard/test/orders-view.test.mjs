import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { OrdersView } from "../app/orders-view.js";

function render(props) {
  return renderToStaticMarkup(createElement(OrdersView, {
    onOrderAction() {},
    busyOrderId: null,
    ...props
  }));
}

test("renders Korean side, formatted quantity, limit price, and a status badge", () => {
  const html = render({
    section: {
      data: [{
        id: "order-1", side: "BUY", type: "LIMIT", symbol: "NVDA",
        quantity: 1234.5, limitPrice: 100, currency: "USD", status: "PROPOSED"
      }]
    }
  });

  assert.match(html, /매수 NVDA/);
  assert.match(html, /1,234.5/);        // formatQuantity via lib/format
  assert.match(html, /USD 100.00/);     // limit price via formatAmount, 통화 접두
  assert.match(html, /승인 대기/);       // status badge
  assert.match(html, /승인/);
  assert.match(html, /취소/);
});

test("exposes an unknown status instead of hiding it (D-03)", () => {
  const html = render({
    section: { data: [{ id: "o", side: "SELL", symbol: "AAPL", currency: "USD", status: "QUEUED" }] }
  });
  assert.match(html, /알 수 없는 상태: QUEUED/);
});

test("shows a raw unknown side and disables actions (D-30)", () => {
  const html = render({
    section: { data: [{ id: "o", side: "WEIRD", symbol: "AAPL", currency: "USD" }] }
  });
  assert.match(html, /WEIRD AAPL/);
  // 두 버튼(승인/취소)이 모두 비활성화된다.
  assert.equal((html.match(/disabled/g) ?? []).length, 2);
});

test("falls back to UNKNOWN_TEXT and never leaks undefined for missing fields (R-02)", () => {
  const html = render({
    section: { data: [{ id: "o", side: "BUY", symbol: "AAPL" }] }
  });
  for (const forbidden of ["undefined", "NaN", "Invalid Date", ">null<"]) {
    assert.ok(!html.includes(forbidden), `markup leaked "${forbidden}"`);
  }
});

test("treats busyOrderId as a Set to guard concurrent orders (D-13)", () => {
  const html = render({
    section: {
      data: [
        { id: "busy", side: "BUY", symbol: "AAPL", currency: "USD", status: "PROPOSED" },
        { id: "free", side: "SELL", symbol: "NVDA", currency: "USD", status: "PROPOSED" }
      ]
    },
    busyOrderId: new Set(["busy"])
  });
  // busy 주문의 두 버튼만 비활성화, free 주문은 활성 상태.
  assert.equal((html.match(/disabled/g) ?? []).length, 2);
});

test("renders the unavailable reason path without crashing", () => {
  const html = render({ section: { unavailable: true } });
  assert.match(html, /주문 정보를 불러오지 못했습니다/);
});

test("renders distinct Korean labels and badge-pill states for non-PROPOSED statuses (D-03)", () => {
  const html = render({
    section: {
      data: [
        { id: "a", side: "BUY", symbol: "AAPL", currency: "USD", status: "MANUAL_REVIEW_REQUIRED" },
        { id: "b", side: "SELL", symbol: "NVDA", currency: "USD", status: "COMPLETED" },
        { id: "c", side: "BUY", symbol: "TSLA", currency: "USD", status: "REJECTED" }
      ]
    }
  });
  // MANUAL_REVIEW_REQUIRED 는 고유 상태로 렌더된다("수동 검토 필요"), unknown/pending 으로 접히지 않는다.
  assert.match(html, /수동 검토 필요/);
  assert.match(html, /badge-pill badge-pill--warn/);
  assert.match(html, /체결 완료/);
  assert.match(html, /badge-pill badge-pill--ok/);
  assert.match(html, /거부됨/);
  assert.match(html, /badge-pill badge-pill--danger/);
  assert.doesNotMatch(html, /알 수 없는 상태/);
});

test("shows creation and expiry timing; null expiresAt reads as 만료 없음 not expired (D-42)", () => {
  const html = render({
    section: {
      data: [{
        id: "o", side: "BUY", symbol: "AAPL", currency: "USD", status: "PROPOSED",
        createdAt: "2026-08-05T00:00:00Z", expiresAt: null
      }]
    }
  });
  assert.match(html, /기준 2026-08-05 09:00 KST/);
  assert.match(html, /만료 없음/);
  assert.doesNotMatch(html, /만료됨/);
  // 만료가 아니므로 승인은 활성 상태여야 한다(비활성 버튼 없음).
  assert.equal((html.match(/disabled/g) ?? []).length, 0);
});

test("disables 승인 (but not 취소) for an expired proposal and shows the expiry badge (D-42)", () => {
  const html = render({
    section: {
      data: [{
        id: "o", side: "BUY", symbol: "AAPL", currency: "USD", status: "PROPOSED",
        createdAt: "2026-08-05T00:00:00Z", expiresAt: "2000-01-01T00:00:00Z"
      }]
    }
  });
  assert.match(html, /만료됨/);
  assert.match(html, /badge-pill badge-pill--danger/);
  // 승인 1개만 비활성화되고 취소는 남는다.
  assert.equal((html.match(/disabled/g) ?? []).length, 1);
});

test("disables 승인 and 취소 for a non-PROPOSED proposal but leaves a fresh PROPOSED one actionable (D-03)", () => {
  // 상태 필터 확대로 APPROVED 등 확정·종결 주문이 목록에 도달해도 액션 버튼은 표시 전용이어야 한다.
  const settled = render({
    section: { data: [{ id: "o", side: "BUY", symbol: "AAPL", currency: "USD", status: "APPROVED" }] }
  });
  // 승인·취소 두 버튼 모두 비활성화된다.
  assert.equal((settled.match(/disabled/g) ?? []).length, 2);

  const proposed = render({
    section: { data: [{ id: "o", side: "BUY", symbol: "AAPL", currency: "USD", status: "PROPOSED" }] }
  });
  // 만료 문제가 없는 PROPOSED 는 두 버튼 모두 활성 상태다.
  assert.equal((proposed.match(/disabled/g) ?? []).length, 0);
});

test("offers modification only for an active live limit order", () => {
  const html = render({
    onModifyPrice() {},
    section: {
      data: [
        { id: "live", executionMode: "LIVE", side: "BUY", type: "LIMIT", symbol: "AAPL",
          quantity: 1, limitPrice: 180, currency: "USD", status: "ACTIVE" },
        { id: "paper", executionMode: "PAPER", side: "BUY", type: "LIMIT", symbol: "MSFT",
          quantity: 1, limitPrice: 180, currency: "USD", status: "ACTIVE" }
      ]
    }
  });
  assert.equal((html.match(/>정정</g) ?? []).length, 1);
});
