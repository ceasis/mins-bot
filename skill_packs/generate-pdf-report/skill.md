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

A multi-phase deliverable. Plan → parallel research → synthesize → self-critique → refine. The output is a real, styled PDF saved to the user's Desktop under `MinsBot Deliverables/<task-id>/FINAL.pdf`.

## Steps

1. Parse the user's request into a single concrete `goal` string. Be specific: include the subject, scope, audience, and any constraints the user mentioned (e.g. budget, region, time horizon). If the topic is one ambiguous word ("AI", "phones") ask ONE clarifier — otherwise proceed.
2. Call `produceDeliverable` with:
   - `goal` = the parsed goal sentence
   - `format` = `"report"` (use `"memo"` only if the user said "memo"; `"brief"` if they said "brief")
   - `output` = `"pdf"`
3. The tool runs the plan→execute→synthesize→critique→refine loop internally and returns a path. Do NOT also call `createPdfDocument`, `openApp("word")`, or take any screenshots — that's the wrong path and will produce a worse result.
4. When the tool returns, reply with: file path, the critic's final score, and the number of refine cycles. Also tell the user that the **task folder** alongside the PDF contains:
   - `plan.md` — the planner's decomposition
   - `scratchpad.md` — every research finding gathered
   - `draft-N.md` / `critique-N.md` — every revision cycle
   - `images/` — every image picked, numbered to match section order, plus `_search-log.txt` mapping the search query to the chosen URL for each one
   - `FINAL.md` — the markdown the PDF was rendered from
   So if any image looks wrong, the user can swap the file in `images/` and re-render, or look at the search log to see why the bot picked it.

## Quality bar

- Real cited sources at the end (not LLM-recalled URLs).
- One real image per major section, sourced from a live image search.
- Title page, page numbers in the footer, two-column section layout where it helps readability.
- Critic score ≥ 8/10 before shipping; otherwise reply with the critic's last note so the user knows what's weak.

## Guardrails

- Never invent URLs, prices, or citations — the underlying executor strips LLM-emitted URLs and replaces them with verified search results. Don't try to bypass that.
- If `produceDeliverable` returns `ok=false`, surface its `message` verbatim — don't paper over it with a "here's a draft" fallback.
- If the user explicitly asked for a Word doc, route them to `generate-docx-report` (or pass `output="docx"`) instead. Same for PowerPoint → `generate-ppt-report`.
- The output lives on the user's Desktop. Don't move/copy it elsewhere unless asked.
