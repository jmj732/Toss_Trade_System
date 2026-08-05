import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { NotificationCenter } from "../app/notification-center.js";

test("renders the unread badge and toggle without opening the panel", () => {
  const html = renderToStaticMarkup(createElement(NotificationCenter, {
    unreadCount: 3,
    notifications: [],
    open: false,
    busy: false,
    onToggle() {},
    onMarkRead() {}
  }));

  assert.match(html, /알림/);
  assert.match(html, />3</);
  assert.doesNotMatch(html, /notification-panel/);
});

test("hides the badge when there is nothing unread", () => {
  const html = renderToStaticMarkup(createElement(NotificationCenter, {
    unreadCount: 0,
    notifications: [],
    open: false,
    busy: false,
    onToggle() {},
    onMarkRead() {}
  }));

  assert.doesNotMatch(html, /class="badge"/);
});

test("lists notifications and only offers mark-read for unread ones", () => {
  const html = renderToStaticMarkup(createElement(NotificationCenter, {
    unreadCount: 1,
    notifications: [
      {
        id: "notification-1",
        type: "ORDER_RESULT",
        title: "Order COMPLETED",
        body: "COMPLETED order for NVDA: no additional reason",
        createdAt: "2026-07-28T00:00:00Z",
        readAt: null
      },
      {
        id: "notification-2",
        type: "SYNC_SUCCEEDED",
        title: "Portfolio sync completed",
        body: "Your portfolio data is up to date.",
        createdAt: "2026-07-27T00:00:00Z",
        readAt: "2026-07-27T00:05:00Z"
      }
    ],
    open: true,
    busy: false,
    onToggle() {},
    onMarkRead() {}
  }));

  assert.match(html, /Order COMPLETED/);
  assert.match(html, /Portfolio sync completed/);
  assert.equal((html.match(/읽음 표시/g) ?? []).length, 1);
});

test("shows an empty state when the panel is open with no notifications", () => {
  const html = renderToStaticMarkup(createElement(NotificationCenter, {
    unreadCount: 0,
    notifications: [],
    open: true,
    busy: false,
    onToggle() {},
    onMarkRead() {}
  }));

  assert.match(html, /알림이 없습니다/);
});

test("disables mark-read while a mutation is in flight", () => {
  const html = renderToStaticMarkup(createElement(NotificationCenter, {
    unreadCount: 1,
    notifications: [{
      id: "notification-1",
      type: "ORDER_RESULT",
      title: "Order COMPLETED",
      body: "body",
      createdAt: "2026-07-28T00:00:00Z",
      readAt: null
    }],
    open: true,
    busy: true,
    onToggle() {},
    onMarkRead() {}
  }));

  assert.match(html, /disabled=""/);
});
