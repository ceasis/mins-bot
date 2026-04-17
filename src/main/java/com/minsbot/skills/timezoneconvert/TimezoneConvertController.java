package com.minsbot.skills.timezoneconvert;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/timezoneconvert")
public class TimezoneConvertController {
    private final TimezoneConvertService service;
    private final TimezoneConvertConfig.TimezoneConvertProperties properties;

    public TimezoneConvertController(TimezoneConvertService service, TimezoneConvertConfig.TimezoneConvertProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "timezoneconvert", "enabled", properties.isEnabled())); }

    @GetMapping("/convert")
    public ResponseEntity<?> convert(@RequestParam String dateTime, @RequestParam String from, @RequestParam String to) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.convert(dateTime, from, to)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/now")
    public ResponseEntity<?> now(@RequestParam(required = false) String zones) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.now(zones == null ? java.util.List.of("UTC") : Arrays.asList(zones.split(","))));
    }

    @GetMapping("/zones")
    public ResponseEntity<?> zones() {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(Map.of("zones", service.listZones()));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "TimezoneConvert skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
