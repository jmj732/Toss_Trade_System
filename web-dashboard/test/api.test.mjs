import assert from "node:assert/strict";
import test from "node:test";

import { resetAuthForTest } from "../lib/auth.js";

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
  orderActionKey,
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

test.afterEach(() => resetAuthForTest());

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" }
  });
}

test("loads bearer metadata and owned dashboard with same-origin cookies", async () => {
  const calls = [];
  const fetcher = async (url, options) => {
    calls.push([url, options]);
    return json(url.includes("session")
      ? { userId: "user-1", authenticatedAt: "2026-07-28T00:00:00Z" }
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

test("stock product APIs preserve owner paths, history selection, and bearer mutations", async () => {
  resetAuthForTest("access-token");
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return json({ symbol: "AAPL", result: { status: "DEGRADED" } });
  };

  await loadStockAnalysis("AAPL", fetcher);
  await loadStockAnalysisHistory("AAPL", 7, fetcher);
  await loadStockAnalysisRun("AAPL", "run/1", fetcher);
  await createStockAnalysis("AAPL", { identifiers: { cik: "1" } }, fetcher);
  await loadStockForecast("AAPL", fetcher);
  await loadStockForecast("AAPL", "run-1", fetcher);
  await createStockForecast(
    "AAPL",
    { connectionId: "connection/1", modelVersion: "model-v1", contractVersion: "forecast-v1" },
    fetcher);
  await loadStockAnalysisExplanation("AAPL", fetcher);
  await loadStockAnalysisExplanation("AAPL", "run-1", fetcher);
  await createStockAnalysisExplanation("AAPL", fetcher);

  assert.deepEqual(calls.map(([url, options]) => [
    url,
    options.method,
    options.credentials,
    options.headers?.Authorization
  ]), [
    ["/api/v1/stock-analyses/AAPL", undefined, "same-origin", "Bearer access-token"],
    ["/api/v1/stock-analyses/AAPL/history?limit=7", undefined, "same-origin", "Bearer access-token"],
    ["/api/v1/stock-analyses/AAPL/runs/run%2F1", undefined, "same-origin", "Bearer access-token"],
    ["/api/v1/stock-analyses/AAPL", "POST", "same-origin", "Bearer access-token"],
    ["/api/v1/stock-forecasts/AAPL", undefined, "same-origin", "Bearer access-token"],
    ["/api/v1/stock-forecasts/AAPL?runId=run-1", undefined, "same-origin", "Bearer access-token"],
    ["/api/v1/stock-forecasts/AAPL", "POST", "same-origin", "Bearer access-token"],
    ["/api/v1/stock-analysis-explanations/AAPL", undefined, "same-origin", "Bearer access-token"],
    ["/api/v1/stock-analysis-explanations/AAPL?runId=run-1", undefined, "same-origin", "Bearer access-token"],
    ["/api/v1/stock-analysis-explanations/AAPL", "POST", "same-origin", "Bearer access-token"]
  ]);
  assert.deepEqual(JSON.parse(calls[3][1].body), { identifiers: { cik: "1" } });
  assert.deepEqual(JSON.parse(calls[6][1].body), {
    connectionId: "connection/1", modelVersion: "model-v1", contractVersion: "forecast-v1"
  });
});

test("treats an unauthenticated session as signed out", async () => {
  assert.equal(await loadSession(async () => new Response(null, { status: 401 })), null);
});

test("reads readiness and probes providers with bearer auth", async () => {
  resetAuthForTest("access-token");
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return json({ status: "DEGRADED" });
  };

  await loadOperationalReadiness(fetcher);
  await runProviderReadinessCheck("AAPL", fetcher);

  assert.equal(calls[0][0], "/api/v1/operations/readiness");
  assert.equal(calls[0][1].credentials, "same-origin");
  assert.equal(calls[1][0], "/api/v1/operations/readiness/provider-check");
  assert.equal(calls[1][1].method, "POST");
  assert.equal(calls[1][1].headers.Authorization, "Bearer access-token");
  assert.deepEqual(JSON.parse(calls[1][1].body), { symbol: "AAPL" });
});

test("approve submits only user-confirmed display values and never self-fetches a preview", async () => {
  resetAuthForTest("access-token");
  const calls = [];
  const fetcher = async (url, options) => {
    calls.push([url, options]);
    if (url.endsWith("/step-up")) {
      return json({ stepUpToken: "step-up-token" });
    }
    return json({ status: "COMPLETED" });
  };

  await actOnProposal("order-1", "approve", {
    idempotencyKey: "idem-1",
    displayed: { quantity: 1, maxLoss: 100, currency: "USD", proposalVersion: null }
  }, fetcher);
  await actOnProposal("order-2", "cancel", { idempotencyKey: "idem-2" }, fetcher);

  // 승인 게이트 무력화 방지: preview 나 주문 조회를 서버에 요청하지 않는다.
  assert.ok(!calls.some(([url]) => url.endsWith("/approval-preview")));
  assert.ok(!calls.some(([url]) => /\/paper-orders\/order-1$/.test(url)));

  const stepUp = calls.find(([url]) => url.endsWith("/order-1/step-up"));
  assert.equal(stepUp[0], "/api/v1/paper-orders/order-1/step-up");
  assert.equal(stepUp[1].headers.Authorization, "Bearer access-token");

  const approve = calls.find(([url]) => url.endsWith("/order-1/approve"));
  assert.equal(approve[1].method, "POST");
  assert.equal(approve[1].credentials, "same-origin");
  assert.equal(approve[1].headers.Authorization, "Bearer access-token");
  assert.equal(approve[1].headers["Idempotency-Key"], "idem-1");
  assert.equal(approve[1].headers["X-Step-Up-Token"], "step-up-token");
  assert.deepEqual(JSON.parse(approve[1].body), {
    channel: "WEB", displayedQuantity: 1, displayedMaxLoss: 100,
    displayedCurrency: "USD", proposalVersion: null
  });

  const cancel = calls.find(([url]) => url.endsWith("/order-2/cancel"));
  assert.equal(cancel[1].headers["Idempotency-Key"], "idem-2");
  assert.deepEqual(JSON.parse(cancel[1].body), { channel: "WEB" });
});

test("approve refuses to send a request without user-confirmed display values", async () => {
  resetAuthForTest("access-token");
  let called = false;
  const fetcher = async () => {
    called = true;
    return json({ status: "COMPLETED" });
  };

  await assert.rejects(
    actOnProposal("order-1", "approve", { idempotencyKey: "idem-1" }, fetcher),
    { message: "APPROVAL_DISPLAY_REQUIRED" });
  // 과거 시그니처(3번째 인자=키 문자열)로는 승인할 수 없다.
  await assert.rejects(
    actOnProposal("order-1", "approve", "idem-legacy", fetcher),
    { message: "APPROVAL_DISPLAY_REQUIRED" });
  // 불완전한 display(통화 누락)도 거부한다.
  await assert.rejects(
    actOnProposal("order-1", "approve", {
      displayed: { quantity: 1, maxLoss: 100, currency: "" }
    }, fetcher),
    { message: "APPROVAL_DISPLAY_REQUIRED" });

  assert.equal(called, false);
});

test("order action idempotency key is reused across retries of the same order and action", () => {
  assert.equal(
    orderActionKey("order-1", "approve"),
    orderActionKey("order-1", "approve"));
  assert.notEqual(
    orderActionKey("order-1", "approve"),
    orderActionKey("order-1", "cancel"));
  assert.notEqual(
    orderActionKey("order-1", "approve"),
    orderActionKey("order-2", "approve"));
});

test("approve without an explicit key falls back to the stable per-order key", async () => {
  resetAuthForTest("access-token");
  const calls = [];
  const fetcher = async (url, options) => {
    calls.push([url, options]);
    if (url.endsWith("/step-up")) {
      return json({ stepUpToken: "t" });
    }
    return json({ status: "COMPLETED" });
  };

  await actOnProposal("order-9", "approve", {
    displayed: { quantity: 2, maxLoss: 50, currency: "KRW", proposalVersion: 3 }
  }, fetcher);

  const approve = calls.find(([url]) => url.endsWith("/order-9/approve"));
  assert.equal(approve[1].headers["Idempotency-Key"], orderActionKey("order-9", "approve"));
});

test("error body is preserved and step-up is promoted to a distinct failure", async () => {
  resetAuthForTest("access-token");
  const mismatch = actOnProposal("order-1", "approve", {
    displayed: { quantity: 1, maxLoss: 100, currency: "USD", proposalVersion: null }
  }, async url => url.endsWith("/step-up")
    ? json({ stepUpToken: "t" })
    : json({
      code: "PAPER_ORDER_DISPLAY_MISMATCH",
      serverQuantity: 2, serverMaxLoss: 250, currency: "USD"
    }, 409));

  await mismatch.then(
    () => assert.fail("expected rejection"),
    error => {
      assert.equal(error.status, 409);
      assert.equal(error.body.serverQuantity, 2);
      assert.equal(error.body.serverMaxLoss, 250);
      assert.equal(error.body.currency, "USD");
    });
});

test("a 409 PAPER_ORDER_PROPOSAL_EXPIRED carries the code and a Korean message (D-42)", async () => {
  resetAuthForTest("access-token");
  const expired = actOnProposal("order-1", "approve", {
    displayed: { quantity: 1, maxLoss: 100, currency: "USD", proposalVersion: null }
  }, async url => url.endsWith("/step-up")
    ? json({ stepUpToken: "t" })
    : json({ code: "PAPER_ORDER_PROPOSAL_EXPIRED" }, 409));

  await expired.then(
    () => assert.fail("expected rejection"),
    error => {
      assert.equal(error.status, 409);
      assert.equal(error.code, "PAPER_ORDER_PROPOSAL_EXPIRED");
      assert.equal(error.body.code, "PAPER_ORDER_PROPOSAL_EXPIRED");
      assert.match(error.message, /만료되어 승인할 수 없습니다/);
    });
});

test("logout posts the refresh-cookie endpoint", async () => {
  resetAuthForTest("access-token");
  let call;
  await logout(
    async (...args) => {
      call = args;
      return new Response(null, { status: 204 });
    });

  assert.deepEqual(call, ["/api/v1/auth/logout", {
    method: "POST",
    credentials: "same-origin",
    headers: { Authorization: "Bearer access-token" }
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
  ]);
});


test("broker onboarding commands use bearer auth and existing APIs", async () => {
  resetAuthForTest("access-token");
  const calls = [];
  const fetcher = async (url, options) => {
    calls.push([url, options]);
    return options.method === "DELETE"
      ? new Response(null, { status: 204 })
      : json({ id: "connection-1", status: "ACTIVE" });
  };
  const credentials = { clientId: "client", clientSecret: "secret" };

  await createBrokerConnection(credentials, fetcher);
  await replaceBrokerCredentials("connection-1", credentials, fetcher);
  await verifyBrokerConnection("connection-1", fetcher);
  await syncPortfolio("connection-1", fetcher);
  await analyzePortfolio("connection-1", fetcher);
  await deleteBrokerConnection("connection-1", fetcher);

  assert.deepEqual(calls.map(([url, options]) => [
    url,
    options.method,
    options.headers.Authorization,
    options.body && JSON.parse(options.body)
  ]), [
    ["/api/v1/broker-connections/toss", "POST", "Bearer access-token", credentials],
    ["/api/v1/broker-connections/connection-1/credentials",
      "PUT", "Bearer access-token", credentials],
    ["/api/v1/broker-connections/connection-1/verify", "POST", "Bearer access-token", undefined],
    ["/api/v1/broker-connections/connection-1/portfolio-syncs",
      "POST", "Bearer access-token", undefined],
    ["/api/v1/broker-connections/connection-1/portfolio-analyses",
      "POST", "Bearer access-token", undefined],
    ["/api/v1/broker-connections/connection-1", "DELETE", "Bearer access-token", undefined]
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

test("manual event commands use owned paths, bearer auth, version, and idempotency", async () => {
  resetAuthForTest("access-token");
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return json(url.endsWith("/events")
      ? [{ id: "event-1", reviewStatus: "PENDING" }]
      : { id: "event-1", reviewStatus: "CONFIRMED" });
  };
  const command = {
    source: "MANUAL",
    sourceEventId: "fed-rate-1",
    type: "MACRO",
    summary: "Rate decision",
    affectedSymbols: ["NVDA"],
    occurredAt: "2026-07-28T00:00:00.000Z"
  };

  await createEvent("connection/1", command, fetcher);
  await listEvents("connection/1", fetcher);
  await loadEvent("connection/1", "event/1", fetcher);
  await reanalyzeEvent("connection/1", "event/1", fetcher);
  await reviewEvent(
    "connection/1", "event/1", "CONFIRMED", 2, "idem-1", fetcher);

  assert.deepEqual(calls.map(([url, options]) => [
    url,
    options.method,
    options.headers?.Authorization,
    options.headers?.["Idempotency-Key"],
    options.body && JSON.parse(options.body)
  ]), [
    ["/api/v1/broker-connections/connection%2F1/events",
      "POST", "Bearer access-token", undefined, command],
    ["/api/v1/broker-connections/connection%2F1/events",
      undefined, "Bearer access-token", undefined, undefined],
    ["/api/v1/broker-connections/connection%2F1/events/event%2F1",
      undefined, "Bearer access-token", undefined, undefined],
    ["/api/v1/broker-connections/connection%2F1/events/event%2F1/reanalyze",
      "POST", "Bearer access-token", undefined, undefined],
    ["/api/v1/broker-connections/connection%2F1/events/event%2F1/review",
      "POST", "Bearer access-token", "idem-1", { status: "CONFIRMED", expectedVersion: 2 }]
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

test("marks a notification read with bearer auth", async () => {
  resetAuthForTest("access-token");
  let call;

  await markNotificationRead("notification/1", async (...args) => {
    call = args;
    return json({ id: "notification/1", readAt: "2026-07-28T00:00:00Z" });
  });

  const [url, options] = call;
  assert.equal(url, "/api/v1/notifications/notification%2F1/read");
  assert.equal(options.method, "POST");
  assert.equal(options.credentials, "same-origin");
  assert.equal(options.headers.Authorization, "Bearer access-token");
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

test("records an analysis prediction with bearer auth", async () => {
  resetAuthForTest("access-token");
  let call;
  const fetcher = async (...args) => {
    call = args;
    return json({ id: "prediction-1" });
  };
  const command = {
    symbol: "AAPL", currency: "USD", predictedDirection: "UP",
    modelVersion: "v1", contractVersion: "1"
  };

  await createAnalysisPrediction("connection/1", command, fetcher);

  assert.deepEqual(call, [
    "/api/v1/broker-connections/connection%2F1/analysis-predictions",
    {
      method: "POST",
      credentials: "same-origin",
      headers: { Authorization: "Bearer access-token", "content-type": "application/json" },
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

test("manages prediction model versions with same-origin credentials and bearer auth", async () => {
  resetAuthForTest("access-token");
  const calls = [];
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    return options.method === "DELETE"
      ? new Response(null, { status: 204 })
      : json([]);
  };
  const command = { modelVersion: "v1", contractVersion: "1" };

  await loadPredictionModelVersions(fetcher);
  await registerPredictionModelVersion(command, fetcher);
  await deprecatePredictionModelVersion("version/1", fetcher);
  await deletePredictionModelVersion("version/1", fetcher);

  assert.deepEqual(calls, [
    ["/api/v1/prediction-model-versions", {
      credentials: "same-origin", headers: { Authorization: "Bearer access-token" }
    }],
    ["/api/v1/prediction-model-versions", {
      method: "POST",
      credentials: "same-origin",
      headers: { Authorization: "Bearer access-token", "content-type": "application/json" },
      body: JSON.stringify(command)
    }],
    ["/api/v1/prediction-model-versions/version%2F1/deprecate", {
      method: "POST",
      credentials: "same-origin",
      headers: { Authorization: "Bearer access-token" }
    }],
    ["/api/v1/prediction-model-versions/version%2F1", {
      method: "DELETE",
      credentials: "same-origin",
      headers: { Authorization: "Bearer access-token" }
    }]
  ]);
});

test("manages prediction ingestion API keys and reads operations with bearer auth", async () => {
  resetAuthForTest("access-token");
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
  const command = {
    modelVersion: "model-v1",
    contractVersion: "contract-v1",
    expiresAt: "2099-01-01T00:00:00.000Z"
  };

  await loadPredictionIngestionApiKeys(fetcher);
  await issuePredictionIngestionApiKey(command, fetcher);
  await rotatePredictionIngestionApiKey("key/1", { expiresAt: null }, fetcher);
  await revokePredictionIngestionApiKey("key/1", fetcher);
  await loadPredictionOperations(fetcher);

  assert.deepEqual(calls, [
    ["/api/v1/prediction-ingestion-api-keys", {
      credentials: "same-origin", headers: { Authorization: "Bearer access-token" }
    }],
    ["/api/v1/prediction-ingestion-api-keys", {
      method: "POST",
      credentials: "same-origin",
      headers: { Authorization: "Bearer access-token", "content-type": "application/json" },
      body: JSON.stringify(command)
    }],
    ["/api/v1/prediction-ingestion-api-keys/key%2F1/rotate", {
      method: "POST",
      credentials: "same-origin",
      headers: { Authorization: "Bearer access-token", "content-type": "application/json" },
      body: JSON.stringify({ expiresAt: null })
    }],
    ["/api/v1/prediction-ingestion-api-keys/key%2F1", {
      method: "DELETE",
      credentials: "same-origin",
      headers: { Authorization: "Bearer access-token" }
    }],
    ["/api/v1/prediction-operations", {
      credentials: "same-origin", headers: { Authorization: "Bearer access-token" }
    }]
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

test("updates the risk policy with bearer auth", async () => {
  resetAuthForTest("access-token");
  let call;
  const input = {
    expectedVersion: 0, maxOrderAmountKrw: 500000, maxOrderAmountUsd: 500,
    maxQuantity: 2, maxConcentration: 0.3
  };

  await updateRiskPolicy(input, async (...args) => {
    call = args;
    return json({ version: 1, customized: true });
  });

  const [url, options] = call;
  assert.equal(url, "/api/v1/risk-policy");
  assert.equal(options.method, "PUT");
  assert.equal(options.credentials, "same-origin");
  assert.equal(options.headers.Authorization, "Bearer access-token");
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
    async () => json({ code: "EVENT_ALREADY_EXISTS" }, 409)),
    { message: "EVENT_ALREADY_EXISTS", status: 409 });
});
