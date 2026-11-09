<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Admin

[All Guide topics](Guide.md)

## World Mode Reset

Admin command to re-trigger the world mode selection (Questing vs Admin) welcome screen.

*Operators and the host only.*

*Shown only while Questing world mode is on.*

- Useful when a recreated world inherits the old mode-selection state (save-path collision) and the welcome screen doesn't reappear.
- Run /bot worldmode reset. You'll get a chat confirmation.
- Then press the open-roster hotkey (or rejoin) and the mode selection screen will open again.
- Requires bot command permission (admin).

**Command:** `/bot worldmode reset`

## Learning Mode (Admin)

Operator-only demonstration capture used to record player examples for later bot-control tuning.

- Admin entries like Learning Status / Start / Stop control a recording session, not normal companion progression.
- Captured traces can include movement, camera, interactions, and local context for analysis.
- Use this for testing and tuning. See the Roadmap topic for the larger ML / LLM direction.

**Command:** `/bot learn status | arm | start | stop | list | report`

**Where:** No keybind by default
