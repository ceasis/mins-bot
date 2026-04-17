package com.minsbot.skills.matrixops;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/matrixops")
public class MatrixOpsController {
    private final MatrixOpsService service;
    private final MatrixOpsConfig.MatrixOpsProperties properties;
    public MatrixOpsController(MatrixOpsService service, MatrixOpsConfig.MatrixOpsProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "matrixops", "enabled", properties.isEnabled(), "maxDim", properties.getMaxDim())); }

    @PostMapping("/add") @SuppressWarnings("unchecked") public ResponseEntity<?> add(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try { double[][] a = service.fromList((List<List<Number>>) body.get("a")); double[][] b = service.fromList((List<List<Number>>) body.get("b")); return ResponseEntity.ok(Map.of("result", service.add(a, b))); } catch (Exception e) { return bad(e); }
    }

    @PostMapping("/subtract") @SuppressWarnings("unchecked") public ResponseEntity<?> subtract(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try { double[][] a = service.fromList((List<List<Number>>) body.get("a")); double[][] b = service.fromList((List<List<Number>>) body.get("b")); return ResponseEntity.ok(Map.of("result", service.subtract(a, b))); } catch (Exception e) { return bad(e); }
    }

    @PostMapping("/multiply") @SuppressWarnings("unchecked") public ResponseEntity<?> multiply(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try { double[][] a = service.fromList((List<List<Number>>) body.get("a")); double[][] b = service.fromList((List<List<Number>>) body.get("b")); return ResponseEntity.ok(Map.of("result", service.multiply(a, b))); } catch (Exception e) { return bad(e); }
    }

    @PostMapping("/transpose") @SuppressWarnings("unchecked") public ResponseEntity<?> transpose(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try { double[][] a = service.fromList((List<List<Number>>) body.get("matrix")); return ResponseEntity.ok(Map.of("result", service.transpose(a))); } catch (Exception e) { return bad(e); }
    }

    @PostMapping("/determinant") @SuppressWarnings("unchecked") public ResponseEntity<?> determinant(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try { double[][] a = service.fromList((List<List<Number>>) body.get("matrix")); return ResponseEntity.ok(Map.of("determinant", service.determinant(a))); } catch (Exception e) { return bad(e); }
    }

    @PostMapping("/scalar") @SuppressWarnings("unchecked") public ResponseEntity<?> scalar(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try { double[][] a = service.fromList((List<List<Number>>) body.get("matrix")); double s = ((Number) body.get("scalar")).doubleValue(); return ResponseEntity.ok(Map.of("result", service.scalarMultiply(a, s))); } catch (Exception e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "MatrixOps skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
