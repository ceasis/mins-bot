package com.minsbot.skills.geodistance;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class GeoDistanceService {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    public Map<String, Object> haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double km = EARTH_RADIUS_KM * c;
        return Map.of(
                "from", Map.of("lat", lat1, "lon", lon1),
                "to", Map.of("lat", lat2, "lon", lon2),
                "distanceKm", round(km),
                "distanceMi", round(km * 0.621371),
                "distanceNmi", round(km * 0.539957),
                "bearingDeg", round(bearing(lat1, lon1, lat2, lon2))
        );
    }

    public Map<String, Object> destination(double lat, double lon, double bearingDeg, double distanceKm) {
        double angular = distanceKm / EARTH_RADIUS_KM;
        double brg = Math.toRadians(bearingDeg);
        double lat1 = Math.toRadians(lat);
        double lon1 = Math.toRadians(lon);
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(angular) + Math.cos(lat1) * Math.sin(angular) * Math.cos(brg));
        double lon2 = lon1 + Math.atan2(Math.sin(brg) * Math.sin(angular) * Math.cos(lat1),
                Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2));
        return Map.of("lat", round(Math.toDegrees(lat2)), "lon", round(Math.toDegrees(lon2)));
    }

    public Map<String, Object> midpoint(double lat1, double lon1, double lat2, double lon2) {
        double bx = Math.cos(Math.toRadians(lat2)) * Math.cos(Math.toRadians(lon2 - lon1));
        double by = Math.cos(Math.toRadians(lat2)) * Math.sin(Math.toRadians(lon2 - lon1));
        double lat3 = Math.atan2(
                Math.sin(Math.toRadians(lat1)) + Math.sin(Math.toRadians(lat2)),
                Math.sqrt((Math.cos(Math.toRadians(lat1)) + bx) * (Math.cos(Math.toRadians(lat1)) + bx) + by * by));
        double lon3 = Math.toRadians(lon1) + Math.atan2(by, Math.cos(Math.toRadians(lat1)) + bx);
        return Map.of("lat", round(Math.toDegrees(lat3)), "lon", round(Math.toDegrees(lon3)));
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private static double round(double v) { return Math.round(v * 100000.0) / 100000.0; }
}
