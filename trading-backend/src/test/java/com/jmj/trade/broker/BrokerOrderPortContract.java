package com.jmj.trade.broker;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BrokerOrderPort} 의 구현 독립 계약(플랜 원장 B4). Paper 와 Toss 하위 클래스가 이 같은
 * 계약을 각자의 구현에 대해 검증한다.
 *
 * <p>여기 있는 단언들은 <b>모든 구현이 동일하게</b> 만족해야 하는 불변식이다. 구현별로 갈리는
 * 능력(Paper 는 전송/조회 지원, Toss 는 미지원)은 각 하위 클래스에서 별도로 고정한다.
 */
public abstract class BrokerOrderPortContract {

    protected abstract BrokerOrderPort port();

    protected abstract BrokerAccountRef account();

    protected BrokerOrderRequest marketRequest() {
        return new BrokerOrderRequest(
                BrokerOrderSide.BUY, BrokerOrderType.MARKET, "AAPL", new BigDecimal("1"), null, Currency.USD);
    }

    protected BrokerOrderRequest limitRequest() {
        return new BrokerOrderRequest(
                BrokerOrderSide.BUY, BrokerOrderType.LIMIT, "MSFT", new BigDecimal("2"),
                new BigDecimal("100"), Currency.USD);
    }

    @Test
    protected void quantityModificationIsRejectedIdenticallyAsContractViolation() {
        var response = port().modifyOrder(
                account(), BrokerOrderModification.changeQuantity("broker-order-qty", new BigDecimal("5")));

        assertThat(response.value().status()).isEqualTo(BrokerOrderDispatchStatus.REJECTED);
        assertThat(response.value().reason()).isEqualTo(BrokerErrorCategory.CONTRACT);
        assertThat(response.value().isRejected()).isTrue();
    }

    @Test
    protected void dispatchOutcomesAreTypedAndNeverSilentlySucceed() {
        var place = port().placeOrder(account(), marketRequest(), "idem-" + UUID.randomUUID()).value();

        assertThat(place).isNotNull();
        assertThat(place.status()).isNotNull();
        if (place.status() == BrokerOrderDispatchStatus.ACCEPTED) {
            assertThat(place.brokerOrderId()).isNotBlank();
        } else {
            assertThat(place.status()).isIn(
                    BrokerOrderDispatchStatus.REJECTED,
                    BrokerOrderDispatchStatus.UNKNOWN,
                    BrokerOrderDispatchStatus.UNSUPPORTED);
        }
    }

    @Test
    protected void unknownDispatchIsDistinctFromRejection() {
        var unknown = BrokerOrderAck.unknown("broker-order-1", "idem-1");
        var rejected = BrokerOrderAck.rejected(BrokerErrorCategory.BROKER_UNAVAILABLE, null, "idem-1");

        assertThat(unknown.status()).isNotEqualTo(rejected.status());
        assertThat(unknown.isUnknown()).isTrue();
        assertThat(unknown.isRejected()).isFalse();
        assertThat(rejected.isRejected()).isTrue();
        assertThat(rejected.isUnknown()).isFalse();
    }

    @Test
    protected void openClosedClassificationIsSharedAcrossImplementations() {
        assertThat(BrokerOrderLifecycle.PENDING.group()).isEqualTo(BrokerOrderGroup.OPEN);
        assertThat(BrokerOrderLifecycle.PARTIALLY_FILLED.group()).isEqualTo(BrokerOrderGroup.OPEN);
        assertThat(BrokerOrderLifecycle.CANCELING.group()).isEqualTo(BrokerOrderGroup.OPEN);
        assertThat(BrokerOrderLifecycle.REPLACING.group()).isEqualTo(BrokerOrderGroup.OPEN);
        assertThat(BrokerOrderLifecycle.FILLED.group()).isEqualTo(BrokerOrderGroup.CLOSED);
        assertThat(BrokerOrderLifecycle.CANCELED.group()).isEqualTo(BrokerOrderGroup.CLOSED);
        assertThat(BrokerOrderLifecycle.REJECTED.group()).isEqualTo(BrokerOrderGroup.CLOSED);
        assertThat(BrokerOrderLifecycle.CANCEL_REJECTED.group()).isEqualTo(BrokerOrderGroup.CLOSED);
        assertThat(BrokerOrderLifecycle.REPLACE_REJECTED.group()).isEqualTo(BrokerOrderGroup.CLOSED);
        assertThat(BrokerOrderLifecycle.REPLACED.group()).isEqualTo(BrokerOrderGroup.CLOSED);
    }
}
