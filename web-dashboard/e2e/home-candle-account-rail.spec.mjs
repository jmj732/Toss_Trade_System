import { expect, test } from "@playwright/test";

import { CONNECTION_ID, primeAuth, stateRoute } from "./fixtures/states.mjs";

test.beforeEach(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== "vp-1280", "home candle journey runs at 1280 only");
});

test("home requests the first holding candle once, then only on interval change", async ({ page, context }) => {
  const requests = [];
  await primeAuth(context, { withConnection: true });
  await page.route("**/api/v1/**", stateRoute("degraded", {
    delayMs: 0,
    onRequest: info => requests.push(info)
  }));

  await page.goto("/#access_token=test-token", { waitUntil: "domcontentloaded" });
  await expect(page.locator(".market-candle-chart")).toContainText("부분 데이터");

  const candleRequests = () => requests.filter(request => request.url.endsWith("/candles")
    && (request.query === "?symbol=NVDA&interval=1m" || request.query === "?symbol=NVDA&interval=1d"));
  await expect.poll(() => candleRequests().length).toBe(1);
  expect(`${candleRequests()[0].url}${candleRequests()[0].query}`).toBe(
    `/api/v1/broker-connections/${CONNECTION_ID}/candles?symbol=NVDA&interval=1m`
  );

  await page.getByRole("button", { name: "일봉" }).click();
  await expect.poll(() => candleRequests().length).toBe(2);
  expect(`${candleRequests()[1].url}${candleRequests()[1].query}`).toBe(
    `/api/v1/broker-connections/${CONNECTION_ID}/candles?symbol=NVDA&interval=1d`
  );

  await page.getByRole("button", { name: "일봉" }).click();
  await page.waitForTimeout(100);
  expect(candleRequests()).toHaveLength(2);
  await expect(page.locator(".home-dashboard-main .market-candle-chart")).toBeVisible();
  await expect(page.locator(".home-dashboard-account")).toBeVisible();
});
