---
name: jq-run
description: Run a jq expression against a JSON file or the clipboard. Useful for extracting fields, reshaping nested structures, or sanity-checking an API response. Trigger on "jq this", "extract from json", "run jq", "grep json for X".
metadata:
  minsbot:
    emoji: "🗂️"
    os: ["windows", "darwin", "linux"]
    requires:
      bins: ["jq"]
    install:
      - id: winget-jq
        kind: winget
        package: jqlang.jq
        bins: ["jq"]
        label: Install jq (winget)
      - id: brew-jq
        kind: brew
        formula: jq
        bins: ["jq"]
        label: Install jq (brew)
---

# jq Run

## Steps

1. Get input JSON — file path, clipboard, or inline. If none, ask.
2. Get the jq expression from the user. If the user describes what they want in natural language ("give me the names of all users over 30"), draft the jq expression for them and ask for confirmation before running.
3. Run `jq '<expr>' <file>` or `<clipboard> | jq '<expr>'`.
4. Parse the exit code:
   - 0 — success, return the output
   - 3 — parse error in the jq expression (most common); surface the jq error message line
   - 4 — input wasn't valid JSON; tell the user which line/col
5. If output is large (> 50 lines), also offer to save it to a file.

## Guardrails

- Never guess at what the user wants — confirm the jq expression if you drafted it.
- For pipe sequences like `jq '.[] | select(...)' | jq '...'`, combine into one jq invocation with `|` inside the expression string.
- Wrap the expression in single quotes; escape any embedded single quotes properly (or use `'`).
