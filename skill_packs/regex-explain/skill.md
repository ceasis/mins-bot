---
name: regex-explain
description: Explain a regex in plain English, piece by piece, with real-world matching examples. Trigger on "explain this regex", "what does this regex do", "what does /^...$/ match", "regex breakdown", "parse this regex".
metadata:
  minsbot:
    emoji: "🧩"
    os: ["windows", "darwin", "linux"]
---

# Regex Explain

All local — just a focused LLM task with a tight output format.

## Steps

1. Extract the regex from the user's message. Accept any of: `/pattern/flags`, `"pattern"`, raw `pattern`, Python r-string, bash heredoc. If there's ambiguity, ask.
2. Call the local-model chat capability with this system prompt:
   ```
   You are a regex teacher. Given ONE regex, produce:
   1. A one-sentence summary of what it matches.
   2. A token-by-token breakdown. Each line: `TOKEN — meaning`.
   3. Three positive examples that match.
   4. Three negative examples that DON'T match, with a note on why each fails.
   5. Flag any classic bugs (greedy .* where .*? intended, missing anchors, unescaped special chars).
   Output exactly these sections, no preamble.
   ```
3. Return the output in a fenced block.

## Guardrails

- If the regex looks unsafe (catastrophic backtracking pattern like `(a+)+b`), flag it prominently at the top.
- Don't rewrite the regex unless asked — just explain what's there.
- If the input isn't a regex at all, say so and stop — don't guess.
