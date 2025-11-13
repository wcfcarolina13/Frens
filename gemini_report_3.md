- ## Summary of Changes

- **Removed Duplicate Class:** Deleted the redundant `AIPlayerClient.java` file located in the `GraphicalUserInterface` directory. This file was causing a "duplicate class" compilation error.

- **Created `BotInventoryScreen`:** Introduced a new `BotInventoryScreen` class to manage the graphical user interface for the bot's inventory. This class extends `HandledScreen` and is responsible for rendering the inventory screen.

- **Updated `AIPlayerClient`:** Modified the main `AIPlayerClient.java` file to register and use the new `BotInventoryScreen`. This ensures that the correct screen is displayed when a player interacts with the bot's inventory.

- **Temporary Background:** As a temporary workaround for a compilation issue with the `drawTexture` method, the inventory screen's background is now a solid color. The original intention was to use a texture, but this was causing a build failure. The issue with `drawTexture` can be revisited in a future update.

- **Successful Build:** The project now compiles successfully, and the bot's inventory screen is functional, albeit with a temporary solid-colored background.

- ## Updated `file_index.md`

- **Restructured the file:** The `file_index.md` file has been restructured to be more organized. The file now has the following sections:
    - Root Directory
    - Gradle
    - Source Code
        - net.shasankp000
            - AI
            - Chat
            - Commands
            - Danger
            - Database
            - Entity
            - Exceptions
            - FilingSystem
            - FunctionCaller
            - GameAI
            - GUI
            - LauncherDetection
            - Mixin
            - Network
            - Ollama
            - Overlay
            - PacketHandler
            - PathFinding
            - Player
            - ServiceLLMClients
            - UI
            - Utils
            - Web
            - World
    - Resources
    - Other Files
- **Added missing files:** The following files have been added to the `file_index.md` file:
    - `src/main/java/net/shasankp000/GraphicalUserInterface/BotInventoryScreen.java`
- **Updated descriptions:** The descriptions of the files have been updated to be more accurate.
