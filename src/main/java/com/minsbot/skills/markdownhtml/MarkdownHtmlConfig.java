package com.minsbot.skills.markdownhtml;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarkdownHtmlConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.markdownhtml")
    public MarkdownHtmlProperties markdownHtmlProperties() { return new MarkdownHtmlProperties(); }
    public static class MarkdownHtmlProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
