# In-Game Audit Master Runbook

Date: February 14, 2026  
Scope: Unified reliability + dialogue/audio verification

## Purpose
This is the canonical in-game audit entrypoint for current work.

It consolidates and extends:
- `docs/reliability/FOLLOW_COME_ASSERT_RUNBOOK.md`
- `docs/audio/BATCH3_PHASE1_PLAYTEST.md`

Use this file first, then drill into the source runbooks for full trigger catalogs.

## Validation Gates
Run before in-game checks:

```bash
./gradlew compileJava
./gradlew build -x test
```

CI parity check:
- Confirm GitHub Actions `Build Jar` is green for the pushed SHA.

Required log marker families:
- `[PermCheck]` for permission compatibility mode
- `[FollowAssert]` for follow/come invariants
- `[PersistCheck]` for respawn save/restore sequencing

## Fast Daily Smoke (10 min)

| ID | Setup | Command(s) | Expected token/log/audio | Pass |
|---|---|---|---|---|
| AUD-001 | OP player + one bot online | `/bot sound_test <bot>` | Bot says test line; subtitle and audible clip present | [ ] |
| AUD-002 | OP player + one bot online | `/bot follow <bot>` then `/bot follow_check <bot> follow+has_target+planner_idle` | `follow_check` passes; no planner stuck loop | [ ] |
| AUD-003 | Same | `/bot come <bot>` then `/bot follow_check <bot> come+fixed_goal+force_walk` | `follow_check` passes for come mode | [ ] |
| AUD-004 | Same | `/bot dialogue_test <bot> ambient ambient_thinking` | Overhead text + matching audio + subtitle | [ ] |
| AUD-005 | Same | `/bot dialogue_test <bot> creeper_hiss meme_creeper_aw_man` | Deterministic meme line plays | [ ] |
| AUD-006 | Same | `/bot chat_check inspect "<bot>, follow me"` | Named routing classified correctly | [ ] |
| AUD-007 | Same | Kill bot once, wait respawn | `[PersistCheck] death-pre-snapshot`, `respawn-event`, `post-respawn-restore` appear in order | [ ] |
| AUD-008 | Server restart with same bot alias | Spawn bot after restart | Stats/inventory restoration marker sequence appears once | [ ] |

## Full Reliability Sweep (45-60 min)

### A. Permission Compatibility

| ID | Setup | Command(s) | Expected token/log/audio | Pass |
|---|---|---|---|---|
| AUD-010 | Server startup | start server/client world | `[PermCheck] operator-permission mode=...` logged once at mod init | [ ] |
| AUD-011 | Non-op account + op account | Non-op: `/bot follow <bot>` | Command denied by Brigadier permission gate | [ ] |
| AUD-012 | Op account | `/bot follow <bot>` | Command accepted | [ ] |
| AUD-013 | Console | run `/bot list` or `/bot recruit status` | Console accepted (no player context required) | [ ] |
| AUD-014 | Dedicated + integrated worlds | startup both modes | No permission predicate startup crash | [ ] |
| AUD-015 | Op account + known alias | `/bot identity_check <alias>` | Identity summary prints with `[IdentityCheck]` log marker | [ ] |

### B. Follow/Come Deterministic Assertions

| ID | Setup | Command(s) | Expected token/log/audio | Pass |
|---|---|---|---|---|
| AUD-020 | One bot, mixed terrain | `/bot follow <bot>` then `/bot follow_check <bot> follow+has_target+planner_idle` | Passes; planner idle once path stable | [ ] |
| AUD-021 | Same | `/bot come <bot>` then `/bot follow_check <bot> come+fixed_goal+force_walk` | Passes; no direct snap behavior | [ ] |
| AUD-022 | Same | `/bot come_safe <bot>` then `/bot follow_check <bot> come+recovery_off` | Passes safe-mode invariant | [ ] |
| AUD-023 | Awkward corners/vertical lips | Repeated `/bot come <bot>` then `/bot follow_check <bot> reroute_scheduled` | Reroute scheduling becomes true when needed | [ ] |
| AUD-024 | Same | `/bot follow_check <bot> repeat_wp` and `/bot follow_check <bot> vertical_trap` | One or both appear in trap scenarios | [ ] |
| AUD-025 | Water ledge scenario | `/bot follow_check <bot> water_escape_active`, then `water_escape_idle` after recovery | Water escape lifecycle visible and bounded | [ ] |
| AUD-026 | Mounted bot stuck-ish geometry | force stuck-like context | `[FollowAssert] mounted-rescue-skip ...` appears; no false rescue mining | [ ] |

### C. Chat Routing

| ID | Setup | Command(s) | Expected token/log/audio | Pass |
|---|---|---|---|---|
| AUD-030 | Two bots online | `/bot chat_check inspect "allbots, gather"` | Broadcast classification and multi-target routing | [ ] |
| AUD-031 | Two bots online | `/bot chat_check inspect "<botA>, follow me"` | Single named target, no cross-talk | [ ] |
| AUD-032 | Two bots online | `/bot chat_check assert named|single|bot:<botA> "<botA>, status"` | Assertion pass | [ ] |
| AUD-033 | Two bots online | `/bot chat_check assert broadcast|multi "allbots, stop"` | Assertion pass | [ ] |

### D. Audio Mapping and Trigger Sanity

| ID | Setup | Command(s) | Expected token/log/audio | Pass |
|---|---|---|---|---|
| AUD-040 | One bot online | `/bot sound_test <bot>` | baseline dialogue sound path good | [ ] |
| AUD-041 | Same | `/bot dialogue_test <bot> villager villager_rude` | villager line text/subtitle/audio aligned | [ ] |
| AUD-042 | Same | `/bot dialogue_test <bot> wolf_hurt wolf_leave_alone` | wolf hurt line plays immediately | [ ] |
| AUD-043 | Same | `/bot dialogue_test <bot> precipice precipice_back_up` | precipice line plays with subtitle | [ ] |
| AUD-044 | Same | `/bot dialogue_test <bot> batch3_biomes topic_deep_dark_first_1` | topic line appears with voiced playback | [ ] |
| AUD-045 | Same | `/bot dialogue_test <bot> batch3_structures topic_stronghold_first_1` | topic routing + audio mapping works | [ ] |

### E. Respawn Persistence Deterministic Checks

| ID | Setup | Command(s) | Expected token/log/audio | Pass |
|---|---|---|---|---|
| AUD-050 | Give bot known inventory + XP + hunger state | `/bot inventory <bot>` snapshot baseline | Baseline written in notes | [ ] |
| AUD-051 | Force bot death | kill bot | `[PersistCheck] death-pre-snapshot ... vitals=... inv=...` appears | [ ] |
| AUD-052 | Wait for respawn event | none | `[PersistCheck] respawn-event ...` then `[PersistCheck] respawn-restore-expected ...` | [ ] |
| AUD-053 | After respawn tick | none | `[PersistCheck] join-restore-order ... stage=inventory-load` then `post-respawn-restore` | [ ] |
| AUD-054 | Stress duplicate handling by repeated respawn/rejoin | cycle respawn quickly | no duplicate restore state corruption; stale/duplicate skips are explicit if triggered | [ ] |
| AUD-055 | Verify restored stats in-game | `/bot inventory <bot>` | health/food/xp align with expected restore behavior | [ ] |

### F. Identity Consistency Diagnostics

| ID | Setup | Command(s) | Expected token/log/audio | Pass |
|---|---|---|---|---|
| AUD-060 | Alias with known config profile | `/bot identity_check <alias>` | Config alias/UUID shown; no false warning for healthy state | [ ] |
| AUD-061 | Bot online for alias | `/bot identity_check <alias>` | Online UUID visible; UUID match field reflects config parity | [ ] |
| AUD-062 | Alias with old/stale data files | `/bot identity_check <alias>` | Warning list highlights variant keys / multi snapshots | [ ] |

## Rare/Slow Trigger Audit via Forced Commands
Use deterministic forcing to bypass low-frequency cadence triggers:

```mcfunction
/bot dialogue_test <bot> random_ambient meta_not_robot
/bot dialogue_test <bot> baby_zombie_on_chicken meme_chicken_jockey
/bot dialogue_test <bot> lightning_at_night meme_herobrine_leaving
/bot dialogue_test <bot> batch3_dimensions topic_nether_first_1
/bot dialogue_test <bot> batch3_travel topic_elytra_first_1
```

If a line does not play immediately, wait out global anti-spam (2.5s, and up to 10s for combat/status category).

## Runtime Notes Template

```text
Date/Build:
World type (integrated/dedicated):
Tester:
Case IDs run:
Pass count:
Fail count:
Observed markers:
- [PermCheck]
- [FollowAssert]
- [PersistCheck]
Failures and repro:
```

## Source Runbooks (Detailed Appendices)
For full command catalogs and deeper context, use:
- `docs/reliability/FOLLOW_COME_ASSERT_RUNBOOK.md`
- `docs/audio/BATCH3_PHASE1_PLAYTEST.md`
