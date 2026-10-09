package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InvestmentReviewRecordIntegrationTest extends PostgresIntegrationTest {
    private static final UUID USER = UUID.fromString("11990000-0000-7000-8000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("11990000-0000-7000-8000-0000000000a2");
    private static final UUID SESSION = UUID.fromString("11990000-0000-7000-8000-0000000000a3");
    private static final Instant AS_OF = Instant.parse("2026-10-04T07:00:00.123456789Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;
    private InvestmentReviewService reviews;

    @BeforeEach
    void setUp() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO users(id) VALUES(?)", USER);
        jdbc.update("INSERT INTO users(id) VALUES(?)", OTHER);
        reviews = new InvestmentReviewService(jdbc, mapper);
    }

    @AfterEach
    void closeTestDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void preservesRawLegacyMeaningWithoutCoercingIntoDecisionShape() {
        var legacyRow = mapper.readTree("""
                {"Decision ID":"legacy-07","As Of":"2026-10-04","Asset":"PORTFOLIO","Action":"REVIEW",
                 "Price Session":"장마감 후","Reference Price":"","Note":"rebalance check"}""");
        var saved = reviews.recordReview(USER, input("SHEET_LEGACY_IMPORT", "legacy-07", "PORTFOLIO", null,
                        "REVIEW", null, "장마감 후", legacyRow),
                InvestmentReviewService.Actor.userSession(USER, SESSION));

        assertThat(saved.source()).isEqualTo("SHEET_LEGACY_IMPORT");
        assertThat(saved.recordKey()).isEqualTo("legacy-07");
        assertThat(saved.scope()).isEqualTo("PORTFOLIO");
        assertThat(saved.asset()).isNull();
        assertThat(saved.rawAction()).isEqualTo("REVIEW");
        assertThat(saved.referencePrice()).isNull();
        assertThat(saved.rawPriceSession()).isEqualTo("장마감 후");
        assertThat(saved.priceSession()).isNull();
        assertThat(saved.asOf()).isEqualTo(Instant.parse("2026-10-04T07:00:00.123456Z"));
        assertThat(saved.rawPayload()).isEqualTo(legacyRow);
        assertThat(saved.actorType()).isEqualTo("USER_SESSION");
        assertThat(saved.actorUserId()).isEqualTo(USER);
        assertThat(saved.actorSessionId()).isEqualTo(SESSION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_decision_ledger", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_states", Integer.class)).isZero();
    }

    @Test
    void securityScopeRequiresAssetAndOnlyExactSessionsBecomeCanonical() {
        var actor = InvestmentReviewService.Actor.userSession(USER, null);
        assertInvalid(input("USER_REST", "sec-missing", "SECURITY", null, "REVIEW", null, null, null), actor);
        assertInvalid(input("USER_REST", "port-asset", "PORTFOLIO", "AAPL", "REVIEW", null, null, null), actor);

        var exact = reviews.recordReview(USER, input("USER_REST", "sec-1", "SECURITY", "aapl", "REVIEW",
                new BigDecimal("101.5"), " regular_close ", null), actor);
        assertThat(exact.asset()).isEqualTo("AAPL");
        assertThat(exact.rawPriceSession()).isEqualTo(" regular_close ");
        assertThat(exact.priceSession()).isEqualTo("REGULAR_CLOSE");
        assertThat(exact.referencePrice()).isEqualByComparingTo("101.5");

        var controlChars = reviews.recordReview(USER, input("USER_REST", "sec-3", "SECURITY", "AAPL", "REVIEW",
                null, "\tregular_close\n", null), actor);
        assertThat(controlChars.rawPriceSession()).isEqualTo("\tregular_close\n");
        assertThat(controlChars.priceSession()).isEqualTo("REGULAR_CLOSE");

        var alias = reviews.recordReview(USER, input("USER_REST", "sec-2", "SECURITY", "AAPL", "REVIEW",
                null, "CLOSE", null), actor);
        assertThat(alias.rawPriceSession()).isEqualTo("CLOSE");
        assertThat(alias.priceSession()).isNull();
        // Server-derived raw payload keeps the received fields when no original row is supplied.
        assertThat(alias.rawPayload().path("rawPriceSession").asText()).isEqualTo("CLOSE");
        assertThat(alias.rawPayload().has("rawPayload")).isFalse();
    }

    @Test
    void idempotentReplayReturnsStoredRowAndDifferentBodyConflicts() {
        var actor = InvestmentReviewService.Actor.userSession(USER, SESSION);
        var first = reviews.recordReview(USER, input("USER_REST", "not-a-uuid:key/1", "SECURITY", "AVT",
                "REVIEW", null, "odd-session", null), actor);
        var replay = reviews.recordReview(USER, input("USER_REST", "not-a-uuid:key/1", "SECURITY", "AVT",
                "REVIEW", null, "odd-session", null), InvestmentReviewService.Actor.userSession(USER, null));
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_review_records", Integer.class)).isEqualTo(1);

        // The verbatim raw payload is part of the stored body: a differently spelled resend is not silently merged.
        assertThatThrownBy(() -> reviews.recordReview(USER, input("USER_REST", "not-a-uuid:key/1", "SECURITY",
                "avt", "REVIEW", null, "odd-session", null), actor))
                .isInstanceOf(InvestmentException.class)
                .extracting(exception -> ((InvestmentException) exception).code())
                .isEqualTo(InvestmentException.Code.CONFLICT);

        assertThatThrownBy(() -> reviews.recordReview(USER, input("USER_REST", "not-a-uuid:key/1", "SECURITY",
                "AVT", "HOLD", null, "odd-session", null), actor))
                .isInstanceOf(InvestmentException.class)
                .extracting(exception -> ((InvestmentException) exception).code())
                .isEqualTo(InvestmentException.Code.CONFLICT);

        // Same key under another source or another owner is a different record.
        var legacy = reviews.recordReview(USER, input("SHEET_LEGACY_IMPORT", "not-a-uuid:key/1", "SECURITY",
                "AVT", "REVIEW", null, null, mapper.readTree("{\"Action\":\"REVIEW\"}")), actor);
        var other = reviews.recordReview(OTHER, input("USER_REST", "not-a-uuid:key/1", "SECURITY", "AVT",
                "HOLD", null, null, null), InvestmentReviewService.Actor.userSession(OTHER, null));
        assertThat(legacy.id()).isNotEqualTo(first.id());
        assertThat(other.id()).isNotEqualTo(first.id());
    }

    @Test
    void rejectsFutureAsOfMissingLegacyRowAndMismatchedSourceActor() {
        var user = InvestmentReviewService.Actor.userSession(USER, null);
        var future = new InvestmentReviewService.ReviewInput("USER_REST", "future", "PORTFOLIO", null,
                Instant.now().plus(Duration.ofHours(1)), "REVIEW", null, null, null, null, null, null, null);
        assertInvalid(future, user);
        assertInvalid(input("SHEET_LEGACY_IMPORT", "no-raw", "PORTFOLIO", null, "REVIEW", null, null, null), user);
        assertInvalid(input("CONNECTOR_MCP", "rest-as-mcp", "PORTFOLIO", null, "REVIEW", null, null, null), user);
        assertInvalid(input("USER_REST", "mcp-as-rest", "PORTFOLIO", null, "REVIEW", null, null, null),
                InvestmentReviewService.Actor.connector(USER));
        assertInvalid(input("USER_REST", "zero-price", "SECURITY", "AVT", "REVIEW", BigDecimal.ZERO, null, null), user);
        assertInvalid(input("USER_REST", " ", "PORTFOLIO", null, "REVIEW", null, null, null), user);
        assertInvalid(input("USER_REST", "x".repeat(129), "PORTFOLIO", null, "REVIEW", null, null, null), user);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_review_records", Integer.class)).isZero();
    }

    @Test
    void listsOwnerScopedNewestFirstAndExposesReviewLogInContext() {
        var actor = InvestmentReviewService.Actor.userSession(USER, null);
        reviews.recordReview(USER, new InvestmentReviewService.ReviewInput("USER_REST", "older", "PORTFOLIO", null,
                Instant.parse("2026-10-01T00:00:00Z"), "REVIEW", null, null, null, null, null, null, null), actor);
        reviews.recordReview(USER, input("USER_REST", "newer", "PORTFOLIO", null, "REVIEW", null, null, null), actor);
        reviews.recordReview(OTHER, input("USER_REST", "foreign", "PORTFOLIO", null, "REVIEW", null, null, null),
                InvestmentReviewService.Actor.userSession(OTHER, null));

        assertThat(reviews.reviews(USER, 200)).extracting(InvestmentReviewService.ReviewView::recordKey)
                .containsExactly("newer", "older");
        assertThat(reviews.reviews(USER, 1)).extracting(InvestmentReviewService.ReviewView::recordKey)
                .containsExactly("newer");
        assertThatThrownBy(() -> reviews.reviews(USER, 0)).isInstanceOf(InvestmentException.class);
        assertThatThrownBy(() -> reviews.reviews(USER, 201)).isInstanceOf(InvestmentException.class);

        var riskPolicies = mock(RiskPolicyService.class);
        org.mockito.Mockito.when(riskPolicies.current(USER)).thenReturn(new RiskPolicyService.RiskPolicySnapshot(
                1, new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("100"), BigDecimal.ONE,
                null, true));
        var context = new InvestmentContextService(jdbc, mapper, new DataSourceTransactionManager(dataSource),
                new StockDataProviderRegistry(List.of()), null, mock(PortfolioReadService.class),
                mock(MonitoringWatchlistService.class), riskPolicies,
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(210), Duration.ofDays(10));
        context.setInvestmentReviewService(reviews);
        var view = context.context(USER);
        assertThat(view.reviewLog()).extracting(InvestmentReviewService.ReviewView::recordKey)
                .containsExactly("newer", "older");
        assertThat(view.decisionLedger()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_decision_ledger", Integer.class)).isZero();
    }

    @Test
    void reviewRecordsAreAppendOnly() {
        reviews.recordReview(USER, input("USER_REST", "immutable", "PORTFOLIO", null, "REVIEW", null, null, null),
                InvestmentReviewService.Actor.userSession(USER, null));
        assertThatThrownBy(() -> jdbc.update("UPDATE investment_review_records SET outcome = 'edited'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM investment_review_records"))
                .hasMessageContaining("append-only");
    }

    @Test
    void restRecordsVerbatimBodyRejectsConnectorSourceAndReportsReviewConflict() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new InvestmentReviewController(reviews, mapper)).build();
        var principal = new org.springframework.security.authentication.TestingAuthenticationToken(USER.toString(), null);
        var body = """
                {"recordKey":"rest-1","scope":"SECURITY","asset":"avt","asOf":"2026-10-04T07:00:00Z",
                 "rawAction":"REVIEW","rawPriceSession":"pre-market?","outcome":"no change"}""";
        var created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/investment/reviews").principal(principal).contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        var json = mapper.readTree(created);
        assertThat(json.path("source").asText()).isEqualTo("USER_REST");
        assertThat(json.path("asset").asText()).isEqualTo("AVT");
        assertThat(json.path("rawPayload")).isEqualTo(mapper.readTree(body));
        assertThat(json.path("actorType").asText()).isEqualTo("USER_SESSION");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/investment/reviews").principal(principal).contentType("application/json")
                        .content(body.replace("no change", "changed")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("INVESTMENT_REVIEW_CONFLICT"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/investment/reviews").principal(principal).contentType("application/json")
                        .content(body.replace("\"recordKey\"", "\"source\":\"CONNECTOR_MCP\",\"recordKey\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        var listed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/investment/reviews").param("limit", "200").principal(principal))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(listed).size()).isEqualTo(1);
    }

    private void assertInvalid(InvestmentReviewService.ReviewInput input, InvestmentReviewService.Actor actor) {
        assertThatThrownBy(() -> reviews.recordReview(USER, input, actor))
                .isInstanceOf(InvestmentException.class)
                .extracting(exception -> ((InvestmentException) exception).code())
                .isEqualTo(InvestmentException.Code.INVALID_INPUT);
    }

    private static InvestmentReviewService.ReviewInput input(String source, String key, String scope, String asset,
                                                             String rawAction, BigDecimal price, String rawSession,
                                                             tools.jackson.databind.JsonNode rawPayload) {
        return new InvestmentReviewService.ReviewInput(source, key, scope, asset, AS_OF, rawAction,
                "kept position under review", null, "next earnings", price, rawSession, null, rawPayload);
    }
}
