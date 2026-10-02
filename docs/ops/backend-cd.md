# Trade stack CD

The CD job lives in `.github/workflows/cd.yml`. A `web-dashboard/**` change is detected by
`.github/workflows/ci.yml`, runs the dashboard test/build and gate, then the CD workflow
builds and deploys the dashboard image after a successful push to `main`. The same deploy
also updates the Spring backend and analysis images from that verified commit. The separate
`deploy-vercel` job builds and promotes the same dashboard checkout to Vercel production after
the same successful CI completion. Deployments are serialized and an active deploy is not
cancelled by a newer push.

## One-time server setup

The target host must already have:

- Docker Engine and Docker Compose v2.
- A writable deploy path. CD syncs `compose.yaml`, `compose.staging.yaml`, and
  `compose.staging.credentialed.yaml` there on every deployment.
- Doppler CLI authenticated on the host for project `trade`, config `stg`.
  The deploy command also searches `$HOME/bin`, which supports a user-local CLI install.
- The existing database secret file and Doppler-backed provider/OIDC values. These stay on
  the server and are never sent through GitHub Actions.
- The reverse proxy forwarding the public HTTPS host to the backend's loopback port 8080.

The server-side command used by CD is equivalent to:

```sh
doppler run --project trade --config stg -- env \
  BACKEND_IMAGE=trade-backend:<commit> \
  ANALYSIS_IMAGE=trade-analysis:<commit> \
  DASHBOARD_IMAGE=trade-dashboard:<commit> \
  docker compose -f compose.yaml -f compose.staging.yaml \
    -f compose.staging.credentialed.yaml up --no-build -d --wait migrate backend dashboard
```

The backend, analysis, and dashboard images are built from the verified checkout, streamed
over SSH, and loaded on the host, so no registry token or remote source checkout is needed.
The deploy uses `--no-build` and removes unreferenced commit-tagged images, prunable untagged
parents, and transfer archives on both successful and failed exits; container image references
are preserved.

## Investment data provider mappings

The credentialed overlay preserves the FMP quote, FRED, and SEC mappings and adds FMP daily
regular-close history, quarterly balance-sheet fields, TTM income and cash-flow fields, and
annual analyst estimates. The EOD endpoint carries explicit `REGULAR_CLOSE` metadata and its
full dated response is passed through for history processing. FMP's quote endpoint has no
verified session label, so it remains available to legacy `quote.*` consumers and is not
classified as a live session price.

Fundamental fiscal dates and filing dates come from the statement rows. Consensus uses the
collection timestamp as `asOf`; the forecast row date is only its horizon. The current JSON
pointer mapping reads the first annual-estimate row, and FMP's mapped estimate fields do not
include FCF. Revenue growth uses the prior year's TTM row with the same fiscal year and period;
it remains missing if the exact comparison row is unavailable. Revision fields stay missing
until historical consensus snapshots exist. The investment scheduler and Sheets sync retain
their separate default-off switches.

## FMP entitlement recovery

### Current evidence

As of the runtime evidence captured at `2026-10-02T13:21:14Z`, deployed image `93be55cc1656f01d79f3139b21927eb34b64dc4f` was healthy, V52 had succeeded, and the investment data scheduler was enabled. This is a dated snapshot, not a claim about live status. The `SECURITY_DATA` pipeline failed with `PROVIDER_HTTP_402`. The current held symbols in that evidence were AVT, CSTM, LUNR, and RDW; prior requests for all four returned HTTP 402. The detailed examples include AVT quote and a separate EOD light probe; the configured full EOD route is `/historical-price-eod/full`. AAPL quote returned HTTP 200 as a control, which does not establish plan or route coverage for the held symbols. The account portal was signed out, so the key's account plan could not be independently verified.

### Route and symbol acceptance

The credentialed overlay calls these six FMP Stable API routes. Each request also carries `symbol=<ticker>` and the API key as the `apikey` query parameter; examples intentionally omit the key.

| Purpose | Route and configured query | Required response evidence |
|---|---|---|
| Quote | `/quote` | Non-empty quote payload for the requested symbol; mapped price, volume, change percent, and market cap. Quote has no verified session label, so it is not published as a live-session price. |
| Regular-close history | `/historical-price-eod/full` | Dated response rows mapped as `REGULAR_CLOSE`; retain the provider dates. |
| Quarterly balance sheet | `/balance-sheet-statement?period=quarter&limit=1` | Dated cash and debt fields, with fiscal and filing dates. |
| TTM income statement | `/income-statement-ttm?limit=5` | TTM revenue, EBITDA, diluted EPS and diluted shares, with fiscal and filing dates. |
| TTM cash flow | `/cash-flow-statement-ttm` | TTM free cash flow and statement dates. |
| Annual analyst estimates | `/analyst-estimates?period=annual&page=0&limit=10` | Annual horizon date and revenue, EPS, and EBITDA consensus fields. The observed-at timestamp is the snapshot `asOf`; the row date is the forecast horizon. FCF consensus is not mapped. |

### Free and zero-cost feasibility

**FMP Basic is free (250 requests/day) but cannot satisfy this bundle.** Its quote and balance-sheet rows restrict symbols to `AAPL`, `TSLA`, `AMZN`, plus 84 unlisted symbols; balance-sheet calls return up to five rows. Estimates are annual-only (max 10 rows/call). `Income Statements TTM` and `Cashflow Statements TTM` have no Basic/Starter/Premium coverage; Ultimate is the only listed tier for both. The matrix has no exact `/historical-price-eod/full` row or quarterly balance-sheet qualifier, so the minimum for all six routes is unconfirmed. The deployed key returned 402 for four held symbols; its plan is unknown. AAPL 200 proves only that quote request. The monthly view showed Starter US$29, Premium US$69, Ultimate US$139; annual prices are US$19/US$49/US$99 per month billed yearly. Matrix is Personal Use; verify rights for the actual app and Sheet use. No purchase is recommended.

| Zero-cost path | Verified coverage | What it cannot currently replace |
|---|---|---|
| SEC EDGAR company facts | No-key `company_tickers.json` resolution and CompanyFacts requests returned HTTP 200 for all four holdings. The official [SEC API guide](https://www.sec.gov/search-filings/edgar-application-programming-interfaces) documents submissions and CompanyFacts; SEC also documents [ticker-file limitations](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data). | The app's SEC mapping currently uses a static Apple CIK and reads filing metadata only. Per-ticker CIK/facts ingestion is not implemented. CompanyFacts provides filed facts, not quotes or analyst estimates; TTM needs calculation from duration facts and balance sheet values are instant facts. |
| FMP Basic/free | Free, 250 calls/day; annual estimates, limited symbols. | Current-key requests returned 402 for the four held symbols; full route coverage is unverified. |
| Twelve Data Basic | Official pricing lists a $0 tier with 800 calls/day. Its US feed covers about 5% of US trading activity, not the consolidated market. See [pricing](https://twelvedata.com/pricing), [US-equities coverage](https://support.twelvedata.com/en/articles/9935903-us-equities-market-data), and [quote timestamps](https://support.twelvedata.com/en/articles/5195429-pre-post-market-data). | Exact coverage for these four symbols and required fields is unverified. No API key or route checks are available; its individual plan terms are personal/internal/non-commercial, so usage rights need review. Not a verified replacement. |
| Alpha Vantage free | Official docs list 25 requests/day, daily historical OHLCV, and an end-of-day default quote. See [documentation](https://www.alphavantage.co/documentation/) and [free-tier limits](https://www.alphavantage.co/support/). | Real-time and delayed US quotes are premium. Free terms specify personal, non-commercial use. The four symbols, statements, and estimates were not verified; no API key is available. See [terms](https://www.alphavantage.co/terms_of_service/). |

### Analyst-estimate probe

Read-only runtime evidence captured at `2026-10-02 15:41 UTC` tested credential availability and the configured FMP estimate request. It did not verify free-tier entitlement for any provider.

| Provider and documented request | Result for AVT, CSTM, LUNR, and RDW |
|---|---|
| Alpha Vantage `function=EARNINGS_ESTIMATES` ([official docs](https://www.alphavantage.co/documentation/)) | **NOT TESTED:** `ALPHA_VANTAGE_API_KEY`, `ALPHAVANTAGE_API_KEY`, and `AV_API_KEY` were absent from the runtime. No demo-key request was used. The docs describe annual and quarterly EPS/revenue estimates, analyst counts, and revision history; actual symbol access and free-key response remain unverified. |
| Finnhub `/stock/eps-estimate` and `/stock/revenue-estimate` ([EPS docs](https://finnhub.io/docs/api/company-eps-estimates), [revenue docs](https://finnhub.io/docs/api/company-revenue-estimates)) | **NOT TESTED:** `FINNHUB_API_KEY` and `FINNHUB_TOKEN` were absent from the runtime. No demo-key request was used. Annual/quarterly symbol coverage and free-key access remain unverified. |
| FMP [annual estimates endpoint](https://site.financialmodelingprep.com/developer/docs/stable/financial-estimates) `/stable/analyst-estimates?symbol=<ticker>&period=annual&page=0&limit=10` | The existing `FMP_API_KEY` was present but hidden. Each held-symbol request returned HTTP 429 with the sanitized message `Limit Reach` and zero rows. No retries or quarterly request were made. This is a rate-limit result; it does not identify the account plan or prove a paywall. The earlier HTTP 402 evidence is from the separate `2026-10-02T13:21:14Z` capture. |

Next credentialed read-only matrix: after keys are available, make four Alpha Vantage `function=EARNINGS_ESTIMATES` calls (one per held symbol; response should include both annual and quarterly estimates). First make eight Finnhub calls: EPS and revenue endpoints for each symbol with `freq=quarterly`; test `freq=annual` only after quarterly rows are usable. Finnhub routes are `GET /api/v1/stock/eps-estimate` and `GET /api/v1/stock/revenue-estimate`, with `symbol=<ticker>` and `X-Finnhub-Token`. Retry FMP `/stable/analyst-estimates?symbol=<ticker>&period=annual&page=0&limit=10` for each symbol when rate capacity is available. Keep Alpha Vantage and Finnhub keys in Doppler project `trade`, config `stg`; do not put values in this report or logs. Judge EPS and revenue coverage separately: an endpoint passes only with HTTP 200, no provider error, and a non-empty expected numeric estimate row for the requested symbol and horizon. Alpha Vantage's revision-history timestamp/schema is not yet verified; preserve a validated provider revision timestamp if present. Use fetch time as local snapshot `asOf` for all providers unless Alpha Vantage returns a timestamp whose field and meaning are validated. A forecast fiscal period/date is a horizon, not observation time. Free access and usage rights must be verified separately. Do not backfill earlier analyst consensus: 30D/90D revisions need genuine same-source, same-horizon snapshots on or before each cutoff. Until then, leave revision values `DATA_MISSING`; do not substitute LLM output or fabricated history.

SEC is the strongest verified $0 partial path for filed fundamentals in this symbol set. The read-only probe resolved these CIKs and confirmed taxonomy/tag presence; it did not export financial values.

| Symbol | CIK | Taxonomy observed | Latest core filing evidence |
|---|---:|---|---|
| AVT | `0000008858` | US-GAAP | Period 2026-06-27; filed 2026-08-14 |
| CSTM | `0001563411` | US-GAAP and IFRS taxonomy present; latest core facts are US-GAAP | US-GAAP period 2026-03-31; filed 2026-04-29. IFRS facts observed only through 2024-06-30; filed 2024-07-23. |
| LUNR | `0001844452` | US-GAAP | Period 2026-06-30; filed 2026-08-13 |
| RDW | `0001819810` | US-GAAP | Period 2026-06-30; filed 2026-08-06 |

The SEC probe found standard revenue, diluted EPS/shares, operating cash flow, capex, and cash facts across these issuers; debt tags vary by filer/taxonomy. CompanyFacts covers standard, non-custom facts for the whole filer and may omit custom or dimensional facts. Map each issuer's taxonomy and units, preserve accession and filing dates, derive TTM only from eligible fiscal-duration facts, use instant facts for balance-sheet fields, and honor filing/acceptance times before treating SEC data as eligible. Do not treat CSTM's presence of an IFRS taxonomy as current IFRS facts. SEC facts and FRED macro values remain partial inputs; no provider change or company-facts integration is included here. Keep unavailable prices and consensus marked missing. No free source in this verification provides the required analyst estimates, so it cannot begin a 30D/90D consensus revision history.

For any plan, accept each route only when it returns HTTP 200 **and** a non-error, correctly shaped payload for the requested symbol and mapped fields. A 200 for AAPL quote alone is insufficient. Treat any other HTTP status as a failed coverage check. In particular, 402 blocks the affected route; 401/403 requires checking credentials and access; 429 or 5xx is a temporary quota/provider failure. Allow the configured retries, then repeat the check; these responses do not count as coverage. Before relying on any plan, verify its license permits the actual app and Sheet use.

### Update the credential and recapture

1. Keep the key in Doppler project `trade`, config `stg`, under `FMP_API_KEY`, using the access-controlled Doppler secret-management path. An FMP plan upgrade updates the existing key; only change Doppler if the key itself is rotated ([FMP FAQ](https://site.financialmodelingprep.com/faqs?code=statements)). Do not put the key in this report, source control, `.env`, shell history, command output, tickets, or deployment logs. The Compose overlay requires this Doppler value and sends it to FMP as `apikey`; protect any request traces that can contain query strings.
2. Only if the API key itself is rotated, update Doppler and refresh the backend through the established deployment procedure so the container receives that key. A plan upgrade updates the existing key and needs no secret refresh ([FMP FAQ](https://site.financialmodelingprep.com/faqs?code=statements)). Confirm readiness after any credential rollout and keep `INVESTMENT_DATA_SCHEDULER_ENABLED` enabled. This application exposes no manual recapture/admin API.
3. Allow the next normal full capture at 16:15 America/New_York on weekdays. It requests quote, EOD history, statements, and estimates. The 08:00 pre-market run refreshes portfolios and captures quote fields only; the five-minute intraday run is also quote-only after bootstrap.
4. If the in-memory startup bootstrap previously exhausted its three full-capture attempts, it does not retry that bootstrap until the backend process starts again. The scheduled after-close full capture remains active. For an earlier retry, use the existing backend restart procedure after the entitlement is fixed; a restart re-enters the existing bootstrap and is not a data backfill.
5. Verify readiness (`/actuator/health/readiness`) and inspect the existing structured backend logs for a successful `investment_data_after_close` or `investment_data_initial_bootstrap` operation. Then read the authenticated `GET /investment/context` response for the authorized account. `pipeline.status` should be `SUCCEEDED`, `lastSuccessAt` should advance, and `lastError` should be null. Check AVT, CSTM, LUNR, and RDW (plus in-scope active watchlist symbols) for source-dated regular closes, fiscal-dated fundamentals, and annual revenue/EPS/EBITDA consensus with a fresh collection `asOf`. Redact account identifiers and credentials from any shared evidence.

### Revision history acceptance

The capture stores new consensus snapshots as collected; it does not backdate or fetch historical analyst consensus. Revision compares the current value with the latest prior snapshot from the same source and forecast horizon whose collection `asOf` is at or before the 30- or 90-day cutoff. Therefore 30D and 90D revisions remain `DATA_MISSING` until a successful prior capture exists on or before the corresponding 30- or 90-calendar-day cutoff (roughly 30 and 90 days of accumulation, respectively). `consensus.horizon` is the forecast row date; `consensus.asOf` is when that estimate was collected. Never substitute the horizon for the capture time or manufacture a historical baseline. TTM financial statements are not forward estimates, and FCF consensus remains missing because the configured estimates route does not map it.

The investment scheduler and Sheets sync use independent enable switches. If Sheets sync is enabled, its current behavior archives a mismatched non-empty tab before writing the replacement; verify active tabs and archived content as part of that separate operation.

## GitHub Secrets

Add these repository or environment secrets before enabling the first deploy:

| Secret | Value |
|---|---|
| `BACKEND_DEPLOY_HOST` | Public server hostname or IP |
| `BACKEND_DEPLOY_PORT` | SSH port, for example `2222` |
| `BACKEND_DEPLOY_USER` | Dedicated deploy user, not root |
| `BACKEND_DEPLOY_PATH` | Writable server path for Compose files, for example `/home/deploy/trade` |
| `BACKEND_DEPLOY_SSH_KEY` | Private key for the deploy user |
| `BACKEND_DEPLOY_KNOWN_HOSTS` | Pinned output for the host from a trusted `ssh-keyscan -H` |
| `VERCEL_TOKEN` | Vercel production deployment token |
| `VERCEL_ORG_ID` | Vercel team/org ID for the linked project |
| `VERCEL_PROJECT_ID` | Vercel project ID for `web-dashboard` |

The workflow uses `StrictHostKeyChecking=yes`, `IdentitiesOnly=yes`, and the supplied pinned
known-hosts file. It does not log the key, provider credentials, Doppler token, or Compose
environment.

Disable Vercel's direct Git auto-deploy for this project. Otherwise Vercel can create a second
deployment that bypasses the repository's CI gate; the GitHub Actions `deploy-vercel` job is the
controlled production path.

## Rollback

CD has no manual trigger. Roll back by reverting the bad change through the normal PR flow;
the resulting verified push to `main` automatically rebuilds and deploys. Do not deploy from an
unverified working tree.
