# Driving Java Minecraft from Claude: what works, what doesn't

Living doc. Update it every time a session learns something new about testing Frens without Bradley at the keyboard. Newest findings go at the top of "Log".

## Current state (2026-10-13)

| Capability | Status | Notes |
|---|---|---|
| Create a Prism instance | ✅ works | Write the folder directly: `instances/<id>/instance.cfg` + `mmc-pack.json` (copy from `1.21.11`) + `minecraft/mods/`. Prism picks it up on next start. Set `name=` inside `[General]`, or Prism shows "Unnamed Instance". |
| Launch an instance | ✅ works | `open -a "Prism Launcher" --args --launch <instance-id>`. Uses Bradley's existing Prism account, so no sign-in. |
| Read game state | ✅ works | Tail `instances/<id>/minecraft/logs/latest.log`. Every field-checklist item names its log line. This is the reliable channel. |
| Stop/start Ollama | ✅ works | `osascript -e 'quit app "Ollama"'` gets cancelled by the app's confirm dialog. SIGTERM works: `pkill -TERM -f "/Applications/Ollama.app/Contents/MacOS/Ollama"` then `pkill -TERM -f "ollama serve"`. Restart with `open -a Ollama`. Check: `curl -s localhost:11434/api/version`. |
| See the game window | ❌ blocked | The game is a bare `java` process (no bundle ID). Computer use only grants apps by name, and its screenshot filter **blanks out** windows of non-granted apps, on any display. |
| Click/type in the game | ❌ blocked | Same reason: the frontmost-app gate refuses a non-granted app. Don't work around it. |
| Prism Launcher UI | ✅ grantable | `org.prismlauncher.PrismLauncher` is a normal app. |
| See what the player sees | ✅ via F2 | Minecraft's own screenshot key saves PNGs to `instances/<id>/minecraft/screenshots/`. Claude reads them with Read (resize with `sips -Z 1800` first). Needs a human to press F2, or a future in-mod harness that calls the screenshot recorder. Shell `screencapture` is blocked by the sandbox. |

## Legitimate paths to more autonomy (not built yet)

These make the game controllable **with Bradley's explicit grant**, or let the mod test itself. None of them bypass the computer-use safety checks.

1. **Give the game an app identity.** If Minecraft ran inside a real `.app` bundle with its own bundle ID, Bradley could grant it in the computer-use dialog like any app. Options to research: Prism's macOS launch wrapper, or a small signed launcher app that execs the instance's java command. Check that the dock name/bundle ID actually changes for the java process.
2. **An in-mod test harness** (dev builds only, off by default): Frens reads scripted steps (spawn bot, say line, quit to title, reopen world) from a file or a localhost-only socket and logs PASS/FAIL. Must never ship enabled, must bind 127.0.0.1 only, and must be excluded from the Modrinth JAR or gated behind a dev flag. This would make most field checks fully autonomous.
3. **Fabric client gametest** (`fabric-client-gametest-api`): Fabric's own framework for scripted client tests (create world, wait for ticks, take screenshots). The most standard route. Research whether it runs on 1.21.11 with our setup.
4. **Headless dedicated server + fake client** for server-side items. Doesn't cover singleplayer-only journeys like the first-run screen.

## Split of work that works today

Bradley clicks, Claude watches `latest.log` and calls pass/fail with evidence. Give one step at a time, each with the log line Claude expects.

- **Screenshots:** fn+F2 on Mac. A background loop waits for a new file in `screenshots/` and re-invokes Claude, so each one gets reviewed as it lands.
- **In-game chat is a notes channel:** anything Bradley types shows in `latest.log` as `<Name> …`. Type a note, then fn+F2, and both arrive together.
- **Filter the log:** a grep that drops `RideSyncTick|PersistCheck|FollowAssert|planner|ensureAtSurface|movement local|Missing sound|Saving chunks|UBO` makes the rest readable.

## Gotchas

- `pbcopy` without `LANG=en_US.UTF-8` mangles emoji and dashes. Not Minecraft-specific, but it bit the same session.
- Prism's **global** Java args (`prismlauncher.cfg` → `JvmArgs`) apply to every instance unless the instance sets `OverrideJavaArgs=true`. Bradley's global args include `-Daiplayer.llmMode=ollama`, which equals Frens' default, so tests stay representative.
- 1.21.x renamed "Allow Cheats" to **Allow Commands** (Create World → Game tab). It's OFF by default in Survival.

## Log

- **2026-10-13:** First attempt at a fully autonomous fresh-install test. Instance creation, launch, Ollama control and log reading all worked. Game window blocked for both per-app and full-screen control (filtered out of screenshots on the external monitor TD1655 too). Fell back to the guided split above.
