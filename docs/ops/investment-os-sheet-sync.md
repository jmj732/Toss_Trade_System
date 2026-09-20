# Investment OS Sheet sync

The optional `InvestmentOsSheetConfiguration` runs a five-minute, read-only Toss sync for one configured connection. The observed MINVESTOS connection is mapped to `ACCOUNT_2` by default, and the managed label can be changed explicitly with `INVESTMENT_OS_SHEET_ACCOUNT_LABEL=ACCOUNT_1|ACCOUNT_2`. It reuses `AccountSyncService`, `ConnectorService`, and `BrokerSurfaceService`; current quotes refresh every held ticker in both account rows while only the configured account's holdings, cost basis, and cash are broker-upserted. Market values and account/combined USD metrics are recalculated only when every held ticker has a valid quote. A failed quote preserves prior prices and metrics and records a partial price reconciliation. HWM tracking starts from the first verified sync because earlier history is unavailable. A PostgreSQL lease (`investment-os-sheet-sync`) prevents duplicate workers across instances.

Enable only after the target spreadsheet is shared with the service-account email as Editor and the Sheets API is enabled:

```text
INVESTMENT_OS_SHEET_ENABLED=true
INVESTMENT_OS_SHEET_USER_ID=<MINVESTOS user UUID>
INVESTMENT_OS_SHEET_CONNECTION_ID=<Toss broker connection UUID>
INVESTMENT_OS_SHEET_ACCOUNT_LABEL=ACCOUNT_2
GOOGLE_SHEETS_SPREADSHEET_ID=14GkxnY1zubBjTwtp2NB7pSDFJ4TKeYj3_3YVBzMzBQY
GOOGLE_SHEETS_SERVICE_ACCOUNT_JSON=<service-account JSON; Doppler only>
```

`GOOGLE_SHEETS_SERVICE_ACCOUNT_JSON` is never committed or logged. Production reads these values from Doppler `trade/stg`; Compose keeps the feature disabled by default. Manual sync uses the session-authenticated endpoint:

```text
POST /api/v1/investment-os/sheet-sync
```

Connector API keys do not authenticate this endpoint. A Toss portfolio failure appends only a reconciliation row and leaves published account, order, aggregate, and metric values intact; a Google read failure writes nothing. A quote-only failure can publish the authoritative holdings/orders snapshot, but retains existing prices and skips aggregate/metric recalculation until the next complete quote pass. No fill price is inferred from an order limit price.
