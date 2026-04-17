package com.minsbot.skills.unitconvert;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/unitconvert")
public class UnitConvertController {

    private final UnitConvertService service;
    private final UnitConvertConfig.UnitConvertProperties properties;

    public UnitConvertController(UnitConvertService service, UnitConvertConfig.UnitConvertProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "unitconvert",
                "enabled", properties.isEnabled()
        ));
    }

    @GetMapping("/categories")
    public ResponseEntity<?> categories() {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.categories());
    }

    @GetMapping("/convert")
    public ResponseEntity<?> convert(@RequestParam String category,
                                     @RequestParam double value,
                                     @RequestParam String from,
                                     @RequestParam String to) {
        if (!properties.isEnabled()) return disabled();
        try {
            double result = service.convert(category, value, from, to);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("category", category);
            response.put("input", Map.of("value", value, "unit", from));
            response.put("output", Map.of("value", result, "unit", to));
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "UnitConvert skill is disabled"));
    }
}
