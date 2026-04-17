package com.minsbot.skills.abtestcalc;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AbTestCalcService {

    public Map<String, Object> significance(long visitorsA, long conversionsA, long visitorsB, long conversionsB) {
        if (visitorsA <= 0 || visitorsB <= 0) throw new IllegalArgumentException("visitors must be > 0");
        if (conversionsA < 0 || conversionsA > visitorsA) throw new IllegalArgumentException("conversionsA out of range");
        if (conversionsB < 0 || conversionsB > visitorsB) throw new IllegalArgumentException("conversionsB out of range");

        double pA = (double) conversionsA / visitorsA;
        double pB = (double) conversionsB / visitorsB;
        double lift = pA == 0 ? 0 : (pB - pA) / pA;

        // Pooled standard error (two-proportion z-test)
        double p = ((double) (conversionsA + conversionsB)) / (visitorsA + visitorsB);
        double se = Math.sqrt(p * (1 - p) * (1.0 / visitorsA + 1.0 / visitorsB));
        double z = se == 0 ? 0 : (pB - pA) / se;
        double pValue = 2 * (1 - normalCdf(Math.abs(z)));
        double confidence = 1 - pValue;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("variantA", Map.of(
                "visitors", visitorsA,
                "conversions", conversionsA,
                "rate", round(pA, 6),
                "ratePercent", round(pA * 100, 4)
        ));
        result.put("variantB", Map.of(
                "visitors", visitorsB,
                "conversions", conversionsB,
                "rate", round(pB, 6),
                "ratePercent", round(pB * 100, 4)
        ));
        result.put("liftPercent", round(lift * 100, 2));
        result.put("zScore", round(z, 4));
        result.put("pValue", round(pValue, 6));
        result.put("confidencePercent", round(confidence * 100, 2));
        result.put("significant95", confidence >= 0.95);
        result.put("significant99", confidence >= 0.99);
        result.put("winner", pValue < 0.05 ? (pB > pA ? "B" : "A") : "inconclusive");
        return result;
    }

    public Map<String, Object> sampleSize(double baselineRate, double minDetectableEffectPct, double confidencePct, double powerPct) {
        if (baselineRate <= 0 || baselineRate >= 1) throw new IllegalArgumentException("baselineRate must be between 0 and 1 (exclusive)");
        if (minDetectableEffectPct <= 0) throw new IllegalArgumentException("minDetectableEffect must be > 0");
        double confidence = confidencePct / 100.0;
        double power = powerPct / 100.0;
        double alpha = 1 - confidence;
        double zAlpha = inverseNormalCdf(1 - alpha / 2);
        double zBeta = inverseNormalCdf(power);

        double p1 = baselineRate;
        double p2 = baselineRate * (1 + minDetectableEffectPct / 100.0);
        double pBar = (p1 + p2) / 2;
        double numerator = Math.pow(zAlpha * Math.sqrt(2 * pBar * (1 - pBar))
                + zBeta * Math.sqrt(p1 * (1 - p1) + p2 * (1 - p2)), 2);
        double denominator = Math.pow(p2 - p1, 2);
        long perVariant = (long) Math.ceil(numerator / denominator);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baselineRate", baselineRate);
        result.put("minDetectableEffectPercent", minDetectableEffectPct);
        result.put("confidencePercent", confidencePct);
        result.put("powerPercent", powerPct);
        result.put("sampleSizePerVariant", perVariant);
        result.put("totalSampleSize", perVariant * 2);
        return result;
    }

    private static double normalCdf(double x) {
        return 0.5 * (1 + erf(x / Math.sqrt(2)));
    }

    // Abramowitz & Stegun 7.1.26 approximation
    private static double erf(double x) {
        double sign = x < 0 ? -1 : 1;
        x = Math.abs(x);
        double a1 =  0.254829592, a2 = -0.284496736, a3 =  1.421413741;
        double a4 = -1.453152027, a5 =  1.061405429, p  =  0.3275911;
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        return sign * y;
    }

    // Beasley-Springer-Moro approximation
    private static double inverseNormalCdf(double p) {
        if (p <= 0 || p >= 1) throw new IllegalArgumentException("p must be in (0,1)");
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double plow = 0.02425, phigh = 1 - plow;
        double q, r;
        if (p < plow) {
            q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1);
        }
        if (p <= phigh) {
            q = p - 0.5; r = q * q;
            return (((((a[0]*r+a[1])*r+a[2])*r+a[3])*r+a[4])*r+a[5])*q / (((((b[0]*r+b[1])*r+b[2])*r+b[3])*r+b[4])*r+1);
        }
        q = Math.sqrt(-2 * Math.log(1 - p));
        return -(((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1);
    }

    private static double round(double v, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(v * factor) / factor;
    }
}
