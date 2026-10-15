"""Unit tests for frens_log_report.py and frens_log_prune.py.

Run from tools/logs:  python3 -m unittest
"""
import gzip
import io
import json
import os
import shutil
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import frens_log_prune as prune  # noqa: E402
import frens_log_report as flr  # noqa: E402

FIXTURE = os.path.join(HERE, "fixtures", "synthetic_ab.log")


def run_lines(text, **kw):
    a = flr.Analyzer(**kw)
    for line in text.strip("\n").split("\n"):
        a.feed(line + "\n")
    return a.report()


class ReportFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rep = flr.analyze(FIXTURE)

    def test_roster_excludes_human(self):
        self.assertEqual(self.rep["overview"]["bots"], ["Jake", "Bob"])

    def test_midnight_wrap_keeps_order(self):
        ov = self.rep["overview"]
        self.assertEqual(ov["span"], ["23:58:00", "d1+00:00:40"])
        self.assertEqual(ov["span_s"], 160)

    def test_pathcompare_pairs_and_tolerance(self):
        pc = self.rep["pathcompare"]
        self.assertEqual(pc["count"], 2)
        self.assertEqual(pc["finders"], ["baritone", "bidir"])
        pair = pc["pairs"]["baritone_vs_bidir"]
        self.assertEqual(pair["both_found"], 1)
        self.assertEqual(pair["only_baritone"], 1)
        self.assertEqual(pair["only_bidir"], 0)
        self.assertEqual(pair["baritone_faster"], 2)
        self.assertEqual(pair["median_ms_ratio_bidir_over_baritone"], 10.0)
        self.assertEqual(pc["per_finder"]["baritone"]["exec_of_found_pct"], 50.0)
        self.assertEqual(pc["per_bot"]["Bob"]["primary"], {"baritone": 1})

    def test_ab_finder_per_bot(self):
        rows = {r["bot"]: r for r in self.rep["ab"]["rows"]}
        self.assertEqual(rows["Jake"]["finder"], "bidir")
        self.assertEqual(rows["Bob"]["finder"], "baritone")
        self.assertTrue(self.rep["ab"]["is_ab"])
        self.assertEqual(rows["Jake"]["logs_cut"], 4)
        self.assertEqual(rows["Jake"]["median_start_to_first_log_s"], 85)
        self.assertEqual(rows["Bob"]["targets_abandoned"], 1)
        self.assertEqual(rows["Bob"]["stuck_episodes"], 1)
        self.assertEqual(rows["Bob"]["deaths"], 1)

    def test_bob_timeline_and_stand_vs_target(self):
        bob = self.rep["bots"]["Bob"]
        self.assertEqual(bob["stands_abandoned"], 1)
        self.assertEqual(bob["abandon_reasons"], {"no trunk reach": 1})
        self.assertEqual(bob["stuck_events"], 2)
        kinds = [e["kind"] for e in bob["timeline"]]
        self.assertEqual(kinds[0], "SKILL+")
        self.assertIn("DEATH", kinds)
        self.assertEqual(kinds[-1], "SKILL-")
        self.assertEqual(bob["chat_lines"], 1)  # the death echo in [CHAT] is not a second death

    def test_pathfinding_extras(self):
        pf = self.rep["pathfinding"]
        self.assertEqual(pf["baritone"], {"Using best-so-far": 1})
        self.assertEqual(pf["terrain_moves"]["BREAK"], {"count": 1, "median_cost": 4.5})
        self.assertEqual(pf["terrain_moves"]["WALK_AROUND"]["count"], 1)
        self.assertEqual(pf["obstruction_mine"], {"refused": 1})
        self.assertEqual(pf["modes"]["baritone"]["walk_outcomes"], {"pursuit failed": 1, "walk blocked near": 1})

    def test_markdown_renders(self):
        md = flr.render_md(self.rep)
        for h in ("## 1. Overview", "## 2. Bots", "## 3. Pathfinding", "## 4. PathCompare", "## 5. A/B"):
            self.assertIn(h, md)


class ReportUnitTest(unittest.TestCase):
    def test_thread_binding_rebinds(self):
        rep = run_lines("""
[10:00:00] [mod-command-skill-1/INFO]: Skill 'woodcut' starting for bot Jake on thread mod-command-skill-1
[10:00:01] [mod-command-skill-1/INFO]: [PathFinder] mode=bidir elapsed=10ms pathLen=3 from=1, 2, 3 to=4, 5, 6
[10:00:02] [mod-command-skill-1/INFO]: Skill 'woodcut' starting for bot Bob on thread mod-command-skill-1
[10:00:03] [mod-command-skill-1/INFO]: [PathFinder] mode=bidir elapsed=20ms pathLen=0 from=1, 2, 3 to=4, 5, 6
[10:00:04] [mod-command-skill-1/INFO]: [PathFinder] mode=bidir elapsed=30ms pathLen=0 from=1, 2, 3 to=4, 5, 6
""")
        rows = {r["bot"]: r for r in rep["ab"]["rows"]}
        self.assertEqual(rows["Jake"]["paths"], 1)
        self.assertEqual(rows["Bob"]["paths"], 2)
        self.assertEqual(rows["Bob"]["pathLen0_pct"], 100.0)

    def test_pathcompare_thread_finder_lines_stay_out_of_section_3(self):
        rep = run_lines("""
[10:00:00] [mod-command-skill-1/INFO]: Skill 'woodcut' starting for bot Bob on thread mod-command-skill-1
[10:00:01] [mod-command-skill-1/WARN]: [BaritonePathFinder] Open set exhausted, 1 nodes expanded
[10:00:01] [mod-command-skill-1/INFO]: [PathFinder] mode=baritone elapsed=3ms pathLen=0 from=1, 2, 3 to=4, 5, 6
[10:00:01] [frens-pathcompare/INFO]: Starting Bi-directional A* pathfinding with block tagging...
[10:00:11] [frens-pathcompare/WARN]: A* pathfinding timed out after 10000ms (900 iterations) from 1, 2, 3 to 4, 5, 6
[10:00:12] [frens-pathcompare/INFO]: [BaritonePathFinder] Start=(1,2,3) Target=(4,5,6) timeout=500ms
[10:00:12] [frens-pathcompare/WARN]: [BaritonePathFinder] Open set exhausted, 1 nodes expanded
[10:00:12] [frens-pathcompare/INFO]: [BaritonePathFinder] Goal reached! 12 iterations, 10 nodes expanded
[10:00:13] [mod-command-skill-1/WARN]: A* pathfinding timed out after 10000ms (900 iterations) from 1, 2, 3 to 4, 5, 6
""")
        pf = rep["pathfinding"]
        self.assertEqual(pf["astar_timeouts"], 1)
        self.assertEqual(pf["baritone"], {"Open set exhausted": 1})
        self.assertEqual(pf["shadow_finder_lines_excluded"], 3)
        self.assertEqual(pf["modes"]["baritone"]["count"], 1)
        self.assertIn("3 finder lines from the frens-pathcompare thread excluded", flr.render_md(rep))

    def test_pathcompare_outcome_joins_latest_compare_line(self):
        rep = run_lines("""
[10:00:00] [mod-command-skill-1/INFO]: [PathCompare] bot=Jake from=0,64,0 to=10,64,20 primary=baritone bidir=90ms/14/exec baritone=12ms/15/exec
[10:00:05] [mod-command-skill-1/INFO]: [PathCompare] bot=Jake from=1,64,1 to=10,64,20 primary=baritone bidir=80ms/13/exec baritone=11ms/14/noexec
[10:00:09] [mod-command-skill-1/INFO]: [PathCompareOutcome] bot=Jake to=10,64,20 reached=no detail=woodcut:_walk_blocked_near_9,64,19
[10:00:10] [mod-command-skill-2/INFO]: [PathCompare] bot=Bob from=5,68,11 to=-12,68,152 primary=bidir bidir=50ms/40/exec baritone=15ms/40/exec
[10:00:20] [mod-command-skill-2/INFO]: [PathCompareOutcome] bot=Bob to=-12,68,152 reached=no detail=stand:_no_walkable_path
[10:00:30] [mod-command-skill-1/INFO]: [PathCompare] bot=Jake from=3,64,3 to=30,64,30 primary=baritone bidir=70ms/20/exec baritone=9ms/21/exec
[10:00:31] [mod-command-skill-1/INFO]: [PathCompareOutcome] bot=Jake to=30,64,30 reached=yes detail=woodcut:_walked_to_destination
[10:00:32] [mod-command-skill-1/INFO]: [PathCompareOutcome] bot=Jake to=30,64,30 reached=yes detail=woodcut:_walked_directly
[10:00:40] [mod-command-skill-1/INFO]: [PathCompareOutcome] bot=Jake to=99,64,99 reached=n/a
""")
        pc = rep["pathcompare"]
        self.assertEqual(pc["count"], 4)  # outcome lines never count as compare lines
        oc = pc["outcomes"]
        self.assertEqual(oc["count"], 5)
        self.assertEqual(oc["overall"]["reached"], 2)
        self.assertEqual(oc["joined"], 3)  # the second 30,64,30 outcome and 99,64,99 find no unjoined line
        self.assertEqual(oc["unjoined"], 2)
        self.assertEqual(oc["unjoined_line_used"], 1)  # second 30,64,30 outcome
        self.assertEqual(oc["unjoined_no_compare"], 1)  # 99,64,99: a walk that never planned
        self.assertEqual(oc["per_primary"]["baritone"], {"n": 2, "reached": 1, "reached_pct": 50.0})
        self.assertEqual(oc["per_primary"]["bidir"], {"n": 1, "reached": 0, "reached_pct": 0.0})
        # Jake's first outcome joins the LATEST compare line (baritone noexec), not the earlier exec one.
        self.assertEqual(oc["per_primary_exec"]["noexec"], {"n": 1, "reached": 0, "reached_pct": 0.0})
        self.assertEqual(oc["per_primary_exec"]["exec"], {"n": 2, "reached": 1, "reached_pct": 50.0})
        self.assertEqual(oc["exec_not_reached"], 1)
        # The sanitized detail ("walk_blocked_near", "no_walkable_path") never counts as a walk failure.
        self.assertNotIn("unknown", rep["pathfinding"]["modes"])
        md = flr.render_md(rep)
        self.assertIn("walk outcomes: n=5 reached=2 (40%)", md)
        self.assertIn("exec but not reached: 1", md)

    def test_outcome_joins_compare_line_written_after_it(self):
        rep = run_lines("""
[10:00:00] [mod-command-skill-1/INFO]: [PathCompareOutcome] bot=Jake to=5,64,5 reached=no detail=woodcut:_no_path
[10:00:03] [frens-pathcompare/INFO]: [PathCompare] bot=Jake from=0,64,0 to=5,64,5 primary=baritone bidir=900ms/0/none baritone=3000ms/9/exec
[10:01:00] [mod-command-skill-1/INFO]: [PathCompareOutcome] bot=Jake to=6,64,6 reached=no detail=-
[10:01:20] [frens-pathcompare/INFO]: [PathCompare] bot=Jake from=0,64,0 to=6,64,6 primary=baritone bidir=1ms/0/none baritone=1ms/9/exec
""")
        oc = rep["pathcompare"]["outcomes"]
        self.assertEqual(oc["joined"], 1)  # 3 s late joins, 20 s late does not
        self.assertEqual(oc["unjoined_no_compare"], 1)
        self.assertEqual(oc["exec_not_reached"], 1)

    def test_used_latest_line_leaves_outcome_unjoined_without_fallback(self):
        rep = run_lines("""
[10:00:00] [frens-pathcompare/INFO]: [PathCompare] bot=Jake from=0,64,0 to=5,64,5 primary=baritone bidir=9ms/4/exec baritone=3ms/4/exec
[10:00:10] [frens-pathcompare/INFO]: [PathCompare] bot=Jake from=0,64,0 to=5,64,5 primary=baritone bidir=9ms/0/none baritone=3ms/0/noexec
[10:00:11] [mod-command-skill-1/INFO]: [PathCompareOutcome] bot=Jake to=5,64,5 reached=no detail=a
[10:00:12] [mod-command-skill-1/INFO]: [PathCompareOutcome] bot=Jake to=5,64,5 reached=no detail=b
""")
        oc = rep["pathcompare"]["outcomes"]
        # The first outcome takes the 10:00:10 noexec line; the second finds it used and does NOT
        # fall back to the older 10:00:00 exec line.
        self.assertEqual(oc["joined"], 1)
        self.assertEqual(oc["unjoined"], 1)
        self.assertEqual(oc["unjoined_line_used"], 1)
        self.assertEqual(oc["unjoined_no_compare"], 0)
        self.assertEqual(oc["per_primary_exec"], {"noexec": {"n": 1, "reached": 0, "reached_pct": 0.0}})
        self.assertEqual(oc["exec_not_reached"], 0)
        self.assertIn("nearest line already used 1", flr.render_md(rep))

    def test_plan_id_join_is_exact_and_preferred(self):
        rep = run_lines("""
[10:00:00] [Worker-1/INFO]: [PathFinder] mode=unified elapsed=2ms pathLen=10 from=0, 64, 0 to=5, 64, 5 plan=p1 status=FULL reason=NONE moves=9 nodes=30
[10:00:01] [Worker-1/INFO]: [PathFinder] mode=unified elapsed=1ms pathLen=10 from=0, 64, 0 to=5, 64, 5 plan=p2 status=FULL reason=NONE moves=9 nodes=31
[10:00:02] [Worker-1/INFO]: [PathFinder] mode=unified elapsed=1ms pathLen=0 from=0, 64, 0 to=9, 64, 9 plan=p3 status=NO_PATH reason=BLOCKED_PROTECTED moves=0 nodes=1
[10:00:03] [frens-pathcompare/INFO]: [PathCompare] bot=Jake from=0,64,0 to=5,64,5 primary=unified bidir=9ms/10/exec baritone=3ms/10/exec unified=2ms/10/exec plan=p1
[10:00:04] [frens-pathcompare/INFO]: [PathCompare] bot=Jake from=0,64,0 to=5,64,5 primary=unified bidir=9ms/0/none baritone=3ms/0/none unified=1ms/10/noexec plan=p2
[10:00:05] [Worker-1/INFO]: [PathCompareOutcome] bot=Jake to=5,64,5 reached=yes plan=p1 detail=a
[10:00:06] [Worker-1/INFO]: [PathCompareOutcome] bot=Jake to=5,64,5 reached=no plan=p9 detail=b
[10:00:07] [Worker-1/INFO]: [PathCompareOutcome] bot=Jake to=5,64,5 reached=no detail=c
""")
        oc = rep["pathcompare"]["outcomes"]
        # p1 joins its own line although the p2 line is later; p9 has no line and does not guess;
        # the plan-less outcome falls back to the time join and takes the latest line (p2).
        self.assertEqual(oc["joined_by_plan"], 1)
        self.assertEqual(oc["joined"], 2)
        self.assertEqual(oc["unjoined_no_compare"], 1)
        self.assertEqual(oc["per_primary_exec"], {"exec": {"n": 1, "reached": 1, "reached_pct": 100.0},
                                                  "noexec": {"n": 1, "reached": 0, "reached_pct": 0.0}})
        pc = rep["pathcompare"]
        self.assertEqual(pc["finders"], ["baritone", "bidir", "unified"])
        self.assertEqual(pc["unified_only_reachable"],
                         {"lines_with_unified": 2, "count": 1, "by_status": {"noexec": 1},
                          "by_primary": {"unified": 1}, "reasons": {}})
        self.assertEqual(pc["pairs"]["baritone_vs_unified"]["only_unified"], 1)
        modes = rep["pathfinding"]["modes"]
        self.assertEqual(modes["unified"]["count"], 3)
        self.assertEqual(modes["unified"]["plan_status"], {"FULL": 2, "NO_PATH": 1})
        self.assertEqual(modes["unified"]["no_path_reasons"], {"BLOCKED_PROTECTED": 1})
        md = flr.render_md(rep)
        self.assertIn("unified-only reachable: 1 of 2 lines", md)
        self.assertIn("(by plan ID 1)", md)
        self.assertIn("unified plans", md)

    def test_used_plan_line_blocks_the_time_join(self):
        rep = run_lines("""
[10:00:00] [frens-pathcompare/INFO]: [PathCompare] bot=Jake from=0,64,0 to=5,64,5 primary=unified bidir=9ms/4/exec baritone=3ms/4/exec unified=1ms/4/exec plan=p4
[10:00:01] [Worker-1/INFO]: [PathCompareOutcome] bot=Jake to=5,64,5 reached=no detail=old_format_walk
[10:00:02] [Worker-1/INFO]: [PathCompareOutcome] bot=Jake to=5,64,5 reached=yes plan=p4 detail=x
""")
        oc = rep["pathcompare"]["outcomes"]
        # The plan join claims the line first, so the earlier plan-less outcome finds it used.
        self.assertEqual(oc["joined_by_plan"], 1)
        self.assertEqual(oc["joined"], 1)
        self.assertEqual(oc["unjoined_line_used"], 1)

    def test_plan_ids_reused_after_a_restart_join_their_own_session(self):
        rep = run_lines("""
[10:00:00] [frens-pathcompare/INFO]: [PathCompare] bot=Jake from=0,64,0 to=5,64,5 primary=unified bidir=9ms/4/exec baritone=3ms/4/exec unified=1ms/4/exec plan=p1
[10:00:05] [Worker-1/INFO]: [PathCompareOutcome] bot=Jake to=5,64,5 reached=yes plan=p1 detail=first_session
[11:00:00] [frens-pathcompare/INFO]: [PathCompare] bot=Jake from=0,64,0 to=8,64,8 primary=unified bidir=9ms/0/none baritone=3ms/0/none unified=1ms/6/noexec plan=p1
[11:00:05] [Worker-1/INFO]: [PathCompareOutcome] bot=Jake to=8,64,8 reached=no plan=p1 detail=second_session
""")
        oc = rep["pathcompare"]["outcomes"]
        self.assertEqual(oc["joined_by_plan"], 2)
        # Each outcome took its own session's line: exec/reached for the first, noexec/not for the second.
        self.assertEqual(oc["per_primary_exec"], {"exec": {"n": 1, "reached": 1, "reached_pct": 100.0},
                                                  "noexec": {"n": 1, "reached": 0, "reached_pct": 0.0}})

    def test_unified_reason_suffix_parses_and_is_counted(self):
        rep = run_lines("""
[10:00:00] [frens-pathcompare/INFO]: [PathCompare] bot=Eve from=29,78,142 to=17,73,166 primary=unified bidir=1ms/0/none baritone=32ms/11/exec unified=0ms/0/none:GOAL_NOT_STANDABLE plan=p12
[10:00:01] [frens-pathcompare/INFO]: [PathCompare] bot=Eve from=18,71,151 to=17,73,166 primary=baritone bidir=1ms/0/none baritone=42ms/5/exec unified=30ms/7/exec:GOAL_NOT_STANDABLE plan=p15
[10:00:02] [frens-pathcompare/INFO]: [PathCompare] bot=Eve from=0,64,0 to=5,64,5 primary=baritone bidir=9ms/4/exec baritone=3ms/4/exec unified=1ms/4/exec plan=p16
""")
        pc = rep["pathcompare"]
        rec = rep["pathcompare"]["unified_only_reachable"]
        self.assertEqual(rec["reasons"], {"none:GOAL_NOT_STANDABLE": 1, "partial:GOAL_NOT_STANDABLE": 1})
        self.assertEqual(pc["per_finder"]["unified"]["n"], 3)
        self.assertIn("unified reasons (none = no path, partial): none:GOAL_NOT_STANDABLE=1", flr.render_md(rep))
        fv = flr.FINDER_VAL_RE.match("0ms/0/none:GOAL_NOT_STANDABLE")
        self.assertEqual((fv.group(3), fv.group(4)), ("none", "GOAL_NOT_STANDABLE"))
        self.assertIsNone(flr.FINDER_VAL_RE.match("3ms/4/exec").group(4))

    def test_old_two_finder_lines_still_parse(self):
        rep = run_lines("""
[10:00:00] [frens-pathcompare/INFO]: [PathCompare] bot=Jake from=0,64,0 to=5,64,5 primary=baritone bidir=9ms/4/exec baritone=3ms/4/exec
[10:00:01] [Worker-1/INFO]: [PathFinder] mode=baritone elapsed=3ms pathLen=4 from=0, 64, 0 to=5, 64, 5
""")
        pc = rep["pathcompare"]
        self.assertEqual(pc["unified_only_reachable"]["lines_with_unified"], 0)
        self.assertNotIn("plan_status", rep["pathfinding"]["modes"]["baritone"])
        self.assertNotIn("unified-only", flr.render_md(rep))

    def test_pathcompare_regex_ignores_outcome_lines(self):
        line = "[PathCompareOutcome] bot=Jake to=1,2,3 reached=yes detail=x"
        for trigger, rx, name in flr.PATTERNS:
            if name == "pathcompare":
                self.assertNotIn(trigger, line)
                self.assertIsNone(rx.search(line))
            if name == "pathcompare_outcome":
                self.assertIn(trigger, line)
                self.assertIsNotNone(rx.search(line))

    def test_outcomes_without_compare_lines_still_render(self):
        rep = run_lines("[10:00:00] [Server thread/INFO]: [PathCompareOutcome] bot=Bob to=1,2,3 reached=no detail=-")
        self.assertEqual(rep["pathcompare"]["outcomes"]["unjoined"], 1)
        self.assertIn("walk outcomes: n=1 reached=0", flr.render_md(rep))

    def test_spam_and_masking(self):
        lines = "\n".join("[10:00:%02d] [Server thread/WARN]: Mining attempt 1/3 out-of-reach at %d, 64, 2" % (i % 60, i)
                          for i in range(70))
        rep = run_lines(lines, spam_per_min=60)
        self.assertEqual(rep["overview"]["warn_patterns"][0]["count"], 70)
        self.assertEqual(rep["overview"]["spam"][0]["peak_per_min"], 70)
        self.assertIn("at N, N, N", rep["overview"]["spam"][0]["pattern"])

    def test_since_until_and_bot_filter(self):
        text = """
[10:00:00] [mod-command-skill-1/INFO]: Skill 'woodcut' starting for bot Jake on thread mod-command-skill-1
[10:05:00] [mod-command-skill-2/INFO]: Skill 'woodcut' starting for bot Bob on thread mod-command-skill-2
[10:10:00] [mod-command-skill-1/INFO]: Woodcut unified: mined 2 logs for base 1, 2, 3
"""
        rep = run_lines(text, since=flr.parse_hms("10:04"), only_bot="Jake")
        self.assertEqual(rep["overview"]["lines"], 2)
        self.assertEqual(list(rep["bots"]), ["Jake"])
        self.assertEqual(rep["bots"]["Jake"]["logs_mined"], 2)

    def test_gz_and_partial_last_line(self):
        d = tempfile.mkdtemp()
        try:
            gz = os.path.join(d, "a.log.gz")
            with gzip.open(gz, "wt") as fh:
                fh.write("[10:00:00] [Server thread/INFO]: Bob joined the game\n")
            plain = os.path.join(d, "latest.log")
            with open(plain, "w") as fh:
                fh.write("[10:00:01] [Server thread/INFO]: one\n[10:00:02] [Server thread/INFO]: half-writ")
            rep = flr.analyze([gz, plain])
            self.assertEqual(rep["overview"]["lines"], 2)  # partial line left for the next read
        finally:
            shutil.rmtree(d)


class PruneTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.inst_root = os.path.join(self.d, "instances")
        self.arch = os.path.join(self.d, "archive")
        self.now = time.time()
        for inst in ("mcp-sandbox", "1.21.11"):
            for sub in ("logs", "screenshots", "crash-reports"):
                os.makedirs(os.path.join(self.inst_root, inst, "minecraft", sub))
        self.config = {
            "instances_root": self.inst_root, "archive_root": self.arch, "min_age_hours": 24,
            "instances": {
                "mcp-sandbox": {"logs": {"archive_after_days": 3, "delete_raw_after_days": 14},
                                "screenshots": {"dirs": ["screenshots", "screenshots/mcp"],
                                                "archive_after_days": 7, "delete_after_days": 30}},
                "1.21.11": {"logs": {"archive_after_days": 14, "delete_raw_after_days": 90}, "screenshots": None},
            }}

    def tearDown(self):
        shutil.rmtree(self.d)

    def mk(self, rel, days_old, text="[10:00:00] [Server thread/INFO]: hello\n"):
        p = os.path.join(self.d, rel)
        if not os.path.isdir(os.path.dirname(p)):
            os.makedirs(os.path.dirname(p))
        if p.endswith(".gz"):
            with gzip.open(p, "wt") as fh:
                fh.write(text)
        else:
            with open(p, "w") as fh:
                fh.write(text)
        t = self.now - days_old * 86400
        os.utime(p, (t, t))
        return p

    def ops(self, actions):
        return sorted((a["op"], os.path.basename(a["src"])) for a in actions)

    def test_age_rules(self):
        sb = "instances/mcp-sandbox/minecraft/"
        nirn = "instances/1.21.11/minecraft/"
        self.mk(sb + "logs/old.log.gz", 4)
        self.mk(sb + "logs/young.log.gz", 2)
        self.mk(sb + "logs/latest.log", 40)
        self.mk(sb + "logs/notes.txt", 40)
        self.mk(nirn + "logs/nirn-old.log.gz", 20)
        self.mk(nirn + "logs/nirn-mid.log.gz", 10)
        self.mk(nirn + "screenshots/n.png", 400)
        self.mk(sb + "screenshots/s-old.png", 8)
        self.mk(sb + "screenshots/s-new.png", 6)
        self.mk(sb + "screenshots/mcp/m-old.png", 9)
        self.mk(sb + "crash-reports/crash.log", 400)
        self.mk("archive/mcp-sandbox/raw/ancient.log.gz", 15)
        self.mk("archive/mcp-sandbox/raw/recent.log.gz", 13)
        self.mk("archive/mcp-sandbox/screenshots/gone.png", 31)
        actions, refusals, _ = prune.plan(self.config, now=self.now)
        self.assertEqual(self.ops(actions), [
            ("archive_log", "nirn-old.log.gz"), ("archive_log", "old.log.gz"),
            ("delete", "ancient.log.gz"), ("delete", "gone.png"),
            ("move", "m-old.png"), ("move", "s-old.png")])
        self.assertEqual(refusals, [])

    def test_24h_floor_beats_zero_retention(self):
        self.config["instances"]["mcp-sandbox"]["logs"]["archive_after_days"] = 0
        self.mk("instances/mcp-sandbox/minecraft/logs/fresh.log.gz", 0.5)
        actions, _, _ = prune.plan(self.config, now=self.now)
        self.assertEqual(actions, [])

    def test_symlink_and_outside_refused(self):
        outside = self.mk("elsewhere/secret.log.gz", 30)
        os.symlink(outside, os.path.join(self.inst_root, "mcp-sandbox", "minecraft", "logs", "link.log.gz"))
        actions, refusals, guard = prune.plan(self.config, now=self.now)
        self.assertEqual(actions, [])
        self.assertTrue(any("symlink" in r for r in refusals))
        with self.assertRaises(prune.Refused):
            guard.check(outside)
        with self.assertRaises(prune.Refused):
            guard.check(self.mk("instances/mcp-sandbox/minecraft/crash-reports/c.log", 30))
        with self.assertRaises(prune.Refused):
            guard.check(os.path.join(self.inst_root, "mcp-sandbox", "minecraft", "logs", "latest.log"))

    def test_archive_overlapping_instance_refused(self):
        self.config["archive_root"] = os.path.join(self.inst_root, "mcp-sandbox", "minecraft", "arch")
        with self.assertRaises(prune.Refused):
            prune.plan(self.config, now=self.now)

    def test_dry_run_changes_nothing_apply_acts(self):
        src = self.mk("instances/mcp-sandbox/minecraft/logs/old.log.gz", 4)
        doomed = self.mk("archive/mcp-sandbox/raw/ancient.log.gz", 15)
        actions, _, guard = prune.plan(self.config, now=self.now)
        out = io.StringIO()
        self.assertEqual(prune.execute(actions, guard, apply=False, out=out), 0)
        self.assertTrue(os.path.exists(src))
        self.assertTrue(os.path.exists(doomed))
        self.assertFalse(os.path.exists(os.path.join(self.arch, "mcp-sandbox", "digests")))
        self.assertEqual(prune.execute(actions, guard, apply=True, out=out), 0)
        self.assertFalse(os.path.exists(src))
        self.assertFalse(os.path.exists(doomed))
        real_arch = os.path.realpath(self.arch)
        self.assertTrue(os.path.exists(os.path.join(real_arch, "mcp-sandbox", "raw", "old.log.gz")))
        with open(os.path.join(real_arch, "mcp-sandbox", "digests", "old.log.gz.json")) as fh:
            self.assertEqual(json.load(fh)["overview"]["lines"], 1)
        self.assertTrue(os.path.exists(os.path.join(real_arch, "mcp-sandbox", "digests", "old.log.gz.md")))

    def test_digest_failure_keeps_log(self):
        src = self.mk("instances/mcp-sandbox/minecraft/logs/old.log.gz", 4)
        actions, _, guard = prune.plan(self.config, now=self.now)
        orig = prune.write_digest

        def boom(*a):
            raise RuntimeError("digest broke")
        prune.write_digest = boom
        try:
            errors = prune.execute(actions, guard, apply=True, out=io.StringIO())
        finally:
            prune.write_digest = orig
        self.assertEqual(errors, 1)
        self.assertTrue(os.path.exists(src))

    def test_main_exit_code_and_summary(self):
        self.mk("instances/mcp-sandbox/minecraft/logs/old.log.gz", 4)
        cfg = os.path.join(self.d, "cfg.json")
        with open(cfg, "w") as fh:
            json.dump(self.config, fh)
        buf = io.StringIO()
        old = sys.stdout
        sys.stdout = buf
        try:
            rc = prune.main(["--config", cfg])
        finally:
            sys.stdout = old
        self.assertEqual(rc, 0)
        self.assertIn("dry-run: archive 1 logs", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
