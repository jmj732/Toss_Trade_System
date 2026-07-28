# In-App Notification Center Delta

## Scope

- Transactional outbox → idempotent inbox, internal only (no external system on either end):
  `notification_outbox_events` is written atomically inside the same transaction as each
  triggering domain event; `NotificationOutboxProcessor` (a `@Scheduled` sweep, default
  `PT2S`) consumes unprocessed rows with `SELECT ... FOR UPDATE SKIP LOCKED` and inserts into
  `notifications` via `ON CONFLICT (outbox_event_id) DO NOTHING` — reprocessing the same
  outbox row after a crash is a no-op, and concurrent sweep instances partition work instead
  of double-processing.
- Five trigger points, one outbox emission each, inside the existing transaction that already
  commits the domain result: `AccountSyncTransactions.complete()/fail()`,
  `PortfolioAnalysisWorkflowService.complete()/fail()`, `EventIntelligenceService.create()`,
  `OrderIntentTransitionService.transition()`, and `OrderSubmissionService.transitionIntent()`
  (both on any terminal `OrderIntentStatus`). Paper orders, pre-trade risk blocks, and manual
  cancellation flow through `OrderIntentTransitionService`; real-broker submission outcomes
  (fills, broker rejections, broker-side cancellations) flow through the separate
  `OrderSubmissionService`, which does not call `OrderIntentTransitionService` — both needed
  their own emission call, guarded identically by `OrderIntent.isTerminal(...)`.
- `GET /api/v1/notifications` (list, `unreadOnly` filter), `GET
  /api/v1/notifications/unread-count`, `POST /api/v1/notifications/{id}/read` (idempotent —
  marking an already-read notification read again is a no-op, not an error). Owner-scoped
  like every other endpoint in this codebase.
- Dashboard UI: a notification bell + list, loaded once per session (not per broker
  connection, since notifications are per-user) and refreshed after mark-read.
- No email/Slack/Kakao/push — in-app only, by explicit instruction.

## Noted scope decision (not a bug)

"동기화 결과 알림" is implemented literally: every sync completion notifies, including the
15-minute scheduled auto-refresh from `ScheduledPortfolioRefreshService` (it calls the same
`AccountSyncTransactions.complete()/fail()`). This will be noisy at scale — a real product
decision (failure-only, digesting, per-connection preferences) is future work, not silently
added here, since the instruction didn't ask for it and inventing throttling logic unrequested
would be scope creep in the other direction.

## Minimal design

- Migration `V14__create_notification_center.sql`: `notification_outbox_events` (unique on
  `(event_type, source_id)` — emission itself is idempotent, defense-in-depth against a
  trigger firing twice for the same domain event) and `notifications` (unique on
  `outbox_event_id` — the inbox idempotency guarantee).
- `NotificationOutboxWriter` (plain `JdbcTemplate` `@Component`): one `emit(...)` method,
  callable from both JPA-`@Transactional` services (`OrderIntentTransitionService`) and
  `TransactionTemplate`-wrapped raw-JDBC services (`AccountSyncTransactions`,
  `PortfolioAnalysisWorkflowService`, `EventIntelligenceService`) — it just participates in
  whichever transaction is already active on the calling thread.
- `NotificationOutboxProcessor` + `NotificationOutboxScheduler`, mirroring the
  `ScheduledPortfolioRefreshService`/`Scheduler` split from the stale-recovery delta so the
  core processing method is directly callable from tests without waiting on `@Scheduled`.
  Rendering (event type → title/body) lives here, not in the domain services, so they stay
  free of notification-formatting concerns.
- `NotificationService` + `NotificationController`, matching the existing
  `Principal`→`userId()`→owner-scoped-query convention used by every other controller.
- No new dependency, no change to any existing table's shape.

## Plan

- [ ] Add migration + failing tests: outbox emission at each of the 4 trigger points, atomic
      with the domain transaction.
- [ ] Add `NotificationOutboxWriter`, wire the 4 call sites.
- [ ] Add `NotificationOutboxProcessor`/`Scheduler` with failing idempotency/reprocessing/
      concurrency tests (`FOR UPDATE SKIP LOCKED` under real concurrent invocation).
- [ ] Add `NotificationService`/`Controller` with failing list/unread-count/mark-read/
      ownership tests.
- [ ] Add dashboard notification bell/list UI + API client functions + tests.
- [ ] Perform exactly one code review and fix findings.
- [ ] Run full verification (backend, analysis, dashboard, local-stack, existing drills).
- [ ] One feature commit, squash merge into base branch, push.
