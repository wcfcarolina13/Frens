"""Field-test helper for the mcp-sandbox (game loaded). Marks and evidence go to $FT_DIR (default: the system temp dir).

  ft.py chat "/cmd" ...          send chat lines or commands, 2 s apart
  ft.py cmd <name> k=v ...       raw MCP command (screen_info, screen_buttons, click_button label=..., bot_state name=...)
  ft.py mark <tag>               remember the current log length
  ft.py grep <tag> <regex> [ev]  log lines since the mark that match; with ev, also append them to ev_<ev>.txt
  ft.py shot <name> [bot]        screenshot (or bot_view of <bot>) to $FT_DIR/<name>.png, resized to 1200 px wide
  ft.py clickr <x> <y>           click at pixel x,y of the last 1200-wide shot (custom-drawn screens screen_buttons can't see)
  ft.py scroll <x> <y> <amount>  mouse wheel at shot pixels (needs the fork's scroll command)
Stream panel (~/dev/frens-stream/director.py reads these from $FRENS_STREAM_STATE):
  ft.py item <id> <title...>     current checklist item        ft.py say <text...>         one-line narration
  ft.py verdict PASS|FAIL|PARTIAL|SKIP|NOTE <text...>          ft.py idle on|off|auto      force or release the idle card
  ft.py segment gui|timed        GUI segments pause the world  ft.py pip <bot>|off|auto    lock the PiP / hand it back
  ft.py cam <bot>|self [mode]    spectate a bot (moves the commander!), or back to [mode] (default creative)
  ft.py stream-reset             clear the panel for a new run
"""
import json, re, sys, time, urllib.request, os
API = "http://127.0.0.1:9876/api/cmd"
LOG = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/logs/latest.log"
SP = os.environ.get("FT_DIR", __import__("tempfile").gettempdir())
STREAM = os.environ.get("FRENS_STREAM_STATE", os.path.expanduser("~/Library/Caches/frens-stream/state.jsonl"))
def stream(kind, **kw):
    os.makedirs(os.path.dirname(STREAM), exist_ok=True)
    with open(STREAM, "a") as f: f.write(json.dumps(dict(kind=kind, **kw)) + "\n")
def cmd(**kw):
    req = urllib.request.Request(API, data=json.dumps({k: str(v) for k, v in kw.items()}).encode(), headers={"Content-Type": "application/json"})
    try: return urllib.request.urlopen(req, timeout=40).read().decode()
    except Exception as e: return f"ERR {e}"
def lines(): return open(LOG, errors="replace").read().splitlines()
a = sys.argv[1]
def warn_if_paused():   # an open screen pauses the integrated server: bots freeze but workers keep logging failures
    info = cmd(cmd="screen_info")
    if '"screen":null' not in info.replace(" ", ""):
        print("WARNING: a screen is open, the server is paused:", info[:160], file=sys.stderr)
if a == "chat":
    warn_if_paused()
    for t in sys.argv[2:]:
        print(cmd(cmd="send_chat", text=t)[:200]); time.sleep(2)
elif a == "cmd":
    kw = dict(x.split("=", 1) for x in sys.argv[3:]); print(cmd(cmd=sys.argv[2], **kw)[:4000])
elif a == "mark":   # mark <name>: remember current log line count
    open(f"{SP}/mark_{sys.argv[2]}", "w").write(str(len(lines())))
elif a == "grep":   # grep <mark> <regex> [outfile]: lines since mark matching regex
    warn_if_paused()
    start = int(open(f"{SP}/mark_{sys.argv[2]}").read())
    out = [l[:260] for l in lines()[start:] if re.search(sys.argv[3], l)]
    if len(sys.argv) > 4:
        open(f"{SP}/ev_{sys.argv[4]}.txt", "a").write("\n".join(out) + "\n")
    print("\n".join(out[-80:])); print(f"-- {len(out)} lines")
elif a == "shot":   # shot <name> [bot]: save a 1200-wide PNG and remember the scale for clickr
    import base64, subprocess
    r = cmd(cmd="bot_view", name=sys.argv[3], perspective="back") if len(sys.argv) > 3 else cmd(cmd="screenshot")
    data = r.strip().strip('"')
    if not data.startswith("data:"):
        try: data = json.loads(r).get("result", r)
        except Exception: pass
    if not str(data).startswith("data:"): print(r[:300]); sys.exit(1)
    out = f"{SP}/{sys.argv[2]}.png"
    open(out, "wb").write(base64.b64decode(data.split(",", 1)[1]))
    w = int(subprocess.run(["sips", "-g", "pixelWidth", out], capture_output=True, text=True).stdout.split()[-1])
    subprocess.run(["sips", "-Z", "1200", out], capture_output=True)
    open(f"{SP}/shot_scale", "w").write(str(w / 1200)); print(out, "scale", round(w / 1200, 4))
elif a == "clickr":   # clickr x y in the last shot's pixels -> framebuffer pixels for click_px
    k = float(open(f"{SP}/shot_scale").read())
    print(cmd(cmd="click_px", x=int(float(sys.argv[2]) * k), y=int(float(sys.argv[3]) * k)))
elif a == "scroll":
    k = float(open(f"{SP}/shot_scale").read())
    print(cmd(cmd="scroll", x=int(float(sys.argv[2]) * k), y=int(float(sys.argv[3]) * k), amount=sys.argv[4]))
elif a == "item": stream("item", id=sys.argv[2], title=" ".join(sys.argv[3:]))
elif a == "say": stream("say", text=" ".join(sys.argv[2:]))
elif a == "verdict":
    item = open(STREAM).read().splitlines() if os.path.exists(STREAM) else []
    cur = next((json.loads(l)["id"] for l in reversed(item) if '"kind": "item"' in l), "")
    stream("verdict", result=sys.argv[2].upper(), id=cur, text=" ".join(sys.argv[3:]))
elif a == "idle": stream("idle", value=sys.argv[2])
elif a == "segment": stream("segment", value=sys.argv[2])
elif a == "pip": stream("pip", bot=sys.argv[2])
elif a == "cam":   # spectating moves the player onto the bot: only for items where commander position does not matter
    if sys.argv[2] == "self":
        for t in ("/spectate", f"/gamemode {sys.argv[3] if len(sys.argv) > 3 else 'creative'}"): print(cmd(cmd="send_chat", text=t)[:200]); time.sleep(1)
    else:
        for t in ("/gamemode spectator", f"/spectate {sys.argv[2]}"): print(cmd(cmd="send_chat", text=t)[:200]); time.sleep(1)
elif a == "stream-reset": os.makedirs(os.path.dirname(STREAM), exist_ok=True); open(STREAM, "w").close()
