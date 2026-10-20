# Investment OS OPENING Execution Contract Plan

## Goal

Connect a persisted, user-owned risk response decision to a read-only, account-local sell sizing and execution-readiness result. Preserve existing order preparation, thesis approval, policy, and MCP compatibility. Do not infer SELL from event severity or create operational orders.

## Implemented scope

1. Extend the append-only Decision Ledger with optional `targetRemainingQuantity` and `targetBrokerConnectionId`. Only an externally authored `REDUCE`/`EXIT` decision linked to one owned risk event and an active user-owned broker connection can provide a target. The server derives the reduction quantity from that account's persisted position. Existing rows and request signatures remain compatible.
2. Add a read-only MCP readiness tool that verifies decision ownership, linked risk event, current thesis revision, fresh account-local sellable quantity, and existing OPEN/PREPARED orders. Then inspect the existing Toss order book and official market calendar. Price freshness, session, spread, tick, and fractional-order capabilities must be verified; absent evidence is returned as a blocker. No provider refresh is performed outside this explicit user-invoked read tool.
3. Keep all action, sizing, pricing, and prepare gates distinct. `CRITICAL` alone never authorizes a trade. Existing `prepare_order` behavior is not reinterpreted as readiness.
4. Clarify that daily close labels represent session dates; capture/observation time is a separate field. Extend MCP error metadata safely, without exposing provider payloads. Fill deduplication is handled in its owning slice.
5. Document run audit fields (`promptVersion`, `scheduleRevision`, `runId`) and clarify that live scheduled-task configuration is not inspected or changed here.

## Verification

Add focused tests for decision target validation/idempotency, stale revision and event mismatch blockers, account-local derived quantity, unknown execution capabilities, duplicate order blockers, strict order-book/session gates, and MCP tool schema/dispatch. Re-run relevant existing context/order/MCP tests and compile.

## Explicit external blockers

No existing approved quantity policy was found. Therefore a persisted external `REDUCE`/`EXIT` target is required. Toss's existing order-book response has no tick-size contract, and fractional US LIMIT SELL is unsupported; those cases remain blocked instead of guessing or rounding. No production calls, orders, deployments, or live scheduled-task edits are part of this change.
