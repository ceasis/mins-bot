# Skills

Skills are Mins Bot's main extension surface. Each skill is a self-contained Java sub-package that adds a capability — a calculator, a port killer, a marketing orchestrator — exposed as both a REST endpoint and (optionally) a chat tool the LLM can call.

---

## Anatomy of a skill

A skill is **3 files under `com.minsbot.skills.<name>`**:

```
src/main/java/com/minsbot/skills/<name>/
├── <Name>Config.java       — properties + Spring @Bean
├── <Name>Service.java      — the actual logic
└── <Name>Controller.java   — REST endpoints (/api/skills/<name>/...)
```

Plus a properties block in [src/main/resources/application.properties](../src/main/resources/application.properties):

```properties
app.skills.<name>.enabled=false
app.skills.<name>.<other-knob>=...
```

**Convention rule: skills must default to `enabled=false`.** The user opts in.

---

## Worked example — adding `wordcount`

A skill that counts words in a string. Trivial, but shows the full pattern.

### 1. `WordCountConfig.java`

```java
package com.minsbot.skills.wordcount;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WordCountConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.wordcount")
    public WordCountProperties wordCountProperties() { return new WordCountProperties(); }

    public static class WordCountProperties {
        private boolean enabled = false;
        private int maxInputBytes = 1_000_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getMaxInputBytes() { return maxInputBytes; }
        public void setMaxInputBytes(int v) { this.maxInputBytes = v; }
    }
}
```

### 2. `WordCountService.java`

```java
package com.minsbot.skills.wordcount;

import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class WordCountService {
    private final WordCountConfig.WordCountProperties props;
    public WordCountService(WordCountConfig.WordCountProperties props) { this.props = props; }

    public Map<String, Object> count(String text) {
        if (text == null) text = "";
        if (text.length() > props.getMaxInputBytes())
            throw new IllegalArgumentException("input too long");
        long words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        long chars = text.length();
        long lines = text.isBlank() ? 0 : text.split("\\R", -1).length;
        return Map.of("words", words, "characters", chars, "lines", lines);
    }
}
```

### 3. `WordCountController.java`

```java
package com.minsbot.skills.wordcount;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/wordcount")
public class WordCountController {
    private final WordCountService svc;
    private final WordCountConfig.WordCountProperties props;
    public WordCountController(WordCountService svc, WordCountConfig.WordCountProperties props) {
        this.svc = svc; this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "wordcount", "enabled", props.isEnabled()));
    }

    @PostMapping("/count")
    public ResponseEntity<?> count(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.count((String) body.getOrDefault("text", ""))); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
```

### 4. Add properties

```properties
# WordCount — count words/lines/chars in arbitrary text
app.skills.wordcount.enabled=false
app.skills.wordcount.max-input-bytes=1000000
```

### 5. Done

Spring auto-discovers the `@Configuration`, `@Service`, and `@RestController` classes via component scanning. Restart the bot, `POST http://localhost:8765/api/skills/wordcount/count {"text": "hello world"}` returns `{"words":2,"characters":11,"lines":1}`.

---

## Exposing the skill to the chat agent

REST endpoints alone aren't visible to the LLM. To make the bot's chat-driven agent able to call your skill, add a thin bridge under `com.minsbot.agent.tools`:

```java
package com.minsbot.agent.tools;

import com.minsbot.skills.wordcount.WordCountConfig;
import com.minsbot.skills.wordcount.WordCountService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WordCountTools {
    @Autowired(required = false) private WordCountService svc;
    @Autowired(required = false) private WordCountConfig.WordCountProperties props;

    @Tool(description = "Count words, lines, and characters in a string. "
            + "Use when the user says 'how many words is this', 'count words', 'wc this'.")
    public String countWords(@ToolParam(description = "Text to count") String text) {
        if (svc == null || props == null || !props.isEnabled()) return "wordcount disabled";
        var r = svc.count(text);
        return r.get("words") + " words · " + r.get("characters") + " chars · " + r.get("lines") + " lines";
    }
}
```

The Spring AI runtime picks up `@Tool`-annotated methods automatically. Restart the bot, say *"count words in 'hello world to mars'"* in chat — agent calls your tool.

---

## Patterns by skill type

### Skill that fetches a URL
Use `java.net.http.HttpClient`. Use `props.getTimeoutMs()` and `props.getMaxFetchBytes()` knobs (every fetch-y skill has them — see e.g. [`gighunter`](../src/main/java/com/minsbot/skills/gighunter/)).

### Skill that persists state
Write under `memory/<skillname>/` (relative to project working dir). Use `MemoryService` if your data fits the key-value pattern, or write your own JSON files. See [`outreachtracker`](../src/main/java/com/minsbot/skills/outreachtracker/) for the JSON-per-record pattern.

### Skill that needs an LLM call
Inject `ChatClient` (`@Autowired @Qualifier("chatClient") private ChatClient chatClient`). Call `chatClient.prompt().user(prompt).call().content()`. See [`blogwriter`](../src/main/java/com/minsbot/skills/blogwriter/), [`pageguide`-like tools](../src/main/java/com/minsbot/agent/tools/PageGuideTools.java).

### Skill that orchestrates other skills
Inject the other skills' Service classes with `@Autowired(required = false)`. See [`selfmarket`](../src/main/java/com/minsbot/skills/selfmarket/) for the canonical orchestrator pattern.

### Skill that runs a shell command
Use `ProcessBuilder` directly. Always:
- Use `redirectErrorStream(true)` so stderr is captured.
- Cap output (`StringBuilder` with size check).
- Provide a configurable `timeoutSec` and use `process.waitFor(timeout, TimeUnit.SECONDS)`.
- Document required external binaries in the property block (`# Requires nvidia-smi on PATH`).

See [`portkiller`](../src/main/java/com/minsbot/skills/portkiller/), [`networkdiag`](../src/main/java/com/minsbot/skills/networkdiag/).

### Skill that needs OS-specific code
Branch on `System.getProperty("os.name")` at the top of the service. See [`mediactl`](../src/main/java/com/minsbot/skills/mediactl/), [`servicectl`](../src/main/java/com/minsbot/skills/servicectl/).

---

## Dangerous skills — protected lists

If your skill could brick the bot or the user's machine, support a configurable protected list and refuse to act on items in it.

Examples:
- [`portkiller`](../src/main/java/com/minsbot/skills/portkiller/PortKillerConfig.java) — refuses to kill port `8765` (the bot itself) by default.
- [`processkiller`](../src/main/java/com/minsbot/skills/processkiller/ProcessKillerConfig.java) — refuses `java`, `javaw`, `system`, `explorer`, `csrss`, `winlogon`, `wininit`, `lsass`, `services`.
- [`powerctl`](../src/main/java/com/minsbot/skills/powerctl/PowerCtlConfig.java) — `allowShutdown=false` by default; user must explicitly opt in.

---

## Portability

Each skill should be **self-contained enough to copy into another Spring Boot project** with just the package + properties block. That means:
- No imports from other `com.minsbot.skills.*` packages (orchestrators are an exception — they explicitly depend on others).
- No tight coupling to `com.minsbot` core unless via injectable services.
- Properties prefix matches the package name.

This isn't enforced by the build, but it's the project convention.

---

## Where each piece is documented

| What | Where |
|---|---|
| Convention rules (this doc) | [SKILLS.md](SKILLS.md) ← you are here |
| Per-tool reference for the agent | [TOOLS.md](TOOLS.md) |
| Existing skill code | [`src/main/java/com/minsbot/skills/`](../src/main/java/com/minsbot/skills/) |
| Existing chat tool bridges | [`src/main/java/com/minsbot/agent/tools/`](../src/main/java/com/minsbot/agent/tools/) |
| Properties for every skill | [application.properties](../src/main/resources/application.properties) |

---

## FAQ

**Q: Do I need both a Service and a Controller?**
A: No — only `@Service` + chat-tool bridge if you don't need a REST endpoint. But controllers are cheap and let the user / external scripts hit the skill via `curl`.

**Q: Can a skill use the database?**
A: There's no database. Skills persist state via files in `memory/` or `~/mins_bot_data/<skill>/`. If you need real persistence, that's a redesign.

**Q: Can two skills share state?**
A: Yes — both inject the same `@Service`, or share via `MemoryService`. Avoid shared mutable singletons.

**Q: How do I make my skill turn on by default?**
A: Set `enabled=true` in `application.properties`. But: the convention is **off by default** to respect the user's opt-in. Only the safest, no-side-effect skills default-on (`charcounter`, `unitconvert`, etc.).
