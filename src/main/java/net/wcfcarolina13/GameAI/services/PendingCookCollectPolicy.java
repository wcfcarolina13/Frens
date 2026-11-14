package net.wcfcarolina13.GameAI.services;

/**
 * When a bot goes back for food it left cooking. {@code /bot cook} and auto-cook load a furnace and
 * walk away; before, the bot came back for the cooked food only once it was hungry again, so a
 * 4-beef batch sat in the furnace for minutes (1.1.301 sandbox). {@link PendingCookLedger} keeps what
 * the bot put in; this decides, from what the furnace holds now, whether to wait, take some, or give
 * the entry up. The bot only ever takes as much cooked food as it loaded raw: the rest of the output
 * may be the owner's. No Minecraft imports.
 */
public final class PendingCookCollectPolicy {

    /** An entry this old is dropped: the bot's food has long been taken by someone or it never cooked. */
    public static final long EXPIRY_TICKS = 20L * 60L * 20L;

    public enum Decision {
        /** Leave it: still cooking. Other auto-cook rules (the hungry collect) still run. */
        WAIT,
        /** Walk over and take {@link #take} items now. */
        TAKE,
        /** Nothing of the bot's is in the furnace any more: forget the entry. */
        DROP
    }

    private PendingCookCollectPolicy() {
    }

    /**
     * One trip should bring back the whole batch, so the bot waits while its raw food is still in the
     * input slot and the output is short of what it is owed. It goes anyway when the output stack is
     * full (the furnace has stopped) or the furnace is out (not lit), since nothing more will cook.
     *
     * @param owed          cooked items still owed to the bot at this furnace
     * @param outputIsCooked whether the output slot holds the cooked item the bot is owed
     * @param outputCount   the output stack's count
     * @param outputMax     the output stack's max count
     * @param inputHoldsRaw whether the input slot still holds the bot's raw item
     * @param furnaceLit    whether the furnace is burning
     */
    public static Decision decide(int owed, boolean outputIsCooked, int outputCount, int outputMax,
                                  boolean inputHoldsRaw, boolean furnaceLit) {
        if (owed <= 0) {
            return Decision.DROP;
        }
        int cooked = outputIsCooked ? Math.max(0, outputCount) : 0;
        if (cooked == 0) {
            // Someone took the cooked food and the raw food is gone too: nothing left to come back for.
            return inputHoldsRaw ? Decision.WAIT : Decision.DROP;
        }
        if (!inputHoldsRaw || cooked >= owed || cooked >= Math.max(1, outputMax) || !furnaceLit) {
            return Decision.TAKE;
        }
        return Decision.WAIT;
    }

    /** How many cooked items to take: what is there, never more than owed. */
    public static int take(int owed, int outputCount) {
        return Math.max(0, Math.min(owed, outputCount));
    }

    /**
     * After a take the entry closes when nothing is owed, or when the input holds none of the raw
     * item: nothing more of the bot's will cook here, and a kept entry would later take the owner's
     * next batch of the same food. A take cut short (pack and chests full) keeps it: the bot's own
     * cooked food is still in the output and the bot tries again later.
     */
    public static boolean closeAfterTake(int owedLeft, boolean inputHoldsRaw, boolean cutShort) {
        return owedLeft <= 0 || (!inputHoldsRaw && !cutShort);
    }

    /** A tick counter that went backwards (a new world) expires the entry as well. */
    public static boolean isExpired(long nowTick, long recordedTick) {
        long age = nowTick - recordedTick;
        return age < 0 || age > EXPIRY_TICKS;
    }
}
