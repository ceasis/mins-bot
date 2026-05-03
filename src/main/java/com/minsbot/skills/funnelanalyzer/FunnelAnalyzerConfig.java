package com.minsbot.skills.funnelanalyzer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FunnelAnalyzerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.funnelanalyzer")
    public FunnelAnalyzerProperties funnelAnalyzerProperties() { return new FunnelAnalyzerProperties(); }

    public static class FunnelAnalyzerProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
