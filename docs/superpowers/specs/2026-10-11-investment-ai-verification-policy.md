# Investment thesis AI verification policy

## Goal

Let an external AI caller submit a sourced thesis verification for the authenticated user's current proposal. Spring stores the caller's assertions and applies a user-scoped, versioned policy. A passing result may confirm the exact proposal revision; policy failures remain visible as blocked results. The feature does not run a model or retrieve evidence itself.

## Contracts

- `put_investment_thesis` remains a proposal write. It accepts optional `proposalRunId` (nonblank, at most 160 characters), stores it as asserted revision provenance, and cannot set `CONFIRMED`.
- `get_investment_thesis_verification_context` is an existing-scope read tool, with the equivalent session-authenticated REST read at `GET /investment/securities/{ticker}/thesis/verification-context`. It returns the current thesis revision, resolved policy, latest verification, persisted quote/risk-mark facts, and eligible trigger candidates. It does not refresh providers or write data.
- `verify_investment_thesis` uses the existing `connector:trade` scope and accepts `verificationEventId`, `ticker`, `thesisRevisionId`, `verificationRunId`, `provider`, `model`, `verdict`, `sourceAsOf`, `sourceUrls`, `rationale`, `counterevidence`, and required-but-nullable `selectedTriggerPrice`. Unknown fields are rejected.
- `PASS` produces `AUTO_APPROVED` only when every policy gate passes; otherwise it is persisted as `BLOCKED`. `REJECT` is persisted as `REJECTED`. A same-user replay of an event UUID and the same normalized body returns the original event; a different body conflicts.
- Evidence URLs, as-of time, provider/model labels, run IDs, verdict, rationale, and counterevidence are external assertions. `sourceAsOf` is the claimed observation time for the cited evidence, not a filing or fiscal-period end date. The service checks shape and policy but does not fetch or independently authenticate them.

## Policy and evaluation

Deployment defaults are disabled (`investment.thesis.ai-policy.enabled=false`), version `v1`, maximum evidence age `PT24H`, minimum URL count `2`, and maximum trigger distance `0.25` (25%). Deployment-level enablement requires `investment.thesis.ai-policy.enabled=true` and is restricted to the configured Sheet owner by `investment-os.sheet.enabled` and `investment-os.sheet.user-id`. A session-authenticated user policy revision can enable or disable that user's policy and takes precedence over the deployment default; thresholds remain deployment configured. The authenticated policy API is `GET` and `PUT /investment/thesis/ai-policy`; `enabled` is required. `expectedRevisionId` is optional or null when creating the first user revision and is the current revision UUID when updating.

For `PASS`, the service checks that the supplied thesis revision is still current and in a proposable state; policy is enabled; asserted proposal and verification run IDs differ when a proposal run ID exists; `sourceAsOf` is recent and not future; enough unique sources are supplied; a positive trigger is present; and the trigger is below a trusted mark within the policy distance. Failures are recorded with reason codes such as `STALE_PROPOSAL_REVISION`, `AI_POLICY_DISABLED`, `PROPOSAL_AND_VERIFICATION_RUN_MATCH`, `VERIFICATION_EVIDENCE_STALE`, `VERIFICATION_SOURCES_INSUFFICIENT`, `MISSING_TRIGGER_PRICE`, `TRUSTED_PRICE_<status>`, `TRIGGER_NOT_BELOW_TRUSTED_PRICE`, and `TRIGGER_DISTANCE_EXCEEDS_POLICY`.

The trusted mark is exposed separately from the latest quote and its status. The service prefers a valid current quote. It may use the canonical Toss daily regular close only during a verified declared closed interval when the quote/calendar facts and close source/status/session-date checks pass. A regular-close `asOf` is the provider's New York session-date label at midnight; it is not the actual exchange close instant. This fallback does not rewrite the quote, its timestamp, or its status. Missing, conflicting, stale, or unverified price facts do not become a usable risk mark.

## Ownership and safety

- V60 stores policy revisions and verification events in append-only PostgreSQL tables. A successful confirmation also appends a thesis revision with actor `AI_POLICY` and links the event/policy version.
- `Thesis State` remains a generated current-state mirror and adds only `Approval Actor`, `Policy Version`, `Verification Event ID`, and `Revision ID`; `Security Snapshot` adds the seven current risk-mark fields documented in the Sheet sync guide. No new tab or full verification-event ledger is added. Event evidence and policy history are stored in PostgreSQL; Context/MCP reads expose current provenance and the latest verification receipt, not full event payloads or history.
- Auto-confirmation is not sizing approval. Existing portfolio freshness, price, risk inputs, and soft-budget checks independently determine sizing eligibility. No verification result creates, prepares, or submits an order.
- No app permission or connector scope is added. The feature does not configure or update a scheduled task. An external scheduled AI must read the current revision/policy, gather actual evidence, call the tool using its existing authorized connector session, and inspect the persisted outcome. It must not fabricate evidence, `CONFIRMED`, or a successful tool result.

## External caller sequence

1. Read `get_investment_thesis_verification_context` for the ticker and take the returned `revisionId` and policy state as the starting point.
2. Gather actual source material independently. Provide truthful source URLs, source time, provider/model/run labels, balanced rationale and counterevidence, and a chosen trigger or explicit `null`.
3. If writing a proposal, use `put_investment_thesis` with `proposalRunId`, then re-read the context to obtain the saved revision ID. Do not set `CONFIRMED` in the proposal.
4. Call `verify_investment_thesis` with a stable event UUID for retries and the exact proposal revision. A new evidence submission needs a new event UUID.
5. Treat only persisted `AUTO_APPROVED` as policy confirmation. `BLOCKED` and `REJECTED` remain non-approved; sizing and order gates still apply separately.

## Non-goals

Spring does not run an AI model, fetch or score source URLs, invent a trigger, infer a successful external run, schedule a verifier, authorize an order, or bypass existing risk/sizing checks. Google Sheets is not an evidence, policy, or audit write surface.
