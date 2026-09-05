# Field Session — Frens 1.1.202

**Version under test:** `frens-1.1.209-release+1.21.11.jar` (1.1.201 memory digest + 1.1.202 torch/creeper diagnostics and the creeper fuse fix + 1.1.203 config sync / per-player mute masks — Phase 6b + 1.1.204 backlog run — Phase 6c + 1.1.205 loose ends — Phase 6d + 1.1.206 follow-ups — Phase 6e + 1.1.207 crafting/water — Phase 6f + 1.1.208 refactors — Phase 6g + 1.1.209 fortify extraction — Phase 6h). Session protocol: `GUIDED_SESSION_PROTOCOL.md` beside this file.
**Date:** ____________  **Instance:** PrismLauncher `1.21.11`
**Server log Claude tails:** `~/Library/Application Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log`

Nothing has been field-tested since 1.1.184. This is the merged, deduplicated checklist for **1.1.175 → 1.1.209** plus the Lane 1 / Lane 2 items from `RALPH_TASK.md` (Backlog Lineup 2026-09-03). One continuous session, run in order — souls are enabled once, calm tests precede noisy ones, day-boundary tests sit near the end, destructive resets last.

## How the session runs

- **Claude** tails `latest.log` live, greps the prefixes named per item, and calls pass/fail.
- **Bradley** executes the "Bradley does" line and reports what he saw/heard.
- Every item has three sub-lines: **Bradley does** / **Claude watches for** / **Pass when**.
- Record-keeping rule inherited from `SOUL_COMMUNICATION_PILOT.md`: log **correlation ids and outcomes, never message bodies**.
- Related runbooks — link, don't duplicate: [`SOUL_COMMUNICATION_PILOT.md`](SOUL_COMMUNICATION_PILOT.md) (its "Manual — to be executed in-game" section is still the authority for DM edge cases: disconnect/death/dimension, restart persistence, remote perception leakage, reserved deterministic routing) and [`IN_GAME_AUDIT_MASTER.md`](IN_GAME_AUDIT_MASTER.md) (Fast Daily Smoke + Full Reliability Sweep for the non-soul stack).
- Useful greps: `[souls]`, `[VoicedDialogue]`, `[config]`, `Rejected config save`, `travel-wait`, `[guard-escape]`, `placed no blocks reason=`, `[furnace-offload]`, `pulled`, `Tool select:`, `Tool switch:`, `remembered`, `Can't craft`, `outcome=`, `vetoed:`, `verdict`, `memory digest`, `mind consolidated`, `Idle wooden fallback`, `torch-hold`, `scene-playback`, `tts`.
- Veto vocabulary in the log: `vetoed:cooldown`, `vetoed:salience`, `vetoed:danger`, `vetoed:roster-lost`, `vetoed:hard-reject`, `vetoed:changed-before-capture`, `vetoed:muted`.

---

## Phase 0 — Setup (once, at world start)

- [ ] **World up on 1.1.202, souls master on**
  - Bradley does: launch the `1.21.11` instance, load the test save, confirm the JAR version in the mod list.
  - Claude watches for: `[souls] runtime started; masterEnabled=true settingsValid=true`; the benign `[Frens] LLM runtime not available (build without -PaiEnabled?)` line is expected (that is the CLASSIC DJL path, souls run on Ollama).
  - Pass when: runtime started with `masterEnabled=true settingsValid=true`.
- [ ] **Ollama reachable**
  - Bradley does: nothing; check chat for errors.
  - Claude watches for: absence of `[souls] ollama not reachable at {}` and of `[souls] Soul conversations will be sent to non-local Ollama host`.
  - Pass when: neither line appears.
- [ ] **Bind both bots**
  - Bradley does: `/bot soul enable Jake` then `/bot soul enable Bob` (Bob **must** be re-enabled once — his pre-1.1.184 binding was `frens:jake`).
  - Claude watches for: `"Bob is now speaking as Bob."`; `[souls] rebound {} from frens:jake to its own profile {}` if the 100-tick legacy migration fires instead.
  - Pass when: Bob reports his own profile, not Jake's.
- [ ] **Toggles on**
  - Bradley does: `/bot soul banter on`, `/bot soul local on`, `/bot soul digest status` (expect ON by default; `/bot soul digest on` if not), `/bot soul voice on`.
  - Claude watches for: `"Memory digest is ON. Runs at each Minecraft day rollover for soul-bound bots."`
  - Pass when: all four report ON.
- [ ] **Voice engine chosen**
  - Bradley does: Bot Control → Soul Voice → Eng… → pick the engine for this session (Piper / Dreamsleeve / Pocket). Note which one in the bundle.
  - Claude watches for: `[souls] tts engine started pid=…` — one of `model={}`, `(dreamsleeve warm server)`, `(pocket-tts serve :{})`.
  - Pass when: an engine pid line appears and no `[souls] tts engine unavailable, voice disabled:`.
- [ ] **Baseline status snapshot**
  - Bradley does: `/bot soul status`, `/bot soul banter status`, `/bot soul local status`.
  - Claude watches for: the live veto reason and cooldown printed by banter status.
  - Pass when: all three print without error; snapshot pasted into the bundle as the baseline.

---

## Phase 1 — Calm solo (quiet, one bot in the roster)

Run this with **Bob dismissed or out of earshot** so the roster is genuinely 1.

- [ ] **Solo remark delivers (1.1.181, 1.1.184)**
  - Bradley does: stand quiet near Jake for 90 s after `/bot soul banter now`; if nothing, wait out the 4–8 min grace.
  - Claude watches for: `[souls] scene correlationId=… rosterSize=1 … outcome=` and a delivery line; NOT `outcome=failed:MALFORMED`.
  - Pass when: a remark addressed to Bradley is delivered within 4–8 min of world start (8–15 min thereafter), `rosterSize=1`, delivered.
- [ ] **Exactly one continuation (1.1.181)**
  - Bradley does: answer the remark within 30 s.
  - Claude watches for: one further scene, then nothing; no chain.
  - Pass when: exactly one continuation, no third line.
- [ ] **Ignored window lapses silently**
  - Bradley does: let the next remark go unanswered.
  - Claude watches for: no error, no retry storm.
  - Pass when: the window closes with nothing logged as a failure.
- [ ] **Streaming first-word latency (1.1.175, still open)**
  - Bradley does: send Jake a DM and start a stopwatch at Enter.
  - Claude watches for: `[souls] tts correlationId=… outcome=… synthMs=… bytes=… chunks=…` and the scene's `elapsedMs=`.
  - Pass when: first audible word inside ~3 s; record the number either way.
- [ ] **llama3.2:3b speed vs llama3.1:8b (1.1.175, still open)**
  - Bradley does: three DMs on the current model, then `/bot soul model <other>` and three more.
  - Claude watches for: `[souls] soul model set to {}` then per-turn `elapsedMs=` for both sets.
  - Pass when: both medians recorded and a preference called. Watch for 3B's known weakness — roster-of-one scenes going `MALFORMED` (1.1.184).
- [ ] **Failed generation retries (1.1.184)**
  - Bradley does: nothing; opportunistic.
  - Claude watches for: any `outcome=failed:` followed by a fresh attempt within ~2 min.
  - Pass when: a failure is followed by a retry, not silence. (Skip-and-note if none occurs.)

---

## Phase 2 — Two-bot banter and pacing (1.1.176, 1.1.177, 1.1.181, 1.1.188)

- [ ] **Banter fires with 2 bots**
  - Bradley does: bring Bob back, stand near both, keep chat quiet, wait out the 4–8 min grace; `/bot soul banter status` while waiting.
  - Claude watches for: `[souls] banter lane=… player=… outcome=fired routingId=… roster=… seedChars=… act=… topic="…"` and the veto reason changing as the wait proceeds.
  - Pass when: a two-bot scene fires and both bots speak.
- [ ] **Distinct display names keep the scene coherent (1.1.176)**
  - Claude watches for: speaker attribution in `scene-playback … speaker=`.
  - Pass when: no line is attributed to the wrong bot; no unknown speaker dropped.
- [ ] **No same-speaker-twice runs (1.1.197)**
  - Claude watches for: consecutive `scene-playback … line=n/N speaker=` with the same speaker.
  - Pass when: multi-speaker scenes alternate; runs are merged into one turn.
- [ ] **Nobody answers for the player (1.1.195)**
  - Pass when: no bot line puts words in Bradley's mouth or credits him with bot events.
- [ ] **~1/3 of banter ends addressed to the player (1.1.181)**
  - Claude watches for: `addressPlayer=true` on the fired line.
  - Pass when: across the session roughly 2/3 plain, 1/3 player-addressed.
- [ ] **`/bot soul banter now` primes both lanes (1.1.182, 1.1.188)**
  - Bradley does: `/bot soul banter now`, stand quiet 90 s.
  - Pass when: a scene inside ~10 s of eligibility; status shows both IDLE and ACTIVE lanes.
- [ ] **Active lane fires on work (1.1.188)**
  - Bradley does: set a bot woodcutting, stay nearby.
  - Claude watches for: `lane=ACTIVE … outcome=fired` within ~2.5 min.
  - Pass when: it fires and the scene mentions the work.
- [ ] **Pacing sliders (1.1.188)**
  - Bradley does: Banter row → **Rate…** → set Scripted to 100, play a few minutes; then 0; leave Idle at 50. Restart the client once mid-session to check persistence.
  - Pass when: 100 → denser pet/scripted remarks, 0 → near-silent, values survive restart.
- [ ] **Combat aborts a scene (1.1.177)**
  - Bradley does: pick a fight mid-scene.
  - Claude watches for: `scene-playback routingId=… outcome=ambient-combat-abort`.
  - Pass when: the scene stops.
- [ ] **Walking away / bystander earshot (1.1.176)**
  - Bradley does: walk out of earshot mid-scene; on another scene, stand at the edge of earshot.
  - Claude watches for: `scene-playback … outcome=skipped-speaker-gone`, `listeners=`.
  - Pass when: playback degrades cleanly, listener counts match who was actually near.
- [ ] **Cooldown spacing over the session**
  - Claude watches for: banter and local alternating rather than stacking.
  - Pass when: no two surfaces fire back to back repeatedly.
- [ ] **Solo-banter suppression judgement (1.1.181 open question)**
  - Bradley does: judge subjectively whether solo banter every 8–15 min is crowding out his organic local reactions (every banter submit re-arms local chat's 6–12 min cooldown).
  - Pass when: a verdict is recorded — this is a design call, not a bug check.
- [ ] **Governor floor during a scene (1.1.176)**
  - Claude watches for: LoadGoverner floor holding through the render window.
  - Pass when: no frame stall reported by Bradley during playback.

---

## Phase 3 — Local chat (1.1.178, 1.1.179)

- [ ] **Toggle off is fully dark**
  - Bradley does: `/bot soul local off`, chat unaddressed lines for a minute.
  - Claude watches for: nothing matching `[souls] local`.
  - Pass when: zero local lines, and DM replies unchanged.
- [ ] **Salience moves with the line**
  - Bradley does: `/bot soul local on`; alternate deliberately boring and deliberately salient unaddressed lines, running `/bot soul local status` between them.
  - Claude watches for: `[souls] local player=… outcome=` with the score, and the veto reason moving (`vetoed:salience` vs firing).
  - Pass when: the score and veto track the content.
- [ ] **A fired reaction end to end, audible to a bystander**
  - Bradley does: needs a **second player** standing nearby.
  - Claude watches for: `[souls] local player=… bot=… outcome=fired routingId=… score=…`.
  - Pass when: the bystander hears it, not just the speaker.
- [ ] **One continuation, then nothing**
  - Bradley does: answer inside 30 s; on the next one, let it lapse.
  - Pass when: exactly one continuation; lapse is silent.
- [ ] **Addressing a bot mid-window closes it**
  - Pass when: the window closes on explicit address.
- [ ] **Danger veto**
  - Bradley does: pick a fight during a local window.
  - Claude watches for: `vetoed:danger`.
  - Pass when: the veto appears and nothing fires.
- [ ] **Server-thread cost under load (1.1.178, never measured)**
  - Bradley does: stand several soul bots near an active chatter and chat continuously.
  - Claude watches for: tick-lag warnings, `[souls] local capture failed for bot`.
  - Pass when: no measurable tick lag attributable to the earshot scan.
- [ ] **Roster-lost backoff (1.1.179)**
  - Claude watches for: repeated `vetoed:roster-lost` — must be spaced, not per-line.
  - Pass when: the cooldown is pushed on each roster-lost.

---

## Phase 4 — Group chat / PARTY (1.1.176)

- [ ] **"bots, …" two-bot scene**
  - Bradley does: `bots, what should we do next?` with both near.
  - Claude watches for: `[souls] scene-routing routingId=… player=… outcome=… candidates=… eligible=… routingMs=…` then a PARTY scene.
  - Pass when: both bots contribute, ≤2 lines each.
- [ ] **Mixed soul / non-soul broadcast**
  - Bradley does: same with a non-soul bot present.
  - Pass when: the non-soul bot uses the legacy path, no crosstalk.
- [ ] **Voice-off beat pacing**
  - Bradley does: `/bot soul voice off`, run a group scene, then voice back on.
  - Pass when: lines still pace sensibly with no synth.
- [ ] **A group scene resets the banter timer**
  - Pass when: banter's next-eligible pushes out after the scene.
- [ ] **`soulPartyEnabled=false` → legacy loop**
  - Bradley does: toggle it off in settings, one "bots, …" line, toggle back on.
  - Pass when: legacy behaviour, no PARTY scene.

---

## Phase 5 — Voices (1.1.190, 1.1.192, 1.1.198)

- [ ] **Catalogue and bindings**
  - Bradley does: `/bot soul voice list`.
  - Pass when: lessac shows installed, each **spawned bot** is listed with its bound profile and effective voice (the 1.1.192 bot-keyed view), Jake and Bob both on the global voice.
- [ ] **Install a voice**
  - Bradley does: `/bot soul voice install en_US-ryan-medium`.
  - Claude watches for: 25/50/75/100 % progress lines; `[souls] piper installed: binary=… voice=…`.
  - Pass when: it completes and is size/sha256 verified.
- [ ] **Per-bot assignment actually separates the voices (the 1.1.190 → 1.1.192 bug)**
  - Bradley does: `/bot soul voice assign bob en_US-ryan-medium`, then get Bob to speak.
  - Claude watches for: no `[souls] tts voice '{}' for {} not found under {} — using the default voice`.
  - Pass when: Bob's next line is a different voice **and Jake's is unchanged**.
- [ ] **Revert**
  - Bradley does: `/bot soul voice assign bob default`.
  - Pass when: Bob returns to the global voice, next line, no reload.
- [ ] **Bob still shares Jake's Dreamsleeve clone (known open, 1.1.184)**
  - Pass when: confirmed still true (or not) and noted — a second clone needs its own reference sample; this is a known remainder, not a regression.
- [ ] **Background install survives menu close/reopen (1.1.175)**
  - Bradley does: start a voice or Ollama model download, close the menu, reopen it.
  - Pass when: the screen re-attaches to the live progress bar, buttons stay disabled, no second concurrent download can be started.
- [ ] **Piper install retry on macOS (1.1.175 / 1.1.172, still open)**
  - Bradley does: Bot Control → Soul Voice → Eng… → Install Piper… → Download & Install.
  - Claude watches for: the dylib stage (~44 MB plan), and on failure the child's **stderr tail** in the on-screen message (e.g. a dyld "Library not loaded" line).
  - Pass when: install completes, or fails with a specific stderr-bearing message.
- [ ] **Pocket TTS installer (1.1.198)**
  - Bradley does: Eng… → install Pocket. Watch the stage text (uv or python ≥3.10 discovery, venv creation, `pip install pocket-tts==3.0.2` ~850 MB, then the smoke line that pulls the 228 MB model).
  - Claude watches for: `[souls] pocket-tts installed: {}`, `[souls] tts engine started pid=… (pocket-tts serve :{})`, `[souls] reloadSettings failed after pocket install:` (must NOT appear).
  - Pass when: install finishes, the engine starts, and the screen correctly states up front whether voice cloning is available (HF login).
- [ ] **Pocket voice quality vs Piper**
  - Bradley does: same line through both engines.
  - Pass when: a subjective verdict is recorded (this is the reason Pocket exists).
- [ ] **Unknown engine id fails loudly (1.1.198)**
  - Pass when: no silent fallback to Piper is observed anywhere in the session.

---

## Phase 6 — Muting matrix and live settings (1.1.175, 1.1.182, 1.1.184, 1.1.193)

- [ ] **Category-muting round-trip (1.1.175)**
  - Bradley does: mute `ambient_chatter` in **both** Adv menus (text and voice).
  - Claude watches for: no chat fallback leaking the line as GENERAL (the "Nice bird!" bug).
  - Pass when: scripted one-liners are silent in chat **and** voice.
- [ ] **The separation proof (1.1.182)**
  - Bradley does: with ambient_chatter muted in both Adv menus, wait for banter.
  - Pass when: scripted one-liners silent, **banter still speaks**.
- [ ] **Ambient mute matrix on banter/local (1.1.177, 1.1.178)**
  - Bradley does: text muted → expect voice-only; voice muted → expect text-only and no synth in the log; both muted → expect `vetoed:muted` and **zero generations**.
  - Claude watches for: `scene-playback routingId=… line=n/N outcome=skipped-muted`, absence of `[souls] tts correlationId=` when voice is muted.
  - Pass when: all three quadrants behave as stated.
- [ ] **`vetoed:muted` only in the right condition (1.1.182)**
  - Pass when: it appears only with Text master off AND (Voice or Soul Voice) off.
- [ ] **Two lanes, two masters (1.1.193)**
  - Bradley does: Soul Voice on + Scripted Voice off.
  - Pass when: soul lines are still spoken while scripted ones are not.
- [ ] **Live-settings apply (1.1.184)**
  - Bradley does: save any per-bot toggle mid-session.
  - Pass when: it acts immediately, no restart, and every router notice uses the bot's real name.
- [ ] **Global toggles UI (1.1.189, 1.1.191)**
  - Bradley does: expand the global toggle list; drag the scroll thumb; click the track; click the 9 px gutter.
  - Pass when: at least 5 rows visible, footer not buried, gutter clicks do **not** toggle the row underneath, dividers stop at the gutter.
- [ ] **At most one bot comments on the same thing (1.1.184)**
  - Bradley does: stand two bots near a parrot.
  - Pass when: at most one of them comments.

---

## Phase 6b — Config sync and per-player mute masks (1.1.203)

Single-player items first (same world, no extra client). The LAN pair needs a **second Frens
client** — open the world to LAN from the 1.21.11 instance and join it from a second 1.21.11
launch (Prism: right-click the instance → *Launch* again, or a copy of the instance); the
`1.21.10 TEST` instance cannot join a 1.21.11 world. Skip the pair if only one client is
available and record it under "Not covered".

- [ ] **Cheats-off single-player still saves (1.1.203, review fix)**
  - Bradley does: in a world created with cheats **off**, flip any global toggle (e.g. Gameplay
    Tips) in Bot Control, close the screen, reopen it.
  - Claude watches for: **no** `Rejected config save from non-operator` in `latest.log`.
  - Pass when: the toggle stays flipped after reopen and after `/reload`-free relog.
- [ ] **Mute screen sends a mask, not a global save (1.1.203)**
  - Bradley does: mute `reactions` in the Voice "Adv…" menu.
  - Claude watches for: the next reactions line logging `[VoicedDialogue] Muted (category reactions)`
    (baseline path — single-player shares the list) and no `sendSaveConfigPacket` debug noise.
  - Pass when: the line is silent in voice; text fallback unaffected.
- [ ] **RAM warning on the Download button (1.1.203)**
  - Bradley does: LLM chip → model manager.
  - Pass when: on this 32 GB machine every button reads `Download`/`Use`/`In use ✔` with **no** ⚠
    and no red RAM note (nothing recommends more than 12 GB). If any ⚠ shows, that's a fail —
    record the model tag.
- [ ] **Dreamsleeve row on macOS (1.1.203)**
  - Bradley does: Soul Voice engine chooser.
  - Pass when: the Dreamsleeve row is **not** greyed and reads "Available." / "Use Dreamsleeve" as
    before (the grey-out must only trigger off macOS).

**LAN pair (optional — needs a second client; host = H, guest = G)**

- [ ] **Sync on join**
  - Bradley does: on H set the scripted dialogue rate to a non-default value and mute
    `ambient_chatter` **text**; then join from G and open the same screens.
  - Claude watches for: nothing — this is a UI check. (`sendConfigSync failed` in the log = fail.)
  - Pass when: G shows H's rate and text mask on open.
- [ ] **Guest edit is rejected and reverted**
  - Bradley does: on G (not op) flip the Voice master and close the screen.
  - Claude watches for: `Rejected config save from non-operator <G>`.
  - Pass when: G's screen shows the old value again on reopen and H is unchanged.
- [ ] **Op edit propagates**
  - Bradley does: `/op <G>` on H; on G flip the Voice master.
  - Claude watches for: no rejection line; bots stop/start scripted voice for **both** clients.
  - Pass when: H's screen shows G's change on reopen without a relog.
- [ ] **Per-player mute (the point of 1.1.203)**
  - Bradley does: on G mute `ambient_chatter` **voice**; stand both clients near a chatting bot.
  - Claude watches for: `[VoicedDialogue] sound … category ambient_chatter -> 1 recipient(s), 1 muted`
    (DEBUG — enable `frens` debug logging or accept the ear test).
  - Pass when: H still hears the line, G does not; text/subtitle unaffected on both.
- [ ] **Guest's local settings survive the visit**
  - Bradley does: before joining, note G's local Gameplay Tips + dialogue rate values; join H's
    world (whose values differ); leave; open a **single-player** world on G.
  - Claude watches for: `[config] save skipped — config is remote-authoritative` (DEBUG) while G
    was connected, none after leaving.
  - Pass when: G's single-player values are its own, not H's.

---

## Phase 6c — Backlog run (1.1.204): bundles, Spells key, Silas, pillar reason, travel-wait

All single-player. Do the Silas item early if you want him in the roster for Phases 2–4 banter
(three bots), otherwise run it here.

- [ ] **Spells key opens the inventory Spells tab (1.1.204)**
  - Bradley does: look at Jake, press the Spells keybind; then press it from the radial (slot 3).
  - Claude watches for: nothing in the log on success; `Out of range or wrong dimension` in chat = fail
    (should not happen — the host is op).
  - Pass when: the **bot inventory** screen opens already on the Spells tab both times, Enchant/Anvil
    buttons behave as before, and the old standalone spells popup never appears.
- [ ] **Crafting with planks only inside a bundle (1.1.204)**
  - Bradley does: put all of Jake's planks into a bundle (nothing loose), give a task that needs
    planks (e.g. `/bot skill woodcut` with no axe → it crafts one, or ask for a crafting table).
  - Claude watches for: no `no planks` / crafting refusal; the planks reappear as a loose stack
    (extract-first) right before the craft.
  - Pass when: the craft succeeds; the bundle is lighter by exactly what was used.
- [ ] **Bundled food counts (1.1.204)**
  - Bradley does: bag Jake's only food in a bundle, `/bot set hunger 6 Jake`.
  - Claude watches for: the eat path pulling food out of the bundle (one stack becomes loose) then
    eating; **no** 2 s stall on the tick thread (TPS steady).
  - Pass when: he eats within a few seconds; food bar rises.
- [ ] **Silas persona (1.1.204)**
  - Bradley does: spawn a bot named `Silas`, `/bot soul enable Silas`, optionally
    `/bot soul voice assign Silas george`; talk to him; let banter run with Jake and Bob nearby.
  - Claude watches for: `[souls]` lines resolving profile `frens:silas` (not `frens:jake`); no
    profile-not-found warnings.
  - Pass when: he answers in the laconic omen-reading register, mentions Jake or Bob by name in at
    least one banter line, and never breaks into Jake's voice/persona.
- [ ] **Wedged bot: pillar reason + 30 s back-off (1.1.204, P1 escape)**
  - Bradley does: dig a 1×1×2 hole under a guarding/patrolling bot with cobblestone in inventory;
    cover the top with a block so pillaring is impossible.
  - Claude watches for: `pillar recovery placed no blocks reason=<x>` (x names the actual break —
    `feet-occupied`, `overhead-mine-failed`, `not-airborne`, `place-rejected:…`), then
    `[guard-escape] <bot> still below surface after recovery; cooldown 30s reason=<x>`.
  - Pass when: the reason is a concrete break, not blank, and the retry spacing becomes ≥30 s
    (was ~12 s). Remove the cover: he pillars out on the next attempt.
- [ ] **Travel-wait: hobby during a fast-travel cooldown (1.1.204, P1 idle)**
  - Bradley does: with idle hobbies **on**, give Jake a lodestone compass + a registered base; do
    one fast-travel (`/bot come` from far away), then immediately `/bot come` again from ≥100 blocks.
  - Claude watches for: chat `Jake is resting Xm Ys before traveling to …; I'll keep busy
    meanwhile`; `travel-wait enqueued Jake remaining=…t`; a `-> HOBBY` policy line; a hobby
    task starting; later `travel-wait cooldown expired for Jake (waited …t); retrying travel`.
  - Pass when: he does a hobby instead of standing still, then fast-travels on his own when the
    cooldown ends (≤3 min), with no repeated `giving up … after 3 retries`.
- [ ] **Travel-wait cancels on stop (1.1.204)**
  - Bradley does: during the wait above, `/bot stop Jake`.
  - Pass when: no `retrying travel` line fires later — he stays put.
- [ ] **Travel-wait offload stays deferred (1.1.204, known)**
  - Bradley does: same setup with hobbies **off**, inventory ≥85 % full, a registered chest nearby.
  - Claude watches for: exactly one `travel-wait offload deferred (no worker launch path)` per
    wait — not one per tick.
  - Pass when: the line appears once and the bot travels when the cooldown ends. (Real offload is a
    follow-up; this item just proves the log isn't spamming.)
- [ ] **Hotbar-lock combat honesty (1.1.204, side effect)**
  - Bradley does: put Jake in a boat with his sword outside the hotbar, spawn a zombie nearby.
  - Pass when: he does not swing a random item; either he dismounts and fights or stays passive —
    no wrong-item swings. Record what he did.

---

## Phase 6d — Loose ends (1.1.205): bundle reach, ambient offload, furnace fuel

- [ ] **Bundled cobblestone still gets a pillar (1.1.205)**
  - Bradley does: put ALL of Jake's cobblestone in a bundle (none loose), wedge him in a 1×1×2 hole
    with an open top while guarding.
  - Claude watches for: `ensureAtSurface: Jake pulled N scaffold stack(s) out of bundles for pillar
    recovery` then a successful pillar (no `placed no blocks`).
  - Pass when: he pillars out; the bundle is lighter by the stacks used.
- [ ] **Hand-bundled better pickaxe gets pulled (1.1.205)**
  - Bradley does: give Jake a stone pickaxe loose and a diamond pickaxe INSIDE a bundle; `/bot skill
    mining` on stone.
  - Pass when: the diamond pickaxe appears loose and is the one in hand within the first few blocks;
    no repeated extraction attempts (one stack moves once).
- [ ] **Bundled armor equips only if better (1.1.205)**
  - Bradley does: Jake wearing iron chestplate; put a leather chestplate in a bundle → nothing should
    happen; then put a diamond chestplate in a bundle.
  - Pass when: leather stays bundled; diamond is extracted and equipped on the next armor audit.
- [ ] **Boat + out-of-hotbar sword, FarmSkill/RideSync variants (1.1.205)**
  - Bradley does: in a boat with the sword outside the hotbar, ask him to place a boat/minecart
    (RideSync) or scoop water (farm) — both should refuse cleanly rather than use slot 0.
  - Pass when: no wrong-item use; chat/log shows the action declined.
- [ ] **Travel-wait offload to an existing chest (1.1.205)**
  - Bradley does: hobbies OFF, inventory ≥85 % full, a registered chest within ~12 blocks; trigger the
    cooldown wait (`/bot come` twice from far).
  - Claude watches for: `travel-wait offloading to existing chest` ONCE, an AMBIENT task
    `travel-wait-offload` starting, items landing in the chest, then `cooldown expired … retrying travel`.
  - Pass when: exactly one offload walk; no head-swivel during the walk (auto-face suppressed); if he was
    following you, follow resumes afterwards.
- [ ] **Offload backoff on a full chest (1.1.205)**
  - Bradley does: same, but the chest is completely full.
  - Pass when: at most 2 attempts ≥60 s apart, then no more offload walks for that wait.
- [ ] **`/bot stop` during the offload does not poison later offloads (1.1.205)**
  - Bradley does: `/bot stop` mid-walk, then trigger a fresh wait later.
  - Pass when: the later wait can still offload (the abort did not count as a failure).
- [ ] **Furnace fuel fallback (1.1.205)**
  - Bradley does: no chest anywhere near, no chest materials (take his planks below 32 and remove
    logs), leaves + sticks in inventory, a furnace within 12 blocks (not in a protected zone); force an
    offload (`/bot store` or a full-inventory drop sweep).
  - Claude watches for: `[furnace-offload] Jake gave N item(s) to furnace at …`; chat "No chest nearby -
    dropped fuel into a furnace instead."
  - Pass when: leaves/sticks are in the furnace fuel slot, planks stayed at ≥32, nothing damageable or
    bundled was given. Repeat with a furnace that already holds coal → he skips it.

---

## Phase 6e — Follow-ups (1.1.206): on-thread tool swaps, hotbar target, offload latch

- [ ] **Mining first-swing hitch is one tick, not more (1.1.206)**
  - Bradley does: `/bot skill mining` with the best pickaxe OUTSIDE the hotbar.
  - Claude watches for: `Swapping best tool from main inventory slot N to hotbar slot M` once; no
    `Tool select: server-thread hop failed` / `on-thread step failed`; TPS steady.
  - Pass when: the swap happens before the first swing, ≤1 tick pause, then mining proceeds normally.
- [ ] **Swap re-validation is rare (1.1.206)**
  - Bradley does: same as above, and while he mines pull an item out of his inventory via the bot
    inventory screen once.
  - Claude watches for: at most one `Tool select: slot N no longer holds … — re-running scan once`
    (DEBUG) around that moment; never a repeating stream of them.
  - Pass when: he continues with a valid tool (the hotbar pick, or hands if none), no wrong-item swing.
- [ ] **Full unlocked hotbar keeps the selected slot (1.1.206)**
  - Bradley does: fill all 9 hotbar slots, select slot 5, have him scoop water (farm) or place a boat.
  - Pass when: the swapped-in item lands in slot 5 (the selected one), not slot 0.
- [ ] **`/bot stop` during an offload does not disable later offloads (1.1.206)**
  - Bradley does: trigger a travel-wait offload (Phase 6d setup), `/bot stop` mid-walk, then trigger a
    fresh wait later.
  - Claude watches for: no second failure counted; the later wait still logs
    `travel-wait offloading to existing chest`.
  - Pass when: the later offload runs. (Two genuine failures in one wait still stop further attempts.)

---

## Phase 6f — Crafting messages + water memory (1.1.207)

- [ ] **Actions tab has no Regroup; Spells tab still does (1.1.207)**
  - Bradley does: open the bot inventory → Actions tab ("Orders & Travel"), then the Spells tab.
  - Pass when: Regroup appears only on Spells (Movement) and works from there.
- [ ] **`/bot direction reset` is the real name (1.1.207)**
  - Bradley does: set a strip-mine direction by standing behind him, then `/bot direction reset`.
  - Pass when: the command exists and the next strip mine uses his facing again (README now matches).
- [ ] **Crafting refusal says exactly what's missing (1.1.207)**
  - Bradley does: give Jake 1 white wool + 10 planks, `/bot craft bed`; then 2 sticks and no coal,
    `/bot craft torch 4`.
  - Claude watches for: chat `Can't craft bed: need 2 more white wool`; `Can't craft torch: need 2 more
    sticks, 4 more coal or charcoal` (numbers may differ by what's in nearby chests for the bed).
  - Pass when: the numbers match the shortfall, the bot crafts nothing, and no old generic line
    ("Beds need 3 wool…") appears.
- [ ] **Unknown craft name speaks once per 30 s (1.1.207)**
  - Bradley does: `/bot craft spaceship` three times in ten seconds, then once more after 40 s.
  - Pass when: `I don't know how to craft spaceship.` appears at the 1st and 4th attempt only.
- [ ] **Remembered fishing spot (1.1.207)**
  - Bradley does: `/bot fish 1` at a pond, let him catch one; walk him 40–60 blocks away where no water
    is visible; `/bot fish 1` again.
  - Claude watches for: `Reusing remembered fishing spot: water=… stand=…`; then normal casting.
  - Pass when: he walks back to the pond instead of saying "I need to be standing near open water".
    Fill the pond in (or a 4-block-wide patch of it) and repeat → `Forgot remembered fishing spot …`
    then a fresh scan.
- [ ] **Wider fishing scan (1.1.207)**
  - Bradley does: stand 20 blocks from a pond (was out of the old 12-block reach), `/bot fish 1`.
  - Pass when: he finds it without a remembered spot (fresh world or after `bot_home_data.json` edit);
    the failure path in a desert answers within a second, not a multi-second stall.
- [ ] **Farm reuses its irrigation source (1.1.207)**
  - Bradley does: set up a farm with a bucket refill from a nearby pool; next day move the farm 30
    blocks (still within 48 of the pool) where no water is within 24 blocks.
  - Claude watches for: `Falling back to remembered water source at … for irrigation/refills`.
  - Pass when: he refills from the remembered pool instead of failing.
- [ ] **Per-kind cap keeps fishing memory (1.1.207)**
  - Bradley does: after the two items above, open `config/frens/bot_home_data.json` (or the
    equivalent BotHomeService file) → `waterSpotsByBot`.
  - Pass when: both a `fishing` and an `irrigation` entry are present for Jake with sane coordinates.

---

## Phase 6g — Zero-change refactors + debounced saves (1.1.208)

These are regression checks: every item must behave exactly as it did on 1.1.207. Any difference is a
fail, even an improvement — note it and move on.

- [ ] **Leaf-blocked navigation unchanged (1.1.208)**
  - Bradley does: `/bot come` through a dense leaf canopy (oak/jungle), twice.
  - Claude watches for: the same bypass-then-mine sequence as before; the 1200 ms per-bot cooldown
    still visible as the gap between leaf-mine attempts; no new exceptions mentioning `LeafClearService`.
  - Pass when: he gets through with the same feel and roughly the same number of leaves broken.
- [ ] **Woodcut cleanup line-of-sight leaf clearing unchanged (1.1.208)**
  - Bradley does: fell a leafy tree so the cleanup pass runs.
  - Claude watches for: per-attempt leaf cap unchanged (same count of "cleared leaf" lines per target);
    decaying leaves still skipped.
  - Pass when: cleanup completes; no leaf outside the old 3×3×3 sample box is touched.
- [ ] **Bridge scaffold still clears snow (1.1.208)**
  - Bradley does: woodcut across a snowy gap that needs a bridge.
  - Pass when: snow layers and leaves on the line are cleared exactly as before (raycast hit only).
- [ ] **CollectDirt ladder exit unchanged (1.1.208)**
  - Bradley does: `/bot skill collect_dirt` where he digs a shaft deep enough to need ladders.
  - Pass when: the ladder column goes in on the same support side as before and he climbs out.
- [ ] **Home data survives a normal quit (1.1.208, debounce)**
  - Bradley does: change something BotHomeService owns (set nav mode or sleep in a new bed), then quit
    to title within 2 s.
  - Claude watches for: no `frens-bot-home-writer` exception; file mtime updated at shutdown.
  - Pass when: on reload the change is present (`bot_home_data.json` was flushed on SERVER_STOPPING).
- [ ] **Home data write is batched (1.1.208, debounce)**
  - Bradley does: run a hobby-heavy 2 minutes (several base/sleep/nav mutations).
  - Claude watches for: `bot_home_data.json` mtime changing at most every ~0.5–5 s, not per mutation.
  - Pass when: writes are visibly coalesced and nothing is lost on reload. (Known cost: a hard kill loses
    ≤5 s of these changes — not a fail, just record it if it happens.)

---

## Phase 6h — Fortify Phase 2 extraction (1.1.209)

Regression checks only: fortify must behave exactly as on 1.1.208. Needs a village with at least two
hull vertices that get towers (use the same test village as before if it still exists). Watch the log
for the new categories `skill-fortify-tower` and `skill-fortify-cleanup` — the message text is unchanged.

- [ ] **Tower build unchanged (1.1.209)**
  - Bradley does: `/bot skill fortify` on the village and let the wall stage reach the towers.
  - Claude watches for: `[FortifyTower]` vertex lines in the same order as a 1.1.208 run; scaffold
    column placed on the same side; summit step/return lines; the ledger teardown at the end.
  - Pass when: every tower reaches the completion ratio and no `FortifyTowerHelper` exception appears.
- [ ] **Deferred cleanup still drains (1.1.209)**
  - Bradley does: during the build, watch for a carve corridor (bot mines through terrain) or force one
    by standing so the bot has to route through a hillside.
  - Claude watches for: `[FortifyCleanup]` queue lines under `skill-fortify-cleanup`; the replace-path
    retries (`mandatory` repairs) and the "would seal current exit" skip still fire.
  - Pass when: the corridor is repaired after the bot leaves it; the queue reports empty.
- [ ] **Tower patch unchanged (1.1.209)**
  - Bradley does: remove two blocks from a tower top, then `/bot skill fortify patch`.
  - Pass when: both blocks are replaced via the tower staging position (same approach as before);
    a vertex that makes no progress twice is skipped, as before.
- [ ] **Merge verb still expands (1.1.209)**
  - Bradley does: `/bot skill fortify merge` (the dead `handleMerge` was deleted; the verb routes to
    expand).
  - Pass when: the response is the expand behaviour, not "unknown verb".
- [ ] **Tower replan guard unchanged (1.1.209)**
  - Bradley does: block the tower approach so the bot triggers a medium-range replan.
  - Claude watches for: exactly one replan at a time (`replan already active` never appears twice in a
    row); the movement-epoch cancel still aborts the stale walk.
  - Pass when: the bot reaches the approach or gives the same give-up message as on 1.1.208.
- [ ] **No new log noise (1.1.209)**
  - Claude watches for: any `NullPointerException` mentioning `FortifyTowerHelper`,
    `FortifyCleanupProcessor` or `FortifySkillOps`.
  - Pass when: none in the whole session.

---

## Phase 7 — Conversation ontology (1.1.196, 1.1.197, 1.1.198)

Observed across several scenes; collect as the session runs.

- [ ] **Change-driven cues lead (1.1.196)**
  - Bradley does: force a change — wait out rain stopping, cross a biome, let night fall.
  - Pass when: the second scene after the change leads with it.
- [ ] **Speech acts rotate (1.1.196)**
  - Claude watches for: `act=` on consecutive fired lines.
  - Pass when: consecutive scenes show different `act=` values.
- [ ] **First sightings fire once (1.1.196, 1.1.198)**
  - Pass when: "the first wolves any of you have seen" appears once, never again — and survives a restart (the `seen` registry is now persisted per bot in `mind.json`).
- [ ] **No player-as-animal, no raw fact leakage (1.1.197)**
  - Pass when: no "the first player x2", and events read as `started woodcutting` / `slew a zombie`, never `started a task (skill:sleep, skill)`.
- [ ] **Open threads (1.1.198)**
  - Bradley does: let a bot ask him a question and **ignore it** for 10+ min; separately, answer one.
  - Claude watches for: an `OPEN THREADS` influence — the expired one should produce a one-shot seed anchor ("Bob never got an answer about …").
  - Pass when: expiry raises exasperation and surfaces the anchor; answering clears the thread.
- [ ] **Stance shows in the prompt (1.1.198)**
  - Pass when: stance clauses ("wary of Roti", "fed up with being ignored", "full of questions for Roti") appear as behaviour over the session.
- [ ] **Day memories from consolidation (1.1.198)**
  - Claude watches for: `[souls] mind consolidated bot=… day=… memories=…`; also `[souls] day consolidation failed for bot … day …` must NOT appear.
  - Pass when: up to 3 memories per day are formed and `events.jsonl` is trimmed to 200 records.
- [ ] **Sleep is not journaled (1.1.199)**
  - Pass when: a night's sleep does not write four events.

---

## Phase 8 — Gameplay bugs (noisy; RALPH_TASK Lane 2 / P1)

- [ ] **Woodcut restart loop diagnostic (1.1.200) — the priority item**
  - Bradley does: put an **axeless** bot near trees, idle, and leave it there.
  - Claude watches for: `Idle wooden fallback: starting one-tree woodcut for {}` repeating, and critically the new INFO line `Idle wooden fallback: {} signature changed {} -> {} with {} ticks of cooldown left; resetting` — capture the **A -> B** values verbatim, they name what is invalidating the 240-tick `NEXT_WOODEN_FALLBACK_TICK` cooldown.
  - Pass when: at least one signature-changed line is captured with both signatures, so the fix can be made at the source. Also note whether `I have no axe and can't make or find one` still accompanies it, and answer the product question: **should an axeless bot punch one tree instead of refusing?**
- [ ] **Idle hobbies have real shared state (1.1.200 fix, verify)**
  - Claude watches for: `Shared state unavailable for idle-hobbies` and any `NoClassDefFoundError`/`OllamaBaseException` — must NOT appear.
  - Pass when: neither appears in a full session.
- [ ] **BotTorchHoldService not firing (P1, diagnostic-first)**
  - Bradley does: follow-mode in a dim cave and at night, with a torch in the bot's inventory.
  - Claude watches for (1.1.202 state-change diagnostics): `[torch-hold] <Bot> verdict=reject gate=<gate> dist=<d> light=<l> mob=<id>` and `[torch-hold] <Bot> verdict=hold gate=none ... action=promoted+held slot=<n> savedSlot=<m>`. Gates: `mode-<M>`, `active-task`, `using-item`, `mounted`, `sleeping`, `light-above-7`, `audible-hostile-8`, `visible-hostile-16`, `no-torch-in-inventory`. Each line prints only when the verdict changes.
  - Pass when: the rejecting gate is named (expected suspects: the 8-block audible-hostile suppression, or foreign-swap cycling against the combat loadout / AutoFaceEntity). Do not fix in-session — collect the reason.
- [ ] **Creeper back-away (P1 — 1.1.202 ships a fix, verify it)**
  - Bradley does: let a creeper walk up to a bot (a) unarmed, (b) armed with a sword and shield, (c) mid-skill (mining/woodcut). Do NOT ignite it with flint & steel — proximity swelling is the case that was broken.
  - Claude watches for: `[creeper] <Bot> decision=BACK_AWAY|FLEE_SPRINT|STAY dist=<d> armed=<bool> fuse=SWELLING|IDLE|IGNITED` (prints on decision change) and the `BotCreeperDefenseService` backoff lines (`creeper-defense` logger) — the root-cause fix: the always-on interrupt filtered on `isIgnited()`, which proximity swelling never sets, so it never fired in normal play; it now also triggers on `getFuseSpeed() > 0`.
  - Pass when: the bot backs off in all three cases before the fuse completes, the armed case shows `decision=BACK_AWAY` inside 4.5 blocks, and no explosion damages a bot. Record any case where it still stands still — with the dist/fuse values from the line.
- [ ] **Doorway / pressure-plate stalls (observe only)**
  - Bradley does: route bots through doorways and over pressure plates during normal play.
  - Claude watches for: `applyMovementInput-reject` bursts, NavHazardCache penalty lines, the 5-min top-cells summary; door-plan jump loops (the 1.1.180 symptom).
  - Pass when: any stall is captured with timestamps and cell coordinates. **Observe only** — the door-plan architecture rework is a multi-day job to discuss with Bradley first.

---

## Phase 9 — Day rollover and memory digest (1.1.201) — do this near the end

- [ ] **Tell Jake three things**
  - Bradley does: DM Jake three distinct, memorable, factual things about himself across the session (≥4 player lines total — `MIN_PLAYER_LINES` is 4).
  - Pass when: at least four substantive player lines exist before the rollover.
- [ ] **Sleep through a night**
  - Bradley does: sleep, or let midnight pass with a bot that never sleeps.
  - Claude watches for: `[souls] memory digest bot=… player=… channel=… outcome=… kept=… lines=…`; also `[souls] memory digest failed bot=… player=… :` and `[souls] memory digest sweep failed bot=… :` must NOT appear.
  - Pass when: a digest runs with `kept=` ≥ 1 for Jake.
- [ ] **`mind.json` inspection**
  - Bradley does: after the rollover, open `saves/<world>/souls/v1/<jake>/mind.json`.
  - Pass when: `playerMemories` holds the facts and `digestCursors` has advanced. Cursors advance on **every** outcome except cancellation — an empty or failed digest must still move them.
- [ ] **`/bot soul memory Jake`**
  - Bradley does: run it.
  - Pass when: output is `day N · salience S · fact`, newest day first — or the empty-state string `"Jake doesn't remember anything about you yet."` if nothing was kept.
- [ ] **Recall in banter**
  - Bradley does: keep playing near both bots after the rollover.
  - Claude watches for: a scene whose topic is `memory:said` (player-memory anchors feed `SoulBanterSeed` at weight 4).
  - Pass when: a memory is recalled aloud, and the recall bumps salience (+3, cap 10).
- [ ] **Party-scene attribution (added at final review)**
  - Bradley does: with the **second player** present, run a "bots, …" party scene where both players say something factual.
  - Pass when: each fact ends up in the right player's memories — a fact said by the other player must not appear under Bradley's, and vice versa.
- [ ] **Present truth beats memory (1.1.201 Task 6)**
  - Bradley does: state something that contradicts a stored memory and ask about it.
  - Pass when: the bot goes with present, authoritative state — the `ABOUT <Player>` block sits after it in the prompt and is framed as untrusted conversational content.
- [ ] **`/bot soul digest off` is not amnesia**
  - Bradley does: `/bot soul digest off`, then let another rollover pass.
  - Pass when: no **new** memories form, but existing ones still render and still decay. `/bot soul digest status` reports OFF. Turn it back on afterwards.
- [ ] **Privacy guard on `memory`**
  - Bradley does: as operator, run `/bot soul memory <Bot>` for a bot the second player uses.
  - Pass when: it still shows only **his own** memories — an operator cannot read another player's.

---

## Phase 10 — Destructive (last)

- [ ] **Reset during a running digest (added at final review)**
  - Bradley does: trigger a rollover and run `/bot soul reset Jake` **while the digest is in flight**.
  - Claude watches for: `outcome=superseded` on the digest line; `[souls] memory archive failed for bot … player …` and `memory archive submit failed` must NOT appear.
  - Pass when: memories stay **empty** — the in-flight digest must not resurrect what the reset just archived.
- [ ] **`/bot soul reset Jake`**
  - Bradley does: run it, then `/bot soul memory Jake`, then re-open `mind.json`.
  - Pass when: the memory list is empty, `archivedPlayerMemories` holds them, and that caller's `digestCursors` entries are dropped.
- [ ] **`/bot soul reset party` mid-banter**
  - Bradley does: run it while a banter scene is playing.
  - Pass when: the actor's own party epoch is archived, local + group + banter records archived alongside, no crash, playback ends cleanly.
- [ ] **Toggle everything off**
  - Bradley does: `/bot soul banter off`, `/bot soul local off`.
  - Pass when: both directors go fully dark — nothing matching `[souls] banter` or `[souls] local` for the remainder.

---

## Appendix — Feedback bundle

Claude fills this in live during play; it becomes the input to the post-session autopsy
(RALPH_TASK Lane 1, third item).

| Time | Phase / item | Verdict | Log line (correlation id + outcome, no bodies) | Bradley's remark |
|---|---|---|---|---|
|  |  |  |  |  |
|  |  |  |  |  |
|  |  |  |  |  |

**Session totals:** pass ___ / fail ___ / skipped ___ / blocked ___

**New bugs found (one line each, with timestamp):**

**Open questions answered this session:** (axeless fallback — punch or refuse? · solo-banter vs
organic local balance · 3B vs 8B · Pocket vs Piper quality)

**Not covered / deferred:**
