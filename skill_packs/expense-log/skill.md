---
name: expense-log
description: Log an expense to a running spreadsheet — category, amount, date, note. Trigger on "log expense", "spent X on Y", "add expense", "paid Z pesos for lunch", "record a purchase".
metadata:
  minsbot:
    emoji: "💸"
    os: ["windows", "darwin", "linux"]
---

# Expense Log

Pure-local spreadsheet append. No cloud, no API, no bank scraping — just a clean record you own.

## Steps

1. Parse the user's line for:
   - Amount + currency (default to user's configured currency, typically PHP on Mins Bot's default profile)
   - Category — infer from keywords (food/groceries/transport/bills/entertainment/health/other). If unclear, ask.
   - Merchant / counterparty (if mentioned)
   - Date — default today; accept "yesterday" / "last Friday" etc.
2. Open the expense sheet at `~/mins_bot_data/expenses.xlsx`. Create it if missing with columns: `Date | Category | Amount | Currency | Merchant | Note | AddedAt`.
3. Append a row.
4. Compute and report this month's total for the category after insert: "Logged ₱250 groceries. Month-to-date groceries: ₱4,820."

## Guardrails

- Never auto-categorize as "other" when a better category exists — ask.
- Confirm large amounts (> 5× the user's median expense) before logging, in case of typo.
- If the same amount + same merchant was logged in the last 30 minutes, ask "looks like a duplicate — log anyway?".
- Never edit existing rows — only append. Corrections = a new row with negative amount + note "correction for <date>".
