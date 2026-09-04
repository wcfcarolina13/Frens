# Implementation plan — config sync + voice mute masks + RAM warning + Dreamsleeve grey-out (1.1.203)

Spec: `docs/superpowers/specs/2026-09-04-frens-config-sync-and-voice-mute-masks-design.md`.
Branch: `feat/1.1.203-config-sync-mute-masks`. Every task: tests first, `./gradlew build -x test`
for compile, `./gradlew test --tests '<class>'` for the new tests, one commit per task.
Threading rule: world/entity/config mutations on the server thread (`context.server().execute`).

## Task A — SharedConfig DTO + ConfigJsonUtil round-trip (pure logic)

Files: new `src/main/java/net/wcfcarolina13/FilingSystem/SharedConfig.java`;
rewrite `src/main/java/net/wcfcarolina13/network/ConfigJsonUtil.java`;
new `src/test/java/net/wcfcarolina13/network/ConfigJsonUtilRoundTripTest.java`.

1. Test first (construct `ManualConfig` via its private constructor with reflection, as
   `SoulFoundationTest.newRealConfig()` does): set every shared field to a non-default value,
   `configToJson(cfg)`, `applyConfigJson(json, fresh)`, assert equality per getter; assert the
   JSON string contains none of `openAIKey`, `claudeKey`, `geminiKey`, `grokKey`, `customApiKey`,
   `ollamaBaseUrl`, `botSkins`, `mutedVoiceCategories`; malformed JSON → `false`, target
   untouched; `"{}"` → true, target untouched; unknown field ignored; `botControlsByWorld` with
   one world/one bot round-trips `isVoicedDialogue`/`isFollowTeleport`.
2. Implement `SharedConfig` with boxed fields + `capture` + `applyTo` (use existing setters;
   grep each `setX` exists — add a setter only if missing, matching neighbours).
3. Implement `ConfigJsonUtil` per spec §2 (Gson, WARN on parse failure, `"{}"` when null).
4. Commit `feat: SharedConfig snapshot + real ConfigJsonUtil round-trip`.

## Task B — network wiring (depends on A)

Files: new `network/ConfigSyncPayload.java`; rewrite `network/configNetworkManager.java`;
`Frens.java` (payload registration ~L497, remove the 3 stub calls ~L752, JOIN block ~L915);
`FrensClient.java` (~L761 receiver + new `ConfigSyncPayload` receiver).

1. `ConfigSyncPayload(String configJson)`, id `frens:config_sync`, `StringCodec(262144)`;
   register `playS2C` next to `OpenConfigPayload`.
2. `configNetworkManager`: `sendSaveConfigPacket` (client, `canSend` guard),
   `sendOpenConfigPacket`, `sendConfigSync(player)`, `broadcastConfigSync(server)`,
   `registerServerReceivers()` with the three C2S receivers (operator gate via
   `Frens.isOperator(player)`; on reject WARN + `sendConfigSync(sender)`; on accept apply/save/
   `BotControlApplier.refreshBotPreferences(server)`/broadcast — all inside
   `context.server().execute`). API-key receivers: operator-only, setter + save, no echo.
   Keep the old three method names as thin no-arg delegates only if anything else calls them
   (grep first; the spec says replace the calls in `Frens.java`).
3. `Frens.onInitialize`: call `configNetworkManager.registerServerReceivers()` once, near the
   other `registerGlobalReceiver` blocks. JOIN: `if (!(player instanceof createFakePlayer))
   configNetworkManager.sendConfigSync(player);`.
4. `FrensClient`: `ConfigSyncPayload` receiver → `context.client().execute(() ->
   ConfigJsonUtil.applyConfigJson(payload.configJson()))`; move the `OpenConfigPayload`
   apply inside its `client.execute`.
5. Build, commit `feat: real config sync — S2C on join/change, operator-gated C2S save`.

## Task C — per-player voice mute masks (independent of B; same payload pattern)

Files: new `network/VoiceMuteMaskPayload.java`; `ChatUtils/VoiceLineMuteService.java`;
`ChatUtils/BotDialoguePlayer.java` (`playSound` ~L894, `playSoundInternal` ~L1002,
`forcePlaySound` ~L1127); `Frens.java` (register C2S + receiver + DISCONNECT clear);
`FrensClient.java` (`ClientPlayConnectionEvents.JOIN` send);
`GraphicalUserInterface/ConfigureVoiceCategoriesScreen.java` (`persist()` ~L102);
new `src/test/java/net/wcfcarolina13/ChatUtils/VoiceLineMuteServiceTest.java`.

1. Test first: `VoiceLineMuteService.setBaselineSupplier(() -> cfg)` (test seam; reset in
   `@AfterEach`), baseline mute mutes for any UUID; `setPlayerMask(u1, ["reactions"])` mutes
   `REACTIONS` for u1 only; `clearPlayerMask(u1)` restores; `isMutedFor(cat, null)` uses
   baseline only; null category → false.
2. Implement the service (ConcurrentHashMap, unmodifiable copies, `isMuted(cat, viewer)` =
   `isMutedFor(cat, viewer == null ? null : viewer.getUuid())`).
3. Payload: `record VoiceMuteMaskPayload(List<String> mutedCategoryIds)`; codec via
   `PacketCodecs.collection(ArrayList::new, PacketCodecs.STRING)` (verify the exact 1.21.11
   helper name with javap on `net.minecraft.network.codec.PacketCodecs` before writing it).
   Validate ids server-side against `VoiceLineCategory` ids; cap list at the enum size.
4. `Frens`: register C2S, receiver stores on server thread, DISCONNECT clears. `FrensClient`:
   JOIN sends `Frens.CONFIG.getMutedVoiceCategories()` when `canSend`.
5. `BotDialoguePlayer`: `playSound(bot, sound, VoiceLineCategory category)` per spec §4
   (radius = `VOLUME > 1 ? 16*VOLUME : 16`, squared compare, skip `createFakePlayer`, skip
   muted viewers, `PlaySoundFromEntityS2CPacket` per recipient). `playSoundInternal` passes
   `muteCategory`; `forcePlaySound` passes null. Keep the INFO log; add a DEBUG count of
   recipients/skipped.
6. `ConfigureVoiceCategoriesScreen.persist()`: `save()` then
   `configNetworkManager.sendVoiceMuteMask(Frens.CONFIG.getMutedVoiceCategories())` (add that
   client helper to `configNetworkManager` — `canSend` guard).
7. Build, run test, commit `feat: per-player voice mute masks with per-recipient sound send`.

## Task D — RAM warning + Dreamsleeve grey-out (independent)

Files: `GameAI/souls/OllamaModelInstaller.java`, `GraphicalUserInterface/SoulModelManagerScreen.java`,
`GameAI/souls/voice/SoulVoiceSettings.java`, `GraphicalUserInterface/SoulVoiceEngineScreen.java`;
tests `GameAI/souls/OllamaModelInstallerRamTest.java`, `GameAI/souls/voice/SoulVoiceSettingsPlatformTest.java`.

1. Tests: `ramShortfallGb(model(12 GB), 8 GiB) == 4`, `(…, -1) == 0`, `(…, 16 GiB) == 0`;
   `dreamsleeveSupportedOn("Mac OS X")` true, `"Darwin"` true, `"Windows 11"` false,
   `"Linux"` false, null false.
2. Implement both static helpers. Screen: description note uses the helper; `buttonLabel`
   returns `"Download ⚠"` when shortfall > 0 and not installed; keep everything else.
3. `SoulVoiceEngineScreen`: `EngineRow` gets `boolean supported`; Dreamsleeve row uses
   `SoulVoiceSettings.dreamsleeveSupportedOn(System.getProperty("os.name"))`; unsupported →
   blurb `"§8macOS only — Qwen3-TTS server runs on Apple silicon."`, `available=false`,
   `installer=null`, button label `"macOS only"`, inactive; render title/blurb with
   `0xFF707070` when `!supported`.
4. Build, tests, commit `feat: model-manager RAM shortfall on the button; Dreamsleeve greyed off macOS`.

## Task E — integration (main session)

Whole-branch review (correctness + threading), `./gradlew build` full, bump `mod_version` →
`1.1.203-release+1.21.11`, changelog entry (prepend after header), `RALPH_TASK.md` handoff +
backlog checkboxes, merge `--no-ff` to main, push, deploy only if the pgrep check is clean.
