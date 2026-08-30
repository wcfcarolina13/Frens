# Frens soul voice: Pocket TTS engine + in-game installer — design (2026-08-29)

## Why

Piper is the CPU fallback voice and it sounds robotic. Bradley asked for something less
robotic "that doesn't cost any more in CPU", installable from inside the game like Piper and
the Ollama models. Spike on this M2 Pro (scratch venv, `pocket-tts 3.0.2`, torch 2.13 CPU):

| | Pocket TTS | Piper (today) |
|---|---|---|
| CPU cost (Picovoice 2026 core-hour ratio) | 0.37x | 0.35x |
| Measured here | 8 s of audio in 1.0–1.6 s (5–7× real time), torch pinned to 1 thread | — |
| Resident memory (server) | ~1.0 GB | small |
| Output | 24 kHz mono 16-bit WAV — exactly what `SoulVoicePcm.parseWav` requires | 22.05 kHz |
| Cold start | ~5 s model load → must be a warm server, not a per-line process | per-line process |
| Voices | 20 English presets (alba, anna, azelma, bill_boerst, caro_davy, charles, cosette, eponine, eve, fantine, george, jane, javert, jean, marius, mary, michael, paul, peter_yearsley, stuart_bell, vera) + fr/de/it/es/pt | 7-entry catalog |
| Cloning | supported, but weights are HF-gated (accept terms at huggingface.co/kyutai/pocket-tts + `hf auth login`) — the spike's clone from Jake's ref clip failed on exactly that gate | no |
| License | MIT (code) / Apache-2 (weights) | MIT |

Samples to listen to: `voices/ab-test/pocket/out-{charles,paul,george}.wav` (same line the
Piper voices speak in the field).

## Scope (three phases, ≤5 files each; approval between phases)

### Phase A — engine + settings (no UI)
1. `voice/PocketVoiceEngine.java` — implements `SoulVoiceEngine`. Owns one
   `<installDir>/venv/bin/pocket-tts serve --host 127.0.0.1 --port <free port>` subprocess
   (same lifecycle discipline as `DreamsleeveVoiceEngine`: single engine thread, stdout/stderr
   drained on daemon threads, non-blocking idempotent `close()`, `alive()` = not closed).
   Startup: poll `GET /health` up to 20 s, then fire-and-forget warm-up `"Ready."`.
   Synthesis: `POST /tts` (form `text`, `voice_url=<preset name>`) → WAV bytes; response is
   already 24 kHz/mono/16-bit, returned as-is. Any exception → kill process + fail the future
   (backoff ladder in `SoulVoiceService` unchanged). Env `OMP_NUM_THREADS=1` for the child —
   Pocket already pins torch to one thread; this keeps MKL/OpenBLAS honest too.
2. `SoulVoiceSettings` — new engine id `"pocket"` (`ENGINE_POCKET`), fields
   `pocketDir` (install root, default `<gameDir>/config/frens/pocket-tts`) and `pocketVoice`
   (default preset when a bot has no assignment; default `charles`). `validationErrorFor`
   gets a `case "pocket"` (venv binary must exist). Constructor churn handled with a
   compact overload so `DISABLED` and the two fallbacks stay one-liners.
3. `ManualConfig` — `soulVoicePocketDir`, `soulVoicePocketVoice` + getters/setters.
4. `SoulRuntime.buildVoiceService` — `case ENGINE_POCKET -> new PocketVoiceEngine(...)`;
   the `default` arm becomes an explicit `ENGINE_PIPER` case and unknown ids log + disable
   (today a typo silently means Piper — friction item 4 of the survey).
5. `SoulTypes.VoiceSpec` — rename `piperModel/piperSpeaker` → `voice/speaker` (engine-
   interpreted: Piper = onnx model name, Pocket = preset name, Dreamsleeve ignores it);
   `refAudio/refText` unchanged. Profile JSON and `ManualConfig.SoulVoiceAssignment` keep
   accepting `piperModel` as an alias on read and write `voice`. Callers: `SoulRuntime.toSpec`,
   `SoulProfileRegistry` parser, `BotSoulCommands.describeSpec`, `PiperVoiceEngine`.
   Tests: `PocketVoiceEngineTest` (request/response against a stub HTTP server on
   localhost), `SoulVoiceSettingsTest` (+pocket case), `SoulVoiceSpecTest` (alias).

### Phase B — installer + screens
1. `voice/PocketInstaller.java` — mirrors `PiperInstaller`: `detect() → Plan` (python
   runtime found?, install dir, existing install, free disk ≥ 2 GB, network), `installAsync`
   via the shared `InstallJob` with `ACTIVE_JOB` guard, `activeJob()/clearFinishedJob()`.
   Runtime resolution order: `uv` on PATH/homebrew (`uv venv --python 3.12 <dir>/venv` — uv
   fetches an interpreter itself, so no system-python dependency) → else first python ≥3.10
   among `/opt/homebrew/bin/python3.1x`, `/Library/Frameworks/Python.framework/Versions/3.1x/bin/python3`,
   `python3` on PATH (`/usr/bin/python3` is 3.9 on this Mac and is rejected) → else FAILED
   with a one-line instruction. Install: `pip install pocket-tts==3.0.2` (pinned; pip/uv
   output streamed to the job stage text — no byte totals exist, so the bar is stage-based:
   venv → deps (~850 MB) → model warm-up (228 MB HF download, triggered by the smoke test)
   → smoke test (one real line through `PocketVoiceEngine`) → write config
   (`engine=pocket`, dir, voice) → `SoulRuntime.reloadSettings`.
2. `GraphicalUserInterface/PocketInstallerScreen.java` — clone of `PiperInstallerScreen`
   (DETECTING/READY/FAILED phases, background detect, job re-attach, progress bar).
3. `SoulVoiceEngineScreen` — rewritten from two hardcoded buttons to a loop over an
   `EngineRow(id, label, available, description, installer)` list; popup height grows with
   rows. Pocket's row doubles as "Install Pocket TTS…" when absent. Cloning status line:
   "Voice cloning: needs Hugging Face login" / "available" (detects `~/.cache/huggingface/token`).
4. `SoulVoiceSettings`/`ManualConfig` — nothing new; Phase A already carries the keys.

### Phase C — catalog + commands
1. `BotSoulCommands` — `/bot soul voice list|install|assign` become engine-aware: `list`
   shows the active engine's catalog (Pocket: the 20 presets, all "installed" once the engine
   is); `assign` validates against the active engine (today it calls Piper's model resolver
   unconditionally — survey friction 8); `install <name>` is a no-op success for Pocket
   presets (they download on first use) and stays Piper's per-voice download otherwise.
2. `voice/VoiceCatalog.java` — one place for both engines' catalogs (Piper's 7 hardcoded
   entries move here; Pocket's 20 names with a gender/age hint each) so the screen and the
   commands stop reaching into `PiperInstaller.VOICE_CATALOG`.

Out of scope now: Pocket voice cloning (gated weights; revisit once Bradley decides whether
to log in to HF on this machine — Jake keeps his Dreamsleeve clone), non-English models,
Supertonic/Kokoro engines (same `SoulVoiceEngine` seam; evaluate after Pocket is heard in
the field).

## Testing
Unit: engine request/response + failure paths against a localhost stub; settings validation;
spec alias parsing; installer plan logic with a fake filesystem/runtime probe. Manual:
fresh install from the in-game screen on this Mac (uv path) and with uv hidden (python3.12
path); Bob + Jake on two Pocket presets in a two-bot scene; LoadGoverner telemetry during a
scene (expect no stage-floor difference vs Piper).
