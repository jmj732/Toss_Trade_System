package com.jmj.trade.investment;

import com.jmj.trade.investment.tactical.TacticalOverlayService;
import com.jmj.trade.investment.tactical.TacticalOverlayService.StoredDailyBar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Computes numeric invalidation-trigger <em>candidates</em> from already stored daily bars. It never generates
 * thesis text, ThemeId, EntrySetup, InitialRiskPrice or AVWAP anchors and never fetches data: bars come from
 * {@link TacticalOverlayService#storedDailyBars} and price facts from the latest stored security snapshot.
 *
 * <ul>
 *   <li>Completed bars only: {@code bar_date < today(America/New_York)}.</li>
 *   <li>COMPUTED_ATR: ATR(14, Wilder) over the last 60 completed bars. TR_i = max(H_i-L_i, |H_i-C_{i-1}|,
 *       |L_i-C_{i-1}|) exists from the window's second bar (it needs a prior close); ATR is seeded with the simple
 *       mean of the first 14 TRs, then ATR_t = (13*ATR_{t-1} + TR_t)/14. Fewer than 15 bars (14 TRs) is
 *       INSUFFICIENT_HISTORY. Candidate = lastClose - 2*ATR, DECIMAL128, setScale(4, FLOOR) only at the end.</li>
 *   <li>COMPUTED_SUPPORT: lowest low of the last 20 completed bars; fewer than 20 is INSUFFICIENT_HISTORY.</li>
 *   <li>Price freshness: the refreshed quote passes when its status is OK with an as-of (basis QUOTE, price date =
 *       quote as-of in New York); otherwise the separately refreshed regular close passes when its status is OK
 *       with a session date (basis REGULAR_CLOSE, price date = that session date), so candidates also exist
 *       outside live regular hours. The regular close only gates freshness: it is never copied into the quote and
 *       the candidate math always uses the stored completed bars. The basis is recorded as
 *       {@code priceFreshnessBasis}.</li>
 *   <li>UNVERIFIED (no candidate) when a window bar has a source conflict (SOURCE_CONFLICT), neither price basis
 *       passes (by quote status: STALE is PRICE_STALE, SOURCE_CONFLICT is PRICE_SOURCE_CONFLICT, DATA_MISSING or
 *       absent is PRICE_MISSING, anything else is PRICE_UNVERIFIED), the last completed bar is more than 4
 *       calendar days older than the price date (STALE_BARS; a weekend/holiday heuristic, not an exchange
 *       calendar), the window has no source as-of, or the candidate is &lt;= 0 or &gt;= lastClose
 *       (CANDIDATE_OUT_OF_RANGE).</li>
 * </ul>
 * Prices are stored as captured (UNADJUSTED); splits inside the window are not corrected.
 */
@Service
public class ThesisCandidateGenerator {

    public static final String COMPUTED_ATR = "COMPUTED_ATR";
    public static final String COMPUTED_SUPPORT = "COMPUTED_SUPPORT";
    static final int ATR_PERIOD = 14;
    static final int ATR_WINDOW = 60;
    static final int SUPPORT_WINDOW = 20;
    static final BigDecimal ATR_MULTIPLIER = BigDecimal.valueOf(2);
    static final long STALE_BAR_DAYS = 4;
    static final String BASIS_QUOTE = "QUOTE";
    static final String BASIS_REGULAR_CLOSE = "REGULAR_CLOSE";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final TacticalOverlayService tactical;
    private final InvestmentContextService investment;
    private final Clock clock;

    @Autowired
    public ThesisCandidateGenerator(TacticalOverlayService tactical, InvestmentContextService investment) {
        this(tactical, investment, Clock.systemUTC());
    }

    ThesisCandidateGenerator(TacticalOverlayService tactical, InvestmentContextService investment, Clock clock) {
        this.tactical = Objects.requireNonNull(tactical, "tactical");
        this.investment = Objects.requireNonNull(investment, "investment");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Both computed candidates for the caller's ticker, keyed by candidate source; read-only. */
    public Map<String, Candidate> candidates(UUID userId, String ticker) {
        var bars = tactical.storedDailyBars(userId, ticker);
        var price = investment.priceFacts(userId, ticker);
        var now = clock.instant();
        var today = LocalDate.ofInstant(now, NEW_YORK);
        var result = new LinkedHashMap<String, Candidate>();
        result.put(COMPUTED_ATR, atrCandidate(bars, price, today, now));
        result.put(COMPUTED_SUPPORT, supportCandidate(bars, price, today, now));
        return result;
    }

    static Candidate atrCandidate(List<StoredDailyBar> bars, InvestmentContextService.PriceFacts price,
                                  LocalDate today, Instant now) {
        var completed = completed(bars, today);
        var window = tail(completed, ATR_WINDOW);
        var inputs = baseInputs("ATR14_WILDER_X2", window, price, today);
        inputs.put("atrPeriod", ATR_PERIOD);
        inputs.put("atrMultiplier", ATR_MULTIPLIER.toPlainString());
        inputs.put("windowMaxBars", ATR_WINDOW);
        if (window.size() < ATR_PERIOD + 1) return Candidate.unverified(COMPUTED_ATR, "INSUFFICIENT_HISTORY", inputs);
        var blocked = blockedReason(window, price, now);
        if (blocked != null) return Candidate.unverified(COMPUTED_ATR, blocked, inputs);
        var atr = wilderAtr(window);
        var lastClose = window.getLast().close();
        inputs.put("atr", atr.setScale(8, RoundingMode.HALF_UP).toPlainString());
        var candidate = lastClose.subtract(ATR_MULTIPLIER.multiply(atr, MathContext.DECIMAL128), MathContext.DECIMAL128)
                .setScale(4, RoundingMode.FLOOR);
        return finish(COMPUTED_ATR, candidate, lastClose, window, inputs);
    }

    static Candidate supportCandidate(List<StoredDailyBar> bars, InvestmentContextService.PriceFacts price,
                                      LocalDate today, Instant now) {
        var completed = completed(bars, today);
        var window = tail(completed, SUPPORT_WINDOW);
        var inputs = baseInputs("SUPPORT_LOW_20", window, price, today);
        inputs.put("windowMaxBars", SUPPORT_WINDOW);
        if (window.size() < SUPPORT_WINDOW) {
            return Candidate.unverified(COMPUTED_SUPPORT, "INSUFFICIENT_HISTORY", inputs);
        }
        var blocked = blockedReason(window, price, now);
        if (blocked != null) return Candidate.unverified(COMPUTED_SUPPORT, blocked, inputs);
        var support = window.stream().map(StoredDailyBar::low).reduce(BigDecimal::min).orElseThrow();
        inputs.put("lowestLow", support.toPlainString());
        return finish(COMPUTED_SUPPORT, support.setScale(4, RoundingMode.FLOOR), window.getLast().close(),
                window, inputs);
    }

    /** ATR(14, Wilder) over a chronological window of at least 15 bars; exact DECIMAL128 arithmetic. */
    static BigDecimal wilderAtr(List<StoredDailyBar> window) {
        if (window.size() < ATR_PERIOD + 1) throw new IllegalArgumentException("ATR needs 15 bars");
        var period = BigDecimal.valueOf(ATR_PERIOD);
        var seedSum = BigDecimal.ZERO;
        for (int i = 1; i <= ATR_PERIOD; i++) seedSum = seedSum.add(trueRange(window.get(i), window.get(i - 1)));
        var atr = seedSum.divide(period, MathContext.DECIMAL128);
        var smoothing = BigDecimal.valueOf(ATR_PERIOD - 1);
        for (int i = ATR_PERIOD + 1; i < window.size(); i++) {
            atr = atr.multiply(smoothing).add(trueRange(window.get(i), window.get(i - 1)))
                    .divide(period, MathContext.DECIMAL128);
        }
        return atr;
    }

    static BigDecimal trueRange(StoredDailyBar bar, StoredDailyBar previous) {
        var priorClose = previous.close();
        return bar.high().subtract(bar.low())
                .max(bar.high().subtract(priorClose).abs())
                .max(bar.low().subtract(priorClose).abs());
    }

    private static Candidate finish(String source, BigDecimal candidate, BigDecimal lastClose,
                                    List<StoredDailyBar> window, Map<String, Object> inputs) {
        inputs.put("lastClose", lastClose.toPlainString());
        if (candidate.signum() <= 0 || candidate.compareTo(lastClose) >= 0) {
            return Candidate.unverified(source, "CANDIDATE_OUT_OF_RANGE", inputs);
        }
        var sourceAsOf = sourceAsOf(window);
        inputs.put("sourceAsOfBasis", "MAX_BAR_SOURCE_AS_OF");
        return new Candidate(source, "OK", null, candidate, sourceAsOf, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputs)));
    }

    private static String blockedReason(List<StoredDailyBar> window, InvestmentContextService.PriceFacts price,
                                        Instant now) {
        if (window.stream().anyMatch(StoredDailyBar::sourceConflict)) return "SOURCE_CONFLICT";
        var freshness = priceFreshness(price);
        if (freshness.reason() != null) return freshness.reason();
        if (ChronoUnit.DAYS.between(window.getLast().date(), freshness.priceDate()) > STALE_BAR_DAYS) {
            return "STALE_BARS";
        }
        var sourceAsOf = sourceAsOf(window);
        if (sourceAsOf == null) return "SOURCE_AS_OF_MISSING";
        if (sourceAsOf.isAfter(now)) return "SOURCE_AS_OF_IN_FUTURE";
        return null;
    }

    /**
     * Which price fact proves freshness: the OK quote first, else the OK regular close. Neither passing yields the
     * reason mapped from the quote status; the regular close never stands in for the quote price itself.
     */
    static PriceFreshness priceFreshness(InvestmentContextService.PriceFacts price) {
        if (price != null && "OK".equals(price.status()) && price.asOf() != null) {
            return new PriceFreshness(BASIS_QUOTE, LocalDate.ofInstant(price.asOf(), NEW_YORK), null);
        }
        if (price != null && "OK".equals(price.regularCloseStatus()) && price.regularCloseSessionDate() != null) {
            return new PriceFreshness(BASIS_REGULAR_CLOSE, price.regularCloseSessionDate(), null);
        }
        var status = price == null ? null : price.status();
        var reason = switch (status == null ? "DATA_MISSING" : status) {
            case "STALE" -> "PRICE_STALE";
            case "SOURCE_CONFLICT" -> "PRICE_SOURCE_CONFLICT";
            case "DATA_MISSING" -> "PRICE_MISSING";
            default -> "PRICE_UNVERIFIED";
        };
        return new PriceFreshness(null, null, reason);
    }

    /** Exactly one of {@code basis}+{@code priceDate} or {@code reason} is set. */
    record PriceFreshness(String basis, LocalDate priceDate, String reason) {
    }

    private static Instant sourceAsOf(List<StoredDailyBar> window) {
        return window.stream().map(StoredDailyBar::sourceAsOf).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
    }

    private static List<StoredDailyBar> completed(List<StoredDailyBar> bars, LocalDate today) {
        return bars == null ? List.of() : bars.stream().filter(bar -> bar.date().isBefore(today)).toList();
    }

    private static List<StoredDailyBar> tail(List<StoredDailyBar> bars, int size) {
        return bars.subList(Math.max(0, bars.size() - size), bars.size());
    }

    private static Map<String, Object> baseInputs(String method, List<StoredDailyBar> window,
                                                  InvestmentContextService.PriceFacts price, LocalDate today) {
        var inputs = new LinkedHashMap<String, Object>();
        inputs.put("method", method);
        inputs.put("priceAdjustment", "UNADJUSTED");
        inputs.put("completedBarsBefore", today.toString());
        inputs.put("windowBars", window.size());
        if (!window.isEmpty()) {
            inputs.put("windowStart", window.getFirst().date().toString());
            inputs.put("windowEnd", window.getLast().date().toString());
        }
        inputs.put("priceStatus", price == null || price.status() == null ? "DATA_MISSING" : price.status());
        if (price != null && price.asOf() != null) inputs.put("priceAsOf", price.asOf().toString());
        if (price != null && price.regularCloseStatus() != null) {
            inputs.put("regularCloseStatus", price.regularCloseStatus());
        }
        if (price != null && price.regularCloseSessionDate() != null) {
            inputs.put("regularCloseSessionDate", price.regularCloseSessionDate().toString());
        }
        var freshness = priceFreshness(price);
        if (freshness.basis() != null) inputs.put("priceFreshnessBasis", freshness.basis());
        inputs.put("staleBarThresholdDays", STALE_BAR_DAYS);
        return inputs;
    }

    /** One candidate: {@code status} OK with a trigger and sourceAsOf, or UNVERIFIED with a reason and no trigger. */
    public record Candidate(String source, String status, String reason, BigDecimal trigger, Instant sourceAsOf,
                            Map<String, Object> inputs) {
        static Candidate unverified(String source, String reason, Map<String, Object> inputs) {
            return new Candidate(source, "UNVERIFIED", reason, null, null, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputs)));
        }

        public boolean available() {
            return "OK".equals(status) && trigger != null && sourceAsOf != null;
        }
    }
}
