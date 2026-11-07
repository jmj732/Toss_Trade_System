package com.jmj.trade.investment.tactical;

import com.jmj.trade.investment.PositionMandatePolicy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static com.jmj.trade.investment.tactical.TacticalOverlayCalculator.*;

/** Toss OHLCV-only market risk metrics. It never infers business or fundamental health. */
public final class MarketRiskEvaluator {

    private static final int SCALE = 10;
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int TRADING_DAYS_PER_YEAR = 252;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime REGULAR_CLOSE = LocalTime.of(16, 0);
    public static final String POLICY_VERSION = "TOSS_MARKET_RISK_V1";

    public MarketMetrics calculate(Input input, List<Bar> bars, List<IndicatorBar> indicators,
                                   BigDecimal highRvolThreshold) {
        var asOfBars = bars.stream().filter(bar -> !bar.date().isAfter(input.asOf())).toList();
        var current = indicators.isEmpty() ? null : indicators.getLast();
        if (!input.configured()) {
            return unavailable(input, current, OverlayStatus.NOT_CONFIGURED, MetricStatus.NOT_CONFIGURED);
        }
        if (input.sourceConflict()) {
            return unavailable(input, current, OverlayStatus.SOURCE_CONFLICT, MetricStatus.SOURCE_CONFLICT);
        }
        if (asOfBars.isEmpty()) {
            return unavailable(input, current, OverlayStatus.DATA_MISSING, MetricStatus.DATA_MISSING);
        }
        var status = asOfBars.size() >= 60 && input.historyComplete() ? OverlayStatus.OK
                : asOfBars.size() >= 20 ? OverlayStatus.PARTIAL : OverlayStatus.INSUFFICIENT_HISTORY;
        var splitDates = Set.copyOf(input.splitConflictDates());
        var drawdown20 = maximumDrawdown(asOfBars, 20, splitDates);
        var drawdown60 = maximumDrawdown(asOfBars, 60, splitDates);
        var volatility20 = annualizedVolatility(asOfBars, 20, splitDates);
        var priceVolume = priceVolumeSignal(asOfBars, current, highRvolThreshold);
        var lowerHighs = lowerHighs(asOfBars, splitDates);
        var lowerLows = lowerLows(asOfBars, splitDates);
        return new MarketMetrics(status, asOfBars.getLast().date(), input.source(),
                drawdown20, drawdown60, volatility20,
                current == null ? MetricValue.unavailable(MetricStatus.DATA_MISSING) : current.return20(),
                current == null ? MetricValue.unavailable(MetricStatus.DATA_MISSING) : current.relativeStrength20(),
                current == null ? MetricValue.unavailable(MetricStatus.DATA_MISSING) : current.relativeVolume(),
                current == null ? MetricValue.unavailable(MetricStatus.DATA_MISSING) : current.ema50(),
                priceVolume, lowerHighs, lowerLows, asOfBars.getLast().close());
    }

    public Assessment assess(MarketMetrics metrics, String investmentHorizon, String riskPriceRole,
                             boolean triggerConfirmed, BigDecimal approvedTriggerPrice,
                             BigDecimal currentPrice, Instant currentPriceAsOf) {
        return assess(metrics, investmentHorizon, riskPriceRole, triggerConfirmed, approvedTriggerPrice,
                currentPrice, currentPriceAsOf, null, null);
    }

    public Assessment assess(MarketMetrics metrics, String investmentHorizon, String riskPriceRole,
                             boolean triggerConfirmed, BigDecimal approvedTriggerPrice,
                             BigDecimal currentPrice, Instant currentPriceAsOf,
                             Instant now, Duration regularCloseStaleAfter) {
        var priceRiskState = priceRiskState(triggerConfirmed, approvedTriggerPrice, currentPrice, currentPriceAsOf);
        var marketStatus = marketDataStatus(metrics, now, regularCloseStaleAfter);
        if (metrics != null && !"TOSS".equals(metrics.source())) {
            marketStatus = "SOURCE_CONFLICT";
        }
        var health = "OK".equals(marketStatus) ? health(metrics) : "NOT_EVALUABLE";
        var action = action(investmentHorizon, priceRiskState, health);
        return new Assessment(POLICY_VERSION, marketStatus,
                health, "NOT_EVALUABLE", "TOSS_OPENAPI_HAS_NO_FUNDAMENTAL_HEALTH_DATA",
                priceRiskState, action, investmentHorizon == null ? "UNKNOWN" : investmentHorizon,
                riskPriceRole, approvedTriggerPrice, currentPrice, currentPriceAsOf,
                metrics == null ? null : metrics.asOf(), metrics == null ? null : metrics.source(),
                metrics == null ? null : metrics.maximumDrawdown20(),
                metrics == null ? null : metrics.maximumDrawdown60(),
                metrics == null ? null : metrics.annualizedVolatility20(),
                metrics == null ? null : metrics.return20(),
                metrics == null ? null : metrics.relativeStrength20(),
                metrics == null ? null : metrics.relativeVolume(),
                metrics == null ? null : metrics.ema50(),
                metrics == null ? "NOT_EVALUABLE" : metrics.priceVolumeSignal().name(),
                metrics == null ? null : metrics.lowerHighs20(),
                metrics == null ? null : metrics.lowerLows20());
    }

    private static String marketDataStatus(MarketMetrics metrics, Instant now, Duration regularCloseStaleAfter) {
        if (metrics == null) return "NOT_EVALUABLE";
        var status = metrics.status().name();
        if (Set.of("NOT_CONFIGURED", "DATA_MISSING", "SOURCE_CONFLICT").contains(status)) return status;
        if (now == null || regularCloseStaleAfter == null) return status;
        if (metrics.asOf() == null) return "DATA_MISSING";
        var regularCloseAt = metrics.asOf().atTime(REGULAR_CLOSE).atZone(NEW_YORK).toInstant();
        if (regularCloseAt.isAfter(now)) return "SOURCE_CONFLICT";
        if (regularCloseAt.isBefore(now.minus(regularCloseStaleAfter))) return "STALE";
        return status;
    }

    private static MarketMetrics unavailable(Input input, IndicatorBar current, OverlayStatus status,
                                             MetricStatus metricStatus) {
        var unavailable = MetricValue.unavailable(metricStatus);
        return new MarketMetrics(status, input.asOf(), input.source(), unavailable, unavailable, unavailable,
                current == null ? unavailable : current.return20(),
                current == null ? unavailable : current.relativeStrength20(),
                current == null ? unavailable : current.relativeVolume(),
                current == null ? unavailable : current.ema50(),
                PriceVolumeSignal.NOT_EVALUABLE, null, null, null);
    }

    private static MetricValue maximumDrawdown(List<Bar> bars, int window, Set<LocalDate> splitDates) {
        if (bars.size() < window) return MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY);
        var start = bars.size() - window;
        if (crossesSplitConflict(splitDates, bars.get(start).date(), bars.getLast().date())) {
            return MetricValue.unavailable(MetricStatus.CORPORATE_ACTION_CONFLICT);
        }
        var peak = bars.get(start).close();
        var maximum = BigDecimal.ZERO;
        for (int index = start + 1; index < bars.size(); index++) {
            var close = bars.get(index).close();
            if (close.compareTo(peak) > 0) peak = close;
            else maximum = maximum.max(peak.subtract(close).divide(peak, SCALE, RoundingMode.HALF_UP));
        }
        return MetricValue.available(maximum);
    }

    private static MetricValue annualizedVolatility(List<Bar> bars, int returnWindow,
                                                     Set<LocalDate> splitDates) {
        if (bars.size() < returnWindow + 1) return MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY);
        var start = bars.size() - returnWindow - 1;
        if (crossesSplitConflict(splitDates, bars.get(start).date(), bars.getLast().date())) {
            return MetricValue.unavailable(MetricStatus.CORPORATE_ACTION_CONFLICT);
        }
        var returns = new java.util.ArrayList<BigDecimal>(returnWindow);
        for (int index = start + 1; index < bars.size(); index++) {
            returns.add(bars.get(index).close().divide(bars.get(index - 1).close(), MC).subtract(BigDecimal.ONE));
        }
        var average = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), MC);
        var squaredDifferenceSum = returns.stream().map(value -> value.subtract(average).pow(2, MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var variance = squaredDifferenceSum.divide(BigDecimal.valueOf(returns.size() - 1L), MC);
        var annualization = BigDecimal.valueOf(Math.sqrt(TRADING_DAYS_PER_YEAR));
        return MetricValue.available(variance.sqrt(MC).multiply(annualization, MC)
                .setScale(SCALE, RoundingMode.HALF_UP));
    }

    private static MetricValue lowerHighs(List<Bar> bars, Set<LocalDate> splitDates) {
        if (bars.size() < 40) return MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY);
        var start = bars.size() - 40;
        if (crossesSplitConflict(splitDates, bars.get(start).date(), bars.getLast().date())) {
            return MetricValue.unavailable(MetricStatus.CORPORATE_ACTION_CONFLICT);
        }
        var priorHigh = bars.subList(start, start + 20).stream().map(Bar::high).max(BigDecimal::compareTo).orElseThrow();
        var recentHigh = bars.subList(start + 20, bars.size()).stream().map(Bar::high).max(BigDecimal::compareTo).orElseThrow();
        return MetricValue.available(recentHigh.compareTo(priorHigh) < 0 ? BigDecimal.ONE : BigDecimal.ZERO);
    }

    private static MetricValue lowerLows(List<Bar> bars, Set<LocalDate> splitDates) {
        if (bars.size() < 40) return MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY);
        var start = bars.size() - 40;
        if (crossesSplitConflict(splitDates, bars.get(start).date(), bars.getLast().date())) {
            return MetricValue.unavailable(MetricStatus.CORPORATE_ACTION_CONFLICT);
        }
        var priorLow = bars.subList(start, start + 20).stream().map(Bar::low).min(BigDecimal::compareTo).orElseThrow();
        var recentLow = bars.subList(start + 20, bars.size()).stream().map(Bar::low).min(BigDecimal::compareTo).orElseThrow();
        return MetricValue.available(recentLow.compareTo(priorLow) < 0 ? BigDecimal.ONE : BigDecimal.ZERO);
    }

    private static PriceVolumeSignal priceVolumeSignal(List<Bar> bars, IndicatorBar current,
                                                       BigDecimal highRvolThreshold) {
        if (bars.size() < 2 || current == null || current.relativeVolume().status() != MetricStatus.OK
                || highRvolThreshold == null) return PriceVolumeSignal.NOT_EVALUABLE;
        if (current.relativeVolume().value().compareTo(highRvolThreshold) < 0) return PriceVolumeSignal.NO_SPIKE;
        var direction = bars.getLast().close().compareTo(bars.get(bars.size() - 2).close());
        return direction < 0 ? PriceVolumeSignal.SELLING_VOLUME_SPIKE
                : direction > 0 ? PriceVolumeSignal.BUYING_VOLUME_SPIKE : PriceVolumeSignal.NO_SPIKE;
    }

    private static String health(MarketMetrics metrics) {
        if (metrics == null || !"TOSS".equals(metrics.source()) || !required(metrics.ema50()) || !required(metrics.return20())
                || !required(metrics.relativeStrength20())) return "NOT_EVALUABLE";
        var close = metrics.ema50().value();
        var priceBelowEma50 = metrics.latestClose() != null && metrics.latestClose().compareTo(close) < 0;
        int bearishSignals = (priceBelowEma50 ? 1 : 0)
                + (metrics.return20().value().signum() < 0 ? 1 : 0)
                + (metrics.relativeStrength20().value().signum() < 0 ? 1 : 0)
                + (metrics.priceVolumeSignal() == PriceVolumeSignal.SELLING_VOLUME_SPIKE ? 1 : 0)
                + (isTrue(metrics.lowerHighs20()) ? 1 : 0)
                + (isTrue(metrics.lowerLows20()) ? 1 : 0);
        if (priceBelowEma50 && metrics.return20().value().signum() < 0
                && metrics.relativeStrength20().value().signum() < 0) return "BREAKDOWN";
        return bearishSignals >= 2 ? "IMPAIRED" : "INTACT";
    }

    private static boolean required(MetricValue value) {
        return value != null && value.status() == MetricStatus.OK && value.value() != null;
    }

    private static boolean isTrue(MetricValue metric) {
        return required(metric) && BigDecimal.ONE.compareTo(metric.value()) == 0;
    }

    private static String priceRiskState(boolean confirmed, BigDecimal trigger, BigDecimal price, Instant asOf) {
        if (trigger == null) return "NOT_CONFIGURED";
        if (!confirmed) return "UNVERIFIED";
        if (price == null || price.signum() <= 0 || asOf == null) return "NOT_EVALUABLE";
        return price.compareTo(trigger) <= 0 ? "TRIGGER_BREACHED" : "CLEAR";
    }

    private static String action(String horizon, String priceRiskState, String health) {
        if ("TRIGGER_BREACHED".equals(priceRiskState)) {
            return PositionMandatePolicy.resolve(horizon, "PRICE_RISK_BREACH", "NOT_EVALUABLE", false).name();
        }
        if ("BREAKDOWN".equals(health) || "IMPAIRED".equals(health)) {
            return switch (horizon == null ? "UNKNOWN" : horizon) {
                case "TACTICAL" -> PositionMandatePolicy.Response.EXIT_EVALUATION.name();
                case "STRATEGIC" -> PositionMandatePolicy.Response.THESIS_REVALIDATION.name();
                default -> PositionMandatePolicy.Response.MANDATE_REVIEW.name();
            };
        }
        return "NOT_EVALUABLE".equals(health)
                ? PositionMandatePolicy.Response.MANDATE_REVIEW.name()
                : PositionMandatePolicy.Response.NO_PRICE_ACTION.name();
    }

    private static boolean crossesSplitConflict(Set<LocalDate> dates, LocalDate start, LocalDate end) {
        return dates.stream().anyMatch(date -> date.isAfter(start) && !date.isAfter(end));
    }

    public enum PriceVolumeSignal { SELLING_VOLUME_SPIKE, BUYING_VOLUME_SPIKE, NO_SPIKE, NOT_EVALUABLE }

    public record MarketMetrics(OverlayStatus status, LocalDate asOf, String source,
                                MetricValue maximumDrawdown20, MetricValue maximumDrawdown60,
                                MetricValue annualizedVolatility20, MetricValue return20,
                                MetricValue relativeStrength20, MetricValue relativeVolume,
                                MetricValue ema50, PriceVolumeSignal priceVolumeSignal,
                                MetricValue lowerHighs20, MetricValue lowerLows20,
                                BigDecimal latestClose) {
        public MarketMetrics {
            java.util.Objects.requireNonNull(status, "status");
            java.util.Objects.requireNonNull(priceVolumeSignal, "priceVolumeSignal");
        }
    }

    public record Assessment(String policyVersion, String marketDataStatus, String marketHealth,
                             String fundamentalHealth, String fundamentalHealthReason,
                             String priceRiskState, String recommendedAction, String investmentHorizon,
                             String riskPriceRole, BigDecimal approvedTriggerPrice, BigDecimal currentPrice,
                             Instant currentPriceAsOf, LocalDate marketAsOf, String marketDataSource,
                             MetricValue maximumDrawdown20, MetricValue maximumDrawdown60,
                             MetricValue annualizedVolatility20, MetricValue return20,
                             MetricValue relativeStrength20, MetricValue relativeVolume,
                             MetricValue ema50, String priceVolumeSignal,
                             MetricValue lowerHighs20, MetricValue lowerLows20) {
    }
}
