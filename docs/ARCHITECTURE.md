# Architecture

How Mins Bot is put together.

---

## Stack

| Layer | Tech |
|---|---|
| Window | JavaFX 21 (transparent, always-on-top, draggable) |
| UI | WebView (JavaFX) loading vanilla HTML/CSS/JS from classpath |
| Backend | Spring Boot 3.5 + Spring AI |
| LLM clients | OpenAI / Anthropic / Gemini / Groq / Ollama (offline) |
| Browser automation | Playwright (Chrome CDP) |
| TTS | Piper (local), ElevenLabs / Fish Audio / OpenAI TTS (cloud) |
| Document generation | Apache POI (DOCX, PPTX, XLSX), Flying Saucer (PDF) |
| State | Filesystem only — no database |
| Build | Maven + jpackage for native installers |

---

## Top-level data flow

```
                    ┌─────────────────────────────────────┐
                    │  JavaFX Window (FloatingAppLauncher) │
                    │  ─ swirling-orb collapsed view       │
                    │  ─ chat panel expanded view          │
                    └──────────────┬──────────────────────┘
                                   │ WebView ↔ Java bridge
                                   │ (window.java.expand, etc.)
                                   ▼
                    ┌─────────────────────────────────────┐
                    │  HTML/CSS/JS UI (static/index.html)  │
                    │  fetch() to localhost:8765           │
                    └──────────────┬──────────────────────┘
                                   │ HTTP
                                   ▼
                    ┌─────────────────────────────────────┐
                    │  Spring Boot REST + Spring AI Agent  │
                    │  ─ ChatService — main loop           │
                    │  ─ /api/chat                         │
                    │  ─ tool routing via classifier       │
                    │  ─ memory + transcript persistence   │
                    └─┬────────────┬──────────────┬───────┘
                      │            │              │
                      ▼            ▼              ▼
              ┌────────────┐ ┌─────────┐  ┌──────────────┐
              │ 180 skills │ │ Agent   │  │ Integrations │
              │ (Service + │ │ tools   │  │ Telegram /   │
              │ Controller)│ │ (@Tool) │  │ Discord /    │
              └────────────┘ └─────────┘  │ Slack / etc. │
                                          └──────────────┘
```

---

## Module map

```
com.minsbot
├── FloatingAppLauncher      — JavaFX entry, transparent window, drag/resize, window-state persist
├── MinsbotApplication       — Spring Boot entry
├── ChatService              — main reply loop; routes to skills/tools, manages memory, autonomous mode
├── WindowBridge             — JS ↔ Java (expand, collapse, drag, setPosition)
├── ChatController           — POST /api/chat
├── LocalTtsService          — Piper wrapper + pitch-shift + length-scale persistence
├── TtsSettingsController    — GET/POST /api/tts/* (provider priority, rates, pitch)
├── Setup{Secrets,Wizard}*   — first-run modal + API-key persistence
├── *ApiClient               — Telegram/Discord/Slack/WhatsApp/Messenger/LINE/Teams/WeChat/Signal/Viber
├── *WebhookController       — incoming webhooks for the 9 platforms
├── agent/
│   ├── AsyncMessageService     — async push to chat UI from background tasks
│   ├── ChromeCdpService        — Playwright connection to user's running Chrome
│   ├── PcAgentService          — keyboard/mouse control, screen actions
│   ├── ScreenStateService      — periodic screen capture + caption
│   ├── DeliverableExecutor     — generate PDF/DOCX/PPTX from chat
│   ├── DeliverableFormatter    — markdown → HTML → POI → file
│   ├── ProactiveActionService  — Jarvis-style proactive monitoring
│   ├── SystemPromptService     — assembles the LLM system prompt
│   └── tools/                  — @Tool bridges exposing skills + ad-hoc capabilities to LLM
├── skills/
│   ├── package-info.java       — skills convention docs
│   └── <name>/                 — 180+ self-contained sub-packages
└── memory/                     — file-based key-value persistence
```

For the skills convention, see [SKILLS.md](SKILLS.md).

---

## Critical paths

### A user message arrives

1. WebView's `fetch('/api/chat')` hits `ChatController.chat()`
2. `ChatService.processUser()` enqueues the message
3. Main loop thread:
   - Detects deliverable intents (PDF/PPT/DOCX requests) via `DeliverableIntentInterceptor`
   - Otherwise classifies tools via `ToolRouter` (regex + AI classifier fallback)
   - Calls Spring AI's `ChatClient` with the relevant `@Tool`-annotated methods exposed
   - LLM picks tools, runs them via Spring AI's tool-calling glue
   - Reply stored in transcript + pushed via `AsyncMessageService` to the UI
4. If TTS auto-speak is on, `autoSpeak()` either summarizes or speaks verbatim (narration intent → slower length-scale) via `TtsTools.speakAsync` / `speakNarrationAsync`

### A skill REST call arrives

1. Spring's standard request mapping → `<Name>Controller`
2. Controller checks `props.isEnabled()` (skills off by default)
3. Calls `Service` method
4. Returns JSON via Spring's default Jackson serialization

### The bot speaks

1. Text → `TtsTools.speakAsync(text)`
2. Engine chain (configurable order): Piper local → Fish Audio → ElevenLabs → OpenAI
3. First engine that returns audio wins
4. Audio streamed to JavaFX Media player

### A first-run user opens the bot

1. JS calls `/api/setup/needs` — returns the 3 essential LLM keys not yet set
2. Quick Setup modal renders → user pastes keys → POST `/api/setup/save`
3. Keys written to project-root `application-secrets.properties`
4. Optional "Launch on startup" → POST `/api/firstrun/install-autostart` → calls `AutoStartManagerService.installSelf()` reflectively (so it works whether or not the autostart skill is enabled at compile time)

---

## Filesystem layout

### At runtime

```
<install-root>/                          ← jpackage-bundled app
├── MinsBot.exe (or MinsBot.app)
├── app/mins-bot-1.0.0-SNAPSHOT.jar
└── runtime/                             ← bundled JRE

<working-dir>/                           ← where the user launches the bot
├── application-secrets.properties       ← gitignored, holds real API keys
└── memory/                              ← skill state per-skill subdir
    ├── invoices/
    ├── outreach/
    ├── mentions/
    ├── briefings/
    └── ...

~/mins_bot_data/                         ← user-specific, OS-independent
├── mins-bot.log                         ← app log
├── piper/
│   ├── piper(.exe)                      ← Piper binary
│   ├── voices/*.onnx                    ← voice models
│   ├── .selected-voice                  ← persistent voice pick
│   ├── .pitch-semitones                 ← persistent pitch
│   ├── .length-scale                    ← persistent normal speech rate
│   └── .narration-length-scale          ← persistent narration rate
├── window-state.txt                     ← last window pos/size
└── ...
```

### In source

```
src/main/
├── java/com/minsbot/                    ← Java code (see Module map above)
└── resources/
    ├── application.properties           ← committed, defaults for everything
    ├── application-secrets.properties   ← committed, EMPTY template (guard enforces)
    └── static/                          ← UI: index.html + css/ + js/
```

---

## Persistence approach

Mins Bot has **no database**. State is plain files:
- **Settings** → properties files
- **Skill state** → JSON files under `memory/<skill>/` (one record per file when records grow over time, single state file otherwise)
- **Transcript / chat memory** → markdown files
- **User preferences** (voice, pitch, rate) → tiny single-line text files in `~/mins_bot_data/piper/`

**Why no DB:**
- Mins Bot ships as a single-file installer with no SQL/NoSQL daemon to set up.
- All state is grep-able / editable / backup-able by the user with normal file tools.
- Restart-resilient: load on `@PostConstruct`, write on each mutation.
- Skill portability: dropping a skill into another Spring Boot project doesn't drag a schema with it.

The trade-off: skills with millions of records would need a real store. None currently approach that — `outreachtracker` and `mentiontracker` (the largest record-count skills) are fine at a few thousand records.

---

## Threading

- **JavaFX FX thread** — UI only. `Platform.runLater` for any UI-touching code from non-FX threads.
- **Spring async pool** — `@Async` for fire-and-forget tasks.
- **`ChatService` main loop** — single-threaded message processor; uses a queue to serialize bot replies.
- **`ttsExecutor`** in `TtsTools` — dedicated single-thread executor so consecutive TTS calls play in order.
- **Background scanners** — `Watcher`, `ProactiveActionService`, etc., each on `@Scheduled` cron threads.

Skills should NOT spawn unbounded threads. Use `@Async` or the existing executors.

---

## Configuration model

Three layers, later layers override earlier:

1. **`application.properties`** (committed, in classpath) — defaults for every property
2. **`application-secrets.properties`** (gitignored, project root or `~/`) — user's real API keys
3. **Environment variables** (e.g. `OPENAI_API_KEY`, `MINS_BOT_PORT`) — final override, useful in CI/Docker

A skill reads its config via `@ConfigurationProperties(prefix = "app.skills.<name>")` bound to a `<Name>Properties` POJO defined in `<Name>Config.java`.

---

## Extension points

| Extending… | Where |
|---|---|
| Capabilities | Add a skill — see [SKILLS.md](SKILLS.md) |
| Chat-callable tools | Add a `@Component` with `@Tool` methods under `agent/tools/` |
| LLM providers | Spring AI auto-configures; add the dep + the API key |
| TTS providers | Implement a service like `FishAudioVoiceService` and add to the engine chain in `TtsTools` |
| Messaging platforms | `*Config` + `*ApiClient` + `*WebhookController` (see Viber/Telegram for reference) |
| First-run wizard steps | Add a step to `static/index.html` setup modal + an endpoint to `SetupWizardController` |
| Voices | Drop `.onnx` + `.onnx.json` into `~/mins_bot_data/piper/voices/` — auto-detected by `LocalTtsService` |

---

## Performance notes

- Cold start: ~3-5s on a modern laptop; dominated by JavaFX WebView init.
- LLM call latency: dominated by network. Local Ollama (offline mode) ~500ms first-token; cloud providers ~300-2000ms.
- Skill REST endpoints respond in <50ms for in-memory skills, 100ms-30s for fetch-y / shell-out skills.
- Memory footprint: ~300-500 MB resident. Spike during PDF/PPT generation (POI + Flying Saucer pull a lot).

---

## Why this shape (not microservices, not browser extension)

- **Single process, single binary**: end users get a desktop app, not a stack to operate.
- **Skills are sub-packages, not plugins-on-disk**: no plugin loader complexity, simple compile-time discovery.
- **Native window, not a tab**: it stays out of the way of the user's browser/work.
- **CDP, not a browser extension**: works with the user's existing Chrome session, no separate install path, can be revoked any time by closing CDP.
- **Filesystem state, not DB**: no daemon to install, no migration story.

Every one of these has a downside (single binary = one big jar; sub-packages = recompile to add a skill; filesystem = no concurrent multi-process access). They're acceptable for a single-user desktop assistant.
