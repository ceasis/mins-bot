---
name: gif-from-video
description: Convert a short video clip (or segment) into a shareable GIF or MP4 under 10 MB. Trigger on "make a gif from this video", "convert to gif", "turn this clip into a gif", "gif this", "make this shareable".
metadata:
  minsbot:
    emoji: "🎞️"
    os: ["windows", "darwin", "linux"]
    requires:
      bins: ["ffmpeg"]
    install:
      - id: winget-ffmpeg
        kind: winget
        package: Gyan.FFmpeg
        bins: ["ffmpeg"]
        label: Install FFmpeg (winget)
---

# GIF from Video

Two outputs: a true GIF (for places that don't accept video) and an MP4 (Slack/Discord prefer MP4 — smaller, smoother).

## Steps

1. Resolve source video path. Parse optional start/end timestamps from user ("first 5 seconds", "from 0:10 to 0:25"). Default: first 10 seconds.
2. Extract the clip: `ffmpeg -i <src> -ss <start> -t <duration> -c copy <tmp.mp4>` (re-encode only if copy fails).
3. Generate GIF palette (avoids ugly dithering):
   ```
   ffmpeg -i <tmp.mp4> -vf "fps=12,scale=640:-2:flags=lanczos,palettegen" -y <palette.png>
   ffmpeg -i <tmp.mp4> -i <palette.png> -lavfi "fps=12,scale=640:-2:flags=lanczos [x]; [x][1:v] paletteuse" -y <out.gif>
   ```
4. Also produce a compressed MP4 (H.264 CRF 28, muted, max 1280px wide) as an alternative.
5. Report both file sizes. If the GIF is > 10 MB, warn and recommend the MP4.

## Guardrails

- Cap clip duration at 30 seconds by default — longer produces huge GIFs.
- Strip audio on the MP4 by default; offer to keep it if user says so.
- Delete intermediate `tmp.mp4` / `palette.png` after success.
