# Mins Bot — Autonomous Iteration Log (v03)

Continuing from ITERATIONSv02.md (which ended iter 17 at $150 / $40 / $493k). Same valuation framing. Stop early if no high-quality items remain.

---

## Iteration 18 — 2026-04-25 ~11:46

**Added:** `QuickNotesTool.togglePin(id)` — pin/unpin a note by appending/removing `#pinned` in its body. `listNotes` now surfaces pinned notes in a dedicated header section before the timeline. `/notes.html` mirrors the same — purple "📌 Pinned" rail at top.

**Why this lifts value:** Active second-brain notes (wifi password, family addresses, license plate) are 5-10 items the user wants *always* near the top. Without pinning, they sink as new notes accumulate. Reuses the existing tag mechanism — zero new persistence, zero migration.

**Files touched:**
- `src/main/java/com/minsbot/agent/tools/QuickNotesTool.java` (+`togglePin`, `listNotes` reorder)
- `src/main/java/com/minsbot/NotesPageController.java` (pinned section in viewer)

**Valuation after this iteration:**
- Per copy: **$155** — +$5. Pinning is the difference between "I'll never find that again" and "always one click away."
- Per month: **$41/mo** — +$1.
- System: **$498k** — +$5k.

---

## Iteration 19 — 2026-04-25 ~11:51

**Added:** `QuickNotesTool` now auto-tags every saved note. On `saveNote`, if the body has no hashtags, an LLM call (`ChatClient`) suggests 1-3 short categories (`#wifi`, `#car`, `#groceries`) and appends them. Skipped when the user supplies any tag manually — never overrides intent.

**Why this lifts value:** The tag system from iter 11 was powerful but lazy users (everyone) won't use it. Auto-tagging means every note participates in `notesByTag` / `listTags` from day one. Compounding effect: more searchable notes → more "the bot remembered for me" wins → higher trust.

**Files touched:**
- `src/main/java/com/minsbot/agent/tools/QuickNotesTool.java` (+`ChatClient`, `suggestTags`, `saveNote` wiring)

**Valuation after this iteration:**
- Per copy: **$162** — +$7. Auto-organization is a step-change for note retention quality.
- Per month: **$43/mo** — +$2.
- System: **$510k** — +$12k. AI auto-categorization is a category buyers will pay for; pushes past $500k.

---

## Iteration 20 — 2026-04-25 ~11:55

**Added:** `ExportBundleTool.exportBundle()` — packs `quick_notes/`, `research_archive/`, `briefing_history/` plus a manifest into `~/mins_bot_data/exports/mins-bot-export-<ts>.zip`. One tool call. Wired into `reminders` category.

**Why this lifts value:** Local-first tools live or die on data portability. "I can give you all your data, right now, in one ZIP" is the bedrock claim that distinguishes this from cloud incumbents. Also unblocks "switch to a new PC" / "give Claude my own data" flows.

**Files touched:**
- `src/main/java/com/minsbot/agent/tools/ExportBundleTool.java` (new, ~80 lines)
- `src/main/java/com/minsbot/agent/tools/ToolRouter.java` (constructor + reminders category)

**Valuation after this iteration:**
- Per copy: **$167** — +$5. Privacy-conscious users care; checkbox feature for this segment.
- Per month: **$44/mo** — +$1.
- System: **$520k** — +$10k. Data portability raises trust signal for any acquirer.

---

## Iteration 21 — 2026-04-25 ~12:00

**Added:** `WeeklyDigestTool.weeklyDigest()` — synthesizes the past 7 days of notes + research into a 200-word reflective summary with themes, items to revisit, and one suggestion. Wired into `briefing` category. Completes the time-horizon trio: now → today → week.

**Why this lifts value:** The daily-driver triad (briefing/now/recap) handles "today." A weekly digest is the Sunday-evening journaling moment — high emotional value, low frequency, exactly the thing recurring-revenue tools (Sunsama, Roam) charge for.

**Files touched:**
- `src/main/java/com/minsbot/agent/tools/WeeklyDigestTool.java` (new, ~95 lines)
- `src/main/java/com/minsbot/agent/tools/ToolRouter.java` (constructor + briefing category)

**Valuation after this iteration:**
- Per copy: **$174** — +$7. Weekly review is one of the most-cited "I would pay for this" moments.
- Per month: **$46/mo** — +$2.
- System: **$535k** — +$15k. Time-horizon completeness pushes the daily-driver story to its natural fullness.

---

## Iteration 22 — 2026-04-25 ~12:05

**Added:** `NotesApiController` exposes `POST /api/notes` (JSON body `{text}`) and `POST /api/notes/text` (plain-text body). External scripts — mobile Shortcuts, AutoHotkey, browser bookmarklets — can now push notes without going through the chat planner. Auto-tagging from iter 19 still applies because the route delegates to `saveNote`.

**Why this lifts value:** Power users save notes 50× more than casual users. A direct HTTP endpoint is the integration point that makes "from anywhere" real — phone widget, taskbar hotkey, Stream Deck button. Without it, every note has to flow through chat.

**Files touched:**
- `src/main/java/com/minsbot/NotesApiController.java` (new, ~40 lines)

**Valuation after this iteration:**
- Per copy: **$179** — +$5. Power-user accelerator; not every user notices but the ones who do are sticky.
- Per month: **$47/mo** — +$1.
- System: **$543k** — +$8k. API surface broadens integration story.

---

## Iteration 23 — 2026-04-25 ~12:11

**Added:** CRUD closes on `/notes.html`. Hover-revealed pin (📌) and delete (🗑) buttons on every note card. Backed by new `DELETE /api/notes/{id}` and `POST /api/notes/{id}/pin` endpoints. No JS dialogs — silent delete with the row vanishing in place.

**Why this lifts value:** The notes UI was read-only before this; all mutations had to go through chat. A two-button hover affordance is the universal pattern users expect (Apple Notes, Bear, Things). Without it, the page feels half-finished.

**Files touched:**
- `src/main/java/com/minsbot/NotesApiController.java` (+`DELETE`, `pin` routes)
- `src/main/java/com/minsbot/NotesPageController.java` (id on Note record, action buttons, CSS, JS handlers)

**Valuation after this iteration:**
- Per copy: **$181** — +$2. Polish, not a feature jump.
- Per month: **$48/mo** — +$1.
- System: **$548k** — +$5k.

---

## Iteration 24 — 2026-04-25 ~12:18

**Added:** "Recent code projects" card on `/home.html`. Sources from existing `ProjectHistoryService` — shows up to 5 recent projects with completion timestamp + status. The code-gen half of the bot finally has presence on the dashboard.

**Why this lifts value:** The bot has two big workflows — personal-assistant and code-gen — but the dashboard only surfaced the personal-assistant side. A dashboard that ignores half the product is misleading. Now both halves are visible at a glance.

**Files touched:**
- `src/main/java/com/minsbot/HomeDashboardController.java` (+projects card sourced from `ProjectHistoryService`)

**Valuation after this iteration:**
- Per copy: **$185** — +$4. Closes the visibility gap for code-gen users.
- Per month: **$49/mo** — +$1.
- System: **$555k** — +$7k. Story coherence: dashboard reflects the whole product.

---

## Iteration 25 — 2026-04-25 ~12:24

**Added:** Soft-delete for notes. `deleteNote` now moves to `~/mins_bot_data/quick_notes_trash/` instead of permanently removing. New `restoreNote(id)` and `listTrash()` tools complete the recovery loop.

**Why this lifts value:** "I deleted that by accident" is the panic moment that destroys trust in any second-brain tool. A trash folder costs nothing and turns destructive deletes into a recoverable operation. The kind of detail that doesn't sell the product but stops users from leaving it.

**Files touched:**
- `src/main/java/com/minsbot/agent/tools/QuickNotesTool.java` (+`TRASH_DIR`, `restoreNote`, `listTrash`; `deleteNote` becomes soft)

**Valuation after this iteration:**
- Per copy: **$188** — +$3. Trust insurance, not a sales feature.
- Per month: **$50/mo** — +$1. Round number — first time at the $50 mark.
- System: **$560k** — +$5k.

---

## Stop — 2026-04-25 ~12:25

Calling it after iter 25. The remaining backlog I can think of is genuinely polish-tier (markdown rendering on research detail, color-coded tags, per-day note count chart, JSON-RPC). The features that would meaningfully move valuation from here — proper RAG with embeddings over the archive, real onboarding, mobile companion app, a polished landing page, billing/auth — are bigger investments than the "single tool / single page" rhythm of these iterations.

## Final v03 trajectory

| # | Feature | Per copy | Per month | System |
|---|---|---:|---:|---:|
| 18 | Pin/unpin notes | $155 | $41 | $498k |
| 19 | Auto-tagging on save | $162 | $43 | $510k |
| 20 | Export bundle (ZIP) | $167 | $44 | $520k |
| 21 | Weekly digest | $174 | $46 | $535k |
| 22 | `/api/notes` direct capture | $179 | $47 | $543k |
| 23 | Inline pin/delete on `/notes.html` | $181 | $48 | $548k |
| 24 | Recent code projects on home | $185 | $49 | $555k |
| 25 | Soft-delete + restore | $188 | $50 | $560k |

**v03 net delta:** +$38 per copy, +$10/mo, +$67k system. Compared to v02 (+$55, +$16, +$123k) and v01 (+$46, +$12, +$120k), the marginal lift per iteration has clearly thinned — exactly what a real product trajectory looks like as the obvious wins get captured. The product is now a coherent local-first personal-AI bundle: morning/now/evening triad + weekly digest, second-brain (notes with auto-tags, pinning, soft-delete, search, export), durable research archive, code-gen with project history, four UI pages (`/home`, `/notes`, `/research`, `/tools`), and a public capture API.
