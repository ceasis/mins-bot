package com.minsbot.skills.csvtools;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CsvToolsService {

    public Map<String, Object> describe(String csv, char delimiter) {
        List<List<String>> rows = parse(csv, delimiter);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowCount", Math.max(0, rows.size() - 1));
        out.put("hasHeader", !rows.isEmpty());
        if (!rows.isEmpty()) {
            out.put("columns", rows.get(0));
            out.put("columnCount", rows.get(0).size());
            out.put("sample", rows.subList(0, Math.min(6, rows.size())));
        }
        return out;
    }

    public Map<String, Object> extractColumn(String csv, String columnName, char delimiter) {
        List<List<String>> rows = parse(csv, delimiter);
        if (rows.isEmpty()) throw new IllegalArgumentException("empty csv");
        int idx = rows.get(0).indexOf(columnName);
        if (idx < 0) throw new IllegalArgumentException("column not found: " + columnName);
        List<String> values = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            values.add(idx < row.size() ? row.get(idx) : "");
        }
        return Map.of("column", columnName, "values", values, "count", values.size());
    }

    public Map<String, Object> filter(String csv, String columnName, String contains, char delimiter) {
        List<List<String>> rows = parse(csv, delimiter);
        if (rows.isEmpty()) throw new IllegalArgumentException("empty csv");
        int idx = rows.get(0).indexOf(columnName);
        if (idx < 0) throw new IllegalArgumentException("column not found: " + columnName);
        List<List<String>> matches = new ArrayList<>();
        matches.add(rows.get(0));
        String needle = contains.toLowerCase();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (idx < row.size() && row.get(idx).toLowerCase().contains(needle)) matches.add(row);
        }
        return Map.of("matched", matches.size() - 1, "rows", matches);
    }

    public Map<String, Object> toJson(String csv, char delimiter) {
        List<List<String>> rows = parse(csv, delimiter);
        if (rows.isEmpty()) return Map.of("records", List.of());
        List<String> headers = rows.get(0);
        List<Map<String, String>> records = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> obj = new LinkedHashMap<>();
            List<String> row = rows.get(i);
            for (int c = 0; c < headers.size(); c++) {
                obj.put(headers.get(c), c < row.size() ? row.get(c) : "");
            }
            records.add(obj);
        }
        return Map.of("records", records, "count", records.size());
    }

    private static List<List<String>> parse(String csv, char delimiter) {
        List<List<String>> out = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return out;
        int len = csv.length();
        int i = 0;
        while (i < len) {
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean inQuotes = false;
            while (i < len) {
                char c = csv.charAt(i);
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < len && csv.charAt(i + 1) == '"') { cell.append('"'); i += 2; continue; }
                        inQuotes = false; i++;
                    } else { cell.append(c); i++; }
                } else {
                    if (c == '"') { inQuotes = true; i++; }
                    else if (c == delimiter) { row.add(cell.toString()); cell.setLength(0); i++; }
                    else if (c == '\r') { i++; }
                    else if (c == '\n') { i++; break; }
                    else { cell.append(c); i++; }
                }
            }
            row.add(cell.toString());
            if (!(row.size() == 1 && row.get(0).isEmpty())) out.add(row);
        }
        return out;
    }
}
