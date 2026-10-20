#!/usr/bin/env python3
"""1.1.275 fix checks in the mcp-sandbox (a commands-on save, "Sweep Souls3"; game loaded, control mode on). Like fix274.py, each
stage builds on a floating stone platform far from the base so terrain never varies.

  fix275.py setup              gamerules (no day cycle, no mobs), noon, easy difficulty, platform at X,Y,Z
  fix275.py threat             finding 37: W1 and W2 run /bot skill mining on the deck, a frozen zombie 6 blocks off for 60 s.
                               Pass: "Terminating all current tasks due to threat detections" at most once per bot.
  fix275.py threat2            variant of threat: an awake invulnerable zombie in a glass cell, W1 and W2 woodcutting 4 trees.
  fix275.py threat3            variant: an ordinary zombie walks up to W1 and W2 while they mine.
  fix275.py skeleton           the pause path: an invulnerable skeleton on a 4-high pillar 8 blocks from W1 and W2 woodcutting.
                               Pass: one threat line per bot with a 'pause requested', no repeat while it lives.
  fix275.py soil               finding 38: Axer (stone axe, wooden pickaxe, 24 dirt) fells a 9-log oak standing on one dirt
                               block in a stone deck. Pass: the dirt is still there after cleanup, any bot pillar removed.
  fix275.py onetree            finding 41 (after soil + clean Axer): Axer2, the only tree on the 300,300 deck (6 logs). Pass: "Cut 1 tree(s). No more trees
                               nearby." and success, no "Repeated failures".
  fix275.py fish [dx] [dz]     finding 39: a fresh 7x7 pond in the deck at X+dx, Z+dz, Fisher with a rod, /bot fish 1. Pass: snapshot=skipped (or a
                               small snapshotMs), elapsed well under 2000 ms, a cast follows.
  fix275.py starve [dz]        finding 42: Hungry (stone pickaxe, 4 rotten flesh, nothing else) drained by a hunger effect,
                               entombed in stone, resistance after the first hits, /bot skill stripmine, 45 s.
                               Pass: "Desperate food" at most once per 10 s; no "starving and out of food" pause while eating.
  fix275.py chests             finding 40: registry keys/claims and this launch's adoption lines (checked/chestLike).
  fix275.py deposit <bot>      give <bot> 7 cobblestone, /bot store deposit 7 cobblestone (run in "AB R1").
  fix275.py clean <bot>        despawn a stage's bot
  fix275.py state <bot>        bot_state summary (item totals)
Flashback: tools/sandbox/rec.py start / stop frens_1_1_275_6bm_fix_checks.
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




def block_at(x, y, z):
    try:
        d = json.loads(cmd(cmd="blocks", x=x, y=y, z=z, r=0, dy=0))
    except Exception:  # noqa: BLE001
        return cmd(cmd="blocks", x=x, y=y, z=z, r=0, dy=0)[:200]
    return d


def per_minute(hits):
    c = Counter()
    for h in hits:
        mm = re.match(r"\[(\d\d:\d\d)", h)
        c[mm.group(1) if mm else "?"] += 1
    return dict(c)


def woodcut_run(bot, tx, tz, h, label):  # noqa: ARG001
    m = mark()
    chat(f"/bot skill woodcut {bot}", 2)
    pat = (r"Got \d+ logs|Cut \d+ tree|No more trees|Repeated failures|woodcut.*(COMPLETED|FAILED|ABORTED|success)|"
           r"woodcut_cleanup|fallback sweep|Mining -?\d+, -?\d+, -?\d+ with|finalReason")
    watch(m, 300, pat, stop=r"Cut \d+ tree|Stopped after cutting|woodcut.*(COMPLETED|FAILED|ABORTED)")
    time.sleep(25)  # cleanup runs after the main skill
    for l in since(m, r"woodcut_cleanup|fallback sweep|finalReason|Cut \d+ tree|Repeated failures")[-12:]:
        print("  >", l[:260])
    soil = f"{tx}, {Y-1}, {tz}"
    print(f"[{label}] soil mined lines: {len(since(m, 'Mining ' + soil + ' with'))}, "
          f"fallback sweep lines: {since(m, 'fallback sweep removed')}")
    print(f"[{label}] block under trunk:", block_at(tx, Y - 1, tz))
    print(f"[{label}] column above:", [block_at(tx, Y + i, tz) for i in range(0, 3)])
    print(f"[{label}] outcome:", since(m, r"\[CHAT\].*(Cut \d+ tree|Stopped after cutting|Repeated failures|No more trees)"))
    return m


st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear",
              "/difficulty easy"):
        chat(t, 1)
    platform(); chat(f"/tp @p {X+4} {Y} {Z}")
elif st == "threat":
    sx = X - 60
    chat(f"/tp @p {sx} {Y} {Z+6} 0 0", 4)  # the r=8 deck from fix274 sight1 is already here (Jake and Bob may stand on it)
    chat("/kill @e[type=zombie]", 1)
    chat(f"/fill {sx+1} {Y-6} {Z-6} {sx+5} {Y-4} {Z-2} stone", 1)  # deeper stone under the miners
    for b, dx in (("W1", 2), ("W2", 4)):
        spawn(b, sx + dx, Y, Z - 4)
        give(b, "stone_pickaxe 1", "bread 8")
    m = mark()
    chat("/bot skill mining W1", 2); chat("/bot skill mining W2", 4)
    chat(f"/summon zombie {sx-3} {Y} {Z-4} {{NoAI:1b,Invulnerable:1b,PersistenceRequired:1b}}", 1)
    t0 = time.time(); time.sleep(60)
    hits = since(m, r"Terminating all current tasks")
    chat("/kill @e[type=zombie]", 1)
    for h in hits:
        print(h[:200])
    print("pauses:", [l[:200] for l in since(m, r"pause requested")][:10])
    print(f"Terminating lines in {time.time()-t0:.0f} s: {len(hits)} per minute {per_minute(hits)} "
          f"W1={sum('W1' in h for h in hits)} W2={sum('W2' in h for h in hits)}")
    chat("/bot stop W1", 1); chat("/bot stop W2", 1)
elif st == "threat2":
    # variant: an awake zombie sealed in a glass cell (the finding's "behind a fence" case), W1 and W2 woodcutting 4 trees
    sx = X - 60
    chat(f"/tp @p {sx} {Y} {Z+6} 0 0", 4)
    chat("/kill @e[type=zombie]", 1)
    for i, (tx, tz) in enumerate(((sx + 4, Z - 6), (sx + 6, Z - 3), (sx + 2, Z - 7), (sx + 7, Z - 6))):
        tree(tx, tz, 5)
    chat(f"/fill {sx-5} {Y} {Z-6} {sx-3} {Y+2} {Z-4} glass hollow", 1)
    for b, dx in (("W1", 2), ("W2", 4)):
        chat(f"/tp {b} {sx + dx} {Y} {Z - 2}", 1); chat(f"/clear {b}", 1)
        give(b, "stone_axe 1", "dirt 16", "bread 8")
    m = mark()
    chat("/bot skill woodcut W1", 2); chat("/bot skill woodcut W2", 6)
    chat(f"/summon zombie {sx-4} {Y} {Z-5} {{Invulnerable:1b,PersistenceRequired:1b}}", 1)
    t0 = time.time(); time.sleep(60)
    hits = since(m, r"Terminating all current tasks")
    chat("/kill @e[type=zombie]", 1)
    for h in hits:
        print(h[:200])
    print("pauses:", [l[:200] for l in since(m, r"pause requested|Pausing|paused")][:12])
    print(f"Terminating lines in {time.time()-t0:.0f} s: {len(hits)} per minute {per_minute(hits)} "
          f"W1={sum('W1' in h for h in hits)} W2={sum('W2' in h for h in hits)}")
    chat("/bot stop W1", 1); chat("/bot stop W2", 1)
elif st == "threat3":
    # variant: an ordinary awake zombie walks up to W1 and W2 while they mine (they fight it)
    sx = X - 60
    chat(f"/tp @p {sx} {Y} {Z+6} 0 0", 4)
    chat("/kill @e[type=zombie]", 1)
    chat(f"/fill {sx-7} {Y} {Z-8} {sx+8} {Y+8} {Z-1} air", 1)  # threat2's trees and cell
    for b, dx in (("W1", 2), ("W2", 4)):
        chat(f"/tp {b} {sx + dx} {Y} {Z - 4}", 1); chat(f"/clear {b}", 1)
        give(b, "stone_pickaxe 1", "bread 8")
    m = mark()
    chat("/bot skill mining W1", 2); chat("/bot skill mining W2", 3)
    chat(f"/summon zombie {sx-6} {Y} {Z-4} {{PersistenceRequired:1b}}", 1)
    t0 = time.time(); time.sleep(60)
    hits = since(m, r"Terminating all current tasks")
    chat("/kill @e[type=zombie]", 1)
    for h in hits:
        print(h[:200])
    print("pauses:", [l[:200] for l in since(m, r"pause requested|Pausing|[Cc]ombat|Killed|killed")][:12])
    print(f"Terminating lines in {time.time()-t0:.0f} s: {len(hits)} per minute {per_minute(hits)} "
          f"W1={sum('W1' in h for h in hits)} W2={sum('W2' in h for h in hits)}")
    chat("/bot stop W1", 1); chat("/bot stop W2", 1)
elif st == "skeleton":
    # the pause path itself: a skeleton (shoots, so its hits pause a task) on a pillar 8 blocks from W1 and W2 woodcutting
    sx = X - 60
    chat(f"/tp @p {sx} {Y} {Z+6} 0 0", 4)
    chat("/kill @e[type=zombie]", 1); chat("/kill @e[type=skeleton]", 1)
    chat(f"/fill {sx-7} {Y} {Z-8} {sx+8} {Y+8} {Z-1} air", 1)
    for tx, tz in ((sx + 4, Z - 6), (sx + 7, Z - 3), (sx + 1, Z - 7)):
        tree(tx, tz, 5)
    for b, dx in (("W1", 2), ("W2", 4)):
        chat(f"/tp {b} {sx + dx} {Y} {Z - 2}", 1); chat(f"/clear {b}", 1)
        give(b, "stone_axe 1", "dirt 16", "bread 8")
    chat(f"/fill {sx-7} {Y} {Z-5} {sx-5} {Y+3} {Z-3} stone", 1)  # a 3x3 pillar, fenced on top so it cannot step off
    chat(f"/fill {sx-7} {Y+4} {Z-5} {sx-5} {Y+4} {Z-3} oak_fence", 1); chat(f"/setblock {sx-6} {Y+4} {Z-4} air", 1)
    m = mark()
    chat("/bot skill woodcut W1", 2); chat("/bot skill woodcut W2", 4)
    chat(f"/summon skeleton {sx-6} {Y+4} {Z-4} {{Invulnerable:1b,PersistenceRequired:1b}}", 1)
    t0 = time.time(); time.sleep(60)
    hits = since(m, r"Terminating all current tasks")
    chat("/kill @e[type=skeleton]", 1)
    for h in hits:
        print(h[:200])
    for l in since(m, r"pause requested|did not pause|hit paused|Pausing")[:12]:
        print("  >", l[:220])
    print(f"Terminating lines in {time.time()-t0:.0f} s: {len(hits)} per minute {per_minute(hits)} "
          f"W1={sum('W1' in h for h in hits)} W2={sum('W2' in h for h in hits)}")
    chat("/bot stop W1", 1); chat("/bot stop W2", 1)
elif st == "soil":
    tx = X - 3
    platform(r=10); chat(f"/tp @p {X+7} {Y} {Z+7}")
    tree(tx, Z, 9)
    print("before: under trunk", block_at(tx, Y - 1, Z))
    spawn("Axer", X + 1, Y, Z)
    give("Axer", "stone_axe 1", "wooden_pickaxe 1", "dirt 24", "bread 8")  # dirt: a bot pillar is then dirt too
    woodcut_run("Axer", tx, Z, 9, "soil")
elif st == "onetree":
    cx = X  # the 300,300 deck: no other tree in woodcut range (finding 41 was seen here); run after `soil` + `clean Axer`
    platform(cx, Z, r=10); chat(f"/tp @p {cx+7} {Y} {Z+7}")
    tree(cx + 3, Z - 3, 6)
    spawn("Axer2", cx - 1, Y, Z)
    give("Axer2", "stone_axe 1", "wooden_pickaxe 1", "cobblestone 24", "bread 8")
    woodcut_run("Axer2", cx + 3, Z - 3, 6, "onetree")
elif st == "fish":
    # fix274's pond is remembered by Fisher ("Reusing remembered fishing spot", no search): use a fresh pond, fish <dx> <dz>
    fx = X + (int(sys.argv[2]) if len(sys.argv) > 2 else 120)
    fz = Z + (int(sys.argv[3]) if len(sys.argv) > 3 else 0)
    chat(f"/tp @p {fx+8} {Y+2} {fz+8}", 4)
    platform(fx, fz, r=12); chat(f"/tp @p {fx+8} {Y} {fz+8}")
    chat(f"/fill {fx-3} {Y-2} {fz-3} {fx+3} {Y-1} {fz+3} water", 2)
    spawn("Fisher", fx + 5, Y, fz)
    give("Fisher", "fishing_rod 1", "bread 8")
    chat("/bot stop Fisher", 3)
    m = mark()
    chat("/bot fish 1", 2)
    watch(m, 60, r"Fishing spot|[Cc]ast|bobber", stop=r"[Cc]ast(ing)? |Casting|cast=")
    time.sleep(15)
    for l in since(m, r"Fishing spot search")[:3]:
        print("  >", l[:400])
    print("casts:", since(m, r"[Cc]ast|Reusing remembered|Selected fishing spot|Starting fishing session")[:4])
    chat("/bot stop Fisher", 2)
elif st == "starve":
    sx = X + 180
    zo = int(sys.argv[2]) if len(sys.argv) > 2 else 0  # starve -120: off fix274's tailorshop base (a protected zone)
    chat(f"/tp @p {sx+6} {Y+2} {Z+zo+6}", 4)
    platform(sx, Z + zo); chat(f"/tp @p {sx+6} {Y} {Z+zo+6}")
    spawn("Hungry", sx, Y, Z + zo)
    give("Hungry", "stone_pickaxe 1")
    chat("/effect give Hungry hunger 8 255", 9)
    chat("/effect clear Hungry", 1)
    print(cmd(cmd="bot_state", name="Hungry")[:400])
    m = mark()
    chat(f"/fill {sx-1} {Y} {Z+zo-1} {sx+1} {Y+2} {Z+zo+1} stone", 1)  # entombed: in_wall damage, missing health
    chat(f"/setblock {sx} {Y} {Z+zo} stone", 1); chat(f"/setblock {sx} {Y+1} {Z+zo} stone", 2)
    chat("/effect give Hungry resistance 90 4 true", 1)  # survive past the 10 s log window
    chat("/give Hungry rotten_flesh 16", 1); chat("/give Hungry torch 32", 1)  # only now: during the drain autoEat would eat it before the entombment
    chat("/bot skill stripmine Hungry", 1)
    t0 = time.time(); time.sleep(45)
    des = since(m, r"Desperate food")
    for h in des:
        print(h[:200])
    paused = since(m, r"starving and out of food")
    print("stripmine:", [l[:200] for l in since(m, r"[Ss]tripmine|Hungry.*(eat|starv|stuck)")][:12])
    print(f"Desperate lines in {time.time()-t0:.0f} s: {len(des)} per minute {per_minute(des)}; starving pauses: {paused}")
    print(cmd(cmd="bot_state", name="Hungry")[:400])
    chat("/bot stop Hungry", 1)
    chat(f"/fill {sx-1} {Y} {Z+zo-1} {sx+1} {Y+2} {Z+zo+1} air", 1)
elif st == "chests":
    d = json.load(open(REG))
    print("keys:", list(d.keys()))
    print("worlds:", {k: {b: len(v) for b, v in w.get("chestsByBot", {}).items()} for k, w in d.get("worlds", {}).items()})
    print("legacyWorldClaims:", d.get("legacyWorldClaims"))
    for l in since(0, r"[Cc]hest registry|Adopted legacy"):
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
elif st == "state":
    show_bag(sys.argv[2])
else:
    print(__doc__)
