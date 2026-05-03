---
name: watcher
description: Watch a product page (Nike PH or generic HTTP) and email/webhook-notify when a target size is in stock or the price drops. Trigger on "watch this Nike URL", "ping me when X is in stock", "set up a stock watcher for Y", "monitor this product for size Z".
metadata:
  minsbot:
    emoji: "👁️"
    os: ["windows", "darwin", "linux"]
    playwright:
      # Nike PH page checks run via Playwright. Default false = headless so
      # the watcher can poll quietly in the background. Flip to true if you
      # want to watch the browser drive the page during debugging.
      show-browser: false
---

# Watcher

Periodically polls product URLs and notifies (email + optional webhook) when a target size flips in-stock or the price drops below a ceiling. Two adapters today: `nike-ph` (Playwright-driven for Nike Philippines product pages) and `generic-http` (regex match on the fetched HTML, suitable for plain pages).

## When to use

Trigger this skill when the user says:
- "watch this Nike URL for size 9.5"
- "ping me when [URL] is back in stock"
- "set up a stock watcher for [product]"
- "monitor [URL] and email me when price drops below $X"

## Steps

1. Parse the user's request into the watcher fields:
   - **Label** — human-readable (e.g. "Jordan 11 Gamma size 9.5").
   - **Product URL** — the page to monitor.
   - **Adapter** — `nike-ph` if the URL contains `nike.com/ph/`; otherwise `generic-http`.
   - **Target** — for `nike-ph`, the shoe size (e.g. `9.5`). For `generic-http`, a regex pattern to match against the HTML.
   - **Notify email** — required.
   - **Interval** — seconds between checks. Default 900 (15 min). Min 60.
   - **Max price** — optional ceiling; only fires when actual ≤ this. 0/empty = ignore price.
   - **Webhook URL** — optional Discord / ntfy.sh / Pushover for instant phone push.
2. POST the watcher to `POST /api/skills/watcher` with the parsed fields. Confirm the watcher appears in `GET /api/skills/watcher`.
3. Reply with the watcher ID, the polling interval in human form, and what will fire the alert.

## Guardrails

- Never set an interval below 60 seconds — that's anti-social toward the target site and the watcher service rejects it.
- For `generic-http`, sanity-check the regex against the live page once before saving so the user doesn't end up with a watcher that can never match.
- If the user asks to watch dozens of URLs, suggest grouping by site or using a single page that lists multiple SKUs — many watchers polling the same domain is rude.

## Notes

- Watchers persist across restarts (stored under `~/mins_bot_data/memory/watchers/`).
- The Watcher tab in the web UI is the canonical management surface; this skill is for natural-language creation.
