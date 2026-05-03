package com.minsbot.skills.systemstats;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SystemStatsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.systemstats")
    public SystemStatsProperties systemStatsProperties() { return new SystemStatsProperties(); }
    public static class SystemStatsProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
