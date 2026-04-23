---
name: csv-dedupe
description: Remove duplicate rows from a CSV, either by exact row match or by a subset of key columns. Trigger on "dedupe csv", "remove duplicates", "unique rows by X", "csv dedup", "remove dupe rows".
metadata:
  minsbot:
    emoji: "♻️"
    os: ["windows", "darwin", "linux"]
---

# CSV Dedupe

## Steps

1. Resolve source CSV + sniff delimiter.
2. Ask the user for dedupe key:
   - "exact row" (default) — all columns
   - "by email" — single key column
   - "by first_name + last_name" — composite key
3. Stream-read the CSV. Track seen keys in a set. On collision, drop the newer row. Keep the first occurrence.
4. Write to `<source>_deduped.csv`. Also write `<source>_duplicates.csv` with the dropped rows so nothing's lost.
5. Report: "Read N rows. Kept M unique, dropped K duplicates. Dedupe key: <key>."

## Guardrails

- Never overwrite the source.
- Always produce the duplicates file — user may want to audit what was dropped.
- Case sensitivity defaults to case-sensitive. Ask the user if they want case-insensitive (common for email).
- Trim whitespace on key columns before comparison — "foo " and "foo" are same.
