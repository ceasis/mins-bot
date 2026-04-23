---
name: compare-llm-outputs
description: Send the same prompt to two or more models (local Ollama, cloud GPT/Claude/Gemini) and show a side-by-side comparison — answer, length, latency, token count. Trigger on "compare models on this", "llm bake-off", "which model answers better", "try this prompt on X and Y", "model comparison".
metadata:
  minsbot:
    emoji: "⚖️"
    os: ["windows", "darwin", "linux"]
---

# Compare LLM Outputs

Useful for deciding when local Ollama is "good enough" vs. when to pay for cloud.

## Steps

1. Get the prompt from the user.
2. Determine participants:
   - Default: local (top installed Ollama model) + any cloud model whose API key is set
   - User can specify: "gpt-4o vs llama3.1:8b"
3. For each model in parallel:
   - Record start time
   - Call with the prompt
   - Record end time, length, approximate token count
4. Assemble a comparison table:
   ```
   MODEL             LATENCY   TOKENS   LENGTH
   llama3.1:8b       1.8 s     412      384 words
   gpt-4o            3.2 s     408      371 words
   claude-4.6        2.7 s     395      358 words
   ```
5. Print each response full-text, clearly labeled.
6. Provide a one-line observation about the tradeoff — don't pick a winner, surface the dimensions.

## Guardrails

- If any model fails, continue with the rest and report the failure inline.
- Strip chain-of-thought markers from reasoning models so comparisons are apples-to-apples.
- Respect the user's offline-mode setting — if offline, skip cloud models rather than erroring.
