package com.minsbot.skills.statsbasics;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class StatsBasicsService {

    public Map<String, Object> describe(List<Double> raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("values required");
        double[] arr = raw.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(arr);

        double sum = 0, min = arr[0], max = arr[arr.length - 1];
        for (double v : arr) sum += v;
        double mean = sum / arr.length;

        double sqSum = 0;
        for (double v : arr) sqSum += (v - mean) * (v - mean);
        double variance = arr.length > 1 ? sqSum / (arr.length - 1) : 0;
        double stdev = Math.sqrt(variance);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", arr.length);
        out.put("sum", round(sum));
        out.put("min", round(min));
        out.put("max", round(max));
        out.put("mean", round(mean));
        out.put("median", round(percentile(arr, 50)));
        out.put("mode", mode(arr));
        out.put("range", round(max - min));
        out.put("variance", round(variance));
        out.put("stdev", round(stdev));
        out.put("q1", round(percentile(arr, 25)));
        out.put("q3", round(percentile(arr, 75)));
        out.put("iqr", round(percentile(arr, 75) - percentile(arr, 25)));
        out.put("p90", round(percentile(arr, 90)));
        out.put("p95", round(percentile(arr, 95)));
        out.put("p99", round(percentile(arr, 99)));
        return out;
    }

    public Map<String, Object> correlation(List<Double> x, List<Double> y) {
        if (x == null || y == null || x.size() != y.size() || x.isEmpty()) {
            throw new IllegalArgumentException("x and y must be non-empty and same length");
        }
        int n = x.size();
        double sumX = 0, sumY = 0;
        for (int i = 0; i < n; i++) { sumX += x.get(i); sumY += y.get(i); }
        double mx = sumX / n, my = sumY / n;
        double num = 0, dx2 = 0, dy2 = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - mx, dy = y.get(i) - my;
            num += dx * dy; dx2 += dx * dx; dy2 += dy * dy;
        }
        double r = (dx2 == 0 || dy2 == 0) ? 0 : num / Math.sqrt(dx2 * dy2);
        double slope = dx2 == 0 ? 0 : num / dx2;
        double intercept = my - slope * mx;
        return Map.of(
                "n", n,
                "pearsonR", round(r),
                "rSquared", round(r * r),
                "regressionSlope", round(slope),
                "regressionIntercept", round(intercept)
        );
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        double pos = p / 100.0 * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    private static List<Double> mode(double[] arr) {
        Map<Double, Integer> count = new LinkedHashMap<>();
        for (double v : arr) count.merge(v, 1, Integer::sum);
        int maxCount = count.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (maxCount <= 1) return List.of();
        List<Double> modes = new ArrayList<>();
        for (Map.Entry<Double, Integer> e : count.entrySet()) if (e.getValue() == maxCount) modes.add(e.getKey());
        return modes;
    }

    private static double round(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
}
