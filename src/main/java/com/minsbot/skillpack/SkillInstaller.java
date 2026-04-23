package com.minsbot.skillpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a {@link SkillManifest.InstallRecipe}. Each supported {@code kind} maps to a
 * platform-appropriate package manager. On Windows, {@code brew} formulas are translated
 * to their {@code winget} equivalent via a curated table (covers the common cases seen
 * across SKILL.md-based skills).
 */
@Service
public class SkillInstaller {

    private static final Logger log = LoggerFactory.getLogger(SkillInstaller.class);

    /**
     * Homebrew formula -> winget package ID. Extend as you hit misses.
     * Only the formulas that appear across the sample skills need to be here today.
     */
    private static final Map<String, String> BREW_TO_WINGET = Map.ofEntries(
            Map.entry("curl",            "cURL.cURL"),
            Map.entry("wget",            "GNU.Wget"),
            Map.entry("git",             "Git.Git"),
            Map.entry("gh",              "GitHub.cli"),
            Map.entry("jq",              "jqlang.jq"),
            Map.entry("yq",              "MikeFarah.yq"),
            Map.entry("ffmpeg",          "Gyan.FFmpeg"),
            Map.entry("yt-dlp",          "yt-dlp.yt-dlp"),
            Map.entry("node",            "OpenJS.NodeJS"),
            Map.entry("python",          "Python.Python.3.12"),
            Map.entry("python@3.12",     "Python.Python.3.12"),
            Map.entry("rust",            "Rustlang.Rustup"),
            Map.entry("1password-cli",   "AgileBits.1Password.CLI"),
            Map.entry("tmux",            "")                       // no winget ID; fall back to manual
    );

    public enum Status { OK, UNSUPPORTED, FAILED }

    public record Result(Status status, String message) {}

    /**
     * Runs {@code recipe} streaming stdout lines to {@code emit} with {@code "log|..."} /
     * {@code "progress|..."} / {@code "phase|..."} prefixes that match the other installers
     * in the codebase (ComfyUI / Piper) so the UI can reuse the same SSE framing.
     */
    public Result install(SkillManifest.InstallRecipe recipe, Consumer<String> emit) {
        if (recipe == null || recipe.kind() == null) {
            return new Result(Status.UNSUPPORTED, "No install recipe provided.");
        }
        String kind = recipe.kind().toLowerCase(Locale.ROOT);

        try {
            return switch (kind) {
                case "brew"   -> installBrewOrWinget(recipe, emit);
                case "winget" -> installWinget(recipe, emit);
                case "scoop"  -> runCommand(emit, "scoop", "install", nullToEmpty(recipe.packageName()));
                case "node"   -> runCommand(emit, npmCommand(), "install", "-g", nullToEmpty(recipe.packageName()));
                case "pip"    -> runCommand(emit, pipCommand(), "install", "--user", nullToEmpty(recipe.packageName()));
                case "cargo"  -> runCommand(emit, "cargo", "install", nullToEmpty(recipe.packageName()));
                case "manual" -> new Result(Status.UNSUPPORTED,
                        "Manual install only — follow the skill's homepage.");
                default       -> new Result(Status.UNSUPPORTED,
                        "Unsupported install kind: " + kind);
            };
        } catch (Exception e) {
            log.warn("[SkillInstaller] {} failed: {}", recipe.id(), e.getMessage(), e);
            return new Result(Status.FAILED, e.getMessage());
        }
    }

    // ─── winget (native Windows package IDs) ─────────────────────────

    private Result installWinget(SkillManifest.InstallRecipe recipe, Consumer<String> emit) throws Exception {
        if (!isWindows()) {
            return new Result(Status.UNSUPPORTED, "winget installs only run on Windows.");
        }
        String id = recipe.packageName();
        if (id == null || id.isBlank()) {
            return new Result(Status.FAILED, "winget recipe missing package ID");
        }
        return runCommand(emit, "winget", "install", "--id", id, "--silent",
                "--accept-source-agreements", "--accept-package-agreements");
    }

    // ─── brew (with Windows translation table) ───────────────────────

    private Result installBrewOrWinget(SkillManifest.InstallRecipe recipe, Consumer<String> emit) throws Exception {
        String formula = recipe.packageName();
        if (formula == null || formula.isBlank()) {
            return new Result(Status.FAILED, "brew recipe missing formula");
        }
        if (isWindows()) {
            String winget = BREW_TO_WINGET.get(formula.toLowerCase(Locale.ROOT));
            if (winget == null) {
                return new Result(Status.UNSUPPORTED,
                        "No winget equivalent known for brew formula '" + formula + "'. "
                                + "Install manually via the skill's homepage, then click Re-check.");
            }
            if (winget.isBlank()) {
                return new Result(Status.UNSUPPORTED,
                        "'" + formula + "' has no winget package. Install manually via the skill's homepage.");
            }
            emit.accept("log|Translating brew '" + formula + "' → winget '" + winget + "'");
            return runCommand(emit, "winget", "install", "--id", winget, "--silent",
                    "--accept-source-agreements", "--accept-package-agreements");
        }
        if (isMac()) {
            return runCommand(emit, "brew", "install", formula);
        }
        // Linux — try the native brew if installed, otherwise punt.
        return runCommand(emit, "brew", "install", formula);
    }

    // ─── generic command runner ──────────────────────────────────────

    private Result runCommand(Consumer<String> emit, String... cmd) throws Exception {
        // strip any empty trailing args (e.g. nullToEmpty -> "")
        List<String> filtered = java.util.Arrays.stream(cmd).filter(s -> s != null && !s.isBlank()).toList();
        emit.accept("log|$ " + String.join(" ", filtered));

        ProcessBuilder pb = new ProcessBuilder(filtered).redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                emit.accept("log|" + line);
            }
        }
        if (!p.waitFor(15, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return new Result(Status.FAILED, "Install timed out after 15 minutes");
        }
        int exit = p.exitValue();
        if (exit == 0) {
            return new Result(Status.OK, "Installed successfully.");
        }
        return new Result(Status.FAILED, "Exit code " + exit);
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private static String npmCommand() { return isWindows() ? "npm.cmd" : "npm"; }

    private static String pipCommand() {
        // On Windows, "pip" ships as pip.exe + sometimes as `py -m pip`. Prefer the straight binary.
        return isWindows() ? "pip" : "pip3";
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
