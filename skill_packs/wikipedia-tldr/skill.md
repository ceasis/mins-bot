---
name: wikipedia-tldr
description: Summarize a Wikipedia article — headline facts, 5 key points, and what the topic is *not* (common confusions). Trigger on "summarize this wikipedia", "what is X (wiki)", "wikipedia tldr", "wiki summary", "give me the gist of this article".
metadata:
  minsbot:
    emoji: "📚"
    os: ["windows", "darwin", "linux"]
---

# Wikipedia TL;DR

Uses the Wikipedia REST API — no scraping, gives clean JSON. Then runs a summarizer prompt on the lead section.

## Steps

1. Extract the topic. Accept: a wikipedia URL, a bare term ("quantum computing"), or the clipboard if none is given.
2. Resolve the article via the REST API:
   ```
   https://en.wikipedia.org/api/rest_v1/page/summary/<URL-encoded topic>
   ```
   If the topic redirects, follow the `titles.canonical` link.
3. If the response is a disambiguation page, list the top 5 options and ask the user which they meant.
4. Call the summarization capability on `extract` (the lead section) with this instruction:
   ```
   Produce:
   - Headline: one sentence.
   - Key facts: 5 bullets. Each must contain a number, date, or proper noun.
   - Often confused with: 2 items the topic is NOT (from the article's "not to be confused with" links if present).
   - Further reading: 2–3 section headings worth going deeper into.
   ```
5. Include the article URL at the bottom so the user can open it.

## Guardrails

- If the article is < 200 words, just return the lead — no need to re-summarize.
- Cite section headings verbatim ("See also", "History") — don't invent them.
- If the topic is a non-English wiki page, ask whether to use the native language summarizer or translate first.
