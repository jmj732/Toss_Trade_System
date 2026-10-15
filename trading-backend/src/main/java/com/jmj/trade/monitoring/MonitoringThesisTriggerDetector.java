package com.jmj.trade.monitoring;

import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.investment.InvestmentDataCalculator.DataStatus;
import com.jmj.trade.notification.NotificationEventType;
import com.jmj.trade.notification.NotificationOutboxWriter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
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
 * <p>Dedup: one alert per user + ticker + trigger + level + New York session date of the price, via the
 * deterministic outbox {@code source_id} and the existing {@code ON CONFLICT (event_type, source_id) DO NOTHING}.
 */
@Service
class MonitoringThesisTriggerDetector {

    static final String SCOPE = "THESIS_REVIEW";
    static final String REVIEW_MESSAGE = "투자 논리 재검토 필요 — 자동 무효화·주문 없음";
    /** NEAR: trigger &lt; price &le; trigger × 1.03 (within 3% above the trigger). BREACH: price &le; trigger. */
    static final BigDecimal NEAR_BAND_MULTIPLIER = new BigDecimal("1.03");
    private static final String LIVE_REGULAR = "LIVE_REGULAR";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final InvestmentContextService investmentContext;
    private final NotificationOutboxWriter notifications;

    MonitoringThesisTriggerDetector(InvestmentContextService investmentContext,
                                    NotificationOutboxWriter notifications) {
        this.investmentContext = Objects.requireNonNull(investmentContext, "investmentContext");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
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
        for (var observation : investmentContext.confirmedTriggerObservations(userId, held)) {
            var trigger = observation.triggerPrice();
            var price = trustedAlertPrice(observation);
            if (price == null || trigger == null || trigger.signum() <= 0) continue;
            var level = level(price.value(), trigger);
            if (level == null) continue;
            var sessionDate = LocalDate.ofInstant(price.asOf(), NEW_YORK);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("scope", SCOPE);
            payload.put("alertType", "REVIEW");
            payload.put("subjectKey", observation.ticker());
            payload.put("level", level);
            payload.put("distancePct", distancePct(price.value(), trigger));
            payload.put("priceBasis", price.basis());
            payload.put("sessionDate", sessionDate.toString());
            payload.put("thesisStatusChanged", false);
            payload.put("orderAction", "NONE");
            payload.put("message", REVIEW_MESSAGE);
            notifications.emit(userId, NotificationEventType.MONITORING_ALERT,
                    sourceId(userId, observation.ticker(), trigger, level, sessionDate), payload,
                    price.asOf());
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
            return new AlertPrice(observation.latestPrice(), observation.latestPriceAsOf(), LIVE_REGULAR);
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
        return new AlertPrice(observation.regularClose(), closeAsOf, "REGULAR_CLOSE");
    }

    record AlertPrice(BigDecimal value, Instant asOf, String basis) {
    }

    static String level(BigDecimal price, BigDecimal trigger) {
        if (price.compareTo(trigger) <= 0) return "BREACH";
        if (price.compareTo(trigger.multiply(NEAR_BAND_MULTIPLIER)) <= 0) return "NEAR";
        return null;
    }

    /** Signed distance of the price from the trigger, as a percentage of the trigger, 2 decimals. */
    static String distancePct(BigDecimal price, BigDecimal trigger) {
        var value = price.subtract(trigger).multiply(HUNDRED).divide(trigger, 2, RoundingMode.HALF_UP);
        return (value.signum() > 0 ? "+" : "") + value.toPlainString();
    }

    private static UUID sourceId(UUID userId, String ticker, BigDecimal trigger, String level, LocalDate sessionDate) {
        var key = String.join("|", "THESIS_TRIGGER_REVIEW", userId.toString(), ticker,
                trigger.stripTrailingZeros().toPlainString(), level, sessionDate.toString());
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
