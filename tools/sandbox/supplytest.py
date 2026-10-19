#!/usr/bin/env python3
"""Supply prompts (1.1.221, field Phase 6s) in a commands-on sandbox save with the game loaded.
Jake stands at (X, Y, Z) with a chest beside him inside base SupBase; the owner stands 4 blocks away.

  supplytest.py setup                 base, chest, Jake emptied
  supplytest.py ask <answer> <items>  fill the chest (e.g. stone_axe:2), empty Jake, run `/bot skill woodcut Jake`,
                                      answer the first prompt with once|always|no|none, print the [supply] lines
  supplytest.py food <bread> [answer] [secs]  chest with N bread, `/bot set hunger 10 Jake`, answer (once), wait, print
  supplytest.py revoke                `/frens supply revoke all`
Each stage prints the log lines it proves with and Jake's inventory.
"""
import json, os, re, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
X, Y, Z = int(os.environ.get("SX", 30)), 74, -112   # Jake's cell; the chest is at X+1 (SX env moves it)
WATCH = r"\[supply\]|Mutual aid: Jake|CHAT\] (Jake|Can't|I need|No axe)|Woodcut axe|NO_ROOM|Can't keep up|Dropped"


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=30).read().decode()
    except Exception as e:  # noqa: BLE001
        return f"ERR {e}"


def chat(text, wait=1.5):
    cmd(cmd="send_chat", text=text)
    cmd(cmd="press_key", key="escape")
    time.sleep(wait)


def mark():
    return len(open(LOG, errors="replace").read().splitlines())


def lines(start, pattern=WATCH):
    return [l[:240] for l in open(LOG, errors="replace").read().splitlines()[start:] if re.search(pattern, l)]


def wait_for(start, pattern, secs):
    end = time.time() + secs
    while time.time() < end:
        hit = lines(start, pattern)
        if hit:
            return hit
        time.sleep(1)
    return []


def chest(items):
    chat(f"/setblock {X + 1} {Y} {Z} air")
    entries = []
    for i, spec in enumerate(items):
        item, n = spec.split(":")
        entries.append('{slot:%db,id:"minecraft:%s",count:%s}' % (i, item, n))
    chat(f"/setblock {X + 1} {Y} {Z} chest{{Items:[{','.join(entries)}]}}")


def inv():
    s = cmd(cmd="bot_state", name="Jake")
    m = re.search(r'"inventory":(\[.*?\])', s)
    return m.group(1) if m else s[:200]


def answer(start, token):
    hit = wait_for(start, r"OPENED id=", 40)
    if not hit:
        print("  no OPENED request seen")
        return
    rid = re.search(r"OPENED id=([0-9a-f-]{36})", hit[-1]).group(1)
    if token != "none":
        chat(f"/frens supply answer {rid} {token}", 2)


stage = sys.argv[1]
if stage == "setup":
    chat(f"/fill {X - 3} {Y} {Z - 3} {X + 3} {Y + 3} {Z + 3} air")
    chat(f"/fill {X - 3} {Y - 1} {Z - 3} {X + 3} {Y - 1} {Z + 3} stone")
    chat(f"/tp @s {X - 3} {Y} {Z}")
    chat("/bot base set SupBase", 3)
    chat(f"/tp Jake {X}.5 {Y} {Z}.5")
    chat("/clear Jake")
    chest([])
elif stage == "ask":
    token, items = sys.argv[2], sys.argv[3:]
    chat(f"/tp Jake {X}.5 {Y} {Z}.5")
    chat("/clear Jake")
    chest(items)
    m = mark()
    chat("/bot skill woodcut Jake")
    answer(m, token)
    time.sleep(15)
    chat("/bot stop Jake")
    for l in lines(m):
        print("  " + l)
    print("  inv", inv())
elif stage == "food":
    chat(f"/tp Jake {X}.5 {Y} {Z}.5")
    chat("/clear Jake")
    if os.environ.get("JUNK"):
        chat("/give Jake cobblestone 2304", 2)   # 36 stacks: a full bag
    chest([f"bread:{sys.argv[2]}"])
    m = mark()
    chat("/bot set hunger 10 Jake")
    answer(m, sys.argv[3] if len(sys.argv) > 3 else "once")
    time.sleep(int(sys.argv[4]) if len(sys.argv) > 4 else 15)
    for l in lines(m):
        print("  " + l)
    print("  inv", inv())
elif stage == "revoke":
    m = mark()
    chat("/frens supply revoke all", 3)
    for l in lines(m, r"\[supply\]|CHAT\]"):
        print("  " + l)
