"use client";

import { createElement as h } from "react";

function formatTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function NotificationList({ notifications, busy, onMarkRead }) {
  if (notifications.length === 0) {
    return h("p", { className: "empty" }, "No notifications");
  }
  return h("ul", { className: "list notification-list" }, ...notifications.map(item =>
    h("li", { key: item.id, className: item.readAt ? "read" : "unread" },
      h("div", null,
        h("strong", null, item.title),
        h("span", null, item.body),
        h("span", null, formatTime(item.createdAt))),
      item.readAt
        ? null
        : h("button", {
          type: "button",
          className: "secondary",
          disabled: busy,
          onClick: () => onMarkRead(item.id)
        }, "Mark read"))));
}

export function NotificationCenter({
  unreadCount,
  notifications,
  open,
  busy,
  onToggle,
  onMarkRead
}) {
  return h("div", { className: "notification-center" },
    h("button", {
      type: "button",
      className: "secondary notification-toggle",
      "aria-expanded": open,
      onClick: onToggle
    },
    "Notifications",
    unreadCount > 0 ? h("span", { className: "badge" }, String(unreadCount)) : null),
    open ? h("div", { className: "notification-panel panel" },
      h(NotificationList, { notifications, busy, onMarkRead })) : null);
}
