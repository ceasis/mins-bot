---
name: csv-filter
description: Filter and project a CSV file — keep rows matching a condition, optionally keep only specific columns, save as a new file. Trigger on "filter this csv", "keep rows where X", "extract column Y from csv", "csv subset", "grep this csv".
metadata:
  minsbot:
    emoji: "🧮"
    os: ["windows", "darwin", "linux"]
---

# CSV Filter

## Steps

1. Resolve the source CSV path. Sniff delimiter (`,`, `;`, `\t`) from the first line.
2. Read the header row. Show the column list to the user if they haven't specified yet.
3. Parse the user's condition into a filter expression:
   - "rows where state = CA" → `state == "CA"`
   - "amount > 1000" → `amount > 1000`
   - "name contains smith" → `"smith" in name.lower()`
4. Parse the projection (column selection) if given — else keep all.
5. Stream-read the CSV (don't load to RAM), apply filter, write matches to `<source>_filtered.csv` alongside the original.
6. Report row count in / out, and size in / out.

## Guardrails

- Never overwrite the source file. Output is always a new file.
- Preserve the original header row and delimiter in the output.
- For files > 500 MB, warn before starting — operation may take minutes.
- If the condition references a column that doesn't exist, list the actual columns and stop.
