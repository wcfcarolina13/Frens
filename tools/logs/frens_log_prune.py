#!/usr/bin/env python3
"""Digest -> archive -> prune old Frens Minecraft logs and screenshots.

For every configured Prism instance (tools/logs/prune_config.json):
  * rotated logs in minecraft/logs/ (*.log.gz, *.log except latest.log) older than
    logs.archive_after_days: run frens_log_report on them, write the digest
    (<archive>/<instance>/digests/<name>.md + .json, kept forever), then move the
    raw file to <archive>/<instance>/raw/.
  * raw archive files older than logs.delete_raw_after_days: delete.
  * screenshots (only where configured): move files older than archive_after_days
    to <archive>/<instance>/<dir>/, delete archived ones older than delete_after_days.

Safety: dry-run unless --apply; never latest.log, anything modified in the last
min_age_hours (24 h), symlinks, or anything outside the configured instance dirs
and the archive root; crash-reports/, saves/, mods/, config/ are refused outright;
a log whose digest fails is neither moved nor deleted. Exit 1 on any error.

Python 3.9 (launchd runs /usr/bin/python3 3.9.6), stdlib only.
"""
import argparse
import json
import os
import shutil
import sys
import time
from typing import Dict, List

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import frens_log_report  # noqa: E402

DAY = 86400.0
FORBIDDEN_PARTS = frozenset(["crash-reports", "saves", "mods", "config", "defaultconfigs", "backups", "world"])
SCREENSHOT_EXT = (".png", ".jpg", ".jpeg")
LIST_LIMIT = 10


class Refused(Exception):
    pass


def expand(p):
    # type: (str) -> str
    return os.path.abspath(os.path.expanduser(os.path.expandvars(p)))


def load_config(path):
    with open(path) as fh:
        return json.load(fh)


class Guard(object):
    """Path containment: realpath inside an allowed root, never a symlink, never a forbidden subtree."""

    def __init__(self, instance_dirs, archive_root):
        # type: (Dict[str, str], str) -> None
        self.instance_dirs = dict((k, os.path.realpath(v)) for k, v in instance_dirs.items())
        self.archive_root = os.path.realpath(archive_root)
        for name, d in self.instance_dirs.items():
            if _inside(self.archive_root, d) or _inside(d, self.archive_root):
                raise Refused("archive root %s overlaps instance %s (%s)" % (self.archive_root, name, d))

    def check(self, path):
        # type: (str) -> str
        if os.path.islink(path):
            raise Refused("symlink: %s" % path)
        real = os.path.realpath(path)
        roots = list(self.instance_dirs.values()) + [self.archive_root]
        root = next((r for r in roots if _inside(real, r)), None)
        if root is None:
            raise Refused("outside configured roots: %s" % path)
        if root != self.archive_root:
            rel = os.path.relpath(real, root).split(os.sep)
            if rel and rel[0] in FORBIDDEN_PARTS:
                raise Refused("forbidden subtree %s: %s" % (rel[0], path))
            if os.path.basename(real) == "latest.log":
                raise Refused("latest.log is never touched: %s" % path)
        return real


def _inside(path, root):
    # type: (str, str) -> bool
    try:
        return os.path.commonpath([path, root]) == root
    except ValueError:
        return False


def _files(d):
    """Regular files directly in d, never following symlinks. Missing dir -> []."""
    out = []
    if not os.path.isdir(d) or os.path.islink(d):
        return out
    for e in os.scandir(d):
        if e.is_symlink():
            out.append((e.path, None))  # reported as refused
            continue
        if e.is_file(follow_symlinks=False):
            st = e.stat(follow_symlinks=False)
            out.append((e.path, st))
    return sorted(out)


def _is_rotated_log(name):
    return name != "latest.log" and (name.endswith(".log.gz") or name.endswith(".log"))


def plan(config, now=None):
    """Return (actions, refusals). Actions: dicts with op in archive_log|delete|move."""
    now = time.time() if now is None else now
    min_age = float(config.get("min_age_hours", 24)) * 3600.0
    inst_root = expand(config["instances_root"])
    archive_root = expand(config["archive_root"])
    inst_dirs = {}
    for name in config["instances"]:
        inst_dirs[name] = os.path.join(inst_root, name, "minecraft")
    guard = Guard(inst_dirs, archive_root)
    archive_root = guard.archive_root  # build destinations from the resolved root
    actions = []  # type: List[dict]
    refusals = []  # type: List[str]

    def age_ok(st, days):
        age = now - st.st_mtime
        return age >= min_age and age >= days * DAY

    for name, conf in sorted(config["instances"].items()):
        mc = inst_dirs[name]
        arch = os.path.join(archive_root, name)
        if not os.path.isdir(mc):
            refusals.append("%s: instance dir missing (%s), skipped" % (name, mc))
            continue
        logs = conf.get("logs")
        if logs:
            for path, st in _files(os.path.join(mc, "logs")):
                base = os.path.basename(path)
                if not _is_rotated_log(base):
                    continue
                if st is None:
                    refusals.append("%s: symlink skipped: %s" % (name, path))
                    continue
                if not age_ok(st, float(logs["archive_after_days"])):
                    continue
                try:
                    guard.check(path)
                except Refused as e:
                    refusals.append("%s: %s" % (name, e))
                    continue
                actions.append({"op": "archive_log", "instance": name, "src": path, "bytes": st.st_size,
                                "dest": os.path.join(arch, "raw", base),
                                "digest": os.path.join(arch, "digests", base)})
            for path, st in _files(os.path.join(arch, "raw")):
                if st is not None and age_ok(st, float(logs["delete_raw_after_days"])):
                    actions.append({"op": "delete", "instance": name, "kind": "raw log", "src": path,
                                    "bytes": st.st_size})
        shots = conf.get("screenshots")
        if shots:
            for rel in shots.get("dirs", ["screenshots"]):
                rel_parts = [p for p in rel.split("/") if p]
                src_dir = os.path.join(mc, *rel_parts)
                dst_dir = os.path.join(arch, *rel_parts)
                for path, st in _files(src_dir):
                    if not path.lower().endswith(SCREENSHOT_EXT):
                        continue
                    if st is None:
                        refusals.append("%s: symlink skipped: %s" % (name, path))
                        continue
                    if not age_ok(st, float(shots["archive_after_days"])):
                        continue
                    try:
                        guard.check(path)
                    except Refused as e:
                        refusals.append("%s: %s" % (name, e))
                        continue
                    actions.append({"op": "move", "instance": name, "kind": "screenshot", "src": path,
                                    "dest": os.path.join(dst_dir, os.path.basename(path)), "bytes": st.st_size})
                for path, st in _files(dst_dir):
                    if st is not None and age_ok(st, float(shots["delete_after_days"])):
                        actions.append({"op": "delete", "instance": name, "kind": "screenshot", "src": path,
                                        "bytes": st.st_size})
    return actions, refusals, guard


def write_digest(src, digest_base):
    rep = frens_log_report.analyze([src])
    if any(f.get("error") for f in rep["files"]):
        raise RuntimeError("analyzer could not read %s" % src)
    md = frens_log_report.render_md(rep)
    tmp_md, tmp_js = digest_base + ".md.tmp", digest_base + ".json.tmp"
    with open(tmp_md, "w") as fh:
        fh.write(md)
    with open(tmp_js, "w") as fh:
        json.dump(rep, fh, indent=1, default=str)
    os.replace(tmp_md, digest_base + ".md")
    os.replace(tmp_js, digest_base + ".json")


def execute(actions, guard, apply, out=None):
    """Run (or just print) the actions. Returns number of errors."""
    out = out or sys.stdout
    errors = 0
    for a in actions:
        try:
            src = guard.check(a["src"])
            for key in ("dest", "digest"):
                if key in a and not _inside(os.path.realpath(a[key]), guard.archive_root):
                    raise Refused("%s outside archive root: %s" % (key, a[key]))
            if "dest" in a:
                if os.path.lexists(a["dest"]):
                    raise Refused("destination exists, not overwriting: %s" % a["dest"])
            if not apply:
                continue
            if a["op"] == "archive_log":
                os.makedirs(os.path.dirname(a["digest"]), exist_ok=True)
                write_digest(src, a["digest"])
                os.makedirs(os.path.dirname(a["dest"]), exist_ok=True)
                shutil.move(src, a["dest"])
            elif a["op"] == "move":
                os.makedirs(os.path.dirname(a["dest"]), exist_ok=True)
                shutil.move(src, a["dest"])
            elif a["op"] == "delete":
                if not _inside(src, guard.archive_root):
                    raise Refused("delete outside archive root: %s" % src)
                os.remove(src)
            a["done"] = True
        except Exception as e:  # noqa: BLE001 - every failure is reported and counted
            errors += 1
            a["error"] = "%s: %s" % (type(e).__name__, e)
            out.write("ERROR %s %s: %s\n" % (a["op"], a["src"], a["error"]))
    return errors


def mb(n):
    return "%.1f MB" % (n / 1048576.0)


def print_plan(actions, refusals, apply, verbose, out=None):
    out = out or sys.stdout
    groups = {}  # type: Dict[tuple, List[dict]]
    for a in actions:
        groups.setdefault((a["instance"], a["op"], a.get("kind", "log")), []).append(a)
    out.write("%s plan\n" % ("APPLY" if apply else "DRY-RUN"))
    for key in sorted(groups):
        items = groups[key]
        out.write("%s: %s %s x%d (%s) from %s%s\n" % (
            key[0], key[1], key[2], len(items), mb(sum(i["bytes"] for i in items)), os.path.dirname(items[0]["src"]),
            (" to " + os.path.dirname(items[0]["dest"])) if "dest" in items[0] else ""))
        shown = items if verbose else items[:LIST_LIMIT]
        for i in shown:
            out.write("  %s\n" % os.path.basename(i["src"]))
        if len(items) > len(shown):
            out.write("  ... %d more (--verbose)\n" % (len(items) - len(shown)))
    for r in refusals:
        out.write("refused: %s\n" % r)


def summary(actions, refusals, errors, apply):
    n_arch = [a for a in actions if a["op"] == "archive_log"]
    n_move = [a for a in actions if a["op"] == "move"]
    n_del = [a for a in actions if a["op"] == "delete"]
    return ("%s: archive %d logs (%s, digests written first), move %d screenshots (%s), delete %d files "
            "(%s freed), refused %d, errors %d" % (
                "applied" if apply else "dry-run", len(n_arch), mb(sum(a["bytes"] for a in n_arch)), len(n_move),
                mb(sum(a["bytes"] for a in n_move)), len(n_del), mb(sum(a["bytes"] for a in n_del)), len(refusals),
                errors))


def main(argv=None):
    ap = argparse.ArgumentParser(description="Digest, archive and prune old Frens logs and screenshots.")
    ap.add_argument("--config", default=os.path.join(HERE, "prune_config.json"))
    ap.add_argument("--apply", action="store_true", help="act (default is a dry-run plan)")
    ap.add_argument("--verbose", action="store_true", help="list every file in the plan")
    args = ap.parse_args(argv)
    sys.stdout.write("frens_log_prune %s\n" % time.strftime("%Y-%m-%d %H:%M:%S"))
    try:
        config = load_config(args.config)
        actions, refusals, guard = plan(config)
    except (Refused, OSError, ValueError, KeyError) as e:
        sys.stdout.write("ERROR config/plan: %s\n" % e)
        return 1
    print_plan(actions, refusals, args.apply, args.verbose)
    errors = execute(actions, guard, args.apply)
    sys.stdout.write(summary(actions, refusals, errors, args.apply) + "\n")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
