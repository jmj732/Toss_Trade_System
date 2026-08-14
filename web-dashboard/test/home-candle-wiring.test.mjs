import assert from "node:assert/strict";
import test from "node:test";

import {
  homeCandleRequestKey,
  needsHomeCandleRequest,
  selectHomeSymbol
} from "../app/route-workspace.js";

test("selectHomeSymbol reads only portfolio positions and uppercases the first nonblank symbol", () => {
  const dashboard = {
    portfolio: {
      data: {
        positions: [
          { symbol: "   " },
          { symbol: " aapl " },
          { symbol: "msft" }
        ]
      }
    },
    symbol: "TSLA"
  };

  assert.equal(selectHomeSymbol(dashboard), "AAPL");
});

test("selectHomeSymbol has no fallback outside dashboard.portfolio.data.positions", () => {
  assert.equal(selectHomeSymbol({ portfolio: { data: { positions: [] } }, symbol: "TSLA" }), "");
  assert.equal(selectHomeSymbol({ portfolio: { data: {} }, symbol: "TSLA" }), "");
  assert.equal(selectHomeSymbol({ positions: [{ symbol: "TSLA" }] }), "");
  assert.equal(selectHomeSymbol(null), "");
  assert.equal(selectHomeSymbol({ portfolio: { data: { positions: [{ symbol: 123 }, { symbol: " msft " }] } } }), "MSFT");
});

test("home candle request keys compare connection, symbol, and interval exactly", () => {
  const key = homeCandleRequestKey("connection-1", "AAPL", "1m");

  assert.equal(needsHomeCandleRequest(key, homeCandleRequestKey("connection-1", "AAPL", "1m")), false);
  assert.equal(needsHomeCandleRequest(key, homeCandleRequestKey("connection-2", "AAPL", "1m")), true);
  assert.equal(needsHomeCandleRequest(key, homeCandleRequestKey("connection-1", "MSFT", "1m")), true);
  assert.equal(needsHomeCandleRequest(key, homeCandleRequestKey("connection-1", "AAPL", "1d")), true);
});
