#!/usr/bin/env python3
"""Leash checks (Phases 6at, 6ap-3) in the mcp-sandbox, commands on, game loaded. Needs the fork's `keybind` command.

Stages (bot Lea on a tame saddled horse on a flat stone pad at X Y Z, the player standing 3 blocks away looking at Lea):
  (the player rides a second horse 3 blocks west, tag ph: the key acts on a mounted bot you look at or ride beside)
  open     no fence near: leash key -> Lea dismounts and holds the horse. Expect `saved mount ... (HELD`-ish lines,
           '/bot leash' replies, then `/bot leash release Lea` -> `[leash-release] Lea let go of 1 lead(s)`, `(RELEASED)`
  fence    an oak fence 2 blocks from the horse: leash key -> horse tied. Expect `[leash-target] Lea cleared leash target
           ... after tying to a fence` and `(TIED_TO_FENCE)`
  holdtie  open without the release, then a fence 2 blocks from Lea and `/bot leash Lea`: expect `[leash-target] Lea
           cleared leash target ... after tying to a fence` and `(TIED_TO_FENCE)` (6ap-3)
  state    print the horse's leash holder and Lea's inventory (run after a reload)
"""
import json, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
BOT = "Lea"
X, Y, Z = 140, 100, 140
PAT = r"leash|Leash|lead\(s\)|TIED_TO_FENCE|RELEASED|saved mount|dismount|tether|Mount"


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=30).read().decode()
    except Exception as e:  # noqa: BLE001
        return f"ERR {e}"


def chat(t, wait=2):
    r = cmd(cmd="send_chat", text=t)
    time.sleep(wait)
    return r


def mark():
    return len(open(LOG, errors="replace").read().splitlines())


def show(m, pat=PAT):
    for l in open(LOG, errors="replace").read().splitlines()[m:]:
        if re.search(pat, l) and "VoicedDialogue" not in l and "RideSyncTick" not in l:
            print(l[:230])


def pad(fence):
    chat(f"/fill {X-8} {Y} {Z-8} {X+8} {Y+5} {Z+8} air")
    chat(f"/fill {X-8} {Y-1} {Z-8} {X+8} {Y-1} {Z+8} stone")
    if fence:
        chat(f"/setblock {X+2} {Y} {Z} oak_fence")
    chat(f"/bot spawn {BOT} admin", 8)
    chat(f"/tp {BOT} {X} {Y} {Z}")
    chat(f"/kill @e[type=minecraft:horse,distance=..40]", 1)
    chat(f"/kill @e[type=minecraft:item,distance=..40]", 1)
    chat(f'/summon minecraft:horse {X} {Y} {Z} {{Tame:1b,SaddleItem:{{id:"minecraft:saddle",count:1}},'
         f'equipment:{{saddle:{{id:"minecraft:saddle",count:1}}}}}}', 2)
    chat(f"/give {BOT} lead 2")
    chat(f"/ride {BOT} mount @e[type=minecraft:horse,limit=1,sort=nearest]", 3)
    chat("/gamemode creative")
    # the key needs the player looking at the mounted bot or riding beside it: ride a second horse 3 west
    chat("/ride @s dismount")
    chat(f'/summon minecraft:horse {X-3} {Y} {Z} {{Tame:1b,Tags:["ph"],equipment:{{saddle:{{id:"minecraft:saddle",count:1}}}}}}', 2)
    chat("/ride @s mount @e[tag=ph,limit=1,sort=nearest]", 3)


stage = sys.argv[1] if len(sys.argv) > 1 else "open"
cmd(cmd="enter_control_mode")
cmd(cmd="press_key", key="escape")
time.sleep(1)
m = mark()
if stage == "holdtie":
    pad(False)
    print(cmd(cmd="keybind", id="key.frens.leash"))
    time.sleep(8)
    chat("/ride @s dismount")
    chat(f"/execute at {BOT} run setblock ~2 ~ ~ oak_fence", 2)
    chat(f"/bot leash {BOT}", 8)
    show(m)
    print(cmd(cmd="send_chat", text=f"/data get entity @e[type=minecraft:horse,tag=!ph,limit=1,sort=nearest] leash"))
    time.sleep(2)
    show(m, r"leash|Found no elements|has the following")
elif stage in ("open", "fence"):
    pad(stage == "fence")
    print(cmd(cmd="keybind", id="key.frens.leash"))
    time.sleep(8)
    if stage == "open":
        chat(f"/bot leash release {BOT}", 5)
    show(m)
    print(cmd(cmd="send_chat", text=f"/data get entity @e[type=minecraft:horse,tag=!ph,limit=1,sort=nearest] leash"))
    time.sleep(2)
    show(m, r"leash|Found no elements|has the following")
elif stage == "state":
    chat(f"/data get entity @e[type=minecraft:horse,tag=!ph,limit=1,sort=nearest] leash", 2)
    show(m, r"leash|Found no elements|has the following")
print(cmd(cmd="bot_state", name=BOT)[:400])
