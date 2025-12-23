#!/bin/zsh
# Double-clickable helper for macOS.
# Trims latest.log for all PrismLauncher instances once.

set -euo pipefail

# Resolve repo root based on this script's location.
SCRIPT_DIR="${0:A:h}"
REPO_ROOT="${SCRIPT_DIR:h:h}"

cd "$REPO_ROOT"

print "\n[logtrim] Repo: $REPO_ROOT"
print "[logtrim] Running: python3 tools/logtrim/logtrim.py --prism-all-once\n"

python3 tools/logtrim/logtrim.py --prism-all-once

print "\n[logtrim] Done. You can close this window."
read -r "?Press Enter to close..."