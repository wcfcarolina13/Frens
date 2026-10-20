#!/usr/bin/env python3
"""1.1.281 checks in the mcp-sandbox (findings 57/58: the idle stone upgrade crafts only stone, keeps the cobblestone the
rest of the batch needs, and stays quiet; explicit crafts still talk). Commands-on save "Sweep Souls3", game loaded,
control mode on. Copies fix279's craft stage onto fresh decks at Z 940 (Y=120, feet at Y), clear of fix279's (Z 894-910).

  fix281.py setup                 gamerules (no day cycle, no mobs), noon, easy difficulty
  fix281.py craft [full|rotten|mats] [cobble] [free] [bot]
                                  the idle stone upgrade with a full pack: wooden pickaxe and shovel, <cobble> (16)
                                  cobblestone, 8 sticks, bread and 31 valuables that are not tool materials (rotten: the last
                                  is 5 rotten flesh; mats: the fill has 3 diamonds and 5 iron, gold and copper ingots), a table
                                  2 blocks away, a chest 12 blocks away, a base, idle hobbies on. One bot per variant (Smith2
                                  full, Smith3 rotten, Smith4 mats, Smith5 mats with fewer than 16 cobblestone) so no
                                  per-bot cooldown carries over. Watches up to 90 s; prints every `Crafted N <kind>(s) using
                                  <material>`, every make-room drop, every system chat line, and the bag's diamonds, iron
                                  and cobblestone before and after. free: leave that many main slots empty (the full pack
                                  has no cheap stack but the reserved cobblestone, so with free 0 nothing can be crafted);
                                  bot: a fresh name per run (Smith6...), so no per-bot cooldown carries over.
  fix281.py explicit              regression: Expl with 3 cobblestone and 2 sticks (no planks, no table within 40 blocks, deck at 760, 980):
                                  /bot craft stone_pickaxe 1 stone Expl must still answer in chat (explicit crafts are not quiet).
  fix281.py follow [x z dist [south]]   regression: fix277.py follow (default the south route 60 230 46 south).
  fix281.py clean <bot>           despawn a stage's bot
Flashback: tools/sandbox/rec.py start / stop frens_1_1_281_6bs_<topic>.
"""
import json, os, re, subprocess, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
MC = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft"
LOG = MC + "/logs/latest.log"
HERE = os.path.dirname(os.path.abspath(__file__))
PLAYER = "RotiWokeman"
X, Y, Z = 300, 120, 940  # feet at Y; deck top is Y-1


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


VALUABLES = ["iron_ingot 5", "gold_ingot 5", "copper_ingot 5", "diamond 3", "emerald 3", "lapis_lazuli 6", "redstone 7",
             "quartz 4", "amethyst_shard 3", "raw_iron 4", "raw_gold 4", "raw_copper 4", "coal 6", "charcoal 5",
             "iron_nugget 9", "gold_nugget 9", "glowstone_dust 4", "gunpowder 4", "paper 6", "book 2", "slime_ball 3",
             "ender_pearl 2", "blaze_rod 2", "prismarine_shard 3", "flint 4", "clay_ball 5", "brick 4", "glass_bottle 3",
             "iron_pickaxe 1", "iron_shovel 1", "iron_axe 1", "shears 1", "flint_and_steel 1", "bucket 1", "compass 1",
             "clock 1"]
MATS = ("diamond", "iron_ingot", "gold_ingot", "copper_ingot", "cobblestone", "stick")

st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear",
              "/difficulty easy"):
        chat(t, 1)
elif st == "craft":
    variant = sys.argv[2] if len(sys.argv) > 2 else "full"
    cobble = int(sys.argv[3]) if len(sys.argv) > 3 else 16
    free = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    bot = sys.argv[5] if len(sys.argv) > 5 else \
        {"full": "Smith2", "rotten": "Smith3", "mats": "Smith4" if cobble >= 16 else "Smith5"}[variant]
    slot = int(re.sub(r"\D", "", bot) or 2) - 2  # Smith2 -> deck 0, Smith9 -> deck 7 (X 580)
    cx, cz = X + 40 * slot, Z
    chat(f"/tp {PLAYER} {cx} {Y+6} {cz+8} 180 40", 4)
    deck(cx - 14, cz - 6, cx + 6, cz + 8)
    chat(f"/setblock {cx+2} {Y} {cz} crafting_table", 1)
    chat(f"/setblock {cx-12} {Y} {cz} chest", 1)
    chat(f"/tp {PLAYER} {cx+3} {Y} {cz+4} 180 20", 2)
    chat("/kill @e[type=item]", 1)
    ensure(bot, cx + 0.5, Y, cz + 0.5)
    kit = ["wooden_pickaxe 1", "wooden_shovel 1", f"cobblestone {cobble}", "stick 8", "bread 8"]
    # fix279's fill: no tool materials, except in mats (findings 57/58 came from that run)
    tool_mats = ("iron_pickaxe", "iron_shovel", "iron_axe", "iron_ingot", "gold_ingot", "copper_ingot", "diamond")
    fill = ([v for v in VALUABLES if not v.startswith(tool_mats)] + ["honeycomb 3", "rabbit_hide 2"])[:31]
    if variant == "mats":
        fill = [v for v in VALUABLES if not v.startswith(("iron_pickaxe", "iron_shovel", "iron_axe"))][:31]
    if variant == "rotten":
        fill[-1] = "rotten_flesh 5"
    if free:
        fill = fill[:len(fill) - free]  # free main slots: the crafts need no make-room drop
    give(bot, *(kit + fill))
    before = inv(bot)
    print(f"{bot} slots {len(before)}; before:", {k: count(bot, k) for k in MATS})
    chat(f"/bot base set craft281{bot.lower()}", 3)
    p0 = pos(bot)
    m = mark()
    t_on = time.time()
    chat(f"/bot idle_hobbies on {bot}", 1)
    t0, far = time.time(), 0.0
    while time.time() - t0 < 90:
        p = pos(bot)
        if p and p0:
            far = max(far, ((p[0] - p0[0]) ** 2 + (p[2] - p0[2]) ** 2) ** 0.5)
        if since(m, rf"Idle stone upgrade: {bot}|craft: {bot} has a full pack") and time.time() - t0 > 30:
            break
        time.sleep(1)
    chat(f"/bot idle_hobbies off {bot}", 1)
    after = inv(bot)
    print(f"farthest from start: {round(far, 2)} blocks; end {pos(bot)}")
    print("after:", {k: count(bot, k) for k in MATS})
    print("gone:", [x for x in before if x[0] not in {a for a, _ in after}], "| new:",
          [x for x in after if x[0] not in {a for a, _ in before}])
    crafted = since(m, r"Crafted \d+ \w+\(s\) using \w+")
    drops = since(m, r"Dropped \d+x .* to free inventory space")
    sysmsg = since(m, rf"Sending system message: '|<{bot}> ")
    craftchat = [l for l in sysmsg if re.search(r"craft|Missing materials|pack's full|crafting table|planks", l)]
    for l in since(m, rf"craft: {bot}|craft \(quiet\)|Idle stone upgrade|Crafted \d+ \w+\(s\)|to free inventory space|"
                      rf"disposable stack|Placed chest|Can.t keep up")[:30]:
        print("  >", l[:240])
    print("system messages:")
    for l in sysmsg[:20]:
        print("  chat>", l[:200])
    print(f"SUMMARY {bot}: crafted {len(crafted)} {[re.search(r'Crafted \d+ (\w+)\(s\) using (\w+)', l).groups() for l in crafted]}"
          f" | non-stone {sum(not re.search(r'using stone', l) for l in crafted)}"
          f" | drops {[re.search(r'Dropped (\d+x .*?) to free', l).group(1) for l in drops]}"
          f" | cobblestone drops {sum('Cobblestone' in l for l in drops)}"
          f" | craft chat lines {len(craftchat)} | pack-full lines {sum('pack' in l for l in craftchat)}"
          f" | Can't keep up {len(since(m, r'Can.t keep up'))}")
    mm = mark()
    chat(f"/execute if block {cx-12} {Y} {cz} chest run say CHEST_STILL", 0.5)
    print("chest still there:", bool(since(mm, "CHEST_STILL")), "| chest placements:", len(since(m, r"Placed chest|placed a chest")))
elif st == "explicit":
    cx, cz = X + 460, Z + 40  # 760, 980: over 40 blocks (the worker's table scan) from every staged table
    bot = "Expl"
    chat(f"/tp {PLAYER} {cx} {Y+6} {cz+8} 180 40", 4)
    deck(cx - 6, cz - 6, cx + 6, cz + 8)
    chat(f"/tp {PLAYER} {cx+3} {Y} {cz+4} 180 20", 2)
    ensure(bot, cx + 0.5, Y, cz + 0.5)
    give(bot, "cobblestone 3", "stick 2", "bread 4")
    m = mark()
    chat(f"/bot craft stone_pickaxe 1 stone {bot}", 8)
    for l in since(m, rf"craft|Craft|Sending system message|{bot}> ")[:20]:
        print("  >", l[:240])
    sysmsg = since(m, rf"Sending system message: '|<{bot}> ")
    print(f"SUMMARY explicit: system chat lines {len(sysmsg)}; quiet log lines {len(since(m, r'craft \(quiet\)'))}; "
          f"bag {inv(bot)}")
elif st == "follow":
    args = sys.argv[2:] or ["60", "230", "46", "south"]
    subprocess.run([sys.executable, os.path.join(HERE, "fix277.py"), "follow", *args])
elif st == "clean":
    chat(f"/bot despawn {sys.argv[2]}", 3)
else:
    print(__doc__)
