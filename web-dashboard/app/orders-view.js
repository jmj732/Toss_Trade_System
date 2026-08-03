import { createElement as h } from "react";

export function OrdersView({ section, busyOrderId, onOrderAction }) {
  const orders = section?.data ?? [];
  return h("section", { className: "panel orders-surface" },
    h("header", null, h("div", null,
      h("p", { className: "eyebrow" }, "ORDER CONTROL"), h("h1", null, "Orders")),
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
            }, "Approve"),
            h("button", {
              type: "button", className: "secondary", disabled: busyOrderId === order.id,
              onClick: () => onOrderAction(order.id, "cancel")
            }, "Cancel")))))
        : h("p", { className: "empty" }, "No pending proposals"));
}
