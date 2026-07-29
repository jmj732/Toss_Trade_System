package com.jmj.trade.prediction;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "prediction.evaluation.enabled=true",
                "prediction.evaluation.interval=PT24H",
                "prediction.evaluation.initial-delay=PT24H",
                "prediction.evaluation.lock-ttl=PT10M"
        })
@Import(AnalysisPredictionIntegrationTest.PredictionBrokerConfiguration.class)
class AnalysisPredictionIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestBrokerAdapter broker;

    @Autowired
    private AnalysisPredictionService predictions;

    @Autowired
    private PredictionEvaluationScheduler scheduler;

    @Autowired
    private PredictionEvaluationLease evaluationLease;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.execute("TRUNCATE prediction_evaluation_leases, analysis_prediction_outcomes, analysis_predictions, "
                + "broker_connections, users CASCADE");
        broker.reset();
    }

    @Test
    void capturesBaselinePriceFromLiveQuoteAtCreation() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));

        mockMvc.perform(post("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest("AAPL", "USD", "UP", "v1", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.baselinePrice").value(100))
                .andExpect(jsonPath("$.predictedDirection").value("UP"));

        assertCount("analysis_predictions", 1);
    }

    @Test
    void rejectsQuoteCurrencyMismatch() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));

        mockMvc.perform(post("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest("AAPL", "KRW", "UP", "v1", "1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_PREDICTION_QUOTE_CURRENCY_MISMATCH"));
    }

    @Test
    void doesNotEvaluateAHorizonBeforeItsWallClockTimeHasPassed() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        broker.reset();
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("120"));

        predictions.evaluateDue(Instant.now());
        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions[0].outcomes").isEmpty());

        assertCount("analysis_prediction_outcomes", 0);
        org.assertj.core.api.Assertions.assertThat(broker.quoteCallCount()).isZero();
    }

    @Test
    void getDoesNotCallBrokerOrWriteOutcomes() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        backdatePrediction("AAPL", Instant.now().minusSeconds(2 * 86400));
        broker.reset();
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("110"));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions[0].outcomes").isEmpty());

        assertCount("analysis_prediction_outcomes", 0);
        org.assertj.core.api.Assertions.assertThat(broker.quoteCallCount()).isZero();
    }

    @Test
    void outcomeGradeIsPermanentEvenIfPriceMovesAgainOnALaterTick() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        backdatePrediction("AAPL", Instant.now().minusSeconds(2 * 86400));
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("110"));

        predictions.evaluateDue(Instant.now());
        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions[0].outcomes.D1.price").value(110));

        broker.setPrice("AAPL", Currency.USD, new BigDecimal("50"));
        predictions.evaluateDue(Instant.now());
        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions[0].outcomes.D1.price").value(110));

        assertCount("analysis_prediction_outcomes", 1);
    }

    @Test
    void gradesOnlyTheEarliestDueHorizonPerPredictionPerTick() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        // D1, D5 and D20 are all overdue at once, as if the connection went unread for a while.
        backdatePrediction("AAPL", Instant.now().minusSeconds(25 * 86400));
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("110"));

        predictions.evaluateDue(Instant.now());
        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions[0].outcomes.D1.price").value(110))
                .andExpect(jsonPath("$.predictions[0].outcomes.D5").doesNotExist())
                .andExpect(jsonPath("$.predictions[0].outcomes.D20").doesNotExist());
        assertCount("analysis_prediction_outcomes", 1);

        broker.setPrice("AAPL", Currency.USD, new BigDecimal("120"));
        predictions.evaluateDue(Instant.now());
        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions[0].outcomes.D1.price").value(110))
                .andExpect(jsonPath("$.predictions[0].outcomes.D5.price").value(120))
                .andExpect(jsonPath("$.predictions[0].outcomes.D20").doesNotExist());
        assertCount("analysis_prediction_outcomes", 2);
    }

    @Test
    void gradesOnlyDuePredictionsInTargetDueAtOrderWithinTheTickLimit() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("LARGE_ID", Currency.USD, new BigDecimal("100"));
        var largeId = createPrediction(connectionId, "LARGE_ID", "USD", "UP", "v1", "1");
        broker.setPrice("SMALL_ID", Currency.USD, new BigDecimal("100"));
        var smallId = createPrediction(connectionId, "SMALL_ID", "USD", "UP", "v1", "1");
        var duePredictedAt = T0.minus(Duration.ofDays(2));
        jdbc.update("UPDATE analysis_predictions SET id = ?, predicted_at = ? WHERE id = ?",
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), offset(duePredictedAt), largeId);
        jdbc.update("UPDATE analysis_predictions SET id = ?, predicted_at = ? WHERE id = ?",
                UUID.fromString("00000000-0000-0000-0000-000000000001"), offset(duePredictedAt), smallId);
        broker.setPrice("NOTDUE", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "NOTDUE", "USD", "UP", "v1", "1");
        backdatePrediction("NOTDUE", T0);
        broker.reset();
        broker.setPrice("LARGE_ID", Currency.USD, new BigDecimal("110"), T0);
        broker.setPrice("SMALL_ID", Currency.USD, new BigDecimal("120"), T0);
        broker.setPrice("NOTDUE", Currency.USD, new BigDecimal("130"), T0);

        predictions.evaluateDue(T0, 10, 1, () -> true);

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT prediction.symbol
                  FROM analysis_prediction_outcomes outcome
                  JOIN analysis_predictions prediction ON prediction.id = outcome.prediction_id
                """, String.class)).isEqualTo("SMALL_ID");
        org.assertj.core.api.Assertions.assertThat(broker.quoteCallCount()).isEqualTo(1);
    }

    @Test
    void repeatedBatchesStillAttemptOnlyOneHorizonPerPredictionPerTick() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        backdatePrediction("AAPL", T0.minus(Duration.ofDays(25)));
        broker.setPrice("MSFT", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "MSFT", "USD", "UP", "v1", "1");
        backdatePrediction("MSFT", T0.minus(Duration.ofDays(2)));
        broker.reset();
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("110"), T0);
        broker.setPrice("MSFT", Currency.USD, new BigDecimal("110"), T0);

        predictions.evaluateDue(T0, 1, 10, () -> true);

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList("""
                SELECT prediction.symbol, outcome.horizon
                  FROM analysis_prediction_outcomes outcome
                  JOIN analysis_predictions prediction ON prediction.id = outcome.prediction_id
                 ORDER BY prediction.symbol
                """)).containsExactly(
                java.util.Map.of("symbol", "AAPL", "horizon", "D1"),
                java.util.Map.of("symbol", "MSFT", "horizon", "D1"));
    }

    @Test
    void stopsBeforeTheNextBatchWhenLeaseContinuationFails() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        backdatePrediction("AAPL", T0.minus(Duration.ofDays(2)));
        broker.setPrice("MSFT", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "MSFT", "USD", "UP", "v1", "1");
        backdatePrediction("MSFT", T0.minus(Duration.ofDays(2)));
        broker.reset();
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("110"), T0);
        broker.setPrice("MSFT", Currency.USD, new BigDecimal("110"), T0);
        var batches = new AtomicInteger();

        predictions.evaluateDue(T0, 1, 10, () -> batches.incrementAndGet() == 1);

        assertCount("analysis_prediction_outcomes", 1);
        org.assertj.core.api.Assertions.assertThat(broker.quoteCallCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(batches).hasValue(2);
    }

    @Test
    void quoteFailureLeavesThatHorizonPendingWithoutBlockingOtherPredictions() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        backdatePrediction("AAPL", Instant.now().minusSeconds(2 * 86400));
        broker.setPrice("MSFT", Currency.USD, new BigDecimal("200"));
        createPrediction(connectionId, "MSFT", "USD", "UP", "v1", "1");
        backdatePrediction("MSFT", Instant.now().minusSeconds(2 * 86400));
        broker.failFor("AAPL");

        predictions.evaluateDue(Instant.now());
        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions.length()").value(2));

        assertCount("analysis_prediction_outcomes", 1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM analysis_prediction_outcomes outcome
                  JOIN analysis_predictions prediction ON prediction.id = outcome.prediction_id
                 WHERE prediction.symbol = 'AAPL'
                """, Long.class)).isZero();
    }

    @Test
    void detailedTickResultSeparatesSucceededAndQuoteFailedAttempts() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        backdatePrediction("AAPL", T0.minus(Duration.ofDays(2)));
        broker.setPrice("MSFT", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "MSFT", "USD", "UP", "v1", "1");
        backdatePrediction("MSFT", T0.minus(Duration.ofDays(2)));
        broker.reset();
        broker.setPrice("MSFT", Currency.USD, new BigDecimal("110"), T0);
        broker.failFor("AAPL");

        var result = predictions.evaluateDueWithResult(T0, 10, 10, () -> true);

        org.assertj.core.api.Assertions.assertThat(result.attempted()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(result.succeeded()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.quoteFailed()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.countLimitReached()).isFalse();
        assertCount("analysis_prediction_outcomes", 1);
    }

    @Test
    void memoizesQuotesPerConnectionAndSymbolWithinOneTick() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var otherConnectionId = insertConnection(OTHER_USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        createPrediction(connectionId, "AAPL", "USD", "DOWN", "v2", "1");
        createPrediction(OTHER_USER_ID, otherConnectionId, "AAPL", "USD", "UP", "v3", "1");
        jdbc.update("UPDATE analysis_predictions SET predicted_at = ? WHERE symbol = 'AAPL'",
                offset(Instant.now().minusSeconds(2 * 86400)));
        broker.reset();
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("110"));

        predictions.evaluateDue(Instant.now());
        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions.length()").value(2));

        assertCount("analysis_prediction_outcomes", 3);
        org.assertj.core.api.Assertions.assertThat(broker.quoteCallCount()).isEqualTo(2);
    }

    @Test
    void aggregatesHitRateDirectionalReturnAndMaxAdverseExcursionByModelAndContractVersion() throws Exception {
        var connectionId = insertConnection(USER_ID);

        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        backdatePrediction("AAPL", Instant.now().minusSeconds(2 * 86400));
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("90"));

        predictions.evaluateDue(Instant.now());
        var results = mockMvc.perform(
                        get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                                .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byVersion[0].modelVersion").value("v1"))
                .andExpect(jsonPath("$.byVersion[0].contractVersion").value("1"))
                .andExpect(jsonPath("$.byVersion[0].horizon").value("D1"))
                .andExpect(jsonPath("$.byVersion[0].sampleCount").value(1))
                .andExpect(jsonPath("$.byVersion[0].hitRate").value(0))
                .andExpect(jsonPath("$.byVersion[0].avgDirectionalReturn").value(-0.1))
                .andExpect(jsonPath("$.byVersion[0].avgMaxAdverseExcursion").value(0.1));

        results.andReturn();
    }

    @Test
    void persistsTargetDueQuoteObservationTimeAndLag() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var predictedAt = Instant.parse("2026-01-01T00:00:00Z");
        var observationTime = Instant.parse("2026-01-03T00:00:00Z");
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        backdatePrediction("AAPL", predictedAt);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("110"), observationTime);

        predictions.evaluateDue(Instant.parse("2026-01-04T00:00:00Z"));

        var outcome = predictions.read(
                        USER_ID, connectionId, null, null, null, null,
                        Instant.parse("2026-01-04T00:00:00Z"))
                .predictions().getFirst().outcomes().get(Horizon.D1);
        org.assertj.core.api.Assertions.assertThat(outcome.targetDueAt())
                .isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
        org.assertj.core.api.Assertions.assertThat(outcome.observationTime()).isEqualTo(observationTime);
        org.assertj.core.api.Assertions.assertThat(outcome.lag()).isEqualTo(Duration.ofDays(1));
        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions[0].outcomes.D1.targetDueAt")
                        .value("2026-01-02T00:00:00Z"))
                .andExpect(jsonPath("$.predictions[0].outcomes.D1.observationTime")
                        .value("2026-01-03T00:00:00Z"))
                .andExpect(jsonPath("$.predictions[0].outcomes.D1.lag").value("PT24H"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForMap("""
                SELECT target_due_at, observation_time, lag_ms
                  FROM analysis_prediction_outcomes
                """)).containsEntry("lag_ms", 86_400_000L);
    }

    @Test
    void leaseHeldByAnotherOwnerExcludesTheTick() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        backdatePrediction("AAPL", Instant.now().minusSeconds(2 * 86400));
        broker.reset();
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("110"));
        jdbc.update("""
                INSERT INTO prediction_evaluation_leases (name, owner, acquired_at, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                """, PredictionEvaluationLease.NAME, UUID.randomUUID());

        scheduler.evaluate();

        assertCount("analysis_prediction_outcomes", 0);
        assertCount("prediction_evaluation_leases", 1);
        org.assertj.core.api.Assertions.assertThat(broker.quoteCallCount()).isZero();
    }

    @Test
    void renewsOnlyTheCurrentUnexpiredLeaseOwner() {
        var owner = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThat(evaluationLease.acquire(owner)).isTrue();
        jdbc.update("""
                UPDATE prediction_evaluation_leases
                   SET expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute'
                 WHERE name = ?
                """, PredictionEvaluationLease.NAME);
        var before = jdbc.queryForObject("""
                SELECT expires_at
                  FROM prediction_evaluation_leases
                 WHERE name = ?
                """, OffsetDateTime.class, PredictionEvaluationLease.NAME);

        org.assertj.core.api.Assertions.assertThat(evaluationLease.renew(owner)).isTrue();
        var after = jdbc.queryForObject("""
                SELECT expires_at
                  FROM prediction_evaluation_leases
                 WHERE name = ?
                """, OffsetDateTime.class, PredictionEvaluationLease.NAME);
        org.assertj.core.api.Assertions.assertThat(after).isAfter(before);
        org.assertj.core.api.Assertions.assertThat(evaluationLease.renew(UUID.randomUUID())).isFalse();

        jdbc.update("""
                UPDATE prediction_evaluation_leases
                   SET acquired_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                       expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                 WHERE name = ?
                """, PredictionEvaluationLease.NAME);
        org.assertj.core.api.Assertions.assertThat(evaluationLease.renew(owner)).isFalse();
    }

    @Test
    void predictionDueLookupIndexExists() {
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND tablename = 'analysis_predictions'
                   AND indexname = 'ix_analysis_predictions_due'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void metricsCountOnlyTheEarliestDueUngradedHorizon() throws Exception {
        var now = Instant.now();
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        var d1 = createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        setPredictedAt(d1, now.minus(Duration.ofDays(3)));

        broker.setPrice("MSFT", Currency.USD, new BigDecimal("100"));
        var d5 = createPrediction(connectionId, "MSFT", "USD", "UP", "v1", "1");
        var d5PredictedAt = now.minus(Duration.ofDays(10));
        setPredictedAt(d5, d5PredictedAt);
        insertOutcome(d5, Horizon.D1, d5PredictedAt.plus(Duration.ofDays(1)));

        broker.setPrice("GOOG", Currency.USD, new BigDecimal("100"));
        var d20 = createPrediction(connectionId, "GOOG", "USD", "UP", "v1", "1");
        var d20PredictedAt = now.minus(Duration.ofDays(30));
        setPredictedAt(d20, d20PredictedAt);
        insertOutcome(d20, Horizon.D1, d20PredictedAt.plus(Duration.ofDays(1)));
        insertOutcome(d20, Horizon.D5, d20PredictedAt.plus(Duration.ofDays(5)));

        broker.setPrice("NVDA", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "NVDA", "USD", "UP", "v1", "1");

        var registry = new SimpleMeterRegistry();
        new PredictionEvaluationMetrics(
                jdbc, registry, Duration.ofMinutes(1), Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThat(registry
                .get("trade.prediction.evaluation.backlog").gauge().value()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(registry
                        .get("trade.prediction.evaluation.max.lag.ms").gauge().value())
                .isBetween(
                        (double) Duration.ofDays(9).plus(Duration.ofHours(23)).toMillis(),
                        (double) Duration.ofDays(10).plus(Duration.ofHours(1)).toMillis());
    }

    @Test
    void duplicateOutcomeIsRejectedAndExistingOutcomeRemainsAppendOnly() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        var predictionId = createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        backdatePrediction("AAPL", Instant.now().minusSeconds(2 * 86400));
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("110"));
        predictions.evaluateDue(Instant.now());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO analysis_prediction_outcomes (
                    id, prediction_id, horizon, price, actual_return, direction_correct,
                    target_due_at, observation_time, lag_ms
                )
                SELECT ?, prediction_id, horizon, price, actual_return, direction_correct,
                       target_due_at, observation_time, lag_ms
                  FROM analysis_prediction_outcomes
                 WHERE prediction_id = ?
                   AND horizon = 'D1'
                """, UUID.randomUUID(), predictionId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "UPDATE analysis_prediction_outcomes SET price = 120 WHERE prediction_id = ?",
                        predictionId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM analysis_prediction_outcomes WHERE prediction_id = ?",
                        predictionId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertCount("analysis_prediction_outcomes", 1);
    }

    @Test
    void filtersPredictionsByModelAndContractVersion() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");
        broker.setPrice("MSFT", Currency.USD, new BigDecimal("200"));
        createPrediction(connectionId, "MSFT", "USD", "DOWN", "v2", "1");

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("modelVersion", "v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions.length()").value(1))
                .andExpect(jsonPath("$.predictions[0].modelVersion").value("v1"));
    }

    @Test
    void hidesAnotherUsersConnectionPredictions() throws Exception {
        var connectionId = insertConnection(USER_ID);
        broker.setPrice("AAPL", Currency.USD, new BigDecimal("100"));
        createPrediction(connectionId, "AAPL", "USD", "UP", "v1", "1");

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(OTHER_USER_ID.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));
    }

    @Test
    void rejectsBlankInput() throws Exception {
        var connectionId = insertConnection(USER_ID);

        mockMvc.perform(post("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest("", "USD", "UP", "v1", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANALYSIS_PREDICTION_INPUT_INVALID"));
    }

    private UUID createPrediction(
            UUID connectionId, String symbol, String currency, String direction,
            String modelVersion, String contractVersion
    ) throws Exception {
        return createPrediction(
                USER_ID, connectionId, symbol, currency, direction, modelVersion, contractVersion);
    }

    private UUID createPrediction(
            UUID userId,
            UUID connectionId,
            String symbol,
            String currency,
            String direction,
            String modelVersion,
            String contractVersion
    ) throws Exception {
        var response = mockMvc.perform(
                        post("/api/v1/broker-connections/{connectionId}/analysis-predictions", connectionId)
                                .with(user(userId.toString()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createRequest(symbol, currency, direction, modelVersion, contractVersion)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var id = response.substring(response.indexOf("\"id\":\"") + 6);
        return UUID.fromString(id.substring(0, id.indexOf("\"")));
    }

    private void backdatePrediction(String symbol, Instant predictedAt) {
        jdbc.update("UPDATE analysis_predictions SET predicted_at = ? WHERE symbol = ?",
                offset(predictedAt), symbol);
    }

    private void setPredictedAt(UUID predictionId, Instant predictedAt) {
        jdbc.update("UPDATE analysis_predictions SET predicted_at = ? WHERE id = ?",
                offset(predictedAt), predictionId);
    }

    private void insertOutcome(UUID predictionId, Horizon horizon, Instant dueAt) {
        jdbc.update("""
                INSERT INTO analysis_prediction_outcomes (
                    id, prediction_id, horizon, price, actual_return, direction_correct,
                    target_due_at, observation_time, lag_ms
                ) VALUES (?, ?, ?, 100, 0, false, ?, ?, 0)
                """, UUID.randomUUID(), predictionId, horizon.name(), offset(dueAt), offset(dueAt));
    }

    private String createRequest(
            String symbol, String currency, String direction, String modelVersion, String contractVersion
    ) {
        return """
                {
                  "symbol":"%s",
                  "currency":"%s",
                  "predictedDirection":"%s",
                  "modelVersion":"%s",
                  "contractVersion":"%s"
                }
                """.formatted(symbol, currency, direction, modelVersion, contractVersion);
    }

    private UUID insertConnection(UUID userId) {
        var connectionId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], offset(T0), offset(T0));
        return connectionId;
    }

    private void assertCount(String table, int expected) {
        var actual = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo((long) expected);
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @TestConfiguration
    static class PredictionBrokerConfiguration {

        @Bean
        TestBrokerAdapter brokerAdapter() {
            return new TestBrokerAdapter();
        }
    }

    static final class TestBrokerAdapter implements BrokerAdapter {
        private final ConcurrentHashMap<String, BigDecimal> prices = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Currency> currencies = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Instant> observationTimes = new ConcurrentHashMap<>();
        private final java.util.Set<String> failingSymbols = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.concurrent.atomic.AtomicInteger quoteCalls =
                new java.util.concurrent.atomic.AtomicInteger();

        void setPrice(String symbol, Currency currency, BigDecimal price) {
            setPrice(symbol, currency, price, Instant.now());
        }

        void setPrice(String symbol, Currency currency, BigDecimal price, Instant observationTime) {
            prices.put(symbol, price);
            currencies.put(symbol, currency);
            observationTimes.put(symbol, observationTime);
        }

        void failFor(String symbol) {
            failingSymbols.add(symbol);
        }

        int quoteCallCount() {
            return quoteCalls.get();
        }

        void reset() {
            prices.clear();
            currencies.clear();
            observationTimes.clear();
            failingSymbols.clear();
            quoteCalls.set(0);
        }

        @Override
        public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
            quoteCalls.incrementAndGet();
            if (failingSymbols.contains(symbol)) {
                throw new BrokerException(
                        BrokerErrorCategory.BROKER_UNAVAILABLE, 503, null, null, null, true,
                        "broker unavailable in test");
            }
            var price = prices.get(symbol);
            if (price == null) {
                throw new AssertionError("no test price set for " + symbol);
            }
            var observedAt = observationTimes.get(symbol);
            var quote = new Quote(
                    connection, symbol, currencies.get(symbol), price, null, null, observedAt, observedAt);
            return new BrokerResponse<>(quote, new BrokerCallMetadata("test-quote", observedAt, Optional.empty()));
        }

        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            throw new AssertionError("prediction tracking must not list live accounts");
        }

        @Override
        public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
            throw new AssertionError("prediction tracking must not read a live account");
        }

        @Override
        public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
            throw new AssertionError("prediction tracking must not read live positions");
        }

        @Override
        public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(
                BrokerAccountRef account, Currency currency
        ) {
            throw new AssertionError("prediction tracking must not read live buying power");
        }
    }
}
