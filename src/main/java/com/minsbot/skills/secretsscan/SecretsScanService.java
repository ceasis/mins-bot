package com.minsbot.skills.secretsscan;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SecretsScanService {

    private record Rule(String id, String severity, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("aws-access-key-id",   "high",   Pattern.compile("\\b(AKIA|ASIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA|ASCA)[0-9A-Z]{16}\\b")),
            new Rule("aws-secret-access-key","high",  Pattern.compile("(?i)aws(.{0,20})?(secret|private)(.{0,20})?['\"]?([A-Za-z0-9/+=]{40})['\"]?")),
            new Rule("gcp-api-key",         "high",   Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
            new Rule("github-pat",          "high",   Pattern.compile("\\bghp_[A-Za-z0-9]{36,}\\b")),
            new Rule("github-oauth",        "high",   Pattern.compile("\\bgho_[A-Za-z0-9]{36,}\\b")),
            new Rule("github-app-token",    "high",   Pattern.compile("\\b(ghu|ghs)_[A-Za-z0-9]{36,}\\b")),
            new Rule("gitlab-pat",          "high",   Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}\\b")),
            new Rule("slack-token",         "high",   Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b")),
            new Rule("slack-webhook",       "medium", Pattern.compile("https://hooks\\.slack\\.com/services/T[A-Z0-9]+/B[A-Z0-9]+/[A-Za-z0-9]+")),
            new Rule("stripe-live-key",     "high",   Pattern.compile("\\bsk_live_[A-Za-z0-9]{24,}\\b")),
            new Rule("stripe-restricted",   "high",   Pattern.compile("\\brk_live_[A-Za-z0-9]{24,}\\b")),
            new Rule("openai-api-key",      "high",   Pattern.compile("\\bsk-[A-Za-z0-9_-]{32,}\\b")),
            new Rule("anthropic-api-key",   "high",   Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{80,}\\b")),
            new Rule("google-service-json", "high",   Pattern.compile("\"type\"\\s*:\\s*\"service_account\"")),
            new Rule("twilio-account-sid",  "medium", Pattern.compile("\\bAC[a-f0-9]{32}\\b")),
            new Rule("sendgrid-api-key",    "high",   Pattern.compile("\\bSG\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}\\b")),
            new Rule("mailgun-api-key",     "medium", Pattern.compile("\\bkey-[a-f0-9]{32}\\b")),
            new Rule("heroku-api-key",      "medium", Pattern.compile("\\b[hH]eroku[^\\n]{0,20}?[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\b")),
            new Rule("npm-token",           "high",   Pattern.compile("\\bnpm_[A-Za-z0-9]{36,}\\b")),
            new Rule("pypi-token",          "high",   Pattern.compile("\\bpypi-AgEIcHlwaS5vcmcCJ[A-Za-z0-9_-]+")),
            new Rule("jwt-token",           "low",    Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+\\b")),
            new Rule("private-key-pem",     "high",   Pattern.compile("-----BEGIN (RSA |DSA |EC |OPENSSH |PGP )?PRIVATE KEY-----")),
            new Rule("ssh-private-key",     "high",   Pattern.compile("-----BEGIN OPENSSH PRIVATE KEY-----")),
            new Rule("generic-api-key",     "low",    Pattern.compile("(?i)(api[_-]?key|apikey|access[_-]?token|auth[_-]?token)[\"'\\s:=]+[\"']?([A-Za-z0-9_\\-]{20,})[\"']?")),
            new Rule("basic-auth-url",      "medium", Pattern.compile("https?://[^\\s:@/]+:[^\\s@/]+@"))
    );

    public Map<String, Object> scan(String text, boolean redact, int redactKeep) {
        if (text == null) text = "";
        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Integer> byId = new LinkedHashMap<>();
        Map<String, Integer> bySeverity = new LinkedHashMap<>();

        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                String match = m.group();
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("ruleId", rule.id());
                f.put("severity", rule.severity());
                f.put("match", redact ? redact(match, redactKeep) : match);
                f.put("matchLength", match.length());
                f.put("start", m.start());
                f.put("end", m.end());
                f.put("line", lineOf(text, m.start()));
                findings.add(f);
                byId.merge(rule.id(), 1, Integer::sum);
                bySeverity.merge(rule.severity(), 1, Integer::sum);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scannedChars", text.length());
        result.put("totalFindings", findings.size());
        result.put("byRule", byId);
        result.put("bySeverity", bySeverity);
        result.put("findings", findings);
        return result;
    }

    private static String redact(String s, int keep) {
        if (s.length() <= keep * 2) return "***";
        return s.substring(0, keep) + "…" + s.substring(s.length() - keep);
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }
}
