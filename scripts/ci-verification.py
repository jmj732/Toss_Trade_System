#!/usr/bin/env python3
"""Reuse only a successful same-repository PR verification of the exact Git tree."""
import datetime as dt
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], timeout=20))


def valid_proof(proof, run, repo, tree, now):
    try:
        age = now - dt.datetime.fromisoformat(run["updated_at"].replace("Z", "+00:00"))
        return (
            run["conclusion"] == "success" and run["status"] == "completed"
            and run["event"] == "pull_request" and run["path"] == ".github/workflows/ci.yml"
            and run["head_repository"]["full_name"] == repo
            and dt.timedelta(0) <= age <= dt.timedelta(hours=2)
            and proof["version"] == 1 and proof["repository"] == repo
            and proof["runId"] == str(run["id"])
            and proof["runAttempt"] == str(run["run_attempt"])
            and proof["headSha"] == run["head_sha"] and proof["treeSha"] == tree
            and proof["event"] == "pull_request"
            and proof["results"]["changes"] == "success"
            and set(proof["results"]) == {"changes", "backend", "analysis", "dashboard", "stack"}
            and all(value in {"success", "skipped"} for value in proof["results"].values())
        )
    except (KeyError, TypeError, ValueError):
        return False


def find_verified_pr(repo, sha, tree):
    now = dt.datetime.now(dt.timezone.utc)
    for pr in api(f"repos/{repo}/commits/{sha}/pulls"):
        if not (pr.get("merged_at") and pr.get("merge_commit_sha") == sha
                and pr["base"]["ref"] == "main"
                and pr["head"]["repo"] and pr["head"]["repo"]["full_name"] == repo):
            continue
        head = pr["head"]["sha"]
        runs = api(f"repos/{repo}/actions/workflows/ci.yml/runs?event=pull_request&head_sha={head}&per_page=20")
        for run in runs["workflow_runs"]:
            age = now - dt.datetime.fromisoformat(run["updated_at"].replace("Z", "+00:00"))
            if run.get("conclusion") != "success" or not dt.timedelta(0) <= age <= dt.timedelta(hours=2):
                continue
            artifacts = api(f"repos/{repo}/actions/runs/{run['id']}/artifacts")
            for artifact in artifacts["artifacts"]:
                if artifact["name"] != "ci-verification" or artifact["expired"]:
                    continue
                # gh handles the authenticated API redirect; never forward tokens to storage hosts.
                archive = subprocess.check_output([
                    "gh", "api", "--allow-escape-sequences",
                    f"repos/{repo}/actions/artifacts/{artifact['id']}/zip"], timeout=20)
                with zipfile.ZipFile(io.BytesIO(archive)) as files:
                    proof = json.loads(files.read("verification.json"))
                if valid_proof(proof, run, repo, tree, now):
                    return run["id"]
    return None


def write_proof():
    Path("verification.json").write_text(json.dumps({
        "version": 1, "repository": os.environ["GITHUB_REPOSITORY"],
        "event": os.environ["GITHUB_EVENT_NAME"], "runId": os.environ["GITHUB_RUN_ID"],
        "runAttempt": os.environ["GITHUB_RUN_ATTEMPT"],
        "headSha": os.environ["VERIFIED_HEAD_SHA"], "treeSha": git("rev-parse", "HEAD^{tree}"),
        "results": json.loads(os.environ["JOB_RESULTS"]),
    }))


def check_proof():
    run_id = None
    if os.environ["GITHUB_EVENT_NAME"] == "push" and os.environ["GITHUB_REF"] == "refs/heads/main":
        try:
            run_id = find_verified_pr(os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_SHA"],
                                      git("rev-parse", "HEAD^{tree}"))
        except (subprocess.SubprocessError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile):
            print("Verification evidence unavailable: run full CI.")
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"reuse={'true' if run_id else 'false'}\n")
    if run_id:
        print(f"Exact tree already verified by successful PR CI run {run_id}.")


if __name__ == "__main__":
    {"write": write_proof, "check": check_proof}[sys.argv[1]]()
