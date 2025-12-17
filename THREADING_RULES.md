# Threading Rules (AI-Player)

This project mixes “game thread” (Minecraft server tick thread) logic with background work (path planning, scanning, waiting).
Most hard-to-debug bugs and lag spikes come from violating these boundaries.

## Server Thread Only

Do these only on the Minecraft server thread:

- World mutations: placing/breaking blocks, opening doors, interacting with blocks/entities.
- Inventory mutation: moving stacks between inventories, crafting/smelting UI interactions.
- Entity state mutation: teleport/setPosition, applying velocity, changing attributes, swing/interact calls.

If you must do something from a worker thread, schedule it onto the server thread via the project’s existing `server.execute(...)`
or `callOnServer(...)` patterns.

## Worker Thread OK (Preferred)

These are safe and preferred off-thread:

- Planning: choosing targets, scanning candidate positions, path computation, heuristic scoring.
- Waiting/backoff loops: timeouts, retries, “sleep a bit and re-check”.
- Log/metrics aggregation.

## Rules of Thumb

- Never `Thread.sleep(...)` on the server thread.
- Never run long “while loop until X” logic on the server thread unless it is bounded to a tiny time window.
- Keep “server thread sections” small: compute off-thread, then execute a single atomic action on-thread.

