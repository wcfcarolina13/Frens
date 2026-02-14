# Follow/Come Deterministic Assert Runbook

## Purpose
This runbook validates follow/come reliability behavior with deterministic `/bot follow_check` assertions when live playtesting time is limited.

## Prerequisites
- Server running a build from this branch.
- At least one bot online and controllable.
- Operator permissions.

## Quick Build Gate
Run before entering world:

```bash
./gradlew compileJava
./gradlew build -x test
```

## Baseline Checks
1. Start follow:

```mcfunction
/bot follow <bot>
/bot follow_check <bot> follow+has_target+planner_idle
```

2. Start come:

```mcfunction
/bot come <bot>
/bot follow_check <bot> come+fixed_goal+force_walk
```

3. Start safe come:

```mcfunction
/bot come_safe <bot>
/bot follow_check <bot> come+recovery_off
```

## Reroute/Planner Checks
Use awkward geometry (corners, doorways, cave lips) and issue repeated come commands.

1. Confirm reroute attempt state appears:

```mcfunction
/bot follow_check <bot> rerouted
```

2. Confirm reroute scheduling state:

```mcfunction
/bot follow_check <bot> reroute_scheduled
```

3. Confirm planner is not hot-looping forever:

```mcfunction
/bot follow_check <bot> planner_idle
```

4. Check repeated-waypoint and vertical-trap signals where relevant:

```mcfunction
/bot follow_check <bot> repeat_wp
/bot follow_check <bot> vertical_trap
```

## Water Escape Checks
In shallow/deep water ledge scenarios:

```mcfunction
/bot follow_check <bot> water_escape_active
```

After bot recovers:

```mcfunction
/bot follow_check <bot> water_escape_idle
```

## Resume Checks
Interrupt follow/come with a skill and verify resume logging + state:

```mcfunction
/bot follow_check <bot> follow
```

or

```mcfunction
/bot follow_check <bot> come
```

Expect `[FollowAssert] task-resume ...` lines in log around the transition.

## Mounted Rescue Safety Checks
Mount bot in a boat/vehicle and force stuck-like geometry.

Expected log marker:
- `[FollowAssert] mounted-rescue-skip ...`

Expected behavior:
- No proactive burial mining while mounted unless real suffocation is detected.

## Token Reference
- Core: `follow`, `come`, `idle`, `has_target`, `no_target`, `fixed_goal`, `no_fixed_goal`
- Movement mode: `force_walk`, `can_teleport`
- Recovery mode: `recovery_on`, `recovery_off`, `rerouted`, `no_reroute`
- Planner state: `planner_inflight`, `planner_idle`, `has_waypoints`, `no_waypoints`
- Reroute scheduling: `reroute_scheduled`, `no_reroute_scheduled`
- Repeated planning signatures: `repeat_wp`, `no_repeat_wp`, `vertical_trap`, `no_vertical_trap`
- Water escape: `water_escape_active`, `water_escape_idle`

## Pass/Fail Logging Template
Record outcomes in your test notes as:
- Scenario
- Commands run
- `follow_check` result
- Relevant `[FollowAssert]` log lines
- Regression observed (yes/no)
