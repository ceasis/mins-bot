# CLAUDE.md — Mins Bot

## Project Overview
Mins Bot is a floating desktop chatbot (Java 17, Spring Boot 3.2.5, JavaFX 21). A swirling ball sits on the desktop; double-click expands a chat panel. It connects to 9 messaging platforms and supports pluggable skills.

## Tech Stack
- **Java 17** + **Spring Boot 3.2.5** + **JavaFX 21** (WebView for UI)
- **Maven** build system
- Frontend: vanilla HTML/CSS/JS served as static resources
- No database — persistent storage via `memory/` folder (file-based key-value)

## Build & Run
```bash
mvn clean package -DskipTests
mvn spring-boot:run
```
Main class: `com.botsfer.FloatingAppLauncher`
Server port: `8765` (configurable via `MINS_BOT_PORT` env var)

## Project Structure
```
src/main/java/com/botsfer/
├── BotsferApplication.java          # Spring Boot entry
├── FloatingAppLauncher.java         # JavaFX entry, transparent window + WebView
├── WindowBridge.java                # JS ↔ Java bridge (expand/collapse/drag)
├── ChatController.java              # POST /api/chat
├── ChatService.java                 # Shared reply logic for all platforms
├── *Config.java / *ApiClient.java / *WebhookController.java
│   └── (Viber, Telegram, Discord, Slack, WhatsApp, Messenger, LINE, Teams, WeChat, Signal)
├── memory/
│   ├── MemoryConfig.java            # app.memory.* properties
│   └── MemoryService.java           # key-value file persistence
└── skills/
    ├── package-info.java            # skills convention docs
    └── diskscan/                    # portable disk scan skill
        ├── DiskScanConfig.java      # app.skills.diskscan.* properties
        ├── DiskScanService.java     # file system operations (java.nio)
        └── DiskScanController.java  # GET /api/skills/diskscan/*

src/main/resources/
├── application.properties           # all config lives here
└── static/                          # frontend (index.html, css/, js/)

memory/                              # persistent data folder (gitignored contents)
```

## Key Patterns

### Configuration
Each integration/skill uses:
- `@Configuration` class with `@Bean @ConfigurationProperties(prefix = "app.<name>")`
- Nested static `Properties` POJO with `enabled = false` by default
- Properties in `application.properties` under `app.<name>.*`

### REST Endpoints
- `@RestController @RequestMapping("/api/...")`
- Request/response as `Map<String, Object>` (JSON)
- Platform webhooks: `POST /api/<platform>/webhook`
- Skills: `GET /api/skills/<skillname>/*`

### Frontend
- Vanilla JS with `fetch('/api/...')` calls
- JavaFX WebView loads `http://localhost:<port>/`
- `window.java` bridge for native window control (expand, collapse, drag, setPosition)

### Skills System
- Self-contained sub-packages under `com.botsfer.skills.<name>`
- Spring auto-discovers via component scanning (no registration needed)
- Portable: copy the package + its properties block to share
- Convention: `app.skills.<name>.enabled`, endpoints at `/api/skills/<name>/*`

### Memory System
- `MemoryService`: `save(key, value)`, `load(key)`, `delete(key)`, `listKeys()`
- Files stored in `memory/` directory at project root
- Keys must be safe filenames (alphanumeric, dots, dashes, underscores)
- Path traversal protection built in

## Important Implementation Details

### JavaFX Window
- `StageStyle.TRANSPARENT` — no title bar, transparent background
- Event filters on the Scene intercept mouse events BEFORE the WebView
- Ball area: top-left 64x64 — consume MOUSE_PRESSED to prevent WebView native drag
- Outside ball area: `webView.requestFocus()` to ensure text input works
- Double-click on ball toggles expand/collapse

### Platform Integrations
All 9 platforms follow the same pattern as Viber (the first integration):
1. `*Config.java` — properties with `enabled=false`
2. `*ApiClient.java` — HTTP client using RestTemplate
3. `*WebhookController.java` — receives platform callbacks
4. (optional) `*WebhookRegistrar.java` — auto-registers webhook on startup

All use `ChatService.getReply()` for responses.

### Security
- Disk scan skill: hard-coded blocklist (System32, $Recycle.Bin, /proc, /sys) + configurable `blocked-paths`
- Memory service: key validation regex + path traversal check
- All skills disabled by default

## Common Tasks

### Add a new messaging platform
1. Create `*Config.java`, `*ApiClient.java`, `*WebhookController.java` in `com.botsfer`
2. Add `app.<platform>.*` properties to `application.properties`
3. Inject `ChatService` and call `getReply()` for responses

### Add a new skill
1. Create package `com.botsfer.skills.<skillname>/`
2. Add `*Config.java` (with `enabled=false`), `*Service.java`, `*Controller.java`
3. Endpoints under `/api/skills/<skillname>/*`
4. Add `app.skills.<skillname>.*` properties to `application.properties`

### Customize bot responses
Edit `ChatService.getPlaceholderReply()` or replace with your own logic.
