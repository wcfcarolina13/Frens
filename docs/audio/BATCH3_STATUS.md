# Batch 3 Audio Integration Status

## Source and phase split

- Authoritative index: `../gemini_projects/ai-player-dialogue/january_2026_batch3/AUDIO_INDEX.md`
- Generated source date: January 22, 2026
- Batch totals:
  - Non-topic lines: 87 IDs (Phase 1)
  - Topic lines: 105 IDs (Phase 2)

## Phase 1 scope (implemented)

- Manifest: `tools/audio/batch3_phase1_manifest.tsv`
- Assets copied in scope: 261 files (`87 * 3`)
- Audio audit script: `tools/audio/audit_batch3_phase1.sh`
- Playtest runbook: `docs/audio/BATCH3_PHASE1_PLAYTEST.md`

## Phase 1 code integration matrix

| Area | Files | Status |
|---|---|---|
| Asset manifest + ingest | `tools/audio/batch3_phase1_manifest.tsv` | Implemented |
| Static audio audit | `tools/audio/audit_batch3_phase1.sh` | Implemented |
| Sound event declarations | `src/main/java/net/shasankp000/ChatUtils/BotDialogueSounds.java` | Implemented |
| Sound json entries | `src/main/resources/assets/ai-player/sounds.json` | Implemented |
| Subtitle mappings | `src/main/java/net/shasankp000/ChatUtils/BotDialoguePlayer.java` | Implemented |
| Combat non-topic triggers | `src/main/java/net/shasankp000/GameAI/services/BotCombatCalloutService.java` | Implemented |
| Bot combat metadata feed | `src/main/java/net/shasankp000/GameAI/BotEventHandler.java` | Implemented |
| Friendly-fire dealt hook | `src/main/java/net/shasankp000/AIPlayer.java` | Implemented |
| Villager proximity/interaction | `src/main/java/net/shasankp000/GameAI/services/VillageProximityReactionService.java` | Implemented |
| Pet and tame-animal proximity | `src/main/java/net/shasankp000/GameAI/services/PetProximityReactionService.java` | Implemented |
| Context trigger service | `src/main/java/net/shasankp000/GameAI/services/CompanionContextReactionService.java` | Implemented |
| Context + pet tick registration | `src/main/java/net/shasankp000/AIPlayer.java` | Implemented |
| Shelter completion hook | `src/main/java/net/shasankp000/GameAI/skills/impl/ShelterSkill.java` | Implemented |
| Admin verification command | `src/main/java/net/shasankp000/Commands/BotUtilityCommands.java`, `src/main/java/net/shasankp000/Commands/modCommandRegistry.java` | Implemented (`/bot dialogue_test <bot> <trigger_key> [line_id]`) |

## Overlap IDs intentionally preserved

These IDs were not remapped/replaced in Phase 1:

- `bot.line.discover_mineshaft`
- `bot.line.discover_spawner`
- `bot.line.weather_rain`
- `bot.line.weather_snow`
- `bot.line.weather_sunny`
- `bot.line.weather_thunder`
- `bot.line.time_sunset_soon`
- `bot.fx.hurt_grunt`

## Overlap refresh (Phase 2B)

- Event IDs remained unchanged for overlap keys.
- `sounds.json` path references were refreshed to canonical Batch 3 source filenames for:
  - weather (`weather_*__01..06`)
  - sunset (`time_sunset_soon__01..06`)
  - hurt grunts (`hurt_grunt__01..08`)
- Result: source coverage now verifies as `618 / 618` clips mapped from `output_ogg`.

## Phase 2 (deferred)

- Side-quest dialogue audio + routing (deferred until side-quest overhaul)

## Batch 3 Topic Pack Port (Phase 2A)

- Scope: full `topic_*` asset + mapping + minimal routing pass
- Manifest: `tools/audio/batch3_topic_manifest.tsv`
- Audio audit script: `tools/audio/audit_batch3_topic.sh`
- Assets in scope: 315 files (`105 * 3`)
- Trigger groups:
  - `batch3_biomes`
  - `batch3_structures`
  - `batch3_dimensions`
  - `batch3_traders_mounts`
  - `batch3_travel`
- Routing files:
  - `src/main/java/net/shasankp000/GameAI/services/Batch3TopicDialogueService.java`
  - `src/main/java/net/shasankp000/GameAI/services/SurvivalCompanionQuestService.java`
  - `src/main/java/net/shasankp000/GraphicalUserInterface/BotPlayerInventoryScreen.java`
  - `src/main/java/net/shasankp000/network/CompanionQuestNetworkManager.java`
  - `src/main/java/net/shasankp000/Commands/modCommandRegistry.java`
- Status: Implemented

## Coverage Tooling (Step 4)

- Tooling script: `tools/audio/report_dialogue_coverage.py`
- Convenience wrapper: `tools/audio/report_dialogue_coverage.sh`
- Latest generated report: `docs/audio/DIALOGUE_COVERAGE_REPORT.md`
- Current headline results:
  - `618 / 618` source clips mapped from `output_ogg`
  - Batch 3 manifest rows: `192` direct-routed, `105` exact text-mapped, `0` unreachable
  - Log-observed unmapped messages currently point at side-quest text lines

## TODO

- Review and close current coverage-report findings:
  - side-quest text lines still appear as unmapped in logs (expected for deferred side-quest pass).
  - constants currently referenced only in subtitle table should be reviewed for intended routing.
  - keep intentional shared-text alias: `precipice_big_drop` text maps to `LINE_WARNING_DROP_AHEAD` on lookup path by design.
- Ambient social banter currently emits overhead text directly; evaluate unified playback path so banter lines consistently use voiced audio when assets exist.
- Regenerate banter delivery for the creeper joke line (`Look out, creeper! Haha, just kidding.`) due to low-quality intonation; keep current asset as placeholder until replacement is ready.
- Side-quest dialogue audio is intentionally deferred until the side-quest overhaul pass (generate + map dedicated side-quest clips at that time).
