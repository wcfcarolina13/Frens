# AI-Player File Index

This document provides an index of all the files in the AI-Player-checkpoint project, with a short description of each file and its purpose.

## Root Directory

-   `.gitattributes`: Specifies attributes for pathnames in Git. Enforces line endings for script files.
-   `.gitignore`: Specifies intentionally untracked files to ignore. Used by Git to exclude build artifacts, IDE configs, and OS-specific files.
-   `build.gradle`: Gradle build script for the project. Defines project dependencies, plugins, and build tasks. Influences the entire build process.
-   `changelog.md`: A log of changes for each version of the project. Used to track progress and new features.
-   `CUSTOM_PROVIDERS.md`: Documentation on how to use custom OpenAI-compatible providers.
-   `eula.txt`: End User License Agreement for Minecraft. Must be set to `true` to run a Minecraft server.
-   `GEMINI.md`: Contains instructions and context for the Gemini CLI agent.
-   `gradle.properties`: Project-specific Gradle settings. Defines versions for Minecraft, mappings, loader, and the mod itself.
-   `gradlew`: Unix/Linux shell script to run the Gradle wrapper. Allows building the project without installing Gradle.
-   `gradlew.bat`: Windows batch script to run the Gradle wrapper. Allows building the project without installing Gradle.
-   `LICENSE`: MIT License file for the project.
-   `old_changelogs.md`: Contains changelogs for older versions of the project.
-   `README.md`: Provides a guide to the commands available in the mod.
-   `settings.gradle`: Gradle settings script. Includes plugin management repositories.
-   `TODO.md`: A list of tasks and features to be implemented in the project.

## Gradle

-   `gradle/wrapper/gradle-wrapper.jar`: The Gradle wrapper JAR file.
-   `gradle/wrapper/gradle-wrapper.properties`: The properties file for the Gradle wrapper, specifying the Gradle version and other settings.

## Source Code

### net.shasankp000

-   `AIPlayer.java`: The main class for the AI Player mod.
-   `AIPlayerClient.java`: The main client-side class for the AI Player mod.
-   `AIPlayerDataGenerator.java`: A class for generating data for the AI player.
-   `CommandUtils.java`: A utility class for commands.
-   `EntityUtil.java`: A utility class for working with entities.

#### AI

-   `GameAI/RLAgent.java`: The reinforcement learning agent. It implements the Q-learning algorithm to enable the bot to learn from its actions.
-   `GameAI/BotActions.java`: A minimal action executor that directly manipulates the server-side player, allowing training steps to take effect without the Carpet mod.
-   `GameAI/BotEventHandler.java`: The main event handler for the bot. It orchestrates the bot's behavior based on environmental triggers and game state.
-   `GameAI/StateActions.java`: Defines the possible actions the bot can take in the game.
-   `GameAI/DropSweeper.java`: A utility that walks the bot over to nearby item entities to gather drops from recent tasks.
-   `GameAI/State.java`: Represents the state of the bot and its environment at a given moment. Used as input for the reinforcement learning algorithm.
-   `GameAI/ActionHoldTracker.java`: Tracks repeated invocations of movement/mining actions to differentiate between sustained key holds and single taps for reward calculation.
-   `GameAI/Knowledge/OreYLevelKnowledge.java`: A knowledge base that stores information about the optimal Y-levels for finding different ores.
-   `GameAI/Knowledge/BlockMetadata.java`: A data class representing the metadata for a single block.
-   `GameAI/Knowledge/BlockKnowledgeRetriever.java`: A utility to look up and suggest block information from the `BlockKnowledgeBase`.
-   `GameAI/Knowledge/BlockKnowledgeBase.java`: A knowledge base that stores metadata about blocks, such as hardness, preferred tools, and drops.
-   `GameAI/skills/impl/CollectDirtSkill.java`: A skill for collecting dirt-like blocks.
-   `GameAI/skills/impl/DropSweepSkill.java`: A standalone drop collection skill that uses the `DropSweeper` utility.
-   `GameAI/skills/impl/DirtShovelSkill.java`: A skill for using a shovel to dig dirt-like blocks.
-   `GameAI/skills/impl/MiningSkill.java`: A skill for collecting stone-like blocks using pickaxes.
-   `GameAI/skills/ExplorationMovePolicy.java`: Tracks the historical success rate of exploratory reposition steps to bias movement toward offsets that previously worked.
-   `GameAI/skills/Skill.java`: An interface for all skills that the bot can perform.
-   `GameAI/skills/DirtNavigationPolicy.java`: A lightweight reinforcement tracker for dirt navigation outcomes.
-   `GameAI/skills/SkillManager.java`: Manages the registration and execution of skills.
-   `GameAI/skills/SkillExecutionResult.java`: A data class representing the result of a skill execution, including success status and a message.
-   `GameAI/skills/SkillContext.java`: A data class that holds the context for a skill execution, including the bot's command source and shared state.
-   `GameAI/services/BotInventoryStorageService.java`: A service to save and load the bot's inventory to and from NBT files.
-   `GameAI/services/BotPersistenceService.java`: Delegates persistence of fake player aliases to vanilla's `PlayerManager`, avoiding the need to chase internal API changes.
-   `GameAI/services/SkillResumeService.java`: Manages the pausing and resuming of skills, for example, when a bot dies and needs to continue its task after respawning.
-   `GameAI/services/BotTargetingService.java`: A shared helper for resolving fake player command targets.
-   `GameAI/services/MovementService.java`: A shared navigation helper that plans direct, wading, or bridge-assisted approaches for the bot.
-   `GameAI/services/InventoryAccessPolicy.java`: A service to check if a player has permission to open a bot's inventory.
-   `GameAI/services/TaskService.java`: A global task lifecycle tracker that replaces ad-hoc session flags for skills.

#### Chat

-   `ChatUtils/ChatContextManager.java`: A class for managing the chat context.
-   `ChatUtils/ClarificationState.java`: A class representing the clarification state in a conversation.
-   `ChatUtils/ConfirmationState.java`: A class representing the confirmation state in a conversation.
-   `ChatUtils/ChatUtils.java`: A utility class for chat-related functions.
-   `ChatUtils/NLPProcessor.java`: A class for processing natural language.
-   `ChatUtils/BERTModel/BertModelManager.java`: A class for managing the BERT model.
-   `ChatUtils/BERTModel/BertTranslator.java`: A class for translating text for the BERT model.
-   `ChatUtils/DecisionResolver/DecisionResolver.java`: A class for resolving decisions in a conversation.
-   `ChatUtils/CART/CartClassifier.java`: A class for classifying text using a CART model.
-   `ChatUtils/CART/TreeNodeDeserializer.java`: A class for deserializing a tree node.
-   `ChatUtils/PreProcessing/OpenNLPProcessor.java`: A class for processing text using OpenNLP.
-   `ChatUtils/PreProcessing/NLPModelSetup.java`: A class for setting up the NLP models.
-   `ChatUtils/Helper/RAG2.java`: A class for the second version of the RAG model.
-   `ChatUtils/Helper/OldRAGImplementation.java`: The old implementation of the RAG model.
-   `ChatUtils/Helper/JsonUtils.java`: A utility class for working with JSON.
-   `ChatUtils/Helper/helperMethods.java`: A class containing helper methods.
-   `ChatUtils/LIDSNetModel/LIDSNetTranslator.java`: A class for translating text for the LIDSNet model.
-   `ChatUtils/LIDSNetModel/LIDSNetModelManager.java`: A class for managing the LIDSNet model.

#### Commands

-   `Commands/modCommandRegistry.java`: A class for registering the mod's commands.
-   `Commands/configCommand.java`: The configuration command for the mod.

#### Danger

-   `DangerZoneDetector/CliffDetector.java`: A class for detecting cliffs.
-   `DangerZoneDetector/DangerZoneDetector.java`: A class for detecting danger zones.
-   `DangerZoneDetector/LavaDetector.java`: A class for detecting lava.

#### Database

-   `Database/SQLiteDB.java`: The main class for interacting with the SQLite database. It handles database creation, memory storage, and retrieval of relevant memories using vector similarity.
-   `Database/VectorExtensionHelper.java`: A helper class to download and load the `sqlite-vec` and `sqlite-vss` extensions for SQLite, enabling vector search capabilities.
-   `Database/QTable.java`: Represents the Q-table for reinforcement learning. It stores a map of state-action pairs to Q-entries.
-   `Database/StateActionTransition.java`: Represents a complex state-action-nextState-reward structure for the Q-learning algorithm.
-   `Database/QTableExporter.java`: A utility to export the Q-table from a binary file to a human-readable JSON format.
-   `Database/OldSQLiteDB.java`: An older, now likely deprecated, implementation for interacting with an SQLite database. It handles creation of `conversations` and `events` tables.
-   `Database/StateActionPair.java`: A wrapper class to represent a single state-action pair, used as a key in the Q-table.
-   `Database/QEntry.java`: A simple data class representing an entry in the Q-table, containing a Q-value and the next state.
-   `Database/QTableStorage.java`: Manages the storage of the Q-table, including loading, saving, and migrating from legacy formats.

#### Entity

-   `Entity/AutoFaceEntity.java`: A class that makes the bot automatically face the closest entity. It also contains the main behavior loop for the bot.
-   `Entity/RayCasting.java`: A utility class for performing ray casting to detect blocks and entities.
-   `Entity/LookController.java`: A utility class for controlling the bot's look direction.
-   `Entity/RespawnHandler.java`: A class that handles the bot's respawn logic.
-   `Entity/FaceClosestEntity.java`: A utility class for making the bot face the closest entity.
-   `Entity/EntityDetails.java`: A data class representing the details of an entity, such as its name, position, and whether it is hostile.
-   `Entity/createFakePlayer.java`: A utility class for creating a fake player entity in the game.

#### Exceptions

-   `Exception/ollamaNotReachableException.java`: An exception for when the Ollama server is not reachable.
-   `Exception/intentMisclassification.java`: An exception for when an intent is misclassified.

#### FilingSystem

-   `FilingSystem/ManualConfig.java`: Handles all mod configuration settings using a plain JSON file, replacing the owo-lib config wrapper.
-   `FilingSystem/LLMClientFactory.java`: A factory class for creating `LLMClient` instances based on the selected provider.
-   `FilingSystem/getLanguageModels.java`: A utility class for fetching the list of available language models from the Ollama server.
-   `FilingSystem/ServerConfigUtil.java`: A utility class for updating the selected language model in the config file on the server.

#### FunctionCaller

-   `FunctionCaller/OutputVerifier.java`: An interface for verifying the output of a function call.
-   `FunctionCaller/SharedStateUtils.java`: A utility class for getting and setting values in the shared state map.
-   `FunctionCaller/FunctionCallerV2.java`: The main class for the function calling system. It uses a large language model to determine which tool or sequence of tools to use to accomplish a task.
-   `FunctionCaller/ToolRegistry.java`: A registry of all the tools available to the bot.
-   `FunctionCaller/ToolVerifiers.java`: A registry of verifiers for tool outputs.
-   `FunctionCaller/Tool.java`: A data class representing a tool that the bot can use.
-   `FunctionCaller/ToolStateUpdater.java`: A functional interface for updating the shared state after a tool has been used.

#### GUI

-   `GraphicalUserInterface/ReasoningLogScreen.java`: A screen that displays the bot's chain-of-thought reasoning.
-   `GraphicalUserInterface/BotInventoryScreen.java`: A screen for viewing the bot's inventory.
-   `GraphicalUserInterface/ConfigManager.java`: The main configuration screen for the mod. It allows the user to select the language model and access other configuration screens.
-   `GraphicalUserInterface/APIKeysScreen.java`: A screen for managing API keys for various AI services.
-   `GraphicalUserInterface/Widgets/DropdownMenuWidget.java`: A custom dropdown menu widget for selecting from a list of options.

#### LauncherDetection

-   `LauncherDetection/LauncherEnvironment.java`: A class for detecting the launcher environment.

#### Mixin

-   `mixin/ExampleMixin.java`: An example Mixin class that injects code into the `MinecraftServer` class.

#### Network

-   `Network/ConfigJsonUtil.java`: A utility class for converting the mod's configuration to a JSON string.
-   `Network/OpenConfigPayload.java`: A custom payload for sending the mod's configuration from the server to the client.
-   `Network/SaveAPIKeyPayload.java`: A custom payload for sending an API key from the client to the server.
-   `Network/FakeClientConnection.java`: A minimal `ClientConnection` used for server-controlled fake players, mirroring Carpet mod's behavior.
-   `Network/SaveConfigPayload.java`: A custom payload for sending the selected language model from the client to the server.
-   `Network/StringCodec.java`: A simple packet codec for encoding and decoding strings with a maximum length.
-   `Network/SaveCustomProviderPayload.java`: A custom payload for sending custom provider settings from the client to the server.
-   `Network/configNetworkManager.java`: Manages the network communication for configuration-related packets.

#### Ollama

-   `OllamaClient/ollamaClient.java`: A client for the Ollama server.

#### Overlay

-   `Overlay/ThinkingStateManager.java`: A class for managing the thinking state manager.

#### PacketHandler

-   `PacketHandler/InputPacketHandler.java`: A class for handling input packets.

#### PathFinding

-   `PathFinding/Segment.java`: A data class representing a segment of a path, including start and end points, and whether a jump is required.
-   `PathFinding/PathTracer.java`: Executes a path found by the `PathFinder`, moving the bot along the path in segments.
-   `PathFinding/PathFinder.java`: Implements the A* pathfinding algorithm to find a path between two points.
-   `PathFinding/ChartPathToBlock.java`: A utility class for charting a path to a specific block.
-   `PathFinding/GoTo.java`: The main class for pathfinding. It uses the `PathFinder` and `PathTracer` to move the bot to a specific location.

#### Player

-   `PlayerUtils/blockDetectionUnit.java`: A utility class for detecting blocks.
-   `PlayerUtils/getHealth.java`: A utility class for getting the player's health.
-   `PlayerUtils/turnTool.java`: A utility class for turning a tool.
-   `PlayerUtils/getArmorStack.java`: A utility class for getting the player's armor stack.
-   `PlayerUtils/hotBarUtils.java`: A utility class for working with the hotbar.
-   `PlayerUtils/ResourceEvaluator.java`: A class for evaluating resources.
-   `PlayerUtils/getPlayerOxygen.java`: A utility class for getting the player's oxygen level.
-   `PlayerUtils/SelectedItemDetails.java`: A class for holding details about the selected item.
-   `PlayerUtils/BlockNameNormalizer.java`: A class for normalizing block names.
-   `PlayerUtils/CombatInventoryManager.java`: A class for managing the inventory during combat.
-   `PlayerUtils/ToolSelector.java`: A class for selecting the best tool for a given block.
-   `PlayerUtils/InternalMap.java`: A class for representing the internal map of the world.
-   `PlayerUtils/getPlayerHunger.java`: A utility class for getting the player's hunger level.
-   `PlayerUtils/armorUtils.java`: A utility class for working with armor.
-   `PlayerUtils/getOffHandStack.java`: A utility class for getting the player's off-hand stack.
-   `PlayerUtils/getFrostLevel.java`: A utility class for getting the player's frost level.
-   `PlayerUtils/MiningTool.java`: A class representing a mining tool.
-   `PlayerUtils/BlockDistanceLimitedSearch.java`: A class for searching for blocks within a limited distance.
-   `PlayerUtils/ThreatDetector.java`: A class for detecting threats to the player.

#### ServiceLLMClients

-   `ServiceLLMClients/ClaudeModelFetcher.java`: A class for fetching available models from the Anthropic (Claude) API.
-   `ServiceLLMClients/GrokModelFetcher.java`: A class for fetching available models from the xAI (Grok) API.
-   `ServiceLLMClients/GenericOpenAIModelFetcher.java`: A model fetcher for generic OpenAI-compatible APIs.
-   `ServiceLLMClients/GeminiClient.java`: A client for interacting with the Google Gemini API.
-   `ServiceLLMClients/GeminiModelFetcher.java`: A class for fetching available models from the Google Gemini API.
-   `ServiceLLMClients/OpenAIClient.java`: A client for interacting with the OpenAI API.
-   `ServiceLLMClients/LLMClient.java`: An interface for all Large Language Model clients.
-   `ServiceLLMClients/AnthropicClient.java`: A client for interacting with the Anthropic (Claude) API.
-   `ServiceLLMClients/GrokClient.java`: A client for interacting with the xAI (Grok) API.
-   `ServiceLLMClients/LLMServiceHandler.java`: The main handler for all LLM services. It routes intents to the appropriate function (RAG, FunctionCaller, etc.).
-   `ServiceLLMClients/GenericOpenAIClient.java`: A generic OpenAI-compatible client that supports custom API base URLs.
-   `ServiceLLMClients/OpenAIModelFetcher.java`: A class for fetching available models from the OpenAI API.
-   `ServiceLLMClients/ModelFetcher.java`: An interface for fetching a list of available models from a language model provider.

#### UI

-   `ui/BotMainInventoryView.java`: A 27-slot `Inventory` wrapper that maps to a bot's main inventory (slots 9-35) for a chest-like UI.
-   `ui/BotInventoryAccess.java`: A server-side helper to open the bot's inventory view and enforce access policies.

#### Web

-   `WebSearch/WebSearchTool.java`: A tool for performing web searches.
-   `WebSearch/AISearchConfig.java`: The configuration for the AI search.

#### World

-   `WorldUitls/GetTime.java`: A utility class for getting the world time.
-   `WorldUitls/isBlockItem.java`: A utility class for checking if an item is a block.
-   `WorldUitls/isFoodItem.java`: A utility class for checking if an item is food.

## Resources

-   `src/main/resources/logback.xml`: The configuration file for the Logback logging framework.
-   `src/main/resources/fabric.mod.json`: The main configuration file for the Fabric mod, containing metadata such as the mod ID, version, and entry points.
-   `src/main/resources/ai-player.mixins.json`: The configuration file for the Mixin framework, used to apply custom modifications to the Minecraft source code.
-   `src/main/resources/assets/ai-player/icon.png`: The icon for the AI Player mod.
-   `src/main/resources/block_metadata.json`: A JSON file containing metadata about blocks, such as their hardness and required tools.

## Other Files

-   `.DS_Store`: macOS specific file that stores custom attributes of its containing folder.
