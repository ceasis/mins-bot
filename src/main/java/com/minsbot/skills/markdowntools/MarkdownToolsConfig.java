package com.minsbot.skills.markdowntools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarkdownToolsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.markdowntools")
    public MarkdownToolsProperties markdownToolsProperties() { return new MarkdownToolsProperties(); }

    public static class MarkdownToolsProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
