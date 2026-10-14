package net.wcfcarolina13.ui;

import java.util.List;

/**
 * Stable bit index per idle hobby for the disabled-hobbies bitmask synced in botStats slot 25.
 * The one source for {@code BotPlayerInventoryScreenHandler.HOBBY_BIT_ORDER} and
 * {@code BotActionPolicy.HOBBY_NAMES}. Free of Minecraft imports so tests can read it.
 *
 * <p>Append new hobbies to the END of this list, never reorder, or saved bots will see the wrong
 * hobbies marked disabled. Up to 31 entries fit in a signed int.
 */
public final class HobbyBitOrder {

    private HobbyBitOrder() {}

    public static final List<String> NAMES = List.of(
            "hunt", "fish", "feed_animals", "flowers", "grass_seeds",
            "mining", "shadow_companion", "hangout", "cook", "leaf_litter",
            "mushrooms", "wander", "woodcut", "collect_dirt", "honey_collect",
            "walk_dogs"
    );
}
