# Liberal feeding & hunger tuning

Date: 2026-04-21
Status: Approved — ready for implementation plan

## Problem

Observed in the 2026-04-20 session ([latest.log](../../../../Library/Application%20Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log), tick 14:02–14:17):

1. Bot's hunger plateaued at food=15 for ~10 minutes. `HealingService.autoEat` gate is `foodLevel < HUNGER_COMFORTABLE (15)` — strict, so at exactly 15 the bot neither self-eats nor accepts food from the player. The player saw "bot refuses food" and gave up.
2. After sunset fast-travel home + sunrise fast-travel back to the fishing spot, food dropped to 9. Auto-hunt fired at food=9 even though the bot had a nearly-full inventory of raw fish.
3. `BotAutoCookingService` fired in parallel with auto-hunt (same threshold, `foodLevel <= 10`), sending "Heading to the furnace..." — redundant given the hunt.
4. `HuntSkill.attackTarget` crashed with `ThreadLocalRandom accessed from a different thread` under c2me. `bot.attack(target)` and `bot.swingHand(...)` were being called from a worker thread.

Root cause is three independent gaps, not one bug:

- Feeding threshold (`< 15`) is stricter than the visual hunger bar suggests and creates a dead zone where the bot looks hungry but refuses food.
- Auto-hunt doesn't consult the backup-food count before firing at `food ≤ 10`. The existing `MIN_BACKUP_FOOD_ITEMS` check only runs when `food > 10`.
- `HuntSkill.attackTarget` mutates entity state off the server thread.

The sunset/sunrise double fast-travel was investigated and is working as designed: one trip home at sunset, one resume trip back at sunrise ([BotAutoReturnSunsetService.java:315–348](../../../src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java)).

## Goals

- Let the player feed a bot any non-precious food whenever the bot isn't at max hunger.
- Gate auto-hunt on larder size — don't hunt when the bot already has plenty to eat.
- Let the bot eat earlier when its inventory is abundant (full stack of non-precious food).
- Fix the off-thread entity mutation crash in hunt combat.

## Non-goals

- No change to auto-hunt when the larder is genuinely empty.
- No change to `BotAutoCookingService` thresholds. (It still fires at `food ≤ 10`, but with the hunt gate in place, auto-hunt will no longer fire simultaneously; auto-cook alone is fine.)
- No change to sunset/sunrise fast-travel behavior.

## Design

### 1. Liberal feeding — `BotFoodGivingService`

**File:** [BotFoodGivingService.java](../../../src/main/java/net/wcfcarolina13/GameAI/services/BotFoodGivingService.java)

Current acceptance rule (line 108–115):

```java
boolean needsComfortFood = foodLevel < HUNGER_COMFORTABLE;  // < 15
boolean needsRegenFuel = isHungry && missingHealth && (foodLevel < REGEN_READY_FOOD_LEVEL || saturation <= 0.0F);
if (!needsComfortFood && !needsRegenFuel) {
    showDialogue(bot, false);  // refuse
    return true;
}
```

New acceptance rule for **non-precious** foods: `foodLevel < 20`. Drop the `HUNGER_COMFORTABLE` and `needsRegenFuel` gates.

For **precious** foods (membership in `HealingService.PRECIOUS_FOODS` — `golden_apple`, `enchanted_golden_apple`, `golden_carrot`):

- If per-bot toggle **"Auto-accept precious foods"** is ON: consume normally (same acceptance rule as above — `foodLevel < 20`).
- If OFF: do **not** consume. Instead, emit a chat message to the interacting player:

  ```text
  <Bot>: That's precious. Eat it anyway? [Yes] [No]
  ```

  `[Yes]` and `[No]` are clickable `ClickEvent.RUN_COMMAND` text components pointing at an internal command (see "Confirmation protocol" below). The held stack stays with the player until Yes is clicked.

**Precious food filtering:** `HealingService.PRECIOUS_FOODS` is already `Set<String>` matched via `stack.getItem().getTranslationKey().contains(id)`. Extract two public static predicates on `HealingService`:

```java
public static boolean isPrecious(ItemStack stack);
public static boolean isForbidden(ItemStack stack);
```

Reuse both in `BotFoodGivingService` (currently it doesn't check precious at all) and in the new `HuntSkill.countSafeFoodItems`.

**Forbidden foods** (`rotten_flesh`, `poisonous_potato`, `spider_eye`, `pufferfish`, `suspicious_stew`) keep current behavior — refused with a dialogue line.

#### Confirmation protocol

A lightweight pending-confirmation map keyed by `(playerUuid, botUuid)`:

```java
record PendingFeed(UUID playerUuid, UUID botUuid, Item expectedItem, long expiryMillis) {}
```

- On the precious-food interaction when toggle is OFF: record a `PendingFeed` with `expiryMillis = now + 15_000`, send the chat prompt.
- `[Yes]` click runs `/frens feedconfirm <botUuid>` (suffix already namespaced under `/bot` or a new root — see "Command wiring" below). Handler:
  1. Look up pending entry for `(sender.uuid, botUuid)`. If missing/expired, send "that offer expired" to sender and return.
  2. Verify the player is still holding ≥1 of `expectedItem`. If not, clear and send "you're no longer holding that".
  3. Take 1 from the held stack and run the normal feed path (insert into bot inventory, select hotbar slot, `useSelectedItem`).
  4. Clear the pending entry.
- `[No]` click runs `/frens feedcancel <botUuid>` which just clears the pending entry and sends a brief ack.
- Cleanup: a single `onServerTick` sweep (every ~20 ticks) purges expired entries. Entries are also cleared on bot removal and player disconnect.

Pending state lives in `BotFoodGivingService` itself. No persistence — if the player logs out mid-prompt, the entry expires.

**Command wiring:** register `feedconfirm` and `feedcancel` subcommands in [modCommandRegistry.java](../../../src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java) under the existing `/bot` root (matches existing convention — e.g. `/bot stop`, `/bot come`). Both take a single `UUID` arg. Both are hidden from help (internal-only, surfaced only via clickable chat).

#### Per-bot toggle

Add `autoAcceptPreciousFoods: boolean` (default `false`) to the same persistence surface used by the durability preservation toggle (memory note: [project_durability_preservation.md] — lives in `DurabilityPolicyService` per-bot config). The precious-foods flag follows the same pattern: lookup helper + setter, persisted with the bot's existing per-bot config blob.

UI: add a checkbox row to **Admin → Behavior** tab labeled `"Auto-accept precious foods"`. Wired to the same client-server sync pattern used by the durability toggle.

### 2. Hunt gate — `BotAutoHuntService` + `HuntSkill`

**Files:** [BotAutoHuntService.java:146–153](../../../src/main/java/net/wcfcarolina13/GameAI/services/BotAutoHuntService.java), [HuntSkill.java:1559–1571](../../../src/main/java/net/wcfcarolina13/GameAI/skills/impl/HuntSkill.java)

Current logic (paraphrased):

```java
if (foodLevel > AUTO_HUNT_HUNGER_THRESHOLD) {        // > 10
    if (foodLevel > 14 || countFoodItems(bot) >= MIN_BACKUP_FOOD_ITEMS) continue;  // skip
}
// falls through and hunts
```

New logic: check backup food **first**, regardless of hunger level:

```java
if (HuntSkill.countSafeFoodItems(bot) >= HuntSkill.MIN_BACKUP_FOOD_ITEMS) {
    continue;  // has food to eat — don't hunt
}
// existing food-level check stays as a secondary gate (starvation override is covered by the emergency threshold further below, see note).
if (foodLevel > AUTO_HUNT_HUNGER_THRESHOLD) {
    continue;
}
```

**New helper `HuntSkill.countSafeFoodItems(ServerPlayerEntity bot)`:** identical to `countFoodItems` but skips any stack where `HealingService.isPrecious(stack)` or `HealingService.isForbidden(stack)` returns true. Raw fish, cooked meat, bread, carrots, etc. all count. Golden apples and rotten flesh don't.

**Constant change:** `HuntSkill.MIN_BACKUP_FOOD_ITEMS` bumped from `4` → `8`. Used by both the auto-hunt gate and the existing mid-hunger check.

**Emergency starvation:** if `foodLevel <= HUNGER_EMERGENCY (2)` the bot will die soon. But by that point auto-eat should already have consumed from the larder (since `autoEat` runs from multiple tick paths). If auto-eat can't fire for some reason and the bot is truly at food=2 with a full inventory of food, auto-hunt being gated is the right call — it's a symptom of auto-eat failing, and hunting won't help. No special-case override needed.

### 3. Auto-eat abundance — `HealingService` + `BotFoodGivingService`

**File:** [HealingService.java:109–130](../../../src/main/java/net/wcfcarolina13/GameAI/services/HealingService.java)

Add helper:

```java
public static boolean hasAbundantFood(ServerPlayerEntity bot) {
    if (bot == null) return false;
    for (int i = 0; i < bot.getInventory().size(); i++) {
        ItemStack stack = bot.getInventory().getStack(i);
        if (stack.isEmpty() || stack.getCount() < 64) continue;
        if (isPrecious(stack) || isForbidden(stack)) continue;
        FoodComponent food = stack.getComponents().get(DataComponentTypes.FOOD);
        if (food != null && food.nutrition() > 0) return true;
    }
    return false;
}
```

In `autoEat`, replace the fixed `HUNGER_COMFORTABLE` threshold:

```java
int effectiveComfort = hasAbundantFood(bot) ? 18 : HUNGER_COMFORTABLE;  // 15
boolean needsComfortFood = foodLevel < effectiveComfort;
```

**Apply the same threshold in `BotFoodGivingService`**: when the interaction lands on a non-precious food, treat acceptance as `foodLevel < 20` directly (per design #1) — no abundance check needed, since the rule is already more generous than the abundance-boosted auto-eat.

The abundance rule only affects **what the bot self-eats**, not what it accepts from the player.

### 4. Threading fix — `HuntSkill.attackTarget`

**File:** [HuntSkill.java:1175–1195](../../../src/main/java/net/wcfcarolina13/GameAI/skills/impl/HuntSkill.java)

Current (runs on `auto-hunt-N` worker thread):

```java
if (distSq <= ATTACK_RANGE_SQ && bot.canSee(target)) {
    BotActions.selectBestMeleeWeapon(bot);
    bot.attack(target);                 // server-thread-only
    bot.swingHand(Hand.MAIN_HAND, true); // server-thread-only
}
```

New — schedule the mutation on the server thread via `MinecraftServer.execute`:

```java
if (distSq <= ATTACK_RANGE_SQ && bot.canSee(target)) {
    MinecraftServer server = bot.getServer();
    if (server != null) {
        server.execute(() -> {
            if (!target.isAlive() || target.isRemoved()) return;
            BotActions.selectBestMeleeWeapon(bot);
            bot.attack(target);
            bot.swingHand(Hand.MAIN_HAND, true);
        });
    }
}
```

`distSq`/`canSee` checks stay on the worker thread (read-only, and the distance can be slightly stale by the time the server executes — that's fine, the server-thread `isAlive` re-check prevents attacking a just-killed target). `sleep(220L)` and the outer `while` loop remain on the worker thread.

`BotActions.selectBestMeleeWeapon` reads inventory and calls `selectHotbarSlot` — the latter mutates the selected slot, so it belongs inside the `server.execute` block too. Confirmed by grepping `BotActions.selectHotbarSlot`.

## Components and data flow

```text
Player right-clicks bot with food in hand
        │
        ▼
BotFoodGivingService.canHandleFoodInteraction
        │
        ├── forbidden? → refuse, dialogue
        ├── precious & toggle OFF → register PendingFeed, send chat prompt with [Yes]/[No]
        │       │
        │       ▼
        │   /bot feedconfirm <bot> → verify pending + item → consume
        │   /bot feedcancel <bot>  → clear pending
        │
        └── non-precious or (precious & toggle ON) → foodLevel < 20 ? consume : refuse


Server tick
        │
        ▼
BotAutoHuntService.onServerTick
        │
        ▼
countSafeFoodItems(bot) >= 8 ? skip : (foodLevel <= 10 ? hunt : skip)


Server tick
        │
        ▼
HealingService.autoEat (called from BotEventHandler, BotFleeService, BotEmergencyRescueService, etc.)
        │
        ▼
hasAbundantFood(bot) ? threshold=18 : threshold=15
foodLevel < threshold ? eat : skip


Auto-hunt worker thread
        │
        ▼
HuntSkill.attackTarget — inner attack block wrapped in server.execute
```

## Testing plan

Manual (no test suite). Verify in 1.21.11:

- Feed bot cooked salmon at food=19 → accepted. (Was refused before.)
- Feed bot cooked salmon at food=9 → accepted. Unchanged.
- Feed bot golden apple at food=5, toggle OFF → chat prompt appears with clickable [Yes]/[No]; Yes → bot eats, item consumed from player; No → prompt dismissed, player keeps held item.
- Feed bot golden apple with toggle OFF, ignore prompt for 20 s, then click Yes → "that offer expired".
- Feed bot golden apple, toggle ON → silently consumed (no prompt).
- Feed bot rotten flesh → refused with existing dialogue.
- With 64+ salmon in inventory and food=17 → bot auto-eats within 1–2 ticks.
- With 8+ raw fish in inventory and food=8 → auto-hunt does NOT fire, auto-eat does.
- With 0 safe food items and food=8 → auto-hunt fires as before.
- Auto-hunt engages a cow/zombie → no `ThreadLocalRandom` crash, attack and swing animations play normally.
- Admin → Behavior → toggle "Auto-accept precious foods" → persists across world reload per the existing per-bot config pattern.

## Risks and mitigations

- **Pending-feed map leaks** if onServerTick sweep misses an entry. Mitigation: bounded sweep, expiry covers all entries, entries also cleared on bot removal / player disconnect hooks.
- **Clickable command injection via chat:** the `[Yes]` text component uses `ClickEvent.RUN_COMMAND` with a server-registered `/bot` subcommand that validates `(sender, bot, item)` before acting. No arbitrary-command risk.
- **Abundance threshold thrashing:** if the bot eats its way from 64 to 63 and the threshold drops from 18 back to 15 mid-eat, the bot stops eating between ticks. Benign — next tick re-evaluates. Worst case, bot stops one bite short of 18 when it had 64 and now has 63.
- **Server-thread dispatch latency** for `HuntSkill.attackTarget`: the worker sleeps 220 ms between swings, far longer than one server tick, so the `server.execute` queue will drain between iterations. No piling-up risk.
