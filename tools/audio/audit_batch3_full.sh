#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

echo "[1/2] Running Batch 3 Phase 1 audit..."
"$ROOT_DIR/tools/audio/audit_batch3_phase1.sh"

echo
echo "[2/2] Running Batch 3 Topic (Phase 2A) audit..."
"$ROOT_DIR/tools/audio/audit_batch3_topic.sh"

echo
echo "PASS: Batch 3 full audio audit passed (Phase 1 + Phase 2A topic pack)."
