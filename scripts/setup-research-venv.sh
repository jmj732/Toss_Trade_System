#!/bin/sh
# Create analysis-service/.venv with Python 3.11 and the pinned dependencies from analysis-service/pyproject.toml.
# The LL-1 reproducibility reference (scripts/verify-research-repro.sh) is defined for Python 3.11 only.
# Exit codes: 0 success, 1 setup failure (no Python 3.11, install or pin check failed).
set -eu

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERVICE_DIR="$REPO_ROOT/analysis-service"
VENV_DIR="$SERVICE_DIR/.venv"
UV_PYTHON_DIR="${UV_PYTHON_INSTALL_DIR:-$HOME/.local/share/uv/python}"

fail() {
  echo "error: $*" >&2
  exit 1
}

find_python311() {
  if command -v python3.11 >/dev/null 2>&1; then
    command -v python3.11
    return 0
  fi
  for candidate in "$UV_PYTHON_DIR"/cpython-3.11.*/bin/python3.11; do
    if [ -x "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

PYTHON=$(find_python311) || fail "Python 3.11 not found. Put python3.11 on PATH, or install one with 'uv python install 3.11'."

rm -rf "$VENV_DIR"
"$PYTHON" -m venv "$VENV_DIR" || fail "could not create $VENV_DIR"

REQUIREMENTS=$(mktemp)
trap 'rm -f "$REQUIREMENTS"' EXIT
"$PYTHON" - "$SERVICE_DIR/pyproject.toml" >"$REQUIREMENTS" <<'PY'
import sys
import tomllib

with open(sys.argv[1], "rb") as handle:
    project = tomllib.load(handle)["project"]
for requirement in project["dependencies"] + project["optional-dependencies"]["test"]:
    print(requirement)
PY

"$VENV_DIR/bin/python" -m pip install --quiet --disable-pip-version-check --requirement "$REQUIREMENTS" ||
  fail "pip install failed"

# Verify the interpreter and that every pinned package installed at exactly the pinned version.
"$VENV_DIR/bin/python" - "$REQUIREMENTS" <<'PY' || fail "installed versions do not match the pins"
import platform
import sys
from importlib.metadata import version

ok = True
for line in open(sys.argv[1], encoding="utf-8"):
    requirement = line.strip()
    if not requirement:
        continue
    name, pinned = requirement.split("==", 1)
    installed = version(name)
    if installed != pinned:
        ok = False
        print(f"mismatch: {name} installed {installed}, pinned {pinned}", file=sys.stderr)
print(f"python: {platform.python_version()} ({sys.executable})")
sys.exit(0 if ok and sys.version_info[:2] == (3, 11) else 1)
PY

echo "ok: research venv ready at $VENV_DIR"
