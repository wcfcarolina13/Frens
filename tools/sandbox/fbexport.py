#!/usr/bin/env python3
"""Render clips from a Flashback replay in the mcp-sandbox, unattended. Needs the MCP fork's window_shot, raw_mouse,
raw_key, raw_text and flashback_save_path (fork 5013f8c, 3c548bd) and Flashback 0.39.10. Take the sandbox lock first.

  fbexport.py open "<replay search text>"        title screen -> Open Replays -> search -> Open Replay, closes the Frens
                                                 restore screen and hides the recording player (/hide RotiWokeman)
  fbexport.py clip OUT.mp4 START END [X Y Z YAW PITCH | spectate:BOT]
                                                 camera (static /tp, or first person on a bot), then File > Export Video
                                                 with Start/end tick START END, the save dialog answered with OUT.mp4,
                                                 waits until the file stops growing

Replay ticks are 20 per second of replay time. The replay starts when "Start Recording" was clicked, so replay time =
log wall time minus that moment (find it from a known event: a block change the camera can see).
Export settings (1080x1350, 30 fps, No GUI, Record Audio, stereo) are set once in the dialog and Flashback keeps them.
Coordinates below are window pixels at the sandbox's 1708x960 framebuffer; read them off `ft.py wshot` if the window size
changes. Flashback's editor is Dear ImGui: screen_buttons and click_px do not reach it, raw_mouse does.
Learned 2026-10-18: a static camera inside a tree canopy sees only leaves; a Frens HUD hint shows in the editor but not
in a No-GUI export; nametags are off in Visuals (Render Nametags); Start Export without a preset opens a native macOS
save dialog that nothing can answer.
"""
import json, os, subprocess, sys, time, urllib.request

API = "http://127.0.0.1:9876/api/cmd"
# window pixels (1708x960)
FILE_MENU, EXPORT_VIDEO = (46, 23), (102, 77)
TICK_START, TICK_END = (619, 202), (882, 202)      # Start/end tick fields in the Export to video dialog
START_EXPORT = (662, 921)
DONE_POPUP_CLOSE = (1077, 300)


def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=60).read().decode() or "{}")


def click(p, action="click"):
    return cmd(cmd="raw_mouse", x=p[0], y=p[1], action=action)


def key(k, n=1):
    for _ in range(n):
        cmd(cmd="raw_key", key=k, action="tap")


def field(p, value):
    click(p)
    key("backspace", 10)
    key("delete", 8)
    cmd(cmd="raw_text", text=str(value))
    key("enter")


def open_replay(search):
    cmd(cmd="enter_control_mode")
    cmd(cmd="click_button", label="Open Replays")
    cmd(cmd="type_text", i=0, text=search)
    cmd(cmd="click_button", i=10)          # first row of the filtered list
    cmd(cmd="click_button", label="Open Replay")
    time.sleep(15)
    cmd(cmd="press_key", key="escape")      # Frens' Companions restore screen opens in the replay world
    cmd(cmd="send_chat", text="/hide RotiWokeman")


def clip(out, start, end, cam):
    click(DONE_POPUP_CLOSE)                 # harmless when no popup is up
    if cam and cam[0].startswith("spectate:"):
        cmd(cmd="send_chat", text=f"/spectate {cam[0].split(':', 1)[1]}")
    elif cam:
        cmd(cmd="send_chat", text="/spectate")
        cmd(cmd="send_chat", text="/tp @s " + " ".join(cam))
    time.sleep(1)
    click(FILE_MENU)
    click(EXPORT_VIDEO)
    time.sleep(1)
    field(TICK_START, start)
    field(TICK_END, end)
    r = cmd(cmd="flashback_save_path", path=os.path.abspath(out))
    if not r.get("set"):
        raise SystemExit(f"save path refused: {r}")
    click(START_EXPORT)
    time.sleep(5)
    st = cmd(cmd="flashback_save_path")
    if not str(st.get("lastAnswer", "")).endswith(os.path.abspath(out)):
        raise SystemExit(f"the save dialog was not answered with {out}: {st}")
    prev = -1
    while True:
        time.sleep(10)
        size = os.path.getsize(out) if os.path.exists(out) else 0
        if size and size == prev and subprocess.run(["ffprobe", "-v", "error", out]).returncode == 0:
            break
        prev = size
    print(out, size)


if __name__ == "__main__":
    if sys.argv[1] == "open":
        open_replay(sys.argv[2])
    elif sys.argv[1] == "clip":
        clip(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), sys.argv[5:])
