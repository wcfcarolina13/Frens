package net.wcfcarolina13.GameAI.services.supply;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Supplies Phase 3 routed every automatic chest→bot withdrawal through
 * {@code SupplyWithdrawals.withdraw(...)}. This keeps the old bypasses closed, by scanning
 * {@code src/main/java} (comments stripped):
 *
 * <ul>
 *   <li><b>Container-access ratchet.</b> Every production file that obtains a world container
 *   inventory (a container-typed {@code instanceof} or cast, {@code ChestBlock.getInventory(},
 *   a hopper static, a typed block-entity lookup, the Fabric transfer API) is listed in
 *   {@link #RATCHET} with its exact number of such sites and why it may have them. A file that is
 *   not listed, or whose count moved either way, fails. The ratchet counts obtaining a container,
 *   not {@code removeStack}/{@code split}/{@code setStack}: those also run on the bot's own
 *   inventory everywhere, so a blanket regex on them could not tell a bypass from a craft.</li>
 *   <li><b>Choke points name the facade.</b> Each method the Phase 3 sites now funnel through
 *   calls {@code SupplyWithdrawals.withdraw(} (or {@code grantableEstimate(} for an availability
 *   estimate) and, like the listings that feed them, holds no raw item move.</li>
 *   <li><b>Deleted pulls stay deleted.</b> The raw container pulls removed from HuntSkill,
 *   ToolProvisionService and CraftingHelper are not back, and ToolProvisionService's chest listing
 *   keeps no chest handle to move items through.</li>
 *   <li><b>One hop.</b> The supply hops in ToolProvisionService and ChestStoreService's walking
 *   withdrawal go through {@code SupplyServerHop.call(}, which never runs a task its worker gave
 *   up on.</li>
 * </ul>
 *
 * The scan fails loudly when the source tree cannot be found or its patterns stop matching the
 * sites they are known to match, so it can never pass by reading nothing.
 */
class SupplyBypassClosedTest {

    private static final String NET = "net/wcfcarolina13/";
    private static final String SERVICES = NET + "GameAI/services/";
    private static final String SUPPLY = SERVICES + "supply/";
    private static final String SKILLS = NET + "GameAI/skills/";
    private static final String TPS = SERVICES + "ToolProvisionService.java";
    private static final String CSS = SERVICES + "ChestStoreService.java";
    private static final String MUTUAL_AID = SERVICES + "BotMutualAidService.java";
    private static final String CRAFTING = SERVICES + "CraftingHelper.java";
    private static final String HUNT = SKILLS + "impl/HuntSkill.java";

    private static final String ROUTED = "automatic chest→bot moves must go through SupplyWithdrawals.withdraw(...) "
            + "(SupplyWithdrawals.grantableEstimate(...) for an availability estimate)";

    /** How many container-access sites a file may hold, and why it may hold them. */
    private record Allowed(int sites, String reason) {
    }

    /**
     * Every production file that obtains a world container, with its exact site count. Derived
     * from the tree at supplies Phase 3 (1.1.220); a change either way must update the entry and
     * say why.
     */
    private static final Map<String, Allowed> RATCHET = new LinkedHashMap<>();

    static {
        allow(SUPPLY + "SupplyRequestService.java", 6,
                "supply adapter: checks both chest halves and moves what the owner granted (transferNow)");
        allow(SUPPLY + "SupplyWithdrawals.java", 1,
                "facade: re-reads the merged chest after a move to refresh the registry snapshot (read-only)");
        allow(TPS, 1,
                "scanContainers: read-only ChestBlock listing for pullFromReachableChests / grantableChestCount (facade)");
        allow(CSS, 5,
                "owner-initiated /bot withdraw + Quick Fetch (performStoreTransferWithBotDetailed) + deposits; read-only "
                        + "item-type snapshot; askBeforeWalking's read before the facade (1.1.220 fix wave: dead "
                        + "performStoreTransfer and its chest probe deleted, 6 -> 5)");
        allow(CRAFTING, 3,
                "deposits (depositIntoChests, placeChestNearBot) + findPullCandidates' read-only listing for the facade");
        allow(MUTUAL_AID, 1,
                "merged chest read-only listing; every take goes through askForFood (facade)");
        allow(SERVICES + "NavigationArtifactService.java", 1,
                "storage-screen Collect after fast travel: owner-initiated, exempt per DESIGN (its missing "
                        + "owner-to-bot check is a separate security task)");
        allow(SERVICES + "BotChestRegistryService.java", 1, "read-only contents snapshot (refreshAllSnapshots)");
        allow(SERVICES + "BotEmergencyRescueService.java", 1, "read-only availability probe: is there chest food nearby");
        allow(SERVICES + "SmeltingService.java", 10,
                "furnace input/output (not a chest; out of scope per DESIGN) + deposits of furnace output into chests");
        allow(SERVICES + "FurnaceOffloadService.java", 2, "furnace fuel slot: read + deposit only (not a chest)");
        allow(SERVICES + "CookingReactionService.java", 1, "read-only furnace probe for cooking dialogue (not a chest)");
        allow(SERVICES + "TravelMountHandler.java", 1,
                "vehicle collection: chest boat/minecart contents on fast travel (out of scope per DESIGN)");
        allow(SKILLS + "impl/FishingSkill.java", 2, "read-only free-space check when choosing a deposit chest");
        allow(SKILLS + "impl/ShelterSkill.java", 3, "burrow deposit only");
        allow(SKILLS + "impl/WoolSkill.java", 1, "deposit only");
        allow(SKILLS + "support/MiningHazardDetector.java", 2,
                "hazard classifier: pause mining at a chest or barrel; no item access");
        allow(NET + "GraphicalUserInterface/StoreTargetPickerOverlay.java", 1,
                "client store-target picker: is the hovered block a container; no item access");
    }

    private static void allow(String file, int sites, String reason) {
        if (RATCHET.put(file, new Allowed(sites, reason)) != null) {
            throw new IllegalStateException("listed twice: " + file);
        }
    }

    /** Block-entity and inventory types a world container is reached through. */
    private static final String CONTAINER_TYPE = "(?:Inventory|SidedInventory|LootableInventory|VehicleInventory"
            + "|ChestBlockEntity|TrappedChestBlockEntity|BarrelBlockEntity|ShulkerBoxBlockEntity"
            + "|LootableContainerBlockEntity|LockableContainerBlockEntity|HopperBlockEntity"
            + "|DispenserBlockEntity|DropperBlockEntity|CrafterBlockEntity|ShelfBlockEntity"
            + "|DecoratedPotBlockEntity|ChiseledBookshelfBlockEntity|BrewingStandBlockEntity"
            + "|AbstractFurnaceBlockEntity|FurnaceBlockEntity|SmokerBlockEntity|BlastFurnaceBlockEntity)";
    /** An optional package or outer-class qualifier, e.g. {@code net.minecraft.inventory.}. */
    private static final String QUALIFIER = "(?:[A-Za-z_]\\w*\\s*\\.\\s*)*";
    /** One site where a file obtains a world container inventory. */
    private static final Pattern CONTAINER_SITE = Pattern.compile(
            "\\binstanceof\\s+" + QUALIFIER + CONTAINER_TYPE + "\\b"
                    + "|\\(\\s*" + QUALIFIER + CONTAINER_TYPE + "\\s*\\)\\s*(?=[\\w(])"
                    + "|\\bChestBlock\\s*\\.\\s*getInventory\\s*\\("
                    + "|\\bHopperBlockEntity\\s*\\.\\s*\\w+\\s*\\("
                    + "|\\bBlockEntityType\\s*\\.\\s*(?:CHEST|TRAPPED_CHEST|COPPER_CHEST|BARREL|SHULKER_BOX|HOPPER"
                    + "|DISPENSER|DROPPER|CRAFTER|SHELF|DECORATED_POT|CHISELED_BOOKSHELF|BREWING_STAND|FURNACE"
                    + "|SMOKER|BLAST_FURNACE)\\b"
                    + "|\\bItemStorage\\s*\\.\\s*SIDED\\b|\\bInventoryStorage\\s*\\.\\s*of\\s*\\(");

    private static final Pattern WITHDRAW_CALL = Pattern.compile("\\bSupplyWithdrawals\\s*\\.\\s*withdraw\\s*\\(");
    private static final Pattern GRANTABLE_CALL =
            Pattern.compile("\\bSupplyWithdrawals\\s*\\.\\s*grantableEstimate\\s*\\(");
    private static final Pattern ASK_FOR_FOOD_CALL = Pattern.compile("(?<![\\w.])askForFood\\s*\\(");
    /** A raw item move on some inventory: what a choke point must leave to the facade. */
    private static final Pattern RAW_MOVE = Pattern.compile(
            "\\.\\s*(?:removeStack|split|setStack|decrement|increment|insertStack|offerOrDrop|giveItemStack)\\s*\\("
                    + "|\\bInventories\\s*\\.|(?<![\\w.])moveItems\\s*\\(");
    /** Reading a chest handle out of TPS's listing record (it keeps none since the 1.1.220 fix wave). */
    private static final Pattern CONTAINER_SLOT_INV_READ = Pattern.compile("\\.\\s*inv\\s*\\(\\s*\\)");
    /** TPS's listing record and its component list. */
    private static final Pattern CONTAINER_SLOT_RECORD = Pattern.compile("\\brecord\\s+ContainerSlot\\s*\\(([^)]*)\\)");
    /** A container type named as a word, e.g. a record component's type. */
    private static final Pattern CONTAINER_TYPE_WORD = Pattern.compile("\\b" + CONTAINER_TYPE + "\\b");
    /** The shared, abandon-safe hop. */
    private static final Pattern SHARED_HOP_CALL = Pattern.compile("\\bSupplyServerHop\\s*\\.\\s*call\\s*\\(");
    /** A site's own hop copy. */
    private static final Pattern PRIVATE_HOP_CALL = Pattern.compile("(?<![\\w.])(?:callOnServer|onServerThread)\\s*\\(");

    private static Map<String, String> sources;

    @BeforeAll
    static void readSourceTree() {
        Path root = findMainJavaRoot();
        Map<String, String> read = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                read.put(relative, stripComments(Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + root, e);
        }
        sources = read;
    }

    /** Walks up from the working directory to the project's {@code src/main/java}. */
    private static Path findMainJavaRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            Path candidate = d.resolve("src/main/java");
            if (Files.isDirectory(candidate.resolve("net/wcfcarolina13"))) {
                return candidate;
            }
        }
        fail("src/main/java/net/wcfcarolina13 not found from " + dir + "; the bypass scan cannot run");
        return null;
    }

    @Test
    void scanReadsTheRealTree() {
        assertTrue(sources.size() > 100, "suspiciously few sources scanned: " + sources.size());
        for (String file : List.of(TPS, CSS, MUTUAL_AID, CRAFTING, HUNT, SUPPLY + "SupplyWithdrawals.java")) {
            assertNotNull(sources.get(file), file + " not found where expected");
        }
        // Known live sites, so a pattern that silently stopped matching reads as a broken pattern.
        assertEquals(1, count(CONTAINER_SITE, sources.get(SUPPLY + "SupplyWithdrawals.java")),
                "the scan must see the facade's one ChestBlock.getInventory(");
        assertEquals(6, count(CONTAINER_SITE, sources.get(SUPPLY + "SupplyRequestService.java")),
                "the scan must see the adapter's chest-half checks, casts and merged views");
        int total = 0;
        for (String text : sources.values()) {
            total += count(CONTAINER_SITE, text);
        }
        assertTrue(total > 40, "suspiciously few container-access sites: " + total);
        assertTrue(sources.get(HUNT).contains("class HuntSkill") && sources.get(HUNT).length() > 20_000,
                "HuntSkill.java is not the real skill");
    }

    @Test
    void containerAccessMatchesTheRatchet() {
        Map<String, List<Integer>> found = new TreeMap<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            List<Integer> lines = siteLines(e.getValue());
            if (!lines.isEmpty()) {
                found.put(e.getKey(), lines);
            }
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : found.entrySet()) {
            String file = e.getKey();
            List<Integer> lines = e.getValue();
            Allowed allowed = RATCHET.get(file);
            if (allowed == null) {
                problems.add(file + ": " + lines.size() + " container-access site(s) at lines " + lines
                        + ", file not in the ratchet");
            } else if (allowed.sites() != lines.size()) {
                problems.add(file + ": expected " + allowed.sites() + " container-access site(s), found "
                        + lines.size() + " at lines " + lines + " (listed as: " + allowed.reason() + ")");
            }
        }
        for (Map.Entry<String, Allowed> e : RATCHET.entrySet()) {
            if (!found.containsKey(e.getKey())) {
                problems.add(e.getKey() + ": listed with " + e.getValue().sites()
                        + " site(s) but " + (sources.containsKey(e.getKey()) ? "has none" : "not found")
                        + " (listed as: " + e.getValue().reason() + ")");
            }
        }
        assertEquals(List.of(), problems, ROUTED + "; a new, changed or removed world-container access must update "
                + "SupplyBypassClosedTest.RATCHET with a one-line reason");
    }

    @Test
    void ratchetEntriesAreWellFormed() {
        for (Map.Entry<String, Allowed> e : RATCHET.entrySet()) {
            assertTrue(e.getKey().startsWith(NET) && e.getKey().endsWith(".java"), "not a source path: " + e.getKey());
            assertTrue(e.getValue().sites() > 0, e.getKey() + ": a file with no sites leaves the ratchet");
            assertFalse(e.getValue().reason().isBlank(), e.getKey() + ": every entry says why");
        }
    }

    @Test
    void chokePointsNameTheFacade() {
        assertCalls(TPS, "pullFromReachableChests", WITHDRAW_CALL);
        assertCalls(TPS, "grantableChestCount", GRANTABLE_CALL);
        assertCalls(CSS, "askBeforeWalking", WITHDRAW_CALL);
        assertCalls(CSS, "withdrawMatchingWalkOnly", WITHDRAW_CALL);
        assertCalls(MUTUAL_AID, "askForFood", WITHDRAW_CALL);
        assertCalls(MUTUAL_AID, "takeFoodFromChest", GRANTABLE_CALL);
        assertCalls(MUTUAL_AID, "takeFoodFromChest", ASK_FOR_FOOD_CALL);
        assertCalls(CRAFTING, "withdrawFromNearbyChests", WITHDRAW_CALL);
        assertCalls(CRAFTING, "countItemsInInventoryAndNearbyChests", GRANTABLE_CALL);
    }

    @Test
    void chokePointsAndTheirChestListingsHoldNoRawMove() {
        Map<String, List<String>> methods = new LinkedHashMap<>();
        methods.put(TPS, List.of("pullFromReachableChests", "grantableChestCount", "scanContainers"));
        methods.put(CSS, List.of("askBeforeWalking", "withdrawMatchingWalkOnly"));
        methods.put(MUTUAL_AID, List.of("tryTakeFoodFromSharedChest", "takeFoodFromChest", "askForFood", "listChestFood"));
        methods.put(CRAFTING, List.of("withdrawFromNearbyChests", "countItemsInInventoryAndNearbyChests",
                "findPullCandidates"));
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : methods.entrySet()) {
            for (String method : e.getValue()) {
                if (RAW_MOVE.matcher(methodBody(e.getKey(), method)).find()) {
                    offenders.add(e.getKey() + "#" + method);
                }
            }
        }
        assertEquals(List.of(), offenders, ROUTED + "; these bodies leave the item move to the facade");
    }

    @Test
    void deletedPullsStayDeleted() {
        assertAbsent(HUNT, "\\bscanContainers\\w*", "\\bwithdrawWeaponFromContainers\\w*", "\\bContainerSlot\\w*",
                "\\bcountInContainers\\w*", "getBlockEntity\\s*\\(", "import\\s+net\\.minecraft\\.inventory\\.Inventory\\s*;");
        assertEquals(0, count(CONTAINER_SITE, sources.get(HUNT)), "HuntSkill obtains no world container");
        assertFalse(RATCHET.containsKey(HUNT), "HuntSkill stays out of the ratchet");
        assertAbsent(TPS, "\\bwithdrawFromNearbyContainers\\w*", "\\bwithdrawBestAccessibleSlot\\w*",
                "\\bwithdrawAccessibleItems\\w*", "\\bwithdrawFromContainerSlot\\w*");
        // A chest handle kept in each ContainerSlot would reopen a raw move without a new
        // container-access site, so the listing record holds none (and nothing reads one).
        Matcher slotRecord = CONTAINER_SLOT_RECORD.matcher(sources.get(TPS));
        assertTrue(slotRecord.find(), "ToolProvisionService's ContainerSlot listing record not found");
        assertFalse(CONTAINER_TYPE_WORD.matcher(slotRecord.group(1)).find(),
                ROUTED + "; ContainerSlot keeps no chest handle: " + slotRecord.group());
        assertFalse(CONTAINER_SLOT_INV_READ.matcher(sources.get(TPS)).find(),
                ROUTED + "; ToolProvisionService never reads ContainerSlot.inv()");
        assertAbsent(CRAFTING, "\\bwithdrawFromInventory\\w*");
    }

    @Test
    void supplyHopsGoThroughTheSharedHop() {
        // ToolProvisionService's only hops are supply hops (the off-thread pull, the tool search's
        // registry refresh): it keeps no private copy at all.
        assertFalse(PRIVATE_HOP_CALL.matcher(sources.get(TPS)).find(),
                "ToolProvisionService hops through SupplyServerHop.call only; it keeps no private copy");
        assertFalse(sources.get(TPS).contains("CompletableFuture"),
                "ToolProvisionService hops through SupplyServerHop.call only; no hand-rolled future");
        assertCalls(TPS, "pullFromReachableChests", SHARED_HOP_CALL);
        // ChestStoreService keeps its private copy for the owner's own transfers, deposits and the
        // walk; the ask before walking uses the shared hop.
        assertCalls(CSS, "withdrawMatchingWalkOnly", SHARED_HOP_CALL);
        assertFalse(PRIVATE_HOP_CALL.matcher(methodBody(CSS, "withdrawMatchingWalkOnly")).find(),
                "ChestStoreService#withdrawMatchingWalkOnly hops through SupplyServerHop.call only");
    }

    @Test
    void patternsCatchTheShapesTheyGuard() {
        // Obtaining a container, every spelling in the tree and the obvious new ones.
        assertEquals(1, count(CONTAINER_SITE, "if (!(world.getBlockEntity(pos) instanceof Inventory chest)) {"));
        assertEquals(1, count(CONTAINER_SITE, "if (be instanceof net.minecraft.inventory.Inventory inv) {"));
        assertEquals(1, count(CONTAINER_SITE, "boolean ok = w.getBlockEntity(p) instanceof Inventory, 800, false);"));
        assertEquals(1, count(CONTAINER_SITE, "if (!(be instanceof net.minecraft.block.entity.ChestBlockEntity chest))"));
        assertEquals(2, count(CONTAINER_SITE, "be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity"));
        assertEquals(1, count(CONTAINER_SITE, "var chestInv = (net.minecraft.inventory.Inventory) chest;"));
        assertEquals(1, count(CONTAINER_SITE, "ChestBlockEntity chestA = (ChestBlockEntity) entityA;"));
        assertEquals(1, count(CONTAINER_SITE, "Inventory s = ChestBlock.getInventory(cb, state, world, pos, true);"));
        assertEquals(1, count(CONTAINER_SITE, "s = net.minecraft.block.ChestBlock . getInventory (cb, st, w, p, true);"));
        assertEquals(1, count(CONTAINER_SITE, "Inventory i = HopperBlockEntity.getInventoryAt(world, pos);"));
        assertEquals(1, count(CONTAINER_SITE, "world.getBlockEntity(pos, BlockEntityType.BARREL).ifPresent(b -> {});"));
        assertEquals(1, count(CONTAINER_SITE, "if (be instanceof LootableContainerBlockEntity loot) {"));
        assertEquals(1, count(CONTAINER_SITE, "if (e instanceof VehicleInventory cart) {"));
        assertEquals(1, count(CONTAINER_SITE, "var s = ItemStorage.SIDED.find(world, pos, Direction.UP);"));
        // Not a container obtain: the bot's own inventory, a chest block, other block entities, parameters.
        assertEquals(0, count(CONTAINER_SITE, "PlayerInventory inv = bot.getInventory(); inv.removeStack(3, 1);"));
        assertEquals(0, count(CONTAINER_SITE, "if (state.getBlock() instanceof ChestBlock chestBlock) {"));
        assertEquals(0, count(CONTAINER_SITE, "(ChestBlock) state.getBlock(); x instanceof BeehiveBlockEntity hive"));
        assertEquals(0, count(CONTAINER_SITE, "o instanceof InventoryOwner io || o instanceof PlayerInventory pi"));
        assertEquals(0, count(CONTAINER_SITE, "o instanceof InventoryProvider ip || o instanceof SimpleInventory si"));
        assertEquals(0, count(CONTAINER_SITE, "private int depositPreferred(Inventory from, Inventory to) {"));
        assertEquals(0, count(CONTAINER_SITE, "ItemStack left = insertIntoInventory(chest, stack.copy());"));
        assertEquals(0, count(CONTAINER_SITE, "private record ContainerSlot(Inventory inv, BlockPos pos) {}"));
        assertEquals(0, count(CONTAINER_SITE, "entity instanceof ChestBoatEntity; (int) x; (ServerWorld) w"));
        assertEquals(0, count(CONTAINER_SITE, stripComments("// if (be instanceof Inventory inv)\n/* (Inventory) be */ x();")));

        assertTrue(WITHDRAW_CALL.matcher("r = SupplyWithdrawals.withdraw(bot, pos, s, 1, 1, \"p\", mode, null);").find());
        assertTrue(WITHDRAW_CALL.matcher("return SupplyWithdrawals\n        .withdraw(bot, pos, s, 1, 1, p, m, a);").find());
        assertFalse(WITHDRAW_CALL.matcher("SupplyWithdrawals.withdrawn(); SupplyWithdrawals.Result r;").find());
        assertTrue(GRANTABLE_CALL.matcher("int g = SupplyWithdrawals.grantableEstimate(world, sample, 5);").find());
        assertFalse(GRANTABLE_CALL.matcher("SupplyWithdrawals.withdraw(a); grantableEstimate(w, s, 1);").find());
        assertTrue(ASK_FOR_FOOD_CALL.matcher("result = askForFood(bot, chestPos, food.sample(), pieces);").find());
        assertFalse(ASK_FOR_FOOD_CALL.matcher("x.askForFood(a); askForFoodLater(b);").find());

        assertTrue(RAW_MOVE.matcher("inv.removeStack(slot, 2)").find());
        assertTrue(RAW_MOVE.matcher("ItemStack part = stack . split (4);").find());
        assertTrue(RAW_MOVE.matcher("chest.setStack(i, ItemStack.EMPTY);").find());
        assertTrue(RAW_MOVE.matcher("stack.decrement(taken);").find());
        assertTrue(RAW_MOVE.matcher("bot.getInventory().insertStack(copy)").find());
        assertTrue(RAW_MOVE.matcher("Inventories.splitStack(list, 0, 1)").find());
        assertTrue(RAW_MOVE.matcher("moved = moveItems(storage, bot.getInventory(), filter, n);").find());
        assertFalse(RAW_MOVE.matcher("int n = stack.getCount(); ItemStack c = stack.copyWithCount(1); inv.getStack(i);")
                .find());
        assertFalse(RAW_MOVE.matcher("LOGGER.info(\"took {}\", n); tried.add(pos); String[] p = s.splitAt(1);").find());

        assertTrue(CONTAINER_SLOT_INV_READ.matcher("slot.inv().removeStack(slot.slot(), 1)").find());
        assertFalse(CONTAINER_SLOT_INV_READ.matcher("new ContainerSlot(inv, pos, i, stack); slot.invalid();").find());
        Matcher kept = CONTAINER_SLOT_RECORD.matcher("private record ContainerSlot(Inventory inv, BlockPos pos) {}");
        assertTrue(kept.find());
        assertTrue(CONTAINER_TYPE_WORD.matcher(kept.group(1)).find(), "a kept chest handle is seen");
        Matcher clean = CONTAINER_SLOT_RECORD.matcher("private record ContainerSlot(BlockPos pos, int slot, ItemStack stack) {}");
        assertTrue(clean.find());
        assertFalse(CONTAINER_TYPE_WORD.matcher(clean.group(1)).find(), "no handle, no match");
        assertFalse(CONTAINER_TYPE_WORD.matcher("PlayerInventory own, SimpleInventory copy").find());

        assertTrue(SHARED_HOP_CALL.matcher("ask = SupplyServerHop.call(server, () -> x(), 2500L, null);").find());
        assertFalse(SHARED_HOP_CALL.matcher("SupplyServerHop.hop(server, task, 1); callOnServer(s, t, 1, f);").find());
        assertTrue(PRIVATE_HOP_CALL.matcher("SupplyAsk ask = callOnServer(server, () -> a(), 2500, null);").find());
        assertTrue(PRIVATE_HOP_CALL.matcher("return onServerThread(server, task);").find());
        assertFalse(PRIVATE_HOP_CALL.matcher("SupplyServerHop.call(s, t, 1, f); this.callOnServerLater(x);").find());
    }

    @Test
    void methodBodiesAreBraceMatched() {
        String source = "class A {\n"
                + "    private static int target(Map<String, List<Integer>> m, int x) throws IOException {\n"
                + "        LOGGER.info(\"{} } {\", x);\n"
                + "        char c = '}';\n"
                + "        if (x > 0) { run(() -> { target(m, x - 1); }); }\n"
                + "        return SupplyWithdrawals.withdraw(a);\n"
                + "    }\n"
                + "    void after() { return target(null, 1); }\n"
                + "}\n";
        String body = bodyOf(source, "target");
        assertTrue(body.startsWith("{") && body.endsWith("}"));
        assertTrue(body.contains("SupplyWithdrawals.withdraw(a);"));
        assertFalse(body.contains("after()"), "the body ends at its own closing brace");
        assertEquals(1, declarations(source, "target"), "calls are not declarations");
        assertEquals(0, declarations("x = target(1); return target(2);", "target"));
        assertEquals(2, declarations("private int f(int a) { }\npublic static int f(String b) { }", "f"),
                "an overload is seen, so the pin cannot pick the wrong one silently");
    }

    @Test
    void commentsAreStrippedBeforeScanning() {
        String stripped = stripComments("a();\n/* instanceof Inventory\n still comment */ b(); // (Inventory) be\nc();");
        assertFalse(stripped.contains("Inventory"));
        assertTrue(stripped.contains("b();") && stripped.contains("c();"));
        assertEquals(4, stripped.split("\n", -1).length, "line breaks survive so reported line numbers stay true");
        assertTrue(stripComments("String u = \"http://x\"; d();").contains("d();"));
    }

    private static void assertCalls(String file, String method, Pattern call) {
        assertTrue(call.matcher(methodBody(file, method)).find(),
                file + "#" + method + " must call " + call.pattern() + ": " + ROUTED);
    }

    private static void assertAbsent(String file, String... regexes) {
        String text = sources.get(file);
        assertNotNull(text, file + " not found");
        List<String> present = new ArrayList<>();
        for (String regex : regexes) {
            if (Pattern.compile(regex).matcher(text).find()) {
                present.add(regex);
            }
        }
        assertEquals(List.of(), present, file + " brought back a deleted raw container pull; " + ROUTED);
    }

    private static String methodBody(String file, String method) {
        String text = sources.get(file);
        assertNotNull(text, file + " not found");
        return bodyOf(text, method);
    }

    private static Pattern declaration(String method) {
        // Modifiers, then a return type, then the name: no statement, block, call or assignment between.
        return Pattern.compile("(?:\\bprivate|\\bpublic|\\bprotected|\\bstatic)\\b[^;{}()=]*?\\b"
                + Pattern.quote(method) + "\\s*\\(");
    }

    private static int declarations(String source, String method) {
        return count(declaration(method), source);
    }

    /** The body, braces included, of the one method declared as {@code method}. */
    private static String bodyOf(String source, String method) {
        Matcher m = declaration(method).matcher(source);
        assertTrue(m.find(), method + "( is not declared; the choke point moved or was renamed");
        int openParen = m.end() - 1;
        assertFalse(m.find(), method + "( is declared more than once; pin the overload that names the facade");
        int closeParen = matching(source, openParen, '(', ')');
        assertTrue(closeParen > 0, method + ": unbalanced parameter list");
        int openBrace = source.indexOf('{', closeParen);
        int semicolon = source.indexOf(';', closeParen);
        assertTrue(openBrace > 0 && (semicolon < 0 || openBrace < semicolon), method + " has no body");
        int closeBrace = matching(source, openBrace, '{', '}');
        assertTrue(closeBrace > 0, method + ": unbalanced body");
        return source.substring(openBrace, closeBrace + 1);
    }

    /** The index of the bracket closing the one at {@code start}; string and char literals are skipped. */
    private static int matching(String source, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(source, i);
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int endOfLiteral(String source, int start) {
        char quote = source.charAt(start);
        if (quote == '"' && source.startsWith("\"\"\"", start)) {
            int end = source.indexOf("\"\"\"", start + 3);
            return end < 0 ? source.length() : end + 2;
        }
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote || c == '\n') {
                return i;
            }
        }
        return source.length();
    }

    /** The 1-based line of each container-access site in {@code text}. */
    private static List<Integer> siteLines(String text) {
        List<Integer> lines = new ArrayList<>();
        Matcher m = CONTAINER_SITE.matcher(text);
        int line = 1;
        int scanned = 0;
        while (m.find()) {
            for (; scanned < m.start(); scanned++) {
                if (text.charAt(scanned) == '\n') {
                    line++;
                }
            }
            lines.add(line);
        }
        return lines;
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(?<![:\"])//[^\\n]*");

    /**
     * Removes block and line comments, keeping their line breaks so line numbers stay true; string
     * literals are left alone (none of the guarded shapes are URLs).
     */
    private static String stripComments(String source) {
        Matcher m = BLOCK_COMMENT.matcher(source);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(" " + m.group().replaceAll("[^\\n]", "")));
        }
        m.appendTail(out);
        return LINE_COMMENT.matcher(out).replaceAll(" ");
    }
}
