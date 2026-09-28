# Investment OS continuous monitoring delta

## Existing boundaries

Spring owns account snapshots, risk policy, event ingestion, notification outbox and PostgreSQL. FastAPI owns calculations. Monitoring is read only: it must never call order prepare, submit or cancel, nor call an LLM on a schedule.

## Delta

- A scheduled, opt-in monitoring run reads the latest owned portfolio state and configured market/event providers. Intraday price work pauses outside US regular market hours; lower-frequency series run only when due. Missing or stale inputs stay `UNKNOWN` with source and observation time.
- FastAPI calculates four separate contagion axes (`EQUITY`, `CREDIT`, `FUNDING`, `FX`) using 5/10/20 trading-day changes and simultaneous confirmation. Vulnerability alone never raises a crash signal. Market stages: `NORMAL`, `EARLY_WARNING`, `RISK_TRANSITION`, `STRONG_RISK_OFF`, `P0_SYSTEMIC`. An institutional/fund/market-function incident plus forced deleveraging is required for P0. A 10Y nominal yield of 5.3–5.5% is an observation, not a sell rule; distinguish real yield, breakeven, and term premium when available.
- Position states: `NORMAL`, `ATTENTION`, `REDUCE_CANDIDATE`, `THESIS_INVALIDATED`, `P0`. Use quantity, average cost, value, weight, sector/factor, beta/correlation, thesis and explicit add/reduce/exit/invalidation conditions when present. Apply existing Risk Policy and combined exposure. Price alone never invalidates a thesis.
- Official SEC/IR/exchange/government events for holdings and watchlist may set `THESIS_RECHECK_REQUIRED`. Material kinds include filings, guidance, earnings, dilution, contracts, M&A, regulation, officers, ratings, default and project delay. News, rumours and analyst commentary do not alert.
- Watchlist is DB based. Price, volume and relative strength jointly gate `WATCH` → `PREPARE` → `ACTION_CANDIDATE`; explicit invalidation yields `INVALIDATED`. All transitions have timestamp and evidence.
- PostgreSQL records current state, append-only transitions, watchlist definitions, material event IDs and notification delivery attempts. Emit only rising risk, thesis invalidation, material events, watchlist transitions and policy breaches. Deduplicate by signal and source event; healthy runs are silent. Telegram is the preferred configured delivery channel.
- Market inputs and official event feeds sit behind adapters so local fake data can drive deterministic tests. No Kafka, new service or recurring LLM calls.

## Safety and acceptance

UNKNOWN never becomes zero. Missing evidence cannot create `NORMAL` confidence or P0. Data/source timestamps persist with every change. Consecutive identical runs emit no alert. Restart preserves state and deduplication. Fake market, portfolio, event and watchlist inputs exercise all stage transitions; no order code is reachable. Existing FastAPI and Spring test suites remain green.
