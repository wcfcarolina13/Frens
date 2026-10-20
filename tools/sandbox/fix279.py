#!/usr/bin/env python3
"""1.1.279 checks in the mcp-sandbox (same-cell steps don't clip into walls, make-room never drops a valuable stack,
server-thread crafts never walk to or place a chest, fresh stuck counters per return, /bot cook places its own furnace,
bundled food counts, follow/woodcut/door regressions). Commands-on save "Sweep Souls3", game loaded, control mode on.
Every stage builds its own floating stone deck at Z ~900 (Y=120, feet at Y), clear of fix275-278 (Z 300-810).
The bot-actions logger at DEBUG (sandbox log4j file) shows `moveRelative-same-cell ... result=clamped(..)|refused`.

  fix279.py setup                 gamerules (no day cycle, no mobs), noon, easy difficulty
  fix279.py kite [rounds]         check 1: Kiter (iron sword) in a 1-wide stone corridor, back 0.2 from the north wall
                                  (centre 0.5 from it), facing south; a knockback-proof zombie held still (slowness
                                  255) 1.4 blocks in front with 200 health, so it outlives the 20 s watch (a free zombie was
                                  knocked 9 blocks down the corridor and chased; a NoAI one was never fought).
                                  Melee kiting backpedals (moveBackward 0.45) after each swing. Polls the bot's box against the
                                  corridor walls every 0.25 s; prints the deepest overlap, IN_WALL WARNs and same-cell lines.
  fix279.py drown                 check 1: Swimmer in a 3x3 water pool 2 deep with a stone roof (so it is submerged and the
                                  drowning rescue steps it forward each tick), facing the north wall; 12 s roofed, then the roof
                                  is opened for 8 s. Same box checks against the pool walls.
  fix279.py drop                  check 1: a diamond at the foot of a corridor's back wall, /bot skill drop_sweep Dropper.
  fix279.py wedge                 check 1: Wedge /tp'd into a closed oak door's cell, then onto a bottom stair's step half;
                                  /bot follow each time. Pass: it leaves the cell.
  fix279.py full                  check 2 (aid make-room): Fullz with 36 distinct non-cheap, non-food stacks (ingots, gems,
                                  iron tools; a first run with raw meat ate it as fast as the hunger effect drained, so food
                                  never reached the starving threshold 5), kept starving with the hunger effect, bread summoned at its feet
                                  (BotMutualAidService FOOD_PICKUP; a food *handoff* from an ally needs no room by policy).
                                  Watches 45 s: make-room lines, drops, the owner line, the inventory before and after.
  fix279.py craft [full|rotten|mats]  check 2 (server-thread craft): Smith2 with wooden pickaxe and shovel, 16 cobblestone,
                                  8 sticks, bread and non-tool-material valuables to fill 36 slots (rotten: one is 5 rotten
                                  flesh; mats: the fill includes diamonds and iron, gold and copper ingots, finding 57), a table 2 blocks away,
                                  a chest 12 blocks away, a base, idle hobbies on. Watches 90 s: craft refusal / drop lines,
                                  chest placements, its position.
  fix279.py lane                  check 3: stucktest.py's lane pocket on a fresh deck with its own base 14 blocks NW: /bot return,
                                  30 s, /bot stay, /bot return again, 30 s. Times of the backup / flare lines per return and
                                  jump-sprint "succeeded" lines against polled cell changes.
  fix279.py cook                  check 4: Cooky with a furnace, 8 beef and 8 coal in the bag, nothing placed nearby:
                                  /bot cook beef coal Cooky.
  fix279.py bundle                check 5: Bun with a stone axe and a bundle of 8 bread (no loose food), one tree; woodcut, hunger
                                  effect pulses; food level and loose bread tracked; any "I have no food" line.
  fix279.py regress               check 6: fix277.py follow 60 230 46 south, fix277.py woodcut, fix278.py door A.
  fix279.py clean <bot>           despawn a stage's bot
Flashback: tools/sandbox/rec.py start / stop frens_1_1_279_6bq_<topic>.
"""
import json, os, re, subprocess, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
MC = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft"
LOG = MC + "/logs/latest.log"
HERE = os.path.dirname(os.path.abspath(__file__))
PLAYER = "RotiWokeman"
X, Y, Z = 300, 120, 900  # feet at Y; deck top is Y-1


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
    return tuple(float(v) for v in p[:3]) if isinstance(p, list) else None


def inv(bot):
    return [(i["id"].split(":")[1], i["count"]) for i in state(bot).get("inventory", [])]


def count(bot, item):
    return sum(c for i, c in inv(bot) if i == item)


def spawn(bot, x, y, z):
    chat(f"/bot spawn {bot} admin", 12)
    chat(f"/tp {bot} {x} {y} {z}", 2)
    chat(f"/clear {bot}", 1)


def ensure(bot, x, y, z):
    if not pos(bot):
        spawn(bot, x, y, z)
    chat(f"/bot stop {bot}", 1)
    chat(f"/clear {bot}", 1)
    chat(f"/effect clear {bot}", 1)
    chat(f"/tp {bot} {x} {y} {z}", 2)


def give(bot, *items):
    for it in items:
        chat(f"/give {bot} {it}", 0.6)


def deck(x0, z0, x1, z1, h=6):
    chat(f"/fill {x0} {Y-3} {z0} {x1} {Y+h} {z1} air", 1)
    chat(f"/fill {x0} {Y-3} {z0} {x1} {Y-1} {z1} stone", 1)


def overlap(p, blocks, height=1.8):
    """Deepest overlap (blocks) of a player box at p (0.6 wide, height tall) with any full block in blocks.
    A swimming bot is 0.6 tall (the drowning rescue sets swimming), so its box is checked with height 0.6."""
    if not p:
        return 0.0
    bx0, bx1, by0, by1, bz0, bz1 = p[0] - 0.3, p[0] + 0.3, p[1], p[1] + height, p[2] - 0.3, p[2] + 0.3
    worst = 0.0
    for (x, y, z) in blocks:
        ox = min(bx1, x + 1) - max(bx0, x)
        oy = min(by1, y + 1) - max(by0, y)
        oz = min(bz1, z + 1) - max(bz0, z)
        if ox > 0 and oy > 0 and oz > 0:
            worst = max(worst, min(ox, oz))
    return round(worst, 3)


def track(bot, secs, blocks, until=None, dt=0.25, height=1.8):
    """Poll the bot's position; return (samples overlapping > 0.01, worst overlap, positions)."""
    t0, bad, worst, ps = time.time(), 0, 0.0, []
    while time.time() - t0 < secs:
        p = pos(bot)
        o = overlap(p, blocks, height)
        if o > 0.01:
            bad += 1
        worst = max(worst, o)
        ps.append((round(time.time() - t0, 1), p and tuple(round(v, 2) for v in p)))
        if until and until():
            break
        time.sleep(dt)
    return bad, worst, ps


def wall_report(m, bot):
    inwall = since(m, rf"Bot {bot} taking IN_WALL damage")
    same = since(m, rf"moveRelative-same-cell bot={bot} ")
    print(f"IN_WALL WARN lines: {len(inwall)}; same-cell lines: {len(same)} "
          f"(clamped {sum('clamped' in l for l in same)}, refused {sum('refused' in l for l in same)}); "
          f"Can't keep up: {len(since(m, r'Can.t keep up'))}")
    for l in same[:6]:
        print("  same>", l.split("moveRelative-same-cell ")[1][:220])
    for l in inwall[:3]:
        print("  wall>", l[:200])


def zombie_gone():
    mm = mark()
    chat("/execute unless entity @e[type=zombie] run say NO_ZOMBIE", 0.2)
    return bool(since(mm, "NO_ZOMBIE"))


VALUABLES = ["iron_ingot 5", "gold_ingot 5", "copper_ingot 5", "diamond 3", "emerald 3", "lapis_lazuli 6", "redstone 7",
             "quartz 4", "amethyst_shard 3", "raw_iron 4", "raw_gold 4", "raw_copper 4", "coal 6", "charcoal 5",
             "iron_nugget 9", "gold_nugget 9", "glowstone_dust 4", "gunpowder 4", "paper 6", "book 2", "slime_ball 3",
             "ender_pearl 2", "blaze_rod 2", "prismarine_shard 3", "flint 4", "clay_ball 5", "brick 4", "glass_bottle 3",
             "iron_pickaxe 1", "iron_shovel 1", "iron_axe 1", "shears 1", "flint_and_steel 1", "bucket 1", "compass 1",
             "clock 1"]

st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear",
              "/difficulty easy"):
        chat(t, 1)
elif st == "kite":
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 2
    cx, cz = X, Z
    chat(f"/tp {PLAYER} {cx} {Y+6} {cz-4} 0 40", 4)
    deck(cx - 4, cz - 6, cx + 4, cz + 10)
    chat(f"/fill {cx-1} {Y} {cz-1} {cx-1} {Y+2} {cz+8} stone", 1)
    chat(f"/fill {cx+1} {Y} {cz-1} {cx+1} {Y+2} {cz+8} stone", 1)
    chat(f"/setblock {cx} {Y} {cz-1} stone", 0.5); chat(f"/setblock {cx} {Y+1} {cz-1} stone", 0.5)
    chat(f"/setblock {cx} {Y+2} {cz-1} stone", 0.5)
    walls = [(cx - 1, y, z) for y in (Y, Y + 1) for z in range(cz - 1, cz + 9)] + \
            [(cx + 1, y, z) for y in (Y, Y + 1) for z in range(cz - 1, cz + 9)] + [(cx, Y, cz - 1), (cx, Y + 1, cz - 1)]
    chat(f"/tp {PLAYER} {cx} {Y} {cz-4} 0 10", 2)
    chat("/kill @e[type=zombie]", 1)
    ensure("Kiter", cx + 0.5, Y, cz + 0.5)
    give("Kiter", "iron_sword 1", "bread 8")
    m = mark()
    total_bad, total_worst = 0, 0.0
    for r in range(rounds):
        chat(f"/tp Kiter {cx+0.5} {Y} {cz+0.5} 0 0", 1)
        chat("/effect give Kiter instant_health 1 3 true", 0.5)
        # 200 health: the bot keeps swinging and backpedalling at the wall for the whole watch
        # full knockback resistance + slowness 255: the first runs knocked a free zombie 9 blocks down the corridor, so
        # the bot chased it and never backpedalled near the wall; a NoAI zombie was never fought at all
        chat(f"/summon zombie {cx+0.5} {Y} {cz+1.9} {{PersistenceRequired:1b,Rotation:[180f,0f],"
             f"Health:200f,attributes:[{{id:\"minecraft:knockback_resistance\",base:1.0}},{{id:\"minecraft:max_health\",base:200}}]}}", 0.3)
        chat("/effect give @e[type=zombie] fire_resistance 120 0 true", 0.1)
        chat("/effect give @e[type=zombie] slowness 25 255 true", 0.1)
        bad, worst, ps = track("Kiter", 20, walls, dt=0.25)
        total_bad += bad; total_worst = max(total_worst, worst)
        zs = [p[1][2] for p in ps if p[1]]
        print(f"round {r+1}: {len(ps)} samples, z range {min(zs) if zs else None}..{max(zs) if zs else None} "
              f"(wall face at {cz}.0, flush centre {cz}.3), overlapping samples {bad}, worst {worst}")
        print("  samples:", ps[::3][:20])
        chat("/kill @e[type=zombie]", 1)
    print(f"kite total: overlapping samples {total_bad}, worst overlap {total_worst}")
    for l in since(m, r"Kiter.*(attack|Attack|kite|backpedal)|Bot Kiter fleeing")[:6]:
        print("  >", l[:220])
    wall_report(m, "Kiter")
    print("Kiter health", state("Kiter").get("health"))
elif st == "drown":
    cx, cz = X + 30, Z
    chat(f"/tp {PLAYER} {cx} {Y+8} {cz+5} 180 50", 4)
    deck(cx - 4, cz - 4, cx + 4, cz + 6)
    chat(f"/fill {cx-2} {Y} {cz-2} {cx+2} {Y+3} {cz+2} stone", 1)
    chat(f"/fill {cx-1} {Y} {cz-1} {cx+1} {Y+1} {cz+1} water", 1)
    walls = [(x, y, z) for x in range(cx - 2, cx + 3) for z in range(cz - 2, cz + 3) for y in (Y, Y + 1, Y + 2)
             if not (cx - 1 <= x <= cx + 1 and cz - 1 <= z <= cz + 1 and y <= Y + 1)]
    chat(f"/tp {PLAYER} {cx+3} {Y+4} {cz+3} 135 40", 2)
    ensure("Swimmer", cx + 0.5, Y + 5, cz + 4.5)
    give("Swimmer", "bread 8")
    m = mark()
    chat(f"/tp Swimmer {cx+0.5} {Y} {cz-0.5} 180 0", 0.3)
    print("roofed: air", state("Swimmer").get("air"))
    bad1, worst1, ps1 = track("Swimmer", 12, walls, height=0.6)
    print(f"roofed 12 s: overlapping samples {bad1}, worst {worst1}; z range "
          f"{min(p[1][2] for p in ps1 if p[1])}..{max(p[1][2] for p in ps1 if p[1])} (pool faces z {cz-1}.0/{cz+2}.0), x "
          f"{min(p[1][0] for p in ps1 if p[1])}..{max(p[1][0] for p in ps1 if p[1])} (faces {cx-1}.0/{cx+2}.0)")
    print("  samples:", ps1[:16])
    chat(f"/fill {cx-1} {Y+2} {cz-1} {cx+1} {Y+3} {cz+1} air", 0.3)
    rim = [b for b in walls if not (cx - 1 <= b[0] <= cx + 1 and cz - 1 <= b[2] <= cz + 1)]  # the roof is gone
    bad2, worst2, ps2 = track("Swimmer", 8, rim, height=0.6)
    print(f"open 8 s: overlapping samples {bad2}, worst {worst2}; samples {ps2[::4]}")
    wall_report(m, "Swimmer")
    for l in since(m, r"Swimmer.*(drown|Drown|water|ledge|escape|suffocat)")[:8]:
        print("  >", l[:220])
    s = state("Swimmer"); print("Swimmer health", s.get("health"), "air", s.get("air"))
    chat(f"/tp Swimmer {cx+0.5} {Y} {cz+4.5}", 1)
elif st == "drop":
    cx, cz = X + 60, Z
    chat(f"/tp {PLAYER} {cx} {Y+6} {cz+8} 180 40", 4)
    deck(cx - 4, cz - 4, cx + 4, cz + 10)
    chat(f"/fill {cx-1} {Y} {cz-1} {cx-1} {Y+2} {cz+8} stone", 1)
    chat(f"/fill {cx+1} {Y} {cz-1} {cx+1} {Y+2} {cz+8} stone", 1)
    chat(f"/fill {cx} {Y} {cz-1} {cx} {Y+2} {cz-1} stone", 1)
    walls = [(cx - 1, y, z) for y in (Y, Y + 1) for z in range(cz - 1, cz + 9)] + \
            [(cx + 1, y, z) for y in (Y, Y + 1) for z in range(cz - 1, cz + 9)] + [(cx, Y, cz - 1), (cx, Y + 1, cz - 1)]
    chat(f"/tp {PLAYER} {cx+3} {Y} {cz+8} 180 20", 2)
    ensure("Dropper", cx + 0.5, Y, cz + 5.5)
    give("Dropper", "bread 4")
    m = mark()
    chat(f'/summon item {cx+0.5} {Y} {cz+0.15} {{Item:{{id:"minecraft:diamond",count:3}},PickupDelay:0s}}', 0.5)
    chat("/bot skill drop_sweep Dropper", 0.3)
    bad, worst, ps = track("Dropper", 25, walls)
    print(f"drop: overlapping samples {bad}, worst {worst}; min z {min(p[1][2] for p in ps if p[1])} (wall face {cz}.0)")
    print("  samples:", ps[::4][:20])
    print("diamonds in bag:", count("Dropper", "diamond"))
    for l in since(m, r"Drop sweep|drop_sweep|Picked up|Dropper.*(nudge|wall)")[:8]:
        print("  >", l[:220])
    wall_report(m, "Dropper")
elif st == "wedge":
    cx, cz = X + 90, Z
    chat(f"/tp {PLAYER} {cx} {Y+6} {cz+8} 180 40", 4)
    deck(cx - 4, cz - 4, cx + 10, cz + 10)
    chat(f"/fill {cx-2} {Y} {cz} {cx+2} {Y+2} {cz} stone", 1)
    chat(f"/setblock {cx} {Y} {cz} oak_door[half=lower,facing=south,hinge=left,open=false]", 0.5)
    chat(f"/setblock {cx} {Y+1} {cz} oak_door[half=upper,facing=south,hinge=left,open=false]", 0.5)
    chat(f"/setblock {cx+6} {Y} {cz} oak_stairs[facing=north,half=bottom]", 0.5)
    chat(f"/tp {PLAYER} {cx+3} {Y} {cz+7} 180 10", 2)
    ensure("Wedge", cx + 0.5, Y, cz + 4.5)
    give("Wedge", "bread 4")
    for label, spot, cell in (("door", (cx + 0.5, Y, cz + 0.5), (cx, cz)), ("stair", (cx + 6.5, Y + 0.1, cz + 0.5), (cx + 6, cz))):
        chat("/bot stop Wedge", 1)
        chat(f"/tp Wedge {spot[0]} {spot[1]} {spot[2]} 0 0", 1)
        p0 = pos("Wedge"); print(label, "start", p0)
        m = mark()
        chat("/bot follow Wedge", 0.3)
        t0, out_at, ps = time.time(), None, []
        while time.time() - t0 < 20:
            p = pos("Wedge")
            ps.append((round(time.time() - t0, 1), p and tuple(round(v, 2) for v in p)))
            if p and out_at is None and (int(p[0] // 1), int(p[2] // 1)) != cell:
                out_at = round(time.time() - t0, 1)
            if out_at is not None and time.time() - t0 > out_at + 3:
                break
            time.sleep(0.25)
        print(f"{label}: left the cell at {out_at} s; end {ps[-1]}")
        print("  samples:", ps[::4][:12])
        wall_report(m, "Wedge")
    chat("/bot stop Wedge", 1)
elif st == "full":
    cx, cz = X + 120, Z
    chat(f"/tp {PLAYER} {cx} {Y+6} {cz+8} 180 40", 4)
    deck(cx - 6, cz - 6, cx + 6, cz + 8)
    chat(f"/tp {PLAYER} {cx} {Y} {cz+6} 180 20", 2)
    chat("/kill @e[type=item]", 1)
    ensure("Fullz", cx + 0.5, Y, cz + 0.5)
    give("Fullz", *VALUABLES)
    before = inv("Fullz")
    print(f"Fullz slots {len(before)}:", before)
    chat("/effect give Fullz saturation 1 0 true", 1)
    chat("/effect give Fullz hunger 120 60 true", 0.5)
    t0 = time.time()
    while time.time() - t0 < 60 and state("Fullz").get("food", 20) > 5:
        time.sleep(0.25)
    print(f"food {state('Fullz').get('food')} after {round(time.time()-t0,1)} s")
    m = mark()
    chat(f'/summon item {cx+0.5} {Y} {cz+1.5} {{Item:{{id:"minecraft:bread",count:4}}}}', 0.5)
    foods = []
    t0 = time.time()
    while time.time() - t0 < 45:
        s = state("Fullz"); foods.append((round(time.time() - t0), s.get("food"), len(s.get("inventory", []))))
        time.sleep(3)
    chat("/effect clear Fullz", 1)
    after = inv("Fullz")
    print("(t, food, slots):", foods)
    lost = [x for x in before if x[0] not in {a for a, _ in after}]
    print("stack types gone:", lost, "| counts changed:",
          [(i, c, dict(after).get(i)) for i, c in before if dict(after).get(i) not in (None, c)])
    for l in since(m, r"make-room|to free inventory space|disposable stack|cleared inventory space|Fullz> My pack|won't throw away|Desperate food|IdleSweep|Drop sweep")[:20]:
        print("  >", l[:240])
    print("owner lines:", len(since(m, r"won't throw away")), "| make-room hold-offs:", len(since(m, r"make-room: Fullz has nothing")),
          "| drops:", len(since(m, r"to free inventory space")), "| Can't keep up:", len(since(m, r"Can.t keep up")))
    mm = mark(); chat(f"/execute if entity @e[type=item,distance=..6,x={cx},y={Y},z={cz}] run say ITEMS_NEAR", 0.5)
    print("items on the deck:", bool(since(mm, "ITEMS_NEAR")))
elif st == "craft":
    variant = sys.argv[2] if len(sys.argv) > 2 else "full"
    bot = {"full": "Smith2", "rotten": "Smith3", "mats": "Smith2"}[variant]
    cx, cz = X + 160 + (40 if variant == "rotten" else 0), Z
    chat(f"/tp {PLAYER} {cx} {Y+6} {cz+8} 180 40", 4)
    deck(cx - 14, cz - 6, cx + 6, cz + 8)
    chat(f"/setblock {cx+2} {Y} {cz} crafting_table", 1)
    chat(f"/setblock {cx-12} {Y} {cz} chest", 1)
    chat(f"/tp {PLAYER} {cx+3} {Y} {cz+4} 180 20", 2)
    ensure(bot, cx + 0.5, Y, cz + 0.5)
    kit = ["wooden_pickaxe 1", "wooden_shovel 1", "cobblestone 16", "stick 8", "bread 8"]
    # no tool materials in the fill: the first run (with diamonds and iron) crafted a diamond sword and shovel and an
    # iron axe from them, after dropping the leftover cobblestone (finding 57)
    tool_mats = ("iron_pickaxe", "iron_shovel", "iron_axe", "iron_ingot", "gold_ingot", "copper_ingot", "diamond")
    fill = ([v for v in VALUABLES if not v.startswith(tool_mats)] + ["honeycomb 3", "rabbit_hide 2"])[:31]
    if variant == "mats":
        fill = [v for v in VALUABLES if not v.startswith(("iron_pickaxe", "iron_shovel", "iron_axe"))][:31]
    if variant == "rotten":
        fill[-1] = "rotten_flesh 5"
    give(bot, *(kit + fill))
    before = inv(bot)
    print(f"{bot} slots {len(before)}")
    chat(f"/bot base set craft279{variant}", 3)
    p0 = pos(bot)
    m = mark()
    chat(f"/bot idle_hobbies on {bot}", 1)
    t0, far = time.time(), 0.0
    while time.time() - t0 < 90:
        p = pos(bot)
        if p and p0:
            far = max(far, ((p[0] - p0[0]) ** 2 + (p[2] - p0[2]) ** 2) ** 0.5)
        if since(m, rf"Idle stone upgrade: {bot}|craft: {bot} has a full pack") and time.time() - t0 > 30:
            break
        time.sleep(1)
    after = inv(bot)
    print(f"farthest from start: {round(far, 2)} blocks; end {pos(bot)}")
    print("gone:", [x for x in before if x[0] not in {a for a, _ in after}], "| new:",
          [x for x in after if x[0] not in {a for a, _ in before}])
    for l in since(m, rf"craft: {bot}|Idle stone upgrade|to free inventory space|disposable stack|{bot}.*(chest|Chest)|"
                      rf"Placed chest|craft-station|can't craft|Can.t keep up|{bot}> ")[:20]:
        print("  >", l[:240])
    mm = mark()
    chat(f"/execute if block {cx-12} {Y} {cz} chest run say CHEST_STILL", 0.5)
    print("chest still there:", bool(since(mm, "CHEST_STILL")), "| chest placements:", len(since(m, r"Placed chest|placed a chest")),
          "| Can't keep up:", len(since(m, r"Can.t keep up")))
    chat(f"/bot idle_hobbies off {bot}", 1)
elif st == "lane":
    cx, cz = X + 240, Z
    bot = "Laney2"
    chat(f"/tp {PLAYER} {cx} {Y+6} {cz+6} 180 40", 4)
    deck(cx - 16, cz - 16, cx + 6, cz + 6)
    chat(f"/tp {PLAYER} {cx-12} {Y} {cz-12}", 2)
    chat("/bot base set lane279", 3)
    chat(f"/fill {cx-1} {Y} {cz-1} {cx+3} {Y+2} {cz+1} stone", 1)
    chat(f"/fill {cx} {Y} {cz} {cx+1} {Y+1} {cz} air", 1)
    chat(f"/fill {cx+2} {Y} {cz} {cx+2} {Y+1} {cz} oak_fence", 1)
    chat(f"/tp {PLAYER} {cx-3} {Y} {cz+4} 135 20", 2)
    ensure(bot, cx + 0.5, Y, cz + 0.5)
    chat(f"/bot stay {bot}", 1)
    chat(f"/tp {bot} {cx+0.5} {Y} {cz+0.5}", 2)
    PAT = rf"{bot} stuck for|bot={bot} (reached|stuck)|Stuck escape|quick-nudge|flare|ReturnBaseStuck: {bot}|scaffold-escape"
    for n in (1, 2):
        m = mark(); t0 = time.time()
        chat(f"/bot return {bot}", 0.3)
        cells = set()
        while time.time() - t0 < 30:
            p = pos(bot)
            if p:
                cells.add((int(p[0] // 1), int(p[2] // 1)))
            time.sleep(0.5)
        print(f"== return {n}: cells visited {sorted(cells)}")
        hits = since(m, PAT)
        for l in hits[:20]:
            ts = re.match(r"\[(\d\d):(\d\d):(\d\d)\]", l)
            print("  >", l[:230])
        print(f"  backup lines: {len(since(m, bot + r' stuck for \d+ ticks, attempting backup'))}, "
              f"flare lines: {len(since(m, rf'{bot}.*(flare|firework)'))}, jump-sprint succeeded: "
              f"{len(since(m, r'jump-sprint .* succeeded'))}, return sent at {time.strftime('%H:%M:%S', time.localtime(t0))}")
        if n == 1:
            chat(f"/bot stay {bot}", 5)
    chat(f"/bot stay {bot}", 1)
elif st == "cook":
    cx, cz = X + 300, Z
    chat(f"/tp {PLAYER} {cx} {Y+6} {cz+8} 180 40", 4)
    deck(cx - 6, cz - 6, cx + 6, cz + 8)
    chat(f"/tp {PLAYER} {cx+3} {Y} {cz+5} 180 20", 2)
    ensure("Cooky", cx + 0.5, Y, cz + 0.5)
    give("Cooky", "furnace 1", "beef 8", "coal 8", "bread 4")
    m = mark()
    chat("/bot cook beef coal Cooky", 1)
    hits = watch(m, 40, r"Smelting|furnace|Furnace|cook|Cook|Can.t keep up|Heading")
    print("bag:", inv("Cooky"))
    print("Can't keep up:", len(since(m, r"Can.t keep up")), "| placed:", len(since(m, r"Placed furnace-like")))
elif st == "bundle":
    cx, cz = X + 340, Z
    chat(f"/tp {PLAYER} {cx} {Y+6} {cz+8} 180 40", 4)
    deck(cx - 8, cz - 8, cx + 8, cz + 8, h=10)
    chat(f"/tp {PLAYER} {cx} {Y} {cz+6} 180 10", 2)
    for tx, tz in ((cx + 4, cz - 5), (cx - 3, cz - 5)):
        chat(f"/setblock {tx} {Y-1} {tz} dirt", 0.5)
        chat(f"/fill {tx} {Y} {tz} {tx} {Y+4} {tz} oak_log", 0.5)
        chat(f"/fill {tx-2} {Y+3} {tz-2} {tx+2} {Y+5} {tz+2} oak_leaves keep", 0.5)
    ensure("Bun", cx + 0.5, Y, cz + 0.5)
    give("Bun", "stone_axe 1", "dirt 16", 'bundle[bundle_contents=[{id:"minecraft:bread",count:8}]] 1')
    print("bag:", inv("Bun"))
    chat("/effect give Bun saturation 1 0 true", 1)
    m = mark()
    chat("/bot skill woodcut Bun", 1)
    track_f = []
    t0 = time.time()
    pulses = 0
    while time.time() - t0 < 100:
        s = state("Bun"); f = s.get("food", 20)
        track_f.append((round(time.time() - t0), f, sum(i["count"] for i in s.get("inventory", []) if i["id"] == "minecraft:bread")))
        if f > 9 and pulses < 4:
            chat("/effect give Bun hunger 3 80 true", 0.5); pulses += 1
        time.sleep(2)
    print("(t, food, loose bread):", track_f)
    for l in since(m, r"Woodcut hunger|no food|Got \d+ logs|Skill 'woodcut' finished|Bundled|bundle|Bun> ")[:14]:
        print("  >", l[:240])
    print("no-food lines:", len(since(m, r"I have no food|no safe food")), "| starving pause:", len(since(m, r"starving and out of food")))
    chat("/bot stop Bun", 1)
elif st == "regress":
    for script, args in (("fix277.py", ["follow", "60", "230", "46", "south"]), ("fix277.py", ["woodcut"]), ("fix278.py", ["door", "A"])):
        print("==", script, *args)
        subprocess.run([sys.executable, os.path.join(HERE, script), *args])
elif st == "clean":
    chat(f"/bot despawn {sys.argv[2]}", 3)
else:
    print(__doc__)
