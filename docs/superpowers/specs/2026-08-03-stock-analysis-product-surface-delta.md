# Stock analysis product surface Delta spec

Branch: `feature/stock-analysis-product-surface`

## Goal

Expose the existing authenticated trading workspace as independent Next.js App Router
surfaces centered on `/stocks/[symbol]`, while keeping `/` as the current SPA entry point.
The stock surface must compose the persisted stock analysis, forecast, Gemini explanation,
related events, provenance, missing-data and immutable snapshot history. Analysis actions are
explicit user actions and never create or submit orders.

## Scope

- Add independent routes for `/portfolio`, `/stocks/[symbol]`, `/events`, `/orders`,
  `/predictions`, and `/settings`.
- Share the existing connection selector/session boundary and API helpers; preserve all
  existing `/` behavior and paper-order approval/cancel behavior.
- Add frontend API helpers for the existing stock analysis, forecast, and Gemini endpoints.
- Add authenticated stock-analysis history/read-by-run endpoints so past immutable snapshots
  can be selected without changing stored results.
- Bind forecast and Gemini reads to the selected analysis run so panels never mix snapshots.
- Render explicit `PROGRESS`, `FAILED`, `DEGRADED`, and `READY` states. HTTP errors remain
  isolated to their section so a failed forecast or explanation does not hide analysis/events.
- Surface provenance (`provider`, field, as-of, collected-at) and every missing-data reason.
- Related events are filtered from the already authorized connection event feed by symbol.
- Stock analysis pages contain no order command. Orders remain on `/orders` and continue to
  call the existing step-up/safety-gated command path, using a server approval preview and
  one-time step-up token before approval submission.

## Non-goals

- No change to broker order authorization, step-up, paper/live safety gates, or event review/
  reanalyze APIs.
- No new client dependency, charting library, or replacement of the existing SPA.
- No background scheduler or automatic analysis generation; route actions are opt-in.

## Contracts

### Stock analysis history

`GET /api/v1/stock-analyses/{symbol}/history?limit=20` returns an array of:

```json
{
  "runId": "uuid",
  "inputSnapshotId": "uuid|null",
  "symbol": "AAPL",
  "status": "RUNNING|SUCCEEDED|FAILED",
  "errorCode": "string|null",
  "startedAt": "instant",
  "completedAt": "instant|null",
  "result": "StockAnalysisCoreContract.Response|null"
}
```

`GET /api/v1/stock-analyses/{symbol}/runs/{runId}` returns the same item and enforces the
authenticated owner. Results and snapshots are read-only.

## Acceptance criteria

1. Visiting each requested route renders an independent surface while `/` still renders the
   current SPA.
2. A stock symbol route can load, create/rerun, and select a historical analysis snapshot.
3. Forecast and Gemini explain can be generated from the active model/connection context and
   their failure/degraded states remain visible without hiding other sections.
4. Stock detail shows related events, provenance, and missing-data reasons when present.
5. The stock detail source contains no `actOnProposal`/order submission path; existing order
   controls remain on the orders surface and retain the existing API/CSRF/idempotency path.
6. Added frontend and backend tests cover route rendering, API paths/CSRF behavior, status
   labels, history selection, and owner-safe history reads.
