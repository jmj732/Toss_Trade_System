# Investment OS Decision Layers Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Execute only the active phase; gates are sequential.

**Goal:** Preserve contemporaneous monitoring inputs, decisions and subsequent outcomes without orders or automatic threshold changes.

**Architecture:** Spring remains authenticated API/persistence/scheduler owner, PostgreSQL durable ledger. FastAPI remains deterministic analysis. Reuse monitoring.evaluation.evaluate for replay, MonitoringEvaluationClient for runtime analysis, existing outbox for qualified real-time changes, existing prediction outcome/calendar patterns for maturation.

**Tech Stack:** Existing Python/Pydantic/FastAPI, Spring/JdbcTemplate/PostgreSQL/Flyway; no dependencies.

## Delta investigation
- Market evaluator: analysis-service/app/monitoring/market_risk.py; freshness excludes future asOf/collectedAt, units normalized in evaluator. Replay must call the exact existing evaluation entry point.
- Portfolio evaluator: portfolio_risk.py; position contexts already contain sector/factor/beta/correlation/thesis/conditions and existing risk-policy limits. Extend rather than replace.
- Watchlist: watchlist.py + MonitoringWatchlistService; retain thresholds/statuses.
- Scheduler/coordinator: MonitoringScheduler/MonitoringCoordinator, existing adapters for FRED, quotes and official events. Replay has no outbox writes.
- Alerts: MonitoringEvaluationPersister + notification/Telegram outbox, existing escalation dedup.
- Historical metric storage: monitoring_market_observations (V47), source-stamped points; no replay-run ledger yet.
- Persistence: V47 monitoring states/history/contexts/cursors. V49 official provenance. Add V50 onward only per active phase.
- Decision/outcome precedent: prediction ledger and ForecastQualityMonitoringService exist; do not mix forecast accuracy with monitoring signals.
- Sheets: InvestmentOsSheetSyncService is separate authenticated connector state sync; no monitoring decision ownership, no new Sheets writes.
- Current production P0 limitations remain explicit: missed systemic funding/credit, yen unwind, broad crash; do not change thresholds to fit crisis dates.

## Phase 1: Historical Replay
- [x] Tests first: production equality, point-in-time filtering, revised approximation metadata, chronological transitions, invalid/future inputs, API integration and transactional persistence.
- [x] Python replay.py/models and internal route; input is existing MonitoringRequest snapshots with quality/version metadata. Capture all market axes and complete input/output.
- [x] Spring MonitoringReplayService/Controller, V50 replay runs/points, immutable source snapshots; use existing analysis URL/timeouts, owner scoping, atomic run persistence, no alerts.
- [x] Historical import/report command: read-only official FRED observations + price history when available, explicit historical approximation for revised data. Stress and control manifest; missing sources marked unavailable.
- [x] Gate: Python suite, Spring suite, real PostgreSQL integration, HTTP replay, failures, persistence, regression; no Phase 2 before green.

## Phase 2: Forward Outcomes and FP/FN
- [ ] Test first forward 1/5/10/20/60 trading observations, returns/MAE/MFE/volatility/drawdown, censor incomplete data; separate labels from signals.
- [ ] Config-driven thresholds/error costs; per-state confusion/precision/recall/specificity/FPR/FNR/lead/duration/recovery, zero denominators UNKNOWN.
- [ ] Add outcome/metric persistence and report API; gate same six categories.

## Phase 3: Position Mapping
- [ ] Test first profitable mega-cap vs financing-dependent high-beta small-cap, unknown data, evidence and no market-only exit.
- [ ] Extend existing context with vulnerability features; configurable scenario rules and six candidate actions, preserve market/company/portfolio reasons, confidence/invalidation.
- [ ] Wire existing monitoring evaluations/persistence; gate same six categories.

## Phase 4: Thesis/Event
- [ ] Test first official guidance raise/cut, dilution, contract win/loss, non-official/future evidence, no one-event automatic invalidation.
- [ ] Extend existing thesis context with structured expectations, evidence ledger, reviewed/confirmed invalidation; connect material event evaluator.
- [ ] Gate same six categories.

## Phase 5: Opportunity
- [ ] Test first acceleration/overreaction/quality/value trap and missing dimensions.
- [ ] Reuse watchlist/company context; retain nine dimensions, complete capital alternatives including benchmark/sector/cash, UNKNOWN expectation without invented ranking.
- [ ] Qualified deduplicated Korean state-change alerts only; gate same six categories.

## Phase 6: Shadow Calibration
- [ ] Test first immutable input/rule/version decisions; idempotent horizon scheduling/evaluation; separately attested process vs return quality; independent-sample/multi-regime/multi-horizon gates.
- [ ] Persist all candidate decisions; scheduler obtains historical observations read-only, outcomes matured by actual trading sessions, proposal-only calibration monthly.
- [ ] End-to-end replay -> position/thesis/opportunity -> decision -> matured outcome -> metric -> proposal; full regression.

## Safety and reporting
No broker order methods, LLM calls in monitoring loops, live production deployment or threshold mutation. No historical/replay/calibration Telegram. Historical evidence with unavailable contemporaneous vintages is HISTORICAL_APPROXIMATION, never point-in-time proof. No calendar-specific crisis branches. Final report includes unavailable metrics and remaining P0 issues honestly.

## Contracts and measurement safeguards
- Caller version names are run labels. Authoritative executedVersions are server-generated SHA-256 fingerprints of evaluator code, actual rule constants and freshness/config constants, persisted with full input/result snapshots.
- Replay effective market state follows real-time persistence: insufficient NORMAL does not clear current risk; adverse axes becoming UNKNOWN do not imply recovery; alertGenerated means would-alert, never delivered Telegram.
- Forward outcomes use supplied actual dated trading-session bars (no fabricated weekday calendar). Missing sessions/horizon data are censored, never false negatives or zero returns. OHLC permits true range excursions; close-only data is labeled approximation.
- Outcome prices may occur after decision T for evaluation only, never evaluator inputs. Adjusted/revised history retains approximation metadata.
- Per-state metrics use explicit signal rank and separate configured forward drawdown/credit/funding labels; report benchmark/horizon/version separately. Denominator-zero measures are null. Distinguish per-observation confusion from independent episode/decision counts.
- Position/thesis/opportunity inputs carry observedAt/source/confidence, reject future data, mark incomplete features UNKNOWN. No invented business quality or return expectations from missing fundamentals.
- Thesis invalidation requires reviewed explicit matching conditions, not a keyword or one event. Event classification must preserve official source and event identity; duplicate evidence is idempotent.
- Capital competition preserves all alternatives and dimensions. Expected-value ranking is UNKNOWN without comparable estimates/horizons; a high historical return is not an expected return.
- Process quality derives from pre-decision source freshness, policy/evidence completeness and explicit review criteria, never subsequent return; outcome quality is measured independently.
- Calibration proposals require at least ten independent decisions, multiple regimes and multiple matured horizons. Overlapping repeated alerts are not independent samples. Production config is never written by proposal generation.

Historical provenance reference: [FRED real-time periods](https://fred.stlouisfed.org/docs/api/fred/realtime_period.html) defaults to current-date vintage; [ALFRED archival data](https://fred.stlouisfed.org/docs/api/fred/alfred.html) is needed for original release/revision vintages. Public graph CSV replay is explicitly an approximation, not a point-in-time validation.

## Position/action delta constraints for later phases
Reuse monitoring_position_contexts for source-stamped feature inputs; add a JSON field rather than a parallel position owner. Broker quantities/value/weights remain PortfolioReadService-owned. Feature provenance and freshness are independent of the portfolio snapshot timestamp; updating one context must not refresh old fundamentals. Missing or stale fundamentals remain UNKNOWN. Existing legacy portfolio state/evidence contract remains available; the six requested action candidates are separate explicit output fields with immutable contemporaneous evidence. Market-only EXIT_REVIEW is forbidden. Held adverse market state must not produce a new ADD_CANDIDATE/KEEP solely because fresh data disappeared; compare effective persisted risk with raw evaluator output and preserve the reason. Rules/config fingerprints must include added position/thesis/opportunity rules and input freshness policies. Later phases begin only after the active phase gate passes.
