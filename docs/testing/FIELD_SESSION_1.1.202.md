# Field Session — Frens 1.1.202

**Version under test:** `frens-1.1.292-release+1.21.11.jar` (1.1.292: no new items) (1.1.201 memory digest + 1.1.202 torch/creeper diagnostics and the creeper fuse fix + 1.1.203 config sync / per-player mute masks — Phase 6b + 1.1.204 backlog run — Phase 6c + 1.1.205 loose ends — Phase 6d + 1.1.206 follow-ups — Phase 6e + 1.1.207 crafting/water — Phase 6f + 1.1.208 refactors — Phase 6g + 1.1.209 fortify extraction — Phase 6h + 1.1.210 carve extraction — Phase 6i + 1.1.211 novelty rejection — Phase 6j + 1.1.212 peer stance — Phase 6k + 1.1.213 typed relations — Phase 6l + 1.1.214 structured output — Phase 6m + 1.1.215 bullet-sentinel fix, no new items + 1.1.216 speech floor and idle-hobby backoff — Phase 6n + 1.1.217 pre-warm, torch hysteresis, truthful scripted logs, scene floor reservation, group-chat hint — Phase 6o + 1.1.218 construction interior egress — Phase 6p + 1.1.219 chat addressee rules, DM follow-up window and supplies groundwork — Phase 6q + 1.1.220 Bot Storage owner-only — Phase 6r + 1.1.221 companions ask before taking from chests (supplies Phase 3) — Phase 6s + 1.1.222 no tick freezes from table searches or make-room — Phase 6t + 1.1.223 gate follow, redstone doors, craft names, scene smoothing, crafting search — Phase 6u + 1.1.224 access control, DM privacy, no native downloads — Phase 6v + 1.1.225 AI companion setup, multiplayer AI settings, model-free first run — Phase 6w + 1.1.226 singleplayer auto-spawn waits for the host — Phase 6x + 1.1.227 commands-off first run — Phase 6y + 1.1.228 narrow-screen layouts — Phase 6z + 1.1.229 fresh-world woodcut, follow after tasks, tool wear, drop pickups — Phase 6aa + 1.1.230 woodcut protection nudge — Phase 6ab + 1.1.231 non-op owners use the UI — Phase 6ac + 1.1.232 host sweep — Phase 6ad + 1.1.233 cleanup — Phase 6ae + 1.1.234 playthrough fixes — Phase 6af + 1.1.235 sandbox fixes and Questing flag — Phase 6ag + 1.1.236 shelter-hole climb-out and per-world roster — Phase 6ah + 1.1.237 capped-pit surface fix, no new items + 1.1.238 Spawn-base trees protected — Phase 6ai + 1.1.239 bare-hand stone refused, unreachable trees skipped, stuck backoff, hunger in chat — Phase 6aj + 1.1.240 multi-step navigation — Phase 6ak + 1.1.241 tree claims, creeper line, walk outcomes — Phase 6al + 1.1.242 unified finder opt-in, audit bug fixes — Phase 6am + 1.1.243 dead-code removal 1, no new items + 1.1.244 dead-code removal 2 — Phase 6an + 1.1.245 dead-code removal 3, no new items + 1.1.246 dead-code removal 4 — Phase 6ao + 1.1.247 dead-code removal 5, no new items + 1.1.248 combat callouts, base owner, leash target — Phase 6ap + 1.1.249 wall-off night shelter — Phase 6aq + 1.1.250 legacy LLM chat retired, part 1 — Phase 6ar + 1.1.251 legacy LLM stack deleted — Phase 6as + 1.1.252 leash release, base commands — Phase 6at + 1.1.253 shelters — Phase 6au + 1.1.254 Bot Controls per world — Phase 6av + 1.1.255 death retrieval — Phase 6aw + 1.1.256 global spawns retired, no new items + 1.1.257 dig-down dawn climb-out — Phase 6ax + 1.1.258 retrieval drops only, trips survive reload — Phase 6ay + 1.1.259 ranged pause, woodcut stands — Phase 6az + 1.1.260 player text sweep, no new items + 1.1.261 unified pathfinder default — Phase 6ba + 1.1.263 sunset return and reach probes use the bot's finder — Phase 6bc + 1.1.264 stuck escape reads collision shapes — Phase 6bd + 1.1.265 go_to uses the bot's finder — Phase 6be + 1.1.266 sandbox bugs A-G — Phase 6bf + 1.1.267 unified search routes around its own breaks, faster empty cleanup — Phase 6bg + 1.1.268 Spawn-base own scaffold, forest woodcut, honey and fish — Phase 6bh + 1.1.269 honey on the server thread, own-tree log count, rescue chest food — Phase 6bi + 1.1.270 rescue sees refusals, locked chests and an absent owner, honey reach and deposit, no new items + 1.1.271 operator-permission lookup reaches leveled-mapped, Phase 6v reworded, no new items + 1.1.272 fix build from the 0-6ai sweep (settings past 32 KB, lodestone, travel-wait, wool, guard hole, Spells without Questing, woodcut chest axe, souls player scenes), sandbox-checked, Phase 6bj + 1.1.273 second fix build from the 0-6ai sweep (ambient chatter floor and rate, any-actor danger veto, threat resume, mining re-poll, idle stone upgrade, bed craft, pickup count) — Phase 6bk + 1.1.274 third fix build from the 0-6ai sweep (woodcut keeps its axe, craft after a failed reach, fishing search, idle fallbacks, farm no hoe, minds preload, damage in words, chest registry per save) — Phase 6bl + 1.1.275 fix build from the 1.1.273/274 sandbox checks (threat line, tree soil, woodcut outcome, fishing search, chest adoption, desperate eating) — Phase 6bm + 1.1.276 worker scans read each chunk once, miner threats, copied chest records retired — Phase 6bn + 1.1.277 canopy step down, flooded-pocket ledge, soul stage directions, woodcut per-log reads — Phase 6bo + 1.1.278 tick-driven defensive aid, golden-apple yes/no, go_to dimension, doorway line-up, Collect messages — Phase 6bp + 1.1.279 small steps don't clip into walls, a full pack never costs a valuable stack, fresh stuck counters per return, no cooking sleep on the server thread, voice installer timeout, bundled food — Phase 6bq + 1.1.280 follow under oak canopies, flooded-pocket ledge climb, cleanup leaves other trees' soil and pillars alone, bow defenders and friendly fire, admin remote open — Phase 6br + 1.1.281 idle stone upgrade crafts only stone, keeps its cobblestone, stays quiet — Phase 6bs + 1.1.282 cleanup never swings a weapon at leaves, no wrong-material line after a full pack, chest placement misses logged — Phase 6bt + 1.1.283 fortify cavity callouts voiced, flower gifts skip a full pack — Phase 6bu + 1.1.284 sunset return finds a distant owner, voice mute mask cap — Phase 6bv + 1.1.285 starvation pauses reach a distant owner, Allow once while the owner is away, Collect re-checks permission, no chest-record revival by a stranger's chest — Phase 6bw + 1.1.286 follow mines leaves at a step up, bow defender sidesteps for a clear shot, double chests counted once, implicit pathfinder default, mounted leaf clearing at normal speed — Phase 6bx + 1.1.287 owner-only zone edits, village mapping and wall claims, double chest with an unloaded half refused, a stopped tool search stays stopped, woodcut cleanup tracks its own pillars — Phase 6by + 1.1.288 snow and carpet mined, automatic refusals said once, canopy counts as surface — Phase 6bz + 1.1.289 buried-bot rescue and tool swaps on the server thread, resume waits for surface recovery — Phase 6ca + 1.1.290 cleanup scan via chunk reader, broken bee nests, mutual aid waits for commanded jobs — Phase 6cb). Session protocol: `GUIDED_SESSION_PROTOCOL.md` beside this file.
**Date:** ____________  **Instance:** PrismLauncher `1.21.11`
**Server log Claude tails:** `~/Library/Application Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log`

Nothing has been field-tested since 1.1.184. This is the merged, deduplicated checklist for **1.1.175 → 1.1.238** plus the Lane 1 / Lane 2 items from `RALPH_TASK.md` (Backlog Lineup 2026-09-03). One continuous session, run in order — souls are enabled once, calm tests precede noisy ones, day-boundary tests sit near the end, destructive resets last.

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

- [x] **World up on 1.1.202, souls master on**
  - Sandbox 2026-10-18: PASS: S1 `[16:49:57] [souls] runtime started; masterEnabled=true settingsValid=true` (the old `-PaiEnabled` line is gone since 1.1.250, by design). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: launch the `1.21.11` instance, load the test save, confirm the JAR version in the mod list.
  - Claude watches for: `[souls] runtime started; masterEnabled=true settingsValid=true`; the benign `[Frens] LLM runtime not available (build without -PaiEnabled?)` line is expected (that is the CLASSIC DJL path, souls run on Ollama).
  - Pass when: runtime started with `masterEnabled=true settingsValid=true`.
- [x] **Ollama reachable**
  - Sandbox 2026-10-18: PASS: S1+S2 whole sessions: no `ollama not reachable` and no `non-local Ollama host` line (host 127.0.0.1, via the loopback tap). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: nothing; check chat for errors.
  - Claude watches for: absence of `[souls] ollama not reachable at {}` and of `[souls] Soul conversations will be sent to non-local Ollama host`.
  - Pass when: neither line appears.
- [x] **Bind both bots**
  - Sandbox 2026-10-18: PASS: S1 `/bot soul enable Jake` then `Bob`: chat "Jake is now speaking as Jake." and "Bob is now speaking as Bob." (16:50:29-31); `/bot soul status` shows `Bob ... profile=frens:bob`. The pre-1.1.184 `frens:jake` binding ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul enable Jake` then `/bot soul enable Bob` (Bob **must** be re-enabled once — his pre-1.1.184 binding was `frens:jake`).
  - Claude watches for: `"Bob is now speaking as Bob."`; `[souls] rebound {} from frens:jake to its own profile {}` if the 100-tick legacy migration fires instead.
  - Pass when: Bob reports his own profile, not Jake's.
- [x] **Toggles on**
  - Sandbox 2026-10-18: PASS: S1 16:50:33-39: "Companion banter set to on", "Companion local chat set to on", "Memory digest is ON. Runs at each Minecraft day rollover for soul-bound bots.", "Soul voice enabled.". See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul banter on`, `/bot soul local on`, `/bot soul digest status` (expect ON by default; `/bot soul digest on` if not), `/bot soul voice on`.
  - Claude watches for: `"Memory digest is ON. Runs at each Minecraft day rollover for soul-bound bots."`
  - Pass when: all four report ON.
- [x] **Voice engine chosen**
  - Sandbox 2026-10-18: PASS: S1 Piper (Nirn's installed Piper folder copied into the sandbox config, read-only): `[16:50:40] [souls] tts engine started pid=17837 model=en_US-lessac-medium.onnx`, no `tts engine unavailable`. Engine set in config, ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: Bot Control → Soul Voice → Eng… → pick the engine for this session (Piper / Dreamsleeve / Pocket). Note which one in the bundle.
  - Claude watches for: `[souls] tts engine started pid=…` — one of `model={}`, `(dreamsleeve warm server)`, `(pocket-tts serve :{})`.
  - Pass when: an engine pid line appears and no `[souls] tts engine unavailable, voice disabled:`.
- [x] **Baseline status snapshot**
  - Sandbox 2026-10-18: PASS: S1 16:50:54-58: soul status (`system=on settingsValid=true ready=true provider=ollama model=llama3.1:8b providerHealthy=true queueDepth=0`), banter status (`Idle: last verdict: vetoed:cooldown, cooldown: 123s ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul status`, `/bot soul banter status`, `/bot soul local status`.
  - Claude watches for: the live veto reason and cooldown printed by banter status.
  - Pass when: all three print without error; snapshot pasted into the bundle as the baseline.

---

## Phase 1 — Calm solo (quiet, one bot in the roster)

Run this with **Bob dismissed or out of earshot** so the roster is genuinely 1.

- [x] **Solo remark delivers (1.1.181, 1.1.184)**
  - Sandbox 2026-10-18: PASS: S2 Bob despawned, `/bot soul banter now`: `[16:58:18] banter routingId=b605e996 ... rosterCount=1`, `scene ... rosterSize=1 ... lines=1`, Jake to the player "We should take a look at the nearby cave system...", ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: stand quiet near Jake for 90 s after `/bot soul banter now`; if nothing, wait out the 4–8 min grace.
  - Claude watches for: `[souls] scene correlationId=… rosterSize=1 … outcome=` and a delivery line; NOT `outcome=failed:MALFORMED`.
  - Pass when: a remark addressed to Bradley is delivered within 4–8 min of world start (8–15 min thereafter), `rosterSize=1`, delivered.
- [x] **Exactly one continuation (1.1.181)**
  - Sandbox 2026-10-18: PASS: S2 player answered unaddressed 22 s later: `[16:59:01] [souls] local ... outcome=fired routingId=8bfdcc55 score=3`, one LOCAL line from Jake, then nothing for the next 40 s. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: answer the remark within 30 s.
  - Claude watches for: one further scene, then nothing; no chain.
  - Pass when: exactly one continuation, no third line.
- [x] **Ignored window lapses silently**
  - Sandbox 2026-10-18: PASS: S2 remark a0e5e62a at 17:00:35 left unanswered: next souls line is `vetoed:cooldown`, no error or retry in the following 2 min. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: let the next remark go unanswered.
  - Claude watches for: no error, no retry storm.
  - Pass when: the window closes with nothing logged as a failure.
- [ ] **Streaming first-word latency (1.1.175, still open)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): FAIL: S5 by log: `llama3.1:8b` providerMs 8019/5476/4255 for three DMs (19:31:56-19:32:39), Piper 74-148 ms a segment; `llama3.2:3b` 2090/1896 ms after a 4829 ms load. Still over ~3 s on 8B, inside it on 3B. Replay A. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-18: FAIL: S2 by log (not by ear): DM sent 17:01:17, Ollama answer at 17:01:23 (`promptEvalMs=4386`), first segment synthesised `synthMs=545` at 17:01:24, about 7 s. Second DM 17:01:33 to first segment 17:01:38, about 5 s. ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: send Jake a DM and start a stopwatch at Enter.
  - Claude watches for: `[souls] tts correlationId=… outcome=… synthMs=… bytes=… chunks=…` and the scene's `elapsedMs=`.
  - Pass when: first audible word inside ~3 s; record the number either way.
- [x] **llama3.2:3b speed vs llama3.1:8b (1.1.175, still open)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 three DMs each: 8B median 5.5 s, 3B median 2.1 s (`Soul model set to 'llama3.2:3b'` 19:33:11, back 19:37:06). Two 3B banter scenes 4/4 lines, no MALFORMED (roster of two; roster of one not run). By speed 3B; the quality call ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: three DMs on the current model, then `/bot soul model <other>` and three more.
  - Claude watches for: `[souls] soul model set to {}` then per-turn `elapsedMs=` for both sets.
  - Pass when: both medians recorded and a preference called. Watch for 3B's known weakness — roster-of-one scenes going `MALFORMED` (1.1.184).
- [x] **Failed generation retries (1.1.184)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 `gpt-oss:20b` returns empty content (its reasoning uses the whole reply): `failed:MALFORMED` 20:29:51, then a fresh `banter ... outcome=fired` 20:31:54 with no input (2 min); again 20:32:06. Replay A. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: nothing; opportunistic.
  - Claude watches for: any `outcome=failed:` followed by a fresh attempt within ~2 min.
  - Pass when: a failure is followed by a retry, not silence. (Skip-and-note if none occurs.)

---

## Phase 2 — Two-bot banter and pacing (1.1.176, 1.1.177, 1.1.181, 1.1.188)

- [x] **Banter fires with 2 bots**
  - Sandbox 2026-10-18: PASS: S1 `[16:51:13] [souls] banter routingId=24e11327... outcome=fired rosterCount=2`, playback 4/4, Jake and Bob each spoke twice. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: bring Bob back, stand near both, keep chat quiet, wait out the 4–8 min grace; `/bot soul banter status` while waiting.
  - Claude watches for: `[souls] banter lane=… player=… outcome=fired routingId=… roster=… seedChars=… act=… topic="…"` and the veto reason changing as the wait proceeds.
  - Pass when: a two-bot scene fires and both bots speak.
- [x] **Distinct display names keep the scene coherent (1.1.176)**
  - Sandbox 2026-10-18: PASS: S1 routingId 24e11327: each `scene-playback ... speaker=` UUID matches the chat name (Jake 2af3f0a5, Bob 6a00845c), no dropped unknown speaker. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Claude watches for: speaker attribution in `scene-playback … speaker=`.
  - Pass when: no line is attributed to the wrong bot; no unknown speaker dropped.
- [ ] **No same-speaker-twice runs (1.1.197)**
  - Sandbox 2026-10-18: FAIL: S2 banter 7fe07d6f (17:40:48-54): `line=1/2 speaker=2af3f0a5` then `line=2/2 speaker=2af3f0a5`, Jake twice in a row, not merged. Other scenes alternated. Finding 3. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Claude watches for: consecutive `scene-playback … line=n/N speaker=` with the same speaker.
  - Pass when: multi-speaker scenes alternate; runs are merged into one turn.
- [x] **Nobody answers for the player (1.1.195)**
  - Sandbox 2026-10-18: PASS: S2 the model wrote lines as "RotiWokeman:" in 4 scenes (tap); each was cut before playback: `dropped rawLine=4 reason=OWNER_TAGGED` (17:18:16), two more at 17:21:18, one at 17:21:48. No delivered line speaks for the ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Pass when: no bot line puts words in Bradley's mouth or credits him with bot events.
- [x] **~1/3 of banter ends addressed to the player (1.1.181)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 tap: 11 of 37 scene prompts carried "One of you may end by saying one short thing to RotiWokeman" (30 %). `addressPlayer=` is not logged at INFO, so counted from the prompts. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Claude watches for: `addressPlayer=true` on the fired line.
  - Pass when: across the session roughly 2/3 plain, 1/3 player-addressed.
- [x] **`/bot soul banter now` primes both lanes (1.1.182, 1.1.188)**
  - Sandbox 2026-10-18: PASS: S1 `/bot soul banter now` at 16:51:11, `[16:51:13] banter lane=IDLE ... outcome=fired`; banter status lists both the Idle and Active lanes. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul banter now`, stand quiet 90 s.
  - Pass when: a scene inside ~10 s of eligibility; status shows both IDLE and ACTIVE lanes.
- [x] **Active lane fires on work (1.1.188)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S6 Active on in config, `/bot skill woodcut Jake`: `banter lane=ACTIVE outcome=fired` 21:06:55 (about 2 min in), scene `kind=WORK` 4 lines, Bob "Oh, nice piece of wood!"; again 21:12:35. Replay B 21:06:55. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: set a bot woodcutting, stay nearby.
  - Claude watches for: `lane=ACTIVE … outcome=fired` within ~2.5 min.
  - Pass when: it fires and the scene mentions the work.
- [ ] **Pacing sliders (1.1.188)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S6 Rate... after a restart showed Scripted 100 and Idle 70 (set in config, persisted). At 100: 31 voiced scripted lines in 5.9 min vs 2.9/min at 50 in S5. S7 Scripted 0 through the slider (saved, `dialogueScriptedRate: 0`): Jake ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: Banter row → **Rate…** → set Scripted to 100, play a few minutes; then 0; leave Idle at 50. Restart the client once mid-session to check persistence.
  - Pass when: 100 → denser pet/scripted remarks, 0 → near-silent, values survive restart.
- [x] **Combat aborts a scene (1.1.177)**
  - Sandbox 2026-10-18: PASS: S2 zombies summoned as banter 31cbc3eb fired: `[17:43:41] scene-playback routingId=31cbc3eb... outcome=ambient-combat-abort`, `outcome=cancelled delivered=0/1`. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: pick a fight mid-scene.
  - Claude watches for: `scene-playback routingId=… outcome=ambient-combat-abort`.
  - Pass when: the scene stops.
- [x] **Walking away / bystander earshot (1.1.176)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 scene 9d50eb8a 20:44:25, Bob teleported 150 blocks after line 1: `listeners=3` then 2, then 1 on Bob's own line 3 (not shown to the player), then 2. No error. No `skipped-speaker-gone` because Bob stayed online, and the far ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: walk out of earshot mid-scene; on another scene, stand at the edge of earshot.
  - Claude watches for: `scene-playback … outcome=skipped-speaker-gone`, `listeners=`.
  - Pass when: playback degrades cleanly, listener counts match who was actually near.
- [ ] **Cooldown spacing over the session**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S5 natural fires were banter only (20:52:09, 20:57:49, 21:03:19, about 5.5 min apart at Idle 70); local never fired unprompted (lines `vetoed:salience`), so banter/local alternation could not be judged. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
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

- [x] **Toggle off is fully dark**
  - Sandbox 2026-10-18: PASS: S2 `/bot soul local off` (17:25:41), three unaddressed lines over 35 s: zero `[souls] local` lines. DM replies kept working all session (e.g. 17:30:54). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul local off`, chat unaddressed lines for a minute.
  - Claude watches for: nothing matching `[souls] local`.
  - Pass when: zero local lines, and DM replies unchanged.
- [x] **Salience moves with the line**
  - Sandbox 2026-10-18: PASS: S2 `/bot soul local now` before each line: "the sky is grey" `vetoed:hard-reject` score 0, "I found diamonds and a skeleton spawner..." `vetoed:salience` score 1, "does anyone know where we can find iron around ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul local on`; alternate deliberately boring and deliberately salient unaddressed lines, running `/bot soul local status` between them.
  - Claude watches for: `[souls] local player=… outcome=` with the score, and the veto reason moving (`vetoed:salience` vs firing).
  - Pass when: the score and veto track the content.
- [ ] **A fired reaction end to end, audible to a bystander**
  - Bradley does: needs a **second player** standing nearby.
  - Claude watches for: `[souls] local player=… bot=… outcome=fired routingId=… score=…`.
  - Pass when: the bystander hears it, not just the speaker.
- [x] **One continuation, then nothing**
  - Sandbox 2026-10-18: PASS: S2 the 16:59 reply drew exactly one LOCAL line (8bfdcc55); the 17:00:35 remark left unanswered lapsed with no line, error or retry. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: answer inside 30 s; on the next one, let it lapse.
  - Pass when: exactly one continuation; lapse is silent.
- [x] **Addressing a bot mid-window closes it**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 20:38:53 Jake's solo remark to the player, 20:38:55 "Jake, hold that thought." (routed as a DM), 20:39:01 unaddressed "the snow is pretty": `dm follow-up routed`, no `[souls] local ... fired` continuation. Replay A 20:38:53. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Pass when: the window closes on explicit address.
- [ ] **Danger veto**
  - Sandbox 2026-10-18 pass 3 (1.1.271): FAIL: S5 easy difficulty, zombies summoned 2 and 8 blocks from Bob, `/bot soul local now`, a salient line: `local outcome=fired score=5` at 20:41:52 and 20:42:46, never `vetoed:danger`. The zombie is not in that scene's prompt, though ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: pick a fight during a local window.
  - Claude watches for: `vetoed:danger`.
  - Pass when: the veto appears and nothing fires.
- [x] **Server-thread cost under load (1.1.178, never measured)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 five soul bots in earshot (Jake, Bob, Ash, Birch, Cedar), 16 unaddressed lines in about 30 s (21:00): no `Can't keep up`, no `local capture failed`; the earshot scan ran on every line (it comes before the cooldown gate). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: stand several soul bots near an active chatter and chat continuously.
  - Claude watches for: tick-lag warnings, `[souls] local capture failed for bot`.
  - Pass when: no measurable tick lag attributable to the earshot scan.
- [ ] **Roster-lost backoff (1.1.179)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): NOT REACHED: `vetoed:roster-lost` only fires when the snapshot capture throws; no way to force one from the game. The code pushes the cooldown 120 s on it (SoulLocalDirector, the roster-lost branch). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Claude watches for: repeated `vetoed:roster-lost` — must be spaced, not per-line.
  - Pass when: the cooldown is pushed on each roster-lost.

---

## Phase 4 — Group chat / PARTY (1.1.176)

- [ ] **"bots, …" two-bot scene**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S5 on 1.1.271: "everyone, should we build a shelter..." 20:33:41 both bots spoke, one line each. "bots, what should we do next?" twice: 20:33:09 `failed:MALFORMED` (the 8B reply was only a `##FRENS` line, finding 16) and 20:34:29 ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-18: FAIL: S2 three "bots,/everyone" lines with both bots eligible (`scene-routing ... candidates=2 eligible=2`, 17:04:58, 17:15:05, 17:44:45): each PARTY scene had `lines=1`, one bot spoke. The tap shows the model ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `bots, what should we do next?` with both near.
  - Claude watches for: `[souls] scene-routing routingId=… player=… outcome=… candidates=… eligible=… routingMs=…` then a PARTY scene.
  - Pass when: both bots contribute, ≤2 lines each.
- [x] **Mixed soul / non-soul broadcast** — closed 2026-10-18 by triage: OBSOLETE, the legacy LLM chat path the pass line needs was deleted in 1.1.250 (04032600) and 1.1.251 (080160e4); a non-soul bot now gets the AI-chat-off notice
  - Bradley does: same with a non-soul bot present.
  - Pass when: the non-soul bot uses the legacy path, no crosstalk.
- [ ] **Voice-off beat pacing**
  - Bradley does: `/bot soul voice off`, run a group scene, then voice back on.
  - Pass when: lines still pace sensibly with no synth.
- [x] **A group scene resets the banter timer**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 banter status 68 s left (20:32:57), then after the PARTY scene 328 s (20:33:36), 333 s after the next one. Pushed out each time. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-18: PARTIAL: S2 banter status before the PARTY scene: next-eligible about 17:56:22; after it (17:45:12): "592s remaining", about 17:55:04. The timer was re-armed (it jumped) but landed earlier, not later. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Pass when: banter's next-eligible pushes out after the scene.
- [x] **`soulPartyEnabled=false` → legacy loop** — closed 2026-10-18 by triage: SUPERSEDED, the legacy loop was deleted in 1.1.250 (04032600); covered by 6q "Guys" and "everyone" reach both bots (1.1.219)
  - Bradley does: toggle it off in settings, one "bots, …" line, toggle back on.
  - Pass when: legacy behaviour, no PARTY scene.

---

## Phase 5 — Voices (1.1.190, 1.1.192, 1.1.198)

- [x] **Catalogue and bindings**
  - Sandbox 2026-10-18: PASS: S1 `/bot soul voice list`: Piper voices folder lists en_US-lessac-medium and en_US-ryan-medium, catalogue marks ryan `[installed]`, and the bot-keyed view lists `Bob [frens:bob] → global voice`, `Jake [frens:jake] → ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul voice list`.
  - Pass when: lessac shows installed, each **spawned bot** is listed with its bound profile and effective voice (the 1.1.192 bot-keyed view), Jake and Bob both on the global voice.
- [ ] **Install a voice**
  - Bradley does: `/bot soul voice install en_US-ryan-medium`.
  - Claude watches for: 25/50/75/100 % progress lines; `[souls] piper installed: binary=… voice=…`.
  - Pass when: it completes and is size/sha256 verified.
- [x] **Per-bot assignment actually separates the voices (the 1.1.190 → 1.1.192 bug)**
  - Sandbox 2026-10-18: PASS: S2 `/bot soul voice assign bob en_US-ryan-medium`: "Bob → voice en_US-ryan-medium", Bob's next DM started a second engine `[17:30:54] tts engine started pid=27979 model=en_US-ryan-medium.onnx`, no `not found ... ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul voice assign bob en_US-ryan-medium`, then get Bob to speak.
  - Claude watches for: no `[souls] tts voice '{}' for {} not found under {} — using the default voice`.
  - Pass when: Bob's next line is a different voice **and Jake's is unchanged**.
- [x] **Revert**
  - Sandbox 2026-10-18: PASS: S2 `/bot soul voice assign bob default`: "Bob now uses the global voice.", Bob's next line spoke at once (17:31:33) with no engine start or reload. By log, not by ear. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
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
- [x] **Unknown engine id fails loudly (1.1.198)**
  - Sandbox 2026-10-18: PASS: S1+S2: every `tts engine started` line is the configured Piper (`model=en_US-lessac-medium.onnx`, ryan after the assign), no fallback line. An unknown engine id was not set on purpose. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Pass when: no silent fallback to Piper is observed anywhere in the session.

---

## Phase 6 — Muting matrix and live settings (1.1.175, 1.1.182, 1.1.184, 1.1.193)

- [ ] **Category-muting round-trip (1.1.175)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S7 Ambient Chatter unticked in both Adv menus (config `mutedTextCategories`/`mutedVoiceCategories: ["ambient_chatter"]`): no ambient line reached chat, no GENERAL fallback. The voice half is not visible in the log: `Played sound` ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: mute `ambient_chatter` in **both** Adv menus (text and voice).
  - Claude watches for: no chat fallback leaking the line as GENERAL (the "Nice bird!" bug).
  - Pass when: scripted one-liners are silent in chat **and** voice.
- [x] **The separation proof (1.1.182)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S7 ambient muted in both menus, `/bot soul banter now`: scene a37da787 21:33:43 played 4/4 `text=true voiced=true`. Replay D 21:33:43. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with ambient_chatter muted in both Adv menus, wait for banter.
  - Pass when: scripted one-liners silent, **banter still speaks**.
- [ ] **Ambient mute matrix on banter/local (1.1.177, 1.1.178)**
  - Bradley does: text muted → expect voice-only; voice muted → expect text-only and no synth in the log; both muted → expect `vetoed:muted` and **zero generations**.
  - Claude watches for: `scene-playback routingId=… line=n/N outcome=skipped-muted`, absence of `[souls] tts correlationId=` when voice is muted.
  - Pass when: all three quadrants behave as stated.
- [ ] **`vetoed:muted` only in the right condition (1.1.182)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): CRITERION OUT OF DATE: Since the 1.1.193 lane split the banter/local surface is open whenever Soul Chat or Soul Voice is on (SoulRuntime ambientTextOpen/ambientVoiceOpen); the scripted Text and Voice masters no longer reach it. With Soul Chat off the ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Pass when: it appears only with Text master off AND (Voice or Soul Voice) off.
- [x] **Two lanes, two masters (1.1.193)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S7 Scripted Text and Voice OFF in Bot Controls (21:38), Soul Voice on: Jake's DM reply spoken (`tts ... outcome=spoken-all-1seg` 21:39:15), scene lines `voiced=true`, no `Played sound` scripted line in 4 min. Replay D 21:38-21:41. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: Soul Voice on + Scripted Voice off.
  - Pass when: soul lines are still spoken while scripted ones are not.
- [x] **Live-settings apply (1.1.184)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S7 `/bot soul disable Bob` 21:41:16: "Bob's soul communication is now disabled", the next "Bob, can you hear me?" got no turn; `/bot soul enable Bob` 21:41:27 "Bob is now speaking as Bob.", the next DM answered 21:41:37. Bot ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: save any per-bot toggle mid-session.
  - Pass when: it acts immediately, no restart, and every router notice uses the bot's real name.
- [ ] **Global toggles UI (1.1.189, 1.1.191)**
  - Bradley does: expand the global toggle list; drag the scroll thumb; click the track; click the 9 px gutter.
  - Pass when: at least 5 rows visible, footer not buried, gutter clicks do **not** toggle the row underneath, dividers stop at the gutter.
- [x] **At most one bot comments on the same thing (1.1.184)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 parrot beside both bots from 19:31:47: one `parrot_nearby_nice_bird` (Jake), none from Bob in the next 30 min. Only one parrot event, so a thin sample. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: stand two bots near a parrot.
  - Pass when: at most one of them comments.

---

## Phase 6b — Config sync and per-player mute masks (1.1.203)

Single-player items first (same world, no extra client). The LAN pair needs a **second Frens
client** — open the world to LAN from the 1.21.11 instance and join it from a second 1.21.11
launch (Prism: right-click the instance → *Launch* again, or a copy of the instance); the
`1.21.10 TEST` instance cannot join a 1.21.11 world. Skip the pair if only one client is
available and record it under "Not covered".

- [x] **Cheats-off single-player still saves (1.1.203, review fix)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S10 commands-off world, Bot Controls global Force-Place OFF to ON, closed every screen, reopened: still ON, `settings.json5` `fortifyForcePlaceEnabled: true`, no `Rejected config save` in the log. Set back OFF the same way. The ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: in a world created with cheats **off**, flip any global toggle (e.g. Gameplay
    Tips) in Bot Control, close the screen, reopen it.
  - Claude watches for: **no** `Rejected config save from non-operator` in `latest.log`.
  - Pass when: the toggle stays flipped after reopen and after `/reload`-free relog.
- [x] **Mute screen sends a mask, not a global save (1.1.203)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PASS: S12 Bot Controls > global toggles > Scripted Voice Adv... > Reactions off, Done (08:50:24). With `bot-dialogue` DEBUG on, every reactions line from 08:50:25 logs `[VoicedDialogue] Muted (category reac ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: mute `reactions` in the Voice "Adv…" menu.
  - Claude watches for: the next reactions line logging `[VoicedDialogue] Muted (category reactions)`
    (baseline path — single-player shares the list) and no `sendSaveConfigPacket` debug noise.
  - Pass when: the line is silent in voice; text fallback unaffected.
- [x] **RAM warning on the Download button (1.1.203)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S10 AI setup > Choose model...: "System: 32.0 GB RAM", buttons "Selected ✔" (8B), "Use" (3B), "Download" (1B), no ⚠ and no red RAM note. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: LLM chip → model manager.
  - Pass when: on this 32 GB machine every button reads `Download`/`Use`/`In use ✔` with **no** ⚠
    and no red RAM note (nothing recommends more than 12 GB). If any ⚠ shows, that's a fail —
    record the model tag.
- [ ] **Dreamsleeve row on macOS (1.1.203)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): NEEDS BRADLEY: S10 the Soul Voice Engine screen shows "Dreamsleeve: cloned bot voice ... Not set up on this machine" with "Install Dreamsleeve..." greyed, because the sandbox has no Dreamsleeve folder; whether the row reads "Available." needs ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
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
  - Sandbox 2026-10-18 pass 3 (1.1.271): FAIL: S8 the Spells keybind ships unbound (`GLFW_KEY_UNKNOWN`); bound to K in the sandbox's options, looking at Jake, pressed: nothing opened, no log. `handleSpellsContextKey` returns unless survival recruitment is on and completed, so ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: look at Jake, press the Spells keybind; then press it from the radial (slot 3).
  - Claude watches for: nothing in the log on success; `Out of range or wrong dimension` in chat = fail
    (should not happen — the host is op).
  - Pass when: the **bot inventory** screen opens already on the Spells tab both times, Enchant/Anvil
    buttons behave as before, and the old standalone spells popup never appears.
- [x] **Crafting with planks only inside a bundle (1.1.204)**
  - Sandbox 2026-10-18: PASS: S3 Jake holding only a bundle of 12 oak planks and 2 sticks, `/bot craft wooden_pickaxe`: "Crafted 1 Wooden Pickaxe.", no planks refusal; after it the bundle is empty and 9 planks sit loose (all 12 extracted first, 3 ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: put all of Jake's planks into a bundle (nothing loose), give a task that needs
    planks (e.g. `/bot skill woodcut` with no axe → it crafts one, or ask for a crafting table).
  - Claude watches for: no `no planks` / crafting refusal; the planks reappear as a loose stack
    (extract-first) right before the craft.
  - Pass when: the craft succeeds; the bundle is lighter by exactly what was used.
- [x] **Bundled food counts (1.1.204)**
  - Sandbox 2026-10-18: PASS: S3 Jake's only food 5 bread in a bundle, `/bot set hunger 6 Jake`: within 15 s food 20 and 3 bread loose beside the bundle, no `Can't keep up`. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
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
- [x] **Wedged bot: pillar reason + 30 s back-off (1.1.204, P1 escape)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 on 1.1.271 fresh bot Pebble guarding in a 1x1x2 hole, player 150 blocks away, day. Bedrock lid: every try `pillar recovery placed no blocks reason=overhead-mine-failed`, then `Guard escape: Pebble failed — cooldown 30s` ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-18: FAIL: S3 fresh bot Wedge guarding in a 1x1x2 hole with 16 cobblestone, player 60 blocks away, day: every 30 s `pillar recovery placed no blocks reason=aborted steps=6` (x3), then `Guard escape: Wedge failed — cooldown ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: dig a 1×1×2 hole under a guarding/patrolling bot with cobblestone in inventory;
    cover the top with a block so pillaring is impossible.
  - Claude watches for: `pillar recovery placed no blocks reason=<x>` (x names the actual break —
    `feet-occupied`, `overhead-mine-failed`, `not-airborne`, `place-rejected:…`), then
    `[guard-escape] <bot> still below surface after recovery; cooldown 30s reason=<x>`.
  - Pass when: the reason is a concrete break, not blank, and the retry spacing becomes ≥30 s
    (was ~12 s). Remove the cover: he pillars out on the next attempt.
- [ ] **Travel-wait: hobby during a fast-travel cooldown (1.1.204, P1 idle)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PARTIAL: passes only with Admin > Idle Hobbies Anywhere on (default off: `too-far-from-home`, decision in the guided doc). S12 trigger is `/bot compass travel Jake` twice (lodestone at -92 62 -599). With Idle Hobbies Anywhere on, 07:57:55 `travel-wait enqueued Jake remaining=1463t`, chat "Jake is resting 1m 13s before tra ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with idle hobbies **on**, give Jake a lodestone compass + a registered base; do
    one fast-travel (`/bot come` from far away), then immediately `/bot come` again from ≥100 blocks.
  - Claude watches for: chat `Jake is resting Xm Ys before traveling to …; I'll keep busy
    meanwhile`; `travel-wait enqueued Jake remaining=…t`; a `-> HOBBY` policy line; a hobby
    task starting; later `travel-wait cooldown expired for Jake (waited …t); retrying travel`.
  - Pass when: he does a hobby instead of standing still, then fast-travels on his own when the
    cooldown ends (≤3 min), with no repeated `giving up … after 3 retries`.
- [x] **Travel-wait cancels on stop (1.1.204)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PASS: S12 08:00:35 `travel-wait enqueued Jake remaining=1857t`, `/bot stop Jake` 08:00:42 ("Jake has stopped."); the cooldown ran out at about 08:02:05 with no `travel-wait` line at all and Jake stayed put ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: during the wait above, `/bot stop Jake`.
  - Pass when: no `retrying travel` line fires later — he stays put.
- [x] **Travel-wait offload stays deferred (1.1.204, known)** — closed 2026-10-18 by triage: SUPERSEDED, 1.1.205 (39b48d9a) made the offload real and removed the deferred line; covered by 6d Travel-wait offload to an existing chest (1.1.205)
  - Bradley does: same setup with hobbies **off**, inventory ≥85 % full, a registered chest nearby.
  - Claude watches for: exactly one `travel-wait offload deferred (no worker launch path)` per
    wait — not one per tick.
  - Pass when: the line appears once and the bot travels when the cooldown ends. (Real offload is a
    follow-up; this item just proves the log isn't spamming.)
- [x] **Hotbar-lock combat honesty (1.1.204, side effect)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 Jake in an oak boat on land, hotbar planks/dirt/sticks, iron sword in the main inventory, easy, zombie 4 blocks away (21:47:40): Jake moved the sword into hotbar slot 0 (planks pushed to slot 14) and held it; no swing with ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: put Jake in a boat with his sword outside the hotbar, spawn a zombie nearby.
  - Pass when: he does not swing a random item; either he dismounts and fights or stays passive —
    no wrong-item swings. Record what he did.

---

## Phase 6d — Loose ends (1.1.205): bundle reach, ambient offload, furnace fuel

- [x] **Bundled cobblestone still gets a pillar (1.1.205)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 Pebble guarding in an open 1x1x2 hole, all 16 cobblestone in a bundle: `ensureAtSurface: Pebble pulled 1 scaffold stack(s) out of bundles for pillar recovery` (21:53:11), pillar steps=6, `Guard escape: Pebble succeeded` ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: put ALL of Jake's cobblestone in a bundle (none loose), wedge him in a 1×1×2 hole
    with an open top while guarding.
  - Claude watches for: `ensureAtSurface: Jake pulled N scaffold stack(s) out of bundles for pillar
    recovery` then a successful pillar (no `placed no blocks`).
  - Pass when: he pillars out; the bundle is lighter by the stacks used.
- [ ] **Hand-bundled better pickaxe gets pulled (1.1.205)**
  - Sandbox 2026-10-18: FAIL: S3 stone pickaxe loose, diamond pickaxe in a bundle, `/bot skill mining` into a placed stone block: every `Mining ... with minecraft:stone_pickaxe` (6 blocks), the diamond pickaxe stayed in the bundle. Finding 6. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: give Jake a stone pickaxe loose and a diamond pickaxe INSIDE a bundle; `/bot skill
    mining` on stone.
  - Pass when: the diamond pickaxe appears loose and is the one in hand within the first few blocks;
    no repeated extraction attempts (one stack moves once).
- [x] **Bundled armor equips only if better (1.1.205)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PASS: S12 iron chestplate on, a bundle holding a leather chestplate given (08:06:59), `/bot equipArmor Jake` and 25 s of audits: no Armor line, leather still in the bundle (`bundle_contents` checked). Bundl ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: Jake wearing iron chestplate; put a leather chestplate in a bundle → nothing should
    happen; then put a diamond chestplate in a bundle.
  - Pass when: leather stays bundled; diamond is extracted and equipped on the next armor audit.
- [ ] **Boat + out-of-hotbar sword, FarmSkill/RideSync variants (1.1.205)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PARTIAL: S12 pool at 24..34 -106..-96, me and a pig in my boat, Jake following with a full hotbar, oak boat in slot 21 and iron sword in slot 22: `boat-place:equip:slot0` (pickaxe moved out to 21), eight `boat ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: in a boat with the sword outside the hotbar, ask him to place a boat/minecart
    (RideSync) or scoop water (farm) — both should refuse cleanly rather than use slot 0.
  - Pass when: no wrong-item use; chat/log shows the action declined.
- [ ] **Travel-wait offload to an existing chest (1.1.205)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): FAIL: S12 08:04:03 hobbies off, bag 35/36 (`full=0.97`), chest 4 blocks away: every travel-wait line reads `chest=no ... -> WAIT` until `TRAVEL_NOW` at 08:05:38; no offload. The chest probe never runs: `las ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: hobbies OFF, inventory ≥85 % full, a registered chest within ~12 blocks; trigger the
    cooldown wait (`/bot come` twice from far).
  - Claude watches for: `travel-wait offloading to existing chest` ONCE, an AMBIENT task
    `travel-wait-offload` starting, items landing in the chest, then `cooldown expired … retrying travel`.
  - Pass when: exactly one offload walk; no head-swivel during the walk (auto-face suppressed); if he was
    following you, follow resumes afterwards.
- [ ] **Offload backoff on a full chest (1.1.205)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): NOT REACHED: blocked by finding 32: no travel-wait offload can start. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: same, but the chest is completely full.
  - Pass when: at most 2 attempts ≥60 s apart, then no more offload walks for that wait.
- [ ] **`/bot stop` during the offload does not poison later offloads (1.1.205)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): NOT REACHED: blocked by finding 32. Note: `/bot stop` also cancels the whole wait (`BotEventHandler.noteStopCommand` calls `TravelWaitService.cancel`), so a later wait is always a fresh request. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot stop` mid-walk, then trigger a fresh wait later.
  - Pass when: the later wait can still offload (the abort did not count as a failure).
- [x] **Furnace fuel fallback (1.1.205)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PASS: S12 first try with 20 planks: Jake crafted and placed a chest (08:43:06), so "planks below 32" does not stop a chest; it needs fewer than 8. With 7 planks, no logs, 16 leaves, 16 sticks, 32 leaf litte ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: no chest anywhere near, no chest materials (take his planks below 32 and remove
    logs), leaves + sticks in inventory, a furnace within 12 blocks (not in a protected zone); force an
    offload (`/bot store` or a full-inventory drop sweep).
  - Claude watches for: `[furnace-offload] Jake gave N item(s) to furnace at …`; chat "No chest nearby -
    dropped fuel into a furnace instead."
  - Pass when: leaves/sticks are in the furnace fuel slot, planks stayed at ≥32, nothing damageable or
    bundled was given. Repeat with a furnace that already holds coal → he skips it.

---

## Phase 6e — Follow-ups (1.1.206): on-thread tool swaps, hotbar target, offload latch

- [x] **Mining first-swing hitch is one tick, not more (1.1.206)**
  - Sandbox 2026-10-18: PASS: S3 only an iron pickaxe, in main inventory slot 11: the first swing already logs `Mining ... with minecraft:iron_pickaxe` and the pickaxe ends in hotbar slot 0; no `server-thread hop failed`, `on-thread step failed` ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot skill mining` with the best pickaxe OUTSIDE the hotbar.
  - Claude watches for: `Swapping best tool from main inventory slot N to hotbar slot M` once; no
    `Tool select: server-thread hop failed` / `on-thread step failed`; TPS steady.
  - Pass when: the swap happens before the first swing, ≤1 tick pause, then mining proceeds normally.
- [ ] **Swap re-validation is rare (1.1.206)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): NOT REACHED: S12 with `tool-selector` DEBUG on, three mining runs with the best pickaxe outside the hotbar; the pickaxe was pulled with `/item replace` each time, but every run had already ended (5.6 s "Cannot bre ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: same as above, and while he mines pull an item out of his inventory via the bot
    inventory screen once.
  - Claude watches for: at most one `Tool select: slot N no longer holds … — re-running scan once`
    (DEBUG) around that moment; never a repeating stream of them.
  - Pass when: he continues with a valid tool (the hotbar pick, or hands if none), no wrong-item swing.
- [x] **Full unlocked hotbar keeps the selected slot (1.1.206)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PASS: S12 hotbar full, selected slot 1 (no command sets it; bread in a slot plus hunger moves it), `/bot skill farm Jake`: the empty bucket came from main slot 24 into hotbar slot 1, the selected one, and w ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: fill all 9 hotbar slots, select slot 5, have him scoop water (farm) or place a boat.
  - Pass when: the swapped-in item lands in slot 5 (the selected one), not slot 0.
- [ ] **`/bot stop` during an offload does not disable later offloads (1.1.206)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): NOT REACHED: blocked by finding 32 (see the 6d offload item). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: trigger a travel-wait offload (Phase 6d setup), `/bot stop` mid-walk, then trigger a
    fresh wait later.
  - Claude watches for: no second failure counted; the later wait still logs
    `travel-wait offloading to existing chest`.
  - Pass when: the later offload runs. (Two genuine failures in one wait still stop further attempts.)

---

## Phase 6f — Crafting messages + water memory (1.1.207)

- [ ] **Actions tab has no Regroup; Spells tab still does (1.1.207)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S9 bot inventory overlay: Actions, "Orders & Travel" lists Follow, Guard, Patrol, Return Home, Sleep, no Regroup; the Spells tab's Movement section lists Regroup (greyed, "Spells require artifacts. No artifacts detected."). ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: open the bot inventory → Actions tab ("Orders & Travel"), then the Spells tab.
  - Pass when: Regroup appears only on Spells (Movement) and works from there.
- [ ] **`/bot direction reset` is the real name (1.1.207)**
  - Sandbox 2026-10-18: PARTIAL: S3 `/bot direction reset Jake` exists and answers "Jake has no stored work direction." Setting a direction and the next strip mine were not run. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: set a strip-mine direction by standing behind him, then `/bot direction reset`.
  - Pass when: the command exists and the next strip mine uses his facing again (README now matches).
- [ ] **Crafting refusal says exactly what's missing (1.1.207)**
  - Sandbox 2026-10-18: FAIL: S3 Jake with 1 white wool and 10 planks, `/bot craft bed`: in the Spawn base it crafted a crafting table, failed to place it (`claim-denied protected-zone`) and said only "I crafted a crafting table but couldn't ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: give Jake 1 white wool + 10 planks, `/bot craft bed`; then 2 sticks and no coal,
    `/bot craft torch 4`.
  - Claude watches for: chat `Can't craft bed: need 2 more white wool`; `Can't craft torch: need 2 more
    sticks, 4 more coal or charcoal` (numbers may differ by what's in nearby chests for the bed).
  - Pass when: the numbers match the shortfall, the bot crafts nothing, and no old generic line
    ("Beds need 3 wool…") appears.
- [ ] **Unknown craft name speaks once per 30 s (1.1.207)**
  - Sandbox 2026-10-18: FAIL: S3 `/bot craft spaceship Jake` three times in 6 s, then once 40 s later: "I don't know how to craft spaceship." all four times. Finding 7. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot craft spaceship` three times in ten seconds, then once more after 40 s.
  - Pass when: `I don't know how to craft spaceship.` appears at the 1st and 4th attempt only.
- [ ] **Remembered fishing spot (1.1.207)**
  - Sandbox 2026-10-18: PARTIAL: S4 second `/bot fish 1` at the same pond: `[19:16:29] Reusing remembered fishing spot: water=60, 77, -47 stand=59, 78, -47` with no new scan. The pond sat under an overhang and three runs ended "No bites after 6 ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot fish 1` at a pond, let him catch one; walk him 40–60 blocks away where no water
    is visible; `/bot fish 1` again.
  - Claude watches for: `Reusing remembered fishing spot: water=… stand=…`; then normal casting.
  - Pass when: he walks back to the pond instead of saying "I need to be standing near open water".
    Fill the pond in (or a 4-block-wide patch of it) and repeat → `Forgot remembered fishing spot …`
    then a fresh scan.
- [ ] **Wider fishing scan (1.1.207)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S8 new bot Finn (no remembered spot), a 7x7 pond dug 18 blocks east, `/bot fish 1 Finn`: `Selected fishing spot: stand=67, 68, 60 water=70, 67, 58` and it walked there (22:13:45). The search took 18712 ms (pass 2 finding 14 ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: stand 20 blocks from a pond (was out of the old 12-block reach), `/bot fish 1`.
  - Pass when: he finds it without a remembered spot (fresh world or after `bot_home_data.json` edit);
    the failure path in a desert answers within a second, not a multi-second stall.
- [x] **Farm reuses its irrigation source (1.1.207)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PASS: S12 day 1 (`/bot skill farm Jake targetX=14 targetY=78 targetZ=-100`): `Found still water at 1, 76, -90 for irrigation/refills`; `bot_home_data.json` `waterSpotsByBot` gained `jake: {1, 76, -90, kind ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: set up a farm with a bucket refill from a nearby pool; next day move the farm 30
    blocks (still within 48 of the pool) where no water is within 24 blocks.
  - Claude watches for: `Falling back to remembered water source at … for irrigation/refills`.
  - Pass when: he refills from the remembered pool instead of failing.
- [ ] **Per-kind cap keeps fishing memory (1.1.207)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S8 `bot_home_data.json` `waterSpotsByBot` after the fish: `finn: [{x 70, y 67, z 58, kind fishing}]`, and Jake's fishing spot from S4 is still there. No irrigation entry, because the farm item was not run. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: after the two items above, open `config/frens/bot_home_data.json` (or the
    equivalent BotHomeService file) → `waterSpotsByBot`.
  - Pass when: both a `fishing` and an `irrigation` entry are present for Jake with sane coordinates.

---

## Phase 6g — Zero-change refactors + debounced saves (1.1.208)

These are regression checks: every item must behave exactly as it did on 1.1.207. Any difference is a
fail, even an improvement — note it and move on.

- [x] **Leaf-blocked navigation unchanged (1.1.208)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 `/bot come Jake` twice through the forest canopy around 15 -110 (22:11:30 from 28 blocks, 22:11:48 from 30 blocks): both arrived (2.8 blocks at the end), no exception, no `LeafClearService` line. Judged by Bradley's ruling ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley's ruling 2026-10-18 (triage): later builds changed this path on purpose, so "identical to the old build" is no longer the bar. Pass when: the task runs with no errors and finishes.
  - Bradley does: `/bot come` through a dense leaf canopy (oak/jungle), twice.
  - Claude watches for: the same bypass-then-mine sequence as before; the 1200 ms per-bot cooldown
    still visible as the gap between leaf-mine attempts; no new exceptions mentioning `LeafClearService`.
  - Pass when: he gets through with the same feel and roughly the same number of leaves broken.
- [x] **Woodcut cleanup line-of-sight leaf clearing unchanged (1.1.208)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 three woodcuts with cleanup ran to "Cleared the local woodcut area after 5 target(s)." (19:43:24, 19:53:44) and "after 4 target(s)" (19:57:44) with no error. Judged by Bradley's ruling (runs clean and finishes); the ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley's ruling 2026-10-18 (triage): later builds changed this path on purpose, so "identical to the old build" is no longer the bar. Pass when: the task runs with no errors and finishes.
  - Bradley does: fell a leafy tree so the cleanup pass runs.
  - Claude watches for: per-attempt leaf cap unchanged (same count of "cleared leaf" lines per target);
    decaying leaves still skipped.
  - Pass when: cleanup completes; no leaf outside the old 3×3×3 sample box is touched.
- [ ] **Bridge scaffold still clears snow (1.1.208)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PARTIAL: No snowy gap was built: a bridge only starts from a woodcut perch toward logs, and RAVINE/DEEP_WATER directions are skipped. By log: pass 3 S5/S6 woodcut on the snowy plain ran nine `Woodcut fallback ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: woodcut across a snowy gap that needs a bridge.
  - Pass when: snow layers and leaves on the line are cleared exactly as before (raycast hit only).
- [x] **CollectDirt ladder exit unchanged (1.1.208)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S11 outside the Spawn base, Jake at the bottom of a 6-deep 1x1 shaft with a shovel and 32 ladders, `/bot skill collect_dirt Jake`: "Collected 10 dirt blocks." (23:04:18), no error; he tunnelled sideways and ended 6 below the ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley's ruling 2026-10-18 (triage): later builds changed this path on purpose, so "identical to the old build" is no longer the bar. Pass when: the task runs with no errors and finishes.
  - Bradley does: `/bot skill collect_dirt` where he digs a shaft deep enough to need ladders.
  - Pass when: the ladder column goes in on the same support side as before and he climbs out.
- [x] **Home data survives a normal quit (1.1.208, debounce)**
  - Sandbox 2026-10-18: PASS: S3 `/bot base set QuitBase` then save-and-quit within the same second: `bot_home_data.json` mtime moved to the quit second and holds QuitBase, no `frens-bot-home-writer` exception; after relaunch `/bot base list` ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: change something BotHomeService owns (set a base or sleep in a new bed), then quit
    to title within 2 s.
  - Claude watches for: no `frens-bot-home-writer` exception; file mtime updated at shutdown.
  - Pass when: on reload the change is present (`bot_home_data.json` was flushed on SERVER_STOPPING).
- [ ] **Home data write is batched (1.1.208, debounce)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S11 mtime of `config/frens/bot_home_data.json` sampled every 0.2 s for 26 min (22:59-23:25): one write, at 23:08:00 when `/bot base set TreeBase` saved a base. No per-mutation write storm, but the run had no hobby-heavy burst of ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: run a hobby-heavy 2 minutes (several base/sleep/nav mutations).
  - Claude watches for: `bot_home_data.json` mtime changing at most every ~0.5–5 s, not per mutation.
  - Pass when: writes are visibly coalesced and nothing is lost on reload. (Known cost: a hard kill loses
    ≤5 s of these changes — not a fail, just record it if it happens.)

---

## Phase 6h — Fortify Phase 2 extraction (1.1.209)

Regression checks only: fortify must behave exactly as on 1.1.208. Needs a village with at least two
hull vertices that get towers (use the same test village as before if it still exists). Watch the log
for the new categories `skill-fortify-tower` and `skill-fortify-cleanup` — the message text is unchanged.

- [x] **Tower build unchanged (1.1.209)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S11 `/bot fortify` at the snowy village at -160, -688 (Jake with 640 blocks and diamond tools): plan 18 vertices, 9260 blocks; all 17 towers visited in order with `[FortifyTower] approach-select`, `tower=N/17 ... attempt=k ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley's ruling 2026-10-18 (triage): later builds changed this path on purpose, so "identical to the old build" is no longer the bar. Pass when: the task runs with no errors and finishes.
  - Bradley does: `/bot skill fortify` on the village and let the wall stage reach the towers.
  - Claude watches for: `[FortifyTower]` vertex lines in the same order as a 1.1.208 run; scaffold
    column placed on the same side; summit step/return lines; the ledger teardown at the end.
  - Pass when: every tower reaches the completion ratio and no `FortifyTowerHelper` exception appears.
- [ ] **Deferred cleanup still drains (1.1.209)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): NOT REACHED: S11 no carve corridor happened (`Break-through found no viable candidates` 120 times, never a carve), so no `[FortifyCleanup]` / `skill-fortify-cleanup` line. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: during the build, watch for a carve corridor (bot mines through terrain) or force one
    by standing so the bot has to route through a hillside.
  - Claude watches for: `[FortifyCleanup]` queue lines under `skill-fortify-cleanup`; the replace-path
    retries (`mandatory` repairs) and the "would seal current exit" skip still fire.
  - Pass when: the corridor is repaired after the bot leaves it; the queue reports empty.
- [ ] **Tower patch unchanged (1.1.209)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S11 two blocks removed from tower 10's top (-204 67 -617, -202 67 -615), `/bot fortify patch` 23:38: it ran with no error, patching wall edges first ("Patching edge 13 (11 missing blocks, attempt 2)...", zero-progress segments ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley's ruling 2026-10-18 (triage): later builds changed this path on purpose, so "identical to the old build" is no longer the bar. Pass when: the task runs with no errors and finishes.
  - Bradley does: remove two blocks from a tower top, then `/bot skill fortify patch`.
  - Pass when: both blocks are replaced via the tower staging position (same approach as before);
    a vertex that makes no progress twice is skipped, as before.
- [x] **Merge verb still expands (1.1.209)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S11 `/bot fortify merge` 23:36:25: "Auto-detected nearest wall: wall_-173_-697", then "[Fortify] Schema expanded! Fortification plan: 18 hull vertices, 18 edges, 9980 blocks", "Carry-over scan retained 627 placed blocks", ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot skill fortify merge` (the dead `handleMerge` was deleted; the verb routes to
    expand).
  - Pass when: the response is the expand behaviour, not "unknown verb".
- [x] **Tower replan guard unchanged (1.1.209)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S11 whole fortify run (17 towers, many `walkTo unified replan` lines): `replan already active` never printed; the bot reached each tower approach or moved on. Not deliberately blocked, so judged by Bradley's ruling. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley's ruling 2026-10-18 (triage): later builds changed this path on purpose, so "identical to the old build" is no longer the bar. Pass when: the task runs with no errors and finishes.
  - Bradley does: block the tower approach so the bot triggers a medium-range replan.
  - Claude watches for: exactly one replan at a time (`replan already active` never appears twice in a
    row); the movement-epoch cancel still aborts the stale walk.
  - Pass when: the bot reaches the approach or gives the same give-up message as on 1.1.208.
- [x] **No new log noise (1.1.209)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S11 fortify 23:13-23:46: no `NullPointerException` (no exception of any kind) in the log. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Claude watches for: any `NullPointerException` mentioning `FortifyTowerHelper`,
    `FortifyCleanupProcessor` or `FortifySkillOps`.
  - Pass when: none in the whole session.

---

## Phase 6i — Fortify Phase 3 carve extraction (1.1.210)

Regression checks only: carving must behave exactly as on 1.1.209. Watch the log for the new category
`skill-fortify-carve` — the message text is unchanged. Grep: `grep -n "skill-fortify-carve" latest.log`.

- [ ] **Corridor carve unchanged (1.1.210)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): NOT REACHED: S11 `[FortifyNav] Carve mode activated ctx=fortify-tower:approach` once (23:17:37, tower 4), but every break-through search found no candidate (`allAir=34 canBreak=0 noFloor=28`); the approach was a 5-block drop, not a hillside, ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley's ruling 2026-10-18 (triage): later builds changed this path on purpose, so "identical to the old build" is no longer the bar. Pass when: the task runs with no errors and finishes.
  - Bradley does: during `/bot skill fortify`, stand so the bot has to route through a hillside (or
    reuse the corridor from Phase 6h).
  - Claude watches for: break-through candidate/eligibility lines under `skill-fortify-carve`; the
    carve session opens and the corridor is dug the same depth as on 1.1.209.
  - Pass when: the bot reaches the far side and `attemptFinalizeCarveTransaction` queues the repair
    (`skill-fortify-cleanup` lines follow).
- [ ] **Carve repair still drains (1.1.210)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): NOT REACHED: no carve happened (see the corridor item). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Claude watches for: the mandatory repair of the corridor after the bot leaves it; the "would seal
    current exit" skip still fires when the bot is still inside.
  - Pass when: the corridor is closed and the cleanup queue reports empty.
- [ ] **Trap-escape carve unchanged (1.1.210)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): NOT REACHED: not set up (no 1x1 pocket during the build). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley's ruling 2026-10-18 (triage): later builds changed this path on purpose, so "identical to the old build" is no longer the bar. Pass when: the task runs with no errors and finishes.
  - Bradley does: box the bot into a 1×1 pocket beside the wall during the build.
  - Claude watches for: emergency trap-escape eligibility and meaningful-progress lines; carving stops
    at the depth limit (6) if it cannot break out.
  - Pass when: the bot is freed or gives the same give-up message as on 1.1.209.
- [ ] **Refused carve still summarised (1.1.210)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S11 the break-through summary prints once per attempt with reason counts (`allAir=4 canBreak=0 reach=0 noFloor=0 recentCooldown=0 offsets=4 rejectReasons=none`, 120 lines); no protected block was put in a carve line, so the ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: put a protected block (lodestone, chest) in the only carve line.
  - Claude watches for: the nav-break reject summary line (reason counts) once per attempt.
  - Pass when: the summary prints and the bot replans instead of mining the protected block.
- [x] **No new log noise (1.1.210)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S11 fortify run: no `NullPointerException` mentioning `FortifyCarveHelper` or `FortifyCarveContext` (no exception at all). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Claude watches for: any `NullPointerException` mentioning `FortifyCarveHelper` or
    `FortifyCarveContext`.
  - Pass when: none in the whole session.

---

## Phase 6j — Soul novelty rejection (1.1.211)

Off by default — nothing in this phase happens until the toggle is on. Grep:
`grep -n "novelty" latest.log` (drops print as `[souls] novelty dropped bot=<name> reason=trigram|exact`).

- [x] **Toggle defaults off and round-trips (1.1.211)**
  - Sandbox 2026-10-18: PASS: S2 `/bot soul novelty status` "Novelty rejection is OFF", `on`, status "is ON", settings.json5 `"soulNoveltyRejectionEnabled": true`. Singleplayer only; the LAN/dedicated sync half was not run. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul novelty status` on the fresh config, then `/bot soul novelty on`, then
    `status` again; on a dedicated-server/LAN client also confirm the value arrives after config sync.
  - Claude watches for: the status line reading off, then on; no `UnrecognizedProperty` in the log.
  - Pass when: the second status reads on and `config/frens/settings.json5` contains
    `soulNoveltyRejectionEnabled: true`.
- [ ] **Novelty fires and does not gag the bots (1.1.211)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): FAIL: S5 drops happen and banter survives them (20:06:49 trigram drop, scene still 3 lines). But PLAYER scene b489440f "bots, what should we do next?" 20:34:29: the model repeated its previous answer, `novelty dropped bot=Jake ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with the toggle on and two bots in the roster, sit through ~6 scenes in one sitting.
  - Claude watches for: `novelty dropped` lines and the delivered-line count per scene.
  - Pass when: ≥1 drop occurs across the session **and** no scene delivers zero lines because of
    novelty alone.
- [x] **Silas stays laconic (1.1.211)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 Silas in the roster for four scenes 20:11-20:14: no novelty drop for Silas at all (so never `trigram`). Replay A 20:11. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: run at least two scenes with Silas in the roster.
  - Claude watches for: any `novelty dropped bot=Silas` line and its reason.
  - Pass when: Silas's short idioms ("Aye.") only ever drop with `reason=exact`, never `trigram`.
- [x] **Quoted short idiom is not a repeat (1.1.211)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 the only trigram drops were real near-repeats of long lines: 20:06:49 Jake "RotiWokeman grew up on a farm, didn't he? Maybe he knows something about dealing with...other things." after 20:05:37 "...about finding food in the ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Claude watches for: a `reason=trigram` drop whose dropped line is long and merely quotes a short
    earlier line (the `MIN_TRIGRAMS` floor should prevent this).
  - Pass when: none in the session; if one appears, note the two lines for the floor 3→4 follow-up.
- [x] **Toggle off is silent (1.1.211)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 `/bot soul novelty off` 20:09:19, scenes 20:09:31 and 20:10:16: no `novelty` line, both 4/4. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul novelty off`, then two more scenes.
  - Claude watches for: any `novelty` line after the toggle.
  - Pass when: none, and scene delivery counts match the pre-toggle pattern.

---

## Phase 6k — Soul peer stance (1.1.212)

No toggle — the feature is inert until a peer stance leaves baseline, and the prompt clause is empty
until then. Grep: `grep -n "peer stance" latest.log` (lines print as
`[souls] peer stance bot=<name> peer=<name> trust=<t> exasperation=<e> curiosity=<c>`).
Mind files: `config/frens/souls/<botId>/mind.json` (or wherever `[souls] mind consolidated` names).

- [x] **Old minds load clean (1.1.212)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S7 simulated: Bob's `mind.json` saved without the `peerStances` and `relations` keys (game closed), launch, rollover 21:23:44 `mind consolidated bot=6a00845c... day=8`: the file now has `"peerStances": {}` and `"relations": []`, ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: launch with the pre-1.1.212 `mind.json` files in place (no reset); sleep through one
    night.
  - Claude watches for: `[souls] mind consolidated` at the rollover and any Jackson
    `UnrecognizedProperty` / `InvalidDefinition` warning.
  - Pass when: consolidation succeeds and each rewritten file contains `"peerStances": {}` (or a
    populated map) — never a missing key or a null.
- [x] **Peer entry appears after shared scenes (1.1.212)**
  - Sandbox 2026-10-18: PASS: S1/S2 `peer stance bot=Jake peer=Bob trust=4 exasperation=0 curiosity=3` and the Bob mirror (16:51:47). mind.json: Jake's `peerStances` has one entry keyed `6a00845c...` (Bob's UUID), Bob's keyed `2af3f0a5...` ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with Jake and Bob together and quiet, let two banter scenes fire in which both speak.
  - Claude watches for: `[souls] peer stance bot=Jake peer=Bob …` and the mirror line for Bob.
  - Pass when: both files hold one `peerStances` entry keyed by the other bot's UUID, all three values
    in 0..6, and at least one value differs from baseline (3/0/3).
- [x] **Peer clause reaches the prompt (1.1.212)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S6 Jake's peer curiosity for Bob seeded to 5 in `mind.json` (game closed): WORK scene 21:07:02 CURRENT STATE for Jake ends "..., curious about Bob" (16 chars, Bob in that roster). Replay B 21:07:02. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: force a question between the bots (`/bot soul banter now` until one bot ends a line
    naming the other with `?`), then one more scene next day so curiosity/exasperation ≥ the ladder
    threshold — or read the clause from `mind.json` values directly.
  - Claude watches for: the request's `CURRENT STATE` block (diagnostic logging) carrying
    "curious about Bob" / "a little tired of Bob" / "short with Bob" / "wary of Bob" /
    "thick as thieves with Bob".
  - Pass when: the clause names the other bot, is ≤50 chars, and only appears for bots present in
    that roster.
- [x] **Bumps are once per day per pair (1.1.212)**
  - Sandbox 2026-10-18: PASS: S2 day 0: Jake's trust moved once (16:51:47) and curiosity once (3 to 4 at 17:23:00); scenes at 17:18, 17:20, 17:21, 17:22 logged no `peer stance` line. The next change came after the day-1 rollover (`lastTrustDay: 1`). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul banter now` ×3 within one Minecraft day with both bots speaking.
  - Claude watches for: `peer stance` lines after scene 1 vs 2 vs 3.
  - Pass when: trust changed at most once and curiosity/exasperation each at most once that day
    (no `peer stance` line at all for scenes 2–3 is the expected outcome).
- [x] **Day rollover decays toward baseline (1.1.212)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 rollover 19:59:40 (day 1 to 2): Bob's peer entry for Jake (trust 4, curiosity 3) is gone from `peerStances`: trust stepped to 3, so it reached baseline and was dropped. Jake's map was already empty. Completes the S2 PARTIAL. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-18: PARTIAL: S2 the day-1 rollover ran (`mind consolidated bot=... day=1`, 17:23:58/17:24:06). After it both peer entries read `trust 4, curiosity 3` with `lastTrustDay: 1`: Jake's curiosity 4 moved to 3 (one step), trust was ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: sleep through a night after the entries exist; read both `mind.json` files.
  - Claude watches for: `[souls] mind consolidated` and the peer values.
  - Pass when: every peer axis moved exactly one step toward 3/0/3, and an entry that reached exact
    baseline is gone from the map.

---

## Phase 6l — Soul typed relation facts (1.1.213)

Toggle-gated: `/bot soul relations on` first — until then no BELIEFS block, no relation anchors, no
`relations` log lines, only an empty `"relations": []` in `mind.json`. Grep:
`grep -n "\[souls\] relations" latest.log` (lines print as
`[souls] relations bot=<name> added=<n> total=<n>` at day rollover). Audit view: `/bot soul beliefs <Bot>`.

- [x] **Toggle default and round trip (1.1.213)**
  - Sandbox 2026-10-18: PASS: S2 "Relation facts are OFF", `on`, "Relation facts are ON", settings.json5 `"soulRelationsEnabled": true`. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul relations status`, then `/bot soul relations on`, `status` again.
  - Claude watches for: nothing in the log; chat replies.
  - Pass when: first status reads off on a fresh config, second reads on, and `config/frens/*.json5`
    holds `soulRelationsEnabled: true` after the save.
- [x] **Old minds load clean (1.1.213)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S7 same simulated file as 6k: consolidation 21:23:44 wrote `"relations": []`, no Jackson warning. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: launch with the pre-1.1.213 `mind.json` files in place (no reset); sleep one night.
  - Claude watches for: `[souls] mind consolidated` and any Jackson `UnrecognizedProperty` /
    `InvalidDefinition` warning.
  - Pass when: consolidation succeeds and each rewritten file contains `"relations": []` (or a populated
    list) — never a missing key or a null.
- [x] **SEEN fact forms from repeated completions (1.1.213)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 three finished `/bot skill woodcut Jake` on day 0 (19:43:24, 19:53:44, 19:57:44), rollover: `[19:59:45] relations bot=Jake added=1 total=1` once; `/bot soul beliefs Jake` "Jake is good at woodcut (conf 0.4, SEEN, day 2, ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with the toggle on, have Jake complete `/bot skill woodcut` at least three times in one
    Minecraft day (let each run finish, not `/bot stop`), then sleep through the night.
  - Claude watches for: `[souls] relations bot=Jake added=1 total=1`.
  - Pass when: the line appears exactly once and `/bot soul beliefs Jake` prints
    `Jake is good at woodcut (conf 0.4, SEEN, day <d>, salience 5)` — the object is `woodcut`, never `skill`.
- [x] **BELIEFS block reaches the prompt (1.1.213)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 tap, scene 20:01:06: "BELIEFS (claims the bots hold, not world truth)\nJake believes:\n- Jake is good at woodcut" right after the ABOUT RotiWokeman block, about 80 chars; no "Bob believes" (Bob has no facts). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: nothing; let the next group banter scene fire (or `/bot soul banter now`).
  - Claude watches for: the request's prompt (diagnostic logging) carrying
    `BELIEFS (claims the bots hold, not world truth)` followed by `Jake believes:` / `- Jake is good at woodcut`.
  - Pass when: the block sits right after the ABOUT block, is ≤240 chars, and never appears for a bot
    whose `relations` is empty.
- [x] **Relation anchor fires and goes on cooldown (1.1.213)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 day 5, four `banter now`: the third cue (20:16:54) "one of you points out I am good at woodcut", Bob "You're good at woodcut, though. I've seen you make quick work of those trees."; the fact's day 2 to 5 and salience 4 to 7; ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: three days after the fact formed, `/bot soul banter now` up to 4 times.
  - Claude watches for: a seed with `topic="relation:GOOD_AT|Jake|woodcut"` on a fired line; then the
    fact's `day` in `mind.json`.
  - Pass when: the seeded line references being good at woodcut without stating it as world fact, and
    after it fires the fact's `day` equals the current day and `salience` rose by 3 (cap 10) — the same
    anchor does not fire again for three days.
- [x] **Toggle off is inert (1.1.213)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 `/bot soul relations off` 20:19:45, rollover to day 6: no `relations` line, the fact kept (salience 7 to 6); scenes 20:21:03 and 20:22:10 have no BELIEFS block and no relation cue. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul relations off`, sleep a night, fire two scenes.
  - Claude watches for: `relations` log lines, `BELIEFS` in the prompt, `relation:` seed topics.
  - Pass when: none of the three appear; `mind.json` still holds the earlier fact (decayed by 1 per
    night) — the toggle stops production and rendering, not storage.

---

## Phase 6m — Soul structured LLM output, `##FRENS` side channel (1.1.214)

Toggle-gated: `/bot soul structured on` first — until then the prompt carries no side-channel contract,
the output cap stays 320 tokens, and a stray `##FRENS` line is stripped from the scene but never applied.
Grep: `grep -n "\[souls\] side-channel" latest.log` (lines print as
`[souls] side-channel correlationId=<id> stance=<applied>/<seen> facts=<applied>/<seen> dropped=<n>` or
`[souls] side-channel unparsed correlationId=<id> chars=<n>`). Absent tail = no line at all (expected).
Requires `/bot soul relations on` for facts to be visible via `/bot soul beliefs <Bot>`.

- [x] **Toggle default and round trip (1.1.214)**
  - Sandbox 2026-10-18: PASS: S2 "Structured output is OFF", `on`, "Structured output is ON", settings.json5 `"soulStructuredOutputEnabled": true`. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul structured status`, then `/bot soul structured on`, `status` again.
  - Claude watches for: nothing in the log; chat replies.
  - Pass when: first status reads off on a fresh config, second reads on, and `config/frens/*.json5`
    holds `soulStructuredOutputEnabled: true` after the save.
- [x] **Contract reaches the prompt only when enabled (1.1.214)**
  - Sandbox 2026-10-18: PASS: S2 loopback tap (`tools/sandbox/ollama_tap.py`): the 19 requests before the toggle (16:51-17:16) have no `##FRENS` paragraph, the first scene after it (17:18:15) has "You may end your reply with one extra line ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with the toggle off fire one scene (`/bot soul banter now`), turn it on, fire another.
  - Claude watches for: the request's system contract (diagnostic logging) carrying
    `You may end your reply with one extra line starting with ##FRENS`.
  - Pass when: the paragraph is absent from the first request and present in the second.
- [ ] **Side channel is emitted at all (1.1.214)**
  - Sandbox 2026-10-18: FAIL: S2 structured on, "Jake, I hate the Nether" then 6 banter scenes (17:18-17:24, llama3.1:8b): no `[souls] side-channel` line, and the tap shows no reply ending in a `##FRENS` line. The 8B model ignores the optional ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with the toggle on, tell Jake something opinionated ("I hate the Nether") and let
    five scenes fire (Jake + Bob together).
  - Claude watches for: `[souls] side-channel` INFO lines — applied or `unparsed` — per correlationId.
  - Pass when: over 5 scenes at least one usable tail appears (a line with nonzero `stance=` or `facts=`
    applied), **or** the outcome is recorded as "3B cannot do it" and fallback (3), the clerk call, is
    scheduled in `RALPH_TASK.md`.
- [ ] **A malformed tail never costs a scene (1.1.214)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): FAIL: S5 20:33:09 PLAYER scene: the 8B reply was only `##FRENS {"stance":...,"facts":[...]}` with no dialogue: `accepted=false rawChars=241`, `outcome=failed:MALFORMED`. Finding 16. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: nothing; watch the same 5 scenes.
  - Claude watches for: every `side-channel unparsed` line and the delivery outcome on the same
    `correlationId`.
  - Pass when: each scene with an unparsed tail still delivered its lines; zero `outcome=failed:MALFORMED`
    attributable to a sentinel.
- [x] **`##FRENS` is never spoken (1.1.214)**
  - Sandbox 2026-10-18: PASS: S2 structured on, 6 scenes: 0 chat lines contain `##FRENS` (the model never wrote a tail either, see the side-channel item). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: read chat and listen during those scenes.
  - Claude watches for: the literal `##FRENS` in a delivered line or in TTS text.
  - Pass when: it never appears in chat or audio.
- [ ] **A model fact lands as INFERRED at ≤0.6 (1.1.214)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): NOT REACHED: no side-channel line was ever applied (the one tail came with no dialogue and failed the scene; banter replies never carried one). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: after an applied line with `facts=1/…`, run `/bot soul beliefs Jake` and `/bot soul beliefs Bob`.
  - Claude watches for: the fact in both bots' output.
  - Pass when: it prints with `INFERRED`, confidence ≤0.6, subject is Roti/Jake/Bob/the world — never an
    unknown name — and appears for every bot that was in the scene.
- [ ] **Model stance delta is peers-only and once per day (1.1.214)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): NOT REACHED: same: no applied side-channel line. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: force three scenes in one Minecraft day; then read `mind.json` for both bots.
  - Claude watches for: `stance=` applied counts, and `peerStances` values before/after.
  - Pass when: `playerStance` never moves on a side-channel line; each peer axis moved at most one step
    that day; values stay within 0..6.
- [x] **Toggle off is inert (1.1.214)**
  - Sandbox 2026-10-18: PASS: S2 `/bot soul structured off` (17:44:38): the next two requests (17:44:49, 17:46:26) carry no `##FRENS` contract (tap), no `side-channel` line. No INFERRED fact existed to check storage. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul structured off`, fire two scenes.
  - Claude watches for: `side-channel` lines and the contract paragraph in the prompt.
  - Pass when: neither appears; earlier INFERRED facts remain in `mind.json` (the toggle stops
    production, not storage).

---

## Phase 6n — Speech floor and idle-hobby backoff (1.1.216)

First build driven by a real field log (`logs/2026-09-06-session-overlap.log.gz`, 2026-09-06 22:29–22:33).
Needs Jake + Bob both spawned, souls enabled, and a rideable animal nearby for the mount lines. No new
toggles: the floor and the backoff are always on.

- [ ] **No two bots talk over each other (1.1.216)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): FAIL: S5 273 voiced scripted lines (`Played sound`), 27 pairs under 4 s, several from two bots in the same second (20:21:49 Bob `wildlife_pig_nearby` + Jake `ambient_blame_terrain`; 20:45:28 `idle_here_if_needed` + `animal_quality`). ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: stand near both bots with an animal in view for two minutes and let ambient lines fire.
  - Claude watches for: `Scripted chat delivered bot=… part=0` timestamps in `latest.log` (1.1.217+). NOT
    `Sending chat message` — before 1.1.217 that line was logged ahead of the text gate and counted lines nobody saw.
    With scripted Text off, delivery is voice/overhead only: listen, and use `Scripted chat suppressed (text off)` to
    confirm the gate.
  - Pass when: no two scripted lines land within four seconds of each other, and the 22:30:08 shape of
    three lines from two bots inside one second never recurs.
- [ ] **Scripted lines do not interleave with a soul scene (1.1.216)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): FAIL: S5 voiced ambient lines between scene lines: 20:04:49 `context_listening` (scene e0caee4c, lines 3-4), 20:05:30 and 20:05:34 (8b3eaa9a), 20:06:06 (0f9ba089), 20:07:36 `weather_sunny` (69bbfde8), 20:58:09. Finding 17. Replay A ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: trigger a scene (`/bot soul banter now`) while standing near an animal.
  - Claude watches for: `[souls] scene-playback … line=N/M` and any `Sending chat message` between the
    first and last line of that `routingId`.
  - Pass when: no scripted line lands mid-scene, and none lands in the twenty seconds after
    `outcome=finished`.
- [ ] **A scene still gets through a scripted floor (1.1.216)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S5 every requested scene fired; the only `vetoed:speech-floor` lines were `armedBy=scene-end` (6). Ambient lines never arm the floor (finding 17), so the scripted-floor preemption itself was not exercised. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Log evidence 2026-09-08 20:10–20:15 (1.1.216): 4 `vetoed:speech-floor`, each followed by `fired` — PASS in logs; confirm live.
  - Bradley does: same as above, but keep the animal lines firing while asking for a scene.
  - Claude watches for: `outcome=vetoed:speech-floor` versus `outcome=fired`.
  - Pass when: scenes still fire — a run of only `vetoed:speech-floor` with no `fired` over several minutes
    means scripted ambient is starving the soul lane and the preemption is not working.
- [x] **The two banter lanes respect one quiet period (1.1.216)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S6 ACTIVE scene finished 21:12:49, IDLE `vetoed:speech-floor armedBy=scene-end` 21:13:05, IDLE fired 21:13:10 (21 s). S5 IDLE-to-IDLE gaps 20-24 s. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Log evidence 2026-09-08 20:09–20:15 (1.1.216): finish→next fire 20–45 s, all ≥20 s — PASS in logs; confirm live.
  - Bradley does: let two scenes fire back to back without intervening.
  - Claude watches for: the gap between `scene-playback … outcome=finished` and the next
    `banter lane=… outcome=fired`.
  - Pass when: the gap is at least twenty seconds regardless of which lane fires — the 1.1.215 log had
    IDLE finish at 22:32:26 and ACTIVE fire at 22:32:35.
- [x] **Muting scripted dialogue does not mute souls (1.1.216)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S7 Scripted Text and Voice masters OFF: scenes bac71e99 (21:38:31) and c99432c7 (21:39:47) played 4/4 `text=true voiced=true`, no `vetoed:speech-floor` from the scripted lane. Replay D 21:38:31. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: mute the scripted Text and Voice masters, then let a scene fire.
  - Claude watches for: `scene-playback` lines and `vetoed:speech-floor`.
  - Pass when: scenes play normally. A silent scripted lane must never close the floor — that would be the
    lane-separation rule broken.
- [ ] **Mount and animal lines stop repeating (1.1.216)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S5 five minutes riding a tamed saddled horse beside cows, a sheep and a tamed wolf (20:54:39-20:59:39): no "Nice horse." / "That's a quality animal." / "I respect a well-behaved animal." at all, so no repeat (each fired once ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: stay mounted near the same animals for five minutes.
  - Claude watches for: repeats of "Nice horse.", "That's a quality animal.", "I respect a well-behaved
    animal." with their timestamps and speakers.
  - Pass when: no line repeats inside five minutes, from either bot. The 1.1.215 log repeated at 122s, 123s
    and 91s.
- [x] **A hurt wolf still gets an answer (1.1.216)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 tamed wolf beside both bots, `/damage` 20:53:49 then 20:54:05 (16 s): `wolf_leave_alone` at 20:53:49 and again at 20:54:05. Replay A 20:53:49. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: let a wolf take damage near both bots, twice, about fifteen seconds apart.
  - Claude watches for: the wolf-hurt line and which bot said it.
  - Pass when: the second event still draws a line — the per-pool dedup must not have inherited the
    five-minute animal window.
- [x] **The woodcut storm is gone (1.1.216)** — the repro Bradley captured
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 Jake with an empty inventory idle in the forest at 15 76 -110, hobbies on: `Idle hobby 'woodcut' finished ... success=false` at 21:50:03, 21:52:03, 21:56:03 only, never more than once a minute. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Log evidence 2026-09-07/08 (1.1.216): 2 failures per session, `failures=1 remaining=48s` then `failures=2 remaining=108s` — PASS in logs.
  - Bradley does: take every axe, plank and stick off Jake, leave him idle near trees for three minutes.
  - Claude watches for: `Idle hobby 'woodcut' finished for Jake: success=false` and
    `skipping hobby 'woodcut' backing off: failures=N remaining=…s`.
  - Pass when: the failure line appears a handful of times at widening intervals, never more than once a
    minute, and never the 3–19 per second of the 1.1.215 log.
- [x] **The backoff ladder widens (1.1.216)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 `backing off: failures=1 remaining=48s` (21:49:15), `failures=2 remaining=108s`, `failures=3 remaining=228s`, `failures=4 remaining=468s` (21:56:15): 60, 120, 240, 480 s. The ten-minute hold was not reached before the axe test. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Log evidence 2026-09-07/08: 48 s → 108 s seen; sessions ended before 240 s — PARTIAL.
  - Bradley does: keep watching the same idle Jake for ten minutes.
  - Claude watches for: the `remaining=` values across successive skip lines.
  - Pass when: the gaps widen roughly 60s, 120s, 240s, 480s and then hold at ten minutes.
- [x] **An axe in a chest wakes it back up (1.1.216)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 a chest with a stone axe beside Jake 22:00:48, deep in the 480 s backoff: `Idle wooden fallback: Jake signature changed ... with 3894 ticks of cooldown left; resetting` the same second, and supply requests for the axe at once. ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: once Jake is deep in a long backoff, put an axe where he can reach it.
  - Claude watches for: the next `Idle wooden fallback` / woodcut start.
  - Pass when: he retries within seconds rather than waiting out the remaining backoff.
- [x] **Commanded work still starts immediately (1.1.216)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 `/bot skill woodcut Jake` 22:04:15 during the backoff: the skill started the same second (bare-hand bootstrap, "No axe yet, so I'll punch a tree first."). `/bot woodcut Jake` as the item writes it answers "Unsupported inline ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with Jake in a woodcut backoff, issue `/bot woodcut Jake` directly.
  - Claude watches for: the skill starting.
  - Pass when: the command runs at once — the backoff gates the idle fallback only, never a direct order.

## Phase 6o — Pre-warm, torch hysteresis, truthful scripted logs, scene floor reservation (1.1.217)

From the five unreviewed 1.1.216 sessions (2026-09-07/08). No new toggles. Includes the Codex work shipped in this build
(voice-preserving model switch, group-chat guide topic, one-time group-chat hint).

- [x] **Voices are warm before the first scene (1.1.217)**
  - Sandbox 2026-10-18: PASS: S2 `[16:57:40] tts prewarm voices=2` and `tts engine started pid=19661` 2 s after the bots spawned, 40 s before the first scene. Line 1 vs line 2 speed not judged by ear. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: load the world with Jake + Bob soul-enabled and voice on; wait ~10 s, then `/bot soul banter now`.
  - Claude watches for: `[souls] tts prewarm voices=N` within ~5 s of the bots being online, and `[souls] tts engine
    started pid=…` lines BEFORE the scene's `scene correlationId=… outcome=scene-started`.
  - Pass when: no `tts engine started` line appears as the first scene plays, and line 1 starts about as fast as line 2.
    If line 1 still lags with the process already up, Piper's first inference is the cost — the deferred "Ready." priming.
- [ ] **Torch stays up in the dark (1.1.217)** — run at night or underground; daylight only yields `light-above-7`
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S8 sealed stone tunnel at y 40 with one wall torch: `[torch-hold] Jake verdict=hold gate=none light=7 action=held slot=0 savedSlot=2` (savedSlot is his pre-torch slot), and `verdict=reject gate=light-above-11 light=12 ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: walk Jake (give him torches; Bob carries none unless given some) through a dim cave for a minute.
  - Claude watches for: `[torch-hold] Jake verdict=hold … savedSlot=<his real slot>` and the gate on the yield line.
  - Pass when: the torch stays in hand across light 8–11 and is put away only at `gate=light-above-11` (light ≥12) or a
    hostile/task gate; `savedSlot=` shows the pre-torch slot, not the torch slot.
- [x] **Quit mid-hold does not reload with a torch in hand (1.1.217)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 quit while Jake held a torch in the dark tunnel (22:18), relaunch: first lines `verdict=reject gate=no-torch-in-inventory` then `verdict=hold ... light=5 action=held slot=0 savedSlot=2`, so he loaded on his own slot 2 and only ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: while Jake holds a torch underground, save and quit, reload.
  - Claude watches for: Jake's held item after reload (and `[torch-hold]` lines).
  - Pass when: Jake reloads holding his own pre-torch item.
- [ ] **Scripted lines hold off while a scene is generating (1.1.217)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): FAIL: S5 20:06:44 banter fired, 20:06:50 `weather_sunny` with line 1; 20:05:59 `ambient_my_job` in the fire second. Pet and context lines do respect the reservation, ambient chatter does not (finding 17). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: stand near an animal with scripted voice or text on, trigger `/bot soul banter now`.
  - Claude watches for: `Scripted chat delivered … part=0` or audible scripted lines between `banter … outcome=fired` and the
    scene's first line; scripted veto lines naming the reservation.
  - Pass when: no scripted line lands between fire and line 1, and scripted lines resume after the scene (post-scene quiet
    20 s).
- [ ] **Voice on, text off still arms the floor (1.1.217)**
  - Bradley does: scripted Voice on, scripted Text off; stand near animals for two minutes.
  - Claude watches for: audible pet/context lines and their spacing.
  - Pass when: no two voiced scripted lines within four seconds of each other.
- [x] **Scripted logs tell the truth (1.1.217)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S7 Scripted Text OFF 21:38-21:41: `Scripted chat suppressed (text off) category=reactions bot=Bob` (21:39:29, 21:39:40) and Jake (21:40:20), no `Sending chat message` for a bot line. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: toggle scripted Text off for a minute, then on.
  - Claude watches for: `Scripted chat suppressed (text off)` while off, `Scripted chat delivered … part=0` while on.
  - Pass when: no `Sending chat message` line appears while Text is off.
- [x] **Ollama usage is logged (1.1.217)**
  - Sandbox 2026-10-18: PASS: S2 32 `[souls] ollama usage model=llama3.1:8b promptTokens=... evalTokens=...` lines, one per call. Largest `promptTokens=1731` (digest), `numCtx=8192`, no `nearCtxLimit=true`. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: nothing extra — any soul reply or scene.
  - Claude watches for: `[souls] ollama usage model=… promptTokens=… evalTokens=…`.
  - Pass when: the line appears per call; note the largest `promptTokens` seen (sizing input for `NUM_CTX`), and flag any
    `nearCtxLimit=true`.
- [x] **Model switch keeps voices alive (1.1.217, Codex 7a663785)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 `/bot soul model` 8B to 3B (19:33:11), back (19:37:06), to gpt-oss and back: one `tts engine started` (19:30:07), none after any switch; the next replies were voiced. Switched by command, the menu's "Selected" label not read. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: switch the soul model in the model manager mid-session, then trigger a scene.
  - Claude watches for: `tts engine started` lines after the switch.
  - Pass when: no voice engine restarts on a model-only switch; the menu says "Selected", and the first reply on the new
    model may be slow (model load).
- [ ] **Group-chat hint appears once (1.1.217, Codex 43bd5474)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S5 seen file deleted before launch; rewritten once at 19:30:07 when two soul bots were in earshot; mtime unchanged after the 21:04 and 21:17 relaunches, so it did not come back. The on-screen hint and "Open guide" were not ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: first time with two owned soul-enabled bots in earshot, in calm gameplay.
  - Claude watches for: the hint on screen; `frens/group-chat-hint-seen.txt` in the world dir afterwards.
  - Pass when: it shows once for ~15 s, Open guide lands on the group-chat topic, and it does not come back after
    reconnecting.

## Phase 6p — Construction interior egress (1.1.218)

Bradley's report: the bot builds a house and gets stuck inside trying to reach the far side of a corner instead of
walking out the doorway. Needs a flat, open build site; give the bot enough materials. No toggles.

- [x] **Schematic builder leaves through the doorway (1.1.218)**
  - Sandbox 2026-10-18: PASS: S3 `/bot skill build small_hut` (with 8 torches, which it requires): `[egress] ... door=121,-61 src=DESIGNED_GAP waypoints=3 ... outcome=ok` twice, no `outcome=failed`. The site was on a tree canopy, so only 37/86 ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot build small_hut` (then `small_shelter`) with the bot starting inside the footprint area once the
    walls are half up — or just watch a full build.
  - Claude watches for: `[egress] bot=… door=x,z src=DESIGNED_GAP … outcome=ok` when a stance is outside.
  - Pass when: the bot walks out the gap instead of pressing into the wall; no `outcome=failed` run of two followed by the
    old stall.
- [x] **Hovel builder uses the door from the wall line (1.1.218)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S9 `/bot shelter hovel Bob` (diamond tools given): five `[hovel-egress] ... from=INSIDE to=OUTSIDE outcome=ok guard=proceed` (22:24:34-22:30:18) out the east door, then two `outcome=failed:exit guard=fail` from INSIDE (allowed), ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot shelter hovel` and watch the corners.
  - Claude watches for: `[hovel-egress] … from=ON_WALL|INSIDE … outcome=ok guard=proceed`.
  - Pass when: corner stances on the far side are reached via the door; any `outcome=failed` line still shows
    `guard=proceed` unless the bot was strictly inside heading strictly outside.
- [x] **No fall off a raised floor (1.1.218)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S9 `/bot skill build watchtower Jake` with no spruce fence (the railing): "Built schematic 'watchtower': 102/119 blocks placed." in 80 s; Jake's health 20 all through (sampled every 10 s), ended on top at y 72; the one egress on ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot build watchtower` with materials for all but the top railing (or stop it before the railing).
  - Claude watches for: `[egress]` lines while the bot is on the platform; its Y afterwards.
  - Pass when: the bot never walks off the platform edge through a missing railing (no `[egress] … outcome=ok` whose exit is
    past the edge; no fall damage).
- [x] **Line-of-sight retries stay local (1.1.218)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S9 hovel: egress sequences 22:24:34, 22:28:33, 22:29:25, 22:30:18 minutes apart, each to a different target; watchtower two egresses at different wall gaps. No out-and-back run at one wall block. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: nothing extra — watch wall placement from inside.
  - Claude watches for: `[egress]` lines clustered at the same wall block.
  - Pass when: no out-and-back egress sequence per wall block; retries stay on the bot's side first.
- [ ] **`/bot stop` mid-egress stops cleanly (1.1.218)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S9 second hovel, `/bot stop Bob` the moment the first `[hovel-egress]` line printed (22:33:50): `Task 'skill:shelter' aborted: Stopped by the player.`, "Bob has stopped.", skill exit 22:33:51. The egress line is written when the ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot stop` while the bot walks out the doorway.
  - Claude watches for: `outcome=aborted` (schematic) or `(aborted)` (hovel).
  - Pass when: the bot stops at once and the next build still tries the doorway (an abort does not count toward the
    2-failure switch).

## Phase 6q — Who a chat line is for; supplies groundwork (1.1.219)

The chat addressee rules and the 30 s DM follow-up window came out of the 2026-09-25 decision-model eval. The window
opens only after a soul DM reply is actually delivered, and only while no other human is online. Souls on, Jake + Bob
nearby, solo. Supplies Phase 2 is dormant: bots can't ask for chest items yet, so only the groundwork is checked here.

- [x] **A follow-up without a name reaches the same bot (1.1.219)**
  - Sandbox 2026-10-18: PASS: S2 "Jake, how is the mining going?" then "ok, and after that?" 16 s later: `[17:01:33] [souls] dm follow-up routed player=RotiWokeman bot=Jake ageMs=10169`, Jake answered in the same thread, no "Processing your ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: "Jake, how is the mining going?" — wait for Jake's reply — then within 30 s a follow-up with no name
    ("why that?", "ok, and after that?").
  - Claude watches for: `[souls] dm follow-up routed player=… bot=Jake ageMs=…` (ageMs < 30000), then Jake's reply.
  - Pass when: Jake answers the follow-up in the same conversation; no "Processing your message" line; no refusal
    notice.
- [x] **The window closes after 30 s and on a new address (1.1.219)**
  - Sandbox 2026-10-18: PASS: S2 an unaddressed line 64 s after Jake's reply got only `local ... vetoed:cooldown`, no `dm follow-up routed`. Then "Bob, hi" and an unnamed line 14 s later: `[17:03:25] dm follow-up routed ... bot=Bob ageMs=9048`. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: after Jake replies, wait ~40 s and type an unaddressed line; then DM Bob ("Bob, hi"), wait for Bob,
    and send another unaddressed line.
  - Claude watches for: no `dm follow-up routed` for the late line; the last line routed with `bot=Bob`.
  - Pass when: the stale window never catches the late line, and the follow-up goes to the bot that answered last.
- [ ] **"Guys" and "everyone" reach both bots (1.1.219)**
  - Sandbox 2026-10-18: PARTIAL: S2 "hey guys, what's the plan?" `scene-routing ... candidates=2 eligible=2 outcome=submitted` (17:04:15, 17:14:22), no "Processing your message". Only Bob spoke (`lines=1`, finding 2). The party-OFF half was not run. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with party chat on, "hey guys, what's the plan?" and "everyone follow me".
  - Claude watches for: `[souls] scene-routing … outcome=…` with both bots as candidates.
  - Pass when: both bots take part. With the party toggle OFF, the same line produces nothing: no "Processing your
    message", no legacy LLM call.
- [x] **A named bot beats a soft word (1.1.219)**
  - Sandbox 2026-10-18: PASS: S2 "hey guys, Jake come here" (17:10:44): one `[souls] routing ... bot=2af3f0a5` (Jake), no scene-routing line, only Jake answered ("I'm here. What's up?"). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: "hey guys, Jake come here".
  - Claude watches for: a single-bot DM routing line for Jake, no scene-routing line.
  - Pass when: only Jake responds.
- [x] **Trailing name and comma lists (1.1.219)**
  - Sandbox 2026-10-18: PASS: S2 "where did Bob put the torches, Jake?" routed to Jake only (17:11:27); "Jake, Bob come here" gave `scene-routing ... candidates=2 eligible=2` (17:15:47, after Bob was rebound); "Bob, Jake says the chest is full" ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: "where did Bob put the torches, Jake?" then "Jake, Bob come here" then "Bob, Jake says the chest is full".
  - Claude watches for: the routing lines' bot names.
  - Pass when: the first goes to Jake only; the second reaches both; the third goes to Bob only.
- [ ] **A line for another person stays with them (1.1.219, needs a second player)**
  - Bradley does: with a friend online, "<friend's name>, Jake is being weird".
  - Claude watches for: no soul routing line, no scene line, no overhear reaction for that line.
  - Pass when: neither bot reacts. Also: with the friend online, a DM follow-up window never opens (lines after Jake's
    reply need his name again).
- [x] **Handled lines don't become DMs (1.1.219)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 night, two beds: "Jake, it's getting dark out here." 21:01:27, reply 21:01:33; "zzz" 21:01:35: both bots `Task 'sleep' started` and slept (21:01:37-38), no `dm follow-up routed`. Replay A 21:01:35. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: right after a Jake DM reply, type "zzz" at night near a bed.
  - Claude watches for: the zzz trigger, and no `dm follow-up routed` for that line.
  - Pass when: the bot sleeps and no DM turn fires for "zzz".
- [x] **Bot-placed chests record their owner (1.1.219, supplies groundwork)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 Jake placed a supply chest during a woodcut when his bag filled (20:43:49, -53, 82, -60); `bot_chest_registry.json` record has `"ownerUuid": "e6608e8d-..."` (RotiWokeman). The registry keys worlds by level name only, so other ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: let a bot fill its inventory until it places a supply chest, then open
    `config/frens/bot_chest_registry.json`.
  - Claude watches for: the new record.
  - Pass when: the new record has `"ownerUuid"` set to Bradley's UUID. Records from before 1.1.219 have no owner;
    that is expected and they are refused for future supply requests until re-placed. (Superseded by 1.1.221: they
    prompt like any other chest; see Phase 6s.)
- [x] **Supply commands are safe for non-ops (1.1.219)**
  - Sandbox 2026-10-18: PASS: S4 as the host: `/frens supply answer 00000000-... once` gave `[supply] answer ... result=NOT_FOUND` and "That request is no longer open."; `/frens supply revoke` on a fresh chest gave `revoke ... removed=false` and ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: as a non-op (or just as himself), `/frens supply answer 00000000-0000-0000-0000-000000000000 once` and
    `/frens supply revoke ~ ~ ~` on a chest.
  - Claude watches for: `[supply] answer … result=NOT_FOUND` (or NOT_RUNNING_OR_INVALID) and
    `[supply] revoke … removed=false`; `[supply] started: 0 standing permission(s) restored` at world load.
  - Pass when: both reply with a plain sentence, nothing errors, nothing moves.

## Phase 6r — Bot Storage screen is owner-only (1.1.220)

1.1.220 closes a multiplayer hole in the Bot Storage screen: any player could use it on any bot. The owner's own use must
still work exactly as before. Solo is enough for the first three items; the last one needs a second player (LAN).
Before starting, have a bot that has placed at least one supply chest (it shows in Bot Storage).

- [ ] **Collect still works for the owner (1.1.220)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): NOT REACHED: no live chest record for Jake in this save (Quick Store does not register its chest; the only record was destroyed). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: bot inventory screen → Storage → pick a live chest → Collect, once with "stay" and once with "return
    to player".
  - Claude watches for: `Chest collect: … sending <bot> to chest at …`, then `Post-arrival: <bot> withdrew N items from
    chest at …`. No `[chest-registry] denied`, no `[chest-registry] refused collect`, no `withdraw refused`.
  - Pass when: the bot comes back with the chest's items, and the return trip happens for "return to player".
- [ ] **Go and Dismiss still work for the owner (1.1.220)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S9 Storage > for Jake listed one record (the supply chest he placed in S5, shown "Destroyed" in this copy); Dismiss: `Dismissed chest record for Jake at -53,82,-60` (22:44:09) and the list emptied. Go needs a live chest record; ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: Go on a live chest; later, Dismiss on a chest entry he no longer wants.
  - Claude watches for: `Chest go: … sending <bot> to chest at …`; `Dismissed chest record for <bot> at …`.
  - Pass when: the bot travels to the chest and takes nothing; the dismissed entry leaves the list after the refresh.
- [x] **Quick Store / Quick Fetch still work for the owner (1.1.220)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S9 chest beside Jake, Quick Store, point and click: "Jake deposited 434 items (kept its gear)." (`Quick deposit: Jake deposited 434 items at 32,63,40`, 22:42:45), Quick Fetch "Jake fetched 434 items." (22:43:08). No ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: Actions → Store Here on a chest near the bot, then a Fetch from it.
  - Claude watches for: `Quick deposit: <bot> deposited N items at …` and `Quick fetch: <bot> fetched N items at …`.
  - Pass when: both move items, and no `[chest-registry] denied store` line appears.
- [ ] **Another player can't use your bot's storage (1.1.220, needs a second player)**
  - Bradley does: with a non-op friend on LAN who has their own bot, the friend right-clicks their own bot → Storage,
    then picks Bradley's bot in the screen's bot dropdown (it lists every online bot) and tries Refresh and Collect.
    Right-clicking Bradley's bot directly is already refused by the inventory permission check.
  - Claude watches for: `[chest-registry] denied request from <friend> for <bot>: DENY_NOT_OWNER`, and the
    same line with `collect` / `store` for the others.
  - Pass when: the friend sees "Only <bot>'s owner can do that." and gets no chest list; the bot doesn't move; nothing
    leaves any chest.

## Phase 6s — Companions ask before taking from chests (1.1.221)

Supplies Phase 3: bots take nothing from a chest by themselves any more. They ask ("Jake wants 1 Stone Axe from the
chest at x y z — [Allow once] [Always: common supplies, this chest] [No]"), or use a chest you marked Always. Only
common items qualify: dirt, cobblestone, planks, logs, sticks, torches, coal and charcoal, bread and cooked food, seeds,
carrot and potato, and wooden, stone and copper tools and leather/copper armour. A chest always keeps 16 of each
material and 1 spare of each tool type. Souls optional. One bot (Jake) is enough; stand within 32 blocks unless an item
says otherwise.
Grep: `[supply] request`, `[supply] answer`, `[supply] transfer`, `[supply] withdraw`.

- [ ] **Do this first: mark your supply chest Always (1.1.221)**
  - Sandbox 2026-10-18: PARTIAL: S3 Always works for food: `choice=always result=GRANTED_ALWAYS` (18:12:53), the next take `outcome=COVERED_BY_ALWAYS ... MOVED` with no prompt. The axe case never prompted: `/bot skill woodcut` with no axe beside a ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Why: an idle bot far from you can no longer fetch a tool unless the chest has an Always.
  - Bradley does: take Jake's axe (let him keep his sword), then put 2 stone axes in a chest beside him while he
    idles. Click
    **[Always: common supplies, this chest]** when the prompt appears.
  - Claude watches for: `[supply] request … outcome=OPENED id=…`, then `[supply] answer … result=GRANTED_ALWAYS`,
    then `[supply] withdraw … result=MOVED moved=1`.
  - Pass when: exactly one prompt appears, Jake takes one axe, and one axe stays in the chest.
- [x] **Allow once really lets him take it (1.1.221)**
  - Sandbox 2026-10-18: PASS: S3 (bread, not the axe/sword kit): `answer ... choice=once result=GRANTED_ONCE` at 18:07:02, `withdraw ... result=MOVED moved=2` at 18:07:08 (6 s), no second prompt for it. Also planks for a craft: once at 18:16:55, ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: at a chest with no Always, holding 2 wooden axes and 1 wooden sword, take Jake's axe and sword.
    Click **[Allow once]** on the first prompt.
  - Claude watches for: `result=GRANTED_ONCE`, then `[supply] withdraw … result=MOVED` for that item.
  - Pass when: the approved item moves within ~10 s (he may take an axe as his weapon; the lone sword stays, it is
    the chest's spare) and the same item is never prompted a second time. In this build's pre-review commits, only
    Always worked.
- [x] **No means no (1.1.221)**
  - Sandbox 2026-10-18: PASS: S3 `food 10 no 70`: `choice=no result=REJECTED`, then `withdraw ... REFUSED reason=NOT_PERMITTED`, and 68 s later `request ... outcome=REJECT_COOLDOWN` with no new prompt. Nothing moved. Three minutes later the same ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: at a chest with no Always, answer **[No]** to a prompt.
  - Claude watches for: `result=REJECTED`, then no `[supply] withdraw … result=MOVED` for that chest and item.
  - Pass when: nothing moves, and Jake doesn't ask about that chest again for at least a minute.
- [ ] **Walking away keeps the log quiet; coming back works at once (1.1.221)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S8 chest of 2 stone axes beside Jake (no Always), player 50 blocks away 2 min (22:02-22:04): 3 `[supply]` lines (`OWNER_NOT_NEARBY`), quiet. Back, `/bot skill woodcut Jake`: started at once but never asked the chest; it went to ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with a chest that has no Always, walk more than 32 blocks from Jake for 2 minutes while he lacks an
    axe. Then walk back and run `/bot woodcut`.
  - Claude watches for: no prompt while you are away, and at most 4 `[supply]` lines a minute for Jake however many
    chests are near him. On `/bot woodcut`, a prompt within seconds.
  - Pass when: the woodcut prompts as soon as you are back. It must not fail with "no axe" for minutes after you
    return.
- [x] **A hungry bot asks for food above the reserve (1.1.221, reserve 8 since 1.1.268)**
  - Sandbox 2026-10-18: PASS: S3 `tools/sandbox/supplytest.py food 10`: `[18:07:00] [supply] request bot=Jake ... item=minecraft:bread qty=2 ... outcome=OPENED`, answered once, `withdraw ... result=MOVED moved=2`, 8 left. `food 8`: no request ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot set hunger 10 Jake` beside a chest holding 10 bread. Then repeat with a chest holding only 8
    bread.
  - Claude watches for: a food prompt at 10 bread (`item=minecraft:bread`), then `withdraw … MOVED` for at most 2.
    No request line for the 8-bread chest.
  - Pass when: he eats from the 10 and leaves 8, and leaves the 8-bread chest alone (Bradley 2026-10-18: food keeps 8,
    other materials 16).
- [x] **A full inventory never freezes the game (1.1.221)**
  - Sandbox 2026-10-18: PASS: S3 Jake holding 36 stacks of cobblestone, Always chest with 24 bread: `transfer ... status=NO_ROOM`, `Dropped 64x Cobblestone to free inventory space.`, `MOVED moved=2`, `Mutual aid: Jake took 2x Bread`, all in the ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: fill Jake's inventory with junk, set hunger 10, and stand beside an Always chest with 20+ bread.
  - Claude watches for: `NO_ROOM` then a dropped cheap stack, then `MOVED`. No "Can't keep up!" line in the log.
  - Pass when: he drops something cheap, takes the bread, and the game never stutters.
- [ ] **Old bot chests prompt instead of refusing (1.1.221)**
  - Bradley does: use a chest a bot placed before 1.1.219 (its registry record has no `ownerUuid`) for any of the
    checks above.
  - Claude watches for: `outcome=OPENED`, not `DENIED(DENY_UNKNOWN)`.
  - Pass when: you get the normal prompt.
- [ ] **Crafting and seeds from a chest (1.1.221, new behaviour)**
  - Sandbox 2026-10-18: PARTIAL: S3 craft half: the first `/bot craft stick` asked (`purpose=craft-planks ... WAITING`) and answered "Sticks need 2 planks per craft."; after [Allow once] the second run `withdraw ... MOVED moved=2` then "Crafted 4 ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: put 20 oak planks in a chest beside Jake, empty his planks and sticks, and run `/bot craft` for
    sticks. Click [Allow once], then run the same `/bot craft` again within a minute. Then put 20 wheat seeds in a
    chest and run `/bot harvest` on a field with empty farmland.
  - Claude watches for: a planks prompt and a failed first craft; on the second run `[supply] withdraw … purpose=…
    result=MOVED`, then the craft. For the harvest, a seeds prompt while Jake waits at the chest, then `MOVED`.
  - Pass when: the second `/bot craft` crafts with the chest planks (the first one only asks; crafting doesn't wait for
    an answer), and the harvest replants after one click. Chest pulls from a worker thread never worked before this
    build.
- [x] **Never-allowed items stay put (1.1.221)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 chest with saddle, lead and iron axe beside idle Jake (22:05-22:06:15, hobbies on): no `[supply]` line at all, nothing taken; the stone-axe chest at the same spot had drawn requests within seconds. The mount half was not tried. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: put a saddle, a lead and an iron axe in a chest beside Jake, then try to mount him or make him fetch
    an axe.
  - Claude watches for: no `[supply] request` for those items.
  - Pass when: nothing is taken and nothing is prompted. Hand him the items yourself.
- [x] **Revoke all (1.1.221)**
  - Sandbox 2026-10-18: PASS: S3 after an Always: `[18:14:14] [supply] revoke-all owner=RotiWokeman removed=1 grants=0`, chat "Revoked 1 standing permission(s)...", and the next hunger take prompted again (`outcome=OPENED` 18:14:24). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/frens supply revoke all`, then repeat the first check.
  - Claude watches for: `[supply] revoke-all owner=… removed=1`.
  - Pass when: the reply counts what it removed, and the next take prompts again.

## Phase 6t — The game never freezes while a bot looks for a table or makes room (1.1.222)

Before this build, three things a bot does on its own could stop the whole server for 2–6 s at a time:
- an idle craft looking for a crafting table (the stone-tool upgrade, cobble tools, wooden fallback);
- a hungry bot with a full bag making room for food on the ground;
- a bot making room for gear another bot hands it.

Each tried to walk on the server's own tick, which can never move the bot. Now:
- on the tick, a bot only uses a table within reach, or places one from its inventory beside itself;
- a full bot only drops a stack; it never walks to a chest.
`/bot craft` and skills still walk to a far table as before.

Souls optional; Jake is enough, plus Bob for the gear item. Stand within 32 blocks.
Freeze detector for every item: no `Can't keep up!` line in the log.
Grep: `craft-station tick-side`, `Idle stone upgrade`, `to free inventory space`, `mutual-aid make-room`.

- [ ] **Idle stone upgrade with a table in reach (1.1.222)**
  - Sandbox 2026-10-18: PARTIAL: S3 with the item's kit (wooden pickaxe and shovel, 3 cobblestone, 2 sticks, table 2 blocks away, idle hobbies on) nothing crafted in 100 s: every 12 s `Idle wooden fallback: Jake blocked — no safe woodcut target near ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: give Jake a wooden pickaxe and a wooden shovel (no other pickaxe or shovel), 3 cobblestone and 2
    sticks, with no planks or logs. Without the shovel he goes off to cut wood for one, which is a different hobby.
    Put a crafting table 2 blocks from him and let him idle.
  - Claude watches for: `Idle stone upgrade: Jake upgraded wooden tools to stone`, and no `Can't keep up!`.
  - Pass when: he crafts the stone pickaxe at that table without going to look for another one, and the game never
    stutters.
- [x] **A far table: no walk, no freeze, one quiet line (1.1.222)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 Jake with the stone-upgrade kit (wooden pickaxe, shovel, axe, sword, 3 cobblestone, 2 sticks), the only table 18 blocks away, 3 min idle: one `craft-station tick-side: no table in reach for Jake, not walking (reason=no-table)` ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: the same kit, but the only crafting table is about 20 blocks away and Jake holds no table. Let him
    idle for 3 minutes.
  - Claude watches for: `craft-station tick-side: no table in reach for Jake, not walking (reason=far-table)` or
    `(reason=no-table)`. After a miss the idle craft backs off 2 minutes, so expect one or two such lines, not a
    stream. No `Can't keep up!`. No repeating `Failed to place` WARN.
  - Pass when: he doesn't walk off to the far table on his own, the game never stutters, and the log stays that
    quiet.
- [x] **A table in his bag gets placed beside him (1.1.222)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 same kit plus a crafting table in the bag: `Placed crafting table from inventory at 17, 76, -108` (Jake at 16, 76, -108), `Idle stone upgrade: Jake upgraded wooden tools to stone` 22:10:30; stone pickaxe in the bag. The axe ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: the same kit plus 1 crafting table in Jake's inventory, with no table nearby.
  - Claude watches for: `Idle stone upgrade: Jake upgraded wooden tools to stone`, and no `Can't keep up!`.
  - Pass when: a crafting table appears within 2 blocks of him and he crafts the stone pickaxe.
- [x] **`/bot craft` still walks to a far table (1.1.222, unchanged)**
  - Sandbox 2026-10-18: PASS: S4 planks in the bag, a table 15 blocks away on open ground: "Give me a moment. I'll use the crafting table at 57, 79, -59.", walked there in 4 s, "Crafted 1 Wooden Pickaxe.", no `craft-station tick-side` line. A run ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with a crafting table about 15 blocks away and planks in Jake's bag (no table in it), run
    `/bot craft` for a wooden pickaxe.
  - Claude watches for: no `craft-station tick-side` line for this command.
  - Pass when: he walks to that table and crafts, as before.
- [x] **A starving bot with a full bag drops junk, not a chest walk (1.1.222)**
  - Sandbox 2026-10-18: PASS: S4 (1.1.271) Jake with 36 stacks of cobblestone and dirt, a chest 10 blocks away, hunger 4, a bread summoned at his feet: `[19:00:2x] Dropped 64x Cobblestone to free inventory space.`, `Mutual aid: Jake cleared ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: fill Jake's bag with cobblestone and dirt stacks, put a chest 10 blocks away, then run
    `/bot set hunger 4 Jake` and drop a bread at his feet.
  - Claude watches for: `Dropped 64x … to free inventory space.`, then
    `Mutual aid: Jake cleared inventory space for nearby dropped food`. No `Can't keep up!`.
  - Pass when: he throws out one junk stack and eats the bread without walking to the chest. Rarely he may pick his
    own stack back up after 2 s; that is known and accepted.
- [x] **Gear: the full bot only makes room when there is something to give (1.1.222)**
  - Sandbox 2026-10-18: PASS: S4 Bob full of junk with no weapon, Jake one stone sword: no `to free inventory space` for Bob in 65 s. Two more swords to Jake: `[19:02:35] Dropped 64x Cobblestone to free inventory space.`, `Mutual aid: Jake ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: fill Bob's bag with junk and take every sword, axe and pickaxe from him (the gear share counts any
    axe or pickaxe as a weapon). Give Jake one weapon and no leather armour, and wait a minute. Then give Jake two
    stone swords; he gives away a spare only when he holds at least two weapons.
  - Claude watches for: no `to free inventory space` line for Bob in the first minute. After the sword, one
    `Dropped …` for Bob, then `Mutual aid: Jake offered spare gear to Bob`.
  - Pass when: Bob throws nothing away until Jake actually has a sword for him.
- [x] **A flower never makes a full bot drop anything (1.1.222)**
  - Sandbox 2026-10-18: PASS: S4 Bob full, Jake carrying 5 poppies and 5 dandelions for 65 s: no `to free inventory space` line for Bob, no gift. Replay `frens_1_1_271_6t_full_bag_gear_craft`. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: keep Bob's bag full, and give Jake a few flowers.
  - Claude watches for: no `to free inventory space` line for Bob.
  - Pass when: Bob keeps all his stacks; the flower gift simply doesn't happen while he is full.

## Phase 6u — Gate follow, redstone doors, craft names, scene smoothing, crafting search (1.1.223)

This phase comes from the 2026-09-25 session, when Jake sat about 20 s behind the pressure-plate gate outside the underground base. It covers:
- Follow keeps handling doors when the bot is stuck far from you.
- A climb helper no longer drags a bot to a ladder behind it.
- Bots stop closing doors that a plate or button controls.
- `/bot craft stone_axe` works and says what's missing.
- Bot-to-bot scenes no longer end at a mere mention of you.
- The Crafting window has search and sort.

Souls on for the scene item (Jake + Bob). Freeze detector for every item: no `Can't keep up!`.
Grep: `Door debug`, `door-close skip`, `vertical-lock skip`, `nudgeTowardUntilClose`, `owner-address cut`,
`[souls] banter routingId`, `I need`.

- [ ] **Out through the plate gate (1.1.223)**
  - Bradley does: walk Jake and Bob out of the underground base through the oak fence gate with the pressure plate,
    as on 2026-09-25, and keep walking 10–15 blocks past it.
  - Claude watches for: `Door debug: … door=266,66,1285 open=… powered=… controlled=true`. There should be no run
    of `applyMovementInput-reject` into the fence, and no `vertical-lock acquire` toward a ladder behind the bot.
  - Pass when: both bots are through within about 5 s of you, and the game never stutters.
- [ ] **The bot leaves the plate's gate alone (1.1.223)**
  - Bradley does: the same gate, and watch it as the last bot passes.
  - Claude watches for: `door-close skip: powered or controlled`, or no close by a bot at all. There should be no
    `door-closed: marked recently closed` for 266,66,1285.
  - Pass when: the plate opens and closes the gate by itself, and no bot right-clicks it shut.
- [x] **Ordinary doors still get closed behind the bots (1.1.223)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S9 stone wall with a plain oak door, Jake and Bob following: once the player stood 12 blocks past it both went through and `door-close attempt` then `door-closed: marked recently closed` for 50, 66, 50 (Bob 22:22:27, Jake ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: open a plain wooden door with no plate or button nearby, walk through, and let the bots follow.
  - Claude watches for: `door-closed: …` for that door after the last bot passes.
  - Pass when: the door is closed behind them, as before.
- [x] **No server-thread walk warning (1.1.223 tripwire)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5-S8 whole logs (19:28-22:19): no `nudgeTowardUntilClose on the server thread` WARN. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: nothing extra; this is read at the end of the session.
  - Claude watches for: any `nudgeTowardUntilClose on the server thread` WARN, and records the caller it names.
    The expected candidates are return-base door and escape.
  - Pass when: none appear. If one does, it's a known deferral: log the caller in the handoff.
- [x] **`/bot craft stone_axe` names what's missing (1.1.223)**
  - Sandbox 2026-10-18: PASS: S3 3 sticks only: "I need 3 cobblestone and 2 sticks for a stone axe. I have 3 sticks."; with 3 cobblestone added: "Crafted 1 Stone Axe.", no "I don't know how to craft". See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: give Jake 3 sticks and nothing else, then `/bot craft stone_axe 1 Jake`. Then give him 3
    cobblestone and run it again.
  - Claude watches for: `I need 3 cobblestone … for a stone axe — I have 3 sticks.`, then a crafted stone axe.
    There should be no `I don't know how to craft stone axe`.
  - Pass when: both happen.
- [x] **A scene that mentions you plays in full (1.1.223)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 scenes mentioning RotiWokeman played in full: 20:01:06 4/4, 20:05:21 4/4, 20:06:49 3/3. The one `owner-address cut` (20:12:56) was Jake speaking TO RotiWokeman mid-scene. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with Bob and Jake near each other and banter on, let a few idle scenes play; a Rate… slider at
    about 70 speeds this up.
  - Claude watches for: `[souls] banter routingId=… seed="Stay on one subject:…"`. There should be no
    `owner-address cut` unless a bot spoke TO you. Scenes that mention RotiWokeman should have 2–3 lines.
  - Pass when: the bots answer each other on one subject, and a mention of you doesn't end the scene.
- [x] **Crafting window search and sort (1.1.223)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S9 Actions, Crafting >: search "diamond" gave "Nothing matches "diamond"."; cleared, Sort cycled A-Z, Z-A, Category (headers Functional Blocks / Tools & Utilities / Ingredients), Base type (Wood / Stone), Learned; selected Stick, ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: open the Crafting window. Type `diamond`, then clear it. Cycle Sort through A-Z, Z-A, Category,
    Base type and Learned. Select an item, re-sort, and press Craft.
  - Claude watches for: `bot craft <the selected item>` in the log.
  - Pass when: search filters the list, Category and Base type show headers, and Craft makes the item you
    selected.

## Phase 6v — Access control, DM privacy, no native downloads (1.1.224)

1.1.224 locks down what other players can do to your bots and keeps what you tell a bot in a DM out of scenes other players can hear. It also stops Frens downloading native libraries at startup. Most items need only you; the ones marked **2P** need a second, non-op account (a second client or a friend).

**First, once:** run `/bot config diagnostics on` so follow logs stay at INFO for the rest of the session.

Grep: `[bot-access] denied`, `[PermCheck]`, `scene privacy stop`, `[legacy-chat] denied`, `sqlite-vec`, `Downloading`, `NLP model`.

- [ ] **Startup permission mode (1.1.224)**
  - Sandbox 2026-10-18: PARTIAL: S4 on 1.1.271: `[18:57:13] [PermCheck] operator-permission mode=leveled-mapped class=net.minecraft.class_12086 field=OWNERS/field_63185`; bots spoke in chat as before (Jake's lines through this session). Carpet is ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley's ruling 2026-10-18 (triage): the library half is closed (sqlite-vec, xattr, the NLP model and the legacy stack were deleted in 1.1.243-1.1.251, and the 1.1.270 sandbox log has none of those lines). Only the permission check is left.
  - Found 2026-10-18: every log from 1.1.232 to 1.1.270 showed `mode=permission-all-mapped-fallback`. Cause: a static-init order bug (the field list was still null when the lookup ran). Fixed in 1.1.271. The old fallback was harmless (ALL grants everything OWNERS does in vanilla and Carpet), see the 1.1.271 changelog.
  - Claude watches for (startup, no play needed; any 1.1.271+ launch): `[PermCheck] operator-permission mode=leveled-mapped class=net.minecraft.class_12086 field=OWNERS/field_63185`.
  - Pass when: that line appears and bots still talk in chat and run Carpet `/player` moves as before.
- [ ] **Your own bot still works everywhere (1.1.224)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S9 right-click on Jake opens his inventory; `/bot inventory Jake` prints his inventory; Guide > Admin opens Jake's inventory overlay; no `[bot-access] denied` all session. The remote spell needs an artifact and the sunset prompt ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does:
    1. Right-click Jake.
    2. Run `/bot inventory Jake`.
    3. Open his inventory through the Guide.
    4. Cast a remote inventory / guidance spell from far away.
    5. Answer a sunset return prompt.
  - Claude watches for: no `[bot-access] denied` lines.
  - Pass when: everything opens, and the remote screen stays open at a distance.
- [x] **Diagnostics switch (1.1.224)**
  - Sandbox 2026-10-18: PASS: S2 `/bot config diagnostics off` with Jake following: 0 `Follow status:` lines in 30 s (0 in the whole log before it). `on`: 25 lines in the next 40 s. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot config diagnostics off`, follow for 30 s, then `/bot config diagnostics on`.
  - Claude watches for: `Follow status:` lines disappearing, then returning.
  - Pass when: both switches take effect.
- [ ] **2P: a stranger can't touch your bot (1.1.224)**
  - Bradley does, from the non-op second account:
    1. Right-click Jake.
    2. Try `/bot inventory Jake` (if permitted).
    3. Open the Guide on Jake.
    4. Try to change his hunts.
  - Claude watches for: `[bot-access] denied action=… sender=<2nd> target=Jake`.
  - Pass when: every attempt is refused and nothing changes.
- [ ] **2P: a DM stays private (1.1.224)**
  - Bradley does:
    1. Alone on the server, DM Jake a distinctive fact ("my secret base is under the oak tree").
    2. With Bob nearby, let a group scene play.
    3. Then have the second account join and stand nearby during a scene.
  - Claude watches for: the fact may appear while you're alone. Once the second player is in earshot, expect `[souls] scene privacy stop` or no mention of the fact.
  - Pass when: the second player never hears or sees the fact.
- [ ] **2P: hidden bases stay hidden (1.1.224)**
  - Bradley does: the second account opens the zone list or visualiser.
  - Claude watches for: only its own, allied and server bases listed.
  - Pass when: your private base doesn't appear.

## Phase 6w — AI companion setup, multiplayer AI settings, model-free first run (1.1.225)

1.1.225 makes Frens usable without Ollama and turns AI companions on from one checklist. Run the first two items in a **new world with cheats OFF**, with Ollama **stopped**, then start Ollama for the rest. Items marked **2P** need a second account joined over LAN or to a dedicated server. A LAN guest counts as a remote player; the LAN host counts as local.

Grep: `[ai-setup]`, `Ollama not detected`, `Ollama is not reachable`, `Processing your message`, `Rejected config save`, `reloadSettings failed`.

- [x] **No-Ollama first run (1.1.225)** — closed 2026-10-18 by triage: OBSOLETE, the legacy config hub, its Refresh and the "Ollama not detected" lines were deleted in 1.1.250/1.1.251 (7e25ac47, 080160e4) and the chooser is off by default since 1.1.235; the rest is covered by 6ar, 6as and 6y
  - Bradley does:
    1. Create a new world (cheats off) with Ollama stopped.
    2. Read the first-run screen.
    3. Spawn Jake and say "Jake, hello" in chat.
    4. Open the legacy config screen and press Refresh.
  - Claude watches for: at most one INFO `Ollama not detected at … optional, only needed for AI chat`. There should be no ERROR and no `Ollama is not reachable` anywhere.
  - Pass when:
    - The screen says "Choose Frens World Mode" and that Frens works without Ollama.
    - No "Processing your message, please wait." appears.
    - The model list shows "Ollama not detected (optional — only needed for AI chat)" instead of a fake model entry.
- [x] **Setup checklist, cheats-off singleplayer (1.1.225)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S10 commands-off world, Ollama up: Guide > AI setup listed Ollama ✔, model ✔, Soul Chat ✘, Finn and Newt ✘. Choose model... Use 3B (`soul model set to llama3.2:3b`), Turn on (`[ai-setup] RotiWokeman set Soul Chat on`), Enable ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does:
    1. Start Ollama.
    2. Open Guide → AI setup; then also try Bot Controls → Soul Chat row → Setup….
    3. Walk the steps: Choose model… (pick a small one), Turn on, Enable Jake, Say hi, then press Enter in chat.
  - Claude watches for: `[ai-setup] … set Soul Chat on` and `[ai-setup] enabled bot Jake as …`, then a soul reply correlation id for Jake.
  - Pass when: every step ticks ✔ without using a `/bot` command, and Jake answers the test line.
- [x] **Enable message names the blocker (1.1.225)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S8 `/bot soul system off`, `/bot soul enable Bob`: "Bob will speak as Bob, but Soul Chat is OFF. Turn it on in Set up AI companions or run /bot soul system on." See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: in a world with cheats on, run `/bot soul system off`, then `/bot soul enable Bob`.
  - Pass when: the reply says Soul Chat is OFF and how to turn it on, rather than an unconditional success.
- [ ] **Model button and labels (1.1.225)**
  - Bradley does: with a model selected, remove it in a terminal (`ollama rm <tag>`), then open Choose model….
  - Pass when: that model offers Download (not a disabled "Selected"), and the 8B row says "Largest option", not "Current default".
- [ ] **Checklist fits a small window (1.1.225)**
  - Bradley does: set GUI scale to its largest value (or shrink the window), with 3+ bots, then open the checklist.
  - Pass when: Refresh and Close are on screen, and bot rows scroll with the mouse wheel.
- [x] **Bot Controls keeps a Soul Chat change (1.1.225)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S10 after the checklist turned Soul Chat on: Bot Controls, Force-Place flipped, closed, reopened: Soul Chat row still ON, `soulsEnabled: true` in the file. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does:
    1. Turn Soul Chat on from the checklist and return to Bot Controls.
    2. Flip an unrelated toggle, close, and reopen.
  - Pass when: Soul Chat is still on.
- [ ] **2P: a non-operator gets told, not ignored (1.1.225)**
  - Bradley does, from the non-op second account:
    1. Flip Soul Chat in Bot Controls.
    2. Open the AI setup checklist and the model screen.
  - Claude watches for: one `Rejected config save from non-operator <2nd>`; repeats only at DEBUG.
  - Pass when:
    - The player sees "Only an operator or the host can change this server's Frens settings — your change was not saved."
    - Server-changing checklist buttons are disabled with "Ask the server operator / host".
    - The model screen says only the operator or host can change it.
- [ ] **2P: a remote operator's picks reach the server (1.1.225)**
  - Bradley does, from a remote account that is op (a LAN guest after `/op`, or a dedicated server):
    1. Open Choose model… and pick an installed model.
    2. Open Voice… and pick an engine.
    3. Toggle Soul Chat off and on in Bot Controls.
  - Claude watches for: `[ai-setup] soul model set to <tag>` and `[ai-setup] … set soul voice engine …` in the SERVER log, and no `reloadSettings failed`.
  - Pass when:
    - Both screens say "Runs on the server host".
    - A model not installed on the server shows "Pull it on the server machine: ollama pull <tag>" and no Download button.
    - The bot's next reply uses the new model, and the Soul Chat toggle takes effect without a restart.
- [x] **Scene logs carry no private text (1.1.225)**
  - Sandbox 2026-10-18: PASS: S2 diagnostics on, after the "I hate the Nether" DM: banter, scene and playback lines show only ids, counts, `seedChars=`, `privateSeedCount=`, `dropped rawLine=N reason=... chars=`. The only log line with the DM ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: let 2–3 group scenes play after a DM to Jake, with diagnostics on.
  - Claude watches for: `latest.log` banter-seed, scene-output and dropped-line lines. They should show counts, lengths, reasons and `correlationId=`, with no dialogue text at INFO.
  - Pass when: no DM fact or scene line text appears in the log at INFO.

## Phase 6x — Singleplayer auto-spawn waits for the host (1.1.226)

1.1.226 fixes "server is full" in singleplayer: saved bots used to auto-spawn before the host joined and could take all 8 slots. Now they spawn after the host is in, and one slot always stays free.

Grep: `Auto-spawn deferred until the first real player joins`, `Auto-spawn skipped for`, `Auto-spawn skipped`.

- [x] **Auto-spawn after the host joins (1.1.226)**
  - Sandbox 2026-10-18: PASS: S2 reopening Sweep Souls with Jake and Bob saved: `[16:57:35] Auto-spawn deferred until the first real player joins (integrated server).`, then `RotiWokeman[local] logged in` (16:57:38) and only after it `Spawned new ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: in a singleplayer world with 2–3 saved bots, quit to title, then reopen the world.
  - Claude watches for: `Auto-spawn deferred until the first real player joins (integrated server).` at world load, then the bots' spawn lines only after Bradley's own join line.
  - Pass when: Bradley gets in, and the saved bots appear a moment later where they were.
- [ ] **Too many saved bots never lock the host out (1.1.226)**
  - Sandbox 2026-10-18: PARTIAL: S3 7 saved bots (Jake, Ash, Birch, Cedar, Dale, Elm, Fir), quit and reopen: host joined with no "server is full", `Auto-spawn deferred until the first real player joins`, then all 7 spawned at 18:51:50 (8/8). No ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: in a throwaway singleplayer world, `/bot spawn` 7 bots (8 players with Bradley), quit to title, reopen the world.
  - Claude watches for: one `Auto-spawn skipped for <bot>: 7/8 players and 1 slot(s) kept free` line, and `Auto-spawn skipped 1 companion(s); world allows 8 players`.
  - Pass when: Bradley joins without "server is full", 6 bots auto-spawn, and chat shows "1 companion was not auto-spawned … Use /bot spawn to bring one in." `/bot spawn` of the 7th still works.

## Phase 6y — Commands-off first run (1.1.227)

1.1.227 makes Frens usable in a Survival world with Allow Commands OFF. Use a NEW world, commands OFF, no Carpet (the `frens-fresh` instance has no Carpet).

Grep: `Unknown or incomplete command`, `No recipient resolved`, `Stopped by the player`, `AI chat is off`, `is pending`.

- [ ] **Spawn and command with commands off (1.1.227)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): FAIL: S10 `Sweep NoCmd` (allowCommands 0, Soul Chat off): `-` opened the roster ("No companions in this world yet", Other worlds (8)), Spawn Selected: "Spawned new bot Finn!" (22:50:10, the click caught Finn instead of Jake), no ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: in a new commands-off world press `-`, tick Jake, Spawn Selected. Then the follow key, then `\` to stop.
  - Claude watches for: no `Unknown or incomplete command` anywhere; `Spawned new bot Jake!`; follow ON; a single "Jake has stopped." line.
  - Pass when: Jake spawns, follows and stops with no red error, and stop shows one chat line.
- [x] **World Mode chooser for the host (1.1.227)** — closed 2026-10-18 by triage: SUPERSEDED, Questing is behind an off-by-default server flag since 1.1.235, so a new world shows no chooser; covered by 6ag Questing hidden (1.1.235)
  - Bradley does: create a new commands-off world.
  - Claude watches for: the World Mode selection screen opening on join (F2 screenshot).
  - Pass when: "Choose Frens World Mode" opens without enabling cheats.
- [x] **Roster and hints (1.1.227)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S10 with Finn out, Guide > Admin > Bot Controls > Spawn Bots...: "All your companions are already here", Finn not offered. "Finn, hello" with AI chat off: one notice "AI chat is off. To let companions talk back, open the Guide ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with Jake out, open the roster (Guide → Admin → Bot Controls → Spawn Bots), then say "Jake, hello" in chat with AI chat off.
  - Claude watches for: roster without Jake (or "All your companions are already here"); one notice "AI chat is off. To let companions talk back, open the Guide (]) → AI setup."
  - Pass when: present bots aren't offered, the notice shows once, and a brand-new bot's first greeting is not "welcome back".

## Phase 6z — Narrow-screen layouts (1.1.228)

1.1.228 makes every Frens screen fit at the smallest Minecraft GUI size. Test three sizes: set Video Settings → GUI Scale and resize the window. 427x240 = 854x480 window at scale 2. 342x328 = Bradley's 1708x1642 window at Auto (scale 5). 320x240 = 640x480 window at scale 2. Take an F2 screenshot of each screen at each size.

- [x] **Wide unchanged (1.1.228)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S9 at 427x240 (window 1708x960, GUI scale 4): Guide topics and detail side by side, bot inventory with its Actions/Spells/Admin overlay and equal tabs, "Looking at Jake. Right-click to open menu • Press Right Bracket (]) for ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: at 427x240 (or wider), open the Guide, the bot inventory (right-click a bot) with its Admin/topics overlay, the roster, and look at a bot.
  - Claude watches for: screenshots matching 1.1.227 (Guide topics/detail side by side, equal tabs, "Looking at X. Right-click to open menu • Press Right Bracket (]) for Guide").
  - Pass when: nothing looks different from 1.1.227.
- [ ] **Narrow fits (1.1.228)**
  - Bradley does: at 342x328 and 320x240, open the Guide, bot inventory + overlay, roster, Bot Controls popups (Hobbies, Piper, Model manager), and say something long near a bot.
  - Claude watches for: no text past a panel edge, inventory fully visible (no "ealth"/"unger"), overlay tabs not colliding, overhead line wrapped to at most 3 lines.
  - Pass when: every screen fits and all buttons are reachable.
- [ ] **Scaled inventory clicks (1.1.228)**
  - Bradley does: at 320x240 in the bot inventory: click, shift-click, drag-split items on both sides incl. armour and the last column, hover for tooltips, drop an item outside the panels, use the overlay tabs and scrollbar.
  - Claude watches for: no wrong-slot moves in `[bot-inv-` lines.
  - Pass when: every click hits the slot under the cursor and tooltips sit at the cursor.

## Phase 6aa — Fresh-world woodcut, follow after tasks, tool wear, drop pickups (1.1.229)

Best on a brand-new Survival world (the `frens-fresh` instance or a new world on Nirn) with a bot that has an empty inventory.

- [x] **Woodcut from nothing (1.1.229)**
  - Sandbox 2026-10-18: PASS: S3 Jake empty in the forest, `/bot skill woodcut`: `Woodcut axe bootstrap: Jake has no axe, punching logs bare-handed`, chat "No axe yet, so I'll punch a tree first.", first punch about 4 s later, `has enough wood ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: on a new world, give a fresh bot no items, stand by a tree near spawn and order woodcut.
  - Claude watches for: `Woodcut axe bootstrap: <bot> has no axe, punching logs bare-handed`, then `has enough wood (logs=… planks=… sticks=…), crafting an axe`, then `crafted an axe, back to normal woodcut`. No `protection=admin-zone` or `protection=base-radius` rejects for trees near the Spawn base. No `A* pathfinding timed out` with the same from and to.
  - Pass when: the bot says "No axe yet, so I'll punch a tree first.", punches a few logs, places a table, makes a wooden axe and keeps chopping. First chop within ~10 s of the order with trees adjacent.
- [ ] **Your base's trees still protected (1.1.229)**
  - Bradley does: on Nirn, order woodcut while standing inside your own base radius with trees around.
  - Claude watches for: `protection=base-radius` rejects for those trees, same as 1.1.228.
  - Pass when: the bot does not fell trees inside your base.
- [x] **Follow comes back after a task, no "Busy" (1.1.229)**
  - Sandbox 2026-10-18: PASS: S3 Jake following, `/bot skill woodcut 4 Jake` ran four trees and ended: `[18:34:53] [FollowAssert] task-resume bot=Jake mode=follow ... resumed=true result='Now following RotiWokeman.'`, no "Busy with another task" ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: with a bot following, order a short skill (woodcut one tree, or any task) and let it finish without stopping it.
  - Claude watches for: `[FollowAssert] task-resume bot=… mode=follow … resumed=true`. No "Busy with another task right now." and no "Stopping follow command." in chat. If the task ended underground, no post-task trip home while it follows.
  - Pass when: the bot follows you again after the task, with no wrong lines.
- [x] **No axe wear on dirt (1.1.229)**
  - Sandbox 2026-10-18: PASS: S3 two woodcut runs with an axe and dirt: every dirt pillar and bridge block came down `with minecraft:dirt` in hand (300-750 ms), never the axe. Note: plank bridge blocks were also mined with planks in hand, some at ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: give a bot a copper or stone axe and a hotbar with dirt, let it woodcut a tall tree that needs a dirt pillar, and watch the pillar teardown.
  - Claude watches for: `MiningTool` lines on dirt with `bare-hands` (or a shovel), never an axe. Plank bridge retracts using the axe (well under 3 s per plank).
  - Pass when: the axe's durability doesn't drop during teardown.
- [x] **No crevice suffocation on pickups (1.1.229)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 three woodcuts on snowy hills with drops in gaps (19:40-19:58): `Drop sweep nudge skipped near -55, 84, -52: no 2-high clearance` (19:40:45) and again 19:58:24, unreachable drops left (`Drop sweep failed to approach ...`), and ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: woodcut near uneven ground where drops fall into 1-high gaps.
  - Claude watches for: `Drop sweep nudge skipped near …: no 2-high clearance`. No `nudged away from wall … to escape collision clip`.
  - Pass when: the bot never takes suffocation damage while picking up and leaves items it can't safely reach.
- [x] **Unreachable drop given up fast (1.1.229)**
  - Sandbox 2026-10-18: PASS: S4 a sapling dropped on a persistent leaf block 4 above idle Jake (idle hobbies on for 2 of the 3 minutes): no `[IdleSweep]` target at it, so no bot stared at it. The abandon path was not exercised. Replay ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: while bots idle, leave a sapling or item up in leaves 3+ blocks above them (or wait for one to land there).
  - Claude watches for: either no `[IdleSweep]` target at it, or `[IdleSweep] <bot> abandoning unreachable drop at … (dy=3+) … no progress for 3s`. If the item falls, the bot follows it to the new spot.
  - Pass when: no bot stares at an unreachable item for more than a few seconds.

## Phase 6ab — Woodcut protection nudge (1.1.230)

- [ ] **Bot says why it skips your base trees (1.1.230)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S11 `/bot base set TreeBase` with three oaks around, `/bot skill woodcut Jake`: `protected-only scans triggered staged relocation`, `Woodcut protection nudge: Jake reasons={admin-zone=4}`, "The trees around here are in a ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: set a base (or stand in your Nirn base) with trees around and no other trees close, order woodcut.
  - Claude watches for: `Woodcut: protected-only scans triggered staged relocation` followed by `Woodcut protection nudge: <bot> reasons={base-radius=…}`.
  - Pass when: the bot says "The trees around here are inside your base, so I'm leaving them alone and heading out to find others." once, walks out, and a second woodcut (or a second bot) does not repeat it until the world is reopened.

## Phase 6ac — Non-op owners use the UI (1.1.231)

Run the first item single-player BEFORE anything else in this session: every button (ops and host too) now goes through the new payload, so a dispatch bug breaks all of them. The rest need a second account or a LAN guest who is not an operator and owns a bot.

- [x] **Buttons still work for the host (1.1.231)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S9 single-player host, Jake's inventory overlay: Patrol (22:35:28 "Patrolling this area"), Guard (22:35:45), Follow (22:35:59 "Jake is following RotiWokeman."), Stop (22:36:00 "Jake has stopped."), Idle Hobbies toggle (22:36:45 ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: single-player, open a bot's inventory, press Stop, Follow, Guard, a Skills toggle, a hobby toggle, then a key (come / sleep) and a Crafting History craft x1 and x2.
  - Claude watches for: no `[bot-access] refused granted dispatch` and no `[bot-access] denied` lines.
  - Pass when: each button does what it did in 1.1.230, the craft runs on the bot whose screen is open, and no "That button could not run. Check the server log." appears.
- [ ] **A non-op owner commands their own bot (1.1.231)**
  - Bradley does: LAN guest (or second account on a server where it is not op) recruits or is given a bot, then uses the inventory screen buttons, Follow, Guard/Patrol, a skill (woodcut, mining overlay), and the keybinds.
  - Claude watches for: the skill/follow log lines for that bot, no `[bot-access] denied`.
  - Pass when: every button works for the guest's own bot. Companion come/summon/home are still refused for non-op owners (known, deferred).
- [ ] **A non-op cannot command someone else's bot (1.1.231)**
  - Bradley does: guest opens or targets the host's bot (aim and press a key, or pick it in a screen if visible) and presses Stop / Follow.
  - Claude watches for: `[bot-access] denied` naming the guest and the host's bot.
  - Pass when: the host's bot ignores it and the guest gets a short refusal line.
- [ ] **Fortify respects wall ownership (1.1.231)**
  - Bradley does: guest uses Base Manager / Construction fortify Patch or Resume on their own wall, then near the host's wall. Also press fortify List as the guest.
  - Pass when: own wall works, the host's wall gives "You cannot work on that wall.", and List is refused for the guest (ops/host only).
- [ ] **Feed [Yes]/[No] and resume prompt (1.1.231)**
  - Bradley does: as the guest, trigger a precious-food offer and click [Yes] (then repeat and click [No]). Let the guest's bot die mid-skill and answer yes to resume.
  - Pass when: both clicks work (they now run `/frens feed confirm|cancel`), the host clicking the guest's [Yes] does nothing, and the resume runs for the guest's bot.
- [ ] **Hunt target pick (1.1.231)**
  - Bradley does: as the guest, open Huntables and pick a target mob for their bot.
  - Pass when: the guest's bot starts hunting that mob.

## Phase 6ad — Host sweep (1.1.232)

Run after Phase 6ac's first item. Items 1 and 6-7 are single-player; 2-5 need a dedicated server op client or a LAN guest.

- [x] **Host with cheats off passes admin gates (1.1.232)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: single-player world with cheats OFF. Resize the Spawn base in the Base screen, use the zone wand and the zone screen, toggle lock mode on a door, run `/bot soul status` and `/bot learn status`, change a bot skin, right-click a bot to open it.
  - Pass when: each works with no "operator" refusal. The Guide now shows the admin topics (world mode reset, modes).
- [ ] **Ops see the Admin tab on a server (1.1.232)**
  - Sandbox 2026-10-17: NOT TESTABLE in the sandbox (needs a server op or LAN guest), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: as an op on a dedicated server or as an op LAN guest, open a bot's inventory.
  - Pass when: the Admin tab is visible and works. A non-op guest does NOT see it.
- [ ] **Companion owner after a recruitment reset (1.1.232)**
  - Sandbox 2026-10-17: NOT TESTABLE in the sandbox (needs a guest and a Questing world), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: in recruitment mode, as the guest who owns the recruited companion, after a recruitment reset, press Companion come and home.
  - Pass when: both work. A different non-op player is still refused.
- [ ] **Wall grantees fortify (1.1.232)**
  - Sandbox 2026-10-17: NOT TESTABLE in the sandbox (needs a guest), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: host grants the guest access to the host's wall, then the guest runs fortify Patch and Resume on it, then Expand, then tries to remove or rename it in the Base screen.
  - Pass when: Patch and Resume run, Expand gives "You cannot work on that wall.", remove and rename are refused. On a wall with no owner the guest gets "That wall has no owner. Claim it in the Base screen first."
- [ ] **Skins are owner-checked (1.1.232)**
  - Sandbox 2026-10-17: NOT TESTABLE in the sandbox (needs a guest), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: as a LAN guest, pick a preset skin for the guest's own bot, then try the host's bot. (Bradley's ruling 2026-10-18 (triage): the "allow everyone" toggle was removed, so that half is dropped.)
  - Pass when: the guest's own bot changes skin, and the host's bot answers "You can only change the skin of your own bot."
- [x] **Throttle notice (1.1.232)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: click one bot button about 30 times fast.
  - Pass when: one "Slow down, that click was ignored." line, not a flood.
- [x] **No bot-slot mismatch warnings (1.1.232)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Claude watches for: `[bot-access] bot-slot mismatch` across the whole session (every button in 6ac and 6ad).
  - Pass when: none. Any hit names the action; report it so 1.1.233 can switch the check to refuse.

## Phase 6ae — Cleanup: unowned bots, skin toggle, tool picks (1.1.233)

- [ ] **Unowned bots refuse Set Home / Go To for non-admins (1.1.233)**
  - Sandbox 2026-10-17: NOT TESTABLE in the sandbox (needs a guest), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: as a LAN guest, pick an unowned bot (console-spawned, no owner) in the Base screen and press Set Home, then Go To. Repeat as the host.
  - Claude watches for: `[bot-access] denied` with action `base_set_home` / `base_go_to` for the guest only.
  - Pass when: the guest is refused, the host and the bot's owner succeed.
- [ ] **Skin toggle gone (1.1.233)**
  - Sandbox 2026-10-17: PARTIAL: toggle gone from Admin tab and Player Permissions; guest half not testable, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: open the Admin tab and the per-player admin settings.
  - Pass when: no "allow everyone to change skins" toggle anywhere, and a guest reskinning the host's bot still gets "You can only change the skin of your own bot." (also when the host's bot is offline).
- [x] **Leaves drop saplings, teardown uses hands (1.1.233)**
  - Sandbox 2026-10-18: PASS: S3 Jake with stone pickaxe, iron axe, shears and 32 dirt woodcut two trees with a 2-step dirt pillar: the dirt teardown was mined `with minecraft:dirt` in hand, stone pickaxe `damage 0` before and after (shears also ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-17: FAIL: shears still mine leaves during felling and pillarUp (bug A); stone scaffold pass, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: give a bot shears and a pickaxe in the hotbar, run woodcut on a tree that needs scaffold, then watch the cleanup and a fortify/shelter scaffold teardown.
  - Bradley's ruling 2026-10-18 (triage): open only for the part never tested: dirt and plank scaffold comes down without the pickaxe losing durability.
  - Pass when: the pickaxe's durability is the same before and after a dirt or plank scaffold teardown.
- [x] **Crafting table line (1.1.233)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: order a craft that needs a crafting table when the bot has none.
  - Pass when: chat says "Crafted 1 crafting table(s)." with no "run /bot place" hint, and the bot places it.

## Phase 6af — Playthrough fixes: first axe, survival breaks, per-save worlds (1.1.234)

Run FIRST in a session: open **Nirn** first after deploying (item 1), then a brand-new commands-off Survival world for the rest.

- [x] **Nirn keeps its zones and locked doors (1.1.234)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: open Nirn before any other world. Look at a protected zone and a locked door near home.
  - Claude watches for: the zone and lock load lines at SERVER_STARTED with Nirn's counts (4 zones incl. home/deep dark 1/tower dungeon jungle, 3 locked blocks), adoption outcome ADOPT_LEGACY.
  - Pass when: Nirn's zones and locks are all there. A `claimed_by_world.txt` appears in `bot_zones/minecraft_overworld__41d0e9b1/`.
- [x] **A new world starts clean (1.1.234)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: create a new Survival world (commands off), named the same as an old save if possible.
  - Claude watches for: no Nirn zones/locks loaded, its own Spawn base registered at the real spawn, no stale `base:*` auto-zone.
  - Pass when: the World Mode chooser opens before the first companion.
- [x] **First axe on the ground (1.1.234)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: spawn a bot with an empty inventory and start woodcut next to trees.
  - Claude watches for: `Woodcut axe bootstrap`, a crafting table placed on the ground, a wooden_axe crafted, no `Pillar stock: crafted … planks` before the axe, a few dirt-family blocks gathered.
  - Pass when: the bot never pillars or crafts on a pillar before it has an axe, and at most one table line shows in chat if placement fails.
- [x] **Scaffold breaks at survival speed (1.1.234)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: watch the bot take down a plank or dirt pillar, then stop it mid-teardown and run woodcut cleanup.
  - Claude watches for: `Mining … (delta=…, ticks=…)` lines for each teardown block (planks ~60 ticks by hand), no `surface-pillar: remove` burst under a second.
  - Pass when: each block takes visible time, and after the stop the cleanup still finds and removes the leftover scaffold.
- [x] **No canopy pillar at cleanup start (1.1.234)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Pass when: starting woodcut cleanup under leaves logs no `ensureAtSurface: … pillar recovery`.
- [x] **Cleanup finishes and says one sentence (1.1.234)** — closed 2026-10-18 by triage: SUPERSEDED, bug B fixed in 1.1.266 and 1.1.267 (c2bc8ad0, 16 s in the sandbox); covered by 6bg Empty cleanup ends fast (B2)
  - Sandbox 2026-10-17: FAIL: right sentence, 1 s on bare ground but 127 s in forest (bug B), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Pass when: with nothing to clean, woodcut cleanup ends within a few seconds with "Nothing left to clean up here." (no `minedLogs=` stats in chat).
- [x] **Drop sweep, return and craft feedback (1.1.234)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S11 on 1.1.271, four piles summoned around Jake (5 logs, 3 apples, 7 sticks, 2 saplings = 17 items), `/bot skill drop_sweep Jake`: "Picked up 17 items." and his bag holds exactly those 17; no fast-travel error, no return trip. ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-18: FAIL: S3 drops spread around Jake, `/bot skill drop_sweep Jake`: chat "Picked up 27 items.", but the skill's own summary says `collected: 20` and Jake's bag (cleared before) holds 21 items (13 saplings, 5 logs, 2 apples, 1 ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-17: FAIL: no-wood craft answers with two lines (bug C); sweep counts stacks, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: drop a few items, run a drop sweep. Then `/bot craft` an axe with no wood anywhere.
  - Bradley's ruling 2026-10-18 (triage): the craft half passed (bug C fixed in 1.1.266). Open only for the sweep count.
  - Pass when: the sweep says "Picked up N items." with the right N (items, not stacks), no fast-travel error and no return trip after it.

## Phase 6ag — Sandbox fixes and Questing flag (1.1.235)

- [x] **Table placement never blocks itself (1.1.235)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does (or Claude in the sandbox): fresh bot with no axe, woodcut next to a small tree on flat ground.
  - Claude watches for: `Utility placement summary [crafting_table]` with `outcome=ready`, then `Placed crafting table`, no `bot-intersects-target`.
  - Pass when: the bot places its table and crafts a wooden axe on the ground.
- [x] **Bootstrap gather outside the Spawn base (1.1.235)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Pass when: woodcut started at spawn logs skipped protected cells (no "Cannot mine: protected claim") and a regather at the tree's stand.
- [x] **Plain tree lines (1.1.235)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Pass when: each tree ends with one line like "Got 3 logs from that tree." (no "Woodcut target cleared (…)").
- [ ] **Questing hidden (1.1.235)**
  - Sandbox 2026-10-17: PARTIAL: new-world line, no chooser, recruit refusal pass; existing Questing world not testable, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: create a new world with default config.
  - Pass when: no World Mode chooser, one line "Frens is in Admin mode: open the roster with [-] to bring in a companion.", the roster opens. `/bot recruit enable` answers "Questing mode is turned off on this server." An existing Questing world still recruits.
- [x] **Craft names (1.1.235)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Pass when: `/bot craft wooden_axe` answers "Crafted 1 Wooden Axe."

## Phase 6ah — Shelter-hole climb-out and per-world roster (1.1.236)

- [x] **Climb out of a shallow open-sky hole by day (1.1.236)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does (or Claude in the sandbox): load the sandbox "New World" by day, where Jake sits 3 down at 41,62,25.
  - Claude watches for: `linger: allow — shallow open-sky hole by day, climbing out (depth=3 scaffold=N)`, then `pillar recovery`, and no more `no tools, deep underground`.
  - Pass when: Jake is standing on the surface within a minute.
- [x] **Night bunker registers and holds (1.1.236)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: let a bot with no tools face dusk away from base.
  - Claude watches for: the dig-down cap line with `covers=true`, no `rejected reason=no_overhead_cover`, then `linger: suppressed — inside own shelter` or `nighttime, waiting it out` (no pillar through the cap).
  - Pass when: the bot stays in its bunker until morning, then leaves.
- [x] **Roster shows this world only (1.1.236)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: in a world with one companion, open the roster.
  - Claude watches for: no `roster world scope not ready` line (or a later `arrived late, refreshing`).
  - Pass when: only this world's companion is listed. "Other worlds (N)" lists the rest. A new world says "No companions in this world yet." Bringing one in spawns it here with empty pockets. Typing an other-world name greys Create with the tooltip.

## Phase 6ai — Spawn-base trees protected, admin toggle (1.1.238)

- [x] **Fresh world: bot walks out of the Spawn base (1.1.238)** — closed 2026-10-18 by triage: SUPERSEDED, bug D fixed in 1.1.266 (6bcd088e, af1b322e); covered by 6bf Relocation reaches distant trees (D)
  - Sandbox 2026-10-17: FAIL: spawn line once, but relocation walks hit their deadline and it got no logs (bug D), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does (or Claude in the sandbox): new default world, a companion with no axe, `/bot skill woodcut <name>` at spawn.
  - Claude watches for: `Woodcut detect` with `protectedFail>0`, then the chat line "The trees around spawn are protected, so I'm heading out to find others.", relocation, logs mined outside the zone. `logscan.sh` shows no repeated `Cannot mine: protected claim` (at most one per block).
  - Pass when: the bot gets logs from a tree outside the Spawn base and says the spawn line once.
- [ ] **Admin toggle lets bots cut Spawn trees (1.1.238)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PARTIAL: S12 Admin > World Settings > "Cut trees in the Spawn base: On" ("Bots may now cut trees in the Spawn base.", 08:52:43), Jake at 4 73 4 in the Spawn base, `/bot skill collect_dirt Jake`: "This is a pro ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-17: FAIL: logs are cut but every one reports a protected-claim refusal afterwards (bug E), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: Admin, World Settings, "Cut trees in the Spawn base: On", then woodcut at spawn.
  - Bradley's ruling 2026-10-18 (triage): felling passed in later builds (1.1.266 bug E, 1.1.268 own scaffold). Open only for the two parts never rechecked.
  - Pass when: dirt in the Spawn base is still refused, and a non-admin who tries the toggle gets a refusal in chat.
- [ ] **Own base over spawn still works (1.1.238)**
  - Sandbox 2026-10-17: PARTIAL: host bot mines inside its own base over spawn; torches not placeable by command, Nirn half not testable, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: host makes a base overlapping spawn, has his bot plant or place torches there.
  - Pass when: no `protected-zone` refusal for the host's bot inside his base. Nirn's home base trees stay protected with the toggle on.

## Phase 6aj — No bare-hand stone, unreachable trees skipped, stuck bots back off, hunger in chat (1.1.239)

- [x] **Approach never hand-mines stone (1.1.239)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does (or Claude in the sandbox): woodcut with a companion that has no pickaxe in hilly or cave-cut forest.
  - Claude watches for: `movement obstruction mine refused [...]: … too slow by hand (N ground ticks > 100, held=…)` instead of `Mining … stone … with bare-hands`. No burst of identical `Mining complete` lines on one tick.
  - Pass when: no stone or deepslate is mined bare-handed by movement, and dirt, sand and snow still clear.
- [x] **Woodcut gives up on trees it can't reach from a pocket (1.1.239)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: woodcut with a no-axe, no-blocks bot standing in a roofed hole or cave (the sandbox "New World (1)" pocket at 20,62,172 works).
  - Claude watches for: `Woodcut approach: abandoning dry reposition … after 4 failed stands on any side`, feasibility lines with the streak, then chat "I'm stuck down here and can't reach any trees."
  - Pass when: the run ends within about 2 targets instead of 10 minutes of retries, and the task slot frees (underground survival starts).
- [x] **Stuck bot with nothing to build with backs off (1.1.239)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: leave a no-block bot in a covered stone pocket by day.
  - Claude watches for: `Vertical ascent step: no climb (landed at Y=…, nothing placed)` (never a false "climbed"), `Stuck-escape backoff: … pausing retries for 30 s` then 60 s, 120 s …, at most 10 min. After `/bot stop`: `holding still after a stop command until the next command` and no more staging walks.
  - Pass when: chat says "I'm stuck in a hole with nothing to build with." once (also after you respawn from a death), and the log has no once-a-second loop.
- [x] **Hunger said in chat, pause on time (1.1.239)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: woodcut with a companion that has no food, far from you (more than 32 blocks), until food drops.
  - Claude watches for: chat "I'm getting hungry and I have no food." once at food 10 or below, then at food 5 or below "I'm starving and out of food, so I stopped cutting. Feed me, then /bot resume." within one approach step.
  - Pass when: both lines reach chat at distance and the pause comes within seconds of food 5, not minutes.
- [x] **Roster empty state and voice stubs (1.1.239)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: open the roster in a world with no companions here but some in other worlds.
  - Pass when: the title and the list placeholder say different things. Claude: `grep -c "Missing sound for event"` on a fresh launch is 0.

## Phase 6ak — Multi-step navigation (1.1.240)

- [ ] **Bots walk around, break soft blocks or place a dirt step (1.1.240)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: 1.1.271: S5 woodcut `terrain move: BREAK ... result: BREAK ok=true mined=true` (19:55:55) and `WALK_AROUND ok=true ... displaced=true` (19:55:57); S11 fortify 13 WALK_AROUND, 10 ok, 3 not. No PLACE step seen; no `not a standing ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-18 (1.1.267): PARTIAL: BREAK ok=true and WALK_AROUND moves seen, both WALK_AROUND ok=false, no PLACE, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Sandbox 2026-10-17: PARTIAL: walk-arounds ok, no timeouts, no BREAK or PLACE success seen, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does (or Claude in the sandbox): woodcut with a no-tools companion in rough terrain (ravines, sand banks, stepped hills), outside the Spawn base.
  - Claude watches for: `terrain move: WALK_AROUND|BREAK|PLACE cost=…` then `terrain move result: … ok=true … displaced=true`, no `Refusing path … not a standing cell`, no `A* pathfinding timed out`. (Bradley's ruling 2026-10-18 (triage): the Baritone p90 part is dropped, unified is the default since 1.1.261.)
  - Pass when: the bot reaches trees it used to slam against, and any dirt step it leaves is a single plain dirt block.
- [x] **Wading through shallow water (1.1.240)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: a target across a 1-deep stream or pond edge.
  - Claude watches for: `Path wades N of M cells`.
  - Pass when: the bot wades across instead of walking around a long way, and never dives into 2-deep water.
- [x] **Baritone default migrated once (1.1.240)**
  - Sandbox 2026-10-18 (1.1.267): SUPERSEDED: replaced by 6ba-1 (passed), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Sandbox 2026-10-17: SUPERSEDED by 1.1.261 (Phase 6ba); classic still survives a restart, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Claude watches for: `1.1.240 config migration: pathfinder switched to baritone` on the first Nirn launch only. `/bot config pathfinder` says baritone.
  - Pass when: the line appears once and a later `classic` choice survives a restart.
- [x] **Escape after a fight underground (1.1.240)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does: let a companion die or abort to a mob while in a cave or pit with no task.
  - Claude watches for: `cleared a stale abort latch … so escape can run`, never `holding still after a stop command` unless you typed /bot stop.
  - Pass when: the bot climbs out on its own after a fight, and stays put after a real /bot stop.

## Phase 6al — Tree claims, creeper line, walk outcomes (1.1.241)

- [x] **Several bots spread across one forest (1.1.241)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Sandbox 2026-10-17: FAIL: claims keyed on the base block, a second bot takes the same tree after its bottom log is cut (bug G), see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Bradley does (or Claude in the sandbox): `/bot skill woodcut` on three companions in the same forest.
  - Claude watches for: `Woodcut: tree <base> is claimed by <bot>, picking another`, no single-file queue (`walk blocked near` on each other), at most one `sharing it (no free tree after waiting)` near the end.
  - Pass when: the bots work different trees and none cuts logs from a tree another is felling.
- [x] **Creeper backoff line (1.1.241)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Claude watches for: chat "A creeper! I dropped what I was doing to get clear." once per bot, never `creeper-fuse`.
  - Pass when: the line appears once and the bot is not auto-resumed (by design).
- [x] **Quiet torch-hold log (1.1.241)**
  - Sandbox 2026-10-17: PASS, see `FIELD_6AD_6AL_SANDBOX_2026-10-17.md`.
  - Claude: `frens_log_report.py` spam section has no `[torch-hold]` pattern above 60/min during woodcut.
  - Pass when: torch-hold lines appear only when the gate or verdict changes.

## Phase 6am — Unified pathfinder opt-in, audit bug fixes, per-bot dig gate (1.1.242)

- [x] **Unified finder A/B, longer run (1.1.242)**
  - Sandbox 2026-10-18 (1.1.267): SUPERSEDED: the 2026-10-17 swapped A/B (`0ce0441d`) and the 1.1.261 default, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does (or Claude in the sandbox): daylight, bots outside the Spawn base with axes, terrain with water, cliffs and soft walls. `/bot config pathcompare on`, then `/bot config pathfinder unified <bot>` on half the bots, woodcut on all.
  - Claude watches for: `frens_log_report.py` section 4: reached rate per primary finder (baseline 61.5%), `unified-only reachable`, `planned move ASCEND|ASCEND_PLACE|SWIM|FALL … ok=true`, no `placed-not-dirt`, no breaks of planks, logs, fences or anything near a build.
  - Pass when: unified reaches its goal at least as often as Baritone over 30+ joined walks and crosses terrain the others refuse. Then 1.1.243 makes it the default and removes the old finders.
- [ ] **Audit fixes in play (1.1.242)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PARTIAL: S13 fishing PASS: hobby `fish` by the pool with a furnace (19 76 -101) and chest (19 76 -99) beside it, bites from 08:59:56, at a full bag `Stored 164 items in chest 19, 76, -99` (2 cod, salmon, puffe ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-18 (1.1.267): FAIL: hobby fishing says "No 'fish' to cook" (finding 8), honey_collect harvests nothing (finding 9), wool shears but deposit not reached, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: let a companion fish with a furnace and chest nearby, run the honey hobby by a smoked hive, wool deposit to a chest.
  - Claude watches for: no "That furnace is unavailable." while a furnace is in range, fish deposited to the home chest, honey harvested, wool deposited.
  - Pass when: all three work on the first try.
- [x] **Only the bot doing the task digs (1.1.242)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: one bot woodcutting, another following you through a narrow cave.
  - Claude watches for: no `movement obstruction mine` or `terrain move: BREAK` for the following bot.
  - Pass when: the follower never mines because the other bot is busy.

## Phase 6an — Dead-code removal 2: RL and training gone, debug gate, command paths (1.1.244)

- [x] **Saved training bot still spawns (1.1.244)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: open a save whose Bot Controls list a bot with spawn mode Training (or `/bot spawn <name> training`).
  - Claude watches for: `maps to mode 'admin'`, no `Invalid spawn mode!`.
  - Pass when: the bot spawns and behaves as an admin bot. If the world was still waiting for survival recruitment, note whether it switched to recruited (expected, ruling 2).
- [x] **Dev commands only with debug on (1.1.244)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: type `/bot test` and look at tab completion, then `/bot debug on`, then again, then `/bot debug off`.
  - Pass when: `testgear` and `test_chat_message` appear only while debug is on, and `/bot emote list` and `/bot wizard_tome` work.
- [x] **Respawn point stays put near lava or cliffs (1.1.244)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: let a companion idle next to a cliff edge or lava for a minute, then let it die elsewhere.
  - Pass when: it respawns at its commanded spawn, not at the cliff edge.

## Phase 6ao — Dead-code removal 4: nav_mode, legacy config and mount data (1.1.246)

- [ ] **Skins and saved mounts survive the update (1.1.246)**
  - Sandbox 2026-10-18 (1.1.267): NEEDS BRADLEY (Nirn save data), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: open Nirn, spawn the usual companions, check their skins, and rejoin with a saved horse or mount.
  - Claude watches for: no `random preset` skin line for an existing bot, mount restore lines as before, a one-time `old-format mount entry skipped` only if the file had one.
  - Bradley's ruling 2026-10-18 (triage): Nirn already ran the 1.1.246 update (logs from 1.1.255 on), so this closes after one look: open Nirn, check the companions' skins, rejoin with a saved mount.
  - Pass when: every companion keeps its skin and the saved mount comes back.
- [x] **Fast travel Guide topic reads right (1.1.246)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: open the Guide, search "fast travel", read "Fast Travel & Navigation Tiers".
  - Pass when: it matches what happens (lodestone compass in the bot's hands is full speed, a Map and Compass is slower, no walk or fast-travel setting).

## Phase 6ap — Combat callouts, base owner command, leash target (1.1.248)

- [x] **Multi-mob and friendly-fire lines play (1.1.248)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: fight 3 or more hostiles next to a companion at night. Separately, stand in front of a companion while it fights (or shoots) so it hits you.
  - Claude watches for: `[combat-callout] <bot> faces <n> hostiles, multi-mob line`, `combat ended after up to <n> hostiles, post-combat multi line`, `hit player <you> for <x> damage, apology line`.
  - Pass when: you hear a "there are lots of them" style line mid-fight and an apology after a hit, each not more than once per cooldown.
- [ ] **/bot base owner (1.1.248)**
  - Sandbox 2026-10-18 (1.1.267): PARTIAL: owner command and log line pass, Bases screen not read, non-op half needs a second player, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: in Nirn, `/bot base list`, then `/bot base owner "<an old base>" <you>` and open the Bases screen.
  - Claude watches for: `[base-owner] ... set owner of base`.
  - Pass when: the Bases screen shows you as owner, and a non-op player gets "Only operators or the host can reassign base ownership."
- [x] **Tied animals stay tied (1.1.248)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: have a companion lead a horse (dismount with the leash key, no fence near), then use the leash action near a fence so it ties the horse. Leave and rejoin.
  - Claude watches for: `[leash-target] <bot> cleared leash target ... after tying to a fence` and `saved mount ... no longer held by bot (TIED_TO_FENCE)`.
  - Pass when: after the rejoin the horse is still on the fence and the bot does not pull a lead to grab it.

## Phase 6aq — Wall-off night shelter (1.1.249)

- [x] **A companion walls itself into an overhang at night and walks out at dawn (1.1.249)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: away from bases and villages, give a companion 8 dirt or cobblestone and leave it idle near a natural overhang or a 1 to 2 sided nook at dusk, then come back at dawn.
  - Claude watches for: `wall-off: scan`, `walking to`, `arrival arrived`, `sealed at`, `wall-off shelter active`, then at dawn `wall-off teardown: removed N/N` and `broke free from wall-off shelter via <side>`.
  - Pass when: the bot spends the night sealed in, at dawn the placed blocks are gone, the overhang is untouched and there is no hole. No overhang near: it falls back to cliff-dig or dig-down as before.
- [x] **No wall-off with only valuables, near a village, or in a protected zone (1.1.249)**
  - Sandbox 2026-10-18 (1.1.267): PASS (valuables case; village and zone cases not set up), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: same setup with only diamond blocks in the inventory, or inside a mapped village, or inside a protected zone.
  - Pass when: `candidates=0` or `blocks=0` and it falls through to the old tactics.

## Phase 6ar — Legacy LLM chat retired, part 1 (1.1.250)

- [x] **Bot Controls keep their settings (1.1.250)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: before updating, note the global toggles in Bot Controls (Recruitment, Text, Voice, Soul Chat, Soul Voice, Banter, Active, Local) and one bot's per-bot toggles and Voiced Dialogue. Update, open `/configMan` (it now opens Bot Controls directly) and compare.
  - Pass when: every toggle shows the same value as before, and the "LLM World (legacy)" and "LLM Chat (this bot)" rows are gone.
- [x] **Companions still talk and work (1.1.250)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: with Soul Chat on, talk to a companion by name and in a group. Spawn a bot and give it a task.
  - Pass when: souls answer as before, the bot starts moving right after spawn, and nothing says "connects to the language model".

## Phase 6as — Legacy LLM stack and libraries deleted (1.1.251)

- [x] **Souls still talk, startup is quieter (1.1.251)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: start Nirn with Soul Chat on and Ollama running, talk to two companions, let banter run for a few minutes.
  - Claude watches for: `[souls] runtime started; masterEnabled=true`, soul replies as before, no `ClassNotFound` or `NoClassDefFound`, no "Legacy BERT" or "Ollama not detected" lines at startup.
  - Pass when: souls answer and banter as before and the log has none of the old legacy startup lines.

## Phase 6at — Leash release, leash threading, base commands (1.1.252)

- [x] **Release a held animal anywhere (1.1.252)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: dismount a companion with the leash key away from fences so it holds the horse, then use Release Leads in its menu (or `/bot leash release <bot>`). Leave and rejoin.
  - Claude watches for: `[leash-release] <bot> let go of 1 lead(s)`, `saved mount ... no longer held by bot (RELEASED)`.
  - Pass when: the horse is free, the lead is in the bot's inventory, and after the rejoin the bot does not grab it again.
- [x] **Leash key still dismounts and ties (1.1.252)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: ride with a companion, press the leash key near a fence, then away from one.
  - Pass when: near a fence the horse is tied, away from one the bot holds it, no "couldn't dismount" line, nothing odd in the log.
- [x] **Base commands and rename (1.1.252)**
  - Sandbox 2026-10-18 (1.1.267): PASS (command rename), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: rename one of your bases in the Bases screen, then check its owner and radius.
  - Pass when: the owner and radius are unchanged after the rename.

## Phase 6au — Shelters: seal order, reload, dig-down (1.1.253)

- [x] **Dirt first without a pickaxe (1.1.253)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: at dusk, leave a companion with dirt and cobblestone but no pickaxe near an overhang.
  - Pass when: it seals with dirt and takes the dirt back at dawn.
- [x] **A shelter survives quitting the game (1.1.253)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: while a companion is sealed in for the night (any shelter type), save and quit, reopen the world.
  - Claude watches for: `shelter restored on join type=...`, then at dawn the matching break-free line.
  - Pass when: the bot stays sheltered after the reload and walks or digs out normally at dawn, with no roof mined and no blocks left behind.
- [x] **Dig-down leaves no open pit (1.1.253)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: at night on open flat ground, a companion with 4 dirt and no overhang or cliff near.
  - Claude watches for: `dig-down: centred=true`, `cap placed`, `dug emergency bunker`, or `dig-down skipped reason=...`, or `climbing out` and `column ... marked`.
  - Pass when: either a capped bunker (and a normal exit in the morning) or no hole at all. Never an open 1x1 pit with the bot at the bottom at night.

## Phase 6av — Bot Controls per world, remote server (1.1.254)

- [x] **Bot Controls keep their per-bot settings (1.1.254)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: open Nirn, `/configMan`, check one bot's toggles, change one, close, reopen.
  - Pass when: the change sticks and the other bots are unchanged.
- [ ] **Remote server edits stick (1.1.254, only if a dedicated or LAN server is handy)**
  - Sandbox 2026-10-18 (1.1.267): NEEDS BRADLEY (server), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: join a server running 1.1.254 as an op, change a bot's toggle in Bot Controls, rejoin.
  - Pass when: the change is still there after rejoining.

## Phase 6aw — Death retrieval (1.1.255)

- [x] **Fetch your own drops (1.1.255)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: with keep inventory off, die somewhere on land (not lava), respawn, open a companion's menu and use Retrieve Drops (or `/bot retrieve <bot>`).
  - Claude watches for: `[DeathRecord] RotiWokeman died at`, `[Retrieve] ... verdict=GO`, `sweep done: picked=`, `returning to RotiWokeman`.
  - Pass when: the bot comes back to you with your items within a minute or so and lands on the ground.
- [x] **Fetch a companion's drops (1.1.255)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: after a companion dies, `/bot retrieve <another bot> <dead bot>`.
  - Pass when: the items come back, and a lava death gets the "items burned up" refusal instead.

## Phase 6ax — Dig-down dawn climb-out, shelter lock (1.1.257)

- [x] **A dig-down bot is out of its pit at dawn (1.1.257)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: let a companion dig down for the night on open ground, check on it at sunrise.
  - Claude watches for: `dig-down dawn exit: climbing out`, `climb-out: out at`.
  - Pass when: within a few seconds of sunrise the bot is on the surface, the hole is filled to ground level, and no block sticks up.

## Phase 6ay — Retrieval drops only, trips survive a reload (1.1.258)

- [x] **Quit while a companion is fast travelling (1.1.258)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: send a companion somewhere with fast travel (or Retrieve Drops), save and quit before it arrives, reopen.
  - Claude watches for: `Auto-spawn skipped for <bot>: in transit`, then its arrival.
  - Pass when: the bot finishes the trip instead of reappearing where it started.

## Phase 6az — Ranged hits pause tasks, woodcut stand memory (1.1.259)

- [x] **A skeleton shooting a busy companion makes it stop and deal with it (1.1.259)**
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
  - Bradley does: let a companion woodcut near a skeleton or Bogged at dusk or in a dark forest.
  - Claude watches for: `paused task for ranged hit` or `hostile hit did not pause task: reason=...`.
  - Pass when: the bot stops cutting, fights or flees, and resumes once it is safe.

## Phase 6ba — Unified pathfinder is the default (1.1.261)

- [x] **First load migrates once.** Bradley opens Nirn on 1.1.261. Claude greps latest.log for `1.1.261 config migration: pathfinder switched to unified` (exactly once), then reopens and checks it does not repeat. Pass when the line appears once and `config/settings.json5` has `"unifiedDefaultMigrated": true`.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **The command reports and saves.** `/bot config pathfinder` replies `Current pathfinder: unified (default)`. `/bot config pathfinder baritone`, quit and reopen, then `/bot config pathfinder` replies baritone. Switch back with `/bot config pathfinder unified`. Pass when the choice survives the reload.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Bots walk on unified.** Woodcut or follow for a few minutes. Claude runs `tools/logs/frens_log_report.py latest.log --since <start>`. Pass when section 3 shows `unified: n=...` as the primary finder, no `SERVER PAUSED`, no deaths, and walk reach is about where the sandbox A/B had it (around two thirds).
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Settings toggle.** In Bot Controls the topic reads "Unified Pathfinder". Turning it off gives Baritone (`/bot config pathfinder` says baritone), and turning it on gives unified again. Pass when both directions show in the command reply.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.

## Phase 6bc — Sunset return and reach probes use the bot's pathfinder (1.1.263)

- [x] **Sunset return uses the chosen finder.** With a base set and auto-return at sunset on, let a companion work more than 60 blocks from the base until dusk. Claude greps latest.log for `Sunset segment for <bot> finder=`. Pass when the finder word matches `/bot config pathfinder` (unified by default) and the bot heads home. With `/bot config pathfinder baritone` the line says baritone.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **No server hitch at dusk.** During the same return, Claude checks for no `Can't keep up` lines and no `unified probe failed` WARN. Pass when the return does not stall the game.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.

## Phase 6bd — Stuck escape reads collision shapes (1.1.264)

Claude runs these in the mcp-sandbox with `tools/sandbox/stucktest.py` (Wall Test save), never in Nirn.

- [x] **Head-level wall nudge.** `stucktest.py wall`: stone at head height beside the bot. Pass when latest.log shows `nudged away from wall west to escape collision clip (head-level)`.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Plants are not walls.** `stucktest.py clip`: short grass, leaf litter and a snow layer around the bot, stone diagonal at head height. Pass when the log shows `escape side cell ... enterable (partial or plant), moving there instead of up` and no `escape movement toward up`.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Drop one off a block.** `stucktest.py pen`: bot on a plank in a closed fence ring, `/bot return`. Pass when the log shows `quick-nudge drop-1 toward` and no `ALL 8 directions blocked`.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Fences block the backup path.** `stucktest.py lane`: closed stone pocket with a fence two cells out. Pass when the log shows `feet blocked by Oak Fence ... (collision shape)` and the bot stays inside the pocket.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **No regressions on paths and farms.** In live play, a companion on a dirt path or in a farm never logs `ALL 8 directions blocked` while it can walk. Claude greps for it during any field session.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.

## Phase 6be — /bot go_to uses the bot's pathfinder (1.1.265)

Claude runs this in the mcp-sandbox with `tools/sandbox/gototest.py` (a copy of New World-commands), never in Nirn.

- [x] **go_to follows a per-bot override.** `/bot config pathfinder unified`, then `/bot config pathfinder baritone <bot>`, then `/bot go_to <bot> <x y z> false` to a spot about 20 blocks away. Pass when the first `[PathFinder] mode=` line after the command says `baritone`, and after `/bot config pathfinder default <bot>` the same go_to says `unified`.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.

## Phase 6bf — Sandbox bugs A-G fixed (1.1.266)

- [x] **No shears on leaves (A).** A bot with shears in its inventory fells a leafy tree and pillars up under leaves. Claude greps `Mining .* with minecraft:shears` (none on leaves) and checks `bot_state` for no `oak_leaves`. Pass when leaves are mined by hand or hoe and drop saplings or sticks.
  - Sandbox 2026-10-18 (1.1.267): PASS (1.1.266 recheck `366552e0`), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Empty cleanup in forest (B).** `woodcut_cleanup` in untouched forest. Claude greps `woodcut_cleanup completed` for `coverage=` and the time from `woodcut_cleanup starting`. Pass when it ends well under 2 minutes, or ends with "I checked what I could reach in time and found nothing to clean up." rather than "Nothing left to clean up here." when coverage is below 1.
  - Sandbox 2026-10-18 (1.1.267): SUPERSEDED by 6bg B2, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **One line for a failed craft (C).** A bot with no wood: `/bot craft wooden_axe` gives one line naming planks, sticks and the table. `/bot craft axe` gives one line, "I need 4 planks per craft.". Then give 7 planks: `/bot craft crafting_table` works right away (C2). `/bot craft wooden_axe <botName>` crafts with that bot (C4). Pass when each answer is one line and C2 and C4 hold.
  - Sandbox 2026-10-18 (1.1.267): PASS (1.1.266 recheck `366552e0`), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Relocation reaches distant trees (D).** A fresh copied world with all trees inside the Spawn base protected. Claude greps `Woodcut relocation` for `continuing toward` and for no `direct: deadline` on the first hop under 130 blocks. Pass when the bot reaches a tree 60-120 blocks away and cuts logs.
  - Sandbox 2026-10-18 (1.1.267): PASS (1.1.266 recheck `366552e0`), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Spawn-tree toggle cuts without a refusal (E).** "Cut trees in the Spawn base: On", woodcut a Spawn tree. Pass when there is no `miner refused … protected claim` and the logs count as cut.
  - Sandbox 2026-10-18 (1.1.267): PASS (1.1.266 recheck `366552e0`), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **No second claim on a half-cut tree (G).** Three bots woodcut near two trees. Pass when no two bots log `selected tree target` with the same x and z within 2 blocks while both claims are live, and the waiting bot's line names the owner.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Unified self-conflict is cut (F).** Watch any long unified walk with breaks. Claude greps `unified plan truncated at move` and `cost verification move .* now forbidden`. Pass when a forbidden line, if any, is not followed by the same plan again, and the truncation line shows which earlier move breaks the cell.
  - Sandbox 2026-10-18 (1.1.267): SUPERSEDED by 6bg F, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.

## Phase 6bg — Unified search routes around its own breaks, faster empty cleanup (1.1.267)

- [x] **Empty cleanup ends fast (B2).** `woodcut_cleanup` in untouched forest. Claude greps `woodcut_cleanup starting` and the `Skill 'woodcut_cleanup' exit` line. Pass when it ends in well under a minute, with "Nothing left to clean up here." at `coverage=1.00`, or with `empty scan phase capped after` and "I checked what I could reach in time and found nothing to clean up." (sandbox: 16 s at 400,96,-298).
  - Sandbox 2026-10-18 (1.1.267): PASS (1.1.267 sandbox, 16 s), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Cleanup with real work still finds it (B2).** Stop a bot mid-woodcut to leave floating logs or a scaffold pillar, then `woodcut_cleanup`. Pass when the floaters and scaffold are removed and no `empty scan phase capped` line appears.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **No self-undoing route (F).** Watch long unified walks that break blocks (forest, hills). Claude greps `unified plan truncated at move`, `now forbidden` and `SELF_CONFLICT`. Pass when the bot never mines the block it then needs to step up onto, and a truncation line, if any, is rare (it now means search staleness, not bug F).
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Probes still answer (F).** Sunset return and `canReach` callers over mining terrain. Pass when there is no new `reason=BUDGET` on sunset probes compared with 1.1.266.
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Table craft without planks (M1, M2).** A bot with no wood near a chest with no wood: `/bot craft wooden_axe` twice within 15 s. Claude greps `pulled|withdraw|table` lines. Pass when the second call does no chest pull. A bot holding a crafting table where it can't place one (e.g. standing in water): `/bot craft wooden_axe` gives one line, ending "I have a crafting table but can't place it here.".
  - Sandbox 2026-10-18 (1.1.267): PASS, see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Mounted leaf clearing (M3).** Ride a horse under leaves with a bot holding a sword or shears and an empty hotbar slot. Pass when it clears leaves bare-handed (saplings or sticks drop, no leaf blocks, no sword wear) and does nothing while its hotbar is locked.
  - Sandbox 2026-10-18 (1.1.267): PASS (clearing; locked hotbar not set up), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.
- [x] **Bot named after a material (M4).** A bot named Iron: `/bot craft stone_axe Iron` runs on Iron, `/bot craft axe Iron` still makes an iron axe with the active bot (sandbox: passed).
  - Sandbox 2026-10-18 (1.1.267): PASS (1.1.267 sandbox), see `FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`.

## Phase 6bh — Spawn-base own scaffold, forest woodcut, honey and fish fixes (1.1.268)

- [x] **A Spawn tree is felled whole with the bot's own scaffold (1.1.268).** Sandbox 2026-10-18: PASS, save "Sweep Spawn", rooted oak at 14,73,3: `[spawn-scaffold] Sprout placed own scaffold at 13, 73, 3` to 13,76,3, `scaffoldPlaced=4 scaffoldRemoved=4 terminalReason=SUCCESS`, six logs cut, column empty, no dirt dug.
- [x] **Forest woodcut keeps to one tree and scans fast (1.1.268).** Sandbox 2026-10-18: PASS, `forest.py 15 78 -110 3` (three bots): `Woodcut detect: durationMs=` 170-1882 with `bases=N memoHits=M`, failedBaseCount 1-2 of 4-6, leftover lists only the tree's own logs, no relocation.
- [x] **honey_collect and cooking fish (1.1.268).** Sandbox 2026-10-18: PASS, a full smoked hive 5 blocks away: `Harvested honey from beehive at 14, 75, 3`, honey bottle in the inventory. `/bot cook fish` put Raw Cod in the furnace.
- [x] **A stop mid-fell leaves no scaffold in the Spawn base (1.1.268).** Sandbox 2026-10-18: PASS, save "Sweep Spawn", toggle On, a 9-log jungle tree at -2,74,-3, `/bot stop Sprout` sent the moment the third block went down: `[11:32:20] [spawn-scaffold] Sprout placed own scaffold at -2, 74, -4` to -2,76,-4, `Task 'skill:woodcut' aborted: Stopped by the player.`, `[11:32:29] [spawn-scaffold] Sprout removed 3/3 own scaffold block(s) (abort=true)`, trace `scaffoldPlaced=3 scaffoldRemoved=3`. Column empty 9 s after the stop, nothing else mined (the grass under the pillar turned to dirt, vanilla). Replay `frens_1_1_268_6bh_stop_mid-fell.zip`. Note: the half-felled trunk (logs 78-82 and the canopy) stays in the base, later detects reject it (`failedBase=true`).
- [x] **Leftover own scaffold after a quit is cleared next time (1.1.268).** Sandbox 2026-10-18: PASS, Save and Quit right after `[11:33:31] Sprout placed own scaffold at 0, 75, -3` (two blocks, persisted in `ownedSpawnScaffoldBlocks`), relaunch: Sprout stood on the pillar, `/bot skill woodcut Sprout`: `[11:35:03] [spawn-scaffold] Sprout cleared 2 leftover own scaffold block(s) from an earlier fell (0 farther than 16 blocks left remembered)`, 0,74..75,-3 air, nothing nearby touched. Replay `frens_1_1_268_6bh_leftover_and_axeless.zip`.
- [x] **An axeless bot in the Spawn base still cuts only what it can reach (1.1.268).** Sandbox 2026-10-18: PASS, Twig with an empty inventory at a 12-log birch (4,74,-3): `Woodcut scaffold gather: ... (133 protected), gathered 0 of 4`, `has no axe, not pillaring (fell-ascent)`, `mined 3 logs`, `scaffoldPlaced=0`, chat "Got 3 logs, the rest of that tree needs an axe.", then it left the base, punched a tree outside it and crafted an axe. No dirt dug, no block left.

## Phase 6bi — Rescue reads chest food the way the take does (1.1.269)

- [x] **A trapped, starving bot beside reserve-only chest food is rescued (1.1.269).** ✅ sandbox 2026-10-18 on 1.1.270 (`tools/sandbox/rescuetest.py`): 5 bread, rescue every 20 s, no request; 12 bread, the bot asked, took 3 bread, no rescue.
  - Bradley does (or Claude in the sandbox): put 5 bread in an owned chest (all inside the reserve of 8), wall a bot in next to it with an empty inventory and hunger near zero, wait.
  - Claude watches for: `Emergency rescue` lines for that bot (a helper picked, or `unavailable ... status=no_valid_helper` if no one can help) and no `food_path_available` / `sheltered_with_food` reason while the chest holds only reserve food.
  - Pass when: the rescue fires. Then with 12 bread in the chest the bot takes from the chest instead and no rescue fires.

## Phase 6bj — Fix build from the 0-6ai sweep (1.1.272)

Sandbox 2026-10-19 on the 1.1.272 jar (md5 71313211...), save "Sweep Old3", `tools/sandbox/fix272.py`, one Flashback recording (replay `frens_1_1_272_6bj_fix_checks.zip`). Checked there and not repeated here: settings save past 32 KB (finding 15: the 72 KB `settings.json5.bak-pass3-bloat` swapped in with `sb_cfg`, `/configMan` opened and a Bot Controls toggle saved, no `EncoderException`, no disconnect), guard escape after `/bot stop` then `/bot guard` with the owner 4 blocks away (5: `reached operational surface via pillar recovery`, `[guard-escape] Wedge guard point moved to 299, 120, 299`, no re-wedge), woodcut asks an unregistered chest for an axe (8: `[supply] tool scan for Jake2: registered=0 nearby=1`, no answer in 30 s → bare hands, "Allow once" → `Found an axe in a nearby chest.` and logs `with minecraft:stone_axe`), bundled diamond pickaxe pulled (6), wool gives up after 3 stuck cycles in 13 s (35), lodestone travel from about 980 blocks to an unloaded lodestone (31: departed and arrived at -92 63 -599), travel-wait finds the chest and offloads (32: `chest=yes full=1.00 -> OFFLOAD_EXISTING`, bag 1.00 → 0.47), Spells key with Questing off and no artifact opens the Spells tab (30), a soul-bound bot killed with Auto Respawn on stays `active=true` after the forced respawn (1).

- [ ] **A party question gets an answer from each bot, and a repeated answer still delivers one line (1.1.272, souls 2 and 19).**
  - Bradley does (or Claude in the sandbox with the Ollama tap): souls on, two bots enabled beside you, say "bots, what should we do next?" twice in a row.
  - Claude watches for: `scene-routing ... eligible=2` then the scene's `lines=` count, and on the repeat `[souls] novelty floor kept bot=`.
  - Pass when: the first scene has a line from each bot, and the repeat still delivers one line (never `lines=0 delivered=0/0`).
- [ ] **A DM reply no longer says "you said that already" to a new line (1.1.272, souls 22).**
  - Bradley does: DM Jake three new statements in a row ("the snow is pretty", "I found a cave", "let's build a boat").
  - Pass when: no reply treats the line just said as a repeat, and the tap of the prompt shows the line once (as the final user turn), not again in RELEVANT KNOWLEDGE.
- [ ] **Resurrection with Questing off (1.1.272, finding 30).**
  - Bradley does (or Claude in the sandbox): Admin world, a bot of yours with Auto Respawn off dies, answer "no" to the respawn prompt. In the Nether, sneak-right-click a charged respawn anchor holding a Ghast Tear, with the ritual offering (4 gold ingots, 1 blaze powder) in your inventory.
  - Pass when: the bot comes back, and it loads with the world on the next join. A bot that belongs to another player is never revived (multiplayer half, Bradley).

## Phase 6bk — Second fix build from the 0-6ai sweep (1.1.273)

Sandbox 2026-10-19 on the 1.1.273 jar (md5 afc36cec...), save "Sweep Souls3" (easy, no mob spawning, noon), `tools/sandbox/fix273.py`, replays `frens_1_1_273_6bk_fix_checks.zip` (16:06-16:48) and `frens_1_1_273_6bk_fix_checks_rate0.zip` (16:49-16:58, after a relaunch with `dialogueScriptedRate: 0`). Checked there and not repeated here: follow stop prints one line (27: `/bot stop Jake` and the `\` key each gave only "Jake has stopped.", never "Stopping follow command."), bed craft with 1 white wool and 10 planks and no table near (4: "Can't craft bed: need 2 more matching wool", no table crafted or placed, bag still 1 wool + 10 planks), drop sweep count (11: 30 items in six piles, "Picked up 30 items." and the bag holds 30), idle stone upgrade (9: wooden pickaxe and shovel, no axe or sword, 16 cobble, `Idle stone upgrade: Smith crafted stone tools (wooden or missing before)` 3 s after `/bot idle_hobbies on`, bag then has stone pickaxe, sword, axe and shovel). Covered by unit tests and not listed here: Scripted-rate scaling (`DialoguePacingTest`), "Time to punch some trees" gate (`TreePunchLinePolicyTest`), any-actor veto decision (`SoulLocalDirectorTest`), resume freshness and record ownership (`ResumeFreshnessPolicyTest`), environmental damage is not combat (`SoulEventObserverTest`), re-poll progress (`RepollPolicyTest`), pickup count (`DropSweepMessagesTest`), bed plank count (`CraftingStationPolicy` tests), stone upgrade policy (`StoneUpgradePolicyTest`).

- [ ] **Ambient chatter never talks over a scene or another bot (1.1.273, finding 17).**
  - Bradley does: play 20+ minutes with two or three bots near you, souls on, Scripted rate at its default; then 10 minutes with the Scripted rate at 0.
  - Claude watches for: ambient lines (weather, sunset, "If you need me, I'm listening.", "My teeth itch!") in `latest.log` within 4 s of another bot's line or inside a soul scene.
  - Pass when: none overlap; at rate 0 ambient lines are rare but still appear.
  - Sandbox 2026-10-19 (1.1.273): PARTIAL (passes, shorter than the item asks): Jake and Bob idle 6 blocks from the player, 4 forced banter scenes. Default rate, 10 min: 26 ambient lines, none within 4 s of another bot's line (closest pairs exactly 4 s: 16:39:43/16:39:47, 16:43:56/16:44:00), none inside a scene (`fix273.py ambient`). Rate 0, 9 min, 3 scenes: 3 ambient lines (16:51:16, 16:53:33, 16:56:31), about 7x rarer, still present. The 20+ minute play stays open.
- [x] **No soul scene with a hostile near anyone (1.1.273, finding 18).**
  - Bradley does (or Claude in the sandbox): souls on, a zombie near a bot that is not the one about to speak, say something to the group.
  - Claude watches for: `vetoed:danger` with the actor that saw it.
  - Pass when: no scene starts while any bot or the player has a hostile within 16 blocks.
  - Sandbox 2026-10-19 (1.1.273): PASS: a frozen zombie 6 blocks from Bob, 17+ from Jake and the player, an unaddressed salient line: `[souls] local player=... danger seen by Bob (1 hostile(s) within 16)` then `outcome=vetoed:danger` (16:35:50). Zombie beside the player only, both bots 12+ blocks off: `danger seen by RotiWokeman (1 hostile(s) within 16)` (16:36:43). No scene fired. (A line naming a bot routes as a DM and skips the local director.)
- [x] **A threat never revives an old task (1.1.273, finding 23).**
  - Bradley does (or Claude in the sandbox): `/bot skill woodcut` to completion, wait a few minutes, let a zombie come near the idle bot and get killed.
  - Pass when: no "Resuming 'woodcut' ... Hostiles cleared." for the finished task; a woodcut actually interrupted by a zombie still resumes.
  - Sandbox 2026-10-19 (1.1.273): PASS: Cutter's woodcut finished 16:24:39 (`Cleared resume record for Cutter after 'woodcut' finished`, DEBUG `skill-resume`), zombie 16:24:52, "Terminating all current tasks", killed, no resume in 90 s. Zombie mid-cut 16:31:04: "Pausing current task due to nearby threat.", then `Resuming 'woodcut' for Cutter. Hostiles cleared.` 16:31:14. That resumed run finished 16:31:45 (record cleared); a zombie at 16:31:52 brought no resume in 75 s. The 10-minute stale drop (`Not auto-resuming ... past the 600 s window`) was not exercised.
- [x] **Woodcut on a stone platform finishes its blocks and leaves natural stone (1.1.273).**
  - Claude in the sandbox: woodcut with a stone axe on a tree standing on stone.
  - Pass when: no run of `Mining re-poll: swapping` lines each second, and the cleanup sweep mines only blocks the bot placed.
  - Sandbox 2026-10-19 (1.1.273): PASS: Feller with a stone axe and 24 cobble (no pickaxe), a 9-log oak on a dirt block set in the stone deck (woodcut's soil check refuses a trunk on bare stone): 7 cobble pillar and bridge, every block `Mining complete at`, "Got 9 logs from that tree.", 0 `Mining re-poll` lines; cleanup `found 0 candidates`, the fallback sweep took only the dirt under the trunk (finding 38), the 9x9 stone around the trunk unchanged. What re-equipped the axe: in a `/bot skill mining` run on the floating deck every block started `with minecraft:wooden_pickaxe` and 1 s later the re-poll found `stone_axe` back in hand (`swap ... was undone (now minecraft:stone_axe)`); with the deck filled solid underneath (no drop within 10 blocks), 23 blocks and 0 swaps. So the AutoFaceEntity danger-zone (cliff) branch's combat loadout, as the 1.1.274 scope found (`e4f77737`).

## Phase 6bl — Third fix build from the 0-6ai sweep (1.1.274)

Sandbox 2026-10-19 on the 1.1.274 jar (md5 6a431884...), saves "Sweep Souls3" (easy, no mob spawning, noon; two launches with a save_and_quit between) and "AB R1" (the save that holds the name-keyed chests), `tools/sandbox/fix274.py`, replays `frens_1_1_274_6bl_fix_checks.zip` (17:09-17:29), `frens_1_1_274_6bl_fix_checks_minds_relaunch.zip` (17:29-17:31) and `frens_1_1_274_6bl_fix_checks_chest_registry.zip` (17:32-17:34). Checked there and not listed below: **woodcut keeps its axe** (PASS, `e4f77737`: Axer with a stone axe, a wooden pickaxe and 24 cobble on the floating deck, a 9-log oak on dirt: "Got 9 logs from that tree." 17:10:39, the pillar mined `with minecraft:wooden_pickaxe`; then `/bot skill mining` 17:12:10-17:12:30, 10 blocks each `with minecraft:wooden_pickaxe` and `Mining complete at`; 0 `was undone`, 0 `Mining re-poll: swapping`, 0 `Combat loadout swapped`); **craft after a failed reach** (PASS, finding 13: a table inside a barrier ring 7 blocks west, a second one 15 blocks east on open deck, 3 planks + 2 sticks: call 1 `Failed to reach crafting table at 353, 120, 300` 17:14:29 and "I need a crafting table placed nearby to craft that." (that call still reports "No crafting table within 40 blocks", the changelog's not-covered part); call 2 `Found nearby crafting table at 375, 120, 300` 17:14:33, "Crafted 1 Wooden Pickaxe." 17:14:35, bag only the pickaxe and bread, no table crafted); **unknown item once** (PASS, finding 7: `/bot craft spaceship` at 17:14:51, :53, :55, one "I don't know how to craft spaceship.", no "I couldn't craft"); **fishing search** (FAIL, finding 14 still open, new finding 39: beside a 7x7 pond `Fishing spot search: columns=49 evaluated=5 stands=0 aStar=0 cacheHits=0 found=true elapsed=11745ms` 17:15:58, a fresh pond 80 blocks away 11561 ms at 17:18:03; a cast followed both times, bite 17:16:12); **idle fallbacks** (PASS: Tailor with a wooden pickaxe and shovel, no axe, no planks, 24 leather, `Idle leather armor: Tailor crafted leather armor` 17:19:10, 3 s after `/bot idle_hobbies on`, four pieces; the wooden fallback logged only `blocked — no safe woodcut target`, 0 "I have no axe", `sb_flood` clean apart from finding 42); **first sightings survive a relaunch** (PASS, finding 20: a frozen pig beside Jake and Bob, cue "the first pig any of you have seen" 17:27:09, `animal:pig` in both `mind.json` at once; save_and_quit and relaunch, the pig still there (17:30:09), scenes 17:29:53 and 17:30:43 without it); **damage in words** (PASS, finding 21: the setup's deck fill entombed Jake and Bob at load, `takingSuffocationDamage=true ... headState=Stone` 17:09:32, both died; the cue at 17:26:14 "tell RotiWokeman a worry about took a beating from being stuck in a wall. (background only, mention at most once: died recently from being stuck in a wall ...)"; fire not exercised); **chest registry per save** (PASS for the owning save, finding 26: with the pre-upgrade `bot_chest_registry.json` restored and AB R1 loaded first, `Adopted legacy chest registry 'New World/minecraft:overworld' as 'New World#9c8d4fde:minecraft:overworld'` 17:32:12, the json has the fingerprint key and `legacyWorldClaims`, all four records kept, none destroyed; smokey `/bot store deposit 7 cobblestone` 17:33:06 "Deposited 7 items." into the registered chest at 68, 72, 226 (its snapshot was not refreshed). But the first launch of Sweep Souls3, a different save also named "New World", had already adopted those chests at 17:08:36, new finding 40). **Finding 37 repro** (not fixed): a frozen zombie 6 blocks from Bob, 16 "Terminating all current tasks due to threat detections" chat lines in 61 s (17:27:33-17:28:34, every 4 s, Jake and Bob alternating). New findings 39-42 in `FIELD_0_6AI_SANDBOX_2026-10-18.md`.

- [ ] **A farm whose hoe is gone fails with the no-hoe line, even half planted (1.1.274).**
  - Bradley does: let a bot make and half plant its own farm, take its hoe (and any hoe in reach), leave seeds in its bag, `/bot skill farm`.
  - Claude watches for: `[FarmIrrigation] farm run did nothing: ... hoeAvailable=false untilledTillable=N seedsLeft=M` and the chat line "I don't have a hoe in reach, so nothing got planted."
  - Pass when: the run fails with that line, never "planted 0 seeds (tilled 0)" or a success.
  - Sandbox 2026-10-19 (1.1.274): PARTIAL: with no hoe at all the start gate answers first (17:20:38 "Farmer: I need a hoe to till the soil." and "Missing hoe."), never "planted 0 seeds". The half-planted path was not reached: the skill rejects a hand-made farm (`site-reject ... reason=existing-farmland`), and its own farm on the floating deck failed ("Failed to stabilize irrigation basin.").

## Phase 6bm — Fix build from the 1.1.273/274 sandbox checks (1.1.275)

Sandbox 2026-10-19 on the 1.1.275 jar (md5 f4a234fd...), saves "Sweep Souls3" (easy, no mob spawning, noon; 18:58-19:23, launched with the pre-1.1.274 `bot_chest_registry.json` in place) and "AB R1" (the save that holds the name-keyed chests; two launches, 19:23 and 19:24), `tools/sandbox/fix275.py`, replays `frens_1_1_275_6bm_fix_checks.zip` (19:00-19:22), `frens_1_1_275_6bm_fix_checks_chest_owner.zip` (19:23-19:24), `frens_1_1_275_6bm_fix_checks_chest_foreign.zip` (19:24-19:25) and `frens_1_1_275_6bm_fix_checks_threat_skeleton.zip` (Sweep Souls3 relaunched, 19:29-19:33). Checked there and not listed below: **threat line** (PASS for the repro, finding 37: a frozen zombie 5-6 blocks from idle Jake and Bob and from W1 and W2 running `/bot skill mining`, 0 "Terminating all current tasks due to threat detections" lines in 61 s (1.1.274: 16); the same with an awake zombie in a glass cell beside two woodcutting bots, and with an ordinary zombie that walked up and hit two miners (0 lines, the bots fought it). The once-per-episode announcement itself was not reached: no threat pause happened in any run (`Bot W1 hostile hit did not pause task: reason=not-projectile task=skill:mining`, 19:08:21), nor with an invulnerable skeleton beside two woodcutting bots (19:30-19:33, it came down and hit in melee, then fenced on a pillar it never shot); the ranged-hit pause in `Frens.pauseTaskForRangedHit` does not say the line anyway, new finding 46); **tree soil** (PASS, finding 38: Axer with a stone axe, a wooden pickaxe and 24 dirt felled a 9-log oak on one dirt block in the stone deck, "Got 9 logs from that tree." 19:16:03, `scaffoldPlaced=7 scaffoldRemoved=7`, 0 `fallback sweep removed`, 0 `Mining 297, 119, 300`, the block under the trunk still `dirt` and the trunk column air); **woodcut that runs out of trees** (PASS, finding 41: Axer2 with the only tree on the deck, three relocations found nothing ("relocation 3/3 failed" 19:17:53), then `success=true` "Cleared the local woodcut area after 1 target(s)." 19:17:56, task COMPLETED, no "Repeated failures". The plain `/bot skill woodcut` is a radius-clear run, so it prints that line; "Cut 1 tree(s). No more trees nearby." is the open-ended run's line); **fishing search** (PARTIAL, finding 39: the old ponds are remembered ("Reusing remembered fishing spot", no search), so two fresh ponds: `Fishing spot search: columns=49 evaluated=5 stands=0 aStar=0 cacheHits=0 found=true elapsed=1781ms (snapshot=skipped snapshotMs=0 chests=0 chestMs=12 columnsMs=194 evalMs=1572)` 19:13:17, and at an identical pond `stands=1 aStar=1 ... elapsed=8392ms (snapshot=built snapshotMs=6837 ... evalMs=1356)` 19:14:22; a fishing session started both times. The chest scan is fixed (12 ms); the evaluation of 5 columns still takes 1.4-1.6 s and the snapshot 6.8 s when built, new finding 43); **chest adoption** (PASS, finding 40: with the pre-1.1.274 registry, Sweep Souls3 logged `Chest registry 'New World#3644a587:minecraft:overworld' starts empty (START_EMPTY_NOT_THIS_SAVE, checked=4, chestLike=0); legacy 'New World/minecraft:overworld' left untouched` 18:58:44 and the json kept `legacyWorldClaims` empty; then AB R1 `Adopted legacy chest registry 'New World/minecraft:overworld' as 'New World#9c8d4fde:minecraft:overworld' (checked=1, chestLike=1)` 19:23:42 and smokey `/bot store deposit 7 cobblestone smokey` "Deposited 7 items." 19:24:02; from the after-Souls3 backup (claim held by 3644a587) AB R1 logged `Adopted ... over the claim of 'New World#3644a587': a recorded chest is in this save (checked=1, chestLike=1)` 19:24:46, deposit again "Deposited 7 items." The empty-own-store path was not run. The foreign save keeps its 1.1.274 copy of the four records, new finding 45; the starting registry was restored afterwards); **desperate eating** (PASS, finding 42: Hungry with a stone pickaxe drained to food 0, set in stone (the emergency snap recovery freed it within a second), resistance, then 16 rotten flesh and `/bot skill stripmine`: 2 "Desperate food: eating rotten flesh" lines in 45 s (19:19:20, 19:19:38) while food went 0 → 18 on 6 flesh; off the base, 1 line in 45 s (19:21:18) and stripmine started 2 s later at food ≤ 5 and finished "Stripmine cleared 8 blocks." with no "starving and out of food" pause). `sb_flood` clean apart from new finding 44 (444 `mining detected 1 hostile(s) nearby` WARN lines).

No field items: every 1.1.275 check is a sandbox check (the open threat-pause path is finding 46).

## Phase 6bn — Worker scans and miner threats (1.1.276)

Sandbox 2026-10-19 on the 1.1.276 jar (md5 0e626b6e...), save "Sweep Souls3" (easy, no mob spawning, noon; 20:49-21:17, launched with `bot_chest_registry.after-foreign-275.json` in place, the state where `New World#3644a587` holds the four copied records; relaunched 21:20-21:23 for the fishing numbers), "Sweep Souls3" on the 1.1.275 jar (21:18-21:19, follow baseline only) and "AB R1" (21:24), `tools/sandbox/fix276.py` (each stage on a fresh floating stone deck away from fix275's), the sandbox log4j config with DEBUG for `skill-hangout`, `farm-skill`, `crafting-helper` and (relaunch) `skill-fishing` (restored afterwards), replays `frens_1_1_276_6bn_fix_checks.zip` (20:49-21:17), `frens_1_1_276_6bn_fix_checks_follow_275.zip` (1.1.275, 21:18-21:19), `frens_1_1_276_6bn_fix_checks_fishing.zip` (21:20-21:23) and `frens_1_1_276_6bn_fix_checks_abr1.zip` (21:24). The starting registry was restored afterwards. Checked there and not listed below: **fishing search** (PASS, finding 43: five fresh 7x7 ponds, a new bot each. With DEBUG on, `Fishing spot search: columns=49 evaluated=25 stands=1 aStar=1 cacheHits=0 found=true elapsed=31ms (snapshot=built snapshotMs=8 chests=0 chestMs=9 columnsMs=4 evalMs=8 surfaces=49)` 21:21:04, `stands=3 aStar=3 ... elapsed=14ms (snapshot=built snapshotMs=5 ... evalMs=4)` 21:22:07 and `columns=21 ... elapsed=13ms (snapshot=built snapshotMs=5 ... evalMs=4)` 21:23:15 (1.1.275: 1781 ms with the snapshot skipped, evalMs 1572; 8392 ms when built, snapshotMs 6837). The two first-launch searches (20:50:12, 20:51:24) logged no INFO summary, so each took under 250 ms. A fishing session started every time, in the command's second. Every search built the snapshot (5-8 ms), so the skipped case was not produced; it no longer matters for the time); **hangout start** (PASS, finding 43: Camper 7 blocks from a lit campfire, `/bot skill hangout Camper` 20:52:23, `[Hangout] campfire scan r=24 took 2 ms (1 campfires)` and "I'll hang out by the fire for a bit." in the same second (the path snapshot is taken between them), `Hangout move [hangout-init]: target=303, 120, 420 success=true` 20:52:24. No 1.1.275 sandbox run had a hangout; the 1.1.276 changelog puts the old start at the ~7 s snapshot); **woodcut scans** (PARTIAL: W1 and W2 on a 4-tree deck (fix275's shape). With trees standing, `Woodcut detect: durationMs=394 logs=20 ... bases=4` 21:03:09 and `durationMs=374 logs=18` 21:03:11 (1.1.275, same shape: 1091-1541, median 1066, n=87), about 3x faster but not under 100 ms; the per-log checks still read the world (deferred in the changelog). The 9 empty detects took 0-1 ms (1.1.274/275: 104-119). Relocation: `Tree sampling from 242, 120, 413: sampledColumns=618 reachedRadius=117/120 found=none budgetExhausted=false after 10ms`, stage total 105-126 ms (1.1.275: broad stage 66-200 ms, total 318-626 ms, "no unprotected tree within 120 blocks"; it logged no reach). No tree within 120 blocks of that deck, so none was found; both bots felled all four trees and ended `success=true`); **craft table scan** (PASS: Crafter with 3 planks and 2 sticks, never near a table, 15 blocks from one: `Crafting table scan: 1 hit(s) in a 81x13x81 box, 1 section(s) walked, 1.47 ms` 20:53:08, walked to the table at 412, 120, 420 and "Crafted 1 Wooden Pickaxe." 20:53:11, no spare table. The line is new in 1.1.276; on a floating deck only one section holds blocks, so natural ground walks more sections); **farm water search** (PASS for the search: Farmer2 with a wooden hoe and 16 seeds, a 2x2 pool 7 blocks from the grass: `[FarmScan] enclosed 2x2 irrigation scan r=24 took 6 ms (found=true)` and `[FarmScan] still-water source scan r=24 took 4 ms (4 sources)` 20:59:52, the command's second. The farm then stopped with "No water available for irrigation." because the test gave no bucket. Water at the farm centre is rejected by the site check (`site-reject ... reason=water-surface`, also on 1.1.274), so fix274's layout cannot test this); **miner beside hostiles** (PASS, finding 44: a frozen zombie 5 blocks off, M2 logged `mining sees 1 hostile(s) nearby (0 it would fight): mining on.` once (21:05:08) and "Collected 10 stone blocks." 21:05:27, 0 `detected ... hostile(s)` WARN lines (1.1.275: 444); an ordinary zombie, M1 `mining sees 1 hostile(s) nearby (1 it would fight): engaging.` 21:07:43, killed it by 21:07:46 ("Monster Hunter"), `mining iteration 4` at 21:07:47 and "Collected 10 stone blocks." 21:08:06; a creeper 8 blocks off, M3 `[creeper] M3 decision=FLEE_SPRINT dist=5.9` 21:09:40, then `[creeper-defense] M3 engaging backoff` and "A creeper! I dropped what I was doing to get clear." 21:09:46; M3 went from x 361 to 370.5 and stayed there, it never walked back to dig toward the creeper. Test artifacts: in the first caged run mutual aid handed M1's pickaxe to the bare M2 before `/clear` (fixed in the script), and in the free run M2 rejoined at an old underwater position and stopped with "water flooded the dig site"); **copied chest records** (PASS, finding 45: Sweep Souls3 logged `Retired 4 live copied chest record(s) in 'New World#3644a587:minecraft:overworld' (marked destroyed, not removed): legacy 'New World/minecraft:overworld' is claimed by 'New World#9c8d4fde' and none of the checked own positions holds a chest (checked=4, chestLike=0)` 20:49:14, the json kept the four records with `destroyed=true`, and the relaunch logged no second retire; AB R1 logged no retire, its four `9c8d4fde` records stayed `destroyed=false`, and smokey `/bot store deposit 7 cobblestone smokey` "Deposited 7 items." 21:24:31); **follow** (PASS as a sanity check, no regression: Fol followed the player 36 blocks south over hills (Y 72-79, the player hopping 2 blocks a second onto the ground), 0.7-3.3 blocks behind for the first 13 hops, no "Can't keep up!" all session; then it stalled 8 blocks behind under an oak canopy at 61, 77, 266 (`Follow status: ... directBlocked=true usingWaypoints=true wp=4 navGoal=60, 76, 268`) for over 40 s. 1.1.275 stalled at the same tree (55.7, 77, 268, replay `_follow_275`), new finding 47). `sb_flood` clean apart from new finding 48 (Bob, underwater since the 1.1.275 mining stage, retries surface recovery every ~10 s: 167 "ascent failed" WARN lines in 28 minutes).

No field items: every 1.1.276 check is a sandbox check (open: findings 47 and 48; finding 46 is closed by 44's ruling, its pause line still unseen).

## Phase 6bo — 1.1.277 fix checks (1.1.277)

Sandbox 2026-10-19/20 on the 1.1.277 jar (md5 45aa2300...), save "Sweep Souls3" (easy, no mob spawning, noon; 23:29-00:19), the sandbox pointed at `tools/sandbox/ollama_tap.py` for the soul replies (restored afterwards), `tools/sandbox/fix277.py` (fresh structures at Z 660-720, away from fix275/276's decks; woodcut, follow and mine reuse fix276's spots), replays `frens_1_1_277_6bo_canopy_flood.zip` (23:30-00:07) and `frens_1_1_277_6bo_souls_woodcut_follow_mine.zip` (00:07-00:19). Checked there and not listed below: **canopy step down** (FAIL, finding 47 open, new findings 50 and 51: the scoper's repro, leaves X-6..X+6 at the head cell of the step down, read back 13/13 before and after every run. Canopy walked to the row's west end and stood at 294.0, 120, 660.7 with `navGoal=293, 119, 661` for 55-60 s in each of `/bot follow` (twice), `/bot stop` then `/bot follow`, and `/bot follow-distance 3 Canopy`; 0-2 `head-not-passable` lines a run (1.1.276 looped on them), no leaf mined, no "Mining aborted" after a fresh command. A wall-to-wall row (X-11..X+11, no detour): `head-not-passable` 75 and 77 lines in 60 s, no `leaf-cleared`. After an external `/tp` with follow still on, 5 leaf clears each ended `Mining aborted for Canopy` (23:54:59-23:55:28, finding 51). `/bot follow <bot> 3` is not a command (3 is read as a player, "No player was found"); the distance form is `/bot follow-distance 3 <bot>`. The original spot passed: Fol followed the player 46 blocks south from 60, 72, 230 over the oak near 61, 77, 266 and ended 1.1 blocks from him (lag up to 9.0 blocks mid-run), 0 "Can't keep up!", 0 `head-not-passable`); **flooded pocket** (PARTIAL, finding 48, new finding 49: a 1x1x3 pocket under 21 blocks of stone, water at 102, solid below, a fresh bot `/tp`'d in and never stopped. With the owner about 24 blocks above, the bot lingers by design (`linger: suppressed — commander nearby and underground`, 23:31-23:35); from 48 blocks away: `carving a ledge on north ... mine=2 depthToSky=23` 23:37:01, `ledge at 420, 103, 659 ready, climbed out=false` 23:37:12, then `not on ground` and `Surface recovery stalled without gaining height.`; `Stuck-escape backoff: Flood1 failed surface recovery 2 times at Y=102 while floating in water, pausing retries for 30 s` 23:38:20 with "I'm stuck in a flooded hole and can't climb out.", then 60 s (23:39:20) and 120 s (23:40:50); `ascent failed` 3 times in 4 minutes. Bare-handed: the ledge took 80 s (23:41:42-23:43:02), the rest the same. Bob at 234, 62, 299 is in open ocean under one of the old floating decks (stone at Y ~120), not a pocket: `no ledge carved: no side of natural rock to carve [north:nofloor/OPEN/OPEN ...]`, backoff at his 2nd failure (23:30:09), 600 s at the 9th (00:06:47)); **stage directions** (PASS, weak: 6 DMs to Jake and Bob and 7 banter scenes, 34 soul chat lines, none with a stage direction; the tap shows the model wrote none in its 14 replies (the DM prompt now forbids them), so the strip itself ran on nothing. "(get it?)" stayed, dialogue by the punctuation rule. Unlisted words seen only in older tap replies: in the Observations of `FIELD_0_6AI_SANDBOX_2026-10-18.md`); **woodcut detect** (PASS: W1 and W2 on fix276's 4-tree deck, `Woodcut detect: durationMs=24 logs=20 visited=0 humanProx=0 protected=0 nearest=246, 120, 417 bases=4 memoHits=16` 00:14:34 and `durationMs=12 logs=18 ... nearest=244, 120, 414 bases=4` 00:14:36 (1.1.276: 374-394 ms), empty detects 0-13 ms; all four trunks gone and replanted, both `success=true` "Cleared the local woodcut area after 1 target(s)."; W1's cleanup sweep mined the dirt under W2's tree, new finding 52); **regressions** (PASS: the follow above; M2 beside a free zombie logged `mining sees 1 hostile(s) nearby (0 it would fight): mining on.` once, "Collected 10 stone blocks." in 28 s, 0 `detected ... hostile(s)` WARN lines. M1 lost its pickaxe to mutual aid during the gives, a test artifact). `sb_flood` clean (top line "Bot is busy, skipping facing" 237 of 20000).

- [ ] **Soul lines carry no stage directions (1.1.277).**
  - Bradley does: play a while with souls on: a few DMs and some party scenes.
  - Claude watches for: soul `[CHAT]` lines with a `(...)`, `*...*` or `_..._` span, and spoken lines that start with a stage word (also the unlisted ones: skeptical, cautious, curious, smirking, muttering, absent-mindedly).
  - Pass when: no stage direction reaches chat or voice; dialogue in parentheses ("(get it?)") stays.
  - Sandbox 2026-10-20 (1.1.277): PARTIAL: none in 34 soul chat lines, but the model wrote none in 14 raw replies, so the strip was not exercised.

## Phase 6bp — 1.1.278 fix checks (1.1.278)

Sandbox 2026-10-20 on the 1.1.278 jar (md5 b2ea36c5...), save "Sweep Souls3" (easy, no mob spawning, noon; 00:30-01:01), `tools/sandbox/fix278.py` (fresh floating decks at Z 790-810, X 280-780, and a netherrack floor in the Nether at 594-616, 70, 594-616), replays `frens_1_1_278_6bp_aid_apple.zip` (00:31-00:42), `frens_1_1_278_6bp_goto_door.zip` (00:42-00:49), `frens_1_1_278_6bp_collect.zip` (00:49-00:56) and `frens_1_1_278_6bp_regress.zip` (00:56-01:00). One "Can't keep up!" all session (00:43:06, 2033 ms), from a test mistake: `/execute in minecraft:the_nether run tp @p` picks the nearest player in the Nether, and bots are players, so Fisher and Axer2 were sent there (Nether chunks generated; Fisher suffocated and respawned at spawn with an empty inventory; Axer2 was moved back). `sb_flood` clean (top line "Bot is busy, skipping facing" 170 of 20000). New findings 53-56 in `FIELD_0_6AI_SANDBOX_2026-10-18.md`.

- [x] **Defensive mutual aid without a server freeze (1.1.278).**
  - Sandbox 2026-10-20 (1.1.278): PASS. Jake (fed, iron sword) and Bob (bare-handed, health 13, food 14; not armed and fed as the brief said, because `findDefenseRequest` only answers an ally that is hungry, hurt or hunting, from a bot with a weapon gap or 4 more readiness, so two armed, fed bots never defend each other by design) 15 blocks apart on a railed 41x17 deck, both stopped; a fire-resistant zombie 4 blocks past Bob, 19 from Jake. `Mutual aid: Jake moved to defend Bob against Zombie` 00:34:38 (1 s after the summon; Jake ran 8 blocks in about 1.3 s) and again 00:34:43 (the 4 s cooldown); the zombie (12 health) was dead by 9.2 s, Jake stood still afterwards (moved 1.1 blocks in the next 5 s). 0 `nudgeTowardUntilClose on the server thread` WARN lines and 0 "Can't keep up!" in all three runs. Bow variant: with only a bow and arrows Jake never responded (Bob killed the zombie bare-handed): mutual aid counts only melee weapons, finding 53. With a bow plus an iron chestplate (6 armor makes him combat-capable): `Mutual aid: Jake hands its defend_hostile approach to its own combat` 00:37:07 and 00:37:12, he drew and shot ("Take Aim"), killed it ("One less to worry about." 00:37:16), but one arrow hit Bob ("Ow. That was you.", Bob 13 → 4.5 health with the zombie's hits), finding 54. A first run on a 29x9 deck without a rail knocked the zombie off the edge with the first strike (test artifact).
- [x] **A yes or no to "eat this golden apple?" is heard (1.1.278).**
  - Sandbox 2026-10-20 (1.1.278): PASS. Apple (fresh bot, only a golden apple, health 20) drained to food 5 with the hunger effect: `Desperate food: trying precious food item.minecraft.golden_apple` and one prompt "I can eat Golden Apple, but it's rare. Use it anyway? (yes/no) (manual use) ..." (00:38:51, during the drain, unanswered; asked again at 00:39:11 when the 20 s window ran out, as ruled). "yes" typed in chat 00:39:12 → "Apple: Understood. Using it." the same second, the apple gone and food 5 → 9 within 3 s. Repeat with "no" (00:40:41): "Alright. I'll save it.", the apple kept and food 5 for the 15 s watched; the next desperate try 10 s later did not ask again (30 s deny cooldown). Hand-feed: Apple at food 10, the player holding a golden apple right-clicked her (fork keybind `key.use`): "That Golden Apple is precious. Eat it anyway? [Yes] [No]" (shown twice for one tap, `Precious-food prompt shown` twice in one tick), then `/frens feed confirm <uuid>` (the command [Yes] runs): `Bot Apple accepted food item.minecraft.golden_apple from player RotiWokeman` 00:42:33, food 10 → 14, 0 "(yes/no)" prompts.
- [x] **`/bot go_to` in the bot's own dimension (1.1.278).**
  - Sandbox 2026-10-20 (1.1.278): PASS. Nethy on a 23x23 netherrack floor in the Nether (a netherrack wall with one gap between start and goal), the player in the Overworld: `/bot go_to Nethy 610 70 610 false` → "Nethy can't go there across dimensions." (00:44:37), Nethy did not move. Both in the Nether, `/bot go_to Nethy ~10 ~ ~10 false`: `[PathFinder] mode=unified elapsed=7ms pathLen=18 from=600, 70, 600 to=610, 70, 610 ... status=FULL`, every node on netherrack floor, the route through the wall's gap at x 611; Nethy at 610.5, 70, 610.5 within a second (the tracer moves by segment hops), 0 samples inside the wall, 0 "Can't keep up!".
- [x] **A following bot goes through a closed doorway (1.1.278).**
  - Sandbox 2026-10-20 (1.1.278): PASS. A 7-wide corridor (side walls 3 high) crossed by a 5-wide 3-high stone wall with a closed 1x2 oak door, stone above it; bots start 6 blocks before the door, the player 7 past it; positions and the door state polled every ~0.5 s. A (one bot): `door-open success`, `door-ray ... plan doorBase=480, 120, 800`, through at 3.2 s, `door-close wait: bot too close` then `door-closed` once it was 3 blocks past. B (two bots): DoorB1 through at 2.9 s, DoorB2 waited behind it and was through at 5.5 s; `door-close wait: another player at the door (attempt=3)`, closed after both. C (hinge right): through at about 5 s, closed behind. All three: 0 `door-plan abort`, 0 `head-not-passable`, the door shut at the end, never shut while a bot stood in the doorway.
- [x] **Collect and Go tell the owner (1.1.278).**
  - Sandbox 2026-10-20 (1.1.278): PASS, through the Bot Storage screen (`/bot open Coll`, Actions, search "storage", rows clicked by screenshot pixels). Coll with a lodestone compass and two registered chests (A 625, 120, 799; B 760, 120, 799). Collect A, Stay: "Coll is fast-traveling to the chest (ETA ~7s)." and arrival 7 s later. Collect B during the 3-minute travel cooldown: "Coll is resting 17s before traveling to 760, 120, 799. I'll keep busy meanwhile." and no ETA line (00:55:24). Collect A while B's retry waited: "Replaced the collect at 760, 120, 799 with 625, 120, 799." (00:55:35); the retry then went to A (`travel-wait cooldown expired ... retrying travel to 625, 120, 799`, arrived 00:55:48, "Coll collected 0 items from the chest."). The cooldown-refused Collect kept its withdraw. `/bot open` from 16 blocks shows nothing, finding 55.
- [x] **Follow and woodcut, no regression (1.1.278).**
  - Sandbox 2026-10-20 (1.1.278): PASS. Follow 46 blocks south from 60, 72, 230 over hills (the 1.1.277 route): Fol ended 1.1 blocks from the player, lag up to 8.9 blocks mid-run (1.1.277: 1.1, up to 9.0), 0 "Can't keep up!", 0 `head-not-passable`. Woodcut on fix276's 4-tree deck: `Woodcut detect: durationMs=25 logs=20 ... bases=4` and `durationMs=7 logs=18`, "Got 5 logs from that tree." from W1 and W2, all four trunks felled. The default east route of `fix277.py follow` (not run before) stalled: finding 56.
- [ ] **Bots defend each other in real play without a stutter (1.1.278).**
  - Bradley does: play a night with two or more bots idle near him; let a mob reach one of them (a hungry or hurt one), with a better-armed bot 10-20 blocks away.
  - Claude watches for: `Mutual aid:` lines (`moved to defend` / `hands its ... approach`), `nudgeTowardUntilClose on the server thread` WARN, "Can't keep up!".
  - Pass when: the other bot comes to help and the mob is fought, no WARN, no stutter felt, no bot left walking.
  - Sandbox 2026-10-20 (1.1.278): PASS on a flat deck with one zombie (above); real terrain, several mobs and a bow defender (findings 53, 54) not seen.
- [ ] **Hand-feeding a golden apple asks once (1.1.278).**
  - Bradley does: right-click a hungry bot while holding a golden apple, click [Yes] in chat.
  - Claude watches for: `Precious-food prompt shown` (how many per right-click), `accepted food`, any "(yes/no)" prompt after it.
  - Pass when: one [Yes] [No] prompt per right-click, the bot eats, no "(yes/no)" follow-up.
  - Sandbox 2026-10-20 (1.1.278): PARTIAL: the bot ate and no "(yes/no)" followed, but the [Yes] [No] prompt showed twice for one fork keybind tap (maybe the tap lasted two ticks), and [Yes] was run as its command, not clicked.

## Phase 6bq — 1.1.279 fix checks (1.1.279)

Sandbox 2026-10-20 on the 1.1.279 jar (md5 93768df7...), save "Sweep Souls3" (easy, no mob spawning, noon; 04:04-04:39), `tools/sandbox/fix279.py` (fresh floating decks at Z 894-910, X 296-650), the `bot-actions` logger at DEBUG for the session (sandbox log4j file only, restored after) so `moveRelative-same-cell` lines show; replays `frens_1_1_279_6bq_walls.zip` (04:05-04:16), `frens_1_1_279_6bq_makeroom_craft.zip` (04:16-04:27), `frens_1_1_279_6bq_return_cook_bundle.zip` (04:27-04:33) and `frens_1_1_279_6bq_regress.zip` (04:34-04:38). 0 "Can't keep up!" and 0 `taking IN_WALL damage` WARN lines all session; 28 `moveRelative-same-cell` lines, every one `clamped`. `sb_flood` clean (top line "Bot is busy, skipping facing" 443 of 20000). New findings 57-58 in `FIELD_0_6AI_SANDBOX_2026-10-18.md`. The Pocket installer timeout is unit-tested only (NOT RUN here).

- [x] **A bot's small steps stop flush with a wall (1.1.279).**
  - Sandbox 2026-10-20 (1.1.279): PASS for the drowning rescue, drop sweep and wedge cases; kiting NOT EXERCISED. Drowning rescue: Swimmer in a 3x3 water pool 2 deep under a stone roof, /tp'd facing the north wall 0.5 from it: `moveRelative-same-cell bot=Swimmer from=(330.500, 899.500) to=(330.500, 899.050) result=clamped(330.500, 899.300) penetrationBefore=0.0000 penetrationTarget=0.2700` (the old 0.27 clip, now flush), 9-16 clamped lines per 20 s run, the bot's box (0.6 tall while swimming) never inside a pool wall in 77 samples per run (x 330.91-331.70 between faces 329/332, z flush at 899.3 or 901.7), health 20, 0 IN_WALL. Drop sweep: 3 diamonds summoned at the foot of a 1-wide corridor's back wall, `/bot skill drop_sweep`: picked up from 901.12 (wall face 900), never overlapping. Wedged: Wedge /tp'd into a closed oak door's cell and, separately, onto a bottom stair's step half, `/bot follow`: out of the cell within 0.3 s both times. Kiting: a sword bot with its back 0.2 from the wall and a zombie in front (free, NoAI, immune, and a 200-health zombie held by slowness 255 with knockback resistance) never backpedalled in five runs: 0 same-cell lines for Kiter, positions flat (one run flush at 900.3, never inside), so the combat `moveBackward` path was not shown.
- [x] **Make-room keeps valuables; server-thread crafts never walk to or place a chest (1.1.279).**
  - Sandbox 2026-10-20 (1.1.279): PASS, with findings 57 and 58. Aid make-room (FOOD_PICKUP; a food handoff from an ally needs no room by policy, so the brief's "ally needing food" case cannot drop anything): Fullz with 36 distinct non-food, non-cheap stacks (ingots, gems, iron tools, paper, books...), held at food 0 with the hunger effect, 4 bread at its feet: `No disposable stack available to free inventory space (cheapOnly=true).` and `mutual-aid make-room: Fullz has nothing it may drop, retry in 10 s (kind=FOOD_PICKUP)` every 10 s (5 times in 45 s), the owner line "<Fullz> My pack's full of things I won't throw away." at 04:20:30 and 04:21:10 (40 s apart), the inventory unchanged. A first run with 16 each of raw beef, porkchop and mutton in the pack never reached food 5: it ate them as fast as the effect drained (raw food kept, never dropped). Idle stone upgrade with a full pack, a table 2 blocks away and a chest 12 blocks away: no chest placed, the chest untouched, no walk toward it (the 6-11 blocks moved were the `wander` hobby that started afterwards); Smith2 crafted the stone pickaxe and then `Dropped 13x Cobblestone to free inventory space (cheap=true)`, which the rest of the pass needed (finding 57); with rotten flesh in the pack (Smith3) `Dropped 5x Rotten Flesh to free inventory space (cheap=true)` came first, then the pickaxe and the sword. With diamonds and iron ingots in the pack the "stone" upgrade made a diamond sword, a diamond shovel and an iron axe (finding 58). The refusal line "My pack's full, so I can't craft X right now." was not reached: a cheap stack to drop was always there.
- [x] **A new return starts with fresh stuck counters; jump-sprint counts only a sideways move (1.1.279).**
  - Sandbox 2026-10-20 (1.1.279): PASS. stucktest.py's lane pocket on a fresh deck, base 14 blocks NW. Return 1 (04:28:17): `reached 60 ticks stagnant ... backupAttempted=false` and `Bot Laney2 stuck for 60 ticks, attempting backup/sidestep escape` at 04:28:20; `moving east 1 block(s)` failed, `feet blocked by Oak Fence ... (collision shape)`, `trying jump-sprint escape` then `all backup/sidestep attempts failed` (no false success). `/bot stay`, then return 2 (04:28:52): again `backupAttempted=false` and the backup at 04:28:55, 3 s after the command like the first (no carried counters), `successfully moved to 541, 120, 900` (a real move into the open cell). No flare or firework line in either return; the 180-tick step went to `panic flee failed` and come-recovery.
- [x] **`/bot cook` places a furnace from the bag and cooks without a server-thread sleep (1.1.279).**
  - Sandbox 2026-10-20 (1.1.279): PASS. Cooky with a furnace, 8 beef and 8 coal, nothing placed nearby: `/bot cook beef coal Cooky` → `Placed furnace-like Air at 599, 120, 900` (the line names the used-up stack), "Heading to the furnace...", "Added 1 raw stack to the furnace.", 4 cooked beef in the furnace after 40 s, 0 "Can't keep up!".
- [x] **"No food" counts food in a bundle (1.1.279).**
  - Sandbox 2026-10-20 (1.1.279): PASS. Bun woodcutting with a stone axe and a bundle of 8 bread, no loose food, hunger effect pulses: the 8 bread came out of the bundle at food 11 (t+8 s), it ate at food 5 (5 → 10 → 15), 0 "I'm getting hungry and I have no food." lines, no starvation pause, "Got 5 logs from that tree."
- [x] **Follow, woodcut and doorway, no regression (1.1.279).**
  - Sandbox 2026-10-20 (1.1.279): PASS. Follow 46 blocks south from 60, 72, 230 (the 1.1.277/278 route): Fol ended about 1.9 blocks from the player (1.1.278: 1.1), 0 "Can't keep up!", 1 `head-not-passable`. Woodcut on fix276's deck: detect 24 and 6 ms with logs, "Got 5 logs from that tree." from W1 and W2, all four trunks felled. Doorway (fix278 `door A`): through at 3.3 s, `door-close wait: bot too close` then `door-closed`, 0 `door-plan abort`, 0 `head-not-passable`, never shut on the bot.
- [ ] **A bot kiting at a wall in real combat stays out of it (1.1.279).**
  - Bradley does: fight with a sword-armed bot in a 1-wide tunnel or against a cliff wall at night.
  - Claude watches for: `Bot <name> taking IN_WALL damage`, suffocation, with the `bot-actions` logger at DEBUG `moveRelative-same-cell ... clamped`.
  - Pass when: no in-wall damage while the bot backpedals between swings.
  - Sandbox 2026-10-20 (1.1.279): NOT EXERCISED: the bot never backpedalled against a staged zombie (above); the drowning rescue's same-cell steps were clamped flush.
- [ ] **A full pack in a real session costs no valuables (1.1.279).**
  - Bradley does: play with a bot whose pack is full of mixed loot (ores, ingots, food) while allies get hungry and idle upgrades run.
  - Claude watches for: `to free inventory space (cheap=...)`, `mutual-aid make-room`, "My pack's full..." lines, `Crafted ... using diamond|iron`.
  - Pass when: only cheap stacks are dropped, the owner line at most once per 30 s, no valuable turned into a tool he did not ask for.
  - Sandbox 2026-10-20 (1.1.279): PASS for drops and the owner line; findings 57 and 58 open.
- [ ] **The Pocket voice installer can't hang (1.1.279).**
  - Bradley does: open the Pocket voice setup or install on a machine where it used to stall (or with the network off).
  - Claude watches for: the detect screen and install job finishing or timing out, no frozen screen.
  - Pass when: the screen answers within its timeout and the game stays responsive.
  - Sandbox 2026-10-20 (1.1.279): NOT RUN (unit-tested only).

## Phase 6br — 1.1.280 fix checks (1.1.280)

Sandbox 2026-10-20 on the 1.1.280 jar (md5 85317c82...), save "Sweep Souls3" (easy, no mob spawning, noon; 04:48-05:19), `tools/sandbox/fix280.py` (reuses fix277's canopy decks, flooded pocket, woodcut deck and follow route and fix278's aid deck and doorway, polling every 0.5 s), replays `frens_1_1_280_6br_canopy_tp.zip` (04:49-04:53), `frens_1_1_280_6br_flood.zip` (04:53-05:07), `frens_1_1_280_6br_woodcut_aid_open.zip` (05:07-05:16) and `frens_1_1_280_6br_regress.zip` (05:16-05:19). 0 "Can't keep up!" and 0 IN_WALL lines all session. `sb_flood` clean; its top lines (`Follow status: bot=Canopy` 790, `skip-door-magnet: forcing direct follow` 586 of 20000) come from Canopy left following the player from about 150 blocks for 25 minutes after its runs, a test artifact. New findings 59-60 in `FIELD_0_6AI_SANDBOX_2026-10-18.md`.

- [x] **A following bot gets past an oak canopy row (1.1.280).**
  - Sandbox 2026-10-20 (1.1.280): PASS, six runs on fix277's staged canopy (a grass deck, the area south one block lower, oak leaves at the head cell of the step down). Detour row (X-6..X+6): `/bot follow`, `/bot stop` then `/bot follow`, and `/bot follow-distance 3 Canopy` each took the diagonal step down past the end leaf and were below the row 8.3-8.4 s after the follow command (1.1.277: 55-60 s at `navGoal=293, 119, 661`); the bot crossed the corner, where the Follow status lines pause for 5-6 s, in about 2 s by the 0.5 s track. Wall-to-wall row (X-11..X+11), the same three commands: `leaf-cleared: toward=south stagnant=10`, `Mining complete at class_2338{x=361, y=121, z=661}` 2 s later, below the row in 7.2-7.3 s after the follow command, 5 `head-not-passable` lines a run (1.1.277: 75-77 a minute, no clear). No two consecutive Follow status lines had the same `dist` in any run, 0 "Mining aborted". The bot stopped 2.58 (detour) and 2.95 (wall) from the player: personal space is 3.0 (`horiz <= 3.0` stops it), the rest is momentum; it never walked into him.
- [x] **A `/tp` while following doesn't block leaf clearing (1.1.280).**
  - Sandbox 2026-10-20 (1.1.280): PASS. Canopy following from the detour deck, `/tp` 50 blocks onto the wall-to-wall deck 3 s in, no command after: `External teleport detected for Canopy (moved 50 blocks in 1 tick)`, `leaf-cleared` 3 s later, the leaf `Mining complete` 6 s after the teleport, below the row at 7.2 s, 0 "Mining aborted" (1.1.277: 5 aborted clears).
- [x] **A bot in a flooded pocket climbs onto its ledge and out (1.1.280).**
  - Sandbox 2026-10-20 (1.1.280): PASS. fix277's pocket (1x1x3 under 21 blocks of stone, water at Y 102, the owner 48 blocks away). Stone pickaxe, water one deep: `carving a ledge on north` 04:53:48, `ledge at 420, 103, 659 ready, climbed out=true` 04:53:54 with the bot on ground on the ledge at that 0.5 s poll, then pillar steps and `Surface recovery exit: bot=Flood1 success=true ... 'Reached surface with open sky after 12 steps.'` 58.6 s after the `/tp`. Water two deep: `climbed out=true` 7 s after the carve, surface at 64 s. Bare-handed, one deep: the ledge took 76 s to carve, `climbed out=true` and on ground at once, then it pillared from Y 103 to 115 on its 16 cobblestone and stopped (`Stuck-escape backoff: FloodB1 failed surface recovery 2 times at Y=115 with nothing to build with`): bare hands get nothing from stone, a limit of the set-up, not of the climb. 0 "did not get onto the ledge" / "cannot swim up" lines.
- [x] **Two woodcutters leave each other's tree soil and pillars alone (1.1.280).**
  - Sandbox 2026-10-20 (1.1.280): PASS, with finding 59. W1 and W2 on fix276's 4-tree deck, twice (the second with W2's saplings cleared every 2 s, but neither bot held a sapling in either run, `Woodcut replant skip ... no saplings`, so both runs tested the soil ledger alone): 0 `Mining` lines at any base (Y 119), all 4 bases dirt after both runs, no cell mined under the other bot's polled feet (0.5 s polls, +-1 s), detect 7-27 ms with logs, all trunks felled, both `finished`. The first run left two scaffold dirt blocks at Y 120 (one on a base), finding 59.
- [x] **A bow-armed bot defends an ally and never shoots through it (1.1.280).**
  - Sandbox 2026-10-20 (1.1.280): PASS for 53 and 54, with finding 60. fix278's aid deck (Jake 15 blocks west of Bob, the zombie 4 past Bob, all on one line). Bow and arrows only: `Mutual aid: Jake hands its defend_hostile approach to its own combat` 1 s after the summon (1.1.278: no response). With Bob in the line (bow, and bow plus chestplate) Jake fired no arrow and walked along the line past Bob to about 1 block from the zombie; Bob killed it at 7.7 and 9.0 s (finding 60). Clear-line controls (the zombie summoned between Jake and Bob): with the chestplate Jake shot 3 arrows and the zombie was dead in 6.7 s; bow only, 1 arrow, Bob made the kill. 0 `ff_received` for Bob in all four runs (1.1.278: "Ow. That was you."), 0 "Can't keep up!".
- [x] **An admin opens a bot's inventory from 16 blocks (1.1.280).**
  - Sandbox 2026-10-20 (1.1.280): PASS. `/bot open Opener` with Opener 16.0 blocks away: `BotPlayerInventoryScreen` "Opener's Inventory" open at 1 s and still at 5 s, one `[bot-inv-open]` line. The first try showed no screen only because the script pressed escape right after the command (fix280 now sends without it); the 1.1.278 repro used the same helper. The non-admin "too far away" message was not checked (no second player).
- [x] **Follow, doorway and woodcut, no regression (1.1.280).**
  - Sandbox 2026-10-20 (1.1.280): PASS. Follow 46 blocks south from 60, 72, 230 (the 1.1.277-279 route): Fol ended 2.7 from the player (1.1.279: 1.9), lag up to 9.7 mid-run, 0 "Can't keep up!", 1 `head-not-passable`. Doorway (fix278 `door A`): through at 5.3 s (1.1.279: 3.3, 1.1.278: 3.2), `door-close wait: bot too close` then `door-closed`, 0 `door-plan abort`, 0 `head-not-passable`, never shut on the bot. Woodcut: the two-bot runs above. The east route (finding 56) still stalls, 8.5 blocks behind at a step up into oak leaves (details in finding 56).
- [ ] **A bot follows through a real oak forest, up and down, without stalling (1.1.280).**
  - Bradley does: walk through a dense oak or dark-oak forest on uneven ground with one bot following; climb and drop over one-block steps under the canopy.
  - Claude watches for: `Follow status: bot=<name>` with the same `dist=` for 5 s or more, `leaf-cleared` without a `Mining complete`, `head-not-passable` runs, "Mining aborted".
  - Pass when: the bot keeps up (no stall over 10 s) and never walks into him.
  - Sandbox 2026-10-20 (1.1.280): PASS on the staged step down (both rows) and the south route; the east route's step up into leaves still stalls (finding 56).
- [ ] **A bow defender in a real fight gets its shot past an ally (1.1.280).**
  - Bradley does: at night, with a bow-armed bot and a second bot nearby, let mobs reach the second bot.
  - Claude watches for: `Mutual aid:` lines, `ff_received` lines, arrows used, the bow bot's movement.
  - Pass when: no ally is hit, and the bow bot shoots from a clear stance rather than walking into melee.
  - Sandbox 2026-10-20 (1.1.280): no friendly fire in four staged runs; with the ally in the line it walked in and did not shoot (finding 60).

## Phase 6bs — 1.1.281 fix checks (1.1.281)

Sandbox 2026-10-20 on the 1.1.281 jar (md5 45431a42...), save "Sweep Souls3" (easy, no mob spawning, noon; 05:31-05:46), `tools/sandbox/fix281.py` (fix279's craft stage on fresh decks at Z 940, one fresh bot per run, with an optional number of free main slots), replays `frens_1_1_281_6bs_craft.zip` (05:31-05:43) and `frens_1_1_281_6bs_explicit_follow.zip` (05:43-05:45). 0 "Can't keep up!", 0 IN_WALL, 0 "Missing materials", 0 `using diamond|iron` all session. `sb_flood` clean (top line `Idle hobbies blocked for SmithN: hobbies-disabled` 82). New finding 61 in `FIELD_0_6AI_SANDBOX_2026-10-18.md`.

- [x] **The idle stone upgrade crafts only stone, keeps the cobblestone the batch needs, and stays quiet (1.1.281).**
  - Sandbox 2026-10-20 (1.1.281): PASS. fix279's full pack (wooden pickaxe and shovel, 16 cobblestone, 8 sticks, bread, 31 valuables; 36 of 36 main slots), a table 2 blocks away: the only cheap stack is the reserved cobblestone, so `craft: Smith2 has a full pack and nothing cheap to drop, not crafting Stone Pickaxe on the server thread`, one chat line "My pack's full, so I can't craft stone pickaxe right now.", nothing dropped, nothing crafted (1.1.279 crafted the pickaxe into a non-main slot, then `Dropped 13x Cobblestone` and 18 "Missing materials" lines). The same with 3 diamonds and 5 iron, gold and copper ingots (Smith4, all kept) and with 4 cobblestone (Smith5). Rotten flesh in the pack (Smith3): `Dropped 5x Rotten Flesh to free inventory space (cheap=true).`, `Crafted 1 pickaxe(s) using stone`, the sword refused with one "My pack's full, so I can't craft stone sword right now.", 13 cobblestone kept, no second line at the 10 s retry. With 4 free main slots: 4x `Crafted 1 <kind>(s) using stone`, 0 chat (Smith6); the same with the diamonds and ingots in the bag, all still there after (Smith7); 4 cobblestone: the pickaxe, then a shovel from the 1 left over, the sword and axe logged quietly as short, 0 chat, no valuable used (Smith8). 0 `Dropped .*Cobblestone`, 0 chest placements or chest walks. The brief's "4 crafts with a full pack" can't happen with this set-up by design (nothing but reserved cobblestone to drop; the full-pack line is the 1.1.281 ruling); the free-slot runs cover the 4 crafts. Finding 61: after the room refusal the quiet log names cobbled deepslate as the missing material.
- [x] **An explicit `/bot craft` still answers in chat (1.1.281).**
  - Sandbox 2026-10-20 (1.1.281): PASS. Expl with 3 cobblestone and 2 sticks, no planks, no table within 40 blocks: `/bot craft stone_pickaxe 1 stone Expl` → `No crafting table within 40 blocks of 760, 120, 980` and the chat line "I need a crafting table placed nearby to craft that.", 0 `craft (quiet)` lines.
- [x] **Follow, no regression (1.1.281).**
  - Sandbox 2026-10-20 (1.1.281): PASS. Follow 46 blocks south from 60, 72, 230 (fix277 route): Fol ended 2.7 from the player (1.1.280: 2.7), lag up to 9.8 on the last hop, 0 "Can't keep up!", 1 `head-not-passable`, 0 "Mining aborted".
- [ ] **A bot with a mixed, nearly full pack upgrades to stone in a real game without spending valuables (1.1.281).**
  - Bradley does: play with a bot that carries wooden tools, cobblestone or cobbled deepslate and some iron or diamonds, its pack nearly full, near a table, and let it idle.
  - Claude watches for: `Crafted 1 <kind>(s) using <material>`, `Dropped .* to free inventory space`, "My pack's full, so I can't craft X right now." and how often it repeats, `craft (quiet)` lines.
  - Pass when: only stone tools are made, no iron, gold or diamond is used, no cobblestone it still needs is dropped, and the pack-full line comes at most once per 30 s.
  - Sandbox 2026-10-20 (1.1.281): PASS with cobblestone only; mixed stone types (strict stone needs 3 of one type, deferred in the changelog) not checked.

## Phase 6bt — 1.1.282 fix checks (1.1.282)

Sandbox-checked 2026-10-23 on 1.1.292 (`FIELD_6BT_6CB_SANDBOX_2026-10-23.md`, replay `frens_1_1_292_6bt_6cb_sweep.zip`). Log-only changes not listed here: chest-pull pauses and pending pulls cleared at server stop (backlog 218).

- [x] **Woodcut cleanup with a hotbar full of tools and weapons never swings one at leaves (1.1.282).**
  - Sandbox 2026-10-23 (1.1.292): PASS: sharpness sword in slot 0 + 8 tools, cleanup done in 7 s, no `Mining ... with` a sword/trident/bow line, held axe/dirt/shovel/empty. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: fill all nine hotbar slots of a bot with tools and weapons (an enchanted sword in slot 0), have it fell a tree, and let woodcut cleanup run under the canopy.
  - Claude watches for: `woodcut_cleanup starting`, `woodcut_cleanup target selected: kind=...`, `woodcut_cleanup completed`; the leaf clear itself is skipped with no log line (visual: the held item while it clears).
  - Pass when: the bot never breaks a leaf with the sword or a tool from slot 0; leaves it can't clear are left, and the logs still come down.
- [x] **A full-pack refusal of a stone tool says nothing about cobbled deepslate (1.1.282).**
  - Sandbox 2026-10-23 (1.1.292): PASS: `fix281.py craft full` Smith12, pack-full line once, no `craft (quiet)` line, no deepslate/blackstone line in the log. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: let a bot with wooden tools, cobblestone, sticks and a full pack idle near a crafting table (the 6bs set-up; `fix281.py craft full` in the sandbox).
  - Claude watches for: "My pack's full, so I can't craft stone pickaxe right now." and `craft (quiet) <name>: ...` lines after it.
  - Pass when: the pack-full line comes once, and no `craft (quiet)` line after it names cobbled deepslate or blackstone ("I need 3 cobbled deepslate ...", finding 61).
- [ ] **A chest a bot can't place for storage is logged (1.1.282).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN: no store run placed a chest. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: ask a bot that carries a chest to store its loot where there is no room to place one (a 1x2 tunnel, or standing in water).
  - Claude watches for: `Store chest placement miss: no placeable spot near <pos> (bot=<name>)` or `Store chest placement miss: placed at <pos> but found <block> (bot=<name>)`.
  - Pass when: a placement that fails leaves one of those lines (log-level change; check it only when a store run happens anyway).

## Phase 6bu — 1.1.283 fix checks (1.1.283)

Sandbox-checked 2026-10-23 on 1.1.292 (`FIELD_6BT_6CB_SANDBOX_2026-10-23.md`, replay `frens_1_1_292_6bt_6cb_sweep.zip`). The punctuation guard now covering JSON resources is test-only (no field check).

- [ ] **A fortify run that finds a cavity speaks the callout aloud (1.1.283).**
  - Sandbox 2026-10-23 (1.1.292): Bradley only (audible), in `GUIDED_SESSION_BRADLEY.md`. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: with bot voices on, run fortify on ground that has a small hidden cavity (a pocket or air gap in the wall line).
  - Claude watches for: chat line "Noted a pocket in the wall, flagging it for you.", "Found a tiny cavity here. It's safe but needs eyes on.", "Here's the gap I marked. No mobs, just a small void.", "Spotting the ignored cavity. We can patch later together." or "This nook is harmless, but I'm calling it out for you." (no log line for the sound; audible).
  - Pass when: the chat line is heard as well as read, in the bot's voice.
- [ ] **A flower gift skips a recipient with a full pack (1.1.283).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN (time). See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: have two bots idle together, the giver carrying flowers and the other with every inventory slot full (no slot that takes a flower); then free a slot.
  - Claude watches for: `Mutual aid: <giver> offered a flower to <recipient>; awaiting acknowledgment`.
  - Pass when: no flower offer goes to the full bot (no walk-over or offer line for it), and an offer can come once it has room.

## Phase 6bv — 1.1.284 fix checks (1.1.284)

Sandbox-checked 2026-10-23 on 1.1.292 (`FIELD_6BT_6CB_SANDBOX_2026-10-23.md`, replay `frens_1_1_292_6bt_6cb_sweep.zip`).

- [x] **At sunset a bot comes to its owner more than 48 blocks away (1.1.284).**
  - Sandbox 2026-10-23 (1.1.292): PASS: owner 90 blocks off, `Auto-return at sunset triggered`, `Sunset segment ... finder=unified pathLen=86 -> 55 -> 26`, reached him in 25 s (needs `/bot auto_return_sunset on <bot>`). See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: leave an owned bot working 60-150 blocks from him in the same dimension (no base nearer) and wait for sunset.
  - Claude watches for: `Auto-return at sunset triggered for <name> (day=... tod=...)`, `Sent sunset auto-return notification to ...`, `Sunset segment for <name> finder=... pathLen=... next=...` heading toward him.
  - Pass when: the bot travels to him rather than to a nearer player or ally bot, or staying put.
- [ ] **Voice mute toggles still apply (1.1.284).**
  - Sandbox 2026-10-23 (1.1.292): Bradley only (audible), in `GUIDED_SESSION_BRADLEY.md`. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: in the voice settings, mute two or three voice categories, then unmute one, while bots are talking.
  - Claude watches for: (no log line; audible).
  - Pass when: muted categories stay silent, the unmuted one speaks again, and the choice survives a rejoin.

## Phase 6bw — 1.1.285 fix checks (1.1.285)

Sandbox-checked 2026-10-23 on 1.1.292 (`FIELD_6BT_6CB_SANDBOX_2026-10-23.md`, replay `frens_1_1_292_6bt_6cb_sweep.zip`). Two items need a second, non-op player.

- [x] **A starving strip-mine bot reaches its owner from far away (1.1.285).**
  - Sandbox 2026-10-23 (1.1.292): PASS: owner 102 blocks away got `SM: I'm starving and out of food. Stripmine paused. Feed me, then /bot resume.` See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: start a strip mine (`/bot skill stripmine`, or collect dirt) on a bot with no food, walk 100+ blocks away, and wait until it starves.
  - Claude watches for: chat line "I'm starving and out of food. Stripmine paused. Feed me, then /bot resume." (collect dirt: "I'm starving and out of food. Paused. Feed me, then /bot resume.").
  - Pass when: he sees the line at that distance.
- [ ] **An "Allow once" supply grant works while the owner is away (1.1.285).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN: two setup misses (chest past the 12-block scan; one axe hit `RESERVE_EXHAUSTED`). Use 2 stone axes. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: answer a bot's chest supply ask with [Allow once], then walk far off before it takes the items (or let it re-ask for the same chest and item later).
  - Claude watches for: `[supply] request bot=<name> chest=... item=... qty=... need=... outcome=COVERED_BY_GRANT` (not `OWNER_NOT_NEARBY`), then `[supply] transfer bot=<name> ... moved=...`.
  - Pass when: the bot takes what was granted once, with no second prompt, and the grant is then spent.
- [ ] **Collect re-checks permission on arrival (1.1.285; needs a second, non-op player).**
  - Sandbox 2026-10-23 (1.1.292): Bradley only (second player), in `GUIDED_SESSION_BRADLEY.md`. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: let a second player who may collect from a bot's chest start a Collect, then remove that permission (or `/deop` them) while the bot is walking.
  - Claude watches for: chat line "<bot> arrived, but you no longer have permission to collect from its chest. Nothing was collected."
  - Pass when: the line comes on arrival and the chest is unchanged.
- [ ] **A hand-placed chest on a destroyed record stays unlisted (1.1.285).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: break a chest a bot registered, place a new chest by hand on the same spot, then reopen the bot's chest list or let it look for supplies.
  - Claude watches for: no `[supply]` request or withdraw line naming that chest's position (no log line for the record itself; visual in the chest list).
  - Pass when: the hand-placed chest is not listed or used until a bot places and registers a chest there.

## Phase 6bx — 1.1.286 fix checks (1.1.286)

Sandbox-checked 2026-10-23 on 1.1.292 (`FIELD_6BT_6CB_SANDBOX_2026-10-23.md`, replay `frens_1_1_292_6bt_6cb_sweep.zip`). The stair-escape rewrite was reverted (unreachable path), so there is nothing to check for backlog 146.

- [ ] **Following through oak leaves at a step up, the bot mines the leaf and gets past (1.1.286).**
  - Sandbox 2026-10-23 (1.1.292): FAIL: east route, 19 s stuck, `leaf-action ... BYPASS_STEP` only (no `MINING_STARTED`), the leaf over the bot's own head (jump headroom) never mined, `feet-not-passable` rejects at the step. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: walk up a one-block step whose top is in oak leaves (feet and head height) with a bot following; the fix277/280 `canopy` east route in the sandbox.
  - Claude watches for: `leaf-action: toward=... action=MINING_STARTED target=... stagnant=...` (the old `leaf-cleared` line is gone), then `Mining complete at ...`; `Follow status: bot=<name>` with the same `dist=` for 5 s or more.
  - Pass when: the leaf is mined and the bot is past the step within about 10 s (finding 56: five `leaf-cleared` lines and no mine).
- [x] **A bow-only defender sidesteps and shoots past an ally; a sword-and-bow one melees up close (1.1.286).**
  - Sandbox 2026-10-23 (1.1.292): PASS: bow-only Jake sidestepped 2 blocks, 2 arrows, 0 `ff_received`; sword-and-bow (new `fix280.py aid swordbow`) `profile=sword-sweep`, zombie dead in 2 s. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: fix278 `aid bowarmor` with Bob in the line: once with Jake holding only a bow and arrows, once with a sword as well and the zombie inside 5 blocks.
  - Claude watches for: `Mutual aid: Jake hands its defend_hostile approach to its own combat`, arrows fired, `ff_received` for Bob, Jake's movement.
  - Pass when: bow-only Jake steps sideways or back to a clear line and shoots, never hitting Bob; sword-and-bow Jake inside 5 blocks fights with the sword (finding 60).
- [ ] **A mounted bot under leaves mines them at normal speed (1.1.286).**
  - Sandbox 2026-10-23 (1.1.292): Bradley only (he rides), in `GUIDED_SESSION_BRADLEY.md`. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: ride with a bot on a horse under low oak leaves, and past a few leaves he placed by hand (persistent).
  - Claude watches for: with the `mount-leaf` and `leaf-clear-service` loggers at DEBUG, `Leaf clear: bot=<name> mining=... toward=...` and `leaf-mine start [mounted]: bot=<name> pos=...`; no stuck line while riding.
  - Pass when: leaves take a normal break time (not instant), the tool is picked once, and his placed leaves are left alone.
- [ ] **A supply ask counts a double chest once (1.1.286).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN: `/bot set hunger 3` opened no `[supply] request` in 75 s (two tries). See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: put a known count of an item a bot needs (e.g. 20 cobblestone, split over both halves) in a double chest, and let the bot ask for it.
  - Claude watches for: `[supply] request bot=<name> chest=... item=... qty=... need=...` and `[supply] withdraw ... moved=...`.
  - Pass when: the asked and moved amounts match the total in the double chest, not one half or double.
- [x] **The settings file no longer pins the default pathfinder (1.1.286).**
  - Sandbox 2026-10-23 (1.1.292): PASS (file-save path, not the GUI): key removed before launch, the game saved the file all session and on quit with no `"pathfinder"` line, bots used `finder=unified`. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: on a fresh config, open and save the Frens settings once without touching the pathfinder, then look at `config/settings.json5`.
  - Claude watches for: (no log line; visual) a `"pathfinder"` line in the file.
  - Pass when: the file has no `"pathfinder"` line until he picks one, and bots still use the default pathfinder.

## Phase 6by — 1.1.287 fix checks (1.1.287)

Sandbox-checked 2026-10-23 on 1.1.292 (`FIELD_6BT_6CB_SANDBOX_2026-10-23.md`, replay `frens_1_1_292_6bt_6cb_sweep.zip`). Riskiest: the two-bot tree cleanup (finding 59, cause not proven) and the permission gates, which need a second, non-op player (on a LAN world with cheats every guest is an op, so the gates can't be shown there).

- [ ] **RISKY: two bots cleaning the same felled tree leave no scaffold dirt and no hole in natural soil (1.1.287, finding 59).**
  - Sandbox 2026-10-23 (1.1.292): PASS in 1 run of the 2-3 asked: touching fancy oaks, Wb worked Wa's canopy, deck left with 0 dirt/cobblestone above and 0 soil holes. Not ticked until rerun. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: have two bots fell and clean up the same tree (or two trees whose canopies touch) on grass, both using dirt pillars; repeat two or three times.
  - Claude watches for: `woodcut_cleanup scaffold phase: removeScaffold=... shouldAbort=...`, `woodcut_cleanup completed: ...`, `woodcut_cleanup target failed: kind=... pos=... reason=...`.
  - Pass when: no dirt pillar or scaffold block is left standing (grass-topped ones included), and no natural block under a tree is mined. Any leftover block: note its position and which bot built there.
- [ ] **RISKY: a non-owner can't rename or delete someone else's zone (1.1.287; needs a second, non-op player).**
  - Sandbox 2026-10-23 (1.1.292): Bradley only (second player), in `GUIDED_SESSION_BRADLEY.md`. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: as a second non-op player, open the Base Manager's Protected Zones and try to rename and delete one of Bradley's zones, a base auto-zone (`base:X`) and the player's own zone (e.g. a village it mapped).
  - Claude watches for: chat line "Only the zone's owner, an operator or the host can rename or delete this zone.", "Only an operator or the host can rename or delete a base's automatic zone.", or "No zone named '<name>' found." for a zone hidden from that player.
  - Pass when: only the player's own zone changes; also `/bot zone remove` on a zone with no owner gives no crash.
- [ ] **RISKY: only a bot owner, an op or the host can map a village (1.1.287; needs a second, non-op player).**
  - Sandbox 2026-10-23 (1.1.292): Bradley only (second player), in `GUIDED_SESSION_BRADLEY.md`. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: as a second non-op player who owns no bot, press Map Village in the Base Manager near a village; then again after giving that player a bot.
  - Claude watches for: chat line "Only a bot owner, an operator or the host can map a village."
  - Pass when: refused without a bot, allowed with one.
- [ ] **RISKY: an ownerless wall can be claimed only by an op or the host (1.1.287; needs a second, non-op player).**
  - Sandbox 2026-10-23 (1.1.292): Bradley only (second player), in `GUIDED_SESSION_BRADLEY.md`. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: unclaim a wall (Unclaim in the Base Manager), then as the second non-op player press Claim Wall on it; then claim it back as the host.
  - Claude watches for: chat line "This wall has no recorded owner or builder. Only an operator or the host can claim it."
  - Pass when: the non-op player is refused and the host can claim it.
- [x] **A stopped tool search stays stopped (1.1.287).**
  - Sandbox 2026-10-23 (1.1.292): PASS: stop while the axe prompt was open, then follow: no `[supply]` request/withdraw after, never walked to the chest. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: give a bot a task that needs a tool it lacks (so it starts looking in chests), `/bot stop` while it searches, then give it a new task straight away.
  - Claude watches for: `[supply] tool scan for <name>: ...` before the stop, and no `[supply] request` or `[supply] withdraw` line for that bot after it; the bot's path.
  - Pass when: the bot never walks to the chest or takes the tool after the stop, and does the new task.
- [ ] **A double chest with one half in an unloaded chunk is skipped, not half-read (1.1.287).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN (chunk-edge setup). See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: place a double chest across a chunk border at the edge of loaded terrain, with the item a bot needs, and let the bot ask for supplies while the far half is unloaded; then again with both halves loaded.
  - Claude watches for: `[supply] request bot=<name> chest=... outcome=DENIED(DENY_UNLOADED)`, then a request at the next chest.
  - Pass when: the half-loaded chest is skipped with no partial take and no lost "Yes"; with both halves loaded it is used.

## Phase 6bz — 1.1.288 fix checks (1.1.288)

Sandbox-checked 2026-10-23 on 1.1.292 (`FIELD_6BT_6CB_SANDBOX_2026-10-23.md`, replay `frens_1_1_292_6bt_6cb_sweep.zip`). Riskiest: timed clutter clearing, which changed the shared placement path.

- [ ] **RISKY: fortify or a shelter inside a mapped village places onto short grass, snow and carpet (1.1.288, backlog 147).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN; worth running with the 6bz2 fix. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: inside a mapped village, put snow layers, a carpet and short grass on cells a fortify wall or a shelter will use; run it.
  - Claude watches for: placement reasons `clearing-occupied=`, `failed-to-clear-occupied`, `clearing-busy` in placement failure logs (a few `clearing-occupied` on snow/carpet are normal; `failed-to-clear-occupied` is not).
  - Pass when: the blocks get placed; snow and carpet are mined with a visible break animation and drop their item; grass vanishes instantly; the bot ends holding what it held before (or its block item).
- [ ] **`/bot place` onto snow still works in one command (1.1.288).**
  - Sandbox 2026-10-23 (1.1.292): FAIL: 2 tries, bot walks to the snow cell on the server thread (tick frozen ~14 s, `Can't keep up`), then "An unexpected error occurred trying to execute that command"; same spot without snow placed in 3 s. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: look at a snow layer and run `/bot place crafting_table` (or furnace/chest) for a bot.
  - Claude watches for: (no log line; visual).
  - Pass when: the block is placed on the first command.
- [ ] **An automatic return refused for combat says so once (1.1.288, backlog 148).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN: the notice is on the fast-travel combat gate, needs a lodestone return. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: let a bot's sunset return start while a hostile is near it, and keep the fight going for a few minutes; then type `/bot return` (or the return command) during the fight.
  - Claude watches for: the owner's chat line for the refusal, and its timestamps.
  - Pass when: the automatic refusal appears once, again only after about 3 minutes or when the reason changes (combat → food); the typed command gets its answer every time.
- [ ] **A bot under an oak canopy counts as at the surface; a bot in a cave under one leaf does not (1.1.288, backlog 145).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN: `/bot go_to` did not move the trapped bot, no recovery triggered. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: trap a bot in a pocket under a dense oak canopy on grass, then in a shallow cave whose mouth has a leaf block above.
  - Claude watches for: surface-recovery lines (`[surface]`, `ensureAtSurface`, pillar/stair escape) for each case.
  - Pass when: under the canopy, no dig-up or pillar recovery starts; in the cave, recovery runs as before.

## Phase 6ca — 1.1.289 fix checks (1.1.289)

Sandbox-checked 2026-10-23 on 1.1.292 (`FIELD_6BT_6CB_SANDBOX_2026-10-23.md`, replay `frens_1_1_292_6bt_6cb_sweep.zip`). Riskiest: the shared server-call helper now cancels queued actions on timeout.

- [x] **RISKY: a buried or wedged bot is dug out cleanly (1.1.289, backlog 138).**
  - Sandbox 2026-10-23 (1.1.292): PASS (log): buried farming bot, `emergency snap recovery [fully-encased-suffocation]`, out in ~1 s at 20 HP, 0 `Moved wrongly`, no double placement (stutter not judged visually). See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: bury a farming bot to the head in dirt (or wedge a returning bot in a 1x1 hole) and watch it recover.
  - Claude watches for: burial rescue lines from `BotRescueService` and any `Moved wrongly` / position-correction warnings.
  - Pass when: the bot gets out with no rubber-banding or stutter, and no placement happens twice.
- [ ] **A resume waits for the bot to reach the surface (1.1.289, backlog 179).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN (time). See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: pause a bot's job, then answer "yes" (or `/bot resume`) while it is climbing out of a hole.
  - Claude watches for: chat line "<bot> will continue once it's back on the surface." (once).
  - Pass when: the line appears once and the job resumes after the bot surfaces; a `/bot stop` during the wait cancels it.
- [ ] **Mining tool swaps still happen while walking (1.1.289, backlog 144).**
  - Sandbox 2026-10-23 (1.1.292): PARTIAL: axe for logs, shovel for dirt, hand for leaves; no stone met, pickaxe swap not shown. Not ticked. See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: send a bot on a woodcut or strip-mine job through mixed terrain.
  - Claude watches for: `Tool select:` / `Tool switch:` lines.
  - Pass when: the bot holds the right tool for each block (axe for logs, pickaxe for stone).

## Phase 6cb — 1.1.290 fix checks (1.1.290)

Sandbox-checked 2026-10-23 on 1.1.292 (`FIELD_6BT_6CB_SANDBOX_2026-10-23.md`, replay `frens_1_1_292_6bt_6cb_sweep.zip`). Riskiest: the cleanup scan now goes through the shared chunk reader.

- [ ] **RISKY: woodcut cleanup at the edge of loaded terrain finishes and says it was partial (1.1.290, backlog 168).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN (chunk-edge setup). See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: fell a tree near the edge of view distance (stand so part of the 37-block area around the bot is unloaded), then let cleanup run.
  - Claude watches for: `woodcut_cleanup completed: ... unknownCells=...` with a nonzero count.
  - Pass when: cleanup ends in normal time (not at its deadline), the player line says it couldn't check everything, and no block in the unloaded part was touched.
- [x] **Logs next to a broken bee nest get cut again (1.1.290).**
  - Sandbox 2026-10-23 (1.1.292): PASS: `protection=beehive` with the nest, nest set to air, next run `protected=0` and `Got 5 logs from that tree.` See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: let a bot see a bee nest on a tree (so it's remembered), break the nest, then order woodcut on that tree.
  - Claude watches for: no "beehive" protection reason for those logs after the nest is gone.
  - Pass when: the bot cuts the tree.
- [ ] **Mutual aid waits for a commanded job but not for an idle hobby (1.1.290, backlog 184).**
  - Sandbox 2026-10-23 (1.1.292): NOT RUN (time). See `FIELD_6BT_6CB_SANDBOX_2026-10-23.md`.
  - Bradley does: fill a bot's pack, then trigger aid toward it once during `/bot woodcut` and once while it is idling with a hobby.
  - Claude watches for: mutual-aid make-room lines.
  - Pass when: during the commanded job no item is dropped from its pack; during the hobby, aid proceeds.

## Phase 6cc — 1.1.291 Codex wave on Nirn (1.1.291)

Sandbox-checked with Carpet 1.4.194: startup, End exit, death respawn, smoke, legacy adoption, same-name isolation, host commands, `legacy-state` (replays `frens_1_1_291_end_respawn_carpet.zip`, `frens_1_1_291_host_legacy_state.zip`). These two need Nirn itself.

- [ ] **RISKY: Nirn's first start on 1.1.291 brings back its furnaces, beehives, crafting and hunt history, hunt settings, fortifications and villages (1.1.291).**
  - Bradley does: open Nirn once and play normally for a few minutes (nothing special).
  - Claude watches for: one `Adopted legacy <store> 'Nirn...' as 'Nirn#<hash>...'` line per store that had data, no `.bad-` file in `config/frens/`, and a second start logging none of them.
  - Pass when: bots still know their furnaces and hives, fortification progress is where it was, and hunt depopulation is still set the way it was before.
- [ ] **Nirn's full mod list starts clean with the new respawn hook (1.1.291).**
  - Bradley does: start Nirn (same session as above).
  - Claude watches for: no `Mixin apply failed`, `InjectionError` or `conflict. Skipping` in the startup log.
  - Pass when: the log is clean and a bot that dies respawns as a working companion.

## Phase 7 — Conversation ontology (1.1.196, 1.1.197, 1.1.198)

Observed across several scenes; collect as the session runs.

- [x] **Change-driven cues lead (1.1.196)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 thunder (`weather_thunder` 20:03:04), the next scene's cue 20:04:40 "the thunder just stopped". See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: force a change — wait out rain stopping, cross a biome, let night fall.
  - Pass when: the second scene after the change leads with it.
- [x] **Speech acts rotate (1.1.196)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 cue verbs 20:06:05-20:17:42: needles, voices a worry, points out, share a joke, make a plan, asks, voices a worry, needles, recall, make a plan, asks, joke, points out, needles. No two in a row the same (read from the tap; ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Claude watches for: `act=` on consecutive fired lines.
  - Pass when: consecutive scenes show different `act=` values.
- [ ] **First sightings fire once (1.1.196, 1.1.198)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): FAIL: S5 "the first wolf any of you have seen" in the cue at 20:52:15; after the 21:04 relaunch (no rollover in between) the cue at 21:08:27 said it again. "the first horse" (20:01:06) did not come back: a rollover at 20:14 had saved ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Pass when: "the first wolves any of you have seen" appears once, never again — and survives a restart (the `seen` registry is now persisted per bot in `mind.json`).
- [ ] **No player-as-animal, no raw fact leakage (1.1.197)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): FAIL: S5 cues "took a beating from a on fire" (19:35:34) and "remember when took a beating from a in wall" (20:01:06, 20:16:08), Jake's day memory phrase "took a beating from a in wall", Bob then said "the in-wall took a bite out of ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Pass when: no "the first player x2", and events read as `started woodcutting` / `slew a zombie`, never `started a task (skill:sleep, skill)`.
- [x] **Open threads (1.1.198)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): PASS: S13 (prompts through `ollama_tap.py`). Bob's banter "You see this shovel, Roti?" (08:59:20) opened a thread in `mind.json`; by 09:09 it was `expired: true` and exasperation went 0 to 1. The 09:12:08 s ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: let a bot ask him a question and **ignore it** for 10+ min; separately, answer one.
  - Claude watches for: an `OPEN THREADS` influence — the expired one should produce a one-shot seed anchor ("Bob never got an answer about …").
  - Pass when: expiry raises exasperation and surfaces the anchor; answering clears the thread.
- [x] **Stance shows in the prompt (1.1.198)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S6 Jake's player stance seeded (exasperation 4, curiosity 5): CURRENT STATE 21:07:02 "sulking at being ignored, full of questions for RotiWokeman". See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Pass when: stance clauses ("wary of Roti", "fed up with being ignored", "full of questions for Roti") appear as behaviour over the session.
- [x] **Day memories from consolidation (1.1.198)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 `[19:59:45] mind consolidated bot=2af3f0a5... day=2 memories=7` (three day-2 memories), no `day consolidation failed` all session. events.jsonl 98 and 58 lines, under 200, so the trim was not exercised. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Claude watches for: `[souls] mind consolidated bot=… day=… memories=…`; also `[souls] day consolidation failed for bot … day …` must NOT appear.
  - Pass when: up to 3 memories per day are formed and `events.jsonl` is trimmed to 200 records.
- [x] **Sleep is not journaled (1.1.199)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 the 21:01 sleep wrote one `SLEEP` event per bot and nothing else. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Pass when: a night's sleep does not write four events.

---

## Phase 8 — Gameplay bugs (noisy; RALPH_TASK Lane 2 / P1)

- [x] **Woodcut restart loop diagnostic (1.1.200) — the priority item** — closed 2026-10-18 by triage: SUPERSEDED, root cause fixed in 1.1.216 (HobbyBackoffPolicy) and the axeless question answered in 1.1.229; covered by 6n The woodcut storm is gone (1.1.216), 6n The backoff ladder widens (1.1.216) and 6aa Woodcut from nothing (1.1.229)
  - Bradley does: put an **axeless** bot near trees, idle, and leave it there.
  - Claude watches for: `Idle wooden fallback: starting one-tree woodcut for {}` repeating, and critically the new INFO line `Idle wooden fallback: {} signature changed {} -> {} with {} ticks of cooldown left; resetting` — capture the **A -> B** values verbatim, they name what is invalidating the 240-tick `NEXT_WOODEN_FALLBACK_TICK` cooldown.
  - Pass when: at least one signature-changed line is captured with both signatures, so the fix can be made at the source. Also note whether `I have no axe and can't make or find one` still accompanies it, and answer the product question: **should an axeless bot punch one tree instead of refusing?**
- [x] **Idle hobbies have real shared state (1.1.200 fix, verify)** — closed 2026-10-18 by triage: OBSOLETE, neither watched line can occur: the unavailable-state path was removed in 7123d837 (1.1.200) and FunctionCallerV2/ollama4j were deleted in 1.1.251 (080160e4, 4bb9df49)
  - Claude watches for: `Shared state unavailable for idle-hobbies` and any `NoClassDefFoundError`/`OllamaBaseException` — must NOT appear.
  - Pass when: neither appears in a full session.
- [x] **BotTorchHoldService not firing (P1, diagnostic-first)** — closed 2026-10-18 by triage: SUPERSEDED, logs show the service fires and 1.1.217 added hysteresis; covered by 6o Torch stays up in the dark (1.1.217) and 6o Quit mid-hold does not reload with a torch in hand (1.1.217)
  - Log evidence 2026-09-07/08 (1.1.216): it DOES fire — Jake held 5 times at light 6–7 but yielded within ≤1 s at light 8 (spatial flicker); Bob had `no-torch-in-inventory`. 1.1.217 added hysteresis (keep until light ≥12) — use Phase 6o's torch items; this item passes if 6o's do.
  - Bradley does: follow-mode in a dim cave and at night, with a torch in the bot's inventory.
  - Claude watches for (1.1.202 state-change diagnostics): `[torch-hold] <Bot> verdict=reject gate=<gate> dist=<d> light=<l> mob=<id>` and `[torch-hold] <Bot> verdict=hold gate=none ... action=promoted+held slot=<n> savedSlot=<m>`. Gates: `mode-<M>`, `active-task`, `using-item`, `mounted`, `sleeping`, `light-above-7`, `audible-hostile-8`, `visible-hostile-16`, `no-torch-in-inventory`. Each line prints only when the verdict changes.
  - Pass when: the rejecting gate is named (expected suspects: the 8-block audible-hostile suppression, or foreign-swap cycling against the combat loadout / AutoFaceEntity). Do not fix in-session — collect the reason.
- [ ] **Creeper back-away (P1 — 1.1.202 ships a fix, verify it)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S11 easy, creeper summoned 6-7 blocks from Jake: (a) unarmed `[creeper] Jake decision=FLEE_SPRINT dist=5.4 armed=false`, `[creeper-defense] Jake engaging backoff: creeper at dist 2.5`, no damage; (b) iron sword + shield: ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: let a creeper walk up to a bot (a) unarmed, (b) armed with a sword and shield, (c) mid-skill (mining/woodcut). Do NOT ignite it with flint & steel — proximity swelling is the case that was broken.
  - Claude watches for: `[creeper] <Bot> decision=BACK_AWAY|FLEE_SPRINT|STAY dist=<d> armed=<bool> fuse=SWELLING|IDLE|IGNITED` (prints on decision change) and the `BotCreeperDefenseService` backoff lines (`creeper-defense` logger) — the root-cause fix: the always-on interrupt filtered on `isIgnited()`, which proximity swelling never sets, so it never fired in normal play; it now also triggers on `getFuseSpeed() > 0`.
  - Pass when: the bot backs off in all three cases before the fuse completes, the armed case shows `decision=BACK_AWAY` inside 4.5 blocks, and no explosion damages a bot. Record any case where it still stands still — with the dist/fuse values from the line.
- [x] **Doorway / pressure-plate stalls (observe only)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: Captured S9 22:21:11-22:22:27: Jake and Bob following through a closed oak door at 50, 66, 50, player 7 blocks past it: `door-plan abort: stuck=24 doorBase=50, 66, 50`, `applyMovementInput-reject bot=Bob ... ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: route bots through doorways and over pressure plates during normal play.
  - Claude watches for: `applyMovementInput-reject` bursts, NavHazardCache penalty lines, the 5-min top-cells summary; door-plan jump loops (the 1.1.180 symptom).
  - Pass when: any stall is captured with timestamps and cell coordinates. **Observe only** — the door-plan architecture rework is a multi-day job to discuss with Bradley first.

---

## Phase 9 — Day rollover and memory digest (1.1.201) — do this near the end

- [x] **Tell Jake three things**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 four DMs 19:58:21-19:59:00: goats on a farm, spicy pumpkin soup, scared of deep ocean water, brother Theo. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: DM Jake three distinct, memorable, factual things about himself across the session (≥4 player lines total — `MIN_PLAYER_LINES` is 4).
  - Pass when: at least four substantive player lines exist before the rollover.
- [x] **Sleep through a night**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 `[19:59:45] memory digest bot=2af3f0a5... channel=DIRECT outcome=ok kept=5 lines=12`, no digest failure line. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: sleep, or let midnight pass with a bot that never sleeps.
  - Claude watches for: `[souls] memory digest bot=… player=… channel=… outcome=… kept=… lines=…`; also `[souls] memory digest failed bot=… player=… :` and `[souls] memory digest sweep failed bot=… :` must NOT appear.
  - Pass when: a digest runs with `kept=` ≥ 1 for Jake.
- [x] **`mind.json` inspection**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 Jake `playerMemories` 9 to 14 (the five facts), DIRECT cursor 22 to 48. PARTY `too-few` (lines=1) kept its cursor at 43 (deferred, not an empty or failed digest). See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: after the rollover, open `saves/<world>/souls/v1/<jake>/mind.json`.
  - Pass when: `playerMemories` holds the facts and `digestCursors` has advanced. Cursors advance on **every** outcome except cancellation — an empty or failed digest must still move them.
- [x] **`/bot soul memory Jake`**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 20:00:52 "day 2 · salience 10 · RotiWokeman has three goats named Pip, Pop, and Pud." and the rest, day 2 first, then day 1. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: run it.
  - Pass when: output is `day N · salience S · fact`, newest day first — or the empty-state string `"Jake doesn't remember anything about you yet."` if nothing was kept.
- [x] **Recall in banter**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 cues 20:05:21 and 20:09:31 "RotiWokeman once said: RotiWokeman mentioned a chest is full." (memory:said), Bob's two memories `lastRecalledDay` -1 to 2. Their salience was already at the cap of 10, so the +3 bump could not show. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: keep playing near both bots after the rollover.
  - Claude watches for: a scene whose topic is `memory:said` (player-memory anchors feed `SoulBanterSeed` at weight 4).
  - Pass when: a memory is recalled aloud, and the recall bumps salience (+3, cap 10).
- [ ] **Party-scene attribution (added at final review)**
  - Bradley does: with the **second player** present, run a "bots, …" party scene where both players say something factual.
  - Pass when: each fact ends up in the right player's memories — a fact said by the other player must not appear under Bradley's, and vice versa.
- [ ] **Present truth beats memory (1.1.201 Task 6)**
  - Sandbox 2026-10-18 pass 4 (1.1.271, run 2026-10-19): FAIL: S13 `/msg Bob Remember, I took your shovel away yesterday. What are you holding right now?` (09:29:06). AUTHORITATIVE STATE: held 13x Snowball, carrying a Diamond Shovel. Reply: "Right! I remember now ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Sandbox 2026-10-18 pass 3 (1.1.271): NOT REACHED: not run. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: state something that contradicts a stored memory and ask about it.
  - Pass when: the bot goes with present, authoritative state — the `ABOUT <Player>` block sits after it in the prompt and is framed as untrusted conversational content.
- [x] **`/bot soul digest off` is not amnesia**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S5 `/bot soul digest off` 20:19:45, status "Memory digest is OFF", four new DMs, rollover: Jake's `playerMemories` 14 to 14, every salience down by 1, cursors unchanged. Turned back on 20:22. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: `/bot soul digest off`, then let another rollover pass.
  - Pass when: no **new** memories form, but existing ones still render and still decay. `/bot soul digest status` reports OFF. Turn it back on afterwards.
- [ ] **Privacy guard on `memory`**
  - Bradley does: as operator, run `/bot soul memory <Bot>` for a bot the second player uses.
  - Pass when: it still shows only **his own** memories — an operator cannot read another player's.

---

## Phase 10 — Destructive (last)

- [ ] **Reset during a running digest (added at final review)**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S7 four DMs, then `/time add 24000` and `/bot soul reset Jake` 0.3 s apart (21:25:34-35): "Archived your conversation with Jake, new epoch 1.", no DIRECT digest for Jake ran (the reset landed first), memories stayed empty, no ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: trigger a rollover and run `/bot soul reset Jake` **while the digest is in flight**.
  - Claude watches for: `outcome=superseded` on the digest line; `[souls] memory archive failed for bot … player …` and `memory archive submit failed` must NOT appear.
  - Pass when: memories stay **empty** — the in-flight digest must not resurrect what the reset just archived.
- [x] **`/bot soul reset Jake`**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S7 21:25:35 reset: `/bot soul memory Jake` "Jake doesn't remember anything about you yet."; `mind.json` `playerMemories` 0, `archivedPlayerMemories` 22, `digestCursors` {}. See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: run it, then `/bot soul memory Jake`, then re-open `mind.json`.
  - Pass when: the memory list is empty, `archivedPlayerMemories` holds them, and that caller's `digestCursors` entries are dropped.
- [x] **`/bot soul reset party` mid-banter**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PASS: S7 scene 72cfdd67 line 1 at 21:28:35, `/bot soul reset party` the same second: `scene-playback ... outcome=cancelled delivered=1/3`, "Archived your group conversation, new epoch 2.", archive file `epoch-1-...jsonl`; the epoch-0 ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
  - Bradley does: run it while a banter scene is playing.
  - Pass when: the actor's own party epoch is archived, local + group + banter records archived alongside, no crash, playback ends cleanly.
- [ ] **Toggle everything off**
  - Sandbox 2026-10-18 pass 3 (1.1.271): PARTIAL: S7 `/bot soul banter off` + `local off` 21:29:3x, two salient unaddressed lines, 2 min: no `[souls] local` line, but the Active lane kept evaluating (`banter lane=ACTIVE outcome=vetoed:not-quiet`, `vetoed:nobody-working`). Active ... See `FIELD_0_6AI_SANDBOX_2026-10-18.md`.
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
