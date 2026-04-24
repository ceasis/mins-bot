# Mins Bot — Next-Round Roadmap

Captured 2026-04-25 after the code-gen-as-software-team build session.

## Finish what's half-built

- [ ] **1. Async chat for long tool runs.**
      Return a `jobId` from `continueProject` / `testAndAutoFix` / any tool that can exceed ~20s, stream progress through the existing `CodeGenJobService` + SSE pattern already used on the Code page, and free the main loop so follow-up chat turns don't queue. Fast-lane covers trivial queries around a long run; this removes the block for real follow-ups too.

- [ ] **2. Live log tail viewer on the Code page.**
      Add `GET /api/code/logs/{project}/stream` (SSE) that tails `run.log` / `run.err`. New UI panel next to Recent Projects with a device-style thumbnail grid. Symmetric with `/api/code/screenshots/...` already live. Removes the "did it actually boot?" chat round-trip.

- [ ] **3. `spring-boot-auth` template.**
      Spring Security + login page + protected `/dashboard` out of the box. Gives `testLoginFlow` a real target in the template set (currently none of the 5 templates ship with auth).

## Reliability & observability

- [ ] **4. Cost tracker for Claude CLI + vision API spend.**
      `TokenUsageService` already tracks LLM spend — extend it to count `claude -p` invocations (time-based estimate) and `VisionService.analyzeWithPrompt` bytes. Surface on the existing Costs tab so nightly spend is visible in one place.

- [ ] **5. Recent-projects status pill.**
      On the Code page, per-row indicator: `● running :8080` / `○ stopped` / `? unknown`. One-line check: is the recorded port bound? Catches zombie `mvn spring-boot:run` processes you forgot about.

- [ ] **6. Server-side transcript replay on page reload.**
      Persist live status messages (`notifier.notify`) per session for ~5 minutes. On WebView refresh during a long run, replay the accumulated stream so progress isn't lost. Addresses the "SSE dropped → reply disappeared" class of bug we hit repeatedly.

## Polish

- [ ] **7. Auto-load most recent QA screenshot run on Code page.**
      The current UI requires typing the project name. Default to the most recent `visualReview` run on page load; keep the input for switching projects.

- [ ] **8. Generate `/tools.html` cheat sheet from `@Tool` annotations.**
      Reflective scan at startup — build a single static page listing every `@Tool` method with its description and a sample phrase. Link it from the Code page footer. Muscle-memory the surface area before forgetting what the bot can do.

- [ ] **9. Decide Experimental-tab fate within a week.**
      The four previously-hidden tabs (Workflows · Templates · Multi-Agent · Automations) now surface under **Experimental — might not be needed**. By May 2, either promote to Advanced or delete entirely. Don't let Experimental become permanent limbo.

## Validation (higher priority than more features)

- [ ] **10. END-TO-END DRY RUN of the critical path.**
      Pick a real project and run the full battery without adding anything new:
      1. `scaffoldAndLaunch("react-vite", "e2e-test", ...)` — full loop.
      2. `buildAndLaunch` auto-fix — verify the retry loop works with a real failure (introduce one).
      3. `visualReview` across `desktop, tablet, mobile`.
      4. `snapshotBaseline` → intentional UI edit → `visualReview` again — verify pixel-diff skipping works.
      5. `accessibilityAudit` — verify axe-core injection against a non-trivial page.
      6. `networkTrace` — verify 4xx/5xx detection by hitting a deliberately broken endpoint.
      7. `testFormsSmoke` — template needs to have a form; may require the `spring-boot-auth` template from #3 first.
      8. `testLoginFlow` — same.
      9. `generateE2eTestScript` → run `npx playwright test` standalone → confirm it passes.

      At least one of the five chains will have a subtle bug we haven't hit. Finding those beats building a sixth surface area.

- [ ] **11. Verify-bar for every item above.**
      Each item is only "done" when it has one observable demonstration: a log line, a screenshot in chat, or a file on disk that proves it worked end-to-end. No merge-to-main-after-compile claims.

---

## Not on this list (intentionally)

- More templates beyond `spring-boot-auth`. We have five; adding a sixth doesn't move the needle until validation (#10) exposes what the current five actually do wrong.
- More QA tools beyond what's built. The battery is comprehensive; gaps will show in #10.
- UI polish passes on the Code page beyond #7. Same reason.

## Principle for this round

Stop adding surface area. Finish, verify, and trim. Anything that lands on this list must have a concrete failure-mode it prevents, a concrete observable-done test, and a week-by reality check.
