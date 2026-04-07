# Mins Bot

A floating desktop AI assistant built with **Java 17**, **Spring Boot**, and **JavaFX**. Think Jarvis for your PC — a swirling orb sits on your desktop, expanding into a full AI command center with voice, vision, proactive actions, browser automation, and connections to 10 messaging platforms.

![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-green)
![JavaFX](https://img.shields.io/badge/JavaFX-21-orange)
![License](https://img.shields.io/badge/License-AGPL--3.0%20%2B%20Commons%20Clause-red)

---

## Features

### Desktop UI
- **Floating window** — always-on-top, draggable orb with custom title bar
- **Tabbed interface** — Chat, Browser, Agents, Integrations, Setup, Skills, Schedules, Todo, Directives, Personality, Knowledge, Voice, Calibration, Workflows, Templates, Marketplace, Dashboard, Multi-Agent, Automations
- **Command palette** — `Ctrl+K` for quick access to all commands and tabs
- **Chat search** — `Ctrl+F` to search through message history
- **Keyboard shortcuts** — `Ctrl+/` to view all shortcuts, `Ctrl+L` to clear chat
- **Smooth transitions** — animated tab switching and message appearances
- **Sound effects** — subtle audio feedback for sent/received/notification/error (toggleable)
- **Styled tooltips** — hover over any toolbar icon for a descriptive tooltip
- **System tray** — minimize to tray for background operation

### AI & Chat
- **Multi-model support** — OpenAI (GPT-5.1, GPT-4o), Google Gemini (2.5 Pro, 3 Flash), Anthropic Claude (Opus, Sonnet), and local models via Ollama
- **100+ built-in tools** — the AI can invoke tools across files, browser, system, media, health, finance, GitHub, and more
- **Dynamic tool routing** — AI classifier selects relevant tools per message (respects 128-tool API limit)
- **Task planning** — numbered checklist before executing complex multi-step tasks
- **Autonomous mode** — works on directives independently when you're idle
- **Chat memory** — persistent transcript history across restarts

### Jarvis Mode — Proactive Intelligence
- **Proactive Action Mode** (lightning bolt icon) — continuously monitors your screen, pending tasks, and directives, then takes action automatically
  - Screen check every 15s — detects dialogs, forms, errors, notifications and acts on them
  - Task check every 30s — completes pending to-do items proactively
  - Directive check every 60s — executes standing directives
  - Safety: skips when you're actively working, 60s cooldown per action, speaks actions aloud via TTS
- **Jarvis Watch Mode** (eye icon) — AI actively comments on your screen like a real assistant
  - `[COMMENT]` — conversational tips, warnings, and observations appear as chat messages
  - `[REACT]` — auto-types into forms, quizzes, and prompts
  - `[SILENT]` — stays quiet when nothing interesting is happening
  - 10-second cooldown between comments, semantic deduplication to avoid repetition
- **Auto-pilot** (brain icon) — proactive screen help suggestions
- **Keyboard & mouse control** (keyboard icon) — allow the bot to click and type on your behalf

### Voice & Vision
- **Voice input** — speech-to-text via Web Speech API and native microphone capture
- **Text-to-speech** — ElevenLabs, Fish Audio, OpenAI TTS, or Windows native voice
- **Gemini Live** — real-time bidirectional audio streaming with language translation
- **Screen analysis** — live screen capture with Gemini vision + OCR before every AI response
- **Webcam** — capture and analyze webcam feed
- **Audio listening** — background audio capture and transcription with model selection

### Browser & Automation
- **Chrome DevTools Protocol** — control your real Chrome browser (navigate, click, extract data, fill forms)
- **Playwright** — headless browser automation for web scraping
- **System control** — execute system commands, manage processes, control applications
- **Window manager** — arrange windows, app switching
- **Automations** — custom trigger/action rules ("when message contains X, do Y")

### Life Management Tools

#### Personal Profile & Memory
- **Life profile** — 11 sections: Routines, Preferences, Relationships, Goals, Health, Finance, Locations, Vehicles, Pets, Important Dates, Notes
- **Episodic memory** — stores life events as searchable JSON episodes with type, tags, people, mood, importance
- **Auto-memory extraction** — automatically detects life facts from conversations and saves them
- **Personal config** — name, birthdate, family, work info loaded into every AI response
- **Knowledge base** — upload documents (PDF, Word, Excel, code, etc.) for AI reference

#### Health Tracker (11 tools)
- Log water, meals, exercise, weight, mood, sleep, medications
- Daily health summaries and multi-day trend analysis
- Set and track health goals

#### Finance Tracker (13 tools)
- Log expenses and income with categories
- Monthly budgets with real-time tracking
- Bill tracking with due date alerts
- Debt overview and financial goal tracking
- Monthly reports by category

#### Proactive Engine
- Morning briefings, break reminders, hydration reminders
- Meeting prep, bill reminders, relationship nudges
- Weekly goal check-ins, weather alerts
- Custom rules with quiet hours support

### Developer & Productivity Tools

#### GitHub Integration (18 tools)
- List repos, branches, README content, search repos
- Create/list/comment on issues and pull requests
- View notifications, activity feed, gists
- Monitor CI/CD workflow runs

#### Video Creation (Remotion)
- Scaffold and manage Remotion projects
- Create custom React video compositions
- Quick text videos with animated typography
- Slideshow videos from images with crossfade transitions
- Render to MP4 via CLI

#### Other Tools
- **Email** — send/read via SMTP/IMAP + Gmail API
- **Calendar** — Google Calendar integration
- **Web search** — Serper, SerpAPI, or DuckDuckGo
- **Web monitoring** — track website changes
- **Code audit** — clone repos, scan for vulnerabilities
- **File operations** — read, write, search, download, export, Excel, Word, PDF
- **Media** — image manipulation, QR codes, screen recording, video download
- **Utilities** — calculator, unit conversion, hash, timers, clipboard history
- **Software management** — install/uninstall via winget
- **Network diagnostics** — ping, traceroute, port scan
- **Printer control** — list printers, print documents

### Messaging Integrations (10 Platforms)
Connect the same AI to any combination — all share the same reply logic:

| Platform | Webhook Endpoint | Config Prefix |
|----------|-----------------|---------------|
| Viber | `POST /api/viber/webhook` | `app.viber.*` |
| Telegram | `POST /api/telegram/webhook` | `app.telegram.*` |
| Discord | `POST /api/discord/interactions` | `app.discord.*` |
| Slack | `POST /api/slack/events` | `app.slack.*` |
| WhatsApp | `POST /api/whatsapp/webhook` | `app.whatsapp.*` |
| Messenger | `POST /api/messenger/webhook` | `app.messenger.*` |
| LINE | `POST /api/line/webhook` | `app.line.*` |
| Teams | `POST /api/teams/messages` | `app.teams.*` |
| WeChat | `POST /api/wechat/webhook` | `app.wechat.*` |
| Signal | `POST /api/signal/webhook` | `app.signal.*` |

All integrations are **disabled by default** and conditionally loaded — disabled platforms don't consume memory.

---

## Requirements

- **Java 17** (JDK 17 or later)
- **Maven 3.6+**
- **Windows**, **macOS**, or **Linux**
- **Node.js 18+** (optional — for Remotion video creation)
- **API keys** for AI services you want to use (see [Configuration](#configuration))

---

## Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/user/mins-bot.git
cd mins-bot
```

### 2. Configure your API keys

Create a file called `application-secrets.properties` in the project root (this file is gitignored):

```properties
# Required — at least one AI provider
spring.ai.openai.api-key=YOUR_OPENAI_API_KEY

# Optional — Gemini
gemini.api.key=YOUR_GEMINI_API_KEY

# Optional — Claude
ANTHROPIC_API_KEY=YOUR_ANTHROPIC_KEY

# Optional — GitHub
GITHUB_TOKEN=YOUR_GITHUB_TOKEN

# Optional — ElevenLabs TTS
app.elevenlabs.api-key=YOUR_ELEVENLABS_API_KEY
app.elevenlabs.voice-id=YOUR_VOICE_ID

# Optional — Email
spring.mail.host=smtp.gmail.com
spring.mail.username=YOUR_EMAIL
spring.mail.password=YOUR_APP_PASSWORD
```

### 3. Build

```bash
mvn clean package -DskipTests
```

### 4. Run

**Option A — Maven (recommended)**
```bash
mvn spring-boot:run
```

**Option B — Batch script (Windows)**
```bash
run.bat
```

**Option C — JAR**
```bash
java --add-modules javafx.controls,javafx.web,javafx.fxml \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     -jar target/mins-bot-1.0.0-SNAPSHOT.jar
```

**Option D — Windows Installer**
```bash
build-installer.bat
```
Creates an MSI installer in `target/installer/` (requires JDK 17+ and WiX Toolset).

### 5. Use

- The chat panel appears on your desktop
- **Type** a message and press Enter
- **Click the microphone** for voice input
- **Ctrl+K** to open command palette
- **Toolbar icons**: eye (watch), keyboard (control), headphones (listen), brain (autopilot), lightning (proactive)

---

## Configuration

All configuration lives in `src/main/resources/application.properties`. Sensitive values go in `application-secrets.properties` (gitignored).

### Window Settings

| Property | Description | Default |
|----------|-------------|---------|
| `server.port` | HTTP port | `8765` |
| `app.window.expanded.width` | Chat panel width (px) | `456` |
| `app.window.expanded.height` | Chat panel height (px) | `520` |
| `app.window.always-on-top` | Keep window above all others | `true` |

### AI Models

| Property | Description | Default |
|----------|-------------|---------|
| `spring.ai.openai.chat.options.model` | OpenAI chat model | `gpt-5.1` |
| `app.gemini.vision-model` | Gemini vision model | `gemini-3-flash-preview` |
| `app.gemini.reasoning-model` | Gemini reasoning model | `gemini-2.5-pro` |
| `app.claude.model` | Claude model | `claude-opus-4-6` |
| `app.tool-classifier.model` | Tool routing classifier | `gpt-4o-mini` |

### Feature Toggles

| Property | Description | Default |
|----------|-------------|---------|
| `app.planning.enabled` | Task planning before execution | `true` |
| `app.autonomous.enabled` | Autonomous mode when idle | `true` |
| `app.chat.live-screen-on-message` | Auto-capture screen before replies | `true` |
| `app.proactive.enabled` | Proactive engine (briefings, reminders) | `false` |
| `app.proactive-action.enabled` | Proactive action mode (auto-act) | `false` |
| `app.cdp.enabled` | Chrome DevTools Protocol | `true` |
| `app.tray.enabled` | System tray icon | `true` |

### Proactive Mode Settings

| Property | Description | Default |
|----------|-------------|---------|
| `app.proactive-action.screen-check-seconds` | Screen check interval | `15` |
| `app.proactive-action.task-check-seconds` | Task check interval | `30` |
| `app.proactive-action.directive-check-seconds` | Directive check interval | `60` |
| `app.proactive.check-interval-ms` | Proactive engine check interval | `300000` |
| `app.proactive.quiet-hours-start` | Quiet hours start | `22` |
| `app.proactive.quiet-hours-end` | Quiet hours end | `7` |

---

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+K` | Command palette |
| `Ctrl+/` | Shortcuts help |
| `Ctrl+F` | Search chat messages |
| `Ctrl+L` | Clear chat |
| `Enter` | Send message |
| `Arrow Up/Down` | Input history |
| `Esc` | Close overlay/palette |

---

## Documentation

| Document | Description |
|----------|-------------|
| [docs/SETUP.md](docs/SETUP.md) | Complete setup guide — prerequisites, env vars, platform setup, troubleshooting |
| [docs/TOOLS.md](docs/TOOLS.md) | Full tool reference — 100+ tools organized by category |
| [CLAUDE.md](CLAUDE.md) | Developer context for AI assistants |

---

## Project Structure

```
mins-bot/
├── pom.xml
├── LICENSE
├── CLAUDE.md                              # AI developer context
├── run.bat / build-installer.bat          # Windows launch scripts
├── docs/
│   ├── SETUP.md                           # Full setup guide
│   └── TOOLS.md                           # Tool reference (100+ tools)
│
├── src/main/java/com/minsbot/
│   ├── FloatingAppLauncher.java           # JavaFX entry point
│   ├── MinsbotApplication.java            # Spring Boot entry point
│   ├── ChatService.java                   # Core agent loop & AI orchestration
│   ├── ChatController.java               # REST API endpoints
│   │
│   ├── agent/
│   │   ├── ProactiveActionService.java    # Jarvis-like auto-action engine
│   │   ├── ProactiveEngineService.java    # Briefings, reminders, nudges
│   │   ├── EpisodicMemoryService.java     # Life event memory system
│   │   ├── AutoMemoryExtractor.java       # Auto-detect life facts from chat
│   │   ├── ScreenStateService.java        # Screen capture + AI analysis
│   │   ├── SystemContextProvider.java     # System prompt builder
│   │   └── tools/                         # 100+ tool implementations
│   │       ├── ToolRouter.java            # Dynamic tool selection
│   │       ├── ToolClassifierService.java # AI-powered tool categorization
│   │       ├── HealthTrackerTools.java    # Health logging & trends
│   │       ├── FinanceTrackerTools.java   # Expense/budget tracking
│   │       ├── GitHubTools.java           # GitHub API (18 tools)
│   │       ├── RemotionVideoTools.java    # Programmatic video creation
│   │       ├── LifeProfileTools.java      # Personal life profile
│   │       ├── EpisodicMemoryTools.java   # Memory recall & search
│   │       └── ...
│   │
│   ├── skills/                            # Pluggable skill packages
│   └── [Platform]*.java                   # 10 messaging integrations
│
├── src/main/resources/
│   ├── application.properties
│   └── static/                            # Frontend (HTML/CSS/JS)
│
├── src/test/java/                         # 51 unit tests
│   └── com/minsbot/agent/
│       ├── EpisodicMemoryServiceTest.java
│       └── tools/
│           ├── HealthTrackerToolsTest.java
│           ├── FinanceTrackerToolsTest.java
│           └── LifeProfileToolsTest.java
│
└── ~/mins_bot_data/                       # Persistent data (user home)
    ├── personal_config.txt
    ├── life_profile.txt
    ├── directives.txt
    ├── health/                            # Health logs
    ├── finance/                           # Finance logs
    ├── episodic_memory/                   # Life event JSONs
    ├── knowledge_base/                    # Uploaded documents
    ├── remotion/                          # Video project
    └── videos/                            # Rendered videos
```

---

## API Endpoints

### Chat
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/chat` | Send a message, get a reply |
| `GET` | `/api/chat/history` | Load recent chat history |
| `GET` | `/api/chat/status` | Poll tool execution updates |
| `POST` | `/api/chat/clear` | Clear memory and transcript |

### Modes
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/proactive-action/toggle` | Toggle proactive action mode |
| `GET` | `/api/status/proactive-action` | Proactive action status |
| `POST` | `/api/autopilot/toggle` | Toggle auto-pilot |
| `GET` | `/api/status/autopilot` | Auto-pilot status |

### System
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/health` | System health check |
| `GET` | `/api/version` | App version |
| `POST` | `/api/briefing` | Generate daily briefing |

### Knowledge Base
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/kb/upload` | Upload document |
| `GET` | `/api/kb/list` | List documents |
| `GET` | `/api/kb/read/{name}` | Read document |
| `DELETE` | `/api/kb/{name}` | Delete document |

---

## Roadmap

### Near-term
- [ ] **Smart home integration** — Home Assistant / MQTT for lights, thermostat, locks, cameras
- [ ] **Spotify / music control** — play, pause, skip, search, queue management
- [ ] **Contact CRM** — relationship tracking with last interaction, birthday alerts, gift ideas
- [ ] **Daily briefing dashboard** — visual home screen with weather, calendar, tasks, health, budget
- [ ] **Sidebar navigation** — collapsible icon sidebar replacing the horizontal tab bar
- [ ] **Rich message cards** — structured cards for health logs, finance, weather, bills

### Mid-term
- [ ] **Mobile companion** — responsive web UI optimized for phone browsers
- [ ] **Wake word detection** — always-listening "Hey Mins" trigger
- [ ] **Workflow builder** — visual drag-and-drop automation chains
- [ ] **Location awareness** — GPS/IP-based triggers ("you're near the grocery store")
- [ ] **Subscription tracker** — track all subscriptions, total cost, renewal reminders
- [ ] **Docker management** — list/start/stop containers, view logs

### Long-term
- [ ] **Plugin marketplace** — community-created skills and tools
- [ ] **Multi-device sync** — seamless handoff between desktop, phone, and wearables
- [ ] **Habit pattern detection** — learns routines without being told
- [ ] **Voice cloning** — custom Jarvis voice via voice training
- [ ] **AR/camera integration** — point phone camera for real-world AI assistance
- [ ] **Offline mode** — local LLM fallback when internet is unavailable

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `no jfxwebkit in java.library.path` | Use `mvn spring-boot:run` or add `-Djava.library.path=target/javafx-natives` |
| `JavaFX runtime components are missing` | Add `--add-modules javafx.controls,javafx.web,javafx.fxml` to VM options |
| Window doesn't appear | Check port 8765 is free; try `http://localhost:8765/` in a browser |
| Circular dependency on startup | Check for `@Lazy` annotations on ToolRouter injections |
| Voice not working | Try opening `http://localhost:8765/` in Chrome instead of the JavaFX window |
| Messaging webhook not receiving | Ensure HTTPS (ngrok) and correct webhook URL |
| Ollama models not loading | Install from [ollama.com](https://ollama.com) and pull your model |
| GitHub tools not working | Set `GITHUB_TOKEN` environment variable with a Personal Access Token |
| Remotion render fails | Ensure Node.js 18+ is installed; run `setupRemotion` first |

---

## License

This project is licensed under the **GNU Affero General Public License v3.0** with the **Commons Clause** license condition.

**You may:**
- Use, view, and modify the source code
- Distribute copies under the same license terms
- Use it for personal and internal purposes

**You may not:**
- Sell the software or offer it as a paid hosted service
- Distribute modified versions without sharing the source code
- Remove license or copyright notices

See [LICENSE](LICENSE) for the full text.
