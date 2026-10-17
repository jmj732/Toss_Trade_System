package com.jmj.trade.monitoring;

import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.investment.InvestmentDataCalculator.DataStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;

/**
 * Flags held positions whose CONFIRMED thesis invalidation trigger is breached or nearly breached by a trusted
 * regular-session price, and asks the user to re-review the thesis through the monitoring alert outbox.
 *
 * <p>This is a review prompt only. It never changes thesis status or trigger, never writes thesis revisions,
 * and never creates, submits or cancels orders. A price move alone never invalidates a thesis. Unapproved
 * triggers (AI_PROPOSED/UNVERIFIED/INVALIDATION_UNDEFINED) are never considered: the canonical
 * {@code investment_thesis_states} read only returns CONFIRMED triggers.
 *
 * <p>Each real state transition is persisted in the monitoring history and emitted to the existing notification
 * outbox in one transaction. Repeated evaluations of the same state update only the current observation; they do
 * not create another history event or Telegram notification.
 */
@Service
class MonitoringThesisTriggerDetector {

    static final String SCOPE = "THESIS_REVIEW";
    /** NEAR: trigger &lt; price &le; trigger × 1.03 (within 3% above the trigger). BREACH: price &le; trigger. */
    static final BigDecimal NEAR_BAND_MULTIPLIER = new BigDecimal("1.03");
    private static final String LIVE_REGULAR = "LIVE_REGULAR";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final InvestmentContextService investmentContext;
    private final InvestmentRiskEventService riskEvents;

    MonitoringThesisTriggerDetector(InvestmentContextService investmentContext,
                                    InvestmentRiskEventService riskEvents) {
        this.investmentContext = Objects.requireNonNull(investmentContext, "investmentContext");
        this.riskEvents = Objects.requireNonNull(riskEvents, "riskEvents");
    }

    /**
     * Held = a position with positive quantity in the fresh monitoring holdings snapshot. A stale or missing
     * snapshot ({@code asOf == null}) yields no held set and therefore no alert; tickers that are not held are
     * never alerted.
     */
    void detect(UUID userId, MonitoringEvaluationContract.PortfolioInput portfolio) {
        Objects.requireNonNull(userId, "userId");
        if (portfolio == null || portfolio.asOf() == null || portfolio.positions() == null) return;
        var held = new LinkedHashSet<String>();
        for (var position : portfolio.positions()) {
            if (position.symbol() != null && position.quantity() != null && position.quantity().signum() > 0) {
                held.add(position.symbol());
            }
        }
        if (held.isEmpty()) return;
        var evaluatedAt = Instant.now();
        for (var observation : investmentContext.confirmedTriggerObservations(userId, held)) {
            var trigger = observation.triggerPrice();
            var price = trustedAlertPrice(observation);
            if (price == null || trigger == null || trigger.signum() <= 0) continue;
            var targetState = targetState(price, trigger);
            var reasonCode = switch (targetState) {
                case "NEAR" -> "APPROVED_TRIGGER_NEAR";
                case "INTRADAY_BREACH" -> "APPROVED_TRIGGER_INTRADAY_BREACHED";
                case "PRICE_RISK_BREACH" -> "APPROVED_TRIGGER_CLOSE_BREACHED";
                default -> "PRICE_CLEARED_NEAR_BAND";
            };
            var evidence = new java.util.LinkedHashMap<String, Object>();
            evidence.put("sourceAsOf", price.asOf().toString());
            evidence.put("priceSource", price.source());
            evidence.put("priceSession", price.session());
            evidence.put("priceBasis", price.basis());
            evidence.put("observedPrice", price.value());
            evidence.put("triggerPrice", trigger);
            evidence.put("regularCloseSessionDate", observation.regularCloseSessionDate());
            evidence.put("riskMarkStatus", observation.riskMarkStatus() == null
                    ? null : observation.riskMarkStatus().name());
            evidence.put("riskMarkReason", observation.riskMarkReason());
            evidence.put("thesisRevisionId", observation.thesisRevisionId() == null
                    ? null : observation.thesisRevisionId().toString());
            evidence.put("thesisRevision", observation.thesisRevision());
            evidence.put("invalidationStatus", observation.invalidationStatus());
            evidence.put("reasonCode", reasonCode);
            evidence.put("sessionDate", LocalDate.ofInstant(price.asOf(), NEW_YORK).toString());
            riskEvents.recordObservation(userId, observation.ticker(), targetState, evidence, evaluatedAt);
        }
    }

    /**
     * Uses only a trusted live regular-session quote or the verified close for the provider's last completed session.
     * The close path is for a review alert only; it does not make the risk mark or sizing inputs available.
     */
    static AlertPrice trustedAlertPrice(InvestmentContextService.ConfirmedTriggerObservation observation) {
        if (observation == null) return null;
        if (observation.priceStatus() == DataStatus.OK && observation.latestPrice() != null
                && observation.latestPrice().signum() > 0 && observation.latestPriceAsOf() != null
                && LIVE_REGULAR.equals(observation.priceSession())) {
            return new AlertPrice(observation.latestPrice(), observation.latestPriceAsOf(), LIVE_REGULAR,
                    observation.priceSource(), LIVE_REGULAR,
                    LocalDate.ofInstant(observation.latestPriceAsOf(), NEW_YORK));
        }
        var closeAsOf = observation.regularCloseAsOf();
        var closeDate = observation.regularCloseSessionDate();
        if (observation.regularCloseStatus() != DataStatus.OK || observation.regularClose() == null
                || observation.regularClose().signum() <= 0 || closeAsOf == null
                || !"TOSS".equals(observation.regularCloseSource()) || closeDate == null
                || !closeDate.equals(observation.lastCompletedSessionDate())) return null;
        var closeLabel = closeAsOf.atZone(NEW_YORK);
        if (!closeDate.equals(closeLabel.toLocalDate()) || !LocalTime.MIDNIGHT.equals(closeLabel.toLocalTime())) {
            return null;
        }
        return new AlertPrice(observation.regularClose(), closeAsOf, "REGULAR_CLOSE",
                observation.regularCloseSource(), "REGULAR_CLOSE", closeDate);
    }

    record AlertPrice(BigDecimal value, Instant asOf, String basis, String source,
                      String session, LocalDate sessionDate) {
    }

    static String targetState(AlertPrice price, BigDecimal trigger) {
        if (price.value().compareTo(trigger) <= 0) {
            return LIVE_REGULAR.equals(price.basis()) ? "INTRADAY_BREACH" : "PRICE_RISK_BREACH";
        }
        if (price.value().compareTo(trigger.multiply(NEAR_BAND_MULTIPLIER)) <= 0) return "NEAR";
        return "NORMAL";
    }
}
