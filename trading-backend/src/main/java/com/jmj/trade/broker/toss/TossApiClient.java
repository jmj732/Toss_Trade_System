package com.jmj.trade.broker.toss;

import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.RateLimitSnapshot;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

final class TossApiClient {

    private final RestClient restClient;
    private final TossTokenManager tokenManager;
    private final TossErrorNormalizer errors = new TossErrorNormalizer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    TossApiClient(TossApiProperties properties, TossTokenManager tokenManager) {
        Objects.requireNonNull(properties, "properties");
        this.tokenManager = Objects.requireNonNull(tokenManager, "tokenManager");
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    TossApiResponse<List<TossApiDtos.Account>> getAccounts(UUID brokerConnectionId) {
        return withTokenRefresh(brokerConnectionId, token -> get("/api/v1/accounts", token)
                .retrieve()
                .toEntity(String.class), TossApiDtos.AccountsEnvelope.class)
                .map(TossApiDtos.AccountsEnvelope::result);
    }

    TossApiResponse<TossApiDtos.Holdings> getHoldings(UUID brokerConnectionId, String accountSeq) {
        return withTokenRefresh(brokerConnectionId, token -> get("/api/v1/holdings", token)
                .header("X-Tossinvest-Account", accountSeq)
                .retrieve()
                .toEntity(String.class), TossApiDtos.HoldingsEnvelope.class)
                .map(TossApiDtos.HoldingsEnvelope::result);
    }

    TossApiResponse<List<TossApiDtos.Price>> getPrices(UUID brokerConnectionId, String symbols) {
        return withTokenRefresh(brokerConnectionId, token -> restClient.get()
                .uri(builder -> builder.path("/api/v1/prices").queryParam("symbols", symbols).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toEntity(String.class), TossApiDtos.PricesEnvelope.class)
                .map(TossApiDtos.PricesEnvelope::result);
    }

    TossApiResponse<TossApiDtos.BuyingPower> getBuyingPower(UUID brokerConnectionId, String accountSeq, String currency) {
        return withTokenRefresh(brokerConnectionId, token -> restClient.get()
                .uri(builder -> builder.path("/api/v1/buying-power").queryParam("currency", currency).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Tossinvest-Account", accountSeq)
                .retrieve()
                .toEntity(String.class), TossApiDtos.BuyingPowerEnvelope.class)
                .map(TossApiDtos.BuyingPowerEnvelope::result);
    }

    private RestClient.RequestHeadersSpec<?> get(String path, String accessToken) {
        return restClient.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    private <T> TossApiResponse<T> withTokenRefresh(
            UUID brokerConnectionId,
            TossReadRequest request,
            Class<T> responseType) {
        Objects.requireNonNull(brokerConnectionId, "brokerConnectionId");
        var token = tokenManager.getAccessToken(brokerConnectionId);
        try {
            return call(request, token, responseType);
        } catch (BrokerException exception) {
            if (!isRefreshableUnauthorized(exception)) {
                throw exception;
            }
            tokenManager.invalidateIfCurrent(brokerConnectionId, token);
            return call(request, tokenManager.getAccessToken(brokerConnectionId), responseType);
        }
    }

    private <T> TossApiResponse<T> call(TossReadRequest request, String token, Class<T> responseType) {
        try {
            var response = request.execute(token);
            var metadata = metadata(response.getHeaders());
            return new TossApiResponse<>(decodeSuccess(response.getBody(), responseType), metadata);
        } catch (RestClientResponseException exception) {
            throw errors.httpError(exception);
        } catch (RestClientException exception) {
            throw errors.networkError();
        }
    }

    private <T> T decodeSuccess(String body, Class<T> responseType) {
        if (body == null || body.isBlank()) {
            throw errors.contractError(200);
        }
        try {
            var decoded = objectMapper.readValue(body, responseType);
            if (decoded == null || result(decoded) == null) {
                throw errors.contractError(200);
            }
            return decoded;
        } catch (BrokerException exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw errors.contractError(200);
        }
    }

    private Object result(Object envelope) {
        if (envelope instanceof TossApiDtos.AccountsEnvelope typed) {
            return typed.result();
        }
        if (envelope instanceof TossApiDtos.HoldingsEnvelope typed) {
            return typed.result();
        }
        if (envelope instanceof TossApiDtos.PricesEnvelope typed) {
            return typed.result();
        }
        if (envelope instanceof TossApiDtos.BuyingPowerEnvelope typed) {
            return typed.result();
        }
        return null;
    }

    private BrokerCallMetadata metadata(HttpHeaders headers) {
        var observedAt = Instant.now();
        return new BrokerCallMetadata(
                blankToNull(headers.getFirst("X-Request-Id")),
                observedAt,
                rateLimit(headers, observedAt));
    }

    private Optional<RateLimitSnapshot> rateLimit(HttpHeaders headers, Instant observedAt) {
        var limit = number(headers, "X-RateLimit-Limit");
        var remaining = number(headers, "X-RateLimit-Remaining");
        var reset = resetAt(headers, observedAt);
        var retryAfter = seconds(headers, "Retry-After");
        if (limit.isEmpty() && remaining.isEmpty() && reset.isEmpty() && retryAfter.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RateLimitSnapshot(limit, remaining, reset, retryAfter));
    }

    private Optional<Integer> number(HttpHeaders headers, String name) {
        return parseHeader(headers, name, () -> {
            var value = Integer.parseInt(headers.getFirst(name));
            if (value < 0) {
                throw new IllegalArgumentException();
            }
            return value;
        });
    }

    private Optional<Instant> resetAt(HttpHeaders headers, Instant observedAt) {
        return parseHeader(headers, "X-RateLimit-Reset", () -> {
            var seconds = Long.parseLong(headers.getFirst("X-RateLimit-Reset"));
            if (seconds < 0) {
                throw new IllegalArgumentException();
            }
            return observedAt.plus(Duration.ofSeconds(seconds));
        });
    }

    private Optional<Duration> seconds(HttpHeaders headers, String name) {
        return parseHeader(headers, name, () -> {
            var seconds = Long.parseLong(headers.getFirst(name));
            if (seconds < 0) {
                throw new IllegalArgumentException();
            }
            return Duration.ofSeconds(seconds);
        });
    }

    private <T> Optional<T> parseHeader(HttpHeaders headers, String name, Supplier<T> parser) {
        var raw = headers.getFirst(name);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parser.get());
        } catch (RuntimeException exception) {
            throw errors.contractError(200);
        }
    }

    private static boolean isRefreshableUnauthorized(BrokerException exception) {
        if (exception.category() != BrokerErrorCategory.AUTHENTICATION || exception.httpStatus().orElse(0) != 401) {
            return false;
        }
        return exception.brokerErrorCode()
                .map(code -> code.equals("invalid-token") || code.equals("expired-token"))
                .orElse(false);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @FunctionalInterface
    private interface TossReadRequest {
        ResponseEntity<String> execute(String accessToken);
    }
}

record TossApiResponse<T>(T value, BrokerCallMetadata metadata) {

    TossApiResponse {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(metadata, "metadata");
    }

    <R> TossApiResponse<R> map(java.util.function.Function<T, R> mapper) {
        return new TossApiResponse<>(Objects.requireNonNull(mapper.apply(value), "mapped value"), metadata);
    }
}
