package net.wcfcarolina13.GameAI.services;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.*;

/**
 * Manages bot player skins.  Each skin is a <em>preset</em> backed by a real
 * {@code textures.minecraft.net} URL — clients download the texture from
 * Mojang's CDN automatically.
 *
 * <h3>Spawn flow</h3>
 * {@link #applySkinToProfile(GameProfile, String)} enriches the GameProfile
 * <em>before</em> {@code PlayerManager.onPlayerConnect}, so the very first
 * {@code PlayerListS2CPacket} already carries the skin.
 *
 * <h3>Runtime change flow</h3>
 * {@link #changeSkin(MinecraftServer, ServerPlayerEntity, String)} swaps the
 * texture property, then does a player-list remove → re-add → entity
 * respawn so all clients see the change immediately.
 */
public final class BotSkinService {
    private static final Logger LOGGER = LoggerFactory.getLogger("BotSkinService");
    public static final String SAFE_PRESET_ID = "steve";
    private static final int MAX_CUSTOM_URL_LENGTH = 512;

    private BotSkinService() {}

    public enum SkinSource {
        PRESET("preset"),
        CUSTOM_URL("custom_url");

        private final String wireId;

        SkinSource(String wireId) {
            this.wireId = wireId;
        }

        public String wireId() {
            return wireId;
        }

        public static SkinSource fromWire(String wireValue) {
            String normalized = wireValue == null ? "" : wireValue.trim().toLowerCase(Locale.ROOT);
            return "custom_url".equals(normalized) ? CUSTOM_URL : PRESET;
        }
    }

    public record SkinSelection(
            SkinSource source,
            String value,
            String lastSetByUuid,
            String lastSetByName,
            boolean lastSetByAdmin,
            long lastSetAtEpochMs
    ) {
        public SkinSelection normalized() {
            SkinSource normalizedSource = source == null ? SkinSource.PRESET : source;
            String normalizedValue = value == null ? "" : value.trim();
            if (normalizedSource == SkinSource.PRESET && normalizedValue.isBlank()) {
                normalizedValue = SAFE_PRESET_ID;
            }
            return new SkinSelection(
                    normalizedSource,
                    normalizedValue,
                    (lastSetByUuid == null || lastSetByUuid.isBlank()) ? null : lastSetByUuid.trim(),
                    (lastSetByName == null || lastSetByName.isBlank()) ? null : lastSetByName.trim(),
                    lastSetByAdmin,
                    Math.max(0L, lastSetAtEpochMs)
            );
        }

        public boolean isPreset() {
            return source == SkinSource.PRESET;
        }

        public boolean isCustomUrl() {
            return source == SkinSource.CUSTOM_URL;
        }

        public static SkinSelection preset(String presetId) {
            return new SkinSelection(SkinSource.PRESET, presetId, null, null, false, System.currentTimeMillis());
        }

        public static SkinSelection customUrl(String textureUrl, String actorUuid, String actorName, boolean actorIsAdmin) {
            return new SkinSelection(SkinSource.CUSTOM_URL, textureUrl, actorUuid, actorName, actorIsAdmin, System.currentTimeMillis());
        }
    }

    // ── Skin preset catalogue ───────────────────────────────────────────

    /**
     * One curated skin from a real Mojang profile.  The URL is hosted on
     * {@code textures.minecraft.net} so the vanilla client trusts it.
     */
    public record SkinPreset(String id, String displayName, String textureUrl, boolean slim) {}

    /** All available presets, in display order. */
    private static final List<SkinPreset> PRESETS = List.of(
        // ── Classic ──
        new SkinPreset("steve",       "Steve",       "http://textures.minecraft.net/texture/60a5bd016b3c9a1b9272e4929e30827a67be4ebb219017adbbc4a4d22ebd5b1",  false),
        new SkinPreset("alex",        "Alex",        "http://textures.minecraft.net/texture/46acd06e8483b176e8ea39fc12fe105eb3a2a4970f5100057e9d84d4b60bdfa7",  true),
        // ── Notables ──
        new SkinPreset("notch",       "Notch",       "http://textures.minecraft.net/texture/292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680",  false),
        new SkinPreset("jeb",         "Jeb",         "http://textures.minecraft.net/texture/7fd9ba42a7c81eeea22f1524271ae85a8e045ce0af5a6ae16c6406ae917e68b5",  false),
        new SkinPreset("dinnerbone",  "Dinnerbone",  "http://textures.minecraft.net/texture/50c410fad8d9d8825ad56b0e443e2777a6b46bfa20dacd1d2f55edc71fbeb06d",  false),
        // ── Diverse defaults (1.19.3+) ──
        new SkinPreset("ari",         "Ari",         "http://textures.minecraft.net/texture/e995383b99d68ce9da9b6dc4509941934d4897636eeeb0f62d1c3e54edc41965",  false),
        new SkinPreset("zuri",        "Zuri",        "http://textures.minecraft.net/texture/49c0ec97d5dff9efb0abc4507e283b81e6028ef48fe7b6eb05235f0557062c21",  false),
        new SkinPreset("noor",        "Noor",        "http://textures.minecraft.net/texture/f896cf354b2c1c6f393d67dbf1ec26d7ae8b2f8a354cdd83416d558a60534c02",  false),
        new SkinPreset("kai",         "Kai",         "http://textures.minecraft.net/texture/68fe08b8ef31dc1f711c00efe6eefde33b65b259fec10eae76cc31e54aba9c5c",  true),
        new SkinPreset("makena",      "Makena",      "http://textures.minecraft.net/texture/3ee3f2fa8163bab44a7eefd58bccb339cc8089e27847178d1439ccfc6a3f894c",  true),
        new SkinPreset("efe",         "Efe",         "http://textures.minecraft.net/texture/db7f338eaaa8b56b2978b14aa395f33e9c4b4e2663b6b8b2ab07218de5e31aec",  false),
        new SkinPreset("sunny",       "Sunny",       "http://textures.minecraft.net/texture/fb0b359f00d62a66da3e746fd12b1abee316fbc4f1afdd839546255009e22dcb",  false),
        // ── Mob-head style ──
        new SkinPreset("villager",    "Villager",    "http://textures.minecraft.net/texture/b4bd832813ac38e68648938d7a32f6ba29801aaf317404367f214b78b4d4754c",  false)
    );

    /** Unmodifiable view for UI enumeration. */
    public static List<SkinPreset> presets() {
        return PRESETS;
    }

    /** Look up a preset by its short id (case-insensitive). */
    public static SkinPreset presetById(String id) {
        if (id == null) return null;
        String lower = id.toLowerCase(Locale.ROOT);
        for (SkinPreset p : PRESETS) {
            if (p.id().equals(lower)) return p;
        }
        return null;
    }

    public static boolean isValidCustomTextureUrl(String textureUrl) {
        if (textureUrl == null) {
            return false;
        }
        String trimmed = textureUrl.trim();
        if (trimmed.isBlank() || trimmed.length() > MAX_CUSTOM_URL_LENGTH) {
            return false;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            String normalized = scheme.toLowerCase(Locale.ROOT);
            if (!normalized.equals("http") && !normalized.equals("https")) {
                return false;
            }
            return uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static SkinSelection fromConfigSelection(ManualConfig.BotSkinSelection cfg) {
        if (cfg == null) {
            return null;
        }
        return new SkinSelection(
                SkinSource.fromWire(cfg.getSource()),
                cfg.getValue(),
                cfg.getLastSetByUuid(),
                cfg.getLastSetByName(),
                cfg.isLastSetByAdmin(),
                cfg.getLastSetAtEpochMs()
        ).normalized();
    }

    private static ManualConfig.BotSkinSelection toConfigSelection(SkinSelection selection) {
        SkinSelection normalized = selection.normalized();
        return new ManualConfig.BotSkinSelection(
                normalized.source().wireId(),
                normalized.value(),
                normalized.lastSetByUuid(),
                normalized.lastSetByName(),
                normalized.lastSetByAdmin(),
                normalized.lastSetAtEpochMs()
        ).sanitized();
    }

    private static SkinSelection applyCustomPolicy(MinecraftServer server, SkinSelection selection) {
        SkinSelection normalized = selection.normalized();
        if (!normalized.isCustomUrl()) {
            return normalized;
        }
        boolean allowCustomSkins = SurvivalRecruitmentService.isAllowCustomSkins(server);
        if (allowCustomSkins || normalized.lastSetByAdmin()) {
            return normalized;
        }
        return new SkinSelection(
                SkinSource.PRESET,
                SAFE_PRESET_ID,
                normalized.lastSetByUuid(),
                normalized.lastSetByName(),
                normalized.lastSetByAdmin(),
                System.currentTimeMillis()
        ).normalized();
    }

    // ── Profile enrichment (pre-connect) ────────────────────────────────

    /**
     * Returns a <b>new</b> GameProfile with the {@code "textures"}
     * property set to the given preset.  The original profile is
     * not modified (GameProfile is a Java record with final fields).
     *
     * @param profile  the bot's current GameProfile
     * @param presetId preset short id (e.g. {@code "ari"})
     * @return new GameProfile with skin, or the original if preset unknown
     */
    public static GameProfile applySkinToProfile(GameProfile profile, String presetId) {
        SkinPreset preset = presetById(presetId);
        if (preset == null) return profile;
        return applyPresetToProfile(profile, preset);
    }

    public static GameProfile applySelectionToProfile(GameProfile profile, SkinSelection selection) {
        if (profile == null || selection == null) {
            return profile;
        }
        SkinSelection normalized = selection.normalized();
        if (normalized.isCustomUrl()) {
            if (!isValidCustomTextureUrl(normalized.value())) {
                return profile;
            }
            return applyCustomUrlToProfile(profile, normalized.value());
        }
        return applySkinToProfile(profile, normalized.value());
    }

    /** Returns a random preset id from the catalogue. */
    public static String randomPresetId() {
        return PRESETS.get(new Random().nextInt(PRESETS.size())).id();
    }

    /**
     * Creates a <b>new</b> {@link GameProfile} with the same UUID and name
     * as {@code profile} but with a fresh {@link com.mojang.authlib.properties.PropertyMap}
     * containing the skin texture property for {@code preset}.
     *
     * <p>GameProfile is a Java record in authlib 7 — its fields are
     * final, and Java 21 blocks {@code Unsafe.objectFieldOffset} on record
     * classes.  So we never mutate an existing profile; we always return a
     * brand-new one.
     */
    private static GameProfile applyPresetToProfile(GameProfile profile, SkinPreset preset) {
        String json = buildTexturesJson(profile, preset.textureUrl(), preset.slim());
        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        com.google.common.collect.LinkedHashMultimap<String, Property> mutable =
                com.google.common.collect.LinkedHashMultimap.create();

        // Copy any non-textures properties from the old map.
        try {
            for (var entry : profile.properties().entries()) {
                if (!"textures".equals(entry.getKey())) {
                    mutable.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception ignored) {
            // Old map might be in a weird state; carry on with just the new texture.
        }
        mutable.put("textures", new Property("textures", base64));

        // Construct a brand-new GameProfile (record) with the fresh property map.
        // PropertyMap's constructor freezes to ImmutableMultimap — that's fine.
        GameProfile newProfile = new GameProfile(
                profile.id(), profile.name(),
                new com.mojang.authlib.properties.PropertyMap(mutable));

        LOGGER.info("Built new GameProfile for {} with skin '{}'",
                newProfile.name(), preset.id());
        return newProfile;
    }

    private static GameProfile applyCustomUrlToProfile(GameProfile profile, String textureUrl) {
        String json = buildTexturesJson(profile, textureUrl, false);
        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        com.google.common.collect.LinkedHashMultimap<String, Property> mutable =
                com.google.common.collect.LinkedHashMultimap.create();
        try {
            for (var entry : profile.properties().entries()) {
                if (!"textures".equals(entry.getKey())) {
                    mutable.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception ignored) {
        }
        mutable.put("textures", new Property("textures", base64));

        GameProfile newProfile = new GameProfile(
                profile.id(), profile.name(),
                new com.mojang.authlib.properties.PropertyMap(mutable));

        LOGGER.info("Built new GameProfile for {} with custom_url skin", newProfile.name());
        return newProfile;
    }

    // ── PlayerEntity profile swap (Unsafe on regular class) ─────────────

    /**
     * Replaces the {@code gameProfile} field on a live {@link net.minecraft.entity.player.PlayerEntity}.
     * {@code PlayerEntity} is a regular (non-record) class, so
     * {@code Unsafe.objectFieldOffset} works normally.
     *
     * <p>At runtime the field may be named {@code "gameProfile"} (Yarn dev)
     * or {@code "field_7507"} (intermediary prod).  We try both.
     */
    private static void swapPlayerProfile(ServerPlayerEntity bot, GameProfile newProfile) {
        try {
            java.lang.reflect.Field f = null;
            // Try Yarn name first, then intermediary.
            for (String name : new String[]{"gameProfile", "field_7507"}) {
                try {
                    f = net.minecraft.entity.player.PlayerEntity.class.getDeclaredField(name);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            // Ultimate fallback: search by type.
            if (f == null) {
                for (java.lang.reflect.Field candidate :
                        net.minecraft.entity.player.PlayerEntity.class.getDeclaredFields()) {
                    if (candidate.getType() == GameProfile.class) {
                        f = candidate;
                        break;
                    }
                }
            }
            if (f == null) {
                LOGGER.error("Cannot find GameProfile field on PlayerEntity");
                return;
            }
            java.lang.reflect.Field unsafeField =
                    sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            long offset = unsafe.objectFieldOffset(f);
            unsafe.putObject(bot, offset, newProfile);
            LOGGER.info("Swapped GameProfile on PlayerEntity for '{}' (field: {})",
                    newProfile.name(), f.getName());
        } catch (Exception e) {
            LOGGER.error("Failed to swap GameProfile on PlayerEntity for '{}'",
                    newProfile.name(), e);
        }
    }

    // ── Runtime skin change (post-connect) ──────────────────────────────

    /**
     * Changes a live bot's skin.  The bot must already be online.
     * Sends player-list remove, updates profile properties, then re-adds
     * and re-spawns the entity so all clients see the change immediately.
     *
     * @return true if the skin was applied successfully
     */
    public static boolean changeSkin(MinecraftServer server, ServerPlayerEntity bot, String presetId) {
        return changeSkin(server, bot, SkinSelection.preset(presetId));
    }

    public static boolean changeSkin(MinecraftServer server, ServerPlayerEntity bot, SkinSelection selection) {
        if (server == null || bot == null || selection == null) return false;

        SkinSelection normalized = applyCustomPolicy(server, selection).normalized();
        GameProfile oldProfile = bot.getGameProfile();
        GameProfile newProfile;
        String appliedLabel;

        if (normalized.isCustomUrl()) {
            if (!isValidCustomTextureUrl(normalized.value())) {
                LOGGER.warn("Invalid custom skin URL '{}' for bot '{}'", normalized.value(), bot.getGameProfile().name());
                return false;
            }
            newProfile = applyCustomUrlToProfile(oldProfile, normalized.value());
            appliedLabel = "custom_url";
        } else {
            SkinPreset preset = presetById(normalized.value());
            if (preset == null) {
                LOGGER.warn("Unknown skin preset '{}' for bot '{}'", normalized.value(), bot.getGameProfile().name());
                return false;
            }
            newProfile = applyPresetToProfile(oldProfile, preset);
            appliedLabel = preset.id();
        }

        // 1. Remove from player list for all clients.
        server.getPlayerManager().sendToAll(
                new PlayerRemoveS2CPacket(List.of(oldProfile.id())));

        // 2. Remove entity from all clients.
        server.getPlayerManager().sendToAll(
                new EntitiesDestroyS2CPacket(bot.getId()));

        // 3. Swap the profile reference on PlayerEntity (regular class,
        //    Unsafe works on non-record fields).
        swapPlayerProfile(bot, newProfile);

        // 4. Schedule re-add + respawn for the NEXT server tick.
        server.execute(() -> {
            LOGGER.info("Skin refresh phase for '{}': sending ADD_PLAYER and retrack", bot.getGameProfile().name());

            server.getPlayerManager().sendToAll(
                    PlayerListS2CPacket.entryFromPlayer(List.of(bot)));

            retrackEntity(bot);

            persistSkinSelection(bot.getGameProfile().name(), normalized);

            LOGGER.info("Applied skin '{}' to bot '{}'", appliedLabel, bot.getGameProfile().name());
        });
        return true;
    }

    // ── Persistence (via ManualConfig) ──────────────────────────────────

    /** Gets the persisted skin preset id for a bot alias, or null. */
    public static String getSkin(String alias) {
        SkinSelection selection = getSkinSelection(alias);
        if (selection == null || !selection.isPreset()) {
            return null;
        }
        return selection.value();
    }

    /** Persists the skin choice for a bot alias. */
    public static void persistSkin(String alias, String presetId) {
        persistSkinSelection(alias, SkinSelection.preset(presetId));
    }

    public static SkinSelection getSkinSelection(String alias) {
        if (Frens.CONFIG == null || alias == null || alias.isBlank()) {
            return null;
        }
        ManualConfig.BotSkinSelection structured = Frens.CONFIG.getBotSkinSelection(alias);
        if (structured != null) {
            return fromConfigSelection(structured);
        }
        String legacyPreset = Frens.CONFIG.getBotSkin(alias);
        if (legacyPreset == null || legacyPreset.isBlank()) {
            return null;
        }
        return SkinSelection.preset(legacyPreset).normalized();
    }

    public static void persistSkinSelection(String alias, SkinSelection selection) {
        if (Frens.CONFIG == null || alias == null || alias.isBlank() || selection == null) return;
        Frens.CONFIG.setBotSkinSelection(alias, toConfigSelection(selection));
        Frens.CONFIG.save();
    }

    public static SkinSelection resolveSelectionForSpawn(MinecraftServer server, String alias) {
        SkinSelection existing = getSkinSelection(alias);
        SkinSelection resolved;
        if (existing == null) {
            resolved = SkinSelection.preset(randomPresetId()).normalized();
            persistSkinSelection(alias, resolved);
            return resolved;
        }
        resolved = applyCustomPolicy(server, existing).normalized();
        if (!resolved.equals(existing.normalized())) {
            persistSkinSelection(alias, resolved);
        }
        return resolved;
    }

    public static int reconcileNonAdminCustomSkinsToSafe(MinecraftServer server) {
        if (Frens.CONFIG == null) {
            return 0;
        }
        Map<String, ManualConfig.BotSkinSelection> selections = Frens.CONFIG.getBotSkinSelections();
        if (selections == null || selections.isEmpty()) {
            return 0;
        }

        int reverted = 0;
        List<Map.Entry<String, ManualConfig.BotSkinSelection>> snapshot = new ArrayList<>(selections.entrySet());
        for (Map.Entry<String, ManualConfig.BotSkinSelection> entry : snapshot) {
            String alias = entry.getKey();
            SkinSelection current = fromConfigSelection(entry.getValue());
            if (alias == null || alias.isBlank() || current == null || !current.isCustomUrl() || current.lastSetByAdmin()) {
                continue;
            }
            SkinSelection fallback = new SkinSelection(
                    SkinSource.PRESET,
                    SAFE_PRESET_ID,
                    current.lastSetByUuid(),
                    current.lastSetByName(),
                    current.lastSetByAdmin(),
                    System.currentTimeMillis()
            ).normalized();
            persistSkinSelection(alias, fallback);
            reverted++;

            if (server != null) {
                ServerPlayerEntity online = null;
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p instanceof createFakePlayer && p.getGameProfile().name().equalsIgnoreCase(alias)) {
                        online = p;
                        break;
                    }
                }
                if (online != null) {
                    changeSkin(server, online, fallback);
                }
            }
        }
        return reverted;
    }

    // ── Entity re-track (reflection) ────────────────────────────────────

    /**
     * Unloads and re-loads the entity in the chunk loading manager so that
     * a fresh entity spawn packet is sent to all tracking clients.
     * Uses reflection because {@code unloadEntity}/{@code loadEntity} are
     * protected on {@code ServerChunkLoadingManager}, and at runtime Yarn
     * names are remapped to intermediary.
     */
    private static void retrackEntity(ServerPlayerEntity bot) {
        net.minecraft.server.world.ServerWorld sw =
                (net.minecraft.server.world.ServerWorld) bot.getEntityWorld();
        var loadingManager = sw.getChunkManager().chunkLoadingManager;
        try {
            Class<?> managerClass = loadingManager.getClass();

            // Deterministic resolution across both dev (Yarn names) and prod
            // (intermediary names) runtimes.
            java.lang.reflect.Method unload = resolveEntityVoidMethod(
                    managerClass,
                    new String[]{"unloadEntity", "method_18716"},
                    true
            );
            java.lang.reflect.Method load = resolveEntityVoidMethod(
                    managerClass,
                    new String[]{"loadEntity", "method_18701"},
                    true
            );

            // Last-resort fallback if remapping changes in future versions:
            // classify protected void(Entity) methods by name heuristics,
            // but never rely on raw declaration index ordering.
            if (unload == null || load == null) {
                for (java.lang.reflect.Method m : managerClass.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1
                            && params[0] == net.minecraft.entity.Entity.class
                            && m.getReturnType() == void.class
                            && java.lang.reflect.Modifier.isProtected(m.getModifiers())) {
                        String n = m.getName().toLowerCase(Locale.ROOT);
                        if (unload == null && (n.contains("unload") || n.contains("18716"))) {
                            unload = m;
                        } else if (load == null && (n.contains("load") || n.contains("18701"))) {
                            load = m;
                        }
                    }
                }
            }

            if (unload != null && load != null) {
                LOGGER.info("Skin re-track using {}#{} then {}#{} for '{}'",
                        managerClass.getSimpleName(), unload.getName(),
                        managerClass.getSimpleName(), load.getName(),
                        bot.getGameProfile().name());

                unload.setAccessible(true);
                unload.invoke(loadingManager, bot);
                load.setAccessible(true);
                load.invoke(loadingManager, bot);
            } else {
                LOGGER.warn("Could not find unloadEntity/loadEntity on {}",
                        loadingManager.getClass().getName());
            }
        } catch (Exception e) {
            LOGGER.warn("Reflective entity re-track failed for skin change on '{}'; " +
                    "skin will still apply on next login", bot.getGameProfile().name(), e);
        }
    }

    private static java.lang.reflect.Method resolveEntityVoidMethod(
            Class<?> owner,
            String[] names,
            boolean requireProtected
    ) {
        for (String name : names) {
            try {
                java.lang.reflect.Method m = owner.getDeclaredMethod(
                        name, net.minecraft.entity.Entity.class);
                if (m.getReturnType() != void.class) continue;
                if (requireProtected && !java.lang.reflect.Modifier.isProtected(m.getModifiers())) {
                    continue;
                }
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    // ── Client-side preview helper ──────────────────────────────────────

    /**
     * Builds a {@link GameProfile} with the given preset's texture property
     * already embedded.  Intended for client-side skin preview — pass the
     * result to {@code MinecraftClient.getSkinProvider().supplySkinTextures()}
     * to get an async-resolving {@code Supplier<SkinTextures>}.
     */
    public static GameProfile buildPreviewProfile(SkinPreset preset) {
        UUID uuid = UUID.nameUUIDFromBytes(
                ("preview:" + preset.id()).getBytes(StandardCharsets.UTF_8));
        // Build the same base64 textures JSON the server would produce.
        GameProfile temp = new GameProfile(uuid, "Preview");
        String json = buildTexturesJson(temp, preset.textureUrl(), preset.slim());
        String base64 = Base64.getEncoder().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));

        com.google.common.collect.LinkedHashMultimap<String, Property> mutable =
                com.google.common.collect.LinkedHashMultimap.create();
        mutable.put("textures", new Property("textures", base64));

        return new GameProfile(uuid, "Preview",
                new com.mojang.authlib.properties.PropertyMap(mutable));
    }

    // ── Texture JSON builder ────────────────────────────────────────────

    private static String buildTexturesJson(GameProfile profile, String textureUrl, boolean slim) {
        String uuid = profile.id() != null
                ? profile.id().toString().replace("-", "")
                : "00000000000000000000000000000000";
        String name = profile.name() != null ? profile.name() : "Bot";

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"timestamp\":").append(System.currentTimeMillis());
        sb.append(",\"profileId\":\"").append(uuid).append("\"");
        sb.append(",\"profileName\":\"").append(escapeJson(name)).append("\"");
        sb.append(",\"textures\":{\"SKIN\":{\"url\":\"").append(escapeJson(textureUrl)).append("\"");
        if (slim) {
            sb.append(",\"metadata\":{\"model\":\"slim\"}");
        }
        sb.append("}}}");
        return sb.toString();
    }

    private static String buildTexturesJson(GameProfile profile, SkinPreset preset) {
        return buildTexturesJson(profile, preset.textureUrl(), preset.slim());
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
