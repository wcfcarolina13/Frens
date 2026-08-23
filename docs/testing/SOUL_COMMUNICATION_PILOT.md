# Soul Communication (Jake DM) Pilot — Acceptance Checklist

Validates the pilot acceptance criteria and manual test matrix in
`docs/superpowers/specs/2026-08-23-frens-soul-communication-design.md`
("Pilot acceptance criteria" / "Manual pilot test matrix" sections).

Two sections below:

- **Automated** — genuinely exercised by this branch's JUnit suite (`./gradlew test`, 228 tests
  green as of this document). Each case cites the exact test class/method so a reviewer can
  re-run just that slice (`./gradlew test --tests "net.wcfcarolina13.GameAI.souls.ClassName"`).
- **Manual — to be executed in-game** — requires a running Minecraft 1.21.11 client/server and a
  human at the keyboard. Every box starts unchecked; the user runs these and checks them off.

## Record-keeping rule for every manual case

Record **timestamps and correlation IDs** from the `frens.souls` logger (e.g.
`[souls] routing routingId=... outcome=...`, `[souls] turn correlationId=... outcome=delivered`).
**Never paste private message bodies** (player chat text or Jake's replies) into test notes,
issue trackers, or this document. If a failure needs a repro, describe it by outcome/status code
(e.g. "TIMEOUT status shown, correlationId abc123"), not by quoting the conversation.

---

## Automated (verified in this branch's test suite)

- [x] **Feature disabled → legacy behavior unchanged (routing decision table)**
  `SoulChatRouterTest.disabledOrUnboundSoulLeavesLegacyRoutingUntouched` — master flag off, or
  master on but bot unbound, both return `RouteOutcome.NOT_SOUL` so legacy `LLMOrchestrator`
  routing is untouched.
- [x] **Souls-disabled-by-default config**
  `SoulFoundationTest.freshConfigHasSoulsDisabledByDefault` — a fresh `ManualConfig` has
  `soulsEnabled == false`; also confirmed by reading `ManualConfig.java:102`
  (`private boolean soulsEnabled = false;`).
- [x] **Loading state consumed before profile-activation is even considered**
  `SoulChatRouterTest.notReadyIndexConsumesBeforeProfileActivationIsEvenConsidered` — index not
  ready → `CONSUMED`, sending `"Jake's conversation memory is still loading. Try again in a
  moment."` (LOADING notice), regardless of profile state. This state is timing-dependent (the
  index preload window right after server start) and may not be reproducible on a small save
  where preload finishes before a player can act — treat as best-effort if attempted manually.
- [x] **Unbound bot with ready index → NOT_SOUL**
  `SoulChatRouterTest.readyIndexWithNoActiveProfileIsUnboundNotSoul`.
- [x] **Broadcast keyword ("bots"/"all bots") never soul-routed, even at one registered bot**
  `SoulChatRouterTest.oneRegisteredBotPlusBroadcastKeywordIsNotSoulRoutable`.
- [x] **Explicit single-bot name is soul-routable; multi-bot lists never are**
  `SoulChatRouterTest.explicitSingleBotNameIsSoulRoutable`,
  `SoulChatRouterTest.multiBotExplicitListIsNeverSoulRoutableRegardlessOfBroadcastFlag`,
  `SoulChatRouterTest.zeroTargetsIsNeverSoulRoutable`.
- [x] **Owner/operator authorization gate (private conversation write access)**
  `SoulGroundingTest.privateSoulRequiresExactOwnerOrOperator` — operator always passes; a
  non-operator must be the exact recorded owner UUID; an unowned bot is NOT eligible (display
  name is not an identity boundary).
- [x] **Unauthorized sender consumed with a deterministic refusal, never silently dropped to legacy**
  `SoulChatRouterTest.enabledActiveSoulConsumesUnauthorizedAndUnreachableTurns`.
- [x] **Reachability classification: LOCAL / REMOTE / UNREACHABLE boundaries**
  `SoulGroundingTest.reachabilityIsLocalAtExactly32BlocksBoundary`,
  `reachabilityIsRemoteJustBeyond32BlocksWhenChatGateAllows`,
  `reachabilityIsUnreachableBeyond32BlocksWithoutChatGate`,
  `reachabilityIsUnreachableAcrossDimensionsWithoutChatGate`.
- [x] **Remote grounding omits player-surrounding facts; local grounding includes them**
  `SoulGroundingTest.remoteSnapshotOmitsPlayerState`, `localSnapshotIncludesPlayerState`;
  `SoulPromptAssemblerTest.remotePromptDoesNotContainPlayerSurroundings` — REMOTE prompts never
  read `GroundingSnapshot.player()`; the assembled system message instead states "There is no
  shared line of sight; the player's position, surroundings, and held item are not visible to
  you."
- [x] **Rapid messages stay strictly ordered per player-bot thread, never overlap**
  `SoulProviderSchedulerTest.sameConversationNeverOverlaps`.
- [x] **Bounded queue rejects overload visibly (OVERLOADED)**
  `SoulProviderSchedulerTest.queueOverflowCompletesImmediatelyWithOverloaded` → surfaces as
  `SoulConversationService`'s `"<name> is tied up answering something else. Try again in a
  moment."` status line.
- [x] **Global concurrency cap holds across two different conversation keys**
  `SoulProviderSchedulerTest.twoDifferentKeysRespectGlobalConcurrencyCap`.
- [x] **HTTP timeout → TIMEOUT failure**
  `SoulProviderSchedulerTest.httpTimeoutMapsToTimeoutFailure` → `"<name> didn't answer in
  time."`.
- [x] **Provider request shape (local Ollama adapter)**
  `SoulProviderSchedulerTest.requestBodyMatchesOllamaChatShape`.
- [x] **Malformed/unusable provider output rejected, never delivered**
  `SoulResponseValidatorTest` (17 cases): blank/null text, NUL characters, unclosed
  `<think>`/`<analysis>` reasoning tags, fenced tool/JSON payloads (`` ``` `` at line start),
  excessive length, control characters, legacy formatting codes — all rejected as `MALFORMED`
  and never reach `ValidationResult.text()`.
- [x] **Provider failure never becomes spoken memory**
  `SoulConversationServiceTest.providerFailureAppendsFailureRecordAndNeverSpoken`,
  `invalidProviderResponseAppendsMalformedFailureRecord`,
  `failedDeliveryNeverBecomesSpokenMemory` — a reply is committed as `SPOKEN` only after
  `Delivery#deliverReply` actually succeeds (`SoulConversationServiceTest
  .recordsSpokenOnlyAfterSuccessfulDelivery`).
- [x] **Disconnect / bot removal / dimension change / reset / world close during generation —
  delivery-gate logic**
  `SoulMessageDeliveryTest`: `deliversOnlyWhenEveryGatePasses`, `failsClosedWhenMasterDisabled`,
  `failsClosedWhenActiveProfileChanged`, `failsClosedWhenCursorEpochNoLongerMatchesTheToken`,
  `failsClosedWhenEitherPartyIsOffline`, `failsClosedWhenOwnershipNoLongerExact`,
  `failsClosedWhenReachabilityIsUnreachable` — the six-gate `SoulMessageDelivery.evaluate`
  combinator (master-enabled, profile-unchanged, epoch-matches, both-online, ownership-valid,
  reachability≠UNREACHABLE) is exhaustively unit-tested; production wiring for disconnect calls
  `SoulRuntime.cancelPlayer`/`cancelBot` (`Frens.java` `ServerPlayConnectionEvents.DISCONNECT`).
  *Live socket/process behavior itself (actual disconnect packet, actual `SERVER_STOPPING`
  during an in-flight HTTP call) is still manual — see below.*
- [x] **Conversation reset during generation cancels/staleifies in-flight work, skips a spurious
  failure record**
  `SoulConversationServiceTest.resetDuringGenerationSkipsFailureRecordButStillFails`;
  `SoulProviderSchedulerTest.invalidateOlderEpochCompletesQueuedJobAsStaleEpochAndCancelsActive`,
  `closeCancelsActiveAndCompletesQueuedAsCancelled`.
- [x] **Reset archives the current epoch; stale in-flight replies rejected by the new epoch**
  `SoulStoreTest.archivesResetEpochAndRejectsStaleSpeech`.
- [x] **Restart persistence: transcript, cursor, and bound state survive a reload**
  `SoulStoreTest.restartRecoversPersistedConversationAndState`,
  `usesExactWorldLocalPersistencePaths` (confirms the exact
  `<world>/frens/souls/v1/<bot-uuid>/...` layout),
  `beginHeardTurnReconcilesSequenceAheadOfPersistedCursor`,
  `reconcilesInterruptedResetFromArchiveArtifact` (interrupted-reset recovery — covers the
  "world close mid-reset" corruption edge, not a live client restart).
- [x] **Corrupt/incomplete final JSONL line tolerated; earlier valid records still load**
  `SoulStoreTest.corruptTailIsQuarantinedAndValidRecordsSurvive`,
  `malformedRecordBeforeFinalLineFailsLoadVisibly` (a corrupt record before the final line still
  fails loudly rather than silently dropping data).
- [x] **Bot UUID identity is stable across dimension changes (data-model level)**
  `SoulFoundationTest.conversationKeyUsesUuidNotDisplayName` — `ConversationKey` is keyed by
  UUID, not display name; dimension is not part of identity. *Actually watching Jake and the
  player cross dimensions independently and confirming history survives is still manual.*
- [x] **Reserved deterministic routing: quest fast-path always resolves ahead of souls**
  `SoulEventObserverTest.questStageChangeEmitsOnTransition` (journal side); routing order itself
  (`BotZzzSleepService` → nearby-quest-ask → `FunctionCallerV2.tryHandleConfirmation` →
  `BotRespawnPromptService` → `SkillResumeService` → `BotQuestService.tryHandleQuestPrompt` →
  `SoulChatRouter.tryRoute`) is fixed source order in `Frens.java`'s `CHAT_MESSAGE` handler
  (lines ~1218–1292) — not itself unit-tested as an ordering property, so the reserved-message
  cases are listed manual below.
- [x] **Generated prose cannot be parsed as an action — validator never inspects for
  commands/tools**
  `SoulResponseValidator` Javadoc + `SoulResponseValidatorTest.rejectsToolSyntaxAndExcessiveOutput`
  (fenced tool/JSON payloads are rejected outright); `SoulMessageDelivery` sends validated text
  via `player.sendMessage(...)` only — it never constructs a command source or re-enters chat
  parsing. Static grep in Step 3 below independently confirms the `souls` package never touches
  `FunctionCallerV2`/`withPermissions`/command dispatch. *Confirming in a live client that typed
  command-like prose ("/bot mine diamonds") from Jake does not fire an action is still manual.*
- [x] **Undelivered generated text never stored as spoken dialogue**
  `SoulConversationServiceTest.failedDeliveryNeverBecomesSpokenMemory`.
- [x] **Credentials never appear in the souls package source (static analysis)**
  See "Static privacy and authority checks" below — `rg` over
  `src/main/java/net/wcfcarolina13/GameAI/souls` for credential field names returned zero matches.
- [x] **Provider-independent pieces have focused coverage**
  228 tests total across `SoulFoundationTest`, `SoulGroundingTest`, `SoulPromptAssemblerTest`,
  `SoulResponseValidatorTest`, `SoulProviderSchedulerTest`, `SoulStoreTest`,
  `SoulConversationServiceTest`, `SoulMessageDeliveryTest`, `SoulChatRouterTest`,
  `SoulRuntimeTest`, `SoulEventObserverTest`, `BotSoulCommandsTest`.
- [x] **`./gradlew build -x test` succeeds**
  See "Test/build evidence" below.

---

## Manual — to be executed in-game

Setup common to all cases unless a case says otherwise: a local Ollama (or equivalent
OpenAI-compatible local) model configured per `SoulSettings`; server started fresh; `Jake` is a
spawned/registered Frens bot. Use `/bot soul status [bot]` liberally to confirm system/provider
state without guessing.

### Baseline (feature disabled)

- [ ] **Souls disabled, including existing scripted chat and commands**
  Setup: `soulsEnabled=false` (default — confirm via `/bot soul status`, expect "Soul system:
  runtime not currently running." or `system=off`). Address Jake by name in chat, and separately
  run existing `/bot` commands (spawn/follow/store/etc.) and scripted `zzz`/quest dialogue.
  Expected: Frens behaves exactly as before this branch — legacy `LLMOrchestrator`/quest/zzz
  handlers answer normally; no soul-specific chat line, no `frens.souls` routing log entries.

### Activation and permissions

- [ ] **Jake activation and deactivation**
  Setup: as an operator, `/bot soul system on`; then `/bot soul enable <Jake>` as Jake's owner
  (or an operator). Expected: chat confirms `"<Jake> is now speaking as Jake."`; `/bot soul
  status <Jake>` shows `active=true`. Then `/bot soul disable <Jake>`. Expected: chat confirms
  `"<Jake>'s soul communication is now disabled (conversation files preserved)."`; DMing Jake
  afterward falls back to legacy routing (bot is unbound-for-routing purposes because
  `active=false`); `soul.json`/conversation files remain on disk unchanged.
- [ ] **Unauthorized sender attempting to address Jake**
  Setup: souls on, Jake enabled and owned by Player A. Player B (not owner, not operator) sends
  Jake a private chat message. Expected: Player B sees `"Jake's private conversation is
  available only to his owner or an operator."`; nothing is appended to Jake's conversation
  history; no provider call occurs (confirm via log: no `[souls] turn correlationId=...` line
  for this attempt, only a `[souls] routing ... outcome=unauthorized` line).
- [ ] **Owner/operator permission boundary on `/bot soul` subcommands**
  Setup: as a non-owner, non-operator player, attempt `/bot soul enable/disable/reset <Jake>`
  and `/bot soul status <Jake>`. Expected: each is refused with an authorization error; as an
  operator acting on someone else's bot, `/bot soul reset <Jake>` archives only the operator's
  own direct thread with Jake, never the actual owner's.

### Local, remote, and unreachable DM

- [ ] **Authorized local DM**
  Setup: owner within 32 blocks of Jake, souls on, Jake enabled. Send a normal message.
  Expected: reply arrives as a private `"Jake: ..."` line; local grounding includes player
  distance/direction/held item/condition; log shows `reachability=LOCAL`, full stage timings,
  ending `outcome=delivered`.
- [ ] **Authorized remote DM through each supported communication method**
  Setup: owner beyond 32 blocks (or different world) from Jake, and in turn: (a) both holding
  Eye of Ender, (b) both holding Goat Horn, (c) either party carrying the Wizard's Tome item,
  (d) either party within 4 blocks of an enchanting table. Expected: each method independently
  classifies as `reachability=REMOTE`, the reply is delivered, and it explicitly signals no
  shared line of sight (Jake should not describe or react to the player's current surroundings,
  held item, or condition — only what he's been told or already knows).
- [ ] **Unreachable DM with no provider request**
  Setup: owner beyond 32 blocks from Jake with none of the remote-communication conditions
  above satisfied. Send a message. Expected: immediate `"You cannot reach Jake from here."`;
  message is NOT queued, NOT sent to any provider, NOT appended to conversation history; log
  shows `outcome=unreachable` with no subsequent `[souls] turn correlationId=...` entry.

### Rapid messages, saturation, and provider failure modes

- [ ] **Rapid consecutive messages and queue saturation**
  Setup: send several messages to Jake back-to-back faster than generation completes.
  Expected: replies arrive in the same order sent, never interleaved/overlapping; once the
  bounded queue is full, additional messages get `"Jake is tied up answering something else.
  Try again in a moment."` rather than hanging or crashing the server.
- [ ] **Local provider unavailable before a request**
  Setup: with a correctly-configured Ollama provider (`/bot soul status` shows `settingsValid=
  true`), stop the local Ollama/model server, then address Jake. Expected: the router's own
  `pipelineAvailable()` check depends only on config shape (`SoulSettings.valid()` — provider
  name/model/URL), not server liveness, so it still reports ready; the request reaches
  `OllamaSoulProvider`, whose HTTP call fails, and `OllamaSoulProvider.mapFailure` maps that to
  `UNAVAILABLE`. Expected chat line: `"Jake's local conversation model is unavailable."` Confirm
  the server does not hang or error out.
- [ ] **Misconfigured pipeline (invalid settings) — separate from the stopped-server case above**
  Setup: with Ollama actually running, make the persisted config itself invalid — edit
  `settings.json5`'s `soulModel` field to blank directly (the `/bot soul model` command refuses
  a blank value itself, so this requires editing the file — or an equivalent invalid field —
  then restarting or otherwise causing `SoulRuntime` to re-derive `SoulSettings`). Address Jake.
  Expected: `SoulSettings.valid()` is false purely from config shape (`SoulSettings.from`'s blank-
  model branch sets `validationError = "Configure a local soul model first."`), so
  `pipelineAvailable()` is false regardless of whether Ollama is reachable. Expected chat line:
  `"Jake's local conversation model is not ready: Configure a local soul model first."`
  (`outcome=invalid-pipeline` in the routing log).
- [ ] **HTTP 503 from the provider**
  Setup: point the configured model at a stub/proxy returning HTTP 503, or otherwise force a
  503 response. Expected: `OllamaSoulProvider.mapResponse` maps every non-2xx status to
  `UNAVAILABLE` before inspecting the response body — `OVERLOADED` is produced only by the
  scheduler's separate queue-full check (see "Rapid consecutive messages and queue saturation"
  above), never by a provider HTTP status. Expected chat line: `"Jake's local conversation model
  is unavailable."` Record the correlation ID.
- [ ] **Malformed JSON from the provider**
  Setup: force a non-JSON or schema-invalid response from the provider (stub/proxy). Expected:
  `"Jake couldn't form a usable reply."` (`MALFORMED`); nothing is delivered or recorded as
  spoken.
- [ ] **Timeout**
  Setup: force the provider to exceed the configured `SoulSettings` timeout (e.g. point at a
  server that never responds). Expected: `"Jake didn't answer in time."` (`TIMEOUT`) after
  roughly the configured timeout window, not indefinitely; server stays responsive throughout
  (confirms provider work is off the server thread).

### Disconnect, death, dimension, reset, and world-close during generation

- [ ] **Player disconnect during generation**
  Setup: send Jake a message, then disconnect the player before the reply completes. Expected:
  no crash; `SoulRuntime.cancelPlayer` fires (confirm log); no reply is delivered to a
  disconnected player; reconnecting later shows no orphaned "Jake is thinking…" state and the
  conversation history shows the heard message with failure metadata rather than a phantom
  spoken reply.
- [ ] **Jake removal or death during generation**
  Setup: send Jake a message, then kill/remove Jake before the reply completes. Expected:
  `SoulRuntime.cancelBot` fires; delivery fails closed (both-online gate fails); no crash; no
  spoken record without a real delivery.
- [ ] **Conversation reset during generation**
  Setup: send Jake a message, then immediately `/bot soul reset <Jake>` before the reply
  arrives. Expected: chat confirms `"Archived your conversation with <Jake>; new epoch N."`; the
  in-flight reply either never lands or lands as part of the OLD epoch's archive, never
  interleaved into the new epoch; player sees `"The conversation changed before Jake could
  answer."` if the in-flight turn surfaces a status at all.
- [ ] **World save, close, reopen, and continued conversation**
  Setup: send Jake a message, then close the world (or stop the server) before/while the reply
  is in flight. Reopen the world. Expected: no corruption on load (a torn last JSONL line, if
  any, is quarantined per `SoulStoreTest.corruptTailIsQuarantinedAndValidRecordsSurvive`'s
  logic); prior valid history is intact; sending Jake a new message continues the conversation
  normally with correct history/epoch.
- [ ] **Jake and player changing dimensions independently**
  Setup: have Jake and the player travel to different dimensions independently (e.g. player
  goes to the Nether, Jake stays overworld). Expected: reachability reclassifies correctly
  (`sameWorld=false` → REMOTE only if a remote-comm method is active, else UNREACHABLE); direct
  conversation history persists and is addressable regardless of either party's current
  dimension (bot UUID identity is dimension-independent).

### Restart persistence

- [ ] **Restart persistence and dimension-stable UUID history**
  Setup: have a multi-turn conversation with Jake, note the epoch via `/bot soul status`, fully
  restart the server (not just reload). Expected: `/bot soul status <Jake>` after restart shows
  the same `active`/`profile`/`directEpoch` state; sending a new message continues the same
  conversation (prior turns still influence context up to the bounded history window); no
  duplicate or lost turns at the restart boundary.

### Remote perception leakage probe

- [ ] **Remote conversation while the player encounters information Jake cannot witness**
  Setup: establish a REMOTE conversation (see above). While remote, have the player pick up or
  stand next to a distinctive, player-only landmark/item that Jake has no in-game way of
  knowing about (e.g. a uniquely named item the player just crafted, or a structure only the
  player has seen). Ask Jake something that would tempt a model to "notice" it (e.g. "what am I
  standing near?" or "what's in my hand?"). Expected: Jake does NOT reference the landmark/item;
  if he addresses the question at all, he does so by acknowledging he cannot see the player
  right now — never by guessing or hallucinating a specific detail that happens to be correct.
  This is the one case where a wrong answer is a real privacy/grounding defect worth flagging
  even though it's a probabilistic LLM output, not a deterministic assertion.

### Reserved deterministic routing (must never reach souls)

- [ ] **`zzz` sleep coordination**
  Setup: souls on, Jake enabled, nighttime. Type `zzz` (or the bot's configured sleep trigger)
  near Jake. Expected: `BotZzzSleepService` handles it exactly as before this branch (Jake
  attempts to sleep); no soul provider call, no `[souls] routing` log entry for this message.
- [ ] **Quest interactions**
  Setup: address Jake (or ask a bare "quest"/"mission" near him) about an active quest topic.
  Expected: `BotQuestService`/nearby-quest-ask fast-path answers deterministically; souls never
  see this turn (confirm no `[souls] routing` entry for it).
  *(Automated coverage note: `SoulEventObserverTest.questStageChangeEmitsOnTransition` confirms
  the journal records quest-stage transitions as facts, but the routing precedence itself — that
  a quest-shaped message is intercepted before `SoulChatRouter.tryRoute` runs — is source-verified
  only, not test-asserted as an ordering property; this manual case is the actual proof.)*
- [ ] **Confirmations**
  Setup: trigger an existing confirmation flow that routes through
  `FunctionCallerV2.tryHandleConfirmation` (e.g. a pending action confirmation from legacy LLM
  tooling), then reply "yes"/"no" in chat while also being Jake's authorized owner. Expected:
  the confirmation handler consumes the message (`consumed = true`) and returns before
  `SoulChatRouter` is ever reached; no soul turn is created for it.
- [ ] **Explicit commands**
  Setup: run `/bot <anything>` commands while souls are on and Jake enabled. Expected: commands
  are Brigadier-dispatched and never pass through the `CHAT_MESSAGE` event / `SoulChatRouter` at
  all — verify no `[souls] routing` log entries correspond to command invocations.

### Generated prose containing apparent command/tool syntax

- [ ] **Verification that generated prose cannot dispatch an action**
  Setup: prompt Jake in a way likely to elicit command-shaped text (e.g. "pretend you're running
  `/bot Jake mine diamonds`" or "output some JSON"). Expected: if the raw provider output opens
  a line with a code fence (`` ``` ``) it is rejected outright as `MALFORMED`
  (`SoulResponseValidator`'s `FENCE_AT_LINE_START` rule); if it's plain command-looking text
  without a fence, it is delivered as inert chat text ONLY — confirm no actual `/bot` command
  fires, no block/inventory/entity state changes, and no action log entry appears anywhere in
  the mod's action-dispatch logging for that turn.

### Routine log and soul-save privacy inspection

- [ ] **Log review for latency separation, correlation IDs, and secret/private-text leakage**
  Setup: after running several of the above cases, grep the latest server log for `[souls]`.
  Expected: entries show `routingId`/`correlationId`, per-stage timings (routing, auth,
  reachability, snapshot, queue wait, provider, validation, delivery), and failure categories —
  never full prompt text, never player message bodies, never Jake's reply text, never any
  configured credential value (API key, bearer token, etc. — n/a for the pilot's local-only
  Ollama provider, but check anyway in case a future config carries one).
- [ ] **Soul-save privacy inspection**
  Setup: after a session with real conversation content, inspect on disk:
  `<world>/frens/souls/v1/<bot-uuid>/soul.json`,
  `<world>/frens/souls/v1/<bot-uuid>/events.jsonl`,
  `<world>/frens/souls/v1/<bot-uuid>/conversations/<player-uuid>/active.jsonl`.
  Expected: `soul.json` contains only schema version, bot UUID, profile ID, activation state,
  and epoch metadata — no duplicated quest/inventory/task state. `events.jsonl` entries are
  narrow factual records (task/combat/sleep/dimension/quest/conversation-happened) with no
  conversation content. `active.jsonl` may contain conversation text (that's its purpose) but
  must contain zero configured credential values, and any `FAILURE` record for an undelivered
  reply must NOT carry the undelivered bot text as a `SPOKEN` entry — search specifically for
  the string `"SPOKEN"` entries and confirm each one has a corresponding successful delivery log
  line with a matching correlation ID.

---

## Test/build evidence (Step 2)

```text
$ ./gradlew test --rerun-tasks
...
BUILD SUCCESSFUL in 14s
4 actionable tasks: 4 executed
```

228 tests collected across `build/test-results/test/*.xml`; zero `failures`/`errors` attributes
present in any suite file.

```text
$ ./gradlew build -x test
...
BUILD SUCCESSFUL in 6s
8 actionable tasks: 1 executed, 7 up-to-date
```

Artifact: `build/libs/frens-1.1.137-release+1.21.11.jar`.

## Static privacy and authority checks (Step 3)

```text
$ rg -n "FunctionCallerV2|withPermissions|LLMServiceHandler|LLMOrchestrator|MemoryStore" src/main/java/net/wcfcarolina13/GameAI/souls
src/main/java/net/wcfcarolina13/GameAI/souls/SoulChatRouter.java:17: * to the soul-communication pilot instead of the legacy {@code LLMOrchestrator} path, and — when
```

One match, inside a class-level Javadoc comment describing what the router does NOT do (it
explicitly documents that soul turns do not route through the legacy `LLMOrchestrator` path). It
is prose, not a functional reference — no import, call site, or dependency on `LLMOrchestrator`
exists in the `souls` package. Per the task-12 brief's exact instruction, this match is reported
verbatim without editing code, and the overall status is **DONE_WITH_CONCERNS** pending
controller adjudication on whether a Javadoc mention counts as a "reference" for this check's
purposes.

```text
$ rg -n "Authorization|Bearer|apiKey|ApiKey|openAIKey|claudeKey|geminiKey|grokKey|customApiKey" src/main/java/net/wcfcarolina13/GameAI/souls
(no matches)
```

Zero credential references — matches expectation exactly.
