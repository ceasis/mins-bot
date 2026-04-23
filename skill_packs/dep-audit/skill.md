---
name: dep-audit
description: Run a security + freshness audit on the project's dependencies, rank findings by severity × exposure, and suggest concrete upgrade commands. Trigger on "audit my deps", "check dependencies", "any CVEs", "dependabot me", "how outdated is my project".
metadata:
  minsbot:
    emoji: "🛡️"
    os: ["windows", "darwin", "linux"]
---

# Dependency Audit

## Steps

1. Detect ecosystem:
   - `package-lock.json` / `yarn.lock` → `npm audit --json` and `npm outdated --json`
   - `requirements.txt` / `pyproject.toml` → `pip-audit --format json` (install pip-audit first if missing)
   - `Cargo.lock` → `cargo audit --json`
2. Parse CVE findings. For each:
   - CVE ID
   - Severity (critical / high / medium / low)
   - Affected package → fixed version
   - Exposure — is it a direct dep or transitive
3. Parse outdated list. Split into:
   - Patch updates (always safe — suggest together)
   - Minor updates (usually safe)
   - Major updates (breaking — list separately, don't batch)
4. Produce the report:
   ```
   Security: 1 critical, 3 high, 5 medium
   ── 1. CVE-2026-xxxx (critical) in lodash@4.17.20 → fixed in 4.17.21
      direct dep — upgrade with: npm install lodash@^4.17.21
   ...

   Freshness: 8 patches, 14 minor, 3 major
   Patches (run now): npm install a@1.2.3 b@2.3.4 ...
   Major (manual): react 18 → 19, vite 5 → 6
   ```
5. Offer to run the patch batch — but never the majors.

## Guardrails

- Never `npm audit fix --force` — silently rewrites locks, can break builds.
- For pip-audit, respect the user's venv if one is active.
- Don't surface dev-only CVEs with the same urgency as prod ones — flag them separately.
