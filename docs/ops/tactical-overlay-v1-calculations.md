# Tactical Overlay V1 calculation contract

## Scope and ownership

This document defines deterministic calculations for one ticker at a time. The calculator consumes ordered daily bars and optional, explicitly selected inputs; it does not choose securities, anchors, themes, setups, risk, or trades. It does not call providers or write data.

The integration boundary is:

1. The V55 service persists source bars and user-supplied, effective-dated theme memberships, AVWAP anchors, and optional entry/risk/exit records. The existing investment capture path supplies the bars.
2. `TacticalOverlayCalculator` calculates ticker indicators, breakout/failure events, matured per-breakout cohorts, anchored VWAPs, and optional performance metrics from the supplied data.
3. Context assembles the immutable result with source provenance and effective membership. Theme and `TRACKED_SECURITIES` aggregates use only eligible ticker outputs at a common as-of/base date; membership must be effective on the event date and must not be backfilled from current membership.
4. Sheets project the already-calculated Context values. Sheet formatting and persistence do not recalculate indicators.

The calculator is pure: it does not call providers, select inputs, or write data. Persistence, capture scheduling, Context mapping, and Sheet projection belong to the V55 integration.

## Inputs and data states

- Daily bars are strictly date-ordered and unique. OHLC must be positive and volume nonnegative; low cannot exceed open/close, high cannot be below open/close, and low cannot exceed high. A zero total volume yields a missing VWAP/RVOL denominator. Future bars are excluded at the supplied `asOf` date, and the supplied `asOf` cannot be after the evaluated New York date.
- A source conflict blocks overlay values and stages. Missing bars are `DATA_MISSING`; insufficient warm-up is reported as `INSUFFICIENT_HISTORY`; incomplete but usable source history is `PARTIAL`; disabled/unavailable configuration is `NOT_CONFIGURED`.
- Price adjustment is explicit (`ADJUSTED`, `UNADJUSTED`, or `UNKNOWN`). The calculator never manufactures split adjustments. A known split-conflict date suppresses returns/relative strength and performance metrics whose comparison crosses that date.
- The SPY comparison uses only exact date matches for both stock and SPY endpoints. It never carries a benchmark close forward or substitutes a calendar-day endpoint.
- Daily rolling VWAP is `sum(typicalPrice * volume) / sum(volume)` over the current and prior 19 daily bars, where `typicalPrice = (high + low + close) / 3`. It is labeled `DAILY_ROLLING_20`; it is a daily-bar proxy and must not be described as intraday VWAP.
- Anchored VWAP exists only for a caller-provided `(id, date, anchorType)` whose date matches an input bar. Accepted anchor types are `EARNINGS_GAP`, `BREAKOUT_DAY`, `CATALYST_DAY`, `MAJOR_LOW`, and `POSITION_ENTRY`. Its sum starts on the anchor bar, inclusive, and ends at `asOf`.

## Indicators

- EMA 9, 21, and 50 are seeded once from the simple average of the first respective N complete bars. Later values use `alpha = 2 / (N + 1)` and `EMA = alpha * close + (1 - alpha) * priorEMA`. For each emitted EMA, the result exposes the common seed start date and that EMA's observation count so consumers can see warm-up dependence. EMA50 is unavailable before 50 complete bars.
- RVOL is current volume divided by the arithmetic mean of the immediately preceding 20 bars' volume. The current bar is excluded from its denominator.
- Return 20 and 60 use exactly 20 or 60 prior trading-bar intervals: `currentClose / close[N bars earlier] - 1`.
- Relative strength is the gross-return ratio on the same two exact stock dates: `(stockCurrent / stockBase) / (SPYCurrent / SPYBase) - 1`. Missing aligned endpoints are `INSUFFICIENT_HISTORY`; a split conflict crossing either interval suppresses that result.

## Stages and events

- Stage is null with an explicit `stageStatus` until EMA9, EMA21, EMA50, current close, and prior EMA21 are available. It is also null on missing data, source conflict, or unconfigured input.
- Stage values are exactly `BASE`, `EARLY_UPTREND`, `CONFIRMED_UPTREND`, `EXTENDED`, `DISTRIBUTION`, and `BREAKDOWN`. Precedence is:
  1. `BREAKDOWN`: `close < EMA50 && EMA21 < EMA50`.
  2. `DISTRIBUTION`: `close < EMA21 && EMA21 >= EMA50`.
  3. `EXTENDED`: bullish EMA stack (`EMA9 > EMA21 > EMA50`), with `(close / EMA21 - 1) > extensionThreshold`.
  4. `CONFIRMED_UPTREND`: bullish EMA stack and `close > EMA21`.
  5. `EARLY_UPTREND`: `close > EMA21 && EMA21 >= priorEMA21`.
  6. Otherwise `BASE`.
- Events are exactly `EMA21_RECLAIM`, `EMA50_RECLAIM`, `BREAKOUT`, `FAILED_BREAKOUT`, `HIGH_RVOL_BREAKOUT`, and `TREND_BREAKDOWN`. Reclaims and breakdown require a cross: previous close `<=` previous EMA and current close `>` current EMA for reclaim; previous close `>=` previous EMA50 and current close `<` current EMA50 for breakdown.
- The default breakout lookback is 20 prior bars and default buffer is zero. A breakout event occurs when `close > priorLookbackHigh * (1 + breakoutBufferRatio)`. The current bar is excluded from the prior high. `HIGH_RVOL_BREAKOUT` is emitted alongside `BREAKOUT` when current RVOL is at least 2.0 by default.
- A breakout fails on the first later bar whose close is strictly below its breakout level. That produces one `FAILED_BREAKOUT` event for that breakout. An equal close is not below the level.
- Configurable defaults under `investment.tactical-overlay`: breakout lookback 20, buffer ratio 0, extension threshold 0.10, high-RVOL threshold 2.0, cohort horizons `[3, 5]`, performance horizons `[5, 20]`, and current-mark freshness 15 minutes.

## Breakout cohorts

For each breakout event, each configured horizon counts subsequent available trading bars, excluding the breakout bar. An outcome is not matured until the exact horizon bar is at or before `asOf`. Before maturity, outcome/success/failure/return fields stay unavailable with `INSUFFICIENT_HISTORY`. A horizon that crosses a known split conflict remains matured by date but its outcomes are unavailable with `CORPORATE_ACTION_CONFLICT`. At maturity without a conflict:

- `success` means the horizon close is strictly above the breakout level and no close from the first post-breakout bar through the horizon close fell below that level.
- `failedByHorizon` means any close in that same interval fell below the breakout level, even if price later recovered.
- Cohort return is `horizonClose / breakoutClose - 1`.

Theme and market cohort health are aggregations of eligible ticker results. Their denominator and missing exposure are computed by the service from its explicit eligible universe and effective-dated memberships; this calculator does not assign or reassign membership.

## Theme and market aggregation

- The service supplies one common `asOf` date and one exact common base date shared by the eligible ticker histories and SPY. `ThemeReturn20D` and `ThemeRS20_SPY` use only bars on those two dates; an absent endpoint or a ticker without the matching 20-bar interval is excluded and reported as missing/partial. No close is carried forward. Returns and relative strength are equal-weighted across eligible theme members.
- A conflicting SPY bar at one endpoint suppresses only `ThemeRS20_SPY` with `SOURCE_CONFLICT`; SPY-derived EMAs and returns remain usable, while an OHLC conflict on a ticker's own bars blocks that ticker's overlay.
- `ThemeBreadthEMA21` and `ThemeBreadthEMA50` are the fraction of eligible members whose exact `asOf` close is above the matching EMA. `MedianRVOL` is the ordinary median of eligible members' `asOf` RVOL values; an even-sized set uses the average of its two middle values.
- `BreakoutCount` counts unique `BREAKOUT` events in the last configured number of ticker trading bars (the default is the configured breakout lookback, 20). `HIGH_RVOL_BREAKOUT` is a companion event and does not count as a second breakout. Theme events and cohorts use the membership interval active on each breakout date, including historical mapping intervals.
- A 3D/5D success rate includes only the matching unique breakout cohorts whose exact horizon endpoint is at or before both the common `asOf` date and the supplied maturity cutoff. `FailedBreakoutRate` is failed mature 5D cohorts divided by all eligible mature 5D breakout cohorts. `MedianPostBreakoutReturn5D` uses only eligible mature 5D cohort returns. Immature cohorts remain excluded and are exposed through status/reasons and expected/eligible counts.
- `TRACKED_SECURITIES` aggregates only the explicit configured ticker universe. Each metric returns its value, expected/eligible counts, status, and reasons. Missing members or inputs produce `PARTIAL`/missing status; no missing exposure is converted to a zero observation. Aggregate records preserve contributing membership sources and input references for service provenance.

## Performance

- A performance entry is supplied explicitly with entry date, entry price, stop/risk price, optional entry quantity, and optional explicit exits. Per-share risk is `entryPrice - stopPrice` and must be positive.
- `initialRiskAmount` is `quantity * perShareRisk` only when entry quantity is supplied. It is never fabricated. `initialR` is explicitly the one per-share risk unit (`perShareRisk / perShareRisk = 1`) when a valid explicit entry and risk price are supplied.
- `currentR` is `(verifiedCurrentMark - entryPrice) / perShareRisk`; a mark after evaluation time is invalid, and a mark older than the configured freshness window is `STALE`. It never falls back to the last daily close.
- MAE/MFE are R multiples from completed daily bars strictly after the entry date through `asOf`. The entry-date daily bar is excluded because its high/low may predate an intraday entry. If explicit exits close the full supplied quantity by `asOf`, the excursion window ends strictly before the final exit date; that date's high/low may occur after the exit. This window is labeled `POST_ENTRY_PRE_EXIT_COMPLETED_DAILY_BARS`. Partial exits keep the remaining position window open through `asOf`, labeled `POST_ENTRY_COMPLETED_DAILY_BARS`.
- Realized R is present only when explicit exits exist. It is total realized P&L divided by per-share risk times the explicitly exited quantity; unclosed quantity is not inferred.
- Forward +5/+20 returns use the close of the exact fifth/twentieth trading bar after entry and are emitted only after that horizon has matured at `asOf`.

## Setup, capture, and API use

Flyway V55 creates the append-only overlay input, bar, and result tables. The existing full investment capture path stores TOSS regular-close daily bars for captured securities and SPY; scheduled captures refresh the saved overlay results. The default aggregate universe is `AVT,CSTM,GOOGL,LUNR,RDW,VST`. Override it with the comma-separated `investment.tactical-overlay.tracked-symbols` property. SPY is the benchmark and is removed from that universe.

```yaml
investment:
  tactical-overlay:
    tracked-symbols: AVT,CSTM,GOOGL,LUNR,RDW,VST
```

The capture scheduler processes users with an active broker connection or an eligible monitoring watchlist. Initial bootstrap, after-close, and weekend full captures populate history; quote-only captures do not. `GET /investment/context` and `GET /investment/tactical-overlay/inputs` are read-only: they do not fetch bars or refresh results. A successful input `PUT` appends a versioned input and recalculates from bars already stored; it does not trigger a provider request. For a user with no stored bars, wait for the initial bootstrap or next scheduled full capture. An external-input ticker outside the holdings, watchlist, or configured tracked-symbol universe is not added to scheduled capture automatically; add it through one of those existing paths, with the default six tracked symbols already covered.

Overlay routes under `/investment` use the authenticated session principal:

| Operation | Route | Input |
| --- | --- | --- |
| Read current Context | `GET /investment/context` | None |
| Read overlay input history | `GET /investment/tactical-overlay/inputs` | None |
| Add an anchor | `PUT /investment/securities/{ticker}/avwap-anchors` | `anchorId`, `date`, `anchorType`, `source`, `sourceAsOf` |
| Define a theme | `PUT /investment/tactical-overlay/themes` | `themeId`, `name`, `effectiveDate`, `source`, `sourceAsOf` |
| Add a theme member | `PUT /investment/tactical-overlay/themes/{themeId}/members/{ticker}` | `effectiveDate`, `source`, `sourceAsOf` |
| Add a position or decision entry | `PUT /investment/securities/{ticker}/performance-entries` | `key`, `entryDate`, `entryPrice`, `initialRiskPrice`, `source`, `sourceAsOf`; optional `decisionId`, `quantity`, `entrySetup`, `overlayEffect`, `exits` |

For example, an anchor body is:

```json
{"anchorId":"earnings-2026-q3","date":"2026-07-29","anchorType":"EARNINGS_GAP","source":"USER_INPUT","sourceAsOf":"2026-10-05T00:00:00Z"}
```

A theme definition and member body are:

```json
{"themeId":"quality-compounders","name":"Quality compounders","effectiveDate":"2026-01-01","source":"USER_INPUT","sourceAsOf":"2026-10-05T00:00:00Z"}
```

```json
{"effectiveDate":"2026-01-01","source":"USER_INPUT","sourceAsOf":"2026-10-05T00:00:00Z"}
```

An optional position performance entry is:

```json
{"key":"position-entry","entryDate":"2026-10-01","entryPrice":100.00,"initialRiskPrice":95.00,"quantity":10,"entrySetup":"POSITION_ENTRY","overlayEffect":"NO_EFFECT","exits":[],"source":"USER_INPUT","sourceAsOf":"2026-10-05T00:00:00Z"}
```

A performance entry can link to one existing Decision Ledger row by `decisionId`; the row's asset must match the route ticker. `initialRiskPrice` must be positive and below `entryPrice`. `entrySetup` is caller-provided text. `overlayEffect`, when present, is one of `BETTER_ENTRY`, `AVOIDED_CHASE`, `AVOIDED_FAILED_BREAKOUT`, `PREMATURE_FILTER`, or `NO_EFFECT`. Exit rows contain `date`, `price`, and `quantity`.

`sourceAsOf` is required and cannot be in the future. `source` is a provenance label and defaults to `USER_INPUT`. The anchor type is one of the five values listed above. Theme and anchor dates are effective dates, not capture times. `entryDate` cannot be after the New York date of `sourceAsOf`. Deletes for anchors, themes, members, and performance entries append an inactive version; they do not erase history. Delete routes require `sourceAsOf` as a query parameter: `/investment/securities/{ticker}/avwap-anchors/{anchorId}`, `/investment/tactical-overlay/themes/{themeId}`, `/investment/tactical-overlay/themes/{themeId}/members/{ticker}` (also requires `effectiveDate`), and `/investment/securities/{ticker}/performance-entries/{key}`.

## Context response shape and integrated limits

`ContextView.tacticalOverlay` contains the portfolio overlay (`status`, `asOf`, `source`, `sourceAsOf`, `market`, and `themes`). Each `SecurityView.tacticalOverlay` contains `status`, `reason`, `themeId`, `trendStage`, `entrySetup`, `initialRiskPrice`, `overlayEffect`, `source`, `sourceAsOf`, `asOf`, `overlayVersion`, `indicators`, `events`, `cohorts`, `anchoredVwaps`, and `performance`. Ticker-level entry fields are populated only when there is exactly one active position entry; use the `performance` list for multiple entries. `ContextView.decisionOverlays` is keyed by the exact decision UUID; each value contains that decision's status, entry fields, provenance, version, and performance. Unavailable direct values remain null, and collections may be empty. `NOT_CONFIGURED`, `DATA_MISSING`, `INSUFFICIENT_HISTORY`, `PARTIAL`, and other statuses describe readiness; a null metric is not a zero.

The current persisted capture path supplies TOSS bars with `UNADJUSTED` status and no split-conflict dates. Therefore the calculator's split-conflict suppression is available to callers that supply those inputs, but the current service integration does not provide split metadata; do not treat its current results as split-adjusted. The `DAILY_ROLLING_20` value remains a daily-bar VWAP proxy, never intraday VWAP. Current-mark R uses a fresh TOSS price snapshot and does not fall back to a daily close.
