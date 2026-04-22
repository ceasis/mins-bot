package com.minsbot.diagnostics;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private final DiagnosticsService diagnostics;

    public DiagnosticsController(DiagnosticsService diagnostics) {
        this.diagnostics = diagnostics;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> runAll() {
        return ResponseEntity.ok(diagnostics.runAll());
    }
}
