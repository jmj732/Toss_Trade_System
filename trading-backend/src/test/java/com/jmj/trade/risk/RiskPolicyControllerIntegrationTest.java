package com.jmj.trade.risk;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "pre-trade-risk.max-order-amount.krw=1000000",
                "pre-trade-risk.max-order-amount.usd=1000",
                "pre-trade-risk.max-quantity=5",
                "pre-trade-risk.max-concentration=0.50"
        })
class RiskPolicyControllerIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RiskPolicyService policies;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.execute("TRUNCATE risk_policy_history, risk_policies, users CASCADE");
        jdbc.update("INSERT INTO users (id) VALUES (?), (?)", USER_ID, OTHER_USER_ID);
    }

    @Test
    void reportsThePlatformDefaultUntilTheUserCustomizesIt() throws Exception {
        mockMvc.perform(get("/api/v1/risk-policy").with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.customized").value(false))
                .andExpect(jsonPath("$.maxOrderAmountKrw").value(1000000))
                .andExpect(jsonPath("$.maxOrderAmountUsd").value(1000))
                .andExpect(jsonPath("$.maxQuantity").value(5))
                .andExpect(jsonPath("$.maxConcentration").value(0.50));
    }

    @Test
    void updatesFromDefaultThenReflectsTheNewLimitsOnRead() throws Exception {
        mockMvc.perform(put("/api/v1/risk-policy")
                        .with(user(USER_ID.toString())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"maxOrderAmountKrw":500000,
                                 "maxOrderAmountUsd":500,"maxQuantity":2,"maxConcentration":0.30}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.customized").value(true))
                .andExpect(jsonPath("$.maxQuantity").value(2));

        mockMvc.perform(get("/api/v1/risk-policy").with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.maxOrderAmountKrw").value(500000))
                .andExpect(jsonPath("$.maxConcentration").value(0.30));
    }

    @Test
    void rejectsAStaleExpectedVersion() throws Exception {
        mockMvc.perform(put("/api/v1/risk-policy")
                        .with(user(USER_ID.toString())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"maxOrderAmountKrw":500000,
                                 "maxOrderAmountUsd":500,"maxQuantity":2,"maxConcentration":0.30}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/risk-policy")
                        .with(user(USER_ID.toString())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"maxOrderAmountKrw":400000,
                                 "maxOrderAmountUsd":400,"maxQuantity":1,"maxConcentration":0.20}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RISK_POLICY_VERSION_CONFLICT"));
    }

    @Test
    void rejectsConcentrationAboveOne() throws Exception {
        mockMvc.perform(put("/api/v1/risk-policy")
                        .with(user(USER_ID.toString())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"maxOrderAmountKrw":500000,
                                 "maxOrderAmountUsd":500,"maxQuantity":2,"maxConcentration":1.5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RISK_POLICY_INPUT_INVALID"));
    }

    @Test
    void rejectsNonPositiveLimits() throws Exception {
        mockMvc.perform(put("/api/v1/risk-policy")
                        .with(user(USER_ID.toString())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"maxOrderAmountKrw":0,
                                 "maxOrderAmountUsd":500,"maxQuantity":2,"maxConcentration":0.3}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RISK_POLICY_INPUT_INVALID"));
    }

    @Test
    void recordsEveryAcceptedEditInHistoryNewestFirst() throws Exception {
        update(USER_ID, 0, "500000", "500", "2", "0.30");
        update(USER_ID, 1, "400000", "400", "1", "0.20");

        mockMvc.perform(get("/api/v1/risk-policy/history").with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].maxQuantity").value(1))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].maxQuantity").value(2));
    }

    @Test
    void oneUsersPolicyIsInvisibleAndUneditableByAnother() throws Exception {
        update(USER_ID, 0, "500000", "500", "2", "0.30");

        mockMvc.perform(get("/api/v1/risk-policy").with(user(OTHER_USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.customized").value(false));
    }

    @Test
    void concurrentFirstEditsForTheSameUserProduceExactlyOneWinner() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> firstEdit = () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                policies.update(
                        USER_ID, 0L,
                        new RiskPolicyService.RiskPolicyInput(
                                new BigDecimal("500000"), new BigDecimal("500"),
                                new BigDecimal("2"), new BigDecimal("0.30")),
                        "test");
                return true;
            } catch (RiskPolicyException exception) {
                assertThat(exception.code()).isEqualTo(RiskPolicyException.Code.VERSION_CONFLICT);
                return false;
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(firstEdit);
            var second = executor.submit(firstEdit);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }

        assertThat(jdbc.queryForObject(
                "SELECT version FROM risk_policies WHERE user_id = ?", Long.class, USER_ID))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM risk_policy_history WHERE user_id = ?", Long.class, USER_ID))
                .isEqualTo(1L);
    }

    @Test
    void policyHistoryRowsAreAppendOnlyAtPostgresBoundary() throws Exception {
        update(USER_ID, 0, "500000", "500", "2", "0.30");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE risk_policy_history SET max_quantity = 99 WHERE user_id = ?", USER_ID))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM risk_policy_history WHERE user_id = ?", USER_ID))
                .isInstanceOf(RuntimeException.class);
    }

    private void update(
            UUID userId,
            long expectedVersion,
            String krw,
            String usd,
            String quantity,
            String concentration
    ) throws Exception {
        mockMvc.perform(put("/api/v1/risk-policy")
                        .with(user(userId.toString())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":%d,"maxOrderAmountKrw":%s,
                                 "maxOrderAmountUsd":%s,"maxQuantity":%s,"maxConcentration":%s}
                                """.formatted(expectedVersion, krw, usd, quantity, concentration)))
                .andExpect(status().isOk());
    }
}
