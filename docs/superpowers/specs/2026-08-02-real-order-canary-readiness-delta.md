# Real-order canary readiness

## Background

The live-order activation path can submit, cancel, modify, and reconcile Toss OpenAPI 1.2.5
orders, but it does not yet provide one operator-run canary procedure with an explicit small
limit and an auditable stop condition.

## Decision deltas

- Add `real-order.canary.*` configuration. Canary execution requires the flag, pinned connection
  and broker-account IDs, positive per-currency order caps below hard safety ceilings, a positive
  quantity cap, a bounded quote freshness window, and an allowed client-order-id prefix.
- Add a readiness endpoint and a single-run endpoint. Missing canary configuration, encrypted
  credentials, active connection, allowlist mapping, live dependencies, kill-switch reader, step-up
  authentication, or fresh quote returns a preflight-only result and never calls the broker order
  port.
- Require an operator-supplied `Idempotency-Key` for the run endpoint. Persist a hashed run key and
  request fingerprint, replay completed results for the same key, and hold a unique active-account
  claim so concurrent or abandoned runs cannot submit a second canary order.
- The run sequence is fixed: preflight → propose → approve → submit once → read OPEN and CLOSED →
  cancel once when an unfilled order is OPEN → read OPEN and CLOSED again → record final broker
  outcome or manual review. There is no retry or resend branch.
- Before proposal, approval, submission, observation/cancel, and final reconciliation, the service
  rechecks canary limits, exact account mapping, kill switch state, and quote freshness. Each
  broker-mutating stage obtains and consumes an OIDC-auth-time-backed step-up token.
- Add an append-only canary audit ledger. It stores internal IDs, enum statuses, booleans, safe
  reason codes, and SHA-256 hashes of client/broker order IDs; it never stores credentials, Toss
  account numbers, raw broker IDs, tokens, or broker error bodies.
- Document the operator procedure and failure handling in a runbook. UNKNOWN, cancel rejection,
  stale/failed lookup, and incomplete final reconciliation end in manual review without retry.

## Acceptance evidence

- Unit tests cover hard canary caps, missing-config preflight, stale-quote blocking, and the
  no-resend rule.
- Testcontainers integration covers a small allowlist canary run through submission, OPEN/CLOSED
  reads, one cancel, final reconciliation, and redacted audit rows.
- Fault injection covers broker lookup timeout and cancel UNKNOWN; both stop without a second
  submission or cancel.
