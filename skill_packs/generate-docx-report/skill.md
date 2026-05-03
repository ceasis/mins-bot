---
name: generate-docx-report
description: Produce a researched, image-rich Word (.docx) report on any topic. Trigger on "make a Word doc on X", "docx report about Y", "compile a Word document on Z", "write me a memo as docx", or any variant of "create / generate / produce a (Word|docx|memo) (report|brief|memo|whitepaper|analysis) on …".
metadata:
  minsbot:
    emoji: "📝"
    os: ["windows", "darwin", "linux"]
    model: gpt-5.1
    output: docx
    format: report
    quality:
      ship-score: 8
      max-cycles: 3
    playwright:
      # Set true to watch Chromium drive Bing/Google during this skill's runs.
      # Default false = headless. Per-skill override; doesn't affect other skills.
      show-browser: false
    triggers:
      keywords:
        - word document
        - word doc
        - word
        - docx
        - memo
---

# Generate Word (.docx) Report

A multi-phase deliverable. Plan → parallel research → synthesize → self-critique → refine. The `.docx` is published to the user's Desktop; the scratch folder (with plan, drafts, critiques, images, search log) is managed by Java — `produceDeliverable` returns both paths so this skill never has to hardcode them.

> **Note on rendering parity with the PDF skill.** The current `.docx` writer is a hand-rolled OOXML emitter — it produces a title + markdown-derived body cleanly, but it does NOT yet render the colored callout boxes, alternating-row tables, or two-column image layouts that the PDF path gets via Chromium CSS. If the user wants the most polished output, route them to `generate-pdf-report` instead. If they specifically need an editable Word file (track changes, comments, distribution to non-technical reviewers), this skill is the right call.

## Document shape (executive-grade business report)

This skill produces an **executive-grade Word document**, not a wiki dump. The synthesizer is general-purpose, so the requirements below MUST be included verbatim inside the `goal` string you pass to `produceDeliverable` — that's how the skill drives the format without hardcoding it in Java.

The shape:

- **Title** — big bold centered heading (rendered automatically from the goal).
- **Executive Summary** — `## Executive Summary` heading, 3–5 sentence prose paragraph (no bullets).
- **Key Findings** — `## Key Findings` heading, 3–5 short declarative bullets.
- **Body sections** — one `## H2 heading` per item / topic. NEVER use `**1. Name**` bold-numbered inline labels.
- **Tables for structured comparisons** — markdown tables with pipes and dashes, NOT parallel bullet lists.
- **Recommendation** — `## Recommendation` heading, 2–3 opinionated sentences.
- **Sources** — `## Sources` heading, numbered list of cited references.

## Steps

1. Parse the user's request into a single concrete `goal` string. Be specific: include the subject, scope, audience, and any constraints. If the topic is one ambiguous word, ask ONE clarifier — otherwise proceed.
2. **Append the format spec to the goal.** This is how the skill controls document shape without Java changes. Construct the final goal as:

   `<the parsed user goal>. OUTPUT MUST BEGIN with a single # H1 line containing a clean 5-10 word headline title in Title Case (NOT a paraphrase of this prompt — invent a polished headline). Then produce as an executive-grade business report: ## Executive Summary (3-5 sentence prose, no bullets), ## Key Findings (3-5 standalone bullets), one ## H2 section per item with 2-3 sentences + at most 4 bullets, markdown tables for spec/price/date comparisons, ## Recommendation (opinionated) and ## Sources (numbered references — real URLs only). RULES: bullets use "- " only (NEVER "> -"); blockquote ">" only for actual quotations; every period/?/!/:/; followed by a space; no "(check the product page)" or "Hero image URL:" placeholders; no bold-numbered **N. Name** inline labels.`

3. Call `produceDeliverable` with:
   - `goal` = the parsed goal **plus** the format spec from step 2
   - `format` = `"report"` (use `"memo"` only if the user said "memo"; `"brief"` if they said "brief")
   - `output` = `"docx"`

4. **VERIFY against this skill's quality bar before reporting done.** The tool result contains two paths — `File:` (the published .docx) and `TaskFolder:` (the scratch folder Java created); use those, never hardcode a path. If any check fails, call `produceDeliverable` AGAIN with a more specific goal. Up to 1 retry — if it still fails, ship with an explicit warning.
   - **File exists & non-trivial size**: `listFiles` / `getFileInfo` on the `File:` path. .docx must be ≥ 8 KB (a real report is heavier than a blank doc; below that means the writer choked). If smaller, retry.
   - **No "embed failed" / placeholder leakage**: read `<TaskFolder>/FINAL.md` and grep for `embed failed`, `Hero image URL:`, `(check the product page)`. Any hit means the synthesizer left placeholders behind — retry with the goal explicitly forbidding those phrases.
   - **Section count matches request**: count `## ` headings in `<TaskFolder>/FINAL.md`; should match the items the user asked for (top 5 → at least 5 body sections plus exec summary + recommendation + sources).

5. When verification passes, reply with: file path, the critic's final score, refine cycles, and a one-line verification summary. Point the user at the `TaskFolder:` so they can audit drafts, critiques, and any sourced images.

## Quality bar

- Real cited sources at the end (not LLM-recalled URLs).
- Tone: confident, scannable, specific. Avoid hedging language ("might be", "could potentially").
- Critic score ≥ 8/10 before shipping.

## Guardrails

- Never invent URLs, prices, or citations — the underlying executor strips LLM-emitted URLs and replaces them with verified search results.
- If `produceDeliverable` returns `ok=false`, surface its `message` verbatim.
- Output is published to the user's Desktop by Java; never assume or rewrite the destination path.
- If the user wants a polished printable PDF, route to `generate-pdf-report` instead. If they want slides, route to `generate-ppt-report`.
