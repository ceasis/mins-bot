---
name: generate-ppt-report
description: Produce a researched, image-rich PowerPoint deck on any topic. Trigger on "make a PowerPoint on X", "PPT deck about Y", "build slides comparing Z", "give me a presentation on W", or any variant of "create / generate / produce a (PowerPoint|PPT|PPTX|deck|slides|presentation) on …".
metadata:
  minsbot:
    emoji: "📊"
    os: ["windows", "darwin", "linux"]
    model: gpt-5.1
    output: pptx
    format: slides
    quality:
      ship-score: 8
      max-cycles: 3
---

# Generate PowerPoint Deck

A multi-phase deliverable. Plan → parallel research → synthesize → self-critique → refine. The output is a real `.pptx` file (one slide per section, image per slide where the topic supports it) saved to the user's Desktop under `MinsBot Deliverables/<task-id>/FINAL.pptx`.

## Steps

1. Parse the user's request into a single concrete `goal` string. Include the audience and the angle if the user gave one (e.g. "for a Series-A pitch", "for a security review"). If the topic is one ambiguous word, ask ONE clarifier — otherwise proceed.
2. Call `produceDeliverable` with:
   - `goal` = the parsed goal sentence
   - `format` = `"slides"`
   - `output` = `"pptx"`
3. The tool runs the plan→execute→synthesize→critique→refine loop internally and returns a path. Do NOT call `openApp("powerpoint")`, do NOT take screenshots of the desktop PowerPoint app, do NOT call `createPdfDocument` and ask the user to convert. The pure-Java POI writer is the only correct path.
4. When the tool returns, reply with: file path, the critic's final score, and the number of refine cycles. Also point the user at the **task folder** alongside the .pptx — it contains:
   - `plan.md` — the planner's decomposition
   - `scratchpad.md` — every research finding
   - `draft-N.md` / `critique-N.md` — every revision cycle
   - `images/` — every slide image picked, numbered to match slide order, plus `_search-log.txt` showing the search query → chosen URL for each
   - `FINAL.md` — the source markdown
   If a slide image looks wrong, the user can replace the file in `images/` and re-render, or read the log to see what the bot searched for.

## Quality bar

- 8–14 slides — too few feels thin, too many is unread.
- Title slide first, then one slide per major section.
- A real image per slide where the topic supports a visual (product, place, person, diagrammable concept). Skip images for pure-text slides like "Agenda" or "Closing notes".
- Slide titles are short (≤ 8 words). Body bullets are short and parallel — no paragraphs.

## Guardrails

- Never invent image URLs — the underlying executor sources them from a live image search and verifies them. Don't try to bypass that.
- If the user wants a written PDF instead of slides, route to `generate-pdf-report`.
- If `produceDeliverable` returns `ok=false`, surface its `message` verbatim.
- Output lives on the user's Desktop under `MinsBot Deliverables/`.
