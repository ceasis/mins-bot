package com.minsbot.skillpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Verifies the prerequisites a {@link SkillManifest} declares: binaries on PATH,
 * mandatory env vars. Cheap, cached for 30 s so the UI can poll without costing
 * a subprocess per card.
 */
@Service
public class SkillPrereqChecker {

    private static final Logger log = LoggerFactory.getLogger(SkillPrereqChecker.class);

    /** bin name -> {resolved, checkedAtMillis}. */
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private static final long CACHE_MS = 30_000;

    public record Result(
            boolean ok,
            /** Binaries from {@code requires.bins} that weren't found. */
            List<String> missingBins,
            /** {@code requires.anyBins} list if none were found; empty if at least one was. */
            List<String> missingAnyOfBins,
            /** Env vars from {@code requires.env} that weren't set. */
            List<String> missingEnv,
            /** True when this skill's {@code os} list doesn't include the current OS. */
            boolean osIncompatible
    ) {}

    public Result check(SkillManifest skill) {
        boolean osIncompatible = !skill.supportsOs(SkillRegistry.currentOsId());

        List<String> missingBins = new ArrayList<>();
        for (String bin : skill.requiredBins()) {
            if (!isBinOnPath(bin)) missingBins.add(bin);
        }

        List<String> missingAnyOf = List.of();
        if (!skill.anyOfBins().isEmpty()) {
            boolean anyFound = skill.anyOfBins().stream().anyMatch(this::isBinOnPath);
            if (!anyFound) missingAnyOf = List.copyOf(skill.anyOfBins());
        }

        List<String> missingEnv = new ArrayList<>();
        for (String env : skill.requiredEnv()) {
            String v = System.getenv(env);
            if (v == null || v.isBlank()) missingEnv.add(env);
        }

        boolean ok = !osIncompatible
                && missingBins.isEmpty()
                && missingAnyOf.isEmpty()
                && missingEnv.isEmpty();
        return new Result(ok, missingBins, missingAnyOf, missingEnv, osIncompatible);
    }

    /** Clear the cache. Call after installing something so prereq status refreshes. */
    public void invalidate() {
        cache.clear();
    }

    /** True iff {@code name} resolves to an executable on the current PATH. */
    public boolean isBinOnPath(String name) {
        if (name == null || name.isBlank()) return false;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        Cached c = cache.get(normalized);
        long now = System.currentTimeMillis();
        if (c != null && (now - c.checkedAt) < CACHE_MS) return c.resolved;

        boolean resolved = runWhich(normalized);
        cache.put(normalized, new Cached(resolved, now));
        return resolved;
    }

    private static boolean runWhich(String name) {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String[] cmd = win ? new String[]{"where", name} : new String[]{"/bin/sh", "-c", "command -v " + name};
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            log.debug("[SkillPrereq] probe {} failed: {}", name, e.getMessage());
            return false;
        }
    }

    private record Cached(boolean resolved, long checkedAt) {}
}
