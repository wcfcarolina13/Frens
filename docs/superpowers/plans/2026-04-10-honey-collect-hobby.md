# Honey Collection Hobby Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an idle hobby that harvests honey bottles or honeycombs from nearby smoked beehives.

**Architecture:** A new `HoneyCollectSkill` implements the `Skill` interface following the `LeafLitterSkill` pattern. It scans for beehives/bee nests with `honey_level==5` and `isSmoked()==true`, walks to them, and right-clicks with glass bottles (preferred) or shears (fallback). Registered in `SkillManager` and wired into `BotIdleHobbiesService` as a low-weight hobby.

**Tech Stack:** Java 21, Minecraft Fabric 1.21.11, vanilla `BeehiveBlockEntity.isSmoked()` API, `Properties.HONEY_LEVEL` block state.

**Spec:** `docs/superpowers/specs/2026-04-10-honey-collect-hobby-design.md`

---

## Task 1: Create HoneyCollectSkill

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/HoneyCollectSkill.java`

- [ ] **Step 1: Create the skill file with full implementation**

Create `HoneyCollectSkill.java` following the LeafLitterSkill pattern. The skill:
- Scans nearby beehives/bee nests within a configurable radius
- Filters to hives with `honey_level == 5` AND `BeehiveBlockEntity.isSmoked() == true`
- Prefers glass bottles (-> honey bottle food) over shears (-> honeycombs)
- Walks to each hive, equips the right tool, right-clicks the hive block
- Collects dropped honeycombs with `DropSweeper.sweep()`
- Deposits extras in nearby chests
- Never breaks the hive

```java
package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BeehiveBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.DropSweeper;
import net.wcfcarolina13.GameAI.services.BotBeehiveRegistryService;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Idle hobby: collect honey bottles or honeycombs from nearby smoked beehives.
 * Never breaks the hive. Only harvests when the hive is full (honey_level=5)
 * and calmed by smoke (campfire below).
 */
public final class HoneyCollectSkill implements Skill {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-honey-collect");

    private static final int DEFAULT_COUNT = 2;
    private static final int DEFAULT_RADIUS = 16;
    private static final double REACH_SQ = 4.5 * 4.5;
    private static final double DROP_SWEEP_RADIUS = 6.0;
    private static final long DROP_SWEEP_DURATION_MS = 3000L;

    @Override
    public String name() {
        return "honey_collect";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No bot available.");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("World unavailable.");
        }

        int count = getIntParameter(context.parameters(), "count", DEFAULT_COUNT);
        int radius = getIntParameter(context.parameters(), "radius", DEFAULT_RADIUS);

        // Determine harvest tool: prefer glass bottles (food), fall back to shears (crafting)
        boolean hasBottles = hasItem(bot, Items.GLASS_BOTTLE);
        boolean hasShears = hasItem(bot, Items.SHEARS);
        if (!hasBottles && !hasShears) {
            return SkillExecutionResult.failure("No glass bottles or shears available.");
        }

        // Discover and find harvestable hives
        BotBeehiveRegistryService.discoverBeehivesNear(world, bot.getBlockPos(), radius, 6);
        List<BlockPos> candidates = findHarvestableHives(world, bot, radius);
        if (candidates.isEmpty()) {
            return SkillExecutionResult.failure("No harvestable beehives nearby.");
        }

        int harvested = 0;
        Set<BlockPos> unreachable = new HashSet<>();

        for (BlockPos hivePos : candidates) {
            if (harvested >= count) break;
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Honey collection interrupted.");
            }
            if (unreachable.contains(hivePos)) continue;

            // Re-validate before walking (state may have changed)
            if (!isHarvestable(world, hivePos)) continue;

            // Walk to the hive
            if (!moveIntoReach(source, bot, hivePos)) {
                unreachable.add(hivePos);
                continue;
            }

            // Re-validate after walking (bees may have left, honey may have been taken)
            if (!isHarvestable(world, hivePos)) continue;

            // Equip the right tool and harvest
            boolean used;
            if (hasBottles && equipItem(bot, Items.GLASS_BOTTLE)) {
                used = useOnHive(bot, hivePos);
            } else if (hasShears && equipItem(bot, Items.SHEARS)) {
                used = useOnHive(bot, hivePos);
            } else {
                continue;
            }

            if (used) {
                harvested++;
                LOGGER.info("Harvested honey from beehive at {}", hivePos.toShortString());

                // Sweep dropped honeycombs (shears drop items; bottles go into hand)
                try {
                    DropSweeper.sweep(source, DROP_SWEEP_RADIUS, 4.0D, 8, DROP_SWEEP_DURATION_MS);
                } catch (Exception e) {
                    LOGGER.debug("Drop sweep after honey harvest failed: {}", e.getMessage());
                }

                // Brief pause between hives
                sleepQuietly(300L);
            }

            // Refresh tool availability for next iteration
            hasBottles = hasItem(bot, Items.GLASS_BOTTLE);
            hasShears = hasItem(bot, Items.SHEARS);
            if (!hasBottles && !hasShears) break;
        }

        if (harvested <= 0) {
            return SkillExecutionResult.failure("Couldn't harvest any beehives.");
        }

        // Deposit honeycombs and honey bottles in nearby chests
        depositHoneyItems(source, bot, world);

        return SkillExecutionResult.success("Collected honey from " + harvested
                + (harvested == 1 ? " beehive." : " beehives."));
    }

    // ── Hive scanning ──────────────────────────────────────────────

    private static List<BlockPos> findHarvestableHives(ServerWorld world, ServerPlayerEntity bot, int radius) {
        List<BlockPos> out = new ArrayList<>();
        if (world == null || bot == null) return out;
        BlockPos center = bot.getBlockPos();
        int r = Math.max(6, radius);
        for (BlockPos pos : BlockPos.iterate(center.add(-r, -4, -r), center.add(r, 4, r))) {
            if (!world.isChunkLoaded(pos)) continue;
            if (isHarvestable(world, pos)) {
                out.add(pos.toImmutable());
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        return out;
    }

    private static boolean isHarvestable(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (!BotBeehiveRegistryService.isBeehiveBlock(state)) return false;
        // Must be full (honey_level == 5)
        if (state.get(Properties.HONEY_LEVEL) != 5) return false;
        // Must be smoked (campfire within 5 blocks below)
        BlockEntity be = world.getBlockEntity(pos);
        return be instanceof BeehiveBlockEntity hive && hive.isSmoked();
    }

    // ── Interaction ─────────────────────────────────────────────────

    private static boolean useOnHive(ServerPlayerEntity bot, BlockPos hivePos) {
        LookController.faceBlock(bot, hivePos);
        ItemStack handStack = bot.getMainHandStack();
        var result = handStack.useOnBlock(new net.minecraft.item.ItemUsageContext(
                bot, Hand.MAIN_HAND,
                new net.minecraft.util.hit.BlockHitResult(
                        Vec3d.ofCenter(hivePos), Direction.NORTH, hivePos, false)));
        if (result.isAccepted()) {
            bot.swingHand(Hand.MAIN_HAND, true);
            return true;
        }
        return false;
    }

    // ── Movement ────────────────────────────────────────────────────

    private static boolean moveIntoReach(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        if (bot.getBlockPos().getSquaredDistance(target) <= REACH_SQ) return true;
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT, target, target, null, null, null);
        MovementService.MovementResult result = MovementService.execute(
                source, bot, plan, SkillPreferences.teleportDuringSkills(bot), true);
        return result != null && (result.success() || bot.getBlockPos().getSquaredDistance(target) <= REACH_SQ);
    }

    // ── Inventory helpers ───────────────────────────────────────────

    private static boolean hasItem(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) return true;
        }
        return false;
    }

    private static boolean equipItem(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        // Already in main hand?
        if (bot.getMainHandStack().isOf(item)) return true;
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getStack(i).isOf(item)) {
                BotActions.selectHotbarSlot(bot, i);
                return bot.getMainHandStack().isOf(item);
            }
        }
        // Item is in main inventory (slot >= 9), swap to hotbar
        for (int i = 9; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).isOf(item)) {
                // Find an empty hotbar slot or use slot 0
                int hotbarSlot = 0;
                for (int h = 0; h < 9; h++) {
                    if (bot.getInventory().getStack(h).isEmpty()) {
                        hotbarSlot = h;
                        break;
                    }
                }
                // Swap
                ItemStack temp = bot.getInventory().getStack(hotbarSlot);
                bot.getInventory().setStack(hotbarSlot, bot.getInventory().getStack(i));
                bot.getInventory().setStack(i, temp);
                BotActions.selectHotbarSlot(bot, hotbarSlot);
                return bot.getMainHandStack().isOf(item);
            }
        }
        return false;
    }

    private static void depositHoneyItems(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world) {
        try {
            // Deposit honeycombs
            ChestStoreService.depositMatchingWalkOnly(source, bot, bot.getBlockPos(),
                    stack -> stack != null && stack.isOf(Items.HONEYCOMB));
            // Deposit honey bottles
            ChestStoreService.depositMatchingWalkOnly(source, bot, bot.getBlockPos(),
                    stack -> stack != null && stack.isOf(Items.HONEY_BOTTLE));
        } catch (Exception e) {
            LOGGER.debug("Honey deposit failed: {}", e.getMessage());
        }
    }

    // ── Utilities ───────────────────────────────────────────────────

    private static int getIntParameter(Map<String, Object> params, String key, int def) {
        if (params == null || key == null) return def;
        Object raw = params.get(key);
        if (raw instanceof Number number) return number.intValue();
        if (raw instanceof String str) {
            try { return Integer.parseInt(str.trim()); }
            catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
```

- [ ] **Step 2: Build and verify**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL (the skill file compiles but isn't registered yet)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/HoneyCollectSkill.java
git commit -m "feat: add HoneyCollectSkill — idle honey/honeycomb collection from smoked beehives"
```

---

## Task 2: Register in SkillManager

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/SkillManager.java:80`

- [ ] **Step 1: Add registration call**

After line 80 (`register(new FortifyMoatSkill());`), add:

```java
register(new HoneyCollectSkill());
```

Add the import at the top of the file with the other impl imports:

```java
import net.wcfcarolina13.GameAI.skills.impl.HoneyCollectSkill;
```

Note: If the file uses fully-qualified names for some skills (like `net.wcfcarolina13.GameAI.skills.impl.HangoutSkill`), follow that pattern instead.

- [ ] **Step 2: Build and verify**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/SkillManager.java
git commit -m "feat: register HoneyCollectSkill in SkillManager"
```

---

## Task 3: Wire into BotIdleHobbiesService

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotIdleHobbiesService.java`

- [ ] **Step 1: Add eligibility check in `pickHobby()`**

In `pickHobby()` (around line 530, near the other eligibility checks like `canCollectLeafLitter`), add:

```java
boolean canCollectHoney = (hasItem(bot, Items.SHEARS) || hasItem(bot, Items.GLASS_BOTTLE))
        && hasNearbyHarvestableBeehive(world, bot.getBlockPos(), 16);
```

Then in the weighted list section (around line 582-587, near the `leaf_litter` entries), add:

```java
if (canCollectHoney) {
    weighted.add("honey_collect");
}
```

Low weight (single entry) so it doesn't compete heavily with major hobbies.

- [ ] **Step 2: Add the `hasNearbyHarvestableBeehive` helper method**

After the existing `hasItem()` method (around line 690), add:

```java
private static boolean hasNearbyHarvestableBeehive(ServerWorld world, BlockPos origin, int radius) {
    if (world == null || origin == null) return false;
    int r = Math.max(6, radius);
    for (BlockPos pos : BlockPos.iterate(origin.add(-r, -4, -r), origin.add(r, 4, r))) {
        if (!world.isChunkLoaded(pos)) continue;
        var state = world.getBlockState(pos);
        if (!BotBeehiveRegistryService.isBeehiveBlock(state)) continue;
        if (state.get(net.minecraft.state.property.Properties.HONEY_LEVEL) != 5) continue;
        net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof net.minecraft.block.entity.BeehiveBlockEntity hive && hive.isSmoked()) {
            return true;
        }
    }
    return false;
}
```

- [ ] **Step 3: Add parameter setup in `startAmbientSkill()`**

In `startAmbientSkill()` (around line 930, after the leaf_litter parameter block), add:

```java
if ("honey_collect".equalsIgnoreCase(runSkillName)) {
    params.put("count", 1 + RNG.nextInt(3)); // 1-3 hives
    params.put("radius", 16);
}
```

- [ ] **Step 4: Build and verify**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotIdleHobbiesService.java
git commit -m "feat: wire honey_collect hobby into idle hobby picker"
```

---

## Task 4: Changelog + Final Build

**Files:**
- Modify: `changelog.md`

- [ ] **Step 1: Full build verification**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Update changelog**

Add entry to top of `changelog.md`:

```markdown
## 2026-04-10 — Idle honey collection hobby

- New idle hobby: `honey_collect`. Bot harvests honey from nearby beehives/bee nests when idle.
- Only harvests when `honey_level == 5` (full hive) AND `BeehiveBlockEntity.isSmoked()` is true (campfire within 5 blocks below, handles carpet/slabs between).
- Prefers glass bottles (-> honey bottle food item) over shears (-> 3 honeycombs crafting material).
- Never breaks the hive. Never harvests an unsmoked hive.
- Low hobby weight — doesn't compete heavily with fishing/woodcutting/etc.
- Deposits honeycombs and honey bottles in nearby chests after collection.
```

- [ ] **Step 3: Commit changelog**

```bash
git add changelog.md
git commit -m "docs: changelog entry for honey collection hobby"
```

- [ ] **Step 4: Report artifact path**

Report the built JAR path. Do NOT copy to PrismLauncher — user is playing.
