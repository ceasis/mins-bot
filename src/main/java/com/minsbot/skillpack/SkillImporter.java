package com.minsbot.skillpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a skill pack from a remote URL into {@code ~/mins_bot_data/skill_packs/}.
 *
 * <p>Three URL shapes supported:
 * <ul>
 *   <li><b>Raw SKILL.md</b> — URL ends with {@code .md}. Fetched directly; skill name
 *       taken from the frontmatter {@code name:} field.</li>
 *   <li><b>ZIP archive</b> — URL ends with {@code .zip}. Downloaded, extracted, searched
 *       for the first {@code SKILL.md}/{@code skill.md}; that folder becomes the skill.</li>
 *   <li><b>GitHub repo URL</b> — {@code https://github.com/USER/REPO} or
 *       {@code /tree/BRANCH/path}. Auto-translated to the GitHub
 *       {@code /archive/refs/heads/BRANCH.zip} endpoint, then treated as a ZIP import.
 *       If a sub-path is given, only that subtree is extracted.</li>
 * </ul>
 */
@Service
public class SkillImporter {

    private static final Logger log = LoggerFactory.getLogger(SkillImporter.class);

    @Value("${app.skill-packs.folder:#{systemProperties['user.home']}/mins_bot_data/skill_packs}")
    private String destRoot;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Result(boolean ok, String name, String installedPath, String message) {}

    public Result importFromUrl(String url) {
        if (url == null || url.isBlank()) return new Result(false, null, null, "Empty URL.");
        String u = url.trim();

        try {
            Files.createDirectories(Paths.get(destRoot));

            if (u.endsWith(".md")) {
                return importMarkdown(u);
            }
            if (u.endsWith(".zip")) {
                return importZip(u, null);
            }
            if (u.contains("github.com/")) {
                return importGitHub(u);
            }
            return new Result(false, null, null,
                    "Unsupported URL. Supply a direct .md, a .zip, or a github.com repo/tree URL.");
        } catch (Exception e) {
            log.warn("[SkillImporter] {} failed: {}", u, e.getMessage(), e);
            return new Result(false, null, null, "Import failed: " + e.getMessage());
        }
    }

    // ─── MD path ─────────────────────────────────────────────────────

    private Result importMarkdown(String url) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "mins-bot-skill-importer/1.0")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return new Result(false, null, null, "HTTP " + resp.statusCode() + " fetching " + url);
        }
        String body = resp.body();
        String name = extractNameFromFrontmatter(body);
        if (name == null) {
            return new Result(false, null, null,
                    "Couldn't find 'name:' in the markdown frontmatter. Not a valid SKILL.md.");
        }
        Path dir = Paths.get(destRoot, safeFolderName(name));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("skill.md"), body,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("[SkillImporter] imported single .md → {}", dir);
        return new Result(true, name, dir.toString(),
                "Installed " + name + " from single SKILL.md.");
    }

    // ─── ZIP path ────────────────────────────────────────────────────

    /**
     * Download + extract a ZIP. {@code subpath} (nullable) = directory within the archive
     * whose contents become the skill folder — used by the GitHub path to skip
     * {@code REPO-main/} + the user's sub-directory.
     */
    private Result importZip(String zipUrl, String subpath) throws Exception {
        Path tmpZip = Files.createTempFile("skill-import-", ".zip");
        Path tmpDir = Files.createTempDirectory("skill-extract-");
        try {
            downloadTo(zipUrl, tmpZip);
            unzip(tmpZip, tmpDir);

            // Find the first directory containing a SKILL.md / skill.md.
            Path skillFolder = locateSkillFolder(tmpDir, subpath);
            if (skillFolder == null) {
                return new Result(false, null, null,
                        "No SKILL.md or skill.md found inside the archive"
                                + (subpath != null ? " at subpath '" + subpath + "'." : "."));
            }
            Path skillMd = Files.list(skillFolder)
                    .filter(p -> "skill.md".equalsIgnoreCase(p.getFileName().toString()))
                    .findFirst().orElseThrow();
            String body = Files.readString(skillMd);
            String name = extractNameFromFrontmatter(body);
            if (name == null) {
                return new Result(false, null, null,
                        "Found a skill.md but it has no 'name:' in frontmatter.");
            }
            Path dest = Paths.get(destRoot, safeFolderName(name));
            if (Files.exists(dest)) {
                // Replace atomically: remove then move.
                deleteRecursive(dest);
            }
            // Move the whole folder (includes scripts/, references/, assets/ if present).
            moveDir(skillFolder, dest);
            log.info("[SkillImporter] imported zip '{}' → {}", zipUrl, dest);
            return new Result(true, name, dest.toString(),
                    "Installed " + name + " from archive.");
        } finally {
            try { Files.deleteIfExists(tmpZip); } catch (IOException ignored) {}
            try { deleteRecursive(tmpDir); } catch (IOException ignored) {}
        }
    }

    // ─── GitHub path ────────────────────────────────────────────────

    private Result importGitHub(String url) throws Exception {
        // Shapes:
        //   https://github.com/user/repo
        //   https://github.com/user/repo/tree/BRANCH
        //   https://github.com/user/repo/tree/BRANCH/path/to/skill
        String trimmed = url.replaceAll("/+$", "");
        String[] parts = trimmed.split("/");
        if (parts.length < 5 || !"github.com".equals(parts[2])) {
            return new Result(false, null, null, "Not a github.com URL.");
        }
        String user = parts[3];
        String repo = parts[4];
        String branch = "main";
        String subpath = null;
        if (parts.length > 6 && "tree".equals(parts[5])) {
            branch = parts[6];
            if (parts.length > 7) {
                // Everything after /tree/BRANCH/ is the subpath
                StringBuilder sb = new StringBuilder();
                for (int i = 7; i < parts.length; i++) {
                    if (sb.length() > 0) sb.append('/');
                    sb.append(parts[i]);
                }
                subpath = sb.toString();
            }
        }
        String zipUrl = "https://github.com/" + user + "/" + repo
                + "/archive/refs/heads/" + branch + ".zip";
        // Inside the zip, GitHub prefixes everything with REPO-BRANCH/
        String archiveSubpath = repo + "-" + branch + (subpath != null ? "/" + subpath : "");
        return importZip(zipUrl, archiveSubpath);
    }

    // ─── helpers ────────────────────────────────────────────────────

    private void downloadTo(String url, Path dest) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "mins-bot-skill-importer/1.0")
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " fetching " + url);
        }
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(dest,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void unzip(Path zip, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir)) continue; // zip-slip
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
            }
        }
    }

    /** Find the folder containing SKILL.md/skill.md. Honors a subpath hint when given. */
    private static Path locateSkillFolder(Path extractRoot, String subpath) throws IOException {
        Path start = (subpath == null || subpath.isBlank())
                ? extractRoot : extractRoot.resolve(subpath);
        if (!Files.isDirectory(start)) return null;
        try (Stream<Path> walk = Files.walk(start, 4)) {
            return walk.filter(p -> "skill.md".equalsIgnoreCase(p.getFileName() == null ? "" : p.getFileName().toString()))
                    .map(Path::getParent)
                    .findFirst().orElse(null);
        }
    }

    /** Extract the YAML {@code name:} value without a full YAML parse. */
    private static String extractNameFromFrontmatter(String body) {
        if (body == null || !body.startsWith("---")) return null;
        int end = body.indexOf("\n---", 3);
        if (end < 0) return null;
        String fm = body.substring(3, end);
        for (String line : fm.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("name:")) {
                String v = t.substring(5).trim();
                // strip surrounding quotes
                if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                if (v.startsWith("'") && v.endsWith("'")) v = v.substring(1, v.length() - 1);
                return v.isBlank() ? null : v;
            }
        }
        return null;
    }

    private static String safeFolderName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private static void moveDir(Path src, Path dst) throws IOException {
        // Move-by-copy to survive cross-filesystem moves (temp dir may be on a different drive).
        Files.createDirectories(dst);
        try (Stream<Path> walk = Files.walk(src)) {
            walk.forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path target = dst.resolve(rel);
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        deleteRecursive(src);
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException ignored) {}
            });
        }
    }
}
