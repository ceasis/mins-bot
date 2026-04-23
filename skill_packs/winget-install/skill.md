---
name: winget-install
description: Install any Windows application via winget, silently. Trigger on "install X", "get me Chrome", "install VS Code", "put spotify on my pc", "download and install Y", "add Z to my computer" — when the user names a common app and wants it installed.
homepage: https://learn.microsoft.com/en-us/windows/package-manager/winget/
metadata:
  minsbot:
    emoji: "📦"
    os: ["windows"]
    requires:
      bins: ["winget"]
---

# Winget Install

Installs Windows apps with silent flags so there's no UAC dance per dependency.

## Steps

1. Parse the user's message for the app name. Examples: "install chrome", "get vscode", "put obsidian on my pc".
2. Resolve to a winget ID:
   - First try an exact match from the common-app table below.
   - If not in the table, call `runShell` with `winget search "<name>"` and pick the first result whose Name is a close match.
3. Confirm with the user once before installing: "Install `<Name>` (winget id `<Id>`)? This takes 1–3 min."
4. On confirmation, run:
   ```
   winget install --id <ID> --silent --accept-source-agreements --accept-package-agreements
   ```
5. Report exit code + whichever binary name(s) are now on PATH.

## Common-app table (fast path)

| User says | winget ID |
|---|---|
| chrome | Google.Chrome |
| firefox | Mozilla.Firefox |
| edge | Microsoft.Edge |
| vscode / vs code | Microsoft.VisualStudioCode |
| obsidian | Obsidian.Obsidian |
| notion | Notion.Notion |
| spotify | Spotify.Spotify |
| discord | Discord.Discord |
| slack | SlackTechnologies.Slack |
| zoom | Zoom.Zoom |
| 7zip | 7zip.7zip |
| git | Git.Git |
| gh / github cli | GitHub.cli |
| node | OpenJS.NodeJS |
| python | Python.Python.3.12 |
| ffmpeg | Gyan.FFmpeg |
| yt-dlp | yt-dlp.yt-dlp |
| rustdesk | RustDesk.RustDesk |

## Guardrails

- Always confirm before installing — don't silently add software.
- If winget returns "multiple packages match", surface the top 3 and ask the user to pick.
- If the user asks for something that isn't on winget, suggest scoop or a direct download link and stop.
