from __future__ import annotations

import ast
import json
import subprocess
import sys
from pathlib import Path

from research_fixtures import universe_series, write_dataset

from app.research.__main__ import main
from app.research.data import load_dataset
from app.research.report import REPORT_LABEL, build_report, render_markdown
from app.research.validation import run_validation

ANALYSIS_ROOT = Path(__file__).resolve().parent.parent
RESEARCH_DIR = ANALYSIS_ROOT / "app" / "research"
FORBIDDEN_MODULES = ("fastapi", "httpx", "urllib", "socket", "requests", "app.main", "app.monitoring")
COUNT = 2000
FULL_COUNT = 2250  # research_end 1800 -> three folds, OOS 756 bars, holdout 450 bars


def _dataset(tmp_path: Path, name: str = "data", basis: str = "SPLIT_AND_DIVIDEND") -> Path:
    return write_dataset(tmp_path / name, universe_series(COUNT), adjustment_basis=basis)


def test_cli_end_to_end_writes_canonical_report_and_markdown(tmp_path: Path) -> None:
    data = _dataset(tmp_path)
    out = tmp_path / "report" / "result.json"
    markdown = tmp_path / "report" / "summary.md"
    completed = subprocess.run(
        [
            sys.executable,
            "-m",
            "app.research",
            "--data",
            str(data),
            "--out",
            str(out),
            "--markdown",
            str(markdown),
            "--commission-bps",
            "10",
            "--slippage-bps",
            "5",
        ],
        cwd=ANALYSIS_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    report = json.loads(out.read_text(encoding="utf-8"))
    assert report["label"] == REPORT_LABEL
    assert report["trials"]["count"] == 54
    assert report["bias_controls"]["survivorship"] == "LIMITED_NOT_CONTROLLED — fixed ETF universe chosen today"
    assert report["bias_controls"]["risk_free_rate"] == 0
    assert report["config"]["verdict_thresholds"]["required_adjustment_basis"] == "SPLIT_AND_DIVIDEND"
    assert set(report["strategies"]) == {"momentum", "trend", "mean_reversion"}
    for family in report["strategies"].values():
        assert family["verdict"]["name"] in {"VALIDATED_EDGE", "NOT_VALIDATED", "INSUFFICIENT_DATA"}
    assert "generated_at" not in out.read_text(encoding="utf-8")
    assert "Quant research summary" in markdown.read_text(encoding="utf-8")


def test_repeated_runs_on_the_same_dataset_are_byte_identical(tmp_path: Path) -> None:
    data = _dataset(tmp_path)
    first = tmp_path / "first.json"
    second = tmp_path / "second.json"
    assert main(["--data", str(data), "--out", str(first)]) == 0
    assert main(["--data", str(data), "--out", str(second)]) == 0
    assert first.read_bytes() == second.read_bytes()


def test_non_split_and_dividend_basis_produces_bias_warning(tmp_path: Path) -> None:
    data = _dataset(tmp_path, name="split_only", basis="SPLIT_ONLY")
    out = tmp_path / "split_only.json"
    assert main(["--data", str(data), "--out", str(out)]) == 0
    report = json.loads(out.read_text(encoding="utf-8"))
    assert report["bias_controls"]["adjustment_basis"] == "SPLIT_ONLY"
    assert any("dividend treatment may be biased" in w for w in report["bias_controls"]["warnings"])


def test_invalid_dataset_exits_with_error_code(tmp_path: Path, capsys) -> None:
    data = _dataset(tmp_path)
    (data / "SPY.csv").write_text("date,open,high,low,close,volume\n", encoding="utf-8")
    code = main(["--data", str(data), "--out", str(tmp_path / "x.json")])
    assert code == 2
    assert "error:" in capsys.readouterr().err


def _markdown_for(
    tmp_path: Path, name: str, basis: str = "SPLIT_AND_DIVIDEND", count: int = FULL_COUNT
) -> tuple[dict, str]:
    """FULL_COUNT reaches the verdict path; COUNT leaves OOS too short for any verdict."""
    root = write_dataset(tmp_path / name, universe_series(count), adjustment_basis=basis)
    dataset = load_dataset(root)
    result = run_validation(dataset, commission_bps=10.0, slippage_bps=5.0)
    report = build_report(dataset, result, commission_bps=10.0, slippage_bps=5.0)
    return report, render_markdown(report)


def _pct(value: float) -> str:
    return f"{value * 100:.2f}%"


def _cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def test_markdown_main_table_has_risk_cost_and_holdout_columns(tmp_path: Path) -> None:
    report, markdown = _markdown_for(tmp_path, "columns")
    header = next(line for line in markdown.splitlines() if line.startswith("| Strategy |"))
    assert _cells(header) == [
        "Strategy",
        "Verdict",
        "OOS Sharpe",
        "Benchmark OOS Sharpe",
        "DSR",
        "Active PSR",
        "Holdout Sharpe",
        "2x-cost OOS Sharpe",
        "OOS CAGR",
        "SPY OOS CAGR",
        "OOS MaxDD",
        "SPY OOS MaxDD",
        "OOS turnover/yr",
        "est. cost drag/yr",
        "Holdout CAGR",
        "SPY holdout CAGR",
        "Failed criteria",
    ]
    cost_rate = report["config"]["costs"]["cost_rate_per_unit_traded"]
    for family, data in report["strategies"].items():
        row = next(line for line in markdown.splitlines() if line.startswith(f"| {family} |"))
        cells = _cells(row)
        oos = data["out_of_sample"]
        turnover = oos["strategy"]["turnover_annualized"]
        assert cells[8] == _pct(oos["strategy"]["cagr"])
        assert cells[9] == _pct(oos["benchmark"]["cagr"])
        assert cells[10] == _pct(oos["strategy"]["max_drawdown"])
        assert cells[11] == _pct(oos["benchmark"]["max_drawdown"])
        assert cells[12] == _pct(turnover)
        assert cells[13] == _pct(turnover * cost_rate)
        assert cells[14] == _pct(data["holdout"]["strategy"]["cagr"])
        assert cells[15] == _pct(data["holdout"]["benchmark"]["cagr"])


def test_markdown_has_walk_forward_fold_table_per_family(tmp_path: Path) -> None:
    report, markdown = _markdown_for(tmp_path, "folds")
    assert "## Walk-forward folds" in markdown
    for family, data in report["strategies"].items():
        assert len(data["folds"]) == 3
        section =markdown.split(f"### {family}\n\n", 1)[1].split("\n#", 1)[0]
        assert section.splitlines()[0] == "| Fold | Test window | Selected config | Test total return | SPY test total return |"
        rows = [line for line in section.splitlines() if line.startswith("| ") and line[2].isdigit()]
        assert len(rows) == len(data["folds"])
        for fold, row in zip(data["folds"], rows):
            assert _cells(row) == [
                str(fold["fold"]),
                f"{fold['test_start']} to {fold['test_end']}",
                fold["selected_config"],
                _pct(fold["test_total_return"]),
                _pct(fold["benchmark_test_total_return"]),
            ]


def test_markdown_states_adjustment_basis_and_withholds_held_verdicts(tmp_path: Path) -> None:
    _, held = _markdown_for(tmp_path, "held", basis="SPLIT_ONLY")
    assert "- adjustment_basis: `SPLIT_ONLY` (verdicts require `SPLIT_AND_DIVIDEND`)" in held
    assert "Verdicts withheld (HELD_UNVERIFIED_ADJUSTMENT): dividend/split adjustment is unconfirmed" in held
    assert "| momentum | HELD_UNVERIFIED_ADJUSTMENT |" in held

    _, validated = _markdown_for(tmp_path, "clean")
    assert "- adjustment_basis: `SPLIT_AND_DIVIDEND` (verdicts require `SPLIT_AND_DIVIDEND`)" in validated
    assert "Verdicts withheld" not in validated


def test_markdown_is_byte_identical_across_runs(tmp_path: Path) -> None:
    data = _dataset(tmp_path)
    first = tmp_path / "first.md"
    second = tmp_path / "second.md"
    assert main(["--data", str(data), "--out", str(tmp_path / "a.json"), "--markdown", str(first)]) == 0
    assert main(["--data", str(data), "--out", str(tmp_path / "b.json"), "--markdown", str(second)]) == 0
    assert first.read_bytes() == second.read_bytes()


def _imported_modules(path: Path) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    modules: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            modules.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            modules.add(node.module)
    return modules


def test_research_package_source_has_no_forbidden_imports() -> None:
    sources = sorted(RESEARCH_DIR.glob("*.py"))
    assert sources
    for source in sources:
        for module in _imported_modules(source):
            for forbidden in FORBIDDEN_MODULES:
                assert not (module == forbidden or module.startswith(forbidden + ".")), (
                    f"{source.name} imports {module}"
                )


def test_importing_research_package_loads_no_http_or_app_modules() -> None:
    script = (
        "import sys\n"
        f"sys.path.insert(0, {str(ANALYSIS_ROOT)!r})\n"
        "import app.research.__main__\n"
        "import app.research.report, app.research.validation\n"
        "bad = [m for m in ('fastapi', 'httpx', 'urllib.request', 'socket', 'requests', 'app.main', 'app.monitoring') "
        "if m in sys.modules]\n"
        "print(','.join(bad))\n"
    )
    completed = subprocess.run(
        [sys.executable, "-I", "-c", script],
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    assert completed.stdout.strip() == ""


def test_subprocess_cli_output_matches_in_process_run_byte_for_byte(tmp_path: Path) -> None:
    # Separate interpreters use different hash seeds, so this catches set-order leakage.
    data = _dataset(tmp_path)
    via_process = tmp_path / "subprocess.json"
    in_process = tmp_path / "in_process.json"
    completed = subprocess.run(
        [sys.executable, "-m", "app.research", "--data", str(data), "--out", str(via_process)],
        cwd=ANALYSIS_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    assert main(["--data", str(data), "--out", str(in_process)]) == 0
    assert via_process.read_bytes() == in_process.read_bytes()
