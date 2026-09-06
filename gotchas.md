# Gotchas

## Keep command scope separate from execution safety

- Do not assume different proximity rules between two entry paths are themselves a bug.
- Diagnose the failure mechanism separately from product semantics. For `zzz`, remote activation of an owned bot may be intentional; the freeze came from server-thread pathfinding and duplicate bed candidates.
- Confirm the intended targeting behavior before narrowing an existing command's range.

## Do not generalise a scoping correction beyond the method it was about

- 1.1.211 found scene *delivery* lives in `SoulGroupConversationService`, not `SoulRuntime`. The next brief repeated that as "the spec is stale about SoulRuntime" — but the mind callback `noteSceneDeliveredForMind` really is in `SoulRuntime`.
- Record corrections per method, not per file, and hand them to the next scoper as things to verify.

## A scoper's "does not exist" is a hypothesis, not a finding

- 1.1.213's scoper reported `SoulRuntime.noteSceneDeliveredForMind` missing; the handoff carried that as fact. It existed at `SoulRuntime:939` — the 1.1.212 line number had merely drifted by 100.
- A negative existence claim is one failed grep away from wrong. Before writing "X does not exist" into a handoff, grep the bare method name across `src/`; when two sessions disagree, hand the conflict to the next scoper by name and require file:line either way.
