import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "merge_toss_collections", Path(__file__).with_name("merge-toss-collections.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def write_shard(root: Path, symbols: dict[str, str], errors: dict | None = None) -> Path:
    manifest = {"provider": "TOSS", "interval": "1d", "adjusted": True, "adjustment_basis": "UNVERIFIED",
                "collected_at": "2026-11-23T00:00:00Z", "resumed_at": [], "retry_total": 1,
                "symbols": {}, "unadjusted": {}, "errors": errors or {}, "warnings": []}
    (root / "unadjusted").mkdir(parents=True)
    (root / "raw").mkdir()
    for symbol, body in symbols.items():
        for relative, key in ((f"{symbol}.csv", "symbols"), (f"unadjusted/{symbol}.csv", "unadjusted")):
            (root / relative).write_text(body)
            manifest[key][symbol] = {"file": relative, "rows": 1, "stop_reason": "NO_CURSOR",
                                     "sha256": hashlib.sha256(body.encode()).hexdigest()}
        (root / "raw" / f"{symbol}-adj-000.json").write_text("{}")
    (root / "manifest.json").write_text(json.dumps(manifest))
    return root


class MergeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def test_merges_shards_and_reclassifies(self):
        a = write_shard(self.tmp / "a", {"AAA": "date\n1\n"}, errors={"BBB": "earlier failure"})
        b = write_shard(self.tmp / "b", {"BBB": "date\n2\n"})
        module.merge(self.tmp / "out", [a, b])
        merged = json.loads((self.tmp / "out" / "manifest.json").read_text())
        self.assertEqual(["AAA", "BBB"], sorted(merged["symbols"]))
        self.assertEqual({}, merged["errors"])  # BBB recovered in shard b
        self.assertEqual(2, merged["retry_total"])
        self.assertIn("adjustment_classification", merged)
        self.assertEqual("UNVERIFIED", merged["adjustment_basis"])
        self.assertTrue((self.tmp / "out" / "unadjusted" / "BBB.csv").exists())
        self.assertTrue((self.tmp / "out" / "raw" / "AAA-adj-000.json").exists())

    def test_merges_into_existing_target(self):
        target = write_shard(self.tmp / "out", {"AAA": "x\n"})
        b = write_shard(self.tmp / "b", {"BBB": "y\n"})
        module.merge(target, [b])
        merged = json.loads((target / "manifest.json").read_text())
        self.assertEqual(["AAA", "BBB"], sorted(merged["symbols"]))

    def test_refuses_duplicate_symbol(self):
        a = write_shard(self.tmp / "a", {"AAA": "x\n"})
        b = write_shard(self.tmp / "b", {"AAA": "y\n"})
        with self.assertRaises(module.MergeError):
            module.merge(self.tmp / "out", [a, b])

    def test_refuses_hash_mismatch(self):
        a = write_shard(self.tmp / "a", {"AAA": "x\n"})
        (a / "AAA.csv").write_text("tampered\n")
        with self.assertRaises(module.MergeError):
            module.merge(self.tmp / "out", [a])


if __name__ == "__main__":
    unittest.main()
