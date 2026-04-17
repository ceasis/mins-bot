package com.minsbot.skills.citationformatter;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CitationFormatterService {

    public Map<String, Object> format(Map<String, String> source, String style) {
        String authors = authors(source.get("authors"));
        String year = source.getOrDefault("year", "n.d.");
        String title = source.getOrDefault("title", "");
        String publisher = source.getOrDefault("publisher", "");
        String journal = source.getOrDefault("journal", "");
        String volume = source.getOrDefault("volume", "");
        String issue = source.getOrDefault("issue", "");
        String pages = source.getOrDefault("pages", "");
        String url = source.getOrDefault("url", "");
        String city = source.getOrDefault("city", "");

        String result = switch (style.toUpperCase()) {
            case "APA" -> apa(authors, year, title, journal, volume, issue, pages, publisher, url);
            case "MLA" -> mla(authors, title, journal, volume, issue, year, pages, publisher, city, url);
            case "CHICAGO" -> chicago(authors, title, journal, volume, issue, year, pages, publisher, city, url);
            case "HARVARD" -> harvard(authors, year, title, journal, volume, issue, pages, publisher, city);
            case "IEEE" -> ieee(authors, title, journal, volume, issue, pages, year, publisher, city);
            default -> throw new IllegalArgumentException("Unknown style: " + style);
        };
        return Map.of("style", style.toUpperCase(), "citation", result);
    }

    private static String apa(String a, String y, String t, String j, String v, String i, String p, String pub, String u) {
        StringBuilder sb = new StringBuilder();
        if (!a.isEmpty()) sb.append(a).append(" ");
        sb.append("(").append(y).append("). ").append(t).append(".");
        if (!j.isEmpty()) {
            sb.append(" ").append(j);
            if (!v.isEmpty()) sb.append(", ").append(v);
            if (!i.isEmpty()) sb.append("(").append(i).append(")");
            if (!p.isEmpty()) sb.append(", ").append(p);
            sb.append(".");
        } else if (!pub.isEmpty()) sb.append(" ").append(pub).append(".");
        if (!u.isEmpty()) sb.append(" ").append(u);
        return sb.toString();
    }

    private static String mla(String a, String t, String j, String v, String i, String y, String p, String pub, String city, String u) {
        StringBuilder sb = new StringBuilder();
        if (!a.isEmpty()) sb.append(a).append(". ");
        sb.append("\"").append(t).append(".\"");
        if (!j.isEmpty()) {
            sb.append(" ").append(j);
            if (!v.isEmpty()) sb.append(", vol. ").append(v);
            if (!i.isEmpty()) sb.append(", no. ").append(i);
            sb.append(", ").append(y);
            if (!p.isEmpty()) sb.append(", pp. ").append(p);
            sb.append(".");
        } else {
            if (!city.isEmpty()) sb.append(" ").append(city).append(",");
            if (!pub.isEmpty()) sb.append(" ").append(pub).append(",");
            sb.append(" ").append(y).append(".");
        }
        if (!u.isEmpty()) sb.append(" ").append(u);
        return sb.toString();
    }

    private static String chicago(String a, String t, String j, String v, String i, String y, String p, String pub, String city, String u) {
        StringBuilder sb = new StringBuilder();
        if (!a.isEmpty()) sb.append(a).append(". ");
        if (!j.isEmpty()) {
            sb.append("\"").append(t).append(".\" ");
            sb.append(j);
            if (!v.isEmpty()) sb.append(" ").append(v);
            if (!i.isEmpty()) sb.append(", no. ").append(i);
            sb.append(" (").append(y).append(")");
            if (!p.isEmpty()) sb.append(": ").append(p);
            sb.append(".");
        } else {
            sb.append("\"").append(t).append(".\" ");
            if (!city.isEmpty()) sb.append(city).append(": ");
            if (!pub.isEmpty()) sb.append(pub).append(", ");
            sb.append(y).append(".");
        }
        if (!u.isEmpty()) sb.append(" ").append(u);
        return sb.toString();
    }

    private static String harvard(String a, String y, String t, String j, String v, String i, String p, String pub, String city) {
        StringBuilder sb = new StringBuilder();
        if (!a.isEmpty()) sb.append(a).append(" ");
        sb.append(y).append(", ").append(t).append(".");
        if (!j.isEmpty()) {
            sb.append(" ").append(j);
            if (!v.isEmpty()) sb.append(", vol. ").append(v);
            if (!i.isEmpty()) sb.append(", no. ").append(i);
            if (!p.isEmpty()) sb.append(", pp. ").append(p);
        } else {
            if (!city.isEmpty()) sb.append(" ").append(city).append(":");
            if (!pub.isEmpty()) sb.append(" ").append(pub);
        }
        return sb.append(".").toString();
    }

    private static String ieee(String a, String t, String j, String v, String i, String p, String y, String pub, String city) {
        StringBuilder sb = new StringBuilder();
        if (!a.isEmpty()) sb.append(a).append(", ");
        sb.append("\"").append(t).append(",\"");
        if (!j.isEmpty()) {
            sb.append(" ").append(j);
            if (!v.isEmpty()) sb.append(", vol. ").append(v);
            if (!i.isEmpty()) sb.append(", no. ").append(i);
            if (!p.isEmpty()) sb.append(", pp. ").append(p);
            sb.append(", ").append(y).append(".");
        } else {
            if (!city.isEmpty()) sb.append(" ").append(city).append(":");
            if (!pub.isEmpty()) sb.append(" ").append(pub).append(", ");
            sb.append(y).append(".");
        }
        return sb.toString();
    }

    private static String authors(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String[] parts = raw.split("\\s*;\\s*|\\s*,\\s*(?=[A-Z])");
        if (parts.length <= 1) return raw.trim();
        if (parts.length == 2) return parts[0].trim() + ", & " + parts[1].trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(i == parts.length - 1 ? ", & " : ", ");
            sb.append(parts[i].trim());
        }
        return sb.toString();
    }

    public List<String> supportedStyles() { return List.of("APA", "MLA", "CHICAGO", "HARVARD", "IEEE"); }
}
