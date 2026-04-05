# Chest Tool Retrieval Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow bots to retrieve tools from registered chests when inventory is depleted, with woodcut axe retrieval as the first consumer.

**Architecture:** Add a general `retrieveToolFromChests()` method to `ToolProvisionService` that queries `BotChestRegistryService` for nearby chests matching a snapshot filter, walks to the best candidate, and withdraws one tool via `ChestStoreService.withdrawMatchingWalkOnly()`. WoodcutSkill integrates via `prepareWoodcutTooling()` (start) and a new `ensureAxeOrRetrieve()` wrapper (mid-session).

**Tech Stack:** Java 21, Fabric 1.21.11, Minecraft server-thread model

**Spec:** `docs/superpowers/specs/2026-04-04-chest-tool-retrieval-design.md`

---

## File Map

| File | Action | Responsibility |
| --- | --- | --- |
| `src/main/java/net/wcfcarolina13/GameAI/services/ToolProvisionService.java` | Modify | Add `retrieveToolFromChests()`, axe helpers, `callOnServer` utility |
| `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java` | Modify | Add `ensureAxeOrRetrieve()`, update `prepareWoodcutTooling()`, convert 4 call sites |

---

## Task 1: Add `retrieveToolFromChests()` to ToolProvisionService

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/ToolProvisionService.java:1-30` (imports), `:1589` (before closing brace)

### Step 1: Add new imports

- [ ] Add these imports after line 25 (after existing `import java.util.*` block) in `ToolProvisionService.java`:

```java
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.wcfcarolina13.GameAI.services.BotChestRegistryService.ItemSnapshot;
```

Note: check which of `Map`, `Predicate` are already imported (they may be via wildcard or explicit). Only add what's missing. `List`, `ArrayList`, `Comparator`, `Set`, `Locale` are already imported. `ServerWorld`, `ServerPlayerEntity`, `ServerCommandSource`, `ItemStack` are already imported.

### Step 2: Add `callOnServer` private helper

- [ ] Add before the closing `}` of `ToolProvisionService` (line 1590):

```java
    private static <T> T callOnServer(MinecraftServer server,
                                       java.util.function.Supplier<T> task,
                                       long timeoutMs,
                                       T fallback) {
        if (server == null || task == null) return fallback;
        if (server.isOnThread()) {
            try {
                return task.get();
            } catch (Throwable t) {
                return fallback;
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.complete(fallback);
            }
        });
        try {
            return future.get(Math.max(250L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }
```

### Step 3: Add axe-specific constants and helpers

- [ ] Add after `callOnServer`, before the closing `}`:

```java
    // ── Chest tool retrieval: axe helpers ──────────────────────────────

    private static final Set<String> ALLOWED_AXE_IDS = Set.of(
            "minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:copper_axe");

    private static final Map<String, Integer> AXE_TIER_RANK = Map.of(
            "minecraft:copper_axe", 3,
            "minecraft:stone_axe", 2,
            "minecraft:wooden_axe", 1);

    public static Predicate<ItemSnapshot> allowedAxeSnapshotFilter() {
        return snap -> snap != null && ALLOWED_AXE_IDS.contains(snap.itemId);
    }

    public static Predicate<ItemStack> allowedWoodcutAxePredicate() {
        return stack -> {
            if (stack == null || stack.isEmpty()) return false;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (!ALLOWED_AXE_IDS.contains(id)) return false;
            if (stack.hasEnchantments()) return false;
            if (stack.isDamageable()) {
                int remaining = stack.getMaxDamage() - stack.getDamage();
                if (remaining < 8) return false;
            }
            return true;
        };
    }

    public static Comparator<ItemSnapshot> axeTierComparator() {
        return Comparator.comparingInt(
                snap -> -AXE_TIER_RANK.getOrDefault(snap.itemId, 0));
    }
```

### Step 4: Add `retrieveToolFromChests()` method

- [ ] Add after the axe helpers, before the closing `}`:

```java
    /**
     * Walk to a registered chest and retrieve one tool matching the given criteria.
     * Runs on a worker thread. Uses callOnServer for snapshot refresh.
     *
     * @return true if a tool was withdrawn into the bot's inventory
     */
    public static boolean retrieveToolFromChests(ServerPlayerEntity bot,
                                                  ServerWorld world,
                                                  ServerCommandSource source,
                                                  Predicate<ItemSnapshot> snapshotFilter,
                                                  Predicate<ItemStack> stackPredicate,
                                                  Comparator<ItemSnapshot> snapshotComparator,
                                                  int maxRange) {
        if (bot == null || world == null || source == null) return false;
        MinecraftServer server = world.getServer();
        if (server == null) return false;

        // Refresh snapshots on server thread (block entities must be read there)
        Boolean refreshed = callOnServer(server, () -> {
            BotChestRegistryService.refreshAllSnapshots(bot, world);
            return Boolean.TRUE;
        }, 3000L, Boolean.FALSE);
        if (!Boolean.TRUE.equals(refreshed)) {
            LOGGER.debug("Chest tool retrieval: snapshot refresh failed/timed out for {}",
                    bot.getName().getString());
            return false;
        }

        // Get all registered chests for this bot/owner
        List<BotChestRegistryService.ChestRecord> allChests =
                BotChestRegistryService.listChestsForOwner(bot, world);
        if (allChests.isEmpty()) return false;

        double maxDistSq = (double) maxRange * maxRange;
        BlockPos botPos = bot.getBlockPos();

        // Build candidate list: chests within range whose snapshots contain matching items
        record ChestCandidate(BlockPos pos, ItemSnapshot bestMatch, double distSq) {}
        List<ChestCandidate> candidates = new ArrayList<>();

        for (var record : allChests) {
            if (record.destroyed) continue;
            BlockPos pos = record.toBlockPos();
            if (pos == null) continue;
            double distSq = botPos.getSquaredDistance(pos);
            if (distSq > maxDistSq) continue;
            if (record.contentsSnapshot == null) continue;

            // Find the best matching snapshot item in this chest
            ItemSnapshot bestMatch = null;
            for (ItemSnapshot snap : record.contentsSnapshot) {
                if (snap != null && snapshotFilter.test(snap)) {
                    if (bestMatch == null || snapshotComparator.compare(snap, bestMatch) < 0) {
                        bestMatch = snap;
                    }
                }
            }
            if (bestMatch != null) {
                candidates.add(new ChestCandidate(pos.toImmutable(), bestMatch, distSq));
            }
        }

        if (candidates.isEmpty()) {
            LOGGER.debug("Chest tool retrieval: no matching chests within {} blocks for {}",
                    maxRange, bot.getName().getString());
            return false;
        }

        // Sort: best tool tier first, then nearest
        candidates.sort(Comparator
                .<ChestCandidate, ItemSnapshot>comparing(c -> c.bestMatch, snapshotComparator)
                .thenComparingDouble(c -> c.distSq));

        LOGGER.info("Chest tool retrieval: {} candidate chest(s) for {} within {} blocks",
                candidates.size(), bot.getName().getString(), maxRange);

        // Try each candidate
        for (ChestCandidate candidate : candidates) {
            int withdrawn = ChestStoreService.withdrawMatchingWalkOnly(
                    source, bot, candidate.pos, 1, stackPredicate);
            if (withdrawn > 0) {
                LOGGER.info("Chest tool retrieval: withdrew tool from chest at {} for {}",
                        candidate.pos.toShortString(), bot.getName().getString());
                return true;
            }
            LOGGER.debug("Chest tool retrieval: chest at {} had no valid match for {}",
                    candidate.pos.toShortString(), bot.getName().getString());
        }

        LOGGER.debug("Chest tool retrieval: all {} candidates exhausted for {}",
                candidates.size(), bot.getName().getString());
        return false;
    }
```

### Step 5: Build and verify

- [ ] Run: `./gradlew build -x test`
- [ ] Expected: BUILD SUCCESSFUL

### Step 6: Commit

- [ ] Commit:
```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/ToolProvisionService.java
git commit -m "feat: Add retrieveToolFromChests() to ToolProvisionService

General-purpose method for retrieving tools from registered chests.
Includes axe-specific helpers (wooden/stone/copper only, no enchanted,
min 8 durability). Uses callOnServer pattern for thread-safe snapshot
refresh."
```

---

## Task 2: Integrate into WoodcutSkill

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java:4775-4792` (`prepareWoodcutTooling`, `selectAxe`), `:1959`, `:2229`, `:3917`, `:4484` (4 call sites)

### Step 1: Add `ensureAxeOrRetrieve()` method

- [ ] Add after the existing `ensureAxeEquipped()` method (after line ~5280) in `WoodcutSkill.java`:

```java
    private boolean ensureAxeOrRetrieve(ServerPlayerEntity bot) {
        if (ensureAxeEquipped(bot)) return true;
        if (isAbortRequested(bot)) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        ServerCommandSource source = bot.getCommandSource();
        boolean retrieved = ToolProvisionService.retrieveToolFromChests(
                bot, world, source,
                ToolProvisionService.allowedAxeSnapshotFilter(),
                ToolProvisionService.allowedWoodcutAxePredicate(),
                ToolProvisionService.axeTierComparator(),
                32);
        if (retrieved && ensureAxeEquipped(bot)) {
            ChatUtils.sendSystemMessage(source, "Found an axe in a nearby chest.");
            return true;
        }
        return false;
    }
```

Verify that `ServerWorld` is already imported (it should be — the file uses it elsewhere). Also verify `ToolProvisionService` is already imported or add the import.

### Step 2: Update `prepareWoodcutTooling()`

- [ ] Replace lines 4775-4788 with:

```java
    private void prepareWoodcutTooling(ServerCommandSource source, ServerPlayerEntity bot) {
        if (selectAxe(bot)) {
            return;
        }
        boolean crafted = ToolProvisionService.ensureAxe(bot, source, source.getPlayer());
        if (crafted) {
            if (selectAxe(bot)) {
                return;
            }
        }
        // Try retrieving from registered chests before falling back to hands
        if (bot.getEntityWorld() instanceof ServerWorld world) {
            boolean retrieved = ToolProvisionService.retrieveToolFromChests(
                    bot, world, source,
                    ToolProvisionService.allowedAxeSnapshotFilter(),
                    ToolProvisionService.allowedWoodcutAxePredicate(),
                    ToolProvisionService.axeTierComparator(),
                    32);
            if (retrieved) {
                if (selectAxe(bot)) {
                    ChatUtils.sendSystemMessage(source, "Found an axe in a nearby chest.");
                    return;
                }
            }
        }
        // Continue with hands/non-tools so woodcut still works when no axe is available.
        selectHandsOrHarmlessItem(bot);
        ChatUtils.sendSystemMessage(source, "No axe available; I'll chop with my hands for now.");
    }
```

### Step 3: Convert 4 mid-session call sites

- [ ] Replace `ensureAxeEquipped(bot)` → `ensureAxeOrRetrieve(bot)` at these 4 locations:

1. **Line ~1959** (descent sweep mining):
   - Old: `ensureAxeEquipped(bot);`
   - New: `ensureAxeOrRetrieve(bot);`

2. **Line ~2229** (branch mining):
   - Old: `ensureAxeEquipped(bot);`
   - New: `ensureAxeOrRetrieve(bot);`

3. **Line ~3917** (breakLeaf log case):
   - Old: `ensureAxeEquipped(bot);`
   - New: `ensureAxeOrRetrieve(bot);`

4. **Line ~4484** (mineBlockDetailed preferAxe):
   - Old: `ensureAxeEquipped(bot);`
   - New: `ensureAxeOrRetrieve(bot);`

**Do NOT convert** these 2 sites (leave as `ensureAxeEquipped`):
- Line ~4791 (`selectAxe()` delegate) — called from `prepareWoodcutTooling` which has its own chest step
- Line ~4874 (`selectAdaptiveToolOrHands`) — misc cleanup blocks, not worth a 32-block walk

### Step 4: Build and verify

- [ ] Run: `./gradlew build -x test`
- [ ] Expected: BUILD SUCCESSFUL

### Step 5: Commit

- [ ] Commit:
```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: WoodcutSkill retrieves axes from nearby chests

Integrates ToolProvisionService.retrieveToolFromChests() into woodcut:
- prepareWoodcutTooling: inventory -> craft -> chest -> bare hands
- ensureAxeOrRetrieve: 4 mid-session call sites try chest retrieval
  before degrading to hands
Allowed: wooden/stone/copper axes, no enchanted, 32-block range."
```

---

## Task 3: Update changelog and backlog

**Files:**
- Modify: `changelog.md`
- Modify: `RALPH_TASK.md`

### Step 1: Add changelog entry

- [ ] Add entry to top of `changelog.md`:

```markdown
## 2026-04-04 — Chest tool retrieval

- Added `ToolProvisionService.retrieveToolFromChests()` — general-purpose method for retrieving tools from registered chests within a configurable range
- Axe-specific helpers: `allowedAxeSnapshotFilter()`, `allowedWoodcutAxePredicate()`, `axeTierComparator()` — wooden/stone/copper only, no enchanted, min 8 durability
- WoodcutSkill integration: `prepareWoodcutTooling()` now tries chest retrieval after crafting fails; 4 mid-session call sites use `ensureAxeOrRetrieve()` wrapper
- Bot sends chat message when it finds an axe in a chest
```

### Step 2: Mark backlog item complete

- [ ] In `RALPH_TASK.md`, change the axe retrieval backlog item from `- [ ]` to `- [x]`:

Old: `- [ ] **Axe retrieval from nearby chests**: When the bot runs out of axes during woodcut...`
New: `- [x] **Axe retrieval from nearby chests**: When the bot runs out of axes during woodcut...`

### Step 3: Commit

- [ ] Commit:
```bash
git add changelog.md RALPH_TASK.md
git commit -m "docs: Update changelog and mark axe retrieval complete"
```
