# Changelog & History

Historical record and reasoning. `TODO.md` is the source of truth for what’s next.

## Soul ambient/local chat: companions overhear unaddressed chat, opt-in; 1.1.178 (2026-08-26)

Fourth conversational surface on the pilot, and the last one before consolidation. Spec
`docs/superpowers/specs/2026-08-26-frens-soul-local-chat-design.md`, plan
`docs/superpowers/plans/2026-08-26-soul-local-chat.md`. A soul-bound companion standing
near you overhears what you say to nobody in particular and, very occasionally, chimes
in out loud to everyone in earshot — not a whisper back. Rides the 1.1.176 PARTY scene
pipeline exactly like banter did; no new store, no new scheduler behavior.

**The never-consume invariant, first, because it's the property that matters most.**
Unlike every other chat surface in the mod, the local-chat hook never returns `CONSUMED`.
It observes one unaddressed line and returns — `tryHandleNearbyQuestAsk`,
`BotRespawnPromptService.handleChat`, `SkillResumeService.handleChat`,
`FunctionCallerV2.tryHandleConfirmation`, and the terminal
`handleLegacyInlineActionFromRaw` all still run, unconditionally, exactly as before this
feature existed. The chat callback in `Frens.java` needed no new `return` and no new
branch to get this: the hook call sits on the pre-existing fall-through path,
immediately before `handleLegacyInlineActionFromRaw` (the plan's premise was an `else`
branch to add; the real code just falls through, so the ruling was to insert on that
fall-through rather than invent a branch — strictly safer for the invariant, easier to
verify by eye). An explicit address closes any open reply window via a matching
`noteAddressedChat` call at the top of the addressed branch, but records nothing and
still consumes nothing itself — routing to bots proceeds exactly as before.

- **Two units, split by cost and risk.** `SoulLocalMemory` is an always-on, in-memory,
  bounded per-player ring (8 entries, 10 min TTL) of recently overheard lines — no LLM
  call, no output, no disk, keyed by the set of soul-bot UUIDs actually in earshot when
  the line was spoken (a bot reads only what it witnessed). `SoulLocalDirector` is the
  deterministic, default-OFF reaction gate that turns a rare high-salience line into a
  one-bot `LOCAL` scene. They're separable on purpose: the recorder is free and useful on
  its own (it feeds the DM prompt and the banter seed even with reactions off); the
  speaking half is the only part that costs a provider call, so it's the only part gated
  by the toggle.
- **The write-side gate is deliberately asymmetric between the two units.** Recording
  into the ring is gated ONLY by `soulLocalChatEnabled` and the hard rejects (blank,
  <3 words, <~12 chars, a repeat of the player's last line, sender is a bot) — never by
  cooldown, busy, muted, or roster. The expensive half — `SoulSnapshotBuilder.capture`,
  one per roster candidate, feeding both the salience score and the scene grounding —
  sits behind the full cheap-gate chain (`pipeline` → `cooldown` → `busy` → `muted` →
  `player-not-at-ease` → `roster` → `salience` → `danger`). Fix round 1 on
  `SoulLocalDirector` got this backwards: it restructured the method into two `firstVeto`
  calls to stop the capture sweep from running before the cheap gates (correct), but
  accidentally pulled the recording write along with it, so nothing was recorded for the
  entire 6-12 min post-fire cooldown — the always-on memory half went dark exactly when a
  reaction fires. Fix round 2 separated the two tiers again: recording happens right
  after the hard-reject check, unconditionally past that point; the reacting tier (roster
  filter, capture loop, real veto call) begins strictly after. This is the shape of the
  two-tier split described in the spec, restored after one implementation regression.
- **The reply window opens on scene delivery, not scene submission — this changed
  during implementation.** The plan (and the original Task 5 code) opened the 30 s
  bypass window the moment the director submitted the scene. The spec says "on a
  delivered reaction," and the gap matters: a scene that fails to generate (malformed,
  timeout) or whose text and voice are both muted still grants a free bypass window under
  submit-time opening, even though the player received nothing. Task 6 closed the gap by
  adding a `default void sceneDelivered(GroupSceneTurn turn, int deliveredLines)` method
  to `GroupScenePlayback.LineCommitter` (default body, so the interface change is source-
  and binary-compatible with its one production implementer), called from
  `GroupScenePlayback.finish()` — the single terminal point for both FINISH and ABORT —
  right after `sceneFinished`. `SoulRuntime`'s anonymous committer forwards LOCAL scenes
  with `deliveredLines > 0` to `SoulLocalDirector.noteSceneDelivered`, which is the only
  thing that ever calls `openReplyWindow`.
- **Exactly one continuation, and it fails closed.** A `ContinuationTracker` records,
  at fire time, whether the fired scene itself was a continuation of an already-open
  window. On delivery, `consumeShouldOpenWindow` removes and returns that flag — `true`
  only for a genuine first reaction, never for a continuation's own delivery — so a
  continuation can never re-open the window it answered, which would otherwise chain LLM
  calls and speech synthesis indefinitely if a player kept replying. The tracker had to
  be pulled out as its own class because `MinecraftServer` can't be constructed or mocked
  in this test harness, so `SoulLocalDirector` itself can't be exercised end-to-end; a fix
  round caught one more edge on this — `noteAddressedChat` and a player-initiated scene
  both closed the reply-window map but left a pending continuation flag alive, so a
  scene already in flight when the player addressed a bot could still pop a window open
  after the fact. Both callers now clear the tracker too. A window closes on the first
  of: 30 s elapse; the continuation is used; explicit address; the player leaving earshot
  or changing world (added in a fix round — the earshot/world check reuses the same
  `EARSHOT_BLOCKS` radius the witness pass uses); a new reaction firing.
- **Salience is a pure, additive, per-bot score against constants tuned for field
  testing, not architecture.** Hard rejects (above) run before any bot context is built.
  Then: naming a nearby bot NOT in leading position +3 (a leading name is an address,
  already routed away, and scores 0 here), stated intent/plan phrasing +2, keyword
  overlap with the bot's active task or last journal event +2, ending in a question mark
  +2, six-plus words +1, mostly digits/coordinates −2. Fires at ≥4. The threshold and
  every weight live as constants in one place (`SoulLocalSalience`) — this is the
  designated field-tuning surface once real logs exist, not something that should need a
  code review to adjust. Scoring is per-bot so the highest-scoring eligible bot in
  earshot answers (ties broken by proximity), not merely the nearest one; the reply
  window's continuation bypass is keyed to that exact bot too — a fix round caught the
  original implementation OR-ing `windowOpen` into every candidate's eligibility, which
  let a different bot than the one who opened the window ride its bypass.
- **Grounding is the seam that kept the DM pipeline untouched.**
  `SoulTypes.GroundingSnapshot` gained a 6th component, `overheard` (compatibility
  constructors preserve every existing call site), populated by
  `SoulSnapshotBuilder.capture` from `SoulLocalMemory.witnessedBy(botId, playerId, now)`
  — empty whenever the toggle is off, because nothing was ever written. Three consumers
  read it: the `LOCAL` scene's own turn grounding, one optional "overheard" fragment in
  `SoulBanterSeed`, and a bounded (≤200 char) `RECENTLY OVERHEARD` block in
  `SoulPromptAssembler`, omitted entirely when the list is empty. That last one is the
  only DM-pipeline behavior change in the whole feature, and it rides an argument the
  assembler already receives — `SoulConversationService`, `SoulChatRouter`, and
  `SoulMessageDelivery` are all genuinely untouched. With the toggle off, `overheard` is
  empty everywhere and DM prompts are byte-identical to 1.1.177, so the 8B-tuned DM
  behavior stays A/B-able against this feature rather than silently mixed with it.
- **`SceneKind.LOCAL` and the ambient generalization.** Three of the existing
  `== SceneKind.BANTER` branches in `GroupScenePlayback` and
  `SoulGroupConversationService` were really asking "is this ambient?" and became
  `SceneKind.isAmbient()` (voice-mute/combat-abort gating, text/voice surface masking,
  silent-failure notification — renamed `statusUnlessBanter` → `statusUnlessAmbient`,
  which turned out to have 4 call sites, not the 2 originally assumed, all correctly
  caught during implementation). Three branches where content genuinely differs by kind
  stayed explicit switches: the final USER message (BANTER gets a bracketed narrator
  directive with no player words at all; `LOCAL` gets a bracketed "overhearing, not
  addressed" context note followed by the real speaker-tagged line, e.g. `[Bradley is
  talking nearby, not to you...]` then `Bradley: heading to the ravine`), the per-kind
  scene-line cap (new `LOCAL_MAX_SCENE_LINES = 1`, vs. banter's 4 and the ordinary 6),
  and the HEARD-record tagging.
- **The `LOCAL` scene cap of 1 and the ordinary, unmarked HEARD record.** A `LOCAL` scene
  is capped at exactly one line — a comment, not a conversation. Its transcript record is
  the deliberate opposite of banter's: banter's HEARD record carries a `[banter]` prefix
  because a banter seed is a synthetic narrator directive that must never replay as
  something the player said, and the assembler's history replay skips prefixed records
  for exactly that reason. A `LOCAL` record uses the ordinary speaker-tagged form
  (`Owner: message`) with no marker at all — the overheard line genuinely *was* said by
  the player in earshot, so it's byte-identical in shape to a `PLAYER` record and replays
  normally, which is also what gives a windowed continuation its context for free.
- **Config/UI/commands:** `soulLocalChatEnabled` (default false) + "Local" chip (10th
  `GLOBAL_TOGGLES` entry, all four wiring sites — list, index constant, `init()` load,
  `saveSettings()` write — moved together) + `/bot soul local on|off` (operator) and
  `status` (enablement, most recent veto reason, last score, time-to-next-eligible, open
  reply window — the primary field-test tool, matching banter's).
- **Cooldown interplay with banter is unchanged from the spec's design.** A fired local
  reaction re-arms banter's cooldown and vice versa (`notePlayerScene` on both
  directors), so the two ambient surfaces take turns instead of stacking; both submit
  under the same `partyKey(ownerId)` single-flight, so they cannot overlap regardless.
- Suite 488 → 525, all green (baseline for this branch was 488 at the 1.1.177 tag; the
  branch's own running counts moved 507 → 511 → 516 → 523 → 524 → 525 across the ten
  commits below, tracked commit-by-commit in `.superpowers/sdd/2026-08-26-soul-local-chat/progress.md`).
  Final verification for this entry (`./gradlew build -x test && ./gradlew test`):
  **BUILD SUCCESSFUL, 525 tests, 0 failures, 0 errors, 0 skipped.**
  Commits (oldest first): `edf0910` SoulLocalMemory ring, `fbd80d4` SoulLocalSalience
  scorer, `7a56ea9` SceneKind.LOCAL + ambient generalization, `ec9e9d8` grounding seam
  (overheard on GroundingSnapshot, DM + banter-seed consumers), `47aa151`
  SoulLocalDirector (salience gate, cooldowns, reply window), `9e77845` director fix
  round 1 (cheapest-first gating, window bot identity, cooldown merge, `forget()`),
  `279dfd2` director fix round 2 (recording gated only by enablement, not the reaction
  cooldown), `8a810ca` runtime + chat-hook wiring, `90d96c6` wiring fix round 1
  (continuation flag outlives its window, zero-delivery consume), `67ba7a9` config,
  command, UI chip.

**Field-test checklist (local chat, on top of the still-open 1.1.176/1.1.177 lists):**
toggle off → grep the log for `[souls] local` (should be nothing at all) and confirm DM
replies are unchanged; `/bot soul local status` while typing deliberately boring vs.
deliberately salient lines, watching the score and the veto reason move; a fired
reaction end to end with voice, confirmed audible to a second player standing nearby;
ambient text muted → voice only, ambient voice muted → text only, both muted →
`vetoed:muted` and no generation at all; answer the bot inside 30 s → exactly one
continuation, let it lapse → nothing; address a bot explicitly mid-window → window
closes; pick a fight during the window → `danger` veto; cooldown spacing over a session,
with banter and local alternating rather than stacking; `/bot soul reset party`
archiving local records alongside group-chat and banter ones.

**Follow-ups (deferred during this plan, not blocking, not yet fixed):**

- `mentionsBotNotLeading` (salience scorer) uses raw `String.indexOf`, not word-boundary
  matching — a short bot name ("Al", "Sam") would substring-match inside ordinary words
  and inflate salience. Contrived for "Jake," real for short names; a 3-line regex fix.
- `SoulLocalDirector.statusFor` doesn't report enablement itself (spec §8 lists it); the
  `/bot soul local status` command layer already prints enablement separately ahead of
  the director's status, so this may already be satisfied in practice — worth a quick
  look before touching the director.
- `vetoed:roster-lost` (every roster candidate's `capture` throwing) does not push
  `nextEligibleAtMs`, so repeated capture failures would retry on every single chat line;
  banter's equivalent path does push its cooldown. Cheap fix, low blast radius.
- `GroupScenePlaybackTest.banterCombatAbortOnlyAppliesToBanterScenes` now near-duplicates
  the newer `ambientCombatAbortAppliesToAmbientKindsOnly` and kept its stale banter-only
  name after the generalization to `isAmbient()`. Housekeeping only — delete the older
  test.
- `SoulLocalMemoryTest`/`SoulGroupPromptAssemblerTest` (task 1, 4 reports)
  each carry one weak or forward-referencing assertion flagged during review
  (`seedWithoutOverheardLinesIsUnchanged` only checks the literal string "overheard" is
  absent, a weak proxy for "seed unchanged"; `SoulLocalMemory`'s class Javadoc
  forward-referenced the toggle and chat callback this entry now confirms exist).
  Cosmetic; no functional risk.
- Two reporting-only inaccuracies caught during review, left uncorrected in the task
  reports themselves per the ledger's ruling (the code is right, the prose in two task
  reports undercounted call sites): `statusUnlessAmbient` has 4 call sites, not the 3 one
  report claimed; `SoulGroupConversationService` also implements `LineCommitter`
  (a no-op default method there, no functional impact) though one report said nothing
  else does.
- Server-thread cost on the chat hot path (earshot distance scan per unaddressed line,
  one `SoulSnapshotBuilder.capture` per roster bot once the cheap gates pass) is correct
  and bounded on a typical few-bot roster, but was never measured under field load — watch
  it during the checklist above, especially with several soul bots near an active chatter.

**Pre-existing bug found during review, NOT introduced by this work — ships in 1.1.177's
banter feature, flagged here because this is the first time anyone traced it:**
`SoulPlayerActivity.clear()` has no production call site anywhere in the repo (the sole
caller in the whole codebase is a test, `SoulBanterDirectorTest`), and `SoulPlayerActivity`
has no per-player eviction on disconnect either — its `LAST_ACTIONS`/`LAST_CHAT_AT` maps
are process-wide statics that grow across an entire server session and survive a player
rejoin, so a stale "broke Stone 5s ago" can resurface for a returning player. This local-
chat plan added the equivalent disconnect eviction for `SoulLocalMemory` and
`SoulLocalDirector` (Task 6 amendment D), but `SoulPlayerActivity` itself was out of
scope. Recommend a small standalone follow-up: call `clear()`/an per-player evict from
the existing `ServerPlayConnectionEvents.DISCONNECT` handler, same place the new
`forgetPlayerLocalMemory` call landed.

## Soul banter: autonomous companion scenes, opt-in, ambient-gated; 1.1.177 (2026-08-26)

Next soul-track item (group chat → BANTER → ambient → consolidation → actions). Spec
`docs/superpowers/specs/2026-08-26-frens-soul-banter-design.md`, plan
`docs/superpowers/plans/2026-08-26-soul-banter.md`. Interview decisions: opt-in default
OFF; ~8–15 min cadence; topics = witnessed events + situation; audience = player within
24 blocks and at ease. Rides the 1.1.176 PARTY machinery end to end — no DM changes,
scheduler untouched.

- **Director (`SoulBanterDirector`):** deterministic two-phase trigger ticked with the
  scenes (evaluates every 5 s). Gate chain, cheapest first, each with a named veto:
  disabled → pipeline → cooldown ([8,15] min post-fire, [4,8] min initial grace, ~2 min
  retry after danger) → busy (activeGenerations()==0 + no active scene) → muted (both
  ambient surfaces closed = no generation at all) → player-not-at-ease (alive, awake,
  hurtTime==0, no attacker) → not-quiet (90 s since last typed line, noted via
  `SoulRuntime.notePlayerChat` from the chat callback) → roster (group-chat eligibility
  filter, ≥2 bots within 24 of player) → bots-apart (all within 12 of the nearest). Phase
  B re-checks, captures fresh grounding per bot (`SoulSnapshotBuilder`), vetoes on
  hostiles/combat/breaking-free, builds the seed, submits a BANTER-kind turn. Verdicts
  logged only on change (`[souls] banter outcome=...`).
- **Seed (`SoulBanterSeed`, pure):** ≤3 events salience-first (dedup by type,
  DIRECT_CONVERSATION excluded, mild randomness in the 3rd pick) + situation line +
  player-activity line; ≤400 chars.
- **Turn shape:** `GroupSceneTurn` gained `SceneKind` (compat ctor keeps PLAYER);
  banter's HEARD record is `"[banter] <seed>"` — the assembler skips those on history
  replay (a stale seed must never replay as a player utterance) while banter SPOKEN
  lines replay normally, so banter and group chat remember each other. Prompt ends in a
  bracketed narrator directive, never a fake player line; scene cap 4 lines (validator
  parse now takes the cap).
- **Delivery gating (the D6 carve-out):** BANTER scenes respect the ambient text/voice
  category masks, re-read per line — text via `TextLineVisibilityService`, voice via the
  ambient voice mask on top of the Voice master; voice-muted lines skip the TTS render
  entirely; both-muted lines skip without committing. PLAYER scenes keep the soul
  exemption byte-identically. Combat involving audience or speaker aborts the remaining
  banter lines (stale-facts rule).
- **Silent failures:** banter never sends status lines — busy/timeout/malformed are
  log-only. Player scenes still nag as before.
- **Config/UI/commands:** `soulBanterEnabled` (default false) + "Banter" chip (index 8,
  all three GLOBAL_TOGGLES sites moved together; no pipeline reload needed — live
  supplier) + `/bot soul banter on|off` (operator) and `status` (prints the live veto +
  cooldown — the "why is no one talking?" tool). Player-initiated scenes re-arm the
  banter cooldown via `submitGroupTurn`.
- Suite 463 → 488, all green. Commits: SceneKind+cap, seed builder, assembler mode,
  service banter turns, playback gating+combat abort, director+quiet signal, wiring.

**Field-test checklist (banter, on top of the still-open 1.1.176 group-chat list):**
enable the chip (or `/bot soul banter on`), stand near 2 soul-bound bots, wait out the
4–8 min grace with chat quiet; `status` while waiting to watch the veto change;
ambient-mute matrix (text muted → voice-only banter, voice muted → text-only + no synth
in the log, both → `vetoed:muted`, zero generations); pick a fight mid-scene →
`banter-combat-abort`; cooldown spacing over a session; a "bots, ..." scene resets the
banter timer; `/bot soul reset party` mid-banter; toggle off → director goes fully dark.

## Soul group chat (PARTY channel): "bots, ..." scenes with multi-voice paced playback; 1.1.176 (2026-08-25)

Next soul-track item from the roadmap (group chat → banter → ambient → consolidation →
actions). Spec `docs/superpowers/specs/2026-08-25-frens-soul-group-chat-design.md`, plan
`docs/superpowers/plans/2026-08-25-soul-group-chat.md`. Interview decisions: broadcast-
addressed one-turn scenes (no persistent session), roster = speaker's own soul-bound bots
within 32 blocks, delivery to everyone in earshot, one capped orchestration call (parent-
spec ruling), text synced to per-speaker positional voice, additive parallel path (DM
pipeline untouched).

- **Addressing:** `ChatAddressing` resolves leading multi-name runs ("Jake and Sara, ..."
  / "Jake, Sara, ...") — `Resolution.matchedNameIndices` list, back-compat accessor kept,
  single-name behavior byte-identical. Broadcast keywords unchanged.
- **Routing:** `SoulGroupRouter` (pure `eligibleRoster` + `decide` + live `tryRoute`).
  Eligible = soul-profile-bound ∧ owner/operator-authorized ∧ LOCAL, nearest-first, cap 4.
  ≥2 → scene; exactly 1 → downgrade to the ordinary DM path; 0 → deterministic notice;
  souls or `soulPartyEnabled` off → legacy loop exactly as before. When a scene accepts,
  the legacy per-bot loop does NOT also run (non-soul bots stay quiet on a soul broadcast
  — deliberate).
- **Key trick that kept the diff small:** the party key is
  `ConversationKey(ownerId, ownerId, PARTY)` against a SECOND `SoulStore` instance rooted
  at `<world>/frens/party/v1` (new `SoulStore.openAt`). Scheduler, epochs, crash
  reconciliation, corrupt-tail quarantine, bounded history — all reused with zero changes.
  Party records are speaker-tagged ("Bradley: ...", "Jake: ...") so history replays into
  prompts verbatim; party history never merges into DM relationship memory.
- **Generation:** `SoulGroupPromptAssembler` (scene contract, per-bot CAST/STATE blocks,
  shared situation, 12-turn/4k party history, 320 output tokens) → one LLM call in the
  same 1-slot scheduler → `SoulGroupResponseValidator` parses speaker-tagged plain lines
  (chosen over JSON for 3B/8B reliability): unknown speakers dropped, ≤2 lines/bot,
  ≤6/scene, ≤300 chars/line, zero survivors = MALFORMED.
- **Playback:** `GroupScenePlayback`, tick-driven on END_SERVER_TICK. Per line: synthesize
  with the speaker's voice (`SoulVoiceService.synthesizeLine`, counted in the LoadGoverner
  probe), then fan out text + positional audio to every player within 32 blocks of the
  speaking bot, then commit SPOKEN (delivered lines only, per spec). Beat pacing
  (1.5–2.5 s by length) when voice is off/fails; next line renders while the current one
  plays; per-line derived voice groupIds keep each speaker on their own client audio
  source (no client changes). Owner disconnect / `/bot soul reset party` / bot death
  abort/skip via a pure staleness combinator.
- **LoadGoverner:** `activeGenerations()` signature untouched; sum now includes active
  scenes so the floor holds across the whole playback window.
- **Config/commands:** `soulPartyEnabled` (default on, only meaningful with souls on);
  `/bot soul reset party` archives the actor's own party epoch.
- Suite 417 → 463 tests, all green. Commits: ChatAddressing multi-name, party store root +
  types, group validator, group assembler, playback + synthesizeLine, group service,
  router + runtime wiring, chat seam + config + command.

**Field-test checklist (open):** 2-bot scene (bind a second bot via `/bot soul enable
<bot>` — it reuses Jake's profile until a second profile is authored, distinct display
names keep the scene coherent); mixed soul/non-soul broadcast; bystander earshot;
voice-off beat pacing; walking away mid-scene; party reset mid-scene; governor floor
during a scene; `soulPartyEnabled=false` → legacy loop.

## Background installs survive menu close/reopen + "Nice bird!" category leak; 1.1.175 (2026-08-25)

Two field reports from Bradley:

- **Installer progress lost on menu reopen.** Verified from code: downloads DID continue
  in the background (daemon threads outlive the screen) — but progress state lived in the
  screen instance, so reopening showed a fresh idle screen with ACTIVE buttons: a user
  could start a second concurrent download racing on the same .part file. Fix: job state
  moved into the services (shared `InstallJob`, volatile fields; `installAsync`/`pullAsync`
  guard with compare-and-set on a static active-job ref). Screens re-attach to the running
  job on open, show its live progress bar, consume the finished outcome once (green/red
  line), and keep their buttons disabled while any job runs — double-start now impossible.
  Applies to both the Piper installer and the Ollama model manager.
- **"Nice bird!" in chat despite Ambient muted.** Log-verified (latest.log 15:59:27):
  the line went out via `ChatUtils.sendChatMessages` — the voice attempt was correctly
  category-muted (AMBIENT_CHATTER), so `PetProximityReactionService` FELL BACK to chat,
  and chat lines had no category → gated as GENERAL → shown. The voice mute was working;
  the chat fallback laundered the line into a category the mask couldn't see. Fix:
  `sendChatMessages` gained a category-aware overload threaded through the whole chain
  (schedule → sendSingle → text gate AND the voice attempt); the four voice-to-chat
  fallback services now pass their real category (pet/village → ambient_chatter, context
  → reactions, topic → topics_quests). Untagged chat (LLM replies, skills, status) stays
  GENERAL.

## Companion Settings autosave; 1.1.174 (2026-08-25)

Bradley: menu settings should just autosave (destructive things excepted). The new
screens (category menus, engine chooser, model manager) already persisted per click —
`BotControlScreen` was the holdout, batching everything behind its Save button. Now:

- Global toggle chips save on flip; per-bot `CyclingButtonWidget`s got a real change
  callback (`saveSettings()`) instead of the no-op; Save button removed (Close remains),
  including its dropdown-open click branch, footer draw, rect, and the now-unused
  SystemToast import.
- Nothing in this screen qualified as destructive (no deletion/overwrite); the
  auto-respawn "revive by toggling" flow now fires immediately when Auto Respawn is
  flipped on — which is the user's intent when clicking it. Bulk ALL ON/OFF buttons were
  already self-saving. Genuinely action-like flows (Spawn Bots…, Permissions Editor)
  stay explicit buttons.

## Text Adv unified with Voice + soul LLM model manager; 1.1.173 (2026-08-25)

Two Bradley rulings from the Piper field round:

- **Text Adv semantics unified with Voice** ("backwards/conflicting" — correct: the menus
  were duals). One rule everywhere now: the master toggle is a hard kill switch, and
  while it is ON the Adv checkboxes choose which categories are active. `mutedTextCategories`
  (default empty = all show) replaces the short-lived inverse `textVisibleCategoryExceptions`;
  `TextLineVisibilityService` = master && !muted; screen/tooltips reworded ("Same rule as
  Voice"). The "quiet except danger" preset is still one Mute All + two checks away.
  Old exception field in existing settings.json5 is ignored by Gson and drops on next save.
- **Soul LLM model manager** — the Piper-installer treatment for the LLM, per request
  (wants to try a smaller model for speed). `OllamaModelInstaller`: detect (Ollama
  /api/version + /api/tags, total RAM via OperatingSystemMXBean, disk free), streaming
  `/api/pull` with NDJSON progress, `select()` = setSoulModel + runtime hot-reload.
  Offered models verified against registry.ollama.ai (not inferred): llama3.1:8b
  (4.6 GB, current default), llama3.2:3b (1.9 GB, faster), llama3.2:1b (1.3 GB, fastest);
  each row shows size, description, installed state, and a RAM-recommendation warning
  when the machine is under the model's comfortable floor. `SoulModelManagerScreen`
  behind a new "LLM…" chip on the Soul Chat row; when the Ollama daemon is unreachable
  the screen says so plainly and points at ollama.com (the mod does not install Ollama
  itself). Pulls are Ollama-resumable so a failed download retries safely.

## Piper installer field fixes: macOS dylibs + stderr-aware errors; 1.1.172 deployed (2026-08-25)

Bradley's first in-game run of the installer failed on BOTH paths — reproduced and
root-caused offline:

- **"Use Existing" rejection was correct behavior** (pipx Python piper renders but reports
  the WAV on stderr, breaking the stdout contract) — working as designed.
- **"Download & Install" failure was an UPSTREAM PACKAGING DEFECT**: the macOS piper
  2023.11.14-2 archives ship WITHOUT their runtime dylibs (libespeak-ng.1,
  libpiper_phonemize.1, libonnxruntime.1.14.1 — only the .dSYM debug bundle is present),
  and the binary has no LC_RPATH, so dyld dies with "Library not loaded". Windows (.dll)
  and Linux (.so) archives verified complete by listing — Windows users were never
  affected.
- **Fix** (proven locally: piper runs at ~18x realtime, prints the WAV path on stdout):
  on macOS the installer additionally downloads the pinned
  piper-phonemize 2023.11.14-4 archive for the arch (25MB, sha256-pinned like everything
  else), copies the three dylibs beside the binary, and `PiperVoiceEngine` +
  the smoke test now spawn with `DYLD_LIBRARY_PATH` = the binary's dir (the binary has no
  rpath to find them otherwise). Linux gets `LD_LIBRARY_PATH` as harmless insurance.
  Download plan total on macOS is now ~44MB + voice.
- **Smoke-test failures now carry the child's stderr tail** in the on-screen message — the
  dyld "Library not loaded" line would have been visible in-game instead of the vague
  "exited during smoke test (incompatible binary?)".

Deployed **frens-1.1.172**; suite green. Retry path: Bot Control → Soul Voice → Eng… →
Install Piper… → Download & Install (the voice from the earlier attempt is already
present and will be reused).

## Piper installer + soul-voice engine chooser; 1.1.171 (2026-08-25)

Bradley's ruling on the A/B: Piper quality is acceptable for Minecraft — users get the
choice, via an installer with full transparency (sizes, download sources, install
location, system check, pre-installed detection).

- **`PiperInstaller`** (souls/voice) — everything pinned, nothing inferred: release
  2023.11.14-2, per-platform assets (Windows x64 21.4MB / macOS ARM+Intel 18.3MB /
  Linux x64 25.2MB) + voice en_US-lessac-medium (60MB), each with sha256 computed from
  one-time verified downloads; every byte hash-checked before trust (.part file, atomic
  move). `detect()`: OS/arch → asset (unsupported platforms told plainly), disk free,
  existing-piper scan (configured path, prior mod install, ~/.local/bin, Homebrew,
  /usr/local/bin, PATH). `install()`: download → verify → extract (zip via
  java.util.zip with entry-escape guard; tar.gz via system tar) → chmod → **synthesis
  smoke test** → config write (engine=piper, binary, model, enabled) + SoulRuntime
  reload. Smoke test proves the PiperVoiceEngine CLI contract (--model/--output_dir,
  stdin text, WAV path on stdout) — verified live that the pipx *Python* piper 1.7.0
  renders but reports on stderr, so "Use Existing" correctly rejects it with a
  use-Download message instead of failing silently in-game.
- **`PiperInstallerScreen`** — check rows (platform ✓/✗, disk need ~300MB), download
  plan (size, github.com + huggingface.co, checksums-verified note), install
  destination, found pre-installs with origin labels + honest compat warning; progress
  bar during install; all work on daemon threads, screen renders volatile state.
- **`SoulVoiceEngineScreen`** — user-facing choice: Dreamsleeve (cloned voice, GPU,
  best quality) vs Piper (lightweight, CPU); availability detected per engine; select
  writes config + hot-reloads; "Install Piper…" opens the installer. Entry: "Eng…"
  chip on the Soul Voice row in Bot Control.
- Deployed as **frens-1.1.171**. Dedicated-server caveat noted in class javadoc
  (binary would be needed server-side; config sync still stubbed).

## TTS A/B results; 1.1.170 deployed (2026-08-25)

Offline A/B (no game running — contention-free): Piper en_US-lessac-medium (CPU) ~0.97s
per line after a 3s first-load; Dreamsleeve warm (Qwen3 1.7B, Metal) ~2.6s per line for
~3.7s of audio (~1.5x realtime). The in-game 8-10s Bradley measured was multi-sentence
replies rendered whole + GPU contention with an unheld governor floor — both fixed by the
entry below. Piper is ~2.6x faster and zero-GPU but a generic voice (loses the Jake clone).
Samples for listening: `voices/ab-test/`. An in-game Piper trial needs no code — the
`soulVoiceEngine: "piper"` config path already exists (binary now installed via pipx).
Warm-server model load measured at ~3min cold — the mod's spawn-time warm-up request
covers this in practice.

Deployed **frens-1.1.170** (sentence streaming + governor floor + everything prior) to all
three Prism instances; md5 verified.

## Soul TTS: sentence-streaming synthesis + LoadGoverner floor through the render window (2026-08-25)

Bradley: TTS "quite slow, and seems to bug the laptop." Two of the three planned levers:

- **LoadGoverner gap CONFIRMED and fixed.** `activeGenerations()` counted only the LLM
  scheduler's queue+active; the scheduler slot frees before validation/delivery, so the
  entire 8-10s Qwen3-TTS Metal render ran with the probe at 0 — the governor floor dropped
  exactly at peak contention. `SoulVoiceService.activeSyntheses` (AtomicInteger, counted
  inside the worker task so a queue-full rejection can't leak) is now added into
  `SoulRuntime.activeGenerations()`. Probe signature unchanged (LoadGoverner reflects on it).
- **Sentence-streaming synthesis.** Replies are split into sentences
  (`splitSentences`: split on .!?…, fragments <24 chars merge forward, ≥6 segments fold
  into the last) and each segment renders + delivers independently: playback starts after
  sentence one while the rest render. Plumbing: `VoiceDelivery.send` gains
  `groupId` (turn routingId) + `segmentIndex`; `correlationId` is now a derived
  per-segment id (`segmentCorrelationId`, xor-fold — log-traceable to the turn);
  `SoulVoicePayload` carries both new fields; `SoulVoiceClientPlayer` queues same-group
  segments on ONE OpenAL source via `alSourceQueueBuffers` (first segment queued too —
  static attach can't be appended to), resumes a drained source (render slower than
  playback = natural pause), and treats a different groupId as a new reply (old
  stop-and-replace path). `ActiveVoice` record → class holding the buffer list; reap and
  stop paths delete all queued buffers. Mid-reply engine failure aborts remaining segments
  (skipping one would garble the reply). Per-segment `[souls] tts` log lines
  (`spoken-segN/M`) + a `spoken-all-Nseg` total.
- Tests updated for the correlation-id change; **suite 417/417 green**.

## Text Chat "Adv…" — keep-visible category exceptions; 1.1.169 built, NOT deployed (2026-08-25)

Bradley's ruling: inverse semantics from the voice menu. Text Chat master ON = everything
shows; OFF = quiet EXCEPT categories checked in the new Text Adv menu (defaults:
combat_alerts + survival_status, so danger warnings and status lines survive a muted chat).

- `ManualConfig.textVisibleCategoryExceptions` (List of VoiceLineCategory ids; default
  seeded on first load via null-guard) + accessors.
- **`TextLineVisibilityService.isTextAllowed(category)`** — single text-visibility decision
  point: master || exception. Wired into every scripted-text gate:
  `CompanionOverheadDialogueService` (showOverheadLine / tryShowGeneric / tryShowLeafStuck,
  using the call-site tag → category), `BotDialoguePlayer.showSubtitle` (category threaded
  from playSoundInternal), `ChatUtils` chat lines (no tag → GENERAL category). Soul replies
  remain fully exempt (see 1.1.168 entry).
- **`ConfigureTextCategoriesScreen`** (clone of the voice screen, inverse semantics,
  "Check All / Clear All") behind a second "Adv…" chip on the Text Chat row
  (`TEXT_TOGGLE_INDEX = 4`); chip hit-tests before the row toggle, per-chip tooltips.
- VoiceLineCategory now serves both voice muting and text visibility — same taxonomy.

Backlog noted from field round: TTS latency/system load — test a lighter synthesis path
(see RALPH_TASK.md).

## Invisible-text fix (alpha-0 colors), soul-chat text exemption, per-bot copy; 1.1.168 (2026-08-25)

Field round on 1.1.167. World Settings no longer crashes (fix confirmed live) but rendered
with NO labels — root-caused against the disassembled 1.21.11 client: `DrawContext.drawText`
early-returns when `ColorHelper.getAlpha(color) == 0`, so every text draw using a 6-digit
color (`0xFFFFFF` etc., the pre-1.21.x idiom) is silently invisible. Swept the whole mod:
13 draw sites across 8 screens fixed by adding the FF alpha byte (AdminWorldSettingsScreen
title/label/status, ConfigureHobbiesScreen title, ConfigureVoiceCategoriesScreen title+hint,
ZoneNamePopup/Huntables/CompanionSpells/NavigationConfirm titles, BotPlayerInventoryScreen
0x404040 inventory labels). `withColor(0xFFAA00)` untouched — Text style colors are RGB.

Bradley's live-session rulings, applied:
- **Soul Chat replies are EXEMPT from the Text Chat master** (reverses phase B's text
  gating): direct conversation stays visible even when generic dialogue text is off.
  `SoulRuntime` back to the 2-arg `SoulMessageDelivery` constructor; the `textEnabled`
  supplier seam stays for the planned text-category menu. Tooltip updated.
- **"LLM World" vs per-bot confusion**: global tooltip now states the precedence (soul-bound
  bots ignore it; classic path needs global AND per-bot ON); per-bot section renamed
  "LLM" → "Dialogue & AI — per-bot overrides", rows renamed "LLM Chat (this bot)" /
  "Voiced Dialogue (this bot)" with tooltips naming their global master.

Approved next (not in this build): Text Chat "Adv…" menu with keep-visible-exception
semantics (Text off but checked categories — danger warnings, status — still show); TTS
lighter-path A/B test (Dreamsleeve Qwen3 is slow + heavy in play even at low graphics).

## Dialogue/LLM controls consolidation — phase C: panel cleanup (2026-08-25)

- Global panel reordered so the dialogue family is contiguous: LLM World, Recruitment,
  Force-Place, Teleport, then Text Chat → Voice (+Adv…) → Soul Chat → Soul Voice. All
  three index-wired sites (GLOBAL_TOGGLES, init() loads, saveSettings() writes) moved
  together; VOICE_TOGGLE_INDEX 4→5.
- Honest master tooltips: Text Chat documents the voice-only fallback behavior; Voice
  states it covers baked lines AND soul TTS, with per-bot and per-category tiers named.
- Deleted the dead commented-out "Personal Preferences" footer button (field, layout,
  draw, and both click blocks — removed 2026-04-07, never revived).

## Dialogue/LLM controls consolidation — phase B: soul under the masters (2026-08-25)

Per Bradley's decisions: global "Voice" and "Text Chat" are now true masters over the soul
pipeline too, and the command-only soul switches got GUI rows.

- **Voice master gates soul TTS.** `SoulVoiceService` takes an injected
  `masterVoiceEnabled` BooleanSupplier (3-arg constructor keeps the always-true default so
  the class stays Frens-free for plain-JUnit loading); `SoulRuntime.buildVoiceService`
  passes a live `ManualConfig.isVoicedDialogueEnabled()` probe. Voice OFF now silences
  Jake's cloned voice, not just the baked lines.
- **Text Chat master gates soul reply text — without killing voice-only mode.** The gate
  sits inside `SoulMessageDelivery.deliverReply`: chat line suppressed but the turn still
  reports delivered, because `SoulConversationService` only fires the voice subscriber
  after a successful delivery (gating in the guard would have failed the whole turn).
  Injected supplier, same test-freedom pattern. `deliverStatus` (command feedback) stays
  ungated.
- **"Soul Chat" and "Soul Voice" rows** appended to the global panel (indices 6/7 —
  append-only positional list), backed by the same `soulsEnabled`/`soulVoiceEnabled`
  fields as `/bot soul enable` and `/bot soul voice on|off`. Save triggers
  `SoulRuntime.reloadSettings` when they changed (the runtime holds a settings snapshot —
  same pattern as `BotSoulCommands.awaitReloadThenReport`), so the chips are live, not
  write-until-restart. Note: enabling Soul Voice via GUI skips `/bot soul voice on`'s
  path validation; an invalid engine config degrades to disabled-with-warning in
  `buildVoiceService`, tooltip points at `/bot soul voice status`.

## Dialogue/LLM controls consolidation — phase A: make the switches real (2026-08-25)

Bradley: "overlapping, seemingly redundant controls for LLM activation, voice, and text…
I suspect the controls don't even work." Full audit confirmed it; phase A fixes behavior
without touching UI layout. (Phases B/C planned: soul toggles under the Voice/Text
masters + panel regrouping.)

- **LLM single source of truth.** `LLMOrchestrator` now reads enablement lazily from
  `ManualConfig` (`isWorldEnabled()` = global flag; `isBotEnabled(bot)` = effective
  per-bot control). Deleted the push-populated `WORLD_TOGGLES`/`BOT_TOGGLES` maps, which
  caused three defects: GUI toggles write-only until server restart (no re-apply on
  Save), world toggle keyed to overworld only (LLM permanently dead in Nether/End), and
  `/bot llm` writing a store the UI couldn't see. `BotControlApplier.applyWorldToggle`
  removed; `applyToBot` no longer pushes LLM state. Behavior note: unconfigured bots now
  default LLM-off (config default), where the old in-memory map defaulted on — config
  was always the intended authority.
- **`/bot llm world|bot` now persist** — they write `ManualConfig` + `save()`, so command
  and GUI can no longer diverge.
- **Global toggles survive navigation** — `BotControlScreen.init()` reloaded
  `globalValues` from config on every re-init, so clicking "Adv…" or "Permissions" (or
  resizing) silently discarded unsaved chip flips. Now loaded once per screen instance
  (`globalsLoaded`).
- **Text/voice gate rule unified** — `CompanionOverheadDialogueService.showOverheadLine`
  early-returned when Text Chat was off, silencing voice too; `tryShowGeneric` didn't.
  Rule now everywhere: text toggle gates text, voice toggles gate audio, independently
  (matches ChatUtils' voice-only fallback mode).

## AdminWorldSettingsScreen double-blur crash fix; bump 1.1.167 (2026-08-25)

Bradley: pressing "World Settings" in the bot Admin menu crashed the game. Crash report
(`crash-2026-08-25_12.24.30-client.txt`): `IllegalStateException: Can only blur once per
frame` at `AdminWorldSettingsScreen.render:123` — the screen called `renderBackground()`
AND `super.render()` (which backgrounds again); 1.21.11's once-per-frame blur assertion
makes that legacy redundancy fatal. Same crash was already fixed in
`AdminPlayerSettingsScreen` ("manual dim instead of renderBackground()"); this screen was
missed. Applied the identical pattern: `context.fill(0,0,w,h,0xD0101010)` instead of the
`renderBackground()` call. Audited the rest: all other `renderBackground` uses are
overrides (safe), none double-call.

Also diagnosed "muting doesn't work": the jar deployed 10:43 to all three Prism instances
was the PRE-muting 1.1.166 (0 VoiceLineCategory classes, size == the 10:42 build) — the
feature had never run in-game. Bumped `mod_version` → 1.1.167; `frens-1.1.167` carries the
crash fix + all three muting phases. Not yet deployed.

## Voiced-line category muting — phase 3: UI (2026-08-25)

The user-visible half. Feature complete for single-player.

- **`ConfigureVoiceCategoriesScreen`** (new) — popup modeled on `ConfigureHobbiesScreen`:
  2-column grid of the 9 categories with `§a[x]`-style checked = audible buttons, tooltips
  from `VoiceLineCategory.description()`, Enable All / Mute All / Done, hint line
  "Unchecked categories play no audio — text still shows." Writes `Frens.CONFIG` directly
  and saves on every toggle (no server round-trip needed — global config is same-JVM in
  single player; the `sendSaveConfigPacket` call is kept for pattern parity with
  `BotControlScreen` even though that path is stubbed).
- **`BotControlScreen`** — "Adv…" chip on the global Voice toggle row (index
  `VOICE_TOGGLE_INDEX = 4`), drawn beside the on/off chip with its own hover tooltip; its
  hit test runs before the row-wide toggle hit test. Opens the new screen with this screen
  as parent.
- **`RALPH_TASK.md`** — backlog section "Multiplayer Voice Muting": per-player masks via
  the `VoiceLineMuteService.isMuted(category, viewer)` seam + per-recipient send loop, and
  the prerequisite fix of the stubbed `configNetworkManager`/`ConfigJsonUtil` sync.

Manual test plan (in-game, Bradley): expand global toggles in Bot Control → Voice row →
Adv… → mute Ambient Chatter → confirm idle banter shows text only; `/bot sound_test`
still audible (force path deliberately ungated); mute-all then a rescue/combat moment
stays silent with text intact; settings.json5 gains `mutedVoiceCategories`.

## Voiced-line category muting — phase 2: direct-caller categories (2026-08-25)

All ~11 direct `BotDialoguePlayer` call sites now pass explicit `VoiceLineCategory` instead
of falling back to the coarse sound-id bucket. New category-aware overloads of
`tryPlayDialogue(Detailed)` added for the text-lookup path. Assignments: rescue shouts ×6 →
survival_status; combat callouts + hurt grunts → combat_alerts; pet/villager reactions,
touch chat, ambient chatter ×3 → ambient_chatter; context reactions (weather/wake-up etc.)
→ reactions; topic lines + quest dialogue → topics_quests; dog-walking → skill_task. The
only remaining fromSound fallback is `ChatUtils.sendSingleMessage` chat auto-voicing
(mount/lead lines etc.) → general, by design. Phase 3 (UI screen) next.

## Voiced-line category muting — phase 1: core gate (2026-08-25)

Baked (pre-TTS) voice lines can now be muted by category, audio-only — overhead text,
subtitles, and chat fallback are untouched. Design: global scope (settings.json5), tag-based
categories grouped into 9 buckets, multiplayer deferred behind a seam.

- **`VoiceLineCategory`** (new, ChatUtils) — 9 user-facing categories (combat_alerts,
  ambient_chatter, reactions, skill_task, survival_status, sunset_travel, topics_quests,
  enchanting, general). `fromTag(...)` maps the overhead-dialogue call-site tags (exact table
  plus `sunset-`/`follow-`/`gear-`/`snowball-fight`/`hobby` prefix rules, unknown → general);
  `fromSound(...)` is the coarse sound-id-prefix fallback for chat auto-voicing. Ids are the
  stable config keys — never rename, append only.
- **`VoiceLineMuteService`** (new) — single mute decision point. `isMuted(category, viewer)`
  reads only the global mask today; the `viewer` param is the seam for per-player masks in
  multiplayer later (backlog: per-player masks + fixing the stubbed `configNetworkManager`
  sync — note the whole SaveConfigPayload path is a no-op stub, so ALL global config is
  effectively single-player today).
- **`ManualConfig`** — `mutedVoiceCategories: List<String>` + load null-guard + accessors.
- **`BotDialoguePlayer`** — gate at the top of `playSoundInternal` returning `DISABLED`
  (same result as voice-off, so caller fallback logic is unchanged); category-aware
  `playSoundForBot(Detailed)` overloads. `forcePlaySound` (`/bot sound_test`) deliberately
  ungated.
- **`CompanionOverheadDialogueService`** — threads its `tag` into
  `tryPlayVoicedOverheadLine` → `playSoundForBotDetailed(bot, sound, fromTag(tag))`, so ~30
  call sites get correct categories with zero call-site churn.

Phase 2 next: explicit categories for the ~11 direct `BotDialoguePlayer` callers. Phase 3:
`ConfigureVoiceCategoriesScreen` + "Advanced…" button beside the global Voice chip.

## Soul voice: loudness parity + cloned from the baked dialogue voice (2026-08-25)

First live-play feedback on the Dreamsleeve engine ("works... really quiet; should be as loud as
the baked-in voice audio, and should use that voice"). Two fixes:

- **Loudness parity.** The baked OGG lines play at volume 0.8 in `SoundCategory.VOICE` with
  Minecraft's gentle linear falloff; the synth voice was riding the PLAYERS slider with
  OpenAL's default inverse-distance model (reference distance 1.0), which crushed gain within
  a few blocks. `SoulVoiceClientPlayer` now mirrors the baked path on all three axes: gain =
  master × VOICE × 0.8 for positional lines, `AL_LINEAR_DISTANCE_CLAMPED` on our (thread-local)
  context with reference distance 2 / max distance 14 / rolloff 1. Radio stays master × VOICE
  × 0.6, flat.
- **Voice re-anchored to the mod's own voice.** Jake's clone reference is now built from the
  baked dialogue lines themselves: three of the longest ambient clips concatenated
  (`ambient_same_tree__01` + `ambient_giant_statue__01` + `ambient_forgot_something__01`,
  11.85 s @ 24 kHz mono → `voices/jake/jake-baked-ref.wav`, committed) with their verbatim
  subtitle transcript as `ref_text` — so the LLM voice and the scripted lines are the same
  voice. Smoke-tested against the warm server: identity gate sim=0.992, first take, 8.2 s
  render. Live config updated (the Falx Carius anchor retired).

Suite 417/417. Known: response latency is real (LLM generation + ~8-10 s synth per line);
sentence-streaming synthesis remains the deferred latency lever.

## Dreamsleeve voice-clone engine for soul TTS (2026-08-24)

Bradley's correction after voice v1 shipped: this machine already runs a proven TTS stack — the
Dreamsleeve warm server (`~/pontus/openmw-forge/dreamsleeve`, Qwen3-TTS 12Hz 1.7B 8-bit on
MLX/Metal via the `qwen-tts` venv), the same voice-clone system that voices Casca in OpenMW —
and the v1 engine choice should have started from an audit of it instead of assuming Piper.
Nothing to install: new `DreamsleeveVoiceEngine` behind the existing `SoulVoiceEngine` seam
spawns that server on a PRIVATE Unix socket (never Dreamsleeve's daemon socket — OpenMW and
Frens coexist) and speaks its newline-JSON protocol: `op:"speak"` with the voice-anchor
reference clip + transcript (cloned per line, temperature 0.3 for identity stability) and an
`out_file` the server renders a dry normalized 16-bit mono WAV into via atomic rename; the
engine polls, reads, deletes. Same lifecycle discipline as the Piper engine (engine-thread
confinement, drained pipes, non-blocking idempotent close). A fire-and-forget warm-up request
starts the model load at engine build so Jake's first reply doesn't pay it all.

Config: `soulVoiceEngine` ("piper" default | "dreamsleeve"), `soulVoiceDreamsleeveDir`,
`soulVoiceRefAudio`, `soulVoiceRefText`; validation branches per engine (pure
`validateVoiceConfig`, tested); `/bot soul voice status` now names the engine. Jake's anchored
voice on this machine: the vanilla imperial-m bank's calm clip (Captain Falx Carius) — swap the
ref clip/transcript to re-anchor, which is exactly the per-bot "anchored personal voice" seam
the spec reserved. Suite 417/417.

## Soul voice: final whole-branch review fixes (2026-08-25)

Four findings from the final review of `feature/soul-generated-voice`, fixed in one wave:

- **Critical** — `PiperVoiceEngine.alive()` returned `!closed && !lastStartFailed`, and
  `lastStartFailed` latched `true` on any synth failure with no way back except a
  successful synthesis. Since `SoulVoiceService.onSpoken` gates every attempt on
  `engineAlive()`, one timeout/crash/EOF permanently silenced voice — the process restart
  in `ensureProcess()` and the 4-strikes self-disable backoff were unreachable. Deleted
  `lastStartFailed` entirely; `alive()` is now just `!closed` (the engine is always
  retryable). Health/backoff policy already lived correctly in `SoulVoiceService` via
  `VoiceBackoffPolicy` — it just couldn't run.
- **Important** — `runSynthesis`'s failure log used `ex.getClass().getSimpleName()`, but
  `Future.get()` wraps engine failures in `ExecutionException`, so a Piper timeout logged
  `outcome=failed-ExecutionException` instead of `failed-TimeoutException`. Now unwraps a
  non-null `ExecutionException` cause before taking the simple name.
- **Important** — `SoulVoicePcm.Reassembly.accept` sized a `byte[chunkCount][]` from a
  network-controlled varint with no upper bound. Capped at 256 chunks, rejected before any
  allocation.
- **Important** — `SoulVoiceClientPlayer.onPayload` now wraps its body in
  `catch (Throwable)` so a malformed payload can never reach the Fabric network receiver.
  `play()`'s mode-resolution ternary previously let an unrecognized mode byte fall through
  to the positional branch, NPEing on a null last-known position after the AL source/buffer
  were already allocated (leaking them); any byte other than `MODE_POSITIONAL` now
  unconditionally resolves to radio.

Two new tests: `SoulVoiceServiceTest` proves a post-failure engine (fails once, alive()
always true) still delivers the next line; `SoulVoicePcmTest` proves chunkCount 257 is
rejected without retaining reassembly state. Full `./gradlew build -q` (with tests) green.

## Soul generated voice v1 (2026-08-24)

Per `docs/superpowers/specs/2026-08-24-soul-generated-voice-design.md`: Jake’s committed soul
replies are synthesized locally with Piper (CPU, mod-owned long-lived subprocess) and played
client-side through a dedicated OpenAL context — positional from his body when LOCAL, flat
quieter "radio" when REMOTE. Text-first always: voice subscribes at the commit-spoken point via
the new `SoulConversationService.SpokenListener` seam and any failure drops audio only.
Off by default (`/bot soul voice on|off|status`, up-front path validation). Voice requires `/bot soul voice on` after configuring `soulVoicePiperBinary`/`soulVoiceModel`. The client protects game audio by refusing to play (with a WARN) if the OpenAL thread-local-context extension is unavailable. `[souls] tts`
lines join the turn’s routingId chain with synth time kept separate from LLM/delivery time.

## cancelPlayer implemented: disconnect cancels in-flight soul generations (2026-08-24)

Second item off the deferred soul-track pile. `SoulRuntime.cancelPlayer` was an explicit no-op
stub from the pilot ("no per-player in-flight-generation registry exists yet"); the disconnect
hook in `Frens` has called it all along. It turned out no separate registry is needed — the
scheduler's queue/active maps are keyed by `ConversationKey`, which carries the player id, so
they ARE the registry. New `SoulGenerationScheduler.cancelForPlayer(UUID)` (same collect-under-
lock / side-effect-after-release discipline as `invalidate`/`close`) cancels the player's active
calls via `Call.cancelNow()` and completes their queued jobs with `CANCELLED`, returning the
count; `cancelPlayer` delegates and logs `[souls] cancelPlayer player= cancelledGenerations=`
only when something was actually stopped. Payoff beyond hygiene: a disconnected player's reply
was already undeliverable (delivery guard fails closed), but the Ollama generation kept burning
GPU — and, since 0.2.0, kept holding LoadGoverner's soul stage floor — until completion. Now it
stops immediately. 3 new tests (per-player selectivity active+queued, zero-count idle path,
runtime delegation). Suite 384/384.

## Bot places its own bed even when the commander is already asleep (2026-08-24)

Bradley's field report: a bot carrying a bed, with no free placed bed in range, never attempted
to place it — it "waited out" the night and logged off. Root cause: `SleepService.sleep`'s
nearby-sleeper suppression (`isAnyPlayerSleepingNearby` within 32) returned before the placement
branch whenever any player was already in bed — the common case, since the sleep hint invites
typing zzz *from* the bed. The suppression's own comment ("the sleeping player will advance time
to morning") was backwards: bots count toward the players-sleeping percentage, so a bot waiting
awake is precisely what blocks the sleeper's night from advancing.

Fix: suppression now applies only when the bot would have to *craft* a bed first (burning wool
and planks is still wasted work given the logoff fallback) — a bot already carrying a bed
proceeds to place it and sleep. Decision extracted to the pure
`SleepBedCandidatePolicy.waitOutNearbySleeper(canSleepNow, playerSleepingNearby, hasBedItem)`
with 4 tests. Also corrected the misleading handoff message ("Nearby bed is taken") to "No free
bed nearby — setting up my own." for the none-placed case. Suite 381/381.

## Thin sleep hint + one correlation id per soul turn (2026-08-24)

- **Sleep hint restyled (Bradley: "gets in the way, noisy").** The in-bed "Frens need to sleep"
  notice was a bordered two-line box floating above the Leave Bed button; it is now a single
  thin translucent strip pinned across the very top of the screen — one line ("Your Frens need
  to sleep too — type zzz in chat to send them to bed.", with a compact fallback for narrow
  scaled widths), ~17 px tall, softer alpha, no border box. Same `shouldRender` gating
  (sleeping + SleepingChatScreen), test unchanged.
- **routingId on AcceptedTurn (first of the deferred soul-track pile).** The router's
  `routingId` now travels inside `AcceptedTurn` (new trailing component; old 7-arg constructor
  delegates with a fresh id, so existing call sites compile unchanged) and
  `SoulConversationService.submit` adopts it as the turn's correlation id instead of minting a
  second one. Every `[souls]` line for one turn — `routing`, `turn`, `knowledge`, generation,
  `delivery`/`delivery-recheck` — now joins on a single id, ending the timestamp-matching
  exercise in log reconstruction. New end-to-end test asserts the id survives into the provider
  request and the delivery token. Suite 377/377.

## Cross-mod soul load probe for LoadGoverner (2026-08-24)

Frens half of the LoadGoverner rework (separate repo, `~/pontus/LoadGoverner`, reworked same
session per `docs/AUDIT-2026-08-23.md`): a stable static probe
`SoulRuntime.activeGenerations()` — soul generation calls currently queued or active, 0 when the
runtime is off — backed by new `SoulGenerationScheduler.inFlightCount()` (queued + active under
the scheduler lock). LoadGoverner 0.2.0 reflects that exact signature
(`integrations/FrensSoulProbe`, no compile dependency either way) and holds a transient stage-2
floor while the count is positive, so the game sheds load *before* the LLM contends for the GPU
instead of reacting after ticks spike. Do not rename the method without updating the probe.
Suite 376/376.

## Whisper routing + leading-name quirk fix (2026-08-24)

The two remaining addressing-surface items on the soul track.

- **`/msg` / `/tell` / `/w` whispers now route to the soul pipeline.** The 1.1.140 boundary
  ("whispers are plain vanilla, souls listen on chat only") is lifted: `createFakePlayer`
  overrides `sendChatMessage(SentMessage, boolean, MessageType.Parameters)` — the exact method
  vanilla `MessageCommand` calls per recipient (verified in the 1.21.11 named jar, along with
  `MessageType.MSG_COMMAND_INCOMING`, `RegistryEntry.matchesKey`, `SentMessage.Chat.message()`,
  `SignedMessage.getSender()/getSignedContent()`). An incoming whisper whose sender resolves to
  a live non-bot player with non-blank content is handed to the existing exclusive
  `SoulChatRouter.tryRoute` gate — same DIRECT conversation thread as a public `Jake ...` DM
  (continuity preserved across surfaces), and no delivery changes needed since soul replies were
  already sent privately to the asking player. Everything else (console/command-block
  Profileless whispers, bot-to-bot, souls off, unbound profile) leaves the whisper purely
  vanilla. The decision table is the pure, unit-tested `SoulChatRouter.isWhisperEligible`;
  the override exits on the message-type check first since it also fires for every broadcast
  chat line delivered to the bot. Routing logs `[souls] whisper bot= player= outcome=`.
- **Leading-name quirk fixed; addressing rules extracted pure.** `Frens#resolveChatTargets`'s
  token matching + prompt extraction moved to the new pure `ChatAddressing` resolver
  (15 unit tests; `Frens` just maps the returned name index/broadcast flag onto live bots, and
  its private `normalizeToken` moved along). Matching is byte-identical to before (normalize,
  first broadcast keyword or bot name wins). Prompt extraction changes only for non-leading
  matches: a leading name still sends the tail (`Jake come here` → `come here`, so every
  existing command phrasing is untouched), but a name matched later now sends the **full
  trimmed message with the name in place** instead of the tail. This fixes both observed
  quirks: `Ping, Jake` previously produced an empty tail and never routed at all; a
  mid-sentence `can you tell Jake to come home` routed only the garbled tail `to come home`.
  The bot knows its own name, so full-sentence prompts stay meaningful for souls, quest
  matching, and legacy LLM routing alike.

Runbook surface-boundary section rewritten accordingly. Suite 374/374 (was 354); build green.

## Item frames/displays described; memory v3 disproof-on-revisit (2026-08-24)

Two items: the proactive fix for the armor-stand look-alike suspect, and the next soul-track
line item.

- **Item frames and item displays are now described.** Within the scan box and canSee-gated:
  `Item frames holding: Clock, 2x Map` and `Item displays showing: ...` (via
  `ItemFrameEntity.getHeldItemStack` / `ItemDisplayEntity.getItemStack`, both verified in the
  1.21.11 named jar). If the "geared stands" are decor-entity look-alikes, their contents now
  surface through these lines regardless; and map walls / frame decor become conversational
  either way. The diagnostic line evolved to `[souls] displays stands= visible= frameItems=
  displayItems= lines=`.
- **Memory v3: disproof-on-revisit.** Each capture, remembered places inside the current scan
  box that the bot can actually see (line of sight — never through walls) are checked against
  the real block; a mismatch (chest mined out, furnace moved) drops that memory. Store gains a
  synchronous cached peek (`cachedKnowledgeMemory`, mirroring the `cachedState` pattern) so the
  server-thread capture can read memory without blocking, plus `removePlaces` on the writer
  thread; the removal policy is pure (`SoulKnowledgeMemoryOps.removePlaces`) and unit-tested.
  Grouping helper `SoulItemDescriber.groupCounts` is pure and tested. Suite 354/354.

## Armor stands: source-verified equipment path + look-alike census (2026-08-24)

Bradley questioned whether the armor-stand model was understood deeply enough. Went to the
1.21.11 yarn-named jar and verified at the bytecode level: `ArmorStandEntity extends
LivingEntity` directly (not a mob, not a player), it has no `getEquippedStack` override, and
its own `equip()` — the code that runs when a player dresses a stand — writes the same
`EntityEquipment` field that `getEquippedStack` reads. Conclusion: for vanilla stands our scan
provably reads real gear. The remaining hypothesis is look-alikes: modern decor mods and
datapacks dress rooms with item/block `DisplayEntity`s or item frames that read to a player as
"armor stands with gear" while the actual `ArmorStandEntity` nearby is bare. The `[souls]
armorstands` diagnostic now also counts `displayEntities=` and `itemFrames=` in the scan box —
one session's log now either confirms the look-alike theory (slots=0 + displays>0) or pins the
failure on the model ignoring good prompt data (slots>0 with a described line).

## Armor stands round 3: canSee visibility + per-stand diagnostics (2026-08-24)

Bradley refuted the glass-display-case theory (no glass near those stands), and a closer read of
the timeline agrees: the log showed `visible=2` around the question and Jake still said
"Nothing" — so the LOS filter was not the cause and the earlier "solved" call was premature.
Two changes: entity sight now uses the codebase's proven `EntityVisibilityUtil.canSee`
(eye-to-eye; the feet-block raycast graded stands invisible from angles where the body was
plainly in view — likely the intermittent visible=1), and each visible stand now logs
`[souls] armorstand pos=… slots=N line=…`. Next session's log decisively splits the remaining
mystery: slots=0 on a stand the player calls "geared" means the displayed gear is not real
armor-stand equipment (suspect: a decor mod rendering display entities over bare stands —
several are installed); slots>0 with a line means the 8B ignored good prompt data.

## Field-test 3 fixes: glass sight lines, cap 16, drop noise, look-target recency (2026-08-24)

Third field test on 1.1.153. Confirmed working: graph built (1255 craftable / 1583 drop tables in
the log), look-target ("You're looking at a Chest", carpet by name), "what can that do?" on the
enchanting table, drops retrieval firing. Four diagnosed misses:

- **Armor stand mystery SOLVED by the diagnostic line**: `armorstands inBox=2 visible=1` while
  Jake said "Nothing" — the geared display stand was LOS-filtered because glass counted as
  opaque (display case), and the bare visible one made "Nothing" technically true.
  `hasLineOfSight` now steps past up to 4 non-opaque obstructions (glass, panes), so display
  cases read as see-through everywhere LOS applies (facilities and stands).
- **Facility cap 10→16** — third crowd-out casualty: potted plants and the campfire lost slots
  in a facility-dense base room ("do you see flower pots?" → "No", campfire flickering between
  turns). The regression test now floods 18 kinds.
- **Silk-touch self-drops filtered** — "Diamond Ore drops: Diamond Ore, Diamond" pushed the
  model into "Diamond Ore and Diamonds"; self is dropped whenever real drops exist.
- **Look-target recency** — "The Chest again" repeats came from history over-attention; the
  Right-now PRESENT MOMENT line now repeats "; the player is looking at X" in the freshest slot.
- **New retrieval diagnostic**: `[souls] knowledge correlationId=… lines=[…]` logs exactly what
  the retriever injected, so the unexplained "Zombie drops → Raw Beef" answer can be split into
  data-vs-model next session.

Suite 352/352 green.

## Knowledge graph v2: drops, mob topics, deictic retrieval, natural blocks (2026-08-24)

The approved v2 pass on the graph:

- **Drop edges for blocks and mobs.** Loot tables walked once at build via
  `LootTable.CODEC.encodeStart(RegistryOps(JsonOps))` → recursive JSON scan for item entries —
  no reflection into `LootTable`'s private pools, version-tolerant, modded tables included.
  Block tables via `getLootTableKey()`; entity tables by the `entities/<id>` key convention
  (vanilla and well-behaved mods follow it; misses just yield no edge). Capped 6 drops/node;
  blocks that only drop themselves are suppressed as noise. Retrieval renders "Diamond Ore
  drops: Diamond" / "Zombie drops: Rotten Flesh, Iron Ingot, ...".
- **Entities join the name index**, so mob names are topics ("what do zombies drop?"). Where an
  entity and item share an id path (chicken, salmon) they share a node — cook edge and drop
  edge coexist coherently.
- **Deictic retrieval.** The player's look-target is folded into the retriever's match text, so
  "what are these? / what are they good for?" while looking at pointed dripstone retrieves the
  dripstone facts — closing the "Dripstone Powder" hallucination from the first field test
  end-to-end (look-target names it, graph explains it).
- **~20 natural-block phrases** for blocks recipes/tags can't explain: dripstone pair, amethyst
  family, obsidian/crying obsidian, soul sand/soil, magma, sponge pair, sculk trio, cobweb,
  slime/honey, mycelium, glowstone. Phrase-only knowledge (not facilities); surfaces via topic
  or look-target retrieval.

Graph-built log line now includes the drop-table count. Suite 350/350 green.

## Player-activity awareness + second field-test fixes (2026-08-24)

Second field test (1.1.151) verdict: look-target, facilities, oxidation-by-display-name, and
craftability all landed; three misses fixed here, plus the approved Option C feature:

- **Player-activity awareness (Option C).** `SoulPlayerActivity` keeps one most-recent
  observable action per player — block broken (fed by the existing `PlayerBlockBreakEvents`
  hook, one added line) or entity attacked (new `AttackEntityCallback` registration) — inside a
  30s window, joined at capture with instantaneous states (sneaking/sprinting/swimming/gliding/
  using <item>) into `PlayerSnapshot.activity`, rendered ", currently: sneaking; broke Stone 4s
  ago". Pure describe/window logic unit-tested; facades on SoulRuntime keep the hooks one-liners.
- **Flower pots** ("what flowers are in the pots?" → confabulated "none, they're empty"):
  potted plants had no block entity or POI so they were invisible. `FlowerPotBlock` instanceof
  (empty `flower_pot` excluded) now counts as a facility — vanilla display names ("Potted
  Poppy") carry the flower for free, modded pots included.
- **Lit state** ("do you see the fire?" → "there's a fire burning in the Furnace", furnace cold,
  campfire lit): facility display names now append the `LIT` blockstate where present —
  "Furnace (unlit)", "Campfire (lit)" — grouping separates lit from unlit and the model stops
  inventing fires.
- **Armor stands still read as absent** ("nothing on the armorstands") despite wiring verified
  end-to-end. Root cause not reproducible from logs (suspects: display stands behind glass — a
  COLLIDER raycast treats glass as opaque — or outside the ±3-height box). Diagnostic-first per
  repo convention: the scan now logs `[souls] armorstands inBox=N visible=M sample=<pos>` per
  capture whenever stands are in range; next session's log pinpoints the filter.

Suite 346/346 green.

## Honest perception + graph memory: LOS, known places, told facts (2026-08-24)

Bradley's ruling after the graph shipped: the bot must never announce things it couldn't
plausibly see (hidden treasure, sealed rooms) — but if it has been somewhere and seen a thing,
it should remember it, and if a player tells it where something is, it should remember that too;
all through the graph/retrieval path, never a per-turn memory dump. Three layers:

- **Line-of-sight gate.** The facilities box scan previously saw through walls — at radius 12 a
  sealed treasure room's chest would leak into the prompt. `scanFacilities` and
  `scanArmorStands` now require an unobstructed COLLIDER raycast from the bot's eyes to the
  target (adjacent ≤2 blocks exempt from ray ambiguity); LOS runs only on blocks that already
  passed the functional filter, so cost stays trivial. `RawFacility` gained positions;
  `SituationSnapshot.facilitySightings` (with coords) replaces `facilityIds`, which is now a
  derived method — conversation-service call sites compiled unchanged.
- **Known-places memory.** Every LOS-verified sighting is persisted per bot in
  `knowledge.json` (next to soul.json; atomic tmp+move on the store's single writer thread,
  in-memory cached): id path, dimension, position, lastSeen. Dedup by position (re-sighting
  refreshes lastSeen), capped 200 LRU. Retrieval renders the nearest same-dimension memory —
  "You remember an Enchanting Table about 30 blocks east." — suppressed while the facility is
  currently in sight (the Facilities line already covers it). Memory phrasing stays epistemic:
  a since-destroyed chest reads as a stale memory, never a false current sighting.
  Disproof-on-revisit deferred to v3.
- **Told facts.** When a player message is a *statement* (pure `isStatement` heuristic: no "?",
  no interrogative first word) and names a graph topic, the sentence is stored verbatim against
  that topic (teller + timestamp, 160-char cap, 3/topic, 40 topics LRU) and retrieval later
  surfaces "Roti told you: \"the spare picks are in the barrel by the gate\"". Deterministic
  string capture — no NLP, no extra LLM call.

All three stay topic-gated behind the retriever's message matching, preserving the v1 token
economics. Merge/cap policy is pure (`SoulKnowledgeMemoryOps`) and unit-tested; 8 new tests,
suite 339/339 green.

## Knowledge graph v1: recipes + tags, retrieved per soul turn (2026-08-24)

Bradley asked for an ontology/lookup-graph so the soul can know more while spending fewer
tokens. Key insight: the game already ships the ontology — registries, tags, recipes are
version-correct, mod-inclusive structured data in memory at runtime. v1 per the spec
(`docs/superpowers/specs/2026-08-24-soul-knowledge-graph-design.md`):

- **`GameKnowledgeGraph`** (`GameAI/Knowledge/`) — plain-data model (`CraftEdge`,
  `IngredientReq`, `GraphData` with name index + display names), no Minecraft types in any
  signature; volatile holder, empty until built. **`GameKnowledgeGraphBuilder`** projects it in
  one pass over `getRecipeManager().values()` (crafting/smelting/smoking/blasting/campfire;
  results via the recipe display system: `RecipeDisplay.result()` → `StackSlotDisplay`/
  `ItemSlotDisplay`, since 1.21.11 recipes have no public result accessor; identical ingredient
  alternative-sets merged with summed counts) plus the item registry (display names; vanilla
  tags capped 8/item). Registered on `SERVER_STARTED` and `END_DATA_PACK_RELOAD` (a deliberate
  deviation from the spec's lazy-build: eager on the server thread avoids off-thread first-query
  builds). Any failure logs once and installs an empty graph.
- **`SoulKnowledgeRetriever`** (pure) — longest-name-first word-bounded matching with plural
  tolerance ("torches" finds torch, "sticking" does not find stick), 2 topics max, ~380-char
  budget. Craftable topic: `"Torch: craft 4 at crafting table from 1 Stick (have 4) + 1 Coal or
  Charcoal (MISSING)"` — diffs against real carried counts, names the station, and appends
  "; no crafting table nearby" when the station isn't among seen facilities. Non-craftable:
  carried count + SoulBlockKnowledge phrase + "a kind of: <tags>".
- **Wiring** — `BotSnapshot.itemCounts` (id path → count, main slots) and
  `SituationSnapshot.facilityIds` (deduped raw scan ids, derived in the pure buildSituation
  seam) as non-rendered retriever inputs; retrieval runs in `SoulConversationService`
  (assembler stays pure, old `assemble` overload delegates); `RELEVANT KNOWLEDGE` SYSTEM block
  between witnessed events and PRESENT MOMENT, omitted when nothing matched — an unmatched
  message costs zero extra prompt tokens.

10 new tests (8 retriever + 2 assembly); suite 331/331 green.

## Ground the situational-awareness field-test failures (2026-08-24)

Bradley ran a 19-question awareness matrix in-game on 1.1.148 and "it failed most of them."
Log + soul-store reconstruction (questions from `latest.log` chat lines, Jake's answers from the
world-save `active.jsonl`) showed the new gear/inventory/facility features all working — the
failures clustered into four root causes, each now fixed:

- **Facility cap/radius too tight for a real base room.** The room held 8+ facility kinds; the
  6-kind, most-numerous-first cap let bed/chest multiples push the singleton stonecutter and
  enchanting table out entirely, and radius 8 clipped workstations he led Jake toward in FOLLOW
  mode ("No, I don't see a Stonecutter" while standing near one). Cap 6→10, radius 8→12, and a
  regression test that reconstructs the exact room.
- **Light level was never captured**, so the 8B confabulated ("It's daytime, so it's bright
  enough" — indoors, sky=false; then "light level's below 12", invented). Snapshot now carries
  block/sky light at the bot's feet (`world.getLightLevel`, -1 = unknown) and the prompt states
  the real spawn rule: "Hostile mobs can only spawn where block light is 0."
- **Deixis had no grounding.** "What are these?" / "this copper trapdoor" can't be answered from
  a bot-centric snapshot; Jake guessed ("Those are Stone Bricks"). `capturePlayer` now raycasts
  the player's crosshair 20 blocks (`player.raycast`, block hits only) into
  `PlayerSnapshot.lookingAt`, rendered as ", looking at: Weathered Copper Trapdoor" on the
  player line. One capture grounds the whole question class.
- **Armor-stand displays were invisible.** Stands are (correctly) excluded from the entity scan
  as decoration, so "what's on the armorstands?" got a confabulated "nothing." A dedicated
  `scanArmorStands` within the facility box describes displayed items through the same item
  describer (enchantments and custom names included), capped at 3 stands.

Still open from the matrix, deliberately not in this round: potted-flower contents (the
look-target covers them when the player looks at a pot), and encyclopedic "what is it good for"
knowledge ("Dripstone Powder" hallucination) — that is the knowledge-graph spec
(`docs/superpowers/specs/2026-08-24-soul-knowledge-graph-design.md`), next up. Suite 321/321.

## Weight witnessed-event selection by salience (2026-08-24)

Post-pilot backlog item. The prompt's witnessed-events block was the newest 12 journal events,
chronologically -- so a HIGH-salience event (a death, a self-rescue) 20 events back was silently
dropped while 12 LOW hobby ticks filled every slot. Selection is now salience-weighted but
recency-dominant: `SoulConversationService` fetches the last 48 events (`EVENT_FETCH_WINDOW`),
and a pure `SoulPromptAssembler.selectEvents` keeps the 6 newest unconditionally
(`RECENT_EVENT_FLOOR`, conversational continuity) and fills the remaining 6 slots from the older
window by salience tier -- HIGH, then NORMAL, then LOW, newest first within a tier -- with the
final pick re-emitted in journal order so the story still reads chronologically. With 12 or
fewer events the selection is the identity, so short sessions behave exactly as before. Salience
ranking is an explicit switch, not enum ordinal, so declaration-order drift can't invert the
policy. No store or schema changes. Five new selection tests; suite 316/316 green.

## Teach souls what nearby functional blocks are for (2026-08-24)

Second half of the awareness request: Jake now recognizes the *facilities* around him and what
they're used for — storage blocks, workstations, utility blocks — using the categorizations the
game and community actually use rather than an invented taxonomy:

- **Detection is structural, not an allowlist** (`SoulSnapshotBuilder.scanFacilities`, radius 8
  ±3 vertical): a block is functional when its state `hasBlockEntity()` (all storage and
  workstations — chests, barrels, shulkers, the furnace family, brewing stands, beds, lecterns,
  bells, beacons, spawners, hoppers... and modded equivalents for free) or when vanilla's own
  point-of-interest registry maps it (`PointOfInterestTypes.getTypeForState` — job sites, nether
  portal, lodestone, beehives). Wider than the 3×5 terrain scan because a chest across the room
  matters conversationally while dirt doesn't.
- **`SoulBlockKnowledge`** (new, pure) holds ~40 curated utility phrases keyed by block id path,
  following two real conventions: the creative inventory's "Functional Blocks" grouping for
  storage/utility ("stores items", "enchants gear with lapis and XP", "compass anchor point")
  and vanilla's villager job-site assignments for profession blocks ("smelts ores fast; armorer
  job site", "holds a book for reading; librarian job site"). Suffix families cover colored/
  damaged variants (`_bed`, `shulker_box`, `anvil`, `campfire`). A detected block missing from
  the table is still reported by name — detection never depends on the knowledge table. Signs,
  banners, heads, and decorated pots are excluded as decor so they can't crowd the cap.
- **Prompt**: the SITUATION block gains one prose line, e.g. `Facilities nearby: 2x Chest
  (stores items), Furnace (smelts ore, cooks food), Crafting Table (crafting station).` — capped
  at six kinds, most numerous first. `SituationSnapshot` gains a `facilities` component (old
  constructor delegates with empty, so prior call sites compile unchanged); the raw sightings
  thread through `SituationInputs` into the pure `buildSituation` seam like every other capture.

TDD: 8 SoulBlockKnowledgeTest cases + a buildSituation digest case + 2 prompt cases; suite
311/311 green.

## Give souls dynamic awareness of gear and notable inventory (2026-08-24)

Field feedback from the 1.1.145 sessions: Jake couldn't talk about what he was carrying beyond a
count-sorted resource list — a bundle showed as "1x Bundle" with no color or contents, a single
bed always lost the count sort to bulk blocks, and worn armor was only the numeric armor-bar
value. Rather than hardcoding item categories, the fix is component-driven and allowlist-free:

- **`SoulTypes.ItemFacts`** — plain-data facts for one stack (display name, type name, count,
  max stack size, enchantment display strings, contents of bundles/shulkers one level deep,
  wear fraction), extracted from Minecraft types only at capture time so all downstream logic
  stays unit-testable without game classes.
- **`SoulItemDescriber`** (new, pure) — `describe()` renders any facts into a short fragment:
  `"Fang" (Iron Sword)`, `Iron Pickaxe (Efficiency III, badly worn)`, `Red Bundle holding
  32x Torch, 5x Bread` (bundle color is free — it's part of the display name). `digest()`
  partitions the inventory by salience with no per-item allowlist: items carrying data
  components (custom name > enchantments > non-empty container) rank highest, then anything
  inherently scarce by max stack size (1, then ≤16 — beds, buckets, pearls, boats), and plain
  bulk ranks by count. Top 6 salient items become `notableItems`; the rest merge by name into
  the count-sorted 6-entry bulk list (superseding `topResourceSummary`). Anything vanilla or
  modded that carries components is picked up with zero new code — that's the dynamic part.
- **`SoulSnapshotBuilder`** — captures the four armor slots + offhand via
  `getEquippedStack` through the same describer into `wornGear`; scopes the carried digest to
  the 36 main slots (`getMainStacks()`) so an equipped piece is never double-counted as carried;
  held item is now describer-rendered, so its enchantments/wear surface too. Component APIs
  (`BUNDLE_CONTENTS.iterate()`, `CONTAINER.streamNonEmpty()`, `ItemEnchantmentsComponent`
  entries + static `Enchantment.getName`) match the patterns already proven in BundleService/
  ArtifactScanner/BotActions on 1.21.11.
- **`SoulPromptAssembler`** — state block gains `Wearing: ...` (or `Wearing: nothing`) and a
  `Carrying: ...` line for notable items, kept prose-shaped per the 8B-attention field lessons.

TDD throughout: 16 new SoulItemDescriberTest cases + 2 prompt-assembly cases; the old
`resourceSummaryCapsAtSixEntries` test retired with its method. Full suite + `build` green.

## Deduplicate cursor keys and cache sequence scans (2026-08-24)

Two deferred follow-ups from prior soul-store reviews, batched together since both are pure
internal cleanup with no behavior change: (1) the persistence cursor-key format
(`channel.name() + ":" + playerId`) was hand-built independently in `SoulStore.cursorKey`,
`SoulRuntime.status`, and `SoulMessageDelivery.ProductionDeliveryGuard.evaluateLive` — any drift
between the three copies would have silently broken epoch checks. `SoulStore.cursorKey` is now
the single package-private source of truth; the other two call it. (2)
`SoulStore.reconciledNextSequence` re-parsed the entire `active.jsonl` transcript on every
`beginHeardTurn`/`appendTurn` call (O(n) per turn, O(n²) over a conversation) to defend against a
narrow crash window between a JSONL append and its cursor persist. Added an in-memory
per-conversation `Map<String, SequenceCache>` (`SequenceCache(epoch, maxSequence)`) on the
store's single-writer thread: the first touch of a conversation still scans and seeds the cache
exactly as before; every subsequent append in the same epoch computes the next sequence as
`max(cursor.nextSequence(), cached.maxSequence() + 1)` with zero file I/O. `archiveAndReset` and
the interrupted-reset heal in `reconciledCursor` explicitly clear the cache entry on an epoch
bump; a fresh `SoulStore` (real process restart) always starts with an empty cache, so its first
touch re-scans identically to pre-change behavior. One existing crash-window test
(`beginHeardTurnReconcilesSequenceAheadOfPersistedCursor`) simulated its crash by hand-writing
directly into `active.jsonl` without ever closing the store — with the cache warm from the
in-process store's own prior write, that in-process hand-write is no longer distinguishable from
a real crash, so the test now closes and reopens the store before the hand-write, which is the
faithful way to simulate a process restart under the new design and is exactly the "advisory,
same-process-only" cache contract this change documents. Three new tests cover the cached path
(many sequential turns stay strictly increasing/duplicate-free), the cold-path seed scan (a
hand-appended record beyond the cursor on a never-touched conversation is still picked up), and
cache invalidation on reset (sequence restarts at 0 in the new epoch).

## Notice shoulder pets and name species first (2026-08-23, mod_version 1.1.145)

Round-4 field-test fix: the user's pet parrot, sitting in the same room, went completely
unnoticed by the soul pipeline. Two root causes, both verified against the Minecraft Wiki and the
1.21.11 Yarn mappings (`mappings.tiny` under `~/.gradle/caches/fabric-loom/1.21.11/...`, cross-checked
by `javap`-ing the merged-named jar under the worktree's own loom cache):

- **Shoulder-perched pets aren't world entities.** A tamed parrot auto-perches on its owner's
  shoulder, and while perched it is *not* a live `Entity` at all -- vanilla stores it as raw NBT on
  the holding player. `AutoFaceEntity.detectNearbyEntities` (or any other entity scan) can never
  see it, no matter the radius. Confirmed the exact 1.21.11 API by disassembling
  `ServerPlayerEntity#mountOntoShoulder`/`#spawnShoulderEntity`: the entity is held in
  `getLeftShoulderNbt()`/`getRightShoulderNbt()` (both `public`, both return `NbtCompound`, empty
  when nothing is perched), and `spawnShoulderEntity` decodes it back with
  `shoulderNbt.get("id", EntityType.CODEC)`. `captureSituation` now reads both shoulder slots on
  the bot itself (`"parrot (on your shoulder)"`) and, when reachability is LOCAL and a player is
  present, both of the player's shoulder slots too (`"parrot (on Bradley's shoulder)"`), folding
  each occupied slot into the same `RawEntity` list the ground-entity scan builds -- the existing
  aggregation (name grouping, owner/self exclusion, cap) picks them up with no further changes.
  (Note: `PlayerEntity` separately exposes `getLeftShoulderParrotVariant()`/
  `getRightShoulderParrotVariant()` returning `Optional<ParrotEntity.Variant>` -- that pair is a
  rendering-only concern for the parrot's color and was not used here; the `NbtCompound` pair on
  `ServerPlayerEntity` is the one that actually identifies *what* is perched.)
- **Named pets hid behind their names.** `EntityDetails.getName()` returns the vanilla display
  name, which is the custom name ("Rex") when the entity has one -- a small local model has no way
  to map an arbitrary player-chosen name back to a species. `captureSituation`'s entity loop now
  builds the `RawEntity` name from the entity's *type* first (`EntityType.getId(e.getType())
  .getPath()`, e.g. `"wolf"`, `"parrot"`), confirmed via `javap` on the same merged-named jar
  (`Entity#hasCustomName()`, `Entity#getCustomName()` returning `Text`, `Entity#getType()`), and
  annotates a custom name onto the species instead of replacing it: `"wolf (Rex)"`. The
  species+custom-name formatting and the shoulder-entry formatting were both extracted into small
  pure static helpers (`formatEntityName`, `shoulderEntry`) specifically so this round's fixes are
  unit-testable -- the enclosing loop still can't be, for the same reason documented in 1.1.144's
  entry above (live `Entity`/`ServerPlayerEntity` can't be constructed or mocked in this harness).
- **Diagnosability.** `SoulChatRouter`'s `[souls] routing ...` INFO line gained `hostiles=<n>
  animals=[a, b, ...]` (capped at 6 rendered entries) sourced from the same captured
  `GroundingSnapshot` as the existing `mode`/`following`/`sky` fields, so the next field test can
  confirm from `latest.log` whether capture actually saw the parrot without touching player
  message content -- these are species-level game facts, never private text.
- 6 new pure-seam tests added to `SoulGroundingTest` (`formatEntityName`/`shoulderEntry` unit
  cases plus one aggregation-flow test showing a pre-formatted shoulder entry passes through
  `buildSituation`'s existing name-grouping/cap logic alongside a ground sighting); full suite
  (281 tests) and `./gradlew build -x test` both green.

## Only living creatures count as nearby animals (2026-08-23, mod_version 1.1.144)

Pre-deploy review of the Fix B radius widening (below) caught a pre-existing bug it made worse:
`AutoFaceEntity.detectNearbyEntities` applies no entity-type filter at all -- `getOtherEntities`
returns every `Entity` subtype in the box, filtered only by line-of-sight. Before this fix, a
dropped item stack, arrow, or boat sitting in the bot's line of sight could reach the
`nearbyAnimals` aggregation and render as a fabricated line like `"Animals nearby: Oak Planks
x3."` -- indistinguishable from a real animal to the prompt. Widening the scan radius 10→16 (see
below) made this more likely to trigger, not less.

- `SoulSnapshotBuilder`'s capture loop (`captureSituation`, the `for (Entity e : nearby)` block)
  now skips any entity that isn't a `LivingEntity`, plus `ArmorStandEntity` specifically (armor
  stands are `LivingEntity` in vanilla but are decoration, not creatures) -- `if (!(e instanceof
  LivingEntity) || e instanceof ArmorStandEntity) continue;` before building the `RawEntity`.
  Imports (`net.minecraft.entity.LivingEntity`, `net.minecraft.entity.decoration.ArmorStandEntity`)
  and the `instanceof` idiom match existing usage elsewhere in the mod (`BotEventHandler.java`,
  `CompanionOverheadHologramService.java`).
- `AutoFaceEntity.detectNearbyEntities` itself was deliberately left untouched -- its other callers
  (`BotEventHandler`'s hostile-detection path) rely on its current unfiltered behavior.
- Untestable in this harness: the filter lives in `captureSituation`'s server-thread-only capture
  block, operating directly on live `net.minecraft.entity.Entity`/`LivingEntity` instances.
  Per the established precedent documented on `SoulMessageDeliveryTest`'s class Javadoc,
  Fabric/Minecraft classes in this family cannot be constructed or mocked outside a running
  server -- Mockito's inline mock maker fails outright on the remapped/final classes involved. A
  pure predicate extracted to take an `Entity` argument wouldn't help, since the argument itself
  still can't be constructed in-harness. The pure aggregation tests added in 1.1.144 below (which
  operate on already-collected `RawEntity`/`SituationInputs` values, never live `Entity`
  instances) are unaffected and still cover the dedupe/cap/exclude logic downstream of this
  filter. This gap is exercised in-game instead, same as `SoulChatRouter#tryRoute`.
- Full suite (276 tests, unchanged count since this filter has no in-harness test) and
  `./gradlew build -x test` both green.

## See blocks bases birds and follow state (2026-08-23, mod_version 1.1.144)

Round-3 field-test fixes on the soul situational-awareness branch. The 1.1.143 pass fixed
rendering/salience for facts the pipeline already captured; this round fixes facts the pipeline
never captured at all -- the bot still missed FOLLOW mode, never saw birds/bats, had no idea what
block it stood on or what surrounded it, and never recognized being at a known base.

- **Follow signal, sourced correctly.** `captureSituation` now reads
  `BotEventHandler.isFollowingPlayer(bot)` directly (true only for an active player-follow, false
  for return-to-base, which also runs `Mode.FOLLOW` internally) into a new
  `SituationSnapshot.following` component. `SoulPromptAssembler`'s PRESENT MOMENT dynamic line and
  the SITUATION `Mode:` line both switched from string-comparing `bot.behaviorMode() == "FOLLOW"`
  to reading `situation.following()` -- the previous string check couldn't distinguish an actual
  follow from a return-to-base disguised as one.
- **Diagnosability.** `SoulChatRouter`'s `[souls] routing ...` INFO line gained `mode=<mode>
  following=<bool> sky=<bool>`, sourced from the captured `GroundingSnapshot` on the "submitted"
  outcome (the five pre-capture outcomes -- loading/invalid-pipeline/unauthorized/unreachable/
  no-server -- log the neutral defaults since no snapshot exists yet). Content-free facts only, so
  a field test can check ground truth in `latest.log` without touching player message content.
- **Birds and other ambient fliers.** `captureSituation`'s entity scan radius widened from 10 to 16
  blocks for this capture only (no other `AutoFaceEntity.detectNearbyEntities` caller touched).
  Confirmed the underlying scan was never the problem: `detectNearbyEntities`/`EntityDetails.from`
  apply no `MobEntity`-only filter -- every `Entity` subtype in line-of-sight (parrots, chickens,
  bats included) already reaches the nearby-animal aggregation. The miss was purely range: birds
  and bats often perch/roost past 10 blocks. Non-`LivingEntity` types (dropped items, arrows) can
  still reach `nearbyAnimals` if in LoS and not hostile -- pre-existing behavior, not a regression
  from the radius change, left as-is (out of scope for this pass).
- **Block recognition.** New `SituationSnapshot.standingOn` (block directly under the bot's feet,
  via `world.getBlockState(pos.down())`) and `SituationSnapshot.nearbyBlocks` (deduped block-type
  names, top 4 by count, air excluded). Capture mirrors `BotEventHandler`'s own scan exactly --
  `new BlockDistanceLimitedSearch(bot, 3, 5).detectNearbyBlocks()` -- so the soul sees what the
  bot's own AI already scans. Dedup/count/cap is pure logic in `buildSituation`, unit-tested
  without a Minecraft server. Renders in the SITUATION logistics tier: `"Standing on <x>; nearby
  blocks: a, b, c."`
- **Base recognition.** New `SituationSnapshot.atBase`: label of the nearest known base within 32
  blocks of the bot's *current* position. Extracted the inline nearest-base-to-a-point loop that
  previously existed only for `lastSleepLabel` into a shared `nearestBaseLabel(bases, pos,
  maxDistanceSq)` helper, reused for both the sleep-position and current-position lookups (same
  32-block radius, different point). Renders in SITUATION: `You are at your base "<label>".` and
  appends `, at base <label>` to the PRESENT MOMENT dynamic line.
- `SituationSnapshot` grows from 21 to 25 components (four added: `standingOn`, `nearbyBlocks`,
  `following`, `atBase` -- not the three originally estimated). Final order: `dangerDistance,
  hostiles, nearbyAnimals, standingOn, nearbyBlocks, enclosed, hasHeadroom, hasEscapeRoute,
  behaviorMode, following, inCombat, postCombatLinger, recentKillCount, inShelter,
  surfaceRecoveryActive, breakingFree, nightTravelActive, companionDays, deathCount, mount,
  knownBaseCount, lastSleepLabel, atBase, hunt, lastHobby`. `SituationInputs` (the pure-seam
  carrier in `SoulSnapshotBuilder`) grows in parallel to 27 fields. Every positional construction
  across `SoulSnapshotBuilder.java`, `SoulTypes.java`, `SoulGroundingTest.java`,
  `SoulPromptAssemblerTest.java`, and `SoulSituationTypesTest.java` updated; defensive copies
  (`List.copyOf` on `nearbyBlocks`) and null/blank normalization added to both records' canonical
  constructors. 13 new tests (following passthrough, standingOn passthrough, nearbyBlocks
  dedupe/cap/blank-drop, atBase passthrough and rendering, follow-mode rendering from the boolean
  not the string, routing-log diagnostics) plus 2 tests fixed for the new render text. Full suite
  (276 tests) and `./gradlew build -x test` both green.

## Make Jake's situation legible to small models (2026-08-23, mod_version 1.1.143)

Field-test on the 1.21.11 test instance surfaced four salience gaps that the pre-existing
grounding/prompt pipeline captured correctly but rendered too tersely for an 8B model to act on:
the bot never mentioned being underground, never reacted to having moved since the last turn,
ignored an active FOLLOW mode, and had no way to see nearby passive mobs (horses, dogs) because
`captureSituation`'s entity loop kept only `isHostile()` sightings. Root cause: facts were present
in the AUTHORITATIVE STATE key-value dump but buried in undifferentiated key:value pairs an
under-attending small model skips past, and passive entities were filtered out before they ever
reached the prompt.

- **Nearby passive/notable entities.** `SoulSnapshotBuilder.buildSituation` now aggregates every
  non-hostile entity from the same 10-block scan by name (`"horse x3"`, `"wolf"`), most-numerous
  first, capped at 4, excluding the owner (`SituationInputs.ownerName`) and the bot itself
  (`SituationInputs.botName`) so a nearby commander or teammate bot never shows up as "wildlife".
  New `SituationSnapshot.nearbyAnimals` component (inserted right after `hostiles`, the record is
  now 21 components) flows through the existing pure `buildSituation` seam — capture stays
  server-thread-only, aggregation stays unit-testable without a Minecraft server.
- **Location and underground lines, prose not key-value.** `SoulPromptAssembler.situationLines`
  now opens every non-empty SITUATION block with `"You are in <biome> at (x,y,z) in <dimension>."`,
  and adds `"You are underground -- no sky overhead. There are no trees or open terrain down here;
  surface features are out of sight."` whenever `!bot.skyVisible()`. Both are top-priority: they
  render before hazards and survive the 800-char budget while lower-priority lines (last hobby,
  last slept) get dropped first, same as before. `nearbyAnimals` renders as `"Animals nearby: horse
  x2, wolf."` in the existing logistics tier, right after `Mode:`.
- **PRESENT MOMENT carries a dynamic recency anchor.** `presentMoment()` was static boilerplate
  text; it now takes the `GroundingSnapshot` and appends one compact line — `"Right now: <biome>,
  (x,y,z), <open sky|underground>, mode <MODE>[, following your owner]."` — after the three
  existing instruction lines, so the turn immediately preceding the player's message restates
  current position/mode instead of trusting the model to reconstruct it from further up the prompt.
  The block still starts with `"PRESENT MOMENT\n"`, so the mandated
  `presentMomentImmediatelyPrecedesCurrentPlayerMessage` ordering assertion is unaffected.
- **Contract states recency wins.** `systemContract()` gained one sentence: "The AUTHORITATIVE
  STATE message reflects the present moment and OVERRIDES anything remembered or said earlier in
  this conversation when they conflict." — closing the gap where a stale remembered fact (old
  location, old mode) could outweigh the current turn's grounding in a small model's attention.
- Every positional `SituationSnapshot`/`SituationInputs` construction across
  `SoulSnapshotBuilder.java`, `SoulGroundingTest.java`, `SoulPromptAssemblerTest.java`, and
  `SoulSituationTypesTest.java` was updated for the new components; `SoulSituationTypesTest`'s
  null-normalization test now also asserts `nearbyAnimals` collapses a `null` to `List.of()`.
  7 new tests added (location/underground/animals rendering, PRESENT MOMENT dynamic line,
  nearby-animal aggregation and cap) plus assertions strengthened on 2 existing tests; full suite
  (267 tests) and `./gradlew build -x test` both green.

## Correct situational grounding fidelity (2026-08-23)

Final whole-branch review fix wave on the soul situational-awareness branch (post `71f2205`).
Two critical fixes and seven important fixes, all re-verified against 259 pre-existing tests plus
new/updated coverage:

- **SELF_RESCUE false positives (critical).** The hook moved off the `ensureAtSurface`/
  `ensureAtSurfaceForHobby` wrappers (which fired on `recovered == true`, including the
  already-at-surface no-op) and into the private `ensureAtSurface(bot, world, skipLingerCheck)`
  itself, placed only at the seven `return true` sites that follow an actual recovery action
  (dry-land move, nearby-staging move, pillar recovery ×3, step-building, post-ascent) —
  `BotFleeService.java`. The already-at-surface early return (`logOperationalSurfaceState(...
  "ensureAtSurface:current")`) stays unhooked.
- **Hostile direction was bot-relative, not compass (critical).** `SoulSnapshotBuilder` dropped
  `EntityDetails.getDirectionToBot()` (a broken front/right/behind/left bearing) from `RawEntity`
  entirely and now derives hostile direction from the existing `cardinalDirection(dx, dz)` compass
  helper — the same one `PlayerSnapshot` already used — so "zombie 5 blocks northeast" means what
  it says.
- **`onSelfRescue` made UUID-only.** `ensureAtSurface` runs on worker threads; the hook signature
  changed to `onSelfRescue(UUID botId, String kind)` (empty dimension/biome, tick 0), matching
  `onHobbySession`'s existing worker-thread contract. `SoulEventObserver`'s class Javadoc now
  documents this.
- **Death-hook vocabulary and gate ordering.** `onMobKilled` now takes the raw `Entity` and derives
  `EntityType.getId(...).getPath()` (registry path, e.g. `zombie`) after the master-switch gate,
  instead of `Frens.java` pre-computing a localized display name before the gate.
- **Capture no longer mutates stuck-state or over-scans.** `SoulSnapshotBuilder.captureSituation`
  reads `BotStuckService.analyzeEnvironment`, `LavaDetector`/`CliffDetector` directly, and
  `AutoFaceEntity.detectNearbyEntities` instead of `BotEventHandler.createInitialState`, which
  side-effected `BotStuckService.setLastSafePosition` and computed ~10k blocks of fields this
  capture immediately discarded.
- **One behavior-mode source of truth.** `BotSnapshot.behaviorMode` now comes from
  `BotEventHandler.getCurrentMode(bot).name()`, same as the SITUATION block's `Mode:` line, instead
  of a separate `following`/`idle` guess.
- **Hobby name uses the in-scope skill, not a stale map read.** `onHobbySession` calls at the cook
  branch and the generic completion path now pass `"cook"`/`skillToRun` directly; the two
  ambient-woodcut fallback starts that previously skipped `LAST_HOBBY.put` now record it, mirroring
  the primary decision loop.
- **Hazard distance is a real minimum, not a meaningless sum.** Capture now calls
  `LavaDetector.detectNearestLava` and `CliffDetector.detectCliffWithBoundingBox` separately and
  takes the nearer of whichever hazard(s) are actually present, instead of
  `DangerZoneDetector.detectDangerZone`'s `lavaDistance + cliffDistance`.
- **Event-volume flooding.** `HUNT_PROGRESS` now fires only at the first kill and at goal-reached,
  using `candidate.target.label()` for the target name; `MOB_KILLED` is suppressed while the
  killer's active task is `skill:hunt` (`TaskService.getActiveTaskInfo`), since `HUNT_PROGRESS`
  already covers hunt kills at milestones.

Added a SELF_RESCUE manual case (negative: hobby/hunt while already safe → no record; positive:
genuine trapped-underground recovery → exactly one record) to
`docs/testing/SOUL_COMMUNICATION_PILOT.md`. `./gradlew test` and `./gradlew build -x test` both
green.

## Document situational awareness acceptance (2026-08-23)

Task 6, closing out the soul situational-awareness branch. Jake's DM replies now ground on a
live `SITUATION` sub-block (hazards/hostiles, combat, survival flags, behavior mode,
relationship, then mount/base/hunt/hobby logistics — 800-char budget, priority-ordered, omitted
entirely when empty) and on a bounded journal of witnessed events (kills, self-rescues, hobby
sessions, hunt progress), both gated on the master soul switch and an active profile so a
souls-disabled install stays byte-identical to pre-branch behavior. Added a "Situational
awareness" manual test subsection to `docs/testing/SOUL_COMMUNICATION_PILOT.md` covering: naming
a visible hostile and its direction, truthful `STAY`/`FOLLOW` mode reporting, a kill surfacing
later from event memory, REMOTE DMs still carrying Jake's own situation while excluding the
player's, hobby (including cook) sessions surfacing on ask, and a re-run of the feature-off
baseline's disk assertion. `./gradlew test` (259 tests, 0 failures/errors — up from 228
pre-branch) and `./gradlew build -x test` both green; artifact
`build/libs/frens-1.1.141-release+1.21.11.jar`. No `mod_version` bump and no deploy — this is
documentation only, pending manual acceptance and user confirmation the game is closed.

## Gate break-free rescue on real success (2026-08-23)

Review fix round on Task 5 (commit `d63b65c`). Two findings:

**Finding 1 — break-free `onSelfRescue` fired on "no abort," not success.** The hook
added in `BotFleeService.java:1971` sat right after `breakFreeFromShelter`'s post-dig
`shouldAbortSurvival` check, which only proves the thread wasn't interrupted — it does
**not** prove the bot got free. All four type-specific dispatch methods
(`breakFreeCliff`/`breakFreeDugDown`/`breakFreeVillageHouse`/`breakFreeGeneric`,
`:2010-2111`) are `void` and can silently no-op: `breakFreeVillageHouse` returns
immediately when `doorPos`/`interiorDir` is null, and `breakFreeGeneric`'s "protected
ore — staying put" fallback returns having mined nothing. A sealed-in bot with either
of those two conditions would have journaled a false rescue. Checked for a cheap,
generically-reusable post-condition to re-run at the hook site instead: `isInShelter()`
is unusable (`SHELTER_ACTIVE` is unconditionally cleared by both callers —
`clearShelterAndBreakFree`/`forceBreakFree` — *before* dispatch even runs, so it always
reads `false` by hook time, success or not); `isAtSurface()` is also unusable pre-hook
because the follow-up `escapeToSurface(bot, world)` call (which is what actually gets a
still-underground bot to the surface) runs *after* this point. No caller of
`breakFreeFromShelter` shares one single stuck/enclosure predicate either — the seven
call sites (`BotEventHandler`, `BotAutoReturnSunsetService`, `BotAutoHuntService`,
`BotIdleHobbiesService`, `modCommandRegistry`) invoke it for varied reasons (some are
"leave shelter for a command," not "the bot got trapped"). Per the review's documented
fallback ("if no such check is cheaply re-runnable... REMOVE the break-free hook
entirely... a missing event is honest, a false one is not"), removed the
`onSelfRescue(bot, "break-free")` call from `BotFleeService.java:1971` rather than
inventing a per-dispatch-type re-check (explicitly out of scope — no refactor of the
four dispatch methods' signatures this round). `onSelfRescue(bot, "surface-recovery")`
(the two `ensureAtSurface`/`ensureAtSurfaceForHobby` wrapper hooks, unaffected by this
finding) remains the sole self-rescue signal for this release; "break-free" as a `kind`
value is defined on `SoulEventObserver` but currently has no production emitter.
**Correction (2026-08-23, later fix wave below):** describing those two wrapper hooks as
"success-only" was itself wrong — `boolean recovered` being `true` also covers the
already-at-surface early return inside the private `ensureAtSurface(bot, world,
skipLingerCheck)` (no recovery action taken, just a truthy status check), so the wrappers
fired on every no-op call, not only on genuine recoveries. Fixed by moving the hook off
the two wrappers entirely and into the private method's real-recovery `return true` sites
only — see "Correct situational grounding fidelity" below.

**Finding 2 — cook hobby never journaled.** `BotIdleHobbiesService.java`'s special-cased
`cook` branch (`:1003-1034`) runs `SmeltingService.cookAllFoodSync` directly and never
reaches the generic `runSkill` completion line the original hook sat on, so cook
sessions were invisible to `onHobbySession`. Added the same one-line call at the cook
branch's own completion site, right after its `LAST_HOBBY_END_MS.put(...)`
(`BotIdleHobbiesService.java:1019`) — mirrors the generic-path placement exactly,
including firing unconditionally on `result` (success or failure), consistent with how
the generic path already treats a "finished" session regardless of outcome. The
surface-escape-skip branch (never actually cooked) and the crash `catch` are still not
hooked, matching the original scope decision for the generic path.

Verified: `./gradlew test` and `./gradlew build -x test` both green after both fixes.

## Hook awareness events into services (2026-08-23)

Task 5 of situational awareness: one guarded call per transition wired into the four
production sites Task 4's hooks needed, each purely additive (no reordering, no other
behavior change). Two of the four true sites diverged from the task's original file list
after reading the code, and both are documented deviations rather than blocking stops, per
the task's own contingency guidance:

- **Mob kill** — `noteKillPosition(UUID, Vec3d)` has exactly one caller, in `Frens.java`'s
  death-detection block (not `BotCombatCalloutService.java`, which only receives the
  killed entity for *hostile* kills behind a 10s dialogue-callout cooldown — hooking there
  would silently under-report kills in a multi-mob fight). `onMobKilled(killer2,
  dead.getType().getName().getString())` sits right after `noteKillPosition`, inside the
  existing `isRegisteredBot` guard, unconditional on hostile/non-hostile.
- **Self-rescue** — `BotFleeService.java`. Surface recovery has no single internal
  convergence point (the private `ensureAtSurface` has ~7 `return true;` sites), so the
  hook sits at the two public funnel wrappers (`ensureAtSurface`, `ensureAtSurfaceForHobby`)
  that capture the boolean result and fire `onSelfRescue(bot, "surface-recovery")` only
  when `true`. Break-free fires `onSelfRescue(bot, "break-free")` right after
  `breakFreeFromShelter`'s post-dig abort check clears — proof the dig-out completed
  without an abort — before the follow-up `escapeToSurface` call (which does not route
  through the hooked wrappers, so no double-fire).
- **Hobby session** — `BotIdleHobbiesService.java`, the generic `runSkill` completion path
  right after `LAST_HOBBY_END_MS.put(...)`, using `LAST_HOBBY.get(botUuid)` so the emitted
  name matches what `getLastHobby()` returns. The special-cased `cook` hobby branch (its own
  separate completion site) is intentionally not hooked to keep this to one call per
  transition.
- **Hunt progress** — the actual `kills++` mutation lives in `HuntSkill.java`, not
  `HuntSessionService.java` (that file only stores an already-computed tally via
  `saveSession`, called once at sunset, not per kill). `onHuntProgress` fires inside the
  same `if` that increments `kills`, target resolved via the same "first `targetIds` else
  `zoneName`" fallback `SoulSnapshotBuilder` already uses (`selectedTargets` /
  `huntZone.name()` here).

Verified: `./gradlew test` and `./gradlew build -x test` both green; both pilot static
checks over `GameAI/souls` clean (only the known `SoulChatRouter` Javadoc line for the
first, zero hits for the credential grep).

## Journal kills, rescues, hobbies, and hunts (2026-08-23)

Task 4 of situational awareness: four new event types appended to the end of
`SoulTypes.EventType` (`MOB_KILLED`, `SELF_RESCUE`, `HOBBY_SESSION`, `HUNT_PROGRESS` — order
preserved, nothing else in `SoulTypes.java` touched) plus matching production entry points and
data-only note methods in `SoulEventObserver`. `onMobKilled(ServerPlayerEntity, String)` and
`onSelfRescue(ServerPlayerEntity, String)` mirror the existing damage hooks' gate-first shape
exactly: `PRODUCTION.get()`/entity-null check, then `SoulRuntime.current()` +
`isMasterEnabled()`, and only after both gates pass do they read live dimension/biome/world-tick
off the bot. `onHobbySession(UUID, String)` and `onHuntProgress(UUID, String, int, int)` take a
bare bot UUID (no entity is guaranteed live at call time from an idle-hobby or hunt-tally
callback), so their instance-seam counterparts emit with `""` dimension/biome and world-tick `0`
— the same convention `noteTaskStarted` already uses for ticket-sourced events. Facts stay
string-only: `{"mob": mobType}`, `{"kind": kind}`, `{"hobby": hobbyName}`, and
`{"target": t, "kills": String.valueOf(k), "goal": String.valueOf(g)}`, with every nullable input
normalized through the file's existing `nullToEmpty` helper so no fact map ever holds a null
value. Salience: `MOB_KILLED`/`HUNT_PROGRESS` → NORMAL, `SELF_RESCUE` → HIGH, `HOBBY_SESSION` →
LOW; witness is `SELF` for all four, matching every other self-observed event in this file.

`SoulEventObserverTest` gained ten new cases (five happy-path type/salience/fact assertions, four
null-normalization checks, one sink-rejects-everything no-op check) exercising the four new
`note*` methods through the existing `CapturingSink` pattern — no Minecraft type touched. One test
wrinkle: `Map.copyOf(...).containsValue(null)` throws `NullPointerException` (the JDK's immutable
map implementation doesn't tolerate a null probe key/value), so the null-fact assertions use
`facts.values().stream().anyMatch(Objects::isNull)` instead. RED confirmed via a compile failure
(`cannot find symbol: noteMobKilled`/`noteSelfRescue`/`noteHobbySession`/`noteHuntProgress`) before
implementation; GREEN confirmed via the focused test class, the full `./gradlew test` suite, and
`./gradlew build -x test`, all passing.

## Restore companion prompt wording (2026-08-23)

Fix round on the SITUATION prompt rendering task: reverted the `appendBotState` wording change
from the previous entry (`"permanently recruited"` / `"recruitment quest stage"` back to
`"permanent companion"` / `"companion quest stage"`). Review caught that `recruited` and
`permanentCompanion` are two distinct progression milestones (the companion questline turns
"recruited" into "permanent companion"), so `"permanently recruited: true"` sitting right next to
`"recruited: true"` read to the LLM as the same fact restated — a prompt-quality regression, not a
neutral rename. The actual collision this was working around (the mandated ordering test's bare
`indexOf("companion")` finding the earlier "permanent companion" text instead of the new
Relationship line) is fixed on the test side instead: `situationBlockRendersInsideAuthoritativeStateInPriorityOrder`
now searches for `"companion for"` — the literal prefix of the Relationship line's own clause —
which cannot collide with `appendBotState`'s wording. No production rendering logic changed beyond
the revert.

## Render Jake's situation in prompts (2026-08-23)

Third step of situational awareness: `SoulPromptAssembler.authoritativeState` now appends a
`SITUATION` sub-block inside the existing authoritative-state system message (message order/count
is unchanged — no new message, system contract untouched). `situationLines(SituationSnapshot)`
renders in a fixed priority order — hazard distance + hostiles + enclosure, combat, survival
flags, behavior mode, relationship, then logistics (mount, bases, last sleep, hunt, last hobby) —
skipping every default-valued field (`-1` distances/days/deaths, empty hostiles/Optionals, `""`
behaviorMode, `false` booleans) so `SituationSnapshot.empty()` produces zero lines and therefore no
block at all. `appendSituation` greedily fills a fixed 800-char budget (`MAX_SITUATION_CHARS`) in
that priority order; the first line that would overflow the budget is dropped along with every
lower-priority line after it, so hazards/hostiles always win a fitting slot over a long last-hobby
or last-sleep label. Per the Task 2 review's controller ruling, the mount line renders only the
raw current health (`"Mount: horse, saddled, health 11."`) — never a ratio/percentage against
`MountSummary.maxHealth`, since that field silently defaults to health when the source has no real
max. Added `situationBlockRendersInsideAuthoritativeStateInPriorityOrder`,
`situationBlockIsCappedAt800CharsDroppingLowestPriorityFirst`,
`emptySituationRendersNoSituationBlock`, and `remotePromptRendersSituationWithoutPlayerSurroundings`
(REMOTE grounding still omits `playerBiomeSecret` with a populated situation present) to
`SoulPromptAssemblerTest`; all prior tests pass unmodified.

## Capture Jake's live situation (2026-08-23)

Second step of situational awareness: `SoulSnapshotBuilder.capture` now populates the
`SituationSnapshot` added to `GroundingSnapshot` in the prior step. Server-thread-only
`captureSituation(server, bot)` reads danger distance and nearby entities from
`BotEventHandler.createInitialState`, behavior mode from `getCurrentMode(bot)`, combat/lingering
state from `BotCombatCalloutService`, shelter/recovery/break-free flags from `BotFleeService`,
night-travel state from `BotAutoReturnSunsetService`, recruitment epoch/death-count from
`SurvivalRecruitmentService` (alias-gated exactly like the existing recruitment read in
`captureBot`), mount state from `MountPersistenceService`, known-base count and a nearest-base
sleep label (within 32 blocks, else omitted — never raw coordinates) from `BotHomeService`, an
active hunt from `HuntSessionService`, and the last idle hobby from `BotIdleHobbiesService`. Every
source group is wrapped in its own try/catch so one throwing/absent source degrades to that
group's defaults instead of failing the whole capture.

All filtering/sorting/capping/day-floor logic lives in a new pure seam,
`SoulSnapshotBuilder.buildSituation(SituationInputs)`: hostiles are filtered from nearby-entity
deltas, distance-sorted nearest-first and capped at 5; `companionDays` floors from
`recruitedAtEpochMs`/`nowEpochMs` (0 recruited-at = unknown, `-1`); danger distance `<=0` collapses
to the snapshot's `-1` sentinel. `SituationInputs` (and its `RawEntity` component) are new
package-private records of plain values only, so `SoulGroundingTest` exercises `buildSituation`
directly with hand-built inputs — no Minecraft/Mockito needed. `assemble(...)` gains a 5-arg
overload taking the situation snapshot; the old 4-arg overload now delegates with
`SituationSnapshot.empty()`, so the two remote/local `assemble` tests from the first step still
compile and pass unchanged.

## Add soul situation types (2026-08-23)

First step of situational awareness: `SoulTypes` gains `HostileSighting`, `MountSummary`,
`HuntSummary`, and `SituationSnapshot` (danger distance, nearby hostiles, enclosure/escape flags,
combat and behavior-mode state, mount, known-base count, last sleep/hobby, active hunt) —
all immutable, defensively copied/normalized records matching the existing SoulTypes style.
`GroundingSnapshot` grows a fifth `situation` component via a new canonical 5-arg constructor;
the old 4-arg constructor is preserved as a delegating convenience that defaults to
`SituationSnapshot.empty()`, so every existing call site (SoulSnapshotBuilder, SoulChatRouter,
and their tests) compiles unchanged. Record component order matches the task brief exactly since
later tasks (populating the snapshot, wiring it into the prompt assembler) construct these
positionally.

## 1.1.141 — pin the soul request context window (2026-08-23)

Pre-warming for the retest exposed the deeper half of the freeze: this machine's Ollama default
context for llama3.1:8b is the model maximum (131k), so a request that doesn't pin `num_ctx`
spawns a runner with a ~74 GB KV-cache allocation that spills 68% to CPU. The soul request now
sends `options.num_ctx=8192` (the prompt assembler budgets ~5k tokens worst-case), keeping the
runner small and fully on-GPU regardless of the user's Ollama app settings. Request-shape test
extended to lock the new field.

## 1.1.140 — hold the soul model resident for a play session (2026-08-23)

Second in-game finding: the "crash" on first DM was not a crash — no exception anywhere in the
log. Ollama cold-loaded llama3.1:8b while Minecraft was rendering, and the unified-memory/Metal
contention pushed server ticks from ~20 ms to 2,700 ms (a multi-second freeze; the reply then
arrived after world close and was correctly discarded by the delivery-guard recheck). Raised the
Ollama request `keep_alive` from the plan's `5m` to `60m` so a session pays the model load at
most once, and documented a pre-warm step plus the addressing surface boundary in the runbook:
vanilla `/msg` and its aliases `/tell` and `/w` (confirmed against the Minecraft Wiki) are plain
whispers to the fake player and intentionally do not route to the soul pipeline.

## 1.1.139 — /bot soul model accepts real Ollama names (2026-08-23)

First in-game finding from the acceptance run: `/bot soul model llama3.1:8b` failed at the
Brigadier parse layer ("Expected whitespace to end one argument... at position 24") because the
model argument used `StringArgumentType.string()`, whose unquoted form rejects `:` — present in
every tagged Ollama name — and `/` in `hf.co/...` names. Switched to `greedyString()` (model is
the final argument); `validatedModel` still trims and rejects blank/control/overlong input. The
plan's mandated test exercised `validatedModel("qwen3:14b")` directly, so the unit suite could
not catch the parse-layer mismatch — runbook updated implicitly by this note: command-syntax
cases should be typed in-game, not only asserted through pure helpers.

## 1.1.138 — soul pilot test build (2026-08-23)

`mod_version` bumped to 1.1.138 and deployed to the Prism instances for the manual in-game
acceptance run of the soul-communication pilot (`docs/testing/SOUL_COMMUNICATION_PILOT.md`).
Everything from "Add soul communication domain model" through "Gate soul side effects on the
master switch" below ships in this build; `soulsEnabled` remains default-off.

## Gate soul side effects on the master switch (2026-08-23)

Final whole-branch review fix wave for `feature/soul-communication`. Five findings, all now gated
on `SoulRuntime.isMasterEnabled()`:

1. **Souls-disabled installs wrote `soul.json` on bot death/disconnect.** `SoulStore.setActive`
   synthesized and saved a default state even for a bot with no prior soul.json; `SoulRuntime.
   cancelBot` (called unconditionally on every bot death/disconnect via `SoulEventObserver.
   onBotDeath`/disconnect hooks) always called `store.setActive(botId, false)`. Fixed both
   layers: `SoulStore.setActive` now decides, on its own single writer thread, to skip the save
   entirely when deactivating a bot whose `soul.json` never existed; `SoulRuntime.cancelBot` now
   also short-circuits before touching the store at all when the master switch is off AND nothing
   is cached for the bot, so a souls-disabled install never creates `<world>/frens/`.
2. **Event journal kept writing after the master switch was flipped off.** The production
   `SoulEventObserver.EventSink.accepts()` checked only `hasActiveProfile`, so a bot bound+active
   before switch-off kept having session-transition events (dimension/sleep/quest/combat)
   journaled to disk after. Extracted the pure `SoulEventObserver.acceptsEvent(masterEnabled,
   hasActiveProfile)` predicate (now unit tested) and wired it into the production sink.
3. **Damage hooks did live-world reads before any soul gate.** `onBotDamage`/`onPlayerDamage`
   computed dimension/biome/damage-source classification (and, for player damage, iterated
   `BotRegistry` + resolved each bot's controller) on every hit in the world regardless of whether
   souls were even enabled. Both hooks now return immediately, before any of that work, unless a
   runtime exists and `isMasterEnabled()`.
4. **`/bot soul enable|disable|reset|status <bot>` accepted non-bot players.** None of the four
   bot-targeting handlers checked that the named entity was actually a registered Frens bot before
   running owner/operator authorization. Each now rejects a non-bot target up front via
   `BotEventHandler.isRegisteredBot(...)` with "<name> is not a Frens bot." (Minecraft-type-only
   path; verified in-game per this repo's existing test-harness constraint on `ServerPlayerEntity`.)
5. **Soul traffic could silently target a non-local Ollama host.** `/bot soul status` now reports
   the resolved `host:port` (never scheme/path/credentials — there are none) via a new
   `SoulRuntime.Status.ollamaHost()` field; `SoulRuntime.buildPipeline` (used by both `start` and
   `reloadSettings`) now logs one `frens.souls` warning whenever enabled+valid settings resolve to
   a non-loopback host, since that means private chat and game-context facts leave the machine.

Also: scoped `CompanionCommunicationPolicy.isPrivateSoulAuthorized`'s Javadoc "unowned bot is NOT
eligible" sentence to the non-operator path (it contradicted the preceding "operators always
pass" sentence); moved `SoulChatRouter.tryRoute`'s `UUID.randomUUID()`/`System.nanoTime()` below
the runtime-exists check so a no-runtime call doesn't mint them for nothing; added a `<world>/
frens/` non-existence disk assertion to the runbook's disabled-baseline case (covering both a bot
death and a disconnect) plus a note that `/bot soul disable` drops the bot out of no-arg `/bot
soul status`.

New/extended tests: `SoulStoreTest.setActiveFalseOnAFreshBotCreatesNoStateOnDisk`,
`SoulRuntimeTest.cancelBotSkipsTheStoreWhenMasterDisabledAndNothingCached` +
`cancelBotStillDeactivatesACachedProfileEvenWhenMasterDisabled`,
`SoulEventObserverTest.acceptsEventRequiresBothTheMasterSwitchAndAnActiveProfile`. Focused suite
(`SoulStoreTest`/`SoulRuntimeTest`/`SoulEventObserverTest`/`BotSoulCommandsTest`) and the full
`./gradlew test` (232 tests, up from 228) both green; `./gradlew build -x test` green.

## Correct soul runbook failure expectations (2026-08-23)

Review follow-up to the entry below: `SOUL_COMMUNICATION_PILOT.md`'s "Local provider unavailable"
and "HTTP 503" manual cases wrongly hedged toward `OVERLOADED`/`INVALID PIPELINE` outcomes that
`OllamaSoulProvider`/`SoulRuntime` cannot actually produce from a stopped server or a 503 response
(both map to `UNAVAILABLE` only); split the config-shape-only `INVALID PIPELINE` case into its own
setup, and quoted the LOADING notice's literal text.

## Document soul pilot acceptance (2026-08-23)

New `docs/testing/SOUL_COMMUNICATION_PILOT.md`: the Task-12 runbook for the soul-communication
pilot, covering every case in the design spec's "Pilot acceptance criteria" and "Manual pilot
test matrix" sections plus the task-12 brief's additional 503/malformed-JSON/timeout/privacy
cases. Split into an **Automated** section (cases genuinely exercised by this branch's JUnit
suite, each citing its exact test class/method — routing decisions, reachability boundaries,
scheduler ordering/overflow/timeout, validator rejection rules, delivery six-gate combinator,
reset/epoch/restart persistence) and a **Manual — to be executed in-game** section (unchecked
checkboxes; requires a live 1.21.11 client/server) for everything that needs a real socket
disconnect, real process restart, or a live model's probabilistic output — most notably the
remote-perception leakage probe, which is the one case where a wrong answer is a real defect
even though it can't be asserted deterministically.

`./gradlew test --rerun-tasks` (228 tests, all green) and `./gradlew build -x test` both succeed;
artifact `build/libs/frens-1.1.137-release+1.21.11.jar`. The two brief-mandated static checks ran
over `src/main/java/net/wcfcarolina13/GameAI/souls`: the credential-reference grep
(`Authorization|Bearer|apiKey|...`) is clean; the privileged/legacy-reference grep
(`FunctionCallerV2|withPermissions|LLMServiceHandler|LLMOrchestrator|MemoryStore`) surfaces one
match — a `SoulChatRouter` class Javadoc sentence describing that soul turns do NOT route through
`LLMOrchestrator` (prose, not a call site or import). Per the brief's exact instruction for any
match, code was not edited; the match is reported verbatim in the runbook for controller
adjudication. No code review, mod_version bump, or deploy performed in this task — those are
separately scoped (code review is the controller's whole-branch pass; manual acceptance and any
release decision are the user's).

## Journal witnessed gameplay transitions (2026-08-23)

New `GameAI/souls/SoulEventObserver.java` journals gameplay transitions as bounded, factual
`SoulTypes.SoulEvent`s recorded via `SoulRuntime.recordEvent` — combat start/end, bot/owner
damage, sleep/wake, dimension changes, quest-stage changes, death/respawn, and task lifecycle.
Split in two: a static production surface (`initializeProduction`, `onServerTick`,
`onBotDamage`/`onPlayerDamage`/`onBotDeath`/`onBotRespawn`, `onTaskStarted`/`onTaskPaused`/
`onTaskFinished`) that converts live Fabric/Minecraft state into primitives, and a data-only
instance seam (`observe`, `noteBotDamage`, `noteOwnerDamage`, `tickCombat`, static `taskOutcome`)
that never touches a Minecraft type — same seam discipline as the rest of the soul-communication
package, so `SoulEventObserverTest` runs without a Minecraft server.

Session state per bot is intentionally small: last dimension, sleeping flag, quest signature, and
a combat-quiet deadline. A bot's first observation only seeds this baseline — it never emits a
synthetic transition on first sight. Combat starts on the first hit in a quiet window and ends
once `onServerTick`'s 20-tick sampling (or a direct `tickCombat` call) sees the quiet deadline has
passed; a second hit inside the window refreshes the deadline without re-emitting `COMBAT_STARTED`.
Owner-damage events only fire when the bot actually witnessed it — same owner as
`CompanionCommunicationPolicy.resolveController` resolves, and within
`CompanionCommunicationPolicy.VISIBLE_RANGE_BLOCKS`. `onBotDeath` records the death event before
calling `SoulRuntime.cancelBot` (order matters: cancelling first would make the sink's
`hasActiveProfile` gate reject the death event itself). Task events never log prompt text or
command arguments — only the ticket's fixed `name()`, its `:`-prefix category, terminal state, and
a `sanitizeReasonCategory` bucket derived from the raw cancel reason (never the raw reason itself).

`TaskService` hooks: `onTaskStarted` after each successful ticket-insertion return in `beginSkill`
(3 paths) and `beginSystemTask` (2 paths) — `beginAmbientSkill` inherits `beginSkill`'s call since
it delegates rather than re-inserting, so it never double-fires; `onTaskPaused` in `requestPause`
right after `setState(PAUSED)` succeeds; `onTaskFinished` in `complete` right after `setState`,
before the active slot is cleared. `taskOutcome` maps `COMPLETED` → `TASK_COMPLETED`, cancel-
requested `ABORTED` → `TASK_CANCELLED`, any other `ABORTED` → `TASK_FAILED`.

`Frens.java` wiring: `SoulEventObserver.initializeProduction()` right after `SoulRuntime.start`;
`onBotDamage`/`onPlayerDamage` forwarded from `ALLOW_DAMAGE`; `onBotDeath` from `AFTER_DEATH`;
`onBotRespawn` from `AFTER_RESPAWN`; `onServerTick` registered on `END_SERVER_TICK`;
`resetSession()` called in `SERVER_STOPPED` alongside the other per-world session resets.

TDD: `SoulEventObserverTest` covers the brief's mandated
`damageStartsCombatOnceAndCooldownEndsItOnce` case verbatim, plus first-observation seeding,
sleep/wake edges, dimension change, quest-stage change, owner-damage witness filtering,
disabled/unbound sink no-op, and the `taskOutcome` mapping — 8 tests, all data-only. RED confirmed
by temporarily removing `SoulEventObserver.java` (compile failure); GREEN after restoring it.
228 tests pass across the full suite; `./gradlew build -x test` succeeds.

## Add explicit `/bot soul` pilot controls (2026-08-23)

New `Commands/BotSoulCommands.java` attaches a `literal("soul")` subtree under `/bot` (next to the
legacy, untouched `/bot llm`): `status [bot]`, `system <on|off>`, `model <model>`, `enable <bot>`,
`disable <bot>`, `reset <bot>`.

`system`/`model` require `Frens.isOperator(source)`; they write `ManualConfig`
(`setSoulsEnabled`/`setSoulModel`), call `save()`, then AWAIT `SoulRuntime.reloadSettings(CONFIG)`
before reporting success, so the live pipeline never drifts from the just-persisted config. Model
names are sanitized by the pure `validatedModel(String)` helper — trimmed, non-blank, no control
characters, ≤128 chars.

`enable`/`disable`/`reset`/`status <bot>` require the command actor be either the bot's exact
recorded owner or an operator (`CompanionCommunicationPolicy.isPrivateSoulAuthorized`). `enable`
always binds the pilot's one supported profile (`frens:jake`, resolved through the pure
`profileId(String)` alias helper so `"jake"`/`"frens:jake"` are equivalent and anything else is
rejected) via `SoulRuntime.bindJake` + `setActive(true)` — naming a bot "Jake" in-game does nothing
by itself; only this explicit command grants the profile. `disable` calls `setActive(false)` only,
never touching the store's on-disk files. `reset` always archives the **actor's own** direct thread
with the target bot (`ConversationKey(botId, actor.getUuid(), DIRECT)`), reporting the new epoch —
an operator resetting someone else's bot can never erase that other player's private conversation.
`status` with no `[bot]` picks the actor's own soul-bound online bot (first `BotRegistry` entry
with an active profile the actor is authorized to see); it reports master flag, provider, model,
provider health, queue depth, bot UUID/profile/active state, and the actor's current direct epoch —
no URLs or keys ever leave `SoulRuntime.Status`.

Every `CompletableFuture` completion from `SoulRuntime` (`reloadSettings`, `bindJake`, `setActive`,
`reset`, `status`) schedules its chat feedback back onto the server thread via
`source.getServer().execute(...)` before touching `ServerCommandSource`/chat — these futures
resolve on the store's writer thread or the scheduler, never the server thread.

TDD: `Commands/BotSoulCommandsTest.java` covers the brief's two mandated cases plus extra coverage
of `profileId`/`validatedModel` (case-insensitivity, blank/null, embedded control character,
exact-128 boundary) — the only Minecraft-free pure helpers in this class; the Brigadier command
executors themselves need `ServerCommandSource`/`MinecraftServer`/`ServerPlayerEntity`, which this
harness's Mockito/ByteBuddy setup cannot mock (same constraint documented on `SoulChatRouterTest`),
so they're exercised in-game per the phase gate. Verified RED (compile failure with the test
present and the class absent) before GREEN. `./gradlew test --tests
…Commands.BotSoulCommandsTest` — 4/4 pass; full `./gradlew test` — all pass; `./gradlew build -x
test` — BUILD SUCCESSFUL.

Files: `Commands/BotSoulCommands.java` (new), `Commands/modCommandRegistry.java` (attaches
`.then(BotSoulCommands.build())` right after the legacy `/bot llm` block, before
`BotInventoryCommands.build()`), `Commands/BotSoulCommandsTest.java` (new), `changelog.md`.

## Keep broadcast chat out of soul routing (2026-08-23, 1.1.137)

Review finding on the previous entry: `Frens.java`'s soul-routing gate checked only
`routedBots.size() == 1`, but `resolveChatTargets`'s "bots"/"all bots" broadcast branch also
produces a size-1 target list whenever the server has exactly one registered bot — so `bots how
are you` with one companion silently became a soul DM, violating "`bots`/`all bots` broadcasts
never route to souls."

Fixed by adding a `broadcast` flag to the private `ChatTarget` record, set `true` only inside
`resolveChatTargets`'s keyword-match branches (`"bots"`/`"allbots"` and the two-token `"all
bots"`), never derived from list size and never true for an explicit bot-name match. The
soul-routing gate now calls a new pure static `SoulChatRouter.isSingleBotAddress(int
routedBotCount, boolean broadcastKeyword)` (`routedBotCount == 1 && !broadcastKeyword`) instead of
inlining the size check, so the fix is unit-testable without a running server. The pre-existing,
unrelated `handleLegacyInlineActionPrompt` fallback (also gated on `routedBots.size() == 1`,
further down the same method) was deliberately left untouched — it isn't part of the soul pilot
and the finding didn't implicate it.

Test coverage (`SoulChatRouterTest`, +4 cases): one bot registered + broadcast keyword →
`isSingleBotAddress` false (the exact scenario from the finding); an explicit single-bot name →
true; a multi-bot explicit list or zero targets → false regardless of the broadcast flag.
`./gradlew test --tests …SoulChatRouterTest` — 11/11 pass; full `./gradlew test` — 216/216 pass, 0
failures; `./gradlew build -x test` — BUILD SUCCESSFUL.

Considered adding `.exceptionally()` logging to the discarded `submitTurn` future per the review's
optional suggestion, but confirmed it would be dead code: `SoulConversationService.submit`'s
returned future always completes normally (`DELIVERED`/`FAILED`, never exceptionally) — every
internal failure point is already caught by a `whenComplete`/`thenCompose` chain that routes to
`failTurn` (which logs and notifies the player) before completing the outer future normally.
Left unchanged.

Files: `Frens.java`, `GameAI/souls/SoulChatRouter.java`, `SoulChatRouterTest.java`, `changelog.md`.

## Route authorized Jake DMs exclusively to souls (2026-08-23, 1.1.137)

`GameAI/souls/SoulChatRouter` gates whether an already-resolved single-target bot DM in `Frens.java`'s targeted-chat block is handled exclusively by the soul-communication pilot instead of the legacy `LLMOrchestrator` path. `decide(masterEnabled, indexReady, profileActive, pipelineAvailable, authorized, reachability)` is the pure coarse decision: master off → `NOT_SOUL`; master on but the index still loading → `CONSUMED` (profile activation is unknown until the index is warm, so this is checked before `profileActive`); master on, index ready, no active bound profile → `NOT_SOUL` ("unbound"); master on, index ready, profile active → `CONSUMED` unconditionally, since an unauthorized or unreachable turn is still exclusively consumed with its own refusal rather than silently falling through to legacy routing. `tryRoute(bot, sender, prompt)` then walks the fine-grained order the brief specifies — index-readiness, cached `hasActiveProfile`, `pipelineAvailable`, `CompanionCommunicationPolicy.isPrivateSoulAuthorized`, `CompanionCommunicationPolicy.classifySoulReachability`, server-thread `SoulSnapshotBuilder.capture`, then `SoulRuntime.submitTurn` — sending exactly one of four deterministic private notices (LOADING / INVALID PIPELINE with `safeValidationError()` / UNAUTHORIZED / UNREACHABLE) and returning `CONSUMED` without ever appending history or invoking a provider, or (only once every gate passes) capturing a grounding snapshot and submitting an `AcceptedTurn`.

**Blocker found and resolved (controller-authorized correction):** `SoulRuntime` (Task 8) never wired a turn-submission entry point — no method or accessor exposed `Pipeline.conversationService()` to any caller outside the class, and Task 8's own report explicitly flagged this as deferred to "the chat/command router, a later task." Reported `NEEDS_CONTEXT` rather than guessing; the controller authorized adding one minimal method, `SoulRuntime.submitTurn(SoulTypes.AcceptedTurn)`, to the briefed file set. It reads `pipelineRef` at call time (the same plain-read discipline `isMasterEnabled()`/`safeValidationError()` already use, deliberately *not* taken under `lifecycleLock` so an unrelated turn is never serialized against every lifecycle transition) and fails closed with `SoulConversationService.Submission.FAILED` — without touching the pipeline — when the runtime is stopped or the current settings aren't enabled and valid; otherwise it delegates to `pipelineRef.get().conversationService().submit(turn)`. No other `SoulRuntime` behavior changed.

`Frens.java`'s targeted-chat block (`ServerMessageEvents.CHAT_MESSAGE`, ~line 1204) gained one new block between the quest fast-path and the legacy `LLMOrchestrator` loop: when `routedBots.size() == 1` and the prompt is non-empty, `SoulChatRouter.tryRoute` runs (wrapped in try/catch, matching the file's existing "don't let optional AI wiring break chat" pattern for `FunctionCallerV2`), returning from the chat callback on `CONSUMED`. Multi-bot ("bots"/"all bots") targets skip this block entirely via the size check and always reach the legacy loop unchanged. The legacy `"Processing your message, please wait."` line was already scoped inside the per-bot legacy loop (not printed before it), so a `CONSUMED` soul turn — which returns before that loop is ever reached — was already structurally guaranteed to never emit it; no line needed to move. With souls disabled or no `SoulRuntime` installed, `tryRoute` returns `NOT_SOUL` via one `Optional`/boolean check before touching any Minecraft state, and execution falls through to the exact same legacy loop, in the same order, with the same messages as before this change.

Test coverage (`SoulChatRouterTest`, 7 cases) locks in the brief's two mandated `decide()` matrix tests plus two more `decide()` branch cases (loading short-circuits ahead of profile-activation; ready+unbound is `NOT_SOUL`) and three cases against `SoulRuntime#submitTurn` — the seam `tryRoute`'s final step delegates to — proving an authorized/reachable/ready turn is submitted to the mocked `SoulConversationService` exactly once, and that a not-enabled or stopped runtime fails closed without ever calling the service. `tryRoute` itself takes `ServerPlayerEntity` parameters and calls `bot.getEntityWorld().getServer()`/`CompanionCommunicationPolicy`/`SoulSnapshotBuilder.capture`, all needing live Minecraft state; consistent with `SoulMessageDeliveryTest`'s documented hard limit (`MinecraftServer` cannot be mocked or constructed in this harness), it is left for in-game verification. RED was captured by stubbing `decide()` to always return `NOT_SOUL` and `submitTurn` to always return `FAILED` without touching the service, confirming 4 of 7 tests fail, before restoring the real implementations for GREEN.

Files: `GameAI/souls/SoulChatRouter.java`, `GameAI/souls/SoulRuntime.java`, `Frens.java`, `SoulChatRouterTest.java`.

## Per-server soul runtime lifecycle (2026-08-23, 1.1.137)

`GameAI/souls/SoulRuntime` is the one-per-`MinecraftServer` owner of the soul-communication pipeline: a static `AtomicReference<SoulRuntime>` singleton (`start`/`stop`/`current`, mirroring `BotRegistry`/`TaskService`) wrapping a world-local `SoulStore`, an async index preload that gates `isReady()`, and a `Pipeline` record (`SoulSettings` + `SoulModelProvider` + `SoulGenerationScheduler` + `SoulConversationService`) swapped as one atomic unit by `reloadSettings`. `reloadSettings` always rebuilds the pipeline — even for disabled/invalid settings — so storage/status stay backed by a consistent object graph; only `isConversationEnabled()` (`enabled && valid`) gates whether it is ever used to generate a reply, and `pipelineAvailable()` additionally requires `isReady()`. `reset(key)` archives via `SoulStore.archiveAndReset`, then calls `conversationService.invalidate(key, newEpoch)` before the returned future completes. `stop()` cancels the current scheduler/provider and closes the store without ever calling `awaitTermination` — safe to call from the server thread. The package-private 5-arg constructor + `installForTest` are the only test seam; the class never references `MinecraftServer`/`ManualConfig` as static state and never touches `Frens`, so its test never trips `Frens`'s static initializer.

`SoulStore` gained: `preloadIndex()` (walks `<world>/frens/souls/v1/<bot-uuid>/soul.json` into a `ConcurrentHashMap<UUID, SoulState>` cache — never creates `root` or any subdirectory, so a never-enabled world stays untouched on disk), a synchronous `cachedState(UUID)` read over that cache (kept current by every subsequent `saveState`), and a production `SoulStore(Path worldRoot)` factory that opens its own named daemon writer thread (`frens-soul-store`) — the existing injected-executor constructor is untouched, so `SoulStoreTest` needed no changes. `close()` now sets a `closed` flag checked by `submit()` (any write after `close()` fails fast with a `RejectedExecutionException`-backed future instead of throwing synchronously) and calls only `executor.shutdown()` — no `awaitTermination`/`shutdownNow` — so already-queued writes drain in the background instead of blocking the caller.

`Frens.java` wiring: `SoulRuntime.start(server, CONFIG)` right after `serverInstance = server` in `SERVER_STARTED` (line ~738); `SoulRuntime.stop()` as the first statement in `SERVER_STOPPING`, before `TaskService.markServerStopping()` (line ~795); and in `ServerPlayConnectionEvents.DISCONNECT`, `SoulRuntime.current().ifPresent(...)` calls `cancelBot` for a registered fake player or `cancelPlayer` for a real player, before `BotPersistenceService.onBotDisconnect` (line ~918). `setActive(botId, false)` also calls `cancelBot(botId)`.

**Reconciled ambiguity (`cancelBot`/`cancelPlayer` semantics):** the brief specifies the call sites but not what "cancel" does at the scheduler level, and `SoulGenerationScheduler` (not in this task's file list) only supports invalidating a *known* `ConversationKey` — there is no "cancel everything for this bot/player across all conversations" primitive, and this task doesn't yet wire a turn-submission entry point that would populate an active-keys registry (that lands with the chat/command router in a later task). `cancelBot` therefore deactivates the bot in the store (stopping future dispatch); `cancelPlayer` is a documented no-op today, since `SoulMessageDelivery.ProductionDeliveryGuard` already fails closed the instant either party stops resolving via the player manager, making an in-flight reply to a disconnected party undeliverable regardless.

Test coverage (`SoulRuntimeTest`, 17 cases) locks in the brief's two mandated tests (disabled settings never call `provider.generate`; `stop()` closes the provider and clears `current()`) plus: `stop()` also closes the scheduler and store, and is idempotent with nothing installed; `isReady()`/`pipelineAvailable()` track preload completion rather than construction; `safeValidationError()` surfaces the deterministic `SoulSettings` message; `reloadSettings` swaps the pipeline and closes the previous provider/scheduler; `reset` invalidates the conversation service at the store's new epoch; `hasActiveProfile` requires both `active` and a non-blank profile id; `bindJake` binds `"frens:jake"`; `setActive(false)` routes through `cancelBot`; `status` combines live settings/store/provider-health/queue-depth; `recordEvent` appends through the store; `cancelPlayer` never touches the provider; and two cases against a *real* `SoulStore` (not mocked) confirming `preloadIndex` creates no directories for an absent root and correctly populates `cachedState` from disk across a simulated restart while `close()` makes further writes fail fast — kept in this file rather than `SoulStoreTest` per the task's exact-five-file commit list. RED was captured via `./gradlew test --tests …SoulRuntimeTest` failing to compile (`cannot find symbol: SoulRuntime`, plus the new `SoulStore` methods) before either class existed. `net.minecraft.server.MinecraftServer` still cannot be mocked in this harness, so `start()` itself is untestable at the unit level and left for in-game verification, consistent with `SoulMessageDeliveryTest`'s documented limitation.

Files: `GameAI/souls/SoulRuntime.java`, `GameAI/souls/SoulStore.java`, `Frens.java`, `SoulRuntimeTest.java`.

## Heard-to-spoken soul conversation lifecycle (2026-08-23, 1.1.137)

`GameAI/souls/SoulConversationService` orchestrates one turn end to end with a fixed promise chain: `SoulStore.beginHeardTurn` (unconditional — a turn is always remembered as heard) → `recentBefore`/`recentEvents` fetched concurrently → `SoulPromptAssembler.assemble` → exactly one call through `SoulGenerationScheduler` → `SoulResponseValidator.validate` → (accepted only) `Delivery.deliverReply` → (delivered only) `appendSpoken` + a content-free `DIRECT_CONVERSATION` event. A reply is committed as `SPOKEN` memory only after `Delivery.deliverReply` actually completes the private send; any failure before then appends a `FAILURE` record and sends one of six deterministic, provider-detail-free status lines — except when the failure code is `CANCELLED` or `STALE_EPOCH` (a mid-flight `archiveAndReset`/`invalidate`), where the append is skipped outright since the token's epoch no longer matches the store's cursor and `SoulStore` would itself reject it as stale. `invalidate(key, newEpoch)` is a pure passthrough to `SoulGenerationScheduler.invalidate`.

`GameAI/souls/SoulMessageDelivery` is the server-thread-only `Delivery` implementation: `deliverReply` schedules via `server.execute`, resolves the bot/player `ServerPlayerEntity`s, consults a `DeliveryGuard`, and only then sends `Text.literal(botName + ": " + text)` privately — it never constructs a bot command source and never touches `ChatUtils` or a voice mapper. Its nested `ProductionDeliveryGuard` requires, all at once: master enabled (a caller-supplied `BooleanSupplier`, since `SoulRuntime` doesn't exist yet), the bot's bound profile unchanged, the conversation's cursor epoch still matching the token's epoch, both parties online, `CompanionCommunicationPolicy.isPrivateSoulAuthorized` still true, and `CompanionCommunicationPolicy.classifySoulReachability` not `UNREACHABLE`. The profile/epoch recheck is a bounded (500ms), fail-closed `store.state(...)` read — the one non-purely-synchronous piece of an otherwise-synchronous guard, called from inside `server.execute`. The six-gate decision itself is factored into a Minecraft-free static `evaluate(...)` combinator so it's exhaustively unit-testable without a server.

**Seam mismatch found and resolved:** the task brief's fixture constructs `SoulConversationService` with a `SoulProfileRegistry` instance argument (`new SoulConversationService(store, SoulProfileRegistry.loadBuiltIns(), ...)`), but `SoulProfileRegistry` (Task 5) is a static, non-instantiable registry (private constructor) and `loadBuiltIns()` returns `void` — passing it as a constructor argument does not compile. Resolved by dropping the `profiles` parameter from the constructor entirely and calling `SoulProfileRegistry.require(...)` statically inside `dispatchProvider`, with `loadBuiltIns()` called as its own `@BeforeEach` statement instead.

**Second issue found via RED/GREEN itself (not anticipated by the brief):** both production classes originally logged through `Frens.LOGGER`. Merely referencing the `Frens` class triggers its static initializer (`resolveOperatorPermissions()`, which reflectively touches Minecraft permission classes), which throws `IllegalStateException` outside a running game — this silently aborted `SoulConversationService`'s async callback chains before they reached `outcome.complete(...)`, manifesting as `CompletableFuture.get()` timeouts in every test, not a visible exception. Fixed by giving both classes their own `LoggerFactory.getLogger("frens.souls")`, consistent with the rest of this package's documented "no Minecraft/Fabric/mod-class reference" boundary. A second, unrelated discovery during the same debugging pass: `net.minecraft.server.MinecraftServer` cannot be mocked at all in this test harness (Mockito: "Cannot instrument class ... because it or one of its supertypes could not be initialized") — no existing test in this repo mocks it either, confirming this is a hard environment limit, not a shallow-vs-deep mocking choice.

Test coverage (`SoulConversationServiceTest`, 6 cases) locks in the brief's two mandated tests (`SPOKEN` recorded only after successful delivery; failed delivery never becomes `SPOKEN` memory) plus: the current inbound message appears exactly once as the final `USER` message and is excluded from its own turn's prior history while a previous turn's heard/spoken pair does appear as history; a provider failure (`TIMEOUT`) appends a typed `FAILURE` record; an invalid/rejected provider response appends a `MALFORMED` `FAILURE` record; and a conversation reset while a turn's job is still queued (`invalidate` racing a still-queued job under `SoulGenerationScheduler(1, 8)`'s single concurrency slot) fails the submission but leaves only the original `HEARD` record — no `FAILURE` append is attempted against the now-stale token. `SoulMessageDeliveryTest` (7 cases) exhaustively covers `evaluate`'s six fail-closed gates; `deliverReply`/`deliverStatus`/`ProductionDeliveryGuard`'s live entity and store resolution are documented as untestable here and left for in-game verification. RED was captured by moving both production classes aside and observing the expected `cannot find symbol` compile failure before restoring them for GREEN.

Files: `GameAI/souls/SoulConversationService.java`, `GameAI/souls/SoulMessageDelivery.java`, `SoulConversationServiceTest.java`, `SoulMessageDeliveryTest.java`.

## Projecting authoritative Frens state into immutable soul grounding (2026-08-23, 1.1.137)

`GameAI/souls/SoulSnapshotBuilder.capture(server, bot, player, reachability)` is the server-thread-only boundary that reads live bot/player/world state and projects it into an immutable `SoulTypes.GroundingSnapshot`. It never fabricates a snapshot for a turn that already resolved to `UNREACHABLE` — calling `capture` with that reachability throws `IllegalArgumentException` rather than silently proceeding. Coordinates are rounded to 8-block increments (`roundToEight`), time-of-day is bucketed into `day`/`dusk`/`night`/`dawn` (`timePhase`, boundaries at 12000/13000/23000 ticks, matching `BotQuestService`'s existing `time_night` predicate), the player's compass position is resolved to one of 8 points (`cardinalDirection`, north = -Z / east = +X per vanilla convention), and inventory resource stacks are aggregated by display name and capped to the top 6 by count (`topResourceSummary`). REMOTE reachability always yields `player().isEmpty()` — enforced at the `assemble` seam itself (not just at the call site) so a caller can never leak the player's local surroundings into a prompt for a bot with no shared line of sight. Recruitment/permanence/companion-quest-stage fields are populated only when the authoritative `SurvivalRecruitmentState`'s configured bot alias case-insensitively matches the bot being captured, so a world-level recruitment flag can never bleed onto an unrelated bot.

`CompanionCommunicationPolicy` gained the soul-communication policy boundary: `classifySoulReachability(bot, player)` returns `LOCAL` inside `VISIBLE_RANGE_BLOCKS` (32 blocks), `REMOTE` only when the existing `canBotChatToController` delivery rules allow it (comm items, wizard's tome, enchanting-table proximity), and `UNREACHABLE` otherwise — its pure projection `classifySoulReachability(sameWorld, distanceSquared, remoteAllowed)` is `public` (not package-private as the brief's pseudocode showed) because the soul unit tests live in a different package (`GameAI.souls`) than the policy (`GameAI.services`) and must exercise it without a Minecraft server. `isPrivateSoulAuthorized(actor, bot)` requires the actor be an operator or the bot's exact recorded owner UUID; unlike `isAllowedToControl`, an unowned bot is deliberately **not** eligible, and its pure seam `isPrivateSoulAuthorized(operator, actorId, ownerId)` is `public` for the same cross-package reason.

`GameAI/services/BotQuestService` gained an immutable `QuestSnapshot(String id, String intent, int actionIndex, int actionCount, long expiresTick)` record and `getActiveQuestSnapshot(UUID botId)`, copying only primitives/strings out of the live `ActiveQuestRuntime`/`QuestDefinition` so the running quest state can safely cross into a `GroundingSnapshot`.

Test coverage (`SoulGroundingTest`, 11 cases) locks in the brief's two mandated tests (`isPrivateSoulAuthorized` requires exact owner-or-operator, REMOTE snapshots omit player state) plus the 32-block LOCAL/REMOTE/UNREACHABLE boundary (at, just past, cross-dimension), 8-block coordinate rounding, all 4 cardinal/intercardinal directions, the 4 time-of-day phase boundaries, and the 6-entry resource-summary cap. RED was captured via `./gradlew test --tests …SoulGroundingTest` failing to compile (`cannot find symbol: SoulSnapshotBuilder`) before the class existed.

Files: `GameAI/souls/SoulSnapshotBuilder.java`, `GameAI/services/CompanionCommunicationPolicy.java`, `GameAI/services/BotQuestService.java`, `SoulGroundingTest.java`.

## Conversational output validation for soul dialogue (2026-08-23, 1.1.137)

`GameAI/souls/SoulResponseValidator` is the last checkpoint between a provider's raw text and anything spoken by a bot: `validate(raw, botDisplayName)` returns a `ValidationResult(accepted, text, FailureCode, reason)`. It removes `<think>...</think>` and `<analysis>...</analysis>` blocks (case-insensitive, DOTALL — hidden reasoning never reaches dialogue), a leading `"<botDisplayName>:"` label the provider echoed back, Minecraft legacy `§`-formatting codes, and ISO control characters other than newline/tab; it collapses runs of more than two consecutive blank lines down to two. It rejects (always `FailureCode.MALFORMED`) blank output, any raw NUL character, a fenced ```` ``` ```` payload (providers should never be emitting tool/JSON syntax as dialogue), and cleaned output over 1,200 characters. The validator never parses or dispatches the cleaned text or the rejection reason as a command — both are inert data for the caller to speak or log, matching the pilot's plain-dialogue-only contract.

Test coverage (`SoulResponseValidatorTest`, 12 cases) locks in the brief's two mandated tests (hidden-reasoning + speaker-prefix strip, tool-syntax/excessive-output rejection) plus blank/null input, `<analysis>` block stripping, control-character stripping while preserving newline/tab, NUL rejection, section-sign formatting strip, ordinary multiline prose passing through unchanged, blank-line collapsing, an unrelated speaker label (`"Steve:"`) staying untouched, and the exact 1,200-character boundary staying accepted. RED was captured by running the test against the not-yet-created `SoulResponseValidator` class (`cannot find symbol` compile failure) before implementing it for GREEN.

Files: `GameAI/souls/SoulResponseValidator.java`, `SoulResponseValidatorTest.java`.

## Jake's authored profile + deterministic prompt assembly (2026-08-23, 1.1.137)

`GameAI/souls/SoulProfileRegistry` is a static registry (like `SkillManager`) that loads built-in `SoulTypes.SoulProfile` definitions from classpath JSON via `loadBuiltIns()` (currently `data/frens/souls/jake.json`), and exposes `register(SoulProfile)` / `require(String profileId)`. `register` rejects a blank id (`IllegalArgumentException`) or a duplicate id (`IllegalStateException`) so profiles can never silently shadow one another; `require` throws `IllegalArgumentException` on an unknown id. `loadBuiltIns()` is idempotent (a `builtInsLoaded` guard) so repeated calls across the process don't collide with themselves.

`GameAI/souls/SoulPromptAssembler` (`assemble(correlationId, model, profile, grounding, priorHistory, recentEvents, currentMessage, timeout)`) deterministically builds a `ProviderRequest` in a fixed order: system contract (stable, provider-neutral, states generated prose has no action authority) → authored identity (profile identity/values/boundaries + its authored examples) → authoritative state (rendered from the `GroundingSnapshot`, never invented) → bounded prior role history (`HEARD`→USER, `SPOKEN`→ASSISTANT, `FAILURE` turns dropped; capped at 20 turns and 12,000 characters, most-recent-first budgeting then re-ordered chronologically) → bounded recent witnessed events (SYSTEM role, capped at 12 events and 4,000 characters, event `facts` map rendered key-sorted for determinism) → a `PRESENT MOMENT` marker message → the current user message exactly once as the final message. `maxOutputTokens` is fixed at 220 (four characters ≈ one token is the deterministic budget proxy documented on the char-budget constants, not computed at runtime). REMOTE grounding renders "remote communication" and its branch never reads `GroundingSnapshot.player()` at all, so a bot with no shared line of sight can't leak the player's local surroundings into the prompt even if a caller mistakenly populated that field. Conversation history and recalled events never get folded into the system contract message itself — only USER/ASSISTANT/SYSTEM messages later in the sequence carry that content — so a player's message or a replayed event can't rewrite the character's operating rules.

Test coverage (`SoulPromptAssemblerTest`, 11 cases) locks in the brief's two mandated tests (`PRESENT MOMENT` immediately precedes the final current-message, REMOTE prompts omit player surroundings while still saying "remote communication") plus bound assertions (history capped at 20 turns / 12,000 chars, events capped at 12 / 4,000 chars, current message appears exactly once) and registry behavior (built-in Jake profile loads and is retrievable, unknown id throws, blank id rejected, duplicate id rejected). RED was captured by running the test against the not-yet-created `SoulPromptAssembler`/`SoulProfileRegistry` classes (`cannot find symbol` compile failure) before authoring the two production classes and `jake.json` for GREEN.

Files: `GameAI/souls/SoulProfileRegistry.java`, `GameAI/souls/SoulPromptAssembler.java`, `src/main/resources/data/frens/souls/jake.json`, `SoulPromptAssemblerTest.java`.

## Bounded local soul generation: provider contract, Ollama adapter, scheduler (2026-08-23, 1.1.137)

`GameAI/souls/SoulModelProvider` is the provider-neutral contract: `generate(ProviderRequest)` returns a `Call` pairing a `CompletableFuture<ProviderResult>` with a `cancel` `Runnable`. A well-behaved provider never fails that future exceptionally for an ordinary generation problem — timeouts, upstream errors, malformed responses, and cancellation are all normal, successful completions carrying a typed `FailureCode`, so a caller can `.join()` without ever touching `try/catch`.

`GameAI/souls/OllamaSoulProvider` implements it against local Ollama's non-streaming `/api/chat`: `stream:false`, `keep_alive:"5m"`, `options.temperature:0.7`, `options.num_predict` from `request.maxOutputTokens()`, roles serialized lowercase, and only `message.content` read back as dialogue — the raw response body is never surfaced in a result or a log. Non-2xx maps to `UNAVAILABLE`, `HttpTimeoutException` to `TIMEOUT`, a cancelled HTTP future to `CANCELLED`, and a missing/unparsable body to `MALFORMED`. `firstOutputMillis` stays `null` since a non-streaming call has no real first-token latency to report. `health()` is a 1500ms-timeout `GET <base>/api/tags`. A package-private `Transport` functional interface (`OllamaSoulProvider(URI, String, Transport, ObjectMapper)`) lets tests inject a fake HTTP layer; the public constructor builds and owns a real `HttpClient`, releasing it via the non-blocking `shutdownNow()` in `close()` rather than the blocking `close()`/`shutdown()` JDK 21 added to `HttpClient`.

`GameAI/souls/SoulGenerationScheduler(maxConcurrent, queueCapacity)` is a synchronized FIFO queue over an `activeKeys` set and an `activeCalls` map, with no thread pool and no polling — every state transition is driven by a provider future's `whenComplete` calling `pump()`. Two invariants: the same `ConversationKey` never has two calls in flight, and no more than `maxConcurrent` calls run globally regardless of key. `submit(key, epoch, Supplier<Call>)` completes immediately with `OVERLOADED` (supplier never invoked) once the queue backlog reaches `queueCapacity`. `invalidate(key, newEpoch)` cancels an older active call for that key (its own future resolves however the provider maps that cancellation — normally `CANCELLED`) and immediately completes any older still-queued jobs for that key with `STALE_EPOCH`, since those never got a provider call to report anything else. `close()` cancels every active call and completes every still-queued job with `CANCELLED`, without blocking.

Test coverage (`SoulProviderSchedulerTest`, 11 cases) locks in the brief's two mandated tests (same-key non-overlap, HTTP failure never leaking into dialogue text) plus queue overflow, HTTP timeout, explicit cancellation, two-key global concurrency cap, epoch invalidation, malformed-JSON mapping, the exact outbound JSON shape, and the `/api/tags` health check. RED was captured by temporarily moving the three new production files aside and confirming `SoulProviderSchedulerTest` fails to compile (`cannot find symbol` / `package does not exist`, 34 errors) before restoring them for GREEN.

Files: `GameAI/souls/SoulModelProvider.java`, `GameAI/souls/OllamaSoulProvider.java`, `GameAI/souls/SoulGenerationScheduler.java`, `SoulProviderSchedulerTest.java`.

## Crash-tolerant world-local soul storage (2026-08-23, 1.1.137)

`GameAI/souls/SoulStore` persists the soul-communication domain model (`SoulTypes`) under `<world>/frens/souls/v1`, pure Java with no Minecraft/Fabric imports so it can be constructed and tested off the server thread. Each bot owns a `soul.json` (profile binding, active flag, per-conversation cursors keyed `DIRECT:<player-uuid>`) written temp-file + `ATOMIC_MOVE` with a `REPLACE_EXISTING` fallback, and an append-only `conversations/<player-uuid>/active.jsonl` transcript. All filesystem work funnels through an injected single-writer `ExecutorService` — every append serializes one record, a line separator, and a flush before its future completes, so the transcript never holds a half-written line.

`archiveAndReset` moves the active transcript into `conversations/<player-uuid>/archive/epoch-<epoch>-<UTC timestamp>.jsonl` rather than deleting it, then bumps the conversation's epoch. `beginHeardTurn` allocates the epoch/sequence pair baked into the returned `TurnToken`; `appendSpoken`/`appendFailure` compare that epoch against the live cursor and fail the future with a `StaleEpochException` (`FailureCode.STALE_EPOCH`) on mismatch, so a reply generated against a conversation that has since been reset can never land. Load-time recovery treats a malformed *final* line in a JSONL file as an interrupted write — it's quarantined to `<filename>.corrupt-tail-<timestamp>` and the file is atomically rewritten with only its complete lines — while a malformed record anywhere *before* the final line fails the load visibly, since that pattern indicates real corruption rather than a crash mid-append. `recent`/`recentBefore` bound conversation history by both turn count and a total character budget, trimmed from the most recent turn backward.

Test coverage (`SoulStoreTest`, 13 cases) locks in the brief's mandated archive/stale-epoch scenario plus corrupt-tail quarantine, malformed-mid-file rejection, restart recovery through a fresh `SoulStore` instance pointed at the same world root, exact on-disk paths, maxTurns/maxChars bounding, `recentBefore` sequence exclusion, failure-turn metadata, profile/active-state round-tripping, event append/read, and that `close()` leaves no executor thread alive. Jackson is configured with `new ObjectMapper().registerModule(new JavaTimeModule())` per the brief; despite the `jackson-datatype-jsr310` 2.8.4 / `jackson-databind` 2.17.2 version skew already present in `build.gradle`, `Instant` round-tripped through real files with no compatibility issue.

Files: `GameAI/souls/SoulStore.java`, `SoulStoreTest.java`.

## Soul communication domain model: immutable types and default-off settings (2026-08-23, 1.1.137)

Foundation for an opt-in, local-Ollama-only conversational pilot for Jake. `GameAI/souls/SoulTypes` adds the immutable record/enum boundary (conversation identity, provider request/result, grounding snapshots, profiles, events) that every later piece of the pilot will pass across worker threads — strings, primitives, UUIDs, instants, durations, and defensively-copied collections only, never Minecraft classes. `GameAI/souls/SoulSettings` validates the new configuration into a single immutable snapshot: only the local `ollama` provider is accepted, the base URL must be HTTP/HTTPS, the request timeout is clamped to 10–180s, and the queue capacity to 1–32.

`ManualConfig` gains five new non-secret fields — `soulsEnabled` (default **false**), `soulProvider` (default `"ollama"`), `soulModel`, `soulRequestTimeoutSeconds`, `soulQueueCapacity` — kept entirely separate from the legacy `defaultLlmWorldEnabled` toggle and with no API-key field of any kind; the pilot never talks to a hosted provider.

`ManualConfig.FILE_PATH` was also changed from an eager `static final` field initializer to a lazily-resolved `filePath()` accessor. It previously called into `LauncherEnvironment` → `FabricLoader.getInstance().getGameDir()` at class-initialization time, which throws outside a real Fabric launch and made `ManualConfig` impossible to construct or mock in a plain JUnit JVM. Resolution now happens on first real use (`save()`/`load()`), which in practice is always after Fabric has launched, so runtime behavior is unchanged — but the config class is now unit-testable, which the new `SoulFoundationTest` mock-based coverage depends on.

Files: `GameAI/souls/SoulTypes.java`, `GameAI/souls/SoulSettings.java`, `FilingSystem/ManualConfig.java`, `SoulFoundationTest.java`.

## Sleeping screen explains the zzz companion command (2026-08-22, 1.1.137)

The vanilla sleeping screen now shows a compact Frens-styled hint above the Leave Bed button: companions need to sleep too, and typing bare `zzz` in chat makes every active Frens bot owned by the player in that dimension try to sleep. This documents the existing 1.1.136 behavior at the moment players need it, including when a bot is too far away for automatic co-sleep.

The hint renders only while the local player is sleeping on the vanilla sleeping-chat screen. Its instruction wraps to fit smaller GUI widths, and focused coverage locks in the sleep-state and screen-state visibility gates.

Files: `FrensClient.java`, `GraphicalUserInterface/SleepCommandHintHud.java`, `SleepCommandHintHudTest.java`.

## zzz reaches remote owned bots in the same dimension (2026-08-22, 1.1.136)

The 1.1.135 freeze fix incorrectly coupled chat-command targeting to the 16-block automatic co-sleep radius. That prevented a bot near its own bed from receiving bare `zzz` whenever its commander was farther away, even though the command is ownership-gated and the bot searches for beds around its own position.

Bare `zzz` now targets every active bot controlled by the sender in the sender's dimension, without a commander-distance check. Bots in other dimensions and bots controlled by another player remain excluded. The worker-thread sleep execution, task cancellation, debounce state machine, and local bed selection from 1.1.135 are unchanged. The 16-block radius remains in place for automatic co-sleep behavior.

Focused regression coverage locks in same-dimension owned-bot targeting and rejects bots owned by another player.

Files: `GameAI/services/BotZzzSleepService.java`, `BotZzzSleepServiceTest.java`.

## zzz sleep no longer blocks the server or shutdown (2026-08-22, 1.1.135)

The live log showed automatic co-sleep correctly skip Jake at 19.1 blocks, followed by chat `zzz` selecting him through a separate 48-block radius. The chat handler then invoked the synchronous sleep movement loop through `server.execute`, so all bed pathfinding ran on the server thread. Jake retried inaccessible beds for more than a minute, continued navigating after client shutdown began, and prevented the integrated server from closing until the game was force-quit.

Chat `zzz` now uses a dedicated task-backed worker executor, registers the executing thread for `/bot stop` and shutdown interruption, and checks cancellation between beds and stand attempts. The executor participates in integrated-server start/stop lifecycle handling. Chat and automatic co-sleep now share one 16-block commander proximity policy, and chat triggers only affect bots actually controlled by the sender. Idle-hobby resume also waits until the `zzz` fallback cycle has finished.

The bed scan now stores immutable canonical foot positions in its de-duplication set. Its mutable iterator previously corrupted set membership, causing both halves of a physical bed to be returned and the same bed to be tried twice. Focused regressions cover the shared distance boundary, immutable bed deduplication, worker-thread dispatch, and idle-resume suppression.

Files: `Frens.java`, `GameAI/services/BotZzzSleepService.java`, `GameAI/services/BotWakeUpDialogueService.java`, `GameAI/services/BotIdleResumeService.java`, `GameAI/services/SleepService.java`, `GameAI/services/BotSleepProximityPolicy.java`, `GameAI/services/SleepBedCandidatePolicy.java`, `BotZzzSleepServiceTest.java`.

## Arrow recovery ignores impossible and underwater targets (2026-08-22, 1.1.134)

The live game showed Jake repeatedly chasing arrows stuck in terrain and several blocks underwater. Inventory snapshots confirmed he was using an Infinity bow, whose fired arrows cannot be picked up in Survival. The recovery scanner nevertheless tracked every nearby arrow—including mob arrows, other players’ arrows, Infinity arrows, and wet arrows—and its ten-second chase timeout could not break the loop because the five-tick scan immediately registered the still-present projectile again.

Ordinary arrow recovery now requires all three conditions: the bot fired it, its vanilla pickup permission is `ALLOWED`, and it is not touching water. Ineligible projectiles are also removed from existing tracking and active chase state. Bot-owned tridents remain mandatory recovery targets even underwater; foreign tridents remain ignored. Focused policy tests cover each ownership, pickup-permission, and water branch.

Files: `GameAI/services/BotArrowRecoveryService.java`, `BotArrowRecoveryPolicyTest.java`.

## Shared-inventory tooltips show live keyboard shortcuts (2026-08-22, 1.1.134)

Action tooltips in the shared inventory now append the player’s current world shortcut for Guide, Spells, Stop, Resume, Follow, Home, Sleep, Regroup, Cleanup, Stripmine, Ascent, and Descent. Direct bindings are shown as `[key]`; numbered hotkey-menu actions are shown as `Hold [menu key], then [number]`. If every route for an action is unavailable, the tooltip says `Unbound — configure in Controls`. Actions without a keyboard route omit the shortcut line.

The collapsed action grid, expanded Actions/Spells entries, and Guide/Spells header icons all read from the same live client-keybinding source. The companion hotkey overlay footer also uses that source instead of hard-coded default keys, so rebinding Follow, Go To, or Stop is reflected consistently. Added focused regression coverage for direct, composite, and unbound formatting plus the numbered action-to-slot mapping.

Files: `FrensClient.java`, `GraphicalUserInterface/BotPlayerInventoryScreen.java`, `GraphicalUserInterface/CompanionHotkeyOverlayHud.java`, `FrensClientShortcutHintTest.java`.

## IdleSweep refuses unsafe origins and preserves drop cooldowns (2026-08-22, 1.1.134)

The live play log showed Jake repeatedly activating IdleSweep from a position the shared surface evaluator rated `standable=false`, with two to four steep-drop neighbors. One target was five blocks below the bot. The follow drop guard prevented a fall, but IdleSweep kept pushing toward each target for ten seconds before declaring it unreachable.

IdleSweep now requires a standable origin that is not enclosed underground and has at most one steep-drop neighbor and two blocked cardinal directions. The same safety policy is rechecked whenever an active sweep reaches a new grounded block; if the terrain becomes unsafe, the sweep cancels immediately and blacklists its current target instead of continuing to drive movement.

The cleanup pass also found mixed clocks in the blacklist: expiry values were stored using server ticks but pruned using persistent world time, so cooldowns could disappear immediately after a restart. Blacklist pruning now lives in `BackgroundSweepPolicy` and consistently uses server ticks. Focused regression coverage includes the live unsafe assessment, ravine edges, enclosed underground cells, safe terrain, and expiry-boundary behavior.

Files: `GameAI/BotEventHandler.java`, `GameAI/services/BackgroundSweepPolicy.java`, `BackgroundSweepPolicyTest.java`.

## Fully encased suffocation uses validated emergency snap (2026-08-22, 1.1.134)

The latest play log showed Bob become fully embedded in stone at both feet and head level. Burial rescue detected the condition, but with an empty inventory it chose bare-hand stone mining; Bob died four seconds later before that recovery could complete.

When recent `IN_WALL` damage coincides with solid collision at both body cells, burial rescue now first searches for a validated standable cell within six blocks and snaps the bot there. If no local position exists, it falls back to the previously recorded safe area and then to the existing mining path. Partial overlaps and ordinary stuck detection keep their existing movement/mining behavior, so this only escalates active, fully encased suffocation.

Added regression coverage for the emergency threshold: recent suffocation plus both blocked cells is required.

Files: `GameAI/services/BotRescueService.java`, `BotRescueEmergencySnapPolicyTest.java`.

## Unusable crossbows no longer trap bots in a suppressed melee loop (2026-08-22, 1.1.134)

The latest play log showed Bob repeatedly switching from bare hands back to an unloaded crossbow while a skeleton attacked at close range. `selectBestMeleeWeapon` correctly rejected the crossbow, but the combat caller immediately ran `selectBestWeapon`, which equipped it again. `attackTarget` is melee-only and rejected the crossbow, so the same sequence repeated until Bob died.

Ranged readiness now requires ammunition, creative mode, or an already-charged crossbow. Close-range combat uses one explicit choice: prefer a compliant melee weapon, otherwise use a genuinely fireable ranged weapon, and only then fall back to a tool or bare hands. `selectBestWeapon`, ranged inventory checks, and ranged selection all use the same readiness rule, preventing an unloaded bow or crossbow from being treated as actionable combat gear.

Added focused regression coverage for unloaded, loaded, and pre-charged ranged weapons plus the close-range fallback decision.

Files: `GameAI/BotActions.java`, `GameAI/BotEventHandler.java`, `GameAI/CombatWeaponPolicy.java`, `CombatWeaponPolicyTest.java`.

## zzz logoff feedback to sender; co-sleep skips zzz-active bots (2026-05-17, 1.1.133)

Two user-visible polish fixes from the 1.1.132 success-trace audit ([latest.log](logs) 18:05:20–18:06:02).

**1. "Went to sleep" chat message was being dropped.** [BotZzzSleepService.beginLogoff](src/main/java/net/wcfcarolina13/GameAI/services/BotZzzSleepService.java) was sending the friendly logoff line via `ChatUtils.sendSystemMessage(server.getCommandSource(), ...)`. The server command source has no recipient, so the [ChatUtils router](src/main/java/net/wcfcarolina13/ChatUtils/ChatUtils.java) logged `"No recipient resolved for system message; dropping: '... went to sleep ...'"` and silently dropped it. The user saw nothing in chat when their bots logged off — just the vanilla "Jake left the game" line. Fix: pass the `sender` (the player who typed zzz, already tracked in the StateEntry) through to `beginLogoff` and call `sender.sendMessage(Text.literal(...), false)` directly. Mirrors the `notifyOwner` pattern at [NavigationArtifactService.java:1697](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java#L1697).

**2. Co-sleep service was firing redundantly on zzz-active bots.** When the user got into bed at 18:05:54, [BotWakeUpDialogueService.triggerCoSleep](src/main/java/net/wcfcarolina13/GameAI/services/BotWakeUpDialogueService.java#L178) (which auto-makes nearby bots sleep when the commander does) ran for both bots — but they were already in `WAITING_LOGOFF` state from the zzz trigger. The co-sleep call ran `SleepService.sleep` on a worker thread, hit my 1.1.127 bed-share gate ("Someone's already sleeping — I'll wait it out"), and produced three duplicate chat lines per cycle. Fix: skip co-sleep when `BotZzzSleepService.isInSleepCycle(botId) || BotZzzSleepService.isLoggedOff(botName)` — the zzz service owns the bot's sleep state machine, no need for co-sleep to second-guess it. Adds one log line: `"Co-sleep: {bot} skipped — already in zzz-cycle"`.

Not fixed in this release (flagged for later):

- The `"Fakeplayer 'Jake' still present after removal attempt"` warning during logoff. The bot IS removed eventually (respawn works) but `removeFromPlayerManager` returns false via the reflection fallback. Investigative — not currently breaking anything.
- The 19s sleep-attempt window is still long because the bot walks to the bed before vanilla rejects with `bed.obstructed`. A pre-walk vanilla-rules check (1-block air above head + foot) could cut that to ~0.1s but requires careful mirroring of vanilla's `BedBlock.canBeUsedToSleep` logic — defer until repro shows it as a real pain point.

Files: `GameAI/services/BotZzzSleepService.java` (beginLogoff signature + chat fix), `GameAI/services/BotWakeUpDialogueService.java` (co-sleep skip guard).

## SleepService doesn't thrash on obstructed beds; zzz deadline math uses now-anchor (2026-05-17, 1.1.132)

Root-cause fix for the user's "zzz didn't do anything" report from the 1.1.131 latest.log audit. The previous diagnosis (1.1.131) added user-facing feedback but didn't fix the actual freeze. Re-reading the log past line 3110 revealed the real story:

User typed `zzz` at 17:46:29. SleepService thrashed for 57 seconds on a bed at -17, 63, 2 that vanilla was rejecting with `block.minecraft.bed.obstructed` (bed-area property — not enough headroom or other geometry vanilla refuses). The loop walked the bot through 3 stand candidates × 2 beds × ~10s/pathfind = 57s of server-thread saturation, ending with `"Can't keep up! Running 57366ms or 1147 ticks behind"`. Bob's queued attempt then took another 56s. User paused the game before either bot's WAITING_LOGOFF could fire — the feature LOOKED dead because the server was dead.

Three coupled fixes:

1. **Early-exit on bed-area failures** ([SleepService.java:175-210](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java#L175-L210)). When `trySleep` rejects with `block.minecraft.bed.obstructed` or `block.minecraft.bed.not_safe`, those are properties of the bed area, not the stand position — different stand candidates won't fix them. Return `FAIL_OTHER` immediately so caller moves on to the next bed (or to the placement branch). Also surface a clear per-bed chat line so the user knows WHICH bed and WHY: `"{bot} can't use the bed at {pos} — blocks too close."` / `"— monsters nearby."`

2. **Stand-candidate cap** ([SleepService.java:157-167](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java#L157-L167)). Cap `tryUseBed` to max 2 stand-candidate attempts per bed regardless of failure reason. Each attempt runs a full Baritone pathfind + walk loop costing ~10s in pathological cases — 2 is enough to cover "first candidate is unreachable but second one works" while bounding total cost. Log line updated to `tryUseBed bedFoot=... standCandidates=... (cap=...)`.

3. **WAITING_LOGOFF deadline anchored on now-tick** ([BotZzzSleepService.java:236-244](src/main/java/net/wcfcarolina13/GameAI/services/BotZzzSleepService.java#L236-L244)). Previously the 20s deadline was `startedTick + 400` where `startedTick` was when chat fired. If SleepService.sleep blocked the server for 57s before returning, the deadline was already 37s in the past by the time WAITING_LOGOFF was set, firing the logoff check immediately instead of giving the user the promised 20s window. Now anchors on `server.getTicks()` at the moment we transition to WAITING_LOGOFF, so the 20s timer always means 20s from "couldn't sleep" to "decision time."

Net effect: an obstructed bed now fails fast in ~1s with a clear chat line per bot per bed, the user gets a real 20s window to climb into bed, and the server doesn't lock up. Future failure modes (any other vanilla `trySleep` rejection reason) still get the existing stand-candidate retry but capped at 2 attempts.

Files: `GameAI/services/SleepService.java`, `GameAI/services/BotZzzSleepService.java`.

## zzz feedback fix: bot-named messages, time-of-day pre-check, debounce visibility (2026-05-17, 1.1.131)

User report: typed "zzz" with multiple bots around, no beds, no means to craft → "nothing happened, they appeared to just do nothing." Log analysis ([latest.log](logs) at 15:46:47–15:47:09) showed three issues:

1. **The world wasn't actually night.** User was underground (`openSky=false`, Y=63), so it FELT dark, but `world.isDay()` returned true. SleepService correctly refused with "I couldn't sleep right now (not night/thunder)" but the message wasn't attributed to a bot, and the user (in cave-dark) read it as bot confusion rather than vanilla time-of-day rules.
2. **Generic chat message** — both bots sent the identical "I couldn't sleep right now" line, no way to tell which bot from chat.
3. **Re-zzz silently debounced** — first zzz at 15:46:47 put both bots in WAITING_LOGOFF (deadline 15:47:07). User did `/time set 13000` at 15:46:53 to actually make it night, then re-zzz at 15:46:57 — but my service's `ACTIVE.containsKey(botId) continue;` debounce skipped it with zero feedback. At 15:47:09 deadline expired with "deadline reached but sender isn't sleeping — staying online" logged to server, but again nothing to the user.

Net effect: user typed zzz twice, got two confusing/missing messages, watched 20 seconds of nothing, and walked away thinking the feature was broken. It wasn't — but the UX was opaque.

**Fix** — three changes to [BotZzzSleepService.handleChatTrigger](src/main/java/net/wcfcarolina13/GameAI/services/BotZzzSleepService.java):

1. **Pre-check `world.isDay() && !world.isThundering()`** before calling SleepService. If sleep is fundamentally impossible right now, skip the 20s wait entirely and send `"{bot} can't sleep right now — it's not night yet."` per bot. No more pointless WAITING_LOGOFF when the only fix is for time to advance.
2. **Bot-named feedback for all chat lines** — every response now leads with `{botName}` so multi-bot scenarios are unambiguous (`"Jake can't sleep…"`, `"Bob is already trying to sleep…"`).
3. **Verbose debounce messages** — when re-zzz hits a bot already in ACTIVE state, send a state-aware message instead of silently dropping:
   - ATTEMPTING: `"{bot} is already trying to sleep — give them a moment."`
   - WAITING_LOGOFF: `"{bot} already tried — waiting to see if you sleep."`
   - Already sleeping: `"{bot} is already asleep."`
4. **Deadline-expired feedback to sender** — when the 20s WAITING_LOGOFF expires and the sender isn't in bed, send `"{bot} couldn't sleep, and you're not in bed either — staying online."` so the user knows the wait window is over, not silently log-only.

The underlying state machine is unchanged — same trigger pattern, same debounce, same logoff/respawn cycle. Only the user-facing visibility improves. Mounted-bot refusal from 1.1.129 inherits the same chat format.

Files: `GameAI/services/BotZzzSleepService.java` (handleChatTrigger rewritten, tickActiveStates deadline path adds sender chat).

## Pressure-plate stuck observability (2026-05-17, 1.1.130)

The "bot still gets stuck for too long at doorways and pressure plates" complaint from the Notes-list audit. Doorway side was already handled by [MovementService.java:2389-2410](src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java#L2389-L2410) (stuck-near-door auto-close after 8+ attempts within 4 blocks). Pressure-plate side had no instrumentation — plates were just marked walkable in [WalkablePartialBlocks.java:78](src/main/java/net/wcfcarolina13/GameAI/services/WalkablePartialBlocks.java#L78) with no stuck-detection or oscillation tracking.

Without a confirmed reproduction it would be reckless to ship a speculative fix that might regress the cases where the pathfinder already handles plates correctly (most of the time it does — plates are passable, you just walk over them). Two suspected failure modes:

1. **Plate-controlled door / gate / piston ahead** — bot pathfinds a route that crosses a plate but doesn't model the plate as "I need to STAND on this to keep the iron door open" or "stepping past this plate closes the gate behind me." Pathfinder treats the plate as a normal walkable cell.
2. **Oscillation** — bot steps on plate → door opens → bot walks past → door closes behind it → bot turns back for a drop / mob / commander → re-steps on plate → repeat. Classic A* + redstone-state-blind interaction.

Shipping pure observability so the next time the user hits it, the logs tell us which failure mode it is and we fix the right thing instead of guessing.

New [BotPressurePlateDiagnosticService](src/main/java/net/wcfcarolina13/GameAI/services/BotPressurePlateDiagnosticService.java) ticked from [Frens.java](src/main/java/net/wcfcarolina13/Frens.java) at 10-tick cadence. Two signals:

- **`[plate-stuck]`** — bot's block position hasn't changed for ≥60 ticks (3 s) AND an `AbstractPressurePlateBlock` is within 2 blocks of its feet. Logs once per bot per 30 s.
- **`[plate-osc]`** — bot transitioned on/off a pressure plate ≥3 times within a 100-tick (5 s) sliding window. Logs once per bot per 30 s.

Both signals include the bot name, plate position, feet position, and dimension so a single log line is enough to repro. Throttled to prevent flood. **No behavior change** — purely a diagnostic feed.

When a log entry appears in the field, the next pass becomes a targeted fix (likely either pathfinder-side plate-controlled-blocker awareness for case 1, or a short anti-oscillation cooldown that prevents re-stepping the same plate within N ticks for case 2). Same approach the [NavHazardCache visibility surfacing](src/main/java/net/wcfcarolina13/GameAI/services/navigation/NavHazardCache.java) used in 1.1.101 — instrument first, fix from data.

Files: `GameAI/services/BotPressurePlateDiagnosticService.java` (new, ~175 lines), `Frens.java` (1-line tick registration).

## zzz refuses mounted bots with a hint (2026-05-17, 1.1.129)

Follow-up to 1.1.128. User asked: how does zzz handle a bot mounted on horse/boat/minecart? Investigation:

- **Vanilla normal-player path** (verified via Yarn 1.21.11 mappings — `Entity.readRootVehicle` / `writeRootVehicle` exist on `net.minecraft.entity.Entity`, and the [Player.dat format wiki page](https://minecraft.wiki/w/Player.dat_format) documents the `RootVehicle` NBT compound): vanilla serializes the entire vehicle (with full Entity NBT, not just a reference) INSIDE the player's .dat file on disconnect, and recreates+remounts the vehicle on reconnect. Works for horses, boats, minecarts, nested passenger chains.
- **Fake-player divergence** (per the load-bearing comment at [BotPersistenceService.java:222-228](src/main/java/net/wcfcarolina13/GameAI/services/BotPersistenceService.java#L222-L228)): vanilla's `savePlayerData` does NOT write RootVehicle for fake players — verified empirically by scanning a saved bot .dat file. Frens compensates via [MountPersistenceService](src/main/java/net/wcfcarolina13/GameAI/services/MountPersistenceService.java) which dismounts on disconnect, calls `setPersistent()` on mob mounts so they don't despawn, records UUID-keyed state, and on rejoin force-loads chunks → finds entity by UUID → remounts. The non-mob branch at [L318](src/main/java/net/wcfcarolina13/GameAI/services/MountPersistenceService.java#L318) handles boats and minecarts too.

So the existing dismount-respawn-remount machinery handles all three vehicle types correctly. The only awkward part of the zzz flow with a mounted bot was the doomed 20-second `WAITING_LOGOFF` wait — vanilla refuses sleep while mounted, so the sleep attempt was guaranteed to fail. The bot would sit there for 20 s, then log off, then respawn, then auto-remount. Functional but jarring.

**Fix**: skip the whole attempt when the bot has a vehicle. Send the user a clear hint matching vanilla's semantics — "{bot} can't sleep while mounted — dismount them first." User dismounts via the `'` hotkey (or by getting the bot to stand on land), then re-types zzz. Single-line check in [BotZzzSleepService.handleChatTrigger](src/main/java/net/wcfcarolina13/GameAI/services/BotZzzSleepService.java) at the per-bot loop, BEFORE the ACTIVE map is touched so debounce semantics are unaffected.

Files: `GameAI/services/BotZzzSleepService.java` (6-line insert in `handleChatTrigger`).

## Chat "zzz" sleep trigger + failure-driven bot logoff/respawn (2026-05-17, 1.1.128)

User-requested QoL flow: when any player types "zzz" (3+ z's, case-insensitive, nothing else) in chat, every Frens bot in the sender's dimension within 48 blocks tries to sleep. If a bot can't sleep AND the sender is themselves sleeping 20 s later, the bot "logs off" — its fake-player entity is removed from the world (state persisted) and it waits until either daytime or no same-world non-bot player is sleeping, then respawns at the spot where it logged off.

New service [BotZzzSleepService](src/main/java/net/wcfcarolina13/GameAI/services/BotZzzSleepService.java) owns the whole state machine. Three coupled pieces:

**1. Chat hook with per-bot debounce.** Wired into the existing `ServerMessageEvents.CHAT_MESSAGE` block in [Frens.java:1188-1198](src/main/java/net/wcfcarolina13/Frens.java#L1188-L1198), positioned BEFORE the LLM/quest handlers so a zzz never gets eaten by another consumer. Regex `^z{3,}$` case-insensitive on trimmed text — strict so "zzz lol" or "zz" don't match. Per-bot ACTIVE map keys on UUID: any bot already in ATTEMPTING / WAITING_LOGOFF state, or currently `isSleeping()`, ignores further triggers. Multiple players spamming "zzzzz" or one player mashing the same message → exactly one sleep attempt per bot, no stacking. The 48-block radius scopes "your zzz" to the bots actually at your base, not every bot on the server.

**2. Failure → logoff state machine.** `tryAttemptSleep` runs the existing [SleepService.sleep](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java#L58) via `server.execute` to stay on the server thread. On success the entry clears. On failure the bot transitions to `WAITING_LOGOFF(deadlineTick = nowTick + 400)`. Two early-exits prevent dumb logoff: if the bot is in Nether/End (`SleepService.dimensionAllowsBeds` will never return true there) we clear immediately, and at the deadline if the sender isn't `isSleeping()` we cancel — no point logging off if the player isn't actually committed to the sleep cycle.

**3. Logoff/respawn cycle.** `beginLogoff` mirrors the proven disconnect path from [NavigationArtifactService.respawnBotAtDestination](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java#L1300): `BotPersistenceService.onBotDisconnect` → `removeFromPlayerManager` → `bot.discard()`. Bot pos/yaw/pitch/dimension/gamemode + name go into a `LoggedOff` record persisted to `<configDir>/frens/sleep_logoff_state.json` so the cycle survives server restart. Wake monitor ticks every 40 ticks (2 s — fast enough, not noisy): for each logged-off bot, checks the target world's time-of-day; if `tod ∈ [0, 12000)` (daytime) → respawn via [createFakePlayer.createFake](src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java#L63). Otherwise checks if any non-bot player in the SAME dimension is currently sleeping; if no human is sleeping AND at least one human is in that world, respawns. (If no human is in the dim at all, the bot waits for time to tick naturally to dawn.)

**Dimension philosophy** matches the rest of the mod (see [SleepService.java:456-461](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java#L456-L461), [BotAutoReturnSunsetService.java:260-265](src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java#L260-L265)): the wake check only counts players in the bot's own dimension. A commander in the Nether (where sleep is impossible) doesn't block an Overworld bot's wake conditions. Confirmed during the audit that this composes correctly with the existing dimension gates — bot in Overworld + commander in Nether → existing autonomous-sleep path lets the bot sleep on its own, no change needed.

**Edge cases handled:** bot is removed mid-cycle → entry purged on next tick. Bot succeeds in sleeping after a delayed bed opens up → entry purged via `bot.isSleeping()` check. Sender disconnects before deadline → at deadline the sleeping check finds `sender == null` → no logoff. Server shutdown with logged-off bots → state survives; on restart, `diskLoaded` guard triggers `loadFromDisk()` on first tick and the wake monitor picks up where it left off. Multiple bots near the same sender → each independent ACTIVE entry, each independent logoff/respawn decision.

Files: `GameAI/services/BotZzzSleepService.java` (new, ~390 lines), `Frens.java` (chat hook + tick registration, 6-line + 1-line inserts).

## Notes-list cleanup pass: panda/sniffer dialogue gaps, sleep-share gating, stand-down hotkey (2026-05-17, 1.1.127)

Three small follow-ups from the 2026-05-17 Notes-list audit, bundled because they don't share code paths but each is too small for its own release. None of them touch shared runtime state.

**1. Panda variant dialogue + sniffer alt line.** `tryPandaNearby` in [CompanionContextReactionService.java:2050-2110](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java#L2050-L2110) previously had a fallthrough comment `NORMAL / PLAYFUL / WEAK don't have lines`. Added lines for two of the three:

- `panda_playful` → "Look at it go!" (7% trigger weight)
- `panda_weak` → "Aw, that little one looks fragile." (10% trigger weight, slotted in priority above WORRIED since fragile cubs are more visually distinctive than worried adults)
- `sniffer_cutest_thing` → "That's the cutest thing I've ever seen." (added alongside the existing `sniffer_dinosaur` line so the pool picks one or the other)

NORMAL gene intentionally stays unremarked — too common to comment on every time. New SoundEvents registered in [BotDialogueSounds.java](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java) and stub `sounds.json` entries added with empty `sounds: []` arrays, matching the same pattern `panda_aggressive` already uses while awaiting voice recording. Until OGGs are recorded, lines fall through to chat + overhead bubble — same UX as the silent panda_aggressive line that's already shipping.

**2. Sleep-share gating in [SleepService.java](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java).** User complaint: "Bot doesn't put down beds that are in its inventory if the player sleeps and there is only one bed nearby." The prior logic ([L78-97](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java#L78-L97)) filtered occupied beds out of `findNearbyBedFeet`, then fell into the craft+place branch if none remained — so a base with one bed that the player was sleeping in would trigger the bot to set up a second redundant bed. Added a short-circuit: if any non-bot player within 32 blocks is currently sleeping AND it's a valid sleep time, suppress the placement entirely and report "Someone's already sleeping — I'll wait it out." The sleeping player's bed advances time for the bot anyway, so a second bed is wasted work. New helper `isAnyPlayerSleepingNearby` skips other Frens bots via `BotRegistry.isRegistered` so a follower bot's sleep doesn't also suppress.

**3. Stand-down hotkey.** [BotStandDownService](src/main/java/net/wcfcarolina13/GameAI/services/BotStandDownService.java) and the `/bot standdown` command have existed since 2026-04-10 but were only reachable via slot 1 of the hotkey overlay (`executeOverlayHotkeySelection`). Added a dedicated unbound keybind `KEY_STAND_DOWN_LOOK` in [FrensClient.java](src/main/java/net/wcfcarolina13/FrensClient.java) that calls `handleStandDownLook(client)` directly — same look-target logic, no overlay round-trip. Unbound by default (like rescue teleport) so users opt in via Controls without keybind collisions. Lang key added: `key.frens.stand_down_look = "Frens: Stand Down (look target — 60s pause)"`.

Files: `ChatUtils/BotDialogueSounds.java`, `GameAI/services/CompanionContextReactionService.java`, `GameAI/services/SleepService.java`, `FrensClient.java`, `resources/assets/frens/sounds.json`, `resources/assets/frens/lang/en_us.json`.

## Creeper fuse backoff: always-on defensive interrupt (2026-05-17, 1.1.126)

The bot had no defensive logic against ignited creepers outside of an inline block in [BotEventHandler.engageHostiles](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3877) which only fires once combat is already engaged. While mining, drop-sweeping, following, or idling, the bot would happily continue what it was doing while a creeper swelled next to it. The existing [BotCombatCalloutService](src/main/java/net/wcfcarolina13/GameAI/services/BotCombatCalloutService.java) only emitted audio reactions to creepers, not defensive movement. Audit notes from the 2026-05-17 Notes-list pass confirmed this as the highest-impact remaining safety gap.

New [BotCreeperDefenseService](src/main/java/net/wcfcarolina13/GameAI/services/BotCreeperDefenseService.java) is a per-bot defensive interrupt invoked early in [BotEventHandler.updateBehavior](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L1611) (which runs every tick per bot via `AutoFaceEntity`). When `tickBackoff` returns true, the caller short-circuits and the bot is driven directly away from the closest ignited creeper at sprint speed.

Detection: per-tick scan via `world.getEntitiesByClass(CreeperEntity.class, ±16, c -> c.isAlive() && c.isIgnited())`. The `isIgnited()` gate flips true for BOTH flint/steel ignition AND vanilla `CreeperIgniteGoal` proximity swell, so it covers every path. Distant creepers (≥4 blocks) require line-of-sight via `bot.canSee(creeper)` — walls block both vanilla ignition and explosion damage, so wall-blocked creepers don't need a panic response.

Pre-emption: on first engagement the service force-aborts any active task via `TaskService.forceAbort(uuid, "creeper-fuse")`, cancels and suppresses drop-sweep for 3 s via `DropSweepService.requestCancel` + `suppressFor`, lowers any raised shield (to allow movement), and dismounts if mounted. The abort-latch ownership is tracked per-bot and cleared via `TaskService.clearAbortLatch` when backoff resolves, per [[feedback-abort-latch-ownership]].

Backoff target distance: **9 blocks for a normal creeper, 17 for a charged (powered) one** — vanilla explosion damage radius is `2 × power − distance`, so normal blast damages out to ~7 blocks and charged out to ~14; the extra margin covers travel time during the ~1.5 s fuse window. Movement uses `FollowMovementService.moveToward(bot, fleeTarget, 1.0, sprint, null)` — the same primitive the inline engage-hostiles flee already uses.

Release conditions (any): creeper UUID no longer resolves, creeper dies/un-ignites, safe distance held for ≥10 ticks (hysteresis to prevent oscillation), or hard 60-tick timeout (vanilla fuse is ~30 ticks; doubled for safety). On release `BotActions.stop` is called and the abort latch is cleared if we owned it.

Edge cases handled: charged creepers (larger safe distance), wall LOS (skip distant blocked threats), submerged in water (skip sprint), mounted (dismount before backoff), cornered (stuck detection via `bestDistance` not growing ≥0.3 over 20 ticks → raise shield via `BotActions.raiseShieldFacing`). Skill resume is the user's job after backoff (force-abort is intentional — pause-and-resume across a 1.5 s creeper emergency is too brittle).

The existing inline creeper flee at [BotEventHandler.engageHostiles:3877-3948](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3877-L3948) stays in place — it still handles the not-yet-ignited but in-combat case (bot fighting a creeper that hasn't started swelling). The new service handles the ignited-or-about-to-blow case and runs strictly earlier in the per-tick dispatch.

Files: `GameAI/services/BotCreeperDefenseService.java` (new, 240 lines), `GameAI/BotEventHandler.java` (8-line insert).

## Multi-bot multi-mount hardening: type validation + cross-bot dedup + multi-leash auto-secure (2026-05-17, 1.1.125)

Three coupled gaps in the multi-bot/multi-mount data flow, all on the rejoin/server-restart path. Failure modes: bot remounts wrong species when UUID is reused, two bots claim the same horse during simultaneous rejoin fuzzy-match, second-leashed mount silently orphaned on overwrite.

**1. Type validation on direct UUID rejoin lookup.** [MountPersistenceService.restoreMount](src/main/java/net/wcfcarolina13/GameAI/services/MountPersistenceService.java#L277) was trusting `world.getEntity(state.mountUuid())` unconditionally. Vanilla can reuse UUIDs after entity removal + new spawn (rare but observed). New `entityTypeMatches` helper checks the resolved entity's type against the saved type id. On mismatch, treat as collision and fall through to fuzzy-match (which IS type-filtered) so the bot finds the right species instead of silently mounting a llama where a horse was expected.

**2. Cross-bot fuzzy-match dedup.** Two bots both losing their saved UUIDs during the same restore pass could fall through to `findNearbyMount` and both pick the same nearest matching entity. New per-tick `RESTORE_CLAIMED_THIS_TICK` UUID set claimed by every successful restore (direct UUID hit OR fuzzy match) and consulted in `findNearbyMount`'s predicate. Cleared in `onServerTick`'s finally block so a thrown exception doesn't leave stale claims. Tick-scoped because subsequent ticks read the persisted state, not in-memory claims.

**3. Multi-leash auto-secure on overwrite.** `RideSyncService.setLeashTarget` silently overwrote the previous mount UUID — vanilla allows a player to leash multiple mobs, so the previous mount was getting orphaned. New `maybeSecureOrphanedLeash` runs when an overwrite is about to happen: if the bot is online and the previous mount entity is loaded, try `secureMountForTravel` (tether to nearby fence). If that fails, at least call `setPersistent` so the mount doesn't despawn before the player can retrieve it. The new mount UUID then takes the slot cleanly.

Three other items checked and confirmed already-solid (not part of this commit but worth documenting): per-bot per-world `STATE` isolation (`Map<botAlias, Map<worldKey, MountState>>` — never collides across bots), `BotPersistenceService.saveBotsBeforeShutdown` (enumerates all fake-players and persists mount state pre-shutdown), and chunk force-load on rejoin (`restoreMount` force-loads a 3×3 chunk box around the saved mount position before lookup, so persistent mounts in unloaded chunks reload reliably).

Files: `GameAI/services/MountPersistenceService.java`, `GameAI/services/RideSyncService.java`.

## Mount tether-at-source before traveling alone; dismount invariant documented (2026-05-17, 1.1.124)

Follow-up to the 1.1.116-119 mount-safety work. User pointed out that "refuse the whole travel if the mount can't fit at destination" is too harsh — if there's a fence nearby (or a fence can be placed), we should be able to tether the mount in place and let the bot proceed alone, just like the cross-dim path already does. Also: as a defensive invariant, any code path that teleports the bot without the mount must dismount first so there's no dangling rider/vehicle state.

Three changes:

1. **New `tryTetherAtSourceForSameDim`** ([TravelMountHandler.java](src/main/java/net/wcfcarolina13/GameAI/services/TravelMountHandler.java)) — modeled exactly on `tryTetherForCrossDim`. Calls `bot.stopRiding()`, then `RideSyncService.secureMountForTravel` which leashes the mount, finds/places a fence within reach, and ties it off. On success: returns `TETHERED_AT_SOURCE` and the bot proceeds alone. On failure: returns `REFUSE_NO_ROOM_AT_DEST` with a clearer message ("no fence within reach, no lead, or no spot to place a fence — build a fence nearby, equip a lead, or pick a more open destination").
2. **`evaluateTravel` rewired** — the same-dim "no room at destination" branch now calls the new helper instead of immediately refusing. Both consumers (lodestone fast-travel via `NavigationArtifactService:1063` and chorus-recall via `SpellNavigationNetworkManager:185`) already handled the tethered decision correctly by falling through to bot-only travel, so they pick up the new behavior automatically.
3. **Enum rename**: `TETHERED_CROSS_DIM` → `TETHERED_AT_SOURCE` since the decision now covers both cross-dim and same-dim cases. `tetherDimName` field renamed to `tetherLocation`. Two consumer switch-cases updated.

Plus the **dismount invariant** is now documented at the top of `evaluateTravel`'s Javadoc with the load-bearing rationale and the list of decisions it applies to. Future contributors who add new decisions know they must dismount first if the path teleports without bringing the mount.

The previously-queued backlog item ([RALPH_TASK.md P2 Navigation & Movement](RALPH_TASK.md)) for "lodestone fast-travel mount-placement pre-check" is now marked `[x]` — this fix achieves the same goal via a richer mechanism.

Files: `GameAI/services/TravelMountHandler.java`, `GameAI/services/NavigationArtifactService.java`, `network/SpellNavigationNetworkManager.java`, `RALPH_TASK.md`.

## Snowball fight: /bot stop ends it + overwhelmed-yield on sustained pelting (2026-05-17, 1.1.123)

User report: "When bot starts snowball fight, it doesn't stop, even if you manually command to stop. I threw lots of snowballs at it and it never gave up, either."

Two coupled bugs in [BotSnowballFightService](src/main/java/net/wcfcarolina13/GameAI/services/BotSnowballFightService.java):

1. **No `/bot stop` hook.** The service never checked `BotEventHandler.isInStopCommandGrace`. Adding a gate at the top of `tickBot`: if the bot is non-IDLE and within the 60-tick stop-command grace window, fire a YIELD line and `endFight` immediately. /bot stop now ends the fight on the next tick.
2. **No overwhelmed-yield from sustained pelting.** The only natural yield trigger was the bot's own ammo running out. A player throwing a stack of snowballs at the bot had no effect because vanilla snowballs deal 0 damage to fake players. Added a sliding-window hit counter (`Deque<Long> snowballHitTicks` on the per-bot State) populated in the ACTIVE-phase damage hook. After 8 commander snowball hits within a 200-tick (10s) window, bot yields gracefully via the existing YIELDED phase + 5-minute fight cooldown. Hit log cleared on entry to ACTIVE and on `endFight` so a stale tally doesn't carry between fights.

File: `GameAI/services/BotSnowballFightService.java`.

## "It's cold down here" requires low sky-light, not just low Y (2026-05-17, 1.1.122)

User report: "Bot was saying 'it's cold down here' when we were above ground, probably a canopy cover thing."

Cause: `isDeepUnderground = y < 0` in [BotAmbientChatter](src/main/java/net/wcfcarolina13/ChatUtils/BotAmbientChatter.java). Modern Overworld terrain extends below Y=0 in deep mountain valleys — the player can walk above-ground at Y < 0 with the sky directly overhead. The Y check satisfied, deepslate chatter pool fires.

Fix: tighten `isDeepUnderground = y < 0 && skyLight <= 3` in both scopes (line 665 in event-driven pickup, line 1471 in area-pickup). Real deepslate caves have skyLight=0; valleys have skyLight 12-15 even with leaf canopy overhead — gate now correctly rejects them.

File: `ChatUtils/BotAmbientChatter.java`.

## "Walked past this tree" gated on actual nearby tree (2026-05-17, 1.1.121)

User report: "Bot says it walked past the same tree but there are no trees around."

Cause: [CompanionContextReactionService.tryOutdoorAmbient](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java#L980) gated the `OUTDOOR_AMBIENT_LINES` pool only on `isSkyVisible` — no actual tree check. Pool included `ambient_same_tree` ("I swear I've walked past this exact tree three times now"), which fired in any outdoor biome regardless of fauna.

Fix: split the pool into two — `OUTDOOR_AMBIENT_LINES` (both lines) and `OUTDOOR_AMBIENT_SKY_ONLY_LINES` (just "saw a bird"). New `hasNearbyLog` helper does an early-exit scan within 12 blocks for any block in `BlockTags.LOGS`. Picks the full pool when a log is nearby, the sky-only subset otherwise. "Saw a bird" still fires in any outdoor biome since birds (parrots, chickens) appear in plains/savannah/swamp too.

File: `GameAI/services/CompanionContextReactionService.java`.

## Dance stops when the song ends, not when the disc is removed (2026-05-17, 1.1.120)

User report: "I played a jukebox disc and the bot danced, but the bot never stopped dancing, long after the track was over. It didn't stop until I took the disc out of the jukebox."

Cause: [BotRandomDanceService.isJukeboxPlayingNear](src/main/java/net/wcfcarolina13/GameAI/services/BotRandomDanceService.java#L216) was using the block-state property `Properties.HAS_RECORD` as the play-state authority. That property is `true` whenever a disc *sits in* the jukebox, regardless of whether music is currently playing. The author flagged this in a comment ("keeps the bot dancing slightly past song-end") but it was actually the dominant failure mode — once a track ends in vanilla, the disc stays in the jukebox until manually removed, so the bot kept dancing for the full duration of the user's session until they popped the disc.

Fix: use `JukeboxBlockEntity.getManager().isPlaying()` — vanilla 1.21's actual play-state flag (verified in `net/minecraft/block/jukebox/JukeboxManager` via Yarn mappings). Stops the moment the song ends.

Bonus: bed-placement task ([RALPH_TASK.md P1](RALPH_TASK.md)) marked user-verified in-game 2026-05-17 — the bot now places and uses its own bed from inventory when no nearby bed is available, as shipped in 1.1.100.

File: `GameAI/services/BotRandomDanceService.java`.

## Bot teleport defers when mount can't be safely placed (2026-05-17, 1.1.119)

Follow-up to 1.1.117. Previously, when `coTeleportSavedMount` couldn't find a safe placement for the bot's mount at the destination, it skipped the animal teleport but still let the bot teleport — orphaning the horse at the source. User's correction: "skipping animal teleport should skip bot teleport too, until you're in a safe place for them to teleport."

Changed `coTeleportSavedMount` from `void` to `boolean` — returns `true` when the bot's teleport may proceed (no mount, stowaway-gated, or mount placed successfully), `false` when mount placement failed. Callers gate their own `bot.teleport(...)` on the return value.

Per-callsite behavior:

- **Cross-dim follow handoff** ([BotEventHandler:2509](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L2509)): aborts on false, returns from the handoff method. Next follow tick re-attempts.
- **Wolf-teleport catch-up** ([BotEventHandler:7145](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L7145)): aborts on false, returns from the wolf-tp method. Self-correcting — next tick may pick a clearer follow spot.
- **`/bot come`** ([modCommandRegistry:5128](src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java#L5128)): aborts and tells the player: "Can't safely bring [bot]'s mount here. Try a more open spot or dismount first."
- **Emergency-rescue spell** ([BotEmergencyRescueService:387](src/main/java/net/wcfcarolina13/GameAI/services/BotEmergencyRescueService.java#L387)): aborts and HUD-notifies. Also reordered: mount placement is now checked BEFORE reagent consumption, so a failed rescue doesn't burn Ender Pearls + Chorus Fruits. Message is honest: "Reagents not consumed — try a more open anchor."
- **Lodestone fast-travel** ([NavigationArtifactService:1390](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java#L1390)): bot has already teleported by the time the mount call runs (different ordering), so can't abort retroactively. Logs the partial-arrival; player can `/bot come` later or walk the bot back. Restructuring to pre-check is bigger scope — deferred.

Files: `GameAI/services/TravelMountHandler.java`, `GameAI/BotEventHandler.java`, `Commands/modCommandRegistry.java`, `GameAI/services/BotEmergencyRescueService.java`, `GameAI/services/NavigationArtifactService.java`.

## Fence-tie reliability: reach-bounded fence search + verify + retry (2026-05-17, 1.1.118)

User report: "Bot has trouble tying animals to fences, seems to just drop the lead when it tries." Cause: [secureMountIfPossible](src/main/java/net/wcfcarolina13/GameAI/services/RideSyncService.java#L3448) was picking fences up to 10 blocks from the vehicle, but vanilla `interactBlock` reach is ~4.5 blocks. The bot would call `interactFence` on an out-of-reach fence, vanilla's `LeadItem.attachHeldMobsToBlock` would silently no-op (no error, no knot, lead stays in hand), and the function returned `TETHERED_TO_FENCE` — caller thought success while the mob was still on the bot's lead. Subsequent state changes then "dropped" the lead (or it auto-broke at 10-block range).

Three fixes:

1. **Reach-bounded fence search.** New `findReachableFence` requires the fence to be within 4 blocks of the bot (interaction reach) AND within 7 blocks of the mob (the radius `attachHeldMobsToBlock` actually scans). Both constraints are required for the tether to succeed — picking a fence that satisfies only one was the silent-fail trap.
2. **Verify post-interact.** New `tieToFenceWithVerify` runs `interactFence` then checks that the mob is no longer leashed to the bot. The leash should have transferred to a LeashKnotEntity at the fence; if it didn't, the attempt failed.
3. **Retry once, then honest reporting.** On verification failure, re-select the lead and try one more time. If still failing, downgrade to `HELD_BY_BOT` (not `TETHERED_TO_FENCE`) and announce honestly to the player: "I tried to tie it but the lead won't take. Holding it instead." Player now knows the animal isn't secured.

File: `GameAI/services/RideSyncService.java`.

## Horse safety: real-bbox placement + proactive in-wall rescue (2026-05-17, 1.1.117)

Two fixes for "horse suffocates in tree after teleport."

**1. Stricter placement.** `findSafeAnimalSpot` now uses the animal's real bounding box via `ServerWorld.isSpaceEmpty(animal, box)` instead of the prior per-cell collision-shape iteration. The per-cell test correctly rejected leaves at the candidate's column, but missed bbox-width collisions — a horse is 1.4 blocks wide and a column-clear placement could still clip the corner of an adjacent tree trunk. Same-Y candidates are now preferred over vertical drift so the animal stays close to the intended landing height when a flush spot exists. Also removed the silent fallback to `destination` in both `teleportAnimalWithBot` and `coTeleportSavedMount` — if no safe spot is found in the ±3/±2 search, log a warning and skip the animal teleport. Leaving the animal at source is better than placing it in a wall.

**2. Proactive in-wall rescue.** `BotRescueService.rescueFromBurial` previously skipped mounted bots unless the bot itself was actively taking suffocation damage (`[FollowAssert] mounted-rescue-skip reason=not-suffocating`). The horse, with a different bbox, could be in a wall long before the bot's IN_WALL counter fired. Now the rescue also fires when `vehicle.isInsideWall()` — same check vanilla uses for the IN_WALL damage source. Bot dismounts and the standard rescue ladder takes over, freeing the horse before the first damage tick.

Files: `GameAI/services/TravelMountHandler.java`, `GameAI/services/BotRescueService.java`.

## Stowaway-mount gate: skip co-teleport when bot has dismounted (2026-05-17, 1.1.116)

Animals (horses, donkeys) were being silently dragged along on every bot teleport even after the bot had dismounted and stopped tracking them, often suffocating where the bot landed. Logs showed `resolvePreferredMount reject: bot=Jake savedMount=53095a7e-... reason=prepare-vehicle-failed` followed by the saved mount continuing to be co-teleported.

Cause: [TravelMountHandler.coTeleportSavedMount](src/main/java/net/wcfcarolina13/GameAI/services/TravelMountHandler.java#L376) gated only on `state == null` and `mount.isRemoved()`. The MountPersistenceService state intentionally outlives a dismount so the on-rejoin remount can put the horse back under the bot — but every teleport callsite (follow catch-up, /bot come, fast-travel, emergency rescue, NavArtifact lodestone) was treating that persisted state as "drag this animal along," which is wrong.

Fix: short-circuit if neither `state.wasMounted()` nor `state.heldByBot()` — i.e. the bot isn't actively riding or leading the animal. Single check, no other behavior changes. All five callsites benefit automatically. Rejoin remount is unaffected (different code path on MountPersistenceService).

File: `GameAI/services/TravelMountHandler.java`.

## Powder snow rescue: walk out laterally when an open neighbor exists (2026-05-16, 1.1.115)

Missed scenario in the 1.1.113 ladder: if the bot is in a shallow powder snow pit (or near the edge of any column) with a normal walkable cell at the same Y next door, "just walk out" is the cheapest and least disruptive recovery. No leather needed, no buckets consumed, no blocks destroyed, no teleport.

New `findLateralWalkExit` step inserted between leather-equip and jump. Checks the four horizontal neighbours: each must have a passable feet cell (air or thin walkable partial), a passable head cell, real footing below, and neither cell can be deadly (don't escape powder snow into lava — same `BotHazardService.isDeadlyBlock` gate the pathfinders use, plus an explicit powder-snow check so we don't drift into more snow). If any direction qualifies, apply a 0.35-block/tick lateral velocity with a small upward kick. Powder snow's ~15% movement multiplier eats most of the impulse, but the integrated drift across a few ticks carries the bot out.

`setJumping(true)` still runs in parallel — both attempts proceed simultaneously, and vanilla physics resolves whichever exit is faster. Bot in a 1-deep depression: drifts sideways onto the open ground. Bot in a deep column with no lateral exit: lateral helper returns null, falls through to climb-up via jump. Bot with leather boots: collision-shape change pops it onto the top of the column on the next tick anyway, so lateral velocity then just nudges it off the column edge if commanded somewhere.

File: `GameAI/services/BotPowderSnowRescueService.java`.

## Powder snow emergency teleport gated on teleportDuringSkills (2026-05-16, 1.1.114)

Follow-up to 1.1.113. Per the mod's existing convention: autonomous bot teleports must be opt-in via `SkillPreferences.teleportDuringSkills` (per-bot setting with a global override, default OFF). The new powder-snow last-resort teleport was firing unconditionally — adding the same gate as every other autonomous teleport path.

If the toggle is OFF (default), the rescue ladder still runs all the cheaper steps: equip leather armor, hold jump, empty-bucket scoop, water-bucket placement, mine-with-shovel-or-bare-hands. The emergency teleport simply doesn't fire. The bot only dies in powder snow if **all four** of: no leather armor in inventory, no empty bucket, no water bucket, can't mine out vertically (sealed by a roof or deep column with no breakable side), AND the teleport toggle is off. Rare combination, and the user can either enable the toggle or rescue manually.

File: `GameAI/services/BotPowderSnowRescueService.java`.

## Powder snow rescue ladder: equip leather → jump → bucket/water/dig → teleport (2026-05-16, 1.1.113)

Bot was getting trapped in powder snow and nearly freezing to death. Root cause: `BotRescueService.rescueFromBurial` doesn't fire on powder snow (no suffocation, empty collision shape), and `BotHazardService.tryEscapeHazardBlockAtFeet` does fire but its velocity-kick (0.45 horizontal + 0.15 upward) gets eaten by powder snow's ~15% movement multiplier. Bot barely moves while freezing.

Wiki research (1.21 mechanics) drove the design:

- **No suffocation, but freezing damage** starts at 140 ticks (7s) — 1 HP every 2s thereafter. Ample warning window.
- **Any leather armor piece** stops freezing AND reverses accumulated freezing effect, even if equipped while already inside.
- **Leather boots specifically** turn powder snow into scaffolding — jumping climbs the column fast.
- **Water or lava destroys powder snow.** Burning entities also break it on contact.
- **No tool speeds up mining.** Bare hands = shovel = 0.4s. (User still prefers shovel-when-available for player intuition; honoured.)
- Pathfinder audit: both `BaritoneStylePathFinder` and `PathFinder` already gate via `BotHazardService.isDeadlyBlock`, which includes powder snow. No fix needed.

New `BotPowderSnowRescueService` runs per-tick when a bot's feet block is powder snow:

1. **Equip leather armor** (any piece in inventory, boots first since they unlock scaffolding climb).
2. **Set `setJumping(true)`** every tick — fast climb with boots, slow swim-up without.
3. **After 60 ticks sustained**, escalate to active block removal (priority order):
   - Empty bucket → `useOnBlock` to scoop the feet block.
   - Water bucket (Overworld/End only — water evaporates in the Nether) → `useOnBlock` to destroy.
   - Select shovel (if present) or bare hands → `MiningTool.mineBlock` at feet.
4. **After 200 ticks AND `getFrozenTicks() >= 140`** (visible freezing damage), `SafePositionService.findAlternativeSafeNear` + `snapTo` for emergency teleport. Cooldown-gated.

`BotHazardService.tryEscapeHazardBlockAtFeet` now skips powder snow so the dedicated service has exclusive ownership — same pattern as the existing campfire skip.

Files: `GameAI/services/BotPowderSnowRescueService.java` (new), `GameAI/services/BotHazardService.java`, `Frens.java`.

## Magma block added to mining-contraindicated hazards (2026-05-16, 1.1.112)

Extension of 1.1.111's `isMiningContraindicatedHazard` classifier. Per user rule: magma deposits in the Nether stack vertically (multi-block layers), and even on a single magma layer the revealed surface below the mined block is typically more magma or lava. Mining as rescue drops the bot onto another burning floor, not safe ground. Same reasoning as lava — better to displace than dig.

Solid hazards that *do* still get mined out for rescue (mining them reveals safe ground): cactus, pointed dripstone, sweet berry bush, wither rose, cobweb.

File: `GameAI/services/BotHazardService.java`.

## Hazard classifier tag/class-based; burial rescue refuses to mine through lava or fire (2026-05-16, 1.1.111)

Continuation of the 1.1.110 mod-block-compat work, broadening the same pattern to `BotHazardService`. Two changes:

1. **`isDeadlyBlock` is now tag- and class-based for the fluid/fire categories.** Lava check switched from `state.isOf(Blocks.LAVA)` to `state.getFluidState().isIn(FluidTags.LAVA)` — auto-includes any mod fluid that opts into the vanilla lava tag (most do). Fire check switched from `state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)` to `state.getBlock() instanceof FireBlock` — auto-covers vanilla `SoulFireBlock` (which extends `FireBlock`) and any mod fire-block that extends the vanilla class. Magma / cactus / dripstone / sweet berry / wither rose / powder snow / cobweb stay explicit because vanilla offers no clean tag or class abstraction for them — mod variants will need to be added if they show up, per [[feedback-mod-block-compat-pattern]].

2. **`BotRescueService.rescueFromBurial` no longer mines through lava or fire.** Per user rule: "Fire and lava are to be avoided; mining out of them would just dig the bot deeper into danger." Lava: a mined source block refills from neighboring lava flow — bot stays in lava. Fire: the burning ground beneath (netherrack, any infiniburn block) re-ignites the bot immediately. The fix adds a new `BotHazardService.isMiningContraindicatedHazard(state)` classifier (subset of `isDeadlyBlock` — only lava + fire-class) and short-circuits all three rescue mining branches (headspace mining, horizontal escape direction, feet mining) to `attemptEscapeMovement` when the target is mining-contraindicated. Other deadly blocks (magma, cactus, dripstone, etc.) still get mined out — those are solid/minable and mining them reveals safe ground.

Per-tick displacement-based escape via `BotHazardService.tryEscapeHazardBlockAtFeet` continues to run independently, giving the bot multiple displacement attempts per second when stuck in lava/fire.

Files: `GameAI/services/BotHazardService.java`, `GameAI/services/BotRescueService.java`.

## Burial rescue trusts vanilla physics; sinkable surface detector handles mod blocks (2026-05-16, 1.1.110)

Wet Sand mod's "Soaked Sand" was tripping two false-positive paths: `BotRescueService.rescueFromBurial` flagged the bot as buried (and started mining its own feet with "I'm stuck in Soaked sand at feet! Mining with Iron Shovel..." chat spam), and `BotActions.applyMovementInput` rejected horizontal movement with `reason=feet-not-passable`. Both used hardcoded vanilla allowlists (Soul Sand / Mud / Muddy Mangrove Roots / Farmland / Dirt Path / Honey Block) — no mod variant was ever going to slot in cleanly.

Per user direction: adapt to *any* mod block that behaves similarly, not just Soaked Sand. Two layers, no per-mod allowlist entries:

1. **Vanilla-physics short-circuit in `BotRescueService.rescueFromBurial`.** If `!takingSuffocationDamage && bot.isOnGround() && !bot.isInsideWall()`, return early. `isOnGround` means vanilla seated the bot on a valid surface; `isInsideWall` is the same check vanilla uses to apply suffocation damage; no recent damage = no actual stuck. Any mod block that lets the bot stand normally now passes without needing a hardcoded entry. Same pattern as 1.1.106's post-arrival stability check.

2. **`WalkablePartialBlocks.isSinkableSurface` classifier**, called from `isStandable`. Recognizes Soul Sand-equivalents by three layers:
   - Explicit vanilla allowlist (Soul Sand, Soul Soil, Mud, Muddy Mangrove Roots, Honey Block, Farmland, Dirt Path).
   - Defensive fence/wall/gate exclusion via tags so real burials in those cells still trigger rescue.
   - **Collision-shape heuristic:** non-empty shape with `maxY ∈ (0.5, 1.0)`. Catches mod blocks that don't tag themselves at all. The bot logged at Y=62.92 on a Soaked Sand block at Y=62, which means Soaked Sand has partial-height collision like Soul Sand (0.875) and falls inside this window.

The shape heuristic deliberately excludes full-cell sand (vanilla SAND/RED_SAND maxY=1.0) — those sit BELOW the bot's feet blockpos and don't trigger the rescue path anyway. Slabs and thinner partials are handled by existing class checks / `isPathable`'s 0.125 fallback.

Files: `GameAI/services/WalkablePartialBlocks.java`, `GameAI/services/BotRescueService.java`.

## Species-specific "nice X" lines gated on proximity + LoS (2026-05-16, 1.1.109)

The bot was shouting "nice camel" / "nice bird" / "nice horse" / "good dog!" / "I love dogs" / skinwalker callouts at random during surface daytime regardless of whether any such animal was nearby or visible. Root cause: the six species-specific lines authored in the April 2026 handoff were wired into `BotAmbientChatter.WILDLIFE_CHATTER`, a blind random pool that fires on a 20% roll whenever the bot is on the surface during the day — no entity-presence check, no LoS check.

Fix:

1. Removed the six species-specific entries from `WILDLIFE_CHATTER` (kept the generic `LINE_ANIMAL_NEARBY_LOVE_ANIMALS` mood line — that one is a "fauna in general" statement and is fine as ambient).
2. Added four new species-gated trigger paths in `PetProximityReactionService` reusing the existing weighted-pool / cooldown / `playLine` infrastructure: `PARROT_NICE_LINES`, `CAMEL_NICE_LINES`, `HORSE_NICE_LINES` (each one-line), and `WOLF_OBSERVATION_LINES` (three lines). Each scans within `PET_RADIUS = 10` and applies `EntityVisibilityUtil.canSee` so the line only fires when the bot has an unobstructed eye-to-eye line of sight to the target. 5-minute per-pool cooldown so it stays a remark, not background chatter.
3. `WOLF_OBSERVATION_LINES` is independent from the existing `WOLF_NEARBY_LINES` (which is tamed-only "guard dog on duty" / "who's a menace"). The observation pool fires on any visible wolf, wild or tamed, with its own cooldown so both can fire on the same scan tick.
4. Debug triggers added: `/bot debug trigger parrot_nice|camel_nice|horse_nice|wolf_observation <bot>` plays each line on demand, bypassing cooldown.

Horse-like scan uses `AbstractHorseEntity` with no `isTame()` filter — the line is "nice horse", not "well-trained horse", so wild horses/donkeys/mules/llamas qualify too.

Files: `ChatUtils/BotAmbientChatter.java`, `GameAI/services/PetProximityReactionService.java`.

## TreeStuck escape clears stale abort latch (2026-05-10, 1.1.108)

Follow-up audit of the same 1.1.107 log surfaced the *actual* reason the bot didn't catch up after the misfired teleport detector. Sequence:

1. Bot wolf-teleports 32 blocks at 13:55:46.
2. Pre-1.1.107 detector misfires at 13:55:47 — `TaskService.forceAbort` sets `ABORT_LATCH` even though no skill ticket existed (the latch is intentionally set so survival actions like break-free see it).
3. Bot continues following commander on horse until 13:56:12, when it gets stuck on oak_leaves at (569,158,2254) — a real navigation stuck, unrelated to the earlier misfire.
4. `TreeStuckEscapeService` activates, tries safe-drop (unreachable), then falls through to leaf-mining.
5. **Every leaf-mine attempt aborts** because `MiningTool` checks `SkillManager.shouldAbortSkill` → `TaskService.isAbortRequested` → the stale `ABORT_LATCH` from step 2. The bot is now trapped in a 6-minute "try-mine, abort, retry" loop while the commander rides 468 blocks away.

1.1.107 fixed step 2 (wolf-tp self-notify), so the latch shouldn't get set in the normal case. But TreeStuck is autonomous bot self-recovery — it should not be vulnerable to a stale latch from *any* source (a real external teleport, a prior `/bot stop`, a fast-travel arrival edge case). Per `feedback_abort_latch_ownership.md`, non-skill operations that bypass `beginSkill` must call `TaskService.clearAbortLatch(UUID)` to avoid inheriting stale state.

Fix: `TreeStuckEscapeService#startLeafMine` calls `TaskService.clearAbortLatch(botId)` before `MiningTool.mineBlock`. The escape recovery now runs unconditionally regardless of latch state — appropriate, because if the bot is physically stuck in foliage, self-recovery is what we want regardless of what triggered an abort earlier.

File: `GameAI/services/TreeStuckEscapeService.java`.

## Follow + sleep recovery: wolf-tp self-notify, co-sleep cooldown clears on failure, wider bed placement (2026-05-10, 1.1.107)

User session log surfaced three coupled regressions during a long-distance follow + sleep flow:

1. **Follow chain breaking on wolf-teleport.** When the bot caught up via wolf-teleport (~32-block jump), the external-teleport detector in `BotEventHandler#handleTick` saw the same-tick position delta exceed its 16-block threshold and called `TaskService.forceAbort` + cleared `state.followFixedGoal`. The bot then stopped chasing the commander. Fix: after `bot.teleport(...)` in `tryWolfTeleport`, prime the detector via the existing `notifyTravelArrival(uuid, pos, tick)` helper (the same one fast-travel uses). The detector treats the next-tick large delta as expected.

2. **Co-sleep cooldown trapping the bot after a failed attempt.** `BotWakeUpDialogueService#triggerCoSleep` stamped `CO_SLEEP_QUEUED_TICK` *before* dispatching the sleep skill and never cleared it on failure. After one failed `SleepService.sleep` (e.g. no safe placement spot), the bot was locked out for 5 minutes — even though the commander kept laying beds nearby. Fix: in `scheduleCoSleep`'s `finally` block, when `success == false`, remove the cooldown stamp so the commander's next sleep edge re-queues the bot.

3. **`SleepService.placeBedNearby` rejecting valid spots.** Two issues stacked: (a) the placement sweep only iterated 4 cardinal directions × 4 distances from the bot's exact tile, so a bed nestled near the commander's bed left every cardinal lane blocked; (b) `isPlaceableBedFoot` required `isSolidBlock` under the bed (rejecting top slabs, stairs, dirt paths, farmland) and required `isAir` at the bed cells (rejecting tall grass, snow layers, vines — all replaceable). Fix: sweep the full -radius..+radius grid around the bot and try every facing per cell; replace `isSolidBlock` with a non-empty top-collision check and replace `isAir` with `isReplaceable`. Per `feedback_isSolidBlock_footing_trap.md` — `isSolidBlock` keeps trapping us into rejecting legitimate floors.

Files: `GameAI/BotEventHandler.java`, `GameAI/services/BotWakeUpDialogueService.java`, `GameAI/services/SleepService.java`.

## Deferred work cleared: fast-travel mount, cross-dim mount, post-arrival stability (2026-05-10, 1.1.106)

User asked: "work on the deferred stuff but work with vanilla, not against it." Three deferred items resolved using vanilla's own APIs (`Entity.teleport`, `Entity.isInsideWall`, `MountPersistenceService.findRecordedMount` which already loads chunks via vanilla's `ChunkManager`).

### 1. Fast-travel co-teleport (despawn/respawn cycle)

[NavigationArtifactService](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java) discards the bot entity at the source and creates a fresh one at the destination — passengers and vehicles can't follow that. The saved mount state survives the discard (it's keyed by alias in `MountPersistenceService.STATE`, not by entity reference), so the recovery path is straightforward: after the new bot is in place, look up the recorded mount and teleport it.

The `coTeleportSavedMount` helper from 1.1.105 was refactored to resolve the mount's source world from the saved `state.worldId()` (instead of `bot.getEntityWorld()`). That single change makes the helper work uniformly across same-world teleport, cross-dim teleport, AND fast-travel respawn — the only contract is "saved state survived." Wired into [completePostSpawnSetup](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java) right after the destination teleport.

### 2. Cross-dimension follow handoff

[BotEventHandler:2504](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L2504) — bot follows commander into the Nether or End. Vanilla cross-dim teleport for an entity preserves passengers IF the vehicle is the one teleported, but the existing code teleports the bot directly, which strips the vehicle relationship. With the refactored `coTeleportSavedMount`, calling it before `bot.teleport(...)` brings the mount across the dim boundary too. No changes to vanilla's cross-dim physics or chunk-loading needed — the helper uses `Entity.teleport(destWorld, ...)` which is the standard vanilla cross-dim method.

### 3. Post-arrival stability check (vanilla `Entity.isInsideWall`)

The route-end-state validator from the 1.1.104 deferral. Per-step route validation already exists (`isValidLocalEscapeMove` checks `isSolidStandable`), so the gap isn't pre-route — it's that vanilla physics can drift the bot off-route mid-step. Pre-route validation can't catch that.

Vanilla-friendly fix: at every teleport / snap call site, after positioning, check `player.isInsideWall()` (the same method vanilla uses to gate suffocation damage). If true, immediately call `BotRescueService.rescueFromBurial` — the rescue is already correct after the 1.1.103/1.1.104 fixes, the issue was just that it ran on the next tick, leaving a 50ms window of damage. Now it runs synchronously.

Wired into:

- [MovementService.moveTo teleport-fallback](src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java) — when walk fails and we teleport to destination.
- [MovementService.snapTo](src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java) — internal snap repositioning.

Both use `findNearbyStandable` to pick the destination but fall back to the raw destination when no standable spot is found within the search radius — that's the case the post-arrival check catches.

### Why "work with vanilla" matters

All three fixes use vanilla APIs as the source of truth: `Entity.teleport` for cross-world transit (handles chunk loading, dimension change, and entity sync correctly), `Entity.isInsideWall` for stuck detection (matches the same logic vanilla uses to apply suffocation damage), and `MountPersistenceService.findRecordedMount` which uses vanilla's `ChunkManager.getChunk(...)` to load the mount's chunk before the entity lookup. No reimplementation of collision, physics, or chunk-loading logic.

### Verification

Build: `./gradlew build -x test` clean.

Manual:

- Mount bot on horse, fast-travel via lodestone compass to a distant base. Bot should arrive WITH the horse, not without.
- Mount bot on horse, follow commander into the Nether. Bot should arrive in the Nether with the horse alongside.
- Stand bot in a tight corner, force the movement teleport-fallback. If the destination ends up being a wall, the rescue should fire on the same tick — no audible suffocation damage.

### Out of scope (remains deferred)

- **Pre-route bbox check**: in `isValidLocalEscapeCandidate`, additionally check `World.isSpaceEmpty(bot.getBoundingBox().offset(...))` for the candidate cell. Marginal value over per-step `isSolidStandable` and would require threading the bot through the route-search call chain. Not worth the surface change unless we see a specific case where standable-ground-but-bbox-clipping cells slip through.
- **Velocity-drift mid-step**: vanilla physics can carry the bot horizontally past a validated cell into an unvalidated one due to leftover velocity. The post-arrival check at teleport/snap sites doesn't address this because no teleport happens. The 1.1.104 nudge fix mitigates the consequence; preventing the drift itself would require physics-level intervention which goes against "work with vanilla."

## Co-teleport mount on far-distance bot teleports — keep horse paired (2026-05-10, 1.1.105)

User report: "the bot's horse disappeared twice. The first time it seems to have appeared out of nowhere, and when we got off our horses again, it disappeared for good." User asked the horses-disappearing problem be fixed properly, not just auto-cleared.

### Root mechanism

The 1.1.103 fix (auto-clear stale state after 5 stale rejections) lets the bot re-pair with a fresh mount, but doesn't prevent the disappearance in the first place. The agent investigation in 1.1.103 showed the saved mount position drifts >400 blocks from the bot. That happens because:

1. Bot is mounted on horse at position A.
2. Bot teleports to position B (fast travel, follow-teleport catch-up, /bot come, rescue, etc.).
3. Vanilla teleport dismounts the bot but doesn't carry the vehicle.
4. Saved state still references position A (recorded on the dismount).
5. Bot is at B; horse is at A (often in unloaded chunk after the bot leaves).
6. From the user's perspective: "the horse disappeared."

### Fix

New `TravelMountHandler.coTeleportSavedMount(bot, destWorld, destination)` helper — looks up the bot's recorded mount, finds a safe spot for it near the destination via the existing `findSafeAnimalSpot`, dismounts the bot cleanly, teleports the mount, refreshes the saved state. No-op when the bot has no recorded mount or the mount entity isn't currently loaded in the source world.

Wired into three high-impact teleport sites (called BEFORE `bot.teleport` so the source-world lookup still works):

- [BotEventHandler.followTeleport](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) — the catch-up teleport during follow when commander gets too far. Highest frequency of any teleport.
- [modCommandRegistry.executeCome](src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java) — `/bot come` summons.
- [BotEmergencyRescueService](src/main/java/net/wcfcarolina13/GameAI/services/BotEmergencyRescueService.java) — emergency rescue teleport.

### Verification

Build: `./gradlew build -x test` clean.

Manual:

- Mount bot on horse, ride out 100+ blocks from base. Press follow-teleport scenario (let bot fall behind so the catch-up teleport fires). Bot should arrive WITH the horse beside it, not stranded at the start.
- Same setup with `/bot come` — bot teleports and horse follows.
- Same with the rescue teleport keybind — horse co-teleports.
- Dismount bot near a horse, walk away ~50 blocks. Return. Horse should still be there (unaffected by this fix; existing `setPersistent()` flag in `recordMount` already covers despawn).

### Out of scope (next)

- **Fast travel co-teleport**: NavigationArtifactService uses a despawn-respawn cycle (the bot entity is fully discarded and recreated at destination). Adding mount-co-teleport requires capturing the saved-state pre-discard and restoring in `completePostSpawnSetup`, with cross-dimension considerations. Worth doing — the user uses fast travel — but the despawn/respawn flow is moderate surface; defer to a focused next session.
- **Route-end-state validator + post-arrival stability check**: still open from 1.1.104 deferral. Independent work; horse fixes don't help routing. Defer until test data on 1.1.104 nudge fix shows whether the suffocation symptom recurs.
- **Dimension handoff teleport** (BotEventHandler:2504): bot follows commander to nether/end. Should also co-teleport mount, but cross-dimension entity teleport is finicky (vanilla strips passengers/leashes on dim transit). Worth a careful pass but not in this commit.

## Rescue-nudge destination validation — actual root cause of suffocation drift (2026-05-10, 1.1.104)

User asked whether the deferred items from 1.1.103 were worth doing. Investigation surfaced a different actual root cause for the suffocation incident: the rescue itself was contributing to the encasement.

### Root cause

The 22:42:23 incident logs show alternating "nudged away from wall west" / "nudged away from wall east" lines firing repeatedly BEFORE full encasement was reported. The `rescueFromBurial` nudge paths at [BotRescueService:304](src/main/java/net/wcfcarolina13/GameAI/services/BotRescueService.java#L304) and [:703](src/main/java/net/wcfcarolina13/GameAI/services/BotRescueService.java#L703) call `bot.setPosition` with a 0.4-block offset *away from one detected wall*, but never validate that the destination cell is itself clear. In a 1-block-wide corridor (walls on both sides), nudging "away from west wall" pushes the bot 0.4 deeper into the EAST wall. The rescue tick alternates between the two wall directions, drilling the bot through walls instead of out of them. Once the bot's center crosses a wall boundary, it's fully encased — and the rescue logic that depends on `bot.getBlockPos()` reading correctly is now operating on a totally bogus cell.

This is the actual mechanism behind the 1.1.103 incident. The 1.1.103 fix (gating `attemptEscapeMovement` on `!fullyEncased`) is the safety net that catches the resulting encasement and starts mining. This 1.1.104 fix prevents the encasement from happening in the first place.

### Fix

New helper `isNudgeDestinationSafe(world, x, y, z)` — checks both feet and head cells at the candidate position have empty collision (or climbable / fluid). Called from both nudge sites before `setPosition`. If the candidate is itself in a wall, skip that direction and try the next; if no direction has a clear destination, fall through to `attemptEscapeMovement` (which still returns false for fully-encased bots after the 1.1.103 gate).

The leftover velocity case (route lands bot in a validated cell, vanilla physics carries it sideways into an adjacent unvalidated cell on the next tick) — still possible in theory, but no longer the primary failure mode now that the nudge can't compound the drift.

### Verification

Build: `./gradlew build -x test` clean.

Manual:

- Repro setup from 1.1.103 incident: walk bot into a 1-block-wide cobblestone corridor with you. Wait for any clip-collision rescue trigger. Should NOT see alternating "nudged west / nudged east" log spam any more — only one direction at most, then fall through to mining if needed.
- Regression check: bot's normal "stuck against a single wall" rescue should still nudge correctly (only one wall, opposite direction is air, destination check passes).

### Out of scope (next)

- The deeper `leavesTrap=true` route-planner heuristic question is still open. The IdleSweep gate from 1.1.103 protects ONE call site; the same planner runs during follow + active drop-sweep + generic `MovementService.execute(DIRECT)`. A route-end-state validator + post-arrival-stability check is the comprehensive fix. Deferred again — the nudge fix should significantly reduce the symptom even when the planner picks a marginal route, so we want test data on whether that's "good enough" before doing the larger rewrite.

## Suffocation regression fix + IdleSweep cave guard + captain voice line + horse stale-state auto-clear (2026-05-10, 1.1.103)

Test data from 1.1.102 surfaced four regressions or long-standing bugs that the user flagged. All four fixed.

### 1. Suffocation regression — bot dying in walls (CRITICAL)

User reported: "bot got jammed into walls and suffocating again" — caused one death and several near-deaths. Confirmed in [logs/2026-05-09-3.log.gz at 22:42:23-30](file:///Users/roti/Library/Application%20Support/PrismLauncher/instances/1.21.11/minecraft/logs/2026-05-09-3.log.gz): bot fully encased in cobblestone (head AND feet = cobblestone) with `takingSuffocationDamage=true` for ~7 seconds, repeatedly logging `attempting escape movement toward north` while never mining out.

**Root cause.** [BotRescueService.rescueFromBurial:341](src/main/java/net/wcfcarolina13/GameAI/services/BotRescueService.java#L341) called `attemptEscapeMovement` first as the "try to nudge out before mining" optimization. But the helper returns `true` whenever ANY adjacent cell has air for both feet+head levels — regardless of whether a velocity-based escape can actually work. When the bot is fully encased in solid blocks, vanilla physics rejects horizontal velocity into the same solid block; the bot stays put while we falsely "succeed" the rescue. The mining branch at line 354 (with its 60-tick cooldown) was unreachable.

**Fix.** Gate the velocity-escape call on `!fullyEncased` (where `fullyEncased = headBlocked && feetBlocked`). When both are solid, skip straight to mining. Single-block-encased cases still try velocity first since that's what the optimization was designed for.

### 2. IdleSweep dragged bot into the encasement (root cause of #1)

The user's deeper question — "why did the bot end up fully encased to begin with?" — surfaced via log reconstruction. At 22:42:20 [IdleSweep](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) activated targeting an item drop 4 blocks away in a narrow cobblestone cave. The route planner committed to `route=282,50,1294 -> 281,50,1295 -> 280,49,1295 -> 279,48,1294`, descending the bot through a tight passage. The bot ended up at (278, 48, 1297) — fully encased in cobblestone — within seconds.

The [BotFleeService underground-linger decision tree](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java) was already saying "non-functional position but commander nearby — staying put" for this same cell. IdleSweep wasn't consulting that signal, so it activated anyway. The two systems disagreed and IdleSweep won.

**Fix.** Before activating IdleSweep in [tickOpportunisticIdleSweep](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java), call `SafePositionService.analyzeSurfaceCandidate(world, botBlock)`. If `!openSky() && !nearSurface()` — same gate the linger tree uses — reject sweep activation. The bot stays put exactly where the linger tree already wanted it to stay.

### 3. "Hey hey look at me" + captain-now sequence broken

User reported: (a) "hey, hey, look at me" line firing whether bot is on a horse or on land — basically random. (b) "I'm the captain now" never fires when the user looks at the bot, only "that ruined the joke."

**Root cause for (a).** Stage 1 entry condition `elevated = bot.getY() >= 70.0 && world.isSkyVisible(bot.getBlockPos().up())` evaluates to true everywhere outdoors above sea level. Walking around the overworld surface with the user → fires randomly.

**Fix for (a).** `elevated` now requires `bot.getY() >= commander.getY() + 3.0` AND sky access AND bot not currently following the commander. Real semantic: dramatically above the commander, stationary. Won't trigger during normal follow.

**Root cause for (b).** Stage 2 required 60 ticks (3 s) of *continuous* commander-looking-at-bot. A single tick of `commanderLookingNow=false` reset the streak to 0. Natural human looking pattern (glance, look back, reposition) means 3 s continuous never accumulates — so the captain line never fires, only the 14 s timeout fires "ruined joke."

**Fix for (b).** New `endShipLookAwayStreak` counter. Brief blinks (≤20 ticks of non-look) are tolerated; only ~1 s of continuous non-look resets the streak. Looking-tick accumulator stays at 60 (3 s) total looking time.

### 4. Horse disappearing — recurring (and previously dismissed)

User noted I dismissed this before. Reinvestigated with log evidence this time. [Background-agent investigation summary](https://example.invalid):

- Horse UUID `fd5c19b3-3fe0-4bac-a0ab-b29ff03b1672` saved at (243, 66, 1303) while bot was riding it. Subsequent `RideSyncTick` shows the horse position drifting 435+ blocks from the bot.
- [RideSyncService.resolvePreferredMount:1710](src/main/java/net/wcfcarolina13/GameAI/services/RideSyncService.java#L1710) rejects with `state-too-far` (over the 48-block search radius). Repeats every 3 s forever.
- Without auto-clear, the bot is permanently locked onto the unreachable saved entity. Even when the bot wanders into a fresh horse, the saved state blocks fresh pairing.

**Fix.** New per-bot rejection-streak counter `STALE_REJECT_STREAK` in [RideSyncService](src/main/java/net/wcfcarolina13/GameAI/services/RideSyncService.java). After 5 consecutive stale rejections (`state-too-far`, `mount-not-found-in-world`, `world-mismatch`, `outside-combined-radius`) — about 15 s at the existing 3 s log cadence — the saved state is auto-cleared via new `MountPersistenceService.clearRecordedState(bot, reason)`. Non-stale rejections (transient mismatches) reset the streak. Successful resolves also reset (via `noteSuccessfulMountResolve` — wired separately when the call site is touched next).

### Verification

Build: `./gradlew build -x test` clean.

Manual:

- Stand-alongside test for #1: trap bot in 1×1 cobblestone column (place blocks around bot). Bot should mine its way out within 3-6 s, not loop on nudges. Watch for `Bot Jake clearing headspace by mining Cobblestone` log line.
- Test for #2: descend with bot through a tight cave. Stop. Wait 15 s. Bot should NOT activate IdleSweep on items in the cave — it should stay put per the linger decision.
- Test for #3a: walk on flat ground with bot in follow. The "hey hey look at me" line should NOT fire.
- Test for #3b: provoke the joke (have bot riding a horse, look away, wait for the line, then look at bot for ~3 s). The captain line should fire reliably now even with normal head movement.
- Test for #4: lose the horse (let it wander or unload by distance). Within ~15 s of repeated `state-too-far` rejections in the log, see `Cleared stale mount state for Jake (stale-rejects=5 ...)` followed by free re-pairing on the next horse the bot mounts.

### Out of scope (next)

- The deeper question for #1 — how the route planner committed to a path that ended in a fully-encased cell. The `leavesTrap=true` route annotation suggests the planner KNEW it was leaving a trap, which is the wrong direction. Root-cause that planner heuristic in a follow-up pass.
- Wire `noteSuccessfulMountResolve(botId)` into the resolver's happy-path return — currently only declared. The auto-clear still works because non-stale rejections reset the streak; this is just defensive.
- Voice-line tightening: 1 s glance tolerance is generous; could expose as a Behavior tab toggle.

## Smoker preference + hunger-aware skill pause + Tier-1 backlog audit (2026-05-09, 1.1.102)

Two small features plus a backlog audit pass.

### Smoker preference for food cooking

User report (Tier-1 backlog): `resolveFurnaceTarget` was generic — picked any furnace-like (FURNACE / BLAST_FURNACE / SMOKER) by distance. Smokers cook food 2× as fast as regular furnaces; blast furnaces can't cook food at all. Now the resolver takes a `FurnacePreference` enum (`FOOD` / `ORE` / `ANY`) and:

- Filters out incompatible stations (BLAST_FURNACE rejected for FOOD; SMOKER rejected for ORE).
- Two-pass selection: prefer the specialized type first (SMOKER for FOOD, BLAST_FURNACE for ORE), fall back to plain FURNACE.
- Applied at every selection step: commander look-at, shared tactical registry, nearest-placed scan, inventory placement.
- Crafting fallback (step 5) keeps using the universal FURNACE recipe — smoker requires logs + cobblestone in a different layout, out of scope for this pass.

Wired: `cookAllFoodSync` always passes `FOOD`. `startBatchCookInternal` passes `FOOD` when `foodOnly=true`, else `ANY` (mixed-mode caller — preserves the current "accept any station" behavior so the user isn't locked out of cooking when only a blast furnace is nearby).

### Hunger-aware skill pause

User report (Tier-1 backlog): "bot works until death." Verified — only `HuntSkill`, `FishingSkill`, and `GrassSeedSkill` had starving checks; the long-running mining/woodcut/stripmine paths grinded until the bot starved.

New helper `HealingService.shouldPauseForStarvation(bot)`: returns true iff the bot is starving (`foodLevel ≤ HUNGER_CRITICAL`) AND a single `autoEat` pass couldn't fix it (no food in inventory or only forbidden food). Skills that already do their own hunger handling keep their existing logic; this helper is for the rest.

Wired into:

- [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java) main loop — pauses at iteration boundary, calls `flagManualResume`, sends "feed me, then /bot resume."
- [CollectDirtSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/CollectDirtSkill.java) main loop — covers `MiningSkill` (which extends it) too.
- [WoodcutSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java) main `while` loop.

### Tier-1 backlog audit (already-done items)

User flagged that some Tier-1 items might already be done. Verified:

- ✅ **Add shelves and containers to no-break list** — `ProtectedStructureBlockHelper.isProtectedContainer` already covers bookshelves (incl. chiseled), all chest variants, barrels, hoppers, dispensers, droppers, decorated pots, crafters, brewing stands, furnaces, blast furnaces, smokers, all 17 shulker box variants. Wired through `isNeverBreakBlock` and consulted from `BotStuckService` + `MovementService`. Backlog flipped to `[x]`.
- ✅ **Craft chest from wood** — `ToolProvisionService.ensureChest` exists and crafts an 8-plank chest when planks/logs are available; wired into `ChestStoreService` offload path, `HuntSkill` camp-build, `FishingSkill`. Backlog flipped to `[x]`.

### Out of scope (next session)

- Furnace offload fallback (Tier-1 #3) — when no chest is available, dump fuel-eligibles into the fuel slot. Genuinely missing but moderate surface (needs furnace screen-handler interactions). Backlog item retained.
- Hunger pause in `FarmSkill` — main loop is complex enough that the safe insertion point isn't obvious. Other skills (BridgeScaffoldService, ShelterSkill body, FortifyVillageSkill) also unhandled. If the user hits the symptom in those paths we add them; not blanket-applying to avoid breaking subtle skill interactions.
- Smoker crafting recipe wiring — would need `CraftingHelper` to know about the smoker recipe (logs + cobblestone in non-grid layout). Generic furnace remains the crafting fallback.

### Verification

Build: `./gradlew build -x test` clean.

Manual:

- Place a smoker near the bot (no other furnace types nearby). Run `/bot cook beef`. Bot should target the smoker. Place a regular furnace closer; bot should still prefer the smoker.
- Cook with only a blast furnace nearby: bot should report "I need a furnace (or similar) placed nearby" because BLAST_FURNACE is rejected for FOOD.
- Run `/bot skill stripmine 50` with hunger near zero and no food in inventory. Bot should mine 1-2 blocks then announce "I'm starving and out of food. Stripmine paused — feed me, then /bot resume." Feed bot, /bot resume, mining continues.

## NavHazardCache learning surfaced (2026-05-09, 1.1.101)

User report: "Bot doesn't seem to be getting better at pathfinding from the cache learning system we implemented." Investigation: the [NavHazardCache](src/main/java/net/wcfcarolina13/GameAI/services/navigation/NavHazardCache.java) is wired and recording (today's logs had 903 `applyMovementInput-reject` events — recording site is firing) but its only diagnostic log was a throttled `LOGGER.debug` invisible at default log levels. Hard to tell whether the cache is broken, ineffective, or working but only helping in scenarios with route alternatives.

Architectural note for the user's perception: the cache penalizes cells, biasing the pathfinder toward alternates. In tight bottlenecks (one-block-wide doorway with no alternative route) the bot still has to go through; the cache doesn't help there even when working perfectly. Today's stuck reports were dominated by doorways and pressure plates — exactly the no-alternative case. So "no perceived improvement" is partly architectural, not a bug.

### What changed

- Penalty hits at `NavHazardCache.penaltyFor` are now logged at INFO (throttled to 1/s, only when penalty ≥ 1.0). Previously `LOGGER.debug` invisible at default levels.
- New periodic learning summary every 5 minutes (`SUMMARY_LOG_INTERVAL_TICKS = 6_000L`) at INFO: `nav-hazard summary: cells=N active-penalty=M top=[(x,y,z)(score=S,rejects=R,successes=U), ...]`. Silent when the cache is empty (fresh server / pristine world).
- Promotion-to-score events still need work (next session): currently the `score += REJECT_INCREMENT` line is silent. If we want to see a cell graduate from "noise" to "tracked hazard," that transition should log too. Backlog item.

### Verification

Build: `./gradlew build -x test` clean.

After deploy: walk a bot through a few problem doorways, then watch the log over ~10 min. Should see periodic summary lines listing the top scoring cells. If summary stays at zero cells while rejections are firing, the streak-promotion threshold is too strict (`STREAK_PROMOTION_THRESHOLD = 3` rejections within `STREAK_WINDOW_TICKS = 40L` ≈ 2 s) — tune down. If summary populates but pathfinder never logs a penalty hit, it means pathfinding requests aren't going through cells with score (likely because the bot's stuck spots have no alternate routes for the pathfinder to weigh against).

### Out of scope (next session)

- Surface the cache state via a chat command (e.g. `/bot debug nav-hazard`) using the existing `debugTopCells` API, so the user can pull current state on demand instead of waiting for the periodic summary.
- Log score promotions at INFO when a cell first crosses the streak threshold ("cell X promoted to tracked hazard, score=1.5").
- Tune `STREAK_PROMOTION_THRESHOLD` / `STREAK_WINDOW_TICKS` once we have real data on whether they're starving the cache.

## Bed selection: skip occupied + prefer claimed bed (2026-05-09, 1.1.100)

User report: (a) bot sleeps in the user's same bed instead of its own; (b) when the user is asleep and only one bed (the user's) is nearby, the bot doesn't place its own bed from inventory.

### Root causes

[SleepService.findNearbyBedFeet](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java) returned every bed in a 24-block / vertical-12 box, sorted purely by squared distance to bot. Two problems:

- **No occupancy filter.** A bed where another player or villager is already sleeping (`BedBlock.OCCUPIED == true`) was still returned as a candidate. The loop would walk over to it, call `bot.trySleep`, get an `OCCUPIED` rejection mapped to `FAIL_OTHER`, and try the next bed. In a household with 1–2 beds this often left the bot idling next to the user's occupied bed, occasionally succeeding into a contested state.
- **No claim memory.** The bot's previously-recorded "last sleep" bed (via `BotHomeService.recordLastSleep` → `getLastSleep`) was tracked but never preferred during candidate sorting. Bot picked whichever bed was closest to its current position, which after a long day might be the user's bed.

### Fix

`findNearbyBedFeet` now takes the bot, filters out beds with `BedBlock.OCCUPIED == true` (logging the filtered count), and sorts candidates with the bot's claimed bed first (when present and not occupied), then by distance. Defensive late-occupancy guard added inside `tryUseBed` so a state change between filter and use also bails cleanly.

When all nearby beds are filtered (only occupied beds existed) and the bot has a bed item, the placement branch now fires with an explicit "Nearby bed is taken. Setting up my own." message, so the user sees the handoff. The existing `placeBedNearby` flow handles the rest — finds a 2-block-clear footprint within 4 blocks, swings the bed item, and beds down.

### What's where

- [SleepService.findNearbyBedFeet](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java) — signature gained `ServerPlayerEntity bot`. Reads `BotHomeService.getLastSleep(bot)` for claim priority. Filters `BedBlock.OCCUPIED`. New log: `findNearbyBedFeet: filtered N occupied bed(s); M candidate(s) remain` when at least one is filtered.
- [SleepService.tryUseBed](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java) — defensive occupancy check on `bed.foot` after geometry lookup. Bails to `FAIL_OTHER` instead of walking to a freshly-occupied bed.
- [SleepService.attemptSleep](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java) (the entry point) — message handoff when the only nearby beds were filtered: "Nearby bed is taken. Setting up my own."

### Verification

Build: `./gradlew build -x test` clean.

Manual:

- Single-bed household: user goes to bed first. `/bot sleep` should produce "Nearby bed is taken. Setting up my own." and the bot should place its own bed (assuming it has one in inventory or can craft).
- Two-bed household where the bot has slept in bed-B before: `/bot sleep` from a position closer to bed-A should still send the bot to bed-B (claim memory wins over distance).
- All beds occupied, bot has no bed item, no crafting materials: existing fallback "I couldn't craft a bed" path still fires.

### Out of scope

- "Reserve" semantics — this only filters momentary occupancy (`OCCUPIED == true`). If the user steps out of bed mid-night the bot may still grab it on a subsequent sleep cycle. Backlog if it becomes annoying.
- Multi-bot bed assignment: if two bots have the same claimed bed (both slept there once), they'll race for it. Backlog.
- Cross-day persistence of `getLastSleep` across world reloads — already exists via `BotHomeService` persistence, just leaning on it harder now.

## Protected-zone override via Resume (2026-05-09, 1.1.99)

User report: bot rejected `/bot skill stripmine` because the worksite was inside a registered base zone. Bot's caution was correct (zones are there to prevent the bot from accidentally griefing the user's build), but the user — being the zone's owner — should be able to *confirm and proceed* without yanking the entire zone or memorizing a flag. New flow reuses the existing Resume hotkey instead of inventing a new command.

### Flow

1. User runs `/bot skill stripmine` inside a protected zone.
2. Bot rejects with the existing protected-zone hazard message; rejection text now appends "Press Resume to override."
3. The skill calls `SkillResumeService.flagManualResume(bot)` (existing) AND `SkillResumeService.flagZoneOverridePending(bot)` (new).
4. User presses the Resume hotkey (or `/bot resume`).
5. The resume path detects the zone-override-pending flag → calls `ProtectedZoneOverrideService.grantOverride(uuid, 60_000L)` → re-issues the original skill command.
6. The re-run hits the same zone check, but `ProtectedZoneService.isProtectedForBot(uuid, pos, world, owner)` short-circuits to `false` because the override is active → skill proceeds.
7. Override is consumed by `SkillResumeService.clear(uuid)` (which fires on completion / `/bot stop` / death / new skill) or by the 60 s expiry — whichever comes first.

### What's where

- New [ProtectedZoneOverrideService](src/main/java/net/wcfcarolina13/GameAI/services/ProtectedZoneOverrideService.java) — per-bot `Map<UUID, Long>` of expiry timestamps. `grantOverride`, `hasActiveOverride`, `clearOverride`, `getRemainingMs`. ~70 LOC.
- New [ProtectedZoneService.isProtectedForBot](src/main/java/net/wcfcarolina13/GameAI/services/ProtectedZoneService.java) — bot-aware variant that short-circuits to "not protected" when the override is active, otherwise delegates to the existing anonymous `isProtected`.
- [MiningHazardDetector.inspectBlock](src/main/java/net/wcfcarolina13/GameAI/skills/support/MiningHazardDetector.java) — signature gained `UUID botUuid` and now uses `isProtectedForBot`. The two `detect()` call sites pass `bot.getUuid()`; `collectAdjacentHazards` already had `bot` so trivially threads through.
- New helper [MiningHazardDetector.isProtectedZoneHazard(hazard)](src/main/java/net/wcfcarolina13/GameAI/skills/support/MiningHazardDetector.java) — checks the failure-message prefix so callers can distinguish "this hazard is the zone gate" from "this hazard is lava / a drop / etc." String-based for now to avoid a wider Hazard-record refactor; the message prefix is stable.
- [SkillResumeService](src/main/java/net/wcfcarolina13/GameAI/services/SkillResumeService.java) — new `ZONE_OVERRIDE_PENDING` set, new `flagZoneOverridePending(bot)` API. `resume()` consumes the flag and grants the override. `clear()` drops both the flag and any active override so a /bot stop / death / new skill cleans up reliably.
- [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java) — at the existing protected-zone reject branch, calls `flagZoneOverridePending(player)` alongside the existing `flagManualResume(player)`. Other skills consuming `MiningHazardDetector` will need the same one-line addition when their zone-rejection path becomes user-actionable.

### Scope and trade-offs

- **One-shot**: each rejection grants a single 60 s window. No persistent "ignore zones" toggle — that would defeat the purpose of zones.
- **Bot-wide for the window**: the grant covers any zone the bot enters during the 60 s, not strictly the zone(s) it was rejected from. Rationale: a stripmine path can wander, and tracking the exact zone-set up front is complex. Window is short enough that this is acceptable.
- **String-tag for hazard kind**: `isProtectedZoneHazard` matches on `failureMessage` prefix. Stable today; if more hazard kinds need this discrimination, refactor `Hazard` to include a `kind` field (out of scope here).
- **Other skill paths**: only StripMineSkill is wired this round. Other skills that detect the protected-zone hazard via `MiningHazardDetector` (anything calling `MiningHazardDetector.detect`) get the bot-aware check for free, but won't queue the override-on-Resume flag until they call `flagZoneOverridePending`. Add as needed.
- **Non-MiningHazardDetector zone checks** ([BotActions.java:1423](src/main/java/net/wcfcarolina13/GameAI/BotActions.java#L1423), [FeedAnimalsSkill.java:59](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FeedAnimalsSkill.java#L59)) still call the anonymous `isProtected(pos, world, null)` path. These continue to reject regardless of override. Migrate when the user encounters a friction case there.

### Verification

Build: `./gradlew build -x test` clean.

Manual:

- Stand inside a registered protected zone with bot following. Run `/bot skill stripmine`. Bot should refuse with "...Press Resume to override."
- Press Resume hotkey. Chat should say `<botName> will ignore protected-zone refusals for the next 60s.` Skill resumes and now mines.
- Run `/bot stop` mid-mine. Override should be cleared (next stripmine in the same zone refuses again with the prompt).
- Wait 60 s without pressing Resume after a refusal. Pressing Resume after the timer should still re-run the skill but the bot will refuse again (override grant happens at Resume time but the re-run also hits the now-expired check).

## Locked-gate enclosure respect + torch-hold diagnostic logs (2026-05-09, 1.1.98)

Two small wins.

### Locked-gate enclosure respect

User report: marking a gate as locked already blocked pathfinding ([LockableBlockService](src/main/java/net/wcfcarolina13/GameAI/services/LockableBlockService.java) memory entry from 2026-04-06), but the bot still ran into locked enclosures to fetch item drops, got stuck against the inside wall, and left mobs free to escape past it. New ray-cast helper [DangerousPursuitGate.crossesLockedGate](src/main/java/net/wcfcarolina13/GameAI/services/DangerousPursuitGate.java): samples ~1 cell per block along the bot→target line and returns true if any sampled cell is a tracked locked door / fence gate / trapdoor.

Wired into:

- [DangerousPursuitGate.isLocationSafeForPursuit](src/main/java/net/wcfcarolina13/GameAI/services/DangerousPursuitGate.java) as Rule 5 — drop-sweep candidates whose line crosses a locked gate are rejected and stamped into the retry-cooldown so they're skipped for the cooldown window.
- [BotEventHandler.engageHostiles](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) — combat target acceptance loop. The bot won't run through a locked gate to fight a mob on the far side. Self-defense is preserved because a hostile that's already past the gate (`mob.getTarget() == bot`) would already be on the bot's side.

Known caveat: ray-cast over-rejects when there's a wall containing both a locked gate AND an unlocked gap, since the line could cross the locked cell while a legitimate path goes around. Acceptable trade — under-rejection (current behavior) was the actual user complaint.

### Torch-hold diagnostic logs

User reported [BotTorchHoldService](src/main/java/net/wcfcarolina13/GameAI/services/BotTorchHoldService.java) "doesn't seem to be working." Service is deployed (1.1.93+) and registered, but the only log line was a `LOGGER.debug` that never surfaces at default levels. Refactored `shouldHoldTorch` → `evalHoldRejection` returning a stable reason string (`mode-IDLE`, `light-15`, `audible-hostile-zombie`, etc.). On reason change OR successful hold/yield, log at INFO. New `LAST_REJECT_REASON` map suppresses repeat lines so the log doesn't drown in identical entries. Next deploy will tell us what's actually gating the service in dim follow situations.

### Verification

Build: `./gradlew build -x test` clean.

## Dangerous-pursuit gate (2026-05-09, 1.1.97)

User report: bot dives into dark caves full of mobs to fetch XP orbs and item drops, accepts fall damage to chase non-aggroed mobs, etc. New shared gate composes four rules at the two relevant call sites.

### The gate

[DangerousPursuitGate](src/main/java/net/wcfcarolina13/GameAI/services/DangerousPursuitGate.java) (95 LOC, two static methods):

- `isLocationSafeForPursuit(bot, targetPos, world)` — environment rules:
  1. Target sits more than 4 blocks below bot's Y → reject (fall-damage threshold).
  2. Combined sky+block light at target ≤ 0 → reject (pitch-black target; mobs spawn there and bot can't see).
  3. 2+ visible hostile mobs (`HostileEntity` or `Monster`) within 5 blocks of target → reject (cluster danger).
- `shouldEngageNonAggro(bot, candidate)` — combat rule:
  4. Mob is targeting bot (`mob.getTarget() == bot`) → engage. Otherwise require ranged weapon (`BotActions.hasRangedWeapon`). Wandering hostiles that haven't spotted the bot don't get rushed with a melee weapon.

### Wiring

[DropSweepService.collectNearbyDrops](src/main/java/net/wcfcarolina13/GameAI/services/DropSweepService.java) — added inside the per-drop iterator filter, alongside the existing retry-cooldown check. Rejected drops are stamped into `dropRetryTimestamps` so they're skipped for the cooldown window.

[BotEventHandler.engageHostiles](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) — added inside the hostile-filter loop next to the `BotCombatPolicyService.shouldBotAttack` check. Self-defense (mob targeting bot) always passes. Initiating combat against a wandering mob requires a long-range weapon.

### Verification

Build: `./gradlew build -x test` clean.

### Out of scope (next session)

- Path-based fall analysis: currently rule 1 only checks vertical distance to the target, not the actual route. A target at the same Y as bot but reachable only by descending a 6-block shaft would still pass. A real route check would need a pathfinder probe.
- Light-level rule rejects the *target* tile but doesn't check the route. A bright target reached via a dark corridor still passes. Probably fine for now — the route brightness will be reflected by the bot already being there or the target naturally being unreachable to the pathfinder.
- Charged-creeper detection in pursuit cluster (rule 3) treats a single charged creeper the same as a single normal creeper. Could weight charged creepers as 2 toward the cluster threshold.

### Backlog item filed alongside

- **BotTorchHoldService not visibly firing** — service deployed in 1.1.72-1.1.91, registered, but user reports torch never goes into hand in dim follow situations. Likely caused by overly-strict combat suppression + foreign-swap detection cycling against other selected-slot mutators. Diagnostic-first: bump key state transitions to INFO-level logs and re-test. Possible fix: drop the 8-block audible-hostile gate, keep only the 16-block visible-LOS gate. Don't touch until the user confirms the diagnostic approach.

## Creeper handling: charged escalation + shield-when-stuck + guide/HUD exposure for stand-down (2026-05-09, 1.1.96)

Three threads in one bump:

### Charged-creeper escalation

Vanilla charged creepers (lightning-powered) have roughly 2× the blast radius of a normal creeper, but [BotEventHandler](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) was treating them identically: 6-block engagement radius, 4.5-block shield radius, 12-block flee target. The bot would be in *certain* lethal range while still doing the "block + shield" trick that's only viable against a small-blast creeper. New thresholds when `CreeperEntity.isCharged()` is true:

- Engagement radius: 12 blocks (was 6)
- Shield radius: 8 blocks (was 4.5)
- Flee-target distance: 24 blocks (was 12)
- Block-and-shield trick **disabled** for charged — a single block barrier doesn't survive a charged blast at point-blank range, so the bot just flees + shields.

### Shield-when-can't-make-distance

User report: "be sure it uses its shield when it can't make distance from the creeper, if it has a shield." Added per-bot tracking via a new `CREEPER_FLEE_STATE` map keyed on bot UUID, holding `CreeperFleeMemory(creeperUuid, lastDistance, lastTick, stuckTicks)`. Each flee tick, if distance hasn't improved by ≥0.3 blocks since last sample, the stuck counter accumulates ticks. Once it reaches 20 (≈1 s of zero progress) AND the bot has a shield in main- or off-hand, the shield raises even when outside the normal shield radius. State is dropped when the bot exits engagement range so a future approach gets a clean slate. Pattern matches existing shield detection at [BotFleeService.java:593](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java#L593).

### Stand-down: guide entry + HUD hint

User noted yesterday's 1.1.95 stand-down hotkey isn't discoverable. Added:

- **Guide topic** "Stand Down" in [BotGuideScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java) Basics section, between Stop and Resume. Documents that hold-`\` → 1 is the access path, that drop-sweep is suppressed for the full 60 s, and that any other command cancels the auto-resume.
- **Looking-at-bot HUD hint** in [FrensClient.renderLookedAtBotStatusHint](src/main/java/net/wcfcarolina13/FrensClient.java) gets a second line — `Hold [\] for Stand Down (60s pause + auto-resume follow).` — only when the bot is following or actively working (not when paused/returning home, since stand-down doesn't fit those contexts). Existing line 1 unchanged. Box now scales for 1 or 2 lines.

### Verification

Build: `./gradlew build -x test` clean.

### Out of scope (next session)

- Visual countdown for the stand-down timer over the bot's head.
- Charged-creeper detection currently checks `isCharged()` on the closest threat only. If multiple creepers are nearby and only one is charged, the radii currently reflect the closest. Probably fine — by the time you're getting murdered by a non-closest charged creeper, the closest one has already detonated — but worth a follow-up if it turns out to matter.
- "Can't make distance" only triggers shield raise. Could also abandon flee and dig in / wall up if shield isn't available, but that requires a place-block action against a path the bot is desperately trying to flee down — risk of self-trapping is high.

## Stand-down hotkey + stop→drop-sweep cooldown (2026-05-09, 1.1.95)

Two halves of one feature, paired because they share infrastructure (per-bot drop-sweep suppression timestamps). Both target the same daily friction: bot grabs XP/items the user wanted, or re-enters drop-sweep seconds after a `/bot stop`.

### Bug 1 — Stop didn't actually stop the sweep loop

Today's logs showed 48 `Drop sweep approach failed (direct: aborted)` lines clustered between 13:08:21 and 13:08:54 — a 33-second window where the bot kept restarting sweeps almost immediately after each abort. [DropSweepService.collectNearbyDrops](src/main/java/net/wcfcarolina13/GameAI/services/DropSweepService.java) had a 4 s global cooldown but no per-bot suppression after an explicit user-driven stop. So `/bot stop` would cancel the in-flight sweep, but the next opportunistic decision tick (a few seconds later) would happily start a new one.

### Bug 2 — "Stop" hotkey was duplicated

Two stop paths existed: tap `\` (calls `handleStopLook`), and hold `\` → companion overlay → slot 1 (also `handleStopLook`). The slot-1 duplicate is now repurposed as **Stand Down**: the bot stops following + drop-sweeping for 60 s and then auto-resumes follow against the same target. Closes the "I sniped a mob, now my bot is running into a cave to grab the orbs" loop.

### Fix

New per-bot suppression layer in `DropSweepService`:

- `suppressFor(UUID, long durationMs)` / `isSuppressedFor(UUID)` / `getSuppressionRemainingMs(UUID)` — `ConcurrentHashMap<UUID, Long>` of expiry timestamps. `merge(Math::max)` so a longer suppression isn't shortened by a later shorter one.
- `collectNearbyDrops` short-circuits on `isSuppressedFor` before any other gating.

[modCommandRegistry.executeStop](src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java) now calls `DropSweepService.suppressFor(bot.getUuid(), 60_000L)` after `requestCancel`. Also calls `BotStandDownService.cancel` so a stale stand-down auto-resume doesn't fire after the user explicitly halted.

New service [BotStandDownService](src/main/java/net/wcfcarolina13/GameAI/services/BotStandDownService.java) (94 LOC):

- `beginStandDown(bot, durationMs)` — snapshots current follow target via `BotEventHandler.getFollowTargetUuid`, calls `stopFollowing(bot, false)` (silent — we'll send our own message), registers drop-sweep suppression for the same window.
- `onServerTick(server)` — when timer expires, re-issues `setFollowMode(bot, target, false)` and posts a "Back in formation." chat line. If the saved follow target is offline, leaves the bot idle (no fallback target).
- Tick handler registered in `Frens.java` next to `BotAutoHuntService::onServerTick`.

New brigadier command `bot standdown [target]` in [BotLifecycleCommands.buildStandDown](src/main/java/net/wcfcarolina13/Commands/BotLifecycleCommands.java); wired into the `bot` literal next to `buildStop()`. Targets via the standard `resolveTargetBots` helper, so `/bot standdown all` works.

Client side: [CompanionHotkeyOverlayHud](src/main/java/net/wcfcarolina13/GraphicalUserInterface/CompanionHotkeyOverlayHud.java) slot 1 label flipped from `🛑 Stop` to `🪖 Stand Down (60s)`. [FrensClient.executeOverlayHotkeySelection](src/main/java/net/wcfcarolina13/FrensClient.java) slot 1 now calls a new `handleStandDownLook` helper that resolves the looked-at bot and sends `bot standdown <name>`. Tap `\` still does the regular stop.

### Verification

Build: `./gradlew build -x test` clean.

Manual:

- Have bot following you. Press and hold `\`, release on slot 1. Bot should announce stop-follow, then 60 s later say "Back in formation." and resume follow.
- Snipe a mob within 8 blocks of a stood-down bot. Drops should sit unmolested for the duration of the timer.
- `/bot stop bob` while a sweep is in flight: sweep cancels and no new sweep starts for 60 s. Verify by watching `Drop sweep approach failed` cadence in the log — should drop to zero for the cooldown window.

### Out of scope (next session)

- Visual countdown timer over the bot's head during stand-down (could overload existing overhead-line system).
- A "stand down all" broadcast hotkey (current path requires looking at one bot at a time). The `/bot standdown all` chat command works.
- Stand down should also cancel an in-flight pursuit/combat target — currently only follow + drop-sweep are gated. If the bot is mid-attack on a mob when the hotkey fires, it'll keep swinging until that mob is resolved, then the stand-down kicks in.

## Auto-eat prefers cooked food over raw (2026-05-09, 1.1.94)

Originally branched from `dafd7b8` (1.1.71) on a `cooked-food-preference` worktree; cherry-picked onto main and shipped as 1.1.94 after the worktree was cleaned up.

### Bug

[HealingService.findCheapestSafeFood](src/main/java/net/wcfcarolina13/GameAI/services/HealingService.java) ranks edible inventory by `nutrition + saturation*2.0` and picks the lowest score, on the theory that the cheapest food is the right thing to spend on routine top-ups. That logic systematically prefers raw meat over cooked: raw beef scores 6.6 while cooked beef scores 33.6, so a bot with a stack of cooked beef and a stack of raw beef will eat the raw stack first. With raw chicken in the mix it's worse — same low score plus a 30% food-poisoning chance per bite.

This affects all three eating paths since they all consult the same finder: `autoEat` (tick-loop hunger top-up), `stabilizeEat` (pre-shelter / in-shelter), `healBot` (`/bot heal`).

### Fix

Two-pass search inside `findCheapestSafeFood`: first pass excludes raw meats; if no cooked food turns up, second pass admits the raw meats. Raw meats are matched against a small `RAW_MEATS` `Set<Item>` (`BEEF`, `PORKCHOP`, `MUTTON`, `CHICKEN`, `RABBIT`, `COD`, `SALMON` — the seven vanilla raw foods that have a cooked counterpart). Tropical fish has no cooked variant; pufferfish is already in `FORBIDDEN_FOODS`.

The cheapest-cooked-first policy still applies *within* the cooked tier, so a bot with bread + cooked beef will still eat bread first (low nutrition + saturation = low score = cheap routine top-up). Only the raw-vs-cooked ordering changes.

`isRawMeat(ItemStack)` is exposed as a public predicate in case other services want to filter by it later.

### Out of scope (follow-ups)

The same `nutrition + saturation*2.0` scoring pattern is duplicated in four other places that would each need their own raw-aware variant if we want the policy applied consistently:

- [HuntSkill:1372](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HuntSkill.java#L1372)
- [NavigationArtifactService:825,843](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java#L825) (fast-travel provisioning — arguably correct as-is, since raw meat is fine to *carry* if the bot can cook later)
- [BotMutualAidService:982](src/main/java/net/wcfcarolina13/GameAI/services/BotMutualAidService.java#L982) (food-sharing)
- [CombatInventoryManager:350](src/main/java/net/wcfcarolina13/PlayerUtils/CombatInventoryManager.java#L350)

Left untouched in this commit — the user-reported symptom is "auto-eat picks raw," and the auto-eat path is HealingService only. The other sites should be audited under a separate task.

### Verification

Manual: spawn a bot with `give @s minecraft:beef 64` and `give @s minecraft:cooked_beef 64`, run hunger down (e.g. wait or sprint), watch which item slot decrements first. Pre-fix: raw beef. Post-fix: cooked beef. Repeat with chicken to confirm the food-poisoning surface is closed.

Build: `./gradlew build -x test` clean.

## Jukebox-driven dancing + actually-working stopEmote (2026-05-08, 1.1.93)

### Stop API correction

**1.1.92's `stopEmote` was a silent no-op.** The deployed-1.1.92 log showed `Emotecraft.stopEmote(UUID) not found; long-running emotes will not be cancelable` — the API method I probed for doesn't exist in Emotecraft 3.2.0-b.149. `javap`-ing `ServerEmoteAPI.class` from the installed jar confirmed: there is no `stopEmote`, but `setPlayerPlayingEmote(UUID, Animation)` accepts `null` as the "stop" signal. The bridge now resolves that method instead, and `EmotecraftBridge.stopEmote(bot)` invokes it as `setPlayerPlayingEmote(uuid, null)`. The 1.1.92 duration cap, jukebox-stop, and all state-broken stops finally take effect.

Renamed the cached field from `STOP_EMOTE_METHOD` to `SET_EMOTE_METHOD` to reflect what we're actually calling. Same `NoSuchMethodException` fallback semantics — bridge stays usable if the method ever gets renamed again.

### Jukebox-driven dancing

Bots now dance when a music disc is playing in a nearby jukebox. Builds on the 1.1.92 dance-stop infrastructure — same eligibility gates, same active-dance bookkeeping, but adds a second trigger path that bypasses the cooldown and roll, and suppresses the duration cap for as long as the music keeps playing.

### New trigger path

[BotRandomDanceService.tickJukeboxStart](src/main/java/net/wcfcarolina13/GameAI/services/BotRandomDanceService.java) runs every server tick (no eval throttle, since we want the bot to react visibly when a song starts):

- Probes for a `JukeboxBlock` with `Properties.HAS_RECORD=true` within `JUKEBOX_HEAR_RADIUS=12` blocks horizontally / `JUKEBOX_VERTICAL_RANGE=3` blocks vertically. Cached for 10 ticks (0.5 s) per bot to keep the cost off the hot path.
- If no dance is currently active and the bot passes the standard eligibility gates (IDLE mode, no task, not using item / mounted / sleeping, no nearby hostile), picks a random dance from the 4-emote pool and fires it. Marks `JUKEBOX_DRIVEN` so the stop check knows to skip the duration cap.

### Stop semantics for jukebox dances

`tickStopCheck` already runs every tick. When the active dance is jukebox-driven, the duration cap is skipped for as long as `isJukeboxPlayingNear` still returns true. When the user pops the record (or the song ends and HAS_RECORD flips false), the dance stops on the next tick — logged as `reason=music-ended`. State-broken stops (sleep / mount / aggro / task) still apply identically.

### Why HAS_RECORD as the proxy

The block-state property is the cheapest reliable "music playing" signal in 1.21.11. A record sitting in a finished jukebox keeps the bot dancing past actual song end, but only until the user extracts the record (HAS_RECORD → false → stop). Combined with the per-bot 10-tick scan cache and the hostile/state stop conditions, the bot's behaviour stays bounded.

### Random idle is unchanged

`tickRandomStart` was renamed from `tickStartCheck` and otherwise untouched. Same 200-tick eval, 2% probability roll, 5-min cooldown, 20-second duration cap. Jukebox-driven and random-idle paths are mutually exclusive — the start guard `ACTIVE_DANCE_SINCE.containsKey(id)` prevents both from firing concurrently.

## Stop runaway dance emotes (2026-05-08, 1.1.92)

Bug: bot started a random dance and never stopped — dancing through sleep, follow, combat. The Emotecraft dance emotes (`backflip`, `twerk`, `club_penguin_dance`, `roblox_potion_dance`) are looping animations; once dispatched they animate indefinitely until explicitly cancelled. [BotRandomDanceService](src/main/java/net/wcfcarolina13/GameAI/services/BotRandomDanceService.java)'s 5-minute cooldown only prevented re-triggering, and [EmotecraftBridge](src/main/java/net/wcfcarolina13/GameAI/services/EmotecraftBridge.java) had no `stopEmote` wrapper.

### Fix

- **`EmotecraftBridge.stopEmote(bot)`** — reflection on `ServerEmoteAPI.stopEmote(UUID)`. Probed in `initialize()` with a `NoSuchMethodException` fallback that logs a warning so the bridge stays usable if the API surface ever changes name (looping dances would just stop being cancellable, the rest of the bridge keeps working).
- **`BotRandomDanceService.tickStopCheck`** — runs every server tick (no eval throttle, since stops need to react fast when the bot enters a bed or mounts up). Tracks `ACTIVE_DANCE_SINCE` per bot; cancels via `stopEmote` when:
  - `bot.isSleeping()`, `hasVehicle()`, `isUsingItem()` → user state changed.
  - `Mode != IDLE`, `TaskService.hasActiveTask()` → bot picked up work.
  - Hostile within audible/visible radius → combat suppression.
  - `MAX_DANCE_DURATION_TICKS = 400` (≈20 s) → hard cap. Long enough to look intentional, short enough that a mistimed trigger can't dominate behaviour.
- **`tickStartCheck`** — split out from the old `tickBot`. Same eligibility + 5-min cooldown gates as before, plus a new "no concurrent dance" guard so a new dance can't kick off before the stop check has cleared the previous one.

### Reset hook

`BotRandomDanceService.reset()` now clears `ACTIVE_DANCE_SINCE` alongside the other maps so server restarts don't leave a stale "still dancing" flag that would block the next start.

## Partial-block-aware arrival check (2026-05-08, 1.1.91)

Defense-in-depth for the long-standing "bot oscillates / appears stuck on carpets, pressure plates, soul sand, slabs" symptom. The 2026-04-10 doorway-stall autopsy fixed the *passability* side (`WalkablePartialBlocks.isPathable` returns true for these) but the *position-reporting* side was never addressed — `bot.getBlockPos()` floors the entity Y, so on partial-height floors the bot's reported cell is the floor block itself rather than the cell above.

| Floor type at Y=N | bot.getY() | floor(Y) | expected feet cell |
|---|---|---|---|
| Full block (dirt) | N + 1.0+ | N + 1 | N + 1 ✓ matches |
| Soul sand / mud | N + 0.875 | N | N + 1 ✗ off by 1 |
| Slab bottom / snow8 | N + 0.5 | N | N + 1 ✗ off by 1 |
| Carpet / plate / snow1 | N + 0.0625 | N | N + 1 ✗ off by 1 |

Pathfinder waypoints live at `floor + 1`, so a 1-block phantom Y delta makes the bot appear "not arrived" even when it is physically there. Most user-visible bite point: [MovementService.nudgeTowardExactBlock](src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java#L1198) was timing out (1.1–1.6 s) and oscillating because `bot.squaredDistanceTo(targetCenter)` couldn't drop below `reachSq=0.64` when the bot's actual Y was ~1.4 below the target center.

### Fix

New helper [BotPositionUtil.effectiveFootCell](src/main/java/net/wcfcarolina13/GameAI/services/BotPositionUtil.java) — promotes the reported foot cell upward by one when the bot is overlapping with a partial-height block (top collision &lt; 1.0). Full-collision overlaps (closed door, fence, wall) still return the literal cell so genuine "stuck inside a block" cases are still detected.

Two targeted updates in [MovementService](src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java):

1. **`nudgeTowardExactBlock`** (post-loop arrival check): replace `bot.getBlockPos().equals(target)` with `BotPositionUtil.isAt(bot, target)`. Catches the case where the loop times out but the bot is actually at the target cell.
2. **`nudgeTowardUntilClose`** (inner spin loop): add a per-iteration foot-cell-match short-circuit alongside the existing `distSq <= reachSq` exit. The bot now bails as soon as it's at the target cell instead of spinning until timeout.

### Why this is "partial sweep" not "full sweep"

The same `getBlockPos().equals(target)` brittleness exists in ~15 other arrival sites (FishingSkill stand check, CollectDirtSkill stair-foot, FlowerPickSkill search center, FortifyVillageSkill approach, etc.). None of them are on the per-tick movement hot path the way `MovementService` is — most are skill-internal and pair with retry/distance fallbacks, so the user-visible impact is "skill takes one extra retry" rather than "bot oscillates visibly." Sweeping those is straightforward when a specific skill turns up the failure pattern. Helper is in place; future updates are one-line swaps.

### Caveat

`canAcceptMovementImpulse`'s same-cell short-circuit ([BotActions.java:1886](src/main/java/net/wcfcarolina13/GameAI/BotActions.java#L1886)) intentionally uses raw `getBlockPos().equals(...)` because it wants literal cell sameness, not effective-foot semantics. Helper is documented to call out which kind of check the caller actually wants.

## Anchor cell validation on bonus read (2026-05-08, 1.1.90)

Tightening for the "world has changed" failure mode in [PassageAnchorService.bonusFor](src/main/java/net/wcfcarolina13/GameAI/services/navigation/PassageAnchorService.java).

Before this change, an anchor whose door was replaced with a non-passable, non-door block (e.g. door demolished, stone block placed in the gap) would sit in the cache pulling paths until 7 in-game days of disuse decayed it past the eviction threshold. The pathfinder's passability gate kept things *correct* — the wasted bonus couldn't route the bot through a wall — but iterations were spent expanding into the dead anchor before routing around.

The fix adds one block-state read on the primary-anchor lookup path:

```java
if (entry != null && entry.score > 0.0F) {
    if (isAnchorCellValid(serverWorld, x, y, z)) {
        primary = Math.min(BONUS_CAP, entry.score * BONUS_SCALE);
    } else {
        synchronized (entry) { entry.score = 0.0F; }
        DIRTY.set(true);
    }
}
```

`isAnchorCellValid` accepts `DoorBlock` / `FenceGateBlock` / `TrapdoorBlock` *or* air. Air is allowed because "door removed but opening preserved" is a legitimate state — the bot can still walk through. Anything else is stale → score zeroed → next prune-and-flush at +30s evicts the entry.

Halo neighbors (the 6-cardinal expansion that gives 0.7× of an anchor's bonus to its immediate neighbors) intentionally *skip* validation — fractional contribution is bounded at `BONUS_CAP × NEIGHBOR_BONUS_FRACTION = 4.2`, and the staleness self-corrects when the halo cell is queried directly. Saves 6 extra block reads per neighbor expansion.

Net cost: one block-state read per anchor hit (which is rare — most pathfinder neighbor lookups miss the cache entirely). Net benefit: stale anchors die in one tick instead of seven days.

## Passage anchors + button-direction cleanup (2026-05-08, 1.1.89)

### Dead-code removal: button-direction mining hint

The `scanForButtonDirection` / `isButtonBlock` / `horizontalFromVector` helpers in [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java) and [CollectDirtSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/CollectDirtSkill.java) — which let the player drop a button next to the bot to indicate which way it should mine — are gone. Modern callers pass `issuerYaw` / `issuerFacing` directly when invoking the skill, so the fallback was already redundant. The new `isRedstoneComponent` protection (1.1.88) would have fought this code anyway since the bot now refuses to break ANY block adjacent to a button. Same pass also removed an unread `"buttonDirection"` shared-state lookup in CollectDirtSkill.

### Passage anchor system

New service [PassageAnchorService](src/main/java/net/wcfcarolina13/GameAI/services/navigation/PassageAnchorService.java) — the inverse of `NavHazardCache`. Where NavHazard punishes cells that rejected movement, PassageAnchor *rewards* cells where movement succeeded through a doorway, biasing future paths through known-good corridors over time.

#### Detection

`PassageAnchorService.onBotTick` is called from [BotActions.applyMovementInput:273](src/main/java/net/wcfcarolina13/GameAI/BotActions.java#L273), one cheap fast-path check per movement tick:

1. Look up `LAST_FOOT_CELL[botId]`. If unchanged from current cell → return immediately (hot path).
2. On change, probe both endpoints (foot + head, since doors are 2-tall) for `DoorBlock` / `FenceGateBlock` / `TrapdoorBlock`.
3. If found, increment that cell's anchor score (`TRAVERSE_INCREMENT=1.0`, capped at `MAX_SCORE=30`).

No mode-gating — any traversal counts (follow, return, hobby, manual `/bot come`). The user's "follow me out of the basement" scenario is the strongest signal but not the only one.

#### Pathfinder bonus

[BaritoneStylePathFinder.java:380](src/main/java/net/wcfcarolina13/PathFinding/BaritoneStylePathFinder.java#L380) and [PathFinder.java:152, 186](src/main/java/net/wcfcarolina13/PathFinding/PathFinder.java#L152) subtract `PassageAnchorService.bonusFor(world, x, y, z)` from each neighbor's `tentativeG`. The bonus has two layers:

- **Direct hit** on an anchor cell: `min(BONUS_CAP=6.0, score × BONUS_SCALE=0.6)` — up to 6 cost units off, vs `NavHazardCache.PENALTY_CAP=12` for rejection.
- **6-neighbor halo**: any cardinal neighbor that's an anchor donates `bonus × NEIGHBOR_BONUS_FRACTION=0.7`. This widens the attractor band so a path one block off-center still feels the pull.

The anchor pull is intentionally smaller than the rejection penalty — it nudges paths, doesn't override navigation logic. A door used twice barely registers; a door used 30 times pulls strongly.

#### Decay and persistence

- `DECAY_PER_TICK=1/4000` (≈0.005/sec) — slower than `NavHazardCache` (0.05/sec). Anchors are durable knowledge, not transient hazards. An unused anchor decays past the eviction threshold over ~7 in-game days.
- Eviction: `score < 0.05 && age > 7 in-game days`.
- Persistence: `<configDir>/frens/passage_anchors.json`, partitioned `worldKey -> dimensionId -> "x,y,z"`. Mirrors `NavHazardCache` storage layout.
- Async flush every 30s via dedicated `frens-passage-anchor-flush` thread; sync flush on `SERVER_STOPPING`.

Lifecycle wired into [Frens.java](src/main/java/net/wcfcarolina13/Frens.java): `restartExecutors` + `load` on server start, `onServerTick` for decay/flush, `flushSync` + `shutdownExecutors` on stop.

## Block protection list expansion (2026-05-08, 1.1.88)

Extended [ProtectedStructureBlockHelper](src/main/java/net/wcfcarolina13/GameAI/services/ProtectedStructureBlockHelper.java) to cover everything the user asked to keep bots away from. The list was previously narrow (lodestone/beacon/conduit/glass-like/containers); it now covers entire categories.

### New predicates

- `isWorkstation` — crafting table, smithing table, loom, anvil/chipped/damaged anvil, grindstone, fletching table, cartography table, stonecutter, lectern, composter, jukebox, note block.
- `isRedstoneComponent` — redstone wire, redstone torch (standing + wall), redstone block, redstone lamp, repeater, comparator, observer, piston/sticky piston/piston head/moving piston, lever, daylight detector, target block, tripwire + tripwire hook, dispenser, dropper, hopper, crafter, all `BlockTags.BUTTONS`, all `BlockTags.PRESSURE_PLATES`.
- `isCauldron` — empty / water / lava / powder-snow.

### Context-aware overload

`isNeverBreakAt(World, BlockPos)` adds neighbor and entity scans on top of the state-only check:

- 6-cardinal-neighbor scan: refuse if any neighbor is a redstone component (stops the bot from mining a wall block that holds up a wire/repeater) or a chain/lantern variant (so removing the support drops the chain).
- Painting entity scan: 5-block expand-box around the target; if any painting's "behind" face overlaps the target, refuse. Catches all 1×1 → 4×4 painting variants.

### Glass-like extended

`isProtectedGlassLikeTranslationKey` now suffix-matches `_lantern` and `_chain` so 1.21.11 copper-lantern and copper-chain variants (plus any future `*_lantern`/`*_chain` blocks) are caught without per-block listing. Tinted glass / iron bars / stained glass + panes / pressure plates suffix were already covered.

### Wire-up

[MiningTool.mineBlock](src/main/java/net/wcfcarolina13/PlayerUtils/MiningTool.java) now calls `isNeverBreakAt(world, targetBlockPos)` at both the pre-mining gate (line 150) and the per-tick mid-mining check (line 254). This is the single chokepoint for every mining caller (skills, break-free, surface recovery, pillar overhead-mining), so the new categories block everything from hobby pickaxe-swings to stuck-recovery digging.

## Base-aware pillar defense + auto-zones from bases (2026-05-08, 1.1.87)

Three layered fixes for the failure where Jake — sitting at the home base coord underground — broke through the user's roof during a hobby pre-flight surface escape (see `latest.log` 21:51 in 1.21.11 instance: pillar mining at 274/272/270, y=64–73 around base center 273,48,1294).

### 1. Pillar refuses to chew through registered bases ([ScaffoldService.java:331](src/main/java/net/wcfcarolina13/GameAI/services/construction/ScaffoldService.java#L331))

Before `MiningTool.mineBlock(headSpace)` in `pillarUpWithPositions`, check `BotHomeService.findBaseNearPosition`. If the overhead block is inside any registered base's protection radius, log and `break` — pillar abandoned with whatever blocks were placed so far. Cheap belt-and-suspenders that fires even if zones aren't loaded yet.

### 2. Bases auto-mirror as `ProtectedZoneService` zones ([BotHomeService.java:84](src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java#L84), [Frens.java:769](src/main/java/net/wcfcarolina13/Frens.java#L769))

The "base" feature previously only seeded behaviors (linger, animal-defense scope, fast-travel range) and had **zero** block protection. Closing that gap:

- New label scheme `base:<normalized>` for auto-zones — separate keyspace from user-created zones (no collision risk).
- Hooks in `addBase` / `removeBase` / `renameBase` / `setBaseRadius` upsert/remove/rename/resize the matching zone.
- New system-level APIs in `ProtectedZoneService` (`upsertZoneInternal`, `removeZoneInternal`, `renameZoneInternal`, `isLoaded`) — bypass the player-actor permission gate that the user-facing `createZone` requires. `LOADED_WORLDS` set marks worlds whose zone file has been read so eager writes don't get clobbered when `loadZones` runs later.
- Owner mapping: player UUID is parsed and stored on the zone so `BotTerritoryAuthorizationService` recognizes the bot's commander as authorized. Server-owned bases (sentinel `SERVER`) map to a null-owner zone — protects all bot mutations including the owner's own bots, which is correct for spawn.
- Migration: `BotHomeService.syncZonesFromBases(server, world)` runs once at server start (after `ProtectedZoneService.loadZones`) and back-fills zones for every existing base. Idempotent — re-runs upsert the same data. This is what fixes existing worlds (like Nirn) where the zones folder is empty but bases exist.

Zone size uses the base's stored radius if non-zero, otherwise `DEFAULT_BASE_PROTECTION_RADIUS=40` — same default already used by all other "near base" decisions, so the protection footprint matches what the user already mentally associates with the base.

### 3. Hobby pre-flight skips when bot is underground inside its own base ([BotFleeService.java:2336](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java#L2336))

`shouldSuppressHobbyEscape` now checks `BotHomeService.findBaseNearPosition` and returns `true` if the bot is sitting inside any registered base. This branch only fires when the bot is **already** underground (caller gates on `!isAtSurface`), so "underground + inside base" means the bot is in its own basement/storage — surfacing requires breaking through walls, not navigating a door. Better to skip the hobby than chew through the structure. The other suppression conditions (commander nearby, nighttime, post-task grace) still fire first.

### Diagnosis notes

`NavHazardCache` (the rejection-cost cache that pathfinders consult) is loading and pruning correctly — 278 rejection events recorded in the failure session. It just can't help here: the cache only steers *future* paths, the current Baritone plan doesn't replan when one node fails, and pillar recovery bypasses pathfinding entirely. The penalty (`PENALTY_SCALE=0.4`, capped at 12) is also too small relative to a 19-block ascent for one fence to reroute the path.

## Underwater dialogue suppression (2026-05-07, 1.1.86)

Voiced dialogue is now suppressed while the bot's head is submerged in water, unless **both** the bot and its controller (resolved via `CompanionCommunicationPolicy.resolveController`) have `WATER_BREATHING` or `CONDUIT_POWER`. Implemented as a single gate in [BotDialoguePlayer.playSoundInternal](src/main/java/net/wcfcarolina13/ChatUtils/BotDialoguePlayer.java) — the chokepoint for both `tryPlayDialogueDetailed` (chat → sound) and `playSoundForBotDetailed` (direct ambient/programmatic sound). Returns `PlayResult.DISABLED` when suppressed, so callers don't fall back to chat spam. `forcePlaySound` (the `/bot sound_test` debug bypass) is intentionally not gated.

Villager-specific dialogue gating was already in place: [VillageProximityReactionService.hasNearbyVisibleVillagers](src/main/java/net/wcfcarolina13/GameAI/services/VillageProximityReactionService.java) requires an actual `VillagerEntity` within 40 blocks with line-of-sight before villager lines fire — not just village structure proximity.

## Audio handoff integration (2026-05-07, 1.1.85)

Integrated the audio batch from [/Users/roti/pontus/ai-player-dialogue/audio_triage/handoff_to_mod_repo.md](file:///Users/roti/pontus/ai-player-dialogue/audio_triage/handoff_to_mod_repo.md) — covers 232 events flagged `map`. Most of those events were already in `sounds.json` from prior batches; this pass added the 53 brand-new event blocks and 31 additional sound variants on existing events, plus 94 OGG copies.

### Integration tooling

New helper: [tools/audio/integrate_handoff.py](tools/audio/integrate_handoff.py). Parses the handoff markdown directly (each `### bot.line.X` section's copy bullets + JSON fence), then:

1. Copies referenced OGGs from `pontus/ai-player-dialogue/<batch>/output_ogg/<file>.ogg` to `src/main/resources/assets/frens/sounds/dialogue/<file>.ogg`. Skips files already present at the same byte size.
2. Merges sounds.json — creates new event blocks for new IDs, dedupe-appends new variants to existing blocks.
3. Reports new events / appended variants / missing sources.

Idempotent — re-running fills only the gaps. Dry-run by default; `--apply` actually writes.

### Snowball-fight dialogue wiring (1.1.81 → audio-ready)

The 32 inline strings emitted by [BotSnowballFightService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotSnowballFightService.java) via `CompanionOverheadDialogueService.showOverheadLine()` had no SoundEvent constants registered, so the audio that just shipped wouldn't have played. Wired the full chain:

1. **32 `LINE_SNOWBALL_*` SoundEvent constants** registered in [BotDialogueSounds.java](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java).
2. **32 `EXACT_MAP` entries** in [DialogueTextMapper.java](src/main/java/net/wcfcarolina13/ChatUtils/DialogueTextMapper.java) — exact-text → SoundEvent lookup, which is how `showOverheadLine` finds the audio for inline-string lines.
3. **32 `SUBTITLE_MAP` entries** in [BotDialoguePlayer.java](src/main/java/net/wcfcarolina13/ChatUtils/BotDialoguePlayer.java) for the closed-caption pass.

Plus subtitle entries for the 5 warden + 4 snow-golem + 3 iron-golem-daisy lines that had been missed in 1.1.82/1.1.83 (the audio playback already worked via direct SoundEvent reference inside `WeightedLine`/`tryTrigger`, but closed-captions needed the map entries).

3 of the 32 snowball lines have no audio yet (TTS regen pending): `snowball_probe_incoming`, `snowball_escalate_on_now`, `snowball_escalate_in_for_it`. The IDs are registered and the EXACT_MAP entries are in place, so the audio will pop into place when those OGGs land — no further mod-side changes needed for those.

### Numbers

- `sounds.json`: 682 → 735 events (+53), 31 new variants on existing events
- `dialogue/`: 1440 → 1523 OGGs (+83 unique copies; 11 of the 94 source files were duplicates of OGGs already at the same name from earlier batches and were overwritten harmlessly)
- Recent feature audio coverage: snowball 29/32, warden 5/5, snow-golem 3/4, iron-golem-daisy 2/3

### Verification (manual)

1. Build + boot the mod. No load-time errors should appear about missing sound events.
2. Trigger a snowball fight (60s idle/follow with snow nearby + commander present). Bot should emit a probe line; audio should play.
3. Approach an iron golem with a poppy in inventory. Bot should drop the poppy and a daisy line should play with audio.
4. Approach a snow golem. Bot should emit a snow-golem line with audio (subject to the 5-min cooldown).
5. Approach a warden (carefully). Bot should emit one of the warden avoidance lines with audio.
6. Sleep next to a bot. Wake-up line should play with audio (per 1.1.84 fixes).

## Wake-up dialogue fixes (2026-05-07, 1.1.84)

User reported never hearing the bot's post-sleep lines despite the pool, sound IDs, and audio assets all being present. Three compounding bugs found:

### Bug 1: 30-minute content cooldown

[CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) had `wake_up` keyed to `COOLDOWN_MEME_MS` (30 min real time). Wake-up isn't a meme — that's wildly over-restrictive. Lowered to 30 seconds. The 10-min service-level cooldown in `BotWakeUpDialogueService.COOLDOWN_TICKS` is the real per-sleep gate, so the content cooldown only needs to prevent same-tick double-firing.

### Bug 2: Random-suppression burned the service cooldown

[BotWakeUpDialogueService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotWakeUpDialogueService.java) updated `LAST_WAKE_LINE_TICK = nowTick` on the 60% speak-chance roll *even when the roll said "stay silent"*. The line never played, but the bot was now locked out for 10 minutes — so the next sleep cycle would also be silent if the previous random roll lost. Combined with the 60% gate, that compounds to ~36% silence streaks of 20+ minutes (two losing rolls in a row). Fix: only update the cooldown when the line actually fires.

### Bug 3: Global "recently shown" suppression races the 2 s fade-out delay

The wake line is scheduled 40 ticks (2s) after the wake edge so the sleep-screen fade-out finishes. During that 2s, *any* other ambient line on the same bot blocks the wake-up via `CompanionOverheadDialogueService.isRecentlyShown` (4s window) inside `tryTrigger`. Fixed by:

1. Wiring up the previously-no-op `bypassSuppression` parameter on `tryTrigger` (it was declared as `debugPath` and never consulted). Existing debug-command callers already pass `true` and bypassing suppression is correct for those too.
2. Adding `CompanionContextReactionService.playWakeUpForced(bot)` that passes `bypassSuppression=true`.
3. `BotWakeUpDialogueService` now calls `playWakeUpForced` from its scheduled task — the wake schedule was set on the wake edge specifically and should win over any unrelated ambient line.

### Diagnostics

Promoted the wake-up scheduling and fire-or-not logs from `debug` to `info` in `BotWakeUpDialogueService`, so the user can verify in `latest.log` whether the schedule is firing. Log lines:

- `Scheduled voiced wake-up line for bot {} in 40 ticks` — schedule was set
- `Wake-up dialogue suppressed (random silence) for bot {}` — 40% silence roll
- `Wake-up line for bot {}: fired` / `not fired (cooldown)` — what `playWakeUpForced` returned

### Files touched

- [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) — cooldown lowered, `playWakeUpForced` added, `tryTrigger` parameter renamed `debugPath` → `bypassSuppression` and wired into the recently-shown check.
- [BotWakeUpDialogueService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotWakeUpDialogueService.java) — random-suppression no longer burns cooldown; calls `playWakeUpForced`; logs at `info` level.

### Verification (manual)

1. Sleep with a bot nearby (within 16 blocks). On wake, you should now hear one of the four lines: "You know you snore like a piglin?" / "I had the strangest dream..." / "A good night's rest." / "Seize the day!" Roughly 60% of wakes should fire (40% silent by design).
2. Check `latest.log` for the `Scheduled voiced wake-up line` and `Wake-up line for bot ...: fired` info entries to confirm the path runs.
3. Sleep two consecutive nights. Both should be eligible to fire (subject only to the 60% roll and the 10-min service-level cooldown — no longer the 30-min content cooldown).

## Smell-trigger constraints + warden avoidance dialogue (2026-05-07, 1.1.83)

Three dialogue-pool refinements based on user-noted overuse + a new pool.

### "Something smells good" — gated on actually-edible food

[CompanionContextReactionService.tryCookingNearby](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) previously fired on any lit furnace/campfire — including a furnace cooking cobblestone or sand. Now delegates to [CookingReactionService.isNearActivelyCookingFood](src/main/java/net/wcfcarolina13/GameAI/services/CookingReactionService.java#L199), which inspects the input + output slots of furnaces/smokers/blast furnaces (must contain a food item) and the 4 campfire cooking slots (must be non-empty). Same scan radius (6 blocks). Smokers were already covered via `instanceof AbstractFurnaceBlock`. Made the helper public for reuse.

### "Smells terrible" — relocated to a contextually-gated trigger

The line was firing as part of [BotAmbientChatter](src/main/java/net/wcfcarolina13/ChatUtils/BotAmbientChatter.java) `AMBIENT_CAVE_CHATTER` any time the bot transitioned overworld → underground. Now removed from that pool and given a dedicated trigger in `CompanionContextReactionService.trySmellsTerrible` that only fires when at least one of the following is in scanning range:

- **Mobs (within 12 blocks):** zombies, slimes, witches, zombie villagers. `ZombieEntity` covers `ZombieVillagerEntity`/`HuskEntity`/`DrownedEntity`; `SlimeEntity` covers `MagmaCubeEntity`.
- **Mob spawner block** within 8 blocks (dungeon vibes).
- **Lush caves biome** at the bot's position (musty moss smell).
- **Block triggers within 6 blocks:** mushroom (small + large + stem), rooted dirt, moss block, moss carpet, clay, coarse dirt, mud, mycelium.

RNG-first gating (~0.5% per ~1 Hz check) so the expensive block scan only runs when we're about to fire. 5-min per-bot cooldown. The existing `LINE_AMBIENT_SMELLS_TERRIBLE` audio is unchanged — only the trigger logic moved.

This means a bot underground with no smelly source will no longer randomly mutter "Smells terrible." — it'll only fire when there's a contextually-coherent reason for it (you're in a damp lush cave, or near a dungeon spawner, or near a rotting mob, etc.).

### Warden avoidance dialogue (new)

5 new SoundEvent constants in [BotDialogueSounds.java](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java) and a new `WARDEN_NEARBY_LINES` pool. Fires when a `WardenEntity` is within 32 blocks. 3-min per-bot cooldown, ~4% per-tick fire rate so most encounters land 1–2 lines rather than a stream.

The existing `isScaryNearby` already routed warden into the generic `SCARY_LINES` ("I hate that sound."); these new lines are warden-specific avoidance/fear:

- "We need to leave. Now."
- "Not a sound. Not a single sound."
- "Don't make a peep. I'm serious."
- "Please tell me that's not what I think it is."
- "Sneak. Don't sneak loudly. Just sneak."

Audio for these is **Pending** — IDs registered but no OGGs yet. Tracked in [AUDIO_NEEDED.md](AUDIO_NEEDED.md) under "Warden proximity (1.1.83)".

### Backlog audit — all 14 user-noted dialogue items already shipped

User asked to double-check 14 dialogue ideas. All confirmed shipped before this session — only annotations needed:

| Item | Where it ships |
|---|---|
| "Can we keep it?" — cute animals | `cute_animal_*` (1.1.61) |
| Pandas variants (worried/lazy/brown/aggressive) | `panda_*` (1.1.60) |
| Foxes/ocelots near chickens | `fox_ocelot_near_chickens` (1.1.60) |
| "Meow" — cats | `cat_meow` (1.1.59) |
| "What's up, porkchop?" — zombified piglins | `zombified_piglin_porkchop` (1.1.58) |
| "Bacon spree" — hoglins | `hoglin_bacon_spree` (1.1.58) |
| "Bigger than the others" — piglin brutes | `piglin_brute_bigger` (1.1.58) — note: user said "hoglin brute"; that mob doesn't exist, code targets `PiglinBruteEntity` |
| "Dinosaur" — sniffer | `sniffer_dinosaur` (1.1.58) |
| "Goblins with wings" — vexes | `vex_goblins_wings` (1.1.59) |
| Tech-o-no-lo-hee-ah / hell and back — redstone | `redstone_machine_*` (1.1.64) |
| Mob-crusher anti-cruelty | `mob_crusher_*` (1.1.63) |
| "Did you see that dolphin?" | `dolphin_did_you_see` (1.1.59) |
| Tamed/untamed nautilus | `nautilus_ride` / `nautilus_ocean_never` (1.1.57) |
| "Quality animal" scope-down (donkeys/camels/llamas/horses) | `MOUNT_QUALITY_LINES` + `hasNearbyMountAnimal` ([PetProximityReactionService.java:213-232](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L213-L232)). `AbstractHorseEntity.class` covers donkeys (subclass), llamas, trader llamas, horses. `CamelEntity` checked separately. 5-min cooldown |

### Files touched

- New cooking-helper visibility: [CookingReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CookingReactionService.java) — `isNearActivelyCookingFood` made public.
- Smells-good gate: [CompanionContextReactionService.tryCookingNearby](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java).
- Smells-terrible relocation: [BotAmbientChatter.java](src/main/java/net/wcfcarolina13/ChatUtils/BotAmbientChatter.java) `AMBIENT_CAVE_CHATTER` (line removed); [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) — new `SMELLS_TERRIBLE_LINES`, `trySmellsTerrible`, `hasSmellyContextNearby`, cooldown entry, dispatch hook.
- Warden pool: [BotDialogueSounds.java](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java) — 5 new `LINE_WARDEN_*` constants; [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) — new `WARDEN_NEARBY_LINES`, `tryWardenNearby`, cooldown, dispatch hook.
- [AUDIO_NEEDED.md](AUDIO_NEEDED.md) — 5 warden lines added as Pending; smells-terrible relocation note.

## Snowman + iron-golem-daisy dialogue + Base Manager polish (2026-05-07, 1.1.82)

Three small features + a backlog audit pass.

### Snowman proximity dialogue

New `SNOW_GOLEM_NEARBY_LINES` pool in [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) fires when a `SnowGolemEntity` is within 12 blocks with line of sight. 5-min per-bot cooldown so a snow-golem army doesn't drown out other reactions. Lines reference the snowball-fight feature shipped in 1.1.81: "He makes the ammo, I do the throwing." Sound IDs registered in [BotDialogueSounds.java:912-916](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java#L912-L916) (OGGs not yet recorded — overhead text + chat fall-through fires regardless).

### Iron golem with daisy

When the bot is within 5 blocks of a non-angry `IronGolemEntity` and has a `Items.POPPY` or `Items.OXEYE_DAISY` in inventory, it offers the flower at low per-tick chance (~4%). Models the vanilla villager-children-give-poppies-to-iron-golems behavior:

1. Closest un-gifted golem selected (one-shot per golem UUID, tracked in `TriggerState.giftedGolems`).
2. `LookController.faceEntity` turns the bot toward the golem.
3. One flower removed from inventory via `stack.split(1)`.
4. `ItemEntity` spawned at the golem's feet with a 40-tick pickup delay so the moment reads as "given," not just dropped.
5. `IRON_GOLEM_DAISY_LINES` pool fires ("Hold on big guy, I've got something for you." / "Iron golem with a flower. Cute, right?").

Note: we don't make the golem visually *hold* the flower — that's hardcoded vanilla AI tied to villager goals and isn't reachable without mixins. The dropped flower at the golem's feet is the visual payoff. 30-min cooldown per trigger so the bot doesn't dump its entire flower inventory on the same village.

Anger gate uses the `Angerable.hasAngerTime()` interface method (not `isAngry()` which doesn't exist on `IronGolemEntity` in 1.21.11 — first compile attempt caught this).

### Base Manager UX polish (carry-forward from 2026-04-20)

[BaseManagerScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BaseManagerScreen.java) — full spec landed:

- **Sort:** registered bases (Base/Wall/Village kinds) first, lodestones second. Stable sort in `applyBasesJson` preserves server-supplied order within each group.
- **In-list section headers:** new `DisplayRow` record represents either a header or a base; `buildDisplayRows` inserts "⌂ Registered Bases" + "◆ Lodestone Compasses" headers when both groups have entries (single-group lists get no header to avoid clutter). Headers are non-clickable rows that take ROW_H pixels with their own subtle stripe + underline.
- **Hit-test math:** `mouseClicked` now maps display-row index → base index via the same `buildDisplayRows` helper, skipping headers.
- **Per-row hover tooltip:** new `setHomeTooltipFor(BaseDto, alias)` helper returns kind-specific text. Captured during the list rendering pass into `hoverTooltip`/`hoverTooltipX`/`hoverTooltipY` fields, then drawn via `context.drawTooltip` after `disableScissor` so the tooltip box isn't clipped.
- **Chat echo on Set Home:** new `echoToChat(msg)` helper sends a client-only message (`player.sendMessage(text, false)`) confirming the action ("Jake will treat 'home' as home." / "Jake's home compass → home."). Visible to the clicker only, not broadcast — appears in chat outside the menu so the user can verify the action took.

The `contentHeight()` and `listRect()` calculations also derive from `buildDisplayRows().size()` rather than the raw bases list, so the scrollbar and click-area math include the new header rows correctly.

### Backlog audit pass — 5 items flipped to ✅

Pre-implementation audit caught these as already shipped, just unchecked:

| Item | Where it lives |
|---|---|
| Pig staring at bot | [CompanionContextReactionService.tryPigStaring():1283-1298](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java#L1283-L1298) |
| Diggy diggy hole | [CompanionContextReactionService.onBotBlockBreak()](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) — DIRT_DIG_LINES at 8% on dirt-family blocks |
| Going-for-walkies / Who's-a-good-dog voiced | [BotDogWalkingHobbyService.playSessionStartLine():176-188](src/main/java/net/wcfcarolina13/GameAI/services/BotDogWalkingHobbyService.java#L176-L188) (1.1.65) |
| Drop-sweep cobblestone loop | [DropSweeper.ensureSpaceForDropSweep:284-286](src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java#L284-L286) + per-bot TTL self-drop suppression at [216-217](src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java#L216-L217) (1.1.70) |
| "Quality animal" scope-down | [PetProximityReactionService.MOUNT_QUALITY_LINES + hasNearbyMountAnimal():213-232](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L213-L232) |

Backlogs updated in [RALPH_TASK.md](RALPH_TASK.md) and the personal vault `Feature Backlog March 2026.md` with implementation pointers.

### Files touched

- New dialogue + interaction logic: [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) — added `SnowGolemEntity` + `IronGolemEntity` + `ItemEntity` + `ItemStack` + `LookController` imports, `SNOW_GOLEM_NEARBY_LINES` and `IRON_GOLEM_DAISY_LINES` pools, `trySnowGolemNearby` and `tryIronGolemDaisy` methods, `giftedGolems` field on TriggerState, dispatch hooks in `onServerTick`, cooldown entries.
- New sound IDs: [BotDialogueSounds.java](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java) — 4 snow-golem + 3 iron-golem-daisy entries.
- Base Manager UX: [BaseManagerScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BaseManagerScreen.java) — `DisplayRow` record, `buildDisplayRows`/`setHomeTooltipFor` helpers, sort in `applyBasesJson`, render-loop rewrite, mouseClicked hit-test rewrite, `contentHeight`/`listRect` row-count fixes, `echoToChat` helper, hoverTooltip fields, post-scissor tooltip render.

### Verification (manual)

1. Build a snow golem near a bot. Wait. Bot eventually says one of the snowman lines. Verify ~5min cooldown.
2. Pick a poppy or oxeye daisy from a flower forest, give it to a bot. Approach a non-angry iron golem. Bot turns to face it, drops the flower at its feet, says a daisy line. Try the same golem again — bot doesn't re-gift. Try a different golem with a remaining flower — bot gifts that one too.
3. Open Base Manager with a mix of registered bases + lodestones. Confirm rows are sorted (bases first, lodestones below), section headers visible, list scrolls correctly. Hover any row — tooltip shows kind-specific Set Home description. Click Set Home on a base — chat shows "[Bases] Jake will treat 'home' as home." Repeat with a lodestone — chat shows "[Bases] Jake's home compass → home."

## Snowball-fight idle hobby (2026-05-07, 1.1.81)

A new playful idle behavior. After a sustained period (60s) of being in `Mode.IDLE` or `Mode.FOLLOW` with snow available (snowballs in inventory or `Blocks.SNOW`/`SNOW_BLOCK` within 6 blocks), a bot may throw a single snowball at the nearest non-bot player. If the player reciprocates **with their own snowball**, the bot escalates to a sustained snowball fight. Otherwise it drops back to idle with a 5-minute cooldown so it doesn't pester.

### Eligibility gating

Initiation requires *all* of the following on both bot and commander:

- Mode in {IDLE, FOLLOW}, no active task ([TaskService.hasActiveTask](src/main/java/net/wcfcarolina13/GameAI/services/TaskService.java)), not in [BotFleeService.isInShelter](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java), no recent hostile damage in the last 200 ticks ([BotCombatCalloutService.wasRecentlyDamagedByHostile](src/main/java/net/wcfcarolina13/GameAI/services/BotCombatCalloutService.java#L1068))
- Commander within 16 blocks, same world
- Health ≥ 40% of max on both ("not dire" — bot doesn't pester wounded players, won't initiate when itself wounded)
- Hunger ≥ 6 on both (vanilla loses sprint/regen below 6, that's the "dire" threshold)
- Neither in the `hurtTime > 0` red-flash damage window
- Neither in `entity.inPowderSnow` (the field is naturally false when a player is wearing leather boots — they stand *on top* of powder snow rather than sinking in, so the leather-boots immunity falls out for free)
- Not at night with combined light ≤ 7 *and* either party wearing zero armor (`PlayerEntity.getArmor() == 0`)
- No `HostileEntity` within 16 blocks of either party

The 60-second eligibility timer resets every time *any* condition fails, so transient threats (a phantom flying overhead, a dip into hunger, a brief hurt flash) cleanly defer initiation.

### State machine

- **IDLE** — eligibility evaluated each tick. Sustained 60s + has snowball → throw probe + transition to PROBING. PROBE_LINES emitted with the throw.
- **PROBING** — bot threw the initiation snowball. Watches a 16-block radius for any `SnowballEntity` whose `getOwner()` is the commander. If detected within 30s → ACTIVE. Damage hook also escalates if the commander's snowball lands directly on the bot. Real attack (mob, or commander hits with a non-snowball) → abort + cooldown. Window timeout → TIMEOUT_LINES ("Tough crowd…", "Guess you don't wanna play.") then cooldown.
- **ACTIVE** — entered with an ESCALATE_LINES line ("Oh, it's ON now!"). Throws every 30–50 ticks at the commander. ~10% chance of a TAUNT_LINES quip per throw. When a commander snowball is detected near the bot, ~25% chance of a DODGE_LINES line (rate-limited to once per 2s). Hostile mob hit during ACTIVE → bot flees via existing [BotFleeService.fleeFromEntity](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java#L661) but the throw cadence keeps running, fulfilling the "throws snowballs while fleeing like a provoked peaceful mob" spec. Commander leaves 24-block range → fight ends with cooldown.
- **YIELDED** — entered when ACTIVE bot runs out of snowballs. YIELD_LINES emitted ("Out of ammo — I yield!", "Mercy! I surrender!"). 3-second grace; during that window any attacker triggers `BotFleeService.fleeFromEntity` (vanilla peaceful-mob flee). Then drops to IDLE with the 5min cooldown applied.

### Throw mechanism — vanilla path

Throws route through the same code real players use:

1. [BotActions.ensureHotbarItem](src/main/java/net/wcfcarolina13/GameAI/BotActions.java#L836) moves a snowball stack into a hotbar slot and selects it (no-op if already in hand).
2. `aimAt()` sets `bot.setYaw/setHeadYaw/setBodyYaw/setPitch` to face the target's eye, with an upward bias of `horiz · 0.12` to compensate for gravity drop on the snowball arc — the same correction a player intuits.
3. `stack.use(world, bot, Hand.MAIN_HAND)` triggers vanilla [SnowballItem.use](https://github.com/) which plays the throw sound, spawns a `SnowballEntity` with velocity from the bot's pitch/yaw (POWER 1.5, divergence 1.0), increments the USED stat, and decrements the stack.
4. On `ActionResult.isAccepted()`, `bot.swingHand(MAIN_HAND, true)` for the arm animation.

This means the snowballs the bot throws are indistinguishable from player throws: same trajectory physics, same `getOwner()`, same `Stats.USED` increment, same sound, same collision behavior — anything that listens to vanilla snowball events sees the bot the same way it sees a player.

### Dialogue pools

Six pools, all emitted via [CompanionOverheadDialogueService.showOverheadLine](src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadDialogueService.java#L172) (overhead nameplate hologram, 3.5s):

- **PROBE_LINES** — initiation throw ("Catch this!", "Wanna play?", "Snowball fight?")
- **ESCALATE_LINES** — fight just became ACTIVE ("Oh, it's ON now!", "Eat snow!")
- **TAUNT_LINES** — random 10% chance per throw during ACTIVE ("Bullseye!", "Hold still!")
- **DODGE_LINES** — commander snowball flying near bot during ACTIVE ("Whoa, close one!", "Missed me!")
- **TIMEOUT_LINES** — probe expired without reciprocation ("Tough crowd…", "Suit yourself.")
- **YIELD_LINES** — ran out of ammo in ACTIVE ("Out of ammo — I yield!", "Mercy! I surrender!")

### Powder-snow handling

`Entity.inPowderSnow` is set when an entity's bounding box overlaps a `POWDER_SNOW` block. Vanilla rule: leather boots prevent the fall-through, so a booted player stands on top of powder snow without their bbox overlapping the block — `inPowderSnow` stays false. We rely on this behavior directly instead of explicitly checking the boots slot, which keeps the gate aligned with whatever vanilla does in future patches.

### Files touched

- New: [BotSnowballFightService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotSnowballFightService.java) (~390 lines).
- [Frens.java](src/main/java/net/wcfcarolina13/Frens.java) — `BotSnowballFightService::onServerTick` registration; damage hook calls `notifyBotDamaged` from inside the existing `ServerLivingEntityEvents.ALLOW_DAMAGE` registered bot branch so reciprocation by snowball, mid-fight mob attacks, and post-yield retaliation all route through one entry point.

### Verification (manual)

1. Stand near a bot in `/bot follow`. Wait ~60s on a snow biome (or hand the bot snowballs). Bot throws a single snowball with a PROBE_LINES line. If you ignore it, no further throws — at 30s, bot says a TIMEOUT_LINES line and returns to normal.
2. Throw a snowball back at the bot. Bot transitions to ACTIVE with an ESCALATE_LINES line, then throws every ~1.5–2.5s. Occasional TAUNT_LINES and DODGE_LINES.
3. Empty the bot's snowballs while ACTIVE. Bot says a YIELD_LINES line, stops throwing, enters 5-min cooldown.
4. While ACTIVE, hit the bot with bare fists or sword. Bot flees (existing `BotFleeService` movement) while continuing to throw snowballs at the commander on cadence — provoked-peaceful-mob behavior.
5. Damage the bot with a real mob (zombie, skeleton). Same flee-while-throwing behavior.
6. Drop bot health below 40% with `/damage` or fall damage. No initiation. Heal. Wait the gate window again.
7. Drop hunger below 6 with `/bot set hunger 5 <bot>`. No initiation. Restore.
8. Stand on powder snow without leather boots. No initiation. Equip leather boots — bot now stands on top, `inPowderSnow` clears, initiation resumes after the gate window.
9. Try at night in a dark area with no armor. No initiation. Add a single piece of armor. Allowed.

## Emotecraft: wire remaining 7 emotes (clap / here / kazotsky_kick / 4 random dances) (2026-05-06, 1.1.80)

Wires up the seven Emotecraft emotes that 1.1.79 left dormant. All hooks are soft-dependency through [EmotecraftBridge](src/main/java/net/wcfcarolina13/GameAI/services/EmotecraftBridge.java); silent no-op when Emotecraft isn't installed.

| Emote | Trigger |
|---|---|
| `clap` | Commander breaks a high-value ore (`DIAMOND_ORE`, `DEEPSLATE_DIAMOND_ORE`, `EMERALD_ORE`, `DEEPSLATE_EMERALD_ORE`, `ANCIENT_DEBRIS`) → nearest visible bot within 16 blocks claps. **Also**: commander kills a high-value hostile (warden, elder guardian, wither, ender dragon, ravager) → every visible bot within 24 blocks claps. Pure emote, no voice line |
| `here` | Bot fires the `end_ship_look_at_me` line (existing 1-stage of the captain-now bit) → bot does the "come here" gesture. Also: bot announces "Waiting by the opening" at a follow drop-off → `here` overhead-gesture so the commander can spot them visually |
| `kazotsky_kick` | Skill completes successfully AND the skill name is in the celebratory set (`woodcut`, `farm`, `fortify`, `mining`, `hunt`, `fishing`, `shelter`, `wool`, `harvest`, `collectdirt`). Wired in [SkillManager.runSkill](src/main/java/net/wcfcarolina13/GameAI/skills/SkillManager.java#L256) finally block right after `TaskService.complete(ticket, success)` |
| `backflip`, `twerk`, `club_penguin_dance`, `roblox_potion_dance` | Rare random idle from the new [BotRandomDanceService](src/main/java/net/wcfcarolina13/GameAI/services/BotRandomDanceService.java). One of the four picked at random when a bot has been calmly idle for an extended period |

### BotRandomDanceService details

- Per-bot evaluation every 200 ticks (10 s).
- Eligibility: `Mode.IDLE`, no active task, not using item, not in vehicle, not sleeping, no hostile within 16 blocks LOS or 8 blocks regardless of LOS (same combat-suppression model as `BotTorchHoldService`).
- 2% sample probability per evaluation. Hard cooldown of 6000 ticks (5 minutes) between dances per bot. Combined avg interval is ~10 minutes of eligible idle time.
- Uses `force=true` on the bridge `playEmote` call so the per-bridge 30 s emote cooldown doesn't gate dance picks (the per-bot 5 min cooldown is the actual rate-limiter).

### CompanionContextReactionService.onBotBlockBreak — clap insertion point

The high-value-ore branch runs BEFORE the `directlyBelowFeet` palm/dig-down branch, so a commander mining diamond ore *while standing on top of it* gets both reactions: clap (for the ore) and palm (for the dig-down). The bridge's per-emote 30 s cooldown ensures the bot doesn't try to play both at the same instant.

### Files touched

- New: [BotRandomDanceService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotRandomDanceService.java) (~120 lines).
- [Frens.java](src/main/java/net/wcfcarolina13/Frens.java) — `BotRandomDanceService::onServerTick` registration + reset on stop, two new private helpers (`isCelebrationWorthyKill`, `triggerNearbyBotClappingForKill`), invoked from existing `AFTER_DEATH` handler.
- [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) — high-value-ore branch in `onBotBlockBreak` non-bot path; `here` emote after `end_ship_look_at_me`.
- [BotEventHandler.java](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) — `here` emote in the FOLLOW wait-above announce branch.
- [SkillManager.java](src/main/java/net/wcfcarolina13/GameAI/skills/SkillManager.java) — `kazotsky_kick` after successful completion of celebratory skills + `CELEBRATORY_SKILLS` set constant.

### Verification

1. **Clap (ore):** stand near a bot, mine a diamond ore. Bot claps within ~250 ms (no voice line).
2. **Clap (kill):** spawn a warden (creative-only or via /summon) near a bot in LOS, kill it yourself. Every visible bot within 24 blocks claps.
3. **Here (end-ship):** the existing end-ship sequence fires (rare; needs the conditions). Bot does the come-here gesture along with the line.
4. **Here (wait-above):** lead a bot in `/bot follow` mode to a drop-off too dangerous to follow. After ~30 s the bot announces "Waiting by the opening" and gestures with `here`.
5. **Kazotsky kick:** complete a `/bot skill woodcut` task successfully. On completion the bot kazotsky-kicks. Cancelled / failed skills do NOT trigger.
6. **Random dance:** leave a bot fully idle in a calm area for 10+ minutes. Within average ~10 min of eligible idle time, bot performs a backflip / twerk / club_penguin_dance / roblox_potion_dance. Hostile spawn or commander activity gates it off.

## Emotecraft soft-dependency bridge: bots play body-language emotes (2026-05-06, 1.1.79)

User asked for Emotecraft (kosmx) integration so bots play character-animation emotes alongside voice-line reactions. Soft dependency: when Emotecraft is installed, bots emote; when it isn't, every emote call is a silent no-op.

### Bridge design

[EmotecraftBridge.java](src/main/java/net/wcfcarolina13/GameAI/services/EmotecraftBridge.java) (new, ~140 lines) is a reflection-based wrapper. On `SERVER_STARTED`, it checks `FabricLoader.isModLoaded("emotecraft")` and caches two `Method` handles:

- `UniversalEmoteSerializer.getEmote(UUID) -> Animation`
- `ServerEmoteAPI.playEmote(UUID, Animation, boolean force)`

A volatile `AVAILABLE` flag gates every public call. Reflection chosen over `compileOnly` so Frens.jar stays self-contained — no Emotecraft / playeranimcore / NoteBlockLib in the build classpath.

### Emote registry

11 built-in Emotecraft 3.2.0 emotes hardcoded as an `EmoteId` enum with stable JSON-derived UUIDs:

`waving`, `point`, `palm`, `clap`, `crying`, `here`, `kazotsky_kick`, `backflip`, `twerk`, `club_penguin_dance`, `roblox_potion_dance`. UUIDs are stable across Emotecraft installs (extracted from each emote's JSON `uuid` field).

### Cooldown

Per-bot per-emote, default 30 seconds. Voice-line triggers already gate at 60+ s; the emote cooldown is a safety net. The `force` boolean variant of `playEmote` bypasses the cooldown — used by the `/bot debug emote` test command.

### Wired triggers

| Emote | Trigger |
|---|---|
| `palm` | After [dig_down_warning](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java#L1900) fires (1.1.76 third-party path) — bot facepalms when commander digs straight down |
| `point` | Paired with every entity-spotted voice line (1.1.75 LOS-gated): enderman, sniffer, piglin brute / hoglin / zombified piglin, glow squid, squid, dolphin (in-boat + sighted), fox+ocelot+chicken combo, elder guardian, guardian charging, guardian proximity, panda variants (brown / aggressive / worried / lazy), vex, trader, llama, cute animals |
| `waving` | [`/bot follow` ack](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java#L1978) + bot rejoin after disconnect/death (in [BotPersistenceService.onBotJoin](src/main/java/net/wcfcarolina13/GameAI/services/BotPersistenceService.java#L186) post-restore) |
| `crying` | Real-player death within 32 blocks LOS, OR tamed-pet (`TameableEntity` with owner) death within 16 blocks LOS — every nearby registered bot cries |

The unwired emotes (`clap`, `here`, `kazotsky_kick`, `backflip`, `twerk`, `club_penguin_dance`, `roblox_potion_dance`) ship dormant — user is testing them via the manual command and will pick mappings.

### Manual test command

`/bot debug emote list` — print the 11 emote slugs.
`/bot debug emote <name>` — force-play on every registered bot in the server.
`/bot debug emote <name> <bot>` — force-play on a specific bot.

Force-play bypasses cooldown so you can chain test invocations.

### Files touched

- New: [EmotecraftBridge.java](src/main/java/net/wcfcarolina13/GameAI/services/EmotecraftBridge.java) (~140 lines).
- [Frens.java](src/main/java/net/wcfcarolina13/Frens.java) — `initialize()` on SERVER_STARTED, `reset()` on SERVER_STOPPING, new private helper `triggerNearbyBotCryingForDeath` invoked from the existing `AFTER_DEATH` handler.
- [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) — ~18 trigger sites get `EmotecraftBridge.playEmote(...)` after their successful `tryTrigger` call. `playFollowAck` adds the wave.
- [BotPersistenceService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotPersistenceService.java) — wave on join-restore-complete.
- [Commands/modCommandRegistry.java](src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java) — `/bot debug emote` subcommand + `executeEmote` helper.

### Verification

1. Stand near the bot in dim conditions, dig the block under your feet → bot facepalms (palm) and says "Never dig straight down!".
2. Spawn entities the bot can see (enderman, dolphin, panda, etc.) → bot points and speaks the line.
3. `/bot follow` → bot waves and says the follow ack.
4. Kill yourself near a bot → bot cries.
5. `/bot debug emote list` → prints all 11 emote slugs. `/bot debug emote backflip` → all bots backflip.
6. Remove Emotecraft mod, re-launch → log shows "Emotecraft not detected; bot emote bridge disabled". All Frens features still work; emote calls no-op.

## BotTorchHoldService: hold torches in dim areas while idle/following (2026-05-06, 1.1.78)

User requested an atmospheric service: when the bot is idle or following the commander through a dark area, put a torch in the active hand. Must yield to any other system that needs the slot — skills, combat, eating, foreign hand-swaps.

### What it does

[BotTorchHoldService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotTorchHoldService.java) (~210 lines, new) runs on `END_SERVER_TICK` (5-tick throttle), iterates `BotRegistry.getPlayers(server)`, and for each bot evaluates a "should hold torch" predicate. On positive transition: save current selected slot, swap to the torch slot. On negative transition: restore the saved slot. Pure hotbar selection — no inventory mutation beyond optional torch promotion (below).

### Hold conditions (all must hold)

- Mode is `IDLE` or `FOLLOW` (skills, guard, patrol, stay all skip).
- `!TaskService.hasActiveTask(uuid)` — no skill running.
- `!bot.isUsingItem()` (eating, drawing bow, blocking) and `!hasVehicle()` and `!isSleeping()`.
- Block/sky-light at the bot's blockpos `≤ 7` (vanilla mob-spawn threshold; "this is the kind of dim where torches matter").
- **No hostile within 16 blocks AND line-of-sight** — visible threat → bot keeps weapon out.
- **No hostile within 8 blocks regardless of LOS** — proxy for footstep/mob-sound audibility through walls; bot keeps weapon out.

LOS check reuses [EntityVisibilityUtil.canSee](src/main/java/net/wcfcarolina13/GameAI/services/EntityVisibilityUtil.java) from 1.1.75. Hostile scan uses [BotThreatService.findHostilesAround](src/main/java/net/wcfcarolina13/GameAI/services/BotThreatService.java).

### Inventory promotion

If a torch stack exists only in main inventory (slots 9–35), the service promotes it to a hotbar slot:

1. **First pass:** empty hotbar slot.
2. **Second pass:** non-tool / non-food / non-weapon hotbar slot, excluding the currently-selected slot (avoids round-trip churn with whatever the bot just had in hand).
3. If neither, give up — keep tool slots intact.

Once promoted the torch stays in hotbar; we don't shuffle it back. Stable layout, no thrash. Supports `Items.TORCH`, `SOUL_TORCH`, `REDSTONE_TORCH`.

### Cooperation with other services

Foreign-swap detection: every tick we re-find the torch hotbar slot and check if the bot's `getSelectedSlot()` matches what we expected. If something else swapped (e.g., `BotActions.selectBestTool` for combat, `ensureAxeEquipped` from a skill), our state clears and we yield. Next tick we re-evaluate from scratch — usually `shouldHoldTorch` is now false (skill ticket open, hostile detected, etc.) so we don't fight.

The active-task gate is the strongest guard. Any skill that runs through `SkillManager.execute` opens a `TaskService` ticket; our predicate returns false the entire time.

### Files touched

- New: [BotTorchHoldService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotTorchHoldService.java) (~210 lines).
- [BotEventHandler.java](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L347-L350) (+4 lines: `getModePublic` accessor since the existing `getMode` is private).
- [Frens.java](src/main/java/net/wcfcarolina13/Frens.java) (+2 lines: `END_SERVER_TICK` registration + `reset()` in `SERVER_STOPPING`).

### Verification

1. **Idle dark cave:** stand commander next to a bot in IDLE mode in a Y=30 unlit cave (light ≤ 7) with torches in inventory. Bot should equip a torch within ~250 ms.
2. **Follow through a tunnel:** issue `/bot follow`. Walk through dark areas; bot's hand has a torch. Step into a lit room (light > 7); bot reverts to whatever was selected before.
3. **Combat suppression — visible:** spawn a zombie 12 blocks away with LOS in a dim room. Bot does NOT pull a torch (visible threat). When zombie dies / leaves LOS, torch returns.
4. **Combat suppression — audible through wall:** spawn a zombie 6 blocks away through a 1-block stone wall. Bot does NOT pull a torch (audible threat).
5. **Skill in progress:** issue `/bot skill woodcut` in a dim area. Bot does NOT pull a torch (active task ticket); when skill completes, torch returns.
6. **Eating:** give bot a steak; bot eats. During the use animation, no torch swap. After eating completes, torch returns.
7. **Foreign swap:** while torch is held, force a manual hand swap (or trigger another service that swaps tool). Our state clears; we don't fight; next quiet tick we re-evaluate.
8. **Inventory promotion:** put a torch stack in slot 27 (deep inventory) with all hotbar slots full of non-tool blocks. Bot in dark IDLE → torch promotes to a non-tool hotbar slot, bot equips it.

## Woodcut tooling: bootstrap wooden axe + terminate on no-axe instead of pickaxe fallback (2026-05-06, 1.1.77)

User reported the bot using a pickaxe to chop logs during woodcut, and noted the bot wasn't crafting a wooden axe even though it could. Two related bugs in the woodcut tool-provisioning chain.

### Bug 1: wooden axe craft gated on commander history

[ToolProvisionService.ensureAxe](src/main/java/net/wcfcarolina13/GameAI/services/ToolProvisionService.java#L148) gates the wooden axe branch on `(canCraft || allowWoodenFallback) && hasPlanksOrLogs`. `canCraftAxe(historyOwner)` returns true only if the commander has crafted an axe before — wooden, stone, iron, or diamond. The 3-arg `ensureAxe` overload (used by [WoodcutSkill.prepareWoodcutTooling](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java#L4836)) hardcoded `allowWoodenFallback=false`. So a fresh commander who's never crafted an axe before, OR a bot working autonomously without a tracked history, would skip the wooden fallback entirely — even with planks/logs in inventory.

The wooden axe is the bootstrap craft; gating it on history makes no sense for the woodcut start path. Now [prepareWoodcutTooling](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java#L4836) and [ensureAxeOrRetrieve](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java#L5350) both call the 4-arg overload with `allowWoodenFallback=true`. `ensureAxe` still tries stone → iron → diamond first when materials are present (preserves existing behavior of preferring better tiers); wooden is the always-available fallback.

### Bug 2: pickaxe used to chop logs

After the existing chain failed (no axe, no chest retrieval, no craft due to Bug 1), `prepareWoodcutTooling` called `selectHandsOrHarmlessItem(bot)` which has a "last resort: equip slot 0" path. If slot 0 was a pickaxe — common on a survival bot — the woodcut loop proceeded to mine logs with that pickaxe.

`prepareWoodcutTooling` now returns `boolean` instead of `void`. On failure to obtain an axe, the caller at [WoodcutSkill.java:699](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java#L699) returns `SkillExecutionResult.failure("I have no axe and can't make or find one. Get me an axe (or planks + sticks) and try again.")`. No more pickaxe-on-logs.

`ensureAxeOrRetrieve` (the mid-task replenishment hook called from `fellTree` inner loops) now also tries crafting with wooden fallback before falling through to chest retrieval. Mid-task axe breakage gets a clean replacement when materials are around. If even that fails the existing call sites just keep the bot's currently-equipped tool — for now that's acceptable since a still-running woodcut had a working axe at start; the per-block `selectAdaptiveToolOrHands` for logs will fall to hands rather than pickaxe. (Stricter mid-task termination is a follow-up if the issue resurfaces.)

### Files touched

- [WoodcutSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java) — `prepareWoodcutTooling` return type + 4-arg ensureAxe call + caller termination at L699; `ensureAxeOrRetrieve` adds a craft attempt before chest retrieval.

### Verification

1. **Bot with planks but no axes (no commander history):** spawn a bot, give it planks + sticks, no axes anywhere, no craft history. Issue `/bot skill woodcut`. Expect: bot crafts a wooden axe at the crafting table, then proceeds with woodcut.
2. **Bot with no materials and no axes:** clear all axes/planks/logs from inventory + nearby chests. Issue `/bot skill woodcut`. Expect: skill terminates immediately with the failure message; no chopping starts.
3. **Mid-task axe breakage:** start woodcut with a near-broken iron axe. When it breaks, the bot should try to craft a wooden axe from collected logs (woodcut by then has yielded plenty). It should NOT switch to pickaxe.
4. **Sanity: existing happy path:** bot with a fresh axe in inventory still woodcuts as before.

## Dig-straight-down warning fires on the wrong actor (2026-05-06, 1.1.76)

User reported the bot saying `"Never dig straight down! Are you new here?"` while it was woodcutting. The line is admonitory — clearly meant as a companion warning to a player making the noob mistake — but [CompanionContextReactionService.onBotBlockBreak:1856](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java#L1856) was firing it on bot self-actions: when the bot's `WoodcutSkill` mines a log directly under its feet during column descent or stump clearing, the hook saw `pos == feet.down()` and triggered the warning. The bot was scolding itself for normal woodcut operations.

Two parts to the fix:

1. **Skip dig-down on bot self-action.** The hook still fires for bots, but `tree_punch_first` ("Time to punch some trees", "This tree owes me money") and `dirt_dig` ("Diggy diggy hole") stay — those read as self-narration and fit a bot chopping/digging. The dig-down warning is excluded from the bot-self path.
2. **Add the third-party path.** When a real player digs straight down (block broken at `feet.down()`), the nearest registered bot within 16 blocks **with line-of-sight** to the player reacts with the warning. Reuses [EntityVisibilityUtil.canSee](src/main/java/net/wcfcarolina13/GameAI/services/EntityVisibilityUtil.java) from 1.1.75 — bots don't warn through walls. New private helper `findNearestVisibleBot(player, world, maxDistance)` iterates `BotRegistry.getPlayers(server)`, filters by world + LOS, returns the closest.

The hook signature stays as `onBotBlockBreak(ServerPlayerEntity player, ...)` — name now slightly misleading (it handles both bot and non-bot breakers) but renaming would churn the registration site at [Frens.java:464](src/main/java/net/wcfcarolina13/Frens.java#L464) for no functional gain. Updated the docstring instead.

**Why the warning was previously dead code from the player side:** the existing logic only proceeded if `isRegisteredBot(player) == true`. Real players' breaks short-circuited at the first guard. So before this change, the only way the line could fire was via bot self-action, which is exactly the bug the user reported. Now the line fires in its intended context.

**Verification:**

1. **Bot woodcut, no false warning:** issue `/bot skill woodcut` near a tree. During column descent / stump clearing, the bot will mine logs at `feet.down()`. Expect: no `dig_down_warning` line during the task. Tree-punch + dirt-dig narration may still fire as before.
2. **Player dig-down, bot reacts:** stand near a bot in open terrain. Dig the block under your feet (e.g., punch dirt below). Expect: within ~3 attempts (35% roll, 60s cooldown), the bot says "Never dig straight down! Are you new here?".
3. **Player dig-down behind a wall, no warning:** stand 8 blocks from the bot with a 2-block-thick wall between you. Dig down. Expect: no warning (LOS check from 1.1.75 prevents the bot from "seeing" your action).
4. **Multiple bots:** with two bots near you, only one (the closest with LOS) speaks the warning per trigger.

## Voice-line LOS gating: stop bots commenting on entities through walls (2026-05-06, 1.1.75)

Followup to the 1.1.74 IdleSweep autopsy. The user reported Jake saying `enderman_spotted_dont_look` when no enderman was visible from where they were standing. The trigger at [CompanionContextReactionService.tryEndermanSpotted](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java#L1438) used `world.getEntitiesByClass` with a 33×17×33 detection box and an `isEntityFacing` forward-cone gate, but **no line-of-sight check**. An enderman in an adjacent dark cave or behind a wall within the box would still trigger the line — the bot was technically "facing the direction" of the enderman through solid stone.

**Audit scope:** 30 entity-scan call sites across 8 services. Classification:

- **20 LOS_REQUIRED** — voice-line / "I see X" reactions where the bot is commenting on something it should actually see. Endermen, sniffers, nether mobs (brute / hoglin / zombified piglin), squids, glow squids, dolphins (in-boat + standalone), pandas, vex, traders + llamas, fox+ocelot+chicken combo, guardian + elder guardian, cute animals, pig-staring, villager-noise. 19 in `CompanionContextReactionService`, 1 in `VillageProximityReactionService`.
- **10 LOS_NOT_NEEDED** — threat / hazard / mechanical detection that correctly should NOT require LOS (creeper around the corner is still a real threat, warden audio aura penetrates walls, primed TNT, hostile-radius scans, animal-defense watch list, recruitment village counts).

**Fix:** new shared utility [EntityVisibilityUtil.canSee(bot, target)](src/main/java/net/wcfcarolina13/GameAI/services/EntityVisibilityUtil.java) — strict eye-to-eye raycast using `RaycastContext.ShapeType.COLLIDER + FluidHandling.NONE`, ignoring the bot itself. Mirrors vanilla `LivingEntity.canSee` semantics. **No tolerance** — that's deliberate. The 2-block "near a surface" tolerance used for item pickup in `BotEventHandler.findNearestDrop` is for a different purpose (items physically resting on a block surface look "behind" a block to a naive ray); here we want voice lines to fire only on genuine visual sightings.

**Where it's applied (20 call sites):** every LOS_REQUIRED voice-line trigger now adds `.stream().anyMatch(e -> EntityVisibilityUtil.canSee(bot, e))` (or `.filter` for List flows) to its `getEntitiesByClass` / `getOtherEntities` chain. Mob-class predicate stays unchanged; the LOS check is layered on top so the intent is explicit at every site.

**Where it's deliberately NOT applied:** [BotThreatService.findHostilesAround](src/main/java/net/wcfcarolina13/GameAI/services/BotThreatService.java), [BotAnimalDefenseService.buildWatchList](src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java), `hasPrimedTntNear`, `tryHostileDetection` (the in-combat boolean check at [CompanionContextReactionService.java:628](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java#L628)), the warden / creeper threat checks in `isUnderThreat`, chicken-jockey hazard scan, recruitment village + golem counts, mob-crusher anti-cruelty detection. These services correctly need to know about threats and mechanical state regardless of occlusion.

**Pig-staring (line 1283):** also gated. The voice line is literally about noticing the pig staring at you, so observation is required. Now combined with the existing `isEntityFacing(pig, bot)` forward-cone check, both conditions must hold.

**Files touched:** new [EntityVisibilityUtil.java](src/main/java/net/wcfcarolina13/GameAI/services/EntityVisibilityUtil.java) (~50 lines including docstring), [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) (~25 line changes across 19 trigger methods), [VillageProximityReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/VillageProximityReactionService.java) (renamed `hasNearbyVillagers` → `hasNearbyVisibleVillagers`, +1 parameter, +1 line for the LOS filter, single caller updated).

**Verification:**

1. **Enderman behind a wall:** spawn an enderman in a dark cave 12 blocks horizontally from the bot, with solid stone between them. Stand near the bot for several minutes (long enough to clear cooldown). Expect: no `enderman_spotted_dont_look` line.
2. **Enderman visible:** clear line of sight to the same enderman (e.g., open the cave wall). Within the per-tick roll window, expect the line to fire normally.
3. **Threat persists through walls:** spawn a creeper around a corner from the bot. The bot's combat state should still register the creeper as a threat (combat callout systems still fire).
4. **Per-trigger:** each of the 20 LOS_REQUIRED triggers can be tested by burying the relevant mob behind a 2-block-thick wall vs. having clear LOS. The line should fire only in the visible case.

## IdleSweep: no-progress timeout + drop blacklist + AutoFace suppression (2026-05-06, 1.1.74)

Diagnosed from a Prism log where Jake spent 67 seconds hopping in place while looking up at an enderman. Two distinct bugs converged on the same scene:

**Bug 1: IdleSweep had no give-up.** [tickOpportunisticIdleSweep](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3018) committed to a target drop and called `followWaypointStep` every tick until arrival, commander move, or `/bot stop`. There was no "we haven't gotten any closer for N seconds, abandon" check. Jake locked onto a drop at `(319, 69, 1306)`, reached `(316, 69, 1304)`, hit a 3-block lateral obstacle (probably a fence post or head-clearance under tree cover), and kept auto-jumping in place forever.

**Bug 2: AutoFaceEntity was not suppressed during the sweep.** Per CLAUDE.md, `AutoFaceEntity.setBotExecutingTask(true)` is set by `SkillManager.execute` only. IdleSweep runs outside the skill system, so the idle head-rotation tracker was free to swing Jake's pitch toward the most interesting nearby entity — in this case a real enderman within the 33×17×33 detection box (no LOS check) that the user couldn't see from their angle. Confirmed via the `frens:bot.line.enderman_spotted_dont_look` voiced dialogue at `(316.44, 70.00, 1304.47)` immediately after the hopping started.

**Fix 1 — No-progress timeout + per-bot blacklist:**

Two new state maps in [FollowStateService.java:117-126](src/main/java/net/wcfcarolina13/GameAI/services/FollowStateService.java#L117-L126):

- `IDLE_SWEEP_LAST_PROGRESS_TICK` — last tick the bot got measurably closer to its target.
- `IDLE_SWEEP_LAST_DISTANCE_SQ` — last recorded squared distance for progress comparison.
- `IDLE_SWEEP_TARGET_BLACKLIST` — `Map<UUID, Map<BlockPos, Long>>` per-bot of unreachable drop positions with cooldown-expiry ticks. Entries auto-prune on read past their expiry tick. Persists across `clearIdleSweep` so stop/restart of the sweep doesn't reset learned-unreachable knowledge.

In [tickOpportunisticIdleSweep](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3018), every tick we walk toward the committed target we now compare current `distSq` to the last recorded one. If `distSq` dropped by ≥ `IDLE_SWEEP_PROGRESS_DELTA_SQ` (= 0.25, ≈0.5 blocks closer), we update the progress tick + distance. If we go `IDLE_SWEEP_NO_PROGRESS_TIMEOUT_TICKS` (= 200 ticks / 10 s) without progress, we log an abandon line, blacklist the target's BlockPos for `IDLE_SWEEP_BLACKLIST_DURATION_TICKS` (= 1200 ticks / 60 s), clear sweep state, cancel any in-progress `DropSweepService` sweep, and return false. [findNearestDrop](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L7148) now filters out blacklisted positions, so subsequent sweeps skip the unreachable drop and pick the next nearest.

**Fix 2 — AutoFaceEntity suppression:**

`AutoFaceEntity.setBotExecutingTask(true)` is now called on IdleSweep activation and at every entry to the active branch; reset to `false` on every exit path that returns `false` (player moved, sweep complete, no-progress abandonment). Mirrors the existing `SkillManager` pattern. Bot's head now stays pointed at the waypoint it's walking toward instead of swinging at random nearby mobs.

**Architectural composition with NavHazardCache (1.1.73):** when Jake stalls at the lateral obstacle, the `applyMovementInput-reject` calls feed `NavHazardCache.recordRejection`. The streak gate (3 rejects / 40 ticks) promotes the wedge cell to a tracked hazard with `score > 0`. So even *before* the IdleSweep abandonment timeout fires, the cells around the obstacle accumulate hazard score, and the *next* sweep target's pathfinder routes around them. The IdleSweep blacklist handles the rare case where the drop itself is unreachable (no path to it at all); the hazard cache handles the more common case of "path exists but goes through bad cells."

**Files touched:** [BotEventHandler.java](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) (+~50 lines for progress tracking + blacklist helper + AutoFace toggles), [FollowStateService.java](src/main/java/net/wcfcarolina13/GameAI/services/FollowStateService.java) (+10 lines for new maps + clearIdleSweep update).

**Verification (manual, in-game):**

1. Drop an item somewhere reachable through a fence-corner chokepoint Jake's pathfinder can't easily navigate. Stand still until IdleSweep activates.
2. Watch Jake walk toward the drop, hit the obstacle, attempt for ~10 s. Expect `[IdleSweep] Jake abandoning unreachable drop at <pos> — no progress for 10s` in the log, then sweep state clears.
3. Drop another item nearby in a clearly reachable spot. Wait for the next sweep activation. Jake should pick the new drop (the original is blacklisted for 60 s) and ignore the previously-blocked one.
4. After 60 s, drop a fresh item at the originally-blocked spot. Jake should still avoid attempting (entry still blacklisted until tick TTL elapses).
5. Confirm head behavior: during the active sweep, Jake's pitch should track the waypoint, not random mobs. Bring an enderman near him during a sweep and watch his head — it should stay locked on the drop direction, not swing at the enderman.

## Per-world stuck-cell navigation hazard cache (2026-05-06, 1.1.73)

First step in the "let the bot get better at pathfinding over time" arc the user proposed after watching Jake oscillate on a stair-fence-door cluster (logs that drove 1.1.72). Item #2 in the four-part roadmap; items #3 (route corridor cache) and #4 (edge-cost tuner from observed traversal time) are deferred but the design accommodates them.

**What it does:** [NavHazardCache.java](src/main/java/net/wcfcarolina13/GameAI/services/navigation/NavHazardCache.java) (new, ~340 lines) records every `applyMovementInput` rejection at the cell where it happened, scoped per save × per dimension. After a streak of 3 rejections within 40 ticks at one cell, the cell is "promoted" with a `score` that grows on further rejections (+1.5 each, capped at 50.0), shrinks on successful traversals (−3.0 each), and decays linearly with wall-clock time (~0.05/sec). Both pathfinders ([PathFinder.java:151,184](src/main/java/net/wcfcarolina13/PathFinding/PathFinder.java#L151) and [BaritoneStylePathFinder.java:379](src/main/java/net/wcfcarolina13/PathFinding/BaritoneStylePathFinder.java#L379)) consult `NavHazardCache.penaltyFor(world, cell)` during cost expansion and add `min(score × 0.4, 12.0)` to the candidate's `gScore`. Saturated cells (`score=50`) cost ~12 extra blocks of detour — A* gladly routes around when alternatives exist, but won't refuse the cell when it's the only path home.

**Why these constants:** Streak gate (3 rejects in 2 s) suppresses single-bump noise; today's stair stall would have promoted in ~50 ms. Linear decay (vs RLAgent's exponential epsilon) gives a deterministic "this stale entry ages out in T seconds" guarantee — saturated cells fully clear in ~17 min idle. Pathfinder cap matches the existing per-step move costs (1.0–1.8 in `BaritoneStylePathFinder.expandNeighbors`) so penalties dominate routing decisions without making cells unreachable.

**Persistence:** JSON at `<configDir>/frens/nav_hazard_cache.json`, partitioned by `worldKey -> dimensionId -> "x,y,z"`. World key reuses `BotWorldStateService.currentWorldKey(server)` (level name + save-root hash). Dimension key is `world.getRegistryKey().getValue().toString()`. Loaded synchronously on `SERVER_STARTED`; pruned + flushed every 30 s (mirrors `BotPersistenceService.AUTOSAVE_INTERVAL_TICKS`) via a single-thread `ScheduledExecutorService` so disk I/O never blocks the server thread; final sync flush on `SERVER_STOPPING` before bot save.

**Success signal:** Hooked into `NavHazardCache.onServerTick` via per-bot foot-cell tracking. When a bot's `BlockPos.asLong()` changes between ticks, the previous cell was definitively traversed — look it up, decrement score, increment `successes`. Chosen over hooking the `applyMovementInput` accept branch (too noisy, fires every moving tick) or `MovementService.walkTo` completion (path-level, misses interrupted-but-progressed paths).

**Recording site:** [BotActions.java:272-275](src/main/java/net/wcfcarolina13/GameAI/BotActions.java#L272-L275) — between the `canAcceptMovementImpulse` gate and the throttled `diagnoseOccupancyRejection`. Recorder is *unthrottled* (true counts feed the streak gate); diagnostic stays at one log/0.5 s.

**Inspection:** New `/bot debug nav-hazard` command lists the top-10 highest-scoring cells in the player's current world+dimension and prints the cache file path. Useful for confirming a stuck cell got tracked, or verifying decay returned a score to zero after the obstacle was removed.

**Architectural room for #4:** The deferred edge-cost tuner records *between-cell* timings, not single-cell hazard. Same JSON top-level partitioning, same flush executor, same per-tick handler can hold both. The single penalty call site in each pathfinder becomes the home for all additive cost adjustments — the tuner just adds a second term `cellPenalty + edgeMultiplier`. No persistence-layer or pathfinder rework when #4 lands.

**Files touched:** New: `NavHazardCache.java`. Modified: `BotActions.java` (+3 lines), `BaritoneStylePathFinder.java` (+1 line), `PathFinder.java` (+4 lines, two sites), `Frens.java` (+5 lines: load, restartExecutors, tick register, flushSync, shutdownExecutors), `Commands/modCommandRegistry.java` (+24 lines for the debug subcommand).

**Verification (manual, in-game):**
1. Wedge a bot at a known-bad cell (post-1.1.72 stairs are now traversable, so this needs a fresh trap such as an L-shaped fence corner). Issue `/bot follow`; expect rejection log spam for 2-3 s, then the next repath should route around the cell.
2. `/bot debug nav-hazard` should list the cell with `rejects ≥ 3`, `score > 0`.
3. Inspect `~/Library/Application Support/PrismLauncher/instances/1.21.11/.minecraft/config/frens/nav_hazard_cache.json` (it appears within 30 s).
4. Break the obstructing block; route the bot through the cleared spot a few times. Score drops by 3.0 per traversal; entry vanishes from the JSON within ~10 min idle once score < 0.1.
5. Restart server and confirm prior entries reload (minus offline decay — `lastTick` is server-tick-relative, resets at boot).

## Stair-feet impulse gate: stop rejecting velocity onto stairs/slabs/snow (2026-05-06, 1.1.72)

Diagnosed from a Prism log where Jake spent the better part of two minutes thrashing back and forth on a four-step cobblestone staircase flanked by oak fences and a wood door at `(266, 66, 1285)`. Every horizontal impulse onto a stair-feet cell was rejected with `feet-not-passable`:

```
applyMovementInput-reject bot=Jake from=(271.98, 61.50, 1285.52) to=(271.80, 61.50, 1285.52)
  reason=feet-not-passable feet=271,61,1285=cobblestone_stairs head=271,62,1285=air
```

The same pattern repeated against four ascending stair cells, then drifted into the rail-fence cells `(266,66,1284)` and `(265,66,1283)`, then bashed against the door. `Door debug: stuck near door, closing anyway after 18 attempts` was the visible outcome.

**Root cause:** [BotActions.applyMovementInput:272](src/main/java/net/wcfcarolina13/GameAI/BotActions.java#L272) gated impulse application through `canOccupyPosition` → `hasMovementClearance` → `isPassableForMovement`. `WalkablePartialBlocks.isPathable` deliberately excludes stairs/slabs/snow ([docstring](src/main/java/net/wcfcarolina13/GameAI/services/WalkablePartialBlocks.java#L64)) and punts to "the path planner's step-up logic." But `applyMovementInput` has no step-up logic — it only adds horizontal velocity for vanilla physics to consume. Vanilla's auto-step (`stepHeight=0.6`) handles stair traversal natively when there's velocity to consume; our pre-gate kept the bot from ever accumulating any. Bot's velocity stayed zero, vanilla's auto-step never ran, bot oscillated.

**Fix:** [BotActions.java:1810-1875](src/main/java/net/wcfcarolina13/GameAI/BotActions.java#L1810-L1875)

- New `isFeetPassableForMovement` helper: passable OR `WalkablePartialBlocks.isStandable` (which already includes stairs/slabs/snow precisely because the bot stands on top of those).
- New `canAcceptMovementImpulse` pre-gate: feet uses the permissive helper, head uses the strict one, and the strict box-clear is skipped — vanilla physics is the actual collision authority once velocity is added.
- `applyMovementInput` swaps to `canAcceptMovementImpulse`. `moveRelative` (teleport-style mover) keeps the strict `canOccupyPosition` since it bypasses physics with `refreshPositionAndAngles`.
- `diagnoseOccupancyRejection`'s `feetOk` now uses the new helper so the diagnostic's reported reason matches what actually rejects the move.

Pathfinders (`PathFinder`, `BaritoneStylePathFinder`, `PathTracer`) were not touched — they still call `WalkablePartialBlocks.isPathable` and run their own step-up logic, which is correct for them.

**Why the rail-fence rejections appeared in the same log:** symptom, not cause. Once the bot couldn't traverse the stairs forward, repeated impulse attempts drifted it laterally into the stair's flanking fences. With the impulse gate fixed, the bot should advance through the staircase before the fence collisions become an issue.

## Named-hostile pacifism: close the Phase 3 mode-guard gap (2026-05-06, 1.1.71)

Closes the Phase 3 caveat that's been a `// TODO` comment in [BotFleeService.fleeFromEntity:643-648](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java#L643-L648) since 1.1.55. Walking through the named-hostile pacifism test plan against the current implementation surfaced this as the one real blocker for Step 2 of the test plan ("Let Bob hit the bot. Bot should flee.").

**The gap:** [BotFleeService.tickFlee:420](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java#L420) returns false when `mode != Mode.IDLE`. The bot's `Mode` enum is `IDLE / FOLLOW / GUARD / PATROL / STAY / RETURNING_BASE / TRAVELING`. So if the bot is following the commander (FOLLOW), guarding a base (GUARD), or on patrol (PATROL) when a name-tagged hostile damages it, the damage hook in `Frens.java:932-944` calls `fleeFromEntity` which seeds `FleeState` and runs the initial `applyFleeMovement` impulse — but on every subsequent tick, `tickFlee` returns false because of the mode guard, so `continueFlee` never runs to update direction or apply movement. The bot moves once and stops.

**Fix:** new `forcedByDamage` boolean on `FleeState`. `fleeFromEntity` sets it true when `startFleeing` actually launches (i.e., flee direction was traversable). `tickFlee` checks the flag to bypass the mode guard:

```java
boolean damageOverride = modeBypass != null && modeBypass.forcedByDamage && modeBypass.isFleeing;
if (mode != BotEventHandler.Mode.IDLE && !damageOverride) return false;
```

`stopFleeing` clears the flag so the next non-damage flee starts clean.

This makes the spec intent ("if the named mob attacks the bot, flee instead of fighting back") work regardless of bot mode — flee from named-hostile damage now overrides the bot's current orders.

**Other findings from the test-plan walkthrough** (left as-is):

- **Wall-interpose during flee is not implemented.** The spec wording "prefer putting a wall between bot and attacker" was aspirational. Current `computeTraversableFleeDirection` picks a direction with the most clear blocks (5 candidate angles); doesn't place a block between bot and attacker. Refinement, not a blocker.
- **Step 1b "overhead warn still fires for named hostiles" is conditional.** `BotAnimalDefenseService.maybeOverheadWarn` only fires when the named mob is targeting a defended entity (commander pet, etc.), not generically. To fully see this in-game, set up a defended animal nearby.
- **Steps 1, 3, 4, 5, 6 verified correct in code** — `engageHostiles` filter at `BotEventHandler:3657` cleanly drops named hostiles from the engage list while leaving them visible in scans, and `BotAnimalDefenseService.passesVictimSanityGates` explicitly filters `HostileEntity` / `RaiderEntity` / etc. so a named zombie can't accidentally become "defended" (the named-hostile loophole is closed by design).

Built clean on `./gradlew build -x test`. Deploying to all three Prism instances.

## Drop-sweep cobblestone loop: per-bot TTL self-drop suppression (2026-05-06, 1.1.70)

Closes the long-standing 2026-03-28 backlog item. The bug: when a bot's inventory fills up during patrol/idle, [DropSweeper.ensureSpaceForDropSweep](src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java) tries bundle-pack → chest-store → drop-cheap-stack as a fallback chain. In environments with no reachable chest and no leather, the bot reaches the drop step and dumps 64x cobblestone "to free space." But it's still standing there, so the next sweep tick (~7s later) finds the freshly-dropped cobblestone, walks to it, picks it back up — full inventory again. Repeat forever.

There was an earlier partial fix at the call site ([DropSweeper.java:278](src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java#L278)) that gated the drop on `chestStoreSucceeded`, but it didn't catch the inter-call loop and didn't apply to the 3 other callers of `dropCheapStackForSpace` in `BotMutualAidService`. The user's framing was right: this is a problem with **anything the bot drops to make space**, not just cobblestone.

The fix moves the suppression into [CraftingHelper.dropCheapStackForSpace](src/main/java/net/wcfcarolina13/GameAI/services/CraftingHelper.java) itself so all 4 callers benefit:

1. The drop call now captures the spawned `ItemEntity`: `ItemEntity dropped = bot.dropItem(removed, false, false);`
2. New `registerSelfDrop(bot, dropped)` adds the entity UUID to a per-bot map with a 5-minute (`SELF_DROP_TTL_TICKS = 6000L`) expiry — slightly longer than vanilla's 6000-tick item-entity despawn, so the suppression outlives the dropped item naturally.
3. New public `isRecentlySelfDropped(bot, item)` query, with self-evicting reads (an expired entry is removed on lookup, and per-bot maps are removed when empty).
4. [DropSweeper.findClosestDrop](src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java#L200) gets a new stream filter that rejects items recently self-dropped by this same bot.

The earlier `chestStoreSucceeded` gate is kept as defense-in-depth — it prevents the drop from happening at all when no offload exists. The TTL system addresses the inter-call loop that the gate alone couldn't.

Why 5 min and not 30s: the original log showed sweeps every ~7s. A 30s TTL would lock out 4 sweeps then resume the loop. 5 min lines up with the vanilla ItemEntity despawn (6000 ticks), so by the time the suppression expires, either the item is gone naturally or the bot has moved on.

Built clean on `./gradlew build -x test`. Not deployed.

## Remove dead `openSpellsMenu` method + reframe `CompanionSpellsScreen` backlog (2026-05-06, 1.1.69)

Audited the 2026-04-20 backlog item that called for deleting `CompanionSpellsScreen.java` after the unified Spells tab cutover. Finding: the legacy screen is **not** dead. It's still reached via:

- The dedicated `KEY_OPEN_SPELLS` keybind path in [FrensClient.java:660 → 1578](src/main/java/net/wcfcarolina13/FrensClient.java#L660)
- The recruit-contact key fall-through after recruitment is complete
- The temporary `-` go-to-spells override (active when holding a spell trigger item like tome / horn / eye, or near an enchanting table)

`isEyeSpellOnCooldown` / `armEyeSpellCooldown` are also still consumed inside `FrensClient` itself (line 1570), not just by the legacy screen — so they can't be ripped out either. Deleting the screen would break the keybind UX.

What WAS dead and is now removed: `openSpellsMenu` in [BotPlayerInventoryScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java) — defined at the old line 6529 but never called anywhere (grep confirmed no invocations). It was a leftover from the pre-cutover wiring; `switchToSpellsTab` is the in-place replacement that the `✦` button uses now. Updated `switchToSpellsTab`'s javadoc to call out that the keybind path to the legacy screen is intentional, not orphan code.

Updated the backlog entry in `RALPH_TASK.md`: marked partial-complete with the dead-method removal noted, with a follow-up to either migrate the keybind to use the unified Spells tab (would let us actually delete the legacy screen) or accept the dual UX as intentional.

Built clean on `./gradlew build -x test`. Not deployed.

## Add shelves + containers to no-break list (2026-05-06, 1.1.68)

Closes a long-standing backlog item. `ProtectedStructureBlockHelper.isNeverBreakBlock` now rejects player-facing storage + workstation blocks via a new `isProtectedContainer` predicate, so bot stuck-escape / mine-escape / `breakFreeGeneric` paths can no longer accidentally destroy a chest and dump its contents while panicking.

Covered:

- Bookshelves: `BOOKSHELF`, `CHISELED_BOOKSHELF`
- Chest family: `CHEST`, `TRAPPED_CHEST`, `ENDER_CHEST`, `BARREL`
- All shulker box variants — matched via translation-key suffix `endsWith("shulker_box")` so the uncolored shulker box + all 16 dyed variants pass with one check
- Redstone-storage: `HOPPER`, `DISPENSER`, `DROPPER`
- Newer storage: `DECORATED_POT` (1.20+ single-slot), `CRAFTER` (1.21+ auto-crafter)
- Active workstations: `BREWING_STAND`, `FURNACE`, `BLAST_FURNACE`, `SMOKER` — these hold fuel/input/output stacks; breaking mid-cook would dump or destroy them

The new predicate is exposed publicly so other callers (e.g. mining safety paths, never-break guards) can specifically check "is this a player container?" without going through the broader `isNeverBreakBlock` umbrella. `BotRescueService`, `ReturnBaseStuckService`, `BotStuckService`, `MovementService` mine-paths, and `MiningTool` mineBlock guards all consult `isNeverBreakBlock` and inherit the new protection automatically.

Built clean on `./gradlew build -x test`. Not deployed.

## Walking-dogs: voiced session-start line (2026-05-06, 1.1.67)

Closes the first deferred follow-up from the walking-dogs hobby work in 1.1.65. New 2-line pool fires once per session start in [BotDogWalkingHobbyService](src/main/java/net/wcfcarolina13/GameAI/services/BotDogWalkingHobbyService.java) right after the wolf actually flips to standing and the session is recorded:

- `LINE_WALK_DOGS_GOOD_DOG` ("Who's a good dog?")
- `LINE_WALK_DOGS_WALKIES` ("Going for walkies.")

50/50 coin flip per fire. Audio plays via `BotDialoguePlayer.playSoundForBotDetailed`; the overhead-text companion comes from the standard SUBTITLE_MAP entry, same wiring as every other voiced bot line. Empty `sounds[]` arrays scaffolded for parallel TTS generation.

Dispatch is gated by the existing session-start path — the call is placed *after* `SESSIONS.put(...)`, so the line only fires when (a) the wolf was eligible, (b) `ensureNonFoodInMainHand` succeeded, (c) `BotActions.interactEntity` returned accepted, and (d) the post-interact `wolf.isSitting()` re-check confirmed the toggle worked. No risk of the line firing on a misfire.

Built clean on `./gradlew build -x test`. Not deployed.

## Walking-dogs: strip food + golden-* items from hand before wolf interact (2026-05-06, 1.1.66)

User concern: wolves' feedable item list keeps growing across Minecraft versions (rabbit stew, tropical fish, golden apples / carrots in current vanilla, plus a rumored golden-dandelion-style item in an upcoming snapshot). When the bot wants to walk a dog, having any of these in main hand causes the right-click to feed the wolf instead of toggling sit/stand — wasting valuable items and silently failing to start the session.

Fix in [BotDogWalkingHobbyService](src/main/java/net/wcfcarolina13/GameAI/services/BotDogWalkingHobbyService.java): new `ensureNonFoodInMainHand(bot)` helper that runs **before every wolf interact** (both the initial stand-up and the at-home sit-down). Strategy:

1. Inspect the currently-selected hotbar slot. If it's not risky, no-op.
2. Scan hotbar for a non-risky slot (empty counts as non-risky) and select it.
3. Otherwise scan the main inventory for a non-risky stack and swap it into the currently-selected hotbar slot.
4. If every slot in the entire inventory is risky, skip the interact this tick rather than risk a misfire.

"Risky" predicate (`isWolfFeedingRisk`):

- Has the `FOOD` data component → catches all current vanilla wolf-edible food (raw beef, mutton, rabbit stew, tropical fish, golden apples, etc.).
- OR registry id starts with `golden_` → defensive future-proofing against the rumored golden-dandelion / other upcoming `golden_*` items, even before they ship with a `FOOD` component.

The string-prefix check is broader than strictly necessary (catches `golden_ingot`, etc.) but the bot doesn't lose anything by holding a sword or stick when toggling a wolf — over-eager-by-a-little is the right side of safety here.

Built clean on `./gradlew build -x test`. Not deployed.

## Walking-dogs hobby (2026-05-06, 1.1.65)

New idle-companion hobby. When the bot walks past an unnamed sitting tamed wolf during idle time, it stands the wolf up and the wolf tags along until a 3–10 minute timer expires. If the bot is back near a registered base or its last-slept bed when the timer ends, there's a 50% chance it sits the wolf again.

**Architecture — passive companion-tracker, not a skill.** The hobby does NOT take a `TaskService` slot. It only fires when the bot's idle wandering brings it within direct interact reach (3 blocks) of an eligible wolf. The bot doesn't detour to find one — true to "the dog tags along."

**New service:** [BotDogWalkingHobbyService](src/main/java/net/wcfcarolina13/GameAI/services/BotDogWalkingHobbyService.java) tick handler runs every 20 ticks and per-bot does one of:

- **No active session** — only proceed if `TaskService.hasActiveTask(bot) == false`, bot has no vehicle, bot is not inside a registered base, and pickup-retry cooldown (30 s) has elapsed. If a `WolfEntity` within 4.5 blocks satisfies `isAlive() && isTamed() && isSitting() && !hasCustomName()`, face it via `LookController.faceEntity` and call `BotActions.interactEntity(bot, wolf, MAIN_HAND)`. This routes through the same vanilla `bot.interact()` path used elsewhere in the mod — no direct `setSitting()` mutation. If the wolf actually flipped to standing (it won't if the bot was holding wolf food and fed it instead), open a session with a random 3–10 min duration.
- **Active session** — read `wolf.isSitting()` each tick. If anyone else (commander / another bot / another player) sat the wolf, drop the session quietly. Also drop if the wolf is dead/removed, or if it's separated by more than 24 blocks. When the timer expires, check at-home (within 16 blocks of a registered base or `getLastSleep`) AND wolf within 3 blocks; on a 50% roll, interact again to sit it.

**Custom-named wolves are excluded entirely** — `name your wolf` to opt out. Inside a registered base the hobby doesn't try to pick up sitting wolves at all (the user's home wolves stay seated).

**Hobby gate:**

- `walk_dogs` appended to `BotPlayerInventoryScreenHandler.HOBBY_BIT_ORDER` (per the append-only rule documented in MEMORY.md so saved bots don't see the wrong hobbies disabled).
- New `Walk Dogs` button + tooltip in [ConfigureHobbiesScreen](src/main/java/net/wcfcarolina13/GraphicalUserInterface/ConfigureHobbiesScreen.java) — slots into the existing 16th cell of the 3-column grid; popup dimensions already had headroom.
- Default ON. Disabled mid-session drops the session immediately (wolf reverts to vanilla owner-follow / sit AI).
- Tick handler registered alongside `PetProximityReactionService` in [Frens.java](src/main/java/net/wcfcarolina13/Frens.java) END_SERVER_TICK chain.

**Edge cases handled:**

- Bot holding wolf food in main hand → wolf gets fed instead of toggled. Session not opened (we re-check `wolf.isSitting()` after the interact call).
- Wolf teleports far away (vanilla owner-teleport when distance > 12) → session drops on the separation check.
- Bot disabled hobby mid-walk → session drops; wolf falls back to vanilla AI.
- State garbage-collected each tick against the live registered-bot set so removed bots don't leak entries.

Verified all entity APIs (`WolfEntity.isSitting()`, `isTamed()`, `hasCustomName()`) and the `bot.interact(target, hand)` path in the existing `BotActions.interactEntity` helper before importing.

Built clean on `./gradlew build -x test`. Not deployed.

## Redstone-machine proximity dialogue — May 2026 backlog complete (2026-05-05, 1.1.64)

`tryRedstoneMachineNearby` in [CompanionContextReactionService](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java).

Detection: scan a 5×5×5 box (125 cells) around the bot, count redstone components (`REPEATER`, `COMPARATOR`, `OBSERVER`, `PISTON`, `STICKY_PISTON`, `DISPENSER`, `DROPPER`, `HOPPER`) and tally distinct block types. Threshold per spec: **≥ 4 components AND ≥ 2 distinct types**. Below that we're not confident enough it's a contraption (vs. a redstone door or single pressure-plate setup).

Skipped via `BotHomeService.findBaseNearPosition` when the bot is inside a registered base — the user's own base would trigger the line constantly otherwise.

2 weighted lines:

- `LINE_REDSTONE_MACHINE_TECH` ("Tech-o-no-lo-hee-ah") — COMMON
- `LINE_REDSTONE_MACHINE_HELL_AND_BACK` ("We literally went to hell and back to build this.") — UNCOMMON

20% roll, 90s cooldown.

**The May 2026 dialogue backlog is now fully wired.** All trigger pools from RALPH_TASK.md's "Dialogue / Voiced Lines (backlog 2026-05-04)" section are now implemented across 1.1.57 → 1.1.64:

- 1.1.57 — Nautilus untamed/tamed + mount-quality pool split
- 1.1.58 — Cat / sniffer / 3 Nether neighbours + walking-dogs hobby spec captured
- 1.1.59 — Squid / glow squid / dolphin / vex
- 1.1.60 — Fox+ocelot near chickens + 4 panda variants
- 1.1.61 — Cute-animal "can we keep it?" pool
- 1.1.62 — Guardian (proximity + laser-charging) + elder guardian
- 1.1.63 — Mob-crusher anti-cruelty pool
- 1.1.64 — Redstone-machine proximity pool

35 new dialogue lines, 13 new triggers, all scaffolded with empty `sounds[]` for parallel TTS generation. Verified all entity classes against 1.21.11 yarn mappings; all builds clean. Not deployed.

The walking-dogs hobby remains in the backlog under `### Hobbies (new ideas)` — design captured, implementation deferred to a future session per CLAUDE.md "one clearly bounded task at a time" rule.

## Mob-crusher anti-cruelty dialogue (2026-05-05, 1.1.63)

`tryMobCrusher` in [CompanionContextReactionService](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java).

Detection algorithm: scan an 8-block box for living passives of the curated set (`CowEntity`, `SheepEntity`, `PigEntity`, `ChickenEntity`, `VillagerEntity` — hostiles explicitly excluded per spec so skeleton/zombie grinders don't fire). Group by entity type + integer block-cell and check if any single bucket has ≥ 6 entities. The "stuffed in a 1×1 column" pattern is what distinguishes a crusher from a normal pen.

3 weighted lines:

- `LINE_MOB_CRUSHER_HUMANE` ("Totally humane.") — COMMON
- `LINE_MOB_CRUSHER_CRUELTY_FREE` ("100% cruelty free.") — COMMON
- `LINE_MOB_CRUSHER_NETHER_PLACE` ("There's a special place in the Nether for whoever built this.") — RARE

20% roll, 10-min cooldown — the line is editorial, not scan-frequent.

Verified `CowEntity`, `SheepEntity`, `PigEntity`, `VillagerEntity` class paths in 1.21.11 yarn mappings before importing.

Built clean on `./gradlew build -x test`. Not deployed.

## Guardian + elder guardian proximity dialogue (2026-05-05, 1.1.62)

`tryGuardianFamily` in [CompanionContextReactionService](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java). Single 16-block scan, three state branches in priority order:

1. **Elder guardian present** — wins outright (rarer, more emphatic encounter). 20% roll, 8-min cooldown. Two lines:
   - `LINE_ELDER_GUARDIAN_BOSS` ("That one's the boss. We should leave.")
   - `LINE_ELDER_GUARDIAN_FATIGUE` ("Mining Fatigue incoming, I just know it.")
2. **Regular guardian charging at us** — `guardian.hasBeamTarget()` AND beam target is bot or commander. 30% roll, **60s** cooldown so the bot can re-react during sustained combat. Two lines:
   - `LINE_GUARDIAN_GLOWING` ("Why is it glowing at me?!")
   - `LINE_GUARDIAN_BEAM_HURT` ("That beam is gonna hurt — move!")
3. **Regular guardian present, not charging at us** — fallback proximity pool. 10% roll, 5-min cooldown. Two lines:
   - `LINE_GUARDIAN_STARING_RIGHT` ("It's staring right at me.")
   - `LINE_GUARDIAN_DONT_LIKE` ("I don't like the way it's looking at us.")

`ElderGuardianEntity` extends `GuardianEntity`, so the regular-guardian scan filters out elder instances to keep the priority hierarchy clean. Commander resolved via the existing `findNearbyCommander` helper (24-block scan).

Verified `GuardianEntity.hasBeamTarget()` and `getBeamTarget()` accessors in 1.21.11 yarn mappings. The "charging" detection uses these public methods rather than reading the DataTracker directly — clean and stable across versions.

Built clean on `./gradlew build -x test`. Not deployed.

## Cute-animal "can we keep it?" pool (2026-05-05, 1.1.61)

`tryCuteAnimalNearby` in [CompanionContextReactionService](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java). Single 12-block scan over a multi-class predicate: `FoxEntity`, `OcelotEntity`, `AxolotlEntity`, `BeeEntity`, `RabbitEntity`, `TurtleEntity`, `PandaEntity`, `ParrotEntity` (untamed only — tamed parrots already trigger `LINE_PARROT_NEARBY_NICE_BIRD`). Sniffers excluded per spec since they have their own dedicated line.

4 weighted lines:

- `LINE_CUTE_ANIMAL_KEEP_IT` ("Can we keep it?") — UNCOMMON
- `LINE_CUTE_ANIMAL_LOOK_AT_IT` ("Look at it!") — COMMON
- `LINE_CUTE_ANIMAL_WANT_ONE` ("I want one of those.") — UNCOMMON
- `LINE_CUTE_ANIMAL_SO_CUTE` ("It's so cute.") — COMMON

5% roll, 8-min cooldown — flavor remark, not chatter. Dispatched AFTER `tryFoxOcelotNearChickens` and `tryPandaProximity` so those more-specific triggers win when applicable: foxes near chickens fire the chicken-specific line, pandas with BROWN/AGGRESSIVE/WORRIED/LAZY genes fire variant lines, and only NORMAL/PLAYFUL/WEAK pandas (no variant line) fall through to this pool.

Verified `AxolotlEntity`, `BeeEntity`, `RabbitEntity`, `ParrotEntity`, `TurtleEntity` class paths in 1.21.11 yarn mappings before importing.

Built clean on `./gradlew build -x test`. Not deployed.

## Fox/ocelot + chicken conjunction line + panda variant-keyed lines (2026-05-05, 1.1.60)

Continued the May 2026 dialogue backlog. 5 lines across 2 new triggers in [CompanionContextReactionService](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java).

**`tryFoxOcelotNearChickens`** — combined-condition scan within 12 blocks. Fires `LINE_FOX_OCELOT_NEAR_CHICKENS` ("Don't let it near the chickens.") only when a `FoxEntity` or `OcelotEntity` AND a `ChickenEntity` are both in range. 10% roll, 5-min cooldown. Single shared pool — both predator types trigger the same line per spec.

**`tryPandaProximity`** — variant-keyed via `PandaEntity.getMainGene()`. Walks all pandas in a 12-block scan once to determine which genes are present, then fires the highest-priority pool:

- `LINE_PANDA_BROWN` ("A brown panda. That's special.") — 20% roll, 10-min cooldown. Highest priority because brown is the rarest variant (Qinling subspecies analogue).
- `LINE_PANDA_AGGRESSIVE` ("That one looks angry. Give it space.") — 10% roll, 3-min cooldown. Combat-relevant.
- `LINE_PANDA_WORRIED` ("That panda looks stressed.") — 8% roll, 5-min cooldown.
- `LINE_PANDA_LAZY` ("Lying down on the job, eh?") — 6% roll, 5-min cooldown.

`NORMAL`, `PLAYFUL`, and `WEAK` variants don't have lines and are skipped — the spec only requested 4 variant lines. Each variant has its own cooldown so seeing a brown panda and then a worried panda later can fire both.

Verified `PandaEntity.getMainGene()` and the 7-member `Gene` enum (NORMAL/LAZY/WORRIED/PLAYFUL/BROWN/WEAK/AGGRESSIVE) in the 1.21.11 yarn mappings before committing — wiki MCP + mappings cross-check, no training-data assumptions.

Built clean on `./gradlew build -x test`. Not deployed.

## 4 aquatic + raid mob-proximity dialogue lines (2026-05-05, 1.1.59)

Continued the May 2026 dialogue backlog. 4 simple proximity lines, all in [CompanionContextReactionService](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java), all scaffolded with empty `sounds[]` for parallel TTS.

**`tryAquaticAmbient`** — single 12-block scan, three pools in priority order:

- `LINE_GLOW_SQUID_PRETTY` ("Pretty.") — `GlowSquidEntity`, 8% roll, 5-min cooldown. Highest priority because it's the rare standout.
- `LINE_SQUID_JUST_A` ("Just a squid.") — `SquidEntity`, 5% roll, 5-min cooldown. The squid scan filters out `GlowSquidEntity` subclass instances so the priority above isn't double-counted.
- `LINE_DOLPHIN_DID_YOU_SEE` ("Did you see that dolphin?") — `DolphinEntity`, 8% roll, 3-min cooldown. Uses the existing `isEntityFacing` forward-cone helper so it only fires when the bot is actually facing the dolphin — matches the "Did you SEE that dolphin?" framing.

The whole aquatic scan is gated on `!bot.hasVehicle()` so the existing `in_boat_dolphin_nearby` boat-escort line keeps the on-the-water flavor without competing with the new sighting line.

**`tryVexNearby`** — 12-block scan for `VexEntity`, 12% roll, 3-min cooldown. Higher roll than the ambient pools because vexes are combat-relevant (raids, woodland mansions) and the bot should react promptly. Single line: `LINE_VEX_GOBLINS_WINGS` ("Goblins with wings! Duck and cover!").

Verified `SquidEntity`, `GlowSquidEntity`, `VexEntity` class paths in the 1.21.11 yarn mappings before importing — `GlowSquidEntity` extends `SquidEntity` (passive package) which is why the priority filter is needed.

Built clean on `./gradlew build -x test`. Not deployed.

## 5 mob-proximity dialogue lines + walking-dogs hobby spec (2026-05-05, 1.1.58)

Continued the May 2026 dialogue backlog. 5 simple proximity-triggered lines, all scaffolded with empty `sounds[]` for parallel TTS.

**Tamed cat — `LINE_CAT_MEOW`** ("Meow."). Goes in [PetProximityReactionService](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java) with its own pool — split out of the broad-tamed scan per the backlog spec ("this one isn't a quality assessment"). 10-block scan, 5-min cooldown, 20% roll. `CatEntity` excluded from the broad-pet scan so it doesn't double-fire alongside `animal_well_behaved`.

**Sniffer — `LINE_SNIFFER_DINOSAUR`** ("Dinosaur."). [CompanionContextReactionService](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java) `trySnifferNearby`. 12-block, 5-min cooldown, 8% roll. Sniffers are rare so the cooldown is generous — keeps it a remark.

**Nether neighbours — three pools, one trigger.** `tryNetherNeighbourNearby` runs three sequential pool checks within a single 12-block scan, in priority order (rarer/weirder first):

- `LINE_PIGLIN_BRUTE_BIGGER` ("That one's bigger than the others!") — `PiglinBruteEntity`, 8% roll.
- `LINE_HOGLIN_BACON_SPREE` ("If they give us gravel again I'm going on a bacon spree.") — `HoglinEntity`, 6% roll.
- `LINE_ZOMBIFIED_PIGLIN_PORKCHOP` ("What's up, porkchop?") — `ZombifiedPiglinEntity`, 6% roll.

Each pool has its own 3-min cooldown so seeing all three Nether mobs in quick succession can fire all three lines. `tryNetherNeighbourNearby` returns true on the first successful trigger, so the priority order also serves as a "skip the rest of the scan this tick" gate when the priority mob fires.

Sound events registered in [BotDialogueSounds](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java) under the existing `=== MAY 2026 BACKLOG ===` section. Subtitles in [BotDialoguePlayer.SUBTITLE_MAP](src/main/java/net/wcfcarolina13/ChatUtils/BotDialoguePlayer.java).

Debug triggers added: `cat_meow`, `sniffer_nearby` and the per-pool nether keys are reachable through the standard `tryTrigger` cooldown registry.

Wiki MCP confirmed entity class paths in 1.21.11 mappings (`SnifferEntity`, `PiglinBruteEntity`, `HoglinEntity`, `ZombifiedPiglinEntity`, `CatEntity`) before importing — no training-data guessing.

**Walking-dogs hobby — design captured in [RALPH_TASK.md](RALPH_TASK.md)**, not implemented yet. Spec includes pickup conditions (unnamed tamed sitting wolf within 12 blocks), physical-interaction rule (bot must walk into reach + LoS and activate the sit/stand toggle the way a player would, NOT direct `setSitting()` mutation), composition with other hobbies (runs as a passive companion-tracker, not via `TaskService.beginSkill()`), random end-of-session sit gate (~50% roll when bot returns to home/last-bed), and external cancellation (any other agent ordering the wolf to sit ends the session smoothly). Identifies hooks to grep for at implementation time and lists open design questions for the next session.

Built clean on `./gradlew build -x test`. Not deployed — user is currently playing.

## Nautilus proximity dialogue + mount-quality pool split (2026-05-05, 1.1.57)

Picked up the May 2026 dialogue backlog. Two scoped changes in [PetProximityReactionService](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java):

**Nautilus lines (new in 1.21.11).** The wiki MCP confirmed Nautilus is a tameable, rideable ocean mob added in 1.21.11 — full spawn/taming/riding spec retrieved fresh, no training-data guessing. Two scaffolded lines with empty `sounds[]` so TTS generation can run in parallel:

- `LINE_NAUTILUS_OCEAN_NEVER` — "Never going near the ocean again." Fires near a wild `NautilusEntity` (untamed). 12-block scan, 10-min cooldown, 20% roll per 20-tick check.
- `LINE_NAUTILUS_RIDE` — "You can actually ride one of these?" Fires near a tamed `NautilusEntity`. Same scan/cooldown/roll. Tamed takes priority when both present.

The scan uses `NautilusEntity` (passive) explicitly rather than `AbstractNautilusEntity`, so the future zombie-nautilus variant (in `entity.mob`, separate trigger) won't accidentally fire either pool.

**`ANIMAL_NEARBY_LINES` pool split.** Per the backlog item: the old combined pool fired "That's a quality animal." every ~90s with one cat/parrot/horse in range, which felt constant. Split into:

- `ANIMAL_WELL_BEHAVED_LINES` — broad tamed-non-wolf-non-nautilus pool. Keeps the 90s cooldown and "I respect a well-behaved animal." line.
- `MOUNT_QUALITY_LINES` — only fires for tamed `AbstractHorseEntity` (covers horse/donkey/mule/llama/trader-llama) or any living `CamelEntity`. Camels have no vanilla tame system in 1.21.11 so any nearby living camel counts. 5-min cooldown so the line stays a remark, not background chatter.

Also excluded `AbstractNautilusEntity` from the broad scan so a tamed nautilus only triggers the dedicated nautilus line, not the broad-pet line on top of it.

Subtitles wired in [BotDialoguePlayer.SUBTITLE_MAP](src/main/java/net/wcfcarolina13/ChatUtils/BotDialoguePlayer.java). Sound events registered in [BotDialogueSounds](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java) under a new `// === MAY 2026 BACKLOG ===` section. `sounds.json` entries use empty `sounds[]` arrays following the same scaffold pattern as the April 2026 backlog commit (`33e81fe`). Debug triggers added: `nautilus_untamed`, `nautilus_tamed`, `mount_quality`.

Built clean on `./gradlew build -x test`. Not deployed — backlog items 12+ remain (pandas, foxes/ocelots, cats, zombified piglins, hoglins, piglin brutes, sniffers, vexes, guardians, elder guardians, squids, glow squids, dolphins, redstone-machine and mob-crusher detection) and the user is currently playing.

Also: `CLAUDE.md` "Game / API Knowledge" section now leads with the Minecraft Wiki MCP rather than `WebFetch`, citing the Nautilus example as proof that the MCP returns 1.21.11-current data the training set doesn't have. `RALPH_TASK.md` got a matching MCP-first reminder under "Ralph Instructions."

## Configure Hobbies screen: optimistic toggle feedback + per-hobby tooltips (2026-05-04)

User reported that toggling a hobby in the new menu only showed visible `[x]/[ ]` flip after closing and reopening the screen. Root cause: the screen sent the chat command and immediately rebuilt buttons, but the server's `botStats[25]` bitmask resync arrives a tick or two later — so the rebuilt buttons read the OLD authoritative state.

Fix in [ConfigureHobbiesScreen](src/main/java/net/wcfcarolina13/GraphicalUserInterface/ConfigureHobbiesScreen.java): added a `Map<String, Boolean> localOverrides` that flips immediately on click. Render reads from override-first, falling back to `handler.isHobbyAllowed(name)`. A `reconcileOverrides()` pass on each `render()` drops any override whose value already matches the handler's authoritative state, so once the server resyncs we transparently return to handler-source reads with no visible flicker.

Also added `Tooltip.of(...)` per hobby button — short factual descriptions, with a red §c warning on Mine Stone calling out the surface-mining bug and on Wander explaining it's the default fallback. Tooltips use the existing pattern from CompanionSpellsScreen.

## Idle hobbies: per-hobby on/off menu so the user can pick which hobbies a bot is allowed to do (2026-05-03)

The master Idle Hobbies switch was all-or-nothing — when a hobby misbehaves (e.g. mining destroying surface terrain in [the latest.log autopsy](src/main/java/net/wcfcarolina13/GameAI/services/BotIdleHobbiesService.java#L1345)), the user had to disable everything to stop it. Now there's per-hobby control.

**Storage** ([BotHomeService](src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java)): added `Map<String, Map<String, Boolean>> hobbyDisabledByBot` to per-world JSON. Stored as a "disabled" set so the default (everything enabled) requires no migration on existing worlds. New API: `setHobbyEnabled(bot, name, enabled)`, `isHobbyEnabled(bot, name)`, `getDisabledHobbies(bot)`.

**Selection gate** ([BotIdleHobbiesService](src/main/java/net/wcfcarolina13/GameAI/services/BotIdleHobbiesService.java)): `pickHobby` filters the weighted pool with `weighted.removeIf(name -> !hobbyAllowed(bot, name))` after the pool is built. `pickFallbackHobby` ANDs each `canX` flag with `hobbyAllowed(bot, "X")` so the sequential preference cascade naturally skips disabled hobbies. The unconditional final `wander` fallback is also gated; if disabled, returns null and the bot just sits idle that tick.

**Command** (new `/bot hobby <name> on|off [target]`): registered via `BotHomeCommands.buildHobby()`, executor `executeHobbySetTargets` in [modCommandRegistry](src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java). Accepts any hobby name string — no whitelist, so future hobbies don't need a registry edit.

**UI** — new sub-screen [ConfigureHobbiesScreen](src/main/java/net/wcfcarolina13/GraphicalUserInterface/ConfigureHobbiesScreen.java), reachable from a new "Configure Hobbies..." entry sitting under the master "Idle Hobbies" entry in [BotPlayerInventoryScreen](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java). 3×5 grid of toggle buttons showing `[x] Hunt` / `[ ] Hunt` style state, plus Enable All / Disable All / Done at the bottom. Each toggle sends a chat command (same path as the master toggle), no new payload class.

**State sync to the client**: `BotPlayerInventoryScreenHandler` got a new `botStats` slot 25 holding a 15-bit bitmask of disabled hobbies. The order is fixed by `BotPlayerInventoryScreenHandler.HOBBY_BIT_ORDER` — append-only, never reorder, or saved bots will see the wrong hobbies disabled. Client reads via `handler.isHobbyAllowed(name)`.

After the toggle, the screen rebuilds itself; the next server tick refreshes the bitmask, so the checkmark flips on its own. Doesn't fix the underlying mining-on-surface bug from yesterday — that still needs the `shouldSuppressMiningHobby` surface-suppression rule — but the user can now uncheck "Mine Stone" as a workaround.

## Mount sync: never auto-mount llamas when commander is on a non-llama horse-like (2026-05-02)

Riding a horse or camel and walking past a wild/tame llama would cause the bot to drop pursuit and hop onto the llama, because all of HORSE/DONKEY/MULE/CAMEL/LLAMA/TRADER_LLAMA share `RideCategory.HORSE_LIKE` and the auto-mount loop picked the nearest match. Llamas can't be steered with a saddle and are slow, so this was almost always the wrong call.

Fix in [RideSyncService](src/main/java/net/wcfcarolina13/GameAI/services/RideSyncService.java): added `isLlamaType()` and `shouldExcludeLlamaCandidates()`. When the commander's vehicle is HORSE_LIKE but not itself a llama, llamas are filtered out of both the candidate scan ([findCandidateVehicles](src/main/java/net/wcfcarolina13/GameAI/services/RideSyncService.java#L4025)) and the persisted-mount resolution ([resolvePreferredMount](src/main/java/net/wcfcarolina13/GameAI/services/RideSyncService.java#L1694)). With no llama-allowed mount available, the bot falls through to on-foot pursuit. If the commander is themselves on a llama, llamas remain valid candidates — riding a llama IS the manual signal.

## Bot anvil/enchant: fix server-side close race that silently dropped every interaction (2026-05-02)

**The bug:** clicking Anvil or Enchant from the bot inventory screen opened the bot's anvil/enchant UI on the client, but every subsequent slot click was silently rejected by the server. Items "in" the slots were pure client-side prediction — nothing committed. Closing the screen returned the bot's inventory to its original state because the server never processed any operation.

**Root cause:** `openBotAnvil()` and `openBotEnchant()` in [BotPlayerInventoryScreen](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java) sent the open-payload then called `close()` on the inventory screen. `HandledScreen.close()` sends a `CloseHandledScreenC2SPacket` to the server. Vanilla `ServerPlayNetworkHandler.onCloseHandledScreen` does NOT check syncId — it unconditionally calls `player.closeHandledScreen()` on whatever the current handler is. Server packet processing order:

1. `BotAnvilOpenPayload` arrives → `player.openHandledScreen(...)` opens the anvil with new syncId Y; server's currentScreenHandler is now the anvil.
2. `CloseHandledScreen` arrives milliseconds later → vanilla closes whatever's current → **closes the just-opened anvil**.

Vanilla `onClickSlot` DOES check `currentScreenHandler.syncId == packet.syncId`, so subsequent slot clicks from the client (still showing the anvil with anvil syncId Y) get silently dropped — nothing reaches our `BotAnvilScreenHandler.onSlotClick` / `canTakeOutput` / `onTakeOutput`. The client UI keeps showing predicted state until the player closes (or opens another screen) because the server has no anvil handler to send slot updates from.

**Fix:** removed the `close()` call from both methods. The server's `openHandledScreen()` already calls `closeHandledScreen()` on any existing screen as part of opening the new one, so the explicit close is redundant AND harmful.

**Confirmed by diagnostics:** v1.1.52 added `[anvil-onSlotClick]` logging on every slot interaction reaching the server-side handler. A repro session showed bot-inv-open → anvil-onClosed with **zero** slot-click rows in between — proving every click was being rejected before reaching our handler.

Diagnostic logging stays in place; tags `anvil-onSlotClick`, `anvil-canTakeOutput-allow/-deny`, `anvil-take-output-pre/post`, `enchant-onButtonClick-pre/post`, `*-onClosed-*` all remain. Once a repro confirms operations now commit, the chatty logging can be trimmed in a follow-up.

## Spells header button now expands the overlay + anvil canTakeOutput chat warning (2026-05-02)

Two fixes from the in-session debug pass:

- **Spells header button**: `switchToSpellsTab()` in [BotPlayerInventoryScreen](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java) only set `overlayCategory = TopicCategory.SPELL` and played a chime — it never called `toggleTopicsExpanded(true)`. So clicking the [✦] icon in the header from the collapsed state played the sound but didn't visibly open anything. Now it expands the overlay if it's collapsed, then switches the tab.
- **Anvil canTakeOutput diagnostics + chat warning**: extended [BotAnvilScreenHandler.canTakeOutput](src/main/java/net/wcfcarolina13/ui/BotAnvilScreenHandler.java) to log every accept/reject decision with bot XP vs cost, and to send a `§e[Anvil] Jake has X XP levels but needs Y§r` (or "no valid anvil output") chat line on rejection so the player can see why their click did nothing. The vanilla client predicts a successful take, then snaps back when the server rejects, which previously looked indistinguishable from a successful operation that "reverted." Same diagnostic surface as the earlier instrumentation; new logger tags `anvil-canTakeOutput-allow` / `-deny`.

If repro shows `validCost=false`, the spellbook items aren't producing a real anvil recipe (different bug); if it shows `enoughXp=false`, the "reverting" symptom is actually the bot lacking XP for the operation and the next step is to surface bot XP to the client so it doesn't predict false success.

## Bot anvil/enchant diagnostic build (2026-05-02)

Diagnostic logging added under logger `bot-anvil-enchant-diag` to track down the reported bug where anvil book-combine and enchant operations appear to apply (visible in-screen, output taken to cursor / shift-clicked back into inventory) but revert when the bot inventory is re-opened. Static code paths look correct, so this build instruments every read/write boundary so a single repro pinpoints the divergence:

- `enchant-onButtonClick-pre/post` — bot XP, slot 0/1 contents, item-stack identity hash, bot inventory summary
- `anvil-take-output-pre/post` — level cost, bot/viewer XP, output stack, input stacks, viewer cursor
- `*-onClosed-pre/post` + `*-insert` — cursor and slot contents at close; per-insert `attempted/inserted/remainder` rows so we can see exactly what ends up in `bot.getInventory()`
- `bot-inv-open` — bot inventory snapshot at the moment the player views it post-anvil/enchant

Also added a defensive `BotInventoryStorageService.save(bot)` at the end of `BotAnvilScreenHandler.onClosed` and `BotEnchantmentScreenHandler.onClosed` so any concurrent autosave or join-restore path can't roll back what just landed. If this alone fixes the bug, we'll know it's a persistence race; if the logs show a mutated inventory followed by a stale `bot-inv-open` summary, we'll know there's a reset path still to find.

Helper class: `net.wcfcarolina13.GameAI.services.BotAnvilEnchantDiagnostics`. Logs are intentionally chatty for a single repro — once the bug is identified the logging can be trimmed.

## Store Here: tooltip + in-game guide updated for new modifier (2026-05-01)

Quick Store tooltip in `BotPlayerInventoryScreen` and the Quick Store / Quick Fetch guide entry in `BotGuideScreen` now describe both modifiers (plain = filtered, shift = dump everything). Picker overlay HUD already mentioned both modes from the prior commit.

## Store Here: filtered by default, shift+click for full dump (2026-05-01)

The "Store Here" target picker (`StoreTargetPickerOverlay` → `StoreTargetPayload` → `ChestRegistryNetworkManager.handleStoreTarget`) used to call `ChestStoreService.depositAll` unconditionally, dumping the bot's entire inventory including equipped armor, mainhand tool, food, and lodestone compasses.

New behavior:

- **Plain left-click** → filtered deposit using `!isOffloadProtected(stack)` (same predicate the bot uses for its own auto-offload and `depositHuntLoot`). Skips damageable gear, bundles, lodestone compasses, cooked food, and items in `OFFLOAD_PROTECTED_ITEMS`.
- **Shift + left-click** → unfiltered `depositAll` (legacy "dump everything" behavior, kept for cases where the player explicitly wants to strip the bot — e.g. swapping loadouts at a base).

Confirmation message includes a "(kept its gear)" suffix on the filtered path so the player can tell which variant ran. HUD instructions on the picker now mention both modes. Older clients that don't send `keepGear` default to filtered (safer) on the server.

## Walkable-partials fix in workstation stand-finders (2026-05-01)

Audited the rest of the codebase for the same walkable-partial-collision bug that broke chest deposits. Three sites had identical bugs in their "find a place near the workstation for the bot to stand" logic:

- [CraftingHelper.findStandableOptions](src/main/java/net/wcfcarolina13/GameAI/services/CraftingHelper.java) — used by auto-crafting (e.g., log → planks); a crafting table with stairs/slabs/carpets in any of its accessible neighbor cells would have been impossible to use.
- [SmeltingService.findStandableOptions](src/main/java/net/wcfcarolina13/GameAI/services/SmeltingService.java) — used by the auto-cook batch flow; a furnace on a stair-edged kitchen counter would have been impossible to fuel.
- [HangoutSkill.isStandable](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HangoutSkill.java) — campfire hangout idle behavior; carpets / pressure plates near a fireplace would have been treated as un-standable.

All three now accept walkable partials at the foot and head positions via `WalkablePartialBlocks.isStandable` / `isPathable`. The footing-below check (which correctly requires non-empty collision) is unchanged.

Audit also flagged a few sites that turned out to be polarity-confusion false positives (HarvestCropSkill / FarmSkill / PlantSeedsSkill `isSafeStandingGround`, MovementService `isFootSolid`) — those check that the FOOTING block is non-empty, which is the correct behavior; stairs-as-footing already pass. `MovementService.hasClearance` has a minor cosmetic walkable-partial issue (rejects standing exactly inside a carpet cell), but it's used pervasively for door / gate / corridor traversal so it's left alone — too risky to change without a specific failure log.

## Chest stand-candidate also tries chestY - 1 (2026-05-01)

Same beach chest from earlier today still failed to deposit even with the walkable-partial fix. The chest is the upper of a 2-tall stacked-chest pair at (227, 65, 1253) — no platform around it at y=65, just open air with sand at y=63. The original logic only tried stand cells at `chest.y` (feet at y=65), and for every horizontal neighbor the cell at y=64 (below) is air, so the footing check rejected all four candidates.

Fix: [ChestStoreService.findStandCandidatesNearChest](src/main/java/net/wcfcarolina13/GameAI/services/ChestStoreService.java) now iterates `yOffset ∈ {0, -1}`. The `chest.y - 1` candidate puts the bot's feet on the ground next to the lower chest with its head at chest level — exactly the position a player uses to access an elevated/stacked chest, and well within the standard reach distance for `BlockInteractionService.canInteract` to succeed.

## Chest stand-candidate accepts walkable partials (2026-05-01)

Bot repeatedly failed to deposit into an empty, accessible chest on a beach with `Store transfer abort: no stand candidates from findStandCandidatesNearChest at 228, 65, 1253`. Root cause: [ChestStoreService.findStandCandidatesNearChest](src/main/java/net/wcfcarolina13/GameAI/services/ChestStoreService.java) rejected any horizontal neighbor whose stand or head cell had a non-empty collision shape. Stairs/slabs/carpets/pressure plates have non-empty shapes but are walkable — the same pattern already documented in `feedback_walkable_partial_blocks.md` and handled by `WalkablePartialBlocks`. The chest sat on a stair-edged sandy platform, so all four neighbors were stairs and every candidate was rejected.

Fix: stand cell now accepts blocks that pass `WalkablePartialBlocks.isStandable(...)` (slabs, stairs, snow layers, carpets, plates, rails); head cell accepts blocks that pass `WalkablePartialBlocks.isPathable(...)` (thin partials only — carpets/plates/rails/etc., not slabs/stairs which would actually block the head). Footing-below check is unchanged: solid collision required.

## Liberal feeding + hunger tuning + hunt crash fix (2026-04-21)

**Symptoms observed in the 2026-04-20 session:**

1. Bot's hunger plateaued at food=15 for ~10 minutes. Trying to hand-feed raw salmon was refused. Bot also didn't self-eat from inventory.
2. Sunset fast-travel + sunrise resume dropped food to 9. `Auto-hunt (hungry) starting for Jake at food=9` fired — even though the bot had 300+ raw fish in its inventory. Simultaneously `Auto-cook: started batch cook` sent `"Heading to the furnace..."`.
3. The hunt immediately crashed with `ThreadLocalRandom accessed from a different thread (owner: Server thread, current: auto-hunt-1)` from [HuntSkill.attackTarget](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HuntSkill.java) — c2me's `CheckedThreadLocalRandom` caught `bot.attack` being called off the server thread.

**Root causes** (three independent, see [docs/superpowers/specs/2026-04-21-liberal-feeding-and-hunger-tuning-design.md](docs/superpowers/specs/2026-04-21-liberal-feeding-and-hunger-tuning-design.md) for the full investigation):

- `HealingService.autoEat` and `BotFoodGivingService.tryGiveFood` both gated eating on `foodLevel < HUNGER_COMFORTABLE (15)` — strict less-than, so at exactly 15 neither self-eat nor hand-feed fired.
- `BotAutoHuntService` fired at `food ≤ 10` without consulting the bot's larder. The existing `MIN_BACKUP_FOOD_ITEMS` check only ran when `food > 10`.
- `HuntSkill.attackTarget` loops on a worker thread (`auto-hunt-N`) but called `bot.attack(target)` + `bot.swingHand(...)` directly — both mutate entity state and use RNG, forbidden off the server thread.

The sunset/sunrise "double fast-travel" was investigated and is working as designed — one trip home at sunset, one resume trip back at sunrise ([BotAutoReturnSunsetService.java:315–348](src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java)).

**Fixes:**

- **Liberal feeding** in [BotFoodGivingService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotFoodGivingService.java): non-precious food is now accepted whenever `foodLevel < 20`. The `HUNGER_COMFORTABLE`/`needsRegenFuel` gates are gone. Precious foods (golden apple, enchanted golden apple, golden carrot) instead open a clickable chat prompt — `"<Bot>: That golden apple is precious. Eat it anyway? [Yes] [No]"` — with a 15-second TTL. `[Yes]`/`[No]` fire internal `/bot feedconfirm <uuid>` / `/bot feedcancel <uuid>` commands registered in [modCommandRegistry.java](src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java). Pending entries are validated against sender UUID + held item before consuming; a tick sweep purges expired ones. Forbidden foods (rotten flesh, pufferfish, etc.) still refuse.
- **Per-player "Auto-accept Precious Foods" toggle** following the established durability-preservation pattern: new [UpdatePlayerAutoAcceptPreciousPayload](src/main/java/net/wcfcarolina13/network/UpdatePlayerAutoAcceptPreciousPayload.java) / [RequestPlayerAutoAcceptPreciousPayload](src/main/java/net/wcfcarolina13/network/RequestPlayerAutoAcceptPreciousPayload.java) / [PlayerAutoAcceptPreciousStatePayload](src/main/java/net/wcfcarolina13/network/PlayerAutoAcceptPreciousStatePayload.java), `ManualConfig.playerAutoAcceptPreciousFoods` map + getter/setter, server handlers in [Frens.java](src/main/java/net/wcfcarolina13/Frens.java), client S2C receiver in [FrensClient.java](src/main/java/net/wcfcarolina13/FrensClient.java), new `AUTO_ACCEPT_PRECIOUS_SERVER_VALUE` field + setter on [BotPlayerPreferencesScreen](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerPreferencesScreen.java), new `AUTO_ACCEPT_PRECIOUS_FOODS` TopicAction row in Admin → Behavior on [BotPlayerInventoryScreen](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java). Default OFF. When ON, precious foods are consumed without the prompt.
- **Hunt gate** in [BotAutoHuntService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotAutoHuntService.java): check `HuntSkill.countSafeFoodItems(bot) >= MIN_BACKUP_FOOD_ITEMS` **before** the hunger threshold. If the bot has enough safe non-precious food, auto-hunt skips — `autoEat` will consume next tick. `MIN_BACKUP_FOOD_ITEMS` bumped from 4 → 8 so "plenty of fish" is the rule, not "has 4 scraps." New helper `HuntSkill.countSafeFoodItems` excludes precious (golden apple etc.) and forbidden (rotten flesh etc.) items so a single golden apple doesn't block legitimate hunts.
- **Abundance auto-eat** in [HealingService.java](src/main/java/net/wcfcarolina13/GameAI/services/HealingService.java): new `hasAbundantFood(bot)` — true if any single non-precious, non-forbidden edible stack has `count >= 64`. When true, `autoEat`'s comfort threshold rises from 15 → 18 so bots eat sooner when there's plenty in the bag. New public `HealingService.isPrecious(ItemStack)` / `isForbidden(ItemStack)` helpers replace the scattered inline lowercase-contains checks in three places.
- **Threading fix** in [HuntSkill.attackTarget](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HuntSkill.java): the in-range branch now schedules `selectBestMeleeWeapon` + `bot.attack` + `bot.swingHand` via `server.execute(...)` with an `isAlive`/`isRemoved` re-check inside the lambda. `distSq`/`canSee` stay on the worker thread (read-only). `sleep(220L)` keeps the worker-thread loop paced.

**What this does NOT change:**

- `BotAutoCookingService` threshold stays at `food ≤ 10`. With the hunt gate in place, auto-hunt will no longer fire simultaneously, and auto-cook alone doing "heading to the furnace" when food is low is reasonable.
- Sunset/sunrise fast-travel behavior unchanged — it was working correctly.
- Emergency starvation path (food ≤ 2) unchanged. If auto-eat somehow can't fire at emergency and the bot has food in inventory, hunting isn't the right remediation anyway — auto-eat reaching that state is itself the bug to fix.

**Build:** `./gradlew build -x test` — green. No `mod_version` bump — user was playing during implementation; deploy when the game is closed.

## Trader/llama proximity dialogue + ungate Batch3 topics from questing mode (2026-04-21)

**Symptom:** In admin-mode worlds, the bot never said any of the `topic_trader_*` / `topic_llama_*` lines even when a wandering trader and its llamas walked right past. Ditto every other Batch3 topic group — clicking "Tell me about traders and mounts" in the dialogue UI replied `"This world isn't using survival recruitment."` All five Batch3 passive-observation topic groups (biomes, structures, dimensions, traders/mounts, travel) were unreachable outside questing mode.

**Cause:** [SurvivalCompanionQuestService.handleTopic](src/main/java/net/wcfcarolina13/GameAI/services/SurvivalCompanionQuestService.java) gates ALL topic keys on `SurvivalRecruitmentService.isEnabled` + `st.isRecruited()`. Those gates belong on quest-progression keys (`companion_status`, `companion_check`, `village_missing`, `village_projects`, `companion_anchor_set`, `companion_anchor_here`) — not on passive world-observation lines that are just flavor about what the bot sees.

**Fixes:**

- **Ambient proximity trigger** in [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java): new `tryTraderOrLlamaNearby` fires `topic_trader_*` or `topic_llama_*` from two flat weighted pools (`TRADER_NEARBY_LINES`, `LLAMA_NEARBY_LINES`) when a `WANDERING_TRADER`, `TRADER_LLAMA`, or `LLAMA` is within 14 blocks horizontally / 6 vertically. Trader takes priority when both are present (llama is usually leashed to it anyway). 1.5%/tick probability, `COOLDOWN_180S_MS` per family — matches the pig-staring / enderman-spotted cadence. Weights lean `ASK` > `FIRST` > `MEMORY` so intro lines still fire but don't dominate. Independent state — does not touch `Batch3TopicDialogueService` quest-memory tracker, so the two paths stay decoupled. Debug handles added (`/bot debug_line trader_nearby <id>` / `llama_nearby <id>`).
- **Batch3 gate removal** in [SurvivalCompanionQuestService.java](src/main/java/net/wcfcarolina13/GameAI/services/SurvivalCompanionQuestService.java): new `isBatch3TopicKey` helper matches the five Batch3 group keys (plus their legacy `topic_*` aliases). When the incoming topic key matches, `handleTopic` short-circuits BEFORE the `isEnabled` / `isRecruited` checks, serves the line via `Batch3TopicDialogueService.pickLineForTopic` with whatever alias is available (recruited alias if any, else the passed `botAlias`), and returns. Quest-specific topic keys still flow through all the existing gates.

**What this does NOT change:**

- Quest progression topics (`companion_status`, `village_missing`, `village_projects`, `companion_check`, `companion_anchor_set`) still require questing mode + recruitment. They're genuinely quest-scoped.
- `BotQuestService.java:138` passive sidequest proposer gate (2026-04-18) stays — that one is about suppressing unwanted auto-triggered sidequests in admin mode, different category from "allow the bot to say words about a llama."
- Distant non-HOME base compass requirement (`BotEventHandler.java:2079`) stays — world navigation gate, not dialogue.

**Build:** `./gradlew build -x test` — green. No version bump yet; deploy on user confirm.

## 1.1.42 — Named-mob pacifism (2026-04-20)

**User intent:** When the player name-tags a mob — hostile (a display zombie in a farm, a kept raid captain) or peaceful (a prize cow) — the bot should treat it as off-limits. No attacking; if hit back, flee instead of fighting. Per-bot opt-in toggle restores normal engagement for players who want the bot to keep defending them.

**Design.** The naive `if (entity.hasCustomName()) return false` inside `EntityUtil.isHostile` was rejected as too blunt: it would strip named hostiles from threat detection entirely, meaning the bot couldn't even react (flee) when they damage it. The split is:

- **Threat detection unchanged.** `EntityUtil.isHostile` still matches named hostiles so hostile scans and the flee planner see them.
- **New central policy service.** [BotCombatPolicyService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotCombatPolicyService.java) exposes `shouldBotAttack(Entity, ServerPlayerEntity)` — single method returning `false` for any `LivingEntity` with a custom name when the bot's `attackNamedMobs` toggle is off.
- **Per-bot persisted toggle** on [BotHomeService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java) modelled 1:1 on `autoReturnPreferLastBedAtSunset`. Default OFF (pacifism is the default).
- **Flee-on-damage hook** via new `BotFleeService.fleeFromEntity(bot, attacker, currentTick)` — public wrapper over the existing private `startFleeing` that seeds state with the attacker as the sole threat.

**Engagement gates** (where bots would otherwise attack a named mob):

- `BotEventHandler.engageHostiles` — added a `shouldBotAttack(e, bot)` gate inside the existing `filtered` loop at ~L3657. This is the single choke point for all 8+ `BotActions.attackTarget` sites inside `engageHostiles`.
- `BotAnimalDefenseService.scanHostilesStep1` / `scanWatchListStep2` — gated the `markAttackerForDefense` call in each `canEngage` branch. Step-1/Step-2 still run the scan (so named hostiles remain visible in threat detection), the gate only stops the engagement.
- `BotMutualAidService.respondToDefenseRequest` — guard clause returns `false` before `attackTarget` if the target is named.
- `BotRLActionService` `"attack"` case — filter hostile list before `attackNearest`; logs "No attackable hostile entities (all name-tagged or none present)" when filtered empty.

**HuntSkill was already safe.** [HuntSkill.java:992](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HuntSkill.java#L992) `isDomesticated()` already returned `true` on `hasCustomName()`, so named cows/sheep were never valid hunt targets. No change needed.

**Damage listener** in [Frens.java:899-911](src/main/java/net/wcfcarolina13/Frens.java#L899-L911): after the existing player/hostile callout branches, if attacker is a non-player `LivingEntity` with a custom name and the toggle is off, call `fleeFromEntity`. Player attackers are explicitly excluded — the pacifism rule is about mobs, not PvP.

**Mode caveat documented in the `fleeFromEntity` javadoc** (not fixed): `tickFlee` only continues flee when the bot is in `Mode.IDLE`. If the bot is in COMBAT against *other* hostiles when a named mob hits it, seeded flee state won't tick until that combat ends. If in-game testing shows this is wrong, the fix is a `forcedByDamage` flag on `FleeState` that bypasses the mode guard — not committed pre-emptively since it complicates the existing flee state machine without evidence we need it.

**Admin plumbing:**

- New chat command `/bot attack_named_mobs on|off|toggle [<target>]` via `BotHomeCommands.buildAttackNamedMobs()` and two handlers in `modCommandRegistry`.
- New Admin-tab toggle row **Attack Named Mobs** (⚐) between **Auto Hunt (Starving)** and **Unleash Tethered**. `botStats` delegate grew from 24 → 25 slots, slot 24 exposes the toggle to the client.
- Tooltip: "When OFF (default), the bot protects any name-tagged mob — hostile or peaceful — and flees if one attacks it. Turn ON to let the bot engage name-tagged mobs normally."

**Unchanged as of this ship:** named raid captains during an active raid are still pacified. If mid-raid testing shows this breaks the Bad Omen mechanic (bot can't engage the rest of the raid because the named captain blocks the engagement flow), follow-up will add a raid-in-progress bypass. Not wired pre-emptively because `BotAnimalDefenseService`'s gate only blocks defense marking — the engageHostiles filter excludes the one named captain while letting other raiders through, so the common case is already correct.

## Unified spells menu — migrate CompanionSpellsScreen into the Spells tab (2026-04-20)

**Symptom:** Two separate "spells" surfaces with mostly-disjoint content, both labelled "Spells". (1) `CompanionSpellsScreen` — a dedicated grid screen with Regroup, Summon, Home, Remote Inventory, Enchant, Anvil. (2) Bot Inventory → Spells tab — a topic list with Remote Guidance, Chorus Recall, Soul of Ender, Remote Inventory. User (dev) reported: "Shouldn't these be the same thing? If I have the Eye of Ender and/or am next to the Enchanting Table, I should just see that in the spell menu." Only "Remote Inventory" appeared in both. Which UI you landed in depended on which button you clicked. No single canonical list.

**Fix** in [BotPlayerInventoryScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java):

- Added three new `TopicAction` values: `SPELL_SUMMON`, `SPELL_ENCHANT`, `SPELL_ANVIL`. Reused the existing `COMPANION_COME` and `COMPANION_HOME` actions for the Regroup and Home entries (they already have server-side handlers).
- Rebuilt `SPELL_TOPIC_ENTRIES` with three categorical groupings: **Movement** (Regroup / Summon / Home), **Travel** (Remote Guidance / Chorus Recall / Soul of Ender), **Remote Access** (Remote Inventory / Enchant / Anvil). All 9 spells now live in one list.
- Added `switchToSpellsTab()` — the `OPEN_SPELLS` action (`✦` button in the inventory header) now switches `overlayCategory` to `SPELL` in-place instead of opening the legacy screen. One screen, one spell list.
- Added `openBotEnchant()` and `openBotAnvil()` that send the same `BotEnchantOpenPayload` / `BotAnvilOpenPayload` the legacy screen used.
- Extended `isSpellEntryEnabled()` with gates for the new actions: Regroup and Summon need full access or an Eye of Ender; Enchant needs the player within 4 blocks of an Enchanting Table; Anvil needs the player within 4 blocks of an Anvil (new `isNearAnvil` helper). Home is left always-enabled — server gates by navigation tier which the client can't cheaply verify.
- Added per-spell tooltip text mirroring the CompanionSpellsScreen tooltips so hover reveals the gating requirements.

The legacy `CompanionSpellsScreen` is no longer referenced from the inventory flow, but was left in the codebase for one session so the cutover can be verified before deletion. Backlog flagged in `RALPH_TASK.md` for removal plus the two Actions-tab duplicates (Regroup / Return Home) that could also move into Spells for full consolidation.

## Fast-travel gate visibility in chat + guide topic (2026-04-20)

**Symptom:** When the bot fast-travels (sunset return, sunrise resume, `/bot home`, etc.) the chat just says `Jake has departed and will arrive in ~10 seconds.` — no mention of which gate opened (lodestone compass? map+compass? smoke signal? ender eye? nearby enchanting table?). The reason tag was already being logged to `latest.log` as `Fast-travel tier: … reason=lodestone-compass mult=1.0`, but never surfaced to the player. Meant that even the dev couldn't tell in-game whether a departure used the tier-2 lodestone shortcut or the tier-1 map-reading path, and players had no way to learn what gates existed without reading source.

**Fix** in [NavigationArtifactService.java](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java):

- Hoisted `tier.reason()` and `tier.delayMultiplier()` out of the gate-evaluation block into local variables available at the departure-notification site (~L1103).
- New `formatTierSuffix(reason, multiplier)` helper maps the compact telemetry tags (`lodestone-compass`, `map+compass-rendered+smoke-signal+spyglass`, `underground-no-artifact`) into readable prose (`lodestone compass`, `map + compass + smoke signal + spyglass`, etc.) plus a speed label (`instant-class` / `tier 1` / `slow`).
- Departure chat now reads: `Jake has departed §7(fast-travel: lodestone compass, instant-class)§e and will arrive in ~10 seconds.` The existing refused-travel chat messages were already specific per-reason and were left untouched.

**Also added** a new `fast_travel_gates` guide topic under Basics (and a `bases_home_explained` topic in the same pass). The gates topic documents:

- Tier 2 (1.0×) qualifiers: lodestone compass, Eye of Ender, Wizard's Tome, nearby Enchanting Table, mutual Ender Pearls.
- Surface tier 1 gates (map+compass+rendered destination, smoke signal within 5× base radius), the spyglass step-saver, and 1-gate (3.0×) / 2-gate (2.0×) scoring.
- Underground gate variants (map+compass+light, lodestone-no-target lenient path, smoke signal within 2× radius).
- An important clarification: these gates apply to automatic fast-travel. The **Summon** button in the spellbook (CompanionSpellsScreen) uses Eye of Ender as its own gate with 60s cooldown, and the **Remote Guidance** / **Chorus Recall** / **Soul of Ender** spells in the Bot Inventory Spells topic are separate manual spells with their own costs (ender pearls / chorus fruit). Addresses the "I have an Eye of Ender but don't see anything teleport-related in the spellbook" confusion by pointing at the two different UI surfaces.

## Follow/stop clear pending sunrise-resume + Base Manager clarity (2026-04-20)

**Symptom:** After `/bot fish` aborts at sunset, the mod persists a "resume fishing tomorrow" record. Player then issues `/bot stop` (clears active task) and `/bot follow` (manual control), walks the bot back to base, sleeps. The next morning the bot auto-fast-travels back to the fishing spot and resumes the skill the player had explicitly stopped. Traced in [latest.log](~/Library/Application%20Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log) around `15:05:19 → 15:06:14`: sunrise-resume record saved, `/bot stop` + `/bot follow` issued, bot slept, dawn broke, `Sunrise fishing resume` fired regardless.

**Cause:** [`SkillResumeService.clear()`](src/main/java/net/wcfcarolina13/GameAI/services/SkillResumeService.java#L64) wipes `LAST_SKILL_BY_BOT`, `AWAITING_DECISION`, `AUTO_RESUME_PENDING`, and `PENDING_BY_RESPONDER` — but **not** `SUNRISE_RESUME_BY_BOT`. That map was only ever dropped by `/bot fish forget` (`executeFishForget`). Additionally, [`executeFollow`](src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java#L3410) only aborted the current task and set follow mode; it didn't touch any resume state or the sunset-return session.

**Fix:**

- `SkillResumeService.clear(UUID)` now also calls `SUNRISE_RESUME_BY_BOT.remove(botUuid)`. Any code path that resets pending-skill state (manual `/bot stop`, no-vote decline, etc.) now drops the sunrise resume as part of the same clear.
- `executeFollow(...)` now calls `SkillResumeService.clearAndNotify(bot.getUuid())` and `BotAutoReturnSunsetService.clearSession(bot.getUuid())` before setting follow mode. Explicit manual control = clear latent auto-resume paths so nothing re-fires behind the player's back.

**Base Manager clarity pass (same symptoms, different reach):** the menu shows registered bases (yellow `§e[Base]`) mixed with lodestone compasses (white rows), and `[Home]` means two different things depending on row color — the preferred home base vs the designated home compass. Three layers of clarity added:

- [BaseManagerScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BaseManagerScreen.java) renders a one-line legend above the list when it's non-empty: `[Base] = registered base · white = lodestone compass · [Home] = preferred`.
- [BotGuideScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java) gets a new `bases_home_explained` guide topic under Basics covering the color code, the two `[Home]` meanings, the sunset-return priority (closest of lastSleep / preferred / nearest base), and why spawn can steal the return when a preferred home is far away.
- `RALPH_TASK.md` carries the remaining UX work (section headers, hover tooltips, echo-on-Set-Home confirmation) into a dedicated backlog item so the next session can do the full polish.

Also added a separate backlog item for the **named-hostile-mob pacifism** idea — skip combat targeting on any mob with a custom name tag and engage flee behavior if hit, to keep mob-collection farms/displays safe.

## Dialogue audio: apply in-mod-fill TTS batch (2026-04-18)

TTS job for `april_2026_in_mod_fill` completed and produced 55 OGGs. Staged all 55 into `src/main/resources/assets/frens/sounds/dialogue/`. Full categories filled: `cook_*` (8 events), all `food_accept_*` / `food_refuse_*` (8 events), `react_rotten_*` (3 of 4 — `brave` not generated), `react_stew_*` (3 of 4 — `bold` not generated), `wake_*` (4 events), `portal_nether_overworld_1` (3 variants), `portal_end_overworld_1` (3 variants), `follow_dim_nether` (3 variants), `follow_dim_end_2` (3 variants), partial fills for 4 other `follow_dim_*` events.

**Reconciled sounds.json against on-disk OGGs** — the TTS run produced fewer variants than planned for some events. Pruned 13 events' `sounds[]` to only reference OGGs that actually exist:
- `cook_could_eat`, `react_rotten_brave`, `react_stew_bold`, `portal_nether_overworld_2`, `weird_portal_end_in_nether_1/2`, `weird_portal_nether_in_end_1/2` → empty `"sounds": []` with `"replace": false` (registered event, silent until a future TTS pass produces audio).
- `follow_dim_end` → keep `[1, 2]`, drop `[3]`
- `follow_dim_nether_2` → keep `[1, 2]`, drop `[3]`
- `follow_dim_overworld` → keep `[1, 3]`, drop `[2]`
- `follow_dim_overworld_2` → keep `[1]`, drop `[2, 3]`
- `portal_end_overworld_2` → keep `[1]`, drop `[2, 3]`

Also populated `care_player_hurt_1` with newly-generated `__02` variant (now has `[1, 2, 3]`).

Net effect at next launch: zero "File … does not exist, cannot add it to event" warnings for the staged categories. Every OGG referenced by sounds.json exists on disk; every OGG on disk is referenced by sounds.json.

**Handoff doc note**: `generate_handoff.py` output ([`audio_triage/handoff_to_mod_repo.md`](/Users/roti/gemini_projects/ai-player-dialogue/audio_triage/handoff_to_mod_repo.md)) had several generator bugs I worked around rather than applied verbatim: duplicate filename entries in sounds.json blocks (counts OGG+WAV as two variants), WAV files listed as copy targets (WAVs shouldn't ship), and false-positive "new event" entries for events that already exist under a different prefix (e.g. `bot.line.hurt_grunt` vs the existing `bot.fx.hurt_grunt`). Worth fixing in the generator when there's time, but none of those issues leaked into this apply — computed deltas directly from the on-disk state instead.

## Dialogue audio: apply pending `map` decisions from triage (2026-04-18)

Staged 24 OGG files tagged `map` in [`audio_decisions.json`](/Users/roti/gemini_projects/ai-player-dialogue/audio_triage/audio_decisions.json) from [`april_2026_v1_1_36_fill/output_ogg/`](/Users/roti/pontus/ai-player-dialogue/april_2026_v1_1_36_fill/output_ogg) and [`april_2026_v1_1_36_redo/output_ogg/`](/Users/roti/pontus/ai-player-dialogue/april_2026_v1_1_36_redo/output_ogg) into the mod's `sounds/dialogue/`.

**Creeper joke line (`bot.line.banter_creeper_kidding`)** — swapped in the A/B-tested Apr 18 take `banter_creeper_kidding__01.ogg` (29,360 B) over the old Jan 8 version, and collapsed the event from 3 variants → 1 (removed `__02`/`__03` OGGs and sounds.json entries). User rejected the A/B __02 take in triage (`delete`), so this is now single-variant.

**Kraken line (`bot.line.boat_kraken`)** — removed voiced audio entirely per user instruction ("not good, marked for removal"). Deleted `boat_kraken__{01,02,03}.ogg`; set `sounds.json` → `"sounds": []` with `"replace": false` so the event stays registered (overhead text dialogue still resolves) and is a clean target for future regen. All existing takes across batches were tagged `regen` or `delete` in triage.

**Care reactions (6 events × various variants = 12 new OGGs)** — populated previously-empty `sounds[]` arrays in sounds.json for `care_player_hurt_1/2/3` and `care_player_hungry_2/3`. Note: `care_player_hurt_1` has only `__01` + `__03` (no `__02` was generated/tagged); `care_player_hungry_2` has only `__02`; `care_player_hungry_1` still has no audio (not in the fill batch). Fed by `CompanionContextReactionService.tryPlayerHurt` / `tryPlayerHungry`.

**Tree-punch "ora" line (`bot.line.tree_punch_ora`)** — populated previously-empty `sounds[]` with single `__01` variant.

**Fresh takes replacing existing OGGs (11 files, same filenames):** `ambient_saw_bird__01`, `dig_down_warning__01`, `discover_mineshaft__02`, `discover_quartz__01`, `freefall_aaahaha__01`, `freefall_woohoo__03`, `hurt_grunt__06`, `mount_cant_find_food__02/03`, `skill_woodcut_careful__01`, `wolf_guard_duty__03`. No sounds.json changes — same filenames, new contents.

## Fishing: treasure-eligible open-water picks (2026-04-18)

**Symptom:** Near open lakes, Jake was routinely setting up at shoreline spots that don't pass vanilla's 5x5x4 open-water check — so no treasure (enchanted books, saddles, bows, name tags). [latest.log:5147](~/Library/Application%20Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log#L5147) confirmed scores weren't even being logged — `score={:.2f}` printed literally.

**Three fixes** in [FishingSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java):

1. **Scoring bug.** `findStandOptions` at L1357 ranked stand+cast combinations without considering whether the chosen cast target passes the vanilla open-water check — the `-3.0` bonus only lived inside `chooseCastTargetAlongLine`, which picks *the best target per stand*, so the bonus never crossed the stand-comparison boundary. A slightly deeper shoreline spot could out-score a true open-water spot. Added an `isVanillaOpenWater(world, castTarget)` bonus of `-5.0` directly in the per-stand quality formula — large enough to dominate typical openness/depth deltas (±3.15 / ±3.30).

2. **`isVanillaOpenWater` didn't actually match vanilla.** Old implementation diverged three ways from [`FishingBobberEntity#isOpenOrWaterAround`](https://maven.fabricmc.net/docs/yarn/): (a) required `Blocks.WATER` block match instead of still-water fluid + empty collision (rejected waterlogged edge cases vanilla accepts), (b) used `!isOpaque()` for upper layers instead of `isAir() || isLilyPad` (accepted tall grass / string spots vanilla rejects), (c) required `isSkyVisible()` which vanilla does **not** check (rejected treasure-eligible covered ponds). Rewrote as a faithful port: four 5x5 slabs at bobber `Y-1..Y+2`, three-way `PositionType` classification (INSIDE_WATER / ABOVE_WATER / INVALID), state-machine transition rules. Dropped the sky-visibility requirement.

3. **Log format.** `LOGGER.info("... (score={:.2f})", ..., bestScore)` used Python-style format specifiers in an SLF4J template — SLF4J printed the placeholder literally. Changed to `LOGGER.info("... (score={})", ..., String.format(Locale.ROOT, "%.2f", bestScore))` so future sessions actually log a number.

Kept `findFishingSpot`'s `EARLY_EXIT_SCORE=8.0` threshold untouched — with the stronger bonus, true open-water spots will now score low enough that the early-exit should fire on them, not on shoreline candidates.

## Skip wolf-teleport when the bot is mounted (2026-04-18)

**Symptom:** While the commander galloped ahead on their own horse, Jake fell behind on his horse, then abruptly appeared next to the commander *on foot* — his horse was nowhere to be seen. Reproduced in [latest.log](~/Library/Application%20Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log) around 16:35:44 — mount record saved at 16:35:35 (horse `924fd96b-…` at `185,64,1387`, saddled, HP 17), then `Follow wolf-teleport: bot=Jake -> 224, 68, 1407` fires and Jake is 41 blocks away on foot. `resolvePreferredMount` then rejects the saved horse repeatedly as `state-too-far` with distSq growing (4.6k → 40k) as the commander keeps moving — the horse is orphaned at its last position.

**Cause:** `tryWolfTeleport` calls `bot.teleport(world, x, y, z, …)` unconditionally. Vanilla `ServerPlayerEntity#teleport` dismounts the rider before moving, so the horse/boat is left behind. No `hasVehicle()` guard existed at [BotEventHandler.java:6982](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L6982) or at either call site (~4338, ~4730).

**Fix** in [BotEventHandler.java](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java): add an early `if (bot.hasVehicle()) return false;` at the top of `tryWolfTeleport`. Mounted bots now rely entirely on `RideSyncService` to keep up with the commander — the wolf-teleport fallback only fires for on-foot bots. Tradeoff: if `RideSync` can't catch a mounted bot up (e.g. commander crosses into unreachable terrain), the bot falls behind until the commander comes back or stops. That's strictly better than silently losing a saddled horse in unloaded chunks.

## TNT-proximity plea sequence (2026-04-18, v1.1.36)

Implements the 4-line escalating gag from the March 2026 backlog when the bot is stationary near primed TNT and the commander is watching. Mirrors the end-ship state machine.

**Trigger in [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java)** (`tryTntSequence`). Entry: bot not in a vehicle, velocity² < 0.04, live `TntEntity` within 10 blocks, commander within 24 blocks and either facing the TNT or facing the bot, 5-minute start cooldown. Sequence: 4 lines ~1 s apart via `TNT_SEQUENCE_LINES`. Cancels if the TNT despawns (explodes) mid-sequence so we don't play to empty air.

## End-ship "captain now" conditional gag (2026-04-18, v1.1.35)

Implements the 3-stage conditional sequence the user flagged in `audio_decisions.json`:

> *"The hey hey look at me needs to come first, then only after you look at the bot for 3 seconds does it follow with the line 'I'm the captain now.' It's conditional. If you don't turn to look at the bot for more than 14 seconds, the bot says 'that ruined the joke'"*

**Trigger in [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java)** (`tryEndShipSequence`). Entry: commander within 24 blocks, commander NOT currently facing bot (joke needs the look-away setup), bot is perched (in a vehicle, in The End, or y≥70 under open sky), rare roll (~0.08%/tick), 30-min start cooldown. State machine: if commander looks at bot for ≥ 60 continuous ticks (3 s) within 14 s → `I'm the captain now.` If 280 ticks (14 s) elapse without 3 s of continuous looking → `That ruined the joke.` Either resolution resets the state. New fields on `TriggerState`: `endShipSolicitedAtTick`, `endShipLookingTicks`.

## Wire enderman-spotted reaction (2026-04-18, v1.1.34)

New tick-scan trigger `tryEndermanSpotted` in [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java). Scans a 32×16×32 box for live endermen; if any is in the bot's forward cone (dot-product > 0.85 via existing `isEntityFacing`) the bot has a 6%/tick roll to fire `enderman_spotted_dont_look` ("Don't look at it."), 3-min cooldown. No aggro side-effects — `EndermanSafetyService`'s look-avoidance is unaffected.

## Berry-bush + foliage-stuck audio (2026-04-18, v1.1.33)

Both categories had `tryShowSweetBerryBush*` / leaf-stuck plumbing in `CompanionOverheadDialogueService` already — they just weren't reachable from `DialogueTextMapper`, so audio lookup returned null. Added exact mappings in [DialogueTextMapper.java](src/main/java/net/wcfcarolina13/ChatUtils/DialogueTextMapper.java):

- `LINE_BERRY_BUSH_OUCH` / `_YOWCH` / `_THORNY` / `_EDIBLE_I_THINK` (both `...I think` and `... I think` spacings)
- `LINE_FOLIAGE_STUCK_BRANCHES_THICK` / `_IN_BRANCHES` / `_CANT_GET_THROUGH` / `_GOT_ME` (em-dashes and ellipses pre-normalized)

## Fix load-bearing gap in `showOverheadLine` (2026-04-18, v1.1.32)

**Root cause of most "never heard" triage retune items.** `CompanionOverheadDialogueService.showOverheadLine` showed the overhead hologram but never invoked `tryPlayVoicedOverheadLine`, so any caller that went through it got silent dialogue. The private helper `tryShowGeneric` did play audio, but only a handful of internal call sites used it. Everything routed through the public entry point (~48 call sites: greetings, touch-chat context, topic dialogue, overhead lines in `BotEventHandler`, `HuntSkill`, `MushroomForageSkill`, `FortifyVillageSkill`, `LeafLitterSkill`, `GrassSeedSkill`, `BotAutoReturnSunsetService`, `BotFoodGivingService`, `BotAnimalDefenseService`, `PlayerEatingReactionService`, etc.) showed text but played no sound.

**Fix** in [CompanionOverheadDialogueService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadDialogueService.java): also invoke `tryPlayVoicedOverheadLine` from the public method. Safety: `BotDialoguePlayer`'s `MIN_GAP_ANY_VOICE_MS` (2.5 s) throttle protects the few callers that also manually play audio (combat `sayWithSound`, `CompanionContextReactionService.tryTrigger`). In those paths the sound event resolved from the mapper matches the explicit one, so only one play survives the throttle.

Expected impact on triage retune list: greetings (×4), skill_sleep (×2), context_fish (×3), topic_enchanting_ask_3, topic_llama_memory, and most other "I've seen the overhead but never heard it" items resolve in a single fix.

## Phase B: wire new events into existing pools + apply triage retunes (2026-04-18, v1.1.31)

Wires the 75 new April 2026 backlog events into existing game triggers, plus applies actionable triage retune notes.

**B.1 — Register 75 new events + subtitles** in [BotDialogueSounds.java](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java) and [BotDialoguePlayer.java](src/main/java/net/wcfcarolina13/ChatUtils/BotDialoguePlayer.java): weather variants ×24, sunset variants ×6, ambient_teeth_itch, animal/parrot/horse/camel/wolf proximity ×7, berry_bush ×4, creepy_place ×7, enderman, foliage_stuck ×4, i_hit_player ×6, intense_combat ×3, player_hit_me ×6, post_combat/post_explosion extras, shelter_built ×4.

**B.1b — Wire into existing pools**: `CompanionContextReactionService` extends `WEATHER_*_LINES`, `AMBIENT_LINES`, `HIGH_THREAT_LINES`, `SHELTER_LINES`. `BotAmbientChatter` extends `WILDLIFE_CHATTER` + adds `SUNSET_SOON_VARIANTS` helper that picks randomly from the original + 6 new sunset variants. `BotCombatCalloutService` extends `POST_COMBAT_GENERAL_LINES`, `POST_EXPLOSION_LINES`, `FF_RECEIVED_LINES` (+6 player_hit_me), `FF_DEALT_LINES` (+6 i_hit_player), `COMBAT_MULTI_LINES` (+3 intense_combat).

**B.2 — Six triage retune tunings**:

- `ambient_bad_feeling`: moved from `AMBIENT_LINES` to `HIGH_THREAT_LINES` (fires only in nether/end/deep-dark/ancient-city/soul-sand-valley biomes).
- `ambient_my_job`: weight dropped `RARE` → `VERY_RARE` (was still too frequent).
- `meta_stop_looking`: extracted from `META_LINES` into new `COMMANDER_STARING_LINES` with `tryCommanderStaring` trigger that only fires after ≥ 5 s (100 ticks) of the commander continuously facing the bot.
- `ambient_cave_deep`: split into `AMBIENT_DEEP_CAVE_CHATTER`, gated to `y<20 + low-light` (was firing in shallow caves).
- `ambient_dont_like_this`: split into `AMBIENT_HOSTILE_CAVE_CHATTER`, gated to `(y<20 + low-light)` OR `hostile mob within 16 blocks`.
- `post_explosion_bones`: moved to new `POST_EXPLOSION_CREEPER_LINES` pool. Added `creeperExplosionSeen` flag to `CombatMetadata`, set at the 5 explosion-detection sites when `EntityType.CREEPER`. End-of-combat picker now routes to the creeper-only pool only when `creeperExplosionSeen` is true.

## Apply audio triage handoff Phase A: map-new + in-mod deletes (2026-04-18, v1.1.30)

Machine-applied from `audio_decisions.json` via `/tmp/handoff_apply/plan.py`, NOT from the markdown handoff (which had OGG/WAV duplication bugs in its sounds.json snippets for single-variant events).

[sounds.json](src/main/resources/assets/frens/sounds.json):

- 75 new events created (`bot.line.*`), sourced from 2026-04-17/18 Chatterbox batches (`april_2026_v1_1_27_fill`, `january_2026_batch3`).
- 25 events had new OGG variants appended (mostly filling empty `sounds[]` scaffolded in v1.1.27).
- 32 events had variants pruned (69 OGGs deleted from `dialogue/`). 18 of those events drained to empty `sounds[]` — kept the event definitions intact so Java triggers that reference them don't break.
- File reformatted with consistent `{ "name": ..., "weight": 1 }` compact inline style.

Also copied 149 OGGs into `src/main/resources/assets/frens/sounds/dialogue/` and deleted 69 OGGs from the same directory.

Deferred (for TTS agent / separate passes):

- **Regen** (20 files, 12 events): needs re-TTS workflow, no new audio yet.
- **Retune** (163 decisions, 63 events): requires per-trigger Java tuning (6 of the high-priority ones applied in v1.1.31).
- **Java wiring for the 75 genuinely-new events**: handled in v1.1.31 for the routable ones.

## Gate passive sidequest proposer on questing mode (2026-04-18)

**Symptom:** In admin-mode worlds, companions would still spontaneously propose sidequests (e.g. `combat_secure_this_place` — "If we're stopping here, let's make it safe." — triggered by a hostile-mob constraint match). Admin-mode players aren't using the questing flow and don't want these auto-triggered.

**Fix** in [BotQuestService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotQuestService.java): before the passive proposing block in `onServerTick`, skip the bot when `SurvivalRecruitmentService.isEnabled(server)` is false (i.e. admin mode). Active quests (if any carried over from a mode switch) still tick to completion or timeout. The explicit chat hook `tryHandleQuestPrompt` is unaffected — typing "give me a quest" to a bot still works in either mode.

## Mounted-leash HUD hint: boat wording (2026-04-18)

**Symptom:** When a bot was sitting in a boat, the mounted-leash HUD hint still said `🐴 Jake is riding a horse` / `Press ['] to dismount & tether`. The "tether" half is a no-op for boats (RideSync already handles non-leashable vehicles via the `CANNOT_SECURE` path), but the wording was misleading.

**Fix** in [FrensClient.java](src/main/java/net/wcfcarolina13/FrensClient.java): detect `vehicle instanceof AbstractBoatEntity` when populating the mounted-leash hint (both the look-at case and the ride-along case), store it in a new `mountedLeashBotOnBoat` flag, and branch the rendered text:

- Boat: `🛶 <name> is riding a boat` / `Press ['] to dismount`
- Horse / other mount (unchanged): `🐴 <name> is riding a horse` / `Press ['] to dismount & tether`

`findNearbyMountedBotName` was renamed to `findNearbyMountedBot` and now returns the `PlayerEntity` so the caller can inspect the vehicle. Keybind action is unchanged — `/bot stop` + `/bot leash` still does the right thing in both cases.

## Fix async entity spawn crash in hologram service (2026-04-18)

**Symptom:** Woodcut skill crashed repeatedly (`Skill 'woodcut' crashed: Async entity load`) when the bot tried to show its "Scanning for trees..." overhead label. Stack trace ([latest.log 00:05:21](~/Library/Application%20Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log)):

```text
java.util.ConcurrentModificationException: Async entity load
    at class_3898.handler$zfp000$c2me-fixes-general-threading-issues$preventAsyncEntityLoad
    at class_3218.method_8649 (World#spawnEntity)
    at CompanionOverheadHologramService.spawnStand:192
    at CompanionOverheadHologramService.show:92
    at WoodcutSkill.execute:779
```

**Cause:** `WoodcutSkill.execute` runs on the `mod-command-skill-1` worker thread. It calls `CompanionOverheadHologramService.show` directly, which calls `spawnStand` → `world.spawnEntity`. Entity spawning is a server-thread-only operation; C2ME's `preventAsyncEntityLoad` mixin correctly rejects off-thread spawns by throwing `ConcurrentModificationException`. This crashes the skill thread, aborting woodcut.

**Fix** in [CompanionOverheadHologramService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadHologramService.java): defensive re-enqueue at the service boundary. Before touching the ACTIVE map or calling `spawnStand`, check `server.isOnThread()`. If false, schedule a `server.execute(() -> show(bot, line, durationMs))` and return. Server-thread callers are unaffected (one boolean check).

This pattern protects every caller: `WoodcutSkill`, any other skill that labels its progress, and the dialogue-ack paths added in [33e81fe](https://github.com/) (follow/stop acks, context reactions — all of which eventually funnel through `CompanionOverheadHologramService.show` via `CompanionOverheadDialogueService.showOverheadLine`). No need to audit each call site.

**Why fix in the service and not the call site:** the pattern in this repo is "keep server-thread sections small: compute off-thread, execute atomically on-thread" (CLAUDE.md → Threading Rules). Fixing at each worker-thread call site is defensible but fragile — future contributors adding an overhead label from a new skill would trip the same crash. One guard in the service closes the category of bug.

## Dialogue backlog scaffolding: 27 new unvoiced events + triggers (2026-04-17)

Lays the mod-side groundwork for the April 2026 dialogue backlog (from `Minecraft Frens Feature Backlog March 2026.md`) so Chatterbox TTS generation can proceed. No audio files yet — each event is registered with an empty `sounds[]` so the triage tool will catch incoming OGGs as orphans and the handoff generator fills in the `sounds[]` arrays.

**Event registrations** (27 total) in [BotDialogueSounds.java](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java), [sounds.json](src/main/resources/assets/frens/sounds.json), and [BotDialoguePlayer.java](src/main/java/net/wcfcarolina13/ChatUtils/BotDialoguePlayer.java) subtitle map:

- **Context-triggered (14):** dig-straight-down; tree-punch (3 variants); pig-staring; underground/mines; end-ship sequence (3); TNT proximity (4); dirt-dig.
- **Ambient chatter (5):** saw-a-bird; had-a-plan; giant-statue; forgot-something; same-tree-lost.
- **Follow acks (3):** you-lead; let's-go; moving.
- **Stop acks (5):** I'll-stay-here; don't-be-too-long; see-ya; adiós; I'll-wait-here. ("standing by" reuses the existing `LINE_MODE_STAY_STANDING_BY`.)

**Trigger wiring** in [CompanionContextReactionService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionContextReactionService.java):

- New weighted pools + cooldown entries for each category.
- Tick-scan triggers: `tryPigStaring` (expanded-box pig search + forward-vector dot-product facing check; 1.5%/tick) and `tryUnderground` ("I yearn for the mines" — Y<40 + sky blocked + overworld-like dimension; 1.2%/tick).
- Block-break entry point: `onBotBlockBreak(bot, world, pos, state)` dispatches to dig-down (broken block is directly under the bot's feet; 35%), tree-punch (block matches `BlockTags.LOGS`; 30%), or dirt-dig (dirt/coarse-dirt/rooted-dirt/grass/podzol/mycelium; 8%). Hooked from the existing `PlayerBlockBreakEvents.AFTER` handler in [Frens.java](src/main/java/net/wcfcarolina13/Frens.java). Gated by `BotEventHandler.isRegisteredBot` so player-broken blocks don't trigger.
- Public acks: `playFollowAck(bot)` and `playStopAck(bot)` — cooldown-throttled (30s).

**Follow/stop command acks** in [modCommandRegistry.java](src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java): `executeFollow` plays follow-ack on successful mode transition; `executeFollowStop` and `executeStay` play stop-ack. Cooldown prevents spam from repeat commands; the existing voice-throttle in `BotDialoguePlayer` further guards against bursts.

**Deferred intentionally:**

- **End-ship look-at-me sequence** — the 3-line captain-now bit (`look-at-me` → look-check → `captain-now` OR leave → `ruined-joke`) needs a per-bot state machine tracking the player's look direction over several seconds. Out of scope for this pass; events + subtitles are scaffolded so TTS can still be recorded.
- **TNT proximity** — 4 lines conditional on "bot ordered to stand near primed TNT + player moving away while looking at the TNT." Same story: scaffolded, needs a dedicated detector later.
- **Entity interactions** (iron/copper golem banter, daisy gift, snowmen, creation reactions) — the backlog itself flags these as needing line text written first; not scaffolded.

**No version bump, no deploy** — the triage/handoff pipeline is the only consumer of these registrations until TTS arrives.

## PvP visibility: hide strangers' bases + walls from non-op list (2026-04-16)

For PvP-friendly servers, non-operators should not be able to browse other players' base/wall locations in the bases manager. Previously `sendBasesList` returned every base and wall regardless of owner. Now visibility is gated.

**Visibility rules (non-operators):**

- **Bases:** viewer owns it, viewer is allied with the owner, or the base is server-owned (`SERVER_OWNER_UUID` — Spawn, admin-claimed landmarks). Legacy/null-owner bases are **hidden** — admins can reassign to SERVER to expose as a public landmark.
- **Walls:** viewer owns it, viewer is in the wall's explicit `allowedOwnerUuids` (existing grant/revoke system — kept intact), or viewer is allied with the owner.
- **Villages:** always visible — per the "names are first come first serve" decision. Not filtered.
- **Operators:** bypass all of the above — see everything.

**Implementation in [BaseNetworkManager.java](src/main/java/net/wcfcarolina13/network/BaseNetworkManager.java):**

- `isBaseVisibleToViewer(base, viewerUuid)` and `isWallVisibleToViewer(wall, viewerUuid)` helpers.
- `sendBasesList` now computes `isOp` once and filters the base loop and the wall loop. Village and lodestone entries are untouched.

**What this does NOT change:**

- **Overlap rejection** still fires on create/resize. A non-op will be told they overlap *someone*'s base by name (so they know who to ally with), even though that base doesn't appear in their list. That's intentional — the reject message is the one place where the owner reveal is actionable.
- **Bot behavior** is unchanged. `findNearestBase` etc. still iterate every base in the world, so a bot may still treat a stranger's base as a "nearest fallback." Deliberate: visibility is a UI concern; bot navigation is a separate product question and worth revisiting independently.
- **Existing wall grant/revoke semantics** stay intact — the explicit grant list and alliance are now both entry points to wall visibility.

**Bump:** 1.1.25 → 1.1.26.

## Base system overhaul phase 5: overlap rejection on create/resize (2026-04-16)

Closes the loop on the base ownership + alliance work. When a player creates a new base or grows an existing one, the server now rejects the operation if it would overlap another player's (non-allied) base. This makes the alliance system actually useful: before you can plant a base on your ally's claim, they have to be your ally.

**Overlap model: sphere-sphere intersection.**

- Euclidean distance between centers vs sum of protection radii. `dist(c1, c2) < (r1 + r2)` → overlap.
- Legacy and server-owned bases are treated as "another owner" — nobody but admin can place a base inside the server-owned Spawn sphere.

**New helper in [BotHomeService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java):**

- `findOverlappingBase(server, world, center, radius, requesterUuid, excludeLabel)`: returns the first offending base, or empty. Skip conditions: same base (matched by normalized label — used during resize so a base doesn't conflict with itself), same owner, and {@link PlayerAllianceService#areAllied allied owners}. Operator check is caller-side.

**Gating in [BaseNetworkManager.java](src/main/java/net/wcfcarolina13/network/BaseNetworkManager.java):**

- `BaseSetPayload` (create): non-operator requests now pre-check overlap with `DEFAULT_BASE_PROTECTION_RADIUS`. On reject, send a formatted message and refresh the bases list.
- `BaseSetRadiusPayload` (resize): same check with the target radius, passing the current label as `excludeLabel` so the base doesn't collide with itself.
- Shared `formatOverlapReject(proposedCenter, proposedRadius, conflicting)` helper builds the error message. Computes minimum blocks to move — `ceil((r1 + r2) - dist)` — so the user gets actionable guidance instead of just "rejected". Owner name falls back to "the server" for server-owned and legacy bases.

**Example reject text:**

```
Overlaps Jake's base 'Fishpond'. Move at least 12 blocks farther (or ally with Jake) and try again.
```

**Design decisions:**

- **Operator override kept.** Admins can still create overlapping bases for moderation (e.g., staging a recovery base inside a griefer's claim). The check wraps in `if (!Frens.isOperator(player))`.
- **Self-overlap allowed.** A player can create multiple overlapping bases of their own — no rule against it. Rare, but not worth blocking.
- **Alliance is a gate on rejection, not a claim of co-ownership.** Allied players can place adjacent/overlapping bases freely, but they still can't *edit* each other's bases (Phase 3's `canEditBase` is unchanged). Whether to extend alliance to co-editing is a separate product decision.

**Bump:** 1.1.24 → 1.1.25.

## Base system overhaul phase 4a: player alliance backend + commands (2026-04-16)

Backend and command surface for reciprocal player alliances. This is the foundation for Phase 5's overlap rejection ("bases cannot overlap with another user's bases unless they're allied"). Proper UI tab with the flag icon is deferred to Phase 4b.

**Model — reciprocal, consent-based, symmetric:**

- One side invites, the other side accepts by sending an invite in the opposite direction. When both directions exist, the service immediately promotes to a confirmed bond.
- `revoke` breaks a confirmed bond (both ways) OR cancels an outgoing invite OR declines an incoming invite. Uniform verb covers all three exit paths.
- State lives in `config/frens/alliances.json`. Two maps:
  - `bondsByPlayer: Map<uuid, Set<uuid>>` — symmetric, both keys carry the other.
  - `pendingInvitesByRecipient: Map<recipientUuid, Set<senderUuid>>` — one direction only; the service scans to derive outgoing invites for UI/list.
- Name cache `nameByUuid` stores the display name at invite time so commands and UI can show names for offline players without hitting the server's user cache.

**New service:**

- [PlayerAllianceService.java](src/main/java/net/wcfcarolina13/GameAI/services/PlayerAllianceService.java): `invite`, `revoke`, `areAllied`, `snapshot(playerUuid)` (for listing), `lookupName(uuid)`. Result enums communicate outcome to callers: `InviteResult.{INVITED, ALLIED_NOW, ALREADY_BONDED, ALREADY_INVITED, SELF, INVALID}`, `RevokeResult.{BOND_BROKEN, INVITE_CANCELED, NOT_FOUND}`.

**New commands** (added to [modCommandRegistry.java](src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java)):

- `/bot ally invite <player>` — sends an invitation; auto-bonds on mutual consent; also serves as "accept" (if target already invited you, this completes the bond). Both sides get a system message on bond formation.
- `/bot ally revoke <player>` — breaks a confirmed bond or cancels/declines a pending invite. Notifies the counterparty when a bond is broken.
- `/bot ally list` — three rows in chat: Allies, Incoming invites, Outgoing invites. Shows names (from cache) rather than UUIDs.

**Not hooked into `canEditBase` yet:** Phase 3's `canEditBase` helper still returns false for non-owners even if they're allied. Whether allies should be able to edit each other's bases is a separate product decision — Phase 5 only needs `areAllied` for overlap-rejection, so I kept the co-editing question open.

**Bump:** 1.1.23 → 1.1.24.

## Base system overhaul phase 3: base ownership + server spawn base (2026-04-16)

Bases now have owners. Previously all bases were world-scoped with no attribution — any player could edit, rename, resize, or delete any other player's base. This phase stamps an owner on base creation, enforces owner-or-operator permission on edits, introduces a reserved `SERVER` owner sentinel for server-owned bases (used by the new auto-seeded Spawn base), and adds an admin-only owner reassign API.

**BotHomeService — data model + API:**

- `BaseEntry` record now has `ownerUuid` and `ownerName` fields. Only two in-service construction sites; no external callers construct it.
- `SavedBase` gained `ownerUuid` and `ownerName` fields. Gson defaults missing fields to `null` on legacy JSON load, so existing saves forward-migrate without touchup.
- New constants: `SERVER_OWNER_UUID = "SERVER"` (sentinel — not a valid MC UUID format, no collision risk), `SERVER_OWNER_NAME = "Server"`, `AUTO_SPAWN_BASE_LABEL = "Spawn"`.
- `addBase(...)` six-arg overload stamps owner; legacy four-arg overload delegates with null owner.
- `setBaseOwner(server, world, label, ownerUuid, ownerName)` — admin-only caller responsibility; service doesn't gate.
- `canEditBase(player, baseEntry)` permission helper. Rules: operators can edit anything; null/blank owner (legacy) = admin-only; `SERVER` owner = admin-only; owner UUID match = allowed. Alliance logic hooks in here in Phase 4.
- `initializeSpawnBaseIfNeeded(server, world)` — idempotent per world via new `WorldData.spawnBaseInitialized` sticky flag. On first call, creates a base labeled "Spawn" at `world.getSpawnPoint().getPos()` with owner=SERVER. If admin deletes it later, the flag stays true and we don't re-create — matches the user directive "admin deletion should stick".

**BaseNetworkManager — enforcement:**

- `BaseSetPayload` receiver now stamps the creating player as owner via `player.getUuid()` + `player.getName().getString()`.
- `BaseRemovePayload`, `BaseRenamePayload`, `BaseSetRadiusPayload` receivers now gate via new `checkBaseEditPermission(...)` helper. Helper returns true for non-bases (walls/villages) so existing wall-ownership paths keep working unchanged. On denial, sends `"Only <ownerName> (or an operator) can modify '<label>'."` to chat.
- `sendBasesList` now populates `BaseDto.ownerName` for bases from the `SavedBase.ownerName` field (previously null for bases — the field was only used for wall claims). `BaseManagerScreen` already falls back to "Unclaimed" when blank, so legacy bases display as "Unclaimed" until an admin reassigns.
- New `BaseSetOwnerPayload` receiver: admin-only reassign. Accepts `(label, newOwnerUuid, newOwnerName)` — blank UUID clears to legacy/unowned, `"SERVER"` marks server-owned.

**New payload:**

- [BaseSetOwnerPayload.java](src/main/java/net/wcfcarolina13/network/BaseSetOwnerPayload.java) (C2S, admin-only). Registered in [Frens.java](src/main/java/net/wcfcarolina13/Frens.java) alongside other base payloads.

**Frens.java — SERVER_STARTED hook:**

- After server-instance assignment, iterate all `server.getWorlds()` and call `initializeSpawnBaseIfNeeded(server, w)` wrapped in try/catch. The service itself filters to Overworld only. Throwables are logged but don't block server start.

**Scope notes:**

- UI to reassign ownership is deferred to a later phase. For now, admins can invoke via command/script if needed, or wait for UI in Phase 3.5.
- Legacy bases (null owner) are treated as admin-only. Design rationale: pre-ownership bases could belong to anyone; defaulting to admin-only prevents drive-by edits. Admins use the new `BaseSetOwnerPayload` to claim/reassign as needed.
- `BaseSetHomePayload` was intentionally left ungated: setting a base as "home for my bot" doesn't modify the base itself, only the bot's reference to it. Any bot owner can target any base.

**Bump:** 1.1.22 → 1.1.23.

## Base system overhaul phase 2: admin-tuneable max base radius (2026-04-16)

Server operators can now set a per-world ceiling on how large a base radius any user may configure. Previous behavior: hard-coded 128 in [BaseNetworkManager.java:189](src/main/java/net/wcfcarolina13/network/BaseNetworkManager.java#L189). New behavior: the cap is stored per-world and admin-editable via UI; the payload handler clamps to the current cap and notifies the user when a request exceeds it.

**Backend changes:**

- [BotHomeService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java): added `DEFAULT_MAX_BASE_RADIUS = 128` (parity with prior behavior), `HARD_MAX_BASE_RADIUS_LIMIT = 256` (absolute ceiling even admins can't exceed — interacts with animal-defense / nav-artifact scans that multiply the radius), and per-world `maxBaseRadius` state field on `WorldData`. Public getter/setter: `getMaxBaseRadius(server, world)` and `setMaxBaseRadius(server, world, value)`. Setter clamps, flushes JSON, returns the applied value.
- [BaseNetworkManager.java](src/main/java/net/wcfcarolina13/network/BaseNetworkManager.java): replaced hardcoded `128` in the `BaseSetRadiusPayload` receiver with dynamic `BotHomeService.getMaxBaseRadius(...)`. New receiver for `AdminMaxBaseRadiusPayload` handles both query (value < 0) and set (value ≥ 0); set path is operator-gated via `Frens.isOperator(player)`. Always responds with `AdminMaxBaseRadiusStatePayload` so the client UI always reflects authoritative state.

**New payloads:**

- [AdminMaxBaseRadiusPayload.java](src/main/java/net/wcfcarolina13/network/AdminMaxBaseRadiusPayload.java) (C2S): `int value`. Negative = query, non-negative = set request.
- [AdminMaxBaseRadiusStatePayload.java](src/main/java/net/wcfcarolina13/network/AdminMaxBaseRadiusStatePayload.java) (S2C): `int current, int hardLimit`. Hard limit tells the client UI the absolute ceiling.
- Registered in [Frens.java](src/main/java/net/wcfcarolina13/Frens.java) alongside other base payloads.

**UI surface:**

- [AdminWorldSettingsScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/AdminWorldSettingsScreen.java) (new): minimal modal with a numeric text field, Apply, Done. Sends query on open and set on apply. S2C receiver in [FrensClient.java](src/main/java/net/wcfcarolina13/FrensClient.java) forwards state into the screen via `pushState(current, hardLimit)`. Static `INSTANCE` pointer is cleared on close/removed to avoid leaking references across screen transitions.
- [BotPlayerInventoryScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java): new `OPEN_WORLD_SETTINGS` TopicAction, new "World Settings >" entry in the Admin tab's Controls section (next to "Player Permissions"). Visible and clickable only for admins (same gating as Player Permissions). Hover tooltip added.

**Scope notes:**

- Admin UI is intentionally minimalist for v1. Future Phase 2b candidates if more server-wide knobs accumulate: lift the screen to a multi-field form, add slider + numeric field combo, show the current default base radius and HARD limit inline.
- Legacy bases that already have a radius >cap keep their saved radius — the cap only affects *future* set/resize operations. Deliberate: shrinking existing bases retroactively would break player expectations without warning.

**Bump:** 1.1.21 → 1.1.22.

## Base system overhaul phase 1 + return-intent API (2026-04-16)

Three overlapping issues converged into a single phase: (1) fishing home-chest scan used a too-narrow y band (±3), (2) the default base protection radius (24) was too small for a typical survival base, (3) `resolveHomeTarget` treated the preferred home as absolute priority, so a bot fishing 40 blocks from a recently-slept-in base would walk 400 blocks back to the old preferred home at sunset.

**Changes in [BotHomeService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java):**

- `DEFAULT_BASE_PROTECTION_RADIUS`: 24 → 40. Covers a standard survival base with attached farms. Still well below the 128 UI cap. Field survey of typical base sizes drove the choice; smaller bases pay no cost, larger bases (castles, mega-bases) still tune manually.
- New `ReturnIntent` enum: `SUNSET_BED`, `COMMANDER_SIDE`, `BASE_CENTER_LAZY`, `DEFAULT`. Callers pick the mode that matches their situation.
- New overload `resolveHomeTarget(bot, intent)`. Legacy no-arg version is unchanged (stays on `DEFAULT` semantics) so un-migrated callers keep current behavior.
- `SUNSET_BED` implementation: **closest-validated candidate wins** among (lastSleep, preferredHome, nearestBase). lastSleep is validated by checking `state.isIn(BlockTags.BEDS)` at the stored position — if the chunk is loaded and the bed is gone, lastSleep is eliminated. Unloaded-chunk lastSleep is trusted but only wins if it's not wildly farther (≤ 2× distance) than loaded alternatives. This directly fixes the observed "bot walks back to old home even after sleeping at a new base" bug.
- `COMMANDER_SIDE` and `BASE_CENTER_LAZY` are stubbed to delegate to `BASE_CENTER_LAZY`'s preferred→slept→nearest chain; proper commander lookup and walkable-surface snap will land when the first caller migrates to each intent (avoids building infrastructure with no consumer).
- New `resolvePreferredHomeBaseEntry(bot)` returns the full `BaseEntry` (including the user-set radius). Used by callers that size scans/operations to the declared base extent.

**Changes in [FishingSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java):**

- Home chest scan y-span: ±3 → **±16** (covers basements + multi-story bases + watchtowers). Local ring scan: ±3 → **±8** (shore platforms, docks). Nearby scan: ±2 → **±6**. Iteration cost stays modest; the scan only runs when inventory is full.
- Home scan xz radius is now `max(baseEntry.radius(), HOME_CHEST_SCAN_RADIUS)`. A user who set a 60-block base gets a 60-block scan; a default-radius base still gets at least the 48-block floor.
- `handleFullInventory` now pulls `resolvePreferredHomeBaseEntry(bot)` first to get both position and radius; falls back to legacy `resolveHomeTarget(bot)` if no preferred base is set.

**Changes in [BotAutoReturnSunsetService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java):**

- Both sunset-path `resolveHomeTarget(bot)` calls (HUD notification at line 464, actual destination selection at line 1212) now pass `ReturnIntent.SUNSET_BED`. Bot will now prefer a recently-slept nearby bed over a distant preferred home at dusk.

**Bump:** 1.1.20 → 1.1.21.

**Not migrated in this phase:** 11+ other `resolveHomeTarget` callers (FarmSkill, WoodcutSkill, HuntSkill, WanderSkill, ToolRegistry, ReturnBaseStuckService, etc.) stay on legacy semantics until each is reviewed for the correct intent. Deliberate: migration without context could regress subtle behaviors.

## Fishing: chest-offload scanners ignored barrels (2026-04-16)

User-reported: at a newly set home base with two freshly placed **barrels** near the shore, the bot's fishing session hit full inventory and failed offload — session terminated with "Inventory full and no reachable chest." Log trace (`[13:52:58]` → `[13:52:59]`):

```
No nearby empty chest detected around 892, 63, 1362; crafting/placing chest...
Unable to locate or place a chest near 892, 63, 1362 during storage.
No home-base chest with space found within 48 blocks of 886, 64, 1400
```

Root cause: `ChestStoreService` (the deposit/walk-to side) already treats `Blocks.BARREL` as a valid container at every check site (lines 435, 547, 570, 1311). But `FishingSkill` has five **local** scanner predicates that filter on `CHEST`/`TRAPPED_CHEST` only — they never saw the user's barrels:

- `findChestWithSpaceNear` (home-base fallback scan)
- `scanForChestWithSpace` (local ring-search)
- `findNearbyChests` (local chest list before fallback)
- `scanForChests` (helper used during session setup)

Block-entity casts (`instanceof ChestBlockEntity`) also rejected `BarrelBlockEntity` even if the isOf check had passed.

**Fix:**
- Added `Blocks.BARREL` to all 5 `isOf` predicates in FishingSkill's local scanners.
- Swapped `ChestBlockEntity` import for `net.minecraft.inventory.Inventory` — the common interface both `ChestBlockEntity` and `BarrelBlockEntity` implement. `chestHasSpace(Inventory)` works unchanged against either.
- `ChestStoreService.depositMatching*` downstream already uses `Inventory` as the gate (lines 850, 1336), so barrels deposit fine once discovered.

Left `placeChestNearby`'s post-placement check (`isOf(Blocks.CHEST)`) alone — it verifies a placement made with `Items.CHEST`, so barrel-handling there is a non-sequitur.

**Bump:** 1.1.19 → 1.1.20.

## Fishing: rod-state race at sunset abort (2026-04-16)

User-reported regression: after a sunset auto-return that walked the bot home with the line still extended, all subsequent fishing sessions got zero bites. Bot appeared to cast only when told to stop; bites went un-reeled; recasts left no visible bobber.

Root cause traced from Prism log `11:18:27` → `11:22:19`: **double-click race in the sunset-abort path**.

Sequence:

1. `BotAutoReturnSunsetService` at tick 12000 calls `TaskService.forceAbort(bot, ...)` which sets `ABORT_LATCH` and issues `Thread.interrupt()` on the skill's worker thread.
2. Worker was inside `waitForBite`. Next abort-poll exits false.
3. [FishingSkill.java:524](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java#L524) `useSelectedItem(bot) // Retract` runs. Internally calls `runOnServerThread` which queues the reel runnable, then `future.get` throws `InterruptedException` immediately (worker's interrupt flag is set) and returns false. **Reel runnable stays on the server queue.**
4. Worker's `continue` → next iteration → sunset branch at line 340 → line 343 `retractBobberIfPresent`. `findActiveBobber` scans the world. **The reel runnable hasn't drained yet** (server tick not fired). Bobber is still present.
5. `retractBobberIfPresent` calls `useSelectedItem` **again** — queues a second reel runnable.
6. Server thread drains both runnables in order: #1 reels (fishHook → null, bobber discarded). #2 sees fishHook==null → interprets as CAST → spawns a fresh bobber, sets fishHook.
7. Session saves for sunrise. Bot walks home with a stray bobber in the water (matches user's "line still extended" observation).

On sunrise resume, `bot.fishHook` points to that stray bobber. First iteration's `useSelectedItem // Cast` reels instead. `waitForBite` polls a non-existent bobber. User `/bot stop` → line 524 fires → fishHook==null → CASTS (matches user's "cast when told to stop"). Ping-pong persists across sessions until bobber auto-despawns or user takes the rod.

**Fix:** replace the unconditional vanilla *toggle* (`stack.use()` which casts-if-null-reels-if-not) with intent-explicit helpers gated on `bot.fishHook` as the source of truth:

- `hasActiveFishHook(bot)` — reads `bot.fishHook != null && !bot.fishHook.isRemoved()`. Source of truth; reflects vanilla's own cast/reel bookkeeping without lagging behind queued runnables.
- `reelRod(bot)` — no-op if `fishHook==null`. Otherwise dispatches rod-use and polls `fishHook` up to 1s waiting for vanilla to process the reel. Debounced per-bot (1500ms) so back-to-back retract calls (line 524 + sunset block's `retractBobberIfPresent`) cannot queue a second runnable that would be processed as a CAST.
- `castRod(bot)` — if `fishHook!=null`, reel first via `reelRod` (with wait). Then cast. If fishHook is still non-null after the reel (server stalled, thread interrupted), skip the cast rather than risk another vanilla-side inversion.
- `retractBobberIfPresent(bot)` now delegates to `reelRod(bot)`.
- Skill entry at [line 288](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java#L288): after `ensureHotbarItem`, if `hasActiveFishHook(bot)` then reel before starting the session. Cleans stale state from any prior session's aborted retract, even if we can't retroactively know how the stray bobber got there.
- All four unconditional `useSelectedItem` call-sites in the fishing loop (line 490 cast, line 514 bad-throw retract, line 524 no-bite retract, line 534 on-bite reel) replaced with the intent-explicit helpers.

Does **not** touch `useSelectedItem` in `BotActions` or elsewhere — this is a fishing-skill-specific fix. Other skills that use the rod (none currently) would need similar treatment.

Does **not** change `findActiveBobber` — it's still used for settle-check geometry (line 494, bad-throw detection). Its weakness was not bobber detection per se but the timing gap between world scan and queued runnable execution. Using `fishHook` for state decisions sidesteps this.

Watch on next sunset: after forceAbort fires, the new `LAST_REEL_DISPATCH_MS` debounce + `awaitFishHookClear` polling means the worker thread blocks (up to 1s) until the server thread confirms fishHook is null. That cleanly serialises the reel. If the debounce window (1500ms) expires without clearance, we give up — that'd be a server-thread stall and should be investigated separately.

## Door passage part 14: stop raycast heuristic from discarding pathfinder output (2026-04-16)

The 1.1.17 diagnostic caught it. At `09:28:53→09:29:02` Jake was pinned inside a closed oak_door cell at `(888, 64, 1405)` — `feet=888, 64, 1405=minecraft:oak_door`, `reason=feet-not-passable`, repeated ~22 times over 9 seconds. Bot had walked the spiral staircase up through the tower interior trying to reach the commander (who moved outside), arrived at the south door, and got stuck because `isPassableForMovement(oak_door with OPEN=false)` returns false, so every velocity impulse was rejected before `addVelocity` could run. `/rescue` freed him.

The deeper pattern: **`commander-route clear` (every ~2s during normal follow) was wiping `FOLLOW_WAYPOINTS` based on a 2-height raycast**. A raycast can miss thin door panels, stair corners, and box-boundary precision obstacles that the pathfinder's cell-by-cell analysis caught. Once waypoints are wiped, the follow tick falls through to `followInputStep` (direct-pursuit velocity push) which has no planner help. If the target cell contains a closed door or a box-boundary wedge, `canOccupyPosition` rejects, bot freezes. The raycast is weaker than the planner and should not be allowed to discard planner output. Architectural defect flagged at end of prior session; this fix addresses it.

**Three changes:**

1. [BotEventHandler.shouldFollowDoor()](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) — `commander-route clear` no longer removes `FOLLOW_WAYPOINTS`. The pathfinder's plan persists until consumed normally (waypoint-reached), the commander moves far enough to trigger replan (`movedSq >= 256`), or stagnancy fires a replan (`stagnantTicks >= 10`). Door-plan scratch state still gets wiped — that's raycast-scoped and rebuilds next tick. Logged as `commander-route clear (waypoints preserved)` so the intent is visible in Prism logs.

2. [BotActions.canOccupyPosition()](src/main/java/net/wcfcarolina13/GameAI/BotActions.java) — short-circuit when the target feet cell equals the bot's current feet cell. Sub-cell motion within the bot's own cell is always legal: vanilla physics has already accepted the bot at its current position, so a tiny nudge that stays in the same cell cannot violate any invariant our stricter passability check enforces. This frees a bot pinned in any wedge cell — closed door, stair-adjacent precision boundary, etc. — to accumulate velocity and eventually cross a cell boundary, where vanilla `Entity.move()` takes over with its own collision check. Strictly narrower than 1.1.15's reverted escape hatch (which allowed ENTRY into new standable cells — too permissive); this only allows WITHIN-CELL motion.

3. [BotActions.applyMovementInput()](src/main/java/net/wcfcarolina13/GameAI/BotActions.java) — before the `canOccupyPosition` gate, if the bot's feet blockstate is a closed `DoorBlock`, dispatch `MovementService.tryOpenDoorAt(bot, feet)`. Self-heals the wedge state directly: the door opens, vanilla physics carries the bot through on the next tick. Throttled per-bot to once per 20 ticks (1s). Honors existing iron-door, locked, and cooldown gates in `tryOpenDoorAt`. New log line `auto-open-current-door` records every attempt with the block registry id and the open/fail result.

Does **not** touch the door-plan state machine (door-corner/adjacent/escape/ray approach planning) — that still handles the "bot approaching a closed door" case where the bot is OUTSIDE the door cell. This fix handles the "bot inside a closed-door cell" wedge only.

Does **not** change the other two waypoint-drop paths (`drop-waypoints: long-range target` at >30 blocks, `drop-waypoints: come-mode flat terrain direct-clear`) — those are scoped to specific contexts where the optimization is arguably correct. If they misbehave, revisit separately.

Risks to watch:
- Stale waypoints dragging the bot slightly off-direct when the commander is close. Mitigated by the commander-moved replan (`movedSq >= 256`) and waypoint consumption via `WAYPOINT_REACH_SQ`. Likely cosmetic if it shows up.
- Auto-open firing on a legitimately-locked door. Shouldn't happen — `tryOpenDoorAt` checks `LockableBlockService.isLocked` and returns false; we log `opened=false` and don't retry until the throttle lapses.
- A bot auto-opening a door and then immediately auto-closing. The `scheduleDoorClose` inside `tryOpenDoorAt` already handles this by deferring close until the bot has cleared the doorway, so no regression expected.

## Door passage part 13: diagnostic for direct-pursuit rejection (2026-04-16)

Last session ended with a residual issue: bot at `(888, 64, 1397)` stuck for ~11s with no door plan active (`commander-route clear` was clearing waypoints every ~2s), but the outer direct-pursuit path was silently rejected by `canOccupyPosition`. The task file says don't guess which gate — instrument and run it once in-game.

Added `applyMovementInput-reject` log channel in [BotActions](src/main/java/net/wcfcarolina13/GameAI/BotActions.java). When `canOccupyPosition` rejects a velocity impulse, the new `diagnoseOccupancyRejection` helper re-runs the three gates and logs the specific failure reason:

- `feet-not-passable` — feet cell's blockstate failed `isPassableForMovement` (the air/!blocksMovement + openable + WalkablePartialBlocks.isPathable ladder).
- `head-not-passable` — same, for the cell above feet.
- `box-clear-rejected` — bounding-box AABB intersected a non-walkable-partial collision shape; log includes the offending cell position and its block registry id (e.g., `minecraft:oak_stairs`).
- `race-space-now-empty` — geometry changed between the original gate and the diag re-run.

Each entry includes bot position, target cell, both `feet`/`head` cell positions with block registry ids, and the specific offending cell for box-clear rejections.

Throttled per-bot to once per 10 server ticks (~0.5s) to keep a proper stall producing a steady heartbeat of ~22 entries over 11s without flooding the log during normal traversal. Only instruments the `applyMovementInput` call site — the task note specifically identified outer direct-pursuit as the failing path, and `FollowMovementService` already has the 1.1.12 `WalkablePartialBlocks.isPathable` fix for its own passability check.

This is an evidence-collection build, not a fix. Once we see which gate + which block rejects, we can make a targeted fix without further guessing. Rule of thumb from last session: 3+ fix attempts already failed on door passage, so the next change has to be evidence-driven.

## Door passage part 12: revert 1.1.15’s auto-step escape hatch (2026-04-16)

1.1.15’s escape hatch in `canOccupyPosition` was too permissive. It allowed the bot to attempt movement into any cell where the feet block was `isStandable` + cells above were passable, trusting vanilla `Entity.move()` auto-step to handle the rise. In practice, vanilla couldn’t always auto-step (blocks taller than `stepHeight`, stair orientations where the back-top-half blocks further forward motion after the step), so the bot pushed into partials, got zero-velocity on collision, and accumulated stuck time — which eventually triggered rescue-mining behavior. Reverted `canOccupyPosition` to 1.1.14 behavior.

Note: the "bot mining stairs on spawn" the user observed was NOT caused by 1.1.15 — it was pre-existing shelter-breakfree logic firing at `08:39:17` because the bot spawned indoors ("no sky"). That’s a separate, longstanding behavior that auto-mines upward when a bot respawns inside an enclosed structure. Worth revisiting separately but out of scope for door-passage work.

## Door passage part 11: auto-step escape hatch in canOccupyPosition (2026-04-16)

The 1.1.14 stuck-jump is a workaround, not a root-cause fix. The actual problem: [BotActions.canOccupyPosition](src/main/java/net/wcfcarolina13/GameAI/BotActions.java) rejected any target position where the feet cell contained a non-pathable partial block (slab, stair, snow layer). Vanilla `Entity.move()` has built-in auto-step — when horizontal motion would collide with a partial up to `stepHeight` (0.6 blocks), vanilla applies a Y adjustment automatically. But our pre-check rejected BEFORE `bot.addVelocity()` ran, so vanilla’s auto-step never got a chance.

That’s why pressure plates initially stalled, and why stairs/slabs at the doorway threshold kept stalling even after 1.1.12 — players walk over these natively via auto-step; our pre-check was stricter than vanilla physics.

**Fix:** add a second branch to `canOccupyPosition`. If `hasMovementClearance(feet)` fails, but the feet cell is `WalkablePartialBlocks.isStandable` (slab/stair/snow/carpet/plate/etc.) AND the two cells above are `isPassableForMovement`, allow the impulse. Vanilla `Entity.move()` then runs its auto-step and either raises the bot onto the partial or zeros the velocity on collision — no wasted impulse, no false rejection.

Bot should now walk smoothly onto slabs, stairs, snow layers, and any other standable partial without needing a jump. The 1.1.14 stuck-jump remains as a belt-and-suspenders fallback for edge cases the auto-step path misses.

## Door passage part 10: stuck-near-doorway auto-jump (2026-04-16)

After 1.1.13 the bot was getting stuck 2 blocks north of the northern tower door, unable to walk onto the approach cell — same symptom that vanished when the commander jumped. Log at 07:41:13→07:41:26 shows bot stagnant at `(888, 64, 1397)` for 13 seconds, then at `07:41:26` briefly at `Y=65` (mid-jump), and suddenly moving freely through the door. The jump was triggered by the commander’s own Y going to 65 — `applyHumanLikeForwardInput` computes `dy = targetPos.y - bot.getY() > 0.6` and calls `BotActions.jump(bot)` unconditionally. Without that, bot sat there.

Screenshot evidence from the user: the tower has a spiral staircase made of stone brick stairs wrapping a cobblestone column, plus partial blocks at the doorway threshold. Stairs/slabs/trapdoors at head level fail `isPassableForMovement` — they’re in `WalkablePartialBlocks.isStandable` (bot can stand on them) but not `isPathable` (bot can’t walk into a cell where they occupy head-level space). `hasMovementClearance` rejects, `canOccupyPosition` bails out of `applyMovementInput`, bot freezes.

**Fix:** generalize the 1.1.9 forced-jump. Instead of only jumping when `plan.stepping() && doorOpen`, also jump whenever the door-plan’s stuck counter reaches `FOLLOW_DOOR_STUCK_JUMP_TICKS = 8` (0.4s of no block-position change). This mirrors exactly what the user does manually ("bot gets stuck until I jump a couple times"). A jump arc lifts the bot past head-level partial blocks and the forward impulse carries it through. After the jump, `bot.isOnGround()` is false for ~10 ticks (jump arc), so no jump-spam — bot bounces once per arc. If still stuck at `FOLLOW_DOOR_STUCK_ABORT_TICKS = 24`, the plan aborts as before.

No changes to the `canOccupyPosition` classifier — `isPassableForMovement` correctly treats slabs/stairs as non-passable at head level, because admitting them there would let the bot walk into walls it can’t fit through (stairs aren’t ALWAYS walkable-through, only vertically walk-onto). Jump-and-clear is the right shape: it mimics a player realizing "I’m stuck on this threshold, hop over it."

## Door passage part 9: tick-persistent door-open + confirmed-open gate on stepping flip (2026-04-15)

After 1.1.12 the pressure-plate stall is fixed, but door passage is still spotty — sometimes door opens and bot passes, sometimes bot stalls at the approach cell pushing into a closed door. Log evidence at 17:55:16-22: plan flipped to `stepping=true` but NO `door-open success` or `door-open failed` was logged for door 1395 during that window — the `tryOpenDoorAt` call was silently throttled by `doorAttemptAllowed` (1500ms per-door rate limit) or the interact silently failed, yet the plan advanced anyway.

Two root causes:
1. **Door-open only fires in `!stepping` phase.** Once plan flips to `stepping=true`, the tick-loop at lines 4958-4964 skips the `tryOpenDoorAt` call entirely. If the door auto-closes mid-transit (scheduler fired) or the initial open was silently dropped (throttle / interact rejection), the bot pushes against a closed door for the remaining plan TTL.
2. **Stepping flip trusts `tryOpenDoorAt` return instead of verifying state.** The old code: `boolean opened = tryOpenDoorAt(bot, doorBase) || isOpen`. If `tryOpenDoorAt` silently returned false (throttled) but `isOpen` was stale-true from an earlier read, `opened` was true — plan flipped to `stepping=true` with a closed door.

**Fix 1:** Removed the `!plan.stepping()` guard on the door-open block. Now `tryOpenDoorAt` is called **every tick** the door is closed AND bot is within 2 blocks, regardless of stepping phase. If the door closed behind the bot mid-transit, the next tick re-opens it. `doorAttemptAllowed` throttle (1500ms) prevents spam, but ensures at least one real attempt per 1.5s. `doorOpen` flag is re-checked from world state AFTER the open attempt so the jump-while-stepping logic has an accurate signal.

**Fix 2:** Removed the `tryOpenDoorAt` return-value trust from the stepping-flip. Now the flip ONLY commits when `doorOpen` (re-read from world state by the tick-loop above) is true. If the door isn’t open yet, the plan stays at `stepping=false` and the tick-loop keeps retrying. No more "flip then push into closed door" race.

Also removed the old `door-open failed` recovery block (retreat 2 blocks for re-approach angle) — that path could oscillate if the open kept failing, and the tick-persistent retry handles it more robustly.

## Door passage part 8: THE REAL FIX — pressure plate’s blocksMovement() returns true in 1.21.11 (2026-04-15)

The 1.1.11 diagnostic build caught it immediately:

```
applyMovementInput-reject: feetCell=888, 64, 1396 feetState=Oak Pressure Plate
    hasClearance=false isSpaceEmpty=false
```

**`hasMovementClearance` was returning false for the pressure plate cell.** Which means the `isPassableForMovement` helper I wrote in 1.1.5/1.1.6 was classifying pressure plates as **non-passable** — because I assumed `state.blocksMovement()` returns `false` for plates (the standard "non-blocking block" signal). **In 1.21.11 mappings it returns `true` for pressure plates.**

So every movement input trying to step INTO a pressure plate cell was bailed out at the very first gate (line 259 of `applyMovementInput`). The 1.1.10 walkable-partial fallback in `canOccupyPosition` was never reached — `hasMovementClearance` returned false before `isSpaceEmpty` was ever called. The 1.1.9 forced-jump-while-stepping fix likewise — once the bot wasn't on the ground (jumping), subsequent ticks' horizontal impulse was still rejected by the plate cell check, so the bot didn't clear the doorway horizontally during the jump arc.

All the 1.1.5 → 1.1.10 door-passage work was built on top of a broken classifier. My mistake: I trusted my mental model of `blocksMovement()` instead of checking it.

**Fix:** `isPassableForMovement` now consults [WalkablePartialBlocks.isPathable](src/main/java/net/wcfcarolina13/GameAI/services/WalkablePartialBlocks.java) as a third gate, after the air/non-blocking check and the openable-with-OPEN check. `WalkablePartialBlocks.isPathable` already correctly handles pressure plates (via `AbstractPressurePlateBlock`), carpets, rails, tripwire, lily pad, and anything with collision max Y ≤ 0.125 — the exact set of "has collision but doesn't obstruct horizontal motion" blocks we care about. Signature changed to take `(state, world, pos)` so `WalkablePartialBlocks.isPathable` can do its thin-partial fallback via `getCollisionShape`. Same change applied symmetrically to `FollowMovementService.isPassableForMovement`.

**Unexpected bonus:** this also fixes the "head=Torch → isSpaceEmpty=false" case (torches have small collision), since torches pass the ≤ 0.125 fallback in `WalkablePartialBlocks.isPathable`.

Diagnostic logging from 1.1.11 was removed — it served its purpose. `DOOR_DIAG_BOTS` machinery removed from `BotActions`; `FOLLOW_DOOR_DIAG_LOG_MS` kept in `FollowStateService` (unused for now; if the issue recurs we can re-enable without needing code changes).

## Door passage part 7: diagnostic instrumentation (2026-04-15)

After 1.1.10 the bot steps ONTO the pressure plate (so the plate-walkable-partial fix works) but still won't step through the door from the plate. Both the 1.1.9 forced-jump-while-stepping fix AND the 1.1.10 walkable-partial-aware `canOccupyPosition` should allow this. Something silent is still rejecting the motion.

Systematic debugging rule: stop theorizing, add evidence. This build adds two diagnostic log channels — NO behavioral changes:

- **`door-step-diag`** in [tickFollowDoorPlan](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) — emitted once per second per bot while the door plan is `stepping=true` and the door is open. Reports: stepping flag, doorOpen flag, onGround flag, bot's exact (x, y, z), velocity vector, goal, doorBase.
- **`applyMovementInput-reject`** in [BotActions.applyMovementInput](src/main/java/net/wcfcarolina13/GameAI/BotActions.java) — emitted once per second per bot when `canOccupyPosition` rejects an impulse, but ONLY for bots whose UUID is in the `DOOR_DIAG_BOTS` set (toggled on/off by tickFollowDoorPlan based on whether the plan is actively stepping). Reports: bot pos, proposed new pos, impulse, feet BlockPos, feet block name, head block name, `hasMovementClearance` result, `isSpaceEmpty` result.

Together these will show whether the jump is firing, whether the bot is receiving impulse, and if rejected, exactly which collision check is blocking.

## Door passage part 6: pressure plate stops stalling bot's approach to its own doorway (2026-04-15)

User observation during 1.1.9 testing: after a rescue-teleport placed the bot 2 blocks north of an open door, the bot stood on the pressure plate approach cell staring at the commander and never walked through. Log evidence (`latest.log` 14:20:17 → 14:20:40): bot at (888, 64, 1403), door at z=1405 open for the full 23 seconds, waypoint `first=888, 64, 1404` (approach cell with pressure plate) replanned repeatedly, `directBlocked=false`, `commander-route clear` firing — yet bot's BlockPos never advanced. No door plan was active (that code path would have invoked the 1.1.9 forced-jump), just generic waypoint follow.

**Root cause:** `BotActions.canOccupyPosition` pre-checks with `world.isSpaceEmpty(bot, targetBox)`. A pressure plate has a 1-pixel collision shape at y=[0, 0.0625]. When the bot's bounding box (minY = 64.0, maxY = 65.8) tries to step onto a plate cell (plate at y=[64.0, 64.0625]), vanilla `Box#intersects` uses strict less-than on all six axes:
- `botMinY < plateMaxY` → `64.0 < 64.0625` ✓
- `botMaxY > plateMinY` → `65.8 > 64.0` ✓

Y axis overlaps even though the bot is physically STANDING on the ground. When the impulse shifts the box slightly in Z/X to enter the plate's horizontal footprint, all six axes return true → `isSpaceEmpty` returns false → impulse bailed out at [BotActions.java:259](src/main/java/net/wcfcarolina13/GameAI/BotActions.java#L259) without applying velocity. Vanilla movement physics would have lifted the bot 1/16 onto the plate smoothly. Our pre-check is stricter than vanilla.

Same problem would trigger for carpets (1/16), snow layers 1-2 (1/16, 2/16), rails (2/16), and the thin collision strips on open doors/gates.

**Fix:** In `canOccupyPosition`, when `world.isSpaceEmpty` rejects, run a second pass via new `isBoxClearIgnoringWalkablePartials` helper. It iterates block cells that overlap the target box and skips any whose state satisfies `isPassableForMovement` (air, non-blocking blocks, open doors/gates/trapdoors). For the non-skipped blocks it does exact `VoxelShape.getBoundingBoxes() ⟷ targetBox` intersection tests — so real walls, closed doors, and full-cube obstacles still correctly reject.

Reuses the `isPassableForMovement` helper added in 1.1.5/1.1.6; pressure plates pass via `!blocksMovement()`, carpet/rail/tripwire/lilypad same, open doors/gates via the OPEN state check. This means the pressure plate, carpet, snow-1, and rail cases all share the same fix without per-block special-casing.

Trade-off: the custom helper iterates block collisions only — entity collisions aren't considered. In the follow use case, personal-space guards already prevent the bot from walking into the commander. Other contexts (bot inside a mob pile) might see the bot push slightly closer before vanilla physics catches up, but this is a minor regression at worst and matches how vanilla players themselves behave.

## Door passage part 5: jump when actively stepping through an open doorway (2026-04-15)

User observation while testing 1.1.8: "the bot has a much easier time following me through doors if I jump or am a bit elevated. If we're on roughly the same horizon or the bot's standing on something like a pressure plate, it gets stuck until teleportation saves it."

Traced the asymmetry:
- When commander is elevated by ≥0.6 blocks, `FollowMovementService.applyHumanLikeForwardInput` computes `dy > 0.6` and calls `BotActions.jump(bot)` **unconditionally**.
- When commander is level with bot, the same function falls to `BotActions.autoJumpIfNeeded(bot)`, which has an explicit door-skip at [BotActions.java:1894](src/main/java/net/wcfcarolina13/GameAI/BotActions.java#L1894): `if (frontState.getBlock() instanceof DoorBlock) { return; // doors handled elsewhere; don't bunny-hop at them }`. Even without that skip, its `headSpace` probe rejects open doors (the door's 3-pixel upper-half strip isn't an empty collision shape).

That asymmetry is why the bot appears to "linger" at the threshold on level ground: it never jumps, and whatever is blocking sustained forward motion (pressure plate at y=0.0625 boundary? door's thin strip clipping the bot's box at a fractional X? collision engine friction?) eats enough of the impulse that the bot can't traverse the 1-block cell before the plan TTL expires or `commander-route clear` strips the plan.

**Fix:** in [tickFollowDoorPlan](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) the "doors handled elsewhere" comment from the autoJumpIfNeeded skip now actually means something — when the plan is `stepping=true`, the door is open, and the bot is on the ground, force `BotActions.jump(bot)` each tick instead of deferring to autoJumpIfNeeded. Matches vanilla villager behavior: they don't hesitate at doorways they're actively crossing. Non-stepping phases (approach side, waiting for door to open) still use the existing autoJump logic.

The fix is narrow — it only fires while a door plan is ACTIVELY stepping through an OPEN door. It does not change behavior at non-doorway obstacles, fence gates that are closed, or cases without a door plan.

## Door passage part 4: wrong-side check applied to all FOUR door-plan creation sites (2026-04-15)

1.1.7 log (`latest.log` 13:12:20 onward) shows the wrong-side-of-door check firing correctly in one place (`avoid-door: reason=wrong-side-of-door` at 13:13:29) but still letting backward-pointing plans slip through in two other places: `door-escape` (line ~4520) and `door-ray` (line ~4570). Both built plans with `approach` on the bot's side and `step` on the far side of a door that was BEHIND the bot relative to the commander, sending the bot backward into a 16-second stall before the `stuck=24` abort eventually kicked in and wolf-teleport rescued it.

**Fix:** extracted [isDoorPlanWrongSide(approachPos, stepPos, goalBlock)](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) as a shared helper and applied it at **all four** door-plan creation sites:

1. `door-corner` (line ~4658) — previously had the inline check; now uses the shared helper.
2. `door-escape` (line ~4520) — stagnant-triggered escape when directly blocked; now gated.
3. `door-ray` (line ~4558) — raycast-detected door between bot and goal; now gated.
4. `door-adjacent` (line ~4465) — bot adjacent to a closed door; now gated.

Each site now calls `avoidDoorFor(doorBase, 5_000L, "wrong-side-of-door")` + emits a `skip-door-*: wrong side` log entry + refuses to build the plan. The bot falls through to direct pursuit / wolf-teleport instead of committing 5-10 seconds to a backward door transit.

This is a pure additive patch — the three existing paths still work normally for legitimate wrong-room scenarios where the door is actually between bot and commander.

## Door passage part 3: villager-inspired simplification of follow-mode door plan (2026-04-15)

After 1.1.6 the bot physically passes through open doors but lingers in the doorway for 8-15 seconds before either escaping or being wolf-teleported — exactly as the user reported. Log evidence (`latest.log` 11:59:31-45) shows the door-plan state machine **oscillating with itself** after a successful crossing: plan rebuilds with flipped approach/step orientation each tick, stepping flag toggles true↔false within 2-second windows, door-recovery retreats the bot back, then the plan immediately re-engages the same door.

User observation: "Villagers never have issues pathfinding through doors." That's the right reference point. Vanilla villagers use a single path (with door tiles as plain waypoints), a 30-line `InteractWithDoorGoal` that just opens the door when the villager reaches it, and never retreat when stuck — they either push forward or replan. They don't have an "approach → open → step" phase machine and don't have 7 competing subsystems bidding for control each tick.

Three targeted fixes applied to mimic villager behavior without a full rewrite:

- **Deleted the door-recovery retreat in [tickFollowDoorPlan](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L4905).** The old "stuck for 8 ticks → retreat 2-3 blocks away from door" logic was the primary oscillation source: every time bot transit took longer than 0.4s, recovery pushed it backward, breaking momentum. Replaced with "stuck for 24 ticks → cancel plan entirely, avoid this door for 12s, let generic follow / direct pursuit / wolf-teleport take over." Matches villager "push or abandon" behavior. `BotActions.stop(bot)` is no longer called in the stuck path.
- **Gated door-corner plan rebuild with a "wrong side" check** in [the door-subgoal branch](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L4609). Before creating a new plan, compare `approachPos.squaredDistanceTo(commanderBlock)` with `stepThroughPos.squaredDistanceTo(commanderBlock)`. If the approach (bot's current side) is already closer to the commander than the step pos is, the door is BEHIND the bot and rebuilding a plan for it would send the bot backward. Instead avoid that door for 5s and fall through. This kills the mirror-image plan that at 11:59:33 would've sent the bot back south through the same door it just crossed going north.
- **Open doors/gates/trapdoors no longer block `isDirectRouteBlocked`** at [line 4734+](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L4722). The raycast uses both COLLIDER and OUTLINE shape types; an open door's OUTLINE can clip the ray even when the passage is physically clear. When the ray hits a block, check `Properties.OPEN == true` on DoorBlock/FenceGateBlock/TrapdoorBlock — if so, skip it and continue the ray. This removes false `directBlocked=true` signals that keep the door-plan alive after the bot has crossed, unblocking the `commander-route clear` early-exit path.

Shared helper `isOpenOpenable(BlockState)` added to BotEventHandler so the three openable classes are classified identically wherever openness-as-passability matters.

### Historical context (user note)

The retreat logic was originally added months ago to solve shelter-escape / construction scenarios where the bot got trapped inside a corner of a structure it couldn't mine through and needed doors as the way out. Without retreat, the bot would sometimes oscillate between inside/outside targets of a partially-built shelter. That use case is now broken by this change — but it's a lesser-used feature and will be re-addressed separately when shelter/construction work resumes. For the follow use case (by far the more common path), vanilla-villager-style "push or abandon" produces dramatically better behavior.

## Door passage part 2: same blocksMovement() bug in FollowMovementService.hasTwoHighClearance (2026-04-15)

- **Symptom after 1.1.5 deploy:** bot can now enter the door cell (my earlier `BotActions.hasMovementClearance` fix worked — log 11:43:01→11:43:02 shows bot transiting z=1394 → z=1396 through door at z=1395), but then stalls at the step cell for ~16 seconds before finally escaping. User still has to teleport-rescue.
- **Root cause:** [FollowMovementService.hasTwoHighClearance](src/main/java/net/wcfcarolina13/GameAI/services/FollowMovementService.java#L706) had the same `blocksMovement()` bug. This helper is consulted by four separate code paths: narrow-passage alignment, chokepoint detection, the local-obstacle nudge, and the dangerous-drop probe. Any of them treating the door cell as "blocked" when it's actually open corrupts routing decisions around doorways.
- **Fix:** Extracted `isPassableForMovement(BlockState)` helper — identical structure to the one added in `BotActions` — and rewrote `hasTwoHighClearance` to use it. Sequence preserves all existing carpet/plate/tripwire behavior, then treats `DoorBlock`/`FenceGateBlock`/`TrapdoorBlock` instances with `Properties.OPEN == true` as passable. Every caller (narrow-passage align, chokepoint, nudge, drop-guard) benefits automatically.
- **Uncertainty:** Haven't proven this is the full story. The log shows bot stagnant at z=1396 for 16 seconds after crossing the door, with multiple code paths firing ("commander-route clear", waypoint replans every 2-3 seconds, "door-corner" at stagnant=11). The `hasTwoHighClearance` fix is necessary but may not be sufficient — if stuck persists after deploy, next step is to add diagnostic logging to `applyMovementInput` / `applyHumanLikeForwardInput` showing whether impulse is being applied and whether the velocity is reaching target.

## Door passage: bot stops refusing to step into its own open doors (2026-04-15)

- **Symptom:** Bot opens a door, then hovers one block away on the approach side and can't advance. Every 1.5s the log repeats `door-close wait: bot too close` (door-close scheduler retrying), interspersed with `door-recovery: goal=<2 blocks back>` until the plan TTL expires, after which the bot sits at the approach cell indefinitely. Breaking the door and its pressure plate lets the bot pass. Log evidence from `latest.log` at 11:09:51–11:10:30: bot at (888, 64, 1406), door at (888, 64, 1405) open, target commander south past the door; distance stuck at 6.74 for ~36 seconds with no position change.
- **Root cause:** [BotActions.hasMovementClearance](src/main/java/net/wcfcarolina13/GameAI/BotActions.java#L1774) uses `BlockState.blocksMovement()` as its passability test. That method is a **static block-class flag** set at registration time — for doors, gates, and trapdoors it returns `true` regardless of open/closed state. So when `applyMovementInput` calls `canOccupyPosition` on the door cell, `hasMovementClearance` says "blocked", `canOccupyPosition` returns false, and `applyMovementInput` early-exits without applying velocity. Bot never enters the door cell. `directBlocked=false` in the log was misleading because that check used a different code path.
- **Prior art:** Exact same bug pattern was fixed in `ReturnBaseStuckService` on 2026-04-08 (commit 29a5de8) — "blocksMovement returns true for open gates … but the collision shape is empty. The bot refused to traverse its own open gates on the way home." The bug lived on in the movement-input path, never caught because it only surfaces when the bot tries to actively push through a door (follow, come, idle hobbies near a doorway).
- **Why not just check `getCollisionShape().isEmpty()`:** That works for fence gates (empty when open) but not for doors. Vanilla `DoorBlock.getOutlineShape` returns a 3-pixel strip flush against the wall even when the door is open, so `isEmpty()` returns false. The authoritative signal is the `OPEN` blockstate property.
- **Fix:** Extracted `isPassableForMovement(BlockState)` helper and rewrote `hasMovementClearance` to use it. Sequence: `isAir() || !blocksMovement()` (preserves existing carpet/plate/tripwire/etc. behavior), then a stateful override — if the block has `Properties.OPEN` and is a `DoorBlock`, `FenceGateBlock`, or `TrapdoorBlock` instance with `OPEN == true`, allow passage. Closed variants and `OPEN`-stateful blocks that are not doors/gates/trapdoors (chests, barrels) still correctly block. The outer `world.isSpaceEmpty(bot, targetBox)` check in `canOccupyPosition` remains — if the open door's thin leaf actually intersects the bot's hitbox (e.g., walking along the wall rather than through the center), it'll still reject.
- **Verification:** Test the bot walking through: (a) a standalone open door, (b) a door triggered by a pressure plate on the approach side, (c) a closed iron door (should still refuse — no passability). (d) Bot should NOT walk through a closed wooden door without opening it first (the door-plan path opens it, then movement proceeds).

## Fast-travel tier system: combination gating + rendered-map check + light requirement underground (2026-04-15)

- **Behavior change (tightening):** Bots with no navigation artifacts can no longer fast-travel at all. Previously a no-artifact bot could travel at 3× delay regardless of destination; now surface fast-travel requires at least one gate to be open.
- **Above-ground gates:**
  - **Map + Compass (rendered)** — bot has at least one filled map *and* at least one compass, *and* the destination block is rendered (strict pixel non-zero) on at least one accessible map. Rendering state is read from the map's `MapState` regardless of where the map sits (inventory, bundle, shulker, ender chest), matching vanilla — the map must actually have been explored.
  - **Smoke signal at labeled destination base** — lit campfire-on-hay within the base, bot within 5× base radius (existing rule, kept).
- **Above-ground tier math:** 1 gate open → 3× delay; 2 gates → 2×; spyglass accessible anywhere (once, above-ground only) → one additional step. All three (map+compass + smoke signal + spyglass) → 1× (tier 2 equivalent). Spyglass is a speed modifier only, never a gate by itself.
- **Tier 2+ short-circuits (unchanged):** lodestone compass with bound target, Eye of Ender, wizard tome, enchanting table within 6 blocks, or mutual ender pearls → 1× delay.
- **Underground gate (new requirement):** Map + Compass now also requires a torch or lantern to read by. Accepted configurations: tier 2+ artifacts, map+compass+light → 2×, lodestone compass without bound target → 2×, smoke signal at dest base within 2× radius → 3×. Spyglass ignored underground.
- **Unified container scanning:** New [ArtifactScanner.java](src/main/java/net/wcfcarolina13/GameAI/services/ArtifactScanner.java) walks main inventory → bundles → shulker-box contents → ender chest → nested bundles/shulkers inside the ender chest. Every gate/tier check consults this single surface, so the same item counts whether stashed or held.
- **Map rendering API:** `ArtifactScanner.hasRenderedMapAt(bot, x, z, dim)` loads each filled map's `MapState` via `FilledMapItem.getMapState(mapId, world)`, validates dimension match, computes the pixel for the destination at the map's scale using `Math.floorDiv` (correct for negative coords), and checks `state.colors[pixelZ*128 + pixelX] != 0`.
- **Refactor in [NavigationArtifactService.java](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java):** deleted the old multi-branch underground gate (lines ~954-1005) and the above-ground smoke extension (lines ~1008-1030). Replaced with a single call to `resolveSurfaceTier` or `resolveUndergroundTier` based on an `isSkyVisible + surfaceY-4` underground heuristic. Refusals emit a tailored chat message per `reason` tag. When a multiplier > 1 is returned, `delayTicks` is recomputed via `calculateDelayTicks(distance, crossDim, multiplier)` so the 7s/5min clamps inside that function remain correct (the old code multiplied the already-clamped value, which could double-exceed the 5-minute cap on long trips).
- **Preserved:** `artifactDelayMultiplier(bot, owner)` returns 1/2/3 as before — kept alive for `ChestRegistryNetworkManager` (display-only ETA hint) and `BotEmergencyRescueService` (base multiplier). Actual enforcement happens inside `beginDelayedTravel`, so any mismatch surfaces as a slightly-off ETA, not a broken refusal. `skipArtifactGate` still bypasses the whole resolver for base-bypass / return-trip callers.

## RideSync: chest boats and payload minecarts are no longer broken on dismount (2026-04-15)

- **Behavior change:** When a bot dismounts its boat/minecart (either because commander dismounted or because RideSync loses its claim), the bot no longer tries to break chest boats, chest/furnace/hopper/TNT/command-block minecarts, or any vehicle carrying another passenger. Previously the bot would walk back and attack the vehicle to recover the item — risky when a chest boat held cargo, or when a mob/villager/player had boarded.
- **New helpers in [RideSyncService.java](src/main/java/net/wcfcarolina13/GameAI/services/RideSyncService.java):**
  - `isReclaimableBoat(entity)` — true only for plain `AbstractBoatEntity` that is not a `ChestBoatEntity`.
  - `isReclaimableMinecart(entity)` — true only for `EntityType.MINECART` exactly (excludes chest/furnace/hopper/TNT/command-block variants).
- **Gated at queue time:** `maybeQueueBoatBreak` and `maybeQueueMinecartBreak` return early if the vehicle is not reclaimable. `maybeQueueMinecartBreak` also now returns early if the cart has passengers (previously missing — boats had this check, minecarts didn't).
- **Gated at break time:** `maybeProcessBoatBreaks` and `maybeProcessMinecartBreaks` now cancel the pending break if someone boards the vehicle between queue and execution. Previous behavior for boats was to keep looping until timeout; minecarts had no check at all. Cancellation uses `finishBoatBreak(..., "abort-passenger", suppressRemount=false)` so the bot isn't prevented from mounting other boats.
- **Also gated in commander-loss iterators:** `maybeQueueBoatBreaksFromCommanderLoss` / `maybeQueueMinecartBreaksFromCommanderLoss` now skip non-reclaimable candidates when scanning nearby vehicles after a commander destroyed their own vehicle.
- **Plain empty boats/minecarts still break** — the reclamation loop is preserved for the safe cases.

## RideSync: bot boats keep up with commander boats at vanilla speed (2026-04-15)

- **Fix:** When the commander and bot were in separate boats, the bot paddled at ~55-60% of vanilla speed and perpetually fell behind. User reported having to wait mid-voyage for the bot to catch up. Log evidence (latest.log 21:03): distance grew from 5.61 → 48.79 blocks in ~20s while every `RideSyncMove` line showed `botVel=0.00,0.00,0.00 commanderVel=0.00,0.00,0.00`.
- **Root cause:** Vanilla `AbstractBoatEntity.tick()` considers a boat with a player passenger to be client-authoritative — the server calls `setVelocity(ZERO)` every tick and waits for `MoveVehiclePacket`s from the real client. Our bots are fake `ServerPlayerEntity` instances with no client, so **nothing** was advancing the boat. The only thing moving the bot boat was `forceMoveBoat`'s fallback `move(MovementType.SELF, step)`, which was gated on `horizSpeed < 0.02 && distance > 4.0` and hardcoded to 0.18-0.24 b/t (vs vanilla ~0.40 b/t).
- **Secondary cause:** `commanderVehicle.getVelocity()` is also zeroed server-side for a player-driven boat, so `targetSpeed = max(baseSpeed, 0) = baseSpeed`, and the "match-vel" branch (`horizontal > 0.05`) never fired — meaning the bot only moved **reactively** once it was already lagging, not proactively.
- **Changes in [RideSyncService.java](src/main/java/net/wcfcarolina13/GameAI/services/RideSyncService.java):**
  - New `VEHICLE_LAST_POS` / `VEHICLE_LAST_POS_TICK` maps + `estimateVehicleHorizontalSpeed(vehicle, server)` helper. Diffs per-tick position to recover a vehicle's real horizontal speed when `getVelocity()` is zeroed. Used for commander tracking; cleared in `resetSession()`.
  - `maybeSyncMovement` now uses the estimated speed for both the `commanderSpeed` passed to `forceMoveMount` and the `horizontal > 0.05` gate that triggers "match-vel". Result: bot tracks commander motion proactively while within the desired follow distance, instead of only reacting after it falls behind.
  - `forceMoveBoat` rewritten to unconditionally call `move(MovementType.SELF, step)` every tick while there's a target, at a step speed scaled to commander speed (clamped to `[baseSpeed, vanillaMax]` = `[0.34, 0.42]` normal, `[0.42, 0.50]` sprint) with a `+0.05` lead-catch boost when `distance > 6`. Removed the `horizSpeed < 0.02` gate since horizSpeed is always ~0 (vanilla zeros it).
- **Net effect:** bot matches vanilla cruise speed of ~0.40 b/t instead of 0.18-0.24 b/t, and maintains pace with the commander rather than playing perpetual catch-up. Y-velocity is preserved so boat buoyancy still works.

## Fishing: sunrise-resume rescans when saved stand is unreachable from lodestone landing (2026-04-14)

- **Fix:** When `navigateToSpot` failed on a sunrise-resumed saved stand, the skill immediately aborted with "Can't reach the fishing spot (blocked?)" even though a re-scan fallback existed later in the code. Root cause: a saved session's `standOptions` contains only the saved stand (singleton list), so the alt-loop found zero alternatives, and the `!approached` branch returned failure BEFORE the precision-walk re-scan at line 236 ever ran. The precision-walk re-scan only fires when `approached == true` but the bot stopped short of the exact stand.
- **New branch:** If `!approached && savedSession != null`, scan fresh from the bot's current landing position (near the lodestone) with full `standOptions` alternates, and try those before returning failure. The original saved spot was chosen from the water's edge; after fast-travel the bot lands somewhere else and the terrain path can differ.
- **Observed trigger:** Log line `Navigation to fishing spot 243, 63, 1228 failed: direct: walk blocked near class_2338{x=238, y=63, z=1235}` — obstacle between lodestone landing and saved stand that the original water-edge search didn't see.

## Fishing: swim-to-shore helper bypasses safeNudge cliff-guard (2026-04-14)

- **Fix:** When the bot drifted into the water (or was ordered to fish while already swimming), FishingSkill's water-exit guard delegated movement to `navigateToSpot`, whose `safeNudge` fallback treats a block-below-feet with empty collision shape as a cliff edge and refuses to step. Water has empty collision shape, so a swimming bot can NEVER reach shore through generic navigation — it would fail with `safe nudge failed` and the skill would abort with "I'm swimming and couldn't reach shore for fishing." even though multiple shore stands were reachable.
- **New:** `BotWaterEscapeService.swimToShore(bot, radius, timeoutMs)` drives swim-forward input + auto-jump directly, with no cliff check (the bot is already in water — "falling" is a no-op). Iterates up to 5 ranked shore candidates internally so a single blacklisted or blocked stand doesn't abort the whole attempt. Stuck-detection bails to the next candidate after ~2s of zero progress.
- **Call-site swaps in FishingSkill:**
  - Entry water-escape guard now calls `swimToShore` instead of `navigateToSpot`.
  - Main-loop anchor-drift check: if drift put the bot into water, swim out first, then let the regular nav walk to the stand.
  - Main-loop "bot is in water / not standable" recovery branch: same — swim out before navigating back.

## Fishing: break sunrise-resume loop + home-base chest fallback + chest-aware spotting + /bot fish forget (2026-04-14)

- **Fix (sunrise-resume loop):** When `handleFullInventory` couldn't find any reachable chest at the fishing spot, `FishingSkill` returned failure WITHOUT clearing the saved session. `BotAutoReturnSunsetService` then kept firing sunrise resumes every morning (it only gates on `FishingSessionService.hasSession`), producing an infinite [fast-travel → inventory-full fail → wait → fast-travel] loop. Both inventory-full exits now call `clearSessionAfterStorageFailure`, which clears `FishingSessionService` + `SkillResumeService.clearSunriseResume`. Lodestone compasses (NBT on the compass item) are untouched.
- **Fix (home-base chest fallback):** Local chest rings (12/24/48) miss chests at the bot's home base when the fishing spot was picked far away. `handleFullInventory` now also scans within 48 blocks of `BotHomeService.resolveHomeTarget(bot)` and uses `depositMatching` (default movement profile) so the bot can actually navigate there. After deposit, the main-loop anchor-drift check walks the bot back to the fishing stand.
- **Behavior (chest-aware spotting):** `findStandOptions` now applies a `-0.45` score bonus for stands within 16 blocks of any known chest. Chest anchors are scanned once per spot search: 48 blocks of the bot plus 48 blocks of home base. Small enough that cast quality still wins in an open lake, strong enough that when two spots have similar water quality the bot picks the one it can actually offload to.
- **New command:** `/bot fish forget [bot|all]` clears the saved fishing session + sunrise-resume record for the target bot(s). Gives the user a way to break out of a bad saved spot without editing config files. Lodestone compasses remain intact — next fresh `/bot fish` still fast-travels to the nearest compass target.
- **In-game UI:** Added a "Forget Spot" button in the Actions menu (indented under "Fishing") with a tooltip explaining when to use it. Added a matching `gather_fish_forget` entry to the in-game Guide under "Gathering".
- **Tuning (rod/bow offload threshold):** Bumped `shouldStoreItem`'s caught-rod/caught-bow durability threshold from `<15%` remaining to `<=25%`. Vanilla fishing treasure-catches cap at exactly 25% durability, so any rod/bow at-or-below that bar is disposable catch loot (and anything above must be the bot's own gear). At the old 15% threshold the bot was stockpiling 3 beaten-up rods at exactly 25% (16/64) and 3 bows at 18-22% alongside the active gear. Also added a named-item bypass (anvil-renamed rods/bows are kept) so user-curated keepsakes aren't auto-stored.

## Fishing: expanding chest search rings (2026-04-14)

- **Fix:** Even at 12 blocks, the chest search was missing dock chests further out. Replaced the single-radius scan with a concentric-ring expansion: try **12 → 24 → 48** blocks, expand on failure, return the closest chest with space inside the first ring that has one. Logs the expansion attempt so it's clear when the bot reached out further.

## Fishing: chest radius, post-task return, faster spot search (2026-04-14)

- **Fix:** `FishingSkill.findNearbyChestWithSpace` was searching a tiny 5-block radius (vs 10–12 used by other skills). Storage-runs failed even when chests were placed at the typical "fishing dock" distance. Bumped to 12 blocks (matching `ChestStoreService.DEFAULT_CHEST_SEARCH_RADIUS`, `HarvestCropSkill`, `BotMutualAidService`, etc.).
- **Fix:** Post-task return (the "fast-travel the bot back to the commander when its task ends" feature) is meant to recover stuck-underground bots. It was misfiring for surface skills like fishing, where the bot ending at the worksite is the expected outcome. Added `skill:fish`, `skill:farm`/`skill:harvestcrop`, `skill:woodcut`, `skill:hunt`, `skill:wool`, and `skill:fortifyvillage` to the suppress list.
- **Perf:** Cold-path `findFishingSpot` was capped at 200 candidate evaluations, which produced 30–50 second searches in dense water areas. Lowered the cap to 80 and added an early-exit (after 25 evaluations) when a good-enough spot has been found (score ≤ 8). Cuts cold-path search time roughly in half without affecting quality for the common case.

## Fix: sunrise resume off-stand → silent zero-bite session (2026-04-14)

- **Fix:** With the savedForSunrise change the bot correctly fast-travels to its lodestone and resumes the saved spot, but fast-travel lands it ~17 blocks from the saved stand and `MovementService` typically stops 1 block short of the exact target. The drift check and `adjustPositionToWaterEdge` both treat that as "close enough", so casts originate from the wrong block, bobbers land badly, and the bot sits silent for minutes.
- Added a precision walk step on the sunrise-resume path only: if the bot isn't on the exact saved stand after `navigateToSpot`, `nudgeTowardUntilClose` drives it the final block. If that walk can't land on the stand (terrain blocked), the saved spot is abandoned and `findFishingSpot` re-scans for a fresh reachable one. No teleport — bot walks naturally or picks a new spot.

## Fix: sunrise fishing resume forgets saved spot (2026-04-14)

- **Fix:** At sunset, `FishingSkill` saved the session via `FishingSessionService.saveSession(...)`, broke the cast loop, then immediately ran the post-loop "clear stale session on normal completion" line — wiping the data it had just persisted. Next morning, sunrise resume fast-travelled to the lodestone (success) but `consumeSession()` returned `null`, so the skill burned 50+ seconds scanning for a new spot near the landing position instead of returning to the saved stand.
- Added a `savedForSunrise` flag set when the loop breaks due to sunset save-for-resume, and gated the post-loop `clearSession` behind it.

## Fishing: offload filter order + multi-spot retry (2026-04-14)

- **Fix (filter bug):** Damaged/enchanted caught rods and bows weren't being offloaded. Root cause: `ChestStoreService.isOffloadProtected()` treats *all* damageable items as protected (to safeguard the bot's real gear), and that check ran *before* the fishing whitelist. Reordered `FishingSkill.shouldStoreItem()` so the raw-fish / leather-boots / rod+bow (<15% durability) checks evaluate FIRST, then the generic protection applies to everything else.
- **Fix (navigation abort):** After offloading items, the fishing skill would abort the entire session if the chosen return spot was unreachable. It now retries with alternate spots up to 3 times (the failed spot is already blacklisted, so `findFishingSpot` naturally picks a different one) before giving up.

## Tooltips + in-game guide coverage for new features (2026-04-14)

- **Guide entries** added to `BotGuideScreen`: Auto Respawn, Bot Roster (Spawn Bots…), Bulk Apply (All Bots), No-Companions HUD Hint, World Mode Reset (admin-only).
- **Tooltips** on Bot Controls footer buttons: Permissions Editor, Spawn Bots….
- **Tooltips** on the four Bulk Apply buttons (ALL ON/OFF for Auto Respawn and Auto Spawn on Load).
- **No-Companions HUD hint** line 2 now mentions the dismiss button inline: "Press [-] to open the roster — click [x] to hide this hint".

## `/bot worldmode reset` admin command (2026-04-14)

- **New:** `/bot worldmode reset` (admin-only) clears the per-world mode-selection state and re-broadcasts the recruitment payload so `WorldModeSelectionScreen` becomes reachable again. Use when a world save path collides with a previously-configured world (recreated world with the same name) and the welcome/mode dialog doesn't reappear.
- After running the command, press `[-]` (or rejoin) to reopen the mode selection screen.

## Fix: no-bots HUD persistence + multi-spawn pile-up (2026-04-14)

- **Fix:** "No companions present" HUD hint no longer auto-fades after 10s. It now stays visible as long as the no-bots condition holds, and is only hidden by the X button or when bots reappear. The X button actually has an effect now.
- **Fix:** Spawning multiple bots at the same look position no longer piles them into the same block. `spawnBot` counts fake players within 2 blocks of the target position and ring-spaces each new one (6-slot ring, radius 1.3). Fixes the multi-select `/bot spawn` pile-up from BotRestoreScreen.

## Multi-select Bot Restore screen + Admin Mode auto-open + dismissible HUD (2026-04-14)

- **Rewrite of `BotRestoreScreen`:** clicking a row now toggles a checkbox (multi-select). New "Spawn Selected (N)" footer button sends `/bot spawn` for each chosen alias with a 220ms stagger so they don't pile up at one position. Pagination preserved.
- **New "Create new bot" sub-section** in the same screen — name input field with client-side validation (`^[a-zA-Z0-9_ \-.']+$`, max 24 chars, duplicate check). "Create" button spawns the bot in admin mode with a randomly-assigned skin (customize later via the bot's inventory → skin chooser).
- **New tooltip link** at the bottom of the screen — "Manage settings → Bot Controls" — clicks open `BotControlScreen` for per-bot rules, auto-respawn, and other preferences.
- **Admin Mode auto-open:** picking "Admin Mode" in `WorldModeSelectionScreen` now drops the user into `BotRestoreScreen` immediately (instead of just printing chat instructions). If no saved aliases exist, the screen shows an empty-state hint pointing to the create-new section.
- **Bot Controls "Spawn Bots…" footer button** opens the same restore screen — so you can spawn additional bots from the controls panel without needing to find and press the `-` hotkey.
- **Dismissible HUD hint:** the "No companions are currently present" overlay now has an X button. Click X to hide it for the rest of the session. The hint reappears next time bots transition from present → absent (so it doesn't stay forever-dismissed if you despawn everyone again).
- **New `MouseHudClickMixin`** to route HUD-level mouse clicks through `FrensClient.handleHudClick()` — required because Fabric's `HudRenderCallback` doesn't deliver mouse events.

## UX polish — Bulk button feedback + Admin default (2026-04-14)

- **New:** Bulk-apply buttons ("ALL ON" / "ALL OFF") now show a brief colored pulse when clicked so the user gets visual confirmation the action ran.
- **Fix:** Bot Controls spawn-mode fallback now defaults to `admin` (matches the `BotControlSettings` field default). Previously the UI fell back to `training` when the raw value was null/blank/unknown — which disagreed with the stored field default. Manually-adjusted spawn modes still persist as before.

## Bulk Apply Buttons in Global Bot Controls (2026-04-14)

- **New:** Expanded global toggles panel now has a "Bulk Apply (current world)" section with 4 action buttons:
  - **Auto Respawn — All Bots:** `[ALL ON]` `[ALL OFF]`
  - **Auto Spawn on Load — All Bots:** `[ALL ON]` `[ALL OFF]`
- Clicking a button applies the value to every bot that has a per-bot settings entry in the current world. Not a persistent state — acts like a reset.
- `ALL ON` for Auto Respawn also auto-spawns any inactive bot whose flag flipped false→true, consistent with the per-bot toggle behavior.
- Buttons apply immediately and discard any staged per-bot edits in the currently open settings panel (the panel rebuilds from the new live config).

## Respawn Permission Prompt (2026-04-14)

- **New:** When a bot dies with Auto Respawn OFF, the controller gets a chat prompt: *"{name} died. Respawn now? (yes/no)"*. Reply `yes` to bring them back, `no` to stand down. Session-scoped — if you don't answer before the session ends, it defaults to "no" and the bot stays shelved until manually spawned.
- **New:** Toggling Auto Respawn ON for a bot that isn't currently spawned auto-spawns them. Useful as a revive path after death-without-answer.
- `/bot spawn <name>` also clears any pending respawn prompt (in case user prefers commands).
- **Fix:** Manual respawn via `/bot spawn` no longer clobbers a user-customized `autoRespawnOnDeath` preference. Mode-based defaults only apply on a bot's first spawn (when the field is null). `autoSpawnOnLoad` still always resets to `true` on manual spawn since that's the user explicitly asking for the bot back.

Complements the existing `SkillResumeService` prompt ("I died. Should I continue with the last job?") which handles skill resumption after respawn.

## Fix: Auto-Respawn Gate Not Enforced (2026-04-14)

- **Fix:** Toggling "Auto Respawn" OFF had no effect — bots still respawned automatically on death. Root cause: `Frens.java` AFTER_DEATH handler always called `BotEventHandler.ensureRespawnHandled()` unconditionally, ignoring `BotControlSettings.autoRespawnOnDeath`. (The flag was only consulted in `BotPersistenceService.onBotDeath` for survival-recruitment gating, which doesn't apply outside that mode.)
- Now: when `autoRespawnOnDeath == false`, the forced respawn is skipped AND the bot entity is unregistered after death-persistence runs. User must `/bot spawn <name>` to bring the bot back. Inventory was already wiped by vanilla death processing.

## Fix: Bot Post-Death Immortality (2026-04-14)

- **Fix:** Bots became immortal after their first death/respawn — all subsequent damage was silently rejected. Root cause: `createFakePlayer.kill()` schedules `networkHandler.disconnect()` during `onDeath()`, which sets `ServerPlayNetworkHandler.dead = true`. `ServerPlayerEntity.isInvulnerableTo()` then short-circuits to invulnerable via `canInteractWithGame()`. Vanilla fixes this by creating a fresh `ServerPlayerEntity` on respawn; fake players reuse the same entity, so the flags must be reset manually.
- Added two mixin accessors (`LivingEntityAccessor`, `ServerPlayNetworkHandlerAccessor`) that expose the private `dead` fields. `BotEventHandler.onBotRespawn()` now clears them alongside the existing `setInvulnerable(false)` / `timeUntilRegen` / `hurtTime` resets, plus `deathTime = 0`.
- Bug was pre-existing, not a regression from the cross-session despawn feature. Visible now because autoRespawn=false exercises post-death behavior more aggressively.

## Chest Offload & Fishing Filter Fixes (2026-04-13)

- **Fix:** Double chests now use the merged 54-slot inventory instead of only the first 27 slots. Affects all chest offload operations globally.
- **Fix:** Fishing offload filter updated — raw fish, leather boots, and nearly-broken rods/bows (<15% durability) are now offloaded. Cooked food and durable gear are kept.

## Cross-Session Bot Despawn (2026-04-13)

- `/bot despawn <name>` now shelves bots across sessions — they stay gone until `/bot spawn`
- New `/bot despawn session <name>` subcommand for the old session-only behavior
- New "Auto Spawn on Load" toggle in Bot Controls > Spawning tab
- New guide entry explaining shelving and despawn modes
- `autoSpawnOnLoad` field added to BotControlSettings (defaults to true, no migration needed)

## 2026-04-11 — Pillager patrol alert system (Feature B)

- **New:** `BotPillagerAlertService` detects illager groups (2+ visible within 16 blocks via LOS-gated `canSee`) and goes defensive — shield up, pursuit suppressed until aggro. One-shot alert via tiered channels: goat horn (vanilla instrument sound, 128/48 block overhead above/below ground) > signal fire (lit campfire + hay bale within 24 blocks, FOLLOW mode suppressed) > direct message (magic-comm gated via `canLongRangeComm`: eye of ender, wizard's tome, both-have-pearl, or enchanting table) > fallback (16 blocks, always). Alert-then-escalate: normal combat + Feature A defense takes over when illagers aggro.
- **New helper:** `CompanionCommunicationPolicy.canLongRangeComm(bot, commander)` — four-gate predicate for magic-comm items/proximity, reusable for future features.
- **Integration:** patrol check + pursuit suppression at three `moveToward` sites in `engageHostiles`, `Frens.java` SERVER_STOPPING cleanup.

## 2026-04-11 — Tamed-animal defense (Feature A)

- **New:** `BotAnimalDefenseService` consolidates "defend the commander's owned animals from non-commander attackers". Hostile-forward primary scan via `BotThreatService.findHostilesAround` + small reverse-scan watch list for player attackers and accidental hits. Threat-score boost via one-line hook in `BotEventHandler.scoreThreat`; non-`HostileEntity` attackers (wolves gone wild, etc.) injected into the engage list via one-line hook at the top of `BotEventHandler.engageHostiles`. Per-tick (10-tick throttle) registered in `Frens.java` alongside the other tick services.
- **Defended categories:** commander-owned tameables (cat/wolf/parrot via `TameableEntity.isTamed` + UUID match), commander-owned horses (`AbstractHorseEntity.isTame`, no 'd'), mobs leashed to the commander (live entity required), animals on the bot's preferred home base near a hay bale (using `BotHomeService.resolvePreferredHomeBase` + `findBaseNearPosition`, implicitly commander-scoped via `WorldData.preferredHomeBaseByBot`), name-tagged entities (`hasCustomName`), and villagers inside mapped villages (label equality via `MappedVillageService.getVillageAt`).
- **Excluded:** `HostileEntity`, `RaiderEntity`, slimes, magma cubes, ender dragon, wither, and the named-hostile loophole (Victim Sanity Gates run before any rule). Iron golems in unmapped villages stay safe (no farm-grief). Attackers riding vehicles (boat/minecart/mounted) skipped as a farm-machinery heuristic (iron-farm scarers, spawner grinders).
- **Iron golem special rules:** accidental hits (golem.target != bot) get silently ignored — bot does not retaliate. Direct aggro (golem.target == bot) triggers a sprint-flee 12 blocks away from the closest aggroed golem; bot does not fight back (golems are too tanky).
- **PvE only in v1.** Player attackers receive an overhead warning ("Engaging threats against allies") instead of engagement, pending the future "alliances" feature. The overhead warning hook (`maybeWarnPlayerAttacker`) and the alliance gate (`isAttackerAllied`, currently always false) are wired in as forward-compat stubs.
- **Self-preservation:** bot below 30% HP suppresses defense engagement (still emits overhead warnings, which don't put it at additional risk).
- **Spec:** `docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md` (rev 3, approved). Plan: `docs/superpowers/plans/2026-04-11-tamed-animal-defense.md`.

## 2026-04-11 — Drop sweep backs off from commander mining activity

- **Problem.** Bots in FOLLOW mode would aggressively rush drops as the player broke blocks in tight tunnels, shoving the commander off their mining spot. The opportunistic idle-sweep path (`BotEventHandler.tickOpportunisticIdleSweep`, 15s idle threshold) fires whenever both bot and player are standing still — which is exactly what happens when the player stands in place hollowing out a tunnel. Every mined block drops an item, and the sweep immediately targets it.
- **Initial attempt (per-drop filter).** New `CommanderActivityService` tracks the last block-break timestamp per real player UUID (fake-player bots excluded). Hooked into the existing `PlayerBlockBreakEvents.AFTER` in `Frens.java`. Added `isDropNearActiveMiner(world, drop)` and filtered drops in `BotEventHandler.findNearestDrop` and `DropSweepService.collectNearbyDrops`.
- **Why the first pass wasn't enough.** `DropSweeper.findClosestDrop` runs its OWN entity scan inside the async sweep loop (up to `maxTargets` iterations, re-querying the world every pass). Once a sweep started on a drop far from the player, the inner loop would happily target new drops as the player kept mining — bypassing both initial filters entirely. The 4.5-block / 3.5-second window was also too tight for real tunnel geometry.
- **Fix (global suppression).** Upgraded to a hard bot-proximity gate with three layers:
  1. **`CommanderActivityService.isBotNearActiveMiner(bot, 8.0)`** — returns true if any real player within 8 blocks of the bot has broken a block in the last 4 s. One helper used everywhere.
  2. **Global suppression in `DropSweepService.collectNearbyDrops`** — if the bot is near an active miner, bail out immediately AND cancel any in-flight sweep this bot owns. Command-driven GUARD/PATROL sweeps are still allowed through (explicit user intent).
  3. **`BotEventHandler.tickOpportunisticIdleSweep`** — added a top-of-function gate that clears pending idle-sweep state, requests cancel on any in-progress sweep, and returns. Ensures the idle-sweep walker stops mid-step when the commander starts mining.
- **Plus per-drop filters at the remaining scanners** — `BotEventHandler.findNearestDrop` (the idle-sweep target picker) and the newly-patched `DropSweeper.findClosestDrop` (the async sweep's inner loop) both skip drops within 6 blocks of a recently-mining player. Safety net so even if a sweep starts legally, it can't retarget player-proximate drops mid-run.
- **Tunables** at the top of `CommanderActivityService.java`: `MINING_DROP_EXCLUSION_RADIUS = 6.0`, `BOT_MINING_PROXIMITY_RADIUS = 8.0`, `MINING_WINDOW_MS = 4000L`.
- Net effect: while the player is mining in a tight tunnel, the bot stays out of the tunnel entirely — no targeting, no walking, no pushing. ~4 seconds after the player stops breaking blocks, the sweep behaviour resumes normally.

## 2026-04-11 — Cobweb avoidance + sprint catch-up + friendly-fire gate + through-cobweb take-cover

- **Cobweb added to deadly-block list.** `BotHazardService.isDeadlyBlock` now returns true for `Blocks.COBWEB`. Cobwebs don't deal direct damage but they slow entities to ~15% speed with an empty collision shape, which means the pathfinder previously treated them as freely-walkable air. In caves, mineshafts, and raids this is a death sentence — the bot wanders into a web, becomes a sitting target for skeleton arrows and zombies, and can't path out. Now rejected by both pathfinders, caught by the per-tick hazard escape, and added to the never-break list (so stuck-escape doesn't try to chop a cobweb wall mid-danger).

- **Sprint catch-up speed fix.** Reports that the bot wasn't reliably sprinting to catch up when far from the player. Root cause: the velocity cap in `BotActions.applyMovementInput` was hardcoded at 0.45 regardless of sprint state, AND the sprint impulses in `FollowMovementService` were only 0.24–0.26 — giving a steady-state of ~6.2 blocks/sec, barely above vanilla player sprint (5.6 blocks/sec). Over short distances the bot technically closed the gap but at a pace slow enough that the user perceived "not sprinting". Fix: made the velocity cap sprint-aware (`0.58` when sprinting, `0.45` when walking) and bumped the three follow-mode sprint impulses to `0.32`. Steady-state is now ~7.7 blocks/sec, a meaningful ~40% advantage over a sprinting player, so catch-up is actually visible.

- **Friendly-fire gate: bot no longer shoots arrows toward the player.** `BotActions.isRangedLineBlocked` now also returns true if any non-bot player is within 1.5 blocks of the bow's fire line between the bot's eye and the aim point. Uses a distance-from-point-to-line-segment test against `world.getPlayers()`, skipping the bot itself and any registered allied bot. All existing ranged fire call sites (bow charge, trident throw, crossbow release) pick up the new gate for free.

- **Through-cobweb engagement + take cover.** Added `isRangedLineBlockedByCobweb` that samples block cells along the raycast at 0.5-block intervals and returns true if any sample is a cobweb. Folded into `isRangedLineBlocked` so the bot never fires through a web (arrows get swallowed and the attack wastes ammo). Added new early branch in `BotEventHandler.engageHostiles`: if the primary target is behind a cobweb (`isCobwebBetweenBotAndTarget`), raise shield facing the target, sprint-retreat 4 blocks directly away, and return. This gives the "take cover if being shot through cobwebs" behaviour the user asked for — bot holds distance and lets the commander break the web manually instead of standing in the line of fire taking skeleton arrows.

## 2026-04-10 — Hazard avoidance sweep + pathfinder pressure-plate regression fix + sign protection + ai-player conflict detection

- **Pathfinder regression fix: bot avoided/jumped over pressure plates.** Both `BaritoneStylePathFinder.isPassable` / `isPassableWorld` and the legacy `PathFinder.isPassable` used `!getCollisionShape().isEmpty()` as the final passability check, which rejected every carpet, pressure plate, rail, tripwire, and lily pad as impassable. The bot was routing around them (and the tagBlocks step-up logic occasionally tried to climb over them as if they were step-up targets). Previously this behavior was masked by the `BotRescueService` false-positive that fired `attemptEscapeMovement` and displaced the bot off the cell. After the rescue-service fix landed, the underlying pathfinder avoidance became visible.
- Extracted a shared classifier `WalkablePartialBlocks` in `GameAI.services` with two views:
  - `isPathable(state, world, pos)` — narrow set: carpets, pressure plates, rails, tripwire, lily pads, pale-moss/moss carpet, plus a ≤ 0.125 max-Y fallback. Does NOT include slabs/stairs/snow layers (those are handled by the pathfinder's step-up logic, not passability).
  - `isStandable(state, world, pos)` — broad set: everything from `isPathable` plus slabs, stairs, snow layers. Used for the bot's current-tile standability question.
- Wired the shared helper into: `BaritoneStylePathFinder.isPassable` / `isPassableWorld`, legacy `PathFinder.isPassable`, `FollowPathService.isPassable`, `BotRescueService.isThinWalkablePartialBlock`, `ReturnBaseStuckService.isPassable`. All five now agree on what counts as walkable partial terrain.

- **Signs protected from bot mining.** Reports of the bot thinking it was spawning inside signs and breaking them. Added `AbstractSignBlock` (covers every variant — standing, wall, hanging, ceiling hanging, wall hanging, all wood types — via class hierarchy) to `ProtectedStructureBlockHelper.isNeverBreakBlock`. Stuck-escape and burial-rescue mining will now skip signs entirely.

- **New hazard avoidance sweep.** New `BotHazardService` consolidates "blocks that hurt the bot" classification and adds per-tick runtime escape for bots standing on hazardous terrain plus pufferfish proximity flee.
  - `isDeadlyBlock(state)` covers: fire, soul fire, lava, magma block, campfires (both variants), cactus, sweet berry bush, wither rose, powder snow, pointed dripstone. Consumed by both pathfinders, `FollowMovementService.isDangerousGround`, and the per-tick scan.
  - Per-tick runtime escape: if a bot's feet blockpos lands on any of those blocks (teleport, unloaded-chunk load-in, griefed terrain), the service nudges it toward a nearby safe tile. Campfires are intentionally skipped because `BotCampfireAvoidanceService` owns their escape path.
  - Per-tick pufferfish flee: any live `PufferfishEntity` within 4 blocks triggers a velocity kick directly away from the fish centroid. No line-of-sight gate, matching the "sting through fences and walls" behaviour the user reported.
  - All deadly blocks also added to `ProtectedStructureBlockHelper.isNeverBreakBlock` via delegation, so escape-mining code will route around them instead of breaking through them and taking damage mid-mine.
  - Extended pathfinder passability to reject deadly blocks directly (previously only fire/soul fire were hardcoded; magma, cactus, sweet berry, powder snow, etc. relied on incidental collision rejection that didn't cover all cases).
  - Service registered in `Frens.java` alongside `BotCampfireAvoidanceService::onServerTick`.

- **ai-player conflict detection.** Root cause of the "duplicate keybinds in controls menu" and "follow go-to key doesn't work" report: the user had BOTH the upstream `ai-player` mod by shasankp000 AND this Frens fork installed. Both mods register their own keybinds with `"Frens: ..."` display strings, and both register `/bot` command roots — key presses and commands were dispatched non-deterministically between the two. Added a startup-time `FabricLoader.isModLoaded("ai-player")` check in `FrensClient.onInitializeClient` that latches `aiPlayerConflictDetected = true` and posts a red chat warning once the player joins, pointing them at the fix (close game, delete `ai-player-*.jar` from mods folder). The log also emits a loud ERROR-level message so users checking logs see it immediately.

## 2026-04-10 — ReturnBaseStuck isPassable walkable-partial fix

- **Fix: `ReturnBaseStuckService.isPassable()` rejected carpeted/plated path cells.** Flagged earlier today as a latent bug after the `BotRescueService` fix — same category, different site. The function had a comment claiming "pressure plates, buttons, rails, carpets → empty collision → pass", but none of those blocks have empty collision shapes. So during return-to-base stuck-escape routing, any path step containing a carpet, pressure plate, rail, tripwire, lily pad, bottom slab, stair, layered snow, or similar walkable partial was silently rejected as non-passable. In villages that's ~every doorway tile.
- Promoted `BotRescueService.isThinWalkablePartialBlock` from `private` to package-private so both services share a single source of truth for "is this cell passable while standing". `isPassable()` now delegates both the feet-cell and head-cell checks to it: a cell is passable if EITHER its collision shape is empty OR it's on the walkable-partial whitelist (or ≤ 0.125 max-Y fallback).
- Rewrote the misleading comment inside `isPassable` to reflect reality and pointed future readers at the shared helper + the 2026-04-10 doorway-stall autopsy.
- `isPassableForMining` (falling-block stabilizer predicate) and `isPassableForStanding` (step-up target detection) intentionally NOT touched — they have different semantics ("strict empty cell for mining" and "no step-up material here") that the whitelist would break.

## 2026-04-10 — Walkable-partial-block stuck loop fix + rescue teleport keybind

- **Fix: bot permanently stuck on walkable partial blocks near doorways.** `BotRescueService` was computing `feetBlocked = !feetState.getCollisionShape().isEmpty()`, and **every walkable partial block has a non-empty collision shape** — carpets (1/16), pressure plates (1/16), bottom slabs (1/2), stairs, layered snow, rails, tripwire, lily pad. A bot standing normally on any of these had its feet blockpos == the partial block (because `Entity.getBlockPos()` floors the entity Y), so the rescue service classified it as `stuckInBlocks=true`. This fired `attemptEscapeMovement` every ~1.2s, yanking the bot off its planned door-traversal path. Combined with the follow system's door-recovery logic, the bot wedged itself at the doorway indefinitely — see log 17:22–17:39: `feetState=White Carpet` → repeated `door-close wait: bot too close` + `door-corner: stagnant`.
- Added `BotRescueService.isThinWalkablePartialBlock(state, world, pos)` helper with a class-based whitelist mirroring `FollowPathService`'s planner-layer whitelist so the rescue service and path planner agree on passable terrain:
  - `CarpetBlock` (white, colored, moss, dyed — all)
  - `AbstractPressurePlateBlock` (wood, stone, polished, weighted — all)
  - `SlabBlock` (bottom slabs — top slabs put feet Y at 1.0, so feet blockpos is air)
  - `StairsBlock` (any orientation)
  - `SnowBlock` (layered snow, layers 1–7)
  - `AbstractRailBlock` (rail, powered, detector, activator)
  - Explicit: `PALE_MOSS_CARPET` (not a `CarpetBlock` subclass), `MOSS_CARPET` (defensive), `TRIPWIRE`, `LILY_PAD`
  - Fallback: any remaining block with collision shape max Y ≤ 0.125 (catches floor candles, skulls, sculk vein, turtle eggs, pink petals, amethyst small buds, frogspawn, etc.)
- Called in both `feetBlocked` sites: `rescueFromBurial()` and `isBotCurrentlyStuck()`. Half-block threshold (0.5) is deliberately not used as a blanket fallback to avoid accidentally whitelisting cakes, composters, hoppers and similar full-collision blocks.
- **Known latent issue (not fixed):** `ReturnBaseStuckService.isPassable()` has a misleading comment claiming pressure plates/carpets have empty collision shapes. They don't. The same category of false-positive can reject carpeted path cells during return-to-base escape routing. Flagged for follow-up if the bot starts getting stuck on carpets in return-to-base flow.

- **New feature: rescue teleport keybind.** A player-pressed "un-stick the bot" hotkey (`key.frens.rescue_teleport`, unbound by default). Server checks that the closest follower satisfies all of: same world, follow-mode with this player, horizontal distance ≤ 5 blocks, at most 3 blocks above, at most 1 block below, and unobstructed line of sight — then teleports the bot to the player's exact block with the player's yaw/pitch and zeroed velocity. Tight constraints so the feature can't be used to yank a bot across the map or phase through walls; purely for wedge-geometry escapes where wolf-teleport isn't triggering.
- Files: `network/RescueTeleportRequestPayload.java` (empty C2S record), `network/RescueTeleportNetworkManager.java` (receiver + constraint checks + actionbar feedback like "Rescue: bot is too far" / "Rescue: bot is out of sight"). Wired into `Frens.registerPayloadsAndReceivers` and `FrensClient.onInitializeClient` + tick handler.

## 2026-04-10 — Idle honey collection hobby

- New idle hobby: `honey_collect`. Bot harvests honey from nearby beehives/bee nests when idle.
- Only harvests when `honey_level == 5` (full hive) AND `BeehiveBlockEntity.isSmoked()` is true (campfire within 5 blocks below, handles carpet/slabs between).
- Prefers glass bottles (-> honey bottle food item) over shears (-> 3 honeycombs crafting material).
- Never breaks the hive. Never harvests an unsmoked hive.
- Low hobby weight — doesn't compete heavily with fishing/woodcutting/etc.
- Deposits honeycombs and honey bottles in nearby chests after collection.

## 2026-04-10 — Fast-travel food quality & magic bypass

- **PRECIOUS_FOODS classification:** Golden apple, enchanted golden apple, and golden carrot are now classified as "precious" in HealingService. They are skipped by `findCheapestSafeFood()` (normal eating) and excluded from the fast-travel food budget. At starvation emergency, the bot will eat rotten flesh first, then precious foods as a last resort (expanded `findDesperateFood()`).

- **Food budget filtering:** The fast-travel food safety gate in NavigationArtifactService now uses `HealingService.isTravelUsableFood()` to exclude both forbidden (rotten flesh, poisonous potato, spider eye, pufferfish, suspicious stew) and precious foods from the nutrition budget. If a bot only has rotten flesh and golden apples, it correctly sees 0 usable travel food.

- **Container food extraction:** Before the food budget is calculated, the bot now scans bundles and shulker boxes in its inventory for usable food. If the main inventory doesn't have enough, cheapest food is extracted from containers first (bundles via `BundleContentsComponent`, shulker boxes via `ContainerComponent`). Handles full inventory gracefully by skipping extraction.

- **Provisions message:** The insufficient-food rejection message was rephrased from "doesn't have enough energy to travel that far. Feed them first." to a provisions-themed message with a rounded-up cooked steak estimate and hunger point shortfall (e.g., "needs provisions for this journey — roughly 3 cooked steak worth of food (~22 hunger points)").

- **Magic travel bypass:** Remote Guidance spell now uses `beginMagicTravel()` which skips the food safety gate entirely and applies no hunger drain on arrival. The reagent cost (ender pearls) is the price. Cooldown and other gates remain enforced. The `magicTravel` flag is threaded through `PendingTravel` record and `SavedTravel` DTO for persistence across server restarts.

## 2026-04-09 — Rejoin remount only fires when bot was actually riding

- **User report:** A bot on foot, near a horse that's safely tethered to a fence, mounts the horse on world load. The user never told it to mount; it wasn't mounted at the previous session close; the horse is already safe. The bot shouldn't be interacting with the horse at all. Intent: rejoin-remount exists solely so that a bot that *was* riding at disconnect (with no way to secure the horse) stays on the horse to prevent it wandering off. Nothing else should trigger a rejoin mount.

- **Log evidence (1.21.10 latest.log:566-567):**

  ```text
  Mount restore: found minecraft:horse at -1236, 69, -51
  Mount rejoin restore: bot=jake mount=... wasMounted=false heldByBot=false remounted=true heldRestored=false
  ```

  `wasMounted=false heldByBot=false` but `remounted=true`. No "already fence-tethered" skip log was emitted between the find and the remount.

- **Root cause:** [MountPersistenceService.maybeSecureOnRejoin](src/main/java/net/wcfcarolina13/GameAI/services/MountPersistenceService.java#L400) had three branches: restore-held-lead, skip-if-fence-tethered, else remount. There was no guard for `wasMounted=false && heldByBot=false` — that case fell straight through to `tryRemountAfterRejoin`. Historically (commit 6942d74, 2026-04-08) the `!wasMounted` guard was intentionally *removed* because an older save-path bug produced stale `wasMounted=false` even after rides. That bug is no longer present: the disconnect path at [Frens.java:751](src/main/java/net/wcfcarolina13/Frens.java#L751) and [BotPersistenceService.java:238](src/main/java/net/wcfcarolina13/GameAI/services/BotPersistenceService.java#L238) now deliberately writes `wasMounted=(secureResult==CANNOT_SECURE)`, so `wasMounted=true` is a reliable "must remount to keep horse from wandering" signal and `wasMounted=false` is a reliable "horse is safe or bot wasn't riding" signal. Re-gating on it is correct.

- **Fix:** Added a top-level branch at the start of `maybeSecureOnRejoin`: if `!state.wasMounted() && !state.heldByBot()`, log a no-op and leave the horse completely untouched. The other branches keep their semantics unchanged — `heldByBot` still restores the leash-target relationship, fence-tether still skips the remount (for `wasMounted=true` cases where the horse got secured between save and load), and the normal remount path only fires when `wasMounted=true` and the horse isn't fence-tethered.

- **Follow-up (not fixed in this commit):** The fence-tether skip inside the `wasMounted=true` branch still uses `mob.getLeashHolder() instanceof LeashKnotEntity`. When a mob is freshly loaded via chunk force-load on the same tick, its `leashData` stores an unresolved `BlockPos` and `getLeashHolder()` returns null until the mob's next `tickLeash()` runs. So a `wasMounted=true` bot next to a now-fence-tethered horse would incorrectly fall through to remount. The top-level guard added here sidesteps the issue for the user's reported case, but the latent timing bug remains for the narrow `wasMounted=true` path. Worth a follow-up with either tick-deferral or direct leash NBT inspection via a mixin accessor.

## 2026-04-08 — Irrigation Infinite Source Fix

- **User report:** bot dug the 2x2 irrigation hole but only created ONE water source (in a single corner), not an infinite 2-source diagonal pair. Log at 1.21.10 12:48:59 showed `Irrigation hole filled on attempt 1 (still=1, water=4, flow=3, dry=0)` — only 1 source, but code said "filled".

- **Root cause — placeWater success check false-positives on pre-existing flowing water:** [FarmSkill.placeWaterOnServerThread:2530-2536](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java#L2530) used `world.getBlockState(waterPos).isOf(Blocks.WATER)` as a fallback success check. In a 2x2 hole, after placing the 1st source at NW, flowing water spreads into SE before the 2nd placement runs. When the bot tries to place at SE:
  1. `useOnBlock` via floor (face=up) returns `ActionResult.Pass` — the bucket is **not consumed**, hand stays `minecraft:water_bucket`.
  2. The `isOf(Blocks.WATER)` check sees the SE cell already contains flowing water and reports success.
  3. Function returns `true` without running the `interactItem` fallback.
  4. Bot moves on thinking both corners are done. Only 1 actual source exists.

- **Fix H1 — bucket consumption as proof of placement:** Replaced `isOf(Blocks.WATER)` with a comparison of the main-hand item before and after the interaction. A `WATER_BUCKET` that became a plain `BUCKET` is the only reliable proof that a water source was placed by the bot's action. Pre-existing flowing water no longer confuses the check. Added `consumed=true|false` to the log lines for future diagnostics. Applied to both the per-`BlockHitResult` loop and the `interactItem` fallback.

- **Why this is correct even with the "flowing → still" propagation the user pointed out:** Vanilla water bucket `use` either places a source block (and consumes the bucket) or does nothing (Pass result, no consumption). When you place a source adjacent to another source via bucket, the call **does** place a source at waterPos (which may immediately look like flowing water due to neighboring sources, or propagate into a source over the next few ticks). The bucket is consumed either way. So checking consumption captures all valid placements — including ones where the cell is already "water" because a neighbor was spreading in.

- **Fix H2 — remove dangerous 1-source acceptability fallback:** `isAcceptableIrrigation` had a `still >= 1 && water >= 4` fallback commented as "snow/cold-biome fallback". This only ever fired when `placeWaterOnServerThread` falsely reported success for the 2nd corner (producing exactly `still=1, water=4`). A single source in a 2x2 hole isn't an infinite supply — `finalTopOffBuckets` can't refill from it (1.21.10 log 12:53:01 confirmed: `finalTopOffBuckets: could not fill all buckets (left empty=1)`). The fallback is removed; `awaitAcceptableIrrigation` now correctly waits for either 4 sources or 2+ sources with 3+ water cells.

- **Expected behavior:** The 2x2 hole should now actually get 2 diagonal source placements, propagate to 4 sources, and serve as a true infinite refill source for subsequent bucket top-offs.

- **Files:** `src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java`. `./gradlew build -x test` ✅.

## 2026-04-08 — Bounded Woodcut Stillness Fixes

- **Root cause (1.21.10 log 12:23–12:24):** After the farm phantom-precipice fix the bot finally selected a tree and entered `fellTree` — and **immediately crashed** with `UnsupportedOperationException` in [WoodcutSkill.collectRemainingEnvelopeLogs:1885](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java#L1885). The crash propagated up to `execute()`'s outer `finally`, which ran `runWoodcutCleanup` for **42 seconds** in the bounded region, producing the "bot standing still" symptom before the exception finally surfaced as "Inline woodcut failed: null".

- **Fix G — UOE in `collectRemainingEnvelopeLogs`:** Latent bug, pre-existing. The bounded-mode branch filtered `remaining` via `.stream().filter(this::isWithinRequestedBounds).toList()` — `Stream.toList()` returns an **unmodifiable** list (since Java 16). The very next line called `remaining.sort(...)`, which throws `UnsupportedOperationException` on an unmodifiable list. This never fired before because the old `isTreeWorkEnvelopeWithinBounds` rejected every bounded-mode target before reaching `fellTree`. Fix A (relaxed envelope check) finally let a tree through, which instantly tripped this latent bug. Replaced `.toList()` with `.collect(Collectors.toCollection(ArrayList::new))` and added the `java.util.stream.Collectors` import.

- **Fix D-broader — unconditional bounded-mode cleanup skip:** Previously Fix D only skipped the outer-finally `runWoodcutCleanup` when `felled > 0`. But on the exception path `felled == 0`, so the 42s cleanup still ran. Broadened the condition: in bounded mode we **always** skip the full-region cleanup in the outer `finally`, because either (a) per-tree maintenance already cleaned each felled tree, or (b) fellTree crashed/exhausted and the bounded-region scan will find zero actionable targets (the trees are still intact) no matter how long it runs. The bounded caller (FarmSkill) owns cleanup responsibility; WoodcutSkill should not spend 35+ seconds on a region it already knows is full of intact trees.

- **Other `.toList()` usages audited:** Five other `.stream()...toList()` sites in WoodcutSkill were reviewed. All are either return statements, read-only iteration, or one-shot `.get(0)` / `.isEmpty()` reads. None feed into mutation operations. No other fixes needed.

- **Expected behavior:** Farm tree clearing in a forest should actually fell trees instead of standing still for 42s then falling back to the brute clear. If fellTree crashes for any other reason, the bot should immediately fall back to the local brute clear instead of burning cleanup budget first.

- **Files:** `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java`. `./gradlew build -x test` ✅.

## 2026-04-08 — Farm Phantom Precipice Fix

- **Root cause:** `FarmSkill.assessFarmSite` used `Heightmap.Type.WORLD_SURFACE` to measure per-column surface heights. WORLD_SURFACE includes every non-air block, so in a forest it returns canopy tops (y=78–85), not walkable ground (y=65–68). The median became a mid-canopy Y, then `hasSimplePrecipice` sampled a 7×7 area at that inflated Y, found 4+ air blocks below each sample (the real ground was far below), and rejected with `precipice-near-center` — even though the actual terrain was fine and the user just wanted the bot to landscape. Same bug in `estimateFarmAreaMedianSurfaceY`.

- **Fix F1 — walkable ground Y:** Replaced `world.getTopY(WORLD_SURFACE, …)` with `SafePositionService.getWalkableGroundY(world, x, z)` in `assessFarmSite`'s main column loop, its inline water-surface check, and `estimateFarmAreaMedianSurfaceY`. `getWalkableGroundY` scans down from `MOTION_BLOCKING_NO_LEAVES`, skipping logs and leaves, and returns the Y where feet stand on the first solid block. Median farm Y now reflects real ground, not canopy.

- **Water detection correctness:** After Fix F1, `walkableY - 1` is the solid block (never water, since `getCollisionShape().isEmpty()` skips water). Water surface is now detected by checking the block AT `walkableY` (what sits directly above the first solid block). Both the dry/water classification at the top of `assessFarmSite` and the in-loop `waterSurface` flag were updated.

- **Fix F2 — removed two redundant `hasSimplePrecipice` rejections:**
  - Pre-assessment guard (in `execute()`) that bailed with "Unsafe drop near farm site" before any tree clearing happened.
  - `assessFarmSite` `precipice-near-center` guard run right after `selectFarmTargetY`.
  Both were strictly redundant with the per-column `grade-cut-too-deep` / `fill-too-deep` loop: the precipice check sampled a 3-block radius around center, which for a 10×10+ footprint lives entirely inside the footprint and is therefore already covered by the per-column loop. The per-column loop is also more honest about WHY it rejects ("I'd need to fill 6 blocks here, max is 3") vs the opaque "precipice-near-center".

- **Kept:** `hasSimplePrecipice` function itself plus its use in `assessFarmWorkingGround` (staging pad / access path check) and `isUsableFarmStandingGround` (live standing-spot check). Those call sites receive specific upstream-chosen positions — after Fix F1 they operate on real-ground Ys, so they should no longer false-positive. If they still do, a follow-up can relax or remove them.

- **Expected behavior:** `/bot skill farm targetX=… targetY=… targetZ=… manual=true` in a forested area should now accept the site and proceed to tree clearing + terrain prep, rather than rejecting with a phantom precipice. The bot will landscape (cut and fill up to `MANUAL_MAX_CUT_DEPTH=4` / `MAX_FILL_DEPTH=3`) and only reject if the real terrain variance actually exceeds those limits — with an honest reason string.

- **Files:** `src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java`. `./gradlew build -x test` ✅.

## 2026-04-08 — Farm Tree Clearing Uses Real Woodcut

- **Root cause (from 1.21.10 log 08:45–08:48):** `/bot skill farm` spent 3+ minutes clearing trees without ever actually felling one. WoodcutSkill's `isTreeWorkEnvelopeWithinBounds` required the tree's leaf envelope, expanded by `WOODCUT_LOG_SCAN_EXPANSION=4` in every direction, to fit entirely inside the caller's bounds. For a farm with a 13×10×13 AABB that rejected every oak found (16 of 16 logs → `out-of-bounds-tree-envelope`), so the farm fell back to `clearBlockingTreeBlocksLocally` — an arbitrary-order "mine any log/leaf in the box" loop with no tree topology awareness. That's where the floaters came from. Each pass also wasted ~35s in a full-region `WoodcutCleanupSkill` that had no targets to find.

- **WoodcutSkill.isTreeWorkEnvelopeWithinBounds (Fix A):** Relaxed to require only that the trunk (`base` → `top`) fit inside the caller's bounds. The canopy/leaves may legitimately overhang — authority to mine them for the tree currently being felled is granted via the new per-tree work envelope overlay (below).

- **WoodcutSkill per-tree work envelope overlay (Fix B):** Added transient fields `activeTreeWorkEnvelopeMin/Max`, set just before `approachBase`/`fellTree` are called for a selected target and cleared in a `finally` at the end of that tree's iteration (and in the outer `finally` for belt-and-suspenders). `isWithinRequestedBounds(pos)` now returns true if the pos is either inside `activeRequestedBounds` OR inside the active tree's expanded envelope (`envelopeMin/Max ± WOODCUT_LOG_SCAN_EXPANSION`). This lets `mineBlockDetailed` prune canopy and leaves that overhang caller bounds, but only for the tree the bot is actively felling.

- **WoodcutSkill skip redundant cleanup (Fix D):** The outer `finally` block normally calls `runWoodcutCleanup` with a hardcoded 35s budget. In bounded mode with at least one tree felled, per-tree `runPerTreeMaintenance` → `runLocalTreeCleanup` has already cleaned each tree. Skipping the full-region pass saves ~30s per farm tree-clear pass while preserving cleanup for open-ended runs and for bounded runs that felled zero trees.

- **FarmSkill work buffer vs. query buffer (Fix C):** Split the two concerns that previously shared `FARM_WOODCUT_BUFFER=2` / `FARM_WOODCUT_VERTICAL_RANGE=9`. The tight values are retained for `isWithinFarmWoodcutBounds` and `collectBlockingTreeBlocks` (detecting real obstructions to the footprint). A new wider pair — `FARM_WOODCUT_WORK_BUFFER=6` / `FARM_WOODCUT_WORK_VERTICAL_RANGE=20`, plus `anchorY-2` for the lower Y — is passed to `runWoodcutInline` so WoodcutSkill has elbow room to approach, scaffold, and prune canopies. Without this, even Fix A would still reject most target trees.

- **FarmSkill retires brute-clear as primary (Fix E):** `escapeTreeAndWoodcut` and `clearBlockingTrees` previously ran `clearBlockingTreeBlocksLocally` in parallel with the inline woodcut on every pass, producing floaters. Both call sites now re-count blockers after the woodcut call and only fall back to the local brute clear when the woodcut failed to reduce the count (last-ditch only). When Woodcut is succeeding, it owns the clear.

- **Expected improvements:** Tree-clearing passes for `/bot skill farm` in forested areas should actually fell trees via real woodcut (top-down, scaffolded, canopy-aware). Per-pass wall-clock time drops from ~40–80s of wasted rejection loops to ~10–20s of real felling per tree. Floaters should be ~zero because fellTree does the mining instead of the brute per-block loop.

- **Files:** `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java` (Fix A/B/D), `src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java` (Fix C/E). `./gradlew build -x test` ✅ after each commit.

## 2026-04-07 — Durability Preservation Toggle

- **Feat: Durability preservation toggle.** Players can now configure bots to refuse low-durability gear as a trade-off between preservation and performance. Toggle via Admin → Behavior section of the bot control menu (visible to all players). When enabled, bots avoid equipping or using enchanted/expensive gear below 11% durability (3% in combat). Mending-enchanted items count as "preserved" and are always safe to use.

- **DurabilityPolicyService:** Core rule engine with per-player policy storage (`ManualConfig.playerPreserveExpensiveGear`). Three methods: `shouldAvoid(bot, stack)` (11% or 3% durability threshold based on context), `isPreserved(stack)` (preserved-material or enchanted check), `isPreservedMaterial(stack)` (gold/diamond/netherite/turtle-shell item set). Policies sync C2S via `UpdatePlayerPreservePayload` and S2C via `PlayerPreserveStatePayload`.

- **DurabilityFallbackService:** Automated fallback chain on a dedicated executor. When a tool/armor is filtered, the bot tries: (1) alternate tool in inventory, (2) matching tool from registered chests, (3) craft a replacement, (4) stand down the task if no compliant option exists. Per-bot per-category fallback cooldown (20 seconds) prevents thrash. Bot death and toggle OFF/ON flip clear cooldowns.

- **Hook sites and tool/armor enforcement:**
  - `ToolSelector.selectBestToolForBlock()` — gates tool selection before every action
  - `armorUtils.autoEquipArmor()` — gates armor equipping; `BotEventHandler.tickDurabilityArmorAudit` (3-second tick per bot, out-of-combat only) actively equips compliant replacements
  - `MiningTool.mineBlock()` — mid-task re-poll every 20 ticks allows freshly-added tools to be adopted mid-mine
  - `CombatInventoryManager.findBestWeaponSlot()` (returns `OptionalInt` slot index) and `BotActions.selectBestWeapon()`/`selectBestMeleeWeapon()`/`selectBestRangedWeapon()` — two-pass filter chains; if only low-durability "real" weapons exist, bot falls back to iron pickaxe/axe/shovel/hoe as melee alternative
  - `FishingSkill.execute()` — rod durability checked at cast time
  - `WoolSkill.execute()` — shears durability checked via `ensureShearsEquipped()` before each shear attempt
  - `ElytraFlightService.tick()` — elytra durability checked before launch

- **Dialogue system integration:** Three new overhead line pools added to `CompanionOverheadDialogueService`: `GEAR_PRESERVE_SWAP_LINES` (gear swap triggers), `GEAR_COMBAT_EDGE_LINES` (low durability in combat), `GEAR_NO_REPLACEMENT_LINES` (no compliant fallback). Wired to 7+ hook sites. All three pools on separate 2-minute per-bot cooldowns with global overhead suppression.

- **UI updates:**
  - `BotPlayerInventoryScreen`: Admin → Behavior section now includes `PRESERVE_EXPENSIVE_GEAR` toggle alongside `LOCK_BLOCKS_MODE`. Both visible to non-admin players (can view/toggle settings for their own bots).
  - `BotControlScreen`: Removed "Lock Blocks" footer button; functionality moved to Admin → Behavior.
  - `BotPlayerPreferencesScreen`: Created but currently unused — the screen-based approach was abandoned in favor of Admin → Behavior integration. File kept alive as `SERVER_VALUE` static cache.
  - `BotGuideScreen`: New guide topic `settings_preserve_expensive_gear` with full explanation, examples, and trade-offs.

- **Network payloads:** Three C2S/S2C messages: `UpdatePlayerPreservePayload` (toggle action), `RequestPlayerPreservePayload` (refresh request), `PlayerPreserveStatePayload` (broadcast sync).

- **Command-line verification:** `/bot testgear <bot>` gives a preset inventory showing: preserved-below items, compliant alternatives per category, and notes on shortages. Useful for manual auditing.

- **Behavioral notes:** Armor strip only at equip time — no mid-combat armor removal. 3-second out-of-combat audit ensures bots actively swap into compliant gear when it becomes available. 20-second per-category fallback cooldown prevents fallback thrashing while tool sources are being located. Default toggle state: OFF (no behavior change until opted in). Bot death auto-clears fallback cooldowns; toggle flip (OFF→ON or ON→OFF) auto-clears all stale cooldowns for that player’s bots.

- **Spec and plan:** Implemented against `docs/superpowers/specs/2026-04-07-durability-preservation-toggle-design.md` and `docs/superpowers/plans/2026-04-07-durability-preservation-toggle.md`. Design trade-offs, architecture, and scope documented there.

## 2026-04-06 — Dialogue tuning & unused voiced lines

- **Overplayed line tuning:** Reduced frequency of "I can't believe this is my job" and "If we die I'm blaming the terrain" (WEIGHT_COMMON → WEIGHT_RARE). Reduced "I am Steve" trigger probability by 75%. Increased wolf proximity cooldown from 90s to 6min to reduce "Guard dog on duty" / "Who's a menace" spam.
- **New weather triggers:** Bots now react to weather transitions (rain, snow, thunder, clearing) when sky-visible. 5-minute cooldown, biome-aware (snow vs rain in cold biomes).
- **Cooking proximity:** Bots comment when near a lit campfire or furnace (8 lines, 3-minute cooldown).
- **Voiced wake-up:** BotWakeUpDialogueService now plays voiced audio instead of text-only overhead lines.
- **Mount dialogue variety:** 19 alternate phrasings wired across 9 mount situations in RideSyncService (horse hurt, no lead, lost horse, lead snapped, etc.). Each trigger randomly picks from 2-6 phrasings instead of repeating the same line.
- **Combat orphan lines wired:** 4 previously unused combat lines added to active pools — "Engaging!" (engagement alternate), "Enemy down." (generic kill), "All clear." (post-combat), "Hey! Watch it!" (friendly fire).

## 2026-04-06 — Lockable Blocks

- **Lockable block system:** Players can now mark individual doors, fence gates, and trapdoors as "locked" so bots treat them as solid walls. Toggle lock mode via the "Lock Blocks" button in Bot Controls (admin only). While in lock mode: right-click a lockable block to toggle its lock state; crosshair shows lock status; blue soul flame particles highlight all locked blocks within 32 blocks.
- **Bot enforcement:** Locked blocks are treated as impassable by both pathfinders (`BaritoneStylePathFinder`, classic `PathFinder`), the follow bounded planner (`FollowPathService`), and the door-opening system (`MovementService.tryOpenDoorAt`). Bots show a brief overhead reaction line when first encountering a locked block.
- **Persistence:** Lock state persists across server restarts in `bot_zones/[world]/locked_blocks.json`. Global to all bots owned by the locking player.

## 2026-04-06 — Pathfinding: narrow passage & gate traversal

- **Pathfinder passability: trapdoors:** `BaritoneStylePathFinder` and classic `PathFinder` now treat `TrapdoorBlock` as passable (wooden = openable, iron = collision check). FenceGateBlock intentionally excluded from pathfinder passability to prevent routing through animal pens — fence gates are handled reactively by FollowPathService and the door-escape system.
- **Narrow passage alignment:** When the bot is stagnant near a 1-wide gap (doorway, archway, narrow opening), `FollowMovementService` now detects the chokepoint and steers the bot toward the gap center instead of approaching diagonally and clipping wall corners. Applies to both waypoint following and direct pursuit. Cardinal-direction gaps only for now; diagonal gaps flagged for future work.

## 2026-04-06 — Bot Anvil Screen

- **Feat: Bot anvil via Spells menu.** Players can use anvils on behalf of bots — repair, rename, or combine items using the bot's XP. Same Spells-menu pattern as enchanting. Vanilla anvil UI, bot's inventory in the grid.
- **BotAnvilScreenHandler**: Extends `AnvilScreenHandler`. Overrides `canTakeOutput` (bot XP check), `onTakeOutput` (bot XP deduction via save/restore trick), and `onClosed` (items return to bot). Rename via `RenameItemC2SPacket` works automatically. "Too Expensive" (>=40 levels) still enforced.
- **BotAnvilOpenPayload + BotAnvilNetworkManager**: C2S payload + server-side handler validates proximity, finds nearest anvil (BlockTags.ANVIL), opens screen.
- **CompanionSpellsScreen**: New "Anvil" button (enabled near any anvil). Tooltip added.
- **BotGuideScreen**: New "Bot Anvil" guide topic under Enchanting section.
- **modCommandRegistry**: Added `findNearestAnvil()` returning `BlockPos`.

## 2026-04-05 — Bot Enchanting Screen

- **Feat: Bot enchanting via Spells menu.** Players can enchant bot items using the vanilla enchanting UI. Open the Spells menu near an enchanting table, click "Enchant" — the standard enchanting screen opens backed by the bot's inventory, XP, and enchantment seed. XP and lapis are deducted from the bot, not the player. Leftover items return to the bot's inventory on close.
- **BotEnchantmentScreenHandler**: Extends `EnchantmentScreenHandler` with full `onButtonClick`/`onClosed` reimplementation to redirect all player references to the bot entity. Private vanilla fields accessed via parallel references and slot delegation.
- **BotEnchantOpenPayload + BotEnchantNetworkManager**: C2S payload + server-side handler validates proximity, finds nearest table, opens the screen for the real player.
- **CompanionSpellsScreen**: New "Enchant" button (enabled only near enchanting table). Tooltips added to all 5 spell buttons.
- **BotGuideScreen**: New "Enchanting" guide section with step-by-step instructions.
- **EnchantingAmbientDialogueService**: Bots near enchanting tables with 20+ levels comment about their XP. Two dialogue tiers (20+ and 30+), 2-minute per-bot cooldown with global overhead suppression.
- **modCommandRegistry**: Added `findNearestEnchantingTable()` returning `BlockPos`.

## 2026-04-05 — Fishing Sunrise Resume + Open Water Positioning

- **FishingSessionService**: New persistence service for multi-day fishing sessions (mirrors HuntSessionService). Saves stand, water, cast target, catch count. 24h expiry.
- **Sunrise resume**: FishingSkill saves session at sunset, resumes at the same spot next morning. Cast target re-evaluated on resume. Progress (catch count) preserved.
- **BotAutoReturnSunsetService**: Fish-specific sunrise resume path alongside existing hunt path.
- **Vanilla open water**: `isVanillaOpenWater()` checks 5x4x5 area per Minecraft spec (water layers, air layers, sky access). Cast targets passing the check get -3.0 scoring bonus for treasure-quality catches. Silent fallback if no open water available.
- **Loop reorder**: Sunset check moved before abort check to prevent session loss from abort latch race.

## 2026-04-05 — Travel respawn ghost entity fix

- **Fix: Bot stuck at old position after fast travel.** When the travel system respawned a bot via `createFakePlayer.createFake()`, the vanilla `PlayerManager.onPlayerConnect()` tried to disconnect the existing entity (same UUID) via `networkHandler.disconnect()`. But `FakeClientConnection.handleDisconnection()` is a no-op, so `onDisconnected()` never fired and `PlayerManager.remove()` was never called — the old entity stayed in the player list. This left two entities with the same name: UUID-keyed lookups returned the new (correct) entity, but list iteration (`BotTargetingService.collectActiveBots()`) returned the old (stale) entity first. Skills and commands targeted the underground ghost entity instead of the real one on the surface. Fix: `respawnBotAtDestination()` now explicitly finds and removes the old entity before creating the new one.

## 2026-04-04 — Chest tool retrieval

- Added `ToolProvisionService.retrieveToolFromChests()` — general-purpose method for retrieving tools from registered chests within a configurable range
- Axe-specific helpers: `allowedAxeSnapshotFilter()`, `allowedWoodcutAxePredicate()`, `axeTierComparator()` — wooden/stone/copper only, no enchanted, min 8 durability
- WoodcutSkill integration: `prepareWoodcutTooling()` now tries chest retrieval after crafting fails; 4 mid-session call sites use `ensureAxeOrRetrieve()` wrapper
- Bot sends chat message when it finds an axe in a chest

## 2026-04-03

- **Feat: General Protected Zones.** Admin-friendly zone protection system with Litematica-style selection. `/bot zone wand` gives an enchanted blaze rod; right-click two blocks to define a 3D bounding box, cyan particles preview the boundary, press `=` to name and save. Bots cannot break blocks inside zones. Any player can view zone boundaries via Base Manager > Protected Zones > Show. Admins can rename/delete zones in the UI. Migrated `ProtectedZoneService` from center+radius to AABB (min/max corners) with automatic legacy migration on load. New files: `ZoneVisualizerService`, `ZoneNetworkManager`, `ZoneNamePopupScreen`, 8 network payloads. Bot Guide updated with Protected Zones topic.

- **Feat: Woodcut hazard avoidance.** New `WoodcutHazardScanner` probes 6 blocks in each cardinal direction from a tree base, classifying terrain as SAFE, SHALLOW_WATER, DEEP_WATER, or RAVINE. Trees fully enclosed by hazardous terrain are rejected. Scaffold placement skips hazardous directions and never places over water. Bridge sweeps skip ravine/deep-water directions.

- **Feat: Scaffold cleanup recovery.** When scaffold blocks are unreachable during cleanup (e.g., over a ravine), the bot now tries to bridge to nearby safe ground first. If bridging fails, it abandons the scaffold and escapes via `findEscapeStandNear` (allows precarious positions if 2+ cardinal neighbors are standable).

- **Fix: Scaffold descent (zero-tree-felled root cause).** Phase 4 of `fellTree` iterated scaffold blocks top→bottom but never moved the bot, leaving it stranded at canopy height. Replaced with `descendScaffoldColumn()` which groups scaffold by Y-level and processes top→bottom: mine bridge blocks at each level, run elevated sweep for logs, then mine the column block under the bot's feet to trigger a gravity drop. The bot now reliably descends through its own scaffold column while harvesting logs at each height.

## 2026-04-02

- **Fix: Stairs/slabs cause false-positive stuck detection.** Stairs and slabs are partial blocks the bot walks through normally, but `BotRescueService` saw them as solid collisions and triggered "stuck in blocks" rescue. Added `BlockTags.STAIRS` and `BlockTags.SLABS` to `isRescueProtectedBlock()`, which is checked in both `rescueFromBurial()` and `isLikelyStuckInBlock()`.

- **Fix: ReturnBaseStuck log noise during normal travel.** The TICK log fired every 20 calls even when `stagnant=0` and no escape was in progress, producing hundreds of useless lines per return trip. Now only logs when stagnant > 0, escape is active, or as a heartbeat every 100 ticks.

- **Fix: Stop command not stopping sunset return-to-base.** The `/bot stop` command cleared movement and tasks but left the sunset session active in `BotAutoReturnSunsetService`. The tick loop detected the bot was no longer returning home and re-triggered `startOrResumeReturn` within 1 second. Fix: (1) `/bot stop` now calls `BotAutoReturnSunsetService.clearSession()` to kill the active sunset session, (2) `tickSunsetSession` checks `isInStopCommandGrace()` and clears the session if within the 60-tick grace window, (3) `isEligibleForSunsetAutomation` also checks the grace to prevent re-triggering via the initial automation path.

- **Fix: Stripmine "failed to advance tunnel" after mining columns.** Replaced the primitive `moveTo` loop (20x `moveForward` nudge with exact `BlockPos.equals` check) with `MovementService.execute(DIRECT)` which has real pathfinding. Added `closeEnough` fallback accepting positions within 1.5 blocks horizontal + ±1 Y. Also fixed gravel/sand stabilization drift: the stuck-in-blocks handler pushes the bot sideways during gravel settling, so the bot now saves its position before stabilization and realigns afterward if it drifted.

- **Feat: Woodcut 'Until sunset' mode.** Actions menu now defaults to "Until sunset" (like fishing). Use +/- to set a specific tree count. Updated tooltip and in-game guide. `SkillManager.isOpenEnded()` extended to treat woodcut without a count as open-ended.

- **Feat: Generic sunrise skill resume.** Any open-ended skill interrupted by sunset is saved and automatically resumed at sunrise. At sunset, the system evaluates 4 cases: (1) lodestone compass available → fast-travel home + save resume, (2) at a saved base → save resume, (3) tactical shelter ON → shelter in place + save resume, (4) nothing → no resume. At sunrise, the bot fast-travels back to the worksite via the nearest lodestone compass (within 128 blocks) and re-runs the skill via `PostArrivalAction`. Hunt keeps its own session system.

- **Feat: Lodestones in Bases menu.** Lodestone compass destinations auto-populate in the bases list with a "Lodestone" tag. "Go To" triggers compass fast-travel; "Set Home" designates the compass as home. Edit controls (rename/delete/radius) disabled for lodestone entries.

- **Feat: Smoke signal navigation beacon.** A lit campfire (or soul campfire) with a hay bale underneath acts as a navigation beacon at saved bases. Extends artifact-free fast-travel range: 5x base radius above ground, 2x below ground. 3x delay penalty still applies. Cached for 60 seconds to avoid per-tick scanning.

- **Infra: Base-bypass travel.** Added `beginBaseBypassTravel()` to `NavigationArtifactService` that skips the underground artifact gate for bots at known bases. `findBaseNearPosition()` added to `BotHomeService` for radius-based base lookup by position.

- **Feat: Lodestone compass fast-travel.** Bots can fast-travel to lodestone compass destinations, including cross-dimension. `/bot compass list <bot>` lists all lodestone compasses a bot holds. `/bot compass home <bot> <name>` designates a named compass as the bot's home compass. `/bot compass travel <bot> [name]` fast-travels to a lodestone compass destination. Lodestone compass promotes to ENHANCED nav tier (1x delay multiplier, same as Eye of Ender). Autonomous sunset return uses the designated home compass as a fallback anchor (after HOME/BASE/BED, same-dimension only). Lodestone block is validated before travel; broken lodestones notify the owner.

- **Fix: Lodestone compass not recognized for underground fast-travel.** The underground gate in `beginDelayedTravel` checked `artifactDelayMultiplier` which returned 1.0 only when `hasLodestoneCompass` found a compass with a valid target. If the lodestone was destroyed (target emptied), the compass fell through to the generic `Items.COMPASS` check (returning 2.0), then the underground gate required both a filled map AND compass — failing for a lodestone compass alone. Three fixes: (1) Added `hasAnyLodestoneCompass()` lenient check that accepts any compass with a `LODESTONE_TRACKER` component regardless of target state, (2) Underground gate now explicitly recognizes lodestone compasses as a standalone navigation tool (2x delay, same as Map+Compass), (3) `ReturnBaseStuckService` now attempts fast-travel via `beginBaseBypassTravel` before pillar-up when the bot has a lodestone compass underground. Updated error message to mention "Lodestone Compass" as an option.

- **Fix: Suppress post-task return for underground mining skills.** After every stripmine or mining task, `TaskService.complete()` scheduled a post-task fast-travel that always failed underground with "cannot fast-travel underground without a Map and Compass." The bot is intentionally underground after these skills, so the return attempt is pointless and the refusal message is spam. Added `isUndergroundSkill()` gate in `complete()` that skips `schedulePostTaskReturnIfEnabled()` for `skill:stripmine` and `skill:mining`.

- **Fix: Co-sleep skips bot silently when commander enters bed.** When the commander enters a bed, `triggerCoSleep` checks each bot for eligibility (idle, nearby, nighttime, etc.). All skip reasons logged at DEBUG level, making failures invisible. Upgraded all skip logs to INFO for diagnostics. Added 3-second delayed retry for bots skipped due to active tasks — handles the common case where a follow/come transition is still completing when the commander gets into bed. The retry re-checks all conditions independently.

## 2026-04-01

- **Fix: Cherry/hillside trees rejected by soil validation.** `resolveStandingTreeBasic` checked only `base.down()` for valid soil — cherry trees on hillsides have trunks starting above ground (e.g., base Y=106, dirt at Y=101), so the block at Y=105 is air and fails the check. Now scans downward up to 5 blocks through air/replaceable blocks looking for soil, stopping at the first solid non-soil block (stone, etc.). Unblocks cherry blossom, acacia on cliffs, and any tree with an air gap below its trunk.

- **Fix: Beehive-protected logs no longer cause infinite mine-reject loop.** `mineReachableBranches` rescanned every pass, re-finding the same protected block. Added `protectedSkips` set to both WoodcutSkill and BridgeScaffoldService so each protected block is attempted only once.

- **Feat: Horizontal bridge scaffold service.** New `BridgeScaffoldService` builds temporary 1-block-wide horizontal bridges outward from a perch, mines targets at each step, then retracts. Supports safe mode (full sneak) and ninja bridging (sneak-toggle at block edges for ~2x speed). Integrated into WoodcutSkill descent phase — at each pillar level, the bot probes all 4 cardinal directions for reachable logs and bridges out up to 6 blocks to harvest them. Handles: sneak-lock safety, fall detection, proactive leaf clearing, target-at-floor mining, pre-flight material check, and clean retraction with scaffold teardown.

- **Fix: Chest offload corridor clearing skips tree trunks.** `clearSoftStorageBlock()` in ChestStoreService only cleared leaves/snow/replaceable blocks. When a tree trunk log blocked the 4-block corridor between the bot and the chest stand, the clearing skipped it and the bot got permanently stuck — unable to take even 1 step, burning 55+ seconds before the 3-consecutive-no-move early exit fired. Added `BlockTags.LOGS` to the clearable set so trunk logs in the approach corridor are mined (1-tick with any axe).

- **Feat: Wider column corridor + elevated sweep during descent.** `preferredColumnCorridorRadius` was capped at 3 blocks even for tall trees — logs 4-7 blocks from base (common in large oaks) were heavily penalized or unreachable. Widened to height-proportional: short trees ≤4 → radius 2, medium ≤7 → 3, tall ≥8 → 5. Also added an elevated sweep during scaffold descent: at each level after breaking a scaffold block, the bot now scans a 9x7x9 cube for ANY unprotected log within reach, not just tree-envelope logs. This catches diagonal branches, adjacent-tree fragments, and stragglers that `mineReachableBranches` missed.

- **Refactor: WoodcutSkill cleanup — dead code, thread safety, overload shells.** Removed 7 unreachable private methods (~140 lines): `selectNextOwnedLogTarget`+`scoreOwnedLogCandidate`, `isWithinWoodcutEnvelope`, `findNearbyChests`, `forcePillarToward`, `findStandableNear`, `findNearestOverheadLog`. Fixed 2 ScaffoldService `.join()` calls that blocked indefinitely — added 5s timeouts matching codebase patterns. Removed 4 delegation-only overload shells (`moveToStandDirect`, `tryRerouteUnsafeWoodcutMove`, `clearBlockingLeaves`, `clearHeadroom`) that were never called from outside their own definitions. Net: ~165 lines removed, 0 behavioral changes.

- **Fix: Woodcut trunk entry targets unreachable above-canopy position.** `resolveTrunkEntryStand()` unconditionally preferred occupiable (already-clear) cells over carveable (need-mining) cells. For intact trees, the only occupiable cell in the column is air above the canopy — the bot targets it, fails to navigate there 3 times, and gives up with 0 logs mined. Merged occupiable and carveable lists into a single scored ranking with a small carve penalty (+3). Now selects the carveable trunk base (score 3) over above-canopy air (score 6), so the existing carve+enter machinery actually fires. Also enhanced descent phase: reasserts sneaking before each level and mines branches both before and after each scaffold break for better branch coverage.

- **Fix: Tree count argument ignored.** `/bot skill woodcut 4` still cut indefinitely because `radiusClearMode` defaulted to `true` when not `openEnded`, making the while-loop condition always true. Now detects explicit `count` parameter and sets `radiusClearMode=false`, so the loop terminates at `felled >= targetTrees`. Default behavior without explicit count unchanged.

- **Fix: Saplings never planted.** Two issues: (1) `runPerTreeMaintenance()` (which calls `plantSaplings`) only ran on successful harvests — since most trees fail with PATH_OR_REACH_FAILURE, `replantStatus=not-run`. Now also replants on failed trees if `trunkMineAttemptsStarted > 0` (trunk was actually cut). (2) In `runPerTreeMaintenance`, replanting ran AFTER cleanup and drop sweep, by which time the bot had drifted far from the base (`replantStatus=out-of-range`). Moved `plantSaplings` to run BEFORE cleanup/sweep.

- **Fix: Column selection picks distant neighbor-tree logs.** `scoreScaffoldColumn` penalized out-of-corridor columns by only +5000, which was insufficient vs distant columns with many same-column logs (-40 each). Increased out-of-corridor penalty to +15000 and changed distance penalty from linear (`dist * 20`) to quadratic (`dist² * 15`). A column 5 blocks away now costs 375 vs 100 before; a column 10 blocks away costs 1500 vs 200. Trunk column (-1000) now always wins over distant columns with many logs.

- **Fix: Column stand scan misses cleared trunk interior.** `resolveColumnStand()` only scanned downward from `anchorY`, missing walkable positions ABOVE the anchor created by mining trunk logs. After the bot mines the base log at Y=91, the empty column at Y=91 is a valid stand — but the downward-only scan starting at Y=90 never finds it. Changed to bidirectional scan: upward first (Y+0 to Y+6) then downward (Y-1 to Y-18). This eliminates the "exact-but-unsupported" failures where the bot picks an adjacent non-column stand and then fails `isBotInColumn`.

- **Fix: False "underground" detection under tree canopy.** `NavigationArtifactService` used `isSkyVisible(bot.getBlockPos().up())` for the underground fast-travel gate, which returns false under tree canopy. Bot would show "cannot fast-travel underground without a Map and Compass" when standing at surface level under trees. Added `SafePositionService.getWalkableGroundY()` proximity check — if bot Y is within 4 blocks of the true walkable ground (which skips logs/leaves), the canopy is ignored. Applied to both underground gate checks in NavigationArtifactService (line 535 and line 683).

- **Fix: Woodcut approach stuck in depression with overhead leaves.** When both dry-stand and planner approach movements fail, `clearLocalOverheadObstacles()` now clears soft blocks (leaves, snow, replaceable) in a 3x3x3 volume above the bot's feet before entering `tryDryRepositionAroundTrunk`. The bot was getting trapped at Y=90 with leaves at Y=92-93 blocking all diagonal movement — the pathfinder routed through the diagonal but nudge failed every time at the same distance. Abort check also added to the `tryDryRepositionAroundTrunk` loop to prevent post-stop spam (8 instant movement calls after `/bot stop`).

- **Feat: Leaf clearing throughout woodcut harvesting pipeline.** Added `clearLeafObstructionDetailed()` calls to four key phases that previously had no leaf clearing: `approachBase()` (before both dry-stand and planner movement attempts), `moveToExactStand()` (before column entry movement), and the floater/cleanup loop (before approach to each remaining log). Previously the bot hit pathfinding timeouts and walk-blocked failures in dense forest because leaves obstructed all movement paths but were never cleared. Uses the same proven infrastructure from chest navigation and follow mode.

- **Tune: LOS-prioritized log selection in mineReachableBranches.** `scanReachableLogs()` results are now re-sorted to prefer logs with clear line-of-sight before falling back to obstructed ones. Previously the bot always picked the closest/lowest log which was often the most leaf-obstructed, wasting the leaf-clear budget. Blocked logs remain as fallbacks.

- **Tune: Increased clearPathToTarget leaf budget from 3 to 5.** Dense canopy commonly has 4-5 leaf layers between the bot and branch logs. The previous cap of 3 left many reachable logs unmined. Global per-target cap (24) unchanged.

- **Feat: Woodcut offload now deposits raw ores, flowers, and eggs.** New `MISC_OFFLOAD_JUNK` set: raw iron/copper/gold, lapis, redstone, diamond, emerald, amethyst shard, egg. Flowers detected via `ItemTags.FLOWERS`. All added to both `isWoodcutOffloadCandidate()` and `DEFAULT_STORE_ITEMS`.

- **Tune: Early interaction check skips wasted nudge attempts during chest offload.** `attemptChestStand()` now checks `canInteract` before starting movement — if the bot is already within interaction range after preclear/leaf-clear, it skips the full movement cycle entirely. Eliminates ~4s of failed nudge attempts when the bot reaches chest vicinity during path walking but isn't on the exact stand tile.

- **Feat: Woodcut offload now deposits mob drops and junk items.** `isWoodcutOffloadCandidate()` expanded with `MOB_DROP_JUNK` set: rotten flesh, bones, string, gunpowder, spider eyes, slime balls, leather, feathers, ender pearls, phantom membranes, ink sacs, glow ink sacs, rabbit hide/feet, armadillo scutes, honeycomb, cobweb. Same items added to `DEFAULT_STORE_ITEMS` for general `/bot store`. Previously only wood, saplings, seeds, raw food, leaf litter, and apples were offloaded during woodcut — junk accumulated and filled the inventory.

- **Fix: Chest offload navigation fails in forest terrain.** ChestStoreService used basic DIRECT movement with `fastReplan=true` (900ms walkDirect + 1200ms pursuit budget) to reach chests, while woodcut/follow used enhanced 8-directional pathfinding with leaf clearing and longer budgets. Five changes: (1) New `CHEST_NAVIGATION` movement profile with `fastReplan=false` (2400ms walkDirect + 3s pursuit + 20s walkTo deadline) for local chests within 50 blocks; distant remembered chests keep the quick `OBSTACLE_AWARE_PROBE` to avoid 20s blocks on unreachable targets. (2) Leaf clearing via `clearLeafObstructionDetailed()` before movement attempt and as a retry after first failure — same infrastructure WoodcutSkill uses for trunk entry. (3) `preclearStorageApproach()` expanded to clear a 4-block corridor from bot toward the stand instead of only at the destination. (4) Stand candidate Y-range widened from ±1 to ±3 when bot/chest Y differ by 2+, with `findBestSurfaceStaging` radius increased from 3 to 5 for hillside chests. Bot-Y proximity bias added to scoring (−12 per Y-delta) so stands closer to the bot's current elevation are preferred. (5) Abort check (`TaskService.isAbortRequested`) added in `reachChestInteractionStand()` loop and both WoodcutSkill offload probe loops; early exit after 3 consecutive zero-movement failures at the same chest to prevent the 30+ rapid-fire movement spam seen after `/bot stop`.

## 2026-03-31

- **Validate + polish: Route-based shallow-hole escape is working in-game, and ordinary movement is now less jittery.** The latest Prism repro logs show the bot selecting real multi-hop local escape routes (`movement local escape ... route=...`) instead of repeatedly slamming the same wall lip. Follow-up tuning in `MovementService` now keeps a short-lived commitment to a successful local route so the follower does not immediately re-solve the same escape on every next segment. Raw lead-in path segments are also collapsed into longer same-level runs when the terrain is no longer trap-like, preserving precision for true trap/exact-stand cases while making normal movement feel less stilted.

- **Fix: Woodcut no longer digs its own stance support by default, and temporary entry terrain can be restored.** `WoodcutSkill.carveEntryHeadway()` was mining `stand.down()` as part of trunk-entry carving, which produced the 1-block pits seen around harvested trees. The entry carve now prioritizes clearing the stance cell, headroom, and lateral lip without removing the support block underneath. Per-tree maintenance also gained a narrow terrain-restore pass for tracked temporary entry repairs so woodcut cleanup can preserve the local forest floor instead of leaving obvious recovery scars.

- **Fix: Sapling replanting now returns to the harvested tree before placing, and idle woodcut no longer opts out.** Standalone woodcut was already configured to replant, but after local cleanup/drop sweep the bot could drift away from the tree and then silently fail to place saplings. Replanting now returns to planting range first and logs explicit skip/summary reasons. Idle/background woodcut no longer hardcodes `replantSaplings=false`, so hobby runs stop stripping the forest over time.

- **Fix: Shallow-hole escape now uses diagonal local exits and preserves the lead-in hop.** The Baritone-style planner only considered cardinal local movement, so it could miss the same diagonal one-block escape step that `ReturnBaseStuck` later detected. The movement follower also simplified early path nodes too aggressively, which let it keep steering into the same 2-block wall instead of honoring the first escape hop. Added 8-direction flat/step-up/step-down expansion with anti-corner-cutting in `BaritoneStylePathFinder`, taught `PathFinder.convertPathToSegments()` to keep diagonal runs intact, and added a `MovementService` local-escape phase that can temporarily prioritize a nearby standable escape tile or a stuck-service escape directive before resuming the long-range goal. `ReturnBaseStuckService` now promotes natural step-up discoveries into that transient local target instead of only doing a one-tick shove. This is the critical fix for bots trapped in shallow pits beside a valid diagonal exit.

- **Fix: Woodcut utility placement recovers from cramped holes.** When woodcut needed a crafting table or chest while the bot was standing in a self-dug depression, placement could fail immediately because no nearby candidate had enough clearance. Added shared local placement preparation in `CraftingHelper`: scan nearby candidates, clear a minimal placement/interaction pocket using only soft natural blockers or bot-placed scaffold, and if needed perform a short relocation to a nearby usable stand before retrying. Wired into both crafting-table placement and chest placement paths so offload recovery no longer dead-ends in the same hole scenario.

- **Fix: Woodcut exact-stand movement no longer accepts false-positive arrival.** `moveToExactStand()` previously trusted generic movement success / `"already at destination"`-style outcomes even when the bot was not actually occupying the required stand block, producing repeated trunk-entry loops. Exact-stand handling now requires true block occupancy, performs bounded recovery by clearing soft blockers + nudging + force-step attempts, and records distinct failure detail with both bot and stand positions when recovery still fails.

- **Fix: Woodcut recovers stance after failed trunk entry or utility placement.** After a failed column-entry or failed offload/placement attempt, the bot now performs a short dry-stand or surface recovery before continuing. This prevents the follow-on pattern where woodcut immediately resumed target selection from the same shallow hole and re-entered the same blocked movement loop.

- **Tune: Shallow-hole escape escalates sooner and drop sweeping is more proactive.** Repeated unchanged trunk-entry failures with the same stand/detail signature now escalate early instead of consuming the full retry budget. Woodcut’s collection maintenance interval was reduced from 20s to 10s, quick sweep radius/vertical reach were increased, failed column-entry recovery now triggers an immediate sweep, and the woodcut-local drop-sweep budget was expanded.

- **Tune: Reduced noisy tactical logs in woodcut/drop-sweep runs.** Utility placement no longer logs every rejected candidate individually; it now emits one per-attempt summary with rejection counts, sample positions, relocation, and cleared-cell counts. Drop-sweep startup and per-step movement traces were pushed to debug where appropriate, manual-nudge/approach misses were softened from warning-level chatter, and expected pursuit failures for woodcut/drop/utility-placement labels were demoted from `WARN` to `INFO` to keep the log readable during rough-terrain recovery.

## 2026-03-30

- **Fix: Woodcut leaves branches when tree canopies overlap.** `TreeDetector.isOwnedTreeLog()` and `leafBelongsToSelectedTree()` rejected logs/leaves within ±1 horizontal of a neighboring tree's base regardless of height. A branch log 6+ blocks above a neighbor's base was incorrectly rejected. Changed `TreeTarget.nearbyRootedBases` from `Set<BlockPos>` to `Map<BlockPos, Integer>` (base → trunk top Y) so the rejection is bounded to the neighbor's actual trunk range (baseY-1 to topY+1). Branch logs above the overlap zone are now correctly claimed by the selected tree.

- **Feat: Level-by-level scaffold mining for woodcut branches.** Replaced the old "pillar to max height, mine owned logs" approach with column-based level-by-level mining. After trunk is cut, the bot visits XZ columns one at a time, scaffolding up one block per level, mining ALL reachable log blocks at each level (not just owned — orphaned canopy from de-trunked neighboring trees is fair game), then safely tearing down top-to-bottom. Column visit tracking prevents re-scaffolding at the same position. Bot stays sneaked on scaffold and never jumps off.

- **Fix: Scaffold tool selection uses wrong tool on stone-type blocks.** `selectScaffoldToolOrHands()` always preferred shovel, causing shovel-on-cobblestone when scaffolding with cobblestone. Now prefers pickaxe first (correct for cobblestone/deepslate, acceptable for dirt), falls back to shovel, then hands.

- **Feat: Pillar-first scoring for branch log mining order.** `scoreOwnedLogCandidate` now penalizes logs with un-mined logs below them (+20) when the candidate isn't immediately reachable, encouraging bottom-to-top mining within vertical pillar branches.

## 2026-03-29

- **Feat: Follow Teleport toggle.** New per-bot "Follow Teleport" setting in Bot Controls > Behavior. Enables wolf-style catch-up teleport during follow mode only — the bot teleports near you when it falls 15+ blocks behind, gets stuck for 3s, or loses vertical contact (10+ block Y gap). Does not affect skill, sweep, or other teleport behaviors. Off by default. Admin permission "Toggle Follow Teleport" controls which players can enable it. Guide entry added under Settings.

- **Fix: Drop sweep chases items through walls/floors.** Both `findNearestDrop()` (BotEventHandler, radius=20) and `findClosestDrop()` (DropSweeper) had no line-of-sight check — they found ItemEntities through solid blocks via bounding-box queries and the bot would walk into walls trying to reach them. Added a raycast filter: items where the ray hits a solid block more than 2 blocks from the item are skipped. This prevents the bot from chasing unreachable drops behind walls, under floors, or in adjacent caves.

- **Fix: "Trapped on join" breaks furnaces in underground bases.** The join enclosure check (`BotEventHandler` line 694) fired on tick+40 when bot was underground with no sky visible, treating registered bases as traps. The break-free mined through player-placed blocks (furnaces, etc.) and triggered drop sweep chasing unreachable items. Added `BotHomeService.isNearAnyBase(bot, 32)` guard — bots near a registered base skip the enclosure check entirely.

- **Fix: Bot inappropriately pillars to surface after tasks / at night / near commander.** Four related bugs: (1) `shouldSuppressSurfaceRecovery` used `isCommanderNearbyAndUnderground` which required the commander to be at the same Y-level with no sky visible — failed in ravines, cave openings, and multi-level mines. Changed to `isCommanderNearby` (proximity only, any depth). (2) No post-task grace period — after a commander-issued task completed, the bot instantly became idle and BotUndergroundSurvivalService or hobbies triggered surface recovery within seconds. Added 30s grace via `TASK_COMPLETED_TICK` in BotFleeService, hooked from `TaskService.complete()` for COMMAND-origin tasks. Also snoozes idle hobbies for 30s. (3) `ensureAtSurfaceForHobby()` passed `skipLingerCheck=true`, completely bypassing commander proximity, nighttime, and all linger checks. Added `shouldSuppressHobbyEscape()` lightweight gate that enforces commander proximity, nighttime, and post-task grace without the full hunger-timer logic. Guard/patrol stuck-escape routes through the same path. (4) "Non-functional position" bypass (check #0) at the top of `shouldSuppressSurfaceRecovery()` returned `false` (allow surfacing) when `openSky=false AND nearSurface=false`, firing BEFORE commander/base checks. This caused bots in deep underground bases (Y=49 with surface Y=67) to constantly attempt surfacing every 10s even with the commander standing right there. Added commander-nearby and near-base guards: if commander is within proximity OR bot is within 32 blocks of any registered base, suppress the non-functional bypass.

## 2026-03-28

- **Fix: Drop-sweep cobblestone loop.** `DropSweeper.ensureSpaceForDropSweep()` unconditionally called `dropCheapStackForSpace()` after chest storage failed, dropping cobblestone that the sweep immediately re-acquired — cycling every ~7s. Fix: only drop items when chest storage at least partially succeeded (viable offload target exists). When no offload target exists, the sweep terminates cleanly.

- **Fix: ensureAtSurface pillar-first.** Surface recovery previously tried slow step-build and movement-to-staging before attempting pillar escape. Pillar recovery is now attempted immediately after direct movement fails, before step-build. Also removed the starving/no-tool gate on pillar attempts — bots now always try pillar escape when they have scaffold blocks, making escape from shallow surface pits much faster (~2s vs ~40s).

- **Fix: Baritone pathfinder avoids 3-block-deep pits.** Added depth floor check in `tryAddNeighbor`: nodes 3+ blocks below `min(startY, targetY)` are rejected. Prevents the pathfinder from routing through surface depressions and overhangs that the bot can’t easily escape. Legitimate downhill navigation to deep targets is unaffected since the depth floor tracks the lower of start/target Y.

- **Fix: ensureAtSurface "ready" false positive in pits.** `logOperationalSurfaceState` was using the lenient `isRecoveryReadySurface` which has a fallback path that returns "ready" for positions with roof cover and ≤4 block gap, even when `openSky=false` and `nearSurface=false`. Changed to use strict `isOperationalSurfaceAssessment` (requires openSky + nearSurface). This prevents ensureAtSurface from short-circuiting when the bot is in a surface pit, allowing pillar/step-build recovery to proceed.

- **Fix: Phantom fall damage on bot respawn.** `BotPersistenceService` wasn’t resetting `fallDistance` after restoring bot position. Stale `fallDistance` from the previous session caused vanilla fall damage on the first tick after respawn. Added `bot.fallDistance = 0.0F` after `refreshPositionAndAngles()`.

- **Fix: Linger suppression blocking pit escape when commander is nearby.** `shouldSuppressSurfaceRecovery` suppressed all recovery when commander was "nearby and underground," even when the bot was stuck in a non-functional position (no sky, not near surface). Added two guards: (1) Y-level check — if commander is 3+ blocks above the bot, don’t suppress; (2) position assessment — if bot’s position has `openSky=false` AND `nearSurface=false`, allow escape regardless of commander proximity. An idle bot stuck in a pit can’t do anything useful, so it should escape.

- **Fix: Auto-hunt spam loop after surface failure.** When `ensureAtSurface` failed, `BotEmergencyRescueService.tryEmergencyRescue` called `requestDecisionNow()` which reset the auto-hunt cooldown to the current tick, causing dozens of retries per second. Now sets `COOLDOWN_TICKS` (160t/8s) AFTER the emergency rescue call to override any reset.

## 2026-03-27 (session 2)

- **Fix: Hunt approach under tree canopy.** `planLootApproach` rejected all positions under leaf canopy because leaf blocks have non-empty collision shapes, failing the `hasClearance` check. Bot would spin for 60s selecting the same target every 0.5s without ever attempting movement. Fix: `approachTarget` now falls back to direct movement toward the target when `planLootApproach` returns empty, allowing the movement system's existing leaf-mining and stuck-recovery to clear the path.

- **Change: Defaults flipped for teleportDuringSkills (now false) and llmEnabled (now false).** New bots/worlds start with teleport and LLM disabled instead of enabled. Existing per-bot per-world settings in settings.json5 are unchanged.

- **Feat: Global teleport override.** New `globalTeleportDuringSkills` setting overrides all per-bot teleport settings. Command: `/bot config globalTeleport <on|off|clear>`. Also available as "Teleport" toggle in the Global Settings panel of the Bot Control config screen. When off, no bot can teleport or snap during skills. When on (or cleared), per-bot settings apply.

- **Fix: Furnace placement failure causes confusing "Look at a furnace" message.** `resolveFurnaceTarget` ignored the return value of `BotActions.placeBlockAt`. When placement failed (e.g., target position occupied by leaves), the code returned a `StationTarget` pointing to a non-furnace block, causing the caller to show "Look at a furnace, blast furnace, or smoker." Fix: both step 4 (place from inventory) and step 5 (craft then place) now check the return value and only return a `StationTarget` when the furnace is confirmed via re-scan.

## 2026-03-27

- **Feat: Food self-sufficiency after hunting.** Bots now keep hunting until they have 4+ food items in inventory (not just a full food bar). Post-hunt cooking is a synchronous multi-batch cycle: walks to furnace, loads each raw food type sequentially, waits for cooking to finish, refuels as needed, eats while waiting if hungry. Cooking added as an idle hobby (2x weighted when raw food exists); after a hunt (auto or commanded), a "prefer cooking" flag makes it the deterministic next hobby pick. `BotAutoCookingService` threshold raised from food<=5 to food<=10 so auto-cooking activates much sooner. Auto-hunt trigger also considers inventory food: at food 11-14 with zero backup items, hunting still activates.

- **Feat: `/bot set hunger <level> <bot>` command.** Sets a bot's food level (0–20) and zeros saturation immediately, so the bot enters a genuinely hungry behavioral state for testing without waiting for natural drain.

- **Fix: Idle underground bot never triggers surface recovery.** The linger decision tree was only reachable through opt-in services (idle hobbies, auto-hunt). New `BotUndergroundSurvivalService` runs on the tick loop with no opt-in gate — checks idle underground bots every 10s and dispatches `ensureAtSurface()`, which evaluates the full linger tree. Survival self-rescue is now always active.

- **Feature: Underground linger system — smart surface recovery.** Bots no longer panic-escape to surface when idle underground. New `shouldSuppressSurfaceRecovery()` evaluates 12 contextual conditions: teleport grace, active tasks, recent shelter exit (60s grace), commander nearby + underground, no tools + deep underground (wait for rescue), nighttime safety, nearby chest food recovery, well-fed status (food >= 14 lingers indefinitely), critically hungry (food <= 6 surfaces immediately), moderately hungry starts configurable linger timer (default 3 min), and commander death guarding. Tool-less breakfree still allowed near surface with soft blocks overhead (dirt/sand/gravel). Config: `undergroundLingerMinutes` and `undergroundProximityBlocks` in settings.json5.

## 2026-03-26 (session 2)

- **Fix: False teleport detection on fast-travel arrival.** Bot spawns at (0,0,0) then teleports to destination, triggering "External teleport detected" and clearing tasks. Added `BotEventHandler.notifyTravelArrival()` — called from `completePostSpawnSetup()` to seed the position tracker and grace period before the first behavior tick.

- **Fix: "No companions present" at distance.** `shouldOfferNoBotsRestore()` and `isCompanionPresentInClientWorld()` used `client.world.getPlayers()` (entity tracking range ~128 blocks). Replaced with `client.getNetworkHandler().getPlayerList()` (TAB list — all connected players regardless of distance).

- **Fix: Armor not displayed on fallback bot.** When bot entity is beyond tracking range, `findBotEntity()` creates a fallback `OtherClientPlayerEntity` without equipment. Now copies armor/offhand from handler slots 0-4 to the fallback entity via `equipStack()`.

- **Fix: Travel cancel warning not delivered.** `spawnBot()` used `server.getCommandSource()` (no player context) for the message. Changed to `context.getSource()` which carries the issuing player.

- **Fix: Close inventory on fast-travel (item duplication).** When a bot departs via fast-travel, any open `BotPlayerInventoryScreenHandler` for that bot is now closed via `viewer.closeHandledScreen()`. Prevents players from manipulating stale inventory copies while the bot respawns with the original.

- **Fix: Hunger drain uses direct food/saturation set.** Replaced `addExhaustion()` (gradual, may not process before next save) with immediate `setFoodLevel()`/`setSaturationLevel()`. Drains saturation first, then food level for remainder. Visible and reliable.

## 2026-03-26

- **Fix: Food safety gate counts inventory food.** The fast-travel gate only checked current food level, ignoring food items in inventory. Bot with food=18 and a stack of cooked meat was refused a 642-block trip. Now sums `currentFoodLevel + totalNutritionInInventory` as the budget.

- **Fix: External teleport cancels bot tasks.** When a bot is teleported >16 blocks via console (`/tp`), it now cancels active tasks, clears movement goals, stops movement, and suppresses surface recovery for 2 seconds (grace period). Prevents bots from walking back to old destinations or pillaring up through tree canopy after being teleported to a forest.

- **Fix: Fast-travel spawn hardening.** `spawnBot()` now checks `NavigationArtifactService.getPendingTravelByName()` before spawning. If the bot is mid-travel, the trip is canceled and the bot spawns at the player's location with a warning message. Added `cancelTravel()` and `getPendingTravelByName()` to NavigationArtifactService. Prevents duplicate bots from spawn-during-travel race conditions.

## 2026-03-25 (session 2)

- **Fix: Surface recovery ascent stuck on LoS.** `executeUpwardStep()` tried to mine blocks 2-8 blocks forward, but MiningTool's line-of-sight raycast hit intervening terrain. Rewrote to vertical-first strategy: mine `feet.up(2)` (always adjacent to head, always passes LoS), then pillar up one step via `ScaffoldService.pillarUpWithPositions`. Falls back to bare mine+jump if no scaffold material. Simple, reliable, no forward LoS issues.

- **Fix: Task display shows "system:" prefix.** `TaskLabelFormatter.humanizeTaskName()` stripped "skill:" prefix but not "system:". Added "system:" prefix stripping + friendly mappings: "surface recovery" → "climbing to the surface", "break free" → "breaking free". HUD now shows "Jake is climbing to the surface" instead of "Jake is working on system:surface recovery".

- **Feature: Fast-travel cost overhaul.** Travel was too cheap (one-time Map+Compass, no resource cost). Added: (1) hunger drain proportional to distance (1 food point per 40 blocks via exhaustion), (2) food safety gate — refuses if bot would arrive below 6 food (3 drumsticks), (3) 3-minute per-bot cooldown between fast-travels, (4) increased base travel time to ~1.5s/chunk (from 1s), (5) underground delay penalty increased from 1.5x to 2.0x. Emergency travel bypasses all gates.

- **Fix: Misnamed "Descent mining attempt" log.** `mineStraightStairBlock()` is used by both ascent and descent but logged "Descent mining attempt". Renamed to "Mining attempt".

## 2026-03-25

- **Fix: Surface detection scans through tree trunks.** `SafePositionService.getWalkableGroundY()` uses MOTION_BLOCKING_NO_LEAVES as a ceiling hint, then scans downward skipping log/leaf blocks to find actual solid ground. Fixes false underground detection under tree canopies, scaffold scanner finding 130 underground stone false positives, and hole recovery overreacting to trunk-biased Y values.

- **Fix: Scaffold detection hardened.** Hard blocks (cobblestone, stone) require at least one exposed face (floating or isolated) — eliminates underground natural stone false positives. Dirt columns only count upward (not into terrain). GRASS_BLOCK removed from detection — was destroying natural terrain (57 blocks shaved in one session).

- **Fix: Shelter breakfree at dawn.** `checkDaylightBreakFree()` was defined but never called. Now wired into the IDLE tick handler. After breaking free, triggers `SkillResumeService.requestAutoResume()` to resume interrupted tasks (e.g., woodcut stopped at sunset). Verified working: woodcut → sunset shelter → dawn breakfree → woodcut auto-resume.

- **Fix: Follow blocked by stale idle sweep.** When follow was toggled ON, a lingering `DropSweepService.isInProgressFor()` from a stuck idle sweep consumed every tick, preventing follow movement. `setFollowMode` now clears idle sweep state and cancels in-progress sweeps.

- **Feature: Scaffold reserve in chest offload.** `ChestStoreService` keeps minimum 32 scaffold blocks across all offload paths. Prevents the bot from dumping all pillar material into chests.

- **Fix: Surface recovery between failed trees.** After PATH_OR_REACH_FAILURE (bot fell into hole), the woodcut skill now calls `recoverSurfacePosition` before selecting the next tree. Prevents cascading failures where 5 consecutive trees fail because the bot is stuck in a depression.

- **Fix: DropSweeper breaks leaf blocks trapping drops.** When nudge-for-pickup fails and the item is inside a leaf block within 2 blocks above the bot, the leaf is broken to free the drop. Drops more than 4 blocks above are skipped entirely.

- **Fix: Pitch reset after skill completion.** SkillManager resets bot pitch to 0 after any skill finishes, preventing leftover look-up angles from pillar operations.

- **Fix: ReturnBaseStuck direct pillar-up.** When scaffold escape can't find an adjacent wall to place against (wide depression), falls back to jump-and-place-underfoot pillar.

- **Feature: Chest offload in woodcut cleanup.** `tryCleanupChestOffload()` runs when inventory is full during the main cleanup loop. Searches 18-block radius for existing chests, deposits logs/planks/saplings/leaf litter/apples via `ChestStoreService.depositMatchingWalkOnly`. Places a new chest if none found. Keeps pillar blocks (scaffold material), tools, and protected items.

- **Fix: Max pillar height increased from 5 to 7.** Most floaters at Y=75 from ground Y=68 need 6-7 steps. Cap of 5 was skipping the majority of overhead targets, leaving cleanup "done" with 0 actionable but visible floaters overhead.

- **Fix: Old scaffold detection expanded for hard blocks.** The `findSuspiciousScaffold` heuristic now accepts cobblestone/deepslate/netherrack pillar patterns even when not horizontally isolated (adjacent to tree trunks or terrain). Natural cobblestone doesn't form vertical pillars, so `pillarLike` alone is sufficient for hard blocks. Dirt-like scaffold still requires the stricter `isolated && pillarLike` check to avoid damaging natural terrain.

## 2026-03-24

- **Fix: WoodcutCleanupSkill scan-per-block bottleneck.** The main cleanup loop called `collectCleanupTargets()` on every iteration — a full region scan (~18K blocks) with BFS flood-fill and tree detection per log. With ~117 logs, each scan took ~21 seconds, so the bot mined 1 block then froze for 21s before the next. Now the scan runs once and the bot works through the cached target list, only rescanning every 4 mined blocks or when the list is exhausted. Non-actionable positions (full trees, grounded logs, human-adjacent) are cached in a rejection set across rescans so the expensive BFS/tree-detection is skipped for unchanged blocks.

- **Fix: Drop sweep interleaved during cleanup.** Previously drops were only swept at the very end of cleanup. Now a lightweight 6-block-radius sweep runs every 3 mined blocks, keeping the area clear as the bot works.

- **Fix: Overhead block reach wastes 17s on futile pathfinding.** `prepareReach` tried to pathfind to the target's Y level (e.g. Y=68 from Y=63) before considering pillaring — each attempt timed out Baritone at 500ms, repeated across 4 retries = 17-20 seconds of staring. Now overhead blocks (3+ above bot) get a fast path: walk to the XZ column below at ground level, clear blocking leaves, pillar up directly. Skips the futile moveUnder/tryReposition cycle entirely.

- **Feature: Cluster mining in cleanup.** After mining a floating log, the bot now flood-fills its 6 neighbours for connected logs and mines them all before returning to the main target list. This eliminates the rescan-per-block overhead for connected floater fragments and matches natural behavior — clear the cluster, sweep drops, move to next cluster.

- **Fix: Scaffold removal skipped after main loop timeout.** The main mining loop often consumed the entire 45-second deadline. Scaffold removal shared this deadline and was silently skipped when time expired, leaving dirt pillars behind (`removedScaffold=0` despite 3 pillar operations). Scaffold removal now gets its own 20-second budget (`scaffoldDeadline = max(deadline, now + 20s)`), ensuring pillar blocks are always cleaned up.

- **Fix: Skip decaying leaves in `clearBlockingLeaves`.** Leaves with `distance=7` and `persistent=false` have lost connection to all logs and will decay on their own via random tick (5-30 seconds). The bot now skips these in LOS-clearing, saving ~300ms per leaf. Player-placed leaves (`persistent=true`) are still cleared since they never decay. Uses `LeavesBlock.DISTANCE` and `LeavesBlock.PERSISTENT` block state properties.

- **Fix: Immediate pillar cleanup after each mine attempt.** Previously `descendAndCleanup` only ran in the `finally` block at the very end of all mining. By then the bot had fallen off scaffolds and couldn't reach upper blocks — leaving dirt pillars as additional mess. Now tracks `placedPillar.size()` before each `mineWithRetries` call and immediately cleans up any freshly-placed pillar blocks via `cleanupFreshPillarBlocks()`. Applied in both the main loop and `chaseAdjacentLogs`. The `finally` block remains as a safety net.

- **Fix: Hole recovery after falls (walk-first strategy).** `tryHoleRecovery()` uses `Heightmap.Type.MOTION_BLOCKING_NO_LEAVES` to detect when the bot has fallen 2+ blocks below surface. Recovery is layered: (1) Try walking out via `MovementService.execute(DIRECT)` in each cardinal direction — Baritone handles 1-block step-ups, so gentle slopes are walkable without consuming dirt. (2) If walking fails, pillar up but KEEP the blocks as an escape route — the scaffold removal phase's heuristic scan will clean them up later. This avoids the pillar-then-collapse-back-in-hole problem.

- **Fix: Pillar-up clears overhead blocks before jumping.** The cleanup skill's `pillarUp` now calls `clearOverheadForPillar()` before each jump step — mines up to 3 solid blocks above the bot's head (dirt, gravel, stone, etc.) that would prevent the jump from succeeding. Previously the bot got stuck when terrain was directly overhead, as shown in the screenshot of the bot trapped in a dirt hole. Mirrors ScaffoldService's proactive overhead mining pattern.

- **Fix: Drop sweep stuck in 4+ minute nudge loop.** `DropSweeper.performSweep` called `MovementService.execute` with `fastReplan=false`, giving walkTo a 20-second budget per drop. When the bot got stuck (unreachable drop at Y=70 surrounded by leaves), the walkSegment's sidestep-unstick loop retried every ~10 seconds for 4+ minutes, freezing all other bot behavior. Changed to `fastReplan=true` (5-second movement budget per drop), so unreachable drops are quickly abandoned.

- **Feature: Async scan — mine while scanning.** The 30+ second initial scan no longer blocks mining. `collectFastTargets()` instantly collects remembered + overhead targets, letting the bot start work immediately. The full region scan runs as a `CompletableFuture.supplyAsync()` in the background. Results are merged into the cached target list when ready. `rejectionCache` changed to `ConcurrentHashMap.newKeySet()` for thread safety. If no fast targets exist, the bot waits up to 5 seconds for the async scan before counting as an empty pass.

## 2026-03-22 (session 2)

- **Fix: Hunt vantage pillar left behind after descent.** `BotFleeService.descendFromSurfacePillar()` preserved one support block during teardown but only cleaned it if the staging move succeeded. If the bot couldn't walk to the staging area, the support block was abandoned permanently. Now the preserved block is always cleaned up, and early-return on abort also does best-effort cleanup.

- **Feature: WoodcutSkill relocates past village protection zones.** When all nearby trees are protected (village boundary), WoodcutSkill now searches an 80-block 360-degree ring for the nearest unprotected tree, walks there via `GoTo.goTo()` A* pathfinding, and resumes woodcutting. If the ring search fails (large village), blind-walks 40 blocks away by sampling 8 compass directions — tries each unprotected direction with pathfinding until one succeeds. Up to 3 relocation attempts per session; failed relocations still consume an attempt but reset the failure counter so the bot can retry from its new position.

- **Refactor: Shared directional tree search in TreeDetector.** Extracted `detectTreeAtCore`/`detectTreeAtForWoodcut` (protection-aware tree detection) and `findNearestUnprotectedTreeInRing` (annular ring search with optional forward-cone restriction) into TreeDetector. BotIdleHobbiesService now delegates to the shared method.

## 2026-03-22

- **Fix: Crafting table / missing materials chat spam throttled.** All 15+ "I need a crafting table" and "Missing materials for X" messages now go through `sendCraftTableNeededOnce()` — a per-bot 30-second cooldown. Previously, the idle hobby chain called `ensurePickaxe` → `ensureSword` → `ensureAxe` → `ensureShovel`, each printing the same message when `ensureCraftingStation()` failed, producing 10-15 identical chat messages in the same second. This also blocked the server thread long enough to cause 7-second lag spikes (pickup failures, teleport delays).

- **Fix: Mutual aid no longer gives away valuable weapons.** `weaponScore()` was missing netherite (scored 5, below wooden at 10) and gold tiers. Added all tiers: netherite=50, diamond=40, iron=30, stone/cobble=20, gold=15, wood=10. Enchanted weapons get +100. Added `MAX_DONATABLE_WEAPON_SCORE = 20` cap in `pickSpareGearSlot()` — bots will only donate wooden or stone (unenchanted) weapons, never iron/gold/diamond/netherite or any enchanted gear.

- **Fix: WoodcutSkill inline dirt collection capped.** When woodcutting triggered `CollectDirtSkill` for scaffold material, it could run unbounded (12+ blocks, minutes of digging). Capped to 8 blocks with a 15-second timeout. If the cap is reached, the woodcut skill proceeds without scaffolding.

- **Fix: Idle hobby loop spin during surface recovery.** When `ensureAtSurface()` failed, `maybeHandleIdleWoodcutForResources()` had no cooldown check, firing hundreds of times per second ("not at surface" log spam). Added `isSurfaceRecoveryActive()` guard before the fallback chain + `NEXT_DECISION_TICK` check at the top of the woodcut resource method.

- **Fix: Terrain depression escape via step-building.** Bot stuck at Y=85 with staging area at Y=88 couldn't climb despite having 2 stacks of dirt. Added `tryStepBuildToStaging()` to `BotFleeService.ensureAtSurface()` recovery ladder — places scaffold blocks as intermediate steps toward a staging area (max 6 blocks, Y gap 1-6). Runs after movement fails and before pillar-up fallback.

- **Feature: Bot-to-bot artifact teleport (summon home).** A bot at base with tier-2 artifact access (Eye of Ender, Wizard's Tome, Enchanting Table nearby, or dual Ender Pearls) can summon another same-owner bot that is >96 blocks away. Integrates into: `/bot home` command flow (in `setReturnToBaseInternal`), sunset auto-return (`beginSunsetSession`). Uses the existing `beginDelayedTravel()` pipeline with 1.0x delay multiplier. Owner receives a purple notification: "ReceiverBot is summoning TravelerBot home via artifact".

- **Fix: Ore announcements now require line-of-sight.** `MiningHazardDetector.collectAdjacentHazards()` was announcing ores (coal, iron, diamonds, etc.) adjacent to the mining path without checking if the bot could actually see them — just whether they had an exposed face anywhere. Added a raycast LoS check (`canBotSeeBlock`) so bots only announce ores they can visually see, matching human player perception. Break targets (blocks the bot is about to mine through) are unaffected.

- **Fix: Idle woodcut search radius mismatch.** `canStartFallbackWoodcut()` checked for trees within 12 blocks, but the WoodcutSkill was launched with `searchRadius=10`. Trees at 11 blocks would pass the trigger check but the skill couldn't find them, causing a futile launch-fail-relaunch loop. Fixed by matching the skill's search radius to 12.

- **Fix: Crafting table search falls through to alternatives.** When `ensureCraftingStation()` found a remembered table but `findStandableOptions(radius=2)` returned empty, it failed immediately without scanning for other accessible tables — then set a 30-second reach cooldown blocking all future attempts. Now clears the stale memory and re-scans for an alternative table with standable access. Also widened standable search radius from 2 to 3 blocks to handle tables placed against walls/corners.

- **Fix: Idle woodcut now skips protected trees + directional expansion.** `canStartFallbackWoodcut()` only checked if the bot's own position was in a protected zone, not the trees. When all 28 nearby trees were inside a village/base protection radius but the bot stood just outside, the trigger returned true and WoodcutSkill launched — only to reject every tree as protected, leaving the bot standing still doing nothing. Now checks `TreeDetector.isProtected()` on each found tree/log and only returns true if an unprotected target exists. As a final fallback, expands search to 24 blocks in a ±60° forward cone from the bot's facing direction — if all nearby trees are protected, the bot looks further ahead for unprotected ones and walks to them.

- **Fix: Server hang on quit — executor shutdown.** 13+ mod executor services (MiningTool, BotIdleHobbiesService, BotEventHandler, modCommandRegistry, BotAutoReturnSunsetService, BotAutoHuntService, MovementService) were never shut down during server stopping. Worker threads kept submitting tasks via `server.execute()` during the shutdown loop, preventing the server from reaching a clean stop state and causing the world to not save. Added `shutdownExecutors()` methods to each class; all are called first thing in the `SERVER_STOPPING` handler, before bot persistence saves.

- **Fix: Tree protection no longer blocks woodcutting near bases.** Two stacking issues made ALL trees within ~24 blocks of a village base protected: (1) `isInsideBaseProtectionRadius` with 24-block default radius — meant to prevent structure destruction, not resource gathering. (2) `isVillageProtected` returned true on just 3 signal blocks (planks, stairs, fences) without requiring any beds/bells/villagers — even scattered village blocks triggered it. Fixed by: adding `isProtectedForWoodcut()` that skips base protection radius (trees are resources, not structures), and changing `signalBlocks >= 3` from an early return to requiring bed/bell confirmation before short-circuiting. WoodcutSkill and idle woodcut fallback now use the woodcut-specific variant.

- **Feature: Pillar-up now mines overhead blocks.** `ScaffoldService.pillarUpWithPositions()` previously gave up when the block 2 above the bot (jump headroom) was solid. Now calls `MiningTool.mineBlock()` to clear it — works bare-handed for soft blocks (dirt, sand, gravel) and with tools for harder blocks. Enables bots trapped underground to pillar + dig upward to the surface without needing pre-cleared shafts.

## 2026-03-21

- **Fix: Crafting table cooldown now allows self-craft fallback.** The reach-failure cooldown was blocking the entire `ensureCraftingStation()` method, including the fast local paths (place from inventory, craft from planks). Now the cooldown only skips movement to distant tables while still allowing the bot to craft and place its own crafting table.

- **Idle woodcutting fallback for missing tools.** When a bot needs a pickaxe or shovel but has no planks/logs (checked in inventory AND nearby chests), the idle hobby chain now triggers woodcutting to gather resources before falling through to low-priority hobbies like grass seeds. New method `maybeHandleIdleWoodcutForResources()` runs after stone tool upgrades in the priority chain, using existing `canStartFallbackWoodcut()` guards (daytime check, nearby tree detection, protected zone check). Added `ToolProvisionService.hasPlanksOrLogsAvailable()` public helper that checks both inventory and nearby chests.

- **Fix: Crafting table retry loop causing server lag.** When a bot couldn't reach a crafting table (e.g., underground with table on surface), each `ensureCraftingStation()` call took ~6 seconds to fail. The idle hobby chain called it 4-7 times in sequence (once per tool provision), blocking the server thread for 35+ seconds and causing "Can't keep up!" lag spikes. Added a 30-second failure cooldown cache: after one failed reach attempt, subsequent calls return `false` immediately. Cache clears on successful reach or after cooldown expires.

- **Fix: Idle hobby `collect_dirt` now requires operational surface.** Added `"collect_dirt"` to `requiresOperationalSurface()` in `BotIdleHobbiesService`. Previously, idle hobbies could trigger `collect_dirt` underground without calling `ensureAtSurface()` first, leaving the bot stuck searching for blocks in a tiny radius inside a cave.

- **Fix: Cave escape pillar-up fallback in ascent mode.** `CollectDirtSkill.executeUpwardStep()` staircase ascent failed in natural caves because mining targets were diagonal (1-8 blocks forward + 1 up), causing LOS raycast failures through irregular cave walls. Added a pillar-up fallback: when 5 staircase jump attempts all fail to gain altitude, mine the 2 blocks directly above the bot's head (always LOS-clear since adjacent) and jump straight up.

- **Fix: Mining direction lost on pause/resume.** When a descent/mining skill paused (danger, hazard, inventory full), `AutoFaceEntity` idle head-swivel rotated the bot to face the player. On resume, `determineStraightStairDirection()` fell back to `player.getHorizontalFacing()` — now pointing at the player, not the original mining direction. Fix: (1) Save resolved direction to `WorkDirectionService` in addition to shared state, so it survives across resume cycles. (2) Read `WorkDirectionService.getDirection()` as a fallback before `getHorizontalFacing()`. (3) Add missing `setPausePosition` + `flagManualResume` at 4 descent-loop abort points (shouldAbortSkill, inventory full, water hazard, lava hazard) that previously didn't save state — matching the existing hazard-detection path pattern.

- **Idle leather armor crafting:** When idle with empty armor slots and leather available (inventory or nearby chests), bots now craft leather armor as a self-sufficiency fallback. Cheapest piece first: boots (4 leather) → helmet (5) → leggings (7) → chestplate (8). `CraftingHelper` gains 4 leather armor recipes; `ToolProvisionService.ensureLeatherArmorForSlot()` follows the existing ensure pattern (inventory → chests → craft); `BotIdleHobbiesService.maybeHandleIdleLeatherArmorFallback()` triggers this in the idle tick loop alongside the existing wooden weapon fallback.

- **Nighttime zombie hunting when hungry + armed:** `BotAutoHuntService` now allows a sheltered, hungry bot to exit shelter at night if it has ≥ 8 hearts, at least one armor piece, and a melee weapon (`isZombieHuntGearReady`). `startAutoHunt` omits `until_sunset` from HuntSkill options when starting at night so the hunt loop isn’t aborted on its first iteration. `HuntSkill` now captures `startedAtDay` before the main loop and gates the sunset-break on it — preventing a nighttime hunt from treating the current time as "sunset already passed." Rotten flesh eating was already handled by the existing `findDesperateFood` fallback (fires at food ≤ 5 when no safe food remains).

- **Depopulation toggle: zombie kills don’t count toward hunt target.** When depopulation is enabled, `kills++` in the hunt loop now skips zombie kills. This lets the bot kill zombies opportunistically during a hunt without consuming the target-count quota meant for food mobs. The no-progress timeout still resets on zombie kills to prevent false timeouts while actively fighting.

- **Hunting menu tooltip improvements.** Depopulation tooltip now explains zombie kills don’t count toward goal. Zone tooltip shows current block radius (dynamic, updates on cycle). Hunt button tooltip clarifies empty-selection behavior. Target button mentions green particle indicators.

- **Idle cobblestone gathering hobby.** New self-sufficiency fallback: `maybeHandleIdleCobblestoneToolsFallback()` crafts wooden pickaxe and shovel when the bot has planks/logs but lacks these tools (uses new `allowWoodenFallback` overloads on `ensurePickaxe`/`ensureShovel` to bypass craft-history gating, matching the existing sword/axe pattern). Once a pickaxe is available and exposed stone is nearby, "mining" enters the idle hobby pool — the bot collects 8-12 cobblestone per session using the existing `MiningSkill`. Mining is surface-gated and low-priority (interruptible for food).

- **Idle stone tool upgrades (tech tree).** `maybeHandleIdleStoneToolUpgrades()` detects wooden-tier tools via translation key matching (`hasOnlyWoodenTool`) and upgrades them to stone when cobblestone is available. Upgrade order: pickaxe → sword → axe → shovel. Stone tools are crafted alongside wooden ones — existing tool-selection logic (`ensureCombatLoadout`, mining tool picker) naturally prefers higher tier. Reuses `NEXT_COBBLESTONE_TOOLS_TICK` cooldown. Public `hasStoneMaterialsAvailable()` wrapper exposes the existing private `hasStoneMaterials()` check.

- **Hunger warnings use overhead text display instead of chat.** `HealingService.checkHungerWarnings()` now uses `CompanionOverheadDialogueService.showOverheadLine()` for "I'm hungry!" / "I'm starving!" / "I'll die if I don't eat!" messages. These appear as floating hologram text above the bot's head instead of in the public chatbox. Command-triggered `healBot()` responses remain in chat since they're direct replies.

## 2026-03-18

- **Fix aimless underground mining + shelter race + skylight in return-base:**
  35. **Shelter race fix:** On-join trap detection (tick+40) now skips if bot already left IDLE mode. Previously, auto-return set FOLLOW immediately, then tick+40 set shelter — creating a flag nothing would clear.
  36. **Shelter validation for all modes:** `validateAndTickShelter()` moved from IDLE-only default case to before the mode switch — runs for FOLLOW, GUARD, etc. Stale shelter flags now clear at dawn regardless of mode.
  37. **ReturnBaseStuck skylight:** Before mining through rock toward a distant base, `tryMineTowardBaseEscape()` now checks `findNearestSkylight(16)`. If daylight is visible within 16 blocks, pathfinds to surface first. Prevents aimless mining through mountains of stone.

- **Wander fixes + shelter torch + auto-return notifications + skip-permission:**
  31. **Wander robustness:** Steps increased from 1-2 to 3-4, step clamp raised to 5, `allowPursuit=true` enabled so bot gets as close as possible instead of hard-failing on first obstacle.
  32. **Dig-down torch placement:** Added 500ms settle delay after cap placement. Torch now placed at fixed `digPos.down(3)` (bunker floor) instead of `bot.getBlockPos()` (race condition). Placement logged at INFO level.
  33. **Auto-return notification queue:** `NavigationHudOverlay` now uses a `ConcurrentLinkedQueue` instead of single static fields. Multiple bot notifications display sequentially — accept/dismiss front of queue, then next appears. Shows "(N more)" when queued. Stale notifications auto-expire after 60 seconds.
  34. **Auto-return skip-permission toggle:** New `BotHomeService.isAutoReturnSkipPermission()` persisted toggle. When ON, bots return home at sunset without HUD notification. Button in bot config UI with tooltip "Bots return home at sunset without asking permission first." Command: `/bot auto_return_skip_permission toggle <target>`.

- **Skylight pathfinding + shelter self-clear for hobbies/hunt:**
  27. **Skylight scanner:** New `findNearestSkylight(world, center, 16)` scans horizontally in a 16-block radius for positions with sky visibility. Underground bots pathfind to natural exits instead of pillaring straight up through terrain.
  28. **Skylight in escapeToSurface():** Before pillar-up, checks for skylight and pathfinds there if found. Falls back to pillar only if no sky within 16 blocks.
  29. **Skylight in stuck detection:** `BotStuckService` bounded-stuck handler now tries skylight pathfinding before mine-escape. Bots in shallow caves/passages navigate to daylight naturally.
  30. **Hobby/hunt shelter self-clear:** `BotIdleHobbiesService` and `BotAutoHuntService` now auto-clear stale shelter state if it's daytime + not thundering, instead of blocking indefinitely. Fixes hobbies being permanently blocked when `validateAndTickShelter` didn't run (low TPS, short session).

- **Fix night break-free loop + remote mining + LoS enforcement:**
  24. **Night guard on break-free:** On-join trap detection now checks time of day. At night, instead of breaking free into danger, sets `SHELTER_ACTIVE` via `setShelterFromJoin()` so the normal dawn break-free path handles escape. Prevents break-free → surface → re-shelter loop.
  25. **MiningTool line-of-sight:** Added raycast check (`world.raycast()`) before mining — if the ray from bot's eye position hits a different block before the target, mining is rejected with "no line of sight." Prevents mining through walls, which was non-vanilla behavior.
  26. **Mine-escape faces block:** `tryMineEscape()` in BotStuckService now calls `LookController.faceBlock()` before mining, ensuring the bot visually turns toward the wall and passes the new LoS check.

- **Fix bots walking into walls instead of mining (bounded-stuck + on-join trap):**
  22. **Bounded-movement stuck detection:** Bots oscillating within a 2-block radius for 60+ ticks (walking into wall and bouncing back) now trigger `tryMineEscape()` if no sky is visible. The old detector only caught truly stationary bots (< 0.01 movement), which never triggered when the bot was actively walking into a wall.
  23. **Position-delta on-join trap detection:** Replaced geometry-based enclosed check (required all 4 walls blocked) with position-delta: 2 seconds after spawn, if bot hasn't moved > 2 blocks from restored position and has no sky visibility, launch `forceBreakFree()`. Catches cliff shelters (tunnels with 1 open direction) that the old 4-wall check missed.

- **Mine-escape for enclosed bots (general stuck detection):**
  20. **Horizontal enclosure detection:** `BotStuckService.analyzeEnvironment()` now tracks `horizontallyEnclosed` — true when all 4 horizontal directions are blocked at feet+head level. Previously only detected `enclosed` at 5+ solid neighbors (requires ceiling too).
  21. **Mine-escape fallback:** When a bot is stationary, horizontally enclosed, and hop/stairs escape fails, `tryMineEscape()` mines the 2 wall blocks in the direction with nearest air (scans up to 3 blocks). 60-tick cooldown prevents rapid re-mining. Works for any enclosed space — shelters, caves, player-built rooms.

- **Fix bots trapped in shelters after restart:**
  19. **Enclosed-on-join detection:** `registerBot()` now checks if the bot has no sky visibility and is enclosed on all 4 horizontal sides (5 ticks after spawn). If detected, launches `forceBreakFree()` — generic escape using the same break-free logic (collect torch, try horizontal exits, pillar to surface). Fixes bots trapped in shelters from a previous session since SHELTER_ACTIVE is in-memory only.

- **Smart shelter breakout + command-triggered escape:**
  14. **ShelterInfo metadata:** `SHELTER_ACTIVE` now stores `ShelterInfo(tick, type, capPos, entryDir)` instead of just a tick timestamp. Both `emergencyCliffDig()` and `emergencyDigDown()` record shelter type, cap block position, and entry direction.
  15. **Type-aware break-free:** `breakFreeFromShelter()` uses stored metadata: cliff shelters mine the 2 seal blocks and pathfind outward; dig-down shelters mine the cap block. Falls back to generic 4-direction probe for unknown types.
  16. **Surface escape:** After breaking the seal, `escapeToSurface()` pillars up (mine above + jump + place below) until sky is visible, max 30 blocks. Handles complex cases like extra blocks placed around cap.
  17. **Command-triggered breakout:** `setMode()` now calls `clearShelterAndBreakFree()` which launches break-free mining on a worker thread. Skill commands (`/bot skill hunt`, fish, etc.) also break free synchronously before executing.
  18. **Torch in dig-down shelters:** `emergencyDigDown()` now places a torch inside if available (previously cliff-only).

- **Fix shelter breakout + guard auto-hunt/hobbies:**
  12. **Break-free mining on shelter clear:** `validateAndTickShelter()` now launches `breakFreeFromShelter()` when clearing the shelter flag at dawn. Previously, break-free mining never fired because `validateAndTickShelter()` cleared `SHELTER_ACTIVE` before `checkDaylightBreakFree()` could read it.
  13. **Shelter guard for auto-hunt/hobbies:** `BotAutoHuntService` and `BotIdleHobbiesService` now skip when `isInShelter()` is true. Prevents starting tasks into a physically trapped bot.

- **Fix phantom behavior loop, shelter spam, "Terminating" chat spam:**
  8. **Shelter thread mutex:** `tryProactiveShelter()` now uses an `AtomicBoolean` per-bot lock to prevent duplicate shelter threads. Previously, 3 threads could launch in the same tick before the cooldown was set.
  9. **No false shelter on failed cap:** `emergencyDigDown()` only sets `SHELTER_ACTIVE` when `capPlaced=true`. Previously, a capless hole still marked the bot as sheltered, trapping it in a loop where it couldn't flee or re-shelter.
  10. **Compromised shelter override:** `tickFlee()` now clears shelter if the bot is actively taking hostile damage, allowing flee to resume instead of being permanently suppressed.
  11. **Suppress "Terminating" for non-diving phantoms:** `AutoFaceEntity.runAutoFaceTick()` skips `broadcastDangerAlert()` and task interruption when the only hostiles are non-diving phantoms at night. Prevents the 4-second chat spam loop.

- **Fix stale shelter threads, immortal bot, phantom flee:**
  1. **Shelter generation counter:** `BotFleeService` tracks a per-bot generation counter incremented on death/respawn. Shelter threads capture gen at start, bail out via `isStaleShelter()` before each mining/movement/sleep step and before setting `SHELTER_ACTIVE`. Prevents stale threads from writing shelter state after bot respawns.
  2. **Reset on death:** `BotFleeService.reset()` now called in AFTER_DEATH handler (previously only on respawn), immediately invalidating shelter threads when the bot dies.
  3. **Vanilla damage timers:** `onBotRespawn()` now zeroes `timeUntilRegen` and `hurtTime` alongside the boolean `setInvulnerable(false)`. Belt-and-suspenders fix for the immortal-bot-after-respawn bug.
  4. **Stale entity reference:** `ensureRespawnHandled()` now resolves the current entity from PlayerManager instead of using the captured (possibly stale) death-event reference.
  5. **Respawn diagnostic logging:** `updateBehavior()` logs invulnerability/damage-immunity state every second for 10 seconds after respawn to help diagnose any remaining immortality issues.
  6. **Phantom flee:** `shouldFlee()` now triggers for phantom-only threats when bot has no ranged weapon and no shield. Unarmed bots flee immediately to seek cover.
  7. **Phantom shelter cooldown bypass:** `tryProactiveShelter()` skips the 30s cooldown when nearby hostiles are all phantoms targeting the bot, enabling immediate shelter construction.

## 2026-03-17

- **Fix flee-to-death + post-respawn unresponsiveness:**
  1. **Flee wall-awareness:** Flee direction now probes 5 candidate directions for traversability (feet+head clearance) before committing. If all directions blocked, bot stands and fights instead of running into a wall.
  2. **Faster stuck detection:** Reduced from 60 ticks/5 blocks to 25 ticks/2 blocks — bot abandons doomed flee 1.75s sooner.
  3. **Combat state reset on death/respawn:** `resetCombatState()` called in both AFTER_DEATH and onBotRespawn, clearing stale LAST_HOSTILE_DAMAGE_TICK, LAST_COMBAT_CENTER, PostCombatSweep state. Prevents post-respawn shelter spam and "walking back to death location" loops.
  4. **Shelter health gate:** `tryProactiveShelter()` now requires HP < 70% — no more sheltering at full health after respawn.
  5. **Shelter teleport/timeout safety:** SHELTER_ACTIVE now stores position; auto-clears if bot moves >8 blocks (teleport) or after 2 minutes. Prevents shelter flag from permanently suppressing combat after /tp.
  6. **Shelter cooldown cleared on reset:** `SHELTER_COOLDOWN` now properly cleared alongside `SHELTER_ACTIVE` and `FLEE_STATES` on death/respawn.
  7. **Shelter doesn't suppress combat:** Hostiles detected while sheltered → normal combat runs. Shelter only suppresses idle behaviors.
  8. **Shelter clears on teleport/damage/commands:** `createFakePlayer.teleportTo()` directly clears shelter. Environmental damage (explosion, drowning, suffocation, lava, fire) clears via ALLOW_DAMAGE. Any mode change (follow, regroup, spell, skill, etc.) clears via `setMode()`.
  9. **Fix immortal bot after death:** Removed `setInvulnerable(true)` + `ServerTask` delayed clear from respawn — the ServerTask wasn't reliably firing, leaving bots permanently invulnerable. Replaced with explicit `setInvulnerable(false)` to clear any stale flag.
  10. **Hardened invulnerability:** Three layers of defense: (a) `onBotJoin` clears invulnerability on session start, (b) `updateBehavior` tick handler force-clears if stuck, (c) `ALLOW_DAMAGE` handler force-clears before processing damage.
  11. **PostCombatSweep log spam:** Downgraded per-tick "starting drop sweep" / "walking to kill site" / "walking back to combat center" from INFO to DEBUG. These fired 30+ times/second.
  12. **Shelter triggers on low HP alone:** Below 50% HP at night, bot shelters regardless of damage source or recency. Above 50%, still requires recent hostile damage (post-combat lull). Previously bot sat at 0.2 HP at night without sheltering because the "recently damaged by hostile" check had expired.
  13. **Eat-then-shelter priority:** New `HealingService.stabilizeEat(bot, maxBites)` — blocking eat loop for worker threads. Pre-shelter: eat max 2 bites to stabilize (only 1 if rotten flesh), then shelter immediately. Inside shelter: eat up to 5 items while safe. Replaces blind 5-second sleep in both dig-down and cliff shelters.

## 2026-03-16

- **Storage screen redesign + fast travel fixes:**
  1. **Bot disappears during fast travel:** Fixed removal order — `removeFromPlayerManager()` now runs BEFORE `kill()`. Previously `kill()` set DISCARDED state which blocked PM removal.
  2. **Contents snapshot refresh:** `refreshAllSnapshots()` reads chest BlockEntities every time Storage screen opens. No longer depends on bot deposit/withdrawal.
  3. **Post-arrival collection:** `PENDING_POST_ARRIVAL_ACTIONS` map stores withdrawal action. `completePostSpawnSetup()` transfers items from chest to bot inventory on arrival.
  4. **3-button layout:** Go / Collect / Dismiss per chest row. Collect opens sub-menu: "Stay at Chest", "Return to Player", "Return to Home".
  5. **Post-collect return trip:** After withdrawing, bot can fast-travel back to player, home base, or stay.
  6. **Artifact tier gating:** Map/Compass = 2x delay, Eye of Ender / Ender Pearls / Wizard's Tome = standard speed.
  7. **Guide updated:** New "Storage" topic and revised "Fast Travel & Navigation Tiers" with full artifact tier system.

## 2026-03-15

- **Storage screen: contents snapshot, tooltips, fast travel collect:**
  1. **Chest contents snapshot:** `ChestRecord` now stores `List<ItemSnapshot>` captured after every deposit/withdrawal. Contents are serialized in network JSON and displayed as an inline summary (up to 3 items) plus full hover tooltip in `BotStorageScreen`.
  2. **Smart collect with fast travel:** Collect button now checks distance. Beyond 100 blocks, bot fast-travels to the chest (disappears, reappears after ~1s/chunk). Under 100 blocks, bot walks using `setReturnToBase` (follow mode with stuck-escape/burrowing logic).
  3. **Bot selector fixed:** Header drag handler was consuming dropdown clicks — now delegates to the widget first.
  4. **Collect command fixed:** Was dispatching `bot skill store withdraw` (wrong path). Now uses `setReturnToBase` for close chests or `beginDelayedTravel` for far ones.
  5. **Button tooltips:** Collect ("Send bot to withdraw items") and Dismiss ("Remove from registry") now have hover tooltips.
  6. **Row height increased:** From 36px to 46px to accommodate the contents summary line.

- **Spells tab fixes & base picker:**
  1. **Spells tab icon:** Replaced "Spells" text label with ✦ icon to prevent overflow. Hover tooltip shows "Spells" after 1700ms delay.
  2. **Status bar shows both player and bot artifacts:** `buildSpellStatusBar()` now scans bot inventory slots (0-40) for Eye of Ender, Pearl, Chorus Fruit instead of relying on nav tier cache. Format: "You: Pearl · Jake: Pearl, Eye of Ender".
  3. **Regroup added to Actions tab:** Added under Orders & Travel section, using existing `COMPANION_COME` action.
  4. **Remote Inventory added to Spells tab:** New `SPELL_REMOTE_INVENTORY` action with tooltip. Requires full artifact access.
  5. **Base picker for Remote Guidance:** Rewrote NavigationConfirmScreen guidance mode as a scrollable destination picker. Options: "Guide to me", "Home (label)" (auto-detected from bot's preferred home), and all saved bases. Reuses existing `RequestBasesPayload`/`BasesListPayload` infrastructure and `BaseManagerScreen.getCachedBases()`. Base list auto-refreshes on tick.

- **Spells tab & fast travel wiring:**
  1. **Spells promoted to 4th top-level tab:** Added `TopicCategory.SPELL` to the inventory screen's tab system. Tab bar now shows Actions | Dialogue | Spells | Admin. Three spell entries: Remote Guidance, Chorus Recall, Soul of Ender — each with hover tooltips (1700ms delay, existing system). Remote Guidance and Chorus Recall open NavigationConfirmScreen; Soul of Ender casts directly.
  2. **Status bar:** Spells tab footer shows per-player/bot artifact status (Eye of Ender, Pearl, Chorus, Enchanting Table, Wizard's Tome, bot nav tier). Falls back to "Spells require artifacts. No artifacts detected." when nothing is held.
  3. **Fast travel wired for Remote Guidance:** Replaced `setReturnToBase()` (bot walks) with `beginDelayedTravel()` (bot disappears, reappears at destination after ~1s/chunk delay). Remote Guidance always uses fast travel — no nav_mode check since ender pearls are consumed. Feedback message shows ETA.
  4. **CompanionSpellsScreen cleaned up:** Removed Remote Guidance, Chorus Recall, and Soul of Ender buttons (now in Spells tab). Remaining buttons: Regroup, Summon, Home, Remote Inventory. Updated status bar text.
  5. **"Spells >" removed from Admin tab:** No longer needed — spells have their own tab.
  6. **Guide entries updated:** Remote Guidance entry now says "always uses fast travel". All "Spells menu" references changed to "Spells tab".
  7. **"Delayed travel" → "fast travel" in comments:** Updated javadoc comments in NavigationArtifactService, BotHomeService, BotWorldStateService.

## 2026-03-14

- **Guide remote inventory gating — full Admin-only mode:**
  1. **`adminPreviewAsNonAdmin` persists across screen re-creations:** Changed from instance field to `static` — toggling it ON and exiting/re-entering the menu no longer resets it to OFF.
  2. **Guide `]` remote open respects artifact access:** `GuideInventoryNetworkManager` checks proximity (≤8 blocks, same dimension) or Remote Inventory spell artifacts (Wizard's Tome / Enchanting Table within 4 blocks) before granting full access. Without these, only the Admin tab is usable. New `GuideInventoryAccessPayload` S2C packet communicates access level + reason to client.
  3. **Hidden inventory in Admin-only mode:** When guide-restricted, `drawBackground()`, `drawForeground()`, and `render()` all early-return — no inventory textures, slots, stats, labels, or entity models render. Only the dark world overlay + Admin overlay panel are drawn, like the hunting menu.
  4. **ESC/X/click-outside close entirely:** In restricted mode, all exit paths close the screen to the world instead of collapsing to the inventory view. No way to fall through to the shared inventory.
  5. **Bot switching blocked:** `<`/`>` arrows hidden, `[`/`]` keys blocked, click handler disabled — can't switch bots in restricted mode.
  6. **Guide button:** A "Guide" button appears in the overlay header (where bot switch controls normally are), navigating back to the guide screen.
  7. **Resize-safe:** `guideStateInitialized` instance guard prevents window resize from resetting guide flags via `init()` re-invocation.
  8. **Cursor position preserved:** BotGuideScreen saves cursor position before the guide→inventory transition; restored in `init()` so the cursor doesn't jump to screen center.
  9. **Remote Inventory banner:** When full access is granted via artifacts (not proximity), a centred banner above the inventory shows the reason (e.g. "Remote Inventory active — Jake holds a Wizard's Tome"). Truncates on small screens, positions relative to inventory.
  10. **`hasSpellbookToken` / `isNearEnchantingTable` made public** in `modCommandRegistry` for reuse.

## 2026-03-13

- **Admin/Actions overlay — 5 bug fixes & UX redesign:**
  1. **"(ON)ON" duplicate fixed:** `displayLabelForEntry()` no longer appends "(ON)" to the "Preview as Non-Admin" label — the toggle status rendering handles ON/OFF display.
  2. **Broken ⚙️ emoji fixed:** Removed VS16 variation selector (U+FE0F) from "⚙️ Behavior" header — Minecraft's font renderer can't display it, causing a dotted box glyph.
  3. **Admin logs filtered from Dialogue tab:** `drawDialogueColumn()` now filters out lines starting with "Admin:" or "You (Admin):" when on the Dialogue tab. Admin messages only appear in the dialogue column when the Admin tab is selected.
  4. **Preview as Non-Admin does NOT block tabs:** Actions and Dialogue tabs remain accessible in preview mode. The inventory screen is only reachable via direct bot interaction, so non-admin users DO have access to these tabs.
  5. **Explicit button controls for action/toggle rows:** Replaced full-row clickability with bordered control boxes. Toggle entries show a bordered ON/OFF button box (full row height, +16px padding). Non-toggle actions show a bordered ▸ button (rendered at 1.5x scale, 20px wide). Label text and icon also act as a clickable target (icon+label region); the gap between label and button is dead space. Added `drawActionControlBox()`, `getActionControlHitInOverlay()`, `hasActionControlBox()`, and `isClickOnActionTarget()`. Admin tab entries use the same UX. Adjustable skills retain existing +/- controls.
  6. **Admin tab accessible from guide via `]` hotkey:** BotGuideScreen now always shows the Admin button, even when opened via `]` with no parent inventory screen. Clicking it sends a new `GuideOpenInventoryPayload` C2S packet to the server, which opens the bot inventory remotely via `BotInventoryAccess.openBotInventoryRemote()` (no distance/dimension restrictions). A `pendingAdminTab` flag tells the new screen to start on the Admin tab. Both admins and non-admins can access — content is filtered by permissions within the Admin tab itself.

- **Soul of Ender spell:** New timed buff spell (75 seconds) that bypasses all teleportation gates — `fixedGoalActive`, `forceWalk`, `allowTeleportPref` — allowing the bot to shadow the player fluidly via wolf-teleport during combat, skill execution, and return-to-base navigation. Activation: bot consumes a Chorus Fruit while holding an Eye of Ender (Eye is kept). New `SoulOfEnderService` tracks buff per bot UUID with tick-based expiry and commander notification on fade. Added `/bot companion soulofender` command, "Soul of Ender" button in CompanionSpellsScreen (with tooltip), and `botHasSoulOfEnderItems()` convenience check in `NavigationArtifactService`. Double-cast prevention returns remaining duration. Server tick handler expires buffs and cleans up on SERVER_STOPPED.

- **Mount-aware travel & "fast travel" rename:** Made all teleportation/travel paths mount-aware. New `TravelMountHandler` service centrally evaluates what to do with a mounted bot before travel: boats/minecarts are collected as inventory items (with container contents transferred), living mounts co-teleport if destination has room (3-block headroom for horses/camels, 2-block for pigs), cross-dimension animal mounts are tethered to fences with coordinates announced in chat, and travel is refused with a notification when inventory is full or destination lacks room. Integrated into `beginDelayedTravel()` (fast travel), `handleChorusRecall()` (Chorus Recall spell), and `BotAutoReturnSunsetService` (sunset return). All mount entities marked persistent via `setPersistent()` at every entry point. Renamed "delayed teleport" to "fast travel" in all player-facing UI: guide screen, command feedback, and added `/bot nav_mode fast_travel` alias.

- **Delayed teleport hardening (6 tasks):** Hardened the fast travel system for edge cases: (1) JSON persistence of `PENDING_TRAVELS` survives server restarts with tick rebasing. (2) Dimension fallback to Overworld spawn with notification if target world is unloaded. (3) Session reset on SERVER_STOPPED prevents stale state on world reload. (4) `isTraveling` guard in `BotPersistenceService.onBotJoin()` skips position restore for mid-travel bots + `saveStateManual` pre-writes destination as safety net. (5) Retry mechanism (up to 3 attempts) for transient respawn failures. (6) Offline notification queuing — messages queued for owners who disconnect during travel, drained on next JOIN.

- **Navigation & Spell Mechanics (full feature):** Implemented the complete navigation artifact and paired spell system across 17 tasks:
  - **Navigation artifacts:** Bot-held Compass/Map (Tier 1: same-dimension base nav) and Eye of Ender (Tier 2: any base, cross-dimension, instant). `NavigationArtifactService` handles tier checks and item validation.
  - **Paired spells:** Remote Guidance (both hold ender pearl → bot navigates to player or base, pearls consumed) and Chorus Recall (both hold pearl + chorus → instant bidirectional teleport, all consumed). Server-authoritative via `SpellGuidancePayload` C2S packets handled in `SpellNavigationNetworkManager`.
  - **Navigation modes:** Per-bot WALK/TELEPORT_DELAY toggle persisted in `BotHomeService`. Walk mode uses `setReturnToBase`. Teleport-delay removes bot from world, tracks in `PendingTravel`, respawns at destination after calculated delay (1s/chunk, min 5s, max 5min, +30s cross-dim). Eye of Ender bypasses delay.
  - **Token rework:** Ender pearl no longer grants passive come/summon/home access. Players use Goat Horn (regroup), Eye of Ender (summon, 60s CD), or paired spells instead.
  - **UI:** CompanionSpellsScreen extended with Remote Guidance + Chorus Recall buttons. NavigationConfirmScreen for destination/direction selection. NavigationHudOverlay for non-obstructive auto-return sunset notifications (Y/N keys).
  - **Network:** 4 new payloads (BotNavTierPayload S2C, NavigationRequestPayload S2C, NavigationResponsePayload C2S, SpellGuidancePayload C2S). Nav tier synced to client on every companion command.
  - **Guide:** 5 new topics (Navigation Artifacts, Remote Guidance, Chorus Recall, Navigation Modes, Spell Ingredients).
  - **Command:** `/bot nav_mode walk|teleport [target]` to toggle navigation mode.
  - **Sounds:** 4 spell sound constants in BotDialogueSounds (amethyst chime, ender eye launch, pearl throw, enderman teleport).
  - **Post-impl hardening needed:** Multi-bot concurrent travel, owner disconnect during travel, server restart persistence of PendingTravel, dimension unload during travel.

- **Auto-return sunset notification (Task 12):** `BotAutoReturnSunsetService` now sends a `NavigationRequestPayload` HUD notification to the bot's owner at sunset instead of immediately triggering `setReturnToBase`. Owner resolved via `CompanionCommunicationPolicy.resolveController()` (checks `BotOwnership` config, falls back to survival recruitment recruiterUuid). If the owner is online and a home target exists, the player sees a non-obstructive overlay with ETA (computed via `NavigationArtifactService.calculateDelayTicks`) and can Accept or Dismiss. Accept triggers `setReturnToBase` via the existing `SpellNavigationNetworkManager` handler. If the owner is offline, unreachable, or has no home set, falls back to the original direct auto-return behavior. Pending-sleep tracking is registered in both paths so sleep-on-arrival works regardless of which path triggered the return.

- **Delayed teleport travel system (Task 8):** `NavigationArtifactService` now implements a full delayed-travel system. `PendingTravel` record tracks in-transit bots (UUID, alias, destination, dimension, departure/arrival ticks, owner UUID). `beginDelayedTravel()` saves bot state via `BotPersistenceService`, sets mode to `TRAVELING`, disconnects the fake player cleanly, and notifies the owner with ETA. `tickPendingTravels()` runs every server tick and respawns arrived bots via `createFakePlayer.createFake()`, registers them with `BotEventHandler.registerBot()` + `AutoFaceEntity.startAutoFace()`, sets mode back to `IDLE`, plays ender pearl sound, and notifies the owner. Hooked into `ServerTickEvents.END_SERVER_TICK` in `Frens.java`. Helper methods `isTraveling()` and `getPendingTravel()` exposed for other systems to query transit state.

- **SpellGuidancePayload + server handlers (Task 7):** New C2S network payload `SpellGuidancePayload` (record with `botAlias`, `spellType`, `destination`) registered in `Frens.java`. `SpellNavigationNetworkManager` now handles two spell types: **Remote Guidance** (consumes ender pearl from both player and bot, navigates bot to player or named base via `setReturnToBase`) and **Chorus Recall** (consumes ender pearl + chorus fruit from both, instant teleport in either direction — bot-to-player or player-to-bot). Sound effects play on cast and arrival. Added `calculateDelayTicks()` stub to `NavigationArtifactService` for Task 8's delayed-travel system.

## 2026-03-12

- **Fix scaffold escape never firing (quick-nudge blocked it):** `ReturnBaseStuckService.tryQuickNudge()` returned `true` and early-exited the method every tick, preventing the scaffold escape check from ever being reached — even after 2000+ stagnant ticks. Once past the scaffold threshold (50 ticks), quick-nudge no longer returns early, allowing the scaffold escape to fire as designed.
- **Skip mining recovery when inventory is full:** `triggerComeRecoverySkill()` now checks for ≥2 empty inventory slots before launching `collect_dirt`/`stripmine`. Previously the bot would loop forever ("Did not climb after 5 attempts, Y remains at 80") because mined blocks had nowhere to go. Now sends "my inventory is full" message and skips mining recovery.
- **Fix `/bot regroup` blocked by LOS check:** `executeCome()` gated on `bot.canSee(commander)` even when `allowRecoverySkills=true` — if the bot couldn't see the player (underground, in a hole) and had no compass/map, the command failed silently with "no navigation tools." The LOS gate now only applies when recovery skills are disabled (idle come), since the entire purpose of regroup + recovery is to handle no-LOS situations.
- **Fix guard/patrol bots staring at mobs without attacking (4 bugs):** (1) Trident classified as ranged weapon but `canFire()` always returned false (TridentItem doesn't extend RangedWeaponItem) — bot wasted ticks on failed ranged attempts every tick. Removed trident from `isRangedWeapon()`; tridents now treated as melee-only (score 40). (2) `attackNearest()` re-filtered with `instanceof HostileEntity`, silently dropping hostile mobs that implement `Monster` interface or have `SpawnGroup.MONSTER` (Slimes, Phantoms, Ghasts, augmented neutral threats). Now uses `EntityUtil.isHostile()` matching the rest of the codebase. (3) Guard/Patrol modes skipped `CombatInventoryManager.ensureCombatLoadout()` — the call in AutoFaceEntity only runs when `updateBehavior()` returns false, which never happens for guard/patrol. Added the call inside `engageHostiles()` so ALL modes get armor/shield/weapon staging. (4) `hasRangedWeapon()` called `selectBestRangedWeapon()` which mutated the hotbar as a side effect of a boolean check — equipping the ranged weapon every tick before the bot decided how to fight. Replaced with a non-mutating inventory scan; actual equip now happens only inside `performRangedAttack()`.
- **Ghast-specific combat: ranged/defilade only, no melee approach:** Ghasts now have a dedicated branch in `engageHostiles()` — bots never attempt to melee-approach a flying ghast. If the bot has a bow/crossbow, it uses ranged attacks with defilade repositioning. Without ranged, the bot raises its shield and holds position. Ghasts are also excluded from `attackNearest()` melee targeting. Fireball deflection continues to be handled independently by `GhastFireballDeflectService`.
- **Ranged combat: fire suppression, defilade, and terrain-aware repositioning:** Bots no longer waste arrows into terrain. `handleChargeWeapon` and `handleCrossbow` now check `isRangedLineBlocked()` before releasing — if all aim points are blocked, the draw is cancelled via `clearActiveItem()` and the bot falls through to approach logic instead. Extended reposition search: when the original 7 close candidates (±1.5 blocks) all lack clear LoS, a second ring of 36 candidates (12 directions × 3 distances at 3/4.5/6 blocks) is evaluated. Defilade scoring: candidate positions are scored with a cover bonus — raycasts from the target's eye to the bot's head/torso/legs at each candidate; more blocked return-fire rays = higher score. Combined with visibility scoring, the bot naturally prefers "peek" positions (clear outgoing LoS + partial incoming cover). Committed multi-tick movement: distant repositions (>2 blocks) persist across ticks with timeout (60 ticks) and early-cancel when LoS clears mid-move. When all reposition options fail, `performRangedAttack` returns false (blocked), falling through to the melee approach path in `engageHostiles` — the bot closes distance around the obstacle instead of standing still.
- **Fix bot pitch stuck at -90/+90 on rejoin (root cause):** `BotControlApplier.java:160` had `Vec2f(yaw, pitch)` but `Vec2f` rotation is `(pitch, yaw)` — yaw and pitch were swapped on every auto-spawn. Yaw values (e.g., 179°) got crammed into the pitch slot, clamped to ±90, then persisted to world state. Fixed the swap. Also added defensive pitch clamping in `BotWorldStateService.BotState.from()` and `BotPersistenceService.saveBotSpawnData()` so out-of-range values can never persist again. Existing corrupted save data manually patched.
- **Fix false drop-off detection on normal slopes:** Changed threshold from `absDeltaY >= 3.0` (triggered on staircases) to `deltaY < -6.0` — requires the player to be 6+ blocks specifically *below* the bot.
- **Gate regroup chat in questing mode:** The "Use /bot regroup" message now only fires in admin mode. In questing mode (`SurvivalRecruitmentService.isEnabled`), the bot silently waits at the opening — players must use in-world items/spells to communicate.
- **Disable `combat_clear_the_nearby_threat` ambient quest:** Added to `BotQuestService.DISABLED_QUEST_IDS`. Ambient quest system will be reworked later.
- **Auto-regroup on lost toggle:** New per-bot toggle in the Behavior section of BotControlScreen. When enabled and the bot has been waiting at a drop-off for ~2 minutes (2400 ticks) without contact, it automatically triggers a regroup — using the same safe-goal pathfinding as `/bot regroup`. Timer tracked via `FollowStateService.FOLLOW_WAIT_ABOVE_START_TICK`, cleared when the drop-off condition ends or the bot regroups.
- **Fix post-recovery mining loop (P0):** After come-mode recovery (collect_dirt ascent), the bot entered a destructive loop where `ReturnBaseStuckService` mine-escape fired every 2.4 seconds in its own stairwell tunnel, mining 100+ stone blocks. Three fixes: (1) **Clear stale stagnant counter** — `ReturnBaseStuckService.clear()` now called on come-mode early exit and when come-recovery skills (pillar-up, ascent, stripmine) complete. Previously the stagnant counter (e.g. 1296 ticks) carried over from the hole, triggering mine-escape immediately at the surface. (2) **Suppress mining when bot can see target** — New `suppressMining` parameter in `ReturnBaseStuckService.tickAndCheckStuck()` disables mine-escape, pillar, and mine-to-surface while still allowing non-destructive escapes (nudge, backup/sidestep, panic flee). Called from follow mode when `canSee && targetDistSq < 100` (within 10 blocks with line-of-sight). (3) **Fix misleading regroup messages** — Drop-off "Use /bot regroup" message now says "I'll find a way down shortly" when auto-regroup is enabled. Stagnant-streak prompt now says "Auto-regrouping shortly..." instead of suggesting manual regroup when the bot is below the player and still has auto-regroup attempts left.
- **Fix come-early-exit not triggering after recovery:** Bot would walk past the player in a stilted way after breaking the surface because the come-early-exit Y tolerance was only 4 blocks — recovery often surfaces 5-8 blocks below the player, so the check never passed. Relaxed `liveDeltaY` threshold from 4.0 to 10.0 (the `canSee` check already ensures the bot and player have line-of-sight). Added near-miss debug logging when the bot is within 12 horizontal blocks but the early-exit conditions aren't met, showing which condition failed.
- **Fix elytra descent retry loop:** The drop guard's elytra fallback had no cooldown — when the bot couldn't launch (e.g. cave opening below, not open air), it retried every 3 seconds indefinitely (6+ attempts observed). Added a 30-second per-bot cooldown in `FollowMovementService.tryDropoffGuard()`. After a failed `tryAutonomousDescent`, the bot falls through to `BotActions.stop()` and the pathfinder can find an alternative route.
- **Scaffold escape before mine-escape:** New non-destructive escape step in `ReturnBaseStuckService` at 50 ticks (before mine-escape at 80, previously 45). When the bot is stuck in a shallow surface depression, it first scans adjacent positions at Y+1 for natural step-ups the bot can jump to. If none found, it places a scaffold block (dirt/cobble) from inventory against a nearby wall to create a step-up. Mine-escape threshold increased from 45 to 80 ticks to give non-destructive methods (nudge, scaffold, backup) time to work before resorting to terrain mining.
- **Come-recovery surface abort:** During come-recovery ascent, the bot now stops as soon as it breaks the surface (sky visible overhead + standing on solid ground) instead of continuing to tunnel the full 12 blocks horizontally. Previously the bot would overshoot the player by 20+ blocks because each staircase step adds horizontal displacement. The ascent loop's existing `isOnSurface()` check is reused via `ascentToSurface=true` in the come-recovery launcher. Once surfaced, come-mode resumes and the early-exit check handles LOS/proximity detection normally.
- **Fix drop guard false-positive on cave staircases:** The drop guard (`tryDropoffGuard` in `FollowMovementService`) probed ahead from the bot's current Y level. On descending cave terrain, the probe saw a deep air column below a perfectly walkable ledge and blocked movement. With no lateral bypass (cave walls), the bot froze — "repeated first waypoint" streak climbing to 8 while stagnant counter hit 121. Root cause: the probe checked the column at the *bot's* Y, not the *target's* Y, so a walkable 1-block step-down over a deep cave triggered false. Fix: after detecting a "dangerous" drop, verify whether the target block itself is a grounded walkable position (`isGroundedTwoHighClearance`) and the step-down is within safe fall distance (≤4.5 blocks). If so, the target is a real floor — skip the guard. Still blocks genuine cliff walk-offs where the target isn't grounded or the fall is lethal.
- **Fix isOnSurface() counting tree canopy as underground:** `CollectDirtSkill.isOnSurface()` used `Heightmap.Type.MOTION_BLOCKING` which includes leaves — bot continued mining through tree canopy instead of stopping. Switched to `MOTION_BLOCKING_NO_LEAVES` (matching `resolveSurfaceTargetY()`), so canopy blocks are treated as surface.
- **Fix bot walking back down its own tunnel after come-recovery:** After successful come-recovery ascent, come-mode resumed with the OLD underground goal. The pathfinder's only known route there was through the stairwell the bot just mined. Now updates `state.followFixedGoal` to the commander's current position after any successful come-recovery skill.
- **Fix post-combat drop sweep not firing in FOLLOW mode:** Three issues prevented drop collection after combat: (1) 2.5s sweep delay gave FOLLOW-mode bot time to walk 10-14 blocks from the kill site — drops fell out of the 16-block search radius. Reduced delay to 1s. (2) No "linger window" — FOLLOW mode kept chasing the player during the delay. Now the bot pauses for 1s after hostiles clear, picking up nearby drops at its feet. (3) No combat center tracking — sweep only searched around the bot's current position. Now records the bot's position when hostiles clear and searches around that position too, walking back if drops exist there.
- **Ranged kill drop recovery:** Bots now record mob death positions via `BotCombatCalloutService.noteKillPosition()` in the `AFTER_DEATH` hook (all bot kills — hostiles and food mobs). During `tickPostCombatSweep`, after searching nearby drops and the combat center, the bot iterates recorded kill positions (nearest-first, max 12 FIFO) and walks to any with item entities within 8 blocks. Solves the bow-kill-at-30-blocks problem where drops spawned at the mob's death site, far from the bot.
- **Reachability probe (`PathFinder.canReach`):** New utility runs `BaritoneStylePathFinder.calculatePath()` with a short timeout (150-200ms) to answer "can I walk there?" without a full raycast. Integrated into: (1) come-early-exit as a fallback when `canSee` fails (within 64 blocks, every 40 ticks); (2) `triggerComeRecoverySkill` pre-check — if a surface path exists, skip mining recovery. Prevents the bot from walking back down its own tunnel after surfacing on a hill where it couldn't see the player through terrain.
- **Simplified regroup (no mining/pillar):** `/bot regroup` now uses pure pathfinding only — no recovery skills, no mining, no pillar-up. Snapshots the player's position at activation. Distance safeguard: if the player moves >128 blocks from the snapshot goal, the bot stops and displays a wait message. Message is contextual: includes "or return home at sunset" when sunset return is enabled. Auto-regroup also uses simplified pathfinding.
- **Companion spell bidirectional tool checks + immersive messages:** `canUseCompanionCome`, `canUseCompanionSummon`, and `canUseCompanionHome` now accept a bot parameter and check both commander AND bot inventories for spell items. Added Eye of Ender and Ender Pearl as valid come/summon/home tools. Immersive contact/summon messages differentiated by tool type: spell-themed (Wizard's Tome/enchanting table), ender-themed (Eye/Pearl), horn-themed (Goat Horn), or bot-initiated (bot holds the focus). Sound effects at the cast location: enchantment table sound for spells, enderman teleport sound for ender items, bell sound for goat horn, amethyst chime for bot-initiated.
- **Questing-mode base navigation rules:** Non-HOME bases beyond 256 blocks now require a compass or map in the bot's inventory (questing mode only). HOME base is always navigable regardless of distance. Recovery skills enabled during all base navigation so bots can mine out of caves/crevices en route. `BotHomeService.resolvePreferredHomeBase()` made public for distance-gate HOME exemption check.

## 2026-03-11

- **Post-combat drop sweep for all modes:** Bots now reliably sweep mob drops and recover arrows after combat ends, regardless of mode (FOLLOW, GUARD, PATROL, STAY, IDLE). Previously STAY and IDLE had no drop sweep, and FOLLOW gated it on commander distance. Added per-bot post-combat sweep state in `BotCombatCalloutService` (fires 2.5 s after hostiles clear, auto-expires at 30 s). New `tickPostCombatSweep()` in `BotEventHandler` runs before mode handlers: recovers arrows first, then sweeps drops within 16 blocks. STAY mode returns to its post after sweeping. Sweep cancels immediately if new hostiles appear.
- **Fix regroup/come stuck in holes — dynamic Y-window, pillar-up recovery, auto-regroup:** FollowPathService's Y-window was clamped to ±3 blocks (`MAX_Y_SPAN=6`), causing "no path found" when the goal was >3 blocks above/below the bot. Now uses a dynamic span: `max(6, min(20, deltaY+4))`, keeping the window tight for flat terrain and widening automatically for vertical gaps. `buildVerticalFallbackGoals()` was generating fallback goals only at the bot's Y level — now generates at bot Y, midpoint Y, and goal Y, enabling stepping-stone paths through holes. Regroup now allows recovery skills (was `comeAllowRecoverySkills=false`, now `true`), making it functionally equivalent to `/bot come`. Added pillar-up as priority-0 recovery strategy: when bot is ≥3 blocks below goal with ≤8 horiz distance, open sky above, and not in a protected zone, uses `ScaffoldService.pillarUp()` to escape. Auto-regroup triggers in follow mode after 5 consecutive no-path results with ≥6 vertical gap (cooldown 600 ticks, max 3 attempts). Added Regroup button to BotControlScreen footer.

## 2026-03-08

- **Hunt playtesting bug fixes (6 issues):** (1) Chest crafting permanently blocked on fresh bots — `canCraftChest()` required crafting history which is empty for new bots; now always returns true since chest is a universally known recipe. (2) Defense-in-depth: `runWoodcutPrerequisite()` now skips entirely if bot already has planks/logs in inventory; `hasPlanksOrLogsInInventory()` added as public helper on `ToolProvisionService`. (3) Drop sweep: increased radius from 6→12 blocks, vertical from 4→6, max targets 8→16, duration 8s→12s; added walk-toward-kill-position step before sweeping when mob died >5 blocks away. (4) Furnace race condition: after `BotActions.placeBlockAt()`, SmeltingService now sleeps 150ms and re-scans with `findNearestFurnace(radius=4)` to confirm block entity is created before building `StationTarget`; applies to both inventory-place and craft-then-place branches. (5) Boats as intentional feature: all boat/raft variants added to `CraftingHelper.CHEAP_BLOCKS` for chest offload; `jettisonDisposables()` drops boats and crafting tables when inventory is full and no chest is available. (6) Partial tree floater cleanup: `fellTree()` now accumulates abandoned log positions into `pendingFloaters`; after the main tree loop, a second-pass cleanup walks to each floater from below and retries mining.
- **Fix HuntablesScreen overlap + tooltips + movement/woodcut bugs:** Fixed "Huntable Mobs" label overlapping with the Hunt/Target/All Edible/Close button row by increasing the gap between controls and the mob list. Added player-friendly tooltips to all hunting menu controls (Count, Refresh, Depop toggle, Zone cycling, Hunt, Target, All Edible, Close). Fixed woodcut prerequisite paradox where hunt→woodcut→deposit failed because the bot had 2 empty slots (threshold triggered deposit) but no chests existed yet — hunt prerequisite runs now use `emptySlots <= 0` threshold instead of `<= 2`. Added natural terrain filter to the movement obstruction miner: dirt, grass, gravel, sand, clay are now skipped (prevents bots from tunneling through hillsides to reach targets). Added explicit furnace/blast furnace/smoker protection to the obstruction miner as a safety net against race conditions with the block entity check.
- **Hunt session persistence + loot summary fix + UI feedback + entity validation:** HuntSessionService now persists to `config/frens/hunt_sessions.json` so multi-day hunts survive server restarts. Sessions auto-expire after 24h and are cleared on bot death (hooked into `Frens.java` ALLOW_DAMAGE death handler). Inventory snapshot for loot summary moved after the woodcut prerequisite chain so chopped logs no longer appear as hunt loot. BotStorageScreen Collect/Dismiss buttons now show brief status feedback ("Sent to collect..." / "Dismissed") in the bottom bar for 3 seconds. Server-side target picker validates entity type against `HuntCatalog.isFoodMob()` via registry lookup before accepting — rejects non-huntable targets with a log warning.
- **Harden hunting system + bot-switching storage menu:** Thread safety fixes across all hunting phases: `TARGET_PICKERS` iteration now uses `removeIf` (was ConcurrentModificationException risk), `PENDING_TARGET_ENTITY` entries now expire after 30s (was unbounded leak), target-picker particles throttled to every 10 ticks (was every tick per mob per player). `ChestRegistryNetworkManager` payload validation now checks `instanceof Number` before casting coordinates (was ClassCastException on malformed JSON). `BotStorageScreen`: replaced `SimpleDateFormat` with thread-safe `DateTimeFormatter`, `LAST_CHESTS` now volatile with `List.copyOf` for immutable snapshots, silent exception catches now log warnings. `HuntablesScreen`: all static config fields now volatile. Added bot-switching dropdown to the Storage screen header — players can toggle between spawned bots to view each bot's chest registry using the existing `DropdownMenuWidget`, populated from online bot aliases.
- **Storage system — chest registry + storage menu (Phase 5):** New `BotChestRegistryService` persistently tracks all bot-placed chests per bot per world (JSON at `config/frens/bot_chest_registry.json`). Records store coordinates, placement context (hunt/woodcut/supply), timestamp, and destroyed status. `ChestStoreService.placeChestNearBot()` now auto-registers placed chests. New `BotStorageScreen` — a resizable, draggable panel accessible from the inventory screen's Utilities topic menu ("Storage >"). Shows all tracked chests grouped by context with coordinates, status (Active/Destroyed), and age. Per-row Collect and Dismiss buttons dispatch the bot to withdraw items or remove the record. Bottom bar shows active/total counts with Refresh and Close buttons. Window size/position persists via `UiPrefs` JSON. Network payloads: `RequestChestRegistryPayload` (C2S), `ChestRegistryPayload` (S2C), `ChestCollectPayload` (C2S), `ChestDismissPayload` (C2S). Server handlers in `ChestRegistryNetworkManager` verify chest existence, serialize records, and dispatch collect commands.
- **Target indicator + entity picking (Phase 4):** New TARGET button on the HuntablesScreen closes the menu and activates a crosshair-based entity picker overlay (`HuntTargetPickerOverlay`). The HUD shows instructions and displays the name of any huntable mob under the crosshair via ray-box intersection. Left-click sends the entity UUID to the server, which dispatches a single-target hunt bypassing depopulation checks. Right-click cancels. While target-picking mode is active, the server spawns green dust particles above all food mobs within 32 blocks of the player. New network payloads: `HuntTargetPayload` (C2S, entity UUID + bot name) and `HuntTargetModePayload` (C2S, toggle particle spawning). `HuntSkill.findTargetedEntity()` consumes the pending UUID and resolves the specific entity in the world.
- **Multi-day hunts + sunset integration (Phase 3):** Hunts now persist across day/night cycles. When sunset triggers and the bot has auto-return enabled, `HuntSkill` saves the session (kill count, origin, targets, zone, depopulation setting) via new `HuntSessionService`, records the execution with `SkillResumeService`, and requests auto-resume. At sunrise (`timeOfDay < 1000`), `BotAutoReturnSunsetService` detects the paused hunt session and triggers `SkillResumeService.tryAutoResume()`. On resume, the bot travels back to the hunting grounds and continues from the saved kill count. Also added post-kill depopulation recheck — if population drops below threshold mid-hunt, the bot ends early with a message.
- **Fix bots not defending without shield (engageHostiles deadlock):** When `shouldBlock` was true (low health, multiple threats, projectile threat) but the bot had no shield, `raiseShield()` returned false but the code always returned true — the bot looped forever trying to raise a non-existent shield and never reached the attack code. Now falls through to melee attack if shield raise fails.
- **Enderman eye-contact avoidance during movement:** `EndermanSafetyService` was wired into `FaceClosestEntity` and `LookController`, but `FollowMovementService.moveToward()` set yaw purely from movement direction with no enderman check. If the bot chased a zombie toward a passive enderman, the yaw triggered Minecraft's stare-detection. Added `deflectFromPassiveEndermen()` to deflect yaw 30 degrees when it would stare at a passive enderman (using Minecraft's own dot-product threshold).
- **Fix bot respawning at death location:** BotSpawn config was auto-saved every 30s with the bot's current position via `recordSpawnData()`. On death, the 6-tier respawn chain resolved BotSpawn (tier 3) to a position near where the bot just died, before the failsafe world_spawn (tier 4) could be reached. Now `onBotDeath()` calls `clearBotSpawn()` so the chain falls through to world spawn (or bed if set).
- **Fix premature combat exit while still under fire:** After killing a zombie, the bot declared combat over and entered idle behavior while a skeleton was still shooting from ~12 blocks (beyond the 10-block detection range). Added hostile damage tick tracking (`noteHostileDamage` in ALLOW_DAMAGE event) and a "recently damaged" guard in `updateBehavior()` that does a wider 16-block scan if the bot took hostile damage within the last 2 seconds.
- **IDLE flee behavior when outnumbered:** New `BotFleeService` — idle-mode bots now flee when critically wounded (health <= 30%), heavily outnumbered (3+ hostiles), or outnumbered and unarmed (2+ hostiles, no weapon). Flee direction is computed away from the centroid of hostile positions. Bots sprint away for up to 10 seconds or until 20+ blocks from all threats, with a 5-second cooldown before re-evaluating. FOLLOW/GUARD/PATROL/STAY modes never flee.
- **Expanded huntable mob catalog + first-kill discovery toast:** Added Fox, Turtle, Goat, Squid, and Glow Squid to `HuntCatalog` (food mob tracking and hunting menu). `HuntHistoryService.recordHunt()` now returns `boolean` indicating first-time discovery. On first kill of any huntable mob type, the server sends a `HuntDiscoveryPayload` to the player and the client shows an achievement-style `SystemToast` ("New Hunting Target! — [Mob] added to the hunting menu"). Bots never receive the notification since they have no real client.
- **Hunt skill: fix depopulation blocking explicit commands:** Depopulation check (`MIN_PEACEFUL_COUNT = 3`) was MORE restrictive for explicit commands (`hunt sheep`) than for hobby hunts — returning immediate failure if fewer than 3 targets existed. Now only hobby/auto-hunts enforce depopulation; explicit commands hunt as long as at least 1 target exists.
- **Hunt skill: re-select weapon before every attack swing:** `attackTarget()` now calls `selectBestMeleeWeapon()` before each `bot.attack()` to ensure the sword stays equipped even if something changes the hotbar mid-fight.
- **Hunt skill: try offloading before skipping drops:** `runDropSweep()` and `runFinalDropSweep()` previously bailed immediately if inventory was full. Now they call `offloadInventory()` first to try making room (offload cheap items to nearby chests), only skipping if still full after the attempt.

## 2026-03-07

- **Vanilla-compliant scaffold teardown (fix remote block destruction):** `ScaffoldService.teardownScaffolds()` was using `world.breakBlock(pos, true)` — a remote server-side block deletion that bypasses the bot entirely. This violated the vanilla-only construction rule (no remote placement or breaking). Replaced with `bot.interactionManager.tryBreakBlock(pos)` + arm swing animation, the same physical break path used by `BotActions.breakBlock()`. Affects all scaffold cleanup including `RoofAccessService.cleanupAllRoofPillars()` and `descendFromRoof()`.
- **Nearest-station pre-build positioning (fix forced corner walk):** `BuildSchematicSkill` always walked the bot to a fixed corner (`origin.add(sizeX-1, 0, 0)`) before building, regardless of where the bot was standing. When the bot was already near the build site but far from that specific corner, this created a long forced walk that triggered cascading movement/protection conflicts. Now computes all perimeter build stations from the schematic footprint and picks the one closest to the bot's current position. Falls back to the old fixed corner only if station computation returns empty.
- **Scaffold-gap roof patch pass (fill hole left by center pillar):** After `completeFromCenterPillar()` tears down the scaffold column, the scaffold blocks that overlapped planned roof positions left air gaps in the roof. Added a dedicated patch pass: after scaffold cleanup, the bot re-pillars to the roof and fills any planned positions that are now air in the scaffold column area. This closes the visible hole in the roof seen in playtests.
- **Relaxed door placement gate:** `shouldProcessDoors` previously required `repairRemaining == 0`, meaning any remaining structure damage (even a single unreachable scaffold-gap block) would skip door placement entirely. Relaxed to allow doors when `repairRemaining <= 2`, since the structure is functionally complete at that point.
- **Navigate bot outside before clearing protection (fix wall-breaking on exit):** After building completes (including door placement), the bot now walks outside through the detected doorway before construction protection is cleared. Previously, protection was cleared immediately in `finally`, and the bot's follow/movement behavior would then mine through shelter walls as "obstructions" because it didn't route through the door opening. Now the bot exits cleanly while walls are still protected.

- **Small shelter phased build flow (corners → centroid fill → center pillar):** Reworked the `small_shelter` path in `BuildSchematicSkill` so it no longer behaves like a generic pass loop with a roof-only fallback. The build now orders all corner targets first, then runs the non-roof body on a centroid-biased order, explicitly re-centers after the first pass, and if structural leftovers remain it switches to a single center-pillar completion phase that climbs to one block above the roof and places remaining reachable blocks from the middle. That late phase uses `RoofAccessService` so the center pillar is also torn down afterward, and successful late placements are fed back into `ActiveBuildRepairSession` so repair tracking still covers blocks placed during the pillar phase.
- **Faster scaffold escalation for corner/roof work:** Extended `ConstructionRecoveryService.ensureReachWithScaffold(...)` with an aggressive one-up scaffold preference used by `BuildSchematicSkill` for `CORNER` and `ROOF` targets. These targets now attempt scaffolding sooner when they are one block above the bot instead of always burning time on same-level movement-first recovery. This is aimed directly at the diagonal-corner/top-ring cases where the bot is “close enough” geometrically but still needs elevation to get a clean placement angle.
- **Diagonal corner LOS fix (multi-support placement for corner/top-ring blocks):** Fixed the long-running construction failure where the bot would reach a block geometrically but still fail to place it from a diagonally opposite corner because `BotActions.tryPlaceBlockAt(...)` committed too early to a single support face—usually the block below—and returned `no-line-of-sight-to-support` if that one face was occluded by the shell. Placement now gathers multiple valid support candidates for the target, prioritizes the support face nearest/most relevant to the bot, and tries each candidate with raycasts before giving up. Also expanded per-face click sampling from just center + corners to include edge-midpoints, which makes narrow corner/edge shots much less brittle. This is a shared low-level fix, so generic schematic builds, scaffold placement, and fortify-style repair paths all benefit without adding more task-specific recovery complexity. Verified with `./gradlew build -x test` (success; only the pre-existing `CraftingResultSlotMixin` warning remains).
- **Small shelter roof simplification pass (defer ugly scaffold thrash):** Simplified `BuildSchematicSkill` for the built-in `small_shelter` path so the normal construction pass now handles non-roof blocks first and defers flat-roof work to a small, explicit roof phase instead of letting generic per-target scaffold recovery thrash around the shell. The new roof phase uses `RoofAccessService` with two clean exterior pillar positions (door side + opposite side), places only directly reachable roof blocks from each perch, and keeps deferred roof blocks in final completion accounting if the roof step is skipped or stalls. This intentionally avoids a larger generalized roof-state machine while reducing the noisy target-by-target scaffolding seen in live `small_shelter` runs. Verified with `./gradlew build -x test` (success; only the pre-existing `CraftingResultSlotMixin` warning remains).
- **Construction parity slice 1 (shared tuning + generic scaffold hardening):** Started the active construction-parity implementation pass by re-anchoring `RALPH_TASK.md` around construction reliability/runtime parity and moving the first shared rules into code. Added `ConstructionPlacementRules` as a shared tuning source for generic construction reach/scaffold thresholds, switched generic schematic building and shared scaffold service to that contract, and strengthened `ConstructionRecoveryService` scaffold stance selection with explicit pillar headroom validation so bad stances are rejected before the bot commits to a dead-end climb. Also fixed the generic schematic tall-structure scaffold gate in `BuildSchematicSkill`: it no longer disables scaffolding simply because a target sits high above the schematic origin, and now bases the hard out-of-reach check on the bot’s current vertical delta instead of absolute structure height. `BuildSchematicSkill` reach checks/logging were also aligned with the shared feet-based reach semantics. Verified with `./gradlew build -x test` (success; only the pre-existing `CraftingResultSlotMixin` warning remains) and deployed `frens-1.1.0-release+1.21.11.jar` to the Prism `1.21.10` instance mods folder with matching SHA-256.
- **Construction scaffold fix: pillar-first for elevated targets (porting FortifyVillageSkill pattern):** Three consecutive playtests showed zero scaffold log entries despite prior threshold changes — the bot endlessly wall-humped protected blocks at Y=-56 from Y=-59 with `no-line-of-sight-to-support` failures. Root cause analysis: (1) `isWithinReach && verticalDiff <= 1` early return fired when bot briefly reached Y=-57 (verticalDiff=1) but couldn't place because its body blocked the support face from below — the recovery said "you're close enough" then placement failed, creating infinite retry loops; (2) `isProtectedStance` checks in `chooseScaffoldStance` rejected all valid scaffold stances because they overlapped with planned schematic blocks, causing null return and silent scaffold failure; (3) `moveTo` (pathfinder) failed for scaffold stances when paths routed through protected blocks, with no fallback. Fixes (ported from FortifyVillageSkill's `ensureCanReachBlockWithEffort` pattern): **`ensureReachWithScaffold` rewritten** — early return threshold tightened from `verticalDiff <= 1` to `verticalDiff <= 0` (only skip when at/above target level); for `verticalDiff >= 2`, ALWAYS scaffold first regardless of isWithinReach; movement only tried for same-level/below targets; `shouldPreferProactiveScaffold` removed (no longer needed with deterministic scaffold-first flow). **`chooseScaffoldStance` protection checks removed** — scaffold stances are temporary positions, not build targets; protection should not prevent the bot from standing near the target to pillar up. **`ensureReachByScaffolding` movement fallback** — when pathfinder fails (path through protected blocks), falls back to `nudgeToward` direct movement (matching FortifyVillageSkill's `walkTowardBlock`); accepts stance if within 3 blocks XZ rather than requiring exact position. **Diagnostic logging added** — logs entry into scaffold path with verticalDiff/pass info, stance failures, unreachable stances, and climb-too-high rejections. Build verified, deployed to Prism (hash `49b11ea4...`).
- **GUI blur reentry hotfix (restore screen crash only):** Fixed the client crash triggered by `BotRestoreScreen` opening on 1.21.11 (`IllegalStateException: Can only blur once per frame`) by replacing the default blur-backed `renderBackground(...)` path with the repo’s flat manual-dim background rendering. A brief experimental dimming change to `BotPlayerInventoryScreen` was fully reverted after it made the handled shared-inventory view render too dark; that screen is back on its original handled-screen render flow. Verified with `./gradlew build -x test` (success; only the pre-existing `CraftingResultSlotMixin` warning remains) and copied the rebuilt jar into the Prism `1.21.10` instance mods folder with matching SHA-256 hashes.
- **Construction reach strategy overhaul (adjacent scaffold + reach-check alignment):** Playtest logs showed the bot stuck in infinite `movement obstruction disabled` / `pursuit failed [direct]` loops for all elevated targets (Y=-56 to Y=-57) after scaffold triggered only once with a bad stance 4 blocks away. Root causes: (1) `isWithinReach` used eye-based distance but `BotActions.tryPlaceBlockAt` gates on feet-based distance — blocks "in reach" to recovery were "out of reach" to placement; (2) `chooseScaffoldStance` picked positions at radius 2-5 from the target XZ, too far horizontally after pillaring; (3) the early `isWithinReach` return in `ensureReachWithScaffold` fired for elevated targets that were technically in-distance but impossible to place from below due to no-LOS to the support face; (4) movement recovery looped endlessly through protected blocks the bot couldn't mine. Fixes: **isWithinReach** now uses `Entity.squaredDistanceTo` (feet-based) matching BotActions; **chooseScaffoldStance** rewritten to prefer cardinal-adjacent (offset 1) → diagonal → same-column → radius 2, scored by horizontal XZ distance (since vertical is eliminated by pillaring); **ensureReachWithScaffold** only early-returns for verticalDiff ≤ 1, forcing scaffold evaluation for blocks 2+ above; **shouldPreferProactiveScaffold** unconditional threshold lowered from 4 to 3; **movement skipped** entirely for protected targets 2+ blocks above (those paths always route through protected blocks). Build verified, deployed to Prism with matching hashes.
- **Small schematic efficiency/scaffold follow-up (proactive exterior pillaring):** Fresh runtime logs finally showed the new protection system working (`Activated schematic protection...`, repeated `movement obstruction disabled ... reason=active-construction-protection`), but they also exposed the next bottleneck: the bot was overthinking upper-wall/roof recovery, repeatedly pathing toward impossible elevated protected targets during pass 1 without ever switching to scaffold/pillar mode. Tightened `ConstructionRecoveryService.ensureReachWithScaffold(...)` so elevated protected targets can trigger scaffolding proactively instead of only as a late pass-2 fallback, moved scaffold target height from `targetY - 2` to `targetY - 1` so pillaring reaches a actually useful working height, added exterior scaffold-stance selection instead of trying to walk into the live shell, and added explicit `task-recovery: scaffold ...` info logs for stance selection, pillar attempts, and outcomes. This should both reduce the repeated direct-path thrash seen in the latest `small_shelter` log and finally make scaffold/pillaring behavior visible in playtests. Verified with `./gradlew build -x test` (success; only the pre-existing `CraftingResultSlotMixin` warning remains) and redeployed the rebuilt jar to Prism with matching hashes.
- **Reusable active-build repair loop (implementation slice 2 + Prism deploy fix):** The next playtest log finally explained why runtime behavior still looked old: the Prism `mods/frens-1.1.0-release+1.21.11.jar` was byte-different from the freshly built workspace jar, so the instance was running stale code. Verified the mismatch with SHA-256, replaced the Prism jar, and re-verified the hashes match after deployment. On the code side, extended the repair refactor so low-level movement damage can feed the active-build repair system immediately instead of waiting for a later pass sweep: added `ConstructionRepairService` as a per-bot registry for active `ActiveBuildRepairSession`s, exposed `recordObservedDamage(...)` on the session, registered/cleared the session from `BuildSchematicSkill`, and taught `MovementService.tryMineObstructionToward(...)` to notify the active repair session after a successful obstruction mine so the system can attempt an immediate replacement/queue cycle (`construction immediate repair hook ...`). Also cleaned up a null-guard in `MovementService` while validating the slice. Verified with `./gradlew build -x test` (success; only the pre-existing `CraftingResultSlotMixin` warning remains) and redeployed the rebuilt jar to Prism with matching hashes.
- **Reusable active-build repair loop (implementation slice 1):** Started the shared repair-first refactor the shelter wall-mining investigation was pointing toward. Added `BlockReplacementService` under `services/construction/` to centralize fortify-style replacement candidate selection, fallback material matching, and mandatory force-replace fallback for active construction repairs. Added `ConstructionRepairSafetyService` to share the fortify-derived “would this seal the bot in?” exit check for repair decisions. Added `ActiveBuildRepairSession`, a task-scoped ledger/queue that only activates planned cells once they have actually existed in a valid structure state, detects when those committed cells are later damaged during the same build, and runs throttled repair sweeps with diagnostics (`construction repair detect`, `construction repair sweep`, etc.). `BuildSchematicSkill` now creates that repair session for schematic builds, marks successful placements as committed structure, runs a repair sweep after every execution pass and once more after scaffold teardown, suppresses doorway post-processing when active-build repairs remain unresolved, and now shares the same replacement candidate logic as the new service for normal placement fallbacks. Verified with `./gradlew build -x test` (success; only the pre-existing `CraftingResultSlotMixin` warning remains).
- **Small shelter wall-mining follow-up 2 (hard kill-switch for generic mining during protected builds):** A fresh `latest.log` still showed `movement obstruction mine` tearing through newly placed `small_shelter` wall columns (`904/905,-59/-58,32+`) and, more tellingly, it did **not** show any of the newly added protection activation/skip diagnostics. To make the runtime behavior safe even before the remaining visibility mystery is fully settled, `MovementService.tryMineObstructionToward(...)` now disables generic obstruction mining outright whenever `ConstructionProtectionService` reports an active protected construction for that bot, logging `movement obstruction disabled ... reason=active-construction-protection` instead of breaking blocks. `BuildSchematicSkill` now also logs protection activation/clear directly through the skill logger (`Activated schematic protection...`, `Clearing schematic protection...`) so the next playtest can confirm without ambiguity that the live Prism jar is exercising the intended code path. Verified with `./gradlew build -x test` (success; only the pre-existing `CraftingResultSlotMixin` warning remains).
- **Small shelter wall-mining follow-up (active footprint hardening + diagnostics):** After a fresh `latest.log` repro still showed `movement obstruction mine` chewing through newly placed `small_shelter` cobblestone wall columns, hardened the active construction guard instead of relying only on exact planned-block membership. `ConstructionProtectionService` now logs activation/clear events, tracks the schematic footprint bounds for the active task, and exposes a protection reason so movement logs can explain *why* a block was skipped. `BuildSchematicSkill` now passes the real perimeter build stations into the protection scope, and `ConstructionRecoveryService` now refuses to path directly into protected target cells during reach recovery (so it stops trying to stand inside the live shell and provoking obstruction mining in the first place). `MovementService` obstruction mining now logs `movement obstruction skip ... protection=...` when a candidate belongs to the active build. Verified with `./gradlew build -x test` (success; only the pre-existing `CraftingResultSlotMixin` warning remains).
- **Incomplete schematic doorway spam fix (`small_shelter` follow-up):** Diagnosed the bizarre multi-door `small_shelter` result from runtime logs and confirmed the built-in blueprint in `SimpleSchematicBuilder` was sane. The malformed result came from an incomplete shell (`71/96` placed, with remaining `NO_SUPPORT` / `NO_LOS` / `OUT_OF_REACH` failures) followed by doorway post-processing that treated many unfinished 2-block wall gaps as valid entrances. Hardened `DoorwayAccessService` so doorway detection now requires a real supported opening with solid side jambs, and updated `BuildSchematicSkill` to skip doorway clearing/door placement entirely when a schematic finishes too incomplete to trust the perimeter. Final verification: `./gradlew build -x test` succeeds; only the pre-existing `CraftingResultSlotMixin` warning remains.
- **Small schematic anti-self-sabotage hardening:** Added `ConstructionProtectionService` so active schematic footprints are registered per bot and generic movement obstruction mining will no longer chew through planned structure/support blocks during an in-progress build. `ScaffoldService` now publishes tracked scaffold positions into shared per-bot memory for the duration of a build and clears them correctly on teardown, letting movement/recovery code distinguish temporary supports from disposable terrain. `BuildSchematicSkill` now activates/clears that protection scope automatically around execution and also nudges generic fallback material order to prefer cobblestone before dirt for structure substitutions. On top of the mining guard, `ConstructionRecoveryService` now looks for stable nearby reach stances outside protected build cells before it tries to walk straight at an upper target, and schematic no-progress recovery in `BuildSchematicSkill` now rotates through precomputed perimeter build stations instead of sometimes picking the footprint centroid as a vantage point. This makes small shelter / small schematic roof work behave more like hovel/fortify perimeter building instead of pathing into its own active shell. Verified with repeated `./gradlew build -x test` runs (success; only existing mixin warning remains).
## 2026-03-06
- **Generic schematic anchor resolution pass:** Tightened `BuildSchematicSkill` so preview-targeted builds no longer treat raw `targetY` as a literal schematic origin. The skill now detects the terrain floor under the requested build center, treats schematic layer `Y=0` as the default grade plane when present (falling back to the lowest occupied layer otherwise), preserves intentional upward offsets from locked previews, and resolves the final origin from that anchor before moving to the build corner. This gives `small_shelter` and other schematic builds a reusable site-prep baseline instead of repeatedly trying to place their first support layer into/under the ground. Follow-up cleanup in the same file removed a dead constant/import and replaced the `Map.merge(..., Integer::sum)` calls with null-safe lambdas so the file stays clean under current diagnostics. Build verified with `./gradlew build -x test`.
- **Locked construction preview controls (first pass):** Construction previews can now be locked in place from the client so players can walk around them in 3D before committing. While a preview is active, `;` toggles lock/unlock. When locked, arrow keys nudge the preview by exactly 1 block in player-relative directions; `Shift+Up/Down` moves it vertically; and `Shift+Left/Right` rotates schematic-based builds in 90° steps. The preview HUD now shows lock state plus the new control legend, and locked previews no longer disappear just because the player turns the camera to inspect them.
- **Preview lock keybind migration:** Added a one-time client migration for the preview lock hotkey so old saved `L` bindings are rewritten to `;` on startup. This fixes existing worlds/instances where Minecraft had already persisted the old binding in `options.txt`, which otherwise kept opening vanilla Advancements and made the HUD keep advertising the wrong key.
- **Preview confirm parity hardening:** Pending construction confirmation in `FrensClient` no longer relies on the legacy `/bot shelter_look` and `/bot build_look` raycast helpers once a preview exists. Confirm now sends direct skill invocations with explicit `targetX/targetY/targetZ` from the client preview transform, so a locked preview still builds at the place the player positioned even after they walk away or turn somewhere else before pressing confirm.
- **Rotated schematic build support:** `BuildSchematicSkill` now honors an optional `rotation` parameter by applying `SchematicData.rotated(...)` before origin calculation and execution. This keeps rotated schematic previews and the actual built result aligned for asymmetric structures while leaving procedural shelter translation support intact.
- **Construction preview UX + looked-at bot wording polish:** Cleaned up the looked-at companion task text so raw build nouns now render as natural phrases (for example `building shelter` instead of awkward `is shelter` wording) via the shared task-name humanizer in `BotTaskPeekNetworkManager`. The client now also shows a persistent top-of-screen preview hint while a construction placement preview is active, including the selected structure label plus confirm/cancel guidance, instead of relying on short-lived actionbar flashes.
- **Preview entry cleanup:** Removed the repeated delayed actionbar resend workaround from both construction preview entry points (`ConstructionScreen` and `BotPlayerInventoryScreen`) now that preview instructions are state-driven and persist until the player confirms or cancels placement.
- **Hovel efficiency pass 1 (rollback-safe):** Started the first narrow optimization pass in `HovelPerimeterBuilder` without deleting the old path. Added explicit feature toggles for the new behavior, cached repeated floor-height scans at the same center point, reordered leveling stations by proximity to reduce unnecessary walking, and narrowed foundation-beam retries so repair passes focus only on corners that are still actually missing beam blocks while reusing known-good scaffold bases where possible. Follow-up compile cleanup removed a dead-code null check and replaced a few fallback corner positions with explicit `BlockPos` construction so the optimized pass builds cleanly under current diagnostics. Build verified with `./gradlew build -x test`, and the refreshed `frens-1.1.0-release+1.21.11.jar` was copied into the Prism instance mods folder for playtesting.
## 2026-03-05
- **Construction menu readability + compact panel pass:** Refactored `ConstructionScreen` away from its floating full-screen text/buttons into a compact Base-style panel with a fixed header, darker in-panel background treatment, clipped scrollable content region, visible draggable scrollbar, and a reserved footer/help lane so section headings stay readable in bright or dark environments and hovered help text no longer collides with scrolled content or the Back button. Added an always-visible **Bases** quick link in the Construction footer and a reciprocal **Builds** quick link in `BaseManagerScreen` so players can hop between the two related menus without digging back through the inventory overlay.
- **Construction preview/recovery UX pass (hovel anchor sync + no-bots restore flow):** Tightened three related construction/recovery pain points. `ConstructionScreen` actions now close fully back to gameplay instead of dropping players back into the inventory overlay, so selecting a construction job immediately returns control for world placement/inspection. Hovel placement now logs its full coordinate chain (look hit, preview center, safe goal, requested anchor, detected floor, resolved build center, and wall/roof blueprint bounds) and `shelter_look hovel` now passes the exact looked-at preview center into `ShelterSkill`, using the same forward-offset anchor math as the client preview plus a 9x9 hovel radius so the actual build footprint lines up with the visible preview instead of drifting to wherever the bot happened to stop. The client-side hovel preview now also snaps to the same floor scan the builder uses. Added a temporary missing-bots recovery flow in `FrensClient`: when known bot aliases exist but none are present in the current world, a short top-of-screen hint appears and `[-]` temporarily opens a lightweight restore menu that respawns a remembered bot in **Admin** mode without making the player remember/type the alias manually.
- **Collect Dirt icon/render fix (final screenshot follow-up):** Reworked `Collect Dirt` in the Actions list so it no longer relies on Minecraft font glyph luck. The row now renders as a first-class full-width skill entry instead of being paired into the two-column simple-row layout, and its icon is drawn manually as a tiny dirt tile in `BotPlayerInventoryScreen` rather than as a text character. This removes the lingering broken/offset icon behavior visible in screenshots and makes `Collect Dirt` read like its own proper action again.
- **Actions alignment + grouping polish (screenshot follow-up):** Refined the experimental two-column `BotPlayerInventoryScreen` Actions layout to read more cleanly at a glance. Skill/action icons now render inside a fixed-width icon slot so labels line up consistently instead of drifting based on icon width, which also fixes the lingering `Collect Dirt` offset problem. Replaced the still-problematic dirt icon with a guaranteed-safe `#` glyph so it renders cleanly in the Minecraft font. Adjustable/full-width rows (`Follow`, `Woodcut`, `Fishing`, `Wool`, `Stripmine`, `Ascent`, `Descent`) now draw their value and `- / +` controls inside a compact darker value cluster instead of leaving a floating parameter island across a large empty gap, making it much clearer which parameters belong to which action label. Section headers also gained a little more breathing room by insetting and lowering the separator line so groups feel less cramped.
- **Actions row spacing + icon cleanup (screenshot follow-up):** Tightened the expanded Actions row layout in `BotPlayerInventoryScreen` so wide adjustable rows like `Woodcut`, `Wool`, `Stripmine`, `Ascent`, and `Descent` no longer throw their amount controls all the way against the far-right edge, leaving an awkward empty gulf in the middle of the menu. The value/control cluster now sits closer to the action label while keeping the two-column experiment intact. Also replaced the unsupported `Collect Dirt` dirt-square emoji icon with a Minecraft-safe glyph so the row renders cleanly instead of showing tofu placeholder boxes.
- **Actions tooltip accuracy + bot-switch menu persistence:** Tightened the new `BotPlayerInventoryScreen` Actions hover help so it reflects the real behavior of the connected skills and toggles instead of falling back to vague or missing copy. Added concise tooltips for `Stop`, `Woodcut`, `Wool`, `Fishing`, `Stripmine`, `Ascent`, and `Descent`; corrected `Woodcut Cleanup` to explicitly mention leftover drops and floating leftover scaffold/log cleanup; and broadened `Collect Dirt` so it accurately describes gathering common soft terrain blocks like dirt, gravel, and sand instead of only literal dirt. Also preserved the open inventory overlay state across in-screen bot switching: using `<`, `>`, `[` or `]` now carries the expanded Actions/Admin/Dialogue menu state, active tab, search text, scroll position, and cursor through the `/bot open <alias>` screen refresh so switching bots no longer kicks players out of the open menu.
- **Actions menu follow-up (selective tooltips + Collect Dirt promotion + two-column trial):** Refined `BotPlayerInventoryScreen` after the first Actions cleanup pass by restoring **selective** player-helpful tooltips for non-obvious Actions rows (for example tether/leash behaviors, sunset return, cleanup, construction/base helpers, and `Collect Dirt`) without bringing back noisy duplicate hover text on obvious rows like Stop/Guard/Sleep. Promoted **Collect Dirt** to a first-class skill row instead of leaving it visually nested under Farming, and gave it its own distinct dirt icon. Also added a section-aware **two-column trial layout** for the full-width Actions tab: section headers and parameter-heavy rows stay full width, while simpler rows render in two columns to use the empty middle space more effectively. Scroll, hit-testing, and hover behavior were updated to match the new visual row model, and the build/deploy flow was re-verified after the experiment.
- **Actions/tooltip UX cleanup (inventory + settings follow-up):** Reworked `BotPlayerInventoryScreen` so the **Actions** tab now uses the full overlay width while the Conversation pane stays visible only for **Dialogue** and **Admin**. Removed redundant hover tooltips from the quick grid and plain action rows, added grouped section headers plus clearer row icons in the expanded Actions list, wrapped long tooltip text to stay on-screen, and corrected the footer switch hint to the real `[` / `]` controls. Bot switching in the open overlay now uses the active header layout instead of a hard-coded `Conversation` width, which fixes the cramped hit area that could behave like a close click. Follow-up tooltip polish also raised hover delay slightly to 1.7s, resets tooltip timers when users click/scroll/toggle through controls, removed the ambiguous mini cycle glyph from `BotControlScreen` value chips, and brought the same delay/reset behavior to `AdminPlayerSettingsScreen`.
- **Tooltip timing + guide/docs clarity pass (settings/admin/roadmap/regroup):** Reduced delayed hover tooltips from 2.5s to 1.5s across `BotControlScreen`, `AdminPlayerSettingsScreen`, and `BotPlayerInventoryScreen` so help text appears faster and more consistently. Rewrote vague settings/admin tooltip copy (notably Force-Place, recruitment, LLM/voice toggles, and learning-mode permissions) in plainer player-facing language. Added richer learning-mode help text in `BotPlayerInventoryScreen`, including operator-only explanation and roadmap pointer, and added a dedicated **Learning Mode (Admin)** topic to `BotGuideScreen`. Expanded the in-game roadmap topic with verified current/active/future buckets, refreshed shortcut docs (including proper single `\` display), and normalized visible spell/guide/hotkey wording to prefer **Regroup** while keeping legacy `/bot come` behavior intact. Synced external docs in `README.md` and `COMPANION_QUESTING_GUIDE.md` to reflect current optional AI/LLM infrastructure, Learning Mode v1, Goat Horn regroup access, and the high-level roadmap. Build/deploy re-verified after this pass.
## 2026-03-04
- **Global dialogue mode controls + roadmap disclosure:** Added new world-level toggles in `BotControlScreen` and persisted config (`ManualConfig`) for **Global Text Dialogue** and **Global Voiced Dialogue** so users can disable text, voice, or both from general settings. Runtime gating now applies centrally: `ChatUtils` suppresses bot dialogue text when global text is off, overhead hologram text is suppressed through `CompanionOverheadDialogueService`, and voiced subtitle holograms are suppressed in `BotDialoguePlayer` when text is disabled. Voice playback now also honors a new global voiced toggle in addition to per-bot voiced settings. Implemented voice-only fallback behavior for missing mapped clips: when text is off but voice is on and a line has no mapped voice clip, chat text is still sent as fallback. Added the requested disclosure that current voice lines use a male voice set in the voiced-dialogue tooltip, and added a new high-level **Roadmap (High-Level)** guide topic in `BotGuideScreen` covering dialogue controls and alternate-voice prioritization on request.
- **Microcopy consistency pass (Admin/Guide/Controls):** Polished wording across `BotPlayerInventoryScreen`, `AdminPlayerSettingsScreen`, `BotControlScreen`, and `BotGuideScreen` for clearer, consistent terminology. Key updates include renaming **Player Settings** to **Player Permissions** where applicable, standardizing recruitment/learning/admin action labels, tightening tooltip phrasing (including ascent `☀` defaults), and clarifying guidance copy in Bot Controls and Guide entries.
- **Admin/actions UI pass (clarity + permissions + Bot Controls guide coverage):** Completed the new interaction pass across companion screens. `BaseManagerScreen` header now clamps/truncates safely to prevent overlap, reset control was upgraded to a clearer `↺ Reset` button, and panel position is viewport-clamped to avoid off-screen snaps after drag/restore. `BotPlayerInventoryScreen` actions now show explicit default amounts (instead of ambiguous “Default”), ascent mode uses the clearer `☀` surface toggle with 5-block default ascent, and row/control tooltips use a 2.5s hover delay. Admin tab was reorganized with emoji category headers and now supports **Preview as Non-Admin** plus a new **Player Settings >** entry. Added server-synced admin-permission snapshot handling in the inventory UI (global defaults + per-user overrides), and added `AdminPlayerSettingsScreen` for granular global/per-player permission toggles with live sync actions (`permissions_snapshot`, `perm_global:*`, `perm_user:*:*`). `BotControlScreen` spawn mode UI is now canonicalized to Training/Questing/Admin (legacy aliases mapped for compatibility). `BotGuideScreen` now includes a dedicated **Bot Controls Panel** topic with backlinks from mode topics, and ascent docs were updated to reference the `☀` surface toggle. Build verified: `./gradlew build -x test` (success).
- **Base Manager UX overhaul (offscreen bleed fix + resizable window + draggable scrollbar):** Refactored `BaseManagerScreen` from a fixed full-screen control stack into a bounded floating panel that keeps all content reachable on smaller displays. Added a right-side draggable scrollbar for overflow content (click-track jump + thumb drag + wheel support), and moved the full base/wall action set into a clipped scrollable content region so rows no longer bleed off the bottom of the screen. Added manual window resizing by dragging panel edges/corners, optional header drag-to-move behavior, and persisted layout preferences (`config/frens/base_manager_ui.json`) so position/size are remembered between sessions. Added a top-left reset control (`↺`) that restores default centered dimensions and persists the reset state.
- **Persistence failsafe hardening (inventory restore + world key scoping + respawn spawn-source guard):** Implemented durability protections to reduce inventory/state corruption across jar swaps and mixed world/server contexts. `BotInventoryStorageService` now resolves the best available snapshot with fallback matching (exact alias+uuid, then alias/uuid candidates by recency), supports join-time stale snapshot gating (`shouldRestoreOnJoin`) so older custom snapshots cannot overwrite newer vanilla player `.dat` state after manager restore, and broadens delete cleanup to remove stale alias/uuid variants. `BotPersistenceService.onBotJoin()` now uses that stale/missing gate and logs skipped restore reasons for diagnosis. `BotWorldStateService` world keys now include a server-root fingerprint (levelName + hashed save-root path) to avoid cross-server/cross-world collisions in global `bot_world_state.json`, with automatic legacy key migration on read. `BotEventHandler.onBotRespawn()` now ignores `BotSpawn` entries whose stored `levelName` doesn’t match the current server level, preventing cross-world spawn bleed-through before falling through to safer checkpoints.
- **Skin chooser UX cleanup (policy notice crowding fix):** Refined `BotSkinChooserScreen` layout after policy/custom-url additions made the top region crowded. Increased panel room (wider/taller), introduced a dedicated notice banner under the title, and moved content start below that reserved area so status text no longer overlaps the title/list region. Policy messages were shortened for readability and wrapped to two lines max; when no restriction applies, the banner now shows a compact usage tip instead of leaving cramped/ambiguous text.
- **Skin policy controls + custom URL enforcement/fallback**: Implemented server-authoritative bot skin governance with two admin toggles synced through recruitment state: **Allow Everyone Skin Change** and **Allow Custom Skins**. Expanded skin selection payloads from preset-only to typed source/value (`preset` or `custom_url`) and added custom URL validation in server handling. Added policy-aware auth (`admin || allowEveryoneSkinChange`) and deterministic fallback for blocked non-admin custom requests to safe preset `steve` with user-facing feedback. Added reconciliation when custom skins are disabled to downgrade persisted non-admin custom selections, plus spawn-time normalization so stale persisted selections cannot bypass policy. Updated admin UI with both toggles and updated skin chooser UI with custom URL input, policy gating, and in-menu notice messaging.
- **Bot skin live-refresh reliability + chooser state reflection**: Fixed skin-change flow where server logs showed successful profile swaps but bots did not visually update. `BotSkinService.retrackEntity()` now resolves `ServerChunkLoadingManager` methods deterministically across dev/prod names (`unloadEntity`/`method_18716`, `loadEntity`/`method_18701`) and always invokes unload → load in correct order, with diagnostics logging selected methods during skin refresh. This replaces brittle fallback behavior that could bind methods in reverse order and prevent proper client-side re-spawn visibility updates. Also improved `BotSkinChooserScreen` UX: on open, it now infers the bot's active skin by decoding the live `GameProfile` textures property and pre-selects the matching preset so the menu reflects current saved/live state.
- **Unsigned bot texture rendering fallback (client mixin)**: Added `AbstractClientPlayerEntitySkinMixin` to provide a narrow fallback for profiles that carry an unsigned `textures` property. For those profiles only, client rendering now resolves skin textures via `MinecraftClient.getSkinProvider().supplySkinTextures(profile, false)` and caches suppliers by UUID+texture value hash. This bypasses secure-signature rejection for companion bot presets while keeping vanilla secure path for signed player properties.

## 2026-03-03
- **Fix bot unresponsive after spawn (startAutoFace never called)**: When LLM is enabled by default but runtime classes are absent (non-LLM build), the `NoClassDefFoundError` catch in `spawnBot()` fired but left `llmActive = true`, causing the `if (!llmActive) { startAutoFace(bot); }` fallback to be skipped. Bot spawned but had no behavior loop — couldn't follow, fight, or respond to any command. Fix: set `llmActive = false` in the catch block so the fallback starts the behavior loop. Affects admin/questing/play spawns and auto-spawn on server start. Training mode and respawns were unaffected.
- **Security hardening (ownership enforcement + respawn safety)**: Audit-driven security fixes across 6 files:
  - **CompanionCommunicationPolicy.java**: Added `isAllowedToControl(actor, botAlias)` centralized ownership gate — ops always pass, un-owned bots accessible to all, owned bots restricted to recorded owner UUID. Overload accepts bot entity directly.
  - **InventoryAccessPolicy.java**: Replaced placeholder TODO with real ownership check — calls `CompanionCommunicationPolicy.isAllowedToControl()` before the proximity check. Non-owners can no longer open bot inventories.
  - **BaseNetworkManager.java**: Network payload handlers (BaseSetHome, BaseGoTo) now enforce ownership — `resolveControlledBot()` accepts requesting player and rejects non-owners. Import added for `CompanionCommunicationPolicy`. Read-only list path passes null (no ownership gate for viewing).
  - **BotEventHandler.java**: Replaced 3 silent `catch (Throwable ignored) {}` blocks (tier 2 recruitment anchor, tier 4a owner_bed, tier 4b saved_base) with `LOGGER.warn()` calls that include the throwable. Fixed saved_base failsafe world lookup: now always looks up bases in the Overworld (where the UI restricts them) instead of `bot.getEntityWorld()` (which may be the death dimension).
  - **ManualConfig.java**: Added `synchronized(SAVE_LOCK)` around `save()` method to prevent concurrent config writes from corrupting the JSON file.
- **Respawn hardening (bed validation + failsafe chain + safe-surface guarantees)**: Hardened the bot respawn system to mirror vanilla Minecraft's bed destruction/obstruction handling. 6 files changed:
  - **SafePositionService.java**: Added `validateBedSpawn(world, bedPos)` — checks bed block still exists (`instanceof BedBlock`) and has safe standing room within 2 blocks. Added `findSafeSurface(world, base, fallbackRadius, heightmapRadius)` — combines spiral search with heightmap scan when underground/void.
  - **ManualConfig.java**: Added `failsafeSpawnMode` field to `BotControlSettings` with 3 values: `owner_bed`, `world_spawn` (default), `saved_base`. Getter normalizes invalid values.
  - **BotControlScreen.java**: Added "Failsafe Spawn" 3-way toggle (Owner Bed / World Spawn / Saved Base) in the Spawning group. New `makeString` 3-option overload. SettingsSnapshot now has 9 fields.
  - **BotEventHandler.java**: Complete rewrite of `onBotRespawn()` fallback chain with 6 tiers: (1) validated bed spawn — rejects if bed destroyed/obstructed with log message, (2) recruitment anchor with safe-surface wrap, (3) BotSpawn config with safe-surface wrap, (4) failsafe per admin toggle — owner's bed (validated, with offline fallthrough), saved base (bot's preferred home base), or world spawn, (5) world spawn with heightmap fallback for void columns, (6) bedrock-level emergency for completely empty worlds. Every tier logs the resolution path. `resolveSpawnPoint()` replaced reflection cascade with direct `WorldProperties.SpawnPoint.getPos()` call.
  - **BotQuestService.java**: Same `resolveSpawnPoint()` reflection→direct replacement.
  - **BotIdleHobbiesService.java**: Same `resolveSpawnPoint()` reflection→direct replacement.
  - Unused `java.lang.reflect.Field` imports removed from BotEventHandler and BotQuestService.
  - Build verified, deployed to Prism.

## 2026-03-02- **Auto-spawn rework (death toggle + implicit server-start + checkpoint respawn)**: Major rework of the auto-spawn system across 9 files. The per-bot "Auto Spawn" toggle has been replaced with "Auto Respawn" — controlling whether bots automatically respawn on death (skipping resurrection ritual) rather than whether they appear on server start. Server-start auto-spawn is now implicit for all bots with saved `BotSpawn` data and matching `levelName`. Key changes:
  - **ManualConfig.java**: Added `autoRespawnOnDeath` (nullable Boolean) to `BotControlSettings` with mode-based defaults (admin/training=ON, play/questing=OFF). Old `autoSpawn` field retained `@Deprecated` for JSON backward compat.
  - **BotControlApplier.java**: `scheduleAutoSpawns()` no longer checks per-bot `isAutoSpawn()` — spawns all bots with matching `BotSpawn` data + `levelName`. Recruitment gate still active.
  - **BotControlScreen.java**: Toggle renamed from "Auto Spawn" to "Auto Respawn" with description "Respawn on death (skip resurrection ritual)."
  - **SurvivalCompanionQuestService.java**: `enableAutoSpawnForPermanentCompanion()` no longer sets `autoSpawn`; only sets `spawnMode("play")`.
  - **BotPersistenceService.java**: `onBotDeath()` now checks `autoRespawnOnDeath` — if ON, skips `noteCompanionDeath()` so bot respawns normally.
  - **BotEventHandler.java**: `onBotRespawn()` rewritten with checkpoint-based fallback chain: bed spawn → recruitment anchor → BotSpawn config → world spawn → absolute fallback. Uses 1.21.11 `getRespawn()` / `WorldProperties.SpawnPoint` API.
  - **SleepService.java**: Now sets vanilla spawn point via `bot.setSpawnPoint(new Respawn(...))` after bed sleep so bots respawn at their last bed.
  - **modCommandRegistry.java**: Admin/training spawn paths set `autoRespawnOnDeath(true)`. Alias collision now returns rejection message instead of repositioning existing bot.
  - **WorldModeSelectionScreen.java**: Admin onboarding text updated to explain unique bot naming.
  - Build verified, deployed to Prism.- **Fix cross-world bot auto-spawn**: Bots saved in one world no longer auto-spawn into new/different worlds. Added `levelName` field to `BotSpawn` in `ManualConfig`, saved via `server.getSaveProperties().getLevelName()` in `BotPersistenceService.recordSpawnData()`, and guarded `BotControlApplier.scheduleAutoSpawns()` to skip spawns whose `levelName` doesn't match the current world. Legacy configs (null/blank levelName) pass through safely. Build verified.
- **Expanded multi-bot switch UX (inventory screen):** Added in-screen companion switching to `BotPlayerInventoryScreen` so players can cycle inventory focus across known bot aliases without closing to chat. New `<` / `>` switch controls now appear in the bot stats row (collapsed view) and in the expanded overlay header, and clicking the alias chip also advances to the next bot. Added keyboard shortcuts `[` and `]` while the inventory UI is open to switch to previous/next bot quickly. Switching issues `/bot open <alias>` under the hood, preserving server-side authorization/targeting behavior and reusing existing command flow. Alias candidates now merge current bot, recruitment alias, configured bot aliases, and visible player-entities (deduped case-insensitively, with `default` excluded). Build verified: `./gradlew build -x test` (success).
- **Multi-bot switch hardening follow-up:** Audited the new inventory switch controls and tightened behavior to reduce false-target friction: switch alias discovery now limits to current/recruitment/configured aliases (no broad client-world player scan), bracket hotkeys only consume input when a switch command is actually dispatched, and switching no longer force-closes the current inventory screen before `/bot open <alias>` resolves. Updated overlay footer hint text to explicitly advertise `[ / ]` switching. Build re-verified: `./gradlew build -x test` (success).
- **Multi-bot switch UX polish + docs sync:** Added explicit in-UI feedback when players try to switch with no alternate companion target available (action-bar hint, cooldown-throttled to avoid spam). Updated in-game guide content (`BotGuideScreen`) with a dedicated “Switch Active Bot (Inventory)” topic and refreshed shortcuts reference, then synchronized external docs in `README.md` and `COMPANION_QUESTING_GUIDE.md` to document `/bot open [alias]` and `[ / ]` inventory switching controls. Build re-verified: `./gradlew build -x test` (success).
- **Admin/guest topic visibility policy (client UX hardening):** Updated `BotPlayerInventoryScreen` to fail closed on client-side admin detection and apply audience filtering in the Admin tab: non-operators now see only the spells entry, while operator-only recruitment/learning/world admin actions are hidden. Updated `BotGuideScreen` to hide admin-only mode-management topics (`Delegate Mode Setup`, `Direct Mode`) for non-operators. This aligns topic visibility with server-side operator enforcement and reduces confusing "button visible but denied" flows.
- **Hybrid world-mode permissions model (operator + delegated guests):** Added per-world delegated chooser persistence in `ManualConfig.SurvivalRecruitmentState` (`modeSelectionDelegatesByUuid`) and server-authoritative permission checks in `SurvivalRecruitmentService.canChooseWorldMode(...)`. World-mode selection is now allowed for operators or explicitly delegated players; delegated status is reflected in `RecruitmentStatePayload`/UI gating. Added new admin commands: `/bot recruit mode_access status`, `/bot recruit mode_access allow <player>`, `/bot recruit mode_access revoke <player>`, and `/bot recruit mode_access clear`, including full client state resync after changes. Updated admin-network status output to include delegates and refreshed the mode-selection lock message text to mention delegated players. Added an in-game guide topic for delegation commands. Build verified: `./gradlew build -x test` (success).
- **Onboarding recoverability + mode naming migration + admin-network mode-switch parity:** Implemented first-pass mod initialization UX improvements: temporary setup reopen via `-` while selection is pending, top-of-screen world-mode reminder banner, dismissible world-mode chooser, permanent in-game guide hotkey (`]`), quick-action guide entry with delayed tooltip/hotkey hint, and crosshair bot-menu hint when targeting a companion. Updated spawn mode semantics to canonical `admin|questing|training` with legacy aliases (`play|quest|train`) and compatibility messaging. Added world-mode switch warning/confirm guard to both command-path and admin UI network-path recruitment toggles (second action required within 12s when switching between `admin` and `questing`), plus refreshed admin tooltip copy. Also updated stale admin setup hint text to use `/bot spawn <name> admin`. Build verified: `./gradlew build -x test` (success).
- **Ownership permissions v2 (explicit allowlist grants/revokes + policy-aware auth):** Extended territorial governance from owner-only lockouts to explicit cross-owner permissions. `FortificationPersistenceService.SavedFortification` now persists `allowedOwnerUuids` and normalizes ownership policy (`owner_only|allowlist|public`), with new `grantOwnerAccess`/`revokeOwnerAccess` APIs. `ProtectedZoneService` now includes matching policy + allowlist fields in persisted zone data and uses policy-aware checks in `isProtected(...)` (plus grant/revoke/mode setters for future command/UI expansion). `BotTerritoryAuthorizationService` now honors these policies for both protected zones and claimed fort interiors, preserving deny-by-default while allowing explicit exceptions. Added new Base Manager networking/actions for wall permission delegation (`base_grant_wall_access`, `base_revoke_wall_access`), owner-subject resolution by UUID/player name/bot-alias owner, and client UI buttons (**Permit**, **Revoke**). Also patched legacy mutation guards to pass bot-owner context instead of `null` in `ReturnBaseStuckService` and `MountedLeafClearingService` so owner bots are no longer over-blocked inside their own protected areas. Build verified: `./gradlew build -x test` (success).
- **Zone governance command surface (permit/revoke/mode + richer list):** Added protected-zone administration commands to complete the policy workflow in-game: `/bot zone permit <label> <owner>`, `/bot zone revoke <label> <owner>`, and `/bot zone mode <label> <owner_only|allowlist|public>`. Added mode suggestions in command parsing, owner/admin authorization checks, owner-subject resolution (UUID/player name/bot-owner alias), and normalized mode validation. Enhanced `/bot zone list` to display per-zone access mode and explicit permit count. Build verified: `./gradlew build -x test` (success).
- **Ownership governance pass (foreign-claim lockout + wall claim UI)**: Added centralized mutation authorization via new `BotTerritoryAuthorizationService` and wired it into core block mutation chokepoints (`BotActions` placement/break/force-replace/support placement + `MiningTool` mining loop). Bots now refuse to modify blocks inside protected zones owned by a different player and inside claimed fort interiors (convex-hull check against saved fort schemas). Extended `FortificationPersistenceService.SavedFortification` with ownership metadata (`ownerUuid`, `ownerName`, `ownershipPolicy`) plus `setOwner/clearOwner` mutators; new/bootstrapped/expanded schemas now auto-inherit owner from the acting bot when available. Added Base Manager ownership controls with lightweight networking (`Claim Wall` / `Unclaim`) and owner display in wall rows; registered new payloads (`base_claim_wall`, `base_unclaim_wall`). Build verified: `./gradlew build -x test` (success).
- **Schema-first fortify foundation (moat bootstrap + drift/expand commands)**: Implemented first pass of schema lifecycle support for moat-first workflows. `FortificationPersistenceService.SavedFortification` now persists schema metadata (`schemaVersion`, `schemaRevision`, `schemaUpdatedAt`, `schemaOrigin`, `schemaParentName`, POI+structure signature, hull signature) with legacy-safe normalization on load. `FortifyVillageSkill` now supports: (1) **moat auto-bootstrap** when no saved wall exists (`/bot fortify moat` or named moat run creates and saves a schema from nearby village scan), (2) **drift inspection** via `/bot fortify drift [name]` (reports stable/minor/major drift using anchor + hull signatures and overlap), and (3) **explicit expansion** via `/bot fortify expand [name]` (also used by `merge` alias) that merges current footprint into the existing schema, carries over build progress via world scan (per-edge actual counts/completed edges), resets moat completion for the expanded perimeter, and auto-resumes build. Also made new-build naming collision-safe by uniquifying auto wall names.
- **Construction/Base menu UX refresh for fortify lifecycle**: Updated `ConstructionScreen` stronghold workflow presentation with explicit subsection headers (`Build & Resume`, `Maintain`, `Evolve Schema`) and added direct entries for **Drift Check** (`/bot fortify drift`) and **Expand Wall** (`/bot fortify expand`) plus a **Wall Manager** shortcut. Updated `BaseManagerScreen` with named-wall actions for **Drift Check** and **Expand Wall** so recommended `/bot fortify expand <name>` flow is available directly from the wall list UI.
- **Moat cleanup accounting/retention fix (partial corner/tower leftovers)**: Session logs showed contradictory outcome lines (`[Moat] cleanup-pass end: 0 remaining` followed by `Moat ... 2 remaining`). Root cause: cleanup pass removed repeatedly blocked targets from the `remaining` working set (`remaining.remove(target)`) after failure-cap, so unresolved corner/tower-adjacent blocks could be dropped from cleanup accounting even when still present in-world. Updated cleanup pass to **defer** blocked targets for the current pass (new `deferredTargets` set) instead of deleting them from `remaining`, preserving accurate unresolved counts and preventing silent loss of stubborn moat targets.
- **Moat stuck-recovery anti-oscillation pass (session inspect follow-up)**: New logs showed moat nav no longer carve-thrashing in moat context (break-through suppressed) but still looping in repeated sidesteps at trench depth (`ctx=fortify-moat:perimeter`, alternating positions, `result=sidestep moved=true` with negligible gain). Added two mitigations in `walkToTarget`: (1) moat-specific early surface escape when bot is clearly below target walk Y (`ensureOnSurface(..., targetY)`), and (2) moat sidestep no-progress burst budget (`FORTIFY_MOAT_SIDESTEP_NO_PROGRESS_LIMIT`) mirroring gate logic so repeated low-gain sidesteps stop masking true no-progress and allow arc/path recovery to take over.
- **Moat carve-path stabilization (gate-aware moat navigation)**: Analyzed logs showing moat perimeter navigation repeatedly carving/replacing wall columns near gate-adjacent waypoints (`Breaking through WALL ...`, `Force-replaced mined block ...`, `rejectReasons=village_adjacent`) instead of routing around fortification boundaries. Updated moat movement to call `navigateThroughGateIfNeeded(...)` before perimeter/direct dig approach moves and switched moat perimeter walking to `walkToTarget(..., "fortify-moat:perimeter")`. In `walkToTarget`, moat contexts now suppress break-through recovery (`allowBreakThrough=false` for `fortify-moat*`) so stuck recovery uses non-destructive sidestep/arc instead of carve-thrashing walls.
- **Moat pass-1 start index fix (session follow-up)**: Analyzed `latest.log` from a partial moat run (`[Moat] simple-perimeter: 17 total ...`) showing long stuck-recovery chains immediately after start (bot at ~836,-62,-246 repeatedly targeting far waypoint ~815,-60,-221). Root cause: `digMoatSimplePerimeterWalk` always started from path index 0, forcing long cross-perimeter traversals in resume/partial states. Updated pass-1 loop to start from `nearestPathIndex(bot, perimeterPath)` and wrap around the path, so the bot begins digging from the nearest waypoint instead of marching across previously dug/problematic sections first.
- Build/deploy verified: `./gradlew build -x test` (success), Prism mods jar refreshed (`frens-1.0.6-release+1.21.11.jar`).

## 2026-03-01
- **Moat nav reliability follow-up (post-playtest logs)**: Confirmed regression fixed where break-through recovery immediately re-filled escape paths (`[FortifyNav] Replaced mined block ...`) by restricting non-deferred carve replacement to mandatory fortification blocks only. Natural terrain broken during navigation is no longer backfilled in-place.
- **Moat perimeter waypoint hardening**: `buildPerimeterPath` now accepts blocked walk columns and `executeMoat` passes all `MOAT_DIG`/`EXTERIOR_CLEAR` XZ columns as disallowed candidates. This prevents local waypoint search drift from snapping onto moat trench columns during pass-1 perimeter walking.
- Build/deploy verified: `./gradlew build -x test` (success), Prism mods jar refreshed (`frens-1.0.6-release+1.21.11.jar`).

## 2026-02-27
- Follow-up: fully reverted the shared inventory screen experiment and restored its original handled-screen background/render behavior; kept only the `BotRestoreScreen` blur crash fix.
- **Moat reliability pass (simple perimeter v2)**: Improved `fortify moat` execution after mixed playtest results. Changes in `FortifyVillageSkill`: moat perimeter path now includes corner/tower-adjacent coverage (vertex skip override for moat mode), dig loop now uses a bounded second cleanup pass for leftover targets (direct approach + retry caps + no-progress cap), and moat walking now performs depth recovery before continuing when the bot drops into trench pockets. Completion semantics were tightened: moat structure placement is skipped when dig targets remain, `moatComplete` persistence is set only when all dig targets are cleared, and final chat/reporting now distinguishes **partial** vs **complete** moat outcomes with remaining-count visibility. Build verified: `./gradlew build -x test` (success).
- **Moat separated as standalone skill + STRONGHOLD UI category**: Major refactor separating the moat-digging phase from the perimeter wall build into an independent action. Changes across 7 files:
  - **ConstructionScreen.java**: Full rewrite with category system. New record field `category`. 4 categories: SHELTER (green), DEFENSIVE (tan), STRONGHOLD (gold), UTILITY (blue-gray). Section headers rendered as centered "── CATEGORY ──" with per-category colors. STRONGHOLD entries: Fortify Village, Fortify Patch, Fortify Moat, Fortify Status, Fortify List. Scrollable layout with 2-column grids per section. Extensible for future Gatehouse/Bridge/etc entries.
  - **FortifyMoatSkill.java** (new): Thin delegating skill (`fortify_moat`). Auto-detects nearest wall or accepts explicit name. Looks up `FortifyVillageSkill` singleton via `SkillManager.getSkill()` and calls `executeMoat()`.
  - **FortifyVillageSkill.java**: Added `executeMoat()` public method — loads saved wall, regenerates layout from hull + surface profile, runs moat dig phase (collect MOAT_DIG/EXTERIOR_CLEAR, densify, `digAllMoatBlocks()`), then places moat structural blocks (MOAT_FLOOR, MOAT_INNER_FACE, MOAT_OVERHANG), marks `moatComplete` in persistence. Added `moat` and `moat <name>` subcommand routing. Added `status` no-arg auto-detect (nearest wall). Removed Phase A moat block from `buildWall()` — wall build now unconditionally skips moat/clearance work. Removed `ENABLE_MOAT_STAGE` constant.
  - **FortifyLayoutHelper.java**: Removed `ENABLE_MOAT_STAGE` constant. `isActiveFortifyBlock()` now unconditionally excludes moat-related types (handled by separate skill).
  - **FortificationPersistenceService.java**: Added `moatComplete` boolean field to `SavedFortification` with getter `isMoatComplete()`. Added `setMoatComplete()` static method.
  - **SkillManager.java**: Added `getSkill(name)` public accessor. Registered `FortifyMoatSkill` after `FortifyVillageSkill`.
  - Build verified: BUILD SUCCESSFUL.
- **Fortify force-place config toggle (default OFF)**: Added `fortifyForcePlaceEnabled` config toggle for unreachable edge blocks where `losScore=0` from all candidate positions. Edges 16/17 each had 1 block fully occluded by surrounding wall structure — no scaffold position provided LOS to any support face. When enabled, the bot falls back to `BotActions.forceReplaceBlock()` (world.setBlockState) after scaffold escalation fails to find any candidate with line-of-sight, and also after the post-scaffold vanilla placement loop for blocks that still couldn't be placed. Config: `ManualConfig.fortifyForcePlaceEnabled` field + getter/setter, `/bot config forceplace <on|off>` chat command with query support, "Fortify Force-Place" toggle in BotControlScreen admin GUI. Default OFF preserving vanilla mechanics preference. 4 files changed. Build verified: BUILD SUCCESSFUL.
- **Fortify edge geometry/LOS fixes (4 fixes)**: Diagnosed persistent placement failures at edges 10, 15, 16, 17 where blocks could never be placed across multiple passes. **Fix 1** (scaffold trigger threshold): Changed from requiring NO_LOS alone ≥50% to counting `NO_LOS + OUT_OF_REACH` together — Edge 10's 3+3=6/8=75% now correctly triggers scaffold escalation (was 3/8=37.5% NO_LOS-only, below threshold). **Fix 2** (multi-candidate scaffold positions): `attemptScaffoldEscalation` now generates 5 candidate positions (toward-bot + 4 cardinal offsets), validates overhead clearance for each, and scores by `countReachableWithLOS` from simulated elevated eye position — fixes Edge 15 where the single blind position had overhead obstruction. **Fix 3** (lateral-first for ground-level targets): Before scaffolding, if all remaining targets are ≤ bot Y+2, tries placing from 2 perpendicular lateral positions first — fixes Edges 16/17 where pillaring up worsened the LOS angle to support faces at/below eye level. **Fix 4** (reach consistency): Changed `isWithinReach()` from eye-based (`bot.getEyePos()`) to feet-based (`bot.squaredDistanceTo()`) to match `BotActions.tryPlaceBlockAt()`'s reach gate — eliminates spurious OUT_OF_REACH where pre-check passed but actual placement failed on blocks below the bot. Build verified: BUILD SUCCESSFUL.
- **Fortify tower stuck-loop fixes (3 fixes)**: Diagnosed 25-minute session where bot placed only 12 tower blocks due to stuck-recovery loops (193 attempts, 69 walk timeouts, 101 pursuit failures, 53 suffocation events). Root causes: (A) `towerStartMs` was set AFTER long-range approach navigation, so the 20s zero-progress budget never covered approach failures; (B) `clearEscapeShaftHeadroom` couldn't break overhead blocks near villages because `digBlock` has `isAdjacentToVillageStructure` check; (C) `ScaffoldService.pillarUp` vanilla placement rejected by entity collision. **Fix A** (FortifyVillageSkill): Added `towerOverallStartMs` before approach navigation; 45s overall budget now enforced in both local attempt loop and scaffold phase gate. **Fix B** (FortifyEscapeHelper): Added `else if (dy <= 3)` branch in `clearEscapeShaftHeadroom` to force-dig immediate overhead using `digBlockForNavigation` (skips village adjacency check). **Fix C** (FortifyEscapeHelper): Added force-pillar fallback in `tryPillarEscapeFirst` — when `ScaffoldService.pillarUp` fails but shaft is clear, uses `BotActions.forceReplaceBlock` (world.setBlockState) in a jump-place loop. Build verified: BUILD SUCCESSFUL.
- **FortifyVillageSkill refactoring — FortifySkillOps interface + FortifyEscapeHelper extraction**: Created tiered callback interface `FortifySkillOps.java` (113 lines) with `FortifyBlockOps` (Tier 1: block queries, digging, sleeping, abort/overhead, scope access — 12 methods) and `FortifyNavOps extends FortifyBlockOps` (Tier 2: navigation, walk, scaffold, escape, reach-with-effort — 13 additional method overloads). `FortifyVillageSkill` now `implements FortifySkillOps.FortifyNavOps` with `@Override` annotations on all 25 interface methods (visibility widened from `private` to `public`). Extracted escape/precipice-defense logic into `FortifyEscapeHelper.java` (491 lines): `isFortifyPrecipiceDefenseContext`, `airDropDepth`, `hasDangerousFortifyPrecipiceAhead`, `tryPatchFortifyFootingNearWorksite`, `isFortifyEscapeContext`, `countEscapeShaftBlockers`, `clearEscapeShaftHeadroom`, `clearImmediateOverheadForEscape`, `tryPillarEscapeFirst`, `escapeIfInHole` (2 overloads), `ensureOnSurface` (2 overloads), plus static `dominantHorizontalDirection`. Helper takes `FortifyBlockOps` for block mutations, `FortifyEntombmentHelper` for entombment state, and `Supplier<Set<BlockPos>>` for protected positions (handles re-assignment of `fortificationProtectedPositions`). Footing-patch cooldown state (`fortifyFootingPatchLastMs/Origin`) moved to helper. Constants moved: `FORTIFY_FOOTING_PATCH_COOLDOWN_MS`, `FORTIFY_PRECIPICE_DEFENSE_MIN_DROP`, `FORTIFY_FOOTING_PATCH_SCAN_DEPTH`, `FORTIFY_FOOTING_PATCH_MATS`, `MAX_SCAFFOLD_HEIGHT` (duplicated). Skill retains thin delegates for all extracted methods to minimize call-site churn. Skill: 8944→8611 lines (-333). Build verified: BUILD SUCCESSFUL.
- **FortifyVillageSkill refactoring — FortifyLayoutHelper extraction**: Extracted layout query methods, block satisfaction checks, material fallback lists, and edge ordering from `FortifyVillageSkill` (9161→8943 lines) into new `FortifyLayoutHelper` (280 lines). Moved constants: 5 material fallback lists (`STONE_BRICK_FALLBACKS`, `OAK_LOG_FALLBACKS`, `CHISELED_FALLBACKS`, `SLAB_FALLBACKS`, `COBBLE_FALLBACKS`), `ENABLE_MOAT_STAGE`. Moved methods: `buildCandidateList`, `isMoatRelatedType` (static), `isActiveFortifyBlock`, `isPlannedBlockSatisfied`, `computeEdgePlannedCounts`, `countPresentBlocks`, `chooseEdgeStartIndex`, `orderedRemainingEdges`, `pickNearestRemainingEdge`, `patchEdgeDistanceSq` (static). Helper takes shared `ignoredCavityPositions` Set reference in constructor. High-call-count methods (`isActiveFortifyBlock` 15 sites, `isPlannedBlockSatisfied` 11 sites, `countPresentBlocks` 11 sites, `computeEdgePlannedCounts` 2 sites) retained as thin delegates in skill to minimize churn. 8 direct call sites updated; `tryReplaceMinedBlock` references `FortifyLayoutHelper.STONE_BRICK_FALLBACKS`/`COBBLE_FALLBACKS`. Build verified: BUILD SUCCESSFUL in 4s.
- **FortifyVillageSkill refactoring — FortifyEntombmentHelper extraction**: Extracted entombment recovery state tracking, surface-escape retry tracking, and carve-column cooldown management from `FortifyVillageSkill` (9901→9593 lines) into new `FortifyEntombmentHelper` (411 lines). Moved: `EntombmentRecoveryState`, `SurfaceEscapeRetryState` inner types; 4 constants; 3 state maps; 21 methods including `noteEntombment*`, `shouldPreferEntombmentEscape`, `isFortifyEntombmentCandidate`, `shouldSkipRepeatedSurfaceEscape`, surface-escape retry tracking, carve-column cooldown, and `isSealedFortifyEntombmentSurfaceEscapeCell`. Helper receives shared context (layout, nav scope, movement epoch) via setter methods called from `beginFortifyNavScope`/`endFortifyNavScope` and layout setters. `fortifyContextPrefix` became a static utility on the helper. All 35+ external call sites in the skill now delegate to `entombmentHelper.*`. Trivial predicate delegates (`isTrapLikeCell`, `canStandAt`, `isInsideCurrentFortificationHull`, `isAdjacentToCurrentFortificationHull`) duplicated in helper for self-containment.
- **FortifyVillageSkill refactoring — FortifySkillTypes extraction**: Extracted all 9 private inner enums, 16 records, and 5 inner classes from `FortifyVillageSkill` (9593→9246 lines) into new package-private `FortifySkillTypes.java` (~300 lines). Moved: enums `FortifyNavMode`, `FortifyCleanupKind`, `CleanupState`, `NavBreakRejectReason`, `ReplaceFailureKind`, `TowerPillarOutcome`, `TowerStepOutcome`, `TowerReturnOutcome`, `TowerScaffoldSideOutcome`; records `NavBreakCandidateEval`, `CavityCheckResult`, `DeferredRepair`, `ReplaceBlockResult`, `TowerStepAttemptResult`, `TowerReturnAttemptResult`, `TowerSummitStepCandidate`, `TowerSummitRoamResult`, `TowerHardResetResult`, `FortifyNavProgressWindow`, `TowerNavCandidate`, `ApproachCandidateEval`, `ScaffoldTeardownResult`, `SurfaceProfile`, `StartupRecoveryResult`, `MoatDigResult`; classes `DeferredCleanupTask`, `FortifyCarveSession` (constant `FORTIFY_CARVE_MAX_BLOCKS_PER_EPISODE` absorbed), `FortifyNavRuntimeScope`, `TowerNavAttemptState`, `ScaffoldLedger`. All types are package-private; accessible unchanged from FortifyVillageSkill in the same package. Build verified: BUILD SUCCESSFUL in 8s.
- **FortifyVillageSkill refactoring — FortifyCleanupHelper extraction**: Extracted the deferred cleanup queue, throttle timestamp, 3 constants, and 9 pure queue-management methods from `FortifyVillageSkill` (9246→9161 lines) into new `FortifyCleanupHelper` (143 lines). Moved constants: `BACKOFF_BASE_MS=250`, `BACKOFF_MAX_MS=1000`, `PROCESS_MIN_INTERVAL_MS=350`. Moved methods: `queue` (was `queueDeferredCleanupTask`), `queueCarveRepairs` (was `queueDeferredCarveRepairs`), `noteSkip`, `noteImmediateRetry`, `noteResolved`, `checkAndUpdateThrottle` (new, replaces inline throttle logic in processDeferredFortifyCleanupQueue), `isForcedContext`, `allowActiveRecovery`, static `incrementReason`, static `formatReasonSummary`. Skill adds `private final FortifyCleanupHelper cleanupHelper = new FortifyCleanupHelper()` and delegates all 32 call sites. `processDeferredFortifyCleanupQueue` stays in skill (calls `digBlock`, `sleepQuiet`, `tryReplaceMinedBlock`). Build verified: BUILD SUCCESSFUL in 3s.
## 2026-02-26
- **Movement & Stuck Recovery Consolidation**: 
  - Extracted the massive 7-step stuck recovery fallback chain (open door, traverse doorway, door escape, step up, local unstick, sidestep, mine obstruction) from `nudgeTowardUntilClose` and `pursuitUntilClose` into a single, shared `executeStuckRecoveryHeuristics` method in `MovementService`. This removes ~150 lines of duplicated code and ensures all short-range movement uses the exact same, predictable recovery logic.
  - Pruned the complex and error-prone diagonal staircase escape logic from `FortifyVillageSkill.escapeIfInHole`. When the bot falls into a moat, it now always uses a simple, reliable vertical pillar escape (`ScaffoldService.pillarUp`).
  - Removed the redundant `trySimplePillarEscape` fallback from `FortifyVillageSkill`, relying entirely on the centralized `ScaffoldService.pillarUp` to prevent race conditions and state confusion.

## 2026-02-25
- **Fortify entombment escape adaptation (learning-trace informed)**: Emergency trap/entombment break-through now prefers a head-first carve order (`head -> overhead -> feet`) and restores carved blocks top-down after crossing (`overhead -> head -> feet`) to match successful player escape behavior and reduce self-re-entombing during tower escape repairs.
- **Scaffold/tower control generalization tuning (learning-trace informed)**: Used first `pillaring_to_roof` learning trace cadence (jump/place/land timing + client input samples) to generalize construction movement control in shared scaffold logic and tower summit stepping. `ScaffoldService` now recenters on the current perch before pillar jumps, broadens the jump-place readiness window, and retries placement across the airborne window instead of a single apex-only attempt. Tower summit step-out / step-back now recenter before and after moves, use distance-scaled sneak impulses, and require stable landing on the intended target/perch column before counting success.
- **Learning Mode v1 (generalized demonstration capture + admin toggle)**: Added `/bot learn` command family (`status`, `arm`, `start`, `stop`, `mark`, `list`, `report`) plus operator Admin-tab actions for learning status/start/stop. New `LearningModeService` records generalized player demonstrations to JSON/JSONL (movement/camera state, block interactions, mining starts, breaks, inferred place results, local voxel snapshots, warnings, optional paired-bot telemetry), with bounded sampling tiers (`core|balanced|heavy`) and optional client input telemetry streaming. Sessions write to `config/frens/learning/<worldKey>/<sessionId>/` with `session.json`, `events.jsonl`, and `summary.json` for targeted bot-control tuning.
- **Fortify tower reliability pass (top-block repair + scaffold summit hardening)**: Fortify break-through now verifies/queues mandatory repairs for every mined column block (including the frequent overhead “third block” miss), retries transient replacement failures with reason-aware logging, and pushes unresolved mandatory replacements into the deferred cleanup queue immediately instead of dropping them. Tower scaffold patching now uses stricter summit step/return validation (must land on the intended top block/perch), fall/off-target recovery teardown, top-layer verification before leaving a tower side, and cleanup queue backoff/reason buckets to reduce repeated scaffold thrash and `remaining=1` spam. `ScaffoldService` pillar placement also retries `bot-intersects-target` timing failures with adaptive retargeting and a stricter jump place-window timeout.
- **Fortify towers: outside-only repair with safe cavity ignore + reporting**: Patch scan now skips interior tower cells not reachable from the exterior shell and classifies tiny sealed voids (≤2 air blocks or non-spawnable) as ignored cavities. Ignored cavities are tracked, treated as satisfied, logged, and can be visualized via the new `/bot fortify report_cavities` helper.
- **Navigation guardrail: no tower carve through cores**: Break-through attempts in tower patch contexts now require layout blocks to be exterior-reachable before mining, stopping Swiss-cheese tunneling and underground corridors while keeping gate/wall carve behavior unchanged.
- **Build/deploy**: Rebuilt `frens-1.0.6-release+1.21.11.jar` with JDK21/Gradle wrapper; refreshed `releases/ai-player-1.0.6-release+1.21.11.jar` and Prism mods copies (ai-player & frens).
- **UI**: Added “Fortify: Report Cavities” shortcut to the Construction menu to trigger `/bot fortify report_cavities` without typing.
- **Dialogue**: When the bot is standing at an ignored cavity while running `fortify report_cavities`, it now speaks a natural line via the overhead hologram (stats remain in chat).

## 2026-02-24
- **Fortify: gate nav no break-through + micro-replan before carving**: `walkToTarget` now runs a one-time micro pathfind (planLootApproach without door/obstruction assists) when stuck before attempting break-through, and disables break-through entirely when walking gate waypoints (`navContext` starts with `fortify-gate`). This prevents village-adjacent carve spam inside gatehouses and lets bots take existing openings before mining.
- **Fortify: trap-escape regression rollback + guardrails (gate/tower entombment)**:
    - Reverted the emergency `noFloor` support-column trap escape experiment in `tryBreakThroughObstacle`. It caused under-floor/remote placement attempts (multi-block downward fills) and produced false-positive "escape success" logs on 1-block oscillation.
    - New guardrail: emergency trap carve mode no longer treats same-level air candidates as break-through success (`A <-> B` wiggle suppression). `tryBreakThroughObstacle` now requires meaningful progress for trap escapes (topology/open-face improvement, target distance gain, or larger net displacement).
    - Added step-aware emergency trap candidate offsets (`dy = -1 / +1`) for immediate neighbors so the bot can try real step-out exits without placing support columns under itself.
    - Hard guardrail: no downward mining in emergency trap escape. `dy < 0` candidates may be used only for already-open step-down moves; "tunneling" is enforced as a wall opening through the obstacle, not excavation below floor level.
    - Regression notes captured in `docs/reliability/FORTIFY_NAV_GUARDRAILS.md` (failed approaches, constraints, and acceptance checks for trap recovery).
- **Fortify: fix gate routing for tower vertices + stuck timeout**: Root cause of bot stuck at wall for 50+ seconds: `navigateThroughGateIfNeeded` checked `pointInConvexHull()` for the target, which returns `true` for boundary points (uses `cross >= 0`). Tower vertices ARE hull vertices — on the boundary — so they were treated as "inside" and gate routing never fired. Bot then tried to path directly through the wall. Three fixes: (1) Added `pointStrictlyInsideHull()` that uses `cross > 0` (strict interior only); tower vertex targets now correctly trigger gate routing. (2) `tryBreakThroughObstacle` now logs diagnostic counts for rejection reasons (allAir/canBreak/reach/noFloor) when no viable candidates found. (3) Tower approach loop has a 20-second safety timeout — if zero blocks placed after 20s, skip to the next tower instead of burning 50+ seconds.

## 2026-02-23
- **Fortify: pillarToY partial-success tolerance + placement diagnostics**: Two fixes. (1) Tower scaffold phase now accepts partial pillar success — if the bot gained height (postPillarY > startBotY) and has tracked scaffolds, it proceeds to place tower blocks from the elevated position instead of tearing down and retrying. Previously, placing 3/4 blocks (1 short of target) was treated as total failure, wasting 3 scaffold blocks + 3 seconds. The edge escalation caller already had this pattern (`pillared || bot.getBlockPos().getY() > currentY`). (2) `tryPlaceBlockAt` failure reason now includes item name, target pos, support pos/face, and placement context pos instead of just the obfuscated ActionResult (`class_9857[]`). Makes scaffold/tower placement failures debuggable.
- **Test run analysis — gate routing v5 + scaffold fix**: 7.5-minute session, patched ~5 blocks total (1 edge scaffold, 3+1 tower scaffold). Gate routing v5 Y-fix confirmed (waypoints at correct Y=-60), but walk-through still fails on XZ alignment — bot misses the 3-block gate gap. Failure caching works (skips after 2 failures). New issues identified: (1) scaffold `place-rejected=class_9857[]` — Minecraft rejects block placement during jump-pillar despite passing all pre-checks, (2) `pillarToY` treats partial success as failure (placed 3/4 blocks, tore them all down instead of using the height), (3) edge NO_SUPPORT failures (6/15 on edge 26).
- **Fortify: gate-routing v5 — use bot's actual Y, not lintel Y**: v4's `gateCenterY` came from `safeSurfaceY()` which at the gate center XZ hits the stone brick lintel (Y+3 above ground) and returns that Y instead of the walkable ground. With gateCenterY=-58 and bot at Y=-60, `Vec3d.ofCenter` put the target at Y=-57.5 — a 2.5-block Y gap contributing 6.25 to `squaredDistanceTo`. This made `walkTowardBlock` (threshold 6.0) literally unable to ever "arrive" and `walkToTarget` (threshold 9.0) require the bot to be within 1.66 blocks in XZ. Fix: all gate waypoints (interior, gate center, exit, extension) now use `bot.getBlockPos().getY()` — the actual ground level the bot walks on. Also added gate routing failure caching: after 2 consecutive failures, skip gate routing entirely and fall back to normal nav immediately, saving 30+ seconds per edge.
- **Scaffold: fix pillar-up placement failures on occupied blocks**: Root cause of 0% tower scaffolding success — `pillarUp` captured `bot.getBlockPos()` as the placement target, but when the bot stood on stairs/slabs/walls, that position was already occupied and `placeBlockAt` silently rejected it. Fix: both `pillarUp()` and `pillarUpWithPositions()` now check if the feet position is non-air/non-replaceable and shift the target up (up to 2 blocks) to the first air block. Also switched from `placeBlockAt` (boolean) to `tryPlaceBlockAt` (PlaceResult) so failure reasons are logged. Expanded `SCAFFOLD_BLOCKS` with cobblestone and stone to avoid using expensive wall material for scaffolding.
- **Fortify: gate-routing v4 — use gate center Y for all waypoints**: v3's interior approach still arrived at moat level because `safeSurfaceY()` uses the heightmap which returns moat-floor Y after digging. The interior at (911, -61, -219) was 3 blocks below the gate center at (913, -58, -222) — an impossible 3-block step-up. Fix: all three waypoints (interior, gate center, exit) now use `gateCenterY` from the layout's planned surface profile, ignoring the post-dig heightmap. Interior approach arrives at the same Y as the gate opening, enabling a flat walk through the gap. Exit Y is also surface level — bot steps down onto the moat bridge naturally.
- **Fortify: 3-block break-through, LOS tower approach, scaffold outside tower footprint**: Three fixes. (1) `tryBreakThroughObstacle` now clears 3 blocks (feet Y+0, head Y+1, overhead Y+2) instead of 2, preventing tall walls (4+ blocks) from clipping the bot during walk-through. Overhead mining is best-effort — skipped if unreachable or unbreakable. Mines top-down; rollback and replacement include all 3 blocks. (2) `chooseTowerApproachPos` now scores candidates by LOS-reachable unsatisfied tower blocks via `world.raycast()`. Positions where zero blocks are visible through the tower center post are rejected, eliminating wasted approach attempts on the wrong face. New helpers: `hasLineOfSight`, `countReachableWithLOS`. (3) `chooseTowerScaffoldPos` skips positions inside the 3x3 tower footprint (`abs(dx)<=1 && abs(dz)<=1`). Previously distance-1 positions landed on tower ring blocks with overhead obstructions, causing pillar-up failure. Distance-2 cardinal is now the closest valid scaffold position.
- **Fortify: sneak during scaffold pillar + cap break-through retries**: Two fixes. (1) `executeTowerScaffoldPhase` now engages `SneakLockService` + `BotActions.sneak` BEFORE `ScaffoldService.pillarToY`, preventing the bot from walking off the scaffold column during pillar-up. Previously sneak was only engaged after pillar completion, so any timing issue during the jump-place-land cycle caused the bot to fall and leave scattered scaffolding. (2) `walkToTarget` now increments `breakThroughCount` on failed break-through attempts (no viable candidates), not just successes. Previously the count only incremented on success, so the bot could cycle endlessly: stuck → break-through fails (no adjacent wall) → sidestep → walk back → stuck again. Now caps at 3 total attempts before moving to sidestep-only recovery.
- **Fortify: fix walkToTarget stuck detection — BlockPos-based instead of distSq**: The root cause of the bot never breaking through walls: `walkToTarget`'s stuck detection compared `Math.abs(currentDistSq - lastDistSq) < 0.5` each tick. When the bot bounced off a wall, its position oscillated, changing distSq by >0.5 each tick, permanently resetting `stuckTicks` to 0. Break-through (at stuckTicks > 10) was NEVER reached. Replaced with BlockPos-based detection: if the bot's integer block position hasn't changed in 10 ticks, it's stuck — immune to floating-point oscillation. Also added diagnostic log when `tryBreakThroughObstacle` finds no viable candidates.
- **Fortify: walkToTarget fallback after long-range nav for break-through**: After `MovementService.execute` gets the bot close but not at the destination (e.g. stuck 6 blocks away with a wall between), the recovery code now calls `walkToTarget` which walks the bot tick-by-tick up against the wall, triggers stuck detection, and fires break-through automatically. Previously `tryBreakThroughObstacle` was called directly but the bot wasn't adjacent to the wall so it found no blocking candidates and silently returned false. Same fix added for tower long-range nav.
- **Fortify: gate snap-teleport behind teleport toggle, fix scaffold step-onto regression**: (1) `MovementService.walkSegment` snap-forward teleport (up to 2.2 blocks) now checks `SkillPreferences.teleportDuringSkills(player)` — bots with teleport disabled no longer snap through walls when stuck. The full `GoTo` teleport was already gated; this closes the mini-teleport loophole. (2) Reverted step-onto-structure movement from `walkTowardBlock` (which ran at 0.28D and walked the bot off the scaffold) back to direct impulse at 0.12D sneaking speed targeting the actual 1-block-away step position. Same fix for return-to-scaffold movement.
- **Fortify: fix break-through traversal, higher/closer scaffolding, step-onto-structure**: Five fixes to fortification building. (1) `tryBreakThroughObstacle` now walks toward a point 4 blocks past the gap via `walkTowardBlock`, bypassing its `distSq < 6.0` early-exit that caused futile mine-replace loops (feetPos was only 1 block away, distSq ≈ 1.0). (2) `ensureCanReachBlockWithEffort` scaffolds on pass 1 when `verticalDiff >= 4` (clearly unreachable from ground). (3) `executeTowerScaffoldPhase` raises `optimalY` from `maxTargetY - 1` to `maxTargetY` — bot scaffolds 1 block higher, putting feet at the highest target block level for better reach and enabling step-onto. (4) `chooseTowerScaffoldPos` now starts at distance 1 (was 2), placing scaffold adjacent to the tower so wall blocks are immediately steppable. (5) Step-onto-structure phase after scaffold placement: bot walks onto adjacent solid structure blocks, places newly-reachable far-side blocks, then returns to scaffold for safe teardown.
- **Fortify: aggressive break-through & wider-arc navigation**: `walkToTarget()` now allows up to 3 break-throughs per walk (was 1), lowers stuck threshold from 15 to 10 ticks, and adds lateral sidestep recovery (moves 4 blocks perpendicular to target, alternating sides). `navigateToEdgeApproach()` lowers the pathfinding threshold from 20 to 12 blocks so medium-range moves also get proper pathfinding. Both pathfinding and short-range approaches now share a unified post-nav recovery: break-through attempt first, then wider-arc retry (7 blocks out along the outward normal) if still >5 blocks away.
- **Fortify: scaffold height +1**: Changed scaffold target from `target.getY() - 2` to `target.getY() - 1` in three locations (FortifyVillageSkill `ensureCanReachBlockWithEffort`, `executeTowerScaffoldPhase`, and ScaffoldService `calculateScaffoldHeight`). Puts the bot one block higher, giving a better downward angle to top wall/tower blocks that were often unreachable at the old height.
- **Fortify: proactive stray scaffold cleanup in auto-patch**: Added `scanAndRemoveStrayScaffolds()` to FortifyVillageSkill. Scans XZ columns near hull vertices and edge midpoints (±4 blocks) for leftover scaffold pillars (2+ consecutive dirt/cobbled_deepslate/netherrack blocks above surface Y, not in layout). Mines them top-down via `digBlock()`. Runs at the start of each auto-patch pass before repairs begin, so abandoned scaffolds don't block wall placement.

## 2026-02-22
- **Fortify: break-through stuck recovery**: When the bot gets stuck navigating between wall segments (wedged in corners between existing cobblestone walls), it now mines through blocking blocks as a last resort, walks through the gap, then replaces them. Two-tier safety: first tries non-layout blocks, then allows breaking its own fortification wall blocks (with mandatory replacement using wall material fallback lists). If replacement fails, logs a warning for auto-patch to repair. Max one break-through per `walkToTarget()` call to prevent tunnel mining. Integrated into `walkToTarget()` (stuck handler), `tryUnwedgeFromTightSpace()` (failure path), and `navigateToEdgeApproach()` (post-MovementService fallback).
- **Fortify: sprint during walkToTarget navigation**: Changed `BotActions.sprint(bot, false)` to `sprint(bot, !onScaffold)` in `walkToTarget()` so the bot sprints when navigating between sections (but not on scaffolds).
- **Baritone Pathfinder toggle in Admin tab**: Added persisted `baritonePathfinderEnabled` field to `ManualConfig`. New toggle in Admin tab sets `PathFinder.USE_BARITONE_STYLE` and persists to `settings.json5`. Value restored on server start. `/bot config pathfinder` command also syncs to config.
- **Remove duplicate toggles from Skills tab**: Removed Voiced Dialogue, Teleport during Skills, and Teleport during Sweeps from the Skills tab — these per-bot settings are already in Bot Controls screen (their canonical home).
- **Permission predicate proxy fix**: Replaced broken `Proxy.newProxyInstance` last-resort fallback in `Frens.java` with direct `new PermissionPredicate()`. The proxy would throw `IllegalArgumentException` since `PermissionPredicate` is a class, not an interface. Dead code in practice (shipped stubs always resolve at step 1), but now correct if ever reached.
- **Bot Controls UI refactor**: Replaced flat all-bots-at-once grid layout with single-bot view. Dropdown selector picks an alias; settings shown vertically in labelled groups (Spawning, Behavior, LLM) with descriptions. Added 8th setting `voicedDialogue` (was missing from old UI). Edits buffered per-alias so switching bots doesn’t lose unsaved changes. Added `isOpen()` and `renderOnTop()` to `DropdownMenuWidget` for z-order/input priority.
- **Bot Controls shortcut in Admin tab**: Added "Bot Controls >" entry to the Admin tab in `BotPlayerInventoryScreen`, opens the new single-bot config screen directly from the bot interaction UI.

## 2025-11-19
- Hardened suffocation recovery: multiple iterations to detect head/feet blockage before damage ticks, throttle alerts, and mine with the correct tool rather than instant breaks. Spawn-in-block checks now run shortly after registration.
- Upward stairs (ascent) refinements: walk-and-jump algorithm with headroom increases, issuer-facing direction lock, button-based direction overrides, and explicit `lockDirection` parameter for consistent stair orientation. Direction state resets per command to avoid stale facings.
- Safety changes: blocked destructive helpers (`digOut`, `breakBlockAt`) to enforce tool-based mining; escape routines schedule work on tick instead of blocking server threads. Added hazard scanning during ascent and tightened drop cleanup to reuse trusted sweep logic.
- Docs: added button-orientation tip to the guide and logged ascent headroom tweaks and obstruction damage gating.

## 2025-11-20
- Follow rework: bots now chase with WASD-style input, timeboxed path steps (no tick stalls), sensible teleport catch-up, and chill when adjacent; hill walking and vertical catch-up improved.

## Unreleased
- Fortify: skip towers on resume — when `startEdgeIndex > 0`, tower phase is skipped entirely (already attempted in prior session, finish with patch). Eliminates ~60s of wasted walking to already-built towers.
- Fortify: skip already-built tower vertices — `countPresentBlocks` check before walking to each vertex; 90%+ present = skip without navigating.
- Fortify: **moat is now a single unified phase** — all MOAT_DIG + EXTERIOR_CLEAR blocks across ALL edges are collected and dug in one continuous sweep before any wall placement begins. Sorted by XZ locality (16-block grid regions → XZ column → top-down Y) so the bot works in one physical area regardless of edge boundaries. Eliminates the per-edge dig→place→escape→next-edge cycle that caused repeated stuck loops.
- Fortify: **reference surface Y from FOUNDATION blocks** — `escapeIfInHole()` now uses the original terrain level (from layout FOUNDATION blocks) instead of the heightmap, which changes after moat digging. Previously the bot thought it was on the surface when it was actually on the moat floor because `terrainY()` returns the current top block, not the original surface.
- Fortify: reduced movement overhead — removed redundant `walkTowardBlock` calls and unnecessary sleep delays in dig loop; single fast reach-check instead of double walk attempt; reduced walkTowardBlock timeouts from 2000/1500ms to 1500ms; removed 50ms sleep after each dig (MiningTool already waits via CompletableFuture).
- Fortify: `escapeIfInHole()` rewritten as staircase ramp builder — when bot is trapped below terrain (e.g. in moat on resume), it picks the best direction and builds a diagonal staircase upward: places blocks under feet, clears headroom above, jumps forward and up, repeating until at terrain level. Works from anywhere including from inside the moat. Pillar-up as last resort if staircase fails.
- Fortify: `ensureOnSurface()` at buildWall start — calls staircase escape to get bot to surface on resume from stuck positions.
- Fortify: stuck-position detection in place loops — if bot hasn't moved for 5 consecutive block attempts, breaks the pass and escapes.
- Fortify: place phase also sorts by locality segment (8-block clusters) before build priority, keeping the bot in one area before moving on.
- Fortify: increase place-phase bail-out tolerance from 2 to 3 consecutive no-progress passes; increase MAX_PASSES_PER_EDGE from 4 to 6.
- Fortify: major geometry overhaul — full cross-section now includes wall + stone brick slab top + inner cliff face + spider-proof overhang + 3-wide/3-deep moat with cobblestone floor + 2-block exterior clearance zone.
- Fortify: village detection improved — POI scan step reduced to 1 (was 2), structure scan step reduced to 2 (was 3); scan range expanded from 15 to 25 blocks beyond POI bounding box; vertical ranges widened to catch taller structures.
- Fortify: ~40 new village block types added to detection (dark oak, acacia, mangrove wood; stone bricks, bricks, mossy variants; terracotta, wool, hay blocks, bookshelves, iron bars, ladders) — prevents buildings from being left outside the wall.
- Fortify: structure exclusion zone — detected village buildings are padded by 2 blocks and wall/moat/tower blocks at those XZ positions are skipped, creating natural gaps instead of building through houses.
- Fortify: build execution split into dig + place phases — moat ditch and exterior clearance are dug first (top-down), then wall/floor/face/overhang/slab blocks are placed in priority order.
- Fortify: gatehouse now generates a walkable bridge across the moat (MOAT_FLOOR blocks at terrain level under the gate gap).
- Fortify: tower caps changed from TOWER_CAP to WALL_TOP_SLAB (stone brick slab), matching new wall top profile.
- Fortify: new material fallback lists for slabs (stone brick slab → cobblestone slab → stone slab → cobblestone → dirt) and cobblestone (cobblestone → cobbled deepslate → stone → dirt).
- Fortify: tryPlaceBlock now returns success for air target state (dig-phase blocks handled separately).
- Fortify: digBlock helper uses MiningTool.mineBlock with 10s timeout, skips bedrock/doors/beds/unbreakable blocks.
- Fortify: visualizer adds moat (dark blue 0x0066CC), overhang (purple 0x9933FF), and clearance (light red 0xFF3333) particle colors.
- Fortify: guide text updated with moat/defense description and new particle color legend.
- Fortify: fix broken completion tracking — edges now require 85% placement ratio (not just 1+ block) before being marked complete; final wall completion verified with world scan.
- Fortify: `resume` on a complete wall now suggests `patch` instead of silently refusing.
- Fortify: `patch` mode now shows per-edge completion stats with color coding (green/yellow/red), distinguishes never-built vs damaged edges, and prioritizes foundations before upper blocks.
- Fortify: `list` shows overall completion percentage from per-edge tracking data.
- Fortify: add `status <name>` subcommand — shows per-type and per-edge breakdown + spawns coloured ground-level particles (orange=towers, blue=walls, gold=gatehouse, red=missing, green=hull).
- Fortify: `dry_run` now spawns ground footprint particles alongside the text summary.
- Fortify: add overlap detection — new builds check existing walls for hull overlap (>30% blocks with warning, >5% warns and continues).
- Fortify: add `merge <name>` subcommand — combines current village hull with named wall, saves unified layout, counts existing blocks as already placed.
- Fortify: add hull geometry helpers (SAT overlap, point-in-hull, overlap percentage, hull merge) to VillageFortificationLayoutService.
- Fortify: new FortificationVisualizerService for server-side DustParticleEffect visualization at ground level.
- MovementService: throttle nudgeToward WARN logs to once per 10s per label prefix; repeated failures downgraded to DEBUG.
- Backlog hygiene: archived 23 completed checklist items out of `TODO.md` so it now tracks pending work only. Completed references include return-base build fix, ascent/suffocation/task-lifecycle/drop handling fixes, protected-zone persistence, fishing skill + sleep integration, follow-distance command, follow/come/combat/mining verification passes, and debug/equip quality-of-life commands.
- Dialogue: add missing `bot.line.*` sound entries for ambient/dark/wildlife/lost/found lines so code-triggered events play.
- Dialogue: add follow-adventure banter when following far from base/recent bed; track last-sleep timestamps for "recent bed" checks.
- Drop sweep: treat inventory full only when there are zero empty slots; attempt bundle packing before terminating.
- Drop sweep: resolve a commander/follow target for bundle crafting so recipes can be validated against the player's history.
- Bundles: align crafting inputs with 1.21.10 recipe (leather + string) and allow rabbit hide -> leather fallback.
- Drop sweep: if bundling and chest storage fail, drop a cheap stack to free a slot and retry bundle packing.
- Drop sweep: place a crafting table from inventory before dropping stacks when bundling needs a station.
- Bundles: pack full stacks that cost the least bundle occupancy first, then fill with low-occupancy items.
- Bundles: add bundle-craft diagnostics for missing rabbit hide/string and recipe lockouts.
- Mount persistence: secure leashed mounts on disconnect/shutdown and skip fuzzy restore matching when a bot was not mounted.
- Woodcut: reuse return-to-base stuck escape checks while approaching tree bases to recover from overhang stalls.
- ReturnBaseStuckService: woodcut profile now triggers escape checks ~3x sooner and tries pillar escape first.
- ReturnBaseStuckService: stuck timing now scales with real elapsed time, and pillar attempts switch to mine-to-surface when overhead blocks are detected (woodcut pillars wait ~4s of no progress).
- Chest offload: auto-deposits now protect cooked foods, torches, leads, compass/clock items, and arrows across chest offload flows.
- Crafting: record per-world crafting history for the commander when bots craft items.
- ReturnBaseStuckService: move debug logging to after variable initialization so builds succeed post-revert.
- Woodcut: tighter unreachable-log cap (4 skips), wider drop sweep sized to the job footprint, and confirmed sapling replant stays on; aborts still trigger a sweep.
- Woodcut (standalone): defaults to a small target count and stops at sunset with a clear chat message (internal woodcut calls are unaffected).
- Chest use: woodcut deposits via `ChestStoreService` (stand-candidate + door-aware); if nearby chests are full/unusable it will place a new chest near the bot and announce coordinates.
- Chest use: added ChestStoreService debug logging for deposit/movement flow (thread, world, stand candidates, and interact failures) to diagnose crafting offload failures.
- ChestStoreService: fix build by using `getEntityWorld()` for bot world logging.
- Crafting: force offload retry after failed insert so chest deposit runs even when the initial room check is optimistic.
- Chest debug logging now routes through DebugToggleService so it can be toggled with the global debug flag.
- Permissions: align LeveledPermissionPredicate stub field types with runtime to avoid OWNERS NoSuchFieldError on launch.
- Networking: restore non-null codecs for SaveAPIKey/SaveCustomProvider payloads to prevent Fabric payload registry NPE at init.
- LLM: skip BERT/LIDSNet loading when DJL is missing to avoid ai.djl.Model classpath crashes at startup.
- Fake players: use an EmbeddedChannel-backed SERVERBOUND FakeClientConnection to avoid local connection refusals and side mismatches on spawn.
- Hunt UI: move the huntable list below the control buttons to prevent header/controls overlap.
- UI: stop pausing the game while the topics/crafting/cooking/hunting screens are open.
- Hunt debugging: add detailed logging around task starts, blocked slots, and hunt execution flow.
- Hunt debugging: add temporary stdout markers for skill/task entry so logs capture flow even if logger filters hide INFO.
- Hunt debugging: write skill flow markers to `config/frens/skill_debug.log`.
- Hunt debugging: add pre-context/pre-run markers and log SkillManager static init/register steps.
- Skills: build the skill execution context on the server thread before dispatching to the executor to avoid worker-thread stalls.
- Skills: guard shared state lookup so missing ollama4j classes can't crash `/bot skill`.
- Hunting: drop-sweep during hunts now runs much more frequently to avoid leaving loot behind.
- Riding: detach fence-held leads before mounting with a lightweight lead pickup approach (no heavy sweep), retry mount lookup over several ticks with expanding radius, and secure the bot's last mount on rejoin, disconnect, or bot removal with a lead/fence tie-in to prevent wandering (no respawns). Fix mount flagging so rejoin-secure works when dismounting on logout, stop killing bots on disconnect so mounts persist like player mounts, ensure shutdown save/secure uses the live player list so bots are still captured on integrated-server stop, and add pre-shutdown persistence/logging at real-player disconnect to capture bots before the server stops.
- Inventory: add bundle crafting and bundle-packing support to free slots when inventories fill up.
- Tools: auto-craft torches/shovels/pickaxes during mining tasks when supplies run out (gated by crafting history).
- Crafting: add torch crafting support for the auto-provision flow.
- Tool provisioning: expand auto-craft coverage to axes/hoes/swords/shears/fishing rods/ladders/beds/chests/furnaces/crafting tables/doors/sticks/planks/buckets/shields.
- Crafting: add door crafting support with plank-type selection.
- Riding: add ride-sync service for vanilla rideables with commander mirroring and mount feeding.
- Hobbies: add flower-picking and animal-feeding hobbies; hobby hunting now caps to one kill and can hang out afterward.
- Crafting: add saddle + control-stick crafting for ride sync.
- Hunting: run a drop-sweep after each kill and before stopping on depopulation safeguards.
- Hunting: run a final wider-radius drop-sweep after hunts to catch nearby leftovers.
- Mining safety: on lava detection, bots try water buckets first, then plug with rock/gravel/dirt, back off to a 2-block buffer, and retreat again if lava closes in (alerts preserved).
- Riding: steer boat follow with paddle-animated velocity nudges (no direct position snaps) so rowing is visible and movement stays responsive.
- Riding: drive all boats via AbstractBoatEntity inputs so chest boats move in sync with paddling.
- Riding: increase boat-follow speed targets and stuck-assist step to better match natural player pace.
- Riding: scale boat-follow speed by distance so far targets allow max speed catch-up.
- Riding: switch boat-follow speed scaling to an exponential curve for faster catch-up at long range.
- Riding: match boat-follow target speed to commander velocity (with a small catch-up boost).
- Riding: avoid joining commander boats already carrying another passenger and allow mounting boats with mob-only passengers.
- Riding: prefer empty nearby boats and fall back to mob-occupied boats to prevent stuck re-approach loops.
- Riding: skip joining the commander boat when another usable boat is nearby.
- Riding: ignore submerged boats as mount candidates to avoid chasing sunken boats.
- Riding: if the commander destroys their boat on landfall, bots dismount and break their own boat to collect it.
- Riding: queue boat-breaks for dismounted bots after the commander's boat is destroyed; fallback boat placement uses interactBlock on water.
- Combat: ignore underwater hostiles unless they have an active player target.
- Riding: widen bot boat-break detection radius and track last bot boat continuously; add interactItem fallback for boat placement.
- Riding: require bots to surface before placing boats when submerged.
- Riding: track pending boat placements and retry mounting a newly placed boat instead of re-placing every tick.
- Riding: avoid force-mounting boats with existing passengers to prevent seat overlap.
- Riding: add boat placement diagnostics and allow placing from offhand; log no-boat/no-water/failed cases.
- Riding: honor hotbar locks when selecting slots so combat loops don't override boat placement items.
- Riding: tighten boat item detection to avoid treating bows as boats when selecting placement items.
- Riding: nudge toward the water target after failed boat placement attempts to improve follow-up retries.
- Git: ignore local status/audio-need markdown notes.
- Hunt UX: announce hunt start target/count so manual requests give immediate feedback.
- Smelting: when the furnace already has input, auto-cook or matching cook requests now top it up from inventory and refill matching fuel (direct inventory scan, item-only match); auto fuel selection now prefers leaf litter/leaves/saplings and ranks logs/planks/sticks/charcoal/coal blocks above coal.
- Woodcut: inventory-full handling now attempts fallback chest deposits for non-tool woodcut drops and uses placed chests for both primary and fallback offloads.
- Woodcut cleanup: drop sweep radius now reflects the full area traversed during cleanup, and scaffold placements are recorded for later cleanup passes.
- Woodcut: collect-dirt fallback now uses a square 6-block radius to keep dig sites tidy.
- Woodcut subskills: collect-dirt can attempt ChestStoreService offloads when inventory fills during woodcut.
- Collect dirt: square quarry mode is now the default (radius remains configurable), and the Topics menu includes a Collect Dirt shortcut.
- Drop sweep: if inventory is full, the sweep now attempts ChestStoreService deposits (and chest placement) before aborting.
- Topics UI: added Stop/Resume/Drop Sweep entries with disabled states when not applicable.
- Client: stop keybind now defaults to `\\` and stops the looked-at bot.
- Commands: `/bot skill woodcutting ...` now aliases to `woodcut` (reduces “unknown skill” typos).
- Commands: manual skills now interrupt ambient hobbies so `/bot skill ...` can start immediately.
- TaskService: if a task slot is stale (non-running state or dead thread), replace it so new skills can start.
- Store: `/bot store deposit|withdraw` now supports no-arg defaults; when not looking at a chest it will prefer the last chest it placed, otherwise use a nearby chest (or place one for deposits). Default deposit now “matches what’s already in the chest” (e.g., chest has oak logs ⇒ deposit oak logs); if the chest is empty it falls back to construction materials + common mob drops. Default deposit also includes construction/clutter items so it can be used as a general stash when you’re trying to make space. Use item filter `all`/`*` to match everything.
- Crafting: crafting-table placement is now more robust (avoids fluids and blocked tiles, and retries nearby placements) so “logs → planks → place table → chest” works more reliably for storage automation.
- Shelter: added `/bot shelter hovel <alias?>` to build a quick dirt/cobble hovel (roofed, torches, fills gaps, gathers dirt if short).
- Hunting: new `hunt` skill with mob catalog, unlock-on-kill gating, hunting grounds based on bot/bases/beds, cooking fallback, and drop/overflow handling. Topics menu adds Hunting popout and an auto-hunt-when-starving toggle.
- Networking: standardized network payload packages to `net.wcfcarolina13.network` to eliminate mixed-case imports.
- Shelter: hovel now prefers staying near the build site for material gathering (shallow local dig only; no auto descent/stripmine), and can detect/complete nearby pre-existing shelter footprints.
- Shelter: hovel siting rejects unsupported/water-edge footprints (won’t try to build walls over shoreline overhangs), and runs a final interior leveling + gap patch pass right before drop-sweeping.
- Shelter: hovel placement now attempts a “double back and exit” when reach-moves fail inside the footprint (targets doorway/perimeter openings instead of endlessly pushing into walls).
- Placement: block placement now clicks a real supporting block face (prevents “phantom place” attempts where the support check passed but the click target was air).
- Shelter: hovel no longer misclassifies shallow water/riverbeds as “underground”; if the bot is in water it steps onto nearby dry land first, and “underground” logic is now gated on sky visibility. Also added a lightweight recenter-to-build-site when the bot is dragged far away (e.g., after combat).
- Shelter: hovel no longer treats “inside an unfinished/roofed hovel” as underground; if the bot is under a roof it will try to relocate to a nearby sky-visible, dry opening before attempting any surface-escape mining.
- Shelter: improved “indoors vs underground” detection by treating common build-material roof planes overhead as being indoors (prevents false ascent when the bot is placed inside a completed/roofed hovel).
- Shelter: while building a hovel, the bot now yields briefly to nearby hostiles and triggers immediate defense (reduces “task movement fighting combat movement” hopping).
- Shelter: door gap/walls are now aligned to the bot’s walking level (prevents “sealed in” hovels where the doorway existed only at head-height), and a final `ensureDoorwayOpen` runs right before drop-sweeping.
- Shelter: hovel stage messages now route to the command issuer’s chat (so build stages are visible in-game) and redundant door/interior chat calls were removed to reduce spam.
- Shelter: hovel base-perimeter patch now re-enters via the doorway and avoids placing support blocks on carpet/snow cover; exterior ring movement adds a short hop fallback for uneven terrain.
- Shelter: foundation beam phase now announces start, emits a slow-progress heartbeat, and reports a timeout corner before continuing.
- Shelter: hovel leveling now clears a one-block buffer ring for safer perimeter walking; roof-perimeter access uses doorway/perimeter routing and wall finishing sweep was disabled to reduce redundant passes.
- Shelter: exterior patch starts from the closest ring waypoint; interior amenities/leveling now prioritize nearest targets, and outside destinations force perimeter waypoint routing before direct moves.
- Shelter: leveling now fills perimeter/base holes and trims buffer-ring height; doorway routing now always stages at the doorfront and then uses a straight-line step inside/outside before continuing.
- Shelter: roof perimeter exit now requires standing on the pillar top before teardown to avoid breaking the scaffold while still on the roof.
- Shelter: leveling now clears replaceable cover (grass/flowers/carpets) before filling holes; roof pillar leftovers are cleared at the base after the roof phase, and door placement now reports failures.
- Shelter: disabled diagonal scaffold corner patch; roof perimeter no longer tries to return to the pillar before teardown, and doorway placement now steps one block away before placing doors.
- Shelter: roof pillar cleanup now snapshots pending pillars to avoid ConcurrentModificationException during perimeter cleanup passes.
- Shelter: roof pillar cleanup now approaches via nearby standable cells and tries clearing from ring positions; door placement avoids standing in the doorway.
- Shelter: removed wall corner diagonal scaffold pass, initial roof build sweep, finishing sweep, and base perimeter patch stages to reduce stage bloat and pathing stalls; foundation beam retries now stop early when no progress is detected.
- Shelter: roof exit can snap-teleport to the access pillar for teardown, and door placement now prefers a farther outside stance to avoid blocking itself.
- Shelter: interior cleanup no longer forces a roof-pillar cleanup run from inside; roof pass now snap-teleports to the pillar top only when the bot is already on the roof plane to avoid disruptive falls.
- Shelter: hovel build now uses a perimeter ring wall pass plus a roof serpentine walk to place reachable blocks under survival reach/LOS rules, and restored shelter option parsing/placement helpers to keep burrow stripmine stable.
- Shelter: leveling keeps the foot layer clear (no fill) after aligning wall placement to the ground so walls use natural support blocks.
- Shelter: align wall base and doorway to foot level, and lower the roof plane to match so wall placements have proper ground support; add ring-pass placement stats for diagnosing zero-placement runs.
- Shelter: move wall placement back to start at y+1 with a y+2 door gap, lift the roof plane to match wallHeight, and pick roof pillar bases from ground Y so roof building can start reliably.
- Shelter: allow adjacent placements to bypass strict LOS checks when a solid support is present (prevents false reach failures on wall and roof placements while staying within survival reach).
- Shelter: ring wall pass now requires standable ring tiles and uses a direct ring-step move to get adjacent before attempting placements, reducing long-range LOS failures.
- Shelter: added detailed wall/roof placement logging plus scaffold teardown/step diagnostics so we can trace exactly why each target fails during the perimeter pass.
- Placement: block placement now requires line-of-sight to the supporting click block (prevents placing blocks “through” other blocks, e.g., placing above the roof while still inside).
- Placement: placement hit results are now raycast-derived (must hit the intended support block *and face*), preventing “clicking the top face from below” exploits that allowed through-roof placements.
- Shelter: doorway viability/clearing now uses the same floor-level door coordinates as the build logic and ensures the bot walks into reach before carving the opening.
- Shelter: hovel exterior routing now exits via the doorway using path planning, stays on a 1-block perimeter ring, avoids reusing scaffold bases, and clears interior scaffolds up to the roof plane during final leveling.
- Shelter: add detailed hovel leveling + skill exit logging to diagnose early aborts during site clearing.
- Shelter: fix stack overflow in hovel movement pathing caused by recursive `pathMove` calls.
- Shelter: foundation beam checks no longer abort builds; missing beam segments are cached and scaffold-patched after wall passes.
- Shelter: add stage-aware chat messaging and doorway-based interior re-entry for final cleanup/amenities; delay scaffold-column fills until descent completes.
- Shelter: `ensureDoorwayOpen` now reliably carves a 2×2 passage (inside + outside), can carve through most breakable blocks (not just “soft”/roof materials), and logs doorway carve attempts for easier debugging.
- Shelter: timeboxed/attempt-capped “nearby materials gather” to avoid long pathing loops that prevent the hovel build (and doorway carving) from ever starting; added gather progress logging.
- Shelter: hovel now fills the support layer and places the first wall layers using a “station-based” placement pass (few movement steps, many placements per stop) to reduce per-block pathfinding churn and incomplete low walls on uneven terrain.
- Shelter: doorway selection now re-evaluates all sides at build time (lowest carve cost + safe outside step) and carves from an interior approach tile, so the bot can recover even if it forgot the original doorway location.
- Shelter: `hovel2` now runs the leveling pass and then builds walls + roof primarily from a central scaffold tower perch (always crouching), using direct-within-reach placements to minimize path churn; for larger radii it may add a few auxiliary perches, then falls back to a single gap-patch pass if anything remains unreachable.
- Shelter: improved `pillarUp` reliability (clears soft headroom, allows replaceables in the foot cell, waits for Y to actually increase) and added clearer logs/chat for the `hovel2` tower build.
- Shelter: fixed `hovel2` tower perch falling by enforcing sneaking via `BotActions.sneak(...)` (server-thread safe) instead of direct `setSneaking(...)`, and reasserting sneak after moves and before placement passes.
- Shelter: added `SneakLockService` and used it during `hovel2` builds to prevent movement/RL/stop routines from turning sneaking off mid-build (bots should stay crouched on the tower perch until completion).
- Shelter: fixed `hovel2` tower reach failures by building walls layer-by-layer while ascending the center tower (vertical distance counts toward reach, so a single top perch cannot place lower wall layers).
- Shelter: `hovel2` tower build now retries each wall layer/roof pass until all *in-reach* missing targets are confirmed placed (or progress stalls), instead of ascending immediately after a single sweep.
- Shelter: `hovel2` roof placement from the tower now fills ring-by-ring (outer → inner) and places farthest targets first to avoid line-of-sight blocking that disproportionately caused missed corner blocks.
- Shelter: build-site moves now fall back to `GoTo.goTo` routefinding if the short-range MovementService stepper gets stuck (reduces “infinite jump/step on one block” stalls when approaching build stations).
- Shelter: hovel siting now avoids shorelines and farms (rejects candidates with nearby fluids or farmland/crops), and shelter clearing refuses to break farmland/crops (prevents griefing farm plots).
- Shelter: `hovel2` now attempts corner-focused auxiliary pillars (woodcut-style pillar + teardown) before the general patch pass to finish tricky corner/roof placements without wandering.
- Shelter: reach-move candidate stepping now falls back to `GoTo.goTo` to reduce “stuck stepping” when moving between stand positions during placement.
- Shelter: `hovel2` now tears down its central tower scaffolding after building, and caps the roof-center hole (it may need to drop 1 block to avoid the placement bounding-box intersection check).
- Shelter: added light placement throttling during high-volume roof/wall passes to reduce server-thread task flooding (helps mitigate build-related tick lag).
- Shelter: `hovel2` now over-collects build blocks (3× estimate) and avoids mining near/under the active footprint while gathering (prevents “gathering changed the math” placement issues).
- Shelter: `hovel2` no longer crafts ladders during tower builds (prevents wandering to far-away crafting tables).
- Shelter: improved scaffolding cleanup by using a safe teardown that never breaks roof/wall cells, plus strict vertical pillaring for `hovel2` tower/aux pillars to avoid messy exterior offsets.
- Shelter: added a final roof-center cap attempt after all teardown/patching, to handle cases where the cap block was previously removed during cleanup.
- Shelter: add persistent IN_WALL / clipping emergency regroup during hovel building (abort + come-to-commander) to avoid repeated suffocation loops.
- Shelter: hovel builder now checks abort requests between major phases so `/bot stop` halts promptly (and avoids emitting misleading timeout/completion stage messages after abort).
- Movement: obstruction mining during movement stalls now also considers the bot's own head/feet cells while clipping, and no longer requires being fully grounded to trigger.
- Shelter: `CraftingHelper` now tells chat when it’s heading to a nearby crafting table (coords) so “ran off” trips are understandable.
- Shelter: `hovel2` now runs a final drop sweep around the build site.
- Shelter: shelter material gathering now prefers a descent-based dig at a safe distance first (then falls back to a small surface gather), and avoids mining near the active build footprint to prevent corner holes/terrain distortion.
- Shelter: `hovel2` final roof cap pass now fills any remaining roof holes from a temporary center pillar instead of only trying the single center cell.
- Shelter: `hovel2` roof cap now avoids player/target collision by capping from a lower foot Y (and falls back to small auxiliary cap pillars near remaining holes).
- Shelter: scaffolding support blocks placed for pillaring are now tracked and included in teardown; teardown now ensures reach before mining so fewer “mystery” exterior columns remain.
- Shelter: corner pillar passes now prefer exterior bases and target full adjacent wall spans, with build blocks allowed for scaffolding and teardown covering them.
- Shelter: pillar-up attempts now retry jump/placement per step to reduce stalled scaffold climbs.
- Drop sweep: disabled MovementService snap-forward during drop sweeps to avoid visible “teleport” snaps during survival-style cleanup.
- Shelter: added lightweight `hovel2` stage timing logs (leveling / tower build / cap / drop sweep) to help identify build-related lag.
- Mining: `MiningTool.mineBlock(...)` now uses a shared scheduled executor (instead of spinning up a new thread/executor per block) to reduce tick spikes in heavily modded instances.
- Mining (descent): stair-descent mining now retries transient failures/timeouts (falling blocks, slow breaks) and pauses with clearer chat + coordinates instead of hard-aborting without context.
- Mining (descent): avoid canceling `MiningTool` futures on slow breaks; align wait timeouts to MiningTool’s failsafe and mark the bot as “in ascent mode” during descent so burial-rescue doesn’t fight the intentional 1×2 stairwell.
- Mining hazards: lava detection now treats “lava-like” modded fluids (ids containing `lava`/`molten`) as lava hazards so the bot will announce and pause appropriately.
- Chat: delayed multi-part chat now uses a shared scheduler (no per-message `new Thread(...)`), reducing thread churn during heavy automation.
- Emotes: ambient bot emotes now run only when the bot is truly `IDLE` (never during follow/guard/patrol or active skills), and can optionally do a brief crouch “ack” emote in addition to waving.
- UI: bot inventory screen shift-click routing no longer dumps arbitrary items into armor/offhand; only armor auto-fills armor slots and shields auto-fill offhand, everything else prefers hotbar then main.
- Shelter: `hovel2` tower pillaring uses the same underfoot placement strategy as woodcut pillaring (more reliable on uneven terrain/snow), with a fallback to the regular `pillarUp` when strict tower pillaring fails.
- Shelter: `hovel2` corner completion now prefers auxiliary perches inside the footprint to reduce exterior teardown mess; scaffolding teardown uses the full reach helper and keeps sneak asserted until teardown completes.
- Shelter: `hovel2` no longer claims success if wall/roof targets remain missing; it reports an incomplete build with remaining target count.
- Farming: secured irrigation basins on uneven terrain (fills edges/underblocks, cleans stray flow), repair pass now levels plots to farm Y before re-till/plant, and leaves are broken with shears/harmless items (no axe wear).
- Wool skill: peaceful shearing that crafts/equips shears if needed, detects pens vs. wild range (fence-aware search), collects drops, and auto-deposits bulk blocks to nearby chests to keep ≥5 free slots.
- Wool: `/bot stop` now interrupts movement/drop-sweeps immediately; short-range moves avoid full pathfinding to reduce hitching.
- Movement: reduced per-step INFO spam (most movement tracing is now DEBUG) to keep integrated-server stutter down during frequent short moves.
- Movement: improved “stuck under canopy” recovery by prioritizing clearing low headroom leaves (shears/harmless only), speeding up escape from dense forests.
- Fishing: added a `/bot fish` skill that can craft a rod (3 sticks + 2 string), move to the shore, cast, wait for bites, and reel in fish while tracking successes.
- Crafting: added bed crafting (selects a craftable color based on available matching wool) and enforced crafting-table requirements for 3x3 recipes; will place/craft a table, remember/seek the last known table, and can pull basic inputs from nearby chests.
- Commands: added `/bot sleep <alias|all|default>` which finds/places/crafts a bed and attempts to sleep (survival reach enforced; no teleport).
- Movement: bots can open/close doors while pathing (opens when blocked, closes after passing with a small delay).
- Follow: reduced snap-teleport frequency; bots walk much farther before teleport catch-up is even considered.
- Crafting: `/bot craft` now runs asynchronously (like skills) so moving to tables/chests no longer blocks the server tick thread; crafting-table searches avoid chunk-loading scans.
- Crash fix: move bot item use/placement/hotbar changes and sheep shearing interactions onto the server thread to avoid `LegacyRandomSource` multi-thread crashes (1.21.10).
- Follow/Movement: follow movement planning now enables sprint on mid-range walks, and door opening can trigger during close-pursuit/direct-walk stalls (not just during path-segment walking).
- Come: when teleport is disabled, `/bot come` now uses follow-walk replanning instead of a one-shot direct-path attempt (reduces “could not reach you: direct: walk blocked” false failures).
- Rescue: spawn-in-block checks no longer mine doors/trapdoors; they attempt interaction and nudge out to avoid griefing player builds.
- Combat: close-range attacks prefer melee weapons over bows/crossbows when available.
- Patrol: patrol target selection now uses a bounded cooldown (less “standing still” variance when nothing else is happening).
- Commands: `/bot defend on|off` and `/bot defend nearby on|off` added as shorthands for the existing defend syntax.
- Come: `/bot come` now walks to a fixed snapshot of the commander’s position at command time (doesn’t chase a moving target) and won’t “complete early” when directly below/above the destination.
- Come: when stuck trying to reach a fixed goal (e.g., tunnel below the target), the bot will auto-attempt short `collect_dirt ascent|descent` or `stripmine` recovery steps, then resume coming to the destination.
- Come: improved “stuck” detection to use best-distance tracking (robust to jitter) and trigger vertical recovery sooner when the goal is above/below without line-of-sight.
- Commands: added `/bot regroup` as a clearer alias for `/bot come` (walk to the commander’s last location rather than live-follow).
- Come: removed the old “blocked, run `/bot resume` to dig” staging path (now superseded by automatic recovery skills).
- Refactor: removed obsolete Spartan mode; confined/no-escape handling now relies on environment checks plus the stuck/escape routines.
- Refactor: extracted follow debug-log throttling to `FollowDebugService` (no behavior change).
- Refactor: extracted follow/come state maps to `src/main/java/net/wcfcarolina13/GameAI/services/FollowStateService.java` (behavior unchanged; BotEventHandler delegates state storage/reset).
- Refactor: extracted follow waypoint planning to `src/main/java/net/wcfcarolina13/GameAI/services/FollowPlannerService.java` (behavior unchanged; BotEventHandler delegates async planning and waypoint application checks).
- Refactor: extracted follow movement helpers to `src/main/java/net/wcfcarolina13/GameAI/services/FollowMovementService.java` (behavior unchanged; BotEventHandler delegates movement primitives).
- Refactor: extracted stuck tracking + enclosure snapshot to `src/main/java/net/wcfcarolina13/GameAI/services/BotStuckService.java` (now per-bot; no change for single-bot play).
- Refactor: extracted RL action execution to `src/main/java/net/wcfcarolina13/GameAI/services/BotRLActionService.java` (mechanical move; behavior unchanged).
- Refactor: extracted RL persistence throttling to `src/main/java/net/wcfcarolina13/GameAI/services/BotRLPersistenceThrottleService.java` (no behavior change).
- Commands: `/bot skill` now accepts bot targets anywhere in the args (e.g., `ascend Jake 5`) and supports `ascend`/`descend` synonyms (defaulting to 5 blocks when no number is given).
- UX: `/bot inventory` summary now includes bot stats (health/food/XP) so XP/level persistence is visible.
- UX: `/bot open <alias>` is now distance/dimension independent for ops/admins; the GUI now shows bot XP progress/total XP alongside level.
- Shelter: `/bot shelter hovel` now selects a nearby safer/flatter build site, uses terrain walls when available to reduce block needs, supports placing a door/chest when available, and can auto-gather materials (use `ask|wait|manual` to pause and require `/bot resume <alias>`).
- Shelter: fixed hovel placement reach logic (avoid “standable but still out of reach” spots) and will report an incomplete build instead of claiming success when most placements fail.
- Shelter: hovel placement now tries multiple nearby stand positions for each block and places the roof from perimeter → center for reliable support (reduces “swing but no block placed” frustration).
- Shelter: hovel walls are now taller (+2 blocks) and will use temporary pillar scaffolds (then mine them back down) to place higher wall layers and roofs more reliably.
- Shelter: hovel now avoids “using trees as walls/roof” by scoring away from vegetation, clearing obstructive leaves/logs at the build site, and treating leaf/log blocks in the shell/roof as gaps to replace with proper build blocks.
- Shelter: hovel resource gathering now prefers a cleaner “descent 6 + stripmine until enough blocks + climb back the same way” loop instead of roaming `collect_dirt`, and it will place/find a nearby chest to dump common junk when inventory fills during building.
- Shelter: if commanded to build while underground and no safe build site is reachable, the bot will attempt to reach the surface (ascent with short stripmine retries) and then retry the hovel plan.
- Shelter: roof completion now uses perimeter→center ordering during scaffold placement, and scaffold teardown tracks the actual placed positions (reduces leftover scaffold columns).
- Shelter: if the build site is too dense with trees, the bot will run a small woodcut pass to clear space before building.
- Survival: enforce reach limits for mining and block placement to prevent “remote” digging/placing (no more surface trees breaking while the bot is deep underground).
- CollectDirt: ascent/descent only rewind to a stored pause position when `/bot resume` was used; fresh commands no longer auto-return to old pause coordinates.
- Shelter: when commanded to build underground, the bot now proactively escapes to the surface before selecting a hovel site; if it has a clear vertical shaft and scaffold blocks, it will attempt a woodcut-style pillar climb first.
- Shelter: surface detection now uses `MOTION_BLOCKING_NO_LEAVES` and will refuse to claim success/build a surface hovel unless it actually reached near-surface Y.
- CollectDirt: ascent/descent hazard scans no longer treat torches as blocking hazards (prevents false failures like “Cannot break placed torches” during surface escape).
- Shelter: surface escape now terminates as soon as the bot reaches near-surface height at its current X/Z (prevents long “walk forward then backtrack” drift during ascent).
- Shelter: floor logic now works on the support layer under the bot (fills holes/levels uneven ground) instead of placing a new floor in the standable air layer.
- Shelter: shaft pillaring no longer seals the bot by placing into the jumped-into block; it places underfoot even if `blockPos` advances mid-jump.
- Shelter: pillar escape no longer “caps” the shaft by placing above the bot when the intended underfoot cell is occupied; it aborts and falls back to ascent/stripmine instead.
- Shelter: roof/wall scaffold placement now clears log blocks (when not near player builds) the same way the normal placement path does, reducing “almost closed roof” failures from tree trunks.
- Commands: `/bot open` supports “last targeted bot” (no alias) and `/bot skill` now remembers the active-bot fallback target so follow/open/etc can default correctly after skill commands like `shelter hovel`.
- Follow: follow no longer runs blocking movement/path loops on the server thread; it now sprints when >2 blocks away and uses a wolf-style teleport catch-up only when far/stuck (with cooldown).
- Storage: prevent “remote” chest deposits/withdrawals through doors/walls by requiring survival-like reach + line-of-sight checks before moving items.
- Safety: rescue-from-burial/suffocation escape no longer mines doors; it will attempt to open them instead (prevents bots breaking enclosure doors).
- Movement: doors opened during movement now reliably close after the bot passes through (retry-based “close behind you” behavior).
- Crafting: crafting-table station approach now requires true interactability (reach + line-of-sight) and will open a blocking door when needed.
- Follow: follow now proactively raycasts for a blocking door within ~5 blocks and opens it (not just “stagnant” detection).
- Doors: door interact checks now probe door edges (doors are thin, center-raycasts often miss), and stuck escape will open doors instead of breaking them.
- Doors: door closing now retries longer and will close once the bot is safely away even if the inferred travel direction was wrong; close attempts emit `Door debug:` logs.
- Storage: `/bot store deposit|withdraw` now walks to the chest (opening doors en route) and only transfers once the bot has true reach + line-of-sight.
- Movement: added doorway traversal assist when a door is open but the bot is stuck on the threshold; follow and movement pursuit will “step through” instead of giving up.
- UX: bots now explicitly say they can’t open iron doors without redstone.
- Storage: `/bot store deposit|withdraw` now runs movement asynchronously (so ticks can advance) and includes a “step through doorway” assist when exiting enclosures.
- Pathfinding: wooden doors are treated as passable for planning (bot opens them on approach); iron doors remain blocked unless already open via redstone.
- Movement: `walkDirect` and `walkSegment` now attempt doorway step-through + sidestep recovery before giving up (improves exiting door enclosures for storage/stations).
- Movement: when the goal is “around the corner” and the bot is stuck (common in door enclosures), movement will pick a nearby wooden door that leads closer to the goal and treat it as a sub-goal (approach → open → step through).
- Follow: added non-blocking door sub-goal traversal (approach → open → step through) so follow doesn’t stall at doorways or freeze the server tick with blocking nudges.
- Follow: added bounded, async follow path planning (snapshot-on-thread + plan-off-thread) to navigate multi-door “around the corner” obstacle courses without oscillating back through the same door.
- Follow: path planner now falls back to “exit nearest wooden door” when no path-to-target exists in the bounded snapshot (enclosure escape even if it initially moves away from the commander); added INFO logs for plan start/success.
- Follow: path planning now triggers on stagnation even if `canSee()` is true (fences/doors can allow LoS while still blocking movement), so the bot will choose door/waypoints instead of pushing into the corner.
- Follow: stagnation now also tracks “no block-position change” (distance-to-target can jitter while pinned in a corner), so the reroute-to-door planner reliably fires from enclosure corners.
- Follow: follow now requests an initial bounded path plan immediately on `/bot follow` start (async), so it can exit enclosures even if the commander is not standing at the doorway.
- Follow: “close enough, chill” now requires an unobstructed short raycast to the commander; prevents getting stuck staring through glass/fences when the shortest route requires backing up to a door.
- Follow: enclosure-door sub-goal selection no longer requires immediate distance improvement (around-the-corner routes often start by moving away); replans after crossing doors.
- Follow: when close-but-blocked (fence/glass/wall), follow now proactively selects an exit door sub-goal (throttled) instead of pushing into the barrier until “stagnant” triggers.
- Follow: added throttled INFO logs for follow path planning early-return reasons (cooldown/inflight/same-target/world), so wiring issues show up clearly in `latest.log`.
- Doors/Follow: door “escape/sub-goal” selection no longer requires survival reach (it now checks only unobstructed line-of-sight), so bots can pick their enclosure door even when starting far away in a corner.
- Follow: “blocked route” detection now uses a conservative, throttled collision-probe (not just raycasts) so fence/glass enclosures trigger door escape routing instead of the bot pushing into the corner.
- Follow: follow planning now uses a two-phase approach: goal-inclusive snapshot first, then a bot-centered “escape nearest door” fallback when the correct first move is away from the commander (dweller/stalker-style repathing).
- Follow: improved enclosure/building door navigation by (1) scoring escape-door choices against the commander position (even when the goal is outside the bounded window), (2) increasing the bounded planning window size, (3) preventing “step-through” commits when the door did not actually open, and (4) adding a doorway recovery that uses local sidesteps/backsteps (better for fence/hinge corners), aborting door plans that can’t be unstuck, avoiding “step 1 block beyond the door” overshoots (prevents jamming between adjacent doors), plus throttled `Follow decision:` INFO logs for debugging.
- Stuck rescue: fixed a cooldown bug where “Bot is stuck!” logging updated the same timestamp used to throttle escape nudges, which could prevent movement escape from ever triggering (notably when wedged inside doors).
- Follow: reduced “door magnet” behavior by only considering door subgoals when blocked or lacking LoS, clearing stale waypoints when the commander is close/visible, and adding a short per-door cooldown when a door plan aborts or fails to open (prevents endlessly re-picking the same door).
- Follow: door traversal now prefers standing directly in front/behind doors (based on door facing), triggers “double back” recovery sooner, and no longer marks a door as “crossed” while the bot is still standing inside the door block (fixes a common stuck/oscillation loop).
- Follow: reverted a regression where door traversal could oscillate by dynamically re-picking front/back tiles each tick; door plans are stable again, and doorway recovery now prioritizes an explicit 2–3 block retreat before any micro-nudges (more reliable “double back and try again”).
- Rescue: burial/suffocation rescue no longer treats ladders/vines/scaffolding as “stuck blocks” (prevents rescue from mining ladders during shaft escapes and breaking navigation).
- Shelter: hovel build now detects falling into pits/shafts mid-build and attempts a ladder escape (craft/place ladders + move to an exit) with a pillar-climb fallback; scaffolding pillaring now uses cheap scaffold blocks explicitly, places ladders on pillars when available, and logs scaffold activity.
- Shelter: scaffold stations for upper walls/roof now prefer exterior ring positions (no dependency on an early doorway), place upper walls “one side at a time”, and bias roof placement per-side to reduce out-of-reach/blocked attempts; will also place an exterior ladder column when ladders are available.
- Shelter: hovel siting no longer treats water blocks as standable; if no valid site is found in the initial scan it will do a wider “dry/flat” fallback search instead of defaulting to the current (possibly waterlogged) position.
- Shelter: hovel now stabilizes the support layer for the footprint plus a 1-block outer ring before any other work, reducing undercut “nooks” where the bot gets stuck; scaffolding phase crafts a small set of ladders when possible and sneaks while placing upper walls/roof.
- Shelter: hovel upper build now prefers a roof-edge perimeter pass (move to top-edge waypoints, sneak, place walls/roof within reach) and will fall back to building from the roof edge even if it can’t path out via the doorway.
- Shelter: added experimental `hovel2` mode; initial step selects a build site and levels/stabilizes the footprint (fills support layer + clears foot-level obstructions) to reduce “stuck under the build” nooks before any building starts.
- Shelter: `hovel2` leveling now runs a second verification sweep (re-fill supports + re-clear foot-level obstructions) to catch a few missed blocks on the first pass.
- Shelter: `hovel2` leveling now also cuts down raised blocks above the target floor and fills shallow undercuts (up to 2 blocks deep) below the intended ground plane (Y=floor-1), preventing leftover “grass islands” and holes like in the leveling screenshots.
- Commands: `/bot shelter hovel2` wired as a first-class subcommand (matches `hovel`/`burrow` syntax, including optional target and options).
- Follow/Training: paused the RL training loop while the bot is in a player-commanded mode (FOLLOW/skills/etc) to prevent training actions from canceling follow movement, and added INFO logging to show who invoked `/bot stop` when follow is unexpectedly canceled.
- Training/Performance: throttled Q-table + epsilon persistence (reduces server hitching from frequent disk writes during RL steps).
- Training/Performance: added `/bot rl on|off` hard toggle to disable the RL loop entirely (prevents background “thinking” during normal play/follow).
- Follow: cancel active door subgoals once the commander is close/visible and directly reachable, reducing “linger by the door” after the commander moves away.
- Follow: reduce long-range “door distraction” by dropping stale waypoints when the commander moves far away and suppressing door-corner subgoals when the commander is far outside the bounded planning window.
- Follow: after the bot closes a door behind itself, temporarily avoid re-opening that same door while the commander is far away (reduces “loop back to the nearest door” behavior).
- Follow: reduced post-door “door fixation” by gating adjacent/door-ray interactions to close-range/stuck situations and avoiding doors near the last-crossed doorway when the commander is far away.
- Follow: avoid door blocks as follow waypoints (expand to doorway-adjacent approach/step tiles) and prevent burial-rescue from fighting doorway traversal when the bot is inside door blocks without suffocation damage.
- Follow: when the commander is far away and the bot is not enclosed, drop stale follow waypoints/door subtasks so it doesn’t “orbit” a doorway instead of pursuing the commander.
- Follow/Debug: added throttled INFO `Follow status:` logs (distance, LoS, blocked, waypoints, door plan) to diagnose long-range “door fixation” issues from `latest.log`.
- Follow: added a long-range override that clears door plans/waypoints when the commander is visible far outside the structure so the bot heads straight for you instead of circling the doors.
- Follow: trigger sprinting toward waypoints whenever the commander is far (so sprint doesn’t stall near the doorway), and skip “door-plan” obsession when an already-open door sits between the bot and you.
- Follow: detect when the bot or commander are inside sealed rooms (closed doors are the only exits), keep/retain the door plan, and log that reason instead of dropping it when the route momentarily clears.
- Follow: fixed follow/door timer overflow caused by `Long.MIN_VALUE` timestamp sentinels (cooldowns/avoidance could become permanently “stuck”), restoring replanning and door escape behavior.
- Follow: personal-space stop now applies even when following waypoints, preventing the bot from constantly colliding with/jumping onto the commander.
- Follow: follow spacing now uses horizontal distance (prevents “push into the commander” when there’s small Y offsets like steps/slabs).
- Follow: doorway traversal plan now chooses valid standable doorway-adjacent cells and applies a small lateral nudge if stuck on the threshold after the door opens.
- Follow: reduced door-vs-target “thrash” by making blocked-route detection prefer raycast clarity over wide collision probes at close range, preventing unnecessary door escape behavior when the commander is directly reachable.
- Follow: when blocked and an adjacent door is opened, follow now commits to the existing door subtask (approach → open → step-through) instead of switching back to direct steering.
- Safety: bots refuse to mine/break protected player blocks (chests/barrels/shulkers/beds) and will nudge away instead if embedded.
- Safety: burial/suffocation/spawn escape no longer mines fences/walls/gates (treat as protected; nudge instead), preventing griefing of player-built structures.
- Safety: generic block-breaking logic refuses to break fences/walls/gates to avoid destroying player enclosures.
- Cook: `/bot cook` now runs movement asynchronously (no server tick freeze) and requires true furnace interactability (reach + line-of-sight), opening/closing doors as needed.
- Performance: removed per-step `stdout` spam from `LookController.faceBlock/faceEntity`.
- Building: allow jump-pillaring placements (used by woodcut scaffolds and shelter scaffolds) by relaxing the “don’t place inside your own bounding box” guard when the bot is airborne and placing into its current foot block.
- Mining: when `collect_dirt`/`mine` navigation fails because the bot is trapped in a vertical pit, it will attempt a ladder escape (craft/place/use when possible) and otherwise fall back to carving a short ascent staircase.

## 2025-11-18
- Persistency and safety: inventory save timing fixed; drop sweeps stop breaking blocks and only collect items; bots break out when spawned in walls; upward stairs start in the controller’s facing direction (partial fix).
- Task queue notes captured for stats persistence and the simplified upward stair spec.

## 2025-11-17 Checkpoint
- Mining polish: work-direction persistence across pause/resume, hazard pauses with `/bot resume`, torch placement on walls (level ≥7), and `/bot reset_direction` to clear stored facings.
- Survival & UX: hunger auto-eat thresholds with `/bot heal`, inventory full messaging, drop sweep retries, suffocation checks after tasks, and `inventory` chat summaries.
- Controls: config UI adds Bot Controls tab (auto-spawn, teleportDuringSkills, inventoryFullPause, per-bot/world LLM toggles) with owner display and scrollable rows; bots auto-spawn at last saved position.
- LLM bridge: natural-language job routing to real skills with confirmation, per-bot personas/memory, action queueing, status responses, and `/bot config llm …` toggles.

## 2025-10-31 (Gemini report recap)
- Added composite tools (`mineBlock`, `chopWood`, `shovelDirt`, `cultivateLand`) with FunctionCaller orchestration and state tracking; verified builds.
- Early RL/hold-tracking tweaks and Mineflayer/RAG exploration notes logged for future LLM integration work.

## Legacy Releases (pre-2025)
- 1.0.x line: 1.20.6 compatibility, server-side training mode support, Q-table format change, risk-taking mechanism, expanded triggers (lava/cliffs/sculk), and broad command set (`use-key`, `detectDangerZone`, inventory queries, armor equip/remove, etc.). See archived release notes in `archive/legacy_changelogs.md`.

## 2025-12-08
- UI: Moved Specific URLs to dedicated button + popup; ensured popup min 800x600.
- Textbox: Added placeholder guidance.
- Specific URLs flow: Added --urls support, timestamped outputs saved to 'Specific Video Lists/'.
- Cleanup: Removed empty markdown placeholders in audio_briefing project (kept API keys out of git).

## [Unreleased] - 2025-12-16
### Added
- **Fishing Skill Upgrade**:
    - Added `until_sunset` parameter to fish until nightfall.
    - Added auto-chest handling: automatically finds or crafts/places a chest when inventory is full and deposits items.
    - Added movement safety: bot now stops moving after catching to prevent running into water; relies on rod mechanics for item collection.
    - Improved default behavior to handle infinite fishing or specific counts more intuitively.
    - Added `depositAll` method to `ChestStoreService`.

### Fixed
- **Fishing Skill**:
    - Fixed issue where the bot would deposit its fishing rod into the chest, preventing it from continuing.
    - Updated default behavior: if no count is specified, the bot fishes until sunset (or stopped).
    - Improved `ChestStoreService` to support item exclusion during deposits.

### Improved
- **Fishing Skill Upgrade (Part 2)**:
    - **Drop Sweeping**: Bot now performs item collection sweeps every 3 minutes and at the end of the session to catch floating items.
    - **Positioning**: Improved logic to move closer to the shoreline edge before casting to avoid hitting the ground.
    - **Bad Throw Detection**: Automatically detects if the bobber lands on dry land, retracts, and adjusts position.
    - **Cliff Casting**: Expanded vertical search range for fishing spots to better support fishing from ledges.

### Fixed
- **Fishing Skill Navigation**:
    - Replaced simple nudging with robust pathfinding for approaching fishing spots, allowing the bot to navigate around obstacles.
    - Added logic to automatically clear obstructing leaves when navigating to the water, ensuring the bot doesn't get stuck by trees.

### Fixed
- **Bot Respawn & Navigation**:
    - Fixed a bug where bots would zombie-resume 'follow' mode after respawning even if ordered to stop.
    - Bots now correctly reset to IDLE state and enable 'Assist Allies' defense mode upon respawn.
    - Improved water physics: bots now swim properly (using the swimming pose) instead of bobbing unnaturally on the surface.

### Fixed
- **Bot Respawn & Navigation**:
    - Fixed a bug where bots would zombie-resume 'follow' mode after respawning even if ordered to stop.
    - Bots now correctly reset to IDLE state and enable 'Assist Allies' defense mode upon respawn.
    - Improved water physics: bots now swim properly (using the swimming pose) instead of bobbing unnaturally on the surface.
- **Fishing Skill Navigation**:
    - Replaced simple nudging with robust pathfinding for approaching fishing spots, allowing the bot to navigate around obstacles.
    - Added logic to automatically clear obstructing leaves when navigating to the water, ensuring the bot doesn't get stuck by trees.

### Fixed
- **Mining descent**:
    - Skip aborting when only out-of-reach headroom blocks remain in the stairwell, preventing premature pauses.
- **Shelter hovel2**:
    - Prefer strict vertical scaffold pillaring for the central tower to avoid messy offset scaffolding.
    - Use direct pillar teardown for hovel2 auxiliary perches to reduce leftover scaffold columns.
    - Verified descent reliability and headroom reach in live testing.
    - Allow exterior pillar scaffolding on corner/roof gap passes so the bot can reach remaining edge targets.
    - Require line-of-sight for direct placement targets to keep survival-style behavior.
    - Iterate corner/roof gap passes to place all reachable blocks before stepping down.
    - Remove GoTo fallback moves during shelter builds to prevent wall phasing.
    - Require line-of-sight for soft block breaking to stop remote snow clears.
    - Loosen placement LOS checks to validate support-block visibility (allows placing into air targets without wall phasing).
    - Allow pursuit-based walking when moving to hovel2 perches to avoid stalling without teleporting.
    - Add short horizontal scaffold-step attempts during corner/roof patching to reach nearby gaps.
    - Fix placement reach checks for air targets by validating support visibility instead of target visibility.
    - Exit the footprint before exterior gap passes/drop sweep so the bot can reach outside targets.
    - Allow scaffold-step extension to use structure blocks when no scaffold blocks are available.
    - Give leveling more passes to clear remaining foot-level obstructions.
- **Shelter hovel refactor**:
    - Unified hovel/​hovel2 into a simpler corner-pillar build with explicit roof fill and drop-sweep completion.
    - Preserve survival-only movement by avoiding teleport/snap while still exiting interiors for exterior targets.
    - Removed legacy hovel2 tower/roof-cap routines and unused perch helpers to cut redundancy.
    - Focus corner pillars on near-corner targets to avoid looping over the full wall each pass.

## [2025-12-22]
### Fixed
- **Shelter Hovel Refactor & Improvements**:
    - Refactored `ShelterSkill.java` into `HovelPerimeterBuilder` and `BurrowBuilder` for better maintainability.
    - Improved hovel leveling: Bot now moves to multiple points (center and corners) to ensure full reach when clearing the site.
    - Enhanced wall/roof building: Bot now builds all reachable blocks from the outside ring walk, including upper wall layers and roof edges.
    - Robust roof completion: `buildFromInside` now uses multiple pillar points for hovels with radius >= 4, ensuring full roof coverage without reach failures.
    - Ensured doorway preservation during all building phases to prevent bot entrapment.

- **Shelter Hovel (Reachability Fix)**:
    - Enabled teleportation during the site leveling phase to ensure the bot can clear terrain obstructions that prevent movement.
    - Relaxed movement strictness during the wall-building phase: bot now attempts to build even if it cannot perfectly reach the exact ring position.

- **Shelter Hovel (Robustness)**:
    - Fixed `pillarUp` reliability by adding an explicit wait loop for the bot to become airborne before placing blocks below.
    - Added `patchWalls` phase: after the initial ring build, the bot specifically targets and moves to any remaining missing wall blocks to ensure completion.
    - Improved `recoverFromFall` to be more persistent and smarter about clearing obstructions above the head before pillaring.

- **Shelter Hovel (Recovery Refinement)**:
    - `recoverFromFall` logic strengthened: now retries pillaring up to 5 times instead of aborting immediately on first failure.
    - Added proactive obstruction clearing: bot now clears blocks 2 and 3 meters above head before attempting a recovery jump to prevent head-bumping.

- **Shelter Hovel (Dynamic Scaffolding)**:
    - Implemented a "move closer" fallback in "patchWalls" to ensure blocks just out of reach from the ring path are attempted from a better position.
    - "PillarUp" now actively waits for the bot to become airborne, fixing cases where placement failed due to the bot still being grounded.
    - "RecoverFromFall" now retries intelligently and clears obstructions more aggressively.

- **Shelter Hovel (Opportunistic Pillaring)**:
    - Implemented a general-purpose pillaring fallback: if any block is horizontally reachable but vertically too high, the bot will now automatically pillar up 1 block to place it, then tear down the pillar.
    - Improved `pillarUp` stability by centering the bot on the block before jumping to prevent slipping off edges during scaffolding.
    - Added proactive headroom clearing to `pillarUp` to ensure jump height is sufficient for block placement.

- **Shelter Hovel (Efficiency & Logic)**:
    - Implemented a "Smart Ring Walk": Bot now skips ring positions that do not have any reachable missing blocks, drastically reducing time spent pathfinding around the perimeter.
    - Optimized Roof Build Order: Bot now builds the roof from the outer edges inward (using wall support) rather than trying to place floating blocks in the center first.

- **Moat/Survival**: Add `hasLineOfSight` check to nearest-first algorithm to stop remote mining through dirt walls

- **Moat/Nav**: Add unreachable block skipping and micro-movement alignment to fix 'finished' infinite loops
