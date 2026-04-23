---
name: react-component-gen
description: Scaffold a new React/TypeScript component with the project's existing conventions (file layout, styling approach, test file). Trigger on "new react component", "scaffold a component called X", "create a <Name> component", "make me a tsx component".
metadata:
  minsbot:
    emoji: "⚛️"
    os: ["windows", "darwin", "linux"]
---

# React Component Generator

Infers conventions from the existing codebase — don't impose a template the project doesn't use.

## Steps

1. Find project root. Look for `src/components/` or similar. If ambiguous, ask where components live.
2. Sample 2–3 existing component files to detect:
   - File style: single `.tsx` or folder-with-index?
   - Styling: CSS modules (`*.module.css`), Tailwind classes, styled-components, or plain CSS?
   - Tests: Vitest / Jest / none, located alongside or in a `__tests__` dir?
   - Import conventions: default export or named?
3. Ask the user for the component name (PascalCase) and any props they know up front.
4. Generate the file(s) following detected conventions. Include:
   - Typed props interface
   - Sensible default render (`<div className="..."><h2>Name</h2></div>`)
   - Export matching project style
   - If tests are standard, a minimal test file with one smoke test
5. Print file paths. Offer to open them.

## Guardrails

- Never overwrite an existing file with the same name — rename with a suffix or ask.
- Match the project's import style (single vs default quotes, semicolons vs not) — detect from existing files.
- Don't add dependencies the project doesn't already have (no "let me install react-query for you").
