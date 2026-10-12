package com.jmj.trade.investment;

import com.jmj.trade.monitoring.MonitoringWatchlistService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Read-only onboarding preflight plus idempotent enrollment into the existing scheduled watchlist pipeline. */
@Service
public final class InvestmentOnboardingService {

    private static final Pattern TICKER = Pattern.compile("[A-Z0-9._-]{1,32}");
    private static final Map<String, String> READINESS_FIELDS = Map.of(
            "priceStatus", "price",
            "trendStatus", "trend",
            "fundamentalStatus", "fundamentals",
            "consensusStatus", "consensus",
            "revisionStatus", "revision",
            "valuationStatus", "valuation",
            "balanceSheetStatus", "balanceSheet",
            "riskStatus", "risk");

    private final MonitoringWatchlistService watchlist;
    private final InvestmentContextService investment;
    private final ThesisCandidateGenerator candidateGenerator;
    private final InvestmentThesisVerificationService verificationService;

    @Autowired
    public InvestmentOnboardingService(
            MonitoringWatchlistService watchlist,
            InvestmentContextService investment,
            ThesisCandidateGenerator candidateGenerator,
            InvestmentThesisVerificationService verificationService
    ) {
        this.watchlist = Objects.requireNonNull(watchlist, "watchlist");
        this.investment = Objects.requireNonNull(investment, "investment");
        this.candidateGenerator = Objects.requireNonNull(candidateGenerator, "candidateGenerator");
        this.verificationService = verificationService;
    }

    InvestmentOnboardingService(
            MonitoringWatchlistService watchlist,
            InvestmentContextService investment,
            ThesisCandidateGenerator candidateGenerator
    ) {
        this(watchlist, investment, candidateGenerator, null);
    }

    /** Returns stored readiness only. It never invokes a provider, refreshes data, or writes state. */
    public ReadinessView readiness(UUID userId, String rawTicker) {
        requireUser(userId);
        var ticker = ticker(rawTicker);
        var registered = watchlist.list(userId).stream().anyMatch(entry -> ticker.equals(entry.symbol()));
        var context = investment.context(userId);
        var security = context.securities().stream().filter(entry -> ticker.equals(entry.ticker()))
                .findFirst().orElse(null);
        var pipeline = pipeline(context.pipeline());
        if (security == null) return missing(ticker, registered, pipeline);

        var readiness = security.readiness();
        var statuses = statuses(readiness);
        var dataAsOf = dataAsOf(security);
        var receipt = security.thesis() == null || verificationService == null
                || security.thesis().verificationEventId() == null ? null
                : verificationService.verificationReceipt(userId, ticker, security.thesis().verificationEventId());
        var thesis = thesis(security.thesis(), receipt, security.risk());
        var riskMark = riskMark(security);
        var candidateViews = candidateGenerator.candidates(userId, ticker).values().stream()
                .toList();
        var blockers = blockers(security.asOf(), statuses, readiness, pipeline, thesis, riskMark, candidateViews);
        var status = security.asOf() == null ? "DATA_MISSING" : blockers.isEmpty() ? "READY" : "BLOCKED";
        return new ReadinessView(ticker, registered, status, security.asOf(), statuses, dataAsOf, pipeline, thesis,
                riskMark, candidateViews, blockers);
    }

    /** Registers a ticker for collection by existing schedulers; retries preserve the existing watchlist row. */
    public RegistrationView register(UUID userId, String rawTicker) {
        requireUser(userId);
        var ticker = ticker(rawTicker);
        var result = watchlist.registerIfAbsent(userId, ticker);
        return new RegistrationView(result.entry().symbol(), true, !result.created(), result.entry().status());
    }

    private static ReadinessView missing(String ticker, boolean registered, PipelineSummary pipeline) {
        var statuses = new LinkedHashMap<String, String>();
        READINESS_FIELDS.values().forEach(name -> statuses.put(name, "DATA_MISSING"));
        var thesis = new ThesisSummary(false, null, null, "NO_THESIS", "NOT_VERIFIED", null,
                false, false, false, List.of());
        var riskMark = new RiskMarkSummary(null, null, null, null, null, "DATA_MISSING", null);
        var blockers = new ArrayList<String>();
        blockers.add("SECURITY_DATA_NOT_CAPTURED");
        blockers.addAll(blockers(null, Map.copyOf(statuses), null, pipeline, thesis, riskMark, List.of()));
        return new ReadinessView(ticker, registered, "DATA_MISSING", null, statuses, Map.of(), pipeline,
                thesis, riskMark, List.of(), blockers);
    }

    private static Map<String, String> statuses(JsonNode readiness) {
        var statuses = new LinkedHashMap<String, String>();
        READINESS_FIELDS.forEach((field, name) -> statuses.put(name, safeStatus(text(readiness, field))));
        return Map.copyOf(statuses);
    }

    private static ThesisSummary thesis(InvestmentContextService.ThesisView value,
                                        InvestmentThesisVerificationService.VerificationReceipt receipt,
                                        InvestmentContextService.RiskContributionView risk) {
        if (value == null) return new ThesisSummary(false, null, null, "NO_THESIS", "NOT_VERIFIED", null,
                false, false, false, risk == null ? List.of("THESIS_MISSING") : risk.eligibilityReasons());
        var receiptMatches = "AI_POLICY".equals(value.approvalActorType())
                && value.verificationEventId() != null && receipt != null
                && value.verificationEventId().equals(receipt.verificationEventId())
                && "AUTO_APPROVED".equals(receipt.outcome())
                && receipt.approvedRevisionId() != null
                && value.revisionId() != null
                && value.revisionId().equals(receipt.approvedRevisionId())
                && Objects.equals(value.revision(), receipt.approvedRevision())
                && Objects.equals(value.policyVersion(), receipt.policyVersion());
        String receiptStatus;
        if (receiptMatches) {
            receiptStatus = "AI_RECEIPT_CURRENT_REVISION";
        } else if ("AI_POLICY".equals(value.approvalActorType()) && value.verificationEventId() != null) {
            receiptStatus = "AI_RECEIPT_STALE_OR_UNVERIFIED";
        } else if ("AI_POLICY".equals(value.approvalActorType())
                && "AI_PROPOSED".equals(safeStatus(value.invalidationStatus()))) {
            receiptStatus = "AI_PROPOSED_NO_VERIFICATION_RECEIPT";
        } else {
            receiptStatus = switch (value.approvalActorType() == null ? "" : value.approvalActorType()) {
                case "USER_SESSION" -> "USER_AUTHORED_NO_AI_RECEIPT";
                case "TELEGRAM" -> "TELEGRAM_AUTHORED_NO_AI_RECEIPT";
                case "AI_POLICY" -> "AI_AUTHORED_NO_VERIFICATION_RECEIPT";
                default -> "NO_CURRENT_AI_RECEIPT";
            };
        }
        var eligibilityReasons = risk == null ? List.<String>of() : risk.eligibilityReasons();
        return new ThesisSummary(true, value.revision(), safeStatus(value.invalidationStatus()), receiptStatus,
                receipt == null ? "NOT_VERIFIED" : safeStatus(receipt.outcome()),
                receipt == null ? null : safeReason(receipt.reasonCode()), receiptMatches,
                value.priceRiskTriggerPrice() != null && value.priceRiskTriggerPrice().signum() > 0,
                risk != null && risk.sizingEligible(), eligibilityReasons);
    }

    private static RiskMarkSummary riskMark(InvestmentContextService.SecurityView security) {
        var mark = security.risk() == null ? null : security.risk().riskMark();
        return mark == null
                ? new RiskMarkSummary(null, null, null, null, null, "DATA_MISSING", "RISK_MARK_NOT_AVAILABLE")
                : new RiskMarkSummary(mark.value(), mark.asOf(), mark.source(), mark.basis(), mark.asOfBasis(),
                        mark.status() == null ? "UNVERIFIED" : mark.status().name(), safeReason(mark.reason()));
    }

    private static Map<String, Instant> dataAsOf(InvestmentContextService.SecurityView security) {
        var result = new LinkedHashMap<String, Instant>();
        result.put("price", instant(security.price(), "latestPriceAsOf", "asOf"));
        result.put("trend", instant(security.technical(), "asOf"));
        result.put("fundamentals", instant(security.fundamentals(), "asOf"));
        result.put("consensus", instant(security.consensus(), "asOf"));
        result.put("revision", instant(security.revision(), "asOf"));
        result.put("valuation", instant(security.valuation(), "asOf"));
        result.put("balanceSheet", instant(security.fundamentals(), "asOf"));
        result.values().removeIf(Objects::isNull);
        return Map.copyOf(result);
    }

    private static Instant instant(JsonNode node, String... fields) {
        if (node == null) return null;
        for (var field : fields) {
            var value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                try {
                    return Instant.parse(value);
                } catch (RuntimeException ignored) {
                    // Try the next stored timestamp field when the preferred one is malformed.
                }
            }
        }
        return null;
    }

    private static List<String> blockers(
            Instant snapshotAsOf,
            Map<String, String> statuses,
            JsonNode readiness,
            PipelineSummary pipeline,
            ThesisSummary thesis,
            RiskMarkSummary riskMark,
            List<ThesisCandidateGenerator.Candidate> candidates
    ) {
        var blockers = new ArrayList<String>();
        if (snapshotAsOf == null) blockers.add("SECURITY_DATA_NOT_CAPTURED");
        statuses.forEach((name, status) -> {
            if (!"OK".equals(status)) blockers.add("DATA_" + safeToken(name) + "_" + safeToken(status));
        });
        if (readiness != null && readiness.path("missingFields").isArray()) {
            readiness.path("missingFields").forEach(field -> {
                var value = safeToken(field.asText(null));
                if (!value.isBlank()) blockers.add("MISSING_FIELD_" + value);
            });
        }
        if (pipeline.lastSuccessAt() == null) {
            blockers.add("SECURITY_DATA_PIPELINE_" + safeToken(pipeline.status()));
        }
        if (!thesis.present()) blockers.add("THESIS_NOT_PRESENT");
        else if (!"CONFIRMED".equals(thesis.invalidationStatus())) blockers.add("INVALIDATION_NOT_CONFIRMED");
        if (thesis.present() && "CONFIRMED".equals(thesis.invalidationStatus())
                && !thesis.receiptMatchesCurrentRevision()
                && ("AI_RECEIPT_STALE_OR_UNVERIFIED".equals(thesis.receiptStatus())
                || "AI_AUTHORED_NO_VERIFICATION_RECEIPT".equals(thesis.receiptStatus()))) {
            blockers.add("AI_APPROVAL_RECEIPT_MISSING_OR_MISMATCHED");
        }
        if (thesis.present()) {
            if (!thesis.sizingEligible()) blockers.add("SIZING_INELIGIBLE");
            thesis.eligibilityReasons().stream().map(InvestmentOnboardingService::safeToken)
                    .filter(reason -> !reason.isBlank()).forEach(blockers::add);
        }
        if (!"OK".equals(riskMark.status())) {
            blockers.add("RISK_MARK_" + safeToken(riskMark.reason() == null ? riskMark.status() : riskMark.reason()));
        }
        candidates.stream().filter(candidate -> !"OK".equals(candidate.status())).forEach(candidate ->
                blockers.add("CANDIDATE_" + safeToken(candidate.source()) + "_"
                        + safeToken(candidate.reason() == null ? candidate.status() : candidate.reason())));
        return blockers.stream().distinct().sorted().toList();
    }

    private static PipelineSummary pipeline(InvestmentContextService.PipelineView value) {
        return value == null
                ? new PipelineSummary("NEVER_RUN", null, null)
                : new PipelineSummary(safeStatus(value.status()), value.lastAttemptAt(), value.lastSuccessAt());
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.path(field).asText(null);
        return value == null || value.isBlank() ? "DATA_MISSING" : value;
    }

    private static String safeStatus(String value) {
        var normalized = safeToken(value == null ? "UNVERIFIED" : value);
        return normalized.isBlank() ? "UNVERIFIED" : normalized;
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = safeToken(value);
        return normalized.isBlank() ? "UNVERIFIED" : normalized;
    }

    private static String safeToken(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String ticker(String value) {
        if (value == null || value.isBlank()) throw invalid();
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!TICKER.matcher(normalized).matches()) throw invalid();
        return normalized;
    }

    private static void requireUser(UUID userId) {
        if (userId == null) throw new InvestmentException(InvestmentException.Code.INVALID_USER);
    }

    private static InvestmentException invalid() {
        return new InvestmentException(InvestmentException.Code.INVALID_INPUT);
    }

    public record ReadinessView(
            String ticker,
            boolean registered,
            String status,
            Instant snapshotAsOf,
            Map<String, String> dataStatuses,
            Map<String, Instant> dataAsOf,
            PipelineSummary collection,
            ThesisSummary thesis,
            RiskMarkSummary riskMark,
            List<ThesisCandidateGenerator.Candidate> candidates,
            List<String> blockers
    ) {
        public ReadinessView {
            dataStatuses = dataStatuses == null ? Map.of() : Map.copyOf(dataStatuses);
            dataAsOf = dataAsOf == null ? Map.of() : Map.copyOf(dataAsOf);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    public record PipelineSummary(String status, Instant lastAttemptAt, Instant lastSuccessAt) {
    }

    public record ThesisSummary(boolean present, Long revision, String invalidationStatus, String receiptStatus,
                                String verificationOutcome, String verificationReason,
                                boolean receiptMatchesCurrentRevision, boolean triggerConfigured,
                                boolean sizingEligible, List<String> eligibilityReasons) {
        public ThesisSummary {
            eligibilityReasons = eligibilityReasons == null ? List.of() : List.copyOf(eligibilityReasons);
        }
    }

    public record RiskMarkSummary(BigDecimal value, Instant asOf, String source, String basis, String asOfBasis,
                                  String status, String reason) {
    }

    public record RegistrationView(String ticker, boolean registered, boolean alreadyRegistered, String status) {
    }
}
