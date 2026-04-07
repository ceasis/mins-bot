# MinsBot Tools Reference

All tools available to the AI agent, organized by category. Each tool is a `@Tool`-annotated method in `src/main/java/com/minsbot/agent/tools/`.

---

## System & Desktop Control

**SystemTools.java** -- Core system operations, mouse/keyboard control, process management

| Method | Description |
|--------|-------------|
| `takeScreenshot` | Take a screenshot, analyze it with AI vision, and describe what is visible |
| `runPowershell` | Execute a PowerShell command and return output |
| `runCmd` | Execute a CMD command and return output |
| `getSystemInfo` | Get hardware/OS info: RAM, CPU, OS, uptime, disk space |
| `getCurrentDateTime` | Get current date, time, and time zone |
| `getEnvVar` | Get the value of an environment variable |
| `listEnvVars` | List all environment variable names |
| `openApp` | Launch an application by name |
| `openAppWithArgs` | Open an application with arguments (file, URL, folder) |
| `closeApp` | Close a specific running application |
| `closeAllWindows` | Close all running user application windows |
| `listRunningApps` | List all running user applications |
| `listOpenWindows` | List open windows with titles and PIDs |
| `focusWindow` | Bring a window to the front by title or process name |
| `minimizeAll` | Minimize all windows and show the desktop |
| `lockScreen` | Lock the computer screen |
| `sleep` | Put the computer to sleep |
| `hibernate` | Hibernate the computer |
| `shutdown` | Shut down the computer (optional delay) |
| `restartComputer` | Restart the computer (optional delay) |
| `mute` | Mute system volume |
| `unmute` | Unmute system volume |
| `ping` | Ping a host to check reachability |
| `getLocalIpAddress` | Get local machine IP addresses |
| `getRecentFiles` | Get recently opened files from Windows |
| `changeWallpaper` | Change the desktop wallpaper |
| `sendKeys` | Send keystrokes to the focused window |
| `quitMinsBot` | Quit the Mins Bot application |
| `waitSeconds` | Pause for a specified number of seconds |

**Mouse & Screen Interaction (SystemTools.java)**

| Method | Description |
|--------|-------------|
| `clickMouse` | Click at screen coordinates |
| `doubleClickMouse` | Double-click at screen coordinates |
| `mouseMove` | Move cursor to coordinates without clicking |
| `mouseDrag` | Drag from one point to another |
| `mouseScroll` | Scroll the mouse wheel |
| `getMousePosition` | Get current cursor position |
| `getScreenSize` | Get screen resolution |
| `findAndClickElement` | Find a UI element by description and click it using AI vision |
| `clickAndType` | Click a field and type text (clipboard paste method) |
| `findElementCoordinates` | Find a UI element and return coordinates without clicking |
| `dragElementToElement` | Drag a visual element to another using AI vision |
| `dragSourceToTarget` | Find two elements on screen and drag one to the other |
| `openUrlInBrowser` | Navigate the PC browser to a URL |
| `openNewBrowserTab` | Open a new empty browser tab |
| `searchOnWebsite` | Search on a website (YouTube, Google, Amazon, etc.) |
| `browserCloseTab` | Close the current browser tab |
| `browserCloseTabsByKeyword` | Close all tabs matching a keyword |
| `browserSwitchTab` | Switch to next/previous tab |
| `browserRefresh` | Refresh current page |
| `browserBack` | Go back one page |
| `browserForward` | Go forward one page |
| `captureAllBrowserTabs` | Screenshot all open browser tabs |

**ScreenClickTools.java** -- Advanced vision-based screen clicking

| Method | Description |
|--------|-------------|
| `scanAndClick` | Scan the entire screen for a UI element and click it |
| `navigateClickPath` | Navigate a multi-step click path with backtracking |

**AppSwitchTools.java** -- Application switching

| Method | Description |
|--------|-------------|
| `switchToApp` | Switch to a specific app window using Alt+Tab and vision |

**WindowManagerTools.java** -- Window arrangement and layout

| Method | Description |
|--------|-------------|
| `snapWindow` | Snap focused window to a position (left, right, quadrants, maximize) |
| `splitScreen` | Open two apps side by side |
| `gridLayout` | Arrange multiple apps in a grid layout |
| `moveAndResizeWindow` | Move and resize a window to exact pixel coordinates |
| `cascadeWindows` | Cascade all open windows |
| `tileWindows` | Tile all windows horizontally or vertically |
| `minimizeAllExcept` | Minimize all windows except one |

---

## File & Storage

**FileSystemTools.java** -- File and directory operations

| Method | Description |
|--------|-------------|
| `listDirectory` | List directory contents with name, type, size, date |
| `countDirectoryContents` | Count files and directories in a folder |
| `getFileInfo` | Get detailed info about a file or directory |
| `pathExists` | Check whether a file or directory exists |
| `listDrives` | List all disk drives with space info |
| `createDirectory` | Create a new directory (including parents) |
| `writeTextFile` | Create or overwrite a text file |
| `readTextFile` | Read file contents (first 10000 characters) |
| `copyFile` | Copy a file |
| `movePath` | Move a file or directory |
| `moveByPattern` | Move files matching a glob pattern |
| `copyByPattern` | Copy files matching a glob pattern |
| `rename` | Rename a file or folder |
| `deleteFile` | Delete a single file |
| `deleteDirectory` | Delete a directory recursively |
| `openPath` | Open a file or folder in explorer/default app |
| `searchFiles` | Search for files by name pattern (wildcards) |
| `zipPath` | Compress a file or directory into a zip archive |
| `unzipFile` | Extract a zip archive |

**FileTools.java** -- PC-wide file collection

| Method | Description |
|--------|-------------|
| `collectFiles` | Scan entire PC and collect files by category (photos, videos, etc.) |
| `searchFilesByName` | Search for files by name across the PC (background task) |
| `listCollected` | Show collected files grouped by category |
| `openCollectedFolder` | Open the collected files folder |

**DownloadTools.java**

| Method | Description |
|--------|-------------|
| `downloadFile` | Download a file from a URL to a local path |

---

## Browser & Web

**BrowserTools.java** -- Basic browser operations

| Method | Description |
|--------|-------------|
| `openUrl` | Open a URL in the default browser |
| `searchGoogle` | Search Google and open results |
| `searchYouTube` | Search YouTube and open results |
| `closeAllBrowsers` | Close all browser windows |
| `listBrowserTabs` | List open browser windows with titles |
| `downloadFileFromUrl` | Download a file to local storage |
| `openIncognito` | Open a URL in Chrome incognito mode |

**ChromeCdpTools.java** -- Chrome DevTools Protocol (DOM-level control)

| Method | Description |
|--------|-------------|
| `cdpSearch` | Search on a website by typing into its search box |
| `cdpClickByText` | Click a button or link by its visible text |
| `cdpFillField` | Fill a form field by CSS selector |
| `cdpListTabs` | List all open Chrome tabs |
| `cdpExtractText` | Extract visible text from a Chrome tab |
| `cdpNavigate` | Navigate an existing tab to a new URL |
| `cdpOpenNewTab` | Open a URL in a new Chrome tab |

**PlaywrightTools.java** -- Headless browser automation

| Method | Description |
|--------|-------------|
| `browsePage` | Browse a URL with headless browser, return visible text |
| `browsePageImages` | Extract all image URLs from a page (JS-rendered) |
| `browsePageLinks` | Extract all links from a page (JS-rendered) |
| `browseScreenshot` | Take a full-page screenshot |
| `browseSearchAndDownloadImages` | Search for images, download, and save to folder |
| `browseClickElement` | Click an element by CSS selector, return resulting page |
| `browseFillForm` | Fill a form field and optionally submit |
| `openInBrowserTab` | Open URL in the built-in chat browser tab |
| `searchInBrowser` | Search web using the built-in chat browser |
| `searchImagesInBrowser` | Search images in the built-in chat browser |
| `collectImagesFromBrowser` | Collect image URLs from current chat browser page |
| `readBrowserPageText` | Read text from current chat browser page |
| `downloadImagesFromBrowser` | Download images from chat browser page |

**WebScraperTools.java** -- Web page scraping

| Method | Description |
|--------|-------------|
| `fetchPageText` | Fetch a web page and return readable text |
| `extractImageUrls` | Extract all image URLs from a page |
| `extractLinks` | Extract all links from a page |
| `searchAndDownloadImages` | Search web for images and download them |
| `fetchPageWithImages` | Fetch both text and image URLs in one call |

**WebSearchTools.java** -- Web search

| Method | Description |
|--------|-------------|
| `searchWeb` | Search the web (Serper, SerpAPI, or DuckDuckGo fallback) |
| `fetchWebPage` | Fetch a specific web page's readable text |

**WebMonitorTools.java** -- Website change monitoring

| Method | Description |
|--------|-------------|
| `startMonitor` | Start monitoring a website for changes |
| `stopMonitor` | Stop monitoring a website |
| `listMonitors` | List all active monitors |
| `checkNow` | Force an immediate check on a monitored site |

---

## Communication

**EmailTools.java** -- Email send/receive

| Method | Description |
|--------|-------------|
| `sendEmail` | Send an email (SMTP or Gmail browser fallback) |
| `sendEmailWithAttachment` | Send email with file attachment |
| `sendHtmlEmail` | Send an HTML-formatted email |
| `readInbox` | Read recent emails via IMAP |

**GmailApiTools.java** -- Gmail via Google API

| Method | Description |
|--------|-------------|
| `getUnreadEmails` | Get unread emails from Gmail |
| `getRecentEmails` | Get recent emails (read and unread) |
| `getUnreadCount` | Quick count of unread emails |

**NotificationTools.java** -- Desktop notifications

| Method | Description |
|--------|-------------|
| `showNotification` | Show a system desktop notification/toast |

---

## Media & Documents

**ImageTools.java** -- Image manipulation

| Method | Description |
|--------|-------------|
| `flipImageVertical` | Flip image top-to-bottom |
| `flipImageHorizontal` | Flip image left-to-right |
| `imageToBlackAndWhite` | Convert image to grayscale |
| `rotateImage` | Rotate image (90/180/270 degrees) |
| `resizeImage` | Resize image to new dimensions |
| `getImageInfo` | Get image dimensions, size, and format |

**PdfTools.java** -- PDF operations

| Method | Description |
|--------|-------------|
| `extractPdfText` | Extract plain text from a PDF |
| `createPdf` | Create a PDF document with title and body |
| `convertDocxToPdf` | Convert Word document to PDF (requires Word) |

**WordDocTools.java** -- Word document creation

| Method | Description |
|--------|-------------|
| `createWordDocument` | Create a .docx with title and body |
| `createSectionedDocument` | Create a .docx with multiple headed sections |

**ExcelTools.java** -- Excel automation (via COM/PowerShell)

| Method | Description |
|--------|-------------|
| `createExcelWorkbook` | Create a blank .xlsx workbook |
| `writeExcelCells` | Write values to cells |
| `readExcelCell` | Read a single cell value |
| `readExcelRange` | Read a range of cells as a text table |
| `listExcelSheets` | List worksheet names |
| `addExcelSheet` | Add a new worksheet |
| `deleteExcelSheet` | Delete a worksheet |
| `formatExcelCells` | Format cells (bold, italic, font, color) |

**QrTools.java** -- QR codes

| Method | Description |
|--------|-------------|
| `generateQr` | Generate a QR code image |
| `decodeQr` | Decode a QR code from an image |

**HuggingFaceImageTool.java** -- Local AI image classification

| Method | Description |
|--------|-------------|
| `searchHuggingFaceImageModels` | Search for image classification models |
| `classifyImageWithHf` | Classify an image using a local ONNX model |

**ScreenRecordTools.java** -- Screen recording

| Method | Description |
|--------|-------------|
| `recordScreen` | Record screen for a specified duration |
| `startScreenRecording` | Start background screen recording |
| `stopScreenRecording` | Stop ongoing screen recording |
| `openGameBar` | Open Windows Game Bar for recording |

**VideoDownloadTools.java** -- Video downloading (yt-dlp)

| Method | Description |
|--------|-------------|
| `downloadVideo` | Download video from YouTube, Twitter, TikTok, etc. |
| `downloadAudioOnly` | Download audio as MP3 |
| `getVideoInfo` | Get video info without downloading |
| `downloadPlaylist` | Download a full YouTube playlist |
| `listVideoFormats` | List available format/quality options |
| `checkYtDlp` | Check if yt-dlp is installed |

**RemotionVideoTools.java** -- Programmatic video creation (requires Node.js)

| Method | Description |
|--------|-------------|
| `setupRemotion` | Initialize the Remotion video project |
| `createComposition` | Create a video composition from React code |
| `renderVideo` | Render a composition to MP4 |
| `listCompositions` | List available compositions |
| `deleteComposition` | Delete a composition |
| `listRenderedVideos` | List rendered video files |
| `createQuickTextVideo` | Create a text animation video (no coding) |
| `createSlideshow` | Create a slideshow from images |
| `getRemotionStatus` | Get Remotion setup status |

---

## Voice & Audio

**TtsTools.java** -- Text-to-speech

| Method | Description |
|--------|-------------|
| `speak` | Convert text to speech and play audio |
| `listMicrophones` | List available microphone devices |
| `switchCloudTts` | Switch between Fish Audio and ElevenLabs |
| `switchTtsEngine` | Switch active TTS engine (fish, elevenlabs, openai, windows) |
| `ttsStatus` | Show current TTS status and settings |

**AudioListeningTools.java** -- System audio capture

| Method | Description |
|--------|-------------|
| `startListening` | Start listening to system audio in the background |
| `stopListening` | Stop listening to system audio |

**WakeWordTools.java** -- Voice activation

| Method | Description |
|--------|-------------|
| `startWakeWord` | Start always-listening wake word detection |
| `stopWakeWord` | Stop wake word detection |
| `changeWakeWord` | Change the wake word/phrase |
| `wakeWordStatus` | Get wake word detection status |

**MusicControlTools.java** -- Media playback control

| Method | Description |
|--------|-------------|
| `playPause` | Play or pause current track |
| `nextTrack` | Skip to next track |
| `previousTrack` | Go to previous track |
| `stopMusic` | Stop music playback |
| `volumeUp` | Increase system volume |
| `volumeDown` | Decrease system volume |
| `toggleMute` | Mute/unmute system audio |
| `searchSpotify` | Search Spotify for a song/artist/album |
| `playOnSpotify` | Play a specific item on Spotify |
| `getCurrentTrack` | Get info about what's playing on Spotify |
| `ensureSpotifyRunning` | Check/launch Spotify |

---

## AI & Knowledge

**ModelSwitchTools.java** -- AI model management

| Method | Description |
|--------|-------------|
| `getCurrentModel` | Get the currently active AI model |
| `switchModel` | Switch AI model at runtime |
| `listAvailableModels` | List available AI models |

**LocalModelTools.java** -- Ollama local models

| Method | Description |
|--------|-------------|
| `checkOllamaStatus` | Check if Ollama is installed and running |
| `installOllama` | Install Ollama on this computer |
| `pullModel` | Download a local AI model via Ollama |
| `listLocalModels` | List installed Ollama models |
| `removeModel` | Remove a local model |
| `switchToLocal` | Switch to using a local Ollama model |
| `switchToCloud` | Switch back to OpenAI |
| `getActiveProvider` | Get current AI provider info |

**WebSearchTools.java** -- Web search (see Browser section)

**KnowledgeBaseTools.java** -- Personal knowledge base

| Method | Description |
|--------|-------------|
| `searchKnowledgeBase` | Search documents for a keyword or phrase |
| `listKnowledgeBase` | List all documents in the knowledge base |
| `readKnowledgeDocument` | Read a specific document's full content |

**SummarizationTools.java** -- Directive summarization

| Method | Description |
|--------|-------------|
| `summarizeDirective` | Summarize all findings for a directive |
| `listDirectiveStatuses` | Show status of all directive folders |

**IntelligenceTools.java** -- Cross-domain intelligence

| Method | Description |
|--------|-------------|
| `dailyBriefing` | Generate daily briefing (weather, calendar, tasks, email, health) |
| `purchaseDecision` | Analyze a purchase against budget and goals |
| `calendarConflictScan` | Scan calendar for scheduling conflicts |
| `travelPlan` | Create a comprehensive travel plan |

---

## Memory & Profile

**ChatHistoryTool.java** -- Conversation history

| Method | Description |
|--------|-------------|
| `recallHistory` | Recall recent conversation history (last 100 messages) |
| `searchPastConversations` | Search past conversation files on disk |
| `getFullRecentHistory` | Get the full recent conversation buffer |

**EpisodicMemoryTools.java** -- Life event memory

| Method | Description |
|--------|-------------|
| `rememberEpisode` | Store a life event or episode |
| `searchMemories` | Search memories by text query |
| `recallByPerson` | Recall memories involving a specific person |
| `recallByTopic` | Recall memories by topic/tag |
| `recentMemories` | Recall most recent memories |
| `memoriesByDateRange` | Recall memories from a date range |
| `forgetMemory` | Delete a specific memory by ID |
| `memoryStats` | Get memory statistics |

**ScreenMemoryTools.java** -- Screen capture history

| Method | Description |
|--------|-------------|
| `getScreenMemory` | Get historical screen OCR text for a date |
| `takeScreenshotNow` | Take a live screenshot with OCR |
| `listScreenMemoryDates` | List dates with screen memory |

**AudioMemoryTools.java** -- Audio capture history

| Method | Description |
|--------|-------------|
| `getAudioMemory` | Get audio transcriptions for a date |
| `captureAudioNow` | Capture and transcribe system audio right now |
| `listAudioMemoryDates` | List dates with audio recordings |
| `diagAudioMemory` | Diagnose why audio memory might be empty |
| `listAudioDevices` | List Windows audio capture devices |

**WebcamMemoryTools.java** -- Webcam capture

| Method | Description |
|--------|-------------|
| `captureWebcam` | Take a photo from webcam and analyze with vision AI |
| `recordWebcamVideo` | Record a video clip from webcam |
| `getWebcamMemory` | Get historical webcam descriptions for a date |
| `listWebcamMemoryDates` | List dates with webcam recordings |
| `webcamStatus` | Check webcam memory status |
| `listCameras` | List available camera devices |
| `startWebcamCapture` | Start webcam capture |
| `stopWebcamCapture` | Stop webcam capture |

**LifeProfileTools.java** -- Comprehensive life profile

| Method | Description |
|--------|-------------|
| `getLifeProfile` | Read the entire life profile |
| `getProfileSection` | Read a specific section (Routines, Preferences, etc.) |
| `setProfileSection` | Replace a section's content entirely |
| `addToSection` | Add a bullet-point entry to a section |
| `removeFromSection` | Remove lines matching a substring |
| `searchProfile` | Search across all sections |

**PersonalConfigTools.java** -- Personal info config

| Method | Description |
|--------|-------------|
| `getPersonalConfig` | Read personal config (name, birthdate, family, work) |
| `updatePersonalSection` | Update a section in personal config |

**SystemConfigTools.java** -- System config

| Method | Description |
|--------|-------------|
| `getSystemConfig` | Read system config (browser, apps, paths, network) |
| `updateSystemSection` | Update a section in system config |

**MinsbotConfigTools.java** -- Bot identity config

| Method | Description |
|--------|-------------|
| `saveBotName` | Save the assistant's display name |
| `getBotName` | Read the assistant's saved name |

---

## Health & Finance

**HealthTrackerTools.java** -- Personal health tracking

| Method | Description |
|--------|-------------|
| `logWater` | Log water intake |
| `logMeal` | Log a meal with calories |
| `logExercise` | Log a workout session |
| `logWeight` | Log body weight |
| `logMood` | Log mood (1-10 scale) |
| `logSleep` | Log sleep duration and quality |
| `logMedication` | Log medication taken |
| `healthSummary` | Get health summary for a specific date |
| `healthTrend` | Get trend for a health metric over N days |
| `setHealthGoal` | Set a health goal |
| `listHealthGoals` | List all health goals with progress |

**FinanceTrackerTools.java** -- Personal finance

| Method | Description |
|--------|-------------|
| `logExpense` | Log an expense |
| `logIncome` | Log income received |
| `setBudget` | Set a monthly budget for a category |
| `budgetStatus` | Show budgets vs actual spending |
| `monthlyReport` | Get monthly financial report |
| `expensesByCategory` | Get expenses filtered by category |
| `addBill` | Add a recurring bill |
| `listBills` | List all recurring bills |
| `upcomingBills` | Show bills due in the next N days |
| `trackDebt` | Track a debt (loan, credit card) |
| `debtOverview` | Overview of all tracked debts |
| `setFinancialGoal` | Set a savings goal with target and deadline |
| `listFinancialGoals` | List all financial goals with progress |

**HealthMonitorTools.java** -- System health monitoring

| Method | Description |
|--------|-------------|
| `systemHealth` | Get system health snapshot (CPU, RAM, disk, top processes) |
| `startHealthMonitor` | Start continuous monitoring with alerts |
| `stopHealthMonitor` | Stop the health monitor |
| `listHealthMonitors` | List active monitors and watchdogs |
| `startWatchdog` | Start a process watchdog (auto-restart on crash) |
| `stopWatchdog` | Stop a process watchdog |
| `listProcesses` | List all running processes with CPU/memory usage |
| `killProcess` | Kill a process by name or PID |

---

## Scheduling & Automation

**ScheduledTaskTools.java** -- Timers and recurring tasks

| Method | Description |
|--------|-------------|
| `setReminder` | Schedule a one-shot reminder |
| `scheduleRecurring` | Schedule a recurring notification |
| `scheduleAiTask` | Schedule a recurring AI-generated task |
| `cancelScheduledTask` | Cancel a task by ID |
| `listScheduledTasks` | List all scheduled tasks |
| `getFiredReminders` | Get unacknowledged reminders |

**TimerTools.java** -- Simple timer

| Method | Description |
|--------|-------------|
| `setTimer` | Set a reminder after N minutes |

**CronConfigTools.java** -- Cron schedule config

| Method | Description |
|--------|-------------|
| `getCronConfig` | Read cron/scheduled checks config |
| `updateCronSection` | Update a section in cron config |

**AutoPilotTools.java** -- Proactive screen watching

| Method | Description |
|--------|-------------|
| `enableAutoPilot` | Enable auto-pilot (proactive screen help) |
| `disableAutoPilot` | Disable auto-pilot |
| `autoPilotStatus` | Check auto-pilot status |
| `adjustAutoPilotTiming` | Change check interval and suggestion cooldown |

**ProactiveEngineTools.java** -- Proactive notifications

| Method | Description |
|--------|-------------|
| `proactiveStatus` | Get proactive engine status |
| `toggleProactive` | Enable or disable the proactive engine |
| `setQuietHours` | Set quiet hours (no notifications) |
| `addProactiveRule` | Add a custom proactive check rule |
| `removeProactiveRule` | Remove a custom rule |
| `listProactiveRules` | List all active rules |
| `forceCheck` | Force an immediate proactive check |

**ScreenWatchingTools.java** -- Continuous screen observation

| Method | Description |
|--------|-------------|
| `startScreenWatch` | Start continuously watching the screen |
| `stopScreenWatch` | Stop screen watch mode |

---

## Social & Contacts

**SocialMonitorTools.java** -- Social media tracking

| Method | Description |
|--------|-------------|
| `addContact` | Add a contact to track on social media |
| `updateContact` | Update an existing contact's info |
| `listContacts` | List all tracked contacts |
| `removeContact` | Remove a contact from tracking |
| `checkBirthdays` | Check for upcoming birthdays |
| `savePost` | Save an important social media post |
| `logMention` | Log a mention of the user |
| `recentPosts` | Show recent tracked posts |
| `recentMentions` | Show recent mentions |
| `contactSummary` | Get social summary for a contact |
| `socialReport` | Generate a social monitoring report |

**GiftIdeaTools.java** -- Gift management

| Method | Description |
|--------|-------------|
| `saveContact` | Save a contact for gift ideas |
| `addPastGift` | Add past gift to avoid duplicates |
| `listContacts` | List saved gift contacts |
| `getContactDetails` | Get full details of a gift contact |
| `removeContact` | Remove a contact |
| `generateGiftIdeas` | Generate personalized gift ideas |
| `updateContactField` | Update a specific field for a contact |

---

## Trends & Discovery

**TrendScoutTools.java** -- Trend tracking

| Method | Description |
|--------|-------------|
| `addInterest` | Add a topic to track for trends |
| `removeInterest` | Remove an interest |
| `listInterests` | List all tracked interests |
| `scoutTopic` | Search YouTube and web for trending content on a topic |
| `scoutAll` | Scout all tracked interests at once |
| `searchYouTube` | Search YouTube for videos about a topic |
| `recentTrends` | Show recently discovered trends from cache |

**TravelSearchTools.java** -- Travel search

| Method | Description |
|--------|-------------|
| `searchFlights` | Search for flights between two locations |
| `searchHotels` | Search for hotels in a location |

---

## Configuration & Directives

**DirectivesTools.java** -- Persistent behavior instructions

| Method | Description |
|--------|-------------|
| `getDirectives` | Read current directives |
| `setDirectives` | Replace all directives |
| `appendDirective` | Append a directive |
| `listDirectivesNumbered` | List directives with numbers |
| `moveDirective` | Move a directive to a new position |
| `removeDirective` | Remove a directive by number |
| `clearDirectives` | Clear all directives |

**DirectiveDataTools.java** -- Directive data storage

| Method | Description |
|--------|-------------|
| `saveFinding` | Save a text finding to a directive's folder |
| `captureScreenForDirective` | Save a screenshot for a directive |
| `listDirectiveData` | List files in a directive's folder |
| `readDirectiveFinding` | Read a finding file |
| `listAllDirectiveFolders` | List all directive data folders |

**SitesConfigTools.java** -- Saved site credentials

| Method | Description |
|--------|-------------|
| `listSites` | List saved site credentials (passwords masked) |
| `getSiteCredentials` | Look up credentials for a site |
| `addSite` | Save a new site with credentials |
| `removeSite` | Remove a saved site |

**TodoListTools.java** -- Task checklist

| Method | Description |
|--------|-------------|
| `markStepDone` | Mark a plan step as done |
| `getPendingTasks` | Get all pending tasks |

---

## Utilities

**CalculatorTools.java**

| Method | Description |
|--------|-------------|
| `calculate` | Evaluate a numeric expression (+, -, *, /, ^, parentheses) |

**UnitConversionTools.java**

| Method | Description |
|--------|-------------|
| `convertUnit` | Convert between units (miles/km, lb/kg, C/F) |

**HashTools.java**

| Method | Description |
|--------|-------------|
| `fileSha256` | Compute SHA-256 checksum of a file |
| `fileSha1` | Compute SHA-1 checksum of a file |

**ClipboardTools.java**

| Method | Description |
|--------|-------------|
| `getClipboardText` | Get current clipboard text |
| `setClipboardText` | Copy text to clipboard |

**ClipboardHistoryTools.java** -- Clipboard history tracking

| Method | Description |
|--------|-------------|
| `showHistory` | Show clipboard history (last N items) |
| `searchHistory` | Search clipboard history by keyword |
| `getEntry` | Get full content of a history entry |
| `restoreEntry` | Restore a history entry to clipboard |
| `clearHistory` | Clear all clipboard history |
| `clipboardStats` | Get clipboard history statistics |

**ExportTools.java** -- Conversation export

| Method | Description |
|--------|-------------|
| `exportMarkdown` | Export conversation to Markdown file |
| `exportHtml` | Export conversation to styled HTML file |
| `exportText` | Export conversation as plain text |

**WeatherTools.java**

| Method | Description |
|--------|-------------|
| `getWeather` | Get current weather for a city (no API key needed) |

**PlaylistTools.java** -- Auto-detected music playlist

| Method | Description |
|--------|-------------|
| `getPlaylist` | Get auto-detected music playlist |
| `addSong` | Manually add a song |
| `removeSong` | Remove a song |
| `clearPlaylist` | Clear the entire playlist |

**TaskStatusTool.java**

| Method | Description |
|--------|-------------|
| `taskStatus` | Show status of background tasks |

---

## Software & Network

**SoftwareTools.java** -- Software management (winget)

| Method | Description |
|--------|-------------|
| `searchSoftware` | Search for software packages |
| `installSoftware` | Install software via winget |
| `uninstallSoftware` | Uninstall software |
| `listInstalledSoftware` | List all installed software |
| `checkForUpdates` | Check for available updates |
| `installAllUpdates` | Install all available updates |

**NetworkTools.java** -- Network management

| Method | Description |
|--------|-------------|
| `listWifiNetworks` | List available WiFi networks |
| `connectWifi` | Connect to a WiFi network |
| `disconnectWifi` | Disconnect from WiFi |
| `getWifiStatus` | Get WiFi connection status |
| `getNetworkInfo` | Get detailed network info |
| `dnsLookup` | DNS lookup for a domain |

**PrinterTools.java** -- Printer management

| Method | Description |
|--------|-------------|
| `listPrinters` | List installed printers |
| `printFile` | Print a file using default printer |
| `printToSpecificPrinter` | Print to a specific printer |
| `getDefaultPrinter` | Get default printer name |

---

## Sensory Controls

**SensoryToggleTools.java** -- Toggle bot senses

| Method | Description |
|--------|-------------|
| `toggleSensory` | Turn eyes/keyboard/hearing/mouth on or off |
| `getSensoryStatus` | Show status of all sensory features |

---

## Hotkeys

**GlobalHotkeyService.java** -- System-wide keyboard shortcuts

| Method | Description |
|--------|-------------|
| `registerHotkey` | Register a global keyboard shortcut |
| `removeHotkey` | Remove a registered hotkey |
| `listHotkeys` | List all registered hotkeys |
| `getTriggeredHotkeys` | Get hotkey events since last check |

---

## System Tray

**SystemTrayService.java**

| Method | Description |
|--------|-------------|
| `getTrayStatus` | Get tray icon status |
| `showTrayNotification` | Show a desktop notification via tray |

---

## Plugins

**PluginLoaderService.java** -- JAR plugin loading

| Method | Description |
|--------|-------------|
| `listPlugins` | List plugin JARs in plugins/ directory |
| `loadPlugin` | Load a plugin JAR |
| `unloadPlugin` | Unload a plugin |

---

## Learning & Feedback

**FeedbackLoopTools.java** -- Suggestion quality tracking

| Method | Description |
|--------|-------------|
| `rateSuggestion` | Rate a previous bot suggestion |
| `feedbackStats` | Show feedback statistics |
| `recentFeedback` | Show recent suggestion history |

**HabitDetectionTools.java** -- User habit tracking

| Method | Description |
|--------|-------------|
| `logAction` | Log a user action for habit tracking |
| `detectHabits` | Analyze actions and detect patterns |
| `habitStats` | Show habit detection statistics |
| `clearHabits` | Clear all habit data |

**SkillAutoCreateTools.java** -- Workflow automation

| Method | Description |
|--------|-------------|
| `createSkill` | Create a reusable skill/workflow |
| `listSkills` | List auto-created skills |
| `runSkill` | Run a saved skill |
| `deleteSkill` | Delete a skill |
| `toggleSkill` | Toggle a skill on/off |
| `detectPatterns` | Detect repeated action patterns |

---

## Code & Security

**CodeAuditTools.java** -- Code security scanning

| Method | Description |
|--------|-------------|
| `cloneRepo` | Clone a git repository for auditing |
| `scanForVulnerabilities` | Scan code for security issues (SQLi, hardcoded secrets, etc.) |
| `generateReport` | Generate a markdown security audit report |
| `cleanupClonedRepo` | Delete a cloned repo's temp folder |

**BackupConfigTools.java** -- Config file backup

| Method | Description |
|--------|-------------|
| `backupConfigs` | Backup config files from a folder to a zip |
| `scanConfigs` | Preview config files without backing up |
