package net.wcfcarolina13.GameAI.services;

/**
 * Pure-logic policy: how dark it must be for a bot to want a torch in hand.
 *
 * <p>No Minecraft imports — this is threshold arithmetic over ints only, so it is unit-testable
 * without a world. The light value it is compared against is whatever
 * {@link BotTorchHoldService} measures: {@code world.getLightLevel(bot.getBlockPos())}, i.e.
 * max(sky light − ambient darkness, block light) at the bot's feet.
 *
 * <p><b>Why two thresholds.</b> Light is spatial and changes by roughly one level per block. With
 * a single cut-off at 7, a bot that picked a torch up at light 6 dropped it as soon as it walked
 * two blocks toward a lit area: every {@code verdict=hold} line in the 1.1.215/216 field logs was
 * followed within a second by {@code gate=light-above-7 action=yield} (e.g. 6 → 10 → 12 inside one
 * second). Jake's readings in one session sat mostly in the 8–11 band, exactly where a single
 * threshold flaps. So the policy is a hysteresis band:
 * <ul>
 *   <li>a bot that is <em>not</em> holding a torch picks one up only at light
 *       ≤ {@link #ACQUIRE_MAX_LIGHT} (7, the vanilla mob-spawn threshold — "the kind of dim where
 *       torches matter");</li>
 *   <li>a bot that <em>is</em> holding one keeps it through light ≤ {@link #RELEASE_MAX_LIGHT}
 *       (11) and lets go at 12 or brighter.</li>
 * </ul>
 * The gap between the two is what stops the flapping; it must stay positive.
 */
public final class TorchHoldPolicy {

    /** Highest light level at which an empty-handed bot takes a torch out (inclusive). */
    public static final int ACQUIRE_MAX_LIGHT = 7;

    /** Highest light level at which a bot already holding a torch keeps it (inclusive); 12+ releases. */
    public static final int RELEASE_MAX_LIGHT = 11;

    private TorchHoldPolicy() {
    }

    /**
     * The light level above which the light gate rejects holding a torch.
     *
     * @param holding whether the bot currently holds a torch this service put in its hand
     * @return {@link #RELEASE_MAX_LIGHT} while holding, otherwise {@link #ACQUIRE_MAX_LIGHT}
     */
    public static int lightThreshold(boolean holding) {
        return holding ? RELEASE_MAX_LIGHT : ACQUIRE_MAX_LIGHT;
    }

    /**
     * The hotbar slot a bot save should record as selected.
     *
     * <p>The torch-hold bookkeeping is memory-only while the selected slot is persisted, so a
     * save taken mid-hold must record the bot's own pre-torch slot, not the torch. That applies
     * only while the torch slot this service put up is still the selection; once anything else
     * has selected another slot (the few ticks before the service notices a foreign swap), that
     * selection wins.
     *
     * @param savedSlot       the slot selected before the torch went up, or -1 when not holding
     * @param heldTorchSlot   the hotbar slot the torch was put up in, or -1 when not holding
     * @param currentSelected the bot's selected slot right now
     * @return {@code savedSlot} while the held torch slot is still selected, else {@code currentSelected}
     */
    public static int slotToPersist(int savedSlot, int heldTorchSlot, int currentSelected) {
        boolean holding = savedSlot >= 0 && heldTorchSlot >= 0 && currentSelected == heldTorchSlot;
        return holding ? savedSlot : currentSelected;
    }
}
