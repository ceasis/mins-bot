## Automated Report Generator

Pulls data from multiple configured sources and produces formatted Excel spreadsheets and PDF reports on a schedule.

### Features
- **Data sources** — REST APIs, local CSV/JSON files, Excel workbooks, database queries (JDBC)
- **Templates** — define report layouts with headers, data tables, charts, and summary sections
- **Scheduling** — run reports daily, weekly, or monthly via cron expressions
- **Formatting** — auto-styled Excel with headers, borders, number formats; PDF with logo and pagination
- **Variables** — use {{today}}, {{week_start}}, {{month}} in queries and titles
- **Delivery** — save to folder, email as attachment, or both

### Endpoints
- `GET /api/skills/reports/templates` — list report templates
- `POST /api/skills/reports/templates` — create/update a template
- `POST /api/skills/reports/run/{id}` — execute a report now
- `GET /api/skills/reports/history` — list generated reports

### Storage
Templates in `~/mins_bot_data/report_templates/`. Generated reports in `~/mins_bot_data/reports/` with timestamp-named files.