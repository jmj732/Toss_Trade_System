package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerOrderAck;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderLifecycle;
import com.jmj.trade.broker.BrokerOrderModification;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderRequest;
import com.jmj.trade.broker.BrokerOrderSide;
import com.jmj.trade.broker.BrokerOrderType;
import com.jmj.trade.broker.BrokerOrderView;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BrokerOrderPortReconciliationProbe} 단위 테스트 (플랜 원장 E5).
 *
 * <p>"찾지 못함"(성공 조회 + 없음 → {@code Absent})과 "찾을 수 없었음"(조회 예외 → {@code Unavailable})을
 * 코드 수준에서 구분함을 보인다. 실패 사유에 브로커 원문 식별자를 담지 않음도 확인한다(SPEC:1151).
 */
class BrokerOrderPortReconciliationProbeTest {

    private static final BrokerAccountRef ACCOUNT = new BrokerAccountRef(
            UUID.randomUUID(), "acct-native", "PAPER", "****1234");
    private static final String CLIENT = "client-42";

    @Test
    void matchingClientOrderIdInGroupIsMatched() {
        var probe = new BrokerOrderPortReconciliationProbe(
                fixedPort(List.of(view(CLIENT, BrokerOrderLifecycle.FILLED, "10"))));

        var outcome = probe.probe(ACCOUNT, BrokerOrderGroup.CLOSED, CLIENT);

        assertThat(outcome).isInstanceOf(ReconciliationGroupOutcome.Matched.class);
        var matched = (ReconciliationGroupOutcome.Matched) outcome;
        assertThat(matched.brokerReturnedClientOrderId()).isEqualTo(CLIENT);
        assertThat(matched.status()).isEqualTo(BrokerOrderStatus.FILLED);
        assertThat(matched.filledQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void successfulQueryWithNoMatchIsAbsentNotUnavailable() {
        var probe = new BrokerOrderPortReconciliationProbe(
                fixedPort(List.of(view("someone-else", BrokerOrderLifecycle.PENDING, "0"))));

        assertThat(probe.probe(ACCOUNT, BrokerOrderGroup.OPEN, CLIENT))
                .isInstanceOf(ReconciliationGroupOutcome.Absent.class);
    }

    @Test
    void emptyGroupIsAbsent() {
        var probe = new BrokerOrderPortReconciliationProbe(fixedPort(List.of()));

        assertThat(probe.probe(ACCOUNT, BrokerOrderGroup.OPEN, CLIENT))
                .isInstanceOf(ReconciliationGroupOutcome.Absent.class);
    }

    @Test
    void successfulTossStyleQueryWithoutClientOrderIdsIsUnavailable() {
        var probe = new BrokerOrderPortReconciliationProbe(
                fixedPort(List.of(view(null, BrokerOrderLifecycle.FILLED, "10"))));

        assertThat(probe.probe(ACCOUNT, BrokerOrderGroup.CLOSED, CLIENT))
                .isInstanceOf(ReconciliationGroupOutcome.Unavailable.class);
    }

    @Test
    void queryFailureIsUnavailableAndDoesNotLeakBrokerIdentifiers() {
        var probe = new BrokerOrderPortReconciliationProbe(failingPort());

        var outcome = probe.probe(ACCOUNT, BrokerOrderGroup.CLOSED, CLIENT);

        assertThat(outcome).isInstanceOf(ReconciliationGroupOutcome.Unavailable.class);
        var reason = ((ReconciliationGroupOutcome.Unavailable) outcome).reason();
        assertThat(reason).contains("CLOSED").doesNotContain("acct-native");
    }

    private static BrokerOrderView view(String idempotencyKey, BrokerOrderLifecycle status, String filled) {
        return new BrokerOrderView(
                "broker-" + idempotencyKey,
                idempotencyKey,
                BrokerOrderSide.BUY,
                BrokerOrderType.LIMIT,
                "AAPL",
                new BigDecimal("10"),
                new BigDecimal(filled),
                new BigDecimal("100"),
                Currency.USD,
                status);
    }

    private static BrokerOrderPort fixedPort(List<BrokerOrderView> views) {
        return new StubPort() {
            @Override
            public BrokerResponse<List<BrokerOrderView>> getOrders(BrokerAccountRef account, BrokerOrderGroup group) {
                return new BrokerResponse<>(List.copyOf(views), BrokerOrderPort.localMetadata());
            }
        };
    }

    private static BrokerOrderPort failingPort() {
        return new StubPort() {
            @Override
            public BrokerResponse<List<BrokerOrderView>> getOrders(BrokerAccountRef account, BrokerOrderGroup group) {
                throw new BrokerException(
                        BrokerErrorCategory.BROKER_UNAVAILABLE, null, null, null, null, true, "boom");
            }
        };
    }

    private abstract static class StubPort implements BrokerOrderPort {
        @Override
        public BrokerResponse<BrokerOrderAck> placeOrder(
                BrokerAccountRef account, BrokerOrderRequest request, String idempotencyKey) {
            throw new AssertionError("unexpected placeOrder");
        }

        @Override
        public BrokerResponse<BrokerOrderAck> cancelOrder(BrokerAccountRef account, String brokerOrderId) {
            throw new AssertionError("unexpected cancelOrder");
        }

        @Override
        public BrokerResponse<BrokerOrderView> getOrder(BrokerAccountRef account, String brokerOrderId) {
            throw new AssertionError("unexpected getOrder");
        }

        @Override
        public BrokerResponse<BrokerOrderAck> modifyOrder(
                BrokerAccountRef account, BrokerOrderModification modification) {
            throw new AssertionError("unexpected modifyOrder");
        }
    }
}
