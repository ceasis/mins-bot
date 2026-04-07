# MinsBot Setup Guide

A step-by-step guide to get MinsBot running from scratch. For project overview, features, and architecture, see the main [README.md](../README.md).

---

## Prerequisites

| Requirement | Minimum Version | Download |
|-------------|----------------|----------|
| **JDK** (not JRE) | 17+ | [Adoptium](https://adoptium.net/) or [Oracle](https://www.oracle.com/java/technologies/downloads/) |
| **Maven** | 3.6+ | [maven.apache.org](https://maven.apache.org/download.cgi) |
| **Git** | any | [git-scm.com](https://git-scm.com/downloads) |
| **Node.js** (optional) | 18+ | [nodejs.org](https://nodejs.org/) -- only needed for Remotion video creation |
| **Chrome** (optional) | any | [google.com/chrome](https://www.google.com/chrome/) -- needed for CDP browser control |

Verify your installations:

```bash
java -version    # should show 17+
mvn -version     # should show 3.6+
git --version
```

---

## Quick Start (5 minutes)

### 1. Clone the repository

```bash
git clone https://github.com/user/mins-bot.git
cd mins-bot
```

### 2. Create your secrets file

Create `application-secrets.properties` in the project root (this file is gitignored):

```properties
# At minimum, you need ONE AI provider key:
spring.ai.openai.api-key=YOUR_OPENAI_API_KEY
```

The bot looks for this file in three locations (last wins):
1. `~/application-secrets.properties`
2. `./application-secrets.properties`
3. `<project-root>/application-secrets.properties`

### 3. Build

```bash
mvn clean package -DskipTests
```

### 4. Run

```bash
mvn spring-boot:run
```

### 5. Use

- A small swirling orb appears on your desktop
- Double-click the orb to expand the chat panel
- Type a message and press Enter
- Access the web UI directly at `http://localhost:8765`

---

## Environment Variables

All properties in `application.properties` can be overridden via environment variables using Spring Boot's relaxed binding (`app.viber.auth-token` becomes `APP_VIBER_AUTH_TOKEN`).

### AI Provider Keys

| Variable | Required | Description | Where to get it |
|----------|----------|-------------|-----------------|
| `OPENAI_API_KEY` | Yes (or one alternative) | OpenAI API key for GPT models | [platform.openai.com/api-keys](https://platform.openai.com/api-keys) |
| `SPRING_AI_OPENAI_API_KEY` | Alternative to above | Same key, Spring AI naming | Same as above |
| `gemini.api.key` | No | Google Gemini API key (vision, reasoning, live audio) | [ai.google.dev](https://ai.google.dev/) |
| `ANTHROPIC_API_KEY` | No | Anthropic Claude API key (calibration engine) | [console.anthropic.com](https://console.anthropic.com/) |

### Voice / TTS Keys

| Variable | Required | Description | Where to get it |
|----------|----------|-------------|-----------------|
| `app.elevenlabs.api-key` | No | ElevenLabs TTS | [elevenlabs.io](https://elevenlabs.io/) |
| `app.elevenlabs.voice-id` | No | ElevenLabs voice ID | ElevenLabs dashboard -> Voices |
| `fish.audio.api.key` | No | Fish Audio TTS | [fish.audio](https://fish.audio/) |
| `fish.audio.reference.id` | No | Fish Audio voice model ID | Fish Audio dashboard |

### Cloud OCR / Vision Keys

| Variable | Required | Description | Where to get it |
|----------|----------|-------------|-----------------|
| `gcp.docai.api.key` | No | Google Document AI (cloud OCR) | [GCP Console](https://console.cloud.google.com/) |
| `gcp.docai.processor.id` | No | Document AI processor ID | GCP Console -> Document AI |
| `aws.access.key` | No | AWS access key (Textract + Rekognition) | [AWS IAM](https://console.aws.amazon.com/iam/) |
| `aws.secret.key` | No | AWS secret key | Same as above |

### Web Search Keys

| Variable | Required | Description | Where to get it |
|----------|----------|-------------|-----------------|
| `serper.api.key` | No | Serper.dev search API | [serper.dev](https://serper.dev/) |
| `serpapi.api.key` | No | SerpAPI search API | [serpapi.com](https://serpapi.com/) |

If no search API key is set, web search falls back to DuckDuckGo + Google HTML scraping.

### Email Keys

| Variable | Required | Description | Where to get it |
|----------|----------|-------------|-----------------|
| `spring.mail.host` | No | SMTP server (e.g. `smtp.gmail.com`) | Your email provider |
| `spring.mail.username` | No | SMTP username / email address | Your email account |
| `spring.mail.password` | No | SMTP app password | Gmail: [App Passwords](https://myaccount.google.com/apppasswords) |
| `app.email.imap.host` | No | IMAP server (e.g. `imap.gmail.com`) | Your email provider |
| `app.email.imap.username` | No | IMAP username | Your email account |
| `app.email.imap.password` | No | IMAP password | Same as SMTP app password |

### Google OAuth (Gmail API / Calendar)

| Variable | Required | Description | Where to get it |
|----------|----------|-------------|-----------------|
| `spring.security.oauth2.client.registration.google.client-id` | No | Google OAuth client ID | [GCP Console -> Credentials](https://console.cloud.google.com/apis/credentials) |
| `spring.security.oauth2.client.registration.google.client-secret` | No | Google OAuth client secret | Same as above |

Redirect URI to add in GCP: `http://127.0.0.1:8765/api/integrations/google/oauth2/callback`

### Server

| Variable | Required | Description | Default |
|----------|----------|-------------|---------|
| `MINS_BOT_PORT` | No | HTTP server port | `8765` |
| `MINS_BOT_BIND` | No | Bind address | `0.0.0.0` |

---

## Configuration Reference

All configuration lives in `src/main/resources/application.properties`. Below is a section-by-section breakdown.

### Server Settings

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8765` | HTTP port (env: `MINS_BOT_PORT`) |
| `server.address` | `0.0.0.0` | Bind address (env: `MINS_BOT_BIND`) |
| `spring.servlet.multipart.max-file-size` | `50MB` | Max upload file size |
| `spring.servlet.multipart.max-request-size` | `50MB` | Max request size |

### Window / UI Settings

| Property | Default | Description |
|----------|---------|-------------|
| `app.window.collapsed.width` | `45` | Orb width in pixels |
| `app.window.collapsed.height` | `45` | Orb height in pixels |
| `app.window.expanded.width` | `380` | Chat panel width in pixels |
| `app.window.expanded.height` | `520` | Chat panel height in pixels |
| `app.window.initial.x` | `-1` | Initial X position (-1 = center) |
| `app.window.initial.y` | `-1` | Initial Y position (-1 = center) |
| `app.window.always-on-top` | `true` | Keep window above all others |
| `app.window.hover.expand.delay-ms` | `150` | Delay before expanding on hover |
| `app.window.hover.collapse.delay-ms` | `400` | Delay before collapsing |
| `app.sound.volume` | `0.01` | Working sound volume (0.0-1.0) |
| `app.ui.path` | `/` | UI URL path |

### AI Model Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `spring.ai.openai.chat.options.model` | `gpt-5.1` | Primary chat model |
| `spring.ai.openai.base-url` | `https://api.openai.com` | OpenAI-compatible API base URL |
| `app.tool-classifier.model` | `gpt-4o-mini` | Lightweight model for tool routing |
| `app.vision.model` | `gpt-4o-mini` | Vision model for screenshot analysis |
| `app.vision.verify-model` | `gpt-4o` | Vision model for verification |
| `app.vision.detail` | `high` | Vision detail level |
| `app.gemini.model` | `gemini-2.5-pro` | Gemini primary model |
| `app.gemini.reasoning-model` | `gemini-2.5-pro` | Gemini reasoning model |
| `app.gemini.computer-user-interface` | `gemini-3-flash-preview` | Gemini model for UI element detection |
| `app.gemini.computer-user-interface-reasoning` | `gemini-2.5-pro` | Gemini reasoning for UI detection |
| `app.claude.model` | `claude-opus-4-6` | Claude model for calibration |
| `app.gemini-live.model` | `gemini-2.5-flash-native-audio-latest` | Gemini Live real-time audio model |
| `app.gemini-live.source-language` | `Filipino/Tagalog` | Live translation source language |
| `app.openai.transcription-model` | `gpt-4o-mini-transcribe` | Audio transcription model |

### Feature Toggles

| Property | Default | Description |
|----------|---------|-------------|
| `app.planning.enabled` | `true` | Show task plans before execution (adds latency) |
| `app.autonomous.enabled` | `true` | Work on directives when user is idle |
| `app.proactive.enabled` | `false` | Push notifications (briefings, reminders) |
| `app.auto-memory.enabled` | `true` | Auto-detect life facts from chat |
| `app.tray.enabled` | `true` | System tray icon |
| `app.cdp.enabled` | `true` | Chrome DevTools Protocol browser control |
| `app.hotkeys.enabled` | `false` | Global keyboard hooks (JNativeHook) |
| `app.screenshot.enabled` | `true` | Periodic screen capture for context |
| `app.gemini-live.enabled` | `true` | Real-time audio streaming |
| `app.chat.live-screen-on-message` | `true` | Capture screen before task-related replies |
| `app.fishaudio.enabled` | `true` | Fish Audio TTS |
| `app.elevenlabs.enabled` | `true` | ElevenLabs TTS |
| `app.skills.diskscan.enabled` | `false` | Disk scan skill |

### Autonomous Mode

| Property | Default | Description |
|----------|---------|-------------|
| `app.autonomous.idle-timeout-seconds` | `60` | Seconds of idle before autonomous work starts |
| `app.autonomous.check-interval-ms` | `15000` | How often to check if user is idle |
| `app.autonomous.pause-between-steps-ms` | `30000` | Pause between autonomous steps |

### Proactive Engine

| Property | Default | Description |
|----------|---------|-------------|
| `app.proactive.check-interval-ms` | `300000` | Check interval (5 minutes) |
| `app.proactive.break-reminder-minutes` | `120` | Remind to take a break every N minutes |
| `app.proactive.hydration-reminder-minutes` | `120` | Remind to drink water every N minutes |
| `app.proactive.morning-briefing-hour` | `8` | Hour for morning briefing (24h format) |
| `app.proactive.quiet-hours-start` | `22` | No notifications after this hour |
| `app.proactive.quiet-hours-end` | `7` | Notifications resume at this hour |
| `app.proactive.weather-location` | `Manila` | Location for weather briefings |

### Screenshots

| Property | Default | Description |
|----------|---------|-------------|
| `app.screenshot.interval-seconds` | `5` | Capture interval |
| `app.screenshot.max-age-days` | `3` | Auto-delete screenshots older than this |

### Background Agents

| Property | Default | Description |
|----------|---------|-------------|
| `app.agents.max-concurrent` | `4` | Max parallel background agents |

### Chrome CDP

| Property | Default | Description |
|----------|---------|-------------|
| `app.cdp.port` | `9222` | Chrome remote debugging port |
| `app.cdp.chrome-path` | (auto-detect) | Path to Chrome executable |

### Web Search

| Property | Default | Description |
|----------|---------|-------------|
| `app.web-search.provider` | `auto` | `auto`, `serper`, `serpapi`, or `ddg` |

### Hugging Face

| Property | Default | Description |
|----------|---------|-------------|
| `app.huggingface.cache-dir` | `~/.cache/mins_bot/hf_models` | ONNX model cache directory |

### Document AI / OCR

| Property | Default | Description |
|----------|---------|-------------|
| `app.document-ai.project-id` | `mins-488318` | GCP project ID |
| `app.document-ai.location` | `us` | GCP location |
| `app.textract.region` | `us-east-1` | AWS region for Textract |
| `app.rekognition.region` | `us-east-1` | AWS region for Rekognition |

### Voice / TTS

| Property | Default | Description |
|----------|---------|-------------|
| `app.elevenlabs.model-id` | `eleven_multilingual_v2` | ElevenLabs model |
| `app.elevenlabs.output-format` | `wav_44100` | Audio output format |
| `app.elevenlabs.female-voice-id` | `EXAVITQu4vr4xnSDxMaL` | Female voice ID |
| `app.elevenlabs.male-voice-id` | `pNInz6obpgDQGcFmaJgB` | Male voice ID |
| `app.fishaudio.model` | `s2-pro` | Fish Audio model |
| `app.fishaudio.format` | `pcm` | Audio format |
| `app.fishaudio.sample-rate` | `24000` | Sample rate |
| `app.fishaudio.prosody-speed` | `1.1` | Speech speed |

### Disk Scan Skill

| Property | Default | Description |
|----------|---------|-------------|
| `app.skills.diskscan.max-depth` | `20` | Max directory recursion depth |
| `app.skills.diskscan.max-results` | `500` | Max results returned |
| `app.skills.diskscan.blocked-paths` | (built-in OS paths) | Additional paths to block |

---

## Platform Integration Setup

All messaging integrations require a **public HTTPS endpoint**. For local development, use [ngrok](https://ngrok.com/):

```bash
ngrok http 8765
# Copy the https://xxxxx.ngrok-free.app URL
```

### Telegram

1. Talk to [@BotFather](https://t.me/BotFather) on Telegram, send `/newbot`
2. Copy the bot token
3. Add to `application-secrets.properties`:
   ```properties
   app.telegram.enabled=true
   app.telegram.bot-token=YOUR_TOKEN
   app.telegram.webhook-url=https://YOUR_NGROK_URL/api/telegram/webhook
   ```
4. Restart -- webhook is registered automatically

### Discord

1. Go to [discord.com/developers/applications](https://discord.com/developers/applications), create an app
2. Under Bot tab, copy the bot token. Under General Info, copy Application ID and Public Key
3. Set Interactions Endpoint URL to `https://YOUR_NGROK_URL/api/discord/interactions`
4. Add to secrets:
   ```properties
   app.discord.enabled=true
   app.discord.bot-token=YOUR_TOKEN
   app.discord.application-id=YOUR_APP_ID
   app.discord.public-key=YOUR_PUBLIC_KEY
   ```

### Viber

1. Go to [partners.viber.com](https://partners.viber.com/), create a bot account
2. Copy the auth token from Settings -> Bot -> Edit Info
3. Add to secrets:
   ```properties
   app.viber.enabled=true
   app.viber.auth-token=YOUR_TOKEN
   app.viber.webhook-url=https://YOUR_NGROK_URL/api/viber/webhook
   ```

### Slack

1. Go to [api.slack.com/apps](https://api.slack.com/apps), create an app
2. Enable Events API, set Request URL to `https://YOUR_NGROK_URL/api/slack/events`
3. Add to secrets:
   ```properties
   app.slack.enabled=true
   app.slack.bot-token=xoxb-YOUR_TOKEN
   app.slack.signing-secret=YOUR_SECRET
   ```

### WhatsApp (Meta Cloud API)

1. Go to [developers.facebook.com](https://developers.facebook.com/), create an app with WhatsApp product
2. Set Callback URL to `https://YOUR_NGROK_URL/api/whatsapp/webhook`
3. Add to secrets:
   ```properties
   app.whatsapp.enabled=true
   app.whatsapp.access-token=YOUR_TOKEN
   app.whatsapp.phone-number-id=YOUR_PHONE_ID
   app.whatsapp.verify-token=YOUR_VERIFY_TOKEN
   ```

### Facebook Messenger

1. Go to [developers.facebook.com](https://developers.facebook.com/), create an app with Messenger product
2. Set Webhook Callback URL to `https://YOUR_NGROK_URL/api/messenger/webhook`
3. Add to secrets:
   ```properties
   app.messenger.enabled=true
   app.messenger.page-access-token=YOUR_TOKEN
   app.messenger.verify-token=YOUR_VERIFY_TOKEN
   app.messenger.app-secret=YOUR_SECRET
   ```

### LINE

1. Go to [LINE Developer Console](https://developers.line.biz/console/), create a Messaging API channel
2. Set Webhook URL to `https://YOUR_NGROK_URL/api/line/webhook`
3. Add to secrets:
   ```properties
   app.line.enabled=true
   app.line.channel-access-token=YOUR_TOKEN
   app.line.channel-secret=YOUR_SECRET
   ```

### Microsoft Teams

1. Register a bot in [Azure Portal](https://portal.azure.com/) -> Bot Services
2. Set Messaging endpoint to `https://YOUR_NGROK_URL/api/teams/messages`
3. Add to secrets:
   ```properties
   app.teams.enabled=true
   app.teams.app-id=YOUR_APP_ID
   app.teams.app-password=YOUR_PASSWORD
   app.teams.tenant-id=YOUR_TENANT_ID
   ```

### WeChat

1. Register at [WeChat Official Account admin](https://mp.weixin.qq.com/)
2. Set Server URL to `https://YOUR_NGROK_URL/api/wechat/webhook`
3. Add to secrets:
   ```properties
   app.wechat.enabled=true
   app.wechat.app-id=YOUR_APP_ID
   app.wechat.app-secret=YOUR_SECRET
   app.wechat.token=YOUR_TOKEN
   app.wechat.encoding-aes-key=YOUR_KEY
   ```

### Signal

1. Run a [signal-cli-rest-api](https://github.com/bbernhard/signal-cli-rest-api) instance
2. Configure its webhook to point to `https://YOUR_NGROK_URL/api/signal/webhook`
3. Add to secrets:
   ```properties
   app.signal.enabled=true
   app.signal.api-url=http://localhost:8080
   app.signal.phone-number=+1234567890
   ```

---

## Feature Activation

Features disabled by default that you may want to enable:

| Feature | Property | What it does |
|---------|----------|-------------|
| **Proactive Engine** | `app.proactive.enabled=true` | Morning briefings, break/hydration reminders, bill alerts |
| **Global Hotkeys** | `app.hotkeys.enabled=true` | Register system-wide keyboard shortcuts |
| **Disk Scan Skill** | `app.skills.diskscan.enabled=true` | Browse file systems via REST API |

Features enabled by default that you may want to disable for faster replies:

| Feature | Property | Why disable |
|---------|----------|-------------|
| **Task Planning** | `app.planning.enabled=false` | Skip the step-planner for faster replies |
| **Autonomous Mode** | `app.autonomous.enabled=false` | Prevent bot from working when you are idle |
| **Live Screen on Message** | `app.chat.live-screen-on-message=false` | Skip auto-screenshot before task replies |
| **Screenshot Capture** | `app.screenshot.enabled=false` | Stop periodic screen captures |

---

## Troubleshooting

### Port already in use

```
Web server failed to start. Port 8765 was already in use.
```

Either stop the other process using port 8765 or change the port:
```bash
MINS_BOT_PORT=9090 mvn spring-boot:run
```

### JavaFX not found / `no jfxwebkit in java.library.path`

Use `mvn spring-boot:run` (it unpacks native libraries automatically). If running the JAR directly, add:
```bash
java --add-modules javafx.controls,javafx.web,javafx.fxml \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     -jar target/mins-bot-1.0.0-SNAPSHOT.jar
```

### Window does not appear

- Verify port 8765 is free and not blocked by firewall
- Check that Java 17+ is being used (not an older JRE)
- Try accessing `http://localhost:8765/` in a browser -- if the web UI loads, the server is running

### API key errors / bot gives generic responses

- Without any AI API key, the bot falls back to regex-based command matching
- Verify your key is set: check `application-secrets.properties` or the environment variable
- Test with: `curl -X POST http://localhost:8765/api/chat -H "Content-Type: application/json" -d '{"message":"hello"}'`

### WebView blank screen

- This typically means JavaFX WebView native libraries are missing
- Make sure you are using JDK 17 (not JRE) with JavaFX support
- Try `mvn clean package -DskipTests` then `mvn spring-boot:run`

### Voice / TTS not working

- Web Speech API depends on the browser/WebView engine
- For TTS, configure at least one provider (ElevenLabs or Fish Audio) with API key and voice ID
- Try `http://localhost:8765/` in Chrome for full Web Speech API support

### Messaging webhook not receiving

- Ensure you have a public HTTPS URL (use ngrok)
- Check the webhook URL in your platform's developer console matches exactly
- Look at the application logs for incoming webhook requests

### Ollama models not loading

- Install Ollama from [ollama.com](https://ollama.com) (or ask the bot: "install ollama")
- Pull a model: `ollama pull llama3.2`
- The bot auto-detects Ollama at `http://localhost:11434`

### Chrome CDP not connecting

- Chrome must be launched with `--remote-debugging-port=9222`
- The bot auto-relaunches Chrome with this flag on first CDP use
- If Chrome path is not detected, set `app.cdp.chrome-path` explicitly

---

## Building for Distribution

### Build the JAR

```bash
mvn clean package -DskipTests
```

The JAR is at `target/mins-bot-1.0.0-SNAPSHOT.jar`.

### Run the JAR

```bash
java --add-modules javafx.controls,javafx.web,javafx.fxml \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     -Djava.library.path=target/javafx-natives \
     -jar target/mins-bot-1.0.0-SNAPSHOT.jar
```

### IDE Setup (Eclipse / IntelliJ)

1. Import as Maven project
2. Set main class: `com.minsbot.FloatingAppLauncher`
3. Add VM options:
   ```
   --add-modules javafx.controls,javafx.web,javafx.fxml
   --add-opens java.base/java.lang=ALL-UNNAMED
   --add-opens javafx.web/javafx.scene.web=ALL-UNNAMED
   --add-opens javafx.web/com.sun.webkit=ALL-UNNAMED
   ```
4. Run

### Portable Distribution

To distribute to another machine:

1. Build the JAR (`mvn clean package -DskipTests`)
2. Copy `target/mins-bot-1.0.0-SNAPSHOT.jar` and `target/javafx-natives/` to the target machine
3. Create an `application-secrets.properties` with API keys on the target machine
4. The target machine needs JDK 17+ installed
5. Run with the command above

### Data Directory

MinsBot stores user data in `~/mins_bot_data/`:
- `minsbot_config.txt` -- bot configuration
- `screenshots/` -- screen captures
- `downloads/` -- downloaded files
- `exports/` -- exported conversations
- `videos/` -- downloaded videos
- `google_integrations/tokens.json` -- Google OAuth tokens
- `remotion/` -- video creation project (if used)
