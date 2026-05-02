package com.minsbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * When the bot is launched from the project tree (i.e. {@code src/main/resources/static}
 * exists alongside the working directory), serve {@code /css}, {@code /js},
 * and the rest of {@code /static/**} directly off disk with zero browser
 * caching. This means a CSS or JS edit takes effect on the next browser
 * refresh — no {@code mvn package}, no JVM restart.
 *
 * <p>When launched from a packaged jar (no {@code src/} folder), this config
 * does nothing and Spring's default classpath-static handler takes over.
 *
 * <p>Why this exists: the user shouldn't have to wait for a full Maven
 * rebuild + bot restart to see a CSS tweak.
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StaticResourceConfig.class);

    private static final Path DEV_STATIC_ROOT =
            Paths.get("src", "main", "resources", "static").toAbsolutePath();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!Files.isDirectory(DEV_STATIC_ROOT)) {
            log.info("[StaticResources] Dev static folder not found ({}). "
                    + "Serving static resources from the classpath.", DEV_STATIC_ROOT);
            return;
        }
        // toUri() produces a proper "file:///C:/.../static/" URL — required
        // by Spring's ResourceLocation resolver on Windows.
        String base = DEV_STATIC_ROOT.toUri().toString();
        if (!base.endsWith("/")) base += "/";
        log.info("[StaticResources] DEV MODE — serving static assets from {}", base);

        // Spring's resource handler strips the URL pattern prefix and resolves
        // the remainder against the location. So /js/foo.js with location
        // static/ resolves to static/foo.js — WRONG (file is static/js/foo.js).
        // Register each subdirectory to its matching location explicitly.
        register(registry, "/css/**",    base + "css/",    "classpath:/static/css/");
        register(registry, "/js/**",     base + "js/",     "classpath:/static/js/");
        register(registry, "/img/**",    base + "img/",    "classpath:/static/img/");
        register(registry, "/icons/**",  base + "icons/",  "classpath:/static/icons/");
        register(registry, "/sounds/**", base + "sounds/", "classpath:/static/sounds/");
    }

    private static void register(ResourceHandlerRegistry registry, String pattern,
                                 String fileLoc, String classpathLoc) {
        registry.addResourceHandler(pattern)
                // Period 0 disables ETag/Last-Modified caching so a JS/CSS edit
                // is picked up on next request, even within one session.
                .addResourceLocations(fileLoc, classpathLoc)
                .setCachePeriod(0);
    }
}
