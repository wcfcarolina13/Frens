#!/usr/bin/env python3
"""1.1.298 sleep checks in the mcp-sandbox (game loaded, control mode on, commands on). Save "Wall Test".

  fix298.py setup                     creative, daylight cycle ON, no mobs, clear weather, time 1000, despawns
                                      every other bot in the world (zzz reaches every bot the player commands, and
                                      an awake bot blocks the vanilla night skip)
  fix298.py pad <x> <z>               a 41x41 stone pad at y 199 with air above (no natural bed or mob near)
  fix298.py spawn <x> <z> <b1> <b2>   spawn two fresh bots on the pad, clear them, follow the player
  fix298.py a <x> <z> <b1> <b2>       co-sleep: three beds, the player lies down (keybind key.use), bots co-sleep,
                                      watch until dawn and after
  fix298.py b <x> <z> <b1> <b2>       zzz with beds, the player awake and teleported 15 blocks away, 35 s watch,
                                      /bot follow <b1>, then /time set 1000 wakes <b2>
  fix298.py c <x> <z> <b1> <b2>       zzz with no bed: logoff, 40 s night watch, the player sleeps alone, dawn return,
                                      inventory compared with the capture before the logoff
  fix298.py d <x> <z> <b1> <b2>       the same with the setting off (config edited while the game was closed)
  fix298.py own <x> <z> <b1> <b2>     each bot carries one red_bed, no bed on the pad, zzz: it places its own bed and sleeps
                                      in it (the 1.1.298 head-target fix; a placed bed used to be "obstructed")
  fix298.py errors                    frens ERROR/Exception lines in this launch's log
While the player is in bed NO control-mode input is sent (escape on the sleeping chat screen is "Leave Bed").
Flashback: tools/sandbox/rec.py start / stop frens_1_1_298_<topic>.
"""
import json, os, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
MC = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft"
LOG = MC + "/logs/latest.log"
SAVE = MC + "/saves/Wall Test"
INV = SAVE + "/playerdata/frens/inventories"
SIDECAR = MC + "/config/frens/sleep_logoff_state.json"
PLAYER = "RotiWokeman"
Y = 200


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=60).read().decode()
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


def show(m, pat, n=60):
    for l in since(m, pat)[:n]:
        print("  >", l[:300])


def state(name):
    try:
        return json.loads(cmd(cmd="bot_state", name=name))
    except Exception:  # noqa: BLE001
        return {}


def tod():
    try:
        d = json.loads(cmd(cmd="players"))
        return d.get("timeOfDay", d.get("time"))
    except Exception:  # noqa: BLE001
        return None


def listall():
    """Every player on the server, from /list (sends chat: never while the player is in bed)."""
    m = mark()
    chat("/list", 2)
    got = since(m, r"players online:")
    return [n.strip() for n in got[-1].split("online:", 1)[1].split(",")] if got else []


def is_online(name):
    """No input: bot_state answers with a position only for a player that is in the world."""
    return bool(state(name).get("pos"))


def online(names=None):
    names = names or []
    return [n for n in names if is_online(n)]


def fill(c, tries=6):
    for _ in range(tries):
        m = mark()
        chat(c, 1.5)
        if not since(m, r"That position is not loaded"):
            return True
        time.sleep(3)
    print("  FILL FAILED (not loaded):", c)
    return False


def brief(s):
    p = s.get("pos")
    return {"pos": p, "sleeping": s.get("sleeping", s.get("isSleeping"))}


def inv(name):
    s = state(name)
    items = s.get("inventory") or s.get("items") or []
    out = []
    for it in items:
        if isinstance(it, dict):
            out.append((it.get("slot"), it.get("item") or it.get("id"), it.get("count")))
        else:
            out.append(it)
    return sorted(out, key=str)


def nbt_sizes():
    try:
        return {f: os.path.getsize(os.path.join(INV, f)) for f in sorted(os.listdir(INV))}
    except OSError as e:
        return {"err": str(e)}


def watch(names, secs, step=2.0, quiet_input=True):
    """Poll positions only (no control-mode input). Returns {name: [pos,...]}."""
    hist = {n: [] for n in names}
    t0 = time.time()
    while time.time() - t0 < secs:
        row = []
        for n in names:
            s = state(n)
            hist[n].append(s.get("pos"))
            row.append(f"{n}={brief(s)}")
        print(f"  t+{time.time()-t0:5.1f}s tod={tod()} " + " ".join(row))
        time.sleep(step)
    return hist


def drift(posl):
    ps = [p for p in posl if isinstance(p, (list, dict))]
    if len(ps) < 2:
        return None
    def xyz(p):
        return (p["x"], p["y"], p["z"]) if isinstance(p, dict) else tuple(p[:3])
    a = xyz(ps[0])
    return max(max(abs(xyz(p)[i] - a[i]) for i in range(3)) for p in ps)


def place_bed(x, y, z, color="red"):
    """Foot at x,y,z, head at x,y,z+1 (facing south)."""
    fill(f"/setblock {x} {y} {z} {color}_bed[facing=south,part=foot]")
    fill(f"/setblock {x} {y} {z+1} {color}_bed[facing=south,part=head]")


def player_sleep(bx, by, bz):
    """Stand north of the foot facing it and press use. After this, no control input until dawn."""
    chat("/clear @p", 1)
    chat(f"/tp @p {bx}.5 {by} {bz-1}.5", 2)
    print("  look:", cmd(cmd="look", yaw=0, pitch=55)[:120])  # /tp facing does not turn the camera in control mode
    time.sleep(1)
    print("  use:", cmd(cmd="keybind", id="key.use")[:200])


def counts(m, names):
    c = {}
    for n in names:
        c[n] = {
            "co_sleep_going": len(since(m, rf"Co-sleep: {n} going to sleep")),
            "trySleep_accepted": len(since(m, rf"trySleep {n} at bed .*: accepted")),
            "trySleep_rejected": len(since(m, rf"trySleep {n} at bed .*: rejected")),
            "follow_plan": len(since(m, rf"Follow path planning start.*bot={n}")),
            "decision": [l.split("decision ")[-1] for l in since(m, rf"\[zzz\] {n} failed to sleep, decision")],
            "logging_off": len(since(m, rf"\[zzz\] {n} logging off at")),
        }
    return c


def follow_times(m, n):
    return [l[:10] for l in since(m, rf"Follow path planning start.*bot={n}")]


st = sys.argv[1] if len(sys.argv) > 1 else ""
args = sys.argv[2:]
if st == "setup":
    cmd(cmd="enter_control_mode")
    cmd(cmd="press_key", key="escape")
    for t in ("/gamemode creative", "/gamerule advance_time true", "/gamerule spawn_mobs false",
              "/weather clear", "/time set 1000"):
        chat(t, 1)
    others = [n for n in listall() if n and n != PLAYER]
    print("other players/bots online:", others)
    for n in others:
        chat(f"/bot despawn {n}", 3)
    print("after despawn:", listall())
elif st == "pad":
    x, z = int(args[0]), int(args[1])
    chat(f"/tp @p {x} {Y+3} {z}", 10)
    fill(f"/fill {x-20} {Y-1} {z-20} {x+20} {Y-1} {z+20} stone")
    fill(f"/fill {x-20} {Y} {z-20} {x+20} {Y+15} {z+20} air")
    chat(f"/tp @p {x} {Y} {z}", 3)
    print("player:", state(PLAYER).get("pos"))
elif st == "spawn":
    x, z, b1, b2 = int(args[0]), int(args[1]), args[2], args[3]
    chat(f"/tp @p {x} {Y} {z}", 2)
    for b in (b1, b2):
        chat(f"/bot spawn {b} admin", 10)
    for b in (b1, b2):
        chat(f"/clear {b}", 1)
        chat(f"/bot follow {b}", 2)
    print({b: brief(state(b)) for b in (b1, b2)})
elif st == "a":
    x, z, b1, b2 = int(args[0]), int(args[1]), args[2], args[3]
    chat(f"/tp @p {x} {Y} {z}", 4)
    place_bed(x, Y, z + 4, "red")          # the player's bed
    place_bed(x + 5, Y, z + 4, "blue")     # bot beds
    place_bed(x - 5, Y, z + 4, "green")
    chat("/time set 13000", 3)
    print("bots before:", {b: brief(state(b)) for b in (b1, b2)})
    m = mark()
    player_sleep(x, Y, z + 4)
    hist = watch([PLAYER, b1, b2], 70, 2)
    show(m, rf"Co-sleep|trySleep|tryUseBed|sleep: nearbyBeds|zzz|{b1}.*(woke|wake)|{b2}.*(woke|wake)|Placed bed|couldn't|ERROR|Exception")
    print("COUNTS", json.dumps(counts(m, [b1, b2])))
    print("follow plan times", {b: follow_times(m, b)[:12] for b in (b1, b2)})
elif st == "b":
    x, z, b1, b2 = int(args[0]), int(args[1]), args[2], args[3]
    chat(f"/tp @p {x} {Y} {z}", 4)
    place_bed(x + 5, Y, z + 4, "blue")
    place_bed(x - 5, Y, z + 4, "green")
    chat("/time set 13000", 3)
    m = mark()
    chat("zzz", 2)
    t0 = time.time()
    while time.time() - t0 < 40 and not all(len(since(m, rf"trySleep {b} at bed")) for b in (b1, b2)):
        time.sleep(2)
    time.sleep(3)
    print("in bed:", {b: brief(state(b)) for b in (b1, b2)})
    mb = mark()
    for step in (5, 10, 15):  # walk-like hops away along -z
        chat(f"/tp @p {x} {Y} {z-step}", 2)
    print("player:", state(PLAYER).get("pos"))
    hist = watch([b1, b2], 35, 2.5)
    print("drift in bed (max abs delta):", {b: drift(hist[b]) for b in (b1, b2)})
    print("follow plans while asleep:", {b: len(since(mb, rf"Follow path planning start.*bot={b}")) for b in (b1, b2)})
    mf = mark()
    chat(f"/bot follow {b1}", 2)
    h2 = watch([b1, b2], 14, 2)
    print("after /bot follow", b1, {b: brief(state(b)) for b in (b1, b2)})
    print("follow plans after /bot follow:", {b: len(since(mf, rf"Follow path planning start.*bot={b}")) for b in (b1, b2)})
    md = mark()
    chat("/time set 1000", 2)
    h3 = watch([b1, b2], 14, 2)
    print("after day:", {b: brief(state(b)) for b in (b1, b2)})
    print("follow plans after day:", {b: len(since(md, rf"Follow path planning start.*bot={b}")) for b in (b1, b2)})
    show(m, rf"Co-sleep|trySleep|tryUseBed|sleep: nearbyBeds|zzz|Placed bed|couldn't|ERROR|Exception")
    print("COUNTS", json.dumps(counts(m, [b1, b2])))
elif st in ("c", "d"):
    x, z, b1, b2 = int(args[0]), int(args[1]), args[2], args[3]
    chat(f"/tp @p {x} {Y} {z}", 3)
    chat(f"/fill {x-20} {Y} {z-20} {x+20} {Y+3} {z+20} air", 2)  # no bed anywhere on the pad
    chat("/clear @p", 1)
    for b in (b1, b2):
        chat(f"/clear {b}", 1)
        chat(f"/give {b} stone_pickaxe 1", 0.5)
        chat(f"/give {b} cobblestone 23", 0.5)
        chat(f"/give {b} bread 5", 0.5)
        chat(f"/give {b} torch 9", 0.5)
    chat("/time set 13000", 4)
    before = {b: inv(b) for b in (b1, b2)}
    print("inventory before:", json.dumps(before))
    print("nbt before:", nbt_sizes())
    m = mark()
    chat("zzz", 2)
    t0 = time.time()
    while time.time() - t0 < 45 and not all(len(since(m, rf"\[zzz\] {b} failed to sleep, decision")) for b in (b1, b2)):
        time.sleep(1)
    print(f"decided after {time.time()-t0:.1f}s")
    time.sleep(3)
    print("online now:", online([b1, b2]))
    print("sidecar:", open(SIDECAR).read()[:800] if os.path.exists(SIDECAR) else None)
    print("nbt after logoff:", nbt_sizes())
    print("night watch 40 s (player awake):")
    t1 = time.time()
    while time.time() - t1 < 40:
        print(f"  t+{time.time()-t1:4.1f}s tod={tod()} online={online([b1, b2])}")
        time.sleep(5)
    show(m, rf"zzz|Co-sleep|trySleep|sleep: nearbyBeds|couldn't|logging off|CHAT\].*({b1}|{b2})|ERROR|Exception")
    if st == "c":
        place_bed(x, Y, z + 4, "red")
        mp = mark()
        player_sleep(x, Y, z + 4)
        t2 = time.time()
        back = False
        while time.time() - t2 < 60:
            on = online([b1, b2])
            print(f"  t+{time.time()-t2:4.1f}s tod={tod()} online={on}")
            if b1 in on and b2 in on:
                back = True
                break
            time.sleep(3)
        time.sleep(4)
        after = {b: inv(b) for b in (b1, b2)}
        print("back:", back, "inventory after:", json.dumps(after))
        print("inventory equal:", {b: before[b] == after[b] for b in (b1, b2)})
        print("nbt after return:", nbt_sizes())
        print("sidecar after:", open(SIDECAR).read()[:400] if os.path.exists(SIDECAR) else None)
        show(mp, rf"zzz|Restoring ({b1}|{b2})|Spawned|respawn|joined the game|ERROR|Exception")
    else:
        print("still online:", {b: is_online(b) for b in (b1, b2)})
    print("COUNTS", json.dumps(counts(m, [b1, b2])))
elif st == "own":
    # change 3: each bot carries one bed, no bed on the pad; it must place its own and the bed must not be "obstructed"
    x, z, b1, b2 = int(args[0]), int(args[1]), args[2], args[3]
    chat(f"/tp @p {x} {Y} {z}", 3)
    chat(f"/fill {x-20} {Y} {z-20} {x+20} {Y+3} {z+20} air", 2)
    for b in (b1, b2):
        chat(f"/clear {b}", 1)
        chat(f"/give {b} red_bed 1", 1)
    chat("/time set 13000", 4)
    m = mark()
    chat("zzz", 2)
    t0 = time.time()
    while time.time() - t0 < 45 and not all(len(since(m, rf"trySleep {b} at bed")) or len(since(m, rf"\[zzz\] {b} failed")) for b in (b1, b2)):
        time.sleep(1)
    time.sleep(2)
    print("bots:", {b: brief(state(b)) for b in (b1, b2)})
    show(m, rf"sleep: nearbyBeds|No free bed|Placed bed|placeBedNearby|tryUseBed|trySleep|obstructed|zzz\]|couldn't|ERROR|Exception")
    print("COUNTS", json.dumps(counts(m, [b1, b2])))
elif st == "errors":
    for i, l in enumerate(lines()):
        if re.search(r"ERROR|Exception", l) and re.search(r"wcfcarolina13|frens|zzz|[Ss]leep", l):
            print(i, l[:300])
elif st == "state":
    print(cmd(cmd="bot_state", name=args[0])[:3000])
else:
    print(__doc__)
