---
name: json-format
description: Pretty-print, minify, or validate JSON from the clipboard or a file, then put the result back on the clipboard. Trigger on "format my json", "pretty print this json", "minify json", "validate this json", "is my json valid".
metadata:
  minsbot:
    emoji: "🎨"
    os: ["windows", "darwin", "linux"]
---

# JSON Format

Zero external deps — uses the built-in JSON tool.

## Steps

1. Get the input: user may have pasted JSON inline, referenced a file path, or expect the clipboard.
   - If a path is present and the file exists, read it.
   - Else grab the clipboard.
   - Else ask.
2. Detect intent from keywords: "pretty" / "indent" → pretty-print; "minify" / "compact" → minify; "validate" / "is this valid" → validate only.
3. Default = pretty-print with 2-space indent.
4. Parse the input. On parse failure, surface the line+column and the offending token — don't silently accept broken JSON.
5. Emit the formatted result. Also put it on the clipboard.
6. Reply one-liner: "Formatted N bytes — on your clipboard." (or "Valid." / "Invalid: <error>")

## Guardrails

- Never lossy-transform — sort keys only if the user asks.
- Preserve number precision — don't let integers get coerced to floats.
- If the input is clearly JSON5 / JSONC (has comments or trailing commas), offer to strip those first rather than silently failing.
