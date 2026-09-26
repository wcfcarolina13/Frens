# Companion supplies and model switching implementation plan

> For agentic workers: use executing-plans or subagent-driven-development for each approved phase. Respect the repository limit of five changed files per phase and stop for review after verification.

**Goal:** Let companions request permission for common chest supplies, enlist a capable idle companion when blocked, and make LLM model changes understandable without restarting unchanged voices.

**Architecture:** Deterministic server-side permission and task policies control inventory and helper actions. Dialogue only presents requests. LLM generation and voice-engine lifetimes remain independent when voice configuration is unchanged.

**Tech stack:** Java 21, Fabric/Minecraft 1.21.11, existing JUnit/Mockito suite.

## Accepted design

- Nearby bots ask the nearby owner before taking exact items and quantities from a chest. Choices: Allow once, Always allow common supplies from this chest, No. No answer grants nothing.
- Common-item allowlist plus reserves; suggested defaults are 16 per material and one spare per equipment type. Named, enchanted and valuable items are excluded. Equipment tier and final allowlist remain to be settled before inventory integration; the earlier iron-tier question was not answered.
- Existing bot-placed chest records establish known ownership. Ordinary player storage has no ownership record: explicit approval grants supply authorization, not historical ownership. Conflicting ownership, locks, or unknown protection state deny access.
- Always permission is scoped to owner, world and chest. Double-chest identity and stock accounting must be consistent. Requests bind caller, bot, chest, exact item components, quantity and expiry. Recheck stock, reserves, need, reach, ownership, locks and capacity at transfer time. A replay or foreign caller cannot consume another owner's request.
- Following bots may ask for missing equipment while accompanying their owner, then continue following. Bundle needs, suppress repeated requests and do not hoard spare equipment.
- Supply lookup precedes help. Recruit at most one same-owner capable idle helper; never interrupt guarding, following, another task, combat or recovery. Reserve helper and requester atomically, cancel on changed circumstances, and prohibit recursive recruitment.
- Existing woodcut count means trees, not logs: gather at most one tree, deliver only the needed amount, retain overflow, and resume only after confirmed receipt. All inventory mutations run on the server thread.
- Shader fix remains a separate pending field test. No further graphics changes or deployment belong to these code phases.

## Phase 1: model switching (current)

Files: this plan, SoulRuntime.java, SoulRuntimeTest.java, SoulModelManagerScreen.java, changelog.md.

- [x] Add lifecycle regression tests: LLM-only reload keeps the same Piper engine alive; changed voice settings replace and close it; shutdown closes the retained engine once; post-stop reload creates no engine.
- [x] Run the focused runtime tests and capture the expected failure before production edits.
- [x] Serialize reload against shutdown. Pass the retained voice service into the replacement conversation pipeline when the immutable voice settings match and the service is healthy; close the old provider/scheduler but not the retained voice. Changed voice settings or an unavailable engine build a replacement; normal shutdown still owns full cleanup.
- [x] Label the selected model as Selected, not In use. Explain that new requests use the selected model and its first reply may take longer; do not imply a successful warm-up or initiate inference from the UI.
- [x] Run the runtime regression tests, full ./gradlew build, and git diff --check. Record results and commit the five-file phase. In-game UI/playback verification remains explicit and pending.

Verification: initial lifecycle tests failed 2/33 before the patch; review found an unavailable-engine recovery case, whose new test failed before the health guard. Final full build passed 949 tests with zero failures/errors/skips. The review also checked menu spacing. Java compilation is the type check; no ESLint is configured. No deployment or live game validation was performed.

## Following phases

Each phase gets its detailed implementation/test plan before code. These are boundaries, not permission to ship disconnected or bypassable inventory logic.

1. [x] Chest request policy and pending state: pure allowlist/reserve rules, exact request fingerprints, owner binding, expiry, rejection cooldown and replay tests. No live withdrawals yet. Done 2026-09-25 (see git log); rulings and scoping corrections below.
2. [x] Chest adapter and authorization persistence (done 1.1.219, DORMANT — no callers; see "Supplies Phase 2" below): new server request/transfer service, authorization store, dedicated command registrar, initialization hook and changelog. Reuse clickable chat choices rather than adding an unnecessary screen. Verify current Fabric lock APIs before implementation.
3. [x] (done 1.1.221; see "Supplies Phase 3" below) Close existing bypasses: ToolProvisionService raw withdrawals, ChestStoreService registered-tool withdrawals, and BotMutualAidService chest-food access route through the shared policy; integration tests cover simultaneous bots and changed stock. Restrict automatic supply discovery to supported chest containers.
4. Storage-room walkthrough: add a need-driven proximity trigger independent of the idle weapon/axe early exit, enabling missing armor requests while following. Confirm following resumes without stealing task control.
5. Helper request policy: eligibility, exclusive reservation, expiry/cancellation, one outstanding request and no recursive recruitment, with pure tests.
6. Helper execution and fallback integration: one-tree skill, safe return and atomic transfer; replace the doomed no-axe dispatch and share the capability check with the other resource-woodcut entry. Do not wrap SkillManager.runSkill in TaskService.runAmbient: both acquire task tickets.

Before structural refactors of files over 300 lines, inspect and remove proven dead code in a separate cleanup commit. Do not combine the phases or deploy a partial chest feature that leaves an automatic withdrawal bypass.

## Supplies Phase 1: chest request policy (done)

Files: `GameAI/services/supply/SupplyRequestPolicy.java` (pure rules), `SupplyRequestLedger.java` (in-memory state), `SupplyRequestPolicyTest.java`, `SupplyRequestLedgerTest.java`, and this plan. Nothing calls them yet: no world, inventory, chest, command or network code was touched. Verification: `./gradlew build -x test` passed; `./gradlew cleanTest test` ran 1122 tests (1078 before, 44 new), with 0 failures, errors or skips.

### Phase 1 rulings

- Package `net.wcfcarolina13.GameAI.services.supply`. The policy class is stateless. The ledger's public methods are all `synchronized`, and it is built with `LongSupplier clock`, `Supplier<UUID> ids`, `Timings` and `Config`. `Config` was added to the constructor because the ledger re-runs the policy at open and at consume.
- Item identity is `ItemKey(itemId, componentsFp, nonDefaultComponentIds)`. A stack whose non-default components go beyond `Config.allowedComponentIds` (default `{"minecraft:damage"}`) is `PROTECTED_COMPONENTS`, so named, enchanted, lore, custom-data and repair-cost stacks are excluded. An id missing from the allowlist is `NOT_ALLOWLISTED`, and that covers every "valuable" item. `classify` checks the allowlist first, then the tier, then the components.
- Default allowlist, with every id checked against the 1.21.11 registry:
  - Materials: cobblestone and dirt; planks of all 12 wood types; the 9 overworld logs; sticks, torches, coal and charcoal; bread, apple, baked potato and the seven cooked meats and fish; wheat and beetroot seeds, carrot and potato.
  - Equipment table: axe, pickaxe, shovel, hoe, sword and spear in the tiers wooden, stone, copper and iron; helmet, chestplate, leggings and boots in leather, copper and iron.
  - Diamond, golden, netherite and chainmail gear is not in the table, so it is `NOT_ALLOWLISTED`.
- `allowedTiers` defaults to `{wooden, stone, copper, leather}`. Leather is there as the armor counterpart of wooden; otherwise leather armor could not be allowlisted. Iron is already in the table and is `TIER_NOT_ALLOWED` by default, so allowing it takes one change: add `"iron"` to `DEFAULT_ALLOWED_TIERS`. Bradley ruled on 2026-09-25: iron stays out. Copper armor is allowed by default through the copper tier.
- Reserves: materials keep 16 of the exact item in the chest (`materialReserve`). Equipment keeps one piece of the same equipment type (`equipmentSpare`). A grant is also capped by the bot's stated need and by the number of that exact item present. `grantable(stock, reserve, requested, need) = max(0, min(requested, need, stock - reserve))`.
- `Stock(itemCount, typeCount)` is a contract for the Phase 2 adapter. Both counts cover both halves of a double chest. `typeCount` counts every stack whose id maps to the same type in the table, whatever its tier or components, and is never taken as lower than `itemCount`.
- Owner binding: a bot with no owner is `NO_OWNER`, so nothing can be requested or granted for it. Only the owner may respond. An operator who is not the owner may respond only when `Config.operatorMayApprove` is set (default false). A foreign responder gets `FOREIGN_CALLER`, and the pending entry, its cooldowns and its grant state stay unchanged.
- `ChestKey(worldId, x, y, z)`. `worldId` is `levelName/dimension` as Phase 1 wrote it; superseded in Phase 2: the adapter passes `SupplyChestRules.worldId(BotWorldStateService.currentWorldKey(server), dimension)`, because `levelName/dimension` collides across saves. `canonical(worldId, half, partnerOrNull)` takes the smaller position, compared as int tuples, so both halves give one key. A partner that is not a horizontal neighbour on the same y is ignored. That fails safe: the result is two chests and more prompts.
- `RequestFingerprint(owner, bot, chest, ItemKey item, qty)` binds owner, bot, chest, item id and component fingerprint (through `item`) plus the quantity. A single-use grant is spent only by a fingerprint that matches on every field, with `qty` no larger than the grant. A larger ask gets `OVER_GRANT` and keeps the grant. Each target holds at most one grant, and a newer grant replaces the older one instead of adding to it.
- Signatures widened from the sketch:
  - `open(fp, Stock, need)`: needs stock to return `INELIGIBLE(RESERVE_EXHAUSTED | NO_NEED)`, and cuts `fp.qty` to what is grantable. The cut fingerprint is what the prompt shows.
  - `consumeGrant(fp, Stock stockNow, needNow)` returns `Consume(status, quantity, verdict)`. It re-runs the policy against the stock at transfer time, so the reserve holds after the chest changes and under ALWAYS. `quantity` is the most the adapter may move.
- ALWAYS_COMMON (`GRANTED_ALWAYS`) grants the current request and records a permission scoped to (owner, world, chest). It stores no grant. Each consume checks the permission again, and it covers only what `classify` passes, which means allowlisted, component-clean, allowed-tier materials and equipment. Any of that owner's bots may use it at that chest. `revokeAlways` removes the permission and every unspent grant that owner left at that chest.
- Timings (defaults, all adjustable): a request lives 30 s and an expired one grants nothing; a grant lives 60 s from the answer; the prompt cooldown is 15 s per bot; the rejection cooldown is 300 s per (bot, chest, item id), covering every component variant of the item. An instant equal to a deadline counts as expired.
- `open` check order: policy verdict (owner, item, need, reserve), then ALWAYS, then rejection cooldown, then the bot's pending prompt, then prompt cooldown. ALWAYS outranks an earlier No because a covered request is never prompted, so the permission must have come after the No. The prompt cooldown starts at open and is extended from the answer or from the expiry, so a bot whose prompt is ignored waits 15 s after it lapses instead of re-prompting at once.
- `open` results: `OPENED(id) | DUPLICATE_PENDING | PROMPT_COOLDOWN | REJECT_COOLDOWN | COVERED_BY_ALWAYS | INELIGIBLE(verdict)`, with one pending prompt per bot. `respond` results: `GRANTED_ONCE | GRANTED_ALWAYS | REJECTED | EXPIRED | NOT_FOUND | FOREIGN_CALLER`. Any answer closes the prompt, so a second answer is `NOT_FOUND`.
- `clearBot` is for removing a bot, not for death or respawn, because it wipes rejection cooldowns. `clearOwner` drops that owner's prompts, grants and permissions. `sweep` drops expired entries and returns how many prompts expired.

### Scoping corrections (2026-09-25)

- Phase 3 must also route these through the shared policy: `HarvestCropSkill` (:493, :635, seed restock), `HuntSkill` (:1256-1275 weapon; :1391 food, with its own scan at :1442) and `NavigationArtifactService` (:1523-1535). The existing Phase 3 list missed them.
- Exempt as owner-initiated: `/bot withdraw` (`modCommandRegistry`:2924) and the storage screen's Quick Fetch (`ChestRegistryNetworkManager`:304).
- Bot-placed chest records take their owner from the bot's current owner (`BotChestRegistryService`:163, :341), and the two halves of a double chest are not linked. Phase 2 needs an explicit placer/owner field and a check on both halves.
- Do not copy `BotFoodGivingService`'s click binding: it removes the pending entry before the player check, so a foreign click cancels it (:133-134, :161). The ledger's `respond` rejects a foreign responder without changing anything.
- Bradley ruled on 2026-09-25 that nobody may approve for an un-owned bot, so it keeps `NO_OWNER`.

## Supplies Phase 2: chest adapter and authorization persistence (done, 1.1.219, DORMANT)

Phase 2 was built in two parts and reviewed once:
- 2a `86791185`: `ChestRecord.ownerUuid`, `recordedOwnersAt`, the ledger's `AlwaysScope`, `restoreAlways` and
  `alwaysSnapshot`, and the pure `SupplyChestRules`.
- 2b `e3732272`: `SupplyRequestService` and `SupplyCommands`.
- Review-wave fix `5c28366f`.

`request(` and `transferNow(` had no production callers. `SupplyDormancyTest` (renamed `SupplyEntryPointTest` in 1.1.221) enforced this; it also pins the
private bodies to their entry points and rejects reflective name strings. Rulings and deferrals are in the
1.1.219 changelog entry. Scope notes (local): `.superpowers/sdd/SCOPE-supplies-phase2.md`.

Rulings that Phase 3 inherits:
- Ownership over both halves: PLAYER_STORAGE and OWNER_STORAGE prompt; DENY_FOREIGN, DENY_UNKNOWN and DENY_MIXED
  refuse. Records made before 1.1.219 have no owner, so they read DENY_UNKNOWN until the chest is re-placed.
  Decide on those before wiring.
- Access refuses when: the chunk is unloaded; the block is not a chest; protected zones haven't loaded; either half is
  locked; territory denies either half; the chest is blocked; or ownership denies.
- The owner must be online and within 32 blocks. The exception is a request already covered by an Always
  permission.
- Prompts are clickable `/frens supply answer <requestId> once|always|no`, keyed by request id. A foreign click
  gets a reply and changes nothing.
- The transfer runs on the server thread in one tick: capacity, then `consumeGrant` with the quantity capped, then
  the exact move, then `markDirty`.
- The adapter never calls `clearOwner`, because that would wipe the persisted Always permissions.

Before wiring:
- decide the null-owner policy above;
- add a tick sweep of the ledger;
- stop a valid empty Always file from WARNing;
- add a revoke-all command (revoking from the surviving half of a broken double chest can't see the old key).
All four were done in 1.1.221 (Phase 3 below).

## Supplies Phase 3: every automatic withdrawal through the policy (done, 1.1.221)

Scoping (four read-only scopers) found the list above incomplete and partly wrong:
- `CraftingHelper.withdrawFromNearbyChests`, the largest site, was missing. It is reached from about 20 craft methods.
- Hunt's weapon pull was dead code.
- NavigationArtifactService's withdraw is the owner's Collect button, so it is exempt.

Also verified: `World.getBlockEntity` returns null off the server thread in 1.21.11 (javap). Every worker-thread chest
read before 1.1.221 saw nothing, so prompts during skills and `/bot craft` are new behaviour.

**What landed:**
- `SupplyWithdrawals.withdraw` is the only caller of `request`/`transferNow`. It is thread-adaptive and two-phase (ask →
  walk → redeem). WaitMode is UNTIL_ANSWERED for Woodcut start and Harvest, NONE for everything else. It pre-filters
  with the policy, keeps tickets per (bot, chest, exact item), and hops through the one abandon-safe `SupplyServerHop`.
- Sites routed through it:
  - ToolProvisionService: the idle wooden fallback, saddle, lead, fence, leather, and chest tool retrieval for Woodcut
    and Durability.
  - Harvest seed restock.
  - CraftingHelper material pulls.
  - MutualAid chest food.
- HuntSkill's container pulls are deleted.
- Discovery is chest-only everywhere.
- Every refusal carries a scope, and all three site policies apply one rule to it:

  | Scope | Site rule |
  |---|---|
  | ITEM | skip the item |
  | CHEST | skip the chest (both halves) |
  | TARGET | skip that stack only |
  | OWNER_ABSENT | skip the chest; an otherwise empty pass defers a flat 60 s, never a miss |
  | BOT | stop and pause |
  | BUSY | stop, retry soon, never a miss |
  | INVENTORY_FULL | stop; never holds the idle fallback |

- The pre-wiring decisions:
  - legacy null-owner records read as unrecorded, so they prompt;
  - a ledger tick sweep;
  - an empty Always file stays quiet;
  - `/frens supply revoke all`.
- Enforcement:
  - `SupplyEntryPointTest`: only the facade calls request/transferNow, and the facade's public surface is pinned.
  - `SupplyBypassClosedTest`: a ratchet on every file that obtains a world container, the choke points name the facade,
    and the deleted pulls stay deleted.
- "Integration tests cover simultaneous bots and changed stock" became pure sequence tests that drive the real ledger,
  ticket book and policy. The test policy forbids Minecraft types in tests. Simultaneous bots are covered by the
  per-bot ledger and the ticket keys; changed stock by the transfer-time re-check (INELIGIBLE_NOW drops the ticket).

**Deferred to Phase 4 or later:**
- Worker skills can't craft from chest materials: one prompt per bot, so a plank ask blocks the tool ask. Needs
  WaitMode through `ensure*`/`craftGeneric` plus need-bundling, and a ruling.
- `ensureCraftingStation` walks on the calling thread, including the idle tick.
- MutualAid's other make-room paths (`tryMakeSpaceForNearbyDroppedFood`, `ensureInventorySpaceForAidRecipient`) walk on
  the tick.
- `grantableEstimate` is per half.
- Three "other half" helpers.
- CraftingHelper pull hints aren't cleared at stop.
- A double chest whose other half is unloaded reads as unreadable.
- A grant-covered ask while the owner is away is refused.

Rulings and costs are in the 1.1.221 changelog entry.
