package com.minsbot;

import com.minsbot.agent.tools.QuickNotesTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Direct HTTP endpoints for quick-capture from external scripts: mobile
 * shortcuts, OS hotkeys, browser bookmarklets. Skips the chat planner
 * — pure write-through to {@link QuickNotesTool}.
 */
@RestController
@RequestMapping("/api/notes")
public class NotesApiController {

    @Autowired private QuickNotesTool quickNotesTool;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, String> body) {
        String text = body == null ? null : body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "text required"));
        }
        String result = quickNotesTool.saveNote(text);
        return ResponseEntity.ok(Map.of("ok", true, "result", result));
    }

    @PostMapping(value = "/text", consumes = MediaType.TEXT_PLAIN_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> saveText(@RequestBody String text) {
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "text required"));
        }
        String result = quickNotesTool.saveNote(text);
        return ResponseEntity.ok(Map.of("ok", true, "result", result));
    }

    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        String result = quickNotesTool.deleteNote(id);
        boolean ok = result != null && result.startsWith("Deleted");
        return ResponseEntity.ok(Map.of("ok", ok, "result", result));
    }

    @PostMapping(value = "/{id}/pin", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> pin(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", true, "result", quickNotesTool.togglePin(id)));
    }
}
