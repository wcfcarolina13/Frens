# Pathfinder A/B, swapped, sandbox, 2026-10-17 (1.1.260 build)

The follow-up to `PATHFINDER_AB_2026-10-16.md`. It ran 22 minutes over two valid rounds, with the finders swapped between
the same bots and spots. Claude ran it in `mcp-sandbox` over MCP with `tools/sandbox/abtest.py`.

## Setup

- **World:** a fresh copy of the sandbox "New World" for each round, with commands switched on in level.dat. It has the
  same seed as "Wall Test". Every round starts from the same untouched terrain.
- **Spots:** a tree-dense strip around x 70-80, found by scanning for `_log` blocks:
  - Wally (70,172)
  - Smokey (78,190)
  - Trainee (74,208)
  - Abby (80,240)

  Each bot lands within 3 blocks of its spot (`/spreadplayers`).
- **Conditions:** day locked (`advance_time false`), mob spawning off (`spawn_mobs false`), and each bot gets a stone axe and
  16 bread. `/bot config pathcompare on`, then `/bot skill woodcut` on all four for 11 minutes.
- **Finders:**
  - Round 1 (00:45:58-00:57:02): Wally and Trainee unified, Smokey and Abby Baritone.
  - Round 2 (01:59:33-02:10:38): swapped.
- **Report:** `tools/logs/frens_log_report.py <log> --since/--until`.

**A discarded run.** The first round 2 (00:59:52-01:10:56) is not counted. The integrated server logged "Saving and pausing
game" at 01:02:15 (a pause screen) and stayed frozen for the remaining 8.7 minutes. While the server is frozen the bots
cannot move, but their worker threads run on wall-clock time. So they kept planning, logging "walk blocked near" (92 lines
after the pause, 2 before) and counting "stuck for N ticks" from elapsed milliseconds. That looked like a walker stuck loop
on leaf_litter. Checked in the 1.21.11 jar, leaf_litter is `replaceable().noCollision()` and classifies as passable. Two
changes guard against this now:
- `abtest.py` watches the log and closes a pause screen.
- `frens_log_report.py` prints **SERVER PAUSED at T** in the overview, and counts `stuck for N ticks` and `attempting escape
  movement` lines as stuck episodes (before this, it reported 0).

## Results

### Logs cut per bot

| Bot | Round 1 | Round 2 | Unified minus Baritone |
|---|---|---|---|
| Wally | unified **53** | Baritone **50** | +3 |
| Trainee | unified **75** | Baritone **76** | -1 |
| Smokey | Baritone **68** | unified **78** | +10 |
| Abby | Baritone **63** | unified **68** | +5 |
| **Totals** | unified 128, Baritone 131 | unified 146, Baritone 126 | **unified 274, Baritone 257 (+6.6%)** |

### Finder metrics

| | R1 unified | R1 Baritone | R2 unified | R2 Baritone |
|---|---|---|---|---|
| Walks that reached the goal (joined to a plan) | 53/77 (68.8%) | 37/78 (47.4%) | 47/73 (64.4%) | 38/68 (55.9%) |
| Plans found (PathCompare, same requests, n=205 / 201) | 79% | 73.7% | 89.6% | 86.6% |
| Routes only this finder found | 14 | 3 | 8 | 2 |
| Plan time p50 / p90 (PathCompare) | 5 / 44 ms | 0 / 512 ms | 3 / 46 ms | 0 / 511 ms |
| Stuck episodes, deaths, ERROR lines | 1 (Trainee holding still after the final stop), 0, 0 | 0, 0, 0 | 0, 0, 0 | 1 (Trainee, brief), 0, 0 |

Combined walk reach: unified 100/150 (66.7%), Baritone 75/146 (51.4%).

## Reading

The same bots on the same terrain, with the finders crossed:
- **Logs:** unified cut 6.6% more logs (274 vs 257). It was ahead on 3 of 4 spots and level on the fourth (Trainee, -1).
- **Walks:** unified reached its walk goal 15 points more often (66.7% vs 51.4%), ahead in both rounds.
- **Plans:**
  - Unified found more routes in both rounds (22 routes Baritone missed, against 5 the other way).
  - Unified's slow plans stay under about 50 ms. Baritone's p90 sits at its 500 ms timeout in both rounds, and those
    timeouts are where its walks fail.
- **Safety:** neither finder caused deaths, ERROR lines or real stuck episodes.

This agrees with the 1.1.259 pass (unified 58.7% vs 36% reach) and now controls for bot and spot. The 1.1.242 field A/B went
the other way (46% vs 61.5%). Those numbers predate the 1.1.259 stand memory and came from a different world.

**Recommendation:** make unified the default pathfinder, with Baritone kept as the opt-in fallback. This is Bradley's call.

**Secondary findings from the scope (not shown to matter here, worth a later look):**
- `BotRescueService.attemptEscapeMovement` and `findNearestAirDirection` accept only `isAir()` side cells. Grass, leaf_litter
  and snow layers on all four sides make "up" win.
- `ReturnBaseStuckService.tryQuickNudge` never considers a drop of 1.
- `ReturnBaseStuckService.isPathClear` and `isBlockBlocking` use `isSolidBlock` as the blocking test.
- `ReturnBaseStuckService.tryScaffoldEscape` uses `!blocksMovement()` for its cells.
