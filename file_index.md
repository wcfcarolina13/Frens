# AI-Player File Index

Last updated: 2025-11-19. Navigation map for common code and docs.

## Docs
- `README.md` — command guide for using the mod.
- `TODO.md` — active tasks (pending only).
- `changelog.md` — history and reasoning.
- `AGENT_RULES.md` — rules for LLM agents.
- `CUSTOM_PROVIDERS.md` — running with non-OpenAI-compatible providers.
- `DOCS_INDEX.md` — doc map/status.
- `CRAFTING_CHEATSHEET.md` — crafting quick reference.
- `archive/legacy_changelogs.md` — pre-2025 release notes.
- `archive/docs/INDEX.md` — archived planning notes and reports (do not update).

## Root / Build
- `build.gradle`, `settings.gradle`, `gradle.properties`, `gradlew*`, `gradle/` — build configuration.
- `LICENSE`, `eula.txt`, `Logs-Prism/`, `run/`, `tmp_collect*.json` — licensing, runtime artifacts, temp data.

## Entry Points
- `src/main/java/net/shasankp000/AIPlayer.java` — main mod entry.
- `src/main/java/net/shasankp000/AIPlayerClient.java` — client-side entry.
- `src/main/java/net/shasankp000/AIPlayerDataGenerator.java` — data generation.
- `src/main/java/net/shasankp000/CommandUtils.java`, `EntityUtil.java` — shared helpers.

## Commands
- `Commands/modCommandRegistry.java` — command registration.
- `Commands/configCommand.java` — config command handling.

## Skills
- `GameAI/skills/impl/CollectDirtSkill.java` — dirt/mining/ascent/descent logic.
- `GameAI/skills/impl/StripMineSkill.java` — horizontal tunneling.
- `GameAI/skills/impl/DropSweepSkill.java`, `DirtShovelSkill.java`, `MiningSkill.java` — other core skills.
- `GameAI/skills/support/MiningHazardDetector.java`, `TorchPlacer.java` — hazard detection and lighting.
- `GameAI/skills/Skill*.java` — interfaces and execution context classes.

## Services
- `GameAI/services/TaskService.java`, `SkillResumeService.java`, `WorkDirectionService.java` — task lifecycle, resume, direction persistence.
- `GameAI/services/MovementService.java`, `BotControlApplier.java` — movement helpers.
- `GameAI/services/BotInventoryStorageService.java`, `BotPersistenceService.java`, `InventoryAccessPolicy.java` — persistence and inventory access.
- `GameAI/services/HungerService.java`, `BotTargetingService.java` — survival and combat targeting.

## Reinforcement Learning & Event Handling
- `GameAI/BotEventHandler.java`, `GameAI/BotActions.java` — core behavior orchestration and low-level actions.
- `GameAI/RLAgent.java`, `GameAI/State*.java`, `GameAI/ActionHoldTracker.java` — RL agent and state/action tracking.
- `GameAI/DropSweeper.java` — drop collection utility.
- `Entity/AutoFaceEntity.java`, `Entity/RespawnHandler.java`, `Entity/LookController.java`, `Entity/RayCasting.java` — entity helpers and main behavior loop.

## LLM & Chat
- `GameAI/llm/*` — orchestration, job tracking, status reporting, and memory store for LLM-driven commands.
- `ChatUtils/*` — chat parsing, NLP models (BERT/OpenNLP), decision routing, and RAG helpers.
- `FunctionCaller/*` — tool registry, verification, and dispatch for LLM tool calls.

## Knowledge & Data
- `GameAI/Knowledge/*` — block/ore knowledge bases and retrieval.
- `Database/*` — SQLite vector DB integration (current). Legacy `OldSQLiteDB.java` remains but is deprecated.

## Danger Detection
- `DangerZoneDetector/*` — lava/cliff detection and related safety checks.

## Configuration / Providers
- `FilingSystem/*` — config handling, provider/model selection utilities.
- `CUSTOM_PROVIDERS.md` — doc for non-OpenAI-compatible providers.
