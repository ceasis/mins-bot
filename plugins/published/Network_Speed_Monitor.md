## Network Speed Monitor

Continuous internet speed tracking with historical data, alerts, and ISP performance reports.

### Features
- **Scheduled tests** — runs speed tests every 30 minutes (configurable) using speedtest-cli
- **Metrics** — download speed, upload speed, ping latency, jitter
- **Alerts** — notification when speed drops below configured threshold (e.g., < 50 Mbps)
- **History** — stores all results for trend analysis over days/weeks/months
- **ISP report** — generates a report card comparing actual speeds vs. advertised plan
- **Chart data** — provides data points for time-series visualization

### Endpoints
- `POST /api/skills/speedtest/run` — run a speed test now
- `GET /api/skills/speedtest/latest` — get most recent result
- `GET /api/skills/speedtest/history?days=7` — historical results
- `GET /api/skills/speedtest/stats` — average/min/max over time period
- `PUT /api/skills/speedtest/config` — update interval and thresholds

### Configuration
Test interval: 30 minutes. Alert threshold: 50 Mbps download. Results in `~/mins_bot_data/speedtest_history.json`. Uses `speedtest-cli` or fallback HTTP download test.