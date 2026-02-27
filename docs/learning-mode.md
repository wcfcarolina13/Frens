# Learning Mode (v1)

Generalized demonstration capture for tuning bot controls from human play sessions.

## Quick start

- Arm a preset:
  - `/bot learn arm goal=pillaring_to_roof profile=construction detail=balanced pair=Jake`
- Start capture:
  - `/bot learn start`
  - or Bot Inventory → `Admin` → `Learning start`
- Mark moments during a run:
  - `/bot learn mark label=fall_off note=slipped_before_step_back`
- Stop capture:
  - `/bot learn stop success` (or `fail`, `abort`)
  - or Bot Inventory → `Admin` → `Learning stop (...)`

## Files

Sessions are written to:

- `config/frens/learning/<worldKey>/<sessionId>/session.json`
- `config/frens/learning/<worldKey>/<sessionId>/events.jsonl`
- `config/frens/learning/<worldKey>/<sessionId>/summary.json`

## Notes

- v1 is **record + analyze only** (no live imitation / model training).
- Admin menu uses the **armed preset** when available.
- If no preset is armed, admin start falls back to `goal=manual_demo` and uses the current bot alias as the default pair.
