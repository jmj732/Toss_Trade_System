# Automated Market Event Ingestion — Delta Spec

## Scope

- Add opt-in providers for SEC EDGAR, company IR feeds, Federal Reserve feeds, FRED,
  BLS, and BEA.
- Provider enablement is configuration-driven and each provider is disabled unless
  explicitly enabled. The ingestion scheduler is independently disabled by default.
- Automated events enter the existing `EventIntelligenceService` write path, so existing
  ownership, `EVENT_CREATED` notification, review, and reanalyze APIs remain the single
  downstream flow. No LLM, order, or order proposal is introduced.

## Event identity and scope

- `source` stores the provider identifier (`SEC`, `IR`, `FED`, `FRED`, `BLS`, or `BEA`).
- `sourceEventId` is provider-stable and includes the provider's revision/vintage/value
  discriminator where the upstream has revisable observations.
- Existing uniqueness `(userId, brokerConnectionId, source, sourceEventId)` remains the
  database authority for idempotent insertion. A duplicate automated event is a no-op;
  it does not fail the provider run or emit a second notification.
- A revised upstream observation has a new source ID because its value/vintage is part
  of the identity; the original event remains immutable and both revisions are retained.
- `occurredAt` is the provider's filing/publication/observation period timestamp. The
  server's UTC `collectedAt` is stored separately.
- `affected_symbols` remains the stock linkage. `macro_scope` is always a JSON array of
  `{provider, identifier, period, vintage}` objects and is `[]` for stock-only events.
  Existing manual event payloads remain compatible.

## Provider adapters

- SEC reads configured CIK-to-symbol mappings and EDGAR submissions, using accession
  numbers as source IDs and the configured descriptive `User-Agent`.
- IR and Federal Reserve read configured RSS/Atom feed URLs using stable `guid`/`id` feed
  IDs, falling back to canonical link and then a hash of feed URL/title/publication time,
  plus publication timestamps. Feed URLs are configuration-only to avoid user-controlled
  SSRF.
- FRED reads configured series IDs; BLS reads configured series IDs; BEA reads configured
  dataset/table/line/geography scopes. Each observation becomes a macro-scoped event with
  a stable composite source ID.
- Shared HTTP transport applies bounded retries for 408/429/5xx/network failures and
  preserves provider boundaries: one provider failure does not abort other providers or
  already-collected events. A provider batch is capped by the lower of `batch-size` and
  `max-events-per-provider`; a failed provider run is retried while other providers
  continue. Macro events intentionally fan out to every active, non-deleted connection
  because the current product has no per-connection macro subscription table.

## Scheduling and recovery

- A PostgreSQL lease prevents concurrent instances from running the same sweep. Lease
  renewal, expiry, and owner-scoped release recover from crashed or long-running
  instances.
- Every provider attempt is recorded in `market_event_ingestion_runs`. Failed attempts
  carry attempt count, error, and `next_retry_at`; stale running attempts are reaped and
  become retryable. Subsequent sweeps reprocess due failures up to the configured retry
  cap while continuing with other providers.
- Ingestion is bounded by a lookback window and batch/provider limits; repeated collection
  is safe because event insertion is idempotent. `collected_events` counts inserted
  per-connection event rows for that provider run, not upstream observations.

## Downstream flow

- Automated events use the same event table and `EventIntelligenceService` insert path as
  manual events. Existing review/list/detail APIs expose them, and the existing explicit
  reanalyze endpoint accepts their event IDs; ingestion never auto-confirms, auto-reviews,
  places orders, or invokes an LLM.

## Tests

- Unit tests cover provider parsing/identity, XML hardening, retry classification, and
  provider registry opt-in.
- PostgreSQL integration tests cover macro scope persistence, duplicate no-op behavior,
  provider failure isolation, retry/reprocessing state, lease exclusion, and scheduler
  default-off registration.
