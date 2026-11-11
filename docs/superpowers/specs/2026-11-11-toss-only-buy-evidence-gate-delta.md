# Toss-only BUY Evidence Gate (delta)

## Goal

Run the investment BUY path with Toss Securities OpenAPI as the only automated
data provider, without letting wrong or missing data allow a BUY.

## Facts that bound this change

- Toss OpenAPI 1.2.28 (live `openapi.json`, checked 2026-11-11) has no financial
  statement, EPS, consensus, analyst estimate, or estimate-revision endpoint.
  SEC fundamentals, consensus and 30/90-day revision therefore cannot be filled
  from Toss. This change does not claim to fill them.
- Toss exposes `GET /api/v1/stocks?symbols=` (`StockInfo`: `status`
  `SCHEDULED|ACTIVE|DELISTED`, `delistDate`, `securityType`, `isCommonShare`,
  `leverageFactor`, `sharesOutstanding` without an as-of) and
  `GET /api/v1/stocks/{symbol}/warnings` (`StockWarning.warningType`, open enum;
  clients must tolerate unknown codes).
- `GET /api/v1/price-limits` returns null limits for US stocks and is not used.
- `SectorStock.marketCapitalization` is KRW-converted and is not used for US
  valuation.
- Today a STRATEGIC BUY needs internal fundamentals, consensus and revision all
  `OK`; under Toss-only that gate is permanently `BLOCKED`, and
  `start_strategic_buy_revalidation` (which needs `REVIEW_REQUIRED`) can never
  start. A TACTICAL BUY is evaluated on market data only.

## Decision (user, 2026-11-11)

STRATEGIC BUY may proceed on ChatGPT-supplied, cited evidence when the
automated source is Toss-only. The server cannot verify cited page contents;
this is a weaker guarantee than provider data and is bounded by the controls
below.

## 1. Source policy

- New property `investment.data.fundamental-source-policy`
  (`INVESTMENT_FUNDAMENTAL_SOURCE_POLICY`): `PROVIDERS` (default, current
  behaviour) or `TOSS_ONLY`.
- `TOSS_ONLY` with any of `stock-analysis.providers.{sec,alpha-vantage,fmp}`
  enabled fails application startup.
- Under `TOSS_ONLY`, fundamentals, consensus and revision readiness carry the
  reason `TOSS_SOURCE_UNSUPPORTED` (status remains non-`OK`). Diagnostics and
  context show this reason instead of an unexplained `DATA_MISSING`.
- `InvestmentBuyRiskGate`, STRATEGIC horizon, `TOSS_ONLY`, market data `OK`,
  market health evaluable, Toss hazards clear: result `REVIEW_REQUIRED` with
  reason `FUNDAMENTALS_EXTERNAL_EVIDENCE_REQUIRED` and an
  `evidenceMode=EXTERNAL_CITED` marker. Every other combination keeps the
  current result. Under `PROVIDERS` nothing changes.
- A stored fundamental/consensus/revision value that is `STALE`,
  `SOURCE_CONFLICT`, `UNVERIFIED`, future-dated, or present-but-failed is never
  treated as "unsupported": it stays `BLOCKED`.

## 2. Toss hazard checks (both horizons)

Evaluated live from Toss for every BUY prepare and submit; any fetch failure,
timeout, or unparsable response blocks.

| Check | Block reason |
|---|---|
| `status != ACTIVE` | `TOSS_STOCK_NOT_ACTIVE` |
| `delistDate` non-null and not after today | `TOSS_STOCK_DELISTING` |
| any warning, known or unknown code | `TOSS_STOCK_WARNING_<CODE>` (unknown → `TOSS_STOCK_WARNING_UNKNOWN`) |
| `leverageFactor` non-null and not `1`/`1.0` | `TOSS_LEVERAGED_PRODUCT` |
| `securityType == STOCK_WARRANTS` | `TOSS_SECURITY_TYPE_UNSUPPORTED` |
| stock info or warnings unavailable | `TOSS_STOCK_HAZARD_UNAVAILABLE` |

Quote sanity, also both horizons: reference price must be `> 0`, its
`observedAt` must not be in the future, and must be within
`price-stale-after`; otherwise `MARKET_DATA_UNVERIFIED`.

## 3. Structured ChatGPT evidence (STRATEGIC, `EXTERNAL_CITED` only)

`finish_strategic_buy_revalidation` gains a required `structuredEvidence`
object when the run was started under `EXTERNAL_CITED`. Any violation rejects
the request as invalid (no row is stored as `PASS`).

- `fundamental`: `form` in `10-K|10-Q|20-F|40-F`, `accessionNumber`
  (`^\d{10}-\d{2}-\d{6}$`), `periodEnd`, `filedAt`, `sharesOutstanding`, `url`.
  URL host must be `sec.gov` or `www.sec.gov` and its path must contain the
  accession number without dashes. `periodEnd < filedAt <= now`, `filedAt` not
  older than `fundamental-stale-after`.
- `consensus`: `horizon` (fiscal period end, after `now`), `revenueConsensus`,
  `epsConsensus`, `analystCount >= 1`, `currency == USD`, `asOf` not older than
  `consensus-stale-after` and not in the future, `url`.
- `revision`: `eps90dChangePct`, `revenue90dChangePct`, `asOf` (same freshness
  as consensus), `baselineAsOf` with `asOf - baselineAsOf` between 80 and 100
  days, `url`.
- Consensus and revision URL hosts must match
  `investment.revalidation.external-evidence-domains` (exact host or subdomain;
  default `nasdaq.com, zacks.com, marketbeat.com, finance.yahoo.com,
  stockanalysis.com, tipranks.com`).
- Consistency: `assessment.fundamentalRevision=SUPPORTED` with
  `eps90dChangePct < 0` → verdict `BLOCKED` (reason
  `REVISION_CONTRADICTS_ASSESSMENT`).
- Toss cross-check: the claimed SEC `sharesOutstanding` must be within ±15 % of
  Toss `StockInfo.sharesOutstanding`; Toss value missing or mismatch → verdict
  `BLOCKED` (`EVIDENCE_IDENTITY_MISMATCH`). This catches evidence for the wrong
  issuer; it does not verify the figures.
- Structured evidence is persisted with the review row and included in the
  body hash.

## 4. Bounds on externally evidenced BUYs

- Review TTL for `EXTERNAL_CITED` reviews: at most
  `investment.revalidation.external-evidence-max-ttl` (default `PT15M`).
- Order notional (`limitPrice × quantity`) at most
  `investment.revalidation.external-evidence-max-notional` (default `1000`
  USD); above → `STRATEGIC_EXTERNAL_EVIDENCE_NOTIONAL_EXCEEDED`.
- `validateForSubmit` re-checks that the policy is still `TOSS_ONLY` and the
  gate still reports `EXTERNAL_CITED`; Toss hazards are re-evaluated at submit.
- Every submit audit payload records `evidenceMode`.

## 5. Toss contract drift

Pin the reduced manifest to 1.2.28: list the paths the client calls (including
the two new ones) and assert, against the 1.2.28 schema, the fields each parser
reads. The 1.2.5 source document is no longer published, so the drift check is
"each parsed field exists with the same type in 1.2.28".

## Regression tests (must stay green)

- Gate: under both policies, `STALE`, `SOURCE_CONFLICT`, `UNVERIFIED`,
  future-dated and missing source values block STRATEGIC; `TOSS_ONLY` alone
  yields `REVIEW_REQUIRED` only when every other input is clean.
- Gate: each Toss hazard row blocks TACTICAL and STRATEGIC; hazard fetch failure
  blocks.
- Revalidation cannot start or pass when the gate is `BLOCKED`.
- Each structured-evidence rule above has a rejecting test; a fully valid
  payload yields `PASS`.
- Submit: notional cap, TTL cap, policy flip to `PROVIDERS` after review, and
  hazard appearing after review all block.
- Startup fails for `TOSS_ONLY` with SEC/Alpha Vantage/FMP enabled.

## Out of scope

Filling fundamentals/consensus/revision from any non-Toss provider; verifying
cited page contents; changing SELL paths.
