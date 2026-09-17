package com.jmj.trade.connector;

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

class PortfolioStateControllerTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");

    @Test
    void dashboardReadsTheSharedPortfolioStateContractWithHumanAuth() throws Exception {
        var service = mock(ConnectorService.class);
        when(service.portfolioState(USER, CONNECTION)).thenReturn(new ConnectorResponse.PortfolioState(
                Instant.parse("2026-09-15T00:00:00Z"), "USD",
                new ConnectorResponse.StateAccount(new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("10")),
                List.of(), List.of(), new ConnectorResponse.Risk(BigDecimal.ZERO, new BigDecimal("90"), new BigDecimal("10")),
                false, null, false, List.of(), List.of()));
        MockMvc mvc = standaloneSetup(new PortfolioStateController(service)).build();

        mvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio/state", CONNECTION)
                        .principal(() -> USER.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.totalValue").value(1000))
                .andExpect(jsonPath("$.risk.investedPct").value(90));

        verify(service).portfolioState(USER, CONNECTION);
    }
}
