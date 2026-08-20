import { expect, test } from "@playwright/test";

import {
  CONNECTIONS_FULL,
  freezeClock,
  fullDashboard,
  jsonResponse,
  KILL_SWITCH_ENGAGED,
  primeAuth,
  RISK_POLICY,
  SESSION
} from "./fixtures/states.mjs";

// P2: Orders is split by execution context. Paper and Live are different blast
// radii, so the screen must never blur them:
//   - the queue shows one context at a time, filtered on executionMode;
//   - Live cannot create orders, only act on existing ones;
//   - an order whose executionMode cannot be determined is quarantined rather than
//     silently treated as Paper;
//   - approving a Live order does NOT send it to the broker — dispatch is a second,
//     separate button;
//   - an engaged kill switch disables every execution control and says why.
//
// The proposals fixture is declared inline because these are exactly the fields the
// shared fixture does not vary, and mixing contexts is the point of the spec.

const PROPOSALS = [
  {
    id: "paper-proposed",
    side: "BUY", type: "MARKET", symbol: "NVDA", quantity: 1, limitPrice: null, currency: "USD",
    executionMode: "PAPER", status: "PROPOSED",
    createdAt: "2026-08-05T23:55:00Z", expiresAt: "2026-08-06T00:20:00Z"
  },
  {
    id: "live-proposed",
    side: "SELL", type: "LIMIT", symbol: "AAPL", quantity: 3, limitPrice: 210.5, currency: "USD",
    executionMode: "LIVE", status: "PROPOSED",
    createdAt: "2026-08-05T23:56:00Z", expiresAt: "2026-08-06T00:25:00Z"
  },
  {
    // Approved but NOT yet sent to the broker: the dispatch step is still pending.
    id: "live-approved",
    side: "BUY", type: "MARKET", symbol: "MSFT", quantity: 2, limitPrice: null, currency: "USD",
    executionMode: "LIVE", status: "APPROVED",
    createdAt: "2026-08-05T23:40:00Z", expiresAt: "2026-08-06T01:00:00Z"
  },
  {
    // executionMode absent entirely — must not be folded into Paper.
    id: "unknown-mode",
    side: "BUY", type: "MARKET", symbol: "GOOGL", quantity: 1, limitPrice: null, currency: "USD",
    status: "PROPOSED",
    createdAt: "2026-08-05T23:50:00Z", expiresAt: "2026-08-06T00:30:00Z"
  }
];

function ordersDashboard() {
  const dash = fullDashboard();
  dash.pendingOrderProposals = { ...dash.pendingOrderProposals, data: PROPOSALS };
  return dash;
}

async function openOrders(page, context, { killSwitch = null, path = "/orders" } = {}) {
  await primeAuth(context, { withConnection: true });
  // The proposals' expiry gates the approve button; without a frozen clock they all
  // read as expired and every assertion below would pass for the wrong reason.
  await freezeClock(context);

  await page.route("**/api/v1/**", async route => {
    const request = route.request();
    const { pathname } = new URL(request.url());

    if (/\/api\/v1\/session$/.test(pathname)) return jsonResponse(route, 200, SESSION);
    if (/\/risk-policy(\/history)?$/.test(pathname)) {
      return jsonResponse(route, 200, pathname.includes("history") ? [] : RISK_POLICY);
    }
    if (/\/broker-connections$/.test(pathname)) return jsonResponse(route, 200, CONNECTIONS_FULL);
    if (/\/dashboard$/.test(pathname)) return jsonResponse(route, 200, ordersDashboard());
    if (/\/trading\/kill-switch$/.test(pathname)) {
      return jsonResponse(route, 200, killSwitch ?? { scope: "USER", engaged: false, reason: null });
    }
    if (/\/events$/.test(pathname)) return jsonResponse(route, 200, []);
    if (/\/buying-power$/.test(pathname)) {
      return jsonResponse(route, 200, {
        status: "AVAILABLE", stale: false, unknown: false, unknownFields: [],
        unavailable: false, unavailableReason: null, provenance: [],
        data: { USD: { cashBuyingPower: 5000 } }
      });
    }
    if (/\/paper-orders\/[^/]+\/approval-preview$/.test(pathname)) {
      return jsonResponse(route, 200, {
        displayedQuantity: 1, displayedMaxLoss: 100, displayedCurrency: "USD", proposalVersion: null
      });
    }
    return jsonResponse(route, 200, {});
  });

  await page.goto(`${path}#access_token=test-token`, {
    waitUntil: "domcontentloaded",
    timeout: 30000
  });
  await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
  await expect(page.locator(".orders-surface")).toBeVisible();
}

function tab(page, name) {
  return page.locator('[role="tablist"][data-order-context]').getByRole("tab", { name });
}

// Order ids currently listed in the main (context-filtered) queue, excluding the
// quarantined group.
function queuedIds(page) {
  return page.locator('.orders-surface > ul.proposals > li[data-order-row]')
    .evaluateAll(nodes => nodes.map(node => node.getAttribute("data-order-row")));
}

test.beforeEach(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== "vp-1280", "orders execution context runs at 1280 only");
});

test("Paper and Live tabs filter the queue by executionMode", async ({ page, context }) => {
  await openOrders(page, context);

  // Paper is the default context.
  await expect(tab(page, /모의/)).toHaveAttribute("aria-selected", "true");
  expect(await queuedIds(page)).toEqual(["paper-proposed"]);

  await tab(page, /실거래/).click();
  await expect(tab(page, /실거래/)).toHaveAttribute("aria-selected", "true");
  expect(await queuedIds(page)).toEqual(["live-proposed", "live-approved"]);

  // Switching back must not leak Live rows into Paper.
  await tab(page, /모의/).click();
  expect(await queuedIds(page)).toEqual(["paper-proposed"]);
});

test("Live replaces the order creation form with an explicit notice", async ({ page, context }) => {
  await openOrders(page, context);

  // Paper can draft orders.
  await expect(page.locator(".order-creation-panel")).toHaveCount(1);
  await expect(page.locator("[data-live-order-notice]")).toHaveCount(0);

  await tab(page, /실거래/).click();

  // Live cannot: the form is gone and replaced by a notice that says so.
  await expect(page.locator(".order-creation-panel")).toHaveCount(0);
  const notice = page.locator("[data-live-order-notice]");
  await expect(notice).toBeVisible();
  await expect(notice).toContainText("실거래 주문 생성은 아직 지원하지 않습니다");
});

test("orders of unknown execution context are quarantined with actions disabled", async ({ page, context }) => {
  await openOrders(page, context);

  // The unknown row is in neither context queue.
  expect(await queuedIds(page)).not.toContain("unknown-mode");
  await tab(page, /실거래/).click();
  expect(await queuedIds(page)).not.toContain("unknown-mode");

  const group = page.locator("[data-order-unknown-group]");
  await expect(group).toBeVisible();
  await expect(group).toContainText("구분 미확인");
  // The tab strip also has to admit the count rather than hiding it.
  await expect(page.locator("[data-order-unknown-count]")).toHaveText("구분 미확인 1건");

  const row = group.locator('[data-order-row="unknown-mode"]');
  await expect(row).toHaveCount(1);
  // Every control in the quarantined row must be inert — an unknown blast radius is
  // not a reason to guess.
  const buttons = row.locator("button");
  const count = await buttons.count();
  expect(count).toBeGreaterThan(0);
  for (let index = 0; index < count; index += 1) {
    await expect(buttons.nth(index)).toBeDisabled();
  }
});

test("an APPROVED Live order exposes broker dispatch as a step separate from approval", async ({ page, context }) => {
  await openOrders(page, context);
  await tab(page, /실거래/).click();

  const approved = page.locator('[data-order-row="live-approved"]');
  const dispatch = approved.locator("[data-order-dispatch]");

  await expect(dispatch).toBeVisible();
  await expect(dispatch).toHaveText("브로커로 전송");
  await expect(dispatch).toBeEnabled();

  // It must be a genuinely different control from the approval button, and the row
  // must state that approval alone did not send the order.
  const approve = approved.getByRole("button", { name: "승인 검토" });
  await expect(approve).toHaveCount(1);
  await expect(approve).toBeDisabled(); // already APPROVED — approval is done
  await expect(approved.locator("[data-order-dispatch-note]"))
    .toContainText("브로커 전송은 별도 단계입니다");

  // A still-PROPOSED Live order has no dispatch button: nothing to send yet.
  await expect(page.locator('[data-order-row="live-proposed"] [data-order-dispatch]'))
    .toHaveCount(0);
});

test("an engaged kill switch disables every execution control and states the reason", async ({ page, context }) => {
  await openOrders(page, context, { killSwitch: KILL_SWITCH_ENGAGED });

  // The reason has to be on screen, not just the fact of the halt.
  const banner = page.locator('[data-kill-switch="engaged"]');
  await expect(banner).toBeVisible();
  await expect(banner).toContainText(KILL_SWITCH_ENGAGED.reason);
  await expect(page.locator('[data-orders-halted="true"]')).toBeVisible();

  for (const contextName of [/모의/, /실거래/]) {
    await tab(page, contextName).click();
    const rows = page.locator("li[data-order-row]");
    const rowCount = await rows.count();
    expect(rowCount, "there must be rows to check").toBeGreaterThan(0);
    for (let index = 0; index < rowCount; index += 1) {
      const buttons = rows.nth(index).locator("button");
      const buttonCount = await buttons.count();
      for (let button = 0; button < buttonCount; button += 1) {
        const control = buttons.nth(button);
        await expect(
          control,
          `"${(await control.innerText()).trim()}" must be disabled while trading is halted`
        ).toBeDisabled();
      }
    }
  }

  // The Paper draft form must refuse too, with its own reason shown.
  await tab(page, /모의/).click();
  await expect(page.locator('[data-order-halted="true"]')).toBeVisible();
});

test("the ?order= deep link opens that order's approval panel in its own context", async ({ page, context }) => {
  await openOrders(page, context, { path: "/orders?order=live-proposed" });

  // The deep link must switch the context tab to match the order, or the user would
  // be approving something the visible queue does not even list.
  await expect(tab(page, /실거래/)).toHaveAttribute("aria-selected", "true");
  await expect(page.locator(".order-approval-panel")).toBeVisible();
});
