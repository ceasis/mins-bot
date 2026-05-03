---
name: generate-pdf-report
description: Produce a researched, image-rich PDF report on any topic. Trigger on "make a PDF report on X", "PDF brief about Y", "compile a PDF on Z", "build a researched PDF for W", or any variant of "create / generate / produce a PDF (report|brief|memo|whitepaper|analysis) on …".
metadata:
  minsbot:
    emoji: "📄"
    os: ["windows", "darwin", "linux"]
    model: gpt-5.1
    output: pdf
    format: report
    quality:
      ship-score: 8
      max-cycles: 3
    playwright:
      # Set true to watch Chromium drive Bing/Google during this skill's runs
      # (debug / demo). Default false = headless. Per-skill override only —
      # other skills keep using the global app.playwright.headless setting.
      show-browser: false
    triggers:
      # Format keywords this skill claims. The deliverable interceptor builds
      # its routing regex from the union of every skill's keywords, so adding
      # a new format-skill is purely a keyword exercise — no Java change.
      keywords:
        - pdf
---

# Generate PDF Report

A multi-phase deliverable. Plan → parallel research → synthesize → self-critique → refine. The PDF is published to the user's Desktop; the scratch folder (with plan, drafts, critiques, images, search log) is managed by Java — `produceDeliverable` returns both paths so this skill never has to hardcode them.

## Document shape (executive-grade business report)

This skill produces an **executive-grade PDF**, not a wiki dump. The synthesizer is general-purpose, so the requirements below MUST be included verbatim inside the `goal` string you pass to `produceDeliverable` — that's how the skill drives the format without hardcoding it in Java.

The shape:

- **Cover page title** — the synthesized markdown MUST start with a single `# <Clean Title>` line. 5–10 words, headline form (Title Case), professional. NEVER paste the user's full prompt as the title. Examples of good titles: `# Top 5 Flagship Laptops to Watch in 2026`, `# Bullet-Proof SUVs: 2026 Buyer's Guide`. Bad titles: `# upcoming laptops this 2026. top tier only.` (raw user prompt), `# Report About Cars` (too vague). The Java formatter pulls this H1 onto the cover page automatically; an absent or sloppy H1 produces a sloppy cover page.
- **Executive Summary** — `## Executive Summary` heading, 3–5 sentence prose paragraph (no bullets), states bottom-line finding and recommended action.
- **Key Findings callout** — `## Key Findings` heading, exactly 3–5 short declarative bullets. Each must stand alone as a pull-quote.
- **Body sections** — one `## H2 heading` per item / topic. NEVER use `**1. Name**` bold-numbered inline labels — each item is its own H2 so it gets its own image and layout. Each section: 2–3 sentences of framing prose + at most 4 bullets of concrete facts (specs / prices / dates / links).
- **Tables for structured comparisons** — when comparing N items across the same dimensions (specs, prices, release dates), write a real markdown table with pipes and dashes, NOT parallel bullet lists. The CSS renders tables with alternating row shading and styled headers.
- **Recommendation** — `## Recommendation` heading, 2–3 opinionated sentences naming the single best option or the direct answer. No hedging ("might be", "could potentially").
- **Sources** — `## Sources` heading, numbered list of cited references at the end. Only include URLs that came from real research — invented URLs are stripped automatically.
- **Footer** — page numbers + report title in the footer of every page (rendered by the CSS).

### Typography rules (enforced via the format spec; non-negotiable)

- **Bullets**: use plain `- ` only. NEVER use `> -` (blockquote-bullet hybrid) — it renders as a quoted line, not a bullet.
- **Blockquotes**: use `> ` only for actual quotations / important callouts, NOT for bullet lists.
- **Sentence spacing**: every period, question mark, exclamation mark, colon, and semicolon MUST be followed by a single space (or end-of-line). No `line.A likely`, `note:Important`, `2026.Top tier`. Re-read every paragraph before emitting.
- **Bold**: use `**word**` for emphasis only, sparingly. Don't bold whole sentences.
- **No placeholder phrases**: never write `(check the product page)`, `Hero image URL:`, `Image:`, `[image — embed failed: ...]`, `>`-quoted disclaimers. The formatter strips most of these but leaks waste reader attention.

## Steps

1. Parse the user's request into a single concrete `goal` string. Be specific: include the subject, scope, audience, and any constraints the user mentioned (e.g. budget, region, time horizon). If the topic is one ambiguous word ("AI", "phones") ask ONE clarifier — otherwise proceed.
2. **Append the format spec to the goal.** This is how the skill controls document shape without Java changes. Construct the final goal as:

   `<the parsed user goal>. OUTPUT MUST BEGIN with a single # H1 line containing a clean 5-10 word headline title in Title Case (NOT a paraphrase or copy of this prompt — invent a polished headline like "Top 5 Flagship Laptops to Watch in 2026"). Then produce as an executive-grade business report: ## Executive Summary (3-5 sentence prose, no bullets), ## Key Findings (3-5 standalone bullets), one ## H2 section per item with 2-3 sentences of framing + at most 4 bullets, use markdown tables for spec/price/date comparisons across items, close with ## Recommendation (opinionated, no hedging) and ## Sources (numbered references — real URLs only). RULES: bullets use "- " only (NEVER "> -"); blockquote ">" only for actual quotations; every period/?/!/:/; followed by a space (no "line.A", "note:Important"); no "(check the product page)" or "Hero image URL:" placeholders; no bold-numbered **N. Name** inline labels — each item is its own ## section.`

3. Call `produceDeliverable` with:
   - `goal` = the parsed goal **plus** the format spec from step 2
   - `format` = `"report"` (use `"memo"` only if the user said "memo"; `"brief"` if they said "brief")
   - `output` = `"pdf"`
3. The tool runs the plan→execute→synthesize→critique→refine loop internally and returns a path. Do NOT also call `createPdfDocument`, `openApp("word")`, or take any screenshots — that's the wrong path and will produce a worse result.
4. **VERIFY against this skill's quality bar before reporting done.** The tool can return successfully even when the output is thin (e.g. zero images on a product comparison). The tool result contains two paths — `File:` (the published PDF) and `TaskFolder:` (the scratch folder Java created); use those, never hardcode a path. If any check fails, call `produceDeliverable` AGAIN with a more specific goal (e.g. add "with one product photo per item" to the goal string). Up to 1 retry — if it still fails, ship with an explicit warning.
   - **File exists & non-trivial size**: `listFiles` / `getFileInfo` on the `File:` path. PDF must be ≥ 50 KB. If smaller, the renderer silently skipped images — retry.
   - **Image count matches section count**: `listFiles` on `<TaskFolder>/images/`. Count `.jpg/.png/.webp` files; compare against the number of product / item / case-study sections requested. If less than half are imaged, the run failed verification — retry.
   - **No "embed failed" / placeholder leakage**: read `<TaskFolder>/FINAL.md` and grep for `embed failed`, `Hero image URL:`, `(check the product page)`. Any hit means the synthesizer left placeholders behind — retry with the goal explicitly forbidding those phrases.
5. When verification passes, reply with: file path, the critic's final score, refine cycles, and **a one-line verification summary** (e.g. "Verified: 5/5 product images, 87 KB"). Also tell the user that the **task folder** (the `TaskFolder:` path) contains:
   - `plan.md` — the planner's decomposition
   - `scratchpad.md` — every research finding gathered
   - `draft-N.md` / `critique-N.md` — every revision cycle
   - `images/` — every image picked, numbered to match section order, plus `_search-log.txt` mapping the search query to the chosen URL for each one
   - `FINAL.md` — the markdown the PDF was rendered from
   If any image looks wrong, the user can swap the file in the task folder's `images/` and re-render, or read the search log to see why the bot picked it.

## Quality bar

- Real cited sources at the end (not LLM-recalled URLs).
- One real image per major section, sourced from a live image search.
- Title page, page numbers in the footer, two-column section layout where it helps readability.
- Critic score ≥ 8/10 before shipping; otherwise reply with the critic's last note so the user knows what's weak.

## Guardrails

- Never invent URLs, prices, or citations — the underlying executor strips LLM-emitted URLs and replaces them with verified search results. Don't try to bypass that.
- If `produceDeliverable` returns `ok=false`, surface its `message` verbatim — don't paper over it with a "here's a draft" fallback.
- If the user asked for a Word doc, route them to `generate-docx-report`. For PowerPoint → `generate-ppt-report`.
- Output is published to the user's Desktop by Java; never assume or rewrite the destination path.
