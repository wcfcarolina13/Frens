package net.wcfcarolina13.GameAI.services.supply;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.AlwaysScope;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.ConsumeStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Category;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Choice;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Config;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Pos;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Stock;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure rules the supplies chest adapter applies to facts it reads from the world: who a chest
 * belongs to, whether it may be touched at all, what it holds, how a stack's components become an
 * {@link ItemKey}, how standing permissions are saved, and the prompt and command text.
 *
 * <p>No Minecraft imports. The adapter reads block entities, locks, zones and stacks on the server
 * thread, turns them into the strings, booleans and records below, and acts on the answer; every
 * decision here is unit-testable and fails closed.
 */
public final class SupplyChestRules {

    private SupplyChestRules() {
    }

    // ── Ownership ────────────────────────────────────────────────────────────────────────────

    /**
     * Whose chest this is, from {@code BotChestRegistryService} records on its half or halves.
     * Only {@link #PLAYER_STORAGE} and {@link #OWNER_STORAGE} may lead to a prompt.
     */
    public enum Ownership {
        /**
         * No owned record on either half: the player's own storage. Records without an owner
         * (written before 1.1.219, or placed by an un-owned bot) are left out by the registry, so
         * a chest holding only those lands here too.
         */
        PLAYER_STORAGE,
        /** Every record on every half names the bot's owner. Still prompts. */
        OWNER_STORAGE,
        /** Some record names a different owner. */
        DENY_FOREIGN,
        /**
         * Some record's owner is blank or not a UUID, the registry lookup failed (its {@code null}
         * sentinel), or the bot has no owner.
         */
        DENY_UNKNOWN,
        /** A double chest where one half has records and the other has none. */
        DENY_MIXED
    }

    /**
     * Classifies a chest from the owner strings recorded on its halves.
     *
     * <p>Each list holds one element per owned record at that half (from
     * {@code BotChestRegistryService.recordedOwnersAt}, which leaves owner-less records out); a
     * blank or unparseable element is a record whose owner cannot be read, a {@code null} element
     * is the registry's failed-lookup sentinel, and both deny. An empty list means no owned
     * record. {@code halfBOrNull} is {@code null} for a single chest.
     *
     * <p>Precedence, first match wins: {@code botOwner == null} or {@code halfA == null} →
     * {@link Ownership#DENY_UNKNOWN} (for a missing owner the request itself is already
     * {@code NO_OWNER} in the policy); any foreign owner →
     * {@link Ownership#DENY_FOREIGN}; any unknown owner → {@link Ownership#DENY_UNKNOWN}; a double
     * chest with records on exactly one half → {@link Ownership#DENY_MIXED}; no records anywhere →
     * {@link Ownership#PLAYER_STORAGE}; otherwise every record names the bot's owner →
     * {@link Ownership#OWNER_STORAGE}. So FOREIGN beats UNKNOWN beats MIXED.
     */
    public static Ownership ownership(UUID botOwner, List<String> halfA, List<String> halfBOrNull) {
        if (botOwner == null || halfA == null) {
            return Ownership.DENY_UNKNOWN;
        }
        boolean foreign = false;
        boolean unknown = false;
        List<List<String>> halves = halfBOrNull == null ? List.of(halfA) : List.of(halfA, halfBOrNull);
        for (List<String> half : halves) {
            for (String recorded : half) {
                UUID owner = parseUuid(recorded);
                if (owner == null) {
                    unknown = true;
                } else if (!owner.equals(botOwner)) {
                    foreign = true;
                }
            }
        }
        if (foreign) {
            return Ownership.DENY_FOREIGN;
        }
        if (unknown) {
            return Ownership.DENY_UNKNOWN;
        }
        if (halfBOrNull != null && halfA.isEmpty() != halfBOrNull.isEmpty()) {
            return Ownership.DENY_MIXED;
        }
        return halfA.isEmpty() ? Ownership.PLAYER_STORAGE : Ownership.OWNER_STORAGE;
    }

    /** Whether a chest of this ownership may be offered to the owner at all. */
    public static boolean mayPrompt(Ownership ownership) {
        return ownership == Ownership.PLAYER_STORAGE || ownership == Ownership.OWNER_STORAGE;
    }

    // ── Access ───────────────────────────────────────────────────────────────────────────────

    /** Whether the adapter may read and take from a chest, and the first reason it may not. */
    public enum Access {
        OK,
        /** The chest's chunk (either half) is not loaded. */
        DENY_UNLOADED,
        /** A half is not a chest block entity. */
        DENY_NOT_CHEST,
        /** Protected zones for the dimension are not loaded yet, so protection is unknown. */
        DENY_ZONES_UNLOADED,
        /** Either half carries a container lock. */
        DENY_LOCKED,
        /** Territory authorization refused either half. */
        DENY_TERRITORY,
        /** The chest cannot be opened (vanilla {@code ChestBlock.isChestBlocked}). */
        DENY_BLOCKED,
        /** {@link #mayPrompt(Ownership)} is false. */
        DENY_OWNERSHIP
    }

    /**
     * What the adapter read about a chest, both halves already combined where a single flag
     * covers both.
     *
     * <p>For a single chest the caller passes {@code lockedB = false} and {@code territoryOkB = true};
     * those two B flags are then neutral. Any other value for a single chest is taken at face value
     * and denies.
     *
     * @param loaded        every half's chunk is loaded
     * @param chestEntities every half is a chest block entity
     * @param zonesLoaded   protected zones for the dimension are loaded
     * @param lockedA       half A carries a container lock
     * @param lockedB       half B carries a container lock ({@code false} for a single chest)
     * @param territoryOkA  territory authorization allows mutating half A
     * @param territoryOkB  territory authorization allows mutating half B ({@code true} for a single chest)
     * @param blocked       the chest cannot be opened
     * @param ownership     {@link #ownership} of the chest
     */
    public record AccessFacts(boolean loaded, boolean chestEntities, boolean zonesLoaded,
                              boolean lockedA, boolean lockedB, boolean territoryOkA,
                              boolean territoryOkB, boolean blocked, Ownership ownership) {
    }

    /**
     * Checks the facts in the order the {@link Access} constants are declared; the first failure
     * wins. {@code null} facts are {@link Access#DENY_UNLOADED}; a {@code null} ownership is
     * {@link Access#DENY_OWNERSHIP}.
     */
    public static Access access(AccessFacts f) {
        if (f == null || !f.loaded()) {
            return Access.DENY_UNLOADED;
        }
        if (!f.chestEntities()) {
            return Access.DENY_NOT_CHEST;
        }
        if (!f.zonesLoaded()) {
            return Access.DENY_ZONES_UNLOADED;
        }
        if (f.lockedA() || f.lockedB()) {
            return Access.DENY_LOCKED;
        }
        if (!f.territoryOkA() || !f.territoryOkB()) {
            return Access.DENY_TERRITORY;
        }
        if (f.blocked()) {
            return Access.DENY_BLOCKED;
        }
        if (!mayPrompt(f.ownership())) {
            return Access.DENY_OWNERSHIP;
        }
        return Access.OK;
    }

    // ── Stock ────────────────────────────────────────────────────────────────────────────────

    /**
     * One non-empty slot as the adapter read it.
     *
     * @param itemId       registry id
     * @param componentsFp {@link #componentsFp} of the stack's component changes ({@code null} = "")
     * @param count        stack size
     */
    public record SlotView(String itemId, String componentsFp, int count) {
    }

    /**
     * Counts {@code item} in a chest's slots (both halves of a double chest in one list), per the
     * {@link Stock} contract: {@code itemCount} is every stack with the same id and component
     * fingerprint; for equipment {@code typeCount} is every stack whose id maps to the same
     * {@link SupplyRequestPolicy#equipmentType equipment type}, whatever its tier or components
     * (each stack counted once, the exact item's stacks included). For a material, or an id that
     * is not equipment in {@code cfg}, {@code typeCount = itemCount}. Stacks of an equipment id
     * missing from the table (diamond, golden, …) are not spares of any type. Null slots, null ids
     * and non-positive counts are skipped.
     */
    public static Stock stock(List<SlotView> slots, ItemKey item, Config cfg) {
        if (slots == null || item == null) {
            return Stock.of(0);
        }
        String targetType = SupplyRequestPolicy.category(item.itemId(), cfg) == Category.EQUIPMENT
                ? SupplyRequestPolicy.equipmentType(item.itemId(), cfg) : null;
        long itemCount = 0;
        long typeCount = 0;
        for (SlotView slot : slots) {
            if (slot == null || slot.itemId() == null || slot.count() <= 0) {
                continue;
            }
            String fp = slot.componentsFp() == null ? "" : slot.componentsFp();
            if (slot.itemId().equals(item.itemId()) && fp.equals(item.componentsFp())) {
                itemCount += slot.count();
            }
            if (targetType != null && targetType.equals(SupplyRequestPolicy.equipmentType(slot.itemId(), cfg))) {
                typeCount += slot.count();
            }
        }
        int items = clampToInt(itemCount);
        return targetType == null ? Stock.of(items) : new Stock(items, clampToInt(typeCount));
    }

    // ── Transfer ─────────────────────────────────────────────────────────────────────────────

    /** Take {@code count} items from slot index {@code slot} of the list a plan was made from. */
    public record Take(int slot, int count) {
    }

    /**
     * How many more of exactly {@code item} fit in {@code slots} (the bot's main inventory, one
     * entry per slot, {@code null} for an empty slot). An empty slot holds {@code maxPerStack}; a
     * slot already holding the same id and component fingerprint holds the difference to
     * {@code maxPerStack}; any other slot holds none. The count is a lower bound: a component
     * fingerprint that reads differently for an equal stack only loses room, never invents it.
     */
    public static int capacity(List<SlotView> slots, ItemKey item, int maxPerStack) {
        if (slots == null || item == null || maxPerStack <= 0) {
            return 0;
        }
        long room = 0;
        for (SlotView slot : slots) {
            if (slot == null || slot.itemId() == null || slot.count() <= 0) {
                room += maxPerStack;
            } else if (matches(slot, item)) {
                room += Math.max(0, maxPerStack - slot.count());
            }
        }
        return clampToInt(room);
    }

    /**
     * Which chest slots to take {@code quantity} of exactly {@code item} from: matching slots
     * (same id and component fingerprint) in slot order, as much of each as still needed. The
     * plan never totals more than {@code quantity}; it totals less only when the slots hold less.
     * Indices refer to positions in {@code slots}; {@code null} entries are empty slots.
     */
    public static List<Take> withdrawPlan(List<SlotView> slots, ItemKey item, int quantity) {
        if (slots == null || item == null || quantity <= 0) {
            return List.of();
        }
        List<Take> plan = new ArrayList<>();
        int remaining = quantity;
        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            SlotView slot = slots.get(i);
            if (slot == null || slot.itemId() == null || slot.count() <= 0 || !matches(slot, item)) {
                continue;
            }
            int take = Math.min(slot.count(), remaining);
            plan.add(new Take(i, take));
            remaining -= take;
        }
        return List.copyOf(plan);
    }

    private static boolean matches(SlotView slot, ItemKey item) {
        String fp = slot.componentsFp() == null ? "" : slot.componentsFp();
        return slot.itemId().equals(item.itemId()) && fp.equals(item.componentsFp());
    }

    /**
     * Why a transfer the ledger did not permit moved nothing: the policy refused the chest's stock
     * or the bot's need as they are now ({@link TransferStatus#INELIGIBLE_NOW}; the grant or
     * permission still stands), or nothing covers it at all — no grant, an expired one, one for
     * fewer items, no standing permission ({@link TransferStatus#NOT_PERMITTED}). Only for a
     * {@code consumeGrant} that did not permit the move.
     */
    public static TransferStatus transferRefusal(ConsumeStatus status) {
        return status == ConsumeStatus.INELIGIBLE ? TransferStatus.INELIGIBLE_NOW : TransferStatus.NOT_PERMITTED;
    }

    // ── Item components ──────────────────────────────────────────────────────────────────────

    /** Joins the entries of a {@link #componentsFp} fingerprint. Registry ids never contain it. */
    public static final String FP_SEPARATOR = ";";

    /**
     * The {@link ItemKey#componentsFp()} of a stack, from its component changes relative to the
     * item's defaults.
     *
     * <p>{@code changes} maps a component type id to its value encoded as JSON, or to {@code null}
     * when the change removes a default component. Each entry becomes {@code id=json}, or
     * {@code !id} for a removal, and entries are joined by {@link #FP_SEPARATOR} in natural key
     * order whatever the map's own comparator is, so equal changes always give equal text. No
     * changes (or a {@code null} map) is {@code ""}, the same as {@link ItemKey#plain}.
     */
    public static String componentsFp(SortedMap<String, String> changes) {
        if (changes == null || changes.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : naturalOrder(changes).entrySet()) {
            if (out.length() > 0) {
                out.append(FP_SEPARATOR);
            }
            if (entry.getValue() == null) {
                out.append('!').append(entry.getKey());
            } else {
                out.append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        return out.toString();
    }

    /**
     * The {@link ItemKey#nonDefaultComponentIds()} of a stack: every changed component id,
     * removals included. Counting a removal as a non-default component fails safe — a stack with
     * a default component stripped reads as {@code PROTECTED_COMPONENTS}.
     */
    public static Set<String> nonDefaultComponentIds(SortedMap<String, String> changes) {
        if (changes == null || changes.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (String id : changes.keySet()) {
            ids.add(String.valueOf(id));
        }
        return Set.copyOf(ids);
    }

    // ── World identity ───────────────────────────────────────────────────────────────────────

    /**
     * The {@link ChestKey#worldId()} for a chest: the save's identity, then the dimension. The
     * adapter passes {@code BotWorldStateService.currentWorldKey(server)} as {@code saveKey}
     * ({@code levelName#rootPathHash}); a level name alone is not unique across saves.
     *
     * @throws IllegalArgumentException if either part is null or blank
     */
    public static String worldId(String saveKey, String dimensionId) {
        if (saveKey == null || saveKey.isBlank() || dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("saveKey and dimensionId must not be blank");
        }
        return saveKey + "/" + dimensionId;
    }

    // ── Revoke ───────────────────────────────────────────────────────────────────────────────

    /**
     * Every key a standing permission for the chest at {@code half} may be stored under: the
     * chest's {@link ChestKey#canonical canonical} key, {@code half}'s own single-chest key, and,
     * for a double chest, the partner's own single-chest key. A permission is stored under the
     * canonical key of the chest as it was when granted, so a chest that has since gained or lost
     * a half would otherwise keep a stale permission that revives when the chest changes back.
     *
     * <p>A partner that is not a horizontal neighbour on the same y is ignored, exactly as
     * {@link ChestKey#canonical} ignores it, so a mis-resolved partner never revokes an unrelated
     * chest. Duplicates are removed; the canonical key comes first.
     */
    public static List<ChestKey> revokeKeys(String worldId, Pos half, Pos partnerOrNull) {
        Objects.requireNonNull(half, "half");
        Pos partner = partnerOrNull != null && half.isHorizontalNeighbour(partnerOrNull) ? partnerOrNull : null;
        Set<ChestKey> keys = new LinkedHashSet<>();
        keys.add(ChestKey.canonical(worldId, half, partner));
        keys.add(ChestKey.canonical(worldId, half, null));
        if (partner != null) {
            keys.add(ChestKey.canonical(worldId, partner, null));
        }
        return List.copyOf(keys);
    }

    // ── ALWAYS persistence ───────────────────────────────────────────────────────────────────

    /** Version written by {@link #encodeAlways}. */
    public static final int ALWAYS_FORMAT_VERSION = 1;

    /** A namespaced identifier: {@code namespace:path}, lower case, no {@code '/'} in the namespace. */
    private static final Pattern DIMENSION_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /**
     * Serialises standing permissions for the per-save file: owner, dimension and position only.
     *
     * <p>The save-key prefix is stripped because the file lives inside that save. A level name may
     * contain {@code '/'} and {@code ':'}, so the dimension is found by identifier grammar rather
     * than by the first {@code '/'}: a dimension id has exactly one {@code ':'} (the last one in the
     * world id) and no {@code '/'} before it, so the dimension starts after the last {@code '/'}
     * that precedes the last {@code ':'}. A scope whose world id yields no valid dimension is
     * skipped. Output is sorted so equal sets write identical files.
     */
    public static String encodeAlways(Collection<AlwaysScope> scopes) {
        List<Entry> entries = new ArrayList<>();
        if (scopes != null) {
            for (AlwaysScope scope : scopes) {
                if (scope == null) {
                    continue;
                }
                String dimension = dimensionOf(scope.chest().worldId());
                if (dimension == null) {
                    continue;
                }
                ChestKey chest = scope.chest();
                entries.add(new Entry(scope.owner().toString(), dimension, chest.x(), chest.y(), chest.z()));
            }
        }
        entries.sort(Comparator.comparing(Entry::owner).thenComparing(Entry::dimension)
                .thenComparingInt(Entry::x).thenComparingInt(Entry::y).thenComparingInt(Entry::z));
        JsonArray array = new JsonArray();
        for (Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("owner", e.owner());
            o.addProperty("dimension", e.dimension());
            o.addProperty("x", e.x());
            o.addProperty("y", e.y());
            o.addProperty("z", e.z());
            array.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", ALWAYS_FORMAT_VERSION);
        root.add("always", array);
        return root.toString();
    }

    /**
     * Reads what {@link #encodeAlways} wrote, re-prefixing each dimension with {@code saveKey}.
     * Never throws: malformed JSON, a missing or blank {@code saveKey}, or a root without an
     * {@code "always"} array gives an empty list, and an entry with a bad owner, dimension or
     * coordinate (non-integer or out of int range) is skipped.
     */
    public static List<AlwaysScope> decodeAlways(String json, String saveKey) {
        if (json == null || json.isBlank() || saveKey == null || saveKey.isBlank()) {
            return List.of();
        }
        JsonArray array;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return List.of();
            }
            JsonElement always = root.getAsJsonObject().get("always");
            if (always == null || !always.isJsonArray()) {
                return List.of();
            }
            array = always.getAsJsonArray();
        } catch (RuntimeException malformed) {
            return List.of();
        }
        List<AlwaysScope> out = new ArrayList<>();
        for (JsonElement element : array) {
            AlwaysScope scope = decodeEntry(element, saveKey);
            if (scope != null) {
                out.add(scope);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The {@code "version"} a saved ALWAYS file declares, or {@code null} when the text is not a
     * JSON object or has no integer {@code "version"}. Never throws. {@link #decodeAlways} does not
     * look at the version; the adapter compares this with {@link #ALWAYS_FORMAT_VERSION} to warn.
     */
    public static Integer alwaysFileVersion(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            return root.isJsonObject() ? intField(root.getAsJsonObject(), "version") : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * How many entries a saved ALWAYS file's {@code "always"} array holds, readable or not, or
     * {@code null} when the text is not a JSON object or has no {@code "always"} array. Never
     * throws. Compared with what {@link #decodeAlways} returned, it tells a deliberately empty
     * file (every permission revoked) from one whose entries could not be read.
     */
    public static Integer alwaysEntryCount(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonElement always = root.getAsJsonObject().get("always");
            return always != null && always.isJsonArray() ? always.getAsJsonArray().size() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Whether loading a saved ALWAYS file deserves a WARN: its version is missing or not
     * {@link #ALWAYS_FORMAT_VERSION}, its entry array is missing or unreadable, or fewer entries
     * were restored than it holds. A current-version file with an empty array — what revoking the
     * last permission writes — is quiet.
     *
     * @param version    {@link #alwaysFileVersion} of the file
     * @param entryCount {@link #alwaysEntryCount} of the file
     * @param restored   how many permissions {@link #decodeAlways} gave back
     */
    public static boolean alwaysLoadWarns(Integer version, Integer entryCount, int restored) {
        return version == null || version != ALWAYS_FORMAT_VERSION || entryCount == null || restored < entryCount;
    }

    // ── Prompt and command text ──────────────────────────────────────────────────────────────

    /** Root literal of the supplies commands; verified free (no other {@code /frens} root exists). */
    public static final String COMMAND_ROOT = "frens";
    public static final String COMMAND_SUPPLY = "supply";
    public static final String COMMAND_ANSWER = "answer";
    public static final String COMMAND_REVOKE = "revoke";
    /** The literal after {@link #COMMAND_REVOKE} that withdraws every standing permission at once. */
    public static final String COMMAND_REVOKE_ALL = "all";

    /** The question shown to the owner, before the clickable choices. */
    public static String promptText(String botName, int qty, String itemName, int x, int y, int z) {
        String who = botName == null || botName.isBlank() ? "Your companion" : botName;
        String what = itemName == null || itemName.isBlank() ? "items" : itemName;
        return who + " asks to take " + qty + " " + what + " from the chest at " + x + ", " + y + ", " + z + ".";
    }

    /** The command token for a choice: {@code once}, {@code always} or {@code no}. */
    public static String choiceToken(Choice choice) {
        Objects.requireNonNull(choice, "choice");
        return switch (choice) {
            case ALLOW_ONCE -> "once";
            case ALWAYS_COMMON -> "always";
            case NO -> "no";
        };
    }

    /** Exactly {@code /frens supply answer <requestId> once|always|no}, for a clickable choice. */
    public static String answerCommand(UUID requestId, Choice choice) {
        Objects.requireNonNull(requestId, "requestId");
        return "/" + COMMAND_ROOT + " " + COMMAND_SUPPLY + " " + COMMAND_ANSWER + " " + requestId
                + " " + choiceToken(choice);
    }

    /** Exactly {@code /frens supply revoke <x> <y> <z>}, for telling the owner how to undo "always". */
    public static String revokeCommand(int x, int y, int z) {
        return "/" + COMMAND_ROOT + " " + COMMAND_SUPPLY + " " + COMMAND_REVOKE + " " + x + " " + y + " " + z;
    }

    /** Exactly {@code /frens supply revoke all}: withdraws every standing permission the caller gave. */
    public static String revokeAllCommand() {
        return "/" + COMMAND_ROOT + " " + COMMAND_SUPPLY + " " + COMMAND_REVOKE + " " + COMMAND_REVOKE_ALL;
    }

    /** The choice for {@code once}, {@code always} or {@code no}, ignoring case and surrounding space. */
    public static Optional<Choice> parseChoice(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return switch (token.trim().toLowerCase(Locale.ROOT)) {
            case "once" -> Optional.of(Choice.ALLOW_ONCE);
            case "always" -> Optional.of(Choice.ALWAYS_COMMON);
            case "no" -> Optional.of(Choice.NO);
            default -> Optional.empty();
        };
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private record Entry(String owner, String dimension, int x, int y, int z) {
    }

    private static AlwaysScope decodeEntry(JsonElement element, String saveKey) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject o = element.getAsJsonObject();
        UUID owner = parseUuid(stringField(o, "owner"));
        String dimension = stringField(o, "dimension");
        Integer x = intField(o, "x");
        Integer y = intField(o, "y");
        Integer z = intField(o, "z");
        if (owner == null || dimension == null || !DIMENSION_ID.matcher(dimension).matches()
                || x == null || y == null || z == null) {
            return null;
        }
        return new AlwaysScope(owner, new ChestKey(worldId(saveKey, dimension), x, y, z));
    }

    private static String stringField(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return null;
        }
        return e.getAsString();
    }

    private static Integer intField(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) {
            return null;
        }
        try {
            return p.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException notAnInt) {
            return null;
        }
    }

    /** The dimension part of a world id, or {@code null} if there is no valid one. */
    private static String dimensionOf(String worldId) {
        if (worldId == null) {
            return null;
        }
        int colon = worldId.lastIndexOf(':');
        if (colon < 0) {
            return null;
        }
        int slash = worldId.lastIndexOf('/', colon);
        if (slash <= 0) {
            return null;
        }
        String dimension = worldId.substring(slash + 1);
        return DIMENSION_ID.matcher(dimension).matches() ? dimension : null;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static SortedMap<String, String> naturalOrder(SortedMap<String, String> changes) {
        if (changes.comparator() == null) {
            return changes;
        }
        SortedMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : changes.entrySet()) {
            sorted.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return sorted;
    }

    private static int clampToInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, value);
    }
}
