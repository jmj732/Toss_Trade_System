# Dashboard Read Model Delta

- API: `GET /api/v1/broker-connections/{connectionId}/dashboard`.
- Active broker connection ownership is checked once before any section query.
- Response has four independent sections: portfolio, analysis, pending events,
  pending order proposals.
- Each section exposes `stale`, `unknown`, `unknownFields`, `unavailable`,
  `unavailableReason`, and nullable `data`.
- Portfolio reuses the latest-success fallback read model. Missing success is
  `unavailable`; fallback and existing quality flags are preserved.
- Analysis uses the latest successful stored result. No result is `unavailable`.
- Analysis is also `stale` when its input snapshot differs from the dashboard's
  latest portfolio snapshot.
- Pending events are events with no review row or `PENDING` review status.
- Pending proposals are `OrderIntent` rows in `PROPOSED` status only.
- Event and proposal collections use set queries; query count is independent of
  returned row count.
- Dashboard reads existing ledgers only. It creates no table, row, event, or
  derived persisted state.
- Collection limit is fixed at 100, matching the existing event read boundary.
