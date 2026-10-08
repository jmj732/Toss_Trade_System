#!/usr/bin/env python3
"""Classify affected verification lanes; uncertain CI changes run every lane."""
import os
import subprocess


def classify(paths):
    lanes = dict.fromkeys(["backend", "analysis", "dashboard", "stack"], False)
    workflows = {"ci-backend.yml": "backend", "ci-analysis.yml": "analysis",
                 "ci-dashboard.yml": "dashboard", "ci-stack.yml": "stack", "cd.yml": "stack"}
    for path in paths:
        if path.startswith(".github/workflows/"):
            lane = workflows.get(path.removeprefix(".github/workflows/"))
            if lane is None:
                return dict.fromkeys(lanes, True)
            lanes[lane] = True
        elif path.startswith("scripts/ci-") or path.startswith("scripts/test-ci-"):
            return dict.fromkeys(lanes, True)
        elif path.startswith("contracts/"):
            lanes.update(backend=True, analysis=True, stack=True)
        elif path.startswith("trading-backend/"):
            lanes["backend"] = True
            if not path.startswith("trading-backend/src/test/"):
                lanes["stack"] = True
        elif path.startswith("analysis-service/"):
            lanes["analysis"] = True
            if not path.startswith("analysis-service/tests/"):
                lanes["stack"] = True
        elif path.startswith("web-dashboard/"):
            lanes.update(dashboard=True, stack=True)
        elif path.startswith(("scripts/", "mocks/", "compose")):
            lanes["stack"] = True
    return lanes


def main():
    base = os.environ.get("BASE_SHA", "")
    valid_base = bool(base and set(base) != {"0"}) and subprocess.run(
        ["git", "cat-file", "-e", f"{base}^{{commit}}"], capture_output=True).returncode == 0
    if os.environ["GITHUB_EVENT_NAME"] == "workflow_dispatch" or not valid_base:
        lanes = dict.fromkeys(["backend", "analysis", "dashboard", "stack"], True)
    else:
        paths = subprocess.check_output(["git", "diff", "--name-only", base, os.environ["GITHUB_SHA"]],
                                        text=True).splitlines()
        lanes = classify(paths)
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        for lane, affected in lanes.items():
            output.write(f"{lane}={str(affected).lower()}\n")
    print("Affected CI lanes:", lanes)


if __name__ == "__main__":
    main()
