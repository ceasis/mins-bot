package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.csvtools.CsvToolsConfig;
import com.minsbot.skills.csvtools.CsvToolsService;
import com.minsbot.skills.difftool.DiffToolConfig;
import com.minsbot.skills.difftool.DiffToolService;
import com.minsbot.skills.dockerfilelint.DockerfileLintConfig;
import com.minsbot.skills.dockerfilelint.DockerfileLintService;
import com.minsbot.skills.fakedatagen.FakeDataGenConfig;
import com.minsbot.skills.fakedatagen.FakeDataGenService;
import com.minsbot.skills.httptester.HttpTesterConfig;
import com.minsbot.skills.httptester.HttpTesterService;
import com.minsbot.skills.loganalyzer.LogAnalyzerConfig;
import com.minsbot.skills.loganalyzer.LogAnalyzerService;
import com.minsbot.skills.markdowntools.MarkdownToolsConfig;
import com.minsbot.skills.markdowntools.MarkdownToolsService;
import com.minsbot.skills.regexinferrer.RegexInferrerConfig;
import com.minsbot.skills.regexinferrer.RegexInferrerService;
import com.minsbot.skills.sqlformatter.SqlFormatterConfig;
import com.minsbot.skills.sqlformatter.SqlFormatterService;
import com.minsbot.skills.yamltools.YamlToolsConfig;
import com.minsbot.skills.yamltools.YamlToolsService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class SkillDataToolsExtra {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolExecutionNotifier notifier;

    private final CsvToolsService csv; private final CsvToolsConfig.CsvToolsProperties csvProps;
    private final DiffToolService diff; private final DiffToolConfig.DiffToolProperties diffProps;
    private final YamlToolsService yaml; private final YamlToolsConfig.YamlToolsProperties yamlProps;
    private final SqlFormatterService sql; private final SqlFormatterConfig.SqlFormatterProperties sqlProps;
    private final MarkdownToolsService md; private final MarkdownToolsConfig.MarkdownToolsProperties mdProps;
    private final DockerfileLintService docker; private final DockerfileLintConfig.DockerfileLintProperties dockerProps;
    private final LogAnalyzerService logs; private final LogAnalyzerConfig.LogAnalyzerProperties logsProps;
    private final RegexInferrerService regexInfer; private final RegexInferrerConfig.RegexInferrerProperties regexProps;
    private final HttpTesterService http; private final HttpTesterConfig.HttpTesterProperties httpProps;
    private final FakeDataGenService fake; private final FakeDataGenConfig.FakeDataGenProperties fakeProps;

    public SkillDataToolsExtra(ToolExecutionNotifier notifier,
                               CsvToolsService csv, CsvToolsConfig.CsvToolsProperties csvProps,
                               DiffToolService diff, DiffToolConfig.DiffToolProperties diffProps,
                               YamlToolsService yaml, YamlToolsConfig.YamlToolsProperties yamlProps,
                               SqlFormatterService sql, SqlFormatterConfig.SqlFormatterProperties sqlProps,
                               MarkdownToolsService md, MarkdownToolsConfig.MarkdownToolsProperties mdProps,
                               DockerfileLintService docker, DockerfileLintConfig.DockerfileLintProperties dockerProps,
                               LogAnalyzerService logs, LogAnalyzerConfig.LogAnalyzerProperties logsProps,
                               RegexInferrerService regexInfer, RegexInferrerConfig.RegexInferrerProperties regexProps,
                               HttpTesterService http, HttpTesterConfig.HttpTesterProperties httpProps,
                               FakeDataGenService fake, FakeDataGenConfig.FakeDataGenProperties fakeProps) {
        this.notifier = notifier;
        this.csv = csv; this.csvProps = csvProps;
        this.diff = diff; this.diffProps = diffProps;
        this.yaml = yaml; this.yamlProps = yamlProps;
        this.sql = sql; this.sqlProps = sqlProps;
        this.md = md; this.mdProps = mdProps;
        this.docker = docker; this.dockerProps = dockerProps;
        this.logs = logs; this.logsProps = logsProps;
        this.regexInfer = regexInfer; this.regexProps = regexProps;
        this.http = http; this.httpProps = httpProps;
        this.fake = fake; this.fakeProps = fakeProps;
    }

    @Tool(description = "Describe a CSV (header, row count, sample). For extracting a column or filtering rows, use csvExtractColumn or csvFilter.")
    public String csvDescribe(@ToolParam(description = "CSV text") String csv,
                              @ToolParam(description = "Delimiter (default ',')") String delimiter) {
        if (!csvProps.isEnabled()) return disabled("csvtools");
        char d = (delimiter == null || delimiter.isEmpty()) ? ',' : delimiter.charAt(0);
        return toJson(this.csv.describe(csv, d));
    }

    @Tool(description = "Extract a column from CSV by header name.")
    public String csvExtractColumn(@ToolParam(description = "CSV text") String csv,
                                   @ToolParam(description = "Column name") String column,
                                   @ToolParam(description = "Delimiter (default ',')") String delimiter) {
        if (!csvProps.isEnabled()) return disabled("csvtools");
        char d = (delimiter == null || delimiter.isEmpty()) ? ',' : delimiter.charAt(0);
        try { return toJson(this.csv.extractColumn(csv, column, d)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Compute a unified diff between two texts. Returns patch-style output with + / - lines.")
    public String textDiff(@ToolParam(description = "Version A") String a,
                           @ToolParam(description = "Version B") String b) {
        if (!diffProps.isEnabled()) return disabled("difftool");
        return this.diff.unifiedDiff(a, b, "a", "b");
    }

    @Tool(description = "Compute similarity between two strings (Levenshtein distance + 0-1 similarity ratio).")
    public String stringSimilarity(@ToolParam(description = "String A") String a,
                                   @ToolParam(description = "String B") String b) {
        if (!diffProps.isEnabled()) return disabled("difftool");
        return toJson(this.diff.similarity(a, b));
    }

    @Tool(description = "Convert between YAML and JSON. Operation: 'yaml-to-json', 'json-to-yaml', 'validate-yaml', or 'pretty-yaml'.")
    public String yamlConvert(@ToolParam(description = "Input text") String input,
                              @ToolParam(description = "Operation") String operation) {
        if (!yamlProps.isEnabled()) return disabled("yamltools");
        try {
            return switch (operation.toLowerCase()) {
                case "yaml-to-json" -> this.yaml.yamlToJson(input);
                case "json-to-yaml" -> this.yaml.jsonToYaml(input);
                case "validate-yaml" -> toJson(this.yaml.validate(input));
                case "pretty-yaml" -> this.yaml.prettyPrint(input);
                default -> "Unknown operation: " + operation;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Pretty-print a SQL query with proper indentation and keyword casing.")
    public String sqlFormat(@ToolParam(description = "SQL query") String sql) {
        if (!sqlProps.isEnabled()) return disabled("sqlformatter");
        return this.sql.format(sql);
    }

    @Tool(description = "Generate a Markdown table of contents from the headings in a document.")
    public String markdownToc(@ToolParam(description = "Markdown text") String markdown,
                              @ToolParam(description = "Max heading depth (1-6, default 6)") double maxDepth) {
        if (!mdProps.isEnabled()) return disabled("markdowntools");
        int d = (int) maxDepth <= 0 ? 6 : (int) maxDepth;
        return toJson(this.md.toc(markdown, d));
    }

    @Tool(description = "Lint a Dockerfile for best-practice issues (versions, root user, apt-get patterns, healthcheck, etc.).")
    public String dockerfileLint(@ToolParam(description = "Dockerfile contents") String dockerfile) {
        if (!dockerProps.isEnabled()) return disabled("dockerfilelint");
        return toJson(this.docker.lint(dockerfile));
    }

    @Tool(description = "Analyze log text: levels, exceptions, top templates, error samples.")
    public String analyzeLogs(@ToolParam(description = "Log text") String log) {
        if (!logsProps.isEnabled()) return disabled("loganalyzer");
        return toJson(this.logs.analyze(log));
    }

    @Tool(description = "Given example strings, suggest a regex pattern that matches them all.")
    public String inferRegex(@ToolParam(description = "Comma-separated example strings") String examplesCsv) {
        if (!regexProps.isEnabled()) return disabled("regexinferrer");
        List<String> examples = Arrays.stream(examplesCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        try { return toJson(this.regexInfer.infer(examples)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Execute an HTTP request. Method: GET/POST/PUT/DELETE/PATCH. Body optional (empty string for GET). Respects app.skills.httptester.allowed-hosts if configured.")
    public String httpRequest(@ToolParam(description = "HTTP method") String method,
                              @ToolParam(description = "URL") String url,
                              @ToolParam(description = "Request body (empty string if none)") String body) {
        if (!httpProps.isEnabled()) return disabled("httptester");
        try { return toJson(this.http.execute(method, url, null, body)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Generate realistic test/fake data. Fields like: name, email, phone, company, address, city, zip, age, id, username. Pass comma-separated field names.")
    public String generateFakeData(@ToolParam(description = "Comma-separated field names") String fieldsCsv,
                                   @ToolParam(description = "How many rows (default 10)") double count) {
        if (!fakeProps.isEnabled()) return disabled("fakedatagen");
        List<String> fields = Arrays.stream(fieldsCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        int n = Math.max(1, (int) count);
        try { return toJson(this.fake.generate(fields, n, fakeProps.getMaxRows())); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String disabled(String name) { return "Skill '" + name + "' is disabled. Enable via app.skills." + name + ".enabled=true"; }
    private String toJson(Object obj) { try { return mapper.writeValueAsString(obj); } catch (Exception e) { return String.valueOf(obj); } }
}
