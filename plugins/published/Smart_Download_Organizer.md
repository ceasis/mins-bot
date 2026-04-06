## Smart Download Organizer

Watches the Downloads folder and automatically sorts new files into categorized subfolders based on file type and content.

### Features
- **Auto-sort rules** — `.pdf` to Documents, `.jpg/.png` to Images, `.mp3/.mp4` to Media, `.zip` to Archives, `.exe/.msi` to Installers
- **Custom rules** — define patterns like `invoice*.pdf` goes to Finance folder
- **Smart naming** — option to prepend date (2026-04-06_filename.pdf) for chronological sorting
- **Conflict handling** — auto-rename with (1), (2) suffix if file exists in destination
- **Undo** — keeps a move log so recent sorts can be reversed
- **Folder watch** — uses Java WatchService for instant detection, no polling

### Endpoints
- `POST /api/skills/downloads/start` — start watching
- `POST /api/skills/downloads/stop` — stop watching
- `GET /api/skills/downloads/rules` — list sort rules
- `POST /api/skills/downloads/rules` — add a custom rule
- `GET /api/skills/downloads/log` — recent move history

### Configuration
Watch path: `~/Downloads` (configurable). Target base: `~/Downloads/Sorted/`. Rules in `~/mins_bot_data/download_rules.json`.