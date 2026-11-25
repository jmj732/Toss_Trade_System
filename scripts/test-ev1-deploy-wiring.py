#!/usr/bin/env python3
"""Static contract checks for EV-1 Compose and CD wiring."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *fragments: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [fragment for fragment in fragments if fragment not in text]
    if missing:
        raise AssertionError(f"{path} missing: {missing}")


def main() -> None:
    require("compose.yaml", "  ev1:", 'profiles: ["ev1"]', "ev1-data:/var/lib/ev1",
            "restart: unless-stopped", "EV1_MANIFEST_PATH", "runner-health.json", "ev1-data:\n")
    require("compose.staging.yaml", "  ev1:", "EV1_IMAGE", "STAGING_EV1_MEMORY_LIMIT")
    require("compose.staging.credentialed.yaml", "SEC_USER_AGENT: ${SEC_USER_AGENT:?set SEC_USER_AGENT in Doppler}")
    require(".dockerignore", ".git", ".env*", "research-data", "target", ".venv", "node_modules", ".next")
    require("scripts/ev1/Dockerfile", "VOLUME [\"/var/lib/ev1\"]", "HEALTHCHECK", "ev1-runner.sh", "runner-health.json", "--runner-status")
    require("scripts/ev1-status.py", "--runner-status", "runner_status")
    require("scripts/ev1-runner.sh", "runner-health.json", "write_runner_status")
    require("scripts/collect-yahoo-daily.py", "--refresh")
    require("scripts/ev1-runner.sh", "collect-yahoo-daily.py", "--refresh", "ev1-recorder.py", "--once", "sleep \"$POLL_INTERVAL\"", "datetime.now(timezone.utc)", "SEC_USER_AGENT")
    require(".github/workflows/cd.yml", "Build EV-1 image", "trade-ev1:", "EV1_IMAGE", "stream_image \"$EV1_IMAGE\"",
            "--profile ev1", "up --no-build -d --wait migrate backend dashboard ev1", "ev1-status.py")
    print("EV-1 deployment wiring contract: OK")


if __name__ == "__main__":
    main()
