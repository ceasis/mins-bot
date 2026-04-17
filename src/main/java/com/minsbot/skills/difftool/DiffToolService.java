package com.minsbot.skills.difftool;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DiffToolService {

    public Map<String, Object> lineDiff(String a, String b) {
        String[] ax = a == null ? new String[0] : a.split("\\r?\\n", -1);
        String[] bx = b == null ? new String[0] : b.split("\\r?\\n", -1);
        int[][] lcs = lcsLengths(ax, bx);
        List<Map<String, Object>> ops = new ArrayList<>();
        backtrack(ax, bx, lcs, ax.length, bx.length, ops);
        int added = 0, removed = 0, unchanged = 0;
        for (Map<String, Object> op : ops) {
            String k = String.valueOf(op.get("op"));
            if ("add".equals(k)) added++;
            else if ("remove".equals(k)) removed++;
            else unchanged++;
        }
        return Map.of("lines", ops, "added", added, "removed", removed, "unchanged", unchanged);
    }

    public String unifiedDiff(String a, String b, String fileA, String fileB) {
        String[] ax = a == null ? new String[0] : a.split("\\r?\\n", -1);
        String[] bx = b == null ? new String[0] : b.split("\\r?\\n", -1);
        int[][] lcs = lcsLengths(ax, bx);
        List<Map<String, Object>> ops = new ArrayList<>();
        backtrack(ax, bx, lcs, ax.length, bx.length, ops);

        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(fileA == null ? "a" : fileA).append("\n");
        sb.append("+++ ").append(fileB == null ? "b" : fileB).append("\n");
        int aLine = 1, bLine = 1;
        for (Map<String, Object> op : ops) {
            String k = (String) op.get("op");
            String text = (String) op.get("text");
            switch (k) {
                case "equal"  -> { sb.append(" ").append(text).append("\n"); aLine++; bLine++; }
                case "remove" -> { sb.append("-").append(text).append("\n"); aLine++; }
                case "add"    -> { sb.append("+").append(text).append("\n"); bLine++; }
            }
        }
        return sb.toString();
    }

    public Map<String, Object> similarity(String a, String b) {
        if (a == null) a = ""; if (b == null) b = "";
        int distance = levenshtein(a, b);
        int maxLen = Math.max(a.length(), b.length());
        double sim = maxLen == 0 ? 1.0 : 1.0 - (double) distance / maxLen;
        return Map.of("levenshteinDistance", distance, "similarity", Math.round(sim * 10000.0) / 10000.0);
    }

    private static int[][] lcsLengths(String[] a, String[] b) {
        int[][] dp = new int[a.length + 1][b.length + 1];
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                dp[i][j] = a[i - 1].equals(b[j - 1]) ? dp[i - 1][j - 1] + 1 : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return dp;
    }

    private static void backtrack(String[] a, String[] b, int[][] lcs, int i, int j, List<Map<String, Object>> ops) {
        if (i > 0 && j > 0 && a[i - 1].equals(b[j - 1])) {
            backtrack(a, b, lcs, i - 1, j - 1, ops);
            ops.add(Map.of("op", "equal", "text", a[i - 1]));
        } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
            backtrack(a, b, lcs, i, j - 1, ops);
            ops.add(Map.of("op", "add", "text", b[j - 1]));
        } else if (i > 0 && (j == 0 || lcs[i][j - 1] < lcs[i - 1][j])) {
            backtrack(a, b, lcs, i - 1, j, ops);
            ops.add(Map.of("op", "remove", "text", a[i - 1]));
        }
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }
}
