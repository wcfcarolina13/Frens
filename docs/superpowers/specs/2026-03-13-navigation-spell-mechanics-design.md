# Navigation & Spell Mechanics — Design Spec

**Date**: 2026-03-13
**Status**: Approved
**Scope**: Navigation artifacts, paired spells (Remote Guidance, Chorus Recall), navigation modes, confirmation UX, sound events, guide entries

---

## Context

The Frens mod has a basic spell system (Regroup, Summon, Home, Remote Inventory) gated by item tokens (Wizard's Tome, Eye of Ender, Goat Horn, Ender Pearl) and Enchanting Table proximity. This design adds:

1. **Navigation artifacts** — bot-held items (Compass, Map, Eye of Ender) that unlock autonomous navigation
2. **Paired spells** — Remote Guidance and Chorus Recall, requiring both player and bot to hold specific items
3. **Navigation modes** — toggle between chunked walking and delayed teleport
4. **Non-obstructive confirmation UX** — safe for combat/swimming scenarios
5. **In-game guide entries** for all new mechanics

**Problem**: Bots have no way to navigate home autonomously based on items they hold, and there's no "paired item" spell concept. The ender pearl token system is simplistic (player holds it -> passive access, no consumption).

**Outcome**: A tiered navigation and spell system where item possession matters for both player and bot, with consumable paired spells and configurable travel mechanics.

---

## 1. Navigation Artifact Tier System

Bot-held (or player-held for Eye of Ender) items that unlock navigation abilities.

### Tier 1 — Compass / Map (basic)
- Bot can self-navigate to **nearest or preferred base** only
- Player can use **Home** spell button to send bot home
- **Same-dimension only**
- Subject to **navigation mode** setting (walk or teleport-with-delay)
- **Not consumed** — reusable

### Tier 2 — Eye of Ender (enhanced)
- **Either** player or bot can hold it for navigation to activate
- Navigate to **any named base** (player specifies which)
- **Cross-dimension** capable
- **Instant teleport** — bypasses delay even in teleport-delay mode
- **Not consumed** — permanent artifact
- **Dual-purpose**: player-held still provides Summon access (60s cooldown, unchanged)

### Implementation
- New `NavigationArtifactService` in `GameAI/services/`
- `getBotNavigationTier(bot, player)` -> `NONE | BASIC | ENHANCED`
  - Scans bot inventory for `Items.COMPASS`, `Items.FILLED_MAP`, `Items.ENDER_EYE`
  - Scans player inventory for `Items.ENDER_EYE` (dual-purpose)
- `canNavigateToBase(bot, player, baseName, targetDimension)` -> boolean
- Reuses `BotHomeService.resolveHomeTarget()` for Tier 1, `BotHomeService.listBases()` for Tier 2

---

## 2. Paired Spells

### Remote Guidance (Ender Pearl)
- **Requirement**: Both player AND bot each hold >= 1 Ender Pearl
- **Effect**: Bot navigates to player's location OR any known base (player chooses destination)
- **Distance**: Any distance, any dimension
- **Consumption**: Both pearls consumed **on departure** (before travel begins). This ensures items are spent before the bot enters transit, making the operation non-cancellable.
- **Navigation mode applies**: Uses player's chosen mode (walk or teleport-delay)
- **Replaces** old ender pearl token behavior entirely. This is an intentional regression: a single ender pearl held by only the player no longer grants come/summon access. Players must use Goat Horn (regroup), Eye of Ender (summon), or paired pearls (Remote Guidance) instead.

### Chorus Recall (Ender Pearl + Chorus Fruit)
- **Requirement**: Both player AND bot each hold 1 Ender Pearl AND 1 Chorus Fruit
- **Effect**: Instant teleport — player chooses direction (bot-to-player or player-to-bot)
- **Cross-dimension**: Yes, always
- **Consumption**: All 4 items consumed (1 pearl + 1 chorus from each)
- **Always instant**: No delay regardless of navigation mode

### Server-side validation flow
1. Command handler checks both player and bot inventories
2. Missing items -> reject with descriptive message ("Both you and your companion must hold an Ender Pearl")
3. Items present -> consume atomically on server thread (decrement stack counts)
4. Trigger navigation/teleport

### Destination selection UX

**Remote Guidance**: The spell button opens a `NavigationConfirmScreen` with a destination selector:

- Radio options: "Guide to me" (player's current position) | "Guide to base: [dropdown of known bases]"
- Dropdown populated from `BotHomeService.listBases()` (synced via existing `BaseNetworkManager`)
- Below the selector: estimated travel time and "Pearls will be consumed" warning
- Buttons: Confirm / Cancel

**Chorus Recall**: The spell button opens a `NavigationConfirmScreen` with a direction selector:

- Radio options: "Teleport bot to me" | "Teleport me to bot"
- Below: "Ender pearl and chorus fruit will be consumed from both inventories"
- Buttons: Confirm / Cancel

### Spell Menu buttons
- **Remote Guidance**: Active when `full || playerHasPearl`
- **Chorus Recall**: Active when `full || (playerHasPearl && playerHasChorus)`
- Client-side checks are hints; server is authoritative (validates bot inventory too)
- Button order: Regroup | Summon | Home | **Remote Guidance** | **Chorus Recall** | Remote Inventory | Back

### Tooltips
- Remote Guidance: "Uses paired ender pearls to guide the bot across any distance to the player or a known base. Both pearls are consumed."
- Chorus Recall: "Consumes an ender pearl and chorus fruit from both the player and the bot to perform an instant teleport."

---

## 3. Navigation Modes & Delayed Teleport

### Toggle
- Stored per-bot in `BotHomeService` JSON (alongside auto-return, preferred base)
- Two modes: `WALK` and `TELEPORT_DELAY`
- Default: `TELEPORT_DELAY`
- Config: `/bot config <alias> nav_mode walk|teleport` + Bot Controls UI panel

### WALK mode

- Chunked pathfinding: bot navigates in ~32-block segments via `MovementService.execute(DIRECT)` with retry on stuck
- Repeats until arrival or stuck 3 times consecutively
- Realistic but slow; can fail on complex terrain

### TELEPORT_DELAY mode

- **Duration**: 1 real second per chunk (distance / 16), minimum 5 seconds, maximum 5 minutes
- Cross-dimension adds flat 30 seconds
- Bot is **removed from world** (discarded/despawned) but tracked in `BotRegistry` with an `IN_TRANSIT` state
- `NavigationArtifactService.PendingTravel` record: bot UUID, destination pos, dimension, departure tick, arrival tick
- On arrival tick: re-spawn bot at destination via `createFakePlayer` + `BotPersistenceService` restore
- **Not cancellable** once started (items already consumed on departure)
- Eye of Ender enhanced navigation **bypasses delay** (instant teleport regardless of mode)

### Bot despawn cleanup (teleport-delay departure)

Before removing the bot from the world, the following services must be notified/cleaned up:

- `TaskService` — release any active skill execution slot and thread ticket
- `FollowMovementService` — cancel any active follow state
- `BotCommandStateService` — set state to a new `TRAVELING` mode
- `BotEventHandler` — exclude bot from server-tick processing while in transit
- `BotRegistry` — mark bot as `IN_TRANSIT` (not `ACTIVE`, not `DESPAWNED`)
- Open inventory viewers — force-close any `BotPlayerInventoryScreenHandler` for this bot
- Combat — clear mob targeting via `bot.setAttacker(null)` / despawn cleanup

### Confirmation UX

**Player-initiated** (Spells Menu or command):
- `NavigationConfirmScreen extends Screen` (follows `RecruitmentDialogueScreen` pattern)
- Shows: "Send [bot] to [destination]? Estimated travel time: [X seconds]. Pearls will be consumed."
- Buttons: Confirm / Cancel

**Auto-return at sunset** (via `BotAutoReturnSunsetService`):
- Non-obstructive HUD overlay rendered above hotbar
- Shows: "[Bot] wants to return home. [Accept] [Dismiss]"
- Clickable buttons, doesn't block gameplay
- Auto-dismisses after 60 seconds (bot stays put)
- Network flow: server sends `NavigationRequestPayload` -> client renders overlay -> player responds -> client sends `NavigationResponsePayload`

### Follow-up hardening (post-initial implementation)
- Multi-bot concurrent travel
- Owner disconnects during bot travel
- Server restart during bot travel (persistence of PendingTravel)
- Dimension unload during travel

---

## 4. Token System Changes

### Removed
- Ender Pearl as passive token (player holds -> come+summon). Replaced by paired Remote Guidance spell.

### Unchanged
- Wizard's Tome -> full access
- Goat Horn -> regroup only
- Eye of Ender (player-held) -> summon only, 60s cooldown
- Enchanting Table proximity -> full access

### Added
- Eye of Ender (either holder) -> enhanced navigation tier
- Compass/Map (bot-held) -> basic navigation tier
- Paired Ender Pearl -> Remote Guidance spell
- Paired Ender Pearl + Chorus Fruit -> Chorus Recall spell

### Modified `CompanionSpellsScreen.AccessState`

```
AccessState(full, eye, horn, pairedPearl, pairedChorus, botNavTier)
```

- `botNavTier` synced from server via new `BotNavTierPayload` packet
- **Sync trigger**: Server sends `BotNavTierPayload` when the companion spells screen is opened (alongside existing base list sync). Client caches the value in `FrensClient` static field; `refreshEnabledState()` reads from cache each tick.
- **Staleness**: If the bot's inventory changes while the screen is open, the UI may be briefly stale. Server remains authoritative — a click on a stale-enabled button is rejected server-side with a message.

### Home button access
- Old: `homeBtn.active = state.full`
- New: `homeBtn.active = state.full || state.botNavTier >= BASIC`

---

## 5. Sound Events

Play vanilla `SoundEvents` directly via `world.playSound()` — no custom sound event registration or `sounds.json` entries needed. These are helper constants, not new registry entries.

| Constant Name | Vanilla SoundEvent | Pitch | Vol | Trigger |
|---|---|---|---|---|
| `SPELL_GUIDANCE_PROMPT` | `SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME` | 1.0 | 0.8 | Guidance prompt appears |
| `SPELL_GUIDANCE_ACCEPT` | `SoundEvents.ENTITY_ENDER_EYE_LAUNCH` | 1.2 | 0.7 | Player confirms spell |
| `SPELL_GUIDANCE_CONSUME` | `SoundEvents.ENTITY_ENDER_PEARL_THROW` | 0.8 | 1.0 | Bot arrives, pearls consumed |
| `SPELL_CHORUS_RECALL` | `SoundEvents.ENTITY_CHORUS_FRUIT_TELEPORT` | 1.0 | 1.0 | Chorus Recall teleport completes |

Chorus Recall also plays `SoundEvents.ENTITY_ENDERMAN_TELEPORT` (pitch 1.4, vol 0.6) on cast start.

---

## 6. Guide Entries

New `GuideTopic` records in `BotGuideScreen.baseTopics()`:

1. **"Navigation Artifacts"** (Items) — Compass, Map, Eye of Ender as bot-held nav items; tier differences; how to give items to bot
2. **"Remote Guidance"** (Spells) — Paired ender pearl requirements, consumption, destination choice, navigation mode interaction
3. **"Chorus Recall"** (Spells) — Paired teleport requirements, direction choice, cross-dimension, instant
4. **"Navigation Modes"** (Settings) — Walk vs Teleport-delay toggle, timing formula, how to configure
5. **"Spell Ingredients"** (Items) — Quick-reference table of all spell items and what they unlock

Tags: `spell navigation compass map ender pearl chorus fruit eye of ender teleport remote guidance chorus recall ritual artifact paired`

---

## Critical Files

| File | Changes |
|---|---|
| `GraphicalUserInterface/CompanionSpellsScreen.java` | Add Remote Guidance + Chorus Recall buttons, update AccessState, add botNavTier |
| `Commands/BotCompanionCommands.java` | Add `guidance` and `recall` subcommands |
| `Commands/modCommandRegistry.java` | Add handlers, remove old pearl token, add bot inventory validation |
| `ChatUtils/BotDialogueSounds.java` | Register 4 new sound events |
| `GraphicalUserInterface/BotGuideScreen.java` | Add 5 new GuideTopic entries |
| **NEW** `GameAI/services/NavigationArtifactService.java` | Tier checks, PendingTravel, delayed teleport, arrival |
| **NEW** `network/BotNavTierPayload.java` | Sync bot nav tier to client (S2C) |
| **NEW** `network/NavigationRequestPayload.java` | Server->client navigation request |
| **NEW** `network/NavigationResponsePayload.java` | Client->server accept/dismiss |
| **NEW** `network/SpellConfirmationNetworkManager.java` | Payload registration (`PayloadTypeRegistry`, `StreamCodec`, receivers in `FrensClient`/server) |
| **NEW** `GraphicalUserInterface/NavigationConfirmScreen.java` | Player-initiated confirmation screen |
| **NEW** `GraphicalUserInterface/NavigationHudOverlay.java` | Non-obstructive auto-return HUD |
| `GameAI/services/BotHomeService.java` | Add navMode field, getter/setter |
| `GameAI/services/BotAutoReturnSunsetService.java` | Integrate with NavigationArtifactService |
| `FrensClient.java` | Register HUD overlay, nav tier tracking |
| `Commands/configCommand.java` | Add nav_mode config option |
| `GameAI/services/BotRegistry.java` | Add `IN_TRANSIT` state for traveling bots |

---

## Verification

### Build
```bash
./gradlew build -x test
```

### In-game testing
1. Give bot compass -> Home button activates -> send home -> verify arrival
2. Give bot/player Eye of Ender -> any-base selection -> cross-dimension -> instant
3. Both hold pearls -> Remote Guidance -> verify navigation + consumption
4. Both hold pearl+chorus -> Chorus Recall -> bidirectional teleport + consumption
5. Toggle walk vs teleport-delay -> verify both modes work
6. Confirmation screen for player-initiated -> HUD overlay for auto-return
7. All 3 Remote Guidance sounds play at correct moments
8. Chorus Recall sounds play (enderman teleport on cast, chorus fruit teleport on arrival)
9. Guide topics searchable and accurate
10. Old pearl passive behavior removed -> pearl alone no longer enables come/summon
11. Insufficient items -> descriptive rejection message
