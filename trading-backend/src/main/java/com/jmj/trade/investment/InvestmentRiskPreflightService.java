package com.jmj.trade.investment;

import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class InvestmentRiskPreflightService {

    private final InvestmentContextService investment;
    private final TransactionTemplate transaction;
    private final InvestmentThesisVerificationService thesisVerification;

    @Autowired
    public InvestmentRiskPreflightService(InvestmentContextService investment, JdbcTemplate jdbc,
                                          InvestmentThesisVerificationService thesisVerification,
                                          PlatformTransactionManager transactionManager) {
        this.investment = Objects.requireNonNull(investment, "investment");
        Objects.requireNonNull(jdbc, "jdbc");
        this.thesisVerification = thesisVerification;
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setReadOnly(true);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** Convenience constructor for direct local tests; Spring uses the injected transaction manager above. */
    public InvestmentRiskPreflightService(InvestmentContextService investment, JdbcTemplate jdbc) {
        this(investment, jdbc, null, new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                Objects.requireNonNull(jdbc, "jdbc").getDataSource()));
    }

    public InvestmentRiskPreflightService(InvestmentContextService investment, JdbcTemplate jdbc,
                                          PlatformTransactionManager transactionManager) {
        this(investment, jdbc, null, transactionManager);
    }

    public PreflightView preflight(UUID userId, PreflightInput input) {
        var normalized = normalize(input);
        if (userId == null) throw new InvestmentException(InvestmentException.Code.INVALID_USER);
        return transaction.execute(status -> evaluate(userId, normalized));
    }

    private PreflightView evaluate(UUID userId, PreflightInput input) {
        var data = investment.riskPreflightData(userId, input.ticker(), input.quantity());
        var currentRisk = data.currentRisk();
        var proFormaRisk = data.proFormaRisk();
        var blockers = new LinkedHashSet<String>();

        var portfolio = data.portfolio();
        var manualFresh = !portfolio.manualStale()
                && (portfolio.manualStatus() == null
                || "OK".equals(portfolio.manualStatus())
                || "EMPTY_CONFIRMED".equals(portfolio.manualStatus()));
        if (!portfolio.riskNumbersAvailable() || !"OK".equals(portfolio.status())) {
            blockers.add("PORTFOLIO_RISK_UNAVAILABLE");
        }
        if (!manualFresh) blockers.add("MANUAL_PORTFOLIO_STALE");

        var thesis = thesisReceipt(userId, input.ticker(), data.thesis());
        if (data.thesis() == null) blockers.add("THESIS_MISSING");
        else if (!"CONFIRMED".equals(data.thesis().invalidationStatus())) blockers.add("THESIS_NOT_CONFIRMED");
        if (thesis.revision() == null || thesis.revisionId() == null) blockers.add("THESIS_REVISION_MISSING");
        if (thesis.actorType() != null && !"AI_POLICY".equals(thesis.actorType())
                && !Set.of("USER_SESSION", "TELEGRAM").contains(thesis.actorType())) {
            blockers.add("APPROVAL_ORIGIN_UNVERIFIED");
        }
        if (thesis.actorType() == null) blockers.add("APPROVAL_ORIGIN_UNVERIFIED");
        if (thesis.receiptRequired() && !thesis.receiptMatchesCurrentRevision()) {
            blockers.add("AI_APPROVAL_RECEIPT_MISSING_OR_MISMATCHED");
        }

        var targetCurrent = currentRisk.get(input.ticker());
        var mark = targetCurrent == null ? null : targetCurrent.riskMark();
        if (mark == null || mark.status() != InvestmentDataCalculator.DataStatus.OK
                || mark.value() == null || mark.value().signum() <= 0 || mark.asOf() == null) {
            blockers.add("RISK_MARK_UNAVAILABLE");
        }

        var held = data.heldTickers();
        if (held.stream().anyMatch(ticker -> !completeCurrentRisk(currentRisk.get(ticker)))) {
            blockers.add("HELD_POSITION_RISK_INCOMPLETE");
        }
        var currentBudgetStatus = firstSoftBudgetStatus(currentRisk, held);
        if (currentBudgetStatus == null || "DATA_MISSING".equals(currentBudgetStatus)
                || "NOT_CONFIGURED".equals(currentBudgetStatus)) {
            blockers.add("CURRENT_RISK_BUDGET_UNAVAILABLE");
        } else if ("OVER_SOFT_BUDGET".equals(currentBudgetStatus)) {
            blockers.add("CURRENT_SOFT_BUDGET_EXCEEDED");
        } else if (!"WITHIN_SOFT_BUDGET".equals(currentBudgetStatus)) {
            blockers.add("CURRENT_RISK_BUDGET_UNAVAILABLE");
        }

        var policy = data.riskPolicy();
        var quantityWithinLimit = policy.maxQuantity() != null && input.quantity().compareTo(policy.maxQuantity()) <= 0;
        if (policy.maxQuantity() == null) blockers.add("POLICY_NOT_CONFIGURED");
        else if (!quantityWithinLimit) blockers.add("MAX_QUANTITY_EXCEEDED");
        var purchaseCost = mark == null || mark.value() == null ? null : input.quantity().multiply(mark.value());
        var maxOrderAmount = data.currency() == null ? null : maxOrderAmount(policy, data.currency());
        var orderAmountWithinLimit = purchaseCost != null && maxOrderAmount != null
                && purchaseCost.compareTo(maxOrderAmount) <= 0;
        if (maxOrderAmount == null && data.currency() != null) blockers.add("POLICY_NOT_CONFIGURED");
        else if (purchaseCost == null || data.currency() == null) blockers.add("ORDER_AMOUNT_UNEVALUATED");
        else if (!orderAmountWithinLimit) blockers.add("MAX_ORDER_AMOUNT_EXCEEDED");
        if (data.currency() == null) blockers.add("CURRENCY_MISSING");

        var concentrationStatus = concentrationStatus(proFormaRisk, held, input.ticker(), policy.maxConcentration());
        var concentrationWithinLimit = "WITHIN".equals(concentrationStatus);
        if ("POLICY_UNAVAILABLE".equals(concentrationStatus)) blockers.add("POLICY_NOT_CONFIGURED");
        else if ("DATA_MISSING".equals(concentrationStatus)) blockers.add("CONCENTRATION_UNEVALUATED");
        else if ("EXCEEDED".equals(concentrationStatus)) blockers.add("MAX_CONCENTRATION_EXCEEDED");

        if (policy.softRiskBudget() == null) blockers.add("POLICY_NOT_CONFIGURED");

        var proFormaBudgetStatus = firstSoftBudgetStatus(proFormaRisk, held, input.ticker());
        if (proFormaBudgetStatus == null || "DATA_MISSING".equals(proFormaBudgetStatus)
                || "NOT_CONFIGURED".equals(proFormaBudgetStatus)) {
            blockers.add("PROFORMA_RISK_BUDGET_UNAVAILABLE");
        } else if ("OVER_SOFT_BUDGET".equals(proFormaBudgetStatus)) {
            blockers.add("PROFORMA_SOFT_BUDGET_EXCEEDED");
        } else if (!"WITHIN_SOFT_BUDGET".equals(proFormaBudgetStatus)) {
            blockers.add("PROFORMA_RISK_BUDGET_UNAVAILABLE");
        }
        if (union(held, input.ticker()).stream().anyMatch(ticker ->
                !completeCurrentRisk(proFormaRisk.get(ticker)))) {
            blockers.add("PROFORMA_RISK_INCOMPLETE");
        }

        var cashBefore = data.currency() == null ? null : data.cashByCurrency().get(data.currency());
        var cashAfter = cashBefore == null || purchaseCost == null ? null : cashBefore.subtract(purchaseCost);
        if (cashBefore == null || purchaseCost == null) blockers.add("CASH_UNAVAILABLE");
        else if (purchaseCost != null && cashBefore.compareTo(purchaseCost) < 0) blockers.add("INSUFFICIENT_CASH");

        var portfolioGate = new PortfolioGate(
                blockers.contains("PORTFOLIO_RISK_UNAVAILABLE") || blockers.contains("HELD_POSITION_RISK_INCOMPLETE")
                        || blockers.contains("MANUAL_PORTFOLIO_STALE")
                        ? "BLOCKED" : "OK",
                portfolio.riskNumbersAvailable(), manualFresh,
                portfolio.manualStatus() == null ? "NOT_APPLICABLE" : portfolio.manualStatus(),
                portfolio.manualAsOf());
        var policyGate = new PolicyGate(
                policy.softRiskBudget() == null || policy.maxQuantity() == null || maxOrderAmount == null
                        || policy.maxConcentration() == null ? "POLICY_UNAVAILABLE"
                        : quantityWithinLimit && orderAmountWithinLimit && concentrationWithinLimit
                        ? "WITHIN_POLICY" : "BLOCKED",
                quantityWithinLimit, orderAmountWithinLimit, concentrationWithinLimit,
                policy.maxQuantity(), maxOrderAmount, policy.maxConcentration(), policy.softRiskBudget());
        var cashGate = new CashGate(cashBefore == null || purchaseCost == null ? "UNAVAILABLE"
                : cashAfter.signum() < 0 ? "INSUFFICIENT" : "SUFFICIENT",
                data.currency(), cashBefore, purchaseCost, cashAfter, data.cashSource());
        return new PreflightView(input.ticker(), input.quantity(), blockers.isEmpty(), List.copyOf(blockers),
                thesis, portfolioGate, policyGate, cashGate, currentRisk, proFormaRisk);
    }

    private ThesisReceipt thesisReceipt(UUID userId, String ticker, InvestmentContextService.ThesisView thesis) {
        if (thesis == null) return new ThesisReceipt("MISSING", null, null, null, null, null,
                null, null, null, false, false);
        var revisions = investment.thesisRevisions(userId, ticker, 1);
        var revision = revisions.isEmpty() ? null : revisions.getFirst();
        var receiptRequired = "AI_POLICY".equals(thesis.approvalActorType());
        var receipt = receiptRequired && thesis.verificationEventId() != null && thesisVerification != null
                ? thesisVerification.verificationReceipt(userId, ticker, thesis.verificationEventId()) : null;
        var matches = !receiptRequired || receipt != null
                && "AUTO_APPROVED".equals(receipt.outcome())
                && Objects.equals(receipt.verificationEventId(), thesis.verificationEventId())
                && Objects.equals(receipt.approvedRevisionId(), thesis.revisionId())
                && Objects.equals(receipt.approvedRevision(), thesis.revision())
                && Objects.equals(receipt.policyVersion(), thesis.policyVersion());
        return new ThesisReceipt(thesis.invalidationStatus(), thesis.revision(), thesis.revisionId(),
                thesis.approvalActorType(), revision == null ? null : revision.reason(), thesis.policyVersion(),
                thesis.verificationEventId(), receipt == null ? null : receipt.outcome(),
                receipt == null ? null : receipt.reasonCode(), receiptRequired, matches);
    }

    private static boolean completeCurrentRisk(InvestmentContextService.RiskContributionView risk) {
        return risk != null && risk.status() == InvestmentDataCalculator.DataStatus.OK
                && risk.portfolioWeight() != null && risk.invalidationDownside() != null
                && risk.plannedLossContribution() != null && risk.riskMark() != null
                && risk.riskMark().status() == InvestmentDataCalculator.DataStatus.OK;
    }

    private static String firstSoftBudgetStatus(Map<String, InvestmentContextService.RiskContributionView> risks,
                                                java.util.Set<String> held, String... extra) {
        var tickers = union(held, extra);
        return tickers.stream().map(risks::get).filter(Objects::nonNull)
                .map(InvestmentContextService.RiskContributionView::softBudgetStatus)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static String concentrationStatus(
            Map<String, InvestmentContextService.RiskContributionView> risks,
            java.util.Set<String> held, String target, BigDecimal limit) {
        if (limit == null) return "POLICY_UNAVAILABLE";
        var contributions = union(held, target).stream().map(risks::get).toList();
        if (contributions.stream().anyMatch(risk -> risk == null || risk.portfolioWeight() == null)) {
            return "DATA_MISSING";
        }
        if (contributions.stream().anyMatch(risk -> risk.portfolioWeight().compareTo(limit) > 0)) return "EXCEEDED";
        return "WITHIN";
    }

    private static java.util.Set<String> union(java.util.Set<String> held, String... extras) {
        var values = new LinkedHashSet<>(held);
        java.util.Collections.addAll(values, extras);
        return values;
    }

    private static BigDecimal maxOrderAmount(RiskPolicyService.RiskPolicySnapshot policy, String currency) {
        return switch (currency) {
            case "USD" -> policy.maxOrderAmountUsd();
            case "KRW" -> policy.maxOrderAmountKrw();
            default -> null;
        };
    }

    private static PreflightInput normalize(PreflightInput input) {
        if (input == null || input.quantity() == null || input.quantity().signum() <= 0) {
            throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        }
        var quantity = input.quantity().stripTrailingZeros();
        if (quantity.scale() > 10 || quantity.scale() < -18
                || (long) quantity.precision() - quantity.scale() > 18 || quantity.precision() > 28) {
            throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        }
        return new PreflightInput(InvestmentContextService.ticker(input.ticker()), quantity);
    }


    public record PreflightInput(String ticker, BigDecimal quantity) {
    }

    public record PreflightView(
            String ticker,
            BigDecimal quantity,
            boolean buyEligible,
            List<String> blockers,
            ThesisReceipt thesis,
            PortfolioGate portfolio,
            PolicyGate policy,
            CashGate cash,
            Map<String, InvestmentContextService.RiskContributionView> currentRisk,
            Map<String, InvestmentContextService.RiskContributionView> proFormaRisk
    ) {
        public PreflightView {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            currentRisk = currentRisk == null ? Map.of() : Map.copyOf(currentRisk);
            proFormaRisk = proFormaRisk == null ? Map.of() : Map.copyOf(proFormaRisk);
        }
    }

    public record ThesisReceipt(
            String invalidationStatus,
            Long revision,
            UUID revisionId,
            String actorType,
            String reason,
            String policyVersion,
            UUID verificationEventId,
            String outcome,
            String reasonCode,
            boolean receiptRequired,
            boolean receiptMatchesCurrentRevision
    ) {
    }

    public record PortfolioGate(
            String status,
            boolean riskNumbersAvailable,
            boolean manualFresh,
            String manualStatus,
            LocalDate manualAsOf
    ) {
    }

    public record PolicyGate(
            String status,
            boolean quantityWithinLimit,
            boolean orderAmountWithinLimit,
            boolean concentrationWithinLimit,
            BigDecimal maxQuantity,
            BigDecimal maxOrderAmount,
            BigDecimal maxConcentration,
            BigDecimal softRiskBudget
    ) {
    }

    public record CashGate(
            String status,
            String currency,
            BigDecimal availableBefore,
            BigDecimal purchaseCost,
            BigDecimal availableAfter,
            String source
    ) {
    }
}
