---
description: Draft a dev-done.md entry for the slice that just shipped, matching the project's established structure
argument-hint: [optional short title hint, e.g. "BOMs read-only viewer"]
---

Update `docs/dev-done.md` (and optionally `docs/dev-todo.md`) for a slice that just shipped. The project maintains a paired backlog/changelog discipline — items move from dev-todo when they ship, and dev-done is append-only chronological.

Workflow:

1. **Inspect the work.** Read `git status` + `git diff` (or `git diff <last-commit>..HEAD` if mid-branch) to understand what changed. If uncommitted work isn't staged but is fresh in the conversation, use that context instead. The point is to know: which files changed, which were created, which deleted, and what the user-visible behaviour change is.

2. **Match the format.** Read the last 2-3 entries in `docs/dev-done.md` to match the established shape. Today's entries (`2026-05-13`) are good exemplars. The shape is:

   ```
   ## YYYY-MM-DD — <short title>

   <One paragraph of context: what was broken, what trigger surfaced it,
   or what the slice's purpose is. Mention any subagent delegation,
   memory rules that applied, or relevant prior dev-done entries.>

   ### Shipped

   - **<area>**: <concrete change with file path>. <Rationale or follow-on
     effect if non-obvious.>
   - …

   ### Smoke

   - `<command>` <result>.
   - Live behaviour confirmed: <what was tested manually>.

   ### Follow-ups (optional)

   - <Items noticed at time of shipping, queued for later>.
   ```

   Sub-sections like "Out of scope" or "Lesson" are fine when the slice warrants them — see the 2026-05-13 schema-rebase entry for an example.

3. **Draft the entry.** Insert it above the most recent existing entry (newest-first ordering — line 7 of dev-done.md is the insertion point, right after the `---` separator). Use the optional `$ARGUMENTS` as the title seed if provided; otherwise infer from the changes.

4. **Reconcile dev-todo.** Grep `docs/dev-todo.md` for any open items whose subject matches the slice (e.g. matching §number, matching keywords). Offer to:
   - Mark them ✅ COMPLETE inline with a date + cross-reference to the new dev-done entry, or
   - Remove them entirely if the slice fully resolves the item.
   Don't auto-edit dev-todo; show the proposed change and let the user accept.

5. **Don't commit.** The output is the dev-done draft + the dev-todo proposed change. The user reviews and decides when to commit.

Project conventions to honour (from auto-memory and CLAUDE.md):
- Capture debugging lessons inline if the slice involved real debugging (auto-memory: `feedback_capture_lessons`).
- Keep historical entries intact — never rewrite past dev-done entries, even if their narrative is dated.
- When the slice resolves a previously-logged follow-up, cross-reference both directions (the new entry mentions the resolved follow-up by ID; the old follow-up gets a ✅ resolved marker).
