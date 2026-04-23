---
name: image-resize
description: Batch-resize images in a folder for web (max-width + quality), producing a /resized subfolder without overwriting originals. Trigger on "resize these images", "shrink images for web", "batch resize", "compress my photos for email", "make images smaller".
metadata:
  minsbot:
    emoji: "🖼️"
    os: ["windows", "darwin", "linux"]
    requires:
      bins: ["ffmpeg"]
    install:
      - id: winget-ffmpeg
        kind: winget
        package: Gyan.FFmpeg
        bins: ["ffmpeg"]
        label: Install FFmpeg (winget)
      - id: brew-ffmpeg
        kind: brew
        formula: ffmpeg
        bins: ["ffmpeg"]
        label: Install FFmpeg (brew)
---

# Image Resize

Uses ffmpeg (which handles JPEG/PNG/WebP natively). Non-destructive — originals untouched.

## Steps

1. Resolve target folder. If ambiguous, ask. Default = current Downloads folder.
2. Parse user constraints:
   - `--max-width` default 1920 (good for web)
   - `--quality` default 82 (invisible-loss for most photos)
   - `--format` keep original unless user says convert to webp/jpg
3. List image files in the folder (`.jpg .jpeg .png .webp .heic`).
4. Show a plan: "N images found. Output → <folder>/resized/. Proceed?"
5. On confirmation, create `resized/` subfolder and for each image run:
   ```
   ffmpeg -i <src> -vf "scale='min(<W>,iw)':-2" -q:v <quality>  <resized>/<name>
   ```
6. Report total bytes before vs after and average compression ratio.

## Guardrails

- Never overwrite originals — always output to a `resized/` subfolder.
- Skip files already smaller than max-width (no upscaling).
- For HEIC on Windows, ffmpeg may need extra codecs — if decode fails, surface the filename and continue with the rest.
