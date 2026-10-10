package com.jmj.trade.investment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates thesis candidates from market data using technical indicators.
 * Supports ATR(14, Wilder) and 20-day support calculations.
 */
public class ThesisCandidateGenerator {

    /**
     * Calculates ATR(14) candidate trigger from completed bar history.
     * Formula: TR_i = max(H-L, |H-C_{i-1}|, |L-C_{i-1}|)
     *          ATR_t = (13*ATR_{t-1} + TR_t) / 14 (Wilder smoothing)
     * Candidate = lastClose - 2*ATR
     *
     * @param bars List of BarData in chronological order (oldest first)
     * @return CandidateResult with trigger price or UNVERIFIED reason
     */
    public static CandidateResult atrCandidate(List<BarData> bars) {
        if (bars == null || bars.isEmpty()) {
            return CandidateResult.unverified("NO_DATA");
        }

        if (bars.size() < 15) {
            return CandidateResult.unverified("INSUFFICIENT_HISTORY");
        }

        var validBars = new ArrayList<BarData>();
        for (var bar : bars) {
            if (bar.close == null || bar.high == null || bar.low == null) {
                continue;
            }
            validBars.add(bar);
        }

        if (validBars.size() < 15) {
            return CandidateResult.unverified("INSUFFICIENT_HISTORY");
        }

        // Calculate True Range
        var trValues = new BigDecimal[validBars.size()];
        for (int i = 0; i < validBars.size(); i++) {
            var bar = validBars.get(i);
            var tr = bar.high.subtract(bar.low).abs();
            if (i > 0) {
                var prev = validBars.get(i - 1);
                var hc = bar.high.subtract(prev.close).abs();
                var lc = bar.low.subtract(prev.close).abs();
                tr = tr.max(hc).max(lc);
            }
            trValues[i] = tr;
        }

        // Initialize ATR with SMA of first 14 TRs
        var atrSum = BigDecimal.ZERO;
        for (int i = 0; i < 14; i++) {
            atrSum = atrSum.add(trValues[i]);
        }
        var atr = atrSum.divide(BigDecimal.valueOf(14), 8, RoundingMode.HALF_UP);

        // Apply Wilder smoothing for remaining bars
        for (int i = 14; i < trValues.length; i++) {
            atr = atr.multiply(BigDecimal.valueOf(13))
                    .add(trValues[i])
                    .divide(BigDecimal.valueOf(14), 8, RoundingMode.HALF_UP);
        }

        // Candidate = lastClose - 2*ATR
        var lastClose = validBars.get(validBars.size() - 1).close;
        var candidate = lastClose.subtract(atr.multiply(BigDecimal.valueOf(2)))
                .setScale(4, RoundingMode.FLOOR);

        if (candidate.compareTo(BigDecimal.ZERO) <= 0 || candidate.compareTo(lastClose) >= 0) {
            return CandidateResult.unverified("PRICE_OUT_OF_RANGE");
        }

        return CandidateResult.verified(candidate);
    }

    /**
     * Calculates 20-day support level (lowest low of last 20 completed bars).
     *
     * @param bars List of BarData in chronological order
     * @return CandidateResult with support level or UNVERIFIED reason
     */
    public static CandidateResult supportCandidate(List<BarData> bars) {
        if (bars == null || bars.isEmpty()) {
            return CandidateResult.unverified("NO_DATA");
        }

        var validBars = new ArrayList<BarData>();
        for (var bar : bars) {
            if (bar.low == null) {
                continue;
            }
            validBars.add(bar);
        }

        if (validBars.size() < 20) {
            return CandidateResult.unverified("INSUFFICIENT_HISTORY");
        }

        var support = validBars.stream()
                .skip(Math.max(0, validBars.size() - 20))
                .map(b -> b.low)
                .reduce(BigDecimal::min)
                .orElse(null);

        if (support == null || support.compareTo(BigDecimal.ZERO) <= 0) {
            return CandidateResult.unverified("PRICE_UNVERIFIED");
        }

        var lastClose = validBars.get(validBars.size() - 1).close;
        if (lastClose != null && support.compareTo(lastClose) >= 0) {
            return CandidateResult.unverified("SUPPORT_ABOVE_PRICE");
        }

        return CandidateResult.verified(support.setScale(4, RoundingMode.FLOOR));
    }

    public record BarData(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {
    }

    public static class CandidateResult {
        public final boolean verified;
        public final BigDecimal trigger;
        public final String reason;

        private CandidateResult(boolean verified, BigDecimal trigger, String reason) {
            this.verified = verified;
            this.trigger = trigger;
            this.reason = reason;
        }

        public static CandidateResult verified(BigDecimal trigger) {
            return new CandidateResult(true, trigger, null);
        }

        public static CandidateResult unverified(String reason) {
            return new CandidateResult(false, null, reason);
        }
    }
}
