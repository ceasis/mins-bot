---
name: breach-check
description: Check if an email or password has appeared in a known data breach, using Have I Been Pwned's k-anonymity API (password is never sent in full). Trigger on "has my email been pwned", "hibp check", "breach check for X", "is this password safe", "was my data leaked".
homepage: https://haveibeenpwned.com
metadata:
  minsbot:
    emoji: "⚠️"
    os: ["windows", "darwin", "linux"]
---

# Breach Check

K-anonymity: we only send the first 5 chars of the password's SHA-1 hash. HIBP returns all hashes with that prefix, and we do the match locally. Password never leaves the machine.

## Steps

1. Detect input type:
   - Contains `@` → email
   - Else → password (treat everything else as a password to check)
2. **Email path**:
   - Call `GET https://haveibeenpwned.com/api/v3/breachedaccount/<urlencoded email>` with the user's HIBP API key if set, otherwise tell the user they need one (free) — the email endpoint requires auth.
   - On 200, list breaches with name, date, affected-data classes.
   - On 404, "Good news — this email doesn't appear in known breaches."
3. **Password path** (no API key needed):
   - SHA-1 hash the password locally
   - Take the first 5 hex chars as `prefix`, rest as `suffix`
   - `GET https://api.pwnedpasswords.com/range/<prefix>`
   - Search the response lines for `<suffix>:<count>`
   - If found: "This password has appeared in N known breaches. Change it."
   - If not: "This password hasn't shown up in known breaches."
4. **Never** echo the password back. Never log it. Clear the variable after use.

## Guardrails

- Never send the full password hash — always the prefix + local match.
- Never persist the password to disk or logs.
- If the user hasn't configured an HIBP API key, offer to open the settings page rather than failing silently.
- Don't call this "safe" or "unsafe" — a never-breached password can still be weak. Recommend running password-gen if the user wants a strong new one.
