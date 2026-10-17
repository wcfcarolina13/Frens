#!/usr/bin/env python3
"""Stuck-escape repro (1.1.262) in the mcp-sandbox 'Wall Test' save (commands on). Game must be loaded already.

Stages: setup | wall | clip | pen | lane | quit.  Each case builds on a floating grass platform so terrain never varies.
  wall  item 4: stone at head height beside the bot (cardinal). Old: no nudge, escape toward up.
  clip  item 1: stone at head height diagonal, short grass / leaf litter / snow layer on the four feet cells.
        Old: 'attempting escape movement toward up'. New: a side cell.
  pen   items 2+3: bot on a 1-high plank inside a closed 2-high fence ring (interior 3x3), home far away.
        Old: 'ALL 8 directions blocked'. New: 'quick-nudge drop-1'.
  lane  item 3: closed stone pocket, one open cell east, 2-high fence two cells east.
        Old: 'Stuck escape: moving east 2 block(s)' into the fence. New: 'blocked by Oak Fence ... (collision shape)'.
"""
import json, os, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
BOT = "Stucky"
X, Y, Z = 200, 110, 200  # platform floor is Y-1, bot feet at Y
STATE = os.path.join(os.environ.get("TMPDIR", "/tmp"), "frens_stucktest_state.json")


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=30).read().decode()
    except Exception as e:
        return f"ERR {e}"


def chat(t, wait=2):
    r = cmd(cmd="send_chat", text=t); time.sleep(wait); return r


def mark():
    return len(open(LOG, errors="replace").read().splitlines())


def log_from(m, pat):
    lines = open(LOG, errors="replace").read().splitlines()[m:]
    return [l[:240] for l in lines if re.search(pat, l)]


def platform():
    chat(f"/fill {X-6} {Y-1} {Z-6} {X+6} {Y+4} {Z+6} air")
    chat(f"/fill {X-6} {Y-2} {Z-6} {X+6} {Y-2} {Z+6} stone")
    chat(f"/fill {X-6} {Y-1} {Z-6} {X+6} {Y-1} {Z+6} grass_block")


def watch(m, secs, pat):
    end = time.time() + secs
    seen = []
    while time.time() < end:
        time.sleep(5)
        for l in log_from(m, pat):
            if l not in seen:
                seen.append(l); print(l)
    return seen


RESCUE = r"Stucky.*(escape|nudged|wall|suffocat|mining block|IN_WALL)|escape side cell"
RBSS = r"quick-nudge|Stuck escape|scaffold-escape|ReturnBaseStuck"

stage = sys.argv[1] if len(sys.argv) > 1 else "setup"
cmd(cmd="enter_control_mode")
cmd(cmd="press_key", key="escape"); time.sleep(1)

if stage == "setup":
    chat("/time set 1000"); chat("/gamerule doDaylightCycle false"); chat("/gamerule doMobSpawning false")
    chat("/gamerule advance_time false"); chat("/gamerule spawn_mobs false")  # 1.21.11 snake_case names
    chat(f"/tp @s {X} {Y+20} {Z-20}", 4)
    platform()
    chat(f"/bot spawn {BOT} admin", 12)
    chat(f"/bot stay {BOT}")
    chat("/bot base list", 3)
    print(log_from(max(0, mark() - 30), r"\[CHAT\]")[-15:])
elif stage in ("wall", "clip"):
    platform()
    chat(f"/bot stay {BOT}")
    if stage == "wall":
        chat(f"/setblock {X-1} {Y+1} {Z} stone")
        pos = f"{X}.12 {Y} {Z}.5"
    else:
        chat(f"/setblock {X-1} {Y+1} {Z-1} stone")
        chat(f"/setblock {X} {Y} {Z-1} short_grass")
        chat(f"/setblock {X-1} {Y} {Z} short_grass")
        chat(f"/setblock {X} {Y} {Z+1} leaf_litter")
        chat(f"/setblock {X+1} {Y} {Z} snow[layers=1]")
        pos = f"{X}.12 {Y} {Z}.12"
    print(cmd(cmd="blocks", x=X, y=Y, z=Z, r=2, dy=2)[:900])
    m = mark()
    chat(f"/tp {BOT} {pos}", 1)
    watch(m, 20, RESCUE)
    print(cmd(cmd="bot_state", name=BOT)[:260])
    chat(f"/tp {BOT} {X+4}.5 {Y} {Z+4}.5")
elif stage == "pen":
    platform()
    # closed 2-high fence ring at distance 2 (interior 3x3), plank under the bot
    chat(f"/fill {X-2} {Y} {Z-2} {X+2} {Y+1} {Z+2} oak_fence")
    chat(f"/fill {X-1} {Y} {Z-1} {X+1} {Y+1} {Z+1} air")
    chat(f"/setblock {X} {Y} {Z} oak_planks")
    chat(f"/tp {BOT} {X}.5 {Y+1} {Z}.5", 3)
    print(cmd(cmd="blocks", x=X, y=Y, z=Z, r=3, dy=1)[:900])
    m = mark()
    chat(f"/bot return {BOT}", 1)
    watch(m, 45, RBSS)
    print(cmd(cmd="bot_state", name=BOT)[:260])
    chat(f"/bot stay {BOT}")
elif stage == "lane":
    # own bot: ReturnBaseStuckService keeps per-bot counters in memory until arrival at base
    LB = "Laney"
    chat(f"/bot stay {BOT}"); chat(f"/tp {BOT} {X+5}.5 {Y} {Z+5}.5")
    platform()
    # closed stone pocket: home is north-west, so the bot presses the west wall. One open cell east,
    # then a 2-high fence two cells east (backup/sidestep tests cells 2-4 out with isPathClear).
    chat(f"/fill {X-1} {Y} {Z-1} {X+3} {Y+2} {Z+1} stone")
    chat(f"/fill {X} {Y} {Z} {X+1} {Y+1} {Z} air")
    chat(f"/fill {X+2} {Y} {Z} {X+2} {Y+1} {Z} oak_fence")
    chat(f"/bot spawn {LB} admin", 12)
    chat(f"/bot stay {LB}")
    chat(f"/tp {LB} {X}.5 {Y} {Z}.5", 3)
    print(cmd(cmd="blocks", x=X, y=Y, z=Z, r=3, dy=1)[:900])
    m = mark()
    chat(f"/bot return {LB}", 1)
    watch(m, 30, RBSS)
    print(cmd(cmd="bot_state", name=LB)[:260])
    chat(f"/bot despawn {LB}", 3)
elif stage == "quit":
    chat(f"/bot despawn {BOT}", 5)
    chat("/gamerule doDaylightCycle true")
    print(cmd(cmd="save_and_quit"))
