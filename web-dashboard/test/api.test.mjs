import assert from "node:assert/strict";
import test from "node:test";

import {
  actOnProposal,
  analyzePortfolio,
  createAnalysisPrediction,
  createBrokerConnection,
  createEvent,
  createSingleFlight,
  deletePredictionModelVersion,
  issuePredictionIngestionApiKey,
  deleteBrokerConnection,
  deprecatePredictionModelVersion,
  listEvents,
  listNotifications,
  loadAnalysisPredictions,
  loadDashboard,
  loadEvent,
  loadPaperPerformance,
  loadPortfolioHistory,
  loadPredictionModelVersions,
  loadPredictionIngestionApiKeys,
  loadPredictionOperations,
  loadOperationalReadiness,
  loadRiskPolicy,
  loadRiskPolicyHistory,
  loadStockAnalysis,
  loadStockAnalysisHistory,
  loadStockAnalysisRun,
  createStockAnalysis,
  loadStockForecast,
  createStockForecast,
  loadStockAnalysisExplanation,
  createStockAnalysisExplanation,
  loadSession,
  loadUnreadCount,
  logout,
  markNotificationRead,
  reanalyzeEvent,
  registerPredictionModelVersion,
  revokePredictionIngestionApiKey,
  rotatePredictionIngestionApiKey,
  runProviderReadinessCheck,
  replaceBrokerCredentials,
  reviewEvent,
  syncPortfolio,
  updateRiskPolicy,
  verifyBrokerConnection
} from "../lib/api.js";
import nextConfig from "../next.config.js";

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" }
  });
}

test("loads the internal session and owned dashboard with same-origin cookies", async () => {
  const calls = [];
  const fetcher = async (url, options) => {
    calls.push([url, options]);
    return json(url.includes("session")
      ? { userId: "user-1", csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" }
      : { portfolio: {}, analysis: {}, pendingEvents: {}, pendingOrderProposals: {} });
  };

  assert.equal((await loadSession(fetcher)).userId, "user-1");
  await loadDashboard("connection/1", fetcher);

  assert.deepEqual(calls, [
    ["/api/v1/session", { credentials: "same-origin" }],
    ["/api/v1/broker-connections/connection%2F1/dashboard",
      { credentials: "same-origin" }]
  ]);
});

test("stock product APIs preserve owner paths, history selection, and CSRF mutations", async () => {
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return json({ symbol: "AAPL", result: { status: "DEGRADED" } });
  };
  const session = { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" };

  await loadStockAnalysis("AAPL", fetcher);
  await loadStockAnalysisHistory("AAPL", 7, fetcher);
  await loadStockAnalysisRun("AAPL", "run/1", fetcher);
  await createStockAnalysis("AAPL", { identifiers: { cik: "1" } }, session, fetcher);
  await loadStockForecast("AAPL", fetcher);
  await loadStockForecast("AAPL", "run-1", fetcher);
  await createStockForecast(
    "AAPL",
    { connectionId: "connection/1", modelVersion: "model-v1", contractVersion: "forecast-v1" },
    session,
    fetcher);
  await loadStockAnalysisExplanation("AAPL", fetcher);
  await loadStockAnalysisExplanation("AAPL", "run-1", fetcher);
  await createStockAnalysisExplanation("AAPL", session, fetcher);

  assert.deepEqual(calls.map(([url, options]) => [
    url,
    options.method,
    options.credentials,
    options.headers?.["X-CSRF-TOKEN"]
  ]), [
    ["/api/v1/stock-analyses/AAPL", undefined, "same-origin", undefined],
    ["/api/v1/stock-analyses/AAPL/history?limit=7", undefined, "same-origin", undefined],
    ["/api/v1/stock-analyses/AAPL/runs/run%2F1", undefined, "same-origin", undefined],
    ["/api/v1/stock-analyses/AAPL", "POST", "same-origin", "csrf"],
    ["/api/v1/stock-forecasts/AAPL", undefined, "same-origin", undefined],
    ["/api/v1/stock-forecasts/AAPL?runId=run-1", undefined, "same-origin", undefined],
    ["/api/v1/stock-forecasts/AAPL", "POST", "same-origin", "csrf"],
    ["/api/v1/stock-analysis-explanations/AAPL", undefined, "same-origin", undefined],
    ["/api/v1/stock-analysis-explanations/AAPL?runId=run-1", undefined, "same-origin", undefined],
    ["/api/v1/stock-analysis-explanations/AAPL", "POST", "same-origin", "csrf"]
  ]);
  assert.deepEqual(JSON.parse(calls[3][1].body), { identifiers: { cik: "1" } });
  assert.deepEqual(JSON.parse(calls[6][1].body), {
    connectionId: "connection/1", modelVersion: "model-v1", contractVersion: "forecast-v1"
  });
});

test("treats an unauthenticated session as signed out", async () => {
  assert.equal(await loadSession(async () => new Response(null, { status: 401 })), null);
});

test("reads readiness and probes providers with session CSRF", async () => {
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return json({ status: "DEGRADED" });
  };
  const session = { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" };

  await loadOperationalReadiness(fetcher);
  await runProviderReadinessCheck("AAPL", session, fetcher);

  assert.equal(calls[0][0], "/api/v1/operations/readiness");
  assert.equal(calls[0][1].credentials, "same-origin");
  assert.equal(calls[1][0], "/api/v1/operations/readiness/provider-check");
  assert.equal(calls[1][1].method, "POST");
  assert.equal(calls[1][1].headers["X-CSRF-TOKEN"], "csrf");
  assert.deepEqual(JSON.parse(calls[1][1].body), { symbol: "AAPL" });
});

test("approval obtains a preview and step-up token before submitting", async () => {
  const calls = [];
  const fetcher = async (url, options) => {
    calls.push([url, options]);
    if (url.endsWith("/approval-preview")) {
      return json({
        displayedQuantity: 1, displayedMaxLoss: 100, displayedCurrency: "USD",
        proposalVersion: null
      });
    }
    if (url.endsWith("/step-up")) {
      return json({ stepUpToken: "step-up-token" });
    }
    return json({ status: "COMPLETED" });
  };
  const session = { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" };

  await actOnProposal("order-1", "approve", session, "idem-1", fetcher);
  await actOnProposal("order-2", "cancel", session, "idem-2", fetcher);

  assert.equal(calls.length, 5);
  assert.equal(calls[0][0], "/api/v1/paper-orders/order-1");
  assert.equal(calls[1][0], "/api/v1/paper-orders/order-1/approval-preview");
  assert.equal(calls[2][0], "/api/v1/paper-orders/order-1/step-up");
  assert.equal(calls[2][1].headers["X-CSRF-TOKEN"], "csrf");

  for (const [index, orderId, action] of [[3, "order-1", "approve"], [4, "order-2", "cancel"]]) {
    const [url, options] = calls[index];
    assert.equal(url, `/api/v1/paper-orders/${orderId}/${action}`);
    assert.equal(options.method, "POST");
    assert.equal(options.credentials, "same-origin");
    assert.equal(options.headers["X-CSRF-TOKEN"], "csrf");
    assert.equal(options.headers["Idempotency-Key"], action === "approve" ? "idem-1" : "idem-2");
    assert.deepEqual(JSON.parse(options.body), action === "approve"
      ? {
        channel: "WEB", displayedQuantity: 1, displayedMaxLoss: 100,
        displayedCurrency: "USD", proposalVersion: null
      }
      : { channel: "WEB" });
    if (action === "approve") {
      assert.equal(options.headers["X-Step-Up-Token"], "step-up-token");
    }
  }
});

test("logout posts the session CSRF token", async () => {
  let call;
  await logout(
    { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" },
    async (...args) => {
      call = args;
      return new Response(null, { status: 204 });
    });

  assert.deepEqual(call, ["/logout", {
    method: "POST",
    credentials: "same-origin",
    headers: { "X-CSRF-TOKEN": "csrf" }
  }]);
});

test("proxies only the session API and OIDC lifecycle to Spring", async () => {
  assert.deepEqual(await nextConfig.rewrites(), [
    {
      source: "/api/:path*",
      destination: "http://localhost:8080/api/:path*"
    },
    {
      source: "/oauth2/:path*",
      destination: "http://localhost:8080/oauth2/:path*"
    },
    {
      source: "/login/oauth2/:path*",
      destination: "http://localhost:8080/login/oauth2/:path*"
    },
    {
      source: "/logout",
      destination: "http://localhost:8080/logout"
    }
  ]);
});

test("redirects failed OIDC callbacks back to the dashboard", async () => {
  assert.deepEqual(await nextConfig.redirects(), [
    {
      source: "/login/oauth2/:path*",
      has: [{ type: "query", key: "error" }],
      destination: "/?error=login",
      permanent: false
    }
  ]);
});

test("broker onboarding commands use CSRF and existing APIs", async () => {
  const calls = [];
  const fetcher = async (url, options) => {
    calls.push([url, options]);
    return options.method === "DELETE"
      ? new Response(null, { status: 204 })
      : json({ id: "connection-1", status: "ACTIVE" });
  };
  const session = { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" };
  const credentials = { clientId: "client", clientSecret: "secret" };

  await createBrokerConnection(credentials, session, fetcher);
  await replaceBrokerCredentials("connection-1", credentials, session, fetcher);
  await verifyBrokerConnection("connection-1", session, fetcher);
  await syncPortfolio("connection-1", session, fetcher);
  await analyzePortfolio("connection-1", session, fetcher);
  await deleteBrokerConnection("connection-1", session, fetcher);

  assert.deepEqual(calls.map(([url, options]) => [
    url,
    options.method,
    options.headers["X-CSRF-TOKEN"],
    options.body && JSON.parse(options.body)
  ]), [
    ["/api/v1/broker-connections/toss", "POST", "csrf", credentials],
    ["/api/v1/broker-connections/connection-1/credentials",
      "PUT", "csrf", credentials],
    ["/api/v1/broker-connections/connection-1/verify", "POST", "csrf", undefined],
    ["/api/v1/broker-connections/connection-1/portfolio-syncs",
      "POST", "csrf", undefined],
    ["/api/v1/broker-connections/connection-1/portfolio-analyses",
      "POST", "csrf", undefined],
    ["/api/v1/broker-connections/connection-1", "DELETE", "csrf", undefined]
  ]);
});

test("single-flight returns one promise and runs one mutation", async () => {
  const run = createSingleFlight();
  let calls = 0;
  let finish;
  const task = () => {
    calls += 1;
    return new Promise(resolve => {
      finish = resolve;
    });
  };

  const first = run(task);
  const duplicate = run(task);

  assert.strictEqual(duplicate, first);
  assert.equal(calls, 1);
  finish("done");
  assert.equal(await first, "done");
});

test("manual event commands use owned paths, CSRF, version, and idempotency", async () => {
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return json(url.endsWith("/events")
      ? [{ id: "event-1", reviewStatus: "PENDING" }]
      : { id: "event-1", reviewStatus: "CONFIRMED" });
  };
  const session = { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" };
  const command = {
    source: "MANUAL",
    sourceEventId: "fed-rate-1",
    type: "MACRO",
    summary: "Rate decision",
    affectedSymbols: ["NVDA"],
    occurredAt: "2026-07-28T00:00:00.000Z"
  };

  await createEvent("connection/1", command, session, fetcher);
  await listEvents("connection/1", fetcher);
  await loadEvent("connection/1", "event/1", fetcher);
  await reanalyzeEvent("connection/1", "event/1", session, fetcher);
  await reviewEvent(
    "connection/1", "event/1", "CONFIRMED", 2, session, "idem-1", fetcher);

  assert.deepEqual(calls.map(([url, options]) => [
    url,
    options.method,
    options.headers?.["X-CSRF-TOKEN"],
    options.headers?.["Idempotency-Key"],
    options.body && JSON.parse(options.body)
  ]), [
    ["/api/v1/broker-connections/connection%2F1/events",
      "POST", "csrf", undefined, command],
    ["/api/v1/broker-connections/connection%2F1/events",
      undefined, undefined, undefined, undefined],
    ["/api/v1/broker-connections/connection%2F1/events/event%2F1",
      undefined, undefined, undefined, undefined],
    ["/api/v1/broker-connections/connection%2F1/events/event%2F1/reanalyze",
      "POST", "csrf", undefined, undefined],
    ["/api/v1/broker-connections/connection%2F1/events/event%2F1/review",
      "POST", "csrf", "idem-1", { status: "CONFIRMED", expectedVersion: 2 }]
  ]);
});

test("lists notifications with optional unread filter and limit as query params", async () => {
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return json([{ id: "notification-1" }]);
  };

  await listNotifications(false, undefined, fetcher);
  await listNotifications(true, 10, fetcher);

  assert.deepEqual(calls.map(([url]) => url), [
    "/api/v1/notifications",
    "/api/v1/notifications?unreadOnly=true&limit=10"
  ]);
  assert.deepEqual(calls.map(([, options]) => options.credentials),
    ["same-origin", "same-origin"]);
});

test("loads the unread notification count", async () => {
  let call;
  await loadUnreadCount(async (...args) => {
    call = args;
    return json({ count: 3 });
  }).then(result => assert.equal(result.count, 3));

  assert.deepEqual(call, [
    "/api/v1/notifications/unread-count", { credentials: "same-origin" }
  ]);
});

test("marks a notification read with the session CSRF token", async () => {
  let call;
  const session = { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" };

  await markNotificationRead("notification/1", session, async (...args) => {
    call = args;
    return json({ id: "notification/1", readAt: "2026-07-28T00:00:00Z" });
  });

  const [url, options] = call;
  assert.equal(url, "/api/v1/notifications/notification%2F1/read");
  assert.equal(options.method, "POST");
  assert.equal(options.credentials, "same-origin");
  assert.equal(options.headers["X-CSRF-TOKEN"], "csrf");
  assert.equal(options.body, undefined);
});

test("loads portfolio history with an optional from/to/maxPoints query", async () => {
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return json({ stale: false, unavailable: false, data: { points: [] } });
  };

  await loadPortfolioHistory("connection/1", {}, fetcher);
  await loadPortfolioHistory(
    "connection/1", { from: "2026-01-01T00:00:00Z", to: "2026-02-01T00:00:00Z", maxPoints: 30 },
    fetcher);

  assert.deepEqual(calls.map(([url, options]) => [url, options.credentials]), [
    ["/api/v1/broker-connections/connection%2F1/portfolio-history", "same-origin"],
    ["/api/v1/broker-connections/connection%2F1/portfolio-history"
      + "?from=2026-01-01T00%3A00%3A00Z&to=2026-02-01T00%3A00%3A00Z&maxPoints=30", "same-origin"]
  ]);
});

test("loads paper performance with an optional from/to/maxPoints query", async () => {
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return json({ unavailable: false, data: { byCurrency: {} } });
  };

  await loadPaperPerformance("connection/1", {}, fetcher);
  await loadPaperPerformance(
    "connection/1", { from: "2026-01-01T00:00:00Z", to: "2026-02-01T00:00:00Z", maxPoints: 30 },
    fetcher);

  assert.deepEqual(calls.map(([url, options]) => [url, options.credentials]), [
    ["/api/v1/broker-connections/connection%2F1/paper-performance", "same-origin"],
    ["/api/v1/broker-connections/connection%2F1/paper-performance"
      + "?from=2026-01-01T00%3A00%3A00Z&to=2026-02-01T00%3A00%3A00Z&maxPoints=30", "same-origin"]
  ]);
});

test("records an analysis prediction with the session CSRF token", async () => {
  let call;
  const fetcher = async (...args) => {
    call = args;
    return json({ id: "prediction-1" });
  };
  const session = { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" };
  const command = {
    symbol: "AAPL", currency: "USD", predictedDirection: "UP",
    modelVersion: "v1", contractVersion: "1"
  };

  await createAnalysisPrediction("connection/1", command, session, fetcher);

  assert.deepEqual(call, [
    "/api/v1/broker-connections/connection%2F1/analysis-predictions",
    {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": "csrf", "content-type": "application/json" },
      body: JSON.stringify(command)
    }
  ]);
});

test("loads analysis predictions with optional period/version filters", async () => {
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return json({ predictions: [], byVersion: [] });
  };

  await loadAnalysisPredictions("connection/1", {}, fetcher);
  await loadAnalysisPredictions(
      "connection/1",
      {
        from: "2026-01-01T00:00:00Z", to: "2026-02-01T00:00:00Z",
        modelVersion: "v1", contractVersion: "1", symbol: "AAPL"
      },
      fetcher);

  assert.deepEqual(calls.map(([url, options]) => [url, options.credentials]), [
    ["/api/v1/broker-connections/connection%2F1/analysis-predictions", "same-origin"],
    ["/api/v1/broker-connections/connection%2F1/analysis-predictions"
      + "?from=2026-01-01T00%3A00%3A00Z&to=2026-02-01T00%3A00%3A00Z&modelVersion=v1&contractVersion=1&symbol=AAPL",
      "same-origin"]
  ]);
});

test("manages prediction model versions with same-origin credentials and CSRF", async () => {
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return options.method === "DELETE"
      ? new Response(null, { status: 204 })
      : json([]);
  };
  const session = { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" };
  const command = { modelVersion: "v1", contractVersion: "1" };

  await loadPredictionModelVersions(fetcher);
  await registerPredictionModelVersion(command, session, fetcher);
  await deprecatePredictionModelVersion("version/1", session, fetcher);
  await deletePredictionModelVersion("version/1", session, fetcher);

  assert.deepEqual(calls, [
    ["/api/v1/prediction-model-versions", { credentials: "same-origin" }],
    ["/api/v1/prediction-model-versions", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": "csrf", "content-type": "application/json" },
      body: JSON.stringify(command)
    }],
    ["/api/v1/prediction-model-versions/version%2F1/deprecate", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": "csrf" }
    }],
    ["/api/v1/prediction-model-versions/version%2F1", {
      method: "DELETE",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": "csrf" }
    }]
  ]);
});

test("manages prediction ingestion API keys and reads operations with session CSRF", async () => {
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    if (options.method === "DELETE") {
      return new Response(null, { status: 204 });
    }
    return json(url.endsWith("prediction-operations")
      ? { evaluationEnabled: true, backlog: 2, maxLagMs: 3000 }
      : []);
  };
  const session = { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" };
  const command = {
    modelVersion: "model-v1",
    contractVersion: "contract-v1",
    expiresAt: "2099-01-01T00:00:00.000Z"
  };

  await loadPredictionIngestionApiKeys(fetcher);
  await issuePredictionIngestionApiKey(command, session, fetcher);
  await rotatePredictionIngestionApiKey("key/1", { expiresAt: null }, session, fetcher);
  await revokePredictionIngestionApiKey("key/1", session, fetcher);
  await loadPredictionOperations(fetcher);

  assert.deepEqual(calls, [
    ["/api/v1/prediction-ingestion-api-keys", { credentials: "same-origin" }],
    ["/api/v1/prediction-ingestion-api-keys", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": "csrf", "content-type": "application/json" },
      body: JSON.stringify(command)
    }],
    ["/api/v1/prediction-ingestion-api-keys/key%2F1/rotate", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": "csrf", "content-type": "application/json" },
      body: JSON.stringify({ expiresAt: null })
    }],
    ["/api/v1/prediction-ingestion-api-keys/key%2F1", {
      method: "DELETE",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": "csrf" }
    }],
    ["/api/v1/prediction-operations", { credentials: "same-origin" }]
  ]);
});

test("loads the risk policy and its optional-limit history", async () => {
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return json(url.includes("history") ? [] : { version: 0, customized: false });
  };

  await loadRiskPolicy(fetcher);
  await loadRiskPolicyHistory(undefined, fetcher);
  await loadRiskPolicyHistory(25, fetcher);

  assert.deepEqual(calls.map(([url, options]) => [url, options.credentials]), [
    ["/api/v1/risk-policy", "same-origin"],
    ["/api/v1/risk-policy/history", "same-origin"],
    ["/api/v1/risk-policy/history?limit=25", "same-origin"]
  ]);
});

test("updates the risk policy with the session CSRF token", async () => {
  let call;
  const session = { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" };
  const input = {
    expectedVersion: 0, maxOrderAmountKrw: 500000, maxOrderAmountUsd: 500,
    maxQuantity: 2, maxConcentration: 0.3
  };

  await updateRiskPolicy(input, session, async (...args) => {
    call = args;
    return json({ version: 1, customized: true });
  });

  const [url, options] = call;
  assert.equal(url, "/api/v1/risk-policy");
  assert.equal(options.method, "PUT");
  assert.equal(options.credentials, "same-origin");
  assert.equal(options.headers["X-CSRF-TOKEN"], "csrf");
  assert.deepEqual(JSON.parse(options.body), input);
});

test("manual event duplicate exposes only public code", async () => {
  await assert.rejects(
    createEvent(
      "connection-1",
      {
        source: "MANUAL",
        sourceEventId: "duplicate",
        type: "MACRO",
        summary: "Duplicate",
        affectedSymbols: ["NVDA"],
        occurredAt: "2026-07-28T00:00:00.000Z"
      },
      { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" },
      async () => json({ code: "EVENT_ALREADY_EXISTS" }, 409)),
    { message: "EVENT_ALREADY_EXISTS", status: 409 });
});
