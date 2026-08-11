package com.jmj.trade.account;

import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BrokerSurfaceControllerTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");

    @Test
    void pricesRouteReturnsTheProviderEnvelopeForTheAuthenticatedUser() throws Exception {
        var surfaces = mock(BrokerSurfaceService.class);
        when(surfaces.prices(USER, CONNECTION, "AAPL")).thenReturn(BrokerSurfaceResponse.degraded(
                List.of(new BrokerSurfaceResponse.PriceView(
                        "AAPL", new BigDecimal("100"), null, null, "USD", Instant.parse("2026-08-09T00:00:00Z"), null)),
                false, true, List.of("AAPL.bidPrice", "AAPL.askPrice"), "PRICE_PARTIAL"));
        MockMvc mvc = standaloneSetup(new BrokerSurfaceController(surfaces)).build();

        mvc.perform(get("/api/v1/broker-connections/{connectionId}/prices", CONNECTION)
                        .principal(() -> USER.toString())
                        .param("symbols", "AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data[0].lastPrice").value(100))
                .andExpect(jsonPath("$.data[0].bidPrice").doesNotExist())
                .andExpect(jsonPath("$.unknownFields[0]").value("AAPL.bidPrice"));
        verify(surfaces).prices(USER, CONNECTION, "AAPL");
    }

    @Test
    void candlesRouteReturnsTheProviderEnvelope() throws Exception {
        var surfaces = mock(BrokerSurfaceService.class);
        when(surfaces.candles(USER, CONNECTION, "AAPL", "1d", 100, null, true))
                .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.CandleSeriesView(
                        "AAPL", "1d", true,
                        List.of(new BrokerSurfaceResponse.CandleView(
                                Instant.parse("2026-08-09T00:00:00Z"),
                                new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"),
                                new BigDecimal("100.5"), new BigDecimal("1000"), "USD")),
                        null), List.of(new BrokerSurfaceResponse.ProviderProvenance(
                        "TOSS", "/api/v1/candles", "USD",
                        Instant.parse("2026-08-09T00:00:00Z"), Instant.parse("2026-08-09T00:01:00Z")))));
        MockMvc mvc = standaloneSetup(new BrokerSurfaceController(surfaces)).build();

        mvc.perform(get("/api/v1/broker-connections/{connectionId}/candles", CONNECTION)
                        .principal(() -> USER.toString())
                        .param("symbol", "AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.candles[0].closePrice").value(100.5))
                .andExpect(jsonPath("$.provenance[0].provider").value("TOSS"));
    }
}
