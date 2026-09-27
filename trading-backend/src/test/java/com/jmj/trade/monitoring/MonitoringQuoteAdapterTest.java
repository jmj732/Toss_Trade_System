package com.jmj.trade.monitoring;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.MarketDataAdapter;
import com.jmj.trade.broker.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonitoringQuoteAdapterTest {

    private static final UUID CONNECTION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    private static final BrokerConnectionRef CONNECTION = new BrokerConnectionRef(CONNECTION_ID);
    private static final Instant COLLECTED_AT = Instant.parse("2026-09-28T14:00:00Z");

    @Test
    void calculatesVolumeMultipleAndFiveSessionRelativeReturnFromProviderData() {
        var broker = mock(BrokerAdapter.class);
        var marketData = mock(MarketDataAdapter.class);
        when(broker.getQuote(any(), eq("AAPL"))).thenReturn(new BrokerResponse<>(
                new Quote(CONNECTION, "AAPL", Currency.USD, new BigDecimal("220"), null, null,
                        Instant.parse("2026-09-28T13:59:00Z"), COLLECTED_AT), metadata()));
        when(marketData.getCandles(any(), eq("AAPL"), eq("1d"), eq(21), eq(null), eq(true)))
                .thenReturn(new BrokerResponse<>(history("AAPL", "1", new BigDecimal("300")), metadata()));
        when(marketData.getCandles(any(), eq("SPY"), eq("1d"), eq(21), eq(null), eq(true)))
                .thenReturn(new BrokerResponse<>(shift(history("SPY", "0.5", new BigDecimal("100")), 60), metadata()));
        var clock = Clock.fixed(COLLECTED_AT, ZoneOffset.UTC);

        var snapshot = new MonitoringQuoteAdapter(broker, marketData, CONNECTION, clock).snapshot("AAPL");

        assertThat(snapshot.price()).isEqualByComparingTo("220");
        assertThat(snapshot.volumeMultiple()).isEqualByComparingTo("3");
        assertThat(snapshot.relativeStrength()).isEqualByComparingTo("0.025");
        assertThat(snapshot.asOf()).isEqualTo(Instant.parse("2026-09-28T13:59:00Z"));
        assertThat(snapshot.collectedAt()).isEqualTo(COLLECTED_AT);
        assertThat(snapshot.source()).contains("BrokerAdapter", "MarketDataAdapter");
    }

    @Test
    void leavesInsufficientCandleMetricsUnknownInsteadOfTreatingThemAsZero() {
        var broker = mock(BrokerAdapter.class);
        var marketData = mock(MarketDataAdapter.class);
        when(broker.getQuote(any(), eq("AAPL"))).thenReturn(new BrokerResponse<>(
                new Quote(CONNECTION, "AAPL", Currency.USD, new BigDecimal("220"), null, null,
                        null, COLLECTED_AT), metadata()));
        when(marketData.getCandles(any(), eq("AAPL"), eq("1d"), eq(21), eq(null), eq(true)))
                .thenReturn(new BrokerResponse<>(new MarketDataAdapter.CandleSeries(
                        "AAPL", "1d", true, List.of(candle(Instant.parse("2026-09-28T13:30:00Z"),
                        new BigDecimal("100"), new BigDecimal("0"))), null), metadata()));
        when(marketData.getCandles(any(), eq("SPY"), eq("1d"), eq(21), eq(null), eq(true)))
                .thenReturn(new BrokerResponse<>(new MarketDataAdapter.CandleSeries("SPY", "1d", true,
                        List.of(), null), metadata()));

        var snapshot = new MonitoringQuoteAdapter(broker, marketData, CONNECTION,
                Clock.fixed(COLLECTED_AT, ZoneOffset.UTC)).snapshot("AAPL");

        assertThat(snapshot.price()).isEqualByComparingTo("220");
        assertThat(snapshot.volumeMultiple()).isNull();
        assertThat(snapshot.relativeStrength()).isNull();
    }

    @Test
    void keepsDailyMetricsUnknownWhenProviderOnlyHasCompletedPriorSessionBars() {
        var broker = mock(BrokerAdapter.class);
        var marketData = mock(MarketDataAdapter.class);
        when(broker.getQuote(any(), eq("AAPL"))).thenReturn(new BrokerResponse<>(
                new Quote(CONNECTION, "AAPL", Currency.USD, new BigDecimal("220"), null, null,
                        Instant.parse("2026-09-28T13:59:00Z"), COLLECTED_AT), metadata()));
        when(marketData.getCandles(any(), eq("AAPL"), eq("1d"), eq(21), eq(null), eq(true)))
                .thenReturn(new BrokerResponse<>(shift(
                        history("AAPL", "1", new BigDecimal("300")), -3L * 86_400), metadata()));
        when(marketData.getCandles(any(), eq("SPY"), eq("1d"), eq(21), eq(null), eq(true)))
                .thenReturn(new BrokerResponse<>(shift(
                        history("SPY", "0.5", new BigDecimal("100")), -3L * 86_400), metadata()));

        var snapshot = new MonitoringQuoteAdapter(broker, marketData, CONNECTION,
                Clock.fixed(COLLECTED_AT, ZoneOffset.UTC)).snapshot("AAPL");

        assertThat(snapshot.price()).isEqualByComparingTo("220");
        assertThat(snapshot.volumeMultiple()).isNull();
        assertThat(snapshot.relativeStrength()).isNull();
    }

    @Test
    void suppliesTwentyOneDateAlignedRatioPointsForFiveTenAndTwentySessionChanges() {
        var marketData = mock(MarketDataAdapter.class);
        when(marketData.getCandles(any(), anyString(), eq("1d"), eq(21), eq(null), eq(true)))
                .thenAnswer(call -> new BrokerResponse<>(ratioHistory(call.getArgument(1)), marketMetadata()));

        var series = new MonitoringQuoteAdapter(mock(BrokerAdapter.class), marketData, CONNECTION,
                Clock.fixed(COLLECTED_AT, ZoneOffset.UTC)).marketRatios();

        assertThat(series).extracting(MonitoringEvaluationContract.MetricSeries::metric)
                .containsExactly("equity.rsp_spy", "equity.iwm_spy", "equity.soxx_spy",
                        "credit.hyg_lqd", "credit.kre_xlf");
        for (var metric : series) {
            var points = metric.points();
            assertThat(points).hasSize(21);
            assertThat(points.get(20).value()).isEqualTo("1.2");
            assertThat(points.get(20).asOf()).isEqualTo(Instant.parse("2026-09-28T13:30:00Z"));
            assertThat(points.get(20).collectedAt()).isEqualTo(COLLECTED_AT.minus(Duration.ofMinutes(5)));
            assertThat(relativeChange(points, 5)).isEqualByComparingTo("0.04347826");
            assertThat(relativeChange(points, 10)).isEqualByComparingTo("0.09090909");
            assertThat(relativeChange(points, 20)).isEqualByComparingTo("0.2");
        }
    }

    @Test
    void returnsUnknownRatioPointsWhenOneSourceIsUnavailable() {
        var marketData = mock(MarketDataAdapter.class);
        when(marketData.getCandles(any(), anyString(), eq("1d"), eq(21), eq(null), eq(true)))
                .thenAnswer(call -> "IWM".equals(call.getArgument(1))
                        ? null : new BrokerResponse<>(ratioHistory(call.getArgument(1)), metadata()));

        var series = new MonitoringQuoteAdapter(mock(BrokerAdapter.class), marketData, CONNECTION,
                Clock.fixed(COLLECTED_AT, ZoneOffset.UTC)).marketRatios();
        var iwmSpy = series.stream().filter(item -> item.metric().equals("equity.iwm_spy")).findFirst().orElseThrow();

        assertThat(iwmSpy.points()).hasSize(21);
        assertThat(iwmSpy.points()).allSatisfy(point -> assertThat(point.value()).isNull());
    }

    @Test
    void returnsUnknownRatioPointsWhenSourceObservationIsStale() {
        var marketData = mock(MarketDataAdapter.class);
        var staleMetadata = new BrokerCallMetadata("stale", COLLECTED_AT.minus(Duration.ofHours(1)), Optional.empty());
        when(marketData.getCandles(any(), anyString(), eq("1d"), eq(21), eq(null), eq(true)))
                .thenAnswer(call -> new BrokerResponse<>(ratioHistory(call.getArgument(1)), staleMetadata));

        var series = new MonitoringQuoteAdapter(mock(BrokerAdapter.class), marketData, CONNECTION,
                Clock.fixed(COLLECTED_AT, ZoneOffset.UTC)).marketRatios();

        assertThat(series).allSatisfy(metric -> assertThat(metric.points())
                .allSatisfy(point -> assertThat(point.value()).isNull()));
    }

    private static MarketDataAdapter.CandleSeries history(String symbol, String dailyStep, BigDecimal lastVolume) {
        var candles = new ArrayList<MarketDataAdapter.Candle>();
        var step = new BigDecimal(dailyStep);
        var start = Instant.parse("2026-09-08T13:30:00Z");
        for (int index = 0; index < 21; index++) {
            var close = new BigDecimal("100").add(step.multiply(BigDecimal.valueOf(Math.max(0, index - 15))));
            candles.add(candle(start.plusSeconds(index * 86_400L), close,
                    index == 20 ? lastVolume : new BigDecimal("100")));
        }
        return new MarketDataAdapter.CandleSeries(symbol, "1d", true, candles, null);
    }

    private static MarketDataAdapter.CandleSeries ratioHistory(String symbol) {
        var denominator = List.of("SPY", "LQD", "XLF").contains(symbol);
        var candles = new ArrayList<MarketDataAdapter.Candle>();
        var start = Instant.parse("2026-09-08T13:30:00Z");
        for (int index = 0; index < 21; index++) {
            var close = denominator ? new BigDecimal("100")
                    : new BigDecimal("100").add(BigDecimal.valueOf(index));
            candles.add(candle(start.plusSeconds(index * 86_400L + (denominator ? 0 : 60)), close,
                    new BigDecimal("100")));
        }
        return new MarketDataAdapter.CandleSeries(symbol, "1d", true, candles, null);
    }

    private static BigDecimal relativeChange(List<MonitoringEvaluationContract.MetricPoint> points, int days) {
        var latest = new BigDecimal(points.get(points.size() - 1).value());
        var reference = new BigDecimal(points.get(points.size() - 1 - days).value());
        return latest.divide(reference, 8, java.math.RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
    }

    private static MarketDataAdapter.Candle candle(Instant timestamp, BigDecimal close, BigDecimal volume) {
        return new MarketDataAdapter.Candle(timestamp, close, close, close, close, volume, Currency.USD);
    }

    private static MarketDataAdapter.CandleSeries shift(MarketDataAdapter.CandleSeries series, long seconds) {
        return new MarketDataAdapter.CandleSeries(series.symbol(), series.interval(), series.adjusted(),
                series.candles().stream().map(candle -> new MarketDataAdapter.Candle(
                        candle.timestamp().plusSeconds(seconds), candle.openPrice(), candle.highPrice(),
                        candle.lowPrice(), candle.closePrice(), candle.volume(), candle.currency())).toList(), null);
    }

    private static BrokerCallMetadata metadata() {
        return new BrokerCallMetadata("monitoring-test", COLLECTED_AT, Optional.empty());
    }

    private static BrokerCallMetadata marketMetadata() {
        return new BrokerCallMetadata("market-test", COLLECTED_AT.minus(Duration.ofMinutes(5)), Optional.empty());
    }
}
