"use client";

import { createElement as h, useEffect, useId } from "react";

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
  const panelId = useId();
  // V-29: 열려 있을 때만 Escape 로 닫는다. open 상태는 부모 소유이므로 onToggle 로 위임한다.
  useEffect(() => {
    if (!open) {
      return undefined;
    }
    function onKeyDown(event) {
      if (event.key === "Escape") {
        onToggle();
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open, onToggle]);

  return h("div", { className: "notification-center" },
    h("button", {
      type: "button",
      className: "secondary notification-toggle",
      "aria-expanded": open,
      "aria-controls": panelId,
      onClick: onToggle
    },
    "알림",
    unreadBadge(unreadCount)),
    open ? h("div", { id: panelId, className: "notification-panel panel" },
      h(NotificationList, { notifications, busy, onMarkRead })) : null);
}
