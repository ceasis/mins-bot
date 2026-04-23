---
name: csv-stats
description: Produce a column-level summary of a CSV — type, null count, unique count, min/max, mean/median for numerics, top 3 values for categoricals. Trigger on "summarize this csv", "csv stats", "describe csv", "column stats", "what's in this csv".
metadata:
  minsbot:
    emoji: "📊"
    os: ["windows", "darwin", "linux"]
---

# CSV Stats

The pandas `.describe()` experience without having to open Python.

## Steps

1. Resolve path + sniff delimiter.
2. Stream-read to detect column types. Rules:
   - All values parseable as int/float → numeric
   - All values match a date pattern → date
   - Few distinct values (< 20 or < 5%) → categorical
   - Else → free text
3. For each column, compute:
   - **Numeric**: count, null%, min, max, mean, median, stdev, 95th percentile
   - **Date**: count, null%, earliest, latest, span in days
   - **Categorical**: count, null%, unique, top 3 by frequency
   - **Text**: count, null%, average length, 95th-percentile length
4. Print a tidy table, one row per column.
5. Flag concerning things at the bottom:
   - Columns with > 30% nulls
   - Numeric columns with suspicious outliers (value > 5× stdev from mean)
   - Suspected date columns that parsed as text

## Guardrails

- Don't load the whole file into memory. Stream + running counters.
- Cap at 2 passes through the file — one to detect types, one to compute.
- For files > 1 GB, sample the first 100k rows and clearly label the output "estimated from 100k sample".
