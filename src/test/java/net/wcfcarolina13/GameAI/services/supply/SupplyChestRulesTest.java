package net.wcfcarolina13.GameAI.services.supply;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.wcfcarolina13.GameAI.services.supply.SupplyChestRules.Access;
import net.wcfcarolina13.GameAI.services.supply.SupplyChestRules.AccessFacts;
import net.wcfcarolina13.GameAI.services.supply.SupplyChestRules.Ownership;
import net.wcfcarolina13.GameAI.services.supply.SupplyChestRules.SlotView;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.AlwaysScope;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Timings;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Choice;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Config;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Stock;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyChestRulesTest {

    private static final UUID OWNER = new UUID(1, 1);
    private static final UUID OTHER = new UUID(1, 2);
    private static final String O = OWNER.toString();
    private static final String F = OTHER.toString();
    private static final Config CFG = Config.defaults();

    private static List<String> recs(String... owners) {
        return Arrays.asList(owners); // null elements allowed, unlike List.of
    }

    // ── ownership ────────────────────────────────────────────────────────────────────────────

    @Test
    void noRecordOnAnyHalfIsPlayerStorage() {
        assertEquals(Ownership.PLAYER_STORAGE, SupplyChestRules.ownership(OWNER, recs(), null));
        assertEquals(Ownership.PLAYER_STORAGE, SupplyChestRules.ownership(OWNER, recs(), recs()));
    }

    @Test
    void everyRecordNamingTheBotsOwnerIsOwnerStorage() {
        assertEquals(Ownership.OWNER_STORAGE, SupplyChestRules.ownership(OWNER, recs(O), null));
        assertEquals(Ownership.OWNER_STORAGE, SupplyChestRules.ownership(OWNER, recs(O, O), null));
        assertEquals(Ownership.OWNER_STORAGE, SupplyChestRules.ownership(OWNER, recs(O), recs(O)));
        // UUID text is compared as a UUID, not as a string.
        assertEquals(Ownership.OWNER_STORAGE,
                SupplyChestRules.ownership(OWNER, recs(O.toUpperCase()), null));
    }

    @Test
    void anyOtherOwnerIsForeign() {
        assertEquals(Ownership.DENY_FOREIGN, SupplyChestRules.ownership(OWNER, recs(F), null));
        assertEquals(Ownership.DENY_FOREIGN, SupplyChestRules.ownership(OWNER, recs(O, F), null));
        assertEquals(Ownership.DENY_FOREIGN, SupplyChestRules.ownership(OWNER, recs(O), recs(F)));
        assertEquals(Ownership.DENY_FOREIGN, SupplyChestRules.ownership(OWNER, recs(F), recs(F)));
    }

    @Test
    void anyUnreadableOwnerIsUnknown() {
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(OWNER, recs((String) null), null));
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(OWNER, recs(""), null));
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(OWNER, recs("   "), null));
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(OWNER, recs("not-a-uuid"), null));
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(OWNER, recs(O), recs((String) null)));
    }

    @Test
    void oneRecordedHalfOfADoubleChestIsMixed() {
        assertEquals(Ownership.DENY_MIXED, SupplyChestRules.ownership(OWNER, recs(O), recs()));
        assertEquals(Ownership.DENY_MIXED, SupplyChestRules.ownership(OWNER, recs(), recs(O)));
    }

    @Test
    void aBotWithoutAnOwnerOrAMissingLookupIsUnknown() {
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(null, recs(), null));
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(null, recs(O), recs(O)));
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(null, recs(F), null));
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(OWNER, null, null));
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(OWNER, null, recs()));
    }

    @Test
    void precedenceIsForeignThenUnknownThenMixed() {
        // foreign beats unknown, on one half or across halves
        assertEquals(Ownership.DENY_FOREIGN, SupplyChestRules.ownership(OWNER, recs(O, F, null), null));
        assertEquals(Ownership.DENY_FOREIGN, SupplyChestRules.ownership(OWNER, recs((String) null), recs(F)));
        // foreign beats mixed
        assertEquals(Ownership.DENY_FOREIGN, SupplyChestRules.ownership(OWNER, recs(F), recs()));
        assertEquals(Ownership.DENY_FOREIGN, SupplyChestRules.ownership(OWNER, recs(), recs(F)));
        // unknown beats owner and mixed
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(OWNER, recs(O, null), null));
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(OWNER, recs((String) null), recs()));
        assertEquals(Ownership.DENY_UNKNOWN, SupplyChestRules.ownership(OWNER, recs(), recs("junk")));
    }

    @Test
    void onlyPlayerAndOwnerStorageMayPrompt() {
        assertTrue(SupplyChestRules.mayPrompt(Ownership.PLAYER_STORAGE));
        assertTrue(SupplyChestRules.mayPrompt(Ownership.OWNER_STORAGE));
        assertFalse(SupplyChestRules.mayPrompt(Ownership.DENY_FOREIGN));
        assertFalse(SupplyChestRules.mayPrompt(Ownership.DENY_UNKNOWN));
        assertFalse(SupplyChestRules.mayPrompt(Ownership.DENY_MIXED));
        assertFalse(SupplyChestRules.mayPrompt(null));
    }

    // ── access ───────────────────────────────────────────────────────────────────────────────

    /** Facts that pass every check except the named ones. */
    private static AccessFacts facts(String... failing) {
        Set<String> f = Set.of(failing);
        return new AccessFacts(!f.contains("loaded"), !f.contains("chest"), !f.contains("zones"),
                f.contains("lockedA"), f.contains("lockedB"), !f.contains("territoryA"),
                !f.contains("territoryB"), f.contains("blocked"),
                f.contains("ownership") ? Ownership.DENY_FOREIGN : Ownership.PLAYER_STORAGE);
    }

    @Test
    void cleanFactsAreOk() {
        assertEquals(Access.OK, SupplyChestRules.access(facts()));
        AccessFacts owners = new AccessFacts(true, true, true, false, false, true, true, false,
                Ownership.OWNER_STORAGE);
        assertEquals(Access.OK, SupplyChestRules.access(owners));
    }

    @Test
    void eachCheckDeniesOnItsOwn() {
        assertEquals(Access.DENY_UNLOADED, SupplyChestRules.access(facts("loaded")));
        assertEquals(Access.DENY_NOT_CHEST, SupplyChestRules.access(facts("chest")));
        assertEquals(Access.DENY_ZONES_UNLOADED, SupplyChestRules.access(facts("zones")));
        assertEquals(Access.DENY_LOCKED, SupplyChestRules.access(facts("lockedA")));
        assertEquals(Access.DENY_LOCKED, SupplyChestRules.access(facts("lockedB")));
        assertEquals(Access.DENY_TERRITORY, SupplyChestRules.access(facts("territoryA")));
        assertEquals(Access.DENY_TERRITORY, SupplyChestRules.access(facts("territoryB")));
        assertEquals(Access.DENY_BLOCKED, SupplyChestRules.access(facts("blocked")));
        assertEquals(Access.DENY_OWNERSHIP, SupplyChestRules.access(facts("ownership")));
    }

    @Test
    void checksRunInDeclaredOrderAndTheFirstFailureWins() {
        List<String> all = List.of("loaded", "chest", "zones", "lockedA", "territoryA", "blocked", "ownership");
        List<Access> expected = List.of(Access.DENY_UNLOADED, Access.DENY_NOT_CHEST,
                Access.DENY_ZONES_UNLOADED, Access.DENY_LOCKED, Access.DENY_TERRITORY,
                Access.DENY_BLOCKED, Access.DENY_OWNERSHIP);
        for (int i = 0; i < all.size(); i++) {
            String[] stillFailing = all.subList(i, all.size()).toArray(new String[0]);
            assertEquals(expected.get(i), SupplyChestRules.access(facts(stillFailing)), "from " + all.get(i));
        }
    }

    @Test
    void everyDenyingOwnershipAndMissingFactsFailClosed() {
        for (Ownership o : List.of(Ownership.DENY_FOREIGN, Ownership.DENY_UNKNOWN, Ownership.DENY_MIXED)) {
            AccessFacts f = new AccessFacts(true, true, true, false, false, true, true, false, o);
            assertEquals(Access.DENY_OWNERSHIP, SupplyChestRules.access(f), o.name());
        }
        assertEquals(Access.DENY_OWNERSHIP, SupplyChestRules.access(
                new AccessFacts(true, true, true, false, false, true, true, false, null)));
        assertEquals(Access.DENY_UNLOADED, SupplyChestRules.access(null));
    }

    // ── stock ────────────────────────────────────────────────────────────────────────────────

    private static SlotView slot(String id, int count) {
        return new SlotView("minecraft:" + id, "", count);
    }

    private static SlotView slot(String id, String fp, int count) {
        return new SlotView("minecraft:" + id, fp, count);
    }

    @Test
    void materialsCountTheExactItemAcrossBothHalves() {
        List<SlotView> slots = List.of(slot("cobblestone", 30), slot("dirt", 64), slot("cobblestone", 20),
                slot("stone_axe", 1));
        assertEquals(new Stock(50, 50), SupplyChestRules.stock(slots, ItemKey.plain("minecraft:cobblestone"), CFG));
        assertEquals(new Stock(64, 64), SupplyChestRules.stock(slots, ItemKey.plain("minecraft:dirt"), CFG));
        assertEquals(new Stock(0, 0), SupplyChestRules.stock(slots, ItemKey.plain("minecraft:torch"), CFG));
    }

    @Test
    void materialsWithOtherComponentsAreNotTheSameItem() {
        List<SlotView> slots = List.of(slot("cobblestone", 30), slot("cobblestone", "minecraft:custom_name=\"x\"", 10));
        assertEquals(new Stock(30, 30), SupplyChestRules.stock(slots, ItemKey.plain("minecraft:cobblestone"), CFG));
    }

    @Test
    void equipmentTypeCountsEveryTierAndComponentOfTheSameType() {
        List<SlotView> slots = List.of(
                slot("stone_axe", 1),
                slot("stone_axe", "minecraft:damage=5", 1),
                slot("wooden_axe", 1),
                slot("iron_axe", "minecraft:enchantments={}", 1),
                slot("diamond_axe", 1),     // not in the table: not a spare of any type
                slot("stone_pickaxe", 1),
                slot("cobblestone", 64));
        Stock stock = SupplyChestRules.stock(slots, ItemKey.plain("minecraft:stone_axe"), CFG);
        assertEquals(new Stock(1, 4), stock);
        // The one spare axe stays; the plain stone axe may go.
        assertEquals(1, SupplyRequestPolicy.grantable(ItemKey.plain("minecraft:stone_axe"), stock, 1, 1, CFG));
    }

    @Test
    void theExactItemIsCountedOnceInTheTypeCount() {
        List<SlotView> slots = List.of(slot("stone_axe", 1));
        Stock stock = SupplyChestRules.stock(slots, ItemKey.plain("minecraft:stone_axe"), CFG);
        assertEquals(new Stock(1, 1), stock);
        assertEquals(0, SupplyRequestPolicy.grantable(ItemKey.plain("minecraft:stone_axe"), stock, 1, 1, CFG));
    }

    @Test
    void aComponentFingerprintMismatchMovesAStackOutOfItemCountButNotTypeCount() {
        List<SlotView> slots = List.of(slot("stone_axe", 1), slot("stone_axe", "minecraft:damage=5", 2));
        ItemKey worn = new ItemKey("minecraft:stone_axe", "minecraft:damage=5", Set.of("minecraft:damage"));
        assertEquals(new Stock(2, 3), SupplyChestRules.stock(slots, worn, CFG));
        assertEquals(new Stock(1, 3), SupplyChestRules.stock(slots, ItemKey.plain("minecraft:stone_axe"), CFG));
    }

    @Test
    void itemsOutsideTheTablesCountOnlyThemselves() {
        List<SlotView> slots = List.of(slot("diamond_sword", 2), slot("stone_sword", 1), slot("cobblestone", 5));
        assertEquals(new Stock(2, 2), SupplyChestRules.stock(slots, ItemKey.plain("minecraft:diamond_sword"), CFG));
    }

    @Test
    void junkSlotsAndMissingInputsAreSkipped() {
        List<SlotView> slots = new ArrayList<>();
        slots.add(null);
        slots.add(new SlotView(null, "", 5));
        slots.add(slot("cobblestone", 0));
        slots.add(slot("cobblestone", -4));
        slots.add(new SlotView("minecraft:cobblestone", null, 7)); // null fp reads as ""
        assertEquals(new Stock(7, 7), SupplyChestRules.stock(slots, ItemKey.plain("minecraft:cobblestone"), CFG));
        assertEquals(new Stock(0, 0), SupplyChestRules.stock(null, ItemKey.plain("minecraft:cobblestone"), CFG));
        assertEquals(new Stock(0, 0), SupplyChestRules.stock(slots, null, CFG));
    }

    // ── component fingerprint ────────────────────────────────────────────────────────────────

    @Test
    void noChangesIsTheEmptyFingerprint() {
        assertEquals("", SupplyChestRules.componentsFp(new TreeMap<>()));
        assertEquals("", SupplyChestRules.componentsFp(null));
        assertEquals(ItemKey.plain("minecraft:stone_axe").componentsFp(), SupplyChestRules.componentsFp(new TreeMap<>()));
        assertEquals(Set.of(), SupplyChestRules.nonDefaultComponentIds(new TreeMap<>()));
        assertEquals(Set.of(), SupplyChestRules.nonDefaultComponentIds(null));
    }

    @Test
    void entriesAreSortedAndRemovalsAreMarked() {
        SortedMap<String, String> changes = new TreeMap<>();
        changes.put("minecraft:repair_cost", "1");
        changes.put("minecraft:food", null);
        changes.put("minecraft:damage", "5");
        assertEquals("minecraft:damage=5;!minecraft:food;minecraft:repair_cost=1",
                SupplyChestRules.componentsFp(changes));
        assertEquals(Set.of("minecraft:damage", "minecraft:food", "minecraft:repair_cost"),
                SupplyChestRules.nonDefaultComponentIds(changes));
    }

    @Test
    void theFingerprintIgnoresInsertionOrderAndTheMapsComparator() {
        SortedMap<String, String> natural = new TreeMap<>();
        natural.put("minecraft:damage", "5");
        natural.put("minecraft:custom_name", "\"Axey\"");
        natural.put("minecraft:food", null);
        SortedMap<String, String> reversed = new TreeMap<>(Comparator.reverseOrder());
        reversed.put("minecraft:food", null);
        reversed.put("minecraft:custom_name", "\"Axey\"");
        reversed.put("minecraft:damage", "5");
        assertEquals(SupplyChestRules.componentsFp(natural), SupplyChestRules.componentsFp(reversed));
        assertEquals(SupplyChestRules.nonDefaultComponentIds(natural), SupplyChestRules.nonDefaultComponentIds(reversed));
    }

    @Test
    void aRemovalIsNotTheSameAsAValue() {
        SortedMap<String, String> removed = new TreeMap<>();
        removed.put("minecraft:damage", null);
        SortedMap<String, String> zero = new TreeMap<>();
        zero.put("minecraft:damage", "0");
        assertEquals("!minecraft:damage", SupplyChestRules.componentsFp(removed));
        assertFalse(SupplyChestRules.componentsFp(removed).equals(SupplyChestRules.componentsFp(zero)));
    }

    @Test
    void derivedKeysClassifyAsThePolicyExpects() {
        SortedMap<String, String> worn = new TreeMap<>();
        worn.put("minecraft:damage", "5");
        ItemKey wornAxe = new ItemKey("minecraft:stone_axe", SupplyChestRules.componentsFp(worn),
                SupplyChestRules.nonDefaultComponentIds(worn));
        assertEquals(Verdict.ELIGIBLE, SupplyRequestPolicy.classify(wornAxe, CFG));

        // A stripped default component counts as non-default, so the stack is protected.
        SortedMap<String, String> stripped = new TreeMap<>();
        stripped.put("minecraft:tool", null);
        ItemKey strippedAxe = new ItemKey("minecraft:stone_axe", SupplyChestRules.componentsFp(stripped),
                SupplyChestRules.nonDefaultComponentIds(stripped));
        assertEquals(Verdict.PROTECTED_COMPONENTS, SupplyRequestPolicy.classify(strippedAxe, CFG));
    }

    // ── world id ─────────────────────────────────────────────────────────────────────────────

    @Test
    void worldIdJoinsSaveKeyAndDimension() {
        assertEquals("New World#1a2b/minecraft:overworld",
                SupplyChestRules.worldId("New World#1a2b", "minecraft:overworld"));
        assertThrows(IllegalArgumentException.class, () -> SupplyChestRules.worldId(null, "minecraft:overworld"));
        assertThrows(IllegalArgumentException.class, () -> SupplyChestRules.worldId(" ", "minecraft:overworld"));
        assertThrows(IllegalArgumentException.class, () -> SupplyChestRules.worldId("New World#1a2b", ""));
    }

    // ── ALWAYS codec ─────────────────────────────────────────────────────────────────────────

    /** A level name may hold '/' and ':'; the dimension must still split off cleanly. */
    private static final String SAVE = "My/Odd:World#1a2b3c";
    private static final String OTHER_SAVE = "Copied World#99ff";

    private static AlwaysScope scope(UUID owner, String save, String dim, int x, int y, int z) {
        return new AlwaysScope(owner, new ChestKey(SupplyChestRules.worldId(save, dim), x, y, z));
    }

    private static Set<AlwaysScope> sampleScopes(String save) {
        Set<AlwaysScope> scopes = new LinkedHashSet<>();
        scopes.add(scope(OWNER, save, "minecraft:overworld", 10, 64, -5));
        scopes.add(scope(OWNER, save, "minecraft:the_nether", 1, 70, 1));
        scopes.add(scope(OTHER, save, "minecraft:overworld", 10, 64, -5));
        scopes.add(scope(OWNER, save, "mymod:dims/deep_dark", -300, -40, 2_000_000));
        return scopes;
    }

    @Test
    void codecRoundTripsUnderTheSameSaveKey() {
        Set<AlwaysScope> scopes = sampleScopes(SAVE);
        String json = SupplyChestRules.encodeAlways(scopes);
        List<AlwaysScope> decoded = SupplyChestRules.decodeAlways(json, SAVE);
        assertEquals(scopes.size(), decoded.size());
        assertEquals(scopes, new HashSet<>(decoded));
    }

    @Test
    void theFileStoresOwnerDimensionAndPositionWithoutTheSaveKey() {
        String json = SupplyChestRules.encodeAlways(Set.of(scope(OWNER, SAVE, "minecraft:overworld", 10, 64, -5)));
        assertFalse(json.contains("Odd"), json);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(SupplyChestRules.ALWAYS_FORMAT_VERSION, root.get("version").getAsInt());
        JsonArray always = root.getAsJsonArray("always");
        assertEquals(1, always.size());
        JsonObject entry = always.get(0).getAsJsonObject();
        assertEquals(Set.of("owner", "dimension", "x", "y", "z"), entry.keySet());
        assertEquals(O, entry.get("owner").getAsString());
        assertEquals("minecraft:overworld", entry.get("dimension").getAsString());
        assertEquals(-5, entry.get("z").getAsInt());
    }

    @Test
    void decodingReprefixesWithTheCurrentSaveKey() {
        String json = SupplyChestRules.encodeAlways(sampleScopes(SAVE));
        assertEquals(sampleScopes(OTHER_SAVE), new HashSet<>(SupplyChestRules.decodeAlways(json, OTHER_SAVE)));
    }

    @Test
    void encodingIsDeterministicWhateverTheSetOrder() {
        List<AlwaysScope> forward = new ArrayList<>(sampleScopes(SAVE));
        List<AlwaysScope> backward = new ArrayList<>(forward);
        Collections.reverse(backward);
        assertEquals(SupplyChestRules.encodeAlways(forward), SupplyChestRules.encodeAlways(backward));
    }

    @Test
    void encodingSkipsNullsAndWorldIdsWithoutADimension() {
        List<AlwaysScope> scopes = new ArrayList<>();
        scopes.add(null);
        scopes.add(new AlwaysScope(OWNER, new ChestKey("no-dimension-here", 1, 2, 3)));
        scopes.add(new AlwaysScope(OWNER, new ChestKey("minecraft:overworld", 1, 2, 3))); // no save key
        scopes.add(scope(OWNER, SAVE, "minecraft:overworld", 1, 2, 3));
        List<AlwaysScope> decoded = SupplyChestRules.decodeAlways(SupplyChestRules.encodeAlways(scopes), SAVE);
        assertEquals(List.of(scope(OWNER, SAVE, "minecraft:overworld", 1, 2, 3)), decoded);

        assertEquals(List.of(), SupplyChestRules.decodeAlways(SupplyChestRules.encodeAlways(null), SAVE));
        assertEquals(List.of(), SupplyChestRules.decodeAlways(SupplyChestRules.encodeAlways(List.of()), SAVE));
    }

    @Test
    void malformedFilesDecodeToNothing() {
        for (String json : Arrays.asList(null, "", "   ", "not json", "{", "[]", "{}", "{\"always\":5}",
                "{\"always\":{}}", "\"text\"", "{\"always\":[1,2]}")) {
            assertEquals(List.of(), SupplyChestRules.decodeAlways(json, SAVE), String.valueOf(json));
        }
        String good = SupplyChestRules.encodeAlways(Set.of(scope(OWNER, SAVE, "minecraft:overworld", 1, 2, 3)));
        assertEquals(List.of(), SupplyChestRules.decodeAlways(good, null));
        assertEquals(List.of(), SupplyChestRules.decodeAlways(good, " "));
    }

    @Test
    void malformedEntriesAreSkippedAndTheRestKept() {
        String valid = "{\"owner\":\"" + O + "\",\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3}";
        List<String> bad = List.of(
                "null",
                "7",
                "{}",
                "{\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3}",
                "{\"owner\":\"nope\",\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3}",
                "{\"owner\":42,\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3}",
                "{\"owner\":\"" + O + "\",\"dimension\":\"\",\"x\":1,\"y\":2,\"z\":3}",
                "{\"owner\":\"" + O + "\",\"dimension\":\"overworld\",\"x\":1,\"y\":2,\"z\":3}",
                "{\"owner\":\"" + O + "\",\"dimension\":\"Minecraft:Overworld\",\"x\":1,\"y\":2,\"z\":3}",
                "{\"owner\":\"" + O + "\",\"dimension\":\"minecraft:overworld\",\"x\":1.5,\"y\":2,\"z\":3}",
                "{\"owner\":\"" + O + "\",\"dimension\":\"minecraft:overworld\",\"x\":\"1\",\"y\":2,\"z\":3}",
                "{\"owner\":\"" + O + "\",\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":3000000000,\"z\":3}",
                "{\"owner\":\"" + O + "\",\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2}");
        String json = "{\"version\":1,\"always\":[" + String.join(",", bad) + "," + valid + "]}";
        assertEquals(List.of(scope(OWNER, SAVE, "minecraft:overworld", 1, 2, 3)),
                SupplyChestRules.decodeAlways(json, SAVE));
    }

    @Test
    void aSavedSnapshotRestoresIntoAFreshLedger() {
        long[] now = {0L};
        long[] ids = {0L};
        SupplyRequestLedger first = new SupplyRequestLedger(() -> now[0], () -> new UUID(9, ++ids[0]),
                Timings.defaults(), CFG);
        for (AlwaysScope s : sampleScopes(SAVE)) {
            first.restoreAlways(s.owner(), s.chest());
        }
        String json = SupplyChestRules.encodeAlways(first.alwaysSnapshot());

        SupplyRequestLedger second = new SupplyRequestLedger(() -> now[0], () -> new UUID(9, ++ids[0]),
                Timings.defaults(), CFG);
        for (AlwaysScope s : SupplyChestRules.decodeAlways(json, SAVE)) {
            second.restoreAlways(s.owner(), s.chest());
        }
        assertEquals(first.alwaysSnapshot(), second.alwaysSnapshot());
    }

    // ── prompt and command text ──────────────────────────────────────────────────────────────

    @Test
    void answerCommandsHaveTheExactShape() {
        UUID id = new UUID(0x1234L, 0x5678L);
        assertEquals("/frens supply answer " + id + " once", SupplyChestRules.answerCommand(id, Choice.ALLOW_ONCE));
        assertEquals("/frens supply answer " + id + " always", SupplyChestRules.answerCommand(id, Choice.ALWAYS_COMMON));
        assertEquals("/frens supply answer " + id + " no", SupplyChestRules.answerCommand(id, Choice.NO));
        assertThrows(NullPointerException.class, () -> SupplyChestRules.answerCommand(null, Choice.NO));
        assertThrows(NullPointerException.class, () -> SupplyChestRules.answerCommand(id, null));
    }

    @Test
    void everyChoiceRoundTripsThroughItsCommand() {
        UUID id = UUID.randomUUID();
        for (Choice choice : Choice.values()) {
            String[] parts = SupplyChestRules.answerCommand(id, choice).split(" ");
            assertEquals(5, parts.length);
            assertEquals(id, UUID.fromString(parts[3]));
            assertEquals(Optional.of(choice), SupplyChestRules.parseChoice(parts[4]));
            assertEquals(Optional.of(choice), SupplyChestRules.parseChoice(SupplyChestRules.choiceToken(choice)));
        }
    }

    @Test
    void parseChoiceIgnoresCaseAndRejectsEverythingElse() {
        assertEquals(Optional.of(Choice.ALLOW_ONCE), SupplyChestRules.parseChoice("ONCE"));
        assertEquals(Optional.of(Choice.ALWAYS_COMMON), SupplyChestRules.parseChoice("Always"));
        assertEquals(Optional.of(Choice.NO), SupplyChestRules.parseChoice(" no "));
        for (String junk : Arrays.asList(null, "", "yes", "allow_once", "always_common", "nope", "o nce")) {
            assertEquals(Optional.empty(), SupplyChestRules.parseChoice(junk), String.valueOf(junk));
        }
    }

    @Test
    void promptTextNamesBotQuantityItemAndPosition() {
        assertEquals("Jake asks to take 8 Cobblestone from the chest at 10, 64, -5.",
                SupplyChestRules.promptText("Jake", 8, "Cobblestone", 10, 64, -5));
        assertEquals("Your companion asks to take 1 items from the chest at 0, 0, 0.",
                SupplyChestRules.promptText(null, 1, " ", 0, 0, 0));
    }
}
