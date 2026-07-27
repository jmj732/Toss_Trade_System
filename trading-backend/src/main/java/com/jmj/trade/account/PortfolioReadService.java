package com.jmj.trade.account;

import com.jmj.trade.broker.connection.BrokerConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PortfolioReadService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PortfolioReadService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public PortfolioView read(UUID userId, UUID connectionId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        var selection = selectRun(userId, connectionId);
        if (selection == null) {
            throw BrokerConnectionException.notFound();
        }
        if (selection.successRunId() == null) {
            throw new PortfolioReadException();
        }

        var account = readAccount(selection.successRunId(), userId, connectionId);
        var positions = readPositions(selection.successRunId(), userId, connectionId);
        var buyingPower = readBuyingPower(selection.successRunId(), userId, connectionId);
        var missing = new ArrayList<String>();
        if (account == null) {
            missing.add("ACCOUNT");
        }
        if (!buyingPower.containsKey("KRW")) {
            missing.add("BUYING_POWER_KRW");
        }
        if (!buyingPower.containsKey("USD")) {
            missing.add("BUYING_POWER_USD");
        }
        var staleReason = staleReason(selection);
        var unknown = account != null && "UNKNOWN".equals(account.cashBalanceStatus())
                ? List.of("account.cashBalance")
                : List.<String>of();
        return new PortfolioView(
                selection.successRunId(),
                selection.completedAt().toInstant(),
                staleReason != null,
                staleReason,
                !missing.isEmpty(),
                List.copyOf(missing),
                unknown,
                account,
                positions,
                buyingPower);
    }

    private RunSelection selectRun(UUID userId, UUID connectionId) {
        return jdbc.query("""
                SELECT success.id AS success_id,
                       success.completed_at,
                       latest.id AS latest_id,
                       latest.status AS latest_status
                  FROM broker_connections connection
                  LEFT JOIN LATERAL (
                      SELECT run.id, run.status
                        FROM account_sync_runs run
                       WHERE run.user_id = connection.user_id
                         AND run.broker_connection_id = connection.id
                         AND run.credential_revision = connection.credential_revision
                       ORDER BY run.started_at DESC, run.id DESC
                       LIMIT 1
                  ) latest ON true
                  LEFT JOIN LATERAL (
                      SELECT run.id, run.completed_at
                        FROM account_sync_runs run
                       WHERE run.user_id = connection.user_id
                         AND run.broker_connection_id = connection.id
                         AND run.credential_revision = connection.credential_revision
                         AND run.status = 'SUCCEEDED'
                       ORDER BY run.completed_at DESC, run.id DESC
                       LIMIT 1
                  ) success ON true
                 WHERE connection.id = ?
                   AND connection.user_id = ?
                   AND connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                """, (resultSet, rowNum) -> new RunSelection(
                resultSet.getObject("success_id", UUID.class),
                resultSet.getObject("completed_at", OffsetDateTime.class),
                resultSet.getObject("latest_id", UUID.class),
                resultSet.getString("latest_status")
        ), connectionId, userId).stream().findFirst().orElse(null);
    }

    private AccountView readAccount(UUID runId, UUID userId, UUID connectionId) {
        return jdbc.query("""
                SELECT account_type, display_account_number, total_purchase_amounts,
                       market_value_amounts, market_value_after_cost_amounts,
                       profit_loss_amounts, profit_loss_after_cost_amounts,
                       daily_profit_loss_amounts, profit_loss_rate,
                       profit_loss_rate_after_cost, daily_profit_loss_rate,
                       cash_balance_status, observed_at
                  FROM account_snapshots
                 WHERE sync_run_id = ?
                   AND user_id = ?
                   AND broker_connection_id = ?
                """, (resultSet, rowNum) -> new AccountView(
                resultSet.getString("account_type"),
                resultSet.getString("display_account_number"),
                amounts(resultSet.getString("total_purchase_amounts")),
                amounts(resultSet.getString("market_value_amounts")),
                amounts(resultSet.getString("market_value_after_cost_amounts")),
                amounts(resultSet.getString("profit_loss_amounts")),
                amounts(resultSet.getString("profit_loss_after_cost_amounts")),
                amounts(resultSet.getString("daily_profit_loss_amounts")),
                resultSet.getBigDecimal("profit_loss_rate"),
                resultSet.getBigDecimal("profit_loss_rate_after_cost"),
                resultSet.getBigDecimal("daily_profit_loss_rate"),
                resultSet.getString("cash_balance_status"),
                instant(resultSet.getObject("observed_at", OffsetDateTime.class))
        ), runId, userId, connectionId).stream().findFirst().orElse(null);
    }

    private List<PositionView> readPositions(UUID runId, UUID userId, UUID connectionId) {
        return jdbc.query("""
                SELECT symbol, name, market_country, quantity, currency,
                       average_price, last_price, purchase_amount, market_value_amount,
                       market_value_after_cost, profit_loss_amount, profit_loss_after_cost,
                       profit_loss_rate, profit_loss_rate_after_cost,
                       daily_profit_loss_amount, daily_profit_loss_rate,
                       commission, tax, observed_at
                  FROM position_snapshots
                 WHERE sync_run_id = ?
                   AND user_id = ?
                   AND broker_connection_id = ?
                 ORDER BY symbol, id
                """, (resultSet, rowNum) -> new PositionView(
                resultSet.getString("symbol"),
                resultSet.getString("name"),
                resultSet.getString("market_country"),
                resultSet.getBigDecimal("quantity"),
                resultSet.getString("currency"),
                resultSet.getBigDecimal("average_price"),
                resultSet.getBigDecimal("last_price"),
                resultSet.getBigDecimal("purchase_amount"),
                resultSet.getBigDecimal("market_value_amount"),
                resultSet.getBigDecimal("market_value_after_cost"),
                resultSet.getBigDecimal("profit_loss_amount"),
                resultSet.getBigDecimal("profit_loss_after_cost"),
                resultSet.getBigDecimal("profit_loss_rate"),
                resultSet.getBigDecimal("profit_loss_rate_after_cost"),
                resultSet.getBigDecimal("daily_profit_loss_amount"),
                resultSet.getBigDecimal("daily_profit_loss_rate"),
                resultSet.getBigDecimal("commission"),
                resultSet.getBigDecimal("tax"),
                instant(resultSet.getObject("observed_at", OffsetDateTime.class))
        ), runId, userId, connectionId);
    }

    private Map<String, BuyingPowerView> readBuyingPower(
            UUID runId,
            UUID userId,
            UUID connectionId
    ) {
        var values = new LinkedHashMap<String, BuyingPowerView>();
        jdbc.query("""
                SELECT currency, cash_buying_power, observed_at
                  FROM account_capacity_snapshots
                 WHERE sync_run_id = ?
                   AND user_id = ?
                   AND broker_connection_id = ?
                 ORDER BY currency
                """, (resultSet, rowNum) -> Map.entry(
                resultSet.getString("currency"),
                new BuyingPowerView(
                        resultSet.getBigDecimal("cash_buying_power"),
                        instant(resultSet.getObject("observed_at", OffsetDateTime.class)))),
                runId, userId, connectionId).forEach(entry ->
                values.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(values);
    }

    private Map<String, BigDecimal> amounts(String json) {
        try {
            return Map.copyOf(objectMapper.readValue(
                    json,
                    new TypeReference<Map<String, BigDecimal>>() {
                    }));
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored snapshot amounts are invalid", exception);
        }
    }

    private static String staleReason(RunSelection selection) {
        if (selection.successRunId().equals(selection.latestRunId())) {
            return null;
        }
        return switch (selection.latestStatus()) {
            case "RUNNING" -> "SYNC_IN_PROGRESS";
            case "FAILED" -> "LATEST_SYNC_FAILED";
            default -> null;
        };
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    private record RunSelection(
            UUID successRunId,
            OffsetDateTime completedAt,
            UUID latestRunId,
            String latestStatus
    ) {
    }

    public record PortfolioView(
            UUID syncRunId,
            Instant completedAt,
            boolean stale,
            String staleReason,
            boolean partial,
            List<String> missingSections,
            List<String> unknownFields,
            AccountView account,
            List<PositionView> positions,
            Map<String, BuyingPowerView> buyingPower
    ) {
    }

    public record AccountView(
            String accountType,
            String displayAccountNumber,
            Map<String, BigDecimal> totalPurchaseAmounts,
            Map<String, BigDecimal> marketValueAmounts,
            Map<String, BigDecimal> marketValueAfterCostAmounts,
            Map<String, BigDecimal> profitLossAmounts,
            Map<String, BigDecimal> profitLossAfterCostAmounts,
            Map<String, BigDecimal> dailyProfitLossAmounts,
            BigDecimal profitLossRate,
            BigDecimal profitLossRateAfterCost,
            BigDecimal dailyProfitLossRate,
            String cashBalanceStatus,
            Instant observedAt
    ) {
    }

    public record PositionView(
            String symbol,
            String name,
            String marketCountry,
            BigDecimal quantity,
            String currency,
            BigDecimal averagePrice,
            BigDecimal lastPrice,
            BigDecimal purchaseAmount,
            BigDecimal marketValueAmount,
            BigDecimal marketValueAfterCost,
            BigDecimal profitLossAmount,
            BigDecimal profitLossAfterCost,
            BigDecimal profitLossRate,
            BigDecimal profitLossRateAfterCost,
            BigDecimal dailyProfitLossAmount,
            BigDecimal dailyProfitLossRate,
            BigDecimal commission,
            BigDecimal tax,
            Instant observedAt
    ) {
    }

    public record BuyingPowerView(BigDecimal cashBuyingPower, Instant observedAt) {
    }
}
