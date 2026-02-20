# HIERARCHY.md — Mins Bot Development Roadmap

Commands and features ordered by priority. Top = do first.

---
## TIER 0 — Update the Primary Directives
- Just update the primary directive, no other task follow.

## TIER 1 — Critical Infrastructure (Do Now)

### 1. Dynamic Tool Routing
**Problem**: 140+ @Tool methods exceed OpenAI's 128-tool limit. Currently 5 tools are manually excluded.
- Implement a `ToolRouter` that selects relevant tools per message using keyword/intent matching
- Group tools into categories (system, files, browser, media, research, dev, comms)
- Pass only the relevant subset (30-50 tools) to each ChatClient call
- Expose all 140+ tools without hitting the API limit

### 2. Streaming Responses
**Problem**: ChatClient blocks until the full response is ready. Long tool chains = long wait with no feedback.
- Switch from `.call()` to `.stream()` on ChatClient
- Push partial tokens to frontend via SSE (`/api/chat/stream`)
- Show real-time typing indicator + incremental text in the chat bubble
- Tool execution status already works (ToolExecutionNotifier) — combine both streams

### 3. Persistent Scheduled Tasks
**Problem**: `ScheduledTaskTools` uses in-memory `ScheduledExecutorService`. All reminders/recurring tasks lost on restart.
- Persist tasks to `memory/scheduled_tasks.json`
- Reload and reschedule on startup (`@PostConstruct`)
- Handle missed reminders (fire immediately if past due)
- Add cron expression support for complex schedules

### 4. Security Hardening
**Problem**: `runPowerShell()` and `runCmd()` accept arbitrary commands. `writeTextFile()` can write anywhere.
- Add configurable command blocklist (e.g., `format`, `rm -rf /`, `del /s /q C:\`)
- Add path allowlist/blocklist for file write operations
- Sanitize shell arguments to prevent injection
- Rate-limit destructive operations (delete, shutdown, format)
- Add confirmation callback for dangerous actions (AI asks user before executing)

---

## TIER 2 — High Priority Features (Do Next)

### 5. Conversation Context Switching
**Problem**: Single conversation thread. No way to have separate topics or switch between them.
- Add conversation IDs (default + named conversations)
- `/new` creates fresh context, `/switch <name>` resumes
- Each conversation gets its own ChatMemory partition
- List active conversations with last message preview

### 6. RAG — Document Knowledge Base
**Problem**: AI can only read files one at a time via `readTextFile`. No semantic search over user documents.
- Index user documents (PDF, DOCX, TXT) into vector embeddings
- Use Spring AI's `VectorStore` with a local store (SimpleVectorStore or Chroma)
- Add `@Tool searchKnowledgeBase(query)` — semantic search over indexed docs
- Auto-index files in `~/mins_bot_data/knowledge/`
- Chunk large documents, store with source metadata

### 7. Response Caching & Cost Control
**Problem**: Every message hits the API. Repeated questions cost money and add latency.
- Cache identical prompts (hash-based, TTL 1 hour)
- Track token usage per session and cumulative
- Add `@Tool getUsageStats()` — show token counts, estimated cost
- Configurable monthly budget cap with warning threshold
- Log all API calls with token counts to `memory/usage_log.csv`

### 8. Plugin System Completion
**Problem**: `PluginLoaderService` can load JARs but can't register new @Tool beans into Spring context.
- Use `GenericApplicationContext.registerBean()` for loaded classes
- Scan loaded JARs for `@Component` and `@Tool` annotations
- Hot-reload: unload old version, load new version
- Plugin manifest (`plugin.json`) with name, version, dependencies
- Plugin isolation via separate classloaders

### 9. Multi-User Support
**Problem**: Single-user desktop app. Platform integrations (Telegram, Discord, etc.) serve one shared context.
- Per-user ChatMemory keyed by platform + user ID
- Per-user directives and preferences
- User profiles stored in `memory/users/`
- Admin commands restricted to local desktop user
- Rate limiting per remote user

---

## TIER 3 — Medium Priority Enhancements

### 10. Voice Pipeline Upgrade
**Problem**: Voice input works (transcription) but voice output is Windows SAPI only. No wake word.
- Add wake word detection ("Hey Mins Bot") via Porcupine or Vosk
- Continuous listening mode (toggle on/off)
- Upgrade TTS to OpenAI TTS API or local Piper TTS
- Voice activity detection (auto-stop recording on silence)
- Audio feedback sounds (chime on wake, ding on response)

### 11. Workflow / Macro System
**Problem**: Users repeat multi-step sequences manually. No way to save and replay command chains.
- `@Tool createWorkflow(name, steps)` — save a named sequence of tool calls
- `@Tool runWorkflow(name)` — replay saved workflow
- `@Tool listWorkflows()` — show saved workflows
- Parameterized workflows with `{{placeholders}}`
- Persist to `memory/workflows/`

### 12. File Watcher & Event Triggers
**Problem**: Bot only acts when spoken to or in autonomous mode. No reactive behavior.
- Watch directories for new/changed files (Java WatchService)
- Configurable triggers: "when a new PDF appears in Downloads, extract text and summarize"
- Trigger types: file created, file modified, file deleted, schedule, webhook
- Store triggers in `memory/triggers.json`
- `@Tool createTrigger(event, action)`, `@Tool listTriggers()`, `@Tool removeTrigger(id)`

### 13. Dashboard V2
**Problem**: Current dashboard is basic HTML with auto-refresh. No interactivity.
- WebSocket-based real-time updates (no polling)
- Tool call timeline visualization
- Token usage charts (daily/weekly)
- Active conversations list
- Directive management UI (add/remove/reorder without chat)
- System health panel (memory, CPU, disk, API status)

### 14. OAuth2 Email & Calendar
**Problem**: Email uses basic SMTP/IMAP auth. No calendar integration.
- OAuth2 flow for Gmail / Outlook
- `@Tool getCalendarEvents(days)` — read upcoming events
- `@Tool createCalendarEvent(title, start, end)` — create events
- `@Tool searchEmails(query, maxResults)` — full-text email search
- `@Tool replyToEmail(messageId, body)` — reply to specific email

### 15. Smart Clipboard
**Problem**: Clipboard tools only get/set text. No history, no image support.
- Clipboard history ring (last 50 items)
- Image clipboard support (paste screenshots)
- `@Tool getClipboardHistory(count)` — recall past clipboard items
- `@Tool getClipboardImage()` — get image from clipboard, save to temp file
- Auto-detect clipboard content type (text, image, file list)

---

## TIER 4 — Lower Priority / Nice to Have

### 16. Git Integration Tools
- `@Tool gitStatus(repoPath)` — show repo status
- `@Tool gitLog(repoPath, count)` — recent commits
- `@Tool gitDiff(repoPath)` — show current changes
- `@Tool gitCommit(repoPath, message)` — stage all + commit
- `@Tool gitPull(repoPath)`, `@Tool gitPush(repoPath)`

### 17. Database Tools
- SQLite or H2 local database for structured queries
- `@Tool queryDb(sql)` — run SELECT queries
- `@Tool insertDb(table, data)` — insert records
- Replace file-based memory with proper DB (optional migration)
- Import CSV/Excel into database tables

### 18. REST API Builder
- Let the AI create ad-hoc REST endpoints at runtime
- `@Tool createEndpoint(method, path, responseTemplate)` — dynamic endpoint
- Useful for quick webhooks, mock APIs, data sharing
- Auto-cleanup after configurable TTL

### 19. Screen OCR & Visual Understanding
- `@Tool ocrScreenshot()` — OCR the current screen
- `@Tool ocrImage(imagePath)` — OCR any image file
- `@Tool describeScreen()` — send screenshot to vision model for description
- Enable "what's on my screen?" queries

### 20. Network Tools
- `@Tool portScan(host, ports)` — check open ports
- `@Tool httpRequest(method, url, headers, body)` — generic HTTP client
- `@Tool dnsLookup(domain)` — resolve DNS
- `@Tool traceroute(host)` — network path trace
- `@Tool speedTest()` — internet speed test

### 21. System Monitoring & Alerts
- Background thread monitoring CPU, RAM, disk, network
- Configurable thresholds ("alert me if CPU > 90% for 5 min")
- `@Tool getSystemMetrics()` — current CPU/RAM/disk/network stats
- `@Tool setAlert(metric, threshold, message)` — create monitoring alert
- Push notifications when thresholds exceeded

### 22. Multi-Language UI
- i18n for chat responses and UI strings
- `@Tool setLanguage(lang)` — switch bot response language
- System prompt includes language preference
- Frontend locale switching

### 23. Auto-Update Mechanism
- Check GitHub releases for new versions
- `@Tool checkForUpdates()` — compare current vs latest
- Download and apply update (restart required)
- Changelog display

### 24. Mobile Companion API
- REST API subset for mobile app consumption
- Push notifications to mobile (Firebase/APNs)
- Remote command execution from phone
- Chat sync between desktop and mobile

---

## Implementation Notes

- Each tier builds on the previous. Don't start Tier 3 until Tier 2 is stable.
- Items within a tier can be parallelized.
- Security hardening (#4) should be done alongside any new tool that accepts user input.
- Streaming (#2) dramatically improves perceived performance — prioritize it.
- Dynamic tool routing (#1) unblocks adding more tools without hitting API limits.
