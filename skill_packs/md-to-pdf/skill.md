---
name: md-to-pdf
description: Convert a local Markdown file into a styled PDF, saved next to the source. Trigger on "convert this md to pdf", "export markdown as pdf", "make a pdf from my notes.md", "print markdown to pdf".
metadata:
  minsbot:
    emoji: "📄"
    os: ["windows", "darwin", "linux"]
    requires:
      bins: ["pandoc"]
    install:
      - id: winget-pandoc
        kind: winget
        package: JohnMacFarlane.Pandoc
        bins: ["pandoc"]
        label: Install Pandoc (winget)
      - id: brew-pandoc
        kind: brew
        formula: pandoc
        bins: ["pandoc"]
        label: Install Pandoc (brew)
---

# Markdown → PDF

Uses Pandoc because it ships ready-made chrome-quality output. No browser spinup, no headless Chromium.

## Steps

1. Parse the user's request for the source path. If the user didn't give one, ask.
2. Verify the file exists and ends in `.md` / `.markdown`.
3. Derive output path: replace the extension with `.pdf` alongside the source.
4. Call `runShell`:
   ```
   pandoc "<source.md>" -o "<source.pdf>" --pdf-engine=wkhtmltopdf
   ```
   If wkhtmltopdf isn't installed, fall back to `--pdf-engine=weasyprint`, then to no engine flag (lets pandoc pick).
5. Verify the PDF exists and is >1 KB.
6. Reply with the output path. If the user set `app.auto-open=true`, also call `openPath`.

## Guardrails

- Never overwrite an existing PDF without confirming.
- If the markdown has embedded images with absolute paths that don't exist, pandoc will fail — surface the specific missing image.
- For files >10 MB of markdown, warn the user that rendering may take a minute.
