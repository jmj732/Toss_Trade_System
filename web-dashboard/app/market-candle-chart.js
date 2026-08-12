"use client";

import { createElement as h } from "react";
import { formatInstant, UNKNOWN_TEXT } from "../lib/format.js";

export const CANDLE_INTERVALS = [
  { value: "1m", label: "1분봉" },
  { value: "1d", label: "일봉" }
];

const CHART = { width: 360, height: 140, volumeHeight: 36 };

function finite(value) {
  if (value === null || value === undefined || value === "") return null;
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) ? number : null;
}

function candleTime(candle) {
  return candle.timestamp ?? candle.asOf ?? candle.date ?? candle.time ?? UNKNOWN_TEXT;
}

function displayTime(candle) {
  if (candle.timestamp) return formatInstant(candle.timestamp);
  if (candle.asOf) return formatInstant(candle.asOf);
  return candle.date ?? candle.time ?? UNKNOWN_TEXT;
}

function displayNumber(value) {
  const number = finite(value);
  return number === null ? UNKNOWN_TEXT : number.toLocaleString("ko-KR");
}

function candleNumbers(candle) {
  return {
    open: finite(candle.open),
    high: finite(candle.high),
    low: finite(candle.low),
    close: finite(candle.close),
    volume: finite(candle.volume)
  };
}

function usablePrice(candle) {
  const numbers = candleNumbers(candle);
  return [numbers.open, numbers.high, numbers.low, numbers.close].every(value => value !== null)
    ? numbers
    : null;
}

function orderedCandles(envelope) {
  const candles = envelope?.data?.candles;
  return Array.isArray(candles) ? [...candles].reverse() : [];
}

function derivedStatus(envelope, drawableCount, rowCount) {
  if (envelope?.status === "ERROR" || envelope?.status === "UNAVAILABLE") return envelope.status;
  if (envelope?.status === "DEGRADED" && drawableCount > 0) return "DEGRADED";
  return rowCount > 0 ? "READY" : "EMPTY";
}

function StatusNote({ envelope, status }) {
  if (status === "ERROR" || status === "UNAVAILABLE") {
    return h("p", { className: "empty" }, "차트 데이터를 불러오지 못했습니다");
  }
  if (status === "DEGRADED") {
    return h("p", { className: "busy", role: "status" }, "부분 데이터");
  }
  if (status === "EMPTY") {
    return h("p", { className: "empty" }, "차트 데이터가 없습니다");
  }
  return envelope?.status ? h("p", { className: "sr-only" }, `원본 상태 ${envelope.status}`) : null;
}

function Metadata({ envelope }) {
  return h("div", { className: "metric-freshness" },
    envelope?.provenance ? h("small", null, envelope.provenance) : null,
    envelope?.asOf ? h("small", null, formatInstant(envelope.asOf)) : null,
    envelope?.unknownFields?.length
      ? h("small", null, `누락 필드: ${envelope.unknownFields.join(", ")}`)
      : null);
}

function CandleSvg({ rows, drawable }) {
  const prices = drawable.flatMap(({ values }) => [values.high, values.low]);
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  const span = max - min || 1;
  const maxVolume = Math.max(...rows.map(candle => finite(candle.volume) ?? 0), 1);
  const step = CHART.width / Math.max(rows.length, 1);
  const priceY = value => ((max - value) / span) * CHART.height;
  const volumeY = value => CHART.height + CHART.volumeHeight - (value / maxVolume) * CHART.volumeHeight;
  const volumeTop = CHART.height + 8;

  return h("svg", {
    className: "market-candle-svg",
    role: "img",
    "aria-labelledby": "market-candle-title market-candle-desc",
    viewBox: `0 0 ${CHART.width} ${CHART.height + CHART.volumeHeight + 8}`
  },
  h("title", { id: "market-candle-title" }, "시세 캔들 차트"),
  h("desc", { id: "market-candle-desc" },
    `${rows.length}개 제공 캔들 중 ${drawable.length}개를 시간순으로 표시합니다`),
  ...rows.map((candle, index) => {
    const volume = finite(candle.volume);
    if (volume === null) return null;
    const x = index * step + step * 0.2;
    const width = step * 0.6;
    return h("rect", {
      key: `volume-${index}`,
      className: "volume-bar",
      x: x.toFixed(2),
      y: Math.max(volumeTop, volumeY(volume)).toFixed(2),
      width: width.toFixed(2),
      height: (CHART.height + CHART.volumeHeight - Math.max(volumeTop, volumeY(volume))).toFixed(2)
    });
  }),
  ...drawable.flatMap(({ index, values }) => {
    const x = index * step + step / 2;
    const bodyTop = Math.min(priceY(values.open), priceY(values.close));
    const bodyHeight = Math.max(Math.abs(priceY(values.open) - priceY(values.close)), 1);
    const direction = values.close >= values.open ? "up" : "down";
    return [
      h("line", {
        key: `wick-${index}`,
        className: `candle-wick candle-${direction}`,
        x1: x.toFixed(2),
        x2: x.toFixed(2),
        y1: priceY(values.high).toFixed(2),
        y2: priceY(values.low).toFixed(2)
      }),
      h("rect", {
        key: `body-${index}`,
        className: `candle-body candle-${direction}`,
        x: (index * step + step * 0.25).toFixed(2),
        y: bodyTop.toFixed(2),
        width: (step * 0.5).toFixed(2),
        height: bodyHeight.toFixed(2)
      })
    ];
  }));
}

function CandleTable({ rows }) {
  return h("div", {
    className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "캔들 숫자 표"
  }, h("table", null,
    h("thead", null, h("tr", null,
      ...["시각", "시가", "고가", "저가", "종가", "등락", "거래량"].map(label =>
        h("th", { key: label, scope: "col" }, label)))),
    h("tbody", null, ...rows.map((candle, index) => {
      const values = candleNumbers(candle);
      const comparable = values.open !== null && values.close !== null;
      const delta = comparable ? values.close - values.open : null;
      const direction = delta === null ? null : delta >= 0 ? "up" : "down";
      return h("tr", { key: `${candleTime(candle)}-${index}` },
        h("td", null, displayTime(candle)),
        h("td", null, displayNumber(candle.open)),
        h("td", null, displayNumber(candle.high)),
        h("td", null, displayNumber(candle.low)),
        h("td", null, displayNumber(candle.close)),
        h("td", { className: direction ? `candle-${direction}` : undefined },
          delta === null ? UNKNOWN_TEXT : `${delta >= 0 ? "상승" : "하락"} ${delta >= 0 ? "+" : ""}${displayNumber(delta)}`),
        h("td", null, values.volume === null ? "거래량 미제공" : values.volume.toLocaleString("ko-KR")));
    }))));
}

export function MarketCandleChart({ envelope, interval = "1d", onIntervalChange }) {
  const rows = orderedCandles(envelope);
  const drawable = rows
    .map((candle, index) => ({ index, values: usablePrice(candle) }))
    .filter(entry => entry.values);
  const status = derivedStatus(envelope, drawable.length, rows.length);
  const showChart = status === "READY" || status === "DEGRADED";

  return h("section", { className: "panel market-candle-chart" },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "Toss OpenAPI 캔들"),
        h("h2", null, "시세 차트")),
      h("div", { className: "panel-actions" },
        ...CANDLE_INTERVALS.map(option =>
          h("button", {
            key: option.value,
            type: "button",
            className: interval === option.value ? "primary" : "secondary",
            "aria-pressed": interval === option.value,
            onClick: () => onIntervalChange?.(option.value)
          }, option.label)))),
    h(StatusNote, { envelope, status }),
    showChart ? h(CandleSvg, { rows, drawable }) : null,
    showChart ? h(CandleTable, { rows }) : null,
    h(Metadata, { envelope }));
}
