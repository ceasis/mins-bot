package com.minsbot.skills.dockerfilelint;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DockerfileLintService {

    public Map<String, Object> lint(String dockerfile) {
        List<Map<String, Object>> issues = new ArrayList<>();
        if (dockerfile == null || dockerfile.isBlank()) {
            issues.add(Map.of("rule", "empty", "severity", "error", "line", 0, "message", "Dockerfile is empty"));
            return Map.of("issues", issues);
        }

        String[] lines = dockerfile.split("\\r?\\n");
        boolean hasFrom = false;
        boolean fromTagged = false;
        boolean runsAsRoot = true;
        boolean hasHealthcheck = false;
        Set<String> stageAliases = new LinkedHashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String lower = line.toLowerCase();
            int ln = i + 1;

            if (lower.startsWith("from ")) {
                hasFrom = true;
                String image = line.substring(5).trim();
                int asIdx = image.toLowerCase().indexOf(" as ");
                if (asIdx > 0) {
                    stageAliases.add(image.substring(asIdx + 4).trim());
                    image = image.substring(0, asIdx).trim();
                }
                if (image.endsWith(":latest") || !image.contains(":") && !image.contains("@sha256:")) {
                    if (image.endsWith(":latest")) {
                        issues.add(issue("DL3007", "warning", ln, "Avoid `:latest` tag — pin a version"));
                    } else {
                        issues.add(issue("DL3006", "warning", ln, "FROM image has no tag — pin a version"));
                    }
                } else {
                    fromTagged = true;
                }
            } else if (lower.startsWith("run ")) {
                if (lower.contains("apt-get upgrade") || lower.contains("apt upgrade")) {
                    issues.add(issue("DL3005", "error", ln, "Do not use `apt-get upgrade` in RUN"));
                }
                if (lower.contains("apt-get install") && !lower.contains("apt-get update")) {
                    issues.add(issue("DL3009", "warning", ln, "Use `apt-get update && apt-get install` in same RUN"));
                }
                if (lower.contains("apt-get install") && !lower.contains("--no-install-recommends")) {
                    issues.add(issue("DL3015", "info", ln, "Consider `--no-install-recommends` to reduce image size"));
                }
                if (lower.contains("sudo ")) {
                    issues.add(issue("DL3004", "warning", ln, "Do not use sudo — it doesn't make sense in Docker"));
                }
                if (lower.contains("pip install") && !lower.contains("--no-cache-dir")) {
                    issues.add(issue("DL3042", "info", ln, "Add `--no-cache-dir` to pip install"));
                }
            } else if (lower.startsWith("user ")) {
                String user = line.substring(5).trim();
                if (!user.equalsIgnoreCase("root") && !user.equals("0")) runsAsRoot = false;
            } else if (lower.startsWith("add ")) {
                issues.add(issue("DL3020", "warning", ln, "Use COPY instead of ADD unless you need ADD's auto-extract"));
            } else if (lower.startsWith("copy ")) {
                if (!line.contains("--chown") && line.split("\\s+").length >= 3) {
                    issues.add(issue("DL3045", "info", ln, "Consider --chown flag for COPY"));
                }
            } else if (lower.startsWith("healthcheck ")) {
                hasHealthcheck = true;
            } else if (lower.startsWith("workdir ")) {
                String path = line.substring(8).trim();
                if (!path.startsWith("/")) {
                    issues.add(issue("DL3000", "warning", ln, "WORKDIR should be an absolute path"));
                }
            } else if (lower.startsWith("expose ")) {
                for (String p : line.substring(7).trim().split("\\s+")) {
                    try {
                        int port = Integer.parseInt(p.replace("/tcp", "").replace("/udp", ""));
                        if (port < 1 || port > 65535) issues.add(issue("DL3011", "error", ln, "EXPOSE port out of range: " + port));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (!hasFrom) issues.add(issue("DL3000", "error", 0, "Missing FROM instruction"));
        if (hasFrom && fromTagged && runsAsRoot) issues.add(issue("DL3002", "warning", 0, "Container runs as root — add USER"));
        if (!hasHealthcheck) issues.add(issue("DL3057", "info", 0, "No HEALTHCHECK instruction"));

        return Map.of(
                "issues", issues,
                "stages", stageAliases,
                "totalIssues", issues.size()
        );
    }

    private static Map<String, Object> issue(String rule, String severity, int line, String msg) {
        return Map.of("rule", rule, "severity", severity, "line", line, "message", msg);
    }
}
