---
name: watch-http
description: Uptime monitor — alert when a URL stops returning 2xx/3xx (down) or comes back (up). Optional latency threshold. Trigger on "watch this URL for uptime", "alert me when api.example.com goes down", "monitor [URL]".
metadata:
  minsbot:
    emoji: "🌐"
    os: ["windows", "darwin", "linux"]
---

# Watch HTTP

GETs a URL on a schedule. Alerts (email + optional webhook) on up→down and down→up flips. Optional `slowAboveMs` threshold fires a separate "slow" alert when the page responds but is dragging.

## Steps
1. Parse: **Label**, **URL** (must start with `http://` or `https://`), **Notify email**, optional **Webhook**, **Interval** (default 300s, min 60s), optional **slowAboveMs** (0 = disabled), optional **followRedirects** (default true).
2. POST to `/api/skills/watch-http`.
3. Confirm with watcher ID, current state (HTTP code + latency).

## Guardrails
- Don't poll faster than 60s — most uptime services charge for sub-minute and the OS rate-limits anyway.
- HEAD would be cheaper but plenty of sites 405 it; we use GET with `discarding()` body handler so we don't burn bandwidth.
