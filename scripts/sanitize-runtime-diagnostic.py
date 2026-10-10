#!/usr/bin/env python3
"""Allowlist and validate the remote diagnostic before it reaches logs or an artifact."""

from __future__ import annotations

import json
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


def sanitize(raw: Any) -> dict[str, Any]:
    root = require_keys(
        raw,
        {
            "schemaVersion",
            "ok",
            "expectedSha",
            "expectedShaMatchesImageTag",
            "backend",
            "readiness",
            "database",
            "portfolioFreshness",
        },
    )
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

    database = require_keys(
        root["database"],
        {
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
        },
    )
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

    return {
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
        "database": {key: database[key] for key in sorted(database)},
        "portfolioFreshness": sanitize_portfolio_freshness(root["portfolioFreshness"]),
    }


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
