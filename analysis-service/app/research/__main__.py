"""CLI: ``python -m app.research --data <dir> --out <file.json> [--markdown <file>]``."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from app.research.data import ResearchError, load_dataset
from app.research.report import build_report, render_canonical, render_markdown
from app.research.validation import run_validation


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m app.research",
        description="Offline walk-forward backtest. Hypothetical results only; no orders are placed.",
    )
    parser.add_argument("--data", required=True, type=Path, help="dataset directory with manifest.json")
    parser.add_argument("--out", required=True, type=Path, help="canonical JSON report path")
    parser.add_argument("--markdown", type=Path, help="optional markdown summary path")
    parser.add_argument("--commission-bps", type=float, default=10.0, help="per-trade commission (bps)")
    parser.add_argument("--slippage-bps", type=float, default=5.0, help="per-trade slippage (bps)")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        dataset = load_dataset(args.data)
        result = run_validation(
            dataset, commission_bps=args.commission_bps, slippage_bps=args.slippage_bps
        )
        report = build_report(
            dataset,
            result,
            commission_bps=args.commission_bps,
            slippage_bps=args.slippage_bps,
        )
    except ResearchError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(render_canonical(report), encoding="utf-8")
    if args.markdown is not None:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(render_markdown(report), encoding="utf-8")
    for family, data in report["strategies"].items():
        print(f"{family}: {data['verdict']['name']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
