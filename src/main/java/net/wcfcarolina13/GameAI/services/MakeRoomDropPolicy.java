package net.wcfcarolina13.GameAI.services;

import java.util.List;

/**
 * Which stack a bot drops on the ground to free one inventory slot
 * ({@code CraftingHelper.dropCheapStackForSpace}).
 *
 * <p>Never food, a tool or other damageable item, a protected item, or a stack the caller reserved.
 * A cheap stack (logs, planks, cobble and other bulk blocks, common mob drops) goes first, the
 * smallest cheap stack first so the least is lost. With {@code cheapOnly} nothing else is dropped:
 * mutual aid and a craft on the server thread never throw a valuable stack away. Without it (the
 * drop sweeper, after a chest took part of the pack) the old rule still applies once no cheap stack
 * is left: the largest stack that is not food, a tool, protected or reserved.
 *
 * <p>No Minecraft types.
 */
public final class MakeRoomDropPolicy {

    /** One inventory slot as the policy sees it. {@code id} is the slot index the caller drops. */
    public record SlotView(int id, int count, boolean cheap, boolean protectedItem, boolean damageable,
                           boolean reserved, boolean food, boolean tool) {
    }

    private MakeRoomDropPolicy() {
    }

    /** The slot to drop, or -1 when nothing may be dropped. */
    public static int pick(List<SlotView> slots, boolean cheapOnly) {
        if (slots == null) {
            return -1;
        }
        SlotView bestCheap = null;
        SlotView bestOther = null;
        for (SlotView slot : slots) {
            if (slot == null || slot.count() <= 0 || neverDropped(slot)) {
                continue;
            }
            if (slot.cheap()) {
                if (bestCheap == null || slot.count() < bestCheap.count()) {
                    bestCheap = slot;
                }
            } else if (!cheapOnly && (bestOther == null || slot.count() > bestOther.count())) {
                bestOther = slot;
            }
        }
        if (bestCheap != null) {
            return bestCheap.id();
        }
        return bestOther != null ? bestOther.id() : -1;
    }

    private static boolean neverDropped(SlotView slot) {
        return slot.food() || slot.tool() || slot.damageable() || slot.protectedItem() || slot.reserved();
    }
}
