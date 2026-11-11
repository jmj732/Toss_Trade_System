package com.jmj.trade.broker.toss;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TossOpenApiVersionTest {

    @Test
    void pinsReducedOpenApiContractToTossVersion1228() throws IOException {
        var manifest = readManifest();

        assertThat(TossOpenApiContract.VERSION).isEqualTo("1.2.28");
        assertThat(manifest.info().version()).isEqualTo(TossOpenApiContract.VERSION);
        assertThat(manifest.paths().keySet()).containsExactlyInAnyOrder(
                "/oauth2/token",
                "/api/v1/accounts",
                "/api/v1/holdings",
                "/api/v1/prices",
                "/api/v1/orderbook",
                "/api/v1/candles",
                "/api/v1/exchange-rate",
                "/api/v1/market-calendar/KR",
                "/api/v1/market-calendar/US",
                "/api/v1/rankings",
                "/api/v1/buying-power",
                "/api/v1/sellable-quantity",
                "/api/v1/orders",
                "/api/v1/orders/{orderId}",
                "/api/v1/orders/{orderId}/modify",
                "/api/v1/orders/{orderId}/cancel",
                "/api/v1/stocks",
                "/api/v1/stocks/{symbol}/warnings");
    }

    @Test
    void stockDtosReadOnlyFieldsThatExistWithCompatibleTypesIn1228() throws IOException {
        var schemas = readManifest().components().schemas();

        assertParsedFieldsExist(TossApiDtos.StockInfo.class, schemas.get("StockInfo"));
        assertParsedFieldsExist(TossApiDtos.StockWarning.class, schemas.get("StockWarning"));
    }

    private static void assertParsedFieldsExist(Class<?> dto, Map<String, Object> schema) {
        assertThat(schema).isNotNull();
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Map<String, Object>>) schema.get("properties");
        for (RecordComponent component : dto.getRecordComponents()) {
            var name = component.getName();
            assertThat(properties).as("%s.%s exists in Toss 1.2.28", dto.getSimpleName(), name)
                    .containsKey(name);
            var expected = component.getType() == Boolean.class ? "boolean" : "string";
            assertThat(typesOf(properties.get(name).get("type")))
                    .as("%s.%s type", dto.getSimpleName(), name)
                    .contains((Object) expected);
        }
    }

    private static List<Object> typesOf(Object type) {
        return type instanceof List<?> list ? List.copyOf(list) : List.of(type);
    }

    private static OpenApiManifest readManifest() throws IOException {
        return new ObjectMapper().readValue(
                TossOpenApiVersionTest.class.getResourceAsStream("/contracts/toss-openapi-1.2.28-manifest.json"),
                OpenApiManifest.class);
    }

    private record OpenApiManifest(String openapi, Info info, Map<String, Object> paths, Components components) {
    }

    private record Info(String version) {
    }

    private record Components(Map<String, Map<String, Object>> schemas) {
    }
}
