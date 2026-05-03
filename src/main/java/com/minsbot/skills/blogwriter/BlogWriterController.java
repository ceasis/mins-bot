package com.minsbot.skills.blogwriter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/blogwriter")
public class BlogWriterController {

    private final BlogWriterService service;
    private final BlogWriterConfig.BlogWriterProperties props;

    public BlogWriterController(BlogWriterService service, BlogWriterConfig.BlogWriterProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "blogwriter", "enabled", props.isEnabled(),
                "storageDir", props.getStorageDir(),
                "purpose", "Draft markdown article skeletons (frontmatter + outline + FAQ + CTA)"));
    }

    @PostMapping("/draft")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> draft(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "blogwriter skill is disabled"));
        try {
            String pk = (String) body.get("primaryKeyword");
            List<String> sk = (List<String>) body.getOrDefault("supportingKeywords", List.of());
            String aud = (String) body.get("audience");
            String cta = (String) body.get("productCta");
            List<String> links = (List<String>) body.getOrDefault("internalLinks", List.of());
            return ResponseEntity.ok(service.draft(pk, sk, aud, cta, links));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<?> list() {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "blogwriter skill is disabled"));
        try { return ResponseEntity.ok(Map.of("articles", service.list())); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
