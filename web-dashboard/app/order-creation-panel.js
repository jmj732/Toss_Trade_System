"use client";

import { createElement as h, useState } from "react";

const INITIAL = { side: "BUY", type: "MARKET", quantity: "", limitPrice: "", currency: "USD" };

export function OrderCreationPanel({ connectionId, initialSymbol = "", initialSide = "BUY", busy = false, error = "", onCreate }) {
  const [values, setValues] = useState({ ...INITIAL, side: initialSide, symbol: initialSymbol });
  const unavailable = !connectionId;
  function update(name, value) {
    setValues(current => ({ ...current, [name]: value }));
  }
  function submit(event) {
    event.preventDefault();
    const quantity = Number(values.quantity);
    const limitPrice = values.type === "LIMIT" && values.limitPrice !== "" ? Number(values.limitPrice) : null;
    if (!values.symbol.trim() || !Number.isFinite(quantity) || quantity <= 0 || (limitPrice != null && (!Number.isFinite(limitPrice) || limitPrice <= 0))) return;
    onCreate?.({
      connectionId, side: values.side, type: values.type, symbol: values.symbol.trim().toUpperCase(),
      quantity, limitPrice, currency: values.currency, channel: "WEB"
    });
  }
  return h("section", { className: "panel order-creation-panel", "aria-busy": busy },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "Decision → Draft"), h("h2", null, "주문 작성")),
      h("span", { className: "badge-pill badge-pill--warn" }, "서버 위험 확인")),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    unavailable ? h("p", { className: "empty" }, "계좌를 먼저 선택하세요") : null,
    h("form", { className: "order-creation-form", onSubmit: submit },
      h("label", null, "심볼", h("input", { name: "symbol", value: values.symbol, onChange: event => update("symbol", event.target.value), required: true, maxLength: 12 })),
      h("label", null, "매수/매도", h("select", { name: "side", value: values.side, onChange: event => update("side", event.target.value) }, h("option", { value: "BUY" }, "매수"), h("option", { value: "SELL" }, "매도"))),
      h("label", null, "시장가/지정가", h("select", { name: "type", value: values.type, onChange: event => update("type", event.target.value) }, h("option", { value: "MARKET" }, "시장가"), h("option", { value: "LIMIT" }, "지정가"))),
      h("label", null, "수량", h("input", { name: "quantity", type: "number", min: "0.00000001", step: "any", value: values.quantity, onChange: event => update("quantity", event.target.value), required: true })),
      values.type === "LIMIT" ? h("label", null, "지정가", h("input", { name: "limitPrice", type: "number", min: "0.00000001", step: "any", value: values.limitPrice, onChange: event => update("limitPrice", event.target.value), required: true })) : null,
      h("label", null, "통화", h("select", { name: "currency", value: values.currency, onChange: event => update("currency", event.target.value) }, h("option", { value: "USD" }, "USD"), h("option", { value: "KRW" }, "KRW"))),
      h("button", { type: "submit", disabled: busy || unavailable }, busy ? "제안 생성 중…" : "위험 확인 후 제안 생성")),
    h("p", { className: "disclaimer" }, "제안 생성 후 서버 미리보기·위험 검사·step-up·승인 절차를 거칩니다."));
}
