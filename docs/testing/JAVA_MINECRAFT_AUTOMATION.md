# Driving Java Minecraft from Claude: what works, what doesn't

Living doc. Update it every time a session learns something new about testing Frens without Bradley at the keyboard. Newest findings go at the top of "Log".

## Current state (2026-10-13)

| Capability | Status | Notes |
|---|---|---|
| Create a Prism instance | ✅ works | Write the folder directly: `instances/<id>/instance.cfg` + `mmc-pack.json` (copy from `1.21.11`) + `minecraft/mods/`. Prism picks it up on next start. Set `name=` inside `[General]`, or Prism shows "Unnamed Instance". |
| Launch an instance | ✅ works | Call the binary: `"/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" --launch <instance-id> &`. It forwards to an already-open Prism. `open -a … --args` silently does nothing when Prism is already running. Uses Bradley's existing Prism account, so no sign-in. |
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

## Hardened MCP fork command reference (2026-10-14)

Fork: `~/dev/minecraft-mod-mcp-hardened`, branch `hardened`. Build `cd packages/mods/1.21.11/fabric && ./gradlew build`, copy `build/libs/minecraft-moddev-mcp-fabric-1.21.11-0.4.6.jar` to the sandbox `mods/` as `minecraft-mcp-hardened-1.21.11-0.4.6.jar` (game closed). API: `POST http://127.0.0.1:9876/api/cmd` body `{"cmd":...}`. Input commands need `enter_control_mode` first and `exit_control_mode` after (control mode takes the player's camera).

| Command | Control mode | What it does |
|---|---|---|
| `health` | no | Answers without the render thread. Verdict `ok`, `saving`, `stuck_save`, `render_idle`, `unknown`; screen, server state, render-thread frames. Every `render thread timeout` error carries it. |
| `screen_info` | no | Screen class/title, framebuffer size, GUI scale. |
| `screenshot` | no | Raw `data:image/png;base64,...` (vanilla ScreenshotRecorder). |
| `bot_view` name, perspective first/back/front, hud | no | Screenshot from an entity's camera, viewer HUD hidden unless `hud=true`. |
| `players` | no | Visible players and bots: position, health, held item, distance, time of day. |
| `bot_state` name | no | Server-side state of one player or bot (singleplayer): exact pos, onGround, sky above head, health, food, saturation, air, held item, full inventory. |
| `blocks` name or x,y,z, r, dy | no | Server-side block grid around a bot or position (r and dy up to 4), one level per y, `.` air, `~` water, `!` lava, `@` centre. Reads terrain around a stuck bot without screenshots. |
| `screen_buttons` | no | Visible widgets: index, label, type, active, centre in screenshot px, text field values. |
| `click_button` label or i | yes | Clicks a widget through the screen (refuses inactive/ambiguous). |
| `click_px` x, y | yes | Click at screenshot pixels. |
| `type_text` text, i, replace | yes | Types into a text field through `charTyped`. |
| `press_key` key | yes | enter, escape, backspace, tab, arrows, delete, home, end. |
| `send_chat` text | yes | Chat or a `/command`. |
| `pause_menu` | yes | Opens the game menu (reports if it did not open). |
| `save_and_quit` | yes | Pause-menu disconnect path, replies first. |
| `set_time` time | yes | Singleplayer overworld time. |

Scratch helpers (session scratchpad, not in the repo): `mc.sh shot|click|cmd|buttons|press|type|key|health`, `logscan.sh [HH:MM:SS]` (WARN/ERROR grouped, numbers masked, most frequent first).

## Gotchas

- **Never toggle fullscreen (F11) mid-run on Mac.** With Sodium + Iris + Distant Horizons it wrecked the window (2026-10-14). Fullscreen also renders 1920×1200 (16:10) stretched onto the 1920×1080 TD1655. Fix: quit, set `fullscreen:false` in `options.txt`, relaunch.

- `pbcopy` without `LANG=en_US.UTF-8` mangles emoji and dashes. Not Minecraft-specific, but it bit the same session.
- Prism's **global** Java args (`prismlauncher.cfg` → `JvmArgs`) apply to every instance unless the instance sets `OverrideJavaArgs=true`. Bradley's global args include `-Daiplayer.llmMode=ollama`, which equals Frens' default, so tests stay representative.
- 1.21.x renamed "Allow Cheats" to **Allow Commands** (Create World → Game tab). It's OFF by default in Survival.

## Log

- **2026-10-14: Claude drives the game.** Prism instance `mcp-sandbox` ("MCP sandbox (throwaway)": Fabric API + Frens + a hardened local build of langyo/minecraft-mod-mcp 0.4.6 at `~/dev/minecraft-mod-mcp-hardened`, JVM `-Dmcp.port=9876`). Patches over upstream: binds loopback only, refuses any request with an Origin header or a non-localhost Host (403), no `Access-Control-Allow-Origin: *`; hands the remapped `MinecraftClient` + window handle to its reflection cache (upstream `Class.forName("...MinecraftClient")` misses in production Fabric); screenshots go through vanilla `ScreenshotRecorder` (`VanillaScreenshotInputHandler`), the upstream glReadPixels path returned 120x329 / 4x1708 garbage on macOS. Verified: LAN address refused, browser-style request 403.
  - API: `POST http://127.0.0.1:9876/api/cmd` JSON `{"cmd":"…", …params as strings}`. `screenshot` returns a raw `data:image/png;base64,…` string (1708x960 framebuffer). Input needs `{"cmd":"enter_control_mode"}` first (the in-game "MCP Take Over" consent).
  - **`click` x,y are window points = screenshot pixels / 2** on Retina (the mod divides by GUI scale itself). Passing framebuffer pixels clicked the copyright link.
  - `get_screen_buttons` returns `[]` (reflective widget lookup misses intermediary names): click by coordinates from the screenshot for now.
  - First drive: title → Singleplayer → Create New World → World Mode chooser opened (1.1.234 per-save fix confirmed). Quit a title-screen game with SIGTERM to the `instances/mcp-sandbox/natives` java process; inside a world use the menu.

- **2026-10-14:** Research pass on full control, see `MINECRAFT_CONTROL_RESEARCH_2026-10.md`. Quick win: langyo/minecraft-mod-mcp (1.21.11 Fabric, screenshots + clicks + keys + typing via the game itself) but it binds 0.0.0.0 with no auth and open CORS, so throwaway instance only, then fork to 127.0.0.1 + token. Long-term: Fabric client gametests (`fabric-client-gametest-api-v1` already in our fabric-api). Medium: copy Prism's java launcher into a real `.app` to get a grantable bundle ID (untested).

- **2026-10-13:** First attempt at a fully autonomous fresh-install test. Instance creation, launch, Ollama control and log reading all worked. Game window blocked for both per-app and full-screen control (filtered out of screenshots on the external monitor TD1655 too). Fell back to the guided split above.
