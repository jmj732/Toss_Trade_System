package com.jmj.trade.broker.toss;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TossOpenApiVersionTest {

    private static final int MAX_DEPTH = 8;

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

    /** Every DTO that the client parses must match the 1.2.28 schema for its endpoint, field by field. */
    @Test
    void everyParsedDtoFieldExistsWithCompatibleTypeIn1228() throws IOException {
        var manifest = readManifest();
        var schemas = manifest.components().schemas();

        assertParsedFields(TossApiDtos.Account.class, schemas, "Account");
        assertParsedFields(TossApiDtos.Holdings.class, schemas, "HoldingsOverview");
        assertParsedFields(TossApiDtos.Price.class, schemas, "PriceResponse");
        assertParsedFields(TossApiDtos.OrderBook.class, schemas, "OrderbookResponse");
        assertParsedFields(TossApiDtos.CandleSeries.class, schemas, "CandlePageResponse");
        assertParsedFields(TossApiDtos.ExchangeRate.class, schemas, "ExchangeRateResponse");
        assertParsedFields(TossApiDtos.Rankings.class, schemas, "RankingResponse");
        assertParsedFields(TossApiDtos.BuyingPower.class, schemas, "BuyingPowerResponse");
        assertParsedFields(TossApiDtos.SellableQuantity.class, schemas, "SellableQuantityResponse");
        assertParsedFields(TossApiDtos.PaginatedOrderResponse.class, schemas, "PaginatedOrderResponse");
        assertParsedFields(TossApiDtos.Order.class, schemas, "Order");
        assertParsedFields(TossApiDtos.OrderResponse.class, schemas, "OrderResponse");
        assertParsedFields(TossApiDtos.OrderOperationResponse.class, schemas, "OrderOperationResponse");
        assertParsedFields(TossApiDtos.StockInfo.class, schemas, "StockInfo");
        assertParsedFields(TossApiDtos.StockWarning.class, schemas, "StockWarning");
    }

    @Test
    void dispatchEvidenceLabelIsDerivedFromThePinnedContractVersion() {
        assertThat(TossOpenApiContract.dispatchEvidenceLabel()).isEqualTo("toss-1.2.28");
    }

    @Test
    void marketCalendarPayloadsAreSchemaPresentButOpaqueToTheClient() throws IOException {
        var schemas = readManifest().components().schemas();

        assertThat(schemas).containsKeys("KrMarketCalendarResponse", "UsMarketCalendarResponse");
    }

    private static void assertParsedFields(Class<?> dto, Map<String, Map<String, Object>> schemas,
                                           String schemaName) {
        assertThat(schemas).as("1.2.28 schema %s", schemaName).containsKey(schemaName);
        assertFields(dto, schemas.get(schemaName), dto.getSimpleName(), 0);
    }

    private static void assertFields(Class<?> dto, Map<String, Object> node, String path, int depth) {
        assertThat(depth).as("recursion bound at %s", path).isLessThan(MAX_DEPTH);
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Map<String, Object>>) node.get("properties");
        assertThat(properties).as("%s has properties in 1.2.28", path).isNotNull();
        for (RecordComponent component : dto.getRecordComponents()) {
            var name = component.getName();
            var fieldPath = path + "." + name;
            assertThat(properties).as("%s exists in Toss 1.2.28", fieldPath).containsKey(name);
            var child = properties.get(name);
            var type = component.getType();
            if (List.class.isAssignableFrom(type)) {
                var element = (Class<?>) ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
                assertThat(typesOf(child.get("type"))).as("%s is an array", fieldPath).contains((Object) "array");
                assertElement(element, asMap(child.get("items")), fieldPath + "[]", depth + 1);
            } else if (type.isRecord()) {
                assertThat(typesOf(child.get("type"))).as("%s is an object", fieldPath).contains((Object) "object");
                if (Boolean.TRUE.equals(child.get("opaque"))) {
                    // 1.2.28 declares no properties here; the nested fields are tracked as unpinned, not silently passed.
                    assertThat(child.get("opaque")).as("%s is opaque in 1.2.28", fieldPath).isEqualTo(true);
                } else {
                    assertFields(type, child, fieldPath, depth + 1);
                }
            } else {
                assertThat(typesOf(child.get("type")))
                        .as("%s scalar type (%s)", fieldPath, type.getSimpleName())
                        .containsAnyElementsOf(expectedTypes(type));
            }
        }
    }

    private static void assertElement(Class<?> element, Map<String, Object> items, String path, int depth) {
        if (element.isRecord()) {
            assertFields(element, items, path, depth);
        } else {
            assertThat(typesOf(items.get("type"))).as("%s element type", path)
                    .containsAnyElementsOf(expectedTypes(element));
        }
    }

    private static List<Object> expectedTypes(Class<?> type) {
        if (type == String.class) return List.of("string");
        if (type == Boolean.class || type == boolean.class) return List.of("boolean");
        if (type == Long.class || type == long.class || type == Integer.class || type == int.class) {
            return List.of("integer", "number");
        }
        if (type == BigDecimal.class) return List.of("number", "string");
        if (type == java.time.Duration.class) return List.of("string");
        throw new AssertionError("unmapped DTO scalar type " + type.getName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).as("array items schema").isInstanceOf(Map.class);
        return (Map<String, Object>) value;
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
