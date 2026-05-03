---
name: watch-folder
description: Watch a folder for new files / deletions / modifications (with optional glob filter and recursion) and email/webhook-notify. Trigger on "watch this folder", "alert me when files land in X", "monitor Downloads for new PDFs", "watch [folder] recursively".
metadata:
  minsbot:
    emoji: "📁"
    os: ["windows", "darwin", "linux"]
---

# Watch Folder

Event-driven folder watcher backed by Java NIO `WatchService`. Fires email + optional webhook when:

- **any** (default) — any file is created, modified, or deleted.
- **create** — only when a new file lands.
- **delete** — only on file removal.
- **modify** — only on content change.

Optional **glob filter** narrows the match (e.g. `*.pdf`, `report-*.csv`). Optional **recursive** flag walks subfolders and auto-registers new ones as they appear.

## When to use

Trigger when the user says:
- "watch [folder] for new files"
- "ping me when something lands in [folder]"
- "alert me on new PDFs in Downloads"
- "monitor [folder] recursively for [glob]"

## Steps

1. Parse:
   - **Label** — human-readable.
   - **Path** — absolute folder path. Reject if it's a file or doesn't exist.
   - **Mode** — `any` unless the user specified create/delete/modify.
   - **Filter** — glob pattern when the user mentioned a file type / pattern. Empty = all files.
   - **Recursive** — true if the user said "recursive", "including subfolders", "all subdirs".
   - **Notify email** — required.
   - **Webhook URL** — optional.
2. POST to `POST /api/skills/watch-folder`.
3. Confirm with watcher ID and what will fire the alert.

## Guardrails

- Never watch the user's entire home directory recursively — the event volume swamps the OS. Suggest a specific subfolder.
- Bursts (e.g. an editor saving via tmp→rename creates several events in a row) are auto-debounced at the service layer to ~1.5 s.
- On Windows, `WatchService` uses `ReadDirectoryChangesW` under the hood — reliable but slightly delayed (~1 s) compared to native FSEvents on macOS.
