import assert from "node:assert/strict";
import test from "node:test";

import {
  authorizedFetch,
  captureAccessTokenFromLocation,
  clearAccessToken,
  getAccessToken,
  resetAuthForTest,
  setAuthLostHandler
} from "../lib/auth.js";

function json(bodyValue, status) {
  return new Response(JSON.stringify(bodyValue), {
    status,
    headers: { "content-type": "application/json" }
  });
}

test("captures the callback fragment into memory and removes it from the URL", () => {
  const location = {
    hash: "#access_token=access-token&expires_at=123",
    pathname: "/portfolio",
    search: "?view=all"
  };
  const history = { calls: [], replaceState: (...args) => history.calls.push(args) };

  resetAuthForTest();
  assert.deepEqual(captureAccessTokenFromLocation(location, history), {
    accessToken: "access-token",
    expiresAt: "123"
  });
  assert.equal(getAccessToken(), "access-token");
  assert.equal(location.hash, "");
  assert.equal(history.calls.length, 1);
});

test("adds Bearer auth, refreshes once after 401, and retries the request", async () => {
  const calls = [];
  resetAuthForTest("expired-token");
  const fetcher = async (url, options = {}) => {
    calls.push([url, options]);
    if (url === "/api/v1/auth/refresh") {
      return new Response(JSON.stringify({ accessToken: "fresh-token", expiresAt: 42 }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    return calls.length === 1
      ? new Response(null, { status: 401 })
      : new Response(JSON.stringify({ userId: "user-1" }), { status: 200 });
  };

  const response = await authorizedFetch("/api/v1/session", {}, fetcher);

  assert.equal(response.status, 200);
  assert.equal(calls[0][1].headers.Authorization, "Bearer expired-token");
  assert.equal(calls[1][0], "/api/v1/auth/refresh");
  assert.equal(calls[2][1].headers.Authorization, "Bearer fresh-token");
});

test("clears the in-memory token without exposing a storage API", () => {
  resetAuthForTest("token");
  clearAccessToken();
  assert.equal(getAccessToken(), null);
});

test("does not spend a refresh on a step-up challenge and does not retry", async () => {
  resetAuthForTest("access-token");
  const calls = [];
  const fetcher = async url => {
    calls.push(url);
    if (url === "/api/v1/auth/refresh") {
      return json({ accessToken: "fresh" }, 200);
    }
    return json({ code: "PAPER_ORDER_STEP_UP_REQUIRED" }, 401);
  };

  const response = await authorizedFetch(
    "/api/v1/paper-orders/order-1/approve",
    { method: "POST", headers: { "Idempotency-Key": "k" } },
    fetcher);

  assert.equal(response.status, 401);
  assert.ok(!calls.includes("/api/v1/auth/refresh"));
  assert.equal(calls.filter(url => url.endsWith("/approve")).length, 1);
});

test("notifies auth loss when refresh fails and does not resend a keyless POST", async () => {
  resetAuthForTest("expired");
  let lost = 0;
  setAuthLostHandler(() => {
    lost += 1;
  });
  const calls = [];
  const fetcher = async url => {
    calls.push(url);
    return new Response(null, { status: 401 });
  };

  const response = await authorizedFetch(
    "/api/v1/paper-orders/order-1/step-up",
    { method: "POST", headers: {} },
    fetcher);

  assert.equal(response.status, 401);
  assert.equal(lost, 1);
  assert.equal(calls.filter(url => url.endsWith("/step-up")).length, 1);
});

test("refreshes but does not resend a keyless POST after a successful refresh", async () => {
  resetAuthForTest("expired");
  const calls = [];
  const fetcher = async url => {
    calls.push(url);
    if (url === "/api/v1/auth/refresh") {
      return json({ accessToken: "fresh" }, 200);
    }
    return new Response(null, { status: 401 });
  };

  await authorizedFetch(
    "/api/v1/paper-orders/order-1/step-up",
    { method: "POST", headers: {} },
    fetcher);

  assert.ok(calls.includes("/api/v1/auth/refresh"));
  // D-37: idempotency key 없는 POST 는 refresh 후에도 재전송하지 않는다.
  assert.equal(calls.filter(url => url.endsWith("/step-up")).length, 1);
});
