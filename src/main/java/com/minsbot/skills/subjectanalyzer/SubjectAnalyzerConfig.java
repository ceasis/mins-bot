package com.minsbot.skills.subjectanalyzer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SubjectAnalyzerConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.subjectanalyzer")
    public SubjectAnalyzerProperties subjectAnalyzerProperties() {
        return new SubjectAnalyzerProperties();
    }

    public static class SubjectAnalyzerProperties {
        private boolean enabled = false;
        private int maxLength = 500;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxLength() { return maxLength; }
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    }
}
