## Window Layout Saver

Save and restore multi-monitor window arrangements for different work contexts like coding, design, or meetings.

### Features
- **Save layout** — captures position, size, and monitor assignment of all open windows
- **Named profiles** — create profiles like "Coding", "Design Review", "Video Call"
- **One-click restore** — restores all windows to saved positions, launching closed apps if needed
- **Monitor-aware** — handles multi-monitor setups; adapts if a monitor is disconnected
- **Auto-profiles** — optionally auto-save layout when switching between monitor configurations
- **Selective restore** — choose which apps to restore from a profile

### Endpoints
- `POST /api/skills/layouts/save` — save current window layout
- `GET /api/skills/layouts` — list saved profiles
- `POST /api/skills/layouts/{id}/restore` — restore a layout
- `DELETE /api/skills/layouts/{id}` — delete a profile
- `GET /api/skills/layouts/current` — snapshot of current window positions

### Implementation
Uses PowerShell `Get-Process | Where MainWindowHandle` and Win32 `SetWindowPos` via JNA. Profiles stored in `~/mins_bot_data/window_layouts.json`. Handles DPI-scaled coordinates.