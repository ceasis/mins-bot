package com.minsbot.skills.sqlformatter;

import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class SqlFormatterService {

    private static final Set<String> MAJOR = Set.of(
            "SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET",
            "INSERT INTO", "VALUES", "UPDATE", "SET", "DELETE FROM",
            "INNER JOIN", "LEFT JOIN", "RIGHT JOIN", "FULL JOIN", "CROSS JOIN", "JOIN",
            "UNION", "UNION ALL", "INTERSECT", "EXCEPT",
            "CREATE TABLE", "ALTER TABLE", "DROP TABLE", "CREATE INDEX", "DROP INDEX",
            "BEGIN", "COMMIT", "ROLLBACK", "WITH"
    );

    private static final Set<String> RESERVED = Set.of(
            "AND", "OR", "NOT", "IN", "NULL", "IS", "ON", "AS", "BY", "DESC", "ASC",
            "CASE", "WHEN", "THEN", "ELSE", "END", "DISTINCT", "ALL", "BETWEEN", "LIKE",
            "EXISTS", "INTERVAL"
    );

    public String format(String sql) {
        if (sql == null) return "";
        String normalized = sql.replaceAll("\\s+", " ").trim();
        StringBuilder out = new StringBuilder();
        int indent = 0;
        int i = 0;
        int n = normalized.length();

        while (i < n) {
            char c = normalized.charAt(i);
            if (c == '(') {
                out.append('(').append('\n');
                indent++;
                appendIndent(out, indent);
                i++;
                continue;
            }
            if (c == ')') {
                indent = Math.max(0, indent - 1);
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                appendIndent(out, indent);
                out.append(')');
                i++;
                continue;
            }
            if (c == ',' && indent > 0) {
                out.append(",\n");
                appendIndent(out, indent);
                i++;
                while (i < n && normalized.charAt(i) == ' ') i++;
                continue;
            }

            // Try to match a major keyword at position i
            String upper = normalized.substring(i).toUpperCase();
            String matched = null;
            for (String kw : MAJOR) {
                if (upper.startsWith(kw + " ") || upper.equals(kw)) {
                    matched = kw; break;
                }
            }
            if (matched != null) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                appendIndent(out, indent);
                out.append(matched).append(' ');
                i += matched.length();
                while (i < n && normalized.charAt(i) == ' ') i++;
                continue;
            }
            // Reserved: uppercase inline
            for (String kw : RESERVED) {
                if (upper.startsWith(kw + " ") || upper.equals(kw)) {
                    out.append(kw).append(' ');
                    i += kw.length();
                    while (i < n && normalized.charAt(i) == ' ') i++;
                    matched = kw;
                    break;
                }
            }
            if (matched == null) {
                out.append(c);
                i++;
            }
        }
        return out.toString().trim();
    }

    public String minify(String sql) {
        if (sql == null) return "";
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static void appendIndent(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
    }
}
