# Field phases 0-6ai in the sandbox: Frens 1.1.270, driven by Claude (2026-10-18)

Step 2 of Bradley's field-checklist drain plan (2026-10-18). Claude swept the open items of phases 0 to 6ai of
`FIELD_SESSION_1.1.202.md` in the `mcp-sandbox` Prism instance over the MCP API, alone, on `frens-1.1.270-release+1.21.11.jar`
(the jar already in the sandbox; nothing was deployed). Nirn was never launched. The checklist was swept against the
text after Bradley's triage rulings (`124595f7`). Phases 7-10 are out of scope (they come after 6ai).

**Counts in `FIELD_SESSION_1.1.202.md`:** before 267 open, 95 done; after **214 open, 148 done** (`grep -c '^- \[ \]'` and `'^- \[x\]'`).

239 open items were in scope. 71 got a verdict: **53 PASS (ticked), 10 FAIL, 8 PARTIAL**. 49 need Bradley or were reworded
for a part not set up (listed below with the reason), 119 were not reached in this session's time. Every item with a verdict
also got a `Sandbox 2026-10-18:` sub-line in the checklist. No high-severity finding: nothing stops a feature for a whole session,
apart from finding 5 (a guarding bot wedged in a hole never climbs out).

Sessions (each save a fresh copy made with `tools/sandbox/worldcopy.py` while the game was closed):

| Session | Save | Used for |
|---|---|---|
| S1 (16:49-16:52) | `Sweep Souls`, copy of `New World`, commands on | Phase 0, first banter |
| S2 (16:57-17:47) | `Sweep Souls` again | souls: phases 1-6, 6j-6o, 6q, 6v-6x |
| S3 (17:47-18:51) | `Sweep Old`, copy of `New World`, commands on, plus one relaunch | crafting, bundles, guard escape, supplies, idle upgrade, woodcut, drop sweep, build, home data, auto-spawn |

Setup: Piper came from Nirn's installed `config/frens/piper/` folder, copied into the sandbox config (read-only use, no download).
Souls ran on Ollama `llama3.1:8b` through `tools/sandbox/ollama_tap.py`, a loopback tap that records each `/api/chat` request,
so the prompt checks (contract, BELIEFS, CURRENT STATE) could be read. The sandbox config points back at 11434 again.
`/bot config diagnostics` was left on and the soul novelty/relations/structured toggles on in the sandbox config.

## Findings (backlog for a fix build)

Severity: high = a feature stops working for the session, medium = wrong or slow behaviour a player notices, minor = wording or rare.
Each finding is a hypothesis for the scoper, not a diagnosis.

1. **A soul-bound bot can drop out of souls after a death (medium, needs repro).** S2: Bob died to a phantom at 17:08:12
   (`AFTER_RESPAWN did not fire for bot Bob; forcing respawn routine`). From then on `/bot soul status` listed only Jake and every
   group line counted `eligible=1` (17:09-17:12) with Bob standing beside the player. `/bot soul enable Bob` (17:14:10) fixed it.
   A `/bot despawn` and `/bot spawn` earlier kept the binding, so the forced-respawn path is the suspect.
2. **Party scenes are one line from one bot (medium).** S2, five "bots, / guys, / everyone" lines with both bots eligible
   (`scene-routing ... candidates=2 eligible=2`): every PARTY scene had `lines=1`. The tap shows `llama3.1:8b` wrote one line;
   the system prompt allows "at most two lines per companion". Banter scenes in the same session ran 2-4 lines with both bots.
3. **Same speaker twice in a scene (minor).** S2 banter `7fe07d6f` (17:40:48-54): `line=1/2` and `line=2/2` both Jake, not merged.
4. **A bed craft builds a table before it checks the wool (minor).** S3, 1 white wool and 10 planks, `/bot craft bed`: the bot
   crafted (and outside the Spawn base placed) a crafting table first, then said "Can't craft bed: need 2 more matching wool".
   Inside the Spawn base the table could not be placed and the only line was "I crafted a crafting table but couldn't place it
   here.", so the player never hears what is missing. The item wants "crafts nothing".
5. **A guarding bot wedged in a hole never climbs out (medium, the one near-high).** S3 fresh bot Wedge, guard mode, 1x1x2 hole,
   16 cobblestone, day, owner 60 blocks away: every 30 s `pillar recovery placed no blocks reason=aborted steps=6` (three tries),
   then `Guard escape: Wedge failed — cooldown 30s`. With the cover removed the staging walk also logs `detail=direct: aborted`.
   Something aborts every movement inside the guard escape (an abort latch, by the look of it). Before that the escape was
   suppressed by "commander nearby", "post-task grace" (10 min after any finished task, `TASK_GRACE_TICKS`) and "nighttime",
   none of which the item expects for a guard escape. The `[guard-escape] ... reason=` line never printed.
6. **A better pickaxe inside a bundle is never pulled (medium).** S3 stone pickaxe loose, diamond pickaxe in a bundle, mining a
   stone block: all six blocks `with minecraft:stone_pickaxe`, the diamond stayed bundled (1.1.205 "hand-bundled better pickaxe").
7. **"I don't know how to craft spaceship." is not throttled (minor).** S3 four tries (three in 6 s, one 40 s later): all four answered.
   The item wants the 1st and the 4th only.
8. **Woodcut never asks a chest for an axe (medium, needs a ruling).** S3 Jake with no axe beside a chest of 2 stone axes inside a base:
   `/bot skill woodcut` went straight to the 1.1.229 bare-hand bootstrap, no `[supply]` line. Food and craft planks do prompt.
   The 6s "mark your supply chest Always" setup (and "walking away" / "never-allowed items") assume woodcut asks for an axe.
   Either the supply path for tools is not wired into woodcut, or the bootstrap now wins by design and the 6s text is stale.
9. **The idle stone upgrade never runs for a bot without an axe (minor).** S3 with the 6t kit (wooden pickaxe and shovel, no axe,
   no sword) the wooden fallback logs `Idle wooden fallback: Jake blocked — no safe woodcut target` every 12 s and returns before
   `maybeHandleIdleStoneToolUpgrades` is reached. With a wooden axe and sword added, the upgrade crafted at once.
10. **Plank bridge comes down with planks in hand, about 3 s a block (minor).** S3 woodcut bridge teardown: `Mining ... with
    minecraft:oak_planks`, several at `~3000ms`, though the bot held an axe (1.1.229 says the axe takes planks, well under 3 s).
11. **"Picked up N items." overcounts (minor).** S3 drop sweep: chat "Picked up 27 items.", skill summary `collected: 20`,
    bag 21 items after a `/clear`. The 1.1.234 count fix (`d7ce8aaf`) does not hold here.
12. **Phase 6v permission mode** (known, not investigated here): every 1.1.270 launch logged `operator-permission mode=permission-all-mapped-fallback`.
    Recorded as FAIL. Already fixed in 1.1.271 (`7dba5ee4`, static-init order), which shipped while this sweep ran; recheck on any 1.1.271 launch.

Not a finding but worth a look: Streaming first-word latency is about 5-7 s with `llama3.1:8b` (Ollama `promptEvalMs` 1.7-4.4 s
plus Piper 0.5 s), over the ~3 s target. The 1.1.226 auto-spawn reserve no longer leaves a bot out now that auto-spawn waits for
the host (7 bots + host = 8/8 spawned fine), so that item's "6 bots" text may be stale.

## Verdicts

| Phase | Item | Verdict | Evidence |
|---|---|---|---|
| 0 | World up on 1.1.202, souls master on | PASS | S1 `[16:49:57] [souls] runtime started; masterEnabled=true settingsValid=true` (the old `-PaiEnabled` line is gone since 1.1.250, by design) |
| 0 | Ollama reachable | PASS | S1+S2 whole sessions: no `ollama not reachable` and no `non-local Ollama host` line (host 127.0.0.1, via the loopback tap). |
| 0 | Bind both bots | PASS | S1 `/bot soul enable Jake` then `Bob`: chat "Jake is now speaking as Jake." and "Bob is now speaking as Bob." (16:50:29-31); `/bot soul status` shows `Bob ... profile=frens:bob`. The pre-1.1.184 `frens:jake` binding does not exist in a fresh sandbox, so the legacy rebind path was not exercised. |
| 0 | Toggles on | PASS | S1 16:50:33-39: "Companion banter set to on", "Companion local chat set to on", "Memory digest is ON. Runs at each Minecraft day rollover for soul-bound bots.", "Soul voice enabled." |
| 0 | Voice engine chosen | PASS | S1 Piper (Nirn's installed Piper folder copied into the sandbox config, read-only): `[16:50:40] [souls] tts engine started pid=17837 model=en_US-lessac-medium.onnx`, no `tts engine unavailable`. Engine set in config, not through the Eng... button. |
| 0 | Baseline status snapshot | PASS | S1 16:50:54-58: soul status (`system=on settingsValid=true ready=true provider=ollama model=llama3.1:8b providerHealthy=true queueDepth=0`), banter status (`Idle: last verdict: vetoed:cooldown, cooldown: 123s remaining. Active: ... no evaluation yet`), local status, all without error. |
| 1 | Solo remark delivers (1.1.181, 1.1.184) | PASS | S2 Bob despawned, `/bot soul banter now`: `[16:58:18] banter routingId=b605e996 ... rosterCount=1`, `scene ... rosterSize=1 ... lines=1`, Jake to the player "We should take a look at the nearby cave system...", `outcome=finished delivered=1/1`, no MALFORMED. Primed with banter now, so the 4-8 min natural grace was not timed. |
| 1 | Exactly one continuation (1.1.181) | PASS | S2 player answered unaddressed 22 s later: `[16:59:01] [souls] local ... outcome=fired routingId=8bfdcc55 score=3`, one LOCAL line from Jake, then nothing for the next 40 s. |
| 1 | Ignored window lapses silently | PASS | S2 remark a0e5e62a at 17:00:35 left unanswered: next souls line is `vetoed:cooldown`, no error or retry in the following 2 min. |
| 1 | Streaming first-word latency (1.1.175, still open) | FAIL | S2 by log (not by ear): DM sent 17:01:17, Ollama answer at 17:01:23 (`promptEvalMs=4386`), first segment synthesised `synthMs=545` at 17:01:24, about 7 s. Second DM 17:01:33 to first segment 17:01:38, about 5 s. Piper itself is fast (70-565 ms per sentence); the wait is the 8B model. Above the ~3 s target. |
| 2 | Banter fires with 2 bots | PASS | S1 `[16:51:13] [souls] banter routingId=24e11327... outcome=fired rosterCount=2`, playback 4/4, Jake and Bob each spoke twice. |
| 2 | Distinct display names keep the scene coherent (1.1.176) | PASS | S1 routingId 24e11327: each `scene-playback ... speaker=` UUID matches the chat name (Jake 2af3f0a5, Bob 6a00845c), no dropped unknown speaker. |
| 2 | No same-speaker-twice runs (1.1.197) | FAIL | S2 banter 7fe07d6f (17:40:48-54): `line=1/2 speaker=2af3f0a5` then `line=2/2 speaker=2af3f0a5`, Jake twice in a row, not merged. Other scenes alternated. Finding 3. |
| 2 | Nobody answers for the player (1.1.195) | PASS | S2 the model wrote lines as "RotiWokeman:" in 4 scenes (tap); each was cut before playback: `dropped rawLine=4 reason=OWNER_TAGGED` (17:18:16), two more at 17:21:18, one at 17:21:48. No delivered line speaks for the player. |
| 2 | `/bot soul banter now` primes both lanes (1.1.182, 1.1.188) | PASS | S1 `/bot soul banter now` at 16:51:11, `[16:51:13] banter lane=IDLE ... outcome=fired`; banter status lists both the Idle and Active lanes. |
| 2 | Combat aborts a scene (1.1.177) | PASS | S2 zombies summoned as banter 31cbc3eb fired: `[17:43:41] scene-playback routingId=31cbc3eb... outcome=ambient-combat-abort`, `outcome=cancelled delivered=0/1`. |
| 3 | Toggle off is fully dark | PASS | S2 `/bot soul local off` (17:25:41), three unaddressed lines over 35 s: zero `[souls] local` lines. DM replies kept working all session (e.g. 17:30:54). |
| 3 | Salience moves with the line | PASS | S2 `/bot soul local now` before each line: "the sky is grey" `vetoed:hard-reject` score 0, "I found diamonds and a skeleton spawner..." `vetoed:salience` score 1, "does anyone know where we can find iron around here?" score 3 (status). The veto and score follow the content. None crossed the firing line outside a reply window. |
| 3 | One continuation, then nothing | PASS | S2 the 16:59 reply drew exactly one LOCAL line (8bfdcc55); the 17:00:35 remark left unanswered lapsed with no line, error or retry. |
| 4 | "bots, …" two-bot scene | FAIL | S2 three "bots,/everyone" lines with both bots eligible (`scene-routing ... candidates=2 eligible=2`, 17:04:58, 17:15:05, 17:44:45): each PARTY scene had `lines=1`, one bot spoke. The tap shows the model (llama3.1:8b) wrote only one line. Finding 2. |
| 4 | A group scene resets the banter timer | PARTIAL | S2 banter status before the PARTY scene: next-eligible about 17:56:22; after it (17:45:12): "592s remaining", about 17:55:04. The timer was re-armed (it jumped) but landed earlier, not later. |
| 5 | Catalogue and bindings | PASS | S1 `/bot soul voice list`: Piper voices folder lists en_US-lessac-medium and en_US-ryan-medium, catalogue marks ryan `[installed]`, and the bot-keyed view lists `Bob [frens:bob] → global voice`, `Jake [frens:jake] → global voice`. |
| 5 | Per-bot assignment actually separates the voices (the 1.1.190 → 1.1.192 bug) | PASS | S2 `/bot soul voice assign bob en_US-ryan-medium`: "Bob → voice en_US-ryan-medium", Bob's next DM started a second engine `[17:30:54] tts engine started pid=27979 model=en_US-ryan-medium.onnx`, no `not found ... using the default voice`. Jake's next line used the existing lessac engine (no new start). Checked by log, not by ear. |
| 5 | Revert | PASS | S2 `/bot soul voice assign bob default`: "Bob now uses the global voice.", Bob's next line spoke at once (17:31:33) with no engine start or reload. By log, not by ear. |
| 5 | Unknown engine id fails loudly (1.1.198) | PASS | S1+S2: every `tts engine started` line is the configured Piper (`model=en_US-lessac-medium.onnx`, ryan after the assign), no fallback line. An unknown engine id was not set on purpose. |
| 6c | Crafting with planks only inside a bundle (1.1.204) | PASS | S3 Jake holding only a bundle of 12 oak planks and 2 sticks, `/bot craft wooden_pickaxe`: "Crafted 1 Wooden Pickaxe.", no planks refusal; after it the bundle is empty and 9 planks sit loose (all 12 extracted first, 3 used). |
| 6c | Bundled food counts (1.1.204) | PASS | S3 Jake's only food 5 bread in a bundle, `/bot set hunger 6 Jake`: within 15 s food 20 and 3 bread loose beside the bundle, no `Can't keep up`. |
| 6c | Wedged bot: pillar reason + 30 s back-off (1.1.204, P1 escape) | FAIL | S3 fresh bot Wedge guarding in a 1x1x2 hole with 16 cobblestone, player 60 blocks away, day: every 30 s `pillar recovery placed no blocks reason=aborted steps=6` (x3), then `Guard escape: Wedge failed — cooldown 30s`. The spacing is 30 s, but the reason is always `aborted`, and with the cover removed (17:59:15) the staging walk and pillar still end `aborted`, so it never climbs out. Before that the escape was suppressed by "commander nearby", "post-task grace" (10 min after any task) and "nighttime". Finding 5. |
| 6d | Hand-bundled better pickaxe gets pulled (1.1.205) | FAIL | S3 stone pickaxe loose, diamond pickaxe in a bundle, `/bot skill mining` into a placed stone block: every `Mining ... with minecraft:stone_pickaxe` (6 blocks), the diamond pickaxe stayed in the bundle. Finding 6. |
| 6e | Mining first-swing hitch is one tick, not more (1.1.206) | PASS | S3 only an iron pickaxe, in main inventory slot 11: the first swing already logs `Mining ... with minecraft:iron_pickaxe` and the pickaxe ends in hotbar slot 0; no `server-thread hop failed`, `on-thread step failed` or `Can't keep up`. The `Swapping best tool` line is not logged at INFO. |
| 6f | `/bot direction reset` is the real name (1.1.207) | PARTIAL | S3 `/bot direction reset Jake` exists and answers "Jake has no stored work direction." Setting a direction and the next strip mine were not run. |
| 6f | Crafting refusal says exactly what's missing (1.1.207) | FAIL | S3 Jake with 1 white wool and 10 planks, `/bot craft bed`: in the Spawn base it crafted a crafting table, failed to place it (`claim-denied protected-zone`) and said only "I crafted a crafting table but couldn't place it here."; outside the base it crafted and placed a table first (4 planks spent), then said "Can't craft bed: need 2 more matching wool". The torch half passed: 2 sticks, no coal, `/bot craft torch 4` gave "Can't craft torch: need 1 more coal or charcoal" and crafted nothing. Finding 4. |
| 6f | Unknown craft name speaks once per 30 s (1.1.207) | FAIL | S3 `/bot craft spaceship Jake` three times in 6 s, then once 40 s later: "I don't know how to craft spaceship." all four times. Finding 7. |
| 6g | Home data survives a normal quit (1.1.208, debounce) | PASS | S3 `/bot base set QuitBase` then save-and-quit within the same second: `bot_home_data.json` mtime moved to the quit second and holds QuitBase, no `frens-bot-home-writer` exception; after relaunch `/bot base list` shows `QuitBase @ 120, 103, -58`. |
| 6j | Toggle defaults off and round-trips (1.1.211) | PASS | S2 `/bot soul novelty status` "Novelty rejection is OFF", `on`, status "is ON", settings.json5 `"soulNoveltyRejectionEnabled": true`. Singleplayer only; the LAN/dedicated sync half was not run. |
| 6k | Peer entry appears after shared scenes (1.1.212) | PASS | S1/S2 `peer stance bot=Jake peer=Bob trust=4 exasperation=0 curiosity=3` and the Bob mirror (16:51:47). mind.json: Jake's `peerStances` has one entry keyed `6a00845c...` (Bob's UUID), Bob's keyed `2af3f0a5...` (Jake's), values `trust 4, exasperation 0, curiosity 3`, trust off baseline. |
| 6k | Bumps are once per day per pair (1.1.212) | PASS | S2 day 0: Jake's trust moved once (16:51:47) and curiosity once (3 to 4 at 17:23:00); scenes at 17:18, 17:20, 17:21, 17:22 logged no `peer stance` line. The next change came after the day-1 rollover (`lastTrustDay: 1`). |
| 6k | Day rollover decays toward baseline (1.1.212) | PARTIAL | S2 the day-1 rollover ran (`mind consolidated bot=... day=1`, 17:23:58/17:24:06). After it both peer entries read `trust 4, curiosity 3` with `lastTrustDay: 1`: Jake's curiosity 4 moved to 3 (one step), trust was bumped again on day 1 in the same minute, so its decay can't be told apart. |
| 6l | Toggle default and round trip (1.1.213) | PASS | S2 "Relation facts are OFF", `on`, "Relation facts are ON", settings.json5 `"soulRelationsEnabled": true`. |
| 6m | Toggle default and round trip (1.1.214) | PASS | S2 "Structured output is OFF", `on`, "Structured output is ON", settings.json5 `"soulStructuredOutputEnabled": true`. |
| 6m | Contract reaches the prompt only when enabled (1.1.214) | PASS | S2 loopback tap (`tools/sandbox/ollama_tap.py`): the 19 requests before the toggle (16:51-17:16) have no `##FRENS` paragraph, the first scene after it (17:18:15) has "You may end your reply with one extra line starting with ##FRENS". |
| 6m | Side channel is emitted at all (1.1.214) | FAIL | S2 structured on, "Jake, I hate the Nether" then 6 banter scenes (17:18-17:24, llama3.1:8b): no `[souls] side-channel` line, and the tap shows no reply ending in a `##FRENS` line. The 8B model ignores the optional tail. Per the item, the clerk-call fallback needs scheduling in RALPH_TASK.md. |
| 6m | `##FRENS` is never spoken (1.1.214) | PASS | S2 structured on, 6 scenes: 0 chat lines contain `##FRENS` (the model never wrote a tail either, see the side-channel item). |
| 6m | Toggle off is inert (1.1.214) | PASS | S2 `/bot soul structured off` (17:44:38): the next two requests (17:44:49, 17:46:26) carry no `##FRENS` contract (tap), no `side-channel` line. No INFERRED fact existed to check storage. |
| 6o | Voices are warm before the first scene (1.1.217) | PASS | S2 `[16:57:40] tts prewarm voices=2` and `tts engine started pid=19661` 2 s after the bots spawned, 40 s before the first scene. Line 1 vs line 2 speed not judged by ear. |
| 6o | Ollama usage is logged (1.1.217) | PASS | S2 32 `[souls] ollama usage model=llama3.1:8b promptTokens=... evalTokens=...` lines, one per call. Largest `promptTokens=1731` (digest), `numCtx=8192`, no `nearCtxLimit=true`. |
| 6p | Schematic builder leaves through the doorway (1.1.218) | PASS | S3 `/bot skill build small_hut` (with 8 torches, which it requires): `[egress] ... door=121,-61 src=DESIGNED_GAP waypoints=3 ... outcome=ok` twice, no `outcome=failed`. The site was on a tree canopy, so only 37/86 blocks went in ("49 blocks couldn't be placed"), a test-site problem. |
| 6q | A follow-up without a name reaches the same bot (1.1.219) | PASS | S2 "Jake, how is the mining going?" then "ok, and after that?" 16 s later: `[17:01:33] [souls] dm follow-up routed player=RotiWokeman bot=Jake ageMs=10169`, Jake answered in the same thread, no "Processing your message", no refusal. |
| 6q | The window closes after 30 s and on a new address (1.1.219) | PASS | S2 an unaddressed line 64 s after Jake's reply got only `local ... vetoed:cooldown`, no `dm follow-up routed`. Then "Bob, hi" and an unnamed line 14 s later: `[17:03:25] dm follow-up routed ... bot=Bob ageMs=9048`. |
| 6q | "Guys" and "everyone" reach both bots (1.1.219) | PARTIAL | S2 "hey guys, what's the plan?" `scene-routing ... candidates=2 eligible=2 outcome=submitted` (17:04:15, 17:14:22), no "Processing your message". Only Bob spoke (`lines=1`, finding 2). The party-OFF half was not run. |
| 6q | A named bot beats a soft word (1.1.219) | PASS | S2 "hey guys, Jake come here" (17:10:44): one `[souls] routing ... bot=2af3f0a5` (Jake), no scene-routing line, only Jake answered ("I'm here. What's up?"). |
| 6q | Trailing name and comma lists (1.1.219) | PASS | S2 "where did Bob put the torches, Jake?" routed to Jake only (17:11:27); "Jake, Bob come here" gave `scene-routing ... candidates=2 eligible=2` (17:15:47, after Bob was rebound); "Bob, Jake says the chest is full" routed to Bob only (17:16:29). |
| 6s | Do this first: mark your supply chest Always (1.1.221) | PARTIAL | S3 Always works for food: `choice=always result=GRANTED_ALWAYS` (18:12:53), the next take `outcome=COVERED_BY_ALWAYS ... MOVED` with no prompt. The axe case never prompted: `/bot skill woodcut` with no axe beside a chest of 2 stone axes punched a tree ("No axe yet, so I'll punch a tree first.", the 1.1.229 bootstrap) and logged no `[supply]` line. Finding 8. |
| 6s | Allow once really lets him take it (1.1.221) | PASS | S3 (bread, not the axe/sword kit): `answer ... choice=once result=GRANTED_ONCE` at 18:07:02, `withdraw ... result=MOVED moved=2` at 18:07:08 (6 s), no second prompt for it. Also planks for a craft: once at 18:16:55, `MOVED` at 18:16:58. |
| 6s | No means no (1.1.221) | PASS | S3 `food 10 no 70`: `choice=no result=REJECTED`, then `withdraw ... REFUSED reason=NOT_PERMITTED`, and 68 s later `request ... outcome=REJECT_COOLDOWN` with no new prompt. Nothing moved. Three minutes later the same chest still answered REJECT_COOLDOWN. |
| 6s | A hungry bot asks for food above the reserve (1.1.221, reserve 8 since 1.1.268) | PASS | S3 `tools/sandbox/supplytest.py food 10`: `[18:07:00] [supply] request bot=Jake ... item=minecraft:bread qty=2 ... outcome=OPENED`, answered once, `withdraw ... result=MOVED moved=2`, 8 left. `food 8`: no request line at all. |
| 6s | A full inventory never freezes the game (1.1.221) | PASS | S3 Jake holding 36 stacks of cobblestone, Always chest with 24 bread: `transfer ... status=NO_ROOM`, `Dropped 64x Cobblestone to free inventory space.`, `MOVED moved=2`, `Mutual aid: Jake took 2x Bread`, all in the same second (18:16:04). No `Can't keep up!`. |
| 6s | Crafting and seeds from a chest (1.1.221, new behaviour) | PARTIAL | S3 craft half: the first `/bot craft stick` asked (`purpose=craft-planks ... WAITING`) and answered "Sticks need 2 planks per craft."; after [Allow once] the second run `withdraw ... MOVED moved=2` then "Crafted 4 Sticks." The harvest seeds half was not set up. |
| 6s | Revoke all (1.1.221) | PASS | S3 after an Always: `[18:14:14] [supply] revoke-all owner=RotiWokeman removed=1 grants=0`, chat "Revoked 1 standing permission(s)...", and the next hunger take prompted again (`outcome=OPENED` 18:14:24). |
| 6t | Idle stone upgrade with a table in reach (1.1.222) | PARTIAL | S3 with the item's kit (wooden pickaxe and shovel, 3 cobblestone, 2 sticks, table 2 blocks away, idle hobbies on) nothing crafted in 100 s: every 12 s `Idle wooden fallback: Jake blocked — no safe woodcut target near or ahead`, which runs before the stone upgrade and returns first (finding 9). With a wooden axe and sword added: `[18:20:19] Crafted 1 pickaxe(s) using stone ...`, `Idle stone upgrade: Jake upgraded wooden tools to stone`, at the table in reach, no walk, no `Can't keep up!`. |
| 6u | `/bot craft stone_axe` names what's missing (1.1.223) | PASS | S3 3 sticks only: "I need 3 cobblestone and 2 sticks for a stone axe. I have 3 sticks."; with 3 cobblestone added: "Crafted 1 Stone Axe.", no "I don't know how to craft". |
| 6v | Startup permission mode (1.1.224) | FAIL | S2/S3 every launch: `[PermCheck] operator-permission mode=permission-all-mapped-fallback class=net.minecraft.class_12096 field=ALL/field_63208`, never `leveled-mapped ... OWNERS`. Known since 1.1.232 (checklist note 2026-10-18); investigated in the separate "why bot permissions use the fallback" session, not here. |
| 6v | Diagnostics switch (1.1.224) | PASS | S2 `/bot config diagnostics off` with Jake following: 0 `Follow status:` lines in 30 s (0 in the whole log before it). `on`: 25 lines in the next 40 s. |
| 6w | Scene logs carry no private text (1.1.225) | PASS | S2 diagnostics on, after the "I hate the Nether" DM: banter, scene and playback lines show only ids, counts, `seedChars=`, `privateSeedCount=`, `dropped rawLine=N reason=... chars=`. The only log line with the DM text is vanilla's own `[Not Secure] <RotiWokeman>` chat line. |
| 6x | Auto-spawn after the host joins (1.1.226) | PASS | S2 reopening Sweep Souls with Jake and Bob saved: `[16:57:35] Auto-spawn deferred until the first real player joins (integrated server).`, then `RotiWokeman[local] logged in` (16:57:38) and only after it `Spawned new bot Jake!` / `Bob`, at their saved spot. |
| 6x | Too many saved bots never lock the host out (1.1.226) | PARTIAL | S3 7 saved bots (Jake, Ash, Birch, Cedar, Dale, Elm, Fir), quit and reopen: host joined with no "server is full", `Auto-spawn deferred until the first real player joins`, then all 7 spawned at 18:51:50 (8/8). No `Auto-spawn skipped ... 1 slot(s) kept free` line and no chat notice: with the host already in, `effectivePlayerCount` counts it, so the reserve no longer leaves a bot out. The pass text (6 bots) looks stale since auto-spawn waits for the host; Bradley's call. The six bots spawned seconds before the quit came back at world spawn (`inventory-load-skipped reason=missing-snapshot`). |
| 6aa | Woodcut from nothing (1.1.229) | PASS | S3 Jake empty in the forest, `/bot skill woodcut`: `Woodcut axe bootstrap: Jake has no axe, punching logs bare-handed`, chat "No axe yet, so I'll punch a tree first.", first punch about 4 s later, `has enough wood (logs=3 planks=0 sticks=1), crafting an axe` (18:26:24), `crafted an axe, back to normal woodcut`, then normal felling. It used an existing table 45 blocks away instead of placing one. The `admin-zone` rejects are an old zone at 59,-112, not the Spawn base. No A* timeout. |
| 6aa | Follow comes back after a task, no "Busy" (1.1.229) | PASS | S3 Jake following, `/bot skill woodcut 4 Jake` ran four trees and ended: `[18:34:53] [FollowAssert] task-resume bot=Jake mode=follow ... resumed=true result='Now following RotiWokeman.'`, no "Busy with another task" and no "Stopping follow command." |
| 6aa | No axe wear on dirt (1.1.229) | PASS | S3 two woodcut runs with an axe and dirt: every dirt pillar and bridge block came down `with minecraft:dirt` in hand (300-750 ms), never the axe. Note: plank bridge blocks were also mined with planks in hand, some at about 3000 ms each, not with the axe (finding 10). |
| 6ae | Leaves drop saplings, teardown uses hands (1.1.233) | PASS | S3 Jake with stone pickaxe, iron axe, shears and 32 dirt woodcut two trees with a 2-step dirt pillar: the dirt teardown was mined `with minecraft:dirt` in hand, stone pickaxe `damage 0` before and after (shears also 0). Plank scaffold was not part of this run. |
| 6af | Drop sweep, return and craft feedback (1.1.234) | FAIL | S3 drops spread around Jake, `/bot skill drop_sweep Jake`: chat "Picked up 27 items.", but the skill's own summary says `collected: 20` and Jake's bag (cleared before) holds 21 items (13 saplings, 5 logs, 2 apples, 1 stick). No fast-travel error, no return trip. Finding 11. |


## Items without a verdict

### Needs Bradley: a second player, LAN guest or server op (23)

- 3: **A fired reaction end to end, audible to a bystander**. needs a second player as the bystander.
- 6b: **Sync on join**. needs host H and guest G.
- 6b: **Guest edit is rejected and reverted**. needs host H and guest G.
- 6b: **Op edit propagates**. needs host H and guest G.
- 6b: **Per-player mute (the point of 1.1.203)**. needs host H and guest G.
- 6b: **Guest's local settings survive the visit**. needs host H and guest G.
- 6q: **A line for another person stays with them (1.1.219, needs a second player)**. needs a second player, LAN guest or server op.
- 6r: **Another player can't use your bot's storage (1.1.220, needs a second player)**. needs a second player, LAN guest or server op.
- 6v: **2P: a stranger can't touch your bot (1.1.224)**. needs a second player, LAN guest or server op.
- 6v: **2P: a DM stays private (1.1.224)**. needs a second player, LAN guest or server op.
- 6v: **2P: hidden bases stay hidden (1.1.224)**. needs a second player, LAN guest or server op.
- 6w: **2P: a non-operator gets told, not ignored (1.1.225)**. needs a second player, LAN guest or server op.
- 6w: **2P: a remote operator's picks reach the server (1.1.225)**. needs a second player, LAN guest or server op.
- 6ac: **A non-op owner commands their own bot (1.1.231)**. needs a second player, LAN guest or server op.
- 6ac: **A non-op cannot command someone else's bot (1.1.231)**. needs a second player, LAN guest or server op.
- 6ac: **Fortify respects wall ownership (1.1.231)**. needs a second player, LAN guest or server op.
- 6ac: **Feed [Yes]/[No] and resume prompt (1.1.231)**. needs a second player, LAN guest or server op.
- 6ac: **Hunt target pick (1.1.231)**. needs a second player, LAN guest or server op.
- 6ad: **Ops see the Admin tab on a server (1.1.232)**. needs a second player, LAN guest or server op.
- 6ad: **Companion owner after a recruitment reset (1.1.232)**. needs a second player, LAN guest or server op.
- 6ad: **Wall grantees fortify (1.1.232)**. needs a second player, LAN guest or server op.
- 6ae: **Unowned bots refuse Set Home / Go To for non-admins (1.1.233)**. needs a second player, LAN guest or server op.
- 6ae: **Skin toggle gone (1.1.233)**. needs a second player, LAN guest or server op.

### Needs Bradley: a judgement by ear or eye, or his call (5)

- 2: **Solo-banter suppression judgement (1.1.181 open question)**. design call, Bradley's judgement.
- 2: **Governor floor during a scene (1.1.176)**. frame stall is reported by the player.
- 5: **Pocket voice quality vs Piper**. subjective voice-quality verdict.
- 5: **Bob still shares Jake's Dreamsleeve clone (known open, 1.1.184)**. Dreamsleeve clone comparison by ear.
- 6w: **Model button and labels (1.1.225)**. needs `ollama rm` of a model on the shared Ollama; Bradley's call.

### Needs Bradley: Nirn-only data (3)

- 6k: **Old minds load clean (1.1.212)**. needs pre-1.1.212/1.1.213 mind.json files; the sandbox has none and Nirn migrated long ago.
- 6l: **Old minds load clean (1.1.213)**. needs pre-1.1.212/1.1.213 mind.json files; the sandbox has none and Nirn migrated long ago.
- 6aa: **Your base's trees still protected (1.1.229)**. Bradley's own base on Nirn.

### Needs Bradley: the mod downloads from the network (4)

- 5: **Install a voice**. the mod downloads Piper/voices/Pocket from the network; Claude does not start downloads unasked.
- 5: **Background install survives menu close/reopen (1.1.175)**. the mod downloads Piper/voices/Pocket from the network; Claude does not start downloads unasked.
- 5: **Piper install retry on macOS (1.1.175 / 1.1.172, still open)**. the mod downloads Piper/voices/Pocket from the network; Claude does not start downloads unasked.
- 5: **Pocket TTS installer (1.1.198)**. the mod downloads Piper/voices/Pocket from the network; Claude does not start downloads unasked.

### Not reached: fortify village build (11)

- 6h: **Tower build unchanged (1.1.209)**. fortify village build.
- 6h: **Deferred cleanup still drains (1.1.209)**. fortify village build.
- 6h: **Tower patch unchanged (1.1.209)**. fortify village build.
- 6h: **Merge verb still expands (1.1.209)**. fortify village build.
- 6h: **Tower replan guard unchanged (1.1.209)**. fortify village build.
- 6h: **No new log noise (1.1.209)**. fortify village build.
- 6i: **Corridor carve unchanged (1.1.210)**. fortify village build.
- 6i: **Carve repair still drains (1.1.210)**. fortify village build.
- 6i: **Trap-escape carve unchanged (1.1.210)**. fortify village build.
- 6i: **Refused carve still summarised (1.1.210)**. fortify village build.
- 6i: **No new log noise (1.1.210)**. fortify village build.

### Reworded by Bradley's triage, not run (3)

- 6g: **Woodcut cleanup line-of-sight leaf clearing unchanged (1.1.208)**. reworded 2026-10-18 (triage), open for the part never tested; not set up in this sweep.
- 6ad: **Skins are owner-checked (1.1.232)**. reworded 2026-10-18 (triage), open for the part never tested; not set up in this sweep.
- 6ai: **Admin toggle lets bots cut Spawn trees (1.1.238)**. reworded 2026-10-18 (triage), open for the part never tested; not set up in this sweep.

### Not reached in this sweep (119)

- 1: llama3.2:3b speed vs llama3.1:8b (1.1.175, still open); Failed generation retries (1.1.184).
- 2: ~1/3 of banter ends addressed to the player (1.1.181); Active lane fires on work (1.1.188); Pacing sliders (1.1.188); Walking away / bystander earshot (1.1.176); Cooldown spacing over the session.
- 3: Addressing a bot mid-window closes it; Danger veto; Server-thread cost under load (1.1.178, never measured); Roster-lost backoff (1.1.179).
- 4: Voice-off beat pacing.
- 6: Category-muting round-trip (1.1.175); The separation proof (1.1.182); Ambient mute matrix on banter/local (1.1.177, 1.1.178); `vetoed:muted` only in the right condition (1.1.182); Two lanes, two masters (1.1.193); Live-settings apply (1.1.184); Global toggles UI (1.1.189, 1.1.191); At most one bot comments on the same thing (1.1.184).
- 6b: Cheats-off single-player still saves (1.1.203, review fix); Mute screen sends a mask, not a global save (1.1.203); RAM warning on the Download button (1.1.203); Dreamsleeve row on macOS (1.1.203).
- 6c: Spells key opens the inventory Spells tab (1.1.204); Silas persona (1.1.204); Travel-wait: hobby during a fast-travel cooldown (1.1.204, P1 idle); Travel-wait cancels on stop (1.1.204); Hotbar-lock combat honesty (1.1.204, side effect).
- 6d: Bundled cobblestone still gets a pillar (1.1.205); Bundled armor equips only if better (1.1.205); Boat + out-of-hotbar sword, FarmSkill/RideSync variants (1.1.205); Travel-wait offload to an existing chest (1.1.205); Offload backoff on a full chest (1.1.205); `/bot stop` during the offload does not poison later offloads (1.1.205); Furnace fuel fallback (1.1.205).
- 6e: Swap re-validation is rare (1.1.206); Full unlocked hotbar keeps the selected slot (1.1.206); `/bot stop` during an offload does not disable later offloads (1.1.206).
- 6f: Actions tab has no Regroup; Spells tab still does (1.1.207); Remembered fishing spot (1.1.207); Wider fishing scan (1.1.207); Farm reuses its irrigation source (1.1.207); Per-kind cap keeps fishing memory (1.1.207).
- 6g: Leaf-blocked navigation unchanged (1.1.208); Bridge scaffold still clears snow (1.1.208); CollectDirt ladder exit unchanged (1.1.208); Home data write is batched (1.1.208, debounce).
- 6j: Novelty fires and does not gag the bots (1.1.211); Silas stays laconic (1.1.211); Quoted short idiom is not a repeat (1.1.211); Toggle off is silent (1.1.211).
- 6k: Peer clause reaches the prompt (1.1.212).
- 6l: SEEN fact forms from repeated completions (1.1.213); BELIEFS block reaches the prompt (1.1.213); Relation anchor fires and goes on cooldown (1.1.213); Toggle off is inert (1.1.213).
- 6m: A malformed tail never costs a scene (1.1.214); A model fact lands as INFERRED at ≤0.6 (1.1.214); Model stance delta is peers-only and once per day (1.1.214).
- 6n: No two bots talk over each other (1.1.216); Scripted lines do not interleave with a soul scene (1.1.216); A scene still gets through a scripted floor (1.1.216); The two banter lanes respect one quiet period (1.1.216); Muting scripted dialogue does not mute souls (1.1.216); Mount and animal lines stop repeating (1.1.216); A hurt wolf still gets an answer (1.1.216); The woodcut storm is gone (1.1.216); The backoff ladder widens (1.1.216); An axe in a chest wakes it back up (1.1.216); Commanded work still starts immediately (1.1.216).
- 6o: Torch stays up in the dark (1.1.217); Quit mid-hold does not reload with a torch in hand (1.1.217); Scripted lines hold off while a scene is generating (1.1.217); Voice on, text off still arms the floor (1.1.217); Scripted logs tell the truth (1.1.217); Model switch keeps voices alive (1.1.217, Codex 7a663785); Group-chat hint appears once (1.1.217, Codex 43bd5474).
- 6p: Hovel builder uses the door from the wall line (1.1.218); No fall off a raised floor (1.1.218); Line-of-sight retries stay local (1.1.218); `/bot stop` mid-egress stops cleanly (1.1.218).
- 6q: Handled lines don't become DMs (1.1.219); Bot-placed chests record their owner (1.1.219, supplies groundwork); Supply commands are safe for non-ops (1.1.219).
- 6r: Collect still works for the owner (1.1.220); Go and Dismiss still work for the owner (1.1.220); Quick Store / Quick Fetch still work for the owner (1.1.220).
- 6s: Walking away keeps the log quiet; coming back works at once (1.1.221); Old bot chests prompt instead of refusing (1.1.221); Never-allowed items stay put (1.1.221).
- 6t: A far table: no walk, no freeze, one quiet line (1.1.222); A table in his bag gets placed beside him (1.1.222); `/bot craft` still walks to a far table (1.1.222, unchanged); A starving bot with a full bag drops junk, not a chest walk (1.1.222); Gear: the full bot only makes room when there is something to give (1.1.222); A flower never makes a full bot drop anything (1.1.222).
- 6u: Out through the plate gate (1.1.223); The bot leaves the plate's gate alone (1.1.223); Ordinary doors still get closed behind the bots (1.1.223); No server-thread walk warning (1.1.223 tripwire); A scene that mentions you plays in full (1.1.223); Crafting window search and sort (1.1.223).
- 6v: Your own bot still works everywhere (1.1.224).
- 6w: Setup checklist, cheats-off singleplayer (1.1.225); Enable message names the blocker (1.1.225); Checklist fits a small window (1.1.225); Bot Controls keeps a Soul Chat change (1.1.225).
- 6y: Spawn and command with commands off (1.1.227); Roster and hints (1.1.227).
- 6z: Wide unchanged (1.1.228); Narrow fits (1.1.228); Scaled inventory clicks (1.1.228).
- 6aa: No crevice suffocation on pickups (1.1.229); Unreachable drop given up fast (1.1.229).
- 6ab: Bot says why it skips your base trees (1.1.230).
- 6ac: Buttons still work for the host (1.1.231).
- 6ag: Questing hidden (1.1.235).
- 6ai: Own base over spawn still works (1.1.238).

## Test-method notes

- **1.21.11 renamed the day-cycle gamerule.** `/gamerule doDaylightCycle false` answers "Incorrect argument"; use
  `/gamerule advance_time false` (abtest.py and gototest.py try both; sheltertest.py still uses only the old name). S2 ran with the cycle
  on, which is why a day-1 rollover and phantoms happened mid-session.
- **`send_chat` leaves the chat screen open, which pauses the server.** `ft.py chat` warned on its next call. Press escape after
  every chat line (`ft.py cmd press_key key=escape`), as `tools/sandbox/supplytest.py` does.
- Scene playback is not logged as `tts correlationId=` lines; only DM replies are (`SoulVoiceService.runSynthesis`). Use DMs for TTS timing.
- The guard escape is gated by `shouldSuppressHobbyEscape`: the owner within range, night, or any task finished in the last 10 min
  blocks it. Test with a fresh bot, by day, with the player far away.
- `/bot skill build <schematic>` (not `/bot build`); small_hut needs a torch. `/bot idle_hobbies on <bot>` turns hobbies on.
- Supply prompts: `/frens supply answer <id> once|always|no` inside 30 s; a "No" holds that chest and item for more than 3 minutes
  (`REJECT_COOLDOWN`), so move the chest (`SX=` in supplytest.py) between runs.
- New tools: `tools/sandbox/ollama_tap.py` (prompt tap), `tools/sandbox/supplytest.py` (6s prompts).
