package com.botsfer.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Properties;

/**
 * Loads {@code spring.ai.openai.api-key} from env vars or application-secrets.properties
 * before Spring AI runs, so the key is found regardless of working directory or config.import order.
 */
public class OpenAiSecretsLoader implements EnvironmentPostProcessor, Ordered {

    private static final String PROP_KEY = "spring.ai.openai.api-key";
    private static final String ENV_OPENAI = "OPENAI_API_KEY";
    private static final String ENV_SPRING_AI = "SPRING_AI_OPENAI_API_KEY";
    private static final String SECRETS_FILE = "application-secrets.properties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Always resolve from env or file and inject — ensures Spring AI sees the key even when
        // application.properties placeholder resolution order would leave it unset for auto-config.
        String key = System.getenv(ENV_OPENAI);
        if (key == null || key.isBlank()) {
            key = System.getenv(ENV_SPRING_AI);
        }
        if (key == null || key.isBlank()) {
            key = loadFromFile(SECRETS_FILE);
        }
        if (key != null && !key.isBlank()) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("openaiSecretsLoader", Collections.singletonMap(PROP_KEY, key)));
        }
    }

    private static String loadFromFile(String fileName) {
        String userDir = System.getProperty("user.dir");
        Resource[] candidates = {
                new FileSystemResource(fileName),
                new FileSystemResource(userDir + "/" + fileName),
                new ClassPathResource(fileName)
        };
        for (Resource r : candidates) {
            if (!r.exists()) continue;
            try (InputStream in = r.getInputStream()) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty(PROP_KEY);
                if (v != null && !v.isBlank()) return v.trim();
            } catch (IOException ignored) { /* try next */ }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
