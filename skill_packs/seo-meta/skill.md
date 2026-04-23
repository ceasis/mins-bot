---
name: seo-meta
description: Generate title tag, meta description, and Open Graph tags for a web page from its content. Trigger on "seo meta for this page", "generate og tags", "meta description please", "title tag + description".
metadata:
  minsbot:
    emoji: "🏷️"
    os: ["windows", "darwin", "linux"]
---

# SEO Meta

## Steps

1. Get the page content. Accept a URL (fetch + extract main article), a file path, or inline text.
2. Extract: primary topic, target keyword (ask if ambiguous), intent (informational / transactional / navigational).
3. Generate:
   - **Title tag** (≤ 60 chars) — keyword near the start, brand suffix optional
   - **Meta description** (150–160 chars) — active voice, include the keyword, end with a soft CTA if transactional
   - **og:title** — can be punchier than title tag (social context)
   - **og:description** — slightly more casual than meta description
   - **og:image** — prompt for a URL or tell the user where they'd typically add one
   - **twitter:card** — recommend `summary_large_image` if there's a hero image, else `summary`
4. Print the `<meta>` / `<link>` block as copy-pasteable HTML.
5. Flag any value that went over char limit — don't silently truncate.

## Guardrails

- Don't stuff keywords — once is enough. "Best running shoes | Best running shoes for men | Running shoes guide" hurts ranking now.
- Title tag: avoid clickbait ("You'll never believe…") — search engines penalize it.
- For product pages, include the price in the meta description only if you're confident it's current.
