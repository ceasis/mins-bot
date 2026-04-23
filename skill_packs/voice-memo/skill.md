---
name: voice-memo
description: Record a voice memo from the default microphone, transcribe it locally, and append the transcript to today's journal entry. Trigger on "record a memo", "take a voice note", "quick voice memo", "remember this — {speaks}", "log a thought".
metadata:
  minsbot:
    emoji: "🎙️"
    os: ["windows", "darwin", "linux"]
---

# Voice Memo

Fully local transcription via the bundled Whisper. Useful when your hands aren't free or you want to capture something faster than typing.

## Steps

1. Confirm briefly: "Recording — say 'stop' or pause 3 sec to end."
2. Call start a mic recording (audio-record tool) with VAD enabled (voice-activity-detection auto-stops on silence).
3. When recording ends, get the WAV file path.
4. Call `transcribeAudioLocal(wavPath)` — this runs whisper.cpp, no cloud call.
5. Clean up the transcript:
   - Capitalize first letter of sentences.
   - Strip filler ("um", "uh", "like I said") — but preserve meaning.
   - If the memo is a structured list ("first… second… third"), format as bullets.
6. Open today's journal file via `appendJournalEntry(text, timestamp=now)`.
7. Reply: "Captured {N} words. Appended to today's journal at {path}."
8. Delete the WAV file unless `app.voice-memo.keep-audio=true`.

## Guardrails

- Max recording length: 5 minutes. Auto-stop at that point and transcribe what we have.
- If the mic is muted or no audio is captured, surface that clearly — don't silently produce an empty journal entry.
- Never upload the audio anywhere — this is the local-first path.
- If the user says "scratch that" at the end, discard the recording without saving.
