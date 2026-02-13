#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
MANIFEST="$ROOT_DIR/tools/audio/batch3_topic_manifest.tsv"
SOUNDS_JSON="$ROOT_DIR/src/main/resources/assets/ai-player/sounds.json"
SOUNDS_JAVA="$ROOT_DIR/src/main/java/net/shasankp000/ChatUtils/BotDialogueSounds.java"
PLAYER_JAVA="$ROOT_DIR/src/main/java/net/shasankp000/ChatUtils/BotDialoguePlayer.java"
MAPPER_JAVA="$ROOT_DIR/src/main/java/net/shasankp000/ChatUtils/DialogueTextMapper.java"

if [[ ! -f "$MANIFEST" ]]; then
  echo "FAIL: Manifest not found: $MANIFEST" >&2
  exit 1
fi

manifest_ids=$(tail -n +2 "$MANIFEST" | wc -l | tr -d ' ')
expected_ids=105
if [[ "$manifest_ids" -ne "$expected_ids" ]]; then
  echo "FAIL: Expected $expected_ids IDs in manifest; got $manifest_ids" >&2
  exit 1
fi

missing_files=0
missing_sounds_json=0
missing_constants=0
missing_subtitles=0
missing_mapper_exact=0
files_checked=0

while IFS=$'\t' read -r line_id trigger_key rarity chat_text sound_event_id source1 source2 source3 target1 target2 target3; do
  [[ -z "$line_id" || "$line_id" == "line_id" ]] && continue

  for f in "$target1" "$target2" "$target3"; do
    files_checked=$((files_checked + 1))
    if [[ ! -f "$ROOT_DIR/$f" ]]; then
      echo "MISSING FILE: $f"
      missing_files=$((missing_files + 1))
    fi
  done

  if ! grep -Fq "\"$sound_event_id\"" "$SOUNDS_JSON"; then
    echo "MISSING sounds.json entry: $sound_event_id"
    missing_sounds_json=$((missing_sounds_json + 1))
  fi

  const_name="LINE_$(echo "$line_id" | tr '[:lower:]' '[:upper:]')"

  if ! grep -Fq "$const_name" "$SOUNDS_JAVA"; then
    echo "MISSING BotDialogueSounds constant: $const_name"
    missing_constants=$((missing_constants + 1))
  fi

  if ! grep -Fq "$const_name" "$PLAYER_JAVA"; then
    echo "MISSING BotDialoguePlayer subtitle mapping: $const_name"
    missing_subtitles=$((missing_subtitles + 1))
  fi

  if ! grep -Fq "EXACT_MAP.put(\"$chat_text\", BotDialogueSounds.$const_name);" "$MAPPER_JAVA"; then
    echo "MISSING DialogueTextMapper exact mapping: $line_id"
    missing_mapper_exact=$((missing_mapper_exact + 1))
  fi
done < "$MANIFEST"

echo "Audit summary:"
echo "- Manifest IDs: $manifest_ids"
echo "- Files checked: $files_checked"
echo "- Missing files: $missing_files"
echo "- Missing sounds.json entries: $missing_sounds_json"
echo "- Missing BotDialogueSounds constants: $missing_constants"
echo "- Missing BotDialoguePlayer subtitles: $missing_subtitles"
echo "- Missing DialogueTextMapper exact mappings: $missing_mapper_exact"

if [[ "$missing_files" -ne 0 || "$missing_sounds_json" -ne 0 || "$missing_constants" -ne 0 || "$missing_subtitles" -ne 0 || "$missing_mapper_exact" -ne 0 ]]; then
  echo "FAIL: Batch 3 topic integrity checks failed." >&2
  exit 1
fi

echo "PASS: Batch 3 topic integrity checks passed."
