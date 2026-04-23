---
name: screen-ocr
description: Capture a screen region (or the whole screen), OCR its text, and write the extracted text to the clipboard. Trigger on "OCR this screen", "read text from my screen", "what's on this image", "copy text from screenshot", "capture and extract text".
metadata:
  minsbot:
    emoji: "🔍"
    os: ["windows", "darwin", "linux"]
---

# Screen OCR

Uses Mins Bot's built-in screen + OCR tools. No external binary needed on Windows (Windows 10+ ships OCR with the OS via `Windows.Media.Ocr`).

## Steps

1. Determine the capture mode from the user's request:
   - "this screen" / "fullscreen" → full screen
   - "this window" / "active window" → active window bounds
   - "region" / "selection" → prompt for region (the harness shows a region picker)
   - Default when ambiguous → active window
2. Call `captureScreenRegion(mode)` → returns a PNG path.
3. Call `ocrImage(pngPath)` → returns the plain text.
4. If the text is empty, try again with preprocessing (`ocrImage(pngPath, {binarize: true, deskew: true})`).
5. Call `setClipboardText(text)`.
6. Reply with a one-liner: "Extracted N characters to your clipboard. First line: `<first 80 chars>…`"

## Guardrails

- Trim excessive whitespace before putting on clipboard — OCR often produces triple-newlines.
- If text looks garbled (high ratio of unusual characters), warn the user — the region may have been blurry.
- If the active window is a password field, decline and tell the user "refusing to OCR a password field".
- Never save the screenshot to disk permanently — delete the PNG after OCR unless user asks to keep.
