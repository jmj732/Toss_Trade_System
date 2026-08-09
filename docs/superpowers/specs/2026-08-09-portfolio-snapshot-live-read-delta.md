# Portfolio Snapshot Live-Read Freshness Delta

- Status: Accepted by autonomous implementation request
- Scope: user-facing portfolio reads and live-order risk revalidation

## Decision deltas

1. `GET /api/v1/broker-connections/{id}/portfolio` and the dashboard portfolio read always run
   `live account sync → latest persisted snapshot reread` when an account sync service is
   available. Snapshot age never suppresses this synchronization.
2. The persisted snapshot remains the source returned after synchronization. Portfolio value,
   buying power, and position quantities therefore come from the latest completed account sync,
   including sellable quantities persisted by that sync.
3. If synchronization fails, the last usable snapshot may be returned only with `stale=true` and
   an explicit `LIVE_SYNC_FAILED` or persisted sync-status reason. The dashboard maps that reason
   to a degraded-data label and must not present the values as current.
4. Concurrent reads for the same user and broker connection share one in-process synchronization
   future. The existing database running-sync lease remains the cross-process/idempotency guard.
5. The live approval and submission paths synchronize before acquiring the connection row lock,
   then reread the latest persisted snapshot under the transaction and evaluate risk again. A
   stale fallback contributes `STALE_SNAPSHOT` and cannot approve a real order.
6. `portfolio.snapshot.max-age` remains only for stale/fallback classification. It is not a
   live-read skip condition.

## Tests and boundaries

- Fresh reads, sync-failure fallback, same-connection concurrent reads, and live approval ordering
  are covered by focused backend tests.
- Existing integration freshness coverage continues to exercise age-based stale classification and
  stale risk rejection; dashboard coverage exercises the degraded live-sync label.
- This delta does not change scheduled refresh defaults, add streaming, or enable real broker order
  placement.
