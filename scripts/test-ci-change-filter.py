import importlib.util
from pathlib import Path
import unittest
import os
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location("filter", Path(__file__).with_name("ci-change-filter.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ChangeFilterTest(unittest.TestCase):
    def lanes(self, *paths):
        return {key for key, value in module.classify(paths).items() if value}

    def test_shallow_merge_checkout_preserves_parent_comparison_and_tree(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "source"
            clone = Path(temp) / "clone"
            source.mkdir()
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=source,
                                               stderr=subprocess.DEVNULL, text=True).strip()
            git("init", "-b", "main")
            (source / "README.md").write_text("fixture\n")
            git("add", ".")
            commit = ("-c", "user.name=CI Fixture", "-c", "user.email=fixture@example.invalid",
                      "commit", "-m", "fixture")
            git(*commit)
            base = git("rev-parse", "HEAD")
            git("checkout", "-b", "topic")
            test = source / "trading-backend/src/test/java/Example.java"
            test.parent.mkdir(parents=True)
            test.write_text("fixture\n")
            git("add", ".")
            git(*commit)
            git("checkout", "main")
            git("-c", "user.name=CI Fixture", "-c", "user.email=fixture@example.invalid",
                "merge", "--no-ff", "topic", "-m", "fixture merge")
            head, tree = git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}")
            subprocess.run(["git", "clone", "--depth=2", source.as_uri(), str(clone)],
                           check=True, capture_output=True)
            output = Path(temp) / "output"
            env = dict(os.environ, BASE_SHA=base, GITHUB_SHA=head,
                       GITHUB_EVENT_NAME="pull_request", GITHUB_OUTPUT=str(output))
            subprocess.run([sys.executable, str(Path(module.__file__).resolve())],
                           cwd=clone, env=env, check=True, capture_output=True)
            self.assertEqual("backend=true\nanalysis=false\ndashboard=false\nstack=false\n",
                             output.read_text())
            self.assertEqual(tree, subprocess.check_output(
                ["git", "rev-parse", "HEAD^{tree}"], cwd=clone, text=True).strip())
        workflow = Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml"
        self.assertIn("fetch-depth: 2", workflow.read_text())

    def test_backend_tests_do_not_rebuild_stack(self):
        self.assertEqual({"backend"}, self.lanes("trading-backend/src/test/java/Example.java"))

    def test_backend_runtime_retains_smoke(self):
        for path in ["trading-backend/src/main/java/Example.java", "trading-backend/pom.xml",
                     "trading-backend/Dockerfile"]:
            self.assertEqual({"backend", "stack"}, self.lanes(path))

    def test_individual_workflow_does_not_trigger_unrelated_services(self):
        for name, lane in [("backend", "backend"), ("analysis", "analysis"),
                           ("dashboard", "dashboard"), ("stack", "stack")]:
            self.assertEqual({lane}, self.lanes(f".github/workflows/ci-{name}.yml"))

    def test_ci_control_changes_run_everything(self):
        for path in [".github/workflows/ci.yml", ".github/workflows/unknown.yml",
                     "scripts/ci-verification.py", "scripts/test-ci-change-filter.py"]:
            self.assertEqual({"backend", "analysis", "dashboard", "stack"}, self.lanes(path))

    def test_shared_contracts_and_compose(self):
        self.assertEqual({"backend", "analysis", "stack"}, self.lanes("contracts/example.json"))
        self.assertEqual({"stack"}, self.lanes("compose.yaml"))

    def test_dashboard_runtime_and_docs(self):
        self.assertEqual({"dashboard", "stack"}, self.lanes("web-dashboard/package-lock.json"))
        self.assertEqual(set(), self.lanes("docs/example.md"))


if __name__ == "__main__":
    unittest.main()
