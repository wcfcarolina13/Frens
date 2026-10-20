#!/usr/bin/env python3
"""1.1.277 checks in the mcp-sandbox (findings 47 and 48, soul stage directions, woodcut detect speed, follow and mining
regressions). Commands-on save "Sweep Souls3" with soul-bound Jake and Bob, game loaded, control mode on. Like fix276.py,
each stage builds its own fresh structure away from fix275/276's decks (those sat at X 240-420, Z 300-540 at Y=120), so
remembered spots never apply; the woodcut, follow and mine stages reuse fix276's spots on purpose (same setup, same numbers).

  fix277.py setup                 gamerules (no day cycle, no mobs), noon, easy difficulty
  fix277.py canopy [narrow|wide] [build|follow|stop|dist3] [name]   (build once per variant, then the runs)
                                  check 1 (finding 47): a grass deck at feet Y, the area south one block lower (feet Y-1), a row
                                  of oak leaves (persistent=false) at Y+1 over the first lower row: the head cell of the step down
                                  (FollowStepTransitionPolicy's to.up(2)). An oak log column at X+7 and two logs above the row keep
                                  every leaf within distance 4, so nothing decays during the run (the row is read back before and
                                  after). narrow: leaves X-6..X+6 (the scoper's repro, a detour exists at X-7); wide: X-11..X+11
                                  wall to wall, so the bot must clear a leaf. The player stands below at Z+8; the bot starts at Z-4.
                                  follow: /bot follow <bot>; stop: /bot stop then /bot follow <bot>; dist3: /bot follow-distance 3 <bot>.
                                  Pass: the bot reaches the player, no 40 s stall, no head-not-passable loop, no "Mining aborted"
                                  after a leaf clear.
  fix277.py flood [pick|bare] [depth]  (the player then waits on a pad 48 blocks east, outside the 32-block owner linger)
                                  check 2 (finding 48): a 27x27 stone block (Y0=100..125), a 1x1x3 pocket at Y0+2..Y0+4 in its
                                  centre with water at Y0+2 (depth 2: Y0+2..Y0+3), solid below, 21 blocks of stone above. A fresh
                                  bot with 16 cobblestone (pick: and a stone pickaxe) is /tp'd into the water, never /bot stop'd.
                                  Pass: `carving a ledge` then `ledge ... ready`, the bot climbs out and reaches the top (Y0+26);
                                  else `Stuck-escape backoff` by the 2nd failure and `ascent failed` not every 10 s.
  fix277.py bob                   Bob's column at 234, 62, 299 (finding 48's original bot) and his recovery lines since launch.
  fix277.py souls [n]             check 3: n DMs to Jake and Bob (/msg) and one /bot soul banter now scene; prints soul chat
                                  lines and any parenthetical or *asterisk* span in them. Run with ollama_tap.py in between
                                  (settings.json5 ollamaBaseUrl -> 11435 while closed) and read the raw replies with `tap`.
  fix277.py tap [from_line]       raw model replies from the tap file with stage-direction-looking spans counted.
  fix277.py woodcut               check 4: fix276's woodcut stage (W1, W2, 4 trees); prints durationMs, logs=, nearest=, bases=.
  fix277.py follow [x z dist [south]]  check 5 / finding 47 original spot: fix276's follow stage (Fol, natural terrain).
  fix277.py mine                  check 5: fix276's `mine free` stage (M1 and M2 mine, an ordinary zombie nearby).
  fix277.py clean <bot>           despawn a stage's bot
Flashback: tools/sandbox/rec.py start / stop frens_1_1_277_6bo_<topic>.
"""
import json, os, re, sys, time, urllib.request
from collections import Counter

API = "http://127.0.0.1:9876/api/cmd"
MC = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft"
LOG = MC + "/logs/latest.log"
TAP = os.path.expanduser("~/Library/Caches/frens-ft/ollama_tap.jsonl")
X, Y, Z = 300, 120, 300  # fix276's origin; platform top is Y-1, feet at Y
STAGE = re.compile(r"\([^()]{1,40}\)|\*[^*]{1,40}\*|(?<!\w)_[^_]{1,40}_(?!\w)")


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


def state(bot):
    try:
        return json.loads(cmd(cmd="bot_state", name=bot))
    except Exception:  # noqa: BLE001
        return {}


def pos(bot):
    p = state(bot).get("pos") or state(bot).get("position")
    if isinstance(p, dict):
        return (round(float(p.get("x", 0)), 1), round(float(p.get("y", 0)), 1), round(float(p.get("z", 0)), 1))
    if isinstance(p, (list, tuple)):
        return tuple(round(float(v), 1) for v in p[:3])
    return p


def column(x, y, z, dy=0, r=0):
    try:
        lv = json.loads(cmd(cmd="blocks", x=x, y=y, z=z, r=r, dy=dy))["levels"]
    except Exception:  # noqa: BLE001
        return cmd(cmd="blocks", x=x, y=y, z=z, r=r, dy=dy)[:300]
    return {l["y"]: l["rows"] for l in lv}


def leaf_row(x0, x1, y, z):
    """Count oak leaves in a row along x (blocks reads one column at a time here)."""
    n = 0
    for x in range(x0, x1 + 1):
        c = column(x, y, z)
        if isinstance(c, dict) and any("oak_leaves" in "".join(r) for r in c.values()):
            n += 1
    return n


st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear",
              "/difficulty easy"):
        chat(t, 1)
elif st == "canopy":
    variant = sys.argv[2] if len(sys.argv) > 2 else "narrow"
    mode = sys.argv[3] if len(sys.argv) > 3 else "follow"
    bot = sys.argv[4] if len(sys.argv) > 4 else "Canopy"
    cx, cz = X + {"narrow": 0, "wide": 60}[variant], Z + 360
    lx0, lx1 = (cx - 6, cx + 6) if variant == "narrow" else (cx - 11, cx + 11)
    if mode == "build":  # terrain once; the runs below only re-place the leaf row (rebuilding under a following bot
        # and the player dropped both through the deck into the ocean, 1.1.277 check 23:57)
        chat(f"/tp @p {cx} {Y+3} {cz+8} 180 20", 4)
        chat(f"/fill {cx-11} {Y-4} {cz-10} {cx+11} {Y+6} {cz+12} air", 1)
        chat(f"/fill {cx-11} {Y-3} {cz-10} {cx+11} {Y-2} {cz} stone", 1)       # upper deck, feet at Y
        chat(f"/fill {cx-11} {Y-1} {cz-10} {cx+11} {Y-1} {cz} grass_block", 1)
        chat(f"/fill {cx-11} {Y-4} {cz+1} {cx+11} {Y-3} {cz+12} stone", 1)     # lower area, feet at Y-1
        chat(f"/fill {cx-11} {Y-2} {cz+1} {cx+11} {Y-2} {cz+12} grass_block", 1)
        chat(f"/tp @p {cx} {Y-1} {cz+8} 180 20", 2)
        if variant == "narrow":
            chat(f"/fill {cx+7} {Y-1} {cz+1} {cx+7} {Y+1} {cz+1} oak_log", 1)
        for lx in ((cx - 3, cx + 3) if variant == "narrow" else (cx - 7, cx, cx + 7)):
            chat(f"/setblock {lx} {Y+2} {cz+1} oak_log", 1)  # logs before leaves: keeps every leaf within distance 4
        chat(f"/fill {cx-5} {Y-1} {cz+2} {cx+5} {Y-1} {cz+4} leaf_litter keep", 1)
        chat(f"/fill {cx-5} {Y} {cz-2} {cx+5} {Y} {cz} leaf_litter keep", 1)
        chat(f"/fill {lx0} {Y+1} {cz+1} {lx1} {Y+1} {cz+1} oak_leaves[persistent=false,distance=1]", 1)
        print("built; leaf row:", leaf_row(lx0, lx1, Y + 1, cz + 1), "/", lx1 - lx0 + 1)
        sys.exit(0)
    chat(f"/fill {lx0} {Y+1} {cz+1} {lx1} {Y+1} {cz+1} oak_leaves[persistent=false,distance=1] replace air", 1)
    chat(f"/tp @p {cx} {Y-1} {cz+8} 180 20", 2)
    if not pos(bot):
        spawn(bot, cx, Y, cz - 4)
        give(bot, "bread 8")
    chat(f"/tp {bot} {cx} {Y} {cz-4}", 4)
    print("bot at start:", pos(bot))
    n0 = leaf_row(lx0, lx1, Y + 1, cz + 1)
    print(f"leaf row before: {n0}/{lx1-lx0+1}")
    m = mark(); t0 = time.time()
    if mode == "stop":
        chat(f"/bot stop {bot}", 3)
        chat(f"/bot follow {bot}", 1)
    elif mode == "dist3":
        chat(f"/bot follow-distance 3 {bot}", 1)  # "/bot follow <bot> 3" parses 3 as a player ("No player was found")
    else:
        chat(f"/bot follow {bot}", 1)
    track, reached = [], None
    while time.time() - t0 < 60:
        p = pos(bot)
        track.append((round(time.time() - t0), p))
        if p and isinstance(p, tuple) and reached is None and p[2] >= cz + 1.5 and p[1] < Y - 0.5:
            reached = round(time.time() - t0)
        if reached is not None and time.time() - t0 > reached + 8:
            break
        time.sleep(3)
    n1 = leaf_row(lx0, lx1, Y + 1, cz + 1)
    p = pos(bot)
    d = ((p[0] - cx) ** 2 + (p[2] - (cz + 8)) ** 2) ** 0.5 if p and isinstance(p, tuple) else None
    print(f"leaf row after: {n1}/{lx1-lx0+1}; down the step at t+{reached} s; {bot} end {p}, {d and round(d, 1)} from player")
    print("track:", track)
    rej = since(m, rf"applyMovementInput-reject bot={bot}.*head-not-passable")
    print("head-not-passable:", len(rej), [l[:220] for l in rej[:2]])
    for l in since(m, rf"leaf-cleared|Mining aborted|Follow decision: bot={bot}|transition rejects|Follow status: bot={bot}|"
                      rf"Can't keep up|{bot}.*(stuck|no path)")[:20]:
        print("  >", l[:260])
    print("leaf-cleared:", len(since(m, "leaf-cleared")), "Mining aborted:", len(since(m, "Mining aborted")))
elif st == "flood":
    variant = sys.argv[2] if len(sys.argv) > 2 else "pick"
    depth = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    cx, cz, y0 = X + 120 + (0 if depth == 1 else 60), Z + 360 + (0 if variant == "pick" else 60), 100
    bot = ("Flood" if variant == "pick" else "FloodB") + str(depth)
    top = y0 + 25
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
    m = mark(); t0 = time.time(); print("tp into the water at", time.strftime("%H:%M:%S"))
    chat(f"/tp {bot} {cx}.5 {y0+2} {cz}.5", 1)
    # The owner within undergroundProximityBlocks (32) keeps a bot underground on purpose ("commander nearby"
    # linger); the first run sat 4 minutes on that. Watch from a pad 48 blocks east instead.
    chat(f"/fill {cx+47} {top} {cz-1} {cx+49} {top} {cz+1} stone", 1)
    chat(f"/tp @p {cx+48} {top+1} {cz} 90 30", 1)
    pat = (rf"{bot}.*(trapped in water|ledge|ascent failed|not on ground|pillar|linger)|Stuck-escape backoff.*{bot}|"
           rf"Surface recovery (starting|exit).*{bot}|flooded hole|could not open ledge")
    track, out = [], None
    limit = 240 if variant == "pick" else 420
    seen = m
    while time.time() - t0 < limit:
        p = pos(bot)
        st_ = state(bot)
        track.append((round(time.time() - t0), p, st_.get("onGround")))
        ls = lines()
        for l in ls[seen:]:
            if re.search(pat, l):
                print(f"t+{round(time.time()-t0)}", l[:250])
        seen = len(ls)
        if p and isinstance(p, tuple) and p[1] >= top + 1 and out is None:
            out = round(time.time() - t0)
            print(f"{bot} on top at t+{out} s: {p}")
            break
        time.sleep(5)
    print("track:", track)
    print("pocket after:", column(cx, y0 + 1, cz, dy=5, r=1))
    for k, p_ in (("carve", r"trapped in water: carving"), ("ready", r"ledge at .* ready"), ("noledge", r"no ledge carved"),
                  ("ascent failed", r"ascent failed"), ("backoff", r"Stuck-escape backoff:"), ("exit", r"Surface recovery exit")):
        hits = since(m, rf"{bot}.*{p_}|{p_}.*{bot}")
        print(f"{k}: {len(hits)}", [h[:200] for h in hits[:3]])
    print("owner line:", [l[:200] for l in since(m, r"flooded hole|stuck in a hole")][:3])
    print("out at", out)
elif st == "bob":
    print("Bob at", pos("Bob"), "state:", {k: v for k, v in state("Bob").items() if k in ("health", "food", "onGround", "task")})
    print(column(234, 60, 299, dy=6, r=1))
    for l in since(0, r"Bob.*(trapped in water|ledge|ascent failed|not-on-ground)|Stuck-escape backoff.*Bob|"
                      r"Surface recovery exit.*bot=Bob|holding after stop.*Bob|Bob.*holding")[:30]:
        print("  >", l[:240])
elif st == "souls":
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 6
    asks = ["how are you feeling today?", "tell me a joke about creepers", "what do you think of Bob?",
            "be honest, are you tired of mining?", "what would you build if you could?", "say something dramatic",
            "what's your favorite time of day?", "do you trust me?"]
    print("tap lines before:", sum(1 for _ in open(TAP)) if os.path.exists(TAP) else 0)
    m = mark(); t0 = time.time()
    for i in range(n):
        who = ("Jake", "Bob")[i % 2]
        chat(f"/msg {who} {asks[i % len(asks)]}", 1)
        watch(mark(), 45, rf"\[CHAT\].*{who}", stop=rf"\[CHAT\].*{who}")
        time.sleep(3)
    chat("/bot soul banter now", 2)
    watch(mark(), 90, r"scene-playback routingId=\S+ outcome|scene correlationId=\S+ outcome", stop=r"scene-playback routingId=\S+ outcome")
    time.sleep(10)
    soul = [l for l in since(m, r"\[CHAT\]") if re.search(r"Jake|Bob", l) and "RotiWokeman" not in l.split("[CHAT]")[1][:14]]
    print(f"soul chat lines since t0 ({time.strftime('%H:%M:%S', time.localtime(t0))}): {len(soul)}")
    for l in soul:
        print("  >", l[:260])
    bad = [l for l in soul if STAGE.search(l.split("[CHAT]", 1)[1])]
    print("chat lines with a ( ) / * * / _ _ span:", len(bad), [l[:200] for l in bad])
elif st == "tap":
    start = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    n_resp, n_dir, words = 0, 0, Counter()
    for raw in open(TAP, errors="replace").read().splitlines()[start:]:
        try:
            e = json.loads(raw)
        except Exception:  # noqa: BLE001
            continue
        resp = e.get("response")
        if not isinstance(resp, dict):
            continue
        txt = (resp.get("message") or {}).get("content") or ""
        n_resp += 1
        spans = STAGE.findall(txt)
        if spans:
            n_dir += 1
            for s in spans:
                words[s.strip("()*_ ").split(" ")[0].lower()] += 1
            print("  raw:", txt[:300].replace("\n", " | "))
    print(f"tap replies {n_resp}, with a span {n_dir}, first words {dict(words)}")
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
    det = since(m, r"Woodcut detect: durationMs")
    full = [l for l in det if not re.search(r"logs=0 ", l)]
    d = sorted(int(re.search(r"durationMs=(\d+)", l).group(1)) for l in full)
    print(f"detect with logs: n={len(d)} min={d[0] if d else None} median={d[len(d)//2] if d else None} max={d[-1] if d else None}"
          f" (all detects n={len(det)})")
    for l in full[:8]:
        print("  det>", l.split("Woodcut detect: ")[1][:220])
    for l in since(m, r"Tree sampling|Woodcut relocation: (stage|slow|broad|nearby|no unprotected)|Cleared the local|Cut \d+ tree|"
                      r"Got \d+ logs|Skill 'woodcut' finished")[:12]:
        print("  >", l[:300])
    print("logs left:", sum(1 for tx, tz in ((sx + 4, sz - 6), (sx + 6, sz - 3), (sx + 2, sz - 7), (sx + 7, sz - 6))
                            if "oak_log" in json.dumps(column(tx, Y, tz, dy=4))))
    chat("/bot stop W1", 1); chat("/bot stop W2", 1)
elif st == "mine":
    sx, sz = X, Z + 240
    chat(f"/tp @p {sx} {Y+2} {sz+8} 0 0", 4)
    platform(sx, sz, r=10); chat(f"/tp @p {sx} {Y} {sz+8} 0 0")
    chat(f"/fill {sx-2} {Y-12} {sz-6} {sx+6} {Y-4} {sz-2} stone", 1)
    chat("/kill @e[type=zombie]", 1)
    bots = ("M1", "M2")
    for i, b in enumerate(bots):
        spawn(b, sx + 2 + 2 * i, Y, sz - 4)
    for b in bots:  # give after all spawns: mutual aid hands a lone pickaxe to a bare bot
        chat(f"/clear {b}", 1)
        give(b, "stone_pickaxe 1", "stone_sword 1", "bread 8")
    m = mark()
    for b in bots:
        chat(f"/bot skill mining {b}", 2)
    chat(f"/summon zombie {sx-6} {Y} {sz-4} {{PersistenceRequired:1b}}", 1)
    t0 = time.time()
    while time.time() - t0 < 60:
        time.sleep(5)
    chat("/kill @e[type=zombie]", 1)
    for b in bots:
        print(b, [l[:200] for l in since(m, rf"Skill 'mining' finished for bot {b}")])
    for l in since(m, r"sees \d+ hostile|detected \d+ hostile|[Kk]illed|Collected \d+|Monster Hunter|[Mm]ining (paused|stopped|timed out)")[:20]:
        print("  >", l[:260])
    print("WARN detected-hostile lines:", len(since(m, r"detected \d+ hostile")), "sees lines:", len(since(m, r"sees \d+ hostile")))
    for b in bots:
        chat(f"/bot stop {b}", 1)
elif st == "follow":
    fx, fz = int(sys.argv[2]) if len(sys.argv) > 2 else 60, int(sys.argv[3]) if len(sys.argv) > 3 else 230
    dist = int(sys.argv[4]) if len(sys.argv) > 4 else 40
    south = len(sys.argv) > 5 and sys.argv[5] == "south"
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
    if not pos("Fol"):
        spawn("Fol", fx + 2, Y, fz)
    chat(f"/tp Fol {fx} {g} {fz + 1}", 2)
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
    time.sleep(15)
    p = pos("Fol")
    print("player end", last, "Fol end", p, "Fol-player distance per hop:", lag)
    for l in since(m, r"Fol.*(stuck|no path|teleport|gave up|Stopping)|Can't keep up|leaf-cleared|Mining aborted")[-12:]:
        print("  >", l[:240])
    print("cant-keep-up:", len(since(m, r"Can't keep up")), "head-not-passable:",
          len(since(m, r"applyMovementInput-reject bot=Fol.*head-not-passable")),
          "leaf-cleared:", len(since(m, "leaf-cleared")), "Mining aborted:", len(since(m, "Mining aborted")))
    chat("/bot stop Fol", 2)
elif st == "clean":
    chat(f"/bot despawn {sys.argv[2]}", 3)
else:
    print(__doc__)
