#!/usr/bin/env python3
"""Emergency rescue vs chest food (1.1.269/1.1.270, field Phase 6bi) and honey reach, in the mcp-sandbox
'Wall Test' save (commands on). The game must be loaded, rescues ON for this save's world key
(Admin > Autonomous rescues, or `autonomousRescuesEnabled: true` under survivalRecruitment."<world key>"
in config/settings.json5 with the game closed; `/bot recruit status` prints the key).

Stages (run in order; each prints the log lines it proves with):
  setup     despawn other bots, obsidian cell 25 below the surface with Wally + a chest beside him,
            base RescueBase above it (makes the chest a food candidate), owner on the surface
  a_dire    5 bread (all reserve), Wally emptied and starved: expect `Emergency rescue` lines
  a_refill  12 bread: expect a `[supply] request ... OPENED id=`, no rescue while it waits;
            the script answers `once` 15 s after it opens (a prompt lives 30 s), expect `Mutual aid: Wally took`
  b         12 bread, owner 250 blocks away (chunk forceloaded), Wally starved: expect the rescue
            despite the chest food, and the take refused for the absent owner
  c         smoked full beehive, Wally 4 blocks away, then 8: expect `Harvested honey`
  cleanup   remove the base and forceload, despawn Wally
"""
import json, os, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
BOT = "Wally"
OTHERS = ["Smokey", "Trainee", "Abby"]
# cell: obsidian shell, bot at (X+1, Y+1, Z+1), chest at (X+2, Y+1, Z+1)
X, Y, Z = 100, 40, 140
BX, BY, BZ = X + 1, Y + 1, Z + 1
CX, CY, CZ = X + 2, Y + 1, Z + 1
SURF = 66  # owner stands at (BX, SURF, BZ): about 25 blocks above, inside the 32-block prompt range
# honey: platform floor y=64, campfire under the hive smokes it
HX, HY, HZ = 70, 66, 102
WATCH = r"Emergency rescue|\[supply\]|Mutual aid: %s|honey_collect|Harvested honey" % BOT


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=30).read().decode()
    except Exception as e:  # noqa: BLE001
        return f"ERR {e}"


def chat(text, wait=2):
    cmd(cmd="send_chat", text=text)
    time.sleep(wait)


def mark():
    return len(open(LOG, errors="replace").read().splitlines())


def lines(start, pattern=WATCH):
    return [l[:240] for l in open(LOG, errors="replace").read().splitlines()[start:] if re.search(pattern, l)]


def show(start, pattern=WATCH):
    for l in lines(start, pattern):
        print("  " + l)


def state():
    try:
        s = json.loads(cmd(cmd="bot_state", name=BOT))
    except Exception:  # noqa: BLE001
        return "bot_state unreadable"
    inv = [f"{i.get('id')}x{i.get('count')}" for i in s.get("inventory", [])] if isinstance(s.get("inventory"), list) else s.get("inventory")
    return f"pos={s.get('pos')} food={s.get('food')} health={s.get('health')} inv={inv}"


def starve():
    chat(f"/effect give {BOT} minecraft:hunger 15 255", 6)
    chat(f"/effect clear {BOT} minecraft:hunger")
    print("  after hunger:", state())


def chest(n):
    item = f"minecraft:bread {n}" if n > 0 else "minecraft:air"
    chat(f"/item replace block {CX} {CY} {CZ} container.0 with {item}")
    chat(f"/item replace block {CX} {CY} {CZ} container.1 with minecraft:air")


def owner_home():
    chat(f"/tp @s {BX}.5 {SURF} {BZ}.5", 3)


def build_cell():
    chat(f"/fill {X} {Y} {Z} {X + 3} {Y + 3} {Z + 2} minecraft:obsidian")
    chat(f"/fill {BX} {BY} {BZ} {BX} {BY + 1} {BZ} minecraft:air")
    chat(f"/setblock {CX} {CY + 1} {CZ} minecraft:air")
    chat(f"/setblock {CX} {CY} {CZ} minecraft:chest[facing=west]")


def wait_watch(seconds, start, stop=None):
    end = time.time() + seconds
    while time.time() < end:
        time.sleep(5)
        if stop and any(re.search(stop, l) for l in lines(start)):
            return True
    return False


stage = sys.argv[1] if len(sys.argv) > 1 else "setup"
cmd(cmd="enter_control_mode")
cmd(cmd="press_key", key="escape")  # an open screen pauses the integrated server
time.sleep(1)

if stage == "setup":
    chat("/time set 1000")
    chat("/gamerule doDaylightCycle false")
    chat("/difficulty normal")  # hard starves to death; peaceful refills hunger
    chat("/gamemode creative")
    for b in OTHERS:
        chat(f"/bot despawn {b}", 3)
    owner_home()
    chat("/forceload add %d %d" % (BX, BZ))
    build_cell()
    chat(f"/bot base set RescueBase", 3)
    chat(f"/tp {BOT} {BX}.5 {BY} {BZ}.5", 3)
    chat(f"/clear {BOT}")
    chat(f"/effect give {BOT} minecraft:instant_health 1 5")
    chest(0)
    print(cmd(cmd="blocks", x=BX, y=BY, z=BZ, r=2, dy=1)[:900])
    print(" ", state())

elif stage == "a_dire":
    owner_home()
    chest(5)
    chat(f"/clear {BOT}")
    m = mark()
    starve()
    wait_watch(45, m)
    show(m)
    print(" ", state())

elif stage == "a_refill":
    owner_home()
    chat(f"/clear {BOT}")
    chest(12)
    m = mark()
    starve()
    # The take may ask as soon as the bot is hungry, even during starve(); poll by the second.
    end = time.time() + 90
    while time.time() < end and not any("OPENED id=" in l for l in lines(m)):
        time.sleep(1)
    # A prompt lives 30 s (SupplyRequestLedger.Timings); an ignored one pauses the bot 60 s and the
    # rescue then counts no chest food. Hold 15 s to show no rescue while it waits, then answer.
    print("  -- 15 s with the prompt pending (expect no Emergency rescue) --")
    time.sleep(15)
    show(m)
    req = [re.search(r"OPENED id=([0-9a-f-]{36})", l) for l in lines(m)]
    req = [r.group(1) for r in req if r]
    if req:
        m2 = mark()
        chat(f"/frens supply answer {req[-1]} once", 3)
        wait_watch(30, m2, r"Mutual aid: %s took" % BOT)
        print("  -- after answering once --")
        show(m2)
    else:
        print("  no OPENED request seen")
    print(" ", state())

elif stage == "b":
    owner_home()
    chat(f"/clear {BOT}")
    chest(12)
    chat(f"/tp @s {BX + 250}.5 120 {BZ}.5", 4)
    chat("/gamemode spectator")
    m = mark()
    starve()
    wait_watch(75, m)
    show(m)
    print(" ", state())
    chat("/gamemode creative")
    owner_home()

elif stage == "c":
    chat(f"/clear {BOT}")
    chat(f"/effect give {BOT} minecraft:saturation 5 20")
    chat(f"/fill {HX - 12} 64 {HZ - 3} {HX + 3} 64 {HZ + 3} minecraft:stone")
    chat(f"/fill {HX - 12} 65 {HZ - 3} {HX + 3} 69 {HZ + 3} minecraft:air")
    chat(f"/setblock {HX} 65 {HZ} minecraft:campfire[lit=true]")
    chat(f"/tp @s {HX - 6}.5 65 {HZ + 6}.5", 3)
    for dist in (4, 8):
        chat(f"/setblock {HX} {HY} {HZ} minecraft:beehive[facing=west,honey_level=5]")
        chat(f"/clear {BOT}")
        chat(f"/give {BOT} minecraft:glass_bottle 4")
        chat(f"/tp {BOT} {HX - dist}.5 65 {HZ}.5 -90 0", 3)
        m = mark()
        chat(f"/bot skill honey_collect {BOT}", 2)
        wait_watch(40, m, r"Harvested honey|honey_collect skip|SKILL.*honey")
        time.sleep(4)
        print(f"  -- from {dist} blocks --")
        show(m, WATCH + r"|honey")
        print(" ", state())

elif stage == "cleanup":
    owner_home()
    chat("/bot base remove RescueBase", 3)
    chat("/forceload remove all")
    chat(f"/bot despawn {BOT}", 4)
    chat("/gamerule doDaylightCycle true")
