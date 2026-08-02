package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerOrderDispatchStatus;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderLifecycle;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderView;
import com.jmj.trade.broker.BrokerOrderPortContract;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link PaperTradingBroker} 가 {@link BrokerOrderPort} 계약을 만족하는지 검증한다.
 *
 * <p>포트 주문 메서드는 원장 의존성을 쓰지 않는 in-memory 시뮬레이션이라, 원장 협력자는
 * 호출되지 않는 mock 으로 주입한다(기존 DB 워크플로는 별도 통합 테스트가 보존).
 */
class PaperBrokerOrderPortContractTest extends BrokerOrderPortContract {

    private final PaperTradingBroker broker = new PaperTradingBroker(
            mock(OrderIntentRepository.class),
            mock(SubmissionIdempotencyKeyRepository.class),
            mock(SubmissionAttemptRepository.class),
            mock(BrokerOrderRepository.class),
            mock(OrderSubmissionService.class),
            mock(JdbcTemplate.class),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            2);

    private final BrokerAccountRef account =
            new BrokerAccountRef(UUID.randomUUID(), "paper-acct-1", "US_STOCK", "****1234");

    @Override
    protected BrokerOrderPort port() {
        return broker;
    }

    @Override
    protected BrokerAccountRef account() {
        return account;
    }

    @Test
    void samePlacementKeyDoesNotCreateDuplicateBrokerOrders() {
        var key = "idem-dup";
        var first = broker.placeOrder(account, marketRequest(), key).value();
        var second = broker.placeOrder(account, marketRequest(), key).value();

        assertThat(first.status()).isEqualTo(BrokerOrderDispatchStatus.ACCEPTED);
        assertThat(second.status()).isEqualTo(BrokerOrderDispatchStatus.ACCEPTED);
        assertThat(second.brokerOrderId()).isEqualTo(first.brokerOrderId());
        // MARKET 은 즉시 체결(CLOSED). 재호출로도 장부에는 주문이 하나뿐이다.
        assertThat(broker.getOrders(account, BrokerOrderGroup.CLOSED).value())
                .extracting(BrokerOrderView::brokerOrderId)
                .containsExactly(first.brokerOrderId());
    }

    @Test
    void limitRestsOpenMarketClosesAndGetOrderRoundTrips() {
        var limit = broker.placeOrder(account, limitRequest(), "idem-limit").value();
        var market = broker.placeOrder(account, marketRequest(), "idem-market").value();

        assertThat(broker.getOrders(account, BrokerOrderGroup.OPEN).value())
                .extracting(BrokerOrderView::brokerOrderId)
                .containsExactly(limit.brokerOrderId());
        assertThat(broker.getOrders(account, BrokerOrderGroup.CLOSED).value())
                .extracting(BrokerOrderView::brokerOrderId)
                .containsExactly(market.brokerOrderId());

        var view = broker.getOrder(account, limit.brokerOrderId()).value();
        assertThat(view.status()).isEqualTo(BrokerOrderLifecycle.PENDING);
        assertThat(view.group()).isEqualTo(BrokerOrderGroup.OPEN);
        assertThat(view.idempotencyKey()).isEqualTo("idem-limit");
    }

    @Test
    void cancelClosesAnOpenOrder() {
        var limit = broker.placeOrder(account, limitRequest(), "idem-cancel").value();

        var cancel = broker.cancelOrder(account, limit.brokerOrderId()).value();

        assertThat(cancel.status()).isEqualTo(BrokerOrderDispatchStatus.ACCEPTED);
        assertThat(broker.getOrder(account, limit.brokerOrderId()).value().status())
                .isEqualTo(BrokerOrderLifecycle.CANCELED);
        assertThat(broker.getOrders(account, BrokerOrderGroup.CLOSED).value())
                .extracting(BrokerOrderView::brokerOrderId)
                .contains(limit.brokerOrderId());
    }

    @Test
    void ordersAreScopedToTheirAccount() {
        var other = new BrokerAccountRef(UUID.randomUUID(), "paper-acct-2", "US_STOCK", "****9999");
        var mine = broker.placeOrder(account, limitRequest(), "idem-scope").value();

        assertThat(broker.getOrders(other, BrokerOrderGroup.OPEN).value()).isEmpty();
        assertThat(broker.getOrders(account, BrokerOrderGroup.OPEN).value())
                .extracting(BrokerOrderView::brokerOrderId)
                .containsExactly(mine.brokerOrderId());
    }
}
