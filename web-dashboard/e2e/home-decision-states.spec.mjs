import { expect, test } from "@playwright/test";

import { measureOverflow } from "./fixtures/record.mjs";
import {
  DECISION_STATES,
  DECISION_STATE_SURFACES,
  freezeClock,
  primeAuth,
  stateRoute
} from "./fixtures/states.mjs";

// Adaptive Decision Workspace: the home screen re-ranks its regions from the single
// surface state the server data resolves to. These are behavioural gates on that
// ranking — the pixel baseline lives in state-matrix.spec.mjs.
//
// Two viewports are exercised because the whole point of the weighting is that the
// most urgent thing survives the narrowest screen: an assertion that only holds at
// 1280 proves nothing about the phone case. Colour scheme is irrelevant to layout,
// so the dark projects are not duplicated here.
const TARGET_PROJECTS = ["vp-360", "vp-1280"];

// lib/home-decision-center.js WEIGHTS, mirrored so a weight change has to be made
// deliberately in both places instead of silently relaxing this gate.
const EXPECTED_WEIGHTS = {
  CRITICAL: { actions: 4, risk: 3, summary: 1, positions: 1, trend: 0, market: 0 },
  RISK: { actions: 3, risk: 4, summary: 2, positions: 3, trend: 0, market: 0 },
  ACTIVE: { actions: 3, risk: 2, summary: 2, positions: 2, trend: 0, market: 0 },
  CALM: { actions: 1, risk: 1, summary: 3, positions: 3, trend: 1, market: 1 }
};

test.beforeEach(async ({}, testInfo) => {
  test.skip(
    !TARGET_PROJECTS.includes(testInfo.project.name),
    "home decision states run at 360 and 1280 only"
  );
});

async function openHome(page, context, state) {
  await primeAuth(context, { withConnection: true });
  await freezeClock(context);
  await page.route("**/api/v1/**", stateRoute(state, { delayMs: 0 }));
  await page.goto("/#access_token=test-token", { waitUntil: "domcontentloaded", timeout: 30000 });
  await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
}

// Only the shell's own direct children are regions. ActionQueue also carries a
// data-home-region attribute of its own, so an unscoped query would pick up nested
// nodes and make the ordering check meaningless.
function regionsOf(page) {
  return page.locator('.home-decision-shell > [data-weight]');
}

function region(page, name) {
  return page.locator(`.home-decision-shell > [data-home-region="${name}"]`);
}

// ---------------------------------------------------------------------------
// Per-state surface identity
// ---------------------------------------------------------------------------
for (const state of DECISION_STATES) {
  const surface = DECISION_STATE_SURFACES[state];

  test(`${state} renders the ${surface} surface`, async ({ page, context }) => {
    await openHome(page, context, state);

    if (surface === "BLOCKED") {
      // Broken portfolio data means the decision surface has nothing trustworthy to
      // rank, so it must not render at all.
      await expect(page.locator("[data-home-state]")).toHaveCount(0);
      await expect(page.locator(".home-decision-shell")).toHaveCount(0);
      // ...and the user must not be left on a blank screen. BLOCKED is reached when
      // the portfolio section is unavailable, which is exactly the moment the user
      // needs to be told what broke and how to recover.
      //
      // Deliberately NOT matched against a bare [role=alert]: Next.js injects an
      // always-present empty #__next-route-announcer__ with role="alert", which
      // makes such a check pass on a completely blank page.
      await expect(
        page.locator("main"),
        "BLOCKED home must still render a main landmark"
      ).toHaveCount(1);

      const recovery = page.locator(
        ".onboarding-wrap, .landing-shell, .broker-onboarding, main .error, main [role=alert]"
      );
      const recoveryText = (await recovery.allInnerTexts()).join(" ").trim();
      expect(
        recoveryText,
        "BLOCKED home must explain the blocked data and offer a way forward, not render an empty workspace"
      ).not.toBe("");
      return;
    }

    await expect(page.locator(`[data-home-state="${surface}"]`)).toHaveCount(1);
  });
}

// ---------------------------------------------------------------------------
// Weight-driven ordering and sizing
// ---------------------------------------------------------------------------
for (const state of DECISION_STATES.filter(value => DECISION_STATE_SURFACES[value] !== "BLOCKED")) {
  const surface = DECISION_STATE_SURFACES[state];

  test(`${state} orders regions by descending weight`, async ({ page, context }) => {
    await openHome(page, context, state);

    const rendered = await regionsOf(page).evaluateAll(nodes => nodes.map(node => ({
      name: node.getAttribute("data-home-region"),
      weight: Number(node.getAttribute("data-weight"))
    })));

    // Every region in the vocabulary is accounted for — none may be dropped.
    expect(rendered.map(entry => entry.name).sort()).toEqual(
      ["actions", "market", "positions", "risk", "summary", "trend"]
    );

    const weights = Object.fromEntries(rendered.map(entry => [entry.name, entry.weight]));
    expect(weights, `${surface} weights`).toEqual(EXPECTED_WEIGHTS[surface]);

    // DOM order must be weight-descending; ties fall back to the declared
    // actions -> risk -> summary -> positions -> trend -> market order.
    const tieBreak = ["actions", "risk", "summary", "positions", "trend", "market"];
    const expectedOrder = [...rendered].sort(
      (a, b) => (b.weight - a.weight) || (tieBreak.indexOf(a.name) - tieBreak.indexOf(b.name))
    );
    expect(rendered.map(entry => entry.name)).toEqual(expectedOrder.map(entry => entry.name));

    const observedWeights = rendered.map(entry => entry.weight);
    for (let index = 1; index < observedWeights.length; index += 1) {
      expect(
        observedWeights[index],
        `region ${rendered[index].name} outranks ${rendered[index - 1].name} in the DOM`
      ).toBeLessThanOrEqual(observedWeights[index - 1]);
    }
  });

  test(`${state} never scrolls horizontally`, async ({ page, context }) => {
    await openHome(page, context, state);
    const overflow = await measureOverflow(page);
    expect(
      overflow.scrollWidth,
      `horizontal overflow: scrollWidth=${overflow.scrollWidth} innerWidth=${overflow.innerWidth}`
    ).toBeLessThanOrEqual(overflow.innerWidth + 1);
  });
}

// ---------------------------------------------------------------------------
// CRITICAL: the urgent queue takes the hero slot, above the fold
// ---------------------------------------------------------------------------
test("CRITICAL promotes the action queue to the only hero, above the fold", async ({ page, context }, testInfo) => {
  await openHome(page, context, "decision-critical");

  const heroes = page.locator(".home-decision-shell > .panel--hero");
  await expect(heroes).toHaveCount(1);
  await expect(heroes.first()).toHaveAttribute("data-home-region", "actions");
  await expect(region(page, "actions")).toHaveAttribute("data-weight", "4");

  // "Above the fold" means literally without scrolling, so the page must still be
  // at the top and the hero must begin inside the first viewport with a usable
  // amount of itself visible.
  const scrollY = await page.evaluate(() => window.scrollY);
  expect(scrollY, "page must not need scrolling to reach the urgent queue").toBe(0);

  const viewportHeight = testInfo.project.use.viewport.height;
  const box = await heroes.first().boundingBox();
  expect(box, "hero region must be laid out").not.toBeNull();
  expect(box.y, `hero top (${box.y}) must sit inside the first viewport`).toBeLessThan(viewportHeight);
  const visibleHeight = Math.min(box.y + box.height, viewportHeight) - Math.max(box.y, 0);
  expect(
    visibleHeight,
    `only ${Math.round(visibleHeight)}px of the urgent queue is above the fold`
  ).toBeGreaterThanOrEqual(60);

  // The urgent item itself has to be the thing on screen, not just the container.
  await expect(region(page, "actions").locator('[data-action-priority="URGENT"]')).toHaveCount(1);
});

// ---------------------------------------------------------------------------
// RISK: the breached limit takes the hero slot instead
// ---------------------------------------------------------------------------
test("RISK promotes the risk panel to the hero slot", async ({ page, context }) => {
  await openHome(page, context, "decision-risk");

  const heroes = page.locator(".home-decision-shell > .panel--hero");
  await expect(heroes).toHaveCount(1);
  await expect(heroes.first()).toHaveAttribute("data-home-region", "risk");
  await expect(region(page, "risk")).toHaveAttribute("data-weight", "4");
  // The hero must actually be showing the breach, not an empty risk shell.
  await expect(region(page, "risk").locator('[data-risk-breached="true"]')).not.toHaveCount(0);
});

// ---------------------------------------------------------------------------
// CALM: nothing to decide must cost almost no space
// ---------------------------------------------------------------------------
test("CALM compacts the empty queue and promotes positions", async ({ page, context }, testInfo) => {
  await openHome(page, context, "decision-calm");

  const actions = region(page, "actions");
  await expect(actions).toHaveAttribute("data-weight", "1");
  await expect(actions).toHaveClass(/panel--compact/);
  await expect(actions).toContainText("확인할 결정이 없습니다");
  await expect(region(page, "positions")).toHaveAttribute("data-weight", "3");

  // No hero at all: nothing here deserves the full-width treatment.
  await expect(page.locator(".home-decision-shell > .panel--hero")).toHaveCount(0);

  // A big empty card is the specific failure this state exists to prevent.
  const viewportHeight = testInfo.project.use.viewport.height;
  const box = await actions.boundingBox();
  expect(box, "actions region must be laid out").not.toBeNull();
  const share = box.height / viewportHeight;
  expect(
    share,
    `empty action queue takes ${Math.round(share * 100)}% of the viewport `
      + `(${Math.round(box.height)}px of ${viewportHeight}px); must stay under 25%`
  ).toBeLessThan(0.25);
});

// ---------------------------------------------------------------------------
// Collapsed regions: weight 0 must be foldable detail, not deleted content
// ---------------------------------------------------------------------------
test("weight-0 regions collapse into details rather than disappearing", async ({ page, context }) => {
  await openHome(page, context, "decision-critical");

  for (const name of ["trend", "market"]) {
    const node = region(page, name);
    await expect(node).toHaveAttribute("data-weight", "0");
    await expect(node).toHaveClass(/panel--collapsed/);
    // Content is reachable on demand: the details opens and reveals a body.
    expect(await node.evaluate(element => element.tagName)).toBe("DETAILS");
    await node.locator("summary").click();
    await expect(node.locator(".home-region-body")).toBeVisible();
  }
});
