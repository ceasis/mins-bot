# Mins Bot

A floating desktop AI assistant built with **Java 17**, **Spring Boot**, and **JavaFX**. A swirling orb sits on your desktop — double-click to expand a full chat panel with voice, vision, browser automation, and connections to 10 messaging platforms.

![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-green)
![JavaFX](https://img.shields.io/badge/JavaFX-21-orange)
![License](https://img.shields.io/badge/License-AGPL--3.0%20%2B%20Commons%20Clause-red)

---

## Features

### Desktop UI
- **Floating window** — always-on-top, transparent, draggable orb with no title bar
- **Expand/collapse** — double-click the orb to open the chat panel; click the orb again to collapse
- **Tabbed interface** — Chat, Browser, Skills, Schedules, Todo, and Directives tabs
- **System tray** — minimize to tray for background operation

### AI & Chat
- **Multi-model support** — OpenAI (GPT-5.1, GPT-4o), Google Gemini (2.5 Pro, 3 Flash), and local models via Ollama
- **Tool-calling agent** — 57+ built-in tools the AI can invoke (file management, browser control, system commands, and more)
- **Task planning** — the bot shows a numbered checklist before executing complex multi-step tasks
- **Autonomous mode** — the bot can work on directives independently when you're idle
- **Chat memory** — conversation context window with persistent transcript history

### Voice & Vision
- **Voice input** — speech-to-text via Web Speech API and native microphone capture
- **Text-to-speech** — ElevenLabs, OpenAI TTS, or Windows native voice output
- **Gemini Live** — real-time bidirectional audio streaming with language translation
- **Screen watching** — periodic screen capture with OCR for context awareness
- **Webcam** — capture and analyze webcam feed
- **Audio listening** — background audio capture and transcription

### Browser & Automation
- **Chrome DevTools Protocol** — control your real Chrome browser (navigate, click, extract data)
- **Playwright** — headless browser automation for web scraping
- **System control** — execute system commands, manage processes, control applications

### Tools (57+)
- **Files** — read, write, search, download, export
- **Browser** — navigate, screenshot, extract content, fill forms
- **System** — run commands, manage software, network diagnostics, printer control
- **Media** — image generation (Hugging Face ONNX), QR codes, PDF extraction, Excel
- **Utilities** — calculator, unit conversion, hash generation, timers, clipboard
- **Email** — send and read emails via SMTP/IMAP
- **Config** — manage bot personality, sites, schedules, and directives

### Messaging Integrations (10 Platforms)
Connect the same AI chatbot to any combination of these platforms — all share the same reply logic:

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

All integrations are **disabled by default**. Enable only what you need.

### Skills System
- Pluggable skill packages under `com.minsbot.skills.<name>`
- Auto-discovered by Spring — no manual registration
- **Disk Scan** — browse file systems with security blocklists
- Easy to add your own: create a Config + Service + Controller package

---

## Requirements

- **Java 17** (JDK 17 or later)
- **Maven 3.6+**
- **Windows**, **macOS**, or **Linux**
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

# Optional — ElevenLabs TTS
app.elevenlabs.api-key=YOUR_ELEVENLABS_API_KEY
app.elevenlabs.voice-id=YOUR_VOICE_ID

# Optional — Email
spring.mail.host=smtp.gmail.com
spring.mail.username=YOUR_EMAIL
spring.mail.password=YOUR_APP_PASSWORD

# Optional — Messaging platform tokens (add only what you use)
# app.telegram.bot-token=YOUR_TOKEN
# app.discord.bot-token=YOUR_TOKEN
# app.viber.auth-token=YOUR_TOKEN
# ... etc.
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

**Option B — JAR**

```bash
java --add-modules javafx.controls,javafx.web,javafx.fxml \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     -jar target/mins-bot-1.0.0-SNAPSHOT.jar
```

**Option C — IDE (Eclipse / IntelliJ)**

1. Set main class to `com.minsbot.FloatingAppLauncher`
2. Add VM options: `--add-modules javafx.controls,javafx.web,javafx.fxml --add-opens java.base/java.lang=ALL-UNNAMED`
3. Run

### 5. Use

- A small **swirling orb** appears on your desktop
- **Double-click** the orb to expand the chat panel
- **Type** a message and press Enter or click Send
- **Click the microphone** for voice input
- **Drag** the orb to reposition the window

---

## Configuration

All configuration lives in `src/main/resources/application.properties`. Sensitive values (API keys, tokens) should go in `application-secrets.properties` (gitignored).

### Window Settings

| Property | Description | Default |
|----------|-------------|---------|
| `server.port` | HTTP port | `8765` |
| `app.window.collapsed.width` | Orb width (px) | `45` |
| `app.window.collapsed.height` | Orb height (px) | `45` |
| `app.window.expanded.width` | Chat panel width (px) | `380` |
| `app.window.expanded.height` | Chat panel height (px) | `520` |
| `app.window.initial.x` | Initial X position (-1 = auto) | `-1` |
| `app.window.initial.y` | Initial Y position (-1 = auto) | `-1` |
| `app.window.always-on-top` | Keep window above all others | `true` |

### AI Models

| Property | Description | Default |
|----------|-------------|---------|
| `spring.ai.openai.chat.options.model` | OpenAI chat model | `gpt-5.1` |
| `app.gemini.vision-model` | Gemini vision model | `gemini-3-flash-preview` |
| `app.gemini.reasoning-model` | Gemini reasoning model | `gemini-2.5-pro` |
| `app.gemini-live.model` | Gemini Live audio model | `gemini-2.5-flash-native-audio-latest` |
| `app.gemini-live.source-language` | Live translation source language | `Filipino` |

### Feature Toggles

| Property | Description | Default |
|----------|-------------|---------|
| `app.planning.enabled` | Show task plans before execution | `true` |
| `app.autonomous.enabled` | Autonomous mode when idle | `true` |
| `app.tray.enabled` | System tray icon | `true` |
| `app.cdp.enabled` | Chrome DevTools Protocol | `true` |
| `app.hotkeys.enabled` | Global keyboard hooks | `false` |
| `app.skills.diskscan.enabled` | Disk scan skill | `false` |

### Environment Variables

Spring Boot maps properties to env vars with `_` separators:

```bash
# Example
export SPRING_AI_OPENAI_API_KEY="your-key"
export MINS_BOT_PORT="9090"
mvn spring-boot:run
```

---

## Messaging Platform Setup

All messaging integrations require a **public HTTPS endpoint**. For local development, use [ngrok](https://ngrok.com/):

```bash
ngrok http 8765
```

### Example — Telegram

1. Create a bot via [@BotFather](https://t.me/BotFather) and copy the token
2. Add to `application-secrets.properties`:
   ```properties
   app.telegram.enabled=true
   app.telegram.bot-token=YOUR_BOT_TOKEN
   app.telegram.webhook-url=https://YOUR_NGROK_URL/api/telegram/webhook
   ```
3. Restart the app — the webhook is registered automatically on startup

Other platforms follow the same pattern. See each platform's developer docs for obtaining tokens.

---

## Project Structure

```
mins-bot/
├── pom.xml
├── LICENSE                              # AGPL-3.0 + Commons Clause
├── CLAUDE.md                            # AI assistant project context
│
├── src/main/java/com/minsbot/
│   ├── FloatingAppLauncher.java         # JavaFX entry point
│   ├── MinsbotApplication.java          # Spring Boot entry point
│   ├── WindowBridge.java                # JS <-> Java bridge
│   ├── ChatController.java             # REST API (/api/chat)
│   ├── ChatService.java                # Core reply logic (all platforms)
│   │
│   ├── agent/                           # AI configuration & tools
│   │   ├── AiConfig.java               # ChatClient, memory, tool bindings
│   │   ├── GeminiLiveService.java       # Real-time audio streaming
│   │   ├── GeminiVisionService.java     # Image analysis
│   │   ├── VisionService.java           # Screen capture analysis
│   │   └── tools/                       # 57+ tool implementations
│   │
│   ├── skills/                          # Pluggable skill packages
│   │   └── diskscan/                    # File system browser
│   │
│   ├── config/                          # Secrets loader
│   │
│   └── [Platform]*Config.java           # 10 messaging platform integrations
│       [Platform]*ApiClient.java
│       [Platform]*WebhookController.java
│
├── src/main/resources/
│   ├── application.properties           # All configuration
│   ├── application-secrets.properties   # API keys (gitignored)
│   └── static/                          # Frontend
│       ├── index.html                   # Main UI
│       ├── css/style.css                # Dark theme, animations
│       └── js/app.js                    # Client-side logic
│
└── memory/                              # Persistent data (gitignored)
```

---

## API Endpoints

### Chat
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/chat` | Send a message, get a reply |
| `GET` | `/api/chat/history` | Load recent chat history |
| `GET` | `/api/chat/status` | Poll tool execution updates |
| `GET` | `/api/chat/async` | Poll background task results |
| `POST` | `/api/chat/clear` | Clear memory and transcript |

### Skills
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/skills/diskscan/*` | Browse file system |

### Platform Webhooks
Each enabled platform exposes its webhook endpoint (see table above).

---

## Customization

- **AI behavior** — Edit `ChatService.java` to change how the bot generates replies
- **Tools** — Add new tools in `src/main/java/com/minsbot/agent/tools/`
- **Skills** — Create a new package under `com.minsbot.skills.<name>` with Config, Service, and Controller
- **UI** — Edit files in `src/main/resources/static/` (vanilla HTML/CSS/JS)
- **Bot personality** — Configure directives through the Directives tab in the UI

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `no jfxwebkit in java.library.path` | Use `mvn spring-boot:run` (unpacks natives automatically) or add `-Djava.library.path=target/javafx-natives` |
| `JavaFX runtime components are missing` | Add `--add-modules javafx.controls,javafx.web,javafx.fxml --add-opens java.base/java.lang=ALL-UNNAMED` to VM options |
| Window doesn't appear | Check that port 8765 is free and not blocked by firewall |
| Voice not working | Depends on WebView/system Web Speech API support; try `http://localhost:8765/` in a browser |
| Messaging webhook not receiving | Ensure HTTPS is set up (ngrok) and webhook URL is correct |
| Ollama models not loading | Install Ollama from [ollama.com](https://ollama.com) and pull your desired model |

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
