---
name: blog-outline
description: Turn a topic or rough idea into a structured blog post outline with headline options, a hook, 3–5 sections, and research questions to fill each section. Trigger on "outline a blog post about X", "blog outline", "structure an article", "give me a post plan", "help me write about Y".
metadata:
  minsbot:
    emoji: "✒️"
    os: ["windows", "darwin", "linux"]
---

# Blog Outline

Produces the shape, not the content. Leaves room for the user's voice to fill in.

## Steps

1. Parse the topic + any angle the user mentioned. If the topic is too broad ("AI"), ask for a specific angle.
2. Call the local-model chat capability (or cloud if online and the user prefers) with this prompt:
   ```
   For topic "<topic>", produce:
   1. Three headline options, each with a different angle: contrarian, practical, personal.
   2. A 2-sentence hook — what pulls the reader in.
   3. An outline of 4–6 sections. Each section has: a heading, one-sentence purpose, and 2–3 research questions the author needs answered to write it.
   4. Intended takeaway — what the reader should believe or do after reading.
   5. Length estimate (words) and reading time.
   Output as markdown.
   ```
3. Return the outline in a fenced block so the user can copy it into their editor.

## Guardrails

- Don't write the actual content — outlines only. The user writes the prose.
- Don't pad with filler sections ("conclusion: in conclusion, we conclude"). Every section must earn its place.
- If the topic requires expertise the user hasn't signaled (e.g. "how to file taxes"), add a note about needing a subject-matter source.
