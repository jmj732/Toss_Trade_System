package com.jmj.trade.broker.toss;

import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerOrderDispatchStatus;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderModification;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderPortContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TossInvestBrokerAdapter} 가 {@link BrokerOrderPort} 계약을 만족하는지 검증한다.
 */
class TossBrokerOrderPortContractTest extends BrokerOrderPortContract {

    private final TossApiClient apiClient = mock(TossApiClient.class);
    private final TossInvestBrokerAdapter adapter =
            new TossInvestBrokerAdapter(apiClient, new TossResponseMapper());

    private final BrokerAccountRef account =
            new BrokerAccountRef(UUID.randomUUID(), "9876543210", "US_STOCK", "******3210");

    @Override
    protected BrokerOrderPort port() {
        return adapter;
    }

    @Override
    protected BrokerAccountRef account() {
        return account;
    }

    @BeforeEach
    void stubOfficialOrderResponses() {
        when(apiClient.createOrder(any(), any(), anyString()))
                .thenReturn(new TossApiResponse<>(
                        new TossApiDtos.OrderResponse("broker-order-1", "client-order-1"),
                        BrokerOrderPort.localMetadata()));
        when(apiClient.cancelOrder(any(), anyString()))
                .thenReturn(new TossApiResponse<>(
                        new TossApiDtos.OrderOperationResponse("broker-order-cancel"),
                        BrokerOrderPort.localMetadata()));
        when(apiClient.modifyOrder(any(), any()))
                .thenReturn(new TossApiResponse<>(
                        new TossApiDtos.OrderOperationResponse("broker-order-replaced"),
                        BrokerOrderPort.localMetadata()));
        when(apiClient.getOrder(any(), anyString()))
                .thenReturn(new TossApiResponse<>(order("broker-order-1"), BrokerOrderPort.localMetadata()));
        when(apiClient.getOrders(any(), any(), any()))
                .thenReturn(new TossApiResponse<>(
                        new TossApiDtos.PaginatedOrderResponse(List.of(order("broker-order-1")), null, false),
                        BrokerOrderPort.localMetadata()));
    }

    @Test
    void orderOperationsReturnTypedBrokerResults() {
        var place = adapter.placeOrder(account, marketRequest(), "idem-1").value();
        assertThat(place.status()).isEqualTo(BrokerOrderDispatchStatus.ACCEPTED);
        assertThat(place.brokerOrderId()).isEqualTo("broker-order-1");

        var cancel = adapter.cancelOrder(account, "broker-order-x").value();
        assertThat(cancel.status()).isEqualTo(BrokerOrderDispatchStatus.ACCEPTED);

        var repriceModify = adapter.modifyOrder(
                account, BrokerOrderModification.reprice("broker-order-x", new BigDecimal("10"))).value();
        assertThat(repriceModify.status()).isEqualTo(BrokerOrderDispatchStatus.ACCEPTED);
    }

    @Test
    void orderRetrievalMapsBrokerResults() {
        assertThat(adapter.getOrders(account, BrokerOrderGroup.OPEN).value())
                .singleElement()
                .extracting(order -> order.brokerOrderId())
                .isEqualTo("broker-order-1");
        assertThat(adapter.getOrder(account, "broker-order-1").value().brokerOrderId())
                .isEqualTo("broker-order-1");
    }

    @Test
    void operationRejectionStatusesRemainDistinctFromOrderRejection() {
        var mapper = new TossResponseMapper();
        assertThat(mapper.order(order("cancel-rejected", "CANCEL_REJECTED")).status().name())
                .isEqualTo("CANCEL_REJECTED");
        assertThat(mapper.order(order("replace-rejected", "REPLACE_REJECTED")).status().name())
                .isEqualTo("REPLACE_REJECTED");
    }

    private static TossApiDtos.Order order(String orderId) {
        return order(orderId, "FILLED");
    }

    private static TossApiDtos.Order order(String orderId, String status) {
        return new TossApiDtos.Order(
                orderId, "AAPL", "BUY", "MARKET", "DAY", status, "1", null, null, "USD",
                "2026-08-02T10:00:00Z", null,
                new TossApiDtos.OrderExecution("1", "180", "180", "0", "0",
                        "2026-08-02T10:00:01Z", null));
    }
}
