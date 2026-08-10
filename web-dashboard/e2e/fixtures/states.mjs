// Route-interception state factory for the UI audit.
//
// Every data request in the app goes to /api/v1/** (next.config.js proxies to a
// backend that is NOT running here). We intercept all of them and pin one of six
// states so each screen can be captured under a controlled condition.
//
// Payload shapes are lifted from the existing fixtures in test/*.test.mjs so the
// app receives the exact structures it was written against — anything the app then
// renders wrong (Invalid Date, [object Object], overflow, blank) is a real finding.

const NOW = "2026-08-05T00:00:00Z";
const STALE_AS_OF = "2026-05-01T00:00:00Z"; // deliberately old timestamp for `stale`

// ---------------------------------------------------------------------------
// Canonical "healthy" payloads (from test/*.test.mjs)
// ---------------------------------------------------------------------------

export const SESSION = { userId: "user-1", authenticatedAt: "2026-07-28T00:00:00Z" };

export const RISK_POLICY = {
  version: 0,
  customized: false,
  maxOrderAmountKrw: 10000000,
  maxOrderAmountUsd: 10000,
  maxQuantity: 100,
  maxConcentration: 0.25
};

function section(data, extra = {}) {
  return {
    stale: false,
    unknown: false,
    unknownFields: [],
    unavailable: false,
    unavailableReason: null,
    data,
    ...extra
  };
}

function surface(data = null, status = "AVAILABLE", extra = {}) {
  return {
    status,
    stale: false,
    unknown: false,
    unknownFields: [],
    unavailable: status === "UNAVAILABLE",
    unavailableReason: status === "UNAVAILABLE" ? "PROVIDER_UNSUPPORTED" : null,
    data,
    ...extra
  };
}

export function fullDashboard() {
  return {
    portfolio: section(
      {
        account: {
          displayAccountNumber: "****5678",
          marketValueAmounts: { USD: 120 },
          profitLossAmounts: { USD: 20 },
          cashBalanceStatus: "UNKNOWN"
        },
        positions: [
          {
            symbol: "NVDA",
            name: "NVIDIA",
            quantity: 1,
            currency: "USD",
            marketValueAmount: 120,
            profitLossAmount: 20
          }
        ],
        buyingPower: { KRW: { cashBuyingPower: 1000 } }
      },
      { unknown: true, unknownFields: ["account.cashBalance"] }
    ),
    analysis: section({
      result: {
        currencyTotals: [{ currency: "USD", marketValue: 120, profitLoss: 20 }],
        positions: [{ symbol: "NVDA", currency: "USD", weight: 1 }]
      }
    }),
    pendingEvents: section(null, {
      unavailable: true,
      unavailableReason: "EVENTS_UNAVAILABLE"
    }),
    // Four proposals exercise the widened status vocabulary (D-03) and the
    // createdAt/expiresAt lifecycle (D-42) the backend now serves: a fresh
    // PROPOSED, one about to expire, one already expired (approve must be
    // disabled), and a non-PROPOSED status with null timestamps (legacy row,
    // display-only — approve/cancel are both disabled regardless of expiry).
    pendingOrderProposals: section([
      {
        id: "order-1",
        side: "BUY",
        type: "MARKET",
        symbol: "NVDA",
        quantity: 1,
        limitPrice: null,
        currency: "USD",
        status: "PROPOSED",
        createdAt: "2026-08-05T23:55:00Z",
        expiresAt: "2026-08-06T00:20:00Z"
      },
      {
        id: "order-2",
        side: "SELL",
        type: "LIMIT",
        symbol: "AAPL",
        quantity: 3,
        limitPrice: 210.5,
        currency: "USD",
        status: "PROPOSED",
        createdAt: "2026-08-05T23:46:00Z",
        expiresAt: "2026-08-06T00:01:00Z"
      },
      {
        id: "order-3",
        side: "BUY",
        type: "MARKET",
        symbol: "MSFT",
        quantity: 2,
        limitPrice: null,
        currency: "USD",
        status: "PROPOSED",
        createdAt: "2026-08-05T23:40:00Z",
        expiresAt: "2026-08-05T23:55:00Z"
      },
      {
        id: "order-4",
        side: "BUY",
        type: "MARKET",
        symbol: "GOOGL",
        quantity: 1,
        limitPrice: null,
        currency: "USD",
        status: "MANUAL_REVIEW_REQUIRED",
        createdAt: null,
        expiresAt: null
      }
    ])
  };
}

function emptyDashboard() {
  return {
    portfolio: section({
      account: {
        displayAccountNumber: "****0000",
        marketValueAmounts: {},
        profitLossAmounts: {},
        cashBalanceStatus: "KNOWN"
      },
      positions: [],
      buyingPower: {}
    }),
    analysis: section({ result: { currencyTotals: [], positions: [] } }),
    pendingEvents: section([]),
    pendingOrderProposals: section([])
  };
}

function partialDashboard() {
  const dash = fullDashboard();
  // Only some sections unavailable, the rest still render.
  dash.analysis = section(null, {
    unavailable: true,
    unavailableReason: "ANALYSIS_UNAVAILABLE"
  });
  dash.pendingOrderProposals = section(null, {
    unavailable: true,
    unavailableReason: "ORDER_PROPOSALS_UNAVAILABLE"
  });
  return dash;
}

function staleDashboard() {
  const dash = fullDashboard();
  for (const key of ["portfolio", "analysis", "pendingEvents", "pendingOrderProposals"]) {
    dash[key] = { ...dash[key], stale: true, asOf: STALE_AS_OF };
  }
  return dash;
}

function degradedDashboard() {
  const dash = fullDashboard();
  dash.analysis = section({
    result: {
      ...dash.analysis.data.result,
      status: "DEGRADED",
      missingData: ["provider.partial"]
    }
  }, { unknown: true, unknownFields: ["analysis.provider"] });
  return dash;
}

const EVENTS_FULL = [
  {
    id: "event-1",
    summary: "Rate decision",
    type: "MACRO",
    source: "FED",
    affectedSymbols: ["NVDA", "AAPL"],
    reviewStatus: "HELD",
    reviewVersion: 2,
    comparisonAvailable: true,
    occurredAt: "2026-07-28T00:00:00Z"
  }
];

const EVENT_DETAIL = {
  id: "event-1",
  summary: "Rate decision",
  reviewStatus: "HELD",
  reviewVersion: 2,
  analysisComparison: {
    comparison: {
      baselineAvailable: true,
      positions: [
        {
          symbol: "NVDA",
          currency: "USD",
          beforeMarketValue: 100,
          afterMarketValue: 120,
          marketValueChange: 20,
          beforeProfitLoss: 10,
          afterProfitLoss: 20,
          profitLossChange: 10,
          beforeWeight: 0.5,
          afterWeight: 0.6,
          weightChange: 0.1
        }
      ],
      currencyTotals: [
        {
          currency: "USD",
          beforeMarketValue: 100,
          afterMarketValue: 120,
          marketValueChange: 20,
          beforeProfitLoss: 10,
          afterProfitLoss: 20,
          profitLossChange: 10,
          beforeConcentration: 0.5,
          afterConcentration: 0.6,
          concentrationChange: 0.1
        }
      ]
    }
  }
};

const PORTFOLIO_HISTORY_FULL = section(
  {
    from: "2026-01-01T00:00:00Z",
    to: "2026-01-05T00:00:00Z",
    partial: false,
    totalMatched: 2,
    returnedPoints: 2,
    points: [
      {
        syncRunId: "run-1",
        completedAt: "2026-01-01T00:00:00Z",
        marketValueAmounts: { KRW: 130000, USD: 100 },
        profitLossAmounts: { KRW: 13000, USD: 10 },
        profitLossRate: 0.2,
        dailyProfitLossRate: 0.01
      },
      {
        syncRunId: "run-2",
        completedAt: "2026-01-05T00:00:00Z",
        marketValueAmounts: { KRW: 132000, USD: 110 },
        profitLossAmounts: { KRW: 14000, USD: 11 },
        profitLossRate: 0.21,
        dailyProfitLossRate: 0.015
      }
    ]
  },
  { unknown: true, unknownFields: ["account.cashBalance"] }
);

const PORTFOLIO_HISTORY_EMPTY = section(null, {
  unavailable: true,
  unavailableReason: "PORTFOLIO_HISTORY_NOT_FOUND"
});

const ANALYSIS_PREDICTIONS_FULL = {
  predictions: [
    {
      id: "pred-1",
      predictedAt: "2026-01-01T00:00:00Z",
      symbol: "AAPL",
      currency: "USD",
      predictedDirection: "UP",
      modelVersion: "v1",
      contractVersion: "1",
      baselinePrice: 100,
      outcomes: {
        D1: {
          price: 110,
          actualReturn: 0.1,
          directionCorrect: true,
          observedAt: "2026-01-02T00:00:00Z"
        }
      }
    }
  ],
  byVersion: [
    {
      modelVersion: "v1",
      contractVersion: "1",
      horizon: "D1",
      sampleCount: 1,
      hitRate: 1,
      avgDirectionalReturn: 0.1,
      avgMaxAdverseExcursion: 0
    }
  ],
  forecastQuality: {
    minimumSampleCount: 10,
    rows: [
      {
        symbol: "AAPL",
        modelVersion: "v1",
        contractVersion: "1",
        horizon: "D1",
        status: "DATA_SHORTAGE",
        sampleCount: 1,
        minimumSampleCount: 10,
        pendingCount: 0,
        hitRate: null,
        calibrationError: null,
        brierScore: null,
        drift: { status: "DATA_SHORTAGE", degraded: false }
      }
    ]
  }
};

const ANALYSIS_PREDICTIONS_EMPTY = { predictions: [], byVersion: [] };

const MODEL_VERSIONS_FULL = [
  {
    id: "active-1",
    modelVersion: "model-v1",
    contractVersion: "contract-v1",
    status: "ACTIVE",
    createdAt: "2026-01-01T00:00:00Z",
    deprecatedAt: null
  },
  {
    id: "deprecated-1",
    modelVersion: "old",
    contractVersion: "v1",
    status: "DEPRECATED",
    createdAt: "2025-01-01T00:00:00Z",
    deprecatedAt: "2026-01-01T00:00:00Z"
  }
];

const PREDICTION_KEYS_FULL = [
  {
    id: "active-key",
    modelVersion: "model-v1",
    contractVersion: "contract-v1",
    prefix: "tpik_12345678",
    status: "ACTIVE",
    createdAt: "2026-07-31T00:00:00Z",
    lastUsedAt: null,
    revokedAt: null,
    expiresAt: "2099-01-01T00:00:00Z"
  }
];

const PREDICTION_OPERATIONS_FULL = {
  evaluationEnabled: true,
  backlog: 2,
  maxLagMs: 3723000,
  longUngradedCount: 1,
  oldestLongUngradedDueAt: "2026-07-29T00:00:00Z",
  measuredAt: "2026-07-31T00:00:00Z"
};

const READINESS_FULL = {
  status: "DEGRADED",
  canary: { status: "DISABLED" },
  killSwitch: { status: "NOT_REQUIRED" },
  dataFreshness: { status: "STALE", maxLagMs: 301000 },
  alerts: ["PROVIDER_FMP_STALE"],
  providers: [
    {
      provider: "FMP",
      status: "STALE",
      credentialConfigured: true,
      lagMs: 301000,
      missingData: []
    }
  ]
};

const READINESS_EMPTY = {
  status: "READY",
  canary: { status: "DISABLED" },
  killSwitch: { status: "NOT_REQUIRED" },
  dataFreshness: { status: "FRESH", maxLagMs: 0 },
  alerts: [],
  providers: []
};

const NOTIFICATIONS_FULL = [
  {
    id: "notification-1",
    type: "ORDER_RESULT",
    title: "Order COMPLETED",
    body: "COMPLETED order for NVDA: no additional reason",
    createdAt: "2026-07-28T00:00:00Z",
    readAt: null
  }
];

const STOCK_ANALYSIS_FULL = {
  runId: "run-1",
  inputSnapshotId: "snapshot-1",
  symbol: "AAPL",
  completedAt: "2026-08-03T00:00:00Z",
  result: {
    status: "DEGRADED",
    asOf: "2026-08-02T00:00:00Z",
    missingData: ["marketRegime:FIELD_MISSING:macro.vix"],
    observations: [
      {
        provider: "FMP",
        field: "quote.price",
        asOf: "2026-08-02T00:00:00Z",
        collectedAt: "2026-08-03T00:00:00Z",
        value: "200",
        unit: "USD"
      }
    ],
    analyzers: [
      {
        analyzer: "valuation",
        confidence: "0.5",
        missingData: [],
        metrics: [
          {
            name: "valuation.pe",
            value: "20",
            unit: "multiple",
            asOf: "2026-08-02T00:00:00Z",
            missingData: [],
            provenance: [
              {
                provider: "FMP",
                field: "fundamental.eps",
                asOf: "2026-08-02T00:00:00Z",
                collectedAt: "2026-08-03T00:00:00Z"
              }
            ]
          }
        ]
      }
    ]
  }
};

const STOCK_FORECAST_FULL = {
  runId: "run-1",
  result: { status: "COMPLETED", confidence: "0.8", forecasts: [] }
};

const STOCK_EXPLANATION_FULL = {
  status: "DEGRADED",
  missingData: ["GEMINI_UPSTREAM_ERROR"],
  citations: [{ id: "citation-1", provider: "FMP", field: "quote.price" }],
  explanation: {
    evidence: [{ text: "Grounded evidence", citationIds: ["citation-1"] }]
  }
};

const STOCK_HISTORY_FULL = [STOCK_ANALYSIS_FULL];

// ---------------------------------------------------------------------------
// Endpoint router: pick the healthy payload for a given URL + method.
// ---------------------------------------------------------------------------

function matchEndpoint(pathname, method) {
  const is = (re) => re.test(pathname);

  if (is(/\/api\/v1\/session$/)) return { body: SESSION };
  if (is(/\/risk-policy\/history/)) return { body: [] };
  if (is(/\/risk-policy$/)) {
    return method === "PUT"
      ? { body: { ...RISK_POLICY, version: 1, customized: true } }
      : { body: RISK_POLICY };
  }
  if (is(/\/dashboard$/)) return { body: fullDashboard(), kind: "dashboard" };
  if (is(/\/portfolio-history/)) return { body: PORTFOLIO_HISTORY_FULL, kind: "portfolio-history" };
  if (is(/\/paper-performance/)) {
    return { body: section({ byCurrency: {} }), kind: "paper-performance" };
  }
  if (is(/\/analysis-predictions/)) {
    return { body: ANALYSIS_PREDICTIONS_FULL, kind: "analysis-predictions" };
  }
  if (is(/\/prediction-model-versions/)) return { body: MODEL_VERSIONS_FULL, kind: "list" };
  if (is(/\/prediction-ingestion-api-keys/)) return { body: PREDICTION_KEYS_FULL, kind: "list" };
  if (is(/\/prediction-operations/)) return { body: PREDICTION_OPERATIONS_FULL };
  if (is(/\/operations\/readiness\/provider-check/)) return { body: READINESS_FULL };
  if (is(/\/operations\/readiness/)) return { body: READINESS_FULL, kind: "readiness" };
  if (is(/\/notifications\/unread-count/)) return { body: { count: 1 } };
  if (is(/\/notifications/)) return { body: NOTIFICATIONS_FULL, kind: "list" };

  // Events (list + detail + review/reanalyze).
  if (is(/\/events\/[^/]+\/review$/)) return { body: EVENT_DETAIL };
  if (is(/\/events\/[^/]+\/reanalyze$/)) return { body: EVENT_DETAIL };
  if (is(/\/events\/[^/]+$/)) return { body: EVENT_DETAIL };
  if (is(/\/events$/)) return { body: EVENTS_FULL, kind: "events" };

  // Stock product surface.
  if (is(/\/stock-analyses\/[^/]+\/history/)) return { body: STOCK_HISTORY_FULL, kind: "list" };
  if (is(/\/stock-analyses\/[^/]+\/runs\//)) return { body: STOCK_ANALYSIS_FULL };
  if (is(/\/stock-analyses\/[^/]+$/)) return { body: STOCK_ANALYSIS_FULL, kind: "stock-analysis" };
  if (is(/\/stock-forecasts\/[^/]+/)) return { body: STOCK_FORECAST_FULL };
  if (is(/\/stock-analysis-explanations\/[^/]+/)) return { body: STOCK_EXPLANATION_FULL };

  // Provider-backed market surfaces. Unsupported endpoints are explicit envelopes.
  if (is(/\/buying-power$/)) {
    return { body: surface({ USD: { cashBuyingPower: 1000 } }), kind: "surface" };
  }
  if (is(/\/prices$/)) {
    return {
      body: surface([{ symbol: "AAPL", lastPrice: 210, currency: "USD" }], "DEGRADED", {
        unknown: true, unknownFields: ["AAPL.bidPrice", "AAPL.askPrice"], unavailableReason: "PRICE_PARTIAL"
      }), kind: "surface"
    };
  }
  if (is(/\/(orderbook|candles|exchange-rate|market-calendar|rankings|commissions)(\/|$)/)
      || is(/\/stocks\/[^/]+\/(warnings|investor-trading)$/)) {
    return { body: surface(null, "UNAVAILABLE"), kind: "surface" };
  }

  // Broker command endpoints (verify/sync/analysis/connection lifecycle).
  if (is(/\/broker-connections\/[^/]+\/verify/)) return { body: { id: "conn-1", status: "ACTIVE" } };
  if (is(/\/broker-connections\/toss/)) return { body: { id: "conn-1", status: "ACTIVE" } };
  if (is(/\/paper-orders\//)) return { body: { status: "COMPLETED" }, kind: "order" };

  throw new Error(`Unregistered frontend API ${method} ${pathname}`);
}

// State-specific transforms applied to the healthy payload.
function shapeForState(match, state) {
  const { body, kind } = match;
  if (state === "empty") {
    switch (kind) {
      case "dashboard": return emptyDashboard();
      case "portfolio-history": return PORTFOLIO_HISTORY_EMPTY;
      case "paper-performance": return section({ byCurrency: {} });
      case "analysis-predictions": return ANALYSIS_PREDICTIONS_EMPTY;
      case "readiness": return READINESS_EMPTY;
      case "events": return [];
      case "list": return [];
      case "stock-analysis": return { symbol: "AAPL", runId: null, result: null };
      case "surface": return body.status === "UNAVAILABLE" ? body : { ...body, data: null };
      default: return Array.isArray(body) ? [] : body;
    }
  }
  if (state === "partial") {
    switch (kind) {
      case "dashboard": return partialDashboard();
      case "portfolio-history": return PORTFOLIO_HISTORY_EMPTY;
      case "analysis-predictions":
        return { ...ANALYSIS_PREDICTIONS_FULL, forecastQuality: null };
      case "surface": return body.status === "UNAVAILABLE"
        ? body
        : { ...body, status: "DEGRADED", unknown: true, unknownFields: ["provider.partial"] };
      default: return body;
    }
  }
  if (state === "stale") {
    switch (kind) {
      case "dashboard": return staleDashboard();
      case "portfolio-history":
        return { ...PORTFOLIO_HISTORY_FULL, stale: true, asOf: STALE_AS_OF };
      case "paper-performance":
        return { ...section({ byCurrency: {} }), stale: true, asOf: STALE_AS_OF };
      case "stock-analysis":
        return {
          ...STOCK_ANALYSIS_FULL,
          result: { ...STOCK_ANALYSIS_FULL.result, stale: true, asOf: STALE_AS_OF }
        };
      case "surface": return body.status === "UNAVAILABLE"
        ? body
        : { ...body, status: "DEGRADED", stale: true, unavailableReason: "STALE_PROVIDER_DATA" };
      default: return body;
    }
  }
  if (state === "degraded") {
    switch (kind) {
      case "dashboard": return degradedDashboard();
      case "portfolio-history":
        return { ...PORTFOLIO_HISTORY_FULL, unknown: true, unknownFields: ["account.cashBalance"] };
      case "analysis-predictions":
        return { ...ANALYSIS_PREDICTIONS_FULL, forecastQuality: null };
      case "readiness": return READINESS_FULL;
      case "stock-analysis": return STOCK_ANALYSIS_FULL;
      case "surface": return body.status === "UNAVAILABLE"
        ? body
        : { ...body, status: "DEGRADED", unknown: true, unknownFields: ["provider.partial"] };
      default: return body;
    }
  }
  return body;
}

export function jsonResponse(route, status, body) {
  return route.fulfill({
    status,
    contentType: "application/json",
    headers: { "cache-control": "no-store" },
    body: JSON.stringify(body)
  });
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Build a Playwright route handler that pins every /api/v1/** request to `state`.
 * @param {"loading"|"refreshing"|"empty"|"partial"|"degraded"|"stale"|"error"|"unauthorized"|"unsupported"} state
 * @param {{ delayMs?: number, onRequest?: (info) => void }} [opts]
 */
export function stateRoute(state, opts = {}) {
  const delayMs = opts.delayMs ?? 3000;
  const seen = new Map();
  return async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    if (opts.onRequest) {
      opts.onRequest({ url: url.pathname, method, state });
    }

    if (state === "unauthorized") {
      // Session 401 => app treats the user as signed out; every other call 401s too.
      return jsonResponse(route, 401, { code: "UNAUTHORIZED" });
    }

    if (state === "error") {
      // Session must still succeed so the authenticated shell renders and its
      // data requests are the ones that surface the error — otherwise every
      // route collapses to the login screen and nothing about error handling
      // inside the app is exercised.
      if (/\/api\/v1\/session$/.test(url.pathname)) {
        return jsonResponse(route, 200, SESSION);
      }
      return jsonResponse(route, 500, {
        code: "INTERNAL_ERROR",
        message: "Something went wrong. Please try again."
      });
    }

    const match = matchEndpoint(url.pathname, method);

    if (state === "loading") {
      // Hold the response open so the screenshot captures the pending UI.
      // Session resolves quickly so the shell mounts; data requests hang.
      if (/\/api\/v1\/session$/.test(url.pathname)) {
        return jsonResponse(route, 200, SESSION);
      }
      await delay(delayMs);
      return jsonResponse(route, 200, shapeForState(match, "loading"));
    }

    if (state === "refreshing") {
      const key = `${method} ${url.pathname}`;
      const count = seen.get(key) ?? 0;
      seen.set(key, count + 1);
      if (count > 0) {
        await delay(delayMs);
      }
      return jsonResponse(route, 200, shapeForState(match, "degraded"));
    }

    return jsonResponse(route, 200, shapeForState(match, state));
  };
}

export const STATES = [
  "loading",
  "refreshing",
  "empty",
  "partial",
  "degraded",
  "stale",
  "error",
  "unauthorized"
];

export function routeStates(routeDef) {
  return [...STATES, ...(routeDef.extraStates ?? [])];
}

export const ROUTES = [
  { path: "/", name: "home" },
  { path: "/login", name: "login" },
  { path: "/portfolio", name: "portfolio" },
  { path: "/orders", name: "orders" },
  { path: "/events", name: "events" },
  { path: "/predictions", name: "predictions" },
  { path: "/settings", name: "settings" },
  { path: "/stocks/AAPL", name: "stocks-AAPL", extraStates: ["unsupported"] }
];

export const CONNECTION_ID = "audit-connection";

// Frozen wall-clock for visual-regression determinism. Relative-time output
// (formatFreshness / formatRelativeTime) and `new Date()` in the app read the
// page clock; pinning it removes the only source of run-to-run pixel drift.
export const FROZEN_NOW_ISO = "2026-08-06T00:00:00Z";

/**
 * Pin the page's wall clock so date-dependent rendering is deterministic.
 * Only the no-argument Date and Date.now() are frozen; explicit timestamps
 * (the fixture data) still parse normally, and Playwright's own timers are
 * untouched because they run in Node, not in the page.
 */
export async function freezeClock(context, iso = FROZEN_NOW_ISO) {
  await context.addInitScript(fixed => {
    const OriginalDate = Date;
    const fixedTime = OriginalDate.parse(fixed);
    class FrozenDate extends OriginalDate {
      constructor(...args) {
        if (args.length === 0) {
          super(fixedTime);
        } else {
          super(...args);
        }
      }
      static now() {
        return fixedTime;
      }
    }
    // eslint-disable-next-line no-global-assign
    Date = FrozenDate;
  }, iso);
}

/**
 * Seed the memory access token (via URL hash) and a saved connection id so the
 * route workspaces auto-open instead of sitting on the connect form.
 * `/login` intentionally gets no token: it is a server-rendered public page.
 */
export async function primeAuth(context, { withConnection = true } = {}) {
  await context.addInitScript(
    ([connectionId, withConn]) => {
      try {
        if (withConn) {
          window.localStorage.setItem("trade.connectionId", connectionId);
        }
      } catch {
        // localStorage can be unavailable; ignore.
      }
    },
    [CONNECTION_ID, withConnection]
  );
}
