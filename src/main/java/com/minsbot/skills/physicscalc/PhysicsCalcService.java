package com.minsbot.skills.physicscalc;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PhysicsCalcService {

    public static final double G_EARTH = 9.80665;

    public Map<String, Object> kinematicVelocity(double u, double a, double t) {
        return Map.of("formula", "v = u + at", "u", u, "a", a, "t", t, "v", round(u + a * t));
    }

    public Map<String, Object> kinematicDisplacement(double u, double a, double t) {
        double s = u * t + 0.5 * a * t * t;
        return Map.of("formula", "s = ut + ½at²", "u", u, "a", a, "t", t, "s", round(s));
    }

    public Map<String, Object> kineticEnergy(double mass, double velocity) {
        double ke = 0.5 * mass * velocity * velocity;
        return Map.of("formula", "KE = ½mv²", "mass", mass, "velocity", velocity, "kineticEnergyJoules", round(ke));
    }

    public Map<String, Object> potentialEnergy(double mass, double height, double g) {
        double pe = mass * g * height;
        return Map.of("formula", "PE = mgh", "mass", mass, "height", height, "g", g, "potentialEnergyJoules", round(pe));
    }

    public Map<String, Object> force(double mass, double acceleration) {
        return Map.of("formula", "F = ma", "mass", mass, "acceleration", acceleration, "forceNewtons", round(mass * acceleration));
    }

    public Map<String, Object> power(double workJoules, double seconds) {
        if (seconds <= 0) throw new IllegalArgumentException("seconds > 0");
        return Map.of("formula", "P = W/t", "workJoules", workJoules, "seconds", seconds, "powerWatts", round(workJoules / seconds));
    }

    public Map<String, Object> pressure(double forceN, double areaM2) {
        if (areaM2 <= 0) throw new IllegalArgumentException("area > 0");
        return Map.of("formula", "P = F/A", "forceNewtons", forceN, "areaM2", areaM2, "pressurePa", round(forceN / areaM2));
    }

    public Map<String, Object> ohmsLaw(Double voltage, Double current, Double resistance) {
        int unknowns = 0;
        if (voltage == null) unknowns++;
        if (current == null) unknowns++;
        if (resistance == null) unknowns++;
        if (unknowns != 1) throw new IllegalArgumentException("provide exactly 2 of the 3 (V/I/R)");
        if (voltage == null) voltage = current * resistance;
        else if (current == null) current = voltage / resistance;
        else resistance = voltage / current;
        return Map.of("formula", "V = IR", "voltage", round(voltage), "current", round(current), "resistance", round(resistance), "powerWatts", round(voltage * current));
    }

    private static double round(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
}
