# Prediction Evaluation Operations

## Scope

This runbook covers the opt-in prediction grading scheduler, its Prometheus metrics, safe
disablement, and the PostgreSQL V19 candidate-query reference plan. Prediction reads,
grading rules, and aggregation math are unchanged.

`/actuator/prometheus` remains on the backend's existing application port. Compose binds that
port to `127.0.0.1`; no public management port, proxy route, or Spring Security exception is
added. Do not expose the endpoint externally without the deployment's existing network ACL or
authenticated monitoring boundary.

## Configuration

Set these variables in the deployment environment:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PREDICTION_EVALUATION_ENABLED` | `false` | Enables scheduled grading |
| `PREDICTION_EVALUATION_INTERVAL` | `PT1H` | Tick interval |
| `PREDICTION_EVALUATION_INITIAL_DELAY` | `PT1M` | Startup delay |
| `PREDICTION_EVALUATION_LOCK_TTL` | `PT10M` | PostgreSQL lease TTL |
| `PREDICTION_EVALUATION_BATCH_SIZE` | `100` | Candidate rows per batch |
| `PREDICTION_EVALUATION_MAX_PER_TICK` | `1000` | Count cap per tick |
| `PREDICTION_EVALUATION_MAX_RUNTIME` | `PT5M` | Runtime cap per tick |
| `PREDICTION_EVALUATION_METRICS_CACHE_TTL` | `PT30S` | Backlog snapshot TTL |
| `PREDICTION_INGESTION_API_KEY_CLEANUP_ENABLED` | `true` | Marks due keys `EXPIRED` |
| `PREDICTION_INGESTION_API_KEY_CLEANUP_INTERVAL` | `PT1H` | Expiry sweep interval |
| `PREDICTION_INGESTION_API_KEY_CLEANUP_INITIAL_DELAY` | `PT1M` | First expiry sweep delay |

Keep `PREDICTION_EVALUATION_ENABLED=false` until the remaining limits have been reviewed for
the environment. Apply the variables through the normal deployment process, then verify
readiness and metrics from inside the existing network boundary:

```sh
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
curl -fsS http://127.0.0.1:8080/actuator/prometheus |
  grep '^trade_prediction_evaluation_'
```

The API key cleanup uses PostgreSQL time and only performs the immutable
`ACTIVE → EXPIRED` transition. It does not delete key metadata or rejection audit rows.

## Dashboard operations

After opening an owned broker connection, the `Prediction operations` panel shows only the
signed-in user's earliest due/ungraded backlog, maximum lag, and ingestion API keys. It can
issue, rotate, and revoke keys through the existing session/CSRF boundary. Raw key material is
shown only in the issue/rotation response and disappears when dismissed or the page reloads.
The panel cannot trigger evaluation, create predictions, or execute orders.

## Metrics and alerts

- `trade_prediction_evaluation_backlog`: due predictions at their earliest ungraded horizon.
- `trade_prediction_evaluation_max_lag_ms`: age of the oldest due horizon.
- `trade_prediction_evaluation_attempted_total`
- `trade_prediction_evaluation_succeeded_total`
- `trade_prediction_evaluation_quote_failed_total`
- `trade_prediction_evaluation_lease_failure_total{stage="acquire|renew"}`
- `trade_prediction_evaluation_early_stop_total{reason="count|time"}`

Both gauges share one PostgreSQL query and a JVM snapshot. Scrapes inside the configured TTL
do not query PostgreSQL again. A refresh failure retains the last successful snapshot; before
the first success both gauges report zero. Lag continues to advance from the cached oldest
due time, so gauge freshness is bounded by the cache TTL rather than by scrape frequency.

Useful PromQL:

```promql
trade_prediction_evaluation_backlog
trade_prediction_evaluation_max_lag_ms
increase(trade_prediction_evaluation_quote_failed_total[15m])
increase(trade_prediction_evaluation_lease_failure_total[15m])
increase(trade_prediction_evaluation_early_stop_total[15m])
```

Starting alert policy:

- Warn when backlog is non-zero and max lag remains above 24 hours for 15 minutes.
- Warn on any lease renewal failure; investigate repeated acquisition failures only when one
  active scheduler is not expected to hold the lease.
- Warn when quote failures increase for 15 minutes.
- Treat repeated count/time early stops as capacity pressure; first tune batch/count/runtime
  limits, then investigate broker latency and database plans.

## Safe disable and rollback

1. Set `PREDICTION_EVALUATION_ENABLED=false` and redeploy/restart the backend.
2. Confirm attempted count no longer increases after one previous interval.
3. Leave metrics enabled for diagnosis. Disabling the scheduler does not remove outcomes.
4. Do not delete or update existing outcomes: they remain unique and append-only.

An in-flight tick may finish before shutdown. Its lease is released normally or expires at the
configured TTL. No database rollback is required because disabling only stops future ticks.

## V19 keyset candidate `EXPLAIN ANALYZE`

Reference run: PostgreSQL 17.10, Flyway V1–V19, 2026-07-29. The isolated dataset contained
100,000 predictions on one active connection:

- 400 due at D1 with no outcome;
- 300 due at D5 with D1 present;
- 100 due at D20 with D1 and D5 present;
- 99,200 not due.

The production CTE from `AnalysisPredictionService.fetchDuePredictions` was run with
`EXPLAIN (ANALYZE, BUFFERS)`, cursor
`(now() - interval '30 days', 00000000-0000-0000-0000-000000000000)`, and `LIMIT 100`.
`ANALYZE` was run on both prediction tables immediately before the query.

Plan evidence:

```text
Limit ... (actual time=1.419..1.426 rows=100 loops=1)
  Buffers: shared hit=6603
  -> Sort
       Sort Key: (predicted_at + horizon interval), prediction.id
       Sort Method: top-N heapsort  Memory: 38kB
       -> Append ... (actual time=0.335..1.318 rows=800 loops=1)
            -> Index Scan using ix_analysis_predictions_due ... rows=800
                 Index Cond: predicted_at <= now() - interval '1 day'
            -> Index Scan using ix_analysis_predictions_due ... rows=400
                 Index Cond: predicted_at <= now() - interval '5 days'
            -> Index Scan using ix_analysis_predictions_due ... rows=100
                 Index Cond: predicted_at <= now() - interval '20 days'
Planning Time: 1.278 ms
Execution Time: 1.539 ms
```

PostgreSQL used V19's `(predicted_at, id)` index for all three horizon branches, then sorted
the computed `(target_due_at, prediction_id)` key with a bounded top-N heap. This is a
reference result, not a latency guarantee; connection count, due ratio, cache state, and table
growth can change the plan.

## Observability drill (automated)

Run:

```sh
./scripts/prediction-evaluation-observability-drill.sh
```

The drill starts an isolated compose project with random local secrets and ports, keeps
scheduled grading disabled, scrapes Prometheus over the backend's loopback binding, checks all
seven metric families, and removes its containers and volumes. It exits `0` and prints
`prediction evaluation observability drill: PASS` on success.

## What this does not cover

- No public Prometheus ingress, reverse proxy, authentication policy, or hosted alert rules.
- No production-sized load test or query-plan guarantee.
- No automatic tuning of batch, cap, lease, interval, or cache TTL values.
- No mutation or repair of existing prediction outcomes.
