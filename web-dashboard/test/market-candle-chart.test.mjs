import assert from "node:assert/strict";
import test from "node:test";
import { Fragment, createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { CANDLE_INTERVALS, MarketCandleChart } from "../app/market-candle-chart.js";

test("exports only Toss candle intervals without changing API values", () => {
  assert.deepEqual(CANDLE_INTERVALS, [
    { key: "1m", label: "1분봉" },
    { key: "1d", label: "일봉" }
  ]);
});

test("interval buttons expose API keys to the renderer callback", () => {
  const calls = [];
  const tree = MarketCandleChart({
    envelope: { status: "AVAILABLE", data: { candles: [] } },
    interval: "1m",
    onIntervalChange: interval => calls.push(interval)
  });
  const buttons = tree.props.children[0].props.children[1].props.children;

  assert.equal(buttons[0].key, "1m");
  assert.equal(buttons[0].props["aria-pressed"], true);
  assert.equal(buttons[1].key, "1d");
  assert.equal(buttons[1].props["aria-pressed"], false);

  buttons[1].props.onClick();
  assert.deepEqual(calls, ["1d"]);
});

test("renders canonical backend OHLC fields in svg geometry and table", () => {
  const html = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope: {
      status: "AVAILABLE",
      data: {
        candles: [
          {
            timestamp: "2026-08-02T00:00:00Z",
            open: 1,
            high: 2,
            low: 1,
            close: 2,
            openPrice: 100,
            highPrice: 120,
            lowPrice: 90,
            closePrice: 110,
            volume: 1000
          }
        ]
      }
    },
    interval: "1d",
    onIntervalChange() {}
  }));

  assert.equal((html.match(/class="candle-wick/g) ?? []).length, 1);
  assert.equal((html.match(/class="candle-body/g) ?? []).length, 1);
  assert.match(html, /100/);
  assert.match(html, /120/);
  assert.match(html, /90/);
  assert.match(html, /110/);
  assert.doesNotMatch(html, />1<\/td>/);
  assert.doesNotMatch(html, />2<\/td>/);
});

test("renders degraded usable candles chronologically with accessible svg, table, and raw metadata", () => {
  const envelope = {
    status: "DEGRADED",
    data: {
      candles: [
        { timestamp: "2026-08-02T00:00:00Z", open: 10, high: 12, low: 9, close: 11, volume: 100 },
        { timestamp: "2026-08-01T00:00:00Z", open: 8, high: 10, low: 5, close: 6, volume: null },
        { timestamp: "2026-07-31T00:00:00Z", open: null, high: "x", low: 5, close: 6, volume: 70 }
      ]
    },
    provenance: [{ provider: "TOSS", endpoint: "/api/v1/candles", asOf: "2026-08-02T00:01:00Z" }],
    unknownFields: ["candles[2].high"]
  };

  const html = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope,
    interval: "1m",
    onIntervalChange() {}
  }));

  assert.match(html, /aria-labelledby="market-candle-unknown-1m-[^"]+-title market-candle-unknown-1m-[^"]+-desc"/);
  assert.match(html, /<title id="market-candle-unknown-1m-[^"]+-title">UNKNOWN 1분봉\(1m\) 시세 캔들 차트<\/title>/);
  assert.match(html, /<desc id="market-candle-unknown-1m-[^"]+-desc">UNKNOWN 1분봉\(1m\) 3개 제공 캔들 중 2개를 시간순으로 표시합니다<\/desc>/);
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

test("preserves array provenance and degraded empty status without crashing or inventing ready data", () => {
  const html = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope: {
      status: "DEGRADED",
      unknownFields: ["candles"],
      provenance: [{ provider: "TOSS", endpoint: "/api/v1/candles", asOf: "2026-08-12T01:00:00Z" }],
      data: { candles: [] }
    },
    symbol: "AAPL",
    interval: "1m"
  }));

  assert.match(html, /부분 데이터/);
  assert.match(html, /출처 TOSS/);
  assert.match(html, /기준/);
  assert.match(html, /누락 필드: candles/);
  assert.doesNotMatch(html, /market-candle-svg/);
});

test("svg title and description ids are unique and name the symbol interval", () => {
  const envelope = {
    status: "AVAILABLE",
    data: {
      candles: [
        { timestamp: "2026-08-02T00:00:00Z", open: 10, high: 12, low: 9, close: 11, volume: 100 }
      ]
    }
  };
  const html = renderToStaticMarkup(createElement(Fragment, null,
    createElement(MarketCandleChart, { envelope, symbol: "AAPL", interval: "1m" }),
    createElement(MarketCandleChart, { envelope, symbol: "MSFT", interval: "1d" })
  ));
  const labelledBy = [...html.matchAll(/aria-labelledby="([^"]+)"/g)].map(match => match[1].split(" "));
  const titleIds = labelledBy.map(([titleId]) => titleId);
  const descIds = labelledBy.map(([, descId]) => descId);

  assert.equal(labelledBy.length, 2);
  assert.equal(new Set(titleIds).size, 2);
  assert.equal(new Set(descIds).size, 2);
  for (const [titleId, descId] of labelledBy) {
    assert.match(html, new RegExp(`<title id="${titleId}">[^<]+</title>`));
    assert.match(html, new RegExp(`<desc id="${descId}">[^<]+</desc>`));
  }
  assert.match(html, /<title id="[^"]+">AAPL 1분봉\(1m\) 시세 캔들 차트<\/title>/);
  assert.match(html, /<desc id="[^"]+">AAPL 1분봉\(1m\) 1개 제공 캔들 중 1개를 시간순으로 표시합니다<\/desc>/);
  assert.match(html, /<title id="[^"]+">MSFT 일봉\(1d\) 시세 캔들 차트<\/title>/);
  assert.match(html, /<desc id="[^"]+">MSFT 일봉\(1d\) 1개 제공 캔들 중 1개를 시간순으로 표시합니다<\/desc>/);
});

test("does not draw malformed candles and does not mutate provider order", () => {
  const candles = [
    { date: "newest", open: 1, high: 2, low: 1, close: 2, volume: 5 },
    { date: "bad", open: "no", high: 2, low: 1, close: 2, volume: 4 },
    { date: "oldest", open: 3, high: 4, low: 1, close: 1, volume: "bad" }
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

test("does not draw impossible or negative OHLC and treats negative volume as missing", () => {
  const html = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope: {
      status: "AVAILABLE",
      data: {
        candles: [
          { date: "negative-volume", open: 10, high: 12, low: 9, close: 11, volume: -1 },
          { date: "negative-price", open: -10, high: 12, low: 9, close: 11, volume: 10 },
          { date: "low-above-values", open: 10, high: 12, low: 13, close: 11, volume: 10 },
          { date: "high-below-values", open: 10, high: 9, low: 8, close: 11, volume: 10 },
          { date: "valid", open: 10, high: 12, low: 9, close: 11, volume: 20 }
        ]
      }
    }
  }));

  assert.equal((html.match(/class="candle-wick/g) ?? []).length, 2);
  assert.equal((html.match(/class="candle-body/g) ?? []).length, 2);
  assert.equal((html.match(/class="volume-bar/g) ?? []).length, 4);
  assert.match(html, /negative-volume/);
  assert.match(html, /거래량 미제공/);
});

test("treats numeric string OHLC as malformed for candle geometry but keeps raw table values", () => {
  const html = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope: {
      status: "AVAILABLE",
      data: {
        candles: [
          { date: "numeric-string", open: "10.50", high: "12.25", low: "9.75", close: "11.00", volume: 5 }
        ]
      }
    },
    interval: "1d",
    onIntervalChange() {}
  }));

  assert.match(html, /numeric-string/);
  assert.match(html, /10\.50/);
  assert.match(html, /12\.25/);
  assert.match(html, /9\.75/);
  assert.match(html, /11\.00/);
  assert.doesNotMatch(html, /class="candle-wick/);
  assert.doesNotMatch(html, /class="candle-body/);
  assert.doesNotMatch(html, /<svg/);
});

test("keeps degraded warning and hides chart when rows have zero usable OHLC", () => {
  const html = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope: {
      status: "DEGRADED",
      data: {
        candles: [
          { date: "bad-1", open: "10", high: "11", low: "9", close: "10", volume: 5 },
          { date: "bad-2", open: null, high: 12, low: 8, close: 10, volume: 7 }
        ]
      }
    }
  }));

  assert.match(html, /부분 데이터/);
  assert.doesNotMatch(html, /READY/);
  assert.doesNotMatch(html, /<svg/);
  assert.doesNotMatch(html, /class="candle-wick/);
  assert.doesNotMatch(html, /class="candle-body/);
});

test("status priority hides chart for unavailable or error and empty normal stays empty", () => {
  const unavailable = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope: { status: "UNAVAILABLE", unavailableReason: "PROVIDER_UNSUPPORTED", data: { candles: [{ open: 1, high: 2, low: 1, close: 2 }] } }
  }));
  const empty = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope: { status: "AVAILABLE", data: { candles: [] } }
  }));

  assert.match(unavailable, /차트 데이터를 불러오지 못했습니다/);
  assert.match(unavailable, /PROVIDER_UNSUPPORTED/);
  assert.doesNotMatch(unavailable, /<svg/);
  assert.match(empty, /차트 데이터가 없습니다/);
  assert.doesNotMatch(empty, /<svg/);
});

test("shows loading and no-holdings states without claiming empty provider data", () => {
  const loading = renderToStaticMarkup(createElement(MarketCandleChart, { envelope: null, symbol: "AAPL" }));
  const noHoldings = renderToStaticMarkup(createElement(MarketCandleChart, {
    envelope: { status: "AVAILABLE", data: { candles: [] } },
    symbol: ""
  }));

  assert.match(loading, /차트를 불러오는 중/);
  assert.match(noHoldings, /보유 종목이 없어 차트를 표시할 수 없습니다/);
  assert.doesNotMatch(loading, /차트 데이터가 없습니다/);
  assert.doesNotMatch(noHoldings, /차트 데이터가 없습니다/);
});
