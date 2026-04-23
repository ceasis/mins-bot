---
name: test-runner
description: Detect the project's test framework, run the tests, and summarize pass/fail + top failing test names + likely cause. Trigger on "run my tests", "test this project", "what's failing", "test pass check", "jest run", "pytest run".
metadata:
  minsbot:
    emoji: "🧪"
    os: ["windows", "darwin", "linux"]
---

# Test Runner

One command from the user, one coherent report back. No raw test output dumps.

## Steps

1. Find project root. Detect test framework:
   - `package.json` with `jest` / `vitest` → `npx <runner>`
   - `pytest.ini` / `pyproject.toml` with pytest → `pytest`
   - `Cargo.toml` → `cargo test`
   - `pom.xml` → `mvn -B test` (add `-B` to avoid interactive)
   - `go.mod` → `go test ./...`
2. Run with a timeout proportional to repo size (~5 min default, 15 min cap).
3. Parse the output — most runners emit a structured summary near the end.
4. Report:
   ```
   ✓ 142 passed  ✗ 3 failed  ⊝ 7 skipped   (1m 24s)

   Failing:
   - src/user.test.ts › handles missing email
     expected "[email protected]", got null
   - ...

   Likely cause: recent diff touches src/user.ts (line 45 — email parsing).
   ```
5. If all green, one sentence: "All N tests passed."

## Guardrails

- Don't surface stdout unless the user explicitly asks — only the digested summary.
- If the test runner can't be detected, list what *is* configured and ask the user which to use.
- Never "fix" a failing test by editing it. Surface the failure, let the user decide.
