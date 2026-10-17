# Pathfinder A/B, swapped, sandbox, 2026-10-17 (1.1.260 build)

The follow-up to `PATHFINDER_AB_2026-10-16.md`: 22 minutes over two rounds, with the finders swapped between the same bots
and spots. Claude ran it in `mcp-sandbox` over MCP with `tools/sandbox/abtest.py`.

## Setup

- **World:** two fresh copies of the sandbox "New World" (same seed as "Wall Test", commands switched on in level.dat), one
  copy per round. Each round starts from untouched terrain, so the two rounds see the same trees.
- **Spots:** a tree-dense strip around x 70-80 (located by scanning for `_log` blocks):
  - Wally (70,172)
  - Smokey (78,190)
  - Trainee (74,208)
  - Abby (80,240)

  Each bot lands within 3 blocks of its spot (`/spreadplayers`), so its exact landing block differs between rounds.
- **Conditions:** day locked (`advance_time false`), mob spawning off (`spawn_mobs false`), and each bot gets a stone axe and
  16 bread. `/bot config pathcompare on`, then `/bot skill woodcut` on all four for 11 minutes.
- **Finders:**
  - Round 1 (00:45:58-00:57:02): Wally and Trainee unified, Smokey and Abby Baritone.
  - Round 2 (00:59:52-01:10:56): swapped.
- **Report:** `tools/logs/frens_log_report.py <log> --since/--until`.

## Results

### Logs cut per bot

| Bot | Round 1 | Round 2 |
|---|---|---|
| Wally | unified **53** | Baritone **18** |
| Trainee | unified **75** | Baritone **32** |
| Smokey | Baritone **68** | unified **23** |
| Abby | Baritone **63** | unified **19** |
| **Totals** | unified 128, Baritone 131 (259) | unified 42, Baritone 50 (92) |

Combined: unified 170, Baritone 181.

### Finder metrics

| | R1 unified | R1 Baritone | R2 unified | R2 Baritone |
|---|---|---|---|---|
| Walks that reached the goal (joined to a plan) | 53/77 (68.8%) | 37/78 (47.4%) | 12/107 (11.2%) | 6/75 (8%) |
| Plans found (PathCompare, same requests, n=205 / 290) | 79% | 73.7% | 98.3% | 92.1% |
| Routes only this finder found (vs the other) | 14 | 3 | 18 | 0 |
| Plan time p50 / p90 (PathCompare) | 5 / 44 ms | 0 / 512 ms | 6 / 20 ms | 0 / 2 ms |
| Deaths, ERROR lines | 0 | 0 | 0 | 0 |

## Reading

**The round, not the finder, decided the yield.** Every bot cut more in round 1 than in round 2, whichever finder it had
(Smokey 68 on Baritone, then 23 on unified; Wally 53 on unified, then 18 on Baritone). Summed over both rounds, the finders
are level on logs: unified 170, Baritone 181. Log yield gives no reason to switch the default.

**Why round 2 collapsed: bots stuck for minutes after a few trees, under both finders.** The finders found paths: 162 walks
had an executable plan, and 144 of them still did not arrive.

| Bot | Finder | Where it stuck | For how long | What was there |
|---|---|---|---|---|
| Smokey | unified | about 60,75,179 | about 9 min (01:02-01:10) | Nothing walls it in: open air above, leaf_litter around. It repeated "escape movement toward up" every 10-12 s. |
| Trainee | Baritone | 57,73,207 | 4232 ticks (3.5 min) | Inside a ring of single oak_planks (woodcut scaffold remnants) on a leaf_litter floor. |
| Wally | Baritone | 70,78,169 | about 6 min (01:04-01:10) | On top of a 1-high plank. Every step down lands in a leaf_litter cell, and each one ended "walk blocked near 69,77,169". |
| Abby | unified | 78,74,235 | 601 ticks | Same ReturnBaseStuck loop. |

Further signs it is not finder-specific:
- `walk blocked near` lines: 94 in round 2, 18 in round 1.
- The report's per-bot table shows "stuck 0 episodes" for these bots, so its stuck counter misses this loop.
- Round 1 also had the pattern: Wally ended woodcut early at 00:53:41 with "Repeated failures reaching remaining targets".

The working hypothesis for a follow-up: the walker (local escape and segment walk) treats leaf_litter cells and 1-high
scaffold remnants as blocked, although the finders plan through them. `leaf_litter` appears nowhere in the movement code;
the only mentions are the hobby name and an item filter.

**What does hold up across both rounds:**
- Unified finds more routes. It found 14 and 18 routes Baritone did not; Baritone found 3 and 0 that unified did not. Its
  found rate was higher in both rounds (79% vs 73.7%, 98.3% vs 92.1%).
- Unified's plan time is bounded (p90 20-44 ms). Baritone is usually 0-2 ms but hit its 500 ms timeout often enough in
  round 1 to put p90 at 512 ms.
- When walks did complete, unified reached the goal more often (68.8% vs 47.4% in round 1, 11.2% vs 8% in round 2). This
  matches the 1.1.259 pass (58.7% vs 36%).

**Recommendation:**
1. Fix the stuck loop first: check the leaf_litter and scaffold-remnant hypothesis in the walker, and make the report count
   ReturnBaseStuck stretches as stuck episodes.
2. Then rerun this script, which is already set up for both rounds. Until the stuck loop is fixed, log yield measures the
   walker, not the finder.
3. On the evidence so far, unified is the better planner (more routes, no timeouts), and the default switch is Bradley's call.
