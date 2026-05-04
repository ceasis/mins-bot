# Security & Distribution

How Mins Bot handles secrets, what the installer does and doesn't bundle, and how to ship safely.

---

## TL;DR

| Concern | Answer |
|---|---|
| Are my API keys committed to git? | No. `application-secrets.properties` is gitignored. |
| Are my API keys bundled into installers? | **No** (after the secrets-leak-guard fix). The build script aborts if any populated value is detected in the classpath secrets template. |
| Are user transcripts / memory bundled? | No. Those live in `~/mins_bot_data/` and `./memory/` on the user's machine. |
| Does the bot phone home? | No telemetry. Outbound calls go only to the providers you configure (OpenAI / Anthropic / Gemini / etc) + URLs you explicitly supply to skills. |

---

## Where secrets live

There are **two** files named `application-secrets.properties`. Only one of them ever holds real values.

| Path | Purpose | Gitignored? | In installer? |
|---|---|---|---|
| `application-secrets.properties` (project root) | **Your real keys** for dev runs. Spring Boot loads this from the working directory at startup. | ✅ Yes | ❌ No |
| `src/main/resources/application-secrets.properties` | **Empty template only.** Lists every known property so Spring knows the keys exist. | (committed) | ✅ Yes — it's inside the fat JAR |

The classpath copy must stay empty. The build aborts if any value sneaks in. See *Secrets-leak guard* below.

### Loading order (from `application.properties:19`)
```
spring.config.import=optional:file:${user.home}/application-secrets.properties,
                     optional:file:./application-secrets.properties,
                     optional:file:${user.dir}/application-secrets.properties
```

End users get a blank `application-secrets.properties` next to the installed bot. They fill it via the Quick Setup modal that appears on first launch (only the 3 essential LLM keys: OpenAI / Anthropic / Gemini), or via the full Setup tab.

---

## Secrets-leak guard

Every installer build runs this check. If it fails, the build aborts.

**What it does:** opens the freshly-built fat JAR, extracts `BOOT-INF/classes/application-secrets.properties`, and grep'es for any line matching `^[a-z][^#=]*=\S` (i.e. a non-empty value). If found, prints the offending keys (masked as `<VALUE>`) and exits 1.

**Where:**
- [build-installer.bat](../build-installer.bat) — Windows
- [build-installer-mac.sh](../build-installer-mac.sh) — macOS
- [build-installer-linux.sh](../build-installer-linux.sh) — Linux

**To bypass (don't):** there is no flag to bypass. If you genuinely need a populated value in the JAR, ship it via `application.properties` (which is fine for non-secret defaults) instead.

---

## What gets bundled into the installer

| Item | Bundled? | Notes |
|---|---|---|
| Fat JAR | ✅ | Contains code + static UI resources + empty secrets template |
| JRE (Java runtime) | ✅ | ~50 MB; bundled by `jpackage` so users don't need Java |
| Piper TTS binary | ✅ if available | Pulled from the dev's `~/mins_bot_data/piper/`. Skipped silently if not present. |
| Default Piper voice | ✅ if installed locally | Same source. Eliminates first-run download. |
| Icon | ✅ | `installer-assets/MinsBot.ico` (Win) / `.icns` (Mac) / `.png` (Linux) |
| **API keys** | ❌ never | Guarded |
| User data (transcripts, memory) | ❌ never | These live outside the project tree |
| Git history | ❌ never | jpackage only takes the input dir contents |

---

## Code signing

Without code signing, end users see scary warnings:

- **Windows**: "Windows protected your PC — unrecognized publisher" via SmartScreen.
- **macOS**: "MinsBot is damaged and can't be opened" via Gatekeeper. Users must right-click → Open the first time, or notarize the app.
- **Linux**: depends on distro signing infra — generally less prominent than Win/Mac.

### Windows

```cmd
:: Get a code-signing cert (~$200/yr DigiCert/Sectigo, or ~$10/mo Azure Trusted Signing)
set CODE_SIGN_CERT=C:\path\to\your-cert.pfx
set CODE_SIGN_PASSWORD=your-pfx-password
build-installer.bat
```

The script auto-runs `signtool sign` with timestamp from DigiCert's free timestamp server. The signed `.exe` will not trigger SmartScreen for users with reputation built up; new certs may need a few hundred installs to earn that reputation.

### macOS

Requires an Apple Developer Program membership ($99/year).

```bash
export APPLE_DEV_ID="Developer ID Application: Your Name (TEAMID)"
export APPLE_TEAM_ID="TEAMID"
export APPLE_ID="you@example.com"
export APPLE_APP_PASSWORD="app-specific-password"  # not your Apple ID password
./build-installer-mac.sh
```

The script signs *and* notarizes the DMG, then staples the notarization ticket so it works offline.

### Linux

`.deb` and `.rpm` repository signing is a different mechanism (per-repository signing keys rather than per-package). For one-off package signing, run `dpkg-sig --sign builder` or `rpm --addsign` after the build completes.

---

## Threat model

### What the bot guards against
- **Accidental key leak via installer**: secrets-leak guard.
- **Accidental key commit**: gitignore covers `application-secrets.properties`.
- **Path traversal in skills that touch the filesystem**: `MemoryService` and `diskscan` validate paths against allowlists/blocklists.
- **Reading skill state files from outside `memory/`**: skills that store data write under `memory/<skill>/` or `~/mins_bot_data/<area>/` only.
- **Process / port kills affecting the bot itself**: `processkiller` and `portkiller` have configurable protected lists (default protects the bot's own port `8765` and Java/system processes).

### What the bot does NOT guard against (by design)
- A user with shell access to the host machine can read `application-secrets.properties` directly. Don't run the bot as a service exposed to other users on shared boxes.
- A skill that the user explicitly enables can do whatever the user could do (including OS-level commands). Skills are disabled by default — flip them on knowingly.
- Cloud LLM providers see whatever you send them. Use offline mode (Ollama) for sensitive content.

---

## Operational checklist before public distribution

- [ ] Rotate any API key that's ever been committed or shipped (run a build of an old version, unzip `target/mins-bot-*.jar`, check `BOOT-INF/classes/application-secrets.properties` — if it shows real values, rotate everything).
- [ ] Run `build-installer.bat` and confirm it prints `OK - no secret values in classpath copy.`
- [ ] Code-sign the produced installer.
- [ ] Test the unsigned vs signed build on a clean VM — observe the SmartScreen / Gatekeeper behavior to validate signing actually worked.
- [ ] Ship.

---

## Reporting a vulnerability

Email security@mins.io (or the project owner directly) with:
- Reproduction steps
- Affected versions
- Whether the issue is publicly known

Don't open a public GitHub issue for security bugs.
