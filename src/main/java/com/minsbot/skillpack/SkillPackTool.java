package com.minsbot.skillpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Meta-tool that exposes the SKILL.md-based pack ecosystem to the LLM.
 * Two @Tool methods — one to list, one to invoke — keep the main prompt thin
 * (progressive disclosure: descriptions always in context, bodies loaded on
 * explicit invocation).
 */
@Component
public class SkillPackTool {

    private static final Logger log = LoggerFactory.getLogger(SkillPackTool.class);

    private final SkillRegistry registry;
    private final SkillPrereqChecker prereq;

    /** Optional — applied per-skill when the LLM loads a manifest that
     *  declares {@code metadata.minsbot.playwright.show-browser}. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.minsbot.agent.tools.PlaywrightService playwrightService;

    public SkillPackTool(SkillRegistry registry, SkillPrereqChecker prereq) {
        this.registry = registry;
        this.prereq = prereq;
    }

    @Tool(description =
            "List every installed SKILL.md-based skill pack that works on this OS. Returns a compact " +
            "'name — description' menu. Call this FIRST when the user's request sounds like it might " +
            "match a specialized external skill (e.g. '1password', 'discord', 'notion', 'github issues', " +
            "'whisper transcribe'). Each returned name can then be passed to invokeSkillPack to load " +
            "the full how-to instructions for that skill.")
    public String listSkillPacks() {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (SkillManifest s : registry.forCurrentOs()) {
            SkillPrereqChecker.Result r = prereq.check(s);
            sb.append("- ").append(s.name());
            if (s.emoji() != null && !s.emoji().isBlank()) sb.append(' ').append(s.emoji());
            if (!r.ok()) {
                sb.append(" [NOT READY: ");
                boolean first = true;
                if (!r.missingBins().isEmpty()) {
                    sb.append("missing bins ").append(r.missingBins());
                    first = false;
                }
                if (!r.missingEnv().isEmpty()) {
                    if (!first) sb.append("; ");
                    sb.append("missing env ").append(r.missingEnv());
                }
                sb.append("]");
            }
            sb.append(" — ");
            sb.append(oneLine(s.description()));
            sb.append('\n');
            shown++;
        }
        if (shown == 0) return "No skill packs installed. The user can drop SKILL.md-format skills into ~/mins_bot_data/skill_packs/.";
        sb.append("\n(").append(shown).append(" skill pack").append(shown == 1 ? "" : "s")
                .append(" available. Call invokeSkillPack(name) to load a specific one's instructions.)");
        return sb.toString();
    }

    @Tool(description =
            "Load the full how-to instructions for a specific skill pack by name. Returns the skill's " +
            "markdown body — a concrete guide for completing the user's task using that skill's tooling. " +
            "After calling, READ the returned instructions carefully and follow them step by step using " +
            "whatever shell / file / HTTP tools are available. If the skill's binaries aren't installed, " +
            "the return value will include a hint to install them first via the Skills tab.\n" +
            "CHAINING: you MAY call invokeSkillPack again within the same turn to load another skill. " +
            "This is how composite flows work — e.g. 'receipt-ocr' extracts data, then its instructions " +
            "tell you to call invokeSkillPack('expense-log') to log the amount. Follow skill-to-skill " +
            "handoffs exactly as the body describes; do not improvise a different chain.")
    public String invokeSkillPack(
            @ToolParam(description = "Exact skill name as returned by listSkillPacks (e.g. '1password', 'discord').")
            String name
    ) {
        SkillManifest s = registry.byName(name);
        if (s == null) {
            return "Unknown skill: '" + name + "'. Call listSkillPacks() to see installed names.";
        }
        if (!s.supportsOs(SkillRegistry.currentOsId())) {
            return "Skill '" + name + "' is not compatible with this OS (" + SkillRegistry.currentOsId()
                    + "). The skill declares os=" + s.osList() + ". Stop and tell the user.";
        }
        SkillPrereqChecker.Result r = prereq.check(s);
        StringBuilder sb = new StringBuilder();
        if (!r.ok()) {
            sb.append("⚠ PREREQS NOT MET — the skill's instructions reference tools that aren't installed.\n");
            if (!r.missingBins().isEmpty()) {
                sb.append("  Missing binaries on PATH: ").append(r.missingBins()).append('\n');
            }
            if (!r.missingAnyOfBins().isEmpty()) {
                sb.append("  Need at least one of: ").append(r.missingAnyOfBins()).append('\n');
            }
            if (!r.missingEnv().isEmpty()) {
                sb.append("  Missing env vars: ").append(r.missingEnv()).append('\n');
            }
            sb.append("  Tell the user to open the Skills tab and click Install for '").append(name)
                    .append("'. Do not attempt to run the skill until they've done so.\n\n");
        }
        // Apply the skill's per-skill Playwright visibility override (if any)
        // BEFORE returning the body. From here on, any tool the LLM calls that
        // routes through PlaywrightService (searchWeb / fetchPageText / image
        // search / page screenshot) will honor the skill's choice. If the
        // skill omits the field, the global app.playwright.headless wins.
        if (playwrightService != null && s.showPlaywrightBrowser() != null) {
            try {
                playwrightService.setShowBrowserOverride(s.showPlaywrightBrowser());
                log.info("[SkillPack] Playwright show-browser override = {} (from skill '{}')",
                        s.showPlaywrightBrowser(), name);
            } catch (Exception e) {
                log.warn("[SkillPack] failed to apply Playwright override: {}", e.getMessage());
            }
        }

        sb.append("── SKILL: ").append(name).append(" ──\n\n");
        if (s.description() != null && !s.description().isBlank()) {
            sb.append(s.description()).append("\n\n");
        }
        String body = s.body() == null ? "" : s.body();
        // Substitute {baseDir} with the skill's actual folder so script/reference paths
        // in the body are absolute + immediately usable by runSkillScript / loadSkillReference.
        String baseDir = s.folder() == null ? "" : s.folder().toAbsolutePath().toString().replace('\\', '/');
        body = body.replace("{baseDir}", baseDir);
        sb.append(body);
        sb.append("\n\n---\nSKILL ROOT: ").append(baseDir).append("\n")
                .append("For any 'scripts/<x>' referenced above: call `runSkillScript(\"").append(name).append("\", \"<x>\")`.\n")
                .append("For any 'references/<x>' referenced above: call `loadSkillReference(\"").append(name).append("\", \"<x>\")`.\n");
        log.info("[SkillPack] LLM loaded skill '{}' ({} chars of body)", name, body.length());
        return sb.toString();
    }

    @Tool(description =
            "Execute a script bundled inside a skill pack's scripts/ folder (e.g. 'scripts/transcribe.sh'). " +
            "Use this after invokeSkillPack returns a body that references {baseDir}/scripts/<name> or " +
            "scripts/<name>. The script is run from the skill's folder as working directory so relative " +
            "paths inside the script resolve. Returns stdout+stderr, capped at 16 KB.")
    public String runSkillScript(
            @ToolParam(description = "Skill name exactly as returned by listSkillPacks") String skillName,
            @ToolParam(description = "Path relative to the skill folder, e.g. 'scripts/foo.sh' or 'scripts/foo.py'") String relPath,
            @ToolParam(description = "Optional space-separated arguments to pass to the script", required = false) String args
    ) {
        SkillManifest s = registry.byName(skillName);
        if (s == null) return "Unknown skill: '" + skillName + "'.";
        java.nio.file.Path base = s.folder() == null ? null : s.folder().toAbsolutePath().normalize();
        if (base == null) return "Skill '" + skillName + "' has no folder on disk.";
        java.nio.file.Path target = base.resolve(relPath == null ? "" : relPath).normalize();
        if (!target.startsWith(base)) {
            return "Refusing: '" + relPath + "' escapes the skill folder.";
        }
        if (!java.nio.file.Files.isRegularFile(target)) {
            return "Script not found: " + target;
        }
        String name = target.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        boolean win = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

        java.util.List<String> cmd = new java.util.ArrayList<>();
        if (name.endsWith(".py"))       { cmd.add(win ? "python" : "python3"); cmd.add(target.toString()); }
        else if (name.endsWith(".sh"))  { cmd.add(win ? "bash" : "/bin/bash"); cmd.add(target.toString()); }
        else if (name.endsWith(".ps1")) { cmd.add("powershell"); cmd.add("-ExecutionPolicy"); cmd.add("Bypass"); cmd.add("-File"); cmd.add(target.toString()); }
        else if (name.endsWith(".js"))  { cmd.add("node"); cmd.add(target.toString()); }
        else if (name.endsWith(".bat") || name.endsWith(".cmd")) { cmd.add("cmd"); cmd.add("/c"); cmd.add(target.toString()); }
        else                            { cmd.add(target.toString()); } // direct exec (hopefully has shebang / is binary)

        if (args != null && !args.isBlank()) {
            for (String a : args.trim().split("\\s+")) cmd.add(a);
        }

        try {
            Process p = new ProcessBuilder(cmd).directory(base.toFile()).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line; int bytes = 0;
                while ((line = r.readLine()) != null) {
                    if (bytes > 16_000) { out.append("\n…[truncated at 16 KB]…"); break; }
                    out.append(line).append('\n');
                    bytes += line.length() + 1;
                }
            }
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!done) { p.destroyForcibly(); return "Script timed out after 5 minutes.\n" + out; }
            return "exit=" + p.exitValue() + "\n" + out;
        } catch (Exception e) {
            return "Script execution failed: " + e.getMessage();
        }
    }

    @Tool(description =
            "Load an auxiliary reference file bundled with a skill pack (e.g. 'references/api.md'). " +
            "Skill bodies link to deep-dive references — this loads one on demand so the main body " +
            "stays lean. Returns the raw file contents, capped at 64 KB.")
    public String loadSkillReference(
            @ToolParam(description = "Skill name exactly as returned by listSkillPacks") String skillName,
            @ToolParam(description = "Path relative to the skill folder, e.g. 'references/foo.md'") String relPath
    ) {
        SkillManifest s = registry.byName(skillName);
        if (s == null) return "Unknown skill: '" + skillName + "'.";
        java.nio.file.Path base = s.folder() == null ? null : s.folder().toAbsolutePath().normalize();
        if (base == null) return "Skill '" + skillName + "' has no folder on disk.";
        java.nio.file.Path target = base.resolve(relPath == null ? "" : relPath).normalize();
        if (!target.startsWith(base)) {
            return "Refusing: '" + relPath + "' escapes the skill folder.";
        }
        if (!java.nio.file.Files.isRegularFile(target)) {
            return "Reference not found: " + target;
        }
        try {
            String body = java.nio.file.Files.readString(target);
            if (body.length() > 64_000) {
                return body.substring(0, 64_000) + "\n\n…[truncated at 64 KB — file is " + body.length() + " chars total]";
            }
            return body;
        } catch (java.io.IOException e) {
            return "Couldn't read reference: " + e.getMessage();
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String out = s.replaceAll("\\s+", " ").trim();
        return out.length() > 160 ? out.substring(0, 157) + "..." : out;
    }
}
