from __future__ import annotations

import ast
import json
import subprocess
import sys
from pathlib import Path

from research_fixtures import universe_series, write_dataset

from app.research.__main__ import main
from app.research.report import REPORT_LABEL

ANALYSIS_ROOT = Path(__file__).resolve().parent.parent
RESEARCH_DIR = ANALYSIS_ROOT / "app" / "research"
FORBIDDEN_MODULES = ("fastapi", "httpx", "urllib", "socket", "requests", "app.main", "app.monitoring")
COUNT = 2000


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
