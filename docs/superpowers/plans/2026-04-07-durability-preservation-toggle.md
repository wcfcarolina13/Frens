# Durability Preservation Toggle Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-player toggle that makes bots refuse to use enchanted or expensive gear (gold/diamond/netherite/turtle shell) once durability drops below 11% (or 3% in combat), with a fallback chain of inventory re-scan → chest retrieval → crafting → stand-down and immersive overhead dialogue.

**Architecture:** A central stateless `DurabilityPolicyService` owns the rule. Selection sites in `PlayerUtils/` and a handful of skills/services call `shouldAvoid(bot, stack)` as a one-line filter. When a selection site finds zero compliant candidates, it calls `DurabilityFallbackService.requestRefresh(...)` which runs the chest/craft chain on a dedicated worker thread with a 20s cooldown. Toggle state lives in `ManualConfig` keyed by player UUID. Players reach the toggle via a new lightweight `BotPlayerPreferencesScreen` opened from a new footer button on `BotControlScreen` — this avoids the admin-only `AdminPlayerSettingsScreen` entirely.

**Tech Stack:** Java 21, Minecraft 1.21.11, Fabric 0.18.4, Gson (via ManualConfig), existing mod executors pattern.

**Spec:** [docs/superpowers/specs/2026-04-07-durability-preservation-toggle-design.md](docs/superpowers/specs/2026-04-07-durability-preservation-toggle-design.md)

---

## File Map

| File | Action | Responsibility |
| --- | --- | --- |
| [ManualConfig.java](src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java) | Modify | Add `playerPreserveExpensiveGear` map + accessors |
| [DurabilityPolicyService.java](src/main/java/net/wcfcarolina13/GameAI/services/DurabilityPolicyService.java) | Create | Pure-static rule: `shouldAvoid`, thresholds, preserved-item set |
| [UpdatePlayerPreservePayload.java](src/main/java/net/wcfcarolina13/network/UpdatePlayerPreservePayload.java) | Create | C2S payload: player sets their preserve preference |
| [RequestPlayerPreservePayload.java](src/main/java/net/wcfcarolina13/network/RequestPlayerPreservePayload.java) | Create | C2S payload: player asks the server for their current value |
| [PlayerPreserveStatePayload.java](src/main/java/net/wcfcarolina13/network/PlayerPreserveStatePayload.java) | Create | S2C payload: server sends current value to the client |
| [Frens.java](src/main/java/net/wcfcarolina13/Frens.java) | Modify | Register 3 payloads + server receivers; fallback executor shutdown hook |
| [FrensClient.java](src/main/java/net/wcfcarolina13/FrensClient.java) | Modify | Register S2C client receiver for `PlayerPreserveStatePayload` |
| [BotPlayerPreferencesScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerPreferencesScreen.java) | Create | New single-purpose player-facing preferences screen |
| [BotControlScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java) | Modify | Add "Personal Preferences" footer button opening the new screen |
| [DurabilityFallbackService.java](src/main/java/net/wcfcarolina13/GameAI/services/DurabilityFallbackService.java) | Create | Fallback orchestration (inventory → chest → craft → stand-down), cooldown, executor |
| [ToolSelector.java](src/main/java/net/wcfcarolina13/PlayerUtils/ToolSelector.java) | Modify | Filter preserved-below-threshold tools in `selectBestToolForBlock` |
| [armorUtils.java](src/main/java/net/wcfcarolina13/PlayerUtils/armorUtils.java) | Modify | Filter preserved-below-threshold armor in `findBestArmorSlot` |
| [CombatInventoryManager.java](src/main/java/net/wcfcarolina13/PlayerUtils/CombatInventoryManager.java) | Modify | Filter in `findBestWeaponSlot` and `ensureOffhandShield` |
| [BotActions.java](src/main/java/net/wcfcarolina13/GameAI/BotActions.java) | Modify | Filter in `meleeWeaponScore` / `combatWeaponScore` (score = 0 for preserved) |
| [FishingSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java) | Modify | Filter rod in `ensureFishingRod` |
| [WoolSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoolSkill.java) | Modify | Filter shears in `ensureShearsEquipped` |
| [ElytraFlightService.java](src/main/java/net/wcfcarolina13/GameAI/services/ElytraFlightService.java) | Modify | Filter elytra at equip phase |
| [CompanionOverheadDialogueService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadDialogueService.java) | Modify | Add three dialogue line pools + `tryShow*` helpers |
| [BotGuideScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java) | Modify | Add `settings_preserve_expensive_gear` topic |
| [changelog.md](changelog.md) | Modify | Add feature entry |

---

## Testing Notes (Read Before Starting)

**This project has NO automated tests** (per [CLAUDE.md](CLAUDE.md)). Verification is:

1. **Build check** — `./gradlew build -x test` after every task. Must pass with zero compilation errors.
2. **In-game manual verification** — at the end of each phase, run the scenarios listed in the "Verification" block for that phase. The user drives these manually in a test world.
3. **No test files are created or modified** — do not invent unit test infrastructure.

**Commit cadence** — commit after each task. Every commit message starts with `feat:`, `refactor:`, or `docs:` following the existing project convention. Do not combine tasks into one commit.

**Threading rules** (from CLAUDE.md):

- Server thread only: world mutations, inventory mutations, `equipStack`, hotbar swaps, door/interaction.
- Worker threads: pathfinding, scoring, retry loops. Use `server.execute(...)` to hop back to the server thread for any mutation.
- Never `Thread.sleep` on the server thread.

**Known-good API forms** (confirmed by grep at design time; use these exactly):

- Enchantment check: `stack.hasEnchantments()` — already used in [ToolProvisionService.java:1646](src/main/java/net/wcfcarolina13/GameAI/services/ToolProvisionService.java#L1646) and [BotMutualAidService.java:1253](src/main/java/net/wcfcarolina13/GameAI/services/BotMutualAidService.java#L1253).
- Payload location: `src/main/java/net/wcfcarolina13/network/` (flat — no `payloads/` subfolder; 71 existing payloads live here).
- Mod namespace literal: `"frens"` (as used in existing `SaveAPIKeyPayload` and peers). You can also use `Frens.MOD_ID` — either is fine, but pick one and match the file you're next to.
- `DrawContext.drawText(this.textRenderer, text, x, y, color, shadowBool)` — 6-arg form, not `drawTextWithShadow`. Match existing render methods in the file you're editing.
- `mouseClicked(net.minecraft.client.gui.Click click, boolean isInside)` in 1.21.x — use `click.x()` / `click.y()` and `click.button()` (verify against the existing override in the file you're editing).

---

## Chunk 1: Phase 1 — Storage, Policy Service, Payloads, Player Preferences UI

**Goal of this chunk:** Player can flip the toggle from a new "Personal Preferences" button on `BotControlScreen` and the value persists across server restarts. `DurabilityPolicyService` exists and is callable but has zero consumers yet. Nothing else changes bot behavior.

**At the end of Chunk 1, this must be true:**

- `./gradlew build -x test` passes.
- Any player (OP or non-OP) can click a new "Personal Preferences" button in the `BotControlScreen` footer.
- The button opens `BotPlayerPreferencesScreen` showing a "Preserve Expensive Gear" toggle that reflects the server-side value.
- Flipping the toggle sends a C2S payload; reopening the screen (or restarting the server and reopening) shows the value was persisted.
- The `DurabilityPolicyService.shouldAvoid(...)` method returns `false` for every stack because no selection site calls it yet.

---

### Task 1: Add player preference storage to ManualConfig

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java](src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java)

#### Step 1: Read ManualConfig to locate the botOwnership field

- [ ] Read [ManualConfig.java](src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java). Locate:
  - The `botOwnership` field declaration near line 49 (`private Map<String, BotOwnership> botOwnership = new HashMap<>();`)
  - The `getOwner(String alias)` method (around line 459) and `setOwner(String alias, BotOwnership)` method (around line 466)
  - The `save()` method (synchronized on `SAVE_LOCK`)
  - Note the `java.util.UUID` and `java.util.HashMap` import status

#### Step 2: Add the new field

- [ ] Insert this field declaration immediately below the existing `botOwnership` field. **Note: not `final`** — Gson deserialization can leave added fields null on older configs, and the setter lazily re-initializes.

```java
private Map<String, Boolean> playerPreserveExpensiveGear = new HashMap<>();
```

#### Step 3: Add the accessor methods

- [ ] Insert these two methods just after the existing `setOwner(...)` method (or wherever other player-keyed getters live — keep related code together):

```java
public boolean getPreserveExpensiveGear(java.util.UUID playerUuid) {
    if (playerUuid == null) {
        return false;
    }
    if (playerPreserveExpensiveGear == null) {
        return false;
    }
    return playerPreserveExpensiveGear.getOrDefault(playerUuid.toString(), Boolean.FALSE);
}

public void setPreserveExpensiveGear(java.util.UUID playerUuid, boolean value) {
    if (playerUuid == null) {
        return;
    }
    if (playerPreserveExpensiveGear == null) {
        playerPreserveExpensiveGear = new java.util.HashMap<>();
    }
    playerPreserveExpensiveGear.put(playerUuid.toString(), value);
}
```

If the file uses explicit imports for `java.util.UUID` and `java.util.HashMap`, replace the `java.util.` prefix in the bodies. If it uses wildcards, leave the fully qualified names for clarity.

#### Step 4: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds. If it fails with a missing-import error, add explicit `import java.util.UUID;` at the top of the file.

#### Step 5: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java
git commit -m "feat: add playerPreserveExpensiveGear storage to ManualConfig"
```

---

### Task 2: Create DurabilityPolicyService

**Files:**
- Create: [src/main/java/net/wcfcarolina13/GameAI/services/DurabilityPolicyService.java](src/main/java/net/wcfcarolina13/GameAI/services/DurabilityPolicyService.java)

#### Step 1: Create the file

- [ ] Create [DurabilityPolicyService.java](src/main/java/net/wcfcarolina13/GameAI/services/DurabilityPolicyService.java) with exactly this content:

```java
package net.wcfcarolina13.GameAI.services;

import java.util.Set;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import net.wcfcarolina13.Frens;

/**
 * Durability preservation policy for bot tool/armor/weapon selection.
 *
 * <p>When an owner toggles "Preserve Expensive Gear" on, bots owned by that
 * player refuse to use items that are both <em>preserved</em> (expensive
 * material or enchanted) and below the durability threshold (11% normally,
 * 3% in combat).
 *
 * <p>This class is pure-static and stateless. Selection sites call
 * {@link #shouldAvoid(ServerPlayerEntity, ItemStack)} as a one-line filter.
 * See spec at
 * {@code docs/superpowers/specs/2026-04-07-durability-preservation-toggle-design.md}.
 */
public final class DurabilityPolicyService {

    private DurabilityPolicyService() {}

    // ------------------------------------------------------------------
    // Thresholds
    // ------------------------------------------------------------------

    /** Normal (out-of-combat) durability threshold: 11%. */
    public static final double NORMAL_THRESHOLD = 0.11;

    /** Combat durability threshold: 3%. */
    public static final double COMBAT_THRESHOLD = 0.03;

    // ------------------------------------------------------------------
    // Preserved item set (28 items: gold/diamond/netherite tools + armor
    // + turtle helmet)
    // ------------------------------------------------------------------

    private static final Set<Item> PRESERVED_ITEMS = Set.of(
            // Gold tier (9)
            Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL,
            Items.GOLDEN_HOE, Items.GOLDEN_SWORD,
            Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE,
            Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS,
            // Diamond tier (9)
            Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL,
            Items.DIAMOND_HOE, Items.DIAMOND_SWORD,
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
            Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
            // Netherite tier (9)
            Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL,
            Items.NETHERITE_HOE, Items.NETHERITE_SWORD,
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
            Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
            // Turtle shell (1)
            Items.TURTLE_HELMET);

    // ------------------------------------------------------------------
    // Predicates
    // ------------------------------------------------------------------

    public static boolean isPreservedMaterial(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return PRESERVED_ITEMS.contains(stack.getItem());
    }

    /**
     * Returns true if the stack has any enchantment. Uses the canonical
     * {@code stack.hasEnchantments()} form already used by
     * {@code ToolProvisionService} and {@code BotMutualAidService}.
     */
    public static boolean isEnchanted(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.hasEnchantments();
    }

    public static boolean isPreserved(ItemStack stack) {
        return isPreservedMaterial(stack) || isEnchanted(stack);
    }

    /** Returns ratio in [0.0, 1.0]. Non-damageable stacks return 1.0. */
    public static double durabilityRatio(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageable()) {
            return 1.0;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) return 1.0;
        int remaining = max - stack.getDamage();
        if (remaining < 0) remaining = 0;
        return (double) remaining / (double) max;
    }

    /**
     * Returns the current threshold for this bot: 3% if in combat,
     * 11% otherwise.
     */
    public static double currentThreshold(ServerPlayerEntity bot) {
        if (bot == null) return NORMAL_THRESHOLD;
        UUID botId = bot.getUuid();
        if (botId != null && BotCombatCalloutService.isInCombat(botId)) {
            return COMBAT_THRESHOLD;
        }
        return NORMAL_THRESHOLD;
    }

    /**
     * Returns true if the owner of this bot has the preservation toggle
     * enabled. Null owner (unowned bot) → policy disabled.
     */
    public static boolean isPolicyEnabled(ServerPlayerEntity bot) {
        if (bot == null) return false;
        if (Frens.CONFIG == null) return false;
        UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
        if (ownerUuid == null) return false;
        return Frens.CONFIG.getPreserveExpensiveGear(ownerUuid);
    }

    /**
     * Main predicate. Returns true iff the bot should refuse to use this
     * stack right now because the policy is enabled, the stack is
     * preserved, and its durability is below the current threshold.
     */
    public static boolean shouldAvoid(ServerPlayerEntity bot, ItemStack stack) {
        if (bot == null || stack == null || stack.isEmpty()) return false;
        if (!stack.isDamageable()) return false;
        if (!isPolicyEnabled(bot)) return false;
        if (!isPreserved(stack)) return false;
        return durabilityRatio(stack) < currentThreshold(bot);
    }
}
```

#### Step 2: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 3: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/DurabilityPolicyService.java
git commit -m "feat: add DurabilityPolicyService rule engine"
```

---

### Task 3: Create the three network payloads

**Files:**
- Create: [src/main/java/net/wcfcarolina13/network/UpdatePlayerPreservePayload.java](src/main/java/net/wcfcarolina13/network/UpdatePlayerPreservePayload.java)
- Create: [src/main/java/net/wcfcarolina13/network/RequestPlayerPreservePayload.java](src/main/java/net/wcfcarolina13/network/RequestPlayerPreservePayload.java)
- Create: [src/main/java/net/wcfcarolina13/network/PlayerPreserveStatePayload.java](src/main/java/net/wcfcarolina13/network/PlayerPreserveStatePayload.java)

#### Step 1: Read an existing boolean-only payload for pattern reference

- [ ] Read [SaveAPIKeyPayload.java](src/main/java/net/wcfcarolina13/network/SaveAPIKeyPayload.java) if it exists, otherwise search for any small payload in that directory. Note:
  - The exact `CustomPayload.Id` construction pattern
  - The `PacketCodec` factory used (`PacketCodec.of`, `PacketCodec.tuple`, or another form)
  - The `Identifier.of("frens", "...")` vs `Frens.MOD_ID` choice — match the reference file
  - Whether `@Override` on `getId()` is used and what return type

You are pattern-matching three new payload files; use the reference file as your template for the exact API calls.

#### Step 2: Create UpdatePlayerPreservePayload (C2S boolean)

- [ ] Create [UpdatePlayerPreservePayload.java](src/main/java/net/wcfcarolina13/network/UpdatePlayerPreservePayload.java):

```java
package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S payload: player toggles their "Preserve Expensive Gear" preference.
 *
 * <p>The sender is always the subject — no target UUID field. The server
 * handler uses the player who sent the packet as the subject of the update.
 * This makes cross-player editing impossible by construction.
 */
public record UpdatePlayerPreservePayload(boolean enabled) implements CustomPayload {

    public static final CustomPayload.Id<UpdatePlayerPreservePayload> ID =
            new CustomPayload.Id<>(Identifier.of("frens", "update_player_preserve"));

    public static final PacketCodec<PacketByteBuf, UpdatePlayerPreservePayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeBoolean(value.enabled()),
                    buf -> new UpdatePlayerPreservePayload(buf.readBoolean()));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
```

If `SaveAPIKeyPayload` uses a different `PacketCodec` factory or `CustomPayload.Id` construction, match that form exactly. The important invariants are the one boolean field, the `"update_player_preserve"` path, and the C2S direction (registered in Task 5).

#### Step 3: Create RequestPlayerPreservePayload (C2S empty)

- [ ] Create [RequestPlayerPreservePayload.java](src/main/java/net/wcfcarolina13/network/RequestPlayerPreservePayload.java):

```java
package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S payload: player asks the server to send back their current
 * "Preserve Expensive Gear" preference. Empty body — the sender is
 * always the subject.
 */
public record RequestPlayerPreservePayload() implements CustomPayload {

    public static final RequestPlayerPreservePayload INSTANCE =
            new RequestPlayerPreservePayload();

    public static final CustomPayload.Id<RequestPlayerPreservePayload> ID =
            new CustomPayload.Id<>(Identifier.of("frens", "request_player_preserve"));

    public static final PacketCodec<PacketByteBuf, RequestPlayerPreservePayload> CODEC =
            new PacketCodec<>() {
                @Override
                public RequestPlayerPreservePayload decode(PacketByteBuf buf) {
                    return INSTANCE;
                }

                @Override
                public void encode(PacketByteBuf buf, RequestPlayerPreservePayload value) {
                    // no-op — empty payload
                }
            };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
```

**Pattern source:** this anonymous `PacketCodec<>()` form is what existing empty payloads in the codebase use — pattern-match against [RequestRecruitmentDialoguePayload.java](src/main/java/net/wcfcarolina13/network/RequestRecruitmentDialoguePayload.java) if it exists. Do **not** use `PacketCodec.unit(...)` — a grep across the tree confirms that helper is not used anywhere in this codebase, and it may or may not exist depending on Fabric API version.

#### Step 4: Create PlayerPreserveStatePayload (S2C boolean)

- [ ] Create [PlayerPreserveStatePayload.java](src/main/java/net/wcfcarolina13/network/PlayerPreserveStatePayload.java):

```java
package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C payload: server sends the current "Preserve Expensive Gear" value
 * to the client. Used by BotPlayerPreferencesScreen to populate the
 * initial toggle state on open.
 */
public record PlayerPreserveStatePayload(boolean enabled) implements CustomPayload {

    public static final CustomPayload.Id<PlayerPreserveStatePayload> ID =
            new CustomPayload.Id<>(Identifier.of("frens", "player_preserve_state"));

    public static final PacketCodec<PacketByteBuf, PlayerPreserveStatePayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeBoolean(value.enabled()),
                    buf -> new PlayerPreserveStatePayload(buf.readBoolean()));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
```

#### Step 5: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds. All three files compile but are not yet registered or handled.

#### Step 6: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/network/UpdatePlayerPreservePayload.java src/main/java/net/wcfcarolina13/network/RequestPlayerPreservePayload.java src/main/java/net/wcfcarolina13/network/PlayerPreserveStatePayload.java
git commit -m "feat: add player preference network payloads"
```

---

### Task 4: Register payloads and add server + client handlers

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/Frens.java](src/main/java/net/wcfcarolina13/Frens.java)
- Modify: [src/main/java/net/wcfcarolina13/FrensClient.java](src/main/java/net/wcfcarolina13/FrensClient.java)

#### Step 1: Read Frens.java to locate the registration block

- [ ] Read [Frens.java](src/main/java/net/wcfcarolina13/Frens.java). Find:
  - The `onInitialize()` method
  - The `PayloadTypeRegistry.playC2S().register(...)` and `PayloadTypeRegistry.playS2C().register(...)` call blocks
  - The `ServerPlayNetworking.registerGlobalReceiver(...)` call block
  - Note the existing imports (`ServerPlayNetworking`, `PayloadTypeRegistry`)

#### Step 2: Add payload registrations in Frens.java

- [ ] Add imports if missing:

```java
import net.wcfcarolina13.network.UpdatePlayerPreservePayload;
import net.wcfcarolina13.network.RequestPlayerPreservePayload;
import net.wcfcarolina13.network.PlayerPreserveStatePayload;
```

- [ ] Add three registration lines alongside the other registrations (C2S for the two client → server payloads, S2C for the server → client reply):

```java
PayloadTypeRegistry.playC2S().register(UpdatePlayerPreservePayload.ID, UpdatePlayerPreservePayload.CODEC);
PayloadTypeRegistry.playC2S().register(RequestPlayerPreservePayload.ID, RequestPlayerPreservePayload.CODEC);
PayloadTypeRegistry.playS2C().register(PlayerPreserveStatePayload.ID, PlayerPreserveStatePayload.CODEC);
```

#### Step 3: Add server receivers in Frens.java

- [ ] In the `ServerPlayNetworking.registerGlobalReceiver(...)` block, add:

```java
ServerPlayNetworking.registerGlobalReceiver(UpdatePlayerPreservePayload.ID, (payload, context) -> {
    net.minecraft.server.network.ServerPlayerEntity sender = context.player();
    if (sender == null) {
        return;
    }
    java.util.UUID senderUuid = sender.getUuid();
    if (senderUuid == null) {
        return;
    }
    // Subject is always the sender — no cross-player editing.
    context.server().execute(() -> {
        if (Frens.CONFIG != null) {
            Frens.CONFIG.setPreserveExpensiveGear(senderUuid, payload.enabled());
            Frens.CONFIG.save();
        }
    });
});

ServerPlayNetworking.registerGlobalReceiver(RequestPlayerPreservePayload.ID, (payload, context) -> {
    net.minecraft.server.network.ServerPlayerEntity sender = context.player();
    if (sender == null) {
        return;
    }
    java.util.UUID senderUuid = sender.getUuid();
    if (senderUuid == null) {
        return;
    }
    context.server().execute(() -> {
        boolean current = Frens.CONFIG != null
                && Frens.CONFIG.getPreserveExpensiveGear(senderUuid);
        ServerPlayNetworking.send(sender, new PlayerPreserveStatePayload(current));
    });
});
```

#### Step 4: Find FrensClient and add the client receiver

- [ ] Read [FrensClient.java](src/main/java/net/wcfcarolina13/FrensClient.java). Find the `ClientPlayNetworking.registerGlobalReceiver(...)` block (there should be one — grep for `ClientPlayNetworking` in the file).

- [ ] Add the import:

```java
import net.wcfcarolina13.network.PlayerPreserveStatePayload;
```

- [ ] Add the client receiver in the existing client-receiver block:

```java
ClientPlayNetworking.registerGlobalReceiver(PlayerPreserveStatePayload.ID, (payload, context) -> {
    // Write the received value into a static field on the preferences screen.
    // Reading the field happens on the client thread inside the screen's tick/render;
    // writing from the network thread is fine for a single volatile boolean.
    net.wcfcarolina13.GraphicalUserInterface.BotPlayerPreferencesScreen.setServerValue(payload.enabled());
});
```

**Note:** the `BotPlayerPreferencesScreen.setServerValue` method doesn't exist yet; it will be created in Task 5. This will cause a compile error if Task 5 isn't done before the next build. Defer the build check until after Task 5.

#### Step 5: Commit

- [ ] Commit (without building — next task finishes the wiring):

```bash
git add src/main/java/net/wcfcarolina13/Frens.java src/main/java/net/wcfcarolina13/FrensClient.java
git commit -m "feat: register player preserve payloads and handlers"
```

---

### Task 5: Create BotPlayerPreferencesScreen

**Files:**
- Create: [src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerPreferencesScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerPreferencesScreen.java)

#### Step 1: Read an existing small screen for pattern reference

- [ ] Read [BotControlScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java) at least the first 200 lines + the `init()` method + the `render()` method + the `mouseClicked()` override. Note:
  - The `extends Screen` vs `extends HandledScreen` choice
  - The `init()` widget registration pattern
  - The exact `mouseClicked(Click click, boolean isInside)` signature — use `click.x()`, `click.y()`, `click.button()`
  - The `render(DrawContext context, int mouseX, int mouseY, float delta)` signature
  - The tooltip rendering helper if one exists (grep for `drawTooltip` or `setTooltip`)
  - How parent-screen `back` navigation works (saved reference + `this.client.setScreen(parent)` on close)

- [ ] Also read [BotGuideScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java) if it's smaller — it's a read-only informational screen and may have simpler layout code to pattern-match.

#### Step 2: Create the file

- [ ] Create [BotPlayerPreferencesScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerPreferencesScreen.java) with this content. Adjust imports and the `Screen` superclass call if the pattern you observed in Step 1 differs:

```java
package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import net.wcfcarolina13.network.RequestPlayerPreservePayload;
import net.wcfcarolina13.network.UpdatePlayerPreservePayload;

/**
 * Player-facing preferences screen. Currently hosts a single toggle —
 * "Preserve Expensive Gear" — but can grow to hold additional player-level
 * preferences without affecting the admin permission matrix.
 *
 * <p>State model: the screen sends {@link RequestPlayerPreservePayload} on
 * open, and the server replies with {@link net.wcfcarolina13.network.PlayerPreserveStatePayload}
 * which writes into {@link #SERVER_VALUE}. The render loop polls this field.
 * When the player flips the toggle, we update {@link #SERVER_VALUE}
 * optimistically and send {@link UpdatePlayerPreservePayload} to the server.
 */
public class BotPlayerPreferencesScreen extends Screen {

    // Server-authoritative value, written by the S2C handler on the client.
    // Volatile because the network thread writes and the client thread reads.
    private static volatile Boolean SERVER_VALUE = null;

    /** Called from the client payload receiver. */
    public static void setServerValue(boolean value) {
        SERVER_VALUE = value;
    }

    private final Screen parent;

    // Layout constants
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 180;
    private static final int ROW_HEIGHT = 22;
    private static final int CHIP_WIDTH = 44;
    private static final int CHIP_HEIGHT = 18;
    private static final int PADDING = 14;

    private int panelX;
    private int panelY;
    private int chipX;
    private int chipY;

    private boolean requestSent = false;

    public BotPlayerPreferencesScreen(Screen parent) {
        super(Text.literal("Personal Preferences"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelY = (this.height - PANEL_HEIGHT) / 2;

        // Request the current value from the server exactly once per open.
        if (!requestSent) {
            ClientPlayNetworking.send(RequestPlayerPreservePayload.INSTANCE);
            requestSent = true;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        // Panel background
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xD0101010);
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + 1, 0xFF404040);
        context.fill(panelX, panelY + PANEL_HEIGHT - 1, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xFF404040);
        context.fill(panelX, panelY, panelX + 1, panelY + PANEL_HEIGHT, 0xFF404040);
        context.fill(panelX + PANEL_WIDTH - 1, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xFF404040);

        // Header
        Text header = Text.literal("Personal Preferences").formatted(Formatting.YELLOW);
        int headerX = panelX + (PANEL_WIDTH - this.textRenderer.getWidth(header)) / 2;
        context.drawText(this.textRenderer, header, headerX, panelY + 8, 0xFFFFFFFF, false);

        // Separator under header
        int sepY = panelY + 22;
        context.fill(panelX + PADDING, sepY, panelX + PANEL_WIDTH - PADDING, sepY + 1, 0xFF404040);

        // Row: "Preserve Expensive Gear" label + toggle chip
        int rowY = panelY + 38;
        Text rowLabel = Text.literal("Preserve Expensive Gear");
        context.drawText(this.textRenderer, rowLabel, panelX + PADDING, rowY + 5, 0xFFFFFFFF, false);

        // Chip (right-aligned in the row)
        chipX = panelX + PANEL_WIDTH - PADDING - CHIP_WIDTH;
        chipY = rowY;
        boolean loaded = SERVER_VALUE != null;
        boolean enabled = loaded && SERVER_VALUE;
        int chipBg;
        if (!loaded) {
            chipBg = 0xFF3A3A3A; // loading
        } else if (enabled) {
            chipBg = 0xFF2E7D32; // ON (green)
        } else {
            chipBg = 0xFF5A1A1A; // OFF (red)
        }
        context.fill(chipX, chipY, chipX + CHIP_WIDTH, chipY + CHIP_HEIGHT, chipBg);
        String chipText;
        if (!loaded) {
            chipText = "...";
        } else if (enabled) {
            chipText = "ON";
        } else {
            chipText = "OFF";
        }
        int chipTextWidth = this.textRenderer.getWidth(chipText);
        int chipTextX = chipX + (CHIP_WIDTH - chipTextWidth) / 2;
        context.drawText(this.textRenderer, Text.literal(chipText),
                chipTextX, chipY + 5, 0xFFFFFFFF, false);

        // Hint text below the row (wraps)
        int hintY = rowY + ROW_HEIGHT + 8;
        String[] hintLines = new String[] {
                "Bots will refuse to use enchanted gear or items made of gold,",
                "diamond, netherite, or turtle shell once durability drops below",
                "11% — or 3% in combat. They'll swap to a cheaper alternative,",
                "check a nearby chest, or craft a new one.",
                "",
                "Applies to every bot you own."
        };
        int hintCurrentY = hintY;
        for (String line : hintLines) {
            context.drawText(this.textRenderer, Text.literal(line).formatted(Formatting.GRAY),
                    panelX + PADDING, hintCurrentY, 0xFFB0B0B0, false);
            hintCurrentY += 10;
        }

        // Close hint at the bottom
        Text closeHint = Text.literal("Press ESC to close").formatted(Formatting.DARK_GRAY);
        int closeHintX = panelX + (PANEL_WIDTH - this.textRenderer.getWidth(closeHint)) / 2;
        context.drawText(this.textRenderer, closeHint,
                closeHintX, panelY + PANEL_HEIGHT - 16, 0xFF808080, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean isInside) {
        if (click.button() == 0) {
            double mouseX = click.x();
            double mouseY = click.y();
            if (mouseX >= chipX && mouseX < chipX + CHIP_WIDTH
                    && mouseY >= chipY && mouseY < chipY + CHIP_HEIGHT) {
                // Optimistic flip
                boolean current = SERVER_VALUE != null && SERVER_VALUE;
                boolean next = !current;
                SERVER_VALUE = next;
                ClientPlayNetworking.send(new UpdatePlayerPreservePayload(next));
                return true;
            }
        }
        return super.mouseClicked(click, isInside);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        } else {
            super.close();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
```

**API caveats — verify against the pattern in Step 1 and adjust:**

- If the `mouseClicked` signature in the reference file is the older `(double, double, int)` form rather than `(Click, boolean)`, use that. The file you pattern-matched in Step 1 is the source of truth for the version.
- If `renderBackground` has a different arity, match whichever is used in the reference.
- If `close()` is not overridable or uses a different method name, match the parent class form.
- `Text.literal(...).formatted(Formatting.GRAY)` should compile in 1.21.x; if it doesn't, use `Text.literal(...)` without formatting.

#### Step 3: Build verification

- [ ] Run `./gradlew build -x test`. This should now compile cleanly — Task 4's reference to `BotPlayerPreferencesScreen.setServerValue(...)` resolves.

#### Step 4: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerPreferencesScreen.java
git commit -m "feat: add BotPlayerPreferencesScreen for player-level toggles"
```

---

### Task 6: Add "Personal Preferences" button to BotControlScreen

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java)

#### Step 1: Read BotControlScreen to locate the footer button block and Rect type

- [ ] Read [BotControlScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java) around lines 120–130 (field declarations — look for `permissionsActionRect` and `lockBlocksRect`), lines 245–260 (where those Rects are computed in `init()` or layout), lines 515–525 (footer button rendering including the "Permissions Editor" button), and lines 900–950 (click handlers where `AdminPlayerSettingsScreen` is opened).

**Critical: BotControlScreen uses a project-internal `Rect` type (NOT `Rect2i`).** The existing pattern is:

- `private Rect permissionsActionRect;` — field type is `Rect`
- `.contains(mx, my)` — for hit-testing
- `.right()` — right edge X coordinate
- `.x`, `.y` — direct field access (not getters)

Pattern-match these exact calls in the existing code. Do not introduce `Rect2i`.

Note also:

- How `drawActionButton(...)` is called, and whether it takes an enabled flag
- The click dispatch pattern — it uses `rect.contains(mx, my)`, not manual `>=`/`<` bounds checking
- The existing `this.client.setScreen(new AdminPlayerSettingsScreen(this, selectedAlias));` call site — your new button should follow the same pattern but open `BotPlayerPreferencesScreen` with no bot alias
- Which method the `mouseClicked` override uses in this file — 1.21.x's `(Click click, boolean isInside)` or the older `(double, double, int)`. Use `click.x()`/`click.y()`/`click.button()` if the `Click` form is in use.

#### Step 2: Add a new `Rect` field for the Personal Preferences button

- [ ] Add a sibling field next to the existing `permissionsActionRect` declaration around line 121:

```java
private Rect personalPrefsActionRect;
```

- [ ] In the layout computation block around lines 245–260 (where `permissionsActionRect` and `lockBlocksRect` are assigned), add computation for the new rect. **Place the new button to the LEFT of `permissionsActionRect`** because `lockBlocksRect` already occupies the slot to the right. Adjust to match the exact `Rect` constructor/factory used elsewhere in the file:

```java
// After permissionsActionRect is computed, before (or after) lockBlocksRect:
int ppWidth = permissionsActionRect.width;
int ppHeight = permissionsActionRect.height;
int ppY = permissionsActionRect.y;
int ppX = permissionsActionRect.x - ppWidth - 8; // 8px gap to the left, matches existing inter-button spacing
personalPrefsActionRect = new Rect(ppX, ppY, ppWidth, ppHeight);
```

If the project's `Rect` class uses different field/constructor names (e.g. `.w`/`.h` instead of `.width`/`.height`), match whatever the existing code uses. Read the `Rect` class definition if in doubt — grep for `class Rect` or `record Rect` in `GraphicalUserInterface/`.

#### Step 3: Render the button

- [ ] In the render block (same area as the existing `drawActionButton(...permissionsActionRect...)` call near line 517), add an identical call for the new button:

```java
drawActionButton(context, personalPrefsActionRect,
        "Personal Preferences",
        false,
        true, // always enabled (no bot selection required)
        mouseX, mouseY);
```

**Important:** unlike the Permissions Editor button, this button is **always enabled** regardless of whether a bot is selected — the preferences are per-player, not per-bot.

#### Step 4: Handle the click

- [ ] Find the click dispatch for `permissionsActionRect` around line 909 or 942. Add a parallel dispatch for `personalPrefsActionRect` immediately above it (so it matches the rendering order, left-to-right). Use the project's `rect.contains(mx, my)` pattern — do NOT write manual bounds checks:

```java
if (button == 0
        && personalPrefsActionRect != null
        && personalPrefsActionRect.contains(mouseX, mouseY)) {
    if (this.client != null) {
        this.client.setScreen(new BotPlayerPreferencesScreen(this));
    }
    return true;
}
```

**If the `mouseClicked` override uses the 1.21.x `Click`-based signature**, replace `mouseX`/`mouseY`/`button` with `click.x()`, `click.y()`, `click.button()` respectively. Verify by reading the existing override signature in the file.

`BotPlayerPreferencesScreen` is in the same package, so no import is needed.

#### Step 5: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 6: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java
git commit -m "feat: add Personal Preferences button to BotControlScreen footer"
```

---

## Chunk 1 Verification (Manual, In-Game)

After Task 6 commits successfully:

- [ ] Run `./gradlew build -x test` one final time. Confirm clean build.
- [ ] **Manual test 1 (button visibility):** Launch the mod in the dev env. Open `BotControlScreen` as a non-OP player. Confirm the "Personal Preferences" button appears in the footer next to the existing "Permissions Editor" button.
- [ ] **Manual test 2 (button click, initial state):** Click "Personal Preferences". The new screen should open, briefly show "..." for the toggle state, then resolve to "OFF" (the default). If it stays on "..." for more than 2 seconds, the S2C reply isn't being delivered — check that `PlayerPreserveStatePayload` is registered in the `playS2C` registry and that the client receiver is wired.
- [ ] **Manual test 3 (toggle flip):** Click the OFF chip — it should immediately show ON (optimistic update). Close the screen (ESC), reopen it — should still show ON. The value was persisted to `ManualConfig`.
- [ ] **Manual test 4 (persistence across restart):** With the toggle ON, stop the server, restart it, rejoin, reopen the screen. Should still show ON. If it shows OFF, the save path is wrong — check that `Frens.CONFIG.save()` is called inside the `UpdatePlayerPreservePayload` server handler.
- [ ] **Manual test 5 (no behavior change):** Spawn a bot, give it a diamond pickaxe with 5% durability, command it to mine cobblestone. Confirm it still uses the diamond pickaxe. (The policy service exists but no selection site calls it yet, so bot behavior is unchanged.)
- [ ] **Manual test 6 (two players):** If possible, log in as a second (non-OP) player, open the preferences screen as them, verify they see their OWN value (not player 1's value). Flip it ON for player 2. Go back to player 1 — their value should still be whatever player 1 set.

If any test fails, fix and re-verify before proceeding to Chunk 2.

---

## Chunk 1 Summary

After Chunk 1, a player-facing preferences screen exists, the toggle flips and persists, and the policy service is defined and callable but unused. **Six commits total** (one per task). No changes to bot behavior yet — that starts in Chunk 2.

---

## Chunk 2: Phase 2 — Core Selection Site Hooks + Fallback Stub

**Goal of this chunk:** Mining tool selection, armor equip, and combat weapon/shield selection all filter out preserved-below-threshold items. A stub `DurabilityFallbackService` exists with only the inventory re-scan step implemented — chest and craft come in Chunk 3. Selection sites call the stub when they find zero candidates.

**At the end of Chunk 2, this must be true:**

- `./gradlew build -x test` passes.
- With toggle OFF (default), all bot behavior is unchanged.
- With toggle ON and a diamond pickaxe at 5% in hand plus an iron pickaxe at 80% in inventory, a bot commanded to mine swaps to the iron pickaxe.
- With toggle ON, armor equip similarly prefers non-preserved-below-threshold stacks.
- With toggle ON, combat weapon selection similarly avoids preserved-below-threshold swords/shields.
- With toggle ON and no alternative ANYWHERE, the bot does nothing special yet — just returns "no tool" and bails via its existing path. (The full fallback comes in Chunk 3.)

---

### Task 7: Create DurabilityFallbackService stub

**Files:**
- Create: [src/main/java/net/wcfcarolina13/GameAI/services/DurabilityFallbackService.java](src/main/java/net/wcfcarolina13/GameAI/services/DurabilityFallbackService.java)

This is a stub — only the `GearCategory` enum, the `requestRefresh` entry point, the cooldown map, and the Phase 2 inventory-rescan step. Chest retrieval, crafting, and the dedicated executor come in Chunk 3. The stub runs its single step synchronously on the caller's thread (safe because the inventory rescan is read-only until it schedules a swap via `server.execute`).

#### Step 1: Create the file

- [ ] Create [DurabilityFallbackService.java](src/main/java/net/wcfcarolina13/GameAI/services/DurabilityFallbackService.java) with this content:

```java
package net.wcfcarolina13.GameAI.services;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.ServerCommandSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the fallback chain when a selection site is blocked by
 * {@link DurabilityPolicyService}. Phase 2 implements only the inventory
 * re-scan step; chest retrieval, crafting, and the dedicated executor
 * arrive in Phase 3.
 *
 * <p>Call {@link #requestRefresh(ServerPlayerEntity, GearCategory, ServerCommandSource)}
 * from any selection site that found zero compliant candidates.
 *
 * <p>Per-bot, per-category cooldown of 20s prevents thrash loops. Cleared
 * on bot death, bot removal, server stop, and on toggle-on-flip.
 */
public final class DurabilityFallbackService {

    private static final Logger LOGGER = LoggerFactory.getLogger("durability-fallback");

    private DurabilityFallbackService() {}

    // ------------------------------------------------------------------
    // Categories
    // ------------------------------------------------------------------

    public enum GearCategory {
        PICKAXE,
        AXE,
        SHOVEL,
        HOE,
        SWORD,
        MACE,
        SHIELD,
        BOW,
        CROSSBOW,
        TRIDENT,
        FISHING_ROD,
        HELMET,
        CHESTPLATE,
        LEGGINGS,
        BOOTS,
        ELYTRA,
        SHEARS
    }

    // ------------------------------------------------------------------
    // Cooldown state
    // ------------------------------------------------------------------

    static final long COOLDOWN_MS = 20_000L;

    // bot UUID → category → last attempt timestamp (ms since epoch)
    private static final Map<UUID, EnumMap<GearCategory, Long>> LAST_ATTEMPT =
            new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Main entry point. Selection sites call this when their filter leaves
     * no compliant candidates. Fast return if the cooldown hasn't expired.
     */
    public static void requestRefresh(ServerPlayerEntity bot,
                                      GearCategory category,
                                      ServerCommandSource source) {
        if (bot == null || category == null || bot.isRemoved()) {
            return;
        }
        UUID botId = bot.getUuid();
        if (botId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        EnumMap<GearCategory, Long> perCat = LAST_ATTEMPT.computeIfAbsent(
                botId, k -> new EnumMap<>(GearCategory.class));

        synchronized (perCat) {
            Long last = perCat.get(category);
            if (last != null && now - last < COOLDOWN_MS) {
                return; // still in cooldown
            }
            perCat.put(category, now);
        }

        // Phase 2: inventory re-scan only. Phase 3 adds chest + craft steps.
        boolean swapped = tryInventoryRescan(bot, category);
        if (!swapped) {
            LOGGER.debug("Fallback stub: no inventory alternative for {} category={}",
                    bot.getName().getString(), category);
        }
    }

    /** Clears the cooldown for a single bot (call on bot death or removal). */
    public static void clearCooldowns(UUID botId) {
        if (botId != null) {
            LAST_ATTEMPT.remove(botId);
        }
    }

    /** Clears all cooldowns (call on server stop or global toggle-on flip). */
    public static void clearAllCooldowns() {
        LAST_ATTEMPT.clear();
    }

    /** Clears cooldowns for all bots owned by a specific player (toggle-on flip). */
    public static void clearCooldownsForOwner(UUID ownerUuid) {
        if (ownerUuid == null) return;
        // Iterate registered bots; for each bot whose owner is ownerUuid, drop their entry.
        // We rely on BotRegistry for the active bot list. If BotRegistry is not importable
        // here, this method becomes a no-op and the cooldown naturally expires within 20s.
        try {
            for (UUID botId : BotRegistry.getAllBotIds()) {
                ServerPlayerEntity bot = BotRegistry.getBotByUuid(botId);
                if (bot == null) continue;
                UUID botOwner = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
                if (ownerUuid.equals(botOwner)) {
                    LAST_ATTEMPT.remove(botId);
                }
            }
        } catch (Throwable t) {
            // If BotRegistry API differs, the cooldown expires naturally — not critical.
            LOGGER.debug("clearCooldownsForOwner: BotRegistry lookup failed: {}", t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Phase 2: inventory re-scan step
    // ------------------------------------------------------------------

    /**
     * Scans all 36 inventory slots for a stack matching the given category
     * that passes {@code !shouldAvoid(...)}. If found, schedules a swap to
     * hotbar (or {@code equipStack} for armor) via {@code server.execute(...)}.
     *
     * @return true if a compliant alternative was found and scheduled
     */
    private static boolean tryInventoryRescan(ServerPlayerEntity bot, GearCategory category) {
        net.minecraft.entity.player.PlayerInventory inv = bot.getInventory();
        int bestSlot = -1;
        double bestRatio = -1.0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (!matchesCategory(stack, category)) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            double ratio = DurabilityPolicyService.durabilityRatio(stack);
            if (ratio > bestRatio) {
                bestRatio = ratio;
                bestSlot = i;
            }
        }

        if (bestSlot < 0) {
            return false;
        }

        final int slotToUse = bestSlot;
        MinecraftServer server = bot.getEntityWorld().getServer();
        if (server == null) {
            return false;
        }

        server.execute(() -> {
            ItemStack chosen = inv.getStack(slotToUse);
            if (chosen.isEmpty()) return;

            if (isArmorCategory(category)) {
                EquipmentSlot slot = armorSlotForCategory(category);
                if (slot == null) return;
                ItemStack displaced = bot.getEquippedStack(slot);
                bot.equipStack(slot, chosen.copy());
                inv.setStack(slotToUse, displaced.copy());
                inv.markDirty();
            } else {
                // Non-armor: swap to hotbar
                int hotbarTarget = inv.getSelectedSlot();
                if (slotToUse >= 9) {
                    // Find an empty hotbar slot if possible
                    for (int h = 0; h < 9; h++) {
                        if (inv.getStack(h).isEmpty()) {
                            hotbarTarget = h;
                            break;
                        }
                    }
                    ItemStack from = inv.getStack(slotToUse);
                    ItemStack to = inv.getStack(hotbarTarget);
                    inv.setStack(slotToUse, to);
                    inv.setStack(hotbarTarget, from);
                }
                inv.setSelectedSlot(hotbarTarget);
                inv.markDirty();
            }
        });
        return true;
    }

    /**
     * Loose category matcher: returns true if the stack looks like the
     * requested category. Uses item identity for known items and
     * translation-key substring matching for the material-tiered families.
     */
    private static boolean matchesCategory(ItemStack stack, GearCategory category) {
        String key = stack.getItem().getTranslationKey().toLowerCase(java.util.Locale.ROOT);
        return switch (category) {
            case PICKAXE     -> key.endsWith("_pickaxe") || key.equals("item.minecraft.pickaxe");
            case AXE         -> key.endsWith("_axe") && !key.endsWith("_pickaxe");
            case SHOVEL      -> key.endsWith("_shovel");
            case HOE         -> key.endsWith("_hoe");
            case SWORD       -> key.endsWith("_sword");
            case MACE        -> key.endsWith("mace");
            case SHIELD      -> stack.isOf(net.minecraft.item.Items.SHIELD);
            case BOW         -> stack.isOf(net.minecraft.item.Items.BOW);
            case CROSSBOW    -> stack.isOf(net.minecraft.item.Items.CROSSBOW);
            case TRIDENT     -> stack.isOf(net.minecraft.item.Items.TRIDENT);
            case FISHING_ROD -> stack.isOf(net.minecraft.item.Items.FISHING_ROD);
            case HELMET      -> key.endsWith("_helmet") || stack.isOf(net.minecraft.item.Items.TURTLE_HELMET);
            case CHESTPLATE  -> key.endsWith("_chestplate");
            case LEGGINGS    -> key.endsWith("_leggings");
            case BOOTS       -> key.endsWith("_boots");
            case ELYTRA      -> stack.isOf(net.minecraft.item.Items.ELYTRA);
            case SHEARS      -> stack.isOf(net.minecraft.item.Items.SHEARS);
        };
    }

    private static boolean isArmorCategory(GearCategory category) {
        return category == GearCategory.HELMET
                || category == GearCategory.CHESTPLATE
                || category == GearCategory.LEGGINGS
                || category == GearCategory.BOOTS
                || category == GearCategory.ELYTRA;
    }

    private static EquipmentSlot armorSlotForCategory(GearCategory category) {
        return switch (category) {
            case HELMET     -> EquipmentSlot.HEAD;
            case CHESTPLATE, ELYTRA -> EquipmentSlot.CHEST;
            case LEGGINGS   -> EquipmentSlot.LEGS;
            case BOOTS      -> EquipmentSlot.FEET;
            default -> null;
        };
    }
}
```

**Notes on the code above:**

- `BotRegistry.getAllBotIds()` and `BotRegistry.getBotByUuid(...)` are assumed method names based on the exploration. If the actual API differs, grep `BotRegistry` and adjust — the `clearCooldownsForOwner` method is wrapped in try/catch so an API mismatch degrades gracefully to "no-op".
- The `matchesCategory` translation-key substring matching is pragmatic — it catches all vanilla tools including modded variants. If a specific match edge case turns up during in-game testing, tighten the predicate.
- `inv.getSelectedSlot()` / `inv.setSelectedSlot(n)` is the 1.21.x API. If the existing code in `ToolSelector.java` uses `bot.getInventory().selectedSlot = n;` directly (field access), match that.

#### Step 2: Hook bot-removal events to clear cooldowns

- [ ] Grep for `BotRegistry` and find where bots are registered and unregistered (likely in `BotRegistry.java` or `BotEventHandler.java`). You're looking for the method that gets called when a bot is removed from the world (despawn, kick, death).

- [ ] Add one line to that removal path:

```java
DurabilityFallbackService.clearCooldowns(botUuid);
```

If there are multiple removal sites, add the line in each. If you cannot confidently identify the removal path, skip this in Phase 2 — the 20s natural expiry is good enough until Chunk 3 where we'll also add the bot-death hook.

#### Step 3: Register global shutdown hook in Frens.java

- [ ] Open [Frens.java](src/main/java/net/wcfcarolina13/Frens.java) and find the `SERVER_STOPPING` block where other services are shut down (e.g., `MiningTool.shutdownExecutors()`).

- [ ] Add one line to that block:

```java
net.wcfcarolina13.GameAI.services.DurabilityFallbackService.clearAllCooldowns();
```

(The actual executor shutdown call — `shutdownExecutors()` — will be added in Chunk 3 when the executor is created.)

#### Step 3b: Wire toggle OFF→ON cooldown clear in the UpdatePlayerPreservePayload handler

The spec (Data Model → Fallback cooldown state → Lifecycle rules) requires cooldowns to be cleared when a player flips their preference from OFF→ON. This must be wired into the Chunk 1 Task 4 handler.

- [ ] Find the `ServerPlayNetworking.registerGlobalReceiver(UpdatePlayerPreservePayload.ID, ...)` block created in Chunk 1 Task 4 Step 3.

- [ ] Inside its `context.server().execute(() -> { ... })` lambda, immediately after `Frens.CONFIG.save();`, add:

```java
// If the player just flipped OFF → ON, clear any stale cooldowns for their bots
// so the next selection call gets a fresh fallback attempt.
if (payload.enabled()) {
    net.wcfcarolina13.GameAI.services.DurabilityFallbackService.clearCooldownsForOwner(senderUuid);
}
```

The lambda should now read:

```java
context.server().execute(() -> {
    if (Frens.CONFIG != null) {
        Frens.CONFIG.setPreserveExpensiveGear(senderUuid, payload.enabled());
        Frens.CONFIG.save();
    }
    if (payload.enabled()) {
        net.wcfcarolina13.GameAI.services.DurabilityFallbackService.clearCooldownsForOwner(senderUuid);
    }
});
```

#### Step 4: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

**Common failures and fixes:**

- `BotRegistry.getAllBotIds` doesn't exist → the call is already wrapped in try/catch and will degrade to no-op silently. Not a blocker.
- `inv.getSelectedSlot()` doesn't exist → try `inv.selectedSlot` (field access) or `bot.getInventory().getSelectedSlot()`.

#### Step 5: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/DurabilityFallbackService.java src/main/java/net/wcfcarolina13/Frens.java
git commit -m "feat: add DurabilityFallbackService stub with inventory rescan"
```

---

### Task 8: Hook ToolSelector

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/PlayerUtils/ToolSelector.java](src/main/java/net/wcfcarolina13/PlayerUtils/ToolSelector.java)

#### Step 1: Read the existing selectBestToolForBlock method

- [ ] Read [ToolSelector.java](src/main/java/net/wcfcarolina13/PlayerUtils/ToolSelector.java) in full (it's a small file). Locate:
  - The `selectBestToolForBlock(ServerPlayerEntity bot, BlockState blockState)` method
  - The hotbar scan loop (slots 0–8)
  - The main inventory scan loop (slots 9–35)
  - The `findShears(bot)` branch used for leaves

#### Step 2: Add an import

- [ ] Add at the top of the file:

```java
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
```

#### Step 3: Filter the hotbar scan loop

- [ ] In the hotbar scan loop (`for (int i = 0; i < hotbarItems.size(); i++)`), add a policy check immediately after the existing `if (item.isEmpty() || isWeaponOnlyItem(item)) continue;` line:

```java
if (DurabilityPolicyService.shouldAvoid(bot, item)) continue;
```

#### Step 4: Filter the main inventory scan loop

- [ ] In the main inventory scan loop (`for (int i = 9; i < 36; i++)`), add the same policy check immediately after its existing `if (item.isEmpty() || isWeaponOnlyItem(item)) continue;` line:

```java
if (DurabilityPolicyService.shouldAvoid(bot, item)) continue;
```

#### Step 5: Filter the leaves/shears branch

- [ ] Locate the block near the top of the method that handles leaves:

```java
if (blockState != null && blockState.isIn(BlockTags.LEAVES)) {
    ItemStack shears = findShears(bot);
    if (!shears.isEmpty()) {
        return shears;
    }
    return hotBarUtils.getSelectedHotbarItemStack(bot);
}
```

- [ ] Wrap the shears return with a policy check:

```java
if (blockState != null && blockState.isIn(BlockTags.LEAVES)) {
    ItemStack shears = findShears(bot);
    if (!shears.isEmpty() && !DurabilityPolicyService.shouldAvoid(bot, shears)) {
        return shears;
    }
    if (!shears.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, shears)) {
        // Enchanted shears below threshold — request fallback refresh, then fall through.
        DurabilityFallbackService.requestRefresh(
                bot, DurabilityFallbackService.GearCategory.SHEARS, null);
    }
    return hotBarUtils.getSelectedHotbarItemStack(bot);
}
```

#### Step 6: Trigger fallback when both loops leave `highestSpeed <= 1.0f`

- [ ] Find the tail of the method where the fallback to selected/harmless hotbar item happens:

```java
if (highestSpeed <= 1.0f) {
    ItemStack selected = hotBarUtils.getSelectedHotbarItemStack(bot);
    if (!selected.isEmpty() && !isWeaponOnlyItem(selected)) {
        return selected;
    }
    return findHarmlessHotbarFallback(bot);
}
```

- [ ] Before this block returns, request a fallback refresh if the filter is the reason nothing was found. A simple heuristic: if the bot's main hand is a preserved-below-threshold item AND the loops found nothing better, request fallback for the matching category.

```java
if (highestSpeed <= 1.0f) {
    // If the reason nothing was found is that a preserved tool is below threshold,
    // request fallback refresh for the appropriate category.
    ItemStack held = hotBarUtils.getSelectedHotbarItemStack(bot);
    if (!held.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, held)) {
        DurabilityFallbackService.GearCategory cat = heldCategoryGuess(held);
        if (cat != null) {
            DurabilityFallbackService.requestRefresh(bot, cat, null);
        }
    }

    ItemStack selected = hotBarUtils.getSelectedHotbarItemStack(bot);
    if (!selected.isEmpty() && !isWeaponOnlyItem(selected)) {
        return selected;
    }
    return findHarmlessHotbarFallback(bot);
}
```

- [ ] Add a small helper method at the end of the class:

```java
private static DurabilityFallbackService.GearCategory heldCategoryGuess(ItemStack stack) {
    String key = stack.getItem().getTranslationKey().toLowerCase(java.util.Locale.ROOT);
    if (key.endsWith("_pickaxe")) return DurabilityFallbackService.GearCategory.PICKAXE;
    if (key.endsWith("_shovel"))  return DurabilityFallbackService.GearCategory.SHOVEL;
    if (key.endsWith("_hoe"))     return DurabilityFallbackService.GearCategory.HOE;
    if (key.endsWith("_axe"))     return DurabilityFallbackService.GearCategory.AXE;
    return null;
}
```

#### Step 7: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 8: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/PlayerUtils/ToolSelector.java
git commit -m "feat: hook ToolSelector with DurabilityPolicyService filter"
```

---

### Task 9: Hook armorUtils

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/PlayerUtils/armorUtils.java](src/main/java/net/wcfcarolina13/PlayerUtils/armorUtils.java)

#### Step 1: Read the existing file

- [ ] Read [armorUtils.java](src/main/java/net/wcfcarolina13/PlayerUtils/armorUtils.java). Locate:
  - `autoEquipArmor(ServerPlayerEntity bot)` — the main entry point
  - `findBestArmorSlot(PlayerInventory inventory, EquipmentSlot slot)` — the inner loop
  - `isBetterArmor(ItemStack newArmor, ItemStack currentArmor, EquipmentSlot slot)` — the comparator

#### Step 2: Add the import

- [ ] At the top of the file:

```java
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
```

#### Step 3: Change findBestArmorSlot to accept the bot and filter

- [ ] The existing signature is `private static int findBestArmorSlot(PlayerInventory inventory, EquipmentSlot slot)`. We need the bot to call `shouldAvoid(bot, stack)`. Change the signature to take the bot:

```java
private static int findBestArmorSlot(ServerPlayerEntity bot, PlayerInventory inventory, EquipmentSlot slot) {
    int bestArmorSlot = -1;
    double bestScore = 0.0;

    for (int slotIndex = 0; slotIndex < PlayerInventory.MAIN_SIZE; slotIndex++) {
        ItemStack item = inventory.getStack(slotIndex);
        if (!item.isEmpty() && isArmorForSlot(item, slot)) {
            if (DurabilityPolicyService.shouldAvoid(bot, item)) {
                continue; // filtered: preserved material or enchant below threshold
            }
            double score = getArmorScore(item, slot);
            if (score > bestScore) {
                bestScore = score;
                bestArmorSlot = slotIndex;
            }
        }
    }
    return bestArmorSlot;
}
```

- [ ] Update the single caller of `findBestArmorSlot` inside `autoEquipArmor` to pass the bot:

```java
int bestArmorSlot = findBestArmorSlot(bot, inventory, slot);
```

#### Step 4: Request fallback if no compliant armor was found AND the current armor is preserved-below-threshold

- [ ] In `autoEquipArmor`, inside the per-slot loop, after the existing `if (!bestArmor.isEmpty() && ...)` equip block, add:

```java
// If the filter is the reason nothing was equipped AND the currently equipped armor
// is preserved-below-threshold, request a fallback refresh.
if (bestArmor.isEmpty()
        && !equippedArmor.isEmpty()
        && DurabilityPolicyService.shouldAvoid(bot, equippedArmor)) {
    DurabilityFallbackService.GearCategory cat = switch (slot) {
        case HEAD -> DurabilityFallbackService.GearCategory.HELMET;
        case CHEST -> DurabilityFallbackService.GearCategory.CHESTPLATE;
        case LEGS -> DurabilityFallbackService.GearCategory.LEGGINGS;
        case FEET -> DurabilityFallbackService.GearCategory.BOOTS;
        default -> null;
    };
    if (cat != null) {
        DurabilityFallbackService.requestRefresh(bot, cat, null);
    }
}
```

**Important:** per the spec, armor is equip-time only. We do NOT strip already-equipped preserved armor mid-game just because it dropped below threshold. The fallback refresh only fires when there's no equipped armor at all (or an empty slot) AND the worn item would have been filtered anyway. In practice this means the fallback triggers on a freshly-spawned bot or one that just had its armor broken.

#### Step 5: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 6: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/PlayerUtils/armorUtils.java
git commit -m "feat: hook armorUtils with DurabilityPolicyService filter"
```

---

### Task 10: Hook CombatInventoryManager

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/PlayerUtils/CombatInventoryManager.java](src/main/java/net/wcfcarolina13/PlayerUtils/CombatInventoryManager.java)

#### Step 1: Read the existing file

- [ ] Read [CombatInventoryManager.java](src/main/java/net/wcfcarolina13/PlayerUtils/CombatInventoryManager.java). Locate:
  - `ensureCombatLoadout(ServerPlayerEntity bot)` — entry point
  - `ensureBestWeaponAccessible(bot)` — weapon slot selection
  - `findBestWeaponSlot(PlayerInventory inventory)` — the inner weapon scanner
  - `evaluateWeapon(ItemStack stack)` — the scoring function
  - `ensureOffhandShield(bot)` — shield selection

#### Step 2: Add imports

- [ ] At the top of the file:

```java
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
```

#### Step 3: Change findBestWeaponSlot to accept the bot and filter

- [ ] Existing signature is `private static OptionalInt findBestWeaponSlot(PlayerInventory inventory)`. Change to take the bot:

```java
private static OptionalInt findBestWeaponSlot(ServerPlayerEntity bot, PlayerInventory inventory) {
    int bestSlot = -1;
    double bestScore = Double.NEGATIVE_INFINITY;

    for (int i = 0; i < inventory.size(); i++) {
        ItemStack stack = inventory.getStack(i);
        if (stack.isEmpty()) continue;
        if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
        double score = evaluateWeapon(stack);
        if (score > bestScore) {
            bestScore = score;
            bestSlot = i;
        }
    }
    return bestSlot < 0 ? OptionalInt.empty() : OptionalInt.of(bestSlot);
}
```

If the existing body is different, preserve its structure and just add the `if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;` line inside the loop.

- [ ] Update the single caller in `ensureBestWeaponAccessible` to pass the bot:

```java
OptionalInt bestWeaponSlot = findBestWeaponSlot(bot, inventory);
```

#### Step 4: Trigger fallback when no weapon is found

- [ ] In `ensureBestWeaponAccessible`, after the `if (bestWeaponSlot.isEmpty()) return;` bail, detect whether the bail was caused by the filter:

```java
if (bestWeaponSlot.isEmpty()) {
    // If the currently-held weapon is preserved-below-threshold, fallback refresh for SWORD category.
    ItemStack held = bot.getMainHandStack();
    if (!held.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, held)) {
        DurabilityFallbackService.requestRefresh(
                bot, DurabilityFallbackService.GearCategory.SWORD, null);
    }
    return;
}
```

#### Step 5: Hook ensureOffhandShield similarly

- [ ] Find `ensureOffhandShield(ServerPlayerEntity bot)`. Inside its body, before any shield is equipped, add:

```java
ItemStack currentOffhand = bot.getOffHandStack();
if (!currentOffhand.isEmpty()
        && currentOffhand.isOf(net.minecraft.item.Items.SHIELD)
        && DurabilityPolicyService.shouldAvoid(bot, currentOffhand)) {
    // Current shield is preserved below threshold — request fallback.
    DurabilityFallbackService.requestRefresh(
            bot, DurabilityFallbackService.GearCategory.SHIELD, null);
    return;
}
```

If the method scans the inventory for a shield before equipping, add a `shouldAvoid` filter inside that scan loop too so the bot doesn't pull a bad shield from storage.

#### Step 6: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 7: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/PlayerUtils/CombatInventoryManager.java
git commit -m "feat: hook CombatInventoryManager with DurabilityPolicyService filter"
```

---

## Chunk 2 Verification (Manual, In-Game)

After Task 10 commits successfully:

- [ ] Run `./gradlew build -x test`. Clean build.
- [ ] **Manual test 1 (baseline OFF):** With toggle OFF, spawn a bot, give it a diamond pickaxe at 5% durability, command mining. Bot uses the diamond pickaxe normally.
- [ ] **Manual test 2 (swap with alternative):** With toggle ON, same setup + add an iron pickaxe at 80% durability to inventory. Command mining. Bot should swap to iron and use it.
- [ ] **Manual test 3 (no alternative, no fallback yet):** With toggle ON, only the diamond pickaxe at 5%. Command mining. Bot bails the task (no fallback chain yet). The swap fails silently — this is expected for Phase 2.
- [ ] **Manual test 4 (combat threshold):** With toggle ON, diamond sword at 4% durability, spawn a zombie near the bot. Bot uses the sword (4% ≥ 3%). Reduce to 2%. Bot should refuse to use it. Add an iron sword to inventory — bot should swap.
- [ ] **Manual test 5 (armor equip filter):** With toggle ON, put a diamond chestplate at 5% in the bot's inventory plus an iron chestplate at 80%. Trigger auto-equip (spawn near a zombie). Bot should equip the iron chestplate, not the diamond.
- [ ] **Manual test 6 (enchanted cheap item):** With toggle ON, give the bot an Unbreaking III iron pickaxe at 5%. Command mining. Bot should refuse the enchanted iron (preserved by enchantment) — bails or swaps.

If any test fails, fix and re-verify before proceeding to Chunk 3.

---

## Chunk 2 Summary

After Chunk 2, three core selection sites filter preserved-below-threshold items. Bots swap to cheaper alternatives when available. When no alternative is available, bots bail gracefully via existing no-tool paths — the full chest/craft fallback comes in Chunk 3. **Four commits total.**

---

## Chunk 3: Phase 3 — Full Fallback Chain + Ranged/Utility Hooks

**Goal of this chunk:** The fallback chain completes — bots walk to nearby chests to retrieve replacements, craft new tools at a table if materials are available, and speak up via overhead dialogue when all options fail. Ranged weapons, fishing rods, shears in wool harvesting, and elytra all respect the policy.

**At the end of Chunk 3, this must be true:**

- `./gradlew build -x test` passes.
- With toggle ON and a diamond pickaxe at 5% but no inventory alternative, the bot walks to a registered chest containing an iron pickaxe and withdraws it.
- With toggle ON and neither an inventory nor chest alternative but wood in inventory, the bot crafts a wooden pickaxe at a nearby table.
- With toggle ON and no options anywhere, the bot stands down (no crash, no thrash), cooldown applies.
- Ranged weapon selection, fishing rod, shears (WoolSkill), and elytra all filter.
- The dedicated `fallbackExecutor` is declared and shut down in `SERVER_STOPPING`.

---

### Task 11: Fill in DurabilityFallbackService — executor, chest, craft

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/GameAI/services/DurabilityFallbackService.java](src/main/java/net/wcfcarolina13/GameAI/services/DurabilityFallbackService.java)
- Modify: [src/main/java/net/wcfcarolina13/Frens.java](src/main/java/net/wcfcarolina13/Frens.java)

#### Step 1: Add the executor

- [ ] Add this field and helper method to `DurabilityFallbackService`:

```java
// ------------------------------------------------------------------
// Dedicated executor
// ------------------------------------------------------------------

private static final java.util.concurrent.ExecutorService fallbackExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "frens-durability-fallback");
            t.setDaemon(true);
            return t;
        });

public static void shutdownExecutors() {
    try {
        fallbackExecutor.shutdown();
        if (!fallbackExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
            fallbackExecutor.shutdownNow();
        }
    } catch (InterruptedException e) {
        fallbackExecutor.shutdownNow();
        Thread.currentThread().interrupt();
    }
    clearAllCooldowns();
}
```

#### Step 2: Refactor requestRefresh to dispatch to the executor

- [ ] Change the end of `requestRefresh` — instead of calling `tryInventoryRescan` synchronously, dispatch the full chain to the executor:

```java
public static void requestRefresh(ServerPlayerEntity bot,
                                  GearCategory category,
                                  ServerCommandSource source) {
    if (bot == null || category == null || bot.isRemoved()) {
        return;
    }
    UUID botId = bot.getUuid();
    if (botId == null) {
        return;
    }

    long now = System.currentTimeMillis();
    EnumMap<GearCategory, Long> perCat = LAST_ATTEMPT.computeIfAbsent(
            botId, k -> new EnumMap<>(GearCategory.class));
    synchronized (perCat) {
        Long last = perCat.get(category);
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        perCat.put(category, now);
    }

    fallbackExecutor.submit(() -> runFallbackChain(bot, category, source));
}

private static void runFallbackChain(ServerPlayerEntity bot,
                                     GearCategory category,
                                     ServerCommandSource source) {
    if (bot == null || bot.isRemoved()) return;

    // Step 1: inventory re-scan
    if (tryInventoryRescan(bot, category)) {
        LOGGER.debug("Fallback: swapped from inventory for {} category={}",
                bot.getName().getString(), category);
        return;
    }

    // Step 2: chest retrieval
    if (tryChestRetrieval(bot, category, source)) {
        LOGGER.debug("Fallback: retrieved from chest for {} category={}",
                bot.getName().getString(), category);
        return;
    }

    // Step 3: crafting fallback (tool categories only)
    if (tryCraftingFallback(bot, category, source)) {
        LOGGER.debug("Fallback: crafted replacement for {} category={}",
                bot.getName().getString(), category);
        return;
    }

    // Step 4: stand down
    LOGGER.info("Fallback: no replacement found for {} category={} — standing down",
            bot.getName().getString(), category);
    CompanionOverheadDialogueService.tryShowGearNoReplacement(bot);
}
```

**Note:** `CompanionOverheadDialogueService.tryShowGearNoReplacement(bot)` is implemented in Chunk 4. For Chunk 3, the method doesn't yet exist. Either:

- Create a no-op stub in `CompanionOverheadDialogueService` now (just an empty method body with the right signature) and flesh it out in Chunk 4. **Recommended.**
- Or comment out the call until Chunk 4 lands.

Choose the stub approach: add an empty stub now (1-line task at the top of Chunk 4 replaces it with the real body).

#### Step 3: Grep for the exact `retrieveToolFromChests` signature

Before writing the chest retrieval code, pin the exact API surface by reading an existing caller.

- [ ] Grep for callers of `retrieveToolFromChests`:

Use Grep: pattern `retrieveToolFromChests`, type `java`, output_mode `content`, `-n` true.

- [ ] Open the first non-service file that calls it (likely `WoodcutSkill.java` or similar). Record the exact signature:
  - Parameter count and order
  - The exact type of `snapshotFilter` (e.g., `Predicate<BotChestRegistryService.ItemSnapshot>`)
  - The exact type of `stackPredicate`
  - The exact type of `comparator`
  - The return type

- [ ] If the signature differs from the 7-argument form shown in Step 4 below, adjust the code in Step 4 to match. Copy the exact import line for `ItemSnapshot` from the reference caller.

#### Step 4: Implement tryChestRetrieval

- [ ] Add this private method to `DurabilityFallbackService`:

```java
private static boolean tryChestRetrieval(ServerPlayerEntity bot,
                                         GearCategory category,
                                         ServerCommandSource source) {
    if (!(bot.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld world)) {
        return false;
    }

    // Build a stack predicate: matches category AND not preserved-below-threshold
    // AND has enough headroom (≥ 25% durability).
    java.util.function.Predicate<ItemStack> stackPredicate = stack -> {
        if (!matchesCategory(stack, category)) return false;
        if (DurabilityPolicyService.shouldAvoid(bot, stack)) return false;
        return DurabilityPolicyService.durabilityRatio(stack) >= 0.25;
    };

    // Build a snapshot predicate: loose pre-filter to avoid walking to chests
    // that clearly don't contain anything matching. Accept any snapshot; the
    // stack predicate does the real filtering.
    java.util.function.Predicate<net.wcfcarolina13.GameAI.services.BotChestRegistryService.ItemSnapshot> snapshotFilter =
            snap -> true;

    // Sort chest candidates by distance (default comparator).
    java.util.Comparator<net.wcfcarolina13.GameAI.services.BotChestRegistryService.ItemSnapshot> comparator =
            (a, b) -> 0; // rely on ToolProvisionService's default distance ordering

    final int MAX_RANGE = 48;

    try {
        return ToolProvisionService.retrieveToolFromChests(
                bot, world, source, snapshotFilter, stackPredicate, comparator, MAX_RANGE);
    } catch (Throwable t) {
        LOGGER.debug("tryChestRetrieval failed: {}", t.getMessage());
        return false;
    }
}
```

**API reference:** `ToolProvisionService.retrieveToolFromChests` returns `boolean`. Use the signature you pinned in Step 3's grep.

#### Step 5: Grep for the exact `ensurePickaxe` / `ensureAxe` / `ensureShovel` / `ensureSword` signatures

Before writing the crafting fallback, pin the exact signatures.

- [ ] Grep for each helper:

Use Grep: pattern `ensurePickaxe\|ensureAxe\|ensureShovel\|ensureSword`, type `java`, output_mode `content`, `-n` true. Find existing callers (outside `ToolProvisionService` itself) and record the argument count and types used.

- [ ] Determine whether each helper takes `(bot, source, commander)` or `(bot, source, commander, allowWoodenFallback)`. Record the exact form.

- [ ] If the 4-arg overload with `allowWoodenFallback` does not exist, drop that argument from the `PICKAXE` case in Step 6.

#### Step 6: Implement tryCraftingFallback

- [ ] Add this private method. Per spec (lines 188–189), `FISHING_ROD` does NOT have an `ensureX` helper and is intentionally excluded from the crafting fallback — skip straight to stand-down for fishing rods:

```java
private static boolean tryCraftingFallback(ServerPlayerEntity bot,
                                           GearCategory category,
                                           ServerCommandSource source) {
    // Only tool categories (pickaxe/axe/shovel/sword) have ensureX helpers.
    // Armor, shield, bow, crossbow, trident, mace, elytra, shears, and fishing rod
    // all skip the crafting step per the spec.
    try {
        return switch (category) {
            case PICKAXE -> ToolProvisionService.ensurePickaxe(bot, source, null, true);
            case AXE     -> ToolProvisionService.ensureAxe(bot, source, null);
            case SHOVEL  -> ToolProvisionService.ensureShovel(bot, source, null);
            case SWORD   -> ToolProvisionService.ensureSword(bot, source, null);
            default      -> false;
        };
    } catch (Throwable t) {
        LOGGER.debug("tryCraftingFallback failed for {}: {}", category, t.getMessage());
        return false;
    }
}
```

**Note:** adjust each helper's argument list to match the form you pinned in Step 5. If the 4-arg `ensurePickaxe(bot, source, commander, allowWoodenFallback)` overload doesn't exist, use the 3-arg form.

#### Step 7: Hook the shutdown in Frens.java

- [ ] In [Frens.java](src/main/java/net/wcfcarolina13/Frens.java)'s `SERVER_STOPPING` block, REPLACE the `clearAllCooldowns()` call from Task 7 with the full shutdown:

```java
net.wcfcarolina13.GameAI.services.DurabilityFallbackService.shutdownExecutors();
```

(`shutdownExecutors` internally calls `clearAllCooldowns`.)

#### Step 8: Add a stub tryShowGearNoReplacement in CompanionOverheadDialogueService

- [ ] Open [CompanionOverheadDialogueService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadDialogueService.java). Add this one-liner stub at the bottom of the class:

```java
/** Stub — full implementation lands in Chunk 4. */
public static boolean tryShowGearNoReplacement(ServerPlayerEntity bot) {
    return false;
}
```

#### Step 9: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 10: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/DurabilityFallbackService.java src/main/java/net/wcfcarolina13/Frens.java src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadDialogueService.java
git commit -m "feat: complete DurabilityFallbackService with chest and craft steps"
```

---

### Task 12: Hook BotActions ranged weapon scoring

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/GameAI/BotActions.java](src/main/java/net/wcfcarolina13/GameAI/BotActions.java)

BotActions.java is ~199KB. Read only the target methods, not the whole file.

#### Step 1: Read the target methods

- [ ] Read [BotActions.java](src/main/java/net/wcfcarolina13/GameAI/BotActions.java) with offset around line 1380 and limit 160 lines to see:
  - `meleeWeaponScore(ItemStack stack)` around line 1382
  - `combatWeaponScore(ItemStack stack)` around line 1400
  - `isLikelyWeapon(ItemStack stack)` around line 1470
  - Any other weapon-selection helpers in that region

- [ ] Also read around line 2160–2180 for `isRangedWeapon` and `describeMeleeProfile`.

- [ ] These are pure scoring functions — they don't know about the bot. To filter by policy, we need a variant that takes the bot. Check whether there's a caller-level site that can do the filtering instead — search for callers of `meleeWeaponScore` and `combatWeaponScore`.

Use Grep: pattern `meleeWeaponScore\|combatWeaponScore`, type `java`, output_mode `content`, `-n` true. Note each call site — the filtering is cleanest at the call site (not inside the scoring helper) because the scoring helper is bot-agnostic.

- [ ] Cross-check: the spec lists specific line numbers (1386, 1475, 1489, 1517, 2168) as the known ranged-weapon selection hot spots. Your grep should catch callers around those lines. If it doesn't, the spec line numbers may have drifted since the spec was written — read the surrounding ~40 lines at each spec line number to identify the real current call sites.

#### Step 2: Add imports

- [ ] At the top of BotActions.java:

```java
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
```

#### Step 3: Filter at each call site of meleeWeaponScore / combatWeaponScore

- [ ] For each caller found in Step 1, add a policy check. Example pattern — replace:

```java
double score = combatWeaponScore(stack);
if (score > bestScore) { ... }
```

With:

```java
if (DurabilityPolicyService.shouldAvoid(bot, stack)) {
    // Filtered: preserved below threshold.
} else {
    double score = combatWeaponScore(stack);
    if (score > bestScore) { ... }
}
```

Do this at every call site — probably 2–4 sites based on the grep.

#### Step 4: Handle the "nothing found" fallback trigger

- [ ] For each call site where a null or empty result means "no weapon found", add a fallback trigger if the currently-held weapon is preserved-below-threshold. Use `GearCategory.BOW`, `CROSSBOW`, `TRIDENT`, or `SWORD` as appropriate based on what the call site is selecting. If the call site doesn't know which category it wanted, default to `SWORD`.

#### Step 5: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 6: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/GameAI/BotActions.java
git commit -m "feat: hook BotActions weapon scoring with durability policy"
```

---

### Task 13: Hook FishingSkill

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java)

#### Step 1: Read the rod equip site

- [ ] Grep for `ensureFishingRod`, `Items.FISHING_ROD`, and `BotActions.ensureHotbarItem` in [FishingSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java). Locate the point where the bot confirms it has a rod equipped.

#### Step 2: Add imports

- [ ] At the top of the file:

```java
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
```

#### Step 3: Filter the rod check

- [ ] Find the line where the current rod is checked (likely in `ensureFishingRod` or right before the cast). Add:

```java
ItemStack currentRod = bot.getMainHandStack();
if (currentRod.isOf(net.minecraft.item.Items.FISHING_ROD)
        && DurabilityPolicyService.shouldAvoid(bot, currentRod)) {
    DurabilityFallbackService.requestRefresh(
            bot, DurabilityFallbackService.GearCategory.FISHING_ROD, source);
    return SkillExecutionResult.failure("Fishing rod below durability threshold.");
}
```

(Use the actual `SkillExecutionResult` / `return` form that matches the containing method's return type.)

#### Step 4: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 5: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java
git commit -m "feat: hook FishingSkill rod selection with durability policy"
```

---

### Task 14: Hook WoolSkill shears selection

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoolSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoolSkill.java)

#### Step 1: Read the shears equip site

- [ ] Read the relevant region of [WoolSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoolSkill.java) — look at lines 305–340 (the `ensureShearsEquipped` helper) and around 790 (another shears check).

#### Step 2: Add imports

- [ ] At the top:

```java
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
```

#### Step 3: Filter inside ensureShearsEquipped

- [ ] At the top of the method, after the existing `ItemStack hand = bot.getMainHandStack();` line, add:

```java
if (hand.isOf(net.minecraft.item.Items.SHEARS)
        && DurabilityPolicyService.shouldAvoid(bot, hand)) {
    // Current shears preserved below threshold — request fallback and fall through
    // to the inventory search; if it finds another pair, great; otherwise returns false.
    DurabilityFallbackService.requestRefresh(
            bot, DurabilityFallbackService.GearCategory.SHEARS, null);
    // Fall through to the inventory search below — don't return yet.
}
```

- [ ] In the `findShearsSlot(bot)` helper (or wherever the inventory is scanned for shears), add the `shouldAvoid` filter inside the scan loop — skip stacks that the policy refuses.

#### Step 4: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 5: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoolSkill.java
git commit -m "feat: hook WoolSkill shears selection with durability policy"
```

---

### Task 15: Hook ElytraFlightService

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/GameAI/services/ElytraFlightService.java](src/main/java/net/wcfcarolina13/GameAI/services/ElytraFlightService.java)

#### Step 1: Read the elytra equip phase

- [ ] Read [ElytraFlightService.java](src/main/java/net/wcfcarolina13/GameAI/services/ElytraFlightService.java) around lines 310–360 — the "already wearing elytra / search inventory for elytra / equip" block.

#### Step 2: Add imports

- [ ] At the top:

```java
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
```

#### Step 3: Filter the elytra equip site

- [ ] Find the block where `elytraSlot = findItemSlot(bot, Items.ELYTRA)` happens. Right after it, add:

```java
if (elytraSlot >= 0) {
    ItemStack elytraCandidate = bot.getInventory().getStack(elytraSlot);
    if (DurabilityPolicyService.shouldAvoid(bot, elytraCandidate)) {
        LOGGER.info("ElytraFlight: {} skipped preserved elytra below threshold",
                bot.getName().getString());
        DurabilityFallbackService.requestRefresh(
                bot, DurabilityFallbackService.GearCategory.ELYTRA, null);
        setPhase(botId, FlightPhase.NONE, now);
        clearState(botId);
        return;
    }
}
```

(Match the exact variable names and method signatures in the existing code.)

#### Step 4: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 5: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/ElytraFlightService.java
git commit -m "feat: hook ElytraFlightService equip phase with durability policy"
```

---

## Chunk 3 Verification (Manual, In-Game)

After Task 15 commits successfully:

- [ ] Run `./gradlew build -x test`. Clean build.
- [ ] **Manual test 1 (full chest retrieval):** Toggle ON, diamond pickaxe at 5% in bot inventory (nothing else), iron pickaxe at 80% in a registered chest within 48 blocks. Command mining. Bot walks to chest, withdraws iron pickaxe, resumes mining.
- [ ] **Manual test 2 (crafting fallback):** Toggle ON, diamond pickaxe at 5% in bot inventory, no tool in chests, but wood in bot inventory + crafting table within reach. Command mining. Bot walks to table, crafts wooden pickaxe, uses it.
- [ ] **Manual test 3 (stand down):** Toggle ON, diamond pickaxe at 5%, nothing else anywhere. Command mining. Bot bails cleanly with a log message. No crash, no infinite retry loop (the 20s cooldown should prevent re-attempts).
- [ ] **Manual test 4 (fishing rod):** Toggle ON, enchanted fishing rod at 5% durability, unenchanted rod at 80% in inventory. Command fishing. Bot swaps to unenchanted rod.
- [ ] **Manual test 5 (wool shears):** Toggle ON, diamond-tier (N/A for shears, but imagine an enchanted pair) enchanted shears at 5% in hand. Command wool collection. Bot should refuse the enchanted shears if unenchanted pair available.
- [ ] **Manual test 6 (elytra):** Toggle ON, enchanted Unbreaking III elytra at 5% durability. Trigger flight. Bot should refuse to equip the preserved elytra.
- [ ] **Manual test 7 (executor shutdown):** Stop the server cleanly. Server log should show no hanging threads; the `frens-durability-fallback` thread should terminate within 2 seconds.

---

## Chunk 3 Summary

After Chunk 3, the full fallback chain is operational. Bots swap, walk to chests, craft replacements, or stand down as appropriate across every hook site. **Five commits total.**

---

## Chunk 4: Phase 4 — Dialogue, Guide, Changelog

**Goal of this chunk:** The feature feels alive — bots speak up when they refuse gear, the guide explains the toggle to players, and the changelog documents the feature.

**At the end of Chunk 4, this must be true:**

- `./gradlew build -x test` passes.
- Overhead dialogue fires when a bot filters a preserved item (11% normal trigger).
- A different dialogue fires at the 3% combat edge.
- A third dialogue fires when the fallback chain fully fails.
- The "Preserve Expensive Gear" topic appears in the in-game guide under Settings and is searchable.
- `changelog.md` has a new entry summarizing the feature.

---

### Task 16: Add dialogue lines and tryShow methods

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadDialogueService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadDialogueService.java)

#### Step 1: Read the existing dialogue pattern

- [ ] Read [CompanionOverheadDialogueService.java](src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadDialogueService.java). Study:
  - The constants block (`COOLDOWN_MS`, `DURATION_MS`, `OVERHEAD_GLOBAL_SUPPRESSION_MS`)
  - `LEAF_STUCK_LINES` array declaration pattern
  - `tryShowLeafStuck(bot, reason)` for the canonical rate-limited trigger shape
  - `tryShowGeneric(bot, lastMap, cooldownMs, lines, tag, reason)` — the shared helper you'll reuse

#### Step 2: Add the three line pools

- [ ] Add these constants next to the existing line arrays (e.g., after `BERRY_EDIBLE_LINES`):

```java
private static final String[] GEAR_PRESERVE_SWAP_LINES = new String[] {
        "Been using this one a while. Gonna hang it up.",
        "Not risking the good stuff on this.",
        "This blade's getting thin — grabbing something else.",
        "Yeah no, too nice to wreck out here.",
        "Saving the shiny for when it matters.",
        "I'll spare this one. Held up well.",
        "Don't want to snap it doing grunt work.",
        "Shelving the fancy kit. Back to basics.",
        "This one's earned a break.",
        "Swapping — rather not push my luck."
};

private static final String[] GEAR_COMBAT_EDGE_LINES = new String[] {
        "One more hit and she's gone!",
        "Careful — gear's on the edge!",
        "This thing's about to snap!",
        "Running on fumes over here!",
        "Almost spent — pull back!",
        "Hang on, I'm out of good stuff!"
};

private static final String[] GEAR_NO_REPLACEMENT_LINES = new String[] {
        "Got nothing else. Give me a minute.",
        "Can't find a spare anywhere.",
        "Need to restock — this was my last one.",
        "I'm out. Someone grab me a new one?",
        "Tried the chests, no luck.",
        "No replacements around. I'm stuck.",
        "Looked everywhere. Nothing.",
        "Hands are empty. I'll wait."
};
```

#### Step 3: Add three per-bot cooldown maps

- [ ] Add these constants next to existing per-bot cooldown maps (likely named `LAST_LEAF_STUCK_MS`, `LAST_BERRY_STING_MS`, etc.):

```java
private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> LAST_GEAR_PRESERVE_SWAP_MS =
        new java.util.concurrent.ConcurrentHashMap<>();
private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> LAST_GEAR_COMBAT_EDGE_MS =
        new java.util.concurrent.ConcurrentHashMap<>();
private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> LAST_GEAR_NO_REPLACEMENT_MS =
        new java.util.concurrent.ConcurrentHashMap<>();

private static final long GEAR_DIALOGUE_COOLDOWN_MS = 30_000L; // 30s per-bot, per-category
```

#### Step 4: Replace the stub tryShowGearNoReplacement and add the two siblings

- [ ] Delete the Chunk 3 stub `tryShowGearNoReplacement` and replace with the full implementation using `tryShowGeneric` (the existing helper):

```java
public static boolean tryShowGearPreserveSwap(ServerPlayerEntity bot) {
    tryShowGeneric(
            bot,
            LAST_GEAR_PRESERVE_SWAP_MS,
            GEAR_DIALOGUE_COOLDOWN_MS,
            GEAR_PRESERVE_SWAP_LINES,
            "gear-preserve-swap",
            null);
    return true;
}

public static boolean tryShowGearCombatEdge(ServerPlayerEntity bot) {
    tryShowGeneric(
            bot,
            LAST_GEAR_COMBAT_EDGE_MS,
            GEAR_DIALOGUE_COOLDOWN_MS,
            GEAR_COMBAT_EDGE_LINES,
            "gear-combat-edge",
            null);
    return true;
}

public static boolean tryShowGearNoReplacement(ServerPlayerEntity bot) {
    tryShowGeneric(
            bot,
            LAST_GEAR_NO_REPLACEMENT_MS,
            GEAR_DIALOGUE_COOLDOWN_MS,
            GEAR_NO_REPLACEMENT_LINES,
            "gear-no-replacement",
            null);
    return true;
}
```

If `tryShowGeneric` returns a value, chain the return. If it's void, return `true` as above. Match the existing callers' expectation.

#### Step 5: Wire up the dialogue triggers from the hook sites

For each hook site created in Chunks 2 and 3, add a `tryShowGearPreserveSwap(bot)` call at the point where a preserved item was filtered and a compliant alternative was successfully chosen (not just where the filter fired — we only speak when something actually changed).

**Per-file checklist:**

- [ ] [ToolSelector.java](src/main/java/net/wcfcarolina13/PlayerUtils/ToolSelector.java) — in `selectBestToolForBlock`, at the end of the main-inventory scan block where `bestMainSlot != -1` triggers a swap to hotbar. After the `bot.currentScreenHandler.onSlotClick(...)` swap call, add:

  ```java
  CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
  ```

- [ ] [armorUtils.java](src/main/java/net/wcfcarolina13/PlayerUtils/armorUtils.java) — in `autoEquipArmor`, right after the `bot.equipStack(slot, stackToEquip);` line inside the per-slot equip branch. Only fire the dialogue when the replaced armor was preserved-below-threshold (guard with a pre-check):

  ```java
  boolean replacedPreservedBelow =
          !equippedArmor.isEmpty()
          && DurabilityPolicyService.shouldAvoid(bot, equippedArmor);
  bot.equipStack(slot, stackToEquip);
  // ... existing displacement logic ...
  if (replacedPreservedBelow) {
      CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
  }
  ```

- [ ] [CombatInventoryManager.java](src/main/java/net/wcfcarolina13/PlayerUtils/CombatInventoryManager.java) — in `ensureBestWeaponAccessible`, after the `swapStacks(...)` / `setSelectedSlot(...)` call succeeds AND the previously-held item was preserved-below-threshold:

  ```java
  ItemStack priorHeld = bot.getMainHandStack();
  boolean priorWasFiltered = !priorHeld.isEmpty()
          && DurabilityPolicyService.shouldAvoid(bot, priorHeld);
  // ... existing swap + setSelectedSlot ...
  if (priorWasFiltered) {
      CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
  }
  ```

- [ ] [FishingSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java) — in the rod filter block from Chunk 3 Task 13, fire `tryShowGearPreserveSwap(bot)` when the held rod is rejected (the rejection itself is the "swap intent" even if the fallback has to retrieve a replacement).

- [ ] [WoolSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoolSkill.java) — in `ensureShearsEquipped`, after the shears scan finds a non-preserved replacement and before returning true, fire the dialogue.

- [ ] [ElytraFlightService.java](src/main/java/net/wcfcarolina13/GameAI/services/ElytraFlightService.java) — in the elytra filter block from Chunk 3 Task 15, fire the dialogue when the preserved elytra is rejected.

- [ ] [BotActions.java](src/main/java/net/wcfcarolina13/GameAI/BotActions.java) — at each weapon-scoring call site hooked in Task 12, if the filter rejected a preserved-below-threshold candidate AND a compliant alternative was chosen on the same pass, fire the dialogue.

**Combat edge dialogue (`tryShowGearCombatEdge`)** — the 3% combat threshold already narrows firing to when ratio < 3%. Add an additional guard so the dialogue is meaningful: fire only when the filtered stack is a **weapon/shield** (not a pickaxe the bot happens to be holding during combat) AND the policy is rejecting it due to combat:

- [ ] In [CombatInventoryManager.java](src/main/java/net/wcfcarolina13/PlayerUtils/CombatInventoryManager.java)'s `ensureBestWeaponAccessible` fallback path (the `bestWeaponSlot.isEmpty()` branch), replace the single-line dialogue check with:

  ```java
  ItemStack held = bot.getMainHandStack();
  if (!held.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, held)) {
      if (BotCombatCalloutService.isInCombat(bot.getUuid())) {
          CompanionOverheadDialogueService.tryShowGearCombatEdge(bot);
      }
      DurabilityFallbackService.requestRefresh(
              bot, DurabilityFallbackService.GearCategory.SWORD, null);
  }
  ```

- [ ] Same pattern in the `ensureOffhandShield` filter block (from Task 10 Step 5) — if the shield is rejected during combat, fire the combat-edge line.

**Do NOT modify the `tryShowGearNoReplacement` call site** — it's already wired in Chunk 3 Task 11 Step 2's `runFallbackChain` step 4, and Step 4 of this task (above) just replaced the stub body with the real implementation.

#### Step 6: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 7: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadDialogueService.java src/main/java/net/wcfcarolina13/PlayerUtils/ToolSelector.java src/main/java/net/wcfcarolina13/PlayerUtils/armorUtils.java src/main/java/net/wcfcarolina13/PlayerUtils/CombatInventoryManager.java
git commit -m "feat: add durability preservation overhead dialogue"
```

---

### Task 17: Add guide topic

**Files:**
- Modify: [src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java)

#### Step 1: Read baseTopics()

- [ ] Read [BotGuideScreen.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java). Locate the `baseTopics()` method and the `GuideTopic` record definition (near the top).

#### Step 2: Add the new topic

- [ ] Inside the `List.of(...)` returned by `baseTopics()`, add a new entry. Place it next to other "Settings" category entries for cleanliness:

```java
new GuideTopic(
        "settings_preserve_expensive_gear",
        "Settings",
        "Preserve Expensive Gear",
        "Keep your bots from wearing out your best tools.",
        List.of(
                "When enabled, your bots refuse to use enchanted items and gear made of gold, diamond, netherite, or turtle shell once durability drops below 11%.",
                "In combat, the threshold drops to 3% — the bot will push a risky item harder when lives are on the line.",
                "When a tool is locked out, the bot tries to swap to a cheaper alternative from its inventory, walks to a registered chest for a replacement, or crafts a new one at a nearby crafting table.",
                "If nothing is available, the bot pauses and speaks up — check its inventory and give it a refill.",
                "Note on Mending: items with the Mending enchantment are also preserved. If you want a Mending item used continuously for XP self-repair, disable this toggle.",
                "Toggle this under BotControl → Personal Preferences. The setting is per player and applies to every bot you own. Default: OFF."
        ),
        "",
        "",
        "durability tools gear preserve diamond netherite gold enchanted expensive shield armor mending"
)
```

Match the exact `GuideTopic` constructor argument order and spacing used in existing entries.

#### Step 3: Build verification

- [ ] Run `./gradlew build -x test`. Expected: build succeeds.

#### Step 4: Commit

- [ ] Commit:

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java
git commit -m "feat: add Preserve Expensive Gear topic to bot guide"
```

---

### Task 18: Add changelog entry

**Files:**
- Modify: [changelog.md](changelog.md)

#### Step 1: Read the top of the changelog

- [ ] Read the first 30 lines of [changelog.md](changelog.md) to see the entry format.

#### Step 2: Add a new entry at the top (below the header)

- [ ] Insert a new entry right after the top heading block, using the existing format:

```markdown
## 2026-04-07 — Durability Preservation Toggle

- **New player-facing toggle:** Added `BotPlayerPreferencesScreen` reachable from a new "Personal Preferences" button in the `BotControlScreen` footer (visible to all players, not admin-gated). Houses a single "Preserve Expensive Gear" toggle per player, stored in `ManualConfig.playerPreserveExpensiveGear` keyed by player UUID. Default OFF.
- **Rule:** When enabled, bots owned by that player refuse to use items that are both preserved (gold/diamond/netherite tools+armor, turtle helmet, OR any enchanted damageable item) and below 11% durability. In combat, the threshold drops to 3% for new selections.
- **Core policy service:** `DurabilityPolicyService` (`GameAI/services/`) — pure-static rule: `isPreservedMaterial`, `isEnchanted`, `durabilityRatio`, `currentThreshold`, `isPolicyEnabled`, and the main `shouldAvoid(bot, stack)` predicate. Called as a one-line filter from selection sites.
- **Fallback chain:** `DurabilityFallbackService` (`GameAI/services/`) runs on a dedicated single-thread executor (`frens-durability-fallback`) with a 20s per-bot/per-category cooldown. Flow: inventory re-scan → registered chest retrieval (≥25% durability floor on replacements, via `ToolProvisionService.retrieveToolFromChests`) → crafting fallback (`ensurePickaxe/Axe/Shovel/Sword` with wooden fallback allowed) → stand down with overhead dialogue. Cleared on bot death, removal, server stop, and on toggle-on flip.
- **Hooked selection sites:** `ToolSelector.selectBestToolForBlock` (mining), `armorUtils.findBestArmorSlot` (armor equip-time only, no continuous re-check), `CombatInventoryManager.findBestWeaponSlot` + `ensureOffhandShield` (melee + shield), `BotActions` weapon scoring (bow/crossbow/trident/mace), `FishingSkill` rod, `WoolSkill` shears, `ElytraFlightService` elytra equip. Intentionally NOT hooked: emergency leaf-clearing, tree-stuck escape, arrow recovery, crafting helper (create paths).
- **Immersive dialogue:** Three new line pools in `CompanionOverheadDialogueService` — `GEAR_PRESERVE_SWAP_LINES` (10 lines, normal 11% swap), `GEAR_COMBAT_EDGE_LINES` (6 lines, 3% combat edge), `GEAR_NO_REPLACEMENT_LINES` (8 lines, fallback exhausted). Per-bot 30s cooldown, 4s global suppression.
- **Guide entry:** New `settings_preserve_expensive_gear` topic under the Settings category in `BotGuideScreen`, including a note on the Mending interaction.
- **Network:** Three new payloads (`UpdatePlayerPreservePayload` C2S, `RequestPlayerPreservePayload` C2S, `PlayerPreserveStatePayload` S2C) for the one-shot read-on-open + optimistic-write toggle flow. No cross-player editing possible — the sender is always the subject.
```

#### Step 3: Commit

- [ ] Commit:

```bash
git add changelog.md
git commit -m "docs: Add changelog entry for durability preservation toggle"
```

---

## Chunk 4 Verification (Manual, In-Game)

After Task 18 commits successfully:

- [ ] Run `./gradlew build -x test`. Final clean build.
- [ ] **Manual test 1 (swap dialogue):** Toggle ON, diamond pickaxe at 5% + iron pickaxe at 80%. Command mining. Bot swaps and speaks one of the `GEAR_PRESERVE_SWAP_LINES` lines.
- [ ] **Manual test 2 (combat edge dialogue):** Toggle ON, diamond sword at 2% durability, spawn a zombie. Bot should refuse the sword, request fallback (which may fail if no alternative), and speak one of the `GEAR_COMBAT_EDGE_LINES` lines.
- [ ] **Manual test 3 (no replacement dialogue):** Toggle ON, diamond pickaxe at 5%, nothing anywhere. Command mining. After the fallback chain fails, bot speaks one of the `GEAR_NO_REPLACEMENT_LINES` lines.
- [ ] **Manual test 4 (dialogue cooldown):** Repeat test 1 within 30 seconds — no dialogue fires (rate-limited). After 30s, repeat — dialogue fires again.
- [ ] **Manual test 5 (guide searchable):** Open the in-game bot guide, type "preserve" or "durability" in the search box. The "Preserve Expensive Gear" topic should appear under Settings.
- [ ] **Manual test 6 (guide content):** Open the new topic. Verify the description mentions the 11%/3% thresholds, the fallback chain, and the Mending note.
- [ ] **Manual test 7 (full end-to-end):** Run through the spec's verification test cases 1–17 (see spec section "Verification Plan"). All 17 should pass.

---

## Chunk 4 Summary

After Chunk 4, the feature is user-visible, in-character, and documented. **Three commits total.**

---

## Grand Summary

**18 tasks across 4 chunks. 18 commits total.**

- Chunk 1 (6 tasks): storage + policy + payloads + preferences UI
- Chunk 2 (4 tasks): core selection site hooks + fallback stub
- Chunk 3 (5 tasks): full fallback chain + ranged/utility hooks
- Chunk 4 (3 tasks): dialogue + guide + changelog

**Final verification:** after Task 18, re-run every manual test from chunks 1–4. Open `changelog.md` and confirm the entry is present. Open the in-game guide and confirm the topic is listed. Play for 10 minutes with toggle ON and toggle OFF to confirm no regressions in normal bot behavior.

**Commit this plan and the updated spec to git before starting execution.**
