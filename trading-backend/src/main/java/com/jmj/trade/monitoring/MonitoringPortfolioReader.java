package com.jmj.trade.monitoring;

import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class MonitoringPortfolioReader {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int SCALE = 8;

    private final JdbcTemplate jdbc;
    private final PortfolioReadService portfolios;
    private final MonitoringWatchlistService configuration;
    private final RiskPolicyService riskPolicies;
    private final Duration maxAge;

    MonitoringPortfolioReader(
            JdbcTemplate jdbc,
            PortfolioReadService portfolios,
            MonitoringWatchlistService configuration,
            RiskPolicyService riskPolicies,
            @Value("${monitoring.portfolio.max-age:PT15M}") Duration maxAge
    ) {
        this.jdbc = jdbc;
        this.portfolios = portfolios;
        this.configuration = configuration;
        this.riskPolicies = riskPolicies;
        this.maxAge = positive(maxAge);
    }

    MonitoringEvaluationContract.PortfolioInput read(UUID userId, Instant now, BigDecimal krwPerUsd) {
        var policy = new MonitoringEvaluationContract.RiskPolicyInput(
                riskPolicies.current(userId).maxConcentration(), null, null);
        var connectionIds = jdbc.query("""
                SELECT id FROM broker_connections
                 WHERE user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                 ORDER BY id
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class), userId);
        if (connectionIds.isEmpty()) {
            return new MonitoringEvaluationContract.PortfolioInput(null, List.of(), policy);
        }

        var groups = new HashMap<String, PositionGroup>();
        var portfolioValueUsd = BigDecimal.ZERO;
        var valueIsKnown = true;
        var asOf = (Instant) null;
        for (var connectionId : connectionIds) {
            final PortfolioReadService.PortfolioView view;
            try {
                view = portfolios.read(userId, connectionId);
            } catch (RuntimeException exception) {
                return new MonitoringEvaluationContract.PortfolioInput(null, List.of(), policy);
            }
            if (view.stale() || view.account() == null
                    || now.isBefore(view.completedAt()) || now.isAfter(view.completedAt().plus(maxAge))) {
                return new MonitoringEvaluationContract.PortfolioInput(null, List.of(), policy);
            }
            asOf = min(asOf, view.completedAt());
            asOf = min(asOf, view.account().observedAt());
            var accountValue = totalUsd(view.account().marketValueAmounts(), krwPerUsd);
            if (accountValue == null) {
                valueIsKnown = false;
            } else {
                portfolioValueUsd = portfolioValueUsd.add(accountValue);
            }
            for (var position : view.positions()) {
                var group = groups.computeIfAbsent(position.symbol(), PositionGroup::new);
                group.add(position, krwPerUsd);
                asOf = min(asOf, position.observedAt());
            }
        }

        var totalValue = valueIsKnown && portfolioValueUsd.signum() > 0 ? portfolioValueUsd : null;
        var positions = new ArrayList<MonitoringEvaluationContract.PositionInput>();
        for (var group : groups.values().stream().sorted(java.util.Comparator.comparing(value -> value.symbol)).toList()) {
            var value = group.marketValueKnown ? group.marketValueUsd : null;
            var weight = value == null || totalValue == null ? null
                    : value.divide(totalValue, SCALE, RoundingMode.HALF_UP);
            var averageCost = group.costKnown && group.quantity.signum() > 0
                    ? group.costBasisUsd.divide(group.quantity, SCALE, RoundingMode.HALF_UP) : null;
            var context = configuration.positionContext(userId, group.symbol).orElse(null);
            positions.add(new MonitoringEvaluationContract.PositionInput(
                    group.symbol, group.quantity, averageCost, value, weight,
                    context == null ? null : context.sector(),
                    context == null ? null : context.factor(),
                    context == null ? null : context.beta(),
                    context == null ? null : context.correlation(),
                    context == null ? null : context.thesis(),
                    context == null ? null : context.primaryAlpha(),
                    context == null ? Map.of() : context.conditions(),
                    List.of()));
        }
        return new MonitoringEvaluationContract.PortfolioInput(asOf, List.copyOf(positions), policy);
    }

    private static BigDecimal totalUsd(Map<String, BigDecimal> amounts, BigDecimal krwPerUsd) {
        if (amounts == null || amounts.isEmpty()) {
            return null;
        }
        var result = BigDecimal.ZERO;
        for (var entry : amounts.entrySet()) {
            if (entry.getValue() == null) {
                return null;
            }
            if ("USD".equalsIgnoreCase(entry.getKey())) {
                result = result.add(entry.getValue());
            } else if ("KRW".equalsIgnoreCase(entry.getKey())
                    && krwPerUsd != null && krwPerUsd.signum() > 0) {
                result = result.add(entry.getValue().divide(krwPerUsd, SCALE, RoundingMode.HALF_UP));
            } else {
                return null;
            }
        }
        return result;
    }

    private static BigDecimal conversion(String currency, BigDecimal krwPerUsd) {
        if ("USD".equalsIgnoreCase(currency)) return BigDecimal.ONE;
        if ("KRW".equalsIgnoreCase(currency) && krwPerUsd != null && krwPerUsd.signum() > 0) {
            return BigDecimal.ONE.divide(krwPerUsd, SCALE, RoundingMode.HALF_UP);
        }
        return null;
    }

    private static Instant min(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private static Duration positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("monitoring portfolio max age must be positive");
        }
        return value;
    }

    private static final class PositionGroup {
        private final String symbol;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal costBasisUsd = BigDecimal.ZERO;
        private BigDecimal marketValueUsd = BigDecimal.ZERO;
        private boolean costKnown = true;
        private boolean marketValueKnown = true;

        private PositionGroup(String symbol) {
            this.symbol = symbol;
        }

        private void add(PortfolioReadService.PositionView position, BigDecimal krwPerUsd) {
            quantity = quantity.add(position.quantity());
            var multiplier = conversion(position.currency(), krwPerUsd);
            if (multiplier == null || position.averagePrice() == null) {
                costKnown = false;
            } else {
                costBasisUsd = costBasisUsd.add(position.quantity().multiply(position.averagePrice())
                        .multiply(multiplier));
            }
            if (multiplier == null || position.marketValueAmount() == null) {
                marketValueKnown = false;
            } else {
                marketValueUsd = marketValueUsd.add(position.marketValueAmount().multiply(multiplier));
            }
        }
    }
}
