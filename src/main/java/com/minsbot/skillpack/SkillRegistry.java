package com.minsbot.skillpack;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans a folder of SKILL.md-based skills (standard SKILL.md layout) and
 * holds the parsed manifests in memory. Refreshable at runtime so a freshly-
 * installed skill pack shows up without a restart.
 *
 * <p>Default root: {@code ~/mins_bot_data/skill_packs/}. Drop any community
 * skill folder into it and it becomes a first-class Mins Bot skill.</p>
 */
@Service
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    @Value("${app.skill-packs.folder:#{systemProperties['user.home']}/mins_bot_data/skill_packs}")
    private String skillFolder;

    /** Repo-root starter library — scanned as a fallback root so bundled skills light up in dev mode. */
    @Value("${app.skill-packs.bundled-folder:skill_packs}")
    private String sampleFolder;

    private volatile Map<String, SkillManifest> byName = Map.of();

    @PostConstruct
    public void load() {
        rescan();
    }

    /** Re-read every {@code SKILL.md} under the configured roots. Safe to call at runtime. */
    public synchronized int rescan() {
        Map<String, SkillManifest> out = new LinkedHashMap<>();
        for (Path root : roots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root, 3)) {
                // Case-insensitive match — native skills use lowercase 'skill.md',
                // imported third-party community packs use 'SKILL.md'.
                s.filter(p -> "skill.md".equalsIgnoreCase(nameOf(p)))
                        .forEach(p -> {
                            SkillManifest m = SkillManifestParser.parse(p);
                            if (m != null) {
                                // First occurrence wins (user folder > sample folder).
                                out.putIfAbsent(m.name(), m);
                            }
                        });
            } catch (IOException e) {
                log.warn("[SkillRegistry] scan failed for {}: {}", root, e.getMessage());
            }
        }
        this.byName = Map.copyOf(out);
        log.info("[SkillRegistry] Loaded {} skills from {}", out.size(),
                roots().stream().map(Path::toString).toList());
        return out.size();
    }

    /** All skills, sorted by name. Callers that need a stable order can rely on this. */
    public List<SkillManifest> all() {
        return byName.values().stream()
                .sorted(Comparator.comparing(SkillManifest::name))
                .toList();
    }

    public Collection<SkillManifest> allUnsorted() { return byName.values(); }

    public SkillManifest byName(String name) {
        return (name == null) ? null : byName.get(name.trim());
    }

    /** Skills compatible with the current OS (empty {@code os} list = compatible with all). */
    public List<SkillManifest> forCurrentOs() {
        String id = currentOsId();
        return all().stream().filter(m -> m.supportsOs(id)).toList();
    }

    /** "windows" / "darwin" / "linux" — matches what SKILL.md frontmatter uses. */
    public static String currentOsId() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        return "linux";
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private List<Path> roots() {
        List<Path> out = new java.util.ArrayList<>();
        if (skillFolder != null && !skillFolder.isBlank()) {
            out.add(Paths.get(skillFolder).toAbsolutePath());
        }
        if (sampleFolder != null && !sampleFolder.isBlank()) {
            Path rel = Paths.get(sampleFolder).toAbsolutePath();
            if (Files.isDirectory(rel)) out.add(rel);
        }
        return out;
    }

    private static String nameOf(Path p) {
        Path n = p.getFileName();
        return (n == null) ? "" : n.toString();
    }
}
