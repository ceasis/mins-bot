---
name: clipboard-translate
description: Translate whatever is currently on the clipboard into a target language using the local Ollama model, then write the translation back to the clipboard. Trigger on "translate my clipboard", "translate this to X", "what does this mean in English", "pang-tagalog ito" — any request that names a language and implies the source is already copied.
metadata:
  minsbot:
    emoji: "🌐"
    os: ["windows", "darwin", "linux"]
    requires:
      bins: []
---

# Clipboard Translate

Uses the local LLM so this works **offline** — no cloud translation API.

## Steps

1. Call `getClipboardText` to fetch the current clipboard contents.
2. If clipboard is empty or looks like binary, stop and tell the user "clipboard is empty or non-text".
3. Detect source language heuristically (local model is fine at this). If the user specified a target language in their request, use that; otherwise default to English.
4. Call the local-model chat capability with a tight system prompt:
   ```
   You are a translation engine. Translate the user-provided text to {target}. Preserve line breaks, punctuation, and proper nouns. Output ONLY the translation — no preface, no quotes, no commentary.
   ```
5. Write the result to the clipboard via `setClipboardText`.
6. Reply to the user with ONE sentence: "Translated X → {target}. It's on your clipboard."

## Guardrails

- Keep the input under ~8000 characters. If larger, translate the first 8000 and tell the user.
- Never "improve" the text — translate only.
- If the detected source already matches target, skip the LLM call and tell the user "already in {target}".
