package com.jmj.trade.investment;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Stores review assertions and only trusts reviews produced by a server-managed reviewer. */
@Service
public final class InvestmentStrategicBuyRevalidationService {
    private static final Duration MAX_REVIEW_TTL = Duration.ofMinutes(30);
    private static final Duration MAX_SOURCE_AGE = Duration.ofDays(120);
    private static final Set<String> SOURCE_TYPES = Set.of(
            "THESIS", "FUNDAMENTAL", "REVISION", "EXPECTATIONS", "RISK");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final InvestmentContextService context;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public InvestmentStrategicBuyRevalidationService(
            JdbcTemplate jdbc, ObjectMapper mapper, InvestmentContextService context,
            PlatformTransactionManager transactionManager) {
        this(jdbc, mapper, context, transactionManager, Clock.systemUTC());
    }

    InvestmentStrategicBuyRevalidationService(
            JdbcTemplate jdbc, ObjectMapper mapper, InvestmentContextService context,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.context = context;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    RevalidationView record(UUID authenticatedUserId, UUID authenticatedApiKeyId, RevalidationInput rawInput) {
        if (authenticatedUserId == null) throw new InvestmentException(InvestmentException.Code.INVALID_USER);
        var input = normalize(rawInput, clock.instant());
        var bodyHash = hash(input);
        try {
            return transaction.execute(ignored -> {
                var existing = find(input.reviewId());
                if (existing != null) {
                    if (!authenticatedUserId.equals(existing.userId()) || !bodyHash.equals(existing.bodyHash()))
                        throw conflict();
                    return existing;
                }

                var decision = decision(authenticatedUserId, input.decisionId());
                if (decision == null || !input.ticker().equals(decision.ticker())
                        || !"ADD".equals(decision.action())) throw invalid();
                var mandate = context.positionMandates(authenticatedUserId).stream()
                        .filter(item -> input.ticker().equals(item.ticker())).findFirst().orElse(null);
                if (mandate == null || !"STRATEGIC".equals(mandate.investmentHorizon())) throw invalid();
                var thesis = context.currentThesis(authenticatedUserId, input.ticker());
                if (thesis == null || thesis.revisionId() == null
                        || !input.thesisRevisionId().equals(thesis.revisionId())
                        || !"CONFIRMED".equals(thesis.invalidationStatus())) throw invalid();

                var gate = context.investmentBuyRiskGate(authenticatedUserId, input.ticker(), null, null);
                if (gate == null || !"STRATEGIC".equals(gate.investmentHorizon())
                        || !"REVIEW_REQUIRED".equals(gate.status())
                        || !"OK".equals(gate.marketDataStatus())) throw invalid();
                var facts = context.priceFacts(authenticatedUserId, input.ticker());
                var mark = facts.riskMark(clock.instant());
                if (!mark.available() || mark.value() == null || mark.asOf() == null
                        || mark.value().compareTo(input.minPrice()) < 0
                        || mark.value().compareTo(input.maxPrice()) > 0) throw invalid();

                // Callers can submit review claims, never a trusted approval verdict.
                var verdict = "UNTRUSTED";
                var now = clock.instant();
                jdbc.update("""
                        INSERT INTO investment_strategic_buy_revalidations (
                            review_id, user_id, decision_id, ticker, investment_horizon, mandate_updated_at,
                            thesis_revision_id, verdict, review_origin, provider, model, rationale, source_as_of, sources,
                            assessment, reviewed_market_price, market_observed_at, market_health,
                            price_risk_state, min_price, max_price, max_quantity, expires_at,
                            authenticated_actor_user_id, authenticated_actor_key_id, body_hash, created_at
                        ) VALUES (?, ?, ?, ?, 'STRATEGIC', ?, ?, ?, 'CALLER_ASSERTED', ?, ?, ?, ?, CAST(? AS jsonb),
                                  CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, input.reviewId(), authenticatedUserId, input.decisionId(), input.ticker(),
                        timestamp(mandate.updatedAt()), thesis.revisionId(), verdict, input.provider(), input.model(),
                        input.rationale(), timestamp(input.sourceAsOf()), json(input.sources()),
                        json(input.assessment()), mark.value(), timestamp(mark.asOf()), gate.marketHealth(),
                        gate.priceRiskState(), input.minPrice(), input.maxPrice(), input.maxQuantity(),
                        timestamp(input.expiresAt()), authenticatedUserId, authenticatedApiKeyId, bodyHash,
                        timestamp(now));
                var persisted = find(input.reviewId());
                if (persisted == null || !authenticatedUserId.equals(persisted.userId())
                        || !bodyHash.equals(persisted.bodyHash())) throw new IllegalStateException("Revalidation was not persisted");
                return persisted;
            });
        } catch (DuplicateKeyException duplicate) {
            var existing = find(input.reviewId());
            if (existing == null || !authenticatedUserId.equals(existing.userId())
                    || !bodyHash.equals(existing.bodyHash())) throw conflict();
            return existing;
        }
    }

    /** Rechecks the approval against current mandate, thesis revision, market state, price and quantity. */
    public Validation validateForSubmit(UUID userId, UUID decisionId, String rawTicker,
                                        BigDecimal quantity, BigDecimal currentMarketPrice,
                                        BigDecimal orderLimitPrice,
                                        InvestmentBuyRiskGate.Decision currentGate) {
        var ticker = InvestmentContextService.ticker(rawTicker);
        var latest = latest(userId, decisionId);
        if (latest == null) return Validation.blocked("STRATEGIC_REVALIDATION_MISSING");
        if (!"SERVER_GENERATED".equals(latest.reviewOrigin()))
            return Validation.blocked("STRATEGIC_REVALIDATION_UNTRUSTED");
        var now = clock.instant();
        if (!"PASS".equals(latest.verdict())) return Validation.blocked("STRATEGIC_REVALIDATION_NOT_PASSED");
        if (latest.expiresAt() == null || !now.isBefore(latest.expiresAt()))
            return Validation.blocked("STRATEGIC_REVALIDATION_EXPIRED");
        if (latest.sourceAsOf() == null || latest.sourceAsOf().isAfter(now)
                || latest.sourceAsOf().isBefore(now.minus(MAX_SOURCE_AGE)))
            return Validation.blocked("STRATEGIC_REVALIDATION_EVIDENCE_STALE");
        var decision = decision(userId, decisionId);
        if (decision == null || !ticker.equals(decision.ticker()) || !"ADD".equals(decision.action()))
            return Validation.blocked("STRATEGIC_DECISION_CHANGED");
        var mandate = context.positionMandates(userId).stream()
                .filter(item -> ticker.equals(item.ticker())).findFirst().orElse(null);
        if (mandate == null || !"STRATEGIC".equals(mandate.investmentHorizon())
                || !mandate.updatedAt().equals(latest.mandateUpdatedAt()))
            return Validation.blocked("POSITION_MANDATE_CHANGED");
        var thesis = context.currentThesis(userId, ticker);
        if (thesis == null || !"CONFIRMED".equals(thesis.invalidationStatus())
                || !latest.thesisRevisionId().equals(thesis.revisionId()))
            return Validation.blocked("THESIS_REVISION_CHANGED");
        if (currentGate == null || !"STRATEGIC".equals(currentGate.investmentHorizon())
                || !"REVIEW_REQUIRED".equals(currentGate.status())
                || !"OK".equals(currentGate.marketDataStatus()))
            return Validation.blocked("STRATEGIC_REVIEW_STATE_CHANGED");
        if (!latest.marketHealth().equals(currentGate.marketHealth())
                || !latest.priceRiskState().equals(currentGate.priceRiskState()))
            return Validation.blocked("STRATEGIC_MARKET_STATE_CHANGED");
        if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(latest.maxQuantity()) > 0)
            return Validation.blocked("STRATEGIC_APPROVED_QUANTITY_EXCEEDED");
        if (currentMarketPrice == null || orderLimitPrice == null
                || currentMarketPrice.compareTo(latest.minPrice()) < 0
                || currentMarketPrice.compareTo(latest.maxPrice()) > 0
                || orderLimitPrice.compareTo(latest.minPrice()) < 0
                || orderLimitPrice.compareTo(latest.maxPrice()) > 0)
            return Validation.blocked("STRATEGIC_APPROVED_PRICE_RANGE_EXCEEDED");
        return new Validation(true, "STRATEGIC_REVALIDATION_PASS", latest.reviewId());
    }

    private RevalidationInput normalize(RevalidationInput input, Instant now) {
        if (input == null || input.reviewId() == null || input.decisionId() == null
                || input.thesisRevisionId() == null || input.sourceAsOf() == null || input.expiresAt() == null
                || input.sourceAsOf().isAfter(now) || input.sourceAsOf().isBefore(now.minus(MAX_SOURCE_AGE))
                || !input.expiresAt().isAfter(now) || input.expiresAt().isAfter(now.plus(MAX_REVIEW_TTL))
                || input.maxQuantity() == null || input.maxQuantity().signum() <= 0
                || input.minPrice() == null || input.minPrice().signum() <= 0
                || input.maxPrice() == null || input.maxPrice().compareTo(input.minPrice()) < 0
                || input.sources() == null || input.assessment() == null) throw invalid();
        var ticker = InvestmentContextService.ticker(input.ticker());
        var provider = bounded(input.provider(), 120);
        var model = bounded(input.model(), 160);
        var rationale = bounded(input.rationale(), 4000);
        if (input.sources().size() < 2 || input.sources().size() > 10) throw invalid();
        var sources = new ArrayList<EvidenceSource>();
        for (var source : input.sources()) {
            if (source == null || source.asOf() == null || source.asOf().isAfter(now)
                    || source.asOf().isBefore(now.minus(MAX_SOURCE_AGE))) throw invalid();
            var type = bounded(source.type(), 24).toUpperCase(Locale.ROOT);
            if (!SOURCE_TYPES.contains(type)) throw invalid();
            sources.add(new EvidenceSource(validateHttpsUrl(source.url()), source.asOf(), type));
        }
        if (sources.stream().map(EvidenceSource::url).distinct().count() != sources.size()
                || !sources.stream().map(EvidenceSource::type).collect(java.util.stream.Collectors.toSet())
                .containsAll(SOURCE_TYPES)) throw invalid();
        var normalizedAssessment = normalize(input.assessment());
        return new RevalidationInput(input.reviewId(), input.decisionId(), ticker, input.thesisRevisionId(),
                input.sourceAsOf(), input.expiresAt(), provider, model, rationale, List.copyOf(sources),
                normalizedAssessment, input.minPrice(), input.maxPrice(), input.maxQuantity());
    }

    private static Assessment normalize(Assessment assessment) {
        return new Assessment(
                criterion(assessment.thesisHealth(), "INTACT", "IMPAIRED", "INVALIDATED", "NOT_EVALUABLE"),
                criterion(assessment.fundamentalRevision(), "SUPPORTED", "CONTRADICTED", "NOT_EVALUABLE"),
                criterion(assessment.expectationsGap(), "OPPORTUNITY", "NO_OPPORTUNITY", "NOT_EVALUABLE"),
                criterion(assessment.riskReward(), "FAVORABLE", "UNFAVORABLE", "NOT_EVALUABLE"),
                criterion(assessment.hardRisk(), "PASS", "FAIL", "NOT_EVALUABLE"),
                criterion(assessment.thesisInvalidation(), "CLEAR", "TRIGGERED", "NOT_EVALUABLE"));
    }

    private static Criterion criterion(Criterion value, String... statuses) {
        if (value == null || value.status() == null || value.rationale() == null || value.rationale().isBlank())
            throw invalid();
        var status = value.status().trim().toUpperCase(Locale.ROOT);
        if (!Set.of(statuses).contains(status)) throw invalid();
        return new Criterion(status, bounded(value.rationale(), 1000));
    }

    private static String validateHttpsUrl(String value) {
        var url = bounded(value, 2000);
        try {
            var uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().isBlank() || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
                throw invalid();
            return url;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw invalid();
        return value.trim();
    }

    private DecisionTarget decision(UUID userId, UUID decisionId) {
        return jdbc.query("""
                SELECT asset, action FROM investment_decision_ledger
                 WHERE user_id = ? AND decision_id = ?
                """, (rs, row) -> new DecisionTarget(rs.getString("asset"), rs.getString("action")),
                userId, decisionId).stream().findFirst().orElse(null);
    }

    private RevalidationView find(UUID reviewId) {
        return jdbc.query("""
                SELECT review_id, user_id, decision_id, ticker, investment_horizon, mandate_updated_at,
                       thesis_revision_id, verdict, review_origin, provider, model, rationale, source_as_of,
                       sources::text, assessment::text, reviewed_market_price, market_observed_at,
                       market_health, price_risk_state, min_price, max_price, max_quantity, expires_at,
                       body_hash, created_at
                  FROM investment_strategic_buy_revalidations WHERE review_id = ?
                """, viewRow(), reviewId).stream().findFirst().orElse(null);
    }

    private RevalidationView latest(UUID userId, UUID decisionId) {
        if (userId == null || decisionId == null) return null;
        return jdbc.query("""
                SELECT review_id, user_id, decision_id, ticker, investment_horizon, mandate_updated_at,
                       thesis_revision_id, verdict, review_origin, provider, model, rationale, source_as_of,
                       sources::text, assessment::text, reviewed_market_price, market_observed_at,
                       market_health, price_risk_state, min_price, max_price, max_quantity, expires_at,
                       body_hash, created_at
                  FROM investment_strategic_buy_revalidations
                 WHERE user_id = ? AND decision_id = ?
                 ORDER BY created_at DESC, review_id DESC LIMIT 1
                """, viewRow(), userId, decisionId).stream().findFirst().orElse(null);
    }

    private RowMapper<RevalidationView> viewRow() {
        return (rs, row) -> new RevalidationView(
                rs.getObject("review_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("decision_id", UUID.class), rs.getString("ticker"), rs.getString("investment_horizon"),
                instant(rs.getObject("mandate_updated_at", OffsetDateTime.class)),
                rs.getObject("thesis_revision_id", UUID.class), rs.getString("verdict"), rs.getString("review_origin"),
                rs.getString("provider"),
                rs.getString("model"), rs.getString("rationale"), instant(rs.getObject("source_as_of", OffsetDateTime.class)),
                readJson(rs.getString("sources")), readJson(rs.getString("assessment")),
                rs.getBigDecimal("reviewed_market_price"), instant(rs.getObject("market_observed_at", OffsetDateTime.class)),
                rs.getString("market_health"), rs.getString("price_risk_state"), rs.getBigDecimal("min_price"),
                rs.getBigDecimal("max_price"), rs.getBigDecimal("max_quantity"),
                instant(rs.getObject("expires_at", OffsetDateTime.class)), rs.getString("body_hash"),
                instant(rs.getObject("created_at", OffsetDateTime.class)));
    }

    private JsonNode readJson(String value) {
        try { return mapper.readTree(value); }
        catch (tools.jackson.core.JacksonException exception) { throw new IllegalStateException(exception); }
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private String hash(RevalidationInput input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(input)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static OffsetDateTime timestamp(Instant instant) { return instant.atOffset(java.time.ZoneOffset.UTC); }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static InvestmentException invalid() { return new InvestmentException(InvestmentException.Code.INVALID_INPUT); }
    private static InvestmentException conflict() { return new InvestmentException(InvestmentException.Code.CONFLICT); }

    private record DecisionTarget(String ticker, String action) {}

    public record EvidenceSource(String url, Instant asOf, String type) {}
    public record Criterion(String status, String rationale) {}
    public record Assessment(Criterion thesisHealth, Criterion fundamentalRevision, Criterion expectationsGap,
                             Criterion riskReward, Criterion hardRisk, Criterion thesisInvalidation) {
        boolean passes() {
            return "INTACT".equals(thesisHealth.status()) && "SUPPORTED".equals(fundamentalRevision.status())
                    && "OPPORTUNITY".equals(expectationsGap.status()) && "FAVORABLE".equals(riskReward.status())
                    && "PASS".equals(hardRisk.status()) && "CLEAR".equals(thesisInvalidation.status());
        }
    }

    public record RevalidationInput(UUID reviewId, UUID decisionId, String ticker, UUID thesisRevisionId,
                                    Instant sourceAsOf, Instant expiresAt, String provider, String model,
                                    String rationale, List<EvidenceSource> sources, Assessment assessment,
                                    BigDecimal minPrice, BigDecimal maxPrice, BigDecimal maxQuantity) {}

    public record RevalidationView(UUID reviewId, UUID userId, UUID decisionId, String ticker,
                                  String investmentHorizon, Instant mandateUpdatedAt, UUID thesisRevisionId,
                                  String verdict, String reviewOrigin, String provider, String model, String rationale,
                                  Instant sourceAsOf, JsonNode sources, JsonNode assessment,
                                  BigDecimal reviewedMarketPrice, Instant marketObservedAt,
                                  String marketHealth, String priceRiskState,
                                  BigDecimal minPrice, BigDecimal maxPrice, BigDecimal maxQuantity,
                                  Instant expiresAt, String bodyHash, Instant createdAt) {}

    public record Validation(boolean allowed, String reason, UUID reviewId) {
        static Validation blocked(String reason) { return new Validation(false, reason, null); }
    }
}
