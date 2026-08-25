package net.wcfcarolina13.ChatUtils;

import net.minecraft.sound.SoundEvent;

import java.util.Locale;
import java.util.Map;

/**
 * User-facing mute categories for the pre-baked voiced dialogue lines.
 *
 * <p>Categories are resolved from the overhead-dialogue {@code tag} strings that call
 * sites already pass (see {@link #fromTag(String)}), falling back to the sound-id
 * prefix when no tag is available (see {@link #fromSound(SoundEvent)}), e.g. for
 * chat-message auto-voicing.
 *
 * <p>{@link #id()} is the stable key stored in settings.json5 — never rename an id.
 * Append new categories at the end.
 */
public enum VoiceLineCategory {

    COMBAT_ALERTS("combat_alerts", "Combat & Alerts",
            "Combat callouts, phantom warnings, pillager alarms, animal-defense warnings."),
    AMBIENT_CHATTER("ambient_chatter", "Ambient Chatter",
            "Idle small talk, social banter, touch reactions, pet and villager remarks."),
    REACTIONS("reactions", "Reactions",
            "Reactions to cooking, eating, food gifts, berry bushes, snowball fights, weather and surroundings."),
    SKILL_TASK("skill_task", "Skill & Task Lines",
            "Lines tied to skills and hobbies: fortifying, hunting, foraging, gear swaps, chest runs, getting stuck."),
    SURVIVAL_STATUS("survival_status", "Survival & Status",
            "Hunger complaints, full-inventory notices, rescue and distress shouts."),
    SUNSET_TRAVEL("sunset_travel", "Sunset & Travel",
            "Sunset auto-return updates, follow/regroup lines, locked-block notices."),
    TOPICS_QUESTS("topics_quests", "Topics & Quests",
            "Biome/structure/mount topic remarks and quest dialogue."),
    ENCHANTING("enchanting", "Enchanting Ambient",
            "Ambient lines spoken near enchanting tables."),
    GENERAL("general", "General Chat",
            "Auto-voiced chat lines and anything not covered by another category (mount/lead lines etc.).");

    private final String id;
    private final String displayName;
    private final String description;

    VoiceLineCategory(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    /** Stable config key stored in settings.json5. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** Exact-match table for tags that a prefix rule below doesn't cover. */
    private static final Map<String, VoiceLineCategory> TAG_MAP = Map.ofEntries(
            Map.entry("combat", COMBAT_ALERTS),
            Map.entry("phantom", COMBAT_ALERTS),
            Map.entry("pillager-alert", COMBAT_ALERTS),
            Map.entry("animal-defense", COMBAT_ALERTS),

            Map.entry("ambient", AMBIENT_CHATTER),
            Map.entry("ambient-social", AMBIENT_CHATTER),
            Map.entry("touch", AMBIENT_CHATTER),
            Map.entry("villager", AMBIENT_CHATTER),
            Map.entry("pet", AMBIENT_CHATTER),

            Map.entry("cooking", REACTIONS),
            Map.entry("context", REACTIONS),
            Map.entry("rotten-flesh", REACTIONS),
            Map.entry("suspicious-stew", REACTIONS),
            Map.entry("food-accept", REACTIONS),
            Map.entry("food-refuse", REACTIONS),
            Map.entry("berry-sting", REACTIONS),
            Map.entry("berry-edible", REACTIONS),

            Map.entry("fortify", SKILL_TASK),
            Map.entry("tree-stuck", SKILL_TASK),
            Map.entry("leaf-stuck", SKILL_TASK),

            Map.entry("hunger", SURVIVAL_STATUS),
            Map.entry("inventory-full", SURVIVAL_STATUS),
            Map.entry("rescue", SURVIVAL_STATUS),
            Map.entry("shelter", SURVIVAL_STATUS),

            Map.entry("locked-block", SUNSET_TRAVEL),

            Map.entry("topic", TOPICS_QUESTS),
            Map.entry("quest", TOPICS_QUESTS),

            Map.entry("enchant-ambient", ENCHANTING)
    );

    /**
     * Resolve a category from an overhead-dialogue call-site tag.
     * Unknown or blank tags land in {@link #GENERAL}.
     */
    public static VoiceLineCategory fromTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return GENERAL;
        }
        String t = tag.toLowerCase(Locale.ROOT);
        VoiceLineCategory exact = TAG_MAP.get(t);
        if (exact != null) {
            return exact;
        }
        if (t.startsWith("sunset-") || t.startsWith("follow-")) {
            return SUNSET_TRAVEL;
        }
        if (t.startsWith("snowball-fight")) {
            return REACTIONS;
        }
        if (t.startsWith("gear-") || t.startsWith("chest") || t.contains("hobby")) {
            return SKILL_TASK;
        }
        return GENERAL;
    }

    /**
     * Fallback resolution from the sound id when no call-site tag is available
     * (e.g. chat-message auto-voicing). The id prefixes only encode a coarse
     * taxonomy, so most sounds land in {@link #GENERAL}.
     */
    public static VoiceLineCategory fromSound(SoundEvent sound) {
        if (sound == null || sound.id() == null || sound.id().getPath() == null) {
            return GENERAL;
        }
        String path = sound.id().getPath();
        if (path.startsWith("bot.line.combat_")) {
            return COMBAT_ALERTS;
        }
        if (path.startsWith("bot.line.status_") || path.startsWith("bot.line.warning_")) {
            return SURVIVAL_STATUS;
        }
        return GENERAL;
    }
}
