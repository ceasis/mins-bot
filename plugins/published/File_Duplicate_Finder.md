## File Duplicate Finder

Scans selected directories for duplicate files using content-based hashing, showing exact space wasted and offering safe cleanup.

### Features
- **Fast scanning** — two-pass approach: first groups by file size, then SHA-256 hashes only size-matched files
- **Smart ignore** — skips system folders, hidden files, and configurable exclusion patterns
- **Space report** — shows total duplicates found, space reclaimable, breakdown by file type
- **Safe delete** — moves duplicates to Recycle Bin (never permanent delete), keeps the oldest copy
- **Preview** — view duplicate groups side-by-side before taking action
- **Export** — save scan results as CSV for manual review

### Endpoints
- `POST /api/skills/dupes/scan` — start scan with target directories
- `GET /api/skills/dupes/results` — get scan results
- `POST /api/skills/dupes/delete` — delete selected duplicates (to Recycle Bin)
- `GET /api/skills/dupes/stats` — summary statistics

### Configuration
Max scan depth: 10 levels. Blocklist: System32, $Recycle.Bin, node_modules. Min file size: 1KB (skip tiny files).