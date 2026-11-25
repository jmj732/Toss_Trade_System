package com.jmj.trade.connector;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class Ev1McpContractTest {
    @Test
    void exposesOnlyReadOnlyEv1StatusTool() throws Exception {
        Path manifest = Files.createTempFile("ev1-mcp", ".json");
        try {
            Files.writeString(manifest, "{\"progress\":{\"error_count\":0},\"last_poll\":\""
                    + Instant.now().toString() + "\",\"errors\":[]}");
            var mapper = new ObjectMapper();
            var protocol = new ConnectorMcpProtocol(null, null, null, null, null, null, null, null,
                    null, null, new Ev1StatusReader(manifest, 60, mapper), mapper);
            var list = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1)
                    .put("method", "tools/list");
            var tools = protocol.handle(list, null, null).path("result").path("tools");
            var ev1 = java.util.stream.StreamSupport.stream(tools.spliterator(), false)
                    .filter(tool -> "get_ev1_status".equals(tool.path("name").asText()))
                    .findFirst().orElseThrow();
            assertThat(ev1.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(ev1.path("securitySchemes").get(0).path("scopes").get(0).asText())
                    .isEqualTo("connector:read");

            var call = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 2)
                    .put("method", "tools/call");
            call.set("params", mapper.createObjectNode().put("name", "get_ev1_status")
                    .set("arguments", mapper.createObjectNode()));
            assertThat(protocol.handle(call, null, null).path("result").path("structuredContent")
                    .path("status").asText()).isEqualTo("healthy");
        } finally {
            Files.deleteIfExists(manifest);
        }
    }
}
