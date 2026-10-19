"""Flashback recording from the pause menu (game loaded, control mode on).

  rec.py start               pause menu -> "Start Recording" -> back to the game
  rec.py stop <replay-name>  pause menu -> "Finish Recording" -> name the replay -> "Save Replay"
  rec.py status              the pause menu's Flashback buttons, then back to the game

Name replays frens_<version>_<phase>_<short-topic> (Flashback adds .zip), e.g. frens_1_1_271_6t_full_bag_food.
On SaveReplayScreen the name field is widget index 2 and "Save Replay" is index 6 (a label lookup hits an inactive title first).
"""
import json, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return urllib.request.urlopen(req, timeout=30).read().decode()
    except Exception as e:  # noqa: BLE001
        return f"ERR {e}"


def labels():
    try:
        return [w.get("label", "") for w in json.loads(cmd(cmd="screen_buttons")).get("buttons", [])]
    except Exception:  # noqa: BLE001
        return []


def menu():
    cmd(cmd="press_key", key="escape")   # close chat or any screen first
    time.sleep(0.5)
    print(cmd(cmd="pause_menu")[:120])
    time.sleep(1)


a = sys.argv[1]
if a == "start":
    menu()
    print(cmd(cmd="click_button", label="Start Recording")[:200])
    time.sleep(1)
    cmd(cmd="press_key", key="escape")
elif a == "stop":
    name = sys.argv[2]
    menu()
    print(cmd(cmd="click_button", label="Finish Recording")[:200])
    time.sleep(3)
    print(cmd(cmd="type_text", text=name, i=2, replace="true")[:200])
    time.sleep(0.5)
    print(cmd(cmd="click_button", i=6)[:200])
    time.sleep(3)
    print(cmd(cmd="screen_info")[:200])
    cmd(cmd="press_key", key="escape")
elif a == "status":
    menu()
    print([l for l in labels() if "Record" in l or "Replay" in l])
    cmd(cmd="press_key", key="escape")
