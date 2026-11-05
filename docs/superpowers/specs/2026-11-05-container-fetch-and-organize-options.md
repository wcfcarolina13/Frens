# Container fetch and organize: options (2026-11-05)

Bradley's ask: "go look for 30 oak wood" in the base storage room, and "go organize that" for items he hands the bot. This document covers research and options only. No code has changed. Paths below are relative to `src/main/java/net/wcfcarolina13/`.

## 1. What exists today

- **`/bot store deposit|withdraw [amount] [item] <bot>`** (`Commands/modCommandRegistry.java:949`, `GameAI/services/ChestStoreService.handleTransfer`). Each call uses one chest: the one the player is looking at, otherwise the first entry from `listDepositChestCandidates`. That list holds the owner's registry chests within 140 blocks, plus a block scan of radius 12 and ±6 in height. The scan accepts chests, trapped chests and barrels; it does not include copper chests. Withdraw never looks across several chests for an item. If the first chest lacks it, the command fails. Deposit can place a new chest, and falls back to putting fuel in a furnace. Because the owner issues the command, it bypasses the supplies policy.
- **Quick Store / Quick Fetch** (`GraphicalUserInterface/StoreTargetPickerOverlay.java`). The player points at a container and the bot deposits into it or takes everything from it. These chests are not tracked (`BotGuideScreen.java:1543`).
- **Bot chest registry** (`GameAI/services/BotChestRegistryService.java`). It records only chests that bots placed. Each record holds a context tag, a contents snapshot, the slot counts and the owner UUID (from 1.1.219 on). The player's own base chests are never in it. `DepositCandidate.containsPreferredItems` already ranks chests whose snapshot holds matching items. This is the only "learned placement" logic in the mod today. `isChestLike` accepts copper chests through `BlockTags.COPPER_CHESTS`.
- **Storage screen** (`GraphicalUserInterface/BotStorageScreen.java`, `network/ChestRegistryNetworkManager.java`, `ChestCollectPayload`/`ChestDismissPayload`/`ChestRegistryPayload`). It lists registry chests with their snapshots and offers Go, Collect (withdraw all) and Dismiss. `GameAI/services/ChestRegistryAccessPolicy` gates these actions to the owner, an operator or the host, and only for registry chests.
- **Supplies Phases 1–3** (`GameAI/services/supply/`). Every autonomous withdrawal goes through `SupplyWithdrawals`.
  - `SupplyChestRules.ownership` classifies a chest as `PLAYER_STORAGE`, `OWNER_STORAGE`, `DENY_FOREIGN`, `DENY_UNKNOWN` or `DENY_MIXED`.
  - Access denies a chest that has a container lock, is in a protected zone or cannot be opened.
  - `SupplyRequestPolicy` limits requests to an allowlist of common items with no extra components. It keeps a reserve of 8 food, 16 of a material and 1 of each equipment type.
  - `SupplyRequestLedger` allows one pending prompt per bot. "Allow once" gives a single-use grant. "Always" is a standing permission per (owner, world, chest), saved to disk and revocable with `/frens supply`.
- **Planned, not built** (`RALPH_TASK.md:848-849, 1011, 1013`):
  - P2 "combined ask": ask once for everything a craft needs.
  - P3 "Supplies Phase 4: the storage-room walkthrough": need-driven armour asks while following. Despite the name, it is not a fetch feature (`docs/superpowers/plans/2026-09-08-companion-supplies-and-model-switching.md:41`).
  - "Quick-action buttons (Sort, …)": this sorts the bot's inventory, not chests.
  - "Chest management overhaul: locking/access policy, categorization rules, organization modes": one line with no design behind it.
- **Category code**: none for storage. The only item categories are `SupplyRequestPolicy.Category {MATERIAL, EQUIPMENT}` and `DurabilityFallbackService.GearCategory`. Item tags appear in 54 places, but only as ad-hoc checks.
- **Item frames**: the mod reads them only for souls descriptions (`GameAI/souls/SoulSnapshotBuilder.java:434`, an entity box query). That code is a working precedent for a server-side frame read.
- **No client container-screen mixin exists** (`mixin/` has none). `ProtectedStructureBlockHelper.isProtectedContainer` already stops bots from breaking containers.

## 2. Vanilla facts (minecraft.wiki)

- **Item frames** ([Item Frame](https://minecraft.wiki/w/Item_Frame)): an entity in Java Edition. It holds one item and a rotation (`ItemRotation` 0–7, in 45° steps). Placing one on a chest requires sneaking. A frame with the `Fixed` tag "does not drop when it has no support block", which means a normal frame drops when its support goes. On shulker boxes, frames "pop off when the lid is opened". The page does not say whether a frame survives a chest opening, a chest merging into a double chest, or being placed on a barrel. Verify those in-game.
- **Glow item frames** ([Glow Item Frame](https://minecraft.wiki/w/Glow_Item_Frame)) work the same way. The item "appears to glow, though it does not emit any light level".
- **Comparators** ([Redstone Comparator](https://minecraft.wiki/w/Redstone_Comparator)) can read a frame's rotation (1–8) and a container's fullness. A bot does not need either: it can read both directly.
- **Signs** ([Sign](https://minecraft.wiki/w/Sign)): placing one on a chest requires sneaking. A sign is a block entity with `front_text`, `back_text` (four lines each) and `is_waxed`.
- **Chests** ([Chest](https://minecraft.wiki/w/Chest)): 27 slots, or 54 for a double chest. Sneaking stops a new chest from merging, and trapped chests never merge with normal ones. A chest "cannot be opened if there is a conductive block above it", or while a cat sits on it. Java Edition has a `minecraft:lock` item predicate.
- **Barrels** ([Barrel](https://minecraft.wiki/w/Barrel)): 27 slots. They never pair, and opening is "never prevented".
- **Shulker boxes** ([Shulker Box](https://minecraft.wiki/w/Shulker_Box)): 27 slots. They keep their contents when broken (the `minecraft:container` component). A shulker box cannot go inside another shulker box or a bundle, and it needs clear space above its lid to open.
- **Copper chests** ([Copper Chest](https://minecraft.wiki/w/Copper_Chest)) arrived in Java Edition 1.21.9, so they exist in 1.21.11. Copper golems take up to 16 of the first item they find in a copper chest. They then deliver it to the nearest chest or trapped chest they have not yet visited that is "either be empty, or contain the item type it is holding". They search a 65×17×65 area, spend 3 seconds examining each chest, ignore barrels, and give up after 10 chests ([Copper Golem](https://minecraft.wiki/w/Copper_Golem)). Vanilla's own sorter is therefore a learned layout (option iv) with no labels at all.
- **Shelves** ([Shelf](https://minecraft.wiki/w/Shelf)) also arrived in 1.21.9. A shelf holds 3 stacks, works with hoppers, and swaps its slots with the hotbar. It works as a display, not as bulk storage.
- **Hopper sorters** ([Tutorial:Hopper](https://minecraft.wiki/w/Tutorial:Hopper)): each row filters one item type, using 41 of the item plus filler held under a comparator threshold. Non-stackable items need special filters. Hoppers move 2.5 items a second ([Hopper](https://minecraft.wiki/w/Hopper)). Players who build these already have sorted storage, and the bot should leave them alone.
- **Bundles** ([Bundle](https://minecraft.wiki/w/Bundle)) are available by default from 1.21.2. A bundle holds one stack's worth of weight, and bundles can nest.

**Cheap server-side reads for a fake player:**
- Container inventories: block entities in loaded chunks. Reading one costs nothing, so the cost of a design is the walking, not the reading.
- Item frames: one `getEntitiesByClass(ItemFrameEntity, box)` call per area. The attached block is the frame's position offset opposite to its facing.
- Sign text: a block-entity read.
- The bot does not need comparators to read anything.

## 3. Two jobs

**(A) Fetch: "get 30 oak logs".**
- **Candidates:** use the registry plus a scan of the loaded block entities in the base zone if one is set, otherwise a radius of about 24. Include chests, trapped chests, barrels and copper chests. Skip shulker boxes, ender chests and hoppers.
- **Order:** chests whose snapshot or last-seen contents match come first, then the nearest.
- **Visits:** cap the walk at about 12 chests. The bot reads each chest's real contents on arrival and refreshes its snapshot then.
- **Permission:** the owner issued the command, so the same exemption applies as for `/bot store withdraw`. Still refuse `DENY_FOREIGN`, `DENY_UNKNOWN`, locked and protected-zone chests by reusing the `SupplyChestRules` access checks. Apply no reserve.
- **Partial results:** take what it finds and report "Got 18 of 30, checked 9 chests", without crafting to make up the gap.
- **Item names:** "oak wood" should resolve to `oak_log`. "Logs" should resolve to `#minecraft:logs`. The existing item-name parsing in `buildFilterForTransfer` needs a tag path for that.
- **Travel and reach:** reuse `performStoreTransferDetailed` for each chest.

**(B) Organize: "go organize that"** decides a destination for each stack in the bot's inventory, then deposits it with `depositMatching` for each chest. The hard part is the destination rule, which the next section covers.

## 4. Options for (B)

**Category sources.**
- *Exact item*: never wrong, but too narrow for logs or ores.
- *Vanilla item tags* (`#logs`, `#planks`, `#wool`, `#saplings`): free and version-tracked, but patchy. No tag exists for food, ores or "redstone stuff".
- *A curated list* (about 12 families built on tags plus explicit ids): readable, but every Minecraft update needs maintenance.
- **Recommendation:** a curated family table with tags inside it, as a pure `*Policy` that can be tested.

**(i) Item-frame labels.**
- *For the player:* put a frame holding oak logs on a chest, and that chest takes oak logs.
- *Sketch:* add a `ContainerLabelService` on the server that scans frames in the base box and maps them to their attached container (both halves of a double chest). A pure `ContainerLabelPolicy` maps (frame item, rotation) to a rule. Proposed rule: unrotated means the exact item, rotated means its family. No client code or networking is needed.
- *Cost:* small to medium.
- *Failure modes:*
  - A frame on the block beside the chest instead of the chest itself.
  - A frame lost to an explosion or a breaking support.
  - A chest that is full.
  - Two frames on one chest, which should count as the union of both rules.
  - The rotation convention being invisible unless the Guide documents it.
- *Multiplayer:* frames have no owner, so honour them only on containers that the ownership classification already allows.
- *Undo:* break the frame.

**(ii) A UI overlay inside the container screen.**
- *For the player:* an "allowed items" panel on the vanilla chest screen.
- *Sketch:*
  - A new client mixin on `HandledScreen`/`GenericContainerScreen`; this is the mod's first client container-screen mixin.
  - C2S and S2C payloads.
  - Rules stored per world and per container in a new store, re-validated on the server against ownership.
  - Rules kept in sync when chests are broken or merged.
- *Cost:* large.
- *Failure modes:* conflicts with other inventory mods (sort buttons, JEI/EMI overlays), rules left pointing at broken chests, and clients without the mod who see nothing.
- *Multiplayer:* the rule store needs its own permission model.
- *Undo:* a clear button in the panel, plus a data migration if the feature is later dropped.

**(iii) Per-container rules set by command or the storage screen.**
- *For the player:* `/bot storage label <category>` while looking at a chest, or a field on `BotStorageScreen`.
- *Sketch:* reuse `ChestRegistryNetworkManager` and add a rule to `ChestRecord`. The rule is server-authoritative and already permission-gated.
- *Cost:* medium.
- *Failure modes:* player chests are not in the registry, so the registry would have to admit them. That changes ownership semantics: today an unregistered chest counts as `PLAYER_STORAGE`, and a registered one changes class. The rules are also invisible in the world.
- *Undo:* clear the field. The schema change stays.

**(iv) Learned layout (the copper-golem rule).**
- *For the player:* no setup. A chest that already holds oak logs gets more oak logs. A chest holding mostly building blocks gets building blocks.
- *Sketch:* a pure policy scores each chest by its dominant family, its matching stacks and its free slots. Contents are read live on arrival. Unmatched items go to an empty chest or stay with the bot.
- *Cost:* small.
- *Failure modes:*
  - A chest with mixed junk gets more junk.
  - The first deposit into an empty chest defines it by accident.
  - Overlapping families (planks are both fuel and building material).
- *Multiplayer:* nothing new is persisted, so there is nothing new to protect.
- *Undo:* nothing to undo.

**(v) Hybrid.** Learned layout by default, with frame labels as hard overrides. A labelled chest takes only items that match its label, and learned placement never puts anything into a labelled chest that does not match. Optionally, mark one overflow chest, for example with a frame holding a chest item.

## 5. Recommendation

1. **Stage 1: multi-chest fetch (A).** This has the most value at the least risk: it reuses the transfer path, adds no schema change and needs no client code. Its pure parts are the candidate merge (like `ToolChestCandidatePolicy`), the name-to-tag resolution and the partial-result folding.
2. **Stage 2: organize the bot's own inventory, using the hybrid (v).** Build the family table and the learned scoring first, then frame overrides. Do not re-sort chests that are already full: moving the player's existing stock is the riskiest act, and it can wait for evidence that people want it.
3. **Stage 3, only on demand:** a "tidy this room" mode that also moves items between chests, then option (iii) or (ii). Skip the screen overlay unless frames prove too coarse. It is the costliest option and the only one that adds client mixins and a new persisted store.

**Trade-offs:**
- Frames are visible and need no client code, but they are coarse and the rotation convention has to be learned.
- Learned layout needs no setup, but it can only reinforce whatever mess already exists.
- A UI is the most precise option, but it is the most code, the most sync work and the most conflict with other mods.

**Rulings for Bradley:**
1. Can an owner-commanded fetch open any player chest in the base, without the per-chest prompt? *Recommended: yes. Still refuse locked, foreign, unknown-owner and protected-zone chests, and apply no reserve.*
2. What is the search area? *Recommended: the base zone if one is set, otherwise 24 blocks. Cap the walk at about 12 chests.*
3. On a shortfall, does the bot report or go gather? *Recommended: report only. Gathering is a separate command.*
4. Does "organize" cover only what the bot is carrying, or also re-sorting existing chests? *Recommended: carried items only, for now.*
5. Labels: item frames, signs or both? *Recommended: frames first. Sign keywords can come later as a Stage 3 extra.*
6. Should a frame's item mean the exact item, or its family? *Recommended: unrotated means exact, rotated means family, documented in the Guide.*
7. Where do unmatched items go? *Recommended: a marked overflow chest if one exists, otherwise an empty chest, otherwise the bot keeps them and says so.*
8. Which container types count? *Recommended: chests, trapped chests, barrels and copper chests. Never open shulker boxes, hoppers or hopper-sorter input chests (any container with a hopper attached).*
9. Whose frames count on a shared server? *Recommended: any frame, but only on a container the ownership rules already allow.*
10. Should the UI overlay be shelved until frames have been field-tested? *Recommended: yes.*
