import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { CANDLE_INTERVALS, MarketCandleChart } from "../app/market-candle-chart.js";

test("exports only Toss candle intervals without changing API values", () => {
  assert.deepEqual(CANDLE_INTERVALS, [
    { value: "1m", label: "1분봉" },
    { value: "1d", label: "일봉" }
  ]);
});

test("renders degraded usable candles chronologically with accessible svg, table, and raw metadata", () => {
  const envelope = {
    status: "DEGRADED",
    data: {
      candles: [
        { timestamp: "2026-08-02T00:00:00Z", open: 10, high: 12, low: 9, close: 11, volume: 100 },
        { timestamp: "2026-08-01T00:00:00Z", open: 8, high: 10, low: 7, close: 6, volume: null },
        { timestamp: "2026-07-31T00:00:00Z", open: null, high: "x", low: 5, close: 6, volume: 70 }
      ]
    },
    provenance: "TOSS",
    asOf: "2026-08-02T00:01:00Z",
    unknownFields: ["candles[2].high"]
  };

  const html = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope,
    interval: "1m",
    onIntervalChange() {}
  }));

  assert.match(html, /aria-labelledby="market-candle-title market-candle-desc"/);
  assert.match(html, /<title id="market-candle-title">시세 캔들 차트<\/title>/);
  assert.match(html, /<desc id="market-candle-desc">3개 제공 캔들 중 2개를 시간순으로 표시합니다<\/desc>/);
  assert.match(html, /aria-pressed="true"[^>]*>1분봉/);
  assert.match(html, /aria-pressed="false"[^>]*>일봉/);
  assert.match(html, /부분 데이터/);
  assert.match(html, /TOSS/);
  assert.match(html, /2026-08-02 09:01 KST/);
  assert.match(html, /candles\[2\]\.high/);

  assert.equal((html.match(/class="candle-wick/g) ?? []).length, 2);
  assert.equal((html.match(/class="candle-body/g) ?? []).length, 2);
  assert.equal((html.match(/class="volume-bar/g) ?? []).length, 2);
  assert.match(html, /candle-down[^>]*>하락/);
  assert.match(html, /candle-up[^>]*>상승/);
  assert.match(html, /거래량 미제공/);
  assert.match(html, /확인 필요/);

  assert.ok(html.indexOf("2026-07-31") < html.indexOf("2026-08-01"));
  assert.ok(html.indexOf("2026-08-01") < html.indexOf("2026-08-02"));
  assert.doesNotMatch(html, /READY/);
});

test("does not draw malformed candles and does not mutate provider order", () => {
  const candles = [
    { date: "newest", open: 1, high: 2, low: 1, close: 2, volume: 5 },
    { date: "bad", open: "no", high: 2, low: 1, close: 2, volume: 4 },
    { date: "oldest", open: 3, high: 4, low: 2, close: 1, volume: "bad" }
  ];

  const html = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope: { status: "AVAILABLE", data: { candles } },
    interval: "1d",
    onIntervalChange() {}
  }));

  assert.deepEqual(candles.map(candle => candle.date), ["newest", "bad", "oldest"]);
  assert.equal((html.match(/class="candle-wick/g) ?? []).length, 2);
  assert.equal((html.match(/class="volume-bar/g) ?? []).length, 2);
  assert.ok(html.indexOf("oldest") < html.indexOf("bad"));
  assert.ok(html.indexOf("bad") < html.indexOf("newest"));
});

test("status priority hides chart for unavailable or error and empty normal stays empty", () => {
  const unavailable = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope: { status: "UNAVAILABLE", data: { candles: [{ open: 1, high: 2, low: 1, close: 2 }] } }
  }));
  const empty = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope: { status: "AVAILABLE", data: { candles: [] } }
  }));

  assert.match(unavailable, /차트 데이터를 불러오지 못했습니다/);
  assert.doesNotMatch(unavailable, /<svg/);
  assert.match(empty, /차트 데이터가 없습니다/);
  assert.doesNotMatch(empty, /<svg/);
});
