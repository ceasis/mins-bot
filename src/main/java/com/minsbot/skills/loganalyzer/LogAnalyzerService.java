package com.minsbot.skills.loganalyzer;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class LogAnalyzerService {

    private static final Pattern LEVEL = Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN(?:ING)?|ERROR|FATAL|SEVERE)\\b");
    private static final Pattern EXCEPTION = Pattern.compile("\\b([A-Z][A-Za-z0-9$]+(?:Exception|Error))\\b");
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[\\.,]?\\d*");

    public Map<String, Object> analyze(String logText) {
        if (logText == null) logText = "";
        String[] lines = logText.split("\\r?\\n");

        Map<String, Integer> levels = new LinkedHashMap<>();
        Map<String, Integer> exceptions = new LinkedHashMap<>();
        Map<String, Integer> templates = new HashMap<>();
        List<Map<String, Object>> errorSamples = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;

            java.util.regex.Matcher lv = LEVEL.matcher(line);
            String level = null;
            if (lv.find()) {
                level = lv.group(1).toUpperCase();
                if (level.equals("WARNING")) level = "WARN";
                if (level.equals("SEVERE")) level = "ERROR";
                levels.merge(level, 1, Integer::sum);
            }

            java.util.regex.Matcher ex = EXCEPTION.matcher(line);
            while (ex.find()) exceptions.merge(ex.group(1), 1, Integer::sum);

            // Template: normalize numbers, UUIDs, and quoted strings to <N>/<UUID>/<STR>
            String tpl = line
                    .replaceAll("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", "<UUID>")
                    .replaceAll("\\b\\d+\\b", "<N>")
                    .replaceAll("\"[^\"]*\"", "<STR>")
                    .replaceAll("'[^']*'", "<STR>");
            templates.merge(tpl, 1, Integer::sum);

            if ("ERROR".equals(level) || "FATAL".equals(level)) {
                if (errorSamples.size() < 10) errorSamples.add(Map.of("line", i + 1, "text", line.length() > 500 ? line.substring(0, 500) : line));
            }
        }

        List<Map.Entry<String, Integer>> topTemplates = templates.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10).toList();
        List<Map<String, Object>> topTplOut = new ArrayList<>();
        for (var e : topTemplates) topTplOut.add(Map.of("count", e.getValue(), "template", e.getKey()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalLines", lines.length);
        out.put("byLevel", levels);
        out.put("topExceptions", topByValue(exceptions, 10));
        out.put("topTemplates", topTplOut);
        out.put("errorSamples", errorSamples);
        return out;
    }

    private static List<Map<String, Object>> topByValue(Map<String, Integer> map, int n) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();
    }
}
