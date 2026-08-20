import { expect, test } from "@playwright/test";

import { CONNECTION_ID, freezeClock, primeAuth, stateRoute } from "./fixtures/states.mjs";

// Two invariants survive the Adaptive Decision Workspace rework, and both are the
// reason this spec exists:
//
//   (a) request economy — the lower market candle is fetched exactly once on entry
//       and thereafter only when the interval actually changes. Re-clicking the
//       interval already selected must issue zero requests.
//   (b) reading order — candles and market context are context, not decisions, so
//       they must come after every decision region in the DOM.
//
// What changed is only *where* those regions live: the old flat `.home-operations-shell`
// with its own region vocabulary is gone. Regions are now weighted children of
// `.home-decision-shell`, and at weight 0 the market region is a collapsed <details>,
// so the chart has to be expanded before it can be clicked.
const DECISION_REGIONS = ["actions", "risk", "summary", "positions"];

test.beforeEach(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== "vp-1280", "home candle journey runs at 1280 only");
});

test("home requests the lower market candle once, then only on interval change", async ({ page, context }) => {
  const requests = [];
  await primeAuth(context, { withConnection: true });
  // The `degraded` dashboard carries a MANUAL_REVIEW_REQUIRED proposal, so the home
  // resolves to CRITICAL and the market region sits at weight 0. Freeze the clock so
  // the proposals' expiry classification (and therefore the surface state) is stable.
  await freezeClock(context);
  await page.route("**/api/v1/**", stateRoute("degraded", {
    delayMs: 0,
    onRequest: info => requests.push(info)
  }));

  await page.goto("/#access_token=test-token", { waitUntil: "domcontentloaded" });
  await expect(page.locator('[data-home-state="CRITICAL"]')).toHaveCount(1);

  const marketRegion = page.locator('.home-decision-shell > [data-home-region="market"]');
  // Collapsed by weight, but its data request must already have happened: folding a
  // region away must not defer or duplicate its fetch.
  await expect(marketRegion).toHaveAttribute("data-weight", "0");
  await expect(marketRegion.locator(".market-candle-chart")).toContainText("부분 데이터");

  const candleRequests = () => requests.filter(request => request.url.endsWith("/candles"));
  await expect.poll(() => candleRequests().length).toBe(1);
  expect(`${candleRequests()[0].url}${candleRequests()[0].query}`).toBe(
    `/api/v1/broker-connections/${CONNECTION_ID}/candles?symbol=NVDA&interval=1m`
  );

  // Expand the collapsed region so the interval controls are actually clickable.
  await marketRegion.locator("summary").click();
  await expect(marketRegion.locator(".home-region-body")).toBeVisible();

  const daily = marketRegion.getByRole("button", { name: "일봉" });
  await daily.click();
  await expect.poll(() => candleRequests().length).toBe(2);
  expect(`${candleRequests()[1].url}${candleRequests()[1].query}`).toBe(
    `/api/v1/broker-connections/${CONNECTION_ID}/candles?symbol=NVDA&interval=1d`
  );

  // (a) Re-selecting the interval already active must not refetch.
  await daily.click();
  const duplicateCandleRequest = observeNextCandleRequest(page);
  await daily.click();
  await expect(daily).toHaveAttribute("aria-pressed", "true");
  expect(await duplicateCandleRequest).toBeNull();
  expect(candleRequests()).toHaveLength(2);
});

test("candles and market context follow every decision region", async ({ page, context }) => {
  await primeAuth(context, { withConnection: true });
  await freezeClock(context);
  await page.route("**/api/v1/**", stateRoute("degraded", { delayMs: 0 }));

  await page.goto("/#access_token=test-token", { waitUntil: "domcontentloaded" });
  await expect(page.locator('[data-home-state="CRITICAL"]')).toHaveCount(1);

  const shell = page.locator(".home-decision-shell");
  // Every region in the new vocabulary is present, including the collapsed ones.
  for (const name of ["status", ...DECISION_REGIONS, "trend", "market"]) {
    await expect(shell.locator(`> [data-home-region="${name}"]`)).toHaveCount(1);
  }

  // (b) The candle chart itself — not just its wrapper — must trail the decisions.
  const chartFollowsDecisions = await shell.evaluate((root, regions) => {
    const chart = root.querySelector(".market-candle-chart");
    if (!chart) {
      return "no-chart";
    }
    const offenders = regions.filter(name => {
      const node = root.querySelector(`:scope > [data-home-region="${name}"]`);
      if (!node) {
        return true;
      }
      return (node.compareDocumentPosition(chart) & Node.DOCUMENT_POSITION_FOLLOWING) === 0;
    });
    return offenders.length ? offenders.join(",") : "ok";
  }, DECISION_REGIONS);
  expect(chartFollowsDecisions, "regions that the candle chart does not follow").toBe("ok");

  // Market context as a whole is last: nothing decision-bearing may sit below it.
  const marketIsLast = await shell.evaluate(root => {
    const weighted = [...root.querySelectorAll(":scope > [data-weight]")];
    return weighted[weighted.length - 1]?.getAttribute("data-home-region");
  });
  expect(marketIsLast).toBe("market");
});

async function observeNextCandleRequest(page) {
  return page.waitForRequest(request => new URL(request.url()).pathname.endsWith("/candles"), { timeout: 500 })
    .then(request => request.url())
    .catch(error => {
      if (error.name === "TimeoutError") {
        return null;
      }
      throw error;
    });
}
