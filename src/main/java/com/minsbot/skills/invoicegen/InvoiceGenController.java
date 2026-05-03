package com.minsbot.skills.invoicegen;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/invoicegen")
public class InvoiceGenController {

    private final InvoiceGenService service;
    private final InvoiceGenConfig.InvoiceGenProperties props;

    public InvoiceGenController(InvoiceGenService service, InvoiceGenConfig.InvoiceGenProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "invoicegen", "enabled", props.isEnabled(),
                "storageDir", props.getStorageDir(), "currency", props.getCurrency(),
                "purpose", "Generate styled HTML invoices and store on disk"));
    }

    @PostMapping("/generate")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> generate(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "invoicegen skill is disabled"));
        try {
            String num = (String) body.get("invoiceNumber");
            Map<String, Object> from = (Map<String, Object>) body.getOrDefault("from", Map.of());
            Map<String, Object> client = (Map<String, Object>) body.getOrDefault("client", Map.of());
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.getOrDefault("lineItems", List.of());
            Double tax = body.get("taxPercent") instanceof Number n ? n.doubleValue() : null;
            String due = (String) body.get("dueDate");
            String notes = (String) body.get("notes");
            LocalDate dueDate = due == null ? null : LocalDate.parse(due);
            return ResponseEntity.ok(service.generate(num, from, client, items, tax, dueDate, notes));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<?> list() {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "invoicegen skill is disabled"));
        try { return ResponseEntity.ok(Map.of("invoices", service.list())); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
