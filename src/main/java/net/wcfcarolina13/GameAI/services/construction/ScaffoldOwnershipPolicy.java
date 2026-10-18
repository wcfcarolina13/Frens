package net.wcfcarolina13.GameAI.services.construction;

import net.wcfcarolina13.GameAI.skills.support.WoodcutBaseAccessPolicy;

import java.util.Locale;
import java.util.Set;

/**
 * Pure rules for a bot's own woodcut scaffold in the Spawn base (1.1.268 ruling). No Minecraft
 * types: callers pass registry ids ({@code minecraft:oak_planks}) and booleans.
 *
 * <p>Ownership is what lets a bot break a block inside the Spawn base, so it must never be
 * seedable: a position is registered only on a real, flagged placement success of a scaffold
 * material, a break requires the live block id to equal the placed one exactly, and ownership
 * expires 24 h after placement.
 */
public final class ScaffoldOwnershipPolicy {

    /**
     * ScaffoldService's success reason when a retried placement found the cell already holding a
     * scaffold block. That block may not be the bot's, so it is never registered as owned.
     */
    public static final String PLACED_DETECTED_AFTER_RETRY = "placed-detected-after-retry";

    /**
     * Union of {@code ScaffoldService.SCAFFOLD_BLOCKS} and {@code WoodcutSkill.PILLAR_BLOCKS}, as
     * block/item ids (each of these items places the block of the same id). Kept literal so this
     * class loads under JUnit; update it with either list.
     */
    private static final Set<String> SCAFFOLD_MATERIAL_IDS = Set.of(
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:rooted_dirt",
            "minecraft:moss_block",
            "minecraft:pale_moss_block",
            "minecraft:scaffolding",
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:stone",
            "minecraft:netherrack",
            "minecraft:oak_planks",
            "minecraft:spruce_planks",
            "minecraft:birch_planks",
            "minecraft:jungle_planks",
            "minecraft:acacia_planks",
            "minecraft:dark_oak_planks",
            "minecraft:cherry_planks",
            "minecraft:mangrove_planks",
            "minecraft:crimson_planks",
            "minecraft:warped_planks"
    );

    private ScaffoldOwnershipPolicy() {
    }

    /** True when {@code itemOrBlockId} names a scaffold material. A bare path means {@code minecraft:}. */
    public static boolean isScaffoldMaterial(String itemOrBlockId) {
        if (itemOrBlockId == null || itemOrBlockId.isBlank()) {
            return false;
        }
        String id = itemOrBlockId.trim().toLowerCase(Locale.ROOT);
        if (id.indexOf(':') < 0) {
            id = "minecraft:" + id;
        }
        return SCAFFOLD_MATERIAL_IDS.contains(id);
    }

    /**
     * A flagged placement may register ownership only when it really succeeded, never from the
     * {@link #PLACED_DETECTED_AFTER_RETRY} adoption path.
     */
    public static boolean mayRegister(String placeReason, boolean succeeded) {
        return succeeded && !PLACED_DETECTED_AFTER_RETRY.equals(placeReason);
    }

    /** Persisted ownership lasts this long of real time after the placement (24 h). */
    public static final long OWNERSHIP_TTL_MS = 24L * 60L * 60L * 1000L;

    /** What a check of one owned position decides. Every FORGET_* verdict drops it from the registry. */
    public enum OwnedVerdict {
        /** Still this bot's block, exactly as placed: it may be broken as OWN_SCAFFOLD_BREAK. */
        BREAK,
        /** The cell is air: the block is gone. */
        FORGET_GONE,
        /** A different block stands there now: forget it, never break it. */
        FORGET_CHANGED,
        /** Placed more than {@link #OWNERSHIP_TTL_MS} ago (or with no usable timestamp). */
        FORGET_EXPIRED,
        /** This bot never owned the position. */
        NOT_OWNED
    }

    /**
     * True when ownership placed at {@code placedAtMs} has run out at {@code nowMs}: exactly 24 h or
     * more later, no usable timestamp, or a timestamp more than 24 h in the future (clock jump).
     */
    public static boolean isExpired(long placedAtMs, long nowMs) {
        if (placedAtMs <= 0L) {
            return true;
        }
        long age = nowMs - placedAtMs;
        return age >= OWNERSHIP_TTL_MS || -age > OWNERSHIP_TTL_MS;
    }

    /** A stored (or persisted) entry is usable only with a block id and an unexpired timestamp. */
    public static boolean isUsableEntry(String storedBlockId, long placedAtMs, long nowMs) {
        return storedBlockId != null && !storedBlockId.isBlank() && !isExpired(placedAtMs, nowMs);
    }

    /**
     * The one ownership decision before any OWN_SCAFFOLD_BREAK. {@code storedBlockId} is null when
     * this bot does not own the position. The live block id must equal the stored id exactly (the
     * stored id is always a scaffold material, so equality also keeps the material rule).
     */
    public static OwnedVerdict ownedVerdict(String storedBlockId, long placedAtMs, long nowMs,
                                            String liveBlockId, boolean liveIsAir) {
        if (storedBlockId == null) {
            return OwnedVerdict.NOT_OWNED;
        }
        if (!isUsableEntry(storedBlockId, placedAtMs, nowMs)) {
            return OwnedVerdict.FORGET_EXPIRED;
        }
        if (liveIsAir) {
            return OwnedVerdict.FORGET_GONE;
        }
        if (liveBlockId == null || !liveBlockId.equals(storedBlockId) || !isScaffoldMaterial(liveBlockId)) {
            return OwnedVerdict.FORGET_CHANGED;
        }
        return OwnedVerdict.BREAK;
    }

    /** True for every verdict that drops the position from the registry. */
    public static boolean shouldForget(OwnedVerdict verdict) {
        return verdict == OwnedVerdict.FORGET_GONE
                || verdict == OwnedVerdict.FORGET_CHANGED
                || verdict == OwnedVerdict.FORGET_EXPIRED;
    }

    /**
     * The mutation kind the miner asks the claim gate for: a log or leaves is a tree block; else an
     * owned position whose verdict is {@link OwnedVerdict#BREAK} is an own-scaffold break; else other.
     */
    public static WoodcutBaseAccessPolicy.MutationKind breakKind(boolean treeBlock, OwnedVerdict verdict) {
        if (treeBlock) {
            return WoodcutBaseAccessPolicy.MutationKind.TREE_BLOCK;
        }
        if (verdict == OwnedVerdict.BREAK) {
            return WoodcutBaseAccessPolicy.MutationKind.OWN_SCAFFOLD_BREAK;
        }
        return WoodcutBaseAccessPolicy.MutationKind.OTHER;
    }
}
