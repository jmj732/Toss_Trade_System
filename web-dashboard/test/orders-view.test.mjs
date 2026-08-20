import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { OrdersView, OrderContextTabs } from "../app/orders-view.js";

const stylesUrl = new URL("../app/globals.css", import.meta.url);

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

test("keeps side, symbol, quantity, price, currency, status, and timing together in each order row", () => {
  const html = render({
    section: {
      data: [{
        id: "order-1", side: "SELL", type: "LIMIT", symbol: "NVDA",
        quantity: 12, limitPrice: 100, currency: "USD", status: "ACTIVE",
        createdAt: "2026-08-05T00:00:00Z", expiresAt: "2026-08-06T00:00:00Z"
      }]
    }
  });

  assert.match(html, /data-order-row="order-1"[\s\S]*매도[\s\S]*NVDA/);
  assert.match(html, /data-order-row="order-1"[\s\S]*12[\s\S]*USD 100.00/);
  assert.match(html, /data-order-row="order-1"[\s\S]*체결 진행 중/);
  assert.match(html, /data-order-row="order-1"[\s\S]*기준 2026-08-05 09:00 KST[\s\S]*만료 2026-08-06 09:00 KST/);
});

test("stacks the orders header before mobile rows at the <=900px breakpoint", async () => {
  const css = await readFile(stylesUrl, "utf8");

  assert.match(
    css,
    /@media \(max-width: 900px\) \{[\s\S]*\.orders-surface > header \{[\s\S]*flex-direction: column;[\s\S]*\}/
  );
});

test("does not style a disabled cancel action as a live danger action", async () => {
  const css = await readFile(stylesUrl, "utf8");
  assert.match(css, /button\.danger:disabled\s*\{/);
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

// ---------------------------------------------------------------------------
// P2: Paper/Live 실행 컨텍스트 분리 + kill switch 게이트
// ---------------------------------------------------------------------------

test("OrderContextTabs shows per-mode counts, an unknown note, and marks the active tab", () => {
  const html = renderToStaticMarkup(createElement(OrderContextTabs, {
    context: "PAPER",
    orders: [
      { executionMode: "PAPER" }, { executionMode: "PAPER" },
      { executionMode: "LIVE" },
      { executionMode: undefined }
    ],
    onSelect() {}
  }));
  assert.match(html, /모의\(Paper\) \(2\)/);
  assert.match(html, /실거래\(Live\) \(1\)/);
  assert.match(html, /구분 미확인 1건/);
  assert.match(html, /aria-selected="true"[^>]*>모의/);
  assert.match(html, /data-order-context="PAPER"/);
});

test("PAPER context filters the queue to paper orders and hides live ones", () => {
  const html = render({
    context: "PAPER",
    section: {
      data: [
        { id: "p", executionMode: "PAPER", side: "BUY", symbol: "AAPL", currency: "USD", status: "PROPOSED" },
        { id: "l", executionMode: "LIVE", side: "BUY", symbol: "NVDA", currency: "USD", status: "PROPOSED" }
      ]
    }
  });
  assert.match(html, /data-order-row="p"/);
  assert.doesNotMatch(html, /data-order-row="l"/);
  // Paper 컨텍스트의 신선한 PROPOSED 주문은 승인·취소가 활성이다.
  assert.equal((html.match(/disabled/g) ?? []).length, 0);
});

test("LIVE context surfaces the approve/dispatch/modify/cancel hint and keeps modify active", () => {
  const html = render({
    context: "LIVE",
    onModifyPrice() {},
    section: {
      data: [
        { id: "l", executionMode: "LIVE", side: "BUY", type: "LIMIT", symbol: "NVDA",
          quantity: 1, limitPrice: 100, currency: "USD", status: "ACTIVE" }
      ]
    }
  });
  assert.match(html, /data-live-order-hint/);
  // 문구가 실제 동작과 일치한다: 승인·전송·정정·취소 가능, 생성 미지원.
  assert.match(html, /승인·전송·정정·취소가 가능합니다/);
  assert.match(html, /주문 생성은 아직 지원하지 않습니다/);
  assert.doesNotMatch(html, /실거래는 정정만 가능합니다/);
  // 정정은 연동돼 있어 활성 상태다.
  assert.doesNotMatch(html, /<button[^>]*disabled=""[^>]*>정정<\/button>/);
});

test("LIVE context enables approve and cancel for a fresh PROPOSED live order (no longer blocked)", () => {
  const html = render({
    context: "LIVE",
    section: {
      data: [
        { id: "l", executionMode: "LIVE", side: "BUY", type: "MARKET", symbol: "NVDA",
          quantity: 1, currency: "USD", status: "PROPOSED" }
      ]
    }
  });
  assert.match(html, /data-order-row="l"/);
  // 실거래 승인·취소가 이제 연동돼 활성 상태다(이전엔 컨텍스트가 무조건 비활성화했다).
  assert.equal((html.match(/disabled/g) ?? []).length, 0);
  // PROPOSED 라 아직 전송 버튼은 없다(전송은 APPROVED 이후 별도 단계).
  assert.doesNotMatch(html, /브로커로 전송/);
});

test("LIVE context shows a separate 브로커로 전송 button for an APPROVED live order (승인 ≠ 전송)", () => {
  const dispatched = [];
  const html = render({
    context: "LIVE",
    onDispatch: id => dispatched.push(id),
    section: {
      data: [
        { id: "a", executionMode: "LIVE", side: "BUY", type: "MARKET", symbol: "NVDA",
          quantity: 1, currency: "USD", status: "APPROVED" }
      ]
    }
  });
  // 승인됨 상태 배지 + "승인 ≠ 전송" 부연 + 별도 전송 버튼.
  assert.match(html, /브로커로 전송/);
  assert.match(html, /data-order-dispatch="a"/);
  assert.match(html, /data-order-dispatch-note="a"/);
  assert.match(html, /브로커 전송은 별도 단계입니다/);
  // 승인된 주문은 더 이상 PROPOSED 가 아니므로 승인 검토는 비활성이다(D-03).
  assert.match(html, /<button[^>]*disabled=""[^>]*>승인 검토<\/button>/);
});

test("kill switch engaged disables the live 브로커로 전송 button and shows the reason (BC-7)", () => {
  const html = render({
    context: "LIVE",
    tradingHalted: true,
    onDispatch() {},
    section: {
      data: [
        { id: "a", executionMode: "LIVE", side: "BUY", type: "MARKET", symbol: "NVDA",
          quantity: 1, currency: "USD", status: "APPROVED" }
      ]
    }
  });
  assert.match(html, /data-orders-halted/);
  assert.match(html, /거래 중지됨 · 내 계정 기준/);
  assert.match(html, /<button[^>]*disabled=""[^>]*>브로커로 전송<\/button>/);
});

test("unknown executionMode orders go to a separate disabled 구분 미확인 group (not silently paper)", () => {
  const html = render({
    context: "PAPER",
    section: {
      data: [
        { id: "p", executionMode: "PAPER", side: "BUY", symbol: "AAPL", currency: "USD", status: "PROPOSED" },
        { id: "u", side: "BUY", symbol: "TSLA", currency: "USD", status: "PROPOSED" }
      ]
    }
  });
  assert.match(html, /data-order-unknown-group/);
  assert.match(html, /구분 미확인/);
  // 미지 주문 행은 렌더되지만 실행 액션은 비활성이다.
  assert.match(html, /data-order-row="u"/);
  assert.match(html, /data-order-unknown-group[\s\S]*data-order-row="u"[\s\S]*disabled=""/);
});

test("kill switch engaged disables approve and cancel and shows the reason (BC-7)", () => {
  const html = render({
    context: "PAPER",
    tradingHalted: true,
    section: {
      data: [
        { id: "p", executionMode: "PAPER", side: "BUY", symbol: "AAPL", currency: "USD", status: "PROPOSED" }
      ]
    }
  });
  assert.match(html, /data-orders-halted/);
  assert.match(html, /거래 중지됨 · 내 계정 기준/);
  // 신선한 PROPOSED 여도 거래중지면 승인·취소 두 버튼 모두 비활성이다.
  assert.equal((html.match(/disabled/g) ?? []).length, 2);
});

test("kill switch engaged also disables modify on an active live limit order (BC-7)", () => {
  const html = render({
    context: "LIVE",
    tradingHalted: true,
    onModifyPrice() {},
    section: {
      data: [
        { id: "l", executionMode: "LIVE", side: "BUY", type: "LIMIT", symbol: "NVDA",
          quantity: 1, limitPrice: 100, currency: "USD", status: "ACTIVE" }
      ]
    }
  });
  assert.match(html, /<button[^>]*disabled=""[^>]*>정정<\/button>/);
});

test("legacy (no context) behaviour is unchanged: neither halted nor mode-split", () => {
  const html = render({
    section: {
      data: [
        { id: "p", side: "BUY", symbol: "AAPL", currency: "USD", status: "PROPOSED" }
      ]
    }
  });
  assert.doesNotMatch(html, /data-orders-halted/);
  assert.doesNotMatch(html, /data-order-unknown-group/);
  // 컨텍스트 미지정 시 신선한 PROPOSED 는 활성 상태를 유지한다.
  assert.equal((html.match(/disabled/g) ?? []).length, 0);
});
