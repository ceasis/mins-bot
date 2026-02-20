# Mins Bot

A **Java 17** **Spring Boot** desktop app that runs as a **floating UI** on Windows or Mac. It shows a swirling ball that expands into a **chatbot interface with voice** when you hover over it, and collapses back to the ball when the mouse leaves.

All configuration is in `application.properties`.

## Features

- **Floating window**: Always-on-top, draggable, no title bar.
- **Collapsed state**: A single swirling animated ball (gradient orb).
- **Expanded state** (on mouse over): Full chat UI with message history, text input, and send button.
- **Voice input**: Microphone button uses the Web Speech API (browser/WebView) for speech-to-text.
- **REST chat API**: `POST /api/chat` with `{"message":"..."}`; reply comes from the backend (OpenAI tool-calling by default; you can plug in your own).
- **Messaging integrations** (optional): Connect the same chatbot to **10 platforms** via webhooks. Each integration is disabled by default; enable and set tokens in `application.properties` (or secrets). All use the same reply logic (`ChatService`).

## Requirements

- **Java 17** (JDK 17 or later)
- **Maven 3.6+**
- Windows or macOS (Linux may work but is untested)

## Setup

### 1. Clone or open the project

```bash
cd mins-bot
```

### 2. Build

```bash
mvn clean package -DskipTests
```

### 3. Run

**Option A – Maven (recommended)**

```bash
mvn spring-boot:run
```

On the first run, Maven unpacks the JavaFX Web native library (e.g. `jfxwebkit.dll` on Windows) into `target/javafx-natives` and passes it as `java.library.path` so the floating window’s WebView works. This is done automatically via the OS-specific profile (Windows/Mac/Linux).

If you see an error like *"JavaFX runtime components are missing"*, run with JavaFX modules and opens:

**Windows (PowerShell):**

```powershell
$env:MAVEN_OPTS="--add-modules javafx.controls,javafx.web,javafx.fxml --add-opens java.base/java.lang=ALL-UNNAMED"
mvn spring-boot:run
```

**macOS / Linux:**

```bash
export MAVEN_OPTS="--add-modules javafx.controls,javafx.web,javafx.fxml --add-opens java.base/java.lang=ALL-UNNAMED"
mvn spring-boot:run
```

**Option B – Run the JAR**

```bash
java --add-modules javafx.controls,javafx.web,javafx.fxml --add-opens java.base/java.lang=ALL-UNNAMED -jar target/mins-bot-1.0.0-SNAPSHOT.jar
```

**Option C – From your IDE (Eclipse / IntelliJ)**

1. Set the **main class** to: `com.minsbot.FloatingAppLauncher`
2. Add VM options (if needed):
   - `--add-modules javafx.controls,javafx.web,javafx.fxml`
   - `--add-opens java.base/java.lang=ALL-UNNAMED`
3. Run `FloatingAppLauncher`.

### 4. Use the app

- A small **swirling ball** appears (usually bottom-right of the screen).
- **Hover** over the ball to expand the chat UI.
- **Type** a message and press Enter or click Send.
- Click the **microphone** to use voice input (if supported by the WebView).
- **Drag** the window by clicking and dragging anywhere on it.
- **Mouse out** of the window to collapse it back to the ball.

## Configuration (`application.properties`)

| Property | Description | Default |
|----------|-------------|---------|
| `server.port` | HTTP port for the embedded server and UI | `8765` (or `MINS_BOT_PORT` env) |
| `app.window.collapsed.width` | Width when collapsed (ball only) | `64` |
| `app.window.collapsed.height` | Height when collapsed | `64` |
| `app.window.expanded.width` | Width when expanded (chat) | `380` |
| `app.window.expanded.height` | Height when expanded | `520` |
| `app.window.initial.x` | Initial X position (-1 = auto, right side) | `-1` |
| `app.window.initial.y` | Initial Y position (-1 = auto) | `-1` |
| `app.window.always-on-top` | Keep window on top | `true` |
| `app.window.hover.expand.delay-ms` | Delay before expanding on hover | `150` |
| `app.window.hover.collapse.delay-ms` | Delay before collapsing on mouse out | `400` |
| `app.chat.placeholder.enabled` | Use built-in placeholder replies | `true` |

**Messaging integrations:** Each platform has `app.<platform>.enabled=false` and its own token/ID properties. See [Messaging integrations](#messaging-integrations) below for endpoints and setup.

Override with environment variables (e.g. `MINS_BOT_PORT=9090`) or by editing `src/main/resources/application.properties`.

### Local secrets file (not committed)

This project supports a separate secrets file for API keys:

- File path: `application-secrets.properties` (project root)
- Loaded automatically via:
  - `spring.config.import=optional:file:./application-secrets.properties`
- Ignored by git via `.gitignore`

Setup:

1. Copy `application-secrets.properties.example` to `application-secrets.properties`.
2. Put your secrets there (e.g. `spring.ai.openai.api-key` for the AI, and any messaging tokens like `app.telegram.bot-token`).
3. Run the app normally (`mvn spring-boot:run`).

Example `application-secrets.properties`:

```properties
spring.ai.openai.api-key=sk-...
# Optional: keep messaging tokens here instead of application.properties
# app.telegram.bot-token=...
# app.discord.bot-token=...
```

`application-secrets.properties.example` also includes placeholders for other LLM providers (Anthropic, Gemini, Azure OpenAI, etc.); only OpenAI is wired for chat. Messaging platform tokens (`app.viber.auth-token`, `app.telegram.bot-token`, etc.) can go in this file to keep them out of version control.

### Environment configuration

Spring Boot maps `app.openai.*` properties to uppercase env vars with `_` separators:

- `app.openai.enabled` -> `APP_OPENAI_ENABLED`
- `app.openai.api-key` -> `APP_OPENAI_API_KEY`
- `app.openai.base-url` -> `APP_OPENAI_BASE_URL`
- `app.openai.audio-model` -> `APP_OPENAI_AUDIO_MODEL`

You can also set `server.port` using `MINS_BOT_PORT`.

Examples:

**Windows PowerShell (current shell):**

```powershell
$env:APP_OPENAI_ENABLED="true"
$env:APP_OPENAI_API_KEY="sk-..."
$env:APP_OPENAI_AUDIO_MODEL="gpt-4o-audio-preview"
$env:MINS_BOT_PORT="8765"
mvn spring-boot:run
```

**Windows PowerShell (persist for user):**

```powershell
[Environment]::SetEnvironmentVariable("APP_OPENAI_ENABLED", "true", "User")
[Environment]::SetEnvironmentVariable("APP_OPENAI_API_KEY", "sk-...", "User")
[Environment]::SetEnvironmentVariable("APP_OPENAI_AUDIO_MODEL", "gpt-4o-audio-preview", "User")
```

**macOS / Linux:**

```bash
export APP_OPENAI_ENABLED=true
export APP_OPENAI_API_KEY="sk-..."
export APP_OPENAI_AUDIO_MODEL="gpt-4o-audio-preview"
export MINS_BOT_PORT=8765
mvn spring-boot:run
```

Note: configure `src/main/resources/application.properties`, not `target/classes/application.properties` (that target file is build output and gets overwritten).

### Messaging integrations

The same chatbot can receive and reply to messages on multiple platforms. Each integration is **off** by default. Enable the ones you need and set the required tokens/IDs in `application.properties` or `application-secrets.properties`. **All webhooks require public HTTPS** (use [ngrok](https://ngrok.com/) for local dev: `ngrok http 8765`).

| Platform | Webhook endpoint | Config prefix | Docs / notes |
|----------|------------------|---------------|--------------|
| **Viber** | `POST /api/viber/webhook` | `app.viber.*` | [Viber Bot API](https://developers.viber.com/docs/api/rest-bot-api/) — auth-token, webhook-url (auto-registered if set) |
| **Telegram** | `POST /api/telegram/webhook` | `app.telegram.*` | [Telegram Bots](https://core.telegram.org/bots/api) — bot-token, webhook-url (auto-registered) |
| **Discord** | `POST /api/discord/interactions` | `app.discord.*` | [Discord Developer](https://discord.com/developers/docs/intro) — bot-token, application-id, public-key; set Interactions Endpoint URL in Dev Portal |
| **Slack** | `POST /api/slack/events` | `app.slack.*` | [Slack API](https://api.slack.com/) — bot-token, signing-secret, app-token; Events API Request URL |
| **WhatsApp** | `POST /api/whatsapp/webhook` | `app.whatsapp.*` | [Meta Cloud API](https://developers.facebook.com/docs/whatsapp/cloud-api) — access-token, phone-number-id, verify-token |
| **Messenger** | `POST /api/messenger/webhook` | `app.messenger.*` | [Messenger Platform](https://developers.facebook.com/docs/messenger-platform) — page-access-token, verify-token, app-secret |
| **LINE** | `POST /api/line/webhook` | `app.line.*` | [LINE Messaging API](https://developers.line.biz/en/docs/messaging-api/) — channel-access-token, channel-secret |
| **Teams** | `POST /api/teams/messages` | `app.teams.*` | [Azure Bot Service](https://learn.microsoft.com/en-us/azure/bot-service/) — app-id, app-password, tenant-id |
| **WeChat** | `POST /api/wechat/webhook` | `app.wechat.*` | [WeChat Official Account](https://developers.weixin.qq.com/doc/offiaccount/en/) — app-id, app-secret, token, encoding-aes-key |
| **Signal** | `POST /api/signal/webhook` | `app.signal.*` | [signal-cli-rest-api](https://github.com/bbernhard/signal-cli-rest-api) — requires separate signal-cli-rest-api instance; api-url, phone-number |

**Example — Viber**

1. Create a Viber bot ([Viber for developers](https://developers.viber.com/docs/api/rest-bot-api/)).
2. In Viber: **More → Settings → Bots → Edit Info → Your app key** — copy the token.
3. Set in `application.properties`: `app.viber.enabled=true`, `app.viber.auth-token=YOUR_TOKEN`.
4. Expose HTTPS (e.g. ngrok): set `app.viber.webhook-url=https://YOUR_NGROK_URL/api/viber/webhook`. Restart the app; the webhook is registered on startup.
5. Viber sends events to `POST /api/viber/webhook`; the app replies using the same `ChatService` logic as the desktop chat.

## Project layout

```
mins-bot/
├── pom.xml
├── README.md
├── src/main/java/com/minsbot/
│   ├── MinsbotApplication.java      # Spring Boot entry
│   ├── FloatingAppLauncher.java     # JavaFX entry, starts Spring & floating window
│   ├── WindowBridge.java            # JS ↔ Java (expand/collapse/drag/voice)
│   ├── ChatController.java          # REST /api/chat, /api/chat/async, /api/chat/status
│   ├── ChatService.java             # Shared reply logic (desktop + all messaging platforms)
│   ├── config/                      # OpenAI secrets loader, etc.
│   ├── agent/                       # AI config, tools, system context, PcAgent
│   ├── memory/                      # MemoryService (notes), MemoryConfig
│   ├── skills/                      # Optional skills (e.g. diskscan)
│   ├── ViberConfig.java             # + ViberApiClient, ViberWebhookController, ViberWebhookRegistrar
│   ├── TelegramConfig.java          # + TelegramApiClient, TelegramWebhookController, TelegramWebhookRegistrar
│   ├── DiscordConfig.java           # + DiscordApiClient, DiscordWebhookController
│   ├── SlackConfig.java             # + SlackApiClient, SlackEventController
│   ├── WhatsAppConfig.java          # + WhatsAppApiClient, WhatsAppWebhookController
│   ├── MessengerConfig.java         # + MessengerApiClient, MessengerWebhookController
│   ├── LineConfig.java              # + LineApiClient, LineWebhookController
│   ├── TeamsConfig.java             # + TeamsApiClient, TeamsWebhookController
│   ├── WeChatConfig.java            # + WeChatApiClient, WeChatWebhookController
│   └── SignalConfig.java            # + SignalApiClient, SignalWebhookController
└── src/main/resources/
    ├── application.properties       # All config (window, platforms, skills, memory, etc.)
    ├── application-secrets.properties  # Optional, gitignored (OpenAI key, etc.)
    └── static/
        ├── index.html
        ├── css/style.css
        └── js/app.js
```

## Customizing the chatbot

- **Backend logic**: Edit `ChatService.java` — `getReply()` is used by the desktop chat and **all messaging integrations**. Replace or extend the logic (OpenAI tool-calling, regex fallback, or your own services).
- **UI**: Edit `src/main/resources/static/` (HTML, CSS, JS). The floating window is a JavaFX `WebView` loading the app at `http://localhost:<server.port>/`.
- **Voice**: Voice input uses the Web Speech API in the WebView; no backend change needed for basic speech-to-text.

## Troubleshooting

- **"no jfxwebkit in java.library.path"**: The build unpacks the WebView native library into `target/javafx-natives` when you run on Windows/Mac/Linux (OS-specific Maven profile). Use `mvn spring-boot:run` so the plugin can set `-Djava.library.path` to that folder. If you run the JAR directly, use:  
  `java -Djava.library.path=target/javafx-natives -jar target/mins-bot-1.0.0-SNAPSHOT.jar` (and ensure you’ve run a build on the same OS first so `target/javafx-natives` exists).
- **Window doesn’t appear**: Ensure the port in `application.properties` is free and that no firewall is blocking `localhost`.
- **"JavaFX runtime components are missing"**: Use the `MAVEN_OPTS` or `java` command with `--add-modules` and `--add-opens` as above.
- **Voice not working**: Depends on WebView/system support for the Web Speech API (e.g. macOS/Windows with a recent JavaFX/WebKit build). Try in a browser at `http://localhost:<port>/` to compare.

## License

Use and modify as you like for your project.
