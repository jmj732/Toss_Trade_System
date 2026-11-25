#!/usr/bin/env python3
"""Merge shard directories written by collect-toss-candles.py into one dataset directory.

Collecting shards in parallel (one --out directory per process) avoids concurrent writes to a
single manifest. This script is offline: it copies CSVs and raw pages, verifies every series
against the sha256 recorded in its shard manifest, refuses a symbol present in two sources,
merges errors/warnings/retries, and re-runs the collector's own adjustment classification.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import shutil
import sys
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "collect_toss_candles", Path(__file__).with_name("collect-toss-candles.py"))
collector = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(collector)


class MergeError(RuntimeError):
    pass


def _verified_copy(source: Path, target: Path, relative: str, expected: str | None) -> None:
    data = (source / relative).read_bytes()
    if not expected or hashlib.sha256(data).hexdigest() != expected:
        raise MergeError(f"{source / relative}: sha256 does not match its manifest")
    destination = target / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)


def merge(target: Path, sources: list[Path]) -> dict:
    target_has_manifest = (target / collector.MANIFEST_NAME).exists()
    merged = collector.read_manifest(target) if target_has_manifest else None
    origin = {symbol: target for symbol in (merged or {}).get("symbols", {})}
    for source in sources:
        shard = collector.read_manifest(source)
        if merged is None:
            merged = {key: value for key, value in shard.items()
                      if key not in ("symbols", "unadjusted", "errors", "warnings")}
            merged.update({"symbols": {}, "unadjusted": {}, "errors": {}, "warnings": [],
                           "resumed_at": list(shard.get("resumed_at", [])),
                           "retry_total": 0})
        else:
            merged["resumed_at"] = list(merged["resumed_at"]) + list(shard.get("resumed_at", []))
        merged.setdefault("merged_from", []).append(str(source))
        for symbol, entry in shard["symbols"].items():
            if symbol in origin:
                raise MergeError(f"{symbol} present in both {origin[symbol]} and {source}")
            raw_entry = shard["unadjusted"].get(symbol)
            if raw_entry is None:
                raise MergeError(f"{source}: {symbol} has no unadjusted series")
            _verified_copy(source, target, entry["file"], entry.get("sha256"))
            _verified_copy(source, target, raw_entry["file"], raw_entry.get("sha256"))
            for page in sorted((source / "raw").glob(f"{symbol}-*.json")):
                (target / "raw").mkdir(parents=True, exist_ok=True)
                shutil.copy2(page, target / "raw" / page.name)
            merged["symbols"][symbol] = entry
            merged["unadjusted"][symbol] = raw_entry
            origin[symbol] = source
        for symbol, error in shard["errors"].items():
            if symbol not in merged["symbols"]:
                merged["errors"][symbol] = error
        merged["warnings"].extend(shard["warnings"])
        merged["retry_total"] += shard.get("retry_total", 0)
    if merged is None:
        raise MergeError("no sources given")
    for symbol in list(merged["errors"]):
        if symbol in merged["symbols"]:
            del merged["errors"][symbol]
    classification = collector.apply_classification(merged)
    collector.write_manifest(target, merged)
    return classification


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--into", required=True, type=Path, help="target dataset directory")
    parser.add_argument("sources", nargs="+", type=Path, help="shard directories")
    args = parser.parse_args(argv)
    try:
        classification = merge(args.into, args.sources)
    except (MergeError, OSError, ValueError, collector.CollectionError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    manifest = collector.read_manifest(args.into)
    print(f"symbols: {len(manifest['symbols'])}; errors: {sorted(manifest['errors'])}")
    print(f"adjustment: {classification['result']} ({collector.RULE_VERSION})")
    return 1 if manifest["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
