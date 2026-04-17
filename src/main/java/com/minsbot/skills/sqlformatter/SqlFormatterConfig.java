package com.minsbot.skills.sqlformatter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SqlFormatterConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.sqlformatter")
    public SqlFormatterProperties sqlFormatterProperties() { return new SqlFormatterProperties(); }

    public static class SqlFormatterProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
