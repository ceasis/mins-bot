---
name: prompt-improve
description: Take a rough LLM prompt and rewrite it for clarity, structure, and reliability — adds role, constraints, output format, and failure handling. Trigger on "improve this prompt", "rewrite this prompt", "make this prompt better", "prompt engineer this", "tighten my prompt".
metadata:
  minsbot:
    emoji: "🎯"
    os: ["windows", "darwin", "linux"]
---

# Prompt Improve

Produces a better prompt, not a better answer. The user can feed the rewritten prompt back into any model.

## Steps

1. Get the source prompt (inline, file, clipboard).
2. Analyze — does it already have:
   - A role / persona?
   - Explicit task description?
   - Input format?
   - Output format with structure?
   - Edge-case handling ("if X, say Y")?
   - Length / style constraints?
3. Rewrite to include the missing pieces, preserving the user's original intent. Use this template:
   ```
   ROLE: <if helpful>
   TASK: <crystal-clear one sentence>
   INPUT: <what you're giving the model>
   OUTPUT: <exact structure>
   CONSTRAINTS:
   - <length / tone / format>
   EDGE CASES:
   - If <X>, <respond Y>
   ```
4. Show **before** (line-numbered) and **after** (line-numbered) side by side so the user can see what changed.
5. Explain the 2–3 most impactful changes in one sentence each.

## Guardrails

- Don't rewrite past recognition — preserve the user's goal and voice.
- Don't add boilerplate that doesn't earn its place. "You are a helpful assistant" adds nothing.
- If the original is already well-structured, say so — don't invent problems to solve.
