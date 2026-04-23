---
name: pr-body-drafter
description: Draft a pull request description from the current branch's commits and diff against main. Trigger on "write my pr body", "pr description for this branch", "draft pr text", "what should my pr say", "help me write the pull request".
metadata:
  minsbot:
    emoji: "📝"
    os: ["windows", "darwin", "linux"]
    requires:
      bins: ["git"]
---

# PR Body Drafter

Produces a copy-pasteable GitHub PR body with summary, test plan, and screenshots-needed notes.

## Steps

1. Confirm we're in a git repo (`git rev-parse --show-toplevel`).
2. Determine base branch: `git symbolic-ref refs/remotes/origin/HEAD` → strip `origin/`. Fallback: `main`, then `master`.
3. Get the commit list: `git log --oneline <base>..HEAD`. If empty, tell user the branch is in sync with base and stop.
4. Get the diff summary: `git diff --stat <base>..HEAD`.
5. Compose the body in this shape:
   ```
   ## Summary
   <2–3 bullets, user-facing what-changed, not mechanical file-by-file>

   ## Why
   <1–2 sentences motivating the change; infer from commit messages>

   ## Test plan
   - [ ] <concrete checks a reviewer can reproduce>

   ## Screenshots
   <only when the diff touches src/main/resources/static/ — UI changes>
   ```
6. If the diff touches UI files, add "⚠ needs screenshots" to the body.
7. Print the body in a fenced block. Don't create the PR — leave that to the user.

## Guardrails

- Do not summarize commits mechanically ("added X, removed Y") — tell the story of the change.
- If commits are WIP/squash candidates, note that and offer to suggest a clean squashed message.
- Never include commit hashes in the body — they're rendered elsewhere.
