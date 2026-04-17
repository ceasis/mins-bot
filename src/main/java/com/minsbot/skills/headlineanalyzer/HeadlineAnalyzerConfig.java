package com.minsbot.skills.headlineanalyzer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HeadlineAnalyzerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.headlineanalyzer")
    public HeadlineAnalyzerProperties headlineAnalyzerProperties() { return new HeadlineAnalyzerProperties(); }
    public static class HeadlineAnalyzerProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
