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
