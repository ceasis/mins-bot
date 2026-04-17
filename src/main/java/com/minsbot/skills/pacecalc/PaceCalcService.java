package com.minsbot.skills.pacecalc;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PaceCalcService {

    public Map<String, Object> fromTime(double distanceKm, int hours, int minutes, int seconds) {
        double totalSec = hours * 3600 + minutes * 60 + seconds;
        if (distanceKm <= 0 || totalSec <= 0) throw new IllegalArgumentException("distance and time required");
        double secPerKm = totalSec / distanceKm;
        double secPerMi = secPerKm / 0.621371;
        double kmh = distanceKm / (totalSec / 3600.0);
        return Map.of(
                "distanceKm", distanceKm,
                "totalTime", formatTime(totalSec),
                "pacePerKm", formatPace(secPerKm),
                "pacePerMi", formatPace(secPerMi),
                "speedKmH", round(kmh),
                "speedMiH", round(kmh * 0.621371)
        );
    }

    public Map<String, Object> fromPace(double distanceKm, int paceMin, int paceSec) {
        double secPerKm = paceMin * 60 + paceSec;
        if (distanceKm <= 0 || secPerKm <= 0) throw new IllegalArgumentException("distance and pace required");
        double totalSec = distanceKm * secPerKm;
        return Map.of(
                "distanceKm", distanceKm,
                "pacePerKm", formatPace(secPerKm),
                "estimatedFinishTime", formatTime(totalSec),
                "totalSeconds", round(totalSec)
        );
    }

    public Map<String, Object> splits(double distanceKm, int paceMin, int paceSec) {
        double secPerKm = paceMin * 60 + paceSec;
        int whole = (int) Math.floor(distanceKm);
        List<Map<String, Object>> splits = new ArrayList<>();
        for (int k = 1; k <= whole; k++) {
            splits.add(Map.of("km", k, "splitTime", formatTime(secPerKm), "cumulative", formatTime(secPerKm * k)));
        }
        double remainder = distanceKm - whole;
        if (remainder > 0.01) {
            splits.add(Map.of("km", round(distanceKm), "splitTime", formatTime(secPerKm * remainder), "cumulative", formatTime(secPerKm * distanceKm), "partial", true));
        }
        return Map.of("totalDistanceKm", distanceKm, "pacePerKm", formatPace(secPerKm), "splits", splits);
    }

    public Map<String, Object> paceConvert(int min, int sec, String from, String to) {
        double secPerUnit = min * 60 + sec;
        double secPerKm;
        if ("km".equalsIgnoreCase(from)) secPerKm = secPerUnit;
        else if ("mi".equalsIgnoreCase(from)) secPerKm = secPerUnit / 1.609344;
        else throw new IllegalArgumentException("from: km|mi");
        double result;
        if ("km".equalsIgnoreCase(to)) result = secPerKm;
        else if ("mi".equalsIgnoreCase(to)) result = secPerKm * 1.609344;
        else throw new IllegalArgumentException("to: km|mi");
        return Map.of("input", formatPace(secPerUnit) + "/" + from, "output", formatPace(result) + "/" + to);
    }

    private static String formatTime(double sec) {
        int s = (int) Math.round(sec);
        int h = s / 3600; s %= 3600;
        int m = s / 60; s %= 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private static String formatPace(double sec) {
        int s = (int) Math.round(sec);
        int m = s / 60;
        return String.format("%d:%02d", m, s % 60);
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
