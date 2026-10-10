package com.jmj.trade.investment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stores externally authored verification evidence and applies the authenticated user's opt-in policy. */
@Service
public final class InvestmentThesisVerificationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final InvestmentContextService context;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final boolean deploymentPolicyEnabled;
    private final boolean sheetOwnerConfigured;
    private final UUID sheetOwnerUserId;
    private final String deploymentPolicyVersion;
    private final Duration maxEvidenceAge;
    private final int minimumSourceCount;
    private final BigDecimal maxTriggerBelowPricePct;
    private ThesisCandidateGenerator candidateGenerator;

    @Autowired
    public InvestmentThesisVerificationService(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, InvestmentContextService context,
            ObjectMapper mapper,
            @Value("${investment.thesis.ai-policy.enabled:false}") boolean deploymentPolicyEnabled,
            @Value("${investment-os.sheet.enabled:false}") boolean sheetEnabled,
            @Value("${investment-os.sheet.user-id:}") String sheetOwnerUserId,
            @Value("${investment.thesis.ai-policy.version:v1}") String deploymentPolicyVersion,
            @Value("${investment.thesis.ai-policy.max-evidence-age:PT24H}") Duration maxEvidenceAge,
            @Value("${investment.thesis.ai-policy.minimum-source-count:2}") int minimumSourceCount,
            @Value("${investment.thesis.ai-policy.max-trigger-below-price-pct:0.25}") BigDecimal maxTriggerBelowPricePct) {
        this(jdbc, transactionManager, context, mapper, Clock.systemUTC(), deploymentPolicyEnabled, sheetEnabled,
                sheetOwnerUserId, deploymentPolicyVersion, maxEvidenceAge, minimumSourceCount,
                maxTriggerBelowPricePct);
    }

    InvestmentThesisVerificationService(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, InvestmentContextService context,
            ObjectMapper mapper, Clock clock, boolean deploymentPolicyEnabled, boolean sheetEnabled,
            String sheetOwnerUserId, String deploymentPolicyVersion, Duration maxEvidenceAge,
            int minimumSourceCount, BigDecimal maxTriggerBelowPricePct) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.context = context;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.deploymentPolicyEnabled = deploymentPolicyEnabled;
        this.sheetOwnerConfigured = sheetEnabled;
        this.sheetOwnerUserId = parseUuid(sheetOwnerUserId);
        this.deploymentPolicyVersion = deploymentPolicyVersion == null || deploymentPolicyVersion.isBlank()
                ? "v1" : deploymentPolicyVersion.trim();
        this.maxEvidenceAge = maxEvidenceAge == null ? Duration.ofHours(24) : maxEvidenceAge;
        this.minimumSourceCount = minimumSourceCount;
        this.maxTriggerBelowPricePct = maxTriggerBelowPricePct;
        validatePolicyValues(this.maxEvidenceAge, this.minimumSourceCount, this.maxTriggerBelowPricePct);
    }

    @Autowired
    void setCandidateGenerator(ThesisCandidateGenerator candidateGenerator) {
        this.candidateGenerator = candidateGenerator;
    }

    public PolicyView policy(UUID userId) {
        requireUser(userId);
        return latestPolicy(userId);
    }

    public PolicyView updatePolicy(UUID authenticatedUserId, PolicyInput input) {
        requireUser(authenticatedUserId);
        if (input == null || input.enabled() == null) throw invalid();
        return transaction.execute(ignored -> {
            context.lockThesisWriter(authenticatedUserId);
            var current = latestStoredPolicy(authenticatedUserId);
            if (current == null ? input.expectedRevisionId() != null
                    : !current.revisionId().equals(input.expectedRevisionId())) throw conflict();
            var id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO investment_thesis_ai_policy_revisions (
                        id, user_id, enabled, policy_version, max_evidence_age_seconds,
                        minimum_source_count, max_trigger_below_price_pct, actor_user_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, authenticatedUserId, input.enabled(), deploymentPolicyVersion,
                    maxEvidenceAge.toSeconds(), minimumSourceCount, maxTriggerBelowPricePct,
                    authenticatedUserId, timestamp(clock.instant()));
            return latestStoredPolicy(authenticatedUserId);
        });
    }

    public VerificationContextView verificationContext(UUID userId, String rawTicker) {
        requireUser(userId);
        var ticker = ticker(rawTicker);
        var thesis = context.currentThesis(userId, ticker);
        if (thesis == null || thesis.revisionId() == null) throw new InvestmentException(InvestmentException.Code.NOT_FOUND);
        var facts = context.priceFacts(userId, ticker);
        var mark = facts.riskMark(clock.instant());
        var price = new PriceView(facts.latestPrice(), facts.status(), facts.asOf(), facts.source(), facts.session(),
                mark.value(), mark.asOf(), mark.source(), mark.basis(), mark.asOfBasis(), mark.status().name(), mark.reason());
        var candidateTriggers = new ArrayList<TriggerCandidate>();
        if (thesis.priceRiskTriggerPrice() != null && thesis.priceRiskTriggerPrice().signum() > 0) {
            candidateTriggers.add(new TriggerCandidate(thesis.priceRiskTriggerPrice(), "EXISTING_PROPOSAL",
                    thesis.updatedAt()));
        }
        if (candidateGenerator != null) {
            candidateGenerator.candidates(userId, ticker).values().stream()
                    .filter(ThesisCandidateGenerator.Candidate::available)
                    .map(candidate -> new TriggerCandidate(candidate.trigger(), candidate.source(), candidate.sourceAsOf()))
                    .forEach(candidateTriggers::add);
        }
        return new VerificationContextView(latestPolicy(userId), thesis, price,
                latestVerification(userId, ticker), List.copyOf(candidateTriggers));
    }

    public VerificationResult verify(UUID authenticatedUserId, UUID authenticatedApiKeyId, VerificationInput rawInput) {
        requireUser(authenticatedUserId);
        var input = normalize(rawInput);
        var bodyHash = hash(input);
        try {
            return transaction.execute(ignored -> {
            context.lockThesisWriter(authenticatedUserId);
            var existing = findEvent(input.verificationEventId());
            if (existing != null) {
                if (!existing.userId().equals(authenticatedUserId) || !existing.bodyHash().equals(bodyHash))
                    throw conflict();
                return existing.result();
            }

            var policy = latestPolicy(authenticatedUserId);
            var thesis = context.currentThesis(authenticatedUserId, input.ticker());
            if (thesis == null || thesis.revisionId() == null) throw new InvestmentException(InvestmentException.Code.NOT_FOUND);
            var proposal = proposalRevision(authenticatedUserId, input.ticker(), input.thesisRevisionId());
            if (proposal == null)
                throw new InvestmentException(InvestmentException.Code.NOT_FOUND);

            var now = clock.instant();
            var facts = context.priceFacts(authenticatedUserId, input.ticker());
            var mark = facts.riskMark(now);
            var outcome = "BLOCKED";
            var reason = blockedReason(input, thesis, proposal.assertedRunId(), policy, mark, now);
            InvestmentContextService.ThesisView approvedThesis = null;

            if ("REJECT".equals(input.verdict())) {
                outcome = "REJECTED";
                reason = "EXTERNAL_VERIFICATION_REJECTED";
            } else if (reason == null) {
                outcome = "AUTO_APPROVED";
                reason = "AUTO_APPROVAL_POLICY_PASSED";
            }

            var policySnapshot = policySnapshot(policy);
            var evaluation = evaluationSnapshot(input, thesis, proposal.assertedRunId(), policy, facts, mark,
                    outcome, reason, now);
            if ("AUTO_APPROVED".equals(outcome)) {
                approvedThesis = context.confirmAiPolicyThesis(authenticatedUserId, input.ticker(),
                        input.thesisRevisionId(), input.verificationEventId(), policy.policyVersion(),
                        input.selectedTriggerPrice(), input.sourceAsOf(), "EXTERNAL_AI_VERIFICATION_POLICY");
                evaluation.put("approvedThesis", mapper.valueToTree(approvedThesis));
            }

            var createdAt = now;
            jdbc.update("""
                    INSERT INTO investment_thesis_ai_verification_events (
                        verification_event_id, user_id, ticker, proposal_revision_id, proposal_run_id,
                        verification_run_id, provider, model, verdict, source_as_of, source_urls,
                        rationale, counterevidence, selected_trigger_price, outcome, reason_code,
                        policy_version, policy_snapshot, evaluation_snapshot, authenticated_actor_user_id,
                        authenticated_actor_key_id, body_hash, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?,
                              CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)
                    """, input.verificationEventId(), authenticatedUserId, input.ticker(), input.thesisRevisionId(),
                    proposal.assertedRunId(), input.verificationRunId(), input.provider(), input.model(), input.verdict(),
                    timestamp(input.sourceAsOf()), json(input.sourceUrls()), input.rationale(), input.counterevidence(),
                    input.selectedTriggerPrice(), outcome, reason, policy.policyVersion(), json(policySnapshot),
                    json(evaluation), authenticatedUserId, authenticatedApiKeyId, bodyHash, timestamp(createdAt));
            var persisted = findEvent(input.verificationEventId());
            if (persisted == null || !persisted.userId().equals(authenticatedUserId)
                    || !persisted.bodyHash().equals(bodyHash)) {
                throw new IllegalStateException("Persisted verification event could not be read back");
            }
            return persisted.result();
            });
        } catch (DuplicateKeyException duplicate) {
            // A global event-id collision can race across two different user locks.
            // Resolve it only as an exact idempotent replay; never return another user's event.
            var existing = findEvent(input.verificationEventId());
            if (existing == null) throw duplicate;
            if (!existing.userId().equals(authenticatedUserId) || !existing.bodyHash().equals(bodyHash))
                throw conflict();
            return existing.result();
        }
    }

    private String blockedReason(VerificationInput input, InvestmentContextService.ThesisView thesis,
                                 String proposalRunId,
                                 PolicyView policy, InvestmentRiskMarkSelector.Selection mark, Instant now) {
        if (!thesis.revisionId().equals(input.thesisRevisionId())) return "STALE_PROPOSAL_REVISION";
        if (!Set.of("AI_PROPOSED", "UNVERIFIED", "INVALIDATION_UNDEFINED").contains(thesis.invalidationStatus()))
            return "THESIS_NOT_PROPOSABLE";
        if (!policy.enabled()) return "AI_POLICY_DISABLED";
        if (proposalRunId != null && proposalRunId.equals(input.verificationRunId()))
            return "PROPOSAL_AND_VERIFICATION_RUN_MATCH";
        if (input.sourceAsOf().isAfter(now) || input.sourceAsOf().isBefore(now.minusSeconds(policy.maxEvidenceAgeSeconds())))
            return "VERIFICATION_EVIDENCE_STALE";
        if (input.sourceUrls().stream().distinct().count() < policy.minimumSourceCount())
            return "VERIFICATION_SOURCES_INSUFFICIENT";
        if (input.selectedTriggerPrice() == null) return "MISSING_TRIGGER_PRICE";
        if (!mark.available()) return "TRUSTED_PRICE_" + mark.status().name();
        if (input.selectedTriggerPrice().compareTo(mark.value()) >= 0)
            return "TRIGGER_NOT_BELOW_TRUSTED_PRICE";
        var distance = mark.value().subtract(input.selectedTriggerPrice()).divide(mark.value(), 10,
                java.math.RoundingMode.HALF_UP);
        if (distance.compareTo(policy.maxTriggerBelowPricePct()) > 0) return "TRIGGER_DISTANCE_EXCEEDS_POLICY";
        return null;
    }

    private VerificationInput normalize(VerificationInput input) {
        if (input == null || input.verificationEventId() == null || input.thesisRevisionId() == null
                || input.sourceAsOf() == null || input.selectedTriggerPrice() != null
                && input.selectedTriggerPrice().signum() <= 0) throw invalid();
        var ticker = ticker(input.ticker());
        var runId = bounded(input.verificationRunId(), 160);
        var provider = bounded(input.provider(), 120);
        var model = bounded(input.model(), 160);
        var verdict = input.verdict() == null ? "" : input.verdict().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PASS", "REJECT").contains(verdict)) throw invalid();
        var rationale = bounded(input.rationale(), 4000);
        var counterevidence = bounded(input.counterevidence(), 4000);
        if (input.sourceUrls() == null || input.sourceUrls().isEmpty() || input.sourceUrls().size() > 10) throw invalid();
        var urls = new ArrayList<String>();
        for (var url : input.sourceUrls()) {
            var normalized = validateUrl(url);
            if (urls.contains(normalized)) throw invalid();
            urls.add(normalized);
        }
        BigDecimal selectedTrigger = null;
        if (input.selectedTriggerPrice() != null) {
            try {
                selectedTrigger = input.selectedTriggerPrice().setScale(8, java.math.RoundingMode.UNNECESSARY)
                        .stripTrailingZeros();
            } catch (ArithmeticException exception) {
                throw invalid();
            }
            var integerDigits = Math.max(0, selectedTrigger.precision() - selectedTrigger.scale());
            if (selectedTrigger.signum() <= 0 || selectedTrigger.precision() > 24 || integerDigits > 16)
                throw invalid();
        }
        return new VerificationInput(input.verificationEventId(), ticker, input.thesisRevisionId(), runId,
                provider, model, verdict, input.sourceAsOf(), List.copyOf(urls), rationale, counterevidence,
                selectedTrigger);
    }

    private static String validateUrl(String value) {
        var normalized = bounded(value, 2000);
        try {
            var uri = URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().isBlank() || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() > 65535) throw invalid();
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw invalid();
        return value.trim();
    }

    private ProposalRevision proposalRevision(UUID userId, String ticker, UUID revisionId) {
        return jdbc.query("""
                SELECT id, asserted_run_id FROM investment_thesis_revisions
                 WHERE id = ? AND user_id = ? AND ticker = ?
                """, (rs, row) -> new ProposalRevision(rs.getString(2)), revisionId, userId, ticker)
                .stream().findFirst().orElse(null);
    }

    private record ProposalRevision(String assertedRunId) {}

    private VerificationResult latestVerification(UUID userId, String ticker) {
        return jdbc.query("""
                SELECT verification_event_id, proposal_revision_id, outcome, reason_code, verdict, policy_version,
                       authenticated_actor_user_id, authenticated_actor_key_id, created_at,
                       evaluation_snapshot::text
                  FROM investment_thesis_ai_verification_events
                 WHERE user_id = ? AND ticker = ?
                 ORDER BY created_at DESC, verification_event_id DESC LIMIT 1
                """, (rs, row) -> {
            var snapshot = mapper.readTree(rs.getString(10));
            var approved = snapshot.hasNonNull("approvedThesis")
                    ? mapper.treeToValue(snapshot.get("approvedThesis"), InvestmentContextService.ThesisView.class)
                    : null;
            return new VerificationResult((UUID) rs.getObject(1), ticker, (UUID) rs.getObject(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), (UUID) rs.getObject(7),
                    (UUID) rs.getObject(8), instant(rs.getObject(9, OffsetDateTime.class)), approved);
        }, userId, ticker).stream().findFirst().orElse(null);
    }

    private ExistingEvent findEvent(UUID eventId) {
        return jdbc.query("""
                SELECT user_id, body_hash, ticker, proposal_revision_id, outcome, reason_code, verdict,
                       policy_version, authenticated_actor_user_id, authenticated_actor_key_id, created_at,
                       evaluation_snapshot::text
                  FROM investment_thesis_ai_verification_events WHERE verification_event_id = ?
                """, (rs, row) -> {
            var snapshot = mapper.readTree(rs.getString(12));
            var approved = snapshot.hasNonNull("approvedThesis")
                    ? mapper.treeToValue(snapshot.get("approvedThesis"), InvestmentContextService.ThesisView.class)
                    : null;
            return new ExistingEvent((UUID) rs.getObject(1), rs.getString(2), new VerificationResult(eventId,
                    rs.getString(3), (UUID) rs.getObject(4), rs.getString(5), rs.getString(6), rs.getString(7),
                    rs.getString(8), (UUID) rs.getObject(9), (UUID) rs.getObject(10),
                    instant(rs.getObject(11, OffsetDateTime.class)), approved));
        }, eventId).stream().findFirst().orElse(null);
    }

    private record ExistingEvent(UUID userId, String bodyHash, VerificationResult result) {}

    private PolicyView latestPolicy(UUID userId) {
        var stored = latestStoredPolicy(userId);
        if (stored != null) return stored;
        var enabled = deploymentPolicyEnabled && sheetOwnerConfigured && userId.equals(sheetOwnerUserId);
        return new PolicyView(enabled, enabled ? "DEPLOYMENT" : "DEFAULT_DISABLED", deploymentPolicyVersion,
                maxEvidenceAge.toSeconds(), minimumSourceCount, maxTriggerBelowPricePct, null, null, null);
    }

    private PolicyView latestStoredPolicy(UUID userId) {
        return jdbc.query("""
                SELECT id, enabled, policy_version, max_evidence_age_seconds, minimum_source_count,
                       max_trigger_below_price_pct, actor_user_id, created_at
                  FROM investment_thesis_ai_policy_revisions
                 WHERE user_id = ? ORDER BY revision DESC, id DESC LIMIT 1
                """, (rs, row) -> new PolicyView(rs.getBoolean(2), "USER", rs.getString(3), rs.getLong(4),
                rs.getInt(5), rs.getBigDecimal(6), (UUID) rs.getObject(1), (UUID) rs.getObject(7),
                instant(rs.getObject(8, OffsetDateTime.class))), userId).stream().findFirst().orElse(null);
    }

    private Map<String, Object> policySnapshot(PolicyView policy) {
        var result = new LinkedHashMap<String, Object>();
        result.put("enabled", policy.enabled()); result.put("source", policy.source());
        result.put("policyVersion", policy.policyVersion()); result.put("maxEvidenceAgeSeconds", policy.maxEvidenceAgeSeconds());
        result.put("minimumSourceCount", policy.minimumSourceCount());
        result.put("maxTriggerBelowPricePct", policy.maxTriggerBelowPricePct()); result.put("revisionId", policy.revisionId());
        return result;
    }

    private Map<String, Object> evaluationSnapshot(VerificationInput input, InvestmentContextService.ThesisView thesis,
                                                    String proposalRunId,
                                                    PolicyView policy, InvestmentContextService.PriceFacts facts,
                                                    InvestmentRiskMarkSelector.Selection mark, String outcome,
                                                    String reason, Instant now) {
        var result = new LinkedHashMap<String, Object>();
        result.put("proposalRevisionId", input.thesisRevisionId()); result.put("currentRevisionId", thesis.revisionId());
        result.put("proposalRunId", proposalRunId); result.put("currentAssertedRunId", thesis.assertedRunId());
        result.put("verificationRunId", input.verificationRunId());
        result.put("policy", policySnapshot(policy)); result.put("outcome", outcome); result.put("reasonCode", reason);
        result.put("evaluatedAt", now); result.put("trustedMark", mark.value()); result.put("markAsOf", mark.asOf());
        result.put("markSource", mark.source()); result.put("markBasis", mark.basis());
        result.put("markAsOfBasis", mark.asOfBasis()); result.put("markStatus", mark.status().name());
        result.put("markReason", mark.reason()); result.put("latestQuoteAsOf", facts.asOf());
        result.put("latestQuoteStatus", facts.status()); result.put("selectedTriggerPrice", input.selectedTriggerPrice());
        result.put("sourceCount", input.sourceUrls().size()); result.put("rationale", input.rationale());
        result.put("counterevidence", input.counterevidence()); result.put("sourceUrls", input.sourceUrls());
        return result;
    }

    private String hash(VerificationInput input) {
        try {
            var bytes = mapper.writeValueAsBytes(input);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private void requireUser(UUID userId) {
        if (userId == null) throw new InvestmentException(InvestmentException.Code.INVALID_USER);
        var exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)", Boolean.class, userId);
        if (!Boolean.TRUE.equals(exists)) throw new InvestmentException(InvestmentException.Code.INVALID_USER);
    }

    private static String ticker(String rawTicker) {
        if (rawTicker == null || !rawTicker.trim().matches("[A-Za-z0-9._-]{1,32}")) throw invalid();
        return rawTicker.trim().toUpperCase(Locale.ROOT);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value.trim()); } catch (IllegalArgumentException invalid) { return null; }
    }

    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }

    private static InvestmentException invalid() { return new InvestmentException(InvestmentException.Code.INVALID_INPUT); }
    private static InvestmentException conflict() { return new InvestmentException(InvestmentException.Code.CONFLICT); }

    private static void validatePolicyValues(Duration evidenceAge, int sources, BigDecimal triggerDistance) {
        if (evidenceAge == null || evidenceAge.compareTo(Duration.ofMinutes(1)) < 0
                || evidenceAge.compareTo(Duration.ofDays(7)) > 0 || sources < 1 || sources > 10
                || triggerDistance == null || triggerDistance.signum() < 0
                || triggerDistance.compareTo(new BigDecimal("0.50")) > 0)
            throw new IllegalArgumentException("AI thesis policy values are outside supported bounds");
    }

    public record PolicyInput(Boolean enabled, UUID expectedRevisionId) {}

    public record PolicyView(boolean enabled, String source, String policyVersion, long maxEvidenceAgeSeconds,
                             int minimumSourceCount, BigDecimal maxTriggerBelowPricePct,
                             UUID revisionId, UUID actorUserId, Instant updatedAt) {}

    public record PriceView(BigDecimal latestPrice, String status, Instant asOf, String source, String session,
                            BigDecimal riskMark, Instant riskMarkAsOf, String riskMarkSource, String riskMarkBasis,
                            String riskMarkAsOfBasis, String riskMarkStatus, String riskMarkReason) {}

    public record TriggerCandidate(BigDecimal price, String source, Instant asOf) {}

    public record VerificationContextView(PolicyView policy, InvestmentContextService.ThesisView thesis,
                                          PriceView price, VerificationResult latestVerification,
                                          List<TriggerCandidate> candidateTriggers) {
        public VerificationContextView { candidateTriggers = candidateTriggers == null ? List.of() : List.copyOf(candidateTriggers); }
    }

    public record VerificationInput(UUID verificationEventId, String ticker, UUID thesisRevisionId,
                                    String verificationRunId, String provider, String model, String verdict,
                                    Instant sourceAsOf, List<String> sourceUrls, String rationale,
                                    String counterevidence, BigDecimal selectedTriggerPrice) {
        public VerificationInput { sourceUrls = sourceUrls == null ? List.of() : List.copyOf(sourceUrls); }
    }

    public record VerificationResult(UUID verificationEventId, String ticker, UUID thesisRevisionId,
                                     String outcome, String reasonCode, String verdict, String policyVersion,
                                     UUID authenticatedActorUserId, UUID authenticatedActorKeyId, Instant createdAt,
                                     InvestmentContextService.ThesisView approvedThesis) {}
}
