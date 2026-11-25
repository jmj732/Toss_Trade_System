package com.jmj.trade.connector;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class Ev1StatusReaderTest {
    @Test
    void readsPersistedHealthyStatusWithoutDatabase() throws Exception {
        Path manifest = Files.createTempFile("ev1-status", ".json");
        try {
            Files.writeString(manifest, """
                    {"progress":{"polls":2,"error_count":0,"metrics":{"status":"NO_DATA"}},
                     "last_poll":"%s","errors":[]}
                    """.formatted(Instant.now().minusSeconds(5)));
            var result = new Ev1StatusReader(manifest, 60, new ObjectMapper()).read();
            assertThat(result).containsEntry("status", "healthy").containsEntry("health", "green");
            assertThat(result).containsKey("metrics");
            var runner = manifest.resolveSibling("runner-health.json");
            Files.writeString(runner, "{\"status\":\"error\",\"component\":\"sec_poll\"}");
            var degraded = new Ev1StatusReader(manifest, 60, new ObjectMapper()).read();
            assertThat(degraded).containsEntry("status", "error").containsEntry("health", "red");
            Files.deleteIfExists(runner);
        } finally {
            Files.deleteIfExists(manifest);
            Files.deleteIfExists(manifest.resolveSibling("runner-health.json"));
        }
    }

    @Test
    void missingManifestIsReadOnlyUnavailableStatus() {
        var result = new Ev1StatusReader(Path.of("/tmp/ev1-status-missing"), 60, new ObjectMapper()).read();
        assertThat(result).containsEntry("status", "missing").containsEntry("health", "red");
    }
}
