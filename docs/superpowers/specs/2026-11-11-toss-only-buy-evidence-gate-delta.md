# Toss-only BUY Evidence Gate (delta)

## Goal

Run the investment BUY path with Toss Securities OpenAPI as the only automated data provider, without letting
wrong, missing, or unavailable data allow a BUY. Automated data is never treated as more authoritative than it is.

## Facts that bound this change

- Toss OpenAPI 1.2.28 (live `openapi.json`, checked 2026-11-11) has no financial statement, EPS, consensus,
  analyst estimate, or estimate-revision endpoint. SEC fundamentals, consensus, and 30/90-day revisions therefore
  cannot be filled from Toss. This change does not claim to fill them.
- Toss exposes `GET /api/v1/stocks?symbols=` (`StockInfo`: `status` `SCHEDULED|ACTIVE|DELISTED`, `delistDate`,
  `securityType`, `isCommonShare`, `leverageFactor`, `sharesOutstanding` without an as-of) and
  `GET /api/v1/stocks/{symbol}/warnings` (`StockWarning.warningType`, open enum; clients must tolerate unknown codes).
- `GET /api/v1/price-limits` returns null limits for US stocks and is not used.
- `SectorStock.marketCapitalization` is KRW-converted and is not used for US valuation.
- Before this change, a STRATEGIC BUY needed internal fundamentals, consensus, and revision all `OK`. Under
  Toss-only that gate is permanently blocked, and `start_strategic_buy_revalidation` (which needs `REVIEW_REQUIRED`)
  could never start. A TACTICAL BUY is evaluated on market data only.
- Toss has no pinned contract for the legacy provider rows already stored in snapshots (SEC, Alpha Vantage, FMP).
  Those rows must not feed the gate and must not cause a permanent stale block under `TOSS_ONLY`.

## Final decisions (user, 2026-11-11, phase 2)

1. **Toss hard-risk checks apply under every policy.** The hazard checks in section 2 and the quote sanity check run
   for every BUY prepare and submit, under both `PROVIDERS` and `TOSS_ONLY`. An outage or unverifiable input blocks.
   `PROVIDERS` keeps its existing fundamentals behaviour. Stale, conflicting, and unverified stored values still block.
2. **`TOSS_SOURCE_UNSUPPORTED` leads to `REVIEW_REQUIRED`.** Under `TOSS_ONLY`, a STRATEGIC BUY whose only fundamental
   gap is Toss-unsupported and whose hazards are clear is `REVIEW_REQUIRED`, which allows ChatGPT revalidation. Real
   collection failures, `STALE`, and `SOURCE_CONFLICT` stay `BLOCKED`.
3. **ChatGPT fundamental evidence is accepted without server-side content verification.** Every such review records
   the source URLs, values, and as-of dates, and is explicitly marked `verificationStatus=SOURCE_NOT_VERIFIED`.
4. **Per-order approval and live risk checks at submit stay mandatory.** Nothing at submit is relaxed.

## 1. Source policy

- Property `investment.data.fundamental-source-policy` (`INVESTMENT_FUNDAMENTAL_SOURCE_POLICY`): `PROVIDERS` (default)
  or `TOSS_ONLY`. Any other value fails startup.
- `TOSS_ONLY` with any of `stock-analysis.providers.{sec,alpha-vantage,fmp}` enabled fails application startup.
- `PROVIDERS` keeps the existing fundamentals behaviour. Stored values that are `STALE`, `SOURCE_CONFLICT`,
  `UNVERIFIED`, future-dated, or present-but-failed block under both policies.
- Under `TOSS_ONLY`, each fundamentals, consensus, or revision group is classified as follows:
  - Absent (`DATA_MISSING` or `NOT_CONFIGURED`, no as-of, no source): `TOSS_SOURCE_UNSUPPORTED`.
  - Stored by a non-Toss provider (SEC, Alpha Vantage, FMP): `TOSS_SOURCE_UNSUPPORTED`. The value is returned under
    `legacyProviderData` with `source`, `asOf`, and `flag=LEGACY_PROVIDER_EXCLUDED`. It is shown for review and is
    never counted as evidence and never causes a stale block.
  - Any revision value: legacy by definition, because Toss never supplies revisions.
  - Toss-sourced values: existing checks. A stale, conflicting, unverified, future-dated, or source-missing Toss value
    still blocks.
- `InvestmentBuyRiskGate`, STRATEGIC horizon, `TOSS_ONLY`, market data `OK`, market health evaluable, Toss hazards
  clear, and only `TOSS_SOURCE_UNSUPPORTED` as a fundamental gap: result `REVIEW_REQUIRED` with reason
  `FUNDAMENTALS_EXTERNAL_EVIDENCE_REQUIRED` and `evidenceMode=EXTERNAL_CITED`. Any other combination keeps the
  current result. A legacy stale consensus under `TOSS_ONLY` therefore reaches `REVIEW_REQUIRED`, while the same row
  under `PROVIDERS` stays `BLOCKED`.
- Display. The readiness and context security views show `sourcePolicy`, `tossSourceUnsupported`, and
  `legacyProviderData`. Portfolio risk views use the same `TOSS_ONLY` mapping as prepare, so they do not show a
  misleading `BLOCKED`. Hazards in portfolio views are cached for 5 minutes. Failed reads are not cached. Prepare,
  submit, and review start always read live.
- Legacy rows are never evidence. The gate, the review, and the submit path ignore them.

## 2. Toss hazard checks (every policy, both horizons)

Evaluated live from Toss for every BUY prepare and submit, and for review start and completion. Any fetch failure,
timeout, or unparsable response blocks.

| Check | Block reason |
|---|---|
| `status != ACTIVE` | `TOSS_STOCK_NOT_ACTIVE` |
| `delistDate` non-null and not after today (KST) | `TOSS_STOCK_DELISTING` |
| any warning, known or unknown code | `TOSS_STOCK_WARNING_<CODE>` (unknown code: `TOSS_STOCK_WARNING_UNKNOWN`) |
| `leverageFactor` non-null and not `1`/`1.0` | `TOSS_LEVERAGED_PRODUCT` |
| `securityType == STOCK_WARRANTS` | `TOSS_SECURITY_TYPE_UNSUPPORTED` |
| stock info or warnings unavailable (including 404 and 500) | `TOSS_STOCK_HAZARD_UNAVAILABLE` |

Quote sanity, both horizons: the reference price must be `> 0`, its `observedAt` must not be in the future, and it
must be within `price-stale-after`. Otherwise the result is `MARKET_DATA_UNVERIFIED`.

Consequence: a Toss outage blocks every BUY until Toss recovers, under every policy. This is intentional.

## 3. Structured ChatGPT evidence (STRATEGIC, `EXTERNAL_CITED` only)

`finish_strategic_buy_revalidation` gains a required `structuredEvidence` object when the run was started under
`EXTERNAL_CITED`. Any structural violation rejects the request and stores nothing.

- `fundamental`: `form` in `10-K|10-Q|20-F|40-F`, `accessionNumber` (`^\d{10}-\d{2}-\d{6}$`), `periodEnd`, `filedAt`,
  `sharesOutstanding`, `url`. URL host must be `sec.gov` or `www.sec.gov`, and its path must contain the accession
  number without dashes. `periodEnd < filedAt <= now`, and `filedAt` must not be older than `fundamental-stale-after`.
- `consensus`: `horizon` (fiscal period end, after `now`), `revenueConsensus`, `epsConsensus`, `analystCount >= 1`,
  `currency == USD`, `asOf` not older than `consensus-stale-after` and not in the future, `url`.
- `revision`: `eps90dChangePct`, `revenue90dChangePct`, `asOf` (same freshness as consensus), `baselineAsOf` with
  `asOf - baselineAsOf` between 80 and 100 days, `url`.
- Consensus and revision URL hosts must match `investment.revalidation.external-evidence-domains` (exact host or
  subdomain; default `nasdaq.com, zacks.com, marketbeat.com, finance.yahoo.com, stockanalysis.com, tipranks.com`).
  A trailing dot is rejected.
- Consistency: `assessment.fundamentalRevision=SUPPORTED` with `eps90dChangePct < 0` gives verdict `BLOCKED` with
  reason `REVISION_CONTRADICTS_ASSESSMENT`.
- Toss cross-check: the claimed SEC `sharesOutstanding` must be within ±15% of Toss `StockInfo.sharesOutstanding`.
  A missing Toss value or a mismatch gives verdict `BLOCKED` with reason `EVIDENCE_IDENTITY_MISMATCH`. This catches
  evidence for the wrong issuer; it does not verify the figures.
- Verification status: every `EXTERNAL_CITED` review is stored with `verificationStatus=SOURCE_NOT_VERIFIED` (migration
  V70 `verification_status`, constrained to match `evidence_mode`). The value is returned by the finish result and
  validation, written to the submit audit payload, and shown in the approval preview.
- Structured evidence is persisted with the review row and included in the body hash. `PROVIDERS` reviews keep their
  existing hash.

## 4. Bounds on externally evidenced BUYs

- Review TTL for `EXTERNAL_CITED` reviews: at most `investment.revalidation.external-evidence-max-ttl`
  (default `PT15M`).
- Order notional (`limitPrice × quantity`) at most `investment.revalidation.external-evidence-max-notional`
  (default `1000` USD). Above that, the result is `STRATEGIC_EXTERNAL_EVIDENCE_NOTIONAL_EXCEEDED`.
- `validateForSubmit` re-checks the evidence mode against the live gate, re-checks that the policy is still
  `TOSS_ONLY`, re-checks the stored TTL, and re-evaluates Toss hazards at submit.
- Every submit audit payload records `evidenceMode` and `verificationStatus`.
- Approval surface: the approval preview (`GET /api/v1/paper-orders/{id}/approval-preview`, used by the dashboard
  approval panel for live and paper proposals) includes a read-only `buyEvidence` block. It shows the evidence mode,
  the verification status, the cited sources, and the structured values, labelled as cited and not verified. The
  user approves the displayed quantity, max loss, and currency. Telegram thesis approvals are not affected.

## 5. Toss contract pin (1.2.28)

- The manifest pins every path the client calls (18 entries, including `/api/v1/stocks` and
  `/api/v1/stocks/{symbol}/warnings`). Each path is checked against the 1.2.28 schema.
- The manifest also pins the response schema for each DTO-backed endpoint: prices, order book, candles, exchange rate,
  market calendar (opaque payloads), rankings, buying power, sellable quantity, holdings, accounts, orders, and stocks.
  The test reflects over every parsed record component and asserts that it exists in 1.2.28 with a compatible type.
- Drift found and fixed: `RankingItem.rank` was a string in the client but an integer in 1.2.28.
- Drift tracked, not hidden: 1.2.28 declares `Order.execution` as an object without properties. Its nested fields are
  marked `opaque` in the manifest and are not pinned.
- The dispatch-evidence label is derived from the pinned version (`toss-1.2.28`). Persisted historical labels are
  not rewritten, and no code compares them.

## Regression tests

- Gate, both policies: `STALE`, `SOURCE_CONFLICT`, `UNVERIFIED`, future-dated, and missing-source values block STRATEGIC
  (`storedFundamentalFailuresBlockStrategicUnderBothPolicies`). Under `TOSS_ONLY` the only gap being Toss-unsupported
  yields `REVIEW_REQUIRED` (`tossOnlyStrategicWithUnsupportedFundamentalsNeedsExternalEvidence…`). A mixed unsupported
  and stored-stale row stays `BLOCKED` (`mixedUnsupportedAndStoredStaleValueStaysBlockedUnderTossOnly`).
- Legacy separation: a legacy stale consensus under `TOSS_ONLY` yields `REVIEW_REQUIRED` with `EXTERNAL_CITED`, and the
  same row under `PROVIDERS` is `BLOCKED` (`legacyStaleConsensusUnderTossOnlyNeedsExternalReview…`,
  `InvestmentFundamentalVerificationTest`). Legacy values never count as evidence, and a fresh legacy SEC row never
  becomes available (`freshLegacyFundamentalsNeverBecomeAvailableUnderTossOnly`). Toss-sourced stale consensus still
  blocks (`tossSourcedStaleConsensusStillBlocksUnderTossOnly`).
- Hazards: each hazard row blocks both horizons (`TossStockHazardEvaluatorTest` per row;
  `tossHazardsBlockBothHorizonsWithTheirReasons`). Fetch failure blocks (`tossHazardFetchFailureBlocks…`,
  `stockWarningsFailClosedWhenStockIsNotFound`). Hazards block under `PROVIDERS` too
  (`missingHazardInputBlocksTossOnlyBuyAndFailsClosedUnderProviders`).
- Revalidation cannot start on a `BLOCKED` gate (`externalReviewCannotStartWhenGateIsBlocked…`). It cannot pass after a
  hazard appears (`tossHazardAppearingAfterExternalReviewBlocksSubmit`).
- Each structured-evidence rule has a rejecting test (`eachStructuredEvidenceRuleRejectsTheRequestWithoutStoringAPass`).
  A fully valid payload yields `PASS` with `SOURCE_NOT_VERIFIED` (`tossOnlyExternalReviewStoresStructuredEvidence…`).
  `PROVIDERS` reviews are not marked (`providersReviewIsNotMarkedSourceNotVerified`).
- Submit: notional cap (`externalReviewWithinNotionalCapIsAllowed…`), TTL cap
  (`externalReviewStoredBeforeTtlCapWasLoweredCannotSubmit`), policy flip in both directions
  (`policyFlipToProvidersAfterExternalReviewBlocksSubmitInBothDirections`), hazard after review
  (`tossHazardAppearingAfterExternalReviewBlocksSubmit`), and audit fields (`submitAuditRecordsExternalEvidenceModeOfTheBuyGate`).
- Startup fails for `TOSS_ONLY` with SEC, Alpha Vantage, or FMP enabled, and for an unknown policy value
  (`InvestmentFundamentalSourcePolicyValidatorTest`).
- Readiness and portfolio views: `TOSS_ONLY` readiness shows `tossSourceUnsupported` and legacy flags, and
  `PROVIDERS` shows neither (`InvestmentSourcePolicyViewTest`). Hazard view cache honours its TTL and does not cache
  failures (`portfolioViewHazardsAreCachedForFiveMinutes…`, `unavailableHazardsAreNotCached…`).
- Approval preview: external evidence is labelled cited and not verified; https-only links; no empty rows for
  provider evidence (`order-approval-panel.test.mjs`, `PaperOrderBuyEvidenceTest`).
- Toss 1.2.28 pin: every parsed DTO field exists in the 1.2.28 schema with a compatible type
  (`TossOpenApiVersionTest`).
- Mock stack: each hazard scenario is stubbed and documented, and the scenarios load in WireMock 3.13.2
  (`scripts/test-local-stack.sh`, `mocks/README.md`).

## Out of scope

- Filling fundamentals, consensus, or revisions from any non-Toss provider.
- Verifying the content of cited pages (the server records and labels, it does not verify).
- Changing SELL paths, or relaxing per-order approval or submit-time risk checks.
- Changing schedules, cron jobs, scheduler configuration, or enabled flags in deployed environments.
