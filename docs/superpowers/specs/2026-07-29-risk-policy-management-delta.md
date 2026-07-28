# Risk Policy Management Delta

## Scope

- Per-user pre-trade risk limits: max order amount (KRW/USD separately), max quantity,
  max per-symbol concentration. `GET /api/v1/risk-policy` (current, defaults to the
  platform-wide config when uncustomized), `PUT /api/v1/risk-policy`
  (optimistic-concurrency versioned update), `GET /api/v1/risk-policy/history`
  (append-only change log, newest first).
- Applied inside the *existing* `PreTradeRiskEngine.evaluate()` — the same method both the
  APPROVAL and FINAL (pre-submission revalidation) phases already call — by resolving the
  caller's current policy via `RiskPolicyService.current(userId)` instead of the engine's
  previously-hardcoded global thresholds. No new call site, no new revalidation step; the
  policy simply replaces what the engine already checks against on both existing checkpoints.
- Policy version + full history stored (`risk_policies` current row, `risk_policy_history`
  append-only log — same append-only-trigger idiom as `pre_trade_risk_decisions`).
- `pre_trade_risk_decisions` gained a `risk_policy_version` column, written once at decision
  time and never touched again (the table was already append-only). Editing a policy later
  cannot retroactively change what an existing decision recorded — this is the literal
  mechanism behind "기존 주문은 당시 정책 버전 유지": past decisions simply keep whatever
  version they were written with, by construction, not by any special-case logic.
- **No real broker order submission changes, no automatic/algorithmic trading.** This delta
  touches zero lines of `OrderSubmissionService` (the real-broker dispatch path) and adds no
  scheduled/triggered order creation anywhere — it only changes which thresholds
  `PreTradeRiskEngine` (paper-trading only, as before) checks against, and adds CRUD for
  those thresholds.

## Minimal design

- `RiskPolicyService` (new, `com.jmj.trade.risk`) owns both the per-user override storage
  *and* the platform-wide defaults (same `pre-trade-risk.*` `@Value` properties that used to
  be injected directly into `PreTradeRiskEngine`) — `current(userId)` returns whichever
  applies, with `version=0`/`customized=false` meaning "no override, running on defaults,"
  a permanent valid state rather than a migration artifact.
- Optimistic-concurrency update, modeled on `EventReviewWorkflowService`'s
  `event_reviews` pattern (`ON CONFLICT (user_id) DO UPDATE` after a version check) — but
  guarded against the same-user concurrent-first-edit race that pattern doesn't itself close,
  by locking the (always-existing) `users` row for the target user before the version check,
  reusing the exact `SELECT ... FOR UPDATE` idiom `PreTradeRiskEngine.lockConnection()`
  already uses for connection rows.
- `PreTradeRiskEngine`'s constructor lost its four `BigDecimal` threshold parameters in favor
  of one `RiskPolicyService` dependency; `evaluate()` now resolves `riskPolicyService
  .current(userId)` once per check and uses its thresholds for the existing
  `MAX_QUANTITY_EXCEEDED`/`MAX_ORDER_AMOUNT_EXCEEDED`/`CONCENTRATION_EXCEEDED` checks — same
  `Reason` codes as before, since the check semantics are unchanged, only the threshold
  source moved from global-only to per-user-with-global-fallback.
- `RiskPolicyController`, matching `NotificationController`'s user-scoped
  (`Principal`→`userId()`, no connection-id path segment) shape exactly.
- Dashboard UI: a topbar panel (same toggle-dropdown shape as the notification bell),
  gated only on `session`, independent of any open broker connection — settings, not
  connection state.

## Plan

- [x] Migration (`V15`) + `RiskPolicyService`/`Exception`/`Controller` with failing
      CRUD/version-conflict/validation/history tests.
- [x] Wire `PreTradeRiskEngine` to per-user policy, with failing tests proving override
      behavior and that past decisions keep their recorded policy version after a later edit.
- [x] Dashboard risk-policy settings panel + API client functions + tests.
- [ ] Perform exactly one code review and fix findings.
- [ ] Run full verification (backend, analysis, dashboard, local-stack, existing drills).
- [ ] One feature commit, squash merge into base branch, push.
