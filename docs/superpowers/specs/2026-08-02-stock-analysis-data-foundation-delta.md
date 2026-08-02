# Stock Analysis Data Foundation Delta

Status: implementation contract.

## Scope

- Add role-based, opt-in provider contracts/configuration for Toss, SEC, FRED, BLS, BEA, Federal Reserve, FMP, Finnhub, and Polygon/Twelve Data.
- Collect only provider-owned values; preserve provider identity, source `asOf`, `collectedAt`, and field-level `missingData`.
- Keep provider transport policy isolated per provider: rate limit, timeout, retry.
- Continue analysis with field-scoped degradation when a provider is unavailable.
- Persist and send a normalized stock-analysis input snapshot end-to-end to the stateless analysis service.
- Keep Forecast and Gemini explain out of scope.

## Architecture decisions

- `marketdata` owns provider ports, catalog, opt-in configuration, and provider-local transport policy. `analysis` consumes only immutable marketdata DTOs.
- Existing portfolio `analysis_runs`/`analysis_results` remain unchanged. Stock analysis uses `stock_analysis_runs` and `stock_analysis_results`; this avoids mixing portfolio v1 lifecycle with stock-input execution.
- `analysis_input_snapshots` is append-only and links to a stock run through `stock_analysis_runs.input_snapshot_id`. Snapshot payload is canonical JSON and SHA-256 hashed before insert.
- A normalized observation is a typed field envelope: `field`, `value`, `unit`, `period`, `identifier`, `provider`, `asOf`, `collectedAt`, `missingData`. Values from different providers remain separate observations, even when `field` matches.
- Provider catalog ownership is explicit: Toss=broker/account, SEC=regulatory filings, FRED/BLS/BEA/Fed=macro, FMP=fundamentals, Finnhub=news, Polygon/Twelve Data=market data. No provider is fallback for another provider's field.
- Provider endpoint and field selection stay provider-local configuration. Transport supports provider-specific API-key header/query auth, static query parameters, and User-Agent without sharing policy or response state across providers. Concrete provider schema mapping and Toss credential-scoped account integration remain follow-up work; Phase 0 real credentials stay disabled.
- Only typed provider transport failures are converted to field-level missing data. Unexpected assembler/programming failures are surfaced as workflow failure.
- This slice exposes one authenticated manual execution path. Scheduled/event fan-out, Forecast, Gemini explain, proposals, and orders are follow-up deltas.

Provider opt-in requires complete provider-local configuration; the root defaults intentionally enable none:

```yaml
stock-analysis:
  providers:
    fmp:
      enabled: true
      base-url: https://financialmodelingprep.com
      path: /stable/quote/{symbol}
      api-key: ${FMP_API_KEY}
      api-key-query-parameter: apikey
      fields:
        quote.price: /0/price
      as-of-path: /0/timestamp
      units:
        quote.price: USD
      identifiers:
        quote.price: '{symbol}'
```

Use the same shape for each provider, selecting its own endpoint, auth mode, query parameters, field pointers, and policy. SEC additionally requires a non-secret `user-agent`; FRED/BLS/BEA/Fed series/report identifiers belong in `identifiers` or `query-parameters`.

## Non-goals

- No provider-value averaging, arbitrary synthesis, or estimation.
- No automatic order generation or approval bypass.
- No Forecast model, prediction output, or Gemini explanation.
- No raw response persistence or logging.
- No real provider credentials enabled by default.

## Acceptance criteria

- [ ] Provider roles are explicit and opt-in; disabled providers create no client/work.
- [ ] Normalized snapshot and every field carry provider/asOf/collectedAt/missingData metadata.
- [ ] Missing provider data remains missing/null with a reason; no fallback arithmetic across providers.
- [ ] One provider failure leaves unrelated provider fields and analysis usable, with degraded status/quality.
- [ ] Provider timeout/retry/rate-limit settings do not affect another provider.
- [ ] Logs contain provider-safe identifiers and no secret, credential, or raw response.
- [ ] Snapshot is append-only, linked to analysis run, and sent/validated through a versioned backend↔FastAPI contract.
- [ ] Stock run/result lifecycle is separate from portfolio analysis v1 and is user-scoped.
- [ ] Normalization retains unit/period/identifier and stable canonical payload hash.
- [ ] Forecast/Gemini explain fields are absent.
- [ ] Targeted TDD tests, one independent review, full backend/analysis-service verification pass.

## Verification

- Backend provider contract/configuration and repository tests.
- Backend integration test for snapshot persistence, provider degradation, and analysis-service request.
- FastAPI contract tests for normalized snapshot and degraded field behavior.
- Schema tests for append-only snapshot/run/result tables and user scoping.
- `./mvnw clean verify`, `pytest`, and repository smoke checks where available.
