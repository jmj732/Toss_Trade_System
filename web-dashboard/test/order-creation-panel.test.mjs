import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { OrderCreationPanel, PreviewResult, BuyingPowerBanner } from "../app/order-creation-panel.js";

test("renders one server-backed order creation form without approval shortcuts", () => {
  const html = renderToStaticMarkup(createElement(OrderCreationPanel, {
    connectionId: "connection-1", initialSymbol: "NVDA", busy: false, onCreate() {}
  }));
  for (const text of ["주문 작성", "심볼", "매수/매도", "시장가/지정가", "수량", "위험 확인", "제안 생성"]) {
    assert.match(html, new RegExp(text));
  }
  assert.match(html, /value="NVDA"/);
  assert.doesNotMatch(html, /즉시 주문|자동 승인/);
});

test("does not enable proposal creation without an owned connection", () => {
  const html = renderToStaticMarkup(createElement(OrderCreationPanel, {
    connectionId: "", initialSymbol: "NVDA", busy: false, onCreate() {}
  }));
  assert.match(html, /disabled=""/);
  assert.match(html, /계좌를 먼저 선택/);
});

test("offers a distinct 위험 확인 button ahead of 제안 생성", () => {
  const html = renderToStaticMarkup(createElement(OrderCreationPanel, {
    connectionId: "connection-1", initialSymbol: "NVDA", busy: false, onCreate() {}, onPreview() {}
  }));
  assert.match(html, /위험 확인/);
  assert.match(html, /제안 생성/);
  // 위험 확인이 제안 생성보다 앞선다(먼저 검사 → 그다음 생성).
  assert.ok(html.indexOf("위험 확인") < html.indexOf("제안 생성"));
});

test("disables order draft and shows the reason when trading is halted (BC-7)", () => {
  const html = renderToStaticMarkup(createElement(OrderCreationPanel, {
    connectionId: "connection-1", initialSymbol: "NVDA", busy: false,
    onCreate() {}, onPreview() {}, tradingHalted: true, haltReason: "수동 정지"
  }));
  assert.match(html, /거래가 중지되어 주문을 생성할 수 없습니다/);
  assert.match(html, /내 계정 기준/);
  assert.match(html, /수동 정지/);
  // 제안 생성 버튼은 비활성.
  assert.match(html, /disabled=""/);
});

test("PreviewResult renders a passing pre-check with the amount and a final-recheck notice", () => {
  const html = renderToStaticMarkup(createElement(PreviewResult, {
    preview: { approved: true, reasons: [], orderAmount: 100, currency: "USD", evaluatedAt: "2026-08-18T08:07:22Z" }
  }));
  assert.match(html, /현재 기준 사전 검사 통과/);
  assert.match(html, /USD\s*100/);
  // approved:true 가 승인 통과를 보장하지 않음을 반드시 알린다.
  assert.match(html, /승인 시 최종 재검사가 다시 실행됩니다/);
});

test("PreviewResult lists Korean rejection reasons with text, not color alone", () => {
  const html = renderToStaticMarkup(createElement(PreviewResult, {
    preview: {
      approved: false,
      reasons: ["MAX_QUANTITY_EXCEEDED", "CONCENTRATION_EXCEEDED", "SOME_NEW_REASON"],
      orderAmount: 1100, currency: "USD"
    }
  }));
  assert.match(html, /사전 검사 실패/);
  assert.match(html, /최대 주문 수량 한도를 초과했습니다/);
  assert.match(html, /종목 집중도 한도를 초과했습니다/);
  // 미등록 사유는 원문을 함께 노출한다.
  assert.match(html, /알 수 없는 사유: SOME_NEW_REASON/);
  // 색 단독이 아니라 텍스트 마크(✕)를 함께 쓴다.
  assert.match(html, /✕/);
});

test("PreviewResult surfaces the server error text for a 409/404", () => {
  const html = renderToStaticMarkup(createElement(PreviewResult, {
    preview: { error: "PAPER_ORDER_PORTFOLIO_SNAPSHOT_UNAVAILABLE" }
  }));
  assert.match(html, /PAPER_ORDER_PORTFOLIO_SNAPSHOT_UNAVAILABLE/);
});

// §11.6: BuyingPowerBanner 는 별도 카드가 아니라 주문 작성 폼 헤더로 병합된다.
test("BuyingPowerBanner renders buying power as currency-qualified stacked mobile-safe values", () => {
  const html = renderToStaticMarkup(createElement(BuyingPowerBanner, {
    buyingPower: { data: { KRW: { cashBuyingPower: 130000 }, USD: { cashBuyingPower: 100 } } }
  }));
  assert.match(html, /KRW 130,000/);
  assert.match(html, /USD 100\.00/);
  assert.doesNotMatch(html, /class="buying-power-banner" style=/);
});

test("OrderCreationPanel merges the buying-power banner into the form header (not a separate card)", () => {
  const html = renderToStaticMarkup(createElement(OrderCreationPanel, {
    connectionId: "connection-1", initialSymbol: "NVDA", busy: false, onCreate() {},
    buyingPower: { data: { KRW: { cashBuyingPower: 130000 }, USD: { cashBuyingPower: 100 } } }
  }));
  assert.match(html, /buying-power-banner/);
  assert.match(html, /KRW 130,000/);
  // 폼 헤더 병합: 배너가 "주문 작성" 헤더 뒤, 폼 앞에 온다.
  assert.ok(html.indexOf("주문 작성") < html.indexOf("buying-power-banner"));
  assert.ok(html.indexOf("buying-power-banner") < html.indexOf("order-creation-form"));
});
