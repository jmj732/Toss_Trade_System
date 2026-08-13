"use client";

import { createElement as h, useId } from "react";
import { formatInstant, UNKNOWN_TEXT } from "../lib/format.js";

export const CANDLE_INTERVALS = [
  { key: "1m", label: "1분봉" },
  { key: "1d", label: "일봉" }
];

const CHART = { width: 360, height: 140, volumeHeight: 36 };

function finite(value) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function nonNegativeFinite(value) {
  const number = finite(value);
  return number !== null && number >= 0 ? number : null;
}

function idPart(value) {
  return String(value || "unknown").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "unknown";
}

function intervalText(interval) {
  const option = CANDLE_INTERVALS.find(entry => entry.key === interval);
  return `${option?.label ?? interval}(${interval})`;
}

function candleField(candle, field) {
  return candle[`${field}Price`] ?? candle[field];
}

function candleNumbers(candle) {
  return {
    open: nonNegativeFinite(candleField(candle, "open")),
    high: nonNegativeFinite(candleField(candle, "high")),
    low: nonNegativeFinite(candleField(candle, "low")),
    close: nonNegativeFinite(candleField(candle, "close")),
    volume: nonNegativeFinite(candle.volume)
  };
}

function usablePrice(candle) {
  const numbers = candleNumbers(candle);
  const prices = [numbers.open, numbers.high, numbers.low, numbers.close];
  return prices.every(value => value !== null)
    && numbers.high >= Math.max(numbers.open, numbers.close, numbers.low)
    && numbers.low <= Math.min(numbers.open, numbers.close, numbers.high)
    ? numbers
    : null;
}

function orderedCandles(envelope) {
  const candles = envelope?.data?.candles;
  return Array.isArray(candles) ? [...candles].reverse() : [];
}

function derivedStatus(envelope, rowCount) {
  if (envelope?.status === "ERROR" || envelope?.status === "UNAVAILABLE") return envelope.status;
  if (envelope?.status === "DEGRADED") return "DEGRADED";
  return rowCount > 0 ? "READY" : "EMPTY";
}

function StatusNote({ envelope, status, symbol }) {
  if (!envelope || envelope.status === "LOADING") {
    return h("p", { className: "busy", role: "status" }, "차트를 불러오는 중…");
  }
  if (!symbol) {
    return h("p", { className: "empty" }, "보유 종목이 없어 차트를 표시할 수 없습니다");
  }
  if (status === "ERROR" || status === "UNAVAILABLE") {
    return h("p", { className: "empty", role: "alert" },
      `차트 데이터를 불러오지 못했습니다${envelope.unavailableReason ? ` (${envelope.unavailableReason})` : ""}`);
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
  const provenance = Array.isArray(envelope?.provenance) ? envelope.provenance : [];
  const sourceText = provenance.map(item => {
    const asOf = item?.asOf ? ` · 기준 ${formatInstant(item.asOf)}` : "";
    return item?.provider ? `출처 ${item.provider}${asOf}` : null;
  }).filter(Boolean).join(" · ");
  const asOf = envelope?.asOf ?? provenance[0]?.asOf;
  return h("div", { className: "metric-freshness" },
    sourceText ? h("small", null, sourceText) : null,
    asOf && !sourceText ? h("small", null, `기준 ${formatInstant(asOf)}`) : null,
    envelope?.unknownFields?.length
      ? h("small", null, `누락 필드: ${envelope.unknownFields.join(", ")}`)
      : null);
}

function CandleSvg({ rows, drawable, symbol, interval }) {
  const instanceId = `market-candle-${idPart(symbol)}-${idPart(interval)}-${idPart(useId())}`;
  const titleId = `${instanceId}-title`;
  const descId = `${instanceId}-desc`;
  const chartName = `${symbol || "UNKNOWN"} ${intervalText(interval)}`;
  const prices = drawable.flatMap(({ values }) => [values.high, values.low]);
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  const span = max - min || 1;
  const maxVolume = Math.max(...rows.map(candle => candleNumbers(candle).volume ?? 0), 1);
  const step = CHART.width / Math.max(rows.length, 1);
  const priceY = value => ((max - value) / span) * CHART.height;
  const volumeY = value => CHART.height + CHART.volumeHeight - (value / maxVolume) * CHART.volumeHeight;
  const volumeTop = CHART.height + 8;

  return h("svg", {
    className: "market-candle-svg",
    role: "img",
    "aria-labelledby": `${titleId} ${descId}`,
    viewBox: `0 0 ${CHART.width} ${CHART.height + CHART.volumeHeight + 8}`
  },
  h("title", { id: titleId }, `${chartName} 시세 캔들 차트`),
  h("desc", { id: descId },
    `${chartName} ${rows.length}개 제공 캔들 중 ${drawable.length}개를 시간순으로 표시합니다`),
  ...rows.map((candle, index) => {
    const volume = candleNumbers(candle).volume;
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

export function MarketCandleChart({ envelope, symbol = "UNKNOWN", interval = "1d", onIntervalChange }) {
  const rows = orderedCandles(envelope);
  const drawable = rows
    .map((candle, index) => ({ index, values: usablePrice(candle) }))
    .filter(entry => entry.values);
  const status = derivedStatus(envelope, rows.length);
  const showChart = Boolean(symbol) && (status === "READY" || status === "DEGRADED")
    && rows.length > 0 && drawable.length > 0;

  return h("section", { className: "panel market-candle-chart" },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "Toss OpenAPI 캔들"),
        h("h2", null, "시세 차트")),
      h("div", { className: "panel-actions" },
        ...CANDLE_INTERVALS.map(option =>
          h("button", {
            key: option.key,
            type: "button",
            className: interval === option.key ? "primary" : "secondary",
            "aria-pressed": interval === option.key,
            onClick: () => onIntervalChange?.(option.key)
          }, option.label)))),
    h(StatusNote, { envelope, status, symbol }),
    showChart ? h(CandleSvg, { rows, drawable, symbol, interval }) : null,
    h(Metadata, { envelope }));
}
