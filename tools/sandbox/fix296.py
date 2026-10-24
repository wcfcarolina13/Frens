#!/usr/bin/env python3
"""1.1.296 checks in the mcp-sandbox (game loaded, control mode on, commands on). Save "Wall Test".

  fix296.py setup                     no day cycle, no mobs, time 1000, creative
  fix296.py house <x> <z> [roof|wall|flat] [y] join check inside a plank house that is NOT a registered base:
                                      a 13x13 oak_planks shell, 2-high interior, a wooden door in the south wall,
                                      a stepped plank roof over the 2-high ceiling (oak_stairs right over the bot),
                                      Wally in the centre. `wall`: a stone cliff right against the west wall.
                                      `flat`: no stepped roof (plain 1-thick ceiling). Then /bot despawn Wally,
                                      the player 70 blocks east, /time set 1000, /bot spawn Wally admin.
                                      Prints the join lines and every block that changed in the house.
                                      Old jar: `trapped on join` + `generic break-free` = premise true.
                                      1.1.296: `join enclosure suppressed: inside player-built structure`, 0 changed.
                                      y defaults to 230 (above any mountain). A house with natural terrain more than
                                      8 blocks above its roof (y 100 under a hill) is outside the depth gate by design.
  fix296.py deep <x> <z>              the same join, but in a stone-brick room with a wooden door, feet 20+ blocks
                                      below the surface. Expect the old behaviour (not suppressed).
  fix296.py sunset                    sunsettest.py (save "Sunset Test") with day numbers past the save's own, then
                                      the 'Sunset segment' lines: status=, reason=, ms=, manhattan= and lines per second.
                                      sunsettest's PASS/FAIL is case sensitive: that save's bots are 'wally'/'smokey'.
  fix296.py flood [sec]               log lines per second over the last <sec> s and "Can't keep up" count
  fix296.py shot <name>               save a screenshot to the run scratch folder
Flashback: tools/sandbox/rec.py start / stop frens_1_1_296_<topic>.
"""
import base64, json, re, sys, time, urllib.request
from collections import Counter

API = "http://127.0.0.1:9876/api/cmd"
MC = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft"
LOG = MC + "/logs/latest.log"
OUT = "/Users/roti/AI-Player-checkpoint/.claude/worktrees/auto-1-1-296/.superpowers/sdd/run-1.1.296/sandbox"
PLAYER = "RotiWokeman"
BOT = "Wally"
JOIN = (r"trapped on join|generic break-free|join enclosure|enclosed on join|linger:|break-free|broke free|"
        r"Shelter|shelter|MINING_STARTED|Spawned new bot Wally|restored.*Wally|Wally.*(restor|position)")


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=60).read().decode()
    except Exception as e:  # noqa: BLE001
        return f"ERR {e}"


def chat(t, wait=2):
    r = cmd(cmd="send_chat", text=t)
    cmd(cmd="press_key", key="escape")  # an open chat screen pauses the integrated server
    time.sleep(wait)
    return r


def lines():
    return open(LOG, errors="replace").read().splitlines()


def mark():
    return len(lines())


def since(m, pat):
    return [l for l in lines()[m:] if re.search(pat, l)]


def show(m, pat, n=40):
    for l in since(m, pat)[:n]:
        print("  >", l[:260])


def state(bot):
    try:
        return json.loads(cmd(cmd="bot_state", name=bot))
    except Exception:  # noqa: BLE001
        return {}


def snapshot(cx, cy, cz):
    """Block chars over x/z -8..+8 and y cy-1..cy+7 (4 `blocks` calls, r=4 dy=4 around two y centres)."""
    grid = {}
    for ox in (-4, 4):
        for oz in (-4, 4):
            for yc in (cy + 3,):
                try:
                    d = json.loads(cmd(cmd="blocks", x=cx + ox, y=yc, z=cz + oz, r=4, dy=4))
                except Exception:  # noqa: BLE001
                    continue
                for lv in d.get("levels", []):
                    for iz, row in enumerate(lv["rows"]):
                        cells = row.replace("@", "").split(" ") if " " in row else list(row)
                        cells = [c for c in cells if c != ""]
                        for ix, c in enumerate(cells):
                            grid[(cx + ox - 4 + ix, lv["y"], cz + oz - 4 + iz)] = c
    return grid


def diff(a, b):
    return sorted((k, a[k], b.get(k)) for k in a if k in b and a[k] != b[k])


def build_house(x, y, z, variant):
    """Floor y-1, interior y..y+1, ceiling y+2. Shell x/z +-6. Door in the south wall at x, z+6."""
    chat(f"/fill {x-20} {y-4} {z-20} {x+20} {y+10} {z+20} air", 1)
    # open sky over the house: natural terrain above y+10 (a hillside) puts the bot >8 below the heightmap
    chat(f"/fill {x-10} {y+11} {z-10} {x+10} {y+40} {z+10} air", 1)
    chat(f"/fill {x-10} {y+41} {z-10} {x+10} {y+70} {z+10} air", 1)
    chat(f"/fill {x-20} {y-4} {z-20} {x+20} {y-1} {z+20} dirt", 1)
    chat(f"/fill {x-20} {y-1} {z-20} {x+20} {y-1} {z+20} grass_block", 1)
    chat(f"/fill {x-6} {y-1} {z-6} {x+6} {y+2} {z+6} oak_planks", 1)
    chat(f"/fill {x-5} {y} {z-5} {x+5} {y+1} {z+5} air", 1)
    if variant != "flat":
        # stepped roof: rings rising toward the middle, stairs right over the bot
        chat(f"/fill {x-5} {y+3} {z-5} {x+5} {y+3} {z+5} oak_planks", 1)
        chat(f"/fill {x-3} {y+4} {z-3} {x+3} {y+4} {z+3} oak_planks", 1)
        chat(f"/fill {x-1} {y+5} {z-1} {x+1} {y+5} {z+1} oak_planks", 1)
        chat(f"/setblock {x} {y+2} {z} oak_stairs[facing=north,half=top]", 1)
    if variant == "wall":
        chat(f"/fill {x-12} {y} {z-12} {x-7} {y+8} {z+12} stone", 1)
    chat(f"/setblock {x} {y} {z+6} oak_door[facing=south,half=lower]", 1)
    chat(f"/setblock {x} {y+1} {z+6} oak_door[facing=south,half=upper]", 1)
    chat(f"/fill {x+68} {y-1} {z-2} {x+72} {y-1} {z+2} stone", 1)  # the player's pad, 70 blocks east


def build_deep(x, z, feet=30):
    """Stone from feet-4 to feet+25, a 9x9 stone-brick room (7x7x2 interior) with a wooden door in the south wall."""
    y = feet
    chat(f"/fill {x-8} {y-4} {z-8} {x+8} {y+12} {z+8} stone", 1)
    chat(f"/fill {x-8} {y+13} {z-8} {x+8} {y+25} {z+8} stone", 1)
    chat(f"/fill {x-4} {y-1} {z-4} {x+4} {y+2} {z+4} stone_bricks", 1)
    chat(f"/fill {x-3} {y} {z-3} {x+3} {y+1} {z+3} air", 1)
    chat(f"/setblock {x} {y} {z+4} oak_door[facing=south,half=lower]", 1)
    chat(f"/setblock {x} {y+1} {z+4} oak_door[facing=south,half=upper]", 1)
    chat(f"/fill {x} {y} {z+5} {x} {y+1} {z+6} air", 1)  # a pocket behind the door
    return y


def join_run(x, y, z, label, player_to):
    print(f"== {label}: Wally at {x},{y},{z}")
    if not state(BOT).get("pos"):
        chat(f"/bot spawn {BOT} admin", 12)
    chat(f"/tp {PLAYER} {x} {y} {z+9}", 3)
    chat(f"/tp {BOT} {x}.5 {y} {z}.5", 3)
    chat(f"/clear {BOT}", 1)
    chat(f"/give {BOT} stone_pickaxe 1", 1)  # a tool, so a break-free can actually mine
    time.sleep(12)  # past the /tp teleport grace and any stop grace
    print("  before despawn:", state(BOT).get("pos"))
    chat(f"/bot despawn {BOT}", 5)
    before = snapshot(x, y, z)
    chat(f"/tp {PLAYER} {player_to[0]} {player_to[1]} {player_to[2]}", 4)
    chat("/time set 1000", 1)
    m = mark()
    chat(f"/bot spawn {BOT} admin", 30)
    s = state(BOT)
    print("  after spawn:", s.get("pos"), "player:", state(PLAYER).get("pos"))
    after = snapshot(x, y, z)
    ch = diff(before, after)
    print(f"  changed cells: {len(ch)}")
    for k, a, b in ch[:30]:
        print("   ", k, a, "->", b)
    show(m, JOIN)
    print("COUNTS", json.dumps({
        "trapped_on_join": len(since(m, rf"{BOT} trapped on join")),
        "generic_break_free": len(since(m, rf"{BOT} generic break-free")),
        "suppressed_player_built": len(since(m, rf"{BOT} join enclosure suppressed: inside player-built")),
        "suppressed_other": len(since(m, rf"{BOT} join enclosure check suppressed")),
        "enclosed_night": len(since(m, rf"{BOT} enclosed on join")),
        "linger": len(since(m, r"linger:")),
        "changed_cells": len(ch),
        "errors": len(since(m, r"ERROR|Exception"))}))
    return m


st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    cmd(cmd="enter_control_mode")
    cmd(cmd="press_key", key="escape")
    for t in ("/gamemode creative", "/gamerule advance_time false", "/gamerule spawn_mobs false",
              "/time set 1000", "/weather clear"):
        chat(t, 1)
elif st == "house":
    x, z = int(sys.argv[2]), int(sys.argv[3])
    variant = sys.argv[4] if len(sys.argv) > 4 else "roof"
    y = int(sys.argv[5]) if len(sys.argv) > 5 else 230  # above any mountain, so the house is the surface
    chat(f"/tp {PLAYER} {x} {y+12} {z+10}", 5)
    build_house(x, y, z, variant)
    join_run(x, y, z, f"house {variant}", (x + 70, y, z))
elif st == "deep":
    x, z = int(sys.argv[2]), int(sys.argv[3])
    chat(f"/tp {PLAYER} {x} 120 {z}", 5)
    y = build_deep(x, z)
    # the player waits on a pad 70 blocks east, high up
    chat(f"/fill {x+68} 119 {z-2} {x+72} 119 {z+2} stone", 1)
    chat(f"/tp {PLAYER} {x} {y} {z+5}", 3)
    join_run(x, y, z, "deep stone-brick room", (x + 70, 120, z))
    print("  surface heightmap check: blocks above the room top", cmd(cmd="blocks", x=x, y=y + 26, z=z, r=0, dy=1)[:200])
elif st == "sunset":
    # sunsettest.py with days past any the save has already seen (the "Sunset Test" save was saved at dusk on
    # day 3134, so its minute-based days fell behind LAST_TRIGGERED_DAY and nothing fired), then the segment lines
    m = mark()
    src = open(__file__.replace("fix296.py", "sunsettest.py")).read().replace(
        "BASE_DAY = int(time.time() / 60) % 10000 * 3", "BASE_DAY = 50000 + int(time.time() / 60) % 10000 * 3")
    exec(compile(src, "sunsettest.py", "exec"), {"__name__": "__main__"})
    seg = since(m, r"Sunset segment for")
    print(f"segment lines: {len(seg)}")
    for l in seg[:30]:
        print("  >", l[:300])
    per_sec = Counter(re.match(r"\[(\d\d:\d\d:\d\d)", l).group(1) for l in seg if re.match(r"\[\d", l))
    print("max segment lines in one second:", max(per_sec.values()) if per_sec else 0)
    print("fields present on every line:", all(all(f in l for f in ("status=", "reason=", "ms=", "manhattan="))
                                               for l in seg) if seg else None)
    print("cant keep up since start:", len(since(m, r"Can't keep up")))
elif st == "flood":
    sec = int(sys.argv[2]) if len(sys.argv) > 2 else 60
    ls = lines()
    stamps = Counter()
    for l in ls[-40000:]:
        mm = re.match(r"\[(\d\d:\d\d:\d\d)", l)
        if mm:
            stamps[mm.group(1)] += 1
    top = stamps.most_common(5)
    print("busiest seconds:", top)
    print("cant keep up:", sum(1 for l in ls if "Can't keep up" in l))
elif st == "shot":
    r = cmd(cmd="screenshot")
    p = f"{OUT}/{sys.argv[2]}.png"
    b = r.split(",", 1)[1] if r.startswith("data:") else json.loads(r).get("image", "").split(",", 1)[-1]
    open(p, "wb").write(base64.b64decode(b))
    print(p)
else:
    print(__doc__)
