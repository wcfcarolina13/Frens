# sandbox launch/quit helpers: source this file
# Lock: take /Users/roti/AI-Player-checkpoint/.superpowers/SANDBOX.lock before using the sandbox (sb_lock), drop it when done (sb_unlock).
S="/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox"; L="$S/minecraft/logs/latest.log"; A=http://127.0.0.1:9876/api/cmd
SB_LOCK=/Users/roti/AI-Player-checkpoint/.superpowers/SANDBOX.lock
# Patterns are split so pgrep/pkill never match this shell's own command line (a wait loop died with exit 144 that way).
SB_JAVA="java.*mcp-sand""box/nat""ives"; ANY_JAVA="java.*PrismLauncher/instances/.*/nat""ives"
sb_lock() { [ -e "$SB_LOCK" ] && { echo "LOCKED:"; cat "$SB_LOCK"; return 1; }; printf 'session=%s\nsince=%s\n' "${1:-unknown}" "$(date '+%F %T')" > "$SB_LOCK"; echo locked; }
sb_unlock() { rm -f "$SB_LOCK"; echo unlocked; }
sb_up() { pgrep -f "$ANY_JAVA" >/dev/null && { echo "ABORT: a game is running (launch guard: two games share one GPU; the deploy check is per instance)"; return 1; }
  sb_mute || return 1
  local t0; t0=$(date +%s)
  ("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" --launch mcp-sandbox --world "$1" >/dev/null 2>&1 &)
  for i in $(seq 1 60); do sleep 5; H=$(curl -s -m 5 -X POST $A -d '{"cmd":"health"}' 2>/dev/null)
    # the log must be this launch's (mtime after t0), or an old 'joined the game' line passes the check
    if [ -f "$L" ] && [ "$(stat -f %m "$L")" -ge "$t0" ] && echo "$H" | grep -q '"server":"running"' && grep -q 'RotiWokeman joined the game' "$L"; then
      echo up; sleep 8; curl -s -m 10 -X POST $A -d '{"cmd":"enter_control_mode"}' >/dev/null; curl -s -m 10 -X POST $A -d '{"cmd":"press_key","key":"escape"}' >/dev/null; return 0; fi; done; echo "timeout"; return 1; }
sb_down() { curl -s -m 20 -X POST $A -d '{"cmd":"save_and_quit"}' >/dev/null
  for i in $(seq 1 12); do sleep 3; curl -s -m 3 -X POST $A -d '{"cmd":"health"}' 2>/dev/null | grep -q '"server":"running"' || break; done
  sleep 3; pkill -TERM -f "$SB_JAVA"; sleep 6; pgrep -f "$SB_JAVA" >/dev/null && echo still-running || echo closed; }
# sb_deploy <jar>: replace frens-*.jar in the sandbox mods/ (per-instance pre-deploy check: only the sandbox must be closed)
sb_deploy() { [ -f "$1" ] || { echo "no jar: $1"; return 1; }; pgrep -f "$SB_JAVA" >/dev/null && { echo "ABORT: the sandbox is running"; return 1; }
  rm -f "$S/minecraft/mods"/frens-*.jar && cp "$1" "$S/minecraft/mods/" && echo "deployed $(basename "$1") $(md5 -q "$S/minecraft/mods/$(basename "$1")")"; }
# sb_mute: the sandbox runs muted (Bradley 2026-10-19). Sets soundCategory_master:0.0 in options.txt, only while the sandbox is closed
# (the game rewrites options.txt on quit). sb_up calls it. Unmute for a check with the MCP fork's `sound volume=1`.
sb_mute() { local O="$S/minecraft/options.txt"; [ -f "$O" ] || { echo "no options.txt"; return 1; }
  pgrep -f "$SB_JAVA" >/dev/null && { echo "sandbox running: use the MCP fork 'sound' command"; return 0; }
  grep -q '^soundCategory_master:0.0$' "$O" && return 0
  sed -i '' 's/^soundCategory_master:.*/soundCategory_master:0.0/' "$O" && echo "muted (master 0)"; }
# sb_cfg <file>: put a saved settings.json5 in place for a check (e.g. settings.json5.bak-pass3-bloat for the 32 KB save check);
# the current file is kept as settings.json5.sb-keep. sb_cfg_restore puts it back. Both only while the sandbox is closed.
sb_cfg() { local C="$S/minecraft/config"; [ -f "$C/$1" ] || [ -f "$1" ] || { echo "no config: $1"; return 1; }
  pgrep -f "$SB_JAVA" >/dev/null && { echo "ABORT: the sandbox is running"; return 1; }
  [ -e "$C/settings.json5.sb-keep" ] && { echo "ABORT: settings.json5.sb-keep exists, run sb_cfg_restore first"; return 1; }
  cp "$C/settings.json5" "$C/settings.json5.sb-keep" && { [ -f "$C/$1" ] && cp "$C/$1" "$C/settings.json5" || cp "$1" "$C/settings.json5"; } && echo "config $(basename "$1") in place ($(wc -c < "$C/settings.json5") bytes)"; }
sb_cfg_restore() { local C="$S/minecraft/config"; [ -f "$C/settings.json5.sb-keep" ] || { echo "nothing to restore"; return 1; }
  pgrep -f "$SB_JAVA" >/dev/null && { echo "ABORT: the sandbox is running"; return 1; }
  mv "$C/settings.json5.sb-keep" "$C/settings.json5" && echo restored; }
# sb_flood [lines] [limit]: the most repeated log lines (timestamps and numbers masked) among the last N lines (default 20000);
# exits 2 when the top line repeats more than limit (default 2000). Finding 35 (wool skill, 1.6M lines in 13 min) was only seen by file size.
sb_flood() { local n="${1:-20000}" lim="${2:-2000}"; [ -f "$L" ] || { echo "no log"; return 1; }
  echo "log $(( $(stat -f %z "$L") / 1048576 )) MB"
  tail -n "$n" "$L" | sed -E 's/^\[[^]]*\] *//; s/[0-9]+(\.[0-9]+)?/N/g' | sort | uniq -c | sort -rn | head -5 | tee /dev/stderr | awk -v lim="$lim" 'NR==1{exit ($1>lim)?2:0}'; }
