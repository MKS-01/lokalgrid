---
name: build-log
description: Append a dated entry to BUILDLOG.md recording what was tried this session, what surprised you, and what's next. Run at the end of a working session.
disable-model-invocation: true
---

Append an entry to `BUILDLOG.md` at the repo root (create the file with a `# Build log` heading if absent). Newest entries go at the top, under the heading.

If the user passed arguments, treat `$ARGUMENTS` as their own notes and build the entry around them. Otherwise reconstruct the session from the conversation and the working tree — do not interview the user for details you can already see.

Format:

```markdown
## YYYY-MM-DD — <short title>

**Tried:** what was attempted, and whether it worked.

**Surprised:** what didn't behave as expected — wrong assumptions, datasheet lies, tooling weirdness. This is the highest-value line; if nothing surprised you, write "nothing".

**Next:** the concrete next action, specific enough to resume cold.
```

Rules:

- One entry per session. If today's date already has an entry, extend it rather than adding a second.
- Record the broken state honestly. "Scheduler confused, credits go negative after the third client" is a better entry than a clean summary.
- Name the phase from `PROJECT.md` section 7 when the work maps to one.
- Keep it to a few lines each. This is a re-entry aid, not a report.
- Show the user the entry after writing it.
