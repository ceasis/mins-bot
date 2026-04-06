package com.minsbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.agent.AsyncMessageService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * REST controller for Workflow Builder, Prompt Templates, and Plugin Marketplace tabs.
 * Persists data as JSON files under ~/mins_bot_data/.
 */
@RestController
@RequestMapping("/api/tabs")
public class WorkflowController {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final AsyncMessageService asyncMessageService;

    public WorkflowController(AsyncMessageService asyncMessageService) {
        this.asyncMessageService = asyncMessageService;
    }

    private static final Path DATA_DIR = Paths.get(
            System.getProperty("user.home"), "mins_bot_data");
    private static final Path WORKFLOWS_FILE = DATA_DIR.resolve("workflows.json");
    private static final Path TEMPLATES_FILE = DATA_DIR.resolve("prompt_templates.json");
    private static final Path MARKETPLACE_FILE = DATA_DIR.resolve("marketplace_plugins.json");

    // ─── Workflows ───────────────────────────────────────────────────────

    @GetMapping("/workflows")
    public List<Map<String, Object>> getWorkflows() {
        List<Map<String, Object>> list = readJsonList(WORKFLOWS_FILE);
        if (list.isEmpty()) {
            // Seed with pre-built workflow templates
            list.addAll(getBuiltInWorkflows());
            writeJsonList(WORKFLOWS_FILE, list);
        }
        return list;
    }

    @GetMapping("/workflows/templates")
    public List<Map<String, Object>> getWorkflowTemplates() {
        return getBuiltInWorkflows();
    }

    private List<Map<String, Object>> getBuiltInWorkflows() {
        List<Map<String, Object>> templates = new ArrayList<>();

        templates.add(Map.of(
                "id", "tpl-briefing",
                "name", "Morning Briefing",
                "trigger", "every morning",
                "steps", List.of(
                        Map.of("action", "check email", "detail", "Fetch unread emails from Gmail"),
                        Map.of("action", "custom", "detail", "Fetch today's events from Google Calendar"),
                        Map.of("action", "custom", "detail", "Get weather forecast for my location"),
                        Map.of("action", "summarize", "detail", "Summarize emails, calendar, and weather into a concise briefing"),
                        Map.of("action", "speak aloud", "detail", "Read the briefing summary aloud")
                )
        ));

        templates.add(Map.of(
                "id", "tpl-research",
                "name", "Research and Compare Report",
                "trigger", "on command",
                "steps", List.of(
                        Map.of("action", "web search", "detail", "Search for first topic/provider data"),
                        Map.of("action", "web search", "detail", "Search for second topic/provider data"),
                        Map.of("action", "web search", "detail", "Search for third topic/provider data"),
                        Map.of("action", "custom", "detail", "Create Excel spreadsheet with comparison columns"),
                        Map.of("action", "custom", "detail", "Create PDF summary report on Desktop"),
                        Map.of("action", "speak aloud", "detail", "Speak the summary aloud")
                )
        ));

        templates.add(Map.of(
                "id", "tpl-endofday",
                "name", "End of Day Wrap-up",
                "trigger", "every evening",
                "steps", List.of(
                        Map.of("action", "custom", "detail", "Review completed todo items for today"),
                        Map.of("action", "check email", "detail", "Check for any urgent unread emails"),
                        Map.of("action", "custom", "detail", "Check tomorrow's calendar events"),
                        Map.of("action", "summarize", "detail", "Summarize what was done and what's coming tomorrow"),
                        Map.of("action", "speak aloud", "detail", "Read the wrap-up summary aloud")
                )
        ));

        return templates;
    }

    @PostMapping("/workflows")
    public Map<String, Object> saveWorkflow(@RequestBody Map<String, Object> workflow) {
        List<Map<String, Object>> list = readJsonList(WORKFLOWS_FILE);
        String id = (String) workflow.get("id");
        if (id == null || id.isBlank()) {
            workflow.put("id", UUID.randomUUID().toString().substring(0, 8));
            list.add(workflow);
        } else {
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                if (id.equals(list.get(i).get("id"))) {
                    list.set(i, workflow);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) list.add(workflow);
        }
        writeJsonList(WORKFLOWS_FILE, list);
        return workflow;
    }

    @DeleteMapping("/workflows/{id}")
    public Map<String, String> deleteWorkflow(@PathVariable String id) {
        List<Map<String, Object>> list = readJsonList(WORKFLOWS_FILE);
        list.removeIf(w -> id.equals(w.get("id")));
        writeJsonList(WORKFLOWS_FILE, list);
        return Map.of("result", "deleted");
    }

    @PostMapping("/workflows/{id}/run")
    public Map<String, String> runWorkflow(@PathVariable String id) {
        List<Map<String, Object>> list = readJsonList(WORKFLOWS_FILE);
        for (Map<String, Object> w : list) {
            if (id.equals(w.get("id"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> steps = (List<Map<String, Object>>) w.get("steps");
                if (steps == null || steps.isEmpty()) {
                    return Map.of("result", "Workflow has no steps");
                }
                // Build a combined prompt from the workflow steps
                StringBuilder prompt = new StringBuilder();
                prompt.append("Execute this workflow named \"").append(w.get("name")).append("\":\n");
                for (int i = 0; i < steps.size(); i++) {
                    Map<String, Object> step = steps.get(i);
                    prompt.append(i + 1).append(". ")
                          .append(step.getOrDefault("action", ""))
                          .append(": ").append(step.getOrDefault("detail", ""))
                          .append("\n");
                }
                asyncMessageService.push(prompt.toString().trim());
                return Map.of("result", "Workflow started");
            }
        }
        return Map.of("result", "Workflow not found");
    }

    // ─── Prompt Templates ────────────────────────────────────────────────

    @GetMapping("/templates")
    public List<Map<String, Object>> getTemplates() {
        return readJsonList(TEMPLATES_FILE);
    }

    @PostMapping("/templates")
    public Map<String, Object> saveTemplate(@RequestBody Map<String, Object> template) {
        List<Map<String, Object>> list = readJsonList(TEMPLATES_FILE);
        String id = (String) template.get("id");
        if (id == null || id.isBlank()) {
            template.put("id", UUID.randomUUID().toString().substring(0, 8));
            list.add(template);
        } else {
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                if (id.equals(list.get(i).get("id"))) {
                    list.set(i, template);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) list.add(template);
        }
        writeJsonList(TEMPLATES_FILE, list);
        return template;
    }

    @DeleteMapping("/templates/{id}")
    public Map<String, String> deleteTemplate(@PathVariable String id) {
        List<Map<String, Object>> list = readJsonList(TEMPLATES_FILE);
        list.removeIf(t -> id.equals(t.get("id")));
        writeJsonList(TEMPLATES_FILE, list);
        return Map.of("result", "deleted");
    }

    @PostMapping("/templates/{id}/use")
    public Map<String, String> useTemplate(@PathVariable String id,
                                           @RequestBody Map<String, String> variables) {
        List<Map<String, Object>> list = readJsonList(TEMPLATES_FILE);
        for (Map<String, Object> t : list) {
            if (id.equals(t.get("id"))) {
                String body = (String) t.getOrDefault("body", "");
                for (Map.Entry<String, String> e : variables.entrySet()) {
                    body = body.replace("{{" + e.getKey() + "}}", e.getValue());
                }
                return Map.of("result", body);
            }
        }
        return Map.of("result", "Template not found");
    }

    // ─── Plugin Marketplace ──────────────────────────────────────────────

    @GetMapping("/marketplace")
    public List<Map<String, Object>> getMarketplace() {
        // Combine built-in catalog with any user-published plugins
        List<Map<String, Object>> catalog = getBuiltInCatalog();
        List<Map<String, Object>> published = readJsonList(MARKETPLACE_FILE);
        // Merge published into catalog (avoid duplicates by id)
        Set<String> catalogIds = new HashSet<>();
        for (Map<String, Object> c : catalog) catalogIds.add((String) c.get("id"));
        for (Map<String, Object> p : published) {
            if (!catalogIds.contains(p.get("id"))) catalog.add(p);
        }
        return catalog;
    }

    @PostMapping("/marketplace/publish")
    public Map<String, Object> publishToMarketplace(@RequestBody Map<String, Object> plugin) {
        List<Map<String, Object>> list = readJsonList(MARKETPLACE_FILE);
        String id = (String) plugin.get("id");
        if (id == null || id.isBlank()) {
            plugin.put("id", "community-" + UUID.randomUUID().toString().substring(0, 8));
        }
        plugin.put("source", "community");
        plugin.put("publishedAt", System.currentTimeMillis());
        list.add(plugin);
        writeJsonList(MARKETPLACE_FILE, list);
        return plugin;
    }

    @PostMapping("/marketplace/{id}/install")
    public Map<String, String> installPlugin(@PathVariable String id) {
        // For community plugins with a jarUrl, this could download the jar.
        // For built-in entries, we note it as "installed" in the marketplace file.
        List<Map<String, Object>> list = readJsonList(MARKETPLACE_FILE);
        boolean found = false;
        for (Map<String, Object> p : list) {
            if (id.equals(p.get("id"))) {
                p.put("installed", true);
                found = true;
                break;
            }
        }
        if (!found) {
            // Check built-in catalog
            for (Map<String, Object> c : getBuiltInCatalog()) {
                if (id.equals(c.get("id"))) {
                    c.put("installed", true);
                    list.add(c);
                    found = true;
                    break;
                }
            }
        }
        if (found) writeJsonList(MARKETPLACE_FILE, list);
        return Map.of("result", found ? "installed" : "not found");
    }

    @PostMapping("/marketplace/{id}/uninstall")
    public Map<String, String> uninstallPlugin(@PathVariable String id) {
        List<Map<String, Object>> list = readJsonList(MARKETPLACE_FILE);
        for (Map<String, Object> p : list) {
            if (id.equals(p.get("id"))) {
                p.put("installed", false);
                break;
            }
        }
        writeJsonList(MARKETPLACE_FILE, list);
        return Map.of("result", "uninstalled");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private List<Map<String, Object>> readJsonList(Path file) {
        try {
            if (!Files.exists(file)) return new ArrayList<>();
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) return new ArrayList<>();
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void writeJsonList(Path file, List<Map<String, Object>> list) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    private List<Map<String, Object>> getBuiltInCatalog() {
        List<Map<String, Object>> catalog = new ArrayList<>();
        catalog.add(Map.of(
                "id", "builtin-diskscan",
                "name", "Disk Scanner",
                "description", "Scan and analyze files on your local drives",
                "author", "Mins Bot",
                "category", "Utilities",
                "source", "builtin",
                "installed", true
        ));
        catalog.add(Map.of(
                "id", "builtin-websearch",
                "name", "Web Search",
                "description", "Search the web using multiple engines",
                "author", "Mins Bot",
                "category", "Search",
                "source", "builtin",
                "installed", true
        ));
        catalog.add(Map.of(
                "id", "builtin-screenshot",
                "name", "Screenshot & Vision",
                "description", "Capture and analyze screen content with AI vision",
                "author", "Mins Bot",
                "category", "Vision",
                "source", "builtin",
                "installed", true
        ));
        catalog.add(Map.of(
                "id", "builtin-browser",
                "name", "Headless Browser",
                "description", "Automated web browsing with Playwright",
                "author", "Mins Bot",
                "category", "Automation",
                "source", "builtin",
                "installed", true
        ));
        catalog.add(Map.of(
                "id", "builtin-email",
                "name", "Email Integration",
                "description", "Send and manage emails via Gmail API",
                "author", "Mins Bot",
                "category", "Communication",
                "source", "builtin",
                "installed", true
        ));
        catalog.add(Map.of(
                "id", "community-weather",
                "name", "Weather Forecast",
                "description", "Get real-time weather data and forecasts for any location",
                "author", "Community",
                "category", "Utilities",
                "source", "catalog",
                "installed", false
        ));
        catalog.add(Map.of(
                "id", "community-stocks",
                "name", "Stock Tracker",
                "description", "Track stock prices, portfolios, and market data",
                "author", "Community",
                "category", "Finance",
                "source", "catalog",
                "installed", false
        ));
        catalog.add(Map.of(
                "id", "community-translator",
                "name", "Multi-Translator",
                "description", "Translate text between 100+ languages with multiple engines",
                "author", "Community",
                "category", "Language",
                "source", "catalog",
                "installed", false
        ));
        catalog.add(Map.of(
                "id", "community-coderunner",
                "name", "Code Runner",
                "description", "Execute code snippets in Python, JS, Java, and more",
                "author", "Community",
                "category", "Development",
                "source", "catalog",
                "installed", false
        ));
        catalog.add(Map.of(
                "id", "community-summarizer",
                "name", "Document Summarizer",
                "description", "Summarize PDFs, articles, and long documents with AI",
                "author", "Community",
                "category", "Productivity",
                "source", "catalog",
                "installed", false
        ));
        return catalog;
    }
}
