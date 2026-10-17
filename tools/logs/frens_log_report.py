#!/usr/bin/env python3
"""Streaming analyzer for Frens (Minecraft Fabric mod) client logs.

Reads a Minecraft client log (plain or .gz, possibly still being written) line by
line and prints a short markdown digest: overview + spam, per-bot timelines,
pathfinding stats, PathCompare A/B pairs, and a per-bot A/B outcome table.

Python 3.9, stdlib only. See tools/logs/README.md.

    frens_log_report.py LOG [LOG ...] [--since HH:MM:SS] [--until HH:MM:SS]
                        [--bot NAME] [--json OUT] [--md OUT] [--follow SECS]
"""
import argparse
import gzip
import io
import json
import os
import re
import sys
import time
from collections import Counter, defaultdict
from typing import Dict, List, Optional, Tuple

# --------------------------------------------------------------------------- basics

HEADER_RE = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\] \[([^\]]*)/([A-Z]+)\]: ?(.*)$")
# Pools whose threads serve one bot at a time but many bots over a session.
SHARED_THREADS = ("Server thread", "Render thread", "main")
# The shadow PathCompare thread re-runs the other finder; its [BaritonePathFinder] / A* lines are
# already summarised by its [PathCompare] line, so they stay out of section 3's finder counts.
PATHCOMPARE_THREAD = "frens-pathcompare"

MASK_SUBS = [
    (re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "<uuid>"),
    (re.compile(r"class_\d+"), "class_N"),
    (re.compile(r"-?\d+(?:\.\d+)?"), "N"),
]
MASK_MAX = 140

POS_RE = r"(-?\d+), ?(-?\d+), ?(-?\d+)"
POS_ANY_RE = re.compile(
    r"\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}|\((-?\d+),(-?\d+),(-?\d+)\)|(-?\d+), (-?\d+), (-?\d+)")
BOTNAME = r"([A-Za-z][A-Za-z0-9_]{1,15})(?![-A-Za-z0-9_])"

# (trigger, regex) pairs that name a bot for certain; the human player never matches.
ROSTER_RES = [
    ("botName=", re.compile(r"spawnBot starting: botName=" + BOTNAME)),
    ("fakeplayer '", re.compile(r"fakeplayer '" + BOTNAME + "'")),
    ("for bot ", re.compile(r"for bot " + BOTNAME)),
    ("bot=", re.compile(r"\bbot=" + BOTNAME)),
]


def mask(msg):
    # type: (str) -> str
    for rx, rep in MASK_SUBS:
        msg = rx.sub(rep, msg)
    return msg[:MASK_MAX]


def norm_pos(text):
    # type: (str) -> Optional[str]
    m = POS_ANY_RE.search(text)
    if not m:
        return None
    g = [x for x in m.groups() if x is not None]
    return ",".join(g[:3])


def hms(secs):
    # type: (Optional[int]) -> str
    if secs is None:
        return "-"
    secs = int(secs)
    day, rem = divmod(secs, 86400)
    s = "%02d:%02d:%02d" % (rem // 3600, rem // 60 % 60, rem % 60)
    return ("d%d+" % day + s) if day else s


def parse_hms(text):
    # type: (str) -> int
    parts = [int(p) for p in text.split(":")]
    while len(parts) < 3:
        parts.append(0)
    return parts[0] * 3600 + parts[1] * 60 + parts[2]


def pct(values, p):
    # type: (List[float], float) -> Optional[float]
    if not values:
        return None
    v = sorted(values)
    k = min(len(v) - 1, max(0, int(round(p / 100.0 * (len(v) - 1)))))
    return v[k]


def median(values):
    return pct(values, 50)


def open_log(path):
    """Open plain or gzip log for text reading; tolerant of bad bytes."""
    if path.endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8", errors="replace")
    return open(path, "r", encoding="utf-8", errors="replace")


# --------------------------------------------------------------------------- patterns
# Each pattern: (trigger substring, compiled regex, handler name). The trigger is a
# cheap `in` check run before the regex; that is what keeps a 50 MB log fast.
# To add a pattern: append a tuple and write the `_h_<name>` method on Analyzer.

PATTERNS = [
    ("Skill '", re.compile(r"Skill '([^']+)' starting for bot " + BOTNAME), "skill_start"),
    ("Skill '", re.compile(r"Skill '([^']+)' finished for bot " + BOTNAME + r": success=(\w+) msg='(.*)'"),
     "skill_finish"),
    ("[CHAT]", re.compile(r"\[CHAT\] (.*)$"), "chat"),
    ("[PathFinder] mode=", re.compile(
        r"\[PathFinder\] mode=(\w+) elapsed=(\d+)ms pathLen=(\d+)(?: from=" + POS_RE + r")?(?: to=" + POS_RE + r")?"
        r"(?: plan=(\S+))?(?: status=(\w+) reason=(\w+))?"),
     "pathfinder"),
    ("A* pathfinding timed out", re.compile(r"A\* pathfinding timed out after (\d+)ms"), "astar_timeout"),
    ("[BaritonePathFinder]", re.compile(
        r"\[BaritonePathFinder\] (Goal reached!|Timeout after|Using best-so-far|Open set exhausted|No best-so-far)"),
     "baritone_event"),
    ("pursuit failed", re.compile(r"pursuit failed \[([^\]]+)\]"), "walk_pursuit"),
    ("walk blocked near", re.compile(r"walk blocked near"), "walk_blocked"),
    ("no walkable path", re.compile(r"no walkable path"), "walk_nopath"),
    ("walk blocked after replans", re.compile(r"walk blocked after replans"), "walk_replans"),
    ("movement local escape", re.compile(
        r"movement local escape \[([^\]]+)\]:.*?bot=" + BOTNAME + r".*?route=(.*?) stepUp="), "local_escape"),
    ("terrain move:", re.compile(r"terrain move: (BREAK|PLACE|WALK_AROUND)\b(?:.*?cost=(-?[\d.]+))?"),
     "terrain_move"),
    ("movement obstruction mine", re.compile(r"movement obstruction mine( refused)?"), "obstruction"),
    ("Stuck-escape backoff", re.compile(r"Stuck-escape backoff(?!: owner)(?! cleared)"), "stuck"),
    ("ensureAtSurface: ", re.compile(r"ensureAtSurface: " + BOTNAME + r" failed at " + POS_RE), "stuck_surface"),
    ("holding still after a stop", re.compile(r"holding still after a stop"), "stuck"),
    ("trapped on join", re.compile(r"trapped on join"), "stuck"),
    # Wall-clock stuck lines that name the bot (worker or Server thread, so no thread binding).
    ("stuck for ", re.compile(r"(?:ReturnBaseStuck: bot=|Bot )" + BOTNAME + r" stuck for (\d+) ticks"), "stuck_named"),
    ("attempting escape movement", re.compile(r"Bot " + BOTNAME + r" attempting escape movement toward (\w+)"),
     "stuck_named"),
    # The integrated server paused (pause menu): bot workers keep logging failures on wall-clock time.
    ("Saving and pausing game", re.compile(r"Saving and pausing game"), "server_paused"),
    ("Woodcut: selected tree", re.compile(r"Woodcut: selected tree target base=" + POS_RE), "wc_selected"),
    ("Woodcut approach: abandoned", re.compile(
        r"Woodcut approach: abandoned (dry stand )?" + POS_RE + r".*?\(reason=([^)]*?)(?: detail=|\))"),
     "wc_abandoned"),
    ("skipping remaining stands", re.compile(r"skipping remaining stands for " + POS_RE), "wc_skip"),
    ("Woodcut unified: mined", re.compile(r"Woodcut unified: mined (\d+) logs for base " + POS_RE), "wc_mined"),
    ("woodcut_cleanup completed", re.compile(r"minedLogs=(\d+)"), "wc_cleanup_mined"),
    ("Woodcut: trapped", re.compile(r"Woodcut: trapped (exit|at)"), "wc_trapped"),
    ("[PathCompare]", re.compile(r"\[PathCompare\] (.*)$"), "pathcompare"),
    # Logged when a planned walk ends. Its own trigger: "[PathCompare]" is not a substring of it.
    ("[PathCompareOutcome]", re.compile(
        r"\[PathCompareOutcome\] bot=(\S+) to=(-?\d+,-?\d+,-?\d+) reached=(yes|no|n/a)(?: plan=(\S+))?"
        r"(?: detail=(\S*))?"),
     "pathcompare_outcome"),
    ("hp=", re.compile(r"\bhp=([\d.]+)"), "vital_hp"),
    ("food=", re.compile(r"\bfood=(\d+)"), "vital_food"),
    ("pos=", re.compile(r"\bpos=" + POS_RE), "position"),
]

DEATH_RE = re.compile(
    r"^" + BOTNAME + r" (was slain|was blown up|was shot|was killed|was fireballed|was pricked|was squashed|"
    r"was impaled|was struck|was stung|was poked|was obliterated|was skewered|died|drowned|fell|burned|"
    r"starved|suffocated|hit the ground|went up in flames|went off with a bang|tried to swim|walked into|"
    r"froze|experienced kinetic|withered|blew up|discovered the floor)")
HP_ALL_RE = re.compile(r"\bhp=([\d.]+)")
FOOD_ALL_RE = re.compile(r"\bfood=(\d+)")
NOT_NAMES = frozenset(["null", "none", "None", "self", "unknown"])
CHAT_SPEAKER_RE = re.compile(r"^<?" + BOTNAME + r">?:? (.*)$")
KV_RE = re.compile(r"(\w+)=(\S+)")
# Optional ":<REASON>" suffix (1.1.242): a unified plan's NoPathReason on NO_PATH or PARTIAL.
FINDER_VAL_RE = re.compile(r"^(-?\d+)ms/(-?\d+)/(\w+)(?::(\w+))?")

TIMELINE_KINDS_MAX = 40
STUCK_EPISODE_GAP_S = 120
# Timeline kinds whose consecutive repeats collapse even when coordinates differ.
MASKED_DEDUP_KINDS = frozenset(["STUCK", "TRAPPED"])


class Analyzer(object):
    def __init__(self, since=None, until=None, only_bot=None, spam_per_min=60):
        self.since = since
        self.until = until
        self.only_bot = only_bot
        self.spam_per_min = spam_per_min
        # time
        self.day = 0
        self.last_wall = None  # type: Optional[int]
        self.t = None  # type: Optional[int]
        self.first_t = None
        self.last_t = None
        # overview
        self.lines = 0
        self.unparsed = 0
        self.levels = Counter()
        self.warn_patterns = {}  # type: Dict[str, list]
        self.spam_cur_min = None
        self.spam_cur = Counter()
        self.spam = {}  # masked -> [peak, peak_minute_t, minutes_over, total_over]
        self.files = []  # type: List[dict]
        self.pauses = []  # type: List[int]
        # attribution
        self.roster = []  # type: List[str]
        self.mention_re = None
        self.thread_bot = {}  # type: Dict[str, str]
        self.explicit = False  # did the current line name its bot (vs. inherited thread binding)?
        # per bot
        self.bots = defaultdict(self._new_bot)  # type: Dict[str, dict]
        self.system_chat = Counter()
        self.player_deaths = []
        # pathfinding
        self.paths = defaultdict(lambda: {"elapsed": [], "len0": 0, "n": 0, "status": Counter(), "reason": Counter()})
        self.astar_timeouts = 0
        self.shadow_finder_lines = 0  # finder lines from the PathCompare thread, not counted above
        self.baritone = Counter()
        self.thread_mode = {}  # type: Dict[str, str]
        self.walk = defaultdict(Counter)  # mode -> outcome counter
        self.escapes = Counter()  # (bot, route) -> n
        self.terrain = defaultdict(list)  # decision -> costs
        self.obstruction = Counter()
        self.pathcompare = []  # type: List[dict]
        self.pc_outcomes = []  # type: List[dict]

    @staticmethod
    def _new_bot():
        return {
            "timeline": [], "chat": [], "chat_n": 0, "skills": Counter(), "skill_results": Counter(),
            "deaths": 0, "stuck": 0, "stuck_events": 0, "stuck_last_t": None, "logs_mined": 0, "targets_selected": 0, "targets_abandoned": 0,
            "stands_abandoned": 0, "stands_skipped": 0, "abandon_reasons": Counter(), "trapped_exits": 0,
            "hp_min": None, "hp_last": None, "food_min": None, "food_last": None,
            "pos_first": None, "pos_last": None, "modes": Counter(), "primary": Counter(),
            "path_ms": [], "path_len0": 0, "skill_open": None, "first_log_delays": [],
        }

    # ------------------------------------------------------------------ feeding
    def feed_file(self, path, start_offset=0):
        """Stream one file; returns byte offset reached (plain files only)."""
        info = {"path": path, "lines": 0, "truncated": False}
        self.files.append(info)
        try:
            if path.endswith(".gz"):
                fh = open_log(path)
                try:
                    for line in fh:
                        info["lines"] += 1
                        self.feed(line)
                except (EOFError, OSError):
                    info["truncated"] = True
                finally:
                    fh.close()
                return 0
            with open(path, "rb") as fb:
                fb.seek(start_offset)
                pos = start_offset
                for raw in fb:
                    if not raw.endswith(b"\n"):
                        break  # partial line of a file still being written: leave it for next read
                    pos += len(raw)
                    info["lines"] += 1
                    self.feed(raw.decode("utf-8", "replace"))
                return pos
        except IOError as e:
            info["error"] = str(e)
            return start_offset

    def _set_roster(self, name):
        if name in self.roster:
            return
        self.roster.append(name)
        self.mention_re = re.compile(r"\b(" + "|".join(re.escape(n) for n in self.roster) + r")\b")

    def _attribute(self, thread, msg):
        # type: (str, str) -> Optional[str]
        for trigger, rx in ROSTER_RES:
            if trigger in msg:
                m = rx.search(msg)
                if m and m.group(1) not in NOT_NAMES:
                    self._set_roster(m.group(1))
        named = None
        if self.mention_re is not None:
            found = set(self.mention_re.findall(msg))
            if len(found) == 1:
                named = found.pop()
        self.explicit = named is not None
        shared = thread in SHARED_THREADS
        if not shared:
            for b in self.roster:
                if thread.endswith("-" + b):
                    self.explicit = True
                    return b
            if named:
                self.thread_bot[thread] = named
                return named
            return self.thread_bot.get(thread)
        return named

    def feed(self, line):
        line = line.rstrip("\r\n")
        m = HEADER_RE.match(line)
        if not m:
            if line:
                self.unparsed += 1
            return
        hh, mm, ss, thread, level, msg = m.groups()
        wall = int(hh) * 3600 + int(mm) * 60 + int(ss)
        if self.last_wall is not None and wall < self.last_wall - 600:
            self.day += 1  # midnight wrap
        self.last_wall = wall
        t = self.day * 86400 + wall
        bot = self._attribute(thread, msg)
        if self.since is not None and wall < self.since:
            return
        if self.until is not None and wall > self.until:
            return
        self.t = t
        if self.first_t is None:
            self.first_t = t
        self.last_t = t
        self.lines += 1
        self.levels[level] += 1
        masked = mask(msg)
        if level in ("WARN", "ERROR"):
            key = level + " " + masked
            wp = self.warn_patterns.get(key)
            if wp is None:
                self.warn_patterns[key] = [1, t, t, msg[:200]]
            else:
                wp[0] += 1
                wp[2] = t
        self._spam(t, masked)
        if self.only_bot and bot and bot != self.only_bot:
            return
        thr_key = thread
        for trigger, rx, name in PATTERNS:
            if trigger in msg:
                mm2 = rx.search(msg)
                if mm2:
                    getattr(self, "_h_" + name)(mm2, t, thr_key, bot, msg, level)
        if thread == "Server thread":
            dm = DEATH_RE.match(msg)
            if dm:
                who = dm.group(1)
                if who in self.roster:
                    b = self.bots[who]
                    b["deaths"] += 1
                    self._tl(who, t, "DEATH", msg)
                else:
                    self.player_deaths.append((t, msg))

    def _spam(self, t, masked):
        minute = t // 60
        if minute != self.spam_cur_min:
            self._flush_spam()
            self.spam_cur_min = minute
        self.spam_cur[masked] += 1

    def _flush_spam(self):
        if self.spam_cur_min is None:
            return
        for k, n in self.spam_cur.items():
            if n > self.spam_per_min:
                s = self.spam.get(k)
                if s is None:
                    self.spam[k] = [n, self.spam_cur_min * 60, 1, n]
                else:
                    if n > s[0]:
                        s[0], s[1] = n, self.spam_cur_min * 60
                    s[2] += 1
                    s[3] += n
        self.spam_cur = Counter()

    def _tl(self, bot, t, kind, text):
        if bot is None:
            return
        tl = self.bots[bot]["timeline"]
        text = text[:160]
        key = mask(text) if kind in MASKED_DEDUP_KINDS else text
        if tl and tl[-1][1] == kind and tl[-1][3] == key:
            tl[-1][4] += 1
            return
        tl.append([t, kind, text, key, 1])

    # ------------------------------------------------------------------ handlers
    def _h_skill_start(self, m, t, thread, bot, msg, level):
        skill, who = m.group(1), m.group(2)
        b = self.bots[who]
        b["skills"][skill] += 1
        b["skill_open"] = (skill, t)
        self._tl(who, t, "SKILL+", skill)

    def _h_skill_finish(self, m, t, thread, bot, msg, level):
        skill, who, ok, text = m.group(1), m.group(2), m.group(3), m.group(4)
        b = self.bots[who]
        b["skill_results"]["%s %s" % (skill, "ok" if ok == "true" else "fail")] += 1
        b["skill_open"] = None
        self._tl(who, t, "SKILL-", "%s success=%s %s" % (skill, ok, text))

    def _h_chat(self, m, t, thread, bot, msg, level):
        text = m.group(1)
        sm = CHAT_SPEAKER_RE.match(text)
        who = None
        if sm and sm.group(1) in self.roster:
            who = sm.group(1)
        elif self.mention_re is not None:
            found = set(self.mention_re.findall(text))
            if len(found) == 1:
                who = found.pop()
        if who is None:
            self.system_chat[mask(text)] += 1
            return
        if DEATH_RE.match(text):
            return  # echo of a Server-thread death line, counted there
        if self.only_bot and who != self.only_bot:
            return
        b = self.bots[who]
        b["chat_n"] += 1
        b["chat"].append((t, text[:160]))
        if len(b["chat"]) > 50:
            del b["chat"][:25]

    def _h_pathfinder(self, m, t, thread, bot, msg, level):
        mode, ms, plen = m.group(1), int(m.group(2)), int(m.group(3))
        p = self.paths[mode]
        p["n"] += 1
        p["elapsed"].append(ms)
        if plen == 0:
            p["len0"] += 1
        if m.group(11):  # unified lines (1.1.242) carry the plan status and NO_PATH reason
            p["status"][m.group(11)] += 1
            p["reason"][m.group(12)] += 1
        self.thread_mode[thread] = mode
        if bot:
            b = self.bots[bot]
            b["modes"][mode] += 1
            b["path_ms"].append(ms)
            if plen == 0:
                b["path_len0"] += 1

    def _h_astar_timeout(self, m, t, thread, bot, msg, level):
        if thread == PATHCOMPARE_THREAD:
            self.shadow_finder_lines += 1
            return
        self.astar_timeouts += 1

    def _h_baritone_event(self, m, t, thread, bot, msg, level):
        if thread == PATHCOMPARE_THREAD:
            self.shadow_finder_lines += 1
            return
        self.baritone[m.group(1).rstrip("!")] += 1

    def _walk(self, thread, outcome):
        self.walk[self.thread_mode.get(thread, "unknown")][outcome] += 1

    def _h_walk_pursuit(self, m, t, thread, bot, msg, level):
        self._walk(thread, "pursuit failed")

    def _h_walk_blocked(self, m, t, thread, bot, msg, level):
        self._walk(thread, "walk blocked near")

    def _h_walk_nopath(self, m, t, thread, bot, msg, level):
        self._walk(thread, "no walkable path")

    def _h_walk_replans(self, m, t, thread, bot, msg, level):
        self._walk(thread, "walk blocked after replans")

    def _h_local_escape(self, m, t, thread, bot, msg, level):
        self.escapes[(m.group(2), m.group(3))] += 1

    def _h_terrain_move(self, m, t, thread, bot, msg, level):
        cost = m.group(2)
        self.terrain[m.group(1)].append(float(cost) if cost else None)

    def _h_obstruction(self, m, t, thread, bot, msg, level):
        self.obstruction["refused" if m.group(1) else "mined"] += 1

    def _stuck_event(self, bot, t, text):
        """Every stuck line is an event; events closer than STUCK_EPISODE_GAP_S belong to one episode."""
        b = self.bots[bot]
        b["stuck_events"] += 1
        if b["stuck_last_t"] is None or t - b["stuck_last_t"] > STUCK_EPISODE_GAP_S:
            b["stuck"] += 1
        b["stuck_last_t"] = t
        self._tl(bot, t, "STUCK", text)

    def _h_stuck(self, m, t, thread, bot, msg, level):
        if bot:
            self._stuck_event(bot, t, msg)

    def _h_stuck_named(self, m, t, thread, bot, msg, level):
        self._stuck_event(m.group(1), t, msg)

    def _h_server_paused(self, m, t, thread, bot, msg, level):
        self.pauses.append(t)

    def _h_stuck_surface(self, m, t, thread, bot, msg, level):
        self._stuck_event(m.group(1), t, "ensureAtSurface failed at %s,%s,%s" % (m.group(2), m.group(3), m.group(4)))

    def _h_wc_selected(self, m, t, thread, bot, msg, level):
        if bot:
            self.bots[bot]["targets_selected"] += 1
            self._tl(bot, t, "TREE", "selected base=%s,%s,%s" % m.group(1, 2, 3))

    def _h_wc_abandoned(self, m, t, thread, bot, msg, level):
        if not bot:
            return
        b = self.bots[bot]
        reason = m.group(5).strip()
        if m.group(1):
            b["stands_abandoned"] += 1
        else:
            b["targets_abandoned"] += 1
            b["abandon_reasons"][reason] += 1
            self._tl(bot, t, "ABANDON", "%s,%s,%s reason=%s" % (m.group(2), m.group(3), m.group(4), reason))

    def _h_wc_skip(self, m, t, thread, bot, msg, level):
        if bot:
            self.bots[bot]["stands_skipped"] += 1
            self._tl(bot, t, "SKIP", "remaining stands for %s,%s,%s" % m.group(1, 2, 3))

    def _log_mined(self, bot, t, n):
        b = self.bots[bot]
        b["logs_mined"] += n
        so = b["skill_open"]
        if n > 0 and so and so[0] == "woodcut":
            b["first_log_delays"].append(t - so[1])
            b["skill_open"] = ("woodcut-done", so[1])

    def _h_wc_mined(self, m, t, thread, bot, msg, level):
        if bot:
            n = int(m.group(1))
            self._log_mined(bot, t, n)
            self._tl(bot, t, "LOGS", "mined %d at base %s,%s,%s" % (n, m.group(2), m.group(3), m.group(4)))

    def _h_wc_cleanup_mined(self, m, t, thread, bot, msg, level):
        n = int(m.group(1))
        if bot and n:
            self._log_mined(bot, t, n)
            self._tl(bot, t, "LOGS", "cleanup mined %d" % n)

    def _h_wc_trapped(self, m, t, thread, bot, msg, level):
        if bot:
            self.bots[bot]["trapped_exits"] += 1 if m.group(1) == "exit" else 0
            self._tl(bot, t, "TRAPPED", msg)

    def _h_pathcompare(self, m, t, thread, bot, msg, level):
        rec = {"t": t, "finders": {}}
        for k, v in KV_RE.findall(m.group(1)):
            fv = FINDER_VAL_RE.match(v)
            if fv:
                rec["finders"][k] = {"ms": int(fv.group(1)), "len": int(fv.group(2)), "exec": fv.group(3),
                                     "reason": fv.group(4)}
            else:
                rec[k] = v
        who = rec.get("bot") or bot
        if who:
            self._set_roster(who)
            rec["bot"] = who
            if self.only_bot and who != self.only_bot:
                return
            if rec.get("primary"):
                self.bots[who]["primary"][rec["primary"]] += 1
        self.pathcompare.append(rec)

    def _h_pathcompare_outcome(self, m, t, thread, bot, msg, level):
        who = m.group(1)
        self._set_roster(who)
        if self.only_bot and who != self.only_bot:
            return
        self.pc_outcomes.append({"t": t, "bot": who, "to": m.group(2), "reached": m.group(3),
                                 "plan": m.group(4), "detail": m.group(5) or ""})

    def _h_vital_hp(self, m, t, thread, bot, msg, level):
        if bot and self.explicit:  # pool threads serve several bots; never guess a position/vital
            v = float(HP_ALL_RE.findall(msg)[-1])  # last value wins ("before=hp=.. after=hp=..")
            b = self.bots[bot]
            b["hp_last"] = v
            b["hp_min"] = v if b["hp_min"] is None else min(b["hp_min"], v)

    def _h_vital_food(self, m, t, thread, bot, msg, level):
        if bot and self.explicit:  # pool threads serve several bots; never guess a position/vital
            v = int(FOOD_ALL_RE.findall(msg)[-1])
            b = self.bots[bot]
            b["food_last"] = v
            b["food_min"] = v if b["food_min"] is None else min(b["food_min"], v)

    def _h_position(self, m, t, thread, bot, msg, level):
        if bot and self.explicit:  # pool threads serve several bots; never guess a position/vital
            b = self.bots[bot]
            p = "%s,%s,%s" % m.group(1, 2, 3)
            if b["pos_first"] is None:
                b["pos_first"] = (t, p)
            b["pos_last"] = (t, p)

    # ------------------------------------------------------------------ report
    def finish(self):
        self._flush_spam()
        self.spam_cur_min = None

    def report(self, top=10):
        self.finish()
        rep = {
            "files": self.files,
            "overview": self._overview(top),
            "bots": self._bots_report(),
            "pathfinding": self._paths_report(top),
            "pathcompare": pathcompare_stats(self.pathcompare, self.pc_outcomes),
            "ab": self._ab_report(),
        }
        return rep

    def _overview(self, top):
        wp = sorted(self.warn_patterns.items(), key=lambda kv: -kv[1][0])[:top]
        spam = sorted(self.spam.items(), key=lambda kv: -kv[1][0])[:top]
        return {
            "span": [hms(self.first_t), hms(self.last_t)],
            "span_s": (self.last_t - self.first_t) if self.first_t is not None else 0,
            "lines": self.lines, "unparsed_lines": self.unparsed,
            "levels": dict(self.levels),
            "bots": [b for b in self.roster if not self.only_bot or b == self.only_bot],
            "warn_patterns": [{"pattern": k, "count": v[0], "first": hms(v[1]), "last": hms(v[2]), "example": v[3]}
                              for k, v in wp],
            "spam_threshold_per_min": self.spam_per_min,
            "spam": [{"pattern": k, "peak_per_min": v[0], "peak_at": hms(v[1]), "minutes_over": v[2],
                      "lines_in_hot_minutes": v[3]} for k, v in spam],
            "system_chat_top": [{"text": k, "count": n} for k, n in self.system_chat.most_common(top)],
            "player_deaths": [{"t": hms(t), "msg": m} for t, m in self.player_deaths[:top]],
            "server_paused_at": [hms(t) for t in self.pauses],
        }

    def _bots_report(self):
        out = {}
        for name, b in self.bots.items():
            if self.only_bot and name != self.only_bot:
                continue
            tl = b["timeline"]
            out[name] = {
                "skills_started": dict(b["skills"]), "skill_results": dict(b["skill_results"]),
                "chat_lines": b["chat_n"], "chat_last": [{"t": hms(t), "text": x} for t, x in b["chat"][-8:]],
                "deaths": b["deaths"], "stuck_episodes": b["stuck"], "stuck_events": b["stuck_events"], "logs_mined": b["logs_mined"],
                "targets_selected": b["targets_selected"], "targets_abandoned": b["targets_abandoned"],
                "abandon_reasons": dict(b["abandon_reasons"]), "stands_abandoned": b["stands_abandoned"],
                "stands_skipped": b["stands_skipped"], "trapped_exits": b["trapped_exits"],
                "hp_min": b["hp_min"], "hp_last": b["hp_last"], "food_min": b["food_min"], "food_last": b["food_last"],
                "pos_first": [hms(b["pos_first"][0]), b["pos_first"][1]] if b["pos_first"] else None,
                "pos_last": [hms(b["pos_last"][0]), b["pos_last"][1]] if b["pos_last"] else None,
                "timeline_total": len(tl),
                "timeline": [{"t": hms(e[0]), "kind": e[1], "text": e[2], "repeat": e[4]} for e in tl],
            }
        return out

    def _paths_report(self, top):
        modes = {}
        for mode, p in self.paths.items():
            e = p["elapsed"]
            modes[mode] = {"count": p["n"], "p50_ms": pct(e, 50), "p90_ms": pct(e, 90), "max_ms": max(e) if e else None,
                           "pathLen0_pct": round(100.0 * p["len0"] / p["n"], 1) if p["n"] else None,
                           "walk_outcomes": dict(self.walk.get(mode, {}))}
            if p["status"]:
                modes[mode]["plan_status"] = dict(p["status"])
                modes[mode]["no_path_reasons"] = {k: v for k, v in p["reason"].items() if k != "NONE"}
        if "unknown" in self.walk and "unknown" not in modes:
            modes["unknown"] = {"count": 0, "walk_outcomes": dict(self.walk["unknown"])}
        rep = [{"bot": k[0], "route": k[1], "count": n} for k, n in self.escapes.most_common(top) if n >= 3]
        terrain = {}
        for d, costs in self.terrain.items():
            c = [x for x in costs if x is not None]
            terrain[d] = {"count": len(costs), "median_cost": median(c)}
        return {"modes": modes, "astar_timeouts": self.astar_timeouts, "baritone": dict(self.baritone),
                "shadow_finder_lines_excluded": self.shadow_finder_lines,
                "local_escape_repeats": rep, "terrain_moves": terrain, "obstruction_mine": dict(self.obstruction)}

    def _ab_report(self):
        rows = []
        for name, b in self.bots.items():
            if self.only_bot and name != self.only_bot:
                continue
            finder, source = infer_finder(b["primary"], b["modes"])
            rows.append({
                "bot": name, "finder": finder, "finder_source": source, "paths": sum(b["modes"].values()),
                "path_p50_ms": median(b["path_ms"]),
                "pathLen0_pct": round(100.0 * b["path_len0"] / len(b["path_ms"]), 1) if b["path_ms"] else None,
                "logs_cut": b["logs_mined"], "targets_abandoned": b["targets_abandoned"],
                "stuck_episodes": b["stuck"], "deaths": b["deaths"],
                "median_start_to_first_log_s": median(b["first_log_delays"]),
            })
        finders = set(r["finder"] for r in rows if r["finder"] not in ("none", "mixed"))
        return {"rows": rows, "distinct_finders": sorted(finders), "is_ab": len(finders) > 1}


def infer_finder(primary, modes):
    # type: (Counter, Counter) -> Tuple[str, str]
    for src, c in (("PathCompare primary", primary), ("PathFinder mode", modes)):
        total = sum(c.values())
        if total:
            mode, n = c.most_common(1)[0]
            if n >= 0.8 * total:
                return mode, src
            return "mixed", "%s %s" % (src, dict(c))
    return "none", "-"


# An outcome that carries plan=<id> (1.1.242) joins the [PathCompare] line with the same plan ID;
# when no compare line has that ID (the shadow job was dropped) it stays unjoined rather than
# guessing. Plan IDs restart at p1 every server session, so one log can hold the same ID several
# times: the outcome takes the latest unused line with its ID that is at most OUTCOME_JOIN_LATE_S
# after it. An outcome without a plan ID falls back to the time heuristic: it joins the latest
# [PathCompare] line for the same bot+to that is at most OUTCOME_JOIN_WINDOW_S before it and at most
# OUTCOME_JOIN_LATE_S after it. The compare line is written on the frens-pathcompare thread once the
# shadow finders finish, so for a fast failure it often lands after the outcome. If that latest line
# is already taken (by either join), the outcome stays unjoined: falling back to an older line would
# shift every later pair by one when a target is retried.
OUTCOME_JOIN_WINDOW_S = 300
OUTCOME_JOIN_LATE_S = 15


def join_outcomes(records, outcomes):
    # type: (List[dict], List[dict]) -> List[tuple]
    """Pairs each outcome with (compare record or None, reason). reason is "joined_plan" (same plan
    ID), "joined" (time window), "no_compare_line" (nothing in the window, or no line with the
    outcome's plan ID: a direct or aborted walk that never planned, or a dropped shadow job) or
    "compare_line_used" (the nearest line already belongs to another outcome)."""
    by_key = defaultdict(list)
    by_plan = defaultdict(list)
    for r in records:
        if r.get("plan"):
            by_plan[r["plan"]].append(r)
        if r["t"] is not None:
            by_key[(r.get("bot"), r.get("to"))].append(r)
    used = set()
    plan_hits = {}
    for i, o in enumerate(outcomes):
        if not o.get("plan"):
            continue
        for r in reversed(by_plan.get(o["plan"], [])):
            if id(r) in used:
                continue
            if o["t"] is not None and r["t"] is not None and r["t"] > o["t"] + OUTCOME_JOIN_LATE_S:
                continue
            used.add(id(r))
            plan_hits[i] = r
            break
    out = []
    for i, o in enumerate(outcomes):
        if i in plan_hits:
            out.append((o, plan_hits[i], "joined_plan"))
            continue
        if o.get("plan"):
            out.append((o, None, "no_compare_line"))
            continue
        latest = None
        if o["t"] is not None:
            for r in reversed(by_key.get((o["bot"], o["to"]), [])):
                if r["t"] > o["t"] + OUTCOME_JOIN_LATE_S:
                    continue
                if o["t"] - r["t"] <= OUTCOME_JOIN_WINDOW_S:
                    latest = r
                break
        if latest is None:
            out.append((o, None, "no_compare_line"))
        elif id(latest) in used:
            out.append((o, None, "compare_line_used"))
        else:
            used.add(id(latest))
            out.append((o, latest, "joined"))
    return out


def _rate(rows):
    n = len(rows)
    yes = sum(1 for o in rows if o["reached"] == "yes")
    return {"n": n, "reached": yes, "reached_pct": round(100.0 * yes / n, 1) if n else None}


def outcome_stats(records, outcomes):
    # type: (List[dict], List[dict]) -> dict
    pairs = join_outcomes(records, outcomes)
    joined = [(o, r) for o, r, _ in pairs if r is not None]
    by_primary = defaultdict(list)
    by_exec = defaultdict(list)
    for o, r in joined:
        primary = r.get("primary", "?")
        by_primary[primary].append(o)
        status = r["finders"].get(primary, {}).get("exec", "?")
        by_exec[status].append(o)
    return {
        "count": len(outcomes),
        "overall": _rate(outcomes),
        "joined": len(joined),
        "joined_by_plan": sum(1 for _, _, why in pairs if why == "joined_plan"),
        "unjoined": len(pairs) - len(joined),
        "unjoined_no_compare": sum(1 for _, _, why in pairs if why == "no_compare_line"),
        "unjoined_line_used": sum(1 for _, _, why in pairs if why == "compare_line_used"),
        "per_primary": {k: _rate(v) for k, v in sorted(by_primary.items())},
        "per_primary_exec": {k: _rate(v) for k, v in sorted(by_exec.items())},
        "exec_not_reached": sum(1 for o in by_exec.get("exec", []) if o["reached"] != "yes"),
    }


def pathcompare_stats(records, outcomes=None):
    # type: (List[dict], Optional[List[dict]]) -> dict
    oc = outcome_stats(records, outcomes) if outcomes else {"count": 0}
    if not records:
        return {"count": 0, "outcomes": oc}
    finders = sorted(set(k for r in records for k in r["finders"]))
    out = {"count": len(records), "finders": finders, "per_finder": {}, "pairs": {}, "per_bot": {},
           "outcomes": oc}
    for f in finders:
        rs = [r["finders"][f] for r in records if f in r["finders"]]
        found = [x for x in rs if x["len"] > 0]
        ex = [x for x in found if x["exec"] == "exec"]
        out["per_finder"][f] = {
            "n": len(rs), "found": len(found), "found_pct": round(100.0 * len(found) / len(rs), 1) if rs else None,
            "exec_of_found_pct": round(100.0 * len(ex) / len(found), 1) if found else None,
            "p50_ms": median([x["ms"] for x in rs]), "p90_ms": pct([x["ms"] for x in rs], 90)}
    for i, a in enumerate(finders):
        for bname in finders[i + 1:]:
            both = [r for r in records if a in r["finders"] and bname in r["finders"]]
            fa = lambda r, f: r["finders"][f]["len"] > 0  # noqa: E731
            ratios = [max(r["finders"][bname]["ms"], 1) / float(max(r["finders"][a]["ms"], 1)) for r in both
                      if fa(r, a) and fa(r, bname)]
            out["pairs"]["%s_vs_%s" % (a, bname)] = {
                "n": len(both),
                "both_found": sum(1 for r in both if fa(r, a) and fa(r, bname)),
                "only_" + a: sum(1 for r in both if fa(r, a) and not fa(r, bname)),
                "only_" + bname: sum(1 for r in both if fa(r, bname) and not fa(r, a)),
                "neither": sum(1 for r in both if not fa(r, a) and not fa(r, bname)),
                a + "_faster": sum(1 for r in both if r["finders"][a]["ms"] < r["finders"][bname]["ms"]),
                bname + "_faster": sum(1 for r in both if r["finders"][bname]["ms"] < r["finders"][a]["ms"]),
                "median_ms_ratio_%s_over_%s" % (bname, a): round(median(ratios), 2) if ratios else None,
            }
    per_bot = defaultdict(lambda: {"n": 0, "primary": Counter(), "found": Counter()})
    for r in records:
        pb = per_bot[r.get("bot", "?")]
        pb["n"] += 1
        pb["primary"][r.get("primary", "?")] += 1
        for f, v in r["finders"].items():
            if v["len"] > 0:
                pb["found"][f] += 1
    out["per_bot"] = {k: {"n": v["n"], "primary": dict(v["primary"]), "found": dict(v["found"])}
                      for k, v in per_bot.items()}
    out["unified_only_reachable"] = unified_only_reachable(records)
    return out


UNIFIED = "unified"


def unified_only_reachable(records):
    # type: (List[dict]) -> dict
    """Lines where the unified finder found a path and every other finder on the line found none
    (1.1.242). That is the merged finder reaching what the old ones could not (break, place, swim),
    not a disagreement. "by_status" splits it by the unified exec/noexec status."""
    rows = [r for r in records if UNIFIED in r["finders"] and len(r["finders"]) > 1]
    hits = [r for r in rows if r["finders"][UNIFIED]["len"] > 0
            and all(v["len"] == 0 for f, v in r["finders"].items() if f != UNIFIED)]
    by_status = Counter(r["finders"][UNIFIED]["exec"] for r in hits)
    by_primary = Counter(r.get("primary", "?") for r in hits)
    # NO_PATH / PARTIAL reasons the unified field carried (":<REASON>" suffix), "none" key = no path.
    reasons = Counter("%s:%s" % ("none" if r["finders"][UNIFIED]["len"] == 0 else "partial",
                                 r["finders"][UNIFIED]["reason"])
                      for r in records if UNIFIED in r["finders"] and r["finders"][UNIFIED].get("reason"))
    return {"lines_with_unified": len(rows), "count": len(hits),
            "by_status": dict(by_status), "by_primary": dict(by_primary), "reasons": dict(reasons)}


# --------------------------------------------------------------------------- markdown

def _fmt(v):
    if v is None:
        return "-"
    if isinstance(v, float):
        return ("%.1f" % v).rstrip("0").rstrip(".")
    return str(v)


def render_md(rep, timeline_max=TIMELINE_KINDS_MAX):
    o = []
    ov = rep["overview"]
    o.append("# Frens log report")
    o.append("Files: " + ", ".join(
        "%s (%d lines%s)" % (os.path.basename(f["path"]), f["lines"], ", TRUNCATED" if f.get("truncated") else "")
        for f in rep["files"]))
    o.append("")
    o.append("## 1. Overview")
    o.append("Span %s to %s (%dm), %d lines (%d unparsed), levels %s. Bots: %s" % (
        ov["span"][0], ov["span"][1], ov["span_s"] // 60, ov["lines"], ov["unparsed_lines"],
        " ".join("%s=%d" % kv for kv in sorted(ov["levels"].items())), ", ".join(ov["bots"]) or "none"))
    if ov.get("server_paused_at"):
        o.append("")
        o.append("**SERVER PAUSED at %s** (pause menu). Bots do not move while paused but their workers keep "
                 "logging walk failures, so finder and stuck numbers after a pause are not valid."
                 % ", ".join(ov["server_paused_at"]))
    if ov["warn_patterns"]:
        o.append("")
        o.append("Top WARN/ERROR patterns (count, first, last):")
        for w in ov["warn_patterns"]:
            o.append("- %d  %s..%s  `%s`" % (w["count"], w["first"], w["last"], w["pattern"][:110]))
    if ov["spam"]:
        o.append("")
        o.append("Spam (> %d/min): peak/min, at, hot minutes:" % ov["spam_threshold_per_min"])
        for s in ov["spam"]:
            o.append("- %d/min at %s, %d min  `%s`" % (s["peak_per_min"], s["peak_at"], s["minutes_over"],
                                                     s["pattern"][:110]))
    if ov["player_deaths"]:
        o.append("")
        o.append("Non-bot deaths: " + "; ".join("%s %s" % (d["t"], d["msg"]) for d in ov["player_deaths"]))

    o.append("")
    o.append("## 2. Bots")
    for name, b in sorted(rep["bots"].items()):
        o.append("")
        o.append("### %s" % name)
        o.append("skills %s | results %s | logs %d | trees sel/abandoned %d/%d (stands abandoned %d, skipped %d) | "
                 "stuck %d episodes/%d events | deaths %d | trapped exits %d" % (
                     b["skills_started"] or "{}", b["skill_results"] or "{}", b["logs_mined"], b["targets_selected"],
                     b["targets_abandoned"], b["stands_abandoned"], b["stands_skipped"], b["stuck_episodes"],
                     b["stuck_events"], b["deaths"], b["trapped_exits"]))
        o.append("hp min/last %s/%s, food min/last %s/%s, pos %s -> %s, chat %d lines%s" % (
            _fmt(b["hp_min"]), _fmt(b["hp_last"]), _fmt(b["food_min"]), _fmt(b["food_last"]),
            "@".join(reversed(b["pos_first"])) if b["pos_first"] else "-",
            "@".join(reversed(b["pos_last"])) if b["pos_last"] else "-", b["chat_lines"],
            (", abandon reasons %s" % b["abandon_reasons"]) if b["abandon_reasons"] else ""))
        tl = b["timeline"]
        if len(tl) > timeline_max:
            half = timeline_max // 2
            shown = tl[:half] + [None] + tl[-half:]
        else:
            shown = tl
        for e in shown:
            if e is None:
                o.append("  ... %d more ..." % (len(tl) - 2 * half))
                continue
            o.append("  %s %-7s %s%s" % (e["t"], e["kind"], e["text"], (" x%d" % e["repeat"]) if e["repeat"] > 1 else ""))
        for c in b["chat_last"][-4:]:
            o.append("  %s CHAT    %s" % (c["t"], c["text"]))

    pf = rep["pathfinding"]
    o.append("")
    o.append("## 3. Pathfinding")
    for mode, p in sorted(pf["modes"].items()):
        o.append("- %s: n=%d p50=%sms p90=%sms max=%sms pathLen0=%s%% walk fails %s" % (
            mode, p["count"], _fmt(p.get("p50_ms")), _fmt(p.get("p90_ms")), _fmt(p.get("max_ms")),
            _fmt(p.get("pathLen0_pct")), p["walk_outcomes"] or "{}"))
        if p.get("plan_status"):
            o.append("  - %s plans %s, no-path reasons %s" % (mode, p["plan_status"], p["no_path_reasons"] or "{}"))
    o.append("- A* timeouts %d | Baritone %s | obstruction mine %s" % (
        pf["astar_timeouts"], pf["baritone"] or "{}", pf["obstruction_mine"] or "{}"))
    if pf.get("shadow_finder_lines_excluded"):
        o.append("- (%d finder lines from the %s thread excluded; see section 4)" % (
            pf["shadow_finder_lines_excluded"], PATHCOMPARE_THREAD))
    if pf["terrain_moves"]:
        o.append("- terrain moves: " + ", ".join("%s n=%d cost~%s" % (d, v["count"], _fmt(v["median_cost"]))
                                                for d, v in sorted(pf["terrain_moves"].items())))
    for r in pf["local_escape_repeats"][:5]:
        o.append("- escape repeat x%d %s route %s" % (r["count"], r["bot"], r["route"][:70]))

    pc = rep["pathcompare"]
    o.append("")
    o.append("## 4. PathCompare")
    if not pc["count"]:
        o.append("No [PathCompare] lines.")
    if pc["count"]:
        for f, v in pc["per_finder"].items():
            o.append("- %s: n=%d found=%s%% exec(of found)=%s%% p50=%sms p90=%sms" % (
                f, v["n"], _fmt(v["found_pct"]), _fmt(v["exec_of_found_pct"]), _fmt(v["p50_ms"]), _fmt(v["p90_ms"])))
        for k, v in pc["pairs"].items():
            o.append("- %s: %s" % (k, ", ".join("%s=%s" % (a, _fmt(b)) for a, b in v.items())))
        for k, v in sorted(pc["per_bot"].items()):
            o.append("- bot %s: n=%d primary=%s found=%s" % (k, v["n"], v["primary"], v["found"]))
        uo = pc.get("unified_only_reachable") or {}
        if uo.get("lines_with_unified"):
            o.append("- unified-only reachable: %d of %d lines with a unified field (status %s, primary %s)" % (
                uo["count"], uo["lines_with_unified"], uo["by_status"] or "{}", uo["by_primary"] or "{}"))
        if uo.get("reasons"):
            o.append("- unified reasons (none = no path, partial): %s" % ", ".join(
                "%s=%d" % (k, v) for k, v in sorted(uo["reasons"].items())))
    oc = pc.get("outcomes") or {"count": 0}
    if oc["count"]:
        ov = oc["overall"]
        o.append("- walk outcomes: n=%d reached=%d (%s%%), joined to a compare line %d (by plan ID %d), unjoined %d"
                 " (no compare line %d, nearest line already used %d)" % (
                     ov["n"], ov["reached"], _fmt(ov["reached_pct"]), oc["joined"], oc["joined_by_plan"],
                     oc["unjoined"], oc["unjoined_no_compare"], oc["unjoined_line_used"]))
        for k, v in oc["per_primary"].items():
            o.append("  - primary %s: reached %d/%d (%s%%)" % (k, v["reached"], v["n"], _fmt(v["reached_pct"])))
        for k, v in oc["per_primary_exec"].items():
            o.append("  - primary path %s: reached %d/%d (%s%%)" % (k, v["reached"], v["n"], _fmt(v["reached_pct"])))
        o.append("  - exec but not reached: %d" % oc["exec_not_reached"])

    ab = rep["ab"]
    o.append("")
    o.append("## 5. A/B per bot" + ("" if ab["is_ab"] else " (single finder; not an A/B run)"))
    o.append("| bot | finder | paths | p50 ms | len0 % | logs | abandoned | stuck | deaths | start->1st log s |")
    o.append("|---|---|---|---|---|---|---|---|---|---|")
    for r in sorted(ab["rows"], key=lambda r: (r["finder"], r["bot"])):
        o.append("| %s | %s | %d | %s | %s | %d | %d | %d | %d | %s |" % (
            r["bot"], r["finder"], r["paths"], _fmt(r["path_p50_ms"]), _fmt(r["pathLen0_pct"]), r["logs_cut"],
            r["targets_abandoned"], r["stuck_episodes"], r["deaths"], _fmt(r["median_start_to_first_log_s"])))
    mixed = [r for r in ab["rows"] if r["finder"] == "mixed"]
    for r in mixed:
        o.append("(%s finder mixed: %s)" % (r["bot"], r["finder_source"]))
    return "\n".join(o) + "\n"


# --------------------------------------------------------------------------- API + CLI

def analyze(paths, since=None, until=None, bot=None, spam_per_min=60):
    """Analyze one or more log files (in order); returns the report dict."""
    if isinstance(paths, str):
        paths = [paths]
    a = Analyzer(since=since, until=until, only_bot=bot, spam_per_min=spam_per_min)
    for p in paths:
        a.feed_file(p)
    return a.report()


def _snapshot(a):
    snap = {"lines": a.lines, "WARN": a.levels.get("WARN", 0), "ERROR": a.levels.get("ERROR", 0),
            "paths": sum(p["n"] for p in a.paths.values())}
    for name, b in a.bots.items():
        for k in ("logs_mined", "targets_abandoned", "stuck", "deaths", "chat_n"):
            snap["%s.%s" % (name, k)] = b[k]
    return snap


def follow(path, every, args):
    a = Analyzer(since=args.since, until=args.until, only_bot=args.bot, spam_per_min=args.spam_per_min)
    offset = a.feed_file(path)
    a.files = a.files[:1]
    sys.stdout.write(render_md(a.report()))
    prev = _snapshot(a)
    while True:
        time.sleep(every)
        try:
            size = os.path.getsize(path)
        except OSError:
            continue
        if size < offset:
            offset = 0  # rotated
        offset = a.feed_file(path, offset)
        a.files = a.files[:1]
        cur = _snapshot(a)
        delta = ["%s+%d" % (k, cur[k] - prev.get(k, 0)) for k in sorted(cur) if cur[k] != prev.get(k, 0)]
        sys.stdout.write("[%s] %s\n" % (hms(a.last_t), " ".join(delta) or "no change"))
        sys.stdout.flush()
        prev = cur


def main(argv=None):
    ap = argparse.ArgumentParser(description="Digest Frens Minecraft client logs.")
    ap.add_argument("logs", nargs="+", metavar="LOG", help="latest.log or rotated *.log.gz (several = read in order)")
    ap.add_argument("--since", help="HH:MM:SS (wall clock, inclusive)")
    ap.add_argument("--until", help="HH:MM:SS (wall clock, inclusive)")
    ap.add_argument("--bot", help="limit per-bot and pathfinding sections to one bot")
    ap.add_argument("--json", metavar="OUT", help="write the full report as JSON")
    ap.add_argument("--md", metavar="OUT", help="write markdown to a file instead of stdout")
    ap.add_argument("--spam-per-min", type=int, default=60, help="masked pattern lines/minute counted as spam")
    ap.add_argument("--timeline", type=int, default=TIMELINE_KINDS_MAX, help="max timeline rows per bot")
    ap.add_argument("--follow", type=float, metavar="SECS", help="keep reading LOG and print deltas every SECS")
    args = ap.parse_args(argv)
    args.since = parse_hms(args.since) if args.since else None
    args.until = parse_hms(args.until) if args.until else None
    if args.follow:
        try:
            follow(args.logs[-1], args.follow, args)
        except KeyboardInterrupt:
            return 0
    rep = analyze(args.logs, args.since, args.until, args.bot, args.spam_per_min)
    md = render_md(rep, args.timeline)
    if args.json:
        with open(args.json, "w") as fh:
            json.dump(rep, fh, indent=1, default=str)
    if args.md:
        with open(args.md, "w") as fh:
            fh.write(md)
    else:
        sys.stdout.write(md)
    bad = [f for f in rep["files"] if f.get("error")]
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
