# Investment prompt contract alignment

## Scope

Reuse persisted Investment Context, connector authentication, Thesis State storage and existing Sheet scheduler. No orders or investment decisions are generated.

## Contracts

- V56 preserves four legacy invalidation states and accepts AI_PROPOSED, UNVERIFIED and INVALIDATION_UNDEFINED.
- put_investment_thesis uses existing connector:trade scope for proposal upserts. Model tools cannot confirm or overwrite CONFIRMED records. Updates require exact expectedUpdatedAt and serialize with authenticated thesis writes on the owning user row.
- securities[].sizingEligibility exposes YES/NO/CONDITIONAL; existing risk.sizingEligible remains boolean.
- Thesis State owns columns A:N. Compatible manual extension columns retain row positions; incompatible canonical headers remain protected.
- PostgreSQL is canonical. Context reads persisted data and Sheets mirrors on the existing schedule.

## Validation

PostgreSQL/Flyway integration tests verify new states and conflict protection. MCP tests verify write scope and confirmation denial. Sheet tests verify extension preservation and combined portfolio mirroring. Full Maven verify must pass before merge.

## Exclusions

No intraday feed, Calibration Log tab, scheduled-task/Notion changes, tactical MCP writes or append-only proposal audit ledger. Runtime tool discoverability is verified separately from local code.
