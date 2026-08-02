package com.jmj.trade.marketdata;

import java.util.Map;
import java.util.regex.Pattern;

public record ProviderRequest(String symbol, Map<String, String> identifiers) {

    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9._:+\\-]{1,128}");

    public ProviderRequest {
        if (symbol == null || !symbol.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new IllegalArgumentException("symbol is invalid");
        }
        var supplied = identifiers == null ? Map.<String, String>of() : identifiers;
        if (supplied.size() > 32) {
            throw new IllegalArgumentException("too many provider identifiers");
        }
        supplied.forEach((key, value) -> {
            if (key == null || !KEY.matcher(key).matches()
                    || value == null || !VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("provider identifiers must be non-blank");
            }
        });
        identifiers = Map.copyOf(supplied);
    }
}
