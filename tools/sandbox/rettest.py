#!/usr/bin/env python3
"""Death retrieval sandbox check for mcp-sandbox (a save with commands on, e.g. 'Wall Test'). Game must be loaded.

Stages:
  bot      Smokey dies holding 32 cobblestone, Wally is sent to fetch it; prints the [Retrieve] and travel lines
  lava     Smokey dies to lava damage, the retrieval must be refused
  keep     keepInventory on, the retrieval must be refused
"""
import json, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
PAT = r"\[Retrieve\]|\[DeathRecord\]|retriev|death spot|No point|Nothing to fetch|Respawning|arrived|coming back"


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


def mark():
    return len(open(LOG, errors="replace").read().splitlines())


def show(start):
    for line in open(LOG, errors="replace").read().splitlines()[start:]:
        if re.search(PAT, line):
            print(line[:220])


def setup(keep):
    cmd(cmd="enter_control_mode")
    cmd(cmd="press_key", key="escape")
    time.sleep(2)
    chat("/time set 1000")
    chat(f"/gamerule keep_inventory {'true' if keep else 'false'}")
    chat("/gamemode creative")
    chat("/bot spawn Smokey admin", 10)
    chat("/bot spawn Wally admin", 10)
    chat("/tp Smokey 100 80 100", 3)
    chat("/tp Wally 60 80 60", 3)
    chat("/clear Smokey")
    chat("/give Smokey cobblestone 32")
    chat("/effect give Wally saturation 5 10")


stage = sys.argv[1] if len(sys.argv) > 1 else "bot"
m = mark()
if stage == "bot":
    setup(False)
    chat("/kill Smokey", 8)
    chat("/bot retrieve Wally Smokey", 90)
elif stage == "lava":
    setup(False)
    chat("/damage Smokey 1000 minecraft:lava", 8)
    chat("/bot retrieve Wally Smokey", 8)
elif stage == "keep":
    setup(True)
    chat("/kill Smokey", 8)
    chat("/bot retrieve Wally Smokey", 8)
    chat("/gamerule keep_inventory false")
show(m)
print(cmd(cmd="bot_state", name="Wally")[:300])
