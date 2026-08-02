package com.jmj.trade.order;

import com.jmj.trade.broker.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@ConfigurationProperties("real-order.canary")
public record RealOrderCanaryProperties(
        boolean enabled,
        UUID connectionId,
        UUID brokerAccountId,
        BigDecimal maxQuantity,
        BigDecimal maxOrderAmountKrw,
        BigDecimal maxOrderAmountUsd,
        Duration quoteMaxAge,
        String clientOrderIdPrefix
) {

    private static final BigDecimal HARD_MAX_QUANTITY = new BigDecimal("10");
    private static final BigDecimal HARD_MAX_KRW = new BigDecimal("100000");
    private static final BigDecimal HARD_MAX_USD = new BigDecimal("100");
    private static final Duration HARD_MAX_QUOTE_AGE = Duration.ofMinutes(5);
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    public List<String> validationErrors() {
        var errors = new ArrayList<String>();
        if (!enabled) {
            errors.add("CANARY_DISABLED");
        }
        if (connectionId == null || brokerAccountId == null) {
            errors.add("CANARY_ACCOUNT_NOT_PINNED");
        }
        if (maxQuantity == null || maxQuantity.signum() <= 0 || maxQuantity.compareTo(HARD_MAX_QUANTITY) > 0) {
            errors.add("CANARY_QUANTITY_LIMIT_INVALID");
        }
        if (maxOrderAmountKrw == null || maxOrderAmountKrw.signum() <= 0
                || maxOrderAmountKrw.compareTo(HARD_MAX_KRW) > 0) {
            errors.add("CANARY_KRW_LIMIT_INVALID");
        }
        if (maxOrderAmountUsd == null || maxOrderAmountUsd.signum() <= 0
                || maxOrderAmountUsd.compareTo(HARD_MAX_USD) > 0) {
            errors.add("CANARY_USD_LIMIT_INVALID");
        }
        if (quoteMaxAge == null || quoteMaxAge.isZero() || quoteMaxAge.isNegative()
                || quoteMaxAge.compareTo(HARD_MAX_QUOTE_AGE) > 0) {
            errors.add("CANARY_QUOTE_AGE_INVALID");
        }
        if (clientOrderIdPrefix == null || !PREFIX.matcher(clientOrderIdPrefix).matches()) {
            errors.add("CANARY_CLIENT_ORDER_PREFIX_INVALID");
        }
        return List.copyOf(errors);
    }

    public BigDecimal maxOrderAmount(Currency currency) {
        return currency == Currency.KRW ? maxOrderAmountKrw : maxOrderAmountUsd;
    }

    public boolean pins(UUID connection, UUID account) {
        return connectionId != null && brokerAccountId != null
                && connectionId.equals(connection) && brokerAccountId.equals(account);
    }
}
