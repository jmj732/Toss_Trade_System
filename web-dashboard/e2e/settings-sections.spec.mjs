import { expect, test } from "@playwright/test";

import { freezeClock, primeAuth, stateRoute } from "./fixtures/states.mjs";

// Settings is now five <details> sections with only the account one open, and each
// closed section owns its data: nothing is fetched for it until it is expanded, and
// expanding it again must not refetch. That is a request-count claim, so it is
// proved by logging requests rather than by looking at the rendered output.
//
// This spec also owns the /predictions contract. That route is a server component
// doing redirect("/settings"), which is why it is no longer in the pixel matrix —
// its whole behaviour is "you end up on settings", which a URL assertion proves
// once instead of 64 duplicate screenshots.

const SECTIONS = ["account", "risk", "data", "analysis", "strategy"];

// Which endpoint each lazily-loaded section owns. account and risk deliberately
// have none — their data is already on the page from the workspace open.
const LAZY_ENDPOINTS = {
  data: [/\/operations\/readiness$/],
  strategy: [/\/paper-performance/],
  analysis: [
    /\/prediction-model-versions/,
    /\/analysis-predictions/,
    /\/prediction-ingestion-api-keys/,
    /\/prediction-operations/
  ]
};

const ALL_LAZY = Object.values(LAZY_ENDPOINTS).flat();

test.beforeEach(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== "vp-1280", "settings section journey runs at 1280 only");
});

async function openSettings(page, context, path = "/settings") {
  const requests = [];
  await primeAuth(context, { withConnection: true });
  await freezeClock(context);
  await page.route("**/api/v1/**", stateRoute("degraded", {
    delayMs: 0,
    onRequest: info => requests.push(info)
  }));
  await page.goto(`${path}#access_token=test-token`, {
    waitUntil: "domcontentloaded",
    timeout: 30000
  });
  await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
  return requests;
}

function countMatching(requests, patterns) {
  return requests.filter(request => patterns.some(pattern => pattern.test(request.url))).length;
}

test("/predictions redirects onto the settings workspace", async ({ page, context }) => {
  await openSettings(page, context, "/predictions");

  // The redirect is server-side, so by the time the document is loaded the URL is
  // already /settings — /predictions must not render a workspace of its own.
  expect(new URL(page.url()).pathname).toBe("/settings");
  await expect(page.locator('[data-route-region="settings"]')).toHaveCount(1);
  // The prediction features are reachable from here, and only from here.
  await expect(page.locator('[data-settings-section="analysis"]')).toHaveCount(1);
});

test("settings shows five sections with only the account one expanded", async ({ page, context }) => {
  await openSettings(page, context);

  await expect(page.locator("[data-settings-section]")).toHaveCount(SECTIONS.length);
  const rendered = await page.locator("[data-settings-section]").evaluateAll(nodes => nodes.map(node => ({
    id: node.getAttribute("data-settings-section"),
    tag: node.tagName,
    open: node.open
  })));

  expect(rendered.map(entry => entry.id)).toEqual(SECTIONS);
  // Every section must be a real disclosure element, not a div pretending to be one.
  expect(rendered.map(entry => entry.tag)).toEqual(SECTIONS.map(() => "DETAILS"));
  expect(
    rendered.filter(entry => entry.open).map(entry => entry.id),
    "only the account section may start expanded"
  ).toEqual(["account"]);
});

test("entering settings fetches nothing for the collapsed sections", async ({ page, context }) => {
  const requests = await openSettings(page, context);

  const leaked = requests
    .filter(request => ALL_LAZY.some(pattern => pattern.test(request.url)))
    .map(request => request.url);
  expect(
    leaked,
    "collapsed sections must not be fetched on entry — this is the whole point of the lazy split"
  ).toEqual([]);
});

for (const [sectionId, patterns] of Object.entries(LAZY_ENDPOINTS)) {
  test(`expanding "${sectionId}" fetches once and re-expanding never refetches`, async ({ page, context }) => {
    const requests = await openSettings(page, context);
    const section = page.locator(`[data-settings-section="${sectionId}"]`);
    const summary = section.locator("> summary");

    expect(countMatching(requests, patterns), "before first expand").toBe(0);

    // First expand: this is when — and the only time — the data may be requested.
    await summary.click();
    await expect(section).toHaveJSProperty("open", true);
    await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
    const afterFirstExpand = countMatching(requests, patterns);
    expect(
      afterFirstExpand,
      `expanding ${sectionId} must issue its ${patterns.length} request(s)`
    ).toBe(patterns.length);

    // Collapse and expand again: the section is already loaded, so nothing new.
    await summary.click();
    await expect(section).toHaveJSProperty("open", false);
    await summary.click();
    await expect(section).toHaveJSProperty("open", true);
    await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
    await page.waitForTimeout(500);

    expect(
      countMatching(requests, patterns),
      `re-expanding ${sectionId} refetched; the loaded-section guard is not holding`
    ).toBe(afterFirstExpand);
  });
}
