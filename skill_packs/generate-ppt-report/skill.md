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
    playwright:
      # Set true to watch Chromium drive Bing/Google during this skill's runs.
      # Default false = headless. Per-skill override; doesn't affect other skills.
      show-browser: false
    triggers:
      keywords:
        - powerpoint
        - ppt
        - pptx
        - deck
        - slide
        - slides
        - presentation
---

# Generate PowerPoint Deck

A multi-phase deliverable. Plan → parallel research → synthesize → self-critique → refine. The `.pptx` is published to the user's Desktop; the scratch folder (with plan, drafts, critiques, slide images, search log) is managed by Java — `produceDeliverable` returns both paths so this skill never has to hardcode them.

## Steps

1. Parse the user's request into a single concrete `goal` string. Include the audience and the angle if the user gave one (e.g. "for a Series-A pitch", "for a security review"). If the topic is one ambiguous word, ask ONE clarifier — otherwise proceed.
2. **Append the format spec to the goal** so the synthesizer follows the deck shape and typography rules:

   `<the parsed user goal>. OUTPUT MUST BEGIN with a single # H1 line containing a clean 5-10 word headline title in Title Case (NOT a paraphrase of this prompt). Produce as a slide deck: 8-14 slides, one ## H2 per slide. Each slide: a tight title (≤8 words) + 3-5 short parallel bullets (no paragraphs) + 0-1 image. RULES: bullets use "- " only (NEVER "> -"); blockquote ">" only for actual quotations; every period/?/!/:/; followed by a space; no "(check the product page)" / "Hero image URL:" / "[image — embed failed: ...]" placeholders; no bold-numbered **N. Name** inline labels — each item is its own ## section.`

3. Call `produceDeliverable` with:
   - `goal` = the parsed goal **plus** the format spec from step 2
   - `format` = `"slides"`
   - `output` = `"pptx"`
4. The tool runs the plan→execute→synthesize→critique→refine loop internally and returns a path. Do NOT call `openApp("powerpoint")`, do NOT take screenshots of the desktop PowerPoint app, do NOT call `createPdfDocument` and ask the user to convert. The pure-Java POI writer is the only correct path.
4. **VERIFY against this skill's quality bar before reporting done.** The tool can return successfully even when slides are imageless. The tool result contains two paths — `File:` (the published deck) and `TaskFolder:` (the scratch folder Java created); use those, never hardcode a path. If any check fails, call `produceDeliverable` AGAIN with a tighter goal (e.g. add "with a real product photo per slide"). Up to 1 retry — if it still fails, ship with an explicit warning.
   - **File exists & non-trivial size**: `listFiles` / `getFileInfo` on the `File:` path. PPTX must be ≥ 80 KB (a deck with embedded images is heavier than a text-only one). If smaller, image embedding likely failed — retry.
   - **Slide count matches request**: aim for 8–14 slides. If the deck shows fewer than 6 content slides, the synthesizer truncated — retry with the goal restating the slide count.
   - **Image count ≈ slide count IN THE WORKFOLDER**: `listFiles` on `<TaskFolder>/images/`. Count image files; compare against content slides (skip Agenda / Closing / Summary). Expect ≥ 70% coverage. Below that, retry.
   - **Images are actually embedded in the .pptx, not just present in the workfolder.** This is the real test — image search can succeed (files saved to `<TaskFolder>/images/`) while embedding silently fails (slides come out text-only). Use `inspectPptxContents` (or unzip the `.pptx` and check `ppt/media/`) on the `File:` path: a deck with N picture-bearing slides should contain ≈ N image parts under `ppt/media/`. If `<TaskFolder>/images/` has 8 files but the .pptx has zero embedded images, the writer failed to read the local images — surface this clearly to the user and retry once with goal explicitly mentioning "embed product photos into each slide".
   - **No placeholder text on slides**: read `<TaskFolder>/FINAL.md` and grep for `embed failed`, `Hero image URL:`, `(check the product page)`, `[image —`. Any hit means leaked placeholders — retry.
5. When verification passes, reply with: file path, the critic's final score, refine cycles, and **a one-line verification summary** (e.g. "Verified: 8 slides, 7/8 images, 142 KB"). Also point the user at the **task folder** (the `TaskFolder:` path) — it contains:
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
- Output is published to the user's Desktop by Java; never assume or rewrite the destination path.
