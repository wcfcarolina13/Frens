# Legacy Release Notes (Archived)

Changelog entries from the 1.0.x line (pre-2025). Current history lives in `changelog.md`.

---

## Changelog v1.0.4-release+1.20.6
- Updated codebase for 1.20.6 compatibility.
- Optimized codebase by removing redundant code and unused imports.
- Previous version `1.0.4-beta-1` fixed server-sided compatibility.

## Changelog v1.0.3-alpha-2-hotfix-1
- Introduced server-sided compatibility for training mode.
- Play mode failing to connect to ollama server (work in progress).
- Fixed `removeArmor` command.
- Planned decision-making updates.

## Changelog v1.0.3-alpha-2
- Updated Q-table storage format (not compatible with prior version).
- Added “risk taking” mechanism to improve training efficiency.
- Expanded environment triggers for training (lava, fall hazards, sculk).
- Replaced block scanning with DLS algorithm for optimization.

## Changelog v1.0.3-alpha-1
- Added Mineflayer-style interaction commands (`use-key`, `release-all-keys`, `look`).
- Added detection/utilities (`detectDangerZone`, `getHotBarItems`, `getSelectedItem`, `getHungerLevel`, `getOxygenLevel`).
- Added armor helpers (`equipArmor`, `removeArmor` WIP).
- Updated spawn command: `/bot spawn <bot> <training|play>`.
- Multiplayer support considered (server-side only), reinforcement learning triggers added for hostile detection.
- Setup steps for ollama models (`nomic-embed-text`, `llama3.2`) and config reset guidance.
- Early reinforcement learning outline and YouTube demo link.
