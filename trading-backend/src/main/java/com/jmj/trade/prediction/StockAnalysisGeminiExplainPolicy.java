package com.jmj.trade.prediction;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class StockAnalysisGeminiExplainPolicy {

    private static final Pattern NUMERIC_PROSE = Pattern.compile(
            "(?iu).*(\\p{N}|[%€£¥₹₩]|\\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|hundred|thousand|million|billion|percent|percentage|probability|expected\\s+return|expected\\s+loss|returns?)\\b|(?:퍼센트|확률|기대수익|수익률|최대손실|(?:영|일|이|삼|사|오|육|칠|팔|구|십|백|천|만)(?:십|백|천|만|억|조))).*",
            Pattern.DOTALL);

    private StockAnalysisGeminiExplainPolicy() {
    }

    public static List<Citation> citations(Snapshot input) {
        var result = new ArrayList<Citation>();
        for (var index = 0; index < input.observations().size(); index++) {
            var observation = input.observations().get(index);
            result.add(new Citation(
                    citationId(input.snapshotId(), index),
                    observation.field(),
                    observation.provider(),
                    observation.asOf(),
                    observation.collectedAt(),
                    observation.missingData()));
        }
        return List.copyOf(result);
    }

    public static SanitizedClaims sanitize(GeneratedClaims claims, List<String> allowedCitationIds) {
        var allowed = Set.copyOf(allowedCitationIds == null ? List.of() : allowedCitationIds);
        if (claims == null) {
            return new SanitizedClaims(List.of(), List.of(), List.of(), List.of(), 1);
        }
        var removed = new int[]{0};
        return new SanitizedClaims(
                sanitize(claims.evidence(), allowed, removed),
                sanitize(claims.counterArguments(), allowed, removed),
                sanitize(claims.missingData(), allowed, removed),
                sanitize(claims.invalidationConditions(), allowed, removed),
                removed[0]);
    }

    private static List<Claim> sanitize(List<Claim> claims, Set<String> allowed, int[] removed) {
        var result = new ArrayList<Claim>();
        if (claims == null) {
            return List.of();
        }
        for (var claim : claims) {
            if (claim == null || claim.text() == null || claim.text().isBlank()
                    || claim.citationIds().isEmpty()
                    || claim.citationIds().stream().anyMatch(id -> id == null || !allowed.contains(id))
                    || NUMERIC_PROSE.matcher(claim.text()).matches()) {
                removed[0]++;
            } else {
                result.add(claim);
            }
        }
        return List.copyOf(result);
    }

    private static String citationId(UUID snapshotId, int index) {
        return "snapshot:%s:observation:%d".formatted(snapshotId, index);
    }

    public record Citation(
            String id,
            String field,
            String provider,
            java.time.Instant asOf,
            java.time.Instant collectedAt,
            List<String> missingData
    ) {
        public Citation {
            missingData = missingData == null ? List.of() : List.copyOf(missingData);
        }
    }

    record Snapshot(
            UUID snapshotId,
            String symbol,
            String schemaVersion,
            Instant collectedAt,
            List<Observation> observations
    ) {
        Snapshot {
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(symbol, "symbol");
            observations = observations == null ? List.of() : List.copyOf(observations);
        }
    }

    record Observation(
            String field,
            JsonNode value,
            String unit,
            String period,
            String identifier,
            String provider,
            Instant asOf,
            Instant collectedAt,
            List<String> missingData
    ) {
        Observation {
            missingData = missingData == null ? List.of() : List.copyOf(missingData);
        }
    }

    public record Claim(String text, List<String> citationIds) {
        public Claim {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }

    public record GeneratedClaims(
            List<Claim> evidence,
            List<Claim> counterArguments,
            List<Claim> missingData,
            List<Claim> invalidationConditions
    ) {
        public GeneratedClaims {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            counterArguments = counterArguments == null ? List.of() : List.copyOf(counterArguments);
            missingData = missingData == null ? List.of() : List.copyOf(missingData);
            invalidationConditions = invalidationConditions == null ? List.of() : List.copyOf(invalidationConditions);
        }
    }

    public record SanitizedClaims(
            List<Claim> evidence,
            List<Claim> counterArguments,
            List<Claim> missingData,
            List<Claim> invalidationConditions,
            int removedClaims
    ) {
    }
}
