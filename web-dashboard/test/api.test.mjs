import assert from "node:assert/strict";
import test from "node:test";

import {
  actOnProposal,
  analyzePortfolio,
  createBrokerConnection,
  createEvent,
  createSingleFlight,
  deleteBrokerConnection,
  listEvents,
  loadDashboard,
  loadEvent,
  loadSession,
  logout,
  reanalyzeEvent,
  replaceBrokerCredentials,
  reviewEvent,
  syncPortfolio,
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

test("treats an unauthenticated session as signed out", async () => {
  assert.equal(await loadSession(async () => new Response(null, { status: 401 })), null);
});

test("approval and cancellation use only the channel-neutral command API", async () => {
  const calls = [];
  const fetcher = async (url, options) => {
    calls.push([url, options]);
    return json({ status: "COMPLETED" });
  };
  const session = { csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "csrf" };

  await actOnProposal("order-1", "approve", session, "idem-1", fetcher);
  await actOnProposal("order-2", "cancel", session, "idem-2", fetcher);

  for (const [index, action] of ["approve", "cancel"].entries()) {
    const [url, options] = calls[index];
    assert.equal(url, `/api/v1/paper-orders/order-${index + 1}/${action}`);
    assert.equal(options.method, "POST");
    assert.equal(options.credentials, "same-origin");
    assert.equal(options.headers["X-CSRF-TOKEN"], "csrf");
    assert.equal(options.headers["Idempotency-Key"], `idem-${index + 1}`);
    assert.deepEqual(JSON.parse(options.body), { channel: "WEB" });
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
