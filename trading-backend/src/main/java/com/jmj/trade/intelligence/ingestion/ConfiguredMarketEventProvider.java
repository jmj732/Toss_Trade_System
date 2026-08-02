package com.jmj.trade.intelligence.ingestion;

import com.jmj.trade.intelligence.EventIntelligenceService;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

final class ConfiguredMarketEventProvider implements MarketEventProvider {

    private final MarketEventProviderId id;
    private final MarketEventIngestionProperties.ProviderConfiguration configuration;
    private final ObjectMapper objectMapper;
    private final MarketEventHttpClient http;

    ConfiguredMarketEventProvider(
            MarketEventProviderId id,
            MarketEventIngestionProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper
    ) {
        this(id, configuration, objectMapper, new MarketEventHttpClient());
    }

    ConfiguredMarketEventProvider(
            MarketEventProviderId id,
            MarketEventIngestionProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper,
            MarketEventHttpClient http
    ) {
        this.id = id;
        this.configuration = configuration;
        this.objectMapper = objectMapper;
        this.http = http;
        validate();
    }

    @Override
    public MarketEventProviderId id() {
        return id;
    }

    @Override
    public List<MarketEvent> collect(Request request) {
        var result = collectWithFailures(request);
        if (result.events().isEmpty() && result.hasFailures()) {
            throw result.failures().getFirst();
        }
        return result.events();
    }

    @Override
    public CollectionResult collectWithFailures(Request request) {
        return switch (id) {
            case SEC -> sec(request);
            case IR, FED -> feeds(request);
            case FRED -> fred(request);
            case BLS -> bls(request);
            case BEA -> bea(request);
        };
    }

    private CollectionResult sec(Request request) {
        var events = new ArrayList<MarketEvent>();
        var failures = new ArrayList<RuntimeException>();
        var byCik = new java.util.LinkedHashMap<String, List<String>>();
        configuration.identifiers().forEach((symbol, cik) -> {
            var normalized = normalizeCik(cik);
            if (request.symbols().contains(symbol.toUpperCase(Locale.ROOT))) {
                byCik.computeIfAbsent(normalized, ignored -> new ArrayList<>())
                        .add(symbol.toUpperCase(Locale.ROOT));
            }
        });
        for (var entry : byCik.entrySet()) {
            try {
                request.heartbeatCheck();
                var root = json(get(entry.getKey(), request), entry.getKey());
                var recent = root.path("filings").path("recent");
                var ids = recent.path("accessionNumber");
                for (var index = 0; index < ids.size() && events.size() < request.maxEvents(); index++) {
                    var occurred = parseInstant(
                            text(recent.path("acceptanceDateTime").path(index)),
                            parseDate(text(recent.path("filingDate").path(index))));
                    if (occurred.isBefore(request.since())) {
                        continue;
                    }
                    var accession = text(ids.path(index));
                    var form = text(recent.path("form").path(index));
                    events.add(new MarketEvent(
                            id,
                            "CIK" + entry.getKey() + ":" + accession,
                            "SEC_" + (form == null ? "FILING" : form),
                            (form == null ? "SEC filing" : form) + " for "
                                    + String.join(", ", entry.getValue()),
                            occurred,
                            entry.getValue(),
                            List.of()));
                }
            } catch (RuntimeException exception) {
                failures.add(exception);
            }
        }
        return new CollectionResult(events, failures);
    }

    private CollectionResult feeds(Request request) {
        var events = new ArrayList<MarketEvent>();
        var failures = new ArrayList<RuntimeException>();
        var byUrl = new java.util.LinkedHashMap<String, List<String>>();
        for (var entry : configuration.feedUrls().entrySet()) {
            if (id == MarketEventProviderId.IR
                    && !request.symbols().contains(entry.getKey().toUpperCase(Locale.ROOT))) {
                continue;
            }
            byUrl.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>())
                    .add(entry.getKey().toUpperCase(Locale.ROOT));
        }
        for (var entry : byUrl.entrySet()) {
            if (events.size() >= request.maxEvents()) {
                break;
            }
            try {
                request.heartbeatCheck();
                var feed = http.get(url(entry.getKey()), configuration, heartbeat(request));
                request.heartbeatCheck();
                var items = feedItems(feed);
                for (var item : items) {
                    if (events.size() >= request.maxEvents()) {
                        break;
                    }
                    var occurred = feedInstant(item.occurred());
                    if (occurred.isBefore(request.since())) {
                        continue;
                    }
                    var sourceEventId = item.id();
                    if (sourceEventId == null || sourceEventId.isBlank()) {
                        sourceEventId = digest(entry.getKey() + "\n" + item.title() + "\n" + occurred);
                    }
                    sourceEventId = digest(entry.getKey()).substring(0, 16) + ":" + sourceEventId;
                    var symbols = id == MarketEventProviderId.IR ? entry.getValue() : List.<String>of();
                    var scope = id == MarketEventProviderId.FED
                            ? entry.getValue().stream().map(identifier -> new EventIntelligenceService.MacroScope(
                            "FED", identifier, occurred.toString(), null)).toList()
                            : List.<EventIntelligenceService.MacroScope>of();
                    events.add(new MarketEvent(id, sourceEventId, id.name() + "_RELEASE",
                            item.title() == null ? id.name() + " release" : item.title(),
                            occurred, symbols, scope));
                }
            } catch (RuntimeException exception) {
                failures.add(exception);
            }
        }
        return new CollectionResult(events, failures);
    }

    private CollectionResult fred(Request request) {
        var events = new ArrayList<MarketEvent>();
        var failures = new ArrayList<RuntimeException>();
        for (var series : configuration.scopes()) {
            if (events.size() >= request.maxEvents()) {
                break;
            }
            try {
                request.heartbeatCheck();
                var uri = UriComponentsBuilder.fromUri(base())
                        .path(path("/fred/series/observations"))
                        .queryParam("series_id", series)
                        .queryParam("observation_start",
                                LocalDate.ofInstant(request.since(), ZoneOffset.UTC))
                        .queryParam("api_key", configuration.apiKey())
                        .queryParam("file_type", "json")
                        .build().encode().toUri();
                var body = http.get(uri, configuration, heartbeat(request));
                request.heartbeatCheck();
                var observations = json(body, series).path("observations");
                for (var observation : observations) {
                    if (events.size() >= request.maxEvents()) {
                        break;
                    }
                    var date = text(observation.path("date"));
                    var value = text(observation.path("value"));
                    if (date == null || value == null || ".".equals(value)) {
                        continue;
                    }
                    var realtimeStart = text(observation.path("realtime_start"));
                    var realtimeEnd = text(observation.path("realtime_end"));
                    var occurred = parseDate(date);
                    if (occurred.isBefore(request.since())) {
                        continue;
                    }
                    events.add(new MarketEvent(id,
                            String.join(":", series, date, nullToEmpty(realtimeStart),
                                    nullToEmpty(realtimeEnd), value),
                            "FRED_OBSERVATION", series + " observation: " + value, occurred,
                            List.of(), List.of(new EventIntelligenceService.MacroScope(
                                    "FRED", series, date, realtimeStart))));
                }
            } catch (RuntimeException exception) {
                failures.add(exception);
            }
        }
        return new CollectionResult(events, failures);
    }

    private CollectionResult bls(Request request) {
        var events = new ArrayList<MarketEvent>();
        var failures = new ArrayList<RuntimeException>();
        for (var series : configuration.scopes()) {
            if (events.size() >= request.maxEvents()) {
                break;
            }
            try {
                request.heartbeatCheck();
                var uri = UriComponentsBuilder.fromUri(base())
                        .path(path("/publicAPI/v2/timeseries/data/{identifier}"))
                        .queryParam("startyear", LocalDate.ofInstant(request.since(), ZoneOffset.UTC).getYear())
                        .queryParam("endyear", LocalDate.now(ZoneOffset.UTC).getYear())
                        .buildAndExpand(series).encode().toUri();
                var body = http.get(uri, configuration, heartbeat(request));
                request.heartbeatCheck();
                var data = json(body, series)
                        .path("Results").path("series").path(0).path("data");
                for (var observation : data) {
                    if (events.size() >= request.maxEvents()) {
                        break;
                    }
                    var year = text(observation.path("year"));
                    var period = text(observation.path("period"));
                    var value = text(observation.path("value"));
                    if (year == null || period == null || value == null) {
                        continue;
                    }
                    var occurred = blsDate(year, period);
                    if (occurred == null || occurred.isBefore(request.since())) {
                        continue;
                    }
                    events.add(new MarketEvent(id, String.join(":", series, year, period, value),
                            "BLS_OBSERVATION", series + " " + year + " " + period + ": " + value,
                            occurred, List.of(), List.of(new EventIntelligenceService.MacroScope(
                                    "BLS", series, year + "-" + period, null))));
                }
            } catch (RuntimeException exception) {
                failures.add(exception);
            }
        }
        return new CollectionResult(events, failures);
    }

    private CollectionResult bea(Request request) {
        var events = new ArrayList<MarketEvent>();
        var failures = new ArrayList<RuntimeException>();
        for (var scope : configuration.scopes()) {
            if (events.size() >= request.maxEvents()) {
                break;
            }
            try {
                var parts = scope.split("\\|", -1);
                if (parts.length != 5) {
                    throw new IllegalArgumentException("BEA scope must be dataset|table|line|geo|year");
                }
                request.heartbeatCheck();
                var uri = UriComponentsBuilder.fromUri(base())
                        .path(path("/api/data"))
                        .queryParam("UserID", configuration.apiKey())
                        .queryParam("method", "GetData")
                        .queryParam("datasetname", parts[0])
                        .queryParam("TableName", parts[1])
                        .queryParam("LineCode", parts[2])
                        .queryParam("GeoFIPS", parts[3])
                        .queryParam("Year", parts[4])
                        .queryParam("ResultFormat", "JSON")
                        .build().encode().toUri();
                var body = http.get(uri, configuration, heartbeat(request));
                request.heartbeatCheck();
                var data = json(body, scope)
                        .path("BEAAPI").path("Results").path("Data");
                for (var row : data) {
                    if (events.size() >= request.maxEvents()) {
                        break;
                    }
                    var period = text(row.path("TimePeriod"));
                    var value = text(row.path("DataValue"));
                    if (period == null || value == null) {
                        continue;
                    }
                    var occurred = parsePeriod(period);
                    if (occurred.isBefore(request.since())) {
                        continue;
                    }
                    var identity = String.join(":", scope, period, value);
                    events.add(new MarketEvent(id, identity, "BEA_OBSERVATION",
                            parts[0] + " " + period + ": " + value, occurred, List.of(),
                            List.of(new EventIntelligenceService.MacroScope(
                                    "BEA", scope, period, text(row.path("CL_UNIT"))))));
                }
            } catch (RuntimeException exception) {
                failures.add(exception);
            }
        }
        return new CollectionResult(events, failures);
    }

    private String get(String identifier, Request request) {
        request.heartbeatCheck();
        var body = http.get(resolve(identifier), configuration, heartbeat(request));
        request.heartbeatCheck();
        return body;
    }

    private URI resolve(String identifier) {
        return UriComponentsBuilder.fromUri(base())
                .path(path("/submissions/CIK{identifier}.json"))
                .buildAndExpand(identifier).encode().toUri();
    }

    private static java.util.function.BooleanSupplier heartbeat(Request request) {
        return () -> {
            request.heartbeatCheck();
            return true;
        };
    }

    private URI base() {
        return configuration.baseUrl();
    }

    private String path(String fallback) {
        return "/".equals(configuration.path()) ? fallback : configuration.path();
    }

    private URI url(String value) {
        try {
            var uri = URI.create(value);
            MarketEventHttpClient.requireConfiguredUrl(uri);
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("configured feed URL is invalid", exception);
        }
    }

    private void validate() {
        if (id == null || configuration == null || objectMapper == null || !configuration.enabled()) {
            throw new IllegalArgumentException("enabled market event provider configuration required");
        }
        if (id == MarketEventProviderId.IR || id == MarketEventProviderId.FED) {
            if (configuration.feedUrls().isEmpty()) {
                throw new IllegalArgumentException(id + " requires feedUrls");
            }
            configuration.feedUrls().values().forEach(this::url);
            return;
        }
        if (configuration.baseUrl() == null) {
            throw new IllegalArgumentException(id + " requires baseUrl");
        }
        MarketEventHttpClient.requireConfiguredUrl(configuration.baseUrl());
        if (id == MarketEventProviderId.SEC && configuration.userAgent().isBlank()) {
            throw new IllegalArgumentException("SEC requires userAgent");
        }
        if ((id == MarketEventProviderId.FRED || id == MarketEventProviderId.BEA)
                && configuration.apiKey().isBlank()) {
            throw new IllegalArgumentException(id + " requires apiKey");
        }
        if ((id == MarketEventProviderId.SEC && configuration.identifiers().isEmpty())
                || (id != MarketEventProviderId.SEC && configuration.scopes().isEmpty())) {
            throw new IllegalArgumentException(id + " requires configured scopes");
        }
    }

    private static String normalizeCik(String value) {
        var digits = value == null ? "" : value.trim();
        if (!digits.matches("\\d{1,10}")) {
            throw new IllegalArgumentException("SEC CIK is invalid");
        }
        return "%010d".formatted(Long.parseLong(digits));
    }

    private JsonNode json(String body, String provider) {
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException exception) {
            throw new MarketEventHttpClient.ProviderFailure(provider + "_INVALID_JSON", exception);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()
                ? null : node.asText();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return java.time.LocalDateTime.parse(value,
                                DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                        .toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignoredAgain) {
                return fallback;
            }
        }
    }

    private static Instant parseDate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("provider date is required");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    private static Instant blsDate(String year, String period) {
        if (period.startsWith("M") && period.length() == 3) {
            var month = Integer.parseInt(period.substring(1));
            if (month >= 1 && month <= 12) {
                return LocalDate.of(Integer.parseInt(year), month, 1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant();
            }
        }
        return null;
    }

    private static Instant parsePeriod(String period) {
        if (period.matches("\\d{4}")) {
            return LocalDate.of(Integer.parseInt(period), 1, 1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        if (period.matches("\\d{4}M\\d{2}")) {
            return blsDate(period.substring(0, 4), period.substring(4));
        }
        if (period.matches("\\d{4}Q[1-4]")) {
            var year = Integer.parseInt(period.substring(0, 4));
            var month = (Integer.parseInt(period.substring(5)) - 1) * 3 + 1;
            return LocalDate.of(year, month, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return parseDate(period + "-01-01");
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<FeedItem> feedItems(String body) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(body)));
            var nodes = document.getElementsByTagName("item");
            if (nodes.getLength() == 0) {
                nodes = document.getElementsByTagName("entry");
            }
            var items = new ArrayList<FeedItem>();
            for (var index = 0; index < nodes.getLength(); index++) {
                var node = nodes.item(index);
                items.add(new FeedItem(
                        child(node, "guid", "id", "link"),
                        child(node, "title", "summary"),
                        child(node, "pubDate", "published", "updated")));
            }
            return items;
        } catch (Exception exception) {
            throw new MarketEventHttpClient.ProviderFailure("INVALID_FEED", exception);
        }
    }

    private static String child(Node parent, String... names) {
        for (var name : names) {
            var children = parent.getChildNodes();
            for (var index = 0; index < children.getLength(); index++) {
                var child = children.item(index);
                if (name.equalsIgnoreCase(child.getLocalName())
                        || name.equalsIgnoreCase(child.getNodeName())) {
                    if (child instanceof Element element && element.hasAttribute("href")) {
                        return element.getAttribute("href");
                    }
                    return child.getTextContent().trim();
                }
            }
        }
        return null;
    }

    private static Instant feedInstant(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("feed publication time is required");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return OffsetDateTime.parse(value).toInstant();
            }
        }
    }

    private record FeedItem(String id, String title, String occurred) {
    }
}
