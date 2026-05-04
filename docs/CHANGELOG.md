# Changelog

All notable changes to Mins Bot. Newest first.

Format: `YYYY-MM-DD — <area>: <change>`. Tagged releases get their own section.

---

## Unreleased — current main

### Security
- **Secrets-leak guard**: installer build scripts now abort if `src/main/resources/application-secrets.properties` contains any populated values (which would otherwise ship inside the fat JAR to every end user). Sanitized the classpath template; real keys belong in the project-root copy only. See [SECURITY.md](SECURITY.md).

### Documentation
- Rewrote [README.md](../README.md) to reflect current capability set (180+ skills, JARVIS voice preset, marketing automation, page-guide tools, OS control).
- New [docs/INDEX.md](INDEX.md), [docs/SECURITY.md](SECURITY.md), [docs/SKILLS.md](SKILLS.md), [docs/ARCHITECTURE.md](ARCHITECTURE.md), [docs/CHANGELOG.md](CHANGELOG.md).
- New [INSTALL.md](../INSTALL.md) with end-user install paths, dev build instructions, code-signing setup, troubleshooting.

### Installer
- New `build-installer-mac.sh` (DMG) and `build-installer-linux.sh` (DEB / RPM / AppImage).
- `build-installer.bat` upgraded: real Windows wizard installer via WiX (with portable-zip fallback if WiX isn't installed). Adds Start Menu group, optional desktop shortcut, proper uninstaller.
- Bundles Piper binary + selected voice from the dev's machine into the installer payload — eliminates first-run download.
- Optional code-signing flow via `CODE_SIGN_CERT` env var (Windows `signtool`) and Apple Developer creds (macOS `notarytool`).
- New `release.bat` — one-shot build → tag → push → GitHub draft Release via `gh` CLI.

### Voice / TTS
- New JARVIS preset: Alan British male voice + −2 semitones pitch shift for baritone.
- Configurable Piper `--length-scale` for normal speech AND a separate slower default for narration mode.
- Voice page in the UI now has rate / pitch sliders (left = slower, right = faster — uniform direction across all sliders).
- 7 new Piper voices added to the catalog: Alan low, Northern English male, Jenny, Southern English female, Joe (US deeper baritone), LibriTTS-R, Lessac (high).
- New chat tools: `setVoice`, `setSpeechRate`, `setPitch`, `currentVoice`, `listVoices`. Natural-language matching for "low male british" → JARVIS preset, "iron man voice" → JARVIS preset, etc.

### Page-guide tools (Giya-class capabilities, no Chrome extension)
- `explainCurrentPage` — summarize the active Chrome tab and speak it through the bot's voice.
- `readAloudCurrentPage` — narrate the full tab via narration-mode TTS.
- `guidedWalkthrough` — generate step-by-step instructions grounded in the page's actual buttons.
- `extractStructuredData` — return CSV of all matching records on the page.

### Marketing automation
- New skills: `selfmarket` (orchestrator), `gighunter`, `arbiscout`, `contentresearch`, `leadgen`, `marketresearch`, `competitor`, `adcopygen`, `socialschedule`, `landingpageaudit`, `proposalwriter`, `reviewmonitor`, `personabuilder`.
- Execution layer: `socialposter` (Bluesky/Mastodon/webhook), `emailsender` (Resend/SMTP, auto-logs to `outreachtracker`), `mentiontracker` (search-RSS poll + sentiment), `blogwriter` (markdown article skeleton).
- Auto-execute mode in `selfmarket`: when the user says "market the app", the bot now actually publishes posts and sends emails for configured providers (skips others cleanly).

### Sales / conversion / operator
- New skills: `proposalwriter`, `invoicegen`, `pricingadvisor`, `keywordcluster`, `backlinkfinder`, `outreachtracker`, `personabuilder`, `abtestplanner`, `reviewmonitor`, `funnelanalyzer`, `cohortcalc`, `dailybriefing`, `followupqueue`.

### System control
- New skills: `processkiller`, `portmap`, `servicectl`, `networkdiag`, `firewallctl`, `proxyswitcher`, `vpncheck`, `systemstats`, `diskcleaner`, `batterystatus`, `gpustatus`, `appkill`, `applauncher`, `windowctl`, `filerecover`, `bigfilescan`, `duplicatefinder`, `scheduledtaskctl`, `autostartmanager`, `dockerctl`, `gitquickactions`, `buildwatcher`.
- Most enabled by default with safety guards (protected port `8765`, protected process names `java/javaw/system/explorer/csrss/winlogon/wininit/lsass/services`, `powerctl.allow-shutdown=false` opt-in only).

### Daily ops
- New skills: `screenshotter`, `mediactl`, `powerctl`, `archiver`, `filerename`, `logtail`, `petentertainment` (cat/dog enrichment + bedtime stories tuned for pets), `musicplayer` (local library + YouTube fallback).

### File / text ops
- New skills: `filefind`, `filegrep`, `filediff`, `fileinspect` (head/tail/wc), `fileinfo`, `fileopen`, `filestats`, `clipboardctl`.

### Auto-start
- `autostartmanager` skill can now register the bot OR any user-named app to launch on login (Windows registry HKCU\Run, Linux ~/.config/autostart). Self-install path: chat command "auto-start yourself".
- First-run wizard endpoint `POST /api/firstrun/install-autostart` for adding a "Launch on startup" checkbox to the install flow.

### PowerPoint generation
- Markdown tables now render as real PPT tables (XSLFTable with header styling + zebra rows) instead of dumping raw `| | |` text.
- Blockquote `>` markers stripped from slide text.
- Markdown links `[text](url)` now show just the text (slides can't navigate).
- Deduped doubled rows when the LLM joins two rows on one line via `||`.

### Setup / first-run
- Quick Setup modal pruned to the 3 essential LLM keys (OpenAI / Anthropic / Gemini). Optional providers (Groq, ElevenLabs, Fish Audio, GitHub) moved to the full Setup tab — keeps onboarding focused.

### Window
- Default expanded width bumped from 418 → 460 (10% wider).

### Narration mode
- `autoSpeak()` now detects narration intent (`narrate`, `tell me a story`, `bedtime story`, `recite`, `read aloud`, `verbatim`, `audiobook`, `dictate`, `sing`, etc.) and skips LLM summarization, speaking the full reply at the slower length-scale instead.

---

## Pre-changelog history

This file starts from the secrets-leak fix forward. Earlier history: see `git log` and the [iteration logs](../ITERATIONSv01.md).
