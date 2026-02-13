#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

python3 "$ROOT_DIR/tools/audio/report_dialogue_coverage.py" \
  --repo-root "$ROOT_DIR" \
  --out "docs/audio/DIALOGUE_COVERAGE_REPORT.md" \
  "$@"
