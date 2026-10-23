package net.wcfcarolina13.GameAI.services.dialogue;

import java.util.Set;

/**
 * Voice lines that have no recorded clip, only a silent placeholder (Bradley's ruling, 2026-10-23):
 * they are text only. They show as overhead or chat text, the voice lane skips them, and their
 * placeholder sound entries are gone from {@code sounds.json}.
 *
 * <p>The {@code BotDialogueSounds} constants stay as the lines' identities (pools, subtitle text,
 * line ids); they are just never registered as sound events or played. Once a line gets a real
 * clip, add its sounds.json block back and drop its path here.
 *
 * <p>Pure: paths are sound event paths without the namespace ({@code "bot.line.stop_adios"}).
 */
public final class TextOnlyLinePolicy {

    private TextOnlyLinePolicy() {
    }

    /** Sound event paths whose placeholder sounds were removed. */
    public static final Set<String> TEXT_ONLY_PATHS = Set.of(
            "bot.line.mount_banged_up",
            "bot.line.eating_progress",
            "bot.line.ambient_blame_terrain",
            "bot.line.boat_kraken",
            "bot.line.meme_creeper_hate_sound",
            "bot.line.post_explosion_less_boom",
            "bot.line.topic_jungle_memory",
            "bot.line.topic_snow_memory",
            "bot.line.topic_enchanting_memory",
            "bot.line.topic_ruined_portal_ask_1",
            "bot.line.topic_ruined_portal_memory",
            "bot.line.topic_stronghold_ask_1",
            "bot.line.topic_stronghold_memory",
            "bot.line.topic_horse_first_1",
            "bot.line.topic_horse_first_2",
            "bot.line.topic_horse_ask_1",
            "bot.line.topic_horse_ask_2",
            "bot.line.topic_strider_first_2",
            "bot.line.topic_strider_memory",
            "bot.line.portal_nether_overworld_2",
            "bot.line.weird_portal_end_in_nether_1",
            "bot.line.weird_portal_end_in_nether_2",
            "bot.line.weird_portal_nether_in_end_1",
            "bot.line.weird_portal_nether_in_end_2",
            "bot.line.care_player_hungry_1",
            "bot.line.stop_adios",
            "bot.line.sniffer_cutest_thing",
            "bot.line.panda_aggressive",
            "bot.line.panda_playful",
            "bot.line.panda_weak",
            "bot.line.cute_animal_want_one",
            "bot.line.cute_animal_so_cute",
            "bot.line.guardian_beam_hurt",
            "bot.line.redstone_machine_tech",
            "bot.line.iron_golem_daisy_earned",
            "bot.line.snow_golem_ammo",
            "bot.line.snowball_escalate_on_now",
            "bot.line.snowball_escalate_in_for_it",
            "bot.line.snowball_probe_incoming"
    );

    /** True when the line has no clip and must be delivered as text only. */
    public static boolean isTextOnly(String path) {
        return path != null && TEXT_ONLY_PATHS.contains(path);
    }
}
