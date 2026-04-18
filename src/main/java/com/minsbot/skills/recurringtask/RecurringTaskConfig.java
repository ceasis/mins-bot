package com.minsbot.skills.recurringtask;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RecurringTaskConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.recurringtask")
    public RecurringTaskProperties recurringTaskProperties() {
        return new RecurringTaskProperties();
    }

    public static class RecurringTaskProperties {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
