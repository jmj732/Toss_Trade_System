package com.jmj.trade.monitoring;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.MarketDataAdapter;
import com.jmj.trade.broker.Quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only quote and daily-candle metrics for monitoring watchlist symbols.
 * Watchlist volume and relative strength require a current US-session candle; older daily data stays unknown.
 */
public final class MonitoringQuoteAdapter {

    private static final String BENCHMARK = "SPY";
    private static final String SOURCE = "BrokerAdapter.getQuote + MarketDataAdapter.getCandles(1d,adjusted)";
    private static final String RATIO_SOURCE = "MarketDataAdapter.getCandles(1d,adjusted)";
    private static final int VOLUME_WINDOW = 20;
    private static final int RETURN_WINDOW = 5;
    private static final int SCALE = 8;
    private static final int RATIO_WINDOW = 21;
    private static final Duration LIVE_SOURCE_MAX_AGE = Duration.ofMinutes(30);
    private static final ZoneId US_MARKET_ZONE = ZoneId.of("America/New_York");
    private static final List<RatioDefinition> MARKET_RATIOS = List.of(
            new RatioDefinition("equity.rsp_spy", "RSP", "SPY"),
            new RatioDefinition("equity.iwm_spy", "IWM", "SPY"),
            new RatioDefinition("equity.soxx_spy", "SOXX", "SPY"),
            new RatioDefinition("credit.hyg_lqd", "HYG", "LQD"),
            new RatioDefinition("credit.kre_xlf", "KRE", "XLF"));

    private final BrokerAdapter broker;
    private final MarketDataAdapter marketData;
    private final BrokerConnectionRef connection;
    private final Clock clock;

    public MonitoringQuoteAdapter(
            BrokerAdapter broker,
            MarketDataAdapter marketData,
            BrokerConnectionRef connection,
            Clock clock) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.marketData = Objects.requireNonNull(marketData, "marketData");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Snapshot snapshot(String symbol) {
        var normalized = Objects.requireNonNull(symbol, "symbol").trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("symbol must not be blank");

        var quote = quote(normalized);
        var candles = candles(normalized);
        var benchmark = normalized.equals(BENCHMARK) ? candles : candles(BENCHMARK);
        var collectedAt = clock.instant();
        var sessionDate = LocalDate.ofInstant(collectedAt, US_MARKET_ZONE);
        var currentSessionCandles = hasCurrentSession(candles, sessionDate)
                && sourceIsFresh(candles.observedAt(), collectedAt);
        var currentBenchmarkCandles = hasCurrentSession(benchmark, sessionDate)
                && sourceIsFresh(benchmark.observedAt(), collectedAt);
        var price = quote.filter(value -> sourceIsFresh(
                        value.brokerTimestamp() == null ? value.observedAt() : value.brokerTimestamp(), collectedAt))
                .map(Quote::lastPrice).filter(value -> value.signum() > 0).orElse(null);
        var volumeMultiple = currentSessionCandles ? volumeMultiple(candles.values()) : null;
        var relativeStrength = normalized.equals(BENCHMARK) || !currentSessionCandles || !currentBenchmarkCandles
                ? null : relativeStrength(candles.values(), benchmark.values());

        var asOf = new ArrayList<Instant>();
        quote.map(value -> value.brokerTimestamp() == null ? value.observedAt() : value.brokerTimestamp())
                .ifPresent(asOf::add);
        if (candles.observedAt() != null) asOf.add(candles.observedAt());
        if (!normalized.equals(BENCHMARK) && benchmark.observedAt() != null) asOf.add(benchmark.observedAt());
        var timestamp = asOf.stream().min(Instant::compareTo).orElse(null);

        return new Snapshot(normalized, price, volumeMultiple, relativeStrength, timestamp,
                collectedAt, SOURCE);
    }

    /** Returns bounded daily ratio history; absent or stale sources produce null values. */
    public List<MonitoringEvaluationContract.MetricSeries> marketRatios() {
        var candlesBySymbol = new HashMap<String, CandleRead>();
        for (var ratio : MARKET_RATIOS) {
            candlesBySymbol.computeIfAbsent(ratio.numerator(), this::candles);
            candlesBySymbol.computeIfAbsent(ratio.denominator(), this::candles);
        }
        var collectedAt = clock.instant();
        return MARKET_RATIOS.stream().map(ratio -> new MonitoringEvaluationContract.MetricSeries(
                ratio.metric(), "ratio", RATIO_SOURCE, "DAILY",
                ratioPoints(candlesBySymbol.get(ratio.numerator()), candlesBySymbol.get(ratio.denominator()),
                        collectedAt))).toList();
    }

    private Optional<Quote> quote(String symbol) {
        try {
            var response = broker.getQuote(connection, symbol);
            if (response == null || response.value() == null) return Optional.empty();
            var quote = response.value();
            return connection.equals(quote.connection()) && quote.symbol().equalsIgnoreCase(symbol)
                    ? Optional.of(quote) : Optional.empty();
        } catch (BrokerException ignored) {
            return Optional.empty();
        }
    }

    private CandleRead candles(String symbol) {
        try {
            var response = marketData.getCandles(connection, symbol, "1d", VOLUME_WINDOW + 1, null, true);
            if (response == null || response.value() == null) return CandleRead.empty();
            var series = response.value();
            if (!symbol.equalsIgnoreCase(series.symbol())
                    || !"1d".equals(series.interval()) || !series.adjusted()) return CandleRead.empty();
            var candles = series.candles().stream()
                    .filter(Objects::nonNull)
                    .filter(candle -> candle.timestamp() != null)
                    .sorted((left, right) -> left.timestamp().compareTo(right.timestamp()))
                    .toList();
            return new CandleRead(candles, response.metadata().observedAt());
        } catch (BrokerException ignored) {
            return CandleRead.empty();
        }
    }

    private static boolean hasCurrentSession(CandleRead data, LocalDate sessionDate) {
        return data.values().stream().map(MarketDataAdapter.Candle::timestamp)
                .max(Instant::compareTo)
                .map(timestamp -> LocalDate.ofInstant(timestamp, US_MARKET_ZONE).equals(sessionDate))
                .orElse(false);
    }

    private static BigDecimal volumeMultiple(List<MarketDataAdapter.Candle> candles) {
        if (candles.size() < VOLUME_WINDOW + 1) return null;
        var recent = candles.subList(candles.size() - VOLUME_WINDOW - 1, candles.size());
        var current = recent.get(recent.size() - 1).volume();
        if (current == null || current.signum() < 0) return null;
        var previousVolume = BigDecimal.ZERO;
        for (var index = 0; index < VOLUME_WINDOW; index++) {
            var volume = recent.get(index).volume();
            if (volume == null || volume.signum() < 0) return null;
            previousVolume = previousVolume.add(volume);
        }
        if (previousVolume.signum() == 0) return null;
        // ponytail: compares cumulative in-session volume to full-session history; use an intraday baseline if added.
        return current.multiply(BigDecimal.valueOf(VOLUME_WINDOW))
                .divide(previousVolume, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal relativeStrength(
            List<MarketDataAdapter.Candle> symbolCandles,
            List<MarketDataAdapter.Candle> benchmarkCandles) {
        var symbolCloses = closes(symbolCandles);
        var benchmarkCloses = closes(benchmarkCandles);
        var commonDates = symbolCloses.keySet().stream().filter(benchmarkCloses::containsKey).toList();
        if (commonDates.size() < RETURN_WINDOW + 1) return null;

        var first = commonDates.get(commonDates.size() - RETURN_WINDOW - 1);
        var last = commonDates.get(commonDates.size() - 1);
        var symbolStart = symbolCloses.get(first);
        var symbolEnd = symbolCloses.get(last);
        var benchmarkStart = benchmarkCloses.get(first);
        var benchmarkEnd = benchmarkCloses.get(last);
        if (symbolStart.signum() <= 0 || benchmarkStart.signum() <= 0) return null;

        var symbolReturn = symbolEnd.subtract(symbolStart).divide(symbolStart, SCALE, RoundingMode.HALF_UP);
        var benchmarkReturn = benchmarkEnd.subtract(benchmarkStart)
                .divide(benchmarkStart, SCALE, RoundingMode.HALF_UP);
        return symbolReturn.subtract(benchmarkReturn);
    }

    private static TreeMap<LocalDate, BigDecimal> closes(List<MarketDataAdapter.Candle> candles) {
        var closes = new TreeMap<LocalDate, BigDecimal>();
        for (var candle : candles) {
            if (candle.closePrice() != null && candle.closePrice().signum() > 0) {
                closes.put(LocalDate.ofInstant(candle.timestamp(), US_MARKET_ZONE), candle.closePrice());
            }
        }
        return closes;
    }

    private static List<MonitoringEvaluationContract.MetricPoint> ratioPoints(
            CandleRead numerator,
            CandleRead denominator,
            Instant collectedAt) {
        var numeratorByDate = candlesByDate(numerator.values());
        var denominatorByDate = candlesByDate(denominator.values());
        var dates = new TreeSet<LocalDate>();
        dates.addAll(numeratorByDate.keySet());
        dates.addAll(denominatorByDate.keySet());
        var recentDates = dates.stream().skip(Math.max(0, dates.size() - RATIO_WINDOW)).toList();
        var points = new ArrayList<MonitoringEvaluationContract.MetricPoint>(recentDates.size());
        var sourceCollectedAt = sourceObservedAt(numerator, denominator);
        var sourceFresh = sourceIsFresh(sourceCollectedAt, collectedAt);
        for (var date : recentDates) {
            var numeratorCandle = numeratorByDate.get(date);
            var denominatorCandle = denominatorByDate.get(date);
            var asOf = sourceTimestamp(numeratorCandle, denominatorCandle);
            var numeratorClose = numeratorCandle == null ? null : numeratorCandle.closePrice();
            var denominatorClose = denominatorCandle == null ? null : denominatorCandle.closePrice();
            var value = !sourceFresh || numeratorClose == null || numeratorClose.signum() <= 0
                    || denominatorClose == null || denominatorClose.signum() <= 0
                    ? null : numeratorClose.divide(denominatorClose, SCALE, RoundingMode.HALF_UP);
            points.add(new MonitoringEvaluationContract.MetricPoint(
                    value == null ? null : value.stripTrailingZeros().toPlainString(), asOf,
                    sourceCollectedAt == null ? collectedAt : sourceCollectedAt));
        }
        return List.copyOf(points);
    }

    private static Instant sourceObservedAt(CandleRead numerator, CandleRead denominator) {
        if (numerator.observedAt() == null) return denominator.observedAt();
        if (denominator.observedAt() == null) return numerator.observedAt();
        return numerator.observedAt().isBefore(denominator.observedAt())
                ? numerator.observedAt() : denominator.observedAt();
    }

    private static boolean sourceIsFresh(Instant observedAt, Instant collectedAt) {
        return observedAt != null && !observedAt.isAfter(collectedAt)
                && Duration.between(observedAt, collectedAt).compareTo(LIVE_SOURCE_MAX_AGE) <= 0;
    }

    private static TreeMap<LocalDate, MarketDataAdapter.Candle> candlesByDate(
            List<MarketDataAdapter.Candle> candles) {
        var byDate = new TreeMap<LocalDate, MarketDataAdapter.Candle>();
        for (var candle : candles) {
            byDate.put(LocalDate.ofInstant(candle.timestamp(), US_MARKET_ZONE), candle);
        }
        return byDate;
    }

    private static Instant sourceTimestamp(
            MarketDataAdapter.Candle numerator,
            MarketDataAdapter.Candle denominator) {
        if (numerator == null) return denominator.timestamp();
        if (denominator == null) return numerator.timestamp();
        return numerator.timestamp().isBefore(denominator.timestamp())
                ? numerator.timestamp() : denominator.timestamp();
    }

    private record CandleRead(List<MarketDataAdapter.Candle> values, Instant observedAt) {
        private CandleRead {
            values = List.copyOf(values);
        }

        private static CandleRead empty() {
            return new CandleRead(List.of(), null);
        }
    }

    private record RatioDefinition(String metric, String numerator, String denominator) {
    }

    public record Snapshot(
            String symbol,
            BigDecimal price,
            BigDecimal volumeMultiple,
            BigDecimal relativeStrength,
            Instant asOf,
            Instant collectedAt,
            String source) {
    }
}
