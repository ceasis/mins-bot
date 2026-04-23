---
name: npm-outdated
description: Show outdated npm packages grouped by semver-bump risk (patch/minor/major), with concrete upgrade commands. Trigger on "npm outdated", "what deps are outdated", "upgrade my packages", "check npm freshness".
metadata:
  minsbot:
    emoji: "📈"
    os: ["windows", "darwin", "linux"]
    requires:
      bins: ["node"]
---

# npm Outdated

## Steps

1. Confirm we're in a Node project (`package.json` present).
2. Run `npm outdated --json`. Empty output = nothing outdated, tell the user so and stop.
3. For each entry, capture: package, current, wanted, latest, type (dependencies vs devDependencies).
4. Classify by bump risk (compare `current` → `latest`):
   - Patch (1.2.3 → 1.2.4) — almost always safe
   - Minor (1.2.3 → 1.3.0) — usually safe, changelogs worth skim
   - Major (1.x → 2.x) — breaking, needs manual attention
5. Produce three sections with ready-to-run install commands:
   ```
   Patch (safe): npm install a@1.0.3 b@2.4.6 ...
   Minor (skim changelogs): npm install c@1.5.0 d@3.2.0 ...
   Major (review each):
     - react 18.3.1 → 19.0.0 (open https://react.dev/blog for migration guide)
     - ...
   ```
6. Offer to run the patch batch only.

## Guardrails

- Never run `npm install` with the `--force` flag.
- Respect peer dependency constraints — if a minor update would break peerDeps, flag it.
- For monorepos (yarn workspaces, pnpm workspaces), run the audit at the workspace level, not per-package.
