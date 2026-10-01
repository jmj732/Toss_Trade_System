package com.jmj.trade.sheets;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class GoogleSheetsClientContractTest {

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void exchangesJwtAndReadsValuesWithBearerToken() {
        server.stubFor(post("/token")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"sheet-token\",\"expires_in\":3600}")));
        server.stubFor(get(urlPathEqualTo("/v4/spreadsheets/sheet-1/values/Account%20State%21A1%3AC2"))
                .withHeader("Authorization", equalTo("Bearer sheet-token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"range":"Account State!A1:C2","values":[["Ticker","Quantity",null],["ABC",2,""]]}
                                """)));

        var values = client().readValues("sheet-1", "Account State!A1:C2");

        assertThat(values.range()).isEqualTo("Account State!A1:C2");
        assertThat(values.values()).containsExactly(
                Arrays.asList("Ticker", "Quantity", null),
                List.of("ABC", 2L, ""));
        server.verify(postRequestedFor(urlEqualTo("/token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"))
                .withRequestBody(containing("assertion=")));
    }

    @Test
    void batchUpdateSendsRawValuesAndDoesNotRepeatTokenExchangeForCachedToken() {
        server.stubFor(post("/token")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"sheet-token\",\"expires_in\":3600}")));
        server.stubFor(post(urlPathEqualTo("/v4/spreadsheets/sheet-1/values:batchUpdate"))
                .withQueryParam("valueInputOption", equalTo("RAW"))
                .withHeader("Authorization", equalTo("Bearer sheet-token"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        var client = client();
        client.batchUpdateValues("sheet-1", List.of(new GoogleSheetsClient.SheetValueRange(
                "Account State!A1:B2", List.of(List.of("ABC", 2), Arrays.asList("USD", null)))));
        client.batchUpdateValues("sheet-1", List.of(new GoogleSheetsClient.SheetValueRange(
                "Orders!A1:B1", List.of(List.of("order-1", "FILLED")))));

        server.verify(2, postRequestedFor(urlPathEqualTo("/v4/spreadsheets/sheet-1/values:batchUpdate")));
        server.verify(1, postRequestedFor(urlEqualTo("/token")));
    }

    @Test
    void createsOnlyMissingInvestmentTabs() {
        server.stubFor(post("/token")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"sheet-token\",\"expires_in\":3600}")));
        server.stubFor(get(urlPathEqualTo("/v4/spreadsheets/sheet-1"))
                .withQueryParam("fields", equalTo("sheets.properties.title"))
                .withHeader("Authorization", equalTo("Bearer sheet-token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"sheets\":[{\"properties\":{\"title\":\"Security Snapshot\"}}]}")));
        server.stubFor(post(urlPathEqualTo("/v4/spreadsheets/sheet-1:batchUpdate"))
                .withHeader("Authorization", equalTo("Bearer sheet-token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));

        client().ensureSheets("sheet-1", List.of("Security Snapshot", "Decision Ledger"));

        server.verify(1, postRequestedFor(urlPathEqualTo("/v4/spreadsheets/sheet-1:batchUpdate"))
                .withRequestBody(containing("Decision Ledger")));
    }

    @Test
    void readsSheetIdsAlongsideTitles() {
        server.stubFor(post("/token")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"sheet-token\",\"expires_in\":3600}")));
        server.stubFor(get(urlPathEqualTo("/v4/spreadsheets/sheet-1"))
                .withQueryParam("fields", equalTo("sheets.properties(sheetId,title)"))
                .withHeader("Authorization", equalTo("Bearer sheet-token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"sheets\":[{\"properties\":{\"sheetId\":7,\"title\":\"Risk Policy\"}}]}")));

        assertThat(client().sheetIdsByTitle("sheet-1")).containsExactly(Map.entry("Risk Policy", 7));
    }

    @Test
    void duplicatesSheetsUsingAtomicSpreadsheetBatchUpdate() {
        server.stubFor(post("/token")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"sheet-token\",\"expires_in\":3600}")));
        server.stubFor(post(urlPathEqualTo("/v4/spreadsheets/sheet-1:batchUpdate"))
                .withHeader("Authorization", equalTo("Bearer sheet-token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));

        client().duplicateSheets("sheet-1", Map.of("Security Snapshot Legacy before DB", 7));

        server.verify(1, postRequestedFor(urlPathEqualTo("/v4/spreadsheets/sheet-1:batchUpdate"))
                .withRequestBody(containing("duplicateSheet"))
                .withRequestBody(containing("sourceSheetId"))
                .withRequestBody(containing("Security Snapshot Legacy before DB")));
    }

    @Test
    void refreshesTokenOnceAfterUnauthorizedResponse() {
        server.stubFor(post("/token")
                .inScenario("token refresh")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"expired\",\"expires_in\":3600}"))
                .willSetStateTo("refreshed"));
        server.stubFor(post("/token")
                .inScenario("token refresh")
                .whenScenarioStateIs("refreshed")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"fresh\",\"expires_in\":3600}")));
        server.stubFor(get(urlPathEqualTo("/v4/spreadsheets/sheet-1/values/A1"))
                .inScenario("sheet refresh")
                .whenScenarioStateIs("Started")
                .withHeader("Authorization", equalTo("Bearer expired"))
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        server.stubFor(get(urlPathEqualTo("/v4/spreadsheets/sheet-1/values/A1"))
                .inScenario("sheet refresh")
                .whenScenarioStateIs("retried")
                .withHeader("Authorization", equalTo("Bearer fresh"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"range\":\"A1\",\"values\":[]}")));

        var values = client().readValues("sheet-1", "A1");

        assertThat(values.values()).isEmpty();
        server.verify(2, postRequestedFor(urlEqualTo("/token")));
    }

    private GoogleSheetsClient client() {
        var base = URI.create(server.baseUrl());
        var credentials = GoogleServiceAccountCredentials.fromJson(serviceAccountJson(base.resolve("/token")));
        var clock = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);
        var tokens = new GoogleServiceAccountTokenProvider(
                credentials, base.resolve("/token"), Duration.ofSeconds(2), Duration.ofSeconds(2), clock);
        return new GoogleSheetsClient(credentials, tokens, base, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private String serviceAccountJson(URI tokenUri) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var key = generator.generateKeyPair().getPrivate().getEncoded();
            var pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key)
                    + "\n-----END PRIVATE KEY-----\n";
            return """
                    {"client_email":"sheets-test@example.invalid","private_key":"%s","token_uri":"%s"}
                    """.formatted(jsonEscape(pem), tokenUri);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n");
    }
}
