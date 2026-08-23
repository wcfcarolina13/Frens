# Frens soul communication

Date: 2026-08-23
Status: Approved design — implementation plan pending

## Purpose

Frens will gain an optional, LLM-backed communication layer that lets authored bot companions hold free-form, in-character conversations without surrendering control of gameplay, quest truth, or existing scripted behavior.

The first release is deliberately narrow: Jake can participate in persistent, owner-authorized direct-message conversations through Frens' existing chat surface. The architecture must nevertheless preserve clean seams for multiple independently owned bots, group conversation, autonomous banter, generated voice, memory consolidation, and validated action requests.

This is a Frens-native system. It applies lessons learned while building Dreamsleeve for OpenMW, but it does not share Dreamsleeve's daemon, runtime, persistence, or game-specific abstractions.

## Product principles

1. **Frens remains Frens.** Scripted quests, survival behaviors, skills, controls, mood systems, and authored dialogue remain authoritative.
2. **Conversation is embodied.** A bot may speak only from information it could plausibly know, plus explicit authored and remembered information.
3. **Every bot is an individual.** Identity, direct history, perception, authorization, and future action permissions belong to a bot UUID, not a display name.
4. **The first slice must be reversible.** Soul communication is feature-gated, isolated from legacy execution paths, and removable without save damage.
5. **Local-first does not mean local-only architecture.** The pilot targets the existing local-model/offload workflow, while the provider contract allows later hosted APIs.
6. **Failure is visible and safe.** Provider or queue failure must never freeze the game, fabricate a delivered reply, or silently send private context elsewhere.
7. **Generated prose has no authority.** Conversation cannot mutate the world or invoke actions. Future action support uses a separate structured and validated path.

## Current-state findings

Frens already has most of the game-side ingredients needed for grounded character conversation:

- Private bot-to-player chat and communication-policy checks
- Multiple player-owned bots
- Scripted quest dialogue and contextual reactions
- Bot mood, survival state, tasks, targets, homes, ownership, and persistence
- Existing Ollama and hosted-provider clients
- A legacy LLM orchestrator, memory store, and function-calling path

Those pieces should not simply be extended in place.

The current LLM client is based primarily on a system prompt plus one user prompt. It lacks first-class role history, cancellation, structured result metadata, reliable queue semantics, and the privacy boundaries required by persistent character conversations. The current memory store is dimension-scoped and retains only a short list of strings, which fragments identity across dimensions. The existing all-bots route starts independent calls rather than forming a coherent shared conversation. Authorization is also not consistently established at the routing boundary before privileged command execution becomes reachable.

The existing dialogue panel is a scripted quest/topic interface. Its transcript is client-local, session-only, and capped; it has no free-form input. It will not become the canonical soul-memory store in the pilot.

## Lessons transferred from Dreamsleeve

The design adopts the following proven patterns while reimplementing them in Frens:

- Keep game sensing and authority separate from provider work.
- Put the current present-moment snapshot immediately before the user's current turn so older context cannot overpower it.
- Let live authoritative state override recalled conversation.
- Preserve raw transcripts while injecting only a bounded recent tail.
- Represent witnessed gameplay as structured facts before converting those facts to prompt prose.
- Treat direct, group, and autonomous conversations as explicit channel types.
- Use mechanical validation, caps, and parsers rather than relying only on prompt instructions.
- Keep natural-language action classification separate from conversational generation.
- Fail closed when classification or provider calls fail.
- Rebuild the participant roster for each group turn.
- Prevent stale or overlapping generations from winning after a newer turn supersedes them.
- Instrument entry, queueing, provider timing, validation, and delivery separately.
- Never substitute the player's senses for a remote companion's senses.

## Scope

### Pilot goals

- Add a provider-neutral, Frens-owned soul communication core beside the legacy LLM stack.
- Give Jake an authored soul profile bound to a specific bot UUID.
- Support owner/operator-only free-form direct messages with Jake.
- Persist raw direct-message history across restarts and dimension changes.
- Ground replies in authoritative Jake state, plausibly witnessed player state, quest state, and a narrow event journal.
- Keep provider work ordered, bounded, cancellable, observable, and off the server thread.
- Start with a configured local provider while retaining a contract suitable for hosted providers.
- Provide reset/archive behavior from the beginning.

### Pilot non-goals

- No LLM-triggered bot actions
- No autonomous banter
- No group-chat generation
- No generated voice
- No memory consolidation or semantic retrieval
- No free-form dialogue-panel redesign
- No per-player provider credentials
- No replacement of existing scripted dialogue, quests, actions, or mood logic
- No automatic fallback from a local provider to a remote provider
- No soul activation for every bot merely because the architecture supports it

## System boundary

The soul system is a new communication domain beside the legacy stack. It may reuse sound low-level provider transport code, but it does not route pilot turns through `LLMOrchestrator`, `MemoryStore`, or `FunctionCallerV2`.

Routing is exclusive. When the feature flag is off or a bot has no activated soul, existing Frens routing is unchanged. When an eligible message is accepted for a soul-enabled bot, the soul path owns that conversational turn and the legacy LLM path cannot also generate a reply.

The domain has five conceptual responsibilities:

1. **Router:** resolves target, channel, authorization, and reachability.
2. **Snapshot builder:** captures immutable server-authoritative context on the server thread.
3. **Conversation service:** owns histories, strict per-thread ordering, reset epochs, and delivery lifecycle.
4. **Provider scheduler:** performs bounded off-thread generation with explicit timeouts and cancellation.
5. **Event journal:** stores narrow factual events that a bot witnessed or participated in.

Minecraft world objects, mutable entities, inventories, and registries never cross into provider worker code. Provider jobs receive immutable value snapshots only.

## Conversation channels

The internal model supports these channel types even though only `DIRECT` is implemented in the pilot:

- `DIRECT`: one player and one bot; private relationship history
- `PARTY`: explicit player-directed group conversation
- `LOCAL`: nearby social conversation
- `BANTER`: system-initiated, eligibility-gated group exchange
- `SYSTEM`: deterministic notices and delivery status, never treated as character dialogue

The existing private chat/DM surface is the pilot's primary interface. A future dialogue panel may display or submit to the same direct thread, but it must not create a second history.

## Identity and authorization

Soul identity is keyed by bot UUID and a stable authored profile identifier such as `frens:jake`. Display-name matching is not a security or identity boundary.

Activation requires both the server-wide feature flag and an explicit profile binding for that bot. Merely spawning or naming a bot Jake does not activate the authored Jake soul.

The pilot permits private soul conversation only when the sender is the bot's authoritative owner or an authorized operator. Unauthorized players cannot write into Jake's private history, even if they know his name or stand nearby.

Future nearby-social access is a separate configuration decision. Future action authority is also separate and stricter than permission to converse.

## Communication reachability

One authoritative service classifies each attempted conversation before a provider call:

- `LOCAL`: the participants are within the configured ordinary communication rules.
- `REMOTE`: an existing Frens communication method permits long-range contact.
- `UNREACHABLE`: no valid communication link exists.

An unreachable message produces an immediate deterministic notice. It is not queued, sent to a provider, or written into conversational memory.

Remote contact does not imply remote perception. The prompt explicitly states that Jake is communicating at a distance and cannot see the player's current surroundings.

## Turn lifecycle

An accepted direct turn follows this sequence:

1. Resolve the explicitly addressed bot and channel.
2. Verify ownership/operator authorization.
3. Classify the communication link as local, remote, or unreachable.
4. Allow deterministic Frens handlers to consume reserved behavior first, including sleep coordination, explicit commands, confirmations, and quest interactions.
5. Capture an immutable server-thread snapshot for the soul turn.
6. Admit the turn to the serialized pipeline behind earlier work for the same player-bot thread.
7. Append the accepted player message as a heard inbound turn in the active conversation epoch.
8. Assemble the bounded prompt and invoke the provider off-thread.
9. Validate the provider result as conversational text.
10. Recheck that the bot, recipient, epoch, authorization, and communication delivery conditions are still valid.
11. Deliver the private DM.
12. Only after successful delivery, append the bot response as a spoken outbound turn.

If generation fails, the heard player message may remain in history, accompanied by operational failure metadata. Undelivered bot text is never recorded as something Jake said.

After approximately one second of unresolved generation, Frens may show an ephemeral private “Jake is thinking…” indicator. It is not a permanent chat line and never enters memory.

## Ordering, concurrency, and cancellation

Each player-bot conversation is strictly serialized. A second message cannot produce an overlapping reply from the same bot. This prevents reordered conversations and stale answers.

A provider-wide scheduler applies separate global concurrency limits:

- Local generation defaults to one active request.
- Hosted providers may later use a small configured concurrency limit.
- Queues are bounded and reject overload visibly.
- Action jobs, when eventually implemented, use a separate queue and permission boundary.

Every turn carries a conversation ID, epoch, sequence number, and correlation ID. Resetting a conversation advances the epoch so an old in-flight response cannot be delivered into the new conversation. Disconnects, bot removal, world closure, or explicit cancellation invalidate pending delivery.

Network calls, retries, waits, prompt assembly beyond trivial formatting, and transcript I/O do not block the server thread. Any world-dependent state is captured atomically before worker execution and revalidated before delivery.

## Persistence and memory

Soul state is isolated per world save. Bot identity uses a UUID that survives dimension changes. Direct history is further scoped by player UUID.

Proposed versioned layout:

```text
<world>/frens/souls/v1/<bot-uuid>/
  soul.json
  events.jsonl
  conversations/
    <player-uuid>/
      active.jsonl
      archive/
```

`soul.json` contains schema version, bot UUID, authored profile ID, activation state, and active conversation-epoch metadata. It does not duplicate authoritative quest, inventory, task, or ownership state.

Conversation records are append-only and distinguish accepted inbound, delivered outbound, and operational failure events. They may record provider/model identifiers, timing, and token usage when available. They never store credentials.

Each conversation and event file has one ordered writer. Persistence is performed off the server thread, flushes complete records at defined boundaries, and tolerates an incomplete final JSONL line after an interrupted process. Loading never invents or partially accepts a corrupt record; it reports and isolates unreadable tail data.

The active prompt includes only a token-capped recent tail, initially expected to be roughly 10–20 conversational turns. Older raw history remains available for future consolidation but is not blindly sent to the provider.

Reset archives the current epoch and begins a clean one. It does not destructively erase raw data. The data version is namespaced so future schema changes can be migrated or ignored without corrupting existing saves.

The pilot does not implement consolidation. Its storage format merely preserves the source material needed for it.

## Grounding and perception

Grounding starts as structured immutable facts. Prompt prose is derived from those facts afterward.

### Jake's live self state

Where available from existing authoritative services, the snapshot may include:

- Identity and authored profile
- Current dimension, biome, coarse location, and named base
- Time, weather, and coarse indoors/underground state
- Health, hunger, armor, held item, inventory pressure, and a bounded useful-resource summary
- Current behavior mode
- Active task, target, progress, queue state, and latest result
- Recruitment, permanence, home, and ownership state
- Current communication channel

The soul system must read these values from existing Frens sources of truth rather than maintaining parallel mutable copies.

### Player state

When the player is local and plausibly observable, the snapshot may include their name, coarse distance and direction, visible condition, held equipment, and obvious states such as sleeping, fighting, or being injured.

When communication is remote, current player-surrounding information is absent. The prompt explicitly says Jake cannot see the player or their environment.

### Event journal

The event journal records factual, bounded observations. A representative record is:

```text
type: TASK_COMPLETED
actor: <Jake bot UUID>
participants: [<player UUID>]
location: overworld / plains
facts: { skill: woodcut, collected: 14 }
witness: SELF
time: <world and wall-clock metadata>
salience: NORMAL
```

The initial vocabulary is intentionally narrow:

- Task start, completion, failure, pause, and cancellation
- Bot or owner damage when witnessed
- Combat start and end
- Death, respawn, sleep, and wake
- Dimension transitions
- Quest-stage changes
- Direct-conversation turns

The LLM does not write or reinterpret factual event records.

Current state is replaceable; recent events age out of prompts; raw events remain available for later consolidation. Player claims stay attributed as conversational claims and never become world facts. Unknown values are omitted or identified as unknown.

When prompt space is constrained, grounding is prioritized as follows:

1. Immediate danger and active task
2. Jake's physical state
3. Locally observable player state
4. Quest and relationship authority
5. Recent salient events
6. Environmental flavor

The prompt never receives an unbounded inventory dump, entity scan, NBT dump, path map, or debug state.

## Prompt construction

Jake has an authored profile asset containing voice, temperament, values, boundaries, established biography, relationship posture, and short speaking examples. The profile is bound to the selected bot UUID when its soul is activated.

The provider receives content in this order:

1. Soul-system contract and behavioral boundaries
2. Authored identity
3. Current authoritative Frens state
4. Bounded relationship history and recent witnessed events
5. A trailing present-moment snapshot
6. The player's current message

The system contract is stable and provider-neutral. The present-moment snapshot immediately precedes the player turn to preserve recency.

Authored instructions, structured game facts, remembered dialogue, and the current player message remain distinct message sections. Player-authored text and recalled text are always treated as untrusted conversational content; neither is interpolated into the system contract or parsed as provider instructions.

Existing mood state may influence tone, but the model cannot own or mutate that state. Scripted recruitment and quest state always wins over generated recollection.

## Dialogue contract

Jake should:

- Speak naturally in character without calling himself an AI or reciting status fields
- Express personality through judgment and word choice instead of repetitive catchphrases
- Form opinions, remember conversations, joke, disagree, and admit uncertainty
- Distinguish what the player claimed from what Jake witnessed
- Avoid inventing completed actions, observations, inventory, quest progress, or shared experiences
- Avoid claiming that he started or completed a command during the no-actions pilot
- Default to concise Minecraft-friendly replies while allowing more depth for substantive questions

Provider output is plain conversational text. Hidden reasoning, tool syntax, fake speaker labels, unsupported formatting, blank responses, and excessive length are rejected or sanitized. Validation never interprets prose as an action request.

## Provider architecture

The new provider contract supports ordered role-based messages and returns a typed result rather than a bare string.

A request includes:

- Ordered messages
- Model and generation settings
- Conversation and correlation IDs
- Timeout and cancellation signal
- Requested output mode
- Optional streaming preference when the adapter supports it

A result includes:

- Validatable text or a categorized failure
- Provider and model identifiers
- Elapsed and first-output timing when available
- Token or usage metadata when available

The first operational adapter targets the user's local model/offload setup. Existing Ollama or hosted-provider clients may later be wrapped if their behavior and security are suitable. The soul domain does not depend on a specific vendor.

Provider settings are server/operator controlled in the pilot. Per-player credentials and model selection are deferred.

### Privacy and fallback

- Credentials never enter world saves, transcripts, prompts, or routine logs.
- Hosted-provider configuration must clearly disclose that selected chat and game context leaves the machine.
- Existing credential storage must receive a security review before hosted soul providers are enabled.
- A local-provider failure never silently invokes a hosted provider.
- Any remote fallback chain must be explicitly configured, ordered, and consented to.
- Logs contain correlation data, stage timings, and categorized failures—not full prompts or private conversation text.

Timeouts, malformed responses, unavailable providers, and overload produce concise private status messages. Scripted Frens continues normally when the soul system is disabled or unavailable.

The pilot uses one configured local provider, one active generation at a time, no automatic fallback, and no action-capable structured output.

## Observability

Each accepted turn has a correlation ID recorded across these stages:

- Routing and authorization
- Reachability
- Snapshot completion
- Transcript append
- Queue wait
- Provider start, first output, and completion
- Validation
- Delivery recheck
- Delivery and outbound commit

Normal logs use identifiers, durations, queue depth, provider/model names, byte or token counts, and failure categories. Prompt and private-message contents require an explicit diagnostic mode and must still redact secrets.

Metrics must distinguish queue time, LLM generation time, validation time, delivery time, and—later—TTS time. This prevents slow audio or queueing from being misreported as model latency.

## Future extension boundaries

### Group chat

Group conversation has its own channel history. It does not copy every public line into each bot's private DM history.

A fresh authoritative roster is captured for every group turn. The recommended design uses one capped orchestration call that receives the participating profiles and returns a structured sequence of speaker IDs and lines. Frens verifies every speaker and limits response count and length before delivery.

The accepted player turn and successfully delivered bot lines enter the shared transcript. Rejected or undelivered generated lines do not. A participating bot may receive a bounded view of that shared transcript during future group turns without merging it into private relationship memory.

### Autonomous banter

Banter uses the group-channel path and is scheduled by deterministic Frens logic, not by the model. Eligibility requires explicit enablement, nearby qualified participants, suitable player presence, a quiet period, per-bot cooldowns, provider-budget availability, and the absence of danger or urgent transitions. The scene is cancelled if those facts become stale before delivery.

### Generated voice

Voice subscribes only after validated text is committed as spoken. Text remains canonical. TTS failure cannot block dialogue, memory, or gameplay. Local speech may become positional; remote DM voice requires separate explicit configuration. Audio is derived output and does not become memory.

### Consolidation

A later offline process may derive bounded relationship summaries, preferences, recurring concerns, and unfinished topics from raw transcripts and factual events.

Consolidation output is versioned, replaceable, source-linked, and non-authoritative. It cannot rewrite authored identity or game truth. It runs at safe lifecycle points outside active chat turns and can be disabled or rebuilt without losing raw history.

### LLM-triggered actions

Actions never come from parsing Jake's conversational response. A separate structured classifier may eventually propose an allowlisted action. Deterministic Frens code then verifies ownership, action permission, target identity, current preconditions, availability, and any required confirmation.

Only the existing action implementation may mutate gameplay. Conversation describes a result only after an actual action receipt exists. Classifier errors fail closed into ordinary conversation.

## Checkpoint and rollout

Before runtime implementation begins:

- Create and push an annotated tag `checkpoint/pre-souls-2026-08-23` at known-good commit `d6f5b41`.
- Create a dedicated `feature/soul-communication` branch.
- Keep a master `souls.enabled=false` feature flag.
- Avoid destructive save migration.

Implementation is divided into separately verified and committed phases, each touching no more than five files and requiring approval before the next phase:

1. **Foundation:** communication types, versioned storage, identity, authorization, reachability, and sanitized telemetry.
2. **Provider and scheduler:** provider contract, initial local adapter, strict ordering, bounded queues, timeout, cancellation, and categorized failures.
3. **Jake DM pilot:** authored profile, owner-only activation, existing DM routing, persistent transcript, and reset/archive control.
4. **Grounding:** immutable snapshots, local/remote perception, event journal, and authoritative task/survival/quest facts.
5. **Playtest hardening:** restart and failure testing, prompt iteration, latency review, and security review.

Group chat, banter, voice, consolidation, and actions are separate later projects, each requiring its own approved design or plan before implementation.

## Pilot acceptance criteria

- With souls disabled, existing Frens behavior is observably unchanged.
- Only Jake's authorized owner/operator can write to his private soul conversation.
- Rapid messages remain ordered and cannot produce overlapping replies from Jake.
- Provider work and transcript I/O never block the Minecraft server thread.
- Provider failure, timeout, overload, malformed output, reset, disconnect, bot removal, and world closure cannot freeze the game.
- Local-provider failure never sends data to a hosted service.
- Jake retains direct history across restart and dimension changes.
- Reset archives the current epoch and prevents stale in-flight replies from entering the new one.
- Remote Jake receives no unwitnessed player-surrounding information.
- Generated text cannot invoke actions or privileged bot command sources.
- Quest truth, bot state, and scripted behavior remain authoritative.
- Undelivered generated text is not stored as spoken dialogue.
- Credentials and full private prompts do not appear in saves or routine logs.
- Provider-independent pieces receive focused automated coverage where practical.
- `./gradlew build -x test` succeeds.
- A documented manual 1.21.11 test matrix passes before deployment.

## Manual pilot test matrix

At minimum, in-game verification covers:

- Souls disabled, including existing scripted chat and commands
- Jake activation and deactivation
- Authorized local DM
- Authorized remote DM through each supported communication method
- Unreachable DM with no provider request
- Unauthorized sender attempting to address Jake
- Rapid consecutive messages and queue saturation
- Local provider unavailable before a request
- Timeout and malformed response
- Player disconnect during generation
- Jake removal or death during generation
- Conversation reset during generation
- World save, close, reopen, and continued conversation
- Jake and player changing dimensions independently
- Remote conversation while the player encounters information Jake cannot witness
- Existing deterministic `zzz`, quest, confirmation, and explicit-command routing
- Verification that generated prose cannot dispatch an action
- Log review for latency separation, correlation IDs, and secret/private-text leakage

## Rejected alternatives

### Extend the legacy orchestrator directly

Rejected because its prompt, memory, and action assumptions conflate concerns that the soul pilot must keep separate. Retrofitting all boundaries in place would increase regression risk to working Frens behavior.

### Reuse the Dreamsleeve daemon

Rejected because Frens must remain independently installable and maintain a game-native authority model. Dreamsleeve is a reference implementation, not a runtime dependency.

### Make the dialogue panel the primary surface

Rejected for the pilot because the existing panel is designed around scripted quest topics, has no free-form input, and keeps only a client-session log. Direct chat already supplies a robust per-player surface.

### Give the pilot action access

Rejected because conversation quality, grounding, persistence, authorization, and concurrency need to be proven before generated intent is allowed near gameplay mutation.

### Launch one call per bot for group chat

Rejected as the default group design because competing independent calls are slower, costlier, and less coherent. A capped structured group orchestration call provides a better controllable starting point.

### Automatically fall back from local to hosted generation

Rejected because it can transmit private conversation and game context without informed consent.

## Rollback

The immediate rollback is to disable `souls.enabled`, which restores scripted Frens without deleting soul data. Runtime code remains isolated from existing behavior paths.

If code rollback is necessary, the pushed annotated checkpoint tag identifies the exact pre-soul runtime baseline. Because soul storage is versioned and namespaced, an older build ignores it rather than migrating or corrupting it.

## Implementation gate

This document is the design contract, not authorization to write runtime code. After review and approval, the next step is a file-by-file implementation plan. Runtime work begins only after that plan is separately approved.
