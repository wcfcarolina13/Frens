#!/usr/bin/env python3
"""Sunset auto-return finder check in the mcp-sandbox (a copy of 'New World', commands on, game loaded).

Two dusks, each with Wally and Smokey sent 90 blocks from a base:
  round 1  global /bot config pathfinder baritone, no overrides: both segments should say finder=baritone
  round 2  global unified, Wally overridden to baritone: Wally baritone, Smokey unified
The segment pick only runs past 60 blocks (manhattan), so the bots must start far out.
Pass: 'Sunset segment for <bot> finder=<word>' lines match the expectation in each round.
"""
import json, re, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
BOTS = {"Wally": (90, 0), "Smokey": (0, 90)}  # offsets from the base (the player's spot)
ROUNDS = [
    ("baritone", {}, {"Wally": "baritone", "Smokey": "baritone"}),
    ("unified", {"Wally": "baritone"}, {"Wally": "baritone", "Smokey": "unified"}),
]


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


def lines_since(mark):
    return open(LOG, errors="replace").read().splitlines()[mark:]


cmd(cmd="enter_control_mode")
cmd(cmd="press_key", key="escape")
time.sleep(2)
chat("/gamemode creative")
chat("/time set 1000")
BASE_DAY = int(time.time() / 60) % 10000 * 3  # fresh days on every run: auto-return fires once per bot per day
for rule in ("advance_time false", "doDaylightCycle false", "spawn_mobs false", "doMobSpawning false"):
    chat(f"/gamerule {rule}", 1)
chat("/bot base set FinderHome", 3)
for bot in BOTS:
    chat(f"/bot spawn {bot} admin", 8)
    chat(f"/bot auto_return_sunset on {bot}")
    chat(f"/bot auto_return_skip_permission on {bot}")

failures = 0
for rnd, (global_word, overrides, expect) in enumerate(ROUNDS, 1):
    chat(f"/bot config pathfinder {global_word}")
    for bot in BOTS:
        chat(f"/bot config pathfinder {overrides.get(bot, 'default')} {bot}")
    for bot, (x, z) in BOTS.items():
        chat(f"/bot stop {bot}", 1)
        chat(f"/execute at @s run spreadplayers ~{x} ~{z} 1 4 false {bot}", 3)
    time.sleep(5)  # past the 60-tick stop grace
    mark = len(open(LOG, errors="replace").read().splitlines())
    chat(f"/time set {(BASE_DAY + rnd) * 24000 + 12100}")
    time.sleep(25)
    new = lines_since(mark)
    for line in new:
        if ("Auto-return at sunset triggered" in line or "Sunset segment for" in line
                or "[PathFinder] mode=" in line or "timeout=200ms" in line or "unified probe failed" in line):
            print(line[:220])
    for bot, want in expect.items():
        got = {m.group(1) for line in new
               for m in [re.search(rf"Sunset segment for {bot} finder=(\w+)", line)] if m}
        ok = got == {want}
        failures += 0 if ok else 1
        print(f"round {rnd} {bot}: want {want} got {sorted(got) or 'none'} {'PASS' if ok else 'FAIL'}", flush=True)
    for bot in BOTS:
        chat(f"/bot stop {bot}", 1)
for bot in BOTS:
    chat(f"/bot config pathfinder default {bot}", 1)
chat("/bot config pathfinder unified")
print("ALL PASS" if failures == 0 else f"{failures} FAIL")
