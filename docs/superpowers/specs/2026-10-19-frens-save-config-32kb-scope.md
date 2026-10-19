# Scope: settings save disconnects past 32 KB (finding 15)

Date: 2026-10-19. Status: scoped, not shipped. Source: finding 15 of
`docs/testing/FIELD_0_6AI_SANDBOX_2026-10-18.md` (sandbox, 1.1.271, 21:15:43).

## What happens

Every client settings save (Bot Controls toggles and bulk buttons, the Rate... screen, text categories, the pathfinder
toggle) sends `ConfigJsonUtil.configToJson()` in one `frens:save_config` payload. The codec caps the string at 32767
chars, and vanilla also caps any client-to-server custom payload at 32767 bytes. Past that, netty throws
`EncoderException: String too big` and the client is disconnected ("Connection Lost"; in singleplayer the world closes).

## What actually crosses the wire

Only the `SharedConfig` allowlist, not the whole `settings.json5`
(`ConfigJsonUtil.java:38-43`, `SharedConfig.java:63-100`). The class comment already excludes skins, ownership, spawn
data, keys and host-local paths (`SharedConfig.java:14-16`). So `botSpawnPointsByWorld`, `botSkinSelections`,
`botOwnership` and the recruitment data never leave the machine. Trimming them changes nothing on the wire.

The one per-bot map that does cross is `botControlsByWorld` (`SharedConfig.java:51`, captured at `:98`). Measured
(read-only) on the files:

| File | settings.json5 compact | `botControlsByWorld` | aliases / entries / worlds | biggest one-world slice |
|---|---|---|---|---|
| sandbox `settings.json5.bak-pass3-bloat` | 72,674 | **34,086** | 46 / 103 / 20 | 5,362 (16 bots) |
| Nirn `1.21.11/.../settings.json5` | 10,153 | 3,266 | 3 / 10 / 5 | 999 (3 bots) |

The error said 34,859 chars. 34,086 for the map plus about 800 for the scalar fields matches it, so
`botControlsByWorld` is the whole problem. Each entry is about 330 chars, so the payload overflows at roughly
95-100 alias x world entries. Nirn's payload is about 4 KB today (not 10 KB: most of the file never syncs).

Every field in the allowlist is needed on the server: the save receiver applies them all
(`SharedConfig.applyTo`, `:103-160`). None can be dropped from the wire.

## Every config payload and its limit

| Payload | Direction | Codec limit | Registered | Past the limit |
|---|---|---|---|---|
| `SaveConfigPayload` | C2S | 32,767 (`SaveConfigPayload.java:13`) | `register` (`Frens.java:548`) | **disconnect** (finding 15) |
| `OpenConfigPayload` | S2C | 32,767 (`OpenConfigPayload.java:13`) | `register` (`Frens.java:549`) | same overflow on the server's encode; `/configMan` stops opening. Expected, not tested. The `catch` at `configNetworkManager.java:83` cannot catch it, because the encode runs later in the netty pipeline, so it may disconnect too |
| `ConfigSyncPayload` (`sendConfigSync`, `broadcastConfigSync`) | S2C | 262,144 (`ConfigSyncPayload.java:13`) | `register` (`Frens.java:550`) | safe until 262,144 chars. Vanilla allows about 1 MB server-to-client, so this direction is fine for now |

So `sendConfigSync` does not share the 32 KB limit. `/configMan`'s open packet does.

## Recommended fix (smallest safe)

Three lines plus a guard:

1. `SaveConfigPayload.java:13`: `new StringCodec(32767)` becomes `new StringCodec(262144)`, the same as `ConfigSyncPayload`.
2. `Frens.java:548`: `PayloadTypeRegistry.playC2S().register(...)` becomes
   `registerLarge(SaveConfigPayload.ID, SaveConfigPayload.CODEC, 1048576)`. **Step 1 alone does not fix it.** Vanilla
   rejects any client-to-server custom payload over 32,767 bytes whatever the string codec allows.
   Fabric networking 5.1.0 (in fabric-api 0.136.0) has `registerLarge`, which splits the payload into several packets
   (`FabricPacketSplitter`, `SAFE_C2S_SPLIT_SIZE = 32767`). The third argument is the largest whole payload in bytes
   (javadoc: "the maximum size of payload packet"). A 1 MB ceiling covers 262,144 chars even with multi-byte UTF-8.
   Both sides register in `Frens.onInitialize`, and client and server already have to run the same Frens build.
3. `OpenConfigPayload.java:13`: raise it to 262144 too. It is server-to-client and under 1 MB, so it does not need `registerLarge`.
4. Guard, so a future overflow never kicks the player. In `configNetworkManager.sendSaveConfigPacket` (`:40-53`),
   skip the send when `json.length()` is over the codec limit, log a WARN with the size, and tell the player
   once ("Settings too large to sync; saved locally"). The existing `catch (Throwable)` there cannot catch this,
   because `ClientPlayNetworking.send` encodes later in the pipeline. Do the same before the `OpenConfigPayload` send.

Security: non-op saves are refused only after the payload is decoded (`configNetworkManager.java:173`). The 1 MB
`registerLarge` ceiling limits how much a non-op can make the server decode. 1 MB per save is acceptable. Do not raise it further.

## Options considered and deferred

- **Send only the current world's slice of `botControlsByWorld`.** This would shrink the sandbox payload about 6x
  (34 KB down to 5.4 KB). But `SharedConfig.applyTo` replaces the whole map (`:149-154`, `live.clear(); live.putAll(staged)`),
  so a one-world map from a client would **wipe every other world's entries on the server**. It is only safe after
  a per-(alias, world) merge on the receiver, which also changes how a deleted bot's entries go away
  (`ManualConfig.java:1007` removes them server-side, and today the next full client save can bring them back,
  which is a separate small issue). Worth doing later as a traffic cut. Not the fix.
- **Skip the packet in singleplayer.** In singleplayer the client and the integrated server share `Frens.CONFIG`, so
  the packet is `capture(cfg).applyTo(cfg)` (`SharedConfig.java:110` already allows for that). Five of the six save
  sites send every time. Only `BotPlayerInventoryScreen.java:6577` checks `isRemoteAuthoritative()` first.
  Skipping the packet would also skip the receiver's side effects: `PathFinder.ACTIVE` mirror (`configNetworkManager.java:199`),
  soul reload (`:201-210`), `refreshBotPreferences` (`:212`) and the broadcast (`:216`). This option is open only after
  each save site is traced and shown to run those itself. It would hide the bug in singleplayer, but LAN and server
  saves would still hit it. Not the fix.
- **Drop the deprecated `autoSpawn` from `BotControlSettings`** (`ManualConfig.java:1338`). Saves about 5% per entry. Cosmetic.

## Regression test

Minecraft classes are on the test classpath (for example `FollowDoorPlanRulesTest` imports `net.minecraft`), so a real codec
round trip is possible. Extend `src/test/java/net/wcfcarolina13/network/ConfigJsonUtilRoundTripTest.java`, or add
`SaveConfigPayloadSizeTest`:

1. Build a `ManualConfig` through the private constructor, as `newRealConfig()` does in the existing test.
   Add 46 aliases x 3 worlds with `getOrCreateBotControl(alias, world)`, mixing in non-default values. That gives
   138 entries, about 45 KB, which is over 32,767.
2. `String json = ConfigJsonUtil.configToJson(cfg)`. Assert `json.length() > 32767`.
3. Codec round trip: `PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer())`,
   `SaveConfigPayload.CODEC.encode(buf, new SaveConfigPayload(json))`, decode, and assert the strings are equal. This
   fails today with `EncoderException`. Do the same for `OpenConfigPayload.CODEC`.
4. Apply round trip: `ConfigJsonUtil.applyConfigJson(decoded, fresh)`. Assert `fresh.getBotControlsByWorld()` equals the source,
   with each entry's flags preserved, using spot checks like the existing test.
5. Guard test: a pure helper (for example `ConfigSaveReloadPolicy.fitsSavePayload(String)` or similar) is true at
   262,144 chars and false one char past it.

The test cannot cover `registerLarge`, because the Fabric registry and splitter are not live in JUnit. The field
check has to cover it.

## Field check for the release

In the sandbox only (check `.superpowers/SANDBOX.lock`): put `settings.json5.bak-pass3-bloat` back as the sandbox
config, open a world, move the Rate... slider and toggle a Bot Controls switch. Expected: no disconnect, the value
persists after a relaunch, and the log has no `EncoderException`. Then run `/configMan` with the same file and check
that Bot Controls opens. Put the trimmed file back afterwards.

## Files the fix touches

`network/SaveConfigPayload.java`, `network/OpenConfigPayload.java`, `Frens.java` (one registration line),
`network/configNetworkManager.java` (guard), plus the test. Disjoint from other open work. One implementer is enough.
