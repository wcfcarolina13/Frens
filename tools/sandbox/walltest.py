#!/usr/bin/env python3
"""Wall-off dusk test in the mcp-sandbox 'Wall Test' save (commands on). Game must be loaded already."""
import json, sys, time, urllib.request, re

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"

def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=30).read().decode()
    except Exception as e:
        return f"ERR {e}"

def chat(t, wait=3):
    r = cmd(cmd="send_chat", text=t); time.sleep(wait); return r

def log_from(mark, pat):
    lines = open(LOG, errors="replace").read().splitlines()[mark:]
    return [l[:220] for l in lines if re.search(pat, l)]

def mark():
    return len(open(LOG, errors="replace").read().splitlines())

stage = sys.argv[1] if len(sys.argv) > 1 else "setup"
BOT = "Wally"
import os
STATE = os.path.join(os.environ.get("TMPDIR", "/tmp"), "frens_walltest_state.json")

if stage == "setup":
    cmd(cmd="enter_control_mode")
    print(cmd(cmd="press_key", key="escape")); time.sleep(2)
    chat("/time set 1000")
    chat("/gamerule doDaylightCycle false")
    chat("/spreadplayers 80 80 0 1 false @s", 5)
    chat(f"/bot spawn {BOT} admin", 12)
    x, y, z = 80, 65, 83  # fixed site from the first run (surface at 80,80 spread)
    print("bot at", x, y, z)
    cx, cz = x + 3, z
    # clear a 7x4x7 box, floor, overhang: roof 3x3 at y+2, west/south/east walls, cell (cx,y,cz) open to north only
    chat(f"/fill {cx-3} {y} {cz-3} {cx+3} {y+3} {cz+3} air")
    chat(f"/fill {cx-3} {y-1} {cz-3} {cx+3} {y-1} {cz+3} stone")
    chat(f"/fill {cx-1} {y+2} {cz-1} {cx+1} {y+2} {cz+1} stone")
    chat(f"/fill {cx-1} {y} {cz-1} {cx-1} {y+1} {cz+1} stone")
    chat(f"/fill {cx-1} {y} {cz+1} {cx+1} {y+1} {cz+1} stone")
    chat(f"/fill {cx+1} {y} {cz-1} {cx+1} {y+1} {cz} stone")
    chat(f"/tp {BOT} {x}.5 {y} {z}.5", 3)
    chat(f"/clear {BOT} cobblestone")
    chat(f"/give {BOT} cobblestone 8")
    chat(f"/bot base owner Spawn server")
    chat("/gamemode spectator")
    chat(f"/bot stop {BOT}")
    chat(f"/tp @s {x+70} {y+15} {z}")
    open(STATE, "w").write(json.dumps({"x": x, "y": y, "z": z, "cx": cx, "cz": cz}))
    print(cmd(cmd="blocks", x=cx, y=y, z=cz, r=2, dy=2)[:1500])
elif stage == "dusk":
    s = json.loads(open(STATE).read())
    m = mark()
    cmd(cmd="press_key", key="escape")
    chat("/time set 13000", 60)
    for l in log_from(m, r"wall-off|shelter|Wally.*(cliff|dig-down|seeking)"):
        print(l)
    print(cmd(cmd="bot_state", name=BOT)[:300])
    print(cmd(cmd="blocks", x=s["cx"], y=s["y"], z=s["cz"], r=2, dy=2)[:1500])
elif stage == "dawn":
    s = json.loads(open(STATE).read())
    m = mark()
    chat("/time set 1200", 45)
    for l in log_from(m, r"wall-off|shelter|broke free|Wally.*break"):
        print(l)
    print(cmd(cmd="bot_state", name=BOT)[:300])
    print(cmd(cmd="blocks", x=s["cx"], y=s["y"], z=s["cz"], r=2, dy=2)[:1500])
elif stage == "quit":
    chat(f"/bot despawn {BOT}", 5)
    chat("/gamerule doDaylightCycle true")
    print(cmd(cmd="save_and_quit"))
