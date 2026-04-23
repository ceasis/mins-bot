---
name: embedding-search
description: Index a folder of documents and let the user search them by meaning (not keyword). Uses a local embedding model so it runs offline. Trigger on "search my notes for X", "find my docs about Y", "semantic search", "index this folder", "where did I write about Z".
metadata:
  minsbot:
    emoji: "🧭"
    os: ["windows", "darwin", "linux"]
---

# Embedding Search

## Steps

1. Resolve the target folder. Default: `~/Documents` or the user's notes folder if configured.
2. Check for an existing index at `<folder>/.minsbot_index.sqlite`. If missing or stale (any file modified after index time), rebuild:
   - Walk the folder for `.md .txt .pdf .docx` files
   - Chunk each file into ~500-token overlapping chunks
   - Embed each chunk with the local embedding model (`nomic-embed-text` or `mxbai-embed-large` — use whichever is installed via Ollama)
   - Store (path, chunk, vector) in the sqlite index
3. For the user's query:
   - Embed the query with the same model
   - Cosine-similarity against all chunks
   - Return top 8 hits with file path, line range, and the chunk text
4. For each hit, show a 1-line snippet highlighting why it matched.

## Guardrails

- Skip files > 5 MB and binary files. Log what was skipped.
- If no embedding model is installed locally, tell the user to install one via the Models tab. Don't silently call a cloud embedding API.
- Rebuilding the index can take minutes on large folders — show progress and offer to run in the background.
- Never send chunks off-machine for embedding in this skill — defeats the "offline" premise.
