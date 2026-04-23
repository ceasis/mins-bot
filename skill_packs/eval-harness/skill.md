---
name: eval-harness
description: Run a quick prompt-eval harness — a jsonl file of input/expected pairs, pass them through a model, and report pass rate + failing cases. Trigger on "run my eval set", "evaluate this prompt", "eval harness", "test my prompt on examples", "regression test my prompt".
metadata:
  minsbot:
    emoji: "🧪"
    os: ["windows", "darwin", "linux"]
---

# Prompt Eval Harness

## Steps

1. Get:
   - The prompt (inline or file)
   - The eval set as a JSONL file where each line is `{"input": "...", "expected": "..."}`. If the user has a CSV, offer to convert.
   - The model to use (default: local Ollama)
2. For each eval case:
   - Substitute `{input}` into the prompt
   - Call the model
   - Compare the response to `expected`:
     - Exact match → pass
     - Contains match (case-insensitive substring) → pass
     - Fuzzy match (≥ 0.85 similarity) → pass-with-note
     - Otherwise → fail
3. Produce the report:
   ```
   Evaluated 20 cases on llama3.1:8b
   ✓ 14 pass   ~ 3 pass-fuzzy   ✗ 3 fail   (avg 1.4s / case)

   Failing cases:
     [5] input: "..."
         expected: "..."
         got:      "..."
     ...
   ```
4. Save full results to `<eval_file>.results.json` for diff'ing across runs.

## Guardrails

- Cap run at 100 cases — beyond that, batch and offer to run overnight.
- Never modify the eval file — results are separate.
- If a case times out (model stalls), mark as fail with reason "timeout", not crash the whole run.
