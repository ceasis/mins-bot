package com.minsbot.skills.invoicegen;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates a styled HTML invoice and stores it on disk. Returns both
 * a per-line breakdown (totals, tax, grand total) and the rendered HTML.
 */
@Service
public class InvoiceGenService {

    private final InvoiceGenConfig.InvoiceGenProperties props;
    private Path dir;

    public InvoiceGenService(InvoiceGenConfig.InvoiceGenProperties props) { this.props = props; }

    @PostConstruct
    void init() throws IOException {
        dir = Paths.get(props.getStorageDir());
        Files.createDirectories(dir);
    }

    public Map<String, Object> generate(String invoiceNumber, Map<String, Object> from,
                                        Map<String, Object> client, List<Map<String, Object>> lineItems,
                                        Double taxPercent, LocalDate dueDate, String notes) throws IOException {
        if (invoiceNumber == null || invoiceNumber.isBlank())
            invoiceNumber = "INV-" + System.currentTimeMillis();
        if (from == null) from = Map.of();
        if (client == null) client = Map.of();
        if (lineItems == null) lineItems = List.of();
        if (taxPercent == null) taxPercent = 0.0;
        if (dueDate == null) dueDate = LocalDate.now().plusDays(14);

        double subtotal = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> li : lineItems) {
            double qty = ((Number) li.getOrDefault("qty", 1)).doubleValue();
            double rate = ((Number) li.getOrDefault("rate", 0)).doubleValue();
            double amount = qty * rate;
            subtotal += amount;
            Map<String, Object> r = new LinkedHashMap<>(li);
            r.put("amount", round(amount));
            rows.add(r);
        }
        double tax = round(subtotal * taxPercent / 100.0);
        double total = round(subtotal + tax);

        String html = renderHtml(invoiceNumber, from, client, rows, subtotal, taxPercent, tax, total,
                dueDate, notes);
        String safeName = invoiceNumber.replaceAll("[^A-Za-z0-9._-]", "_") + ".html";
        Path file = dir.resolve(safeName);
        Files.writeString(file, html);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "invoicegen");
        result.put("invoiceNumber", invoiceNumber);
        result.put("currency", props.getCurrency());
        result.put("subtotal", round(subtotal));
        result.put("taxPercent", taxPercent);
        result.put("tax", tax);
        result.put("total", total);
        result.put("dueDate", dueDate.toString());
        result.put("storedAt", file.toAbsolutePath().toString());
        result.put("html", html);
        result.put("lineItems", rows);
        return result;
    }

    public List<String> list() throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    private String renderHtml(String num, Map<String, Object> from, Map<String, Object> client,
                              List<Map<String, Object>> rows, double subtotal, double taxPct, double tax,
                              double total, LocalDate due, String notes) {
        String cur = props.getCurrency();
        StringBuilder lis = new StringBuilder();
        for (Map<String, Object> r : rows) {
            lis.append("<tr><td>").append(esc(String.valueOf(r.getOrDefault("description", ""))))
                    .append("</td><td>").append(r.getOrDefault("qty", 1))
                    .append("</td><td>").append(cur).append(" ").append(r.getOrDefault("rate", 0))
                    .append("</td><td>").append(cur).append(" ").append(r.get("amount"))
                    .append("</td></tr>\n");
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Invoice " + esc(num) + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:780px;margin:40px auto;padding:0 20px;color:#222}"
                + "h1{margin:0}.row{display:flex;justify-content:space-between;margin:24px 0}"
                + "table{width:100%;border-collapse:collapse;margin:24px 0}"
                + "th,td{padding:10px;border-bottom:1px solid #eee;text-align:left}"
                + ".totals{text-align:right;margin-top:24px}.totals div{margin:4px 0}"
                + ".grand{font-size:20px;font-weight:bold}.muted{color:#888;font-size:13px}</style></head><body>"
                + "<h1>Invoice " + esc(num) + "</h1>"
                + "<div class=\"muted\">Issued " + LocalDate.now().format(DateTimeFormatter.ISO_DATE)
                + " • Due " + due.format(DateTimeFormatter.ISO_DATE) + "</div>"
                + "<div class=\"row\"><div><strong>From</strong><br>" + addr(from) + "</div>"
                + "<div><strong>Bill to</strong><br>" + addr(client) + "</div></div>"
                + "<table><thead><tr><th>Description</th><th>Qty</th><th>Rate</th><th>Amount</th></tr></thead>"
                + "<tbody>" + lis + "</tbody></table>"
                + "<div class=\"totals\">"
                + "<div>Subtotal: " + cur + " " + subtotal + "</div>"
                + "<div>Tax (" + taxPct + "%): " + cur + " " + tax + "</div>"
                + "<div class=\"grand\">Total: " + cur + " " + total + "</div></div>"
                + (notes == null || notes.isBlank() ? "" : "<p class=\"muted\">" + esc(notes) + "</p>")
                + "</body></html>";
    }

    private static String addr(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        for (String k : List.of("name", "company", "address", "email", "phone")) {
            Object v = m.get(k);
            if (v != null && !v.toString().isBlank()) sb.append(esc(v.toString())).append("<br>");
        }
        return sb.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
