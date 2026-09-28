package com.jmj.trade.monitoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class MonitoringEvaluationContract {

    private MonitoringEvaluationContract() {
    }

    record Request(
            UUID requestId,
            String schemaVersion,
            Instant asOf,
            MarketInput market,
            PortfolioInput portfolio,
            List<EventInput> events,
            List<WatchlistInput> watchlist
    ) {
    }

    record MarketInput(List<MetricSeries> series, List<ShockInput> shocks, List<IncidentInput> incidents) {
    }

    record MetricSeries(
            String metric,
            String unit,
            String source,
            String cadence,
            List<MetricPoint> points
    ) {
    }

    record MetricPoint(String value, Instant asOf, Instant collectedAt) {
    }

    record ShockInput(
            String category,
            String severity,
            String evidence,
            String source,
            Instant asOf
    ) {
    }

    record IncidentInput(
            String kind,
            boolean forcedDeleveraging,
            String evidence,
            String source,
            String sourceType,
            boolean official,
            Instant asOf
    ) {
    }

    record PortfolioInput(
            Instant asOf,
            List<PositionInput> positions,
            RiskPolicyInput riskPolicy
    ) {
    }

    record PositionInput(
            String symbol,
            BigDecimal quantity,
            BigDecimal avgCost,
            BigDecimal marketValue,
            BigDecimal weight,
            String sector,
            String factor,
            BigDecimal beta,
            BigDecimal correlation,
            String thesis,
            String primaryAlpha,
            Map<String, String> conditions,
            List<InvalidationEvidence> invalidationEvidence
    ) {
    }

    record RiskPolicyInput(BigDecimal maxConcentration, BigDecimal maxSectorWeight, BigDecimal maxFactorWeight) {
    }

    record InvalidationEvidence(String kind, String evidence, String source, Instant asOf) {
    }

    record EventInput(
            String sourceEventId,
            String symbol,
            String kind,
            String sourceType,
            String sourceUrl,
            String title,
            String evidence,
            Instant publishedAt,
            Instant collectedAt,
            boolean official
    ) {
    }

    record EventBatch(
            List<EventInput> events,
            List<ShockInput> shocks,
            List<IncidentInput> incidents
    ) {
    }

    record WatchlistInput(
            String symbol,
            String status,
            WatchlistLevels levels,
            BigDecimal price,
            BigDecimal volumeMultiple,
            BigDecimal relativeStrength,
            Instant asOf,
            String source,
            Instant collectedAt
    ) {
    }

    record WatchlistLevels(
            MonitoringWatchlistService.Range prepare,
            MonitoringWatchlistService.Range confirm,
            MonitoringWatchlistService.Range pullback,
            MonitoringWatchlistService.Range invalidate
    ) {
    }
}
