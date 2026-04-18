package com.minsbot.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * LLM-accessible integration tools. The LLM can list integrations, check status,
 * and execute authenticated REST calls against any configured service.
 */
@Component
public class IntegrationCallTools {

    private final ObjectMapper mapper = new ObjectMapper();
    private final IntegrationRegistry registry;
    private final IntegrationCredentialStore credentials;
    private final IntegrationCallService caller;

    public IntegrationCallTools(IntegrationRegistry registry,
                                IntegrationCredentialStore credentials,
                                IntegrationCallService caller) {
        this.registry = registry;
        this.credentials = credentials;
        this.caller = caller;
    }

    @Tool(description = "List all supported third-party integrations with their configuration status. "
            + "Use to discover what services the user can connect to (Stripe, Notion, GitHub, Slack, etc.). "
            + "Optionally filter by category (e.g. 'CRM & marketing', 'Developer & issue tracking').")
    public String listIntegrations(
            @ToolParam(description = "Optional category filter (empty string for all)") String category) {
        List<Map<String, Object>> out = new ArrayList<>();
        Collection<IntegrationRegistry.Integration> source = (category == null || category.isBlank())
                ? registry.all()
                : registry.byCategory(category);
        for (IntegrationRegistry.Integration i : source) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", i.id());
            entry.put("name", i.name());
            entry.put("category", i.category());
            entry.put("authType", i.authType().name());
            entry.put("configured", credentials.isConfigured(i.id()));
            out.add(entry);
        }
        return toJson(Map.of("count", out.size(), "integrations", out));
    }

    @Tool(description = "Call a third-party integration's REST API. "
            + "The integration must already be configured with credentials via /api/integrations-api/<id>/credentials. "
            + "Example: callIntegration('stripe', 'GET', '/customers', '') lists Stripe customers. "
            + "Returns JSON with status, elapsedMs, headers, and body.")
    public String callIntegration(
            @ToolParam(description = "Integration ID (e.g. 'stripe', 'notion', 'github', 'linear')") String integrationId,
            @ToolParam(description = "HTTP method: GET, POST, PUT, DELETE, PATCH") String method,
            @ToolParam(description = "API path (e.g. '/customers', '/v1/databases/abc/query') — prepended with the service's base URL") String path,
            @ToolParam(description = "Request body for POST/PUT/PATCH (empty string for GET/DELETE)") String body) {
        try {
            Map<String, Object> result = caller.call(integrationId, method, path, null, body);
            return toJson(result);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Get configuration details for a specific integration, including the auth type, "
            + "base URL, and docs link. Useful for guiding the user through setup.")
    public String getIntegrationInfo(
            @ToolParam(description = "Integration ID") String integrationId) {
        return registry.get(integrationId)
                .map(i -> toJson(Map.of(
                        "id", i.id(),
                        "name", i.name(),
                        "category", i.category(),
                        "authType", i.authType().name(),
                        "baseUrl", i.baseUrl(),
                        "docsUrl", i.docsUrl(),
                        "propertyPrefix", i.propertyPrefix(),
                        "configured", credentials.isConfigured(i.id())
                )))
                .orElse("Unknown integration: " + integrationId);
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return String.valueOf(obj); }
    }
}
