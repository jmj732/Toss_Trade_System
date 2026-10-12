package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class InvestmentRiskPreflightControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void postReturnsPreflightViewForAuthenticatedUser() throws Exception {
        var service = mock(InvestmentRiskPreflightService.class);
        var input = new InvestmentRiskPreflightService.PreflightInput("AAPL", new BigDecimal("2"));
        var view = new InvestmentRiskPreflightService.PreflightView("AAPL", input.quantity(), true, List.of(),
                new InvestmentRiskPreflightService.ThesisReceipt("CONFIRMED", 3L, UUID.randomUUID(),
                        "USER_SESSION", "USER_CONFIRMED", null, null, null, null, false, true),
                new InvestmentRiskPreflightService.PortfolioGate("OK", true, true, "NOT_APPLICABLE", null),
                new InvestmentRiskPreflightService.PolicyGate("WITHIN_POLICY", true, true, true,
                        new BigDecimal("10"), new BigDecimal("1000"), new BigDecimal("0.4"),
                        new BigDecimal("0.1")),
                new InvestmentRiskPreflightService.CashGate("SUFFICIENT", "USD", new BigDecimal("500"),
                        new BigDecimal("200"), new BigDecimal("300"), "BROKER_REPORTED_BUYING_POWER"),
                Map.of(), Map.of());
        when(service.preflight(USER_ID, input)).thenReturn(view);
        var mvc = standaloneSetup(new InvestmentRiskPreflightController(service)).build();

        mvc.perform(post("/investment/risk/preflight").principal(principal(USER_ID))
                        .contentType("application/json").content("{\"ticker\":\"AAPL\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("AAPL"))
                .andExpect(jsonPath("$.buyEligible").value(true))
                .andExpect(jsonPath("$.cash.availableAfter").value(300));

        verify(service).preflight(USER_ID, input);
    }

    @Test
    void malformedJsonAndInvalidQuantityReturnSanitizedBadRequest() throws Exception {
        var service = mock(InvestmentRiskPreflightService.class);
        var mvc = standaloneSetup(new InvestmentRiskPreflightController(service)).build();
        var zeroQuantity = new InvestmentRiskPreflightService.PreflightInput("AAPL", BigDecimal.ZERO);
        var missingQuantity = new InvestmentRiskPreflightService.PreflightInput("AAPL", null);
        doThrow(new InvestmentException(InvestmentException.Code.INVALID_INPUT))
                .when(service).preflight(USER_ID, zeroQuantity);
        doThrow(new InvestmentException(InvestmentException.Code.INVALID_INPUT))
                .when(service).preflight(USER_ID, missingQuantity);

        mvc.perform(post("/investment/risk/preflight").principal(principal(USER_ID))
                        .contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVESTMENT_INPUT_INVALID"));
        mvc.perform(post("/investment/risk/preflight").principal(principal(USER_ID))
                        .contentType("application/json").content("{\"ticker\":\"AAPL\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVESTMENT_INPUT_INVALID"));
        mvc.perform(post("/investment/risk/preflight").principal(principal(USER_ID))
                        .contentType("application/json").content("{\"ticker\":\"AAPL\",\"quantity\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVESTMENT_INPUT_INVALID"));

        verify(service).preflight(USER_ID, zeroQuantity);
        verify(service).preflight(USER_ID, missingQuantity);
    }

    @Test
    void invalidPrincipalReturnsSanitizedForbidden() throws Exception {
        var service = mock(InvestmentRiskPreflightService.class);
        var mvc = standaloneSetup(new InvestmentRiskPreflightController(service)).build();

        mvc.perform(post("/investment/risk/preflight").principal(principal("not-a-uuid"))
                        .contentType("application/json").content("{\"ticker\":\"AAPL\",\"quantity\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHENTICATED_USER_INVALID"));

        verifyNoInteractions(service);
    }

    private static Principal principal(UUID userId) {
        return principal(userId.toString());
    }

    private static Principal principal(String name) {
        return () -> name;
    }
}
