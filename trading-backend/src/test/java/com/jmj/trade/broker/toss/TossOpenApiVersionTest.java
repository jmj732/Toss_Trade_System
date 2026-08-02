package com.jmj.trade.broker.toss;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TossOpenApiVersionTest {

    @Test
    void pinsReducedOpenApiContractToTossVersion125() throws IOException {
        var manifest = new ObjectMapper().readValue(
                getClass().getResourceAsStream("/contracts/toss-openapi-1.2.5-manifest.json"),
                OpenApiManifest.class);

        assertThat(TossOpenApiContract.VERSION).isEqualTo("1.2.5");
        assertThat(manifest.info().version()).isEqualTo(TossOpenApiContract.VERSION);
        assertThat(manifest.paths().keySet()).containsExactlyInAnyOrder(
                "/oauth2/token",
                "/api/v1/accounts",
                "/api/v1/holdings",
                "/api/v1/prices",
                "/api/v1/buying-power",
                "/api/v1/orders",
                "/api/v1/orders/{orderId}",
                "/api/v1/orders/{orderId}/modify",
                "/api/v1/orders/{orderId}/cancel");
    }

    private record OpenApiManifest(String openapi, Info info, Map<String, Object> paths) {
    }

    private record Info(String version) {
    }
}
