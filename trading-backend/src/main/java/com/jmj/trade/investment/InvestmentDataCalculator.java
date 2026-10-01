package com.jmj.trade.investment;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class InvestmentDataCalculator {

    private static final BigDecimal SOURCE_CONFLICT_THRESHOLD = new BigDecimal("0.005");
    // ponytail: a week covers weekends and common holidays; configure wider if the exchange calendar requires it.
    private static final Duration DEFAULT_REGULAR_CLOSE_MAX_AGE = Duration.ofDays(7);

    private InvestmentDataCalculator() {
    }

    public static PriceAssessment assessPrices(List<SourceQuote> sourceQuotes, Instant now, Duration staleAfter) {
        return assessPrices(sourceQuotes, now, staleAfter, DEFAULT_REGULAR_CLOSE_MAX_AGE);
    }

    public static PriceAssessment assessPrices(
            List<SourceQuote> sourceQuotes, Instant now, Duration staleAfter, Duration regularCloseStaleAfter
    ) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(staleAfter, "staleAfter");
        Objects.requireNonNull(regularCloseStaleAfter, "regularCloseStaleAfter");
        if (staleAfter.isNegative() || staleAfter.isZero()
                || regularCloseStaleAfter.isNegative() || regularCloseStaleAfter.isZero()) {
            throw new IllegalArgumentException("price stale windows must be positive");
        }
        List<SourceQuote> allQuotes = sourceQuotes == null ? List.of() : sourceQuotes;
        var close = allQuotes.stream()
                .filter(Objects::nonNull)
                .filter(quote -> positive(quote.regularClose()) && quote.regularCloseAsOf() != null
                        && !quote.regularCloseAsOf().isAfter(now))
                .max(Comparator.comparing(SourceQuote::regularCloseAsOf))
                .orElse(null);
        var quotes = allQuotes.stream()
                .filter(Objects::nonNull)
                .filter(quote -> positive(quote.latestPrice()) && quote.latestPriceAsOf() != null
                        && !quote.latestPriceAsOf().isAfter(now))
                .sorted(Comparator.comparing(SourceQuote::latestPriceAsOf).reversed()
                        .thenComparing(SourceQuote::source, Comparator.nullsLast(String::compareTo)))
                .toList();
        if (quotes.isEmpty()) {
            return new PriceAssessment(
                    close == null ? null : close.regularClose(),
                    close == null ? null : close.regularCloseAsOf(),
                    null, null, null, null, null,
                    close == null ? DataStatus.DATA_MISSING : DataStatus.PARTIAL);
        }

        var primary = quotes.getFirst();
        var key = sessionKey(primary);
        var latestBySource = new LinkedHashMap<String, SourceQuote>();
        if (key != null) {
            for (var quote : quotes) {
                if (key.equals(sessionKey(quote)) && quote.source() != null && !quote.source().isBlank()) {
                    latestBySource.putIfAbsent(quote.source(), quote);
                }
            }
        }
        var sameSession = latestBySource.values().stream()
                .sorted(Comparator.comparing(SourceQuote::latestPriceAsOf).reversed())
                .toList();
        var secondary = sameSession.stream().filter(quote -> !Objects.equals(quote.source(), primary.source()))
                .findFirst().orElse(null);
        var conflict = sameSession.stream().anyMatch(quote -> differsByAtLeastHalfPercent(primary.latestPrice(), quote.latestPrice()));
        var session = session(primary.session());
        var maxAge = "REGULAR_CLOSE".equals(session) ? regularCloseStaleAfter : staleAfter;
        var status = conflict ? DataStatus.SOURCE_CONFLICT
                : primary.source() == null || session == null ? DataStatus.PARTIAL
                : primary.latestPriceAsOf().isBefore(now.minus(maxAge)) ? DataStatus.STALE
                : DataStatus.OK;
        return new PriceAssessment(
                close == null ? null : close.regularClose(),
                close == null ? null : close.regularCloseAsOf(),
                primary.latestPrice(),
                primary.latestPriceAsOf(),
                session,
                primary.source(),
                secondary == null ? null : secondary.source(),
                status);
    }

    public static RevisionResult revision(
            BigDecimal current,
            Instant currentAsOf,
            List<ConsensusValue> history,
            Duration horizon
    ) {
        if (current == null || currentAsOf == null || horizon == null || !horizon.isPositive()) {
            return RevisionResult.missing();
        }
        var cutoff = currentAsOf.minus(horizon);
        var prior = history == null ? null : history.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.asOf() != null && !value.asOf().isAfter(cutoff))
                .filter(value -> value.value() != null)
                .max(Comparator.comparing(ConsensusValue::asOf))
                .orElse(null);
        if (prior == null || prior.value().signum() == 0) {
            return RevisionResult.missing();
        }
        var value = current.subtract(prior.value())
                .divide(prior.value().abs(), MathContext.DECIMAL128)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
        return new RevisionResult(value, prior.asOf(), DataStatus.OK);
    }

    public static BigDecimal invalidationDownside(BigDecimal currentPrice, BigDecimal triggerPrice) {
        if (!positive(currentPrice) || triggerPrice == null || triggerPrice.signum() < 0
                || triggerPrice.compareTo(currentPrice) > 0) {
            return null;
        }
        return currentPrice.subtract(triggerPrice)
                .divide(currentPrice, MathContext.DECIMAL128)
                .setScale(8, RoundingMode.HALF_UP);
    }

    public static BigDecimal plannedLossContribution(BigDecimal portfolioWeight, BigDecimal invalidationDownside) {
        if (portfolioWeight == null || invalidationDownside == null
                || portfolioWeight.signum() < 0 || invalidationDownside.signum() < 0) {
            return null;
        }
        return portfolioWeight.multiply(invalidationDownside).setScale(8, RoundingMode.HALF_UP);
    }

    public static BigDecimal correlation(List<BigDecimal> left, List<BigDecimal> right) {
        if (left == null || right == null || left.size() != right.size() || left.size() < 2
                || left.stream().anyMatch(Objects::isNull) || right.stream().anyMatch(Objects::isNull)) {
            return null;
        }
        var leftMean = left.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(Double.NaN);
        var rightMean = right.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(Double.NaN);
        var covariance = 0.0;
        var leftVariance = 0.0;
        var rightVariance = 0.0;
        for (var index = 0; index < left.size(); index++) {
            var leftDelta = left.get(index).doubleValue() - leftMean;
            var rightDelta = right.get(index).doubleValue() - rightMean;
            covariance += leftDelta * rightDelta;
            leftVariance += leftDelta * leftDelta;
            rightVariance += rightDelta * rightDelta;
        }
        var denominator = Math.sqrt(leftVariance * rightVariance);
        if (!(denominator > 0) || !Double.isFinite(denominator)) return null;
        var value = covariance / denominator;
        if (!Double.isFinite(value)) return null;
        return BigDecimal.valueOf(Math.max(-1, Math.min(1, value))).setScale(8, RoundingMode.HALF_UP);
    }

    private static boolean differsByAtLeastHalfPercent(BigDecimal first, BigDecimal second) {
        if (!positive(first) || !positive(second)) {
            return false;
        }
        var lower = first.min(second);
        return first.subtract(second).abs().divide(lower, MathContext.DECIMAL128)
                .compareTo(SOURCE_CONFLICT_THRESHOLD) >= 0;
    }

    private static String sessionKey(SourceQuote quote) {
        var session = session(quote.session());
        return session == null || quote.latestPriceAsOf() == null
                ? null : session + ":" + quote.latestPriceAsOf().toString().substring(0, 10);
    }

    private static String session(String value) {
        if (value == null) {
            return null;
        }
        try {
            return PriceSession.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean positiveOrZero(BigDecimal value) {
        return value != null && value.signum() >= 0;
    }

    public enum DataStatus {
        OK,
        PARTIAL,
        DATA_MISSING,
        SOURCE_CONFLICT,
        STALE,
        UNVERIFIED,
        NOT_APPLICABLE
    }

    public enum PriceSession {
        REGULAR_CLOSE,
        LIVE_REGULAR,
        AFTER_HOURS,
        PREMARKET
    }

    public record SourceQuote(
            String source,
            BigDecimal latestPrice,
            Instant latestPriceAsOf,
            String session,
            BigDecimal regularClose,
            Instant regularCloseAsOf
    ) {
        SourceQuote(String source, BigDecimal latestPrice, Instant latestPriceAsOf, String session) {
            this(source, latestPrice, latestPriceAsOf, session, null, null);
        }
    }

    public record PriceAssessment(
            BigDecimal regularClose,
            Instant regularCloseAsOf,
            BigDecimal latestPrice,
            Instant latestPriceAsOf,
            String session,
            String source,
            String secondarySource,
            DataStatus status
    ) {
    }

    public record ConsensusValue(Instant asOf, BigDecimal value) {
    }

    public record RevisionResult(BigDecimal value, Instant baselineAsOf, DataStatus status) {
        private static RevisionResult missing() {
            return new RevisionResult(null, null, DataStatus.DATA_MISSING);
        }
    }
}
