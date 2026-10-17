#!/usr/bin/env python3
"""/bot go_to finder check in the mcp-sandbox (a copy of 'New World-commands', commands on, game loaded).

Global unified, Wally overridden to baritone, Smokey on the global choice. Each bot gets
/bot go_to to a spot 20 blocks away. Pass: the [PathFinder] line right after Wally's go_to says
mode=baritone and the one after Smokey's says mode=unified. Before 1.1.264 both said unified.
"""
import json, re, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
EXPECT = {"Wally": "baritone", "Smokey": "unified"}
OVERRIDES = {"Wally": "baritone", "Smokey": "default"}


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=60).read().decode()
    except Exception as e:  # noqa: BLE001
        return f"ERR {e}"


def chat(text, wait=2):
    cmd(cmd="send_chat", text=text)
    time.sleep(wait)


def log_lines():
    return open(LOG, errors="replace").read().splitlines()


cmd(cmd="enter_control_mode")
cmd(cmd="press_key", key="escape")
time.sleep(2)
chat("/gamemode creative")
chat("/time set 1000")
for rule in ("advance_time false", "doDaylightCycle false", "spawn_mobs false", "doMobSpawning false"):
    chat(f"/gamerule {rule}", 1)
for bot in EXPECT:
    chat(f"/bot spawn {bot} admin", 8)
chat("/bot config pathfinder unified")
for bot, word in OVERRIDES.items():
    chat(f"/bot config pathfinder {word} {bot}")

failures = 0
for bot, want in EXPECT.items():
    chat(f"/bot stop {bot}", 1)
    chat(f"/execute at @s run tp {bot} ~2 ~ ~", 2)
    time.sleep(4)  # past the 60-tick stop grace
    mark = len(log_lines())
    chat(f"/execute at @s run bot go_to {bot} ~20 ~ ~ false", 6)
    new = log_lines()[mark:]
    got = None
    for line in new:
        if "[PathFinder] mode=" in line or "Path output" in line:
            print(line[:220])
        m = re.search(r"\[PathFinder\] mode=(\w+)", line)
        if m and got is None:
            got = m.group(1)
    ok = got == want
    failures += 0 if ok else 1
    print(f"{bot}: want {want} got {got or 'none'} {'PASS' if ok else 'FAIL'}", flush=True)
    chat(f"/bot stop {bot}", 1)

for bot in EXPECT:
    chat(f"/bot config pathfinder default {bot}", 1)
chat("/bot config pathfinder unified")
print("ALL PASS" if failures == 0 else f"{failures} FAIL")
