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
        { id: "busy", side: "BUY", symbol: "AAPL", currency: "USD" },
        { id: "free", side: "SELL", symbol: "NVDA", currency: "USD" }
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
