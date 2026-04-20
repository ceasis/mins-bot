# Cloud Sync — End-to-end encrypted memory vault

The single hardest moat to copy: **the bot remembers you across every machine, but Mins Bot the company can't read a byte of it.**

This document is the v1 spec. Built right, this is what justifies a paid tier and converts "neat demo" into "I can't switch off this product."

---

## Goals (in priority order)

1. **Memory follows the user across machines.** Wipe a laptop, install on a new desktop → the bot still knows you.
2. **Mins Bot the company never sees plaintext.** Zero-knowledge architecture. Server stores opaque encrypted blobs only.
3. **Resilient to network loss.** Every change is queued locally first; sync is opportunistic.
4. **Cheap enough to bundle in a $30/mo Pro tier.** Cloud cost per user must be ≤ $0.10/month.
5. **Trivially auditable.** A user with `openssl` and the README must be able to verify their data is encrypted client-side.

## Non-goals (do not build in v1)

- ❌ Real-time multi-machine collab (you have one bot per user; not a CRDT problem)
- ❌ Sharing memory between users (v3+ — if/when teams)
- ❌ Sub-second sync (5-minute intervals are fine for personal data)
- ❌ Server-side full-text search (would require plaintext access — defeats the model)
- ❌ Sync of large blobs: screenshots, audio clips, video. Local-only.

---

## Architecture overview

```
┌──────────── User's Machine ─────────────┐         ┌────────── Mins Bot Cloud ────────────┐
│                                         │         │                                       │
│  ~/mins_bot_data/  (plaintext markdown) │         │  Auth (Supabase / Clerk / WorkOS)    │
│         │                               │         │         │                             │
│         ▼                               │         │         ▼                             │
│  CloudSyncService (Java)                │ HTTPS   │  Sync API (single Spring Boot svc)   │
│   - watches files for changes           │  ──►    │   POST /sync/upload   {key, blob}    │
│   - queues mutations                    │         │   GET  /sync/changes?since=X         │
│   - encrypts AES-256-GCM client-side    │  ◄──    │   GET  /sync/blob/{key}              │
│   - uploads opaque blobs                │         │   DELETE /sync/blob/{key}            │
│         │                               │         │         │                             │
│         ▼                               │         │         ▼                             │
│  User's master key (derived from        │         │  Cloudflare R2 / Backblaze B2        │
│   passphrase via Argon2id; never        │         │   - opaque blobs only                │
│   leaves device; cached encrypted on    │         │   - per-user keyspace                │
│   disk via OS keychain)                 │         │   - lifecycle: keep last 7 versions  │
│                                         │         │                                       │
└─────────────────────────────────────────┘         └───────────────────────────────────────┘
```

**Two services on the cloud side. Total.** Auth + a thin sync gateway. Storage is just R2 (S3-compatible, $15/TB/month, no egress fees).

---

## What gets synced

### Synced (small, structural, identity-defining)

| Path under `~/mins_bot_data/` | Why |
|---|---|
| `personal_config.txt` | core identity facts |
| `system_config.txt` | machine prefs (debatable — see §"per-machine" below) |
| `cron_config.txt` | scheduled checks |
| `minsbot_config.txt` | bot behavior config |
| `system-prompt.md` | the user's edited master prompt |
| `personality.json` | bot persona |
| `directives.txt` | standing instructions |
| `todolist.txt` | pending tasks |
| `gift_contacts.txt` | personal data |
| `life_profile.txt` | personal data |
| `episodic_memory/*.md` | the long-term memory layer (the moat) |
| `skills/*.md` | custom skills (the marketplace seed) |
| `mins_recurring_tasks/*.md` | task definitions |
| `scheduled_reports/*.md` | report definitions |
| `delivered_reports/*.md` | history of reports the user actually saw (for audit) |

**Total realistic size per user: 1–10 MB** even after a year of use.
Memory is the moat; the moat is small enough to fit in a `tar.gz`.

### Per-machine (NEVER synced)

| Path | Why |
|---|---|
| `screen_memory/` | huge OCR dumps; machine-specific; no value cross-machine |
| `audio_memory/` | privacy + size; per-machine speakers |
| `webcam_memory/` | privacy nuclear; per-machine cameras |
| `mins_google_integrations/tokens.json` | OAuth tokens are device-bound by Google rules |
| `pending_reports/` | timing-sensitive; would re-deliver on every machine |
| `scheduled_reports_state/*.last-fired.txt` | per-machine — otherwise machine A fires for machine B |
| `screenshots/` | huge, ephemeral |
| Any file > 1 MB | size cap — large files belong elsewhere |

**Hard rule:** if a path matches the per-machine list, the sync service refuses to upload it even if the user manually edits the includelist. Defense in depth.

---

## Crypto design

### Key hierarchy

```
User passphrase (entered once at setup, never sent over wire)
    │
    │  Argon2id (memory: 64 MB, iterations: 3, parallelism: 4)
    │  Salt: random 16 bytes per user, stored in cloud (not secret)
    ▼
Master Key (32 bytes)
    │
    ├──►  Per-file File Key (HKDF-SHA256(master, "file:" + path-hash))
    │     │
    │     ▼
    │     AES-256-GCM encrypts file content; 12-byte random nonce per save
    │
    └──►  Filename Key (HKDF-SHA256(master, "filename"))
          │
          ▼
          AES-SIV encrypts the path so the server sees opaque blob keys
          (deterministic — same path always maps to same blob key, so
          updates overwrite cleanly, but server can't see "skills/morning-brief.md")
```

### What the server sees

- A user ID (UUID, set up at account creation)
- Per-blob `content-id` (an AES-SIV-encrypted path → 32-byte hex string, opaque)
- Encrypted blob bytes (random-looking)
- A version number (monotonic int per content-id)
- A modification timestamp
- The Argon2id salt (per-user; not secret)

That's it. **Server cannot:**
- Read any file content
- See any file path or skill name
- Know how many distinct files the user has of any type
- Decrypt anything even with full DB + R2 access

### Key storage on the device

- Derived `Master Key` is held in memory while the bot runs.
- For convenience, an OS-keychain-encrypted copy is cached at:
  - Windows: Windows Credential Manager (`win.minsbot.master-key`)
  - macOS: Keychain (`com.minsbot.master`)
  - Linux: libsecret / GNOME Keyring
- If the OS keychain isn't available, user is prompted for the passphrase on each launch.

### Recovery

- **Forgot passphrase = data is gone.** The bot prints this clearly at setup. Users who can't accept that should disable cloud sync.
- We provide a one-time **recovery code** (24-word BIP-39 mnemonic) at setup that derives the same Master Key. User stores it offline (1Password, paper, etc.)
- Any device with either the passphrase or the recovery code can decrypt the vault.

### What we explicitly DO NOT do

- ❌ Cloud-side keys (e.g., AWS KMS) — defeats zero-knowledge
- ❌ Server-side decryption "for support" — there's nothing to support; if you can't read your own vault you're locked out, see Recovery
- ❌ Email-based password reset — would break the model entirely

---

## Sync protocol

### Mutation flow (laptop ➝ cloud)

```
1. User edits ~/mins_bot_data/skills/morning-brief.md
2. CloudSyncService's WatchService fires (NIO file watcher)
3. CloudSyncService:
   a. Reads the file
   b. Hashes content (SHA-256) — skip upload if hash unchanged
   c. Encrypts: AES-256-GCM(file_content, file_key, fresh_nonce)
   d. Computes content-id: AES-SIV(filepath, filename_key)
   e. Pushes to local sync queue (durable: SQLite or just a journal file)
4. Background uploader (every 30s OR on-demand):
   a. POST /sync/upload   { content_id, blob, version, mtime }
   b. On 409 Conflict: see §"Conflict resolution"
   c. On success: increment local version, mark queue entry done
```

### Pull flow (new device joining, OR poll for remote changes)

```
1. GET /sync/changes?since=<last_sync_token>
   → returns: [{ content_id, version, mtime, size }, ...]
2. For each new/changed:
   a. GET /sync/blob/<content_id>
   b. Decrypt: AES-256-GCM(blob, file_key, nonce)
   c. Decrypt content-id back to file path: AES-SIV-decrypt(content_id, filename_key)
   d. Write to disk (atomic rename: write to .tmp, fsync, rename)
3. Update last_sync_token
```

### Conflict resolution

Personal data + slow change rate → conflicts will be rare. Strategy:

- **Default:** last-write-wins by mtime. Simple, predictable.
- **Override for skills + episodic_memory:** keep BOTH versions, append `.conflict-<timestamp>` suffix to the loser. User reconciles manually next time they open the file. (Mirrors what Dropbox / iCloud do.)
- **Hard rule:** server NEVER auto-merges encrypted blobs. It can't — they're opaque. Any "merge" is a client-side decision after decrypt.

### Sync interval

- Mutation push: **on file change + every 30s drain** of any queued
- Remote pull: **every 5 min** when online + on app startup + on demand via "Sync Now" tool
- "Sync Now" exposed as both a chat tool (`syncNow()`) and a panel button

---

## Spring backend (the cloud side)

**Single Spring Boot service.** No microservices nonsense for v1.

```
src/main/java/io/minsbot/cloud/
├── CloudSyncApplication.java       (Spring Boot entry)
├── auth/
│   ├── AuthController.java         (POST /auth/signup, /auth/login → returns JWT)
│   ├── JwtFilter.java              (validates bearer on every /sync/*)
│   └── User.java                   (id, email, salt, created_at, plan)
├── sync/
│   ├── SyncController.java         (POST /sync/upload, GET /sync/changes, GET /sync/blob, DELETE /sync/blob)
│   ├── BlobStorageService.java     (R2 / S3 client; one bucket, key = userId + "/" + contentId)
│   ├── SyncMetadataRepo.java       (Postgres: blob versions, mtimes, sizes per user)
│   └── QuotaGuard.java             (per-user limits: 50 MB total, 1 MB per blob, 10k blobs)
└── billing/
    └── BillingController.java      (POST /billing/webhook ← Stripe; flips user.plan)
```

**Database:** Postgres. Three tables only:

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    pw_hash TEXT NOT NULL,          -- separate from sync key; just for login
    sync_salt BYTEA NOT NULL,       -- 16 bytes; sent to client at login
    plan TEXT NOT NULL DEFAULT 'free',
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE blobs (
    user_id UUID REFERENCES users(id),
    content_id TEXT NOT NULL,       -- AES-SIV encrypted filepath, opaque to server
    version BIGINT NOT NULL,        -- monotonic per (user_id, content_id)
    mtime TIMESTAMPTZ NOT NULL,
    size_bytes INT NOT NULL,
    r2_key TEXT NOT NULL,           -- "userId/contentId/version"
    PRIMARY KEY (user_id, content_id)
);

CREATE TABLE blob_history (
    user_id UUID,
    content_id TEXT,
    version BIGINT,
    mtime TIMESTAMPTZ,
    r2_key TEXT,
    deleted_at TIMESTAMPTZ           -- soft-delete; cleaned by cron after 7 days
);
```

**Endpoints:**

```
POST   /auth/signup               { email, password, recovery_pubkey } → 201 { jwt, sync_salt }
POST   /auth/login                { email, password }                  → 200 { jwt, sync_salt }
POST   /sync/upload               { content_id, version, mtime, blob } → 200 | 409 { server_version }
GET    /sync/changes?since=<ts>                                        → 200 [{ content_id, version, mtime, size }]
GET    /sync/blob/<content_id>                                         → 200 <encrypted bytes>
DELETE /sync/blob/<content_id>                                         → 204
GET    /sync/quota                                                     → 200 { used_bytes, blob_count, limit }
```

**Hosting:** single $5/mo VPS (Hetzner) for the Spring service + Postgres + R2 bucket. Total cloud cost at 1,000 users: ~$30/mo. At 100,000 users: ~$1,500/mo (R2 dominates). Margin is fine.

---

## Java client integration (in this repo)

### New service

```
src/main/java/com/minsbot/cloud/
├── CloudSyncService.java       (@Service; the orchestrator)
├── KeyDerivationService.java   (Argon2id + HKDF + AES-SIV/GCM via BouncyCastle)
├── SyncQueueRepo.java          (SQLite at ~/mins_bot_data/.sync/queue.db)
├── CloudSyncTools.java         (@Tool: syncNow, syncStatus, signOut, restoreFromCloud)
└── CloudSyncConfig.java        (@ConfigurationProperties: app.cloud.enabled, base-url, etc.)
```

### Wire-in points (existing code)

| Hook | Why |
|---|---|
| `SystemPromptService.reindexSkills()` | After reindex, push any changed skill files to sync queue |
| `EpisodicMemoryService.save(...)` | Push the new episodic memory entry to sync queue |
| `RecurringTasksService.rescan()` | Push any new/edited task files |
| `ScheduledReportService.parseReportFile(...)` | Push any new/edited scheduled report files |
| `PersonalConfigTools.updatePersonalInfo(...)` | Push personal_config.txt |
| `LifeProfileTools.update*` | Push life_profile.txt |
| `GiftIdeaTools.saveContact(...)` | Push gift_contacts.txt |
| All other markdown-touching tools | Same pattern |

A central `CloudSyncService.markDirty(Path)` method takes any path and queues it. All the above just call this — no code knows the encryption details.

### File watcher (catches edits made outside the bot — vim, IDE, etc.)

```java
// CloudSyncService.@PostConstruct
WatchService watcher = FileSystems.getDefault().newWatchService();
SYNCED_DIRS.forEach(dir -> dir.register(watcher,
    StandardWatchEventKinds.ENTRY_CREATE,
    StandardWatchEventKinds.ENTRY_MODIFY,
    StandardWatchEventKinds.ENTRY_DELETE));
```

Background thread polls the watcher, calls `markDirty(path)` on each event.

### Configuration (application.properties additions)

```properties
# Cloud sync — opt-in. Free tier works without this entirely.
app.cloud.enabled=false
app.cloud.base-url=https://sync.minsbot.io
app.cloud.user-email=
# Master key derived from passphrase prompted on first run; cached in OS keychain.
# Sync interval (seconds) — how often the queue is drained.
app.cloud.upload-interval-sec=30
# Pull interval (seconds) — how often we check the cloud for remote changes.
app.cloud.pull-interval-sec=300
# Hard size cap per file. Anything bigger refuses to sync.
app.cloud.max-file-bytes=1048576
```

### Setup UX (a new Cloud Sync card in the control panel)

```
┌─────────────────────────────────────────────────┐
│  Cloud Sync                                     │
│                                                 │
│  Sync your memory + skills across machines.     │
│  Encrypted on your device. We never see your    │
│  data — not even with a court order.            │
│                                                 │
│  [ Sign in with email ]                         │
│  [ Sign up with email + passphrase ]            │
│                                                 │
│  Status: ● Synced 2 minutes ago                 │
│  Vault: 3.2 MB · 47 blobs                       │
│  Last pull: 1 minute ago                        │
│                                                 │
│  [ Sync now ]   [ Show recovery code ]   [ Sign out ] │
└─────────────────────────────────────────────────┘
```

---

## What this unlocks (business, not just tech)

1. **Switching cost.** A user with 6 months of episodic memory + 30 custom skills can't migrate to a competitor without rebuilding everything. Memory IS the moat — cloud sync makes the moat survive a laptop replacement.

2. **Pro tier justification.** "$30/mo for cloud sync + mobile companion + managed models" is an easy yes once the user has invested. "$30/mo for a slightly nicer chatbot" is a hard no.

3. **Mobile companion (next build).** Read your scheduled reports on the phone. Send commands to your desktop. **The mobile app does NOT include the screen-vision agent — that stays on desktop.** Mobile is a remote control + read pane. The bot still lives on the laptop. This is the right scope; building a real mobile agent is a multi-year project.

4. **Team tier (later).** Shared skills, shared agents — same crypto, just multiple authorized devices on a workspace. v3+.

5. **Audit story for enterprise.** "We literally cannot read your data" is a compliance & GDPR superpower. Beats every SaaS competitor.

---

## Build order (8 weeks, solo)

| Week | Build | Done means |
|---|---|---|
| 1 | Cloud Spring service: auth + Postgres + R2 + 4 endpoints | curl can upload + retrieve a blob |
| 2 | Java client: KeyDerivationService + CloudSyncService skeleton | can encrypt + decrypt a file end-to-end via test |
| 3 | Sync queue (SQLite) + upload loop + WatchService integration | edit a file → it appears encrypted in R2 within 30s |
| 4 | Pull loop + conflict handling | run on machine A, sync, run on machine B → files appear |
| 5 | Setup UX (sign-up, sign-in, recovery code, status card) | non-technical user can complete onboarding |
| 6 | OS keychain integration (Win Cred Manager / macOS Keychain / libsecret) | passphrase only entered once per device |
| 7 | Quota guard + size limits + per-user dashboard | a misbehaving client cannot blow up costs |
| 8 | Beta with 10 alpha users, fix bugs, ship | 10 users have multi-machine vaults working |

---

## Cost model (rough)

Per user per month:
- **R2 storage:** ~5 MB avg vault × $0.015/GB = $0.000075/user/mo (negligible)
- **R2 egress:** free (Cloudflare R2's killer feature vs S3)
- **Postgres rows:** ~50/user, $0 effectively at low scale
- **Spring service:** $5/mo VPS handles ~1k users; scale linearly
- **Bandwidth:** modest; 5 MB vault × ~10 syncs/day ≈ 1.5 GB/user/month inbound = trivial

**At 10,000 paying Pro users:**
- R2 + Postgres: ~$50/mo
- Compute (3 VPSes load-balanced): $30/mo
- **Total cloud cost: ~$80/mo against $300,000/mo gross revenue.** Gross margin ~99.97%.

The economics are absurdly good because you're storing tiny encrypted blobs and you can't process them server-side.

---

## What to read before building

- [age encryption library](https://github.com/FiloSottile/age) — clean modern reference for client-side encryption file format. Steal the layout.
- [Standard Notes' end-to-end encryption white paper](https://standardnotes.com/help/2/what-is-standard-notes-encryption) — closest commercial product in spirit. Identical threat model.
- [Bitwarden's password vault sync](https://bitwarden.com/help/what-encryption-is-used/) — for the key-derivation pattern and recovery-code mechanics.
- [BIP-39 spec](https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki) — for the recovery mnemonic.
- [BouncyCastle docs](https://www.bouncycastle.org/) — Java crypto library; supports Argon2id, AES-SIV, HKDF natively.

---

## What I deliberately did NOT design here

- **Real-time collab** — not needed; one bot per user
- **CRDTs** — overkill at the file granularity for personal data
- **Server-side full-text search** — would require plaintext access; defeats model
- **Sharing between users** — v3+
- **Sub-second sync** — 5min is fine for personal scope
- **Search UX over the cloud vault** — the bot already searches your local vault; stays local

The discipline here is: **the simplest design that makes the moat real.** Anything else is yak-shaving until 1,000 paying users prove it matters.
