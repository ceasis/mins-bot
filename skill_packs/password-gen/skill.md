---
name: password-gen
description: Generate a strong password locally (no cloud call), put it on the clipboard, and auto-clear the clipboard after 45 seconds. Trigger on "generate a password", "make me a password", "strong password", "new password for X", "password 20 chars".
metadata:
  minsbot:
    emoji: "🔑"
    os: ["windows", "darwin", "linux"]
---

# Password Generator

100% local — no cloud call, no history, no persistence. The generated value never goes to any LLM provider.

## Steps

1. Parse the user's request for constraints:
   - Length (default 20, min 12, max 64)
   - Character classes: lowercase always on; uppercase/digits/symbols default on unless user says otherwise
   - Human-readable mode? ("pronounceable") → use dice-word style instead
2. Call `generatePassword(length, upper, digits, symbols)` — this is a local util that uses `SecureRandom`.
3. Call `setClipboardText(password)`.
4. Schedule a clipboard clear in 45 seconds: `scheduleClipboardClear(45)` (the harness owns this).
5. Reply with the **length and character-class breakdown**, NEVER the password itself in chat:
   ```
   Generated a 20-char password (upper + lower + digits + symbols) on your clipboard.
   Clipboard will auto-clear in 45 seconds.
   ```

## Guardrails

- **Never** echo the password in the chat reply. If the user explicitly says "show me the password", show it but remind them chat history is persisted.
- **Never** save to any notes/memory — this is ephemeral.
- **Never** call a cloud model for this — `SecureRandom` is already cryptographically strong.
- If the user asks "what was my last password", decline — we don't retain them by design.
