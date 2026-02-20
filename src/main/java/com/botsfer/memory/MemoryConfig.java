package com.botsfer.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MemoryConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.memory")
    public MemoryProperties memoryProperties() {
        return new MemoryProperties();
    }

    public static class MemoryProperties {
        private boolean enabled = true;
        private String basePath = "memory";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) { this.basePath = basePath; }
    }
}
