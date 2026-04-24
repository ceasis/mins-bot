package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Zero-Claude project scaffolder. Writes a complete, compiling starter
 * directly from hard-coded templates — instant, deterministic, always correct.
 * For common stacks, this beats the 90-second "delegate to Claude and hope
 * it doesn't punt" path hands down.
 */
@Component
public class ProjectTemplateTools {

    private static final Logger log = LoggerFactory.getLogger(ProjectTemplateTools.class);

    private final ToolExecutionNotifier notifier;
    private final ProjectHistoryService history;
    private final ProjectBootstrapService bootstrap;

    public ProjectTemplateTools(ToolExecutionNotifier notifier,
                                ProjectHistoryService history,
                                ProjectBootstrapService bootstrap) {
        this.notifier = notifier;
        this.history = history;
        this.bootstrap = bootstrap;
    }

    @Tool(description = "Scaffold a known project template directly (no Claude round-trip). "
            + "FAST alternative to createCodeWithClaude for common stacks. "
            + "Supported templates: spring-boot-tailwind (Java 17 + Spring Boot 3 + Tailwind CDN homepage). "
            + "Writes all files, runs git init, and pushes to GitHub if requested — "
            + "GitHub repos are PRIVATE by default, pass isPrivate=false only when the user asks for public. "
            + "Returns in seconds, not minutes.")
    public String createFromTemplate(
            @ToolParam(description = "Template id: 'spring-boot-tailwind'") String templateId,
            @ToolParam(description = "Project name (used as folder name and Maven artifactId)") String projectName,
            @ToolParam(description = "Absolute base directory where the project folder should be created") String baseDir,
            @ToolParam(description = "Also git-init and push to GitHub on success") boolean createGithub,
            @ToolParam(description = "Create the GitHub repo as PRIVATE. Default true. "
                    + "Pass false only when the user explicitly asks for a public repo.") boolean isPrivate) {
        try {
            if (templateId == null || templateId.isBlank()) return "Error: templateId is required.";
            if (projectName == null || projectName.isBlank()) return "Error: projectName is required.";
            if (baseDir == null || baseDir.isBlank()) return "Error: baseDir is required.";
            String safeName = projectName.trim().replaceAll("[^a-zA-Z0-9_-]", "-");
            Path dir = Path.of(baseDir, safeName);
            Files.createDirectories(dir);

            Map<String, String> files;
            switch (templateId.toLowerCase(Locale.ROOT).trim()) {
                case "spring-boot-tailwind":
                case "springboot-tailwind":
                case "spring-tailwind":
                    files = springBootTailwind(safeName);
                    break;
                case "spring-boot-jpa":
                case "spring-boot-thymeleaf-jpa":
                case "spring-jpa-h2":
                    files = springBootThymeleafJpaH2(safeName);
                    break;
                case "react-vite":
                case "vite-react":
                    files = reactVite(safeName);
                    break;
                case "fastapi":
                case "python-fastapi":
                    files = fastApi(safeName);
                    break;
                case "node-express":
                case "express":
                    files = nodeExpress(safeName);
                    break;
                default:
                    return "Unknown template: " + templateId + ". Known: "
                            + "spring-boot-tailwind, spring-boot-jpa, react-vite, fastapi, node-express.";
            }

            notifier.notify("Writing " + files.size() + " files from template " + templateId + "...");
            for (Map.Entry<String, String> e : files.entrySet()) {
                Path target = dir.resolve(e.getKey()).normalize();
                if (!target.startsWith(dir)) continue;
                Files.createDirectories(target.getParent());
                Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
            }
            log.info("[Template] Wrote {} files to {}", files.size(), dir);

            history.record(null, "template", "template=" + templateId, dir.toString(),
                    "done", "Scaffolded from template " + templateId);

            StringBuilder sb = new StringBuilder();
            sb.append("✓ Created ").append(safeName).append(" from template ")
              .append(templateId).append(" at ").append(dir).append('\n');
            sb.append("Files: ").append(files.size()).append('\n');
            sb.append("Run:   mvn spring-boot:run (or use startDevServer)").append('\n');
            sb.append("Open:  http://localhost:8080/");

            if (createGithub) {
                String bs = bootstrap.bootstrap(dir, isPrivate);
                if (bs != null && !bs.isBlank()) sb.append('\n').append(bs.trim());
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[Template] failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "List available project templates with a short description of each.")
    public String listTemplates() {
        return "Available project templates:\n"
             + "  • spring-boot-tailwind — Java 17 + Spring Boot 3.2 + Tailwind CDN homepage + /api/hello.\n"
             + "  • spring-boot-jpa     — Java 17 + Spring Boot 3.2 + Thymeleaf + Spring Data JPA + H2 in-memory DB.\n"
             + "  • react-vite          — React 18 + Vite + TypeScript + Tailwind starter.\n"
             + "  • fastapi             — Python 3 + FastAPI + Uvicorn with /hello and /hello/{name}.\n"
             + "  • node-express        — Node + Express with /api/hello and /api/hello/:name.\n"
             + "\n"
             + "Usage: createFromTemplate(templateId, projectName, baseDir, createGithub, isPrivate)";
    }

    // ═══════════════════════════════════════════════════════════════════
    // Templates
    // ═══════════════════════════════════════════════════════════════════

    private Map<String, String> springBootTailwind(String name) {
        String pkg = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (pkg.isEmpty()) pkg = "app";
        String appClass = capitalize(pkg) + "Application";

        Map<String, String> files = new LinkedHashMap<>();

        files.put("pom.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <parent>\n" +
                "    <groupId>org.springframework.boot</groupId>\n" +
                "    <artifactId>spring-boot-starter-parent</artifactId>\n" +
                "    <version>3.2.0</version>\n" +
                "    <relativePath/>\n" +
                "  </parent>\n" +
                "  <groupId>com.example</groupId>\n" +
                "  <artifactId>" + name + "</artifactId>\n" +
                "  <version>0.0.1-SNAPSHOT</version>\n" +
                "  <properties><java.version>17</java.version></properties>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>org.springframework.boot</groupId>\n" +
                "      <artifactId>spring-boot-starter-web</artifactId>\n" +
                "    </dependency>\n" +
                "    <dependency>\n" +
                "      <groupId>org.springframework.boot</groupId>\n" +
                "      <artifactId>spring-boot-starter-test</artifactId>\n" +
                "      <scope>test</scope>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "  <build>\n" +
                "    <plugins>\n" +
                "      <plugin>\n" +
                "        <groupId>org.springframework.boot</groupId>\n" +
                "        <artifactId>spring-boot-maven-plugin</artifactId>\n" +
                "      </plugin>\n" +
                "    </plugins>\n" +
                "  </build>\n" +
                "</project>\n");

        files.put("src/main/java/com/example/" + pkg + "/" + appClass + ".java",
                "package com.example." + pkg + ";\n\n" +
                "import org.springframework.boot.SpringApplication;\n" +
                "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n" +
                "@SpringBootApplication\n" +
                "public class " + appClass + " {\n" +
                "    public static void main(String[] args) {\n" +
                "        SpringApplication.run(" + appClass + ".class, args);\n" +
                "    }\n" +
                "}\n");

        files.put("src/main/java/com/example/" + pkg + "/controller/HelloController.java",
                "package com.example." + pkg + ".controller;\n\n" +
                "import org.springframework.web.bind.annotation.GetMapping;\n" +
                "import org.springframework.web.bind.annotation.PathVariable;\n" +
                "import org.springframework.web.bind.annotation.RequestMapping;\n" +
                "import org.springframework.web.bind.annotation.RestController;\n\n" +
                "@RestController\n" +
                "@RequestMapping(\"/api\")\n" +
                "public class HelloController {\n" +
                "    @GetMapping(\"/hello\")\n" +
                "    public String hello() { return \"Hello from " + name + "!\"; }\n\n" +
                "    @GetMapping(\"/hello/{who}\")\n" +
                "    public String hello(@PathVariable String who) { return \"Hello, \" + who + \"!\"; }\n" +
                "}\n");

        files.put("src/main/resources/application.properties",
                "spring.application.name=" + name + "\n" +
                "server.port=8080\n");

        // static/index.html — this is the whole point of the template.
        files.put("src/main/resources/static/index.html", tailwindHomepage(name));

        files.put("src/test/java/com/example/" + pkg + "/" + appClass + "Tests.java",
                "package com.example." + pkg + ";\n\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "import org.springframework.boot.test.context.SpringBootTest;\n\n" +
                "@SpringBootTest\n" +
                "class " + appClass + "Tests {\n" +
                "    @Test void contextLoads() { }\n" +
                "}\n");

        files.put(".gitignore",
                "target/\n" +
                "build/\n" +
                ".idea/\n" +
                ".vscode/\n" +
                "*.class\n" +
                ".DS_Store\n" +
                "run.log\n" +
                "run.err\n");

        files.put("README.md",
                "# " + name + "\n\n" +
                "Java 17 · Spring Boot 3.2 · Tailwind CSS (via CDN)\n\n" +
                "## Run\n\n" +
                "```\nmvn spring-boot:run\n```\n\n" +
                "Then open http://localhost:8080/\n\n" +
                "## Endpoints\n\n" +
                "- `GET /` — Tailwind-styled landing page\n" +
                "- `GET /api/hello` — text greeting\n" +
                "- `GET /api/hello/{who}` — personalized greeting\n");

        return files;
    }

    private String tailwindHomepage(String name) {
        return "<!doctype html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "  <meta charset=\"utf-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                "  <title>" + name + "</title>\n" +
                "  <script src=\"https://cdn.tailwindcss.com\"></script>\n" +
                "</head>\n" +
                "<body class=\"min-h-screen bg-gradient-to-br from-slate-900 via-slate-950 to-black text-slate-100\">\n" +
                "  <header class=\"max-w-5xl mx-auto px-6 pt-12 pb-8 flex items-center gap-3\">\n" +
                "    <div class=\"h-10 w-10 rounded-xl bg-indigo-500 grid place-items-center font-bold text-white\">" +
                capitalize(name).substring(0, 1) + "</div>\n" +
                "    <div>\n" +
                "      <h1 class=\"text-2xl font-semibold tracking-tight\">" + capitalize(name) + "</h1>\n" +
                "      <p class=\"text-sm text-slate-400\">Java 17 · Spring Boot 3 · Tailwind CSS</p>\n" +
                "    </div>\n" +
                "  </header>\n" +
                "  <main class=\"max-w-5xl mx-auto px-6 pb-16 space-y-10\">\n" +
                "    <section class=\"rounded-2xl border border-slate-800 bg-slate-900/60 backdrop-blur p-8 md:p-10\">\n" +
                "      <h2 class=\"text-3xl md:text-4xl font-semibold tracking-tight\">Hello from <span class=\"text-indigo-400\">" +
                capitalize(name) + "</span></h2>\n" +
                "      <p class=\"mt-3 text-slate-300 max-w-2xl\">A minimal Spring Boot starter with a REST API and a Tailwind-styled landing page. Try the live endpoint below.</p>\n" +
                "      <div class=\"mt-6 flex flex-wrap gap-3\">\n" +
                "        <a href=\"/api/hello\" class=\"inline-flex items-center gap-2 rounded-lg bg-indigo-500 hover:bg-indigo-600 transition px-4 py-2 text-sm font-medium\">GET /api/hello</a>\n" +
                "        <button id=\"pingBtn\" class=\"inline-flex items-center gap-2 rounded-lg border border-slate-700 hover:border-indigo-500 transition px-4 py-2 text-sm\">Call it from JS</button>\n" +
                "      </div>\n" +
                "      <pre id=\"pingOut\" class=\"mt-5 text-xs bg-slate-950/80 border border-slate-800 rounded-lg p-4 text-slate-300 whitespace-pre-wrap hidden\"></pre>\n" +
                "    </section>\n" +
                "    <section>\n" +
                "      <h3 class=\"text-lg font-medium mb-3\">API endpoints</h3>\n" +
                "      <ul class=\"grid md:grid-cols-2 gap-3\">\n" +
                "        <li class=\"rounded-xl border border-slate-800 bg-slate-900/40 p-4\"><div class=\"flex items-center gap-2\"><span class=\"text-xs font-mono bg-emerald-500/15 text-emerald-400 px-2 py-0.5 rounded\">GET</span><code class=\"text-sm\">/api/hello</code></div><p class=\"mt-2 text-sm text-slate-400\">Returns a static greeting string.</p></li>\n" +
                "        <li class=\"rounded-xl border border-slate-800 bg-slate-900/40 p-4\"><div class=\"flex items-center gap-2\"><span class=\"text-xs font-mono bg-emerald-500/15 text-emerald-400 px-2 py-0.5 rounded\">GET</span><code class=\"text-sm\">/api/hello/{who}</code></div><p class=\"mt-2 text-sm text-slate-400\">Returns a personalized greeting.</p></li>\n" +
                "      </ul>\n" +
                "    </section>\n" +
                "  </main>\n" +
                "  <footer class=\"max-w-5xl mx-auto px-6 pb-10 text-xs text-slate-500\">Served by Spring Boot on port <span id=\"port\" class=\"font-mono\"></span></footer>\n" +
                "  <script>\n" +
                "    document.getElementById('port').textContent = location.port || '(default)';\n" +
                "    document.getElementById('pingBtn').addEventListener('click', async () => {\n" +
                "      const out = document.getElementById('pingOut');\n" +
                "      out.classList.remove('hidden');\n" +
                "      out.textContent = 'GET /api/hello ...';\n" +
                "      try { const r = await fetch('/api/hello'); out.textContent = 'Status: ' + r.status + '\\n\\n' + (await r.text()); }\n" +
                "      catch (e) { out.textContent = 'Error: ' + e; }\n" +
                "    });\n" +
                "  </script>\n" +
                "</body>\n" +
                "</html>\n";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ─── Template: spring-boot-jpa (Thymeleaf + Spring Data JPA + H2) ─────────
    private Map<String, String> springBootThymeleafJpaH2(String name) {
        String pkg = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (pkg.isEmpty()) pkg = "app";
        String appClass = capitalize(pkg) + "Application";
        Map<String, String> f = new LinkedHashMap<>();

        f.put("pom.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <parent>\n    <groupId>org.springframework.boot</groupId>\n" +
                "    <artifactId>spring-boot-starter-parent</artifactId>\n" +
                "    <version>3.2.0</version>\n    <relativePath/>\n  </parent>\n" +
                "  <groupId>com.example</groupId>\n  <artifactId>" + name + "</artifactId>\n" +
                "  <version>0.0.1-SNAPSHOT</version>\n" +
                "  <properties><java.version>17</java.version></properties>\n" +
                "  <dependencies>\n" +
                "    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>\n" +
                "    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-thymeleaf</artifactId></dependency>\n" +
                "    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>\n" +
                "    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>runtime</scope></dependency>\n" +
                "    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>\n" +
                "  </dependencies>\n" +
                "  <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>\n" +
                "</project>\n");

        f.put("src/main/java/com/example/" + pkg + "/" + appClass + ".java",
                "package com.example." + pkg + ";\n\n" +
                "import org.springframework.boot.SpringApplication;\n" +
                "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n" +
                "@SpringBootApplication\npublic class " + appClass + " {\n" +
                "    public static void main(String[] args) { SpringApplication.run(" + appClass + ".class, args); }\n}\n");

        f.put("src/main/java/com/example/" + pkg + "/model/Item.java",
                "package com.example." + pkg + ".model;\n\n" +
                "import jakarta.persistence.*;\n\n" +
                "@Entity\npublic class Item {\n" +
                "    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;\n" +
                "    private String name;\n" +
                "    public Item() {}\n" +
                "    public Item(String name) { this.name = name; }\n" +
                "    public Long getId() { return id; }\n" +
                "    public String getName() { return name; }\n" +
                "    public void setName(String name) { this.name = name; }\n" +
                "}\n");

        f.put("src/main/java/com/example/" + pkg + "/repo/ItemRepository.java",
                "package com.example." + pkg + ".repo;\n\n" +
                "import com.example." + pkg + ".model.Item;\n" +
                "import org.springframework.data.jpa.repository.JpaRepository;\n\n" +
                "public interface ItemRepository extends JpaRepository<Item, Long> {}\n");

        f.put("src/main/java/com/example/" + pkg + "/web/HomeController.java",
                "package com.example." + pkg + ".web;\n\n" +
                "import com.example." + pkg + ".model.Item;\n" +
                "import com.example." + pkg + ".repo.ItemRepository;\n" +
                "import org.springframework.stereotype.Controller;\n" +
                "import org.springframework.ui.Model;\n" +
                "import org.springframework.web.bind.annotation.*;\n\n" +
                "@Controller\npublic class HomeController {\n" +
                "    private final ItemRepository repo;\n" +
                "    public HomeController(ItemRepository repo) { this.repo = repo; }\n" +
                "    @GetMapping(\"/\")\n" +
                "    public String home(Model model) { model.addAttribute(\"items\", repo.findAll()); return \"index\"; }\n" +
                "    @PostMapping(\"/items\")\n" +
                "    public String add(@RequestParam String name) { repo.save(new Item(name)); return \"redirect:/\"; }\n" +
                "}\n");

        f.put("src/main/resources/templates/index.html",
                "<!doctype html>\n<html xmlns:th=\"http://www.thymeleaf.org\">\n<head>\n" +
                "  <meta charset=\"utf-8\"><title>" + capitalize(name) + "</title>\n" +
                "  <script src=\"https://cdn.tailwindcss.com\"></script>\n</head>\n" +
                "<body class=\"bg-slate-950 text-slate-100 min-h-screen\">\n" +
                "  <main class=\"max-w-2xl mx-auto p-8\">\n" +
                "    <h1 class=\"text-3xl font-semibold mb-6\">" + capitalize(name) + "</h1>\n" +
                "    <form method=\"post\" action=\"/items\" class=\"flex gap-2 mb-6\">\n" +
                "      <input name=\"name\" class=\"flex-1 px-3 py-2 rounded bg-slate-800 border border-slate-700\" placeholder=\"New item\" required>\n" +
                "      <button class=\"px-4 py-2 rounded bg-indigo-500 hover:bg-indigo-600\">Add</button>\n" +
                "    </form>\n" +
                "    <ul class=\"space-y-2\">\n" +
                "      <li th:each=\"i : ${items}\" class=\"p-3 rounded bg-slate-900 border border-slate-800\">\n" +
                "        <span th:text=\"${i.id}\" class=\"text-slate-500 text-xs mr-2\"></span>\n" +
                "        <span th:text=\"${i.name}\"></span>\n" +
                "      </li>\n" +
                "    </ul>\n  </main>\n</body>\n</html>\n");

        f.put("src/main/resources/application.properties",
                "spring.application.name=" + name + "\n" +
                "server.port=8080\n" +
                "spring.datasource.url=jdbc:h2:mem:" + pkg + "db\n" +
                "spring.jpa.hibernate.ddl-auto=update\n" +
                "spring.h2.console.enabled=true\n");

        f.put("src/test/java/com/example/" + pkg + "/" + appClass + "Tests.java",
                "package com.example." + pkg + ";\n\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "import org.springframework.boot.test.context.SpringBootTest;\n\n" +
                "@SpringBootTest\nclass " + appClass + "Tests { @Test void contextLoads() {} }\n");

        f.put(".gitignore", "target/\nbuild/\n.idea/\n.vscode/\n*.class\n.DS_Store\nrun.log\nrun.err\n");
        f.put("README.md",
                "# " + name + "\n\nSpring Boot 3 + Thymeleaf + JPA + H2\n\n" +
                "## Run\n\n```\nmvn spring-boot:run\n```\n\n" +
                "Open http://localhost:8080/ — add items via the form.\n" +
                "H2 console: http://localhost:8080/h2-console (JDBC: `jdbc:h2:mem:" + pkg + "db`)\n");
        return f;
    }

    // ─── Template: react-vite (React 18 + Vite + TypeScript + Tailwind) ──────
    private Map<String, String> reactVite(String name) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("package.json",
                "{\n" +
                "  \"name\": \"" + name + "\",\n" +
                "  \"private\": true,\n" +
                "  \"version\": \"0.0.1\",\n" +
                "  \"type\": \"module\",\n" +
                "  \"scripts\": {\n" +
                "    \"dev\": \"vite\",\n" +
                "    \"build\": \"tsc && vite build\",\n" +
                "    \"preview\": \"vite preview\"\n" +
                "  },\n" +
                "  \"dependencies\": {\n" +
                "    \"react\": \"^18.3.1\",\n" +
                "    \"react-dom\": \"^18.3.1\"\n" +
                "  },\n" +
                "  \"devDependencies\": {\n" +
                "    \"@types/react\": \"^18.3.3\",\n" +
                "    \"@types/react-dom\": \"^18.3.0\",\n" +
                "    \"@vitejs/plugin-react\": \"^4.3.1\",\n" +
                "    \"autoprefixer\": \"^10.4.19\",\n" +
                "    \"postcss\": \"^8.4.38\",\n" +
                "    \"tailwindcss\": \"^3.4.4\",\n" +
                "    \"typescript\": \"^5.4.5\",\n" +
                "    \"vite\": \"^5.3.1\"\n" +
                "  }\n" +
                "}\n");
        f.put("vite.config.ts",
                "import { defineConfig } from 'vite';\nimport react from '@vitejs/plugin-react';\n" +
                "export default defineConfig({ plugins: [react()], server: { port: 5173 } });\n");
        f.put("tsconfig.json",
                "{\n" +
                "  \"compilerOptions\": {\n" +
                "    \"target\": \"ES2020\",\n" +
                "    \"lib\": [\"ES2020\", \"DOM\", \"DOM.Iterable\"],\n" +
                "    \"jsx\": \"react-jsx\",\n" +
                "    \"module\": \"ESNext\",\n" +
                "    \"moduleResolution\": \"bundler\",\n" +
                "    \"strict\": true,\n" +
                "    \"esModuleInterop\": true,\n" +
                "    \"skipLibCheck\": true\n" +
                "  },\n" +
                "  \"include\": [\"src\"]\n" +
                "}\n");
        f.put("tailwind.config.js",
                "export default {\n" +
                "  content: ['./index.html', './src/**/*.{ts,tsx}'],\n" +
                "  theme: { extend: {} },\n" +
                "  plugins: []\n" +
                "};\n");
        f.put("postcss.config.js",
                "export default {\n  plugins: { tailwindcss: {}, autoprefixer: {} }\n};\n");
        f.put("index.html",
                "<!doctype html>\n<html lang=\"en\">\n<head>\n" +
                "  <meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                "  <title>" + capitalize(name) + "</title>\n</head>\n" +
                "<body class=\"bg-slate-950 text-slate-100\">\n  <div id=\"root\"></div>\n" +
                "  <script type=\"module\" src=\"/src/main.tsx\"></script>\n</body>\n</html>\n");
        f.put("src/main.tsx",
                "import React from 'react';\nimport ReactDOM from 'react-dom/client';\nimport App from './App';\nimport './index.css';\n\n" +
                "ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);\n");
        f.put("src/App.tsx",
                "import { useState } from 'react';\n\nexport default function App() {\n" +
                "  const [count, setCount] = useState(0);\n" +
                "  return (\n" +
                "    <main className=\"min-h-screen grid place-items-center\">\n" +
                "      <div className=\"max-w-md w-full p-8 rounded-2xl border border-slate-800 bg-slate-900/60\">\n" +
                "        <h1 className=\"text-3xl font-semibold mb-2\">" + capitalize(name) + "</h1>\n" +
                "        <p className=\"text-slate-400 mb-6\">React 18 + Vite + TypeScript + Tailwind.</p>\n" +
                "        <button className=\"px-4 py-2 rounded-lg bg-indigo-500 hover:bg-indigo-600\" onClick={() => setCount(c => c + 1)}>\n" +
                "          count is {count}\n        </button>\n      </div>\n    </main>\n  );\n}\n");
        f.put("src/index.css",
                "@tailwind base;\n@tailwind components;\n@tailwind utilities;\n" +
                "body { margin: 0; font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, sans-serif; }\n");
        f.put(".gitignore",
                "node_modules/\ndist/\n.vite/\n*.log\n.DS_Store\nrun.log\nrun.err\n");
        f.put("README.md",
                "# " + name + "\n\nReact + Vite + TypeScript + Tailwind\n\n" +
                "```\nnpm install\nnpm run dev\n```\n\nOpen http://localhost:5173/\n");
        return f;
    }

    // ─── Template: fastapi ───────────────────────────────────────────────────
    private Map<String, String> fastApi(String name) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("requirements.txt", "fastapi>=0.110\nuvicorn[standard]>=0.27\n");
        f.put("main.py",
                "from fastapi import FastAPI\n\n" +
                "app = FastAPI(title=\"" + name + "\")\n\n" +
                "@app.get(\"/\")\n" +
                "def root():\n" +
                "    return {\"app\": \"" + name + "\", \"ok\": True}\n\n" +
                "@app.get(\"/hello\")\n" +
                "def hello():\n" +
                "    return {\"message\": \"Hello from " + name + "!\"}\n\n" +
                "@app.get(\"/hello/{who}\")\n" +
                "def hello_name(who: str):\n" +
                "    return {\"message\": f\"Hello, {who}!\"}\n");
        f.put("app.py",
                "import uvicorn\n" +
                "if __name__ == \"__main__\":\n" +
                "    uvicorn.run(\"main:app\", host=\"0.0.0.0\", port=8000, reload=True)\n");
        f.put(".gitignore",
                "__pycache__/\n*.pyc\nvenv/\n.venv/\n.pytest_cache/\n*.egg-info/\nrun.log\nrun.err\n");
        f.put("README.md",
                "# " + name + "\n\nFastAPI + Uvicorn.\n\n```\n" +
                "python -m venv venv\nvenv\\Scripts\\activate  # (.\\venv/bin/activate on *nix)\n" +
                "pip install -r requirements.txt\nuvicorn main:app --reload\n```\n\n" +
                "Open http://localhost:8000/docs for the interactive Swagger UI.\n");
        return f;
    }

    // ─── Template: node-express ──────────────────────────────────────────────
    private Map<String, String> nodeExpress(String name) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("package.json",
                "{\n" +
                "  \"name\": \"" + name + "\",\n" +
                "  \"version\": \"0.0.1\",\n" +
                "  \"main\": \"server.js\",\n" +
                "  \"scripts\": {\n" +
                "    \"start\": \"node server.js\",\n" +
                "    \"dev\": \"node --watch server.js\"\n" +
                "  },\n" +
                "  \"dependencies\": { \"express\": \"^4.19.2\" }\n" +
                "}\n");
        f.put("server.js",
                "const express = require('express');\nconst app = express();\n" +
                "const port = process.env.PORT || 3000;\n\n" +
                "app.get('/', (req, res) => res.send('Hello from " + name + "! Try /api/hello'));\n" +
                "app.get('/api/hello', (req, res) => res.json({ message: 'Hello from " + name + "!' }));\n" +
                "app.get('/api/hello/:who', (req, res) => res.json({ message: `Hello, ${req.params.who}!` }));\n\n" +
                "app.listen(port, () => console.log(`" + name + " listening on http://localhost:${port}`));\n");
        f.put(".gitignore", "node_modules/\n*.log\n.DS_Store\nrun.log\nrun.err\n");
        f.put("README.md",
                "# " + name + "\n\nNode + Express.\n\n```\nnpm install\nnpm run dev\n```\n\n" +
                "Open http://localhost:3000/\n");
        return f;
    }
}
