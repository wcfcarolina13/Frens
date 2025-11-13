# Gemini – Project Rules

This file defines how Gemini Code Assist should work in this repository.

---

## 1. Starting Context Every Session

When you begin work in this project:

1. Read `gemini_report_3.md` to understand:
   - Current status
   - Open tasks
   - Known issues and recent decisions

2. Inspect recent Git changes for context:
   - Check the last few commits and/or pending changes in source control.
   - Do **not** re-solve problems that have already been fixed or intentionally deferred.

3. Treat `gemini_report_3.md` and `changelog.md` as the **source of truth** for what has been done.
   - The IDE’s chat window is **not reliable history**.
   - Assume prior chat messages may be hidden or lost.

---

## 2. Task Selection and Scope

- Always work on **exactly one** well-defined task per response.
- Finish that task or reach a clear stopping point before doing anything else.
- At the end of the response:
  - Stop.
  - Suggest one or two logical follow-up tasks.
  - Explicitly ask whether to proceed.

Examples of valid task scopes:
- Fix a single compiler error or warning.
- Implement a single method or class.
- Refactor a single, clearly bounded behavior (e.g., one skill, one command handler).

Avoid:
- Large, multi-file refactors in a single step.
- Changing unrelated systems in the same response.

---

## 3. Build Errors and Warnings

When there are build problems:

1. Focus on **one error or warning at a time**.
2. For the selected issue:
   - Locate the root cause.
   - Propose a minimal, clear fix.
   - Run or describe the relevant build/test command to validate the fix.
3. When the build succeeds or the targeted error is resolved:
   - Summarize what you changed.
   - Suggest the next error or warning to address.
   - Ask if you should continue.

If build output suggests a cascade of errors, prioritize:
- The **first** real error in the chain.
- Errors in core gameplay / bot logic over peripheral code.

---

## 4. Documentation Requirements

After making any code or config change, always:

1. **Update `gemini_report_3.md`:**
   - Add a short entry describing:
     - The task you worked on.
     - The files you touched.
     - The outcome (fixed, partially fixed, investigation only, deferred, etc.).

2. **Update `changelog.md`:**
   - Add a concise, dated line item:
     - What changed.
     - Why it changed (one sentence).
     - Any important follow-up notes.

Both files must stay in sync with your edits.  
Do **not** rely on chat history to reconstruct changes.

---

## 5. Code Editing Expectations

When editing code:

- Prefer **small, surgical patches** over wide rewrites.
- Preserve working behavior unless there is a clear reason to change it.
- Before modifying an existing function:
  - Check its usages.
  - Look for tests or call sites that describe its intent.
- When you propose changes, make them easy to review:
  - Keep diffs focused.
  - Avoid mixing style-only edits with functional changes.

If you are unsure about intent or side effects:
- State your assumptions in the report entry.
- Suggest alternative approaches, but implement only one.

---

## 6. Ending Each Response

At the end of every response where you edit or propose code:

1. Provide a brief summary:
   - What you did.
   - Which files you touched.
   - Whether the task is complete or needs follow-up.
2. Confirm that:
   - `gemini_report_3.md` and `changelog.md` entries are up to date for this work.
3. Suggest the next most logical task.
4. Ask explicitly: **“Do you want me to continue with this next task?”**