package com.jmj.trade.connector;

import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderView;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

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

    public ConnectorService(FreshPortfolioReadService portfolios, Map<String, BrokerAdapter> brokers) {
        this.portfolios = portfolios;
        this.broker = selectBroker(brokers);
        this.orders = brokers.values().stream()
                .filter(BrokerOrderPort.class::isInstance)
                .map(BrokerOrderPort.class::cast)
                .findFirst()
                .orElse(null);
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
        return PortfolioStateBuilder.build(
                portfolio(userId, connectionId),
                orders(userId, connectionId, "OPEN"));
    }

    public List<ConnectorResponse.Order> orders(UUID userId, UUID connectionId, String rawGroup) {
        requireOrderPort();
        var group = parseGroup(rawGroup);
        var account = account(connectionId);
        return requireOrderPort().getOrders(account, group).value().stream().map(ConnectorService::order).toList();
    }

    public List<ConnectorResponse.Fill> fills(UUID userId, UUID connectionId, Instant since) {
        requireOrderPort();
        var account = account(connectionId);
        var open = requireOrderPort().getOrders(account, BrokerOrderGroup.OPEN).value();
        var closed = requireOrderPort().getOrders(account, BrokerOrderGroup.CLOSED).value();
        return java.util.stream.Stream.concat(open.stream(), closed.stream())
                .filter(order -> order.filledQuantity() != null && order.filledQuantity().signum() > 0)
                .map(ConnectorService::fill)
                .filter(fill -> since == null || (fill.filledAt() != null && !fill.filledAt().isBefore(since)))
                .toList();
    }

    private BrokerOrderPort requireOrderPort() {
        if (orders == null) throw new IllegalStateException("broker order read is unavailable");
        return orders;
    }

    private BrokerAccountRef account(UUID connectionId) {
        var accounts = requireBroker().getAccounts(new BrokerConnectionRef(connectionId)).value();
        if (accounts == null || accounts.size() != 1) {
            throw new IllegalStateException("exactly one broker account is required");
        }
        return accounts.getFirst().account();
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

    private static ConnectorResponse.Fill fill(BrokerOrderView source) {
        return new ConnectorResponse.Fill(source.brokerOrderId(), source.symbol(),
                ConnectorResponse.BrokerOrderSide.valueOf(source.side().name()), source.currency().name(),
                source.filledQuantity(), source.averageFilledPrice() == null ? source.limitPrice() : source.averageFilledPrice(),
                source.commission(), source.tax(), source.filledAt(), null);
    }

    private static BrokerOrderGroup parseGroup(String raw) {
        try { return BrokerOrderGroup.valueOf(raw == null ? "OPEN" : raw.toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("group must be OPEN or CLOSED"); }
    }
}
