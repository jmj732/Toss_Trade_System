package com.jmj.trade.investment;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stores caller assertions separately from ChatGPT MCP review runs and server-managed reviews. */
@Service
public final class InvestmentStrategicBuyRevalidationService {
    private static final Duration MAX_REVIEW_TTL = Duration.ofMinutes(30);
    private static final Duration REVIEW_RUN_TTL = Duration.ofMinutes(15);
    private static final Duration MAX_SOURCE_AGE = Duration.ofDays(120);
    private static final Set<String> SOURCE_TYPES = Set.of(
            "THESIS", "FUNDAMENTAL", "REVISION", "EXPECTATIONS", "RISK");
    private static final Set<String> FILING_FORMS = Set.of("10-K", "10-Q", "20-F", "40-F");
    private static final Set<String> SEC_HOSTS = Set.of("sec.gov", "www.sec.gov");
    private static final Pattern ACCESSION_NUMBER = Pattern.compile("^\\d{10}-\\d{2}-\\d{6}$");
    private static final BigDecimal SHARES_TOLERANCE = new BigDecimal("0.15");
    /** Every EXTERNAL_CITED review is stored with this status: cited content is not verified by the server. */
    public static final String VERIFICATION_SOURCE_NOT_VERIFIED = "SOURCE_NOT_VERIFIED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final InvestmentContextService context;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Value("${investment.data.fundamental-source-policy:PROVIDERS}")
    private String fundamentalSourcePolicy = InvestmentBuyRiskGate.POLICY_PROVIDERS;
    @Value("${investment.data.fundamental-stale-after:P210D}")
    private Duration fundamentalStaleAfter = Duration.ofDays(210);
    @Value("${investment.data.consensus-stale-after:P10D}")
    private Duration consensusStaleAfter = Duration.ofDays(10);
    @Value("${investment.revalidation.external-evidence-max-ttl:PT15M}")
    private Duration externalMaxTtl = Duration.ofMinutes(15);
    @Value("${investment.revalidation.external-evidence-max-notional:1000}")
    private BigDecimal externalMaxNotional = BigDecimal.valueOf(1000);
    @Value("${investment.revalidation.external-evidence-domains:nasdaq.com,zacks.com,marketbeat.com,finance.yahoo.com,stockanalysis.com,tipranks.com}")
    private List<String> externalDomains = List.of("nasdaq.com", "zacks.com", "marketbeat.com",
            "finance.yahoo.com", "stockanalysis.com", "tipranks.com");

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

    public ReviewRun beginChatGptRun(UUID authenticatedUserId, UUID connectionId,
                                     UUID authenticatedApiKeyId, UUID decisionId) {
        if (authenticatedUserId == null || connectionId == null || authenticatedApiKeyId == null
                || decisionId == null) throw invalid();
        var target = decision(authenticatedUserId, decisionId);
        if (target == null || !"ADD".equals(target.action())) throw invalid();
        var ticker = InvestmentContextService.ticker(target.ticker());
        var mandate = context.positionMandates(authenticatedUserId).stream()
                .filter(item -> ticker.equals(item.ticker())).findFirst().orElse(null);
        var thesis = context.currentThesis(authenticatedUserId, ticker);
        var gate = context.investmentBuyRiskGate(authenticatedUserId, ticker, null, null);
        var mark = context.priceFacts(authenticatedUserId, ticker).riskMark(clock.instant());
        if (mandate == null || !"STRATEGIC".equals(mandate.investmentHorizon())
                || thesis == null || thesis.revisionId() == null
                || !"CONFIRMED".equals(thesis.invalidationStatus())
                || gate == null || !"STRATEGIC".equals(gate.investmentHorizon())
                || !"REVIEW_REQUIRED".equals(gate.status()) || !"OK".equals(gate.marketDataStatus())
                || gate.marketHealth() == null || gate.priceRiskState() == null
                || !expectedEvidenceMode().equals(gate.evidenceMode())
                || !mark.available() || mark.value() == null || mark.asOf() == null) throw invalid();

        var now = clock.instant();
        var evidenceMode = gate.evidenceMode();
        var runId = UUID.randomUUID();
        var challenge = UUID.randomUUID() + UUID.randomUUID().toString();
        var expiresAt = now.plus(runTtl(evidenceMode));
        var legacy = gate.fundamentalVerification() == null ? List.<InvestmentBuyRiskGate.LegacyProviderData>of()
                : gate.fundamentalVerification().legacyProviderData();
        var view = new ReviewRun(runId, challenge, decisionId, ticker, thesis.revisionId(),
                mark.value(), mark.asOf(), gate.marketHealth(), gate.priceRiskState(), expiresAt, evidenceMode,
                legacy);
        transaction.execute(ignored -> {
            jdbc.update("""
                    INSERT INTO investment_strategic_buy_revalidation_runs (
                        run_id, user_id, connection_id, api_key_id, decision_id, ticker, thesis_revision_id,
                        mandate_updated_at, reviewed_market_price, market_observed_at, market_health,
                        price_risk_state, challenge_hash, status, created_at, expires_at, evidence_mode
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)
                    """, runId, authenticatedUserId, connectionId, authenticatedApiKeyId, decisionId, ticker,
                    thesis.revisionId(), timestamp(mandate.updatedAt()), mark.value(), timestamp(mark.asOf()),
                    gate.marketHealth(), gate.priceRiskState(), hashSecret(challenge), timestamp(now),
                    timestamp(expiresAt), evidenceMode);
            return null;
        });
        return view;
    }

    public RevalidationView completeChatGptRun(UUID authenticatedUserId, UUID connectionId,
                                                UUID authenticatedApiKeyId, RunCompletion completion) {
        if (authenticatedUserId == null || connectionId == null || authenticatedApiKeyId == null
                || completion == null || completion.runId() == null) throw invalid();
        var now = clock.instant();
        return transaction.execute(ignored -> {
            var run = findRunForUpdate(completion.runId());
            if (run == null || !authenticatedUserId.equals(run.userId())
                    || !connectionId.equals(run.connectionId())
                    || !authenticatedApiKeyId.equals(run.apiKeyId())
                    || !"OPEN".equals(run.status()) || !run.expiresAt().isAfter(now)
                    || !secureEquals(run.challengeHash(), hashSecret(completion.challenge()))) throw conflict();

            var decision = decision(authenticatedUserId, run.decisionId());
            var mandate = context.positionMandates(authenticatedUserId).stream()
                    .filter(item -> run.ticker().equals(item.ticker())).findFirst().orElse(null);
            var thesis = context.currentThesis(authenticatedUserId, run.ticker());
            var gate = context.investmentBuyRiskGate(authenticatedUserId, run.ticker(), null, null);
            var mark = context.priceFacts(authenticatedUserId, run.ticker()).riskMark(now);
            if (decision == null || !"ADD".equals(decision.action()) || !run.ticker().equals(decision.ticker())
                    || mandate == null || !"STRATEGIC".equals(mandate.investmentHorizon())
                    || !timestamp(mandate.updatedAt()).equals(timestamp(run.mandateUpdatedAt()))
                    || thesis == null || !"CONFIRMED".equals(thesis.invalidationStatus())
                    || !run.thesisRevisionId().equals(thesis.revisionId())
                    || gate == null || !"STRATEGIC".equals(gate.investmentHorizon())
                    || !"REVIEW_REQUIRED".equals(gate.status()) || !"OK".equals(gate.marketDataStatus())
                    || !run.marketHealth().equals(gate.marketHealth())
                    || !run.priceRiskState().equals(gate.priceRiskState())
                    || !run.evidenceMode().equals(gate.evidenceMode())
                    || !expectedEvidenceMode().equals(gate.evidenceMode())
                    || !mark.available() || mark.value() == null || mark.asOf() == null) throw invalid();

            var external = InvestmentBuyRiskGate.EVIDENCE_EXTERNAL_CITED.equals(run.evidenceMode());
            if (external != (completion.structuredEvidence() != null)) throw invalid();
            var input = new RevalidationInput(run.runId(), run.decisionId(), run.ticker(),
                    run.thesisRevisionId(), completion.sourceAsOf(), run.expiresAt(), "ChatGPT", "UNATTESTED",
                    completion.rationale(), completion.sources(), completion.assessment(), completion.minPrice(),
                    completion.maxPrice(), completion.maxQuantity());
            var normalized = normalize(input, now);
            if (normalized.minPrice().compareTo(run.reviewedMarketPrice()) > 0
                    || normalized.maxPrice().compareTo(run.reviewedMarketPrice()) < 0
                    || normalized.minPrice().compareTo(mark.value()) > 0
                    || normalized.maxPrice().compareTo(mark.value()) < 0) throw invalid();

            // Structural violations reject the request; consistency and identity failures are stored as BLOCKED.
            var verdictReasons = new ArrayList<String>();
            StructuredEvidence evidence = null;
            if (external) {
                evidence = validateStructuredEvidence(completion.structuredEvidence(), now);
                if (normalized.expiresAt().isAfter(now.plus(externalMaxTtl))) throw invalid();
                if ("SUPPORTED".equals(normalized.assessment().fundamentalRevision().status())
                        && evidence.revision().eps90dChangePct().signum() < 0) {
                    verdictReasons.add("REVISION_CONTRADICTS_ASSESSMENT");
                }
                if (!sharesMatchToss(evidence.fundamental().sharesOutstanding(),
                        context.currentTossStockHazards(authenticatedUserId, run.ticker()))) {
                    verdictReasons.add("EVIDENCE_IDENTITY_MISMATCH");
                }
            }
            var bodyHash = external
                    ? hashValue(new ExternalReviewBody(normalized, run.evidenceMode(), evidence, verdictReasons))
                    : hash(normalized);
            var prior = find(normalized.reviewId());
            if (prior != null) {
                if (!authenticatedUserId.equals(prior.userId()) || !bodyHash.equals(prior.bodyHash())) throw conflict();
                return prior;
            }

            var verdict = normalized.assessment().passes() && verdictReasons.isEmpty() ? "PASS" : "BLOCKED";
            jdbc.update("""
                    INSERT INTO investment_strategic_buy_revalidations (
                        review_id, run_id, user_id, decision_id, ticker, investment_horizon, mandate_updated_at,
                        thesis_revision_id, verdict, review_origin, provider, model, rationale, source_as_of, sources,
                        assessment, reviewed_market_price, market_observed_at, market_health, price_risk_state,
                        min_price, max_price, max_quantity, expires_at, authenticated_actor_user_id,
                        authenticated_actor_key_id, body_hash, created_at, evidence_mode, structured_evidence,
                        verdict_reasons, verification_status
                    ) VALUES (?, ?, ?, ?, ?, 'STRATEGIC', ?, ?, ?, 'CHATGPT_MCP', ?, ?, ?, ?, CAST(? AS jsonb),
                              CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                    """, normalized.reviewId(), run.runId(), authenticatedUserId, run.decisionId(), run.ticker(),
                    timestamp(mandate.updatedAt()), run.thesisRevisionId(), verdict, normalized.provider(),
                    normalized.model(), normalized.rationale(), timestamp(normalized.sourceAsOf()),
                    json(normalized.sources()), json(normalized.assessment()), mark.value(), timestamp(mark.asOf()),
                    gate.marketHealth(), gate.priceRiskState(), normalized.minPrice(), normalized.maxPrice(),
                    normalized.maxQuantity(), timestamp(run.expiresAt()), authenticatedUserId, authenticatedApiKeyId,
                    bodyHash, timestamp(now), run.evidenceMode(), evidence == null ? null : json(evidence),
                    json(verdictReasons), evidence == null ? null : VERIFICATION_SOURCE_NOT_VERIFIED);
            jdbc.update("""
                    UPDATE investment_strategic_buy_revalidation_runs
                       SET status = 'COMPLETED', completed_at = ?
                     WHERE run_id = ? AND status = 'OPEN'
                    """, timestamp(now), run.runId());
            return find(normalized.reviewId());
        });
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
        // CHATGPT_MCP proves a completed, bound MCP flow only. It does not attest task identity or source grounding.
        if (!"SERVER_GENERATED".equals(latest.reviewOrigin()))
            if (!"CHATGPT_MCP".equals(latest.reviewOrigin()) || latest.runId() == null
                    || !completedRun(userId, latest.runId()))
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
        var evidenceMode = evidenceModeOf(latest.evidenceMode());
        if (!evidenceMode.equals(evidenceModeOf(currentGate.evidenceMode())))
            return Validation.blocked("STRATEGIC_EVIDENCE_MODE_CHANGED");
        if (InvestmentBuyRiskGate.EVIDENCE_EXTERNAL_CITED.equals(evidenceMode)) {
            if (!InvestmentBuyRiskGate.POLICY_TOSS_ONLY.equals(
                    InvestmentBuyRiskGate.parsePolicy(fundamentalSourcePolicy)))
                return Validation.blocked("STRATEGIC_EXTERNAL_EVIDENCE_POLICY_CHANGED");
            if (latest.createdAt() == null || latest.expiresAt() == null
                    || Duration.between(latest.createdAt(), latest.expiresAt()).compareTo(externalMaxTtl) > 0)
                return Validation.blocked("STRATEGIC_EXTERNAL_EVIDENCE_TTL_EXCEEDED");
        }
        if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(latest.maxQuantity()) > 0)
            return Validation.blocked("STRATEGIC_APPROVED_QUANTITY_EXCEEDED");
        if (currentMarketPrice == null || orderLimitPrice == null
                || currentMarketPrice.compareTo(latest.minPrice()) < 0
                || currentMarketPrice.compareTo(latest.maxPrice()) > 0
                || orderLimitPrice.compareTo(latest.minPrice()) < 0
                || orderLimitPrice.compareTo(latest.maxPrice()) > 0)
            return Validation.blocked("STRATEGIC_APPROVED_PRICE_RANGE_EXCEEDED");
        if (InvestmentBuyRiskGate.EVIDENCE_EXTERNAL_CITED.equals(evidenceMode)
                && orderLimitPrice.multiply(quantity).compareTo(externalMaxNotional) > 0)
            return Validation.blocked("STRATEGIC_EXTERNAL_EVIDENCE_NOTIONAL_EXCEEDED");
        return new Validation(true, "STRATEGIC_REVALIDATION_PASS", latest.reviewId(), latest.verificationStatus());
    }

    private String expectedEvidenceMode() {
        return InvestmentBuyRiskGate.POLICY_TOSS_ONLY.equals(InvestmentBuyRiskGate.parsePolicy(fundamentalSourcePolicy))
                ? InvestmentBuyRiskGate.EVIDENCE_EXTERNAL_CITED : InvestmentBuyRiskGate.POLICY_PROVIDERS;
    }

    private Duration runTtl(String evidenceMode) {
        return InvestmentBuyRiskGate.EVIDENCE_EXTERNAL_CITED.equals(evidenceMode)
                ? REVIEW_RUN_TTL.compareTo(externalMaxTtl) <= 0 ? REVIEW_RUN_TTL : externalMaxTtl
                : REVIEW_RUN_TTL;
    }

    /** Structural checks for EXTERNAL_CITED evidence. Any violation is an invalid request, never a stored PASS. */
    private StructuredEvidence validateStructuredEvidence(StructuredEvidence evidence, Instant now) {
        if (evidence == null || evidence.fundamental() == null || evidence.consensus() == null
                || evidence.revision() == null) throw invalid();
        var fundamental = evidence.fundamental();
        if (!FILING_FORMS.contains(fundamental.form()) || fundamental.accessionNumber() == null
                || !ACCESSION_NUMBER.matcher(fundamental.accessionNumber()).matches()
                || fundamental.periodEnd() == null || fundamental.filedAt() == null
                || fundamental.sharesOutstanding() == null || fundamental.sharesOutstanding().signum() <= 0
                || !fundamental.periodEnd().atStartOfDay(ZoneOffset.UTC).toInstant().isBefore(fundamental.filedAt())
                || fundamental.filedAt().isAfter(now)
                || fundamental.filedAt().isBefore(now.minus(fundamentalStaleAfter))) throw invalid();
        var secUri = httpsUri(fundamental.url());
        if (!SEC_HOSTS.contains(secUri.getHost().toLowerCase(Locale.ROOT))
                || !secUri.getRawPath().contains(fundamental.accessionNumber().replace("-", ""))) throw invalid();

        var consensus = evidence.consensus();
        if (consensus.horizon() == null
                || !consensus.horizon().atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(now)
                || consensus.revenueConsensus() == null || consensus.epsConsensus() == null
                || consensus.analystCount() == null || consensus.analystCount() < 1
                || !"USD".equals(consensus.currency()) || !freshExternalAsOf(consensus.asOf(), now)) throw invalid();
        requireEvidenceDomain(consensus.url());

        var revision = evidence.revision();
        if (revision.eps90dChangePct() == null || revision.revenue90dChangePct() == null
                || revision.baselineAsOf() == null || !freshExternalAsOf(revision.asOf(), now)) throw invalid();
        var baselineGap = Duration.between(revision.baselineAsOf(), revision.asOf());
        if (baselineGap.compareTo(Duration.ofDays(80)) < 0 || baselineGap.compareTo(Duration.ofDays(100)) > 0) {
            throw invalid();
        }
        requireEvidenceDomain(revision.url());
        return evidence;
    }

    private boolean freshExternalAsOf(Instant asOf, Instant now) {
        return asOf != null && !asOf.isAfter(now) && !asOf.isBefore(now.minus(consensusStaleAfter));
    }

    private void requireEvidenceDomain(String url) {
        var host = httpsUri(url).getHost().toLowerCase(Locale.ROOT);
        var allowed = externalDomains.stream().map(domain -> domain.toLowerCase(Locale.ROOT))
                .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        if (!allowed) throw invalid();
    }

    private static URI httpsUri(String value) {
        var url = bounded(value, 2000);
        try {
            var uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().isBlank() || uri.getHost().endsWith(".") || uri.getPort() != -1
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null) throw invalid();
            return uri;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** Claimed SEC share count must be within the tolerance of Toss StockInfo.sharesOutstanding. */
    private static boolean sharesMatchToss(BigDecimal claimed, InvestmentBuyRiskGate.TossStockHazards hazards) {
        if (claimed == null || hazards == null || hazards.sharesOutstanding() == null
                || hazards.sharesOutstanding().signum() <= 0) return false;
        var tossShares = hazards.sharesOutstanding();
        var deviation = claimed.subtract(tossShares).abs().divide(tossShares, MathContext.DECIMAL64);
        return deviation.compareTo(SHARES_TOLERANCE) <= 0;
    }

    private String hashValue(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private JsonNode readNullableJson(String value) {
        return value == null ? null : readJson(value);
    }

    private static String evidenceModeOf(String value) {
        return value == null ? InvestmentBuyRiskGate.POLICY_PROVIDERS : value;
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
                SELECT review_id, run_id, user_id, decision_id, ticker, investment_horizon, mandate_updated_at,
                       thesis_revision_id, verdict, review_origin, provider, model, rationale, source_as_of,
                       sources::text, assessment::text, reviewed_market_price, market_observed_at,
                       market_health, price_risk_state, min_price, max_price, max_quantity, expires_at,
                       body_hash, created_at, evidence_mode, structured_evidence::text, verdict_reasons::text,
                       verification_status
                  FROM investment_strategic_buy_revalidations WHERE review_id = ?
                """, viewRow(), reviewId).stream().findFirst().orElse(null);
    }

    private RevalidationView latest(UUID userId, UUID decisionId) {
        if (userId == null || decisionId == null) return null;
        return jdbc.query("""
                SELECT review_id, run_id, user_id, decision_id, ticker, investment_horizon, mandate_updated_at,
                       thesis_revision_id, verdict, review_origin, provider, model, rationale, source_as_of,
                       sources::text, assessment::text, reviewed_market_price, market_observed_at,
                       market_health, price_risk_state, min_price, max_price, max_quantity, expires_at,
                       body_hash, created_at, evidence_mode, structured_evidence::text, verdict_reasons::text,
                       verification_status
                  FROM investment_strategic_buy_revalidations
                 WHERE user_id = ? AND decision_id = ?
                 ORDER BY created_at DESC, review_id DESC LIMIT 1
                """, viewRow(), userId, decisionId).stream().findFirst().orElse(null);
    }

    private RunRow findRunForUpdate(UUID runId) {
        return jdbc.query("""
                SELECT run_id, user_id, connection_id, api_key_id, decision_id, ticker, thesis_revision_id,
                       mandate_updated_at, reviewed_market_price, market_observed_at, market_health,
                       price_risk_state, challenge_hash, status, expires_at, evidence_mode
                  FROM investment_strategic_buy_revalidation_runs WHERE run_id = ? FOR UPDATE
                """, (rs, row) -> new RunRow(rs.getObject("run_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getObject("api_key_id", UUID.class), rs.getObject("decision_id", UUID.class),
                rs.getString("ticker"), rs.getObject("thesis_revision_id", UUID.class),
                instant(rs.getObject("mandate_updated_at", OffsetDateTime.class)),
                rs.getBigDecimal("reviewed_market_price"),
                instant(rs.getObject("market_observed_at", OffsetDateTime.class)), rs.getString("market_health"),
                rs.getString("price_risk_state"), rs.getString("challenge_hash"), rs.getString("status"),
                instant(rs.getObject("expires_at", OffsetDateTime.class)), rs.getString("evidence_mode")), runId)
                .stream().findFirst().orElse(null);
    }

    private boolean completedRun(UUID userId, UUID runId) {
        return Boolean.TRUE.equals(jdbc.query("""
                SELECT true FROM investment_strategic_buy_revalidation_runs
                 WHERE run_id = ? AND user_id = ? AND status = 'COMPLETED'
                """, (rs, row) -> rs.getBoolean(1), runId, userId).stream().findFirst().orElse(false));
    }

    private RowMapper<RevalidationView> viewRow() {
        return (rs, row) -> new RevalidationView(
                rs.getObject("review_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("user_id", UUID.class),
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
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                evidenceMode(rs.getString("evidence_mode")), readNullableJson(rs.getString("structured_evidence")),
                readNullableJson(rs.getString("verdict_reasons")), rs.getString("verification_status"));
    }

    private static String evidenceMode(String value) {
        return value == null ? InvestmentBuyRiskGate.POLICY_PROVIDERS : value;
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

    private static String hashSecret(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static boolean secureEquals(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static OffsetDateTime timestamp(Instant instant) { return instant.atOffset(java.time.ZoneOffset.UTC); }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static InvestmentException invalid() { return new InvestmentException(InvestmentException.Code.INVALID_INPUT); }
    private static InvestmentException conflict() { return new InvestmentException(InvestmentException.Code.CONFLICT); }

    private record DecisionTarget(String ticker, String action) {}

    private record RunRow(UUID runId, UUID userId, UUID connectionId, UUID apiKeyId, UUID decisionId,
                          String ticker, UUID thesisRevisionId, Instant mandateUpdatedAt,
                          BigDecimal reviewedMarketPrice, Instant marketObservedAt, String marketHealth,
                          String priceRiskState, String challengeHash, String status, Instant expiresAt,
                          String evidenceMode) {}

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

    /** Citation-level evidence for EXTERNAL_CITED reviews. Values are checked for shape, not verified. */
    public record StructuredEvidence(FundamentalEvidence fundamental, ConsensusEvidence consensus,
                                     RevisionEvidence revision) {}
    public record FundamentalEvidence(String form, String accessionNumber, LocalDate periodEnd, Instant filedAt,
                                      BigDecimal sharesOutstanding, String url) {}
    public record ConsensusEvidence(LocalDate horizon, BigDecimal revenueConsensus, BigDecimal epsConsensus,
                                    Integer analystCount, String currency, Instant asOf, String url) {}
    public record RevisionEvidence(BigDecimal eps90dChangePct, BigDecimal revenue90dChangePct, Instant asOf,
                                   Instant baselineAsOf, String url) {}

    public record ReviewRun(UUID runId, String challenge, UUID decisionId, String ticker, UUID thesisRevisionId,
                            BigDecimal reviewedMarketPrice, Instant marketObservedAt, String marketHealth,
                            String priceRiskState, Instant expiresAt, String evidenceMode,
                            List<InvestmentBuyRiskGate.LegacyProviderData> legacyProviderData) {
        public ReviewRun {
            legacyProviderData = legacyProviderData == null ? List.of() : List.copyOf(legacyProviderData);
        }

        public ReviewRun(UUID runId, String challenge, UUID decisionId, String ticker, UUID thesisRevisionId,
                         BigDecimal reviewedMarketPrice, Instant marketObservedAt, String marketHealth,
                         String priceRiskState, Instant expiresAt, String evidenceMode) {
            this(runId, challenge, decisionId, ticker, thesisRevisionId, reviewedMarketPrice, marketObservedAt,
                    marketHealth, priceRiskState, expiresAt, evidenceMode, List.of());
        }

        public ReviewRun(UUID runId, String challenge, UUID decisionId, String ticker, UUID thesisRevisionId,
                         BigDecimal reviewedMarketPrice, Instant marketObservedAt, String marketHealth,
                         String priceRiskState, Instant expiresAt) {
            this(runId, challenge, decisionId, ticker, thesisRevisionId, reviewedMarketPrice, marketObservedAt,
                    marketHealth, priceRiskState, expiresAt, InvestmentBuyRiskGate.POLICY_PROVIDERS);
        }
    }

    public record RunCompletion(UUID runId, String challenge, Instant sourceAsOf, String rationale,
                                List<EvidenceSource> sources, Assessment assessment,
                                BigDecimal minPrice, BigDecimal maxPrice, BigDecimal maxQuantity,
                                StructuredEvidence structuredEvidence) {
        public RunCompletion(UUID runId, String challenge, Instant sourceAsOf, String rationale,
                             List<EvidenceSource> sources, Assessment assessment,
                             BigDecimal minPrice, BigDecimal maxPrice, BigDecimal maxQuantity) {
            this(runId, challenge, sourceAsOf, rationale, sources, assessment, minPrice, maxPrice, maxQuantity, null);
        }
    }

    public record RevalidationView(UUID reviewId, UUID runId, UUID userId, UUID decisionId, String ticker,
                                  String investmentHorizon, Instant mandateUpdatedAt, UUID thesisRevisionId,
                                  String verdict, String reviewOrigin, String provider, String model, String rationale,
                                  Instant sourceAsOf, JsonNode sources, JsonNode assessment,
                                  BigDecimal reviewedMarketPrice, Instant marketObservedAt,
                                  String marketHealth, String priceRiskState,
                                  BigDecimal minPrice, BigDecimal maxPrice, BigDecimal maxQuantity,
                                  Instant expiresAt, String bodyHash, Instant createdAt,
                                  String evidenceMode, JsonNode structuredEvidence, JsonNode verdictReasons,
                                  String verificationStatus) {}

    private record ExternalReviewBody(RevalidationInput normalized, String evidenceMode,
                                      StructuredEvidence structuredEvidence, List<String> verdictReasons) {}

    public record Validation(boolean allowed, String reason, UUID reviewId, String verificationStatus) {
        static Validation blocked(String reason) { return new Validation(false, reason, null, null); }
    }
}
