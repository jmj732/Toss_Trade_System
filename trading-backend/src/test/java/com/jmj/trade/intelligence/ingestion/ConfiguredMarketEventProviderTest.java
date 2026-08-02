package com.jmj.trade.intelligence.ingestion;

import com.jmj.trade.intelligence.EventIntelligenceService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredMarketEventProviderTest {

    private final AtomicReference<String> feed = new AtomicReference<>("""
            <rss><channel><item><guid>feed-1</guid><title>Release</title>
            <pubDate>Sat, 01 Aug 2026 12:00:00 GMT</pubDate></item></channel></rss>
            """);
    private HttpServer server;
    private URI base;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var body = switch (path) {
                case "/submissions/CIK0001045810.json" -> """
                        {"filings":{"recent":{"accessionNumber":["0001045810-26-000001"],
                        "acceptanceDateTime":["20260801120000"],"filingDate":["2026-08-01"],
                        "form":["8-K"]}}}
                        """;
                case "/fred/series/observations" -> """
                        {"observations":[{"date":"2026-08-01","value":"123.4",
                        "realtime_start":"2026-08-02","realtime_end":"2026-08-08"}]}
                        """;
                case "/publicAPI/v2/timeseries/data/CPALTT01USM657N" -> """
                        {"Results":{"series":[{"data":[{"year":"2026","period":"M07",
                        "value":"3.1"}]}]}}
                        """;
                case "/api/data" -> """
                        {"BEAAPI":{"Results":{"Data":[{"TimePeriod":"2026Q3",
                        "DataValue":"42.0","CL_UNIT":"USD"}]}}}
                        """;
                case "/feed" -> feed.get();
                case "/broken" -> "<rss>";
                default -> "{}";
            };
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        base = URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void normalizesAllConfiguredProviderPayloadsAndStableIdentities() {
        var request = new MarketEventProvider.Request(Set.of("NVDA"),
                Instant.parse("2026-07-01T00:00:00Z"), 10);

        var sec = provider(MarketEventProviderId.SEC).collect(request).getFirst();
        assertThat(sec.sourceEventId()).isEqualTo("CIK0001045810:0001045810-26-000001");
        assertThat(sec.occurredAt()).isEqualTo(Instant.parse("2026-08-01T12:00:00Z"));

        var ir = provider(MarketEventProviderId.IR).collect(request).getFirst();
        assertThat(ir.sourceEventId()).endsWith(":feed-1");
        assertThat(ir.affectedSymbols()).containsExactly("NVDA");

        var fed = provider(MarketEventProviderId.FED).collect(request).getFirst();
        assertThat(fed.macroScope()).singleElement().extracting(
                EventIntelligenceService.MacroScope::identifier).isEqualTo("FOMC");

        var fred = provider(MarketEventProviderId.FRED).collect(request).getFirst();
        assertThat(fred.sourceEventId()).contains("CPIAUCSL:2026-08-01");
        assertThat(fred.macroScope()).singleElement().extracting(
                EventIntelligenceService.MacroScope::vintage).isEqualTo("2026-08-02");

        var bls = provider(MarketEventProviderId.BLS).collect(request).getFirst();
        assertThat(bls.sourceEventId()).isEqualTo("CPALTT01USM657N:2026:M07:3.1");

        var bea = provider(MarketEventProviderId.BEA).collect(request).getFirst();
        assertThat(bea.sourceEventId()).contains("2026Q3:42.0");
        assertThat(bea.occurredAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void rejectsExternalEntitiesInConfiguredFeeds() {
        feed.set("""
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss><channel><item><guid>&xxe;</guid>
                <pubDate>Sat, 01 Aug 2026 12:00:00 GMT</pubDate></item></channel></rss>
                """);

        assertThatThrownBy(() -> provider(MarketEventProviderId.IR).collect(
                new MarketEventProvider.Request(Set.of("NVDA"),
                        Instant.parse("2026-07-01T00:00:00Z"), 10)))
                .isInstanceOf(MarketEventHttpClient.ProviderFailure.class);
    }

    @Test
    void keepsSuccessfulFeedsWhenAnotherConfiguredFeedFails() {
        var configuration = new MarketEventIngestionProperties.ProviderConfiguration(
                true, null, "/", "", "", Map.of(),
                Map.of("NVDA", base + "/feed", "MSFT", base + "/broken"), List.of(),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ZERO);

        var result = new ConfiguredMarketEventProvider(
                MarketEventProviderId.IR, configuration, new ObjectMapper())
                .collectWithFailures(new MarketEventProvider.Request(Set.of("NVDA", "MSFT"),
                        Instant.parse("2026-07-01T00:00:00Z"), 10));

        assertThat(result.events()).hasSize(1);
        assertThat(result.events().getFirst().affectedSymbols()).containsExactly("NVDA");
        assertThat(result.failures()).hasSize(1);
    }

    private MarketEventProvider provider(MarketEventProviderId id) {
        var feedUrls = id == MarketEventProviderId.IR
                ? Map.of("NVDA", base + "/feed")
                : id == MarketEventProviderId.FED ? Map.of("FOMC", base + "/feed") : Map.<String, String>of();
        var scopes = switch (id) {
            case FRED -> List.of("CPIAUCSL");
            case BLS -> List.of("CPALTT01USM657N");
            case BEA -> List.of("NIPA|T10101|1|00000|2026");
            default -> List.<String>of();
        };
        var identifiers = id == MarketEventProviderId.SEC
                ? Map.of("NVDA", "0001045810") : Map.<String, String>of();
        var configuration = new MarketEventIngestionProperties.ProviderConfiguration(
                true, id == MarketEventProviderId.IR || id == MarketEventProviderId.FED ? null : base,
                "/", id == MarketEventProviderId.FRED || id == MarketEventProviderId.BEA ? "key" : "",
                id == MarketEventProviderId.SEC ? "trade-test/1.0 contact@example.com" : "",
                identifiers, feedUrls, scopes, Duration.ofSeconds(1), Duration.ofSeconds(1), 0,
                Duration.ZERO);
        return new ConfiguredMarketEventProvider(id, configuration, new ObjectMapper());
    }
}
