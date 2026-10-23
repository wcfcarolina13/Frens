#!/usr/bin/env python3
"""1.1.295 checks in the mcp-sandbox (game loaded, control mode on, commands on).

Save "Sweep Souls3" (stages leaf, snow, stopack):
  fix295.py setup                 gamerules (no day cycle, no mobs), noon, clear weather
  fix295.py leaf [name]           F1: a 1-wide stone corridor ends in a one-block step up east. Oak leaves
                                  (persistent=false) sit at Y+2 over every corridor cell, so the bot's own head cell is
                                  a leaf wherever it stands before the step; nothing else blocks the step. The bot
                                  follows the player standing up on the raised deck. Pass: the bot gets onto the deck.
  fix295.py snow [name]           F2: snow[layers=1] on grass, the player looks at it, `/bot place crafting_table 1 <bot>`.
                                  Pass: a table where the snow was, no "Can't keep up", no "unexpected error".
  fix295.py far [name]            F2: the same, but the bot stands 7 blocks from the target. Pass: "I can't reach that
                                  spot." at once, no "I don't have any", no freeze.
  fix295.py stopack [name] [n]    text-only lines: n x `/bot stop` while following; prints the stop lines seen in chat
                                  (STOP_ACK_LINES includes the text-only stop_adios).
Save "Wall Test" (fix293.py setup first for the End deck):
  fix295.py walkin <bot> [task]   a bot in follow on the End deck walks into an end_portal at its feet, no /tp.
                                  Pass: it lands in the overworld by itself, one AFTER_RESPAWN alive=true,
                                  "Task ended: changed dimension." once in chat (task), and it follows again.
  fix295.py walkin2 <bot>         the same bot a second time (seenCredits is now true: plain teleport path).
  fix295.py deathtask <bot> [kill|fall]  a mining task, then death by /kill or by fall damage. Pass: no
                                  "Mining complete" after the death, the skill exits abortRequested=true.
Flashback: tools/sandbox/rec.py start / stop frens_1_1_295_<topic>.
"""
import json, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
MC = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft"
LOG = MC + "/logs/latest.log"
PLAYER = "RotiWokeman"
Y = 120
END = "execute in minecraft:the_end run"
OW = "execute in minecraft:overworld run"
EX, EY, EZ = 100, 50, 0     # fix293's End deck
WX, WY, WZ = 1500, 120, 1000  # fix293's overworld deck


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


def where(bot):
    s = state(bot)
    return (s.get("dim"), s.get("pos")) if "pos" in s else (None, s.get("error"))


def dist(a, b):
    return ((a[0] - b[0]) ** 2 + (a[2] - b[2]) ** 2) ** 0.5


def spawn(bot, x, y, z, dim=OW):
    if not state(bot).get("pos"):
        chat(f"/bot spawn {bot} admin", 12)
    chat(f"/{dim} tp {bot} {x} {y} {z}", 3)
    chat(f"/clear {bot}", 1)


def show(m, pat, n=20):
    for l in since(m, pat)[:n]:
        print("  >", l[:260])


def block(x, y, z):
    try:
        lv = json.loads(cmd(cmd="blocks", x=x, y=y, z=z, r=0, dy=0))["levels"]
        return lv[0]["rows"][0].lstrip("@")
    except Exception as e:  # noqa: BLE001
        return f"? {e}"


st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear"):
        chat(t, 1)
elif st == "leaf":
    bot = sys.argv[2] if len(sys.argv) > 2 else "Leaf295"
    x0, z0 = 1800, 1800
    chat(f"/tp @p {x0} {Y + 6} {z0}", 3)
    chat(f"/fill {x0 - 12} {Y - 2} {z0 - 4} {x0 + 14} {Y + 6} {z0 + 4} air", 1)
    chat(f"/fill {x0 - 12} {Y - 2} {z0 - 4} {x0 + 14} {Y - 1} {z0 + 4} stone", 1)
    chat(f"/fill {x0 + 1} {Y} {z0 - 4} {x0 + 14} {Y} {z0 + 4} grass_block", 1)       # raised deck, feet Y+1
    chat(f"/fill {x0 - 10} {Y} {z0 - 1} {x0} {Y + 3} {z0 - 1} stone", 1)              # corridor walls
    chat(f"/fill {x0 - 10} {Y} {z0 + 1} {x0} {Y + 3} {z0 + 1} stone", 1)
    chat(f"/fill {x0 - 10} {Y + 3} {z0} {x0} {Y + 3} {z0} stone", 1)                 # roof, no way over
    chat(f"/fill {x0 - 6} {Y + 2} {z0} {x0} {Y + 2} {z0} oak_leaves[persistent=false]", 1)
    chat(f"/setblock {x0 - 2} {Y + 2} {z0 - 1} oak_log", 1)                            # keeps the leaves alive
    chat(f"/setblock {x0 - 5} {Y + 2} {z0 + 1} oak_log", 1)
    print("leaves before:", [block(x, Y + 2, z0) for x in range(x0 - 6, x0 + 1)])
    spawn(bot, x0 - 7.5, Y, z0 + 0.5)
    chat(f"/tp @p {x0 + 8.5} {Y + 1} {z0 + 0.5} 90 0", 3)
    m = mark()
    chat(f"/bot follow {bot}", 1)
    t0, up = time.time(), None
    while time.time() - t0 < 40:
        p = state(bot).get("pos")
        if p and p[0] > x0 + 1.2 and p[1] >= Y + 0.9:
            up = round(time.time() - t0, 1)
            break
        time.sleep(0.5)
    print("RESULT leaf:", "UP at +%ss" % up if up else "NOT UP", "| bot", where(bot))
    print("leaves after:", [block(x, Y + 2, z0) for x in range(x0 - 6, x0 + 1)])
    show(m, rf"leaf-action|Mining complete|Mining aborted|leaf-cleared|applyMovementInput-reject bot={bot}|Can't keep up", 25)
    chat(f"/bot stop {bot}", 2)
elif st in ("snow", "far"):
    bot = sys.argv[2] if len(sys.argv) > 2 else "SM295"
    x0, z0 = (1850, 1850) if st == "snow" else (1870, 1850)
    chat(f"/tp @p {x0} {Y + 4} {z0 + 2}", 3)
    chat(f"/fill {x0 - 8} {Y - 2} {z0 - 8} {x0 + 8} {Y + 5} {z0 + 8} air", 1)
    chat(f"/fill {x0 - 8} {Y - 2} {z0 - 8} {x0 + 8} {Y - 1} {z0 + 8} grass_block", 1)
    chat(f"/setblock {x0} {Y} {z0} snow[layers=1]", 1)
    bx = x0 + 2.5 if st == "snow" else x0 + 7.5
    spawn(bot, bx, Y, z0 + 0.5)
    chat(f"/give {bot} crafting_table 1", 1)
    chat(f"/tp @p {x0 + 0.5} {Y} {z0 + 2.5} 180 37", 3)
    m = mark()
    t0 = time.time()
    chat(f"/bot place crafting_table 1 {bot}", 6)
    print("RESULT", st, "| target:", block(x0, Y, z0), "| above:", block(x0, Y + 1, z0),
          "| elapsed ~%.0fs" % (time.time() - t0))
    print("COUNTS", json.dumps({
        "cant_keep_up": len(since(m, r"Can't keep up")),
        "unexpected": len(since(m, r"unexpected error|Unexpected error in /bot place")),
        "cant_reach": len(since(m, r"I can't reach that spot")),
        "dont_have": len(since(m, r"I don't have any")),
        "placed": len(since(m, r"Placed \d+ crafting_table")),
        "nudge_on_server": len(since(m, r"on the server thread"))}))
    show(m, r"CHAT\]|Movement execute|server thread|Can't keep up|place", 15)
elif st == "stopack":
    bot = sys.argv[2] if len(sys.argv) > 2 else "Stop295"
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 12
    chat(f"/tp @p 1900 {Y} 1900", 3)
    chat(f"/fill 1892 {Y - 2} 1892 1908 {Y + 4} 1908 air", 1)
    chat(f"/fill 1892 {Y - 2} 1892 1908 {Y - 1} 1908 grass_block", 1)
    spawn(bot, 1902.5, Y, 1900.5)
    m = mark()
    for _ in range(n):
        chat(f"/bot follow {bot}", 2)
        chat(f"/bot stop {bot}", 4)
    show(m, rf"CHAT\].*{bot}|{bot}.*(Sending chat|overhead)", 40)
    print("adios lines:", len(since(m, r"(?i)CHAT\].*adi")), "| missing sound:", len(since(m, r"Missing sound|stop_adios")))
elif st in ("walkin", "walkin2"):
    bot = sys.argv[2]
    task = "task" in sys.argv[3:]
    chat(f"/{OW} tp {PLAYER} {WX + 3} {WY} {WZ}", 4)
    if st == "walkin":
        spawn(bot, WX + 0.5, WY, WZ + 0.5)
        chat(f"/bot base set walkin{bot.lower()}", 2)
        chat(f"/give {bot} bread 8", 1)
    chat(f"/{END} fill {EX-6} {EY-6} {EZ-6} {EX+6} {EY-1} {EZ+6} end_stone", 1)
    chat(f"/{END} fill {EX-6} {EY} {EZ-6} {EX+6} {EY+4} {EZ+6} air", 1)
    chat(f"/{END} tp {PLAYER} {EX+3} {EY} {EZ+3}", 5)
    chat(f"/{END} tp {bot} {EX + 0.5} {EY} {EZ + 0.5}", 3)
    print("bot before:", where(bot))
    chat(f"/bot follow {bot}", 4)
    if task:
        chat(f"/give {bot} diamond_pickaxe 1", 1)
        chat(f"/bot skill mining descent 4 {bot}", 3)
    p = state(bot).get("block") or [EX, EY, EZ]
    m = mark()
    # portal first, the player follows 1 s later (moving the player first lets follow carry the bot across)
    chat(f"/{END} setblock {p[0]} {p[1]} {p[2]} minecraft:end_portal", 1)
    print("1 s after the portal:", where(bot))
    chat(f"/{OW} tp {PLAYER} 4 73 4", 1)  # beside world spawn, where vanilla puts the bot: chat lines in range
    seen = []
    t0 = time.time()
    while time.time() - t0 < 15:
        w = where(bot)
        seen.append((round(time.time() - t0, 1), w[0]))
        if w[0] and "overworld" in str(w[0]):
            break
        time.sleep(0.5)
    time.sleep(3)
    w = where(bot)
    print("RESULT", st, "| bot after:", w, "| first overworld at:", [s for s in seen if s[1] and "overworld" in str(s[1])][:1])
    ar = since(m, rf"AFTER_RESPAWN fired for bot {bot} ")
    print("COUNTS", json.dumps({
        "after_respawn": len(ar), "alive_true": len([l for l in ar if "alive flag=true" in l]),
        "chat_task_ended": len(since(m, r"CHAT\].*Task ended: changed dimension")),
        "regrouped": len(since(m, rf"{bot} has regrouped")),
        "errors": len(since(m, r"ERROR|Exception"))}))
    show(m, rf"AFTER_RESPAWN|Respawn for {bot}|CHAT\]|FollowAssert|Skill 'mining' exit|ERROR|Exception", 25)
    if w[0] and isinstance(w[1], list):
        bx, by, bz = w[1]
        chat(f"/{OW} tp {PLAYER} {bx + 2:.1f} {by:.1f} {bz:.1f}", 3)
        chat(f"/{OW} tp {PLAYER} {bx + 12:.1f} {by:.1f} {bz:.1f}", 1)
        t0, best = time.time(), None
        while time.time() - t0 < 25:
            s, pp = state(bot).get("pos"), state(PLAYER).get("pos")
            if s and pp:
                d = dist(s, pp)
                best = d if best is None else min(best, d)
                if d < 4:
                    break
            time.sleep(1)
        print(f"FOLLOW: start gap ~10, closest {best and round(best, 2)}")
elif st == "deathtask":
    bot = sys.argv[2]
    how = sys.argv[3] if len(sys.argv) > 3 else "kill"
    # the bot mines stone on a deck outside the base:spawn zone (spawn refuses mining); the player waits at
    # world spawn, where the bot respawns, so the respawn chat is in range (fix293 deathtask far plain)
    dx = WX + 60
    chat(f"/{OW} fill {dx-8} {WY-3} {WZ-8} {dx+8} {WY+5} {WZ+8} air", 1)
    chat(f"/{OW} fill {dx-8} {WY-3} {WZ-8} {dx+8} {WY-1} {WZ+8} stone", 1)
    chat(f"/{OW} tp {PLAYER} {dx+3} {WY} {WZ}", 4)
    spawn(bot, dx + 0.5, WY, WZ + 0.5)
    chat(f"/give {bot} diamond_pickaxe 1", 1)
    m0 = mark()
    chat(f"/bot skill mining {bot}", 3)
    chat(f"/{OW} tp {PLAYER} 4 73 4", 2)
    print("task running:", bool(since(m0, rf"Skill 'mining' executing for bot {bot}")))
    m = mark()
    if how == "fall":
        chat(f"/damage {bot} 1000 minecraft:fall", 1)
    else:
        chat(f"/kill {bot}", 1)
    time.sleep(12)
    print("RESULT deathtask", how, "| bot after:", where(bot))
    print("COUNTS", json.dumps({
        "after_respawn": len(since(m, rf"AFTER_RESPAWN fired for bot {bot} ")),
        "mining_complete_after_death": len(since(m, r"Mining complete!")),
        "paused_chat": len(since(m, r"CHAT\].*Skill paused: bot died")),
        "teleport_detector": len(since(m, rf"External teleport detected for {bot}")),
        "still_running_task_lines": len(since(m, rf"Mining class_2338|Skill 'mining' finished for bot {bot}: success=true")),
        "errors": len(since(m, r"ERROR|Exception"))}))
    show(m, rf"Skill 'mining' (exit|finished)|CHAT\]|AFTER_RESPAWN|aborted|moved \d+ blocks|ERROR|Exception", 25)
elif st == "spawnfar":
    # crash recheck: the client reads the bot alias list every tick; spawn bots with the player far from all of them
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    chat(f"/{OW} tp {PLAYER} {WX + 300} {WY + 20} {WZ + 300}", 4)
    m = mark()
    for i in range(n):
        name = f"Far295{chr(97 + i)}{sys.argv[3] if len(sys.argv) > 3 else ''}"
        chat(f"/bot spawn {name} admin", 8)
        chat(f"/bot despawn {name}", 4)
    print("RESULT spawnfar | health:", cmd(cmd="health")[:160])
    print("COUNTS", json.dumps({"spawned": len(since(m, r"Spawned new bot Far295")),
                                "errors": len(since(m, r"ERROR|Exception"))}))
elif st == "portalline":
    # text-only ambient pool: both weird_portal_nether_in_end lines are text only; a nether portal near a bot in the End
    bot = sys.argv[2] if len(sys.argv) > 2 else "Port295"
    chat(f"/{END} tp {PLAYER} {EX + 3} {EY} {EZ + 3}", 5)
    chat(f"/{END} fill {EX-6} {EY-6} {EZ-6} {EX+6} {EY-1} {EZ+6} end_stone", 1)
    chat(f"/{END} fill {EX-6} {EY} {EZ-6} {EX+6} {EY+4} {EZ+6} air", 1)
    spawn(bot, EX + 0.5, EY, EZ + 0.5, dim=END)
    m = mark()
    chat(f"/{END} fill {EX+3} {EY} {EZ-3} {EX+6} {EY+4} {EZ-3} obsidian", 1)
    chat(f"/{END} fill {EX+4} {EY+1} {EZ-3} {EX+5} {EY+3} {EZ-3} nether_portal[axis=x]", 30)
    print("portal:", block(EX + 4, EY + 1, EZ - 3))
    print("COUNTS", json.dumps({
        "portal_text": len(since(m, r"Nether portal\? Here|Portal to the Nether\.\.\.in The End")),
        "missing_sound": len(since(m, r"Missing sound")),
        "weird_played": len(since(m, r"Played sound frens:bot\.line\.weird_portal")),
        "errors": len(since(m, r"ERROR|Exception"))}))
    show(m, rf"Subtitle\].*{bot}|overhead.*{bot}|{bot}.*(Nether portal|Portal to the Nether)|weird_portal", 10)
elif st == "sniffer":
    bot = sys.argv[2] if len(sys.argv) > 2 else "Sniff295"
    tries = int(sys.argv[3]) if len(sys.argv) > 3 else 4
    chat(f"/{OW} tp {PLAYER} {WX + 3} {WY} {WZ}", 4)
    spawn(bot, WX + 0.5, WY, WZ + 0.5)
    m = mark()
    for _ in range(tries):
        chat(f"/{OW} summon sniffer {WX + 4} {WY} {WZ + 3}", 25)
        chat(f"/{OW} kill @e[type=sniffer]", 2)
        if since(m, r"cutest thing I've ever seen"):
            break
    print("COUNTS", json.dumps({
        "cutest_text": len(since(m, r"cutest thing I've ever seen")),
        "cutest_played": len(since(m, r"Played sound frens:bot\.line\.sniffer_cutest")),
        "dinosaur": len(since(m, r"Dinosaur\.")),
        "errors": len(since(m, r"ERROR|Exception"))}))
    show(m, r"cutest thing|Dinosaur|sniffer", 10)
else:
    print(__doc__)
