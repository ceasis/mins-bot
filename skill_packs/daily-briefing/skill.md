---
name: daily-briefing
description: Assemble a morning briefing — unread Gmail, today's calendar, local weather, upcoming bills and birthdays — into one spoken or written summary. Trigger on "brief me", "morning briefing", "what do I have today", "start my day", "good morning".
metadata:
  minsbot:
    emoji: "☀️"
    os: ["windows", "darwin", "linux"]
---

# Daily Briefing

Produce one cohesive paragraph the user can read or hear. Don't dump raw lists.

## Steps

1. Call `getUnreadEmails` with limit=5. Capture sender + subject, not body.
2. Call `getTodayEvents`. Capture times, titles, locations.
3. Call `getWeather` for the user's configured location.
4. Call `getUpcomingImportant` (the umbrella aggregator) with `daysAhead=3` to pick up bills, birthdays, reminders.
5. Compose a **natural-language** paragraph, 4–6 sentences:
   - Start with weather + the single most important thing today.
   - Mention email volume only if noteworthy ("three unread, one from your partner").
   - Group calendar events by morning/afternoon.
   - Close with one upcoming-days note ("heads up: rent is due Friday").
6. If auto-speak is on, let it flow to TTS naturally. Otherwise return the paragraph.

## Guardrails

- **Never** list every email subject — summarize by sender or topic cluster.
- **Never** read out meeting links or phone numbers — privacy.
- If no calendar events: say "your calendar is clear" rather than staying silent.
- If Gmail / Calendar not connected: say so once, don't repeat every morning.
