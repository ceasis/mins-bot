# Mins Bot — Launch Plan (90 days)

The single goal of this document: **get to 100 people on a waitlist and 5 paying users by Day 90.**

That's not a $1B company. It's the only honest first step toward one.
Everything below is scoped to that target.

---

## 1. Positioning

**One sentence:**
> Mins Bot is a desktop AI co-pilot that actually uses your computer for you — clicks, types, reads your screen, watches your work, runs on your machine.

**The 30-second pitch:**
> Most AI products are chatbots stuck behind a browser tab. Mins Bot lives on
> your desktop, sees what you see, and operates your apps directly. It
> remembers what you're working on across days, runs scheduled jobs while
> you sleep, and gets smarter the longer you use it. Local-first, your data
> stays on your machine.

**The 90-second pitch (for the demo video):** see Section 3.

**Category positioning** (pick one and own it):
- ❌ "AI assistant" — competes with ChatGPT/Claude, you lose
- ❌ "AI agent platform" — competes with LangGraph/Zapier, you lose
- ✅ **"Desktop AI co-pilot"** — you OWN the OS-level execution angle. Almost no competitors.

---

## 2. Target persona — pick ONE for v1

Two viable wedges. Pick one for the next 90 days; the other waits.

### A) Solo founder / consultant (RECOMMENDED for v1)

| Why | Detail |
|---|---|
| Decision speed | Buys $50/mo without asking IT |
| Pain | Drowning in email, calendar tetris, doc shuffling, follow-ups |
| Where they hang out | X/Twitter (#buildinpublic), LinkedIn, Indie Hackers, founder Slack/Discord groups |
| What they'll pay | $30–100/mo for "personal chief of staff" |
| Existing fit | Gmail / Calendar / file search / scheduled reports / orchestrator agent — already shipped |

**Day-in-the-life this product fixes:** Wakes up → 4 AM scheduled brief is waiting (calendar + unread email summary + weather). Asks "who haven't I replied to in 3 days?" → bot pulls Gmail, ranks by importance, drafts replies. Lunchtime: "find that contract from Acme last month and copy the renewal date" → done in seconds without opening anything.

### B) AI engineer / researcher (ALT)

| Why | Detail |
|---|---|
| Self-selecting | Will install a Java app + edit YAML |
| Distribution | HN front page, GitHub stars, viral demos in AI Twitter |
| Pain | Wants a local-first alternative to Cursor/ChatGPT for personal automation |
| Risk | Won't pay much; love free. Optimizes for weird configurations |

**Pick A.** Engineers can be the loud demo audience but founders are the early revenue.

---

## 3. Demo video script — 90 seconds, shoot today

**Format:** screen recording of the JavaFX shell + voiceover. No talking head. Loom or OBS.

**Hook (0:00 – 0:08):**
> "I haven't manually opened my email in three weeks. This is why."

[Cut to JavaFX floating window on desktop. User types: "what's waiting for me this morning?"]

**Section 1 — Morning brief, automated (0:08 – 0:25):**
[Bot pushes a 4 AM scheduled report into the chat unprompted: 5 calendar events, 3 unread email summaries, weather. Voiceover:]
> "It generated this brief at 4 AM while I was asleep. Held the message until I touched my mouse. That's all configured in one markdown file."

[Cut to file: `~/mins_bot_data/scheduled_reports/morning-brief.md` — show the 5-line config.]

**Section 2 — Local OS execution (0:25 – 0:50):**
[User: "find my latest CV and open it"]
[Bot: scans Desktop/Downloads/Documents → identifies CV → opens it. Voiceover:]
> "It searched my disk, recognized which file is a CV by its name pattern, opened the right one. No file picker, no folder navigation. The screen is its UI; the OS is its API."

[Quick montage: screenClick on a button, drag-and-drop a file, fill a form via Tab — show 3 different screen actions in 5 seconds.]

**Section 3 — Memory that compounds (0:50 – 1:10):**
[User: "what was I working on yesterday afternoon?"]
[Bot recalls episodic memory + screen OCR → summarizes session]
[User: "remind me to follow up with Sarah on Friday"]
[Bot saves to recurring tasks file. Voiceover:]
> "Every session leaves a trail. Episodic memory, life profile, custom skills — all in plain markdown on YOUR machine. Nothing leaves the device unless you tell it to."

**Section 4 — JARVIS mode (1:10 – 1:25):**
[Click the eye icon → bot starts narrating what's on screen as user opens Slack: "you have 3 mentions in #engineering, the most urgent is from Mateo asking about the Q4 deploy"]
[While bot is speaking, user starts to talk → bot stops mid-sentence → barge-in voiceover:]
> "It interrupts itself the moment I start to talk. Like JARVIS."

**CTA (1:25 – 1:30):**
> "Local-first AI desktop co-pilot. Closed alpha. Sign up at minsbot.io."

**That's it.** 90 seconds, 5 specific demos, no fluff. Shoot it on a real desktop with real data (use a fresh Google account if you don't want yours on camera).

---

## 4. Landing page — see [marketing/landing/index.html](marketing/landing/index.html)

A single-file static landing built alongside this doc. Drop it on Vercel,
Netlify, GitHub Pages, or behind your own domain. 4 sections:

1. **Hero** — headline, subhead, demo video, waitlist email field
2. **What it does** — 3 cards: OS-level execution / Persistent memory / Markdown skills
3. **Different from ChatGPT/Cursor/Claude** — comparison table
4. **Sign up** — single email field again, FAQ, footer

No pricing yet. No download link yet. The only conversion event is **email signup**. Don't engineer a checkout for a product nobody has asked for yet.

**Hosting:** put `marketing/landing/index.html` on a free Vercel project pointed at a domain. Total time: 30 minutes.

**Email capture:** wire to ConvertKit / Beehiiv / Mailchimp free tier. Send 1 email/week to the waitlist with progress + an early-access invite when ready.

---

## 5. Distribution — week-by-week, first 4 weeks

| Week | Channel | Action | Goal |
|---|---|---|---|
| 1 | Twitter/X (you) | Post the 90s demo video. Tag @swyx, @mckaywrigley, @levelsio, @v0. Pinned tweet. | 1,000 views, 30 signups |
| 1 | LinkedIn | Same demo, more buttoned-up framing ("the AI co-pilot for solo operators") | 500 views, 10 signups |
| 2 | Hacker News | Show HN: "I built a local-first AI desktop co-pilot in Java" — link to a public GitHub repo + landing | 100 upvotes = front page = 200+ signups |
| 2 | Indie Hackers | Long-form write-up of the build journey + the "why local-first" angle | 30 signups |
| 3 | r/LocalLLaMA + r/MachineLearning + r/ChatGPT | Demo gif + "open to feedback" post. Don't shill — show. | 50 signups |
| 3 | Founder Slack/Discord groups (On Deck, IH, Lenny's Newsletter community) | Personal DMs to 20 founders you respect: "I built this for me, would you try it?" | 5 alpha users (these become your first paying customers) |
| 4 | Product Hunt | Coming Soon page → launch on Day 28. Aim Tuesday/Wednesday. | 200 signups, top 5 of the day |

**Compound:** every signup gets a weekly progress email. Send REAL updates ("this week the bot got 12% better at finding files") not marketing fluff. Reply to every reply.

---

## 6. Pricing (don't publish until Day 60)

Tentative — validate with the alpha 5 first:

- **Free** — current open-source build, BYO API keys, single machine
- **Pro $30/mo** — managed cloud sync, mobile companion, premium models included, priority support
- **Team $80/seat/mo** — shared skills, shared memory, audit log
- **Enterprise** — call us

**Don't put pricing on the landing page yet.** "Closed alpha — request access" is the only CTA. Pricing comes after you've talked to 20 alpha users and know what they'd pay.

---

## 7. Metrics to actually watch

Forget vanity. Track exactly 5 numbers, weekly:

1. **Waitlist signups** (target: +25/week)
2. **Demo video views** (target: 5k by Day 30)
3. **Active alpha users** — installed and used in the last 7 days (target: 10 by Day 60, 50 by Day 90)
4. **D7 retention of alpha users** — of people who installed, how many used it again 7 days later (target: >40%)
5. **Paying users** (target: 5 by Day 90)

**The retention number is the only one that tells you if this is real.** If D7 < 40%, the product isn't sticky enough — fix that before scaling distribution.

---

## 8. Day 1 checklist (what to do RIGHT NOW)

- [ ] Buy `minsbot.io` (or `.app` / `.dev`) — $12/year, GoDaddy or Namecheap
- [ ] Deploy [marketing/landing/index.html](marketing/landing/index.html) to Vercel — 30 minutes
- [ ] Sign up for ConvertKit free tier, wire the email form — 15 minutes
- [ ] Set up a GitHub Org `minsbot/` and make this repo public (or create a marketing-only public repo with the landing) — 30 minutes
- [ ] Create a Twitter/X account `@minsbotapp` — 5 minutes
- [ ] Record the 90-second demo using OBS or Loom — 2 hours (3 takes)
- [ ] Post the demo on Twitter with the landing link — 5 minutes
- [ ] DM 5 founder friends asking them to try the alpha — 30 minutes

**Total: one focused day.** Everything else this week is iteration on the demo and replying to the first signups.

---

## 9. What NOT to do for the next 90 days

- ❌ Build mobile companion (Day 91+)
- ❌ Build cloud sync (Day 91+, after retention is proven)
- ❌ Build skill marketplace (Day 180+)
- ❌ Add more vision engines (you just removed them)
- ❌ Add more messaging integrations (you just trimmed them)
- ❌ Fundraise (zero leverage at 0 users; raise at 100 paying users for 10x better terms)
- ❌ Hire (waste of money pre-PMF; you and an LLM coding assistant are enough)

**The product is done enough.** Distribution is the bottleneck.
