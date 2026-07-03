---
name: HammerScale de-AI-ification cleanup
description: What "make the repo look human-authored" meant in practice for this project, in case similar requests recur.
---

The user's "remove AI-sounding comments" request meant: KDoc blocks (`/** ... */`) with exhaustive field-by-field protocol documentation, and ASCII section-divider comments (`// ── Something ──────`). Plain short `//` comments explaining non-obvious logic were fine to keep, just without banner formatting or KDoc structure.

Regex-based bulk removal was explicitly forbidden — every removal had to go through the `edit` tool by hand, file by file, verifying via the returned `cat -n` snippet that indentation/structure stayed correct.

`.replit` cannot be deleted or edited directly by the agent (platform-protected file) even when a user asks to strip all Replit-specific files — this is a hard tool limitation, not a judgment call. `attached_assets/` and other plain tracked files/dirs can be removed normally with `rm -rf`.

**Why:** the user wanted the GitHub repo to read as fully human-written with no trace of AI tooling, but `.replit` is owned by the platform's own protection layer, not something `rm`/`edit` can touch.
