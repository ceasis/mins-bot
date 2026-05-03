---
name: watch-disk
description: 'Watch a disk volume''s free space and alert when it drops below a GB threshold. Trigger on "alert when C drops below 10GB", "warn me about low disk", "monitor free space on /Users/me".'
metadata:
  minsbot:
    emoji: "💾"
    os: ["windows", "darwin", "linux"]
---

# Watch Disk

Polls free space and fires email + optional webhook on the OK→LOW flip (and once again on recovery). Edge-triggered so it doesn't spam every tick while still low.

## Steps
1. Parse: **Label**, **Path** (drive letter on Windows like `C:\`, mount point on Unix), **freeBelowGb** threshold, **Notify email**, optional **Webhook URL**, **Interval** (seconds, default 300, min 60).
2. POST to `/api/skills/watch-disk`.
3. Confirm with watcher ID and current free / total GB.

## Guardrails
- Reject if the path's volume returns 0 total bytes (likely an invalid path).
- Don't set thresholds within 1 GB of current free — it'll fire immediately.
