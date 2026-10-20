#!/usr/bin/env python3
"""1.1.276 checks in the mcp-sandbox (mostly timing: worker scans read each chunk once). Commands-on save "Sweep Souls3",
game loaded, control mode on; for the timing lines add DEBUG loggers skill-hangout, farm-skill, crafting-helper and
skill-fishing to the sandbox log4j config (a relaunch applies it; restore it after). Like fix275.py, each stage
builds on a fresh floating stone deck (Y=120) far from the base and from fix275's decks, so remembered spots never apply.

  fix276.py setup               gamerules (no day cycle, no mobs), noon, easy difficulty
  fix276.py fish <dx> <dz> <bx> check 1: a fresh 7x7 pond at X+dx, Z+dz, a new bot <bx> blocks east of the pond centre.
                                bx=4 and bx=10 check stands by A* (snapshot=built); bx=29 puts every stand outside the 49-wide
                                snapshot box (snapshot=skipped). 1.1.275 logged the summary at INFO only from 250 ms (else DEBUG).
                                Pass: `Fishing spot search:` elapsed well under 1000 ms (1.1.275: 1781 / 8392 ms), a cast follows.
  fix276.py hangout             check 2: a lit campfire, Camper 7 blocks off, /bot skill hangout Camper. Pass: the first move
                                within ~1 s of the command; the DEBUG `[Hangout] campfire scan` line.
  fix276.py craft               check 4: Crafter (3 planks, 2 sticks, never near a table) 15 blocks from a crafting table,
                                /bot craft wooden_pickaxe. Pass: `crafting table scan: ... ms` in tens of ms, the table used.
  fix276.py farm [side|planted|bare] check 5: a wooden hoe and seeds on a grass deck, water as a 2x2 pool 7 blocks west of
                                the grass (side). planted (fix274's layout) and bare put water at the centre, which the site check
                                rejects ("No suitable reachable spot", water-surface). Pass: the `[FarmScan]` water scans fast.
  fix276.py woodcut             check 3: W1 and W2 woodcut 4 trees (fix275 threat2's shape, no zombie). Pass: `Woodcut detect:
                                durationMs` mostly < 100 (1.1.275: 104-1541, median 1066), and a relocation's `Tree sampling`.
  fix276.py mine <caged|free|creeper>  check 6 (finding 44): M1 and M2 mine beside a frozen zombie / an ordinary zombie / a creeper.
                                Pass: blocks mined, one `sees N hostile(s) nearby` line per bot, no 60 s stall; free: fight, then mine.
  fix276.py follow [x z dist [south]]  check 8: Fol follows the player 40 blocks east (or south) over natural terrain (60 230);
                                the player hops 2 blocks a second onto the ground. Pass: Fol keeps up, no `Can't keep up!`.
  fix276.py chests              check 7: registry worlds and this launch's retire / adopt lines.
  fix276.py deposit <bot>       give <bot> 7 cobblestone, /bot store deposit 7 cobblestone (run in "AB R1").
  fix276.py clean <bot>         despawn a stage's bot
Flashback: tools/sandbox/rec.py start / stop frens_1_1_276_6bn_fix_checks.
"""
import json, os, re, sys, time, urllib.request
from collections import Counter

API = "http://127.0.0.1:9876/api/cmd"
MC = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft"
LOG = MC + "/logs/latest.log"
SAVE = os.environ.get("SB_SAVE", "Sweep Souls3")
REG = MC + "/config/frens/bot_chest_registry.json"
X, Y, Z = 300, 120, 300  # platform top is Y-1, feet at Y


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=30).read().decode()
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


def watch(m, secs, pat, stop=None):
    """Print new log lines matching pat for up to secs; return early when stop matches."""
    seen, end, hits = m, time.time() + secs, []
    while time.time() < end:
        ls = lines()
        for l in ls[seen:]:
            if re.search(pat, l):
                print(l[:260]); hits.append(l)
                if stop and re.search(stop, l):
                    return hits
        seen = len(ls); time.sleep(2)
    return hits


def since(m, pat):
    return [l for l in lines()[m:] if re.search(pat, l)]


def platform(cx=X, cz=Z, r=8):
    chat(f"/fill {cx-r} {Y-3} {cz-r} {cx+r} {Y+12} {cz+r} air")
    chat(f"/fill {cx-r} {Y-3} {cz-r} {cx+r} {Y-1} {cz+r} stone")


def spawn(bot, x, y, z):
    chat(f"/bot spawn {bot} admin", 12)
    chat(f"/tp {bot} {x} {y} {z}", 2)
    chat(f"/clear {bot}", 1)


def give(bot, *items):
    for it in items:
        chat(f"/give {bot} {it}", 1)


def tree(x, z, h, leaves=True):
    chat(f"/setblock {x} {Y-1} {z} dirt", 1)  # woodcut's soil check needs dirt under the trunk; stone all around it
    chat(f"/fill {x} {Y} {z} {x} {Y+h-1} {z} oak_log", 1)
    if leaves:
        chat(f"/fill {x-2} {Y+h-2} {z-2} {x+2} {Y+h} {z+2} oak_leaves keep", 1)


def bag(bot):
    try:
        st = json.loads(cmd(cmd="bot_state", name=bot))
    except Exception:  # noqa: BLE001
        return None
    inv = st.get("inventory") or st.get("items") or []
    c = Counter()
    for it in inv:
        if isinstance(it, dict):
            c[it.get("id") or it.get("item")] += int(it.get("count", 1))
    return c


def show_bag(bot):
    c = bag(bot)
    if c is None:
        print(cmd(cmd="bot_state", name=bot)[:600]); return None
    print(f"{bot} bag total={sum(c.values())} {dict(c)}")
    return c


def block_at(x, y, z):
    try:
        d = json.loads(cmd(cmd="blocks", x=x, y=y, z=z, r=0, dy=0))
    except Exception:  # noqa: BLE001
        return cmd(cmd="blocks", x=x, y=y, z=z, r=0, dy=0)[:200]
    return d


def pos(bot):
    try:
        st = json.loads(cmd(cmd="bot_state", name=bot))
    except Exception:  # noqa: BLE001
        return None
    p = st.get("pos") or st.get("position")
    if isinstance(p, dict):
        return (round(float(p.get("x", 0)), 1), round(float(p.get("y", 0)), 1), round(float(p.get("z", 0)), 1))
    if isinstance(p, (list, tuple)):
        return tuple(round(float(v), 1) for v in p[:3])
    return p


st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear",
              "/difficulty easy"):
        chat(t, 1)
elif st == "fish":
    dx, dz, bx = (int(a) for a in sys.argv[2:5])
    fx, fz = X + dx, Z + dz
    bot = f"Fish{abs(dx)}{abs(dz)}{bx}"[:16]
    chat(f"/tp @p {fx+8} {Y+2} {fz+8}", 4)
    platform(fx, fz, r=12); chat(f"/tp @p {fx+8} {Y} {fz+8}")
    chat(f"/fill {fx-3} {Y-2} {fz-3} {fx+3} {Y-1} {fz+3} water", 2)
    if bx > 12:  # a stone walkway past the deck; bx=29 puts every stand outside the 49-wide snapshot box (snapshot=skipped)
        chat(f"/fill {fx+12} {Y-1} {fz-1} {fx+bx+2} {Y-1} {fz+1} stone", 1)
    spawn(bot, fx + bx, Y, fz)
    give(bot, "fishing_rod 1", "bread 8")
    chat(f"/bot stop {bot}", 3)
    m = mark(); t0 = time.time()
    chat("/bot fish 1", 2)
    watch(m, 60, r"Fishing spot|[Cc]ast|bobber", stop=r"[Cc]ast(ing)? |Casting|cast=")
    time.sleep(12)
    for l in since(m, r"Fishing spot search")[:3]:
        print("  >", l[:400])
    print("casts:", [l[:200] for l in since(m, r"[Cc]ast|Reusing remembered|Selected fishing spot|Starting fishing session")[:4]])
    chat(f"/bot stop {bot}", 2)
elif st == "hangout":
    hx, hz = X, Z + 120
    chat(f"/tp @p {hx+8} {Y+2} {hz+8}", 4)
    platform(hx, hz, r=10); chat(f"/tp @p {hx+8} {Y} {hz+8}")
    chat(f"/setblock {hx} {Y} {hz} campfire[lit=true]", 1)
    spawn("Camper", hx + 7, Y, hz)
    give("Camper", "bread 8")
    chat("/bot stop Camper", 2)
    m = mark(); t0 = time.time(); print("command at", time.strftime("%H:%M:%S"))
    chat("/bot skill hangout Camper", 1)
    watch(m, 30, r"[Hh]angout|hang out|campfire", stop=r"Hangout move \[hangout-init")
    print(f"wall clock command -> first move line: {time.time()-t0:.1f} s (incl. 1 s chat wait and 2 s log poll)")
    for l in since(m, r"\[Hangout\] campfire scan|hang out|Hangout move|Starting skill|hangout")[:8]:
        print("  >", l[:260])
    time.sleep(5)
    print("Camper at", pos("Camper"))
    chat("/bot stop Camper", 1)
elif st == "craft":
    cx, cz = X + 120, Z + 120
    chat(f"/tp @p {cx} {Y+2} {cz+6}", 4)
    platform(cx, cz, r=16); chat(f"/tp @p {cx} {Y} {cz+6}")
    chat(f"/setblock {cx-8} {Y} {cz} crafting_table", 1)
    spawn("Crafter", cx + 7, Y, cz)
    give("Crafter", "oak_planks 3", "stick 2", "bread 8")
    chat("/bot stop Crafter", 3)  # makes Crafter the active bot for /bot craft
    m = mark(); t0 = time.time()
    chat("/bot craft wooden_pickaxe", 1)
    watch(m, 60, r"Crafter|crafting table|[Cc]raft", stop=r"[Cc]rafted|couldn't|[Cc]an't craft|don't know")
    time.sleep(3)
    for l in since(m, r"crafting table scan|table scan|[Cc]rafted|crafting table at|[Tt]able")[:10]:
        print("  >", l[:260])
    show_bag("Crafter")
    print("table still at", block_at(cx - 8, Y, cz), "Crafter at", pos("Crafter"))
elif st == "farm":
    variant = sys.argv[2] if len(sys.argv) > 2 else "side"
    fx, fz = X + 240, Z + {"bare": 120, "planted": 180}.get(variant, 240)
    chat(f"/tp @p {fx+7} {Y+2} {fz+7}", 4)
    platform(fx, fz, r=12); chat(f"/tp @p {fx+7} {Y} {fz+7}")
    chat(f"/fill {fx-4} {Y-1} {fz-4} {fx+4} {Y-1} {fz+4} grass_block", 1)
    if variant == "planted":  # fix274's farm: half farmland with wheat, water at the centre
        chat(f"/fill {fx-4} {Y-1} {fz-4} {fx+4} {Y-1} {fz-1} farmland[moisture=7]", 1)
        chat(f"/fill {fx-4} {Y} {fz-4} {fx+4} {Y} {fz-1} wheat[age=3]", 1)
    if variant == "planted":
        chat(f"/setblock {fx} {Y-1} {fz} water", 1)
    else:  # side: a 2x2 pool 7 blocks west of the grass, outside the farm footprint
        chat(f"/fill {fx-8} {Y-1} {fz} {fx-7} {Y-1} {fz+1} water", 1)
    spawn("Farmer2", fx + 6, Y, fz)
    give("Farmer2", "wooden_hoe 1", "wheat_seeds 16", "bread 8")
    m = mark(); t0 = time.time(); print("command at", time.strftime("%H:%M:%S"))
    chat("/bot skill farm Farmer2", 2)
    watch(m, 120, r"\[FarmScan\]|Farmer2.*(farm|till|plant)|\[Farm", stop=r"farm.*(COMPLETED|FAILED)|did nothing|planted \d+")
    time.sleep(5)
    for l in since(m, r"\[FarmScan\]")[:10]:
        print("  >", l[:260])
    print(since(m, r"\[CHAT\].*(hoe|plant|farm|water)")[:5])
    chat("/bot stop Farmer2", 1)
elif st == "woodcut":
    sx, sz = X - 60, Z + 120
    chat(f"/tp @p {sx} {Y+2} {sz+6} 0 0", 4)
    platform(sx, sz, r=10); chat(f"/tp @p {sx} {Y} {sz+6}")
    for tx, tz in ((sx + 4, sz - 6), (sx + 6, sz - 3), (sx + 2, sz - 7), (sx + 7, sz - 6)):
        tree(tx, tz, 5)
    for b, dx in (("W1", 2), ("W2", 4)):
        spawn(b, sx + dx, Y, sz - 2)
        give(b, "stone_axe 1", "dirt 16", "bread 8")
    m = mark()
    chat("/bot skill woodcut W1", 2); chat("/bot skill woodcut W2", 4)
    watch(m, 240, r"Tree sampling|Woodcut relocation: (stage|slow|broad|nearby|no unprotected)|Cleared the local|Cut \d+ tree",
          stop=r"Tree sampling")
    time.sleep(15)
    d = [int(x) for x in re.findall(r"Woodcut detect: durationMs=(\d+)", "\n".join(since(m, r"Woodcut detect: durationMs")))]
    d.sort()
    print(f"detect durationMs n={len(d)} min={d[0] if d else None} median={d[len(d)//2] if d else None} max={d[-1] if d else None} "
          f"under100={sum(v < 100 for v in d)}")
    for l in since(m, r"Tree sampling|Woodcut relocation: (stage|slow|broad|nearby|no unprotected)|Cleared the local|Cut \d+ tree")[:12]:
        print("  >", l[:300])
    chat("/bot stop W1", 1); chat("/bot stop W2", 1)
elif st == "mine":
    kind = sys.argv[2] if len(sys.argv) > 2 else "caged"
    sx, sz = {"caged": (X - 60, Z + 240), "free": (X, Z + 240), "creeper": (X + 60, Z + 240)}[kind]
    chat(f"/tp @p {sx} {Y+2} {sz+8} 0 0", 4)
    platform(sx, sz, r=10); chat(f"/tp @p {sx} {Y} {sz+8} 0 0")
    chat(f"/fill {sx-2} {Y-12} {sz-6} {sx+6} {Y-4} {sz-2} stone", 1)  # deeper stone under the miners
    chat("/kill @e[type=zombie]", 1); chat("/kill @e[type=creeper]", 1)
    bots = ("M1", "M2") if kind != "creeper" else ("M3",)
    for i, b in enumerate(bots):
        spawn(b, sx + 2 + 2 * i, Y, sz - 4)
    for b in bots:  # give after all spawns: mutual aid hands a lone pickaxe to a bare bot (the first caged run lost M1's)
        chat(f"/clear {b}", 1)
        give(b, "stone_pickaxe 1", "stone_sword 1", "bread 8")
    m = mark()
    for b in bots:
        chat(f"/bot skill mining {b}", 2)
    mob = {"caged": f"zombie {sx-3} {Y} {sz-4} {{NoAI:1b,Invulnerable:1b,PersistenceRequired:1b}}",
           "free": f"zombie {sx-6} {Y} {sz-4} {{PersistenceRequired:1b}}",
           "creeper": f"creeper {sx-6} {Y} {sz-4} {{PersistenceRequired:1b}}"}[kind]
    chat(f"/summon {mob}", 1)
    t0 = time.time(); track = []
    while time.time() - t0 < 60:
        track.append((round(time.time() - t0), {b: pos(b) for b in bots}))
        time.sleep(5)
    chat("/kill @e[type=zombie]", 1); chat("/kill @e[type=creeper]", 1)
    for b in bots:
        print(b, [l[:200] for l in since(m, rf"Skill 'mining' finished for bot {b}")])
    loot = since(m, r"mining loot pickup")
    print(f"mining loot pickups={len(loot)} first={loot[0][:10] if loot else None} last={loot[-1][:10] if loot else None}")
    for l in since(m, r"sees \d+ hostile|detected \d+ hostile|engag|[Kk]illed|attack|Terminating|pause requested|creeper|[Mm]ining (paused|stopped|timed out)|60 ?s")[:25]:
        print("  >", l[:260])
    print("WARN detected-hostile lines:", len(since(m, r"detected \d+ hostile")), "sees lines:", len(since(m, r"sees \d+ hostile")))
    for t, p in track:
        print("  t+", t, p)
    print("mining-end:", [l[:200] for l in since(m, r"[Mm]ining.*(COMPLETED|FAILED|ABORTED|finished|success)")][:4])
    for b in bots:
        chat(f"/bot stop {b}", 1)
elif st == "follow":
    # natural terrain east of (fx, fz): the player hops 2 blocks a second along +x onto the ground (leaves, logs and water
    # skipped, so it never stands on a tree); Fol follows on foot. Hold-W walking stalled on the first tree here.
    fx, fz = int(sys.argv[2]) if len(sys.argv) > 2 else 60, int(sys.argv[3]) if len(sys.argv) > 3 else 230
    dist = int(sys.argv[4]) if len(sys.argv) > 4 else 40
    south = len(sys.argv) > 5 and sys.argv[5] == "south"  # hop along +z instead of +x
    yaw = 0 if south else -90
    soft = re.compile(r"^(\.|.*leaves|.*_log|leaf_litter|short_grass|tall_grass|fern|.*flower.*|water|snow|vine|bush|.*sapling)$")

    def ground(x, z):
        try:
            lv = json.loads(cmd(cmd="blocks", x=x, y=75, z=z, r=0, dy=14))["levels"]
        except Exception:  # noqa: BLE001
            return None
        col = {l["y"]: l["rows"][0].lstrip("@") for l in lv}
        for y in sorted(col, reverse=True):
            b = col[y]
            if b == "water":
                return None
            if not soft.match(b) and col.get(y + 1, ".") in (".", "leaf_litter", "short_grass") and col.get(y + 2, ".") == ".":
                return y + 1
        return None

    g = ground(fx, fz)
    chat(f"/tp @p {fx} {g} {fz} {yaw} 0", 3)
    spawn("Fol", fx + 2, Y, fz); chat(f"/tp Fol {fx} {g} {fz + 1}", 2)
    m = mark()
    chat("/bot follow Fol", 3)
    step, lag, last = 0, [], None
    while step < dist:
        step += 2
        x, z = (fx, fz + step) if south else (fx + step, fz)
        y = ground(x, z)
        if y is None:
            continue
        chat(f"/tp @p {x} {y} {z} {yaw} 0", 1)
        last = (x, y, z)
        p = pos("Fol")
        if p and isinstance(p, tuple):
            lag.append(round(((x - p[0]) ** 2 + (z - p[2]) ** 2) ** 0.5, 1))
    time.sleep(10)
    p = pos("Fol")
    print("player end", last, "Fol end", p, "Fol-player distance per hop:", lag)
    for l in since(m, r"Fol.*(stuck|no path|teleport|gave up|Stopping)|Can't keep up|Follow status")[-12:]:
        print("  >", l[:240])
    print("cant-keep-up:", len(since(m, r"Can't keep up")), "no-path lines:", len(since(m, r"no path found")),
          "teleports:", since(m, r"Fol.*[Tt]eleport")[:3])
    chat("/bot stop Fol", 2)
elif st == "chests":
    d = json.load(open(REG))
    print("claims:", d.get("legacyWorldClaims"))
    for k, w in d.get("worlds", {}).items():
        print(" ", k, {b: [(r.get("x"), r.get("y"), r.get("z"), r.get("destroyed")) for r in v] for b, v in w.get("chestsByBot", {}).items()})
    for l in since(0, r"[Cc]hest registry|Adopted legacy|Retired \d+"):
        print("  >", l[:400])
elif st == "deposit":
    bot = sys.argv[2] if len(sys.argv) > 2 else "smokey"
    give(bot, "cobblestone 7")
    show_bag(bot)
    m = mark()
    chat(f"/bot store deposit 7 cobblestone {bot}", 2)
    watch(m, 60, rf"{bot}.*([Dd]eposit|chest)|Deposited|[Nn]o chest", stop=r"Deposited \d+|[Nn]o chest|couldn't")
    show_bag(bot)
elif st == "clean":
    chat(f"/bot despawn {sys.argv[2]}", 3)
else:
    print(__doc__)
