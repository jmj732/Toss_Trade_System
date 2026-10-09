#!/usr/bin/env python3
"""Allowlist and validate the remote diagnostic before it reaches logs or an artifact."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


FAILURE = {"schemaVersion": 1, "ok": False, "error": "UNTRUSTED_REMOTE_OUTPUT"}


def require_keys(value: Any, keys: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError("invalid object shape")
    return value


def require_count(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("invalid count")
    return value


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
