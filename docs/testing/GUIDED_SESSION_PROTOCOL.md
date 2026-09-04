# Guided field session — protocol

Bradley plays one long continuous session; Claude reads the server log in near-real time and
directs. Goal: a big bundle of feedback per game launch instead of restart churn. The checklist
for a given build lives beside this file (`FIELD_SESSION_<version>.md`).

## Channels

| Direction | Channel | Notes |
|---|---|---|
| Claude → Bradley | Claude desktop chat (second screen / phone) | Short imperative lines: "stand still 90 s", "punch the oak with no axe". |
| Bradley → Claude (remarks) | In-game `/me <note>` | Lands in `latest.log` as `* <name> <note>` via the COMMAND path, so the soul routers never see it (plain chat is a `CHAT_MESSAGE` and the local-chat director would score it). Verify in the first minute of the session. |
| Bradley → Claude (verdict) | `/me ok <item>` / `/me fail <item> <why>` | Claude copies these into the feedback bundle verbatim. |
| Bradley → bots | Normal chat | Logged as `[Not Secure] <name> text`; addressed vs unaddressed is decided by the router as usual. |

## What Claude watches

- `latest.log` at `<instance>/minecraft/logs/latest.log`, tailed with a grep filter per phase
  (`[souls]`, `verdict`, `vetoed:`, `memory digest`, `[torch-hold]`, `[creeper]`,
  `Idle wooden fallback`, `ERROR`, `Exception`). Content of soul prompts is never in the log;
  only ids, outcomes and counts — that is by design.
- Soul state on disk under `<world>/frens/souls/v1/<bot>/` (`soul.json`, `mind.json`,
  `events.jsonl`, `conversations/<player>/active.jsonl`) and `<world>/frens/party/v1/<owner>/`.
  Read-only during play; useful right after a day rollover or a reset.
- Screenshots only on request or when the log says something happened worth seeing — they are
  slow and token-heavy.

## Cadence

1. Setup phase once (Phase 0 of the checklist). Confirm each toggle from its log line before
   moving on.
2. Calm tests before noisy ones; day-boundary tests near the end; destructive resets last.
3. After every item Claude writes one row to the feedback bundle (time, item, verdict, log line,
   Bradley's remark). Anomalies get a row immediately, tagged `!`.
4. When the game closes, the bundle is already the autopsy draft: fixes get grouped into one
   build, the checklist items flip, and `changelog.md` gets the autopsy entry as before.

## Limits to remember

- Claude's reaction time is 5–15 s per observation: a director, not a co-pilot for anything twitchy.
- Audio quality, pacing feel and "is this charming or annoying" are Bradley's calls — say them
  with `/me`.
- Bystander/second-player tests need a second human or a Carpet fake player (not set up yet).
- Never hot-swap the JAR while the game runs. Fixes wait for the game to close.
