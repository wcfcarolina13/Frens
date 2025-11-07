# Changelog

This file will be used to track changes and progress in the project.

---
## Messages

### 2025-11-07

I asked Jake to mine from the surface, I notice eventually he just teleports down into caves. That's not realistic or ideal. I'd rather he first use appropriate tools to get to the stone underneath, so he'll switch to shoveling until he hits stone and then proceeds to mine.

saying in chat "hey Jake, collect 10 dirt" resulted in nothing Jake died and asked if he should resume his job, but it looks like saying 'yes' didn't actually result in him resuming, even though it said he was in the chat.

'/bot skill mine' would result in collection of all blocks in that family, but '/bot skill mine stone' would result in going for just that block, with any blocks in the way not counting toward the total. However, the bot should still mine and dig through blocks that are in the way until it reaches its goal. An additional parameter will give it a number of 'fails' before it should give up looking for the specific one (useful for looking for rare items) Bot should have an awareness of the most likely levels to find the different rarer stones (e.g., it should know that diamonds are much deeper underground)

Bot should pause and ask to continue before collecting more rare resources (in case the player wants to handle the discovery themselves)

Bot should pause its job and ask to continue if it finds a precipice or sudden large underground opening (like a cave or huge drop-off) any mob spawner, or any type of dungeon (which would include the ancient cities and etc.)

'Jake, what resources do you have?' resulted in the LLM doing a web search and then mentioning a Savanna memory. Are we in a savanna currently, or is it stuck in an old memory it stored? Persistent world memories and memory of terrain would be more realistic. Also, their personalities still feel overly stilted like they're trying to be Clippy or something. Were you able to alter the personality prompts?

### 2025-11-07 - Progress Update

Here's where we stand:

1.  **Project Setup & Review**:
    *   Reviewed `git log`, `gemini_report_3.md`, `changelog.md`, and `run/logs/latest.log`.
    *   `gemini_report_3.md` reformatted into a checklist.
    *   `changelog.md` updated with your last message.

2.  **"Realistic Mining" (digging down instead of teleporting)**:
    *   Added `isOnSurface` helper method to `CollectDirtSkill.java`.
    *   Modified `DirtShovelSkill.java`'s `perform` method and `gatherCandidateDirt` method to accept a `diggingDown` parameter.
    *   Modified `CollectDirtSkill.java` to pass the `isOnSurface` status to `DirtShovelSkill.perform`.
    *   Modified `DirtShovelSkill.gatherCandidateDirt` to prioritize blocks directly below the bot if `diggingDown` is true.
    *   Marked as in progress in `gemini_report_3.md`.

3.  **"Fix 'collect dirt' command"**:
    *   Identified that the `FunctionCallerV2` was missing a generic way to call skills like `collect_dirt` or `mining`.
    *   Added a generic `runSkill` tool to `ToolRegistry.java`.
    *   Implemented the `runSkill` method in `FunctionCallerV2.Tools` to parse arguments and call `SkillManager.runSkill`.
    *   Updated `FunctionCallerV2.callFunction` to map to the new `Tools.runSkill` method.
    *   Updated `FunctionCallerV2.parseOutputValues` and `functionStateKeyMap` to handle the output of `runSkill`.
    *   Marked as in progress in `gemini_report_3.md`.

4.  **"Fix skill resumption"**:
    *   Identified a mismatch in how `PendingSkill` was stored and retrieved in `SkillResumeService.java`.
    *   Modified `SkillResumeService.handleDeath` to correctly store the `PendingSkill` using the `UUID` of the player who is expected to respond.
    *   Marked as in progress in `gemini_report_3.md`.

5.  **Refine `/bot skill mine` (block family, specific block, fails parameter)**:
    *   Attempted to modify `MiningSkill.java` and `CollectDirtSkill.java` to handle `targetBlockIds` and `maxFails` as parameters.
    *   Encountered an error due to a duplicate field declaration in `CollectDirtSkill.java` and an incorrect approach to modifying the constructor.
    *   This is the next immediate task to resolve.

### 2025-11-07 - Compilation Fixes (Round 2)

*   **`CollectDirtSkill.java`**: Corrected `player.getWorld()` to `player.getEntityWorld()` in `isOnSurface` method.
*   **`DirtShovelSkill.java`**: Corrected `execute` method signature to match `Skill` interface and ensured `diggingDown` is passed via `SkillContext` parameters.
*   **`MiningSkill.java`**: Corrected `super` call in constructor to match `CollectDirtSkill` constructor signature.
*   **`SkillResumeService.java`**: Reverted `PENDING_BY_RESPONDER` map key type to `Object` to resolve type incompatibility.
*   **`FunctionCallerV2.java`**: Added `import net.minecraft.util.Identifier;` to resolve `Identifier` symbol errors.
*   **`OreYLevelKnowledge.java`**: Added `import java.util.Optional;` to resolve `Optional` symbol error.
*   **`CollectDirtSkill.java`**: Removed `isOnSurface(loopPlayer)` from `shovelSkill.perform` call as `diggingDown` is now passed via `SkillContext`.

### 2025-11-07 - Bug Fixes

*   **Chat LLM Unresponsive**: Added logic to `AIPlayer.java` to process chat messages as commands for the bot, enabling it to respond to natural language commands.
*   **/bot inventory command**: Added logging to `modCommandRegistry.java` to help diagnose why the command might fail to produce results.
*   **/bot skill mining**: Fixed argument parsing for skills in `modCommandRegistry.java` to correctly handle block types and counts, ensuring the bot mines the correct materials.