package com.jmj.trade.connector;

import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadException;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderView;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public final class ConnectorService {

    private final FreshPortfolioReadService portfolios;
    private final BrokerAdapter broker;
    private final BrokerOrderPort orders;
    private final JdbcTemplate jdbc;

    public ConnectorService(FreshPortfolioReadService portfolios, Map<String, BrokerAdapter> brokers) {
        this(portfolios, brokers, null);
    }

    @Autowired
    public ConnectorService(FreshPortfolioReadService portfolios, Map<String, BrokerAdapter> brokers, JdbcTemplate jdbc) {
        this.portfolios = portfolios;
        this.broker = selectBroker(brokers);
        this.orders = brokers.values().stream()
                .filter(BrokerOrderPort.class::isInstance)
                .map(BrokerOrderPort.class::cast)
                .findFirst()
                .orElse(null);
        this.jdbc = jdbc;
    }

    public ConnectorResponse.Portfolio portfolio(UUID userId, UUID connectionId) {
        var source = portfolios.read(userId, connectionId);
        return new ConnectorResponse.Portfolio(
                source.completedAt(), source.stale(), source.staleReason(), source.partial(),
                source.missingSections(), source.unknownFields(), account(source.account()),
                source.positions().stream().map(ConnectorService::position).toList(),
                source.buyingPower().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> new ConnectorResponse.BuyingPower(
                                entry.getValue().cashBuyingPower(), entry.getValue().observedAt()))));
    }

    public ConnectorResponse.PortfolioState portfolioState(UUID userId, UUID connectionId) {
        final ConnectorResponse.Portfolio portfolio;
        try {
            portfolio = portfolio(userId, connectionId);
        } catch (PortfolioReadException exception) {
            // An active connection with no successful run is still a valid state. Keep the
            // canonical response machine-readable instead of turning first-sync failure into 500.
            return unknownState();
        }

        final List<ConnectorResponse.Order> openOrders;
        try {
            openOrders = orders(userId, connectionId, "OPEN");
        } catch (RuntimeException exception) {
            // The account snapshot remains useful when the broker order surface is unavailable.
            return PortfolioStateBuilder.build(portfolio, null);
        }
        return PortfolioStateBuilder.build(portfolio, openOrders);
    }

    public List<ConnectorResponse.Order> orders(UUID userId, UUID connectionId, String rawGroup) {
        return orders(brokerAccount(connectionId), rawGroup);
    }

    public List<ConnectorResponse.Order> orders(BrokerAccountRef account, String rawGroup) {
        requireOrderPort();
        var group = parseGroup(rawGroup);
        return requireOrderPort().getOrders(account, group).value().stream().map(ConnectorService::order).toList();
    }

    public List<ConnectorResponse.Fill> fills(UUID userId, UUID connectionId, Instant since) {
        requireOrderPort();
        var account = account(connectionId);
        var open = requireOrderPort().getOrders(account, BrokerOrderGroup.OPEN).value().stream()
                .map(ConnectorService::order).toList();
        var closed = requireOrderPort().getOrders(account, BrokerOrderGroup.CLOSED).value().stream()
                .map(ConnectorService::order).toList();
        return fills(open, closed, since);
    }

    public static List<ConnectorResponse.Fill> fills(
            List<ConnectorResponse.Order> open, List<ConnectorResponse.Order> closed, Instant since
    ) {
        return java.util.stream.Stream.concat(
                        (open == null ? List.<ConnectorResponse.Order>of() : open).stream(),
                        (closed == null ? List.<ConnectorResponse.Order>of() : closed).stream())
                .filter(order -> order.filledQuantity() != null && order.filledQuantity().signum() > 0)
                .map(ConnectorService::fill)
                .filter(fill -> since == null || (fill.filledAt() != null && !fill.filledAt().isBefore(since)))
                .toList();
    }

    public ConnectorResponse.Order order(UUID userId, UUID connectionId, String brokerOrderId, String clientOrderId) {
        if ((brokerOrderId == null || brokerOrderId.isBlank()) && (clientOrderId == null || clientOrderId.isBlank())) {
            throw new IllegalArgumentException("brokerOrderId or clientOrderId is required");
        }
        if (brokerOrderId == null || brokerOrderId.isBlank()) {
            brokerOrderId = brokerOrderId(userId, connectionId, clientOrderId);
        }
        var response = requireOrderPort().getOrder(account(connectionId), brokerOrderId);
        if (response == null || response.value() == null) {
            throw new IllegalStateException("broker order is unavailable");
        }
        return order(response.value());
    }

    private String brokerOrderId(UUID userId, UUID connectionId, String clientOrderId) {
        if (jdbc == null) throw new IllegalStateException("clientOrderId lookup is unavailable");
        var ids = jdbc.query("""
                SELECT broker_order.broker_order_id
                  FROM broker_orders broker_order
                  JOIN order_intents intent ON intent.id = broker_order.order_intent_id
                 WHERE broker_order.client_order_id = ?
                   AND intent.user_id = ?
                   AND intent.broker_connection_id = ?
                """, (rs, row) -> rs.getString(1), clientOrderId, userId, connectionId);
        if (ids.size() != 1) throw new IllegalArgumentException("clientOrderId was not found");
        return ids.getFirst();
    }

    private BrokerOrderPort requireOrderPort() {
        if (orders == null) throw new IllegalStateException("broker order read is unavailable");
        return orders;
    }

    public BrokerAccountRef brokerAccount(UUID connectionId) {
        var accounts = requireBroker().getAccounts(new BrokerConnectionRef(connectionId)).value();
        if (accounts == null || accounts.size() != 1) {
            throw new IllegalStateException("exactly one broker account is required");
        }
        return accounts.getFirst().account();
    }

    private BrokerAccountRef account(UUID connectionId) {
        return brokerAccount(connectionId);
    }

    private BrokerAdapter requireBroker() {
        if (broker == null) throw new IllegalStateException("broker account read is unavailable");
        return broker;
    }

    private static BrokerAdapter selectBroker(Map<String, BrokerAdapter> brokers) {
        if (brokers.isEmpty()) return null;
        var named = brokers.get("brokerAdapter");
        if (named != null) return named;
        named = brokers.get("tossBrokerAdapter");
        return named != null ? named : brokers.values().iterator().next();
    }

    private static ConnectorResponse.Account account(PortfolioReadService.AccountView source) {
        if (source == null) return null;
        return new ConnectorResponse.Account(source.accountType(), source.displayAccountNumber(),
                source.totalPurchaseAmounts(), source.marketValueAmounts(), source.marketValueAfterCostAmounts(),
                source.profitLossAmounts(), source.profitLossAfterCostAmounts(), source.dailyProfitLossAmounts(),
                source.profitLossRate(), source.profitLossRateAfterCost(), source.dailyProfitLossRate(), source.observedAt());
    }

    private static ConnectorResponse.Position position(PortfolioReadService.PositionView source) {
        return new ConnectorResponse.Position(source.symbol(), source.name(), source.marketCountry(), source.quantity(),
                source.currency(), source.averagePrice(), source.lastPrice(), source.purchaseAmount(), source.marketValueAmount(),
                source.marketValueAfterCost(), source.profitLossAmount(), source.profitLossAfterCost(), source.profitLossRate(),
                source.profitLossRateAfterCost(), source.dailyProfitLossAmount(), source.dailyProfitLossRate(), source.commission(),
                source.tax(), source.sellableQuantity(), source.observedAt());
    }

    private static ConnectorResponse.Order order(BrokerOrderView source) {
        return new ConnectorResponse.Order(source.brokerOrderId(),
                ConnectorResponse.BrokerOrderSide.valueOf(source.side().name()),
                ConnectorResponse.BrokerOrderType.valueOf(source.type().name()), source.symbol(), source.quantity(),
                source.filledQuantity(), source.limitPrice(), source.currency().name(),
                ConnectorResponse.BrokerOrderLifecycle.valueOf(source.status().name()),
                ConnectorResponse.BrokerOrderGroup.valueOf(source.group().name()), source.filledAt(),
                source.averageFilledPrice(), source.commission(), source.tax());
    }

    private static ConnectorResponse.Fill fill(ConnectorResponse.Order source) {
        return new ConnectorResponse.Fill(source.brokerOrderId(), source.symbol(), source.side(), source.currency(),
                source.filledQuantity(), source.averageFilledPrice(), source.commission(), source.tax(), source.filledAt(), null);
    }

    private static ConnectorResponse.PortfolioState unknownState() {
        return new ConnectorResponse.PortfolioState(
                null, null, null, null, null, null, null, null,
                true, "INITIAL_SYNC_FAILED", true,
                List.of("ACCOUNT", "CASH", "POSITIONS", "OPEN_ORDERS"),
                List.of("PORTFOLIO_STATE"));
    }

    private static BrokerOrderGroup parseGroup(String raw) {
        try { return BrokerOrderGroup.valueOf(raw == null ? "OPEN" : raw.toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("group must be OPEN or CLOSED"); }
    }
}
