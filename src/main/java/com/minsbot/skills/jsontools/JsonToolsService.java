package com.minsbot.skills.jsontools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JsonToolsService {

    private final ObjectMapper pretty = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ObjectMapper compact = new ObjectMapper();

    public Map<String, Object> validate(String input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            compact.readTree(input);
            result.put("valid", true);
        } catch (JsonProcessingException e) {
            result.put("valid", false);
            result.put("error", e.getOriginalMessage());
            if (e.getLocation() != null) {
                result.put("line", e.getLocation().getLineNr());
                result.put("column", e.getLocation().getColumnNr());
            }
        }
        return result;
    }

    public String prettyPrint(String input) throws JsonProcessingException {
        JsonNode node = compact.readTree(input);
        return pretty.writeValueAsString(node);
    }

    public String minify(String input) throws JsonProcessingException {
        JsonNode node = compact.readTree(input);
        return compact.writeValueAsString(node);
    }

    public Map<String, Object> describe(String input) throws JsonProcessingException {
        JsonNode node = compact.readTree(input);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", typeOf(node));
        if (node.isObject()) {
            result.put("fieldCount", node.size());
            result.put("fields", iterToList(node.fieldNames()));
        } else if (node.isArray()) {
            result.put("length", node.size());
            if (!node.isEmpty()) {
                result.put("firstElementType", typeOf(node.get(0)));
            }
        } else {
            result.put("value", node.asText());
        }
        return result;
    }

    private static String typeOf(JsonNode n) {
        if (n.isObject()) return "object";
        if (n.isArray()) return "array";
        if (n.isTextual()) return "string";
        if (n.isNumber()) return "number";
        if (n.isBoolean()) return "boolean";
        if (n.isNull()) return "null";
        return "unknown";
    }

    private static java.util.List<String> iterToList(java.util.Iterator<String> iter) {
        java.util.List<String> list = new java.util.ArrayList<>();
        while (iter.hasNext()) list.add(iter.next());
        return list;
    }
}
