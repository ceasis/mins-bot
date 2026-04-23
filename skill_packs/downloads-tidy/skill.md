---
name: downloads-tidy
description: Organize the Windows Downloads folder by type and age — move images to Pictures/<month>, PDFs and docs to Documents/<month>, videos to Videos/<month>, installers older than 30 days to Archive, keep the rest. Trigger on "tidy downloads", "clean my downloads", "organize downloads folder", "my downloads is a mess".
metadata:
  minsbot:
    emoji: "🧹"
    os: ["windows", "darwin", "linux"]
---

# Downloads Tidy

A dry-run-first file mover. Never destructive — moves only, never deletes.

## Steps

1. Resolve the Downloads folder: `%USERPROFILE%\Downloads` on Windows, `~/Downloads` on Unix.
2. Call `listDirectory` to enumerate it. For each file, capture extension, size, modified date.
3. Categorize:
   - `.png .jpg .jpeg .gif .webp .heic` → `~/Pictures/Downloads/<YYYY-MM>/`
   - `.pdf .docx .xlsx .pptx .odt .rtf` → `~/Documents/Downloads/<YYYY-MM>/`
   - `.mp4 .mkv .mov .avi .webm` → `~/Videos/Downloads/<YYYY-MM>/`
   - `.zip .7z .rar .tar.gz` older than 14 days → `~/Downloads/_archive/<YYYY-MM>/`
   - `.exe .msi .dmg .pkg` older than 30 days → `~/Downloads/_archive/installers/<YYYY-MM>/`
   - Everything else → leave alone.
4. **Dry-run first.** Show the user the plan as a table: `N files → destination, M files → destination`. Include the largest 5 items explicitly ("bigfile.zip (2.3 GB) → archive").
5. Ask the user to confirm.
6. On yes, call `movePath` for each. Do not overwrite — if the destination exists, append `-1`, `-2` to the filename.
7. Summarize: "Moved X files across Y categories. Y GB of installers archived."

## Guardrails

- **Dry-run always.** Never move without showing the plan first.
- Never touch `.crdownload` / `.part` / `.tmp` — those are in-flight.
- Never move files modified in the last 2 hours — the user may still be using them.
- If any move fails, continue with the rest and report the failure count at the end.
