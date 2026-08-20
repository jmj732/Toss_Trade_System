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

// Decision-surface fixtures are anchored to the *frozen* page clock, not to NOW.
// freezeClock pins Date.now() to FROZEN_NOW_ISO (2026-08-06T00:00:00Z) and
// lib/surface-state.js uses URGENT_EXPIRY_WINDOW_MS = 900000 (15 min), so the
// PROPOSED URGENT/HIGH boundary sits at frozen-now + 15 min. Both edges are
// hardcoded here (and asserted against FROZEN_NOW_ISO in states.test.mjs) so a
// change to either constant breaks the fixture instead of silently reclassifying
// an order's priority.
const DECISION_NOW_ISO = "2026-08-06T00:00:00Z"; // must equal FROZEN_NOW_ISO
const DECISION_URGENT_EDGE_ISO = "2026-08-06T00:15:00Z"; // frozen now + 900000ms
// Well past the boundary => PROPOSED stays HIGH (never URGENT).
const DECISION_FAR_EXPIRY_ISO = "2026-08-06T08:00:00Z";

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

export const CONNECTIONS_FULL = [
  {
    id: "audit-connection",
    brokerType: "TOSS_INVEST",
    status: "ACTIVE",
    credentialRevision: 1,
    lastValidatedAt: NOW
  }
];

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
    provenance: status === "UNAVAILABLE" ? [] : [{
      provider: "TOSS", endpoint: "/api/v1/market-data", currency: "USD",
      asOf: NOW, observedAt: NOW
    }],
    data,
    ...extra
  };
}

// BC-4/BC-2: the dashboard read model gained two top-level sections. Both use the
// same Section<T> envelope as the others; only `data` differs. The app must work
// without them (they are optional), but the code paths that *read* them have to be
// covered too, so every healthy dashboard fixture carries them.
function riskEvaluationSection({ breached = false } = {}) {
  return section({
    policyVersion: 1,
    evaluatedAt: NOW,
    items: [
      {
        key: "CURRENCY_CONCENTRATION:USD",
        scope: "CURRENCY",
        subject: "USD",
        current: 0.18,
        limit: 0.25,
        usageRatio: 0.72,
        breached: false
      },
      {
        key: "POSITION_CONCENTRATION:NVDA",
        scope: "POSITION",
        subject: "NVDA",
        current: breached ? 0.41 : 0.12,
        limit: 0.25,
        usageRatio: breached ? 1.64 : 0.48,
        breached
      }
    ]
  });
}

// One row per held position. NVDA carries a full decision, and the two null causes
// stay distinct: MRVL was analysed but had insufficient metrics (decisionRunId set,
// decision null), AMD was never analysed (decisionRunId null).
function positionDecisionsSection() {
  return section([
    {
      symbol: "NVDA",
      riskLevel: "LOW",
      decision: "HOLD",
      confidence: 0.72,
      decisionRuleVersion: "decision-rule-v1",
      decisionAsOf: NOW,
      decisionRunId: "decision-run-1"
    },
    {
      symbol: "MRVL",
      riskLevel: null,
      decision: null,
      confidence: null,
      decisionRuleVersion: null,
      decisionAsOf: null,
      decisionRunId: "decision-run-2"
    },
    {
      symbol: "AMD",
      riskLevel: null,
      decision: null,
      confidence: null,
      decisionRuleVersion: null,
      decisionAsOf: null,
      decisionRunId: null
    }
  ]);
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
    //
    // executionMode is mandatory here. P2 splits the orders queue by execution
    // context and quarantines rows whose context is unknown, so a proposal without
    // executionMode lands in the disabled 구분 미확인 group. Omitting it emptied the
    // Paper queue on every orders screen in the matrix and disabled the approve
    // button the orders journey depends on. The unknown-context path is covered
    // deliberately in orders-execution-context.spec.mjs instead of by accident here.
    pendingOrderProposals: section([
      {
        id: "order-1",
        executionMode: "PAPER",
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
        executionMode: "PAPER",
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
        executionMode: "PAPER",
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
        executionMode: "LIVE",
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
    ]),
    riskEvaluation: riskEvaluationSection(),
    positionDecisions: positionDecisionsSection()
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
    pendingOrderProposals: section([]),
    // Empty means "no rows", not "section missing" — the envelope still arrives.
    riskEvaluation: section({ policyVersion: 1, evaluatedAt: NOW, items: [] }),
    positionDecisions: section([])
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
  // positionDecisions unavailable => the Risk/판단 columns must disappear entirely
  // rather than render empty cells, and riskEvaluation must name what it could not
  // evaluate instead of dropping it silently.
  dash.positionDecisions = section(null, {
    unavailable: true,
    unavailableReason: "POSITION_DECISIONS_UNAVAILABLE"
  });
  dash.riskEvaluation = {
    ...riskEvaluationSection(),
    unknown: true,
    unknownFields: ["positions[NVDA].weight"]
  };
  return dash;
}

function staleDashboard() {
  const dash = fullDashboard();
  for (const key of [
    "portfolio", "analysis", "pendingEvents", "pendingOrderProposals",
    "riskEvaluation", "positionDecisions"
  ]) {
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

// ---------------------------------------------------------------------------
// Home decision-surface states (extraStates on the home route only)
//
// These pin resolveSurfaceState() rather than data quality. Everything that is
// NOT the state under test is held at a healthy, action-free baseline so exactly
// one rule fires and the resulting surface state is unambiguous.
// ---------------------------------------------------------------------------

// Zero-Action baseline. Every DATA_QUALITY rule in lib/action-model.js is held off:
//   portfolio.stale !== true, portfolio.data.partial !== true,
//   account.cashBalanceStatus !== "UNKNOWN", analysis result status !== "DEGRADED"
//   and result.quality.partial !== true.
// Proposals and pending events are both empty, so buildActions() returns [].
function decisionBaseDashboard() {
  return {
    portfolio: section({
      account: {
        displayAccountNumber: "****5678",
        marketValueAmounts: { USD: 1240 },
        dailyProfitLossAmounts: { USD: 12 },
        profitLossAmounts: { USD: 96 },
        // KNOWN (never "UNKNOWN") — UNKNOWN would add a DATA_QUALITY action.
        cashBalanceStatus: "KNOWN"
      },
      positions: [
        { symbol: "NVDA", name: "NVIDIA", quantity: 2, currency: "USD", lastPrice: 120, marketValueAmount: 240, profitLossAmount: 20 },
        { symbol: "MRVL", name: "Marvell", quantity: 3, currency: "USD", lastPrice: 80, marketValueAmount: 240, profitLossAmount: -5 },
        { symbol: "AMD", name: "AMD", quantity: 5, currency: "USD", lastPrice: 152, marketValueAmount: 760, profitLossAmount: 81 }
      ],
      buyingPower: { KRW: { cashBuyingPower: 1350000 }, USD: { cashBuyingPower: 1000 } },
      completedAt: NOW,
      partial: false
    }),
    analysis: section({
      result: {
        // Deliberately NOT "DEGRADED" and without quality.partial.
        status: "COMPLETED",
        currencyTotals: [{ currency: "USD", marketValue: 1240, profitLoss: 96 }],
        positions: [
          { symbol: "NVDA", currency: "USD", weight: 0.19 },
          { symbol: "MRVL", currency: "USD", weight: 0.19 },
          { symbol: "AMD", currency: "USD", weight: 0.62 }
        ]
      }
    }),
    // Empty list, not unavailable: "no event awaiting review" must be a real answer.
    pendingEvents: section([]),
    pendingOrderProposals: section([]),
    riskEvaluation: riskEvaluationSection(),
    positionDecisions: positionDecisionsSection()
  };
}

// CRITICAL: one MANUAL_REVIEW_REQUIRED proposal. ORDER_URGENT_STATUSES makes it
// URGENT regardless of expiry, so urgentCount === 1 and no other rule can win.
function decisionCriticalDashboard() {
  const dash = decisionBaseDashboard();
  dash.pendingOrderProposals = section([
    {
      id: "order-critical-1",
      side: "BUY",
      type: "MARKET",
      symbol: "NVDA",
      quantity: 4,
      limitPrice: null,
      currency: "USD",
      executionMode: "PAPER",
      status: "MANUAL_REVIEW_REQUIRED",
      createdAt: "2026-08-05T23:30:00Z",
      expiresAt: null
    }
  ]);
  return dash;
}

// RISK: no proposals at all (urgentCount 0, actionCount 0) and one breached risk
// item, so riskBreached is the only rule that can fire.
function decisionRiskDashboard() {
  const dash = decisionBaseDashboard();
  dash.riskEvaluation = riskEvaluationSection({ breached: true });
  return dash;
}

// ACTIVE: one PROPOSED proposal whose expiresAt is far beyond frozen-now + 15min,
// so action-model classifies it HIGH (not URGENT). No breach, so ACTIVE wins.
function decisionActiveDashboard() {
  const dash = decisionBaseDashboard();
  dash.pendingOrderProposals = section([
    {
      id: "order-active-1",
      side: "BUY",
      type: "LIMIT",
      symbol: "AMD",
      quantity: 2,
      limitPrice: 150.25,
      currency: "USD",
      executionMode: "PAPER",
      status: "PROPOSED",
      createdAt: DECISION_NOW_ISO,
      // DECISION_FAR_EXPIRY_ISO - frozen now = 8h > URGENT_EXPIRY_WINDOW_MS.
      expiresAt: DECISION_FAR_EXPIRY_ISO
    }
  ]);
  return dash;
}

// BLOCKED: the portfolio section itself is unavailable. isBroken() only exempts
// ANALYSIS_RESULT_NOT_FOUND, so PORTFOLIO_UNAVAILABLE is real data breakage.
function decisionBlockedDashboard() {
  const dash = decisionBaseDashboard();
  dash.portfolio = section(null, {
    unavailable: true,
    unavailableReason: "PORTFOLIO_UNAVAILABLE"
  });
  return dash;
}

// Surface state each fixture must resolve to. The specs assert against this map so
// the fixture and its expectation can never drift apart.
export const DECISION_STATE_SURFACES = {
  "decision-critical": "CRITICAL",
  "decision-risk": "RISK",
  "decision-active": "ACTIVE",
  "decision-calm": "CALM",
  "decision-blocked": "BLOCKED"
};

export const DECISION_STATES = Object.keys(DECISION_STATE_SURFACES);

const DECISION_DASHBOARDS = {
  "decision-critical": decisionCriticalDashboard,
  "decision-risk": decisionRiskDashboard,
  "decision-active": decisionActiveDashboard,
  "decision-calm": decisionBaseDashboard,
  "decision-blocked": decisionBlockedDashboard
};

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

// BC-6 preview response: server-computed pre-trade risk figures. The front end must
// not recompute any of these.
const PAPER_ORDER_PREVIEW = {
  symbol: "AAPL",
  side: "BUY",
  type: "MARKET",
  quantity: 1,
  currency: "USD",
  estimatedAmount: 210,
  maxLoss: 210,
  decision: "ALLOW",
  violations: []
};

// The figures the approval panel shows and then echoes back on approve (D-01).
const APPROVAL_PREVIEW = {
  displayedQuantity: 1,
  displayedMaxLoss: 100,
  displayedCurrency: "USD",
  proposalVersion: null
};

// BC-7: engaged === false is the only "trading allowed" answer. engaged === null
// means "not configured" and must never be folded into false.
const KILL_SWITCH_RELEASED = {
  scope: "USER",
  targetId: null,
  engaged: false,
  reason: null,
  changedAt: "2026-08-01T00:00:00Z"
};

export const KILL_SWITCH_ENGAGED = {
  scope: "USER",
  targetId: null,
  engaged: true,
  reason: "위험 한도 초과로 수동 정지",
  changedAt: "2026-08-05T12:00:00Z"
};

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
  if (is(/\/broker-connections$/) && method === "GET") {
    return { body: CONNECTIONS_FULL, kind: "connections" };
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

  // Provider-backed market surfaces. Payloads mirror the live Toss contract.
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
  if (is(/\/orderbook$/)) {
    return { body: surface({
      symbol: "AAPL", timestamp: NOW, currency: "USD",
      asks: [{ price: 210.1, volume: 120 }, { price: 210.2, volume: 85 }],
      bids: [{ price: 209.9, volume: 140 }, { price: 209.8, volume: 90 }]
    }), kind: "surface" };
  }
  if (is(/\/candles$/)) {
    return { body: surface({
      symbol: "AAPL", interval: "1d", adjusted: true, nextBefore: "2026-08-01T00:00:00Z",
      candles: [
        { timestamp: NOW, openPrice: 208, highPrice: 212, lowPrice: 207, closePrice: 210, volume: 1200000, currency: "USD" },
        { timestamp: "2026-08-04T00:00:00Z", openPrice: 205, highPrice: 209, lowPrice: 204, closePrice: 208, volume: 1100000, currency: "USD" }
      ]
    }), kind: "surface" };
  }
  if (is(/\/exchange-rate$/)) {
    return { body: surface({
      baseCurrency: "USD", quoteCurrency: "KRW", rate: 1380.25, midRate: 1380.1,
      basisPoint: 1.5, rateChangeType: "UP", validFrom: NOW, validUntil: "2026-08-05T00:01:00Z"
    }), kind: "surface" };
  }
  if (is(/\/market-calendar\/[^/]+$/)) {
    return { body: surface({
      market: "US", payload: {
        previousBusinessDay: { date: "2026-08-04", regularMarket: { open: "2026-08-04T22:30:00+09:00", close: "2026-08-05T05:00:00+09:00" } },
        today: { date: "2026-08-05", regularMarket: { open: "2026-08-05T22:30:00+09:00", close: "2026-08-06T05:00:00+09:00" } },
        nextBusinessDay: { date: "2026-08-06", regularMarket: { open: "2026-08-06T22:30:00+09:00", close: "2026-08-07T05:00:00+09:00" } }
      }
    }), kind: "surface" };
  }
  if (is(/\/rankings$/)) {
    return { body: surface({
      rankedAt: NOW,
      rankings: [
        { rank: 1, symbol: "AAPL", price: { lastPrice: 210, basePrice: 208, changeRate: 0.0096 }, tradingVolume: 1200000, tradingAmount: 252000000, currency: "USD" },
        { rank: 2, symbol: "NVDA", price: { lastPrice: 125, basePrice: 123, changeRate: 0.0162 }, tradingVolume: 980000, tradingAmount: 122500000, currency: "USD" }
      ]
    }), kind: "surface" };
  }
  if (is(/\/commissions$/)
      || is(/\/stocks\/[^/]+\/(warnings|investor-trading)$/)) {
    return { body: surface(null, "UNAVAILABLE"), kind: "surface" };
  }

  // Broker command endpoints (verify/sync/analysis/connection lifecycle).
  if (is(/\/broker-connections\/[^/]+\/verify/)) return { body: { id: "conn-1", status: "ACTIVE" } };
  if (is(/\/broker-connections\/toss/)) return { body: { id: "conn-1", status: "ACTIVE" } };
  // BC-6: non-persisting pre-trade risk preview. Must be matched BEFORE the generic
  // /paper-orders/<id>/<action> rule or it collapses into the order-command shape.
  if (is(/\/paper-orders\/preview$/) && method === "POST") {
    return { body: PAPER_ORDER_PREVIEW, kind: "paper-order-preview" };
  }
  if (is(/\/paper-orders$/) && method === "POST") {
    return { body: { id: "order-created", status: "PROPOSED", side: "BUY", type: "MARKET", symbol: "AAPL", quantity: 1, limitPrice: null, currency: "USD" }, kind: "order" };
  }
  if (is(/\/paper-orders\/[^/]+\/approval-preview$/)) {
    return { body: APPROVAL_PREVIEW, kind: "approval-preview" };
  }
  if (is(/\/paper-orders\//)) return { body: { status: "COMPLETED" }, kind: "order" };

  // BC-7: kill switch (USER scope). Reached on EVERY route, so leaving it
  // unregistered made matchEndpoint throw inside the route handler on every
  // single combination in the matrix.
  if (is(/\/trading\/kill-switch$/)) return { body: KILL_SWITCH_RELEASED, kind: "kill-switch" };

  // Live order lifecycle. approve and dispatch are deliberately separate steps:
  // approve does NOT reach the broker. Both return void (204-style empty body).
  if (is(/\/live-orders\/[^/]+\/step-up$/)) {
    return { body: { stepUpToken: "live-step-up-token", expiresAt: "2026-08-06T00:10:00Z" }, kind: "step-up" };
  }
  if (is(/\/live-orders\/[^/]+\/(approve|dispatch|cancel|modify)$/)) {
    return { body: null, kind: "live-order-command" };
  }

  throw new Error(`Unregistered frontend API ${method} ${pathname}`);
}

// State-specific transforms applied to the healthy payload.
function shapeForState(match, state) {
  const { body, kind } = match;
  // Decision states only repoint the dashboard; every other endpoint stays healthy
  // so the surface state is the single variable under test.
  if (Object.prototype.hasOwnProperty.call(DECISION_DASHBOARDS, state)) {
    return kind === "dashboard" ? DECISION_DASHBOARDS[state]() : body;
  }
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
      opts.onRequest({ url: url.pathname, query: url.search, method, state });
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

    const endpointMatch = matchEndpoint(url.pathname, method);
    const match = /\/candles$/.test(url.pathname) && endpointMatch.kind === "surface"
      ? {
        ...endpointMatch,
        body: {
          ...endpointMatch.body,
          data: {
            ...endpointMatch.body.data,
            symbol: url.searchParams.get("symbol")?.trim().toUpperCase() || endpointMatch.body.data.symbol,
            interval: url.searchParams.get("interval") || endpointMatch.body.data.interval
          }
        }
      }
      : endpointMatch;

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

// `/predictions` is intentionally absent. It is now a server component that does
// redirect("/settings"), so putting it in the matrix would commit 8 states x 8
// projects of pixel baselines that are byte-for-byte the settings screen and gate
// nothing extra. The redirect is behaviour, not pixels, so it is asserted once by
// URL in home-decision-states' sibling spec (settings-sections.spec.mjs).
export const ROUTES = [
  { path: "/", name: "home", extraStates: DECISION_STATES },
  { path: "/login", name: "login" },
  { path: "/portfolio", name: "portfolio" },
  { path: "/orders", name: "orders" },
  { path: "/events", name: "events" },
  { path: "/settings", name: "settings" },
  { path: "/stocks/AAPL", name: "stocks-AAPL" }
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
