package com.minsbot;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/setup/secrets")
public class SetupSecretsController {

    private final SetupSecretsService setupSecretsService;

    public SetupSecretsController(SetupSecretsService setupSecretsService) {
        this.setupSecretsService = setupSecretsService;
    }

    @GetMapping
    public Map<String, Object> get(HttpServletRequest request) {
        requireLocal(request);
        return setupSecretsService.buildSetupPayload();
    }

    @PostMapping
    public Map<String, String> save(@RequestBody(required = false) SetupSecretsUpdate body, HttpServletRequest request) {
        requireLocal(request);
        Map<String, String> set = body != null && body.set() != null ? body.set() : Map.of();
        List<String> unset = body != null ? body.unset() : null;
        try {
            setupSecretsService.applyUpdates(set, unset != null ? unset : List.of());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not write secrets file: " + e.getMessage());
        }
        return Map.of("status", "ok", "message",
                "Saved. Restart Mins Bot if chat, TTS, or search still use old keys.");
    }

    private static void requireLocal(HttpServletRequest request) {
        String a = request.getRemoteAddr();
        if (a == null) return;
        if ("127.0.0.1".equals(a) || "::1".equals(a) || "0:0:0:0:0:0:0:1".equals(a)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Setup is only available from this machine");
    }

    public record SetupSecretsUpdate(
            @JsonProperty("set") Map<String, String> set,
            @JsonProperty("unset") List<String> unset) {}
}
