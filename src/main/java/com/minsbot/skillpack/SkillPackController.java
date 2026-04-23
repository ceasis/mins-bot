package com.minsbot.skillpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skill-packs")
public class SkillPackController {

    private static final Logger log = LoggerFactory.getLogger(SkillPackController.class);

    private final SkillRegistry registry;
    private final SkillPrereqChecker prereq;
    private final SkillInstaller installer;
    private final SkillImporter importer;
    private final com.minsbot.agent.AsyncMessageService asyncMessages;

    public SkillPackController(SkillRegistry registry, SkillPrereqChecker prereq,
                               SkillInstaller installer, SkillImporter importer,
                               com.minsbot.agent.AsyncMessageService asyncMessages) {
        this.registry = registry;
        this.prereq = prereq;
        this.installer = installer;
        this.importer = importer;
        this.asyncMessages = asyncMessages;
    }

    /** Full catalog — each entry carries pre-computed prereq status so the UI renders in one round trip. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillManifest s : registry.all()) {
            out.add(toJson(s, prereq.check(s)));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("currentOs", SkillRegistry.currentOsId());
        resp.put("skills", out);
        resp.put("count", out.size());
        return ResponseEntity.ok(resp);
    }

    /** One skill — includes the full markdown body so a detail modal can render it. */
    @GetMapping(value = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> one(@PathVariable String name) {
        SkillManifest s = registry.byName(name);
        if (s == null) return ResponseEntity.notFound().build();
        Map<String, Object> out = toJson(s, prereq.check(s));
        out.put("body", s.body());
        return ResponseEntity.ok(out);
    }

    /** Force a rescan of the skill folder (e.g. after the user drops a new skill in). */
    @PostMapping("/rescan")
    public ResponseEntity<?> rescan() {
        int n = registry.rescan();
        prereq.invalidate();
        return ResponseEntity.ok(Map.of("loaded", n));
    }

    /**
     * Import a new skill from a remote URL into {@code ~/mins_bot_data/skill_packs/}.
     * Accepts a raw {@code .md}, a {@code .zip} archive, or a {@code github.com} repo/tree URL.
     */
    @PostMapping(value = "/import", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> importFromUrl(@RequestBody Map<String, Object> body) {
        String url = body == null ? null : (String) body.get("url");
        SkillImporter.Result r = importer.importFromUrl(url);
        if (r.ok()) {
            registry.rescan();
            prereq.invalidate();
            if (asyncMessages != null) {
                asyncMessages.push("📥 Skill pack '" + r.name() + "' imported. Say 'list my skill packs' to see it.");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("name", r.name());
            out.put("path", r.installedPath());
            out.put("message", r.message());
            return ResponseEntity.ok(out);
        }
        return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", r.message() == null ? "Import failed." : r.message()
        ));
    }

    /**
     * Run the skill's first applicable install recipe, streaming stdout as SSE.
     * Event shape matches the Ollama / ComfyUI / Piper installers (phase|log|progress|done|error)
     * so the frontend install-progress widget is reusable.
     */
    @GetMapping(value = "/{name}/install", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter install(@PathVariable String name) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(20).toMillis());

        SkillManifest skill = registry.byName(name);
        if (skill == null) {
            sendAndClose(emitter, "error", Map.of("error", "Unknown skill: " + name));
            return emitter;
        }
        if (skill.installRecipes().isEmpty()) {
            sendAndClose(emitter, "error", Map.of("error", "No install recipe defined for '" + name + "'.",
                    "homepage", skill.homepage() == null ? "" : skill.homepage()));
            return emitter;
        }

        new Thread(() -> {
            try {
                java.util.function.Consumer<String> emit = s -> {
                    int sep = s.indexOf('|');
                    String event = (sep > 0) ? s.substring(0, sep) : "log";
                    String rest = (sep > 0) ? s.substring(sep + 1) : s;
                    try {
                        if ("progress".equals(event)) {
                            emitter.send(SseEmitter.event().name("progress").data(rest));
                        } else if ("phase".equals(event)) {
                            int sep2 = rest.indexOf('|');
                            String phase = sep2 > 0 ? rest.substring(0, sep2) : rest;
                            String msg = sep2 > 0 ? rest.substring(sep2 + 1) : "";
                            emitter.send(SseEmitter.event().name("phase").data(
                                    "{\"phase\":\"" + phase + "\",\"message\":\"" + escape(msg) + "\"}"));
                        } else {
                            emitter.send(SseEmitter.event().name(event).data(
                                    "{\"message\":\"" + escape(rest) + "\"}"));
                        }
                    } catch (Exception ignored) {}
                };

                // Try each recipe in order; first one that returns OK wins.
                SkillInstaller.Result last = null;
                for (SkillManifest.InstallRecipe recipe : skill.installRecipes()) {
                    emit.accept("phase|install|" + (recipe.label() != null ? recipe.label()
                            : "Installing " + recipe.kind() + " package: " + recipe.packageName()));
                    last = installer.install(recipe, emit);
                    if (last.status() == SkillInstaller.Status.OK) break;
                    emit.accept("log|Recipe '" + recipe.id() + "' returned " + last.status() + " — trying next.");
                }
                prereq.invalidate();

                if (last != null && last.status() == SkillInstaller.Status.OK) {
                    emit.accept("phase|done|Installed — skill is ready to use.");
                    if (asyncMessages != null) asyncMessages.push("📦 Skill pack '" + name + "' installed. Ask me to use it anytime.");
                    emitter.send(SseEmitter.event().name("done").data("{\"status\":\"done\"}"));
                } else {
                    String msg = last == null ? "no recipe ran"
                            : (last.message() == null ? "install failed" : last.message());
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"error\":\"" + escape(msg) + "\"}"));
                }
                emitter.complete();
            } catch (Exception e) {
                log.warn("[SkillPack] install '{}' failed: {}", name, e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"error\":\"" + escape(e.getMessage()) + "\"}"));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        }, "skill-install-" + name).start();

        return emitter;
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private Map<String, Object> toJson(SkillManifest s, SkillPrereqChecker.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("description", s.description());
        m.put("homepage", s.homepage());
        m.put("emoji", s.emoji());
        m.put("os", s.osList());
        m.put("requiresBins", s.requiredBins());
        m.put("anyOfBins", s.anyOfBins());
        m.put("requiresEnv", s.requiredEnv());
        m.put("primaryEnv", s.primaryEnv());
        m.put("installable", !s.installRecipes().isEmpty());
        m.put("installKinds", s.installRecipes().stream().map(SkillManifest.InstallRecipe::kind).toList());
        m.put("folder", s.folder() == null ? null : s.folder().toString());
        m.put("prereqOk", r.ok());
        m.put("missingBins", r.missingBins());
        m.put("missingAnyOfBins", r.missingAnyOfBins());
        m.put("missingEnv", r.missingEnv());
        m.put("osIncompatible", r.osIncompatible());
        return m;
    }

    private static void sendAndClose(SseEmitter e, String name, Object data) {
        try {
            e.send(SseEmitter.event().name(name).data(data));
            e.complete();
        } catch (Exception ignored) {}
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
