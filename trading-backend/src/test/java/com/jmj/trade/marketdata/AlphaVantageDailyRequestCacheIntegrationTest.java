package com.jmj.trade.marketdata;

import com.jmj.trade.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlphaVantageDailyRequestCacheIntegrationTest extends PostgresIntegrationTest {

    private static final Instant TODAY = Instant.parse("2026-10-03T12:00:00Z");
    private static final UUID USER_ID = UUID.fromString("f2e41535-e860-4a18-b270-417a164325d8");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private ObjectMapper mapper;
    private Clock clock;

    @BeforeEach
    void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        mapper = new ObjectMapper();
        clock = Clock.fixed(TODAY, ZoneOffset.UTC);
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
    }

    @Test
    void persistsSnapshotAndReusesOriginalAsOfAfterHelperRestart() {
        var calls = new AtomicInteger();
        var originalAsOf = Instant.parse("2026-10-03T11:58:00Z");
        var first = helper(25).get("AVT", "EARNINGS_ESTIMATES", "secret-token", () -> {
            calls.incrementAndGet();
            return List.of(value(originalAsOf));
        });
        var second = helper(25).get("avt", "earnings_estimates", "secret-token", () -> {
            calls.incrementAndGet();
            return List.of(value(TODAY));
        });

        assertThat(calls).hasValue(1);
        assertThat(second).containsExactlyElementsOf(first);
        assertThat(second.getFirst().asOf()).isEqualTo(originalAsOf);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM alpha_vantage_daily_cache", String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "SELECT credential_fingerprint FROM alpha_vantage_daily_cache", String.class))
                .isNotEqualTo("secret-token");
    }

    @Test
    void cachedHttpFailureDoesNotRepeatTheAttemptAfterHelperRestart() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> helper(25).get("AVT", "EARNINGS_ESTIMATES", "secret-token", () -> {
            calls.incrementAndGet();
            throw new ProviderUnavailableException(StockDataProviderId.ALPHA_VANTAGE, "HTTP_429");
        }))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("HTTP_429");
        assertThatThrownBy(() -> helper(25).get("AVT", "EARNINGS_ESTIMATES", "secret-token", () -> {
            calls.incrementAndGet();
            return List.of(value(TODAY));
        }))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("HTTP_429");

        assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT state FROM alpha_vantage_daily_cache", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM alpha_vantage_daily_cache", String.class))
                .contains("failureCode", "HTTP_429")
                .doesNotContain("secret-token");
    }

    @Test
    void legacyCapturedEstimatesSeedTheDurableDailyQuota() throws Exception {
        insertLegacyCapture("OLD0", Instant.parse("2026-10-03T08:00:00Z"));
        insertLegacyCapture("OLD0", Instant.parse("2026-10-03T08:00:00Z"));
        insertLegacyCapture("OLD1", Instant.parse("2026-10-03T09:00:00Z"));
        insertLegacyCapture("REPLAY", Instant.parse("2026-10-02T23:59:00Z"));
        var cache = helper(3);
        var calls = new AtomicInteger();

        cache.get("NEW0", "EARNINGS_ESTIMATES", "secret-token", () -> {
            calls.incrementAndGet();
            return List.of(value(TODAY));
        });
        assertThatThrownBy(() -> cache.get("NEW1", "SHARES_OUTSTANDING", "secret-token", () -> {
            calls.incrementAndGet();
            return List.of(value(TODAY));
        }))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("DAILY_QUOTA_EXHAUSTED");

        assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alpha_vantage_daily_cache", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void repeatedLegacyMissingObservationsDoNotIncreaseDailyUsage() throws Exception {
        for (int index = 0; index < 24; index++) {
            insertLegacyCapture("LEGACY" + index, Instant.parse("2026-10-03T08:00:00Z"));
        }
        for (int index = 0; index < 4; index++) {
            insertLegacyMissingCapture("SYNTH", TODAY.minusSeconds(index));
        }
        var cache = helper(25);
        var calls = new AtomicInteger();

        cache.get("ALLOWED", "EARNINGS_ESTIMATES", "secret-token", () -> {
            calls.incrementAndGet();
            return List.of(value(TODAY));
        });
        assertThatThrownBy(() -> cache.get("BLOCKED", "EARNINGS_ESTIMATES", "secret-token", () -> {
            calls.incrementAndGet();
            return List.of(value(TODAY));
        }))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("DAILY_QUOTA_EXHAUSTED");

        assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alpha_vantage_daily_cache", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void twentyFiveCleanLegacyObservationsExhaustTheDailyQuota() throws Exception {
        for (int index = 0; index < 25; index++) {
            insertLegacyCapture("LEGACY" + index, Instant.parse("2026-10-03T08:00:00Z"));
        }
        var cache = helper(25);
        var calls = new AtomicInteger();

        assertThatThrownBy(() -> cache.get("BLOCKED", "EARNINGS_ESTIMATES", "secret-token", () -> {
            calls.incrementAndGet();
            return List.of(value(TODAY));
        }))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("DAILY_QUOTA_EXHAUSTED");

        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alpha_vantage_daily_cache", Integer.class))
                .isZero();
    }

    @Test
    void providerConfigurationFactoryUsesTheDurableCacheBean() {
        var providerConfiguration = new StockAnalysisProviderProperties.ProviderConfiguration(
                true, true, URI.create("https://www.alphavantage.co"), "/query", "secret-token", "", "apikey",
                Map.of("function", "EARNINGS_ESTIMATES"), Set.of(), "", Map.of(), Map.of(), Map.of(), Map.of(),
                "INSTANT", Duration.ofSeconds(1), Duration.ofSeconds(2), 3, Duration.ofMillis(10),
                5, Duration.ofSeconds(1), "", Map.of());
        var configuration = new StockAnalysisProviderConfiguration();
        var cacheBean = configuration.alphaVantageDailyRequestCache(jdbc, transactions, mapper);
        var registry = configuration.stockDataProviderRegistry(
                new StockAnalysisProviderProperties(Map.of("alpha-vantage", providerConfiguration)),
                mapper, cacheBean);

        assertThat(registry.providers()).singleElement()
                .isInstanceOf(AlphaVantageEarningsEstimatesProvider.class);
        assertThat(cacheBean.get("AVT", "EARNINGS_ESTIMATES", "secret-token", () -> List.of(value(TODAY))))
                .hasSize(1);
        assertThat(configuration.alphaVantageDailyRequestCache(jdbc, transactions, mapper)
                .get("AVT", "EARNINGS_ESTIMATES", "secret-token", List::of)).hasSize(1);
    }

    private AlphaVantageDailyRequestCache helper(int limit) {
        return new AlphaVantageDailyRequestCache(jdbc, transactions, mapper, clock, limit);
    }

    private void insertLegacyCapture(String symbol, Instant asOf) throws Exception {
        var root = mapper.createObjectNode();
        var observations = root.putArray("observations");
        observations.addObject()
                .put("provider", "ALPHA_VANTAGE")
                .put("field", "consensus.horizon")
                .put("asOf", asOf.toString())
                .put("value", "fiscal year")
                .putArray("missingData");
        insertLegacyPayload(symbol, root);
    }

    private void insertLegacyMissingCapture(String symbol, Instant asOf) throws Exception {
        var root = mapper.createObjectNode();
        var observations = root.putArray("observations");
        observations.addObject()
                .put("provider", "ALPHA_VANTAGE")
                .put("field", "consensus.epsConsensus")
                .put("asOf", asOf.toString())
                .putNull("value")
                .putArray("missingData")
                .add("PROVIDER_DAILY_QUOTA_EXHAUSTED");
        insertLegacyPayload(symbol, root);
    }

    private void insertLegacyPayload(String symbol, ObjectNode root) throws Exception {
        var capture = TODAY.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO analysis_input_snapshots (
                    id, user_id, symbol, schema_version, payload, payload_hash, collected_at, created_at
                ) VALUES (?, ?, ?, '1', CAST(? AS jsonb), ?, ?, ?)
                """, UUID.randomUUID(), USER_ID, symbol, mapper.writeValueAsString(root), "0".repeat(64),
                capture, capture);
    }

    private static ProviderValue value(Instant asOf) {
        return new ProviderValue("consensus.horizon", MAPPER.valueToTree("2027-12-31"), null,
                "fiscal year", null, asOf, List.of(), StockAnalysisInput.AsOfBasis.OBSERVED_AT);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
}
