package net.wcfcarolina13.GameAI.services;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.Frens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
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

    private BotSkinService() {}

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

    // ── Profile enrichment (pre-connect) ────────────────────────────────

    /**
     * Adds (or replaces) the {@code "textures"} property on the given
     * GameProfile.  Call this <em>before</em>
     * {@code PlayerManager.onPlayerConnect} so that the initial
     * {@code PlayerListS2CPacket} carries the skin.
     *
     * @param profile  the bot's GameProfile (mutable properties map)
     * @param presetId preset short id (e.g. {@code "ari"})
     */
    public static void applySkinToProfile(GameProfile profile, String presetId) {
        SkinPreset preset = presetById(presetId);
        if (preset == null) return;
        applyPresetToProfile(profile, preset);
    }

    /** Pick a random skin and apply it to the profile.  Returns the chosen preset id. */
    public static String applyRandomSkin(GameProfile profile) {
        SkinPreset preset = PRESETS.get(new Random().nextInt(PRESETS.size()));
        applyPresetToProfile(profile, preset);
        return preset.id();
    }

    private static void applyPresetToProfile(GameProfile profile, SkinPreset preset) {
        String json = buildTexturesJson(profile, preset);
        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        profile.properties().removeAll("textures");
        profile.properties().put("textures", new Property("textures", base64));
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
        if (server == null || bot == null) return false;
        SkinPreset preset = presetById(presetId);
        if (preset == null) {
            LOGGER.warn("Unknown skin preset '{}' for bot '{}'", presetId, bot.getGameProfile().name());
            return false;
        }

        GameProfile profile = bot.getGameProfile();

        // 1. Remove from player list for all clients.
        server.getPlayerManager().sendToAll(
                new PlayerRemoveS2CPacket(List.of(profile.id())));

        // 2. Remove entity from all clients.
        server.getPlayerManager().sendToAll(
                new EntitiesDestroyS2CPacket(bot.getId()));

        // 3. Update profile textures.
        applyPresetToProfile(profile, preset);

        // 4. Re-add to player list with updated profile.
        server.getPlayerManager().sendToAll(
                PlayerListS2CPacket.entryFromPlayer(List.of(bot)));

        // 5. Re-send entity spawn to all tracking clients.
        //    unloadEntity / loadEntity are protected on ServerChunkLoadingManager,
        //    so we call them reflectively (standard Carpet-style pattern).
        net.minecraft.server.world.ServerWorld sw =
                (net.minecraft.server.world.ServerWorld) bot.getEntityWorld();
        var loadingManager = sw.getChunkManager().chunkLoadingManager;
        try {
            java.lang.reflect.Method unload = loadingManager.getClass()
                    .getDeclaredMethod("unloadEntity", net.minecraft.entity.Entity.class);
            unload.setAccessible(true);
            unload.invoke(loadingManager, bot);
            java.lang.reflect.Method load = loadingManager.getClass()
                    .getDeclaredMethod("loadEntity", net.minecraft.entity.Entity.class);
            load.setAccessible(true);
            load.invoke(loadingManager, bot);
        } catch (Exception e) {
            LOGGER.warn("Reflective entity re-track failed for skin change on '{}'; " +
                    "skin will still apply on next login", bot.getGameProfile().name(), e);
        }

        // 6. Persist choice.
        persistSkin(bot.getGameProfile().name(), presetId);

        LOGGER.info("Applied skin '{}' to bot '{}'", presetId, bot.getGameProfile().name());
        return true;
    }

    // ── Persistence (via ManualConfig) ──────────────────────────────────

    /** Gets the persisted skin preset id for a bot alias, or null. */
    public static String getSkin(String alias) {
        if (Frens.CONFIG == null || alias == null) return null;
        return Frens.CONFIG.getBotSkin(alias);
    }

    /** Persists the skin choice for a bot alias. */
    public static void persistSkin(String alias, String presetId) {
        if (Frens.CONFIG == null || alias == null || presetId == null) return;
        Frens.CONFIG.setBotSkin(alias, presetId);
        Frens.CONFIG.save();
    }

    // ── Texture JSON builder ────────────────────────────────────────────

    private static String buildTexturesJson(GameProfile profile, SkinPreset preset) {
        String uuid = profile.id() != null
                ? profile.id().toString().replace("-", "")
                : "00000000000000000000000000000000";
        String name = profile.name() != null ? profile.name() : "Bot";

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"timestamp\":").append(System.currentTimeMillis());
        sb.append(",\"profileId\":\"").append(uuid).append("\"");
        sb.append(",\"profileName\":\"").append(escapeJson(name)).append("\"");
        sb.append(",\"textures\":{\"SKIN\":{\"url\":\"").append(preset.textureUrl()).append("\"");
        if (preset.slim()) {
            sb.append(",\"metadata\":{\"model\":\"slim\"}");
        }
        sb.append("}}}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
