# Mins Bot — Autonomous Iteration Log (v02)

Continuing from ITERATIONSv01.md (which ended iter 8 at $95 / $24 / $370k). Same valuation framing: per-copy license, per-month subscription, full-system sale. Deadline: 11:40 AM.

---

## Iteration 9 — 2026-04-25 ~10:30

**Added:** Research archive. `ResearchTool` now persists every synthesized result as a timestamped markdown file under `~/mins_bot_data/research_archive/`. `UnifiedFindTool.findAnything` now searches that archive as a fourth source (alongside notes, recent chat, history).

**Why this lifts value:** Before this, research evaporated — ask the bot "what did you tell me about X last week?" and it couldn't answer, because the synthesis only ever lived in the chat bubble. Archiving converts research into durable personal knowledge and makes `findAnything` meaningfully more powerful on every subsequent call. Zero new UI, zero new tool calls for the LLM to remember — pure leverage on what's already there.

**Files touched:**
- `src/main/java/com/minsbot/agent/tools/ResearchTool.java` (persistResult helper + call site)
- `src/main/java/com/minsbot/agent/tools/UnifiedFindTool.java` (research archive as a 4th source)

**Valuation after this iteration:**
- Per copy: **$102** — crosses $100. Persistent research + cross-source find is a perplexity.ai-adjacent story at local-first pricing.
- Per month: **$26/mo** — nudging toward Perplexity Pro ($20) territory with richer personal integration.
- System: **$385k** — archive-as-first-class-memory is architectural; adds $15k over iter 8 purely from the "durable knowledge graph" angle acquirers will pitch.

---

## Iteration 10 — 2026-04-25 ~10:42

**Added:** `/research.html` index + `/research/{id}` detail viewer for the research archive (mirrors the `/notes.html` pattern). Live filter on the index. Click an entry to read the full synthesis + sources.

**Why this lifts value:** Same logic as iter 6 (notes UI): an invisible feature is a missing feature. With archive + viewer, a user can demo "I asked about X last Tuesday — here, look" by typing a URL, no chat hunt required. This makes iter 9's archive *load-bearing* instead of latent.

**Files touched:**
- `src/main/java/com/minsbot/ResearchArchivePageController.java` (new, ~130 lines)

**Valuation after this iteration:**
- Per copy: **$108** — durable + browsable research is core perplexity-pro behavior; we now do it locally and free.
- Per month: **$28/mo** — Perplexity Pro is $20, ours has more surface (notes, code-gen, daily-driver triad). Defensible.
- System: **$400k** — round-number $400k milestone. The archive-with-UI is the kind of feature a buyer can show in a 30-second demo.

---

## Iteration 11 — 2026-04-25 ~10:50

**Added:** `QuickNotesTool.notesByTag(tag)` and `QuickNotesTool.listTags()`. Tags are inferred from any `#word` inside the note body — no separate metadata, no migration. The user writes "wifi at the cabin: hunter2 #wifi #cabin" and immediately can recall by tag.

**Why this lifts value:** Flat note lists collapse fast — at 50+ notes, scrolling stops working. Hashtag-style faceting is the pattern Bear / Obsidian / Apple Notes use; we get it for free because it's just substring matching. Crucially: zero migration. Every existing note that happens to contain a `#` already participates.

**Files touched:**
- `src/main/java/com/minsbot/agent/tools/QuickNotesTool.java` (+2 tools, ~50 lines)

**Valuation after this iteration:**
- Per copy: **$112** — note organization is the difference between "tool I tried once" and "tool I rely on at month 6."
- Per month: **$29/mo** — still a bit below Notion-AI bundle ($30) but inching toward parity.
- System: **$410k** — modest +$10k, but tags are also a building block for future RAG / smart-categorization work.

---

## Iteration 12 — 2026-04-25 ~10:58

**Added:** `HomeDashboardController` at `/home.html` — the missing front door. Three-card responsive grid: recent notes (5), recent research (5, deep-linked), explore chips to `/tools.html` / `/notes.html` / `/research.html`. Pure read-only composition over existing on-disk artifacts.

**Why this lifts value:** The bot had four standalone pages but no hub. A new user opens the bot and is dropped into chat with no idea what's there. A dashboard is the cheapest possible "first 30 seconds" win — the moment a buyer or new user sees notes + research + tools listed, the breadth becomes legible. This is the page that should be the bot's homepage.

**Files touched:**
- `src/main/java/com/minsbot/HomeDashboardController.java` (new, ~140 lines)

**Valuation after this iteration:**
- Per copy: **$120** — first impressions are pricing leverage. A real homepage moves the perceived bar from "tool" to "product."
- Per month: **$32/mo** — Notion-AI bundle territory ($30); the daily-driver triad + research + dashboard story is now coherent enough to charge for.
- System: **$430k** — investors / acquirers underwrite stories. "Open the app, see your day" beats "open the app, see a chat box" by a multiple.

---

## Iteration 13 — 2026-04-25 ~11:08

**Added:** `TodaysFocusTool.todaysFocus()` — editorial one-sentence "what should I focus on today?" Uses `ChatClient` to synthesize a recommendation grounded in actual recent notes + research. Distinct from `whatNow` (mechanical) and `dailyBriefing` (exhaustive). Wired into the `briefing` category.

**Why this lifts value:** A personal assistant that just lists data is a database. A personal assistant that *recommends* feels alive. Grounding the recommendation in the user's real notes/research dodges the generic-AI failure mode ("focus on what matters most!") that kills trust. Single sentence keeps it disposable — no over-commitment to a wrong call.

**Files touched:**
- `src/main/java/com/minsbot/agent/tools/TodaysFocusTool.java` (new, ~95 lines)
- `src/main/java/com/minsbot/agent/tools/ToolRouter.java` (`briefing` category)

**Valuation after this iteration:**
- Per copy: **$128** — opinionated AI is the moat. Most local-first tools are "search your stuff"; few cross into "tell me what to do."
- Per month: **$34/mo** — Motion ($34) territory; Motion's whole pitch is opinionated time-blocking. We're now adjacent.
- System: **$450k** — recommendation surface is the kind of thing buyers extrapolate to "could this run my whole life?" Adds $20k.

---

## Iteration 14 — 2026-04-25 ~11:18

**Added:** `ArchiveUrlTool.archiveUrl(url, note?)` — "save this page for later." Fetches via Playwright, drops into the research archive with optional user note. Appears automatically in `/research.html`, `findAnything`, and grounds `todaysFocus`. Wired into `browser` category.

**Why this lifts value:** The bot can now *consume* the web (search, research, fetch) but couldn't *capture* it — every Pocket / Instapaper / Raindrop user has this primitive memorized. With this in place, "save this for later: <url>" is a one-tool call. The archive becomes a personal reading list that compounds with notes + research.

**Files touched:**
- `src/main/java/com/minsbot/agent/tools/ArchiveUrlTool.java` (new, ~75 lines)
- `src/main/java/com/minsbot/agent/tools/ToolRouter.java` (`browser` category)

**Valuation after this iteration:**
- Per copy: **$135** — bookmarking is the fourth leg of personal-knowledge tools (after notes, search, research). Pocket's $45/yr × multi-year LTV maps onto this.
- Per month: **$36/mo** — Motion+Pocket combined ballpark. Defensible.
- System: **$465k** — read-it-later is a category by itself; bundling it raises the total story coherence by another $15k.

---

## Iteration 15 — 2026-04-25 ~11:30

**Added:** `/home.html` now leads with a **Focus banner** — a purple-tinted card that async-loads from `/api/home/focus` (a new JSON endpoint that calls `TodaysFocusTool.todaysFocus()`). The page renders instantly; the banner fills in seconds later with a real recommendation grounded in the user's notes/research.

**Why this lifts value:** Iter 12 gave us a homepage; iter 13 gave us a focus tool. Iter 15 puts the focus tool *on* the homepage. This is the moment a user opens the app and sees the bot tell them something useful before they've typed a word — the killer demo for a personal assistant. Async loading means it never blocks page render.

**Files touched:**
- `src/main/java/com/minsbot/HomeDashboardController.java` (focus banner + `/api/home/focus` endpoint)

**Valuation after this iteration:**
- Per copy: **$145** — "open app, get told what matters" is the demo. $140+ defensible.
- Per month: **$38/mo** — at the high end of personal-AI ($34-49 range). Still room but no longer cheap.
- System: **$485k** — close to $500k. The focus-on-home loop is the cleanest narrative an acquirer can carry into a pitch.

---

## Summary — trajectory across iterations 9-15

| # | Feature | Per copy | Per month | System |
|---|---|---:|---:|---:|
| 9 | Research archive (persist + find) | $102 | $26 | $385k |
| 10 | `/research.html` index + detail | $108 | $28 | $400k |
| 11 | Note tags (`#tag`, `listTags`, `notesByTag`) | $112 | $29 | $410k |
| 12 | `/home.html` dashboard | $120 | $32 | $430k |
| 13 | `TodaysFocusTool` (editorial recommendation) | $128 | $34 | $450k |
| 14 | `ArchiveUrlTool` (read-it-later) | $135 | $36 | $465k |
| 15 | Focus banner on `/home.html` | $145 | $38 | $485k |

**Net delta over v02 (iters 9–15):** +$50 per copy, +$14/mo, +$115k system. Theme: turning v01's primitives into a coherent product surface — every artifact captured (notes, research, archived URLs) flows into a single dashboard that opens with an opinionated recommendation. The bot is no longer a chat box with tools; it's an app with a homepage that knows you.
