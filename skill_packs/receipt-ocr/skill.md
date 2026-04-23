---
name: receipt-ocr
description: OCR a receipt (image or PDF), extract merchant, date, line items, tax, total — then offer to log as an expense. Trigger on "read this receipt", "extract receipt", "log this receipt as an expense", "what does this receipt say", "itemize this receipt".
metadata:
  minsbot:
    emoji: "🧾"
    os: ["windows", "darwin", "linux"]
---

# Receipt OCR

Pairs the OCR tool with a focused LLM prompt that knows the shape of a receipt. Not perfect on thermal-printed receipts with 3mm text, but solid for photos + PDFs.

## Steps

1. Resolve the source. Accept a file path (image or PDF) or a clipboard image. Ask if none.
2. If PDF: extract the first page as a PNG. If image: use directly.
3. Call the OCR tool with preprocessing (`{binarize: true, deskew: true}`) — receipts benefit.
4. Send the raw OCR text to the local-model chat capability with this system prompt:
   ```
   Extract structured data from this receipt. Output STRICT JSON, nothing else:
   {
     "merchant": "<name>",
     "date": "<YYYY-MM-DD or null>",
     "currency": "<ISO code or null>",
     "items": [{"name": "...", "qty": N, "unit_price": N, "line_total": N}],
     "subtotal": N,
     "tax": N,
     "total": N,
     "payment_method": "<card|cash|gcash|... or null>"
   }
   Rules: numeric fields are JSON numbers, not strings. If a field is illegible, use null. Don't invent items — only include ones clearly listed.
   ```
5. Parse the JSON. If `total` is null or items is empty, warn — OCR quality was too low.
6. Present a clean summary: merchant, date, total, item count.
7. Ask "Log as expense? (yes/no)". On yes, **chain** into the expense-log skill: call `invokeSkillPack("expense-log")` and follow its returned instructions with the merchant, total, and an inferred category (groceries if items look like food, transport if fuel, etc.).

## Guardrails

- Never silently save JSON that has nulls in key fields — surface the gap and let the user correct.
- Redact any card number or last-4-digits string the OCR picks up before logging.
- If the receipt's total doesn't match the sum of line items (within 5%), flag it — possibly missed an item.
