package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class InvestmentOnboardingControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void readinessAndRegistrationRoutesUseAuthenticatedPrincipal() throws Exception {
        var service = mock(InvestmentOnboardingService.class);
        var readiness = new InvestmentOnboardingService.ReadinessView("NEWT", false, "DATA_MISSING", null,
                Map.of("price", "DATA_MISSING"), Map.of(), new InvestmentOnboardingService.PipelineSummary(
                "NEVER_RUN", null, null), new InvestmentOnboardingService.ThesisSummary(
                false, null, null, "NO_THESIS", "NOT_VERIFIED", null, false, false, false, List.of()),
                new InvestmentOnboardingService.RiskMarkSummary(null, null, null, null, null,
                        "DATA_MISSING", null), List.of(), List.of("SECURITY_DATA_NOT_CAPTURED"));
        when(service.readiness(USER_ID, "NEWT")).thenReturn(readiness);
        when(service.register(USER_ID, "NEWT")).thenReturn(
                new InvestmentOnboardingService.RegistrationView("NEWT", true, false, "WATCH"));
        MockMvc mvc = standaloneSetup(new InvestmentOnboardingController(service)).build();
        var principal = (java.security.Principal) () -> USER_ID.toString();

        mvc.perform(get("/investment/onboarding/readiness").param("ticker", "NEWT").principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(false))
                .andExpect(jsonPath("$.status").value("DATA_MISSING"));
        mvc.perform(post("/investment/onboarding/register").principal(principal)
                        .contentType("application/json")
                        .content(new ObjectMapper().writeValueAsString(Map.of("ticker", "NEWT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("NEWT"))
                .andExpect(jsonPath("$.alreadyRegistered").value(false));

        verify(service).readiness(USER_ID, "NEWT");
        verify(service).register(USER_ID, "NEWT");
    }

    @Test
    void registrationRequiresAValidAuthenticatedPrincipal() throws Exception {
        MockMvc mvc = standaloneSetup(new InvestmentOnboardingController(mock(InvestmentOnboardingService.class)))
                .build();

        mvc.perform(post("/investment/onboarding/register").contentType("application/json")
                        .content("{\"ticker\":\"NEWT\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHENTICATED_USER_INVALID"));
    }
}
