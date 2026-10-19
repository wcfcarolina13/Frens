#!/usr/bin/env python3
"""1.1.273 fix checks in the mcp-sandbox (a commands-on save with soul-bound Jake and Bob, e.g. "Sweep Souls3"; game loaded,
control mode on). Like fix272.py, each stage builds on a floating stone platform far from the base so terrain never varies.
DEBUG loggers used: skill-resume, ambient-chatter, bot-dialogue, tool-selector (sandbox JvmArgs -Dlog4j.configurationFile=...).

  fix273.py setup              gamerules (no day cycle, no mobs), noon, easy difficulty, platform at X,Y,Z
  fix273.py follow <bot>       finding 27: /bot follow then /bot stop. Pass: one chat line ("<bot> has stopped."), never
                               "Stopping follow command."
  fix273.py bed                finding 4: Bedder with 1 white wool and 10 oak planks, no table near, /bot craft bed.
                               Pass: a line naming the missing wool, no crafting table in the bag or placed, 10 planks left.
  fix273.py sweep              finding 11: Sweeper /clear-ed, piles spread around, /bot skill drop_sweep.
                               Pass: "Picked up N items." equals the bag total (printed from bot_state).
  fix273.py stoneup            finding 9: Smith with a wooden pickaxe and shovel (no axe, no sword), 16 cobble and 8 sticks,
                               table 2 blocks away, base set beside, idle hobbies on. Pass within a few minutes:
                               'Idle stone upgrade: Smith crafted stone tools' and a stone sword and axe in the bag.
  fix273.py repoll             stone axe + 24 cobble (no pickaxe) on a 9-log tree standing on the stone platform.
                               Pass: no run of 'Mining re-poll: swapping' each second, the bot's cobble comes down
                               ('Mining complete at'), the platform under the tree is intact (blocks grid before/after).
  fix273.py resume_done        finding 23: Cutter (diamond axe + sword) fells a 5-log tree to completion, then a zombie.
                               Pass: no "Resuming 'woodcut' for Cutter. Hostiles cleared.".
  fix273.py resume_live        finding 23: a zombie arrives while Cutter is cutting a tall tree.
                               Pass: "Resuming 'woodcut' for Cutter. Hostiles cleared." once the zombie is dead.
  fix273.py danger_bob         finding 18: player and Jake together, Bob 12 blocks off with a frozen zombie 6 blocks from
                               him (17+ from Jake and the player); a salient line to Jake.
                               Pass: '[souls] local ... danger seen by Bob' then 'outcome=vetoed:danger'.
  fix273.py danger_player      finding 18: the zombie beside the player only, the bots 12+ blocks away.
                               Pass: 'danger seen by <player>'.
  fix273.py idle               finding 17: Jake and Bob idle 4-6 blocks from the player (spread so positions tell them apart).
  fix273.py ambient HH:MM:SS [HH:MM:SS]
                               finding 17 analysis: voiced lines in the window, pairs from different bots under 4 s, and
                               ambient lines inside a soul scene (first..last scene-playback line of a routingId).
  fix273.py clean <bot>        despawn a stage's bot
  fix273.py state <bot>        bot_state summary (item totals)
Flashback: tools/sandbox/rec.py start / stop frens_1_1_273_6bk_fix_checks.
"""
import json, re, sys, time, urllib.request
from collections import Counter

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
X, Y, Z = 300, 120, 300  # platform top is Y-1, feet at Y
AMBIENT = re.compile(r"bot\.line\.(ambient_|idle_|context_|weather_|time_sunset|wildlife_|status_|warning_|dark_|banter_"
                     r"|animal_nearby|combat_standing|nether_|end_|portal_|weird_portal)")


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


def platform(cx=X, cz=Z, r=8):
    chat(f"/fill {cx-r} {Y-3} {cz-r} {cx+r} {Y+12} {cz+r} air")
    chat(f"/fill {cx-r} {Y-3} {cz-r} {cx+r} {Y-1} {cz+r} stone")


def spawn(bot, x, y, z):
    chat(f"/bot spawn {bot} admin", 12)
    chat(f"/tp {bot} {x} {y} {z}", 2)
    chat(f"/clear {bot}", 1)


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


def grid(x, y, z, r=4, dy=1):
    print(cmd(cmd="blocks", x=x, y=y, z=z, r=r, dy=dy)[:3000])


def woodcut_done(m, bot, secs=240):
    return watch(m, secs, rf"{bot}.*(woodcut|Woodcut)|Got \d+ logs|Resuming|Terminating|Not auto-resuming|resume record",
                 stop=r"Got \d+ logs|woodcut.*(COMPLETED|FAILED)")


def hms(s):
    h, m, x = (int(v) for v in s.split(":")); return h * 3600 + m * 60 + x


st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear",
              "/difficulty easy"):
        chat(t, 1)
    platform(); chat(f"/tp @p {X+4} {Y} {Z}")
elif st == "follow":
    bot = sys.argv[2]
    chat(f"/tp {bot} ~2 ~ ~", 2)
    chat(f"/bot follow {bot}", 6)
    m = mark()
    chat(f"/bot stop {bot}", 6)
    hits = watch(m, 6, r"\[CHAT\]|Stopping follow|has stopped")
    print(f"chat lines after stop: {sum(1 for h in hits if '[CHAT]' in h)}")
elif st == "bed":
    platform(); chat(f"/tp @p {X+5} {Y} {Z}")
    spawn("Bedder", X, Y, Z)
    chat("/give Bedder white_wool 1", 1); chat("/give Bedder oak_planks 10", 1)
    chat("/bot stop Bedder", 3)  # makes Bedder the remembered target for /bot craft
    m = mark()
    chat("/bot craft bed", 8)
    watch(m, 20, r"Bedder|\[CHAT\]|crafting table|wool|claim")
    show_bag("Bedder")
    print(cmd(cmd="blocks", x=X, y=Y, z=Z, r=4, dy=1)[:2000])
elif st == "sweep":
    platform(); chat(f"/tp @p {X+7} {Y} {Z+7}")
    spawn("Sweeper", X, Y, Z)
    chat("/clear Sweeper", 1)
    for dx, dz, item, n in ((3, 0, "oak_log", 5), (-3, 2, "apple", 3), (0, -4, "stick", 7), (4, 4, "oak_sapling", 4),
                            (-4, -3, "cobblestone", 6), (2, -2, "wheat_seeds", 5)):
        chat(f'/summon item {X+dx} {Y} {Z+dz} {{Item:{{id:"minecraft:{item}",count:{n}}}}}', 1)
    print("summoned 30 items")
    m = mark()
    chat("/bot skill drop_sweep Sweeper", 2)
    watch(m, 120, r"Picked up|Drop sweep|drop_sweep|collected", stop=r"Picked up \d+")
    time.sleep(3); show_bag("Sweeper")
elif st == "stoneup":
    sx = X + 60
    platform(sx); chat(f"/tp @p {sx+6} {Y} {Z+6}")
    spawn("Smith", sx, Y, Z)
    chat(f"/setblock {sx+2} {Y} {Z} crafting_table", 1)
    for it, n in (("wooden_pickaxe", 1), ("wooden_shovel", 1), ("cobblestone", 16), ("stick", 8), ("bread", 8)):
        chat(f"/give Smith {it} {n}", 1)
    chat("/bot base set smithy", 3)
    m = mark()
    chat("/bot idle_hobbies on Smith", 2)
    watch(m, 300, r"Smith.*(Idle|idle|stone|craft|hobby|blocked)|Idle stone upgrade", stop=r"Idle stone upgrade: Smith")
    show_bag("Smith")
elif st == "repoll":
    platform(r=10); chat(f"/tp @p {X+7} {Y} {Z+7}")
    tree(X - 3, Z, 9)
    grid(X - 3, Y - 1, Z, r=4, dy=0)
    spawn("Feller", X + 1, Y, Z)
    chat("/give Feller stone_axe 1", 1); chat("/give Feller cobblestone 24", 1); chat("/give Feller bread 8", 1)
    m = mark()
    chat("/bot skill woodcut Feller", 2)
    hits = watch(m, 300, r"Mining re-poll|Mining complete|Feller.*(Mining|pillar|scaffold|cleanup|sweep)|Got \d+ logs|woodcut.*(COMPLETED|FAILED)",
                 stop=r"Got \d+ logs|woodcut.*(COMPLETED|FAILED)")
    sw = [h for h in hits if "re-poll: swapping" in h]
    print(f"re-poll swapping lines: {len(sw)}; undone lines: {sum(1 for h in hits if 'was undone' in h)}")
    time.sleep(10)
    grid(X - 3, Y - 1, Z, r=4, dy=0)
    show_bag("Feller")
elif st == "resume_done":
    platform(); chat(f"/tp @p {X+6} {Y} {Z+6}")
    tree(X - 3, Z, 5)
    spawn("Cutter", X + 1, Y, Z)
    for it in ("diamond_axe", "diamond_sword", "bread 8"):
        chat(f"/give Cutter {it}", 1)
    m = mark()
    chat("/bot skill woodcut Cutter", 2)
    woodcut_done(m, "Cutter")
    time.sleep(20)
    m = mark()
    chat(f"/summon zombie {X-3} {Y} {Z+3}", 2)
    watch(m, 90, r"Cutter|Resuming|Terminating|Not auto-resuming|resume record|Hostiles cleared")
    chat("/kill @e[type=zombie]", 1)
elif st == "resume_live":
    # No platform() here: refilling the stone under a bot that stands in a trunk hole entombs it (Cutter
    # suffocated that way on the first run). Clear the air above, fill the deck back, then place the bot.
    chat(f"/tp @p {X+6} {Y} {Z+6}")
    chat(f"/fill {X-8} {Y} {Z-8} {X+8} {Y+12} {Z+8} air", 1)
    chat(f"/fill {X-8} {Y-3} {Z-8} {X+8} {Y-1} {Z+8} stone replace air", 1)
    chat("/tp Cutter %d %d %d" % (X + 1, Y, Z), 2)
    chat("/clear Cutter", 1)
    for it in ("diamond_axe", "diamond_sword", "bread 8"):
        chat(f"/give Cutter {it}", 1)
    for tx, tz in ((X - 4, Z), (X - 4, Z - 4), (X - 4, Z + 4)):
        tree(tx, tz, 6)
    m = mark()
    chat("/bot skill woodcut Cutter", 2)
    watch(m, 60, r"Mining .* with minecraft:diamond_axe", stop=r"Mining .* with minecraft:diamond_axe")
    time.sleep(3)
    chat(f"/summon zombie {X} {Y} {Z+3}", 2)
    watch(m, 240, r"Resuming|Terminating|Not auto-resuming|resume record|Hostiles cleared|Got \d+ logs|woodcut.*(COMPLETED|FAILED)",
          stop=r"Got \d+ logs|woodcut.*(COMPLETED|FAILED)")
    chat("/kill @e[type=zombie]", 1)
elif st in ("danger_bob", "danger_player"):
    platform(r=20)
    zb = '{NoAI:1b,Invulnerable:1b,PersistenceRequired:1b}'
    chat("/kill @e[type=zombie]", 1)
    chat("/bot stop Jake", 1); chat("/bot stop Bob", 1)
    if st == "danger_bob":
        chat(f"/tp @p {X} {Y} {Z} -90 0", 1)
        chat(f"/tp Jake {X+2} {Y} {Z}", 1); chat(f"/tp Bob {X-12} {Y} {Z}", 1)
        chat(f"/summon zombie {X-17} {Y} {Z+3} {zb}", 2)
    else:
        chat(f"/tp @p {X} {Y} {Z} 90 0", 1)
        chat(f"/tp Jake {X-12} {Y} {Z}", 1); chat(f"/tp Bob {X-13} {Y} {Z+3}", 1)
        chat(f"/summon zombie {X+5} {Y} {Z} {zb}", 2)
    print(cmd(cmd="players")[:900])
    m = mark()
    chat("/bot soul local now", 2)
    chat("we should go find some iron near the cave, what do you all think?" if st == "danger_bob" else "we should build a bigger house before night, any ideas?", 2)  # unaddressed: a name routes it as a DM
    watch(m, 30, r"\[souls\] local|danger seen|scene-playback")
    chat("/kill @e[type=zombie]", 1)
elif st == "idle":
    chat(f"/tp @p {X} {Y} {Z}", 1)
    chat("/bot stop Jake", 1); chat("/bot stop Bob", 1)
    chat(f"/tp Jake {X-5} {Y} {Z-3}", 1); chat(f"/tp Bob {X+3} {Y} {Z+5}", 1)
elif st == "ambient":
    t0 = hms(sys.argv[2]); t1 = hms(sys.argv[3]) if len(sys.argv) > 3 else 10 ** 9
    voiced, scenes = [], {}
    for l in lines():
        mt = re.match(r"\[(\d\d:\d\d:\d\d)\]", l)
        if not mt or not (t0 <= hms(mt.group(1)) <= t1):
            continue
        t = hms(mt.group(1))
        mv = re.search(r"Played sound (\S+) at \(([-\d.]+), ([-\d.]+), ([-\d.]+)\)", l)
        if mv:
            voiced.append((t, mt.group(1), mv.group(1), (round(float(mv.group(2))), round(float(mv.group(4))))))
        ms = re.search(r"scene-playback routingId=(\S+) line=(\d+)/(\d+)", l)
        if ms:
            scenes.setdefault(ms.group(1), []).append(t)
    amb = [v for v in voiced if AMBIENT.search(v[2])]
    print(f"voiced lines {len(voiced)}, ambient {len(amb)}, scenes {len(scenes)}")
    for v in amb:
        print("  ambient", v[1], v[2].split(".")[-1], v[3])
    close = [(a, b) for i, a in enumerate(voiced) for b in voiced[i + 1:]
             if b[0] - a[0] < 4 and a[3] != b[3] and (AMBIENT.search(a[2]) or AMBIENT.search(b[2]))]
    print(f"ambient pairs from different positions under 4 s: {len(close)}")
    for a, b in close:
        print("  ", a[1], a[2].split(".")[-1], a[3], "|", b[1], b[2].split(".")[-1], b[3])
    inside = [(v, r) for v in amb for r, ts in scenes.items() if min(ts) <= v[0] <= max(ts)]
    print(f"ambient lines inside a soul scene: {len(inside)}")
    for v, r in inside:
        print("  ", v[1], v[2], r[:8])
elif st == "clean":
    chat(f"/bot despawn {sys.argv[2]}", 3)
elif st == "state":
    show_bag(sys.argv[2])
else:
    print(__doc__)
