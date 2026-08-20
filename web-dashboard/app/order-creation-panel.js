"use client";

import { createElement as h, useState } from "react";

import { formatAmount, formatFreshness, UNKNOWN_TEXT } from "../lib/format.js";
import { describeError } from "../lib/error-messages.js";
import { describeRiskReason } from "../lib/risk-reasons.js";

const INITIAL = { side: "BUY", type: "MARKET", quantity: "", limitPrice: "", currency: "USD" };

// ---------------------------------------------------------------------------
// 매수 가능 금액 배너 — loadAccountBuyingPower().
// §11.6: 별도 카드가 아니라 주문 작성 폼 헤더로 병합한다(주문 작성 맥락에서만 의미).
// ---------------------------------------------------------------------------
export function BuyingPowerBanner({ buyingPower }) {
  if (!buyingPower) return null;
  if (buyingPower.status === "UNAVAILABLE" || buyingPower.unavailable) {
    return h("p", { className: "empty" }, `매수 가능 금액을 사용할 수 없습니다 (${buyingPower.unavailableReason ?? "UNAVAILABLE"})`);
  }
  if (buyingPower.status === "ERROR") {
    return h("p", { className: "empty", role: "alert" }, `매수 가능 금액 조회에 실패했습니다 (${buyingPower.unavailableReason ?? "ERROR"})`);
  }
  if (buyingPower.status === "LOADING") {
    return h("p", { className: "empty" }, "매수 가능 금액을 불러오는 중…");
  }
  const data = buyingPower.data ?? buyingPower;
  const krw = data.krw ?? data.KRW;
  const usd = data.usd ?? data.USD;
  const degraded = buyingPower.status === "DEGRADED" || buyingPower.stale || buyingPower.unknown;
  return h("div", { className: "buying-power-banner" },
    degraded ? h("p", { className: "disclaimer" }, "일부 매수 가능 금액은 오래됐거나 확인되지 않았습니다.") : null,
    h("div", null,
      h("span", null, "원화 (KRW) 매수 가능 금액"),
      h("strong", null, krw != null ? formatAmount("KRW", krw.cashBuyingPower ?? krw) : UNKNOWN_TEXT)),
    h("div", null,
      h("span", null, "외화 (USD) 매수 가능 금액"),
      h("strong", null, usd != null ? formatAmount("USD", usd.cashBuyingPower ?? usd) : UNKNOWN_TEXT)));
}

// BC-6: preview 결과 블록. approved:true 는 "승인이 반드시 통과한다"는 보장이 아니다.
// preview 는 Phase.APPROVAL 이라 FINAL 전용 재검증(kill switch, 매도가능수량, 미체결 주문,
// 계좌 소유)은 포함하지 않는다. 따라서 "현재 기준" + "승인 시 최종 재검사"를 반드시 함께 알린다.
export function PreviewResult({ preview }) {
  if (!preview) {
    return null;
  }
  if (preview.error) {
    return h("div", { className: "order-preview order-preview--error", role: "alert", "data-order-preview": "error" },
      h("p", { className: "error" }, preview.error));
  }
  if (preview.approved === false) {
    const reasons = Array.isArray(preview.reasons) ? preview.reasons : [];
    return h("div", { className: "order-preview order-preview--rejected", role: "alert", "data-order-preview": "rejected" },
      h("p", { className: "order-preview-title" },
        h("span", { className: "badge-pill badge-pill--danger" }, "사전 검사 실패"),
        "이 주문은 현재 기준 사전 위험 검사를 통과하지 못했습니다"),
      reasons.length
        ? h("ul", { className: "list order-preview-reasons" }, ...reasons.map((code, index) =>
          h("li", { key: `${code}-${index}`, className: "order-preview-reason", "data-risk-reason": code },
            h("span", { className: "order-preview-reason-mark", "aria-hidden": "true" }, "✕"),
            h("span", { className: "order-preview-reason-text" }, describeRiskReason(code)))))
        : h("p", { className: "empty" }, "서버가 사유를 제공하지 않았습니다"));
  }
  // approved: true
  return h("div", { className: "order-preview order-preview--passed", "data-order-preview": "passed" },
    h("p", { className: "order-preview-title" },
      h("span", { className: "badge-pill badge-pill--ok" }, "사전 검사 통과"),
      "현재 기준 사전 검사 통과"),
    h("dl", { className: "order-preview-metrics" },
      h("div", null, h("dt", null, "주문 금액"), h("dd", null, formatAmount(preview.currency, preview.orderAmount))),
      h("div", null, h("dt", null, "기준 시각"), h("dd", null, formatFreshness(preview.evaluatedAt)))),
    h("p", { className: "disclaimer" }, "승인 시 최종 재검사가 다시 실행됩니다. 현재 통과가 승인 통과를 보장하지 않습니다."));
}

export function OrderCreationPanel({
  connectionId,
  initialSymbol = "",
  initialSide = "BUY",
  busy = false,
  error = "",
  onCreate,
  onPreview,
  tradingHalted = false,
  haltReason = "",
  buyingPower = null
}) {
  const [values, setValues] = useState({ ...INITIAL, side: initialSide, symbol: initialSymbol });
  // preview 상태는 폼 값과 함께 이 컴포넌트가 소유한다. 폼이 바뀌면 즉시 무효화해
  // 낡은 통과 표시가 사용자를 오도하지 못하게 한다.
  const [preview, setPreview] = useState(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const unavailable = !connectionId;

  function update(name, value) {
    setValues(current => ({ ...current, [name]: value }));
    // BC-6: 폼 값 변경 시 이전 preview 결과를 즉시 폐기(무효화).
    setPreview(null);
  }

  function buildCommand() {
    const quantity = Number(values.quantity);
    const limitPrice = values.type === "LIMIT" && values.limitPrice !== "" ? Number(values.limitPrice) : null;
    if (!values.symbol.trim()
      || !Number.isFinite(quantity) || quantity <= 0
      || (limitPrice != null && (!Number.isFinite(limitPrice) || limitPrice <= 0))) {
      return null;
    }
    return {
      connectionId, side: values.side, type: values.type, symbol: values.symbol.trim().toUpperCase(),
      quantity, limitPrice, currency: values.currency, channel: "WEB"
    };
  }

  const command = buildCommand();
  const formValid = Boolean(command);

  function runPreview() {
    if (!command || !onPreview || previewBusy) {
      return;
    }
    setPreviewBusy(true);
    setPreview(null);
    Promise.resolve()
      .then(() => onPreview(command))
      .then(result => setPreview(result ?? null))
      .catch(value => setPreview({ error: describeError(value.message) }))
      .finally(() => setPreviewBusy(false));
  }

  function submit(event) {
    event.preventDefault();
    if (!command || tradingHalted) {
      return;
    }
    onCreate?.(command);
  }

  // preview 가 approved:false 면 제안 생성을 게이트한다. 아직 실행하지 않았거나 통과면
  // 활성으로 둔다 — 서버가 최종 방어선이므로 UI 가 유일 게이트여서는 안 된다.
  const rejected = Boolean(preview) && !preview.error && preview.approved === false;
  const createDisabled = busy || unavailable || tradingHalted || rejected;
  const previewDisabled = previewBusy || unavailable || tradingHalted || !formValid;

  return h("section", { className: "panel order-creation-panel", "aria-busy": busy },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "Decision → Draft"), h("h2", null, "주문 작성")),
      h("span", { className: "badge-pill badge-pill--warn" }, "서버 위험 확인")),
    // §11.6: 매수 가능 금액을 폼 헤더에 병합한다(별도 카드 금지).
    h(BuyingPowerBanner, { buyingPower }),
    tradingHalted
      ? h("p", { className: "error", role: "alert", "data-order-halted": "true" },
        `거래가 중지되어 주문을 생성할 수 없습니다 · 내 계정 기준${haltReason ? ` · ${haltReason}` : ""}`)
      : null,
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    unavailable ? h("p", { className: "empty" }, "계좌를 먼저 선택하세요") : null,
    h("form", { className: "order-creation-form", onSubmit: submit },
      h("label", null, "심볼", h("input", { name: "symbol", value: values.symbol, onChange: event => update("symbol", event.target.value), required: true, maxLength: 12 })),
      h("label", null, "매수/매도", h("select", { name: "side", value: values.side, onChange: event => update("side", event.target.value) }, h("option", { value: "BUY" }, "매수"), h("option", { value: "SELL" }, "매도"))),
      h("label", null, "시장가/지정가", h("select", { name: "type", value: values.type, onChange: event => update("type", event.target.value) }, h("option", { value: "MARKET" }, "시장가"), h("option", { value: "LIMIT" }, "지정가"))),
      h("label", null, "수량", h("input", { name: "quantity", type: "number", min: "0.00000001", step: "any", value: values.quantity, onChange: event => update("quantity", event.target.value), required: true })),
      values.type === "LIMIT" ? h("label", null, "지정가", h("input", { name: "limitPrice", type: "number", min: "0.00000001", step: "any", value: values.limitPrice, onChange: event => update("limitPrice", event.target.value), required: true })) : null,
      h("label", null, "통화", h("select", { name: "currency", value: values.currency, onChange: event => update("currency", event.target.value) }, h("option", { value: "USD" }, "USD"), h("option", { value: "KRW" }, "KRW"))),
      h("button", {
        type: "button", className: "secondary order-preview-button",
        disabled: previewDisabled, onClick: runPreview
      }, previewBusy ? "위험 확인 중…" : "위험 확인"),
      h("button", { type: "submit", disabled: createDisabled }, busy ? "제안 생성 중…" : "제안 생성")),
    h(PreviewResult, { preview }),
    h("p", { className: "disclaimer" }, "제안 생성 후 서버 미리보기·위험 검사·step-up·승인 절차를 거칩니다."));
}
