package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.SellableQuantitySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * kill switch 조작 엔드포인트({@code POST /api/v1/trading/kill-switch}) 통합 테스트 (플랜 원장 E4).
 * 조작 비대칭을 HTTP 경계에서 확인한다: engage 는 step-up 없이 200, disengage 는 토큰이 없으면 401,
 * 유효한 토큰이 있으면 200.
 */
@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
@Import(KillSwitchApiIntegrationTest.NoBrokerConfiguration.class)
class KillSwitchApiIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        jdbc.execute("TRUNCATE kill_switch_ledger, order_approval_step_up_tokens, users CASCADE");
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", USER_ID);
    }

    @Test
    void engageDoesNotRequireStepUp() throws Exception {
        mockMvc.perform(post("/api/v1/trading/kill-switch")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"GLOBAL\",\"engaged\":true,\"reason\":\"panic\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("GLOBAL"))
                .andExpect(jsonPath("$.engaged").value(true))
                .andExpect(jsonPath("$.version").value(1));

        assertRows(1);
    }

    @Test
    void disengageWithoutStepUpTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/trading/kill-switch")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"GLOBAL\",\"engaged\":true,\"reason\":\"panic\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/trading/kill-switch")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"GLOBAL\",\"engaged\":false,\"reason\":\"resume\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_STEP_UP_REQUIRED"));

        // 해제 실패로 원장에는 engage 행 하나만 남는다.
        assertRows(1);
    }

    @Test
    void disengageWithValidStepUpTokenSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/trading/kill-switch")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"GLOBAL\",\"engaged\":true,\"reason\":\"panic\"}"))
                .andExpect(status().isOk());
        insertDisengageToken(KillSwitchLedger.GLOBAL_TARGET, "api-tok");

        mockMvc.perform(post("/api/v1/trading/kill-switch")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("X-Step-Up-Token", "api-tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"GLOBAL\",\"engaged\":false,\"reason\":\"resume\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engaged").value(false))
                .andExpect(jsonPath("$.version").value(2));

        assertRows(2);
    }

    // --- BC-7: 상태 조회 -------------------------------------------------------------------

    @Test
    void unsetStateIsReportedAsUnknownRatherThanDisengaged() throws Exception {
        // 미설정과 "해제됨"은 다른 사실이다. engaged 를 false 로 뭉개지 않고 null 로 비운다.
        mockMvc.perform(get("/api/v1/trading/kill-switch")
                        .param("scope", "USER")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("USER"))
                .andExpect(jsonPath("$.targetId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.engaged").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.changedAt").doesNotExist());
    }

    @Test
    void engagedStateIsReportedWithLedgerVersionAndReason() throws Exception {
        mockMvc.perform(post("/api/v1/trading/kill-switch")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"USER\",\"targetId\":\"" + USER_ID
                                + "\",\"engaged\":true,\"reason\":\"panic\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/trading/kill-switch")
                        .param("scope", "USER")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("USER"))
                .andExpect(jsonPath("$.engaged").value(true))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reason").value("panic"))
                .andExpect(jsonPath("$.changedAt").isNotEmpty());
    }

    @Test
    void disengagedStateIsDistinctFromUnsetAndKeepsLatestVersion() throws Exception {
        mockMvc.perform(post("/api/v1/trading/kill-switch")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"GLOBAL\",\"engaged\":true,\"reason\":\"panic\"}"))
                .andExpect(status().isOk());
        insertDisengageToken(KillSwitchLedger.GLOBAL_TARGET, "state-tok");
        mockMvc.perform(post("/api/v1/trading/kill-switch")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("X-Step-Up-Token", "state-tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"GLOBAL\",\"engaged\":false,\"reason\":\"resume\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/trading/kill-switch")
                        .param("scope", "GLOBAL")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("GLOBAL"))
                .andExpect(jsonPath("$.targetId").value(KillSwitchLedger.GLOBAL_TARGET.toString()))
                .andExpect(jsonPath("$.engaged").value(false))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reason").value("resume"));
    }

    @Test
    void anotherUsersSwitchIsNotReadable() throws Exception {
        var otherUserId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", otherUserId);

        mockMvc.perform(get("/api/v1/trading/kill-switch")
                        .param("scope", "USER")
                        .param("targetId", otherUserId.toString())
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("KILL_SWITCH_TARGET_FORBIDDEN"));
    }

    @Test
    void unknownScopeIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/trading/kill-switch")
                        .param("scope", "EVERYTHING")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("KILL_SWITCH_INPUT_INVALID"));
    }

    private void insertDisengageToken(UUID subjectRef, String rawToken) {
        var now = Instant.now();
        jdbc.update("""
                INSERT INTO order_approval_step_up_tokens (
                    token_hash, user_id, order_intent_id, subject_kind, subject_ref,
                    issued_at, expires_at, consumed_at
                ) VALUES (?, ?, NULL, 'KILL_SWITCH_DISENGAGE', ?, ?, ?, NULL)
                """,
                OrderApprovalStepUpService.sha256Hex(rawToken),
                USER_ID,
                subjectRef,
                at(now.minusSeconds(1)),
                at(now.plusSeconds(300)));
    }

    private void assertRows(long expected) {
        var actual = jdbc.queryForObject("SELECT count(*) FROM kill_switch_ledger", Long.class);
        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @TestConfiguration
    static class NoBrokerConfiguration {

        @Bean
        BrokerAdapter brokerAdapter() {
            return new UnusedBrokerAdapter();
        }
    }

    static final class UnusedBrokerAdapter implements BrokerAdapter {
        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            throw new AssertionError("kill switch API must not call a broker");
        }

        @Override
        public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
            throw new AssertionError("kill switch API must not call a broker");
        }

        @Override
        public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
            throw new AssertionError("kill switch API must not call a broker");
        }

        @Override
        public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
            throw new AssertionError("kill switch API must not call a broker");
        }

        @Override
        public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(
                BrokerAccountRef account,
                Currency currency
        ) {
            throw new AssertionError("kill switch API must not call a broker");
        }

        @Override
        public BrokerResponse<SellableQuantitySnapshot> getSellableQuantity(BrokerAccountRef account, String symbol) {
            throw new AssertionError("kill switch API must not call a broker");
        }
    }
}
