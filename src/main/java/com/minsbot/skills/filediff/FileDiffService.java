package com.minsbot.skills.filediff;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Line-based diff between two files (LCS unified output) or two folders
 * (set diff: only-in-A, only-in-B, differing files by hash).
 */
@Service
public class FileDiffService {

    public Map<String, Object> diffFiles(String pathA, String pathB) throws IOException {
        Path a = Paths.get(pathA), b = Paths.get(pathB);
        if (!Files.isRegularFile(a)) throw new IllegalArgumentException("not a file: " + a);
        if (!Files.isRegularFile(b)) throw new IllegalArgumentException("not a file: " + b);
        List<String> la = Files.readAllLines(a);
        List<String> lb = Files.readAllLines(b);
        List<String> diff = unifiedDiff(la, lb);
        long added = diff.stream().filter(s -> s.startsWith("+ ")).count();
        long removed = diff.stream().filter(s -> s.startsWith("- ")).count();
        return Map.of("a", a.toAbsolutePath().toString(),
                "b", b.toAbsolutePath().toString(),
                "linesA", la.size(), "linesB", lb.size(),
                "added", added, "removed", removed,
                "identical", added == 0 && removed == 0,
                "diff", String.join("\n", diff));
    }

    public Map<String, Object> diffFolders(String pathA, String pathB) throws IOException {
        Path a = Paths.get(pathA), b = Paths.get(pathB);
        if (!Files.isDirectory(a)) throw new IllegalArgumentException("not a folder: " + a);
        if (!Files.isDirectory(b)) throw new IllegalArgumentException("not a folder: " + b);
        Map<String, Long> fa = walk(a);
        Map<String, Long> fb = walk(b);
        List<String> onlyInA = new ArrayList<>(), onlyInB = new ArrayList<>(), differ = new ArrayList<>(), same = new ArrayList<>();
        for (var e : fa.entrySet()) {
            if (!fb.containsKey(e.getKey())) onlyInA.add(e.getKey());
            else if (!Objects.equals(e.getValue(), fb.get(e.getKey()))) differ.add(e.getKey());
            else same.add(e.getKey());
        }
        for (var e : fb.entrySet()) if (!fa.containsKey(e.getKey())) onlyInB.add(e.getKey());
        return Map.of("a", a.toAbsolutePath().toString(), "b", b.toAbsolutePath().toString(),
                "onlyInA", onlyInA, "onlyInB", onlyInB,
                "differingSize", differ, "identicalSize", same.size());
    }

    private static Map<String, Long> walk(Path root) throws IOException {
        Map<String, Long> out = new HashMap<>();
        Files.walk(root).filter(Files::isRegularFile).forEach(p -> {
            try { out.put(root.relativize(p).toString().replace('\\', '/'), Files.size(p)); }
            catch (Exception ignored) {}
        });
        return out;
    }

    private static List<String> unifiedDiff(List<String> a, List<String> b) {
        // Simple LCS-based diff (Myers-light). For huge files this isn't optimal but works.
        int n = a.size(), m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--) {
                if (a.get(i).equals(b.get(j))) dp[i][j] = dp[i + 1][j + 1] + 1;
                else dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        List<String> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) { out.add("  " + a.get(i)); i++; j++; }
            else if (dp[i + 1][j] >= dp[i][j + 1]) { out.add("- " + a.get(i++)); }
            else out.add("+ " + b.get(j++));
        }
        while (i < n) out.add("- " + a.get(i++));
        while (j < m) out.add("+ " + b.get(j++));
        if (out.size() > 500) {
            List<String> trimmed = new ArrayList<>(out.subList(0, 500));
            trimmed.add("... (truncated, " + (out.size() - 500) + " more lines)");
            return trimmed;
        }
        return out;
    }
}
