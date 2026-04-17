package com.minsbot.skills.cronvalidator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CronValidatorConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.cronvalidator")
    public CronValidatorProperties cronValidatorProperties() { return new CronValidatorProperties(); }

    public static class CronValidatorProperties {
        private boolean enabled = false;
        private int maxNextRuns = 50;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxNextRuns() { return maxNextRuns; }
        public void setMaxNextRuns(int maxNextRuns) { this.maxNextRuns = maxNextRuns; }
    }
}
