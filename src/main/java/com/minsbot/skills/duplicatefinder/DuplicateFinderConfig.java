package com.minsbot.skills.duplicatefinder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DuplicateFinderConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.duplicatefinder")
    public DuplicateFinderProperties duplicateFinderProperties() { return new DuplicateFinderProperties(); }
    public static class DuplicateFinderProperties {
        private boolean enabled = false;
        private long minBytes = 1_000_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public long getMinBytes() { return minBytes; }
        public void setMinBytes(long v) { this.minBytes = v; }
    }
}
