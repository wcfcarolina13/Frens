#!/usr/bin/env python3
"""1.1.272 fix checks in the mcp-sandbox (a commands-on save, game loaded, control mode on). Each stage builds on a
floating stone platform far from the base so terrain never varies; the player is teleported beside it.

  fix272.py setup              gamerules (day, no mobs), platform at X,Y,Z
  fix272.py guard              finding 5: bot Wedge in a 1x1x2 hole, 16 cobblestone, /bot stop then /bot guard
                               (the stop latch case), owner 4 blocks away. Pass: '[guard-escape] ... cleared' or a
                               fresh order, then the bot stands on the platform; no 'reason=aborted'.
  fix272.py woodaxe            finding 8: axe-less Jake2 beside an unregistered chest of 2 stone axes and a 5-log tree.
                               Pass: '[supply] tool scan for Jake2: registered=0 nearby=1', a supply prompt or take, then
                               logs cut with minecraft:stone_axe. Answer the prompt in chat if one shows.
  fix272.py bundle             finding 6: Digger with a loose stone pickaxe and a diamond pickaxe in a bundle mines 3 stone.
                               Pass: 'with minecraft:diamond_pickaxe'.
  fix272.py wool               finding 35: Shep with shears, no sheep anywhere reachable (a sheep 20 blocks up in a glass box).
                               Pass: the give-up chat line within about a minute, no flood (sb_flood).
  fix272.py lodestone <bot>    finding 31: the bot (holding a lodestone compass whose lodestone is far away, e.g. Jake's
                               "Home" in "Sweep Old3") is fed and sent with /bot compass travel. Pass: departs and arrives
                               (bot_state pos at the lodestone), never "no longer points to a valid lodestone".
  fix272.py travelwait <bot>   finding 32: right after a travel (cooldown running), bag filled with dirt, chest 4 blocks
                               away, travel again. Pass: 'travel-wait ... chest=yes ... -> OFFLOAD_EXISTING'.
  fix272.py clean <bot>        despawn a stage's bot
Flashback: tools/sandbox/rec.py start / stop frens_1_1_272_<topic>.
"""
import json, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
X, Y, Z = 300, 120, 300  # platform top is Y-1, feet at Y


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=30).read().decode()
    except Exception as e:  # noqa: BLE001
        return f"ERR {e}"


def chat(t, wait=2):
    r = cmd(cmd="send_chat", text=t); time.sleep(wait); return r


def mark():
    return len(open(LOG, errors="replace").read().splitlines())


def watch(m, secs, pat, stop=None):
    """Print new log lines matching pat for up to secs; return early when stop matches."""
    seen, end = m, time.time() + secs
    hits = []
    while time.time() < end:
        lines = open(LOG, errors="replace").read().splitlines()
        for l in lines[seen:]:
            if re.search(pat, l):
                print(l[:260]); hits.append(l)
                if stop and re.search(stop, l):
                    return hits
        seen = len(lines); time.sleep(2)
    return hits


def platform(cx=X, cz=Z, r=8):
    chat(f"/fill {cx-r} {Y-3} {cz-r} {cx+r} {Y+6} {cz+r} air")
    chat(f"/fill {cx-r} {Y-3} {cz-r} {cx+r} {Y-1} {cz+r} stone")


def spawn(bot, x, y, z):
    chat(f"/bot spawn {bot} admin", 12)
    chat(f"/tp {bot} {x} {y} {z}", 2)
    chat(f"/clear {bot}", 1)


st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear"):
        chat(t, 1)
    platform(); chat(f"/tp @p {X+4} {Y} {Z}")
elif st == "guard":
    platform(); chat(f"/tp @p {X+4} {Y} {Z}")
    chat(f"/fill {X} {Y-2} {Z} {X} {Y-1} {Z} air")  # 1x1x2 hole, open sky
    spawn("Wedge", X + 0.5, Y - 2, Z + 0.5)
    chat("/give Wedge cobblestone 16", 1)
    m = mark()
    chat("/bot stop Wedge", 3)  # sets the manual-stop latch: the case review A found HOLDing forever
    chat("/bot guard Wedge", 2)
    watch(m, 120, r"guard-escape|Guard escape|pillar recovery|operational surface|Wedge.*(aborted|surface)",
          stop=r"reached operational surface|guard point moved")
    print(cmd(cmd="bot_state", name="Wedge")[:300])
elif st == "woodaxe":
    platform(); chat(f"/tp @p {X+4} {Y} {Z+2}")
    chat(f"/setblock {X+2} {Y} {Z} chest", 1)
    chat(f'/item replace block {X+2} {Y} {Z} container.0 with stone_axe', 1)
    chat(f'/item replace block {X+2} {Y} {Z} container.1 with stone_axe', 1)
    chat(f"/fill {X-4} {Y} {Z} {X-4} {Y+4} {Z} oak_log", 1)
    chat(f"/fill {X-6} {Y+3} {Z-2} {X-2} {Y+5} {Z+2} oak_leaves keep", 1)
    spawn("Jake2", X + 1, Y, Z + 1)
    m = mark()
    chat("/bot skill woodcut Jake2", 2)
    watch(m, 150, r"\[supply\]|tool scan|Jake2.*(axe|bare|punch|Mining|log)", stop=r"Got \d+ logs|woodcut.*(COMPLETED|FAILED)")
elif st == "bundle":
    platform(); chat(f"/tp @p {X+4} {Y} {Z}")
    spawn("Digger", X, Y, Z)
    chat("/give Digger stone_pickaxe 1", 1)
    chat('/give Digger bundle[bundle_contents=[{id:"minecraft:diamond_pickaxe",count:1}]] 1', 1)
    chat(f"/fill {X-3} {Y} {Z-1} {X-3} {Y+1} {Z+1} stone", 1)
    m = mark()
    chat("/bot skill mining Digger", 2)
    watch(m, 90, r"Digger.*(with minecraft:|bundle)|Mining .* with", stop=r"Collected|Mining complete")
elif st == "wool":
    platform(); chat(f"/tp @p {X+4} {Y} {Z}")
    chat(f"/fill {X-1} {Y+19} {Z-1} {X+1} {Y+22} {Z+1} glass hollow", 1)
    chat(f"/summon sheep {X} {Y+20} {Z}", 1)
    spawn("Shep", X, Y, Z)
    chat("/give Shep shears 1", 1)
    m = mark()
    chat("/bot skill wool Shep", 2)
    watch(m, 120, r"Shep.*(stuck|sheep|wool)|Exploration stuck|stopping wool", stop=r"stopping wool")
elif st == "lodestone":
    bot = sys.argv[2]
    chat(f"/give {bot} cooked_beef 16", 1)
    m = mark()
    chat(f"/bot compass travel {bot}", 2)
    watch(m, 240, r"compass|lodestone|has departed|arrived|cannot travel", stop=r"has arrived|no longer points|cannot travel")
    print(cmd(cmd="bot_state", name=bot)[:200])
elif st == "travelwait":
    bot = sys.argv[2]
    platform(); chat(f"/tp @p {X+4} {Y} {Z}")
    chat(f"/tp {bot} {X} {Y} {Z}", 2)
    chat(f"/setblock {X} {Y} {Z+4} chest", 1)
    chat(f"/give {bot} dirt 2112", 1)
    m = mark()
    chat(f"/bot compass travel {bot}", 2)
    watch(m, 60, r"travel-wait", stop=r"OFFLOAD_EXISTING|TRAVEL_NOW")
elif st == "clean":
    chat(f"/bot despawn {sys.argv[2]}", 3)
else:
    print(__doc__)
