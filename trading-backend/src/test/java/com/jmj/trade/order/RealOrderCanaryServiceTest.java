package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.toss.TossCredentialProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealOrderCanaryServiceTest {

    @Test
    void missingCanaryConfigurationReturnsPreflightOnlyWithoutBrokerAccess() {
        var quotes = provider(BrokerAdapter.class);
        var orders = provider(BrokerOrderPort.class);
        var properties = new RealOrderCanaryProperties(
                false, null, null, null, null, null, null, null);
        var audit = mock(RealOrderCanaryAuditLedger.class);
        var service = new RealOrderCanaryService(
                quotes, provider(TossCredentialProvider.class), provider(LiveOrderActivationService.class),
                provider(LiveOrderSafetyLedger.class), provider(KillSwitchStateReader.class), orders,
                provider(OrderApprovalStepUpService.class), provider(OrderSubmissionService.class),
                provider(BrokerOrderRepository.class), provider(UnknownAttemptReconciler.class),
                mock(JdbcTemplate.class), audit, properties, fixedClock());

        var result = service.run(UUID.randomUUID(), order(), Instant.parse("2026-08-02T03:00:00Z"), "test", "missing-config");

        assertThat(result.outcome()).isEqualTo("PREFLIGHT_ONLY");
        assertThat(result.orderSubmitted()).isFalse();
        assertThat(result.blockers()).contains("CANARY_DISABLED");
        verify(quotes, never()).getIfAvailable();
        verify(orders, never()).getIfAvailable();
        verify(audit).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("PREFLIGHT"),
                org.mockito.ArgumentMatchers.eq("PREFLIGHT_ONLY"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("CANARY_DISABLED"), org.mockito.ArgumentMatchers.any());
    }

    private static RealOrderCanaryService.CanaryOrder order() {
        return new RealOrderCanaryService.CanaryOrder(OrderSide.BUY, OrderType.LIMIT,
                "AAPL", BigDecimal.ONE, new BigDecimal("180"), Currency.USD);
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-02T03:00:00Z"), ZoneOffset.UTC);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(Class<T> type) {
        return mock(ObjectProvider.class);
    }
}
