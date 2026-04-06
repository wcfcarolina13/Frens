# Bot Enchanting Screen

## Summary

Allow players to enchant items on behalf of bots using the vanilla enchanting UI. The bot's XP, inventory, and enchantment seed drive the enchantment offers. Accessed via a new "Enchant" button in the Spells menu, visible only when the player is near an enchanting table.

## Motivation

Bots accumulate XP/levels through normal gameplay (mining, combat, fishing) but have no way to spend them. Enchanting is the primary XP sink in Minecraft and directly improves the bot's tool effectiveness.

## Design

### Entry Point: CompanionSpellsScreen

Add an "Enchant" `ButtonWidget` to `CompanionSpellsScreen`. Enabled only when `isNearEnchantingTable()` returns true (method already exists in the class). Click sends a `BotEnchantOpenPayload` C2S packet with the bot alias.

### Network: BotEnchantOpenPayload

Minimal C2S payload record:
- `String botAlias` — identifies which bot to enchant for
- Registered in `Frens.java` (server receiver) and `FrensClient.java` (payload registration)

### Server Handler

On receiving `BotEnchantOpenPayload`:

1. **Validate** the sending player is near an enchanting table (reuse `modCommandRegistry.isNearEnchantingTable(player, 4)`)
2. **Resolve** the bot by alias via `BotRegistry`
3. **Validate** the bot is within reasonable distance of the player (~64 blocks, matching existing inventory access checks)
4. **Find** the nearest enchanting table `BlockPos` — add a new `findNearestEnchantingTable(player, radius)` helper to `modCommandRegistry` that returns `BlockPos` (or null). The existing `isNearEnchantingTable()` returns only a boolean; the new helper reuses the same scan loop but returns the position.
5. **Open** the screen:

```java
realPlayer.openHandledScreen(new SimpleNamedScreenHandlerFactory(
    (syncId, playerInv, player) -> new BotEnchantmentScreenHandler(
        syncId, bot.getInventory(),
        ScreenHandlerContext.create(world, tablePos), bot
    ),
    Text.literal(botDisplayName + "'s Enchanting")
));
```

### BotEnchantmentScreenHandler

Extends vanilla `EnchantmentScreenHandler`. Located in `ui/` (matching `BotPlayerInventoryScreenHandler`).

**Why full reimplementation of `onButtonClick`:** Vanilla's `onButtonClick` captures the `player` parameter in a lambda passed to `context.run(...)`. Inside that lambda, `player` is used for XP deduction (`applyEnchantmentCosts`), creative-mode checks (`isInCreativeMode`), stat tracking (`incrementStat`), advancement triggers (`ENCHANTED_ITEM.trigger`), and re-seeding (`getEnchantmentTableSeed`). There is no way to override just the XP parts and delegate to super — the entire method body must be reimplemented with the bot entity substituted for the player parameter. Additionally, `generateEnchantments` is private, so enchantment generation must also be reimplemented or accessed via the slot list.

**Private field access strategy:** The super constructor stores the enchanting table's 2-slot `SimpleInventory` in a private `inventory` field. The subclass stores a parallel reference to its own copy passed to super, or reads item/lapis stacks via the `slots` list (index 0 = item slot, index 1 = lapis slot). The `seed`, `random`, and `context` fields are also private; the subclass stores its own parallel copies from the constructor args.

Estimated size: ~150-180 lines.

```
class BotEnchantmentScreenHandler extends EnchantmentScreenHandler {
    private final ServerPlayerEntity bot;
    private final Inventory enchantSlots;        // parallel ref to the 2-slot inventory
    private final ScreenHandlerContext tableCtx; // parallel ref to table context
    private final Random enchantRandom;          // parallel Random instance

    // Constructor: pass bot's PlayerInventory + table context to super
    // Store bot reference + parallel copies of private fields

    @Override onButtonClick(PlayerEntity player, int id):
        // Full reimplementation of vanilla onButtonClick + the enchant-apply lambda
        // All player references replaced with this.bot:
        //   - bot.experienceLevel for level check
        //   - bot.applyEnchantmentCosts() for XP deduction
        //   - bot.isInCreativeMode() for creative bypass (always false for bots)
        //   - bot.incrementStat(Stats.ENCHANT_ITEM) for stat credit
        //   - Criteria.ENCHANTED_ITEM.trigger(bot, ...) for advancement credit
        //   - bot.getEnchantmentTableSeed() for re-seeding

    @Override onClosed(PlayerEntity player):
        // Override to return leftover enchanting-slot items to the BOT's inventory
        // (not the real player). Vanilla drops items to the closing player, which
        // would cause item transfer from bot → real player.
        // Use this.bot for the drop target.

    @Override canUse(PlayerEntity player):
        // Delegate to super — checks real player's distance to table (correct behavior)
}
```

Key behaviors:
- `getType()` returns vanilla `ScreenHandlerType.ENCHANTMENT` (inherited) — client opens standard `EnchantmentScreen` with zero custom client code
- Enchantment offer generation uses `bot.getEnchantmentTableSeed()` and `bot.experienceLevel` (via the bot's `PlayerInventory.player` reference in super)
- Bookshelf power computed from the real table position (via `ScreenHandlerContext`)
- `canUse(PlayerEntity)` checks the *real player's* distance to the table (vanilla behavior — screen closes if player walks away)
- `onClosed` returns leftover slot items to the **bot**, not the real player
- Stats and advancements credited to the **bot** (it's spending its XP)

### What We Reuse (no changes needed)

- **Vanilla `EnchantmentScreen`** — client renders the standard enchanting UI
- **Vanilla slot syncing** — server pushes bot's inventory contents to the client
- **Vanilla property syncing** — enchantment IDs, levels, and costs sync automatically
- **Bot XP tracking** — `createFakePlayer` inherits `experienceLevel`, `experienceProgress`, `totalExperience` from `ServerPlayerEntity`

### UI Note

The "player inventory" grid in the enchanting UI will show the **bot's** inventory (since we pass `bot.getInventory()` to the constructor). The real player's own inventory is not visible. This is intentional — the player is enchanting on behalf of the bot using the bot's items and lapis.

### Edge Cases

| Scenario | Behavior |
|---|---|
| Player walks away from table | Vanilla `canUse()` closes the screen |
| Bot has no enchantable items | No enchantment offers shown (vanilla) |
| Bot has no lapis | Cannot select enchantments (vanilla) |
| Bot level too low | Enchantment buttons grayed out (vanilla) |
| Multiple bots nearby | Button enchants the bot whose Spells menu is open (alias-targeted) |
| Bot dies while screen open | `canUse()` or inventory sync will close/fail gracefully |
| No bookshelves | Only low-level enchantments offered (vanilla) |
| Screen closed without enchanting | Leftover items returned to bot inventory (overridden `onClosed`) |

## Files Changed

| File | Change |
|---|---|
| `ui/BotEnchantmentScreenHandler.java` | **New.** ~150-180 lines. Subclass of `EnchantmentScreenHandler` with full `onButtonClick`/`onClosed` reimplementation. |
| `network/BotEnchantOpenPayload.java` | **New.** ~15 lines. C2S payload record. |
| `GraphicalUserInterface/CompanionSpellsScreen.java` | Add "Enchant" button + send payload on click. ~10 lines. |
| `Commands/modCommandRegistry.java` | Add `findNearestEnchantingTable(player, radius)` returning `BlockPos`. ~15 lines. |
| `Frens.java` | Register payload type + server-side receiver handler. ~30 lines. |
| `FrensClient.java` | Register payload type on client. ~2 lines. |

## Not In Scope

- Autonomous bot self-enchanting (bot walks to table and enchants on its own)
- Anvil/grindstone support
- Enchantment book application
