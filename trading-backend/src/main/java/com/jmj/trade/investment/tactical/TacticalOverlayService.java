package com.jmj.trade.investment.tactical;

import com.jmj.trade.investment.InvestmentException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.jmj.trade.investment.tactical.TacticalOverlayCalculator.*;

/** User-scoped persistence and read model for the additive TACTICAL_V1 overlay. */
@Service
public class TacticalOverlayService {

    private static final Pattern TICKER = Pattern.compile("[A-Z0-9._-]{1,32}");
    private static final Pattern INPUT_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,120}");
    private static final String VERSION = "TACTICAL_V1";
    private static final ZoneId SOURCE_ZONE = ZoneId.of("America/New_York");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TacticalOverlayCalculator calculator;
    private final TacticalOverlayAggregationCalculator aggregationCalculator;
    private final TacticalOverlayProperties properties;
    private final Clock clock;
    private final List<String> trackedSymbols;

    @Autowired
    public TacticalOverlayService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            TacticalOverlayCalculator calculator,
            TacticalOverlayProperties properties,
            TacticalOverlayAggregationCalculator aggregationCalculator,
            @Value("${investment.tactical-overlay.tracked-symbols:AVT,CSTM,GOOGL,LUNR,RDW,VST}") String trackedSymbols
    ) {
        this(jdbc, mapper, calculator, properties, aggregationCalculator, trackedSymbols, Clock.systemUTC());
    }

    TacticalOverlayService(JdbcTemplate jdbc, ObjectMapper mapper, TacticalOverlayCalculator calculator,
                           TacticalOverlayProperties properties, String trackedSymbols, Clock clock) {
        this(jdbc, mapper, calculator, properties, new TacticalOverlayAggregationCalculator(), trackedSymbols, clock);
    }

    TacticalOverlayService(JdbcTemplate jdbc, ObjectMapper mapper, TacticalOverlayCalculator calculator,
                           TacticalOverlayProperties properties, TacticalOverlayAggregationCalculator aggregationCalculator,
                           String trackedSymbols, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.aggregationCalculator = Objects.requireNonNull(aggregationCalculator, "aggregationCalculator");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.trackedSymbols = parseSymbols(trackedSymbols);
    }

    public List<String> trackedSymbols() {
        return trackedSymbols;
    }

    public boolean needsInitialCapture(UUID userId, List<String> symbols) {
        requireUser(userId);
        var universe = new LinkedHashSet<>(trackedSymbols);
        if (symbols != null) universe.addAll(symbols);
        for (var symbol : universe) {
            var exists = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM investment_tactical_overlay_bar_snapshots
                     WHERE user_id = ? AND ticker = ?)
                    """, Boolean.class, userId, symbol);
            if (!Boolean.TRUE.equals(exists)) return true;
        }
        return !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM investment_tactical_overlay_bar_snapshots
                 WHERE user_id = ? AND ticker = 'SPY')
                """, Boolean.class, userId));
    }

    @Transactional
    public void recordTossBars(UUID userId, String symbol, JsonNode rows, Instant capturedAt,
                               UUID originSnapshotId) {
        requireUser(userId);
        var ticker = ticker(symbol);
        if (rows == null || !rows.isArray() || capturedAt == null) return;
        var byDate = new LinkedHashMap<LocalDate, List<ParsedBar>>();
        for (var row : rows) {
            var bar = parseBar(row, capturedAt);
            if (bar == null) continue;
            var values = byDate.computeIfAbsent(bar.bar().date(), ignored -> new ArrayList<>());
            if (values.stream().noneMatch(existing -> existing.bar().equals(bar.bar()))) values.add(bar);
        }
        byDate.values().stream().filter(values -> values.size() > 1).forEach(values -> {
            for (var index = 0; index < values.size(); index++) {
                var value = values.get(index);
                values.set(index, new ParsedBar(value.bar(), value.sourceAsOf(), true));
            }
        });
        var accepted = byDate.values().stream().flatMap(List::stream).toList();
        if (accepted.isEmpty()) return;
        var captured = timestamp(capturedAt);
        var captureOrder = jdbc.queryForObject(
                "SELECT nextval('investment_tactical_overlay_capture_order_seq')", Long.class);
        accepted.stream().sorted(Comparator.comparing((ParsedBar parsed) -> parsed.bar().date())
                .thenComparing(parsed -> parsed.bar().close())).forEach(parsed -> {
            var bar = parsed.bar();
            jdbc.update("""
                    INSERT INTO investment_tactical_overlay_bar_snapshots (
                        id, user_id, ticker, bar_date, source, source_as_of, captured_at,
                        origin_input_snapshot_id, open_price, high_price, low_price, close_price,
                        volume, source_conflict, capture_order, created_at
                    )
                    SELECT ?, ?, ?, ?, 'TOSS', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                     WHERE NOT EXISTS (
                         SELECT 1 FROM investment_tactical_overlay_bar_snapshots
                          WHERE user_id = ? AND ticker = ? AND bar_date = ?
                            AND open_price = ? AND high_price = ? AND low_price = ?
                            AND close_price = ? AND volume = ? AND source_conflict = ?
                            AND capture_order >= COALESCE((
                                SELECT max(capture_order) FROM investment_tactical_overlay_bar_snapshots
                                 WHERE user_id = ? AND ticker = ? AND bar_date = ? AND source_conflict
                            ), 0)
                     )
                    """, UUID.randomUUID(), userId, ticker, bar.date(), timestamp(parsed.sourceAsOf()), captured,
                    originSnapshotId, bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(),
                    parsed.sourceConflict(), captureOrder, captured,
                    userId, ticker, bar.date(), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(),
                    parsed.sourceConflict(), userId, ticker, bar.date());
        });
    }

    public void recordUnavailableBenchmark(UUID userId, Instant attemptedAt, String safeReason) {
        requireUser(userId);
        var reason = safeReason == null ? "BENCHMARK_DATA_MISSING" : safeReason;
        var previous = latestSnapshot(userId, "MARKET", "TRACKED_SECURITIES");
        var payload = previous == null ? object(Map.of("universe", trackedSymbols, "benchmark", "SPY"))
                : parse(previous.payload());
        if (!(payload instanceof ObjectNode)) payload = object(Map.of("universe", trackedSymbols, "benchmark", "SPY"));
        ((ObjectNode) payload).put("benchmarkStatus", "STALE");
        ((ObjectNode) payload).put("benchmarkReason", reason);
        var reasons = new LinkedHashSet<>(previous == null ? List.<String>of() : previous.reasons());
        reasons.add(reason);
        var refs = previous == null ? List.<UUID>of() : snapshotRefs(userId, "MARKET", "TRACKED_SECURITIES");
        var status = previous == null ? "DATA_MISSING"
                : "OK".equals(previous.status()) ? "PARTIAL" : previous.status();
        writeSnapshot(userId, "MARKET", "TRACKED_SECURITIES", null,
                previous == null ? null : previous.asOf(), previous == null ? "TOSS" : previous.source(),
                status, List.copyOf(reasons), refs, payload,
                previous == null ? null : previous.sourceAsOf());
    }

    public void recordBenchmarkBars(UUID userId, JsonNode rows, Instant capturedAt) {
        recordTossBars(userId, "SPY", rows, capturedAt, null);
    }

    /** Called only from the existing scheduled/full capture path, never from context reads. */
    @Transactional
    public void refresh(UUID userId) {
        requireUser(userId);
        var symbols = new LinkedHashSet<>(trackedSymbols);
        symbols.addAll(jdbc.query("""
                SELECT DISTINCT ticker FROM investment_tactical_overlay_bar_snapshots
                 WHERE user_id = ? AND ticker <> 'SPY' ORDER BY ticker
                """, (rs, row) -> rs.getString(1), userId));
        var results = new LinkedHashMap<String, Result>();
        for (var symbol : symbols) {
            var result = calculateAndPersistSecurity(userId, symbol);
            if (result != null) results.put(symbol, result);
        }
        var histories = new LinkedHashMap<String, History>();
        symbols.forEach(symbol -> histories.put(symbol, history(userId, symbol)));
        histories.put("SPY", history(userId, "SPY"));
        var themePayloads = themePayloads(userId, results, histories);
        themePayloads.forEach((themeId, payload) -> writeSnapshot(userId, "THEME", themeId, null,
                payload.date(), payload.source(), payload.status(), payload.reasons(), payload.inputRefs(),
                payload.node(), payload.sourceAsOf()));
        var market = marketPayload(results, histories, themePayloads);
        writeSnapshot(userId, "MARKET", "TRACKED_SECURITIES", null, market.date(), market.source(),
                market.status(), market.reasons(), market.inputRefs(), market.node(), market.sourceAsOf());
        persistPerformanceSnapshots(userId, symbols);
    }

    public TacticalInputsView inputs(UUID userId) {
        requireUser(userId);
        var rows = jdbc.query("""
                SELECT id, input_type, entity_key, ticker, decision_id, effective_date, source,
                       source_as_of, active, overlay_version, payload::text, created_at
                  FROM investment_tactical_overlay_inputs
                 WHERE user_id = ?
                 ORDER BY input_order
                """, (rs, row) -> new TacticalInputView(
                rs.getObject("id", UUID.class), rs.getString("input_type"), rs.getString("entity_key"),
                rs.getString("ticker"), rs.getObject("decision_id", UUID.class),
                rs.getObject("effective_date", LocalDate.class), rs.getString("source"),
                instant(rs.getObject("source_as_of", OffsetDateTime.class)), rs.getBoolean("active"),
                rs.getString("overlay_version"), parse(rs.getString("payload")),
                instant(rs.getObject("created_at", OffsetDateTime.class))), userId);
        return new TacticalInputsView(VERSION, rows);
    }

    @Transactional
    public UUID putAnchor(UUID userId, String symbol, AnchorInput request) {
        requireUser(userId);
        var ticker = ticker(symbol);
        requireKey(request == null ? null : request.anchorId(), "anchorId");
        if (request == null || request.anchorType() == null || request.date() == null || request.sourceAsOf() == null) invalid();
        var sourceAsOf = requiredInstant(request.sourceAsOf());
        var payload = object(Map.of("anchorId", request.anchorId(), "anchorType", request.anchorType().name(),
                "date", request.date().toString()));
        var id = appendInput(userId, "AVWAP_ANCHOR", ticker + ":" + request.anchorId(), ticker, null,
                request.date(), source(request.source()), sourceAsOf, true, payload);
        refresh(userId);
        return id;
    }

    @Transactional
    public UUID deleteAnchor(UUID userId, String symbol, String anchorId, Instant sourceAsOf) {
        requireUser(userId);
        var ticker = ticker(symbol);
        requireKey(anchorId, "anchorId");
        var prior = latestInput(userId, "AVWAP_ANCHOR", ticker + ":" + anchorId);
        if (prior == null || !prior.active()) throw new InvestmentException(InvestmentException.Code.NOT_FOUND);
        var node = object(Map.of("anchorId", anchorId));
        var id = appendInput(userId, "AVWAP_ANCHOR", ticker + ":" + anchorId, ticker, null,
                prior.effectiveDate(), "USER_INPUT", requiredInstant(sourceAsOf), false, node);
        refresh(userId);
        return id;
    }

    @Transactional
    public UUID putTheme(UUID userId, ThemeInput request) {
        requireUser(userId);
        requireKey(request == null ? null : request.themeId(), "themeId");
        if (request.name() == null || request.name().isBlank() || request.name().trim().length() > 120
                || request.effectiveDate() == null || request.sourceAsOf() == null) invalid();
        var sourceAsOf = requiredInstant(request.sourceAsOf());
        var payload = object(Map.of("themeId", request.themeId(), "name", request.name().trim(),
                "effectiveDate", request.effectiveDate().toString()));
        var id = appendInput(userId, "THEME", request.themeId(), null, null, request.effectiveDate(),
                source(request.source()), sourceAsOf, true, payload);
        refresh(userId);
        return id;
    }

    @Transactional
    public UUID deleteTheme(UUID userId, String themeId, Instant sourceAsOf) {
        requireUser(userId);
        requireKey(themeId, "themeId");
        var prior = latestInput(userId, "THEME", themeId);
        if (prior == null || !prior.active()) throw new InvestmentException(InvestmentException.Code.NOT_FOUND);
        var removedAt = requiredInstant(sourceAsOf);
        var effectiveDate = removedAt.atZone(SOURCE_ZONE).toLocalDate();
        if (effectiveDate.isBefore(prior.effectiveDate())) effectiveDate = prior.effectiveDate();
        var id = appendInput(userId, "THEME", themeId, null, null, effectiveDate, "USER_INPUT",
                removedAt, false, object(Map.of("themeId", themeId)));
        refresh(userId);
        return id;
    }

    @Transactional
    public UUID putThemeMapping(UUID userId, ThemeMappingInput request) {
        requireUser(userId);
        requireKey(request == null ? null : request.themeId(), "themeId");
        var ticker = ticker(request.ticker());
        if (request.effectiveDate() == null || request.sourceAsOf() == null) invalid();
        var sourceAsOf = requiredInstant(request.sourceAsOf());
        var theme = latestInput(userId, "THEME", request.themeId());
        if (theme == null || !theme.active()) throw new InvestmentException(InvestmentException.Code.NOT_FOUND);
        var themeRows = inputRows(userId, "THEME");
        var mappingRows = inputRows(userId, "THEME_MAPPING");
        for (var interval : membershipIntervals(themeRows, mappingRows)) {
            if (ticker.equals(interval.ticker()) && !request.themeId().equals(interval.themeId())
                    && interval.contains(request.effectiveDate())) {
                var closeKey = mappingKey(interval.themeId(), ticker, request.effectiveDate());
                appendInput(userId, "THEME_MAPPING", closeKey, ticker, null, request.effectiveDate(),
                        "USER_INPUT", sourceAsOf, false,
                        object(Map.of("themeId", interval.themeId(), "ticker", ticker,
                                "effectiveDate", request.effectiveDate().toString())));
            }
        }
        var entityKey = mappingKey(request.themeId(), ticker, request.effectiveDate());
        var payload = object(Map.of("themeId", request.themeId(), "ticker", ticker,
                "effectiveDate", request.effectiveDate().toString()));
        var id = appendInput(userId, "THEME_MAPPING", entityKey, ticker, null, request.effectiveDate(),
                source(request.source()), sourceAsOf, true, payload);
        refresh(userId);
        return id;
    }

    @Transactional
    public UUID deleteThemeMapping(UUID userId, String themeId, String symbol, LocalDate effectiveDate,
                                   Instant sourceAsOf) {
        requireUser(userId);
        requireKey(themeId, "themeId");
        var ticker = ticker(symbol);
        if (effectiveDate == null) invalid();
        var active = membershipIntervals(inputRows(userId, "THEME"), inputRows(userId, "THEME_MAPPING"))
                .stream().anyMatch(interval -> themeId.equals(interval.themeId()) && ticker.equals(interval.ticker())
                        && interval.contains(effectiveDate));
        if (!active) throw new InvestmentException(InvestmentException.Code.NOT_FOUND);
        var key = mappingKey(themeId, ticker, effectiveDate);
        var payload = object(Map.of("themeId", themeId, "ticker", ticker,
                "effectiveDate", effectiveDate.toString()));
        var id = appendInput(userId, "THEME_MAPPING", key, ticker, null, effectiveDate, "USER_INPUT",
                requiredInstant(sourceAsOf), false, payload);
        refresh(userId);
        return id;
    }

    @Transactional
    public UUID putPerformanceEntry(UUID userId, String symbol, PerformanceInput request) {
        requireUser(userId);
        var ticker = ticker(symbol);
        requireKey(request == null ? null : request.key(), "performanceKey");
        if (request.entryDate() == null || request.entryPrice() == null || request.initialRiskPrice() == null
                || request.sourceAsOf() == null || request.entryPrice().signum() <= 0
                || request.initialRiskPrice().signum() <= 0
                || request.entryPrice().compareTo(request.initialRiskPrice()) <= 0
                || (request.quantity() != null && request.quantity().signum() <= 0)) invalid();
        var sourceAsOf = requiredInstant(request.sourceAsOf());
        if (request.entryDate().isAfter(sourceAsOf.atZone(SOURCE_ZONE).toLocalDate())) invalid();
        validateEffect(request.overlayEffect());
        if (request.entrySetup() != null && (request.entrySetup().isBlank() || request.entrySetup().length() > 128)) {
            invalid();
        }
        if (request.decisionId() != null) requireDecisionAsset(userId, request.decisionId(), ticker);
        var payload = objectMapperCreate();
        payload.put("key", request.key());
        payload.put("kind", request.decisionId() == null ? "POSITION" : "DECISION");
        payload.put("entryDate", request.entryDate().toString());
        payload.put("entryPrice", request.entryPrice());
        payload.put("initialRiskPrice", request.initialRiskPrice());
        if (request.quantity() != null) payload.put("quantity", request.quantity());
        if (request.entrySetup() != null) payload.put("entrySetup", request.entrySetup().trim());
        if (request.overlayEffect() != null) payload.put("overlayEffect", request.overlayEffect());
        var exits = payload.putArray("exits");
        var exitedQuantity = BigDecimal.ZERO;
        for (var exit : request.exits() == null ? List.<ExitInput>of() : request.exits()) {
            if (exit == null || exit.date() == null || exit.price() == null || exit.quantity() == null
                    || exit.price().signum() <= 0 || exit.quantity().signum() <= 0) invalid();
            if (exit.date().isBefore(request.entryDate())
                    || exit.date().isAfter(sourceAsOf.atZone(SOURCE_ZONE).toLocalDate())) invalid();
            exitedQuantity = exitedQuantity.add(exit.quantity());
            var row = exits.addObject();
            row.put("date", exit.date().toString());
            row.put("price", exit.price());
            row.put("quantity", exit.quantity());
        }
        if (request.quantity() != null && exitedQuantity.compareTo(request.quantity()) > 0) invalid();
        var id = appendInput(userId, "PERFORMANCE_ENTRY", ticker + ":" + request.key(), ticker,
                request.decisionId(), request.entryDate(), source(request.source()), sourceAsOf, true, payload);
        refresh(userId);
        return id;
    }

    @Transactional
    public UUID deletePerformanceEntry(UUID userId, String symbol, String key, Instant sourceAsOf) {
        requireUser(userId);
        var ticker = ticker(symbol);
        requireKey(key, "performanceKey");
        var entityKey = ticker + ":" + key;
        var prior = latestInput(userId, "PERFORMANCE_ENTRY", entityKey);
        if (prior == null || !prior.active()) throw new InvestmentException(InvestmentException.Code.NOT_FOUND);
        var id = appendInput(userId, "PERFORMANCE_ENTRY", entityKey, ticker, prior.decisionId(),
                prior.effectiveDate(), "USER_INPUT", requiredInstant(sourceAsOf), false,
                object(Map.of("key", key)));
        refresh(userId);
        return id;
    }

    public ReadModel context(UUID userId, List<String> symbols) {
        requireUser(userId);
        var securityViews = new LinkedHashMap<String, com.jmj.trade.investment.InvestmentContextService.SecurityTacticalOverlayView>();
        var requested = symbols == null ? List.<String>of() : symbols.stream().map(TacticalOverlayService::ticker)
                .distinct().toList();
        for (var symbol : requested) securityViews.put(symbol, securityView(userId, symbol));
        var market = latestSnapshot(userId, "MARKET", "TRACKED_SECURITIES");
        var themes = latestThemeSnapshots(userId, contextEffectiveDate(market == null ? null : market.asOf()));
        var marketPayload = market == null ? null : parse(market.payload());
        var themePayload = themes.isEmpty() ? null : mapper.valueToTree(themes);
        var portfolio = market == null
                ? com.jmj.trade.investment.InvestmentContextService.TacticalOverlayPortfolioView.notConfigured()
                : new com.jmj.trade.investment.InvestmentContextService.TacticalOverlayPortfolioView(
                        market.status(), market.asOf(), market.source(), market.sourceAsOf(),
                        marketPayload, themePayload);
        var decisions = decisionViews(userId);
        return new ReadModel(portfolio, Map.copyOf(securityViews), Map.copyOf(decisions));
    }

    private Result calculateAndPersistSecurity(UUID userId, String symbol) {
        var history = history(userId, symbol);
        if (history.bars().isEmpty()) {
            writeSnapshot(userId, "SECURITY", symbol, symbol, null, "TOSS", "DATA_MISSING",
                    List.of("OHLCV_HISTORY_MISSING"), history.refs(), object(Map.of(
                            "symbol", symbol, "status", "DATA_MISSING")), clock.instant());
            return null;
        }
        var asOf = history.bars().getLast().date();
        var anchors = activeAnchors(userId, symbol, asOf);
        var mark = latestMark(userId, symbol);
        var spy = history(userId, "SPY");
        var input = new Input(symbol, asOf, clock.instant(), true, history.bars(), benchmarkBars(spy), "TOSS", history.sourceConflict(),
                history.bars().size() >= 60, PriceAdjustmentStatus.UNADJUSTED, List.of(), anchors,
                mark == null ? null : mark.mark(), null);
        var result = calculator.calculate(input);
        var payload = (ObjectNode) mapper.valueToTree(result);
        var themeId = themeFor(userId, symbol, asOf);
        if (themeId == null) payload.putNull("themeId");
        else payload.put("themeId", themeId);
        var sourceAsOf = history.latestSourceAsOf();
        writeSnapshot(userId, "SECURITY", symbol, symbol, result.asOf(), result.source(), result.status().name(),
                List.of(result.stageStatus().name()), securityInputRefs(userId, symbol, asOf, history), payload, sourceAsOf);
        return result;
    }

    private void persistPerformanceSnapshots(UUID userId, Set<String> symbols) {
        persistPerformanceSnapshots(userId, symbols, LocalDate.now(clock.withZone(SOURCE_ZONE)));
    }

    /** Refresh only configured performance inputs after accepted quote snapshots; never fetches prices or bars. */
    @Transactional
    public void refreshPerformanceMarks(UUID userId, List<String> symbols) {
        requireUser(userId);
        if (symbols == null || symbols.isEmpty()) return;
        var selected = new LinkedHashSet<String>();
        symbols.stream().filter(Objects::nonNull).map(TacticalOverlayService::ticker).forEach(selected::add);
        if (!selected.isEmpty()) {
            persistPerformanceSnapshots(userId, selected, LocalDate.now(clock.withZone(SOURCE_ZONE)));
        }
    }

    private void persistPerformanceSnapshots(UUID userId, Set<String> symbols, LocalDate evaluationDate) {
        var evaluatedAt = clock.instant();
        for (var symbol : symbols) {
            var entries = activePerformanceEntries(userId, symbol, evaluationDate);
            if (entries.isEmpty()) continue;
            var history = history(userId, symbol);
            var completedBars = history.bars().stream().filter(bar -> !bar.date().isAfter(evaluationDate)).toList();
            if (completedBars.isEmpty()) continue;
            var barAsOf = completedBars.getLast().date();
            var barSourceAsOf = sourceAsOfThrough(history, evaluationDate);
            var mark = latestMark(userId, symbol);
            for (var entry : entries) {
                var input = new Input(symbol, evaluationDate, evaluatedAt, true,
                        completedBars, List.of(),
                        "TOSS", sourceConflictThrough(history, evaluationDate), completedBars.size() >= 60,
                        PriceAdjustmentStatus.UNADJUSTED,
                        List.of(), List.of(), mark == null ? null : mark.mark(), entry.entry());
                var result = calculator.calculate(input);
                var sourceAsOf = laterInstant(barSourceAsOf, entry.sourceAsOf());
                sourceAsOf = laterInstant(sourceAsOf, mark == null ? null : mark.mark().asOf());
                var node = mapper.createObjectNode();
                var performanceNode = (ObjectNode) mapper.valueToTree(result.performance());
                performanceNode.put("source", entry.source());
                performanceNode.put("sourceAsOf", sourceAsOf.toString());
                performanceNode.put("entrySourceAsOf", entry.sourceAsOf().toString());
                performanceNode.put("evaluationAsOf", evaluationDate.toString());
                performanceNode.put("barAsOf", barAsOf.toString());
                if (barSourceAsOf == null) performanceNode.putNull("barSourceAsOf");
                else performanceNode.put("barSourceAsOf", barSourceAsOf.toString());
                performanceNode.put("overlayVersion", VERSION);
                if (mark == null) {
                    performanceNode.putNull("markAsOf");
                    performanceNode.putNull("markSource");
                    performanceNode.putNull("markSnapshotId");
                } else {
                    performanceNode.put("markAsOf", mark.mark().asOf().toString());
                    performanceNode.put("markSource", mark.source());
                    performanceNode.put("markSnapshotId", mark.id().toString());
                }
                node.set("performance", performanceNode);
                node.put("entrySetup", entry.payload().path("entrySetup").isMissingNode()
                        ? "" : entry.payload().path("entrySetup").asString());
                if (entry.payload().path("initialRiskPrice").isMissingNode()) node.putNull("initialRiskPrice");
                else node.set("initialRiskPrice", entry.payload().path("initialRiskPrice"));
                node.put("overlayEffect", entry.payload().path("overlayEffect").isMissingNode()
                        ? "" : entry.payload().path("overlayEffect").asString());
                node.put("source", entry.source());
                node.put("entrySourceAsOf", entry.sourceAsOf().toString());
                node.put("sourceAsOf", sourceAsOf.toString());
                node.put("evaluationAsOf", evaluationDate.toString());
                node.put("barAsOf", barAsOf.toString());
                if (barSourceAsOf == null) node.putNull("barSourceAsOf");
                else node.put("barSourceAsOf", barSourceAsOf.toString());
                node.put("overlayVersion", VERSION);
                if (mark == null) {
                    node.putNull("markAsOf");
                    node.putNull("markSource");
                    node.putNull("markSnapshotId");
                } else {
                    node.put("markAsOf", mark.mark().asOf().toString());
                    node.put("markSource", mark.source());
                    node.put("markSnapshotId", mark.id().toString());
                }
                var type = entry.decisionId() == null ? "POSITION" : "DECISION";
                var key = entry.decisionId() == null ? entry.key() : entry.decisionId().toString();
                var refs = new LinkedHashSet<>(performanceRefs(history, entry.id(), evaluationDate));
                if (mark != null) refs.add(mark.id());
                var sources = new ArrayList<>(List.of("TOSS", entry.source()));
                if (mark != null) sources.add(mark.source());
                writeSnapshot(userId, type, key, symbol, evaluationDate,
                        joinedSources(sources, null),
                        result.performance().status().name(), List.of(), List.copyOf(refs), node, sourceAsOf);
            }
        }
    }

    private Map<String, AggregatePayload> themePayloads(UUID userId, Map<String, Result> results,
                                                         Map<String, History> histories) {
        var output = new LinkedHashMap<String, AggregatePayload>();
        var allThemes = inputRows(userId, "THEME");
        var mappingRows = inputRows(userId, "THEME_MAPPING");
        var intervals = membershipIntervals(allThemes, mappingRows);
        var defaultAsOf = latestAvailableDate(trackedSymbols, histories);
        var barsByTicker = barsByTicker(histories);
        for (var theme : themeRowsAt(allThemes, defaultAsOf)) {
            var themeId = theme.entityKey();
            var themeMappings = intervals.stream().filter(interval -> themeId.equals(interval.themeId())).toList();
            var currentMembers = themeMappings.stream().filter(interval -> interval.contains(defaultAsOf))
                    .map(TacticalOverlayAggregationCalculator.MembershipInterval::ticker).distinct().toList();
            var asOf = latestAvailableDate(currentMembers, histories);
            if (asOf == null) asOf = defaultAsOf;
            final var themeAsOf = asOf;
            var baseDate = commonBaseDate(currentMembers, histories, themeAsOf);
            var aggregate = aggregationCalculator.calculateTheme(themeId, themeAsOf, baseDate, themeAsOf,
                    properties.breakoutLookback(), results, barsByTicker, themeMappings);
            var refs = new LinkedHashSet<UUID>(aggregate.inputRefs());
            var spy = histories.get("SPY");
            var spyRefs = spy == null ? List.<UUID>of() : refsThrough(spy, themeAsOf);
            refs.addAll(spyRefs);
            refs.add(theme.id());
            themeMappings.stream().filter(interval -> !interval.fromInclusive().isAfter(themeAsOf))
                    .forEach(interval -> refs.addAll(interval.inputRefs()));
            themeMappings.stream().map(TacticalOverlayAggregationCalculator.MembershipInterval::ticker).distinct()
                    .map(histories::get).filter(Objects::nonNull)
                    .forEach(history -> refs.addAll(refsThrough(history, themeAsOf)));
            var node = (ObjectNode) mapper.valueToTree(aggregate);
            node.put("name", theme.payload().path("name").asString(""));
            node.put("overlayVersion", VERSION);
            node.set("inputRefs", mapper.valueToTree(refs));
            var sourceAsOf = latestSourceAsOf(histories, themeMappings.stream()
                    .map(TacticalOverlayAggregationCalculator.MembershipInterval::ticker).distinct().toList(), themeAsOf);
            sourceAsOf = laterInstant(sourceAsOf, spy == null ? null : sourceAsOfThrough(spy, themeAsOf));
            sourceAsOf = laterInstant(sourceAsOf, theme.sourceAsOf());
            for (var mapping : mappingRows) {
                if (themeId.equals(text(mapping.payload().path("themeId")))
                        && !mapping.effectiveDate().isAfter(themeAsOf)) {
                    sourceAsOf = laterInstant(sourceAsOf, mapping.sourceAsOf());
                }
            }
            if (sourceAsOf == null) node.putNull("sourceAsOf"); else node.put("sourceAsOf", sourceAsOf.toString());
            var sources = new ArrayList<>(aggregate.sources());
            if (!spyRefs.isEmpty()) sources.add("TOSS");
            var source = joinedSources(sources, theme.source());
            output.put(themeId, new AggregatePayload(asOf, sourceAsOf, source, aggregate.status().name(),
                    aggregate.reasons(), List.copyOf(refs), node));
        }
        return output;
    }

    private AggregatePayload marketPayload(Map<String, Result> results, Map<String, History> histories,
                                           Map<String, AggregatePayload> themes) {
        var asOf = latestAvailableDate(trackedSymbols, histories);
        var baseDate = commonBaseDate(trackedSymbols, histories, asOf);
        var barsByTicker = barsByTicker(histories);
        var aggregate = aggregationCalculator.calculateMarket("TRACKED_SECURITIES", asOf, baseDate, asOf,
                properties.breakoutLookback(), trackedSymbols, results, barsByTicker);
        var refs = new LinkedHashSet<UUID>(aggregate.inputRefs());
        for (var symbol : trackedSymbols) {
            var history = histories.get(symbol);
            if (history != null) refs.addAll(refsThrough(history, asOf));
        }
        var spy = histories.get("SPY");
        if (spy != null) refs.addAll(refsThrough(spy, asOf));
        themes.values().forEach(theme -> refs.addAll(theme.inputRefs()));
        var node = (ObjectNode) mapper.valueToTree(aggregate);
        node.put("overlayVersion", VERSION);
        node.put("universeType", "EXPLICIT_TRACKED_SECURITIES");
        node.set("universe", mapper.valueToTree(trackedSymbols));
        node.put("benchmark", "SPY");
        node.put("benchmarkCoverage", spy == null ? 0 : spy.bars().size());
        var benchmarkStatus = spy == null || spy.bars().isEmpty() ? "DATA_MISSING"
                : spy.sourceConflict() ? "SOURCE_CONFLICT" : "OK";
        node.put("benchmarkStatus", benchmarkStatus);
        var reasons = new LinkedHashSet<>(aggregate.reasons());
        if ("SOURCE_CONFLICT".equals(benchmarkStatus)) reasons.add("SPY_SOURCE_CONFLICT");
        var status = aggregate.status();
        if ("SOURCE_CONFLICT".equals(benchmarkStatus) && status == OverlayStatus.OK) {
            status = OverlayStatus.PARTIAL;
        }
        node.put("status", status.name());
        node.set("reasons", mapper.valueToTree(reasons));
        node.set("themes", mapper.valueToTree(themes.keySet()));
        node.set("inputRefs", mapper.valueToTree(refs));
        var sourceAsOf = latestSourceAsOf(histories, trackedSymbols, asOf);
        sourceAsOf = laterInstant(sourceAsOf, spy == null ? null : sourceAsOfThrough(spy, asOf));
        if (sourceAsOf == null) node.putNull("sourceAsOf"); else node.put("sourceAsOf", sourceAsOf.toString());
        var source = joinedSources(aggregate.sources(), "TOSS");
        return new AggregatePayload(aggregate.asOf(), sourceAsOf, source, status.name(),
                List.copyOf(reasons), List.copyOf(refs), node);
    }

    private List<InputRow> inputRows(UUID userId, String inputType) {
        return jdbc.query("""
                SELECT id, input_type, entity_key, ticker, decision_id, effective_date, source,
                       source_as_of, active, payload::text, created_at, input_order
                  FROM investment_tactical_overlay_inputs
                 WHERE user_id = ? AND input_type = ?
                 ORDER BY input_order
                """, (rs, row) -> new InputRow(rs.getObject("id", UUID.class), rs.getString("input_type"),
                rs.getString("entity_key"), rs.getString("ticker"), rs.getObject("decision_id", UUID.class),
                rs.getObject("effective_date", LocalDate.class), rs.getString("source"),
                instant(rs.getObject("source_as_of", OffsetDateTime.class)), rs.getBoolean("active"),
                parse(rs.getString("payload")), instant(rs.getObject("created_at", OffsetDateTime.class)),
                rs.getLong("input_order")), userId, inputType);
    }

    private static List<InputRow> themeRowsAt(List<InputRow> rows, LocalDate asOf) {
        if (asOf == null) return List.of();
        var grouped = rows.stream().collect(java.util.stream.Collectors.groupingBy(InputRow::entityKey,
                LinkedHashMap::new, java.util.stream.Collectors.toList()));
        var selected = new ArrayList<InputRow>();
        grouped.values().forEach(group -> {
            var states = statesByEffectiveDate(group);
            states.stream().filter(row -> !row.effectiveDate().isAfter(asOf)).reduce((left, right) -> right)
                    .filter(InputRow::active).ifPresent(selected::add);
        });
        return selected.stream().sorted(Comparator.comparing(InputRow::entityKey)).toList();
    }

    private List<TacticalOverlayAggregationCalculator.MembershipInterval> membershipIntervals(
            List<InputRow> themeRows, List<InputRow> mappingRows) {
        var themeIntervals = new LinkedHashMap<String, List<DateInterval>>();
        var groupedThemes = themeRows.stream().collect(java.util.stream.Collectors.groupingBy(InputRow::entityKey,
                LinkedHashMap::new, java.util.stream.Collectors.toList()));
        groupedThemes.forEach((themeId, group) -> themeIntervals.put(themeId, intervalsForStates(statesByEffectiveDate(group))));

        var groupedMappings = new LinkedHashMap<String, List<InputRow>>();
        for (var row : mappingRows) {
            var themeId = text(row.payload().path("themeId"));
            var symbol = row.ticker();
            if (themeId == null || symbol == null) continue;
            groupedMappings.computeIfAbsent(themeId + "\u0000" + symbol, ignored -> new ArrayList<>()).add(row);
        }
        var result = new ArrayList<TacticalOverlayAggregationCalculator.MembershipInterval>();
        groupedMappings.forEach((key, group) -> {
            var separator = key.indexOf('\u0000');
            var themeId = key.substring(0, separator);
            var symbol = key.substring(separator + 1);
            var mappingIntervals = intervalsForStates(statesByEffectiveDate(group));
            var themePeriods = themeIntervals.getOrDefault(themeId, List.of());
            for (var mapping : mappingIntervals) {
                if (!mapping.row().active()) continue;
                for (var theme : themePeriods) {
                    if (!theme.row().active()) continue;
                    var start = mapping.from().isAfter(theme.from()) ? mapping.from() : theme.from();
                    var end = earlierDate(mapping.to(), theme.to());
                    if (end != null && !end.isAfter(start)) continue;
                    var refs = new LinkedHashSet<UUID>();
                    refs.add(mapping.row().id());
                    refs.add(theme.row().id());
                    var sources = new LinkedHashSet<String>();
                    sources.add(mapping.row().source());
                    sources.add(theme.row().source());
                    if (mapping.endRow() != null) {
                        refs.add(mapping.endRow().id());
                        sources.add(mapping.endRow().source());
                    }
                    if (theme.endRow() != null) {
                        refs.add(theme.endRow().id());
                        sources.add(theme.endRow().source());
                    }
                    var source = joinedSources(List.copyOf(sources), null);
                    result.add(new TacticalOverlayAggregationCalculator.MembershipInterval(themeId, symbol,
                            start, end, source, List.copyOf(refs)));
                }
            }
        });
        return result.stream().sorted(Comparator
                .comparing(TacticalOverlayAggregationCalculator.MembershipInterval::themeId)
                .thenComparing(TacticalOverlayAggregationCalculator.MembershipInterval::ticker)
                .thenComparing(TacticalOverlayAggregationCalculator.MembershipInterval::fromInclusive)).toList();
    }

    private static List<InputRow> statesByEffectiveDate(List<InputRow> rows) {
        var byDate = new java.util.TreeMap<LocalDate, InputRow>();
        rows.stream().sorted(Comparator.comparingLong(InputRow::order)).forEach(row -> byDate.put(row.effectiveDate(), row));
        return List.copyOf(byDate.values());
    }

    private static List<DateInterval> intervalsForStates(List<InputRow> states) {
        var intervals = new ArrayList<DateInterval>();
        for (var index = 0; index < states.size(); index++) {
            var row = states.get(index);
            var next = index + 1 < states.size() ? states.get(index + 1) : null;
            intervals.add(new DateInterval(row.effectiveDate(), next == null ? null : next.effectiveDate(), row, next));
        }
        return intervals;
    }

    private static LocalDate earlierDate(LocalDate left, LocalDate right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private static Map<String, List<Bar>> barsByTicker(Map<String, History> histories) {
        var bars = new LinkedHashMap<String, List<Bar>>();
        histories.forEach((symbol, history) -> bars.put(symbol, history.bars()));
        return bars;
    }

    private static LocalDate latestAvailableDate(List<String> symbols, Map<String, History> histories) {
        return symbols.stream().map(histories::get).filter(Objects::nonNull).map(History::bars)
                .filter(bars -> !bars.isEmpty()).map(List::getLast).map(Bar::date).max(LocalDate::compareTo).orElse(null);
    }

    private static LocalDate commonBaseDate(List<String> symbols, Map<String, History> histories, LocalDate asOf) {
        if (asOf == null || symbols == null || symbols.isEmpty()) return null;
        var eligible = symbols.stream().distinct().map(histories::get).filter(Objects::nonNull)
                .filter(history -> history.bars().stream().anyMatch(bar -> bar.date().equals(asOf))).toList();
        if (eligible.isEmpty()) return null;
        var common = new LinkedHashSet<>(eligible.getFirst().bars().stream().map(Bar::date)
                .filter(date -> !date.isAfter(asOf)).toList());
        for (var history : eligible.stream().skip(1).toList()) {
            common.retainAll(history.bars().stream().map(Bar::date).filter(date -> !date.isAfter(asOf)).toList());
        }
        var dates = common.stream().sorted().toList();
        return dates.size() < 21 ? null : dates.get(dates.size() - 21);
    }

    private static List<UUID> refsThrough(History history, LocalDate asOf) {
        if (history == null || asOf == null) return List.of();
        return history.rows().stream().filter(row -> !row.date().isAfter(asOf)).map(BarRow::id).distinct().toList();
    }

    private static Instant sourceAsOfThrough(History history, LocalDate asOf) {
        if (history == null || asOf == null) return null;
        return history.rows().stream().filter(row -> !row.date().isAfter(asOf)).map(BarRow::sourceAsOf)
                .filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
    }

    private static Instant latestSourceAsOf(Map<String, History> histories, List<String> symbols, LocalDate asOf) {
        return symbols.stream().map(histories::get).filter(Objects::nonNull)
                .map(history -> sourceAsOfThrough(history, asOf)).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
    }

    private static Instant laterInstant(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private static String joinedSources(List<String> sources, String fallback) {
        var values = new LinkedHashSet<String>();
        if (sources != null) sources.stream().filter(value -> value != null && !value.isBlank()).sorted().forEach(values::add);
        if (fallback != null && !fallback.isBlank()) values.add(fallback);
        return values.isEmpty() ? "TOSS" : String.join("+", values);
    }

    private com.jmj.trade.investment.InvestmentContextService.SecurityTacticalOverlayView securityView(
            UUID userId, String symbol) {
        var snapshot = latestSnapshot(userId, "SECURITY", symbol);
        if (snapshot == null) return com.jmj.trade.investment.InvestmentContextService.SecurityTacticalOverlayView.notConfigured();
        var payload = dailyVwapAliases(parse(snapshot.payload()));
        var stageNode = payload == null ? null : payload.path("stage");
        var activePerformance = activePerformanceEntries(userId, symbol, contextEffectiveDate(snapshot.asOf())).stream()
                .filter(row -> row.decisionId() == null).toList();
        var performanceRows = activePerformance.stream()
                .map(row -> latestSnapshot(userId, "POSITION", row.key()))
                .filter(Objects::nonNull)
                .map((SnapshotRow row) -> currentMarkFreshness(parse(row.payload()))).toList();
        var performance = performanceRows.isEmpty() ? null : mapper.valueToTree(performanceRows);
        var latestEntry = activePerformance.size() == 1 ? activePerformance.getFirst() : null;
        var themeId = themeFor(userId, symbol, contextEffectiveDate(snapshot.asOf()));
        return new com.jmj.trade.investment.InvestmentContextService.SecurityTacticalOverlayView(
                snapshot.status(), snapshot.reasons().isEmpty() ? null : snapshot.reasons().getFirst(),
                themeId,
                stageNode == null || stageNode.isNull() ? null : stageNode.asString(),
                latestEntry == null ? null : optionalString(latestEntry.payload(), "entrySetup"),
                latestEntry == null ? null : decimal(latestEntry.payload().path("initialRiskPrice")),
                latestEntry == null ? null : optionalString(latestEntry.payload(), "overlayEffect"),
                snapshot.source(), snapshot.sourceAsOf(), snapshot.asOf(), VERSION,
                payload == null ? null : payload.path("indicators"),
                payload == null ? null : payload.path("events"),
                payload == null ? null : payload.path("cohorts"),
                payload == null ? null : payload.path("anchoredVwaps"), performance);
    }

    private Map<UUID, com.jmj.trade.investment.InvestmentContextService.DecisionTacticalOverlayView> decisionViews(UUID userId) {
        var output = new LinkedHashMap<UUID, com.jmj.trade.investment.InvestmentContextService.DecisionTacticalOverlayView>();
        var activeIds = activeInputs(userId, "PERFORMANCE_ENTRY").stream().map(InputRow::decisionId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        for (var snapshot : jdbc.query("""
                SELECT DISTINCT ON (entity_key) entity_key, ticker, as_of, source_as_of, source, status,
                       reasons::text, payload::text
                  FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type = 'DECISION' AND overlay_version = 'TACTICAL_V1'
                 ORDER BY entity_key, snapshot_order DESC
                """, (rs, row) -> new SnapshotRow(rs.getString("entity_key"), rs.getString("ticker"),
                rs.getObject("as_of", LocalDate.class), instant(rs.getObject("source_as_of", OffsetDateTime.class)),
                rs.getString("source"), rs.getString("status"), parseArray(rs.getString("reasons")),
                rs.getString("payload")), userId)) {
            try {
                var decisionId = UUID.fromString(snapshot.entityKey());
                if (!activeIds.contains(decisionId)) continue;
                var node = currentMarkFreshness(parse(snapshot.payload()));
                var performance = node == null ? null : node.path("performance");
                var status = performance != null && "STALE".equals(text(performance.path("status")))
                        ? "STALE" : snapshot.status();
                output.put(decisionId, new com.jmj.trade.investment.InvestmentContextService.DecisionTacticalOverlayView(
                        decisionId, status, optionalString(node, "entrySetup"),
                        decimal(node.path("initialRiskPrice")), optionalString(node, "overlayEffect"),
                        snapshot.source(), snapshot.sourceAsOf(), VERSION, performance));
            } catch (IllegalArgumentException ignored) {
                // An invalid historical key is not surfaced as an owned decision overlay.
            }
        }
        return output;
    }

    private History history(UUID userId, String symbol) {
        var rows = jdbc.query("""
                SELECT id, bar_date, source_as_of, captured_at, capture_order, source_conflict,
                       open_price, high_price, low_price, close_price, volume
                  FROM investment_tactical_overlay_bar_snapshots
                 WHERE user_id = ? AND ticker = ?
                 ORDER BY bar_date, capture_order
                """, (rs, row) -> new BarRow(rs.getObject("id", UUID.class),
                rs.getObject("bar_date", LocalDate.class), instant(rs.getObject("source_as_of", OffsetDateTime.class)),
                instant(rs.getObject("captured_at", OffsetDateTime.class)), rs.getLong("capture_order"),
                rs.getBoolean("source_conflict"),
                rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"), rs.getBigDecimal("low_price"),
                rs.getBigDecimal("close_price"), rs.getBigDecimal("volume")), userId, symbol);
        var byDate = rows.stream().collect(java.util.stream.Collectors.groupingBy(BarRow::date,
                java.util.TreeMap::new, java.util.stream.Collectors.toList()));
        var latestRows = new ArrayList<BarRow>();
        var conflicted = false;
        for (var dateRows : byDate.values()) {
            var latestOrder = dateRows.stream().mapToLong(BarRow::captureOrder).max().orElseThrow();
            var latest = dateRows.stream().filter(row -> row.captureOrder() == latestOrder).toList();
            var uniqueBars = latest.stream().map(BarRow::bar).distinct().count();
            if (latest.stream().anyMatch(BarRow::sourceConflict) || uniqueBars > 1) conflicted = true;
            latestRows.addAll(latest);
        }
        var bars = byDate.values().stream().map(dateRows -> {
            var latestOrder = dateRows.stream().mapToLong(BarRow::captureOrder).max().orElseThrow();
            return dateRows.stream().filter(row -> row.captureOrder() == latestOrder)
                    .max(Comparator.comparing(BarRow::sourceAsOf)).orElseThrow().bar();
        }).toList();
        var refs = latestRows.stream().map(BarRow::id).distinct().toList();
        var sourceAsOf = latestRows.stream().map(BarRow::sourceAsOf).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        return new History(bars, refs, sourceAsOf, conflicted, List.copyOf(latestRows));
    }

    /**
     * Read-only view of the stored daily bars for {@code symbol} (latest capture per date, oldest first), each with
     * its per-date source conflict flag and latest source as-of. Includes the current session's bar if stored;
     * callers decide which bars count as completed. Never fetches prices or bars.
     */
    public List<StoredDailyBar> storedDailyBars(UUID userId, String symbol) {
        var history = history(userId, ticker(symbol));
        var conflictDates = conflictDates(history);
        var sourceAsOfByDate = history.rows().stream().filter(row -> row.sourceAsOf() != null)
                .collect(java.util.stream.Collectors.toMap(BarRow::date, BarRow::sourceAsOf,
                        (left, right) -> left.isAfter(right) ? left : right));
        return history.bars().stream()
                .map(bar -> new StoredDailyBar(bar.date(), bar.open(), bar.high(), bar.low(), bar.close(),
                        bar.volume(), sourceAsOfByDate.get(bar.date()), conflictDates.contains(bar.date())))
                .toList();
    }

    public record StoredDailyBar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
                                 BigDecimal close, BigDecimal volume, Instant sourceAsOf, boolean sourceConflict) {
    }

    private static java.util.Set<LocalDate> conflictDates(History history) {
        return history.rows().stream().collect(java.util.stream.Collectors.groupingBy(
                BarRow::date, java.util.TreeMap::new, java.util.stream.Collectors.toList())).entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(BarRow::sourceConflict)
                        || entry.getValue().stream().map(BarRow::bar).distinct().count() > 1)
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
    }

    private static List<BenchmarkBar> benchmarkBars(History history) {
        var conflictDates = conflictDates(history);
        return history.bars().stream()
                .map(bar -> new BenchmarkBar(bar.date(), bar.close(), conflictDates.contains(bar.date())))
                .toList();
    }

    private LocalDate contextEffectiveDate(LocalDate snapshotAsOf) {
        var today = LocalDate.now(clock.withZone(SOURCE_ZONE));
        return snapshotAsOf != null && snapshotAsOf.isAfter(today) ? snapshotAsOf : today;
    }

    private List<AvwapAnchor> activeAnchors(UUID userId, String symbol, LocalDate asOf) {
        return activeInputs(userId, "AVWAP_ANCHOR").stream().filter(row -> symbol.equals(row.ticker()))
                .filter(row -> !row.effectiveDate().isAfter(asOf))
                .map(row -> new AvwapAnchor(row.payload().path("anchorId").asString(), row.effectiveDate(),
                        AnchorType.valueOf(row.payload().path("anchorType").asString())))
                .toList();
    }

    private List<UUID> securityInputRefs(UUID userId, String symbol, LocalDate asOf, History history) {
        var refs = new LinkedHashSet<>(history.refs());
        refs.addAll(refsThrough(history(userId, "SPY"), asOf));
        var themeRows = inputRows(userId, "THEME");
        var mappingRows = inputRows(userId, "THEME_MAPPING");
        var activeThemes = themeRowsAt(themeRows, asOf);
        activeInputs(userId, "AVWAP_ANCHOR").stream()
                .filter(row -> symbol.equals(row.ticker()) && !row.effectiveDate().isAfter(asOf))
                .map(InputRow::id).forEach(refs::add);
        var intervals = membershipIntervals(themeRows, mappingRows);
        for (var interval : intervals) {
            if (symbol.equals(interval.ticker()) && interval.contains(asOf)
                    && activeThemes.stream().anyMatch(row -> row.entityKey().equals(interval.themeId()))) {
                refs.addAll(interval.inputRefs());
            }
        }
        activeThemes.stream().filter(row -> intervals.stream().anyMatch(interval ->
                        symbol.equals(interval.ticker()) && interval.themeId().equals(row.entityKey())
                                && interval.contains(asOf)))
                .map(InputRow::id).forEach(refs::add);
        return List.copyOf(refs);
    }

    private static List<UUID> performanceRefs(History history, UUID inputId, LocalDate asOf) {
        var refs = new LinkedHashSet<>(refsThrough(history, asOf));
        refs.add(inputId);
        return List.copyOf(refs);
    }

    private static boolean sourceConflictThrough(History history, LocalDate asOf) {
        if (history == null || asOf == null) return false;
        var rows = history.rows().stream().filter(row -> !row.date().isAfter(asOf)).toList();
        return rows.stream().anyMatch(BarRow::sourceConflict)
                || rows.stream().collect(java.util.stream.Collectors.groupingBy(
                BarRow::date, java.util.stream.Collectors.mapping(BarRow::bar, java.util.stream.Collectors.toSet())))
                .values().stream().anyMatch(bars -> bars.size() > 1);
    }

    private List<PerformanceRow> activePerformanceEntries(UUID userId, String symbol, LocalDate asOf) {
        return activeInputs(userId, "PERFORMANCE_ENTRY").stream().filter(row -> symbol.equals(row.ticker()))
                .filter(row -> !row.effectiveDate().isAfter(asOf))
                .map(row -> {
                    var payload = row.payload();
                    var exits = new ArrayList<Exit>();
                    var exitRows = payload.path("exits");
                    if (exitRows.isArray()) for (var exit : exitRows) {
                        exits.add(new Exit(LocalDate.parse(exit.path("date").asString()),
                                decimal(exit.path("price")), decimal(exit.path("quantity"))));
                    }
                    var entry = new PerformanceEntry(payload.path("key").asString(),
                            LocalDate.parse(payload.path("entryDate").asString()),
                            decimal(payload.path("entryPrice")), decimal(payload.path("initialRiskPrice")),
                            decimal(payload.path("quantity")), exits);
                    return new PerformanceRow(entry, row.id(), row.decisionId(), row.entityKey(), row.source(),
                            row.sourceAsOf(), payload);
                }).toList();
    }

    private PerformanceRow latestActivePerformance(UUID userId, String symbol, LocalDate asOf) {
        var rows = activePerformanceEntries(userId, symbol, asOf);
        return rows.stream().filter(row -> row.decisionId() == null).max(Comparator.comparing(PerformanceRow::key)).orElse(null);
    }

    private String themeFor(UUID userId, String symbol, LocalDate asOf) {
        var activeThemes = themeRowsAt(inputRows(userId, "THEME"), asOf).stream()
                .map(InputRow::entityKey).collect(java.util.stream.Collectors.toSet());
        var current = membershipIntervals(inputRows(userId, "THEME"), inputRows(userId, "THEME_MAPPING")).stream()
                .filter(interval -> symbol.equals(interval.ticker()) && interval.contains(asOf))
                .map(TacticalOverlayAggregationCalculator.MembershipInterval::themeId)
                .filter(activeThemes::contains).distinct().toList();
        return current.size() == 1 ? current.getFirst() : null;
    }

    private List<InputRow> activeInputs(UUID userId, String inputType) {
        var rows = jdbc.query("""
                SELECT id, input_type, entity_key, ticker, decision_id, effective_date, source,
                       source_as_of, active, payload::text, created_at, input_order
                 FROM investment_tactical_overlay_inputs
                 WHERE user_id = ? AND input_type = ?
                 ORDER BY input_order
                """, (rs, row) -> new InputRow(rs.getObject("id", UUID.class), rs.getString("input_type"),
                rs.getString("entity_key"), rs.getString("ticker"), rs.getObject("decision_id", UUID.class),
                rs.getObject("effective_date", LocalDate.class), rs.getString("source"),
                instant(rs.getObject("source_as_of", OffsetDateTime.class)), rs.getBoolean("active"),
                parse(rs.getString("payload")), instant(rs.getObject("created_at", OffsetDateTime.class)),
                rs.getLong("input_order")), userId, inputType);
        var latest = new LinkedHashMap<String, InputRow>();
        for (var row : rows) latest.put(row.entityKey(), row);
        return latest.values().stream().filter(InputRow::active).toList();
    }

    private InputRow latestInput(UUID userId, String type, String key) {
        return jdbc.query("""
                SELECT id, input_type, entity_key, ticker, decision_id, effective_date, source,
                       source_as_of, active, payload::text, created_at, input_order
                 FROM investment_tactical_overlay_inputs
                 WHERE user_id = ? AND input_type = ? AND entity_key = ?
                 ORDER BY input_order DESC LIMIT 1
                """, (rs, row) -> new InputRow(rs.getObject("id", UUID.class), rs.getString("input_type"),
                rs.getString("entity_key"), rs.getString("ticker"), rs.getObject("decision_id", UUID.class),
                rs.getObject("effective_date", LocalDate.class), rs.getString("source"),
                instant(rs.getObject("source_as_of", OffsetDateTime.class)), rs.getBoolean("active"),
                parse(rs.getString("payload")), instant(rs.getObject("created_at", OffsetDateTime.class)),
                rs.getLong("input_order")),
                userId, type, key).stream().findFirst().orElse(null);
    }

    private UUID appendInput(UUID userId, String type, String key, String ticker, UUID decisionId,
                             LocalDate effectiveDate, String source, Instant sourceAsOf, boolean active,
                             JsonNode payload) {
        var id = UUID.randomUUID();
        var now = timestamp(clock.instant());
        jdbc.update("""
                INSERT INTO investment_tactical_overlay_inputs (
                    id, user_id, input_type, entity_key, ticker, decision_id, effective_date, source,
                    source_as_of, active, overlay_version, payload, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TACTICAL_V1', CAST(? AS jsonb), ?)
                """, id, userId, type, key, ticker, decisionId, effectiveDate, source,
                timestamp(sourceAsOf), active, write(payload), now);
        return id;
    }

    private void requireDecisionAsset(UUID userId, UUID decisionId, String ticker) {
        var count = jdbc.queryForObject("""
                SELECT count(*) FROM investment_decision_ledger
                 WHERE user_id = ? AND decision_id = ? AND upper(asset) = ?
                """, Integer.class, userId, decisionId, ticker);
        if (count == null || count != 1) throw new InvestmentException(InvestmentException.Code.NOT_FOUND);
    }

    private void writeSnapshot(UUID userId, String type, String entityKey, String ticker, LocalDate asOf,
                              String source, String status, List<String> reasons, List<UUID> refs,
                              JsonNode payload, Instant sourceAsOf) {
        var reasonArray = mapper.valueToTree(reasons == null ? List.of() : reasons);
        var refArray = mapper.valueToTree(refs == null ? List.of() : refs);
        jdbc.update("""
                INSERT INTO investment_tactical_overlay_snapshots (
                    id, user_id, snapshot_type, entity_key, ticker, as_of, source_as_of, calculated_at,
                    source, overlay_version, status, reasons, input_refs, properties_hash, payload, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'TACTICAL_V1', ?, CAST(? AS jsonb), CAST(? AS jsonb),
                          ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), userId, type, entityKey, ticker, asOf, timestampOrNull(sourceAsOf),
                timestamp(clock.instant()), source, normalizeStatus(status), write(reasonArray), write(refArray),
                propertiesHash(), write(payload), timestamp(clock.instant()));
    }

    private SnapshotRow latestSnapshot(UUID userId, String type, String key) {
        return jdbc.query("""
                SELECT entity_key, ticker, as_of, source_as_of, source, status, reasons::text, payload::text
                  FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type = ? AND entity_key = ? AND overlay_version = 'TACTICAL_V1'
                 ORDER BY snapshot_order DESC LIMIT 1
                """, (rs, row) -> new SnapshotRow(rs.getString("entity_key"), rs.getString("ticker"),
                rs.getObject("as_of", LocalDate.class), instant(rs.getObject("source_as_of", OffsetDateTime.class)),
                rs.getString("source"), rs.getString("status"), parseArray(rs.getString("reasons")),
                rs.getString("payload")), userId, type, key).stream().findFirst().orElse(null);
    }

    private List<UUID> snapshotRefs(UUID userId, String type, String key) {
        return jdbc.query("""
                SELECT input_refs::text FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type = ? AND entity_key = ? AND overlay_version = 'TACTICAL_V1'
                 ORDER BY snapshot_order DESC LIMIT 1
                """, (rs, row) -> parseArray(rs.getString(1)).stream().map(value -> {
                    try { return UUID.fromString(value); }
                    catch (IllegalArgumentException ignored) { return null; }
                }).filter(Objects::nonNull).toList(), userId, type, key).stream().findFirst().orElse(List.of());
    }

    private List<JsonNode> latestThemeSnapshots(UUID userId, LocalDate asOf) {
        if (asOf == null) return List.of();
        var currentThemeIds = themeRowsAt(inputRows(userId, "THEME"), asOf).stream()
                .map(InputRow::entityKey).collect(java.util.stream.Collectors.toSet());
        return jdbc.query("""
                SELECT DISTINCT ON (entity_key) payload::text FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type = 'THEME' AND overlay_version = 'TACTICAL_V1'
                 ORDER BY entity_key, snapshot_order DESC
                """, (rs, row) -> parse(rs.getString(1)), userId).stream()
                .filter(node -> node != null && currentThemeIds.contains(text(node.path("entityKey"))))
                .toList();
    }

    private MarkSnapshot latestMark(UUID userId, String symbol) {
        var rows = jdbc.query("""
                SELECT id, source, latest_price, latest_price_as_of FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = ? AND source = 'TOSS' AND latest_price IS NOT NULL
                   AND latest_price_as_of IS NOT NULL
                 ORDER BY latest_price_as_of DESC, as_of DESC, id DESC LIMIT 1
                """, (rs, row) -> new MarkSnapshot(rs.getObject("id", UUID.class), rs.getString("source"),
                        new Mark(rs.getBigDecimal("latest_price"),
                                instant(rs.getObject("latest_price_as_of", OffsetDateTime.class)))),
                userId, symbol);
        return rows.stream().findFirst().orElse(null);
    }

    private JsonNode currentMarkFreshness(JsonNode stored) {
        if (stored == null || !stored.isObject() || !stored.path("performance").isObject()) return stored;
        var output = (ObjectNode) stored.deepCopy();
        var performance = (ObjectNode) output.path("performance").deepCopy();
        var current = performance.path("currentR");
        if (current.isObject() && current.path("value").isNumber()) {
            var markAsOf = parseInstant(text(output.path("markAsOf")));
            var now = clock.instant();
            var fresh = "TOSS".equals(text(output.path("markSource"))) && markAsOf != null
                    && !markAsOf.isAfter(now)
                    && java.time.Duration.between(markAsOf, now).compareTo(properties.markFreshness()) <= 0;
            if (!fresh) {
                var currentCopy = (ObjectNode) current.deepCopy();
                currentCopy.putNull("value");
                currentCopy.put("status", "STALE");
                performance.set("currentR", currentCopy);
                performance.put("status", "STALE");
                performance.put("reason", markAsOf == null ? "MARK_AS_OF_UNAVAILABLE" : "MARK_STALE");
            }
        }
        output.set("performance", performance);
        return output;
    }

    private JsonNode dailyVwapAliases(JsonNode stored) {
        if (stored == null || !stored.isObject()) return stored == null ? null : stored.deepCopy();
        var output = (ObjectNode) stored.deepCopy();
        if (output.has("indicators")) {
            output.set("indicators", metricAliases(output.path("indicators"),
                    "dailyRolling20Vwap", "dailyVwap20Proxy"));
        }
        if (output.has("anchoredVwaps")) {
            var anchors = metricAliases(output.path("anchoredVwaps"), "value", "anchoredVwap");
            output.set("anchoredVwaps", metricAliases(anchors, "anchoredVwap", "dailyAvwap", "value"));
        }
        return output;
    }

    private JsonNode metricAliases(JsonNode rows, String sourceField, String aliasField) {
        return metricAliases(rows, sourceField, aliasField, null);
    }

    private JsonNode metricAliases(JsonNode rows, String sourceField, String aliasField, String fallbackField) {
        if (rows == null || !rows.isArray()) return rows == null ? null : rows.deepCopy();
        ArrayNode output = mapper.createArrayNode();
        for (var row : rows) {
            if (!(row instanceof ObjectNode object)) {
                output.add(row.deepCopy());
                continue;
            }
            var copy = (ObjectNode) object.deepCopy();
            JsonNode source = copy.get(sourceField);
            if (source == null || source.isNull()) {
                source = fallbackField == null ? copy.get(aliasField) : copy.get(fallbackField);
            }
            if (source != null && !source.isNull()) copy.set(aliasField, source.deepCopy());
            output.add(copy);
        }
        return output;
    }

    private ObjectNode object(Map<String, ?> values) {
        return mapper.valueToTree(values);
    }

    private ObjectNode objectMapperCreate() {
        return mapper.createObjectNode();
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new IllegalStateException("tactical overlay serialization failed", exception);
        }
    }

    private JsonNode parse(String json) {
        if (json == null) return null;
        try {
            return mapper.readTree(json);
        } catch (JacksonException exception) {
            return null;
        }
    }

    private List<String> parseArray(String json) {
        var parsed = parse(json);
        if (parsed == null || !parsed.isArray()) return List.of();
        var values = new ArrayList<String>();
        parsed.forEach(node -> { if (node.isTextual()) values.add(node.asString()); });
        return List.copyOf(values);
    }

    private static ParsedBar parseBar(JsonNode row, Instant capturedAt) {
        if (row == null || !row.isObject() || !"REGULAR_CLOSE".equals(text(row.path("session")))
                || !"USD".equalsIgnoreCase(text(row.path("currency")))) return null;
        try {
            var date = LocalDate.parse(text(row.path("date")));
            var sourceAsOf = Instant.parse(text(row.path("timestamp")));
            if (sourceAsOf.isAfter(capturedAt)) return null;
            var bar = new Bar(date, decimal(row.path("open")), decimal(row.path("high")),
                    decimal(row.path("low")), decimal(row.path("close")), decimal(row.path("volume")));
            return new ParsedBar(bar, sourceAsOf, booleanValue(row.path("sourceConflict")));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() ? null : node.asString();
    }

    private static boolean booleanValue(JsonNode node) {
        return node != null && node.isBoolean() && node.booleanValue();
    }

    private static String optionalString(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        var value = text(node.path(field));
        return value == null || value.isBlank() ? null : value;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node == null || node.isNull() || !node.isNumber() ? null : node.decimalValue();
    }

    private static List<String> parseSymbols(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        var unique = new LinkedHashSet<String>();
        for (var value : raw.split(",")) unique.add(ticker(value));
        unique.remove("SPY");
        return List.copyOf(unique);
    }

    private static String ticker(String raw) {
        if (raw == null) invalid();
        var normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!TICKER.matcher(normalized).matches()) invalid();
        return normalized;
    }

    private static void requireKey(String value, String name) {
        if (value == null || !INPUT_KEY.matcher(value).matches()) invalid();
    }

    private static void validateEffect(String effect) {
        if (effect == null) return;
        try {
            OverlayEffect.valueOf(effect);
        } catch (IllegalArgumentException exception) {
            invalid();
        }
    }

    private static String source(String source) {
        var value = source == null || source.isBlank() ? "USER_INPUT" : source.trim();
        if (value.length() > 80) invalid();
        return value;
    }

    private static String mappingKey(String theme, String ticker, LocalDate date) {
        return theme + ":" + ticker + ":" + date;
    }

    private Instant requiredInstant(Instant instant) {
        if (instant == null || instant.isAfter(clock.instant())) invalid();
        return instant;
    }

    private static String normalizeStatus(String status) {
        return status == null ? "UNVERIFIED" : status.toUpperCase(Locale.ROOT);
    }

    private String propertiesHash() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((VERSION + properties).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireUser(UUID userId) {
        if (userId == null || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)", Boolean.class, userId))) {
            throw new InvestmentException(InvestmentException.Code.INVALID_USER);
        }
    }

    private static void invalid() {
        throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime timestampOrNull(Instant value) {
        return timestamp(value);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record ParsedBar(Bar bar, Instant sourceAsOf, boolean sourceConflict) { }
    private record BarRow(UUID id, LocalDate date, Instant sourceAsOf, Instant capturedAt,
                          long captureOrder, boolean sourceConflict, BigDecimal open, BigDecimal high,
                          BigDecimal low, BigDecimal close, BigDecimal volume) {
        Bar bar() { return new Bar(date, open, high, low, close, volume); }
    }
    private record MarkSnapshot(UUID id, String source, Mark mark) { }
    private record History(List<Bar> bars, List<UUID> refs, Instant latestSourceAsOf,
                           boolean sourceConflict, List<BarRow> rows) { }
    private record InputRow(UUID id, String type, String entityKey, String ticker, UUID decisionId,
                            LocalDate effectiveDate, String source, Instant sourceAsOf, boolean active,
                            JsonNode payload, Instant createdAt, long order) { }
    private record DateInterval(LocalDate from, LocalDate to, InputRow row, InputRow endRow) { }
    private record PerformanceRow(PerformanceEntry entry, UUID id, UUID decisionId, String key,
                                  String source, Instant sourceAsOf, JsonNode payload) { }
    private record AggregatePayload(LocalDate date, Instant sourceAsOf, String source, String status,
                                    List<String> reasons, List<UUID> inputRefs, JsonNode node) { }
    private record SnapshotRow(String entityKey, String ticker, LocalDate asOf, Instant sourceAsOf,
                               String source, String status, List<String> reasons, String payload) { }

    public record AnchorInput(String anchorId, LocalDate date, AnchorType anchorType, String source, Instant sourceAsOf) { }
    public record ThemeInput(String themeId, String name, LocalDate effectiveDate, String source, Instant sourceAsOf) { }
    public record ThemeMappingInput(String themeId, String ticker, LocalDate effectiveDate,
                                    String source, Instant sourceAsOf) { }
    public record ExitInput(LocalDate date, BigDecimal price, BigDecimal quantity) { }
    public record PerformanceInput(String key, UUID decisionId, LocalDate entryDate, BigDecimal entryPrice,
                                  BigDecimal initialRiskPrice, BigDecimal quantity, String entrySetup,
                                  String overlayEffect, List<ExitInput> exits, String source, Instant sourceAsOf) { }
    public record TacticalInputView(UUID id, String inputType, String entityKey, String ticker, UUID decisionId,
                                    LocalDate effectiveDate, String source, Instant sourceAsOf, boolean active,
                                    String overlayVersion, JsonNode payload, Instant createdAt) { }
    public record TacticalInputsView(String overlayVersion, List<TacticalInputView> items) { }
    public record ReadModel(
            com.jmj.trade.investment.InvestmentContextService.TacticalOverlayPortfolioView portfolio,
            Map<String, com.jmj.trade.investment.InvestmentContextService.SecurityTacticalOverlayView> securities,
            Map<UUID, com.jmj.trade.investment.InvestmentContextService.DecisionTacticalOverlayView> decisions
    ) { }

    public enum OverlayEffect {
        BETTER_ENTRY, AVOIDED_CHASE, AVOIDED_FAILED_BREAKOUT, PREMATURE_FILTER, NO_EFFECT
    }
}
