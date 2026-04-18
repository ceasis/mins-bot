package com.minsbot.skills.queryexpander;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns fuzzy user input like "open my latest cv" into a structured breakdown:
 * intents, synonyms, file types, temporal hints, locations, quantity, action.
 *
 * <p>The LLM should call this early when a user request is vague, so it can
 * craft a concrete search/filter/tool invocation.</p>
 */
@Service
public class QueryExpanderService {

    // ── Noun synonyms / category → common extensions ──
    private static final Map<String, List<String>> NOUN_SYNONYMS = new LinkedHashMap<>();
    private static final Map<String, List<String>> NOUN_EXTENSIONS = new LinkedHashMap<>();
    static {
        put(NOUN_SYNONYMS,  "cv", "resume", "curriculum vitae", "curriculumvitae");
        put(NOUN_EXTENSIONS, "cv", "pdf", "docx", "doc", "rtf");

        put(NOUN_SYNONYMS,  "resume", "cv", "curriculum vitae");
        put(NOUN_EXTENSIONS, "resume", "pdf", "docx", "doc", "rtf");

        put(NOUN_SYNONYMS,  "photo", "image", "picture", "pic", "snapshot", "screenshot");
        put(NOUN_EXTENSIONS, "photo", "jpg", "jpeg", "png", "heic", "webp", "gif", "bmp", "tiff");

        put(NOUN_SYNONYMS,  "video", "clip", "movie", "recording", "film");
        put(NOUN_EXTENSIONS, "video", "mp4", "mov", "mkv", "avi", "webm", "wmv", "flv");

        put(NOUN_SYNONYMS,  "song", "music", "audio", "track", "mp3");
        put(NOUN_EXTENSIONS, "song", "mp3", "m4a", "wav", "flac", "ogg", "aac", "wma");

        put(NOUN_SYNONYMS,  "document", "doc", "docs", "text", "write-up", "report");
        put(NOUN_EXTENSIONS, "document", "pdf", "docx", "doc", "txt", "rtf", "odt", "md");

        put(NOUN_SYNONYMS,  "spreadsheet", "sheet", "excel", "workbook", "csv", "worksheet");
        put(NOUN_EXTENSIONS, "spreadsheet", "xlsx", "xls", "csv", "ods", "tsv");

        put(NOUN_SYNONYMS,  "presentation", "slides", "slideshow", "deck", "powerpoint", "ppt");
        put(NOUN_EXTENSIONS, "presentation", "pptx", "ppt", "odp", "key");

        put(NOUN_SYNONYMS,  "invoice", "bill", "receipt");
        put(NOUN_EXTENSIONS, "invoice", "pdf", "jpg", "png", "docx");

        put(NOUN_SYNONYMS,  "contract", "agreement", "nda", "terms");
        put(NOUN_EXTENSIONS, "contract", "pdf", "docx", "doc");

        put(NOUN_SYNONYMS,  "code", "source", "script", "program");
        put(NOUN_EXTENSIONS, "code", "java", "py", "js", "ts", "cpp", "c", "go", "rs", "kt");

        put(NOUN_SYNONYMS,  "note", "notes", "memo");
        put(NOUN_EXTENSIONS, "note", "md", "txt", "rtf", "one");

        put(NOUN_SYNONYMS,  "email", "mail", "message");
        put(NOUN_SYNONYMS,  "contact", "contacts", "address book", "rolodex");
        put(NOUN_SYNONYMS,  "calendar", "schedule", "agenda", "events", "appointments");
        put(NOUN_SYNONYMS,  "password", "pw", "credentials", "login");

        put(NOUN_SYNONYMS,  "project", "work", "repo", "repository");
    }

    // ── Temporal keywords → time intent ──
    private static final Map<String, String> TEMPORAL = new LinkedHashMap<>();
    static {
        TEMPORAL.put("latest", "sort-desc-by-modified");
        TEMPORAL.put("newest", "sort-desc-by-modified");
        TEMPORAL.put("most recent", "sort-desc-by-modified");
        TEMPORAL.put("recent", "sort-desc-by-modified");
        TEMPORAL.put("last", "sort-desc-by-modified");
        TEMPORAL.put("oldest", "sort-asc-by-modified");
        TEMPORAL.put("today", "filter-today");
        TEMPORAL.put("yesterday", "filter-yesterday");
        TEMPORAL.put("this week", "filter-this-week");
        TEMPORAL.put("last week", "filter-last-week");
        TEMPORAL.put("this month", "filter-this-month");
        TEMPORAL.put("last month", "filter-last-month");
        TEMPORAL.put("this year", "filter-this-year");
    }

    // ── Location hints ──
    private static final Map<String, String> LOCATIONS = new LinkedHashMap<>();
    static {
        LOCATIONS.put("desktop", "${USER_HOME}/Desktop");
        LOCATIONS.put("downloads", "${USER_HOME}/Downloads");
        LOCATIONS.put("documents", "${USER_HOME}/Documents");
        LOCATIONS.put("pictures", "${USER_HOME}/Pictures");
        LOCATIONS.put("videos", "${USER_HOME}/Videos");
        LOCATIONS.put("music", "${USER_HOME}/Music");
        LOCATIONS.put("home", "${USER_HOME}");
        LOCATIONS.put("onedrive", "${USER_HOME}/OneDrive");
        LOCATIONS.put("dropbox", "${USER_HOME}/Dropbox");
        LOCATIONS.put("gdrive", "${USER_HOME}/Google Drive");
        LOCATIONS.put("google drive", "${USER_HOME}/Google Drive");
        LOCATIONS.put("icloud", "${USER_HOME}/iCloudDrive");
    }

    // ── Action verbs → intent ──
    private static final Map<String, String> ACTIONS = new LinkedHashMap<>();
    static {
        ACTIONS.put("open", "open");
        ACTIONS.put("launch", "open");
        ACTIONS.put("run", "open");
        ACTIONS.put("show", "show");
        ACTIONS.put("display", "show");
        ACTIONS.put("read", "read");
        ACTIONS.put("view", "show");
        ACTIONS.put("find", "search");
        ACTIONS.put("search", "search");
        ACTIONS.put("locate", "search");
        ACTIONS.put("list", "list");
        ACTIONS.put("get", "fetch");
        ACTIONS.put("fetch", "fetch");
        ACTIONS.put("download", "download");
        ACTIONS.put("upload", "upload");
        ACTIONS.put("send", "send");
        ACTIONS.put("email", "send");
        ACTIONS.put("share", "share");
        ACTIONS.put("delete", "delete");
        ACTIONS.put("remove", "delete");
        ACTIONS.put("rename", "rename");
        ACTIONS.put("move", "move");
        ACTIONS.put("copy", "copy");
        ACTIONS.put("create", "create");
        ACTIONS.put("make", "create");
        ACTIONS.put("add", "create");
        ACTIONS.put("edit", "edit");
        ACTIONS.put("update", "edit");
        ACTIONS.put("summarize", "summarize");
        ACTIONS.put("translate", "translate");
        ACTIONS.put("convert", "convert");
    }

    // ── Quantity ──
    private static final Pattern QUANTITY_TOP = Pattern.compile("\\btop\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY_FIRST_N = Pattern.compile("\\bfirst\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY_LAST_N = Pattern.compile("\\blast\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);

    public Map<String, Object> expand(String query) {
        if (query == null) query = "";
        String original = query.trim();
        String lower = original.toLowerCase();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("originalQuery", original);

        // Action
        String action = null;
        for (Map.Entry<String, String> e : ACTIONS.entrySet()) {
            if (Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b").matcher(lower).find()) {
                action = e.getValue();
                break;
            }
        }
        if (action != null) out.put("action", action);

        // Detected nouns + their synonyms + file types
        List<String> detectedNouns = new ArrayList<>();
        Map<String, List<String>> synonymsOut = new LinkedHashMap<>();
        Set<String> extensions = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> e : NOUN_SYNONYMS.entrySet()) {
            String noun = e.getKey();
            if (Pattern.compile("\\b" + Pattern.quote(noun) + "s?\\b").matcher(lower).find()) {
                if (!detectedNouns.contains(noun)) {
                    detectedNouns.add(noun);
                    synonymsOut.put(noun, e.getValue());
                    List<String> exts = NOUN_EXTENSIONS.get(noun);
                    if (exts != null) extensions.addAll(exts);
                }
            }
        }
        out.put("nouns", detectedNouns);
        out.put("synonyms", synonymsOut);
        if (!extensions.isEmpty()) out.put("fileExtensions", extensions);

        // Build search patterns: e.g. "cv" → *cv*.pdf *cv*.docx *resume*.pdf ...
        if (!detectedNouns.isEmpty()) {
            List<String> patterns = new ArrayList<>();
            for (String noun : detectedNouns) {
                List<String> nounTerms = new ArrayList<>();
                nounTerms.add(noun);
                nounTerms.addAll(NOUN_SYNONYMS.getOrDefault(noun, List.of()));
                for (String term : nounTerms) {
                    if (extensions.isEmpty()) {
                        patterns.add("*" + term + "*");
                    } else {
                        for (String ext : extensions) patterns.add("*" + term + "*." + ext);
                    }
                }
            }
            out.put("suggestedGlobPatterns", patterns.stream().distinct().toList());
        }

        // Temporal
        List<Map<String, String>> temporal = new ArrayList<>();
        for (Map.Entry<String, String> e : TEMPORAL.entrySet()) {
            if (lower.contains(e.getKey())) {
                Map<String, String> t = new LinkedHashMap<>();
                t.put("keyword", e.getKey());
                t.put("intent", e.getValue());
                temporal.add(t);
            }
        }
        if (!temporal.isEmpty()) out.put("temporal", temporal);

        // Locations
        List<Map<String, String>> locations = new ArrayList<>();
        String userHome = System.getProperty("user.home", "");
        for (Map.Entry<String, String> e : LOCATIONS.entrySet()) {
            if (lower.contains(e.getKey())) {
                Map<String, String> l = new LinkedHashMap<>();
                l.put("keyword", e.getKey());
                l.put("path", e.getValue().replace("${USER_HOME}", userHome));
                locations.add(l);
            }
        }
        // Fallback: "my" = all likely user folders
        if (locations.isEmpty() && lower.matches(".*\\bmy\\b.*")) {
            locations.add(Map.of("keyword", "my (implicit)", "path", userHome + "/Documents"));
            locations.add(Map.of("keyword", "my (implicit)", "path", userHome + "/Downloads"));
            locations.add(Map.of("keyword", "my (implicit)", "path", userHome + "/Desktop"));
        }
        if (!locations.isEmpty()) out.put("locations", locations);

        // Quantity
        Map<String, Object> quantity = new LinkedHashMap<>();
        Matcher m;
        if ((m = QUANTITY_TOP.matcher(lower)).find())      quantity.put("top", Integer.parseInt(m.group(1)));
        if ((m = QUANTITY_FIRST_N.matcher(lower)).find())  quantity.put("first", Integer.parseInt(m.group(1)));
        if ((m = QUANTITY_LAST_N.matcher(lower)).find())   quantity.put("last", Integer.parseInt(m.group(1)));
        if (lower.matches(".*\\ball\\b.*"))                quantity.put("all", true);
        if (lower.matches(".*\\bone\\b.*|.*\\ba single\\b.*")) quantity.put("one", true);
        if (!quantity.isEmpty()) out.put("quantity", quantity);

        // Qualifiers
        List<String> qualifiers = new ArrayList<>();
        if (lower.matches(".*\\bmy\\b.*")) qualifiers.add("user-owned");
        if (lower.matches(".*\\bshared\\b.*")) qualifiers.add("shared");
        if (lower.matches(".*\\barchived\\b.*|.*\\bold\\b.*")) qualifiers.add("archived");
        if (lower.matches(".*\\bdraft\\b.*|.*\\bunsent\\b.*|.*\\bunread\\b.*")) qualifiers.add("draft/unread");
        if (!qualifiers.isEmpty()) out.put("qualifiers", qualifiers);

        // Suggested tool chain for the LLM
        out.put("suggestedToolChain", buildSuggestedChain(action, detectedNouns, temporal, locations));

        return out;
    }

    private List<String> buildSuggestedChain(String action, List<String> nouns,
                                             List<Map<String, String>> temporal, List<Map<String, String>> locations) {
        List<String> chain = new ArrayList<>();
        if (nouns.isEmpty()) return chain;
        if (locations != null && !locations.isEmpty()) {
            chain.add("fileSystemTools.search(basePath=<location>, pattern=<glob>) — searches for matching files");
        } else {
            chain.add("fileSystemTools.listHome — discover base paths");
        }
        if (temporal != null && temporal.stream().anyMatch(t -> t.get("intent").startsWith("sort"))) {
            chain.add("sort results by lastModified descending");
        }
        if ("open".equals(action)) {
            chain.add("systemTools.openFile(path) — open the top result in its default app");
        } else if ("show".equals(action) || "list".equals(action)) {
            chain.add("return the list of paths to the user");
        } else if ("read".equals(action)) {
            chain.add("fileTools.readFile(path) — read contents");
        } else if ("summarize".equals(action)) {
            chain.add("fileTools.readFile(path) → summarizationTools.summarize");
        }
        return chain;
    }

    private static void put(Map<String, List<String>> map, String key, String... values) {
        map.put(key, List.of(values));
    }
}
