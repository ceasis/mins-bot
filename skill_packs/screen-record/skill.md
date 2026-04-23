---
name: screen-record
description: Record the screen (full or region) to an MP4 using ffmpeg. Trigger on "record my screen", "start screen recording", "capture a screencast", "record a demo", "screen record this".
metadata:
  minsbot:
    emoji: "🎥"
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

# Screen Record

A thin ffmpeg wrapper. The bot can start and stop, produce an MP4 at a reasonable size.

## Steps

1. Ask the user what to record: full screen, active window, or region.
2. Confirm audio: mic yes/no. Default = no audio (avoids accidental audio capture of music).
3. Generate a timestamped output path in `~/Videos/Screencasts/<YYYY-MM-DD>_<HHMMSS>.mp4`.
4. Start ffmpeg in background:
   - **Windows**: `ffmpeg -f gdigrab -framerate 30 -i desktop -vcodec libx264 -preset veryfast -crf 24 -pix_fmt yuv420p <out.mp4>`
   - **macOS**: `ffmpeg -f avfoundation -framerate 30 -i "1" ...`
   - **Linux**: `ffmpeg -f x11grab -framerate 30 -i :0.0 ...`
5. Reply: "Recording. Say 'stop recording' or type it when you're done."
6. On stop, send `q` to ffmpeg stdin to flush cleanly. Verify the MP4 is valid (non-zero, playable).
7. Report file path + duration + size.

## Guardrails

- Never start recording without confirming. Silent recording = creepy.
- Cap at 30 minutes by default; auto-stop and notify.
- For region mode, add `-offset_x <x> -offset_y <y> -video_size <wxh>` to the gdigrab command.
- If the file ends up 0 bytes (codec missing, denied permission), surface the ffmpeg stderr.
