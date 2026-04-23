---
name: vite-scaffold
description: Scaffold a new Vite + React (or Vue, Svelte) project with Tailwind, ESLint, and Prettier pre-wired. Trigger on "new vite project", "scaffold a react app", "start a frontend project", "vite react tailwind".
metadata:
  minsbot:
    emoji: "⚡"
    os: ["windows", "darwin", "linux"]
    requires:
      bins: ["node", "npm"]
---

# Vite Scaffold

Opinionated starter — the stack you probably wanted anyway.

## Steps

1. Ask the user for:
   - Project name (kebab-case)
   - Framework: react (default) / vue / svelte
   - Tailwind? (default yes)
   - Target directory (default current)
2. Run `npm create vite@latest <name> -- --template <framework>-ts`.
3. `cd <name>` and `npm install`.
4. If Tailwind selected: `npm install -D tailwindcss postcss autoprefixer && npx tailwindcss init -p`. Generate a minimal `tailwind.config.js` with `content: ["./index.html", "./src/**/*.{ts,tsx,vue,svelte}"]`. Add `@tailwind base; @tailwind components; @tailwind utilities;` to the main CSS file.
5. Install ESLint + Prettier: `npm install -D eslint prettier eslint-config-prettier`. Write a minimal `.prettierrc` and `eslint.config.js`.
6. Initialize git: `git init && git add . && git commit -m "chore: initial vite scaffold"`.
7. Report the project path and next command: `cd <name> && npm run dev`.

## Guardrails

- Never overwrite an existing directory with the same name — ask.
- Pin Node-version requirement in `package.json` `engines` field.
- If npm auth fails (offline), surface that clearly so the user knows it's a network issue.
