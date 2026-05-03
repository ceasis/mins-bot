package com.minsbot.skills.abtestplanner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AbTestPlannerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.abtestplanner")
    public AbTestPlannerProperties abTestPlannerProperties() { return new AbTestPlannerProperties(); }

    public static class AbTestPlannerProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
