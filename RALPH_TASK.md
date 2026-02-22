---
task: Stabilize P0/P1 bot reliability issues
test_command: "./gradlew build -x test"
---

# Task: Stabilize P0/P1 Bot Reliability

Address the highest-priority pending issues from the project backlog.

## P0 — Critical

- [ ] Verify launch after permission predicate fix: Ensure bots can start without the LeveledPermissionPredicate OWNERS crash on 1.21.11
- [ ] Persist bot stats on respawn (verification): Save/load path exists; verify health/XP/hunger restore timing is visible before spawn flow completes

## P1 — High

- [ ] Bot config UI refactor: Single-bot view with alias dropdown, grouped/scrollable settings, save/cancel affecting only the selected bot
- [ ] Bot identity separation (verification): Alias canonicalization + consistency guards across restart/respawn with multi-alias scenarios
- [ ] Job resume prompts on death/leave (verification): Verify leave/rejoin prompt and resume behavior in-game
- [ ] Per-bot chat addressing & broadcasts (verification): Verify no duplicate replies or cross-talk in runtime scenarios

## Success Criteria

All checkboxes above marked `[x]` after in-game verification or code fix.

## Notes

- P0 items may require code changes if bugs are found during verification
- P1 items are primarily verification tasks — confirm existing code works correctly
- Build must pass (`./gradlew build -x test`) after any code changes
- Commit after completing each criterion

---

## Ralph Instructions

1. Work on the next incomplete criterion (marked [ ])
2. Check off completed criteria (change [ ] to [x])
3. Run build after code changes
4. Commit your changes frequently
5. Update .ralph/progress.md with what you accomplished
6. When ALL criteria are [x], say: "RALPH COMPLETE"
7. If stuck 3+ times on same issue, say: "RALPH GUTTER"
