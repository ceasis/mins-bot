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
        String model = body != null ? body.get("model") : null;
        try {
            String id = agentService.startAgent(mission, model);
            return Map.of("id", id, "status", "QUEUED");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping(value = "/start-random", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> startRandom(@RequestBody(required = false) Map<String, String> body) {
        String model = body != null ? body.get("model") : null;
        String[] missions = {
            "Search for the latest AI news and summarize the top 5 stories",
            "Find interesting programming tips and create a summary",
            "Research the top trending GitHub repositories this week",
            "Search for productivity tips for developers and compile them",
            "Find the latest tech product releases and summarize them",
            "Research healthy desk exercises and create a quick guide",
            "Search for interesting science discoveries from this month",
            "Find the best free developer tools released recently",
            "Research upcoming tech events and conferences",
            "Search for motivational quotes and compile the best 10"
        };
        String mission = missions[new java.util.Random().nextInt(missions.length)];
        try {
            String id = agentService.startAgent(mission, model);
            return Map.of("id", id, "status", "QUEUED", "mission", mission);
        } catch (Exception e) {
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
