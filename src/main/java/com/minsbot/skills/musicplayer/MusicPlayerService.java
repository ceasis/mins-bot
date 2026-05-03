package com.minsbot.skills.musicplayer;

import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plays music by opening audio files in the OS default media player.
 * - searchAndPlay("query") walks the configured music library, picks best fuzzy match by filename.
 * - playRandom() picks a random track from the library.
 * - playPath(path) opens an explicit file.
 * - openYoutubeSearch("query") falls back to a YouTube search URL in the default browser.
 *
 * Pause/skip/etc are handled by the existing mediactl skill (OS media keys).
 */
@Service
public class MusicPlayerService {
    private final MusicPlayerConfig.MusicPlayerProperties props;
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private final Deque<String> history = new ArrayDeque<>();

    public MusicPlayerService(MusicPlayerConfig.MusicPlayerProperties props) { this.props = props; }

    public Map<String, Object> playPath(String path) throws Exception {
        File f = new File(path);
        if (!f.exists() || !f.isFile()) throw new IllegalArgumentException("not a file: " + path);
        openInDefaultPlayer(f);
        record(f.getAbsolutePath());
        return Map.of("ok", true, "playing", f.getAbsolutePath(), "via", "OS default media player");
    }

    public Map<String, Object> searchAndPlay(String query) throws Exception {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query required");
        List<Path> candidates = scanLibrary();
        if (candidates.isEmpty()) {
            if (props.isYoutubeFallback()) return openYoutubeSearch(query);
            return Map.of("ok", false, "error", "library is empty (configure app.skills.musicplayer.library-paths)");
        }
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        Path best = null; int bestScore = -1;
        for (Path p : candidates) {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            int score = 0;
            for (String t : terms) if (!t.isBlank() && n.contains(t)) score++;
            if (score > bestScore) { bestScore = score; best = p; }
        }
        if (best == null || bestScore <= 0) {
            if (props.isYoutubeFallback()) return openYoutubeSearch(query);
            return Map.of("ok", false, "error", "no library match for '" + query + "' (and YT fallback off)");
        }
        openInDefaultPlayer(best.toFile());
        record(best.toAbsolutePath().toString());
        return Map.of("ok", true, "matched", best.toAbsolutePath().toString(),
                "matchScore", bestScore, "candidatesScanned", candidates.size());
    }

    public Map<String, Object> playRandom() throws Exception {
        List<Path> candidates = scanLibrary();
        if (candidates.isEmpty()) throw new RuntimeException("library is empty (configure app.skills.musicplayer.library-paths or drop files in ~/Music)");
        Path pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        openInDefaultPlayer(pick.toFile());
        record(pick.toAbsolutePath().toString());
        return Map.of("ok", true, "playing", pick.toAbsolutePath().toString(),
                "pickedFrom", candidates.size() + " tracks");
    }

    public Map<String, Object> openYoutubeSearch(String query) throws Exception {
        String url = "https://music.youtube.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        try { Desktop.getDesktop().browse(URI.create(url)); }
        catch (Exception e) {
            String[] cmd = WIN ? new String[]{"cmd", "/c", "start", "", url}
                    : MAC ? new String[]{"open", url}
                    : new String[]{"xdg-open", url};
            new ProcessBuilder(cmd).start();
        }
        record("YT: " + query);
        return Map.of("ok", true, "openedYoutube", url, "query", query);
    }

    public Map<String, Object> listLibrary(int limit) throws Exception {
        List<Path> candidates = scanLibrary();
        List<String> sample = candidates.stream().limit(limit > 0 ? limit : 100)
                .map(p -> p.toAbsolutePath().toString()).toList();
        return Map.of("totalTracks", candidates.size(),
                "libraryPaths", effectiveLibraryPaths(),
                "sample", sample);
    }

    public Map<String, Object> recent() {
        return Map.of("history", new ArrayList<>(history));
    }

    private void record(String s) {
        history.addFirst(s);
        while (history.size() > 50) history.removeLast();
    }

    private void openInDefaultPlayer(File f) throws Exception {
        try { Desktop.getDesktop().open(f); }
        catch (Exception e) {
            String[] cmd = WIN ? new String[]{"cmd", "/c", "start", "", f.getAbsolutePath()}
                    : MAC ? new String[]{"open", f.getAbsolutePath()}
                    : new String[]{"xdg-open", f.getAbsolutePath()};
            new ProcessBuilder(cmd).start();
        }
    }

    private List<String> effectiveLibraryPaths() {
        if (props.getLibraryPaths() == null || props.getLibraryPaths().isEmpty()) {
            return List.of(System.getProperty("user.home") + File.separator + "Music");
        }
        return props.getLibraryPaths();
    }

    private List<Path> scanLibrary() throws Exception {
        Set<String> exts = new HashSet<>();
        for (String e : props.getAudioExtensions()) exts.add(e.toLowerCase(Locale.ROOT));
        List<Path> all = new ArrayList<>();
        for (String s : effectiveLibraryPaths()) {
            Path root = Paths.get(s);
            if (!Files.isDirectory(root)) continue;
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 8, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    String name = f.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    if (dot > 0 && exts.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) all.add(f);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, java.io.IOException e) { return FileVisitResult.CONTINUE; }
            });
        }
        return all;
    }
}
