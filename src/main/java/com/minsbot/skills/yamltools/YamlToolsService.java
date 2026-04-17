package com.minsbot.skills.yamltools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class YamlToolsService {

    private final ObjectMapper jsonPretty = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ObjectMapper jsonCompact = new ObjectMapper();

    public Map<String, Object> validate(String yaml) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            new Yaml().load(yaml);
            out.put("valid", true);
        } catch (Exception e) {
            out.put("valid", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    public String yamlToJson(String yaml) throws Exception {
        Object parsed = new Yaml().load(yaml);
        return jsonPretty.writeValueAsString(parsed);
    }

    public String jsonToYaml(String json) throws Exception {
        Object parsed = jsonCompact.readValue(json, Object.class);
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        return new Yaml(opts).dump(parsed);
    }

    public String prettyPrint(String yaml) throws Exception {
        Object parsed = new Yaml().load(yaml);
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        return new Yaml(opts).dump(parsed);
    }
}
