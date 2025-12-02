# Agent Rules

Repository rules for any LLM agent working here.

---

## 1) Starting Context Each Session

1. Read `TODO.md` for the current, authoritative task list (only pending work lives there).
2. Skim `changelog.md` for historical context and reasoning; do not add new tasks there.
3. Use `README.md` for how to run/use the mod and `file_index.md` to find code quickly.
4. Check git status/last commits for local changes; avoid redoing completed work.

---

## 2) Task Selection & Scope

- Work on exactly one clearly bounded task at a time; finish or reach a clean stop before switching.
- Keep edits minimal and related; avoid mixing unrelated refactors.
- If unclear about intent or side effects, state assumptions in your notes and proceed conservatively.

---

## 3) Build & Validation

When builds fail or warnings appear:
1. Tackle one error/warning at a time (prioritize the first real failure and core gameplay issues).
2. Find the root cause, propose a minimal fix, and run/describe the relevant check.
3. Once resolved, summarize and propose the next check before proceeding.

---

## 4) Documentation Updates

After code or config changes:
- Update `TODO.md` (pending items only) and mark anything finished.
- Add a concise entry to `changelog.md` for history and rationale.
- Do **not** revive archived docs (`gemini_report_3.md`, `old_changelogs.md`, etc.).

---

## 5) Code Editing Expectations

- Prefer surgical patches over wide rewrites; preserve working behavior unless there is a clear reason to change.
- Check usages before modifying functions; keep diffs reviewable (avoid mixing style-only tweaks).
- Use clear comments only when the intent is non-obvious.

---

## 6) Wrap-Up Checklist

- Summarize what you changed and where; note completion vs follow-up needed.
- Confirm `TODO.md` and `changelog.md` are updated for this work.
- Suggest the logical next task only if appropriate; stop and wait for confirmation before moving on.
