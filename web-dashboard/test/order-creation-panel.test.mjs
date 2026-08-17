import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { OrderCreationPanel } from "../app/order-creation-panel.js";

test("renders one server-backed order creation form without approval shortcuts", () => {
  const html = renderToStaticMarkup(createElement(OrderCreationPanel, {
    connectionId: "connection-1", initialSymbol: "NVDA", busy: false, onCreate() {}
  }));
  for (const text of ["주문 작성", "심볼", "매수/매도", "시장가/지정가", "수량", "위험 확인 후 제안 생성"]) {
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
