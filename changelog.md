
- **Inventory GUI refactor:** Removed redundant `BotInventoryScreen.java`, introduced the canonical `BotInventoryScreen` in `GraphicalUserInterface`, and updated `AIPlayerClient` to register it as the bot inventory UI.
- **Build stability:** Fixed duplicate-class and rendering-related compilation errors that were blocking builds. The bot inventory currently uses a solid background as a temporary workaround for the previous texture issue.
- **Docs:** Updated `file_index.md` to reflect the current project structure and to include the new `BotInventoryScreen` location.
- **Mining safety:** Bots now detect when they spawn or tunnel into solid blocks, automatically dig themselves out with the best tool available, and broadcast "I'm suffocating!" if they only have bare hands so players can teleport them to safety. The experimental stair-step planner has been disabled for now so teleport-off mining proceeds with the proven approach while we revisit depth-targeting later.
