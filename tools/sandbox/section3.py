#!/usr/bin/env python3
"""Field checklist section 3 (Phases 6ap, 6at, 6aw, 6an) in the mcp-sandbox 'Wall Test' save (commands on). Game must be loaded.

Stages: leash, bases, combat, ffire, retrieve_me, debug. Each prints the matching log lines.
"""
import json, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
BOT = "Wally"


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


def bot_uuid_ints(name):
    """The entity UUID as Minecraft's int array, read with /data get."""
    start = len(lines())
    chat(f"/data get entity {name} UUID", 3)
    for line in lines()[start:]:
        m = re.search(r"\[I;\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\]", line)
        if m:
            return ",".join(m.groups())
    return None


def prepare():
    cmd(cmd="enter_control_mode")
    cmd(cmd="press_key", key="escape")
    time.sleep(2)
    chat("/gamemode creative")
    chat(f"/bot spawn {BOT} admin", 8)
    chat(f"/tp {BOT} 60 80 60", 3)
    chat("/tp @s 63 80 60", 3)
    chat(f"/bot stop {BOT}")
    # a bot keeps its health across despawn/spawn: a low-health bot flees instead of fighting
    chat(f"/effect give {BOT} instant_health 1 10")


stage = sys.argv[1] if len(sys.argv) > 1 else "leash"
start = len(lines())
if stage == "leash":
    prepare()
    u = bot_uuid_ints(BOT)
    print("wally uuid ints", u)
    chat(f"/summon minecraft:pig 61 80 60 {{leash:{{UUID:[I;{u}]}}}}", 4)
    print(cmd(cmd="send_chat", text=f"/data get entity @e[type=minecraft:pig,limit=1,sort=nearest] leash"))
    chat(f"/bot leash release {BOT}", 4)
    show(start, r"leash|Porky|summon|Summoned|lead")
elif stage == "bases":
    prepare()
    chat("/bot base set S3Base", 3)
    chat("/bot base owner S3Base server", 3)
    chat("/bot base rename S3Base S3Renamed", 3)
    chat("/bot base list", 3)
    show(start, r"base|Base|owner")
elif stage == "combat":
    prepare()
    chat("/time set 18000", 2)
    for dx in (3, 4, 5):
        chat(f"/summon minecraft:zombie {60 + dx} 80 {60 + dx}", 1)
    chat("/gamemode spectator", 25)
    show(start, r"combat-callout|COMBAT|lots of them")
    chat("/kill @e[type=minecraft:zombie,distance=..30]", 2)
    chat("/time set 1000")
elif stage == "ffire":
    prepare()
    chat("/gamemode survival", 2)
    chat(f"/damage @s 2 minecraft:player_attack by {BOT}", 4)
    chat("/gamemode creative", 2)
    show(start, r"combat-callout|apolog|hit player")
elif stage == "retrieve_me":
    prepare()
    chat("/gamerule keep_inventory false")
    chat("/gamemode survival", 2)
    chat("/clear @s")
    chat("/give @s oak_log 20")
    chat("/tp @s 100 80 100", 3)
    chat("/kill @s", 4)
    print(cmd(cmd="screen_buttons")[:400])
    print(cmd(cmd="click_button", label="Respawn"))
    time.sleep(6)
    chat("/gamemode creative", 2)
    chat("/effect give Wally saturation 5 10", 2)
    chat(f"/bot retrieve {BOT} me", 45)
    show(start, r"\[Retrieve\]|\[DeathRecord\]|coming back|staying|No point|Nothing to fetch")
elif stage == "debug":
    prepare()
    chat("/bot testgear", 3)
    chat("/bot debug on", 3)
    chat(f"/bot test_chat_message {BOT}", 5)
    chat("/bot debug off", 3)
    chat(f"/bot test_chat_message {BOT}", 3)
    show(start, r"testgear|test_chat_message|Debug set|Incorrect|Unknown")
