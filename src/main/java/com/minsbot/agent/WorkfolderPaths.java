package com.minsbot.agent;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonical, hardcoded location for every Mins Bot ephemeral scratch folder.
 *
 * <p><b>Invariant.</b> The literal path {@code ~/mins_bot_data/mins_workfolder/}
 * is defined here and ONLY here. No other class — and no skill prompt — chooses
 * where the workfolder lives. The LLM cannot redirect it, override it, or
 * suggest an alternative; this is structure, not behavior, and structure is
 * non-negotiable.
 *
 * <p><b>Why a separate utility.</b> Keeps the path off the call sites, makes
 * the path easy to relocate (one edit), and gives every resolver a path-
 * containment check so a malformed slug (LLM-supplied or otherwise) can never
 * escape the workfolder root via {@code ..} or absolute paths.
 *
 * <p><b>Usage.</b> Resolve a per-task scratch folder via {@link #resolve(String)}.
 * Verify any externally-supplied path via {@link #assertInside(Path)}. Both
 * normalise and refuse escape attempts.
 */
public final class WorkfolderPaths {

    /** Hardcoded scratch root. NOT user-configurable. NOT prompt-overridable. */
    public static final Path ROOT = resolveRoot();

    private WorkfolderPaths() {}

    /** Resolve {@code ROOT/<slug>}. Slug is sanitised; the result is asserted
     *  to live inside {@link #ROOT}. */
    public static Path resolve(String slug) {
        String safe = sanitiseSlug(slug);
        Path p = ROOT.resolve(safe).normalize();
        assertInside(p);
        return p;
    }

    /** Throws if {@code p} (after normalisation) is not under {@link #ROOT}.
     *  Use as a belt-and-suspenders check at any site that resolves an
     *  externally-supplied component into the workfolder. */
    public static void assertInside(Path p) {
        if (p == null) throw new IllegalArgumentException("Workfolder path cannot be null");
        Path n = p.toAbsolutePath().normalize();
        Path r = ROOT.toAbsolutePath().normalize();
        if (!n.startsWith(r)) {
            throw new IllegalStateException(
                    "Workfolder containment violated: " + n + " is not under " + r);
        }
    }

    /** Lowercase + dash-only + length-capped. Empty input becomes "unnamed". */
    public static String sanitiseSlug(String raw) {
        if (raw == null) return "unnamed";
        String s = raw.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.isEmpty()) return "unnamed";
        return s.length() > 95 ? s.substring(0, 95) : s;
    }

    private static Path resolveRoot() {
        String home = System.getProperty("user.home");
        return Paths.get(home == null ? "." : home, "mins_bot_data", "mins_workfolder")
                .toAbsolutePath().normalize();
    }
}
