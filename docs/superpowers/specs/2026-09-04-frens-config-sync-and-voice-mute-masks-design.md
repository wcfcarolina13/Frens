# Config sync + per-player voice mute masks — design (1.1.203)

Date: 2026-09-04. Status: approved (Bradley pre-approved the recommended options for this build).

## Problem

`configNetworkManager` and `ConfigJsonUtil.configToJson/applyConfigJson` are compile-time no-op
stubs. Every screen that edits global config (`BotControlScreen`, `DialogueSettingsScreen`,
`ConfigureVoiceCategoriesScreen`, `ConfigureTextCategoriesScreen`, `ConfigManager`) mutates the
client's `Frens.CONFIG`, saves it to the client's `settings.json5`, then calls the no-op. In
single-player the client and integrated server share one JVM and one `Frens.CONFIG`, so it works.
On a dedicated server nothing reaches the server: the Voice toggle, dialogue rates, soul toggles
and per-bot controls are all single-player-only. Voice-category muting additionally mutes for
everyone because `BotDialoguePlayer.playSound` broadcasts with `world.playSoundFromEntity`.

## Goals

1. Server-authoritative sync of the *shared* config subset: S2C on join and after every
   accepted change; C2S save from the existing screens, gated to operators.
2. Per-player voice mute masks: each client's category mask reaches the server and is consulted
   per recipient; the server's own `settings.json5` mask stays the admin baseline.
3. Unit tests for the JSON round-trip and for the mute decision.

Non-goals: syncing API keys, LLM model lists, skins, ownership, spawn points, soul-voice engine
paths (all host-local or secret); text-category masks per player (text muting stays global).

## Approaches considered

- **A. Serialize the whole `ManualConfig`** — smallest diff, but ships API keys to every client
  and clobbers host-local paths. Rejected.
- **B. Explicit shared subset DTO (recommended)** — a `SharedConfig` snapshot with an allowlist of
  fields, boxed so absent fields merge as "keep". Bounded payload, no secrets, forward-compatible.
- **C. Per-field payloads** — one packet per toggle. Most precise, but ~25 payload classes for
  what is one logical "global settings" document. Rejected for size.

## Design

### 1. `SharedConfig` (new, `FilingSystem/SharedConfig.java`)

Plain Gson DTO, no Minecraft imports. Boxed fields (`Boolean`, `Integer`, `List<String>`, map)
so a field missing from the JSON leaves the target untouched:

```
defaultLlmWorldEnabled, textDialogueEnabled, voicedDialogueEnabled, mutedTextCategories,
gameplayTipsEnabled, idleHobbiesAnywhereEnabled, baritonePathfinderEnabled,
fortifyForcePlaceEnabled, globalTeleportDuringSkills (nullable tri-state already),
fortBufferRadius, undergroundLingerMinutes, undergroundProximityBlocks, survivalRecruitmentMode,
soulsEnabled, soulPartyEnabled, soulBanterEnabled, soulLocalChatEnabled, soulBanterActiveEnabled,
soulMemoryDigestEnabled, dialogueScriptedRate, soulBanterIdleRate, soulBanterActiveRate,
soulLocalRate, soulVoiceEnabled, botControlsByWorld (Map<String, Map<String, BotControlSettings>>)
```

`static SharedConfig capture(ManualConfig)` and `void applyTo(ManualConfig)`. `applyTo` uses the
existing public setters; `botControlsByWorld` replaces the live map's contents (clear + putAll).
`mutedVoiceCategories` is deliberately NOT in the snapshot (see §3).

### 2. `ConfigJsonUtil` (rewritten)

- `String configToJson(ManualConfig)` → Gson of `SharedConfig.capture(cfg)`; the no-arg overload
  uses `Frens.CONFIG` and returns `"{}"` when null.
- `boolean applyConfigJson(String json, ManualConfig target)` → parses to `SharedConfig`, calls
  `applyTo`, returns false (and logs WARN, changes nothing) on malformed JSON or null target.
  One-arg overload targets `Frens.CONFIG`.
- `isValidJson` becomes a real Gson parse check.

### 3. Network wiring

New S2C `ConfigSyncPayload(String configJson)` (`frens:config_sync`, string limit 262144).
Existing C2S `SaveConfigPayload` keeps its 32767 limit (vanilla C2S cap).

`configNetworkManager` becomes real:

- `sendSaveConfigPacket(json)` (client): if `ClientPlayNetworking.canSend(SaveConfigPayload.ID)`
  send it; otherwise log DEBUG. Safe to call from any client screen.
- `sendOpenConfigPacket(player)` (server): sends `OpenConfigPayload(configToJson())`.
- `registerServerReceivers()` (called once from `Frens.onInitialize`, replacing the three
  per-server-start stub calls, which would otherwise double-register on world reload):
  - `SaveConfigPayload`: sender must pass `Frens.hasBotCommandPermission`-equivalent operator
    check (`Frens.isOperator(player)` or permission level 2). Non-ops: WARN + reply with a fresh
    `ConfigSyncPayload` so their client reverts. Ops: on the server thread `applyConfigJson`,
    `CONFIG.save()`, `BotControlApplier.refreshBotPreferences(server)`, then broadcast
    `ConfigSyncPayload` to every non-bot player.
  - `SaveAPIKeyPayload` / `SaveCustomProviderPayload`: operator-only; write the key via the
    matching `ManualConfig` setter and save. Never echoed back to clients.
- `sendConfigSync(ServerPlayerEntity)` / `broadcastConfigSync(MinecraftServer)` helpers; bots
  (`createFakePlayer`) are skipped.

Join: in the existing `ServerPlayConnectionEvents.JOIN` block, real players get
`sendConfigSync(player)` next to `sendRecruitmentState`.

Client (`FrensClient`): receiver for `ConfigSyncPayload` applies on the client thread. The
`OpenConfigPayload` receiver moves its `applyConfigJson` inside `client.execute`.

Single-player: client and server share `Frens.CONFIG`; capture → apply onto the same object is a
self-merge and is harmless. Known limitation, documented in the changelog: on a dedicated server
the synced values live in the client's in-memory `Frens.CONFIG`, so a later client-side
`save()` writes the server's globals into that client's local `settings.json5`.

### 4. Per-player voice mute masks

- New C2S `VoiceMuteMaskPayload(List<String> mutedCategoryIds)` (`frens:voice_mute_mask`).
- `VoiceLineMuteService` gains an in-memory `ConcurrentHashMap<UUID, Set<String>>`:
  `setPlayerMask(UUID, Collection<String>)`, `clearPlayerMask(UUID)`, `playerMask(UUID)`.
  `isMuted(category, viewer)`: `viewer == null` → baseline only (server `Frens.CONFIG`);
  otherwise baseline OR `isMutedFor(category, viewer.getUuid())`. Baseline read is injected via
  a `Supplier<ManualConfig>` (default `() -> Frens.CONFIG`) so tests never touch `Frens`.
- Server: receiver stores the mask (on server thread); `DISCONNECT` clears it.
- Client: `ClientPlayConnectionEvents.JOIN` sends the local mask; `ConfigureVoiceCategoriesScreen`
  `persist()` saves locally and sends `VoiceMuteMaskPayload` instead of `sendSaveConfigPacket`.
  In single-player the client mask and the baseline are the same list, so behaviour is unchanged.
- `BotDialoguePlayer.playSound(bot, sound, category)`: replaces the broadcast with a loop over
  `world.getPlayers()` within vanilla's audible radius (16 blocks for volume ≤ 1, else
  16·volume), skipping bots and any viewer for whom `isMuted(category, viewer)` is true, sending
  `PlaySoundFromEntityS2CPacket(RegistryEntry.of(sound), SoundCategory.VOICE, bot, VOLUME, PITCH,
  world.getRandom().nextLong())` via `player.networkHandler.sendPacket`. Category `null`
  (`forcePlaySound`) sends to everyone in range. The early `isMuted(muteCategory, null)` gate in
  `playSoundInternal` stays as the baseline short-circuit.

### 5. Bounded items in the same build

- **Model-manager RAM warning**: extract `OllamaModelInstaller.ramShortfallGb(KnownModel,
  long totalRamBytes)` (returns 0 when unknown or sufficient) and use it in
  `SoulModelManagerScreen` for the existing red description note **and** for the Download button
  label (`"Download ⚠"`) so the warning is visible where the click happens. Unit-tested.
- **Dreamsleeve off macOS**: `SoulVoiceSettings.dreamsleeveSupportedOn(String osName)` (true only
  for `mac`/`darwin`), unit-tested. `SoulVoiceEngineScreen.EngineRow` gains `boolean supported`;
  unsupported rows render title/blurb in grey, blurb says "macOS only", button reads "macOS only"
  and is inactive.

## Testing

- `SharedConfigJsonRoundTripTest`: capture → JSON → apply onto a fresh config reproduces every
  field; JSON contains no `Key`/`apiKey`/`ollamaBaseUrl`/`botSkins` entries; malformed JSON
  returns false and leaves the target untouched; a JSON missing a field keeps the target's value;
  unknown fields are ignored; `botControlsByWorld` survives with nested settings.
- `VoiceLineMuteServiceTest`: baseline mute wins for every viewer; per-player mask mutes only that
  UUID; clear restores; null viewer ignores masks.
- `OllamaModelInstallerRamTest`, `SoulVoiceSettingsPlatformTest`.
- Full suite stays green (679 → 679 + new).
