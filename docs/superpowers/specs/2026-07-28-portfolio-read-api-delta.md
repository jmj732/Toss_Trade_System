# Portfolio Read API Delta

- Status: Accepted by explicit autonomous-execution instruction
- Scope: latest usable persisted account snapshot only

## Decisions

1. `GET /api/v1/broker-connections/{id}/portfolio` uses the authenticated UUID owner.
2. Missing/cross-owner connections both return `BROKER_CONNECTION_NOT_FOUND`.
3. The selected run is the latest `SUCCEEDED` run for the current credential revision.
4. A newer `RUNNING` or `FAILED` run does not hide that success; the response sets
   `stale=true` with `SYNC_IN_PROGRESS` or `LATEST_SYNC_FAILED`.
5. No usable success returns `PORTFOLIO_SNAPSHOT_NOT_FOUND`.
6. `partial` is true only when the selected success lacks account, KRW capacity, or
   USD capacity rows; `missingSections` names them.
7. Zero positions is complete, not partial.
8. Buying power is keyed separately by `KRW` and `USD`; no conversion or aggregation.
9. Known unavailable cash is returned as `cashBalanceStatus=UNKNOWN` and listed in
   `unknownFields`.
10. Raw broker account identifiers, credentials, user IDs, and internal errors are omitted.

## Excluded

- Portfolio aggregation, automatic sync, FX conversion, and order submission.
