# Fortify Navigation Guardrails (Trap/Entombment Recovery)

Updated: 2026-02-24

## Purpose

Prevent iterative fortify navigation fixes from reintroducing known failure modes:
- self-entombment after carve/repair
- trap escape false positives (`A <-> B` wiggle counted as success)
- long-range carve activation
- remote/under-floor support placement during emergency escape
- repeated gate retries from the same dead-end pocket

## Regression Changelog (What Failed)

### 1. Premature carve cleanup resealed the bot
- Symptom: bot carved into a pocket/tower and immediately repaired the entry while still trapped.
- Failure mode: carve finalize was tied to short `walkToTarget(...)` scope end instead of a full escape episode.
- Guardrail: defer carve finalization while bot remains in `POCKET` / `DEAD_END`; apply seal-risk checks before repairs.

### 2. Tower-only carve activation missed gate/edge entombment
- Symptom: bot trapped in fortify gate/edge loops never escalated to carve.
- Failure mode: emergency carve activation depended on `towerState` presence.
- Guardrail: trap carve activation must be fortify-scope-aware (`tower`, `gate`, `edge`) and not require tower state.

### 3. Inherited `towerState` blocked gate emergency carve
- Symptom: gate scopes with inherited tower state only logged local-only suppression and never activated emergency trap carve.
- Failure mode: emergency activation logic existed only in non-tower branch.
- Guardrail: emergency trap activation must exist in both `towerState` and non-`towerState` scope branches.

### 4. Emergency support-column trap escape (REGRESSION)
- Symptom: bot attempted support placement multiple blocks below the current floor; user-observed "remote mining/under-floor behavior"; no actual escape.
- Failure mode: emergency `noFloor` branch attempted downward support columns (`up to 4` blocks) and mixed trap escape with scaffold placement.
- Guardrail (hard): `tryBreakThroughObstacle(...)` must not place support columns below the candidate stand cell in trap escape mode.

### 5. False-positive trap escape success (REGRESSION)
- Symptom: repeated logs showed "escape success" while bot bounced between two adjacent cells and remained trapped.
- Failure mode: any movement counted as success.
- Guardrail: trap escape success requires meaningful progress:
  - topology/open-face improvement, or
  - target distance improvement >= 1.5 blocks, or
  - net displacement >= 2 blocks.

## Current Guardrails (Enforced)

### Break-through / Trap Escape
- "Tunneling" for navigation is defined as a horizontal opening through the blocking obstacle (feet/head/optional overhead), not excavation beneath the bot/floor.
- Emergency trap carve mode may override `village_adjacent` only in bounded local fortify contexts.
- Same-level all-air candidates are never counted as break-through success in trap carve mode.
- Emergency trap search includes immediate `dy=-1/+1` offsets only for step-down/step-up exits that are already open/standable; downward mining is disallowed.
- Trap escape movement is validated against topology and progress metrics before success is accepted.

### Carve Cleanup
- Carve repairs can be deferred/queued instead of dropped.
- Seal-risk checks prevent repairs that would reduce exits and re-trap the bot.
- Deferred cleanup queue respects seal-risk checks too.

### Scope / Context
- Long-range fortify contexts do not activate carve mode.
- Emergency trap carve is scoped to fortify recovery contexts (`tower`, `gate`, `edge`) only.

## Acceptance Checks For Future Changes

Before shipping trap/entombment changes, verify in `latest.log`:
- `trap-detected ... emergencyEscape=activating` appears when trapped.
- No repeated "success" logs with the same 2-cell oscillation.
- No under-floor or remote support placement attempts (no support target several Y below bot).
- If no escape occurs, logs show explicit rejection reasons (`village_adjacent`, `noFloor`, etc.) without false success.
- Carve/repair cleanup logs preserve deferred work and do not silently discard failures.

## Implementation Notes

- `tryBreakThroughObstacle(...)` is a break-through primitive, not a generic movement planner. Avoid adding broad support-placement or path-building behavior here.
- Prefer geometry-aware candidate selection and topology-validated success over "just move somewhere" fallbacks.
- If support placement is reintroduced in the future, it must be:
  - local (single support only),
  - LOS-valid,
  - explicitly bounded,
  - and verified not to create new entombment or cleanup debt loops.
