#!/usr/bin/env python3
"""Swapped pathfinder A/B in the mcp-sandbox. Run once per round in a fresh copy of 'New World' (commands on), game loaded.

  abtest.py 1   round 1: Wally + Trainee unified, Smokey + Abby Baritone
  abtest.py 2   round 2: the same bots on the same spots with the finders swapped
Each round: day locked, mobs off, stone axe + bread, pathcompare on, woodcut on all four for MINUTES.
A pause screen is closed if one opens (it freezes the server). Report afterwards: tools/logs/frens_log_report.py latest.log --since <printed start time>.
"""
import json, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
MINUTES = 11
SPOTS = {"Wally": (70, 172), "Smokey": (78, 190), "Trainee": (74, 208), "Abby": (80, 240)}
UNIFIED_FIRST = {"Wally", "Trainee"}


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=60).read().decode()
    except Exception as e:  # noqa: BLE001
        return f"ERR {e}"


def chat(text, wait=2):
    cmd(cmd="send_chat", text=text)
    time.sleep(wait)


rnd = int(sys.argv[1]) if len(sys.argv) > 1 else 1
cmd(cmd="enter_control_mode")
cmd(cmd="press_key", key="escape")
time.sleep(2)
chat("/gamemode creative")
chat("/time set 1000")
for rule in ("advance_time false", "doDaylightCycle false", "spawn_mobs false", "doMobSpawning false"):
    chat(f"/gamerule {rule}", 1)
chat("/kill @e[type=#minecraft:skeletons]", 1)
chat("/kill @e[type=minecraft:zombie]", 1)
chat("/kill @e[type=minecraft:creeper]", 1)
chat("/kill @e[type=minecraft:spider]", 1)
chat("/bot config pathcompare on")
for bot in SPOTS:
    chat(f"/bot spawn {bot} admin", 8)
for bot, (x, z) in SPOTS.items():
    unified = (bot in UNIFIED_FIRST) == (rnd == 1)
    chat(f"/bot config pathfinder {'unified' if unified else 'baritone'} {bot}")
    chat(f"/spreadplayers {x} {z} 0 3 false {bot}", 3)
    chat(f"/clear {bot}", 1)
    chat(f"/give {bot} stone_axe 1", 1)
    chat(f"/give {bot} bread 16", 1)
    print(bot, "unified" if unified else "baritone", cmd(cmd="bot_state", name=bot)[:160])
chat("/gamemode spectator")
chat("/tp @s 75 120 205")
for bot in SPOTS:
    chat(f"/bot skill woodcut {bot}", 1)
print("start", time.strftime("%H:%M:%S"), flush=True)
# A pause screen freezes the integrated server while bot workers keep logging failures (round 2 on 2026-10-17
# lost 8.7 minutes that way). Watch for it and close it; the report also flags "SERVER PAUSED".
log_mark = len(open(LOG, errors="replace").read().splitlines())
deadline = time.time() + MINUTES * 60
while time.time() < deadline:
    time.sleep(10)
    new = open(LOG, errors="replace").read().splitlines()[log_mark:]
    log_mark += len(new)
    if any("Saving and pausing game" in line for line in new):
        print("paused at", time.strftime("%H:%M:%S"), "screen", cmd(cmd="screen_info")[:120], flush=True)
        cmd(cmd="enter_control_mode")
        cmd(cmd="press_key", key="escape")
        deadline += 10  # roughly the paused time
for bot in SPOTS:
    chat(f"/bot stop {bot}", 1)
print("end", time.strftime("%H:%M:%S"))
