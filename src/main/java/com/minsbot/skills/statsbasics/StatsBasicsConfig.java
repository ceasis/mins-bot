package com.minsbot.skills.statsbasics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StatsBasicsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.statsbasics")
    public StatsBasicsProperties statsBasicsProperties() { return new StatsBasicsProperties(); }

    public static class StatsBasicsProperties {
        private boolean enabled = false;
        private int maxValues = 1_000_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxValues() { return maxValues; }
        public void setMaxValues(int maxValues) { this.maxValues = maxValues; }
    }
}
