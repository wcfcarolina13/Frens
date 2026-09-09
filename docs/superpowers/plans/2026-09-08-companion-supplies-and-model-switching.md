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

1. Chest request policy and pending state: pure allowlist/reserve rules, exact request fingerprints, owner binding, expiry, rejection cooldown and replay tests. No live withdrawals yet.
2. Chest adapter and authorization persistence: new server request/transfer service, authorization store, dedicated command registrar, initialization hook and changelog. Reuse clickable chat choices rather than adding an unnecessary screen. Verify current Fabric lock APIs before implementation.
3. Close existing bypasses: ToolProvisionService raw withdrawals, ChestStoreService registered-tool withdrawals, and BotMutualAidService chest-food access route through the shared policy; integration tests cover simultaneous bots and changed stock. Restrict automatic supply discovery to supported chest containers.
4. Storage-room walkthrough: add a need-driven proximity trigger independent of the idle weapon/axe early exit, enabling missing armor requests while following. Confirm following resumes without stealing task control.
5. Helper request policy: eligibility, exclusive reservation, expiry/cancellation, one outstanding request and no recursive recruitment, with pure tests.
6. Helper execution and fallback integration: one-tree skill, safe return and atomic transfer; replace the doomed no-axe dispatch and share the capability check with the other resource-woodcut entry. Do not wrap SkillManager.runSkill in TaskService.runAmbient: both acquire task tickets.

Before structural refactors of files over 300 lines, inspect and remove proven dead code in a separate cleanup commit. Do not combine the phases or deploy a partial chest feature that leaves an automatic withdrawal bypass.
