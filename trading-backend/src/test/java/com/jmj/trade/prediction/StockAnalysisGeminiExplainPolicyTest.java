package com.jmj.trade.prediction;

import com.jmj.trade.marketdata.StockDataProviderId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StockAnalysisGeminiExplainPolicyTest {

    private static final UUID SNAPSHOT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant AS_OF = Instant.parse("2026-08-01T20:00:00Z");
    private static final Instant COLLECTED_AT = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void citationIdsAreStableAndClaimsWithoutGroundedNonNumericTextAreRemoved() {
        var input = new StockAnalysisGeminiExplainPolicy.Snapshot(
                SNAPSHOT_ID,
                "AAPL",
                "1",
                COLLECTED_AT,
                List.of(
                        observation("quote.price", "200", StockDataProviderId.FMP),
                        observation("technical.rsi14", "55", StockDataProviderId.FMP)));

        var citations = StockAnalysisGeminiExplainPolicy.citations(input);
        assertThat(citations).extracting(StockAnalysisGeminiExplainPolicy.Citation::id)
                .containsExactly(
                        "snapshot:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:observation:0",
                        "snapshot:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:observation:1");
        assertThat(StockAnalysisGeminiExplainPolicy.citations(input))
                .containsExactlyElementsOf(citations);

        var claims = new StockAnalysisGeminiExplainPolicy.GeneratedClaims(
                List.of(
                        new StockAnalysisGeminiExplainPolicy.Claim(
                                "가격 흐름은 기술적 근거와 함께 관찰됩니다.", List.of(citations.getFirst().id())),
                        new StockAnalysisGeminiExplainPolicy.Claim("12.5% 상승합니다.", List.of(citations.getFirst().id())),
                        new StockAnalysisGeminiExplainPolicy.Claim("근거가 없습니다.", List.of()),
                        new StockAnalysisGeminiExplainPolicy.Claim("외부 근거", List.of("unknown"))),
                List.of(), List.of(), List.of());

        var sanitized = StockAnalysisGeminiExplainPolicy.sanitize(
                claims,
                citations.stream().map(StockAnalysisGeminiExplainPolicy.Citation::id).toList());

        assertThat(sanitized.evidence()).containsExactly(claims.evidence().getFirst());
        assertThat(sanitized.removedClaims()).isEqualTo(3);
    }

    @Test
    void generatedClaimsAreDeserializable() throws Exception {
        var json = """
                {"evidence":[{"text":"관찰됩니다.","citationIds":["citation"]}],
                 "counterArguments":[],"missingData":[],"invalidationConditions":[]}
                """;
        var claims = new ObjectMapper().readValue(json, StockAnalysisGeminiExplainPolicy.GeneratedClaims.class);
        assertThat(claims.evidence()).hasSize(1);
    }

    @Test
    void geminiEnvelopeIsDeserializable() throws Exception {
        var generated = "{\"evidence\":[{\"text\":\"관찰됩니다.\",\"citationIds\":[\"citation\"]}],"
                + "\"counterArguments\":[],\"missingData\":[],\"invalidationConditions\":[]}";
        var mapper = new ObjectMapper();
        var body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                + mapper.writeValueAsString(generated) + "}]}}]}";
        var root = mapper.readTree(body);
        var text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(null);
        assertThat(mapper.readValue(text, StockAnalysisGeminiExplainPolicy.GeneratedClaims.class)
                .evidence()).hasSize(1);
    }

    private static StockAnalysisGeminiExplainPolicy.Observation observation(
            String field, String value, StockDataProviderId provider) {
        return new StockAnalysisGeminiExplainPolicy.Observation(
                field,
                JsonNodeFactory.instance.textNode(value),
                "USD",
                null,
                "AAPL",
                provider.name(),
                AS_OF,
                COLLECTED_AT,
                List.of());
    }
}
