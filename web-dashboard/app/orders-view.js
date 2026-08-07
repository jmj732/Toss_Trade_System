import { createElement as h } from "react";

import { formatAmount, formatQuantity, classifyProposalExpiry, UNKNOWN_TEXT } from "../lib/format.js";
import { OrderStatusBadge, OrderExpiryBadge, OrderTiming } from "./dashboard-view.js";
import { describeError } from "./route-workspace.js";

// D-30: 미지 값이 조용히 "매도" 로 접히지 않도록 명시적으로만 매핑한다.
const SIDE_LABELS = { BUY: "매수", SELL: "매도" };

function knownSide(side) {
  return Object.prototype.hasOwnProperty.call(SIDE_LABELS, side);
}

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

export function OrdersView({ section, busyOrderId, onOrderAction }) {
  const orders = section?.data ?? [];
  return h("section", { className: "panel orders-surface" },
    h("header", null, h("div", null,
      h("p", { className: "eyebrow" }, "주문 관제"), h("h1", null, "주문")),
      h("p", { className: "disclaimer" },
        "승인·취소만 이 화면에서 수행합니다. 모든 명령은 기존 step-up과 안전 게이트를 통과합니다.")),
    section?.unavailable
      // D-27: 백엔드 코드(예: ORDERS_UNAVAILABLE)를 한국어 안내로 옮긴다. 미등록 코드만 원문 노출.
      ? h("p", { className: "empty" }, describeError(section.unavailableReason) ?? "주문 정보를 불러오지 못했습니다")
      : orders.length
        ? h("ul", { className: "list proposals" }, ...orders.map(order => {
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
          return h("li", { key: order.id },
            h("div", null,
              h("strong", null, `${sideText} ${order.symbol ?? UNKNOWN_TEXT}`),
              h("span", null,
                `${order.type ?? UNKNOWN_TEXT} · ${formatQuantity(order.quantity)}`
                + ` · ${order.currency ?? UNKNOWN_TEXT}${priceText}`),
              // D-03: 상태 배지. 미지 상태도 그대로 드러낸다.
              h(OrderStatusBadge, { status: order.status }),
              // D-42: 만료 배지와 생성/만료 시각.
              h(OrderExpiryBadge, { state: expiryState }),
              h(OrderTiming, { order })),
            h("div", { className: "actions" },
              h("button", {
                type: "button",
                // D-30: 미지 side, D-42: 만료 제안은 오발주 방지를 위해 승인을 비활성화한다.
                disabled: busy || !sideKnown || !actionable || expired,
                onClick: () => onOrderAction(order.id, "approve")
              }, "승인"),
              h("button", {
                type: "button", className: "secondary",
                disabled: busy || !sideKnown || !actionable,
                onClick: () => onOrderAction(order.id, "cancel")
              }, "취소")));
        }))
        : h("p", { className: "empty" }, "대기 중인 주문이 없습니다"));
}
