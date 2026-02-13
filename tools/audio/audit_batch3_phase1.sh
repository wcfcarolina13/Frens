#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="$ROOT_DIR/tools/audio/batch3_phase1_manifest.tsv"
SOUNDS_JSON="$ROOT_DIR/src/main/resources/assets/ai-player/sounds.json"
SOUND_REGISTRY="$ROOT_DIR/src/main/java/net/shasankp000/ChatUtils/BotDialogueSounds.java"

EXPECTED_IDS=87
EXPECTED_FILES=261

if [[ ! -f "$MANIFEST" ]]; then
  echo "ERROR: Missing manifest: $MANIFEST"
  exit 1
fi

id_rows=$(( $(wc -l < "$MANIFEST") - 1 ))
if [[ "$id_rows" -ne "$EXPECTED_IDS" ]]; then
  echo "ERROR: Manifest has $id_rows IDs (expected $EXPECTED_IDS)"
  exit 1
fi

declare -a ids
while IFS= read -r id; do
  ids+=("$id")
done < <(awk -F '\t' 'NR>1 { print $1 }' "$MANIFEST")

dup_count=$(printf '%s\n' "${ids[@]}" | sort | uniq -d | wc -l | tr -d ' ')
if [[ "$dup_count" -ne 0 ]]; then
  echo "ERROR: Manifest contains duplicate line IDs"
  printf '%s\n' "${ids[@]}" | sort | uniq -d
  exit 1
fi

missing_files=0
checked_files=0
while IFS=$'\t' read -r _ _ _ _ _ _ _ _ dst1 dst2 dst3; do
  [[ -z "${dst1:-}" ]] && continue
  for rel in "$dst1" "$dst2" "$dst3"; do
    ((checked_files+=1))
    if [[ ! -f "$ROOT_DIR/$rel" ]]; then
      ((missing_files+=1))
      echo "MISSING FILE: $rel"
    fi
  done
done < <(tail -n +2 "$MANIFEST")

if [[ "$checked_files" -ne "$EXPECTED_FILES" ]]; then
  echo "ERROR: Checked $checked_files files (expected $EXPECTED_FILES)"
  exit 1
fi

missing_sounds=0
missing_registry=0
missing_constants=0
for id in "${ids[@]}"; do
  if ! rg -q --fixed-strings "\"bot.line.$id\"" "$SOUNDS_JSON"; then
    ((missing_sounds+=1))
    echo "MISSING sounds.json entry: bot.line.$id"
  fi

  if ! rg -q --fixed-strings "register(\"bot.line.$id\")" "$SOUND_REGISTRY"; then
    ((missing_registry+=1))
    echo "MISSING BotDialogueSounds register() entry: bot.line.$id"
  fi

  upper=$(echo "$id" | tr '[:lower:]' '[:upper:]')
  if ! rg -q "LINE_${upper}\\b" "$SOUND_REGISTRY"; then
    ((missing_constants+=1))
    echo "MISSING BotDialogueSounds constant: LINE_${upper}"
  fi
done

echo "Audit summary:"
echo "- Manifest IDs: $id_rows"
echo "- Files checked: $checked_files"
echo "- Missing files: $missing_files"
echo "- Missing sounds.json entries: $missing_sounds"
echo "- Missing BotDialogueSounds register entries: $missing_registry"
echo "- Missing BotDialogueSounds constants: $missing_constants"

if [[ "$missing_files" -ne 0 || "$missing_sounds" -ne 0 || "$missing_registry" -ne 0 || "$missing_constants" -ne 0 ]]; then
  exit 1
fi

echo "PASS: Batch 3 Phase 1 audio integrity checks passed."
