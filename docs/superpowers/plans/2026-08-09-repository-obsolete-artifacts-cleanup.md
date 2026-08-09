# Repository Obsolete Artifacts Cleanup Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove repository artifacts and frontend references that are proven obsolete while preserving current live portfolio, Toss, risk, approval, audit, idempotency, availability, and degradation contracts.

**Architecture:** Keep the current modular-monolith backend, analysis contract fixtures, and dashboard API surface unchanged except for deletion of unreferenced frontend helpers and obsolete E2E routes. Preserve historical specs that record decisions, adding no replacement architecture.

**Tech Stack:** Java 21/Spring Boot, FastAPI/Python, Next.js/Node.js, Playwright, shell-based local-stack checks.

---

## 1. Lock current behavior

- [x] Run the dashboard unit/API baseline and record the result before edits.
- [x] Confirm the cleanup target list against repository references; retain ambiguous backend beans, feature flags, scheduler settings, environment variables, and contract fixtures.

## 2. Remove obsolete artifacts

- [x] Delete the superseded temporary handoff and tracked UI audit/planning reports that have no live consumer or unique historical decision.
- [x] Delete the tracked generated accessibility aggregate and ignore future `axe.json` output while retaining current E2E snapshots and fixture state matrices.
- [x] Preserve historical portfolio specs and their existing superseded link to the live-read delta.

## 3. Remove proven-dead frontend references

- [x] Remove only the uncalled paper-order detail/cancel, order-detail, and sellable-quantity API helpers from `web-dashboard/lib/api.js`.
- [x] Remove the unreferenced `SellableQuantityInfo` component from `web-dashboard/app/orders-view.js`.
- [x] Remove obsolete sellable-quantity and full paper-order GET branches from E2E fixtures, keeping approval preview, step-up, approve/cancel, buying-power, and current unsupported-route behavior.
- [x] Run targeted dashboard tests after each cleanup pass and revert any fixture deletion that changes a live journey.

## 4. Full verification and review

- [x] Run dashboard lint/tests/build/E2E, analysis tests, backend verification, and local-stack smoke/contract checks.
- [x] Scan for dead references, broken local documentation links, and declared-but-unreferenced environment variables; document intentional Spring-bound or compose-bound exceptions.
- [x] Run independent code-reviewer and architect reviews, fix only actionable findings within scope, then repeat full verification.

## 5. Integration

- [x] Commit the reviewed cleanup with the repository's Korean commit format and required co-author trailer.
- [x] Push the feature branch, create the pull request, wait for the CI gate, and squash-merge it into `main`.

## Stop condition

Stop when the cleanup diff contains only proven-obsolete artifacts/references, current contract tests and full verification pass (or an external blocker is recorded), and the reviewed branch is squash-merged/pushed without unrelated changes.
