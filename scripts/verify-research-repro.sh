#!/bin/sh
# Regenerate full-report.json and leadlag-report.json under Python 3.11 into a temp dir and compare sha256 with the
# reference hashes of the committed reports (research-data is gitignored, so the reference lives here).
# Exit codes: 0 all match, 1 hash mismatch, 2 setup problem (missing research-data, venv, or wrong Python).
set -eu

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERVICE_DIR="$REPO_ROOT/analysis-service"
DATA_DIR="$REPO_ROOT/research-data"
PYTHON="$SERVICE_DIR/.venv/bin/python"
REF_FULL="c565973b2ab4968952d282303c2b75f19aabb7400ceaa04117dbc714abe335ff"
REF_LEADLAG="1f449d9abf5fab4d4709470e21d381dd88ff3dd8be596c9c60bd3fc451861145"

setup_error() {
  echo "error: $*" >&2
  exit 2
}

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    sha256sum "$1" | cut -d' ' -f1
  fi
}

[ -d "$DATA_DIR/full" ] && [ -d "$DATA_DIR/leadlag-tr" ] && [ -f "$DATA_DIR/full-report.json" ] ||
  setup_error "research-data is missing ($DATA_DIR needs full/, leadlag-tr/, full-report.json). It is gitignored; restore it first."
[ -x "$PYTHON" ] || setup_error "$PYTHON not found. Run scripts/setup-research-venv.sh first."
"$PYTHON" -c 'import sys; sys.exit(0 if sys.version_info[:2] == (3, 11) else 1)' ||
  setup_error "the venv is not Python 3.11. Re-run scripts/setup-research-venv.sh."

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

cd "$SERVICE_DIR"
"$PYTHON" -m app.research --data ../research-data/full --out "$TMP_DIR/full-report.json" >/dev/null
"$PYTHON" -m app.research.leadlag --data ../research-data/leadlag-tr \
  --prior-report ../research-data/full-report.json --out "$TMP_DIR/leadlag-report.json" >/dev/null

status=0
check() {
  name=$1
  actual=$2
  expected=$3
  if [ "$actual" = "$expected" ]; then
    echo "match    $name $actual"
  else
    echo "MISMATCH $name expected $expected got $actual" >&2
    status=1
  fi
}

check "full-report.json   (regenerated)" "$(sha256_of "$TMP_DIR/full-report.json")" "$REF_FULL"
check "leadlag-report.json (regenerated)" "$(sha256_of "$TMP_DIR/leadlag-report.json")" "$REF_LEADLAG"
check "research-data/full-report.json (committed prior)" "$(sha256_of "$DATA_DIR/full-report.json")" "$REF_FULL"

if [ "$status" -eq 0 ]; then
  echo "ok: research reports reproduce byte for byte under $("$PYTHON" -c 'import platform; print(platform.python_version())')"
else
  echo "fail: reproduction mismatch" >&2
fi
exit "$status"
