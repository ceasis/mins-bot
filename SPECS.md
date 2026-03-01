# SPECS.md — Mins Bot Technical Specification

## Overview

Mins Bot is a floating desktop chatbot built with **Java 17**, **Spring Boot 3.5.3**, **JavaFX 21**, and **Spring AI 1.0.1**. 

A swirling animated ball sits on the desktop; double-click expands a chat panel. It connects to OpenAI for intelligent tool-calling conversations, falls back to regex-based commands offline, and integrates with 9 messaging platforms. It can control the PC, collect files, browse the web, capture screenshots, and maintain persistent conversation history.

**Main class:** `com.minsbot.FloatingAppLauncher`
**Default port:** `8765` (configurable via `MINS_BOT_PORT` env var)
**Build:** Maven — `mvn clean package -DskipTests` / `mvn spring-boot:run`

---

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│  JavaFX Transparent Window (FloatingAppLauncher)               │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │  WebView  ──►  index.html / app.js / style.css            │ │
│  │            ◄──  window.java bridge (WindowBridge)         │ │
│  └───────────────────────────────────────────────────────────┘ │
│  Scene-level mouse event filters (ball drag, click forwarding) │
└──────────────────────────┬─────────────────────────────────────┘
                           │ HTTP (localhost:8765)
┌──────────────────────────▼─────────────────────────────────────┐
│  Spring Boot Web Server                                        │
│                                                                │
│  ChatController ──► ChatService ──► Spring AI ChatClient       │
│       /api/chat          │              │                      │
│       /api/chat/async    │         ┌────▼────────────────┐     │
│       /api/chat/status   │         │  @Tool methods      │     │
│                          │         │  SystemTools        │     │
│                          │         │  BrowserTools (5)   │     │
│                          │         │  FileTools (4)      │     │
│                          │         │  FileSystemTools    │     │
│                          │         │  ChatHistoryTool (3)│     │
│                          │         │  TaskStatusTool (1) │     │
│                          │         │  ClipboardTools (2)│     │
│                          │         │  MemoryTools (4)    │     │
│                          │         │  ImageTools (6)     │     │
│                          │         │  WeatherTools, NotificationTools │
│                          │         │  CalculatorTools, QrTools, DownloadTools │
│                          │         │  HashTools, UnitConversionTools, TimerTools │
│                          │         │  TtsTools, PdfTools  │     │
│                          │         └─────────────────────┘     │
│                          │                                     │
│                          ├──► PcAgentService (regex fallback)  │
│                          │                                     │
│  ┌──────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │ Platform │  │ Core Services│  │ Persistent Storage       │  │
│  │ Webhooks │  │              │  │                          │  │
│  │ Viber    │  │ SystemCtrl   │  │ TranscriptService        │  │
│  │ Telegram │  │ BrowserCtrl  │  │   ~/mins_bot_data/history │  │
│  │ Discord  │  │ FileCollector│  │ MemoryService            │  │
│  │ Slack    │  │ Screenshot   │  │   ./memory/              │  │
│  │ WhatsApp │  │ NativeVoice  │  │ ScreenshotService        │  │
│  │ Messenger│  │              │  │   ~/mins_bot_data/screens │  │
│  │ LINE     │  └──────────────┘  └──────────────────────────┘  │
│  │ Teams    │                                                  │
│  │ WeChat   │  Skills: DiskScan (/api/skills/diskscan/*)       │
│  │ Signal   │                                                  │
│  └──────────┘                                                  │
└────────────────────────────────────────────────────────────────┘
```

---

## Project Structure

```
src/main/java/com/minsbot/
├── MinsbotApplication.java              # Spring Boot @SpringBootApplication entry
├── config/
│   └── OpenAiSecretsLoader.java         # EnvironmentPostProcessor — loads OpenAI API key early from file/env
├── FloatingAppLauncher.java             # JavaFX Application — transparent window + WebView
├── WindowBridge.java                    # JS ↔ Java bridge (expand/collapse/drag/voice)
├── NativeVoiceService.java              # Microphone capture → WAV → OpenAI transcription
├── ChatController.java                  # REST: /api/chat, /api/chat/async, /api/chat/status
├── ChatService.java                     # Core reply logic — Spring AI or regex fallback
├── TranscriptService.java              # Chat history — daily files + 100-message ring buffer
├── ScreenshotService.java              # Periodic desktop captures with auto-cleanup
│
├── agent/
│   ├── AiConfig.java                    # ChatClient + ChatMemory beans
│   ├── PcAgentService.java              # Regex-based command interpreter (offline fallback)
│   ├── SystemContextProvider.java       # System prompt builder (username, OS, time)
│   ├── SystemControlService.java        # Windows process/app control
│   ├── BrowserControlService.java       # Browser automation
│   ├── FileCollectorService.java        # Scan PC for files by category
│   └── tools/
│       ├── SystemTools.java             # System, date/time, env, volume, power, screenshots, network, recent files
│       ├── BrowserTools.java            # 5 @Tool methods → BrowserControlService
│       ├── FileTools.java               # 4 @Tool methods → FileCollectorService (2 async)
│       ├── FileSystemTools.java         # @Tool methods (open/copy/delete/list/read/write/zip/etc.)
│       ├── ChatHistoryTool.java         # 3 @Tool methods → TranscriptService
│       ├── TaskStatusTool.java          # 1 @Tool method (background task status)
│       ├── ClipboardTools.java          # 2 @Tool methods (get/set clipboard text)
│       ├── MemoryTools.java             # 4 @Tool methods (notes) → MemoryService
│       ├── ImageTools.java              # 6 @Tool methods (flip, rotate, grayscale, resize, info)
│       ├── WeatherTools.java            # 1 @Tool (Open-Meteo)
│       ├── NotificationTools.java       # 1 @Tool (tray / toast)
│       ├── CalculatorTools.java        # 1 @Tool (safe arithmetic)
│       ├── QrTools.java                 # 2 @Tool (generate/decode QR)
│       ├── DownloadTools.java           # 1 @Tool (download URL to file)
│       ├── HashTools.java               # 2 @Tool (SHA-256, SHA-1)
│       ├── UnitConversionTools.java     # 1 @Tool (length, weight, temp)
│       ├── TimerTools.java              # 1 @Tool (reminder → notification)
│       ├── TtsTools.java                # 1 @Tool (Windows SAPI read aloud)
│       ├── PdfTools.java                # 1 @Tool (extract text)
│       └── ToolExecutionNotifier.java   # Status message queue for frontend polling
│
├── memory/
│   ├── MemoryConfig.java                # app.memory.* configuration
│   └── MemoryService.java              # File-based key-value store
│
├── skills/
│   ├── package-info.java                # Skills convention documentation
│   └── diskscan/
│       ├── DiskScanConfig.java          # app.skills.diskscan.* configuration
│       ├── DiskScanService.java         # Browse/search filesystem
│       └── DiskScanController.java      # GET /api/skills/diskscan/*
│
└── [Platform integrations — 9 platforms, 3-4 classes each]
    ├── ViberConfig / ViberApiClient / ViberWebhookController / ViberWebhookRegistrar
    ├── TelegramConfig / TelegramApiClient / TelegramWebhookController / TelegramWebhookRegistrar
    ├── DiscordConfig / DiscordApiClient / DiscordWebhookController
    ├── SlackConfig / SlackApiClient / SlackEventController
    ├── WhatsAppConfig / WhatsAppApiClient / WhatsAppWebhookController
    ├── MessengerConfig / MessengerApiClient / MessengerWebhookController
    ├── LineConfig / LineApiClient / LineWebhookController
    ├── TeamsConfig / TeamsApiClient / TeamsWebhookController
    ├── WeChatConfig / WeChatApiClient / WeChatWebhookController
    └── SignalConfig / SignalApiClient / SignalWebhookController

src/main/resources/
├── application.properties               # All configuration
├── application-secrets.properties       # Optional, gitignored — OpenAI API key (see Config Reference)
└── static/
    ├── index.html                       # Root UI
    ├── css/style.css                    # Dark theme, animations
    └── js/app.js                        # Frontend logic
```

---

## Core Components

### FloatingAppLauncher

JavaFX `Application` that creates a transparent always-on-top window containing a WebView.

- **Startup:** Launches Spring Boot on a background thread, waits for readiness, then creates the JavaFX stage
- **Window:** `StageStyle.TRANSPARENT`, no title bar, configurable dimensions
- **Scene event filters:** Intercept ALL mouse events before the WebView receives them
  - Ball area (0–45px top-left): Java handles drag, single-click-to-expand, double-click-to-toggle
  - Outside ball area: Forwarded to HTML via `engine.executeScript("document.elementFromPoint(x,y).click()")`
  - All events consumed to prevent Windows "Copy" taskbar ghosts from native drag
- **WebView transparency:** Reflection hack — accesses WebEngine's private `page` field, calls `setBackgroundColor(0)` for fully transparent ARGB
- **JVM args required:** `--add-opens javafx.web/javafx.scene.web=ALL-UNNAMED --add-opens javafx.web/com.sun.webkit=ALL-UNNAMED`

### WindowBridge (JS ↔ Java)

Injected into the WebView as `window.java`. Exposes to JavaScript:

| Method | Description |
|--------|-------------|
| `expand()` / `collapse()` | Resize stage |
| `isExpanded()` | Current state |
| `getX()` / `getY()` | Window position |
| `setPosition(x, y)` | Move window |
| `isNativeVoiceAvailable()` | Check microphone support |
| `startNativeVoice()` | Begin 8-second capture |
| `stopNativeVoice()` | Cancel capture |
| `isNativeVoiceListening()` | Capture in progress? |
| `consumeNativeVoiceTranscript()` | One-shot transcript result |
| `consumeNativeVoiceError()` | One-shot error message |
| `shutdownNativeVoice()` | Cleanup on exit |

### NativeVoiceService

Captures microphone audio on Windows, sends to OpenAI for transcription.

- **Format:** 16 kHz, 16-bit, mono WAV, 8-second max
- **Flow:** `TargetDataLine` → raw PCM → WAV container → `ChatService.getReplyFromAudio()` → `__AUDIO_RESULT__{"transcript":"...", "reply":"..."}`
- **Thread safety:** Locks + volatile fields + single-threaded daemon executor

### ChatService

Central reply logic used by the desktop UI and all 9 platform integrations.

**Reply tiers:**

1. **Spring AI tool-calling** (when `ChatClient` is available):
   - Builds system message via `SystemContextProvider`
   - Passes user message + 6 tool objects to `chatClient.prompt().tools(...).call()`
   - 25 @Tool methods available to the AI
   - Supports async callbacks for long-running file operations

2. **Regex fallback** (via `PcAgentService.tryExecute()`):
   - Pattern-matching for ~20 command types
   - Works completely offline without an API key

3. **No-AI message** (if `chatClient == null` and no regex match):
   - Returns: "AI is not connected. Set your OpenAI API key in application-secrets.properties (project root) or set the OPENAI_API_KEY environment variable, then restart."

**Audio pipeline:** WAV bytes → `transcribeAudio()` via OpenAI Whisper API → `getReply(transcript)`

**Async operations:** Background task results queued to `ConcurrentLinkedQueue<String>`, polled by frontend via `/api/chat/async`.

**Tool status:** `ToolExecutionNotifier` queue polled by frontend via `/api/chat/status` every 500ms during active requests.

### TranscriptService

Persistent conversation history with in-memory search.

- **Daily files:** `~/mins_bot_data/mins_bot_history/chat_history_yyyyMMdd.dat`
- **Format:** `[yyyy-MM-dd HH:mm:ss] SPEAKER: message text`
- **Speakers:** `USER`, `USER(voice)`, `BOT`, `BOT(error)`, `BOT(agent)`
- **Ring buffer:** Last 100 messages in memory for fast search
- **Search:** Case-insensitive substring matching (both in-memory and file-based)

### ScreenshotService

Periodic desktop screenshot capture with auto-cleanup.

- **Storage:** `~/mins_bot_data/screenshots/yyyy-MM-dd_HH-mm-ss.png`
- **Schedule:** Configurable interval (default 5 seconds)
- **Cleanup:** Deletes files older than `max-age-days` (default 3 days), runs daily

---

## Spring AI Tool System

Spring AI auto-generates tool schemas from `@Tool` annotations and handles the full tool-calling protocol with the LLM. Tool classes are thin wrappers around core services, keeping business logic AI-agnostic.

### SystemTools (many tools)

| Tool | Description | Delegates to |
|------|-------------|--------------|
| `closeAllWindows()` | Kill non-protected user processes | SystemControlService |
| `closeApp(appName)` | Kill specific app by name | SystemControlService |
| `openApp(appName)` | Launch application | SystemControlService |
| `minimizeAll()` | Show desktop (Win+D) | SystemControlService |
| `lockScreen()` | Lock workstation | SystemControlService |
| `takeScreenshot()` | Capture screen immediately | SystemControlService |
| `listRunningApps()` | List running processes | SystemControlService |
| `runPowerShell(command)` | Execute PowerShell, return output | SystemControlService |
| `runCmd(command)` | Execute CMD, return output | SystemControlService |
| `getSystemInfo()` | RAM, CPU, OS, disk, uptime, Java | — |
| `getCurrentDateTime()` | Current date/time and time zone | — |
| `getEnvVar(name)` | Get environment variable value | — |
| `listEnvVars()` | List all env var names | — |
| `mute()` / `unmute()` | Toggle system volume mute | SystemControlService (PowerShell) |
| `sleep()` | Put PC to sleep | runCmd(rundll32) |
| `hibernate()` | Hibernate PC | SystemControlService (shutdown /h) |
| `shutdown(delayMinutes)` | Shut down (optional delay) | SystemControlService |
| `ping(host)` | Ping host, return round-trip | SystemControlService |
| `getLocalIpAddress()` | Local host name and IP | InetAddress / ipconfig |
| `openScreenshotsFolder()` | Open mins_bot_data/screenshots | Desktop |
| `listRecentScreenshots(maxCount)` | List newest screenshot files | — |
| `getRecentFiles(maxCount)` | Windows shell recent items | SystemControlService (PowerShell) |
| **App interaction** | | |
| `listOpenWindows()` | List open windows with titles (process, PID, title) | SystemControlService (PowerShell) |
| `focusWindow(titleOrProcess)` | Bring a window to front by title or process name | SystemControlService (SetForegroundWindow) |
| `sendKeys(keys)` | Send keystrokes to focused window (^v = Ctrl+V, {ENTER}, etc.) | SystemControlService (WScript.Shell) |
| `openAppWithArgs(appName, args)` | Open app with file/URL/folder (e.g. Notepad + path, Chrome + URL) | SystemControlService |

### BrowserTools (5 tools)

| Tool | Description | Delegates to |
|------|-------------|--------------|
| `openUrl(url)` | Open in default browser | BrowserControlService |
| `searchGoogle(query)` | Google search | BrowserControlService |
| `searchYouTube(query)` | YouTube search | BrowserControlService |
| `closeAllBrowsers()` | Kill all browser processes | BrowserControlService |
| `listBrowserTabs()` | List open browser windows | BrowserControlService |

### FileTools (4 tools, 2 async)

| Tool | Description | Async? |
|------|-------------|--------|
| `collectFiles(category)` | Scan all drives, copy matching files | Yes — returns acknowledgment, result via callback |
| `searchFiles(pattern)` | Glob pattern file search | Yes — returns acknowledgment, result via callback |
| `listCollected()` | Show collected files grouped by category | No |
| `openCollectedFolder()` | Open collected folder in explorer | No |

**File categories:** photos, videos, music, documents, archives (with full extension lists).

**Collection target:** `~/mins_bot_data/collected/<category>/`

### FileSystemTools (4 tools)

| Tool | Description |
|------|-------------|
| `openPath(path)` | Open file/folder in explorer |
| `copyFile(source, dest)` | Copy a file |
| `deleteFile(path)` | Delete a file (not directories) |
| `countDirectoryContents(path)` | Count files and directories |

### ChatHistoryTool (3 tools)

| Tool | Description |
|------|-------------|
| `recallRecentConversation(query)` | Search in-memory buffer (last 100 messages) |
| `searchPastConversations(query)` | Search historical daily .dat files |
| `getFullRecentHistory()` | Return full in-memory buffer |

### TaskStatusTool (1 tool)

| Tool | Description |
|------|-------------|
| `taskStatus()` | Show status of background file tasks |

### ClipboardTools (2 tools)

| Tool | Description |
|------|-------------|
| `getClipboardText()` | Read current text from system clipboard |
| `setClipboardText(text)` | Copy text to system clipboard |

### MemoryTools / Notes (4 tools)

| Tool | Description | Delegates to |
|------|-------------|--------------|
| `saveNote(key, value)` | Save a note/reminder under a key | MemoryService |
| `getNote(key)` | Recall a saved note by key | MemoryService |
| `listNoteKeys()` | List all saved note keys | MemoryService |
| `deleteNote(key)` | Delete a saved note | MemoryService |

When `app.memory.enabled=false`, note tools return a disabled message.

### ImageTools (6 tools)

Manipulate images on disk. All operations save to a **new file** (suffix before extension) so originals are not overwritten. Supports PNG, JPG, GIF, BMP.

| Tool | Description |
|------|-------------|
| `flipImageVertical(imagePath)` | Flip top/bottom. Output: `*_vflip.png` (or same ext). |
| `flipImageHorizontal(imagePath)` | Flip left/right. Output: `*_hflip.*`. |
| `imageToBlackAndWhite(imagePath)` | Convert to grayscale. Output: `*_bw.*`. |
| `rotateImage(imagePath, degrees)` | Rotate 90, 180, or 270° clockwise. Output: `*_rot90.*`, etc. |
| `resizeImage(imagePath, width, height)` | Resize to given dimensions. Output: `*_WxH.*` (e.g. `*_800x600.png`). |
| `getImageInfo(imagePath)` | Return dimensions, file size, and format. |

### HuggingFaceImageTool (2 tools)

Discover and run Hugging Face image-classification models locally (ONNX only). Models are downloaded and cached automatically.

| Tool | Description |
|------|-------------|
| `searchHuggingFaceImageModels(search)` | Search HF for image-classification models by keyword (e.g. nsfw, censored). Returns model IDs and tags. |
| `classifyImageWithHf(imagePath, modelId)` | Download model if needed, run local ONNX inference (e.g. suko/nsfw for NSFW detection). Returns class scores. |

Config: `app.huggingface.cache-dir` (default `~/.cache/mins_bot/hf_models`).

### WeatherTools (1 tool)

Uses [Open-Meteo](https://open-meteo.com/) (free, no API key). Geocodes place names and returns current conditions.

| Tool | Description |
|------|-------------|
| `getWeather(location)` | Get current weather for a city or place (e.g. "New York", "London"). Returns temperature °C, conditions, humidity, wind. Accepts "lat,lon" for coordinates. |

### NotificationTools (1 tool)

| Tool | Description |
|------|-------------|
| `showNotification(title, message)` | Show a system notification (desktop popup / tray balloon). Use when the user asks to be notified, reminded, or when a long task finishes. Falls back to a message if system tray is not supported. |

### CalculatorTools (1 tool)

Safe arithmetic only (no script injection). Supports +, -, *, /, parentheses, ^ (power).

| Tool | Description |
|------|-------------|
| `calculate(expression)` | Evaluate expression (e.g. "280 * 0.15", "(1+2)*3", "2^10"). Returns exact result. |

### QrTools (2 tools)

Generate and decode QR codes (ZXing).

| Tool | Description |
|------|-------------|
| `generateQr(content, outputPath)` | Create QR code image from text/URL; save to file. |
| `decodeQr(imagePath)` | Read QR code from image file; return decoded text. |

### DownloadTools (1 tool)

| Tool | Description |
|------|-------------|
| `downloadFile(url, savePath)` | Download file from URL and save to local path. |

### HashTools (2 tools)

| Tool | Description |
|------|-------------|
| `fileSha256(filePath)` | SHA-256 checksum of file. |
| `fileSha1(filePath)` | SHA-1 checksum of file. |

### UnitConversionTools (1 tool)

| Tool | Description |
|------|-------------|
| `convert(value, fromUnit, toUnit)` | Convert length (mile, km, foot, m), weight (lb, kg), temperature (celsius, fahrenheit). |

### TimerTools (1 tool)

| Tool | Description |
|------|-------------|
| `setReminder(delayMinutes, title, message)` | After delay (1–1440 min), show a system notification. Use for "remind me in X minutes". |

### TtsTools (1 tool)

| Tool | Description |
|------|-------------|
| `speak(text)` | Read text aloud via Windows SAPI (PowerShell). Use for "read aloud" / "say". |

### PdfTools (1 tool)

| Tool | Description |
|------|-------------|
| `extractPdfText(pdfPath)` | Extract plain text from PDF (Apache PDFBox). Use to summarize or answer questions about a PDF. |

### App interaction (SystemTools)

- **listOpenWindows()** — Lists all processes that have a visible window (process name, PID, window title). Use so the user or AI can pick which window to focus.
- **focusWindow(titleOrProcess)** — Brings the first matching window to the front (partial match on title or process name). Uses Windows `SetForegroundWindow` via PowerShell.
- **sendKeys(keys)** — Sends keystrokes to the *currently focused* window. Uses WScript.Shell SendKeys: `+` = Shift, `^` = Ctrl, `%` = Alt; `{ENTER}`, `{TAB}`, `{ESC}`, `{DOWN}`, `{UP}`, etc. Example: `^v` = paste, `Hello{ENTER}` = type and press Enter.
- **openAppWithArgs(appName, args)** — Launches an app with optional arguments: Notepad + file path, Chrome/Edge/Firefox + URL, Explorer + folder path. Generic fallback: `start appName args`.

### ToolExecutionNotifier

Shared `@Component` with a `ConcurrentLinkedQueue<String>` for cross-cutting tool status messages. Every `@Tool` method calls `notifier.notify("Doing X...")` before execution. Frontend polls `/api/chat/status` to display these in real-time.

---

## Core Services

### SystemControlService

Windows system operations via `ProcessBuilder` and `Runtime.exec()`.

- **Process control:** Close all (with protected process whitelist), close by name, open app
- **Protected processes:** `java.exe`, `explorer.exe`, `csrss.exe`, `svchost.exe`, `winlogon.exe`, `lsass.exe`, `dwm.exe`, `MsMpEng.exe`, and others
- **App name mapping:** 60+ apps mapped to executable names (chrome → chrome.exe, word → WINWORD.EXE, etc.)
- **Shell execution:** PowerShell and CMD with 2000-char output limit
- **Desktop operations:** Minimize all (Win+D via PowerShell SendKeys), lock screen (rundll32 user32.dll)
- **Screenshot:** `java.awt.Robot` screen capture to PNG

### BrowserControlService

Browser automation via system commands.

- **Open URL:** `Desktop.getDesktop().browse()` or browser-specific process launch
- **Search:** Constructs Google/YouTube URLs with encoded queries
- **Tab listing:** PowerShell `Get-Process` with MainWindowTitle parsing
- **Close:** `taskkill /IM` for chrome, firefox, msedge, brave, opera

### FileCollectorService

Scans the entire PC for files matching a category, copies them to a centralized folder.

- **Categories:** photos (.jpg, .png, .gif, .heic, .raw, etc.), videos (.mp4, .mkv, .mov, etc.), music (.mp3, .flac, .ogg, etc.), documents (.pdf, .docx, .xlsx, etc.), archives (.zip, .rar, .7z, etc.)
- **Scan roots:** User home + all filesystem roots (depth limit 30)
- **Skip directories:** Windows system dirs, `node_modules`, `.git`, `.gradle`, `target`, hidden dirs
- **Deduplication:** Flattened path naming with counter on collision
- **Output:** `~/mins_bot_data/collected/<category>/`

### PcAgentService (Regex Fallback)

Offline command interpreter using regex patterns. Handles ~20 command types:

- File operations: collect, list collected, search, open path
- System control: close all/specific, minimize, lock, screenshot, list running
- Browser: Google search, YouTube search, open URL, list tabs
- App management: open/launch specific apps
- Shell: PowerShell and CMD execution
- File management: copy, delete

Long-running commands execute on a background `ExecutorService` with async callback.

---

## REST API

### Chat Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/chat` | Send message, get reply. Body: `{"message": "..."}` Response: `{"reply": "..."}` |
| GET | `/api/chat/async` | Poll for background task results. Response: `{"hasResult": bool, "reply": "..."}` |
| GET | `/api/chat/status` | Poll for tool execution status. Response: `{"messages": ["...", "..."]}` |

### Platform Webhooks

| Platform | Endpoint |
|----------|----------|
| Viber | `POST /api/viber/webhook` |
| Telegram | `POST /api/telegram/webhook` |
| Discord | `POST /api/discord/interactions` |
| Slack | `POST /api/slack/events` |
| WhatsApp | `POST /api/whatsapp/webhook` |
| Messenger | `POST /api/messenger/webhook` |
| LINE | `POST /api/line/webhook` |
| Teams | `POST /api/teams/messages` |
| WeChat | `POST /api/wechat/webhook` |
| Signal | `POST /api/signal/webhook` |

### Skills

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/skills/diskscan/roots` | List filesystem roots with space info |
| GET | `/api/skills/diskscan/browse?path=...` | Browse directory contents |
| GET | `/api/skills/diskscan/info?path=...` | Get file/directory info |
| GET | `/api/skills/diskscan/search?basePath=...&pattern=...` | Glob pattern search |

---

## Platform Integrations

All 9 platforms follow the same pattern:

```
*Config.java           → @ConfigurationProperties, enabled=false by default
*ApiClient.java        → HTTP client (RestTemplate) for platform API
*WebhookController.java → Receives callbacks, calls ChatService.getReply()
*WebhookRegistrar.java  → (optional) Auto-registers webhook URL on startup
```

| Platform | Auth mechanism | API |
|----------|---------------|-----|
| Viber | X-Viber-Auth-Token header | chatapi.viber.com/pa |
| Telegram | Bot token in URL path | api.telegram.org |
| Discord | Bot token + public key signature verification | discord.com/api |
| Slack | Bot token + signing secret | slack.com/api |
| WhatsApp | Bearer access token (Meta Cloud API) | graph.facebook.com |
| Messenger | Page access token + app secret | graph.facebook.com |
| LINE | Channel access token + channel secret | api.line.me |
| Teams | App ID + password (Bot Framework) | botframework.com |
| WeChat | App ID + secret + AES key | api.weixin.qq.com |
| Signal | Local signal-cli-rest-api instance | localhost (configurable) |

All platforms use `ChatService.getReply()` for response generation. No platform-specific business logic exists in webhook controllers.

---

## Frontend

### UI Structure

- **Ball:** 34x34px gradient circle with 3-second swirl animation, top-left corner
- **Chat panel:** Dark theme, slides in on expand with opacity transition
  - Header: "Mins Bot" title + close (collapse) and clear buttons
  - Messages log: Scrollable, user messages right-aligned (blue), bot messages left-aligned (dark)
  - Input row: Text input + voice button + send button
  - Voice status indicator (red pulse when listening)

### Interactions

- **Ball click:** Expand panel
- **Ball double-click:** Toggle expand/collapse
- **Ball drag:** Move window (via Java event filter)
- **Enter key:** Send message
- **Voice button:** Toggle native voice capture (8-sec recording → transcription → reply)

### Polling

| Endpoint | Interval | Purpose |
|----------|----------|---------|
| `/api/chat/status` | 500ms (during active request) | Tool execution status updates |
| `/api/chat/async` | 2000ms (always when expanded) | Background task results |
| Native voice state | 180ms (while listening) | Transcript/error/listening state |

### Theme

Dark mode with zinc/slate colors. Custom thin scrollbar. Message animations (fade + slide). Thinking dots animation while waiting for reply.

---

## Data Storage

All file-based, no database.

| Data | Location | Format |
|------|----------|--------|
| Chat history | `~/mins_bot_data/mins_bot_history/chat_history_yyyyMMdd.dat` | `[timestamp] SPEAKER: text` |
| Screenshots | `~/mins_bot_data/screenshots/yyyy-MM-dd_HH-mm-ss.png` | PNG images |
| Collected files | `~/mins_bot_data/collected/<category>/` | Original files (copied) |
| Key-value memory | `./memory/<key>` | Plain text files |

---

## Configuration Reference

### application.properties

```properties
# Server
server.port=${MINS_BOT_PORT:8765}

# Window dimensions
app.window.collapsed.width=45          # Ball size
app.window.collapsed.height=45
app.window.expanded.width=380          # Chat panel size
app.window.expanded.height=520
app.window.initial.x=-1               # -1 = center screen
app.window.initial.y=-1
app.window.always-on-top=true
app.window.hover.expand.delay-ms=150
app.window.hover.collapse.delay-ms=400

# Spring AI — API key loaded from secrets file or env (see below)
spring.ai.openai.api-key=${OPENAI_API_KEY:${SPRING_AI_OPENAI_API_KEY:}}
spring.ai.openai.base-url=https://api.openai.com
spring.ai.openai.chat.options.model=gpt-4o-mini

# Audio transcription (shares Spring AI key)
app.openai.api-key=${spring.ai.openai.api-key:}
app.openai.transcription-model=gpt-4o-mini-transcribe

# Screenshots
app.screenshot.enabled=true
app.screenshot.interval-seconds=5
app.screenshot.max-age-days=3

# Memory
app.memory.enabled=true
app.memory.base-path=memory

# Skills
app.skills.diskscan.enabled=false
app.skills.diskscan.max-depth=20
app.skills.diskscan.max-results=500

# Platform integrations (all disabled by default)
app.viber.enabled=false
app.telegram.enabled=false
app.discord.enabled=false
app.slack.enabled=false
app.whatsapp.enabled=false
app.messenger.enabled=false
app.line.enabled=false
app.teams.enabled=false
app.wechat.enabled=false
app.signal.enabled=false
```

### OpenAI API key (required for AI conversations)

**`OpenAiSecretsLoader`** (EnvironmentPostProcessor, highest precedence) runs before Spring AI and sets `spring.ai.openai.api-key` so the key is always found regardless of working directory. It loads in this order (first non-empty wins):

1. **Already set** in Environment (e.g. from `application.properties` or env)
2. **Environment variables:** `OPENAI_API_KEY`, then `SPRING_AI_OPENAI_API_KEY`
3. **File `application-secrets.properties`** (first existing file with the property):
   - `./application-secrets.properties` (current working directory)
   - `{user.dir}/application-secrets.properties`
   - Classpath: `application-secrets.properties` (e.g. `src/main/resources/application-secrets.properties`)

**Recommended:** Put `application-secrets.properties` in **`src/main/resources/`** so it is on the classpath. Copy from `application-secrets.properties.example` at project root, set `spring.ai.openai.api-key=sk-your-key`, then restart. The file is gitignored.

If no key is set, `ChatClient` is not created; the bot uses regex fallback and shows the "AI is not connected" message for free-form chat.

### application-secrets.properties (gitignored)

```properties
spring.ai.openai.api-key=sk-...
# Platform tokens as needed
```

---

## Security

| Area | Protection |
|------|-----------|
| Memory service | Key validation regex + path traversal prevention |
| Disk scan | Blocklist for system directories (System32, $Recycle.Bin, /proc, /sys) |
| File collector | Skips protected/system/hidden directories |
| Process control | Protected process whitelist (java.exe, explorer.exe, system services) |
| Shell execution | 2000-char output limit on PowerShell/CMD |
| Audio capture | 8-second time limit |
| File deletion | Files only (not directories) |
| API keys | Stored in gitignored secrets file, never in main properties |

---

## Build & Dependencies

### pom.xml

- **Parent:** `spring-boot-starter-parent:3.5.3`
- **Spring AI:** `spring-ai-starter-model-openai:1.0.1`
- **JavaFX:** `javafx-controls`, `javafx-web`, `javafx-fxml` — all `21.0.1`
- **Platform profiles:** Windows/Mac/Linux — unpack JavaFX natives at build time
- **ZXing:** `com.google.zxing:core`, `javase` 3.5.3 — QR generate/decode
- **PDFBox:** `org.apache.pdfbox:pdfbox` 3.0.3 — PDF text extraction

### JVM Arguments

```
-Djava.library.path=${project.basedir}/target/javafx-natives
--add-opens javafx.web/javafx.scene.web=ALL-UNNAMED
--add-opens javafx.web/com.sun.webkit=ALL-UNNAMED
```

### Build Commands

```bash
mvn clean package -DskipTests    # Full build with JavaFX natives
mvn spring-boot:run              # Development run
mvn compiler:compile             # Compile only (when jfxwebkit.dll locked)
```

---

## Spec maintenance

**When adding or changing requirements:** Update this SPECS.md so it stays the single source of truth. Document new config properties, endpoints, behavior, and user-facing messages here.

---

## Hugging Face & local ML models (exploration)

**Goal:** Let the AI decide when an image needs classification (e.g. “is this censored?”), find a suitable model on Hugging Face, download and set it up locally, run inference on the image, and return the result.

### What’s possible

| Approach | Discovery | Download | Run locally | Notes |
|----------|-----------|----------|-------------|--------|
| **HF REST API** | ✅ `GET /api/models?pipeline_tag=image-classification&search=nsfw` returns model IDs, tags, `library_name` | ✅ `https://huggingface.co/<id>/resolve/main/<path>` for file download (no auth for public) | N/A | Public, no token needed for listing/download |
| **HF Inference API (cloud)** | Model ID in request | N/A | ❌ Runs on HF servers | Needs `HF_TOKEN`; not local |
| **Local ONNX** | Same REST API to find models; filter by tag `onnx` | Same URL; cache to e.g. `~/.cache/mins_bot/hf_models/<id>/` | ✅ **ONNX Runtime Java** or DJL | Some HF image models ship ONNX (e.g. `suko/nsfw`) |
| **Local PyTorch/Transformers** | Same | Same or `huggingface-cli download` | ✅ Python subprocess + `transformers` | Requires Python env; good for any HF model |

**Example ONNX model:** `suko/nsfw` — image-classification, tag `onnx`, input shape `[1, 224, 224, 3]`, output 2 classes (e.g. Naked / Safe). Can be downloaded and run entirely in Java with ONNX Runtime.

### Recommended flow (implemented)

1. **Discovery:** Tool calls Hugging Face API to list image-classification models (optional search, e.g. “nsfw”, “censored”). Returns model IDs and metadata so the AI can choose one.
2. **Download:** For a chosen model ID, resolve repo file list (e.g. `/api/models/<id>/tree/main`), download `model.onnx` (and `signature.json` / `labels.txt` if present) to a local cache directory. Skip if already cached.
3. **Run locally:** For ONNX models, load the cached `.onnx` with **ONNX Runtime for Java**, preprocess the image (resize to model input size, e.g. 224×224, normalize to float), run inference, map output tensor to labels. Return a short classification result to the user.

**Limitations:** Only **ONNX** image models are run locally from Java. PyTorch/safetensors models would require a Python helper or conversion to ONNX. The AI can still *discover* any HF model; for non-ONNX models the tool can report “model is not ONNX; use HF Inference API or a Python script to run it”.

### Implementation (Mins Bot)

- **HuggingFaceService:** HTTP client for `huggingface.co/api/models` (list) and `huggingface.co/<id>/resolve/main/<path>` (download); cache under `app.huggingface.cache-dir` (default `~/.cache/mins_bot/hf_models`).
- **HuggingFaceImageTool:**  
  - `searchHuggingFaceImageModels(search)` — lists image-classification models (optional search term).  
  - `classifyImageWithHf(imagePath, modelId)` — ensures the model is in cache (downloads if needed), runs local ONNX inference if the model is ONNX, returns classification (e.g. “Safe: 0.98, Naked: 0.02”). For non-ONNX models, returns a message suggesting HF Inference API or Python.
- **HuggingFaceOnnxClassifier:** runs cached model.onnx (224×224 input), reads signature.json for labels.
- **Dependencies:** `com.microsoft.onnxruntime:onnxruntime` (1.19.2). No Python required for ONNX path.

### Spec maintenance

**When adding or changing requirements:** Update this SPECS.md so it stays the single source of truth. Document new config properties, endpoints, behavior, and user-facing messages here.

---

## Potential additions (roadmap)

Ideas to make Mins Bot the most capable desktop bot. Ordered by impact vs effort.

| Capability | Why it matters | Effort |
|------------|----------------|--------|
| **Weather** | "What's the weather?" — one of the most common questions. Open-Meteo API is free, no key. | Low: HTTP GET + parse JSON. |
| **System notification / toast** | Bot can say "I'll notify you when done" and actually show a Windows toast. "Remind me in 10 min" → timer → toast. | Low: Java SystemTray or Windows toast API. |
| **Timer / reminder** | "Set a timer for 20 minutes" — background delay then notify (toast or chat). Complements notes. | Medium: scheduler + notification. |
| **Text-to-speech (TTS)** | "Read this out loud" — bot speaks the response. Huge for accessibility and hands-free use. | Medium: Windows SAPI, or free TTS API. |
| **Calculator / safe math** | "What's 15% of 280?" — exact answer, no LLM hallucination. Safe expression eval or PowerShell. | Low. |
| **QR code** | Generate QR from URL/text; decode QR from image (ZXing). Share links, read codes. | Low: ZXing library. |
| **Download file from URL** | "Download this file to my Desktop" — generic save from URL to path. | Low: HTTP GET + save. |
| **PDF text extraction** | "What does this PDF say?" — extract text so the LLM can summarize/answer (Apache PDFBox). | Medium: add dependency + tool. |
| **Hash / checksum** | "What's the SHA-256 of this file?" — verification, security. | Low: Java MessageDigest. |
| **Unit conversion** | "Convert 5 miles to km" — simple formulas (length, weight, temp). | Low. |
| **Email (read/send)** | "Send an email to X", "Check my inbox" — IMAP/SMTP or OAuth. | High: auth + protocols. |
| **Calendar** | "What's on my calendar?", "Schedule a meeting" — Google Calendar / Outlook API. | High: OAuth + API. |

**Implemented:** Weather, Notification, Calculator, QR (generate/decode), Download file, Hash (SHA-256/SHA-1), Unit conversion, Timer/reminder, TTS (Windows SAPI), PDF text extraction.

---

## Adding New Components

### New Messaging Platform

1. Create `*Config.java` with `@ConfigurationProperties(prefix="app.<name>")`, `enabled=false`
2. Create `*ApiClient.java` with RestTemplate HTTP client
3. Create `*WebhookController.java` at `POST /api/<name>/webhook`
4. Inject `ChatService`, call `getReply()` for responses
5. Add `app.<name>.*` properties to `application.properties`

### New AI Tool

1. Create `@Component` class in `com.minsbot.agent.tools`
2. Annotate methods with `@Tool(description="...")` and parameters with `@ToolParam`
3. Inject `ToolExecutionNotifier` and call `notifier.notify()` before execution
4. Add the tool bean to `ChatService` constructor and `.tools(...)` call

### New Skill

1. Create package `com.minsbot.skills.<name>/`
2. Add `*Config.java` with `enabled=false`, `*Service.java`, `*Controller.java`
3. Endpoints under `/api/skills/<name>/*`
4. Add `app.skills.<name>.*` properties to `application.properties`

## REMEMBER THESE BASIC RULES
- Do not ask the human user to do the task for you (bot).
- Do not ask for confirmation, just do it.
- Be resourceful. 
  - If you are missing an info, check your screen, webcam, audio, chat history.
  - If you do not know anything, search the internet.
- Never quit. Never give up. Keep on trying.

## CHAT Conversation BOX
- under chat tab
- new messages appear here including responses from the bot
- user can select the text both from user and bot
- bot message is left side, user message is right side.
- show hh:mm:ss (hours minutes seconds)

## Ask Me Anything Chat Input Box
- user can type a message
- user can highlight the text in the box

## MAIN LOOP LOGIC
- Identify if task is SIMPLE or COMPLEX
  - If Simple - process immediately
  - If Simple - but lacks info to answer
    - Gather input (screenshot, audio, chat history etc)
    - Provide answer
  - If Complex
    - Gather input (screenshot, audio, chat history etc)
    - Create plan execution (update @todolist.txt, identify skills)
    - Tell in user chat about the plan.
    - Loop thru the todolist and update as you complete the tasks.
      - Do the task
      - Take screenshot - AI analyze if task was completed
      - If completed then update todolist.txt
      - If not completed
        - Identify what is the resolution
        - Identify if only needs retry
        - Identify if need to replan
    - Verify if task is completed, take screenshot and AI will analyze completion.
    - Once complete, tell user that task was complete (list the tasks completed.)

### Directives ({window_user}/mins_bot_data/directives.txt)
- This is like long term objectives
- Always running until while bot is active
- Starts when user is not using the computer

### FOR EVERY CHAT
- Identify if there is anything related to these needs to be updated:
  - mins_bot_data/minsbot_config.txt
  - mins_bot_data/personal_config.txt
  - mins_bot_data/cron_config.txt
  - mins_bot_data/playlist_config.txt
  - mins_bot_data/todolist_config.txt



## LONG TERM MEMORY - folder: {user}/mins_bot_data/
- this is the only folder we use for long term memory
- all configuration, tasks etc

### Cron Config
- for all chat input, check if chat has schedule info
- update mins_bot_data/cron_config.txt for the info
- update the user once config was updated.

### Personal Config
- for all chat input, check if chat has personal info
- update mins_bot_data/personal_config.txt for the info
- update the user once config was updated.

### Complex tasks
1. Identify if task is complex 
2. Gather input (screenshot, audio, chat history etc)
3. Create plan execution (update @todolist.txt)
4. Tell in user chat about the plan.
5. Loop thru the todolist and update as you complete the tasks.
6. Verify if task is completed, take screenshot and AI will analyze completion.
7. Once complete, tell user that task was complete (list the tasks completed.)

### Browser tasks
1. Use browser CDP (Chrome Dev Tools) first
2. Use the screenshot + move mouse combination
- Always verify with Screenshot+AI if step was done.

## BROWSER AUTOMATION (hierarchy)
Gemini said
In 2026, the Java ecosystem for autonomous browsing has caught up significantly. You no longer have to switch to Python to get "agentic" capabilities.

Here is your Top 1 to 5 choice list for Java, sorted by autonomy and modern features, including how to handle fallbacks.

1. Stagehand (Java SDK) — The Absolute Best
As of early 2026, Stagehand is the only major "agentic" framework with a first-class Java SDK. It allows you to use natural language commands directly in your Java code.

The Power: It uses a "Vision + DOM" approach. You can call page.act("find the login button and sign in") and it handles the mouse and keyboard.

How it uses Java: It wraps a high-performance binary, giving you a clean, typed API.

Best for: High-level autonomous goals.

2. Playwright for Java — The Reliable Engine
If Stagehand is the "brain," Playwright is the "hands." It is the most robust way to control a browser in Java today.

The Power: It is natively faster than Selenium and has built-in Auto-Wait, so your bot won't crash if a page loads slowly.

Fallback Strategy: Most Java developers use Playwright as their primary tool and only drop down to other options if a site has heavy anti-bot protection.

3. Selenium with WebDriver BiDi — The Compatibility King
Selenium 5 (the standard in 2026) has fully embraced the Bi-Directional (BiDi) protocol.

The Power: It finally matches Playwright's speed but retains the massive Java ecosystem.

Use Case: Use this as a fallback if you need to support legacy browsers or if your enterprise environment is strictly locked into the Selenium grid.

4. LangChain4j + Playwright — The Custom "Brain"
If you want to build your own version of "Browser-use" in Java, you combine LangChain4j (the Java version of LangChain) with Playwright.

The Power: You feed the HTML or screenshots from Playwright into LangChain4j, which asks an LLM (like Gemini or GPT-4) what to do next.

Best for: Building a proprietary autonomous bot where you want full control over the AI's "thought process."

5. SeleniumBase (via JNI or CLI) — The Stealth Fallback
There isn't a native Java "SeleniumBase," but many Java teams use it as a last-resort fallback for "unscrappable" sites.

The Power: It has the best "Undetected" mode to bypass Cloudflare.

The Setup: You run a small Python sidecar or CLI command that uses SeleniumBase, and your Java app communicates with it via a simple API or JSON.

## SCREENSHOT VERIFICATION
- Send the screenshot to the vision model (use the latest, best).
- Send the actual image, not image to text and then decide.

### TRY AGAIN
- only check the last 3 chat conversations for the task.
- identify what is that task that needs to be tried again (use AI).

### CONTINUE
- check the mins_bot_data/todolist.txt for the latest pending tasks a continue.
- check latest chat history for context. 

### CRON SCHEDULES
- you have a memory of scheduled items
- check every 10 seconds if something needs to run.
- if there is, execute it

### DEALING WITH EXCEL FILE
- if creating new, then need a blank workbook

## SKILLS TAB in WEB APP
- show list of skills
- add upload skills feature
- add publish skills feature
  - Type in Author
  - Java File to be uploaded

## SKILLS MARKET TAB in WEB APP (tbd)
- show list of skills in marketplace
- you can download a skill from the market
- show author, date, how many downloads

## SCHEDULES TAB in WEB APP
- list all the cron schedules

## TODO LIST tab (loaded from todolist.txt)
- all the tasks, latest on top

## DIRECTIVES tab
- permanent objectives like: 
  tell me if there is a new model of toyota fortuner
  find ways to reduce AWS costs
  find the best apartment for sale in new york under 1M usd

  ## WATCH MODE
  - screenshot every time user click (AI analyze)
  - screenshot every 1 second (AI analyze)
  - this mode will be terminated only after 
    > 30 minutes of no action from user.
    > if user tells explicitly terminate the watch mode.








