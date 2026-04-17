package com.minsbot.skills.loganalyzer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LogAnalyzerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.loganalyzer")
    public LogAnalyzerProperties logAnalyzerProperties() { return new LogAnalyzerProperties(); }
    public static class LogAnalyzerProperties {
        private boolean enabled = false;
        private int maxLines = 200_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxLines() { return maxLines; }
        public void setMaxLines(int maxLines) { this.maxLines = maxLines; }
    }
}
