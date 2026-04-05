package com.minsbot.config;

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
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Loads selected secrets from env or {@code application-secrets.properties} before the rest of
 * configuration binds. Classpath and working-directory secrets are both supported (see
 * {@code application.properties} — {@code spring.config.import} does not use the classpath).
 * <p>
 * Injected keys: OpenAI, Fish Audio, optional web search API keys ({@code serper.api.key}, {@code serpapi.api.key}).
 */
public class OpenAiSecretsLoader implements EnvironmentPostProcessor, Ordered {

    private static final String OPENAI_PROP = "spring.ai.openai.api-key";
    private static final String ENV_OPENAI = "OPENAI_API_KEY";
    private static final String ENV_SPRING_AI = "SPRING_AI_OPENAI_API_KEY";
    private static final String FISH_PROP = "fish.audio.api.key";
    private static final String ENV_FISH = "FISH_AUDIO_API_KEY";
    /** Fish voice model id (from {@code https://fish.audio/m/<id>} when viewing a voice). */
    private static final String FISH_REF_PROP = "fish.audio.reference.id";
    private static final String ENV_FISH_REF = "FISH_AUDIO_REFERENCE_ID";
    private static final String SERPER_PROP = "serper.api.key";
    private static final String ENV_SERPER = "SERPER_API_KEY";
    private static final String SERPAPI_PROP = "serpapi.api.key";
    private static final String ENV_SERPAPI = "SERPAPI_API_KEY";
    private static final String SECRETS_FILE = "application-secrets.properties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Properties fileProps = loadSecretsProperties(SECRETS_FILE);
        Map<String, Object> injected = new HashMap<>();

        String openai = System.getenv(ENV_OPENAI);
        if (openai == null || openai.isBlank()) {
            openai = System.getenv(ENV_SPRING_AI);
        }
        if ((openai == null || openai.isBlank()) && fileProps != null) {
            String v = fileProps.getProperty(OPENAI_PROP);
            if (v != null && !v.isBlank()) openai = v.trim();
        }
        if (openai != null && !openai.isBlank()) {
            injected.put(OPENAI_PROP, openai.trim());
        }

        String fish = System.getenv(ENV_FISH);
        if ((fish == null || fish.isBlank()) && fileProps != null) {
            String v = fileProps.getProperty(FISH_PROP);
            if (v != null && !v.isBlank()) fish = v.trim();
        }
        if (fish != null && !fish.isBlank()) {
            injected.put(FISH_PROP, fish.trim());
        }

        String fishRef = System.getenv(ENV_FISH_REF);
        if ((fishRef == null || fishRef.isBlank()) && fileProps != null) {
            String v = fileProps.getProperty(FISH_REF_PROP);
            if (v != null && !v.isBlank()) fishRef = v.trim();
        }
        if (fishRef != null && !fishRef.isBlank()) {
            injected.put(FISH_REF_PROP, fishRef.trim());
        }

        String serper = System.getenv(ENV_SERPER);
        if ((serper == null || serper.isBlank()) && fileProps != null) {
            String v = fileProps.getProperty(SERPER_PROP);
            if (v != null && !v.isBlank()) serper = v.trim();
        }
        if (serper != null && !serper.isBlank()) {
            injected.put(SERPER_PROP, serper.trim());
        }

        String serpapi = System.getenv(ENV_SERPAPI);
        if ((serpapi == null || serpapi.isBlank()) && fileProps != null) {
            String v = fileProps.getProperty(SERPAPI_PROP);
            if (v != null && !v.isBlank()) serpapi = v.trim();
        }
        if (serpapi != null && !serpapi.isBlank()) {
            injected.put(SERPAPI_PROP, serpapi.trim());
        }

        if (!injected.isEmpty()) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("applicationSecretsLoader", injected));
        }
    }

    /** First readable secrets file among cwd, project dir, classpath; full properties or null. */
    private static Properties loadSecretsProperties(String fileName) {
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
                return p;
            } catch (IOException ignored) { /* try next */ }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
