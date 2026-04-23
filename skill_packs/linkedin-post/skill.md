---
name: linkedin-post
description: Draft a LinkedIn post with a strong hook, 2–3 short paragraphs, a takeaway, and a soft CTA. Trigger on "linkedin post for X", "write a linkedin update", "draft a li post", "professional post about Y".
metadata:
  minsbot:
    emoji: "💼"
    os: ["windows", "darwin", "linux"]
---

# LinkedIn Post

Different voice from Twitter — more "professional story", less "take". Plays to the algorithm's preferences (line breaks, specific hook patterns).

## Steps

1. Get the topic or raw content from the user.
2. Ask (if not obvious) what the user wants out of it:
   - Announcement (new job, launched a thing)
   - Insight (what I learned from X)
   - Question (asking the network)
   - Story (client win, failure retrospective)
3. Run this LLM prompt:
   ```
   Write a LinkedIn post:
   - Hook line (first sentence) that creates curiosity or tension. No "I'm excited to announce".
   - 2–3 short paragraphs (1–3 lines each). Blank line between paragraphs — LI rewards whitespace.
   - A concrete takeaway the reader can act on.
   - Optional soft CTA at the end (a question, not "follow me").
   - Under 1300 chars (LI's truncation point) — aim for 900.
   - First person. No hashtag dumps; 2–3 relevant hashtags at the very end or none.
   - No emojis unless the topic calls for one.
   ```
4. Show character count. Offer a shorter variant for visual scanability if over 1100 chars.

## Guardrails

- Never write in the "thought leader" voice ("Here's why 99% of founders fail…"). It reads hollow.
- Don't invent statistics. If the source has none, the post has none.
- Match the user's voice if they've given prior posts as context — don't override their tone.
