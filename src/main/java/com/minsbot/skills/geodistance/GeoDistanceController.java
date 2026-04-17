package com.minsbot.skills.geodistance;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/geodistance")
public class GeoDistanceController {
    private final GeoDistanceService service;
    private final GeoDistanceConfig.GeoDistanceProperties properties;
    public GeoDistanceController(GeoDistanceService service, GeoDistanceConfig.GeoDistanceProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "geodistance", "enabled", properties.isEnabled())); }

    @GetMapping("/between") public ResponseEntity<?> between(@RequestParam double lat1, @RequestParam double lon1, @RequestParam double lat2, @RequestParam double lon2) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.haversine(lat1, lon1, lat2, lon2));
    }

    @GetMapping("/destination") public ResponseEntity<?> destination(@RequestParam double lat, @RequestParam double lon, @RequestParam double bearing, @RequestParam double distanceKm) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.destination(lat, lon, bearing, distanceKm));
    }

    @GetMapping("/midpoint") public ResponseEntity<?> midpoint(@RequestParam double lat1, @RequestParam double lon1, @RequestParam double lat2, @RequestParam double lon2) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.midpoint(lat1, lon1, lat2, lon2));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "GeoDistance skill is disabled")); }
}
