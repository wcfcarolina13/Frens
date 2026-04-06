# Bot Enchanting Screen Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let players enchant bot items using the vanilla enchanting UI, backed by the bot's XP, inventory, and enchantment seed.

**Architecture:** A C2S payload from CompanionSpellsScreen triggers the server to open a vanilla `EnchantmentScreenHandler` subclass that substitutes the bot entity for all XP/stat operations. The client sees the standard enchanting screen with zero custom UI.

**Tech Stack:** Fabric 1.21.11 networking API, vanilla `EnchantmentScreenHandler`, `ScreenHandlerContext`

**Spec:** `docs/superpowers/specs/2026-04-05-bot-enchanting-screen-design.md`

---

## Task 1: Create BotEnchantOpenPayload

**Files:**
- Create: `src/main/java/net/wcfcarolina13/network/BotEnchantOpenPayload.java`

- [ ] **Step 1: Create the payload record**

Follow the exact pattern from `GuideOpenInventoryPayload.java`. Single field: `botAlias`.

```java
package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request to open the bot's enchanting screen. */
public record BotEnchantOpenPayload(String botAlias) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "bot_enchant_open");
    public static final CustomPayload.Id<BotEnchantOpenPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, BotEnchantOpenPayload> CODEC =
            PacketCodec.tuple(
                    new StringCodec(32767), BotEnchantOpenPayload::botAlias,
                    BotEnchantOpenPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
```

- [ ] **Step 2: Register the payload in Frens.java**

Add after the Guide inventory payload registrations (after line 527):

```java
// Bot enchanting screen
PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BotEnchantOpenPayload.ID, net.wcfcarolina13.network.BotEnchantOpenPayload.CODEC);
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/network/BotEnchantOpenPayload.java
git commit -m "feat: Add BotEnchantOpenPayload C2S packet for bot enchanting"
```

---

## Task 2: Add findNearestEnchantingTable helper

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java` (near line 5235)

- [ ] **Step 1: Add the helper method**

Place it directly after the existing `isNearEnchantingTable` method (which ends at line 5254). Same scan logic but returns `BlockPos` instead of `boolean`. Returns the nearest table by distance or null.

```java
/** Find the nearest enchanting table BlockPos within radius, or null if none found. */
public static BlockPos findNearestEnchantingTable(ServerPlayerEntity player, int radius) {
    if (player == null) {
        return null;
    }
    if (!(player.getEntityWorld() instanceof ServerWorld world)) {
        return null;
    }
    BlockPos origin = player.getBlockPos();
    int r = Math.max(1, radius);
    BlockPos nearest = null;
    double nearestDist = Double.MAX_VALUE;
    for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
        if (!world.isChunkLoaded(pos)) {
            continue;
        }
        if (world.getBlockState(pos).isOf(net.minecraft.block.Blocks.ENCHANTING_TABLE)) {
            double dist = origin.getSquaredDistance(pos);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = pos.toImmutable();
            }
        }
    }
    return nearest;
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git commit -m "feat: Add findNearestEnchantingTable helper returning BlockPos"
```

---

## Task 3: Create BotEnchantmentScreenHandler

**Files:**
- Create: `src/main/java/net/wcfcarolina13/ui/BotEnchantmentScreenHandler.java`

This is the core file. It extends `EnchantmentScreenHandler` and overrides `onButtonClick` and `onClosed` to use the bot entity instead of the clicking player. All private fields from the super class (`inventory`, `context`, `random`, `seed`) are inaccessible, so we store parallel references.

**Important vanilla source context** (from decompiled `EnchantmentScreenHandler`):
- Super constructor creates a `SimpleInventory(2)` stored in private `inventory` field, and adds it as slots 0 (item) and 1 (lapis)
- `onButtonClick(player, id)` checks `player.experienceLevel`, calls `player.applyEnchantmentCosts()`, `player.incrementStat()`, `Criteria.ENCHANTED_ITEM.trigger()`, and `player.getEnchantingTableSeed()`
- `onClosed(player)` calls `dropInventory(player, this.inventory)` — gives leftover items to the closing player
- `generateEnchantments()` is private — must be reimplemented
- Public fields `enchantmentPower[]`, `enchantmentId[]`, `enchantmentLevel[]` are accessible

- [ ] **Step 1: Create BotEnchantmentScreenHandler**

```java
package net.wcfcarolina13.ui;

import java.util.List;
import java.util.Optional;
import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.Util;
import net.minecraft.util.math.random.Random;

/**
 * Enchanting screen handler that operates on a <b>bot's</b> inventory and XP
 * while being viewed by a real player.
 *
 * <p>Extends vanilla {@link EnchantmentScreenHandler} so the client opens the
 * standard {@code EnchantmentScreen} with zero custom UI. The super's private
 * fields ({@code inventory}, {@code context}, {@code random}, {@code seed})
 * are inaccessible, so we keep parallel references.</p>
 */
public class BotEnchantmentScreenHandler extends EnchantmentScreenHandler {

    private final ServerPlayerEntity bot;
    private final ScreenHandlerContext tableContext;
    private final Random enchantRandom = Random.create();
    // The super's SimpleInventory(2) is private. We can read slot contents
    // via this.slots.get(0) / this.slots.get(1), but for onClosed we need
    // a direct Inventory reference. We build one that delegates to the same slots.
    private final Inventory enchantSlots;

    public BotEnchantmentScreenHandler(int syncId, PlayerInventory botInventory,
                                       ScreenHandlerContext context, ServerPlayerEntity bot) {
        super(syncId, botInventory, context);
        this.bot = bot;
        this.tableContext = context;
        // The super constructor already created the 2-slot SimpleInventory and added
        // slots 0 (item) and 1 (lapis). We wrap access to those slots for onClosed.
        this.enchantSlots = new SimpleInventory(2) {
            @Override
            public ItemStack getStack(int slot) {
                return BotEnchantmentScreenHandler.this.slots.get(slot).getStack();
            }

            @Override
            public ItemStack removeStack(int slot) {
                ItemStack stack = BotEnchantmentScreenHandler.this.slots.get(slot).getStack().copy();
                BotEnchantmentScreenHandler.this.slots.get(slot).setStack(ItemStack.EMPTY);
                return stack;
            }

            @Override
            public ItemStack removeStack(int slot, int amount) {
                return BotEnchantmentScreenHandler.this.slots.get(slot).takeStack(amount);
            }

            @Override
            public void setStack(int slot, ItemStack stack) {
                BotEnchantmentScreenHandler.this.slots.get(slot).setStack(stack);
            }

            @Override
            public int size() {
                return 2;
            }

            @Override
            public boolean isEmpty() {
                return getStack(0).isEmpty() && getStack(1).isEmpty();
            }

            @Override
            public void markDirty() {
            }
        };
    }

    /**
     * Full reimplementation — vanilla captures the {@code player} param in a lambda
     * and uses it for XP checks, stat credits, and re-seeding. We substitute {@link #bot}.
     */
    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        if (id < 0 || id >= this.enchantmentPower.length) {
            Util.logErrorOrPause(player.getStringifiedName() + " pressed invalid button id: " + id);
            return false;
        }

        ItemStack itemStack = this.slots.get(0).getStack();
        ItemStack lapisStack = this.slots.get(1).getStack();
        int cost = id + 1;

        // Lapis check (bots are never in creative)
        if (lapisStack.isEmpty() || lapisStack.getCount() < cost) {
            return false;
        }

        // Level and power check against the BOT
        if (this.enchantmentPower[id] <= 0
                || itemStack.isEmpty()
                || bot.experienceLevel < cost
                || bot.experienceLevel < this.enchantmentPower[id]) {
            return false;
        }

        this.tableContext.run((world, pos) -> {
            List<EnchantmentLevelEntry> list = this.generateEnchantmentsForBot(
                    world.getRegistryManager(), itemStack, id, this.enchantmentPower[id]);

            if (!list.isEmpty()) {
                // Deduct XP from the BOT
                bot.applyEnchantmentCosts(itemStack, cost);

                ItemStack resultStack = itemStack;
                if (itemStack.isOf(Items.BOOK)) {
                    resultStack = itemStack.withItem(Items.ENCHANTED_BOOK);
                    this.slots.get(0).setStack(resultStack);
                }

                for (EnchantmentLevelEntry entry : list) {
                    resultStack.addEnchantment(entry.enchantment(), entry.level());
                }

                // Deduct lapis (bots are never creative, so always decrement)
                lapisStack.decrement(cost);
                if (lapisStack.isEmpty()) {
                    this.slots.get(1).setStack(ItemStack.EMPTY);
                }

                // Credit stats and advancements to the BOT
                bot.incrementStat(Stats.ENCHANT_ITEM);
                Criteria.ENCHANTED_ITEM.trigger(bot, resultStack, cost);

                // Re-seed from bot's enchantment seed
                this.enchantRandom.setSeed(bot.getEnchantingTableSeed());
                this.onContentChanged(this.enchantSlots);

                world.playSound(null, pos, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE,
                        SoundCategory.BLOCKS, 1.0F, world.random.nextFloat() * 0.1F + 0.9F);
            }
        });

        return true;
    }

    /**
     * Return leftover enchanting-slot items to the <b>bot's</b> inventory,
     * not the real player who is viewing the screen.
     */
    @Override
    public void onClosed(PlayerEntity player) {
        // Handle cursor stack for the real player (super.super)
        if (player instanceof ServerPlayerEntity) {
            ItemStack cursor = this.getCursorStack();
            if (!cursor.isEmpty()) {
                // Give cursor item back to the bot, not the real player
                if (!bot.getInventory().insertStack(cursor)) {
                    bot.dropItem(cursor, false);
                }
                this.setCursorStack(ItemStack.EMPTY);
            }
        }

        // Return enchanting slot contents to bot
        this.tableContext.run((world, pos) -> {
            for (int i = 0; i < 2; i++) {
                ItemStack stack = this.enchantSlots.removeStack(i);
                if (!stack.isEmpty()) {
                    if (!bot.getInventory().insertStack(stack)) {
                        bot.dropItem(stack, false);
                    }
                }
            }
        });
    }

    /**
     * Reimplementation of the private {@code generateEnchantments} method.
     * Uses our parallel {@link #enchantRandom} seeded from the bot's enchantment seed.
     */
    private List<EnchantmentLevelEntry> generateEnchantmentsForBot(
            DynamicRegistryManager registryManager, ItemStack stack, int slot, int level) {
        this.enchantRandom.setSeed(bot.getEnchantingTableSeed() + slot);

        Optional<RegistryEntryList.Named<Enchantment>> optional = registryManager
                .getOrThrow(RegistryKeys.ENCHANTMENT)
                .getOptional(EnchantmentTags.IN_ENCHANTING_TABLE);

        if (optional.isEmpty()) {
            return List.of();
        }

        List<EnchantmentLevelEntry> list = EnchantmentHelper.generateEnchantments(
                this.enchantRandom, stack, level, optional.get().stream());

        if (stack.isOf(Items.BOOK) && list.size() > 1) {
            list.remove(this.enchantRandom.nextInt(list.size()));
        }

        return list;
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL. If there are compilation errors due to API differences in 1.21.11 vs 1.21.10, fix the specific method signatures.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/ui/BotEnchantmentScreenHandler.java
git commit -m "feat: Add BotEnchantmentScreenHandler — enchanting backed by bot XP/inventory"
```

---

## Task 4: Create BotEnchantNetworkManager

**Files:**
- Create: `src/main/java/net/wcfcarolina13/network/BotEnchantNetworkManager.java`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java` (add `registerReceiversOnce()` call near line 552)

- [ ] **Step 1: Create the network manager**

Follow the pattern from `GuideInventoryNetworkManager.java`. Resolves the bot, validates proximity and enchanting table, opens the screen.

```java
package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Commands.modCommandRegistry;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.EntityUtil;
import net.wcfcarolina13.ui.BotEnchantmentScreenHandler;

/** Handles the C2S request to open a bot's enchanting screen. */
public final class BotEnchantNetworkManager {

    private static boolean REGISTERED = false;

    private BotEnchantNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(BotEnchantOpenPayload.ID, (payload, context) ->
                context.server().execute(() -> handle(context.server(), context.player(), payload))
        );
    }

    private static void handle(MinecraftServer server, ServerPlayerEntity player, BotEnchantOpenPayload payload) {
        if (server == null || player == null || player.isRemoved()) {
            return;
        }
        // Only real players can open this screen.
        if (player instanceof createFakePlayer) {
            return;
        }

        String botAlias = payload != null && payload.botAlias() != null && !payload.botAlias().isBlank()
                ? payload.botAlias().trim()
                : null;
        if (botAlias == null) {
            return;
        }

        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botAlias);
        if (bot == null || !(bot instanceof createFakePlayer)) {
            return;
        }

        // Validate same world and reasonable distance (64 blocks, matching inventory access).
        if (player.getEntityWorld() != bot.getEntityWorld()
                || player.squaredDistanceTo(bot) > 64.0 * 64.0) {
            return;
        }

        // Find the nearest enchanting table.
        BlockPos tablePos = modCommandRegistry.findNearestEnchantingTable(player, 4);
        if (tablePos == null) {
            return;
        }

        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        String displayName = EntityUtil.safeDisplayName(bot.getName().getString());
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, p) -> new BotEnchantmentScreenHandler(
                        syncId, bot.getInventory(),
                        ScreenHandlerContext.create(world, tablePos), bot
                ),
                Text.literal(displayName + "'s Enchanting")
        ));
    }
}
```

- [ ] **Step 2: Register the network manager in Frens.java**

Add after the `GuideInventoryNetworkManager.registerReceiversOnce();` line (near line 551):

```java
net.wcfcarolina13.network.BotEnchantNetworkManager.registerReceiversOnce();
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/network/BotEnchantNetworkManager.java
git commit -m "feat: Add BotEnchantNetworkManager — server-side enchant screen opener"
```

---

## Task 5: Add Enchant button to CompanionSpellsScreen

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/CompanionSpellsScreen.java`

- [ ] **Step 1: Add the Enchant button**

Add a new `ButtonWidget enchantBtn` field alongside the existing button fields (after line 30):

```java
private ButtonWidget enchantBtn;
```

Add an import for the payload at the top:

```java
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.wcfcarolina13.network.BotEnchantOpenPayload;
```

In `init()`, insert a new button. Place it after `openInvBtn` (after line 80), shifting the "Back" button down by one row:

```java
enchantBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Enchant"), (btn) -> openBotEnchanting())
        .dimensions(cx - w / 2, top + 4 * (h + gap), w, h)
        .build());
```

Update the "Back" button to be at position `5 * (h + gap) + 10` instead of `4 * (h + gap) + 10`:

```java
this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), (btn) -> close())
        .dimensions(cx - w / 2, top + 5 * (h + gap) + 10, w, h)
        .build());
```

- [ ] **Step 2: Add the openBotEnchanting method**

Place it after the `castSummon()` method (after line 238):

```java
private void openBotEnchanting() {
    MinecraftClient client = this.client;
    if (client == null || client.getNetworkHandler() == null) {
        return;
    }
    ClientPlayNetworking.send(new BotEnchantOpenPayload(botAlias));
    close();
}
```

- [ ] **Step 3: Update refreshEnabledState to handle the enchant button**

The enchant button should only be active when near an enchanting table. In `refreshEnabledState()` (around line 95), add:

```java
if (enchantBtn != null) enchantBtn.active = isNearEnchantingTable(this.client, 4);
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: Add Enchant button to CompanionSpellsScreen"
```

---

## Task 6: Add tooltips to CompanionSpellsScreen

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/CompanionSpellsScreen.java`

Add tooltips to all buttons (including the new Enchant button). The codebase pattern is `widget.setTooltip(Tooltip.of(Text.literal("...")))` called after `.build()`.

- [ ] **Step 1: Add Tooltip import**

```java
import net.minecraft.client.gui.tooltip.Tooltip;
```

- [ ] **Step 2: Add tooltips to all buttons in init()**

After each button is built (`.build()`), before `refreshEnabledState()`, add:

```java
comeBtn.setTooltip(Tooltip.of(Text.literal("Call your companion to your location.\nRequires: Enchanting Table or Wizard's Tome, or Goat Horn.")));
summonBtn.setTooltip(Tooltip.of(Text.literal("Teleport your companion directly to you.\nRequires: Enchanting Table or Wizard's Tome, or Eye of Ender (60s cooldown).")));
homeBtn.setTooltip(Tooltip.of(Text.literal("Send your companion back to their home base.\nRequires: Navigation tier 1+ (Map on bot) or higher.")));
openInvBtn.setTooltip(Tooltip.of(Text.literal("Open your companion's inventory remotely.\nRequires: Enchanting Table or Wizard's Tome (full access).")));
enchantBtn.setTooltip(Tooltip.of(Text.literal("Open the enchanting table for your companion.\nUses the bot's XP, lapis, and inventory.\nRequires: nearby Enchanting Table.")));
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: Add tooltips to all CompanionSpellsScreen buttons"
```

---

## Task 7: Add Enchanting guide section to BotGuideScreen

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java` (in `baseTopics()`, before the closing `);` near line 1284)

- [ ] **Step 1: Add the Enchanting guide topic**

Insert before the final `);` of the `List.of(...)` in `baseTopics()` (after the "zones_protected" entry, around line 1283). Add a comma after the existing last entry, then:

```java
                new GuideTopic(
                        "enchanting_overview",
                        "Enchanting",
                        "Bot Enchanting",
                        "Enchant your companion's items using the bot's XP and lapis.",
                        List.of(
                                "Open the Spells menu while near an Enchanting Table.",
                                "Click 'Enchant' to open the vanilla enchanting UI for your bot.",
                                "The inventory grid shows the bot's items \u2014 drag items and lapis into the enchanting slots.",
                                "Enchantment offers are based on the bot's XP level, not yours.",
                                "XP and lapis are deducted from the bot. You keep your own levels.",
                                "Bookshelves around the table affect enchantment quality as normal.",
                                "When you close the screen, leftover items return to the bot's inventory.",
                                "Tip: bots accumulate XP from mining, combat, and fishing. Check their level in the inventory stats area."
                        ),
                        "UI-only action (Spells \u2192 Enchant)",
                        "Stand near an Enchanting Table with bookshelves for best results",
                        "enchant enchanting table xp level lapis bot companion spells magic"
                )
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git commit -m "feat: Add Enchanting section to in-game guide"
```

---

## Task 8: Add ambient enchanting dialogue service

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/services/EnchantingAmbientDialogueService.java`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java` (add tick registration near line 870)

This adds overhead dialogue lines when a bot is near an enchanting table with 20+ or 30+ XP levels. Uses the established per-bot cooldown + random chance pattern from `BotWakeUpDialogueService` and `CompanionOverheadDialogueService`.

- [ ] **Step 1: Create EnchantingAmbientDialogueService**

```java
package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shows ambient overhead dialogue when a bot lingers near an enchanting table
 * with enough XP to make it worth mentioning.
 *
 * <p>Two thresholds: 20+ levels (general awareness) and 30+ levels (ready for
 * top-tier enchantments). Per-bot cooldown + random chance prevent spam.</p>
 */
public final class EnchantingAmbientDialogueService {

    private static final int TABLE_RADIUS = 5;
    private static final int XP_THRESHOLD_MID = 20;
    private static final int XP_THRESHOLD_HIGH = 30;

    /** Minimum time between enchanting lines for the same bot. */
    private static final long COOLDOWN_MS = 120_000L; // 2 minutes

    /** Probability of speaking when the cooldown has elapsed (per tick). */
    private static final double SPEAK_CHANCE = 0.005; // ~0.5% per tick ≈ once per ~10s of proximity

    private static final int DURATION_MS = 3_500;
    private static final double RANGE = 32.0;

    private static final ConcurrentHashMap<UUID, Long> LAST_LINE_MS = new ConcurrentHashMap<>();

    private static final String[] MID_XP_LINES = {
            "All this experience and nothing to show for it...",
            "I wonder what enchantments I could get with these levels.",
            "That enchanting table is calling my name.",
            "I've got some levels saved up. Could be useful."
    };

    private static final String[] HIGH_XP_LINES = {
            "Thirty levels. I could get something really good.",
            "I'm sitting on a goldmine of experience here.",
            "Time for some serious enchantments, don't you think?",
            "These levels won't spend themselves. Just saying."
    };

    private EnchantingAmbientDialogueService() {}

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long now = System.currentTimeMillis();

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld)) {
                continue;
            }

            int xp = bot.experienceLevel;
            if (xp < XP_THRESHOLD_MID) {
                continue;
            }

            if (!isNearEnchantingTable(bot)) {
                continue;
            }

            UUID id = bot.getUuid();

            // Per-bot cooldown
            long last = LAST_LINE_MS.getOrDefault(id, 0L);
            if (now - last < COOLDOWN_MS) {
                continue;
            }

            // Check global overhead suppression (avoid stomping other dialogue)
            if (CompanionOverheadDialogueService.isRecentlyShown(id)) {
                continue;
            }

            // Random chance gate
            if (ThreadLocalRandom.current().nextDouble() > SPEAK_CHANCE) {
                continue;
            }

            LAST_LINE_MS.put(id, now);

            String[] pool = xp >= XP_THRESHOLD_HIGH ? HIGH_XP_LINES : MID_XP_LINES;
            String line = pool[ThreadLocalRandom.current().nextInt(pool.length)];

            CompanionOverheadDialogueService.showOverheadLine(
                    bot, line, DURATION_MS, RANGE, "enchant-ambient", null);
        }
    }

    private static boolean isNearEnchantingTable(ServerPlayerEntity bot) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(
                origin.add(-TABLE_RADIUS, -2, -TABLE_RADIUS),
                origin.add(TABLE_RADIUS, 2, TABLE_RADIUS))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (world.getBlockState(pos).isOf(Blocks.ENCHANTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    public static void clearCooldowns() {
        LAST_LINE_MS.clear();
    }
}
```

- [ ] **Step 2: Register the tick handler in Frens.java**

Add near line 870 alongside the other dialogue tick registrations:

```java
ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.EnchantingAmbientDialogueService::onServerTick);
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/EnchantingAmbientDialogueService.java
git commit -m "feat: Add ambient overhead dialogue when bot has XP near enchanting table"
```

---

## Task 9: Final build, changelog, and verification commit

**Files:**
- Modify: `changelog.md`

- [ ] **Step 1: Full build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Update changelog**

Add entry describing the bot enchanting feature, tooltips, guide section, and ambient dialogue.

- [ ] **Step 3: Final commit**

```bash
git commit -m "docs: Add changelog entry for bot enchanting screen"
```

---

## Playtesting Checklist (manual, in-game)

After deploying the JAR, verify:

**Core enchanting:**
1. Open a bot's Spells menu while NOT near an enchanting table → Enchant button is grayed out
2. Place an enchanting table with bookshelves → Enchant button becomes active
3. Click Enchant → vanilla enchanting screen opens showing the **bot's** inventory in the grid
4. Bot's XP level determines available enchantments (give bot XP with `/xp add <bot> 30 levels`)
5. Place item + lapis in enchanting slots, select an enchantment → bot's XP decreases, item is enchanted
6. Close enchanting screen without enchanting → leftover items return to bot's inventory (not your inventory)
7. Walk away from enchanting table while screen is open → screen closes
8. Verify the Wizard's Tome still grants full spells access (no regression)

**Tooltips:**
9. Hover each button in the Spells menu → tooltip appears with description and requirements

**Guide:**
10. Open the in-game Guide → "Enchanting" section appears with "Bot Enchanting" topic
11. Verify the guide text is accurate and searchable

**Ambient dialogue:**
12. Give a bot 20+ levels, stand near an enchanting table → bot eventually says something about XP/enchanting
13. Give a bot 30+ levels → bot uses the higher-tier dialogue pool
14. Verify dialogue doesn't fire more than once per ~2 minutes per bot
15. Verify dialogue doesn't overlap with other overhead lines (global suppression)
