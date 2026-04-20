package com.minsbot;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/mouse-permission")
public class MousePermissionController {

    private final MousePermissionService service;

    public MousePermissionController(MousePermissionService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "decision", service.currentDecision().name(),
                "minutesRemaining", service.minutesRemaining()
        ));
    }

    @PostMapping("/grant")
    public ResponseEntity<?> grant(@RequestBody(required = false) Map<String, Object> body) {
        String choice = body != null ? String.valueOf(body.get("choice")) : "";
        switch (choice == null ? "" : choice.toLowerCase()) {
            case "today" -> service.allowToday();
            case "3h", "3hours", "three_hours" -> service.allowFor3Hours();
            case "deny", "no", "dontallow", "dont_allow" -> service.deny();
            case "clear" -> service.clear();
            default -> {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "choice must be 'today', '3h', 'deny', or 'clear'"));
            }
        }
        return ResponseEntity.ok(Map.of(
                "decision", service.currentDecision().name(),
                "minutesRemaining", service.minutesRemaining()
        ));
    }
}
