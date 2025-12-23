#!/bin/zsh
# Double-clickable helper for macOS.
# Keeps a watcher running for all PrismLauncher instances.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
REPO_ROOT="${SCRIPT_DIR:h:h}"

cd "$REPO_ROOT"

print "\n[logtrim] Repo: $REPO_ROOT"
print "[logtrim] Watching all Prism instances. Leave this window open while you play."
print "[logtrim] Tip: increase --settle-time if you often copy/replace logs.\n"

python3 tools/logtrim/logtrim.py --watch-prism-all --poll-interval 1.0 --settle-time 0.75
