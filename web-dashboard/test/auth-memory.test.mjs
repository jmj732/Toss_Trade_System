import assert from "node:assert/strict";
import test from "node:test";

import {
  authorizedFetch,
  captureAccessTokenFromLocation,
  clearAccessToken,
  getAccessToken,
  resetAuthForTest
} from "../lib/auth.js";

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
