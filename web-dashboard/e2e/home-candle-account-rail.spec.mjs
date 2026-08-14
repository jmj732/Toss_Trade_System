import { expect, test } from "@playwright/test";

import { CONNECTION_ID, primeAuth, stateRoute } from "./fixtures/states.mjs";

test.beforeEach(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== "vp-1280", "home candle journey runs at 1280 only");
});

test("home operations shell requests the lower market candle once, then only on interval change", async ({ page, context }) => {
  const requests = [];
  await primeAuth(context, { withConnection: true });
  await page.route("**/api/v1/**", stateRoute("degraded", {
    delayMs: 0,
    onRequest: info => requests.push(info)
  }));

  await page.goto("/#access_token=test-token", { waitUntil: "domcontentloaded" });
  await expect(page.locator('[data-home-region="market-context"] .market-candle-chart')).toContainText("부분 데이터");

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
  await expect(page.locator(".home-operations-shell")).toBeVisible();
  for (const region of ["freshness-status", "core-metrics", "portfolio-trend", "holdings", "review-queue", "events", "market-context"]) {
    await expect(page.locator(`[data-home-region="${region}"]`)).toBeVisible();
  }
  const operationRegionsPrecedeChart = await page.locator(".home-operations-shell").evaluate(root => [
    "freshness-status", "core-metrics", "portfolio-trend", "holdings",
    "review-queue", "events", "market-context"
  ].every(region => (root.querySelector(`[data-home-region="${region}"]`)
    ?.compareDocumentPosition(root.querySelector(".market-candle-chart")) ?? 0) === Node.DOCUMENT_POSITION_FOLLOWING));
  expect(operationRegionsPrecedeChart).toBe(true);
});
