package com.jmj.trade.connector;

import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadException;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

class ConnectorServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void preservesPortfolioWhenOpenOrderReadFails() {
        var portfolios = mock(FreshPortfolioReadService.class);
        when(portfolios.read(USER, CONNECTION)).thenReturn(portfolio());

        var broker = mock(BrokerAdapter.class, withSettings().extraInterfaces(BrokerOrderPort.class));
        var orders = (BrokerOrderPort) broker;
        var account = new BrokerAccountRef(CONNECTION, "12345678", "GENERAL", "****5678");
        when(broker.getAccounts(any(BrokerConnectionRef.class))).thenReturn(
                new BrokerResponse<>(List.of(new BrokerAccountView(account, "Toss")), metadata()));
        doThrow(new BrokerException(
                BrokerErrorCategory.NETWORK, null, null, null, null, true, "Toss timeout"))
                .when(orders).getOrders(account, BrokerOrderGroup.OPEN);

        var state = new ConnectorService(portfolios, Map.of("brokerAdapter", broker))
                .portfolioState(USER, CONNECTION);

        assertThat(state.account().totalValue()).isEqualByComparingTo("1000");
        assertThat(state.account().cash()).isEqualByComparingTo("100");
        assertThat(state.openOrders()).isNull();
        assertThat(state.partial()).isTrue();
        assertThat(state.unknownFields()).contains("openOrders");
        assertThat(state.staleReason()).isEqualTo("OPEN_ORDERS_UNAVAILABLE");
    }

    @Test
    void returnsExplicitUnknownStateWhenInitialSyncHasNoSnapshot() {
        var portfolios = mock(FreshPortfolioReadService.class);
        when(portfolios.read(USER, CONNECTION)).thenThrow(new PortfolioReadException());

        var state = new ConnectorService(portfolios, Map.of())
                .portfolioState(USER, CONNECTION);

        assertThat(state.asOf()).isNull();
        assertThat(state.account()).isNull();
        assertThat(state.positions()).isNull();
        assertThat(state.openOrders()).isNull();
        assertThat(state.risk()).isNull();
        assertThat(state.stale()).isTrue();
        assertThat(state.staleReason()).isEqualTo("INITIAL_SYNC_FAILED");
        assertThat(state.partial()).isTrue();
        assertThat(state.unknownFields()).containsExactly("PORTFOLIO_STATE");
        assertThat(state.missingSections())
                .containsExactly("ACCOUNT", "CASH", "POSITIONS", "OPEN_ORDERS");
    }

    @Test
    void doesNotTurnMissingConnectionIntoUnknownState() {
        var portfolios = mock(FreshPortfolioReadService.class);
        var notFound = com.jmj.trade.broker.connection.BrokerConnectionException.notFound();
        when(portfolios.read(USER, CONNECTION)).thenThrow(notFound);

        assertThatThrownBy(() -> new ConnectorService(portfolios, Map.of())
                .portfolioState(USER, CONNECTION))
                .isSameAs(notFound);
    }

    @Test
    void confirmedOrderFillDoesNotGuessAveragePriceFromLimitPrice() {
        var order = new ConnectorResponse.Order(
                "order-1", ConnectorResponse.BrokerOrderSide.BUY, ConnectorResponse.BrokerOrderType.LIMIT,
                "ABC", new BigDecimal("2"), new BigDecimal("1"), new BigDecimal("10"), "USD",
                ConnectorResponse.BrokerOrderLifecycle.PARTIALLY_FILLED,
                ConnectorResponse.BrokerOrderGroup.OPEN, OBSERVED_AT, null, null, null);

        var fills = ConnectorService.fills(List.of(order), List.of(), OBSERVED_AT.minusSeconds(60));

        assertThat(fills).hasSize(1);
        assertThat(fills.getFirst().averagePrice()).isNull();
    }

    private static PortfolioReadService.PortfolioView portfolio() {
        var account = new PortfolioReadService.AccountView(
                "GENERAL", "****5678", Map.of(), Map.of("USD", new BigDecimal("900")),
                Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, OBSERVED_AT);
        return new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), OBSERVED_AT, false, null, false,
                List.of(), List.of(), account, List.of(),
                Map.of("USD", new PortfolioReadService.BuyingPowerView(
                        new BigDecimal("100"), OBSERVED_AT)));
    }

    private static BrokerCallMetadata metadata() {
        return new BrokerCallMetadata("request", OBSERVED_AT, Optional.empty());
    }
}
