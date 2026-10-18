#!/bin/bash
# Frens sandbox smoke: deploy JAR to mcp-sandbox, launch into a world, spawn a bot, scan the log, save and quit.
# Usage: smoke.sh <jar> [world] [extra /commands...]
set -u
JAR="$1"; WORLD="${2:-New World}"; shift; shift 2>/dev/null || true
S="/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox"
LOG="$S/minecraft/logs/latest.log"
API=http://127.0.0.1:9876/api/cmd
cmd() { curl -s -m 20 -X POST "$API" -H 'Content-Type: application/json' -d "$1"; echo; }

# Deploy check covers only the sandbox (Bradley 2026-10-18: per-instance pre-deploy check). Patterns are split so pgrep
# never matches this script's own command line. The launch guard stays on any instance: two games share one GPU.
SB_JAVA="java.*mcp-sand""box/nat""ives"; ANY_JAVA="java.*PrismLauncher/instances/.*/nat""ives"
if pgrep -f "$SB_JAVA" >/dev/null; then echo "ABORT: the sandbox is running"; exit 1; fi
if pgrep -f "$ANY_JAVA" >/dev/null; then echo "ABORT: another instance is running (launch guard)"; exit 1; fi
rm -f "$S/minecraft/mods"/frens-*.jar && cp "$JAR" "$S/minecraft/mods/" || exit 1
echo "deployed $(basename "$JAR")"
"/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" --launch mcp-sandbox --world "$WORLD" >/dev/null 2>&1 &

# wait for the MCP API and an integrated server
for i in $(seq 1 90); do
  sleep 5
  H=$(curl -s -m 5 -X POST "$API" -d '{"cmd":"health"}' 2>/dev/null)
  if echo "$H" | grep -q '"ok"' && grep -q 'joined the game' "$LOG" 2>/dev/null; then break; fi
done
echo "health: $H" | cut -c1-300
sleep 10
cmd '{"cmd":"enter_control_mode"}' >/dev/null
cmd '{"cmd":"send_chat","text":"/bot spawn Smokey admin"}'
sleep 15
for c in "$@"; do cmd "{\"cmd\":\"send_chat\",\"text\":\"$c\"}"; sleep 8; done
cmd '{"cmd":"bot_state","name":"Smokey"}' | cut -c1-400
cmd '{"cmd":"players"}' | cut -c1-400
echo "--- log scan (ERROR/Exception/Mixin) ---"
grep -n -E 'ERROR|Exception|Mixin apply failed|MixinApplyError|NoClassDefFound|NoSuchMethod' "$LOG" | grep -v -E 'Missing sound|UBO' | sed 's/[0-9]\{2,\}/N/g' | sort | uniq -c | sort -rn | head -25
cmd '{"cmd":"save_and_quit"}'
sleep 20
cmd '{"cmd":"exit_control_mode"}' >/dev/null 2>&1
# quit from title: SIGTERM the sandbox java
pkill -TERM -f "$SB_JAVA"
sleep 8
pgrep -f "$SB_JAVA" >/dev/null && echo "WARN: game still running" || echo "game closed"
