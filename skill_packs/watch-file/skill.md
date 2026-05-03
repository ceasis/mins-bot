---
name: watch-file
description: Watch a single file for change (modified time, content hash, or regex match) and email/webhook-notify. Trigger on "watch this file", "alert me when X changes", "ping me if /path/to/file is modified", "watch [path] for [pattern]".
metadata:
  minsbot:
    emoji: "📄"
    os: ["windows", "darwin", "linux"]
---

# Watch File

Polls a single file and fires email + optional webhook when it changes. Three modes:

- **mtime** (default, cheapest) — fires when the file's last-modified time advances. Good for "tell me when this report drops".
- **hash** — SHA-256 of the contents; fires when the hash flips. Catches rewrites that touch and revert content (mtime would miss those).
- **regex** — fires when the contents match (or stop matching) a regex pattern. Good for "tell me when this log file contains 'ERROR'".

## When to use

Trigger when the user says:
- "watch [path] for changes"
- "ping me when [file] is modified"
- "alert me if [file] contains [pattern]"
- "let me know when [file] gets updated"

## Steps

1. Parse:
   - **Label** — human-readable.
   - **Path** — absolute file path. Reject and ask for clarification if relative or directory.
   - **Mode** — `mtime` unless the user mentioned content matching (then `regex`) or precise change detection (`hash`).
   - **Pattern** — regex when mode=regex.
   - **Notify email** — required.
   - **Interval** — seconds between checks. Default 60. Min 30.
   - **Webhook URL** — optional.
2. POST to `POST /api/skills/watch-file` with the parsed fields.
3. Confirm with watcher ID and what will fire the alert.

## Guardrails

- Never set interval below 30 s — the service rejects it.
- For `hash`/`regex` mode, the file is read every tick — warn the user if the file is huge (over a few MB).
- For `regex` mode, sanity-check the pattern compiles before saving.
