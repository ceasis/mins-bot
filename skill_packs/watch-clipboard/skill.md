---
name: watch-clipboard
description: Fire when the system clipboard content changes (any) or matches a regex (specific). Trigger on "tell me when I copy a tracking number", "alert me when I copy an email address", "watch clipboard for [pattern]".
metadata:
  minsbot:
    emoji: "📋"
    os: ["windows", "darwin", "linux"]
---

# Watch Clipboard

Polls the JavaFX system clipboard. Two modes:

- **Empty pattern** — fires on ANY clipboard change. Useful for logging or capture workflows.
- **Non-empty regex** — fires only when the new clipboard content matches the pattern. Useful for triggers like UPS tracking numbers (`\b1Z[0-9A-Z]{16}\b`), email addresses, IBANs, etc.

## Steps
1. Parse: **Label**, optional **Pattern** (regex; empty = any change), **Case-insensitive** (default true), **Notify email**, optional **Webhook**, **Interval** seconds (default 5, min 2).
2. POST to `/api/skills/watch-clipboard`.
3. Confirm with watcher ID.

## Guardrails
- The clipboard read happens on the JavaFX Application Thread; if the bot's UI isn't running, the watcher silently no-ops (no JVM clipboard fallback).
- Don't set interval < 2s — diminishing returns and FX-thread contention.
