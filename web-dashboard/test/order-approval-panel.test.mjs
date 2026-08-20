import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { OrderApprovalPanel, isStatusActionable } from "../app/order-approval-panel.jsx";

// renderToStaticMarkup 은 effect 를 실행하지 않으므로 preview 는 로드되지 않는다.
// 따라서 preview 에 의존하지 않는 만료 처리(D-42)만 정적으로 검증한다.
function render(props) {
  return renderToStaticMarkup(createElement(OrderApprovalPanel, {
    onConfirm() {},
    onReject() {},
    onClose() {},
    ...props
  }));
}

const FRESH_ORDER = {
  id: "order-1", side: "BUY", symbol: "AAPL", type: "LIMIT",
  quantity: 1, limitPrice: 100, currency: "USD", status: "PROPOSED"
};

test("blocks approval and shows the expiry reason for an expired proposal (D-42)", () => {
  const html = render({
    order: { ...FRESH_ORDER, createdAt: "2026-08-05T00:00:00Z", expiresAt: "2000-01-01T00:00:00Z" }
  });
  assert.match(html, /만료되어 승인할 수 없습니다/);
  assert.match(html, /만료 시각/);
  // 확인하고 승인 버튼이 비활성화된다.
  assert.match(html, /disabled=""[^>]*>확인하고 승인|확인하고 승인/);
  const confirmDisabled = /<button[^>]*disabled=""[^>]*>확인하고 승인/.test(html);
  assert.ok(confirmDisabled, "확인하고 승인 버튼이 비활성화되어야 한다");
  // 탈출구(거부/닫기)는 남아 있다.
  assert.match(html, /거부/);
  assert.match(html, /닫기/);
});

test("handles PAPER_ORDER_PROPOSAL_EXPIRED with no retry-approve affordance (D-42)", () => {
  const html = render({
    order: { ...FRESH_ORDER, createdAt: "2026-08-05T00:00:00Z", expiresAt: "2026-08-05T01:00:00Z" },
    error: { code: "PAPER_ORDER_PROPOSAL_EXPIRED", body: { code: "PAPER_ORDER_PROPOSAL_EXPIRED" } }
  });
  assert.match(html, /재시도해도 승인되지 않습니다/);
  // 재승인/재조회 어포던스는 없다.
  assert.doesNotMatch(html, /다시 시도/);
  assert.doesNotMatch(html, /최신 값 다시 불러오기/);
  // 거부/닫기만 남는다.
  assert.match(html, /거부/);
  assert.match(html, /닫기/);
});

test("a proposal without an expiry does not read as expired (D-42)", () => {
  const html = render({
    order: { ...FRESH_ORDER, createdAt: "2026-08-05T00:00:00Z", expiresAt: null }
  });
  assert.doesNotMatch(html, /만료되어 승인할 수 없습니다/);
});

// D-03: 승인 게이트는 status==='PROPOSED'(또는 {id} 폴백의 status 없음)일 때만 활성이어야 한다.
// renderToStaticMarkup 은 effect 를 실행하지 않아 preview 가 없으면 확인 버튼이 항상 비활성이므로,
// 게이트 자체는 순수 술어(isStatusActionable)로 직접 검증한다.
test("gates approval to PROPOSED (or the {id} fallback) only (D-03)", () => {
  assert.equal(isStatusActionable({ id: "o", status: "PROPOSED" }), true);
  assert.equal(isStatusActionable({ id: "o" }), true); // {id} 폴백: status 없음 → 승인 가능 유지
  assert.equal(isStatusActionable({ id: "o", status: undefined }), true);
  for (const status of ["APPROVED", "ACTIVE", "COMPLETED", "CANCELLED", "REJECTED", "EXPIRED"]) {
    assert.equal(isStatusActionable({ id: "o", status }), false, `${status} 는 승인 불가여야 한다`);
  }
});

test("renders the confirm button disabled for a non-PROPOSED proposal (D-03)", () => {
  const html = render({ order: { ...FRESH_ORDER, status: "APPROVED" } });
  const confirmDisabled = /<button[^>]*disabled=""[^>]*>확인하고 승인/.test(html);
  assert.ok(confirmDisabled, "APPROVED 주문의 확인하고 승인 버튼은 비활성화되어야 한다");
});

test("kill switch engaged blocks the confirm button and shows the reason (BC-7)", () => {
  const html = render({ order: FRESH_ORDER, tradingHalted: true });
  assert.match(html, /거래 중지됨 · 내 계정 기준/);
  assert.match(html, /data-approval-halted="true"/);
  const confirmDisabled = /<button[^>]*disabled=""[^>]*>확인하고 승인/.test(html);
  assert.ok(confirmDisabled, "거래 중지 시 확인하고 승인 버튼은 비활성화되어야 한다");
});

test("does not leak undefined/NaN/Invalid Date for a bare order", () => {
  const html = render({ order: { id: "o", side: "BUY", symbol: "AAPL" } });
  for (const forbidden of ["undefined", "NaN", "Invalid Date", ">null<"]) {
    assert.ok(!html.includes(forbidden), `markup leaked "${forbidden}"`);
  }
});
