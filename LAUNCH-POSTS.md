# Launch posts — paste-ready copy

Channel-specific copy with variants. Pick one of each, fill in the
landing URL + video link, post.

**Replace before posting:**
- `[VIDEO_URL]` → YouTube/Loom link
- `[LANDING]` → `https://minsbot.io`
- `[GITHUB]` → public repo URL (only if you make one public for HN)
- `@you` / `your.name` → your handle
- `[YOUR_OS_DEMO_DETAIL]` → one specific OS action you actually demoed

---

## Twitter / X

**Posting plan:** Tuesday or Wednesday, 10–11 AM ET (peak engagement).
Quote-tweet your own pinned tweet 24 hours later with a 2nd shorter clip.

### Variant A — the JARVIS hook (recommended)

> I've been quietly building a desktop AI co-pilot that actually USES my computer.
>
> Watches my screen. Clicks for me. Talks. Stops mid-sentence when I interrupt. Runs jobs while I sleep. Local-first.
>
> 90s demo:
>
> [VIDEO_URL]
>
> Closed alpha — [LANDING]

### Variant B — the contrast hook

> Every "AI assistant" is a chatbot stuck in a browser tab.
>
> Mine lives on my desktop and operates my actual apps.
>
> [VIDEO_URL]
>
> Local-first. Markdown-extensible. Closed alpha at [LANDING]

### Variant C — the personal story hook

> I haven't manually opened my email in 3 weeks.
>
> Built a desktop AI that runs my morning brief at 4 AM, drafts replies, schedules follow-ups, and dictates the day back to me.
>
> 90 seconds:
>
> [VIDEO_URL]
>
> [LANDING]

### Reply / thread continuation (any variant)

> The 3 things every chatbot can't do that this can:
>
> 1. Click your buttons, fill your forms, drag your files
> 2. Remember your work across sessions in plain markdown on YOUR machine
> 3. Run scheduled jobs and deliver results when you're actually back at the desk
>
> Built in Java + JavaFX + Spring AI. ~30k lines.
>
> Detailed write-up coming this week.

### Tags / quote-RT targets to seed reach

People who post about AI agents, local-first, and dev tools (don't tag
all in one tweet — looks spammy. Pick 1, max 2):

- @swyx (latent space)
- @mckaywrigley
- @levelsio (will RT a punchy demo)
- @yoheinakajima
- @itsandrewgao
- @indydevdan
- @nat (Friedman)
- @karpathy (only if the demo is genuinely impressive)

---

## Hacker News

**Posting plan:** Tuesday 9 AM ET. Title format `Show HN: …` is essential.
You need GitHub repo public OR a working demo URL — HN crowd will check.

**Submit:** `https://news.ycombinator.com/submit`
**Title:** see variants below (max 80 chars)
**URL:** `[GITHUB]` if open-source; otherwise `[LANDING]`
**Text:** the body below (don't put body if you put a URL — HN forces one or the other)

### Title variants

A. `Show HN: Local-first AI desktop co-pilot that operates your apps for you`
B. `Show HN: Mins Bot – a JARVIS-style desktop AI written in Java`
C. `Show HN: I built a local-first AI agent that lives on the desktop, not in a tab`

(A wins for keyword density. C wins for story.)

### First comment (post immediately after submission)

> Hi HN — I'm @you, solo developer. I built this because every "AI assistant" I tried was either a browser chatbot, a sandboxed first-party (Apple Intelligence, Copilot), or an IDE-only tool (Cursor). None of them could do what I actually wanted: see my whole screen, operate any app, remember my work across days, run scheduled jobs while I'm asleep.
>
> Architecture:
> - JavaFX 21 transparent floating window + WebView for the chat UI
> - Spring Boot backend with ~150 `@Tool` methods exposed to the LLM (BYO key — OpenAI / Claude / Gemini / Ollama)
> - Screen vision via GPT Vision; native mouse/keyboard/clipboard via JNA + java.awt.Robot
> - All memory, skills, configs persisted as plain markdown in `~/mins_bot_data/`. Nothing leaves the machine unless you explicitly enable a cloud integration.
> - File-driven recurring tasks + scheduled reports with guaranteed delivery (generates while you sleep, holds delivery until you're back at the keyboard)
>
> What I'd love feedback on:
> 1. The "scheduled-report-with-delayed-delivery" pattern — is anyone else solving the "ran while you were AFK" problem differently?
> 2. The skill format (markdown with `## Steps`) — too loose? too rigid?
> 3. The local-first vs cloud-sync trade-off. I want to add encrypted cloud sync next so memory follows you across machines, without giving up the "your data, your machine" pitch. Best precedent for E2EE sync I should study?
>
> Demo: [VIDEO_URL]
> Roadmap, install instructions, full architecture docs in the README.

### Things HN will roast you for (be ready)

- "Why Java?" → answer: JavaFX gives me a transparent always-on-top window with a WebView, all cross-platform, no native packaging hell. Spring Boot for the backend because the @Tool method binding works out of the box.
- "Why not Electron?" → answer: tried it; the 200MB RAM floor and Chromium update treadmill weren't worth it for a desktop daemon.
- "How is this different from AutoGPT/AgentGPT/etc.?" → answer: those run in a sandbox or a server. This runs on your actual desktop and touches your actual mouse, keyboard, screen, files.
- "Why local-first?" → answer: my screen, my files, my audio. The deal is I never trust a SaaS with that scope of access.
- "Show me a non-cherry-picked demo" → have a 3-min unedited screencast ready to drop in replies.

---

## LinkedIn

**Posting plan:** Monday or Tuesday, 8 AM your local time.
Different audience: founders, ops people, exec assistants, sales reps.
Less "agent, screen vision" and more "executive assistant that doesn't sleep".

### Variant A — the founder framing (recommended for solo-founder persona)

> Founders, raise your hand if your week looks like this:
>
> – Inbox triage every morning before real work starts
> – Calendar tetris with 12 stakeholders
> – Hunting for that one PDF from last month
> – Forgetting the follow-up you promised on a Tuesday call
>
> I built a desktop AI that does these for me. Not in a browser tab — on my actual computer, operating my actual apps, with persistent memory of what I'm working on.
>
> Closed alpha is rolling out this month. Limited spots.
>
> [LANDING]
>
> #AI #Productivity #Founders #buildinpublic

### Variant B — the productivity framing

> Last week I checked my email twice. My AI did the rest.
>
> Mins Bot is a local-first desktop co-pilot that runs scheduled briefings, triages email, fills forms, and remembers context across days — all without sending your data to a third party.
>
> 90-second demo: [VIDEO_URL]
>
> Get the alpha at [LANDING]

---

## Indie Hackers

**Posting plan:** Long-form post on the IH forum, ~600 words. Lead with
the journey, not the product. IH crowd loves a build-in-public arc.

**Title:** `I built a local-first AI desktop co-pilot in 6 months. Launching the alpha today.`

### Body

> **TL;DR:** Mins Bot is a desktop AI that watches your screen, operates your apps, and remembers your work across sessions — local-first, your data stays on your machine. 90-second demo: [VIDEO_URL]. Waitlist at [LANDING].
>
> ---
>
> **Why I built it**
>
> Six months ago I tried to use ChatGPT to fix my morning. Every day I waste 45 minutes triaging email, checking calendar, and shuffling docs before real work starts. The chatbot couldn't help — it doesn't see my screen, doesn't have access to my apps, doesn't remember yesterday.
>
> Then I tried Cursor for non-coding work. It's brilliant in the editor, useless outside it.
>
> Then I tried various agent frameworks. Most of them run in a sandbox or a Docker container. None of them touched my actual desktop.
>
> So I built one that does.
>
> **What it does**
>
> Three things, concretely:
>
> 1. **It operates my apps.** Sees the screen via GPT Vision, clicks buttons, fills forms, drags files, sends keystrokes. From "open the latest contract from Acme" to actually doing it: one sentence, no manual clicking.
>
> 2. **It remembers across sessions.** Episodic memory, life profile, custom skills — all saved as plain markdown in `~/mins_bot_data/` on my machine. The longer I use it, the better it knows my work. None of this lives on a server.
>
> 3. **It runs while I sleep.** A 4 AM scheduled report generates my daily brief (calendar + unread emails + weather). The brief sits in a queue and only delivers when I'm back at the desk — no notifications when I'm AFK. I see it the moment I touch the mouse in the morning.
>
> **The build**
>
> - Java 17 + JavaFX 21 + Spring Boot 3.5 + Spring AI 1.0
> - ~30k lines of Java, ~10k of HTML/CSS/JS
> - 150+ `@Tool` methods exposed to the LLM
> - Memory layer is just markdown files. Trivially portable, trivially editable, no DB dependency.
> - Solo build, evenings and weekends
>
> **What's hard**
>
> Everyone says "agents are easy now." They're not. The hard parts are:
> - Reliable screen-vision click accuracy (still a tuning problem; I shipped a calibration tab to measure it)
> - Tool budget management (OpenAI caps you at 128 tools per call; I built a classifier to dynamically pick the relevant subset)
> - Avoiding the recursive watch loop (when the bot reads its own UI in the screenshot and reacts to itself — solved with explicit "ignore your own overlay" prompts)
> - Guaranteed delivery for async reports (generate at 4 AM, deliver at 9 AM when user is at desk — built a small queue + activity-detector for this)
>
> **What's next**
>
> v1 is local-only. v2 is encrypted cloud sync — your memory follows you across machines without me ever seeing it. Then mobile companion, then a paid skill marketplace.
>
> **Ask**
>
> If you're a solo founder, consultant, or operator drowning in admin work — the alpha is open. [LANDING]. Free for the first 50.
>
> If you build agents or AI products — I'd love your feedback on the architecture. Post in the comments or DM.

---

## Product Hunt

**Posting plan:** Schedule for a Tuesday or Wednesday launch (avoid Monday
— too crowded). Submit "Coming Soon" page 7-10 days in advance to seed
followers. Have at least 50 confirmed supporters lined up before launch.

### Tagline (60 chars max — Product Hunt rule)

A. `The AI co-pilot that actually uses your computer for you`
B. `Local-first JARVIS for your desktop`
C. `Desktop AI that clicks, types, and remembers — locally`

### Product description (260 chars max)

> Mins Bot is a desktop AI co-pilot that operates your apps, watches your screen, runs scheduled jobs while you sleep, and remembers across sessions. All local. Your data stays on your machine. Skills extend it via plain markdown — no code required.

### Maker comment (post immediately after launch)

> Hi PH 👋 — I'm @you, solo dev. Mins Bot is what I built when I got tired of every AI assistant being a chatbot in a browser tab.
>
> Three things that make it different:
> 1. It actually operates your computer (clicks, types, drags, screen-watches)
> 2. Local-first — your memory and files never leave your machine unless you opt in
> 3. Extensible by markdown — drop a `.md` file in `skills/` and it has a new skill
>
> Demo: [VIDEO_URL]
>
> Free open-source build is available now (BYO API key). Pro tier with cloud sync + mobile companion + managed models is coming after I talk to the first 50 alpha users.
>
> Genuinely open to brutal feedback. Reply with anything you'd want it to do that it doesn't.

---

## r/LocalLLaMA / r/MachineLearning / r/ChatGPT

**Posting plan:** post separately, NOT cross-posted (mods hate cross-posts).
Reddit demands genuine engagement — no "check out my product" energy.
Frame as "I made this and I'm sharing it because the LocalLLaMA crowd
will actually appreciate the local-first angle".

### r/LocalLLaMA — title

`I built a desktop AI agent that runs locally with Ollama, operates my apps, and persists memory in markdown. Source + 90s demo.`

### Body (keep ~150 words)

> Built this over the last 6 months as a side project. Highlights for this sub:
>
> - **BYO LLM** — works with OpenAI/Anthropic/Gemini OR local Ollama models. Tool-calling tested with Llama 3.3, Qwen 2.5
> - **Screen vision** — uses whatever vision model you point it at. GPT-4o by default; can swap to Claude or Gemini
> - **No telemetry, no cloud calls** beyond your chosen LLM provider
> - **Memory is plain markdown** in `~/mins_bot_data/` — version it, sync it with your own tooling, edit it in vim
> - **Skills are markdown too** — drop a file with `## Steps`, the agent follows them
>
> Demo: [VIDEO_URL]
> Source: [GITHUB]
>
> What I'd love help with: (1) better local-vision-model recommendations for screenshot understanding (Llava? Pixtral? something newer?), (2) feedback on the markdown skill format from anyone who's built similar.

---

## DM template (for the 5 founder friends)

Personal — no template feels right. But the rough shape:

> Hey [Name] — built something I think you'd actually use. Desktop AI that handles email triage, runs morning briefs, finds files for you. Closed alpha, 5 free spots reserved for friends. Wanna try it this week?
>
> 90s demo: [VIDEO_URL]
>
> No pressure — just want honest feedback if you have 10 min.

---

## What success looks like (Day 7 metrics)

Set these targets up front. If you miss them by 2x, your message isn't landing — fix the demo before pushing more channels.

| Channel | Day 1 view target | Day 7 signup target |
|---|---|---|
| Twitter | 5,000 impressions | 30 signups |
| HN | front page (200+ points) | 200 signups |
| LinkedIn | 2,000 views | 15 signups |
| IH | 1,000 reads | 30 signups |
| Reddit (LocalLLaMA) | 200 upvotes | 50 signups |
| **Total** | **~10,000 reach** | **~325 signups** |

If Day 7 total is **< 100 signups across all channels**, the message isn't
working. Don't post more — re-shoot the demo with a different hook and
re-launch in 2 weeks.

If Day 7 total is **> 500 signups**, you've hit something. Stop posting
and start onboarding alpha users one by one (limit of 50 — you cannot
support more without losing your mind in your first month).
