---
name: excel-pivot
description: Build a quick pivot table from a spreadsheet — group by a column, sum/count/avg another — and write the result to a new sheet. Trigger on "pivot this", "group by X and sum Y", "summary by column", "pivot table please", "roll up by X".
metadata:
  minsbot:
    emoji: "📑"
    os: ["windows", "darwin", "linux"]
---

# Excel Pivot

## Steps

1. Resolve spreadsheet path. List sheet names if there's more than one; confirm which sheet to pivot.
2. Read the header row. Ask the user:
   - Group-by column (the row axis)
   - Value column (what to aggregate)
   - Aggregation: sum / count / avg / min / max. Default = sum for numerics, count for categoricals.
3. Build the aggregation in-memory (Apache POI or similar via existing Excel tools).
4. Write a new sheet in the same file called `pivot_<groupby>_<agg>_<value>`. Include:
   - Row for each distinct group-by value
   - Aggregated column
   - A TOTAL row at the bottom
5. Report the new sheet name and the top 5 rows.

## Guardrails

- Don't mutate the source sheet. New sheet only.
- If the value column has non-numeric values when sum/avg is requested, coerce nulls but flag any non-numeric strings.
- Cap at 100k rows — beyond that, Excel itself will struggle; recommend csv-stats + DuckDB instead.
