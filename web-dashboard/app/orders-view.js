import { createElement as h, useState } from "react";

import { formatAmount, formatQuantity, classifyProposalExpiry, UNKNOWN_TEXT } from "../lib/format.js";
import { describeError } from "../lib/error-messages.js";
import { OrderStatusBadge, OrderExpiryBadge, OrderTiming } from "./dashboard-view.js";

// ---------------------------------------------------------------------------
// 매수 가능 금액 배너 — loadAccountBuyingPower()
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

export function OrdersView({ section, busyOrderId, onOrderAction, onModifyPrice, buyingPower }) {
const SIDE_LABELS = { BUY: "매수", SELL: "매도" };

function knownSide(side) {
  return Object.prototype.hasOwnProperty.call(SIDE_LABELS, side);
}

const OPEN_ORDER_STATUSES = new Set([
  "PROPOSED", "APPROVED", "REVALIDATING", "SUBMISSION_PENDING", "RECONCILIATION_REQUIRED",
  "MANUAL_REVIEW_REQUIRED", "ACTIVE", "PARTIALLY_COMPLETED", "BLOCKED"
]);
const CLOSED_ORDER_STATUSES = new Set(["COMPLETED", "CANCELED", "REJECTED", "EXPIRED"]);

// D-13: busyOrderId 가 스칼라(현행)든 Set/배열(권장)이든 모두 처리한다.
function isOrderBusy(busy, id) {
  if (busy == null) {
    return false;
  }
  if (typeof busy === "string") {
    return busy === id;
  }
  if (busy instanceof Set) {
    return busy.has(id);
  }
  if (Array.isArray(busy)) {
    return busy.includes(id);
  }
  return false;
}

// D-30: 미지 값이 조용히 "매도" 로 접히지 않도록 명시적으로만 매핑한다.
  const [filterTab, setFilterTab] = useState("ALL");
  const [modifyingId, setModifyingId] = useState(null);
  const [newPriceInput, setNewPriceInput] = useState("");

  const orders = section?.data ?? [];

  const filteredOrders = orders.filter(order => {
    if (filterTab === "OPEN") {
      return OPEN_ORDER_STATUSES.has(order.status);
    }
    if (filterTab === "CLOSED") {
      return CLOSED_ORDER_STATUSES.has(order.status);
    }
    return true;
  });

  return h("section", { className: "panel orders-surface" },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "주문 관제 · Toss Invest OpenAPI"),
        h("h1", null, "주문 목록 및 관리")),
      h("p", { className: "disclaimer" },
        "승인·정정·취소를 이 화면에서 관제합니다. 모든 매매 명령은 사전 위험 통제 엔진 및 step-up 인증을 경유합니다.")),
    h("div", { className: "order-tabs", style: { display: "flex", flexWrap: "wrap", gap: "8px", marginBottom: "16px" } },
      h("button", {
        type: "button",
        className: filterTab === "ALL" ? "primary" : "secondary",
        onClick: () => setFilterTab("ALL")
      }, `전체 (${orders.length})`),
      h("button", {
        type: "button",
        className: filterTab === "OPEN" ? "primary" : "secondary",
        onClick: () => setFilterTab("OPEN")
      }, `진행 중 (${orders.filter(o => OPEN_ORDER_STATUSES.has(o.status)).length})`),
      h("button", {
        type: "button",
        className: filterTab === "CLOSED" ? "primary" : "secondary",
        onClick: () => setFilterTab("CLOSED")
      }, `종료/내역 (${orders.filter(o => CLOSED_ORDER_STATUSES.has(o.status)).length})`)
    ),
    h(BuyingPowerBanner, { buyingPower }),
    section?.unavailable
      // D-27: 백엔드 코드(예: ORDERS_UNAVAILABLE)를 한국어 안내로 옮긴다. 미등록 코드만 원문 노출.
      ? h("p", { className: "empty" }, describeError(section.unavailableReason) ?? "주문 정보를 불러오지 못했습니다")
      : filteredOrders.length
        ? h("ul", { className: "list proposals" }, ...filteredOrders.map(order => {
          const sideKnown = knownSide(order.side);
          // D-29/D-30: 한국어 매수/매도, 미지 side 는 원문 노출.
          const sideText = sideKnown ? SIDE_LABELS[order.side] : (order.side ?? UNKNOWN_TEXT);
          // D-29: limitPrice 를 요약과 동일하게 표시한다.
          const priceText = order.limitPrice == null
            ? ""
            : ` @ ${formatAmount(order.currency, order.limitPrice)}`;
          const busy = isOrderBusy(busyOrderId, order.id);
          // D-42: 만료된 제안은 승인을 막는다. 서버 409 는 최후 방어선일 뿐이다.
          const expiryState = classifyProposalExpiry(order);
          const expired = expiryState === "expired";
          // D-03: 상태 필터가 넓어져 APPROVED/ACTIVE/COMPLETED 등도 이 목록에 도달한다. PROPOSED 만 액션 대상이고 나머지는 표시 전용이다.
          const actionable = order.status === "PROPOSED";
          const modifiable = order.executionMode === "LIVE"
            && order.status === "ACTIVE"
            && order.type === "LIMIT";
          const isModifying = modifyingId === order.id;

          return h("li", {
            key: order.id,
            className: `order-row order-row--${sideKnown ? order.side.toLowerCase() : "unknown"}`,
            "data-order-row": order.id
          },
            h("div", { className: "order-row-main" },
              h("div", { className: "order-row-copy" },
                h("strong", { className: "order-title" }, `${sideText} ${order.symbol ?? UNKNOWN_TEXT}`),
                h("span", { className: "order-summary" },
                  `${order.type ?? UNKNOWN_TEXT} · ${formatQuantity(order.quantity)}`
                  + ` · ${order.currency ?? UNKNOWN_TEXT}${priceText}`),
                // D-03: 상태 배지. 미지 상태도 그대로 드러낸다.
                h(OrderStatusBadge, { status: order.status }),
                // D-42: 만료 배지와 생성/만료 시각.
                h(OrderExpiryBadge, { state: expiryState }),
                h(OrderTiming, { order })),
              h("div", { className: "actions order-row-actions" },
                h("button", {
                  type: "button",
                  // D-30: 미지 side, D-42: 만료 제안은 오발주 방지를 위해 승인을 비활성화한다.
                  disabled: busy || !sideKnown || !actionable || expired,
                  onClick: () => onOrderAction(order.id, "approve")
                }, "승인 검토"),
                modifiable && order.limitPrice != null && onModifyPrice
                  ? h("button", {
                    type: "button", className: "secondary",
                    disabled: busy || !sideKnown,
                    onClick: () => {
                      setModifyingId(isModifying ? null : order.id);
                      setNewPriceInput(String(order.limitPrice ?? ""));
                    }
                  }, "정정")
                  : null,
                h("button", {
                  type: "button", className: "danger",
                  disabled: busy || !sideKnown || !actionable,
                  onClick: () => onOrderAction(order.id, "cancel")
                }, "주문 취소"))
            ),
            isModifying
              ? h("div", { className: "modify-panel", style: { marginTop: "12px", padding: "12px", background: "var(--panel-raised)", borderRadius: "var(--r-md)", display: "flex", gap: "12px", alignItems: "center" } },
                h("label", { style: { fontSize: "var(--fs-sm)", fontWeight: "var(--fw-bold)" } }, "정정 단가:"),
                h("input", {
                  type: "number",
                  step: "0.01",
                  value: newPriceInput,
                  onChange: (e) => setNewPriceInput(e.target.value),
                  style: { width: "120px", padding: "6px 10px", borderRadius: "var(--r-sm)", border: "1px solid var(--line)" }
                }),
                h("button", {
                  type: "button",
                  disabled: busy || !newPriceInput,
                  onClick: () => {
                    if (onModifyPrice && newPriceInput) {
                      onModifyPrice(order.id, parseFloat(newPriceInput));
                      setModifyingId(null);
                    }
                  }
                }, "정정 전송"),
                h("button", {
                  type: "button",
                  className: "secondary",
                  onClick: () => setModifyingId(null)
                }, "닫기")
              )
              : null
          );
        }))
        : h("p", { className: "empty" }, "대기 중인 주문이 없습니다"));
}
