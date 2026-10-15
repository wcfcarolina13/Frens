# Dead code audit, October 2026

Tree: `src/main/java/net/wcfcarolina13` at 0cb45080 (1.1.241). Eight read-only slice audits (core-souls, chat-llm, services-sub, commands-config, skills, gui-network, services-lz, services-ak) were challenged item by item with whole-word greps across `src/main`, `src/test`, `src/main/resources` (including `frens.mixins.json` and `fabric.mod.json`), method references, reflection strings, overloads and `@Override` chains. `PathFinding/*` is out of scope: `docs/testing/NAV_1.1.242_MERGED_FINDER_DESIGN.md` section 12 lists what the merged finder makes redundant in `MovementService` and `PathFinder`, and slice D of that build deletes it. Nothing here duplicates that table.

No source file was modified. Nothing was committed.

## 1. Summary

| Rank | Items | Lines (approx.) | What it means |
|---|---|---|---|
| Live bugs | 16 | n/a | Wrong behaviour the audit found while reading. Fix before deleting anything nearby. |
| SAFE-DELETE | 16 phases, about 90 source files touched | ~4,100 | Zero callers after the challenge. Deletable in phases of at most 5 source files. |
| MERGE | 12 | ~735 lines saved | Duplicate bodies with a clear target helper. Behaviour-preserving if done as described. |
| NEEDS-BRADLEY | 17 questions | ~5,600 in play | Dormant features, legacy paths and persisted keys. Each question has a recommended answer. |
| Challenged and demoted | 9 | ~200 lines left SAFE-DELETE, ~400 of MERGE made conditional | Auditor claims that did not survive the greps, or that need a gate the auditor did not see. |

Verification rule for every phase: full `gradle test`, plus a sandbox smoke on the Prism `1.21.11 TEST` instance, plus the file-specific gates named in the phase. Two tests read the source tree as text and pin counts: `SupplyBypassClosedTest.RATCHET` holds an exact count of world-container access sites per file (CraftingHelper 3, SmeltingService 10, ChestStoreService 5, ToolProvisionService 1, FishingSkill 2, WoolSkill 1, MiningHazardDetector 2, others). Any phase that removes a `getBlockEntity(...) instanceof Inventory|ChestBlockEntity|...` line in one of those files must lower that entry and say why, or the suite goes red. That RATCHET edit lands in the same commit as the deletion and is a gate update, it does not count toward the five source files of a phase (Phases 5 and 7 and MERGE M2 carry it). `WoodcutCleanupSkillTest` reads `WoodcutSkill.java` and `WoodcutCleanupSkill.java` and asserts `WoodcutSkill.PILLAR_BLOCKS.contains(item)` stays, which no item here touches. `MutualAidChestFoodPolicyTest` and `CraftingStationPolicyTest` only assert absence of names inside specific method bodies, so deletions cannot break them.

Provenance note: the package rename to `net.wcfcarolina13` landed in b5479ada (2026-02-21), so `git log -- <path>` on any file stops there. Use `git log --follow` to see history under the old `net.shasankp000` path.

## 2. Live bugs (ranked by severity)

**Status 2026-10-15 (overnight run):** B1 (Fortify and ReturnBaseStuck sites), B2, B3, B4, B5, B7 and B8 are fixed in `6fc7812f` (thread hops or `state.hasBlockEntity()`, ratchet counts unchanged). `MiningHazardDetector:294` was left as is on purpose: the `CHEST_BLOCKS` check above it already catches chests, so swapping the tail to `state.hasBlockEntity()` would make mining stop at every furnace or sign with "I found a chest!". The rest wait for Bradley's answers in section 5.

B1 to B5 are one family (worker-thread `getBlockEntity` reads) and are kept together so one fix pattern covers them. Within the family B2 is the highest single item. B6 onward are in severity order.

Thread facts that several entries rest on: `World.getBlockEntity` returns null off the server thread in 1.21.11 (CHANGELOG line 588, javap-verified for 1.1.221). Skills run on `mod-command-skill-N` worker threads (`modCommandRegistry.java:157`, `SkillManager.java:229` attaches the executing thread). Brigadier command bodies run on the server thread. `BlockState.hasBlockEntity()` is a pure state read and is already used at `SoulSnapshotBuilder.java:327` and `HovelPerimeterSafetyService.java:75`. The reference hop for a real block-entity read is `ShelterSkill.java:840` (`callOnServer(world, () -> world.getBlockEntity(pos))`, a private helper in that skill). `BotActions.callOnServerThread` (`BotActions.java:163`) is also `private static`. Both are patterns to copy into the file being fixed, or `world.getServer().submit(...).join()` directly.

### B1. MEDIUM. Fortify navigation's block-entity guard never fires (furnaces, hoppers, signs, brewing stands, campfires, beehives are breakable)

- `FortifyVillageSkill.java:3883` and `:3897`: `if (world.getBlockEntity(pos) != null) return ... BLOCK_ENTITY` inside `evaluateNonLayoutBreakForNavigation` and `evaluateLayoutBreakForNavigation`.
- Thread: both are reached only through `evaluateBreakForNavigation` (`:3904`, `@Override` of `FortifySkillOps:79`), called from `FortifyCarveHelper.java:278/296/320` and `FortifyEscapeHelper.java:247`, all on the skill worker. There is no server hop anywhere in `FortifyVillageSkill.java`.
- Effect: the guard never fires. `digBlock` (`:3742`) has no block-entity check of its own and `DIG_BLACKLIST` (`:3731`) is only BEDROCK and LECTERN. Chests, trapped chests, barrels, ender chests, beds, shulker boxes and protected glass are still refused one layer down by `MiningTool.isNeverMineBlock` (`MiningTool.java:360`, applied inside `mineBlock` at `:151` and `:257`), which is why this is MEDIUM and not SAFETY. Every other block entity (furnace, smoker, blast furnace, hopper, dispenser, dropper, brewing stand, sign, campfire, jukebox, beehive, decorated pot, chiseled bookshelf) is a legal navigation break for the carve and escape helpers. Village adjacency (`:3886`) needs two structure neighbours and does not cover a lone furnace.
- Smallest fix: replace both reads with `state.hasBlockEntity()` (the `state` local is already in scope on both lines). No hop, no ratchet change. Same substitution recommended at `ReturnBaseStuckService.java:1827` (`canMineBlock`, thread unverified) and `MiningHazardDetector.java:294` (worker scans, the `CHEST_BLOCKS` check at `:291` already covers chests and barrels so the block-entity tail is redundant anyway, keep the ratchet count at 2 or lower it with a reason).

### B2. HIGH. Honey hobby never harvests

- `HoneyCollectSkill.java:193`: `be instanceof BeehiveBlockEntity hive && hive.isSmoked()` inside `isHarvestable`, called from `:96`, `:105`, `:179` in the skill body (worker thread, no hop in the file).
- Effect: `getBlockEntity` is null, so `isHarvestable` is always false and every hive is skipped.
- Smallest fix: `boolean smoked = world.getServer().submit(() -> world.getBlockEntity(pos) instanceof BeehiveBlockEntity h && h.isSmoked()).join();` inside `isHarvestable` (the `world` parameter is already there, no bot needed). `BotActions.callOnServerThread` is `private static`, so it is a pattern to copy, not an API to call, unless this fix widens it.

### B3. MEDIUM. Fishing auto-cook always says "That furnace is unavailable."

- `SmeltingService.java:399`: `var be = world.getBlockEntity(pos)` in `startBatchCookInternal`, before the method's own `server.execute` at `:450`.
- Paths: `/bot cook` → `executeCook` (`modCommandRegistry.java:3184`) runs on the server thread, fine. `FishingSkill.java:627` → `maybeCookFish` (`:2392`) → `SmeltingService.startBatchCook` (`:2399`) runs on the fishing worker, so the hobby path hits the null and bails with the message. Only the fishing hobby is affected.
- Smallest fix: in `maybeCookFish`, run the call through `bot.getEntityWorld().getServer().submit(() -> SmeltingService.startBatchCook(...)).join()`. The command path already runs the whole method on the server thread, so this is the proven shape. Ratchet count for SmeltingService is unchanged.

### B4. MEDIUM. Fishing never finds a deposit chest

- `FishingSkill.java:1059` (`findChestWithSpaceNear`, `:1045`) and `:1142` (`scanForChestWithSpace`, `:1123`): `world.getBlockEntity(pos) instanceof Inventory chest`, reached from `findNearbyChestWithSpace` (`:1097`) at `:989` in the skill body, worker thread.
- Effect: every candidate chest fails the `instanceof`, so the home and ring chest scans return null and the catch stays in the inventory.
- Smallest fix: wrap each scan loop's `getBlockEntity` in `callOnServer` as `ShelterSkill.java:840` does. Keep the literal text `world.getBlockEntity(pos) instanceof Inventory chest` inside the lambda so `SupplyBypassClosedTest` still counts 2 sites for FishingSkill.

### B5. MEDIUM. Wool skill chest deposit is a silent no-op

- `WoolSkill.java:602`: `world.getBlockEntity(chestPos) instanceof ChestBlockEntity c ? c : null`, then `if (chest == null) return` with no message. Worker thread, no hop in the file. The bot walks to the chest (`:597`), announces nothing, keeps the wool.
- Smallest fix: same hop as B4. Ratchet count 1 unchanged if the literal stays inside the lambda.

### B6. MEDIUM. `/bot nav_mode` stores a value nothing reads

- `BotHomeCommands.java:348` → `modCommandRegistry.executeNavModeSetTargets` (`:7692`) → `BotHomeService.setNavMode` (`:1113`) writes `WorldData.navModeByBot` (`:1128`, persisted at `:2022`).
- `BotHomeService.getNavMode` (`:1094`) has zero callers. `NavigationArtifactService` builds `PendingTravel` (`:1101`) without consulting it. The command replies "walk" or "fast travel" (`:7698`) so the player believes it took effect. CHANGELOG line 8605 (2026-03-13) describes the feature as working.
- Smallest fix: either read `getNavMode` where `NavigationArtifactService` chooses teleport-delay versus walk and route WALK through `setReturnToBase` as the changelog says, or delete the command, `setNavMode`, `getNavMode` and the `navModeByBot` field (about 60 lines, the JSON key is tolerated by Gson on load). Bradley's call, see Q6.

### B7. LOW-MEDIUM. Saved mount can be auto-cleared because the success path never resets the stale streak

- `RideSyncService.java:1839` `noteSuccessfulMountResolve` has zero callers. CHANGELOG for 1.1.103 (be2e52bb) says it was to be wired.
- `STALE_REJECT_STREAK` (`:1831`) is incremented at `:1884` for stale reasons (`isStaleRejectReason`, `:1847`: state-too-far, mount-not-found-in-world, world-mismatch, outside-combined-radius) and reset at `:1892` on a non-stale reason, but never on success. Five stale rejects spread across a session with only successes in between reach `STALE_REJECT_CLEAR_THRESHOLD` and `MountPersistenceService.clearRecordedState` (`:1887`) forgets the mount.
- Smallest fix: one line, `noteSuccessfulMountResolve(bot.getUuid());` before `return preferred;` at `:1821` in `resolvePreferredMount`.

### B8. LOW. `/bot detect_blocks` throws when nothing matches

- `modCommandRegistry.java:1164`: `blockDetectionUnit.detectBlocks(bot, blockType)` returns null on a miss (`blockDetectionUnit.java:48`, `:79`), then `:1165` dereferences `outPutPos.getX()`. NPE surfaces as a command error. Dev subcommand, see Q4.
- Smallest fix: null check with a chat line, or delete with the other debug subcommands.

### B9. LOW. Preview-lock keybind migration is a no-op in production

- `FrensClient.java:2478` `migrateLegacyPreviewLockBinding` resolves `KeyBinding.getMethod("getDefaultKey")` and `"setBoundKey"` by yarn name. In the shipped jar those names are intermediary, so `NoSuchMethodException` is swallowed at `:2499` and the user keeps `L` (which collides with Advancements). `invokeFirstNoArgMethod(KeyBinding.class, "updateKeysByCode")` (`:2495`) looks the method up on `java.lang.Class`, so it can never succeed even in dev.
- Smallest fix: the migration dates from 2026-03-06, so delete `migrateLegacyPreviewLockBinding`, `invokeFirstNoArgMethod`, the call at `:685` and the `previewLockBindingMigrationChecked` flag (about 75 lines). If keeping it, call `KEY_LOCK_PREVIEW.setBoundKey(KEY_LOCK_PREVIEW.getDefaultKey())`, `KeyBinding.updateKeysByCode()` and `client.options.write()` directly.

### B10. LOW. Eye of Ender spell cooldown hint never arms on the client

- `FrensClient.java:341` `armEyeSpellCooldown` has zero callers, so `isEyeSpellOnCooldown()` at `:2038` is never true and the actionbar hint never shows. The server enforces the real cooldown at `modCommandRegistry.java:5381` (`COMPANION_EYE_SPELL_LAST_TICK`), so this is lost feedback, not lost safety.
- Smallest fix: call `armEyeSpellCooldown()` where the client learns the cast succeeded, or delete the client-side cooldown fields, `armEyeSpellCooldown`, `getEyeSpellCooldownRemainingMs`, `isEyeSpellOnCooldown` and the `:2038` branch (about 20 lines).

### B11. LOW (legacy LLM path only). Static init state is shared across bots

- `ollamaClient.java:41-42` (`botName`, `isInitialized`) and `LLMServiceHandler.java:39-42` (`initialResponse`, `isInitialized`) are static. `initializeOllamaClient` (`:170`) returns early once any bot initialised, the "is ready!" line (`:197`) prints whichever `botName` was set last, a non-timeout init failure rethrows inside `CompletableFuture.runAsync` (`:209`) where nobody observes it, and callers that wait on `isInitialized` spin if init failed.
- Smallest fix: key the state per bot UUID in a map, or retire the path (Q2). Only reachable with the "LLM World (legacy)" master and per-bot "LLM Chat" on.

### B12. LOW (legacy LLM path only). Clarification state is write-only

- `ChatContextManager.setPendingClarification` is called at `FunctionCallerV2.java:1657`, `:2154`, `:2254`. `getPendingClarification`, `clearPendingClarification` and `isAwaitingClarification` (`ChatContextManager.java:17/21/25`) have zero callers. `pendingClarifications` (`:10`) is an unbounded, unsynchronised `HashMap` written from LLM worker futures.
- Smallest fix: delete the three setters, `ClarificationState` and the map (about 25 lines), or wire the read in the chat handler. The confirmation half of the class is live (`FunctionCallerV2.java:1775-1852`).

### B13. LOW. `BaseSetOwnerPayload` can never be sent

- Registered C2S at `Frens.java:595`, receiver at `BaseNetworkManager.java:587-625`, record at `network/BaseSetOwnerPayload.java`. `new BaseSetOwnerPayload(` appears nowhere, so no client ever sends it. `BotHomeService.setBaseOwner` (`:1287`) is reachable only from that receiver. CHANGELOG line 7384 (2026-04-16) deferred the ownership UI to "Phase 3.5".
- Effect: admins cannot claim a legacy ownerless base. See Q9.

### B14. LOW. Recorded combat lines that can never play

- `BotCombatCalloutService.noteCombatSituation` (`:210`) has zero callers. `maxDangerousMobCount` is clamped to at least 1 at `:202`, `:294`, `:348` and only `noteCombatSituation` can raise it, so the `>= 3` branch at `:512` (`POST_COMBAT_MULTI_LINES`) is unreachable.
- `onBotHitPlayer` (`:642`) has zero callers, so the friendly-fire lines (CHANGELOG 7898, recorded 7185) never play even though melee friendly fire is still possible.
- Smallest fix: call `noteCombatSituation(bot, hostiles)` from the hostile scan that already calls `noteCombatStart`, and `onBotHitPlayer` from the melee damage hook. Or delete both with their line pools (Q8).

### B15. LOW. Pending feed prompts leak until server stop

- `BotFoodGivingService.clearPendingForBot` (`:308`) and `clearPendingForPlayer` (`:313`) have zero callers, so `PENDING_FEEDS` entries for removed bots and disconnected players stay until the server stops. Small map, harmless, but the cleanup hooks were written and never wired. Same shape: `HealingService.clearWarnings` (`:589`), `BotPowderSnowRescueService.resetState` (`:383`).
- Smallest fix: call the two clearers from the bot-removed and player-disconnect handlers in `BotEventHandler`.

### B16. LOW. `/bot config` has two identical branches

- `configCommand.java:21-30`: the `EnvType.SERVER` and client branches both call `configNetworkManager.sendOpenConfigPacket(player)`, and `player` is not null-checked for a console sender. Collapse to one call behind `if (player == null) return 0`.

Not bugs, listed because an auditor flagged them: `ChatUtils.java:80` drops bot chat the controller cannot receive through `CompanionCommunicationPolicy.canBotChatToController` (a visibility rule, by design). `APIKeysScreen.java:187` sends no packet in ollama mode (nothing to save). `ToolProvisionService.java:1586` `scanContainers` is the ratchet's single allowed site for that file and is reached through `SupplyServerHop` by design (`SupplyBypassClosedTest` forbids private hops in that file), so no thread bug there.

## 3. SAFE-DELETE, in removal phases

**Progress:** Phases 1, 2, 3 done in 1.1.243 (`a7207e9d`, `3c4c8a25`, `9bf65447`; `generatePromptContext` in FunctionCallerV2 also went, its only caller was the dead save path). Q1 (RL, training as admin alias), Q4 (dev subcommands, testgear and test_chat_message behind `/bot debug`) and Phase 11 done in 1.1.244. Correction: the BotEventHandler RL range in Q1 is not contiguous, `tickDrowningRescue`, `tickHunger`, `tickDurabilityArmorAudit` sit inside 862-1092 and are live. Phases 4 to 8 done in 1.1.245 (CraftingHelper ratchet went 3 -> 1, not 2: `depositIntoChests` and its private helpers were also dead. SmeltingService stayed 10.) Phases 9, 10 and Q6, Q7, Q11, Q13 (village import, mount `_legacy` write), Q15, Q16 done in 1.1.246. Phases 13 to 16, Q17 and the Q5 deletions done in 1.1.247. Section 3 is complete. Q8, Q9, Q14 shipped in 1.1.248 (Q14 clears on fence tie and death, not on force-dismount, see changelog). Q5 wall-off rebuild shipped in 1.1.249.

Each phase is at most 5 files and one commit. Evidence column: W = whole-word grep found only the declaration (and internal callers that die with it), T = no test references it, R = no reflection or string-literal reference. Every item below passed all three unless noted. Line counts are approximate and include javadoc.

Recommended order: fix B1, B2, B7 first in their own commits, then phases 1 to 16 in order. Phases 1 to 3 are whole-file deletes with no behaviour surface and make the best first checkpoint.

### Phase 1. Legacy RAG and SQLite chain (cross-slice: chat-llm plus commands-config), ~1,190 lines

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `OldRAGImplementation` whole file | `ChatUtils/Helper/OldRAGImplementation.java` | 565 | W T R. Referenced only by `helperMethods`. 0 CHANGELOG hits. |
| `helperMethods` whole file | `ChatUtils/Helper/helperMethods.java` | 408 | W T R. Referenced only by `OldRAGImplementation` (static import `:24-25`). Closed pair. |
| `OldSQLiteDB` whole file | `Database/OldSQLiteDB.java` | 143 | W T R. Callers: `OldRAGImplementation:520` (dead) and `FunctionCallerV2:225` (dead chain below). `createDB` (`:18`) never called, the live one is `SQLiteDB.createDB` (`Frens.java:831`). |
| `getFunctionResultAndSave` + `ExecutionRecord` | `FunctionCaller/FunctionCallerV2.java:2391-2422`, `:204-230` | ~60 | W. `getFunctionResultAndSave` has 0 callers, it is the only constructor call of `ExecutionRecord` (`:2412`), whose `updateRecords` (`:223`) is the only `OldSQLiteDB` use. Cascade: `import OldSQLiteDB` (`:79`), `DB_URL` (`:104`, only used at `:225`), check `SQLException` and `OllamaModelType` imports afterwards. `functionOutput` (`:110`) stays, it is live at `:273`, `:2172-2199`. |
| `getSharedState` | `FunctionCallerV2.java:200-202` | 3 | W. Only a comment at `modCommandRegistry.java:8428` mentions it. Superseded by `SharedStateService` (1.1.200). `sharedState` field stays. |
| `OutputVerifier` whole file | `FunctionCaller/OutputVerifier.java` | 13 | W T R. Interface with no implementor. |

Gate: `SharedStateServiceTest` scans for `FunctionCallerV2.` in SkillContext construction, which this phase does not touch.

### Phase 2. Dead whole files, misc, ~85 lines plus mixin entry

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `ServerConfigUtil` | `FilingSystem/ServerConfigUtil.java` | 45 | W T R. Wrote `selectedLanguageModel`, superseded by `ManualConfig`. |
| `KeyboardMixin` | `mixin/KeyboardMixin.java` | 14 | W T R. Empty final class, not listed in `frens.mixins.json`. |
| `ExampleMixin` + json entry | `mixin/ExampleMixin.java`, `src/main/resources/frens.mixins.json:6` | 14 | Template mixin. Delete both in the same commit or Mixin fails at boot. All other mixin targets were javap-verified by the commands-config auditor. |
| `BotInventoryScreen` stub | `ui/BotInventoryScreen.java` | 11 | W T R. "legacy stub" in the wrong package, b5479ada. |

`StateActionTransition` (`Database/StateActionTransition.java`, 166 lines, 0 references) is also deletable now but is the Serializable chunk format for Q-tables, so it is parked under Q1 and goes with the RL decision either way.

### Phase 3. Block knowledge chain, ~191 lines plus resource

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `BlockKnowledgeRetriever` | `GameAI/Knowledge/BlockKnowledgeRetriever.java` | 49 | W T R. Only self-references. Upstream 7c20d2cb. |
| `BlockKnowledgeBase` | `GameAI/Knowledge/BlockKnowledgeBase.java` | 82 | W T R. Only caller is the retriever. Reads `/block_metadata.json` (`:22`). |
| `BlockMetadata` | `GameAI/Knowledge/BlockMetadata.java` | 14 | W T R. Record used only by the two above. |
| `OreYLevelKnowledge` | `GameAI/Knowledge/OreYLevelKnowledge.java` | 46 | W T R. |
| `block_metadata.json` | `src/main/resources/block_metadata.json` | 29 | The string `block_metadata` appears only at `BlockKnowledgeBase.java:22`. Not in `fabric.mod.json`. |

Keep `GameKnowledgeGraph` and `GameKnowledgeGraphBuilder` (live).

### Phase 4. Construction services, dead halves, ~605 lines

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `PerimeterService`, everything except `getCornerPositions(center, rx, rz, y)` (`:65`) | `services/construction/PerimeterService.java` | ~330 of 392 | W. The only external call is `BuildSchematicSkill.java:921`. Dead: `getCornerPositions(c,r,y)` `:80`, `getPerimeterPositions` x2 `:88/:116`, `getExteriorPerimeter` `:124`, `getInteriorPositions` `:131`, `generateBuildStations` `:155`, `isCornerPosition` `:185`, `isPerimeterPosition` `:194`, `walkExteriorPerimeter` `:204`, `visitBuildStations` `:231`, `escapeToExterior` `:265`, `isInsideStructure` `:304`, `findNearbyStandable` `:313` (public, 0 external callers, the Hovel code has its own), `BuildStation` `:40`, `StationType` `:49`, `PerimeterCallback`/`StationCallback` `:345/:350`, `isStandable` `:356`, `moveToPosition` `:366`, `sleepQuiet` `:385`, `REACH_DISTANCE_SQ` `:33`. 0 CHANGELOG hits. |
| `RoofAccessService` dead walkers | `RoofAccessService.java:140` `walkRoofPerimeter`, `:171` `walkRoofSerpentine`, `:288` `PlaceCallback`, `:294` private `buildRoofPerimeter`, `:33` `REACH_DISTANCE_SQ` | ~105 | W. Live: `clearRoofPillars`, `buildRoofAccessPillar`, `descendFromRoof`, `cleanupAllRoofPillars`, `directWalkTo` (BuildSchematicSkill `:371-770`). |
| `DoorwayAccessService` dead analyzers | `:90` `analyzeDoorway`, `:377` `hasExteriorAccess`, `:393` `hasInteriorAccess`, `:414` `prepareDoorwayForDoor` | ~85 | W. Live: `clearAllDoorways`, `detectDoorways`, `Doorway`, `AccessResult`. |
| `DoorPlacementService` dead | `:243` `navigateThroughDoor`, `:279` `calculateDoorPosition`, `:42` `REACH_DISTANCE_SQ` | ~45 | W. Live: `hasDoorItem`, `placeDoor`. |
| `ConstructionBlueprintService.generateBuildStations` + `getOutwardDirection` | `:299`, `:358` | ~40 | W. Second dead station generator, `getOutwardDirection` is called only from it (`:318`). |

### Phase 5. Services A to K, dead bodies, ~350 lines

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `CraftingHelper.stashInChest` `:2799`, private `placeChestNearBot(ServerWorld, bot, reachSq)` `:3287`, `tickFollowStation` `:3913-3947`, `offloadMatchingItems` `:3036` | `services/CraftingHelper.java` | ~141 | W. `placeChestNearBot`'s only caller is `stashInChest:2820` (the public `ChestStoreService.placeChestNearBot` with 8 callers is a different method). Already called dead in CHANGELOG line 563 (1.1.222). The worker sleep at `:3936` and the `getBlockEntity` at `:3305` go with them. Gate: lower `SupplyBypassClosedTest.RATCHET` CraftingHelper from 3 to 2 with the reason "placeChestNearBot private overload deleted". |
| `BotCombatCalloutService` `pickThreatLine` `:728`, `pickEngagementLine` `:739`, `pickDamageLine` `:750`, `pickLowHealthLine` `:761`, `pickCombatEndLine` `:934` | `services/BotCombatCalloutService.java` | ~55 | W. Carry `@SuppressWarnings("unused")`, replaced by weighted pools (CHANGELOG 8104). |
| `ChestStoreService` private `findNearbyChest` `:545`, private `hasAnyAdjacentStand` `:652`, public `probeDepositMatchingWalkOnly` `:891` | `services/ChestStoreService.java` | ~87 | W. `WoolSkill` has its own `findNearbyChest`, `CraftingHelper` its own `hasAnyAdjacentStand`. Keep `DepositProbeResult` (used by `HarvestCropSkill:602`, `WoodcutSkill:3362`). Gate: run `SupplyBypassClosedTest`, if `findNearbyChest` or the probe held a container site, lower ChestStoreService from 5 with a reason. |
| `BotRescueService.ensureRescueTool` `:832`, `alertSuffocation` `:889` | `services/BotRescueService.java` | ~42 | W. |
| `BotMutualAidService.approachAndInteract` `:981` | `services/BotMutualAidService.java` | 26 | W. |

### Phase 6. Services A to K, zero-caller accessors, ~100 lines over two commits

6a (5 files): `BotBeehiveRegistryService.listBeehives` `:277` (keep `BeehiveRecord`, 11 internal uses), `BotCampfireAvoidanceService.isCampfireBlock` `:274`, `BotChestRegistryService.captureContents` `:477` (keep `ItemSnapshot`, used by `WoodcutSkill:3536`, `ToolProvisionService:21`), `BotStandDownService.isStandingDown` `:62`, `CommanderActivityService.isPlayerRecentlyMining` `:41`.

6b (5 files): `DangerousPursuitGate.isCombatPursuitSafe` `:153`, `DropSweepService.getSuppressionRemainingMs` `:85`, `HealingService.isEmergencyHungry` `:184` (keep `HUNGER_EMERGENCY`), `HotbarLockService.getReason` `:84`, `HuntHistoryService.hasAnyFoodKill` `:182` and `hasFoodKill` `:204`.

All W T R.

### Phase 7. Services L to Z, dead provisioning and smelting, ~370 lines

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `ToolProvisionService` `ensureBucket` `:503`, `ensureShield` `:529`, `ensureFurnace` `:629`, `ensureDoor` `:681`, `ensurePlanks` `:743` and private helpers `canCraftBucket` `:1011`, `canCraftShield` `:1015`, `canCraftFurnace` `:1003`, `canCraftDoor` `:1023`, `canCraftPlanks` `:1057`, `hasCobbleMaterials` `:1193` | `services/ToolProvisionService.java` | ~163 | W. Each helper's only caller is its `ensure*`. Never called since ecc483ec (2026-01-07). No container site among them, ratchet stays 1. |
| `SmeltingService` `maybeLoadFuel` `:1329`, `findCookablesForItem` `:1445`, `topUpInput` `:1554`, private `moveNearBlock` `:1803`, `hasCookableInventoryItems` `:603` | `services/SmeltingService.java` | ~102 | W. `CraftingHelper.moveNearBlock` is a different private. Gate: `maybeLoadFuel` and `topUpInput` touch furnace inventories, so lower the SmeltingService ratchet (10) by the sites removed with a reason. |
| `SleepService.interactBed` `:297`, `findNearestBed` `:491` | `services/SleepService.java` | ~55 | W. Replaced by direct `trySleep` and `findNearbyBedFeet` `:426`. |
| `ProtectedZoneService.removeZoneByPosition` `:296`, `clearWorld` `:605` | `services/ProtectedZoneService.java` | ~28 | W. `clearAll` `:613` is the live one. |
| `MountPersistenceService.recordMountAtPosition` `:102` | `services/MountPersistenceService.java` | ~24 | W. All paths use `recordMount` (6f49e866). |

### Phase 8. Services L to Z, small leftovers, ~55 lines

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `RideSyncService.maybeAnchorLeashedMount` `:4178`, `MINECART_RESYNC_COOLDOWN_TICKS` `:70` | `services/RideSyncService.java` | ~14 | W. Live anchor path is `:3891`. `clearLeashTarget` `:159` is NOT in this phase, see demotion D3. |
| `MovementService.isGoToSuccess` `:4637`, `clearFailedRoutes` `:1442`, `LeafClearResult.shouldShortCircuitFollowTick` `:245` | `services/MovementService.java` | ~18 | W. The skills have their own private `isGoToSuccess`. Not in the NAV design doc's section 12 table, but `MovementService` is under active rewrite for 1.1.242, so do this phase after that build lands to avoid a merge conflict. |
| `ReturnBaseStuckService.findToolSlot` `:1932` | `services/ReturnBaseStuckService.java` | 9 | W. |
| `TaskService.markOpenEnded` `:457` | `services/TaskService.java` | 5 | W. |
| `NavigationArtifactService.getPendingTravel(UUID)` `:672` | `services/NavigationArtifactService.java` | 3 | W. |

### Phase 9. Core (BotActions, BotEventHandler) and two one-liners, ~100 lines

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `BotActions.cancelCommittedReposition` `:3235`, `findWeaponSlot` `:1505` + `isLikelyWeapon` `:1642` (callers lost at e5e7fb1a, `meleeWeaponScore` is the live path), `hasAnyTrident` `:1655`, `isRangedLineCurrentlyBlocked` `:3208` (private `isRangedLineBlocked` is live) | `GameAI/BotActions.java` | ~45 | W. |
| `BotEventHandler.assessImmediateThreat` `:1250` (55588446 removed spartan mode, keep `shouldEnterCombat`), `setFollowModeWalk` `:1853` (superseded by `setComeModeWalk` `:1888`, ba36fe16), `isPassiveMode` `:2287`, `shouldPlanFollow` `:7483` (keep `FollowStateService` maps), `findNearestItem` `:7512` (superseded by `findNearestDrop`/`DropSweeper`), `FOLLOW_DOOR_STUCK_JUMP_TICKS` `:159` (unread alias of `FollowDoorRules.STUCK_JUMP_TICKS`) | `GameAI/BotEventHandler.java` | ~45 | W. |
| `SoulSideChannelOps.SENTINEL` `:34` | `GameAI/souls/SoulSideChannelOps.java` | 1 | W. Unread duplicate of `SoulGroupResponseValidator.SIDE_CHANNEL_SENTINEL` `:42` (both `"##FRENS"`). |
| `PlayerAllianceService.lookupName` `:220` | `services/PlayerAllianceService.java` | 4 | W. Listed as API in v1.1.30, never called. |
| `SurvivalRecruitmentService.defaultAdminPermissionGlobals` `:329` | `services/SurvivalRecruitmentService.java` | 4 | W. |

### Phase 10. GUI and network, ~55 lines

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `BotPlayerInventoryScreen` private `WOODCUT_TREE_COUNT_DEFAULT` `:111`, `getFollowEntryIndex` `:5028`, `getWoodcutEntryIndex` `:5032` (39b39bbd), `canOpenSpellsMenu` `:5393` (wrapper only, its helpers are live), `hasItem(MinecraftClient, Item)` `:5522` | `GraphicalUserInterface/BotPlayerInventoryScreen.java` | ~32 | W. Other classes have their own `hasItem`. |
| `BotPlayerInventoryScreenHandler.getBotTotalExperience` `:331`, `isBotTaskActive` `:386`, `getSectionWidth` `:418`, `getBlockGap` `:422`, `PLAYER_SLOT_COUNT` `:41` | `ui/BotPlayerInventoryScreenHandler.java` | ~14 | W. The `botStats` slots are still filled server-side, only the getters are unread. |
| `BotSkinNetworkManager.LOGGER` `:22` | `network/BotSkinNetworkManager.java` | 1 | Single occurrence in file. |
| `NavigationHudOverlay.handleKeyPress` `:84` | `GraphicalUserInterface/NavigationHudOverlay.java` | 7 | W. Same logic is inline at `FrensClient.java:693-698`. |

### Phase 11. Commands and config, ~70 lines

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `FrensClient.renderSchematicPreviewTick` `:3155` | `FrensClient.java` | ~10 | W. Private stub. |
| `modCommandRegistry.getIssuerOrNull` `:2135`, `executeInventorySummaryTargets` `:6423` | `Commands/modCommandRegistry.java` | ~30 | W. Both private. |
| `ManualConfig.setBotSpawnPoints` `:823`, `setBotSpawn(alias, spawn)` `:829`, `getBotSpawn(alias)` `:838`, `setBotControls` `:1311` | `FilingSystem/ManualConfig.java` | ~20 | W. All `@Deprecated`. Gson serialises fields, not setters, and `getBotSpawn(alias, worldKey)` (`:857`) does not fall back to the 1-arg form. Keep `getBotSpawnPoints`, `getBotControls` (the fields are in saved JSON). The two 1-arg control accessors are NOT here, see D1. |
| `LauncherEnvironment.supportsSharedStorage` `:156` | `LauncherDetection/LauncherEnvironment.java` | ~25 | W. |
| `BlockDistanceLimitedSearch.detectNearbyBlocksAsync` `:86` | `PlayerUtils/BlockDistanceLimitedSearch.java` | 6 | W. |

### Phase 12. Remaining utilities and legacy-chat smalls, ~110 lines over three commits

12a is the first two rows (2 files). 12b and 12c split the third row's 9 files into 5 and 4. Only do 12b and 12c if Q2 keeps the legacy LLM path. If the path is retired, `ollamaClient.java` and most of `ServiceLLMClients` go whole and that row is moot.

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `CombatInventoryManager.tryConsumeIfNeeded` `:94-129` | `PlayerUtils/CombatInventoryManager.java` | 36 | W. |
| `ThinkingStateManager.getBotName` `:35`, `isCollapsed` `:39`, `toggleCollapsed` `:43`, `isThinking` `:27` | `Overlay/ThinkingStateManager.java` | ~12 | W. The class itself is live: writers at `LLMServiceHandler:108-115`, `RAG2:85-92`, `ollamaClient:292`, reader `ReasoningLogScreen:43`. |
| `ollamaClient.runFromChat` `:53`, `pingOllamaServer` `:164`. `LLMServiceHandler.runFromChat` `:200`, `host`/`ollamaAPI` `:40-41`, `initialResponse` `:39/:146` (write-only), empty `runAsync` stub `:176-179`. `ChatUtils.getRandomColorCode` `:396`, `BotDialogueSounds.getByPath` `:989`, `DialogueTextMapper.hasMapping` `:841`, `EmotionalState.getPriority/isDistressed/isPositive` `:67-84`, `BertModelManager.getTopPredictions` `:167` and `closeModel` `:220`, `OpenNLPProcessor.detectSentences` `:43`, `ChatContextManager.isAwaitingConfirmation` `:74` | 9 files | ~60 | W (file-scoped: `LLMServiceHandler.ollamaAPI` has no external reference, the other 7 `ollamaAPI` hits are other classes' own fields). Commits 12b (5 files) and 12c (4 files). |

### Phase 13. Woodcut, tree detection, dirt and farm skills, ~510 lines

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `WoodcutSkill` entry-terrain restore no-op chain: `recordTemporaryEntryTerrainRepair` `:573` (0 callers, the only writer of `temporaryEntryTerrainRepairs` `:503`), `consumeTemporaryEntryTerrainRepairs` `:580`, `pendingTemporaryEntryTerrainRepairs` `:586`, `restoreTemporaryEntryTerrain` `:6950` and its call `:1617`, `terrainRestoreAttempted` counter and its read in the maintenance summary `:7010` | `skills/impl/WoodcutSkill.java` | ~65 | Added 2026-03-31 (CHANGELOG 8239), the writer went with 19447079 (2026-04-01). The map is never populated, so the restore loop runs over an empty map every tree. Behaviour-preserving removal. |
| `WoodcutSkill` `containsXZ` `:388`, `intersectsBox` `:396`, `containsBox` `:408`, `currentColumnPlacements` `:2260`, `toColumnKey` `:2719`, `MAX_LOS_CLEAR_ATTEMPTS` `:114`, `MAX_REPEATED_TRUNK_ENTRY_STALLS` `:136` | same | ~47 | W (file-scoped, `BotTerritoryAuthorizationService` has its own `containsXZ`). |
| `TreeDetector.findFloatingLog` `:272`, `collectOwnedTreeLogs` `:566` + `isOwnedTreeLog` `:581`, `collectConnectedLogs` `:640` + `withinLimits` `:673`, record accessors `blockedHumanAdjacent` `:130`, `rejectedGroundedOrNonFloater` `:138` | `skills/support/TreeDetector.java` | ~140 | W. |
| `WoodcutCleanupSkill.isCleanupFloaterCandidate` `:1091` + `isSuspendedCleanupLog` `:1121` + `isFloatingOrOrphanLog` `:1156` | `skills/impl/WoodcutCleanupSkill.java` | ~100 | W. The two helpers are called only from the candidate method (`:1113-1114`). Superseded by `TreeDetector.classifyCleanupLog`. |
| `CollectDirtSkill.waitForNearbyFallingBlocksToSettle` `:1446` + `maybeSendFallingBlockWaitMessage` `:1485` + `isFallingBlock` `:1500` + constants `:91-93` + `LAST_FALLING_BLOCK_MESSAGE` `:97`, `getTargetBlockIdsParameter` `:2341`, `SPIRAL_RADIUS_LIMIT` `:85`, `MAX_STALLED_FAILURES` `:88` | `skills/impl/CollectDirtSkill.java` | ~75 | W (file-scoped, the `FallingBlockStabilizer` versions are the live ones, used at `:1429`). |
| `FarmSkill.treeNear` `:765`, `snapToFarmGrid` `:1903`, `needsWoodcut` `:2016`, `stillNeighborCount` `:2788`, `offsetAwayFromWater` `:3013`, private `ensureHotbarItem` `:4209`, `findNonFarmlandAdjacent` `:4364`, `PRECIPICE_DEPTH` `:93` | `skills/impl/FarmSkill.java` | ~84 | W (file-scoped, `BotActions.ensureHotbarItem` is the live one). |

Gate: `WoodcutCleanupSkillTest` reads both woodcut sources. Run it alone after the edit, then the suite.

### Phase 14. Shelter and fishing skills, ~115 lines

| Item | File:line | Lines | Evidence |
|---|---|---|---|
| `ShelterSkill.countBuildBlocks` `:543`, `hasOption` `:619`, `placeBlock` `:636` | `skills/impl/ShelterSkill.java` | 46 | W (file-scoped, `HovelPerimeterBuilder` has its own `countBuildBlocks`). |
| `HovelPerimeterBuilder.isSupportHole` `:1705`, `roofWalkMissingThreshold` `:1865`, `isCornerOfRing` `:2939`, `clearIfObstructed` `:2964`, and the three always-true toggles `USE_OPTIMIZED_LEVELING_ROUTE`/`USE_TARGETED_FOUNDATION_REPAIR`/`USE_CACHED_FLOOR_SCAN` `:44-46` with their dead false branches (`:737`, `:753-754`, `:2826`, `:2951`) | `skills/impl/shelter/HovelPerimeterBuilder.java` | ~42 | W. Fold the toggles by keeping the true branch. |
| `HovelBlueprint.buildOuterRingSeeds` `:47`, `buildRoofPerimeter` `:97` | `skills/impl/shelter/HovelBlueprint.java` | ~20 | W. `RoofAccessService:152` calls its own private `buildRoofPerimeter`, not this one. New find during the challenge. |
| `BurrowBuilder.sleepQuiet` `:307` | `skills/impl/shelter/BurrowBuilder.java` | 5 | W (file-scoped). |
| `FishingSkill.isBlacklisted(UUID, BlockPos)` `:2480` | `skills/impl/FishingSkill.java` | 18 | W (file-scoped, `WoodcutRerouteBlacklist.isBlacklisted` is a different, tested method). Ratchet unchanged. |

### Phase 15. Small skills and support, ~45 lines

`DirtShovelSkill.chooseSpiralDirection` `:687` + `chooseHorizontalDirection` `:675` (19). `HuntSkill.countInInventory` `:1228`, `DEFAULT_HUNT_RADIUS`/`DEFAULT_HUNT_Y_SPAN` `:79-80`. `TorchPlacer.isSuitableForTorch` `:288`, `hasProtectedTorch` `:60`, `TORCH_SPACING` `:31`. `LeashToFenceSkill.isHoldingLeashedAnimal` `:358`. `StripMineSkill.TORCH_CHECK_INTERVAL` `:49`. All W T R.

### Phase 16. Fortify helpers, ~40 lines

`FortifyEscapeHelper.updateProtectedPositions` `:69` (body is a comment saying no-op), `clearImmediateOverheadForEscape` `:271`. `FortifyVillageSkill.segmentBucketForTest` `:4592` (no test calls it), `MAX_BUILD_TIME_MS` `:82`, `FORTIFY_SCAFFOLD_LEDGER_RADIUS_XZ/Y` `:110-111`. `FortifySkillTypes.ApproachCandidateEval` `:149` (only a javadoc mention at `:18`). `FortifyEntombmentHelper.LOGGER` `:24` and `FeedAnimalsSkill.LOGGER` `:22` (single occurrence each). All W T R.

## 4. MERGE items

| # | Duplicate | Target helper | Lines saved | Notes |
|---|---|---|---|---|
| M1 | `ConfigureTextCategoriesScreen` and `ConfigureVoiceCategoriesScreen` (158 each, 38 differing lines, CHANGELOG 3317 calls the text one a clone) | One `ConfigureDialogueCategoriesScreen` parameterised by lane | ~120 | Keep the inverse-semantics difference explicit in the constructor. |
| M2 | `SmeltingService` private chest helpers `:1687-1937` (`depositIntoChests`, `placeChestNearBot`, `ensureInteractable`, `chestContainsItem`, `chestHasSpace`, `chestHasAnySpace`, `insertIntoInventory`, `findChestPlacementOptions`) | `CraftingHelper` versions with a reach parameter | ~250 | Gate: `SupplyBypassClosedTest.RATCHET` SmeltingService 10 drops by every chest site removed, CraftingHelper may rise. Update both entries with reasons. |
| M3 | `REACH_DISTANCE_SQ = 20.25` private copies in `PerimeterService:33`, `RoofAccessService:33`, `DoorPlacementService:42`, `BridgeScaffoldService:48`, `HovelPerimeterBuilder:47` and others | `ConstructionPlacementRules.REACH_DISTANCE_SQ` | ~10 | Phase 4 deletes three of the copies anyway. |
| M4 | Forage trio `GrassSeed`/`LeafLitter`/`MushroomForage` byte-identical `moveIntoReach`, `isSafeHandStack`, `sleepQuietly`, `clamp` | `skills/support/ForageSkillSupport` | ~106 | Diff the three first. |
| M5 | `HarvestCropSkill` and `PlantSeedsSkill` identical `moveTo`, `isSafeStandingGround`, `findEmptyHotbarSlot`, `isMutationAuthorized`, `isPassable` | shared support class | ~30 | |
| M6 | `getIntParameter` x15, `getBooleanParameter` x7 across skills | `SkillContext` | ~100 | Diff the variants first, two differ in default handling. |
| M7 | `modCommandRegistry.invokeDialogueDebugTrigger` `:4588` reflects into 5 own classes that expose `public static debugTrigger` | Direct calls | ~25 | Same for `getOptionalIntField`/`getOptionalLongField` `:4604-4626` on now-public `BotCommandStateService` fields. |
| M8 | `CraftingResultSlotMixin` `:25/:30/:35` records the same craft up to three times (`recordCraft` is idempotent) | One inject on `onTakeItem` | ~10 | Verify quick-craft still records once. |
| M9 | `AutoFaceEntity:337-338` calls `detectDangerZone(bot,10,10,10)` twice per tick | Local variable | 1 | Perf only. |
| M10 | `SafePositionService.isOperationalSurfaceAssessment` `:196-206` mirrors `SurfaceReadinessPolicy.isOperational` | Delegate | ~8 | Both have tests, keep the tests. |
| M11 | Boolean wrappers whose `*Detailed` or renamed twin is live: `BotFleeService.isCommanderNearbyAndUnderground` `:2429` (CHANGELOG 8273 moved callers to `isCommanderNearby`), `CompanionContextReactionService.playWakeUp` `:828` (`playWakeUpForced`), `HuntConfigService.setDepopulation/setZone/setSelectedTargets` `:172/:186/:200` (`saveConfig`), `FollowMovementService.tryWaypointLocalReposition` `:242` and `tryFollowWaterLedgeEscape` `:283` (`*Detailed`), `BotSkinService.persistSkin` `:442` (`persistSkinSelection`) | Delete the wrapper, callers already use the twin | ~75 | All zero-caller, so these could sit in SAFE. Listed here because each is "a wrapper of a live method", so the delete should confirm the twin's semantics match. |
| M12 | Hardcoded `localhost:11434` at `FunctionCallerV2:106`, `NLPProcessor:34`, `DecisionResolver:23`, `ollamaClient:40`, `LLMServiceHandler:40` | `ManualConfig.getOllamaBaseUrl()` | 0 | Only if Q2 keeps the legacy path. |

Test-only helpers to keep as-is (regression pins, not dead): `BotRescueService.shouldPreferProtectedGlassSnapKeys` `:141`, `BotStuckService.shouldSnapProtectedGlassDuringMineEscapeKeys` `:223` (make the live `BlockState` versions call them), `RedstoneDoorPolicy.shouldSetAlreadyOpenCooldown` `:32` (b25d1a18), `SupplyRequestPolicy.Config.withAllowedTiers/withOperatorMayApprove`, `InteriorEgressPolicy.onBoundary` `:63` and `exitAcceptable` `:396`, `VillageRuntimeProtectionPolicy.anyNonExemptZone` `:55`, `WoodcutRecoveryHeuristics.shouldProtectTerrainCarve` `:46`, `WoodcutScaffoldRecoveryHeuristics.shouldRepositionForCleanup` `:19`, `TerrainMovePolicy.cellsToBreak` `:96` (finder build).

Not merged on purpose: the three `pillarUp` copies (`WoodcutCleanupSkill:1757`, `WoodcutSkill:4246`, `HovelPerimeterBuilder:1403`) have different guards, and the stuck/surface overlaps (`ReturnBaseStuckService.isSurfaceReached` `:727` vs `SafePositionService`, `findNaturalScaffoldEscape` `:2151` vs `MovementService.findBestLocalEscapeCandidate`, `TreeStuckEscapeService.findSafeDropEdge` `:279` vs `SafePositionService.findSafeNear`) change behaviour if unified. Leave them to the finder build.

## 5. NEEDS-BRADLEY

1. **Reinforcement learning and training mode (~2,400 lines).** `RLAgent` (1552), `StateActions`, `ActionHoldTracker`, `Database/QTable`, `QTableStorage` (323), `QTableExporter`, `QEntry`, `StateActionPair`, `StateActionTransition` (0 refs), `PlayerUtils/ResourceEvaluator`, `ThreatDetector`, `BotEventHandler.detectAndReact` `:862-1092`, `detectAndReactPlayMode` `:1093-1182`, `executeAction` `:1183+`, `performLearningStep` `:7704-7863`, `throttleTraining` `:297`, `performAction` `:7933`, `DEBUG_RL` `:126`. Only entry is `AutoFaceEntity:322-331/373-383`, gated by `modCommandRegistry.enableReinforcementLearning = false` (`/bot rl on`, CHANGELOG 9160). The Q-table still loads and `RLAgent` is still built on AutoFace start (`AutoFaceEntity:115-138`) even when off. `/bot spawn ... training` uses the same cluster. Keep `State` and `createInitialState` (`FunctionCallerV2` uses them). Recommended: delete the cluster and the `rl`, `training`, `debug_qtable_serialization`, `exportQTableToJSON` commands. The early-game focus has never used it, and the always-on Q-table load is a startup cost for nothing.
2. **Pre-souls LLM chat path (~1,400 lines live, ~870 of it cloud clients).** `ollamaClient` (310), `LLMServiceHandler` (386), `OpenAI/Anthropic/Gemini/Grok/GenericOpenAI` clients plus 5 `ModelFetcher`s, `RAG2`, `NLPProcessor`, `DecisionResolver`, `ChatContextManager`, `ToolRegistry` state updaters, `GameAI/llm` (637, the legacy chat route behind the ollama4j runtime check at `Frens:367`). Gated by the `BotControlScreen` "LLM World (legacy)" master (`:42`) and per-bot "LLM Chat" (`:536`), both reachable and default on. Cloud providers are selectable only through `-Dfrens.llmMode` with no in-game picker. Bugs B11 and B12 live here. Recommended: retire the path (souls replaced it), keep `OllamaLocalClient` and `LLMClientFactory` if anything else needs a raw client, delete the rest, remove the two toggles. If kept: do the `ollamaClient` MERGE (route `modCommandRegistry:1116/:2557/:2585` through `LLMClientFactory("ollama")`, then delete `ollamaClient`, `RAG2.run` 3-arg `:205`, `FunctionCallerV2.run(String)` `:1385`, ~400 lines) and fix B11.
3. **`ToolRegistry` state updaters (~150 lines plus `ToolStateUpdater.java`).** `Tool.stateUpdater()` and `stateKeys()` (`Tool.java:24-25`) have zero callers, so the lambdas registered in `ToolRegistry` never run. Part of Q2. Recommended: goes with the path.
4. **Dev and debug `/bot` subcommands with player permission (~400 lines).** `debug_serialization` `:1050`, `debug_qtable_serialization` `:1073`, `test_chat_message` `:1097`, `detect_entities` `:1124`, `detect_blocks` `:1156` (B8), `turn` `:1175`, `detectDangerZone` `:1412`, `getHungerLevel` `:1511`, `getOxygenLevel` `:1549`, `getHealth` `:1565`, `isFoodItem` `:1582`, `exportQTableToJSON` `:1637`, `testgear` `:1805`. Recommended: delete all but `testgear` and `test_chat_message`, and gate those two behind `DebugToggleService`.
5. **Disabled flee tactics in `BotFleeService` (~135 lines).** `runEmergencyTacticChain` `:912` has pillar (step 1) and wall-off (step 4) commented out as DISABLED with reasons. `emergencyPillarUp` `:956`, `findNearbyOverhangShelter` `:1170`, `emergencyWallOff` `:1216`, `findNearbyTree` `:1141` and the unused `phantomsPresent`/`hasBlocks` parameters are dead. Recommended: delete them and the two parameters. The cliff-dig and dig-down steps are the ones that work in the field, and the comments say what would have to change before re-enabling, so the git history is enough.
6. **`/bot nav_mode` (B6).** Recommended: delete (~60 lines). Fast travel already has `BotNavTier` and the lodestone rules, and WALK was never read in seven months.
7. **Client nav tier cache.** `setCachedBotNavTier` is fed by `BotNavTierPayload` (`FrensClient:1077`) but `getCachedBotNavTier` `:352` has no reader. Recommended: keep the payload (it is cheap and the HUD will want it), delete nothing yet, or drop both the payload and the cache together (~30 lines).
8. **Combat line hooks (B14).** `noteCombatSituation`, `onBotHitPlayer`, `POST_COMBAT_MULTI_LINES`, `FF_DEALT_LINES` and the six recorded friendly-fire clips. Recommended: wire both (two call sites, under 10 lines), the lines are already recorded and the friendly-fire one is player-facing feedback.
9. **`BaseSetOwnerPayload` (B13, ~80 lines).** Recommended: add a `/bot base owner <label> <player|server|none>` admin command that sends the existing payload. The receiver is complete and admin-gated, the command is the missing 20 lines, and ownerless legacy bases are a real state in Nirn.
10. **Quest templates behind `BotQuestService.DISABLED_QUEST_IDS` `:64`.** Eight shipped templates (seven ambient and exploration, plus `combat_clear_the_nearby_threat`, CHANGELOG 8631) are loaded then filtered at `:832/:850`. Questing itself is behind `questingModeEnabled` (default off, 1.1.235). Recommended: keep, the questing rework is pending and the set is the switch.
11. **Persisted-but-unused `ManualConfig` keys.** `BotControlSettings.autoSpawn` (`@Deprecated`, JSON compat 2026-03-02), `allowEveryoneSkinChange` (kept for downgrade, e9e66b13, still carried in `RecruitmentStatePayload:14` as a constant false), `modeSelectedAtEpochMs`/`modeSelectedByName`, `companionDiedAtEpochMs`/`companionDiedDimension`/`companionDiedPos` (written, never read), the legacy `botSkins` map mirrored for compat. Recommended: keep `autoSpawn` and `allowEveryoneSkinChange` until the next format bump, delete the two `modeSelected*` and three `companionDied*` keys now (Gson ignores unknown keys on load, so old files still open).
12. **Legacy 1-arg config accessors (demoted from SAFE, see D1).** `ManualConfig.getOrCreateBotControl(alias)` `:1317` and `getEffectiveBotControl(alias)` `:1331` are the final fallback of the per-world versions (`:928`, `:957`) for a config that has a global `botControls` entry and no per-world one. Recommended: keep until a migration pass writes every global entry under `_legacy` per world, then delete.
13. **Legacy migrations that run every startup.** `LegacyDataMigrationService.migrateConfigDir` (74 lines, `config/ai-player` to `config/frens`, `Frens:386`), `MappedVillageService.importLegacyIfNeeded` `:320-395`, `MountPersistenceService` `_legacy` promotion (still written at `:432`). Recommended: keep the config-dir move (cheap, one `Files.exists`), delete the village import and stop writing `_legacy` mounts at the next format bump, both have been live since March.
14. **`RideSyncService.clearLeashTarget` `:159` (demoted from SAFE, D3).** Zero callers, but `clearBotState` deliberately keeps `LEASH_TARGET` because it is persisted, so this is the only way a saved leash target is ever forgotten. Recommended: call it from the force-dismount (`'` hotkey) and `/bot unmount` paths, do not delete.
15. **`FortificationPersistenceService` accessors `:105/:113/:115`.** Unused getters on fields that are in saved JSON. Recommended: delete the getters, keep the fields.
16. **Keybind migration (B9) and eye-spell hint (B10).** Recommended: delete both, ~95 lines total.
17. **Woodcut entry-terrain restore (Phase 13, first row).** Listed under SAFE because removal is behaviour-preserving, but the feature was intended (CHANGELOG 8239). If Bradley still wants temporary terrain restored after a tree, the fix is to call `recordTemporaryEntryTerrainRepair` from wherever `carveEntryHeadway` now places blocks, not to delete. Recommended: delete, 1.1.230's protection nudges cover the case.

## 5a. Bradley's answers (2026-10-15)

Standing rule for every removal: first confirm nothing useful in the removed code is unmerged (move it out if it is), and update all documentation in the same build, including the in-game Guide.

1. RL and training mode: **delete** (after confirming nothing useful is unmerged, e.g. ThreatDetector/ResourceEvaluator users).
2. Pre-souls LLM chat path: **retire** it and remove the "LLM World (legacy)" and per-bot "LLM Chat" toggles. Keep `OllamaLocalClient`/`LLMClientFactory` only if souls or another live path needs a raw client (verify first). B11/B12 go with it.
3. ToolRegistry state updaters: **delete** with the Q2 retirement.
4. Dev/debug `/bot` subcommands: **delete** all except `testgear` and `test_chat_message`, which stay behind `DebugToggleService` (only registered/usable when debug is on).
5. Flee tactics: **delete** `emergencyPillarUp` and `findNearbyTree` (pillaring exposes the bot to skeletons and phantoms, the opposite of seeking cover). **Rebuild wall-off** as a feature, not a deletion: after the village-house check and before cliff-dig, needs ≥4 blocks, walk with the real mover and confirm arrival, seal 1-2 open faces of a natural overhang, accept only if `assessShelterAt` validates, else fall through to cliff-dig/dig-down; record placed blocks for morning teardown; fix the isSolidBlock footing check and off-thread reads. Leaves no hole (dig-down bunkers caused the 1.1.235-1.1.237 stuck-in-a-hole bugs). Sandbox test at dusk by a natural overhang.
6. `/bot nav_mode`: **delete** command, setter, getter, stored field (and any Guide mention). Nav tiers + lodestone/Eye of Ender already decide travel.
7. Client nav tier cache: **delete** `BotNavTierPayload` (registration, send sites, client receiver), the client cache and getter.
8. Combat line hooks: **wire in** `noteCombatSituation` (hostile scan reports the dangerous-mob count) and `onBotHitPlayer` (melee path when the target is a player); existing toggles and cooldowns apply.
9. Ownerless bases: **add** `/bot base owner <base name> <player|server|none>` (admin-only) reusing the existing `BaseSetOwnerPayload` server handler logic; Guide line under Bases.
10. Disabled quest templates: **keep** (data; the list is the switch for the questing rework).
11. Persisted unused config keys: **keep** `autoSpawn` and `allowEveryoneSkinChange` until the next format bump; **delete** `modeSelectedAtEpochMs`/`modeSelectedByName` now; **delete** the legacy `botSkins` mirror after confirming the one-time seeding migration covers all configs; **keep** `companionDiedAtEpochMs`/`companionDiedDimension`/`companionDiedPos` for a new backlog feature: a retrieval mission where you send a bot to the recorded death coordinates to collect the dropped items (companion deaths now, the player's own death if we record it the same way).
12. Legacy 1-arg config lookups: **keep**; backlog a migration that copies global per-bot entries into each world's legacy slot, then delete them.
13. Legacy migrations: **keep** the config folder move; **delete** `MappedVillageService.importLegacyIfNeeded` now; mount `_legacy`: **stop writing it** in the cleanup build, **delete the promotion** one build later.
14. `RideSyncService.clearLeashTarget`: **wire in** from the force-dismount hotkey, `/bot unmount`, and deliberate leash-break paths.
15. Fortification accessors: **delete** the three methods, **keep** the saved fields.
16. Keybind migration (B9) and eye-spell client hint (B10): **delete** both.
17. Woodcut entry-terrain restore: **delete** (writer removed in `19447079`; scaffold and temporary supports have their own working cleanup).

**New backlog items from these answers:** wall-off night shelter rebuild (Q5), combat line hooks (Q8), `/bot base owner` command (Q9), death-location retrieval mission (Q11), per-world migration of global bot controls (Q12), mount `_legacy` promotion removal one build after the write stops (Q13), leash-target clearing (Q14).

## 6. Challenged and demoted

| # | Auditor claim | Finding | New rank |
|---|---|---|---|
| D1 | `ManualConfig.getOrCreateBotControl(alias)` `:1317` and `getEffectiveBotControl(alias)` `:1331` have 0 callers (commands-config) | Called internally by the 2-arg versions at `:928` and `:957` as the legacy global fallback. Deleting changes what old saves resolve to. | NEEDS-BRADLEY Q12 |
| D2 | `ollamaClient` MERGE into `LLMClientFactory` now (chat-llm) | Its three callers are the legacy chat command path that Q2 may retire whole. Merging first is wasted work and touches `modCommandRegistry` twice. | Conditional on Q2 |
| D3 | `RideSyncService.clearLeashTarget` `:159` SAFE (services-lz, with a flag) | Only clearer of a persisted key. The auditor's own flag was right. | NEEDS-BRADLEY Q14 |
| D4 | `StateActionTransition` SAFE now (commands-config) | 0 refs is correct, but it is the Serializable chunk format for Q-tables. Deleting it before the RL ruling makes the Q-table loader uncompilable if Bradley keeps training. | Parked under Q1 |
| D5 | `MovementService` three items SAFE now (services-lz) | Correct, but the file is mid-rewrite for 1.1.242. | SAFE, Phase 8, after 1.1.242 lands |
| D6 | `CraftingHelper.placeChestNearBot` dead (services-ak, as "private placeChestNearBot :3287") | True for the private `(ServerWorld, bot, reachSq)` overload. The bare-name grep also matches `ChestStoreService.placeChestNearBot`, which has 8 live callers. Kept in SAFE with the overload spelled out so nobody deletes the wrong one. The ratchet test counts its `getBlockEntity`. | SAFE, Phase 5, with ratchet gate |
| D7 | `SmeltingService` private chest copies and dead furnace helpers delete cleanly (services-lz) | Deleting changes `SupplyBypassClosedTest.RATCHET` counts (SmeltingService 10). Not wrong, but not free. | SAFE Phase 7 and MERGE M2, both with ratchet gate |
| D8 | `FrensClient.migrateLegacyPreviewLockBinding` is a MERGE (commands-config) | It is a no-op in production and partly impossible even in dev (`invokeFirstNoArgMethod` on `java.lang.Class`). That is a bug with a delete as the fix, not a refactor. | Live bug B9, delete via Q16 |
| D9 | `MiningHazardDetector:294` block-entity read "probably redundant" (skills) | Confirmed redundant for chests and barrels (`CHEST_BLOCKS` at `:291`) and also off-thread. The ratchet lists it with 2 sites. | Live bug B1 tail, replace with `hasBlockEntity()` |

Two items the auditors called dead that the challenge confirmed live and that are therefore absent from every list: `HovelPerimeterBuilder.findNearbyStandable` (used as a method reference at `:2471`, `:2587`) and `ChestStoreService.DepositProbeResult` (two skill callers). One item the challenge added: `HovelBlueprint.buildRoofPerimeter` `:97` (Phase 14).

## 7. Method and coverage limits

- Greps were whole-word (`grep -rnw`) across `src/main/java`, `src/test/java`, `src/main/resources`, `build.gradle`. Same-named methods in other classes were separated by a per-file count. Overloads were separated by reading the declarations. Method references (`Class::name`) and `@Override` chains were included because `-w` matches them.
- Reflection: every `Class.forName`, `getMethod`, `getDeclaredMethod`, `getField` and `getDeclaredField` string literal in `src/main/java` was listed (13 distinct strings: emote and player-animation hooks, `getDefaultKey`, `setBoundKey`, `getBoundKeyTranslationKey`, `hasPermission`, `isUltrawarm`, `theUnsafe`, one ollama4j class name) and compared with the delete list. No deleted name is a reflection target. The dialogue debug triggers (`modCommandRegistry:4545-4579`) and command-state fields (`:3906-3907`) reflect into live classes. Out-of-tree reflection into this mod (the LoadGoverner project reads `SoulRuntime.activeGenerations()`) touches nothing listed here.
- Serialisation: `ManualConfig`, `BotHomeService.WorldData` and `FortificationPersistenceService` fields that appear in saved JSON are kept everywhere, only accessors go.
- Network: all 93 payload registrations were checked by the gui-network auditor for receivers and send sites, re-checked here for the one orphan (`BaseSetOwnerPayload`).
- Threads: established from the executor that starts skills (`modCommandRegistry:157`) and the absence of `callOnServer`/`submit`/`execute` in each file, not from log lines.
- Not covered: `src/main/java/io`, `ai/djl` and `net/minecraft` shim packages, `PathFinding/*` (finder build), generated or resource assets other than `block_metadata.json` and the mixin list, and runtime-only liveness (a method with one caller behind a flag that is never true still counts as live here, those cases are in section 5).
- Line counts are from `wc -l` for whole files and from declaration-to-closing-brace estimates for methods, rounded. Expect the real diff to land within 10 percent.
