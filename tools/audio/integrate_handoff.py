#!/usr/bin/env python3
"""Integrate audio_triage's handoff_to_mod_repo.md into the mod resources.

Reads the handoff markdown, parses each `### bot.line.X` section's source-OGG
copy bullets and the sounds.json JSON block, then:
  1. copies every OGG referenced (skipping files already present unchanged)
  2. merges sounds.json — adds new event blocks, appends new sounds[] entries
     to existing ones (no duplicates)
  3. reports what was added / skipped / missing-on-disk

Idempotent: re-running after a partial integration only fills the gaps.

Usage:
    python3 tools/audio/integrate_handoff.py            # dry run
    python3 tools/audio/integrate_handoff.py --apply    # actually write
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MOD_DIALOGUE_DIR = REPO_ROOT / "src/main/resources/assets/frens/sounds/dialogue"
MOD_SOUNDS_JSON = REPO_ROOT / "src/main/resources/assets/frens/sounds.json"
DIALOGUE_PROJECT = Path("/Users/roti/pontus/ai-player-dialogue")
HANDOFF_PATH = DIALOGUE_PROJECT / "audio_triage/handoff_to_mod_repo.md"


# Match a section header: "### `bot.line.foo`"
SECTION_RE = re.compile(r"^### `(bot\.line\.[a-z0-9_]+)`\s*$", re.MULTILINE)
# Match a source-copy bullet: "- [ ] copy `<batch>/output_ogg/<file>.ogg` → ..."
COPY_RE = re.compile(
    r"- \[ \] copy `([^`]+/output_ogg/[^`]+\.ogg)` "
)
# Match the start of the JSON code block
JSON_FENCE_RE = re.compile(r"^\s*```json\s*$", re.MULTILINE)


def parse_handoff(text: str) -> list[dict]:
    """Return list of {event_id, copies: [src_rel], json_block: dict}."""
    sections = []
    section_starts = [(m.start(), m.group(1)) for m in SECTION_RE.finditer(text)]
    section_starts.append((len(text), None))

    for i in range(len(section_starts) - 1):
        start, event_id = section_starts[i]
        end, _ = section_starts[i + 1]
        block = text[start:end]

        copies = COPY_RE.findall(block)

        # Extract the first ```json ... ``` snippet
        m = JSON_FENCE_RE.search(block)
        json_obj = None
        if m:
            after = block[m.end():]
            close_match = re.search(r"^\s*```\s*$", after, re.MULTILINE)
            if close_match:
                snippet = after[:close_match.start()].strip()
                # Snippet looks like:  "bot.line.foo": { ... }
                # Wrap in braces to make it a complete object.
                wrapped = "{" + snippet + "}"
                try:
                    parsed = json.loads(wrapped)
                    json_obj = parsed.get(event_id)
                except json.JSONDecodeError as e:
                    print(f"  ⚠ JSON parse failed for {event_id}: {e}", file=sys.stderr)

        sections.append({
            "event_id": event_id,
            "copies": copies,
            "json_block": json_obj,
        })
    return sections


def stable_hash(path: Path) -> int:
    return path.stat().st_size  # cheap-and-cheerful change detection


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true",
                        help="actually copy files and write sounds.json (default is dry-run)")
    args = parser.parse_args()

    if not HANDOFF_PATH.exists():
        print(f"❌ Handoff not found at {HANDOFF_PATH}", file=sys.stderr)
        return 1

    text = HANDOFF_PATH.read_text()
    sections = parse_handoff(text)
    # Only the "map" section (1.) carries copy bullets + json blocks. Other sections
    # (regen, replace, retune) won't match COPY_RE so they fall out naturally.
    sections = [s for s in sections if s["copies"] or s["json_block"]]

    print(f"Parsed {len(sections)} mappable sections from handoff.")

    # ─── pass 1: figure out what files we need ───────────────────────────────
    copy_plan = []  # (src, dst, status)
    missing_sources = []
    for s in sections:
        for rel in s["copies"]:
            src = DIALOGUE_PROJECT / rel
            dst = MOD_DIALOGUE_DIR / Path(rel).name
            if not src.exists():
                missing_sources.append(rel)
                continue
            if dst.exists() and stable_hash(src) == stable_hash(dst):
                copy_plan.append((src, dst, "already-present"))
            else:
                copy_plan.append((src, dst, "copy"))

    to_copy = [(s, d) for s, d, st in copy_plan if st == "copy"]
    print(f"  OGGs to copy: {len(to_copy)}")
    print(f"  OGGs already present (size match): {sum(1 for _, _, st in copy_plan if st == 'already-present')}")
    if missing_sources:
        print(f"  ⚠ Missing source files: {len(missing_sources)}")
        for m in missing_sources[:10]:
            print(f"      {m}")

    # ─── pass 2: figure out sounds.json patch ────────────────────────────────
    sounds = json.loads(MOD_SOUNDS_JSON.read_text())
    new_events = 0
    appended_sounds = 0
    for s in sections:
        eid = s["event_id"]
        block = s["json_block"]
        if not block:
            continue
        new_sounds = block.get("sounds", [])
        # Dedupe sounds inside the new block by name (the handoff sometimes lists
        # the same name twice — e.g. one variant present, but quoted twice).
        seen_names = set()
        deduped_new = []
        for snd in new_sounds:
            n = snd.get("name")
            if n and n not in seen_names:
                seen_names.add(n)
                deduped_new.append(snd)

        if eid not in sounds:
            sounds[eid] = {
                "category": block.get("category", "voice"),
                "sounds": deduped_new,
            }
            new_events += 1
        else:
            existing = sounds[eid]
            existing.setdefault("sounds", [])
            existing_names = {s.get("name") for s in existing["sounds"]}
            for snd in deduped_new:
                if snd.get("name") not in existing_names:
                    existing["sounds"].append(snd)
                    existing_names.add(snd.get("name"))
                    appended_sounds += 1

    print(f"  sounds.json: {new_events} new events, {appended_sounds} additional variants appended to existing events")

    if not args.apply:
        print("\n[dry-run] re-run with --apply to commit.")
        return 0

    # ─── apply ───────────────────────────────────────────────────────────────
    for src, dst in to_copy:
        shutil.copy2(src, dst)
    print(f"✓ Copied {len(to_copy)} OGGs into {MOD_DIALOGUE_DIR.relative_to(REPO_ROOT)}")

    if new_events or appended_sounds:
        # Pretty-print preserving the existing 2-space indentation and sorted keys=False
        # (we want to keep the original order; new keys land at the end).
        MOD_SOUNDS_JSON.write_text(json.dumps(sounds, indent=2) + "\n")
        print(f"✓ Wrote {MOD_SOUNDS_JSON.relative_to(REPO_ROOT)} ({new_events} new events, {appended_sounds} new variants)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
