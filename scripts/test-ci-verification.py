import datetime as dt
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("verification", Path(__file__).with_name("ci-verification.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class VerificationTest(unittest.TestCase):
    def setUp(self):
        self.now = dt.datetime(2026, 10, 8, 12, tzinfo=dt.timezone.utc)
        self.run = {"id": 123, "run_attempt": 1, "head_sha": "head", "conclusion": "success",
                    "status": "completed", "event": "pull_request", "path": ".github/workflows/ci.yml",
                    "head_repository": {"full_name": "owner/repo"}, "updated_at": "2026-10-08T11:55:00Z"}
        self.proof = {"version": 1, "repository": "owner/repo", "event": "pull_request",
                      "runId": "123", "runAttempt": "1", "headSha": "head", "treeSha": "tree",
                      "results": dict.fromkeys(["changes", "backend", "analysis", "dashboard", "stack"], "success")}

    def valid(self):
        return module.valid_proof(self.proof, self.run, "owner/repo", "tree", self.now)

    def test_exact_successful_tree(self):
        self.assertTrue(self.valid())

    def test_mismatched_proof_cannot_skip(self):
        for key in ["treeSha", "headSha", "repository", "runId", "runAttempt", "event", "version"]:
            with self.subTest(key=key):
                original = self.proof[key]
                self.proof[key] = "wrong"
                self.assertFalse(self.valid())
                self.proof[key] = original

    def test_unsuccessful_stale_or_wrong_workflow_cannot_skip(self):
        for key, value in [("conclusion", "failure"), ("conclusion", "cancelled"),
                           ("status", "in_progress"), ("event", "push"), ("path", "other.yml"),
                           ("head_repository", {"full_name": "fork/repo"}),
                           ("updated_at", "2026-10-08T09:00:00Z"),
                           ("updated_at", "2026-10-08T13:00:00Z")]:
            with self.subTest(key=key, value=value):
                original = self.run[key]
                self.run[key] = value
                self.assertFalse(self.valid())
                self.run[key] = original

    def test_failed_or_missing_job_cannot_skip(self):
        self.proof["results"]["stack"] = "failure"
        self.assertFalse(self.valid())
        del self.proof["results"]["stack"]
        self.assertFalse(self.valid())


if __name__ == "__main__":
    unittest.main()
