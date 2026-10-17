# Pathfinder A/B, sandbox, 2026-10-16 (1.1.259 build)

Field checklist Phase 6am item 1, first sandbox pass. Claude ran it in `mcp-sandbox` ("Wall Test" save) over MCP.

**Setup:** daylight (cycle off, mob spawning off), four bots placed with `/spreadplayers` in the woods around 40-70, 165-215
(outside the Spawn base), each with a stone axe and bread. `/bot config pathcompare on`. Unified finder for Smokey and Trainee,
Baritone for Wally and Abby. `/bot skill woodcut` on all four, 9 minutes (21:40:10-21:49:50). Report:
`tools/logs/frens_log_report.py latest.log --since 21:40:10`.

| | Unified (Smokey, Trainee) | Baritone (Wally, Abby) |
|---|---|---|
| Logs cut | 68 (47 + 21) | 19 (19 + 0, Abby landed with no tree in range) |
| Walks that reached the goal | 27/46 (58.7%) | 9/25 (36%) |
| Plans found (PathCompare, same 98 requests) | 82.7% | 85.7% |
| Plan time p50 / p90 | 4 / 45 ms | 0 / 505 ms |
| Deaths, stuck episodes, errors | 0 | 0 |

- PathCompare: unified found 14 routes the old bidirectional A* missed, Baritone found 5 unified did not, unified found 2
  Baritone did not.
- Unified no-path reasons: GOAL_NOT_STANDABLE 13, BUDGET 2, BOXED_IN 1, BLOCKED_UNBREAKABLE 1. GOAL_NOT_STANDABLE is what
  1.1.259's woodcut stand memory now remembers for 5 minutes.
- Earlier baseline (1.1.242 field A/B): unified reached 46% vs 61.5%.

**Reading:** encouraging for unified (more logs, higher reach rate, much faster plans) but the sample is small and the start
spots differ (Abby had no trees). Not enough to switch the default. Next: rerun with the finders swapped between the same
bots and spots, 20+ minutes, after 1.1.259 (the GOAL_NOT_STANDABLE memory should lower unified's failed walks).
