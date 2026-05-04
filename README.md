# Mins Bot

A floating desktop AI assistant. A swirling orb sits on your desktop, expanding into a full chat panel with voice, vision, browser automation, OS control, and **180+ skills** that let you actually *do* things instead of just talking to a chatbot.

Built with **Java 17 + Spring Boot 3.5 + JavaFX 21**. Runs locally; works offline once the local LLM + Piper voice are installed.

![Java](https://img.shields.io/badge/Java-17-blue) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green) ![JavaFX](https://img.shields.io/badge/JavaFX-21-orange) ![Skills](https://img.shields.io/badge/Skills-180+-purple) ![License](https://img.shields.io/badge/License-Proprietary-red)

> Landing page: **[mins.io](https://mins.io)**

---

## What it does

Most AI desktop apps stop at chat. Mins Bot ships **real action**: kill an app, free up a port, generate a marketing playbook and publish it, narrate a story to your dog, audit a landing page, build a PowerPoint deck, find duplicates on disk, set itself to auto-launch on login. Each capability is a self-contained "skill" callable from chat or via REST.

**Quick examples** (say these in the floating chat panel):

```
kill port 8080
how many files in my downloads folder
take a screenshot
play music by radiohead
narrate me a bedtime story for my dog
explain this page (with Chrome open)
market the app and publish to bluesky
auto-start yourself on login
use a jarvis voice
zip C:\projects\mins-bot to D:\backup.zip
```

---

## Install

### End users

Download the latest release for your OS:

| OS | File | Steps |
|---|---|---|
| **Windows** | `MinsBot-1.0.0.exe` | Double-click → Next → Next → Finish |
| **Windows portable** | `MinsBot-1.0.0-windows.zip` | Unzip → run `MinsBot/MinsBot.exe` |
| **macOS** | `MinsBot-1.0.0.dmg` | Open → drag to Applications |
| **Linux** | `minsbot_1.0.0_amd64.deb` | `sudo dpkg -i minsbot_1.0.0_amd64.deb` |

No Java install required — the JRE is bundled.

Detailed install + build instructions: **[INSTALL.md](INSTALL.md)**

### Developers (run from source)

```bash
git clone <this-repo>
cd mins-bot
mvn clean package -DskipTests
mvn spring-boot:run
# or: dev.bat (Windows)
```

App: `http://localhost:8765`. Default port set in [application.properties](src/main/resources/application.properties#L1).

---

## Documentation

| Doc | Read this if you want to… |
|---|---|
| **[INSTALL.md](INSTALL.md)** | Install, build installers, code-sign, distribute |
| **[docs/INDEX.md](docs/INDEX.md)** | Find any other doc |
| **[docs/SETUP.md](docs/SETUP.md)** | Set up dev environment from scratch |
| **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** | Understand the codebase layout + module boundaries |
| **[docs/SKILLS.md](docs/SKILLS.md)** | Add your own skill (the bot's main extension surface) |
| **[docs/TOOLS.md](docs/TOOLS.md)** | Full reference of every `@Tool` exposed to the LLM |
| **[docs/SECURITY.md](docs/SECURITY.md)** | API-key handling, secrets-leak guards, code-signing |
| **[docs/CHANGELOG.md](docs/CHANGELOG.md)** | What changed between releases |
| **[CLAUDE.md](CLAUDE.md)** | Project conventions (used by Claude when assisting on this repo) |

---

## Capabilities at a glance

### Chat + Agent
- Multi-LLM: OpenAI, Anthropic Claude, Google Gemini, Groq, plus local **Ollama** for offline mode
- 180+ skills exposed as `@Tool` methods the LLM can call
- Dynamic tool routing (classifier picks relevant tools per turn — respects 128-tool API limits)
- Persistent memory + transcripts across restarts
- Autonomous mode that works on standing directives when you're idle

### Voice (offline-capable)
- **Piper TTS** with 11+ curated voices including a **JARVIS preset** (British male + −2 semitones pitch shift for baritone)
- Configurable speech rate per mode (faster for status updates, slower for narration)
- Voice picker UI in the Voice tab
- Cloud TTS providers as fallback: ElevenLabs, Fish Audio, OpenAI TTS

### Browser ("AI guide for any website")
- Chrome CDP integration: click, fill, navigate, extract
- **`explainCurrentPage`** — speaks a summary of the active tab
- **`readAloudCurrentPage`** — narrates the article through your voice
- **`guidedWalkthrough`** — generates step-by-step instructions grounded in the page's actual buttons
- **`extractStructuredData`** — returns CSV of all matching records on the page

### Marketing automation
- **`selfmarket`** orchestrator: trends → competitor analysis → ad copy → social posts → outreach drafts → publishes/sends if configured
- Building blocks: `gighunter`, `leadgen`, `arbiscout`, `contentresearch`, `marketresearch`, `competitor`, `adcopygen`, `socialschedule`, `landingpageaudit`, `proposalwriter`, `reviewmonitor`, `personabuilder`
- Execution layer: `socialposter` (Bluesky/Mastodon/webhook), `emailsender` (Resend/SMTP), `mentiontracker`, `blogwriter`

### System / OS control
- Process / port / service / window / disk management (`processkiller`, `portkiller`, `appkill`, `windowctl`, `bigfilescan`, `duplicatefinder`, `diskcleaner`)
- Network / VPN / firewall / Docker / Git / build runner
- Power: lock, sleep, scheduled shutdown
- Auto-start the bot on login (`autostartmanager`)

### Daily ops
- Screenshot any monitor; media keys (play/pause/next/prev/volume); media files via `musicplayer` with library search + YouTube fallback
- Archive (zip/unzip), bulk file rename with regex, log tail with filter
- Pet entertainment (cat tv / dog calming music / bedtime stories for pets)

### Deliverables
- Generate **PDF / Word / PowerPoint** decks from chat ("create a PPT about top EVs of 2025")
- Charts, tables, images embedded; markdown tables rendered as real PPT tables

### File / text ops
- `filefind`, `filegrep`, `filediff`, `fileinspect` (head/tail/wc), `fileinfo`, `fileopen`, `filestats`, `clipboardctl`

---

## Architecture (one-line)

```
JavaFX floating window → WebView (HTML/JS UI)
                       ↘
                         Spring Boot REST + Spring AI agent
                       ↘   ↘
                         180+ skills (each: Config + Service + Controller)
                              ↘
                                Skill auto-discovery via component scan
                              ↘
                                Persisted state in memory/ + ~/mins_bot_data/
```

Full diagram: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**

---

## Configuration

- **API keys** → put in `application-secrets.properties` at the project root (gitignored, NOT bundled into installers — see [SECURITY.md](docs/SECURITY.md))
- **Per-skill toggles** → `application.properties` has `app.skills.<name>.enabled=true|false` for each
- **First-run wizard** → modal prompts for the 3 essential LLM keys (OpenAI / Anthropic / Gemini); rest configurable from the Setup tab

---

## Privacy & data

- All chat memory, transcripts, voice prefs, and bot data live in `~/mins_bot_data/` and `./memory/` — local only.
- Cloud LLM calls go to the providers you configure (OpenAI, Anthropic, Gemini). Set them blank to run fully offline via Ollama.
- Telemetry: none. The bot makes no outbound calls beyond the providers you've configured + skills' own user-supplied URLs.
- Installers do not bundle any API keys. A build-time guard aborts if a populated key is detected in the classpath. See [SECURITY.md](docs/SECURITY.md).

---

## Contributing

Skills are the main extension surface. Adding one is ~3 files (Config + Service + Controller) — see **[docs/SKILLS.md](docs/SKILLS.md)** for the convention and a worked example.

Open an issue for bugs or proposals. PRs welcome.

---

## License

- **App**: Proprietary EULA. See [LICENSE](LICENSE) (or LICENSE.md).
- **Skills SDK**: MIT. Skills you write under the documented convention can be redistributed standalone under MIT.

---

## Credits

- [Piper](https://github.com/rhasspy/piper) — local TTS engine
- [Playwright](https://playwright.dev/java/) — browser automation
- [Apache POI](https://poi.apache.org/) — DOCX / PPTX / XLSX generation
- [Spring AI](https://spring.io/projects/spring-ai) — LLM client + tool-calling glue
- Voice models hosted by [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices) on HuggingFace
