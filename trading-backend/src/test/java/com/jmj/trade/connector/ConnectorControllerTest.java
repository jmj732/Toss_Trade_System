package com.jmj.trade.connector;

import com.jmj.trade.account.PortfolioReadService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ConnectorControllerTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");

    @Test
    void portfolioRouteReturnsFreshReadThroughSnapshot() throws Exception {
        var service = mock(ConnectorService.class);
        var response = new ConnectorResponse.Portfolio(
                Instant.parse("2026-09-13T00:00:00Z"), false, false, List.of(), List.of(),
                Map.of("USD", new BigDecimal("1000")), List.of());
        when(service.portfolio(USER, CONNECTION)).thenReturn(response);
        MockMvc mvc = standaloneSetup(new ConnectorController(service)).build();

        mvc.perform(get("/api/v1/connector/portfolio")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.buyingPower.USD.cashBuyingPower").value(1000));

        verify(service).portfolio(USER, CONNECTION);
    }

    @Test
    void ordersAndFillsRoutesUseBoundConnection() throws Exception {
        var service = mock(ConnectorService.class);
        when(service.orders(USER, CONNECTION, "OPEN")).thenReturn(List.of());
        when(service.fills(USER, CONNECTION, null)).thenReturn(List.of());
        MockMvc mvc = standaloneSetup(new ConnectorController(service)).build();

        mvc.perform(get("/api/v1/connector/orders")
                        .principal(authentication())
                        .param("group", "OPEN"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/connector/fills")
                        .principal(authentication()))
                .andExpect(status().isOk());

        verify(service).orders(USER, CONNECTION, "OPEN");
        verify(service).fills(USER, CONNECTION, null);
    }

    @Test
    void portfolioRoutePreservesStaleUncertaintySignals() throws Exception {
        var service = mock(ConnectorService.class);
        when(service.portfolio(USER, CONNECTION)).thenReturn(new ConnectorResponse.Portfolio(
                Instant.parse("2026-09-13T00:00:00Z"), true, "LIVE_SYNC_FAILED", true,
                List.of("cash"), List.of("account.marketValue"), null, List.of(), Map.of()));
        MockMvc mvc = standaloneSetup(new ConnectorController(service)).build();

        mvc.perform(get("/api/v1/connector/portfolio").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.staleReason").value("LIVE_SYNC_FAILED"))
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.missingSections[0]").value("cash"))
                .andExpect(jsonPath("$.unknownFields[0]").value("account.marketValue"));
    }

    private static TestingAuthenticationToken authentication() {
        var token = new TestingAuthenticationToken(USER.toString(), null, "SCOPE_CONNECTOR_READ");
        token.setDetails(new ConnectorApiKeyService.AuthenticatedKey(
                UUID.randomUUID(), USER, CONNECTION, null));
        return token;
    }
}
