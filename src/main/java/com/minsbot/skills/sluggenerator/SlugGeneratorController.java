package com.minsbot.skills.sluggenerator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/sluggenerator")
public class SlugGeneratorController {

    private final SlugGeneratorService service;
    private final SlugGeneratorConfig.SlugGeneratorProperties properties;

    public SlugGeneratorController(SlugGeneratorService service,
                                   SlugGeneratorConfig.SlugGeneratorProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "sluggenerator",
                "enabled", properties.isEnabled(),
                "maxInputChars", properties.getMaxInputChars(),
                "defaultMaxSlugLength", properties.getDefaultMaxSlugLength()
        ));
    }

    @GetMapping("/make")
    public ResponseEntity<?> make(@RequestParam String input,
                                  @RequestParam(defaultValue = "-") String separator,
                                  @RequestParam(defaultValue = "true") boolean lowercase,
                                  @RequestParam(defaultValue = "false") boolean stripStopwords,
                                  @RequestParam(required = false) Integer maxLength) {
        if (!properties.isEnabled()) return disabled();
        if (input.length() > properties.getMaxInputChars()) {
            return ResponseEntity.badRequest().body(Map.of("error", "input exceeds maxInputChars"));
        }
        int len = maxLength == null ? properties.getDefaultMaxSlugLength() : maxLength;
        String slug = service.slugify(input, separator, lowercase, stripStopwords, len);
        return ResponseEntity.ok(Map.of("input", input, "slug", slug));
    }

    @GetMapping("/variations")
    public ResponseEntity<?> variations(@RequestParam String input,
                                        @RequestParam(required = false) Integer maxLength) {
        if (!properties.isEnabled()) return disabled();
        if (input.length() > properties.getMaxInputChars()) {
            return ResponseEntity.badRequest().body(Map.of("error", "input exceeds maxInputChars"));
        }
        int len = maxLength == null ? properties.getDefaultMaxSlugLength() : maxLength;
        return ResponseEntity.ok(Map.of("input", input, "variations", service.variations(input, len)));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "SlugGenerator skill is disabled"));
    }
}
