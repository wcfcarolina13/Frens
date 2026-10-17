package net.wcfcarolina13.GameAI.services;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Pure rules for {@code /bot retrieve <bot> [me|<deadBot>]}: whether a death is worth a trip,
 * where the bot stands, the post-arrival action string, and every player-facing line. No
 * Minecraft types, so it is unit tested. Player-facing text has no em dashes and no semicolons.
 */
public final class DeathRetrievalPolicy {

    private DeathRetrievalPolicy() {}

    /** Past this much real time a death is refused: items last about 5 minutes while loaded. */
    public static final long MAX_AGE_MS = 20L * 60L * 1000L;
    /** Drop sweep radius around the stand point (blocks). */
    public static final double SWEEP_RADIUS = 8.0D;
    /** Drop sweep passes. */
    public static final int SWEEP_PASSES = 3;
    /** Stand-point search box around the death position. */
    public static final int STAND_RADIUS = 3;
    public static final int STAND_DY_MIN = -2;
    public static final int STAND_DY_MAX = 2;

    public static final String ACTION_PREFIX = "retrieve:";
    /** The {@code who} word for the sender's own death. */
    public static final String ME = "me";

    private static final String LAVA = "minecraft:lava";
    private static final String VOID = "minecraft:out_of_world";
    private static final Set<String> MOB_CAUSES = Set.of(
            "minecraft:mob_attack", "minecraft:mob_attack_no_aggro", "minecraft:mob_projectile");

    /** What to do with one recorded death. */
    public enum Verdict {
        GO,
        /** A mob killed them: go, but say so. */
        WARN_MOB,
        REFUSE_LAVA,
        REFUSE_VOID,
        REFUSE_TOO_OLD;

        public boolean refused() {
            return this == REFUSE_LAVA || this == REFUSE_VOID || this == REFUSE_TOO_OLD;
        }
    }

    /**
     * @param causeId     damage type id ({@code minecraft:lava}), or null when unknown
     * @param killerId    entity type id of a non-player killer, or null
     * @param epochMs     wall-clock time of the death, or null / non-positive when unknown
     * @param nowMs       current wall-clock time
     */
    public static Verdict judge(String causeId, String killerId, Long epochMs, long nowMs) {
        String cause = causeId == null ? "" : causeId.trim().toLowerCase(Locale.ROOT);
        if (LAVA.equals(cause)) return Verdict.REFUSE_LAVA;
        if (VOID.equals(cause)) return Verdict.REFUSE_VOID;
        if (ageMs(epochMs, nowMs) > MAX_AGE_MS) return Verdict.REFUSE_TOO_OLD;
        if ((killerId != null && !killerId.isBlank()) || MOB_CAUSES.contains(cause)) return Verdict.WARN_MOB;
        return Verdict.GO;
    }

    /** Age of a death in ms: 0 when the time is unknown or in the future. */
    public static long ageMs(Long epochMs, long nowMs) {
        if (epochMs == null || epochMs <= 0L) return 0L;
        return Math.max(0L, nowMs - epochMs);
    }

    // ── Stand point ─────────────────────────────────────────────────────────

    public record Offset(int dx, int dy, int dz) {}

    public static int gridSize(int radius, int dyMin, int dyMax) {
        int side = radius * 2 + 1;
        return side * side * (dyMax - dyMin + 1);
    }

    /** Index of an offset in the flat grid the caller fills (dy outer, then dx, then dz). */
    public static int gridIndex(int dx, int dy, int dz, int radius, int dyMin) {
        int side = radius * 2 + 1;
        return ((dy - dyMin) * side + (dx + radius)) * side + (dz + radius);
    }

    /**
     * The safe offset nearest the death position: lowest {@code dx² + dz² + 2·dy²}, ties to the
     * first in grid order. Null when no cell is safe or the grid has the wrong size.
     */
    public static Offset chooseStandPoint(boolean[] safe, int radius, int dyMin, int dyMax) {
        if (safe == null || radius < 0 || dyMax < dyMin || safe.length != gridSize(radius, dyMin, dyMax)) {
            return null;
        }
        Offset best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int dy = dyMin; dy <= dyMax; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!safe[gridIndex(dx, dy, dz, radius, dyMin)]) continue;
                    int score = dx * dx + dz * dz + 2 * dy * dy;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Offset(dx, dy, dz);
                    }
                }
            }
        }
        return best;
    }

    // ── Post-arrival action ─────────────────────────────────────────────────

    /** Where the bot goes after the sweep. */
    public enum ReturnMode {
        /** Back to whoever sent it (stay if they are offline or it cannot travel). */
        SENDER,
        /** Stay at the death spot (sent from the console). */
        STAY
    }

    public static String actionType(ReturnMode mode) {
        return ACTION_PREFIX + (mode == ReturnMode.STAY ? "stay" : "sender");
    }

    /** The return mode of a {@code retrieve:<mode>} action, or empty when it is not one. */
    public static Optional<ReturnMode> parseAction(String type) {
        if (type == null || !type.startsWith(ACTION_PREFIX)) return Optional.empty();
        return switch (type.substring(ACTION_PREFIX.length())) {
            case "sender" -> Optional.of(ReturnMode.SENDER);
            case "stay" -> Optional.of(ReturnMode.STAY);
            default -> Optional.empty();
        };
    }

    /** True when {@code who} asks for the sender's own death (null, blank or "me"). */
    public static boolean isMe(String who) {
        return who == null || who.isBlank() || ME.equalsIgnoreCase(who.trim());
    }

    // ── Labels ──────────────────────────────────────────────────────────────

    /** {@code minecraft:the_nether} -> {@code the Nether}; unknown ids are shown as they are. */
    public static String dimensionLabel(String dimensionId) {
        if (dimensionId == null) return "an unknown world";
        return switch (dimensionId) {
            case "minecraft:overworld" -> "the Overworld";
            case "minecraft:the_nether" -> "the Nether";
            case "minecraft:the_end" -> "the End";
            default -> dimensionId;
        };
    }

    /** {@code minecraft:cave_spider} -> {@code cave spider}. */
    public static String mobLabel(String entityId) {
        if (entityId == null || entityId.isBlank()) return "a mob";
        String path = entityId.contains(":") ? entityId.substring(entityId.indexOf(':') + 1) : entityId;
        return path.replace('_', ' ');
    }

    // ── Player-facing lines ─────────────────────────────────────────────────

    public static String usage() {
        return "Usage: /bot retrieve <bot> [me|<dead bot>]";
    }

    public static String consoleNeedsDeadBot() {
        return "From the console, name the bot that died: /bot retrieve <bot> <dead bot>";
    }

    public static String noRecordMe() {
        return "You have no recorded death in this world.";
    }

    public static String noRecordBot(String deadBot) {
        return "I have no record of " + deadBot + " dying in this world.";
    }

    public static String notOwner(String bot) {
        return "You can only send a bot you own. " + bot + " is not yours.";
    }

    public static String notOwnerOfDead(String deadBot) {
        return "You can only fetch the drops of a bot you own. " + deadBot + " is not yours.";
    }

    public static String alreadyTraveling(String bot) {
        return bot + " is already traveling.";
    }

    public static String refusal(Verdict verdict, String whose, long ageMs) {
        return switch (verdict) {
            case REFUSE_LAVA -> "No point going: " + whose + " death was in lava, so the items burned up.";
            case REFUSE_VOID -> "No point going: " + whose + " death was in the void, so the items are gone.";
            case REFUSE_TOO_OLD -> "No point going: " + whose + " death was " + Math.max(1, ageMs / 60_000L)
                    + " minutes ago. Dropped items vanish after about 5 minutes while the area is loaded.";
            default -> "";
        };
    }

    public static String worldUnavailable(String dimensionId) {
        return "That death was in " + dimensionLabel(dimensionId) + ", which is not available on this server.";
    }

    public static String noStandSpot(String bot) {
        return bot + " can't find a safe place to stand next to that spot.";
    }

    public static String cannotSetOff(String bot) {
        return bot + " can't set off right now.";
    }

    public static String departing(String bot, String whose, int x, int y, int z, String dimensionId) {
        return bot + " is heading to " + whose + " death spot at " + x + ", " + y + ", " + z + " in "
                + dimensionLabel(dimensionId) + ". Items on the ground last about 5 minutes while the area is loaded.";
    }

    public static String mobWarning(String bot, String killerId) {
        String killer = killerId == null || killerId.isBlank() ? "a mob" : "a " + mobLabel(killerId);
        return "Careful: " + killer + " did that, and " + bot + " may run into it.";
    }

    public static String arrivedAtFallback(String bot) {
        return bot + " could not reach that world and landed at Overworld spawn. Retrieval canceled.";
    }

    public static String sweepFailed(String bot, String reason) {
        return bot + " could not finish the sweep: " + (reason == null ? "unknown reason" : reason);
    }

    public static String result(String bot, int pickedUp) {
        if (pickedUp <= 0) return bot + " found nothing to pick up at the death spot.";
        return bot + " picked up " + (pickedUp == 1 ? "1 item" : pickedUp + " items") + " at the death spot.";
    }

    /**
     * The report after a successful sweep: the sweep's own line (it counts what it picked up,
     * {@code DropSweepMessages}), or {@link #result} when that line is blank.
     */
    public static String sweepReport(String bot, String sweepMessage, int inventoryGain) {
        if (sweepMessage == null || sweepMessage.isBlank()) return result(bot, inventoryGain);
        return bot + " at the death spot: " + sweepMessage.trim();
    }

    public static String returning(String bot, int seconds) {
        return bot + " is coming back to you (about " + Math.max(1, seconds) + "s).";
    }

    public static String stayingOffline(String bot) {
        return bot + " is staying at the death spot because you are offline.";
    }

    public static String stayingCannotTravel(String bot) {
        return bot + " is staying at the death spot because it can't travel back right now.";
    }

    public static String stayingSentFromConsole(String bot) {
        return bot + " is staying at the death spot.";
    }
}
