---
name: research-and-report
description: Research a topic on the web, read the top sources, and produce a written report saved as a PDF. Trigger on "research X and write a report", "deep dive on Y", "gather info on Z and summarize", "build me a report about X", "find the state of the art on Y".
metadata:
  minsbot:
    emoji: "🔬"
    os: ["windows", "darwin", "linux"]
    playwright:
      # Set true to watch Chromium drive Bing/Google during this skill's
      # web research. Default false = headless. Per-skill override.
      show-browser: false
---

# Research & Report

A multi-step orchestration. Uses web search + page fetch + summarize + PDF gen. Takes ~2 minutes.

## Steps

1. Parse the topic and the scope from the user. Ask ONE clarifier if the scope is ambiguous (e.g. "the state of FLUX image models" is clear; "AI" is not).
2. Call `searchWeb` with the topic. Pull top 10 results.
3. Rank results by domain quality: peer-reviewed > primary source > established publication > blog > aggregator. Drop obviously-spam domains.
4. Read the top 5 via `fetchPageText` (or `readWebPage` (WebScraperTools) for JS-heavy sites). Cap each to ~4000 words.
5. For each source, capture: URL, title, published date (if available), 3-sentence gist, 1-2 quotable findings.
6. Synthesize a report with this shape:
   ```
   # <Topic>
   ## Executive summary (3 bullets)
   ## Current state (2–3 paragraphs)
   ## Key findings
   - finding 1 — source [1]
   - finding 2 — source [2]
   ## Disagreements
   <where sources contradict each other>
   ## Open questions
   ## Sources
   [1] URL — title — date
   [2] ...
   ```
7. Render the report to PDF by **chaining** into the md-to-pdf skill: write the markdown to a temp file, then call `invokeSkillPack("md-to-pdf")` and follow its steps pointing at that temp file. Save the resulting PDF to `~/Documents/Reports/<topic>-<date>.pdf`.
8. Reply with file path + a 2-sentence summary.

## Guardrails

- Always show sources. If a claim can't be traced to a source, drop it.
- Never paraphrase numbers — quote them directly.
- If the topic is health/legal/financial advice, add a one-line disclaimer that this is a research summary, not advice.
- If searchWeb returns < 3 usable results, stop and tell the user the topic is too narrow or novel — better than a thin report.
