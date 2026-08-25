# Frens Soul Generated Voice (TTS) — Design

**Date:** 2026-08-24
**Status:** Approved design, pre-implementation
**Parent spec:** `2026-08-23-frens-soul-communication-design.md` — this document is the
standalone design the parent's "Generated voice" future-extension boundary requires.

## Overview

Jake's validated soul replies gain a spoken voice. The server synthesizes audio locally with
Piper (CPU) the moment a reply's text is committed as spoken, ships the bytes to the one player
in the conversation, and the client plays them — positionally from Jake's body when the player
is near him, as a flat quieter "radio" voice for remote conversations.

Text remains canonical. Voice is a pure subscriber to committed dialogue: it starts only after
text delivery succeeds, and any failure anywhere in the voice path drops the audio silently
while dialogue, memory, and gameplay proceed untouched.

## Inherited constraints (from the parent spec)

- Voice subscribes only after validated text is committed as spoken.
- TTS failure cannot block dialogue, memory, or gameplay.
- Audio is derived output and never becomes memory.
- Explicit enablement — no voice merely because the architecture supports it.
- Metrics distinguish TTS time from queue/LLM/validation/delivery time.
- Local-first: no reply text leaves the machine for synthesis in v1.

## Decisions

| Question | Decision | Why |
|---|---|---|
| Engine | **Piper, CPU**, behind a `SoulVoiceEngine` interface | Zero GPU contention with the game and Ollama on the shared M2 Pro; small models; faster than realtime on CPU. Engine swap (e.g. Kokoro on MPS) stays possible without touching the pipeline. |
| Playback | **Positional + radio** | LOCAL reachability → 3D positional audio from Jake's entity (like the existing OGG lines). REMOTE → flat "radio" voice at reduced gain so long-range DMs stay voiced. |
| Timing | **Text first, voice follows** | Chat reply lands exactly as today; audio starts when synthesis finishes and is dropped past a timeout. Keeps the text path untouched and honors "TTS cannot block dialogue". |
| Hosting | **Mod-owned long-lived subprocess** | One Piper process owned by the mod: model load paid once, restarted on failure, killed on server stop. No standing daemon on the machine. |
| Transport | **Server synthesizes → chunked S2C payload → client OpenAL** | Works identically in singleplayer and on dedicated servers; clients install nothing; vanilla/older clients ignore the payload and still get text. Client-side synthesis (couples clients to host tooling) and shared-file handoff (single-machine only) were rejected. |

## Architecture

### Server components (`GameAI/souls/voice/`)

- **`SoulVoiceService`** — the subscriber. Hooked at the commit-spoken point in
  `SoulConversationService`: a reply is offered to the voice service only after its text
  delivery future completes `true`. Owns a single-thread worker executor and the engine.
  API: fire-and-forget `speak(turn, token, text)`; never throws into the caller.
- **`SoulVoiceEngine`** (interface) + **`PiperVoiceEngine`** — one long-lived Piper subprocess
  using per-utterance file output for framing: write one line to stdin, read the emitted WAV
  filename from stdout, load the bytes, delete the file. Exact CLI flags are verified against
  the installed Piper binary at implementation time (never assumed from memory). The synth
  request carries a **voice id** resolved from the bot's soul profile (see Future extensions);
  v1 resolves every profile to the single configured Jake voice model.
- **`SoulVoiceSanitizer`** (pure) — strips formatting/markup, collapses whitespace, truncates
  at a sentence boundary past `voiceMaxChars`, and rejects empty results.
- **`SoulVoicePayload`** (S2C custom payload) — correlationId (the turn's routingId, so
  `[souls] tts` joins the turn's log chain), bot UUID, mode (`POSITIONAL` | `RADIO`), PCM
  format fields (sample rate, channels, bits), chunk index/count, ≤32 KB of audio bytes per
  chunk. Sent only to the conversation's player.

### Client components

- **`SoulVoiceClientPlayer`** — reassembles chunks by correlationId; on completion decodes the
  WAV and plays it through OpenAL directly (registered `SoundEvent`s are build-time-static and
  cannot carry dynamic audio):
  - `POSITIONAL`: a 3D source pinned to Jake's entity, position updated per client tick while
    the line plays; standard distance attenuation.
  - `RADIO`: a listener-relative source at `voiceRadioGain`.
  - Gain respects master volume and the players sound category.
  - One active voice per bot: a new line stops the previous one.
  - Incomplete chunk sets are discarded after 10 seconds.

## Data flow

```
commitSpoken (text delivered=true)
  → SoulVoiceService.speak            [worker thread]
      → sanitize (pure)               skip if empty/oversized-after-truncation
      → PiperVoiceEngine.synthesize   timeout voiceSynthTimeoutMs
      → chunk WAV bytes (≤32 KB)
  → server.execute: send SoulVoicePayload chunks to the conversation's player
  → client: reassemble → decode → OpenAL source (mode per payload)
```

Reachability at delivery time picks the mode: `LOCAL` → `POSITIONAL`, `REMOTE` → `RADIO`. If
the client cannot resolve Jake's entity for a positional line, it falls back to radio playback.
Whisper-surface turns are voiced identically — same DIRECT channel, same path.

## Gating and configuration

All settings live with the existing soul settings:

| Key | Default | Meaning |
|---|---|---|
| `voiceEnabled` | `false` | Master voice switch — explicit enablement per parent spec. |
| `piperBinary` | `""` | Path to the piper executable. |
| `piperVoiceModel` | `""` | Path to Jake's `.onnx` voice model. |
| `voiceMaxChars` | `400` | Sentence-boundary truncation limit for synthesis input. |
| `voiceSynthTimeoutMs` | `8000` | Per-line synthesis deadline; past it the line is dropped. |
| `voiceRadioGain` | `0.6` | Gain for REMOTE radio playback. |

Commands: `/bot soul voice on|off|status`. `on` validates the binary and model paths up front
and reports concrete errors; `status` reports enabled state, engine liveness, and last synth
outcome. Voice applies only to DIRECT soul replies (chat and whisper surfaces).

## Failure handling and lifecycle

- **Any voice-path failure drops audio only.** Text delivery has already happened; nothing in
  the voice path can retroactively fail a turn.
- **Engine crash:** restart with capped backoff; after repeated failures inside a window, the
  feature disables itself with one WARN (re-enabled via `/bot soul voice on` or settings
  reload).
- **Synthesis timeout:** the in-flight line is dropped and the subprocess is restarted (a hung
  engine is not trusted to recover).
- **Queue discipline:** single worker, queue capped at 4; overflow lines are dropped with a
  log line, never blocking the caller.
- **Server stop:** the subprocess is killed in the SERVER_STOPPING handler alongside the mod's
  other executors.
- **Client:** unknown/newer payload versions are ignored; a missing entity downgrades
  positional to radio; stale partial chunk sets are GC'd.

## Threading

Synthesis and file I/O run entirely on the voice worker. Payload sends are scheduled via
`server.execute`. All OpenAL calls happen on the client thread; source position updates hook
the client tick. Nothing in this feature blocks the server thread.

## Observability

One INFO line per voiced turn on the `frens.souls` logger:

```
[souls] tts correlationId=<routingId> outcome=<spoken|skipped-…|failed-…> synthMs= bytes= chunks=
```

`synthMs` is engine time only, kept distinct from the turn's existing queue/LLM/validation/
delivery timings per the parent spec's metrics rule. Content is never logged.

## Testing

Pure unit tests only, per repo convention (no subprocesses, no Minecraft server):

- Sanitizer: formatting stripped, whitespace collapsed, sentence-boundary truncation, empty →
  skip.
- Chunk math: sizes, counts, round-trip reassembly, stale-set expiry policy.
- Gating decision table: enabled × delivered × reachability → `POSITIONAL` / `RADIO` / skip.
- Restart/backoff policy: failure counting, window reset, self-disable threshold.

Live Piper behavior, positional playback, and volume interaction are manual runbook cases
added to `docs/testing/SOUL_COMMUNICATION_PILOT.md`.

## Out of scope (v1)

- Sentence streaming / partial-line playback.
- Voices for bots other than Jake (the per-profile voice-id seam exists, but v1 ships one
  authored voice).
- Voicing the scripted OGG dialogue lines (that system stays authoritative and untouched).
- Group/banter voice, microphone input (STT), hosted TTS.

## Future extension boundaries

- **Anchored personal voices.** Users will eventually anchor their own voice models per bot —
  preferred once multiple bots (or, e.g., a female bot) exist, so companions stop sharing one
  voice. The v1 seam for this is the per-profile voice-id resolution in `SoulVoiceEngine`:
  extending it means mapping profile → voice model, not restructuring the pipeline. Voice
  models remain local files chosen by the user.
- **Fine-tuned TTS via paid subscription.** A future paid tier may offer higher-quality or
  fine-tuned voices served remotely. That is a hosted-provider path: it inherits the parent
  spec's hosted-API posture and MUST pass the pending hosted-API security review before any
  design work sends reply text off-machine. Nothing in v1 may silently fall back to a remote
  voice.
- **Streaming synthesis** (sentence-by-sentence playback) as a latency polish once v1
  end-to-end timing is measured in real play.

## Acceptance criteria

- With `voiceEnabled=false` (default), behavior is byte-identical to today.
- With voice on: a DM near Jake produces his reply in chat first, then spoken audio from his
  position; walking while he speaks keeps the audio anchored to him.
- A REMOTE DM produces the radio voice at reduced gain.
- Killing the piper process mid-session: dialogue continues in text, one WARN appears, voice
  recovers or self-disables per the backoff policy.
- A synthesis slower than `voiceSynthTimeoutMs` drops that line's audio only.
- `[souls] tts` lines share the turn's routingId with routing/turn/knowledge/delivery lines.
- The full test suite stays green; no new test touches a subprocess or live server.
