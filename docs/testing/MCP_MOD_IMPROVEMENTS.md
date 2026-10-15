# minecraft-mod-mcp: fork notes and upstream contributions

Our fork: `~/dev/minecraft-mod-mcp-hardened` (from langyo/minecraft-mod-mcp v0.4.6, Apache-2.0). Tested only on the 1.21.11 Fabric target, macOS Apple Silicon, Prism. Each item says whether it should go upstream (PR) or stay fork-only.

## Done in the fork

| # | Change | Why | Upstream? |
|---|---|---|---|
| 1 | Bind `InetAddress.getLoopbackAddress()` instead of `0.0.0.0` | Upstream exposes full game control to the LAN with no auth | **Yes, security PR** (make LAN opt-in via config) |
| 2 | `localOnly` filter on every context: 403 if an `Origin` header is present or `Host` isn't localhost/127.0.0.1/[::1]; removed `Access-Control-Allow-Origin: *` | Any web page could POST `/api/cmd` cross-origin (simple request, no preflight) and drive the game; Host check stops DNS rebinding | **Yes, same PR** |
| 3 | 1.21.11 Fabric entrypoint hands `MinecraftClient.getInstance()` and the window handle to `ReflectionHelper` | `Class.forName("net.minecraft.client.MinecraftClient")` misses in production Fabric (intermediary `class_310`); every command failed "Failed to get Minecraft instance" | **Yes, bug** (likely affects every Fabric target in production; their Windows test matrix may run dev mappings) |
| 4 | `VanillaScreenshotInputHandler.screenshot()` via `ScreenshotRecorder.takeScreenshot(framebuffer, …)` | Reflective glReadPixels path returned 120x329 then 4x1708 on macOS (window size from heap `IntBuffer`s passed to GLFW, which needs direct buffers) | **Yes**: per-version module override pattern, plus fix `WindowHelper.getGlfwWindowSize` to use direct buffers |
| 5 | `click_px` (screenshot pixels, calls `Screen.mouseClicked/mouseReleased` with a `Click` on the render thread) and `screen_info` | Upstream `click` takes window points (half the screenshot on Retina, undocumented) and its widget lookup misses custom mod screens, falling back to GLFW event injection that does nothing | **Yes**: pixel-space click as an option, document the coordinate space |

## Notes for later (not done)

- Fork adds `bot_view` (name, perspective first|back|front): swaps the client camera entity, captures one frame, restores. Works within render distance only. Todo: hide the HUD and hand during capture (`options.hudHidden`), keep the front/back camera from clipping into walls (it jams against the face in 1-wide spaces), and a server-side spectator camera for far bots.
- `save_and_quit` replies "render thread timeout" because `disconnectWithSavingScreen` blocks the render thread while saving; reply first, then disconnect on the next tick.
- `mc.sh cmd` style truncation: screenshots and bot_view return large data URLs; clients must not truncate.

- Fork adds `set_time` (singleplayer only, integrated server `setTimeOfDay`) so tests can hold daylight in commands-off worlds. Upstream could offer a generic sandbox/world-control group gated to singleplayer.

- **Control mode takes the camera from the player** (frees the cursor for the whole session), so a watching human can't look around. Hold it per action (enter → act → exit, what our helper does now), or yield automatically when the player moves the mouse / presses a key. Upstream UX PR.
- `open_chat` and `pause_game` fail in production (reflective class/method names); fork adds `send_chat`, `pause_menu`, `save_and_quit` from mapped code.

- `get_screen_buttons` returns `[]` in production (reflective field names). A per-version module could walk `Screen.children()` from mapped code and return label, x, y, w, h in screenshot pixels: the single most useful tool for UI testing.
- `screenshot_to_file` writes to any path. Restrict to the instance's screenshots dir (security).
- `screenshot` returns a bare `data:` string, not JSON like other commands. Make it `{"result":"data:…","w":…,"h":…}`.
- Control mode: a client can call `enter_control_mode` itself, so the in-game "MCP Take Over" consent is advisory. Upstream could require the in-game toggle (or a token) before input works.
- Bridge runs `npx` unpinned by default in docs; recommend `npm i -g minecraft-mod-mcp@<ver>`.
- Resource pack metadata error at startup: `mcpmod pack metadata: missing min_format and max_format` (1.21.11 pack format).
- The take-over overlay icon (top right) overlaps mod screens (covers the corner of the Frens World Mode panel).
- Keyboard: verify `type_text` / `press_key` on mod screens and in-world movement with the window unfocused.
- **(Partly addressed by `health` below.) Every render-thread command stalls when the window is minimized or fully covered (2026-10-14).** `screen_info`, `screenshot` and `save_and_quit` all answered `render thread timeout` while the integrated server kept ticking. macOS stops presenting frames for an occluded GLFW window, so tasks queued with `client.execute` never run. Ideas: a `window_state` command that reads GLFW iconified/focused off-thread and says so in the error, or a `restore_window` command (`glfwRestoreWindow` is main-thread only, so it has the same problem; would need a pre-render hook).
- **DONE in the fork (2026-10-14): `save_and_quit` hung the client on "Saving world".** It called `disconnectWithSavingScreen` alone, which spins `render(false)` until the integrated server stops, but nothing had asked the server to stop (the pause menu calls `MinecraftClient.disconnect(QUITTING_MULTIPLAYER_TEXT)`, which closes the connection first). Now it uses that path, replies before the quit runs, and records the request time.
- **DONE in the fork (2026-10-14): `health` command and stuck detection (Bradley's ask).** `health` answers without the render thread: screen, integrated-server state, render-thread top frames, time since the quit request, and a verdict `ok | saving | stuck_save | render_idle | unknown`. `stuck_save` = render thread inside disconnect while the server is running and not stopping, 10 s after a quit. Every `render thread timeout` error now carries the health JSON, so a minimized window (`render_idle`) and a hung save are told apart. Upstream candidate.
- **DONE in the fork (2026-10-14): `pause_menu` works in control mode.** Upstream's `MinecraftClientMixin` cancels every `GameMenuScreen` while control mode is on (to stop the lost-focus auto-pause), which also cancelled our own request. A one-shot `consumeGameMenuRequest()` flag lets a requested open through, and the command now reports when the menu did not open.
- **DONE in the fork (2026-10-14): `screen_buttons` + `click_button`.** Lists visible widgets (nested in lists/tabs too) with label, type, active flag and centre in screenshot pixels; `click_button` clicks by label (exact, else unique substring) or index through the screen's own `mouseClicked`, refusing inactive or ambiguous targets. Replaces upstream `get_screen_buttons` (always `[]` in production). Upstream candidate.
- **DONE in the fork (2026-10-14): pack metadata ERROR.** `pack.mcmeta` now declares `min_format: 75` / `max_format: 94` (1.21.11 resource/data majors), the mandatory fields for formats past 64. Verify the ERROR is gone on next launch.
- **Helper/scratch tools (not in the fork):** `logscan.sh [HH:MM:SS]` groups WARN/ERROR lines with numbers masked, most frequent first; caught the 67x protected-claim spam (sandbox #9) in one call.
- **DONE in the fork (2026-10-14): `type_text` + `press_key`.** `type_text` focuses a text field (by index from `screen_buttons`, the focused one, or the only one) and feeds characters through the screen's `charTyped`, so the screen's change listeners run (`replace=true` clears first). `press_key` sends enter/escape/backspace/tab/arrows/delete/home/end to the open screen. `screen_buttons` now reports text field values. Covers the roster Create-name check without Bradley.
- **DONE in the fork (2026-10-14): `bot_view` hides the viewer's HUD** (the hotbar/hearts in bot shots were the player's, not the bot's); `hud=true` keeps it.
- **DONE in the fork (2026-10-14): `players`.** Lists player entities the client sees (bots are fake players): position, health, held item, distance, plus time of day. Read-only.

## Upstream PR plan (langyo/minecraft-mod-mcp, prepared 2026-10-14, nothing sent)

Order: security first, then fixes that make production builds work, then new commands. Each PR is one fork commit (branch `hardened` in `~/dev/minecraft-mod-mcp-hardened`), rebased onto upstream main and split per loader where needed (our commits touch only the 1.21.11 Fabric module plus `packages/common`). Bradley approves each PR's text before anything is opened (outbound action).

1. **Security: loopback bind + Origin/Host refusal, no `Access-Control-Allow-Origin: *`** (`fdd4b47`, McpHttpServer part). Any web page could drive the game through the open CORS API. Common module, all loaders benefit.
2. **Production Fabric: pass the mapped client in** (`fdd4b47`, ModDevMcpMod part). Reflection by Yarn names fails under intermediary names in real installs ("Failed to get Minecraft instance").
3. **Screenshots via vanilla ScreenshotRecorder** (`fdd4b47`). The reflective glReadPixels path returned slivers on macOS.
4. **`save_and_quit` hang** (pause-menu disconnect path) and **`pause_menu` blocked in control mode** (`a42e7ed`).
5. **`health` / stuck detection** (`502af9a`).
6. **`screen_buttons` / `click_button` with list rows** (`7a7aa46` + rows commit), replacing `get_screen_buttons` (always `[]` in production).
7. **`type_text` / `press_key`** (`03a4a20`), **`players`**, **`bot_view`** (+ HUD hiding), **`set_time`**, **`click_px`**, **pack metadata** (`1c28266`).

Open questions for upstream: whether they want game-specific helpers (`set_time`, `bot_view`, `players`) or only generic UI control; their loader matrix (NeoForge/Forge equivalents of our Fabric-only changes).
