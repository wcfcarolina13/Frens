package net.wcfcarolina13.ChatUtils;

import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Single decision point for whether a voiced-dialogue category is muted.
 *
 * <p>Two layers stack:
 * <ul>
 *   <li>the <b>baseline</b> mask in settings.json5 ({@link ManualConfig}) — on a dedicated
 *       server this is the admin's mask and applies to every listener;</li>
 *   <li>a per-player mask synced from each client on join and whenever they change their
 *       voice-category screen. A player's mask only ever silences lines for that player.</li>
 * </ul>
 *
 * <p>The baseline read goes through a {@link Supplier} seam so unit tests never touch
 * {@link Frens}.
 */
public final class VoiceLineMuteService {

    private static final Supplier<ManualConfig> DEFAULT_BASELINE = () -> Frens.CONFIG;

    private static volatile Supplier<ManualConfig> baselineSupplier = DEFAULT_BASELINE;

    private static final Map<UUID, Set<String>> PLAYER_MASKS = new ConcurrentHashMap<>();

    private VoiceLineMuteService() {
    }

    // ------------------------------------------------------------------ test seam

    /** Test seam: replace the baseline config source. Always reset in {@code @AfterEach}. */
    public static void setBaselineSupplier(Supplier<ManualConfig> supplier) {
        baselineSupplier = supplier != null ? supplier : DEFAULT_BASELINE;
    }

    /** Restores the production baseline source ({@code Frens.CONFIG}). */
    public static void resetBaselineSupplier() {
        baselineSupplier = DEFAULT_BASELINE;
    }

    // ------------------------------------------------------------------ per-player masks

    /**
     * Stores {@code categoryIds} as {@code playerId}'s mask, ignoring unknown ids.
     * An empty/null collection is stored as an empty mask (i.e. nothing muted for them).
     */
    public static void setPlayerMask(UUID playerId, Collection<String> categoryIds) {
        if (playerId == null) {
            return;
        }
        Set<String> valid = new LinkedHashSet<>();
        if (categoryIds != null) {
            for (String id : categoryIds) {
                if (id == null) {
                    continue;
                }
                for (VoiceLineCategory cat : VoiceLineCategory.values()) {
                    if (cat.id().equals(id)) {
                        valid.add(cat.id());
                        break;
                    }
                }
            }
        }
        PLAYER_MASKS.put(playerId, Collections.unmodifiableSet(valid));
    }

    /** Drops {@code playerId}'s mask (called on disconnect). */
    public static void clearPlayerMask(UUID playerId) {
        if (playerId != null) {
            PLAYER_MASKS.remove(playerId);
        }
    }

    /** Test/diagnostic helper: drops every stored mask. */
    public static void clearAllPlayerMasks() {
        PLAYER_MASKS.clear();
    }

    /** The (unmodifiable) mask stored for {@code playerId}; empty when none is known. */
    public static Set<String> playerMask(UUID playerId) {
        if (playerId == null) {
            return Set.of();
        }
        Set<String> mask = PLAYER_MASKS.get(playerId);
        return mask != null ? mask : Set.of();
    }

    // ------------------------------------------------------------------ decision

    /**
     * @param category the resolved category of the line about to play
     * @param viewer   the player who would hear the line; {@code null} means "baseline only"
     * @return true if playback of this category should be skipped for that listener
     */
    public static boolean isMuted(VoiceLineCategory category, ServerPlayerEntity viewer) {
        return isMutedFor(category, viewer == null ? null : viewer.getUuid());
    }

    /** UUID-based form of {@link #isMuted}; {@code null} viewer = baseline only. */
    public static boolean isMutedFor(VoiceLineCategory category, UUID viewerId) {
        if (category == null) {
            return false;
        }
        ManualConfig cfg = baselineSupplier.get();
        if (cfg != null && cfg.isVoiceCategoryMuted(category.id())) {
            return true;
        }
        return viewerId != null && playerMask(viewerId).contains(category.id());
    }
}
