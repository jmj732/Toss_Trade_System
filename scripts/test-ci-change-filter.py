import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("filter", Path(__file__).with_name("ci-change-filter.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ChangeFilterTest(unittest.TestCase):
    def lanes(self, *paths):
        return {key for key, value in module.classify(paths).items() if value}

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
