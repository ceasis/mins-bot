---
name: tweet-thread
description: Convert a long piece of text (article, thought, essay) into a 5–10-tweet thread — hook first, punchy middle, strong close, each tweet under 280 chars. Trigger on "turn this into a thread", "tweet thread", "x thread", "threadify this".
metadata:
  minsbot:
    emoji: "🧵"
    os: ["windows", "darwin", "linux"]
---

# Tweet Thread

## Steps

1. Get the source text — inline, file path, clipboard, or URL.
2. If the source is a URL, fetch + extract the main content.
3. Run this LLM prompt:
   ```
   Adapt the given text into a Twitter/X thread:
   - First tweet is the hook. It must stand alone — someone scrolling past should want to read more.
   - 4–8 middle tweets, each one idea, each under 280 characters (prefer 240 for safety).
   - Last tweet is the close — a takeaway or a CTA, not a "follow me" beg.
   - Number each tweet like 1/, 2/, 3/.
   - Plain prose. No hashtags, no emojis unless the source uses them.
   - Preserve any direct quotes verbatim.
   ```
4. Print the thread, one tweet per block, with character counts per tweet so the user can see they're safe.

## Guardrails

- Never exceed 280 chars — count precisely, not estimate.
- Don't add hashtags or @ mentions the source didn't have.
- If the source is too short to justify a thread (< 400 words), tell the user — a single post may be better.
