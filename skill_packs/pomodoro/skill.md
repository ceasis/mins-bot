---
name: pomodoro
description: Start a Pomodoro focus session — 25 minutes on, 5 off, with pre/post rituals (goal set at start, retro at end). Trigger on "start pomodoro", "focus timer", "25 minute session", "pomo please", "focus mode".
metadata:
  minsbot:
    emoji: "🍅"
    os: ["windows", "darwin", "linux"]
---

# Pomodoro

Not just a timer — adds the rituals that make Pomodoro actually work.

## Steps

1. **Pre-ritual**: ask the user one line — "What's the single focus for this session?" Store as `pomodoro.goal`.
2. **Do Not Disturb**: if on Windows, call the system-settings tool to enable Focus Assist. If on macOS, enable Focus. Remember to restore after.
3. **Silence TTS pings**: temporarily disable auto-speak notifications.
4. **Start a 25-minute timer**. Post a message "Session started. I'll check in at 25:00." and go silent.
5. **On timer fire**: speak (if TTS on) or post "Session over. Goal was: <goal>. Did you get there? (yes/partial/no)".
6. **Retro**: capture the answer in a one-line log at `~/mins_bot_data/pomodoro.log` (timestamp, goal, outcome).
7. **Break prompt**: start a 5-minute break timer with "Stretch, water, look out the window."
8. **Restore**: re-enable Focus Assist, TTS auto-speak.

## Guardrails

- Never interrupt the user during the 25 minutes — stay silent even if they chat. Just queue messages for after.
- If the user says "abort pomodoro" mid-session, end cleanly and log "aborted".
- After 4 sessions, enforce a 15-minute break instead of 5.
