package com.jmj.trade.broker.toss;

import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class TossOAuthClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    TossOAuthClient(TossApiProperties properties) {
        Objects.requireNonNull(properties, "properties");
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.tokenRequestTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    TossApiDtos.OAuthToken issueToken(UUID brokerConnectionId, TossCredentials credentials) {
        Objects.requireNonNull(brokerConnectionId, "brokerConnectionId");
        Objects.requireNonNull(credentials, "credentials");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", credentials.clientId());
        form.add("client_secret", credentials.clientSecret());

        try {
            var response = restClient.post()
                    .uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toEntity(String.class);
            return decodeSuccess(response.getBody());
        } catch (RestClientResponseException exception) {
            throw mapHttpError(exception);
        } catch (RestClientException exception) {
            throw new BrokerException(
                    BrokerErrorCategory.NETWORK,
                    null,
                    null,
                    null,
                    null,
                    true,
                    "Toss OAuth request failed due to network error");
        }
    }

    private TossApiDtos.OAuthToken decodeSuccess(String body) {
        try {
            return validate(objectMapper.readValue(body, TossApiDtos.OAuthTokenResponse.class));
        } catch (JacksonException exception) {
            throw new BrokerException(
                    BrokerErrorCategory.CONTRACT,
                    200,
                    null,
                    null,
                    null,
                    false,
                    "Toss OAuth token response was invalid");
        }
    }

    private TossApiDtos.OAuthToken validate(TossApiDtos.OAuthTokenResponse response) {
        if (response == null || response.access_token() == null || response.access_token().isBlank()
                || response.expires_in() == null || response.expires_in() <= 0) {
            throw new BrokerException(
                    BrokerErrorCategory.CONTRACT,
                    200,
                    null,
                    null,
                    null,
                    false,
                    "Toss OAuth token response was invalid");
        }
        return new TossApiDtos.OAuthToken(response.access_token(), Duration.ofSeconds(response.expires_in()));
    }

    private BrokerException mapHttpError(RestClientResponseException exception) {
        var status = exception.getStatusCode().value();
        var error = parseError(exception);
        var code = safeText(error == null ? null : error.error());
        var category = category(status, code);
        var retryAfter = status == 429 ? parseRetryAfter(exception) : null;
        return new BrokerException(
                category,
                status,
                code,
                null,
                retryAfter,
                status == 429 || status >= 500,
                "Toss OAuth request failed with status " + status);
    }

    private TossApiDtos.OAuthErrorResponse parseError(RestClientResponseException exception) {
        try {
            return exception.getResponseBodyAs(TossApiDtos.OAuthErrorResponse.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static BrokerErrorCategory category(int status, String code) {
        if (status == 401) {
            return BrokerErrorCategory.AUTHENTICATION;
        }
        if (status == 403) {
            return BrokerErrorCategory.AUTHORIZATION;
        }
        if (status == 429) {
            return BrokerErrorCategory.RATE_LIMITED;
        }
        if (status >= 500) {
            return BrokerErrorCategory.TEMPORARY;
        }
        if (status == 400 && ("invalid_request".equals(code) || "unsupported_grant_type".equals(code))) {
            return BrokerErrorCategory.INVALID_REQUEST;
        }
        return BrokerErrorCategory.UNKNOWN;
    }

    private static Duration parseRetryAfter(RestClientResponseException exception) {
        return Optional.ofNullable(exception.getResponseHeaders())
                .map(headers -> headers.getFirst("Retry-After"))
                .flatMap(TossOAuthClient::parsePositiveSeconds)
                .orElse(null);
    }

    private static Optional<Duration> parsePositiveSeconds(String value) {
        try {
            var seconds = Long.parseLong(value);
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
