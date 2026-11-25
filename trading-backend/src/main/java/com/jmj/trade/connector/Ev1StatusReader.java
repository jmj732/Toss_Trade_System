package com.jmj.trade.connector;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public final class Ev1StatusReader {
    private final Path manifestPath;
    private final Path runnerStatusPath;
    private final long maxAgeSeconds;
    private final ObjectMapper objectMapper;

    @Autowired
    public Ev1StatusReader(
            @Value("${ev1.manifest-path:/var/lib/ev1/forward/manifest.json}") Path manifestPath,
            @Value("${ev1.max-age-seconds:3600}") long maxAgeSeconds,
            @Value("${ev1.runner-status-path:/var/lib/ev1/runner-health.json}") Path runnerStatusPath,
            ObjectMapper objectMapper
    ) {
        this.manifestPath = manifestPath;
        this.runnerStatusPath = runnerStatusPath;
        this.maxAgeSeconds = maxAgeSeconds;
        this.objectMapper = objectMapper;
    }

    Ev1StatusReader(Path manifestPath, long maxAgeSeconds, ObjectMapper objectMapper) {
        this(manifestPath, maxAgeSeconds, manifestPath.resolveSibling("runner-health.json"), objectMapper);
    }

    public Map<String, Object> read() {
        if (maxAgeSeconds < 0) return error("malformed", "EV1 max age is negative");
        final JsonNode manifest;
        try {
            manifest = objectMapper.readTree(Files.readString(manifestPath));
        } catch (IOException | RuntimeException exception) {
            return error("missing", "EV1 manifest is unavailable");
        }
        if (manifest == null || !manifest.isObject() || !manifest.path("progress").isObject()) {
            return error("malformed", "EV1 manifest progress is unavailable");
        }
        var progress = manifest.path("progress");
        var errors = manifest.path("errors");
        if (!errors.isArray()) return error("malformed", "EV1 manifest errors is unavailable");
        var errorCount = progress.path("error_count");
        if (!errorCount.isMissingNode() && (!errorCount.isIntegralNumber() || errorCount.longValue() < 0)) {
            return error("malformed", "EV1 manifest error_count is invalid");
        }
        var count = Math.max(errorCount.isMissingNode() ? 0 : errorCount.longValue(), errors.size());
        var lastPoll = manifest.path("last_poll").asText(null);
        if (lastPoll == null || lastPoll.isBlank()) lastPoll = progress.path("last_poll").asText(null);
        Double age = null;
        if (lastPoll != null && !lastPoll.isBlank()) {
            try {
                age = (double) Duration.between(Instant.parse(lastPoll), Instant.now()).toSeconds();
            } catch (RuntimeException exception) {
                return error("malformed", "EV1 manifest last_poll is invalid");
            }
        }
        var stale = age == null || age > maxAgeSeconds;
        var runnerStatus = readRunnerStatus();
        var runnerError = runnerStatus != null && "error".equals(runnerStatus.get("status"));
        var status = count > 0 || runnerError ? "error" : stale ? "stale" : "healthy";
        var result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("health", "healthy".equals(status) ? "green" : "red");
        result.put("manifest", manifestPath.toString());
        result.put("error_count", count);
        result.put("last_poll_age_seconds", age);
        result.put("stale", stale);
        result.put("progress", objectMapper.convertValue(progress, Map.class));
        result.put("metrics", progress.path("metrics").isMissingNode() ? null
                : objectMapper.convertValue(progress.path("metrics"), Map.class));
        result.put("runner_status", runnerStatus);
        if (count > 0) result.put("error", "EV1 manifest contains recorder errors");
        else if (runnerError) result.put("error", "EV1 runner dependency failed");
        else if (stale) result.put("error", "EV1 manifest last poll is missing or stale");
        return result;
    }

    private Map<String, Object> readRunnerStatus() {
        if (!Files.isRegularFile(runnerStatusPath)) return null;
        try {
            var value = objectMapper.readTree(Files.readString(runnerStatusPath));
            if (value == null || !value.isObject()
                    || !Set.of("healthy", "error").contains(value.path("status").asText())) {
                return Map.of("status", "error", "component", "runner_status");
            }
            return Map.of("status", value.path("status").asText(),
                    "component", value.path("component").asText("unknown"),
                    "observed_at", value.path("observed_at").asText(""));
        } catch (IOException | RuntimeException exception) {
            return Map.of("status", "error", "component", "runner_status");
        }
    }

    private Map<String, Object> error(String status, String message) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("health", "red");
        result.put("manifest", manifestPath.toString());
        result.put("error", message);
        result.put("last_poll_age_seconds", null);
        result.put("progress", Map.of());
        result.put("metrics", null);
        result.put("runner_status", null);
        return result;
    }
}
