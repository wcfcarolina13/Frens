#!/usr/bin/env python3
"""AI-Player latest.log trimmer.

Goal: keep the parts that matter for troubleshooting/debugging AI-Player-Checkpoint,
while collapsing known-noise spam (movement step spam, shader link spam, etc).

- Works on Fabric/MC `latest.log` style logs.
- Preserves multi-line records (continuation lines without the usual prefix).
- Outputs an optimized log file suitable for sharing with LLMs.

No third-party dependencies.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as _dt
import re
import shutil
import time
from collections import Counter, deque
from pathlib import Path
from typing import Deque, Iterator, Optional


PREFIX_RE = re.compile(r"^\[([0-9:.]+)\] \[([^/]+)/([A-Z]+)\]:\s?(.*)$")


@dataclasses.dataclass
class Record:
    start_line_no: int
    timestamp: Optional[str]
    thread: Optional[str]
    level: Optional[str]
    message: str
    lines: list[str]  # includes the prefix line and any continuation lines

    def text(self) -> str:
        return "\n".join(self.lines)


@dataclasses.dataclass
class CollapsedRun:
    category: str
    start_ts: Optional[str]
    end_ts: Optional[str]
    record_count: int = 0


def find_repo_root(start: Path) -> Path:
    """Find repo root by walking upward until a Gradle root marker is found."""
    cur = start.resolve()
    for _ in range(25):
        if (cur / "settings.gradle").exists() or (cur / "settings.gradle.kts").exists():
            return cur
        if (cur / "build.gradle").exists() or (cur / "build.gradle.kts").exists():
            # If settings.gradle is missing, treat build.gradle as a fallback marker.
            return cur
        if cur.parent == cur:
            break
        cur = cur.parent
    return start.resolve()


def find_prism_latest_log() -> Optional[Path]:
    """Best-effort discovery of PrismLauncher latest.log on macOS/Linux/Windows.

    On macOS, Prism default instances path is:
      ~/Library/Application Support/PrismLauncher/instances/<instance>/minecraft/logs/latest.log

    We pick the most recently modified latest.log among instances.
    """
    candidates: list[Path] = []

    home = Path.home()
    mac_root = home / "Library" / "Application Support" / "PrismLauncher" / "instances"
    if mac_root.exists():
        for inst in mac_root.iterdir():
            p = inst / "minecraft" / "logs" / "latest.log"
            if p.exists():
                candidates.append(p)

    if not candidates:
        return None

    return max(candidates, key=lambda p: p.stat().st_mtime)


def find_prism_instances_root() -> Optional[Path]:
    """Return the PrismLauncher instances root folder if it exists.

    Currently supports macOS default location.
    """

    home = Path.home()
    mac_root = home / "Library" / "Application Support" / "PrismLauncher" / "instances"
    if mac_root.exists():
        return mac_root
    return None


def iter_prism_instance_latest_logs(instances_root: Path) -> Iterator[tuple[str, Path]]:
    """Yield (instance_name, latest_log_path) for each Prism instance with a latest.log."""

    for inst in instances_root.iterdir():
        if not inst.is_dir():
            continue
        latest = inst / "minecraft" / "logs" / "latest.log"
        if latest.exists():
            yield inst.name, latest


def _safe_instance_dir_name(name: str) -> str:
    # Prism instance names can contain spaces; those are fine.
    # Avoid path separators and Finder-problematic characters.
    s = name.strip()
    s = s.replace("/", "_").replace("\\", "_").replace(":", "_")
    return s or "(unnamed)"


def _default_multi_output_root() -> Path:
    here = Path(__file__).resolve()
    repo_root = find_repo_root(here)
    return repo_root / "Logs-Prism"


def _instance_output_dir(*, output_root: Path, instance_name: str) -> Path:
    # Layout:
    #   <output_root>/instances/<instance>/trimmed/...
    # Example:
    #   /Users/roti/AI-Player-checkpoint/Logs-Prism/instances/1.21.10/trimmed/latest.trimmed.log
    safe = _safe_instance_dir_name(instance_name)
    return output_root / "instances" / safe


def trim_prism_instances_once(
    *,
    instances_root: Path,
    output_root: Path,
    context_before: int,
    context_after: int,
    keep_head_records: int,
) -> int:
    """Trim latest.log for every Prism instance once and exit.

    Outputs are written to each instance's minecraft/logs/trimmed folder.
    Returns a process exit code (0 for success, 2 if no logs found).
    """

    logs = list(iter_prism_instance_latest_logs(instances_root))
    if not logs:
        print(f"No Prism instance latest.log files found under: {instances_root}")
        return 2

    total = 0
    failures = 0
    for inst_name, log_path in logs:
        base_dir = _instance_output_dir(output_root=output_root, instance_name=inst_name)
        out_dir = base_dir / "trimmed"
        out_dir.mkdir(parents=True, exist_ok=True)
        out_no = out_dir / "latest.trimmed.log"
        out_yes = out_dir / "latest.trimmed.with-shaders.log"

        print(f"Trimming instance '{inst_name}': {log_path}")
        try:
            stats_no, stats_yes = run_trim_both(
                input_path=log_path,
                output_no_shaders=out_no,
                output_with_shaders=out_yes,
                context_before=context_before,
                context_after=context_after,
                keep_head_records=keep_head_records,
            )
            print(
                f"  -> {out_no.name} kept={stats_no['kept_records']} dropped={stats_no['dropped_records']}"
            )
            print(
                f"  -> {out_yes.name} kept={stats_yes['kept_records']} dropped={stats_yes['dropped_records']}"
            )
            total += 1
        except Exception as e:
            failures += 1
            print(f"  !! trim failed: {e}")

    print(f"Done. Trimmed {total} instance logs." + (f" Failures: {failures}." if failures else ""))
    return 0 if failures == 0 else 1


def iter_records(path: Path) -> Iterator[Record]:
    """Parse a log file into timestamped records.

    Any line not matching PREFIX_RE becomes a continuation line attached
    to the previous record.
    """
    current_lines: list[str] = []
    current_meta = None  # (start_line_no, ts, thread, level, message)

    def flush() -> Optional[Record]:
        nonlocal current_lines, current_meta
        if not current_lines or current_meta is None:
            current_lines = []
            current_meta = None
            return None
        start_line_no, ts, thread, level, msg = current_meta
        rec = Record(
            start_line_no=start_line_no,
            timestamp=ts,
            thread=thread,
            level=level,
            message=msg,
            lines=current_lines,
        )
        current_lines = []
        current_meta = None
        return rec

    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line_no, raw in enumerate(f, start=1):
            line = raw.rstrip("\n")
            m = PREFIX_RE.match(line)
            if m:
                prev = flush()
                if prev is not None:
                    yield prev
                ts, thread, level, msg = m.group(1), m.group(2), m.group(3), m.group(4)
                current_meta = (line_no, ts, thread, level, msg)
                current_lines = [line]
            else:
                if current_meta is None:
                    # Orphan continuation: start a synthetic record.
                    current_meta = (line_no, None, None, None, line)
                    current_lines = [line]
                else:
                    current_lines.append(line)

    last = flush()
    if last is not None:
        yield last


KEEP_MARKER_RE = re.compile(
    r"ai-player|aiplayer|config/ai-player|fakeplayer|spawnBot|\bBERT\b|\bCART\b|OpenNLP|LIDSNet|NLP|ollama|LLM|Q-table|qtable|sqlite-vec|sqlite-vss|skill:|Task '\w+|External override|pursuit failed|Drop sweep|block ID map entry|PlayerManager#",
    re.IGNORECASE,
)

# WARN noise that is usually not actionable for this project (still summarized/collapsed).
NOISE_WARN_RE = re.compile(
    r"Reference map 'iris\.|\[Iris Update Check\]|semantic version format|Type is (VERTEX|FRAGMENT)$|Unable to play empty soundEvent|PhantomReference",
    re.IGNORECASE,
)

SPAM_RULES: list[tuple[str, re.Pattern[str]]] = [
    # AI-Player movement step spam.
    ("movement", re.compile(r"^Movement (execute|choosing walk only):\s", re.IGNORECASE)),

    # Shaderpack/Iris/Sodium linking spam.
    ("shader_link", re.compile(r"^Info log when linking program containing\s", re.IGNORECASE)),
    ("shader_link", re.compile(r"^Program link log for\s", re.IGNORECASE)),
    ("shader_link", re.compile(r"^Type is (VERTEX|FRAGMENT)$", re.IGNORECASE)),

    ("atlas_created", re.compile(r"^Created:\s", re.IGNORECASE)),
    ("unifont_load", re.compile(r"^Found unifont_", re.IGNORECASE)),
    ("fakeplayer_inventory_save", re.compile(r"^Saved inventory for fakeplayer\s", re.IGNORECASE)),
    ("ubo_resize", re.compile(r"^Resizing Dynamic Transforms UBO,\s", re.IGNORECASE)),
]


def spam_category(rec: Record) -> Optional[str]:
    msg = rec.message or ""
    for name, rx in SPAM_RULES:
        if rx.search(msg):
            return name
    return None


def is_important(rec: Record, *, include_shaders: bool) -> bool:
    lvl = (rec.level or "").upper()

    cat = spam_category(rec)
    if include_shaders and cat == "shader_link":
        # Shader link logs are noise for AI debugging, but very useful for graphics debugging.
        # Keep them in the "with-shaders" output.
        return True

    # Hard keep for errors
    if lvl == "ERROR":
        return True

    # Anything that smells like our mod or AI systems
    if KEEP_MARKER_RE.search(rec.text()):
        return True

    # Keep warnings by default, except known-noise spam.
    if lvl == "WARN":
        if cat is not None:
            return False
        if NOISE_WARN_RE.search(rec.message or ""):
            return False
        return True

    return False


def transform_record(rec: Record) -> Record:
    """Optionally compress certain very large records (e.g., mod list)."""
    msg = rec.message or ""

    # Compress: "Loading N mods:" big mod list.
    if msg.startswith("Loading ") and " mods:" in msg and any(line.startswith("\t-") for line in rec.lines[1:]):
        keep_top = {
            "ai-player",
            "fabric-api",
            "fabricloader",
            "minecraft",
            "java",
            "iris",
            "sodium",
            "lithium",
            "carpet",
            "distanthorizons",
        }

        new_lines: list[str] = [rec.lines[0]]
        trimmed_top = 0
        trimmed_deps = 0

        current_top: Optional[str] = None
        keep_all_until_next_top = False

        top_rx = re.compile(r"^\t-\s+([^\s]+)\s")

        for line in rec.lines[1:]:
            m = top_rx.match(line)
            if m:
                current_top = m.group(1)
                keep_all_until_next_top = current_top == "ai-player"
                if current_top in keep_top:
                    new_lines.append(line)
                else:
                    trimmed_top += 1
                continue

            # dependency line (indented under the last top-level mod)
            if keep_all_until_next_top:
                new_lines.append(line)
            else:
                trimmed_deps += 1

        if trimmed_top or trimmed_deps:
            new_lines.append(f"\t... [trimmed {trimmed_top} other mods and {trimmed_deps} dependency lines] ...")

        return dataclasses.replace(rec, lines=new_lines)

    # Compress: "Reloading ResourceManager: ..." long list.
    if msg.startswith("Reloading ResourceManager:"):
        # Keep vanilla/fabric/ai-player and a small tail.
        parts = msg.split(":", 1)
        head = parts[0] + ":"
        items = [s.strip() for s in parts[1].split(",") if s.strip()]
        keep = []
        for wanted in ("vanilla", "fabric", "ai-player"):
            if wanted in items and wanted not in keep:
                keep.append(wanted)
        # Add a few more (stable, useful suspects)
        for wanted in ("fabric-api", "fabricloader", "sodium", "iris", "lithium", "carpet", "distanthorizons"):
            if wanted in items and wanted not in keep:
                keep.append(wanted)
        extra = max(0, len(items) - len(keep))
        compressed = head + " " + ", ".join(keep)
        if extra:
            compressed += f", ... [+{extra} more]"
        # Rewrite the first line, preserving its prefix.
        if "]:" in rec.lines[0]:
            prefix = rec.lines[0].split("]:", 1)[0] + "]:"
            new_first = prefix + " " + compressed
        else:
            new_first = compressed
        return dataclasses.replace(rec, lines=[new_first])

    return rec


def write_collapse_marker(out, run: CollapsedRun) -> None:
    if run.record_count <= 0:
        return
    ts_part = ""
    if run.start_ts or run.end_ts:
        ts_part = f" (from {run.start_ts or '?'} to {run.end_ts or '?'})"
    out.write(f"[TRIMMED] collapsed {run.record_count} records in category '{run.category}'{ts_part}\n")


def write_gap_marker(out, count: int) -> None:
    if count <= 0:
        return
    out.write(f"[TRIMMED] removed {count} non-essential records\n")


def trim_log(
    input_path: Path,
    output_path: Path,
    *,
    context_before: int,
    context_after: int,
    keep_head_records: int,
    include_shaders: bool,
) -> dict:
    output_path.parent.mkdir(parents=True, exist_ok=True)

    tmp_path = output_path.with_suffix(output_path.suffix + ".tmp")

    stats = {
        "input_path": str(input_path),
        "output_path": str(output_path),
        "total_records": 0,
        "kept_records": 0,
        "dropped_records": 0,
        "collapsed_by_category": Counter(),
        "removed_nonessential": 0,
    }

    # Avoid spamming the trimmed log with tiny "gap" markers.
    min_gap_marker = 15

    pre_buffer: Deque[Record] = deque(maxlen=max(0, context_before))
    post_remaining = 0

    pending_gap = 0
    open_run: Optional[CollapsedRun] = None

    def flush_open_run(out):
        nonlocal open_run
        if open_run is not None:
            write_collapse_marker(out, open_run)
            stats["collapsed_by_category"][open_run.category] += open_run.record_count
            stats["dropped_records"] += open_run.record_count
            open_run = None

    def start_or_extend_run(cat: str, rec: Record):
        nonlocal open_run
        if open_run is None:
            open_run = CollapsedRun(category=cat, start_ts=rec.timestamp, end_ts=rec.timestamp, record_count=1)
            return
        if open_run.category != cat:
            raise RuntimeError("start_or_extend_run called without category switch handling")
        open_run.end_ts = rec.timestamp
        open_run.record_count += 1

    def emit_record(out, rec: Record):
        stats["kept_records"] += 1
        rec = transform_record(rec)
        for ln in rec.lines:
            out.write(ln + "\n")

    with tmp_path.open("w", encoding="utf-8", errors="replace") as out:
        for idx, rec in enumerate(iter_records(input_path)):
            stats["total_records"] += 1

            cat = spam_category(rec)
            important = is_important(rec, include_shaders=include_shaders)

            # In the with-shaders variant, do not collapse shader records.
            collapse_cat = cat
            if include_shaders and cat == "shader_link":
                collapse_cat = None

            # Always keep first N records (startup context), but never force-keep known spam.
            force_keep = idx < keep_head_records and collapse_cat is None

            # Determine if this record should be kept due to context.
            in_post = post_remaining > 0

            if force_keep or important:
                # flush buffered context
                flush_open_run(out)
                if pending_gap:
                    if pending_gap >= min_gap_marker:
                        write_gap_marker(out, pending_gap)
                    stats["removed_nonessential"] += pending_gap
                    stats["dropped_records"] += pending_gap
                    pending_gap = 0

                while pre_buffer:
                    emit_record(out, pre_buffer.popleft())

                emit_record(out, rec)
                post_remaining = context_after
                continue

            if in_post:
                # In after-context mode: keep some records for narrative,
                # but still collapse known spam categories.
                if collapse_cat is not None:
                    if open_run is not None and open_run.category != collapse_cat:
                        flush_open_run(out)
                    start_or_extend_run(collapse_cat, rec)
                    post_remaining -= 1
                    continue

                flush_open_run(out)
                if pending_gap:
                    if pending_gap >= min_gap_marker:
                        write_gap_marker(out, pending_gap)
                    stats["removed_nonessential"] += pending_gap
                    stats["dropped_records"] += pending_gap
                    pending_gap = 0

                emit_record(out, rec)
                post_remaining -= 1
                continue

            # Not kept: buffer for possible future "before" context, or collapse.
            if collapse_cat is not None:
                # If the spam category changes, flush the previous collapsed run first.
                if open_run is not None and open_run.category != collapse_cat:
                    flush_open_run(out)
                start_or_extend_run(collapse_cat, rec)
            else:
                # Candidate for before-context. Only count it as removed if it gets evicted.
                if pre_buffer.maxlen and len(pre_buffer) == pre_buffer.maxlen:
                    pending_gap += 1
                pre_buffer.append(rec)

        # end for

        # At EOF, avoid dumping huge trailing buffers; just flush collapse run.
        flush_open_run(out)

        # Any remaining pending gap corresponds to evicted, non-essential records.
        if pending_gap:
            stats["removed_nonessential"] += pending_gap
            stats["dropped_records"] += pending_gap
            pending_gap = 0

        # Any remaining buffered records were never emitted.
        if pre_buffer:
            stats["dropped_records"] += len(pre_buffer)
            pre_buffer.clear()

    # Build final output with a header.
    now = _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with output_path.open("w", encoding="utf-8", errors="replace") as final_out:
        final_out.write("# AI-Player logtrim output\n")
        final_out.write(f"# Generated: {now}\n")
        final_out.write(f"# Input: {input_path}\n")
        final_out.write(f"# Output: {output_path}\n")
        final_out.write(f"# Total records: {stats['total_records']}\n")
        final_out.write(f"# Kept records: {stats['kept_records']}\n")
        final_out.write(f"# Dropped records (incl. collapsed/markers): {stats['dropped_records']}\n")
        if stats["collapsed_by_category"]:
            final_out.write("# Collapsed categories:\n")
            for cat_name, cnt in stats["collapsed_by_category"].most_common():
                final_out.write(f"#   - {cat_name}: {cnt}\n")
        if stats["removed_nonessential"]:
            final_out.write(f"# Removed non-essential records: {stats['removed_nonessential']}\n")
        final_out.write("\n")

        with tmp_path.open("r", encoding="utf-8", errors="replace") as tmp_in:
            shutil.copyfileobj(tmp_in, final_out)

    try:
        tmp_path.unlink()
    except OSError:
        pass

    return stats


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    here = Path(__file__).resolve()
    repo_root = find_repo_root(here)

    default_repo_input = repo_root / "Logs-Prism" / "latest.log"
    prism_input = find_prism_latest_log()
    default_input = prism_input if prism_input is not None else default_repo_input
    default_output_dir = repo_root / "Logs-Prism" / "trimmed"

    p = argparse.ArgumentParser(description="Trim Minecraft/Fabric latest.log for AI-Player debugging")
    p.add_argument(
        "--input",
        "-i",
        type=Path,
        default=default_input,
        help=(
            "Path to input latest.log. Defaults to the most recent PrismLauncher latest.log if found; "
            "otherwise uses Logs-Prism/latest.log in this repo."
        ),
    )
    p.add_argument(
        "--output",
        "-o",
        type=Path,
        default=default_output_dir / "latest.trimmed.log",
        help=(
            "Output file path for the no-shaders variant. The with-shaders variant will be written next to it "
            "with '.with-shaders' inserted before the extension unless overridden by --output-with-shaders."
        ),
    )
    p.add_argument(
        "--output-with-shaders",
        type=Path,
        default=None,
        help="Optional explicit output path for the with-shaders variant.",
    )
    p.add_argument(
        "--watch",
        action="store_true",
        help=(
            "Watch the input log and re-run trimming whenever it changes. Useful if you frequently replace latest.log. "
            "(Polling-based; works without extra dependencies.)"
        ),
    )
    p.add_argument(
        "--watch-prism-all",
        action="store_true",
        help=(
            "Watch all PrismLauncher instances' latest.log files and write outputs into each instance's "
            "minecraft/logs/trimmed/ folder (polling-based)."
        ),
    )
    p.add_argument(
        "--prism-all-once",
        action="store_true",
        help=(
            "Trim latest.log for all PrismLauncher instances once and exit. Outputs are written into each "
            "instance's minecraft/logs/trimmed/ folder."
        ),
    )
    p.add_argument(
        "--prism-instances-root",
        type=Path,
        default=None,
        help=(
            "Override Prism instances root (default on macOS: ~/Library/Application Support/PrismLauncher/instances)."
        ),
    )
    p.add_argument(
        "--multi-output-root",
        type=Path,
        default=None,
        help=(
            "Where to write outputs for --watch-prism-all / --prism-all-once. Defaults to this repo's Logs-Prism folder. "
            "(Outputs go under <multi-output-root>/instances/<instance>/trimmed/.)"
        ),
    )
    p.add_argument(
        "--poll-interval",
        type=float,
        default=1.0,
        help="Polling interval in seconds for --watch (default: 1.0).",
    )
    p.add_argument(
        "--settle-time",
        type=float,
        default=0.75,
        help="Seconds the file must remain unchanged before trimming after a change (default: 0.75).",
    )
    p.add_argument("--context-before", type=int, default=2, help="Records kept before an important record")
    p.add_argument("--context-after", type=int, default=4, help="Records kept after an important record")
    p.add_argument("--keep-head-records", type=int, default=30, help="Keep the first N records unconditionally")
    return p.parse_args(argv)


def _file_signature(path: Path) -> Optional[tuple[int, int]]:
    """Return a signature that changes when file content is likely to change.

    Returns (mtime_ns, size) or None if file does not exist.
    """
    try:
        st = path.stat()
    except FileNotFoundError:
        return None
    return (st.st_mtime_ns, st.st_size)


def _derive_with_shaders_path(output_no_shaders: Path) -> Path:
    suffix = output_no_shaders.suffix or ".log"
    name = output_no_shaders.name
    if suffix and name.endswith(suffix):
        base = name[: -len(suffix)]
    else:
        base = output_no_shaders.stem
    return output_no_shaders.with_name(base + ".with-shaders" + suffix)


def run_trim_both(
    *,
    input_path: Path,
    output_no_shaders: Path,
    output_with_shaders: Path,
    context_before: int,
    context_after: int,
    keep_head_records: int,
) -> tuple[dict, dict]:
    stats_no = trim_log(
        input_path,
        output_no_shaders,
        context_before=context_before,
        context_after=context_after,
        keep_head_records=keep_head_records,
        include_shaders=False,
    )
    stats_yes = trim_log(
        input_path,
        output_with_shaders,
        context_before=context_before,
        context_after=context_after,
        keep_head_records=keep_head_records,
        include_shaders=True,
    )
    return stats_no, stats_yes


def watch_and_trim(
    *,
    input_path: Path,
    output_no_shaders: Path,
    output_with_shaders: Path,
    context_before: int,
    context_after: int,
    keep_head_records: int,
    poll_interval: float,
    settle_time: float,
) -> int:
    poll_interval = max(0.1, poll_interval)
    settle_time = max(0.0, settle_time)

    print(f"Watching: {input_path}")
    print(f"  -> no-shaders: {output_no_shaders}")
    print(f"  -> with-shaders: {output_with_shaders}")

    last_sig = _file_signature(input_path)
    if last_sig is not None:
        # Run once immediately on startup (common expectation for a watch mode).
        try:
            run_trim_both(
                input_path=input_path,
                output_no_shaders=output_no_shaders,
                output_with_shaders=output_with_shaders,
                context_before=context_before,
                context_after=context_after,
                keep_head_records=keep_head_records,
            )
            print("Initial trim complete.")
        except Exception as e:
            print(f"Initial trim failed: {e}")

    pending_change_since: Optional[float] = None
    pending_sig: Optional[tuple[int, int]] = None

    while True:
        sig = _file_signature(input_path)

        # Detect change (including file appearing/disappearing/replacement).
        if sig != last_sig:
            pending_change_since = time.time()
            pending_sig = sig
            last_sig = sig

        # If we saw a change, wait until it settles.
        if pending_change_since is not None:
            elapsed = time.time() - pending_change_since
            if elapsed >= settle_time:
                # Ensure it stayed stable for at least the settle window.
                current = _file_signature(input_path)
                if current == pending_sig and current is not None:
                    print(f"Change detected; trimming at {input_path}...")
                    try:
                        stats_no, stats_yes = run_trim_both(
                            input_path=input_path,
                            output_no_shaders=output_no_shaders,
                            output_with_shaders=output_with_shaders,
                            context_before=context_before,
                            context_after=context_after,
                            keep_head_records=keep_head_records,
                        )
                        print(
                            "Trim complete: "
                            f"no-shaders kept={stats_no['kept_records']} dropped={stats_no['dropped_records']}; "
                            f"with-shaders kept={stats_yes['kept_records']} dropped={stats_yes['dropped_records']}"
                        )
                    except Exception as e:
                        print(f"Trim failed: {e}")
                    pending_change_since = None
                    pending_sig = None

        time.sleep(poll_interval)


def watch_prism_instances(
    *,
    instances_root: Path,
    output_root: Path,
    context_before: int,
    context_after: int,
    keep_head_records: int,
    poll_interval: float,
    settle_time: float,
) -> int:
    """Watch all Prism instances' latest.log files and trim them into repo per-instance folders."""

    poll_interval = max(0.1, poll_interval)
    settle_time = max(0.0, settle_time)

    output_root.mkdir(parents=True, exist_ok=True)

    print(f"Watching Prism instances under: {instances_root}")
    print("  Looking for: <instance>/minecraft/logs/latest.log")
    print(f"  Writing outputs under: {output_root}/instances/<instance>/trimmed/")

    # Track signatures per file.
    last_sig: dict[Path, Optional[tuple[int, int]]] = {}
    pending_since: dict[Path, float] = {}
    pending_sig: dict[Path, Optional[tuple[int, int]]] = {}

    def output_paths_for(*, instance_name: str) -> tuple[Path, Path]:
        base_dir = _instance_output_dir(output_root=output_root, instance_name=instance_name)
        out_dir = base_dir / "trimmed"
        out_dir.mkdir(parents=True, exist_ok=True)
        out_no = out_dir / "latest.trimmed.log"
        out_yes = out_dir / "latest.trimmed.with-shaders.log"
        return out_no, out_yes

    while True:
        # Discover current logs (instances can appear/disappear while we're running).
        discovered = list(iter_prism_instance_latest_logs(instances_root))
        current_logs: dict[Path, str] = {lp: inst_name for inst_name, lp in discovered}

        # Remove state for logs that no longer exist.
        for tracked in list(last_sig.keys()):
            if tracked not in current_logs:
                last_sig.pop(tracked, None)
                pending_since.pop(tracked, None)
                pending_sig.pop(tracked, None)

        # Ensure every discovered log has baseline state.
        for log_path, inst_name in current_logs.items():
            if log_path not in last_sig:
                last_sig[log_path] = _file_signature(log_path)
                # Initial trim so per-instance trimmed folder is present.
                try:
                    out_no, out_yes = output_paths_for(instance_name=inst_name)
                    stats_no, stats_yes = run_trim_both(
                        input_path=log_path,
                        output_no_shaders=out_no,
                        output_with_shaders=out_yes,
                        context_before=context_before,
                        context_after=context_after,
                        keep_head_records=keep_head_records,
                    )
                    print(
                        f"Initial trim: {inst_name} -> kept={stats_no['kept_records']} (no-shaders), "
                        f"kept={stats_yes['kept_records']} (with-shaders)"
                    )
                except Exception as e:
                    print(f"Initial trim failed for {log_path}: {e}")

        # Poll for changes.
        for log_path in sorted(current_logs.keys(), key=lambda p: str(p)):
            sig = _file_signature(log_path)
            if sig != last_sig.get(log_path):
                last_sig[log_path] = sig
                pending_since[log_path] = time.time()
                pending_sig[log_path] = sig

            if log_path in pending_since:
                if (time.time() - pending_since[log_path]) >= settle_time:
                    current = _file_signature(log_path)
                    if current == pending_sig.get(log_path) and current is not None:
                        inst_name = current_logs.get(log_path, "(unknown)")
                        out_no, out_yes = output_paths_for(instance_name=inst_name)
                        print(f"Change detected; trimming {inst_name} ({log_path})...")
                        try:
                            stats_no, stats_yes = run_trim_both(
                                input_path=log_path,
                                output_no_shaders=out_no,
                                output_with_shaders=out_yes,
                                context_before=context_before,
                                context_after=context_after,
                                keep_head_records=keep_head_records,
                            )
                            print(
                                "Trim complete: "
                                f"{out_no} (kept={stats_no['kept_records']}), "
                                f"{out_yes} (kept={stats_yes['kept_records']})"
                            )
                        except Exception as e:
                            print(f"Trim failed for {log_path}: {e}")

                        pending_since.pop(log_path, None)
                        pending_sig.pop(log_path, None)

        time.sleep(poll_interval)


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)

    special_modes = sum(
        1
        for v in (
            bool(args.watch),
            bool(args.watch_prism_all),
            bool(args.prism_all_once),
        )
        if v
    )
    if special_modes > 1:
        raise SystemExit("Choose only one of: --watch, --watch-prism-all, --prism-all-once")

    if args.watch_prism_all or args.prism_all_once:
        root = args.prism_instances_root or find_prism_instances_root()
        if root is None or not root.exists():
            raise SystemExit("Prism instances root not found. Use --prism-instances-root to specify it.")

        output_root = args.multi_output_root or _default_multi_output_root()

        if args.prism_all_once:
            return trim_prism_instances_once(
                instances_root=root,
                output_root=output_root,
                context_before=max(0, args.context_before),
                context_after=max(0, args.context_after),
                keep_head_records=max(0, args.keep_head_records),
            )

        return watch_prism_instances(
            instances_root=root,
            output_root=output_root,
            context_before=max(0, args.context_before),
            context_after=max(0, args.context_after),
            keep_head_records=max(0, args.keep_head_records),
            poll_interval=float(args.poll_interval),
            settle_time=float(args.settle_time),
        )

    input_path: Path = args.input
    output_no_shaders: Path = args.output
    if args.output_with_shaders is not None:
        output_with_shaders: Path = args.output_with_shaders
    else:
        output_with_shaders = _derive_with_shaders_path(output_no_shaders)

    if not args.watch and not input_path.exists():
        raise SystemExit(f"Input log not found: {input_path}")

    if args.watch:
        return watch_and_trim(
            input_path=input_path,
            output_no_shaders=output_no_shaders,
            output_with_shaders=output_with_shaders,
            context_before=max(0, args.context_before),
            context_after=max(0, args.context_after),
            keep_head_records=max(0, args.keep_head_records),
            poll_interval=float(args.poll_interval),
            settle_time=float(args.settle_time),
        )

    stats_no, stats_yes = run_trim_both(
        input_path=input_path,
        output_no_shaders=output_no_shaders,
        output_with_shaders=output_with_shaders,
        context_before=max(0, args.context_before),
        context_after=max(0, args.context_after),
        keep_head_records=max(0, args.keep_head_records),
    )

    # Minimal console summary.
    print(f"Wrote (no-shaders): {stats_no['output_path']}")
    print(f"  Total records: {stats_no['total_records']}, kept: {stats_no['kept_records']}, dropped: {stats_no['dropped_records']}")
    if stats_no["collapsed_by_category"]:
        top = ", ".join(f"{k}={v}" for k, v in stats_no["collapsed_by_category"].most_common(6))
        print(f"  Collapsed: {top}")

    print(f"Wrote (with-shaders): {stats_yes['output_path']}")
    print(f"  Total records: {stats_yes['total_records']}, kept: {stats_yes['kept_records']}, dropped: {stats_yes['dropped_records']}")
    if stats_yes["collapsed_by_category"]:
        top = ", ".join(f"{k}={v}" for k, v in stats_yes["collapsed_by_category"].most_common(6))
        print(f"  Collapsed: {top}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
