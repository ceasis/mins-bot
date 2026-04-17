package com.minsbot.skills.stockindicators;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class StockIndicatorsService {

    public List<Double> sma(List<Double> prices, int period) {
        if (period <= 0) throw new IllegalArgumentException("period > 0");
        List<Double> out = new ArrayList<>();
        double sum = 0;
        for (int i = 0; i < prices.size(); i++) {
            sum += prices.get(i);
            if (i >= period) sum -= prices.get(i - period);
            if (i >= period - 1) out.add(round(sum / period));
            else out.add(null);
        }
        return out;
    }

    public List<Double> ema(List<Double> prices, int period) {
        if (period <= 0) throw new IllegalArgumentException("period > 0");
        List<Double> out = new ArrayList<>();
        double k = 2.0 / (period + 1);
        Double prev = null;
        double seed = 0;
        for (int i = 0; i < prices.size(); i++) {
            double p = prices.get(i);
            if (i < period - 1) { seed += p; out.add(null); continue; }
            if (i == period - 1) { seed += p; prev = seed / period; out.add(round(prev)); continue; }
            prev = p * k + prev * (1 - k);
            out.add(round(prev));
        }
        return out;
    }

    public List<Double> rsi(List<Double> prices, int period) {
        if (prices.size() <= period) throw new IllegalArgumentException("need more prices than period");
        List<Double> out = new ArrayList<>();
        double gainAvg = 0, lossAvg = 0;
        for (int i = 1; i <= period; i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) gainAvg += change; else lossAvg -= change;
        }
        gainAvg /= period; lossAvg /= period;
        for (int i = 0; i < period; i++) out.add(null);
        out.add(rsiValue(gainAvg, lossAvg));
        for (int i = period + 1; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
            double gain = Math.max(0, change), loss = Math.max(0, -change);
            gainAvg = (gainAvg * (period - 1) + gain) / period;
            lossAvg = (lossAvg * (period - 1) + loss) / period;
            out.add(rsiValue(gainAvg, lossAvg));
        }
        return out;
    }

    public Map<String, Object> macd(List<Double> prices, int fast, int slow, int signal) {
        List<Double> emaFast = ema(prices, fast);
        List<Double> emaSlow = ema(prices, slow);
        List<Double> macdLine = new ArrayList<>();
        for (int i = 0; i < prices.size(); i++) {
            Double f = emaFast.get(i), s = emaSlow.get(i);
            macdLine.add((f == null || s == null) ? null : round(f - s));
        }
        List<Double> macdForSignal = new ArrayList<>();
        int firstValid = -1;
        for (int i = 0; i < macdLine.size(); i++) { if (macdLine.get(i) != null) { firstValid = i; break; } }
        if (firstValid < 0) return Map.of("macd", macdLine, "signal", List.of(), "histogram", List.of());
        List<Double> subset = new ArrayList<>();
        for (int i = firstValid; i < macdLine.size(); i++) subset.add(macdLine.get(i));
        List<Double> signalSub = ema(subset, signal);
        List<Double> signalLine = new ArrayList<>();
        for (int i = 0; i < firstValid; i++) signalLine.add(null);
        signalLine.addAll(signalSub);
        List<Double> histogram = new ArrayList<>();
        for (int i = 0; i < prices.size(); i++) {
            Double m = macdLine.get(i), sig = signalLine.get(i);
            histogram.add((m == null || sig == null) ? null : round(m - sig));
        }
        return Map.of("macd", macdLine, "signal", signalLine, "histogram", histogram);
    }

    private static double rsiValue(double gainAvg, double lossAvg) {
        if (lossAvg == 0) return 100.0;
        double rs = gainAvg / lossAvg;
        return round(100 - (100 / (1 + rs)));
    }

    private static double round(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
