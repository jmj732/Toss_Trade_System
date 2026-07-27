package com.jmj.trade.broker.toss;

import java.net.URI;
import java.util.Set;

final class TossSensitiveDataMasker {

    private static final Set<String> QUERY_ALLOWLIST = Set.of("symbols", "currency");

    String maskHeader(String name, String value) {
        if ("authorization".equalsIgnoreCase(name) && value != null && value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "Bearer ***";
        }
        return maskCredential(value);
    }

    String maskCredential(String ignored) {
        return "***";
    }

    String maskToken(String ignored) {
        return "***";
    }

    String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "*".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }

    String maskUri(URI uri) {
        var result = new StringBuilder(uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath());
        var query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return result.toString();
        }
        var safe = java.util.Arrays.stream(query.split("&"))
                .filter(part -> {
                    var equals = part.indexOf('=');
                    var name = equals < 0 ? part : part.substring(0, equals);
                    return QUERY_ALLOWLIST.contains(name);
                })
                .toList();
        if (!safe.isEmpty()) {
            result.append('?').append(String.join("&", safe));
        }
        return result.toString();
    }
}
