#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Set, Tuple


BOT_SOUNDS_RE = re.compile(
    r'public\s+static\s+final\s+SoundEvent\s+([A-Z0-9_]+)\s*=\s*register\("([^"]+)"\);'
)
BOT_SOUND_REF_RE = re.compile(r'BotDialogueSounds\.([A-Z0-9_]+)')
EXACT_MAP_RE = re.compile(
    r'EXACT_MAP\.put\("((?:\\\\.|[^"\\])*)",\s*BotDialogueSounds\.([A-Z0-9_]+)\);'
)
NO_MAPPING_LOG_RE = re.compile(r"No sound mapping for message: '(.*)'")
PLAYED_SOUND_LOG_RE = re.compile(r"Played sound\s+([^\s]+)")

# Certain dialogue strings are intentionally shared across systems and can map
# to a different constant on lookup without being a defect.
INTENTIONAL_TEXT_ALIAS_CONSTS: Dict[str, Set[str]] = {
    # Used in proactive danger warnings and cliff-context banter.
    "precipice_big_drop": {"LINE_WARNING_DROP_AHEAD"},
}


@dataclass(frozen=True)
class ManifestRow:
    line_id: str
    trigger_key: str
    rarity: str
    chat_text: str
    sound_event_id: str
    source_oggs: Tuple[str, str, str]
    target_oggs: Tuple[str, str, str]



def decode_java_string(s: str) -> str:
    # Good enough for this mapper content (quotes/slashes/newlines).
    return bytes(s, "utf-8").decode("unicode_escape")



def load_sounds_json(path: Path) -> Dict[str, List[str]]:
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)

    out: Dict[str, List[str]] = {}
    for event_id, payload in data.items():
        sounds = payload.get("sounds", [])
        names: List[str] = []
        for entry in sounds:
            if isinstance(entry, dict):
                name = entry.get("name", "")
            else:
                name = str(entry)
            names.append(name)
        out[event_id] = names
    return out



def parse_bot_dialogue_sounds(path: Path) -> Dict[str, str]:
    text = path.read_text(encoding="utf-8")
    mapping: Dict[str, str] = {}
    for const, event_id in BOT_SOUNDS_RE.findall(text):
        mapping[const] = event_id
    return mapping



def collect_constant_references(repo_root: Path) -> Dict[str, Set[str]]:
    refs: Dict[str, Set[str]] = defaultdict(set)
    for file in repo_root.rglob("*.java"):
        rel = str(file.relative_to(repo_root))
        if rel.endswith("BotDialogueSounds.java"):
            continue
        text = file.read_text(encoding="utf-8", errors="replace")
        for const in BOT_SOUND_REF_RE.findall(text):
            refs[const].add(rel)
    return refs



def parse_exact_map(path: Path) -> Dict[str, str]:
    text = path.read_text(encoding="utf-8")
    mapping: Dict[str, str] = {}
    for raw_text, const in EXACT_MAP_RE.findall(text):
        mapping[decode_java_string(raw_text)] = const
    return mapping



def parse_manifest(path: Path) -> List[ManifestRow]:
    rows: List[ManifestRow] = []
    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for r in reader:
            rows.append(
                ManifestRow(
                    line_id=r["line_id"].strip(),
                    trigger_key=r["trigger_key"].strip(),
                    rarity=r["rarity"].strip(),
                    chat_text=r["chat_text"],
                    sound_event_id=r["sound_event_id"].strip(),
                    source_oggs=(r["source_ogg_01"], r["source_ogg_02"], r["source_ogg_03"]),
                    target_oggs=(r["target_ogg_01"], r["target_ogg_02"], r["target_ogg_03"]),
                )
            )
    return rows



def parse_log(path: Path) -> Tuple[List[str], Counter]:
    no_mapping: List[str] = []
    played: Counter = Counter()
    if not path.exists():
        return no_mapping, played

    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            m1 = NO_MAPPING_LOG_RE.search(line)
            if m1:
                no_mapping.append(m1.group(1))
            m2 = PLAYED_SOUND_LOG_RE.search(line)
            if m2:
                played[m2.group(1)] += 1
    return no_mapping, played



def as_relative_if_possible(path: Path, root: Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)



def top_list(items: List[str], max_items: int) -> List[str]:
    if len(items) <= max_items:
        return items
    return items[:max_items] + [f"... (+{len(items) - max_items} more)"]



def main() -> int:
    parser = argparse.ArgumentParser(description="Generate dialogue/audio coverage report.")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument(
        "--source-ogg-dir",
        default="/Users/roti/gemini_projects/ai-player-dialogue/january_2026_batch3/output_ogg",
    )
    parser.add_argument("--sounds-json", default="src/main/resources/assets/ai-player/sounds.json")
    parser.add_argument("--bot-sounds-java", default="src/main/java/net/shasankp000/ChatUtils/BotDialogueSounds.java")
    parser.add_argument("--mapper-java", default="src/main/java/net/shasankp000/ChatUtils/DialogueTextMapper.java")
    parser.add_argument("--phase1-manifest", default="tools/audio/batch3_phase1_manifest.tsv")
    parser.add_argument("--topic-manifest", default="tools/audio/batch3_topic_manifest.tsv")
    parser.add_argument(
        "--log-path",
        default="/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10/minecraft/logs/latest.log",
    )
    parser.add_argument("--out", default="docs/audio/DIALOGUE_COVERAGE_REPORT.md")
    parser.add_argument("--max-list", type=int, default=120)
    args = parser.parse_args()

    repo_root = Path(args.repo_root).resolve()
    source_ogg_dir = Path(args.source_ogg_dir)
    sounds_json_path = (repo_root / args.sounds_json).resolve()
    bot_sounds_path = (repo_root / args.bot_sounds_java).resolve()
    mapper_path = (repo_root / args.mapper_java).resolve()
    phase1_manifest_path = (repo_root / args.phase1_manifest).resolve()
    topic_manifest_path = (repo_root / args.topic_manifest).resolve()
    log_path = Path(args.log_path)
    out_path = (repo_root / args.out).resolve()

    sounds_json = load_sounds_json(sounds_json_path)
    const_to_event = parse_bot_dialogue_sounds(bot_sounds_path)
    const_refs = collect_constant_references(repo_root)
    exact_map = parse_exact_map(mapper_path)

    phase1_rows = parse_manifest(phase1_manifest_path)
    topic_rows = parse_manifest(topic_manifest_path)
    batch3_rows = phase1_rows + topic_rows

    source_oggs: Set[str] = set()
    if source_ogg_dir.exists():
        source_oggs = {p.name for p in source_ogg_dir.glob("*.ogg")}

    sounds_json_dialogue_assets: Set[str] = set()
    for names in sounds_json.values():
        for n in names:
            if n.startswith("ai-player:dialogue/"):
                sounds_json_dialogue_assets.add(n.split("ai-player:dialogue/")[1] + ".ogg")

    mapped_source = sorted(source_oggs & sounds_json_dialogue_assets)
    unmapped_source = sorted(source_oggs - sounds_json_dialogue_assets)

    line_fx_consts = {
        c
        for c, e in const_to_event.items()
        if e.startswith("bot.line.") or e.startswith("bot.fx.")
    }

    unreferenced_line_fx_consts = sorted(c for c in line_fx_consts if c not in const_refs)

    # Referenced only by subtitles (BotDialoguePlayer) but nowhere else is suspicious.
    subtitle_only_consts: List[str] = []
    for c in line_fx_consts:
        files = const_refs.get(c, set())
        if not files:
            continue
        non_subtitle_files = {f for f in files if not f.endswith("BotDialoguePlayer.java")}
        if not non_subtitle_files:
            subtitle_only_consts.append(c)
    subtitle_only_consts.sort()

    # Event registration consistency.
    declared_events = set(const_to_event.values())
    sounds_json_events = set(sounds_json.keys())
    declared_not_in_json = sorted(e for e in declared_events if e not in sounds_json_events)
    json_not_declared = sorted(e for e in sounds_json_events if e not in declared_events)

    # Batch3 manifest routing checks.
    batch3_missing_json: List[str] = []
    batch3_exact_wrong_const: List[str] = []
    batch3_exact_intentional_alias: List[str] = []
    batch3_direct_routed: List[str] = []
    batch3_text_mapped: List[str] = []
    batch3_unreachable: List[str] = []
    for row in batch3_rows:
        if row.sound_event_id not in sounds_json_events:
            batch3_missing_json.append(row.line_id)

        expected_const = f"LINE_{row.line_id.upper()}"
        got_const = exact_map.get(row.chat_text)
        text_mapped = False
        if got_const is not None and got_const == expected_const:
            text_mapped = True
        elif got_const is not None and got_const != expected_const:
            allowed_aliases = INTENTIONAL_TEXT_ALIAS_CONSTS.get(row.line_id, set())
            if got_const in allowed_aliases:
                batch3_exact_intentional_alias.append(
                    f"{row.line_id} -> expected {expected_const}, got {got_const}"
                )
            else:
                batch3_exact_wrong_const.append(
                    f"{row.line_id} -> expected {expected_const}, got {got_const}"
                )

        refs = const_refs.get(expected_const, set())
        direct_refs = {
            f for f in refs
            if not f.endswith("BotDialoguePlayer.java")
            and not f.endswith("DialogueTextMapper.java")
        }
        direct_routed = bool(direct_refs)

        if direct_routed:
            batch3_direct_routed.append(row.line_id)
        if text_mapped:
            batch3_text_mapped.append(row.line_id)
        if not direct_routed and not text_mapped:
            batch3_unreachable.append(row.line_id)

    # Batch3 target path existence checks.
    missing_target_paths: List[str] = []
    for row in batch3_rows:
        for p in row.target_oggs:
            full = (repo_root / p).resolve()
            if not full.exists():
                missing_target_paths.append(p)

    no_mapping_lines, played_counter = parse_log(log_path)
    no_mapping_unique = sorted(set(no_mapping_lines))

    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")

    out_lines: List[str] = []
    out_lines.append("# Dialogue Coverage Report")
    out_lines.append("")
    out_lines.append(f"Generated: `{now}`")
    out_lines.append("")
    out_lines.append("## Inputs")
    out_lines.append("")
    out_lines.append(f"- Repo root: `{repo_root}`")
    out_lines.append(f"- Source ogg dir: `{source_ogg_dir}`")
    out_lines.append(f"- sounds.json: `{as_relative_if_possible(sounds_json_path, repo_root)}`")
    out_lines.append(f"- BotDialogueSounds: `{as_relative_if_possible(bot_sounds_path, repo_root)}`")
    out_lines.append(f"- DialogueTextMapper: `{as_relative_if_possible(mapper_path, repo_root)}`")
    out_lines.append(f"- Phase1 manifest: `{as_relative_if_possible(phase1_manifest_path, repo_root)}`")
    out_lines.append(f"- Topic manifest: `{as_relative_if_possible(topic_manifest_path, repo_root)}`")
    out_lines.append(f"- Log file: `{log_path}`")
    out_lines.append("")

    out_lines.append("## Summary")
    out_lines.append("")
    out_lines.append(f"- Source clips (`output_ogg`): **{len(source_oggs)}**")
    out_lines.append(f"- Source clips mapped by sounds.json: **{len(mapped_source)}**")
    out_lines.append(f"- Source clips unmapped: **{len(unmapped_source)}**")
    out_lines.append(f"- Declared line/fx constants: **{len(line_fx_consts)}**")
    out_lines.append(f"- Declared line/fx constants unreferenced outside declarations: **{len(unreferenced_line_fx_consts)}**")
    out_lines.append(f"- Declared events missing in sounds.json: **{len(declared_not_in_json)}**")
    out_lines.append(f"- sounds.json events missing in BotDialogueSounds: **{len(json_not_declared)}**")
    out_lines.append(f"- Batch3 manifest rows checked: **{len(batch3_rows)}**")
    out_lines.append(f"- Batch3 rows missing sounds.json event: **{len(batch3_missing_json)}**")
    out_lines.append(f"- Batch3 rows direct-routed via trigger services: **{len(batch3_direct_routed)}**")
    out_lines.append(f"- Batch3 rows exact text-mapped in DialogueTextMapper: **{len(batch3_text_mapped)}**")
    out_lines.append(f"- Batch3 rows with no direct route and no exact map (unreachable): **{len(batch3_unreachable)}**")
    out_lines.append(f"- Batch3 rows with intentional text-alias mapping: **{len(batch3_exact_intentional_alias)}**")
    out_lines.append(f"- Batch3 rows exact-mapped to wrong constant: **{len(batch3_exact_wrong_const)}**")
    out_lines.append(f"- Batch3 target files missing in repo: **{len(missing_target_paths)}**")
    out_lines.append(f"- Log lines with `No sound mapping for message`: **{len(no_mapping_lines)}** total / **{len(no_mapping_unique)}** unique")
    out_lines.append("")

    out_lines.append("## Unmapped Source Clips")
    out_lines.append("")
    if not unmapped_source:
        out_lines.append("- None")
    else:
        for item in top_list(unmapped_source, args.max_list):
            out_lines.append(f"- `{item}`")
    out_lines.append("")

    out_lines.append("## Unreferenced Line/Fx Constants")
    out_lines.append("")
    if not unreferenced_line_fx_consts:
        out_lines.append("- None")
    else:
        for c in top_list(unreferenced_line_fx_consts, args.max_list):
            out_lines.append(f"- `{c}` -> `{const_to_event.get(c, '')}`")
    out_lines.append("")

    out_lines.append("## Constants Referenced Only In Subtitles")
    out_lines.append("")
    if not subtitle_only_consts:
        out_lines.append("- None")
    else:
        for c in top_list(subtitle_only_consts, args.max_list):
            out_lines.append(f"- `{c}` -> `{const_to_event.get(c, '')}`")
    out_lines.append("")

    out_lines.append("## Registration Mismatches")
    out_lines.append("")
    out_lines.append("### Declared But Missing In sounds.json")
    out_lines.append("")
    if not declared_not_in_json:
        out_lines.append("- None")
    else:
        for e in top_list(declared_not_in_json, args.max_list):
            out_lines.append(f"- `{e}`")
    out_lines.append("")

    out_lines.append("### sounds.json But Missing In BotDialogueSounds")
    out_lines.append("")
    if not json_not_declared:
        out_lines.append("- None")
    else:
        for e in top_list(json_not_declared, args.max_list):
            out_lines.append(f"- `{e}`")
    out_lines.append("")

    out_lines.append("## Batch3 Mapping Integrity")
    out_lines.append("")
    out_lines.append("### Missing sounds.json Events")
    out_lines.append("")
    if not batch3_missing_json:
        out_lines.append("- None")
    else:
        for x in top_list(sorted(batch3_missing_json), args.max_list):
            out_lines.append(f"- `{x}`")
    out_lines.append("")

    out_lines.append("### Direct-Routed Rows")
    out_lines.append("")
    if not batch3_direct_routed:
        out_lines.append("- None")
    else:
        for x in top_list(sorted(batch3_direct_routed), args.max_list):
            out_lines.append(f"- `{x}`")
    out_lines.append("")

    out_lines.append("### EXACT_MAP Rows")
    out_lines.append("")
    if not batch3_text_mapped:
        out_lines.append("- None")
    else:
        for x in top_list(sorted(batch3_text_mapped), args.max_list):
            out_lines.append(f"- `{x}`")
    out_lines.append("")

    out_lines.append("### Unreachable Rows (No Direct Route + No EXACT_MAP)")
    out_lines.append("")
    if not batch3_unreachable:
        out_lines.append("- None")
    else:
        for x in top_list(sorted(batch3_unreachable), args.max_list):
            out_lines.append(f"- `{x}`")
    out_lines.append("")

    out_lines.append("### EXACT_MAP Wrong Constant")
    out_lines.append("")
    if not batch3_exact_wrong_const:
        out_lines.append("- None")
    else:
        for x in top_list(sorted(batch3_exact_wrong_const), args.max_list):
            out_lines.append(f"- `{x}`")
    out_lines.append("")

    out_lines.append("### EXACT_MAP Intentional Text-Alias")
    out_lines.append("")
    if not batch3_exact_intentional_alias:
        out_lines.append("- None")
    else:
        for x in top_list(sorted(batch3_exact_intentional_alias), args.max_list):
            out_lines.append(f"- `{x}`")
    out_lines.append("")

    out_lines.append("### Missing Batch3 Target Files")
    out_lines.append("")
    if not missing_target_paths:
        out_lines.append("- None")
    else:
        for x in top_list(sorted(missing_target_paths), args.max_list):
            out_lines.append(f"- `{x}`")
    out_lines.append("")

    out_lines.append("## Log-Observed Unmapped Messages")
    out_lines.append("")
    if not no_mapping_unique:
        out_lines.append("- None")
    else:
        for msg in top_list(no_mapping_unique, args.max_list):
            out_lines.append(f"- `{msg}`")
    out_lines.append("")

    out_lines.append("## Log-Observed Played Sound Events (Top 40)")
    out_lines.append("")
    if not played_counter:
        out_lines.append("- None")
    else:
        for event_id, count in played_counter.most_common(40):
            out_lines.append(f"- `{event_id}`: {count}")
    out_lines.append("")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(out_lines) + "\n", encoding="utf-8")

    print(f"Wrote report: {out_path}")
    print(f"source_count={len(source_oggs)} mapped={len(mapped_source)} unmapped={len(unmapped_source)}")
    print(
        f"batch3_rows={len(batch3_rows)} direct={len(batch3_direct_routed)} "
        f"text_mapped={len(batch3_text_mapped)} unreachable={len(batch3_unreachable)} "
        f"alias={len(batch3_exact_intentional_alias)} missing_json={len(batch3_missing_json)}"
    )
    print(f"log_no_mapping_total={len(no_mapping_lines)} unique={len(no_mapping_unique)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
