package com.jmj.trade.investment.tactical;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.investment.InvestmentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TacticalOverlayServiceIntegrationTest extends PostgresIntegrationTest {

    private static final List<String> SYMBOLS = List.of("AVT", "CSTM", "GOOGL", "LUNR", "RDW", "VST");
    private static final List<String> ALL_TICKERS = List.of("AVT", "CSTM", "GOOGL", "LUNR", "RDW", "VST", "SPY");
    private static final Instant CLOCK_START = Instant.parse("2026-10-05T20:00:00Z");
    private static final LocalDate FIRST_DATE = LocalDate.parse("2026-06-01");

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;
    private ObjectMapper mapper;
    private TacticalOverlayService service;
    private List<LocalDate> dates;

    @BeforeEach
    void migrateAndSeedUsers() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper();
        jdbc.update("INSERT INTO users (id) VALUES (?), (?)", userId, otherUserId);
        dates = tradingDates(FIRST_DATE, 70);
        var clock = new AdvancingClock(CLOCK_START);
        var properties = TacticalOverlayProperties.defaults();
        service = new TacticalOverlayService(jdbc, mapper, new TacticalOverlayCalculator(properties),
                properties, String.join(",", SYMBOLS), clock);
    }

    @AfterEach
    void closeTestPool() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void refreshPersistsTrackedHistoryAndContextReadsAreReadOnlyAndSourceBacked() throws Exception {
        var refsByTicker = recordTrackedHistory();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_tactical_overlay_bar_snapshots", Integer.class))
                .isEqualTo(ALL_TICKERS.size() * dates.size());

        service.refresh(userId);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type = 'SECURITY' AND overlay_version = 'TACTICAL_V1'
                """, Integer.class, userId)).isEqualTo(SYMBOLS.size());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type = 'MARKET' AND entity_key = 'TRACKED_SECURITIES'
                """, Integer.class, userId)).isEqualTo(1);

        var expectedAsOf = sourceAsOf(dates.getLast());
        var databaseSourceAsOf = jdbc.queryForObject("""
                SELECT source_as_of FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type = 'MARKET' AND entity_key = 'TRACKED_SECURITIES'
                 ORDER BY calculated_at DESC, id DESC LIMIT 1
                """, OffsetDateTime.class, userId).toInstant();
        assertThat(databaseSourceAsOf).isEqualTo(expectedAsOf);
        assertThat(databaseSourceAsOf).isNotEqualTo(dates.getLast().atStartOfDay(ZoneOffset.UTC).toInstant());

        var beforeReads = snapshotCount(userId);
        var context = service.context(userId, SYMBOLS);
        var repeated = service.context(userId, SYMBOLS);
        assertThat(snapshotCount(userId)).isEqualTo(beforeReads);
        assertThat(context.portfolio().asOf()).isEqualTo(dates.getLast());
        assertThat(context.portfolio().sourceAsOf()).isEqualTo(expectedAsOf);
        assertThat(context.portfolio().status()).isEqualTo("OK");
        assertThat(context.securities()).containsKeys(SYMBOLS.toArray(String[]::new));
        assertThat(context.securities().get("AVT").status()).isEqualTo("OK");
        assertThat(context.securities().get("AVT").sourceAsOf()).isEqualTo(expectedAsOf);
        assertThat(context.securities().get("AVT").indicators().path(0).path("date").asText())
                .isEqualTo(dates.getFirst().toString());
        assertThat(context.securities().get("AVT").indicators().path(69).path("date").asText())
                .isEqualTo(dates.getLast().toString());
        var securityPayload = jdbc.queryForObject("""
                SELECT payload::text FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type = 'SECURITY' AND entity_key = 'AVT'
                 ORDER BY calculated_at DESC, id DESC LIMIT 1
                """, String.class, userId);
        assertThat(mapper.readTree(securityPayload).path("priceAdjustmentStatus").asText())
                .isEqualTo("UNADJUSTED");
        assertThat(repeated.portfolio()).isEqualTo(context.portfolio());

        var market = context.portfolio().market();
        assertThat(market.path("scope").asText()).isEqualTo("MARKET");
        assertThat(market.path("entityKey").asText()).isEqualTo("TRACKED_SECURITIES");
        assertThat(market.path("asOf").asText()).isEqualTo(dates.getLast().toString());
        assertThat(market.path("baseDate").isTextual()).isTrue();
        assertThat(market.path("metrics").has("BreakoutSuccessRate5D")).isTrue();
        var inputRefs = market.path("inputRefs");
        assertThat(inputRefs.isArray()).isTrue();
        var referenceIds = new ArrayList<String>();
        inputRefs.forEach(reference -> referenceIds.add(reference.asString()));
        for (var ticker : ALL_TICKERS) {
            assertThat(referenceIds).as("market aggregate source references for %s", ticker)
                    .contains(refsByTicker.get(ticker));
        }

        var invalidFutureBarCount = jdbc.queryForObject(
                "SELECT count(*) FROM investment_tactical_overlay_bar_snapshots WHERE user_id = ?",
                Integer.class, userId);
        var futureRow = List.of(barRow(dates.getLast().plusDays(1), bd("120"), CLOCK_START.plusSeconds(60)));
        service.recordTossBars(userId, "AVT", mapper.valueToTree(futureRow), CLOCK_START, null);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM investment_tactical_overlay_bar_snapshots WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(invalidFutureBarCount);

        var other = service.context(otherUserId, SYMBOLS);
        assertThat(other.portfolio().status()).isEqualTo("NOT_CONFIGURED");
        assertThat(other.securities().values()).allSatisfy(view -> assertThat(view.status()).isEqualTo("NOT_CONFIGURED"));
        assertThat(other.decisions()).isEmpty();
        assertThat(snapshotCount(otherUserId)).isZero();
        assertThat(snapshotCount(userId)).isEqualTo(beforeReads);
    }

    @Test
    void contextAddsDailyVwapAliasesToLegacyPayloadWithoutWritingSnapshots() throws Exception {
        var dailyProxy = mapper.readTree("""
                {"value":101.25,"status":"OK"}
                """);
        var legacyAvwap = mapper.readTree("""
                {"value":99.75,"status":"PARTIAL"}
                """);
        var legacyPayload = mapper.readTree("""
                {
                  "stage":"BASE",
                  "indicators":[
                    {"date":"2026-10-01","dailyRolling20Vwap":{"value":101.25,"status":"OK"}},
                    {"date":"2026-10-02","dailyRolling20Vwap":{"value":101.25,"status":"OK"},
                     "dailyVwap20Proxy":{"value":1,"status":"STALE"}}
                  ],
                  "anchoredVwaps":[{
                    "id":"legacy-anchor","date":"2026-09-01","anchorType":"MAJOR_LOW",
                    "value":{"value":99.75,"status":"PARTIAL"},
                    "anchoredVwap":{"value":1,"status":"STALE"},
                    "dailyAvwap":{"value":2,"status":"DATA_MISSING"}
                  }],
                  "events":[],"cohorts":[]
                }
                """);
        var sourceAsOf = sourceAsOf(dates.getLast());
        var storedAt = OffsetDateTime.ofInstant(CLOCK_START, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_tactical_overlay_snapshots (
                    id, user_id, snapshot_type, entity_key, ticker, as_of, source_as_of, calculated_at,
                    source, overlay_version, status, reasons, input_refs, properties_hash, payload, created_at
                ) VALUES (?, ?, 'SECURITY', 'AVT', 'AVT', ?, ?, ?, 'TOSS', 'TACTICAL_V1', 'OK',
                          '[]'::jsonb, '[]'::jsonb, ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), userId, dates.getLast(), OffsetDateTime.ofInstant(sourceAsOf, ZoneOffset.UTC),
                storedAt, "c".repeat(64), mapper.writeValueAsString(legacyPayload), storedAt);

        var beforeRead = snapshotCount(userId);
        var context = service.context(userId, SYMBOLS);
        var overlay = context.securities().get("AVT");

        assertThat(overlay.status()).isEqualTo("OK");
        assertThat(overlay.sourceAsOf()).isEqualTo(sourceAsOf);
        assertThat(overlay.indicators().path(0).path("dailyRolling20Vwap")).isEqualTo(dailyProxy);
        assertThat(overlay.indicators().path(0).path("dailyVwap20Proxy")).isEqualTo(dailyProxy);
        assertThat(overlay.indicators().path(1).path("dailyVwap20Proxy")).isEqualTo(dailyProxy);
        assertThat(overlay.anchoredVwaps().path(0).path("value")).isEqualTo(legacyAvwap);
        assertThat(overlay.anchoredVwaps().path(0).path("anchoredVwap")).isEqualTo(legacyAvwap);
        assertThat(overlay.anchoredVwaps().path(0).path("dailyAvwap")).isEqualTo(legacyAvwap);
        assertThat(overlay.getDailyAvwaps()).isEqualTo(overlay.anchoredVwaps());
        assertThat(mapper.valueToTree(overlay).path("dailyAvwaps")).isEqualTo(overlay.anchoredVwaps());
        assertThat(snapshotCount(userId)).isEqualTo(beforeRead);

        var persistedPayload = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type = 'SECURITY' AND entity_key = 'AVT'
                 ORDER BY snapshot_order DESC LIMIT 1
                """, String.class, userId));
        assertThat(persistedPayload).isEqualTo(legacyPayload);

        var unconfigured = service.context(otherUserId, SYMBOLS).securities().get("AVT");
        assertThat(unconfigured.status()).isEqualTo("NOT_CONFIGURED");
        assertThat(unconfigured.getDailyAvwaps()).isNull();
    }

    @Test
    void effectiveDatedThemeInputsAnchorsAndDecisionPerformanceRecomputeAndTombstone() throws Exception {
        var refsByTicker = recordTrackedHistory();
        insertDecision("AVT", UUID.fromString("6e252d81-816e-4fd8-bdc9-739856015103"));
        insertDecision("AVT", UUID.fromString("6e252d81-816e-4fd8-bdc9-739856015104"));
        var oldDecision = UUID.fromString("6e252d81-816e-4fd8-bdc9-739856015103");
        var newDecision = UUID.fromString("6e252d81-816e-4fd8-bdc9-739856015104");
        var sourceAsOf = CLOCK_START.minusSeconds(60);

        var beforeInputs = inputCount(userId);
        var oldThemeInput = service.putTheme(userId, new TacticalOverlayService.ThemeInput(
                "OLD_THEME", "Old theme", dates.getFirst(), "USER_INPUT", sourceAsOf));
        var oldMembershipInput = service.putThemeMapping(userId, new TacticalOverlayService.ThemeMappingInput(
                "OLD_THEME", "AVT", dates.getFirst(), "USER_INPUT", sourceAsOf));
        service.putTheme(userId, new TacticalOverlayService.ThemeInput(
                "NEW_THEME", "New theme", dates.getFirst(), "USER_INPUT", sourceAsOf));
        var newMembershipInput = service.putThemeMapping(userId, new TacticalOverlayService.ThemeMappingInput(
                "NEW_THEME", "AVT", dates.get(60), "USER_INPUT", sourceAsOf));

        var afterMapping = service.context(userId, SYMBOLS);
        var oldTheme = aggregate(afterMapping.portfolio().themes(), "OLD_THEME");
        var newTheme = aggregate(afterMapping.portfolio().themes(), "NEW_THEME");
        var benchmarkSourceAsOf = jdbc.queryForObject("""
                SELECT source_as_of FROM investment_tactical_overlay_bar_snapshots
                 WHERE user_id = ? AND ticker = 'SPY' AND bar_date = ?
                """, OffsetDateTime.class, userId, dates.getLast()).toInstant();
        var expectedThemeSourceAsOf = sourceAsOf.isAfter(benchmarkSourceAsOf)
                ? sourceAsOf : benchmarkSourceAsOf;
        assertThat(oldTheme.path("scope").asText()).isEqualTo("THEME");
        assertThat(oldTheme.path("asOf").asText()).isEqualTo(dates.getLast().toString());
        assertThat(oldTheme.path("baseDate").isNull()).isTrue();
        assertThat(oldTheme.path("reasons").isArray()).isTrue();
        assertThat(Instant.parse(oldTheme.path("sourceAsOf").asText())).isEqualTo(sourceAsOf);
        assertThat(oldTheme.path("sources").toString()).contains("TOSS", "USER_INPUT");
        assertThat(oldTheme.path("inputRefs").toString())
                .contains(oldThemeInput.toString(), oldMembershipInput.toString());
        assertThat(newTheme.path("inputRefs").toString()).contains(newMembershipInput.toString());
        assertThat(newTheme.path("inputRefs").toString()).contains(refsByTicker.get("SPY"));
        assertThat(Instant.parse(newTheme.path("sourceAsOf").asText())).isEqualTo(expectedThemeSourceAsOf);
        assertThat(oldTheme.path("metrics").has("ThemeReturn20D")).isTrue();
        assertThat(oldTheme.path("metrics").has("ThemeRS20_SPY")).isTrue();
        assertThat(oldTheme.path("metrics").has("ThemeBreadthEMA21")).isTrue();
        assertThat(oldTheme.path("metrics").has("ThemeBreadthEMA50")).isTrue();
        assertThat(oldTheme.path("metrics").has("MedianRVOL")).isTrue();
        assertThat(oldTheme.path("metrics").has("BreakoutCount")).isTrue();
        assertThat(oldTheme.path("metrics").has("BreakoutSuccessRate3D")).isTrue();
        assertThat(oldTheme.path("metrics").has("BreakoutSuccessRate5D")).isTrue();
        assertThat(oldTheme.path("metrics").has("FailedBreakoutRate")).isTrue();
        assertThat(oldTheme.path("metrics").has("MedianPostBreakoutReturn5D")).isTrue();
        assertThat(oldTheme.path("expectedMembers").asInt()).isZero();
        assertThat(newTheme.path("expectedMembers").asInt()).isEqualTo(1);
        assertThat(metricValue(oldTheme, "BreakoutCount")).isEqualByComparingTo("1");
        assertThat(metricValue(oldTheme, "BreakoutSuccessRate5D")).isEqualByComparingTo("1");
        assertThat(metricValue(newTheme, "BreakoutCount")).isEqualByComparingTo("0");
        assertThat(oldTheme.path("metrics").path("BreakoutCount").path("status").asText()).isNotBlank();

        service.putAnchor(userId, "AVT", new TacticalOverlayService.AnchorInput(
                "entry-anchor", dates.get(30), TacticalOverlayCalculator.AnchorType.POSITION_ENTRY,
                "USER_INPUT", sourceAsOf));
        var afterAnchor = service.context(userId, SYMBOLS).securities().get("AVT").anchoredVwaps();
        assertThat(findById(afterAnchor, "entry-anchor").path("anchorType").asText()).isEqualTo("POSITION_ENTRY");
        assertThat(findById(afterAnchor, "entry-anchor").path("asOf").asText()).isEqualTo(dates.getLast().toString());
        assertThat(findById(afterAnchor, "entry-anchor").path("value").path("value").decimalValue())
                .isPositive();
        assertThat(inputCount(userId)).isGreaterThan(beforeInputs);

        insertFreshTossPrice("AVT", bd("121"), CLOCK_START.minusSeconds(5));
        service.putPerformanceEntry(userId, "AVT", new TacticalOverlayService.PerformanceInput(
                "position-current", null, dates.get(45), bd("100"), bd("90"), bd("5"),
                "breakout", "NO_EFFECT", List.of(new TacticalOverlayService.ExitInput(
                dates.get(65), bd("125"), bd("5"))), "USER_INPUT", sourceAsOf));
        service.putPerformanceEntry(userId, "AVT", performanceInput("decision-old", oldDecision,
                dates.get(61), "110", "breakout", "NO_EFFECT", sourceAsOf));
        service.putPerformanceEntry(userId, "AVT", performanceInput("decision-new", newDecision,
                dates.get(62), "115", "pullback", "AVOIDED_CHASE", sourceAsOf));
        var withDecisions = service.context(userId, SYMBOLS);
        assertThat(withDecisions.decisions()).containsKeys(oldDecision, newDecision);
        assertThat(withDecisions.decisions().get(oldDecision).initialRiskPrice()).isEqualByComparingTo("110");
        assertThat(withDecisions.decisions().get(newDecision).initialRiskPrice()).isEqualByComparingTo("115");
        assertThat(withDecisions.decisions().get(oldDecision).performance()).isNotNull();
        assertThat(withDecisions.decisions().get(newDecision).performance()).isNotNull();
        var position = performanceByKey(withDecisions.securities().get("AVT").performance(), "position-current");
        var metrics = position.path("performance");
        var persistedPosition = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type = 'POSITION' AND entity_key = 'AVT:position-current'
                 ORDER BY snapshot_order DESC LIMIT 1
                """, String.class, userId));
        assertThat(persistedPosition.path("performance")).isEqualTo(metrics);
        assertThat(metrics.path("status").asText()).isEqualTo("OK");
        assertMetric(metrics, "initialR", "1", "OK");
        assertMetric(metrics, "initialRiskPerShare", "10", "OK");
        assertMetric(metrics, "initialRiskAmount", "50", "OK");
        assertMetric(metrics, "currentR", "2.1", "OK");
        assertMetric(metrics, "realizedR", "2.5", "OK");
        assertMetric(metrics, "mae", "0.1", "OK");
        assertMetric(metrics, "mfe", "2.1", "OK");
        assertThat(metrics.path("excursionWindow").asText())
                .isEqualTo("POST_ENTRY_PRE_EXIT_COMPLETED_DAILY_BARS");
        assertForward(metrics, 5, "0", dates.get(50));
        assertForward(metrics, 20, "0.2", dates.get(65));

        var snapshotsBeforeRead = snapshotCount(userId);
        service.context(userId, SYMBOLS);
        service.context(userId, SYMBOLS);
        assertThat(snapshotCount(userId)).isEqualTo(snapshotsBeforeRead);

        var inputCountBeforeInvalid = inputCount(userId);
        var snapshotCountBeforeInvalid = snapshotCount(userId);
        assertThatThrownBy(() -> service.putAnchor(userId, "AVT", new TacticalOverlayService.AnchorInput(
                "future-anchor", dates.getLast(), TacticalOverlayCalculator.AnchorType.CATALYST_DAY,
                "USER_INPUT", CLOCK_START.plusSeconds(3_600))))
                .isInstanceOfSatisfying(InvestmentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(InvestmentException.Code.INVALID_INPUT));
        assertThat(inputCount(userId)).isEqualTo(inputCountBeforeInvalid);
        assertThat(snapshotCount(userId)).isEqualTo(snapshotCountBeforeInvalid);

        service.deleteAnchor(userId, "AVT", "entry-anchor", sourceAsOf);
        assertThat(findByIdOrNull(service.context(userId, SYMBOLS).securities().get("AVT").anchoredVwaps(),
                "entry-anchor")).isNull();
        assertThat(inputCount(userId)).isEqualTo(inputCountBeforeInvalid + 1);

        service.deleteTheme(userId, "OLD_THEME", sourceAsOf);
        var afterDelete = service.context(userId, SYMBOLS);
        assertThat(findByEntityKeyOrNull(afterDelete.portfolio().themes(), "OLD_THEME")).isNull();
        assertThat(findByEntityKeyOrNull(afterDelete.portfolio().themes(), "NEW_THEME")).isNotNull();

        var inputsBeforePositionDelete = inputCount(userId);
        service.deletePerformanceEntry(userId, "AVT", "position-current", sourceAsOf);
        var afterPositionDelete = service.context(userId, SYMBOLS);
        assertThat(performanceByKeyOrNull(afterPositionDelete.securities().get("AVT").performance(),
                "position-current")).isNull();
        assertThat(inputCount(userId)).isEqualTo(inputsBeforePositionDelete + 1);
        service.context(userId, SYMBOLS);
        assertThat(performanceByKeyOrNull(service.context(userId, SYMBOLS).securities().get("AVT").performance(),
                "position-current")).isNull();
    }

    @Test
    void fixedClockInputReplacementAndTombstoneRemainDeterministic() {
        var frozenService = new TacticalOverlayService(jdbc, mapper,
                new TacticalOverlayCalculator(TacticalOverlayProperties.defaults()),
                TacticalOverlayProperties.defaults(), String.join(",", SYMBOLS),
                Clock.fixed(CLOCK_START, ZoneOffset.UTC));
        for (var ticker : List.of("AVT", "SPY")) {
            var rows = dates.stream().map(date -> barRow(date, bd("100"), sourceAsOf(date))).toList();
            frozenService.recordTossBars(userId, ticker, mapper.valueToTree(rows), CLOCK_START.minusSeconds(60), null);
        }
        var earlierSource = CLOCK_START.minusSeconds(60);
        frozenService.putAnchor(userId, "AVT", new TacticalOverlayService.AnchorInput(
                "fixed-anchor", dates.get(25), TacticalOverlayCalculator.AnchorType.MAJOR_LOW,
                "USER_INPUT", earlierSource));
        frozenService.putAnchor(userId, "AVT", new TacticalOverlayService.AnchorInput(
                "fixed-anchor", dates.get(26), TacticalOverlayCalculator.AnchorType.CATALYST_DAY,
                "USER_INPUT", earlierSource));

        var active = frozenService.context(userId, SYMBOLS).securities().get("AVT").anchoredVwaps();
        assertThat(findById(active, "fixed-anchor").path("date").asText()).isEqualTo(dates.get(26).toString());
        assertThat(findById(active, "fixed-anchor").path("anchorType").asText()).isEqualTo("CATALYST_DAY");

        frozenService.deleteAnchor(userId, "AVT", "fixed-anchor", CLOCK_START.minusSeconds(1));
        var deleted = frozenService.context(userId, SYMBOLS).securities().get("AVT").anchoredVwaps();
        assertThat(findByIdOrNull(deleted, "fixed-anchor")).isNull();
    }

    @Test
    void contradictoryTossBarsSurfaceSourceConflictInsteadOfSelectingOneValue() {
        recordTrackedHistory();
        var conflictDate = dates.get(30);
        var capturedAt = CLOCK_START.minusSeconds(10);
        var conflictingRows = List.of(
                barRow(conflictDate, bd("100"), sourceAsOf(conflictDate)),
                barRow(conflictDate, bd("105"), sourceAsOf(conflictDate)));
        service.recordTossBars(userId, "AVT", mapper.valueToTree(conflictingRows), capturedAt, null);
        service.refresh(userId);

        var view = service.context(userId, SYMBOLS).securities().get("AVT");
        assertThat(view.status()).isEqualTo("SOURCE_CONFLICT");
        assertThat(view.reason()).isEqualTo("SOURCE_CONFLICT");
    }

    @Test
    void spyConflictLeavesStockEmaUsableButMarksAlignedRelativeStrengthAndMarketBenchmark() {
        recordTrackedHistory();
        var conflictDate = dates.getLast();
        var capturedAt = CLOCK_START.minusSeconds(10);
        var conflictingRows = List.of(
                barRow(conflictDate, bd("100"), sourceAsOf(conflictDate)),
                barRow(conflictDate, bd("105"), sourceAsOf(conflictDate)));
        service.recordBenchmarkBars(userId, mapper.valueToTree(conflictingRows), capturedAt);
        service.refresh(userId);

        var context = service.context(userId, SYMBOLS);
        var indicators = context.securities().get("AVT").indicators();
        var latest = indicators.path(indicators.size() - 1);
        assertThat(latest.path("ema21").path("status").asText()).isEqualTo("OK");
        assertThat(latest.path("relativeStrength20").path("status").asText()).isEqualTo("SOURCE_CONFLICT");
        assertThat(context.portfolio().market().path("benchmarkStatus").asText()).isEqualTo("SOURCE_CONFLICT");
    }

    private Map<String, String> recordTrackedHistory() {
        var sourceAsOf = sourceAsOf(dates.getLast());
        var refs = new LinkedHashMap<String, String>();
        for (var ticker : ALL_TICKERS) {
            var rows = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < dates.size(); index++) {
                var close = "AVT".equals(ticker) && index >= 55 ? bd("120") : bd("100");
                rows.add(barRow(dates.get(index), close, sourceAsOf(dates.get(index))));
            }
            service.recordTossBars(userId, ticker, mapper.valueToTree(rows), CLOCK_START.minusSeconds(120), null);
            refs.put(ticker, jdbc.queryForObject("""
                    SELECT id::text FROM investment_tactical_overlay_bar_snapshots
                     WHERE user_id = ? AND ticker = ? AND bar_date = ?
                    """, String.class, userId, ticker, dates.getLast()));
        }
        return refs;
    }

    private Map<String, Object> barRow(LocalDate date, BigDecimal close, Instant sourceAsOf) {
        return Map.of("date", date.toString(), "timestamp", sourceAsOf.toString(), "session", "REGULAR_CLOSE",
                "currency", "USD", "open", close, "high", close.add(BigDecimal.ONE),
                "low", close.subtract(BigDecimal.ONE), "close", close, "volume", bd("1000"));
    }

    private void insertDecision(String ticker, UUID decisionId) {
        jdbc.update("""
                INSERT INTO investment_decision_ledger (
                    decision_id, user_id, as_of, asset, action, risk_policy_check, created_at
                ) VALUES (?, ?, ?, ?, 'HOLD', '{}'::jsonb, ?)
                """, decisionId, userId, OffsetDateTime.ofInstant(CLOCK_START, ZoneOffset.UTC), ticker,
                OffsetDateTime.ofInstant(CLOCK_START, ZoneOffset.UTC));
    }

    private void insertFreshTossPrice(String ticker, BigDecimal price, Instant asOf) {
        var inputId = UUID.randomUUID();
        var capturedAt = OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO analysis_input_snapshots (
                    id, user_id, symbol, schema_version, payload, payload_hash, collected_at, created_at
                ) VALUES (?, ?, ?, '1', '{}'::jsonb, ?, ?, ?)
                """, inputId, userId, ticker, "b".repeat(64), capturedAt, capturedAt);
        jdbc.update("""
                INSERT INTO investment_price_snapshots (
                    id, user_id, input_snapshot_id, ticker, as_of, session, latest_price,
                    latest_price_as_of, source, observed_at
                ) VALUES (?, ?, ?, ?, ?, 'REGULAR_CLOSE', ?, ?, 'TOSS', ?)
                """, UUID.randomUUID(), userId, inputId, ticker, capturedAt, price, capturedAt, capturedAt);
    }

    private TacticalOverlayService.PerformanceInput performanceInput(String key, UUID decisionId,
                                                                       LocalDate entryDate, String riskPrice,
                                                                       String entrySetup, String effect,
                                                                       Instant sourceAsOf) {
        return new TacticalOverlayService.PerformanceInput(key, decisionId, entryDate, bd("120"), bd(riskPrice),
                bd("5"), entrySetup, effect, List.of(), "USER_INPUT", sourceAsOf);
    }

    private JsonNode aggregate(JsonNode aggregates, String entityKey) {
        var match = findByEntityKeyOrNull(aggregates, entityKey);
        assertThat(match).as("aggregate for %s", entityKey).isNotNull();
        return match;
    }

    private JsonNode findByEntityKeyOrNull(JsonNode values, String entityKey) {
        if (values == null || !values.isArray()) return null;
        for (var value : values) {
            if (entityKey.equals(value.path("entityKey").asString())
                    || entityKey.equals(value.path("themeId").asString())) return value;
        }
        return null;
    }

    private JsonNode findById(JsonNode values, String id) {
        var match = findByIdOrNull(values, id);
        assertThat(match).as("anchor %s", id).isNotNull();
        return match;
    }

    private JsonNode findByIdOrNull(JsonNode values, String id) {
        if (values == null || !values.isArray()) return null;
        for (var value : values) if (id.equals(value.path("id").asString())) return value;
        return null;
    }

    private JsonNode performanceByKey(JsonNode values, String key) {
        var match = performanceByKeyOrNull(values, key);
        assertThat(match).as("performance entry %s", key).isNotNull();
        return match;
    }

    private JsonNode performanceByKeyOrNull(JsonNode values, String key) {
        if (values == null || !values.isArray()) return null;
        for (var value : values) {
            if (key.equals(value.path("performance").path("key").asText())) return value;
        }
        return null;
    }

    private void assertMetric(JsonNode performance, String metric, String expected, String status) {
        var value = performance.path(metric);
        assertThat(value.path("status").asText()).isEqualTo(status);
        assertThat(value.path("value").decimalValue()).isEqualByComparingTo(expected);
    }

    private void assertForward(JsonNode performance, int horizon, String expected, LocalDate endpoint) {
        var rows = performance.path("forwardReturns");
        JsonNode match = null;
        for (var row : rows) if (row.path("horizon").asInt() == horizon) match = row;
        assertThat(match).isNotNull();
        assertThat(match.path("returnPct").path("status").asText()).isEqualTo("OK");
        assertThat(match.path("returnPct").path("value").decimalValue()).isEqualByComparingTo(expected);
        assertThat(match.path("endpointDate").asText()).isEqualTo(endpoint.toString());
    }

    private BigDecimal metricValue(JsonNode aggregate, String name) {
        var value = aggregate.path("metrics").path(name).path("value");
        assertThat(value.isNumber()).as("metric %s value", name).isTrue();
        return value.decimalValue();
    }

    private int snapshotCount(UUID owner) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND overlay_version = 'TACTICAL_V1'
                """, Integer.class, owner);
    }

    private int inputCount(UUID owner) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM investment_tactical_overlay_inputs WHERE user_id = ?
                """, Integer.class, owner);
    }

    private static Instant sourceAsOf(LocalDate date) {
        return date.atTime(16, 0).atZone(ZoneId.of("America/New_York")).toInstant();
    }

    private static List<LocalDate> tradingDates(LocalDate first, int count) {
        var dates = new ArrayList<LocalDate>(count);
        for (var date = first; dates.size() < count; date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() < 6) dates.add(date);
        }
        return List.copyOf(dates);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static final class AdvancingClock extends Clock {
        private final AtomicLong millis;
        private final ZoneId zone;

        private AdvancingClock(Instant start) {
            this(new AtomicLong(start.toEpochMilli()), ZoneOffset.UTC);
        }

        private AdvancingClock(AtomicLong millis, ZoneId zone) {
            this.millis = millis;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId nextZone) {
            return new AdvancingClock(millis, nextZone);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.getAndIncrement());
        }
    }
}
