# Giving Claude control of the Minecraft client: research (2026-10-14)

Scope: every realistic way for Claude Code on Bradley's Mac to see, click, type, move, look, run commands, read state and take screenshots in his **Minecraft 1.21.11 / Fabric (Prism Launcher) / Apple Silicon** client. Research only. Nothing was installed or downloaded. Companion to `JAVA_MINECRAFT_AUTOMATION.md` (what already works: log reading, Prism launch, F2 screenshots).

Legend: **V** = verified this session (command output or primary source read), **U** = unverified / inferred, needs a test.

## 0. Facts checked on this machine (V)

- The running game (pid 31356 at check time) is `…/PrismLauncher/java/java-runtime-delta/bin/java`. `lsappinfo` reports `bundleID=[ NULL ]` and treats `jre.bundle/Contents/Home/bin/java` itself as the "bundle path".
- Prism already passes `-Xdock:icon=icon.png -Xdock:name="Prism…"` and `-XstartOnFirstThread`. **`-Xdock:name` changes the Dock label only. It does not create a bundle ID** (the bundle ID is still NULL with it set).
- The JRE ships `jre.bundle/Contents/Info.plist` (`CFBundleIdentifier=net.java.openjdk.jdk`, `CFBundleExecutable=libjli.dylib`, type `BNDL`). That plist describes the JRE as a loadable bundle, not as an app, so the `java` process doesn't inherit it.
- `bin/java` is a thin Microsoft-signed launcher (`Identifier=net.java.openjdk.java`, hardened runtime). It links only `@rpath/libjli.dylib`, with rpaths `@loader_path/.` and `@loader_path/../lib`.
- **Official launcher:** `/Applications/Minecraft.app` is `com.mojang.minecraftlauncher`, but the game it spawns runs from `~/Library/Application Support/minecraft/runtime/java-runtime-delta/mac-os-arm64/…/jre.bundle/Contents/Home/bin/java`, the same `net.java.openjdk.java` thin launcher. So the official launcher's game is **also a bare java process with no bundle ID**. Switching launchers doesn't help.
- Instance `1.21.11/instance.cfg`: `OverrideJavaLocation=true`, `JavaPath=…/java-runtime-delta/bin/java`, `OverrideCommands=false`. Global `WrapperCommand=` and `PreLaunchCommand=` are empty.
- IntelliJ IDEA CE (`com.jetbrains.intellij.ce`) is installed. Its `Contents/MacOS/idea` is a native launcher that loads `libjli`/`libjvm` and calls `JNI_CreateJavaVM` **in process** (seen in `strings`). That is how a Java app gets a real bundle identity on macOS.
- Frens builds against `fabric-api 0.136.0+1.21.11`. Its POM declares `fabric-client-gametest-api-v1 4.2.14+65ffd0401e`, and that jar is in the local Gradle cache. Frens uses Loom 1.11.8, and Loom's `configureTests` DSL was merged in FabricMC/fabric-loom#1240 on 2025-01-02, before 1.11.

## 1. Options table

| # | Approach | Control it gives | Fits 1.21.11 + Fabric + Apple Silicon? Evidence | Effort | Risks |
|---|---|---|---|---|---|
| A | **langyo/minecraft-mod-mcp** (in-client mod + `npx minecraft-mod-mcp` stdio MCP bridge) | Screenshots (base64 or to file); click/right-click/drag/scroll at x,y; `press_key` with `hold_seconds` (walking); `type_text`/`paste_text`; `look_delta`/`set_view_angle`; `use_item`, `place_block`; `execute_command`; `get_player_info`/`get_world_info`; GUI introspection: `get_screen_buttons`, `click_button_id`, `enumerate_widgets`, `switch_tab`, `select_list_item`, `close_screen`; SSE event stream. Input goes through reflection (`InputInjectionHelper`, `ReflectedInputHandler`), not OS events, so **the window doesn't need focus and the bundle-ID gate doesn't apply.** | **Yes on paper (V):** release v0.4.5 (2026-10-13) ships `minecraft-mcp-1.21.11-fabric-v0.4.5.jar` (924 KB). Repo: 41★, Apache-2.0, pushed 2026-10-14, created 2026-05-24, 6 forks. **macOS/Metal U:** no macOS CI job, no macOS issues. https://github.com/langyo/minecraft-mod-mcp | Low: drop the jar in a test instance, add the bridge to `.mcp.json` | **Serious (V, read in `McpHttpServer.java`):** binds `0.0.0.0` (lines 77/83), **no token or auth**, `Access-Control-Allow-Origin: *`, `/api/cmd` only checks `POST` with no content-type check. While the game runs, **any web page in any browser can send a plain cross-origin POST and drive the game**, and so can any device on the LAN. `screenshot_to_file` takes an arbitrary path. The bridge also `npx`-fetches code on each run unless pinned. Young project. |
| B | **chapmanjw/minecraft-java-fabric-mcp-server** ("Fabric API MCP Server" on Modrinth) | Server/world tools (blocks, entities, commands, inventories, registries; a large surface) on port 8765. A client endpoint on 8766 gives `view_capture` (the real first-person frame as PNG) and `sense_*`/`client_status`. **No input injection, no menu driving.** | **Yes (V):** Modrinth `1.1.0+1.21.11` (2026-07-27), 1,106 downloads; GitHub 11★, MIT, pushed 2026-09-30. Rendering on Metal is U. https://github.com/chapmanjw/minecraft-java-fabric-mcp-server · https://modrinth.com/mod/fabric-api-mcp-server | Low | Best security posture found: binds `127.0.0.1`, strict Host/Origin checks, optional bearer token, opt-in tool groups. World-edit tools are powerful, so use a test world. |
| C | **Minescript 5.0** (Python scripts inside the client) | `screenshot()`, `execute()`, `chat()`, `player_press_forward/jump/use/attack/…`, `press_key_bind`, `player_set_orientation`/`player_look_at`, position, inventory, `container_get_items`, `screen_name`, targeted block/entity, `get_block_region`, event listeners, `set_interval`, plus `java_*` reflection for anything else, such as clicking a screen widget. `autorun[*]=…` runs a script on world join. | **Yes (V):** Modrinth `5.0-fabric-1.21.11` (2026-09-22), 74k downloads, GPL-3.0. GitHub maxuser0/minescript 219★, pushed 2026-09-22. Needs a local Python 3. https://github.com/maxuser0/minescript | Low–medium. To let Claude drive it, write a small Minescript script that polls a command file (or a loopback socket you control) and writes results and screenshots back. | No network listener by default, so it's the safest of the in-client options. GPL licence only matters if Frens bundled it (don't). `java_*` reflection is full power inside the JVM. |
| D | **Baritone** (pathing/mining via `#` chat commands + Java API) | Autonomous navigation (`#goto`, `#mine`, `#follow`, `#build`), callable from chat, Minescript or another mod. No screenshots or menus. | **Yes (V):** cabaletta/baritone **v1.17.0 "For Minecraft 1.21.11"** (2026-08-31), Fabric jars; 9,298★, LGPL-3.0, branch `1.21.11` last merged 2026-08-31. (v1.20.0 is for 26.3, so don't grab "latest".) | Low | Movement only. Can conflict with Frens' own movement or keybinds in the same client. Flagged by anti-cheat on servers (local only, so fine). |
| E | **lolifamily/Minecraft-MCP** (Kotlin REPL inside the game; `execute_code`, `run_command`, `take_screenshot`) | Arbitrary Kotlin against mojmap names, on server/client/render ticks. The most powerful option. | **No 1.21.11 build (V):** `versions/` holds 1.18.2, 1.20.6 and 26.1.2 only, with 0 GitHub releases. 14★, MIT, pushed 2026-10-14. | High (port + build from source) | Its README says so itself: remote code execution by design. Bearer token, loopback only, rejects any request with an `Origin` header. |
| F | effector-mcp, InventivetalentDev/minecraft-mcp | Rich client agents (54 tools; screenshots, player control) | **No (V):** effector targets 26.2 / Java 25; Inventivetalent's Fabric mod targets 26.1.2 / Java 25. | n/a | n/a |
| G | **App identity for the java process** so computer use can grant it (see section 2) | Real computer use: Claude's screenshots show the game, and clicks and keys reach it like any app. Covers every screen with no in-mod work. | **U, but a concrete recipe exists.** The shell-script `.app` route is likely dead (see 2.1). The in-bundle launcher route is plausible (2.2). | Medium (an hour or two of probing) | Synthetic mouse-look with GLFW's captured cursor is unproven (menus should be fine). Re-signing and TCC churn. It must stay a grant Bradley makes himself, not a filter bypass. |
| H | **Fabric client gametests** (`fabric-client-gametest-api-v1`) | Scripted end-to-end tests: `worldBuilder()` makes a fresh singleplayer world; `waitTicks`/`waitFor`/`waitForScreen`; `clickScreenButton`; `TestInput.holdKey/pressKey/typeChars/moveCursor/scroll/holdMouse`; `takeScreenshot`, `assertScreenshotEquals/Contains`; `runOnClient`/`computeOnClient`; dedicated-server context. | **Yes (V):** API 4.2.14 is a dependency of Frens' own fabric-api 0.136.0+1.21.11 (local cache). Loom 1.11.8 has `fabricApi { configureTests { … } }` (loom#1240). Run with `./gradlew runClientGameTest`. **Headless is Linux-only** (Loom `ClientProductionRunTask { useXVFB = true }`). On macOS it opens a normal window, which is fine. Docs: https://docs.fabricmc.net/develop/automatic-testing | Medium (new `src/gametest` source set + `fabric.mod.json` entrypoint) | Runs in its own dev run dir, not Bradley's real Prism instance or worlds. Each run starts a client (~1 min). Fabric's docs note network-synchronizer flakiness on CI (`-Dfabric.client.gametest.disableNetworkSynchronizer=true`). |
| I | **Mineflayer** as a second player (raw JS bot) | A full separate player: chat, move, dig, inventory, entities. Good for LAN-guest and multiplayer tests of Frens. Doesn't control Bradley's client. | **Contested (V/U):** README lists 1.21.11; mineflayer 4.39.0 (2026-09-06), 7,582★, MIT. But **issue #4044 "Connection hangs forever in 'configuration' state against MC 1.21.11 (protocol 774)" is still open** (checked 2026-10-14; reported against Paper). Vanilla LAN is untested. | Medium | A LAN world opened from singleplayer is online-mode, so the bot needs a real account or an offline-mode server (U). Protocol bugs. |
| J | **yuniko-software/minecraft-mcp-server** (Mineflayer-based MCP) | MCP tools for the second-player bot (move, dig, build, chat, inventory) | README says "Currently supports Minecraft version 1.21.11". 780★, Apache-2.0, **last push 2026-04-04**. Inherits the #4044 risk. https://github.com/yuniko-software/minecraft-mcp-server | Low–medium | Same as I. |
| K | **mindcraft** (LLM agents on Mineflayer) | Autonomous LLM players for playtesting or multi-bot scenes | "up to v1.21.11, recommend v1.21.6". 5,890★, MIT, pushed 2026-10-03. ViaProxy for mismatched versions. | Medium | Same as I. Runs its own LLM loop (costs money or uses local Ollama). |
| L | Voyager / MineDojo / MineStudio / Odyssey | Research agent frameworks | **Poor fit (U, not deep-checked):** pinned to old MC versions (Voyager is Mineflayer 1.19-era; MineDojo/MineStudio use MineRL/Malmo-style 1.16 envs). Not worth porting for Frens testing. | High | Stale. |
| M | **HeadlessMC** (headlesshq/headlessmc) / mc-runtime-test | CLI launcher. Can run the client with LWJGL patched out (no window), with a terminal command line inside the game via hmc-specifics. Good for "does it boot / no crash" smoke tests in CI. | 407★, MIT, 3.0.0-RC7 (2026-10-13), pushed 2026-10-14. Supports Fabric. **macOS arm64 and the exact 1.21.11 Fabric 0.19.3 combo are U.** Headless mode renders nothing, so no screenshots. | Medium | Account validation required (offline accounts only in CI). Doesn't exercise rendering or GUIs. |
| N | **PortableMC** | Python CLI launcher (Fabric supported, scriptable) | 552★, pushed 2026-10-12. Launching only, no control. | Low | Adds nothing that Prism's `--launch` doesn't already do. |
| O | VNC / Screen Sharing loopback, OBS projector or virtual camera, or any route that shows the game's pixels inside another granted app | Would show the frame inside a granted app's window | Technically possible, but **not recommended**: it gets the game's pixels past the per-app screenshot filter by laundering them through another app, instead of Bradley granting the game. The existing doc already rules out working around the click gate. macOS Screen Sharing also refuses loopback connections to itself (U). | n/a | Evades the safety design. Input still doesn't reach the game. |
| P | macOS Accessibility (AX) API on the GLFW window | Window-level only (title, frame, raise). GLFW/LWJGL expose no AX tree for in-game widgets. | U, low value. The computer-use grant gate still applies. | n/a | n/a |
| Q | **Frens' own dev harness** (the "in-mod test harness" in the existing doc) | Anything Frens can do: spawn bots, call `MinecraftClient.takeScreenshot`-style capture, open screens, read state, log PASS/FAIL. Driven from a file or a loopback socket. | Fully in our control. The Minescript polling pattern and langyo's tool set are good references. | Medium | Must be dev-only: never in the Modrinth jar, bound to 127.0.0.1 with a token. |

## 2. Giving the game an app identity (computer use)

### 2.1 What won't work (V or strong evidence)

- **`-Xdock:name` / `-Xdock:icon`.** Prism already sets both, and the bundle ID is still NULL (V).
- **Switching to the official launcher.** Its game is the same thin `net.java.openjdk.java` binary (V).
- **A shell-script `.app` that `exec`s java, or Prism's Wrapper command.** macOS takes the app identity from the *executable image* at AppKit check-in. Once a script execs `bin/java`, the process is "java" again. Esko documents exactly this on macOS 14 to 27 for an app built this way: Dock tooltip "java", and a kept Dock item turns into a bare `exec` icon that launches Java's usage text (https://esko.my.site.com/s/article/Bitmap-Viewer-Mac-Dock-behaviour-and-launch-workarounds). Using `open -a Shim.app` from a wrapper also breaks Prism, because Prism sends the launch script over **stdin**.
- **jpackage / appbundler launchers.** They are in-bundle native launchers, so they do get an identity, but they don't accept Prism's raw `java …` argv and stdin protocol.

### 2.2 The recipe that fits Prism (U: run the test before relying on it)

The approach: the binary in `X.app/Contents/MacOS/` must itself be the process that runs the JVM. IntelliJ (`com.jetbrains.intellij.ce`, V) works this way. The cheapest version reuses the existing thin launcher:

```
~/Applications/MinecraftClaude.app/Contents/
  Info.plist     CFBundleIdentifier=dev.bradley.minecraft-claude
                 CFBundleExecutable=java  CFBundleName=Minecraft (Claude)  CFBundlePackageType=APPL
  MacOS/java     <- copy of …/java-runtime-delta/jre.bundle/Contents/Home/bin/java
  lib  -> symlink to …/jre.bundle/Contents/Home/lib     (satisfies @loader_path/../lib/libjli.dylib)
  conf -> symlink to …/jre.bundle/Contents/Home/conf    (libjli derives JAVA_HOME from where libjli sits)
```

Variant B, if A's JAVA_HOME resolution fails: a roughly 30-line C `main` (OpenJDK's `main.c`) that `dlopen`s the real `…/Home/lib/libjli.dylib` and calls `JLI_Launch(argc, argv, …)`, compiled with the Xcode CLT `cc`. JLI then finds the real JRE home from the dlopen'd path. `-XstartOnFirstThread` is honoured by JLI as usual.

Five-minute test sequence (Bradley approves each step; nothing is downloaded):
1. Build the bundle above, then `codesign --force -s - ~/Applications/MinecraftClaude.app` (ad hoc).
2. `~/Applications/MinecraftClaude.app/Contents/MacOS/java -version` should print OpenJDK 21.0.7. If it fails, switch to Variant B.
3. Duplicate the test instance and set its `JavaPath` to that binary (`OverrideJavaLocation` is already true). Run `open -a "Prism Launcher" --args --launch <test-instance>`.
4. `lsappinfo info -only bundleID $(pgrep -f "instances/<test>/natives")` should show `dev.bradley.minecraft-claude` instead of `NULL`.
5. In Claude: `request_access(["Minecraft (Claude)"])`, then take a screenshot. Check that the window is visible and not blanked. Click a title-screen button, type into chat, and test in-world mouse look.

Open questions (all U): whether computer use lists or grants it; which tier it gets; whether synthetic mouse deltas move the camera while GLFW has the cursor captured (if not, use langyo's `look_delta` or a Frens hook for camera work, and computer use for menus); whether Prism's Java check accepts the path; and whether TCC prompts again after re-signing.

## 3. Recommended path

### Tier 1: quick win today
**langyo minecraft-mod-mcp in a disposable test instance**, because it's the only 1.21.11 Fabric option that gives screenshots, GUI widget clicking, typing, key-hold movement, camera control and commands together, without needing the bundle-ID grant.
1. Copy the `1.21.11` instance to `1.21.11-claude-test` (the instance-folder method from the existing doc). Put `minecraft-mcp-1.21.11-fabric-v0.4.5.jar` in its `mods/` (Bradley downloads it, or approves the download), alongside Frens.
2. Add `-Dmcp.port=9876` to that instance's JVM args. Install the bridge once with a pinned version (`npm i -g minecraft-mod-mcp@<ver>`) rather than `npx -y`, and add it to `.mcp.json` / `~/.claude.json` per the dual-config rule.
3. **Until it's patched, during trial sessions:** quit Chrome and other browsers, stay on a trusted network, and use a throwaway test world. The listener has no auth and accepts cross-origin POSTs.
4. Smoke test: `ping` → `screenshot` → `get_screen_buttons` → `click_button_id` (Singleplayer) → `execute_command` → `look_delta` → `press_key W hold_seconds=1`. Record what works on Metal in `JAVA_MINECRAFT_AUTOMATION.md`.

No-network fallback if he won't run an open listener even once: **Minescript 5.0** with an `autorun[*]` script that polls `minescript/claude_cmds.txt` and writes results and screenshots back. It covers movement, commands, screenshots and state, but GUI clicks need `java_*` reflection.

### Tier 2: medium (days)
1. **Fork langyo locally and harden it:** change the two `"0.0.0.0"` binds to `"127.0.0.1"`, drop `Access-Control-Allow-Origin: *`, require a token header that the bridge reads from a file (chapmanjw's Host/Origin checks are the model), and restrict `screenshot_to_file` to the instance's screenshots dir. Build the 1.21.11 Fabric target. It's Apache-2.0, so a fork is fine, and the fix could go upstream.
2. **Try the `.app` identity recipe (section 2.2).** If step 4 shows a bundle ID and step 5 shows the window, Claude gets real computer use for menus and first-run screens on Bradley's normal instance, with no in-game listener at all.
3. Optionally add **chapmanjw's mod** to the test instance for safe, read-heavy world inspection (`view_capture`, `sense_*`, block and entity queries).

### Tier 3: best long-term
1. **Frens client gametests** (`src/gametest`, `fabricApi { configureTests { createSourceSet = true; modId = "frens-test"; enableGameTests = false; enableClientGameTests = true; eula = true } }`, plus a `fabric-client-gametest` entrypoint). Script the field-checklist journeys: first run in a fresh world, spawn a bot, open the Spells and Bot Storage screens, quit to title and reopen. Use `clickScreenButton`, `TestInput`, `takeScreenshot` and `assertScreenshotContains`, all run by `./gradlew runClientGameTest`, with log assertions on the existing log lines. Fully autonomous and repeatable. The API is already in Frens' dependency tree.
2. A **dev-only Frens harness** (a loopback socket with a token, excluded from the Modrinth jar) for ad-hoc exploration on Bradley's real worlds. Reuse langyo's command vocabulary so the bridge or agent prompts carry over.
3. **Second-player tests** with Mineflayer (yuniko's MCP or mindcraft) **only after mineflayer#4044 is fixed** or a vanilla-LAN probe works. A LAN guest also needs an account the online-mode LAN world accepts (U).

## 4. Unverified or not checked

- langyo, chapmanjw and Minescript on **macOS/Metal** (none have macOS CI evidence). The mods don't care about the GPU API, since MC 1.21.11 still renders through OpenGL 4.1 on macOS, but screenshot readback on Apple's GL layer is untested.
- The whole of section 2.2 (bundle identity, the computer-use grant, synthetic mouse-look).
- Mineflayer against a vanilla 1.21.11 LAN world. #4044 is reported against Paper.
- HeadlessMC on macOS arm64 with Fabric 1.21.11.
- Voyager, MineDojo, MineStudio and Odyssey versions were not re-checked in depth this session.
- Compatibility of langyo or Baritone with Frens' own mixins and keybinds in the same client.

## 5. Sources checked (2026-10-14)

- langyo/minecraft-mod-mcp: README, `docs/guides/en/AI-TOOLS.md`, `packages/common/.../McpHttpServer.java`, `McpMessageHandler.java`, releases (v0.4.5, 2026-10-13), `gh api` stats.
- chapmanjw/minecraft-java-fabric-mcp-server README; Modrinth API `fabric-api-mcp-server`.
- maxuser0/minescript `docs/README.md` (v5.0); Modrinth API `minescript`.
- cabaletta/baritone releases (v1.17.0 for 1.21.11).
- lolifamily/Minecraft-MCP README + `versions/`; eikarna/effector-mcp README; InventivetalentDev/minecraft-mcp README.
- PrismarineJS/mineflayer README, releases, issue #4044; yuniko-software/minecraft-mcp-server README; mindcraft-bots/mindcraft README.
- headlesshq/headlessmc README, releases; theorzr/portablemc (`gh search`).
- Fabric docs https://docs.fabricmc.net/develop/automatic-testing; FabricMC/fabric-loom#1240; local `fabric-client-gametest-api-v1-4.2.14` jar via `javap`.
- Esko KB on java-exec app bundles in the Dock (link above).
- Local: `lsappinfo`, `codesign -dv`, `otool -L/-l`, `plutil` on Prism's JRE, the official launcher runtime, Minecraft.app and IntelliJ IDEA CE.app.
