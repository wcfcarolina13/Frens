#!/usr/bin/env python3
"""1.1.274 fix checks in the mcp-sandbox (a commands-on save with soul-bound Jake and Bob, e.g. "Sweep Souls3"; game loaded,
control mode on). Like fix273.py, each stage builds on a floating stone platform far from the base so terrain never varies.

  fix274.py setup              gamerules (no day cycle, no mobs), noon, easy difficulty, platform at X,Y,Z
  fix274.py axe                woodcut keeps its axe (e4f77737): Axer with a stone axe, a wooden pickaxe and 24 cobble fells a
                               9-log oak (dirt under the trunk) on the floating deck, then runs /bot skill mining for 60 s.
                               Pass: no 'was undone', no 'Mining re-poll: swapping' run, no 'Combat loadout swapped' line.
  fix274.py craftfar           finding 13: a table inside a barrier ring (standable inside, out of reach from outside) 7 blocks
                               from Crafter, a second table 15 blocks the other way on open ground; 3 planks + 2 sticks.
                               /bot craft wooden_pickaxe twice. Pass: the first call fails at the ringed table, the second
                               walks to the far table and crafts; no crafting table crafted.
  fix274.py spaceship          finding 7: /bot craft spaceship 3 times in 6 s. Pass: one "I don't know how to craft spaceship."
                               and no "I couldn't craft".
  fix274.py fish               finding 14: a 7x7 pond dug into the deck, Fisher with a rod, /bot fish 1.
                               Pass: 'Fishing spot search:' elapsed well under 2000 ms and a cast follows.
  fix274.py idlefb             idle fallbacks: Tailor with a wooden pickaxe and shovel, no axe, no planks, 24 leather, table
                               2 blocks away, base set, idle hobbies on. Pass: 'Idle leather armor: Tailor crafted leather armor',
                               no repeated "I have no axe".
  fix274.py farm               farm without a hoe: a 9x9 dirt farm round a water block, half of it farmland with ripe-less wheat,
                               half grass; Farmer with 16 wheat seeds and no hoe, planks or sticks. /bot skill farm Farmer.
                               Pass: the no-hoe line, not "planted 0 seeds (tilled 0)".
  fix274.py sight1 <mob>       finding 20, part 1: Jake and Bob beside the player, a frozen <mob> 4 blocks off, a banter scene.
                               Prints new [banter] cues from the party conversation file.
  fix274.py sight2 <mob>       finding 20, part 2 (after a relaunch): same placement, a banter scene, the cues.
                               Pass: no second "the first <mob> any of you have seen".
  fix274.py damage             finding 21: Jake into stone (in_wall) for 2 s, then fire under him; a banter scene; the cues and
                               Jake's mind memories. Pass: "being stuck in a wall" / "fire" words, not "a in wall".
  fix274.py threat             finding 37 repro: a frozen zombie 6 blocks from Bob for 60 s; count 'Terminating all current tasks'.
  fix274.py chests             finding 26 (load "New World"): registry keys, the adoption line, Jake's chest block.
  fix274.py cues [n]           the last n [banter] cues in the party conversation file
  fix274.py clean <bot>        despawn a stage's bot
  fix274.py state <bot>        bot_state summary (item totals)
Flashback: tools/sandbox/rec.py start / stop frens_1_1_274_6bl_fix_checks.
"""
import glob, json, os, re, sys, time, urllib.request
from collections import Counter

API = "http://127.0.0.1:9876/api/cmd"
MC = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft"
LOG = MC + "/logs/latest.log"
SAVE = os.environ.get("SB_SAVE", "Sweep Souls3")
REG = MC + "/config/frens/bot_chest_registry.json"
X, Y, Z = 300, 120, 300  # platform top is Y-1, feet at Y
JAKE, BOB = "2af3f0a5-7c5a-42b4-814a-82649ed97177", "6a00845c-0c22-4bfe-b9d0-2de038dae558"


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


def conv_file():
    fs = glob.glob(f"{MC}/saves/{SAVE}/frens/party/v1/*/conversations/*/active.jsonl")
    return max(fs, key=os.path.getmtime) if fs else None


def cues():
    f = conv_file()
    if not f:
        return []
    out = []
    for l in open(f, errors="replace"):
        try:
            d = json.loads(l)
        except Exception:  # noqa: BLE001
            continue
        c = d.get("content", "")
        if c.startswith("[banter]"):
            out.append((d.get("occurredAt", 0), c))
    return out


def scene(m, wait=60):
    """Clear the banter cooldown and wait for a scene; print the cues it added."""
    before = len(cues())
    chat("/bot soul banter now", 2)
    watch(m, wait, r"\[souls\] banter|scene-playback routingId=\S+ line=1/", stop=r"scene-playback routingId=\S+ line=1/")
    time.sleep(3)
    new = cues()[before:]
    for t, c in new:
        print(time.strftime("%H:%M:%S", time.localtime(t / 1000)) if t else "", c[:400])
    return new


def mind_memories(uid, pat):
    p = f"{MC}/saves/{SAVE}/frens/souls/v1/{uid}/mind.json"
    try:
        d = json.load(open(p))
    except Exception as e:  # noqa: BLE001
        print("mind unreadable", e); return []
    hits = [m for m in d.get("memories", []) if re.search(pat, m.get("phrase", ""))]
    for m in hits:
        print("  memory day", m.get("day"), m.get("phrase"))
    print("  seen:", [s for s in d.get("seen", []) if isinstance(s, str)][:20] if isinstance(d.get("seen"), list) else d.get("seen"))
    return hits


st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear",
              "/difficulty easy"):
        chat(t, 1)
    platform(); chat(f"/tp @p {X+4} {Y} {Z}")
elif st == "axe":
    platform(r=10); chat(f"/tp @p {X+7} {Y} {Z+7}")
    tree(X - 3, Z, 9)
    spawn("Axer", X + 1, Y, Z)
    give("Axer", "stone_axe 1", "wooden_pickaxe 1", "cobblestone 24", "bread 8")
    m = mark()
    chat("/bot skill woodcut Axer", 2)
    pat = r"Mining re-poll|was undone|Combat loadout swapped|Axer.*Mining .* with|Mining .* with|Got \d+ logs|woodcut.*(COMPLETED|FAILED)"
    watch(m, 300, pat, stop=r"Got \d+ logs|woodcut.*(COMPLETED|FAILED)")
    time.sleep(10)
    print("--- mining pass")
    chat("/bot skill mining Axer", 2)
    watch(m, 60, r"Mining re-poll|was undone|Combat loadout swapped|Mining .* with")
    chat("/bot stop Axer", 3)
    print(f"undone={len(since(m, 'was undone'))} repoll_swapping={len(since(m, 'Mining re-poll: swapping'))} "
          f"loadout_swapped={len(since(m, 'Combat loadout swapped'))} "
          f"mined_with_pickaxe={len(since(m, 'with minecraft:wooden_pickaxe'))} mined_with_axe={len(since(m, 'with minecraft:stone_axe'))}")
    show_bag("Axer")
elif st == "craftfar":
    cx = X + 60
    platform(cx, Z, r=18); chat(f"/tp @p {cx} {Y} {Z+6}")
    # ringed table 7 blocks west: barrier ring at radius 4 (2 high + roof), so the nearest outside stand is ~5 blocks off
    tx = cx - 7
    chat(f"/fill {tx-4} {Y} {Z-4} {tx+4} {Y+2} {Z+4} barrier", 1)
    chat(f"/fill {tx-3} {Y} {Z-3} {tx+3} {Y+1} {Z+3} air", 1)
    chat(f"/setblock {tx} {Y} {Z} crafting_table", 1)
    chat(f"/setblock {cx+15} {Y} {Z} crafting_table", 1)  # the reachable one, open deck
    spawn("Crafter", cx, Y, Z)
    give("Crafter", "oak_planks 3", "stick 2", "bread 8")
    chat("/bot stop Crafter", 3)
    pat = (r"Crafter|crafting table|Crafting table|Standable options|Selected approach|Failed to reach|Skipping unreachable|"
           r"No accessible|craft one|wooden_pickaxe|\[CHAT\]")
    m = mark()
    chat("/bot craft wooden_pickaxe", 2)
    watch(m, 40, pat, stop=r"Failed to reach crafting table|Crafted|crafted")
    time.sleep(3)
    print("--- second call")
    m2 = mark()
    chat("/bot craft wooden_pickaxe", 2)
    watch(m2, 60, pat, stop=r"Crafted \d|crafted \d|I crafted|Here you go")
    time.sleep(5)
    show_bag("Crafter")
    print(cmd(cmd="bot_state", name="Crafter")[:300])
    print(f"tables crafted (bag): {(bag('Crafter') or {}).get('minecraft:crafting_table', 0)}")
elif st == "spaceship":
    m = mark()
    for _ in range(3):
        cmd(cmd="send_chat", text="/bot craft spaceship"); cmd(cmd="press_key", key="escape"); time.sleep(2)
    time.sleep(4)
    hits = watch(m, 6, r"spaceship|couldn't craft")
    print(f"dont_know={sum(1 for h in hits if 'know how to craft spaceship' in h and '[CHAT]' in h)} "
          f"couldnt={sum(1 for h in hits if 'couldn' in h and 'craft' in h)}")
elif st == "fish":
    fx = X + 120
    platform(fx, Z, r=12); chat(f"/tp @p {fx+8} {Y} {Z+8}")
    chat(f"/fill {fx-3} {Y-2} {Z-3} {fx+3} {Y-1} {Z+3} water", 2)
    spawn("Fisher", fx + 5, Y, Z)
    give("Fisher", "fishing_rod 1", "bread 8")
    chat("/bot stop Fisher", 3)
    m = mark()
    chat("/bot fish 1", 2)
    watch(m, 60, r"Fishing spot|Fishing spot search|[Cc]ast|bobber|Fisher.*fish", stop=r"[Cc]ast(ing)? |Casting|cast=")
    time.sleep(15)
    print(since(m, r"Fishing spot search:")[:2])
    chat("/bot stop Fisher", 2)
elif st == "idlefb":
    sx = X + 180
    platform(sx, Z); chat(f"/tp @p {sx+6} {Y} {Z+6}")
    spawn("Tailor", sx, Y, Z)
    chat(f"/setblock {sx+2} {Y} {Z} crafting_table", 1)
    give("Tailor", "wooden_pickaxe 1", "wooden_shovel 1", "leather 24", "bread 8")
    chat("/bot base set tailorshop", 3)
    m = mark()
    chat("/bot idle_hobbies on Tailor", 2)
    watch(m, 240, r"Tailor.*(Idle|idle|leather|craft|hobby|blocked|axe)|Idle leather armor|Idle cobblestone tools|have no axe",
          stop=r"Idle leather armor: Tailor")
    time.sleep(20)
    print(f"no_axe_lines={len(since(m, 'have no axe'))} woodcut_starts={len(since(m, 'Tailor.*woodcut'))}")
    show_bag("Tailor")
    print(cmd(cmd="bot_state", name="Tailor")[:800])
elif st == "farm":
    fx = X + 240
    platform(fx, Z, r=10); chat(f"/tp @p {fx+7} {Y} {Z+7}")
    chat(f"/fill {fx-4} {Y-1} {Z-4} {fx+4} {Y-1} {Z+4} grass_block", 1)
    chat(f"/fill {fx-4} {Y-1} {Z-4} {fx+4} {Y-1} {Z-1} farmland[moisture=7]", 1)
    chat(f"/fill {fx-4} {Y} {Z-4} {fx+4} {Y} {Z-1} wheat[age=3]", 1)
    chat(f"/setblock {fx} {Y-1} {Z} water", 1)
    spawn("Farmer", fx + 6, Y, Z)
    give("Farmer", "wheat_seeds 16", "bread 8")
    m = mark()
    chat("/bot skill farm Farmer", 2)
    watch(m, 240, r"Farmer|\[Farm|hoe|planted|tilled", stop=r"farm.*(COMPLETED|FAILED)|did nothing|Prepared|planted \d+")
    time.sleep(5)
    print(since(m, r"\[CHAT\].*(hoe|planted|farm)")[:5])
elif st in ("sight1", "sight2"):
    mob = sys.argv[2] if len(sys.argv) > 2 else "pig"
    sx = X - 60
    if st == "sight1":
        chat(f"/tp @p {sx} {Y+2} {Z+2} 0 0", 4)  # /fill needs the area loaded: be there first
        platform(sx, Z)
        chat(f"/summon {mob} {sx-4} {Y} {Z} {{NoAI:1b,Invulnerable:1b,PersistenceRequired:1b}}", 1)
    chat(f"/tp @p {sx} {Y} {Z+2} 0 0", 1)
    chat("/bot stop Jake", 1); chat("/bot stop Bob", 1)
    chat(f"/tp Jake {sx-1} {Y} {Z}", 1); chat(f"/tp Bob {sx+1} {Y} {Z}", 1)
    print(cmd(cmd="players")[:600])
    for uid in (JAKE, BOB):
        mind_memories(uid, r"^$")
    m = mark()
    new = scene(m, 90)
    print(f"first-{mob} cues: {sum(1 for _, c in new if 'first ' + mob in c)}")
elif st == "damage":
    sx = X - 60
    chat("/bot stop Jake", 1); chat("/bot stop Bob", 1)
    chat(f"/tp @p {sx} {Y} {Z+2}", 1)
    chat(f"/tp Bob {sx+1} {Y} {Z}", 1)
    chat(f"/setblock {sx-2} {Y} {Z-3} stone", 1); chat(f"/setblock {sx-2} {Y+1} {Z-3} stone", 1)
    m = mark()
    chat(f"/tp Jake {sx-2} {Y} {Z-3}", 3)  # inside two stone blocks: in_wall damage
    chat(f"/setblock {sx-2} {Y} {Z-3} air", 1); chat(f"/setblock {sx-2} {Y+1} {Z-3} air", 1)
    chat(f"/tp Jake {sx-1} {Y} {Z}", 2)
    chat(f"/setblock {sx-1} {Y} {Z} fire", 3)  # in_fire / on_fire
    chat(f"/setblock {sx-1} {Y} {Z} air", 1)
    chat(f"/tp Jake {sx-1} {Y} {Z+1}", 1)
    print(since(m, r"Jake.*(hurt|damage|suffocat|burn|fire|wall)|BOT_DAMAGE")[:6])
    time.sleep(5)
    new = scene(mark(), 90)
    print(f"cues with 'a in wall'/'a on fire': {sum(1 for _, c in new if re.search(r'a (in wall|on fire|in fire)', c))}; "
          f"with words: {sum(1 for _, c in new if re.search(r'stuck in a wall|fire', c))}")
    for uid in (JAKE, BOB):
        mind_memories(uid, r"beating|died")
elif st == "cues":
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 6
    for t, c in cues()[-n:]:
        print(time.strftime("%H:%M:%S", time.localtime(t / 1000)) if t else "", c[:500])
elif st == "threat":
    sx = X - 60
    chat("/kill @e[type=zombie]", 1)
    chat(f"/tp Bob {sx+6} {Y} {Z}", 1)
    m = mark()
    chat(f"/summon zombie {sx} {Y} {Z} {{NoAI:1b,Invulnerable:1b,PersistenceRequired:1b}}", 1)
    t0 = time.time(); time.sleep(60)
    hits = since(m, r"Terminating all current tasks")
    chat("/kill @e[type=zombie]", 1)
    for h in hits:
        print(h[:200])
    print(f"Terminating lines in {time.time()-t0:.0f} s: {len(hits)}")
elif st == "chests":
    d = json.load(open(REG))
    print("keys:", list(d.keys()))
    print("worlds:", {k: {b: len(v) for b, v in w.get("chestsByBot", {}).items()} for k, w in d.get("worlds", {}).items()})
    print("legacyWorldClaims:", d.get("legacyWorldClaims"))
    print(since(0, r"chest registry|Adopted legacy")[:6])
    print(cmd(cmd="blocks", x=68, y=72, z=225, r=1, dy=0)[:600])
elif st == "clean":
    chat(f"/bot despawn {sys.argv[2]}", 3)
elif st == "state":
    show_bag(sys.argv[2])
else:
    print(__doc__)
