## RSS Feed Aggregator

Collects articles from configured RSS/Atom feeds, generates AI-powered summaries, and highlights must-read content.

### Features
- **Feed management** — add/remove RSS and Atom feed URLs with custom labels
- **Auto-refresh** — checks feeds every 30 minutes for new articles
- **AI summaries** — generates 2-3 sentence summaries of each article using the configured AI model
- **Priority scoring** — rates articles by relevance to your configured interests
- **Read/unread tracking** — mark articles as read, filter by status
- **Daily digest** — compiles top 10 articles into a morning briefing
- **Search** — full-text search across article titles and summaries

### Endpoints
- `GET /api/skills/feeds` — list configured feeds
- `POST /api/skills/feeds` — add a new feed
- `DELETE /api/skills/feeds/{id}` — remove a feed
- `GET /api/skills/feeds/articles?unread=true` — list articles
- `GET /api/skills/feeds/digest` — today's digest

### Storage
Feed configs in `~/mins_bot_data/rss_feeds.json`. Articles cached in `rss_articles.json` with 30-day rolling window. Max 1000 articles cached.