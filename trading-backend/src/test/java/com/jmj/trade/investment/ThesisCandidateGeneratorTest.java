package com.jmj.trade.investment;

import com.jmj.trade.investment.tactical.TacticalOverlayService.StoredDailyBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThesisCandidateGeneratorTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-09");
    private static final Instant NOW = Instant.parse("2026-10-09T18:00:00Z");
    private static final InvestmentContextService.PriceFacts PRICE_OK =
            new InvestmentContextService.PriceFacts("OK", Instant.parse("2026-10-09T17:55:00Z"), bd("104"));

    @Test
    void atrIsWilderSmoothedFromSeedMeanOfFirstFourteenTrueRanges() {
        // 15 flat bars: TR_1..TR_14 = max(101-99, |101-100|, |99-100|) = 2, so the seed SMA is exactly 2.
        var bars = flatBars(15, "101", "99", "100");
        // Gap bar: H-L = 4, |H-Cprev| = 7, |L-Cprev| = 3 -> TR_15 = 7. ATR = (13*2 + 7) / 14 = 33/14.
        bars.add(bar(TODAY.minusDays(1), "107", "103", "105", false));

        var atr = ThesisCandidateGenerator.wilderAtr(bars);
        assertThat(atr).isEqualByComparingTo(bd("33").divide(bd("14"), java.math.MathContext.DECIMAL128));

        var candidate = ThesisCandidateGenerator.atrCandidate(bars, PRICE_OK, TODAY, NOW);
        // 105 - 2 * 33/14 = 100.285714285714... -> setScale(4, FLOOR) = 100.2857
        assertThat(candidate.available()).isTrue();
        assertThat(candidate.trigger()).isEqualByComparingTo("100.2857");
        assertThat(candidate.trigger().scale()).isEqualTo(4);
        assertThat(candidate.sourceAsOf()).isEqualTo(sourceAsOf(TODAY.minusDays(1)));
        assertThat(candidate.inputs()).containsEntry("method", "ATR14_WILDER_X2")
                .containsEntry("priceAdjustment", "UNADJUSTED")
                .containsEntry("windowBars", 16)
                .containsEntry("lastClose", "105");
    }

    @Test
    void atrUsesOnlyTheLastSixtyCompletedBarsAndIgnoresTodaysBar() {
        var bars = new ArrayList<StoredDailyBar>();
        // An extreme bar just outside the 60-bar window would change TR_1 if it were included.
        bars.add(bar(TODAY.minusDays(70), "600", "400", "500", true));
        bars.addAll(flatBars(60, "101", "99", "100"));
        // Today's (incomplete) bar must be excluded even though it is stored.
        bars.add(bar(TODAY, "300", "1", "2", false));

        var candidate = ThesisCandidateGenerator.atrCandidate(bars, PRICE_OK, TODAY, NOW);

        // Every TR inside the window is 2, so ATR = 2 and candidate = 100 - 4.
        assertThat(candidate.available()).isTrue();
        assertThat(candidate.trigger()).isEqualByComparingTo("96.0000");
        assertThat(candidate.inputs()).containsEntry("windowBars", 60)
                .containsEntry("windowEnd", TODAY.minusDays(2).toString());
    }

    @Test
    void insufficientHistoryNeedsFifteenBarsForAtrAndTwentyForSupport() {
        assertThat(ThesisCandidateGenerator.atrCandidate(flatBars(14, "101", "99", "100"), PRICE_OK, TODAY, NOW))
                .satisfies(candidate -> assertUnverified(candidate, "INSUFFICIENT_HISTORY"));
        assertThat(ThesisCandidateGenerator.atrCandidate(flatBars(15, "101", "99", "100"), PRICE_OK, TODAY, NOW)
                .trigger()).isEqualByComparingTo("96.0000");
        assertThat(ThesisCandidateGenerator.supportCandidate(flatBars(19, "101", "99", "100"), PRICE_OK, TODAY, NOW))
                .satisfies(candidate -> assertUnverified(candidate, "INSUFFICIENT_HISTORY"));
        assertThat(ThesisCandidateGenerator.atrCandidate(List.of(), PRICE_OK, TODAY, NOW))
                .satisfies(candidate -> assertUnverified(candidate, "INSUFFICIENT_HISTORY"));
    }

    @Test
    void supportIsLowestLowOfLastTwentyCompletedBars() {
        var bars = new ArrayList<StoredDailyBar>();
        bars.add(bar(TODAY.minusDays(25), "100", "50", "90", false)); // outside the 20-bar window
        bars.addAll(flatBars(20, "101", "99", "100"));
        bars.set(10, bar(bars.get(10).date(), "101", "97.12345", "100", false));

        var candidate = ThesisCandidateGenerator.supportCandidate(bars, PRICE_OK, TODAY, NOW);

        assertThat(candidate.available()).isTrue();
        assertThat(candidate.trigger()).isEqualByComparingTo("97.1234");
        assertThat(candidate.inputs()).containsEntry("method", "SUPPORT_LOW_20")
                .containsEntry("lowestLow", "97.12345");
    }

    @Test
    void priceNotOkOrWithoutAsOfIsPriceUnverified() {
        var bars = flatBars(20, "101", "99", "100");
        var stale = new InvestmentContextService.PriceFacts("STALE", PRICE_OK.asOf(), bd("100"));
        var noAsOf = new InvestmentContextService.PriceFacts("OK", null, bd("100"));
        assertUnverified(ThesisCandidateGenerator.atrCandidate(bars, stale, TODAY, NOW), "PRICE_UNVERIFIED");
        assertUnverified(ThesisCandidateGenerator.supportCandidate(bars, noAsOf, TODAY, NOW), "PRICE_UNVERIFIED");
        assertUnverified(ThesisCandidateGenerator.atrCandidate(bars, null, TODAY, NOW), "PRICE_UNVERIFIED");
    }

    @Test
    void candidateAtOrAboveLastCloseOrNonPositiveIsOutOfRange() {
        // Flat bars: ATR = 0 -> candidate == lastClose.
        assertUnverified(ThesisCandidateGenerator.atrCandidate(flatBars(20, "100", "100", "100"),
                PRICE_OK, TODAY, NOW), "CANDIDATE_OUT_OF_RANGE");
        // Huge ranges around a small close: lastClose - 2*ATR <= 0.
        assertUnverified(ThesisCandidateGenerator.atrCandidate(flatBars(20, "100", "1", "10"),
                PRICE_OK, TODAY, NOW), "CANDIDATE_OUT_OF_RANGE");
        // Support equal to the last close.
        var bars = flatBars(20, "101", "95", "100");
        bars.set(19, bar(bars.get(19).date(), "99", "95", "95", false));
        assertUnverified(ThesisCandidateGenerator.supportCandidate(bars, PRICE_OK, TODAY, NOW),
                "CANDIDATE_OUT_OF_RANGE");
    }

    @Test
    void sourceConflictInsideTheWindowBlocksTheCandidate() {
        var bars = flatBars(20, "101", "99", "100");
        bars.set(5, bar(bars.get(5).date(), "101", "99", "100", true));
        assertUnverified(ThesisCandidateGenerator.atrCandidate(bars, PRICE_OK, TODAY, NOW), "SOURCE_CONFLICT");
        assertUnverified(ThesisCandidateGenerator.supportCandidate(bars, PRICE_OK, TODAY, NOW), "SOURCE_CONFLICT");
    }

    @Test
    void barsMoreThanFourDaysOlderThanThePriceAreStale() {
        var bars = flatBarsEndingAt(20, TODAY.minusDays(6));
        assertUnverified(ThesisCandidateGenerator.atrCandidate(bars, PRICE_OK, TODAY, NOW), "STALE_BARS");
        var fourDays = flatBarsEndingAt(20, LocalDate.parse("2026-10-05"));
        assertThat(ThesisCandidateGenerator.atrCandidate(fourDays, PRICE_OK, TODAY, NOW).available()).isTrue();
    }

    private static void assertUnverified(ThesisCandidateGenerator.Candidate candidate, String reason) {
        assertThat(candidate.status()).isEqualTo("UNVERIFIED");
        assertThat(candidate.reason()).isEqualTo(reason);
        assertThat(candidate.trigger()).isNull();
        assertThat(candidate.available()).isFalse();
    }

    private static List<StoredDailyBar> flatBarsEndingAt(int count, LocalDate last) {
        var bars = new ArrayList<StoredDailyBar>();
        for (int i = count - 1; i >= 0; i--) bars.add(bar(last.minusDays(i), "101", "99", "100", false));
        return bars;
    }

    private static ArrayList<StoredDailyBar> flatBars(int count, String high, String low, String close) {
        var bars = new ArrayList<StoredDailyBar>();
        var first = TODAY.minusDays(count + 1L);
        for (int i = 0; i < count; i++) bars.add(bar(first.plusDays(i), high, low, close, false));
        return bars;
    }

    private static StoredDailyBar bar(LocalDate date, String high, String low, String close, boolean conflict) {
        return new StoredDailyBar(date, bd(close), bd(high), bd(low), bd(close), bd("1000"), sourceAsOf(date), conflict);
    }

    private static Instant sourceAsOf(LocalDate date) {
        return date.atTime(21, 0).toInstant(java.time.ZoneOffset.UTC);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
