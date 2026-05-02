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
---

# Generate PDF Report

A multi-phase deliverable. Plan → parallel research → synthesize → self-critique → refine. The PDF is published to the user's Desktop; the scratch folder (with plan, drafts, critiques, images, search log) is managed by Java — `produceDeliverable` returns both paths so this skill never has to hardcode them.

## Steps

1. Parse the user's request into a single concrete `goal` string. Be specific: include the subject, scope, audience, and any constraints the user mentioned (e.g. budget, region, time horizon). If the topic is one ambiguous word ("AI", "phones") ask ONE clarifier — otherwise proceed.
2. Call `produceDeliverable` with:
   - `goal` = the parsed goal sentence
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
- If the user explicitly asked for a Word doc, route them to `generate-docx-report` (or pass `output="docx"`) instead. Same for PowerPoint → `generate-ppt-report`.
- Output is published to the user's Desktop by Java; never assume or rewrite the destination path.
