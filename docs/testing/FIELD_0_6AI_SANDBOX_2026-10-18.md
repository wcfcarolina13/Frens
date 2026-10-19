# Field phases 0-6ai in the sandbox: Frens 1.1.270, driven by Claude (2026-10-18)

Step 2 of Bradley's field-checklist drain plan (2026-10-18). Claude swept the open items of phases 0 to 6ai of
`FIELD_SESSION_1.1.202.md` in the `mcp-sandbox` Prism instance over the MCP API, alone, on `frens-1.1.270-release+1.21.11.jar`
(sessions 1-3), then `frens-1.1.271-release+1.21.11.jar` (session 4, copied from Nirn's `mods/`, md5 ec6d16a7..., sandbox closed). Nirn was never launched. The checklist was swept against the
text after Bradley's triage rulings (`124595f7`). Phases 7-10 are out of scope (they come after 6ai).

**Counts in `FIELD_SESSION_1.1.202.md`:** before 267 open, 95 done; after **208 open, 154 done** (`grep -c '^- \[ \]'` and `'^- \[x\]'`).

239 open items were in scope. 78 got a verdict: **59 PASS (ticked), 9 FAIL, 10 PARTIAL**. 49 need Bradley or were reworded
for a part not set up (listed below with the reason, all already in `GUIDED_SESSION_BRADLEY.md`), 13 more are listed in that
guided doc and were left to it, and 99 sandbox-checkable items were not reached. Every item with a verdict
also got a `Sandbox 2026-10-18:` sub-line in the checklist. No high-severity finding: nothing stops a feature for a whole session,
apart from finding 5 (a guarding bot wedged in a hole never climbs out).

Sessions (each save a fresh copy made with `tools/sandbox/worldcopy.py` while the game was closed):

| Session | Save | Used for |
|---|---|---|
| S1 (16:49-16:52) | `Sweep Souls`, copy of `New World`, commands on | Phase 0, first banter |
| S2 (16:57-17:47) | `Sweep Souls` again | souls: phases 1-6, 6j-6o, 6q, 6v-6x |
| S3 (17:47-18:51) | `Sweep Old`, copy of `New World`, commands on, plus one relaunch | crafting, bundles, guard escape, supplies, idle upgrade, woodcut, drop sweep, build, home data, auto-spawn |
| S4 (18:57-19:20, 1.1.271) | `Sweep Old2`, copy of `New World`, commands on, Flashback recording | 6v permission line, 6t full bag, gear, flower, far table; supply commands; unreachable drop; fishing memory |

Setup: Piper came from Nirn's installed `config/frens/piper/` folder, copied into the sandbox config (read-only use, no download).
Souls ran on Ollama `llama3.1:8b` through `tools/sandbox/ollama_tap.py`, a loopback tap that records each `/api/chat` request,
so the prompt checks (contract, BELIEFS, CURRENT STATE) could be read. The sandbox config points back at 11434 again.
`/bot config diagnostics` was left on and the soul novelty/relations/structured toggles on in the sandbox config.

**Replays (S4 only; S1-S3 ran before the recording rule):** in `mcp-sandbox/minecraft/flashback/replays/`, named in each S4 row.
- `frens_1_1_271_6t_full_bag_gear_craft.zip` (18:58-19:06): the junk drop and bread pickup at about 19:00:2x, the sword hand-over between Jake and Bob at 19:02:35, Jake walking 15 blocks to a table and crafting at 19:06:04-08. Best visual moment: the sword hand-over.
- `frens_1_1_271_6q_6aa_supply_cmds_and_drops.zip` (19:07-19:12): supply commands (chat only), the sapling on the leaves, then two trees felled by Jake at 19:11:3x-19:12:25 (good felling footage).
- `frens_1_1_271_6f_fishing_spot_memory.zip` (19:12-19:20): Jake casting into the pond under the overhang.

### Pass 3 sessions (1.1.271, same jar, md5 ec6d16a7...)

A third session took the sandbox lock (`field-0-6ai-pass3`) for the 99 items that pass 2 did not reach, then 6ak-6bi and 7-10.
Souls ran on `llama3.1:8b` through `ollama_tap.py` again, Piper as before, structured output on, Idle banter rate 70.

| Session | Save | Used for | Replay (in `mcp-sandbox/minecraft/flashback/replays/`) |
|---|---|---|---|
| S5 (19:28-21:03) | `Sweep Souls3`, copy of `Sweep Souls` (its `mind.json` files carried over), commands on, party moved to a snowy plain at -60, -60 | 3B vs 8B, three woodcuts then a rollover (beliefs, digest), banter x40, novelty, relations, group lines, local chat, danger, mount and wolf lines, beds, Phase 7 and 9 | A `frens_1_1_271_souls_A_banter_beliefs_digest.zip`. Good moments: 19:43:24 Jake's woodcut finishes; 20:16:54 Bob "You're good at woodcut"; 20:53:49 "Hey, leave the dog alone"; 21:01:37 both bots go to bed |
| S6 (21:04-21:15) | `Sweep Souls3` again; Active banter on and Scripted rate 100 set in config; Jake's stance seeded in `mind.json` | Active lane, two-lane quiet period, stance clauses, Rate... screen; ended by finding 15 | B `frens_1_1_271_souls_B_active_lane_save_config_kick.zip` (recovered after the disconnect). 21:06:55 the WORK scene while Jake fells a tree |
| S7 (21:17-21:42) | `Sweep Souls3`, config trimmed (finding 15), Bob's `mind.json` stripped of the 1.1.212/1.1.213 keys | Scripted rate 0, old-mind load, first-sighting restart check, Phase 10 resets; then muting and masters in Bot Controls | C `frens_1_1_271_souls_C_rate_oldminds_reset.zip` (21:28:35 the party reset cuts a scene), D `frens_1_1_271_6_6n_6o_muting_masters.zip` (21:38 masters off, scenes still play) |
| S8 (21:45-22:18) | `Sweep Old3`, copy of `New World`, commands on; Spells key bound to K in the sandbox options | boat combat, Pebble's guard escape (bundle, stone and bedrock lids), Jake's woodcut backoff ladder in the forest at 15 76 -110, supply chest checks, 6t tables, fishing scan, dark tunnel torch | E `frens_1_1_271_6c_6d_6n_6t_pillar_backoff_tables.zip`. 21:53:06-21:54:06 Pebble pillars out of the hole with bundled cobblestone; 22:10:30 Jake places his table and crafts |
| S9 (22:19-22:47) | `Sweep Old3` relaunched (quit mid torch hold), scripted masters back on | door follow, hovel and watchtower (diamond tools given so the builds go fast), Bot inventory buttons, Crafting window, Quick Store/Fetch, Storage screen | F `frens_1_1_271_6p_6r_6u_6ac_doors_builds_gui.zip`. 22:21-22:22 both bots stuck in the doorway, then through and closing it; 22:31:50-22:33:09 the watchtower |

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
    Fixed in 1.1.271 (`7dba5ee4`, static-init order): S4 on 1.1.271 logs `mode=leveled-mapped ... OWNERS`. The item is PARTIAL only for the Carpet `/player` half (no Carpet in the sandbox).
13. **`/bot craft` right after a failed table reach ignores a reachable table and burns planks (minor, needs repro).** S4 19:04:31: a table 15 blocks away
    on open ground, 6 planks: "No crafting table found; attempting to craft one.", crafted a table from 4 planks, failed to place it beside the bot,
    then "I need 3 planks and 2 sticks ... I have a crafting table but can't place it here." The previous run had walked to a buried table and failed
    ("I need a crafting table placed nearby"). 90 s later the same command walked to the table and crafted. A reach cooldown or the
    `LAST_KNOWN_CRAFTING_TABLE` entry is the suspect.
14. **The fishing spot search takes 18.7 s (minor).** S4 `/bot fish 1` beside a 7x7 pond: `Fishing spot search took 18729ms` before the first cast
    (the remembered spot on later runs is instant).

Pass 3 (1.1.271, sessions S5 on, see "Pass 3 sessions"):

15. **A settings change kicks the player once the config is bigger than 32 KB (high).** S6 21:15:43, moving the Scripted slider on
    the Rate... screen: `EncoderException: String too big (was 34859 characters, max 32767)` in `StringCodec.encode` for the
    `frens:save_config` payload, then "Connection Lost" and the world closed (singleplayer). The sandbox `settings.json5` had grown to
    72 KB compact: `botControlsByWorld` 36 KB and `botSpawnPointsByWorld` 16 KB over 45 bots times every world copy. Nirn's file is 10 KB
    today, so Nirn is safe for now, but these maps only grow with bots and worlds. After trimming the sandbox file to Jake/Bob/Silas
    (backup `config/settings.json5.bak-pass3-bloat`) the same slider saved fine.
16. **A reply that is only the `##FRENS` line costs the scene (medium).** S5 20:33:09, "bots, what should we do next?" with
    structured output on: `llama3.1:8b` answered with nothing but `##FRENS {"stance":...,"facts":[...]}`, so `accepted=false`,
    `failed:MALFORMED`, and the player got no answer. That was the only tail the 8B model wrote in about 30 scenes.
17. **Ambient chatter ignores the speech floor and the Scripted rate (medium).** `BotAmbientChatter` ("If you need me, I'm listening.",
    "teeth itch", weather and idle lines) never consults `SpeechFloorService` or `DialoguePacing`; only pet and context reactions do. In S5
    those lines landed inside soul scenes (20:04:49, 20:05:30, 20:06:06, 20:07:36), in the second a scene fired (20:05:59, 20:06:50),
    and within 4 s of another bot's line 27 times (two bots in the same second at 20:21:49). At Scripted rate 0 (S7) Jake and Bob still
    said 10 ambient lines in 4 min. Behind four FAILs and the PARTIAL on the Rate slider.
18. **The local danger veto does not see a zombie beside the bot (medium).** S5 easy, zombies summoned 2 and 8 blocks from Bob:
    local fired (`score=5`) at 20:41:52 and 20:42:46 and the zombie is absent from that scene's prompt, while Jake's combat code
    reacted to it 9 s later. Summoned zombies were invulnerable (so the bots would not kill them first); a natural mob may differ.
19. **Novelty can empty a player scene (medium).** S5 20:34:29 "bots, what should we do next?": the model repeated its previous answer
    word for word, `novelty dropped ... reason=exact` for both bots, `lines=0 delivered=0/0`, and the player's question went unanswered.
    A banter scene losing its lines is fine; a PLAYER scene should keep at least one line (or re-ask).
20. **First sightings are saved only at the rollover (minor).** S5/S6: "the first wolf any of you have seen" in a cue at 20:52:15 and,
    after a quit and relaunch with no rollover in between, again at 21:08:27. The horse (seen before a rollover) did not repeat.
21. **Raw ids in cues, memories and state (minor).** Damage sources render as "took a beating from a on fire" and "from a in wall"
    (cues, Jake's day memory, then Bob says "the in-wall took a bite out of us"); CURRENT STATE says "busy with skill:woodcut". Phase 7's
    "no raw fact leakage" item.
22. **The DM prompt carries the player's line twice, and Jake answers "you said that already" (medium, quality).** S5 tap of the 20:39:01
    DM: the line is in RELEVANT KNOWLEDGE (`RotiWokeman told you: "the snow is pretty"`) and again as the final user turn; earlier facts
    also sit in both ABOUT and the history. Jake replied "You mentioned that already" / "Roti's already said that" to four brand-new
    lines (19:58:26, 19:59:00, 20:37:23, 20:39:07). Witnessed-event lines are also duplicated (each `DIRECT_CONVERSATION` 8 times).
23. **A threat resumes a woodcut that finished 45 minutes earlier (minor).** S5 20:42:55 an idle Jake (hobbies off, last woodcut
    `COMPLETED` 19:57:44) said "Terminating all current tasks due to threat detections", and at 20:43:05 "Resuming 'woodcut' for Jake.
    Hostiles cleared." and started a new woodcut. Suffocation (`in_wall`) damage is also journaled as `COMBAT_STARTED`.
24. **A bot in a boat on land floods the log (minor).** S8 Jake mounted on an oak boat on land: `[FollowAssert] mounted-rescue-skip
    bot=Jake vehicle=entity.minecraft.oak_boat reason=not-suffocating` about 25 times a second (1,370 lines in a minute).
25. **One axe in a chest makes an idle bot retry every 5 s (minor).** S8 a chest holding a single stone axe beside axe-less Jake:
    `[supply] request ... outcome=INELIGIBLE(RESERVE_EXHAUSTED)` plus the matching `withdraw ... REFUSED` for `idle-weapon` and
    `idle-axe`, every 5 s (four lines each time) while the owner stood by. The reserve rule is fine; the retry has no backoff.
26. **The bot chest registry keys worlds by level name only (minor, real for players).** `bot_chest_registry.json` stores
    `worlds/New World/minecraft:overworld/...`, while `bot_home_data.json` uses `New World#<id>`. Every save named "New World" (the
    default name) shares one chest list: S9 showed Jake's S5 supply chest from another save as "Destroyed".

Observations, not findings: soul lines carry stage directions in parentheses ("(chuckles)", "(dryly)") into chat; two bots took the
same bed at night (21:01:38); `gpt-oss:20b` returns empty `content` (all of it is reasoning), so it cannot be a soul model as wired;
after the S6 trim, the shelved bots Ash, Birch and Cedar came back on the next launch as lowercase `ash`, `birch`, `cedar` at world
spawn, probably a side effect of trimming their name-keyed entries, not a mod bug until reproduced.

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
| 6f | Remembered fishing spot (1.1.207) | PARTIAL | S4 second `/bot fish 1` at the same pond: `[19:16:29] Reusing remembered fishing spot: water=60, 77, -47 stand=59, 78, -47` with no new scan. The pond sat under an overhang and three runs ended "No bites after 6 casts.", so no catch, no 40-60 block walk back and no fill-in check. The first spot search took 18.7 s (`Fishing spot search took 18729ms`). Replay `frens_1_1_271_6f_fishing_spot_memory`. |
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
| 6q | Supply commands are safe for non-ops (1.1.219) | PASS | S4 as the host: `/frens supply answer 00000000-... once` gave `[supply] answer ... result=NOT_FOUND` and "That request is no longer open."; `/frens supply revoke` on a fresh chest gave `revoke ... removed=false` and "Nothing to revoke: you hadn't allowed \"always\" for the chest at 58, 79, -61."; `[supply] started: 0 standing permission(s) restored` at load. Nothing moved, no error. Not tried from a non-op account. Replay `frens_1_1_271_6q_6aa_supply_cmds_and_drops`. |
| 6s | Do this first: mark your supply chest Always (1.1.221) | PARTIAL | S3 Always works for food: `choice=always result=GRANTED_ALWAYS` (18:12:53), the next take `outcome=COVERED_BY_ALWAYS ... MOVED` with no prompt. The axe case never prompted: `/bot skill woodcut` with no axe beside a chest of 2 stone axes punched a tree ("No axe yet, so I'll punch a tree first.", the 1.1.229 bootstrap) and logged no `[supply]` line. Finding 8. |
| 6s | Allow once really lets him take it (1.1.221) | PASS | S3 (bread, not the axe/sword kit): `answer ... choice=once result=GRANTED_ONCE` at 18:07:02, `withdraw ... result=MOVED moved=2` at 18:07:08 (6 s), no second prompt for it. Also planks for a craft: once at 18:16:55, `MOVED` at 18:16:58. |
| 6s | No means no (1.1.221) | PASS | S3 `food 10 no 70`: `choice=no result=REJECTED`, then `withdraw ... REFUSED reason=NOT_PERMITTED`, and 68 s later `request ... outcome=REJECT_COOLDOWN` with no new prompt. Nothing moved. Three minutes later the same chest still answered REJECT_COOLDOWN. |
| 6s | A hungry bot asks for food above the reserve (1.1.221, reserve 8 since 1.1.268) | PASS | S3 `tools/sandbox/supplytest.py food 10`: `[18:07:00] [supply] request bot=Jake ... item=minecraft:bread qty=2 ... outcome=OPENED`, answered once, `withdraw ... result=MOVED moved=2`, 8 left. `food 8`: no request line at all. |
| 6s | A full inventory never freezes the game (1.1.221) | PASS | S3 Jake holding 36 stacks of cobblestone, Always chest with 24 bread: `transfer ... status=NO_ROOM`, `Dropped 64x Cobblestone to free inventory space.`, `MOVED moved=2`, `Mutual aid: Jake took 2x Bread`, all in the same second (18:16:04). No `Can't keep up!`. |
| 6s | Crafting and seeds from a chest (1.1.221, new behaviour) | PARTIAL | S3 craft half: the first `/bot craft stick` asked (`purpose=craft-planks ... WAITING`) and answered "Sticks need 2 planks per craft."; after [Allow once] the second run `withdraw ... MOVED moved=2` then "Crafted 4 Sticks." The harvest seeds half was not set up. |
| 6s | Revoke all (1.1.221) | PASS | S3 after an Always: `[18:14:14] [supply] revoke-all owner=RotiWokeman removed=1 grants=0`, chat "Revoked 1 standing permission(s)...", and the next hunger take prompted again (`outcome=OPENED` 18:14:24). |
| 6t | Idle stone upgrade with a table in reach (1.1.222) | PARTIAL | S3 with the item's kit (wooden pickaxe and shovel, 3 cobblestone, 2 sticks, table 2 blocks away, idle hobbies on) nothing crafted in 100 s: every 12 s `Idle wooden fallback: Jake blocked — no safe woodcut target near or ahead`, which runs before the stone upgrade and returns first (finding 9). With a wooden axe and sword added: `[18:20:19] Crafted 1 pickaxe(s) using stone ...`, `Idle stone upgrade: Jake upgraded wooden tools to stone`, at the table in reach, no walk, no `Can't keep up!`. |
| 6t | `/bot craft` still walks to a far table (1.1.222, unchanged) | PASS | S4 planks in the bag, a table 15 blocks away on open ground: "Give me a moment. I'll use the crafting table at 57, 79, -59.", walked there in 4 s, "Crafted 1 Wooden Pickaxe.", no `craft-station tick-side` line. A run 20 s earlier, right after a failed reach, did not see that table, crafted a spare one from 4 of its 6 planks and could not place it (finding 13). Replay `frens_1_1_271_6t_full_bag_gear_craft`. |
| 6t | A starving bot with a full bag drops junk, not a chest walk (1.1.222) | PASS | S4 (1.1.271) Jake with 36 stacks of cobblestone and dirt, a chest 10 blocks away, hunger 4, a bread summoned at his feet: `[19:00:2x] Dropped 64x Cobblestone to free inventory space.`, `Mutual aid: Jake cleared inventory space for nearby dropped food Bread`, food 4 to 9, no walk (same spot), no `Can't keep up!`. (A first try failed only because the player stood on the same block and took the bread.) Replay `frens_1_1_271_6t_full_bag_gear_craft`. |
| 6t | Gear: the full bot only makes room when there is something to give (1.1.222) | PASS | S4 Bob full of junk with no weapon, Jake one stone sword: no `to free inventory space` for Bob in 65 s. Two more swords to Jake: `[19:02:35] Dropped 64x Cobblestone to free inventory space.`, `Mutual aid: Jake offered spare gear to Bob`, `shared Stone Sword with Bob after acknowledgment`. Replay `frens_1_1_271_6t_full_bag_gear_craft`. |
| 6t | A flower never makes a full bot drop anything (1.1.222) | PASS | S4 Bob full, Jake carrying 5 poppies and 5 dandelions for 65 s: no `to free inventory space` line for Bob, no gift. Replay `frens_1_1_271_6t_full_bag_gear_craft`. |
| 6u | `/bot craft stone_axe` names what's missing (1.1.223) | PASS | S3 3 sticks only: "I need 3 cobblestone and 2 sticks for a stone axe. I have 3 sticks."; with 3 cobblestone added: "Crafted 1 Stone Axe.", no "I don't know how to craft". |
| 6v | Startup permission mode (1.1.224) | PARTIAL | S4 on 1.1.271: `[18:57:13] [PermCheck] operator-permission mode=leveled-mapped class=net.minecraft.class_12086 field=OWNERS/field_63185`; bots spoke in chat as before (Jake's lines through this session). Carpet is not in the sandbox, so the `/player` half was not run. (On 1.1.270 this was the fallback line, finding 12.) |
| 6v | Diagnostics switch (1.1.224) | PASS | S2 `/bot config diagnostics off` with Jake following: 0 `Follow status:` lines in 30 s (0 in the whole log before it). `on`: 25 lines in the next 40 s. |
| 6w | Scene logs carry no private text (1.1.225) | PASS | S2 diagnostics on, after the "I hate the Nether" DM: banter, scene and playback lines show only ids, counts, `seedChars=`, `privateSeedCount=`, `dropped rawLine=N reason=... chars=`. The only log line with the DM text is vanilla's own `[Not Secure] <RotiWokeman>` chat line. |
| 6x | Auto-spawn after the host joins (1.1.226) | PASS | S2 reopening Sweep Souls with Jake and Bob saved: `[16:57:35] Auto-spawn deferred until the first real player joins (integrated server).`, then `RotiWokeman[local] logged in` (16:57:38) and only after it `Spawned new bot Jake!` / `Bob`, at their saved spot. |
| 6x | Too many saved bots never lock the host out (1.1.226) | PARTIAL | S3 7 saved bots (Jake, Ash, Birch, Cedar, Dale, Elm, Fir), quit and reopen: host joined with no "server is full", `Auto-spawn deferred until the first real player joins`, then all 7 spawned at 18:51:50 (8/8). No `Auto-spawn skipped ... 1 slot(s) kept free` line and no chat notice: with the host already in, `effectivePlayerCount` counts it, so the reserve no longer leaves a bot out. The pass text (6 bots) looks stale since auto-spawn waits for the host; Bradley's call. The six bots spawned seconds before the quit came back at world spawn (`inventory-load-skipped reason=missing-snapshot`). |
| 6aa | Woodcut from nothing (1.1.229) | PASS | S3 Jake empty in the forest, `/bot skill woodcut`: `Woodcut axe bootstrap: Jake has no axe, punching logs bare-handed`, chat "No axe yet, so I'll punch a tree first.", first punch about 4 s later, `has enough wood (logs=3 planks=0 sticks=1), crafting an axe` (18:26:24), `crafted an axe, back to normal woodcut`, then normal felling. It used an existing table 45 blocks away instead of placing one. The `admin-zone` rejects are an old zone at 59,-112, not the Spawn base. No A* timeout. |
| 6aa | Follow comes back after a task, no "Busy" (1.1.229) | PASS | S3 Jake following, `/bot skill woodcut 4 Jake` ran four trees and ended: `[18:34:53] [FollowAssert] task-resume bot=Jake mode=follow ... resumed=true result='Now following RotiWokeman.'`, no "Busy with another task" and no "Stopping follow command." |
| 6aa | No axe wear on dirt (1.1.229) | PASS | S3 two woodcut runs with an axe and dirt: every dirt pillar and bridge block came down `with minecraft:dirt` in hand (300-750 ms), never the axe. Note: plank bridge blocks were also mined with planks in hand, some at about 3000 ms each, not with the axe (finding 10). |
| 6aa | Unreachable drop given up fast (1.1.229) | PASS | S4 a sapling dropped on a persistent leaf block 4 above idle Jake (idle hobbies on for 2 of the 3 minutes): no `[IdleSweep]` target at it, so no bot stared at it. The abandon path was not exercised. Replay `frens_1_1_271_6q_6aa_supply_cmds_and_drops`. |
| 6ae | Leaves drop saplings, teardown uses hands (1.1.233) | PASS | S3 Jake with stone pickaxe, iron axe, shears and 32 dirt woodcut two trees with a 2-step dirt pillar: the dirt teardown was mined `with minecraft:dirt` in hand, stone pickaxe `damage 0` before and after (shears also 0). Plank scaffold was not part of this run. |
| 6af | Drop sweep, return and craft feedback (1.1.234) | FAIL | S3 drops spread around Jake, `/bot skill drop_sweep Jake`: chat "Picked up 27 items.", but the skill's own summary says `collected: 20` and Jake's bag (cleared before) holds 21 items (13 saplings, 5 logs, 2 apples, 1 stick). No fast-travel error, no return trip. Finding 11. |



### Pass 3 verdicts (1.1.271)

Evidence names the session (S5-S...) and the Flashback replay (A, B, ...: see Pass 3 sessions).

| Phase | Item | Verdict | Evidence |
|---|---|---|---|
| 1 | Streaming first-word latency (1.1.175, still open) | FAIL | S5 by log: `llama3.1:8b` providerMs 8019/5476/4255 for three DMs (19:31:56-19:32:39), Piper 74-148 ms a segment; `llama3.2:3b` 2090/1896 ms after a 4829 ms load. Still over ~3 s on 8B, inside it on 3B. Replay A. |
| 1 | llama3.2:3b speed vs llama3.1:8b (1.1.175, still open) | PASS | S5 three DMs each: 8B median 5.5 s, 3B median 2.1 s (`Soul model set to 'llama3.2:3b'` 19:33:11, back 19:37:06). Two 3B banter scenes 4/4 lines, no MALFORMED (roster of two; roster of one not run). By speed 3B; the quality call is Bradley's. Replay A 19:31-19:37. |
| 1 | Failed generation retries (1.1.184) | PASS | S5 `gpt-oss:20b` returns empty content (its reasoning uses the whole reply): `failed:MALFORMED` 20:29:51, then a fresh `banter ... outcome=fired` 20:31:54 with no input (2 min); again 20:32:06. Replay A. |
| 2 | ~1/3 of banter ends addressed to the player (1.1.181) | PASS | S5 tap: 11 of 37 scene prompts carried "One of you may end by saying one short thing to RotiWokeman" (30 %). `addressPlayer=` is not logged at INFO, so counted from the prompts. |
| 2 | Active lane fires on work (1.1.188) | PASS | S6 Active on in config, `/bot skill woodcut Jake`: `banter lane=ACTIVE outcome=fired` 21:06:55 (about 2 min in), scene `kind=WORK` 4 lines, Bob "Oh, nice piece of wood!"; again 21:12:35. Replay B 21:06:55. |
| 2 | Pacing sliders (1.1.188) | PARTIAL | S6 Rate... after a restart showed Scripted 100 and Idle 70 (set in config, persisted). At 100: 31 voiced scripted lines in 5.9 min vs 2.9/min at 50 in S5. S7 Scripted 0 through the slider (saved, `dialogueScriptedRate: 0`): Jake and Bob still spoke 10 ambient lines in 4 min. Ambient chatter never reads the rate (finding 17). The first slider move in S6 disconnected the client (finding 15). Replays B, C. |
| 2 | Walking away / bystander earshot (1.1.176) | PASS | S5 scene 9d50eb8a 20:44:25, Bob teleported 150 blocks after line 1: `listeners=3` then 2, then 1 on Bob's own line 3 (not shown to the player), then 2. No error. No `skipped-speaker-gone` because Bob stayed online, and the far line still counts in `delivered=4/4`. Replay A 20:44:25. |
| 2 | Cooldown spacing over the session | PARTIAL | S5 natural fires were banter only (20:52:09, 20:57:49, 21:03:19, about 5.5 min apart at Idle 70); local never fired unprompted (lines `vetoed:salience`), so banter/local alternation could not be judged. |
| 3 | Addressing a bot mid-window closes it | PASS | S5 20:38:53 Jake's solo remark to the player, 20:38:55 "Jake, hold that thought." (routed as a DM), 20:39:01 unaddressed "the snow is pretty": `dm follow-up routed`, no `[souls] local ... fired` continuation. Replay A 20:38:53. |
| 3 | Danger veto | FAIL | S5 easy difficulty, zombies summoned 2 and 8 blocks from Bob, `/bot soul local now`, a salient line: `local outcome=fired score=5` at 20:41:52 and 20:42:46, never `vetoed:danger`. The zombie is not in that scene's prompt, though Jake reacted to it 9 s later. Finding 18. Replay A 20:42:46. |
| 3 | Server-thread cost under load (1.1.178, never measured) | PASS | S5 five soul bots in earshot (Jake, Bob, Ash, Birch, Cedar), 16 unaddressed lines in about 30 s (21:00): no `Can't keep up`, no `local capture failed`; the earshot scan ran on every line (it comes before the cooldown gate). |
| 3 | Roster-lost backoff (1.1.179) | NOT REACHED | `vetoed:roster-lost` only fires when the snapshot capture throws; no way to force one from the game. The code pushes the cooldown 120 s on it (SoulLocalDirector, the roster-lost branch). |
| 4 | "bots, …" two-bot scene | PARTIAL | S5 on 1.1.271: "everyone, should we build a shelter..." 20:33:41 both bots spoke, one line each. "bots, what should we do next?" twice: 20:33:09 `failed:MALFORMED` (the 8B reply was only a `##FRENS` line, finding 16) and 20:34:29 `lines=0` (novelty dropped both repeated lines, finding 19). Replay A. |
| 4 | A group scene resets the banter timer | PASS | S5 banter status 68 s left (20:32:57), then after the PARTY scene 328 s (20:33:36), 333 s after the next one. Pushed out each time. |
| 6 | Category-muting round-trip (1.1.175) | PARTIAL | S7 Ambient Chatter unticked in both Adv menus (config `mutedTextCategories`/`mutedVoiceCategories: ["ambient_chatter"]`): no ambient line reached chat, no GENERAL fallback. The voice half is not visible in the log: `Played sound` is logged before the per-listener mute and the `muted` count is DEBUG. Needs an ear (added to the guided doc). Replay D 21:33-21:37. |
| 6 | The separation proof (1.1.182) | PASS | S7 ambient muted in both menus, `/bot soul banter now`: scene a37da787 21:33:43 played 4/4 `text=true voiced=true`. Replay D 21:33:43. |
| 6 | `vetoed:muted` only in the right condition (1.1.182) | CRITERION OUT OF DATE | Since the 1.1.193 lane split the banter/local surface is open whenever Soul Chat or Soul Voice is on (SoulRuntime ambientTextOpen/ambientVoiceOpen); the scripted Text and Voice masters no longer reach it. With Soul Chat off the pipeline is down first, so `vetoed:muted` is close to unreachable. S7 scripted Text+Voice off: scenes fired, no `vetoed:muted`. Needs Bradley's ruling on the new rule. |
| 6 | Two lanes, two masters (1.1.193) | PASS | S7 Scripted Text and Voice OFF in Bot Controls (21:38), Soul Voice on: Jake's DM reply spoken (`tts ... outcome=spoken-all-1seg` 21:39:15), scene lines `voiced=true`, no `Played sound` scripted line in 4 min. Replay D 21:38-21:41. |
| 6 | Live-settings apply (1.1.184) | PASS | S7 `/bot soul disable Bob` 21:41:16: "Bob's soul communication is now disabled", the next "Bob, can you hear me?" got no turn; `/bot soul enable Bob` 21:41:27 "Bob is now speaking as Bob.", the next DM answered 21:41:37. Bot Controls toggles (21:38) applied with no restart. |
| 6 | At most one bot comments on the same thing (1.1.184) | PASS | S5 parrot beside both bots from 19:31:47: one `parrot_nearby_nice_bird` (Jake), none from Bob in the next 30 min. Only one parrot event, so a thin sample. |
| 6j | Novelty fires and does not gag the bots (1.1.211) | FAIL | S5 drops happen and banter survives them (20:06:49 trigram drop, scene still 3 lines). But PLAYER scene b489440f "bots, what should we do next?" 20:34:29: the model repeated its previous answer, `novelty dropped bot=Jake reason=exact` and `bot=Bob reason=exact`, `lines=0 delivered=0/0`: the player got no answer. Finding 19. |
| 6j | Silas stays laconic (1.1.211) | PASS | S5 Silas in the roster for four scenes 20:11-20:14: no novelty drop for Silas at all (so never `trigram`). Replay A 20:11. |
| 6j | Quoted short idiom is not a repeat (1.1.211) | PASS | S5 the only trigram drops were real near-repeats of long lines: 20:06:49 Jake "RotiWokeman grew up on a farm, didn't he? Maybe he knows something about dealing with...other things." after 20:05:37 "...about finding food in the wild."; 20:35:01 the same with the 20:33:41 line. |
| 6j | Toggle off is silent (1.1.211) | PASS | S5 `/bot soul novelty off` 20:09:19, scenes 20:09:31 and 20:10:16: no `novelty` line, both 4/4. |
| 6k | Old minds load clean (1.1.212) | PASS | S7 simulated: Bob's `mind.json` saved without the `peerStances` and `relations` keys (game closed), launch, rollover 21:23:44 `mind consolidated bot=6a00845c... day=8`: the file now has `"peerStances": {}` and `"relations": []`, no Jackson warning. (No real pre-1.1.212 file in the sandbox.) |
| 6k | Peer clause reaches the prompt (1.1.212) | PASS | S6 Jake's peer curiosity for Bob seeded to 5 in `mind.json` (game closed): WORK scene 21:07:02 CURRENT STATE for Jake ends "..., curious about Bob" (16 chars, Bob in that roster). Replay B 21:07:02. |
| 6k | Day rollover decays toward baseline (1.1.212) | PASS | S5 rollover 19:59:40 (day 1 to 2): Bob's peer entry for Jake (trust 4, curiosity 3) is gone from `peerStances`: trust stepped to 3, so it reached baseline and was dropped. Jake's map was already empty. Completes the S2 PARTIAL. |
| 6l | Old minds load clean (1.1.213) | PASS | S7 same simulated file as 6k: consolidation 21:23:44 wrote `"relations": []`, no Jackson warning. |
| 6l | SEEN fact forms from repeated completions (1.1.213) | PASS | S5 three finished `/bot skill woodcut Jake` on day 0 (19:43:24, 19:53:44, 19:57:44), rollover: `[19:59:45] relations bot=Jake added=1 total=1` once; `/bot soul beliefs Jake` "Jake is good at woodcut (conf 0.4, SEEN, day 2, salience 5)". Replay A 19:40-19:59. |
| 6l | BELIEFS block reaches the prompt (1.1.213) | PASS | S5 tap, scene 20:01:06: "BELIEFS (claims the bots hold, not world truth)\nJake believes:\n- Jake is good at woodcut" right after the ABOUT RotiWokeman block, about 80 chars; no "Bob believes" (Bob has no facts). |
| 6l | Relation anchor fires and goes on cooldown (1.1.213) | PASS | S5 day 5, four `banter now`: the third cue (20:16:54) "one of you points out I am good at woodcut", Bob "You're good at woodcut, though. I've seen you make quick work of those trees."; the fact's day 2 to 5 and salience 4 to 7; scene four on another topic. The cue's "points out I am good at" is first person (cosmetic). Replay A 20:16:54. |
| 6l | Toggle off is inert (1.1.213) | PASS | S5 `/bot soul relations off` 20:19:45, rollover to day 6: no `relations` line, the fact kept (salience 7 to 6); scenes 20:21:03 and 20:22:10 have no BELIEFS block and no relation cue. |
| 6m | A malformed tail never costs a scene (1.1.214) | FAIL | S5 20:33:09 PLAYER scene: the 8B reply was only `##FRENS {"stance":...,"facts":[...]}` with no dialogue: `accepted=false rawChars=241`, `outcome=failed:MALFORMED`. Finding 16. |
| 6m | A model fact lands as INFERRED at ≤0.6 (1.1.214) | NOT REACHED | no side-channel line was ever applied (the one tail came with no dialogue and failed the scene; banter replies never carried one). |
| 6m | Model stance delta is peers-only and once per day (1.1.214) | NOT REACHED | same: no applied side-channel line. |
| 6n | No two bots talk over each other (1.1.216) | FAIL | S5 273 voiced scripted lines (`Played sound`), 27 pairs under 4 s, several from two bots in the same second (20:21:49 Bob `wildlife_pig_nearby` + Jake `ambient_blame_terrain`; 20:45:28 `idle_here_if_needed` + `animal_quality`). Ambient chatter is not on the speech floor (finding 17). |
| 6n | Scripted lines do not interleave with a soul scene (1.1.216) | FAIL | S5 voiced ambient lines between scene lines: 20:04:49 `context_listening` (scene e0caee4c, lines 3-4), 20:05:30 and 20:05:34 (8b3eaa9a), 20:06:06 (0f9ba089), 20:07:36 `weather_sunny` (69bbfde8), 20:58:09. Finding 17. Replay A 20:05:21-20:05:37. |
| 6n | A scene still gets through a scripted floor (1.1.216) | PARTIAL | S5 every requested scene fired; the only `vetoed:speech-floor` lines were `armedBy=scene-end` (6). Ambient lines never arm the floor (finding 17), so the scripted-floor preemption itself was not exercised. |
| 6n | The two banter lanes respect one quiet period (1.1.216) | PASS | S6 ACTIVE scene finished 21:12:49, IDLE `vetoed:speech-floor armedBy=scene-end` 21:13:05, IDLE fired 21:13:10 (21 s). S5 IDLE-to-IDLE gaps 20-24 s. |
| 6n | Muting scripted dialogue does not mute souls (1.1.216) | PASS | S7 Scripted Text and Voice masters OFF: scenes bac71e99 (21:38:31) and c99432c7 (21:39:47) played 4/4 `text=true voiced=true`, no `vetoed:speech-floor` from the scripted lane. Replay D 21:38:31. |
| 6n | Mount and animal lines stop repeating (1.1.216) | PARTIAL | S5 five minutes riding a tamed saddled horse beside cows, a sheep and a tamed wolf (20:54:39-20:59:39): no "Nice horse." / "That's a quality animal." / "I respect a well-behaved animal." at all, so no repeat (each fired once earlier, 20:01:45 and 20:45:28). Other animal lines did repeat: `cute_animal_so_cute` after 68 s (20:45:41, 20:46:49), `wolf_nearby_love_dogs` at 20:57:49 and 21:02:49. |
| 6n | A hurt wolf still gets an answer (1.1.216) | PASS | S5 tamed wolf beside both bots, `/damage` 20:53:49 then 20:54:05 (16 s): `wolf_leave_alone` at 20:53:49 and again at 20:54:05. Replay A 20:53:49. |
| 6o | Scripted lines hold off while a scene is generating (1.1.217) | FAIL | S5 20:06:44 banter fired, 20:06:50 `weather_sunny` with line 1; 20:05:59 `ambient_my_job` in the fire second. Pet and context lines do respect the reservation, ambient chatter does not (finding 17). |
| 6o | Scripted logs tell the truth (1.1.217) | PASS | S7 Scripted Text OFF 21:38-21:41: `Scripted chat suppressed (text off) category=reactions bot=Bob` (21:39:29, 21:39:40) and Jake (21:40:20), no `Sending chat message` for a bot line. |
| 6o | Model switch keeps voices alive (1.1.217, Codex 7a663785) | PASS | S5 `/bot soul model` 8B to 3B (19:33:11), back (19:37:06), to gpt-oss and back: one `tts engine started` (19:30:07), none after any switch; the next replies were voiced. Switched by command, the menu's "Selected" label not read. |
| 6o | Group-chat hint appears once (1.1.217, Codex 43bd5474) | PARTIAL | S5 seen file deleted before launch; rewritten once at 19:30:07 when two soul bots were in earshot; mtime unchanged after the 21:04 and 21:17 relaunches, so it did not come back. The on-screen hint and "Open guide" were not captured. |
| 6q | Handled lines don't become DMs (1.1.219) | PASS | S5 night, two beds: "Jake, it's getting dark out here." 21:01:27, reply 21:01:33; "zzz" 21:01:35: both bots `Task 'sleep' started` and slept (21:01:37-38), no `dm follow-up routed`. Replay A 21:01:35. |
| 6u | A scene that mentions you plays in full (1.1.223) | PASS | S5 scenes mentioning RotiWokeman played in full: 20:01:06 4/4, 20:05:21 4/4, 20:06:49 3/3. The one `owner-address cut` (20:12:56) was Jake speaking TO RotiWokeman mid-scene. |
| 7 | Change-driven cues lead (1.1.196) | PASS | S5 thunder (`weather_thunder` 20:03:04), the next scene's cue 20:04:40 "the thunder just stopped". |
| 7 | Speech acts rotate (1.1.196) | PASS | S5 cue verbs 20:06:05-20:17:42: needles, voices a worry, points out, share a joke, make a plan, asks, voices a worry, needles, recall, make a plan, asks, joke, points out, needles. No two in a row the same (read from the tap; `act=` is not logged at INFO). |
| 7 | First sightings fire once (1.1.196, 1.1.198) | FAIL | S5 "the first wolf any of you have seen" in the cue at 20:52:15; after the 21:04 relaunch (no rollover in between) the cue at 21:08:27 said it again. "the first horse" (20:01:06) did not come back: a rollover at 20:14 had saved it. The seen set is persisted only at consolidation (finding 20). |
| 7 | No player-as-animal, no raw fact leakage (1.1.197) | FAIL | S5 cues "took a beating from a on fire" (19:35:34) and "remember when took a beating from a in wall" (20:01:06, 20:16:08), Jake's day memory phrase "took a beating from a in wall", Bob then said "the in-wall took a bite out of us"; S6 CURRENT STATE "busy with skill:woodcut". Finding 21. |
| 7 | Stance shows in the prompt (1.1.198) | PASS | S6 Jake's player stance seeded (exasperation 4, curiosity 5): CURRENT STATE 21:07:02 "sulking at being ignored, full of questions for RotiWokeman". |
| 7 | Day memories from consolidation (1.1.198) | PASS | S5 `[19:59:45] mind consolidated bot=2af3f0a5... day=2 memories=7` (three day-2 memories), no `day consolidation failed` all session. events.jsonl 98 and 58 lines, under 200, so the trim was not exercised. |
| 7 | Sleep is not journaled (1.1.199) | PASS | S5 the 21:01 sleep wrote one `SLEEP` event per bot and nothing else. |
| 9 | Tell Jake three things | PASS | S5 four DMs 19:58:21-19:59:00: goats on a farm, spicy pumpkin soup, scared of deep ocean water, brother Theo. |
| 9 | Sleep through a night | PASS | S5 `[19:59:45] memory digest bot=2af3f0a5... channel=DIRECT outcome=ok kept=5 lines=12`, no digest failure line. |
| 9 | `mind.json` inspection | PASS | S5 Jake `playerMemories` 9 to 14 (the five facts), DIRECT cursor 22 to 48. PARTY `too-few` (lines=1) kept its cursor at 43 (deferred, not an empty or failed digest). |
| 9 | `/bot soul memory Jake` | PASS | S5 20:00:52 "day 2 · salience 10 · RotiWokeman has three goats named Pip, Pop, and Pud." and the rest, day 2 first, then day 1. |
| 9 | Recall in banter | PASS | S5 cues 20:05:21 and 20:09:31 "RotiWokeman once said: RotiWokeman mentioned a chest is full." (memory:said), Bob's two memories `lastRecalledDay` -1 to 2. Their salience was already at the cap of 10, so the +3 bump could not show. |
| 9 | Present truth beats memory (1.1.201 Task 6) | NOT REACHED | not run. |
| 9 | `/bot soul digest off` is not amnesia | PASS | S5 `/bot soul digest off` 20:19:45, status "Memory digest is OFF", four new DMs, rollover: Jake's `playerMemories` 14 to 14, every salience down by 1, cursors unchanged. Turned back on 20:22. |
| 10 | Reset during a running digest (added at final review) | PARTIAL | S7 four DMs, then `/time add 24000` and `/bot soul reset Jake` 0.3 s apart (21:25:34-35): "Archived your conversation with Jake, new epoch 1.", no DIRECT digest for Jake ran (the reset landed first), memories stayed empty, no `memory archive failed`. `outcome=superseded` was not hit. |
| 10 | `/bot soul reset Jake` | PASS | S7 21:25:35 reset: `/bot soul memory Jake` "Jake doesn't remember anything about you yet."; `mind.json` `playerMemories` 0, `archivedPlayerMemories` 22, `digestCursors` {}. |
| 10 | `/bot soul reset party` mid-banter | PASS | S7 scene 72cfdd67 line 1 at 21:28:35, `/bot soul reset party` the same second: `scene-playback ... outcome=cancelled delivered=1/3`, "Archived your group conversation, new epoch 2.", archive file `epoch-1-...jsonl`; the epoch-0 archive holds 71 HEARD, 175 SPOKEN, 7 FAILURE records. Next banter fired 21:28:56. Replay C 21:28:35. |
| 10 | Toggle everything off | PARTIAL | S7 `/bot soul banter off` + `local off` 21:29:3x, two salient unaddressed lines, 2 min: no `[souls] local` line, but the Active lane kept evaluating (`banter lane=ACTIVE outcome=vetoed:not-quiet`, `vetoed:nobody-working`). Active has its own toggle; whether `banter off` should silence it is Bradley's call. |
| 6c | Spells key opens the inventory Spells tab (1.1.204) | CRITERION OUT OF DATE | S8 the Spells keybind ships unbound (`GLFW_KEY_UNKNOWN`); bound to K in the sandbox's options, looking at Jake, pressed: nothing opened, no log. `handleSpellsContextKey` returns unless survival recruitment is on and completed, and Questing has been off by default since 1.1.235, so in Admin mode the key is a no-op. Needs Bradley's ruling (Questing-only, or bind it and drop the recruitment gate). |
| 6c | Wedged bot: pillar reason + 30 s back-off (1.1.204, P1 escape) | PASS | S8 on 1.1.271 fresh bot Pebble guarding in a 1x1x2 hole, player 150 blocks away, day. Bedrock lid: every try `pillar recovery placed no blocks reason=overhead-mine-failed`, then `Guard escape: Pebble failed — cooldown 30s` (21:57:22, 21:57:56, 21:58:29, about 33 s apart); lid removed, `Guard escape: Pebble succeeded` 22:00:11. A stone lid: first try `overhead-mine-failed`, second try dug through and climbed out. Pass 2's finding 5 (every movement aborted) did not reproduce. Replay E. |
| 6c | Hotbar-lock combat honesty (1.1.204, side effect) | PASS | S8 Jake in an oak boat on land, hotbar planks/dirt/sticks, iron sword in the main inventory, easy, zombie 4 blocks away (21:47:40): Jake moved the sword into hotbar slot 0 (planks pushed to slot 14) and held it; no swing with another item. He stayed in the boat. While mounted the log printed `[FollowAssert] mounted-rescue-skip ... reason=not-suffocating` about 25 times a second (finding 24). |
| 6d | Bundled cobblestone still gets a pillar (1.1.205) | PASS | S8 Pebble guarding in an open 1x1x2 hole, all 16 cobblestone in a bundle: `ensureAtSurface: Pebble pulled 1 scaffold stack(s) out of bundles for pillar recovery` (21:53:11), pillar steps=6, `Guard escape: Pebble succeeded` 21:54:06; 10 cobblestone left loose afterwards. Replay E 21:53:06-21:54:06. |
| 6n | The woodcut storm is gone (1.1.216) | PASS | S8 Jake with an empty inventory idle in the forest at 15 76 -110, hobbies on: `Idle hobby 'woodcut' finished ... success=false` at 21:50:03, 21:52:03, 21:56:03 only, never more than once a minute. |
| 6n | The backoff ladder widens (1.1.216) | PASS | S8 `backing off: failures=1 remaining=48s` (21:49:15), `failures=2 remaining=108s`, `failures=3 remaining=228s`, `failures=4 remaining=468s` (21:56:15): 60, 120, 240, 480 s. The ten-minute hold was not reached before the axe test. |
| 6n | An axe in a chest wakes it back up (1.1.216) | PASS | S8 a chest with a stone axe beside Jake 22:00:48, deep in the 480 s backoff: `Idle wooden fallback: Jake signature changed ... with 3894 ticks of cooldown left; resetting` the same second, and supply requests for the axe at once. With only one axe in the chest the take is refused (`INELIGIBLE(RESERVE_EXHAUSTED)`, one stays for the owner) and retried every 5 s, two lines each (about 24 lines a minute while the owner stands by; finding 25). |
| 6n | Commanded work still starts immediately (1.1.216) | PASS | S8 `/bot skill woodcut Jake` 22:04:15 during the backoff: the skill started the same second (bare-hand bootstrap, "No axe yet, so I'll punch a tree first."). `/bot woodcut Jake` as the item writes it answers "Unsupported inline syntax". |
| 6s | Walking away keeps the log quiet; coming back works at once (1.1.221) | PARTIAL | S8 chest of 2 stone axes beside Jake (no Always), player 50 blocks away 2 min (22:02-22:04): 3 `[supply]` lines (`OWNER_NOT_NEARBY`), quiet. Back, `/bot skill woodcut Jake`: started at once but never asked the chest; it went to the bare-hand bootstrap (pass 2 finding 8, still on 1.1.271), so "prompts within seconds" did not happen. |
| 6s | Never-allowed items stay put (1.1.221) | PASS | S8 chest with saddle, lead and iron axe beside idle Jake (22:05-22:06:15, hobbies on): no `[supply]` line at all, nothing taken; the stone-axe chest at the same spot had drawn requests within seconds. The mount half was not tried. |
| 6t | A far table: no walk, no freeze, one quiet line (1.1.222) | PASS | S8 Jake with the stone-upgrade kit (wooden pickaxe, shovel, axe, sword, 3 cobblestone, 2 sticks), the only table 18 blocks away, 3 min idle: one `craft-station tick-side: no table in reach for Jake, not walking (reason=no-table)` (22:08:30), no walk, no `Can't keep up`, no `Failed to place`. |
| 6t | A table in his bag gets placed beside him (1.1.222) | PASS | S8 same kit plus a crafting table in the bag: `Placed crafting table from inventory at 17, 76, -108` (Jake at 16, 76, -108), `Idle stone upgrade: Jake upgraded wooden tools to stone` 22:10:30; stone pickaxe in the bag. The axe and sword were added because of pass 2 finding 9. |
| 6w | Enable message names the blocker (1.1.225) | PASS | S8 `/bot soul system off`, `/bot soul enable Bob`: "Bob will speak as Bob, but Soul Chat is OFF. Turn it on in Set up AI companions or run /bot soul system on." |
| 6g | Leaf-blocked navigation unchanged (1.1.208) | PASS | S8 `/bot come Jake` twice through the forest canopy around 15 -110 (22:11:30 from 28 blocks, 22:11:48 from 30 blocks): both arrived (2.8 blocks at the end), no exception, no `LeafClearService` line. Judged by Bradley's ruling (runs clean and finishes). |
| 6f | Wider fishing scan (1.1.207) | PARTIAL | S8 new bot Finn (no remembered spot), a 7x7 pond dug 18 blocks east, `/bot fish 1 Finn`: `Selected fishing spot: stand=67, 68, 60 water=70, 67, 58` and it walked there (22:13:45). The search took 18712 ms (pass 2 finding 14 again). The desert failure half was not run. |
| 6f | Per-kind cap keeps fishing memory (1.1.207) | PARTIAL | S8 `bot_home_data.json` `waterSpotsByBot` after the fish: `finn: [{x 70, y 67, z 58, kind fishing}]`, and Jake's fishing spot from S4 is still there. No irrigation entry, because the farm item was not run. |
| 6o | Torch stays up in the dark (1.1.217) | PARTIAL | S8 sealed stone tunnel at y 40 with one wall torch: `[torch-hold] Jake verdict=hold gate=none light=7 action=held slot=0 savedSlot=2` (savedSlot is his pre-torch slot), and `verdict=reject gate=light-above-11 light=12 action=yield` at the bright spot (22:15:11, 22:17:30). The walk was too quick to sample the 8-11 band, so the hysteresis itself was not seen. |
| 6o | Quit mid-hold does not reload with a torch in hand (1.1.217) | PASS | S8 quit while Jake held a torch in the dark tunnel (22:18), relaunch: first lines `verdict=reject gate=no-torch-in-inventory` then `verdict=hold ... light=5 action=held slot=0 savedSlot=2`, so he loaded on his own slot 2 and only then re-took the torch because it is still dark there. |
| 6u | No server-thread walk warning (1.1.223 tripwire) | PASS | S5-S8 whole logs (19:28-22:19): no `nudgeTowardUntilClose on the server thread` WARN. |
| 6u | Ordinary doors still get closed behind the bots (1.1.223) | PASS | S9 stone wall with a plain oak door, Jake and Bob following: once the player stood 12 blocks past it both went through and `door-close attempt` then `door-closed: marked recently closed` for 50, 66, 50 (Bob 22:22:27, Jake 22:22:28); the door is closed. Before that they stood in the doorway for about 45 s while the player was 7 blocks away (Phase 8). Replay F 22:21-22:22. |
| 6p | Hovel builder uses the door from the wall line (1.1.218) | PASS | S9 `/bot shelter hovel Bob` (diamond tools given): five `[hovel-egress] ... from=INSIDE to=OUTSIDE outcome=ok guard=proceed` (22:24:34-22:30:18) out the east door, then two `outcome=failed:exit guard=fail` from INSIDE (allowed), `disabled after 2 failures`, "Hovel built." 22:30:39. No ON_WALL start was logged in this build. |
| 6p | No fall off a raised floor (1.1.218) | PASS | S9 `/bot skill build watchtower Jake` with no spruce fence (the railing): "Built schematic 'watchtower': 102/119 blocks placed." in 80 s; Jake's health 20 all through (sampled every 10 s), ended on top at y 72; the one egress on the platform was `skipped:no-doorway ... y=69`, none past the edge. Replay F 22:31:50-22:33:09. |
| 6p | Line-of-sight retries stay local (1.1.218) | PASS | S9 hovel: egress sequences 22:24:34, 22:28:33, 22:29:25, 22:30:18 minutes apart, each to a different target; watchtower two egresses at different wall gaps. No out-and-back run at one wall block. |
| 6p | `/bot stop` mid-egress stops cleanly (1.1.218) | PARTIAL | S9 second hovel, `/bot stop Bob` the moment the first `[hovel-egress]` line printed (22:33:50): `Task 'skill:shelter' aborted: Stopped by the player.`, "Bob has stopped.", skill exit 22:33:51. The egress line is written when the walk ends, so the stop landed just after one, not inside it; `(aborted)` not seen and the next-build half not run. |
| 8 | Doorway / pressure-plate stalls (observe only) | PASS | Captured S9 22:21:11-22:22:27: Jake and Bob following through a closed oak door at 50, 66, 50, player 7 blocks past it: `door-plan abort: stuck=24 doorBase=50, 66, 50`, `applyMovementInput-reject bot=Bob ... reason=head-not-passable feet=50, 67, 50=oak_door head=50, 68, 50=stone` (Bob had jumped onto the door), both parked at 51, 66, 50 with the door open for about 45 s; they went through when the player moved 12 blocks off. |
| 6f | Actions tab has no Regroup; Spells tab still does (1.1.207) | PARTIAL | S9 bot inventory overlay: Actions, "Orders & Travel" lists Follow, Guard, Patrol, Return Home, Sleep, no Regroup; the Spells tab's Movement section lists Regroup (greyed, "Spells require artifacts. No artifacts detected."). "Works from there" needs an artifact, not run. Replay F 22:34-22:35. |
| 6ac | Buttons still work for the host (1.1.231) | PASS | S9 single-player host, Jake's inventory overlay: Patrol (22:35:28 "Patrolling this area"), Guard (22:35:45), Follow (22:35:59 "Jake is following RotiWokeman."), Stop (22:36:00 "Jake has stopped."), Idle Hobbies toggle (22:36:45 "Jake idle hobbies are now ON."), Crafting window Craft x1 and x2 (22:37:51, 22:38:05 "Crafted 4 Sticks." on Jake). No `[bot-access]` line, no "That button could not run". Skills toggle and the come/sleep keys not pressed. |
| 6u | Crafting window search and sort (1.1.223) | PASS | S9 Actions, Crafting >: search "diamond" gave "Nothing matches "diamond"."; cleared, Sort cycled A-Z, Z-A, Category (headers Functional Blocks / Tools & Utilities / Ingredients), Base type (Wood / Stone), Learned; selected Stick, re-sorted, Craft: "Crafted 4 Sticks." on Jake. The log has no `bot craft <item>` line (the craft runs as a task: `Task 'craft' started`). |
| 6r | Quick Store / Quick Fetch still work for the owner (1.1.220) | PASS | S9 chest beside Jake, Quick Store, point and click: "Jake deposited 434 items (kept its gear)." (`Quick deposit: Jake deposited 434 items at 32,63,40`, 22:42:45), Quick Fetch "Jake fetched 434 items." (22:43:08). No `[chest-registry] denied`. (A first try said "deposited 0 items" because the left click in creative broke the chest: test error.) |
| 6r | Go and Dismiss still work for the owner (1.1.220) | PARTIAL | S9 Storage > for Jake listed one record (the supply chest he placed in S5, shown "Destroyed" in this copy); Dismiss: `Dismissed chest record for Jake at -53,82,-60` (22:44:09) and the list emptied. Go needs a live chest record; none was made. |
| 6r | Collect still works for the owner (1.1.220) | NOT REACHED | no live chest record for Jake in this save (Quick Store does not register its chest; the only record was destroyed). |
| 6q | Bot-placed chests record their owner (1.1.219, supplies groundwork) | PASS | S5 Jake placed a supply chest during a woodcut when his bag filled (20:43:49, -53, 82, -60); `bot_chest_registry.json` record has `"ownerUuid": "e6608e8d-..."` (RotiWokeman). The registry keys worlds by level name only, so other "New World" copies see it (finding 26). |
| 6v | Your own bot still works everywhere (1.1.224) | PARTIAL | S9 right-click on Jake opens his inventory; `/bot inventory Jake` prints his inventory; Guide > Admin opens Jake's inventory overlay; no `[bot-access] denied` all session. The remote spell needs an artifact and the sunset prompt was not set up. |
| 6z | Wide unchanged (1.1.228) | PASS | S9 at 427x240 (window 1708x960, GUI scale 4): Guide topics and detail side by side, bot inventory with its Actions/Spells/Admin overlay and equal tabs, "Looking at Jake. Right-click to open menu • Press Right Bracket (]) for Guide" on the HUD. Nothing broken; there are no 1.1.227 screenshots to compare against, so "unchanged" is judged by eye. |

## Items without a verdict

Every item in the four "Needs Bradley" groups is already in `GUIDED_SESSION_BRADLEY.md` (built from the same checklist), so none was appended there.

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

### Listed in GUIDED_SESSION_BRADLEY.md, not swept (13)

- 4: **Voice-off beat pacing**.
- 6: **Ambient mute matrix on banter/local (1.1.177, 1.1.178)**.
- 6: **Global toggles UI (1.1.189, 1.1.191)**.
- 6c: **Silas persona (1.1.204)**.
- 6o: **Voice on, text off still arms the floor (1.1.217)**.
- 6s: **Old bot chests prompt instead of refusing (1.1.221)**.
- 6u: **Out through the plate gate (1.1.223)**.
- 6u: **The bot leaves the plate's gate alone (1.1.223)**.
- 6w: **Checklist fits a small window (1.1.225)**.
- 6z: **Narrow fits (1.1.228)**.
- 6z: **Scaled inventory clicks (1.1.228)**.
- 6ag: **Questing hidden (1.1.235)**.
- 6ai: **Own base over spawn still works (1.1.238)**.

### Not reached, sandbox-checkable (99)

- 1: llama3.2:3b speed vs llama3.1:8b (1.1.175, still open); Failed generation retries (1.1.184).
- 2: ~1/3 of banter ends addressed to the player (1.1.181); Active lane fires on work (1.1.188); Pacing sliders (1.1.188); Walking away / bystander earshot (1.1.176); Cooldown spacing over the session.
- 3: Addressing a bot mid-window closes it; Danger veto; Server-thread cost under load (1.1.178, never measured); Roster-lost backoff (1.1.179).
- 6: Category-muting round-trip (1.1.175); The separation proof (1.1.182); `vetoed:muted` only in the right condition (1.1.182); Two lanes, two masters (1.1.193); Live-settings apply (1.1.184); At most one bot comments on the same thing (1.1.184).
- 6b: Cheats-off single-player still saves (1.1.203, review fix); Mute screen sends a mask, not a global save (1.1.203); RAM warning on the Download button (1.1.203); Dreamsleeve row on macOS (1.1.203).
- 6c: Spells key opens the inventory Spells tab (1.1.204); Travel-wait: hobby during a fast-travel cooldown (1.1.204, P1 idle); Travel-wait cancels on stop (1.1.204); Hotbar-lock combat honesty (1.1.204, side effect).
- 6d: Bundled cobblestone still gets a pillar (1.1.205); Bundled armor equips only if better (1.1.205); Boat + out-of-hotbar sword, FarmSkill/RideSync variants (1.1.205); Travel-wait offload to an existing chest (1.1.205); Offload backoff on a full chest (1.1.205); `/bot stop` during the offload does not poison later offloads (1.1.205); Furnace fuel fallback (1.1.205).
- 6e: Swap re-validation is rare (1.1.206); Full unlocked hotbar keeps the selected slot (1.1.206); `/bot stop` during an offload does not disable later offloads (1.1.206).
- 6f: Actions tab has no Regroup; Spells tab still does (1.1.207); Wider fishing scan (1.1.207); Farm reuses its irrigation source (1.1.207); Per-kind cap keeps fishing memory (1.1.207).
- 6g: Leaf-blocked navigation unchanged (1.1.208); Bridge scaffold still clears snow (1.1.208); CollectDirt ladder exit unchanged (1.1.208); Home data write is batched (1.1.208, debounce).
- 6j: Novelty fires and does not gag the bots (1.1.211); Silas stays laconic (1.1.211); Quoted short idiom is not a repeat (1.1.211); Toggle off is silent (1.1.211).
- 6k: Peer clause reaches the prompt (1.1.212).
- 6l: SEEN fact forms from repeated completions (1.1.213); BELIEFS block reaches the prompt (1.1.213); Relation anchor fires and goes on cooldown (1.1.213); Toggle off is inert (1.1.213).
- 6m: A malformed tail never costs a scene (1.1.214); A model fact lands as INFERRED at ≤0.6 (1.1.214); Model stance delta is peers-only and once per day (1.1.214).
- 6n: No two bots talk over each other (1.1.216); Scripted lines do not interleave with a soul scene (1.1.216); A scene still gets through a scripted floor (1.1.216); The two banter lanes respect one quiet period (1.1.216); Muting scripted dialogue does not mute souls (1.1.216); Mount and animal lines stop repeating (1.1.216); A hurt wolf still gets an answer (1.1.216); The woodcut storm is gone (1.1.216); The backoff ladder widens (1.1.216); An axe in a chest wakes it back up (1.1.216); Commanded work still starts immediately (1.1.216).
- 6o: Torch stays up in the dark (1.1.217); Quit mid-hold does not reload with a torch in hand (1.1.217); Scripted lines hold off while a scene is generating (1.1.217); Scripted logs tell the truth (1.1.217); Model switch keeps voices alive (1.1.217, Codex 7a663785); Group-chat hint appears once (1.1.217, Codex 43bd5474).
- 6p: Hovel builder uses the door from the wall line (1.1.218); No fall off a raised floor (1.1.218); Line-of-sight retries stay local (1.1.218); `/bot stop` mid-egress stops cleanly (1.1.218).
- 6q: Handled lines don't become DMs (1.1.219); Bot-placed chests record their owner (1.1.219, supplies groundwork).
- 6r: Collect still works for the owner (1.1.220); Go and Dismiss still work for the owner (1.1.220); Quick Store / Quick Fetch still work for the owner (1.1.220).
- 6s: Walking away keeps the log quiet; coming back works at once (1.1.221); Never-allowed items stay put (1.1.221).
- 6t: A far table: no walk, no freeze, one quiet line (1.1.222); A table in his bag gets placed beside him (1.1.222).
- 6u: Ordinary doors still get closed behind the bots (1.1.223); No server-thread walk warning (1.1.223 tripwire); A scene that mentions you plays in full (1.1.223); Crafting window search and sort (1.1.223).
- 6v: Your own bot still works everywhere (1.1.224).
- 6w: Setup checklist, cheats-off singleplayer (1.1.225); Enable message names the blocker (1.1.225); Bot Controls keeps a Soul Chat change (1.1.225).
- 6y: Spawn and command with commands off (1.1.227); Roster and hints (1.1.227).
- 6z: Wide unchanged (1.1.228).
- 6aa: No crevice suffocation on pickups (1.1.229).
- 6ab: Bot says why it skips your base trees (1.1.230).
- 6ac: Buttons still work for the host (1.1.231).


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
- New tools: `tools/sandbox/ollama_tap.py` (prompt tap), `tools/sandbox/supplytest.py` (6s prompts), `tools/sandbox/rec.py start|stop <name>|status` (Flashback from the pause menu; the save dialog's name field is index 2, Save Replay index 6).
- A player standing on the bot's block picks up items summoned at the bot's feet: step away before a dropped-item check.
- A table set into a hillside (`setblock` into terrain) is buried: clear the cells around it first.
