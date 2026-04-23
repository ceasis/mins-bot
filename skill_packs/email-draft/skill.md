---
name: email-draft
description: Draft a professional email from a goal — includes two subject line options, the body, and a reminder of what to double-check before sending. Trigger on "draft an email to X", "write an email about Y", "email to pitch Z", "follow-up email", "cold email draft".
metadata:
  minsbot:
    emoji: "✉️"
    os: ["windows", "darwin", "linux"]
---

# Email Draft

## Steps

1. Ask (if unclear):
   - Recipient name + relationship (cold / warm / ongoing)
   - Goal — one sentence, the thing the email is trying to make happen
   - Anything they already know that you don't need to re-explain
2. Run this prompt:
   ```
   Draft an email:
   TWO subject line options:
   1. Direct ("Intro: [your name] × [their company]")
   2. Curious ("Quick question about [thing that matters to them]")

   BODY:
   - Opener: 1 line, shows you did a tiny bit of homework
   - Context: 1–2 lines, your why
   - Ask: 1 line, crystal clear ONE ask
   - Close: 1 line, warm but not saccharine

   Constraints: under 120 words total. First person. Specific > clever. No "I hope this email finds you well".
   ```
3. At the end, list 2–3 pre-send checks:
   - "Did you replace [their company]?"
   - "Is the ask specific enough they could say yes/no in 10 seconds?"
   - "Did you attach [thing] if you promised to?"

## Guardrails

- Never invent facts about the recipient or their company.
- Don't use "I hope you're well", "I wanted to reach out", or "circle back" — filler that dilutes signal.
- If the goal is vague, push back — "what specifically do you want them to do?"
