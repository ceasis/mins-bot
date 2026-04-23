---
name: lint-scan
description: Run the project's linter, group findings by rule, rank by blast radius, and optionally auto-fix the safe ones. Trigger on "lint my code", "run eslint", "clippy", "find lint errors", "what's my project's lint score".
metadata:
  minsbot:
    emoji: "🔎"
    os: ["windows", "darwin", "linux"]
---

# Lint Scan

## Steps

1. Detect linter:
   - JS/TS: `eslint` via `npx eslint . --format json`
   - Python: `ruff check . --output-format json`
   - Rust: `cargo clippy --message-format=json`
   - Go: `go vet ./...` (no JSON — parse stderr)
2. Run with a 2-min timeout.
3. Aggregate by rule ID. For each, capture:
   - Count of occurrences
   - Severity
   - One example file:line
   - Whether the rule has an auto-fix
4. Present top 10 rules, sorted by (errors first, then count). Include a "silent" 1-line footer for low-severity / low-count rules.
5. If any rules have auto-fix and the user says so, run the fix pass (`eslint --fix`, `ruff check --fix`, `cargo clippy --fix`).
6. Report delta: "Auto-fixed N of M findings. K manual fixes remain."

## Guardrails

- Never auto-fix without explicit user consent — auto-fixers occasionally break working code.
- Don't lint generated dirs (`dist/`, `.next/`, `build/`).
- If lint count > 500, present the top 10 rules only and tell the user to run `--fix` first, then rescan.
