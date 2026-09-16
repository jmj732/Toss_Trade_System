package com.jmj.trade.sheets;

import java.util.UUID;

public record InvestmentOsSheetSyncResult(
        Outcome outcome,
        UUID syncId,
        int rowsChanged,
        int ordersChanged,
        int fillsChanged,
        String error
) {
    public enum Outcome { SUCCEEDED, FAILED, SKIPPED }
}
