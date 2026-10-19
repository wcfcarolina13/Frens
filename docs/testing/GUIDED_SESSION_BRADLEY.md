# Guided session for Bradley: field items only he can check

**Purpose:** one sitting that clears the open items in [`FIELD_SESSION_1.1.202.md`](FIELD_SESSION_1.1.202.md) that need Bradley himself: his ears on voices and dialogue, his eye on screens at his window size, his main world Nirn, and a second human on a second account. Anything a bot-only sandbox run by Claude can check is left to the sandbox sweeps and is not listed here.

**Status:** not launched. Bradley starts it when he can playtest.

**How it runs:** the [guided session protocol](GUIDED_SESSION_PROTOCOL.md). Claude tails `latest.log` and calls each verdict. Bradley does the actions and reports with `/me ok <item>` or `/me fail <item> <why>`. Each item below keeps its checklist phase and exact title so it can be ticked in `FIELD_SESSION_1.1.202.md` afterwards. The "Bradley does", "Claude watches for" and "Pass when" lines are copied from the checklist (with any 2026-10-18 ruling or sandbox note that narrows the item). Only the "Prep" lines are new.

**Built from:** origin/main `0bac19e3` (1.1.271), 267 open items. 55 need Bradley (Blocks A to D plus 2 waiting on a decision), 6 more are listed under "Maybe Claude can do it".

**Estimated time:** about 4 h in all. Block A 20 min, Block B 100 min, Block C 30 min, Block D 100 min. Blocks A to C fit one evening on one machine. Block D can be its own sitting once the second account is sorted.

**Growing list:** sandbox sweeps that hit an item only Bradley can check append it under "Appended by sweeps" at the end, with the same fields.

---

## Before the session (Claude prepares)

1. **Back up Nirn** (`instances/1.21.11/minecraft/saves/<Nirn>`) before it is opened. Nirn is only used in Block C.
2. **Instances.** Blocks A, B and D run in the sandbox instance on a fresh copy of `New World` (the copy the 1.1.270 souls sweep used). Block C runs in the `1.21.11` instance on Nirn. Same JAR in both (current release). Run the pre-deploy check before any copy.
3. **Bots.** Sandbox: Jake and Bob spawned and soul-enabled (`/bot soul enable`), plus a third bot for Block A's 3+ bot screens. Silas is spawned during Block B. A no-owner, console-spawned bot for Block D. Nirn: the usual companions as they are.
4. **Gear.** Block A: one bot with a full mixed inventory including armour. Block C: a saved horse or mount on Nirn. Block D: G's bot carries precious food. Bradley holds nothing special.
5. **Souls and voice.** Ollama running with `llama3.1:8b`. Souls master on, banter on, local on, party chat on, soul voice on, Piper with `en_US-lessac-medium` and `en_US-ryan-medium` (already in the copied Piper folder). Pocket is deliberately not installed: its install is a test item. Dreamsleeve warm server up for the one clone item if Bradley wants it (see decisions).
6. **Logging.** `/bot config diagnostics on` once at the start. `frens` DEBUG logging on for Block D's per-player mute line. LoadGoverner present as on Nirn for the governor-floor item.
7. **Window.** Bradley's window at 1708x1642 with GUI scale Auto (5) gives 342x328. Block A also needs 640x480 at scale 2 (320x240) and the largest GUI scale.
8. **Second human (Block D).** A second Minecraft account on a second 1.21.11 client: Prism can launch the same instance twice or a copy of it. Bradley's client hosts the sandbox world over LAN (H), the second client joins (G). Two clients on one account cannot join the same world, so this is a decision below.
9. **Downloads (Block B).** One Piper voice not yet installed, a Piper reinstall into an empty folder, and Pocket TTS (~850 MB plus a 228 MB model). Bradley approves each before Claude starts it.
10. **Nirn checks before launch (read-only).** Claude confirms the pressure-plate gate at 266,66,1285 still exists (from the last Nirn log or Bradley's memory) and that Nirn's `bot_chest_registry.json` holds a record with no `ownerUuid`.
11. **Flashback recording on** from launch to quit, for every block.
12. **Nothing to install in the mod list.** No new mods are needed.

---

## Block A: sandbox singleplayer, screens at Bradley's window size (20 min, 3 items)

Bradley's eye on layout and readability, with his own mouse. Sandbox world, single client.

- [ ] **Phase 6z: Narrow fits (1.1.228)**
  - Bradley does: at 342x328 and 320x240, open the Guide, bot inventory + overlay, roster, Bot Controls popups (Hobbies, Piper, Model manager), and say something long near a bot.
  - Claude watches for: no text past a panel edge, inventory fully visible (no "ealth"/"unger"), overlay tabs not colliding, overhead line wrapped to at most 3 lines.
  - Pass when: every screen fits and all buttons are reachable.
  - Prep: Window at 342x328 (his 1708x1642 window, GUI scale Auto = 5), then 320x240 (640x480 at scale 2). Bot Controls popups need 3+ bots spawned. F2 screenshot each screen.
- [ ] **Phase 6z: Scaled inventory clicks (1.1.228)**
  - Bradley does: at 320x240 in the bot inventory: click, shift-click, drag-split items on both sides incl. armour and the last column, hover for tooltips, drop an item outside the panels, use the overlay tabs and scrollbar.
  - Claude watches for: no wrong-slot moves in `[bot-inv-` lines.
  - Pass when: every click hits the slot under the cursor and tooltips sit at the cursor.
  - Prep: Same world, 320x240. Bot with a mixed inventory including armour so every column has something to move.
- [ ] **Phase 6w: Checklist fits a small window (1.1.225)**
  - Bradley does: set GUI scale to its largest value (or shrink the window), with 3+ bots, then open the checklist.
  - Pass when: Refresh and Close are on screen, and bot rows scroll with the mouse wheel.
  - Prep: Largest GUI scale, 3+ bots spawned, Ollama running so the checklist rows fill.

---

## Block B: souls and voices by ear (100 min, 14 items)

Same sandbox world. Start the Pocket install early: it runs while the listening items go ahead. Engine is Piper unless an item says otherwise.

- [ ] **Phase 1: Streaming first-word latency (1.1.175, still open)**
  - Bradley does: send Jake a DM and start a stopwatch at Enter.
  - Claude watches for: `[souls] tts correlationId=… outcome=… synthMs=… bytes=… chunks=…` and the scene's `elapsedMs=`.
  - Pass when: first audible word inside ~3 s; record the number either way.
  - Prep: Voice on, engine Piper. Bradley keeps a stopwatch handy.
- [ ] **Phase 6o: Voice on, text off still arms the floor (1.1.217)**
  - Bradley does: scripted Voice on, scripted Text off; stand near animals for two minutes.
  - Claude watches for: audible pet/context lines and their spacing.
  - Pass when: no two voiced scripted lines within four seconds of each other.
  - Prep: Scripted Voice on, scripted Text off, both bots by a pen of animals.
- [ ] **Phase 4: Voice-off beat pacing**
  - Bradley does: `/bot soul voice off`, run a group scene, then voice back on.
  - Pass when: lines still pace sensibly with no synth.
  - Prep: Two soul bots near Bradley, party chat on.
- [ ] **Phase 2: Governor floor during a scene (1.1.176)**
  - Claude watches for: LoadGoverner floor holding through the render window.
  - Pass when: no frame stall reported by Bradley during playback.
  - Prep: LoadGoverner installed as on Nirn. Bradley says out loud (`/me`) if a frame hitches during playback.
- [ ] **Phase 2: Solo-banter suppression judgement (1.1.181 open question)**
  - Bradley does: judge subjectively whether solo banter every 8–15 min is crowding out his organic local reactions (every banter submit re-arms local chat's 6–12 min cooldown).
  - Pass when: a verdict is recorded — this is a design call, not a bug check.
  - Prep: Run across the whole block. Bradley calls it at the end.
- [ ] **Phase 5: Per-bot assignment actually separates the voices (the 1.1.190 → 1.1.192 bug)**
  - Bradley does: `/bot soul voice assign bob en_US-ryan-medium`, then get Bob to speak.
  - Claude watches for: no `[souls] tts voice '{}' for {} not found under {} — using the default voice`.
  - Pass when: Bob's next line is a different voice **and Jake's is unchanged**.
  - Prep: `en_US-ryan-medium` is already in the copied Piper folder per the 1.1.270 sweep, so this needs no download.
- [ ] **Phase 5: Revert**
  - Bradley does: `/bot soul voice assign bob default`.
  - Pass when: Bob returns to the global voice, next line, no reload.
- [ ] **Phase 5: Install a voice**
  - Bradley does: `/bot soul voice install en_US-ryan-medium`.
  - Claude watches for: 25/50/75/100 % progress lines; `[souls] piper installed: binary=… voice=…`.
  - Pass when: it completes and is size/sha256 verified.
  - Prep: Download. Ryan is already installed, so Claude picks a Piper voice that is not (from `/bot soul voice list`) and substitutes it in the command. Bradley approves the download first.
- [ ] **Phase 6c: Silas persona (1.1.204)**
  - Bradley does: spawn a bot named `Silas`, `/bot soul enable Silas`, optionally
    `/bot soul voice assign Silas george`; talk to him; let banter run with Jake and Bob nearby.
  - Claude watches for: `[souls]` lines resolving profile `frens:silas` (not `frens:jake`); no
    profile-not-found warnings.
  - Pass when: he answers in the laconic omen-reading register, mentions Jake or Bob by name in at
    least one banter line, and never breaks into Jake's voice/persona.
  - Prep: Jake and Bob spawned and soul-enabled. The `george` voice is optional per the item. Claude checks `/bot soul voice list` for it first and skips the assign if it is missing.
- [ ] **Phase 5: Bob still shares Jake's Dreamsleeve clone (known open, 1.1.184)**
  - Pass when: confirmed still true (or not) and noted — a second clone needs its own reference sample; this is a known remainder, not a regression.
  - Prep: Dreamsleeve warm server running (decision below). Switch the engine to Dreamsleeve for this item only.
- [ ] **Phase 5: Background install survives menu close/reopen (1.1.175)**
  - Bradley does: start a voice or Ollama model download, close the menu, reopen it.
  - Pass when: the screen re-attaches to the live progress bar, buttons stay disabled, no second concurrent download can be started.
  - Prep: Download. Bradley approves. Pair it with the Ollama `llama3.2:3b` pull if that model is still missing.
- [ ] **Phase 5: Piper install retry on macOS (1.1.175 / 1.1.172, still open)**
  - Bradley does: Bot Control → Soul Voice → Eng… → Install Piper… → Download & Install.
  - Claude watches for: the dylib stage (~44 MB plan), and on failure the child's **stderr tail** in the on-screen message (e.g. a dyld "Library not loaded" line).
  - Pass when: install completes, or fails with a specific stderr-bearing message.
  - Prep: Download (Piper, ~44 MB dylib stage). Only meaningful on a sandbox config without Piper, so Claude points the sandbox at an empty Piper folder first. Bradley approves.
- [ ] **Phase 5: Pocket TTS installer (1.1.198)**
  - Bradley does: Eng… → install Pocket. Watch the stage text (uv or python ≥3.10 discovery, venv creation, `pip install pocket-tts==3.0.2` ~850 MB, then the smoke line that pulls the 228 MB model).
  - Claude watches for: `[souls] pocket-tts installed: {}`, `[souls] tts engine started pid=… (pocket-tts serve :{})`, `[souls] reloadSettings failed after pocket install:` (must NOT appear).
  - Pass when: install finishes, the engine starts, and the screen correctly states up front whether voice cloning is available (HF login).
  - Prep: Download (~850 MB pip plus the 228 MB model). Bradley approves. Run it early in the block, it takes a while.
- [ ] **Phase 5: Pocket voice quality vs Piper**
  - Bradley does: same line through both engines.
  - Pass when: a subjective verdict is recorded (this is the reason Pocket exists).
  - Prep: Needs the Pocket install above. Same line, both engines, back to back.

---

## Block C: Nirn checks (30 min, 7 items)

Quit the sandbox, open Nirn in the `1.21.11` instance (backed up first). Nirn already migrated through 1.1.255, so only checks of what is there now remain.

- [ ] **Phase 6u: Out through the plate gate (1.1.223)**
  - Bradley does: walk Jake and Bob out of the underground base through the oak fence gate with the pressure plate,
    as on 2026-09-25, and keep walking 10–15 blocks past it.
  - Claude watches for: `Door debug: … door=266,66,1285 open=… powered=… controlled=true`. There should be no run
    of `applyMovementInput-reject` into the fence, and no `vertical-lock acquire` toward a ladder behind the bot.
  - Pass when: both bots are through within about 5 s of you, and the game never stutters.
  - Prep: Nirn. Confirm first that the pressure-plate fence gate at 266,66,1285 outside the underground base still exists. If it is gone, Claude rebuilds it in the sandbox instead.
- [ ] **Phase 6u: The bot leaves the plate's gate alone (1.1.223)**
  - Bradley does: the same gate, and watch it as the last bot passes.
  - Claude watches for: `door-close skip: powered or controlled`, or no close by a bot at all. There should be no
    `door-closed: marked recently closed` for 266,66,1285.
  - Pass when: the plate opens and closes the gate by itself, and no bot right-clicks it shut.
  - Prep: Same gate, same walk.
- [ ] **Phase 6aa: Your base's trees still protected (1.1.229)**
  - Bradley does: on Nirn, order woodcut while standing inside your own base radius with trees around.
  - Claude watches for: `protection=base-radius` rejects for those trees, same as 1.1.228.
  - Pass when: the bot does not fell trees inside your base.
  - Prep: Nirn, standing inside Bradley's own base radius with trees around.
- [ ] **Phase 6ai: Own base over spawn still works (1.1.238)**
  - Sandbox 2026-10-17: PARTIAL: host bot mines inside its own base over spawn; torches not placeable by command, Nirn half not testable, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: host makes a base overlapping spawn, has his bot plant or place torches there.
  - Pass when: no `protected-zone` refusal for the host's bot inside his base. Nirn's home base trees stay protected with the toggle on.
  - Prep: Nirn half only (the host half passed in the sandbox): Admin toggle "Cut trees in the Spawn base: On", then check Nirn's home base trees.
- [ ] **Phase 6s: Old bot chests prompt instead of refusing (1.1.221)**
  - Bradley does: use a chest a bot placed before 1.1.219 (its registry record has no `ownerUuid`) for any of the
    checks above.
  - Claude watches for: `outcome=OPENED`, not `DENIED(DENY_UNKNOWN)`.
  - Pass when: you get the normal prompt.
  - Prep: Nirn. Claude checks Nirn's `config/frens/bot_chest_registry.json` (read-only) before the session for a record with no `ownerUuid`. If none exists the item is moot.
- [ ] **Phase 6ao: Skins and saved mounts survive the update (1.1.246)**
  - Sandbox 2026-10-18 (1.1.267): NEEDS BRADLEY (Nirn save data), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: open Nirn, spawn the usual companions, check their skins, and rejoin with a saved horse or mount.
  - Claude watches for: no `random preset` skin line for an existing bot, mount restore lines as before, a one-time `old-format mount entry skipped` only if the file had one.
  - Bradley's ruling 2026-10-18 (triage): Nirn already ran the 1.1.246 update (logs from 1.1.255 on), so this closes after one look: open Nirn, check the companions' skins, rejoin with a saved mount.
  - Pass when: every companion keeps its skin and the saved mount comes back.
  - Prep: Nirn, the usual companions, a saved horse or mount.
- [ ] **Phase 6ap: /bot base owner (1.1.248)**
  - Sandbox 2026-10-18 (1.1.267): PARTIAL: owner command and log line pass, Bases screen not read, non-op half needs a second player, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: in Nirn, `/bot base list`, then `/bot base owner "<an old base>" <you>` and open the Bases screen.
  - Claude watches for: `[base-owner] ... set owner of base`.
  - Pass when: the Bases screen shows you as owner, and a non-op player gets "Only operators or the host can reassign base ownership."
  - Prep: Nirn for the Bases screen half. The non-op refusal half runs in Block D.

---

## Block D: second human on LAN (100 min, 29 items)

Bradley hosts a sandbox world over LAN (H), the second account joins (G). G is non-op unless an item says to `/op` it. Run the non-op items first, then the op items. The Nirn Bases-screen half of "/bot base owner" is in Block C. Its non-op refusal half (a non-op player gets "Only operators or the host can reassign base ownership.") runs here too.

- [ ] **Phase 3: A fired reaction end to end, audible to a bystander**
  - Bradley does: needs a **second player** standing nearby.
  - Claude watches for: `[souls] local player=… bot=… outcome=fired routingId=… score=…`.
  - Pass when: the bystander hears it, not just the speaker.
  - Prep: Second player stands beside Bradley during a local reaction.
- [ ] **Phase 6b: Sync on join**
  - Bradley does: on H set the scripted dialogue rate to a non-default value and mute
    `ambient_chatter` **text**; then join from G and open the same screens.
  - Claude watches for: nothing — this is a UI check. (`sendConfigSync failed` in the log = fail.)
  - Pass when: G shows H's rate and text mask on open.
  - Prep: H = Bradley's client hosting a sandbox world over LAN, G = second client on the second account.
- [ ] **Phase 6b: Guest edit is rejected and reverted**
  - Bradley does: on G (not op) flip the Voice master and close the screen.
  - Claude watches for: `Rejected config save from non-operator <G>`.
  - Pass when: G's screen shows the old value again on reopen and H is unchanged.
  - Prep: G not op.
- [ ] **Phase 6b: Op edit propagates**
  - Bradley does: `/op <G>` on H; on G flip the Voice master.
  - Claude watches for: no rejection line; bots stop/start scripted voice for **both** clients.
  - Pass when: H's screen shows G's change on reopen without a relog.
- [ ] **Phase 6b: Per-player mute (the point of 1.1.203)**
  - Bradley does: on G mute `ambient_chatter` **voice**; stand both clients near a chatting bot.
  - Claude watches for: `[VoicedDialogue] sound … category ambient_chatter -> 1 recipient(s), 1 muted`
    (DEBUG — enable `frens` debug logging or accept the ear test).
  - Pass when: H still hears the line, G does not; text/subtitle unaffected on both.
  - Prep: Enable `frens` DEBUG logging before the session, or accept the ear test.
- [ ] **Phase 6b: Guest's local settings survive the visit**
  - Bradley does: before joining, note G's local Gameplay Tips + dialogue rate values; join H's
    world (whose values differ); leave; open a **single-player** world on G.
  - Claude watches for: `[config] save skipped — config is remote-authoritative` (DEBUG) while G
    was connected, none after leaving.
  - Pass when: G's single-player values are its own, not H's.
  - Prep: Note G's own single-player values before joining.
- [ ] **Phase 6j: Toggle defaults off and round-trips (1.1.211)**
  - Bradley does: `/bot soul novelty status` on the fresh config, then `/bot soul novelty on`, then
    `status` again; on a dedicated-server/LAN client also confirm the value arrives after config sync.
  - Claude watches for: the status line reading off, then on; no `UnrecognizedProperty` in the log.
  - Pass when: the second status reads on and `config/frens/settings.json5` contains
    `soulNoveltyRejectionEnabled: true`.
  - Prep: LAN half only: the sandbox sweep can tick the single-player part. Confirm the novelty value arrives on G after config sync.
- [ ] **Phase 6q: A line for another person stays with them (1.1.219, needs a second player)**
  - Bradley does: with a friend online, "<friend's name>, Jake is being weird".
  - Claude watches for: no soul routing line, no scene line, no overhear reaction for that line.
  - Pass when: neither bot reacts. Also: with the friend online, a DM follow-up window never opens (lines after Jake's
    reply need his name again).
  - Prep: Use the second player's real name in the line.
- [ ] **Phase 6r: Another player can't use your bot's storage (1.1.220, needs a second player)**
  - Bradley does: with a non-op friend on LAN who has their own bot, the friend right-clicks their own bot → Storage,
    then picks Bradley's bot in the screen's bot dropdown (it lists every online bot) and tries Refresh and Collect.
    Right-clicking Bradley's bot directly is already refused by the inventory permission check.
  - Claude watches for: `[chest-registry] denied request from <friend> for <bot>: DENY_NOT_OWNER`, and the
    same line with `collect` / `store` for the others.
  - Pass when: the friend sees "Only <bot>'s owner can do that." and gets no chest list; the bot doesn't move; nothing
    leaves any chest.
  - Prep: Friend owns a bot of their own (recruit or spawn one for G).
- [ ] **Phase 6v: 2P: a stranger can't touch your bot (1.1.224)**
  - Bradley does, from the non-op second account:
    1. Right-click Jake.
    2. Try `/bot inventory Jake` (if permitted).
    3. Open the Guide on Jake.
    4. Try to change his hunts.
  - Claude watches for: `[bot-access] denied action=… sender=<2nd> target=Jake`.
  - Pass when: every attempt is refused and nothing changes.
  - Prep: Non-op second account.
- [ ] **Phase 6v: 2P: a DM stays private (1.1.224)**
  - Bradley does:
    1. Alone on the server, DM Jake a distinctive fact ("my secret base is under the oak tree").
    2. With Bob nearby, let a group scene play.
    3. Then have the second account join and stand nearby during a scene.
  - Claude watches for: the fact may appear while you're alone. Once the second player is in earshot, expect `[souls] scene privacy stop` or no mention of the fact.
  - Pass when: the second player never hears or sees the fact.
  - Prep: Bob near, souls on, second account joins only at step 3.
- [ ] **Phase 6v: 2P: hidden bases stay hidden (1.1.224)**
  - Bradley does: the second account opens the zone list or visualiser.
  - Claude watches for: only its own, allied and server bases listed.
  - Pass when: your private base doesn't appear.
  - Prep: Bradley has a private base set before G looks.
- [ ] **Phase 6w: 2P: a non-operator gets told, not ignored (1.1.225)**
  - Bradley does, from the non-op second account:
    1. Flip Soul Chat in Bot Controls.
    2. Open the AI setup checklist and the model screen.
  - Claude watches for: one `Rejected config save from non-operator <2nd>`; repeats only at DEBUG.
  - Pass when:
    - The player sees "Only an operator or the host can change this server's Frens settings — your change was not saved."
    - Server-changing checklist buttons are disabled with "Ask the server operator / host".
    - The model screen says only the operator or host can change it.
  - Prep: Non-op second account.
- [ ] **Phase 6w: 2P: a remote operator's picks reach the server (1.1.225)**
  - Bradley does, from a remote account that is op (a LAN guest after `/op`, or a dedicated server):
    1. Open Choose model… and pick an installed model.
    2. Open Voice… and pick an engine.
    3. Toggle Soul Chat off and on in Bot Controls.
  - Claude watches for: `[ai-setup] soul model set to <tag>` and `[ai-setup] … set soul voice engine …` in the SERVER log, and no `reloadSettings failed`.
  - Pass when:
    - Both screens say "Runs on the server host".
    - A model not installed on the server shows "Pull it on the server machine: ollama pull <tag>" and no Download button.
    - The bot's next reply uses the new model, and the Soul Chat toggle takes effect without a restart.
  - Prep: G made op (`/op`) for this one. Model screen needs a model not installed on the host for the "Pull it on the server machine" half.
- [ ] **Phase 6ac: A non-op owner commands their own bot (1.1.231)**
  - Bradley does: LAN guest (or second account on a server where it is not op) recruits or is given a bot, then uses the inventory screen buttons, Follow, Guard/Patrol, a skill (woodcut, mining overlay), and the keybinds.
  - Claude watches for: the skill/follow log lines for that bot, no `[bot-access] denied`.
  - Pass when: every button works for the guest's own bot. Companion come/summon/home are still refused for non-op owners (known, deferred).
  - Prep: G owns a bot (recruited or given).
- [ ] **Phase 6ac: A non-op cannot command someone else's bot (1.1.231)**
  - Bradley does: guest opens or targets the host's bot (aim and press a key, or pick it in a screen if visible) and presses Stop / Follow.
  - Claude watches for: `[bot-access] denied` naming the guest and the host's bot.
  - Pass when: the host's bot ignores it and the guest gets a short refusal line.
- [ ] **Phase 6ac: Fortify respects wall ownership (1.1.231)**
  - Bradley does: guest uses Base Manager / Construction fortify Patch or Resume on their own wall, then near the host's wall. Also press fortify List as the guest.
  - Pass when: own wall works, the host's wall gives "You cannot work on that wall.", and List is refused for the guest (ops/host only).
  - Prep: G has a wall of their own and Bradley has one nearby.
- [ ] **Phase 6ac: Feed [Yes]/[No] and resume prompt (1.1.231)**
  - Bradley does: as the guest, trigger a precious-food offer and click [Yes] (then repeat and click [No]). Let the guest's bot die mid-skill and answer yes to resume.
  - Pass when: both clicks work (they now run `/frens feed confirm|cancel`), the host clicking the guest's [Yes] does nothing, and the resume runs for the guest's bot.
  - Prep: G's bot carries precious food. Let G's bot die mid-skill once.
- [ ] **Phase 6ac: Hunt target pick (1.1.231)**
  - Bradley does: as the guest, open Huntables and pick a target mob for their bot.
  - Pass when: the guest's bot starts hunting that mob.
- [ ] **Phase 6ad: Ops see the Admin tab on a server (1.1.232)**
  - Sandbox 2026-10-17: NOT TESTABLE in the sandbox (needs a server op or LAN guest), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: as an op on a dedicated server or as an op LAN guest, open a bot's inventory.
  - Pass when: the Admin tab is visible and works. A non-op guest does NOT see it.
  - Prep: G made op, or a dedicated server.
- [ ] **Phase 6ad: Companion owner after a recruitment reset (1.1.232)**
  - Sandbox 2026-10-17: NOT TESTABLE in the sandbox (needs a guest and a Questing world), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: in recruitment mode, as the guest who owns the recruited companion, after a recruitment reset, press Companion come and home.
  - Pass when: both work. A different non-op player is still refused.
  - Prep: Needs a Questing world (decision below) and recruitment mode.
- [ ] **Phase 6ad: Wall grantees fortify (1.1.232)**
  - Sandbox 2026-10-17: NOT TESTABLE in the sandbox (needs a guest), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: host grants the guest access to the host's wall, then the guest runs fortify Patch and Resume on it, then Expand, then tries to remove or rename it in the Base screen.
  - Pass when: Patch and Resume run, Expand gives "You cannot work on that wall.", remove and rename are refused. On a wall with no owner the guest gets "That wall has no owner. Claim it in the Base screen first."
  - Prep: Bradley grants G access to his wall first.
- [ ] **Phase 6ad: Skins are owner-checked (1.1.232)**
  - Sandbox 2026-10-17: NOT TESTABLE in the sandbox (needs a guest), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: as a LAN guest, pick a preset skin for the guest's own bot, then try the host's bot. (Bradley's ruling 2026-10-18 (triage): the "allow everyone" toggle was removed, so that half is dropped.)
  - Pass when: the guest's own bot changes skin, and the host's bot answers "You can only change the skin of your own bot."
- [ ] **Phase 6ae: Unowned bots refuse Set Home / Go To for non-admins (1.1.233)**
  - Sandbox 2026-10-17: NOT TESTABLE in the sandbox (needs a guest), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: as a LAN guest, pick an unowned bot (console-spawned, no owner) in the Base screen and press Set Home, then Go To. Repeat as the host.
  - Claude watches for: `[bot-access] denied` with action `base_set_home` / `base_go_to` for the guest only.
  - Pass when: the guest is refused, the host and the bot's owner succeed.
  - Prep: A console-spawned bot with no owner.
- [ ] **Phase 6ae: Skin toggle gone (1.1.233)**
  - Sandbox 2026-10-17: PARTIAL: toggle gone from Admin tab and Player Permissions; guest half not testable, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: open the Admin tab and the per-player admin settings.
  - Pass when: no "allow everyone to change skins" toggle anywhere, and a guest reskinning the host's bot still gets "You can only change the skin of your own bot." (also when the host's bot is offline).
  - Prep: Guest half only: the Admin-tab half passed in the sandbox.
- [ ] **Phase 6ai: Admin toggle lets bots cut Spawn trees (1.1.238)**
  - Sandbox 2026-10-17: FAIL: logs are cut but every one reports a protected-claim refusal afterwards (bug E), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: Admin, World Settings, "Cut trees in the Spawn base: On", then woodcut at spawn.
  - Bradley's ruling 2026-10-18 (triage): felling passed in later builds (1.1.266 bug E, 1.1.268 own scaffold). Open only for the two parts never rechecked.
  - Pass when: dirt in the Spawn base is still refused, and a non-admin who tries the toggle gets a refusal in chat.
  - Prep: Only the non-admin refusal half is Bradley's. The dirt half Claude can do in the sandbox.
- [ ] **Phase 6av: Remote server edits stick (1.1.254, only if a dedicated or LAN server is handy)**
  - Sandbox 2026-10-18 (1.1.267): NEEDS BRADLEY (server), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: join a server running 1.1.254 as an op, change a bot's toggle in Bot Controls, rejoin.
  - Pass when: the change is still there after rejoining.
  - Prep: Dedicated or LAN server running the current build. LAN suffices per the item.
- [ ] **Phase 9: Party-scene attribution (added at final review)**
  - Bradley does: with the **second player** present, run a "bots, …" party scene where both players say something factual.
  - Pass when: each fact ends up in the right player's memories — a fact said by the other player must not appear under Bradley's, and vice versa.
  - Prep: Second player present for a "bots, ..." party scene, and both say something factual. Needs a day rollover afterwards.
- [ ] **Phase 9: Privacy guard on `memory`**
  - Bradley does: as operator, run `/bot soul memory <Bot>` for a bot the second player uses.
  - Pass when: it still shows only **his own** memories — an operator cannot read another player's.
  - Prep: Second player has used one of the bots so it holds their memories.

---

## Needs a decision before it can run

- **Second Minecraft account and a second human (blocks all of Block D).** Whose account, which machine, and whether a friend or Bradley on a second client plays G.
- **Dedicated server (optional).** "Remote server edits stick" and the 2P remote operator item accept a LAN guest, so a server is only needed if Bradley wants the dedicated path too.
- **A Questing world.** Questing has been behind a flag since 1.1.235. "Companion owner after a recruitment reset" (Block D) and the item below need an existing Questing world: does one exist, or should Claude make one with the flag on?
- **Downloads.** Block B's voice install, Piper reinstall and Pocket install start network downloads. Bradley approves each, or they move to the sandbox sweep with his blanket OK.
- **Dreamsleeve warm server.** Is it set up and should it run for "Bob still shares Jake's Dreamsleeve clone"?
- **`ollama rm` on the shared Ollama.** Needed by the first item below.

- [ ] **Phase 6w: Model button and labels (1.1.225)**
  - Bradley does: with a model selected, remove it in a terminal (`ollama rm <tag>`), then open Choose model….
  - Pass when: that model offers Download (not a disabled "Selected"), and the 8B row says "Largest option", not "Current default".
  - Prep: Needs `ollama rm <tag>` on the shared Ollama, which Bradley's other projects also use.
- [ ] **Phase 6ag: Questing hidden (1.1.235)**
  - Sandbox 2026-10-17: PARTIAL: new-world line, no chooser, recruit refusal pass; existing Questing world not testable, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: create a new world with default config.
  - Pass when: no World Mode chooser, one line "Frens is in Admin mode: open the roster with [-] to bring in a companion.", the roster opens. `/bot recruit enable` answers "Questing mode is turned off on this server." An existing Questing world still recruits.
  - Prep: Needs an existing Questing world. Only the "existing Questing world still recruits" clause is open.

---

## Maybe Claude can do it

- **Phase 6: Global toggles UI (1.1.189, 1.1.191).** Claude can drag and click in the sandbox. Only the 9 px gutter precision might need Bradley's mouse.
- **Phase 6: Ambient mute matrix on banter/local (1.1.177, 1.1.178).** The text quadrants are log-checkable. The voice-only quadrant needs ears unless `tts correlationId` lines log reliably (the 1.1.270 sweep saw `voiced=true` with zero of them).
- **Phase 6k: Old minds load clean (1.1.212).** Nirn migrated long ago, so no pre-1.1.212 `mind.json` is left anywhere. Propose ticking as superseded, or Claude reads Nirn's current `mind.json` for the `peerStances` key.
- **Phase 6l: Old minds load clean (1.1.213).** Same as above for the `relations` key.
- **Phase 7: Open threads (1.1.198).** Claude can play the player who ignores the question for 10 min. Listed here because "exasperation" is partly a read of behaviour.
- **Phase 7: Stance shows in the prompt (1.1.198).** The clauses are log-checkable with diagnostics, but "as behaviour over the session" is a judgement call.
- **Phases 6ak to 10, sandbox-checkable items.** The running sweep covers phases 0 to 6ai only. The other open items in Phases 6ak to 6bi and 7 to 10 (outside this doc) need no Bradley: Claude can play them as the sandbox player in a later sweep.

---

## Counts

| Block | Items |
|---|---|
| A: screens at Bradley's window size | 3 |
| B: souls and voices by ear | 14 (+1 appended) |
| C: Nirn | 7 (+1 appended) |
| D: second human on LAN | 29 |
| Needs a decision | 2 (+2 appended, 1 ruled 2026-10-19) |
| **Total for Bradley** | **59** (55 + 5 appended by pass 3, 1 ruled) |
| Maybe Claude can do it | 6 |

Split items are counted once, in the block that holds Bradley's half. Tick an item in `FIELD_SESSION_1.1.202.md` only when every half named in its Prep line has passed.

---

## Appended by sweeps

<!-- Sweeps append items here: phase + exact title, the three copied lines, a Prep line, and which block it joins. Update the counts table. -->

From the 0-6ai sweep pass 3 (1.1.271, 2026-10-18, `FIELD_0_6AI_SANDBOX_2026-10-18.md`):

- [ ] **Phase 6: Category-muting round-trip (1.1.175)** (joins Block B)
  - Bradley does: mute `ambient_chatter` in **both** Adv menus (text and voice).
  - Claude watches for: no chat fallback leaking the line as GENERAL (the "Nice bird!" bug).
  - Pass when: scripted one-liners are silent in chat **and** voice.
  - Prep: Pass 3 S7 showed the text half (no ambient line in chat, no fallback). The voice half is not in the log: `Played sound` prints before the per-listener mute and the muted count is DEBUG. Bradley listens for ambient lines ("If you need me, I'm listening.", "teeth itch") for two minutes, or Claude turns on `frens` DEBUG and reads `-> 0 recipient(s), 1 muted`.
- [ ] **Phase 6b: Dreamsleeve row on macOS (1.1.203)** (joins Block C)
  - Bradley does: Soul Voice engine chooser.
  - Pass when: the Dreamsleeve row is **not** greyed and reads "Available." / "Use Dreamsleeve" as before (the grey-out must only trigger off macOS).
  - Prep: In the sandbox the row reads "Not set up on this machine" with the install button greyed, because the sandbox has no Dreamsleeve folder. Open the chooser on Nirn, where Dreamsleeve is installed.
- [x] **Phase 6c: Spells key opens the inventory Spells tab (1.1.204)** (decision answered 2026-10-19, leaves this doc)
  - Bradley's ruling: not Questing-only. The key should open the Spells tab in any world, Admin mode included; each spell stays gated by its own conditions (artifacts and similar). Questing is a separate quest/lore layer. Spellbook creation and the resurrection ritual also stay available with Questing off (they add flavour). Now a fix item (finding 30 in `FIELD_0_6AI_SANDBOX_2026-10-18.md`; resurrection is recruitment-gated today); re-test in the sandbox after the fix, no session needed.
- [ ] **Phase 6: `vetoed:muted` only in the right condition (1.1.182)** (joins "Needs a decision")
  - Pass when: it appears only with Text master off AND (Voice or Soul Voice) off.
  - Prep: Decision first. Since the 1.1.193 lane split the banter/local surface listens only to Soul Chat and Soul Voice (`SoulRuntime` ambientTextOpen/ambientVoiceOpen); the scripted Text master no longer reaches it, and with Soul Chat off the pipeline is down before the muted check. Rule the new condition (Soul Chat off AND Soul Voice off?) or retire the item.
- [ ] **Phase 10: Toggle everything off** (joins "Needs a decision")
  - Bradley does: `/bot soul banter off`, `/bot soul local off`.
  - Pass when: both directors go fully dark — nothing matching `[souls] banter` or `[souls] local` for the remainder.
  - Prep: Decision first. Pass 3 S7: local went dark, but the Active lane (its own toggle) kept logging `banter lane=ACTIVE outcome=vetoed:...`. Should `banter off` also silence Active, or does the item mean Idle only?
