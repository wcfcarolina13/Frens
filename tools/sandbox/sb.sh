# sandbox launch/quit helpers: source this file
# Lock: take /Users/roti/AI-Player-checkpoint/.superpowers/SANDBOX.lock before using the sandbox (sb_lock), drop it when done (sb_unlock).
S="/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox"; L="$S/minecraft/logs/latest.log"; A=http://127.0.0.1:9876/api/cmd
SB_LOCK=/Users/roti/AI-Player-checkpoint/.superpowers/SANDBOX.lock
# Patterns are split so pgrep/pkill never match this shell's own command line (a wait loop died with exit 144 that way).
SB_JAVA="java.*mcp-sand""box/nat""ives"; ANY_JAVA="java.*PrismLauncher/instances/.*/nat""ives"
sb_lock() { [ -e "$SB_LOCK" ] && { echo "LOCKED:"; cat "$SB_LOCK"; return 1; }; printf 'session=%s\nsince=%s\n' "${1:-unknown}" "$(date '+%F %T')" > "$SB_LOCK"; echo locked; }
sb_unlock() { rm -f "$SB_LOCK"; echo unlocked; }
sb_up() { pgrep -f "$ANY_JAVA" >/dev/null && { echo "ABORT: a game is running"; return 1; }
  local t0; t0=$(date +%s)
  ("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" --launch mcp-sandbox --world "$1" >/dev/null 2>&1 &)
  for i in $(seq 1 60); do sleep 5; H=$(curl -s -m 5 -X POST $A -d '{"cmd":"health"}' 2>/dev/null)
    # the log must be this launch's (mtime after t0), or an old 'joined the game' line passes the check
    if [ -f "$L" ] && [ "$(stat -f %m "$L")" -ge "$t0" ] && echo "$H" | grep -q '"server":"running"' && grep -q 'RotiWokeman joined the game' "$L"; then
      echo up; sleep 8; curl -s -m 10 -X POST $A -d '{"cmd":"enter_control_mode"}' >/dev/null; curl -s -m 10 -X POST $A -d '{"cmd":"press_key","key":"escape"}' >/dev/null; return 0; fi; done; echo "timeout"; return 1; }
sb_down() { curl -s -m 20 -X POST $A -d '{"cmd":"save_and_quit"}' >/dev/null
  for i in $(seq 1 12); do sleep 3; curl -s -m 3 -X POST $A -d '{"cmd":"health"}' 2>/dev/null | grep -q '"server":"running"' || break; done
  sleep 3; pkill -TERM -f "$SB_JAVA"; sleep 6; pgrep -f "$SB_JAVA" >/dev/null && echo still-running || echo closed; }
