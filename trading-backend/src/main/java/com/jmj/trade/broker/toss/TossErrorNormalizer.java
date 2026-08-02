package com.jmj.trade.broker.toss;

import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

final class TossErrorNormalizer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    BrokerException httpError(RestClientResponseException exception) {
        var status = exception.getStatusCode().value();
        var error = parseError(exception);
        var requestId = safe(error == null ? null : error.requestId());
        var code = safe(error == null ? null : error.code());
        return new BrokerException(
                category(status),
                status,
                code,
                requestId,
                status == 429 ? parseRetryAfter(exception.getResponseHeaders()).orElse(null) : null,
                status == 429 || status >= 500,
                "Toss API request failed with status " + status);
    }

    BrokerException networkError() {
        return new BrokerException(
                BrokerErrorCategory.NETWORK,
                null,
                null,
                null,
                null,
                true,
                "Toss API request failed due to network error");
    }

    BrokerException contractError(Integer httpStatus) {
        return new BrokerException(
                BrokerErrorCategory.CONTRACT,
                httpStatus,
                null,
                null,
                null,
                false,
                "Toss API response was invalid");
    }

    private TossApiDtos.RegularError parseError(RestClientResponseException exception) {
        try {
            var envelope = objectMapper.readValue(exception.getResponseBodyAsString(), TossApiDtos.RegularErrorEnvelope.class);
            return envelope == null ? null : envelope.error();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static BrokerErrorCategory category(int status) {
        return switch (status) {
            case 400 -> BrokerErrorCategory.INVALID_REQUEST;
            case 401 -> BrokerErrorCategory.AUTHENTICATION;
            case 403 -> BrokerErrorCategory.AUTHORIZATION;
            case 404 -> BrokerErrorCategory.NOT_FOUND;
            case 422 -> BrokerErrorCategory.VALIDATION;
            case 429 -> BrokerErrorCategory.RATE_LIMITED;
            default -> status >= 500 ? BrokerErrorCategory.TEMPORARY : BrokerErrorCategory.UNKNOWN;
        };
    }

    private static Optional<Duration> parseRetryAfter(HttpHeaders headers) {
        if (headers == null) {
            return Optional.empty();
        }
        try {
            var value = headers.getFirst("Retry-After");
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            var seconds = Long.parseLong(value);
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
