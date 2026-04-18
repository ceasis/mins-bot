package com.minsbot.skills.queryexpander;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueryExpanderConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.queryexpander")
    public QueryExpanderProperties queryExpanderProperties() { return new QueryExpanderProperties(); }

    public static class QueryExpanderProperties {
        private boolean enabled = true;  // On by default — this is a helper, not a side-effect tool
        private int maxInputChars = 2000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxInputChars() { return maxInputChars; }
        public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
    }
}
