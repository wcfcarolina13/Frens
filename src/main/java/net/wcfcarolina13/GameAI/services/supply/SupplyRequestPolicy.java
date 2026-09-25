package net.wcfcarolina13.GameAI.services.supply;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure-logic policy: which chest items a companion may ask its owner for, and how many.
 *
 * <p>No Minecraft imports — items, chests and players are plain strings, ints and UUIDs, so the
 * whole rule set is unit-testable. The server adapter (supplies Phase 2) turns a live
 * {@code ItemStack} into an {@link ItemKey} and a chest block into a {@link ChestKey}; nothing in
 * this class touches the world, an inventory or the network.
 *
 * <p><b>What it decides.</b> Three things, all fail-closed:
 * <ul>
 *   <li><b>Eligibility</b> ({@link #classify}): only items on an explicit common-supply allowlist,
 *       of an allowed equipment tier, and carrying no component beyond
 *       {@link Config#allowedComponentIds()} — so a named, enchanted, lore-carrying or
 *       custom-data stack is never requested even when its item id is common.</li>
 *   <li><b>Quantity</b> ({@link #grantable}): never below the chest's reserve and never more than
 *       the bot asked for or actually needs.</li>
 *   <li><b>Who may answer</b> ({@link #mayRespond}): the bot's recorded owner, and nobody at all
 *       for an un-owned bot.</li>
 * </ul>
 *
 * <p>Pending prompts, grants, "always" permissions and cooldowns live in
 * {@link SupplyRequestLedger}; this class holds no state.
 */
public final class SupplyRequestPolicy {

    /** The owner's three answers to a supply prompt. No answer at all grants nothing. */
    public enum Choice {
        /** Take exactly the requested item and quantity, once. */
        ALLOW_ONCE,
        /** Allow common supplies from this chest for this owner's bots until revoked. */
        ALWAYS_COMMON,
        /** Refuse; the same bot does not ask for the same item at the same chest for a while. */
        NO
    }

    /** Why a request is or is not allowed, before any prompt is shown. */
    public enum Verdict {
        ELIGIBLE,
        /** The item id is not on the common-supply allowlist (covers every "valuable" item). */
        NOT_ALLOWLISTED,
        /** The stack carries a component outside {@link Config#allowedComponentIds()}. */
        PROTECTED_COMPONENTS,
        /** Known equipment whose tier is not in {@link Config#allowedTiers()} (iron by default). */
        TIER_NOT_ALLOWED,
        /** Taking any would leave the chest below its reserve. */
        RESERVE_EXHAUSTED,
        /** The bot asked for nothing, or needs nothing. */
        NO_NEED,
        /** The bot has no recorded owner, so there is nobody entitled to approve. */
        NO_OWNER
    }

    /** Whether an allowlisted item is a stackable material or a piece of equipment. */
    public enum Category { MATERIAL, EQUIPMENT }

    /**
     * An item as the policy sees it.
     *
     * @param itemId                 registry id, e.g. {@code "minecraft:stone_axe"}
     * @param componentsFp           opaque fingerprint of the stack's full component map, computed
     *                               by the adapter; two stacks are the same item only if both the
     *                               id and this fingerprint match
     * @param nonDefaultComponentIds ids of the components that differ from the item's defaults
     *                               (e.g. {@code "minecraft:damage"}, {@code "minecraft:custom_name"})
     */
    public record ItemKey(String itemId, String componentsFp, Set<String> nonDefaultComponentIds) {
        public ItemKey {
            itemId = itemId == null ? "" : itemId;
            componentsFp = componentsFp == null ? "" : componentsFp;
            nonDefaultComponentIds = copyNonNull(nonDefaultComponentIds);
        }

        /** A stack with only default components. */
        public static ItemKey plain(String itemId) {
            return new ItemKey(itemId, "", Set.of());
        }
    }

    /** A block position. Compared as an int tuple (x, then y, then z), never as text. */
    public record Pos(int x, int y, int z) {
        int compare(Pos o) {
            int c = Integer.compare(x, o.x);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(y, o.y);
            return c != 0 ? c : Integer.compare(z, o.z);
        }

        boolean isHorizontalNeighbour(Pos o) {
            return y == o.y && Math.abs(x - o.x) + Math.abs(z - o.z) == 1;
        }
    }

    /**
     * One chest, identified the same way from either half of a double chest.
     *
     * @param worldId {@code levelName/dimension}, e.g. {@code "New World/minecraft:overworld"} —
     *                the same spelling {@code BotChestRegistryService.serverWorldKey} writes, so
     *                the key is unique across saves and dimensions
     */
    public record ChestKey(String worldId, int x, int y, int z) {
        public ChestKey {
            if (worldId == null || worldId.isBlank()) {
                throw new IllegalArgumentException("worldId must not be blank");
            }
        }

        /**
         * The single key for a chest: for a double chest, the lexicographically smaller of the two
         * halves (compared as int tuples), so both halves map to one key whichever is clicked.
         *
         * <p>A partner that is not a horizontal neighbour on the same y is ignored and the half
         * keys itself. That fails safe: a mis-resolved partner yields two separate chests, which
         * means more prompts, never an "always" permission leaking onto an unrelated chest.
         */
        public static ChestKey canonical(String worldId, Pos half, Pos partnerOrNull) {
            Objects.requireNonNull(half, "half");
            Pos chosen = half;
            if (partnerOrNull != null && half.isHorizontalNeighbour(partnerOrNull)
                    && partnerOrNull.compare(half) < 0) {
                chosen = partnerOrNull;
            }
            return new ChestKey(worldId, chosen.x(), chosen.y(), chosen.z());
        }
    }

    /**
     * What is in the chest right now, counted across both halves of a double chest.
     *
     * @param itemCount count of this exact item (same id and component fingerprint)
     * @param typeCount for equipment: count of every stack whose id maps to the same
     *                  {@link #equipmentType equipment type} in the config table, whatever its
     *                  tier or components (an enchanted spare axe is still a spare axe); ignored
     *                  for materials. Never effectively below {@code itemCount}.
     */
    public record Stock(int itemCount, int typeCount) {
        public Stock {
            itemCount = Math.max(0, itemCount);
            typeCount = Math.max(itemCount, typeCount);
        }

        /** Stock of a material, or of equipment with no other pieces of its type present. */
        public static Stock of(int itemCount) {
            return new Stock(itemCount, itemCount);
        }
    }

    /**
     * One exact request: who owns the bot, which bot, which chest, which exact item, how many.
     * A grant is only ever consumed by a fingerprint equal to it on every field except that the
     * quantity may be lower. {@link #itemId()} and {@link #componentsFp()} are the item binding.
     *
     * @param owner the bot's current recorded owner, or {@code null} if it has none
     */
    public record RequestFingerprint(UUID owner, UUID bot, ChestKey chest, ItemKey item, int qty) {
        public RequestFingerprint {
            Objects.requireNonNull(bot, "bot");
            Objects.requireNonNull(chest, "chest");
            Objects.requireNonNull(item, "item");
        }

        public String itemId() {
            return item.itemId();
        }

        public String componentsFp() {
            return item.componentsFp();
        }

        public RequestFingerprint withQty(int newQty) {
            return new RequestFingerprint(owner, bot, chest, item, newQty);
        }
    }

    /** An eligibility verdict plus the quantity it permits (0 unless {@link Verdict#ELIGIBLE}). */
    public record Assessment(Verdict verdict, int quantity) {
        public boolean eligible() {
            return verdict == Verdict.ELIGIBLE;
        }
    }

    /**
     * The tunable rule set. {@link #defaults()} is deliberately small; widening it is a config
     * change, not a code change.
     *
     * @param materialIds          allowlisted stackable materials
     * @param equipmentTypeById    allowlisted equipment id → type ({@code "axe"}, {@code "helmet"}, …)
     * @param tierById             equipment id → tier ({@code "wooden"}, {@code "iron"}, …)
     * @param allowedTiers         tiers a bot may ask for; equipment of any other tier is
     *                             {@link Verdict#TIER_NOT_ALLOWED}
     * @param materialReserve      materials left in the chest after any withdrawal
     * @param equipmentSpare       pieces of the same equipment type left in the chest
     * @param allowedComponentIds  non-default components a requestable stack may carry
     * @param operatorMayApprove   whether a server operator who is not the owner may answer
     */
    public record Config(Set<String> materialIds,
                         Map<String, String> equipmentTypeById,
                         Map<String, String> tierById,
                         Set<String> allowedTiers,
                         int materialReserve,
                         int equipmentSpare,
                         Set<String> allowedComponentIds,
                         boolean operatorMayApprove) {
        public Config {
            materialIds = copyNonNull(materialIds);
            equipmentTypeById = equipmentTypeById == null ? Map.of() : Map.copyOf(equipmentTypeById);
            tierById = tierById == null ? Map.of() : Map.copyOf(tierById);
            allowedTiers = copyNonNull(allowedTiers);
            materialReserve = Math.max(0, materialReserve);
            equipmentSpare = Math.max(0, equipmentSpare);
            allowedComponentIds = copyNonNull(allowedComponentIds);
        }

        public static Config defaults() {
            return new Config(DEFAULT_MATERIAL_IDS, DEFAULT_EQUIPMENT_TYPE_BY_ID, DEFAULT_TIER_BY_ID,
                    DEFAULT_ALLOWED_TIERS, DEFAULT_MATERIAL_RESERVE, DEFAULT_EQUIPMENT_SPARE,
                    DEFAULT_ALLOWED_COMPONENT_IDS, false);
        }

        public Config withAllowedTiers(Set<String> tiers) {
            return new Config(materialIds, equipmentTypeById, tierById, tiers, materialReserve,
                    equipmentSpare, allowedComponentIds, operatorMayApprove);
        }

        public Config withOperatorMayApprove(boolean mayApprove) {
            return new Config(materialIds, equipmentTypeById, tierById, allowedTiers, materialReserve,
                    equipmentSpare, allowedComponentIds, mayApprove);
        }
    }

    // ── Defaults ─────────────────────────────────────────────────────────────────────────────

    /** Materials left in the chest: 16 of each (a quarter stack stays with the owner). */
    public static final int DEFAULT_MATERIAL_RESERVE = 16;

    /** Equipment left in the chest: one piece of the same type (one spare axe stays). */
    public static final int DEFAULT_EQUIPMENT_SPARE = 1;

    /**
     * Damage is the only non-default component a requestable stack may carry: a worn stone axe is
     * still a common stone axe. Anything else — {@code custom_name}, {@code enchantments},
     * {@code lore}, {@code custom_data}, {@code repair_cost} — marks the stack as the owner's
     * particular item.
     */
    public static final Set<String> DEFAULT_ALLOWED_COMPONENT_IDS = Set.of("minecraft:damage");

    /**
     * Tiers a bot may ask for by default. {@code wooden}/{@code stone}/{@code copper} match the
     * chest axe retrieval precedent ({@code ToolProvisionService.ALLOWED_AXE_IDS}); {@code leather}
     * is the armor counterpart. Iron equipment is in the table below but gated here — allowing it
     * is one added {@code "iron"} on this line. Diamond, golden, netherite and chainmail are not
     * in the table at all, so they are {@link Verdict#NOT_ALLOWLISTED} whatever this set says.
     */
    public static final Set<String> DEFAULT_ALLOWED_TIERS = Set.of("wooden", "stone", "copper", "leather");

    /** Tool and weapon types, crossed with {@link #TOOL_TIERS} to build the equipment table. */
    static final List<String> TOOL_TYPES = List.of("axe", "pickaxe", "shovel", "hoe", "sword", "spear");
    static final List<String> TOOL_TIERS = List.of("wooden", "stone", "copper", "iron");

    /** Armor types, crossed with {@link #ARMOR_TIERS}. */
    static final List<String> ARMOR_TYPES = List.of("helmet", "chestplate", "leggings", "boots");
    static final List<String> ARMOR_TIERS = List.of("leather", "copper", "iron");

    /**
     * Common building, fuel, food and seed materials. Every id was checked against the 1.21.11
     * item registry. Kept short on purpose; supplies Phase 2 may widen it.
     */
    public static final Set<String> DEFAULT_MATERIAL_IDS = Set.of(
            // building
            "minecraft:cobblestone", "minecraft:dirt",
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
            "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks",
            "minecraft:mangrove_planks", "minecraft:cherry_planks", "minecraft:pale_oak_planks",
            "minecraft:bamboo_planks", "minecraft:crimson_planks", "minecraft:warped_planks",
            "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
            "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
            "minecraft:mangrove_log", "minecraft:cherry_log", "minecraft:pale_oak_log",
            // utility and fuel
            "minecraft:stick", "minecraft:torch", "minecraft:coal", "minecraft:charcoal",
            // food
            "minecraft:bread", "minecraft:apple", "minecraft:baked_potato",
            "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_chicken",
            "minecraft:cooked_mutton", "minecraft:cooked_rabbit", "minecraft:cooked_cod",
            "minecraft:cooked_salmon",
            // seeds (carrot and potato are both food and the crop's seed)
            "minecraft:wheat_seeds", "minecraft:beetroot_seeds", "minecraft:carrot", "minecraft:potato");

    public static final Map<String, String> DEFAULT_EQUIPMENT_TYPE_BY_ID;
    public static final Map<String, String> DEFAULT_TIER_BY_ID;

    static {
        Map<String, String> types = new HashMap<>();
        Map<String, String> tiers = new HashMap<>();
        for (String tier : TOOL_TIERS) {
            for (String type : TOOL_TYPES) {
                String id = "minecraft:" + tier + "_" + type;
                types.put(id, type);
                tiers.put(id, tier);
            }
        }
        for (String tier : ARMOR_TIERS) {
            for (String type : ARMOR_TYPES) {
                String id = "minecraft:" + tier + "_" + type;
                types.put(id, type);
                tiers.put(id, tier);
            }
        }
        DEFAULT_EQUIPMENT_TYPE_BY_ID = Collections.unmodifiableMap(types);
        DEFAULT_TIER_BY_ID = Collections.unmodifiableMap(tiers);
    }

    private SupplyRequestPolicy() {
    }

    // ── Rules ────────────────────────────────────────────────────────────────────────────────

    /** Material, equipment, or {@code null} when the id is on neither list. */
    public static Category category(String itemId, Config cfg) {
        if (itemId == null || cfg == null) {
            return null;
        }
        if (cfg.materialIds().contains(itemId)) {
            return Category.MATERIAL;
        }
        if (cfg.equipmentTypeById().containsKey(itemId)) {
            return Category.EQUIPMENT;
        }
        return null;
    }

    /**
     * The equipment type an id belongs to, or {@code null}. The adapter uses this to count
     * {@link Stock#typeCount()}.
     */
    public static String equipmentType(String itemId, Config cfg) {
        if (itemId == null || cfg == null) {
            return null;
        }
        return cfg.equipmentTypeById().get(itemId);
    }

    /**
     * Whether this exact item may ever be requested, ignoring stock and need. Order: allowlist,
     * then tier, then components — so an enchanted diamond sword reads as not allowlisted and an
     * enchanted stone axe as protected.
     */
    public static Verdict classify(ItemKey item, Config cfg) {
        if (item == null || cfg == null) {
            return Verdict.NOT_ALLOWLISTED;
        }
        Category category = category(item.itemId(), cfg);
        if (category == null) {
            return Verdict.NOT_ALLOWLISTED;
        }
        if (category == Category.EQUIPMENT) {
            String tier = cfg.tierById().get(item.itemId());
            if (tier == null || !cfg.allowedTiers().contains(tier)) {
                return Verdict.TIER_NOT_ALLOWED;
            }
        }
        for (String component : item.nonDefaultComponentIds()) {
            if (!cfg.allowedComponentIds().contains(component)) {
                return Verdict.PROTECTED_COMPONENTS;
            }
        }
        return Verdict.ELIGIBLE;
    }

    /** Units of this item's reserve: the material reserve, or the equipment spare. */
    public static int reserveFor(ItemKey item, Config cfg) {
        Category category = item == null ? null : category(item.itemId(), cfg);
        if (category == Category.EQUIPMENT) {
            return cfg.equipmentSpare();
        }
        return cfg == null ? 0 : cfg.materialReserve();
    }

    /**
     * {@code max(0, min(requested, need, stock - reserve))}: never more than asked, never more
     * than needed, never below the reserve. Negative inputs count as zero.
     */
    public static int grantable(int stock, int reserve, int requested, int need) {
        int available = Math.max(0, stock) - Math.max(0, reserve);
        return Math.max(0, Math.min(Math.min(requested, need), available));
    }

    /**
     * {@link #grantable(int, int, int, int)} for a concrete item. A material keeps
     * {@link Config#materialReserve()} of itself; equipment keeps {@link Config#equipmentSpare()}
     * of its type and can never exceed the pieces of this exact item actually present.
     */
    public static int grantable(ItemKey item, Stock stock, int requested, int need, Config cfg) {
        if (item == null || stock == null || cfg == null) {
            return 0;
        }
        Category category = category(item.itemId(), cfg);
        if (category == null) {
            return 0;
        }
        if (category == Category.MATERIAL) {
            return grantable(stock.itemCount(), cfg.materialReserve(), requested, need);
        }
        int byType = grantable(stock.typeCount(), cfg.equipmentSpare(), requested, need);
        return Math.min(stock.itemCount(), byType);
    }

    /**
     * The full pre-prompt and transfer-time check: owner, eligibility, need, then reserve.
     * {@code requested} is how many the bot asks for; the returned quantity may be lower.
     */
    public static Assessment assess(UUID owner, ItemKey item, Stock stock, int requested, int need,
                                    Config cfg) {
        if (owner == null) {
            return new Assessment(Verdict.NO_OWNER, 0);
        }
        Verdict verdict = classify(item, cfg);
        if (verdict != Verdict.ELIGIBLE) {
            return new Assessment(verdict, 0);
        }
        if (requested <= 0 || need <= 0) {
            return new Assessment(Verdict.NO_NEED, 0);
        }
        int quantity = grantable(item, stock, requested, need, cfg);
        if (quantity <= 0) {
            return new Assessment(Verdict.RESERVE_EXHAUSTED, 0);
        }
        return new Assessment(Verdict.ELIGIBLE, quantity);
    }

    /** {@link #assess(UUID, ItemKey, Stock, int, int, Config)} for a fingerprint's owner, item and quantity. */
    public static Assessment assess(RequestFingerprint fp, Stock stock, int need, Config cfg) {
        if (fp == null) {
            return new Assessment(Verdict.NO_NEED, 0);
        }
        return assess(fp.owner(), fp.item(), stock, fp.qty(), need, cfg);
    }

    /**
     * Whether {@code responder} may answer a prompt about a bot owned by {@code owner}.
     *
     * <p>Only the owner. An operator who is not the owner may answer only when
     * {@link Config#operatorMayApprove()} is set — unlike {@code isPrivateSoulAuthorized}, operator
     * status alone is not ownership of the chest's contents. An un-owned bot has no approver at all.
     */
    public static boolean mayRespond(UUID responder, UUID owner, boolean responderIsOperator, Config cfg) {
        if (owner == null || responder == null) {
            return false;
        }
        if (owner.equals(responder)) {
            return true;
        }
        return responderIsOperator && cfg != null && cfg.operatorMayApprove();
    }

    private static Set<String> copyNonNull(Set<String> in) {
        if (in == null || in.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String s : in) {
            if (s != null) {
                out.add(s);
            }
        }
        return Set.copyOf(out);
    }
}
