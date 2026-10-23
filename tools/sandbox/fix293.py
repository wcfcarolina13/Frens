#!/usr/bin/env python3
"""1.1.293 checks in the mcp-sandbox (save "Wall Test", commands on, game loaded, control mode on).
A bot that leaves the End alive keeps its follow target and is reset once (RespawnResetPolicy); a death still runs
the respawn routine once (RespawnHandler gone); mining with the harmless fallback still works.

  fix293.py setup                     gamerules (no day cycle, no mobs), noon, overworld deck at X,Y,Z, End deck
  fix293.py endexit <bot> [task] [spawn] [fast]  fast: shorter waits (a task still running at the exit). spawn: the player waits beside world spawn (chat range). Fresh bot on the End deck in follow mode on the player, then an end_portal at its
                                      feet (limbo), the player back on the overworld deck, /tp <bot> @s. task: start a
                                      mining descent first (the task must end "Task ended: changed dimension.").
                                      Prints AFTER_RESPAWN / Respawn-resolved / regrouped / task-end counts, the bot's
                                      position right after and 3 s later, then moves the player next to the bot and
                                      12 blocks away and prints whether the bot followed.
  fix293.py death <bot> [spawn]       spawn: do it beside world spawn so the chat line is in range. Fresh bot on the overworld deck, /kill: one AFTER_RESPAWN (alive flag=false), one
                                      "Respawn for <bot>: resolved", one "regrouped" line.
  fix293.py mine <bot> [sword]        /bot skill mining on stone with a diamond pickaxe; sword: a diamond sword selected
                                      and a diamond pickaxe in the bag (the skill's pickaxe gate passes), digging down
                                      through dirt. A "Mining ... with" line naming air (empty hand) means the best tool
                                      was the empty hand, so switchToTool returned false and the harmless fallback ran.
                                      Fails on "could not equip a safe tool" or "too busy".
  fix293.py clean <bot>               despawn a stage's bot
Flashback: tools/sandbox/rec.py start / stop frens_1_1_293_<topic>.
"""
import json, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
MC = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft"
LOG = MC + "/logs/latest.log"
PLAYER = "RotiWokeman"
X, Y, Z = 1500, 120, 1000  # overworld deck, feet at Y
EX, EY, EZ = 100, 50, 0    # End deck, feet at EY (over the void: the deck is all that holds it)
END = "execute in minecraft:the_end run"
OW = "execute in minecraft:overworld run"


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


def ow_deck(x=X, z=Z, r=8, top="stone"):
    chat(f"/{OW} fill {x-r} {Y-3} {z-r} {x+r} {Y+5} {z+r} air", 1)
    chat(f"/{OW} fill {x-r} {Y-3} {z-r} {x+r} {Y-1} {z+r} {top}", 1)


def end_deck():
    chat(f"/{END} fill {EX-6} {EY-6} {EZ-6} {EX+6} {EY-1} {EZ+6} end_stone", 1)
    chat(f"/{END} fill {EX-6} {EY} {EZ-6} {EX+6} {EY+4} {EZ+6} air", 1)


def spawn(bot, dim_run, x, y, z):
    chat(f"/bot spawn {bot} admin", 12)
    chat(f"/{dim_run} tp {bot} {x} {y} {z}", 3)
    chat(f"/clear {bot}", 1)


def report(m, bot, alive):
    flag = "true" if alive else "false"
    ar = since(m, rf"AFTER_RESPAWN fired for bot {bot} ")
    out = {
        "after_respawn": len(ar),
        f"alive={flag}": len([l for l in ar if f"alive flag={flag}" in l]),
        "respawn_resolved": len(since(m, rf"Respawn for {bot}: resolved")),
        "regrouped": len(since(m, rf"{bot} has regrouped")),
        "task_ended_dim": len(since(m, r"Task ended: changed dimension")),
        "death_abort": len(since(m, r"aborted due to (bot )?respawn")),
        "old_handler": len(since(m, rf"Detected bot respawn for {bot}")),
        "errors": len(since(m, r"ERROR|Exception")),
    }
    for l in since(m, rf"AFTER_RESPAWN|Respawn for {bot}|regrouped|Task ended|aborted due|Detected bot respawn|"
                      rf"ERROR|Exception|{bot} (left|joined)")[:30]:
        print("  >", l[:260])
    print("COUNTS", json.dumps(out))
    return out


st = sys.argv[1] if len(sys.argv) > 1 else ""
if st == "setup":
    for t in ("/gamerule advance_time false", "/gamerule spawn_mobs false", "/time set noon", "/weather clear"):
        chat(t, 1)
    chat(f"/{OW} tp {PLAYER} {X+3} {Y} {Z}", 4)
    ow_deck()
    chat(f"/{END} forceload add {EX-16} {EZ-16} {EX+16} {EZ+16}", 6)  # the End deck must exist before anyone lands
    end_deck()
    chat(f"/{END} tp {PLAYER} {EX+3} {EY} {EZ+3}", 6)
    m = mark()
    chat(f"/{END} execute if entity @e[type=ender_dragon] run say DRAGON_PRESENT", 1)
    print("dragon present:", bool(since(m, "DRAGON_PRESENT")))
    chat(f"/{END} kill @e[type=ender_dragon]", 1)
    end_deck()
    chat(f"/{OW} tp {PLAYER} {X+3} {Y} {Z}", 4)
    print(where(PLAYER))
elif st == "endexit":
    bot = sys.argv[2]
    task = "task" in sys.argv[3:]
    # base target first, on the overworld deck (a follow order may replace it: both are recorded)
    chat(f"/{OW} tp {PLAYER} {X+3} {Y} {Z}", 4)
    spawn(bot, OW, X + 0.5, Y, Z + 0.5)
    chat(f"/bot base set endexit{bot.lower()}", 2)
    chat(f"/give {bot} bread 8", 1)
    end_deck()
    chat(f"/{END} tp {PLAYER} {EX+3} {EY} {EZ+3}", 5)
    chat(f"/{END} tp {bot} {EX + 0.5} {EY} {EZ + 0.5}", 2)
    print("bot before:", where(bot), "| player:", where(PLAYER))
    inv0 = [(i["id"], i["count"]) for i in state(bot).get("inventory", [])]
    m0 = mark()
    chat(f"/bot follow {bot}", 4)
    print("follow lines before exit:", len(since(m0, rf"Follow decision: bot={bot}")),
          [l[:200] for l in since(m0, rf"(?i)follow.*{bot}|{bot}.*follow")[:4]])
    if task:
        chat(f"/give {bot} diamond_pickaxe 1", 1)
        chat(f"/bot skill mining descent 4 {bot}", 4)
        print("task lines:", [l[:200] for l in since(m0, r"Skill|skill|Mining|descent")[-6:]])
    p = state(bot).get("block") or [EX, EY, EZ]
    m = mark()
    fast = "fast" in sys.argv[3:]  # shorter waits, so a running task is still running at the exit
    chat(f"/{END} setblock {p[0]} {p[1]} {p[2]} minecraft:end_portal", 1 if fast else 4)
    print("after portal:", where(bot), "| limbo lines:", [l[:200] for l in since(m, rf"{bot}|portal|credits")[:6]])
    if "spawn" in sys.argv[3:]:
        chat(f"/{OW} tp {PLAYER} 4 73 4", 2 if fast else 5)  # beside world spawn, where vanilla puts the bot: chat lines in range
    else:
        chat(f"/{OW} tp {PLAYER} {X+3} {Y} {Z}", 5)
    m1 = mark()
    chat(f"/tp {bot} {PLAYER}", 1)
    w1 = where(bot)
    time.sleep(3)
    w2 = where(bot)
    print("after /tp: +1 s", w1, "| +4 s", w2)
    c = report(m, bot, True)
    if c["after_respawn"] == 0:  # the recipe's fallback: a cross-dimension tp by coordinates
        print("no AFTER_RESPAWN after /tp <bot> <player>; trying execute in overworld tp")
        chat(f"/{OW} tp {bot} {X+1} {Y} {Z}", 1)
        w1 = where(bot)
        time.sleep(3)
        w2 = where(bot)
        print("after execute tp: +1 s", w1, "| +4 s", w2)
        c = report(m, bot, True)
    print("bag before", inv0, "| after", [(i["id"], i["count"]) for i in state(bot).get("inventory", [])])
    # follow: bring the player beside the bot, then 12 blocks away, and watch it come
    if w2[0] and isinstance(w2[1], list):
        bx, by, bz = w2[1]
        chat(f"/{OW} tp {PLAYER} {bx+2:.1f} {by:.1f} {bz:.1f}", 3)
        m2 = mark()
        chat(f"/{OW} tp {PLAYER} {bx+12:.1f} {by:.1f} {bz:.1f}", 1)
        t0, best = time.time(), None
        while time.time() - t0 < 25:
            s = state(bot)
            pp = state(PLAYER).get("pos")
            if s.get("pos") and pp:
                d = dist(s["pos"], pp)
                best = d if best is None else min(best, d)
                if d < 4:
                    break
            time.sleep(1)
        fl = since(m2, rf"Follow decision: bot={bot}")
        print(f"FOLLOW: start gap ~10, closest {best and round(best, 2)} | follow decision lines {len(fl)}")
        for l in fl[:3] + fl[-2:]:
            print("  f>", l[:240])
    print("post-exit regrouped/resolved since tp:", len(since(m1, r"regrouped|Respawn for .*resolved")))
elif st == "death":
    bot = sys.argv[2]
    if len(sys.argv) > 3 and sys.argv[3] == "spawn":
        # beside world spawn (0, 73, 0 in Wall Test), where the death respawn puts the bot, so the
        # "regrouped" line is in chat range (CompanionCommunicationPolicy drops it from far away)
        chat(f"/{OW} tp {PLAYER} 4 73 4", 4)
        spawn(bot, OW, 2.5, 73, 2.5)
    else:
        ow_deck(X + 30, Z)
        chat(f"/{OW} tp {PLAYER} {X+33} {Y} {Z}", 4)
        spawn(bot, OW, X + 30.5, Y, Z + 0.5)
    print("bot before:", where(bot))
    m = mark()
    chat(f"/kill {bot}", 10)
    print("bot after:", where(bot))
    report(m, bot, False)
elif st == "mine":
    bot = sys.argv[2]
    sword = len(sys.argv) > 3 and sys.argv[3] == "sword"
    cx = X + 60 if not sword else X + 90
    ow_deck(cx, Z, top="stone" if not sword else "dirt")
    chat(f"/{OW} tp {PLAYER} {cx+3} {Y} {Z+3}", 4)
    spawn(bot, OW, cx + 0.5, Y, Z + 0.5)
    if sword:
        chat(f"/give {bot} diamond_sword 1", 1)  # slot 0, selected
    chat(f"/give {bot} diamond_pickaxe 1", 1)
    chat(f"/give {bot} bread 4", 1)
    m = mark()
    chat(f"/bot skill mining {'descent 2 ' if sword else ''}{bot}", 1)
    t0 = time.time()
    while time.time() - t0 < 60:
        if since(m, rf"Skill 'mining' finished for bot {bot}"):
            break
        time.sleep(2)
    for l in since(m, rf"Mining class|Skill 'mining'|harmless|safe tool|too busy|Cannot mine|refusing|ERROR|Exception")[:25]:
        print("  >", l[:260])
    print("SUMMARY mine:", json.dumps({
        "mined": len(since(m, r"Mining class_2338")),
        "safe_tool_msg": len(since(m, r"could not equip a safe tool")),
        "busy_msg": len(since(m, r"too busy")),
        "finished": [l[l.find("success"):][:160] for l in since(m, rf"Skill 'mining' finished for bot {bot}")],
        "held": state(bot).get("held"), "bag": [(i["id"], i["count"]) for i in state(bot).get("inventory", [])]}))
elif st == "clean":
    chat(f"/bot despawn {sys.argv[2]}", 3)
else:
    print(__doc__)
