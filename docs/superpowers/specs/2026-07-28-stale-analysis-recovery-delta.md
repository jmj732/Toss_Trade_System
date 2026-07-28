# Stale Analysis Recovery Delta

## Scope

- `PortfolioAnalysisWorkflowService.start()` atomically fails an abandoned `RUNNING` analysis
  run for the same `(user_id, broker_connection_id)` before attempting a new one, mirroring
  `AccountSyncTransactions.start()`'s existing sync recovery.
- Recovery only triggers as a side effect of a new `execute()`/`executeForEvent()` call. No
  scheduler, no heartbeat, no background component.
- A non-stale `RUNNING` row still blocks a new attempt with `ALREADY_RUNNING` (existing
  behavior unchanged).
- Reaping a stale row and inserting the new `RUNNING` row happen in the same transaction as
  today's insert step, so the reap is atomic with the conflict check.
- Existing `SUCCEEDED` runs and their `analysis_results` rows are never touched by the reap.

## Minimal design

- New constructor param `@Value("${portfolio.analysis.stale-after:PT15M}") Duration staleAfter`
  on `PortfolioAnalysisWorkflowService`, validated the same way as its existing timeouts.
- One `UPDATE analysis_runs SET status='FAILED', error_code='FAILED_STALE', completed_at=...
  WHERE user_id=? AND broker_connection_id=? AND status='RUNNING' AND started_at < now() -
  staleAfter` inserted immediately before the existing `INSERT ... ON CONFLICT DO NOTHING`,
  same statement shape as `AccountSyncTransactions.start()`.
- No migration: `analysis_runs` already has the `uq_analysis_run_running` partial unique index
  and the `FAILED` completion shape (`error_code` required) from the original schema.
- `DashboardReadModelIntegrationTest`'s direct constructor call gets the new argument.

## Plan

- [ ] Add failing tests: stale row reaped and new run succeeds; prior `SUCCEEDED` result
      untouched.
- [ ] Add failing boundary tests: row just under the threshold still blocks; just over is
      reaped.
- [ ] Add failing concurrency test: two simultaneous attempts against one stale row yield
      exactly one new run and one `ALREADY_RUNNING`.
- [ ] Add failing test: reap persists even when the new attempt's HTTP call then fails.
- [ ] Add failing test: reading `latest()` never reaps (no scheduler/heartbeat regression).
- [ ] Implement the reap statement and constructor param.
- [ ] Run related tests.
- [ ] Perform exactly one code review and fix findings.
- [ ] Run full verification (backend, analysis, dashboard, local stack, smoke).
- [ ] One feature commit, squash merge into base branch, push.
