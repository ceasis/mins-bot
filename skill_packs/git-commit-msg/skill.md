---
name: git-commit-msg
description: Write a good conventional-commit message from the currently staged git diff. Trigger on "write my commit message", "commit msg for this", "what should I commit this as", "draft commit message", "help me commit".
metadata:
  minsbot:
    emoji: "✍️"
    os: ["windows", "darwin", "linux"]
    requires:
      bins: ["git"]
    install:
      - id: winget-git
        kind: winget
        package: Git.Git
        bins: ["git"]
        label: Install Git (winget)
---

# Git Commit Message

Produces a single-line commit subject + optional short body, conventional-commits style.

## Steps

1. Resolve the repo root: `git rev-parse --show-toplevel`. If the user is not in a git repo, say so and stop.
2. Get the staged diff: `git diff --cached --stat` (for scope summary) and `git diff --cached` (for content).
3. If nothing is staged, run `git status -s` and tell the user what's modified, then stop.
4. Infer:
   - **Type**: `feat` / `fix` / `refactor` / `docs` / `test` / `chore` / `perf` / `style`. Look at whether behavior changes, tests change, docs change.
   - **Scope**: the deepest common folder under `src/` or the top-level dir if the diff spans more than one module.
   - **Subject**: imperative, ≤ 72 chars, no period.
5. If the diff is > 20 files or > 500 lines, add a 2–3 line body listing the highest-level "what changed".
6. Print the message in a fenced block so the user can copy it. Offer to run the commit with `git commit -m "..."` — but do not commit without an explicit yes.

## Guardrails

- Never invent user intent. If the diff is ambiguous, ask which change is the headline.
- Redact anything that looks like a secret in the diff before showing it back.
- Prefer `fix:` over `feat:` when the diff removes bad behavior rather than adds capability.
