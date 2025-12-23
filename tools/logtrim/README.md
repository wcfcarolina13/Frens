# logtrim

A small standalone tool to trim Minecraft/Fabric `latest.log` files down to the parts that matter for debugging **AI-Player-Checkpoint**.

It preserves:
- `ERROR` lines (and nearby context)
- AI-Player / bot / NLP / model-loading / skill-task lifecycle messages
- warnings that look actionable for AI-Player

It collapses known high-volume noise such as:
- per-step movement spam (`Movement execute`, `Movement choosing walk only`)
- shaderpack/Iris/Sodium shader-link spam (`Program link log...`, `Info log when linking...`)

Output is written into the repo’s `Logs-Prism/trimmed/` folder by default.

By default it produces **two** files:
- `latest.trimmed.log` (no-shaders): omits/collapses shader-link spam
- `latest.trimmed.with-shaders.log`: keeps shader-link logs for graphics debugging

## Requirements

- Python 3 (no third-party packages)

## Usage

Run from the repo root:

- Trim the most recent PrismLauncher instance log (auto-detected on macOS), output to both variants in `Logs-Prism/trimmed/`:

  - `python3 tools/logtrim/logtrim.py`

### Watch mode (auto-run on updates)

If you tend to **replace** `latest.log` while iterating (copy/paste, download, overwrite, etc.), use watch mode:

- Watch the chosen input file and re-trim whenever it changes:

  - `python3 tools/logtrim/logtrim.py --watch`

Common workflow: copy a Prism `latest.log` into `Logs-Prism/latest.log` in the repo, then run:

- `python3 tools/logtrim/logtrim.py --input Logs-Prism/latest.log --watch`

Notes:
- Watch mode is polling-based (no external dependencies).
- `--settle-time` controls how long the file must stay unchanged before trimming (helps avoid trimming partial writes).

### Watch all Prism instances (multi-instance)

If you use **multiple PrismLauncher instances**, you can watch *all* of them at once. This monitors:

- `~/Library/Application Support/PrismLauncher/instances/*/minecraft/logs/latest.log`

And writes outputs into your repo under `Logs-Prism/` (easier to grab/share):

- `Logs-Prism/instances/<instance>/trimmed/latest.trimmed.log`
- `Logs-Prism/instances/<instance>/trimmed/latest.trimmed.with-shaders.log`

Run:

- `python3 tools/logtrim/logtrim.py --watch-prism-all`

If you want to run it **just once** (no watcher), use:

- `python3 tools/logtrim/logtrim.py --prism-all-once`

Optional: override the Prism instances root:

- `python3 tools/logtrim/logtrim.py --watch-prism-all --prism-instances-root "/path/to/PrismLauncher/instances"`

Optional: override where outputs are written (default is this repo’s `Logs-Prism/`):

- `python3 tools/logtrim/logtrim.py --watch-prism-all --multi-output-root "/some/folder"`

## macOS: double-click helpers

If you prefer a “just double-click it” workflow, there are two helpers in `tools/logtrim/`:

- `Trim Prism Logs.command` — trims **all** Prism instances once, then exits.
- `Watch Prism Logs (background).command` — runs the watcher; leave the Terminal window open while you play.

They should work via double-click in Finder (macOS will open Terminal). If macOS blocks it, right-click → Open.

- Trim a specific file:

  - `python3 tools/logtrim/logtrim.py --input "/path/to/latest.log"`

- Change output file:

  - `python3 tools/logtrim/logtrim.py --output "Logs-Prism/trimmed/my_session.trimmed.log"`

  This will also write `Logs-Prism/trimmed/my_session.trimmed.with-shaders.log`.

- Override the with-shaders output path explicitly:

  - `python3 tools/logtrim/logtrim.py --output-with-shaders "Logs-Prism/trimmed/my_session.with-shaders.log"`

## Notes

- The trimmer groups continuation lines (those without the usual `[time] [thread/LEVEL]:` prefix) with the record that preceded them.
- The output includes `[TRIMMED] ...` markers to indicate where large blocks of spam were collapsed.
