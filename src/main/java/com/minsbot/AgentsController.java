package com.minsbot;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/agents")
public class AgentsController {

    private final BackgroundAgentService agentService;

    public AgentsController(BackgroundAgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> list() {
        return Map.of(
                "agents", agentService.listJobs(),
                "maxConcurrent", agentService.getMaxConcurrent(),
                "running", agentService.getRunningCount()
        );
    }

    @PostMapping(value = "/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> start(@RequestBody Map<String, String> body) {
        String mission = body != null ? body.get("mission") : null;
        try {
            String id = agentService.startAgent(mission);
            return Map.of("id", id, "status", "QUEUED");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> cancel(@PathVariable String id) {
        if (!agentService.cancelJob(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found or already finished.");
        }
        return Map.of("status", "ok");
    }

    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> delete(@PathVariable String id) {
        if (!agentService.removeJob(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found.");
        }
        return Map.of("status", "ok");
    }
}
