# UI/UX Product Polish Design

**Date:** 2026-08-10  
**Branch:** `chore/ui-ux-product-polish`

## Goal

Polish the existing production dashboard so the core journey—login → home → stock analysis → portfolio → order → order status—is easier to scan, safer to operate, and consistent at 360, 768, 1280, and 1440 px. Secondary production routes (`/events`, `/predictions`, `/settings`) receive the same shared-shell and state-language treatment when they use the affected components.

This is a presentation and interaction-hierarchy change only. It does not add backend capabilities, API calls, order behavior, or new product concepts.

## Evidence reviewed

- `DESIGN.md`: data time and risk precede recommendations; one screen/one purpose; non-color status communication; WCAG 2.2 AA and mobile approval constraints.
- `web-dashboard/app/route-workspace.js`: one client workspace owns session, connection, loading/error state, route navigation, order approval, risk policy, notifications, and all route composition.
- `web-dashboard/app/globals.css`: existing neutral canvas, blue primary, warning/danger tokens, 8/12/24 spacing rhythm, panel/control radii, table and status styles.
- Route view components: dashboard, stock analysis, portfolio history, orders/approval, market overview, events, predictions, settings.
- Existing Playwright state-matrix screenshots at 360/768/1280/1440 for login, home, stock, portfolio, orders, events, predictions, and settings.
- Existing unit, E2E, axe, build, and state-matrix test suites.

## Approved direction: signal-first shared shell

Use the existing visual language and components, but change the order and density of information:

1. Route title and one clear route purpose.
2. Highest-value signal: price, total value/P&L, risk/availability, or order state depending on the route.
3. Data quality and reference time adjacent to the value they qualify.
4. One existing primary action; secondary actions remain visible but visually quiet.
5. Detail tables, history, evidence, and operational controls below the lead block.

The shared shell owns only layout primitives and status presentation. Route views retain domain-specific rendering and existing callbacks.

## Route decisions

### Login and account connection

- Keep the current one-click login and account connection behavior.
- Give the primary action one dominant button treatment and keep the recovery/return path secondary.
- Separate session loading, unauthorized, connection required, and connection error copy; never show a false empty state.
- Keep credential fields and account identifiers visually distinct from portfolio data.

### Home

- Lead with total portfolio value, P/L, freshness/reference time, and the most important risk/availability signal.
- Keep market overview, notifications, and risk policy discoverable but visually subordinate to the portfolio lead block.
- Preserve the existing explicit connection flow and order proposal actions; do not add a new quick-order path.

### Stock analysis

- Lead with symbol, current price/market state, currency, reference time, and the existing analysis action.
- Group analysis, forecast, explanation, events, and broker data into clear sections with fewer competing borders.
- Make `UNKNOWN`, `MANUAL_REVIEW`, `UNAVAILABLE`, `DEGRADED`, and unsupported provider states textual and adjacent to the affected data.
- Preserve all existing analysis/re-run callbacks and current API contract.

### Portfolio

- Lead with total value and P/L, followed by freshness/risk/availability, then buying power, positions, and history.
- Keep currency per value; do not infer or merge missing currencies.
- On narrow screens, collapse secondary columns into readable rows or overflow-safe table layout without hiding symbol, quantity, value, P/L, or status.

### Orders and order status

- Lead with order status and actionability, then BUY/SELL, symbol, quantity, limit price, currency, created/expiry/reference time, and risk/manual review information.
- Preserve approval preview → step-up → approve/reject behavior, single-flight protection, idempotency, and destructive-action confirmation.
- Use a stronger destructive-action hierarchy for reject/cancel while keeping it visually and textually distinct from approve.
- Keep status, loading, refreshing, partial, stale, degraded, and error states distinct; never imply that an unavailable order list is empty.

### Events, analysis, and settings

- Apply the same route header, connection strip, spacing, badge, table, alert, and state treatment.
- Keep operational/readiness and risk controls available without competing with their route's primary read or review task.
- Do not remove or add feature areas; only improve grouping, emphasis, and state explanation.

## State language

Use existing status vocabulary and tokens, with these visual distinctions:

| State | Visual meaning | Required content |
|---|---|---|
| Loading | Initial data is not available | Short progress copy; bounded skeleton/placeholder |
| Refreshing | Prior data remains visible | Refresh indicator without replacing known values |
| Empty | Valid response contains no records | What is empty and the existing next action |
| Partial | Response is usable but incomplete | Missing scope and affected fields |
| Stale | Known values are older than the current freshness target | Reference time and stale reason |
| Degraded | Service returned reduced capability | What remains usable and what is unavailable |
| Error | Request failed | Failure scope and retry action when supported |
| Unauthorized | Session or permission is absent | Login/re-authentication action |
| Unsupported | Provider/field is not supported | Explicit unsupported label; no fake value |

Status must be conveyed by text and structure, not color alone. `UNKNOWN` and `MANUAL_REVIEW` remain explicit values, not replacements with optimistic language.

## Responsive rules

- **360 px:** single-column lead block; full-width primary action; no clipped buttons; tables use stacked rows or safe horizontal overflow with key identity columns preserved.
- **768 px:** two-column detail groups only when labels and long values fit; action groups wrap intentionally; order approval content remains readable without horizontal page overflow.
- **1280/1440 px:** use a centered readable content width; lead signal and action stay in the first viewport; supporting panels may use two columns; avoid empty full-width whitespace.
- Long symbols, account IDs, currency values, timestamps, status codes, and Korean labels must wrap or truncate with accessible full text rather than break controls.

## Accessibility and interaction

- Reuse existing semantic buttons, links, labels, table headers, alert/status regions, and focus styles.
- Keep keyboard order aligned with visual order; preserve Escape behavior for open controls.
- Keep touch targets usable at 360 px and ensure destructive actions are not adjacent without labels/confirmation.
- Respect `prefers-reduced-motion`; no decorative animation is required.

## Implementation boundaries

- Modify only `web-dashboard` presentation/layout/test/snapshot surfaces and this feature's docs.
- Prefer existing components and CSS tokens; add the smallest shared class/primitive only when it removes repeated route styling.
- Do not change backend, analysis-service, API payloads, feature flags, schedulers, environment variables, authentication, or order mutation semantics.
- Do not introduce a new component library, CSS framework, or design-system layer.

## Acceptance criteria

- Core and secondary routes have a clear first-view hierarchy and one visually dominant existing primary action.
- Prices, portfolio values, risk/availability, order status, currency, quantity, and reference time remain visible and correctly qualified.
- All nine requested UI states are distinguishable in copy and structure.
- 360/768/1280/1440 visual review shows no clipping, accidental overflow, unreadable tables, or wrapped controls.
- axe, unit tests, E2E state journeys, build, and route screenshot checks pass.
- Independent review finds no behavior or contract regression.
