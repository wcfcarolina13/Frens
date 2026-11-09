<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Modes

[All Guide topics](Guide.md)

## Quest Mode

Layman progression path: recruit first, then unlock more capabilities.

*Shown only while Questing world mode is on.*

- Designed for survival progression and narrative onboarding.
- Dialogue topics help explain what is unlocked next.
- Backlink: Bot Controls panel includes Spawn Mode and related settings.

**Command:** UI-driven mode

**Where:** Guide + Dialogue topics

## Delegate Mode Setup

Admins can permit specific non-operator players to choose world mode.

*Operators and the host only.*

*Shown only while Questing world mode is on.*

- Use mode_access allow/revoke to manage delegated players.
- Operators can always choose mode. Delegates are world-specific.
- Clear removes all delegates for the current world.

**Command:** `/bot recruit mode_access allow <player> | revoke <player> | clear`

**Where:** No keybind by default

## Direct Mode

Spawn/use bots with broad capabilities immediately.

*Operators and the host only.*

- Best for testing, admin workflows, and sandbox play.
- No recruitment progression needed.
- Backlink: use Bot Controls to switch Spawn Mode and tune behavior.

**Command:** `/bot spawn <alias>`

**Where:** No keybind by default
