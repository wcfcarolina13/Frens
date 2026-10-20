#!/usr/bin/env python3
"""1.1.280 checks in the mcp-sandbox (follow under oak canopies, /tp during follow, flooded-pocket ledge climb, woodcut
cleanup and other bots' soil and pillars, bow defenders and friendly fire, admin remote open, regressions). Commands-on
save "Sweep Souls3", game loaded, control mode on. Reuses fix277's canopy decks (X 300/360, Z 660, built in 1.1.277),
flood block, woodcut deck and follow route, and fix278's aid deck and doorway; this script polls at 0.5 s where the pass
criteria need it (fix277 polled every 3-5 s).

  fix280.py setup                 gamerules (no day cycle, no mobs), noon, easy difficulty
  fix280.py bots                  positions of the old stage bots (Canopy, Flood*, W1, W2, Fol, Jake, Bob, Coll)
  fix280.py canopy [narrow|wide] [follow|stop|dist3] [name]
                                  check 1 (findings 47/50) on fix277's decks (build them with fix277.py canopy <v> build).
                                  Polls the bot every 0.5 s; prints the time it is down the step past the row, the longest run of
                                  one `dist=` value in its Follow status lines, head-not-passable, leaf-cleared / Mining complete /
                                  Mining aborted, and its closest and final horizontal distance to the player (personal space 3.0).
  fix280.py tp [name]             check 2 (finding 51): the bot starts on the narrow deck (X 300), /bot follow toward the player on
                                  the wide deck (X 360, wall-to-wall leaves), 3 s later /tp onto the wide deck's start (~60 blocks,
                                  over the 16-block detector), no command after. Pass: `External teleport detected`, 0 "Mining
                                  aborted", a leaf `Mining complete` within 15 s of the teleport.
  fix280.py flood [pick|bare] [depth]   check 4 (finding 49): fix277's flooded pocket (16 cobblestone, as in 1.1.277), polled
                                  every 0.5 s: the `ledge ... ready, climbed out=` line, when the bot is first on ground above the
                                  water, then the ascent to the top (Y 126).
  fix280.py woodcut [nosap]       check 5 (finding 52): fix277's 4-tree deck, W1 and W2; nosap: W2's saplings cleared every 2 s,
                                  so it can't replant. Reads the 4 base cells (Y-1) before and after, greps `Mining` at them, and
                                  matches every mined cell against the other bot's polled feet.
  fix280.py aid [bow|bowarmor] [clear]   check 6 (findings 53/54): fix278's aid stage (Bob between Jake and the zombie on one line) with
                                  Jake's full position tracked, Bob's health, `Mutual aid:` lines and `ff_received` lines.
                                  clear: the control, the zombie summoned between Jake and Bob (a clear line at first).
  fix280.py open [dist]           check 7 (finding 55): Opener stood dist (16) blocks away, /bot open Opener; screen_info at 1 s
                                  and 5 s, `[bot-inv-open]` lines.
  fix280.py follow [x z dist [south]]   checks 3 and 8: fix277.py follow (default: the 1.1.278 east route, finding 56).
  fix280.py door                  check 8: fix278.py door A.
  fix280.py clean <bot>           despawn a stage's bot
Flashback: tools/sandbox/rec.py start / stop frens_1_1_280_6br_<topic>.
"""
import json, os, re, subprocess, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
MC = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft"
LOG = MC + "/logs/latest.log"
HERE = os.path.dirname(os.path.abspath(__file__))
PLAYER = "RotiWokeman"
X, Y, Z = 300, 120, 300  # fix277's origin; feet at Y
TS = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\]")


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


def since(m, pat):
    return [l for l in lines()[m:] if re.search(pat, l)]


def secs(l):
    t = TS.match(l)
    return int(t.group(1)) * 3600 + int(t.group(2)) * 60 + int(t.group(3)) if t else None


def clock(t):
    lt = time.localtime(t)
    return lt.tm_hour * 3600 + lt.tm_min * 60 + lt.tm_sec


def state(bot):
    try:
        return json.loads(cmd(cmd="bot_state", name=bot))
    except Exception:  # noqa: BLE001
        return {}


def pos(bot):
    p = state(bot).get("pos")
    return tuple(round(float(v), 2) for v in p[:3]) if isinstance(p, list) else None


def player_pos():
    return pos(PLAYER)  # bot_state answers for the player too, with exact coordinates ("players" gives block ints)


def hdist(a, b):
    return round(((a[0] - b[0]) ** 2 + (a[2] - b[2]) ** 2) ** 0.5, 2) if a and b else None


def spawn(bot, x, y, z):
    chat(f"/bot spawn {bot} admin", 12)
    chat(f"/tp {bot} {x} {y} {z}", 2)
    chat(f"/clear {bot}", 1)
    if not pos(bot):
        print(f"SPAWN FAILED: {bot}")


def give(bot, *items):
    for it in items:
        chat(f"/give {bot} {it}", 1)


def column(x, y, z, dy=0, r=0):
    try:
        lv = json.loads(cmd(cmd="blocks", x=x, y=y, z=z, r=r, dy=dy))["levels"]
    except Exception:  # noqa: BLE001
        return cmd(cmd="blocks", x=x, y=y, z=z, r=r, dy=dy)[:300]
    return {l["y"]: l["rows"] for l in lv}


def block_at(x, y, z):
    c = column(x, y, z)
    return "".join(c.get(y, ["?"])).lstrip("@") if isinstance(c, dict) else "?"


def leaf_row(x0, x1, y, z):
    return sum(1 for x in range(x0, x1 + 1) if "oak_leaves" in block_at(x, y, z))


def dist_runs(ls):
    """Longest run of one dist= value in consecutive Follow status lines: (seconds, dist, first time)."""
    best, run = (0, None, None), []
    for l in ls:
        d = re.search(r" dist=([\d.]+)", l)
        if not d:
            continue
        if run and run[-1][1] == d.group(1):
            run.append((secs(l), d.group(1), l[1:9]))
        else:
            run = [(secs(l), d.group(1), l[1:9])]
        span = run[-1][0] - run[0][0]
        if span > best[0]:
            best = (span, run[0][1], run[0][2])
    return best


def canopy_geom(variant):
    cx, cz = X + {"narrow": 0, "wide": 60}[variant], Z + 360
    lx0, lx1 = (cx - 6, cx + 6) if variant == "narrow" else (cx - 11, cx + 11)
    return cx, cz, lx0, lx1


def follow_report(bot, m, t0, track, cz, reached):
    pp = player_pos()
    after = [d for t, p, d in track if reached is not None and t >= reached and d is not None]
    print(f"down the step past the row at t+{reached} s; player {pp}; {bot} end {track[-1][1] if track else None}, "
          f"horizontal distance end {track[-1][2] if track else None}, closest after the step {min(after) if after else None} "
          f"(personal space 3.0)")
    print("track (t, pos, dist):", [(t, p and (p[0], p[1], p[2]), d) for t, p, d in track[::4]])
    fs = since(m, rf"Follow status: bot={bot} ")
    span, d, at = dist_runs(fs)
    print(f"Follow status lines {len(fs)}; longest constant dist run {span} s at dist={d} (from {at})")
    for l in fs[:3] + fs[-2:]:
        print("  fs>", l[:240])
    rej = since(m, rf"applyMovementInput-reject bot={bot}.*head-not-passable")
    print("head-not-passable:", len(rej))
    for l in since(m, rf"leaf-cleared|Mining (aborted|complete)|External teleport detected for {bot}|transition rejects|"
                      rf"Can't keep up|{bot}.*(stuck|no path)")[:16]:
        print("  >", l[:240])
    print("leaf-cleared:", len(since(m, rf"bot={bot} .*leaf-cleared")), "Mining complete:", len(since(m, "Mining complete")),
          "Mining aborted:", len(since(m, rf"Mining aborted for {bot}")), "Can't keep up:", len(since(m, r"Can't keep up")))


def poll_follow(bot, cz, secs_, stop_after=8, t0=None):
    t0 = t0 or time.time()
    track, reached = [], None
    while time.time() - t0 < secs_:
        p = pos(bot)
        d = hdist(p, player_pos())
        t = round(time.time() - t0, 1)
        track.append((t, p, d))
        if p and reached is None and p[2] >= cz + 1.5 and p[1] < Y - 0.5:
            reached = t
        if reached is not None and t > reached + stop_after:
            break
        time.sleep(0.5)
    return track, reached


st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear",
              "/difficulty easy"):
        chat(t, 1)
elif st == "bots":
    for b in ("Canopy", "Flood1", "FloodB1", "Flood2", "FloodB2", "W1", "W2", "Fol", "Jake", "Bob", "Coll", "Opener", "DoorA"):
        s = state(b)
        print(b, s.get("pos"), s.get("dim"), "health", s.get("health"), "task", s.get("task"))
elif st == "canopy":
    variant = sys.argv[2] if len(sys.argv) > 2 else "narrow"
    mode = sys.argv[3] if len(sys.argv) > 3 else "follow"
    bot = sys.argv[4] if len(sys.argv) > 4 else "Canopy"
    cx, cz, lx0, lx1 = canopy_geom(variant)
    chat(f"/fill {lx0} {Y+1} {cz+1} {lx1} {Y+1} {cz+1} oak_leaves[persistent=false,distance=1] replace air", 1)
    chat(f"/tp @p {cx} {Y-1} {cz+8} 180 20", 2)
    if not pos(bot):
        spawn(bot, cx, Y, cz - 4)
        give(bot, "bread 8")
    chat(f"/bot stop {bot}", 1)
    chat(f"/tp {bot} {cx} {Y} {cz-4}", 4)
    print("bot at start:", pos(bot), "player", player_pos())
    print(f"leaf row before: {leaf_row(lx0, lx1, Y + 1, cz + 1)}/{lx1-lx0+1}")
    m = mark(); t0 = time.time()
    if mode == "stop":
        chat(f"/bot stop {bot}", 2)
        chat(f"/bot follow {bot}", 0.5)
    elif mode == "dist3":
        chat(f"/bot follow-distance 3 {bot}", 0.5)
    else:
        chat(f"/bot follow {bot}", 0.5)
    track, reached = poll_follow(bot, cz, 60, t0=t0)
    print(f"leaf row after: {leaf_row(lx0, lx1, Y + 1, cz + 1)}/{lx1-lx0+1}")
    follow_report(bot, m, t0, track, cz, reached)
elif st == "tp":
    bot = sys.argv[2] if len(sys.argv) > 2 else "Canopy"
    ncx, cz, _, _ = canopy_geom("narrow")
    cx, _, lx0, lx1 = canopy_geom("wide")
    chat(f"/fill {lx0} {Y+1} {cz+1} {lx1} {Y+1} {cz+1} oak_leaves[persistent=false,distance=1] replace air", 1)
    chat(f"/tp @p {cx} {Y-1} {cz+8} 180 20", 2)
    if not pos(bot):
        spawn(bot, ncx, Y, cz - 4)
        give(bot, "bread 8")
    chat(f"/bot stop {bot}", 1)
    chat(f"/tp {bot} {ncx} {Y} {cz-4}", 4)
    print("bot at narrow start:", pos(bot), f"wide leaf row before: {leaf_row(lx0, lx1, Y + 1, cz + 1)}/{lx1-lx0+1}")
    m = mark()
    chat(f"/bot follow {bot}", 3)
    print("after 3 s of follow:", pos(bot))
    t0 = time.time()
    chat(f"/tp {bot} {cx} {Y} {cz-4}", 0.5)
    track, reached = poll_follow(bot, cz, 45, t0=t0)
    print(f"wide leaf row after: {leaf_row(lx0, lx1, Y + 1, cz + 1)}/{lx1-lx0+1}")
    tp = since(m, rf"External teleport detected for {bot}")
    print("teleport lines:", [l[:160] for l in tp])
    tps = secs(tp[-1]) if tp else clock(t0)
    done = [l for l in since(m, rf"Mining complete at class_2338\{{x=-?\d+, y={Y+1}, z={cz+1}\}}") if secs(l) >= tps]
    print("leaf Mining complete after the teleport:", [(secs(l) - tps, l[:120]) for l in done][:4])
    follow_report(bot, m, t0, track, cz, reached)
elif st == "flood":
    variant = sys.argv[2] if len(sys.argv) > 2 else "pick"
    depth = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    cx, cz, y0 = X + 120 + (0 if depth == 1 else 60), Z + 360 + (0 if variant == "pick" else 60), 100
    bot = ("Flood" if variant == "pick" else "FloodB") + str(depth)
    top = y0 + 25
    if pos(bot):
        chat(f"/bot despawn {bot}", 4)
    chat(f"/tp @p {cx+3} {top+3} {cz} 0 60", 4)
    chat(f"/fill {cx-13} {y0} {cz-13} {cx+13} {top} {cz+13} stone", 2)
    chat(f"/fill {cx-13} {top+1} {cz-13} {cx+13} {top+6} {cz+13} air", 1)
    chat(f"/fill {cx} {y0+2} {cz} {cx} {y0+4} {cz} air", 1)
    chat(f"/fill {cx} {y0+2} {cz} {cx} {y0+1+depth} {cz} water", 1)
    chat(f"/tp @p {cx+3} {top+1} {cz} 0 60", 2)
    spawn(bot, cx + 2, top + 1, cz)
    give(bot, "cobblestone 16", "bread 8")
    if variant == "pick":
        give(bot, "stone_pickaxe 1")
    print("pocket:", column(cx, y0 + 1, cz, dy=4))
    chat(f"/fill {cx+47} {top} {cz-1} {cx+49} {top} {cz+1} stone", 1)
    m = mark(); t0 = time.time(); print("tp into the water at", time.strftime("%H:%M:%S"))
    chat(f"/tp {bot} {cx}.5 {y0+2} {cz}.5", 0.5)
    chat(f"/tp @p {cx+48} {top+1} {cz} 90 30", 0.5)  # 48 blocks east: outside the 32-block owner linger
    pat = (rf"{bot}.*(trapped in water|ledge|ascent failed|pillar|linger)|Stuck-escape backoff.*{bot}|"
           rf"Surface recovery (starting|exit).*{bot}|flooded hole")
    track, seen, ready_t, ground_t, out = [], m, None, None, None
    limit = 240 if variant == "pick" else 360
    while time.time() - t0 < limit:
        s = state(bot); p = s.get("pos")
        t = round(time.time() - t0, 1)
        p = p and tuple(round(float(v), 2) for v in p[:3])
        track.append((t, p, s.get("onGround"), s.get("inWater", s.get("touchingWater"))))
        ls = lines()
        for l in ls[seen:]:
            if re.search(pat, l):
                print(f"t+{t}", l[:250])
                if ready_t is None and re.search(r"ledge at .* ready", l):
                    ready_t = t
        seen = len(ls)
        if ready_t is not None and ground_t is None and p and s.get("onGround") and p[1] >= y0 + 2.9:
            ground_t = t
            print(f"on ground above the water at t+{t} s: {p} ({round(t - ready_t, 1)} s after the ready line)")
        if (p and p[1] >= top and out is None) or since(m, rf"Surface recovery exit: bot={bot} success=true"):
            out = t
            print(f"{bot} on top at t+{out} s: {p}")
            break
        time.sleep(0.5)
    print("track (t, pos, onGround, water):", track[::6])
    print("pocket after:", column(cx, y0 + 1, cz, dy=6, r=1))
    for k, p_ in (("carve", r"trapped in water at .*carving"), ("ready", r"ledge at .* ready"), ("climbed out=true", r"climbed out=true"),
                  ("did not get onto", r"did not get onto the ledge"), ("cannot swim up", r"cannot swim up onto"),
                  ("ascent failed", r"ascent failed"), ("backoff", r"Stuck-escape backoff:"), ("exit", r"Surface recovery exit")):
        hits = since(m, rf"{bot}.*{p_}|{p_}.*{bot}")
        print(f"{k}: {len(hits)}", [h[:220] for h in hits[:3]])
    print(f"ready at t+{ready_t}, on ground at t+{ground_t}, top at t+{out}; final {pos(bot)}")
elif st == "woodcut":
    nosap = len(sys.argv) > 2 and sys.argv[2] == "nosap"
    sx, sz = X - 60, Z + 120
    trees = ((sx + 4, sz - 6), (sx + 6, sz - 3), (sx + 2, sz - 7), (sx + 7, sz - 6))
    chat(f"/tp @p {sx} {Y+2} {sz+6} 0 0", 4)
    chat(f"/fill {sx-10} {Y-3} {sz-10} {sx+10} {Y+12} {sz+10} air")
    chat(f"/fill {sx-10} {Y-3} {sz-10} {sx+10} {Y-1} {sz+10} stone")
    chat(f"/tp @p {sx} {Y} {sz+6}")
    for tx, tz in trees:
        chat(f"/setblock {tx} {Y-1} {tz} dirt", 1)
        chat(f"/fill {tx} {Y} {tz} {tx} {Y+4} {tz} oak_log", 1)
        chat(f"/fill {tx-2} {Y+3} {tz-2} {tx+2} {Y+5} {tz+2} oak_leaves keep", 1)
    print("bases before:", [block_at(tx, Y - 1, tz) for tx, tz in trees])
    for b, dx in (("W1", 2), ("W2", 4)):
        if not pos(b):
            spawn(b, sx + dx, Y, sz - 2)
        chat(f"/bot stop {b}", 1)
        chat(f"/tp {b} {sx + dx} {Y} {sz - 2}", 1)
        chat(f"/clear {b}", 1)
        give(b, "stone_axe 1", "dirt 16", "bread 8")
    m = mark(); t0 = time.time()
    chat("/bot skill woodcut W1", 2); chat("/bot skill woodcut W2", 1)
    feet, last_clear, finished = [], 0, set()
    while time.time() - t0 < 240 and len(finished) < 2:
        t = time.time()
        for b in ("W1", "W2"):
            bl = state(b).get("block")
            if bl:
                feet.append((clock(t), b, tuple(bl[:3])))
        if nosap and t - last_clear > 2:
            chat("/clear W2 oak_sapling", 0.2); last_clear = t
        for b in ("W1", "W2"):
            if since(m, rf"Skill 'woodcut' finished for bot {b}|Cleared the local woodcut area") and b not in finished:
                if since(m, rf"Skill 'woodcut' finished for bot {b}"):
                    finished.add(b)
        time.sleep(0.5)
    time.sleep(5)
    print(f"watched {round(time.time() - t0)} s; finished: {sorted(finished)}")
    # map the skill threads to bots from lines that name them
    thr = {}
    for l in since(m, r"\[(mod-command-skill-\d+)/"):
        th = re.search(r"\[(mod-command-skill-\d+)/", l).group(1)
        b = re.search(r"\b(W1|W2)\b", l)
        if b and th not in thr:
            thr[th] = b.group(1)
    print("skill threads:", thr)
    mined = []
    for l in since(m, r"Mining class_2338\{x=-?\d+, y=-?\d+, z=-?\d+\} with"):
        c = tuple(int(v) for v in re.search(r"x=(-?\d+), y=(-?\d+), z=(-?\d+)", l).groups())
        th = re.search(r"\[([^/\]]+)/", l).group(1)
        mined.append((secs(l), thr.get(th, th), c, l))
    base_hits = [x for x in mined if (x[2][0], x[2][2]) in trees and x[2][1] == Y - 1]
    print("Mining at a base (y-1):", len(base_hits), [x[3][:200] for x in base_hits])
    under = []
    for ts_, who, c, l in mined:
        for ft, b, f in feet:
            if abs(ft - ts_) <= 1 and b != who and (f[0], f[1] - 1, f[2]) == c:
                under.append((b, who, c, l[:160])); break
    print("cells mined under the other bot's feet (+-1 s):", len(under), under[:5])
    print("dirt mined (with dirt held, any y):", len([x for x in mined if "with minecraft:dirt" in x[3]]),
          "| placed-scaffold lines:", len(since(m, r"(?i)scaffold|pillar")))
    print("bases after:", [block_at(tx, Y - 1, tz) for tx, tz in trees],
          "| above bases:", [block_at(tx, Y, tz) for tx, tz in trees])
    det = since(m, r"Woodcut detect: durationMs")
    full = [l for l in det if not re.search(r"logs=0 ", l)]
    print("detects with logs:", [re.search(r"durationMs=(\d+)", l).group(1) for l in full][:6])
    for l in since(m, r"Cut \d+ tree|Got \d+ logs|Skill 'woodcut' finished|woodcut_cleanup|Cleared the local|soil|"
                      r"RecentTreeSoil|protected")[:20]:
        print("  >", l[:240])
    print("logs left:", sum(1 for tx, tz in trees if "oak_log" in json.dumps(column(tx, Y, tz, dy=4))))
    chat("/bot stop W1", 1); chat("/bot stop W2", 1)
elif st == "aid":
    variant = sys.argv[2] if len(sys.argv) > 2 else "bow"
    cx, cz = X + 60, 800
    chat(f"/tp @p {cx} {Y+4} {cz+6} 180 30", 4)
    chat(f"/fill {cx-20} {Y-3} {cz-8} {cx+20} {Y+6} {cz+8} air", 1)
    chat(f"/fill {cx-20} {Y-3} {cz-8} {cx+20} {Y-1} {cz+8} stone", 1)
    chat(f"/fill {cx-20} {Y} {cz-8} {cx+20} {Y+1} {cz+8} glass", 1)
    chat(f"/fill {cx-19} {Y} {cz-7} {cx+19} {Y+1} {cz+7} air", 1)
    chat(f"/tp @p {cx} {Y} {cz+6} 180 20", 2)
    chat("/kill @e[type=zombie]", 1)
    chat("/kill @e[type=arrow]", 1)
    clear = len(sys.argv) > 3 and sys.argv[3] == "clear"
    jx, bx, zx = cx - 8, cx + 7, (cx + 2 if clear else cx + 11)  # Bob between Jake and the zombie (clear: zombie between)
    for b, x_ in (("Jake", jx), ("Bob", bx)):
        chat(f"/bot stop {b}", 1)
        chat(f"/tp {b} {x_} {Y} {cz}", 2)
        chat(f"/clear {b}", 1)
        chat(f"/effect clear {b}", 1)
    give("Jake", "bow 1", "arrow 32", "bread 8")
    if variant == "bowarmor":
        chat("/item replace entity Jake armor.chest with iron_chestplate", 1)
    chat("/effect give Jake saturation 3 10 true", 3)
    chat("/effect give Bob saturation 1 0 true", 2)
    chat("/effect give Bob instant_health 1 3 true", 2)
    chat("/damage Bob 7 minecraft:generic", 2)
    for b in ("Jake", "Bob"):
        s = state(b)
        print(b, "pos", s.get("pos"), "health", s.get("health"), "food", s.get("food"), "inv",
              [(i["id"].split(":")[1], i["count"]) for i in s.get("inventory", [])])
    m = mark(); t0 = time.time()
    print("zombie at", time.strftime("%H:%M:%S"))
    chat(f"/summon zombie {zx} {Y} {cz} {{PersistenceRequired:1b,Health:12f}}", 0.5)
    chat("/effect give @e[type=zombie] fire_resistance 120 0 true", 0.5)
    track, gone, bob_h = [], None, []
    while time.time() - t0 < 45:
        t = round(time.time() - t0, 1)
        jp, bs = pos("Jake"), state("Bob")
        bp = bs.get("pos")
        track.append((t, jp and (jp[0], jp[2]), bp and (round(bp[0], 1), round(bp[2], 1))))
        bob_h.append((t, bs.get("health")))
        if gone is None and len(track) % 3 == 1:
            mm = mark()
            chat("/data get entity @e[type=zombie,limit=1] Pos", 0.2)
            zp = since(mm, r"has the following entity data: \[")
            arrows = sum(i["count"] for i in state("Jake").get("inventory", []) if i["id"].endswith(":arrow"))
            if zp:
                print(f"  t+{t} zombie", re.search(r"data: (\[.*\])", zp[-1]).group(1)[:40], "Jake", jp, "Bob", bp, "arrows", arrows)
        if gone is None:
            mm = mark()
            chat("/execute unless entity @e[type=zombie] run say NO_ZOMBIE", 0.3)
            if since(mm, r"NO_ZOMBIE"):
                gone = t
        if gone is not None and time.time() - t0 > gone + 10:
            break
        time.sleep(0.4)
    print("track (t, Jake x/z, Bob x/z):", track[::3])
    print("Bob health:", [h for h in bob_h[::3]])
    for l in since(m, r"Mutual aid|ff_received|That was you|on your team|Can't keep up|Take Aim|One less|Bob.*(flee|Flee)|"
                      r"Jake.*(combat|engag|bow|arrow|reposition)")[:30]:
        print("  >", l[:240])
    print("gone at", gone, "| Mutual aid lines:", len(since(m, r"Mutual aid: Jake")),
          "| ff_received (Bob):", len(since(m, r"Played sound frens:bot\.line\.ff_received")),
          "| Can't keep up:", len(since(m, r"Can't keep up")), "| arrows left:",
          sum(i["count"] for i in state("Jake").get("inventory", []) if i["id"].endswith(":arrow")))
    chat("/kill @e[type=zombie]", 1)
elif st == "open":
    d = int(sys.argv[2]) if len(sys.argv) > 2 else 16
    bot = "Opener"
    cx, cz = X + 180, 760
    chat(f"/tp @p {cx} {Y+4} {cz} 0 30", 4)
    chat(f"/fill {cx-2} {Y-3} {cz-2} {cx+2} {Y+4} {cz+d+2} air", 1)
    chat(f"/fill {cx-2} {Y-3} {cz-2} {cx+2} {Y-1} {cz+d+2} stone", 1)
    chat(f"/tp @p {cx} {Y} {cz} 0 10", 2)
    if not pos(bot):
        spawn(bot, cx, Y, cz + d)
        give(bot, "bread 8", "cobblestone 5")
    chat(f"/bot stop {bot}", 1)
    chat(f"/tp {bot} {cx} {Y} {cz + d}", 2)
    print("distance:", hdist(pos(bot), player_pos()))
    m = mark()
    cmd(cmd="send_chat", text=f"/bot open {bot}")  # no escape after: chat()'s escape closes the screen it opened
    time.sleep(1)
    s1 = cmd(cmd="screen_info")[:300]
    time.sleep(4)
    s5 = cmd(cmd="screen_info")[:300]
    print("screen at 1 s:", s1)
    print("screen at 5 s:", s5)
    print("[bot-inv-open]:", len(since(m, r"\[bot-inv-open\] bot=" + bot)),
          [l[:120] for l in since(m, r"too far away|another dimension|Failed to open")])
    cmd(cmd="press_key", key="escape")
elif st == "follow":
    subprocess.run([sys.executable, os.path.join(HERE, "fix277.py"), "follow", *sys.argv[2:]])
elif st == "door":
    subprocess.run([sys.executable, os.path.join(HERE, "fix278.py"), "door", "A"])
elif st == "clean":
    chat(f"/bot despawn {sys.argv[2]}", 3)
else:
    print(__doc__)
