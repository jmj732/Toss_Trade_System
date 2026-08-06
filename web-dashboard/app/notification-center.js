"use client";

import { createElement as h } from "react";

function formatTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function NotificationList({ notifications, busy, onMarkRead }) {
  if (notifications.length === 0) {
    return h("p", { className: "empty" }, "알림이 없습니다");
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
        }, "읽음 표시"))));
}

// D-34: unreadCount 는 세 상태다. null(미확정: 조회 실패/미로딩) · 0(없음) · 양수(개수).
// 미확정을 0("읽지 않음 없음")으로 위장하지 않고 별도 표기한다.
function unreadBadge(unreadCount) {
  if (unreadCount == null) {
    return h("span", {
      className: "badge",
      "aria-label": "읽지 않은 알림 수를 확인하지 못했습니다",
      title: "읽지 않은 알림 수 확인 필요"
    }, "?");
  }
  return unreadCount > 0 ? h("span", { className: "badge" }, String(unreadCount)) : null;
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
    "알림",
    unreadBadge(unreadCount)),
    open ? h("div", { className: "notification-panel panel" },
      h(NotificationList, { notifications, busy, onMarkRead })) : null);
}
