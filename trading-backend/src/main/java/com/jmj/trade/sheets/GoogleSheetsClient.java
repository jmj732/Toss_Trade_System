package com.jmj.trade.sheets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Minimal read/batch-write facade for the Google Sheets Values API. */
public final class GoogleSheetsClient {

    public static final URI DEFAULT_API_URI = URI.create("https://sheets.googleapis.com");
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final GoogleServiceAccountTokenProvider tokens;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GoogleSheetsClient(String serviceAccountJson) {
        this(GoogleServiceAccountCredentials.fromJson(serviceAccountJson));
    }

    public GoogleSheetsClient(GoogleServiceAccountCredentials credentials) {
        this(credentials, new GoogleServiceAccountTokenProvider(credentials), DEFAULT_API_URI,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    GoogleSheetsClient(
            GoogleServiceAccountCredentials credentials,
            GoogleServiceAccountTokenProvider tokens,
            URI apiUri,
            Duration connectTimeout,
            Duration readTimeout) {
        Objects.requireNonNull(credentials, "credentials");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        var httpClient = HttpClient.newBuilder().connectTimeout(positive(connectTimeout, "connectTimeout")).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(positive(readTimeout, "readTimeout"));
        this.restClient = RestClient.builder()
                .baseUrl(requireEndpoint(apiUri, "apiUri").toString())
                .requestFactory(requestFactory)
                .build();
    }

    public SheetValues readValues(String spreadsheetId, String range) {
        requireText(spreadsheetId, "spreadsheetId");
        requireText(range, "range");
        return executeWithRefresh(token -> {
            try {
                var body = restClient.get()
                        .uri(builder -> builder.path("/v4/spreadsheets/{id}/values/{range}")
                                .build(spreadsheetId, range))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve()
                        .body(String.class);
                return decodeValues(body, range);
            } catch (RestClientResponseException exception) {
                throw GoogleSheetsException.http("Google Sheets read failed", exception.getStatusCode().value());
            } catch (RestClientException exception) {
                throw GoogleSheetsException.network("Google Sheets read failed");
            }
        });
    }

    public void batchUpdateValues(String spreadsheetId, List<SheetValueRange> updates) {
        requireText(spreadsheetId, "spreadsheetId");
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("at least one sheet range is required");
        }
        var data = updates.stream().map(update -> {
            Objects.requireNonNull(update, "updates must not contain null");
            return Map.<String, Object>of("range", update.range(), "values", update.values());
        }).toList();
        var request = Map.of("valueInputOption", "RAW", "data", data);
        executeWithRefresh(token -> {
            try {
                restClient.post()
                        .uri(builder -> builder.path("/v4/spreadsheets/{id}/values:batchUpdate")
                                .queryParam("valueInputOption", "RAW")
                                .build(spreadsheetId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(encode(request))
                        .retrieve()
                        .toBodilessEntity();
                return null;
            } catch (RestClientResponseException exception) {
                throw GoogleSheetsException.http("Google Sheets update failed", exception.getStatusCode().value());
            } catch (RestClientException exception) {
                throw GoogleSheetsException.network("Google Sheets update failed");
            }
        });
    }

    private <T> T executeWithRefresh(java.util.function.Function<String, T> request) {
        var token = tokens.accessToken();
        try {
            return request.apply(token);
        } catch (GoogleSheetsException exception) {
            if (!Integer.valueOf(401).equals(exception.httpStatus())) {
                throw exception;
            }
            tokens.invalidateIfCurrent(token);
            return request.apply(tokens.accessToken());
        }
    }

    private SheetValues decodeValues(String body, String requestedRange) {
        if (body == null || body.isBlank()) {
            throw GoogleSheetsException.contract("Google Sheets read response was empty");
        }
        try {
            var root = objectMapper.readTree(body);
            var range = root.path("range").isTextual() ? root.path("range").asText() : requestedRange;
            var values = root.path("values");
            if (values.isMissingNode() || values.isNull()) {
                return new SheetValues(range, List.of());
            }
            if (!values.isArray()) {
                throw GoogleSheetsException.contract("Google Sheets values response was invalid");
            }
            var rows = new ArrayList<List<Object>>();
            for (var row : values) {
                if (!row.isArray()) {
                    throw GoogleSheetsException.contract("Google Sheets values response was invalid");
                }
                var cells = new ArrayList<Object>();
                for (var cell : row) {
                    cells.add(toJavaValue(cell));
                }
                rows.add(Collections.unmodifiableList(cells));
            }
            return new SheetValues(range, List.copyOf(rows));
        } catch (GoogleSheetsException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw GoogleSheetsException.contract("Google Sheets read response was invalid");
        }
    }

    private static Object toJavaValue(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        }
        return node.asText();
    }

    private String encode(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException exception) {
            throw GoogleSheetsException.contract("Google Sheets request could not be encoded");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static URI requireEndpoint(URI uri, String field) {
        Objects.requireNonNull(uri, field);
        var scheme = uri.getScheme();
        var host = uri.getHost();
        var local = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        if (host == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && local))) {
            throw new IllegalArgumentException(field + " must be HTTPS with a host");
        }
        return uri;
    }

    public record SheetValueRange(String range, List<List<Object>> values) {
        public SheetValueRange {
            if (range == null || range.isBlank()) {
                throw new IllegalArgumentException("range is required");
            }
            Objects.requireNonNull(values, "values");
            values = values.stream()
                    .map(row -> Collections.unmodifiableList(new ArrayList<>(
                            Objects.requireNonNull(row, "values must not contain null rows"))))
                    .toList();
        }
    }

    public record SheetValues(String range, List<List<Object>> values) {
        public SheetValues {
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(values, "values");
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
}
