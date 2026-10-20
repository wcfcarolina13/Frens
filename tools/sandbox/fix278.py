#!/usr/bin/env python3
"""1.1.278 checks in the mcp-sandbox (defensive mutual aid, golden-apple yes/no, go_to across dimensions, doorway line-up,
Collect messages, follow and woodcut regressions). Commands-on save "Sweep Souls3", game loaded, control mode on. Every stage
builds its own floating stone deck at Z 800-1000 (Y=120, feet at Y), away from fix275-277's decks (X 240-480, Z 300-720).

  fix278.py setup                 gamerules (no day cycle, no mobs), noon, easy difficulty
  fix278.py aid [sword|bow|bowarmor]     check 1: Jake (fed, the weapon) and Bob (fed to 14, bare-handed, health 13 so he "needs support";
                                  MutualAidService only defends a hungry/hurt/hunting ally and only from a meaningfully stronger
                                  bot) 15 blocks apart on a glass-railed 41x17 deck, both /bot stop'd; a fire-resistant zombie summoned 4 blocks
                                  past Bob, 19 from Jake (outside his 8-block threat skip and 10-block own-combat scan). Prints
                                  Jake's track, the zombie's fate, "Mutual aid:" lines, nudge WARNs and "Can't keep up!" lines, and
                                  whether Jake still moves 5 s after the zombie is gone. bow: a bow
                                  and arrows only (mutual aid ignores bows, finding 53); bowarmor: plus an iron chestplate.
  fix278.py apple [yes|no]        check 2: a fresh bot Apple with only a golden apple, food drained to 3-5 with the hunger effect
                                  (health stays > 6), the effect cleared; waits for one "(yes/no)" prompt, answers in chat, then
                                  watches the apple count and food for 15 s. Answer "no" -> "Alright. I'll save it." and no eat.
  fix278.py feed                  check 2, hand-feed: Apple at food ~10 with no food, the player holds a golden apple and right-clicks
                                  Apple (fork keybind key.use, facing the bot); the [Yes] click is the /frens feed confirm <uuid>
                                  command it runs. Pass: "accepted food", the apple eaten, no "(yes/no)" prompt.
  fix278.py goto [cross|walk]     check 3: a 21x21 netherrack floor in the Nether (X 600, Z 600, Y 70, 4 air above, a netherrack
                                  wall with one gap between start and goal), bot Nethy on it. cross: the player in the Overworld,
                                  /bot go_to Nethy 610 70 610 false -> "Nethy can't go there across dimensions."; walk: the player
                                  on the Nether floor, /bot go_to Nethy ~10 ~ ~10 false; pass: Nethy ends within 1.5 of the goal
                                  and never stands in a wall cell.
  fix278.py door [A|B|C]          check 4: a 7-wide corridor (side walls 3 high) crossed by a 5-wide 3-high stone wall with a 1x2
                                  closed oak door in the middle (stone above it). A: one bot follows from 6 blocks before the door,
                                  the player 7 past it; B: two bots; C: A with the hinge flipped. Polls bot positions and the door
                                  state; prints time through, door-plan/door-close lines, head-not-passable count and any door shut
                                  with a bot in the doorway.
  fix278.py regress               check 6: fix277.py follow (fix276's natural-terrain spot) and fix277.py woodcut.
  fix278.py clean <bot>           despawn a stage's bot
Collect (check 5) is driven by hand through the Bot Storage screen (ft.py shot / clickr); see the Phase 6bp notes.
Flashback: tools/sandbox/rec.py start / stop frens_1_1_278_6bp_<topic>.
"""
import json, os, re, subprocess, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
MC = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft"
LOG = MC + "/logs/latest.log"
HERE = os.path.dirname(os.path.abspath(__file__))
X, Y, Z = 300, 120, 800  # feet at Y; deck top is Y-1


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


def watch(m, secs, pat, stop=None):
    seen, end, hits = m, time.time() + secs, []
    while time.time() < end:
        ls = lines()
        for l in ls[seen:]:
            if re.search(pat, l):
                print(l[:260]); hits.append(l)
                if stop and re.search(stop, l):
                    return hits
        seen = len(ls); time.sleep(1)
    return hits


def state(bot):
    try:
        return json.loads(cmd(cmd="bot_state", name=bot))
    except Exception:  # noqa: BLE001
        return {}


def pos(bot):
    p = state(bot).get("pos")
    return tuple(round(float(v), 1) for v in p[:3]) if isinstance(p, list) else None


def count(bot, item):
    return sum(s.get("count", 0) for s in state(bot).get("inventory", []) if s.get("id") == item)


def spawn(bot, x, y, z):
    chat(f"/bot spawn {bot} admin", 12)
    chat(f"/tp {bot} {x} {y} {z}", 2)
    chat(f"/clear {bot}", 1)


def give(bot, *items):
    for it in items:
        chat(f"/give {bot} {it}", 1)


def deck(cx, cz, rx, rz, h=6):
    chat(f"/fill {cx-rx} {Y-3} {cz-rz} {cx+rx} {Y+h} {cz+rz} air", 1)
    chat(f"/fill {cx-rx} {Y-3} {cz-rz} {cx+rx} {Y-1} {cz+rz} stone", 1)


def cant_keep_up(m):
    return [l for l in since(m, r"Can't keep up") if "Server thread" in l or "WARN" in l]


st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear",
              "/difficulty easy"):
        chat(t, 1)
elif st == "aid":
    variant = sys.argv[2] if len(sys.argv) > 2 else "sword"
    cx, cz = X + {"sword": 0, "bow": 60, "bowarmor": 60}[variant], Z
    chat(f"/tp @p {cx} {Y+4} {cz+6} 180 30", 4)
    deck(cx, cz, 20, 8)
    # a 2-high glass rail all round: the first run's strike knocked the zombie off a 29x9 deck
    chat(f"/fill {cx-20} {Y} {cz-8} {cx+20} {Y+1} {cz+8} glass", 1)
    chat(f"/fill {cx-19} {Y} {cz-7} {cx+19} {Y+1} {cz+7} air", 1)
    chat(f"/tp @p {cx} {Y} {cz+4} 180 20", 2)
    chat("/kill @e[type=zombie]", 1)
    jx, bx, zx = cx - 8, cx + 7, cx + 11
    for b, x_ in (("Jake", jx), ("Bob", bx)):
        chat(f"/bot stop {b}", 1)
        chat(f"/tp {b} {x_} {Y} {cz}", 2)
        chat(f"/clear {b}", 1)
        chat(f"/effect clear {b}", 1)
    give("Jake", "iron_sword 1" if variant == "sword" else "bow 1", "bread 8")
    if variant.startswith("bow"):
        give("Jake", "arrow 32")
    if variant == "bowarmor":  # a bow is not a weapon to mutual aid (weaponScore): 6 armor makes Jake combat-capable
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
    track, gone = [], None
    while time.time() - t0 < 40:
        p = pos("Jake")
        track.append((round(time.time() - t0, 1), p and p[0]))
        if gone is None and len(track) % 6 == 1:
            mm = mark()
            chat("/data get entity @e[type=zombie,limit=1] Pos", 0.3)
            zp = since(mm, r"has the following entity data: \[")
            if zp:
                print(f"  t+{track[-1][0]} zombie", re.search(r"data: (\[.*\])", zp[-1]).group(1)[:60], "Jake", p)
        if gone is None:
            mm = mark()  # an unnamed mob's death is not logged: ask the world
            chat("/execute unless entity @e[type=zombie] run say NO_ZOMBIE", 0.3)
            if since(mm, r"NO_ZOMBIE"):
                gone = round(time.time() - t0, 1)
        if gone is not None and time.time() - t0 > gone + 12:
            break
        time.sleep(0.5)
    print("Jake x track:", track)
    for l in since(m, r"Mutual aid|nudgeTowardUntilClose|Can't keep up|[Zz]ombie|Monster Hunter|Bob.*(flee|Flee)|Jake.*(combat|engag)")[:30]:
        print("  >", l[:240])
    print("gone at", gone, "| nudge WARN:", len(since(m, r"nudgeTowardUntilClose on the server thread")),
          "| Can't keep up:", len(since(m, r"Can't keep up")))
    p1 = pos("Jake"); time.sleep(5); p2 = pos("Jake")
    print("Jake 5 s apart:", p1, p2, "| Bob", pos("Bob"), "health", state("Bob").get("health"))
    chat("/kill @e[type=zombie]", 1)
elif st == "apple":
    answer = sys.argv[2] if len(sys.argv) > 2 else "yes"
    bot = "Apple"
    cx, cz = X + 120, Z
    if not pos(bot):
        chat(f"/tp @p {cx} {Y+4} {cz+6} 180 30", 4)
        deck(cx, cz, 6, 6)
        chat(f"/tp @p {cx} {Y} {cz+4} 180 20", 2)
        spawn(bot, cx, Y, cz - 2)
    chat(f"/bot stop {bot}", 1)
    chat(f"/clear {bot}", 1)
    chat(f"/effect clear {bot}", 1)
    chat(f"/effect give {bot} saturation 1 20 true", 2)
    give(bot, "golden_apple 1")
    print("start:", {k: state(bot).get(k) for k in ("health", "food", "saturation")}, "apples", count(bot, "minecraft:golden_apple"))
    chat(f"/effect give {bot} hunger 60 40 true", 0.2)
    t0 = time.time()
    while time.time() - t0 < 60:
        f = state(bot).get("food", 20)
        if f <= 5:
            break
        time.sleep(0.25)
    chat(f"/effect clear {bot}", 0.5)
    s = state(bot)
    print(f"drained in {round(time.time()-t0,1)} s:", {k: s.get(k) for k in ("health", "food", "saturation")})
    m = mark()
    hits = watch(m, 25, r"\(yes/no\)|Desperate food|Understood|save it", stop=r"\(yes/no\)")
    prompts = [l for l in since(m, r"\(yes/no\)")]
    if not prompts:
        print("NO PROMPT within 25 s; state", {k: state(bot).get(k) for k in ("health", "food")})
        sys.exit(0)
    t1 = time.time()
    chat(answer, 1)
    track = []
    while time.time() - t1 < 15:
        s = state(bot)
        track.append((round(time.time() - t1, 1), s.get("food"), count(bot, "minecraft:golden_apple")))
        time.sleep(1)
    print("after the answer (t, food, apples):", track)
    for l in since(m, r"\(yes/no\)|Understood|save it|Desperate food|precious|Apple.*(eat|consum)")[:12]:
        print("  >", l[:260])
    print("prompts:", len(since(m, r"\(yes/no\)")))
elif st == "feed":
    bot = "Apple"
    cx, cz = X + 120, Z
    chat(f"/bot stop {bot}", 1)
    chat(f"/clear {bot}", 1)
    chat(f"/tp {bot} {cx} {Y} {cz-2}", 1)
    chat(f"/effect clear {bot}", 1)
    chat(f"/effect give {bot} saturation 1 20 true", 2)
    chat(f"/effect give {bot} hunger 60 40 true", 0.2)
    t0 = time.time()
    while time.time() - t0 < 60 and state(bot).get("food", 20) > 10:
        time.sleep(0.25)
    chat(f"/effect clear {bot}", 0.5)
    print("Apple:", {k: state(bot).get(k) for k in ("health", "food")}, "apples", count(bot, "minecraft:golden_apple"))
    chat(f"/tp @p {cx} {Y} {cz} facing entity {bot} eyes", 1)
    chat("/item replace entity @p weapon.mainhand with golden_apple 1", 1)
    m = mark()
    print("use:", cmd(cmd="keybind", id="key.use")[:200])
    time.sleep(2)
    shown = since(m, r"Precious-food prompt shown|precious\. Eat it anyway")
    print("prompt:", [l[:200] for l in shown])
    uuid = state(bot).get("uuid")
    if not uuid:
        r = chat(f"/data get entity {bot} UUID", 2)
        u = since(m, r"has the following entity data: \[I;")
        if u:
            ints = [int(v) for v in re.search(r"\[I;\s*([-\d, ]+)\]", u[-1]).group(1).split(",")]
            h = "".join(f"{(v + (1 << 32)) % (1 << 32):08x}" for v in ints)
            uuid = f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"
    print("uuid", uuid)
    if shown and uuid:
        chat(f"/frens feed confirm {uuid}", 1)
    t1 = time.time(); track = []
    while time.time() - t1 < 10:
        s = state(bot)
        track.append((round(time.time() - t1, 1), s.get("food"), count(bot, "minecraft:golden_apple")))
        time.sleep(1)
    print("after confirm (t, food, apples):", track)
    for l in since(m, r"accepted food|\(yes/no\)|Understood|precious|Precious")[:10]:
        print("  >", l[:260])
    print("(yes/no) prompts:", len(since(m, r"\(yes/no\)")))
elif st == "goto":
    mode = sys.argv[2] if len(sys.argv) > 2 else "walk"
    bot = "Nethy"
    nx, ny, nz = 600, 70, 600
    if mode == "build":
        # never @p under "execute in": it picks the nearest player in that dimension, and bots are players
        # (the first build sent Fisher and Axer2 into the Nether; Fisher suffocated)
        chat(f"/execute in minecraft:the_nether run tp RotiWokeman {nx} {ny+20} {nz}", 5)
        chat(f"/execute in minecraft:the_nether run fill {nx-6} {ny} {nz-6} {nx+16} {ny+5} {nz+16} air", 2)
        chat(f"/execute in minecraft:the_nether run fill {nx-6} {ny-2} {nz-6} {nx+16} {ny-1} {nz+16} netherrack", 2)
        # a wall from (nx+2..nx+12, nz+5) with one gap at nx+11: the straight diagonal is blocked
        chat(f"/execute in minecraft:the_nether run fill {nx-6} {ny} {nz+5} {nx+10} {ny+2} {nz+5} netherrack", 1)
        chat(f"/execute in minecraft:the_nether run fill {nx+12} {ny} {nz+5} {nx+16} {ny+2} {nz+5} netherrack", 1)
        chat(f"/execute in minecraft:the_nether run tp RotiWokeman {nx} {ny} {nz} 0 20", 3)
        if not pos(bot):
            spawn(bot, X + 120, Y, Z - 2)
        chat(f"/execute in minecraft:the_nether run tp {bot} {nx} {ny} {nz}", 3)
        print(bot, state(bot).get("dim"), pos(bot))
        sys.exit(0)
    if mode == "cross":
        chat(f"/execute in minecraft:overworld run tp RotiWokeman {X+120} {Y} {Z+4}", 4)
        print("player dim:", json.loads(cmd(cmd="players")).get("players", [{}])[0].get("pos"), "bot", state(bot).get("dim"), pos(bot))
        m = mark()
        chat(f"/bot go_to {bot} {nx+10} {ny} {nz+10} false", 3)
        for l in since(m, r"across dimensions|Finding the shortest path|Path output|go_to"):
            print("  >", l[:240])
        time.sleep(3)
        print(bot, "after:", state(bot).get("dim"), pos(bot))
        sys.exit(0)
    chat(f"/execute in minecraft:the_nether run tp RotiWokeman {nx} {ny} {nz} 0 20", 4)
    chat(f"/execute in minecraft:the_nether run tp {bot} {nx} {ny} {nz}", 2)
    p0 = pos(bot); print("start", state(bot).get("dim"), p0)
    m = mark(); t0 = time.time()
    chat(f"/bot go_to {bot} ~10 ~ ~10 false", 0.5)
    wall = {(x_, nz + 5) for x_ in list(range(nx - 6, nx + 11)) + list(range(nx + 12, nx + 17))}
    track, inwall, end = [], 0, None
    while time.time() - t0 < 40:
        s = state(bot); p = s.get("pos"); b = s.get("block")
        if p:
            track.append((round(time.time() - t0, 1), round(p[0], 1), round(p[1], 1), round(p[2], 1)))
            if b and (b[0], b[2]) in wall and b[1] in (ny, ny + 1, ny + 2):
                inwall += 1
            if abs(p[0] - (nx + 10.5)) < 1.6 and abs(p[2] - (nz + 10.5)) < 1.6 and end is None:
                end = round(time.time() - t0, 1)
        if end is not None and time.time() - t0 > end + 3:
            break
        time.sleep(0.5)
    print("track:", track)
    print(f"reached goal at t+{end} s; samples inside the wall: {inwall}; end {pos(bot)}")
    for l in since(m, r"across dimensions|Path output|Generated segments|Can't keep up|go_to|PathTracer|Nethy.*(stuck|suffocat|wall)")[:10]:
        print("  >", l[:300])
    print("Can't keep up:", len(since(m, r"Can't keep up")))
elif st == "door":
    variant = sys.argv[2] if len(sys.argv) > 2 else "A"
    cx, cz = X + 180 + {"A": 0, "B": 30, "C": 60}[variant], Z
    hinge = "right" if variant == "C" else "left"
    bots = ("DoorA",) if variant != "B" else ("DoorB1", "DoorB2")
    if variant == "C":
        bots = ("DoorC",)
    chat(f"/tp @p {cx} {Y+5} {cz+9} 180 30", 4)
    chat(f"/fill {cx-4} {Y-3} {cz-10} {cx+4} {Y+5} {cz+10} air", 1)
    chat(f"/fill {cx-4} {Y-2} {cz-10} {cx+4} {Y-1} {cz+10} stone", 1)
    chat(f"/fill {cx-3} {Y} {cz-10} {cx-3} {Y+2} {cz+10} stone", 1)   # side walls, corridor x cx-2..cx+2
    chat(f"/fill {cx+3} {Y} {cz-10} {cx+3} {Y+2} {cz+10} stone", 1)
    chat(f"/fill {cx-2} {Y} {cz} {cx+2} {Y+2} {cz} stone", 1)          # the 5-wide 3-high cross wall
    chat(f"/setblock {cx} {Y} {cz} oak_door[half=lower,facing=south,hinge={hinge},open=false]", 1)
    chat(f"/setblock {cx} {Y+1} {cz} oak_door[half=upper,facing=south,hinge={hinge},open=false]", 1)
    print("door column:", json.loads(cmd(cmd="blocks", x=cx, y=Y, z=cz, r=0, dy=2)).get("levels"))
    chat(f"/tp @p {cx} {Y} {cz+7} 180 10", 2)
    for i, b in enumerate(bots):
        if not pos(b):
            spawn(b, cx - 1 + 2 * i, Y, cz - 6)
            give(b, "bread 8")
        chat(f"/bot stop {b}", 1)
        chat(f"/tp {b} {cx - 1 + 2 * i} {Y} {cz - 6}", 1)
    chat(f"/setblock {cx} {Y} {cz} oak_door[half=lower,facing=south,hinge={hinge},open=false]", 0.5)
    m = mark(); t0 = time.time()
    for b in bots:
        chat(f"/bot follow {b}", 0.5)
    through = {b: None for b in bots}
    shut_on_bot, samples, door_log = 0, [], []
    while time.time() - t0 < 30:
        ps = {b: state(b).get("block") for b in bots}
        mm = mark()
        chat(f"/execute if block {cx} {Y} {cz} oak_door[open=true] run say DOOR_OPEN", 0.3)
        is_open = bool(since(mm, r"DOOR_OPEN"))
        t = round(time.time() - t0, 1)
        samples.append((t, "open" if is_open else "shut", {b: (p and (p[0], p[2])) for b, p in ps.items()}))
        for b, p in ps.items():
            if p and p[2] > cz and through[b] is None:
                through[b] = t
            if p and not is_open and p[0] == cx and p[2] == cz:
                shut_on_bot += 1
        if all(v is not None for v in through.values()) and time.time() - t0 > max(through.values()) + 6:
            break
    mm = mark()
    chat(f"/execute if block {cx} {Y} {cz} oak_door[open=false] run say DOOR_CLOSED_END", 1)
    closed_end = bool(since(mm, r"DOOR_CLOSED_END"))
    print("samples:", samples)
    print(f"through at {through}; door closed at the end: {closed_end}; shut with a bot in the doorway: {shut_on_bot}")
    for b in bots:
        print(b, "end", pos(b))
    for l in since(m, r"door-plan|door-close|door-loop|Door|door ")[:25]:
        print("  >", l[:240])
    print("door-plan abort:", len(since(m, r"door-plan abort")), "head-not-passable:",
          len(since(m, r"head-not-passable")), "Follow decision lines:", len(since(m, r"Follow decision")))
    for b in bots:
        chat(f"/bot stop {b}", 1)
elif st == "regress":
    for args in (["follow"], ["woodcut"]):
        print("== fix277.py", *args)
        subprocess.run([sys.executable, os.path.join(HERE, "fix277.py"), *args])
elif st == "clean":
    chat(f"/bot despawn {sys.argv[2]}", 3)
else:
    print(__doc__)
