package com.jmj.trade.broker.connection;

import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Quote;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrokerSurfaceServiceTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");

    @Test
    void unsupportedSurfaceRequiresOwnedActiveConnection() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), mock(BrokerAdapter.class));

        var response = service.unsupported(USER, CONNECTION);

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.UNAVAILABLE);
        assertThat(response.unavailableReason()).isEqualTo("PROVIDER_UNSUPPORTED");
    }

    @Test
    void pricesExposeProviderFieldsWithoutInventingBidAsk() {
        var jdbc = mock(JdbcTemplate.class);
        var broker = mock(BrokerAdapter.class);
        var observedAt = Instant.parse("2026-08-09T00:00:00Z");
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        when(broker.getQuote(any(BrokerConnectionRef.class), eq("AAPL"))).thenReturn(new BrokerResponse<>(
                new Quote(new BrokerConnectionRef(CONNECTION), "AAPL", Currency.USD,
                        new BigDecimal("100"), null, null, null, observedAt),
                BrokerOrderPort.localMetadata()));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), broker);

        var response = service.prices(USER, CONNECTION, "AAPL");

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.DEGRADED);
        assertThat(response.unknownFields()).containsExactly("AAPL.bidPrice", "AAPL.askPrice");
        assertThat(response.data()).singleElement().satisfies(price -> {
            assertThat(price.lastPrice()).isEqualByComparingTo("100");
            assertThat(price.bidPrice()).isNull();
            assertThat(price.askPrice()).isNull();
        });
    }

    @Test
    void pricesRejectProviderQuoteFromAnotherConnectionOrSymbol() {
        var jdbc = mock(JdbcTemplate.class);
        var broker = mock(BrokerAdapter.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        when(broker.getQuote(any(BrokerConnectionRef.class), eq("AAPL"))).thenReturn(new BrokerResponse<>(
                new Quote(new BrokerConnectionRef(UUID.randomUUID()), "MSFT", Currency.USD,
                        new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("101"), null, Instant.now()),
                BrokerOrderPort.localMetadata()));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), broker);

        var response = service.prices(USER, CONNECTION, "AAPL");

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.DEGRADED);
        assertThat(response.unknownFields()).containsExactly("AAPL.quote");
        assertThat(response.data()).isEmpty();
    }
}
