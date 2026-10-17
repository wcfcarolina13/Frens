#!/usr/bin/env python3
"""Shelter sandbox checks for the mcp-sandbox 'Wall Test' save (commands on). The game must be loaded.

Stages:
  seal     overhang next to Wally, 4 dirt + 4 cobblestone, no pickaxe, dusk: expect dirt in the seal cells
  dawn     daylight: expect the wall-off teardown
  valuables  same overhang, only diamond blocks and chests: expect blocks=0, no wall, dig-down refused
  dig      flat platform with no overhang, 4 dirt only, dusk: expect a capped dig-down bunker
  noblocks flat stone under open sky, empty inventory: expect dig-down skipped reason=no-seal-block, no hole
  scan X Y Z   print the block grid around a position
Reload check: after 'seal', save_and_quit (tools/sandbox/smoke.sh tail or the MCP), relaunch the same save,
then grep latest.log for 'shelter restored on join'.
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


def mark():
    return len(open(LOG, errors="replace").read().splitlines())


def show(start, pattern):
    for line in open(LOG, errors="replace").read().splitlines()[start:]:
        if re.search(pattern, line):
            print(line[:230])


def prepare():
    cmd(cmd="enter_control_mode")
    cmd(cmd="press_key", key="escape")
    time.sleep(2)
    chat("/time set 1000")
    chat("/gamerule doDaylightCycle false")
    chat("/gamemode creative")


def finish(x, y, z):
    chat("/gamemode spectator")
    chat(f"/bot stop {BOT}")
    chat(f"/tp @s {x + 70} {y + 15} {z}")


stage = sys.argv[1] if len(sys.argv) > 1 else "seal"
SHELTER = r"wall-off|shelter|dig-down|bunker|Wally.*(cliff|seeking)"

def overhang_with(items):
    """Stone overhang next to Wally (open to the north only), Wally given `items`, then dusk."""
    prepare()
    x, y, z = 80, 65, 83
    cx, cz = x + 3, z
    chat(f"/bot spawn {BOT} admin", 10)
    chat(f"/fill {cx-3} {y} {cz-3} {cx+3} {y+3} {cz+3} air")
    chat(f"/fill {cx-3} {y-1} {cz-3} {cx+3} {y-1} {cz+3} stone")
    chat(f"/fill {cx-1} {y+2} {cz-1} {cx+1} {y+2} {cz+1} stone")
    chat(f"/fill {cx-1} {y} {cz-1} {cx-1} {y+1} {cz+1} stone")
    chat(f"/fill {cx-1} {y} {cz+1} {cx+1} {y+1} {cz+1} stone")
    chat(f"/fill {cx+1} {y} {cz-1} {cx+1} {y+1} {cz} stone")
    chat(f"/tp {BOT} {x}.5 {y} {z}.5")
    chat(f"/clear {BOT}")
    for item in items:
        chat(f"/give {BOT} {item}")
    finish(x, y, z)
    m = mark()
    chat("/time set 13000", 60)
    show(m, SHELTER)
    print(cmd(cmd="blocks", x=cx, y=y, z=cz, r=2, dy=1)[:900])


if stage == "seal":
    overhang_with(["cobblestone 4", "dirt 4"])
elif stage == "valuables":
    # Only blocks a shelter must never use: expect blocks=0, no wall, and dig-down refused on the stone floor.
    overhang_with(["diamond_block 8", "chest 2"])
elif stage == "dawn":
    m = mark()
    cmd(cmd="press_key", key="escape")
    chat("/time set 1200", 45)
    show(m, SHELTER + r"|broke free|restored")
elif stage == "dig":
    prepare()
    x, y, z = 80, 65, 120
    chat(f"/fill {x-8} {y} {z-8} {x+8} {y+6} {z+8} air")
    chat(f"/fill {x-8} {y-4} {z-8} {x+8} {y-1} {z+8} dirt")
    chat(f"/fill {x-8} {y-1} {z-8} {x+8} {y-1} {z+8} grass_block")
    chat(f"/tp {BOT} {x}.5 {y} {z}.5")
    chat(f"/clear {BOT}")
    chat(f"/give {BOT} dirt 4")
    finish(x, y, z)
    m = mark()
    chat("/time set 13000", 60)
    show(m, SHELTER)
    print(cmd(cmd="bot_state", name=BOT)[:250])
    print(cmd(cmd="blocks", x=x, y=y - 2, z=z, r=1, dy=3)[:1200])
elif stage == "noblocks":
    # Flat stone, empty inventory, no overhang: expect `dig-down skipped reason=no-seal-block` and no hole.
    prepare()
    x, y, z = 80, 65, 150
    chat(f"/fill {x-8} {y} {z-8} {x+8} {y+15} {z+8} air")
    chat(f"/fill {x-8} {y+16} {z-8} {x+8} {y+30} {z+8} air")
    chat(f"/fill {x-8} {y-4} {z-8} {x+8} {y-1} {z+8} stone")
    chat(f"/tp {BOT} {x}.5 {y} {z}.5")
    chat(f"/clear {BOT}")
    finish(x, y, z)
    m = mark()
    chat("/time set 13000", 45)
    show(m, SHELTER)
    print(cmd(cmd="blocks", x=x, y=y - 2, z=z, r=1, dy=2)[:900])
elif stage == "scan":
    print(cmd(cmd="blocks", x=sys.argv[2], y=sys.argv[3], z=sys.argv[4], r=2, dy=3)[:1500])
