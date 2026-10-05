# Investment OS Sheet sync

The optional `InvestmentOsSheetConfiguration` runs a five-minute Toss sync for one configured connection. That connection is always `ACCOUNT_1`; it is not remapped to the manual account. For the configured user, the canonical portfolio combines the broker-owned `ACCOUNT_1` rows with the manually maintained `ACCOUNT_2` rows from `Investment OS Account State`. `ACCOUNT_2` is read when its single `Account Registry` row has `Sync Mode=MANUAL` and `Enabled=TRUE`. If that registry is missing or unconfirmed, sync records a failed attempt and retains the last accepted combined snapshot rather than dropping `ACCOUNT_2`. Manual rows retain their recorded source, including values such as `USER_SCREENSHOT`.

Toss quotes refresh both accounts' holding rows, while only `ACCOUNT_1` holdings, cost basis, and cash are broker-upserted. A complete quote set allows aggregate and USD metrics to be recalculated; USD total is USD holdings market value plus USD cash from both accounts. KRW cash remains excluded because no verified FX conversion is applied. Unknown cash, incomplete quotes, or mixed currencies do not become zero or get converted. The PostgreSQL lease (`investment-os-sheet-sync`) prevents duplicate workers across instances.

Manual `asOf` is the preferred ACCOUNT_2 date. When that column is absent or blank, a valid `Synced At` value supplies the source timestamp; `Price Synced At` and the sync read time do not. An explicitly malformed date remains unknown. The portfolio reports the oldest complete manual row date and retains each holding's own date and source. A manual date more than seven days old is marked stale. A future date or missing source/date is unverified. Refreshing prices does not advance manual dates. HWM tracking starts from the first verified combined USD total because earlier history is unavailable.

Combined portfolio snapshots are persisted in PostgreSQL and drive the authenticated investment context without reading Google Sheets on GET. Failed or malformed manual reads append a failed attempt and retain the last accepted combined snapshot; they never fall back to an ACCOUNT_1-only portfolio. Structurally valid manual edits can be accepted when quote inputs are partial. Optional order failures affect order reconciliation but do not downgrade an otherwise complete portfolio snapshot. Numeric risk remains visible with stale manual provenance when its quantities, market values, quotes, and USD weights are known, while sizing eligibility requires fresh validated inputs. The Security Snapshot appends combined source/status, account and manual dates, and per-position source coverage without reordering existing columns.

An incompatible legacy header in `Thesis State` or `Decision Ledger` preserves that tab and its existing archive without copying or rewriting either one. The research mirror still updates compatible tabs, including `Security Snapshot`. If the primary account sync succeeds, these optional tab conflicts return `PARTIAL` with `RESEARCH_MIRROR_SCHEMA_CONFLICT_<tab names>` and mark the existing Reconciliation Log row unresolved; the accepted PostgreSQL portfolio snapshot keeps its original status. Other primary sync failures remain `FAILED`.

Enable only after the target spreadsheet is shared with the service-account email as Editor and the Sheets API is enabled:

```text
INVESTMENT_OS_SHEET_ENABLED=true
INVESTMENT_OS_SHEET_USER_ID=<MINVESTOS user UUID>
INVESTMENT_OS_SHEET_CONNECTION_ID=<Toss broker connection UUID>
INVESTMENT_OS_SHEET_ACCOUNT_LABEL=ACCOUNT_1
GOOGLE_SHEETS_SPREADSHEET_ID=<spreadsheet ID; Doppler only>
GOOGLE_SHEETS_SERVICE_ACCOUNT_JSON=<service-account JSON; Doppler only>
```

`GOOGLE_SHEETS_SERVICE_ACCOUNT_JSON` is never committed or logged. Production reads these values from Doppler `trade/stg`; Compose keeps the feature disabled by default. Manual sync uses the session-authenticated endpoint:

```text
POST /api/v1/investment-os/sheet-sync
```

Connector API keys do not authenticate this endpoint. A Toss portfolio failure leaves published broker and manual rows intact and records a failed combined-snapshot attempt; a Google read failure also preserves the last accepted combined snapshot. Optional order failures do not invalidate otherwise complete portfolio inputs. Incomplete quotes preserve prior prices and do not create a fresh aggregate valuation. No fill price is inferred from an order limit price.
