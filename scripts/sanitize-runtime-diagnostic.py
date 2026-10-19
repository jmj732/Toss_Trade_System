#!/usr/bin/env python3
"""Allowlist and validate the remote diagnostic before it reaches logs or an artifact."""

from __future__ import annotations

import json
import math
import re
import sys
from pathlib import Path
from typing import Any


FAILURE = {"schemaVersion": 1, "ok": False, "error": "UNTRUSTED_REMOTE_OUTPUT"}
TIMESTAMP = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{3}Z")
DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")
CODE = re.compile(r"[A-Z0-9_]{1,120}")


def require_keys(value: Any, keys: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError("invalid object shape")
    return value


def require_count(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("invalid count")
    return value


def require_optional(value: Any, pattern: re.Pattern[str]) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise ValueError("invalid optional field")
    return value


def require_optional_status(value: Any, allowed: set[str]) -> str | None:
    if value is not None and value not in allowed:
        raise ValueError("invalid attempt status")
    return value


def require_bool(value: Any) -> bool:
    if not isinstance(value, bool):
        raise ValueError("invalid boolean")
    return value


def require_optional_bool(value: Any) -> bool | None:
    if value is not None and not isinstance(value, bool):
        raise ValueError("invalid optional boolean")
    return value


def require_optional_number(value: Any, minimum: float, maximum: float) -> int | float | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError("invalid optional number")
    if not minimum <= value <= maximum:
        raise ValueError("optional number outside allowed range")
    return value


def sanitize_portfolio_freshness(raw: Any) -> dict[str, Any]:
    portfolio = require_keys(
        raw, {"querySuccess", "observedAt", "latestAttempt", "attemptCountsLast24h", "latestAccepted"}
    )
    latest = require_keys(portfolio["latestAttempt"], {"status", "attemptedAt", "errorCode"})
    counts = require_keys(portfolio["attemptCountsLast24h"], {"SUCCEEDED", "PARTIAL", "FAILED"})
    accepted = require_keys(
        portfolio["latestAccepted"],
        {
            "status",
            "attemptedAt",
            "account1AsOf",
            "sessionReason",
            "sessionReferenceAt",
            "nextDeclaredIntervalStartsAt",
            "manualStatus",
            "manualAsOf",
            "manualReadAt",
            "positionsPriceSyncedAtPresent",
            "positionsPriceSyncedAtMin",
            "positionsPriceSyncedAtMax",
        },
    )
    return {
        "querySuccess": require_bool(portfolio["querySuccess"]),
        "observedAt": require_optional(portfolio["observedAt"], TIMESTAMP),
        "latestAttempt": {
            "status": require_optional_status(latest["status"], {"SUCCEEDED", "PARTIAL", "FAILED"}),
            "attemptedAt": require_optional(latest["attemptedAt"], TIMESTAMP),
            "errorCode": require_optional(latest["errorCode"], CODE),
        },
        "attemptCountsLast24h": {key: require_count(counts[key]) for key in ("SUCCEEDED", "PARTIAL", "FAILED")},
        "latestAccepted": {
            "status": require_optional_status(accepted["status"], {"SUCCEEDED", "PARTIAL"}),
            "attemptedAt": require_optional(accepted["attemptedAt"], TIMESTAMP),
            "account1AsOf": require_optional(accepted["account1AsOf"], TIMESTAMP),
            "sessionReason": require_optional(accepted["sessionReason"], CODE),
            "sessionReferenceAt": require_optional(accepted["sessionReferenceAt"], TIMESTAMP),
            "nextDeclaredIntervalStartsAt": require_optional(accepted["nextDeclaredIntervalStartsAt"], TIMESTAMP),
            "manualStatus": require_optional(accepted["manualStatus"], CODE),
            "manualAsOf": require_optional(accepted["manualAsOf"], DATE),
            "manualReadAt": require_optional(accepted["manualReadAt"], TIMESTAMP),
            "positionsPriceSyncedAtPresent": require_bool(accepted["positionsPriceSyncedAtPresent"]),
            "positionsPriceSyncedAtMin": require_optional(accepted["positionsPriceSyncedAtMin"], TIMESTAMP),
            "positionsPriceSyncedAtMax": require_optional(accepted["positionsPriceSyncedAtMax"], TIMESTAMP),
        },
    }


def sanitize_http_diagnostics(raw: Any) -> dict[str, Any]:
    diagnostics = require_keys(raw, {"backendRuntime", "probes", "recentExceptions"})
    runtime = require_keys(
        diagnostics["backendRuntime"],
        {
            "available", "oomKilled", "restartCount", "startedAt", "memoryUsageBytes",
            "memoryLimitBytes", "pidsCurrent", "pidsLimit", "healthcheckExitCodes",
        },
    )
    runtime_available = require_bool(runtime["available"])
    runtime_oom_killed = require_optional_bool(runtime["oomKilled"])
    runtime_counts = {
        key: None if runtime[key] is None else require_count(runtime[key])
        for key in ("restartCount", "memoryUsageBytes", "memoryLimitBytes", "pidsCurrent", "pidsLimit")
    }
    if runtime_counts["restartCount"] is not None and runtime_counts["restartCount"] > 1_000_000_000:
        raise ValueError("restart count outside allowed range")
    for key in ("memoryUsageBytes", "memoryLimitBytes"):
        if runtime_counts[key] is not None and runtime_counts[key] > 2**63 - 1:
            raise ValueError("memory count outside allowed range")
    for key in ("pidsCurrent", "pidsLimit"):
        if runtime_counts[key] is not None and runtime_counts[key] > 1_000_000:
            raise ValueError("pids count outside allowed range")
    started_at = require_optional(runtime["startedAt"], TIMESTAMP)
    healthcheck_codes = runtime["healthcheckExitCodes"]
    if not isinstance(healthcheck_codes, list) or len(healthcheck_codes) > 20:
        raise ValueError("invalid healthcheck history")
    healthcheck_codes = [require_count(code) for code in healthcheck_codes]
    if any(code > 255 for code in healthcheck_codes):
        raise ValueError("invalid healthcheck exit code")
    if not runtime_available and (
        runtime_oom_killed is not None or any(value is not None for value in runtime_counts.values())
        or started_at is not None or healthcheck_codes
    ):
        raise ValueError("runtime fields present while unavailable")

    probes = require_keys(diagnostics["probes"], {"host", "container"})
    sanitized_probes: dict[str, dict[str, dict[str, Any]]] = {}
    allowed_categories = {
        "EXPECTED_HTTP", "UNEXPECTED_HTTP", "DNS_ERROR", "CONNECTION_REFUSED", "TIMEOUT",
        "TLS_ERROR", "CONNECTION_CLOSED", "CURL_ERROR", "CONNECTION_ERROR", "UNKNOWN",
    }
    for source in ("host", "container"):
        source_probes = require_keys(probes[source], {"liveness", "readiness", "mcpUnauthenticated"})
        sanitized_probes[source] = {}
        for name, expected in (("liveness", "200"), ("readiness", "200"), ("mcpUnauthenticated", "401")):
            probe = require_keys(
                source_probes[name], {"expectedHttpStatus", "httpStatus", "exitCode", "category", "durationMs"}
            )
            if probe["expectedHttpStatus"] != expected:
                raise ValueError("invalid expected probe status")
            status = probe["httpStatus"]
            if not isinstance(status, str) or re.fullmatch(r"(?:000|[1-5][0-9]{2})", status) is None:
                raise ValueError("invalid probe HTTP status")
            exit_code = probe["exitCode"]
            if exit_code is not None:
                exit_code = require_count(exit_code)
                if exit_code > 255:
                    raise ValueError("invalid probe exit code")
            category = probe["category"]
            if category not in allowed_categories:
                raise ValueError("invalid probe category")
            duration = probe["durationMs"]
            if duration is not None:
                duration = require_count(duration)
                if duration > 10000:
                    raise ValueError("probe exceeded its time bound")
            if status == expected and category != "EXPECTED_HTTP":
                raise ValueError("expected probe response has inconsistent category")
            if status != expected and category == "EXPECTED_HTTP":
                raise ValueError("unexpected probe response has expected category")
            sanitized_probes[source][name] = {
                "expectedHttpStatus": expected,
                "httpStatus": status,
                "exitCode": exit_code,
                "category": category,
                "durationMs": duration,
            }

    exceptions = require_keys(diagnostics["recentExceptions"], {"available", "counts"})
    exceptions_available = require_bool(exceptions["available"])
    exception_counts = require_keys(
        exceptions["counts"],
        {"nullPointerException", "outOfMemoryError", "poolTimeout", "socketTimeout", "rejectedExecution"},
    )
    sanitized_counts = {key: require_count(value) for key, value in exception_counts.items()}
    if any(value > 2000 for value in sanitized_counts.values()):
        raise ValueError("exception count exceeds inspected log bound")
    if not exceptions_available and any(sanitized_counts.values()):
        raise ValueError("exception counts present while logs unavailable")
    return {
        "backendRuntime": {
            "available": runtime_available,
            "oomKilled": runtime_oom_killed,
            **runtime_counts,
            "startedAt": started_at,
            "healthcheckExitCodes": healthcheck_codes,
        },
        "probes": sanitized_probes,
        "recentExceptions": {"available": exceptions_available, "counts": sanitized_counts},
    }


def sanitize(raw: Any) -> dict[str, Any]:
    root_keys = {
            "schemaVersion",
            "ok",
            "expectedSha",
            "expectedShaMatchesImageTag",
            "backend",
            "readiness",
            "database",
            "portfolioFreshness",
        }
    if not isinstance(raw, dict) or frozenset(raw) not in {frozenset(root_keys), frozenset(root_keys | {"httpDiagnostics"})}:
        raise ValueError("invalid root object shape")
    root = raw
    if root["schemaVersion"] != 1 or not isinstance(root["ok"], bool):
        raise ValueError("invalid root status")

    expected_sha = root["expectedSha"]
    if expected_sha is not None and (
        not isinstance(expected_sha, str) or re.fullmatch(r"[0-9a-f]{40}", expected_sha) is None
    ):
        raise ValueError("invalid expected SHA")
    expected_match = root["expectedShaMatchesImageTag"]
    if expected_match is not None and not isinstance(expected_match, bool):
        raise ValueError("invalid SHA comparison")

    backend = require_keys(root["backend"], {"imageTag", "imageId", "state", "health"})
    image_tag = backend["imageTag"]
    image_id = backend["imageId"]
    if not isinstance(image_tag, str) or re.fullmatch(r"trade-backend:[0-9a-f]{40}|UNKNOWN", image_tag) is None:
        raise ValueError("invalid image tag")
    if not isinstance(image_id, str) or re.fullmatch(r"sha256:[0-9a-f]{64}|UNKNOWN", image_id) is None:
        raise ValueError("invalid image ID")
    if backend["state"] not in {"created", "running", "paused", "restarting", "exited", "dead", "unknown"}:
        raise ValueError("invalid container state")
    if backend["health"] not in {"healthy", "unhealthy", "starting", "none", "unknown"}:
        raise ValueError("invalid container health")

    readiness = require_keys(root["readiness"], {"httpStatus", "status"})
    if not isinstance(readiness["httpStatus"], str) or re.fullmatch(r"[0-9]{3}", readiness["httpStatus"]) is None:
        raise ValueError("invalid readiness HTTP status")
    if not isinstance(readiness["status"], str) or re.fullmatch(r"[A-Z_]+", readiness["status"]) is None:
        raise ValueError("invalid readiness status")

    database_keys = {
        "status",
        "flywayVersion",
        "flywaySuccess",
        "analysisInputSnapshotCount",
        "securitySnapshotCount",
        "priceSnapshotCount",
        "thesisCount",
        "decisionCount",
        "latestSecurityProjectionCount",
        "latestSecurityProjectionSha256",
    }
    database = root["database"]
    if not isinstance(database, dict) or frozenset(database) not in {
        frozenset(database_keys),
        frozenset(database_keys | {"aiVerificationPolicy"}),
        frozenset(database_keys | {"thesisRevisionDiagnostics"}),
        frozenset(database_keys | {"aiVerificationPolicy", "thesisRevisionDiagnostics"}),
    }:
        raise ValueError("invalid object shape")
    if database["status"] not in {"available", "unavailable"}:
        raise ValueError("invalid database status")
    version = database["flywayVersion"]
    if version is not None and (
        not isinstance(version, str) or re.fullmatch(r"[0-9]+([.][0-9A-Za-z_-]+)*", version) is None
    ):
        raise ValueError("invalid Flyway version")
    if not isinstance(database["flywaySuccess"], bool):
        raise ValueError("invalid Flyway status")
    for key in (
        "analysisInputSnapshotCount",
        "securitySnapshotCount",
        "priceSnapshotCount",
        "thesisCount",
        "decisionCount",
        "latestSecurityProjectionCount",
    ):
        require_count(database[key])
    fingerprint = database["latestSecurityProjectionSha256"]
    if not isinstance(fingerprint, str) or fingerprint not in {""} and re.fullmatch(r"[0-9a-f]{64}", fingerprint) is None:
        raise ValueError("invalid projection fingerprint")

    ai_policy_defaults = {
        "verificationEventsAvailable": False,
        "verificationCount": 0,
        "approvedCount": 0,
        "blockedCount": 0,
        "rejectedCount": 0,
        "policyRevisionsAvailable": False,
        "aiPolicyRevisionCount": 0,
        "deploymentEnabled": None,
        "ownerScopeConfigured": False,
    }
    ai_policy = database.get("aiVerificationPolicy", ai_policy_defaults)
    ai_policy_legacy_keys = set(ai_policy_defaults)
    ai_policy_extended_keys = ai_policy_legacy_keys | {"effectiveOwnerPolicy"}
    if not isinstance(ai_policy, dict) or frozenset(ai_policy) not in {
        frozenset(ai_policy_legacy_keys), frozenset(ai_policy_extended_keys)
    }:
        raise ValueError("invalid AI policy shape")
    ai_policy = require_keys(ai_policy, set(ai_policy))
    event_schema_available = require_bool(ai_policy["verificationEventsAvailable"])
    policy_schema_available = require_bool(ai_policy["policyRevisionsAvailable"])
    event_counts = {
        key: require_count(ai_policy[key])
        for key in ("verificationCount", "approvedCount", "blockedCount", "rejectedCount")
    }
    policy_revision_count = require_count(ai_policy["aiPolicyRevisionCount"])
    if event_schema_available:
        if event_counts["verificationCount"] != sum(
            event_counts[key] for key in ("approvedCount", "blockedCount", "rejectedCount")
        ):
            raise ValueError("inconsistent verification outcome counts")
    elif any(event_counts.values()):
        raise ValueError("verification counts present while schema is unavailable")
    if not policy_schema_available and policy_revision_count:
        raise ValueError("policy revision count present while schema is unavailable")
    deployment_enabled = ai_policy["deploymentEnabled"]
    if deployment_enabled is not None and not isinstance(deployment_enabled, bool):
        raise ValueError("invalid deployment enabled state")
    owner_scope_configured = require_bool(ai_policy["ownerScopeConfigured"])
    effective_defaults = {
        "storedOverridePresent": None,
        "storedOverrideEnabled": None,
        "enabled": None,
        "source": "UNKNOWN",
        "sheetEnabled": None,
        "ownerScopeMatches": None,
        "configSources": {
            "deploymentPolicyEnabled": "UNKNOWN",
            "sheetEnabled": "UNKNOWN",
            "ownerScope": "UNKNOWN",
            "thresholds": "UNKNOWN",
        },
        "maxEvidenceAgeSeconds": None,
        "minimumSourceCount": None,
        "maxTriggerBelowPricePct": None,
    }
    effective = ai_policy.get("effectiveOwnerPolicy", effective_defaults)
    effective = require_keys(
        effective,
        {
            "storedOverridePresent", "storedOverrideEnabled", "enabled", "source", "sheetEnabled",
            "ownerScopeMatches", "configSources", "maxEvidenceAgeSeconds", "minimumSourceCount",
            "maxTriggerBelowPricePct",
        },
    )
    if effective["source"] not in {"USER", "DEPLOYMENT", "DEFAULT_DISABLED", "UNKNOWN"}:
        raise ValueError("invalid effective policy source")
    config_sources = require_keys(
        effective["configSources"],
        {"deploymentPolicyEnabled", "sheetEnabled", "ownerScope", "thresholds"},
    )
    if config_sources["deploymentPolicyEnabled"] not in {"CONTAINER_ENV", "UNKNOWN"}:
        raise ValueError("invalid deployment config source")
    if config_sources["sheetEnabled"] not in {"CONTAINER_ENV", "UNKNOWN"}:
        raise ValueError("invalid sheet config source")
    if config_sources["ownerScope"] not in {"CONTAINER_ENV", "UNKNOWN"}:
        raise ValueError("invalid owner config source")
    if config_sources["thresholds"] not in {"CONTAINER_ENV", "DATABASE_USER_POLICY", "UNKNOWN"}:
        raise ValueError("invalid threshold config source")
    override_present = require_optional_bool(effective["storedOverridePresent"])
    override_enabled = require_optional_bool(effective["storedOverrideEnabled"])
    effective_enabled = require_optional_bool(effective["enabled"])
    sheet_enabled = require_optional_bool(effective["sheetEnabled"])
    owner_scope_matches = require_optional_bool(effective["ownerScopeMatches"])
    max_evidence_age = effective["maxEvidenceAgeSeconds"]
    if max_evidence_age is not None and (
        isinstance(max_evidence_age, bool) or not isinstance(max_evidence_age, int) or not 60 <= max_evidence_age <= 604800
    ):
        raise ValueError("invalid effective evidence age")
    minimum_source_count = effective["minimumSourceCount"]
    if minimum_source_count is not None and (
        isinstance(minimum_source_count, bool) or not isinstance(minimum_source_count, int) or not 1 <= minimum_source_count <= 10
    ):
        raise ValueError("invalid effective source count")
    max_trigger_pct = require_optional_number(effective["maxTriggerBelowPricePct"], 0.0, 0.5)
    if override_present is True and override_enabled is None:
        raise ValueError("stored policy override lacks enabled value")
    if override_present is False and override_enabled is not None:
        raise ValueError("absent stored policy override has enabled value")
    if effective["source"] == "USER" and (override_present is not True or effective_enabled != override_enabled):
        raise ValueError("inconsistent user policy resolution")
    if effective["source"] == "DEPLOYMENT" and (
        override_present is not False or effective_enabled is not True or sheet_enabled is not True or owner_scope_matches is not True
    ):
        raise ValueError("inconsistent deployment policy resolution")
    if effective["source"] == "DEFAULT_DISABLED" and (override_present is not False or effective_enabled is not False):
        raise ValueError("inconsistent disabled policy resolution")
    sanitized_effective = {
        "storedOverridePresent": override_present,
        "storedOverrideEnabled": override_enabled,
        "enabled": effective_enabled,
        "source": effective["source"],
        "sheetEnabled": sheet_enabled,
        "ownerScopeMatches": owner_scope_matches,
        "configSources": config_sources,
        "maxEvidenceAgeSeconds": max_evidence_age,
        "minimumSourceCount": minimum_source_count,
        "maxTriggerBelowPricePct": max_trigger_pct,
    }
    sanitized_ai_policy = {
        "verificationEventsAvailable": event_schema_available,
        **event_counts,
        "policyRevisionsAvailable": policy_schema_available,
        "aiPolicyRevisionCount": policy_revision_count,
        "deploymentEnabled": deployment_enabled,
        "ownerScopeConfigured": owner_scope_configured,
        "effectiveOwnerPolicy": sanitized_effective,
    }

    thesis_defaults = {
        "available": False,
        "thesesWithRevisionCount": 0,
        "thesesWithoutRevisionCount": 0,
        "revisionCount": 0,
        "legacyBaselineRevisionCount": 0,
        "currentThesesWithProposalRunIdCount": 0,
        "currentThesesWithoutProposalRunIdCount": 0,
        "latestSnapshotMatchCount": 0,
        "latestSnapshotMismatchCount": 0,
        "snapshotMatchStatus": "UNKNOWN",
    }
    thesis_diagnostics = require_keys(
        database.get("thesisRevisionDiagnostics", thesis_defaults), set(thesis_defaults)
    )
    thesis_available = require_bool(thesis_diagnostics["available"])
    thesis_counts = {
        key: require_count(thesis_diagnostics[key])
        for key in (
            "thesesWithRevisionCount", "thesesWithoutRevisionCount", "revisionCount",
            "legacyBaselineRevisionCount", "currentThesesWithProposalRunIdCount",
            "currentThesesWithoutProposalRunIdCount", "latestSnapshotMatchCount", "latestSnapshotMismatchCount",
        )
    }
    snapshot_status = thesis_diagnostics["snapshotMatchStatus"]
    if snapshot_status not in {"UNKNOWN", "NO_THESIS", "NO_REVISION", "MATCH", "MISMATCH", "MIXED"}:
        raise ValueError("invalid snapshot match status")
    if thesis_available:
        if thesis_counts["thesesWithRevisionCount"] + thesis_counts["thesesWithoutRevisionCount"] != database["thesisCount"]:
            raise ValueError("inconsistent thesis revision counts")
        if thesis_counts["currentThesesWithProposalRunIdCount"] + thesis_counts["currentThesesWithoutProposalRunIdCount"] != database["thesisCount"]:
            raise ValueError("inconsistent proposal run ID counts")
        if thesis_counts["legacyBaselineRevisionCount"] > thesis_counts["revisionCount"]:
            raise ValueError("inconsistent legacy baseline count")
        if thesis_counts["latestSnapshotMatchCount"] + thesis_counts["latestSnapshotMismatchCount"] > thesis_counts["thesesWithRevisionCount"]:
            raise ValueError("inconsistent thesis snapshot counts")
    elif any(thesis_counts.values()) or snapshot_status != "UNKNOWN":
        raise ValueError("thesis diagnostics present while unavailable")
    sanitized_thesis_diagnostics = {
        "available": thesis_available,
        **thesis_counts,
        "snapshotMatchStatus": snapshot_status,
    }

    result = {
        "schemaVersion": 1,
        "ok": root["ok"],
        "expectedSha": expected_sha,
        "expectedShaMatchesImageTag": expected_match,
        "backend": {
            "imageTag": image_tag,
            "imageId": image_id,
            "state": backend["state"],
            "health": backend["health"],
        },
        "readiness": {
            "httpStatus": readiness["httpStatus"],
            "status": readiness["status"],
        },
        "database": {
            **{key: database[key] for key in sorted(database) if key not in {"aiVerificationPolicy", "thesisRevisionDiagnostics"}},
            "aiVerificationPolicy": sanitized_ai_policy,
            "thesisRevisionDiagnostics": sanitized_thesis_diagnostics,
        },
        "portfolioFreshness": sanitize_portfolio_freshness(root["portfolioFreshness"]),
    }
    if "httpDiagnostics" in root:
        result["httpDiagnostics"] = sanitize_http_diagnostics(root["httpDiagnostics"])
    return result


def main() -> int:
    if len(sys.argv) != 3:
        return 2
    destination = Path(sys.argv[2])
    try:
        with Path(sys.argv[1]).open(encoding="utf-8") as source:
            result = sanitize(json.load(source))
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError, TypeError):
        result = FAILURE
        status = 1
    else:
        status = 0
    try:
        destination.write_text(json.dumps(result, separators=(",", ":"), sort_keys=True) + "\n", encoding="utf-8")
    except OSError:
        return 2
    return status


if __name__ == "__main__":
    raise SystemExit(main())
