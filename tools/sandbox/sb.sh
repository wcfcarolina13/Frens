# sandbox launch/quit helpers: source this file
S="/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox"; L="$S/minecraft/logs/latest.log"; A=http://127.0.0.1:9876/api/cmd
sb_up() { pgrep -f "PrismLauncher/instances/.*/natives" >/dev/null && { echo "ABORT: a game is running"; return 1; }
  ("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" --launch mcp-sandbox --world "$1" >/dev/null 2>&1 &)
  for i in $(seq 1 60); do sleep 5; H=$(curl -s -m 5 -X POST $A -d '{"cmd":"health"}' 2>/dev/null)
    if echo "$H" | grep -q '"server":"running"' && grep -q 'RotiWokeman joined the game' "$L" 2>/dev/null; then echo up; sleep 8; curl -s -m 10 -X POST $A -d '{"cmd":"enter_control_mode"}' >/dev/null; curl -s -m 10 -X POST $A -d '{"cmd":"press_key","key":"escape"}' >/dev/null; return 0; fi; done; echo "timeout"; return 1; }
sb_down() { curl -s -m 20 -X POST $A -d '{"cmd":"save_and_quit"}' >/dev/null; sleep 20; pkill -TERM -f "instances/mcp-sandbox/natives"; sleep 6; pgrep -f "PrismLauncher/instances/.*/natives" >/dev/null && echo still-running || echo closed; }
