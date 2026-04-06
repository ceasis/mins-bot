## Password Strength Auditor

Analyzes password strength using entropy calculation, pattern detection, and breach database lookups.

### Features
- **Entropy scoring** — calculates bits of entropy and rates as Weak/Fair/Strong/Excellent
- **Pattern detection** — flags dictionary words, keyboard patterns (qwerty), dates, and repeated characters
- **Breach check** — queries Have I Been Pwned API using k-anonymity (only first 5 chars of hash sent)
- **Suggestions** — generates strong alternatives using configurable rules (length, symbols, no ambiguous chars)
- **Bulk audit** — paste multiple passwords for batch analysis
- **Password generator** — create random passwords with customizable length and character sets

### Endpoints
- `POST /api/skills/password/check` — analyze a single password
- `POST /api/skills/password/bulk` — analyze multiple passwords
- `POST /api/skills/password/generate` — generate a strong password
- `GET /api/skills/password/rules` — get current strength rules

### Privacy
Passwords are NEVER stored or logged. All analysis happens in-memory. Breach checks use k-anonymity — the full password never leaves the machine.