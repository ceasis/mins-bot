package com.minsbot.skills.scheduledtaskctl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScheduledTaskCtlConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.scheduledtaskctl")
    public ScheduledTaskCtlProperties scheduledTaskCtlProperties() { return new ScheduledTaskCtlProperties(); }
    public static class ScheduledTaskCtlProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
