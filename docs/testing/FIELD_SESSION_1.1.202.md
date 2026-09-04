# Field Session — Frens 1.1.202

**Version under test:** `frens-1.1.202-release+1.21.11.jar` (1.1.201 memory digest + 1.1.202 torch/creeper diagnostics and the creeper fuse fix). Session protocol: `GUIDED_SESSION_PROTOCOL.md` beside this file.
**Date:** ____________  **Instance:** PrismLauncher `1.21.11`
**Server log Claude tails:** `~/Library/Application Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log`

Nothing has been field-tested since 1.1.184. This is the merged, deduplicated checklist for **1.1.175 → 1.1.202** plus the Lane 1 / Lane 2 items from `RALPH_TASK.md` (Backlog Lineup 2026-09-03). One continuous session, run in order — souls are enabled once, calm tests precede noisy ones, day-boundary tests sit near the end, destructive resets last.

## How the session runs

- **Claude** tails `latest.log` live, greps the prefixes named per item, and calls pass/fail.
- **Bradley** executes the "Bradley does" line and reports what he saw/heard.
- Every item has three sub-lines: **Bradley does** / **Claude watches for** / **Pass when**.
- Record-keeping rule inherited from `SOUL_COMMUNICATION_PILOT.md`: log **correlation ids and outcomes, never message bodies**.
- Related runbooks — link, don't duplicate: [`SOUL_COMMUNICATION_PILOT.md`](SOUL_COMMUNICATION_PILOT.md) (its "Manual — to be executed in-game" section is still the authority for DM edge cases: disconnect/death/dimension, restart persistence, remote perception leakage, reserved deterministic routing) and [`IN_GAME_AUDIT_MASTER.md`](IN_GAME_AUDIT_MASTER.md) (Fast Daily Smoke + Full Reliability Sweep for the non-soul stack).
- Useful greps: `[souls]`, `outcome=`, `vetoed:`, `verdict`, `memory digest`, `mind consolidated`, `Idle wooden fallback`, `torch-hold`, `scene-playback`, `tts`.
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
