# Fresh-install test: Frens 1.1.226 (2026-10-13)

Setup: new Prism instance `frens-fresh` (Fabric loader 0.19.3, Fabric API 0.141.6, Frens JAR byte-identical to Modrinth version BkS087B5), no Carpet, no owo-lib, Ollama stopped. New singleplayer Survival world, **Allow Commands OFF**. Bradley played, Claude read `latest.log` + F2 screenshots (`instances/frens-fresh/minecraft/screenshots/`). Part 2 (AI setup checklist with Ollama on) was **not run**.

## Passes

- Clean startup: Frens loads, no errors. Ollama-off is one INFO line (`[souls] ollama not reachable`), nothing in chat, no false "Processing your message".
- **1.1.226 fix:** `Auto-spawn deferred until the first real player joins (integrated server).` on both loads. On reopen, Jake + Bob auto-spawned, restored position and inventory, host got in.
- Follow works (some no-path retries on slopes). Woodcut works once the bot has an axe (fells, pillars, crafts planks).
- Idle drop sweep picks items up. Jake handed Bob a spare axe on his own.

## Findings

| # | Finding | Evidence | Severity |
|---|---|---|---|
| 1 | **Client UI sends `/bot` chat commands**, which don't exist for a non-op player. Roster Spawn Selected + Create, and the Follow toggle failed with "Unknown or incomplete command". Likely more buttons. A commands-off Survival player can't get or command a companion. | `BotRestoreScreen.java:344,363` (`sendChatCommand("bot spawn …")`); log 14:54:48 `bot spawn Jake admin<--[HERE]`, 15:09:52 `bot follow toggle Jake<--[HERE]` | 🔴 Blocker |
| 2 | **World Mode chooser never opens** for a commands-off singleplayer host: `canChooseWorldMode` needs `hasBotCommandPermission` (op 2) or a delegate. No host check (1.1.225 added one for the AI checklist only). | `SurvivalRecruitmentService.java:159-172` | 🔴 High |
| 3 | **`/bot stop` runs Carpet's `/player <bot> stop`**. Without Carpet that's a visible red error on every stop. Other `player …` uses exist. | `modCommandRegistry.java:3357` (also 3347, `ChartPathToBlock.java:26,39`, `turnTool.java:16`, `FunctionCallerV2.java:413,426`); log 15:03:01 `player Jake stop<--[HERE]` | 🔴 High |
| 4 | **Woodcut on a fresh world refuses**: "I have no axe and can't make or find one." No punch-a-tree bootstrap. Also says "Busy with another task right now." in the same breath (wrong line). Modrinth changelog says "Bots can make their own wooden axe" (needs planks + sticks). | log 14:58:42 | 🔴 High (first impression) |
| 5 | **Command feedback dropped**: spawn of an already-active bot answered "A bot named 'Bob' is already active…" but `No recipient resolved for system message; dropping`. Button looks dead. Command also logs "completed successfully". | log 15:05:21 | 🟡 Medium |
| 6 | **Roster offers bots already in the world** under "Restore". | screenshots 15:03:35, 15:05:20 | 🟡 Medium |
| 7 | **`-` key changes meaning** after the first bot: roster when zero bots, then "go to where I'm looking" ("No bots are following you.", "that's too far"). Hint only teaches the roster meaning. | `FrensClient.java:688-720`; log 15:00:10-12 | 🟡 Medium |
| 8 | **Layouts overflow at the default width** (window 1708×1642, GUI scale auto → ~427 scaled px, same as vanilla 854×480): Guide detail card + search placeholder, Admin tabs ("ActionsDialogue"), bot inventory screen (clipped both sides), "Looking at X…" hint bar, large overhead greeting text. Roster hint clipped while the vanilla tutorial toast shows. | screenshots 14.52.41, 14.55.15, 14.57.36, 14.57.43, 14.56.55, 15.03.39 | 🟡 Medium |
| 9 | **Scaffold cleanup uses whatever is in hand**: dirt block, then copper axe on dirt (3 s/block, axe wear). | log 15:09:27-32 | 🟡 Medium |
| 10 | **Bots hurt themselves** reaching into a small crevice (Bob, 15:08) and crawling under a tree (Jake, after reopen). Jake stuck staring at an unreachable sapling in a tree even after it fell on him, then gave up. | Bradley's in-game notes, log | 🟡 Medium |
| 11 | **Stop spam**: one stop → "Stopping Jake...", "Jake has stopped.", "Jake: Current task aborted via /bot stop.", "Current task aborted via /bot stop." (mentions a command the player never typed). | screenshot 15.03.03 | ⚪ Minor |
| 12 | Brand-new bot greets with "welcome back". | log 14:56:43 `greeting_welcome_back` | ⚪ Minor |
| 13 | No hint that AI chat is off when you address a bot. | 14:56:53 | ⚪ Minor |
| 14 | Woodcut slow to start (~33 s to first chop with trees adjacent), 600 ms path searches on slopes. | log 15:00:59 → 15:01:32 | ⚪ Minor |
| 15 | 5 `Missing sound for event: frens:bot.line.*` at startup (also on Nirn). | startup log | ⚪ Minor |

## Not tested

Part 2 (Ollama on, Guide → AI setup checklist, model pick, Soul Chat, test reply, voice). Next fresh-install run should cover it.
