# Frens task file: current handoff on top, backlog below

The newest session handoff comes first, then "Recovered deferrals", then the backlog lineup and the prioritized backlog.
Open work is a `- [ ]` line (`grep -n '^\- \[ \]' RALPH_TASK.md`). Superseded handoffs and old session notes are in
`docs/handoffs/ARCHIVE_2026.md`. History is `changelog.md`.

## Session Handoff 2026-10-23 (1.1.295) — next session starts here

**State:** 1.1.295 on main and deployed to Nirn. It is the next public Modrinth release (Bradley 2026-10-23), pending the fresh-install part 2 check and Bradley's OK on the release notes. Ships: a following bot gets up a leafy step (sweep F1), `/bot place` onto snow in one command with no tick freeze or error (F2), a bot that walks into the End exit portal comes back by itself, a dead bot's task stops (no "Mining complete" after death), "Task ended: changed dimension." once, the 39 placeholder-only voice lines are text only, and a client crash on `/bot spawn` (ConcurrentModificationException in the alias maps) is fixed. Sandbox-checked, `docs/testing/FIELD_1_1_295_SANDBOX_2026-10-23.md`, replays `frens_1_1_295_*`. Tests 3745.

**Field checks pending:** Phase 6cd (one item: a text-only line seen in play). Phase 6cc on Nirn. The guided LAN-guest check.

**Deferred (with reasons):** a second End exit by the same bot takes the plain teleport path (`seenCredits` true): follow is cleared and the task ends with "External teleport detected." (pre-existing, now on every bot's second End trip; fix with a reset on the cross-dimension `teleportTo` path). `AFTER_RESPAWN` does not fire for real deaths or `/kill` (the fallback runs; worth knowing why). `/bot place` from a distance needs a worker hop. A live text-only line was not seen in the sandbox. 6bz1 (fortify or shelter onto snow) not run: the `sheltertest.py seal` setup did not trigger a shelter (Wally's commander is another bot). Review minors: weird-portal edge flag re-rolls each tick while text is off, `TaskService.resetAll` clears the worker ticket only on its own thread, `attemptAdjacentChest` places into its own anchor cell.

**Modrinth:** fresh-install part 2 passed on 1.1.295 (no high-severity finding, `docs/testing/FRESH_INSTALL_1.1.226.md` part 2; throwaway instance deleted with Bradley's OK). Bradley OKed the notes text as written on 2026-10-23 (`.superpowers/sdd/modrinth/MODRINTH_NOTES_1.1.295_DRAFT.md`, copy on his Desktop) and uploads it himself.

**From the fresh install, for the backlog:** Voice Engine screen clips each engine's download size under its Install button at GUI scale 4 (medium). First-run roster says "Restore" for the default Jake. Checklist RAM hint and model screen header cut with an ellipsis. Vanilla's tutorial toast covers the top right of Frens screens.

## Session Handoff 2026-10-23 (1.1.294) — superseded

**State:** 1.1.294 on main and deployed to Nirn. Fixes the one 1.1.293 sandbox failure and a second bug the recheck found: after an End exit mid-task the "Task ended: changed dimension." line reaches chat (sent through the new entity), and the bot follows again (`RespawnResetPolicy.resumeFollowAfterSkill`). Sandbox-checked, replays `frens_1_1_294_end_exit_chat`, `frens_1_1_294_end_exit_follow`. Tests 3705.

**New backlog candidates from the recheck:** a skill keeps running a few seconds after its bot dies and can post "Mining complete! Collected 3 out of 10" (death latches no abort for the worker thread). The End-exit line shows twice (bot line plus the skill's result as a system line). Everything under the 1.1.293 handoff below stays open (walk-in End exit limbo first).

## Session Handoff 2026-10-23 (1.1.293) — superseded

**State:** 1.1.293 on main and deployed to Nirn. It ships six deferred minors from the 1.1.291 review (changelog entry "Deferred minors from the 1.1.291 review ... 1.1.293"): a bot leaving the End alive keeps its follow/base targets and is not teleported again (RespawnHandler deleted, so every respawn now runs its reset once, `RespawnResetPolicy`), the mining harmless-fallback hop retries a timeout once, a refused group scene is failed and released, admins get a join notice for unreadable store files, the empty legacy preview stops suggesting an import, Carpet suggests >=1.4.193. Tests 3700.

**Sandbox check:** 4 pass, 1 fail (docs/testing/FIELD_CODEX_WAVE_SANDBOX_2026-10-23.md "1.1.293 check", replay `frens_1_1_293_end_exit_death_mining`): End exit keeps follow with one reset and no relocation, death routine once, mining and harmless fallback fine, no store notice; FAIL: the "Task ended: changed dimension." line never reaches chat (routed, then dropped by the bot-chat gate, likely because the ticket source is the removed old entity), the player sees only the skill's own "Descent aborted" text.

**Deferred (with reasons):** a bot that walks into the End exit portal itself detaches and waits in limbo until teleported (pre-existing, vanilla credits path, scope against Carpet's EntityPlayerMPFake; javap the 1.21.11 yarn jar for the method name first). `MiningTool.switchToTool` has the same timeout-as-false pattern (milder: falls back). 100 ms server-thread sleep on the death path (`AutoFaceEntity.handleBotRespawn`). An End exit still ends the running skill (it holds the old entity), no automatic re-run. From the 1.1.291 list, still open: furnace walk-back after lost reach, ritual crash journal, empty bucket after a refused adoption, 0600 store file mode.

**Sweep results (6bt-6cb on 1.1.292, `docs/testing/FIELD_6BT_6CB_SANDBOX_2026-10-23.md`):** 9 pass and ticked, 2 pass with a gap (two-bot cleanup 1 run, tool swaps without stone), 12 not run, 7 Bradley-only (now in the guided session). Two FAILs for a fix build: (1) following up a leafy step, the leaf over the bot's own head is never mined (`leaf-action ... BYPASS_STEP` only, `LeafClearService`/`MovementService`); (2) `/bot place` onto a snow layer walks the bot on the server thread (tick frozen ~14 s) then throws "An unexpected error occurred" (`modCommandRegistry.attemptPlacement`). Rerun 6bz1 (fortify/shelter onto snow) with fix 2.

**Pending:** field Phase 6cc on Nirn, guided LAN-guest check, the two 6bt-6cb FAILs above.

## Session Handoff 2026-10-23 (1.1.292) — superseded

**State:** 1.1.292 on main and deployed to Nirn. It fixes the one failure from the 1.1.291 sandbox sweep: the idle resource woodcut started in a burst (up to 10 per second) and the refused duplicates pushed its backoff to the cap. Fixed by arming the in-flight cooldowns before the submit, sandbox-checked (`frens_1_1_292_idle_woodcut_once.zip`).

**Sweep results (1.1.291, `docs/testing/FIELD_CODEX_WAVE_SANDBOX_2026-10-23.md`):** farm, furnace reach, mining, ritual payment order, hunt settings carry-over, quarantine and smoke pass. Not run: diagonal wall-clip nudge (the older immediate escape freed the bot first) and the busy co-sleep retry (the busy task ended instantly). The wake greeting delay passed.

**Pending:** field Phase 6cc on Nirn, guided LAN-guest check, everything listed under the 1.1.291 handoff below (deferred minors, Codex limits). Field phases 6bt-6cb (1.1.282-1.1.290) are still not sandbox-checked.

## Session Handoff 2026-10-23 (1.1.291) — superseded

**State:** 1.1.291 on main and deployed to Nirn (1.21.11). It ships the Codex wave below (every "shipped in 1.1.291" handoff) after a Fable review, one fix wave, a scoped Fable re-review and a sandbox check with Carpet 1.4.194 installed. Changelog entry "Codex reliability, access and save-isolation wave, reviewed and hardened; 1.1.291" has the details, rulings and kept limits. Reviews: `.superpowers/codex-wave-backup-2026-10-22/fable-review.md` and `fable-rereview.md` (untracked).

**Fixed on top of Codex:** End-exit respawn hook is a MixinExtras `@WrapOperation` (Carpet redirects the same NEW; two redirects crash at class load, and Nirn runs Carpet). Seven fingerprinted stores adopt legacy level-name data at SERVER_STARTED (`LegacyRegistryRecoveryPolicy.adoptLegacy`, claims plus brand-new declines, no empty buckets). `/bot legacy-state registry import <store> "<fp>" from "<key>"` for moved instances. Malformed store files go to `.bad-<stamp>` (`UnreadableStoreFile.quarantineIfCorrupt`), read errors leave the file alone. Mojang profile lookup times out (10 s).

**Field checks pending:** Phase 6cc (Nirn first start adopts its store data, full mod list starts clean). Guided: LAN guest is not the host (needs a second account). Earlier pending phases unchanged.

**Deferred minors (with reasons, from the review):** no in-game notice for a quarantined file (log WARN only, low frequency). A refused-adoption save's first runtime write still creates an empty bucket, so `from` import must run right after load. End exit runs the death-respawn reset (`RespawnHandler.registerRespawnListener` ignores `alive`, pre-existing). `MiningTool` treats a 2 s `switchToHarmlessFallback` timeout as no safe tool. `SmeltingService` does not walk back after losing furnace reach. `GroupScenePlayback.enqueue` drops silently when a scene is active and the pending entry leaks. `fabric.mod.json` suggests Carpet 1.4.188 (1.21.11 release is 1.4.193+). Hunt-config preview suggests import with 0 records. Fingerprint is a hash of the absolute save path (moved instance: use `from`).

**Kept limits from Codex:** ritual has no crash journal (crash or post-connect refusal can leave a bot before payment). UUID match does not authenticate a spoofed identical UUID. Codex's own open triage list in the next handoff stays valid.

**Next:** Nirn field Phase 6cc on Bradley's next play. Then the deferred minors above, cheapest first (Carpet version string, preview text, enqueue boolean, MiningTool timeout).

## Session Handoff 2026-10-22 (remaining source-confirmed bug wave verified) — shipped in 1.1.291

**Current state:** ONLY checkout `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12`, version `1.1.290-release+1.21.11`. **97 pending source/handoff files:94 Java, one mixin resource and two root handoffs.** Preserve all earlier edits and unrelated `.superpowers/` / `logs/*.gz`. No staging, commits, version bump, game/PrismLauncher access, packaging, deployment or publication. The user resumed after a usage interruption; all in-flight phases were reread, completed and frozen before central verification. No implementation remains in progress. This is source-complete work for the confirmed defects below, not a claim that every historical field report or feature in the backlog is resolved.

**Verification:** Java21 `javac --release 21 -proc:none` compiled **all94 pending Java files** against cached named Minecraft/Fabric dependencies and existing classes. Full direct JUnit **3652/3652 passed, zero failures/skips/aborts**, including PocketVoice loopback tests;53 tests more than prior3599. First whole-suite run was3651pass/1fail: PlayerTextPunctuationGuardTest rejected semicolons in new recovery/ritual messages. Those two production files were reread, messages corrected, recompiled and the whole suite rerun successfully. Byte Buddy agent/experimental flag, test resources and build-local tmp were used. No Gradle/remap build was attempted in this wave; normal Java21 Gradle build is still required before release. Independent reviews found no blockers in host/End, ritual, histories, structures and hunting settings. `git diff --check` passed. Task-owned scratch directories under `build/verify-remaining*`, `build/verify-worldstate-lock/` and `build/verify-hunt-config/` removed after recording results. No live Minecraft result is claimed.

**Exact per-item changes, causes and limits:** shared paths appear under each relevant item. Earlier cumulative manifests below cover prior pending work. Each implementation phase stayed within five edited files; shared-command edits were sequenced.

### 1. Pending online spawn shutdown

A queued profile completion could leave its returned spawn future pending after shutdown discarded the server queue. DeferredSpawnPolicy now tracks futures by server identity, removes terminal entries and drains cancellation after TaskService closes admission. Guards remain before dispatch and inside the server hop. Seven new cases, final policy16/16. The profile HTTP request itself is not interrupted.

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/DeferredSpawnPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/DeferredSpawnPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Frens.java`

### 2. End-return fake-player identity

Vanilla PlayerManager.respawnPlayer constructs a plain ServerPlayerEntity. One exact constructor redirect now creates a createFakePlayer replacement preserving isAShadow for fake players, and preserves vanilla construction for real players. Vanilla still owns inventory/state copying and connection handoff. Four wiring tests. Cached Minecraft1.21.11 PlayerManager was actually transformed with Mixin0.8.7: one target constructor replaced, other field/method/constructor operations retained, ASM BasicVerifier passed. The isolated harness used a signature stub for the fake-player factory; central compilation below checks the actual factory. Full-mod-list compatibility and End gameplay remain unrun.

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/mixin/PlayerManagerRespawnMixin.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/resources/frens.mixins.json`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/FakePlayerRespawnWiringTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java`

### 3. Resurrection connection and payment ordering

The old ritual could consume the offering/anchor and mark alive before an asynchronous connection succeeded. Exact server/alias attempt tokens now reject duplicates and release on all terminal paths. Guarded connection runs admission, actual connection and commit in the same server-thread callback. Both guard points check current server/config/world/player/authority/death or quest eligibility and live offering/loaded charged anchor. Existing-bot teleport checks its result. Anchor mutation must succeed before item debit. Failure reports what was retained/used and warns if a bot may already be connected. Twelve pure/wiring cases. Death history and bot inventory storage remain intact. A process crash or a post-connect refusal can leave a live bot before payment. No persistent transaction journal or unsafe rollback/refund is claimed.

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/CompanionResurrectionService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/RitualAttemptPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/RitualAttemptPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java`

### 4. Name-only integrated-host authority

Cached vanilla bytecode showed IntegratedServer.isHost compares names. Shared BotAccessGate now checks the player belongs to the captured server and matches its host profile UUID through a pure policy. Dedicated/null/mismatched-context paths deny host authority. All production raw vanilla isHost calls were replaced; operator branches remain. Four new cases, HostPermissionPolicy9/9. This fixes different-UUID name matches. It does not authenticate a spoofed identical UUID on an unauthenticated server.

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/HostPermissionPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/HostPermissionPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/network/BotAccessGate.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Frens.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/network/AiSetupNetworkManager.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/network/configNetworkManager.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/network/ChestRegistryNetworkManager.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java`

### 5. Ambiguous saved positions and concurrent publication

Ordinary position reads/clears use only the current save fingerprint and never move/delete the bare-level-name source. Explicit confirmed absent-only recovery copies the immutable BotState. A shared class monitor serializes all mutable state/read/publication paths, including disconnect saves; failed recovery conditionally removes only its own inserted value. Stateless fingerprint utilities remain lock-free to avoid inversion with registry locks. Atomic temporary-file replacement and an unreadable-original write guard protect persistence. Nine pure cases, including two rollback regressions. Actual BotState has x/y/z/yaw/pitch only, no dimension field was invented.

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BotWorldStateService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/LegacyWorldStateRecoveryPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/LegacyWorldStateRecoveryPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Commands/LegacyWorldStateCommand.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java`

### 6. Furnace/beehive registries and crafting/hunt history isolation

These stores still used level-name keys, leaking state between same-named saves. Ordinary access now uses save fingerprint plus dimension; read-only lookups do not install empty destinations that block recovery. Legacy furnace/beehive slash keys and history colon keys remain untouched. Explicit recovery deep-copies whole payloads under the store lock, refuses existing destinations and rolls back failed writes. Temporary-file replacement and unreadable-file guards apply. Earlier beehive lock and unloaded-chunk retention fixes remain. Shared registry policy has11 tests after structure fixtures. Chest registry was already fingerprinted with live-position migration checks and was not rewritten.

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BotFurnaceRegistryService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BotBeehiveRegistryService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/CraftingHistoryService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/HuntHistoryService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/LegacyRegistryRecoveryPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/LegacyRegistryRecoveryPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Commands/LegacyWorldStateCommand.java`

### 7. Fortification and mapped-village isolation with complete recovery

The shared canonical world-key producer now fingerprints both stores and existing consumers. Recovery deep-copies complete WorldData payloads and rewrites only copied embedded fortification.worldKey and village.worldKey/dimension. This matters because subsequent fortification save uses the embedded key. Ownership, allowlists, schema, progress, hull, terrain and moat data remain. Eager normalization of every legacy entry was removed; normal current-save load/list repairs only requested current records. Same guarded atomic persistence and absent-only recovery apply. Two preservation fixtures plus two source-wiring cases cover adapters. Fixtures use JSON copies and source assertions, not actual typed-service copy invocation; source review found no field omission. Full in-game progress recovery remains unrun.

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/construction/FortificationPersistenceService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/MappedVillageService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/LegacyRegistryRecoveryPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/LegacyStructureRecoveryWiringTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Commands/LegacyWorldStateCommand.java`

### 8. Hunting settings isolation and defensive snapshots

An additional source audit found HuntConfigService still sharing depopulation/zone/target settings by raw level name. UI and hunt probes also created persistent empty destinations. Reads now use fingerprint keys and return detached defaults/copies without claiming a destination. Explicit writes create state and copy caller-owned settings/lists. All actual getConfig callers were checked and only read snapshots. Legacy hunt-config recovery preserves originals and uses the same confirmed absent-only deep copy and guarded atomic writes. Four pure data-copy tests. Service/command behavior in a real server remains unrun.

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/HuntConfigService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/HuntConfigCopyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Commands/LegacyWorldStateCommand.java`

**Recovery interface and preservation contract:** `/bot legacy-state` lists pending legacy positions. `/bot legacy-state preview <alias>` displays the source, coordinates and quoted confirmation command. `/bot legacy-state import <alias> "<current-save-fingerprint>"` copies a position without spawning or moving a companion. For the current dimension use `/bot legacy-state registry preview <store>` then `/bot legacy-state registry import <store> "<current-save-fingerprint>"`. Stores: `furnaces`, `beehives`, `crafting`, `hunt`, `fortifications`, `villages`, `hunt-config`. Server admin/host authority is checked at command admission and execution. Preview reports file, source/target key, dimension, count, up to20 examples and existing-target status. Unknown fingerprints, wrong confirmation, existing destinations and failed persistence visibly refuse. Imports never merge or overwrite a current bucket and always retain the legacy original. Admin login notices expose retained records, even where a registry destination already exists. These informational notices can repeat after recovery because originals remain. If ordinary gameplay already created a destination, recovery deliberately refuses and retains both datasets for review.

**What remains / next session:**

- Normal Java21 Gradle/remap build and explicitly authorized field checks: online spawn during shutdown; End exit with actual mod list; owner/op ritual with delayed lookup, changed offerings/anchor, duplicate requests and refused connection; different-UUID host-name match; two same-named saves with independent records, preview/import/refusal and full structure progress/ownership. No automatic deployment or game access.
- Ritual crash atomicity is not solved by a persistent journal. Post-connect refusal can leave a connected bot. Failures disclose retained/used resources. UUID matching is not authentication of identical spoofed IDs.
- Source triage found no small current defect in duplicate sunset chat: one exact emitter, same-base episode guard, accepted return has no second emitter; overhead voice subtitle is separate. Keep open pending actual delivery logs. Default unified NO_PATH already returns failure; legacy blind-fallback headline is not established for that path.
- Follow Y/stair/canopy loops, scaffold descent/pillar reports, shelter breakfree and non-suffocating underground farm recovery need current field evidence or deliberate navigation/mining policy. Do not remove collision/soil/safety gates based on old headings. Door architecture still requires discussion. Sunset short-probe semantics and surface-staging performance likewise need measured/current scope. `soilFail` ratios alone do not prove base-selection failure.
- Multiplayer roster payload is a larger deferred feature: server-owned roster response, authorization/filtering, and loading/unknown UI are missing; OpenRosterPayload only opens the screen. Typed owner commands/SPAWN, slot refusal, supplies WaitMode/need bundling and other feature/design items remain separately scoped.
- Inventory deny text is not established: both command open branches check ownership first. HEARD privacy is not established: DIRECT-channel digest visibility is private independently, party speech is intentionally shared and private/owner/party filters remain. Existing craft-largest-drop, craft-server-walk and Pocket timeout headlines already have fixes.
- LearningModeService level-name/sanitized folders can mix historical session lists/latest reports across saves; only recording/list/report paths use them. No live restore/cache path or ordinary overwrite was found. Keep as separate artifact-provenance follow-up, do not silently move old folders. Historical "server is full" has no demonstrated causal connection to these store collisions and is not marked fixed.
- Original199/200/201/219/222 were already verified complete in committed history in this checkout; this wave does not redo them. Preserve server-side wall authorization and refusal, loaded-half-only chest behavior and queued ask/withdrawal task tickets.

**Hardest review:** complete structure copy and embedded rekeying; legacy isolation without hidden empty destination creation; position serialization/rollback without lock inversion; ritual connect-to-payment ordering and all failure paths; runtime Mixin coexistence. Perfectionist concern: Minecraft-facing integration and process-crash transactions remain unverified or incomplete, and structure tests combine representative JSON fixtures with source wiring. Pragmatic scope: preserve every original, refuse ambiguous/overwriting recovery visibly, use current destination state as authority, and keep changes bounded instead of introducing a new persistence/transaction framework. No exact unique remaining bug count is asserted from broad checklist lines.

**Handoff paths:** only `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/RALPH_TASK.md` and `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/changelog.md`; no `docs/` edits. All edits remain on disk, uncommitted.

## Session Handoff 2026-10-22 (failed-route cache races fixed) — shipped in 1.1.291

**Current state:** original checkout `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12`, version `1.1.290-release+1.21.11`. **69 pending source/handoff files: 67 Java plus handoff/changelog.** Prior cumulative manifests below plus the two newly pending Java paths here cover current work. Preserve every earlier edit and unrelated `.superpowers/` / `logs/*.gz`. No staging, commits, version bump, game/PrismLauncher access, packaging, deployment or publication. One bounded fix complete; no implementation remains in progress.

**Actual cause:** MovementService worker paths query/log FAILED_ROUTES while lifecycle cleanup can clear its maps. `remainingMs` first called isFailed, then re-read the outer bot map without a null check; clearing between those operations caused a reproducible NullPointerException. Stale expiry reads unconditionally removed a route/sidestep even after another operation renewed it. Read-then-put refresh/log operations could also overwrite each other's new TTL or throttle timestamp. ConcurrentHashMap protected individual calls, not these sequences.

**Changed paths (one four-file phase):**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/FailedRoutePolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/FailedRoutePolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/RALPH_TASK.md`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/changelog.md`

**Fix:** remaining-time lookup uses one captured per-bot map/entry snapshot. Route/sidestep expiry uses conditional removal of only the observed value. Route refresh and log claims use per-key atomic compute/computeIfPresent, retaining both current TTL and throttle state. Detached maps after a concurrent clear remain detached; readers do not recreate them. No global lock, pathfinder/escape selection change, TTL or log-interval change. A racing read may return its pre-clear snapshot, consistent with reading before clear; a later lookup sees zero.

**Verification:** five deterministic Minecraft-free interleaving tests reproduce all five defects against unchanged production source: **9 passed / 5 failed**, including the null-pointer. The fixture injects a competing operation at a map access, avoiding flaky sleeps/thread scheduling. Final route policy **14/14**; route/terrain/movement-abort focus **44/44**. Java21 `javac --release 21 -proc:none` compiled **all67 pending Java files**. Full direct JUnit **3599/3599 passed, zero failures/skips/aborts**, including voice loopback tests; five more than prior3594. Cached Byte Buddy agent, experimental flag, test resources and build-local temp used. Source/diff review and `git diff --check` passed. Scratch under `build/verify-route-cache/` removed after verification. No Gradle/remap or live-game result; normal Java21 Gradle build remains required before release.

**Hardest review / limits:** verify renewal is preserved when an expiry read or skip log observes an older entry; verify clear during remaining-time lookup neither throws nor resurrects cache state. Perfectionist concern: deterministic fixture uses reflection to replace private inner maps and needs updating if storage shape changes; it exercises legal competing-operation orderings rather than a Minecraft lifecycle simulation. Pragmatic scope: atomic map operations fix the demonstrated races without introducing a scheduler or service lock. No claim that the historical field stuck symptoms came from these races. Preserve all previous field checks, save-migration and async-spawn/recruitment limits below.

## Session Handoff 2026-10-22 (five source-confirmed fixes verified) — shipped in 1.1.291

**Current state:** original checkout `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12`, version `1.1.290-release+1.21.11`. **67 pending source/handoff files: 65 Java plus handoff/changelog.** This wave adds eight newly pending Java paths; SurvivalRecruitmentService was already pending. Prior cumulative manifests plus paths below cover current work. Preserve every earlier edit and unrelated `.superpowers/` / `logs/*.gz`. User requested five fixes; all five below are implemented and centrally verified. No staging, commits, version bump, game/PrismLauncher access, packaging, deployment or publication. No implementation remains in progress.

**Five bounded phases and exact changed paths:**

**1. Farm assessment**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java`

**2. Diagonal wall rescue**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BotRescueService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/WallClipNudgePolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/WallClipNudgePolicyTest.java`

**3. Recruitment authorization**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/SurvivalRecruitmentService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/RecruitmentAccessPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/RecruitmentAccessPolicyTest.java`

**4. Bundle food facts**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/souls/SoulSnapshotBuilder.java`

**5. Furnace interaction**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/SmeltingService.java`

**1 cause/fix:** new-site assessment precedes tree clearing. Its target Y used walkable terrain but its second slope loop treated a trunk within target+4 as the surface and rejected flat forest as cut-too-deep. Only that assessment loop now uses walkable feet Y minus one, reusing the water sample. Cut/fill/water thresholds and missing-surface refusal remain; tree-inclusive findSurfaceY used by actual clearing is unchanged. Existing helper has a 32-block scan limit/heightmap fallback, so very tall canopies remain a limitation.

**2 cause/fix:** edge/spawn clip scans enumerated four cardinal columns, missing a diagonal wall overlapped by the body. Pure WallClipNudgePolicy lists only overlapping neighboring columns and bounded movements away. Both paths check translated whole-body space plus prior feet/head hazard checks before one <=0.4-block nudge. Review caught that a normalized diagonal alone still clips at center(.99,.99); axial alternatives and a regression now cover every extreme corner. No Y/mining/support changes. Seven new tests (six numerical geometry, one wiring): first red3pass/3fail, then green6; extreme regression red6pass/1fail then final7/7. Scratch reflection runner used JUnit assertions; central verification used full JUnit.

**3 cause/fix:** confirmation trusted a client alias and reused/teleported that bot before ensureOwner, which deliberately does not overwrite an existing owner. A forged alias could target another companion, and a legitimate configured alias could already belong to another player. Server-side service admission now uses the server-selected alias, checks requested alias against it and checks recorded ownership before consuming the handshake or mutating/spawning. Empty legacy requests still use the server alias; case/whitespace variants work. Malformed owner UUID fails closed. Visible refusal preserves the grant. Delayed setup rechecks the original recruiter's recorded ownership before lookup/mutation; transfer/release stops setup and tells the online recruiter. This is reached from the server network handler; UI gates are not relied on. Seven new pure cases and two wiring regressions; before service wiring7pass/2fail, final9/9. Earlier real-delay change preserved. This does not undo an already-started async createFake or redesign early recruited-state persistence/same-owner reset generations.

**4 cause/fix:** snapshot item facts included bundled food but foodItems counted only direct main slots, causing the same prompt to say 'no food at all'. Food count now uses existing InventoryIterator.count across carried/bundled stacks (including offhand), preserving the prior FOOD predicate. Main-slot occupancy and item digest are unchanged. Existing iterator tests cover bundle traversal/count reduction; no gratuitous new helper/test.

**5 cause/fix:** synchronous cooking's server poll and final evacuation could transfer output/refuel an old furnace after the live bot left its dimension/reach. Initial load had coordinate reach but no world match. All three callbacks now require same world and existing bounded interaction checks before item/fuel/output mutation. Poll refusal cancels its future and breaks rather than producing a new false-success result; furnace contents stay put. The interaction helper raycasts and may open a door, but does not walk/sleep/wait on the server thread. Existing unrelated gone/error result semantics are unchanged. No new helper/source-mirror tests.

**Verification:** Java21 `javac --release 21 -proc:none` compiled **all65 pending Java files**. Focused recruitment/geometry/iterator/station/cooking/farm policy tests **96/96**. Full direct JUnit **3594/3594 passed, zero failures/skips/aborts**, including voice loopback tests;16 tests more than prior3578. Cached dependencies, Byte Buddy agent, experimental flag, test resources and build-local tmp used. Independent read-only review found no blockers in recruitment and farm/geometry; controller reviewed all diffs, including furnace guard ordering/cancellation. `git diff --check` passed. All scratch from `build/verify-five-central/` and `build/verify-five-diagonal/` removed after success. No Gradle/remap or live-game result; normal Java21 Gradle build remains required before release. Tests for farm sampling, snapshot production wiring and furnace relocation are indirect existing coverage plus source review, not live simulation.

**Handoff edits:** only `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/RALPH_TASK.md` and `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/changelog.md` after verification. Code phases touched1/3/3/1/1 files; no broad refactor or shared output builds. Preserve all prior limits and field checks below.

**Hardest review / field checks:** flat wooded farm versus genuinely high dirt/deep hollows; all diagonal head/feet clips and extreme corners without pushing into opposing collision or hazards; forged recruitment alias, already-owned configured alias, malformed owner and ownership transfer before delayed setup; bundle-only/offhand food prompt; portal/teleport/walk out of furnace reach while cooking, verifying both inventories stay unchanged by refused transfers. Perfectionist concern: Minecraft-facing paths need field verification, and existing early spawn persistence plus lookup budget remain. Pragmatic scope: reuse established samplers/iterators/scheduler/interaction checks and keep protections instead of adding new frameworks.

**Triage, not silently fixed:** unified NO_PATH already returns failure (blind legacy fallback report is not established for default path); sunset short-probe semantics and underground farm recovery need deliberate policy/field scope. Save-key collisions are real but migration needs durable per-save decisions/provenance; do not blindly move legacy state into a fresh save or hide old history. Craft largest-stack/server-thread walking and Pocket wait-timeout headlines already have fixes; only the residual bundle prompt and remote-furnace mutation were changed here. Five actual source defects fixed does not certify an exact remaining unique bug count from the143 broad checklist lines.

## Session Handoff 2026-10-22 (recruitment post-spawn retries really wait) — shipped in 1.1.291

**Current state:** original checkout `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12`, version `1.1.290-release+1.21.11`. **59 pending source/handoff files: 57 Java plus handoff/changelog.** Prior cumulative manifests below plus the newly pending SurvivalRecruitmentService path here cover current work. Preserve all earlier edits and unrelated `.superpowers/` / `logs/*.gz`. No staging, commits, version bump, game/PrismLauncher access, packaging, deployment or publication. This bounded task is finished; no implementation remains in progress.

**Actual cause:** `schedulePostSpawnSetup` used `server.send(new ServerTask(runAt, ...))` with a future creation tick for its intended initial one-tick check / subsequent one-second retries. ServerTask creation time is not a deadline (cached 1.21.11 MinecraftServer bytecode, confirmed in the preceding phase). All eight retries could execute back-to-back while an online-mode profile lookup was still pending, report failure and miss setup when the entity finally appeared.

**Changed files (one three-file phase):**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/SurvivalRecruitmentService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/RALPH_TASK.md`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/changelog.md`

**Minimal fix:** replace only the scheduling wrapper with existing `TaskService.runOnServerAfterTicks(server, delay, ...)`. Initial check is roughly 50ms later; eight possible retries are roughly one second apart. The helper already checks shutdown/current-server identity before dispatch and on the server thread before executing. A direct comparison with HEAD verified the entire service is otherwise byte-for-byte identical: retry budget, live target lookup, real-player refusal, setup mutations and success/failure notifications remain intact. No new policy/helper/test class was needed for this wrapper replacement.

**Verification:** Java 21 `javac --release 21 -proc:none` compiled **all 57 pending Java files**. Existing focused spawn/result/delay checks **18/18**. Full direct JUnit **3578/3578 passed, zero failures/skips/aborts**, including voice loopback tests. No new test cases were added; the existing tests do not simulate recruitment timing in Minecraft. Cached Byte Buddy agent, experimental flag, test resources and build-local temp paths were used. Source/diff review and `git diff --check` passed. Scratch under `build/verify-recruitment-delay/` removed after verification. This is not a Gradle/remap or live-game result; normal Java 21 Gradle build remains required before release.

**Hardest review / field checks:** dedicated online-mode recruitment with a profile lookup taking a few seconds: checks should be separated by about one second and registration/meeting-site setup should run once the bot arrives. Stop/switch worlds between retries: no stale setup on the old server. Offline synchronous spawn still gets the initial delayed setup. A lookup slower than the retained roughly eight-second budget can still finish after failure is reported; recruitment ownership/state is still persisted before verified arrival. This task does not redesign that transaction, add owner-transfer/setup identity guards, or change resurrection handling. Perfectionist concern: timing and slow-profile success remain field-unverified; pragmatic scope: reuse the existing lifecycle-safe scheduler without changing the setup transaction or adding duplicated infrastructure. Preserve all earlier limits and field checks below.

## Session Handoff 2026-10-22 (sleep dialogue and co-sleep retry really wait) — shipped in 1.1.291

**Current state:** original checkout `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12`, version `1.1.290-release+1.21.11`. **58 pending source/handoff files: 56 Java plus handoff/changelog.** Prior cumulative manifests below plus the two newly pending paths here cover current work. Preserve all earlier edits and unrelated `.superpowers/` / `logs/*.gz`. No staging, commits, version bump, game/PrismLauncher access, packaging, deployment or publication. This bounded task is finished; no implementation remains in progress.

**Actual defect:** BotWakeUpDialogueService queued its intended 40-tick wake greeting and 60-tick busy-bot retry with `server.send(new ServerTask(futureTick, ...))`. Cached 1.21.11 MinecraftServer bytecode confirms `canExecute(ServerTask)` may execute whenever `shouldKeepTicking()` is true, regardless of that future timestamp: creation time is not a delay. The retry also omitted the initial path's current-controller, registration and zzz-cycle checks, allowing a former controller's queued retry or duplicate sleep cycle.

**Changed files (one four-file phase):**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BotWakeUpDialogueService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/WakeUpDelayWiringTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/RALPH_TASK.md`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/changelog.md`

**Implementation:** both callbacks now use existing `TaskService.runOnServerAfterTicks`, which waits off-thread and rechecks lifecycle/server thread before running the body. This means roughly two/three real seconds (50ms per requested tick), not exact game-tick counting under lag. Greeting checks that the captured bot is still the same online registered entity, awake and not removed. Retry resolves the live bot/controller again, requires the sleeping player still controls it, and skips unregistered bots and zzz/logoff cycles before cooldown stamp or sleep scheduling. Existing range/world/time/task/cooldown checks and worker scheduling stay intact. No new scheduler or duplicate policy was introduced; no TaskService edit.

**Verification:** five new Minecraft-free source-wiring regressions all failed against unchanged production source, then passed. Focused wiring/cooldown/zzz/controller tests **27/27**. Java 21 `javac --release 21 -proc:none` compiled **all 56 pending Java files**. Full direct JUnit **3578/3578 passed, zero failures/skips/aborts**, including voice loopback tests. Cached Byte Buddy agent, experimental flag, test resources and build-local temp paths were used. Source/diff review and `git diff --check` passed. Scratch under `build/verify-sleep-delays/` removed after verification. This is not a Gradle/remap or live-game result; normal Java 21 Gradle build remains required before release.

**Hardest review / field checks:** busy companion's task clears within three seconds while its controller stays asleep; retry should run later and start sleep once. Wake greeting waits through the two-second screen fade. Transfer ownership, enter a zzz cycle, remove/recreate the bot, wake the player or stop/switch worlds before callbacks: stale work should not act or stamp retry cooldown. Existing probability/voiced-line cooldown can suppress a greeting, so absence alone is not timing proof. Perfectionist concern: these new regressions pin source wiring, not a live Minecraft timing simulation. Pragmatic scope: use the existing lifecycle-safe helper and eligibility policies rather than duplicate them in another scheduler. Preserve all prior field checks and unresolved spawn/resurrection limits below.

**Scope triage:** the old misleading-inventory-denial backlog claim is not a demonstrated current ownership bypass: both `/bot open` branches already check access and explicitly report permission refusal before calling BotInventoryAccess. It was left untouched; no broad inventory refactor or full-state update changes were made.

## Session Handoff 2026-10-21 (deferred online spawns reject stale server callbacks) — shipped in 1.1.291

**Current state:** original checkout `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12`, version `1.1.290-release+1.21.11`. **56 pending source/handoff files: 54 Java plus handoff/changelog.** The prior cumulative manifests below plus the three newly pending Java paths here cover the changes. Preserve all earlier edits and unrelated `.superpowers/` / `logs/*.gz`. No staging, commits, version bump, game/PrismLauncher access, packaging, deployment or publication. This bounded task is finished; no implementation remains in progress.

**Actual cause:** online-mode `createFake` chained profile lookup into an unobserved `thenAccept`, then queued a connection with no server-lifecycle guard. A late callback could target a stopped/replaced server; dispatch rejection failed only the discarded stage, leaving the returned spawn future pending. Cancelling that returned future did not prevent pending connection work.

**Changed files (one five-file phase):**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/DeferredSpawnPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/DeferredSpawnPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/RALPH_TASK.md`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/changelog.md`

**Implementation:** pure Minecraft-free `DeferredSpawnPolicy` observes lookup, dispatch and connection failures in the returned future. It checks lifecycle before dispatch and inside the queued body and skips already-completed/cancelled work. Production uses the existing TaskService stop flag, captured server stopped state, and current-server identity semantics. Logging observes the original future without replacing it, preserving cancellation. Existing local-profile fallback after lookup failure and synchronous offline/integrated spawning remain intact. No broader spawn or resurrection transaction changes.

**Verification:** nine new tests: eight pure ordering/failure/cancellation/lifecycle cases and one source-wiring check. Before production wiring, **8 passed / 1 failed**; final spawn/lifecycle focus **20/20**. Java 21 `javac --release 21 -proc:none` compiled **all 54 pending Java files**. Full direct JUnit **3573/3573 passed, zero failures/skips/aborts**, including voice loopback tests. Cached Byte Buddy agent, experimental flag, test resources and build-local temp paths were used. Initial compile accidentally included Fabric source jars and failed on their untransformed private constants; excluding source/javadoc jars resolved it without source changes. Direct source review and `git diff --check` passed. Scratch under `build/verify-deferred-spawn/` removed after verification. This is not a Gradle/remap or live-game result; run normal Java 21 Gradle build before release.

**Limits / hardest review:** online dedicated-server lookup completion after stop/world replacement, stop after dispatch but before execution, dispatch rejection, caller cancellation before lookup/while queued, and normal fallback-profile spawning need authorized field checks. Already-started connection and lookup HTTP work are not interrupted or rolled back. A server that accepts then silently discards a queued task can still leave its future pending: this patch adds no shutdown registry/timeout. The pre-dispatch identity check follows existing TaskService semantics; server-thread recheck is authoritative. Perfectionist concern is this remaining accepted-but-discarded queue lifetime; pragmatic scope is preventing stale connection and observing actual rejection without introducing global tracking. Preserve all earlier field checks below.

**Separate follow-up discovered, untouched:** `CompanionResurrectionService` consumes ritual resources/anchor and updates alive state before asynchronous `createFake` completes, and ignores the future. Correct failure handling needs a deliberate reservation/refund and persisted-state design, not merely deleting historical death records (retrieval needs those). Do not call this fixed by the lifecycle guard.

## Session Handoff 2026-10-21 (respawn replies recheck authority through queued spawning) — shipped in 1.1.291

**Current state:** original checkout `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12`, version `1.1.290-release+1.21.11`. **53 pending files: 51 Java files plus handoff/changelog.** Preserve all earlier edits and unrelated `.superpowers/` / `logs/*.gz`. The complete prior manifest below plus the three newly pending paths in this task covers current work. No staging, commits, version bump, game/PrismLauncher access, artifact packaging, deployment or publication. Nothing remains in progress.

**Actual defect:** BotRespawnPromptService trusted the original recipient of a death-time respawn question. A later ownership transfer did not stop their yes/no; yes spawned under a console source. Both maps were removed before validation. Permission could also change after an answer but before its queued console action. Unconditional reverse-map removal could erase the same owner's newer route to another bot.

**Implementation, verified in bounded phases:**

Phase A (two files) shares the existing owner-first/recruiter-fallback lookup with shelved bots through `resolveControllerUuidForAlias`. The live-entity accessor delegates to it; owner parsing remains one source. Existing recorded-owner precedence, offline identity, alias matching and legacy recruiter rules are unchanged. Eight controller tests pass.

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/CompanionControllerPolicyTest.java`

Phase B (three files) adds a pure reply policy requiring the original reply route and current controller/admin authority. Chat checks before either answer removes the request; yes keeps it until the queued action checks the exact Pending identity, sender availability and current authority again before invoking the console command. No uses conditional removal. Refusal visibly retains the alias request and retires only the sender's matching reverse route. Failed command execution restores only an unreplaced request, preserves a newer route and tells the player. Existing automatic/control-triggered spawning retains its behavior; its shared console command executor now returns the result to chat callers.

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BotRespawnPromptService.java` (newly pending)
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/RespawnReplyPolicy.java` (new)
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/RespawnReplyPolicyTest.java` (new)

Handoff phase updates only `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/RALPH_TASK.md` and `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/changelog.md` after code verification.

**Verification:** ten new respawn tests (five pure authority cases, five source-wiring checks) plus one controller-accessor regression. Before the service change, five source tests failed while the five pure tests passed. Final focused reply/controller/access tests **37/37**. Java 21 `javac --release 21 -proc:none` compiled **all 51 pending Java files**. Full direct JUnit **3564/3564 passed, zero failures/skips/aborts**, including voice loopback tests; 11 more than the prior3553. The cached Byte Buddy agent, experimental flag, test resources and build-local temp paths were used. Independent read-only review found no blockers. `git diff --check` passes. All runner/classes/logs under `build/verify-respawn-authority/` were removed. This is not a Gradle/remap or live-game result.

**Limits and reviewer focus:** ownership transfer does not automatically forward an old prompt to its new owner; the request is retained for existing authorized Bot Controls/spawn paths. Owner-level typed SPAWN support remains its separate backlog item. A positive command result means accepted command, not verified asynchronous entity join. Hardest checks: transfer before reply and between reply/queued dispatch; disconnected sender; replaced/cleared Pending before dispatch; admin/host still authorized on their own route; two dead bots sharing one controller; spawn refusal/error preserving the current request. Run those in a future authorized game check, and normal Java 21 Gradle build before release. Preserve every previous field check below.

## Session Handoff 2026-10-21 (resume answers recheck current bot access) — shipped in 1.1.291

**State:** same original checkout `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12`, version `1.1.290-release+1.21.11`. **50 pending files: 48 Java files plus handoff/changelog.** This phase adds two paths to the complete 48-file manifest in the previous handoff below. All earlier edits and unrelated `.superpowers/` / `logs/*.gz` remain preserved. No staging, commits, version bump, game/PrismLauncher access, artifact packaging, deployment or publication.

**This bounded phase (four files):**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/SkillResumeService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/SkillResumeReplyAccessTest.java` (new)
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/RALPH_TASK.md`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/changelog.md`

**Actual defect and fix:** `SkillResumeService.handleChat` associated a death-time continue prompt with its original responder, then let that responder's `no` call `clear(botUuid)` without checking current ownership. A former owner's `yes` also consumed resume state before a later dispatch gate could reject it. The chat answer now resolves the live target by saved UUID and uses existing `BotAccessGate.permits` before either branch. Current owner/op/host rules are unchanged. If unavailable or unauthorized, it tells the sender the saved job was kept, consumes this answer, and compare-removes only that responder's observed route (`remove(key, pending)`). Main paused-job, awaiting-decision, deferred and sunrise state stay untouched. Retiring the route prevents stale prompts swallowing that player's future yes/no messages, while preserving a newer route installed concurrently. Authorized resume/stand-down behavior is unchanged. The direct manual/automatic resume paths were not refactored.

**Verification:** four new Minecraft-free source-wiring tests pin current-target authorization before both mutation branches, UUID lookup, visible refusal with job preservation/conditional route cleanup, and unchanged authorized branches. Three tests failed before the access gate; a separate red regression caught missing route retirement during review. Existing pure `BotAccessPolicyTest` supplies owner/op/host decision coverage; no new authorization policy was invented. All 48 pending Java files compiled with Java 21; final service/test edits were recompiled. Focused reply/access/resume tests **49/49**. Final full direct JUnit **3553/3553 passed, 0 failed/skipped/aborted**, including voice loopback tests. Source/diff review found no remaining blocker; `git diff --check` passes. All scratch under `build/verify-resume-reply/` was removed. This is not a Gradle/remap build or live-game test.

**Review/field checks:** hardest edge is refusing a stale responder without deleting the current owner's resumable job or a replacement prompt. Pause a job at death, transfer ownership, then answer yes/no as former owner: visible refusal, no resume/stand-down, saved job retained. Verify the current owner can manually resume it, an authorized op/host can still answer, and later unrelated yes/no messages are not captured by the retired route. An unavailable bot also refuses while retaining the job; retry through manual resume when it returns. Preserve all prior field checks below. Before release run the normal Java 21 Gradle build with normal access; no release work was authorized here. Nothing remains in progress.

## Session Handoff 2026-10-21 (second reliability wave verified; preference dispatch fixed) — shipped in 1.1.291

**Current state:** checkout `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12`, version `1.1.290-release+1.21.11`. **48 pending files: 46 Java sources/tests plus this handoff and changelog.** Preserve all earlier work and unrelated `.superpowers/` / `logs/*.gz`. The app folder is not this repository. The usage interruption left playback tests unfinished; Bradley reset usage and requested continuation plus a new task. That work is now verified, with no task left in progress. No staging, commits, version bump, PrismLauncher/game access, deployment, or publication.

**New task, preference dispatch (two files):** `src/main/java/net/wcfcarolina13/Frens.java` and new `src/test/java/net/wcfcarolina13/network/PlayerPreferenceReceiverWiringTest.java`. Removed only the extra `context.server().execute` around the preserve-gear and precious-food UPDATE receivers. Cached Fabric networking API 5.1.0 source explicitly guarantees the callback runs on the server thread (ServerPlayNetworking.java lines 46 and 326). Sender identity, every admission/full-state update, cooldown, rate-limit resync, coalesced save and preserve-gear rising-edge cleanup remain intact. Fabric's initial packet dispatch remains; this does not mean zero server tasks per incoming packet. Five new wiring tests reproduce one failure on HEAD and pass with the fix; preference wiring/policy focus **15/15**.

**Owner and food-consent phases:** `CompanionCommunicationPolicy`, new `CompanionControllerPolicy` + test, `FoodConsumptionConfirmationService`, `FoodConfirmReplyPolicy` + test. Recorded ownership stays authoritative even when offline; only ownerless companions fall back to their recruiter. One offline-independent controller identity accessor feeds online routing and cached-consent provenance. Stale food answers visibly refuse before decision changes, consume only that responder's pending entry, and leave items/new-owner prompts untouched. Approvals, denials and explicit hand-feed grants bind to controller identity. Ownership changes invalidate cached decisions; unchanged owners going offline do not. Explicit ownerless hand-feeds remain valid only while ownerless; chat answers require a non-null controller. Seven new controller tests and nine new food tests; final focused **28/28**. Implemented in separate 3/3/2/3-file phases. Reviewer found no blockers in routing/reply gates; final cache changes received direct source review.

**Novelty (two files):** `SoulNoveltyPolicy` + test retain distinct normalized recent lines and refresh repeated lines to newest, so rescued exact repeats cannot evict all other history. Three new pure tests; old policy fails one of 26, fixed novelty/controller focus **30/30**.

**Respawn (four files):** `BotEventHandler`, new pure `RespawnHandlingTracker`, new tracker and wiring tests. Per-bot handled ticks stop one bot suppressing another's fallback. Captured lifecycle identity plus current server/registration checks reject old callbacks after unregister/re-register/reset. Existing ServerTask mechanism and captured tick comparison are unchanged; it is **not a guaranteed five-tick delay**. Nine new tests. **Diagnostics (four files, overlapping BotEventHandler):** new `DiagnosticCooldownPolicy` + test, `Frens`, `BotEventHandler` fix two Long.MIN_VALUE subtraction overflows that suppressed first diagnostics. Clock rollback re-arms logs; recovery/escape mutations remain outside logging gates. Six new tests. Combined respawn/diagnostic/controller focus **22/22**; six source-wiring regressions fail on HEAD. Independent review found no blockers.

**Party lifecycle, completed interrupted work:** `SoulGenerationScheduler` + `SoulProviderSchedulerTest`, `SoulGroupConversationService` + test, `SoulRuntime`, `GroupScenePlayback` + test. Phase A (five files) adds exact party-roster scheduler cancellation and participant revision guards from before HEARD-token creation through history/provider completion, pipeline replacement and queued server-side effects. Phase B (three files) cancels all queued/playing scenes containing a dead/removed bot, independent of the current generation pipeline. Pending synth futures and later lines are discarded; exact-once compare/remove cleanup preserves replacement scenes, releases pending speech-floor reservations and clears scene bookkeeping. Review found a late failure-append notice race; a separate two-file fix and two regressions guard its completion too. **12 new behavioral tests; final party focus 89/89.** An initial compile caught a test using nonexistent `isOpenFor`; it was corrected to the verified `isFloorOpen` API before final verification. Already-sent client audio and already-running synthesis-engine rendering cannot be retracted by the existing API. An already accepted store write is not undone. Source reviews found no remaining blockers; new playback tests received direct source review.

**Final verification:** Java 21 `javac --release 21 -proc:none` compiled **all 46 pending Java files** against cached dependencies and baseline classes. Full direct JUnit: **3549/3549 passed, zero failures/skips/aborts**, including voice loopback tests. This adds 51 tests to the prior wave's 3498. The runner used cached Byte Buddy agent, `-Dnet.bytebuddy.experimental=true`, test resources, and build-local temp paths with scoped access. `git diff --check` passes. All runner/classes/fixtures/logs under `build/verify-grind2/` were deleted after success. This is not a Gradle/remap or live-game result; prior wrapper-lock restrictions remain documented below. No artifact was packaged or deployed.

**Review/field priorities:** ownership transfer while a food question/approval/denial exists; same owner disconnect/reconnect; two bots respawning together; unregister/re-register before a queued fallback; first suffocation/follow-backoff diagnostics; participant death before token/provider reply, during pending synthesis and after pipeline reload; remaining scene lines must stop, while already-sent audio may finish. Check both preference toggles, rapid reversals and current-state resync without losing the final accepted state. Preserve all earlier pending checks (woodcut backoff/cleanup, finding 59 two-bot rerun, beehive contention, movement cancellation, relation toggle). Before future release run normal Java 21 `./gradlew build` with normal build access, then authorized field checks. Historical death records deliberately remain: deleting them would break retrieval; resurrection eligibility needs separate persisted-state/migration scope. BAD_AT mixed-evidence policy, HEARD privacy and user rulings remain open.

**Complete pending-file manifest (absolute paths, including preserved earlier phases):**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/RALPH_TASK.md`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/changelog.md`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/Frens.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/BotActions.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BeehiveVerificationPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BotBeehiveRegistryService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BotIdleHobbiesService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/CompanionControllerPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/DiagnosticCooldownPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/FoodConfirmReplyPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/FoodConsumptionConfirmationService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/HobbyBackoffPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/IdleFallbackGatePolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/RespawnHandlingTracker.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/souls/GroupScenePlayback.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/souls/SoulBanterDirector.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/souls/SoulGenerationScheduler.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupConversationService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/souls/SoulNoveltyPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/PlayerUtils/MiningTool.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/PlayerUtils/ToolSelector.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/network/BotAnvilNetworkManager.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/network/BotEnchantNetworkManager.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/ui/BotAnvilScreenHandler.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/ui/BotEnchantmentScreenHandler.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/BeehiveVerificationPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/CompanionControllerPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/DiagnosticCooldownPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/FoodConfirmReplyPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/HobbyBackoffPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/IdleFallbackGatePolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/MovementAbortPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/RespawnHandlingTrackerTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/RespawnHandlingWiringTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/ToolSearchCancellationWiringTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/souls/GroupScenePlaybackTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupConversationServiceTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/souls/SoulNoveltyPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/souls/SoulProviderSchedulerTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/PlayerUtils/MiningToolPreparationWiringTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/PlayerUtils/ToolSelectorLifecycleTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/network/BotAnvilAccessTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/network/BotEnchantAccessTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/network/PlayerPreferenceReceiverWiringTest.java`

## Session Handoff 2026-10-21 (unreleased reliability and access wave) — shipped in 1.1.291

**Current state:** checkout `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12`, version `1.1.290-release+1.21.11`. Twenty-seven source/test/handoff files are pending across nine bounded phases. Preserve all prior beehive, movement, and relations-supplier edits described below, plus existing untracked `.superpowers/` and `logs/*.gz`. Bradley authorized continuing bounded tasks until near the five-hour usage limit and updating handoffs. No staging, commits, version bump, PrismLauncher/game access, deployment, or publication. Use the explicit original checkout; the app folder is not the repository.

**Anvil access phase (three files):** `network/BotAnvilNetworkManager.java`, `ui/BotAnvilScreenHandler.java`, new `src/test/java/net/wcfcarolina13/network/BotAnvilAccessTest.java` (production paths start `src/main/java/net/wcfcarolina13/`). Opening now checks the existing owner/op/host registry gate before workstation lookup or inventory exposure and tells the player when refused. Live `canUse` rechecks registry/ownership, bot removal, same world, 64-block bot reach, and vanilla workstation reach. Slot clicks and output permission recheck it. Close cleanup remains unguarded so revoked access cannot strand inputs/cursor items. Cached 1.21.11 handler bytecode confirms rename packets and output-taking use the guarded hooks; no guard was added after output has already moved.

**Enchanting access phase (three files):** `network/BotEnchantNetworkManager.java`, `ui/BotEnchantmentScreenHandler.java`, new `src/test/java/net/wcfcarolina13/network/BotEnchantAccessTest.java`. The same server-side open refusal and live owner/registry/bot/world/reach rules apply; slot and enchant-button entrypoints check before mutation. The client prediction path and close-time item returns remain intact. The eight new tests assert source wiring without Minecraft imports and use the existing pure BotAccessPolicy for authorization decisions. Against untouched HEAD source they reproduced six access failures (two cleanup protections already passed); with both fixes, these plus existing policy tests pass 27/27. Independent cross-review found no blockers. Field check still needed: nonowner open denial; owner/op/host open; ownership transfer/despawn/world change while open; remote workstation distance; no mutation after revocation; items returned on closure.

**Idle woodcut backoff phase (five files):** `GameAI/services/BotIdleHobbiesService.java`, `HobbyBackoffPolicy.java`, `IdleFallbackGatePolicy.java`, and their two policy tests. Actual bypasses were a held-axe resource gate, unconditional reset when axe+weapon were present, and level-triggered availability probes. All woodcut routes now respect failure backoff. A per-bot baseline ranks inventory axe readiness at server-observed failure (absent/craftable/held); only strictly better readiness clears it once. Unchanged tools, movement, task/shelter/follow scheduling, or losing/regaining the same tools cannot reset it. Success/session reset clears it; cancellation preserves it. Other hobbies remain available. Four net new tests cover readiness transitions and route gates. The old HEAD gate reproduces two failures in the ten route-policy tests. Review found no storm bypass. Timing limit: an axe delivered between skill return and server completion callback is included in the failure baseline and may leave an unnecessary ordinary backoff until expiry; inventory reads remain on the server thread. Existing abort-latch timing is unchanged. Field check: unchanged held axe with impossible woodcut keeps escalating; supplying an axe after a toolless failure unblocks; movement/task churn does not; other hobbies still run.

**Tool cooldown lifecycle phase (three files):** `src/main/java/net/wcfcarolina13/PlayerUtils/ToolSelector.java`, `src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java`, and new `src/test/java/net/wcfcarolina13/PlayerUtils/ToolSelectorLifecycleTest.java`. The per-bot bundled-tool cooldown map previously retained UUIDs indefinitely. Null-safe `forgetBot` removes only the departing bot, called beside combat cleanup in unregister; `resetSession` clears all entries beside registry clearing in resetAll. Existing cooldown logic is unchanged. These cleanup hooks do not cancel an already-running selector. Four source-wiring regressions fail 4/4 against HEAD and pass with the fix; focused lifecycle/bundle tests pass 21/21; both production files compile. Independent review found no blockers. This resolves the historical 1.1.272 map-pruning deferral in source; no gameplay fault was claimed.

**Mining preparation timeout phase (three files):** `src/main/java/net/wcfcarolina13/PlayerUtils/MiningTool.java`, `ToolSelector.java`, and new `src/test/java/net/wcfcarolina13/PlayerUtils/MiningToolPreparationWiringTest.java`. Both duplicate tool hops previously left mutations queued after their actual 2-second wait expired (historical 750 ms wording is stale). Both now use the existing cancellable server hop, preserving 2,000 ms and exception/null fallback behavior. Mining verifies the actual held selected stack because a hotbar lock can refuse selection. Harmless fallback returns verified actual-held safety (empty or nonfood/nonweapon/drop-preserving), and failure stops each initial branch or re-poll with a visible result before mining proceeds. Fallback priority is unchanged. Six new source tests reproduce five failures on HEAD (priority already passed); focused preparation/server-hop/lifecycle/bundle/harmless tests pass 58/58. Queued abandoned actions cannot start; an action already running may finish. Field check still needed: stall server past two seconds, interrupt tool hop, hotbar-lock rejection, packed inventory with weapons/food, preserved shears on leaves, and mid-mine repoll.

**Task-ticket wiring verification phase (one test file):** new `src/test/java/net/wcfcarolina13/GameAI/services/ToolSearchCancellationWiringTest.java` pins existing `ToolProvisionService.retrieveToolFromChests` original-ticket capture before server refresh, the captured cancellation supplier across queued refresh/candidate attempts, the ask/walk/final withdrawal chain, and the withdrawal facade's per-step and waiting abort checks. Five source tests complement the existing pure identity/cancellation rules; focused wiring/supply/abort tests pass 59/59. No production behavior changed. The source-reading-only backlog deferral is now closed in source; actual stop/replace-during-prompt gameplay remains unverified.

**Verification:** Java 21 `javac --release 21 -proc:none` compiled all 25 pending production/test Java files against cached dependencies/baseline classes. Full direct JUnit: **3498/3498 passed, 0 failed/skipped/aborted**, including voice loopback tests. Cached Byte Buddy agent, `-Dnet.bytebuddy.experimental=true`, test resources, and build-local temp directory were used with scoped access. This is not a Gradle/remap build or live-game test. Prior Gradle wrapper-lock sandbox limitation remains documented below. All scratch runner/classes/fixtures/logs under `build/verify-grind/` were removed after the final successful run. `git diff --check` passes. Independent reviews found no blockers in the workstation, backoff, lifecycle and mining phases; the final ticket tests received a direct source check. Run normal Java 21 `./gradlew build` when build access is available.

**Next:** inspect the combined diff, especially live permission revocation versus close cleanup and failure-baseline reset call sites. Preserve all prior pending field checks (movement stop/timeouts, beehive contention/unknown chunks, relations live toggle, two-bot finding 59). The mining/tool-selection timeout hazard is now fixed in source. Captured task-ticket wiring is now covered by five new tests. No task remains in progress. This wave stopped near the usage reserve after verification and cleanup. BAD_AT inference remains open: failure events exist now, but threshold/mixed-evidence and contradiction rules need a deliberate policy. HEARD privacy is not a demonstrated leak; do not blindly mark shared party speech private.

**Complete pending-file manifest (absolute paths; includes preserved earlier phases):**

- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/RALPH_TASK.md`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/changelog.md`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/BotActions.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BeehiveVerificationPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BotBeehiveRegistryService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/BotIdleHobbiesService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/HobbyBackoffPolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/IdleFallbackGatePolicy.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/souls/SoulBanterDirector.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/PlayerUtils/MiningTool.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/PlayerUtils/ToolSelector.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/network/BotAnvilNetworkManager.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/network/BotEnchantNetworkManager.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/ui/BotAnvilScreenHandler.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/main/java/net/wcfcarolina13/ui/BotEnchantmentScreenHandler.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/BeehiveVerificationPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/HobbyBackoffPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/IdleFallbackGatePolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/MovementAbortPolicyTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/GameAI/services/ToolSearchCancellationWiringTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/PlayerUtils/MiningToolPreparationWiringTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/PlayerUtils/ToolSelectorLifecycleTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/network/BotAnvilAccessTest.java`
- `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad/src/test/java/net/wcfcarolina13/network/BotEnchantAccessTest.java`

## Session Handoff 2026-10-21 (unreleased relations supplier, movement and beehive fixes) — shipped in 1.1.291

**Current state:** original checkout `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12`; version remains `1.1.290-release+1.21.11`. Ten source/test/handoff files are pending across three phases. All are uncommitted, unversioned and undeployed. The app's `/Users/roti/Documents/ChatGPT/Minecraft` folder is not this repository; use the explicit checkout, with scoped write access when required. Preserve prior movement/beehive edits and existing untracked `.superpowers/` / `logs/*.gz`. No commits, PrismLauncher/game access, publication, or deployment were authorized by the repeated "another task" requests.

**This phase (four files):** `src/main/java/net/wcfcarolina13/GameAI/souls/SoulBanterDirector.java`, `src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java`, `RALPH_TASK.md`, `changelog.md`. Implemented the backlog item "SoulBanterDirector.relationsEnabled() static read → supplier." The director now receives a required `BooleanSupplier` and calls it at the existing relation-anchor gate. Runtime's sole constructor call supplies a lambda that rereads current `Frens.CONFIG` on every invocation, preserving null-config → off and live-setting behavior. The old static method/direct global-config reference is removed. No persistence format, relation policy, seed ordering or random consumption changed. The constructor now has one additional required argument; all repository call sites were checked and recompiled.

**Verification:** Java 21 `javac --release 21 -proc:none` compiled all six pending production files and both pending test classes against cached dependencies and existing baseline classes. The existing `SoulBanterDirectorTest`, `SoulRuntimeTest`, `SoulBanterSeedTest` and `SoulRelationOpsTest` passed **98/98 before and after** this behavior-preserving refactor. No new tests were added for the supplier plumbing. Full direct JUnit, including preserved movement and beehive regressions: **3471/3471 passed, no failed/skipped/aborted tests**, including loopback voice tests. Scoped access, cached Byte Buddy `-javaagent`, `-Dnet.bytebuddy.experimental=true`, `src/test/resources` and a build-local `java.io.tmpdir` were used. Independent read-only review found no blockers; `git diff --check` passed. Scratch under `build/verify-relations-supplier/` was removed after verification. This is not a Gradle/remap build or live-game result; the prior sandbox Gradle wrapper-lock limitation is detailed below.

**Backlog status:** the supplier item is checked as source-complete and explicitly unreleased. Other unchecked items, including HEARD-record privacy and axe-bearer woodcut backoff, were not changed. The two previous detailed handoffs below describe the remaining eight pending files and field checks; do not redo that work.

**Next:** review the combined ten-file diff and run `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew build` when normal build access is available. In a future authorized game check, toggle relations off/on during an existing runtime and confirm later banter evaluations follow the new value without rebuilding the director; off should omit relation anchors and on should allow them. Keep all earlier movement timeout/stop-cleanup, beehive contention/unknown-chunk and finding-59 two-bot checks pending. No version bump, packaged JAR, commit or deployment was performed this session.

## Session Handoff 2026-10-21 (unreleased movement dispatch and beehive fixes) — shipped in 1.1.291

**Current checkout:** `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12` (1.1.290). Both the prior beehive fix and this movement fix are uncommitted. `mod_version` remains `1.1.290-release+1.21.11`; no packaged JAR, deployment, or publication this session. The app now opens `/Users/roti/Documents/ChatGPT/Minecraft`, which is not this repository: use the explicit original checkout as working directory. Repository writes/verification there required approved scoped escalation after the app folder changed. Do not move/copy the source into the app folder. No-commit/no-PrismLauncher restrictions remain in force. Existing untracked `.superpowers/` and `logs/*.gz` are untouched.

**Task completed in source:** Bradley asked to take another task. Resolved the 1.1.289 no-cancel movement-dispatch deferral in both `MovementService.runOnServerThread` and `BotActions.runOnServerThread`. Each previously queued a `CompletableFuture` action that could still mutate the bot after a timeout or interruption; missing-server paths also ran the action on the caller thread. Both now use the existing `BotActions.callOnServerThread` / `ServerThreadCallPolicy` hop, whose `FutureTask` is cancelled when the caller stops waiting. Missing server returns false. The policy restores interrupted status and never interrupts the server thread. Cancellation prevents queued actions from starting; an action already running may finish.

**Abort versus cleanup:** `MovementService` retains its captured walk-ticket abort predicate and now reports false if that predicate suppresses steering. `BotActions`' shared low-level helper deliberately has no blanket walk-abort guard: it also dispatches `stop`, sprint/sneak reset, and ranged-use cleanup. Review caught a blanket guard suppressing stop after a revoked walk; this was corrected and pinned by a regression. Direct low-level movement retains its previous walk-abort semantics, with the new timeout cancellation. The old MovementService-specific `Walk step failed` warning was removed with the duplicate wait; failures use the shared false result and caller-specific handling.

**Five files touched in this phase:**

- `src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java`
- `src/main/java/net/wcfcarolina13/GameAI/BotActions.java`
- `src/test/java/net/wcfcarolina13/GameAI/services/MovementAbortPolicyTest.java`
- `RALPH_TASK.md`
- `changelog.md`

**Preserved prior work:** `BotBeehiveRegistryService.java`, new `BeehiveVerificationPolicy.java`, and new `BeehiveVerificationPolicyTest.java` remain uncommitted with this phase. Combined with the five paths above, the pending work is eight files. Do not redo or revert the beehive changes; its detailed handoff follows below. A read-only investigation also confirmed the axe-bearer resource-woodcut backoff bypass still exists, but correcting its reset/probe semantics needs more scope; it was not changed and remains open.

**Verification:** Java 21 `javac --release 21 -proc:none` compiled both movement files, the preserved beehive service/helper, and both changed/new test classes against cached dependencies and existing 1.1.290 baseline classes. Focused movement-abort, server-hop, and beehive tests: **27/27 passed**. Full direct JUnit: **3471 started, 3471 passed, 0 failed/skipped/aborted**. This includes five added movement regressions and the eight prior beehive tests. Both old dispatcher source contracts failed before replacement; an additional red regression caught the review's stop-cleanup hazard. Two tests assert source dispatch wiring because tests cannot load Minecraft types; the behavior tests use the actual pure abort/server-hop policies. A fresh read-only review found no remaining blockers after the cleanup correction. `git diff --check` passed. Temporary runner/classes/logs under `build/verify-movement-dispatch/` were removed after verification.

**Build scope:** The full direct-JUnit run used approved scoped escalation for access to this original checkout and loopback-only test sockets; unlike the earlier restricted run, all four `PocketVoiceEngineTest` cases passed. Cached Byte Buddy `-javaagent`, `-Dnet.bytebuddy.experimental=true`, `src/test/resources`, and a build-local `java.io.tmpdir` were used. Gradle was not rerun: the preceding sandbox attempt failed at the unwritable wrapper-cache lock. This is not a Gradle/remap build or live-game result. When normal build access is available, run `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew build`; focused Gradle filters are `*MovementAbortPolicyTest`, `*ServerThreadCallPolicyTest`, and `*BeehiveVerificationPolicyTest`.

**Pending authorized field checks:** interrupt/stop or replace a chest walk, then resume server ticks: no abandoned queued steering or door action should run, and stop must clear velocity/sprint/sneak/ranged use. Exercise ordinary forward/backward/toward movement, doors and leaf sidesteps. A deliberately stalled server exceeding the hop timeout must not apply a queued step after it recovers; do not expect cancellation to undo an already-started action. Keep the prior beehive contention/unknown-chunk checks, finding-59 two-bot rerun, and all earlier unverified field checks open. No game instance was touched here.

**Next:** review the combined eight-file diff and perform normal build/release work only when authorized. Worker-side cleanup LOS ranking and the documented movement-snap >750 ms fallback remain deferred. The no-cancel dispatch deferral in the historical 1.1.289 handoff is addressed in source by this unreleased phase.

## Session Handoff 2026-10-21 (unreleased beehive lock fix) — shipped in 1.1.291

**State:** worktree `/Users/roti/AI-Player-checkpoint/.claude/worktrees/adoring-hertz-5defad`, branch `claude/jolly-goldberg-b5b405`, HEAD `b0654f12` (1.1.290). The five-file fix below is uncommitted and unversioned; `mod_version` stays `1.1.290-release+1.21.11`. No JAR was packaged or deployed this session. Earlier fixes from this chat are already in history; do not redo them. Existing untracked `.superpowers/` and `logs/*.gz` were left alone.

**Scope and authority:** Bradley selected "Fix the beehive lock risk and update handoffs." Work remains in this checkout. The earlier no-commit/no-PrismLauncher boundary remains in force; this request authorizes the repository handoff/changelog updates. Do not infer permission to deploy or publish. Entry instructions remain the parent `CLAUDE.md` and `docs/GUARDRAILS.md`; tests must not import Minecraft types.

**Fixed in source:** `verifyBeehives` could hold registry `LOCK` while a worker's `ChunkBlockReader.state()` waited for the server thread. A server-thread protection lookup waiting for the same lock completed the deadlock cycle. Verification now snapshots candidate identities and revisions under the lock, reads blocks outside it, and removes known-missing records under the lock only if their registration revision is unchanged. Refreshing a hive increments a transient counter under the same lock, so a concurrent registration survives an older missing verdict. The JSON schema is unchanged. The no-reader verification overload now also uses `ChunkBlockReader`: unloaded/unknown blocks retain protection rather than being marked destroyed. Target-specific checks still skip unrelated hives, and known broken nests still lose protection.

**Changed files (exactly five):**

- `src/main/java/net/wcfcarolina13/GameAI/services/BotBeehiveRegistryService.java`
- `src/main/java/net/wcfcarolina13/GameAI/services/BeehiveVerificationPolicy.java` (new, Minecraft-free locking/version helper)
- `src/test/java/net/wcfcarolina13/GameAI/services/BeehiveVerificationPolicyTest.java` (new)
- `RALPH_TASK.md`
- `changelog.md`

**Verification:** Java 21 direct `javac --release 21 -proc:none` compiled both production files and the new test against cached project dependencies and existing baseline classes. The eight pure tests first reproduced three failures with the old locking pattern, then passed 8/8: lock-free block probes, a simulated server lookup while the worker waits, refreshed/added/replaced registrations, unknown blocks, relevant-only removal, and failed reads. Full direct JUnit: **3466 started, 3462 passed, 4 failed**, no skipped/aborted tests. All four failures are `PocketVoiceEngineTest` loopback binds rejected with `java.net.SocketException: Operation not permitted`. Mockito used the cached Byte Buddy `-javaagent` and `-Dnet.bytebuddy.experimental=true`; `src/test/resources` was on the classpath. This is bounded fallback evidence, not a passing Gradle build or game test. Independent source review found no blockers; overlapping verification passes and legacy null/destroyed-record cleanup lack dedicated new tests. Verification scratch under `build/` was removed after checks.

**Build limitation and next verification:** `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test --tests '*BeehiveVerificationPolicyTest' --console=plain` failed before compilation because `~/.gradle/wrapper/dists/.../gradle-8.14.2-bin.zip.lck` is not writable in the sandbox. When normal Gradle access is available, run that focused command and `./gradlew build` with Java 21. The fallback ran the new class plus the 3458 cached baseline tests. `git diff --check` passed. No full build, remapped artifact, restart, or live Minecraft verification is claimed.

**Pending field check (future authorized sandbox run):** woodcut/cleanup verifies a remembered hive while a server-thread protection lookup runs; server ticks and cleanup must continue. Check an intact loaded hive still protects nearby logs; break the hive and verify those logs become eligible; unload its chunk and verify remembered protection is retained; rediscover/refresh a hive during verification and confirm it is not discarded. Keep all earlier pending checks below, including the two-bot finding-59 rerun, open.

**Next:** review the five-file diff, then perform the normal build/release steps only when authorized. Remaining 1.1.290 deferrals are worker-side LOS ranking in cleanup and the documented >750 ms movement snap fallback; this session does not resolve them. The beehive lock deferral in the superseded handoff below is addressed in source by this uncommitted fix, not field-verified.

## Session Handoff 2026-10-20 (1.1.290) — superseded

**State:** main = origin/main @ 1.1.290. Suite 3458 green. Deployed to Nirn. 1.1.290 = backlog 168, 184 and the 1.1.289 review leftovers (moveTo teleport and snapTo writes on the server thread, death during the resume wait prompts "continue?") (Codex; Fable review, fix wave, follow-up). The threading batch from the triage is now done except 210 (wrong as written). Deferred: `sortByVisibility` in cleanup is off on workers (LOS ranking of fast targets dropped); `verifyBeehives` reads blocks under `LOCK` from a worker (pre-existing deadlock risk if a server-thread caller contends); a walk that hit a >750 ms server stall on its snap now replans once, then reports blocked.

**Pending sandbox check:** see the 1.1.286-1.1.289 lists below, plus:
- 1.1.290: woodcut cleanup at the edge of loaded terrain finishes (no deadline burn) and says it couldn't check everything; logs next to a broken bee nest get cut again; a full-pack bot gets aid while chaining idle hobbies but not during a commanded job; a teleport-fallback walk and segment snaps show no rubber-band.

## Session Handoff 2026-10-20 (1.1.289) — superseded

**State:** main = origin/main @ 1.1.289. Suite 3440 green. Deployed to Nirn. 1.1.289 = backlog 146 (dead escapeStairs chain deleted), 138, 144, 179 (Codex; Fable review; fix wave by a Claude agent while Codex was at its usage limit). **Still open from this batch, give to Codex next:** 168 (woodcut cleanup region scan off-thread: Codex drafted `/private/tmp/frens-cleanup-scan.patch` outside the repo, not applied) and 184 (mutual aid drops recipient items while its task runs: guard on TaskService active task, server thread). Deferred: `player.teleport` before the rescue in `MovementService.moveTo` (~:843) and the `refreshPositionAndAngles`/`setVelocity` in `snapTo` still run on the worker; `runOnServerThread` (`moveForward` etc.) still uses the no-cancel path; a death during the ≤30 s resume wait drops the job without a "continue?" prompt; a 750 ms tool-swap timeout mines with the previous tool.

**Pending sandbox check:** see the 1.1.286-1.1.288 lists below, plus:
- 1.1.289: a buried or wedged bot during farm/woodcut/return is dug out with no position rubber-band; `/bot resume` (or "yes") while a bot is climbing to the surface says "<bot> will continue once it's back on the surface." and resumes after; mining tool swaps still happen while walking.

## Session Handoff 2026-10-20 (1.1.288) — superseded

**State:** main = origin/main @ 1.1.288. Suite 3428 green. Deployed to Nirn. 1.1.288 = backlog 147, 148, 145, 217, 272, 187 partial (Codex; fix wave from a Fable review, scoped re-review). 210 skipped as written: the preference receivers already run on the server thread and the payloads are self-only booleans, so nothing cheap to reject earlier. Field checklist phases 6bt-6by (1.1.282-1.1.287) added in `09c689a3`; older phases grep `leaf-cleared`, renamed `leaf-action:` in 1.1.286. Deferred: `/bot place` keeps its own instant snow clear (the shared timed path would fail in-tick on the server thread); a timed clutter clear cancelled with a sword held leaves the harmless fallback in hand; one-shot server-thread placements onto snow/carpet fail once and retry next tick.

**Pending sandbox check:** see the 1.1.286 and 1.1.287 lists below, plus:
- 1.1.288: fortify/shelter inside a mapped village places onto short grass, snow and carpet (timed mining for snow/carpet, instant for grass); an automatic return refused for combat repeats its line only after 3 min or a reason change; a bot under an oak canopy counts as at surface but not in a cave under one leaf; `/bot place` onto snow still works in one command.

## Session Handoff 2026-10-20 (1.1.287) — superseded

**State:** main = origin/main @ 1.1.287. Suite 3419 green. Deployed to Nirn. 1.1.287 = backlog 199, 200, 201, 219, 222 and finding 59 (Codex, fix wave and follow-up from two Fable reviews). Rulings: an unowned wall is claimable by op/host only (saved walls record no builder); a double chest whose other half is unloaded is refused at take time (`DENY_UNLOADED`, lock unknown); base auto-zones stay admin-only to edit. Finding 59's cause is not proven (bookkeeping defects fixed); it needs a two-bot sandbox rerun. Deferred: an owner who unclaims a wall on a dedicated server with no ops can't reclaim it; walk-abort guard also skips the un-sneak/door-open steps of an aborted walk (next walk resets them).

**Pending sandbox check:** see the 1.1.286 handoff list below, plus:
- 1.1.287: two bots clean the same felled tree (finding 59): no scaffold dirt left, natural soil untouched; a non-owner on a shared server can't rename/delete a zone, map a village or claim an ownerless wall; `/bot stop` then a new task: the old tool search doesn't walk the bot to its chest; double chest with one half unloaded is refused, not half-read.

## Session Handoff 2026-10-20 (1.1.286) — superseded

**State:** main = origin/main @ 1.1.286. Suite 3399 green. Deployed to Nirn. 1.1.286 = findings 56, 60, backlog 216, 135, 167 (Codex `77df45d2`, fix wave `4f20d03f`); 146 restated (escapeStairs unreachable: `BotStuckService.updateStuckTracker` has no callers, delete or wire it). 1.1.285 = backlog 178, 220, 223, 224 (Codex, `9b23cffb`). 1.1.284 = backlog 196, 253 (Codex, `c982f8b6`; 216, 210 skipped). 1.1.283 = backlog 409, 186, 231 (Codex-implemented, `003fd626`); 135 skipped (not small). Not sandbox-checked.
**Pending sandbox check (Bradley 2026-10-20: queue it, run when usage allows, not yet):** one combined run for the unchecked builds:
- 1.1.282: woodcut cleanup with every hotbar slot a tool/weapon skips the leaf clear (no swing); full-pack strict stone craft logs no "I need 3 cobbled deepslate" line (`fix281.py craft full`); store chest placement miss logs at INFO.
- 1.1.283: a fortify run that finds a cavity speaks the callout aloud; a flower gift skips a recipient with a full inventory.
- 1.1.284: sunset with the owner more than 48 blocks away in the same dimension: the bot comes to the owner; voice mute toggles still apply.
- 1.1.285: a starving strip-mine bot far from its owner: the owner sees the pause line; an "Allow once" supply grant is used with the owner away; revoking Collect permission mid-trip gives the "no longer have permission" line; a chest placed by hand on a destroyed record stays unlisted.
- 1.1.286: fix277/280 `canopy` east route (finding 56): the bot mines the step-up leaf and gets past; fix278 `aid bowarmor` with Bob in line: bow-only Jake sidesteps and shoots, sword+bow Jake melees up close; mounted bot under leaves mines them at normal speed and skips persistent leaves; double-chest supply ask counts both halves once.
**Next:** findings 59 (two scaffold dirt blocks left after two bots cleaned the same log), 61 done in 1.1.282; then the remaining triage CODE rows (`.superpowers/backlog-triage/split182.txt` in the base checkout).
**Public release:** 1.1.286 is the candidate. Nothing goes to Modrinth or Discord without Bradley's OK on the exact text.
**Needs Bradley:** the three 1.1.272 questions still stand.
**Field checklist drain:** step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-20 (1.1.282) — superseded

**State:** main = origin/main @ 1.1.282. Suite 3380 green. Deployed to Nirn. 1.1.282 is four small fixes (backlog 169, 218, 227; finding 61), not sandbox-checked; 1.1.281 sandbox (6bs) passed.
**Next:** finding 56 (one-block step up into oak leaves at feet and head: `leaf-cleared` logs but no leaf is mined), 59, 60; then the remaining triage CODE rows (`.superpowers/backlog-triage/split182.txt` in the base checkout; 169, 218, 227 done).
**Public release:** 1.1.282 is the candidate. Nothing goes to Modrinth or Discord without Bradley's OK on the exact text.
**Needs Bradley:** the three 1.1.272 questions still stand (Spells key migration, chest axe before crafting, shared-server chest scan).
**Deferred (reasons in the changelogs):** see the 1.1.277-1.1.281 entries.
**Field checklist drain:** step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-20 (1.1.281) — superseded

**State:** main = origin/main @ 1.1.281. Suite 3380 green. Deployed to Nirn. Sandbox-checked 2026-10-20 (Phase 6bs, `tools/sandbox/fix281.py`): PASS idle stone upgrade (full pack: nothing dropped or crafted, one "My pack's full, so I can't craft stone pickaxe right now."; rotten flesh dropped, pickaxe in stone, cobblestone kept, one line for the sword; with 4 free slots 4x `using stone` and no chat, diamonds and iron kept; 4 cobblestone: pickaxe and shovel in stone only; 0 `using diamond|iron`, 0 cobblestone drops, 0 "Missing materials" all session), explicit `/bot craft` still answers in chat, follow no regression. New finding 61 (log only: after a full-pack refusal the quiet line names cobbled deepslate); 1 field item in Phase 6bs. Built autonomously overnight.
**1.1.281** (changelog entry has rulings and costs): findings 57/58: idle stone upgrade strict stone only (ruling), batch reserve keeps its cobblestone, main-slot room checks, idle crafts quiet except the throttled full-pack line (ruling). Fable review clean; small follow-up `095b3028` read by the controller.
**Next:** finding 56 (one-block step up into oak leaves at feet and head: `leaf-cleared` logs but no leaf is mined), 59, 60 from the 1.1.280 check (6br: all other items pass, findings 47-55 closed); then the remaining triage CODE rows (`.superpowers/backlog-triage/split182.txt` in the base checkout).
**Public release:** 1.1.281 is the candidate once its sandbox checks pass. Nothing goes to Modrinth or Discord without Bradley's OK on the exact text.
**Needs Bradley:** the three 1.1.272 questions still stand (Spells key migration, chest axe before crafting, shared-server chest scan).
**Deferred (reasons in the changelogs):** see the 1.1.277-1.1.281 entries.
**Field checklist drain:** step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-20 (1.1.280) — superseded

**State:** main = origin/main @ 1.1.280. Suite 3370 green. Deployed to Nirn. Sandbox-checked 2026-10-20 (Phase 6br, `tools/sandbox/fix280.py`): PASS canopy follow (staged step down past the end leaf below the row in ~8.3 s, was 55-60 s; wall-to-wall row: a leaf cleared and below in ~7.2 s, 5 `head-not-passable` a run), /tp while following (leaf mined 6 s after, 0 "Mining aborted"), flooded pocket (`climbed out=true`, on the ledge at once, surface in 58-64 s with a pickaxe), two woodcutters (0 mines at any base, all 4 bases dirt, no pillar mined under the other bot), bow defense and friendly fire (bow-only Jake responds; 0 `ff_received` in four runs), admin `/bot open` from 16 blocks (screen stays open), follow/doorway/woodcut no regression. Finding 56 (east route, a step up into oak leaves) unchanged. New findings 59 (two scaffold dirt blocks left on a base after both bots cleaned the same log) and 60 (a bow defender with its ally in the line walks up to melee and never shoots); 2 field items in Phase 6br. Built autonomously overnight (a usage limit paused the session ~03:00-04:00).
**1.1.280** (changelog entry has rulings and costs): follow under oak canopies rescoped and fixed (in-column waypoint reach, 0.3 waypoint stop, last live waypoint handed to direct pursuit, corners kept, no sprint into turns, leaf check both sides, leaf gate by anchor, /tp latch dropped with no task); flooded-pocket ledge climb (hold jump); woodcut cleanup never digs any tree's soil (10 min ledger, ruling) or another bot's pillar, per-bot scaffold memory; bow defenders; ally bots block a shot; admin remote open. Fable review (two reviewers), fix wave `a306fcab`, re-review found 2 more (`54238e1b`, controller read the diff).
**Next:** run `tools/sandbox/fix277.py canopy` and `flood` against 1.1.280 first (both failed/partial on 1.1.277); then the remaining triage CODE rows (`.superpowers/backlog-triage/split182.txt` in the base checkout).
**Public release:** 1.1.280 is the candidate once its sandbox checks pass. Nothing goes to Modrinth or Discord without Bradley's OK on the exact text.
**Needs Bradley:** the three 1.1.272 questions still stand (Spells key migration, chest axe before crafting, shared-server chest scan).
**Deferred (reasons in the changelogs):** see the 1.1.277-1.1.280 entries.
**Field checklist drain:** step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-20 (1.1.279) — superseded

**State:** main = origin/main @ 1.1.279. Suite 3274 green. Deployed to Nirn. Sandbox-checked 2026-10-20 (Phase 6bq, `tools/sandbox/fix279.py`): PASS wall steps (a submerged bot's rescue steps clamped flush, 28 `moveRelative-same-cell ... clamped` lines, 0 IN_WALL; drop sweep at a wall and door/stair wedges fine; combat kiting not exercised), make-room (nothing valuable dropped, owner line twice 40 s apart; idle crafts dropped only rotten flesh or cobblestone, no chest walk or placement), fresh stuck counters (backup 3 s after both returns), `/bot cook` from the bag, bundled food, follow/woodcut/doorway no regression; Pocket timeout NOT RUN (unit-tested). New findings 57 (a full pack's stone upgrade drops the cobblestone the rest of the pass needs, then 18 "Missing materials" chat lines) and 58 (the "stone" upgrade crafts diamond and iron tools from the pack's valuables); 3 field items in Phase 6bq. Built autonomously overnight.
**1.1.279** (changelog entry has rulings and costs): same-cell `moveRelative` steps collision-checked (`SubCellSnapPolicy`, backlog 143); make-room never drops food/tools/reserved/valuables for aid (cheap-only, ruling) and server-thread crafts never walk to or place a chest (181); fresh stuck counters per return (140) and jump-sprint judged after landing on a sideways move (139); no cooking sleep on the server thread (180); Pocket installer timeouts (235); bundled food counted (177). Fable review (two reviewers), fix wave `5eb24b38`, clean re-review.
**Next build (scoped, ready to implement):** findings 47/50/51 follow canopy (waypoint reached only in its column + 0.3 stop distance, compress keeps direction/Y changes, leaf check both axes for a diagonal goal, leaf gate by anchor not same-block, /tp latch cleared when no task); 49 ledge climb (hold jump + forward while swimming, boost on horizontal collision); 52 cleanup never mines any tree's soil (shared recent-tree-soil ledger) and scaffold memory per bot; plus 1.1.278 sandbox findings 53-56.
**Public release:** 1.1.279 is the candidate once its sandbox checks pass. Nothing goes to Modrinth or Discord without Bradley's OK on the exact text.
**Needs Bradley:** the three 1.1.272 questions still stand (Spells key migration, chest axe before crafting, shared-server chest scan).
**Deferred (reasons in the changelogs):** see the 1.1.279, 1.1.278 and 1.1.277 entries.
**Field checklist drain:** step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-20 (1.1.278) — superseded

**State:** main = origin/main @ 1.1.278. Suite 3221 green. Deployed to Nirn. Sandbox-checked 2026-10-20 (Phase 6bp, `tools/sandbox/fix278.py`): all five fixes PASS (aid without a server freeze, golden-apple yes/no, go_to dimension, doorway, Collect messages), follow and woodcut no regression; new findings 53-56 (bow-only bot never defends; a bow defender shot its ally; admin `/bot open` beyond 8 blocks shows nothing; follow stall under leaves on an east route); 2 field items in Phase 6bp. Built autonomously overnight (Bradley 2026-10-19: "do a few more iterations autonomously").
**1.1.278** (changelog entry has rulings and costs): defensive mutual aid tick-driven (no 1.6-2.4 s server freeze), hands off only to combat that will fight; golden-apple yes/no handled (20 s prompt window, ruling); `/bot go_to` in the bot's own dimension; following bots line up with an open doorway, no jump under a lintel, no door shut on a bot; Collect/Go owner messages (refused, replaced, cancelled, traveling, dimension fallback). Fable review (two reviewers), fix wave `d559547c`, re-review found 2 more (`0603252b`, controller read the diff).
**Next build candidates (triage CODE rows):** 143 moveRelative snaps (bots in walls); 185 full-inventory craft drops the largest stack; 139 jump-sprint escape counts a Y bump as success; 140 stuck-return counters carry over; 235 Pocket TTS installer hang; 180 cook sleeps on the server thread; 181 server-thread craft walks to a chest; 177 "no food" ignores bundles; 206 End exit companion state; the 1.1.278 deferrals.
**Public release:** 1.1.278 is the candidate once its sandbox checks pass. Nothing goes to Modrinth or Discord without Bradley's OK on the exact text.
**Needs Bradley:** the three 1.1.272 questions still stand (Spells key migration, chest axe before crafting, shared-server chest scan).
**Deferred (reasons in the changelogs):** see the 1.1.278 and 1.1.277 entries.
**Field checklist drain:** step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-19 (1.1.277) — superseded

**State:** main = origin/main @ 1.1.277. Suite 3159 green. Deployed to Nirn. Sandbox-checked 2026-10-20 (Phase 6bo, `tools/sandbox/fix277.py`): PASS woodcut detect 12-24 ms, 48 backoff (30 s from the 2nd failure), no stage directions in chat (the model wrote none), follow/mining regressions; PARTIAL 48 ledge carved but the bot does not climb out (finding 49); FAIL 47 on the staged canopy (diagonal stall finding 50, wall-to-wall row still loops; original spot passes); new findings 51 (teleport latch aborts follow leaf clears), 52 (cleanup sweep mines another bot's tree soil). Backlog triaged this session: 190 open RALPH items (`.superpowers/backlog-triage/split182.txt` in the base checkout: 113 code / 51 play-test / 17 Bradley); 9 Codex "done" calls rejected stay open (156, 255, 294, 459, 476, 500, 507, 509, 515).
**1.1.277** (changelog entry has rulings and costs): finding 47 follow checks headroom on a step down and every follow/come command clears the abort latch (leaf clearing had aborted on its first tick after every follow start); finding 48 a floating bot in a flooded pocket carves a two-block ledge (ruling) and failed surface recovery backs off, logs throttled; soul stage directions stripped (word list); woodcut per-log checks through the chunk reader. Fable review (two reviewers), one fix wave (`2f736b46`), clean re-review.
**Next build candidates (top CODE rows from the triage):** 132 go_to from Nether/End plans on the Overworld; 182 food confirmation reply unhandled; 226 two Collects in cooldown; 225 cross-dimension Collect dropped silently; 183 defensive aid 1.6-2.4 s freeze; 358 door stall.
**Public release:** 1.1.277 is the candidate once its sandbox checks pass. Nothing goes to Modrinth or Discord without Bradley's OK on the exact text.
**Needs Bradley:** the three 1.1.272 questions still stand (Spells key migration, chest axe before crafting, shared-server chest scan).
**Deferred (reasons in the changelogs):** on-ground flooded pocket; findNearestTree and ring/nearby tree finders still read per block; remembered cleanup log in an unloaded chunk fails its mine; repeated asterisk directions; stair-escape and woodcut ascent pause on flood; plus the 1.1.276 list below minus the woodcut per-log item.
**Field checklist drain:** step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-19 (1.1.276) — superseded

**State:** main = origin/main @ 1.1.276. Suite 3111 green. Deployed to Nirn. Sandbox-checked 2026-10-19 (Phase 6bn, `tools/sandbox/fix276.py`): 7 pass (fishing search 13-31 ms, hangout start under 1 s, craft table scan 1.47 ms, farm water search 4-6 ms, miner threats, copied chest records retired, follow), woodcut partial (detect 374-394 ms with trees; per-log reads deferred); new findings 47 (follow stalls under an oak canopy, pre-existing) and 48 (underwater bot surface-recovery loop). Field checklist: 125 open.
**1.1.276** (changelog entry has rulings and costs): `ChunkBlockReader` (one chunk fetch per scan instead of a server round trip per block) used by the path snapshot (hangout start, fishing), fishing evaluation, woodcut detection/beehives/relocation sampling, crafting table and chest searches (section skip), farm/harvest/planting/cleanup-chest/hangout-campfire scans; finding 44 a miner fights what it would fight and works past the rest (ruling), the AutoFace busy-pause no longer cancels a skill for a mob it won't fight (closes 46); finding 45 copied chest records retired. Fable review (three reviewers), one fix wave (`11e8ed2c`), clean re-review.
**Public release:** 1.1.276 is the candidate once its sandbox checks pass. Nothing goes to Modrinth or Discord without Bradley's OK on the exact text.
**Needs Bradley:** the three 1.1.272 questions still stand (Spells key migration, chest axe before crafting, shared-server chest scan).
**Deferred (reasons in the changelogs):** name-tagged mob attacking a busy bot is neither fought nor paused for; per-log woodcut checks and dynamic-shape collision reads still hop; `crossesLockedGate` ray samples hop with locks present; chest record revival is partial; spare-table placement miss; craft call that hits a failed reach still makes a spare; A* node cap; axe-bearer's failing woodcut ignores the per-hobby backoff; merged scene lines truncated at 300 chars; Wither boss reads "withering"; 2×2 trunk on a slope; off-thread `consumeFood` slot swaps; chest deposit vs verify disagree on barrels/copper.
**Field checklist drain:** step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-19 (1.1.275) — superseded

**State:** main = origin/main @ 1.1.275. Suite 3089 green. Deployed to Nirn. **1.1.275 sandbox-checked** (Phase 6bm, `tools/sandbox/fix275.py`, replays `frens_1_1_275_6bm_fix_checks`, `_chest_owner`, `_chest_foreign`, `_threat_skeleton`): PASS threat line (repro, 0 lines; the pause path not reached, finding 46), tree soil, one-tree woodcut, chest adoption, desperate eating; PARTIAL fishing (1781 ms with the snapshot skipped, 8392 ms when built); new findings 43-46 in `FIELD_0_6AI_SANDBOX_2026-10-18.md`. Open field items: 125. 1.1.273 (Phase 6bk) and 1.1.274 (Phase 6bl) were sandbox-checked this session.
**1.1.275** (changelog entry has rulings and costs): findings 37-42 from the 1.1.273/274 sandbox checks: 37 threat line once per threat per bot, 38 woodcut leaves the felled tree's soil, 39 fishing search (root cause: off-thread getBlockState round-trips to the server thread; snapshot lazy, chest scan bounded and chunk-local), 40 chest list adopted only where the chests are (also over a 1.1.274 foreign claim or an empty own store), 41 woodcut that ran out of trees reports success, 42 desperate eat every 2 s and no starvation pause mid-eat. Fable review (two reviewers), one fix wave (`e7d5dd55`), clean re-review.
**Lead worth a scope:** every skill-worker scan pays a server-thread round trip per `world.getBlockState` (1.21.11 bytecode). Grep large worker-side scans (chest scans, tree/ore searches, snapshot captures) and read through `world.getChunk(cx,cz,FULL,false)` once per chunk like `FishingSkill.collectChestAnchors` (1.1.275) and `BaritoneStylePathFinder`.
**Public release:** 1.1.275 is the candidate once its sandbox checks pass. Nothing goes to Modrinth or Discord without Bradley's OK on the exact text.
**Needs Bradley:** the three 1.1.272 questions still stand (Spells key migration, chest axe before crafting, shared-server chest scan).
**Deferred (reasons in the changelogs):** spare-table placement miss; craft call that hits a failed reach still makes a spare; fishing snapshot still ~7 s when a stand needs A*; A* node cap; axe-bearer's failing woodcut ignores the per-hobby backoff; merged scene lines truncated at 300 chars; Wither boss reads "withering"; 2×2 trunk on a slope soil outside the ring; off-thread `consumeFood` slot swaps; chest deposit vs verify disagree on barrels/copper chests; the previous claimant keeps a stale chest-list copy; farm no-hoe half-planted path unreached in the sandbox (field item, Phase 6bl).
**Field checklist drain:** step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-19 (1.1.274) — superseded

**State:** main = origin/main @ 1.1.274. Suite 3050 green. Deployed to Nirn. Sandbox-checked 2026-10-19 (Phase 6bl, `tools/sandbox/fix274.py`, replays `frens_1_1_274_6bl_fix_checks*.zip`): PASS woodcut keeps its axe, craft after a failed reach (13), unknown item once (7), idle leather-armor fallback, first sightings after a relaunch (20), damage in words (21), chest registry for the owning save (26); FAIL fishing search still 11.6 s (14, new finding 39); PARTIAL farm no hoe (half-planted path is a field item); finding 37 still repeats (16 lines/min). New findings 39-42 in `docs/testing/FIELD_0_6AI_SANDBOX_2026-10-18.md` (40: the first save named "New World" to load adopts the chests even when they are not in it). Field checklist: 125 open.
**1.1.274** (changelog entry has rulings and costs): woodcut keeps its axe (the AutoFaceEntity cliff branch ran the combat loadout with no hostile: the 1.1.273 re-poll axe source), slower re-poll swap extends the timeout, 13 craft after a failed reach, 7 unknown-craft line throttled, 14 fishing search capped at 1.5 s, farm no-hoe with standing crops, idle leather/cobble fallbacks run (wooden fallback reports work only when it did some), 3 same speaker twice, 20 first sightings survive a relaunch (minds preload), 21 damage and tasks in words, 26 chest registry per save fingerprint, resume arm id. Fable review (two reviewers), one fix wave (`cca278d8`), controller read the fix diff.
**Public release:** 1.1.274 is the candidate once its sandbox checks pass. Nothing goes to Modrinth or Discord without Bradley's OK on the exact text.
**Needs Bradley:** the three 1.1.272 questions still stand (Spells key migration, chest axe before crafting, shared-server chest scan).
**Deferred (reasons in the changelog):** craft call that hits a failed reach still makes a spare table; spare table placement miss; fishing A* node cap; axe-bearer's failing woodcut ignores the per-hobby backoff; merged scene lines truncated at 300 chars; BotHomeService identity-before-adopt ordering; Wither boss reads "withering"; banter director never scans around the player; group prompt hostile line count-only.
**Findings 1-36:** all fixed except deferrals above; new findings from the 1.1.273 sandbox check (if any) are numbered 37+ in the sweep doc.
**Field checklist drain:** step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-19 (1.1.273) — superseded

**State:** main = origin/main @ 1.1.273. Suite 3010 green. Deployed to Nirn only (the sandbox still has 1.1.272). Not checked in game or the sandbox yet. Phase 6bk: 4 field items; checklist 127 open.
**1.1.273** (changelog entry has rulings and costs): second fix build from the 0-6ai sweep: 17 ambient chatter waits for the speech floor and follows the Scripted rate ("Time to punch some trees" only without an axe), 18 local director vetoes when any nearby actor is in danger, 23 a threat only resumes a task it paused within 10 min and finished skills clear their record (lost in `1296ae05`), environmental damage is not combat, 27 one stop line, 34 farm without a hoe fails, 24/28/29/36 log and chat floods, 10 bridge teardown tool, mining re-poll keeps progress on an equal-speed swap, woodcut cleanup leaves natural stone, 9 idle stone upgrade, 11 pickup count, 4 bed craft checks ingredients first. Fable review (two reviewers), one fix wave (`4ae67198`), clean re-review. The session hit a usage limit mid-build and was resumed from the CLI.
**Public release:** 1.1.272 was the candidate; 1.1.273 supersedes it once its sandbox checks pass. Nothing goes to Modrinth or Discord without Bradley's OK on the exact text.
**Needs Bradley:** the three 1.1.272 questions still stand (Spells key migration, chest axe before crafting, shared-server chest scan).
**Deferred (reasons in the changelog):** farm hoe lost on a half-planted farm still reports success (needs an untilled-plot count); leather-armor and cobblestone-tools idle fallbacks never run (fix the wooden fallback returning true when it did nothing); what re-equips the axe between mining re-polls (suspect `CombatInventoryManager`); a slower re-poll swap can still time out; resume arm/undo token is millisecond time; banter director never scans around the player; group prompt hostile line is count-only.
**Next fix build (from findings 1-36, not in 1.1.272/273):** 13 craft after a failed table reach, 3 same speaker twice, 7/14/20/21/26 minors, plus the deferrals above.
**Field checklist drain:** step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-19 (1.1.272) — superseded

**State:** main = origin/main @ 1.1.272. Suite 2947 green. Deployed to Nirn and the mcp-sandbox. **Sandbox checks all pass** for 15, 5, 8, 6, 35, 31, 32, 30 (key) and 1 (replay `frens_1_1_272_6bj_fix_checks.zip`, changelog entry has the log lines), so no high finding is open: by Bradley's rule 1.1.272 is the next public release, pending his OK on the notes. Phase 6bj: 3 field items. Checklist 123 open.
**1.1.272** (changelog entry has rulings and costs): all four high findings of the 0-6ai sweep (15 settings save past 32 KB, 31 lodestone travel to an unloaded chunk, 32 travel-wait probe overflow, 35 wool spin), plus 5 (wedged guard escape, stale abort latch), 30 (Spells key, spellbook and resurrection with Questing off, default key `[`), 8 (woodcut asks unregistered nearby chests for an axe before punching), 25, 6, 1, 33, souls 2/16a/19/22, and a `Long.MIN_VALUE` sweep (`TickIntervals`; the combat callout read a never-hit bot as just hit). Fable review (two reviewers), one fix wave (`0c62d973`).
**Public release:** by Bradley's rule (2026-10-18) 1.1.272 is the candidate once the high findings pass in the sandbox. Nothing goes to Modrinth or Discord without his OK on the exact text; the 1.1.268 notes draft (`.superpowers/sdd/modrinth/`, base checkout) needs rewriting for 1.1.272.
**Needs Bradley:**
- Existing installs keep the Spells key unbound (options.txt saved `key.keyboard.unknown`), so Nirn needs Options > Controls > Frens > Spells = `[` (or Reset Keys). Ruling wanted: a one-time migration?
- Woodcut crafts an axe from planks before it asks a chest (order kept: craft, chest, bare hands). Should a chest axe come before crafting?
- On a shared server the woodcut chest scan offers another player's unlocked chest within 12 blocks to the owner through the normal prompt.
**Deferred (with reasons in the changelog):** #16 retry on a sentinel-only reply; enchant/anvil proxy has no owner gate; a picker for several dead bots; death records never cleared (an old shelved bot qualifies for the ritual); party-scene generation is not cancelled when one of its bots dies; two log-only MIN_VALUE sites (`Frens.java` in-wall warning, `BotEventHandler` follow-backoff log); novelty pushes an exact repeat into the ring; ToolSelector cooldown map never pruned; per-bot `lastRespawnHandledTick`.
**Next fix build (from findings 1-36, not in 1.1.272):** 17 ambient chatter ignores the speech floor and Scripted rate (medium, behind four FAILs), 18 local danger veto misses a zombie, 34 farm reports success after tilling nothing, 23 a threat resumes a long-finished woodcut, 13 craft after a failed table reach, 4 bed craft builds a table first, 7/9/10/11/14/20/21/24/26-29/36 minors, 3 same speaker twice.
**Field checklist drain:** steps 1-2 done; step 3 is one guided session (`GUIDED_SESSION_BRADLEY.md`).

## Session Handoff 2026-10-18 (1.1.271) — superseded

**State:** main = origin/main @ 1.1.271. Suite 2853 green. Deployed to Nirn; the mcp-sandbox still has 1.1.270 (a field sweep held the sandbox lock at release time), deploy there when the lock is free.
**1.1.271:** the bot operator-permission lookup now reaches `leveled-mapped` OWNERS. Since 1.1.232 a static-init order bug (the leveled field list was still null) made every launch fall back to `PermissionPredicate.ALL`. The fallback was harmless in vanilla and Carpet; the changelog entry has the javap evidence.
**Field checklist drain:** step 2 done. The sandbox sweep (`docs/testing/FIELD_0_6AI_SANDBOX_2026-10-18.md`) ran four passes: passes 1-2 (1.1.270, 1.1.271) gave 78 items a verdict; pass 3 (1.1.271, sessions S5-S11, Flashback replays A-H) gave 126 more (81 pass, ticked; 11 fail; 23 partial; 9 not reached; 1 criterion out of date and 1 needs Bradley, both in `GUIDED_SESSION_BRADLEY.md`; the Spells key was ruled 2026-10-19: not Questing-only, finding 30), covering the old 99-item list, fortify, 6ak-6bi and 7-10. Pass 4 (2026-10-19, S12-S13, replays I-K) ran the 17 leftover sandbox items plus Phase 9 present truth: 18 verdicts (7 pass, ticked; 2 fail; 5 partial; 4 not reached, three blocked by finding 32); 2 more items to the guided doc (6ai non-admin half, a decision on travel-wait hobbies far from home). Step 2 is done; what is left of the sweep lists needs Bradley or a fix build. Top of pass 4: **lodestone fast-travel refuses whenever the lodestone chunk is unloaded** (31, high); **the travel-wait offload can never start** (`lastChestProbeTick = Long.MIN_VALUE` overflow, 32, high); **the wool skill spins and floods the log** (1.6M lines in 13 min, 35, high); a hobby keeps digging at the old spot after the travel-wait departs (33); farm reports success after tilling nothing (34). Findings 15-36 join 1-14 as the next fix-build backlog. Top of pass 3: **a settings change disconnects the player once the config passes 32 KB** (`frens:save_config` String too big, finding 15, high); ambient chatter ignores the speech floor and the Scripted rate (17); the local danger veto misses a zombie beside the bot (18); novelty or a tail-only `##FRENS` reply can leave a player's group question unanswered (16, 19); the DM prompt carries the player's line twice so Jake says "you said that already" (22). Then step 3, one guided session (`GUIDED_SESSION_BRADLEY.md`, 61 items). **Checklist 120 open, 242 done.**
**Next:** the 1.1.270 Next list still stands.

## Session Handoff 2026-10-18 (1.1.270) — superseded

**State:** main = origin/main @ 1.1.270. Suite 2853 green. Deployed to Nirn and the mcp-sandbox instance.
**1.1.270** (changelog entry has rulings and costs): emergency rescue stops counting chest food behind a refusal or owner-away hold, a denied chest (foreign, locked, territory) or an owner out of prompt range (`SupplyWithdrawals.grantablePreview`), and memoizes its chest scan 20 ticks; honey_collect reaches a hive from about 4 blocks (feet-based reach, nudge onto the stand) and deposits into an existing chest within 32 blocks only when still full after packing. Fable review: one Important (chest access), fixed.
**Sandbox:** Phase 6bi passed on the 1.1.270 jar (`tools/sandbox/rescuetest.py`, save "Wall Test"), plus owner-away and honey from 4 and 8 blocks. No new field items. **Checklist 267 open.**
**Field checklist drain (Bradley's plan 2026-10-18, carry this line forward until step 3 is done):** step 1 done in 1.1.270 (`2fb21417`, 10 closed, 27 more already closed by the 6aj-6bg sweep). The 9 NEEDS-JUDGEMENT items are answered (Bradley took Claude's picks 2026-10-18): 8 reworded in place to the part still worth checking, 6ar was already ticked. The operator-permission finding was fixed in 1.1.271. Next: step 2, sandbox sweeps of the older phases (0 to 6ai) the way 6aj-6bg was swept, then step 3, one guided session with Bradley.
**Next:**
- **Modrinth:** the 1.1.268 notes draft (`.superpowers/sdd/modrinth/MODRINTH_NOTES_1.1.268_DRAFT.md`, base checkout, untracked, over length) still waits for Bradley's OK. The Discord post of 2026-10-18 promised the Spawn-tree scaffold "in the next Frens release". If he wants 1.1.270 shipped, add the 1.1.269 and 1.1.270 fixes and trim to about 700 words.
- Deferred, new: an unanswered supply prompt expires in 30 s and counts as an answer (60 s hold), so the rescue and the prompt take turns a minute at a time for an owner who ignores chat (ruling: should an expired prompt hold the rescue's chest-food view?); the off-thread rescue hop (ensureAtSurface callers run the rescue on workers); the preview does not read the per-chest reject cooldown; an ownership filter for deposits across all skills; the rest of `assessDireStraits` still runs every tick for a starving bot that is not dire; the nudge may stop about 0.7 blocks off centre at an exact-edge honey stand.
- Still deferred: a stopped Spawn-base fell leaves the upper trunk and canopy standing; ascent bridge probe; raised-base neighbour trunks lend logs; abort-safe scaffold cleanup outside bases; finding 2 (ranged pause).
- PlayerMemory vs relations: Bradley's decision is due (see the 1.1.268 handoff). The 1.1.267 Next list still stands.

## Session Handoff 2026-10-18 (1.1.269) — superseded

**State:** main = origin/main @ 1.1.269. Suite 2843 green. Deployed to Nirn and the mcp-sandbox instance.
**1.1.269** (changelog entry has the details): honey_collect packs, unpacks and equips inside `SupplyServerHop` (was the worker), "Got N logs" counts only the felled tree's logs (`WoodcutTreeOwnershipPolicy`, as the leftover list), emergency rescue counts only chest food the supply rules would grant (`BotMutualAidService.hasGrantableChestFood`, reserve 8, allowlist, reach, chests only). Fable review merge-ready, no fix wave.
**Field Phase 6bh done:** the three open items passed in the sandbox on 1.1.268 (save "Sweep Spawn"): stop mid-fell `removed 3/3 own scaffold block(s) (abort=true)`, leftover after a quit cleared, axeless bot cut 3 reachable logs and placed nothing. Replays `frens_1_1_268_6bh_*.zip` in the sandbox. **Phase 6bi** (one item): the starving trapped bot beside 5 chest bread gets rescued. Checklist now 278 open items.
**Next:**
- **Modrinth:** the 1.1.268 notes draft (`.superpowers/sdd/modrinth/MODRINTH_NOTES_1.1.268_DRAFT.md`, untracked) still waits for Bradley's OK. Untouched this session. If he wants 1.1.269 shipped instead, the draft needs these three fixes added.
- Deferred, new: honey_collect started about 4 blocks from a hive skips it as `UNREACHABLE ... arrived-out-of-reach` (the DIRECT walk reports arrived within tolerance without moving, `HoneyCollectSkill.moveIntoReach`), from 8 blocks it works; `depositHoneyItems` passes the bot's own position as the chest, so the offload after packing is a silent `chest-missing` no-op (needs a nearest-chest lookup, behaviour change, field check); a stopped Spawn-base fell leaves the upper trunk and canopy standing (later detects reject it as a failed base); rescue check runs every tick for a starving, not-dire bot (a 20-tick memo would be cheap) and does not see refused foods, the chest hold or an absent owner; HoneyCollectSkill's private 500 ms `callOnServer` could move to `SupplyServerHop`; the rescue's other worker-thread mutations from auto-hunt.
- Still deferred from 1.1.268: ascent bridge probe bridges toward any log within 6, raised-base neighbour trunks lend logs, abort-safe scaffold cleanup outside bases, finding 2 (ranged pause).
- PlayerMemory vs relations: Bradley's decision is due (see the 1.1.268 handoff below). The 1.1.267 Next list still stands.

## Session Handoff 2026-10-18 (1.1.268) — superseded

**State:** main = origin/main @ 1.1.268. Suite 2842 green. Deployed to Nirn and the mcp-sandbox instance.
**Field sweep done:** phases 6aj-6bg in the sandbox on 1.1.267 (`docs/testing/FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`): 58 items got a verdict, 49 pass, 4 superseded, 2 partial (6ak-1 no PLACE move seen, 6ap-2 Bases screen and non-op half), 1 fail (6am-2 audit fixes, fixed in 1.1.268), 2 need Bradley (6ao-1 Nirn skins and mounts, 6av-2 remote server). **No high-severity failure**, so by Bradley's 2026-10-18 rule 1.1.268 is the next Modrinth release.
**1.1.268** (changelog entry has rulings and costs):
- Spawn-base trees: with "Cut trees in the Spawn base" On a woodcut bot places and removes its own pillar scaffold there (Bradley's ruling 2026-10-17, extends 1.1.238 `7f89c502`). Owned-scaffold registry keyed to the exact block, 24 h expiry, persisted per save, abort-safe teardown inside the base. Sandbox: a rooted Spawn oak cut whole, placed 4 removed 4.
- Forest woodcut counts only the felled tree's logs (no early relocation), tree scans build each tree once (detection 0.2-1.9 s, was 2-16 s), food keeps 8 in chests (Bradley 2026-10-18), honey_collect harvests at all, fishing cooks cod and salmon, retrieval reports vanilla pickups, one line for strict crafts and leash results.
- Test tooling: MCP fork key bindings, `look`, `scroll`, key-code typing (`71efa71`, `052c4ec`), `tools/sandbox/forest.py`, `leashtest.py`, `ft.py shot/clickr/scroll`, paused-screen warning, `sb_deploy`, per-instance deploy checks, log report thread fix.
**Next:**
- **Modrinth:** draft notes for 1.1.231-1.1.268 are in `.superpowers/sdd/modrinth/MODRINTH_NOTES_1.1.268_DRAFT.md` (untracked). Bradley OKs the exact text and uploads himself. Nothing was published.
- **Field Phase 6bh:** three items ticked from today's sandbox check (Spawn tree whole, forest, honey and fish), three open: the mid-fell `/bot stop` abort teardown in the Spawn base (not caught in the sandbox, a fell takes about two seconds), leftover own scaffold cleared after a quit, an axeless bot in the Spawn base. Checklist now 280 open items.
- Deferred from 1.1.268 review: honey's `equipItem`/`extractItemFromContainer`/`packHoneyIntoContainers` mutate the inventory on the worker; `countEnvelopeLogs` ("Got N logs" count) is unfiltered; ascent bridge probe still bridges toward any log within 6; raised-base neighbour trunks still lend logs (the soil-under-base rule); `BotEmergencyRescueService.hasNearbyStorageFood` ignores reserve and allowlist; abort-safe scaffold cleanup outside bases; finding 2 (ranged pause resumed while the shooter lived, unrepeated).
- PlayerMemory vs relations: nothing in the sweep showed them disagreeing (one note: a bot on the fallback Jake profile answered "It's Jake, not Wally."). Bradley's decision is now due.
- Sandbox notes for the next session: an open screen pauses the integrated server (`ft.py` warns now), `screen_buttons` cannot see Frens' custom-drawn screens (use `ft.py shot` + `clickr`), Flashback is installed (record from the pause menu: "Start Recording", "Finish Recording", then "Save Replay"), and `.superpowers/SANDBOX.lock` is the shared lock.
- The 1.1.267 Next list (no fallback re-search ruling, SearchBuffers overlay, M2 in game, "woodcut_cleanup interrupted." for the text sweep) still stands. The Spawn-tree pillar item shipped in 1.1.268.

## Session Handoff 2026-10-17 (1.1.267) — superseded

**State:** main = origin/main @ 1.1.267. Suite 2771 green. Deployed to Nirn and the mcp-sandbox instance.
**1.1.266 sandbox recheck done** (save "Fix 266", results in the 1.1.266 changelog entry): A, C/C2/C4, D, E, G pass. B was honest but still took about 2 minutes, so it went into 1.1.267. F never fired in the field. The save now has "Cut trees in the Spawn base" On.
**1.1.267:**
- F: the unified search refuses a move an earlier move of its own path undoes, and routes around it (`MUTATED_LAYER` key bit, truncation kept as backstop). `canReach`, `probePlan` and the PathCompare shadow now see the truncated plan.
- B2: an empty forest `woodcut_cleanup` takes 16 s in the sandbox (was 126 s), with a 30 s empty-phase cap.
- M1: 15 s no-planks table window. M2: a held unplaceable table gives one line. M3: mounted leaf clearing goes bare-handed instead of using shears or a weapon. M4: a bot named after a material can take a craft.
- Reviewed by Fable, one fix wave, re-review, two small follow-ups. Field items: Phase 6bg (checklist now 330 open items).
**Next:**
- **Ruling (Bradley, 2026-10-17): no fallback re-search** after a refusing unified NO_PATH for now. Revisit only if the field shows missed routes (seed 103 is a lawful mining path still missed through dedup staleness; a retry would double failing server-thread probes).
- Per-search overlay allocation into `SearchBuffers` (needs a `MoveSimulator.Overlay` base-terrain change).
- With the Spawn-tree toggle On, a tree that needs a pillar is cut one log at a time: `pillarUp` is refused in the Spawn base (1.1.238 ruling, `7f89c502`, logs and leaves only), so the bot leaves the rest and relocates. Seen on a floating test tree. Watch whether rooted Spawn trees do the same, it may need a ruling.
- M2 not set up in game (tests only). New player string "woodcut_cleanup interrupted." for the next text sweep.
- Older open work no longer chains through earlier handoffs. The 1.1.266 Next list shipped in 1.1.267 (F route fix, M1-M4).
  Everything still open from the 1.1.265 back to 2026-03 handoffs is a `- [ ]` line in "Recovered deferrals (2026-10-17)"
  below (incl. Slice D, the go_to overworld plan, the 1.1.264 stuck-escape scopes, and the "needs Bradley" decisions
  under Docs and process). Field items live in `docs/testing/FIELD_SESSION_1.1.202.md`. The old handoffs are in
  `docs/handoffs/ARCHIVE_2026.md` (history only, grep it).

## Recovered deferrals (2026-10-17)
Triaged 2026-10-23 (`.superpowers/deferral-triage/triage.md`, Bradley's 7 card rulings): 47 kept, 17 closed.

Deferrals that lived only in superseded handoffs (Ralph retirement audit, `.superpowers/ralph-retirement/audit.md` section 2
"ORPHAN" rows). Quoted from the source handoff, source in parentheses. `(uncertain)` = the audit could not confirm it is still
open. "Needs Bradley" items are decisions, not code. The source handoffs are in `docs/handoffs/ARCHIVE_2026.md`.

### Navigation, pathfinding and stuck escape
- [x] "go_to plans in `server.getOverworld()` whatever the bot's dimension." (still `server.getOverworld()` in `modCommandRegistry`; 1.1.265 handoff, 2026-10-17) — ✅ 1.1.278 8d6a7880 (sandbox 6bp PASS)
- [ ] **P2 (M)** "unified sunset probes returned `pathLen=0` or `7` with `next=none` inside 200 ms, so the return fell back to a direct follow plan to the far anchor. Worth a scope before Slice D." (1.1.265 handoff, 2026-10-17)
- [ ] **P3 (M)** "Slice D (remove the legacy boolean and bidir) once the default has held." (1.1.261 handoff; also 1.1.263 and 1.1.242 handoffs, 2026-10-15/17)
- [x] "after the first shared-settings save the file stores `"pathfinder": "unified"` explicitly rather than null, so a later default change would not move that install." (1.1.261 handoff, 2026-10-17) — ✅ 1.1.286 77df45d2
- [x] "diagonal wall nudge (eye-box geometry)" (1.1.264 handoff, 2026-10-17) — Source fixed in prior unreleased2026-10-22 phase, extreme-corner regression included. Live field checks remain.
- [ ] **P3 (M)** "scaffold Phase 3 pillaring up while on a pillar" (1.1.264 handoff, 2026-10-17)
- [x] "the two off-thread `rescueFromBurial` callers (ReturnBaseStuck async mine escape, MovementService nudge loop)." (1.1.264 handoff, 2026-10-17) — ✅ 1.1.289
- [x] "a backup jump-sprint logged `succeeded, now at 199, 110, 202` from inside a closed stone pocket. Worth a scope." (1.1.264 handoff, 2026-10-17) — ✅ 1.1.279 741355c8 + 5eb24b38 (sandbox 6bq PASS)
- [x] "`ReturnBaseStuckService` counters clear only on arrival at base, so a later return inherits `backupAttempted` from an earlier stuck episode (seen in the sandbox). Worth a scope." (1.1.264 handoff, 2026-10-17) — ✅ 1.1.279 741355c8 (sandbox 6bq PASS)
- [ ] **P2 (M)** "blind walkDirect fallback after no path" (1.1.239 handoff, 2026-10-14)
- [ ] **P2 (M)** "hand-dig stair escape for a bot with no blocks." (1.1.236 handoff; also 1.1.239 handoff, 2026-10-14; 1.1.240 `TerrainMovePolicy` places dirt stairs but has no no-blocks dig)
- [x] "BotActions.moveRelative same-cell teleport" ("probable IN_WALL root, shared hot file"; 1.1.233 handoff; also 1.1.229 handoff, 2026-10-13) — ✅ 1.1.279 2a42404b + 5eb24b38 (sandbox 6bq: drowning/drop/door/stair PASS, kiting a field item)
- [x] "MovementService off-thread tool swaps" (1.1.233 handoff, 2026-10-13) — ✅ 1.1.289
- [x] "isAtSurface strict under canopy" (1.1.234 handoff, 2026-10-14) — ✅ 1.1.288
- [x] "RL breakBlockAhead ... still instant" (RL is gone since 1.1.244 but `BotActions.breakBlockAhead` is still called by `BotStuckService` `escapeStairs`; 1.1.234 handoff, 2026-10-14) — 2026-10-20: unreachable (`BotStuckService.updateStuckTracker` has no callers); delete the dead path or wire it, then revisit. — ✅ 1.1.289
- [x] "clutter clears ... still instant" (snow/carpet clutter; 1.1.234 handoff, 2026-10-14) — ✅ 1.1.288
- [x] "long automatic return refusals (combat/cooldown/food) still in chat." (1.1.234 handoff, 2026-10-14) — ✅ 1.1.288
- [x] "Return-base door/escape server-thread traverses (BEH ~3603/~3630): let the tripwire name them first." (1.1.223 handoff, 2026-09-25) — stale (triage 2026-10-19): Mode.RETURNING_BASE is never set; return-to-base runs via FOLLOW (BEH:1336, :3165)
- [ ] **P2 (L)** "redstone step 2 (use plates and buttons, iron doors with triggers)" (1.1.223 handoff, 2026-09-25)
- [ ] **P3 (S)** "`LeafClearAction`/`LeafClearResult` still on MovementService." (2026-09-05 late night handoff; also 2026-09-05 night and later handoffs)
- [ ] **P3 (S)** (uncertain) "Duplicate \"Returning to base\" messages on each sunset return." (Farm Pipeline next-session notes, 2026-04-08) (retest in sandbox)
- [ ] **P3 (S)** (uncertain) "Follow mode stuck at 1-block Y differences. Bot can see commander but `directBlocked=true` when 1 block above." (Farm Pipeline next-session notes, 2026-04-08) (retest in sandbox)
- [ ] **P3 (M)** (uncertain) "Bot standing at end of staircase not trying to walk around." ("can't build a multi-level path through interior stairs around a column"; Door passage series notes, 2026-04-16) (retest in sandbox)
- [x] (uncertain) "The `ensureAtSurface` / `nudgeToward failed` loop at session start ... Triggered when bot starts with `leavesTrap=true` under canopy." (Farm Pipeline next-session notes, 2026-04-08) → covered by the field item "A bot under an oak canopy counts as at the surface" (1.1.288 749b8c7a, backlog 145) (triage 2026-10-23)
- [x] "shallow pits with diagonal one-block exits, verifying fewer repeated micro-hop local-escape restarts" (validation ask, likely stale after the 1.1.240+ finder work; Recent Session Notes, 2026-03-31) ✅ stale: a March validation ask, the stuck path was reworked since (1.1.236, 1.1.239, 1.1.264, 1.1.279 741355c8) and those have their own field items (triage 2026-10-23)

### Construction, scaffold and shelter
- [ ] **P3 (M)** Pillar and leaf migrations onto the shared services: "`FarmSkill.pillarEscape`; `WoodcutSkill.pillarUp`, `HovelPerimeterBuilder.pillarUp`, `WoodcutSkill.clearBlockingLeaves`" (reasons: "timing/placement differ from ScaffoldService — a behaviour change, judge in the field first", "need 2–3 options each, fragile skills"; deferred in 1.1.208, repeated in the 2026-09-05 handoffs and 1.1.216 handoff)
- [ ] **P3 (M)** "Egress follow-ups: outside→inside entry routing, and Fortify onto the shared helper." (hovel `enterInteriorViaDoor`; 1.1.218 ruling "don't touch Fortify" for now; 1.1.218 handoff, repeated 1.1.219-1.1.222, 2026-09-25)
- [ ] **P3 (S)** "inline four single-caller delegates (`tryBreakThroughObstacle` ×2, `attemptFinalizeCarveTransaction`, `isCarveEligibleForBreakAttempt`)" (1.1.210 Minor 3; 2026-09-05 late night handoff, repeated through 1.1.216)
- [ ] **P3 (S)** (uncertain) "Suffocation during scaffold descent — bot embeds in terrain after dropping through scaffold gaps." (Farm Pipeline next-session notes, 2026-04-08) (retest in sandbox)
- [ ] **P2 (S)** (uncertain) "Shelter-breakfree misfires on indoor spawn." ("When bot legitimately respawns inside an enclosed structure ... auto-mines upward, destroying stairs"; Door passage series notes, 2026-04-16)
- [x] (uncertain) "This change intentionally breaks the old shelter-escape \"trapped in corner, needs door as exit\" behavior ... re-address later." (Door passage series notes, 2026-04-16) → covered by "Doorway / pressure plate stuck (still recurring)" in P1 below (triage 2026-10-23)

### Woodcut and mining
- [x] "mounted leaf clearing still instant" (1.1.234 handoff; deferred again in 1.1.238. 1.1.267 M3 fixed the shears, not the speed) — ✅ 1.1.286 77df45d2 + 4f20d03f
- [x] "woodcut cleanup async scan off-thread" (1.1.266 B bounded the scan, it still runs off-thread; 1.1.234 handoff, 2026-10-14) — ✅ 1.1.290
- [x] "cleanup hand fallback slot 0" (1.1.233 handoff, 2026-10-13) — ✅ 1.1.282 2b9de28c
- [ ] **P3 (S)** "woodcut surface-staging cost." (1.1.229 handoff, 2026-10-13)
- [x] "weapons+food-only inventory mining thrash (pre-existing)" (1.1.229 handoff, 2026-10-13) — ✅ 1.1.273 3d01ec5a (RepollPolicy) + 1.1.274 e4f77737 (cliff-branch loadout); sandbox PASS FIELD_0_6AI_SANDBOX:92
- [ ] **P3 (S)** (uncertain) "WoodcutSkill still reports `soilFail=29` out of 31 logs in dense forest" ("May need to revisit the soil/hazard heuristic for bounded-mode farm clearing"; Farm Pipeline next-session notes, 2026-04-08) (retest in sandbox)
- [x] "woodcut trunk-entry cases that previously carved `grass_block` / `dirt` support under the stand" (validation ask, likely stale; Recent Session Notes, 2026-03-31) ✅ stale: trunk entry was replaced by mine-from-outside on 2026-04-01, soil protection came in 1.1.275 and 1.1.280 7a4fecfb, field "Two woodcutters leave each other's tree soil and pillars alone" PASS (triage 2026-10-23)
- [ ] **P3 (S)** "standalone and idle woodcut runs, verifying `Planted sapling at ...` or explicit replant skip summaries after per-tree cleanup" (validation ask, likely stale; Recent Session Notes, 2026-03-31)

### Survival, hunger, crafting, farming and mutual aid
- [x] "bundle food in the no-food warning" (1.1.239 handoff, 2026-10-14) — ✅ 1.1.279 1fedf1ce (sandbox 6bq PASS)
- [x] "the gated hunger lines in CollectDirt/StripMine" (1.1.239 handoff, 2026-10-14) — ✅ 1.1.285 9b23cffb
- [x] "SkillResumeService vs recovery thread" ("not waiting on an active recovery thread"; 1.1.239 handoff, 2026-10-14) — ✅ 1.1.289
- [x] "`SmeltingService.resolveFurnaceTarget` `Thread.sleep(150)` (SS:~810/~831) on `/bot cook` and the auto-cook tick." (1.1.222 handoff, 2026-09-25) — ✅ 1.1.279 0d8cd03e (sandbox 6bq PASS)
- [x] "A server-thread craft with a full inventory: `distributeOutput` / `craftWithPlanks` / `ensurePlanksFromLogs` → `offloadCheapItemsToNearbyChest` → deposit walk or `placeChestNearBot`. Same fix shape; needs its own scope." (1.1.222 handoff, 2026-09-25) — ✅ 1.1.279 4569ab87 + 5eb24b38 (sandbox 6bq PASS)
- [x] "`FoodConsumptionConfirmationService.tryHandleConfirmation` has no caller" (was to ride 1.1.225's yes/no router, which did not ship; 1.1.223 handoff, 2026-09-25) — ✅ 1.1.278 410bd6d8 (sandbox 6bp PASS)
- [x] "Defensive-aid tick freeze (`BotMutualAidService:558`)" ("a 1.6–2.4 s tick freeze when a hostile is near an ally. Needs a one-impulse or worker design."; 1.1.223 handoff, = 1.1.222 deferral 3, 2026-09-25) — ✅ 1.1.278 fdc7690a (sandbox 6bp PASS)
- [x] "The MutualAid recipient's inventory is mutated from the donor's tick while its own skill worker may run (pre-existing)." (1.1.222 handoff, 2026-09-25) — ✅ 1.1.290
- [x] "`dropCheapStackForSpace` drops the LARGEST stack when nothing is cheap, and the dropped stack can be re-picked after 2 s." ("Needs a cheap-only mode or a longer pickup delay"; 1.1.222 handoff, 2026-09-25) — ✅ 1.1.279 4569ab87 (restated: aid make-room never drops valuables; sandbox 6bq PASS)
- [x] "A flower keeps targeting a full preferred recipient (cosmetic)." (1.1.222 handoff, 2026-09-25) — ✅ 1.1.283 003fd626
- [x] "the per-hobby backoff state is written for hunt and collect_dirt but read only by woodcut — ready if a later item gates `pickHobby` on tool availability." (1.1.216 handoff, 2026-09-07) — 1.1.288: both idle pickers now read it (cook and hangout never record attempts; axe-bearer bypass and repeated availability reset fixed in source 2026-10-21, unreleased; field check pending)
- [x] ✅ Ruled 2026-10-23 (Bradley): won't do, no field symptom. Reopen only if one appears (triage 2026-10-23, Bradley ruling). Was: "Farm manual placement still has multiple `Heightmap.Type.WORLD_SURFACE` / `findSurfaceY` call sites outside `assessFarmSite`." ("If they misbehave in forests, apply the same walkable-ground-Y treatment"; Farm Pipeline next-session notes, 2026-04-08)
- [x] ✅ Ruled 2026-10-23 (Bradley): bots never fight players, warn only. Alliances already exist (`PlayerAllianceService`, used by `BotAnimalDefenseService`) (triage 2026-10-23, Bradley ruling). Was: (uncertain) "PvE only. ... Alliances feature is the planned PvP gate, not yet built." (Tamed-animal defense notes, 2026-04-11)
- [ ] **P2 (S)** Field check (from .ralph/progress.md, 2026-03-27, never moved to a field phase; re-check against current hunger/cooking code first): spawn bot, give sword, set hunger to 3, verify it hunts and doesn't stop until 4+ food items (.ralph/progress.md, now in docs/handoffs/ARCHIVE_2026.md) (update pass conditions first: auto-hunt threshold 10, food reserve 8, no stop-at-4 target)
- [ ] **P2 (S)** Field check (same source): after a hunt, the bot walks to a furnace and cooks all raw food types in turn (.ralph/progress.md) (update pass conditions first: auto-hunt threshold 10, food reserve 8, no stop-at-4 target)
- [ ] **P2 (S)** Field check (same source): give the bot raw chicken + raw porkchop, verify the idle hobby picks "cook" (.ralph/progress.md) (update pass conditions first: auto-hunt threshold 10, food reserve 8, no stop-at-4 target)
- [ ] **P2 (S)** Field check (same source): bot at food=8 with zero inventory food, verify auto-hunt still triggers (.ralph/progress.md) (update pass conditions first: auto-hunt threshold 10, food reserve 8, no stop-at-4 target)

### Multiplayer, ownership and permissions
- [x] "join-check commander radius" (48; 1.1.236 handoff, 2026-10-14) — ✅ 1.1.284 c982f8b6
- [ ] **P3 (M)** "multiplayer roster payload" (1.1.236 handoff, 2026-10-14)
- [x] "level-name-keyed stores (chest/furnace/beehive registries, hunt + crafting history, fortifications, mapped villages" (1.1.256 fixed spawns only; 1.1.234 handoff, 2026-10-14) — Source complete unreleased2026-10-22: chest was already fingerprinted; remaining stores plus hunting settings isolated with explicit retained-original recovery. Full3652/3652. Field migration checks remain.
- [x] "D7 non-op zone edit/delete" (1.1.233 handoff, 2026-10-13) — ✅ 1.1.287
- [x] "anyone can map a village" (1.1.233 handoff, 2026-10-13) — ✅ 1.1.287
- [x] "claim an ownerless wall" (1.1.233 handoff, 2026-10-13) — ✅ 1.1.287
- [x] ✅ Ruled 2026-10-23 (Bradley): screens only, non-op owners get no typed /bot and no spawn. A server option only if an admin asks (triage 2026-10-23, Bradley ruling). Was: "typed /bot ... for owners" (1.1.233 handoff; also 1.1.231 handoff, 2026-10-13)
- [x] ✅ Ruled 2026-10-23 (Bradley): screens only, no spawn for non-op owners (same ruling as the line above) (triage 2026-10-23, Bradley ruling). Was: "SPAWN for owners" (1.1.233 handoff; also 1.1.231 handoff, 2026-10-13)
- [x] "resurrection owner clause." (1.1.233 handoff, 2026-10-13) ✅ 1.1.272 (Questing-off ritual: owner, op or host)
- [ ] **P2 (S)** "After 6ac/6ad results: bot-slot check refuse mode" (6ad item 7 was clean; `AuthorizedBotDispatch` is still WARN-only; 1.1.233 handoff; also 1.1.232 "D5 refuse mode", 2026-10-13)
- [x] "A bot respawned by the End exit is a plain `ServerPlayerEntity`, so it gets \"not a companion\" until it is recreated." ("fails closed, and the mod's 78 other `instanceof` checks share it"; 1.1.220 handoff, 2026-09-25) — Source fixed unreleased2026-10-22 with exact respawn constructor redirect; actual offline Mixin transformation validated, full3652/3652. Live End/mod coexistence check remains.
- [x] (uncertain) "An offline-mode LAN guest using the host's name passes `isHost`; `configNetworkManager` has the same gap." (1.1.220 handoff, 2026-09-25) — Shared host gate now compares UUID and captured server, unreleased2026-10-22; full3652/3652. Same-UUID spoof authentication is not solved.
- [x] (uncertain) "misleading \"Out of range\" text after an inventory deny (modCommandRegistry)" (1.1.224 handoff/changelog deferral, 2026-09-25) ✅ done: access is checked first, then the real reason is named (too far or another dimension), 1.1.280 51363665 (triage 2026-10-23)
- [x] "the recruiter-fallback prompt edge in `resolveController`" (1.1.224 changelog deferral, 2026-09-25) — Current-owner authority and stale/cache food consent fixed in source, unreleased 2026-10-21; focused 28/28, full 3549/3549. Field checks remain.
- [x] "each rejected preference packet still queues one server task" (1.1.224 changelog deferral, 2026-09-25) — Retargeted 2026-10-21: redundant application dispatch removed in source; Fabric initial dispatch remains. Full-state/resync behavior retained; focus 15/15, full 3549/3549. Unreleased.
- [x] (uncertain) "the reporter says brand-new worlds failed too, which the per-world key should prevent." ("server is full" report; suspects global `config/frens/` sidecars and legacy level-name-only keys; 1.1.226 handoff, 2026-10-13) ✅ done: global spawn accessor and legacy slots removed in 1.1.256 20ae0bae, remaining level-name stores isolated in 1.1.291 7030d816, field 6ad "A new world starts clean" PASS 2026-10-17. The 7-saved-bots case stays open as field item 6x (triage 2026-10-23)

### Supplies and chests
- [ ] **P2 (M)** Ruled 2026-10-23 (Bradley): one combined ask. The bot works out everything a craft needs and asks the chest once ("I need 3 planks and 2 sticks from your chest, OK?"), one approval covers the set. Medium build, not yet scheduled. Was: "Worker skills can't craft from chest materials. There is one prompt per bot, so a plank ask blocks the tool ask. Fixing it needs WaitMode through `ensure*`/`craftGeneric` plus need-bundling, which is Phase 4 and needs a ruling." (1.1.221 handoff; also 1.1.222 handoff, 2026-09-25)
- [ ] **P3 (L)** "Supplies Phase 4: the storage-room walkthrough, need-driven armour requests while following, and the WaitMode/need-bundling ruling above." (1.1.221 handoff; also 1.1.222 handoff, 2026-09-25)
- [x] "`grantableEstimate` works per chest half, so it under-counts double chests." (1.1.221 handoff, 2026-09-25) — ✅ 1.1.286 77df45d2
- [x] "Three \"other half of a double chest\" helpers could be one." (1.1.221 handoff, 2026-09-25) — ✅ 1.1.288
- [x] "CraftingHelper pull hint maps aren't cleared at stop (bounded wall-clock hints)." (1.1.221 handoff, 2026-09-25) — ✅ 1.1.282 2b9de28c
- [x] "A double chest whose other half is unloaded reads as unreadable (skipped for that pass)." (1.1.221 handoff, 2026-09-25) — ✅ 1.1.287
- [x] "A grant-covered ask while the owner is away is still refused (only Always skips the owner-range check)." (1.1.221 handoff, 2026-09-25) — ✅ 1.1.285 9b23cffb
- [x] "The `/bot stop` fix for the chest tool search (read `hasActiveTask` once before the loop) is verified by reading only; its test pins the pure helper, not the wiring." (1.1.221 handoff, 2026-09-25) — Source wiring covered 2026-10-21 by five ToolSearchCancellationWiringTest cases; focused 59/59, full 3498/3498. Unreleased; live stop/replace check remains pending.
- [x] "Pre-existing latch race: after `/bot stop`, a later `beginSkill` for the same bot clears the abort latch, so a search still inside a hop can resume." ("needs the captured ticket's cancel flag (a TaskService accessor)"; 1.1.221 handoff, 2026-09-25) — ✅ 1.1.287
- [x] "Ownership is not re-checked on arrival." (Bot Storage collect; 1.1.220 handoff, 2026-09-25) — ✅ 1.1.285 9b23cffb
- [x] "`verifyChests` revives a record for any chest placed back at its position." (1.1.220 handoff, 2026-09-25) — ✅ 1.1.285 9b23cffb
- [x] "A dimension-fallback collect is dropped." (1.1.220 handoff, 2026-09-25) — ✅ 1.1.278 569c625d (dimension-fallback collect now tells the owner)
- [x] "Two Collects on different chests while the bot is on cooldown fail safe but silently." ("The owner sees two \"arrived\" lines and gets no items."; 1.1.220 handoff, 2026-09-25) — ✅ 1.1.278 569c625d (restated: refused/replaced/cancelled collects tell the owner; sandbox 6bp PASS)
- [x] "A server-thread chest placement miss logs only at DEBUG." (1.1.222 handoff, 2026-09-25) — ✅ 1.1.282 2b9de28c

### Souls, dialogue and audio
- [x] "Audio for \"Hey, incoming!\"." (also listed in untracked `AUDIO_NEEDED.md`; 1.1.261 handoff, 2026-10-17) → covered by "Ruled 2026-10-23 (Bradley): text only" (placeholder voice lines) below (triage 2026-10-23)
- [x] "Extend the punctuation guard to the JSON resources." (1.1.261 handoff, 2026-10-17) — ✅ 1.1.283 003fd626
- [x] ✅ 1.1.295 **P1 (S)** Ruled 2026-10-23 (Bradley): text only. Lines with only silent placeholder sounds show as chat text, voice skips them, placeholders removed (going into 1.1.295). Was: (uncertain) "#10 audio regeneration (needs Bradley's triage)" = "The missing `snow_golem_ammo` and other voice clips." (1.1.239 `ef4ea499` added empty-sound stubs only; 1.1.239 handoff, 2026-10-14; 1.1.223 handoff, 2026-09-25) (with the 1.1.295 session)
- [ ] **P3 (M)** "Later: scene smoothing step 2 (scene boundaries in history)" (1.1.223 handoff, 2026-09-25)
- [ ] **P3 (L)** "self-directed goals (proposer-only)." (1.1.223 handoff, 2026-09-25)
- [x] "The Pocket installer's pre-wait EOF read, which can hang." (1.1.223 handoff, 2026-09-25) — ✅ 1.1.279 911b1243 (unit-tested; not run in the sandbox)
- [ ] **P2 (S)** "HEARD records (the owner's own replies) are never marked private." (1.1.224 changelog deferral, 2026-09-25)
- [ ] **P3 (M)** "remote model pulls and voice installs on the server host" (1.1.225 handoff, 2026-09-26)
- [ ] **P3 (S)** "a voice-preset picker" (1.1.225 handoff, 2026-09-26)
- [ ] **P3 (S)** "a persona picker" (1.1.225 handoff, 2026-09-26)
- [ ] **P2 (S)** Ruled 2026-10-23 (Bradley): ask once in the AI setup checklist ("Give new companions a soul when Ollama is ready?"), and the answer becomes the default for new bots. Was: "auto-enable on spawn" (souls; 1.1.225 handoff, 2026-09-26)
- [ ] **P3 (S)** "dry-run generation" (1.1.225 handoff, 2026-09-26)
- [ ] **P3 (M)** "Addressee deferrals: mid-sentence third-person mentions, \"you and Bob\" clauses, human nicknames." (1.1.219 handoff; repeated 1.1.220-1.1.222, 2026-09-25)
- [ ] **P3 (S)** "the new `ollama usage` log lets `NUM_CTX` be right-sized." (1.1.217 handoff, 2026-09-25)
- [x] ✅ Ruled 2026-10-23 (Bradley): won't do, no field symptom. Reopen only if one appears (triage 2026-10-23, Bradley ruling). Was: "Unifying the two scripted services' cooldown implementations: larger refactor, no remaining field symptom." (1.1.216 handoff, 2026-09-07)
- [x] ✅ Ruled 2026-10-23 (Bradley): won't do, no field symptom. Reopen only if one appears (triage 2026-10-23, Bradley ruling). Was: (uncertain) "Adding a reason enum to `SkillExecutionResult`: touches every skill call site" (reads as won't-do; 1.1.216 handoff, 2026-09-07)
- [ ] **P3 (S)** "`threads.closed` matching (needs a per-question `SoulMindOps.markAnswered` overload)" (1.1.214 deferral; 1.1.216 and earlier handoffs, 2026-09-06/07)
- [ ] **P3 (S)** "`BAD_AT` from repeated `TASK_FAILED`" (1.1.213-1.1.214 deferral; 1.1.216 and earlier handoffs, 2026-09-06/07)
- [ ] **P3 (M)** "TEASE peer rule once `SoulSpeechAct` rides on `GroupSceneTurn`" (1.1.212-1.1.214 deferral; 1.1.216 and earlier handoffs, 2026-09-06/07)
- [x] "`SoulBanterDirector.relationsEnabled()` static read → supplier" (1.1.216 and earlier handoffs, 2026-09-06/07) — Source-complete in the unreleased 2026-10-21 diff: live supplier injected by SoulRuntime; focused 98/98 and full direct JUnit 3471/3471. Not committed, released or field-checked.
- [x] ✅ Ruled 2026-10-23 (Bradley): won't do, no field symptom. Reopen only if one appears (triage 2026-10-23, Bradley ruling). Was: "`MIN_TRIGRAMS` 3→4" ("if the field session shows quoted-idiom drops"; 1.1.216 and earlier handoffs, 2026-09-06/07)
- [ ] **P3 (S)** "OWNER_DAMAGE-peer rule deferred (event carries only `ownerId`)." (1.1.212 handoff, 2026-09-06 evening)
- [x] ✅ Ruled 2026-10-23 (Bradley): won't do, no field symptom. Reopen only if one appears (triage 2026-10-23, Bradley ruling). Was: "ring idle eviction only if a leak shows" (1.1.211 handoff, 2026-09-06)
- [x] "Deferred minors from the 1.1.203 branch: mask receiver unthrottled; wire cap 64 vs 9 ids" (voice mute masks; 2026-09-04 evening handoff) — ✅ 1.1.284 c982f8b6 (cap only; throttle dropped: it lost the last toggle)
- [x] ✅ Ruled 2026-10-23 (Bradley): skip voice cloning, presets only (triage 2026-10-23, Bradley ruling). Was: "Pocket voice cloning (HF-gated)" (never built, "Not in this cut" in 1.1.198; Backlog Lineup 2026-09-03 Lane 3)
- [ ] **P3 (M)** (uncertain) "ontology Phase 3 / second scripted-text personality (spec first)" (ontology half done; may mean the soul personality Silas shipped in 1.1.204; 2026-09-05 handoffs)

### MCP fork and tooling
- [x] "keyboard in-world" (minecraft-mod-mcp fork; open in `docs/testing/MCP_MOD_IMPROVEMENTS.md`; 1.1.236 handoff, 2026-10-14) — ✅ MCP fork 71efa71 (docs/testing/MCP_MOD_IMPROVEMENTS.md:47)
- [ ] **P3 (S)** Ruled 2026-10-23 (Bradley): send the security PRs only (loopback bind with browser-request refusal, safe screenshot paths). Claude drafts each PR text for Bradley's OK first, nothing sent yet. Per-action control mode stays fork-only. Was: "per-action control mode upstream PR." ("Upstream PR plan ... nothing sent" in `MCP_MOD_IMPROVEMENTS.md`; 1.1.236 handoff, 2026-10-14)

### Docs and process
- [x] ✅ Ruled 2026-10-18: per instance (CLAUDE.md and the release skill updated). Was: Needs Bradley: "scope the CLAUDE.md pre-deploy check to the target instance (`pgrep -f "PrismLauncher/instances/1.21.11/natives"`). The any-instance pattern blocks every release while a sandbox test runs." (1.1.263 handoff, 2026-10-17)
- [x] ✅ Ruled 2026-10-18: generic credit "Voice lines are synthetic speech generated with licensed text-to-speech." for the release notes (no engine or voice names). Was: Needs Bradley: "the TTS engine and voice names for the dialogue-clip attribution line (still a placeholder)." (1.1.225 handoff; also 1.1.224 and 1.1.223 handoffs, 2026-09-25/26)
- [x] ✅ 1.1.268 Ruled 2026-10-18: lower the survival food cutoff to 8 (food keeps 8, other materials 16, `472afa60`). Was: Needs Bradley: confirm "Food reserve 16 as the survival cutoff (early game: a bot can't eat from a chest holding ≤16 bread)." (provisional ruling made on his behalf in 1.1.221; 1.1.221 and 1.1.222 handoffs, 2026-09-25)
- [x] ✅ Ruled 2026-10-18: keep 4 (com.bradley.ollama-env LaunchAgent, measured for souls). Was: Needs Bradley: "Whether to lower `OLLAMA_NUM_PARALLEL`." ("machine-wide; other local jobs share it"; 1.1.217 through 1.1.222 handoffs, 2026-09-25)
- [x] ✅ Ruled 2026-10-23 (Bradley): keep both, relations and PlayerMemory do different jobs and never disagreed in play. Earlier: Ruled 2026-10-18: decide after the 6aj-6bg field sweep (note any disagreement seen in play). Sweep done 2026-10-18: no disagreement seen, decision due. Was: Needs Bradley: "relations coexist with `PlayerMemory`, decide after the field session" (1.1.211 handoff, 2026-09-06)
- [ ] **P1 (S)** Ruled 2026-10-23 (Bradley): publish 1.1.295 after its sandbox check, with notes refreshed for 1.1.231 through 1.1.295; Bradley OKs the exact text and uploads. Earlier: Ruled 2026-10-18: publish the first build after the field sweep with no high-severity failures (likely 1.1.268); notes prepared for Bradley's OK, he uploads. 1.1.268 qualifies; draft notes in `.superpowers/sdd/modrinth/MODRINTH_NOTES_1.1.268_DRAFT.md`, awaiting his OK. Was: Needs Bradley: "1.1.231–1.1.233 NOT published" on Modrinth (now everything after 1.1.230; held by the "no Modrinth until the live playthroughs settle" ruling, 2026-10-14; 1.1.233 handoff, 2026-10-13) (with the 1.1.295 session)
- [ ] **P1 (M)** (uncertain) "5. Fresh-install part 2." (no clean-instance run recorded; 1.1.236 handoff; also 1.1.234, 1.1.229, 1.1.227 and 1.1.225 handoffs) In progress: handed to the 1.1.295 session, Claude runs it before the 1.1.295 public release.
- [ ] **P1 (S)** (uncertain) "(mention the REI/EMI/JEI note)" in the Modrinth changelog (1.1.228 handoff, 2026-10-13) (with the 1.1.295 session)
- [x] (uncertain) "Session cards: task_43ec92da (merged finder, now largely done) and task_e6032cb8 (audit) can be dismissed or re-scoped by Bradley." (1.1.242 handoff, 2026-10-15) ✅ stale: both jobs are finished (unified finder default in 1.1.261, dead-code audit 1.1.243 to 1.1.251), dismissing the chips is a click in Bradley's UI (triage 2026-10-23)
- [ ] **P3 (S)** Tamed-animal defense "Manual verification checklist (15 items) is in the spec" (`docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md`), not in the field checklist (Tamed-animal defense notes, 2026-04-11)
- [x] fabric.mod.json still declares ~1.21.10 though the 1.21.10 instances were removed 2026-10-11 (audit side finding) — ✅ 1.1.288

## Backlog Lineup 2026-09-03

**Where things stood on 2026-09-03 (historical lineup, kept for its open lines; current state is the handoff at the top):** main at 1.1.199, deployed to all three Prism instances, 240 commits
unpushed to origin (Bradley must say push). Everything 1.1.185→1.1.199 shipped 2026-08-29/30 and
has only had a four-minute smoke run (1.21.11 `latest.log`, 01:13–01:17 on 08-30), so the soul
track is validation-bound before anything new starts. Suite 621 green.

### Lane 1 — validate what shipped (blocks the soul track)
- [ ] **Guided field session on 1.1.202** — use the ONE merged checklist `docs/testing/FIELD_SESSION_1.1.202.md` (82 items, 11 phases; protocol in `docs/testing/GUIDED_SESSION_PROTOCOL.md`). It supersedes the per-version lists below, which it was built from: group
      chat (1.1.176), banter (1.1.177), local chat (1.1.178), solo remarks + player-addressed banter
      (1.1.181), pacing sliders (1.1.188), per-bot voices (1.1.190–1.1.192), ontology Phase 1+2 seeds
      / open threads / day memories (1.1.196–1.1.198), Pocket TTS install + voice quality (1.1.198).
      Still-open 1.1.175 items too: Piper install retry on macOS, llama3.2:3b speed vs 8B, streaming
      first-word latency.
- [ ] **Memory digest field checklist** (1.1.201, spec §10): tell Jake three things, sleep through a
      night, inspect `mind.json` for the new `playerMemories` / `digestCursors`, listen for a recall
      in banter, run `/bot soul memory Jake`, then `/bot soul reset Jake` and confirm the list is
      empty and `archivedPlayerMemories` holds them. Also exercise `/bot soul digest on|off|status`.
      - Confirm a party-scene fact is attributed to the right player.
      - Reset while a rollover digest is running → memories stay empty.
- [ ] **Post-session autopsy** in the established pattern; fixes as one build.

### Lane 2 — bugs already on the table
- [x] **ollama4j `NoClassDefFoundError` in idle hobbies** ✅ 2026-09-03, commit `7123d83` (1.1.200).
      Root cause: the shared skill-state map was a static on `FunctionCallerV2`, whose static init
      constructs `OllamaAPI`; ollama4j is excluded from non-AI JARs, so every non-LLM caller (idle
      hobbies, auto-hunt, come-recovery, `/bot` skill commands) got a throwaway empty map.
      `SharedStateService` now owns the map; regression test guards the dependency.
- [x] **Woodcut fallback restart loop** ✅ 1.1.216 — repro captured in the 2026-09-06 field log
      (`logs/2026-09-06-session-overlap.log.gz`): 197 restarts at 3–19 per second. The 1.1.200
      diagnostic never printed, and that was the clue — the same wipe removed the signature its
      guard tests. Nothing exotic flips the signature: the active-task branch called
      `clearWoodenFallbackState` on the very next tick, removing the 12 s cooldown the fallback had
      just set for its OWN woodcut run, so the skill exited on the missing axe and restarted. Fixed
      by narrowing that clear to non-own tasks plus a per-(bot, hobby) doubling failure backoff
      (`HobbyBackoffPolicy`, 60 s to a 10-minute cap) with a throttled availability probe so an axe
      arriving in a reachable chest still wakes it within about five seconds. The product question
      underneath — should the axeless fallback punch one tree instead of refusing? — is still open
      and is now a design choice, not a bug. ✅ 1.1.229: ordered woodcut punches a tree for its first
      axe (`WoodcutAxeBootstrapPolicy`). Hobby runs still refuse (ruling, keeps HobbyBackoffPolicy).
- [x] **Soul follow-ups deferred in 1.1.178/1.1.179** — all three were already closed and the
      handoff above was stale: word-boundary bot-name match (`SoulLocalSalience.
      mentionsBotNotLeading`, regex `\b`), `vetoed:roster-lost` pushes `nextEligibleAtMs`
      (`SoulLocalDirector` ~L262), `SoulPlayerActivity.clear()` on runtime shutdown +
      `forget(playerId)` on disconnect (`SoulRuntime` L434 / L697). Verified in code 2026-09-03.
- [ ] **Bob's own TTS reference sample** — still shares Jake's Dreamsleeve clone (open since 1.1.184).
- [x] **P1 gameplay** (detail in the P1 section below): creeper back-away, BotTorchHoldService not — dropped (triage 2026-10-19): roll-up of 352/354/358, which stay open
      firing (diagnostic-first), doorway/pressure-plate stalls (multi-day rework — discuss the
      door-plan architecture with Bradley first). Slot after the field session; they need in-game time.

### Lane 3 — next features, in queue order
- [x] **CONSOLIDATION** — durable cross-surface memory; fold DM / PARTY / banter / local into one
      whole. Design interview + spec first (`docs/superpowers/specs/`), then plan, then implement.
      ✅ 2026-09-04 (1.1.201) — phase 1 shipped as the memory digest; field-test pending.
- [ ] **Action requests** — follows consolidation.
- [x] Smaller candidates named in specs but not built: second soul personality (engagement spec calls
      it the highest-leverage follow-up), ontology Phase 3 (bot↔bot stance, typed relation facts,
      structured output, novelty rejection — (d) ✅ 1.1.211, (a) ✅ 1.1.212, (b) ✅ 1.1.213, (c) ✅ 1.1.214 — Phase 3 COMPLETE), Pocket voice cloning (HF-gated), per-player voice mute
      masks (gated on the stubbed config sync — see Multiplayer Voice Muting below).

### Housekeeping (one commit)
- [x] Decide on pushing the 240 commits. ✅ pushed 2026-09-04 (main = origin/main from 1.1.203 on).
- [x] `AGENTS.md` pgrep check — already synced to the natives pattern on 2026-09-03 (verified 2026-09-04).
- [x] `CLAUDE.md` + `AGENTS.md` "No automated tests" wording — gone as of 2026-09-03 (verified 2026-09-04); CI still `build -x test`.
- [x] `TODO.md` retired 2026-09-03; changelog header already points at RALPH_TASK.md (verified 2026-09-04).
- [x] Vault: `Frens.md` now says main 1.1.203 pushed (vault commit e8f9d83, 2026-09-04); the March backlog note was already updated 2026-09-03.

# Backlog

Future work items, organized by priority. Candidates for future builds through the frens-release-loop skill.

## P1 — High

### User-reported 2026-05-09 (top criticality)

User-flagged batch from in-game observation against deployed 1.1.93 (latest.log: `~/Library/Application Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log`). Listed in working ROI order; subsequent sessions should pick top-down.

- [x] **Stand-down hotkey + stop→drop-sweep cooldown (60s)** — ✅ shipped 1.1.95. New per-bot drop-sweep suppression layer in [DropSweepService](src/main/java/net/wcfcarolina13/GameAI/services/DropSweepService.java) (`suppressFor`/`isSuppressedFor`). `/bot stop` now sets a 60s suppression. New service [BotStandDownService](src/main/java/net/wcfcarolina13/GameAI/services/BotStandDownService.java) snapshots follow target, stops following, suppresses drop-sweep for 60s, then re-issues follow on tick expiry ("Back in formation."). Companion overlay slot 1 repurposed from duplicate "Stop" to `🪖 Stand Down (60s)`; new `bot standdown` brigadier command. Tap `\` still does plain stop. Final implementation differs from the original spec: timers live in `DropSweepService` (drop-sweep) and `BotStandDownService` (follow snapshot), not `BotHomeService` — closer to the existing per-service ownership pattern.
- [ ] **Creeper self-protection / back-away** — 🔧 1.1.202: root cause found and fixed (`BotCreeperDefenseService` filtered on `isIgnited()`, never set by proximity swelling; now `isFusing()`), decision extracted to tested `CreeperEvasionPolicy`, armed bots back away inside 4.5 blocks; **verify in the field session Phase 8**, then check off. Original report: Existing creeper evasion (sprint away when unarmed, [BotEventHandler.java:3794](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3794)) reportedly insufficient — bot still doesn't reliably back off when creepers are near. Audit fuse-distance / armed-vs-unarmed branches and tighten. Likely also needs a dedicated `BotCreeperSafetyService` or expansion of `BotHazardService`.
- [x] **Dangerous-pursuit gate** — ✅ shipped 1.1.97. New [DangerousPursuitGate](src/main/java/net/wcfcarolina13/GameAI/services/DangerousPursuitGate.java) composes 4 rules: target >4 blocks below bot → reject; combined light ≤0 at target → reject; 2+ hostiles within 5 blocks of target → reject; non-aggroed mob → require ranged weapon. Wired into [DropSweepService.collectNearbyDrops](src/main/java/net/wcfcarolina13/GameAI/services/DropSweepService.java) per-drop filter + [BotEventHandler.engageHostiles](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) hostile-filter loop. Self-defense (mob already targeting bot) always passes. Out of scope: route-based fall/light analysis (currently checks target only, not the path); charged-creeper weighting in cluster threshold.
- [ ] **BotTorchHoldService not visibly firing** — 🔧 1.1.217: diagnostics from 1.1.216 logs named the cause — holds DID fire (Jake, light 6–7) but yielded within ≤1 s at light 8 (spatial flicker); Bob carries no torches. Fixed with hysteresis (hold ≤7, keep until ≥12, `TorchHoldPolicy`); verify at night/underground in Phase 6o. 🔧 1.1.202 ships the state-change `[torch-hold]` gate diagnostics; field session Phase 8 names the rejecting gate, then fix. Original: — added in 1.1.72-1.1.91 batch but user reports torch never appears in bot's hand in dim follow situations. Service IS deployed (in 1.1.93+ JAR) and registered ([Frens.java:1131](src/main/java/net/wcfcarolina13/Frens.java#L1131)). Likely caused by: (a) overly-strict 8-block audible-hostile suppression — in caves/at night there's almost always *some* hostile in 8 blocks → torch hold rejected; (b) foreign-swap detection cycling against other systems that mutate selected slot every tick (combat loadout, AutoFaceEntity), so torch gets re-overridden between 5-tick eval intervals; (c) only logs at `LOGGER.debug` so we can't tell which gate is firing. Diagnostic-first: bump key transitions to `LOGGER.info`, ship a build, see what the gate actually rejects. Possible fix after diagnosis: drop the 8-block audible gate, keep only the 16-block visible-LOS gate. Don't touch until diagnostic confirms the root cause.
- [x] **Locked-gate enclosure respect (drop-sweep + pursuit)** — ✅ shipped 1.1.98. New ray-cast helper [DangerousPursuitGate.crossesLockedGate](src/main/java/net/wcfcarolina13/GameAI/services/DangerousPursuitGate.java) samples ~1 cell per block along the bot→target line and returns true if any sample is a tracked locked door / fence gate / trapdoor. Wired into both `isLocationSafeForPursuit` (drop-sweep) and `engageHostiles` combat-target filter. Caveat: over-rejects when a wall has BOTH a locked gate AND an unlocked gap (line crosses the locked cell while a legitimate path goes around). Under-rejection was the actual user complaint, so the trade-off is acceptable. Reuses existing LockableBlockService — no new wall semantics.
- [x] **Bed selection bugs (two related)** — ✅ shipped 1.1.100, user-verified in-game 2026-05-17 (bot now places and uses its own bed from inventory when no nearby bed is available). (a) [SleepService.findNearbyBedFeet](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java) now filters beds with `BedBlock.OCCUPIED == true` and sorts the bot's previously-claimed bed (via `BotHomeService.getLastSleep`) first. Defensive late-occupancy guard added to `tryUseBed`. (b) When all nearby beds are filtered (only-occupied case), the existing placement branch fires with an explicit "Nearby bed is taken. Setting up my own." handoff message. Out of scope: bed reservation across mid-night user step-outs; multi-bot claim contention.
- [~] **Pathfinding cache learning isn't measurably improving** — diagnostic surfacing shipped 1.1.101. [NavHazardCache](src/main/java/net/wcfcarolina13/GameAI/services/navigation/NavHazardCache.java) is the actual learning system (per-cell rejection scoring; pathfinders consult via `penaltyFor`). Wiring is correct — recording fires per `applyMovementInput-reject` (903 today's session), pathfinders read the penalty in both [PathFinder.java:152,187](src/main/java/net/wcfcarolina13/PathFinding/PathFinder.java#L152) and [BaritoneStylePathFinder.java:380](src/main/java/net/wcfcarolina13/PathFinding/BaritoneStylePathFinder.java#L380). Penalty hits now log at INFO (throttled 1/s, ≥1.0 penalty); periodic summary every 5 min lists top scoring cells. Architectural caveat: cache helps when alternative routes exist; tight bottleneck doorways (the user's actual stuck cases) have no alternates, so cache doesn't help there even when working perfectly. Out of scope: chat command for on-demand cache dump (`/bot debug nav-hazard`); promotion-event INFO log; tuning `STREAK_PROMOTION_THRESHOLD` after we have real data.
- [ ] **Doorway / pressure plate stuck (still recurring)**: 🔧 1.1.223: two follow bugs at a plate-driven fence gate fixed (door handling off beyond 6 blocks; summit climb hijack) and bots no longer close powered/plate-adjacent doors — verify in Phase 6u before closing this. User reports persistent stalls at doorways and pressure plates despite the 1.1.5 → 1.1.16 fix series. Re-read the 2026-04-16 door passage session notes (now in `docs/handoffs/ARCHIVE_2026.md`; the known-good list and test procedure are also in `docs/GUARDRAILS.md`) before touching this — their "Architectural concern" section (drop the door-plan state machine entirely; emit door tiles as regular pathfinder waypoints with an `InteractWithDoorGoal`-style observer) is the recommended next move. Discuss with user before attempting; this is a multi-day rework. — 2026-10-20: the open-door wedge (bots off the doorway centre line, jump under the lintel, door shut on a bot) fixed in 1.1.278 25b87bf8 (sandbox 6bp PASS); pressure-plate checks still unrun.

### Pre-existing P1 items

- [x] **Elder Scrolls-style dialogue menu**: Conversation topics, commands, quests — ✅ BotPlayerInventoryScreen:707-727 topics, :2301 tabs (triage 2026-10-19)
- [ ] **Elder Scrolls-style Journal**: Conversation topics, quests, important information with simple filter search
- [x] **Drop-sweep cobblestone loop**: ✅ shipped. Two-layer fix: (1) [DropSweeper.ensureSpaceForDropSweep:284-286](src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java#L284-L286) only drops items when `chestStoreSucceeded` is true — no offload target → no drop. (2) per-bot TTL self-drop suppression in 1.1.70 (commit `247005e`) — [DropSweeper.java:216-217](src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java#L216-L217) calls `CraftingHelper.isRecentlySelfDropped` to reject pickup of items the bot itself dropped within the last 5 min, killing the inter-sweep reacquisition loop even if guard #1 partially fails.
- [x] **Idle during fast-travel cooldown** ✅ 1.1.204 — `TravelWaitService` (hobbies + auto-travel; offload-existing deferred). Was: When a bot wants to fast-travel but has an active cooldown, it should do useful things while waiting (idle hobbies if enabled, chest offloading to nearby existing chests if disabled), then fast-travel when cooldown expires. Currently the bot just sits idle. For sunset→home specifically, don't build new chests — only use existing ones.
- [x] **Axe retrieval from nearby chests**: When the bot runs out of axes during woodcut, check nearby registered chests (via BotChestRegistryService) for wooden/stone/copper axes — nothing better than copper, nothing enchanted. Take one and continue. Currently the bot just stops or mines with bare hands/wrong tool.
- [x] **Bundle-aware inventory scanning** ✅ 1.1.204 (`InventoryIterator` + extract-first; MiningTool/armor/combat selection still direct-only). Was: Partially shipped. Audited 2026-05-06: lodestone compass ([LodestoneCompassService](src/main/java/net/wcfcarolina13/GameAI/services/LodestoneCompassService.java)), navigation artifacts ([NavigationArtifactService:312](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java#L312)), [HoneyCollectSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HoneyCollectSkill.java), and [BundleService](src/main/java/net/wcfcarolina13/GameAI/services/BundleService.java) + [ArtifactScanner](src/main/java/net/wcfcarolina13/GameAI/services/ArtifactScanner.java) all read `BUNDLE_CONTENTS`. Still raw-slot in food detection (HungerService, isFoodItem, cookAllFoodSync), tool selection (MiningTool, armorUtils, CombatInventoryManager), crafting material checks (CraftingHelper), chest offloading (ChestStoreService). Consider a shared `InventoryIterator` utility that yields both direct slots and bundle contents, so every caller gets bundle support automatically.
- [x] **Escape-with-full-inventory** ✅ 1.1.204 — inventory hypothesis disproved; shipped pillar `reason=` plumbing + escape cooldown when still below surface + hotbar-lock guard; verify in field. Was: Guard/patrol stuck escape (pillar via `ensureAtSurfaceForHobby`) fails when inventory has no room for scaffold blocks — `"pillar recovery placed no blocks"` repeated every ~12s. Bot stuck in 1-block hole with full cobblestone inventory. Consider: temporarily drop a non-essential stack, pillar out, pick it back up. Or: use cobblestone directly as scaffold material.

## P2 — Medium

### Inventory & Storage

- [x] **Furnace offload fallback** ✅ 1.1.205 (`FurnaceOffloadService` + `FuelOffloadPolicy`; verify in field). Was: When no chest is available but furnaces are nearby, dump fuel-eligible items (leaves, sticks, planks) into the fuel slot and smeltable items into the input slot. Especially useful during patrol when bot accumulates items with no chest infrastructure.
- [x] **Craft chest from wood** — ✅ already done. [ToolProvisionService.ensureChest](src/main/java/net/wcfcarolina13/GameAI/services/ToolProvisionService.java) crafts an 8-plank chest when planks/logs are available. Wired into [ChestStoreService.java:588](src/main/java/net/wcfcarolina13/GameAI/services/ChestStoreService.java#L588) (offload path), HuntSkill (camp), FishingSkill.
- [x] Shift-click, double-click, drag support in inventory UI — ✅ HandledScreen delegation :4809/:4838 + ScreenHandler.quickMove :160 (field check still FIELD_SESSION:1517)
- [ ] Quick-action buttons (Sort, Equip Best, Take All, Give All)
- [ ] Bundle packing verification: drop_sweep crafts/uses bundles when inventory is truly full
- [ ] Chest management overhaul: locking/access policy, categorization rules, organization modes

### Follow / Come

- [ ] **Guard/patrol verification**: In-game tests for radius handling, stuck escape in various terrain, interaction with combat and drop sweeps (partially done 2026-03-28: UI radius controls, stuck escape, HUD mode display)
- [ ] **Come tool crafting (verification)**: Verify torches/shovels/pickaxes are provisioned in-world when recipes/materials permit
- [ ] **Follow stability (verification)**: Core planner/backoff/waypoint recovery runtime verification across dimensions/terrain
- [ ] **Deterministic follow/come assertions (verification)**: Run `FOLLOW_COME_ASSERT_RUNBOOK.md` and record pass/fail outcomes
- [ ] **Follow vertical recovery (verification)**: Bot should attempt a nearby projected anchor reroute first, then prompt regroup if still blocked
  - [ ] In-game check: have commander drop into a shaft with a nearby reachable staircase and verify follow reroutes to descend
  - [ ] In-game check: while following, place a 1x1 deep shaft in the movement lane; verify bot sidesteps/stops

### Shelter (Redo Needed)

- [x] **ShelterSkill refactor**: Split `ShelterSkill.java` into smaller hovel/burrow builder classes — ✅ b5479ada (BurrowBuilder + HovelPerimeterBuilder)
- [x] **ScaffoldService extraction** ✅ 1.1.208 (ladders moved. Not migrated, on purpose: `FarmSkill.pillarEscape` (its fixed 120 ms timing, placement and lack of a 12-step cap differ, so migrating is a behaviour change to judge in the field first), `WoodcutSkill.pillarUp` and `HovelPerimeterBuilder.pillarUp` (each needs 2–3 new options), `WoodcutSkill.clearBlockingLeaves` (mutates reach-session counters); changelog 1.1.208. Tracked as an open line under "Recovered deferrals", Construction, scaffold and shelter.)
- [x] **LeafClearService extraction** ✅ 1.1.208 (navigation clearing moved + shared clearLineOfSight; WoodcutSkill's LOS variant deferred)
- [ ] **Shelter resource acquisition flow**: Auto-collect/craft materials by default; allow `ask|wait|manual` to pause
- [x] **Shelter options parameter**: Investigate what `options` currently controls for hovel/burrow — ✅ answer: getOption (ShelterSkill:543-567) reads options only to pick the type; first of burrow/hovel/hovel2 wins, else first token, default hovel; only burrow branches (:86); radius/wall height/door side come from other parameters
- [ ] **Shelter chest workflow**: Withdraw/deposit resources and place chests inside planned interior
- [x] **Burrow "descend-stripmine-descend"**: Restore intended method — ✅ BurrowBuilder:68-148 (descend 5, strip throat, descend 3, hollow; resumable phase state)

### Construction (Blocked — formerly active task)

#### Carried over from the retired `TODO.md` (written 2026-02-25, never revisited)
- [x] **FortifyVillageSkill Phase 2** — 1.1.209: `FortifySharedContext`/`FortifyTowerContext`, `FortifyCleanupProcessor`, `FortifyTowerHelper`; skill 9,560 → 7,093 lines, zero behaviour change (field check Phase 6h).
- [ ] Playtest fortify tower reliability pass (top-block repair verification, scaffold summit step/return, deferred cleanup backoff) on live server and tune retry/backoff values from `latest.log`.
- [ ] Playtest Learning Mode v1 on a live fortify/scaffold scenario (`pillaring_to_roof`) and confirm trace quality + overhead at `detail=balanced`; tune snapshot radius/tick rates if needed.
- [ ] Capture at least one intentional failed pillar/summit demo (`/bot learn stop fail`) to tune generalized scaffold retry/recenter thresholds from trace timing.
- [ ] Playtest fortify tower cavity-ignore flow (`report_cavities` particles + user-guided repair) on live server and adjust thresholds if needed.
- [x] Generate and map audio for fortify cavity callouts (5 variants) listed in `AUDIO_NEEDED.md`. — ✅ 1.1.283 003fd626


- [ ] **Construction parity baseline**: Establish measurable parity for generic schematic builds, shelter/hovel/burrow, fortify wall/patch/moat, and other block-placement paths
- [x] **Shared construction reach/scaffold**: Standardize feet-based reach, LOS-aware recovery, scaffold stance rules in the generic service layer — ✅ ConstructionPlacementRules + ConstructionRecoveryService:441 (changelog parity slice 1); shelter builders not migrated
- [ ] **Generic schematic bottlenecks**: Remove remaining bottlenecks in `BuildSchematicSkill` and `ConstructionRecoveryService`
- [ ] **Shelter onto shared semantics**: Move shelter/hovel/burrow onto shared reach/scaffold without regressing geometry-specific behavior
- [x] **FortifyVillageSkill Phase 2 refactoring** ✅ 1.1.209 (cleanup/tower) + 1.1.210 (carve, Phase 3 — extraction complete): `FortifySharedContext`/`FortifyTowerContext`/`FortifyCarveContext`, `FortifyCleanupProcessor`, `FortifyTowerHelper`, `FortifyCarveHelper`; skill 9,560 → 6,185. Phase 1 complete (extracted EntombmentHelper, SkillTypes, CleanupHelper, LayoutHelper, EscapeHelper — reduced by ~740 lines)

### Commands / UX

- [x] **Command pruning review** ✅ 1.1.207 — both KEPT (only yaw-setter; only WorkDirection reset); README name fixed
- [ ] In-game check: verify guide/search usability and that actions launched from adjusted counts run with the expected arguments
- [x] **Base Manager UX polish (carry-forward from 2026-04-20)**: the menu mixes registered bases (yellow `[Base]`) with lodestone compasses (white rows) in one flat list, and `[Home]` means two different things depending on row color. Even the dev got confused. A minimal inline legend landed in 1.1.39 and a "Home & Bases Explained" guide topic was added, but the full fix is: (a) sort rows so registered bases come first, lodestones second; (b) insert section headers (`Registered Bases`, `Lodestone Compasses`); (c) on row hover, show a one-line tooltip describing what clicking `Set Home` will do for that row type; (d) when the user clicks `Set Home` on a row, echo the stored label back in chat (`Jake will treat 'home' as home.`) — so they can immediately verify it took. — ✅ BaseManagerScreen:282/:132/:145/:1332
- [ ] **Named-hostile-mob pacifism: in-game verification (reopened 2026-10-17)** — no field phase covers it and the "6-item test plan" it cites was not found in the old session notes or `docs/superpowers/specs/` (the notes are now in `docs/handoffs/ARCHIVE_2026.md`). Verify: a name-tagged hostile is not engaged, flee still fires when it deals damage, `/bot attack_named_mobs on|off|toggle` and the Admin row flip it. Original line: implemented in `aeef62a` (1.1.55). `BotCombatPolicyService.shouldBotAttack` gates engagement; named hostiles still appear in scans so flee fires on damage; per-bot `attackNamedMobs` opt-in toggle reachable via `/bot attack_named_mobs <on|off|toggle> [target]` and the Admin tab "Attack Named Mobs" row. **Manual in-game verification still pending.**
- [x] **`CompanionSpellsScreen` cleanup — partial**: Audited 2026-05-06. The legacy screen is **not** fully decommissioned — it's still reached via the dedicated `KEY_OPEN_SPELLS` keybind path in [FrensClient.java:660 → 1578](src/main/java/net/wcfcarolina13/FrensClient.java#L660), the recruit-contact key fall-through after recruitment, and the temporary `-` go-to-spells override (when holding a spell trigger item). `isEyeSpellOnCooldown` / `armEyeSpellCooldown` are also still consumed inside `FrensClient` itself, not just the legacy screen. So the original "delete the file" plan would break the keybind UX. What WAS removable: the dead `openSpellsMenu` method in `BotPlayerInventoryScreen` — defined but never called. Removed in 1.1.68. Open follow-up: decide whether to migrate the `KEY_OPEN_SPELLS` keybind path to also use the unified Spells tab (would let us actually delete the legacy screen) or accept the dual UX as intentional.
- [x] ✅ 1.1.207 (changelog "Command pruning review": the Actions-tab Regroup entry is gone, Spells keeps it; the `Return Home` vs `Home` label question below was not part of that fix) **Actions-tab Regroup duplicate**: `Regroup` lives in both the Actions tab (`Orders & Travel`) and the Spells tab (Movement). Users may hit the Actions-tab version first; it runs `/bot companion come` which the server then rejects if artifacts are missing. Decide: keep both with a visible "gated" indicator on the Actions copy, or remove Regroup from Actions and leave it in Spells only. Same question for `Return Home` (Actions) vs `Home` (Spells) — distinct actions (`RETURN_HOME` vs `COMPANION_HOME`) so this may just need clearer labels.

### Navigation & Movement

- [x] **Lodestone fast-travel: pre-check mount placement before bot teleport** — ✅ shipped 1.1.124 via a richer fix than originally planned. `TravelMountHandler.evaluateTravel` (the pre-discard pre-flight already used by both lodestone fast-travel and chorus-recall) now attempts `tryTetherAtSourceForSameDim` when the destination is too tight for the mount, instead of immediately refusing. If the mount can be tethered to a nearby fence (or one can be placed), travel proceeds with bot alone and the mount waits at source — same pattern as the existing cross-dim tether. Otherwise refuses with a clearer message ("no fence within reach, no lead, or no spot to place a fence"). Also renamed `TETHERED_CROSS_DIM` → `TETHERED_AT_SOURCE` (semantics now cover both) and documented the load-bearing dismount invariant in `evaluateTravel`'s Javadoc. The fallback `mountPos = safeSpot != null ? safeSpot : ps.dest()` at [NavigationArtifactService.java:1457](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java#L1457) is now unreachable in practice (pre-flight `hasAnimalRoom` ensures `findSafeAnimalSpot` succeeds at recreation time) but kept as a defensive last-resort.
- [ ] Swimming parity (surface and underwater, verify behavior matches survival movement)
- [ ] Boat support (enter, exit, navigate) — partial: [TravelMountHandler](src/main/java/net/wcfcarolina13/GameAI/services/TravelMountHandler.java) handles boat mount/dismount/leashed-rejoin sync (incl. ChestBoatEntity); free-form bot-driven boat *navigation* (steer, paddle to a point) is not implemented.
- [ ] Test fishing from a boat
- [ ] Portal following (Nether, End)
- [x] Cross-realm teleport command — ✅ modCommandRegistry:4887-4932 (Tome/enchanting-table gated)
- [ ] Water-aware pickup (wade/bridge)
- [ ] Edge/hole pickup (hop down safely)
- [x] Add shelves and containers to no-break list — ✅ already done. [ProtectedStructureBlockHelper.isProtectedContainer](src/main/java/net/wcfcarolina13/GameAI/services/ProtectedStructureBlockHelper.java) covers bookshelves (incl. chiseled), all chest variants, barrels, hoppers, dispensers, droppers, decorated pots, crafters, brewing stands, furnaces, blast furnaces, smokers, and all 17 shulker box variants. Wired through `isNeverBreakBlock` and consulted from BotStuckService + MovementService.

### Fishing

- [ ] Verify leaf-block clearing when navigating far from shoreline
- [ ] Verify fishing from higher vertical positions (cliffs/piers)
- [ ] In-game check: trigger `/bot fish` while bot is swimming/submerged and verify it relocates to dry shore before first cast
- [x] **Fishing reach** ✅ 1.1.207 — 12→28, ±3, column pre-filter
- [x] **Water location memory** ✅ 1.1.207 — `WaterSpotMemory` in BotHomeService.WorldData, shared with farming

### Combat & Safety

- [x] Creeper evasion (sprint away when unarmed) — implemented in [BotEventHandler.java:3794](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3794): default flee sprints away within 6 blocks, raises shield within 4.5, with extra `isIgnited()` boost in scoreThreat (line 3603).
- [x] Protected build zones (no-grief areas) — implemented as [ProtectedZoneService](src/main/java/net/wcfcarolina13/GameAI/services/ProtectedZoneService.java) with AABB zones, persisted per-world; consulted by FeedAnimalsSkill, MiningHazardDetector, and others.
- [x] Fight with teammates — ✅ BotThreatService.augmentHostiles (BEH:1175, BotThreatService:60-80), since e7a047e7
- [ ] In-game check: stand near passive endermen and confirm bot does not face/aggro them; then provoke one and confirm bot can still target it once hostile
- [ ] In-game check: drop bot from lethal height with/without a water bucket (Overworld), verify clutch attempts near impact and no attempts in ultrawarm dimensions — **code shipped** in [BotFallSafetyService](src/main/java/net/wcfcarolina13/GameAI/services/BotFallSafetyService.java) ("Attempts a last-second water-bucket clutch when a lethal fall is detected"); just unverified in-game.
- [ ] Ride sync verification: mount/dismount mirroring across entities — **code shipped** in [RideSyncService](src/main/java/net/wcfcarolina13/GameAI/services/RideSyncService.java); just unverified in-game.
- [ ] Ride sync leashed persistence: tethered after disconnect/rejoin — **code shipped** in [Frens.java:803-809](src/main/java/net/wcfcarolina13/Frens.java#L803-L809) (`trySecureMountBeforeDismount` + `secureLeashedMountOnDisconnect`); just unverified in-game.

### Crafting & Building

- [ ] Craft common items (armor, torches, etc.)
- [x] Crafting helper: report missing items ✅ 1.1.207 (`CraftingRequirementsPolicy`; torches/bed/door/fence + unknown names)
- [x] Crafting table craft: craft when inputs exist; announce success or missing items in chat — ✅ sandbox PASS FIELD_6AD_6AL_SANDBOX_2026-10-17:42, FIELD_0_6AI_SANDBOX:376
- [x] Placement: place crafted table/furnace/chest near commander safely — ✅ modCommandRegistry:2651/:5612-5682
- [ ] Build walls (specified materials, dimensions)
- [ ] Simple 2-person house
- [x] Block placement primitives — ✅ BotActions:961-989 PlaceResult
- [ ] Recipe awareness: refuse and explain if commander lacks recipe

### Farming & Survival

- [x] **Hunger-aware task interruption** — ✅ shipped 1.1.102. New helper `HealingService.shouldPauseForStarvation(bot)` returns true iff starving + autoEat fails. Wired into [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java), [CollectDirtSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/CollectDirtSkill.java) (covers MiningSkill), [WoodcutSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java) main loop. Skill bails with `flagManualResume`; user feeds bot then `/bot resume`. HuntSkill / FishingSkill / GrassSeedSkill keep their existing in-skill checks. FarmSkill / Bridge / Shelter / Fortify not yet — main-loop boundaries non-obvious; add when symptom hits.
- [x] Till soil, plant seeds, harvest, replant — implemented across [PlantSeedsSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/PlantSeedsSkill.java), [HarvestCropSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HarvestCropSkill.java), and [FarmSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java) (full pipeline: assess site → till → irrigate → plant → harvest → replant; auto-replanting validated 2026-04-08).
- [x] Create infinite water source — [FarmSkill.java:332+](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java#L332) detects + uses an existing 2×2 still-water basin, but does NOT yet *create* one when none exists. The "create from scratch" path is still open. — ✅ FarmSkill:470-482 2x2 dig-and-fill since 5a0eb283 (2025-12-11); the 2026-05-06 'not yet' note was wrong
- [ ] Animal husbandry (shear, collect meat, pen animals) — partial: shears used for [WoolSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoolSkill.java) (sheep) and [HoneyCollectSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HoneyCollectSkill.java); auto-hunt collects meat. Breed/pen behavior not done.
- [ ] **Farm underground recovery**: Escape when underground with overhead dirt
- [ ] **Farm chest workflow**: Proactive chest placement/use during farming
- [ ] **Farm irrigation leak patching**: Detection partially shipped — [FarmSkill.java:741](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java#L741) (`irrigationLeakReason`) flags leaks; the *patch* path (replace flowing-water cells with source / plug missing edges) is still open.
- [ ] Hobby verification: flower picking, feed-animals, hobby hunt behavior
- [x] **HealingService cooked food preference**: ✅ Auto-eat now prefers cooked over raw via two-pass search in [HealingService.findCheapestSafeFood](src/main/java/net/wcfcarolina13/GameAI/services/HealingService.java). Raw meats (`BEEF`, `PORKCHOP`, `MUTTON`, `CHICKEN`, `RABBIT`, `COD`, `SALMON`) are skipped on the first pass and admitted on a second pass only if no cooked food is available. Closes the raw-chicken food-poisoning hole and stops the bot from gnawing raw beef next to a stack of cooked beef. Same `cheapest-within-tier` ordering preserved otherwise. See changelog 2026-05-08.
- [x] **Smoker preference for food cooking** — ✅ shipped 1.1.102. New `FurnacePreference { FOOD, ORE, ANY }` enum threaded through `resolveFurnaceTarget`. FOOD prefers SMOKER (rejects BLAST_FURNACE), ORE prefers BLAST_FURNACE (rejects SMOKER), ANY accepts all. Two-pass selection at every step (commander look-at, shared registry, nearest scan, inventory placement). `cookAllFoodSync` always passes FOOD; `startBatchCookInternal` passes FOOD when `foodOnly`. Smoker crafting (logs + cobblestone non-grid recipe) still out of scope — generic furnace remains the crafting fallback.
- [ ] **Fuel acquisition fallback**: If no fuel in inventory, attempt mini leaf-litter collection before giving up on cooking

### Hunting — Multi-Day Self-Sufficiency (Future Phase)

- [ ] **Hunt camp shelter**: Bot builds a small hut with a bed and door at hunting grounds for multi-day hunts
- [ ] **Hunt self-sufficient resource gathering**: Bot gathers wood/dirt/cobblestone for camp building and chest crafting

### Hobbies (new ideas)

- [x] **Walking dogs** — implemented in 1.1.65 as [BotDogWalkingHobbyService](src/main/java/net/wcfcarolina13/GameAI/services/BotDogWalkingHobbyService.java). v1 design choice: opportunistic-only (no detour to find a wolf — fires when bot is within 3 blocks of an eligible sitting wolf during idle time). Sessions 3–10 min, 50% sit-at-home roll, external cancellation via per-tick `wolf.isSitting()` re-read. See changelog 1.1.65 for full notes. Open follow-ups deferred:
  - ✅ Voiced "Going for walkies" / "Who's a good dog?" line on session start — shipped via [BotDogWalkingHobbyService.playSessionStartLine():176-188](src/main/java/net/wcfcarolina13/GameAI/services/BotDogWalkingHobbyService.java#L176-L188), 50/50 between `LINE_WALK_DOGS_GOOD_DOG` and `LINE_WALK_DOGS_WALKIES` ([BotDialogueSounds:908-909](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java#L908-L909)).
  - Multi-dog sessions (currently 1 wolf per bot).
  - Bot-side detour-to-wolf if user wants the bot to actively seek out sitters (currently fully opportunistic per spec).

### Mining & Resource Gathering

- [x] Tree chopping (safe climbing, late drop collection) — implemented as [WoodcutSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java) (also a registered hobby `woodcut`). Mine-from-outside trunk, scaffold ascent, late drop sweep, hazard scanning, sapling replant — all shipped through 2026-04-03.
- [x] Strip mining with safety offset (sand, gravel, lava) — implemented as [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java); torch placement + falling-block guards in place.
- [ ] **Cubic-area clearing skill (or stripmine extension)**: User wants the bot to clear large rectangular volumes near a base — e.g. excavate a 10×4×10 area for a future build. [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java) is line/branch-shaped only and isn't a fit. Could be a new skill `excavate <w> <h> <d>` that takes a corner anchor + dimensions and runs a layered sweep from top-down (avoids drops landing in unmined cells). Reuse [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java)'s safety primitives (torch placement, falling-block guards, hazard detection) where possible. User flagged 2026-05-09 as a follow-up to the protected-zone-override work.
- [ ] Cave/structure detection and reporting
- [ ] Water encounter handling

## P3 — Low

### Multi-Bot Features

- [x] Per-bot chat behaviors/personas (beyond routing) — ✅ SoulProfileRegistry:30-32 (jake/bob/silas), voices 1.1.190-192
- [ ] Broadcast command UX polish (feedback per bot)
- [ ] Shared job coordination (queue fan-out, conflict handling)
- [ ] Resume prompts respect group commands

### Advanced Combat

- [ ] PVP sparring mode
- [ ] Army formations (line, grid)
- [ ] Archer positioning
- [ ] Horse flank maneuvers

### Quality of Life

- [ ] Command queuing (multi-step instructions)
- [x] Voiced banter variants for follow-adventure lines — ✅ BotAmbientChatter:219-224/:1442 (creeper_kidding has 1 take)

### Dialogue / Voiced Lines (backlog 2026-05-04)

New mob-proximity and context-triggered ambient lines. All would extend the existing dialogue pipeline (`PetProximityReactionService` / `CompanionOverheadDialogueService` / `BotDialoguePlayer` / `BotDialogueSounds` + subtitle map in `BotDialoguePlayer.SUBTITLE_MAP` + `DialogueTextMapper.EXACT_MAP`). New OGGs need to be staged + wired through `BotDialogueSounds` constants.

**Cute-animal "can we keep it?" pool** — fires near untamed cute mobs. Candidates: foxes, ocelots, axolotls, pandas, bees, rabbits, parrots, sniffers (already have own line below), turtles. Long cooldown (~5–10 min/bot).

**Pandas** — variant-specific lines keyed off `PandaEntity.getMainGene()` (NORMAL / LAZY / WORRIED / PLAYFUL / WEAK / BROWN / AGGRESSIVE). At minimum:

- Worried → "That panda looks stressed."
- Lazy → reference to flopping over.
- Brown → rare-variant callout.
- Aggressive → wariness line.

**Foxes & ocelots** — "Don't let it near the chickens." Single shared pool, fires when a fox or ocelot is within ~12 blocks AND a chicken is within ~12 blocks of the bot.

**Cats** — literal "Meow." line near tamed cats. Probably a separate pool from the existing tamed-animal-nearby pool (this one isn't a quality assessment).

**Zombified piglins** — "What's up, porkchop?" near `ZombifiedPiglinEntity` (current name; was "zombie pigman" pre-1.16).

**Hoglins** — "If they give us gravel again I'm going on a bacon spree." near `HoglinEntity`. (Reference to piglins bartering gravel.)

**Piglin brutes** — "That one's bigger than the others!" near `PiglinBruteEntity`. (User originally said "hoglin brute" — there's no such mob; this is the piglin brute.)

**Sniffers** — "Dinosaur." or "That's the cutest thing I've ever seen." near `SnifferEntity`.

**Vexes** — "Goblins with wings! Duck and cover!" near `VexEntity`.

**Guardians** — fires near `GuardianEntity` (ocean monuments). Wiki notes the guardian's eye follows the player and the laser charges over 4 seconds, going purple → yellow. Two trigger states:

- Proximity (mere sighting, ~16 blocks): "It's staring right at me." / "I don't like the way it's looking at us."
- Laser-charging (when guardian has begun beam wind-up on the bot or commander): "Why is it glowing at me?!" / "That beam is gonna hurt — move!"

**Elder guardians** — fires near `ElderGuardianEntity`. Rarer/more-emphatic pool: "That one's the boss. We should leave." / "Mining Fatigue incoming, I just know it." (References the iconic Mining Fatigue debuff Elder Guardians inflict.)

**Squids** — fires near `SquidEntity` underwater. Benign tone:

- "Just a squid." (mundane)
- When ink cloud spawns nearby (squid was hit): "...Ew."

**Glow squids** — fires near `GlowSquidEntity` (deep dark water, Y < 30). Curious tone:

- "Pretty." / "It's glowing." (single-word delivery preferred, since it's an "aqua luminescent" rare passive)
- "Grab the ink — that color's rare." (only the glow variant drops glow ink sacs)

**Dolphins** — "Did you see that dolphin?" Fires when a `DolphinEntity` enters the bot's view cone within ~16 blocks.

**Nautilus, untamed** — "Never going near the ocean again." Fires near a wild `NautilusEntity` (or whatever the entity class is named in 1.21.11; per [minecraft.wiki/w/Nautilus](https://minecraft.wiki/w/Nautilus) they're neutral mobs that spawn 1–3 per group in all ocean biomes between Y 38–58, retaliate when attacked, and dash at pufferfish — so the fearful-traveler tone fits).

**Nautilus, tamed** — "You can actually ride one of these?" Fires near a tamed nautilus (pufferfish-tamed, saddle-equipped). Per the wiki, ridden nautiluses grant "Breath of the Nautilus" (oxygen-pause underwater) and have a dash ability — a second optional line could reference the dash: "It just *jumped* through the water!" Confirm the entity class and tamed-flag accessor at implementation time.

**Redstone machines (proximity)** — fires near complex redstone setups. Lines: "Tech-o-no-lo-hee-ah" / "We literally went to hell and back to build this."

- Lowest-impact detection proposal: on the existing 20-tick idle scan, count powered redstone components (repeaters, comparators, observers, pistons, dispensers) with `block.hasComparatorOutput()` or `state.get(Properties.POWERED) == true` in a 5×5×5 box around the bot. Threshold ≥ 4 components AND ≥ 2 distinct block types triggers the pool. 90s cooldown. Skips if bot is already in a pre-classified base structure.

**Mob-crusher detection (anti-cruelty line)** — "Totally humane." / "100% cruelty free." / "There's a special place in the Nether for whoever built this."

- Lowest-impact detection proposal: scan within ~8 blocks for any `BlockPos` containing ≥ 6 living entities of the same passive type (cows, sheep, pigs, chickens, villagers — explicitly **excludes** hostile mobs per user spec, so skeleton/zombie grinders don't fire). Use `world.getEntitiesByClass(LivingEntity.class, smallBox, …)` filtered to passives, grouped by type. Long cooldown (~10 min/bot) since the line is editorial, not a scan-frequent reaction.

**"That's a quality animal."** — ✅ scope down + slow down shipped.

- Pool split landed: [PetProximityReactionService.java:79-87](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L79-L87) defines `ANIMAL_WELL_BEHAVED_LINES` (broad tamed-non-wolf trigger, 90s cooldown) and `MOUNT_QUALITY_LINES` ("That's a quality animal", mount-only trigger via `hasNearbyMountAnimal` at [lines 213-232](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L213-L232) covering tamed `AbstractHorseEntity` / any `CamelEntity` / `LlamaEntity` & `TraderLlamaEntity` as `AbstractHorseEntity` subclasses).
- Cooldown bumped: [line 36](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L36) — `MOUNT_QUALITY_COOLDOWN_MS = 5L * 60_000L`.

## Bot livestream — cinematic Twitch/YouTube feed (Backlogged 2026-10-13)

Bradley wants a live feed of the bots living their lives, with cinematic camera angles. Plan discussed 2026-10-13:
- [ ] **Scope first: bots with no owner nearby.** Much behaviour orbits a commander. Verify what hobbies, idle/active
  banter and SpeechFloor (keyed by a real-player audience) do with only a spectator account online. Decides whether a
  "living world" mode (self-chosen goals: build, farm, fish, explore, go home at sunset) is needed.
- [ ] **PoC director:** second MC account in spectator mode; client-side director cuts `/spectate <bot>` + third person
  to whoever is speaking/acting (server already knows scene starts and speakers); OBS → unlisted YouTube.
- [ ] **Cinematic camera:** free camera with a shot vocabulary (establishing wide, orbit, shot/reverse-shot in
  dialogue, tracking on travel, event cutaways), smooth interpolation; OBS browser-source overlay fed by a small local
  endpoint (speaker subtitles, current task, time of day).
- [ ] **24/7 hardening:** launchd watchdog/restart, memory/log growth, LLM content filter + kill switch (Twitch ToS),
  probably off-laptop hosting (server + Ollama + TTS + MC render + OBS encode overloads one M2 Pro). Twitch-chat
  interaction later only, behind prompt-injection safeguards.

## Showcase videos from replays (Backlogged 2026-10-18)

Bradley, 2026-10-18: Flashback replays rendered unattended in the sandbox (`tools/sandbox/fbexport.py`, MCP fork `5013f8c`
and `3c548bd`) plus the motion-studio skill make a pipeline for showcase content, not just progress clips.
- [ ] **Refresh the Modrinth page videos** with what Frens can do in general (woodcut on its own scaffold, shelters at
  night, death retrieval, leads and mounts, souls talking, several bots sharing work). Plan the shots per feature in a
  sandbox world, record with Flashback, export 4:5 or 16:9 clips with `fbexport.py`, cut with motion-studio. First
  reference: the "Leave No Trace" film (`~/pontus/experiments/frens-leave-no-trace`). Bradley approves every video and
  uploads himself; nothing is posted by Claude.

## Installer portability (Backlogged 2026-09-04 — only ever tested on the M2 Pro Mac)

Bradley asked whether the installers cater to the user's machine. They detect **platform**, not specs:
- [x] **Pocket TTS installer has no Windows support** ✅ 2026-09-04 (`efd13c7`, `7944410`) — Windows uv/py-launcher/python discovery, `Scripts\` venv layout, WindowsApps stub exclusion, py-launcher false-positive fix. **Still to verify on a real Windows box**: `pocket-tts.exe` console-script name, no-shell process start, `Files.isExecutable` on `.exe`. Original: — `PocketInstaller.uvCandidates/pythonCandidates` search Homebrew, python.org framework, `/usr/local` and `PATH` for `uv`/`python3`; no `uv.exe`/`python.exe`/`py` launcher, no `Scripts/` venv layout. A Windows user with Python installed gets "No Python 3.10+ or uv found". Add Windows candidates + venv `Scripts\python.exe` path, and test.
- [ ] **Piper Windows x64 / Linux x64 paths are pinned (sha256) but untested** — zip extraction on Windows and the `.exe` name, `.so` completeness on Linux. ARM Windows/Linux are explicitly unsupported (screen says so). Needs one run each.
- [x] ✅ 1.1.225 (RAM half: the model manager reads total RAM and warns; the checklist shows the server host's RAM. Cores/GPU are still undetected.) **No RAM/cores/GPU detection anywhere** — the Ollama model manager shows a recommended-RAM guide per model but does not read the machine's RAM; Pocket pins `OMP_NUM_THREADS=1`; Piper spawns one process per voice. A cheap win: read `Runtime.maxMemory` / `OperatingSystemMXBean.getTotalMemorySize()` and warn when a model's recommended RAM exceeds it.
- [x] ✅ 1.1.203 (changelog "Dreamsleeve greyed off macOS": the engine chooser greys the row and says "macOS only") **Dreamsleeve is Mac-Metal only by construction** — fine, but the engine chooser should grey it out off-macOS instead of letting the user configure a path that can never work.
- [x] README now states all of the above honestly (2026-09-04).

## TTS Latency / System Load (Backlogged 2026-08-25)

Bradley (field round on 1.1.167/168): soul TTS response "quite slow, and seems to bug the
laptop even at low graphics/shader settings" — wants a lighter path tested if quality holds.
Audit: only live engine is Dreamsleeve (Qwen3-TTS 12Hz 1.7B 8-bit, MLX/Metal warm server);
`PiperVoiceEngine` exists in code but no piper binary is installed; `soulVoicePiperBinary`
is empty in live config.

- [x] **Sentence-streaming synthesis** — DONE 2026-08-25 (commit `aefc5d3`): per-sentence
      segments share a groupId; client queues them on one OpenAL source. Field-verify the
      first-word latency drop in-game.
- [x] **Verify LoadGoverner covers synthesis windows** — CONFIRMED BROKEN and fixed same
      commit: scheduler slot freed before the TTS render, so `activeGenerations()` was 0
      during the heaviest Metal window; now includes `SoulVoiceService.activeSyntheses`.
- [x] **Offline engine A/B** — run 2026-08-25 (no game running, so contention-free
      numbers). Piper (en_US-lessac-medium, CPU): ~0.97s/line after a 3s first-load.
      Dreamsleeve warm (Qwen3 1.7B, Metal): ~2.6s/line for ~3.7s of audio (~1.5x
      realtime) — the in-game 8-10s was multi-sentence replies + GPU contention, both now
      addressed (streaming + governor floor). Piper is ~2.6x faster and zero-GPU but a
      generic voice. Samples: `voices/ab-test/{piper,dreamsleeve}-line{1..3}.wav`.
      In-game Piper trial needs NO code: settings.json5 `soulVoiceEngine: "piper"`,
      `soulVoicePiperBinary: "~/.local/bin/piper"` (expanded), `soulVoiceModel:
      voices/ab-test/piper-voice/en_US-lessac-medium.onnx`.
- [ ] Hosted-API offload remains separately backlogged behind the credential security review.

## Multiplayer Voice Muting (Backlogged 2026-08-25)

Voiced-line category muting shipped global-only (settings.json5 mask, `ConfigureVoiceCategoriesScreen`
via the Voice row "Adv…" chip in Bot Control). Deferred multiplayer half:

- [x] **Per-player mute masks** (✅ 1.1.203, 2026-09-04) — on a dedicated server the current design mutes for everyone
      (`playSoundFromEntity` broadcasts). Fix: replace the broadcast in `BotDialoguePlayer.playSound`
      with a per-recipient send loop, sync each Frens client's mask via a small C2S payload, and
      consult it in `VoiceLineMuteService.isMuted(category, viewer)` — the `viewer` param is already
      threaded as the seam. Settings.json5 mask becomes the server-admin baseline.
- [x] **Fix the stubbed config sync** (✅ 1.1.203, 2026-09-04) — prerequisite: `configNetworkManager` /
      `ConfigJsonUtil.configToJson/applyConfigJson` are compile-time no-op stubs, so ALL global
      config (including the existing Voice toggle) is effectively single-player-only today.

## Separate Version Ports (Backlogged — Low Priority)

Shelved 2026-05-06. Not on roadmap unless a long-term contributor commits to maintaining a parallel branch.

- [ ] **1.21.1 backport** — A user requested 1.21.1 compatibility. Real port, not a config tweak: ~10 point releases of yarn/registry/component drift between 1.21.1 and 1.21.11. High-risk surfaces are item components (`DataComponentTypes` reworked in 1.21.2), networking payload codecs, fake-player/`ServerPlayerEntity` constructor signatures, screen handler XP-sync, and any post-1.21.1 vanilla content references (e.g. rideable Nautilus). Approach if revived: fork `backport/1.21.1` branch, freeze scope to core companion + skills (no fortify-village), accept it will lag main. Alternative is Stonecutter/preprocessor multi-version build — more upfront work, sustainable long-term. Don't start without a contributor signed up to maintain it.

## LoadGoverner Improvements (DONE 2026-08-24 — separate repo, `~/pontus/LoadGoverner`, v0.2.0)

Reworked per `~/pontus/LoadGoverner/docs/AUDIT-2026-08-23.md` (commits `b42c9cf`..`aa29f83`);
Bradley had already re-enabled the governor himself. Frens side: `SoulRuntime.activeGenerations()`
(1.1.159, commit `76d4a2d`) — stable cross-mod probe signature, do not rename.

- [x] Re-enable + loud disabled-state warning; state-change-only telemetry; real DEBUG log level (S)
- [x] Escalation hygiene: consecutive-check confirmation (default 2) + single-spike `maxMspt()`
      fast path (`spikeMspt=400` → straight to stage 3) (M)
- [x] Soul-pipeline coordination: transient stage floor (default stage 2, 100-tick hold) while a
      Frens soul generation is queued/active, via reflection probe `FrensSoulProbe` (L)

## LLM Integration (Future)

- [x] Phase 1+: Core architecture, toggles, identity & memory, routing, performance, social awareness, integration & testing — stale (triage 2026-10-19): legacy stack deleted 1.1.251 080160e4; souls runtime covers it
