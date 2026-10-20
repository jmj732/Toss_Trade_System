package com.jmj.trade.investment;

import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import com.jmj.trade.connector.ConnectorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public final class InvestmentSellReadinessService {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final JdbcTemplate jdbc;
    private final PortfolioReadService portfolios;
    private final BrokerSurfaceService brokerSurface;
    private final ConnectorService connector;
    private final Environment environment;
    private final Clock clock;

    @Autowired
    public InvestmentSellReadinessService(JdbcTemplate jdbc, PortfolioReadService portfolios,
                                          BrokerSurfaceService brokerSurface, ConnectorService connector,
                                          Environment environment) {
        this(jdbc, portfolios, brokerSurface, connector, environment, Clock.systemUTC());
    }

    InvestmentSellReadinessService(JdbcTemplate jdbc, PortfolioReadService portfolios,
                                   BrokerSurfaceService brokerSurface, ConnectorService connector,
                                   Environment environment, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.portfolios = Objects.requireNonNull(portfolios);
        this.brokerSurface = Objects.requireNonNull(brokerSurface);
        this.connector = Objects.requireNonNull(connector);
        this.environment = Objects.requireNonNull(environment);
        this.clock = Objects.requireNonNull(clock);
    }

    public Readiness evaluate(UUID userId, UUID connectionId, UUID decisionId, UUID eventId) {
        return evaluate(userId, connectionId, decisionId, eventId, null);
    }

    public Readiness evaluate(UUID userId, UUID connectionId, UUID decisionId, UUID eventId,
                              UUID excludedPreparedOrderId) {
        if (userId == null || connectionId == null || decisionId == null || eventId == null)
            throw new IllegalArgumentException("decisionId and eventId are required");
        var now = clock.instant();
        var targets = jdbc.query("""
                SELECT d.asset, d.action, d.as_of, d.target_remaining_quantity,
                       d.target_broker_connection_id, l.thesis_revision_id,
                       current_revision.id AS current_revision_id,
                       current_state.state AS event_current_state,
                       current_state.evidence->>'eventId' AS active_event_id,
                       current_state.evidence->>'ticker' AS active_event_ticker,
                       current_state.observed_at AS event_observed_at,
                       COALESCE(current_state.evidence->'latestObservation'->>'thesisRevisionId',
                                current_state.evidence->>'thesisRevisionId') AS event_thesis_revision_id,
                       COALESCE(current_state.evidence->'latestObservation'->>'invalidationStatus',
                                current_state.evidence->>'invalidationStatus') AS event_invalidation_status,
                       COALESCE(current_state.evidence->'latestObservation'->>'reasonCode',
                                current_state.evidence->>'reasonCode') AS event_reason_code,
                       COALESCE(current_state.evidence->'latestObservation'->>'sourceAsOf',
                                current_state.evidence->>'sourceAsOf') AS event_source_as_of,
                       COALESCE(current_state.evidence->'latestObservation'->>'priceBasis',
                                current_state.evidence->>'priceBasis') AS event_price_basis,
                       COALESCE(current_state.evidence->'latestObservation'->>'priceSession',
                                current_state.evidence->>'priceSession') AS event_price_session,
                       COALESCE(current_state.evidence->'latestObservation'->>'priceSource',
                                current_state.evidence->>'priceSource') AS event_price_source,
                       COALESCE(current_state.evidence->'latestObservation'->>'observedPrice',
                                current_state.evidence->>'observedPrice') AS event_observed_price,
                       COALESCE(current_state.evidence->'latestObservation'->>'triggerPrice',
                                current_state.evidence->>'triggerPrice') AS event_trigger_price,
                       COALESCE(current_state.evidence->'latestObservation'->>'sessionDate',
                                current_state.evidence->>'sessionDate') AS event_session_date,
                       COALESCE(current_state.evidence->'latestObservation'->>'regularCloseSessionDate',
                                current_state.evidence->>'regularCloseSessionDate') AS event_regular_close_session_date,
                       COALESCE(current_state.evidence->'latestObservation'->>'riskMarkStatus',
                                current_state.evidence->>'riskMarkStatus') AS event_risk_mark_status,
                       EXISTS(SELECT 1 FROM investment_decision_ledger newer
                               WHERE newer.user_id = d.user_id AND newer.asset = d.asset
                                 AND newer.as_of > d.as_of
                                 AND (newer.target_broker_connection_id IS NULL
                                      OR newer.target_broker_connection_id = ?)) AS superseded
                  FROM investment_decision_ledger d
                  JOIN investment_risk_event_decision_links l
                    ON l.user_id = d.user_id AND l.decision_id = d.decision_id AND l.event_id = ?
                  LEFT JOIN LATERAL (
                      SELECT id FROM investment_thesis_revisions revision
                       WHERE revision.user_id = d.user_id AND revision.ticker = d.asset
                       ORDER BY revision.revision DESC LIMIT 1
                  ) current_revision ON TRUE
                  LEFT JOIN monitoring_current_states current_state
                    ON current_state.user_id = d.user_id AND current_state.scope = 'THESIS_REVIEW'
                   AND current_state.subject_key = d.asset
                 WHERE d.user_id = ? AND d.decision_id = ?
                """, (rs, row) -> new Target(rs.getString("asset"), rs.getString("action"),
                rs.getObject("as_of", java.time.OffsetDateTime.class).toInstant(),
                rs.getBigDecimal("target_remaining_quantity"),
                (UUID) rs.getObject("target_broker_connection_id"),
                (UUID) rs.getObject("thesis_revision_id"), (UUID) rs.getObject("current_revision_id"),
                rs.getString("event_current_state"), rs.getString("active_event_id"),
                rs.getString("active_event_ticker"),
                instant(rs.getObject("event_observed_at", java.time.OffsetDateTime.class)),
                rs.getString("event_thesis_revision_id"), rs.getString("event_invalidation_status"),
                rs.getString("event_reason_code"), rs.getString("event_source_as_of"),
                rs.getString("event_price_basis"), rs.getString("event_price_session"),
                rs.getString("event_price_source"), rs.getString("event_observed_price"),
                rs.getString("event_trigger_price"), rs.getString("event_session_date"),
                rs.getString("event_regular_close_session_date"), rs.getString("event_risk_mark_status"),
                rs.getBoolean("superseded")), connectionId, eventId, userId, decisionId);
        if (targets.isEmpty()) return blocked(decisionId, eventId, now, "ACTION", List.of("OWNED_DECISION_EVENT_LINK_NOT_FOUND"));
        var target = targets.getFirst();
        var reasons = new ArrayList<String>();
        if (target.asOf() != null && target.asOf().isAfter(now)) reasons.add("DECISION_AS_OF_IN_FUTURE");
        var maxDecisionAge = environment.getProperty("investment.execution.readiness.max-decision-age",
                Duration.class, Duration.ofMinutes(15));
        if (target.asOf() == null || maxDecisionAge == null || maxDecisionAge.isNegative()
                || maxDecisionAge.isZero()
                || Duration.between(target.asOf(), now).compareTo(maxDecisionAge) > 0)
            reasons.add("DECISION_STALE_OR_INVALID");
        var maxEventAge = environment.getProperty("investment.execution.readiness.max-risk-event-age",
                Duration.class, Duration.ofMinutes(15));
        if (target.eventObservedAt() == null || target.eventObservedAt().isAfter(now)
                || maxEventAge == null || maxEventAge.isNegative() || maxEventAge.isZero()
                || Duration.between(target.eventObservedAt(), now).compareTo(maxEventAge) > 0)
            reasons.add("RISK_EVENT_STALE_OR_INVALID");
        if (target.superseded()) reasons.add("DECISION_SUPERSEDED");
        if (!List.of("REDUCE", "EXIT").contains(target.action())) reasons.add("ACTION_NOT_REDUCE_OR_EXIT");
        if (target.remaining() == null) reasons.add("POLICY_NOT_CONFIGURED");
        if (!connectionId.equals(target.connectionId())) reasons.add("DECISION_ACCOUNT_MISMATCH");
        if (target.revision() == null || !target.revision().equals(target.currentRevision())) reasons.add("THESIS_REVISION_STALE");
        if (target.eventState() == null
                || !List.of("NEAR", "INTRADAY_BREACH", "PRICE_RISK_BREACH").contains(target.eventState())
                || target.eventId() == null
                || !eventId.toString().equalsIgnoreCase(target.eventId())
                || !target.ticker().equalsIgnoreCase(Objects.toString(target.eventTicker(), "")))
            reasons.add("RISK_EVENT_NOT_CURRENT");
        reasons.addAll(eventEvidenceBlockers(target, eventId, now));
        if (!reasons.isEmpty()) return blocked(decisionId, eventId, now, "ACTION", reasons);

        final PortfolioReadService.PortfolioView portfolio;
        try {
            portfolio = portfolios.read(userId, connectionId);
        } catch (RuntimeException exception) {
            return blocked(decisionId, eventId, now, "SIZING", List.of("ACCOUNT_SNAPSHOT_UNAVAILABLE"));
        }
        if (portfolio == null || portfolio.stale() || !accountLocalSellSnapshotUsable(portfolio)
                || portfolio.positions() == null)
            return blocked(decisionId, eventId, now, "SIZING", List.of("ACCOUNT_SNAPSHOT_STALE_OR_PARTIAL"));
        var matchingPositions = portfolio.positions().stream()
                .filter(item -> target.ticker().equalsIgnoreCase(item.symbol())).toList();
        if (matchingPositions.size() != 1 || matchingPositions.getFirst().quantity() == null
                || matchingPositions.getFirst().sellableQuantity() == null)
            return blocked(decisionId, eventId, now, "SIZING", List.of("POSITION_OR_SELLABLE_QUANTITY_MISSING"));
        var position = matchingPositions.getFirst();
        var maxPositionAge = environment.getProperty("portfolio.sync.stale-after", Duration.class,
                Duration.ofMinutes(15));
        if (position.observedAt() == null || position.observedAt().isAfter(now)
                || maxPositionAge == null || maxPositionAge.isNegative() || maxPositionAge.isZero()
                || Duration.between(position.observedAt(), now).compareTo(maxPositionAge) > 0)
            return blocked(decisionId, eventId, now, "SIZING", List.of("POSITION_OBSERVATION_MISSING_OR_INVALID"));
        var quantity = position.quantity().subtract(target.remaining());
        if (quantity.signum() <= 0)
            return blocked(decisionId, eventId, now, "SIZING", List.of("TARGET_DOES_NOT_REDUCE_POSITION"));
        if (quantity.compareTo(position.sellableQuantity()) > 0)
            return blocked(decisionId, eventId, now, "SIZING", List.of("TARGET_EXCEEDS_ACCOUNT_SELLABLE_QUANTITY"));
        if (quantity.stripTrailingZeros().scale() > 0)
            return blocked(decisionId, eventId, now, "SIZING", List.of("FRACTIONAL_LIMIT_SELL_UNSUPPORTED"));
        if (hasPreparedOrder(userId, connectionId, target.ticker(), excludedPreparedOrderId))
            return blockedKnown(decisionId, eventId, now, "PREPARE", List.of("PREPARED_ORDER_ALREADY_EXISTS"),
                    target, position.observedAt(), quantity);
        try {
            if (connector.orders(userId, connectionId, "OPEN").stream()
                    .anyMatch(order -> target.ticker().equalsIgnoreCase(order.symbol())))
                return blockedKnown(decisionId, eventId, now, "PREPARE", List.of("OPEN_ORDER_ALREADY_EXISTS"),
                        target, position.observedAt(), quantity);
        } catch (RuntimeException exception) {
            return blockedKnown(decisionId, eventId, now, "PREPARE", List.of("OPEN_ORDERS_UNAVAILABLE"),
                    target, position.observedAt(), quantity);
        }

        final BrokerSurfaceResponse<BrokerSurfaceResponse.OrderBookView> bookResponse;
        try {
            bookResponse = brokerSurface.orderBook(userId, connectionId, target.ticker());
        } catch (RuntimeException exception) {
            return blockedKnown(decisionId, eventId, now, "PRICING", List.of("ORDERBOOK_READ_FAILED"),
                    target, position.observedAt(), quantity);
        }
        if (bookResponse.unavailable() || bookResponse.data() == null)
            return blockedKnown(decisionId, eventId, now, "PRICING", List.of("ORDERBOOK_UNAVAILABLE"),
                    target, position.observedAt(), quantity);
        var book = bookResponse.data();
        var bookSource = bookResponse.provenance().stream()
                .filter(value -> "TOSS".equals(value.provider()) && "/api/v1/orderbook".equals(value.endpoint()))
                .findFirst().orElse(null);
        var maxAge = environment.getProperty("investment.execution.readiness.max-quote-age",
                Duration.class, Duration.ofSeconds(30));
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative())
            reasons.add("MAX_QUOTE_AGE_POLICY_INVALID");
        if (bookResponse.status() != BrokerSurfaceResponse.Status.AVAILABLE) reasons.add("ORDERBOOK_PARTIAL");
        if (!target.ticker().equalsIgnoreCase(book.symbol()) || !"USD".equalsIgnoreCase(book.currency()))
            reasons.add("ORDERBOOK_SYMBOL_OR_CURRENCY_MISMATCH");
        if (book.timestamp() == null || bookSource == null || bookSource.observedAt() == null) {
            reasons.add("ORDERBOOK_PROVENANCE_MISSING");
        } else if (maxAge == null || maxAge.isZero() || maxAge.isNegative()
                || book.timestamp().isAfter(now) || bookSource.observedAt().isAfter(now)
                || bookSource.observedAt().isBefore(book.timestamp())
                || Duration.between(book.timestamp(), now).compareTo(maxAge) > 0
                || Duration.between(bookSource.observedAt(), now).compareTo(maxAge) > 0) {
            reasons.add("ORDERBOOK_STALE_OR_TIME_CONFLICT");
        }
        var bid = safeLevels(book.bids()).stream().filter(InvestmentSellReadinessService::hasDisplayedDepth)
                .map(BrokerSurfaceResponse.LevelView::price)
                .max(BigDecimal::compareTo).orElse(null);
        var ask = safeLevels(book.asks()).stream().filter(InvestmentSellReadinessService::hasDisplayedDepth)
                .map(BrokerSurfaceResponse.LevelView::price)
                .min(BigDecimal::compareTo).orElse(null);
        if (bid == null || ask == null || bid.signum() <= 0 || ask.signum() <= 0 || ask.compareTo(bid) < 0)
            reasons.add("VALID_BID_ASK_MISSING");
        var calendarDate = book.timestamp() == null ? null : book.timestamp().atZone(NEW_YORK).toLocalDate();
        final BrokerSurfaceResponse<BrokerSurfaceResponse.MarketCalendarView> calendar;
        try {
            calendar = calendarDate == null ? null
                    : brokerSurface.marketCalendar(userId, connectionId, "US", calendarDate);
        } catch (RuntimeException exception) {
            return blocked(decisionId, eventId, now, "PRICING", List.of("MARKET_CALENDAR_READ_FAILED"),
                    target, position.observedAt(), quantity, bid, ask, null,
                    bid == null || ask == null ? null : ask.subtract(bid), book.timestamp(),
                    bookSource == null ? null : bookSource.observedAt(), null,
                    bookSource == null ? null : bookSource.provider(), book.currency(), null, null, null);
        }
        var calendarSourceName = calendar == null ? null : calendar.provenance().stream()
                .filter(value -> "TOSS".equals(value.provider()) && value.endpoint() != null
                        && value.endpoint().startsWith("/api/v1/market-calendar/US"))
                .map(value -> value.provider() + value.endpoint()).findFirst().orElse(null);
        var verifiedRegularSession = false;
        if (calendar == null || calendar.unavailable() || calendar.data() == null
                || calendar.status() != BrokerSurfaceResponse.Status.AVAILABLE) {
            reasons.add("MARKET_CALENDAR_UNVERIFIED");
        } else {
            var calendarSource = calendar.provenance().stream()
                    .filter(value -> "TOSS".equals(value.provider()) && value.endpoint() != null
                            && value.endpoint().startsWith("/api/v1/market-calendar/US"))
                    .findFirst().orElse(null);
            var currentDate = now.atZone(NEW_YORK).toLocalDate();
            if (!calendarDate.equals(currentDate)
                    || calendarSource == null || calendarSource.observedAt() == null
                    || calendarSource.observedAt().isAfter(now)
                    || maxAge == null || maxAge.isZero() || maxAge.isNegative()
                    || Duration.between(calendarSource.observedAt(), now).compareTo(maxAge) > 0)
                reasons.add("MARKET_CALENDAR_STALE_OR_UNVERIFIED");
            else {
                var bookSession = InvestmentContextService.tossSession(
                        book.timestamp(), calendarDate, calendar.data().payload());
                var currentSession = InvestmentContextService.tossSession(
                        now, currentDate, calendar.data().payload());
                if (!"LIVE_REGULAR".equals(bookSession) || !"LIVE_REGULAR".equals(currentSession))
                    reasons.add("OUTSIDE_VERIFIED_REGULAR_SESSION");
                else verifiedRegularSession = true;
            }
        }
        BigDecimal spread = null;
        if (bid != null && ask != null && bid.signum() > 0 && ask.compareTo(bid) >= 0) {
            var midpoint = bid.add(ask).divide(BigDecimal.valueOf(2), 12, RoundingMode.HALF_UP);
            spread = ask.subtract(bid).multiply(BigDecimal.valueOf(10_000))
                    .divide(midpoint, 6, RoundingMode.HALF_UP);
            var maximum = environment.getProperty("investment.execution.readiness.max-spread-bps", BigDecimal.class);
            if (maximum == null || maximum.signum() <= 0) reasons.add("MAX_SPREAD_POLICY_UNCONFIGURED");
            else if (spread.compareTo(maximum) > 0) reasons.add("SPREAD_EXCEEDS_POLICY");
        }
        var increment = environment.getProperty(
                "investment.execution.readiness.price-increments." + target.ticker(), BigDecimal.class);
        var incrementSource = environment.getProperty("investment.execution.readiness.price-increment-source", "");
        var incrementVerified = environment.getProperty(
                "investment.execution.readiness.price-increment-verified", Boolean.class, false);
        if (!incrementVerified || increment == null || increment.signum() <= 0
                || !incrementSource.startsWith("https://"))
            reasons.add("PRICE_INCREMENT_UNVERIFIED");
        else if (bid != null && bid.remainder(increment).signum() != 0)
            reasons.add("BID_NOT_ALIGNED_TO_PRICE_INCREMENT");
        var absoluteSpread = bid == null || ask == null ? null : ask.subtract(bid);
        if (!reasons.isEmpty()) return blocked(decisionId, eventId, now, "PRICING", reasons,
                target, position.observedAt(), quantity, bid, ask, spread, absoluteSpread, book.timestamp(),
                bookSource == null ? null : bookSource.observedAt(), verifiedRegularSession ? "LIVE_REGULAR" : null,
                bookSource == null ? null : bookSource.provider(), book.currency(), calendarSourceName,
                increment, incrementSource);
        return new Readiness("READY", "READY", "READY", "READY", decisionId, eventId,
                target.ticker(), target.action(), target.asOf(), target.revision(), quantity, target.remaining(),
                position.observedAt(), bid, ask, bid, absoluteSpread, spread, "LIVE_REGULAR", "TOSS",
                book.currency(), book.timestamp(), bookSource.observedAt(), calendarSourceName,
                incrementSource, increment, List.of(), now);
    }

    private boolean hasPreparedOrder(UUID userId, UUID connectionId, String ticker, UUID excludedOrderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM order_intents
                WHERE user_id = ? AND broker_connection_id = ? AND upper(symbol) = ?
                   AND (CAST(? AS uuid) IS NULL OR id <> CAST(? AS uuid))
                   AND (status <> 'PROPOSED' OR expires_at IS NULL OR expires_at > now())
                   AND status IN ('PROPOSED','APPROVED','REVALIDATING','SUBMISSION_PENDING',
                                  'RECONCILIATION_REQUIRED','MANUAL_REVIEW_REQUIRED','ACTIVE','PARTIALLY_COMPLETED'))
                """, Boolean.class, userId, connectionId, ticker, excludedOrderId, excludedOrderId));
    }

    private static Readiness blocked(UUID decisionId, UUID eventId, Instant now, String stage, List<String> reasons) {
        return blocked(decisionId, eventId, now, stage, reasons, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private static Readiness blockedKnown(UUID decisionId, UUID eventId, Instant now, String stage,
                                          List<String> reasons, Target target, Instant positionAsOf,
                                          BigDecimal quantity) {
        return blocked(decisionId, eventId, now, stage, reasons, target, positionAsOf, quantity,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static Readiness blocked(UUID decisionId, UUID eventId, Instant now, String stage, List<String> reasons,
                                     Target target, Instant positionAsOf, BigDecimal quantity, BigDecimal bid,
                                     BigDecimal ask, BigDecimal spread, BigDecimal absoluteSpread,
                                     Instant bookAsOf, Instant observedAt, String priceSession,
                                     String priceSource, String currency, String marketCalendarSource,
                                     BigDecimal increment, String incrementSource) {
        var actionStatus = "ACTION".equals(stage) ? "BLOCKED" : "READY";
        var sizingStatus = "ACTION".equals(stage) ? "NOT_EVALUATED"
                : "SIZING".equals(stage) ? "BLOCKED" : "READY";
        var pricingStatus = "PRICING".equals(stage) ? "BLOCKED"
                : List.of("ACTION", "SIZING", "PREPARE").contains(stage) ? "NOT_EVALUATED" : "READY";
        var prepareStatus = "PREPARE".equals(stage) ? "BLOCKED" : "NOT_EVALUATED";
        return new Readiness(actionStatus, sizingStatus, pricingStatus, prepareStatus, decisionId, eventId,
                target == null ? null : target.ticker(), target == null ? null : target.action(),
                target == null ? null : target.asOf(), target == null ? null : target.revision(), quantity,
                target == null ? null : target.remaining(), positionAsOf, bid, ask, bid, absoluteSpread, spread,
                priceSession, priceSource, currency, bookAsOf, observedAt, marketCalendarSource,
                incrementSource, increment, reasons, now);
    }

    private static Instant instant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static List<BrokerSurfaceResponse.LevelView> safeLevels(List<BrokerSurfaceResponse.LevelView> levels) {
        return levels == null ? List.of() : levels;
    }

    private static boolean hasDisplayedDepth(BrokerSurfaceResponse.LevelView level) {
        return level != null && level.price() != null && level.price().signum() > 0
                && level.volume() != null && level.volume().signum() > 0;
    }

    /** Allows only account-summary/buying-power omissions for account-local defensive selling. */
    public static boolean accountLocalSellSnapshotUsable(PortfolioReadService.PortfolioView portfolio) {
        if (portfolio == null) return false;
        if (!portfolio.partial()) return true;
        var missing = portfolio.missingSections();
        return missing != null && !missing.isEmpty() && missing.stream().allMatch(Set.of(
                "ACCOUNT", "BUYING_POWER_KRW", "BUYING_POWER_USD")::contains);
    }

    private static List<String> eventEvidenceBlockers(Target target, UUID eventId, Instant now) {
        var blockers = new ArrayList<String>();
        try {
            var evidenceAsOf = target.eventSourceAsOf() == null ? null : Instant.parse(target.eventSourceAsOf());
            var observedPrice = new BigDecimal(Objects.toString(target.eventObservedPrice(), ""));
            var triggerPrice = new BigDecimal(Objects.toString(target.eventTriggerPrice(), ""));
            var expectedReason = switch (target.eventState()) {
                case "NEAR" -> "APPROVED_TRIGGER_NEAR";
                case "INTRADAY_BREACH" -> "APPROVED_TRIGGER_INTRADAY_BREACHED";
                case "PRICE_RISK_BREACH" -> "APPROVED_TRIGGER_CLOSE_BREACHED";
                default -> null;
            };
            // Keep this aligned with MonitoringThesisTriggerDetector.NEAR_BAND_MULTIPLIER (1.03).
            var validPriceRelation = "NEAR".equals(target.eventState())
                    ? observedPrice.compareTo(triggerPrice) > 0
                        && observedPrice.compareTo(triggerPrice.multiply(new BigDecimal("1.03"))) <= 0
                    : observedPrice.compareTo(triggerPrice) <= 0;
            if (evidenceAsOf == null || evidenceAsOf.isAfter(now)
                    || !target.revision().toString().equalsIgnoreCase(target.eventThesisRevisionId())
                    || !eventId.toString().equalsIgnoreCase(target.eventId())
                    || !"TOSS".equals(target.eventPriceSource())
                    || !"CONFIRMED".equals(target.eventInvalidationStatus())
                    || !Objects.equals(expectedReason, target.eventReasonCode())
                    || observedPrice.signum() <= 0 || triggerPrice.signum() <= 0
                    || !validPriceRelation) {
                blockers.add("RISK_EVENT_EVIDENCE_UNVERIFIED");
                return blockers;
            }
            if ("INTRADAY_BREACH".equals(target.eventState())) {
                if (!"LIVE_REGULAR".equals(target.eventPriceBasis())
                        || !"LIVE_REGULAR".equals(target.eventPriceSession()))
                    blockers.add("RISK_EVENT_EVIDENCE_UNVERIFIED");
            } else if ("PRICE_RISK_BREACH".equals(target.eventState())) {
                var closeDate = target.eventRegularCloseSessionDate();
                var sessionDate = target.eventSessionDate();
                var label = evidenceAsOf.atZone(NEW_YORK);
                if (!"REGULAR_CLOSE".equals(target.eventPriceBasis())
                        || !"REGULAR_CLOSE".equals(target.eventPriceSession())
                        || !"TOSS".equals(target.eventPriceSource())
                        || closeDate == null || !closeDate.equals(sessionDate)
                        || !closeDate.equals(label.toLocalDate()) || !label.toLocalTime().equals(java.time.LocalTime.MIDNIGHT))
                    blockers.add("RISK_EVENT_EVIDENCE_UNVERIFIED");
            } else if (!(("LIVE_REGULAR".equals(target.eventPriceBasis())
                    && "LIVE_REGULAR".equals(target.eventPriceSession()))
                    || ("REGULAR_CLOSE".equals(target.eventPriceBasis())
                    && "REGULAR_CLOSE".equals(target.eventPriceSession())
                    && "TOSS".equals(target.eventPriceSource())))) {
                blockers.add("RISK_EVENT_EVIDENCE_UNVERIFIED");
            }
        } catch (RuntimeException exception) {
            blockers.add("RISK_EVENT_EVIDENCE_UNVERIFIED");
        }
        return blockers;
    }

    private record Target(String ticker, String action, Instant asOf, BigDecimal remaining, UUID connectionId,
                          UUID revision, UUID currentRevision, String eventState, String eventId,
                          String eventTicker, Instant eventObservedAt, String eventThesisRevisionId,
                          String eventInvalidationStatus, String eventReasonCode, String eventSourceAsOf,
                          String eventPriceBasis, String eventPriceSession, String eventPriceSource,
                          String eventObservedPrice, String eventTriggerPrice, String eventSessionDate,
                          String eventRegularCloseSessionDate, String eventRiskMarkStatus, boolean superseded) {}

    public record Readiness(String actionStatus, String sizingStatus, String pricingStatus, String prepareStatus,
                            UUID decisionId, UUID eventId, String ticker, String action, Instant decisionAsOf,
                            UUID thesisRevisionId,
                            BigDecimal quantity, BigDecimal targetRemainingQuantity, Instant positionAsOf,
                            BigDecimal bid, BigDecimal ask, BigDecimal limitPrice, BigDecimal absoluteSpread,
                            BigDecimal spreadBps, String priceSession, String priceSource, String currency,
                            Instant orderBookAsOf, Instant orderBookObservedAt, String marketCalendarSource,
                            String priceIncrementSource, BigDecimal priceIncrement,
                            List<String> blockers, Instant asOf) {
        public Readiness { blockers = List.copyOf(blockers == null ? List.of() : blockers); }
    }
}
