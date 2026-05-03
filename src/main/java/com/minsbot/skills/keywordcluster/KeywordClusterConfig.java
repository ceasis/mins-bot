package com.minsbot.skills.keywordcluster;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KeywordClusterConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.keywordcluster")
    public KeywordClusterProperties keywordClusterProperties() { return new KeywordClusterProperties(); }

    public static class KeywordClusterProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
