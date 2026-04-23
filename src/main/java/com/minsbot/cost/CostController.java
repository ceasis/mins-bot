package com.minsbot.cost;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/cost")
public class CostController {

    private final TokenUsageService usage;

    public CostController(TokenUsageService usage) {
        this.usage = usage;
    }

    @GetMapping(value = "/current", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> current() {
        return ResponseEntity.ok(usage.currentSession());
    }

    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> history(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(Map.of(
                "days", Math.max(1, Math.min(365, days)),
                "daily", usage.dailyHistory(Math.max(1, Math.min(365, days)))
        ));
    }

    @PostMapping(value = "/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> reset() {
        usage.resetSession();
        return ResponseEntity.ok(Map.of("reset", true));
    }
}
