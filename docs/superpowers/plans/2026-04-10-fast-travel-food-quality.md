# Fast-Travel Food Quality & Magic Bypass Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve the fast-travel food system: filter out toxic/precious food from budgets, extract food from containers before travel, rephrase the insufficient-food message with a steak estimate, and let magic-based travel bypass food requirements entirely.

**Architecture:** Changes span three files. HealingService gets the food classification sets and public predicate. NavigationArtifactService gets container extraction, budget filtering, provisions message, and magic-travel flag threading (through PendingTravel record, private overload, arrival handler). SpellNavigationNetworkManager switches to the new magic-travel entry point.

**Tech Stack:** Java 21, Minecraft Fabric 1.21.11, Fabric API 0.136.0. No automated tests; verification is `./gradlew build -x test`.

**Spec:** `docs/superpowers/specs/2026-04-10-fast-travel-food-quality-design.md`

---

## Task 1: Food Classification in HealingService

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/HealingService.java`

- [ ] **Step 1: Add PRECIOUS_FOODS set**

After the existing `FORBIDDEN_FOODS` set (line 57), add:

```java
// Valuable foods the bot should preserve — only eaten at starvation emergency.
private static final Set<String> PRECIOUS_FOODS = Set.of(
    "golden_apple",
    "enchanted_golden_apple",
    "golden_carrot"
);
```

- [ ] **Step 2: Add public `isTravelUsableFood(ItemStack)` predicate**

After `PRECIOUS_FOODS`, add a public static method that checks if a food item is suitable for the fast-travel budget (not forbidden, not precious):

```java
/**
 * Returns true if the item is edible and suitable for fast-travel provisioning
 * (i.e., not toxic and not too precious to consume casually).
 */
public static boolean isTravelUsableFood(ItemStack stack) {
    if (stack == null || stack.isEmpty()) return false;
    FoodComponent food = getFoodComponent(stack);
    if (food == null) return false;
    String itemId = stack.getItem().getTranslationKey().toLowerCase(java.util.Locale.ROOT);
    if (FORBIDDEN_FOODS.stream().anyMatch(itemId::contains)) return false;
    if (PRECIOUS_FOODS.stream().anyMatch(itemId::contains)) return false;
    return true;
}
```

Note: `getFoodComponent` is already a private static helper at line 382. Make it `package-private` (remove `private`) so NavigationArtifactService in the same package can reuse it if needed — or keep it private and let NavigationArtifactService go through `isTravelUsableFood`.

- [ ] **Step 3: Update `findCheapestSafeFood()` to also skip PRECIOUS_FOODS**

In `findCheapestSafeFood()` (line 268), the existing forbidden check at lines 283-288:

```java
String itemId = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
boolean forbidden = FORBIDDEN_FOODS.stream().anyMatch(itemId::contains);
if (forbidden) {
    continue;
}
```

Add a precious check right after:

```java
boolean precious = PRECIOUS_FOODS.stream().anyMatch(itemId::contains);
if (precious) {
    continue;
}
```

- [ ] **Step 4: Update `findDesperateFood()` to also consider PRECIOUS_FOODS**

Current `findDesperateFood()` (line 306) only returns rotten flesh. Expand it to also try precious foods as a second fallback:

```java
private static OptionalInt findDesperateFood(PlayerInventory inventory) {
    // First pass: rotten flesh (cheap, acceptable in desperation)
    for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
        ItemStack stack = inventory.getStack(i);
        if (stack.isEmpty()) continue;
        FoodComponent food = getFoodComponent(stack);
        if (food == null) continue;
        String itemId = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (itemId.contains("rotten_flesh")) {
            LOGGER.info("Desperate food: eating rotten flesh (slot {})", i);
            return OptionalInt.of(i);
        }
    }
    // Second pass: precious foods (golden apple, golden carrot — valuable but edible)
    for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
        ItemStack stack = inventory.getStack(i);
        if (stack.isEmpty()) continue;
        FoodComponent food = getFoodComponent(stack);
        if (food == null) continue;
        String itemId = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (PRECIOUS_FOODS.stream().anyMatch(itemId::contains)) {
            LOGGER.info("Desperate food: eating precious food {} (slot {})", itemId, i);
            return OptionalInt.of(i);
        }
    }
    return OptionalInt.empty();
}
```

- [ ] **Step 5: Build and verify**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/HealingService.java
git commit -m "feat: add PRECIOUS_FOODS set, isTravelUsableFood predicate, desperate food fallback"
```

---

## Task 2: Magic Travel Flag in NavigationArtifactService

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java`

- [ ] **Step 1: Add `magicTravel` field to `PendingTravel` record**

At line 411, extend the record:

```java
public record PendingTravel(UUID botUuid, String botAlias, BlockPos destination,
                            RegistryKey<World> dimension, long departureTick, long arrivalTick,
                            UUID ownerUuid, String mountEntityTypeId, double travelDistance,
                            boolean magicTravel) {}
```

- [ ] **Step 2: Add `magicTravel` field to `SavedTravel` DTO**

At line 128, add to the class:

```java
public boolean magicTravel;
```

- [ ] **Step 3: Update `flushPendingTravels()` to serialize `magicTravel`**

In `flushPendingTravels()` (line 1236), after `s.travelDistance = t.travelDistance();` add:

```java
s.magicTravel = t.magicTravel();
```

- [ ] **Step 4: Update `loadPendingTravels()` to deserialize `magicTravel`**

In `loadPendingTravels()` (line 1303), update the PendingTravel constructor call:

```java
PendingTravel travel = new PendingTravel(botUuid, s.botAlias, dest, dim,
        now, newArrival, ownerUuid, s.mountEntityTypeId, s.travelDistance,
        s.magicTravel);
```

- [ ] **Step 5: Add `magicTravel` param to private `beginDelayedTravel` overload**

At line 564, add the parameter:

```java
private static boolean beginDelayedTravel(MinecraftServer server, ServerPlayerEntity bot,
                                          String botAlias, BlockPos destination,
                                          RegistryKey<World> dimension, int delayTicks,
                                          UUID ownerUuid, boolean skipGates, boolean suppressOwnerNotify,
                                          boolean skipArtifactGate, boolean magicTravel) {
```

- [ ] **Step 6: Update all callers of the private overload**

There are 4 callers of the private overload that need the extra `false` argument:

1. Public `beginDelayedTravel` (line 499): append `, false`
2. `beginEmergencyTravel` (line 510): append `, false`
3. `beginBaseBypassTravel` (line ~525): append `, false`
4. `beginCoordinatedEmergencyTravel` (line ~555, two calls): append `, false` to both

- [ ] **Step 7: Thread `magicTravel` into PendingTravel creation**

At line 737 where `PendingTravel` is constructed, add `magicTravel`:

```java
PendingTravel travel = new PendingTravel(botUuid, botAlias, destination, dimension,
        now, arrival, ownerUuid, mountEntityTypeId, travelDistance, magicTravel);
```

- [ ] **Step 8: Add public `beginMagicTravel` entry point**

After the existing `beginBaseBypassTravel` method, add:

```java
/**
 * Magic travel path: spells that consume reagents (ender pearls, chorus fruit)
 * bypass food requirements entirely. The reagent cost IS the price.
 * Hunger drain on arrival is also skipped.
 */
public static boolean beginMagicTravel(MinecraftServer server, ServerPlayerEntity bot,
                                       String botAlias, BlockPos destination,
                                       RegistryKey<World> dimension, int delayTicks,
                                       UUID ownerUuid) {
    return beginDelayedTravel(server, bot, botAlias, destination, dimension, delayTicks,
            ownerUuid, false, false, false, true);
}
```

- [ ] **Step 9: Skip food gate when `magicTravel=true`**

In the food safety gate (line 601), wrap the entire block:

```java
// ── Food safety gate ─────────────────────────────────────────
if (!magicTravel) {
    // ... existing food budget calculation ...
}
```

- [ ] **Step 10: Skip hunger drain on arrival when `magicTravel=true`**

In the arrival handler (around line 1031), wrap the hunger drain:

```java
if (dist > 0 && !ps.travel().magicTravel()) {
```

(Replace the existing `if (dist > 0)` check.)

- [ ] **Step 11: Build and verify**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 12: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java
git commit -m "feat: add magicTravel flag — spells bypass food gate and hunger drain"
```

---

## Task 3: Food Budget Filtering + Provisions Message

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java`

- [ ] **Step 1: Filter food budget to use `isTravelUsableFood`**

In the food safety gate (lines 607-614), replace the existing loop with a filtered version. Add the import for HealingService if not already present.

Replace:
```java
for (int i = 0; i < bot.getInventory().size(); i++) {
    ItemStack stack = bot.getInventory().getStack(i);
    if (stack != null && !stack.isEmpty()) {
        FoodComponent food = stack.getComponents().get(DataComponentTypes.FOOD);
        if (food != null) {
            inventoryNutrition += food.nutrition() * stack.getCount();
        }
    }
}
```

With:
```java
for (int i = 0; i < bot.getInventory().size(); i++) {
    ItemStack stack = bot.getInventory().getStack(i);
    if (HealingService.isTravelUsableFood(stack)) {
        FoodComponent food = stack.getComponents().get(DataComponentTypes.FOOD);
        inventoryNutrition += food.nutrition() * stack.getCount();
    }
}
```

- [ ] **Step 2: Replace the provisions rejection message**

Replace the existing rejection message (lines 619-621):

```java
notifyOwner(server, ownerUuid,
        "\u00A7c" + botAlias + " doesn't have enough energy to travel that far. Feed them first.\u00A7r");
```

With:

```java
int shortfall = (int) Math.ceil(hungerCost) + MIN_POST_TRAVEL_FOOD - totalBudget;
int steakEstimate = (int) Math.ceil(shortfall / 8.0);
notifyOwner(server, ownerUuid,
        "\u00A7e" + botAlias + " needs provisions for this journey \u2014 roughly "
        + steakEstimate + " cooked steak worth of food (~"
        + shortfall + " hunger points). Pack extra before sending them off.\u00A7r");
```

Note: `\u2014` is an em dash. If Minecraft font doesn't render it, use `--` instead. The `\u00A7e` prefix makes it yellow (informational, not error red).

- [ ] **Step 3: Build and verify**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java
git commit -m "feat: filter forbidden/precious food from travel budget, rephrase provisions message"
```

---

## Task 4: Container Food Extraction

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java`

- [ ] **Step 1: Add container food extraction method**

Add a private helper method before the `beginDelayedTravel` private overload. This method scans bundles and shulker boxes in inventory for usable food, scores them, and extracts cheapest-first until the needed nutrition is met.

```java
/**
 * Extract usable food from containers (bundles, shulker boxes) into the bot's
 * main inventory so the fast-travel food budget can account for it.
 * Only extracts what is needed for the journey.
 *
 * @param bot              the bot entity
 * @param neededNutrition   how much additional nutrition the bot needs
 */
private static void extractFoodFromContainers(ServerPlayerEntity bot, int neededNutrition) {
    if (neededNutrition <= 0) return;

    record FoodCandidate(int invSlot, int containerIndex, double score, int nutrition,
                         boolean isBundle) {}

    List<FoodCandidate> candidates = new ArrayList<>();

    for (int slot = 0; slot < bot.getInventory().size(); slot++) {
        ItemStack stack = bot.getInventory().getStack(slot);
        if (stack.isEmpty()) continue;

        // Scan bundles
        var bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            int idx = 0;
            for (ItemStack bundled : bundle.iterate()) {
                if (HealingService.isTravelUsableFood(bundled)) {
                    FoodComponent food = bundled.getComponents().get(DataComponentTypes.FOOD);
                    double score = food.nutrition() + (food.saturation() * 2.0);
                    candidates.add(new FoodCandidate(slot, idx, score,
                            food.nutrition() * bundled.getCount(), true));
                }
                // Scan shulker boxes inside bundles (depth 2)
                var nestedContainer = bundled.get(DataComponentTypes.CONTAINER);
                if (nestedContainer != null) {
                    int nestedIdx = 0;
                    for (ItemStack nested : nestedContainer.iterateNonEmpty()) {
                        if (HealingService.isTravelUsableFood(nested)) {
                            FoodComponent food = nested.getComponents().get(DataComponentTypes.FOOD);
                            double score = food.nutrition() + (food.saturation() * 2.0);
                            // Mark as non-bundle; extraction from nested is complex,
                            // skip for now — only extract from top-level containers.
                        }
                        nestedIdx++;
                    }
                }
                idx++;
            }
        }

        // Scan shulker boxes (items with CONTAINER component that are shulker-like)
        var container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null && stack.getItem() instanceof net.minecraft.item.BlockItem blockItem
                && blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
            int idx = 0;
            for (ItemStack contained : container.iterateNonEmpty()) {
                if (HealingService.isTravelUsableFood(contained)) {
                    FoodComponent food = contained.getComponents().get(DataComponentTypes.FOOD);
                    double score = food.nutrition() + (food.saturation() * 2.0);
                    candidates.add(new FoodCandidate(slot, idx, score,
                            food.nutrition() * contained.getCount(), false));
                }
                idx++;
            }
        }
    }

    if (candidates.isEmpty()) return;

    // Sort cheapest first
    candidates.sort(Comparator.comparingDouble(FoodCandidate::score));

    int extracted = 0;
    for (FoodCandidate c : candidates) {
        if (extracted >= neededNutrition) break;

        ItemStack containerStack = bot.getInventory().getStack(c.invSlot);
        if (containerStack.isEmpty()) continue;

        ItemStack foodToMove;
        if (c.isBundle) {
            var bundle = containerStack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle == null) continue;

            // Collect bundle contents, remove the target item
            List<ItemStack> remaining = new ArrayList<>();
            int idx = 0;
            ItemStack target = ItemStack.EMPTY;
            for (ItemStack bundled : bundle.iterate()) {
                if (idx == c.containerIndex && target.isEmpty()) {
                    target = bundled.copy();
                } else {
                    remaining.add(bundled.copy());
                }
                idx++;
            }
            if (target.isEmpty()) continue;

            // Rebuild bundle without the extracted item
            var builder = new net.minecraft.component.type.BundleContentsComponent.Builder(
                    net.minecraft.component.type.BundleContentsComponent.DEFAULT);
            for (ItemStack item : remaining) {
                builder.add(item);
            }
            containerStack.set(DataComponentTypes.BUNDLE_CONTENTS, builder.build());
            foodToMove = target;
        } else {
            // Shulker box extraction
            var container = containerStack.get(DataComponentTypes.CONTAINER);
            if (container == null) continue;

            List<ItemStack> slots = new ArrayList<>();
            container.streamNonEmpty().forEach(s -> slots.add(s.copy()));
            if (c.containerIndex >= slots.size()) continue;

            foodToMove = slots.remove(c.containerIndex);

            // Rebuild container component
            containerStack.set(DataComponentTypes.CONTAINER,
                    net.minecraft.component.type.ContainerComponent.fromStacks(slots));
        }

        // Place in main inventory
        if (!bot.getInventory().insertStack(foodToMove)) {
            // Inventory full — put the food back (abort extraction)
            LOGGER.debug("Cannot extract food from container: inventory full");
            break;
        }

        extracted += c.nutrition;
        LOGGER.info("Extracted food from container in slot {} for fast-travel provisioning", c.invSlot);
    }
}
```

- [ ] **Step 2: Call extraction before the food budget calculation**

In the food safety gate, right before `double hungerCost = travelDistance / HUNGER_DISTANCE_DIVISOR;` (line 604), insert:

```java
// Extract food from containers (bundles, shulker boxes) if main inventory
// doesn't have enough for the journey.
{
    double estHungerCost = travelDistance / HUNGER_DISTANCE_DIVISOR;
    int estNeeded = (int) Math.ceil(estHungerCost) + MIN_POST_TRAVEL_FOOD;
    int mainFood = bot.getHungerManager().getFoodLevel();
    for (int i = 0; i < bot.getInventory().size(); i++) {
        ItemStack s = bot.getInventory().getStack(i);
        if (HealingService.isTravelUsableFood(s)) {
            FoodComponent f = s.getComponents().get(DataComponentTypes.FOOD);
            mainFood += f.nutrition() * s.getCount();
        }
    }
    if (mainFood < estNeeded) {
        extractFoodFromContainers(bot, estNeeded - mainFood);
    }
}
```

- [ ] **Step 3: Build and verify**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL. If `iterateNonEmpty()` or `ContainerComponent.fromStacks()` don't exist in this MC version, check the actual method names via `grep -r "class ContainerComponent"` in the Fabric/MC source jars and adjust.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java
git commit -m "feat: extract food from bundles and shulker boxes before fast-travel budget check"
```

---

## Task 5: Wire Up Magic Travel in SpellNavigationNetworkManager

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/network/SpellNavigationNetworkManager.java`

- [ ] **Step 1: Switch Remote Guidance to use `beginMagicTravel`**

In `handleRemoteGuidance()` (line 148), replace:

```java
NavigationArtifactService.beginDelayedTravel(
        server, bot, bot.getName().getString(), goal, targetDim, delayTicks, commander.getUuid());
```

With:

```java
NavigationArtifactService.beginMagicTravel(
        server, bot, bot.getName().getString(), goal, targetDim, delayTicks, commander.getUuid());
```

- [ ] **Step 2: Build and verify**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/network/SpellNavigationNetworkManager.java
git commit -m "feat: Remote Guidance spell uses magic travel — bypasses food requirements"
```

---

## Task 6: Final Build + Changelog

**Files:**
- Modify: `changelog.md`

- [ ] **Step 1: Full build verification**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Update changelog**

Add entry to `changelog.md` summarizing all changes:
- PRECIOUS_FOODS set (golden apple, enchanted golden apple, golden carrot) — skipped in normal eating and fast-travel budget
- Container food extraction before fast-travel (bundles + shulker boxes)
- Provisions message with steak estimate
- Magic travel bypass (Remote Guidance spell skips food gate + hunger drain)
- Desperate food fallback expanded to include precious foods

- [ ] **Step 3: Commit changelog**

```bash
git add changelog.md
git commit -m "docs: changelog entry for fast-travel food quality improvements"
```

- [ ] **Step 4: Report artifact path**

Report the built JAR path. Do NOT copy to PrismLauncher — user is playing.
