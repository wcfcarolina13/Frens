# Changelog & History

Historical record and reasoning. `TODO.md` is the source of truth for what’s next.

## 2025-11-19
- Hardened suffocation recovery: multiple iterations to detect head/feet blockage before damage ticks, throttle alerts, and mine with the correct tool rather than instant breaks. Spawn-in-block checks now run shortly after registration.
- Upward stairs (ascent) refinements: walk-and-jump algorithm with headroom increases, issuer-facing direction lock, button-based direction overrides, and explicit `lockDirection` parameter for consistent stair orientation. Direction state resets per command to avoid stale facings.
- Safety changes: blocked destructive helpers (`digOut`, `breakBlockAt`) to enforce tool-based mining; escape routines schedule work on tick instead of blocking server threads. Added hazard scanning during ascent and tightened drop cleanup to reuse trusted sweep logic.
- Docs: added button-orientation tip to the guide and logged ascent headroom tweaks and obstruction damage gating.

## 2025-11-18
- Persistency and safety: inventory save timing fixed; drop sweeps stop breaking blocks and only collect items; bots break out when spawned in walls; upward stairs start in the controller’s facing direction (partial fix).
- Task queue notes captured for stats persistence and the simplified upward stair spec.

## 2025-11-17 Checkpoint
- Mining polish: work-direction persistence across pause/resume, hazard pauses with `/bot resume`, torch placement on walls (level ≥7), and `/bot reset_direction` to clear stored facings.
- Survival & UX: hunger auto-eat thresholds with `/bot heal`, inventory full messaging, drop sweep retries, suffocation checks after tasks, and `inventory` chat summaries.
- Controls: config UI adds Bot Controls tab (auto-spawn, teleportDuringSkills, inventoryFullPause, per-bot/world LLM toggles) with owner display and scrollable rows; bots auto-spawn at last saved position.
- LLM bridge: natural-language job routing to real skills with confirmation, per-bot personas/memory, action queueing, status responses, and `/bot config llm …` toggles.

## 2025-10-31 (Gemini report recap)
- Added composite tools (`mineBlock`, `chopWood`, `shovelDirt`, `cultivateLand`) with FunctionCaller orchestration and state tracking; verified builds.
- Early RL/hold-tracking tweaks and Mineflayer/RAG exploration notes logged for future LLM integration work.

## Legacy Releases (pre-2025)
- 1.0.x line: 1.20.6 compatibility, server-side training mode support, Q-table format change, risk-taking mechanism, expanded triggers (lava/cliffs/sculk), and broad command set (`use-key`, `detectDangerZone`, inventory queries, armor equip/remove, etc.). See archived release notes in `archive/legacy_changelogs.md`.
