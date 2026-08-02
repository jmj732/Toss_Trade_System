package com.jmj.trade.intelligence.ingestion;

import com.jmj.trade.intelligence.EventIntelligenceService;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public record MarketEvent(
        MarketEventProviderId provider,
        String sourceEventId,
        String type,
        String summary,
        Instant occurredAt,
        List<String> affectedSymbols,
        List<EventIntelligenceService.MacroScope> macroScope
) {

    public MarketEvent {
        if (provider == null || sourceEventId == null || sourceEventId.isBlank()
                || type == null || type.isBlank() || summary == null || summary.isBlank()
                || occurredAt == null) {
            throw new IllegalArgumentException("market event identity and content are required");
        }
        sourceEventId = sourceEventId.trim();
        type = type.trim();
        summary = summary.trim();
        if (sourceEventId.length() > 200 || type.length() > 60 || summary.length() > 1000) {
            throw new IllegalArgumentException("market event field is too long");
        }
        var normalized = new LinkedHashSet<String>();
        for (var symbol : affectedSymbols == null ? List.<String>of() : affectedSymbols) {
            if (symbol == null || !symbol.trim().matches("[A-Za-z0-9._-]{1,32}")) {
                throw new IllegalArgumentException("market event symbol is invalid");
            }
            normalized.add(symbol.trim().toUpperCase(Locale.ROOT));
        }
        affectedSymbols = List.copyOf(normalized);
        macroScope = macroScope == null ? List.of() : List.copyOf(macroScope);
        if (affectedSymbols.isEmpty() && macroScope.isEmpty()) {
            throw new IllegalArgumentException("market event must have a stock or macro scope");
        }
    }
}
