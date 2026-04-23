---
name: format-project
description: Detect a project's language + formatter and run it across the tree. Trigger on "format my project", "run prettier", "format all files", "run black", "clean up my code formatting", "apply formatter".
metadata:
  minsbot:
    emoji: "🪣"
    os: ["windows", "darwin", "linux"]
---

# Format Project

Detects language, picks the standard formatter, runs it, reports diff stats.

## Steps

1. Resolve project root (`git rev-parse --show-toplevel` if in a repo; else current dir).
2. Detect language by marker files:
   - `package.json` → JS/TS; look for prettier in devDeps → `npx prettier --write .`
   - `pyproject.toml` or `*.py` → `black .` (or `ruff format .` if ruff's configured)
   - `Cargo.toml` → `cargo fmt`
   - `go.mod` → `gofmt -w .`
   - `pom.xml` / `build.gradle` → use spotless if configured, else skip
3. If no formatter is configured, surface that and suggest the standard one for the language.
4. Run the formatter. Capture stderr for any parse errors.
5. After formatting, run `git diff --stat` to show what changed. If nothing changed, say so.

## Guardrails

- Never commit automatically. Formatting is a prep step, the user commits.
- Skip `node_modules/`, `.venv/`, `target/`, `dist/`, `build/` — those are generated.
- If the user has uncommitted changes, warn before reformatting so they can stash if needed.
