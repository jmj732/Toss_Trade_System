# UI/UX Product Polish Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve information hierarchy, state clarity, responsive density, and accessibility across the existing dashboard routes without changing backend contracts or adding product capabilities.

**Architecture:** Keep `RouteWorkspace` as the single owner of session/connection/route state and keep each existing view component responsible for its domain data. Add only shared presentation classes and small presentational helpers where they remove duplicated hierarchy/state markup; preserve all existing callbacks, API calls, and order safety gates.

**Tech Stack:** Next.js 16 / React 19 with plain JavaScript and `createElement`, hand-written `globals.css`, Node test runner, Playwright state matrix, axe-core, Next build.

---

## Chunk 1: Baseline and shared shell

### Task 1: Lock the current route and state contract

**Files:**
- Modify: `web-dashboard/test/route-surface.test.mjs`
- Modify: `web-dashboard/test/dashboard-view.test.mjs`
- Modify: `web-dashboard/test/orders-view.test.mjs`
- Test: `web-dashboard/test/route-surface.test.mjs`, `dashboard-view.test.mjs`, `orders-view.test.mjs`

- [ ] **Step 1: Add focused assertions for the approved hierarchy.** Assert the shared workspace exposes the route title/nav/connection region, dashboard lead data keeps freshness/quality text, and order rows keep side, quantity, price, currency, status, and reference timing.
- [ ] **Step 2: Run the focused tests before implementation.**

Run: `cd web-dashboard && node --import ./test/jsx-loader.mjs --test test/route-surface.test.mjs test/dashboard-view.test.mjs test/orders-view.test.mjs`

Expected: the new hierarchy assertions fail while the existing contract assertions pass.

### Task 2: Implement the signal-first shared shell

**Files:**
- Modify: `web-dashboard/app/route-workspace.js`
- Modify: `web-dashboard/app/globals.css`
- Test: `web-dashboard/test/route-surface.test.mjs`

- [ ] **Step 1: Add route-purpose and lead-region class hooks without changing callbacks.** Keep the current route names and links, but make title, connection strip, route nav, status region, and route content explicit layout regions.
- [ ] **Step 2: Reorder the existing shared shell.** Keep login/logout, connection ID, and route navigation behavior intact; place the route purpose and first useful signal before secondary controls at desktop and mobile widths.
- [ ] **Step 3: Add the minimum existing-token CSS for readable content width, lead blocks, action hierarchy, responsive wrapping, focus states, tables, and state badges.** Reuse current color variables, radii, spacing, and button variants; do not add a component library or new token family.
- [ ] **Step 4: Run the focused tests and CSS lint.**

Run: `cd web-dashboard && node --import ./test/jsx-loader.mjs --test test/route-surface.test.mjs && npm run lint:css`

Expected: PASS with no API or order behavior changes.

### Task 3: Review Chunk 1

- [ ] **Step 1: Run `git diff --check` and inspect only shared-shell files.**
- [ ] **Step 2: Dispatch a plan/code slice reviewer for `route-workspace.js`, `globals.css`, and the focused tests.**
- [ ] **Step 3: Address only actionable hierarchy, accessibility, or behavior regressions before continuing.**

## Chunk 2: Core journey surfaces

### Task 4: Polish home, portfolio, market, and history surfaces

**Files:**
- Modify: `web-dashboard/app/dashboard-view.js`
- Modify: `web-dashboard/app/market-overview-view.js`
- Modify: `web-dashboard/app/portfolio-history-view.js`
- Modify: `web-dashboard/app/route-workspace.js`
- Modify: `web-dashboard/app/globals.css`
- Test: `web-dashboard/test/dashboard-view.test.mjs`
- Test: `web-dashboard/test/market-overview-view.test.mjs` (create)
- Test: `web-dashboard/test/portfolio-history-view.test.mjs`

- [ ] **Step 1: Add/extend render assertions for lead value, P/L, freshness/reference time, risk/quality labels, and valid empty/partial/stale/unavailable copy.**
- [ ] **Step 2: Reorder the home composition in `route-workspace.js` and `DashboardView` so the main portfolio signal and one existing action lead; keep market overview, notifications, risk policy, proposal actions, and history discoverable below.**
- [ ] **Step 3: Reduce repeated card borders and visual noise while retaining tables, source/time qualifiers, currency labels, and data-quality badges.**
- [ ] **Step 4: Make the portfolio history filter and table readable at 360/768 px without changing query behavior.**
- [ ] **Step 5: Add focused market widget assertions for loading, unavailable/unsupported, market status, and long ranking rows, then run the focused tests.**

Run: `cd web-dashboard && node --import ./test/jsx-loader.mjs --test test/dashboard-view.test.mjs test/market-overview-view.test.mjs test/portfolio-history-view.test.mjs`

Expected: PASS; missing data remains `UNKNOWN`/explicit unavailable rather than inferred.

### Task 5: Polish stock analysis and broker data panels

**Files:**
- Modify: `web-dashboard/app/stock-analysis-product-surface.js`
- Modify: `web-dashboard/app/globals.css`
- Test: `web-dashboard/test/stock-analysis-product-surface.test.mjs`

- [ ] **Step 1: Add render assertions for symbol/price/currency/reference-time lead content and distinct `PROGRESS`, `DEGRADED`, `FAILED`, `UNAVAILABLE`, `UNKNOWN`, and unsupported copy.**
- [ ] **Step 2: Group the existing analysis, forecast, explanation, events, provenance, history, orderbook, candle, investor, warning, and commission panels under a readable lead-to-detail sequence.**
- [ ] **Step 3: Make long provider fields, tables, and status badges wrap safely at 360/768 px while preserving all existing retry/rerun/timeframe callbacks.**
- [ ] **Step 4: Run the focused stock tests.**

Run: `cd web-dashboard && node --import ./test/jsx-loader.mjs --test test/stock-analysis-product-surface.test.mjs`

Expected: PASS with no changes to stock API calls or status interpretation.

### Task 6: Polish orders and approval/status confirmation

**Files:**
- Modify: `web-dashboard/app/orders-view.js`
- Modify: `web-dashboard/app/order-approval-panel.jsx`
- Modify: `web-dashboard/app/dashboard-view.js`
- Modify: `web-dashboard/app/globals.css`
- Test: `web-dashboard/test/orders-view.test.mjs`
- Test: `web-dashboard/test/order-approval-panel.test.mjs`
- Test: `web-dashboard/test/order-approval-wiring.test.mjs`

- [ ] **Step 1: Add/extend assertions that BUY/SELL, symbol, quantity, limit price, currency, created/expiry/reference time, risk/manual-review, and status remain visible together.**
- [ ] **Step 2: Reorder the existing order row and approval panel around status/actionability first, then order facts, then timing/risk detail.**
- [ ] **Step 3: Strengthen existing destructive reject/cancel styling and separation without changing handlers, step-up, idempotency, expiry, or status gating.**
- [ ] **Step 4: Make approval controls full-width and thumb-safe at 360 px and wrapped but compact at 768 px; keep the order table readable at desktop widths.**
- [ ] **Step 5: Run focused order tests.**

Run: `cd web-dashboard && node --import ./test/jsx-loader.mjs --test test/orders-view.test.mjs test/order-approval-panel.test.mjs test/order-approval-wiring.test.mjs`

Expected: PASS; approval remains preview → step-up → approve/reject and no mutation is added.

## Chunk 3: Secondary routes and explicit state matrix

### Task 7a: Polish the events route

**Files:**
- Modify: `web-dashboard/app/event-workflow.js`
- Modify: `web-dashboard/app/globals.css`
- Test: `web-dashboard/test/event-workflow.test.mjs`

- [ ] **Step 1: Add assertions for event review status, affected symbols/time, and the existing create/review/reanalyze actions.**
- [ ] **Step 2: Put the event status and selected event context before the create form and comparison details; preserve existing event callbacks and keyboard behavior.**
- [ ] **Step 3: Run the event tests.**

Run: `cd web-dashboard && node --import ./test/jsx-loader.mjs --test test/event-workflow.test.mjs`

Expected: PASS with the existing event workflow and API calls unchanged.

### Task 7b: Polish the predictions route

**Files:**
- Modify: `web-dashboard/app/analysis-outcome-view.js`
- Modify: `web-dashboard/app/paper-performance-view.js`
- Modify: `web-dashboard/app/prediction-operations-view.js`
- Modify: `web-dashboard/app/globals.css`
- Test: `web-dashboard/test/analysis-outcome-view.test.mjs`
- Test: `web-dashboard/test/paper-performance-view.test.mjs`
- Test: `web-dashboard/test/prediction-operations-view.test.mjs`

- [ ] **Step 1: Add assertions for prediction outcome/run state, freshness, and the existing query/create actions; keep API-key controls secondary.**
- [ ] **Step 2: Put result state and reference time before model registry, paper performance, and operations detail; preserve all existing callbacks and single-exposure API-key behavior.**
- [ ] **Step 3: Run the prediction tests.**

Run: `cd web-dashboard && node --import ./test/jsx-loader.mjs --test test/analysis-outcome-view.test.mjs test/paper-performance-view.test.mjs test/prediction-operations-view.test.mjs`

Expected: PASS with existing callback and API wiring unchanged.

### Task 7c: Polish the settings route and shared panels

**Files:**
- Modify: `web-dashboard/app/operations-readiness-view.js`
- Modify: `web-dashboard/app/broker-onboarding.js`
- Modify: `web-dashboard/app/risk-policy-view.js`
- Modify: `web-dashboard/app/notification-center.js` only for shared action/status styling
- Modify: `web-dashboard/app/globals.css`
- Test: `web-dashboard/test/operations-readiness-view.test.mjs`
- Test: `web-dashboard/test/broker-onboarding.test.mjs`
- Test: `web-dashboard/test/risk-policy-view.test.mjs`
- Test: `web-dashboard/test/notification-center.test.mjs`

- [ ] **Step 1: Add assertions for readiness/risk status, reference time, blocked reason, and the existing refresh/probe/save actions.**
- [ ] **Step 2: Put readiness and risk status before credential/operational detail; keep destructive credential actions distinct and preserve notification/risk keyboard/Escape behavior.**
- [ ] **Step 3: Run the settings/shared-panel tests.**

Run: `cd web-dashboard && node --import ./test/jsx-loader.mjs --test test/operations-readiness-view.test.mjs test/broker-onboarding.test.mjs test/risk-policy-view.test.mjs test/notification-center.test.mjs`

Expected: PASS with existing settings mutations, credential lifecycle, and panel behavior unchanged.

### Task 8: Extend the existing Playwright state matrix

**Files:**
- Modify: `web-dashboard/e2e/fixtures/states.mjs`
- Modify: `web-dashboard/e2e/state-matrix.spec.mjs`
- Modify: `web-dashboard/e2e/a11y.spec.mjs` only if the state assertions require a shared helper
- Test: `web-dashboard/e2e/state-matrix.spec.mjs`

- [ ] **Step 1: Add `refreshing`, `degraded`, and `unsupported` to the existing state factory without changing backend fixture contracts.** Preserve the six existing states and route-specific `UNAVAILABLE`/`PROVIDER_UNSUPPORTED` behavior.
- [ ] **Step 2: Shape state-specific response data so refreshing keeps prior values visible, degraded exposes usable partial data, and unsupported names the unsupported provider/field only for stock/market `surface` endpoints; unrelated routes retain valid normal data.**
- [ ] **Step 3: Add route-aware assertions for readable state text, reference time, no false empty, no horizontal overflow, and existing relogin/error controls.**
- [ ] **Step 4: Run the state matrix against one light/dark viewport first, then update only intentional baselines for all four viewports and both color schemes.**

Run: `cd web-dashboard && npx playwright test e2e/state-matrix.spec.mjs --project=vp-360 --project=vp-360-dark`

Then: `cd web-dashboard && npx playwright test e2e/state-matrix.spec.mjs --project=vp-360 --project=vp-360-dark --project=vp-768 --project=vp-768-dark --project=vp-1280 --project=vp-1280-dark --project=vp-1440 --project=vp-1440-dark`

Expected: PASS after implementation; no route has clipped controls or misleading empty copy.

## Chunk 4: Visual review, verification, and handoff

### Task 9: Perform the four-viewport visual review

**Files:**
- Review: `web-dashboard/e2e/state-matrix.spec.mjs-snapshots/`
- Review: `web-dashboard/e2e/__reports__/records/`
- Modify only if needed: affected `web-dashboard/app/*.js`, `web-dashboard/app/*.jsx`, `web-dashboard/app/globals.css`

- [ ] **Step 1: Use the Playwright `webServer` in `web-dashboard/playwright.config.mjs` and the controlled response data in `web-dashboard/e2e/fixtures/states.mjs` to inspect login, home, stock, portfolio, orders, events, predictions, and settings at 360/768/1280/1440.**
- [ ] **Step 2: Review all nine states in both light/dark schemes and check primary action prominence, lead signal order, status clarity, long labels/timestamps, table behavior, touch targets, keyboard focus, and dark-mode contrast.**
- [ ] **Step 3: Fix only P0/P1 visual or accessibility issues found in the review and rerun the affected focused tests.**

### Task 10: Run full dashboard verification

- [ ] **Step 1: Run CSS lint and all unit tests.**

Run: `cd web-dashboard && npm run lint:css && npm test`

- [ ] **Step 2: Run the production build.**

Run: `cd web-dashboard && npm run build`

- [ ] **Step 3: Run full E2E and axe.**

Run: `cd web-dashboard && npm run e2e`

Expected: all applicable state/journey tests pass, no axe violations, no forbidden tokens, and no horizontal overflow. Remove generated aggregate output from the commit if it is ignored/generated.

- [ ] **Step 4: Generate and inspect the existing aggregate report.**

Run: `cd web-dashboard && npm run e2e:report`

- [ ] **Step 5: Run repository-level backend/analysis contract checks to prove no non-frontend behavior changed.**

Run: `cd analysis-service && pytest -q`; `cd trading-backend && env JAVA_HOME=/Users/jjm/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.8/Contents/Home DOCKER_HOST=unix:///Users/jjm/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./mvnw clean verify`; `./scripts/test-local-stack.sh`; `./scripts/smoke-local-stack.sh`

### Task 11: Independent review and integration

- [ ] **Step 1: Run `git diff --check`, inspect changed files, and assert only approved frontend/docs/snapshot paths changed.**

Run: `git diff --name-only origin/main...HEAD`; expected paths are under `web-dashboard/` plus `docs/superpowers/specs/` and `docs/superpowers/plans/`.
- [ ] **Step 2: Dispatch independent code-reviewer and architect reviews against `origin/main` and the final branch.**
- [ ] **Step 3: Fix critical/important findings only within the approved UX scope and rerun affected verification.**
- [ ] **Step 4: Commit with Korean repository format and required co-author trailer.**
- [ ] **Step 5: Push `chore/ui-ux-product-polish`, open a PR, wait for the CI gate, and squash-merge into protected `main`.**

## Stop condition

Stop only when the approved signal-first hierarchy is implemented across all production routes, the nine UI states are explicit and tested, all four viewports pass visual/accessibility review, full local verification and independent review pass, and the branch is squash-merged and pushed without backend/API or order-contract changes.
