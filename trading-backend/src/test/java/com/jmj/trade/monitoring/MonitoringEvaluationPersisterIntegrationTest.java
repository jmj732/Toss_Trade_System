package com.jmj.trade.monitoring;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TradingBackendApplication.class)
class MonitoringEvaluationPersisterIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MonitoringStateStore states;

    @Autowired
    private MonitoringEvaluationPersister persister;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE monitoring_state_history, monitoring_current_states, users CASCADE");
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
    }

    @Test
    void clearsPolicyBreachFromFreshKnownWeightsEvenWhenContextQualityIsPartial() throws Exception {
        var asOf = Instant.now();
        var policySubject = "POLICY:MAX_CONCENTRATION:SYMBOL:abc123";
        states.observe(USER_ID, "EVENT", policySubject, "BREACH", Map.of("summary", "seed"),
                "RISK_POLICY", "seed", asOf.minusSeconds(1));

        var request = request(asOf, List.of(position("AAPL", "0.10")));
        persister.persist(USER_ID, request, response(request, "PARTIAL",
                "[{\"symbol\":\"AAPL\",\"state\":\"NORMAL\",\"dataQuality\":\"PARTIAL\"}]"));

        assertThat(currentState(policySubject)).isEqualTo("CLEARED");
    }

    @Test
    void rejectsPartialPositionResponseBeforePersistingAnyState() throws Exception {
        var asOf = Instant.now();
        var request = request(asOf, List.of(position("AAPL", "0.10")));

        assertThatThrownBy(() -> persister.persist(USER_ID, request,
                response(request, "COMPLETE", "[]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("monitoring evaluation response contract mismatch");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_current_states WHERE user_id = ?",
                Integer.class, USER_ID)).isZero();
    }

    private MonitoringEvaluationContract.Request request(
            Instant asOf,
            List<MonitoringEvaluationContract.PositionInput> positions
    ) {
        return new MonitoringEvaluationContract.Request(UUID.randomUUID(), "1", asOf,
                new MonitoringEvaluationContract.MarketInput(List.of(), List.of(), List.of()),
                new MonitoringEvaluationContract.PortfolioInput(asOf, positions,
                        new MonitoringEvaluationContract.RiskPolicyInput(new BigDecimal("0.25"), null, null)),
                List.of(), List.of());
    }

    private JsonNode response(
            MonitoringEvaluationContract.Request request,
            String portfolioQuality,
            String responsePositions
    ) throws Exception {
        return objectMapper.valueToTree(Map.of(
                "requestId", request.requestId().toString(),
                "schemaVersion", "1",
                "asOf", request.asOf().toString(),
                "market", Map.of(
                        "state", "NORMAL",
                        "dataQuality", "PARTIAL",
                        "stateEvidence", List.of(),
                        "contagion", Map.of(
                                "EQUITY", axis(), "CREDIT", axis(), "FUNDING", axis(), "FX", axis()),
                        "yieldDecomposition", Map.of("observationZone", false)),
                "portfolio", Map.of(
                        "dataQuality", portfolioQuality,
                        "policyBreaches", List.of(),
                        "positions", objectMapper.readTree(responsePositions)),
                "events", List.of(),
                "watchlist", List.of()));
    }

    private static Map<String, Object> axis() {
        return Map.of("status", "UNKNOWN", "signals", List.of(), "unknownFields", List.of());
    }

    private static MonitoringEvaluationContract.PositionInput position(String symbol, String weight) {
        return new MonitoringEvaluationContract.PositionInput(symbol, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.TEN, new BigDecimal(weight), null, null, null, null, null, null,
                Map.of(), List.of());
    }

    private String currentState(String subject) {
        return jdbc.queryForObject("""
                SELECT state FROM monitoring_current_states
                 WHERE user_id = ? AND scope = 'EVENT' AND subject_key = ?
                """, String.class, USER_ID, subject);
    }
}
