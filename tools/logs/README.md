# Frens log tools

Two stdlib-only Python 3.9 scripts for the Minecraft client logs Prism writes to
`<instance>/minecraft/logs/` (`latest.log` plus rotated `YYYY-MM-DD-N.log.gz`).

- `frens_log_report.py` turns a log of any size into a one-screen digest.
- `frens_log_prune.py` digests old logs, archives them, and prunes the archive.

Tests: `cd tools/logs && /usr/bin/python3 -m unittest` (fixtures in `fixtures/`).

## frens_log_report.py

```
frens_log_report.py LOG [LOG ...] [--since HH:MM:SS] [--until HH:MM:SS] [--bot NAME]
                    [--json OUT] [--md OUT] [--spam-per-min 60] [--timeline 40] [--follow SECS]
```

- Several LOGs are read in the order given, so a whole session is
  `frens_log_report.py logs/2026-10-14-*.log.gz logs/latest.log`.
- `.gz` files are fine. A file still being written is fine too: a trailing partial line is
  ignored until it is complete.
- `--since/--until` filter on wall-clock time of day. The roster and thread bindings still
  learn from lines outside the window.
- `--bot` limits sections 2 to 5 to one bot. The overview stays global.
- `--follow SECS` prints the full report once, then one delta line every SECS
  (`lines+N WARN+N Jake.logs_mined+N ...`). It survives log rotation.
- A 50 MB log takes about 3 s.

### Sections

1. **Overview**: time span, bots, line counts per level, the top WARN/ERROR patterns with
   count, first and last time, and spam. Patterns are masked: numbers become `N`, `class_123`
   becomes `class_N` and UUIDs become `<uuid>`. Spam means any masked pattern, at any level,
   above `--spam-per-min` lines in some minute. Also lists deaths of non-bot players.
2. **Bots**: one block per bot with a summary line covering skills started and their results,
   logs cut, trees selected and abandoned, stands abandoned and skipped, stuck episodes and
   events, deaths, hp/food min and last, first and last position, and chat count. A timeline
   follows (`SKILL+`, `SKILL-`, `TREE`, `LOGS`, `ABANDON`, `SKIP`, `STUCK`, `TRAPPED`, `DEATH`).
   Consecutive repeats are folded into `xN`, the middle is cut past `--timeline` rows, and the
   last few chat lines come at the end.
3. **Pathfinding**: for each `[PathFinder] mode=`: count, p50/p90/max ms, % pathLen=0, and the
   walk-failure mentions on that thread after a path from that mode. Also A* timeouts,
   Baritone outcomes, `movement obstruction mine` mined and refused, `terrain move:` decisions
   with median cost, and local-escape routes repeated at least 3 times per bot. A* timeouts and
   Baritone outcomes logged by the shadow `frens-pathcompare` thread are left out (they are
   counted once, as excluded, and their result is in the `[PathCompare]` line).
4. **PathCompare**: pairs up `[PathCompare] bot=… primary=… bidir=<ms>ms/<len>/<exec|noexec|none>
   baritone=…` lines. For each finder: found %, executable % of found, p50/p90 ms. For each
   pair: both found, only one found, neither, who was faster, and the median ms ratio. Also a
   per-bot breakdown. Parsing goes by key, so new `k=v` fields and new finders are picked up
   automatically.
   Walk outcomes: `[PathCompareOutcome] bot=<name> to=x,y,z reached=<yes|no|n/a> detail=<token>`
   is logged (while the shadow compare is on) when a `walkTo` ends. Each outcome joins the
   latest `[PathCompare]` line for the same bot and `to=` that is at most 300 s before it and at
   most 15 s after it. The compare line is written on the `frens-pathcompare` thread once the
   shadow finder finishes, so for a fast failure it often lands after its outcome. If that
   latest line already belongs to an earlier outcome, the outcome stays unjoined: it never falls
   back to an older line, which would shift every later pair by one when a target is retried.
   Outcome lines are also logged by direct or aborted walks that never planned; those have no
   compare line and appear as unjoined. The report gives the overall reached rate, reached rate
   per primary finder and per primary path status (`exec`/`noexec`/`none`), the count of `exec`
   paths that were not reached (the number to watch), and the unjoined outcomes split into
   "no compare line" (direct or aborted walks, throttled replans) and "nearest line already
   used" (repeat outcomes for one plan). `reached=yes` is the walk's own success
   flag, so the close-enough distance tolerance applies. The detail is one token (spaces become
   `_`, `=` becomes `:`, at most 80 chars), so it never trips the walk-failure phrases.
5. **A/B per bot**: the finder for each bot comes from the `PathCompare primary=` majority,
   falling back to the PathFinder mode majority (at least 80%, otherwise `mixed`). Then: paths,
   p50, % len0, logs cut, targets abandoned, stuck episodes, deaths, and the median seconds
   from a `woodcut` start to its first log.

### How bots are attributed

- The roster comes only from lines that name a bot for certain: `spawnBot starting:
  botName=`, `fakeplayer '…'`, `for bot X`, `bot=X`. The human player is never on it.
- A line that names exactly one roster bot is attributed to that bot. On a worker thread
  (`mod-command-skill-1`, `underground-survival-2`, …) that line also binds the thread to the
  bot, and later lines on the thread that name nobody inherit the binding until another bot
  is named there. Threads ending in `-<Bot>` (`shelter-breakfree-Jake`) belong to that bot.
- On `Server thread` and `Render thread`, only lines that name the bot are attributed.
- Positions, hp and food are taken only from lines that name the bot. Pool threads switch
  bots, so an inherited binding is never trusted for them.

### Adding a pattern

Append a tuple to `PATTERNS` in `frens_log_report.py`:

```python
("cheap substring", re.compile(r"regex with (groups)"), "my_name"),
```

Then add a handler method on `Analyzer`:

```python
def _h_my_name(self, m, t, thread, bot, msg, level):
    if bot:
        self.bots[bot]["my_counter"] += 1      # add "my_counter": 0 in _new_bot
        self._tl(bot, t, "MYKIND", msg)        # optional timeline row
```

The substring is checked before the regex runs, which is what keeps big logs fast, so keep it
specific. Expose the new value in `_bots_report` / `render_md`, and add a fixture line plus an
assertion to `test_frens_log_report.py`.

### Known gaps

- `Mining complete at <pos>` does not name the block, and `Mining … with minecraft:oak_log`
  names the held item. So logs cut come only from `Woodcut unified: mined N logs` and
  `woodcut_cleanup completed … minedLogs=N`. Logs broken by other paths (shelter digging,
  stuck escape) are not counted.
- `terrain move:` and `movement obstruction mine refused` did not exist in the source when
  this was written. They are parsed in the spec'd format and only checked against the
  synthetic fixture. The same goes for `[PathCompare]`.
- Walk outcomes count every mention (`pursuit failed` plus the `walk blocked near` inside a
  later woodcut line), against the last PathFinder mode on that thread. They are not one per
  path.
- In logs up to 1.1.239 the pathfinder is one global toggle (`Pathfinder switched to:`), so a
  bot that lived through a switch shows as `mixed`.
- A stuck episode is any run of stuck events with gaps of 120 s or less.
- System chat without a speaker (`Woodcut paused: …`) shows in the overview, not under a bot,
  unless it names exactly one bot.
- Timestamps have no date. A step back of more than 10 minutes counts as midnight and shows
  as `d1+HH:MM:SS`.

## frens_log_prune.py

Digest, then archive, then prune. Retention for each instance lives in `prune_config.json`
next to the script:

| instance | rotated logs archived after | raw archive deleted after | screenshots |
|---|---|---|---|
| `mcp-sandbox` | 3 days | 14 days | `screenshots/` and `screenshots/mcp/` (MCP fork) archived after 7 days, deleted after 30 |
| `1.21.11` (Nirn) | 14 days | 90 days | never touched |

The archive root is `~/Library/Application Support/PrismLauncher/frens-log-archive/<instance>/`:

- `digests/<logname>.md` and `.json`: the report for every archived log, **kept forever**.
- `raw/<logname>`: the original log, deleted after the raw retention.
- `screenshots/…`: archived screenshots, deleted after their retention.

```
python3 tools/logs/frens_log_prune.py            # dry-run plan + one-line summary (default)
python3 tools/logs/frens_log_prune.py --verbose  # list every file
python3 tools/logs/frens_log_prune.py --apply    # act
```

Safety rules:

- Without `--apply` it only plans.
- It never touches `latest.log`, any file modified in the last 24 h (`min_age_hours`, which
  overrides any retention), or symlinks (and it never follows them).
- It refuses `crash-reports/`, `saves/`, `mods/`, `config/`, `backups/`, and any path whose
  realpath is outside the configured instance dirs and the archive root. It also refuses an
  archive root that overlaps an instance dir, and it never overwrites an existing archive file.
- Each digest is written (atomically) before its log moves. If the digest fails, the log
  stays where it is.
- Any error gives exit code 1. A file archived in this run is deleted on a later run if it is
  already past the raw retention.

### launchd (not installed; needs Bradley's OK)

`com.bradley.frens-log-prune.plist.example` runs `/usr/bin/python3 …/frens_log_prune.py --apply`
every day at 11:15. It logs to `frens-log-archive/prune.log`. The script path in it is the
main checkout (`/Users/roti/AI-Player-checkpoint/tools/logs/`), so merge this first. launchd
does not create the log's folder and does not expand `~`.

```sh
mkdir -p "$HOME/Library/Application Support/PrismLauncher/frens-log-archive"
cp tools/logs/com.bradley.frens-log-prune.plist.example ~/Library/LaunchAgents/com.bradley.frens-log-prune.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.bradley.frens-log-prune.plist
launchctl kickstart -p gui/$(id -u)/com.bradley.frens-log-prune      # run once now
launchctl print gui/$(id -u)/com.bradley.frens-log-prune | grep -i "last exit code"   # must be 0
tail -20 "$HOME/Library/Application Support/PrismLauncher/frens-log-archive/prune.log"
```

To remove it (reversible): `launchctl bootout gui/$(id -u)/com.bradley.frens-log-prune`,
`launchctl disable gui/$(id -u)/com.bradley.frens-log-prune`, then move the plist to
`~/Library/LaunchAgents/_disabled-<date>/`.
