# Investment OS Sheet sync

The optional `InvestmentOsSheetConfiguration` runs a five-minute, read-only Toss sync for one configured connection. The observed MINVESTOS connection is mapped to `ACCOUNT_2` by default, and the managed label can be changed explicitly with `INVESTMENT_OS_SHEET_ACCOUNT_LABEL=ACCOUNT_1|ACCOUNT_2`. It reuses `AccountSyncService` and `ConnectorService`, writes the four canonical tabs, and preserves all rows belonging to the other account label. A PostgreSQL lease (`investment-os-sheet-sync`) prevents duplicate workers across instances.

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

Connector API keys do not authenticate this endpoint. A broker or Google read failure appends only a reconciliation row and leaves the last Account State, Orders, and Aggregate rows intact. No fill price is inferred from an order limit price.
