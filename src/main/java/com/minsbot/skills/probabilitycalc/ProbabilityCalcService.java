package com.minsbot.skills.probabilitycalc;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ProbabilityCalcService {

    public Map<String, Object> factorial(long n) {
        if (n < 0) throw new IllegalArgumentException("n >= 0");
        if (n > 20) throw new IllegalArgumentException("n <= 20 (use BigInteger for larger)");
        long f = 1;
        for (int i = 2; i <= n; i++) f *= i;
        return Map.of("n", n, "factorial", f);
    }

    public Map<String, Object> combinations(long n, long k) {
        if (n < 0 || k < 0 || k > n) throw new IllegalArgumentException("require 0 <= k <= n");
        long c = 1;
        k = Math.min(k, n - k);
        for (int i = 0; i < k; i++) { c *= (n - i); c /= (i + 1); }
        return Map.of("n", n, "k", k, "combinations", c);
    }

    public Map<String, Object> permutations(long n, long k) {
        if (n < 0 || k < 0 || k > n) throw new IllegalArgumentException("require 0 <= k <= n");
        long p = 1;
        for (int i = 0; i < k; i++) p *= (n - i);
        return Map.of("n", n, "k", k, "permutations", p);
    }

    public Map<String, Object> binomial(int n, int k, double p) {
        if (p < 0 || p > 1) throw new IllegalArgumentException("0 <= p <= 1");
        long c = (long) ((Number) combinations(n, k).get("combinations")).longValue();
        double pmf = c * Math.pow(p, k) * Math.pow(1 - p, n - k);
        double cdf = 0;
        for (int i = 0; i <= k; i++) {
            long ci = (long) ((Number) combinations(n, i).get("combinations")).longValue();
            cdf += ci * Math.pow(p, i) * Math.pow(1 - p, n - i);
        }
        return Map.of("n", n, "k", k, "p", p, "pmf", round(pmf), "cdf", round(cdf), "mean", n * p, "variance", n * p * (1 - p));
    }

    public Map<String, Object> normal(double x, double mean, double stdev) {
        if (stdev <= 0) throw new IllegalArgumentException("stdev > 0");
        double z = (x - mean) / stdev;
        double pdf = Math.exp(-0.5 * z * z) / (stdev * Math.sqrt(2 * Math.PI));
        double cdf = 0.5 * (1 + erf(z / Math.sqrt(2)));
        return Map.of("x", x, "mean", mean, "stdev", stdev, "zScore", round(z), "pdf", round(pdf), "cdf", round(cdf));
    }

    public Map<String, Object> poisson(int k, double lambda) {
        if (lambda < 0 || k < 0) throw new IllegalArgumentException("k >= 0, lambda >= 0");
        double pmf = Math.pow(lambda, k) * Math.exp(-lambda) / factL(k);
        double cdf = 0;
        for (int i = 0; i <= k; i++) cdf += Math.pow(lambda, i) * Math.exp(-lambda) / factL(i);
        return Map.of("k", k, "lambda", lambda, "pmf", round(pmf), "cdf", round(cdf));
    }

    private static double factL(int n) {
        double f = 1;
        for (int i = 2; i <= n; i++) f *= i;
        return f;
    }

    private static double erf(double x) {
        double sign = x < 0 ? -1 : 1;
        x = Math.abs(x);
        double a1 = 0.254829592, a2 = -0.284496736, a3 = 1.421413741;
        double a4 = -1.453152027, a5 = 1.061405429, p = 0.3275911;
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        return sign * y;
    }

    private static double round(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
}
