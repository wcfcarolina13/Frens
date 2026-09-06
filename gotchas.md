# Gotchas

## Keep command scope separate from execution safety

- Do not assume different proximity rules between two entry paths are themselves a bug.
- Diagnose the failure mechanism separately from product semantics. For `zzz`, remote activation of an owned bot may be intentional; the freeze came from server-thread pathfinding and duplicate bed candidates.
- Confirm the intended targeting behavior before narrowing an existing command's range.

## Do not generalise a scoping correction beyond the method it was about

- 1.1.211 found scene *delivery* lives in `SoulGroupConversationService`, not `SoulRuntime`. The next brief repeated that as "the spec is stale about SoulRuntime" — but the mind callback `noteSceneDeliveredForMind` really is in `SoulRuntime`.
- Record corrections per method, not per file, and hand them to the next scoper as things to verify.
