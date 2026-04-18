package com.minsbot.skills.upcoming;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UpcomingConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.upcoming")
    public UpcomingProperties upcomingProperties() {
        return new UpcomingProperties();
    }

    public static class UpcomingProperties {
        private boolean enabled = true;
        private int defaultDays = 3;
        private int maxDays = 14;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDefaultDays() { return defaultDays; }
        public void setDefaultDays(int d) { this.defaultDays = d; }
        public int getMaxDays() { return maxDays; }
        public void setMaxDays(int d) { this.maxDays = d; }
    }
}
