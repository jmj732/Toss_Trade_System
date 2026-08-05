import { createElement as h } from "react";

export function OrdersView({ section, busyOrderId, onOrderAction }) {
  const orders = section?.data ?? [];
  return h("section", { className: "panel orders-surface" },
    h("header", null, h("div", null,
      h("p", { className: "eyebrow" }, "주문 관제"), h("h1", null, "주문")),
      h("p", { className: "disclaimer" },
        "승인·취소만 이 화면에서 수행합니다. 모든 명령은 기존 step-up과 안전 게이트를 통과합니다.")),
    section?.unavailable
      ? h("p", { className: "empty" }, section.unavailableReason ?? "ORDERS_UNAVAILABLE")
      : orders.length
        ? h("ul", { className: "list proposals" }, ...orders.map(order => h("li", { key: order.id },
          h("div", null,
            h("strong", null, `${order.side} ${order.symbol}`),
            h("span", null, `${order.type} · ${order.quantity} · ${order.currency}`)),
          h("div", { className: "actions" },
            h("button", {
              type: "button", disabled: busyOrderId === order.id,
              onClick: () => onOrderAction(order.id, "approve")
            }, "승인"),
            h("button", {
              type: "button", className: "secondary", disabled: busyOrderId === order.id,
              onClick: () => onOrderAction(order.id, "cancel")
            }, "취소")))))
        : h("p", { className: "empty" }, "대기 중인 주문이 없습니다"));
}
