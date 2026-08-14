# Home Operations Dashboard Replacement Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents are available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the PR #13 chart-first home composition with the approved operations-first home hierarchy: freshness/status → account metrics → portfolio trend and holdings → review queue → events → lower market context.

**Architecture:** Keep the current routes, APIs, order-approval callbacks, quality-state vocabulary, and existing market widgets. Change only the home composition and its data loading: load the existing portfolio-history endpoint for home, pass existing risk/proposal data into a new summary composition, reuse current dashboard/history/market components, and move `MarketCandleChart` below operational decision surfaces. The previous account rail and chart-first DOM hierarchy are removed, not wrapped by another layout.

**Tech Stack:** Next.js 16, React 19, plain JavaScript via `React.createElement`, global CSS, Node built-in tests, Playwright, axe.

---

## Non-negotiable replacement boundary

Do not append summary cards, queues, or a new grid around the old PR #13 composition. Remove or replace the home-only `home-reference-shell`, `home-dashboard-columns`, `home-dashboard-main`, and `home-dashboard-account` composition. The old account rail must not remain the primary home region. `MarketCandleChart` may remain for stock analysis and as a lower home market-context widget, but it must not be the first home content or the first decision surface.

## Chunk 1: Lock the replacement contract and data flow

### Task 1: Rewrite tests to express the approved hierarchy first

**Files:**

- Modify `web-dashboard/test/dashboard-view.test.mjs`.
- Modify `web-dashboard/test/route-surface.test.mjs`.
- Modify `web-dashboard/e2e/home-candle-account-rail.spec.mjs`.

- [ ] Replace old assertions for `.home-dashboard-columns`, `.home-dashboard-main`, `.home-dashboard-account`, and chart-first ordering with assertions for the new home operations shell and named regions.
- [ ] Assert the new DOM order: freshness/status and core metrics precede portfolio/trend/holdings; review queue and events precede market context; `.market-candle-chart` is after those operational sections.
- [ ] Assert the old chart-first home selectors/composition are absent from the home source.
- [ ] Assert the summary’s review count includes only `PROPOSED` and `MANUAL_REVIEW_REQUIRED` proposal statuses, while expired proposals remain visible, `MANUAL_REVIEW_REQUIRED` remains display-only, and approval stays guarded by the existing expiry behavior.
- [ ] Update route-surface assertions to require the home operations composition and home portfolio-history data flow.
- [ ] Make the home events source explicit: reuse `dashboard.pendingEvents` in the home composition; do not add a full `listEvents` request to home unless the approved scope changes.
- [ ] Keep the candle API “one initial request plus interval-switch request” coverage, but rename the scenario and replace all old account-rail selectors with the new home region selectors.
- [ ] Run the focused tests and confirm they fail for the missing replacement implementation rather than because of a test harness error:

      cd /Users/jjm/Desktop/trade/web-dashboard
      npm test -- test/dashboard-view.test.mjs test/route-surface.test.mjs test/home-candle-wiring.test.mjs

      npx playwright test e2e/home-candle-account-rail.spec.mjs --project=vp-1280

  Expected result before implementation: the new hierarchy assertions fail; existing unrelated route and candle helper tests remain diagnosable.

### Task 2: Load home portfolio history and pass home-only data explicitly

**Files:**

- Modify `web-dashboard/app/route-workspace.js`.
- Modify `web-dashboard/test/route-surface.test.mjs` if the loader contract needs a narrower assertion.

- [ ] In the home branch of `loadWorkspace`, call the existing `loadPortfolioHistory(id, HISTORY_QUERY)` and store its result in the existing `portfolioHistory` state; do not add a new endpoint or backend read model.
- [ ] Set `historyBusy` while the home history request is in flight and clear it in `finally`; on failure, store the existing history-view-compatible unavailable envelope (`unavailable: true` plus `unavailableReason`) instead of changing the global workspace to error. The home core must remain usable when history is missing, partial, stale, degraded, or unavailable.
- [ ] Pass `portfolioHistory`, `historyBusy`, and the already-loaded `riskPolicy` into `DashboardView` for home; retain the existing `realtimePrices`, market overview, candle, order, and quality-state wiring.
- [ ] Replace the old home shell class/prop contract with an explicit operations composition name, so the code cannot silently fall back to the PR #13 chart-first layout.
- [ ] Keep non-home portfolio route history behavior unchanged.
- [ ] Verify the focused route tests again:

      cd /Users/jjm/Desktop/trade/web-dashboard
      npm test -- test/route-surface.test.mjs test/home-candle-wiring.test.mjs

### Task 3: Add the smallest pure home summary helpers needed by the spec

**Files:**

- Modify `web-dashboard/app/dashboard-view.js`.
- Extend `web-dashboard/test/dashboard-view.test.mjs`.

- [ ] Reuse current dashboard fields rather than introducing a new view model: evaluation from `account.marketValueAmounts`, total P/L from `account.profitLossAmounts`, risk status/version/limits from `riskPolicy`, and review count from proposal statuses.
- [ ] Keep money formatting and quality-state formatting consistent with existing dashboard components.
- [ ] Add one focused test for review-count filtering and one for the empty/unknown risk-policy presentation; do not count quality signals as review items.
- [ ] Add an actionability assertion that `MANUAL_REVIEW_REQUIRED` appears in the review queue but receives no approve/cancel controls, preserving the existing `PROPOSED`-only action gate.

## Chunk 2: Implement the operations-first home composition

### Task 4: Replace `DashboardView` home rendering, preserving non-home rendering

**Files:**

- Modify `web-dashboard/app/dashboard-view.js`.

- [ ] Add a home-only operations composition with accessible regions named `내 자산 홈`, `핵심 계좌 지표`, `검토 대기 주문`, and `시장 정보`.
- [ ] Render the new home order explicitly:

      freshness/status
      core metric summary
      portfolio trend + holdings
      review-needed proposals
      events / analysis
      exchange/calendar/rankings/realtime/candle market context

- [ ] Use `dashboard.pendingEvents` as the home events source and preserve its existing unavailable/stale/unknown state rather than silently fetching a second events surface.
- [ ] Reuse `Portfolio`, `Proposals`, `Events`, `Analysis`, `MarketOverviewView`, `RealtimePriceTicker`, and `MarketCandleChart` instead of cloning their business logic.
- [ ] Render portfolio history in the home primary area using a compact trend surface with a text/table equivalent; do not expose the full portfolio-route filter form on home.
- [ ] Keep order approval controls, expiry guard, busy order state, and existing proposal actions unchanged.
- [ ] Keep the current non-home `DashboardView` composition and route behavior intact.
- [ ] Ensure loading, refreshing, empty, stale, partial, degraded, error, and unauthorized states remain represented by the existing quality/status components and do not collapse into fake success content.
- [ ] Ensure the new summary does not imply a risk percentage when no policy data exists; show an explicit unknown/unavailable state.

### Task 5: Reuse portfolio-history rendering for the compact home trend

**Files:**

- Modify `web-dashboard/app/portfolio-history-view.js`.
- Modify `web-dashboard/test/portfolio-history-view.test.mjs` if present; otherwise add only the smallest relevant assertion to `dashboard-view.test.mjs`.

- [ ] Extract or export the existing trend renderer for reuse, or add the smallest `compact` rendering path to the existing component; preserve the full portfolio-route history UI.
- [ ] Include an accessible trend data equivalent through the existing points table/text pattern.
- [ ] Keep missing, stale, partial, and error history states explicit.
- [ ] Avoid adding a charting dependency or a second history-fetch abstraction.

### Task 6: Replace old home CSS with the new hierarchy’s layout rules

**Files:**

- Modify `web-dashboard/app/globals.css`.

- [ ] Delete or stop using the old chart-first selectors: `.home-reference-shell`, `.home-dashboard-columns`, `.home-dashboard-main`, `.home-dashboard-account`, and their home-only media-query rules.
- [ ] Add only the classes required by the new DOM, using a low-variance operations layout: full-width freshness/summary, primary portfolio area, review/event side region where space permits, and a clearly lower market-context region.
- [ ] Keep the existing light neutral canvas, Toss blue action color, restrained borders, Korean typography, and trust/freshness emphasis from `DESIGN.md`.
- [ ] Preserve responsive behavior: one-column stacking on narrow screens with operational regions still ordered before market widgets.
- [ ] Preserve dark-mode tokens, visible keyboard focus, WCAG contrast, and `prefers-reduced-motion` behavior.
- [ ] Do not touch unrelated user-modified visual snapshots while editing CSS.

## Chunk 3: Update fixtures/baselines and verify the replacement

### Task 7: Verify fixture coverage for the additional home history request

**Files:**

- Modify `web-dashboard/e2e/fixtures/states.mjs` only if the existing `/portfolio-history` fixture does not cover the home request/query.
- Do not change unrelated fixture states.

- [ ] Confirm full, loading, refreshing, empty, stale, partial, degraded, error, and unauthorized home states still resolve with the new history request.
- [ ] Confirm the existing risk-policy fixture and proposal fixture provide the fields required by the summary.
- [ ] If a fixture change is necessary, keep it path/query-specific and add no new fake API contract.

### Task 8: Run focused static and unit verification

**Files:** none beyond the implementation above.

- [ ] Run:

      cd /Users/jjm/Desktop/trade/web-dashboard
      npm test
      npm run lint:css
      npm run build

- [ ] Run the focused browser checks:

      npx playwright test e2e/home-candle-account-rail.spec.mjs --project=vp-1280
      npx playwright test e2e/a11y.spec.mjs --grep "home ::"

- [ ] Inspect failures by root cause; do not relax assertions or accessibility checks to make the suite pass.

### Task 9: Refresh only affected visual baselines and smoke-test the hierarchy

**Files:**

- Modify only the home-related files under `web-dashboard/e2e/state-matrix.spec.mjs-snapshots/` if the approved visual change requires new baselines.

- [ ] Record the pre-existing snapshot modifications before any baseline work:

      git status --short -- web-dashboard/e2e/state-matrix.spec.mjs-snapshots

  Treat the existing dirty home snapshots as user-owned visual baselines. Do not overwrite them blindly: inspect the rendered home state first, then update only the affected home cases after confirming the new approved hierarchy; never touch the 448 unrelated non-home snapshots.
- [ ] Use the existing Playwright route-interception fixture for the authenticated local hierarchy smoke; use `agent-browser` only for the deployed unauthenticated/login-gate baseline, because a plain browser visit cannot provide the E2E mock account data. Confirm the operations shell, summary, trend/holdings, review queue, events, and lower market context are visible in that order.
- [ ] Confirm the first meaningful home region is not `.market-candle-chart` and no old PR #13 home selector remains in the app implementation. The regression tests may retain the legacy strings solely inside negative assertions.
- [ ] Run a source guard:

      rg -n "home-reference-shell|home-dashboard-columns|home-dashboard-main|home-dashboard-account" web-dashboard/app

  Expected result: no matches in app code; test-only negative assertions are intentionally out of scope for this guard.
- [ ] Run the complete frontend verification:

      cd /Users/jjm/Desktop/trade/web-dashboard
      npm test
      npm run lint:css
      npm run build
      npm run e2e

- [ ] Review final diff/status, preserving the pre-existing modified CSS and visual snapshots, and report any test/environment gap explicitly.

## Commit checkpoints

If commits are made during execution, use the repository format and keep each commit reviewable:

- `테스트 :: 운영 홈 hierarchy 교체 계약 고정`
- `구현 :: 운영 홈 hierarchy 적용`
- `검증 :: 홈 시각 기준선 및 검증 갱신`

Do not commit unrelated pre-existing user changes.

## Completion criteria

- The home route renders the approved operations-first hierarchy.
- The old PR #13 chart-first account rail is gone from the home composition and its tests.
- Existing APIs, routes, order safety, and non-home surfaces remain intact.
- Home history is loaded through the existing endpoint and can degrade without hiding core account data.
- Evidence includes passing `npm test`, CSS lint, build, the focused Playwright home/a11y checks, the complete `npm run e2e`, and the no-match source guard; any environment limitation is explicitly documented.
