#!/usr/bin/env python3
"""Read-only JSON health status for an EV-1 recorder manifest."""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
from datetime import datetime, timezone
from pathlib import Path


_RECORDER_PATH = Path(__file__).with_name("ev1-recorder.py")
_RECORDER_SPEC = importlib.util.spec_from_file_location("ev1_recorder_status", _RECORDER_PATH)
if _RECORDER_SPEC is None or _RECORDER_SPEC.loader is None:
    raise ImportError("cannot load ev1-recorder.py")
_RECORDER = importlib.util.module_from_spec(_RECORDER_SPEC)
_RECORDER_SPEC.loader.exec_module(_RECORDER)


def _error_result(path: Path, status: str, message: str) -> dict:
    return {
        "status": status,
        "health": "red",
        "manifest": str(path),
        "error": message,
        "last_poll_age_seconds": None,
        "progress": {},
        "metrics": None,
    }


def manifest_status(manifest_path: Path, max_age_seconds: float,
                    now: datetime | None = None) -> tuple[dict, int]:
    """Return JSON-safe status data and a process exit code without network I/O."""
    path = Path(manifest_path)
    if not math.isfinite(max_age_seconds) or max_age_seconds < 0:
        return _error_result(path, "malformed", "max_age_seconds must be finite and non-negative"), 1
    if not path.is_file():
        return _error_result(path, "missing", "manifest does not exist"), 1
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        return _error_result(path, "malformed", f"cannot read manifest: {type(error).__name__}"), 1
    if not isinstance(manifest, dict) or not isinstance(manifest.get("progress"), dict):
        return _error_result(path, "malformed", "manifest must contain an object progress field"), 1
    progress = manifest["progress"]
    errors = manifest.get("errors", [])
    if not isinstance(errors, list):
        return _error_result(path, "malformed", "manifest errors field must be a list"), 1
    raw_error_count = progress.get("error_count", len(errors))
    if (isinstance(raw_error_count, bool) or not isinstance(raw_error_count, (int, float))
            or not math.isfinite(raw_error_count) or raw_error_count < 0
            or int(raw_error_count) != raw_error_count):
        return _error_result(path, "malformed", "progress error_count must be a non-negative integer"), 1
    error_count = max(int(raw_error_count), len(errors))
    last_poll = manifest.get("last_poll") or progress.get("last_poll")
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        raise ValueError("now must include a timezone")
    age: float | None = None
    if last_poll is not None:
        try:
            age = (current.astimezone(timezone.utc) - _RECORDER.parse_timestamp(last_poll)).total_seconds()
        except (TypeError, ValueError):
            return _error_result(path, "malformed", "last_poll must be a timezone-aware timestamp"), 1
    stale = age is None or age > max_age_seconds
    status = "error" if error_count > 0 else "stale" if stale else "healthy"
    result = {
        "status": status,
        "health": "green" if status == "healthy" else "red",
        "manifest": str(path),
        "error_count": error_count,
        "last_poll_age_seconds": age,
        "stale": stale,
        "progress": {key: value for key, value in progress.items() if key != "metrics"},
        "metrics": progress.get("metrics"),
    }
    if error_count > 0:
        result["error"] = "manifest contains recorder errors"
    elif stale:
        result["error"] = "manifest last poll is missing or stale"
    return result, 1 if status != "healthy" else 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--max-age-seconds", type=float, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.max_age_seconds < 0:
        print(json.dumps({"status": "malformed", "health": "red",
                          "error": "--max-age-seconds must be non-negative"}, sort_keys=True))
        return 1
    result, exit_code = manifest_status(args.manifest, args.max_age_seconds)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
