package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs after a successful code generation to create a matching GitHub repo:
 * {@code git init && git add && git commit && gh repo create --source=. --push}.
 * Idempotent — skips if the directory is already a git repo or gh is not installed.
 */
@Component
public class ProjectBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(ProjectBootstrapService.class);

    /** gh CLI location installed by winget. */
    private static final String GH_CMD = "C:\\Program Files\\GitHub CLI\\gh.exe";

    private final ToolExecutionNotifier notifier;

    public ProjectBootstrapService(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    /**
     * Initialize git in {@code dir} and create a matching GitHub repo.
     * Returns a short status string (always safe to append to tool output).
     */
    public String bootstrap(Path dir, boolean isPrivate) {
        try {
            if (dir == null || !Files.isDirectory(dir)) return "";
            log.info("[Bootstrap] Starting for {} (private={})", dir, isPrivate);
            if (Files.exists(dir.resolve(".git"))) {
                log.info("[Bootstrap] {} already a git repo — skipping.", dir);
                return "\n[GitHub] Skipped — already a git repo.";
            }
            if (!Files.list(dir).findAny().isPresent()) {
                log.info("[Bootstrap] {} is empty — skipping.", dir);
                return "\n[GitHub] Skipped — empty directory.";
            }

            notifier.notify("Initializing git repository...");
            log.info("[Bootstrap] git init in {}", dir);
            CmdResult init = run(dir, "git", "init");
            if (init.exit != 0) return "\n[GitHub] git init failed: " + abbreviate(init.output, 200);

            // default branch name
            run(dir, "git", "branch", "-M", "main");

            // write a minimal .gitignore if the generator didn't
            if (!Files.exists(dir.resolve(".gitignore"))) {
                Files.writeString(dir.resolve(".gitignore"),
                        "target/\nbuild/\nout/\n.idea/\n.vscode/\n*.class\nnode_modules/\n.DS_Store\n",
                        StandardCharsets.UTF_8);
            }

            notifier.notify("Committing initial scaffold...");
            log.info("[Bootstrap] git add + commit in {}", dir);
            run(dir, "git", "add", "-A");
            CmdResult commit = run(dir, "git", "commit", "-m", "Initial scaffold");
            if (commit.exit != 0 && !commit.output.toLowerCase().contains("nothing to commit")) {
                return "\n[GitHub] git commit failed: " + abbreviate(commit.output, 200);
            }

            if (!new File(GH_CMD).exists()) {
                log.warn("[Bootstrap] gh CLI not found at {} — skipping remote create.", GH_CMD);
                return "\n[GitHub] git initialized locally. gh CLI not installed — skipping remote create.";
            }

            String repoName = dir.getFileName().toString();
            notifier.notify("Creating GitHub repo '" + repoName + "' and pushing...");
            log.info("[Bootstrap] gh repo create {} ({}) --source=. --push", repoName, isPrivate ? "private" : "public");
            CmdResult gh = run(dir, GH_CMD, "repo", "create", repoName,
                    isPrivate ? "--private" : "--public",
                    "--source=.",
                    "--push");
            log.info("[Bootstrap] gh repo create exit={} output={}", gh.exit, abbreviate(gh.output, 300));
            if (gh.exit != 0) {
                String msg = gh.output.toLowerCase();
                if (msg.contains("name already exists") || msg.contains("already exists")) {
                    return "\n[GitHub] A repo named '" + repoName + "' already exists on your account. "
                            + "Rename the folder or delete the existing repo.";
                }
                if (msg.contains("auth") || msg.contains("credentials")) {
                    return "\n[GitHub] gh not authenticated. Run: gh auth login";
                }
                return "\n[GitHub] gh repo create failed: " + abbreviate(gh.output, 300);
            }
            String url = extractUrl(gh.output);
            return "\n[GitHub] Pushed: " + (url.isEmpty() ? repoName : url);
        } catch (Exception e) {
            log.warn("[ProjectBootstrap] failed", e);
            return "\n[GitHub] Error: " + e.getMessage();
        }
    }

    private CmdResult run(Path dir, String... cmd) {
        try {
            List<String> list = new ArrayList<>(cmd.length);
            for (String c : cmd) list.add(c);
            ProcessBuilder pb = new ProcessBuilder(list)
                    .directory(dir.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(60, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return new CmdResult(-1, "timed out"); }
            return new CmdResult(p.exitValue(), out);
        } catch (Exception e) {
            return new CmdResult(-1, e.getMessage());
        }
    }

    private static String extractUrl(String output) {
        if (output == null) return "";
        for (String line : output.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("https://github.com/")) return t;
        }
        return "";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record CmdResult(int exit, String output) {}
}
