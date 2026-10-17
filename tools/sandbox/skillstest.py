#!/usr/bin/env python3
"""Skills checks in the mcp-sandbox 'Wall Test' save (commands on). Game must be loaded.

Stages:
  ranged   Wally woodcuts a planted tree by day, a helmeted bogged 13 blocks away shoots him: expect the task pause
  hit      deterministic: during woodcut, a NoAI bogged 12 blocks away and `/damage Wally 1 minecraft:arrow by <bogged>`:
           expect `paused task for ranged hit` (10-16 blocks; closer, the older nearby-threat pause fires first)
  cancel   Wally woodcuts with no axe, /bot stop mid-mine: expect an INFO cancel line, no ERROR, pathFailures=0
"""
import json, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
BOT = "Wally"
X, Y, Z = 80, 65, 200  # flat test pad


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=30).read().decode()
    except Exception as e:  # noqa: BLE001
        return f"ERR {e}"


def chat(text, wait=3):
    cmd(cmd="send_chat", text=text)
    time.sleep(wait)


def lines():
    return open(LOG, errors="replace").read().splitlines()


def show(start, pattern):
    for line in lines()[start:]:
        if re.search(pattern, line):
            print(line[:220])


def pad_with_tree():
    cmd(cmd="enter_control_mode")
    cmd(cmd="press_key", key="escape")
    time.sleep(2)
    chat("/gamemode creative")
    chat("/gamerule doDaylightCycle false")
    chat("/gamerule doMobSpawning false")
    chat(f"/fill {X-16} {Y} {Z-16} {X+16} {Y+20} {Z+16} air")
    chat(f"/fill {X-16} {Y-1} {Z-16} {X+16} {Y-1} {Z+16} grass_block")
    chat(f"/fill {X+3} {Y} {Z} {X+3} {Y+4} {Z} oak_log")
    chat(f"/fill {X+1} {Y+3} {Z-2} {X+5} {Y+5} {Z+2} oak_leaves[persistent=true] keep")
    chat(f"/bot spawn {BOT} admin", 8)
    chat(f"/tp {BOT} {X}.5 {Y} {Z}.5")
    chat(f"/clear {BOT}")
    chat("/gamemode spectator")
    chat(f"/tp @s {X} {Y+30} {Z}")


stage = sys.argv[1] if len(sys.argv) > 1 else "ranged"
start = len(lines())
if stage == "ranged":
    # Woodcut refuses to start at night, so run by day with a helmet on the bogged (no sunburn).
    # /summon with NBT skips mob setup in 1.21.11, so the bow must be given explicitly or it melees.
    pad_with_tree()
    chat("/time set 1000")
    chat(f"/effect give {BOT} instant_health 1 5")
    chat(f"/bot skill woodcut {BOT}", 6)
    chat(f'/summon minecraft:bogged {X-13} {Y} {Z} {{equipment:{{mainhand:{{id:"minecraft:bow",count:1}},head:{{id:"minecraft:leather_helmet",count:1}}}}}}', 25)
    show(start, r"paused task for ranged hit|did not pause task|pause requested|being shot|resume|Woodcut (skill|end)|combat-callout|died")
    chat("/kill @e[type=minecraft:bogged]", 2)
    chat("/time set 1000")
elif stage == "hit":
    pad_with_tree()
    chat("/time set 1000")
    chat(f"/bot skill woodcut {BOT}", 5)
    chat(f"/execute at {BOT} run summon minecraft:bogged ~12 ~ ~ {{NoAI:1b}}", 0.3)
    chat(f"/damage {BOT} 1 minecraft:arrow by @e[type=minecraft:bogged,limit=1]", 10)
    show(start, r"paused task for ranged hit|did not pause task|being shot")
    chat("/kill @e[type=minecraft:bogged]", 2)
elif stage == "cancel":
    pad_with_tree()
    chat("/time set 1000")
    chat(f"/bot skill woodcut {BOT}", 9)
    chat(f"/bot stop {BOT}", 6)
    show(start, r"cancelled|Failed to mine|pathFailures=|end summary")
