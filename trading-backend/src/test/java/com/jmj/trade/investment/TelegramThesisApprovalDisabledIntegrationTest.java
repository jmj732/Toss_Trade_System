package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Default configuration (TELEGRAM_APPROVAL_ENABLED unset): webhook 404 before any secret check, creation 409. */
@SpringBootTest(classes = TradingBackendApplication.class)
class TelegramThesisApprovalDisabledIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER = UUID.fromString("11990000-0000-7000-8000-0000000000d1");

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InvestmentContextService investment;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.execute("TRUNCATE telegram_webhook_updates, users CASCADE");
        jdbc.update("INSERT INTO users(id) VALUES (?)", USER);
    }

    @Test
    void webhookIsNotFoundWhenTheWorkflowIsOff() throws Exception {
        var body = "{\"update_id\":1,\"callback_query\":{\"id\":\"q\",\"from\":{\"id\":1},"
                + "\"message\":{\"chat\":{\"id\":1}},\"data\":\"A:x\"}}";
        mvc.perform(post("/api/v1/telegram/webhook").contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", "anything")
                .contentType("application/json").content(body)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM telegram_webhook_updates", Integer.class)).isZero();
    }

    @Test
    void creationIsRejectedWith409WhenTheWorkflowIsOff() throws Exception {
        investment.putThesisProposal(USER, "ACME", new InvestmentContextService.ThesisInput("Core", null, null,
                null, null, null, new BigDecimal("90"), "AI_PROPOSED", null, null, null), null);
        mvc.perform(post("/investment/securities/ACME/thesis/approval-requests").with(user(USER.toString()))
                        .contentType("application/json").content("{\"candidateSource\":\"EXISTING_PROPOSAL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THESIS_APPROVAL_NOT_READY"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_approval_requests", Integer.class))
                .isZero();
    }
}
