#!/usr/bin/env python3
"""Multi-bot forest woodcut run in the mcp-sandbox (commands on, game loaded). Day locked, mobs off.

  forest.py X Y Z MINUTES BOT[:kit] [BOT[:kit] ...]
    kit: none (empty inventory, the default), axe (stone axe + bread), tools (stone axe, pickaxe, shovel + bread)
Spawns each bot (admin) 3 blocks apart around X Y Z, starts woodcut on all, keeps the world unpaused for MINUTES
(closes any screen that opens, a screen pauses the integrated server), then stops the bots and prints the start time
for `tools/logs/frens_log_report.py latest.log --since <start>` and the claim/terrain/self-conflict lines.
"""
import json, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
KITS = {"none": [], "axe": ["stone_axe", "bread 16"], "tools": ["stone_axe", "stone_pickaxe", "stone_shovel", "bread 16"]}


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=30).read().decode()
    except Exception as e:  # noqa: BLE001
        return f"ERR {e}"


def chat(text, wait=2):
    cmd(cmd="send_chat", text=text)
    time.sleep(wait)


X, Y, Z, MIN = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4])
bots = [(a.split(":") + ["none"])[:2] for a in sys.argv[5:]]
cmd(cmd="enter_control_mode")
cmd(cmd="press_key", key="escape")
for g in ("advance_time false", "spawn_mobs false"):
    chat(f"/gamerule {g}")
chat("/time set 1000")
chat("/gamemode spectator")
chat(f"/tp @s {X} {Y + 25} {Z}")
start_line = len(open(LOG, errors="replace").read().splitlines())
for i, (name, kit) in enumerate(bots):
    chat(f"/bot spawn {name} admin", 6)
    chat(f"/tp {name} {X + 3 * i} {Y} {Z}", 2)
    chat(f"/clear {name}")
    for item in KITS[kit]:
        chat(f"/give {name} {item}", 1)
start = time.strftime("%H:%M:%S")
for name, _ in bots:
    chat(f"/bot skill woodcut {name}", 2)
print("start", start, flush=True)
end = time.time() + MIN * 60
while time.time() < end:
    time.sleep(10)
    info = cmd(cmd="screen_info")
    if '"screen":null' not in info and '"screen": null' not in info:
        cmd(cmd="press_key", key="escape")
        print("closed screen", info[:120], flush=True)
for name, _ in bots:
    chat(f"/bot stop {name}", 1)
lines = open(LOG, errors="replace").read().splitlines()[start_line:]
PAT = (r"claimed by|sharing it|selected tree target|terrain move|unified plan truncated|now forbidden|SELF_CONFLICT"
       r"|Woodcut end summary|Refusing path|timed out|ALL 8 directions blocked|died|SERVER PAUSED")
for l in lines:
    if re.search(PAT, l):
        print(l[:240])
print("start", start)
