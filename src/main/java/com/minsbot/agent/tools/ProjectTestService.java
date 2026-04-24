package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA pipeline for a launched project. Does three independent checks:
 *
 * <ol>
 *   <li><b>API endpoint sweep</b> — statically scans controller sources for
 *       {@code @GetMapping / @PostMapping / @RequestMapping} paths and
 *       performs real HTTP GETs against each one. Reports status codes and
 *       response sizes.</li>
 *   <li><b>Browser UI probe</b> — opens the root URL in Playwright, collects
 *       console errors and uncaught page exceptions during load + settle
 *       window.</li>
 *   <li><b>App log scan</b> — tails {@code run.log / run.err} for ERROR /
 *       WARN / Exception / Caused by lines that appeared during the session.</li>
 * </ol>
 *
 * <p>Everything is best-effort — a missing tool (Playwright, etc.) degrades
 * that section to a skip rather than failing the whole report.</p>
 */
@Component
public class ProjectTestService {

    private static final Logger log = LoggerFactory.getLogger(ProjectTestService.class);

    private final ProjectHistoryService history;
    private final ToolExecutionNotifier notifier;
    private final PlaywrightService playwright;
    private final com.minsbot.agent.VisionService vision;
    private final ClaudeCodeTools claudeCodeTools;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ProjectTestService(ProjectHistoryService history,
                              ToolExecutionNotifier notifier,
                              PlaywrightService playwright,
                              com.minsbot.agent.VisionService vision,
                              ClaudeCodeTools claudeCodeTools) {
        this.history = history;
        this.notifier = notifier;
        this.playwright = playwright;
        this.vision = vision;
        this.claudeCodeTools = claudeCodeTools;
    }

    // Path to the per-project baseline shot folder.
    private Path baselineDir(String projectName) {
        return Path.of(System.getProperty("user.home"), "mins_bot_data",
                "qa-baselines", safe(projectName));
    }

    @Tool(description = "Accessibility audit: inject axe-core and report WCAG violations per URL. "
            + "Catches missing labels, insufficient contrast, missing ARIA, etc. — things code-level "
            + "tests and vision review miss. Requires internet (axe loaded from CDN).")
    public String accessibilityAudit(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Base URL, e.g. http://localhost:8080") String baseUrl,
            @ToolParam(description = "Comma-separated paths (empty = '/')") String pathsCsv) {
        String cleanBase = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        List<String> paths = splitCsv(pathsCsv);
        if (paths.isEmpty()) paths.add("/");
        StringBuilder sb = new StringBuilder();
        sb.append("── accessibility audit for '").append(projectName).append("' ──\n");
        int totalCritical = 0, totalSerious = 0;
        for (String p : paths) {
            String url = cleanBase + (p.startsWith("/") ? p : "/" + p);
            notifier.notify("♿ a11y audit → " + p);
            String report = playwright.accessibilityAudit(url, 3);
            sb.append(p).append(":\n");
            for (String line : report.split("\\R")) sb.append("  ").append(line).append('\n');
            totalCritical += countOccurrences(report, "\"impact\":\"critical\"");
            totalSerious  += countOccurrences(report, "\"impact\":\"serious\"");
            sb.append('\n');
        }
        sb.append("── summary ── critical=").append(totalCritical)
          .append(" serious=").append(totalSerious);
        return sb.toString();
    }

    @Tool(description = "Network trace: open each URL in a browser and list every HTTP request made "
            + "during page load. Flags 4xx/5xx and failed requests. Catches 'page looks fine but a "
            + "silent XHR returned 500' bugs that visual review misses.")
    public String networkTrace(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Base URL") String baseUrl,
            @ToolParam(description = "Comma-separated paths (empty = '/')") String pathsCsv) {
        String cleanBase = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        List<String> paths = splitCsv(pathsCsv);
        if (paths.isEmpty()) paths.add("/");
        StringBuilder sb = new StringBuilder();
        sb.append("── network trace for '").append(projectName).append("' ──\n");
        for (String p : paths) {
            String url = cleanBase + (p.startsWith("/") ? p : "/" + p);
            notifier.notify("🔌 network trace → " + p);
            String report = playwright.networkTrace(url, 3);
            sb.append(p).append(":\n");
            for (String line : report.split("\\R")) sb.append("  ").append(line).append('\n');
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Tool(description = "Form smoke test: find every <form> on each page, fill with dummy data, submit, "
            + "and report whether the post-submit page looks broken (Whitelabel, exceptions, 500). "
            + "Covers the CRUD surface of a scaffolded app without hand-written tests.")
    public String testFormsSmoke(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Base URL") String baseUrl,
            @ToolParam(description = "Comma-separated paths that contain forms (empty = '/')") String pathsCsv) {
        String cleanBase = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        List<String> paths = splitCsv(pathsCsv);
        if (paths.isEmpty()) paths.add("/");
        StringBuilder sb = new StringBuilder();
        sb.append("── form smoke for '").append(projectName).append("' ──\n");
        for (String p : paths) {
            String url = cleanBase + (p.startsWith("/") ? p : "/" + p);
            notifier.notify("📝 form smoke → " + p);
            String report = playwright.smokeTestForms(url, 2);
            sb.append(p).append(":\n");
            for (String line : report.split("\\R")) sb.append("  ").append(line).append('\n');
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Tool(description = "Snapshot baseline visual state for a project — screenshots every path on every "
            + "device and stores them as the 'known good'. Future visualReview calls diff against this "
            + "baseline and only invoke AI vision for pages that actually changed pixels. Use after the "
            + "first clean run of a project or after intentional UI edits.")
    public String snapshotBaseline(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Base URL") String baseUrl,
            @ToolParam(description = "Comma-separated paths (empty = '/')") String pathsCsv,
            @ToolParam(description = "Devices to snapshot (empty = desktop,tablet,mobile)") String devicesCsv) {
        try {
            String cleanBase = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
            List<String> paths = splitCsv(pathsCsv);
            if (paths.isEmpty()) paths.add("/");
            List<String> devs = splitCsv(devicesCsv);
            if (devs.isEmpty()) devs = List.of("desktop", "tablet", "mobile");
            Path baseDir = baselineDir(projectName);
            Files.createDirectories(baseDir);
            int saved = 0;
            for (String p : paths) {
                String url = cleanBase + (p.startsWith("/") ? p : "/" + p);
                for (String d : devs) {
                    int[] vp = PlaywrightService.DEVICE_VIEWPORTS.get(d.trim().toLowerCase());
                    if (vp == null) continue;
                    notifier.notify("📸 baseline " + d + " → " + p);
                    byte[] png = playwright.screenshotAtViewport(url, vp[0], vp[1], 3);
                    if (png == null) continue;
                    Path file = baseDir.resolve(d).resolve(
                            (p.equals("/") ? "root" : p.replaceAll("[^a-zA-Z0-9_-]", "_")) + ".png");
                    Files.createDirectories(file.getParent());
                    Files.write(file, png);
                    saved++;
                }
            }
            return "✓ Baseline saved: " + saved + " screenshot(s) at " + baseDir;
        } catch (Exception e) {
            return "snapshotBaseline error: " + e.getMessage();
        }
    }

    @Tool(description = "Capture screenshots of the app's UI at multiple device sizes and have AI vision "
            + "review each one for layout problems, overflow, clipping, contrast issues, missing content, "
            + "and design red flags that code-level tests can't catch. Saves all screenshots to "
            + "~/mins_bot_data/qa-screenshots/<project>/<timestamp>/<device>/<page>.png so the user can "
            + "inspect them too. Use for 'screenshot every page', 'do a visual check', 'test responsive design'.")
    public String visualReview(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Base URL, e.g. http://localhost:8080") String baseUrl,
            @ToolParam(description = "Comma-separated paths to review (empty = '/'). Example: '/,/login,/dashboard'") String pathsCsv,
            @ToolParam(description = "Comma-separated device profiles to test across. Known: "
                    + "desktop-fhd,desktop,laptop,tablet,tablet-lg,mobile,mobile-lg,mobile-sm. "
                    + "Empty = 'desktop,tablet,mobile'.") String devicesCsv) {
        try {
            if (baseUrl == null || baseUrl.isBlank()) return "Error: baseUrl is required.";
            String cleanBase = baseUrl.replaceAll("/+$", "");
            List<String> paths = splitCsv(pathsCsv);
            if (paths.isEmpty()) paths.add("/");
            List<String> deviceKeys = splitCsv(devicesCsv);
            if (deviceKeys.isEmpty()) deviceKeys = List.of("desktop", "tablet", "mobile");

            Path outRoot = Path.of(System.getProperty("user.home"), "mins_bot_data", "qa-screenshots",
                    safe(projectName),
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.createDirectories(outRoot);

            StringBuilder report = new StringBuilder();
            report.append("── visual review for '").append(projectName).append("' ──\n");
            report.append("base: ").append(cleanBase).append("\n");
            report.append("screenshots: ").append(outRoot).append("\n\n");

            int totalShots = 0, totalIssues = 0, majorIssues = 0;
            for (String path : paths) {
                String fullUrl = cleanBase + (path.startsWith("/") ? path : "/" + path);
                report.append("── ").append(path).append(" ──\n");
                for (String dev : deviceKeys) {
                    int[] viewport = PlaywrightService.DEVICE_VIEWPORTS.get(dev.trim().toLowerCase());
                    if (viewport == null) {
                        report.append("  ? unknown device '").append(dev).append("' — skipping\n");
                        continue;
                    }
                    notifier.notify("📸 " + dev + " (" + viewport[0] + "x" + viewport[1] + ") → " + path);
                    byte[] png = playwright.screenshotAtViewport(fullUrl, viewport[0], viewport[1], 3);
                    if (png == null || png.length == 0) {
                        report.append("  ✗ ").append(dev).append(" — screenshot failed\n");
                        continue;
                    }
                    totalShots++;
                    // Save to disk.
                    Path devDir = outRoot.resolve(dev);
                    Files.createDirectories(devDir);
                    String safeName = path.equals("/") ? "root" : path.replaceAll("[^a-zA-Z0-9_-]", "_");
                    Path shot = devDir.resolve(safeName + ".png");
                    Files.write(shot, png);

                    // Baseline short-circuit: if we have a baseline for this path+device and
                    // the bytes are identical, skip the expensive vision call.
                    Path baseFile = baselineDir(projectName).resolve(dev).resolve(safeName + ".png");
                    if (Files.exists(baseFile)) {
                        try {
                            byte[] base = Files.readAllBytes(baseFile);
                            if (java.util.Arrays.equals(base, png)) {
                                report.append("  ✓ ").append(dev)
                                      .append(" (").append(viewport[0]).append("x").append(viewport[1]).append(")")
                                      .append(" — identical to baseline (vision skipped)\n");
                                continue;
                            }
                        } catch (Exception ignored) {}
                    }

                    // AI vision review.
                    String prompt = "You are reviewing a UI screenshot from a web app at path '" + path +
                            "' rendered at " + viewport[0] + "x" + viewport[1] + " (" + dev + "). " +
                            "List any UI/UX issues concisely. Categorize each finding as MAJOR (broken layout, " +
                            "overlapping text, clipped content, unreadable contrast, missing critical CTA, " +
                            "obvious bugs) or MINOR (spacing, polish, small inconsistencies). " +
                            "Be specific — reference what you see. " +
                            "If the page looks clean, reply with exactly: OK — no issues.";
                    String review = "(no vision)";
                    try { review = vision.analyzeWithPrompt(png, prompt); } catch (Exception ignored) {}
                    if (review == null || review.isBlank()) review = "(vision returned empty)";
                    int majors = countOccurrences(review, "MAJOR");
                    int minors = countOccurrences(review, "MINOR");
                    totalIssues += majors + minors;
                    majorIssues += majors;
                    String mark = review.toLowerCase().contains("ok — no issues") && majors == 0 && minors == 0
                            ? "✓" : (majors > 0 ? "✗" : "⚠");
                    report.append("  ").append(mark).append(" ").append(dev)
                          .append(" (").append(viewport[0]).append("x").append(viewport[1]).append(")")
                          .append(majors > 0 ? " — " + majors + " major" : "")
                          .append(minors > 0 ? ", " + minors + " minor" : "")
                          .append("\n    file: ").append(shot).append('\n');
                    if (!review.toLowerCase().contains("ok — no issues")) {
                        for (String line : review.split("\\R")) {
                            if (!line.isBlank()) report.append("    ").append(line).append('\n');
                        }
                    }
                }
                report.append('\n');
            }
            report.append("── summary ──\n");
            report.append("screenshots: ").append(totalShots).append('\n');
            report.append("issues:      ").append(totalIssues)
                  .append(" (").append(majorIssues).append(" major, ")
                  .append(totalIssues - majorIssues).append(" minor)\n");
            report.append(majorIssues > 0 ? "✗ MAJOR issues found — see details above." : "✓ No major issues.");
            return report.toString();
        } catch (Exception e) {
            return "visualReview error: " + e.getMessage();
        }
    }

    @Tool(description = "Run the full QA pipeline (API + UI console + log scan + optional visual review, "
            + "accessibility, network trace, form smoke) and, if any errors are found, feed them back to "
            + "Claude Code with instructions to fix. Retries up to maxAttempts times. Use for 'test and fix "
            + "my app', 'QA it and self-heal', 'run tests and repair any bugs'. Set includeVisual/a11y/"
            + "network/forms=true to include those (they're slower/paid). Returns the final QA report.")
    public String testAndAutoFix(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Port the app is running on") int port,
            @ToolParam(description = "Max fix attempts (1-5, default 2)") int maxAttempts,
            @ToolParam(description = "Include AI visual review (costs vision API calls)") boolean includeVisual,
            @ToolParam(description = "Include accessibility audit (axe-core)") boolean includeA11y,
            @ToolParam(description = "Include network trace (flags 4xx/5xx requests)") boolean includeNetwork,
            @ToolParam(description = "Include form smoke tests") boolean includeForms) {
        ProjectHistoryService.ProjectRecord rec = history.findByName(projectName);
        if (rec == null) return "Unknown project: " + projectName;
        int attempts = Math.max(1, Math.min(5, maxAttempts == 0 ? 2 : maxAttempts));
        String baseUrl = "http://localhost:" + port;
        StringBuilder log = new StringBuilder();
        String last = "";
        for (int i = 1; i <= attempts; i++) {
            notifier.notify("QA auto-fix attempt " + i + "/" + attempts + "...");
            StringBuilder combined = new StringBuilder();
            combined.append(testProject(projectName, port));
            if (includeNetwork) combined.append("\n\n").append(networkTrace(projectName, baseUrl, ""));
            if (includeA11y)    combined.append("\n\n").append(accessibilityAudit(projectName, baseUrl, ""));
            if (includeForms)   combined.append("\n\n").append(testFormsSmoke(projectName, baseUrl, ""));
            if (includeVisual)  combined.append("\n\n").append(visualReview(projectName, baseUrl, "", ""));
            last = combined.toString();
            log.append("── attempt ").append(i).append(" ──\n").append(last).append("\n\n");

            boolean anyFail = last.contains("✗ FAIL")
                    || last.contains("FAILING REQUESTS")
                    || (includeA11y && (last.contains("critical=") && !last.contains("critical=0"))
                                     || last.contains("serious=") && !last.contains("serious=0"))
                    || last.contains("MAJOR issues found")
                    || last.contains("(error page detected)");
            if (!anyFail) {
                log.append("✓ Clean QA on attempt ").append(i);
                return log.toString();
            }
            if (i == attempts) break;
            // Feed the combined findings to Claude for an auto-fix pass.
            String fixPrompt = "The QA pipeline reports failures on this running project. Read the full " +
                    "report below and make the MINIMAL code changes needed to resolve each issue. " +
                    "Address ALL of: console/page errors, HTTP 4xx/5xx, exceptions in logs, accessibility " +
                    "violations, form submission errors, and MAJOR visual issues. Do NOT rewrite unrelated " +
                    "files. Do NOT punt with a summary. Edit existing files.\n\n── QA report ──\n" + last;
            claudeCodeTools.run(fixPrompt, rec.workingDir, false, false);
        }
        log.append("✗ QA still failing after ").append(attempts).append(" attempt(s).");
        return log.toString();
    }

    @Tool(description = "Write a Playwright e2e test script (tests/e2e.spec.ts) into a project so the user "
            + "can run `npx playwright test` from the CLI. Auto-populates with the URLs and login flow "
            + "matching the project. Use for 'add playwright tests to X', 'generate e2e tests for Y'.")
    public String generateE2eTestScript(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Base URL the app runs on (e.g. http://localhost:8080)") String baseUrl,
            @ToolParam(description = "Comma-separated paths to smoke-test (empty = '/')") String pathsCsv,
            @ToolParam(description = "Login URL (empty = no login step)") String loginUrl,
            @ToolParam(description = "Username to enter at login (empty = no login)") String username,
            @ToolParam(description = "Password to enter at login (empty = no login)") String password) {
        try {
            ProjectHistoryService.ProjectRecord rec = history.findByName(projectName);
            if (rec == null) return "Unknown project: " + projectName;
            Path dir = Path.of(rec.workingDir);
            if (!Files.isDirectory(dir)) return "Folder missing: " + dir;
            String cleanBase = baseUrl == null ? "http://localhost:8080" : baseUrl.replaceAll("/+$", "");
            List<String> paths = splitCsv(pathsCsv);
            if (paths.isEmpty()) paths.add("/");
            boolean withLogin = loginUrl != null && !loginUrl.isBlank()
                    && username != null && !username.isBlank();

            StringBuilder t = new StringBuilder();
            t.append("import { test, expect } from '@playwright/test';\n\n");
            t.append("const BASE = '").append(cleanBase).append("';\n\n");
            if (withLogin) {
                t.append("async function login(page) {\n");
                t.append("  await page.goto('").append(loginUrl).append("');\n");
                t.append("  await page.fill('input[name=username], input[name=email], input[type=email]', '")
                 .append(username).append("');\n");
                t.append("  await page.fill('input[type=password]', '").append(password).append("');\n");
                t.append("  await Promise.all([\n");
                t.append("    page.waitForLoadState('networkidle'),\n");
                t.append("    page.click('button[type=submit], input[type=submit]')\n");
                t.append("  ]);\n");
                t.append("  expect(page.url()).not.toContain('login');\n");
                t.append("}\n\n");
            }
            for (String p : paths) {
                String label = p.equals("/") ? "root" : p.replaceAll("[^a-zA-Z0-9]", "_");
                t.append("test('smoke ").append(label).append(" has no console errors', async ({ page }) => {\n");
                t.append("  const errors = [];\n");
                t.append("  page.on('console', msg => { if (msg.type() === 'error') errors.push(msg.text()); });\n");
                t.append("  page.on('pageerror', err => errors.push(String(err)));\n");
                if (withLogin) t.append("  await login(page);\n");
                t.append("  await page.goto(BASE + '").append(p).append("');\n");
                t.append("  await page.waitForLoadState('networkidle');\n");
                t.append("  await page.waitForTimeout(2000);\n");
                t.append("  expect(errors, errors.join('\\n')).toEqual([]);\n");
                t.append("});\n\n");
            }
            Path testsDir = dir.resolve("tests");
            Files.createDirectories(testsDir);
            Path target = testsDir.resolve("e2e.spec.ts");
            Files.writeString(target, t.toString(), StandardCharsets.UTF_8);

            StringBuilder sb = new StringBuilder();
            sb.append("✓ Wrote ").append(target).append("\n\n");
            sb.append("To run from the CLI:\n");
            sb.append("  npm init -y && npm install -D @playwright/test\n");
            sb.append("  npx playwright install chromium\n");
            sb.append("  npx playwright test\n");
            return sb.toString();
        } catch (Exception e) {
            return "generateE2eTestScript error: " + e.getMessage();
        }
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static int countOccurrences(String hay, String needle) {
        if (hay == null || needle == null || needle.isEmpty()) return 0;
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Tool(description = "Log in to a running app's UI and probe authenticated pages for console/page errors. "
            + "Auto-detects standard username+password form fields (username / email / user / login — password / pass) "
            + "and submit buttons. Use when the user says 'test the login flow', 'log into the app and check the "
            + "dashboard', 'test the authenticated pages'. Returns LOGIN OK/FAILED + per-URL error counts + full error list.")
    public String testLoginFlow(
            @ToolParam(description = "Project name (for history lookup)") String projectName,
            @ToolParam(description = "Full URL of the login page, e.g. http://localhost:8080/login") String loginUrl,
            @ToolParam(description = "Username or email to enter") String username,
            @ToolParam(description = "Password to enter") String password,
            @ToolParam(description = "Comma-separated list of URLs to visit AFTER login (e.g. "
                    + "'http://localhost:8080/dashboard,http://localhost:8080/profile'). Empty = just verify login.") String postLoginUrlsCsv) {
        try {
            if (loginUrl == null || loginUrl.isBlank()) return "Error: loginUrl is required.";
            if (username == null) username = "";
            if (password == null) password = "";
            List<String> urls = new ArrayList<>();
            if (postLoginUrlsCsv != null && !postLoginUrlsCsv.isBlank()) {
                for (String u : postLoginUrlsCsv.split(",")) {
                    String t = u.trim();
                    if (!t.isEmpty()) urls.add(t);
                }
            }
            notifier.notify("QA: logging in to " + loginUrl + " as '" + username + "'...");
            String report = playwright.loginAndProbe(loginUrl, username, password, urls, 3);
            // Tag the report with the project name for context.
            ProjectHistoryService.ProjectRecord rec = history.findByName(projectName);
            String projLine = rec != null ? "project: " + rec.projectName + "\n" : "";
            return projLine + report;
        } catch (Exception e) {
            return "Login test error: " + e.getMessage();
        }
    }

    @Tool(description = "Run the QA pipeline against a running project: hit every discovered "
            + "HTTP endpoint, open the UI in a headless browser and capture console + page errors, "
            + "and scan the app's run.log/run.err for error lines. Use after buildAndLaunch or "
            + "scaffoldAndLaunch to verify the app actually behaves. Returns a pass/fail report "
            + "with specific problems. Read-only — does not modify the project.")
    public String testProject(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Port the app is running on (e.g. 8080)") int port) {
        try {
            ProjectHistoryService.ProjectRecord rec = history.findByName(projectName);
            if (rec == null) return "Unknown project: " + projectName;
            Path dir = Path.of(rec.workingDir);
            if (!Files.isDirectory(dir)) return "Folder missing: " + dir;
            if (port <= 0 || port > 65535) return "Invalid port: " + port;

            StringBuilder report = new StringBuilder();
            report.append("── QA report for '").append(rec.projectName)
                  .append("' @ http://localhost:").append(port).append("/ ──\n\n");

            int fails = 0;

            // ─── API sweep ──────────────────────────────────────────
            notifier.notify("QA: sweeping HTTP endpoints...");
            List<String> endpoints = discoverEndpoints(dir);
            if (endpoints.isEmpty()) {
                report.append("API: (no endpoints discovered by static scan)\n");
            } else {
                report.append("API sweep (").append(endpoints.size()).append(" endpoints):\n");
                for (String path : endpoints) {
                    String result = probeEndpoint(port, path);
                    report.append("  ").append(result).append('\n');
                    if (result.contains(" 5") || result.contains(" 4") && !result.contains(" 404")) fails++;
                }
            }
            report.append('\n');

            // ─── UI probe ──────────────────────────────────────────
            notifier.notify("QA: opening UI in headless browser, capturing console errors...");
            String url = "http://localhost:" + port + "/";
            String consoleReport;
            try {
                consoleReport = playwright.probeConsoleErrors(url, 4);
            } catch (Exception e) {
                consoleReport = "UI probe skipped (Playwright not available: " + e.getMessage() + ")";
            }
            report.append("UI probe:\n  ").append(consoleReport.replace("\n", "\n  ")).append("\n\n");
            if (consoleReport.contains("CONSOLE ERRORS") || consoleReport.contains("PAGE ERRORS")
                    || consoleReport.startsWith("NAVIGATION FAILED")) fails++;

            // ─── App log scan ──────────────────────────────────────
            notifier.notify("QA: scanning app logs for errors...");
            String logReport = scanAppLogs(dir);
            report.append("App log scan:\n").append(logReport);
            if (logReport.contains("ERROR") || logReport.contains("Exception")
                    || logReport.contains("Caused by")) fails++;

            report.append('\n').append(fails == 0
                    ? "✓ PASS — no API, UI, or log errors detected."
                    : "✗ FAIL — " + fails + " problem category(ies) detected. See above.");
            return report.toString();
        } catch (Exception e) {
            return "Test error: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // API discovery + probe
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Walk the project source tree and collect URL paths declared on controllers.
     * Handles Spring ({@code @GetMapping / @RequestMapping}), Express ({@code app.get('/x')}),
     * FastAPI ({@code @app.get("/x")}).
     */
    private List<String> discoverEndpoints(Path dir) {
        Set<String> paths = new LinkedHashSet<>();
        try (var s = Files.walk(dir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> {
                 String n = p.getFileName().toString();
                 return n.endsWith(".java") || n.endsWith(".py") || n.endsWith(".js") || n.endsWith(".ts");
             })
             .filter(p -> !p.toString().contains("test") && !p.toString().contains("/target/")
                       && !p.toString().contains("\\target\\") && !p.toString().contains("node_modules"))
             .limit(200)
             .forEach(p -> extractPathsFromSource(p, paths));
        } catch (Exception e) {
            log.debug("[QA] discovery walk failed: {}", e.getMessage());
        }
        // Never probe paths with template variables — keep only concrete ones.
        List<String> concrete = new ArrayList<>();
        for (String p : paths) {
            if (p.contains("{") || p.contains("}") || p.contains(":")) continue;
            concrete.add(p);
        }
        // Always also probe the root.
        if (!concrete.contains("/")) concrete.add(0, "/");
        return concrete;
    }

    private static final Pattern SPRING_CLASS_MAPPING = Pattern.compile(
            "@RequestMapping\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern SPRING_METHOD_MAPPING = Pattern.compile(
            "@(?:GetMapping|RequestMapping)\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern FASTAPI_ROUTE = Pattern.compile(
            "@app\\.get\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern EXPRESS_ROUTE = Pattern.compile(
            "app\\.get\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    private void extractPathsFromSource(Path file, Set<String> out) {
        try {
            String body = Files.readString(file, StandardCharsets.UTF_8);
            String classBase = "";
            Matcher classM = SPRING_CLASS_MAPPING.matcher(body);
            if (classM.find()) classBase = classM.group(1);
            Matcher mm = SPRING_METHOD_MAPPING.matcher(body);
            while (mm.find()) out.add(joinPath(classBase, mm.group(1)));
            Matcher fm = FASTAPI_ROUTE.matcher(body);
            while (fm.find()) out.add(fm.group(1));
            Matcher em = EXPRESS_ROUTE.matcher(body);
            while (em.find()) out.add(em.group(1));
        } catch (Exception ignored) {}
    }

    private static String joinPath(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (!a.startsWith("/") && !a.isEmpty()) a = "/" + a;
        if (!b.startsWith("/") && !b.isEmpty()) b = "/" + b;
        String joined = a + b;
        if (joined.isEmpty()) joined = "/";
        return joined.replaceAll("/+", "/");
    }

    private String probeEndpoint(int port, String path) {
        try {
            String url = "http://localhost:" + port + path;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int bodyLen = resp.body() == null ? 0 : resp.body().length();
            String mark;
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) mark = "✓";
            else if (resp.statusCode() == 404) mark = "·";
            else mark = "✗";
            return mark + " " + path + " → HTTP " + resp.statusCode() + " (" + bodyLen + " bytes)";
        } catch (Exception e) {
            return "✗ " + path + " → connect error: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // App log scan
    // ═══════════════════════════════════════════════════════════════════

    private String scanAppLogs(Path dir) {
        StringBuilder sb = new StringBuilder();
        Path runLog = dir.resolve("run.log");
        Path runErr = dir.resolve("run.err");
        try {
            sb.append(scanOne(runLog, "run.log"));
            sb.append(scanOne(runErr, "run.err"));
        } catch (Exception e) {
            sb.append("  (log scan error: ").append(e.getMessage()).append(")\n");
        }
        if (sb.length() == 0) sb.append("  no log files found (run.log / run.err).\n");
        return sb.toString();
    }

    private String scanOne(Path file, String label) {
        if (!Files.exists(file)) return "";
        StringBuilder sb = new StringBuilder();
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> hits = new ArrayList<>();
            for (String line : all) {
                String lower = line.toLowerCase();
                if (line.contains("ERROR") || line.contains("Exception")
                        || line.startsWith("\tat ") || lower.startsWith("caused by")) {
                    hits.add(line);
                }
            }
            if (hits.isEmpty()) {
                sb.append("  ").append(label).append(": clean (").append(all.size()).append(" lines scanned)\n");
            } else {
                sb.append("  ").append(label).append(": ").append(hits.size()).append(" error/exception line(s):\n");
                int keep = Math.min(20, hits.size());
                for (int i = 0; i < keep; i++) sb.append("    ").append(hits.get(i)).append('\n');
                if (hits.size() > keep) sb.append("    ... (").append(hits.size() - keep).append(" more)\n");
            }
        } catch (Exception e) {
            sb.append("  ").append(label).append(" read error: ").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
