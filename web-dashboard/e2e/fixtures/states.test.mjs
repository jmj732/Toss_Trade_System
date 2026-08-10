import assert from "node:assert/strict";
import test from "node:test";

import { ROUTES, STATES, stateRoute } from "./states.mjs";

async function responseFor(pathname, state) {
  let response;
  const route = {
    request: () => ({
      url: () => `http://localhost:3000${pathname}`,
      method: () => "GET"
    }),
    fulfill: value => {
      response = value;
    }
  };

  await stateRoute(state, { delayMs: 0 })(route);
  return { ...response, body: JSON.parse(response.body) };
}

test("state matrix includes approved new global states", () => {
  assert.ok(STATES.includes("refreshing"));
  assert.ok(STATES.includes("degraded"));
  assert.ok(!STATES.includes("unsupported"));
});

test("unsupported is scoped to stock/market route coverage", () => {
  assert.deepEqual(
    ROUTES.filter(route => route.extraStates?.includes("unsupported")).map(route => route.name),
    ["stocks-AAPL"]
  );
});

test("unsupported uses existing provider envelope on stock market endpoints", async () => {
  const response = await responseFor("/api/v1/connections/audit-connection/exchange-rate", "unsupported");

  assert.equal(response.status, 200);
  assert.equal(response.body.status, "UNAVAILABLE");
  assert.equal(response.body.unavailable, true);
  assert.equal(response.body.unavailableReason, "PROVIDER_UNSUPPORTED");
});
