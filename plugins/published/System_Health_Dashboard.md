## System Health Dashboard

Real-time system monitoring with threshold-based alerts and historical performance tracking.

### Features
- **CPU monitoring** — current usage %, per-core breakdown, temperature (if available)
- **Memory** — used/available RAM, swap usage, top memory-consuming processes
- **Disk** — space used/free per drive, read/write speeds, SMART health status
- **Network** — active connections, bandwidth usage, top talkers by process
- **Alerts** — configurable thresholds (e.g., alert when CPU > 90% for 5+ minutes)
- **History** — stores metrics every minute for 24-hour trend analysis
- **Top processes** — ranked list of resource-hungry processes with kill option

### Endpoints
- `GET /api/skills/health/snapshot` — current system state
- `GET /api/skills/health/history?hours=24` — historical metrics
- `GET /api/skills/health/alerts` — active and recent alerts
- `PUT /api/skills/health/thresholds` — configure alert thresholds
- `POST /api/skills/health/kill/{pid}` — terminate a process

### Implementation
Uses PowerShell WMI queries on Windows. Metrics cached every 60 seconds. History in `~/mins_bot_data/health_metrics.json` (rolling 7-day window).