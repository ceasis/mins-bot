---
name: video-script
description: Turn a concept into a short-form video script (30s–3min) with hook, 3–5 beats, B-roll suggestions, and a closing line. Trigger on "write a video script", "script for a reel", "tiktok script about X", "short video script", "youtube shorts script".
metadata:
  minsbot:
    emoji: "🎬"
    os: ["windows", "darwin", "linux"]
---

# Video Script

## Steps

1. Ask (if unclear):
   - Platform: TikTok / Reels / YT Shorts / YouTube long-form. Drives length + pacing.
   - Target length in seconds.
   - Voice: you on camera, voiceover, or text-on-screen only?
2. Run this prompt:
   ```
   Write a video script for <platform> targeting <seconds>s:
   FORMAT:
   [00:00-00:03] HOOK — a question or surprising claim. This is the scroll-stopper.
   [00:03-00:10] BEAT 1 — <setup>
   [00:10-00:xx] BEAT 2 — <payload>
   ...
   [xx:xx-end]   CLOSE — the single takeaway.

   For each beat, include:
   - Spoken line (under 15 words for TikTok, under 25 for long-form)
   - [B-ROLL: what's on screen]
   - [ON-SCREEN TEXT: 2-4 words max]
   ```
3. Count approximate runtime (average 2.5 words/sec spoken English). Flag if over target.
4. Offer to generate thumbnail ideas as a follow-up. If the user says yes and a `thumbnail-gen` skill is installed, **chain** via `invokeSkillPack("thumbnail-gen")`.

## Guardrails

- Don't write the whole script as voiceover — mix spoken + text-on-screen, it reads better visually.
- No "like and subscribe" filler — wastes screen time.
- Keep first 3 seconds TIGHT — platforms use that as the ranking signal.
