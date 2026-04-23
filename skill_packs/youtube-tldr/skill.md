---
name: youtube-tldr
description: Summarize a YouTube video — fetches the transcript and produces 5–10 bullet points, key quotes, and a single-sentence TL;DR. Trigger on "summarize this youtube", "tldr this video", "what does this youtube video say", "bullet points of {url}", "transcribe and summarize {url}".
metadata:
  minsbot:
    emoji: "📺"
    os: ["windows", "darwin", "linux"]
---

# YouTube TL;DR

Uses Mins Bot's built-in transcript fetcher (no API key) + a summarizer. Falls back to audio download + local Whisper when captions are missing.

## Steps

1. Extract the YouTube URL or video ID from the user's message. If none is present and the clipboard holds one, use the clipboard.
2. Call `getYouTubeTranscript(videoId)`.
3. If the transcript is empty or returns "no captions":
   - Call `downloadYouTubeAudio(url)` via yt-dlp → get an mp3 path.
   - Call `transcribeAudioLocal(path)` to run local Whisper.
   - Use the resulting text as the transcript.
4. Feed the transcript to the summarization capability with the instruction:
   ```
   Produce:
   - TL;DR: one sentence.
   - Key points: 5–10 bullets in the speaker's voice.
   - Memorable quotes: up to 3 short direct quotes.
   Keep proper nouns and numbers verbatim. Ignore sponsorships / channel promos / like-and-subscribe.
   ```
5. Return the formatted summary with the video title as a heading.

## Guardrails

- If the video is >90 minutes, warn the user that the summary may be lossy and offer to chunk it.
- Never paraphrase quotes — only surface them verbatim or drop them.
- If the video is age-restricted or region-blocked, say so clearly and stop.
