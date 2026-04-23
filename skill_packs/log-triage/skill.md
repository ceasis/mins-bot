---
name: log-triage
description: Scan a log file or folder for errors, group similar messages, identify the earliest occurrence of each, and produce a triage summary with likely root causes. Trigger on "triage this log", "find errors in {path}", "what broke in my log", "summarize this log", "why is my app crashing".
metadata:
  minsbot:
    emoji: "🚨"
    os: ["windows", "darwin", "linux"]
---

# Log Triage

For when your app's producing 10,000 lines of stderr and you need to know "what's actually wrong" in 30 seconds.

## Steps

1. Resolve the target path from the user's message. Accept a file, a directory (recurse), or a glob like `*.log`. If ambiguous, ask.
2. Call `readTextFile` (or read the file via `getFileInfo` + content) or `listDirectory` + `readTextFile` (or read the file via `getFileInfo` + content) per file. Cap total content at 2 MB; if bigger, tail the last 2 MB.
3. Extract candidate error/warn lines with this regex pattern family:
   - `\bERROR\b`, `\bSEVERE\b`, `\bFATAL\b`
   - `\bWARN(?:ING)?\b`
   - Stack-trace signatures: `Exception`, `Error:`, `at .*\(.*\.java:\d+\)`, `Traceback (most recent call last)`
4. Cluster similar messages — strip timestamps, thread IDs, memory addresses, and UUIDs to normalize, then group by normalized form. Count occurrences per cluster.
5. For each cluster, capture:
   - Earliest timestamp seen
   - Latest timestamp seen
   - Count
   - Representative raw line (uncollapsed)
   - Any associated stack trace (the next 5–10 indented lines after the line)
6. Rank clusters by count × recency (recent+frequent first).
7. Produce the triage report:
   ```
   ── Log Triage: <path> ──
   Scanned N lines across M files. Found K distinct error/warn clusters.

   1. [COUNT×] First seen <time>, last seen <time>
      "<representative line>"
      Likely cause: <1-line inference from the message + stack trace>

   2. ...
   ```
8. Close with ONE paragraph of "what to look at first" — the top-ranked cluster's most likely fix.

## Guardrails

- Never follow symlinks out of the target directory.
- If you detect secrets (API keys, passwords in the log), redact them in the output — don't surface raw.
- Cap the summary to the top 8 clusters; if more, add "+ N more clusters — increase limit to see".
- If no error lines found, say "No errors or warnings detected" rather than inventing issues.
