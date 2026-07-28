# Manual Event Ingestion UI Delta Spec

- Extend the existing authenticated dashboard page; add no route or dependency.
- Reuse existing owned event create/list/detail/reanalyze/review APIs unchanged.
- Manual event fields: source, source event ID, type, summary, occurred time.
- Affected symbols are checkboxes from the current portfolio positions.
- Event creation is disabled when no owned connection or no symbol is selected.
- Existing `(user, connection, source, sourceEventId)` uniqueness remains the
  duplicate authority; UI shows the public `EVENT_ALREADY_EXISTS` code.
- Load event review summaries with the selected connection dashboard.
- Selecting an event loads its owned detail and stored comparison.
- Reanalysis uses the existing event endpoint, then refreshes detail and list.
- Review actions map to `CONFIRMED`, `HELD`, and `IGNORED`; send current version
  plus a browser-generated idempotency key.
- Reuse the page single-flight guard; all event mutations disable together.
- Display review status/version and position/currency before, after, and change.
- No news ingestion, LLM call, automatic order, or order proposal creation.
- Tests cover paths, CSRF, idempotency, duplicate error, controls, and comparison.
