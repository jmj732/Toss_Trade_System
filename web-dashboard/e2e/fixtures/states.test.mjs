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

test("live market routes are covered by the normal state matrix", () => {
  assert.ok(ROUTES.some(route => route.name === "home"));
  assert.equal(ROUTES.find(route => route.name === "stocks-AAPL")?.extraStates, undefined);
});

test("stock market endpoint fixture is a real provider response", async () => {
  const response = await responseFor("/api/v1/connections/audit-connection/exchange-rate", "unsupported");

  assert.equal(response.status, 200);
  assert.equal(response.body.status, "AVAILABLE");
  assert.equal(response.body.data.baseCurrency, "USD");
  assert.equal(response.body.provenance[0].provider, "TOSS");
});
